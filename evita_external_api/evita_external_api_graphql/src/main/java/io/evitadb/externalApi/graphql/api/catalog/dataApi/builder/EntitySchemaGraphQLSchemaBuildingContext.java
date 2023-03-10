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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.builder;

import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLType;
import io.evitadb.api.CatalogContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.graphql.api.builder.BuiltFieldDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.exception.GraphQLSchemaBuildingError;
import io.evitadb.utils.Assert;
import lombok.Data;

import javax.annotation.Nonnull;
import java.util.Optional;

import static io.evitadb.externalApi.api.ExternalApiNamingConventions.TYPE_NAME_NAMING_CONVENTION;

/**
 * Collection context object for building entity collection-specific GraphQL schema.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Data
public class EntitySchemaGraphQLSchemaBuildingContext {

	@Nonnull
	private final CatalogGraphQLSchemaBuildingContext catalogCtx;
	@Nonnull
	private final EntitySchemaContract schema;

	private GraphQLInputType filterByInputObject;
	private GraphQLInputType orderByInputObject;

	@Nonnull
	public CatalogContract getCatalog() {
		return catalogCtx.getCatalog();
	}

	public void registerEntityObject(@Nonnull GraphQLObjectType entityObject) {
		catalogCtx.registerEntityObject(schema.getName(), entityObject);
	}

	/**
	 * Set built filterBy object corresponding to this schema. Can be set only once before all other methods need it.
	 */
	public void setFilterByInputObject(@Nonnull GraphQLType filterByInputObject) {
		Assert.isPremiseValid(
			this.filterByInputObject == null,
			() -> new GraphQLSchemaBuildingError("FilterBy input object for schema `" + schema.getName() + "` has been already initialized.")
		);
		this.filterByInputObject = (GraphQLInputType) filterByInputObject;
	}

	/**
	 * Returns filterBy object it has been already initialized.
	 */
	public GraphQLInputType getFilterByInputObject() {
		return Optional.ofNullable(filterByInputObject)
			.orElseThrow(() -> new GraphQLSchemaBuildingError("FilterBy input object for schema `" + schema.getName() + "` has not been initialized yet."));
	}

	/**
	 * Set built orderBy object corresponding to this schema. Can be set only once before all other methods need it.
	 */
	public void setOrderByInputObject(@Nonnull GraphQLType orderByInputObject) {
		Assert.isPremiseValid(
			this.orderByInputObject == null,
			() -> new GraphQLSchemaBuildingError("OrderBy input object for schema `" + schema.getName() + "` has been already initialized.")
		);
		this.orderByInputObject = (GraphQLInputType) orderByInputObject;
	}

	/**
	 * Returns orderBy object it has been already initialized.
	 */
	public GraphQLInputType getOrderByInputObject() {
		return Optional.ofNullable(orderByInputObject)
			.orElseThrow(() -> new GraphQLSchemaBuildingError("OrderBy input object for schema `" + schema.getName() + "` has not been initialized yet."));
	}

	/**
	 * Registers new GraphQL field to the main entity object represented by this context.
	 */
	public void registerEntityField(@Nonnull GraphQLObjectType.Builder entityObjectBuilder,
	                                @Nonnull BuiltFieldDescriptor entityField) {
		catalogCtx.registerFieldToObject(
			schema.getNameVariant(TYPE_NAME_NAMING_CONVENTION),
			entityObjectBuilder,
			entityField
		);
	}
}
