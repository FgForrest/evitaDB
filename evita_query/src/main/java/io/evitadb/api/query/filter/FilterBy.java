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
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * Filtering constraints allow you to select only a few entities from many that exist in the target collection. It's
 * similar to the "where" clause in SQL. FilterBy container might contain one or more sub-constraints, that are combined
 * by logical disjunction (AND).
 *
 * Example:
 *
 * <pre>
 * filterBy(
 *    isNotNull("code"),
 *    or(
 *       equals("code", "ABCD"),
 *       startsWith("title", "Knife")
 *    )
 * )
 * </pre>
 *
 * <p><a href="https://evitadb.io/documentation/query/basics#filter-by">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "filterBy",
	shortDescription = "The container encapsulating inner filter constraint into one main constraint that is required by the query.",
	userDocsLink = "/documentation/query/basics#filter-by",
	supportedIn = { ConstraintDomain.GENERIC, ConstraintDomain.ENTITY, ConstraintDomain.SEGMENT, ConstraintDomain.INLINE_REFERENCE }
)
public class FilterBy extends AbstractFilterConstraintContainer implements GenericConstraint<FilterConstraint> {
	@Serial private static final long serialVersionUID = -2294600717092701351L;

	FilterBy() {
		super();
	}

	@Creator
	public FilterBy(@Nonnull @Child(uniqueChildren = true) FilterConstraint... children) {
		super(children);
	}

	@Override
	public boolean isNecessary() {
		return isApplicable();
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException("FilterBy filtering query has no arguments!");
	}

	@Nonnull
	@Override
	public FilterConstraint getCopyWithNewChildren(@Nonnull FilterConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isTrue(ArrayUtils.isEmpty(additionalChildren), "FilterBy doesn't accept other than filtering constraints!");
		return children.length > 0 ? new FilterBy(children) : new FilterBy();
	}

}
