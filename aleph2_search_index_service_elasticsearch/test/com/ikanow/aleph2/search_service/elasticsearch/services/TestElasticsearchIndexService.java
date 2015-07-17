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

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsResponse;
import org.elasticsearch.action.admin.indices.template.get.GetIndexTemplatesRequest;
import org.elasticsearch.action.admin.indices.template.get.GetIndexTemplatesResponse;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.collect.ImmutableSet;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Charsets;
import com.google.common.io.Resources;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService;
import com.ikanow.aleph2.data_model.interfaces.shared_services.ICrudService.IBatchSubservice;
import com.ikanow.aleph2.data_model.objects.data_import.DataBucketBean;
import com.ikanow.aleph2.data_model.objects.data_import.DataSchemaBean;
import com.ikanow.aleph2.data_model.objects.shared.BasicMessageBean;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils;
import com.ikanow.aleph2.data_model.utils.Lambdas;
import com.ikanow.aleph2.search_service.elasticsearch.data_model.ElasticsearchIndexServiceConfigBean;
import com.ikanow.aleph2.search_service.elasticsearch.utils.ElasticsearchIndexConfigUtils;
import com.ikanow.aleph2.search_service.elasticsearch.utils.ElasticsearchIndexUtils;
import com.ikanow.aleph2.shared.crud.elasticsearch.data_model.ElasticsearchConfigurationBean;
import com.ikanow.aleph2.shared.crud.elasticsearch.data_model.ElasticsearchContext;
import com.ikanow.aleph2.shared.crud.elasticsearch.services.ElasticsearchCrudServiceFactory;
import com.ikanow.aleph2.shared.crud.elasticsearch.services.IElasticsearchCrudServiceFactory;
import com.ikanow.aleph2.shared.crud.elasticsearch.services.MockElasticsearchCrudServiceFactory;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

public class TestElasticsearchIndexService {

	public static ObjectMapper _mapper = BeanTemplateUtils.configureMapper(Optional.empty());	
	
	protected MockElasticsearchIndexService _index_service;
	protected IElasticsearchCrudServiceFactory _crud_factory;
	protected ElasticsearchIndexServiceConfigBean _config_bean;
	
	// Set this string to connect vs a real DB
	private final String _connection_string = null;
	private final String _cluster_name = null;
//	private final String _connection_string = "localhost:4093";
//	private final String _cluster_name = "infinite-dev";
	
	@Before
	public void setupServices() {
		final Config full_config = ConfigFactory.empty();		
		if (null == _connection_string) {
			_crud_factory = new MockElasticsearchCrudServiceFactory();
		}
		else {
			final ElasticsearchConfigurationBean config_bean = new ElasticsearchConfigurationBean(_connection_string, _cluster_name);
			_crud_factory = new ElasticsearchCrudServiceFactory(config_bean);
		}
		_config_bean = ElasticsearchIndexConfigUtils.buildConfigBean(full_config);
		
		_index_service = new MockElasticsearchIndexService(_crud_factory, _config_bean);
	}
	
	////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	// VALIDATION
	
	@Test
	public void test_verbosenessSettings() {
		final List<Object> l_true = Arrays.asList(1, "1", "true", true, "TRUE", "True");
		final List<Object> l_false = Arrays.asList(0, "0", "false", false, "FALSE", "False");
		
		for (Object o: l_true) {
			final DataSchemaBean.SearchIndexSchemaBean s = BeanTemplateUtils.build(DataSchemaBean.SearchIndexSchemaBean.class)
																.with(DataSchemaBean.SearchIndexSchemaBean::technology_override_schema, 
																		ImmutableMap.builder().put("verbose", o).build()
																).done().get();
			assertEquals(true, ElasticsearchIndexService.is_verbose(s));
		}
		for (Object o: l_false) {
			final DataSchemaBean.SearchIndexSchemaBean s = BeanTemplateUtils.build(DataSchemaBean.SearchIndexSchemaBean.class)
																.with(DataSchemaBean.SearchIndexSchemaBean::technology_override_schema, 
																		ImmutableMap.builder().put("verbose", o).build()
																).done().get();
			assertEquals(false, ElasticsearchIndexService.is_verbose(s));
		}

		// (not present)
		{
			final DataSchemaBean.SearchIndexSchemaBean s = BeanTemplateUtils.build(DataSchemaBean.SearchIndexSchemaBean.class)
					.with(DataSchemaBean.SearchIndexSchemaBean::technology_override_schema, 
							ImmutableMap.builder().build()
					).done().get();
			assertEquals(false, ElasticsearchIndexService.is_verbose(s));
		}
		{
			final DataSchemaBean.SearchIndexSchemaBean s = BeanTemplateUtils.build(DataSchemaBean.SearchIndexSchemaBean.class)
						.done().get();
			assertEquals(false, ElasticsearchIndexService.is_verbose(s));
		}
	}
	
