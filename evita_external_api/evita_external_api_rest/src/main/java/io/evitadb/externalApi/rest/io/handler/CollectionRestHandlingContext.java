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

package io.evitadb.externalApi.rest.io.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.Evita;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import lombok.Getter;

import javax.annotation.Nonnull;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, 2023
 */
public class CollectionRestHandlingContext extends RestHandlingContext {

	@Nonnull @Getter private final EntitySchemaContract entitySchema;

	public CollectionRestHandlingContext(@Nonnull ObjectMapper objectMapper,
	                                     @Nonnull Evita evita,
	                                     @Nonnull CatalogSchemaContract catalogSchema,
	                                     @Nonnull EntitySchemaContract entitySchema,
	                                     @Nonnull OpenAPI openApi,
	                                     @Nonnull Operation endpointOperation,
	                                     boolean localized) {
		super(objectMapper, evita, catalogSchema, openApi, endpointOperation, localized);
		this.entitySchema = entitySchema;
	}

	@Nonnull
	public String getEntityType() {
		return entitySchema.getName();
	}
}
