/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;
import io.evitadb.api.requestResponse.data.annotation.Reference;
import io.evitadb.api.requestResponse.data.annotation.ReferenceRef;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;
import io.evitadb.api.requestResponse.data.annotation.ReflectedReference;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * Example interface for ClassSchemaAnalyzerTest.
 *
 * Reflected counterpart of `GetterBasedEntityWithReferenceAndReferenceRef`. The same logical
 * reference is exposed via two getters on the entity:
 *
 * - the mandatory getter `getMarketingBrand()` carries `@ReflectedReference("products")` and
 *   returns the reflected DTO directly,
 * - the secondary `getMarketingBrandIfExists()` getter returns `Optional` and carries
 *   `@ReferenceRef("marketingBrand")` to point at the same reference name.
 *
 * The reflected DTO additionally declares an `@Attribute` with description, so that during the
 * second analyzer pass (the `@ReferenceRef` path) the reflected reference is re-opened and
 * `withReflectedReferenceToEntity` is called a second time on the same builder. This is the
 * reflected counterpart of the dual-mapping scenario that originally surfaced the
 * `ReferenceSchemaBuilder` bug.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Entity(name = GetterBasedEntityWithReflectedReferenceAndReferenceRef.ENTITY_NAME)
public interface GetterBasedEntityWithReflectedReferenceAndReferenceRef {

	String ENTITY_NAME = "EntityWithReflectedAndReferenceRef";
	String REFERENCE_NAME = "marketingBrand";
	String SOURCE_REFERENCE_NAME = "products";
	String ATTRIBUTE_NAME = "market";
	String ATTRIBUTE_DESCRIPTION = "Market identifier this brand operates in.";

	/**
	 * Returns the primary key of the entity.
	 *
	 * @return primary key
	 */
	@PrimaryKey
	int getId();

	/**
	 * Mandatory accessor for the marketing brand reflected reference. Carries the
	 * `@ReflectedReference` annotation that mirrors the `products` reference declared on
	 * the `Brand` entity.
	 *
	 * @return marketing brand reflected reference (never `null`)
	 */
	@ReflectedReference(ofName = SOURCE_REFERENCE_NAME)
	@Nonnull
	MarketingBrandRef getMarketingBrand();

	/**
	 * Secondary, optional accessor pointing at the same reflected reference name through
	 * `@ReferenceRef`. Triggers the second analyzer pass over the same reference, which re-runs
	 * the attribute analysis and is the path that surfaces the bug under investigation in the
	 * reflected sibling builder.
	 *
	 * @return optional marketing brand reflected reference
	 */
	@ReferenceRef(REFERENCE_NAME)
	@Nonnull
	Optional<MarketingBrandRef> getMarketingBrandIfExists();

	/**
	 * Reflected DTO that targets the `Brand` entity and exposes a single attribute with a
	 * description that does not exist on the source `products` reference.
	 */
	interface MarketingBrandRef {

		/**
		 * Returns the referenced brand entity.
		 *
		 * @return referenced brand entity
		 */
		@ReferencedEntity
		Brand getBrand();

		/**
		 * Returns the market attribute with description. The description is the part that the
		 * second analyzer pass conflicts on in the bug under investigation.
		 *
		 * @return market identifier
		 */
		@Attribute(name = ATTRIBUTE_NAME, description = ATTRIBUTE_DESCRIPTION)
		String getMarket();

	}

	/**
	 * Brand entity that holds the source `products` reference reflected from this entity.
	 */
	@Entity
	interface Brand {

		/**
		 * Returns the primary key of the brand.
		 *
		 * @return primary key
		 */
		@PrimaryKey
		int getId();

		/**
		 * Returns the products linked to this brand. This is the source reference that the
		 * main entity reflects from.
		 *
		 * @return product references
		 */
		@Reference(indexed = ReferenceIndexType.FOR_FILTERING)
		ProductRef[] getProducts();

	}

	/**
	 * Source-side DTO declaring the standard reference from `Brand` to the main entity.
	 */
	interface ProductRef {

		/**
		 * Returns the referenced entity.
		 *
		 * @return product entity
		 */
		@ReferencedEntity
		GetterBasedEntityWithReflectedReferenceAndReferenceRef getProduct();

	}

}
