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

package io.evitadb.api.query.descriptor;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.filter.*;
import io.evitadb.api.query.head.Collection;
import io.evitadb.api.query.order.AttributeNatural;
import io.evitadb.api.query.order.EntityProperty;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.order.PriceNatural;
import io.evitadb.api.query.order.Random;
import io.evitadb.api.query.order.ReferenceProperty;
import io.evitadb.api.query.require.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * Simple class to gather all registered query classes.
 * To register new query, simply put its class into a list of registered classes.
 * Actual processing and serving of registered classes is done in {@link ConstraintDescriptorProvider}.
 * <p>
 * <b>Note:</b> This exists because Java reflection does not support listing classes annotated by some annotation and
 * annotation processor cannot access specific types and is unnecessarily complex to set up for simple listing of
 * classes where we know all the classes upfront.
 *
 * @see ConstraintDescriptorProvider
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
class RegisteredConstraintProvider {

	/**
	 * List to register annotated constraints for later descriptor processing.
	 * <p>
	 * Also, constraints have to be annotated with {@link ConstraintDefinition} in
	 * order to be truly processed.
	 */
	static final Set<Class<? extends Constraint<?>>> REGISTERED_CONSTRAINTS = Set.of(
		// --- head constraints ---
		Collection.class,

		// --- filter constraints ---
		// generic
		FilterBy.class,
		And.class,
		Not.class,
		Or.class,
		UserFilter.class,
		// entity
		EntityPrimaryKeyInSet.class,
		EntityLocaleEquals.class,
		EntityHaving.class,
		// attribute
		AttributeBetween.class,
		AttributeContains.class,
		AttributeEndsWith.class,
		AttributeEquals.class,
		AttributeGreaterThan.class,
		AttributeGreaterThanEquals.class,
		AttributeInRange.class,
		AttributeInSet.class,
		AttributeIs.class,
		AttributeLessThan.class,
		AttributeLessThanEquals.class,
		AttributeStartsWith.class,
		// price
		PriceBetween.class,
		PriceInCurrency.class,
		PriceInPriceLists.class,
		PriceValidIn.class,
		// reference
		ReferenceHaving.class,
		// hierarchy
		HierarchyDirectRelation.class,
		HierarchyExcluding.class,
		HierarchyExcludingRoot.class,
		HierarchyWithin.class,
		HierarchyWithinRoot.class,
		// facet
		FacetInSet.class,

		// --- order constraints ---
		// generic
		OrderBy.class,
		Random.class,
		// entity
		EntityProperty.class,
		// attribute
		AttributeNatural.class,
		// price
		PriceNatural.class,
		// reference
		ReferenceProperty.class,

		// --- require constraints ---
		// generic
		Require.class,
		Page.class,
		Strip.class,
		DataInLocales.class,
		QueryTelemetry.class,
		// entity
		EntityFetch.class,
		EntityGroupFetch.class,
		// attribute
		AttributeHistogram.class,
		AttributeContent.class,
		// associated data
		AssociatedDataContent.class,
		// price
		PriceHistogram.class,
		PriceContent.class,
		PriceType.class,
		// references
		ReferenceContent.class,
		// hierarchy
//		HierarchyChildren.class, // todo lho we want this constructor to be a creator but we need support for multiple child parameters
		HierarchyDistance.class,
//		HierarchyFromNode.class, // todo lho we want this constructor to be a creator but we need support for multiple child parameters
//		HierarchyFromRoot.class, // todo lho we want this constructor to be a creator but we need support for multiple child parameters
		HierarchyLevel.class,
		HierarchyNode.class,
		HierarchyOfReference.class,
		HierarchyOfSelf.class,
//		HierarchyParents.class, // todo lho we want this constructor to be a creator but we need support for multiple child parameters
		HierarchyParentsOfSelf.class,
		HierarchyParentsOfReference.class,
//		HierarchySiblings.class, // todo lho we want this constructor to be a creator but we need support for multiple child parameters
		HierarchyStatistics.class,
		HierarchyStopAt.class,
		// facet
		FacetGroupsConjunction.class,
		FacetGroupsDisjunction.class,
		FacetGroupsNegation.class,
		FacetSummary.class,
		FacetSummaryOfReference.class
	);
}
