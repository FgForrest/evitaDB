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
import io.evitadb.utils.StringUtils;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The `userFilter` works identically to the and constraint, but it distinguishes the filter scope, which is controlled
 * by the user through some kind of user interface, from the rest of the query, which contains the mandatory constraints
 * on the result set. The user-defined scope can be modified during certain calculations (such as the facet or histogram
 * calculation), while the mandatory part outside of `userFilter` cannot.
 *
 * Example:
 *
 * <pre>
 * userFilter(
 *   facetHaving(
 *     "brand",
 *     entityHaving(
 *       attributeInSet("code", "amazon")
 *     )
 *   )
 * )
 * </pre>
 *
 * <p><a href="https://evitadb.io/documentation/query/filtering/behavioral#user-filter">Visit detailed user documentation</a></p>
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
