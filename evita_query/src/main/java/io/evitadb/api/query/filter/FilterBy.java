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
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * This `filterBy` is container for filtering constraints. It is mandatory container when any filtering is to be used.
 * This container allows only one children container with the filtering condition.
 *
 * Example:
 *
 * ```
 * filterBy(
 *     and(
 *        isNotNull('code'),
 *        or(
 *           equals('code', 'ABCD'),
 *           startsWith('title', 'Knife')
 *        )
 *     )
 * )
 * ```
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "filterBy",
	shortDescription = "The container encapsulating inner filter constraint into one main constraint that is required by the query."
)
public class FilterBy extends AbstractFilterConstraintContainer implements GenericConstraint<FilterConstraint> {
	@Serial private static final long serialVersionUID = -2294600717092701351L;

	FilterBy() {
		super();
	}

	// todo lho/jno: what was the problem with array of children? I would like to revert it
//	@Creator
	public FilterBy(@Nonnull @Child FilterConstraint child) {
		super(child);
	}

	// todo lho/jno: what about the unique children? do we want that?
	@Creator
	public FilterBy(@Nonnull @Child(uniqueChildren = true) FilterConstraint... children) {
		super(children);
	}

	@Override
	public boolean isNecessary() {
		return isApplicable();
	}

	// todo lho
//	@Nonnull
//	private FilterConstraint getChild() {
//		if (getChildrenCount() > 1) {
//			return new And(getChildren());
//		}
//		return getChildren()[0];
//	}

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
