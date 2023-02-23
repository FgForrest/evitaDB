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
import io.evitadb.api.CatalogContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.EvitaSystemDataProvider;
import io.evitadb.externalApi.rest.exception.RESTApiInternalError;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import lombok.Builder;
import lombok.Data;

import javax.annotation.Nonnull;

/**
 * This class contains information required to process REST API requests. Not all attributes has to be set
 * it depends on needs of particular handler.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Data
@Builder
public class RESTApiContext {
	private final Evita evita;
	private final ObjectMapper objectMapper;
	private final OpenAPI openApi;
	private final PathItem pathItem;
	private final CatalogContract catalog;
	private final String entityType;
	private final boolean localized;

	/**
	 * Creates Evita's read-only session
	 */
	@Nonnull
	public EvitaSessionContract createReadOnlySession() {
		return evita.createReadOnlySession(catalog.getName());
	}

	/**
	 * Creates Evita's read/write session
	 */
	@Nonnull
	public EvitaSessionContract createReadWriteSession() {
		return evita.createReadWriteSession(catalog.getName());
	}

	/**
	 * Gets entity schema for entity defined in this context data
	 */
	@Nonnull
	public EntitySchemaContract getEntitySchema() {
		return new EvitaSystemDataProvider(evita).getCatalog(catalog.getName()).getEntitySchema(entityType)
			.orElseThrow(() -> new RESTApiInternalError("No schema found for entity: " + entityType + " in catalog: " + catalog));
	}

	/**
	 * Gets entity schema for any entity by entity name
	 */
	@Nonnull
	public EntitySchemaContract getEntitySchema(@Nonnull String entityName) {
		return new EvitaSystemDataProvider(evita).getCatalog(catalog.getName())
			.getEntitySchema(entityName)
			.orElseThrow(() -> new RESTApiInternalError("No schema found for entity: " + entityName + " in catalog: " + catalog));
	}
}