	@Test
	public void test_validationSuccess() throws IOException {
		final String bucket_str = Resources.toString(Resources.getResource("com/ikanow/aleph2/search_service/elasticsearch/services/test_bucket_validate_success.json"), Charsets.UTF_8);
		final DataBucketBean bucket = BeanTemplateUtils.build(bucket_str, DataBucketBean.class).done().get();
		
		// 1) Verbose mode off
		{			
			final Collection<BasicMessageBean> res_col = _index_service.validateSchema(bucket.data_schema().columnar_schema(), bucket)._2();
			final Collection<BasicMessageBean> res_search = _index_service.validateSchema(bucket.data_schema().search_index_schema(), bucket)._2();
			final Collection<BasicMessageBean> res_time = _index_service.validateSchema(bucket.data_schema().temporal_schema(), bucket)._2();
			
			assertEquals(0, res_col.size());
			assertEquals(0, res_search.size());
			assertEquals(0, res_time.size());
		}
		
		// 2) Verbose mode on
		{
			final DataBucketBean bucket_verbose = BeanTemplateUtils.clone(bucket)
													.with(DataBucketBean::data_schema, 
															BeanTemplateUtils.clone(bucket.data_schema())
																.with(
																	DataSchemaBean::search_index_schema,
																	BeanTemplateUtils.clone(bucket.data_schema().search_index_schema())
																		.with(DataSchemaBean.SearchIndexSchemaBean::technology_override_schema,
																				ImmutableMap.builder()
																					.putAll(bucket.data_schema().search_index_schema().technology_override_schema())
																					.put("verbose", true)
																					.build()
																		)
																		.done()
																)
																.done()
													)
													.done();
			
			final Collection<BasicMessageBean> res_col = _index_service.validateSchema(bucket_verbose.data_schema().columnar_schema(), bucket)._2();
			final Collection<BasicMessageBean> res_search = _index_service.validateSchema(bucket_verbose.data_schema().search_index_schema(), bucket)._2();
			final Collection<BasicMessageBean> res_time = _index_service.validateSchema(bucket_verbose.data_schema().temporal_schema(), bucket)._2();
			
			assertEquals(0, res_col.size());
			assertEquals(0, res_time.size());
			assertEquals(1, res_search.size());
			final BasicMessageBean res_search_message = res_search.iterator().next();
			assertEquals(true, res_search_message.success());
			
			final String mapping_str = Resources.toString(Resources.getResource("com/ikanow/aleph2/search_service/elasticsearch/services/test_verbose_mapping_validate_results.json"), Charsets.UTF_8);
			final JsonNode mapping_json = _mapper.readTree(mapping_str.getBytes());

			assertEquals(mapping_json.toString(), _mapper.readTree(res_search_message.message()).toString());
		}
	}
	
