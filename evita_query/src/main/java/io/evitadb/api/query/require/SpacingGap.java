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
 * The `gap` constraint is a leaf rule inside a {@link Spacing} container that reserves a fixed number of visual
 * slots on a specific page (or set of pages). It is only valid as a direct child of {@link Spacing} and cannot be
 * used anywhere else in the query tree. The EvitaQL name for this constraint is `gap` (not `spacingGap`), because
 * nesting inside `spacing` already provides sufficient disambiguation.
 *
 * ## Arguments
 *
 * - `size` — the number of entity slots to reserve on matching pages. Must be greater than zero.
 * - `onPage` — a boolean expression evaluated against `$pageNumber` (one-based integer). The gap is applied only
 *   when this expression returns `true`. The expression is parsed into an {@link io.evitadb.dataType.expression.Expression}
 *   at constraint construction time, so any syntax errors are reported immediately rather than at query execution.
 *
 * ## Additive semantics
 *
 * Multiple `gap` rules inside a single `spacing` container are evaluated independently. All rules that match the
 * current page have their sizes summed. On a page where two rules match with sizes `2` and `1`, a total gap of `3`
 * slots is reserved, and only `pageSize - 3` entities are returned.
 *
 * ## Expression language
 *
 * The expression has access to only a single variable, `$pageNumber`. The full expression grammar is documented at
 * [the separate page](https://evitadb.io/documentation/user/en/query/expression-language.md).
 *
 * ## Usage example
 *
 * Reserve 2 slots on odd pages 1–6 for a wide ad banner, and 1 additional slot on even pages 2–6 for a teaser:
 *
 * ```evitaql
 * spacing(
 *    gap(2, "($pageNumber - 1) % 2 == 0 && $pageNumber <= 6"),
 *    gap(1, "$pageNumber % 2 == 0 && $pageNumber <= 6")
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/paging#spacing-gap)
 *
 * @see Spacing
 * @see Page
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 **/
@ConstraintDefinition(
	name = "gap",
	shortDescription = "The constraint reserves a fixed number of visual slots on a specific page for non-entity content within a spacing container.",
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