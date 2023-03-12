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

package io.evitadb.externalApi.rest.api.catalog.dataApi.builder;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.externalApi.rest.api.catalog.builder.CatalogRestBuildingContext;
import io.evitadb.externalApi.rest.api.openApi.OpenApiSimpleType;
import io.evitadb.externalApi.rest.exception.OpenApiBuildingError;
import io.evitadb.utils.Assert;
import lombok.Data;

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * This context is used to build REST API part single evitaDB collection.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Data
public class CollectionDataApiRestBuildingContext {

	@Nonnull private final CatalogRestBuildingContext catalogCtx;
	@Nonnull private final EntitySchemaContract schema;

	private OpenApiSimpleType filterByObject;
	private OpenApiSimpleType localizedFilterByObject;

	private OpenApiSimpleType orderByObject;

	private OpenApiSimpleType requiredForListObject;
	private OpenApiSimpleType requiredForQueryObject;
	private OpenApiSimpleType requiredForDeleteObject;

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
	public void setFilterByObject(@Nonnull OpenApiSimpleType filterByObject) {
		Assert.isPremiseValid(
			this.filterByObject == null,
			() -> new OpenApiBuildingError("FilterBy object for schema `" + schema.getName() + "` has been already initialized.")
		);
		this.filterByObject = filterByObject;
	}

	@Nonnull
	public OpenApiSimpleType getFilterByObject() {
		return Optional.ofNullable(filterByObject)
			.orElseThrow(() -> new OpenApiBuildingError("FilterBy for schema `" + schema.getName() + "` has not been initialized."));
	}

	/**
	 * Set built filterBy object corresponding to this schema and localized entity object. Can be set only once before all other methods need it.
	 */
	public void setLocalizedFilterByObject(@Nonnull OpenApiSimpleType filterByLocalizedInputObject) {
		Assert.isPremiseValid(
			this.localizedFilterByObject == null,
			() -> new OpenApiBuildingError("FilterBy object for schema `" + schema.getName() + "` has been already initialized.")
		);
		this.localizedFilterByObject = filterByLocalizedInputObject;
	}

	/**
	 * Set built orderBy object corresponding to this schema. Can be set only once before all other methods need it.
	 */
	public void setOrderByObject(@Nonnull OpenApiSimpleType orderByObject) {
		Assert.isPremiseValid(
			this.orderByObject == null,
			() -> new OpenApiBuildingError("OrderBy object for schema `" + schema.getName() + "` has been already initialized.")
		);
		this.orderByObject = orderByObject;
	}

	@Nonnull
	public OpenApiSimpleType getOrderByObject() {
		return Optional.ofNullable(orderByObject)
			.orElseThrow(() -> new OpenApiBuildingError("OrderBy for schema `" + schema.getName() + "` has not been initialized."));
	}

	public void setRequiredForListObject(@Nonnull OpenApiSimpleType requiredForListObject) {
		Assert.isPremiseValid(
			this.requiredForListObject == null,
			() -> new OpenApiBuildingError("Required for list object for schema `" + schema.getName() + "` has been already initialized.")
		);
		this.requiredForListObject = requiredForListObject;
	}

	@Nonnull
	public OpenApiSimpleType getRequiredForListObject() {
		return Optional.ofNullable(requiredForListObject)
			.orElseThrow(() -> new OpenApiBuildingError("Required for list object for schema `" + schema.getName() + "` has not been initialized."));
	}

	public void setRequiredForQueryObject(@Nonnull OpenApiSimpleType requiredForQueryObject) {
		Assert.isPremiseValid(
			this.requiredForQueryObject == null,
			() -> new OpenApiBuildingError("Required for query object for schema `" + schema.getName() + "` has been already initialized.")
		);
		this.requiredForQueryObject = requiredForQueryObject;
	}

	@Nonnull
	public OpenApiSimpleType getRequiredForQueryObject() {
		return Optional.ofNullable(requiredForQueryObject)
			.orElseThrow(() -> new OpenApiBuildingError("Required for query object for schema `" + schema.getName() + "` has not been initialized."));
	}

	public void setRequiredForDeleteObject(@Nonnull OpenApiSimpleType requiredForDeleteObject) {
		Assert.isPremiseValid(
			this.requiredForDeleteObject == null,
			() -> new OpenApiBuildingError("Required for query object for schema `" + schema.getName() + "` has been already initialized.")
		);
		this.requiredForDeleteObject = requiredForDeleteObject;
	}

	@Nonnull
	public OpenApiSimpleType getRequiredForDeleteObject() {
		return Optional.ofNullable(requiredForDeleteObject)
			.orElseThrow(() -> new OpenApiBuildingError("Required for query object for schema `" + schema.getName() + "` has not been initialized."));
	}
}