	@Test
	public void test_validationFail() throws IOException {
		
		final String bucket_str = Resources.toString(Resources.getResource("com/ikanow/aleph2/search_service/elasticsearch/services/test_bucket_validate_fail.json"), Charsets.UTF_8);
		final DataBucketBean bucket = BeanTemplateUtils.build(bucket_str, DataBucketBean.class).done().get();
		
		// 1) Verbose mode off
		{			
			final Collection<BasicMessageBean> res_col = _index_service.validateSchema(bucket.data_schema().columnar_schema(), bucket)._2();
			final Collection<BasicMessageBean> res_search = _index_service.validateSchema(bucket.data_schema().search_index_schema(), bucket)._2();
			final Collection<BasicMessageBean> res_time = _index_service.validateSchema(bucket.data_schema().temporal_schema(), bucket)._2();
			
			assertEquals(0, res_col.size());
			assertEquals(0, res_time.size());
			assertEquals(1, res_search.size());
			
			final BasicMessageBean res_search_message = res_search.iterator().next();
			assertEquals(false, res_search_message.success());
		}
	}
	
	////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	// INDEX NOT ENABLED
	
	@Test
	public void test_indexNotEnabled() {
		
		final DataBucketBean db1 = BeanTemplateUtils.build(DataBucketBean.class).done().get();
		assertEquals(Optional.empty(), _index_service.getCrudService(JsonNode.class, db1));
		
		final DataBucketBean db2 = BeanTemplateUtils.build(DataBucketBean.class).with("data_schema",
				BeanTemplateUtils.build(DataSchemaBean.class).done().get()
			).done().get();
		assertEquals(Optional.empty(), _index_service.getCrudService(JsonNode.class, db2));
		
		final DataBucketBean db3 = BeanTemplateUtils.build(DataBucketBean.class).with("data_schema",
				BeanTemplateUtils.build(DataSchemaBean.class)
					.with("search_index_schema", 
							BeanTemplateUtils.build(DataSchemaBean.SearchIndexSchemaBean.class).with("enabled", false)
					.done().get()
				).done().get()
			).done().get();
		assertEquals(Optional.empty(), _index_service.getCrudService(JsonNode.class, db3));
		
	}
	
	////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	// INDEX MANAGEMENT
	
