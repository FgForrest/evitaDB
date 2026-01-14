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

package io.evitadb.api.requestResponse.schema.model.evolution;

import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;
import io.evitadb.api.requestResponse.data.annotation.Reference;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;
import io.evitadb.api.requestResponse.data.annotation.ReflectedReference;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;

import javax.annotation.Nonnull;

/**
 * V1 entity with reflected reference for evolution testing.
 * This model demonstrates a bidirectional reference relationship where
 * Product has a reflected reference back from Brand.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Entity(name = GetterBasedEntityEvolutionV1WithReflectedRef.ENTITY_NAME)
public interface GetterBasedEntityEvolutionV1WithReflectedRef {

	String ENTITY_NAME = "EntityWithReflectedRefEvolution";

	/**
	 * Returns the primary key of the entity.
	 *
	 * @return primary key
	 */
	@PrimaryKey
	int getId();

	/**
	 * Returns the code of the entity.
	 *
	 * @return code
	 */
	@Attribute
	@Nonnull
	String getCode();

	/**
	 * Returns the reflected reference to the marketing brand.
	 * This reference is reflected from the Brand entity's products reference.
	 *
	 * @return brand reference
	 */
	@ReflectedReference(ofName = "products")
	BrandRef getMarketingBrand();

	/**
	 * Reference interface for accessing brand data.
	 */
	interface BrandRef {

		/**
		 * Returns the referenced brand entity.
		 *
		 * @return brand entity
		 */
		@ReferencedEntity
		Brand getBrand();

		/**
		 * Returns the order attribute from the original reference.
		 *
		 * @return order
		 */
		@Attribute
		int getOrder();

	}

	/**
	 * Brand entity that has a standard reference to products.
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
		 * Returns references to products associated with this brand.
		 *
		 * @return product references
		 */
		@Reference(indexed = ReferenceIndexType.FOR_FILTERING)
		ProductRef[] getProducts();

	}

	/**
	 * Reference interface for accessing product data from Brand.
	 */
	interface ProductRef {

		/**
		 * Returns the referenced product entity.
		 *
		 * @return product entity
		 */
		@ReferencedEntity
		GetterBasedEntityEvolutionV1WithReflectedRef getProduct();

		/**
		 * Returns the order of the product in the brand's collection.
		 *
		 * @return order
		 */
		@Attribute
		int getOrder();

	}

}
