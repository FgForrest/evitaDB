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
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

/**
 * The `spacing` container defines a set of {@link SpacingGap} rules that reserve visual slots on particular pages
 * for non-entity content such as advertisements, banner images, or editorial inserts. It is a child constraint of
 * {@link Page} and has no meaning outside of that context.
 *
 * ## How spacing works
 *
 * When computing a page result, the engine evaluates every {@link SpacingGap} rule contained in the `spacing`
 * container against the current page number. All rules whose `onPage` expression evaluates to `true` are applied,
 * and their sizes are summed. The resulting total gap count is subtracted from the page size to determine how many
 * entity records are actually fetched and returned. If no rules match, the page is filled with the full page size
 * worth of entities.
 *
 * For example, with `page(1, 20, spacing(...))` on a page that accumulates a gap of `3`, only `17` entities are
 * returned — leaving `3` slots for the client to fill with non-entity UI elements.
 *
 * ## Expression language
 *
 * Each `gap` expression has access to a single variable, `$pageNumber`, representing the one-based index of the
 * page currently being evaluated. The expression must evaluate to a boolean value. The full grammar of the expression
 * language is documented at
 * [the separate page](https://evitadb.io/documentation/user/en/query/expression-language.md).
 *
 * ## Applicability
 *
 * A `spacing` container is considered applicable only when it contains at least one {@link SpacingGap} child. An
 * empty `spacing()` call returns `null` from the factory method and is excluded from the query tree.
 *
 * ## Usage example
 *
 * Two gap rules are defined: one reserving 2 slots on odd pages 1–6 (for a wide ad banner), and one reserving
 * an additional slot on even pages 2–6 (for a blog teaser):
 *
 * ```evitaql
 * require(
 *    page(
 *       1, 20,
 *       spacing(
 *          gap(2, "($pageNumber - 1) % 2 == 0 && $pageNumber <= 6"),
 *          gap(1, "$pageNumber % 2 == 0 && $pageNumber <= 6")
 *       )
 *    )
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/paging#spacing)
 *
 * @see Page
 * @see SpacingGap
 * @author Jan Novotný, FG Forrest a.s. (c) 2021
 **/
@ConstraintDefinition(
	name = "spacing",
	shortDescription = "The constraint defines rules for reserving visual slots (gaps) on specific pages for non-entity content such as advertisements or banners.",
	userDocsLink = "/documentation/query/requirements/paging#spacing",
	supportedIn = ConstraintDomain.SEGMENT
)
public class Spacing extends AbstractRequireConstraintContainer implements GenericConstraint<RequireConstraint> {
	@Serial private static final long serialVersionUID = 6352220342769661652L;

	@Creator
	public Spacing(@Nonnull @Child(domain = ConstraintDomain.SEGMENT) SpacingGap... children) {
		super(children);
	}

	/**
	 * Returns all gap rules defined in this container.
	 *
	 * @return array of gap rules
	 */
	@Nonnull
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
		Assert.isTrue(ArrayUtils.isEmpty(newArguments), "Spacing container doesn't support arguments!");
		return new Spacing(getGaps());
	}
}