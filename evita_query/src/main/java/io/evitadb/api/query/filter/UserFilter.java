/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Set;

/**
 * The `userFilter` constraint is a behavioral container that separates user-controlled filtering conditions from mandatory system-defined
 * constraints. While it functions identically to {@link And} for basic query execution, it plays a critical role during extra result computations
 * such as facet summaries and attribute histograms, where the enclosed constraints can be temporarily excluded or modified to compute broader
 * result statistics.
 *
 * ## Purpose and Design Intent
 *
 * In typical e-commerce search interfaces, some filters are controlled by end users (e.g., facet selections, price ranges, attribute filters)
 * while others are mandatory business rules (e.g., "only show products in stock", "only show items in user's country"). When computing facet
 * statistics or attribute histograms, the system needs to distinguish between these two categories: user-controlled filters should be excluded
 * or relaxed to show the full range of available facet options and histogram data, while mandatory constraints must remain in effect.
 *
 * ## Forbidden Children
 *
 * The `userFilter` constraint prohibits certain child constraints that define fundamental query context or separate entity scopes:
 *
 * - {@link EntityLocaleEquals}: locale is considered a mandatory context parameter
 * - {@link PriceInCurrency}, {@link PriceInPriceLists}, {@link PriceValidIn}: price context is mandatory for price calculations
 * - {@link HierarchyWithin}, {@link HierarchyWithinRoot}: hierarchy scope is considered mandatory
 * - {@link ReferenceHaving}: reference filtering defines a separate entity scope (implements {@link SeparateEntityScopeContainer})
 * - Nested {@link UserFilter}: prevents ambiguous nesting of user-controlled scopes
 *
 * These constraints must appear outside `userFilter` to ensure consistent query semantics.
 *
 * ## Impact on Extra Result Computations
 *
 * During facet summary and histogram calculations, the query engine treats `userFilter` content specially:
 *
 * **Facet Summary Calculation**: When computing {@link io.evitadb.api.requestResponse.extraResult.FacetSummary}, the engine temporarily excludes
 * or modifies constraints within `userFilter` to determine how many results would be available if the user changed their facet selections. Facet
 * constraints placed inside `userFilter` allow the facet summary to show the full breadth of available options (including currently unselected
 * facets), while facet constraints outside `userFilter` restrict the summary to only compatible facets.
 *
 * **Attribute Histograms**: Similarly, when computing {@link io.evitadb.api.requestResponse.extraResult.Histogram} for attributes, the engine can
 * relax user-controlled filters to show the full distribution of attribute values across the broader result set, while respecting mandatory
 * constraints outside `userFilter`.
 *
 * The engine represents this constraint as {@link io.evitadb.core.query.algebra.facet.UserFilterFormula}, which is omitted during the initial
 * phase of facet summary computation to isolate the user-controlled portion of the query tree.
 *
 * ## EvitaQL Syntax
 *
 * ```
 * userFilter(
 *     attributeEquals('available', true),
 *     facetHaving('brand', entityHaving(attributeInSet('code', 'apple', 'samsung'))),
 *     priceBetween(100, 500)
 * )
 * ```
 *
 * In this example, the attribute filter, facet selection, and price range are user-controlled and can be relaxed during extra result computations,
 * while any constraints outside this container remain mandatory.
 *
 * ## Usage Examples
 *
 * **Example 1: Separating user filters from mandatory constraints**
 *
 * ```
 * query(
 *     collection('Product'),
 *     filterBy(
 *         and(
 *             entityLocaleEquals(Locale.ENGLISH),
 *             attributeEquals('inStock', true), // mandatory: only show in-stock products
 *             userFilter(
 *                 facetHaving('brand', entityHaving(attributeInSet('code', 'apple'))), // user-controlled facet
 *                 priceBetween(100, 500) // user-controlled price filter
 *             )
 *         )
 *     ),
 *     require(
 *         facetSummary(IMPACT) // will compute impact by relaxing constraints inside userFilter
 *     )
 * )
 * ```
 *
 * **Example 2: Impact on facet summary breadth**
 *
 * ```
 * // Facet constraint INSIDE userFilter: facet summary shows all available brand options
 * filterBy(
 *     userFilter(
 *         facetHaving('brand', entityHaving(attributeInSet('code', 'apple')))
 *     )
 * )
 *
 * // Facet constraint OUTSIDE userFilter: facet summary shows only brands compatible with current selection
 * filterBy(
 *     facetHaving('brand', entityHaving(attributeInSet('code', 'apple')))
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/filtering/behavioral#user-filter)
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "userFilter",
	shortDescription = "The container for constraints that are controlled by the user (client UI widgets). " +
		"It is used mainly to distinguish between user constraint (refining the search) and program defined " +
		"constraints (considered mandatory), when the extra results are computed.",
	userDocsLink = "/documentation/query/filtering/behavioral#user-filter",
	supportedIn = ConstraintDomain.ENTITY
)
public class UserFilter extends AbstractFilterConstraintContainer implements GenericConstraint<FilterConstraint> {
	@Serial private static final long serialVersionUID = 4780024361058355295L;
	private static final Set<Class<? extends FilterConstraint>> FORBIDDEN_CHILDREN = Set.of(
		EntityLocaleEquals.class,
		PriceInCurrency.class,
		PriceInPriceLists.class,
		PriceValidIn.class,
		HierarchyWithin.class,
		HierarchyWithinRoot.class,
		ReferenceHaving.class,
		UserFilter.class
	);

	@Creator
	public UserFilter(@Nonnull
                      @Child(
						  forbidden = {
							  EntityLocaleEquals.class,
							  PriceInCurrency.class,
							  PriceInPriceLists.class,
							  PriceValidIn.class,
							  HierarchyWithin.class,
							  HierarchyWithinRoot.class,
							  ReferenceHaving.class,
							  UserFilter.class
						  }
                      )
                      FilterConstraint... children) {
		super(children);
		StringBuilder forbidden = null;
		for (final FilterConstraint child : children) {
			if (FORBIDDEN_CHILDREN.contains(child.getClass())) {
				if (forbidden == null) {
					forbidden = new StringBuilder(128);
				} else {
					forbidden.append(',');
				}
				forbidden.append(StringUtils.uncapitalize(child.getClass().getSimpleName()));
			}
		}
		if (forbidden != null) {
			throw new EvitaInvalidUsageException(
				"Constraint(s) " + forbidden + " are forbidden in " + getName() + " query container!"
			);
		}
	}

	@Override
	public boolean isNecessary() {
		return getChildren().length > 0;
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(@Nonnull FilterConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isTrue(ArrayUtils.isEmpty(additionalChildren), "UserFilter doesn't accept other than filtering constraints!");
		return new UserFilter(children);
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException("UserFilter filtering query has no arguments!");
	}

}
