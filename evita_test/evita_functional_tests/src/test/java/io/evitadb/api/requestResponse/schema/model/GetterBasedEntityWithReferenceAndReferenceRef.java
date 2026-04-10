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

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * Example interface for ClassSchemaAnalyzerTest.
 *
 * Reproduces the scenario where the same logical reference is mapped via two getters on the
 * entity:
 *
 * - the mandatory getter `getMarketingBrand()` carries `@Reference` with a description and
 *   returns the reference DTO directly,
 * - the secondary `getMarketingBrandIfExists()` getter returns `Optional` and carries
 *   `@ReferenceRef("marketingBrand")` to point at the same reference name.
 *
 * The reference DTO additionally declares an `@Attribute` with description, so that during the
 * second analyzer pass (the `@ReferenceRef` path) the attribute schema is re-analyzed and the
 * attribute create mutation must reconcile with the description previously emitted by the first
 * pass via a follow-up `ModifyAttributeSchemaDescriptionMutation`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Entity(name = GetterBasedEntityWithReferenceAndReferenceRef.ENTITY_NAME)
public interface GetterBasedEntityWithReferenceAndReferenceRef {

	String ENTITY_NAME = "EntityWithReferenceAndReferenceRef";
	String REFERENCE_NAME = "marketingBrand";
	String REFERENCE_DESCRIPTION = "Mandatory marketing brand reference of the entity.";
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
	 * Mandatory accessor for the marketing brand reference. Carries the `@Reference` annotation
	 * with a description, which together with the description on the inner attribute makes the
	 * generated mutation chain include both `CreateAttributeSchemaMutation` and a follow-up
	 * `ModifyAttributeSchemaDescriptionMutation`.
	 *
	 * @return marketing brand reference (never `null`)
	 */
	@Reference(description = REFERENCE_DESCRIPTION)
	@Nonnull
	MarketingBrandRef getMarketingBrand();

	/**
	 * Secondary, optional accessor pointing at the same reference name through `@ReferenceRef`.
	 * Triggers the second analyzer pass over the same reference, which re-runs the attribute
	 * analysis and is the path that surfaces the bug under investigation.
	 *
	 * @return optional marketing brand reference
	 */
	@ReferenceRef(REFERENCE_NAME)
	@Nonnull
	Optional<MarketingBrandRef> getMarketingBrandIfExists();

	/**
	 * Reference DTO that targets the `MarketingBrand` entity and exposes a single attribute with
	 * a description.
	 */
	interface MarketingBrandRef {

		/**
		 * Returns the referenced marketing brand entity.
		 *
		 * @return referenced brand entity
		 */
		@ReferencedEntity
		MarketingBrand getBrand();

		/**
		 * Returns the market attribute with description. The description is the part that the
		 * second analyzer pass conflicts on.
		 *
		 * @return market identifier
		 */
		@Attribute(name = ATTRIBUTE_NAME, description = ATTRIBUTE_DESCRIPTION)
		String getMarket();

	}

	/**
	 * Target entity for the marketing brand reference.
	 */
	@Entity
	interface MarketingBrand {

		/**
		 * Returns the primary key of the brand.
		 *
		 * @return primary key
		 */
		@PrimaryKey
		int getId();

	}

}
