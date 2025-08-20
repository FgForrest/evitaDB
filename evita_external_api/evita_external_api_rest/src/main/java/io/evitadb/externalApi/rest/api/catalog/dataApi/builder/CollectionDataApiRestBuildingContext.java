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

	private OpenApiSimpleType headerObject;

	private OpenApiSimpleType filterByObject;
	private OpenApiSimpleType localizedFilterByObject;

	private OpenApiSimpleType orderByObject;

	private OpenApiSimpleType requireForListObject;
	private OpenApiSimpleType localizedRequireForListObject;
	private OpenApiSimpleType requireForQueryObject;
	private OpenApiSimpleType localizedRequireForQueryObject;
	private OpenApiSimpleType requireForDeleteObject;

	@Nonnull
	public CatalogContract getCatalog() {
		return this.catalogCtx.getCatalog();
	}

	/**
	 * Gets information whether entity is localized (contains any locale)
	 */
	public boolean isLocalizedEntity() {
		return !this.schema.getLocales().isEmpty();
	}

	public void setHeaderObject(OpenApiSimpleType headerObject) {
		Assert.isPremiseValid(
			this.headerObject == null,
			() -> new OpenApiBuildingError("Header object for schema `" + this.schema.getName() + "` has been already initialized.")
		);
		this.headerObject = headerObject;
	}

	@Nonnull
	public OpenApiSimpleType getHeaderObject() {
		return Optional.ofNullable(this.headerObject)
			.orElseThrow(() -> new OpenApiBuildingError("Header object for schema `" + this.schema.getName() + "` has not been initialized."));
	}

	public void setFilterByObject(@Nonnull OpenApiSimpleType filterByObject) {
		Assert.isPremiseValid(
			this.filterByObject == null,
			() -> new OpenApiBuildingError("FilterBy object for schema `" + this.schema.getName() + "` has been already initialized.")
		);
		this.filterByObject = filterByObject;
	}

	@Nonnull
	public OpenApiSimpleType getFilterByObject() {
		return Optional.ofNullable(this.filterByObject)
			.orElseThrow(() -> new OpenApiBuildingError("FilterBy for schema `" + this.schema.getName() + "` has not been initialized."));
	}

	public void setLocalizedFilterByObject(@Nonnull OpenApiSimpleType filterByLocalizedInputObject) {
		Assert.isPremiseValid(
			this.localizedFilterByObject == null,
			() -> new OpenApiBuildingError("FilterBy object for schema `" + this.schema.getName() + "` has been already initialized.")
		);
		this.localizedFilterByObject = filterByLocalizedInputObject;
	}

	@Nonnull
	public OpenApiSimpleType getLocalizedFilterByObject() {
		return Optional.ofNullable(this.localizedFilterByObject)
			.orElseThrow(() -> new OpenApiBuildingError("Localized FilterBy for schema `" + this.schema.getName() + "` has not been initialized."));
	}

	public void setOrderByObject(@Nonnull OpenApiSimpleType orderByObject) {
		Assert.isPremiseValid(
			this.orderByObject == null,
			() -> new OpenApiBuildingError("OrderBy object for schema `" + this.schema.getName() + "` has been already initialized.")
		);
		this.orderByObject = orderByObject;
	}

	@Nonnull
	public OpenApiSimpleType getOrderByObject() {
		return Optional.ofNullable(this.orderByObject)
			.orElseThrow(() -> new OpenApiBuildingError("OrderBy for schema `" + this.schema.getName() + "` has not been initialized."));
	}

	public void setRequireForListObject(@Nonnull OpenApiSimpleType requireForListObject) {
		Assert.isPremiseValid(
			this.requireForListObject == null,
			() -> new OpenApiBuildingError("Require for list object for schema `" + this.schema.getName() + "` has been already initialized.")
		);
		this.requireForListObject = requireForListObject;
	}

	@Nonnull
	public OpenApiSimpleType getRequireForListObject() {
		return Optional.ofNullable(this.requireForListObject)
			.orElseThrow(() -> new OpenApiBuildingError("Require for list object for schema `" + this.schema.getName() + "` has not been initialized."));
	}

	public void setLocalizedRequireForListObject(@Nonnull OpenApiSimpleType localizedRequireForListObject) {
		Assert.isPremiseValid(
			this.localizedRequireForListObject == null,
			() -> new OpenApiBuildingError("Require for list object for schema `" + this.schema.getName() + "` has been already initialized.")
		);
		this.localizedRequireForListObject = localizedRequireForListObject;
	}

	@Nonnull
	public OpenApiSimpleType getLocalizedRequireForListObject() {
		return Optional.ofNullable(this.localizedRequireForListObject)
			.orElseThrow(() -> new OpenApiBuildingError("Localized Require for list object for schema `" + this.schema.getName() + "` has not been initialized."));
	}

	public void setRequireForQueryObject(@Nonnull OpenApiSimpleType requireForQueryObject) {
		Assert.isPremiseValid(
			this.requireForQueryObject == null,
			() -> new OpenApiBuildingError("Require for query object for schema `" + this.schema.getName() + "` has been already initialized.")
		);
		this.requireForQueryObject = requireForQueryObject;
	}

	@Nonnull
	public OpenApiSimpleType getRequireForQueryObject() {
		return Optional.ofNullable(this.requireForQueryObject)
			.orElseThrow(() -> new OpenApiBuildingError("Require for query object for schema `" + this.schema.getName() + "` has not been initialized."));
	}

	public void setLocalizedRequireForQueryObject(@Nonnull OpenApiSimpleType localizedRequireForQueryObject) {
		Assert.isPremiseValid(
			this.localizedRequireForQueryObject == null,
			() -> new OpenApiBuildingError("Localized Require for query object for schema `" + this.schema.getName() + "` has been already initialized.")
		);
		this.localizedRequireForQueryObject = localizedRequireForQueryObject;
	}

	@Nonnull
	public OpenApiSimpleType getLocalizedRequireForQueryObject() {
		return Optional.ofNullable(this.localizedRequireForQueryObject)
			.orElseThrow(() -> new OpenApiBuildingError("Localized Require for query object for schema `" + this.schema.getName() + "` has not been initialized."));
	}

	public void setRequireForDeleteObject(@Nonnull OpenApiSimpleType requireForDeleteObject) {
		Assert.isPremiseValid(
			this.requireForDeleteObject == null,
			() -> new OpenApiBuildingError("Require for query object for schema `" + this.schema.getName() + "` has been already initialized.")
		);
		this.requireForDeleteObject = requireForDeleteObject;
	}

	@Nonnull
	public OpenApiSimpleType getRequireForDeleteObject() {
		return Optional.ofNullable(this.requireForDeleteObject)
			.orElseThrow(() -> new OpenApiBuildingError("Require for query object for schema `" + this.schema.getName() + "` has not been initialized."));
	}
}
