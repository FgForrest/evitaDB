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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.graphql.api.catalog.builder;

import graphql.schema.GraphQLObjectType;
import io.evitadb.api.CatalogContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.graphql.api.builder.GraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.configuration.GraphQLConfig;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.evitadb.utils.CollectionUtils.createHashMap;
import static io.evitadb.utils.CollectionUtils.createHashSet;

/**
 * Specific {@link GraphQLSchemaBuildingContext} for catalog-based GraphQL schemas.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class CatalogGraphQLSchemaBuildingContext extends GraphQLSchemaBuildingContext {

	@Getter @Nonnull private final CatalogContract catalog;
	@Getter @Nonnull private final Set<Locale> supportedLocales;
	@Getter @Nonnull private final Set<Currency> supportedCurrencies;
	@Getter @Nonnull private final Set<EntitySchemaContract> entitySchemas;
	@Getter @Nonnull private final Map<String, GraphQLObjectType> entityTypeToEntityObject;

	public CatalogGraphQLSchemaBuildingContext(@Nonnull GraphQLConfig config,
	                                           @Nonnull Evita evita,
	                                           @Nonnull CatalogContract catalog) {
		super(config, evita);
		this.catalog = catalog;
		this.supportedLocales = createHashSet(10);
		this.supportedCurrencies = createHashSet(10);

		this.entitySchemas = evita.queryCatalog(catalog.getName(), session -> {
			final Set<String> collections = session.getAllEntityTypes();
			final Set<EntitySchemaContract> schemas = createHashSet(collections.size());
			collections.forEach(c -> {
				final SealedEntitySchema entitySchema = session.getEntitySchema(c)
					.orElseThrow(() -> new GenericEvitaInternalError("Entity `" + c + "` schema unexpectedly not found!"));
				supportedLocales.addAll(entitySchema.getLocales());
				supportedCurrencies.addAll(entitySchema.getCurrencies());
				schemas.add(entitySchema);
			});
			return schemas;
		});
		this.entityTypeToEntityObject = createHashMap(this.entitySchemas.size());
	}

	@Nonnull
	public CatalogSchemaContract getSchema() {
		return catalog.getSchema();
	}

	public void registerEntityObject(@Nonnull String entityType, @Nonnull GraphQLObjectType entityObject) {
		registerType(entityObject);
		entityTypeToEntityObject.putIfAbsent(entityType, entityObject);
	}
}
