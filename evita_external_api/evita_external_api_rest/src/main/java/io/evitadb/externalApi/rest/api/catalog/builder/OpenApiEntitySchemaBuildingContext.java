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
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.rest.api.catalog.builder.constraint.OpenApiConstraintSchemaBuildingContext;
import io.evitadb.externalApi.rest.api.dto.OpenApiSimpleType;
import io.evitadb.externalApi.rest.exception.OpenApiSchemaBuildingError;
import io.evitadb.utils.Assert;
import lombok.Data;

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * This context is used to build OpenApi schema of single Evita entity.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Data
public class OpenApiEntitySchemaBuildingContext {

	@Nonnull private final CatalogRestBuildingContext catalogCtx;
	@Nonnull private final OpenApiConstraintSchemaBuildingContext constraintSchemaBuildingCtx;
	@Nonnull private final EntitySchemaContract schema;

	private OpenApiSimpleType filterByInputObject;
	private OpenApiSimpleType filterByLocalizedInputObject;

	private OpenApiSimpleType orderByInputObject;

	private OpenApiSimpleType requiredForListInputObject;
	private OpenApiSimpleType requiredForQueryInputObject;
	private OpenApiSimpleType requiredForDeleteInputObject;

	@Nonnull
	public CatalogContract getCatalog() {
		return catalogCtx.getCatalog();
	}

	/**
	 * Gets information whether entity is localized (contains any locale)
	 */
	public boolean isLocalizedEntity() {
		return !schema.getLocales().isEmpty();
	}

	/**
	 * Set built filterBy object corresponding to this schema. Can be set only once before all other methods need it.
	 */
	public void setFilterByInputObject(@Nonnull OpenApiSimpleType filterByInputObject) {
		Assert.isPremiseValid(
			this.filterByInputObject == null,
			() -> new OpenApiSchemaBuildingError("FilterBy input object for schema `" + schema.getName() + "` has been already initialized.")
		);
		this.filterByInputObject = filterByInputObject;
	}

	@Nonnull
	public OpenApiSimpleType getFilterByInputObject() {
		return Optional.ofNullable(filterByInputObject)
			.orElseThrow(() -> new OpenApiSchemaBuildingError("FilterBy for schema `" + schema.getName() + "` has not been initialized."));
	}

	/**
	 * Set built filterBy object corresponding to this schema and localized entity object. Can be set only once before all other methods need it.
	 */
	public void setFilterByLocalizedInputObject(@Nonnull OpenApiSimpleType filterByLocalizedInputObject) {
		Assert.isPremiseValid(
			this.filterByLocalizedInputObject == null,
			() -> new OpenApiSchemaBuildingError("FilterBy input object for schema `" + schema.getName() + "` has been already initialized.")
		);
		this.filterByLocalizedInputObject = filterByLocalizedInputObject;
	}

	/**
	 * Set built orderBy object corresponding to this schema. Can be set only once before all other methods need it.
	 */
	public void setOrderByInputObject(@Nonnull OpenApiSimpleType orderByInputObject) {
		Assert.isPremiseValid(
			this.orderByInputObject == null,
			() -> new OpenApiSchemaBuildingError("OrderBy input object for schema `" + schema.getName() + "` has been already initialized.")
		);
		this.orderByInputObject = orderByInputObject;
	}

	@Nonnull
	public OpenApiSimpleType getOrderByInputObject() {
		return Optional.ofNullable(orderByInputObject)
			.orElseThrow(() -> new OpenApiSchemaBuildingError("OrderBy for schema `" + schema.getName() + "` has not been initialized."));
	}

	public void setRequiredForListInputObject(@Nonnull OpenApiSimpleType requiredForListInputObject) {
		Assert.isPremiseValid(
			this.requiredForListInputObject == null,
			() -> new OpenApiSchemaBuildingError("Required for list object for schema `" + schema.getName() + "` has been already initialized.")
		);
		this.requiredForListInputObject = requiredForListInputObject;
	}

	@Nonnull
	public OpenApiSimpleType getRequiredForListInputObject() {
		return Optional.ofNullable(requiredForListInputObject)
			.orElseThrow(() -> new OpenApiSchemaBuildingError("Required for list object for schema `" + schema.getName() + "` has not been initialized."));
	}

	public void setRequiredForQueryInputObject(@Nonnull OpenApiSimpleType requiredForQueryInputObject) {
		Assert.isPremiseValid(
			this.requiredForQueryInputObject == null,
			() -> new OpenApiSchemaBuildingError("Required for query object for schema `" + schema.getName() + "` has been already initialized.")
		);
		this.requiredForQueryInputObject = requiredForQueryInputObject;
	}

	@Nonnull
	public OpenApiSimpleType getRequiredForQueryInputObject() {
		return Optional.ofNullable(requiredForQueryInputObject)
			.orElseThrow(() -> new OpenApiSchemaBuildingError("Required for query object for schema `" + schema.getName() + "` has not been initialized."));
	}

	public void setRequiredForDeleteInputObject(@Nonnull OpenApiSimpleType requiredForDeleteInputObject) {
		Assert.isPremiseValid(
			this.requiredForDeleteInputObject == null,
			() -> new OpenApiSchemaBuildingError("Required for query object for schema `" + schema.getName() + "` has been already initialized.")
		);
		this.requiredForDeleteInputObject = requiredForDeleteInputObject;
	}

	@Nonnull
	public OpenApiSimpleType getRequiredForDeleteInputObject() {
		return Optional.ofNullable(requiredForDeleteInputObject)
			.orElseThrow(() -> new OpenApiSchemaBuildingError("Required for query object for schema `" + schema.getName() + "` has not been initialized."));
	}
}
