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

module evita.external.api.lab {

	uses ExternalApiProviderRegistrar;

	opens io.evitadb.externalApi.lab.configuration to com.fasterxml.jackson.databind;
	opens io.evitadb.externalApi.lab.gui.dto to com.fasterxml.jackson.databind;

	provides ExternalApiProviderRegistrar with io.evitadb.externalApi.lab.LabProviderRegistrar;

	exports io.evitadb.externalApi.lab.configuration;
	exports io.evitadb.externalApi.lab;

	requires static jsr305;
	requires static lombok;
	requires org.slf4j;
	requires com.fasterxml.jackson.databind;
	requires evita.common;
	requires evita.engine;
	requires evita.external.api.core;
	requires evita.external.api.graphql;
	requires evita.external.api.rest;
	requires evita.external.api.system;
	requires evita.external.api.grpc;
	requires evita.external.api.observability;
	requires io.swagger.v3.oas.models;
	requires evita.api;
	requires evita.query;
	requires com.linecorp.armeria;
}
