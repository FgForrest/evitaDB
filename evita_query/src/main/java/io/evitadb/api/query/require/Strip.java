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

import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.dataType.StripList;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Optional;

/**
 * The `strip` requirement controls the number and slice of entities returned in the query response using
 * offset/limit–based pagination. It is one of two {@link ChunkingRequireConstraint} implementations; the other is
 * {@link Page}, which uses a page-number model that maps better to classic page navigation UIs.
 *
 * Use `strip` when the consumer needs direct positional access — for example when implementing infinite-scroll
 * or when the front-end tracks scroll position rather than discrete pages.
 *
 * ## Defaults and out-of-range behaviour
 *
 * Both arguments accept `null`, in which case defaults are applied: offset defaults to `0` and limit defaults
 * to `20`. Offset must be greater than or equal to zero; limit must be greater than or equal to zero (limit `0`
 * produces an empty result while still returning total count metadata).
 *
 * When the requested strip starts beyond the last available record, the engine returns the **first strip** (offset
 * reset to `0`, limit retained) instead of an empty result. This mirrors the behaviour of {@link Page} and avoids
 * the need for a secondary corrective request when the result set shrinks between two calls.
 *
 * ## Response structure
 *
 * The result is wrapped in a {@link StripList} data-chunk object, which exposes:
 *
 * - `offset` — the starting position of the returned slice (zero-based)
 * - `limit` — the maximum number of records requested
 * - `totalRecordCount` — total number of matching entities
 * - `isFirst()`, `isLast()`, `hasNext()`, `hasPrevious()` — navigation flags
 * - `data` — the list of entities in this strip
 *
 * Unlike {@link PaginatedList}, `StripList` does not expose a `lastPageNumber` because there is no inherent notion
 * of pages — the caller is free to choose any offset within `[0, totalRecordCount - 1]`.
 *
 * ## Usage example
 *
 * ```evitaql
 * require(
 *    strip(52, 24)
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/paging#strip)
 *
 * @see Page
 * @see StripList
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "strip",
	shortDescription = "The constraint specifies which strip (subset) of found entities will be returned using offset/limit-based pagination.",
	userDocsLink = "/documentation/query/requirements/paging#strip",
	supportedIn = { ConstraintDomain.GENERIC, ConstraintDomain.REFERENCE }
)
public class Strip extends AbstractRequireConstraintLeaf implements GenericConstraint<RequireConstraint>, ChunkingRequireConstraint {
	@Serial private static final long serialVersionUID = 1300354074537839696L;

	private Strip(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	@Creator
	public Strip(Integer offset, Integer limit) {
		super(
			Optional.ofNullable(offset).orElse(0),
			Optional.ofNullable(limit).orElse(20)
		);
		Assert.isTrue(
			offset == null || offset >= 0,
			"Record offset must be greater than or equal to zero."
		);

		Assert.isTrue(
			limit == null || limit >= 0,
			"Record limit must be greater than or equal to zero."
		);
	}

	/**
	 * Returns number of the items that should be omitted in the result.
	 */
	public int getOffset() {
		return (Integer) getArguments()[0];
	}

	/**
	 * Returns number of entities on that should be returned.
	 */
	public int getLimit() {
		return (Integer) getArguments()[1];
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length > 1;
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new Strip(newArguments);
	}

}