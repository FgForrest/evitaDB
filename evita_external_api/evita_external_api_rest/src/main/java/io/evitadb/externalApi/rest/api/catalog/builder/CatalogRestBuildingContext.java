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

package io.evitadb.externalApi.rest.api.catalog.builder;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.rest.api.builder.RestBuildingContext;
import io.evitadb.externalApi.rest.api.openApi.OpenApiObject;
import io.evitadb.externalApi.rest.api.openApi.OpenApiTypeReference;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * This context contains objects which are used (and shared) during building OpenAPI schema.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
public class CatalogRestBuildingContext extends RestBuildingContext {

	@Getter @Nonnull private final CatalogContract catalog;
	@Getter @Nonnull private final Set<EntitySchemaContract> entitySchemas;

	/**
	 * Gathered all entity objects for all collections (non-localized).
	 */
	@Nonnull @Getter private final List<OpenApiTypeReference> entityObjects;
	/**
	 * Gathered all entity objects for all collections (localized).
	 */
	@Nonnull @Getter private final List<OpenApiTypeReference> localizedEntityObjects;


	public CatalogRestBuildingContext(@Nonnull Evita evita, @Nonnull CatalogContract catalog) {
		super(evita);
		this.catalog = catalog;
		this.entitySchemas = evita.queryCatalog(catalog.getName(), session -> {
			final Set<String> collections = session.getAllEntityTypes();
			final Set<EntitySchemaContract> schemas = createHashSet(collections.size());
			collections.forEach(
				c -> schemas.add(
					session.getEntitySchema(c)
						.orElseThrow(() -> new EvitaInternalError("Schema for `" + c + "` entity type unexpectedly not found!"))
				)
			);
			return schemas;
		});

		entityObjects = new ArrayList<>(this.entitySchemas.size());
		localizedEntityObjects = new ArrayList<>(this.entitySchemas.size());
	}

	@Nonnull
	@Override
	protected Info buildOpenApiInfo() {
		final Info info = new Info();
		info.setTitle("Web services for catalog `" + getCatalog().getName() + "`.");
		info.setContact(new Contact().email("novotny@fg.cz").url("https://www.fg.cz"));
		info.setVersion("1.0.0-oas3");
		return info;
	}

	@Nonnull
	public CatalogSchemaContract getSchema() {
		return catalog.getSchema();
	}

	@Nonnull
	public OpenApiTypeReference registerEntityObject(@Nonnull OpenApiObject entityObject) {
		final OpenApiTypeReference ref = registerType(entityObject);
		entityObjects.add(ref);
		return ref;
	}

	@Nonnull
	public OpenApiTypeReference registerLocalizedEntityObject(@Nonnull OpenApiObject entityObject) {
		final OpenApiTypeReference ref = registerType(entityObject);
		localizedEntityObjects.add(ref);
		return ref;
	}
}
