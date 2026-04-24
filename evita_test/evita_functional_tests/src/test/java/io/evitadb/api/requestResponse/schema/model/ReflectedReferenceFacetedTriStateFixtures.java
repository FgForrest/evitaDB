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
import io.evitadb.api.requestResponse.data.annotation.ReflectedReference;
import io.evitadb.api.requestResponse.data.annotation.ReflectedReference.InheritableBoolean;

/**
 * Fixtures covering every variant of [ReflectedReference#faceted] tri-state
 * [InheritableBoolean]. Each nested entity declares one reflected reference with the same
 * target (`Brand.items`) and differs only in the `faceted` attribute value. The tests use
 * these fixtures to verify that the schema analyzer correctly maps the tri-state value to
 * the corresponding `SetReferenceSchemaFacetedMutation`.
 */
public interface ReflectedReferenceFacetedTriStateFixtures {

	String TARGET_ENTITY = "Brand";
	String TARGET_REFERENCE = "items";
	String REFLECTED_REFERENCE_NAME = "marketingBrand";

	/**
	 * `faceted` attribute omitted — falls back to the annotation default of
	 * [InheritableBoolean#FALSE], which must be treated as "explicitly not faceted".
	 */
	@Entity
	interface ReflectedReferenceDefaultFaceted {

		@PrimaryKey
		int getId();

		@ReflectedReference(ofEntity = TARGET_ENTITY, ofName = TARGET_REFERENCE)
		BrandRef[] getMarketingBrand();

	}

	/**
	 * Explicit `faceted = FALSE` — must be treated as "explicitly not faceted".
	 */
	@Entity
	interface ReflectedReferenceExplicitFalseFaceted {

		@PrimaryKey
		int getId();

		@ReflectedReference(
			ofEntity = TARGET_ENTITY,
			ofName = TARGET_REFERENCE,
			faceted = InheritableBoolean.FALSE
		)
		BrandRef[] getMarketingBrand();

	}

	/**
	 * Explicit `faceted = TRUE` — must be treated as "explicitly faceted in default scope".
	 */
	@Entity
	interface ReflectedReferenceExplicitTrueFaceted {

		@PrimaryKey
		int getId();

		@ReflectedReference(
			ofEntity = TARGET_ENTITY,
			ofName = TARGET_REFERENCE,
			faceted = InheritableBoolean.TRUE
		)
		BrandRef[] getMarketingBrand();

	}

	/**
	 * Explicit `faceted = INHERITED` — the reflected reference must inherit the faceted
	 * flag from the source reference, i.e. no faceted mutation should be emitted.
	 */
	@Entity
	interface ReflectedReferenceExplicitInheritedFaceted {

		@PrimaryKey
		int getId();

		@ReflectedReference(
			ofEntity = TARGET_ENTITY,
			ofName = TARGET_REFERENCE,
			faceted = InheritableBoolean.INHERITED
		)
		BrandRef[] getMarketingBrand();

	}

	/**
	 * Marker interface for the reflected reference return type. The analyzer resolves the
	 * target entity from the `ofEntity` attribute, so no annotations are required here.
	 */
	interface BrandRef {
	}

}
