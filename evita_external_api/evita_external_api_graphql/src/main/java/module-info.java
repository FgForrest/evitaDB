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

/**
 * Module contains GraphQL API for evitaDB.
 */
module evita.external.api.graphql {

	uses io.evitadb.store.spi.CatalogPersistenceServiceFactory;
	uses io.evitadb.externalApi.http.ExternalApiProviderRegistrar;

	provides io.evitadb.externalApi.http.ExternalApiProviderRegistrar with io.evitadb.externalApi.graphql.GraphQLProviderRegistrar;

	opens io.evitadb.externalApi.graphql to com.graphqljava;
	opens io.evitadb.externalApi.graphql.configuration to com.fasterxml.jackson.databind;
	opens io.evitadb.externalApi.graphql.api to com.graphqljava;
	opens io.evitadb.externalApi.graphql.api.catalog to com.graphqljava;
	opens io.evitadb.externalApi.graphql.api.catalog.dataApi.dto to com.graphqljava;
	opens io.evitadb.externalApi.graphql.io to com.fasterxml.jackson.databind;
	opens io.evitadb.externalApi.graphql.io.webSocket to com.fasterxml.jackson.databind;
	opens io.evitadb.externalApi.graphql.io.web to com.fasterxml.jackson.databind;

	exports io.evitadb.externalApi.graphql;
	exports io.evitadb.externalApi.graphql.io;
	exports io.evitadb.externalApi.graphql.configuration;
	exports io.evitadb.externalApi.graphql.metric.event.request;
	exports io.evitadb.externalApi.graphql.metric.event.instance;

	exports io.evitadb.externalApi.graphql.api.catalog.dataApi.model to evita.test.support;
	exports io.evitadb.externalApi.graphql.api.catalog.dataApi.model.entity to evita.test.support;
	exports io.evitadb.externalApi.graphql.api.catalog.dataApi.model.extraResult to evita.test.support;
	exports io.evitadb.externalApi.graphql.io.webSocket;
	exports io.evitadb.externalApi.graphql.io.web;

	requires static jsr305;
	requires static lombok;

	requires com.graphqljava;
	requires com.fasterxml.jackson.databind;
	requires com.linecorp.armeria;
	requires com.linecorp.armeria.graphql;
	requires io.netty.common;
	requires io.netty.transport;
	requires net.bytebuddy;
	requires jdk.jfr;
	requires org.reactivestreams;
	requires org.slf4j;

	requires evita.api;
	requires evita.common;
	requires evita.query;
	requires evita.engine;
	requires evita.external.api.core;
	requires evita.external.api.graphql;

}
