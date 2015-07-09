/*******************************************************************************
 * Copyright 2015, The IKANOW Open Source Project.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.ikanow.aleph2.search_service.elasticsearch.services;

import java.io.IOException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.StreamSupport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.admin.indices.template.get.GetIndexTemplatesRequest;
import org.elasticsearch.action.admin.indices.template.get.GetIndexTemplatesResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.xcontent.XContentBuilder;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Inject;
import com.google.inject.Module;
import com.ikanow.aleph2.data_model.interfaces.data_services.IColumnarService;
import com.ikanow.aleph2.data_model.interfaces.data_services.ISearchIndexService;
import com.ikanow.aleph2.data_model.interfaces.data_services.ITemporalService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.IExtraDependencyLoader;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean.ColumnarSchemaBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean.SearchIndexSchemaBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean.TemporalSchemaBean;
import com.ikanow.aleph2.data_model.objects.shared.BasicMessageBean;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils;
import com.ikanow.aleph2.data_model.utils.ErrorUtils;
import com.ikanow.aleph2.data_model.utils.Lambdas;
import com.ikanow.aleph2.data_model.utils.TimeUtils;
import com.ikanow.aleph2.search_service.elasticsearch.data_model.ElasticsearchIndexServiceConfigBean;
import com.ikanow.aleph2.search_service.elasticsearch.data_model.ElasticsearchIndexServiceConfigBean.SearchIndexSchemaDefaultBean.CollidePolicy;
import com.ikanow.aleph2.search_service.elasticsearch.module.ElasticsearchIndexServiceModule;
import com.ikanow.aleph2.search_service.elasticsearch.utils.ElasticsearchIndexConfigUtils;
import com.ikanow.aleph2.search_service.elasticsearch.utils.ElasticsearchIndexUtils;
import com.ikanow.aleph2.shared.crud.elasticsearch.data_model.ElasticsearchContext;
import com.ikanow.aleph2.shared.crud.elasticsearch.services.ElasticsearchCrudService.CreationPolicy;
import com.ikanow.aleph2.shared.crud.elasticsearch.services.IElasticsearchCrudServiceFactory;
import com.ikanow.aleph2.shared.crud.elasticsearch.utils.ElasticsearchContextUtils;

import fj.data.Validation;

/** Elasticsearch implementation of the SearchIndexService/TemporalService/ColumnarService
 * @author Alex
 *
 */
public class ElasticsearchIndexService implements ISearchIndexService, ITemporalService, IColumnarService, IExtraDependencyLoader {
	final static protected Logger _logger = LogManager.getLogger();

	protected final IElasticsearchCrudServiceFactory _crud_factory;
	protected final ElasticsearchIndexServiceConfigBean _config;
	
	protected final static ObjectMapper _mapper = BeanTemplateUtils.configureMapper(Optional.empty());
	
	protected final ConcurrentHashMap<String, Date> _bucket_template_cache = new ConcurrentHashMap<>();
	
