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

import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;
import io.evitadb.api.requestResponse.data.annotation.Reference;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;
import io.evitadb.api.requestResponse.data.annotation.ReflectedReference;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;

/**
 * Container class for entities that test conflict detection when attempting
 * to create a standard reference over an existing reflected reference.
 * This should result in InvalidSchemaMutationException being thrown.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class EntityWithReflectedReferenceConflict {

	/**
	 * Product entity with a reflected reference to Brand.
	 */
	@Entity(name = Product.ENTITY_NAME)
	public interface Product {

		String ENTITY_NAME = "product";

		/**
		 * Returns the primary key of the product.
		 *
		 * @return primary key
		 */
		@PrimaryKey
		int getId();

		/**
		 * Returns the reflected reference to brand. This reference is reflected
		 * from Brand's products reference.
		 *
		 * @return brand reference
		 */
		@ReflectedReference(ofName = "products")
		BrandRef getBrand();

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

		}

	}

	/**
	 * Brand entity with standard reference to products.
	 */
	@Entity
	public interface Brand {

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

		/**
		 * Reference interface for accessing product data.
		 */
		interface ProductRef {

			/**
			 * Returns the referenced product entity.
			 *
			 * @return product entity
			 */
			@ReferencedEntity
			Product getProduct();

		}

	}

	/**
	 * Invalid product entity that attempts to create a standard reference
	 * where a reflected reference already exists. This should trigger
	 * InvalidSchemaMutationException.
	 */
	public interface InvalidProduct extends Product {

		/**
		 * Attempts to override the reflected reference with a standard reference.
		 * This should fail with InvalidSchemaMutationException.
		 *
		 * @return brand reference
		 */
		@Reference(indexed = ReferenceIndexType.FOR_FILTERING)
		@Override
		BrandRef getBrand();

	}

}