	@Test
	public void test_indexCreation() throws IOException {
		
		final Calendar time_setter = GregorianCalendar.getInstance();
		time_setter.set(2015, 1, 1, 13, 0, 0);
		
		final String bucket_str = Resources.toString(Resources.getResource("com/ikanow/aleph2/search_service/elasticsearch/services/test_bucket_validate_success.json"), Charsets.UTF_8);
		final DataBucketBean bucket = BeanTemplateUtils.build(bucket_str, DataBucketBean.class).with("modified", time_setter.getTime()).done().get();

		final String mapping_str = Resources.toString(Resources.getResource("com/ikanow/aleph2/search_service/elasticsearch/services/test_verbose_mapping_validate_results.json"), Charsets.UTF_8);
		final JsonNode mapping_json = _mapper.readTree(mapping_str.getBytes());		
		
		final String template_name = ElasticsearchIndexUtils.getBaseIndexName(bucket); 
		
		try {
			_crud_factory.getClient().admin().indices().prepareDeleteTemplate(template_name).execute().actionGet();
		}
		catch (Exception e) {} // (This is fine, just means it doesn't exist)
		
		// Create index template from empty
		
		{
			final GetIndexTemplatesRequest gt = new GetIndexTemplatesRequest().names(template_name);
			final GetIndexTemplatesResponse gtr = _crud_factory.getClient().admin().indices().getTemplates(gt).actionGet();
			assertTrue("No templates to start with", gtr.getIndexTemplates().isEmpty());
			
			_index_service.handlePotentiallyNewIndex(bucket, ElasticsearchIndexConfigUtils.buildConfigBeanFromSchema(bucket, _config_bean, _mapper), "_default_");
			
			final GetIndexTemplatesRequest gt2 = new GetIndexTemplatesRequest().names(template_name);
			final GetIndexTemplatesResponse gtr2 = _crud_factory.getClient().admin().indices().getTemplates(gt2).actionGet();
			assertEquals(1, _index_service._bucket_template_cache.size());
			assertEquals(1, gtr2.getIndexTemplates().size());
			
			assertTrue("Mappings should be equivalent", ElasticsearchIndexService.mappingsAreEquivalent(gtr2.getIndexTemplates().get(0), mapping_json, _mapper));
		}
		
		// Check is ignored subsequently (same date, same content; same date, different content)
		{
			
			_index_service.handlePotentiallyNewIndex(bucket, ElasticsearchIndexConfigUtils.buildConfigBeanFromSchema(bucket, _config_bean, _mapper), "_default_");
			
			final GetIndexTemplatesRequest gt2 = new GetIndexTemplatesRequest().names(template_name);
			final GetIndexTemplatesResponse gtr2 = _crud_factory.getClient().admin().indices().getTemplates(gt2).actionGet();
			assertEquals(1, _index_service._bucket_template_cache.size());
			assertEquals(1, gtr2.getIndexTemplates().size());
		}
		
		// Check is checked-but-left if time updated, content not
		{
			time_setter.set(2015, 1, 1, 14, 0, 0);
			final Date next_time = time_setter.getTime();
			final DataBucketBean bucket2 = BeanTemplateUtils.clone(bucket).with("modified", next_time).done();
			
			_index_service.handlePotentiallyNewIndex(bucket2, ElasticsearchIndexConfigUtils.buildConfigBeanFromSchema(bucket2, _config_bean, _mapper), "_default_");
			
			final GetIndexTemplatesRequest gt2 = new GetIndexTemplatesRequest().names(template_name);
			final GetIndexTemplatesResponse gtr2 = _crud_factory.getClient().admin().indices().getTemplates(gt2).actionGet();
			assertEquals(1, _index_service._bucket_template_cache.size());
			assertEquals(next_time, _index_service._bucket_template_cache.get(bucket._id()));
			assertEquals(1, gtr2.getIndexTemplates().size());
		}
		
		// Check is updated if time-and-content is different
		{
			time_setter.set(2015, 1, 1, 15, 0, 0);
			final String bucket_str2 = Resources.toString(Resources.getResource("com/ikanow/aleph2/search_service/elasticsearch/services/test_bucket2_validate_success.json"), Charsets.UTF_8);
			final DataBucketBean bucket2 = BeanTemplateUtils.build(bucket_str2, DataBucketBean.class).with("modified", time_setter.getTime()).done().get();
			
			_index_service.handlePotentiallyNewIndex(bucket2, ElasticsearchIndexConfigUtils.buildConfigBeanFromSchema(bucket2, _config_bean, _mapper), "_default_");
			
			final GetIndexTemplatesRequest gt2 = new GetIndexTemplatesRequest().names(template_name);
			final GetIndexTemplatesResponse gtr2 = _crud_factory.getClient().admin().indices().getTemplates(gt2).actionGet();
			assertEquals(1, _index_service._bucket_template_cache.size());
			assertEquals(time_setter.getTime(), _index_service._bucket_template_cache.get(bucket._id()));
			assertEquals(1, gtr2.getIndexTemplates().size());
			
			assertFalse(ElasticsearchIndexService.mappingsAreEquivalent(gtr2.getIndexTemplates().get(0), mapping_json, _mapper)); // has changed
		}
		
		// Check if mapping is deleted then next time bucket modified is updated then the mapping is recreated
		
		{
			_crud_factory.getClient().admin().indices().prepareDeleteTemplate(template_name).execute().actionGet();

			//(check with old date)
			
			final GetIndexTemplatesRequest gt = new GetIndexTemplatesRequest().names(template_name);
			final GetIndexTemplatesResponse gtr = _crud_factory.getClient().admin().indices().getTemplates(gt).actionGet();
			assertTrue("No templates to start with", gtr.getIndexTemplates().isEmpty());
			
			{
				_index_service.handlePotentiallyNewIndex(bucket, ElasticsearchIndexConfigUtils.buildConfigBeanFromSchema(bucket, _config_bean, _mapper), "_default_");
				
				final GetIndexTemplatesRequest gt2 = new GetIndexTemplatesRequest().names(template_name);
				final GetIndexTemplatesResponse gtr2 = _crud_factory.getClient().admin().indices().getTemplates(gt2).actionGet();
				assertTrue("Initially no change", gtr2.getIndexTemplates().isEmpty());
			}			
			
			// Update date and retry
			
			{
				time_setter.set(2015, 1, 1, 16, 0, 0);
				final Date next_time = time_setter.getTime();
				final DataBucketBean bucket2 = BeanTemplateUtils.clone(bucket).with("modified", next_time).done();				
				
				_index_service.handlePotentiallyNewIndex(bucket2, ElasticsearchIndexConfigUtils.buildConfigBeanFromSchema(bucket2, _config_bean, _mapper), "_default_");
				
				final GetIndexTemplatesRequest gt2 = new GetIndexTemplatesRequest().names(template_name);
				final GetIndexTemplatesResponse gtr2 = _crud_factory.getClient().admin().indices().getTemplates(gt2).actionGet();
				assertEquals(1, _index_service._bucket_template_cache.size());
				assertEquals(1, gtr2.getIndexTemplates().size());
				
				assertTrue("Mappings should be equivalent", ElasticsearchIndexService.mappingsAreEquivalent(gtr2.getIndexTemplates().get(0), mapping_json, _mapper));
			}
		}
	}
	
