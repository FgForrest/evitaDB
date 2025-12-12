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

package io.evitadb.api.requestResponse.schema.model;

import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.annotation.AssociatedData;
import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.ParentEntity;
import io.evitadb.api.requestResponse.data.annotation.PriceForSale;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;
import io.evitadb.api.requestResponse.data.annotation.Reference;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntityGroup;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;

/**
 * Example interface for ClassSchemaAnalyzerTest.
 * The entity attributes are fully filled up.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Entity(
	name = "CustomEntity",
	description = "CustomEntity description",
	deprecated = "And already deprecated!",
	allowedLocales = {"cs-CZ", "en-US"}
)
public interface GetterBasedEntityWithNonDefaults {

	@PrimaryKey(autoGenerate = false)
	int getId();

	@Attribute(
		name = "customCode",
		description = "customCode description",
		deprecated = "And already deprecated!",
		uniqueGlobally = GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG,
		sortable = true,
		representative = true
	)
	@Nonnull
	String getCode();

	@Attribute(
		name = "customYears",
		description = "customYears description",
		deprecated = "And already deprecated!",
		filterable = true,
		nullable = true
	)
	@Nullable
	default int[] getYears() {
		return new int[] {1978,2005,2020};
	}

	@Attribute(
		name = "customName",
		description = "customName description",
		deprecated = "And already deprecated!",
		localized = true,
		global = true
	)
	@Nonnull
	String getName();

	@AssociatedData(
		name = "customReferencedFiles",
		description = "customReferencedFiles description",
		deprecated = "And already deprecated!",
		nullable = true
	)
	@Nullable
	ReferencedFiles getReferencedFiles();

	@AssociatedData(
		name = "customLocalizedTexts",
		description = "customLocalizedTexts description",
		deprecated = "And already deprecated!",
		localized = true
	)
	@Nullable
	LocalizedTexts getLocalizedTexts();

	@ParentEntity
	int getParentEntity();

	@PriceForSale(
		indexedPricePlaces = 4,
		allowedCurrencies = {"CZK", "EUR"}
	)
	PriceContract getSellingPrice();

	@Reference(indexed = ReferenceIndexType.FOR_FILTERING)
	Brand getMarketingBrand();

	@Reference(
		name = "customLicensingBrand",
		description = "customLicensingBrand description",
		deprecated = "And already deprecated!",
		managed = false,
		entity = "customLicensingBrand",
		groupEntityManaged = false,
		groupEntity = "customBrandGroup",
		allowEmpty = false,
		faceted = true,
		indexed = ReferenceIndexType.FOR_FILTERING
	)
	Brand[] getLicensingBrands();

	record ReferencedFiles(@Nonnull int... fileId) implements Serializable {}

	record LocalizedTexts(@Nonnull String... texts) implements Serializable {}

	interface Brand extends Serializable {

		@ReferencedEntity
		int getBrand();

		@ReferencedEntityGroup
		int getBrandGroup();

		@Attribute(
			name = "customMarket",
			description = "customMarket description",
			deprecated = "And already deprecated!",
			filterable = true,
			sortable = true
		)
		String getMarket();

	}

}
