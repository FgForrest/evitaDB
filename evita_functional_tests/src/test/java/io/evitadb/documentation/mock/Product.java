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

package io.evitadb.documentation.mock;

import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.api.requestResponse.data.SealedInstance;
import io.evitadb.api.requestResponse.data.annotation.AssociatedData;
import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.ParentEntity;
import io.evitadb.api.requestResponse.data.annotation.PriceForSale;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;
import io.evitadb.api.requestResponse.data.annotation.Reference;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntityGroup;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * We have these classes because of `java.md` documentation file. Unfortunately, there is problem with Proxycian and
 * ByteBuddy in the JShell REPL classloader and we need to declare the main interfaces on standard classpath.
 */
@Entity(
	allowedEvolution = {
		EvolutionMode.ADDING_LOCALES,
		EvolutionMode.ADDING_CURRENCIES
	}
)
public interface Product extends SealedInstance<Product, ProductEditor>, Serializable {

	@PrimaryKey(autoGenerate = false)
	int getId();

	@Attribute(
		name = "code",
		description = "Unique code of the product.",
		unique = AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION
	)
	@Nonnull
	String getCode();

	@Attribute(localized = true)
	String getName();

	@Attribute
	String getEAN();

	@Attribute(
		name = "manufacturedBefore",
		description = "How many years ago the product was manufactured.",
		deprecated = "This attribute is obsolete.",
		filterable = true,
		nullable = true
	)
	default int[] getYears() {
		// the default implementation defines default value
		return new int[] {1978,2005,2020};
	}

	@AssociatedData
	ReferencedFiles getReferencedFiles();

	@ParentEntity
	int getParentEntity();

	@PriceForSale
	PriceContract getSellingPrice();

	@Reference(indexed = ReferenceIndexType.FOR_FILTERING, managed = false, groupEntityManaged = false)
	Brand getMarketingBrand();

	@Reference(managed = false, groupEntityManaged = false)
	Brand[] getLicensingBrands();

	record ReferencedFiles(@Nonnull int... fileId) implements Serializable {}

	interface Brand extends Serializable {

		@ReferencedEntity
		int getBrand();

		@ReferencedEntityGroup
		int getBrandGroup();

		@Attribute
		String getMarket();

	}

}