	/** Guice generated constructor
	 * @param crud_factory
	 */
	@Inject
	public ElasticsearchIndexService(
			final IElasticsearchCrudServiceFactory crud_factory,
			final ElasticsearchIndexServiceConfigBean configuration)
	{
		_crud_factory = crud_factory;
		_config = configuration;
	}
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.ISearchIndexService#getCrudService(java.lang.Class, com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean)
	 */
	@Override
	public <O> Optional<ICrudService<O>> getCrudService(final Class<O> clazz, final DataBucketBean bucket) {
		
		//TODO (ALEPH-14): Need to be able to configure batch settings (get batch service before returning, update defaults)
		
		// There's two different cases
		// 1) Multi-bucket - equivalent to the other version of getCrudService
		// 2) Single bucket - a read/write bucket
		
		if ((null != bucket.multi_bucket_children()) && !bucket.multi_bucket_children().isEmpty()) {
			return getCrudService(clazz, bucket.multi_bucket_children());
		}
		
		// If single bucket, is the search index service enabled?
		if (!Optional.ofNullable(bucket.data_schema())
				.map(ds -> ds.search_index_schema())
					.map(sis -> Optional.ofNullable(sis.enabled())
					.orElse(true))
			.orElse(false))
		{
			return Optional.empty();
		}
		
		// OK so it's a legit single bucket ... first question ... does this already exist?
		
		final ElasticsearchIndexServiceConfigBean schema_config = ElasticsearchIndexConfigUtils.buildConfigBeanFromSchema(bucket, _config, _mapper);
		
		final Optional<String> type = Optional.ofNullable(schema_config.search_technology_override()).map(t -> t.type_name_or_prefix());
		final String index_type = CollidePolicy.new_type == Optional.ofNullable(schema_config.search_technology_override())
									.map(t -> t.collide_policy()).orElse(CollidePolicy.new_type)
										? "_default_"
										: type.orElse(ElasticsearchIndexServiceConfigBean.DEFAULT_FIXED_TYPE_NAME);
		
		this.handlePotentiallyNewIndex(bucket, schema_config, index_type);
		
		// Need to decide a) if it's a time based index b) an auto type index
		// And then build the context from there
		
		final Validation<String, ChronoUnit> time_period = TimeUtils.getTimePeriod(Optional.ofNullable(schema_config.temporal_technology_override())
																.map(t -> t.grouping_time_period()).orElse(""));

		// Index
		final String index_base_name = ElasticsearchIndexUtils.getBaseIndexName(bucket);
		final ElasticsearchContext.IndexContext.ReadWriteIndexContext index_context = time_period.validation(
				fail -> new ElasticsearchContext.IndexContext.ReadWriteIndexContext.FixedRwIndexContext(index_base_name)
				, 
				success -> new ElasticsearchContext.IndexContext.ReadWriteIndexContext.TimedRwIndexContext(index_base_name + ElasticsearchContextUtils.getIndexSuffix(success), 
									Optional.ofNullable(schema_config.temporal_technology_override().time_field()))
				);
		
		// Type
		final ElasticsearchContext.TypeContext.ReadWriteTypeContext type_context =
				CollidePolicy.new_type == Optional.ofNullable(schema_config.search_technology_override())
						.map(t -> t.collide_policy()).orElse(CollidePolicy.new_type)
					? new ElasticsearchContext.TypeContext.ReadWriteTypeContext.AutoRwTypeContext(Optional.empty(), type)
					: new ElasticsearchContext.TypeContext.ReadWriteTypeContext.FixedRwTypeContext(type.orElse(ElasticsearchIndexServiceConfigBean.DEFAULT_FIXED_TYPE_NAME));
		
		return Optional.of(_crud_factory.getElasticsearchCrudService(clazz,
								new ElasticsearchContext.ReadWriteContext(_crud_factory.getClient(), index_context, type_context),
								Optional.empty(), 
								CreationPolicy.OPTIMIZED, 
								Optional.empty(), Optional.empty(), Optional.empty()));
	}

	//TODO (ALEPH-14): Handle bucket deletion (eg remove template)
	
	/** Checks if an index/set-of-indexes spawned from a bucket
	 * @param bucket
	 */
	protected void handlePotentiallyNewIndex(final DataBucketBean bucket, final ElasticsearchIndexServiceConfigBean schema_config, final String index_type) {
		final Date current_template_time = _bucket_template_cache.get(bucket._id());		
		if ((null == current_template_time) || current_template_time.before(Optional.ofNullable(bucket.modified()).orElse(new Date()))) {			
			try {
				final XContentBuilder mapping = ElasticsearchIndexUtils.createIndexMapping(bucket, schema_config, _mapper, index_type);
				
				final GetIndexTemplatesRequest gt = new GetIndexTemplatesRequest().names(ElasticsearchIndexUtils.getBaseIndexName(bucket));
				final GetIndexTemplatesResponse gtr = _crud_factory.getClient().admin().indices().getTemplates(gt).actionGet();
				
				if (gtr.getIndexTemplates().isEmpty() 
						|| 
					!mappingsAreEquivalent(gtr.getIndexTemplates().get(0), _mapper.readTree(mapping.bytes().toUtf8()), _mapper))
				{
					// If no template, or it's changed, then update
					_crud_factory.getClient().admin().indices().preparePutTemplate(ElasticsearchIndexUtils.getBaseIndexName(bucket)).setSource(mapping).execute().actionGet();
					
					_logger.info(ErrorUtils.get("Updated mapping for bucket={0}, base_index={1}", bucket._id()));
				}				
			}
			catch (Exception e) {
				_logger.error(ErrorUtils.getLongForm("Error updating mapper bucket={1} err={0}", e, bucket._id()));
			}
			_bucket_template_cache.put(bucket._id(), bucket.modified());			
		}
	}
	
