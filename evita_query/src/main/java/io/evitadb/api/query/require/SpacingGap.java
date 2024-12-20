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

import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;

/**
 * The `gap` constraint can only be used within the {@link Spacing} container and defines a single rule that makes
 * the necessary gap on particular page when the `onPage` expression is evaluated to true. The gap is defined by the
 * `size` argument, which specifies the number of entities that should be skipped on the page.
 *
 * See following example:
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
 * The grammar of the expression language is documented on the <a href="https://evitadb.io/documentation/user/en/query/expression-language.md">the separate page</a>.
 * In the context of this constraint, the expression can use only the `$pageNumber` variable, which represents
 * the currently examined page number.
 *
 * <p><a href="https://evitadb.io/documentation/query/requirements/paging#spacing-gap">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 **/
@ConstraintDefinition(
	name = "gap",
	shortDescription = "The constraint sizes the number of entities in particular segment of the output.",
	userDocsLink = "/documentation/query/requirements/paging#spacing-gap",
	supportedIn = ConstraintDomain.SEGMENT
)
public class SpacingGap extends AbstractRequireConstraintLeaf implements GenericConstraint<RequireConstraint> {
	@Serial private static final long serialVersionUID = -2372173491681325841L;
	private static final String CONSTRAINT_NAME = "gap";

	private SpacingGap(Serializable... arguments) {
		// because this query can be used only within some other segment query, it would be
		// unnecessary to duplicate the segment prefix
		super(CONSTRAINT_NAME, arguments);
	}

	@Creator
	public SpacingGap(int size, @Nonnull Expression onPage) {
		// because this query can be used only within some other segment query, it would be
		// unnecessary to duplicate the segment prefix
		super(CONSTRAINT_NAME, size, onPage);
		Assert.isTrue(size > 0, () -> new EvitaInvalidUsageException("Segment size must be greater than zero."));
	}

	/**
	 * Returns size value constraining the number of items in particular segment.
	 */
	public int getSize() {
		return (Integer) getArguments()[0];
	}

	/**
	 * Returns the expression that must be evaluated to true to apply the gap.
	 */
	@Nonnull
	public Expression getOnPage() {
		return (Expression) getArguments()[1];
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length == 2;
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		Assert.isTrue(
			newArguments.length == 2 && newArguments[0] instanceof Integer && newArguments[1] instanceof ExpressionNode,
			"Spacing gap container accepts only two arguments: size and onPage expression."
		);
		return new SpacingGap(newArguments);
	}

}