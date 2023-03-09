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

package io.evitadb.externalApi.rest.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.rest.exception.RestInternalError;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import lombok.AllArgsConstructor;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.function.Function;

/**
 * This class contains information required to process REST API requests. Not all attributes has to be set
 * it depends on needs of particular handler.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@AllArgsConstructor
public class RestHandlingContext {

	@Nonnull @Getter protected final ObjectMapper objectMapper;

	@Nonnull protected final Evita evita;
	@Nonnull @Getter protected final CatalogSchemaContract catalogSchema;

	@Nonnull @Getter private final OpenAPI openApi;
	@Nonnull @Getter private final Operation endpointOperation;

	@Getter private final boolean localized;

	/**
	 * Creates Evita's read-only session
	 */
	public <T> T queryCatalog(@Nonnull Function<EvitaSessionContract, T> queryLogic) {
		return evita.queryCatalog(catalogSchema.getName(), queryLogic);
	}

	/**
	 * Creates Evita's read/write session
	 */
	public <T> T updateCatalog(@Nonnull Function<EvitaSessionContract, T> updater) {
		return evita.updateCatalog(catalogSchema.getName(), updater);
	}

	/**
	 * Gets entity schema for any entity by entity name
	 */
	@Nonnull
	public EntitySchemaContract getEntitySchema(@Nonnull String entityName) {
		return evita.getCatalogInstanceOrThrowException(catalogSchema.getName())
			.getEntitySchema(entityName)
			.orElseThrow(() -> new RestInternalError("No schema found for entity: " + entityName + " in catalog: " + catalogSchema.getName()));
	}
}