	/** Check if a new mapping based on a schema is equivalent to a mapping previously stored (from a schema) 
	 * @param stored_mapping
	 * @param new_mapping
	 * @param mapper
	 * @return
	 * @throws JsonProcessingException
	 * @throws IOException
	 */
	protected static boolean mappingsAreEquivalent(final IndexTemplateMetaData stored_mapping, final JsonNode new_mapping, final ObjectMapper mapper) throws JsonProcessingException, IOException {		
		
		final ObjectNode stored_json_mappings = StreamSupport.stream(stored_mapping.mappings().spliterator(), false)
													.reduce(mapper.createObjectNode(), 
															Lambdas.wrap_u((acc, kv) -> (ObjectNode) acc.setAll((ObjectNode) mapper.readTree(kv.value.string()))), 
															(acc1, acc2) -> acc1); // (can't occur)
		
		final JsonNode new_json_mappings = Optional.ofNullable(new_mapping.get("mappings")).orElse(mapper.createObjectNode());
		
		final JsonNode stored_json_settings = mapper.convertValue(
												Optional.ofNullable(stored_mapping.settings()).orElse(ImmutableSettings.settingsBuilder().build())
													.getAsMap(), JsonNode.class);

		final JsonNode new_json_settings = Optional.ofNullable(new_mapping.get("settings")).orElse(mapper.createObjectNode());
				
		final ObjectNode stored_json_aliases = StreamSupport.stream(Optional.ofNullable(stored_mapping.aliases()).orElse(ImmutableOpenMap.of()).spliterator(), false)
				.reduce(mapper.createObjectNode(), 
						Lambdas.wrap_u((acc, kv) -> (ObjectNode) acc.set(kv.key, kv.value.filteringRequired()
								? mapper.createObjectNode().set("filter", mapper.readTree(kv.value.filter().string()))
								: mapper.createObjectNode()
								)),
						(acc1, acc2) -> acc1); // (can't occur)
		
		final JsonNode new_json_aliases = Optional.ofNullable(new_mapping.get("aliases")).orElse(mapper.createObjectNode());
		
		//DEBUG
//		System.out.println("1a: " + stored_json_mappings);
//		System.out.println("1b: " + new_json_mappings);
//		System.out.println(" 1: " + stored_json_mappings.equals(new_json_mappings));
//		System.out.println("2a: " + stored_json_settings);
//		System.out.println("2b: " + new_json_settings);
//		System.out.println(" 2: " + stored_json_settings.equals(new_json_settings));
//		System.out.println("3a: " + stored_json_aliases);
//		System.out.println("3b: " + new_json_aliases);
//		System.out.println(" 3: " + stored_json_aliases.equals(new_json_aliases));
		
		return stored_json_mappings.equals(new_json_mappings) && stored_json_settings.equals(new_json_settings) && stored_json_aliases.equals(new_json_aliases);		
	}
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.ISearchIndexService#getCrudService(java.lang.Class, java.util.Collection)
	 */
	@Override
	public <O> Optional<ICrudService<O>> getCrudService(final Class<O> clazz, final Collection<String> buckets) {
		
		//TODO (ALEPH-14): expand aliases
		
		// Grab all the _existing_ buckets 
		
		//TODO (ALEPH-14): Handle the read only case
		
		throw new RuntimeException("Read-only interface: Not yet implemented");
	}
	
