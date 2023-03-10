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

package io.evitadb.externalApi.graphql.api.catalog.builder;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.externalApi.graphql.api.builder.GraphQLSchemaBuildingContext;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.Set;

import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * Specific {@link GraphQLSchemaBuildingContext} for catalog-based GraphQL schemas.
 *
 * @author Lukáš Hornych, 2023
 */
public class CatalogGraphQLSchemaBuildingContext extends GraphQLSchemaBuildingContext {

	@Getter
	@Nonnull
	private final CatalogContract catalog;
	@Getter
	@Nonnull
	private final Set<EntitySchemaContract> entitySchemas;

	public CatalogGraphQLSchemaBuildingContext(@Nonnull EvitaContract evita,
	                                           @Nonnull CatalogContract catalog) {
		super(evita);
		this.catalog = catalog;

		this.entitySchemas = evita.queryCatalog(catalog.getName(), session -> {
			final Set<String> collections = session.getAllEntityTypes();
			final Set<EntitySchemaContract> schemas = createHashSet(collections.size());
			collections.forEach(
				c -> schemas.add(
					session.getEntitySchema(c)
						.orElseThrow(() -> new EvitaInternalError("Entity `" + c + "` schema unexpectedly not found!"))
				)
			);
			return schemas;
		});
	}

	@Nonnull
	public CatalogSchemaContract getSchema() {
		return catalog.getSchema();
	}
}
