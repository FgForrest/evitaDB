/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
 * Module contains REST API for evitaDB.
 */
module evita.external.api.rest {

	uses CatalogPersistenceServiceFactory;
	uses ExternalApiProviderRegistrar;

	provides ExternalApiProviderRegistrar with io.evitadb.externalApi.rest.RESTProviderRegistrar;

	opens io.evitadb.externalApi.rest.configuration to com.fasterxml.jackson.databind;
	opens io.evitadb.externalApi.rest.io.model to com.fasterxml.jackson.databind;
	opens io.evitadb.externalApi.rest.io.serializer to com.fasterxml.jackson.databind;

	requires static jsr305;
	requires static lombok;
	requires org.slf4j;
	requires com.fasterxml.jackson.databind;
	requires com.fasterxml.jackson.core;
	requires com.fasterxml.jackson.annotation;
	requires io.swagger.v3.oas.models;
	requires undertow.core;
	requires io.swagger.v3.core;

	requires evita.api;
	requires evita.common;
	requires evita.external.api.core;
	requires evita.query;
	requires evita.engine;

	exports io.evitadb.externalApi.rest.io;
	exports io.evitadb.externalApi.rest.io.handler;
	exports io.evitadb.externalApi.rest.io.handler.constraint;

}