	////////////////////////////////////////////////////////////////////////////////

	// ES CLIENT ACCESS	
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.ISearchIndexService#getUnderlyingPlatformDriver(java.lang.Class, java.util.Optional)
	 */
	@SuppressWarnings("unchecked")
	@Override
	public <T> Optional<T> getUnderlyingPlatformDriver(final Class<T> driver_class, final Optional<String> driver_options) {
		if (Client.class.isAssignableFrom(driver_class)) {
			return (Optional<T>) Optional.of(_crud_factory.getClient());
		}
		return Optional.empty();
	}

	////////////////////////////////////////////////////////////////////////////////

	// VALIDATION	
	
	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.IColumnarService#validateSchema(com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean.ColumnarSchemaBean)
	 */
	@Override
	public List<BasicMessageBean> validateSchema(final ColumnarSchemaBean schema, final DataBucketBean bucket) {
		// (Performed under search index schema)
		return Collections.emptyList();
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.ITemporalService#validateSchema(com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean.TemporalSchemaBean)
	 */
	@Override
	public List<BasicMessageBean> validateSchema(final TemporalSchemaBean schema, final DataBucketBean bucket) {
		// (time buckets aka default schema options are already validated, nothing else to do)
		
		return Collections.emptyList();
	}

	/* (non-Javadoc)
	 * @see com.ikanow.aleph2.data_model.interfaces.data_services.ISearchIndexService#validateSchema(com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean.SearchIndexSchemaBean)
	 */
	@Override
	public List<BasicMessageBean> validateSchema(final SearchIndexSchemaBean schema, final DataBucketBean bucket) {
		try {
			final ElasticsearchIndexServiceConfigBean schema_config = ElasticsearchIndexConfigUtils.buildConfigBeanFromSchema(bucket, _config, _mapper);
			
			final Optional<String> type = Optional.ofNullable(schema_config.search_technology_override()).map(t -> t.type_name_or_prefix());
			final String index_type = CollidePolicy.new_type == Optional.ofNullable(schema_config.search_technology_override())
										.map(t -> t.collide_policy()).orElse(CollidePolicy.new_type)
											? "_default_"
											: type.orElse(ElasticsearchIndexServiceConfigBean.DEFAULT_FIXED_TYPE_NAME);
			
			final XContentBuilder mapping = ElasticsearchIndexUtils.createIndexMapping(bucket, schema_config, _mapper, index_type);
			if (is_verbose(schema)) {
				final BasicMessageBean success = new BasicMessageBean(
						new Date(), true, bucket.full_name(), "validateSchema", null, 
						mapping.bytes().toUtf8(), null);
						
				return Arrays.asList(success);
				
			}
			else {
				return Collections.emptyList();
			}
		}
		catch (Throwable e) {
			final BasicMessageBean err = new BasicMessageBean(
					new Date(), false, bucket.full_name(), "validateSchema", null, 
					ErrorUtils.getLongForm("{0}", e), null);
					
			return Arrays.asList(err);
		}
	}
	protected static boolean is_verbose(final SearchIndexSchemaBean schema) {
		return Optional.ofNullable(schema)
					.map(SearchIndexSchemaBean::technology_override_schema)
					.map(m -> m.get("verbose"))
					.filter(b -> b.toString().equalsIgnoreCase("true") || b.toString().equals("1"))
					.map(b -> true) // (if we're here then must be true/1)
				.orElse(false);
	}
	
	////////////////////////////////////////////////////////////////////////////////
	
	/** This service needs to load some additional classes via Guice. Here's the module that defines the bindings
	 * @return
	 */
	public static List<Module> getExtraDependencyModules() {
		return Arrays.asList((Module)new ElasticsearchIndexServiceModule());
	}
	
	public void youNeedToImplementTheStaticFunctionCalled_getExtraDependencyModules() {
		//(done!)
	}

	@Override
	public Collection<Object> getUnderlyingArtefacts() {
		return Arrays.asList(this, _crud_factory);
	}

}