	////////////////////////////////////////////////////////////////////////////////////////////////////////////////

	// END-TO-END
	
	@Test
	public void test_handleMultiBucket() {
		// Will currently fail because read-only indices not yet supported
		
		final DataBucketBean multi_bucket = BeanTemplateUtils.build(DataBucketBean.class)
																		.with("_id", "test_multi_bucket")
																		.with("multi_bucket_children", ImmutableSet.builder().add("test1").build()).done().get();
		
		try {
			_index_service.getCrudService(JsonNode.class, multi_bucket);
			fail("Should have thrown an exception");
		}
		catch (Exception e) {
			//(don't care about anything else here, this is mostly just for coverage at this point)
		}
	}
	
	// (including getCrudService)
	
	@Test
	public void test_endToEnd_autoTime() throws IOException, InterruptedException {
		final Calendar time_setter = GregorianCalendar.getInstance();
		time_setter.set(2015, 1, 1, 13, 0, 0);
		final String bucket_str = Resources.toString(Resources.getResource("com/ikanow/aleph2/search_service/elasticsearch/services/test_end_2_end_bucket.json"), Charsets.UTF_8);
		final DataBucketBean bucket = BeanTemplateUtils.build(bucket_str, DataBucketBean.class)
													.with("_id", "test_end_2_end")
													.with("full_name", "/test/end-end/auto-time")
													.with("modified", time_setter.getTime())
												.done().get();

		final String template_name = ElasticsearchIndexUtils.getBaseIndexName(bucket);
		
		// Check starting from clean
		
		{
			try {
				_crud_factory.getClient().admin().indices().prepareDeleteTemplate(template_name).execute().actionGet();
			}
			catch (Exception e) {} // (This is fine, just means it doesn't exist)		
			try {
				_crud_factory.getClient().admin().indices().prepareDelete(template_name + "*").execute().actionGet();
			}
			catch (Exception e) {} // (This is fine, just means it doesn't exist)		
			
			final GetIndexTemplatesRequest gt = new GetIndexTemplatesRequest().names(template_name);
			final GetIndexTemplatesResponse gtr = _crud_factory.getClient().admin().indices().getTemplates(gt).actionGet();
			assertTrue("No templates to start with", gtr.getIndexTemplates().isEmpty());
		}				
		
		final ICrudService<JsonNode> index_service_crud = _index_service.getCrudService(JsonNode.class, bucket).get();
		
		// Check template added:

		{
			final GetIndexTemplatesRequest gt2 = new GetIndexTemplatesRequest().names(template_name);
			final GetIndexTemplatesResponse gtr2 = _crud_factory.getClient().admin().indices().getTemplates(gt2).actionGet();
			assertEquals(1, _index_service._bucket_template_cache.size());
			assertEquals(1, gtr2.getIndexTemplates().size());
		}
		
		// Get batch sub-service
				
		@SuppressWarnings("unchecked")
		final Optional<ICrudService.IBatchSubservice<JsonNode>> batch_service = index_service_crud.getUnderlyingPlatformDriver(ICrudService.IBatchSubservice.class, Optional.empty())
																			.map(t -> (IBatchSubservice<JsonNode>)t);
		
		{
			assertTrue("Batch service must exist", batch_service.isPresent());
		}
			
		// Get information about the crud service
		
		final ElasticsearchContext es_context = (ElasticsearchContext) index_service_crud.getUnderlyingPlatformDriver(ElasticsearchContext.class, Optional.empty()).get();
		
		{
			assertTrue("Read write index", es_context instanceof ElasticsearchContext.ReadWriteContext);
			assertTrue("Temporal index", es_context.indexContext() instanceof ElasticsearchContext.IndexContext.ReadWriteIndexContext.TimedRwIndexContext);
			assertTrue("Auto type", es_context.typeContext() instanceof ElasticsearchContext.TypeContext.ReadWriteTypeContext.AutoRwTypeContext);
		}
		
		// Write some docs out
		
		Arrays.asList(1, 2, 3, 4, 5).stream()
						.map(i -> { time_setter.set(2015, i, 1, 13, 0, 0); return time_setter.getTime(); })
						.map(d -> (ObjectNode) _mapper.createObjectNode().put("@timestamp", d.getTime()))
						.forEach(o -> {
							ObjectNode o1 = o.deepCopy();
							o1.set("val1", _mapper.createObjectNode().put("val2", "test"));
							ObjectNode o2 = o.deepCopy();
							o2.put("val1", "test");
							batch_service.get().storeObject(o1, false);
							batch_service.get().storeObject(o2, false);
						});

		//(give it a chance to run)
		Thread.sleep(5000L);
		
		final GetMappingsResponse gmr = es_context.client().admin().indices().prepareGetMappings(template_name + "*").execute().actionGet();
		
		// Should have 5 different indexes, each with 2 types + _default_
		
		assertEquals(5, gmr.getMappings().keys().size());
		final Set<String> expected_keys =  Arrays.asList(1, 2, 3, 4, 5).stream().map(i -> template_name + "_2015-0" + (i+1) + "-01").collect(Collectors.toSet());
		final Set<String> expected_types =  Arrays.asList("_default_", "type_1", "type_2").stream().collect(Collectors.toSet());
		
		StreamSupport.stream(gmr.getMappings().spliterator(), false)
			.forEach(x -> {
				assertTrue("Is one of the expected keys: " + x.key + " vs  " + expected_keys.stream().collect(Collectors.joining(":")), expected_keys.contains(x.key));
				//DEBUG
				//System.out.println(" ? " + x.key);
				StreamSupport.stream(x.value.spliterator(), false).forEach(Lambdas.wrap_consumer_u(y -> {
					//DEBUG
					//System.out.println("?? " + y.key + " --- " + y.value.sourceAsMap().toString());
					// Size 3: _default_, type1 and type2
					assertTrue("Is expected type: " + y.key, expected_types.contains(y.key));
				}));
				// Size 3: _default_, type_1, type_2 
				assertEquals(3, x.value.size());
			});
	}
	
