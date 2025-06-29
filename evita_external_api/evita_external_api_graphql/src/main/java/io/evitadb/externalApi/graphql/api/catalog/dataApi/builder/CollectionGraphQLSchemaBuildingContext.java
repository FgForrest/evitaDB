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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.builder;

import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLObjectType;
import io.evitadb.api.CatalogContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.graphql.api.catalog.builder.CatalogGraphQLSchemaBuildingContext;
import io.evitadb.externalApi.graphql.exception.GraphQLSchemaBuildingError;
import io.evitadb.utils.Assert;
import lombok.Data;

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * Collection context object for building entity collection-specific GraphQL schema.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Data
public class CollectionGraphQLSchemaBuildingContext {

	@Nonnull
	private final CatalogGraphQLSchemaBuildingContext catalogCtx;
	@Nonnull
	private final EntitySchemaContract schema;

	private GraphQLInputType headInputObject;
	private GraphQLInputType filterByInputObject;
	private GraphQLInputType orderByInputObject;
	private GraphQLInputType listRequireInputObject;
	private GraphQLInputType queryRequireInputObject;

	@Nonnull
	public CatalogContract getCatalog() {
		return this.catalogCtx.getCatalog();
	}

	public void registerEntityObject(@Nonnull GraphQLObjectType entityObject) {
		this.catalogCtx.registerEntityObject(this.schema.getName(), entityObject);
	}

	/**
	 * Set built head object corresponding to this schema. Can be set only once before all other methods need it.
	 */
	public void setHeadInputObject(@Nonnull GraphQLInputType headInputObject) {
		Assert.isPremiseValid(
			this.headInputObject == null,
			() -> new GraphQLSchemaBuildingError("Head input object for schema `" + this.schema.getName() + "` has been already initialized.")
		);
		this.headInputObject = headInputObject;
	}

	/**
	 * Set built filterBy object corresponding to this schema. Can be set only once before all other methods need it.
	 */
	public void setFilterByInputObject(@Nonnull GraphQLInputType filterByInputObject) {
		Assert.isPremiseValid(
			this.filterByInputObject == null,
			() -> new GraphQLSchemaBuildingError("FilterBy input object for schema `" + this.schema.getName() + "` has been already initialized.")
		);
		this.filterByInputObject = filterByInputObject;
	}

	/**
	 * Returns filterBy object if has been already initialized.
	 */
	@Nonnull
	public GraphQLInputType getFilterByInputObject() {
		return Optional.ofNullable(this.filterByInputObject)
			.orElseThrow(() -> new GraphQLSchemaBuildingError("FilterBy input object for schema `" + this.schema.getName() + "` has not been initialized yet."));
	}

	/**
	 * Set built orderBy object corresponding to this schema. Can be set only once before all other methods need it.
	 */
	public void setOrderByInputObject(@Nonnull GraphQLInputType orderByInputObject) {
		Assert.isPremiseValid(
			this.orderByInputObject == null,
			() -> new GraphQLSchemaBuildingError("OrderBy input object for schema `" + this.schema.getName() + "` has been already initialized.")
		);
		this.orderByInputObject = orderByInputObject;
	}

	/**
	 * Returns orderBy object if has been already initialized.
	 */
	@Nonnull
	public GraphQLInputType getOrderByInputObject() {
		return Optional.ofNullable(this.orderByInputObject)
			.orElseThrow(() -> new GraphQLSchemaBuildingError("OrderBy input object for schema `" + this.schema.getName() + "` has not been initialized yet."));
	}

	/**
	 * Set built require object corresponding to this schema for listing entities. Can be set only once before all other methods need it.
	 */
	public void setListRequireInputObject(@Nonnull GraphQLInputType listRequireInputObject) {
		Assert.isPremiseValid(
			this.listRequireInputObject == null,
			() -> new GraphQLSchemaBuildingError("List require input object for schema `" + this.schema.getName() + "` has been already initialized.")
		);
		this.listRequireInputObject = listRequireInputObject;
	}

	/**
	 * Returns require object for listing entities if has been already initialized.
	 */
	@Nonnull
	public Optional<GraphQLInputType> getListRequireInputObject() {
		return Optional.ofNullable(this.listRequireInputObject);
	}

	/**
	 * Set built require object corresponding to this schema for querying entities. Can be set only once before all other methods need it.
	 */
	public void setQueryRequireInputObject(@Nonnull GraphQLInputType queryRequireInputObject) {
		Assert.isPremiseValid(
			this.queryRequireInputObject == null,
			() -> new GraphQLSchemaBuildingError("Query require input object for schema `" + this.schema.getName() + "` has been already initialized.")
		);
		this.queryRequireInputObject = queryRequireInputObject;
	}

	/**
	 * Returns require object for querying entities if has been already initialized.
	 */
	@Nonnull
	public Optional<GraphQLInputType> getQueryRequireInputObject() {
		return Optional.ofNullable(this.queryRequireInputObject);
	}
}
