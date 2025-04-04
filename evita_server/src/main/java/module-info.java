/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

import io.evitadb.externalApi.api.system.ProbesProvider;
import io.evitadb.externalApi.http.ExternalApiProviderRegistrar;
import io.evitadb.store.spi.CatalogPersistenceServiceFactory;

/**
 * Module contains evitaDB standalone server.
 */
module evita.server {

	uses CatalogPersistenceServiceFactory;
	uses ExternalApiProviderRegistrar;
	uses ProbesProvider;

	opens io.evitadb.server.configuration to com.fasterxml.jackson.databind;
	exports io.evitadb.server;
	exports io.evitadb.server.log to ch.qos.logback.core;
	exports io.evitadb.server.yaml;

	requires static lombok;
	requires static jsr305;
	requires com.fasterxml.jackson.databind;
	requires org.yaml.snakeyaml;
	requires org.apache.commons.text;
	requires com.fasterxml.jackson.dataformat.yaml;
	requires com.fasterxml.jackson.module.paramnames;
	requires org.slf4j;
	requires ch.qos.logback.core;
	requires jdk.unsupported;

	requires evita.api;
	requires evita.common;
	requires evita.engine;
	requires evita.external.api.core;
	requires ch.qos.logback.classic;
}
