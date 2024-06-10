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

import io.evitadb.externalApi.http.ExternalApiProviderRegistrar;
import io.evitadb.store.spi.CatalogPersistenceServiceFactory;

/**
 * Module contains GraphQL API for evitaDB.
 */
module evita.external.api.graphql {

	uses CatalogPersistenceServiceFactory;
	uses ExternalApiProviderRegistrar;

	provides ExternalApiProviderRegistrar with io.evitadb.externalApi.graphql.GraphQLProviderRegistrar;

	opens io.evitadb.externalApi.graphql to com.graphqljava;
	opens io.evitadb.externalApi.graphql.configuration to com.fasterxml.jackson.databind;
	opens io.evitadb.externalApi.graphql.io to com.fasterxml.jackson.databind;
	opens io.evitadb.externalApi.graphql.api.catalog to com.graphqljava;
	opens io.evitadb.externalApi.graphql.api.catalog.dataApi.dto to com.graphqljava;
	opens io.evitadb.externalApi.graphql.api to com.graphqljava;

	exports io.evitadb.externalApi.graphql;
	exports io.evitadb.externalApi.graphql.io;
	exports io.evitadb.externalApi.graphql.configuration;

	exports io.evitadb.externalApi.graphql.api.catalog.dataApi.model to evita.test.support;
	exports io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity to evita.test.support;
	exports io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult to evita.test.support;

	requires static jsr305;
	requires static lombok;
	requires org.slf4j;
	requires com.graphqljava;
	requires com.fasterxml.jackson.databind;
	requires undertow.core;
	requires net.bytebuddy;

	requires evita.api;
	requires evita.common;
	requires evita.query;
	requires evita.engine;
	requires evita.external.api.core;

}
