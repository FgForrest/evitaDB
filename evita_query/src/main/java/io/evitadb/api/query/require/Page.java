/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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
import io.evitadb.api.query.descriptor.annotation.AliasForParameter;
import io.evitadb.api.query.descriptor.annotation.Child;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Optional;

/**
 * The `page` requirement controls the number and slice of entities returned in the query response using classic
 * page-number–based pagination. It is one of two {@link ChunkingRequireConstraint} implementations, the other being
 * {@link Strip}, which uses an offset/limit model instead.
 *
 * ## Defaults and out-of-range behaviour
 *
 * Both arguments accept `null`, in which case defaults are applied: page number defaults to `1` and page size
 * defaults to `20`. If no `page` (or `strip`) requirement is present in the query at all, those same defaults apply.
 * Page number must be greater than zero; page size must be greater than or equal to zero (size `0` produces an empty
 * result while still returning total count metadata).
 *
 * When the requested page exceeds the last available page, the engine returns the **first page** instead of an empty
 * result. This avoids the need for a secondary corrective request when the result set shrinks between two calls
 * (e.g., concurrent deletions between navigating to a deep page).
 *
 * ## Response structure
 *
 * The result is wrapped in a {@link PaginatedList} data-chunk object, which exposes:
 *
 * - `pageNumber` — the actually returned page (may differ from the requested page when clamped)
 * - `pageSize` — the requested page size
 * - `lastPageNumber` — the index of the last available page
 * - `totalRecordCount` — total number of matching entities
 * - `isFirst()`, `isLast()`, `hasNext()`, `hasPrevious()` — navigation flags
 * - `data` — the list of entities on this page
 *
 * ## Spacing support
 *
 * An optional {@link Spacing} child constraint allows reserving a configurable number of visual slots per page for
 * non-entity content such as advertisements, banners, or promotional inserts. Each {@link SpacingGap} rule inside
 * `spacing` reduces the number of entities returned on pages that match its boolean expression. The gap rules are
 * evaluated for each page independently, and their sizes are additive when multiple rules match the same page.
 *
 * ## Usage examples
 *
 * Basic page request:
 *
 * ```evitaql
 * require(
 *    page(1, 24)
 * )
 * ```
 *
 * Page with spacing for ads on selected pages:
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
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/paging#page)
 *
 * @see Strip
 * @see Spacing
 * @see PaginatedList
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "page",
	shortDescription = "The constraint specifies which page of found entities will be returned using page-number-based pagination.",
	userDocsLink = "/documentation/query/requirements/paging#page",
	supportedIn = { ConstraintDomain.GENERIC, ConstraintDomain.REFERENCE }
)
public class Page extends AbstractRequireConstraintContainer implements GenericConstraint<RequireConstraint>, ChunkingRequireConstraint {
	@Serial private static final long serialVersionUID = 1300354074537839696L;

	private Page(@Nonnull Serializable[] arguments, @Nonnull RequireConstraint... children) {
		super(arguments, children);
	}

	public Page(@Nullable Integer number, @Nullable Integer size) {
		this(number, size, null);
	}

	@Creator
	public Page(@Nullable Integer number, @Nullable Integer size, @Nullable @Child(domain = ConstraintDomain.SEGMENT) Spacing spacing) {
		super(
			new Serializable[]{
				Optional.ofNullable(number).orElse(1),
				Optional.ofNullable(size).orElse(20)
			},
			spacing
		);
		Assert.isTrue(
			number == null || number > 0,
			"Page number must be greater than zero."
		);

		Assert.isTrue(
			size == null || size >= 0,
			"Page size must be greater than or equal to zero."
		);
	}

	/**
	 * Returns page number to return in the response.
	 */
	@AliasForParameter("number")
	public int getPageNumber() {
		return (Integer) getArguments()[0];
	}

	/**
	 * Returns page size to return in the response.
	 */
	@AliasForParameter("size")
	public int getPageSize() {
		return (Integer) getArguments()[1];
	}

	/**
	 * Returns optional spacing rules for the page.
	 */
	@Nonnull
	public Optional<Spacing> getSpacing() {
		return Arrays.stream(getChildren())
			.filter(Spacing.class::isInstance)
			.map(Spacing.class::cast)
			.findFirst();
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length == 2;
	}

	@Nonnull
	@Override
	public RequireConstraint getCopyWithNewChildren(@Nonnull RequireConstraint[] children, @Nonnull Constraint<?>[] additionalChildren) {
		Assert.isTrue(
			ArrayUtils.isEmpty(additionalChildren),
			"Page constraint doesn't support additional children!"
		);
		Spacing spacing = null;
		for (RequireConstraint child : children) {
			if (child instanceof Spacing spacingChild) {
				spacing = spacingChild;
				break;
			}
		}
		return new Page(
			getPageNumber(),
			getPageSize(),
			spacing
		);
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new Page(newArguments, getChildren());
	}
}