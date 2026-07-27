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
import io.evitadb.api.requestResponse.data.annotation.Expression;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;
import io.evitadb.api.requestResponse.data.annotation.ReferenceRef;
import io.evitadb.api.requestResponse.data.annotation.ReflectedReference;
import io.evitadb.api.requestResponse.data.annotation.ReflectedReference.InheritableBoolean;
import io.evitadb.api.requestResponse.data.annotation.ScopeReferenceSettings;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import java.util.Optional;

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
	String FACETED_PARTIALLY_EXPRESSION =
		"$reference.groupEntity?.attributes['inputWidgetType'] == 'CHECKBOX'";

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
	 * Scope-qualified reflected reference carrying a `facetedPartially` expression in the
	 * LIVE scope, paired with a `@ReferenceRef` helper. The helper triggers a second pass
	 * of the analyzer; the per-scope `facetedPartially` must survive that re-analysis.
	 */
	@Entity
	interface ReflectedReferenceWithFacetedPartiallyAndRefHelper {

		@PrimaryKey
		int getId();

		@ReflectedReference(
			ofEntity = TARGET_ENTITY,
			ofName = TARGET_REFERENCE,
			scope = {
				@ScopeReferenceSettings(
					scope = Scope.LIVE,
					indexed = ReferenceIndexType.FOR_FILTERING,
					facetedPartially = @Expression(FACETED_PARTIALLY_EXPRESSION)
				)
			}
		)
		BrandRef[] getMarketingBrand();

		@ReferenceRef(REFLECTED_REFERENCE_NAME)
		@Nonnull
		Optional<BrandRef[]> getMarketingBrandIfAvailable();

	}

	/**
	 * Default-faceted reflected reference paired with a `@ReferenceRef` helper getter. The
	 * helper triggers a second pass of the analyzer on the same reference, which must not
	 * overwrite the explicit `facetedInScopes = []` state established by the primary definer.
	 */
	@Entity
	interface ReflectedReferenceDefaultFacetedWithRefHelper {

		@PrimaryKey
		int getId();

		@ReflectedReference(ofEntity = TARGET_ENTITY, ofName = TARGET_REFERENCE)
		BrandRef[] getMarketingBrand();

		@ReferenceRef(REFLECTED_REFERENCE_NAME)
		@Nonnull
		Optional<BrandRef[]> getMarketingBrandIfAvailable();

	}

	/**
	 * Marker interface for the reflected reference return type. The analyzer resolves the
	 * target entity from the `ofEntity` attribute, so no annotations are required here.
	 */
	interface BrandRef {
	}

}
