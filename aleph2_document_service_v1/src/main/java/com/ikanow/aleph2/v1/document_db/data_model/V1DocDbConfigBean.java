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
 *******************************************************************************/
package com.ikanow.aleph2.v1.document_db.data_model;

import java.util.Optional;

/**
 * @author Alex
 */
public class V1DocDbConfigBean {

	final public static String PROPERTIES_ROOT = "V1DocumentDbService";
	
	public V1DocDbConfigBean() {}
	
	/** User constructor
	 * @param mongodb_connection he connection string that is used to initialize the MongoDB
	 */
	public V1DocDbConfigBean(final String mongodb_connection) {
		this.mongodb_connection = mongodb_connection;
	}
	/** The connection string that is used to initialize the MongoDB
	 * @return
	 */
	public String mongodb_connection() { return Optional.ofNullable(mongodb_connection).orElse("localhost:27017"); }

	/** The location of the infinit.e properties files (defaults to sensible code)
	 * @return
	 */
	public String infinite_config_home() { return Optional.ofNullable(infinite_config_home).orElse("/opt/infinite-home/config"); }
	
	
	private String mongodb_connection;
	private String infinite_config_home;
}
