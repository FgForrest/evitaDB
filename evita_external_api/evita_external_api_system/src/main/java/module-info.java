/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
 * Module contains System API for evitaDB.
 */
module evita.external.api.system {

	uses CatalogPersistenceServiceFactory;
	uses ExternalApiProviderRegistrar;
	uses ProbesProvider;

	provides ExternalApiProviderRegistrar with io.evitadb.externalApi.system.SystemProviderRegistrar;

	opens io.evitadb.externalApi.system.configuration to com.fasterxml.jackson.databind;

	requires static jsr305;
	requires static lombok;
	requires org.slf4j;

	requires evita.api;
	requires evita.common;
	requires evita.external.api.core;
	requires evita.query;
	requires evita.engine;
	requires com.fasterxml.jackson.annotation;
	requires com.fasterxml.jackson.databind;
	requires java.management;
	requires org.bouncycastle.provider;
	requires com.linecorp.armeria;
	requires jdk.jfr;
	requires io.netty.common;

	exports io.evitadb.externalApi.system.configuration;
	exports io.evitadb.externalApi.system;
}
