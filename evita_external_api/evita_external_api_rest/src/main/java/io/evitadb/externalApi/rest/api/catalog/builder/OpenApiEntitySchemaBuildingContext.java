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
import io.evitadb.externalApi.rest.exception.OpenApiSchemaBuildingError;
import io.evitadb.utils.Assert;
import io.swagger.v3.oas.models.media.Schema;
import lombok.Data;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;

import static io.evitadb.externalApi.rest.api.catalog.builder.SchemaCreator.createReferenceSchema;

/**
 * This context is used to build OpenApi schema of single Evita entity.
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
@Data
public class OpenApiEntitySchemaBuildingContext {
	@Nonnull
	private final CatalogSchemaBuildingContext catalogCtx;
	@Nonnull
	private final OpenApiConstraintSchemaBuildingContext constraintSchemaBuildingCtx;
	@Nonnull
	private final EntitySchemaContract schema;
	@Nullable
	private final Schema<Object> localeEnum;
	@Nullable
	private final Schema<Object> currencyEnum;

	/**
	 * Represents Evita's entity object.
	 */
	private Schema<Object> entityObject;
	/**
	 * Represents Evita's entity object with localized attributes and associated data.
	 */
	private Schema<Object> localizedEntityObject;

	private Schema<Object> filterByInputObject;

	/**
	 * Filter by input object for querying localized entity object
	 */
	private Schema<Object> filterByLocalizedInputObject;
	private Schema<Object> orderByInputObject;

	private Schema<Object> requiredForListInputObject;

	private Schema<Object> requiredForQueryInputObject;

	@Nonnull
	public CatalogContract getCatalog() {
		return catalogCtx.getCatalog();
	}

	@Nonnull
	public Schema<Object> getLocaleEnum() {
		return Optional.ofNullable(localeEnum)
			.orElseThrow(() -> new OpenApiSchemaBuildingError("There are no locales in schema `" + getSchema().getName() + "` and thus its enum shouldn't be used."));
	}

	/**
	 * Gets information whether entity is localized (contains any locale)
	 */
	public boolean isLocalizedEntity() {
		//Entity with locale will always have locale enum so this simple condition can be used instead of querying entity itself
		return localeEnum != null;
	}

	@Nonnull
	public Schema<Object> getCurrencyEnum() {
		return Optional.ofNullable(currencyEnum)
			.orElseThrow(() -> new OpenApiSchemaBuildingError("There are no currencies in schema `" + getSchema().getName() + "` and thus its enum shouldn't be used."));
	}

	/**
	 * Set built entity object corresponding to this schema. Can be set only once before all other methods need it.
	 */
	public void setEntityObject(@Nonnull Schema<Object> entityObject) {
		Assert.isPremiseValid(
			this.entityObject == null,
			() -> new OpenApiSchemaBuildingError("Entity object for schema `" + schema.getName() + "` has been already initialized.")
		);
		this.entityObject = entityObject;
		catalogCtx.registerType(entityObject);
	}

	/**
	 * Returns entity object it has been already initialized.
	 */
	@Nonnull
	public Schema<Object> getEntityObject() {
		return Optional.ofNullable(createReferenceSchema(entityObject))
			.orElseThrow(() -> new OpenApiSchemaBuildingError("Entity object for schema `" + schema.getName() + "` has not been initialized yet."));
	}

	/**
	 * Set built localized entity object corresponding to this schema. Can be set only once before all other methods need it.
	 */
	public void setLocalizedEntityObject(@Nonnull Schema<Object> localizedEntityObject) {
		Assert.isPremiseValid(
			this.localizedEntityObject == null,
			() -> new OpenApiSchemaBuildingError("Localized entity object for schema `" + schema.getName() + "` has been already initialized.")
		);
		this.localizedEntityObject = localizedEntityObject;
		catalogCtx.registerType(this.localizedEntityObject);
	}

	/**
	 * Returns localized entity object it has been already initialized.
	 */
	@Nonnull
	public Schema<Object> getLocalizedEntityObject() {
		return Optional.ofNullable(createReferenceSchema(localizedEntityObject))
			.orElseThrow(() -> new OpenApiSchemaBuildingError("Localized entity object for schema `" + schema.getName() + "` has not been initialized yet."));
	}

	/**
	 * Set built filterBy object corresponding to this schema. Can be set only once before all other methods need it.
	 */
	public void setFilterByInputObject(@Nonnull Schema<Object> filterByInputObject) {
		Assert.isPremiseValid(
			this.filterByInputObject == null,
			() -> new OpenApiSchemaBuildingError("FilterBy input object for schema `" + schema.getName() + "` has been already initialized.")
		);
		this.filterByInputObject = filterByInputObject;
	}

	@Nonnull
	public Schema<Object> getFilterByInputObject() {
		return Optional.ofNullable(createReferenceSchema(filterByInputObject))
			.orElseThrow(() -> new OpenApiSchemaBuildingError("FilterBy for schema `" + schema.getName() + "` has not been initialized."));
	}

	/**
	 * Set built filterBy object corresponding to this schema and localized entity object. Can be set only once before all other methods need it.
	 */
	public void setFilterByLocalizedInputObject(@Nonnull Schema<Object> filterByLocalizedInputObject) {
		Assert.isPremiseValid(
			this.filterByLocalizedInputObject == null,
			() -> new OpenApiSchemaBuildingError("FilterBy input object for schema `" + schema.getName() + "` has been already initialized.")
		);
		this.filterByLocalizedInputObject = filterByLocalizedInputObject;
	}

	/**
	 * Set built orderBy object corresponding to this schema. Can be set only once before all other methods need it.
	 */
	public void setOrderByInputObject(@Nonnull Schema<Object> orderByInputObject) {
		Assert.isPremiseValid(
			this.orderByInputObject == null,
			() -> new OpenApiSchemaBuildingError("OrderBy input object for schema `" + schema.getName() + "` has been already initialized.")
		);
		this.orderByInputObject = orderByInputObject;
	}

	@Nonnull
	public Schema<Object> getOrderByInputObject() {
		return Optional.ofNullable(createReferenceSchema(orderByInputObject))
			.orElseThrow(() -> new OpenApiSchemaBuildingError("OrderBy for schema `" + schema.getName() + "` has not been initialized."));
	}

	public void setRequiredForListInputObject(@Nonnull Schema<Object> requiredForListInputObject) {
		Assert.isPremiseValid(
			this.requiredForListInputObject == null,
			() -> new OpenApiSchemaBuildingError("Required for list object for schema `" + schema.getName() + "` has been already initialized.")
		);
		this.requiredForListInputObject = requiredForListInputObject;
	}

	@Nonnull
	public Schema<Object> getRequiredForListInputObject() {
		return Optional.ofNullable(createReferenceSchema(requiredForListInputObject))
			.orElseThrow(() -> new OpenApiSchemaBuildingError("Required for list object for schema `" + schema.getName() + "` has not been initialized."));
	}

	public void setRequiredForQueryInputObject(@Nonnull Schema<Object> requiredForQueryInputObject) {
		Assert.isPremiseValid(
			this.requiredForQueryInputObject == null,
			() -> new OpenApiSchemaBuildingError("Required for query object for schema `" + schema.getName() + "` has been already initialized.")
		);
		this.requiredForQueryInputObject = requiredForQueryInputObject;
	}

	@Nonnull
	public Schema<Object> getRequiredForQueryInputObject() {
		return Optional.ofNullable(createReferenceSchema(requiredForQueryInputObject))
			.orElseThrow(() -> new OpenApiSchemaBuildingError("Required for query object for schema `" + schema.getName() + "` has not been initialized."));
	}
}
