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

package io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.rest.api.catalog.resolver.endpoint.CatalogRestHandlingContext;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * Context object specific to collection-related endpoints for handling and resolving those endpoints.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class CollectionRestHandlingContext extends CatalogRestHandlingContext {

	@Nonnull @Getter private final EntitySchemaContract entitySchema;

	public CollectionRestHandlingContext(
		@Nonnull ObjectMapper objectMapper,
		@Nonnull Evita evita,
		@Nonnull HeaderOptions headerOptions,
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull OpenAPI openApi,
		@Nonnull Map<String, Class<? extends Enum<?>>> enumMapping,
		@Nonnull Operation endpointOperation,
		boolean localized
	) {
		super(objectMapper, evita, headerOptions, catalogSchema, openApi, enumMapping, endpointOperation, localized);
		this.entitySchema = entitySchema;
	}

	@Nonnull
	public String getEntityType() {
		return this.entitySchema.getName();
	}
}