	@Test
	public void test_endToEnd_fixedFixed() throws IOException, InterruptedException {
		final Calendar time_setter = GregorianCalendar.getInstance();
		time_setter.set(2015, 1, 1, 13, 0, 0);
		final String bucket_str = Resources.toString(Resources.getResource("com/ikanow/aleph2/search_service/elasticsearch/services/test_end_2_end_bucket2.json"), Charsets.UTF_8);
		final DataBucketBean bucket = BeanTemplateUtils.build(bucket_str, DataBucketBean.class)
													.with("_id", "2b_test_end_2_end")
													.with("full_name", "/test/end-end/fixed/fixed")
													.with("modified", time_setter.getTime())
												.done().get();

		final String template_name = ElasticsearchIndexUtils.getBaseIndexName(bucket);
		
		// Check starting from clean
		
		{
			try {
				_crud_factory.getClient().admin().indices().prepareDeleteTemplate(template_name).execute().actionGet();
			}
			catch (Exception e) {} // (This is fine, just means it doesn't exist)		
			try {
				_crud_factory.getClient().admin().indices().prepareDelete(template_name + "*").execute().actionGet();
			}
			catch (Exception e) {} // (This is fine, just means it doesn't exist)		
			
			final GetIndexTemplatesRequest gt = new GetIndexTemplatesRequest().names(template_name);
			final GetIndexTemplatesResponse gtr = _crud_factory.getClient().admin().indices().getTemplates(gt).actionGet();
			assertTrue("No templates to start with", gtr.getIndexTemplates().isEmpty());
		}				
		
		final ICrudService<JsonNode> index_service_crud = _index_service.getCrudService(JsonNode.class, bucket).get();
		
		// Check template added:

		{
			final GetIndexTemplatesRequest gt2 = new GetIndexTemplatesRequest().names(template_name);
			final GetIndexTemplatesResponse gtr2 = _crud_factory.getClient().admin().indices().getTemplates(gt2).actionGet();
			assertEquals(1, _index_service._bucket_template_cache.size());
			assertEquals(1, gtr2.getIndexTemplates().size());
		}
		
		// Get batch sub-service
				
		@SuppressWarnings("unchecked")
		final Optional<ICrudService.IBatchSubservice<JsonNode>> batch_service = index_service_crud.getUnderlyingPlatformDriver(ICrudService.IBatchSubservice.class, Optional.empty())
																			.map(t -> (IBatchSubservice<JsonNode>)t);
		
		{
			assertTrue("Batch service must exist", batch_service.isPresent());
		}
			
		// Get information about the crud service
		
		final ElasticsearchContext es_context = (ElasticsearchContext) index_service_crud.getUnderlyingPlatformDriver(ElasticsearchContext.class, Optional.empty()).get();
		
		{
			assertTrue("Read write index", es_context instanceof ElasticsearchContext.ReadWriteContext);
			assertTrue("Temporal index", es_context.indexContext() instanceof ElasticsearchContext.IndexContext.ReadWriteIndexContext.FixedRwIndexContext);
			assertTrue("Auto type", es_context.typeContext() instanceof ElasticsearchContext.TypeContext.ReadWriteTypeContext.FixedRwTypeContext);
		}
		
		// Write some docs out
		
		Arrays.asList(1, 2, 3, 4, 5).stream()
						.map(i -> { time_setter.set(2015, i, 1, 13, 0, 0); return time_setter.getTime(); })
						.map(d -> (ObjectNode) _mapper.createObjectNode().put("@timestamp", d.getTime()))
						.forEach(o -> {
							ObjectNode o1 = o.deepCopy();
							o1.put("val1", 10);
							ObjectNode o2 = o.deepCopy();
							o2.put("val1", "test");
							batch_service.get().storeObject(o1, false);
							batch_service.get().storeObject(o2, false);
						});

		//(give it a chance to run)
		Thread.sleep(5000L);
		
		final GetMappingsResponse gmr = es_context.client().admin().indices().prepareGetMappings(template_name + "*").execute().actionGet();
		
		// Should have 5 different indexes, each with 2 types + _default_
		
		assertEquals(1, gmr.getMappings().keys().size());
		final Set<String> expected_keys =  Arrays.asList("test_fixed_fixed__1cb6bdcdf44f").stream().collect(Collectors.toSet());
		final Set<String> expected_types =  Arrays.asList("data_object").stream().collect(Collectors.toSet());
		
		StreamSupport.stream(gmr.getMappings().spliterator(), false)
			.forEach(x -> {
				assertTrue("Is one of the expected keys: " + x.key + " vs  " + expected_keys.stream().collect(Collectors.joining(":")), expected_keys.contains(x.key));
				// Size 1: data_object 
				assertEquals(1, x.value.size());
				//DEBUG
				//System.out.println(" ? " + x.key);
				StreamSupport.stream(x.value.spliterator(), false).forEach(Lambdas.wrap_consumer_u(y -> {
					//DEBUG
					//System.out.println("?? " + y.key + " --- " + y.value.sourceAsMap().toString());
					assertTrue("Is expected type: " + y.key, expected_types.contains(y.key));
				}));
			});
	}	
}
