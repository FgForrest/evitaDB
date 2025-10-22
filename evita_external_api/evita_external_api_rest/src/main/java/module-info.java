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

import io.evitadb.externalApi.http.ExternalApiProviderRegistrar;
import io.evitadb.externalApi.rest.RestProviderRegistrar;
import io.evitadb.store.spi.CatalogPersistenceServiceFactory;

/**
 * Module contains REST API for evitaDB.
 */
module evita.external.api.rest {

	uses CatalogPersistenceServiceFactory;
	uses ExternalApiProviderRegistrar;

	provides ExternalApiProviderRegistrar with RestProviderRegistrar;

	opens io.evitadb.externalApi.rest.configuration to com.fasterxml.jackson.databind;
	opens io.evitadb.externalApi.rest.api.resolver.serializer to com.fasterxml.jackson.databind;
	opens io.evitadb.externalApi.rest.api.catalog.dataApi.dto to com.fasterxml.jackson.databind;
	opens io.evitadb.externalApi.rest.api.catalog.schemaApi.dto to com.fasterxml.jackson.databind;
	opens io.evitadb.externalApi.rest.api.system.dto to com.fasterxml.jackson.databind;
	opens io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer to com.fasterxml.jackson.databind;
	opens io.evitadb.externalApi.rest.api.catalog.schemaApi.resolver.serializer to com.fasterxml.jackson.databind;

	requires static jsr305;
	requires static lombok;
	requires org.slf4j;
	requires com.fasterxml.jackson.databind;
	requires com.fasterxml.jackson.core;
	requires com.fasterxml.jackson.annotation;
	requires io.swagger.v3.oas.models;
	requires io.swagger.v3.core;
	requires com.linecorp.armeria;

	requires evita.api;
	requires evita.common;
	requires evita.external.api.core;
	requires evita.query;
	requires evita.engine;
	requires org.reactivestreams;
	requires io.netty.common;
	requires io.netty.transport;
	requires jdk.jfr;
	requires evita.external.api.rest;

	exports io.evitadb.externalApi.rest;
	exports io.evitadb.externalApi.rest.io;
	exports io.evitadb.externalApi.rest.configuration;
	exports io.evitadb.externalApi.rest.metric.event.request;
	exports io.evitadb.externalApi.rest.metric.event.instance;

	// these shouldn't be publicly exported, but they are used by the lab module which extends this module
	exports io.evitadb.externalApi.rest.api.builder to evita.external.api.lab;
	exports io.evitadb.externalApi.rest.api.openApi to evita.external.api.lab;
	exports io.evitadb.externalApi.rest.exception to evita.external.api.lab;
	exports io.evitadb.externalApi.rest.api.model to evita.external.api.lab;
	exports io.evitadb.externalApi.rest.api.resolver.serializer to evita.external.api.lab;
	exports io.evitadb.externalApi.rest.api.catalog.dataApi.builder to evita.external.api.lab;
	exports io.evitadb.externalApi.rest.api.catalog.dataApi.model.entity to evita.external.api.lab;
	exports io.evitadb.externalApi.rest.api.catalog.dataApi.model to evita.external.api.lab;
	exports io.evitadb.externalApi.rest.api.catalog.dataApi.model.extraResult to evita.external.api.lab;
	exports io.evitadb.externalApi.rest.api.catalog.dataApi.dto to evita.external.api.lab;
	exports io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer to evita.external.api.lab;
	exports io.evitadb.externalApi.rest.api.system.resolver.serializer to evita.external.api.lab;
	exports io.evitadb.externalApi.rest.api.catalog.schemaApi.builder to evita.external.api.lab;
	exports io.evitadb.externalApi.rest.api.catalog.schemaApi.resolver.serializer to evita.external.api.lab;
	exports io.evitadb.externalApi.rest.api.resolver.endpoint to evita.external.api.lab;
	exports io.evitadb.externalApi.rest.api.dataType to evita.external.api.lab;
	exports io.evitadb.externalApi.rest.api.system.dto to evita.external.api.lab;
	exports io.evitadb.externalApi.rest.api.system.model to evita.external.api.lab;
	exports io.evitadb.externalApi.rest.api;
}
