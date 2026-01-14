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
import io.evitadb.api.requestResponse.data.annotation.ReferenceRef;

/**
 * V2 evolution: adds attribute to existing reflected reference via @ReferenceRef.
 * Tests the defineReflectedReference() code path in ClassSchemaAnalyzer.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public interface GetterBasedEntityEvolutionV2ModifyReflectedRef
	extends GetterBasedEntityEvolutionV1WithReflectedRef {

	/**
	 * Returns the enhanced brand reference with additional attribute.
	 * The @ReferenceRef annotation signals that we're enhancing an existing reference.
	 *
	 * @return enhanced brand reference
	 */
	@ReferenceRef("marketingBrand")
	@Override
	EnhancedBrandRef getMarketingBrand();

	/**
	 * Enhanced reference interface that adds a new attribute to the existing reflected reference.
	 */
	interface EnhancedBrandRef extends BrandRef {

		/**
		 * Returns the brand note attribute. This is a new attribute added to the reflected reference.
		 *
		 * @return brand note
		 */
		@Attribute(sortable = true)
		String getBrandNote();

	}

}
