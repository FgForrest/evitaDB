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

package io.evitadb.api.query.require;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * The `spacing` allows to define multiple rules for inserting gaps instead of entities on particular pages. The gaps
 * are defined by the {@link SpacingGap} sub-constraints, which specify the number of entities that should be skipped
 * on the page when the `onPage` expression is evaluated to true.
 *
 * First gap space that satisfies the condition is used. If no gap space is satisfied, the page contains the number of
 * entities defined by the `page` requirement (as long as there is enough entities available in the result).
 *
 * Example of usage:
 *
 * 1. one ad block on each page, up to page 6
 * 2. an additional block of blog post teasers on the first three even pages
 *
 * <pre>
 * require(
 *    page(
 *       1, 20,
 *       spacing(
 *          gap(2, "($pageNumber - 1) % 2 == 0 && $pageNumber <= 6"),
 *          gap(1, "$pageNumber % 2 == 0 && $pageNumber <= 6")
 *       )
 *    )
 * )
 * </pre>
 *
 * todo jno - document grammar
 * The grammar of the expression language is documented on the <a href="https://evitadb.io/documentation/expression">the separate page</a>.
 * In the context of this constraint, the expression can use only the `$pageNumber` variable, which represents
 * the currently examined page number.
 *
 * <p><a href="https://evitadb.io/documentation/query/requirements/paging#spacing">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "spacing",
	shortDescription = "The container allows to define rules for inserting gaps instead of entities on particular pages.",
	userDocsLink = "/documentation/query/requirements/paging#spacing"
)
public class Spacing extends AbstractRequireConstraintContainer implements GenericConstraint<RequireConstraint> {
	@Serial private static final long serialVersionUID = 6352220342769661652L;

	@Creator
	public Spacing(@Nonnull @Child SpacingGap... children) {
		super(children);
	}

	/**
	 * Returns all gap rules defined in this container.
	 *
	 * @return array of gap rules
	 */
	@Nullable
	public SpacingGap[] getGaps() {
		return Arrays.stream(getChildren())
			.map(SpacingGap.class::cast)
			.toArray(SpacingGap[]::new);
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(@Nonnull RequireConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isPremiseValid(additionalChildren.length == 0, "Spacing cannot have additional children!");
		return new Spacing(
			Arrays.stream(children)
				.peek(it -> Assert.isPremiseValid(it instanceof SpacingGap, "Spacing can only contain `gap` sub-constraints!"))
				.map(SpacingGap.class::cast)
				.toArray(SpacingGap[]::new)
		);
	}

	@Override
	public boolean isNecessary() {
		return isApplicable();
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		throw new UnsupportedOperationException("Segments container doesn't support arguments!");
	}
}