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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * This `userFilter` is a container query that could contain any constraints
 * except [priceInPriceLists](#price-in-price-lists),
 * [language](#language), [priceInCurrency](#price-in-currency), [priceValidInTime](#price-valid-in-time),
 * [with hierarchy](#within-hierarchy).
 *
 * These constraints should react to the settings defined by the end user and must be isolated from the base filter so
 * that [facetSummary](#facet-summary) logic can distinguish base filtering query for a facet summary computation.
 * Facet summary must define so-called baseline count - i.e. count of the entities that match system constraints but no
 * optional constraints defined by the user has been applied yet on them. This baseline is also used
 * for [facet statistics](#facet-statistics) computation.
 *
 * This query might be used even without [facetSummary](#facet-summary) - when the result facet counts are not
 * required but still we want the facets use for filtering.
 *
 * Only single `userFilter` query can be used in the query.
 *
 * Example:
 *
 * ```
 * userFilter(
 * greaterThanEq('memory', 8),
 * priceBetween(150.25, 220.0),
 * facet('parameter', 4, 15)
 * )
 * ```
 *
 * Even more complex queries are supported (although it is hard to make up some real life example for such):
 *
 * ```
 * filterBy(
 * and(
 * or(
 * referenceHavingAttribute('CATEGORY', eq(code, 'abc')),
 * referenceHavingAttribute('STOCK', eq(market, 'asia')),
 * ),
 * eq(visibility, true),
 * userFilter(
 * or(
 * and(
 * greaterThanEq('memory', 8),
 * priceBetween(150.25, 220.0)
 * ),
 * and(
 * greaterThanEq('memory', 16),
 * priceBetween(800.0, 1600.0)
 * ),
 * ),
 * facet('parameter', 4, 15)
 * )
 * )
 * ),
 * require(
 * facetGroupDisjunction('parameterType', 4),
 * negatedFacets('parameterType', 8),
 * )
 *
 * ```
 *
 * User filter envelopes the part of the query that is affected by user selection and that is optional. All constraints
 * outside user filter are considered mandatory and must never be altered by [facet summary](#facet-summary) computational
 * logic.
 *
 * Base count of the facets are computed for query having `userFilter` container contents stripped off. The "what-if"
 * counts requested by [impact argument](#facet-summary) are computed from the query including `userFilter` creating
 * multiple sub-queries checking the result for each additional facet selection.
 *
 * [Facet](#facet) filtering constraints must be direct children of the `userFilter` container. Their relationship is by
 * default as follows: facets of the same type within same group are combined by conjunction (OR), facets of different
 * types / groups are combined by disjunction (AND). This default behaviour can be controlled exactly by using any of
 * following require constraints:
 *
 * - [facet groups conjunction](#facet-groups-conjunction) - changes relationship between facets in the same group
 * - [facet groups disjunction](#facet-groups-disjunction) - changes relationship between facet groups
 *
 * All constraints placed directly inside `userFilter` are combined with by conjunction (AND). Other than `facet` filtering
 * constraints (as seen in example) may represent user conditions in non-faceted inputs, such as interval inputs.
 *
 * ***Note:** this query might be a subject to change and affects advanced searching queries such as exclusion facet
 * groups (i.e. facet in group are not represented as multi-select/checkboxes but as exlusive select/radio) or conditional
 * filters (which can be used to apply a certain filter only if it would produce non-empty result, this is good for
 * "sticky" filters).*
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "userFilter",
	shortDescription = "The container for constraints that are controlled by the user (client UI widgets). " +
		"It is used mainly to distinguish between user constraint (refining the search) and program defined " +
		"constraints (considered mandatory), when the extra results are computed.",
	supportedIn = ConstraintDomain.ENTITY
)
public class UserFilter extends AbstractFilterConstraintContainer implements GenericConstraint<FilterConstraint> {
	@Serial private static final long serialVersionUID = 4780024361058355295L;
	private static final Set<Class<? extends FilterConstraint>> FORBIDDEN_CHILDREN;

	static {
		FORBIDDEN_CHILDREN = new HashSet<>(
			Arrays.asList(
				EntityLocaleEquals.class,
				PriceInCurrency.class,
				PriceInPriceLists.class,
				PriceValidIn.class,
				HierarchyWithin.class,
				HierarchyWithinRoot.class,
				ReferenceHaving.class,
				UserFilter.class
			)
		);
	}

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
		if (Arrays.stream(children).map(FilterConstraint::getClass).anyMatch(FORBIDDEN_CHILDREN::contains)) {
			throw new EvitaInvalidUsageException(
				"Constraint(s) " + Arrays.stream(children)
						.map(FilterConstraint::getClass)
						.filter(FORBIDDEN_CHILDREN::contains)
						.map(Class::getSimpleName)
						.map(StringUtils::uncapitalize)
						.collect(Collectors.joining(",")) +
					" are forbidden in " + getName() + " query container!"
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
		return new UserFilter(children);
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException("UserFilter filtering query has no arguments!");
	}

}
