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
package com.ikanow.aleph2.search_service.elasticsearch.module;

import com.google.inject.AbstractModule;
import com.google.inject.Scopes;
import com.ikanow.aleph2.data_model.utils.BeanTemplateUtils;
import com.ikanow.aleph2.data_model.utils.ErrorUtils;
import com.ikanow.aleph2.data_model.utils.ModuleUtils;
import com.ikanow.aleph2.data_model.utils.PropertiesUtils;
import com.ikanow.aleph2.search_service.elasticsearch.data_model.ElasticsearchIndexServiceConfigBean;
import com.ikanow.aleph2.shared.crud.elasticsearch.data_model.ElasticsearchConfigurationBean;
import com.ikanow.aleph2.shared.crud.elasticsearch.services.ElasticsearchCrudServiceFactory;
import com.ikanow.aleph2.shared.crud.elasticsearch.services.IElasticsearchCrudServiceFactory;
import com.typesafe.config.Config;

/** Creates the bindings needed for the real search index service
 * @author Alex
 */
public class ElasticsearchIndexServiceModule extends AbstractModule {

	/* (non-Javadoc)
	 * @see com.google.inject.AbstractModule#configure()
	 */
	protected void configure() {
		final Config config = ModuleUtils.getStaticConfig();				
		ElasticsearchIndexServiceConfigBean bean;
		try {
			bean = BeanTemplateUtils.from(PropertiesUtils.getSubConfig(config, ElasticsearchIndexServiceConfigBean.PROPERTIES_ROOT).orElse(null), ElasticsearchIndexServiceConfigBean.class);
		} 
		catch (Exception e) {
			throw new RuntimeException(ErrorUtils.get(ErrorUtils.INVALID_CONFIG_ERROR,
					ElasticsearchIndexServiceConfigBean.class.toString(),
					config.getConfig(ElasticsearchIndexServiceConfigBean.PROPERTIES_ROOT)
					), e);
		}
		this.bind(ElasticsearchConfigurationBean.class).toInstance(bean); // (for crud service)
		this.bind(ElasticsearchIndexServiceConfigBean.class).toInstance(bean); // (for es service)
		
		this.bind(IElasticsearchCrudServiceFactory.class).to(ElasticsearchCrudServiceFactory.class).in(Scopes.SINGLETON);
	}

}
