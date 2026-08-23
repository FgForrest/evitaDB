/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.statistics;

import io.evitadb.api.statistics.CollectionIndexCardinality.IndexCardinality;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Everything worth knowing about **one** index: what it occupies, and whether it is earning it.
 *
 * This is the drill-down that follows an index browse. {@link BrowsedIndex} says which indexes exist and how many
 * entities each covers; this says what one of them costs and how well it discriminates - the two questions an operator
 * has once a listing has singled a row out.
 *
 * **One record shape describes an entity collection's index and the catalog's own.** Which one it is, is stated by
 * {@link #entityType()}: a name for a collection's index, null for one the catalog holds directly. A catalog index
 * reaches the same fields through a different route - its {@link IndexCardinality#attributes()} are its global unique
 * indexes, one per (globally-unique attribute x locale in use), and its {@link IndexCardinality#entityCountIfKnown()}
 * is empty because it maintains no primary-key bitmap.
 *
 * **Why this is per-index and has no collection-wide form.** Estimating an index's heap means walking its contents,
 * which is `O(index contents)` and not amortizable - there is no cache to warm, and a measured warm second pass came
 * back slower than the cold one. On a production catalog the largest single index took 151 ms while the median took
 * ~4 µs, so naming one index is affordable and sweeping a collection of a quarter of a million of them is not. A
 * caller who genuinely wants a collection total issues these calls in parallel and sums them, which keeps the cost
 * visible to whoever chose to pay it.
 *
 * **The invariant this contract rests on: nothing may be added here that is not bounded by one index's heap walk.**
 * The cardinality below satisfies it - every reading is either a counter or a walk over buckets the heap estimate
 * already traverses, so it cannot change the call's cost class. So do the five activity readings, which are `O(1)`
 * field reads. A future field that does not satisfy it would silently turn a bounded call into an unbounded one,
 * which is the failure this whole surface is shaped to prevent.
 *
 * @param entityType       name of the entity collection holding the described index, or null for one the catalog holds
 *                         directly; echoed back with the primary key below because it is the other half of the index's
 *                         identity - see {@link BrowsedIndex#entityType()}
 * @param indexPrimaryKey  identity of the described index within its owner, echoed back so a response can be matched to
 *                         the request that asked for it; the same opaque handle {@link BrowsedIndex#indexPrimaryKey()}
 *                         carries
 * @param heapSizeInBytes  best-effort estimate of the heap this index occupies, in bytes.
 *
 *                         **An estimate, computed rather than measured**, and one that deliberately charges structure
 *                         shared with a superseded version of an index in full - that predecessor is garbage waiting
 *                         to be collected, and reporting it as free would understate what the JVM is holding.
 *                         Validated end-to-end against a production catalog, where the indexes' reported total came
 *                         to 11.55 GB against a 12.87 GB live heap.
 * @param cardinality      how many distinct values this index holds and how many records they cover, per attribute
 *                         index - the *"is this index earning its keep, or is it three distinct values over two
 *                         million records?"* reading. Also carries the index's kind, scope, discriminator and entity
 *                         count, so a detail response describes itself without the browse row beside it.
 *
 *                         This is the only place a **per-referenced-entity** index is ever described:
 *                         {@link CollectionIndexCardinality} counts those without describing them, because doing so
 *                         would make its response grow with the catalog's data.
 * @param queryCount       how many executed query plans have chosen this index as part of their winning target index
 *                         set. See {@link BrowsedIndex#queryCount()} for what "chosen" excludes and for the
 *                         since-catalog-load lifetime every one of these activity readings shares.
 * @param updateCount      how many entity mutations have acquired this index for modification. See
 *                         {@link BrowsedIndex#updateCount()}.
 * @param lastQueriedAt    when the last query that chose this index was planned, or null when no query has chosen it
 *                         since the catalog was loaded; see {@link #lastQueriedAtIfKnown()}
 * @param lastUpdatedAt    when the last entity mutation that acquired this index finished applying, or null when none
 *                         has since the catalog was loaded; see {@link #lastUpdatedAtIfKnown()}
 * @param observedSince    when observation of this index began - the moment its activity holder was constructed, and
 *                         the denominator the two counts above are read against. Null only when this description was
 *                         decoded from a remote server that predates the field - a current server always reports it;
 *                         see {@link BrowsedIndex#observedSince()} for why the window is per index rather than per
 *                         catalog load and {@link #observedSinceIfKnown()} for what a client does with an unknown one
 * @param measured        whether the readings on this row were taken at all. False on a server running with
 *                        `server.usageStatisticsTracking: false`, where the index's identity, heap size and
 *                        cardinality are all real while the four activity fields carry no information and
 *                        `observedSince` is absent. Branch on this before rendering a zero - *not measured* and
 *                        *never queried* are opposite findings
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see BrowsedIndex
 * @see CollectionIndexCardinality
 */
public record IndexDetail(
	@Nullable String entityType,
	int indexPrimaryKey,
	long heapSizeInBytes,
	@Nonnull IndexCardinality cardinality,
	long queryCount,
	long updateCount,
	@Nullable OffsetDateTime lastQueriedAt,
	@Nullable OffsetDateTime lastUpdatedAt,
	@Nullable OffsetDateTime observedSince,
	boolean measured
) {

	public IndexDetail {
		// an unmeasured detail carries no readings at all - see the `measured` component documentation for why a
		// zero beside a live window is the one reading this surface must never invent
		Assert.isPremiseValid(
			measured ||
				(queryCount == 0L && updateCount == 0L && lastQueriedAt == null && lastUpdatedAt == null &&
					observedSince == null),
			() -> "Index `" + indexPrimaryKey + "` reports activity while claiming to be unmeasured!"
		);
	}

	/**
	 * When the last query that chose this index was planned.
	 *
	 * **Empty means "not since the catalog was loaded"**, never "never" - the counters and their stamps are reset by a
	 * catalog load, so an index that has served queries for months reports empty here on a freshly started server.
	 *
	 * **It does not imply {@link #queryCount()}, in either direction** - this description is assembled field by field
	 * from readings that advance independently; see {@link BrowsedIndex#lastQueriedAtIfKnown()} for the ordering that
	 * makes either one observable without the other.
	 *
	 * @return when this index was last chosen by a query, empty when it has not been since the catalog was loaded
	 */
	@Nonnull
	public Optional<OffsetDateTime> lastQueriedAtIfKnown() {
		return Optional.ofNullable(this.lastQueriedAt);
	}

	/**
	 * When the last entity mutation that acquired this index for modification finished applying.
	 *
	 * **Empty means "not since the catalog was loaded"** - see {@link #lastQueriedAtIfKnown()}.
	 *
	 * @return when this index was last updated, empty when it has not been since the catalog was loaded
	 */
	@Nonnull
	public Optional<OffsetDateTime> lastUpdatedAtIfKnown() {
		return Optional.ofNullable(this.lastUpdatedAt);
	}

	/**
	 * When observation of this index began - the denominator its two counts are read against.
	 *
	 * **Empty means the window is unknown, never that observation has not started** - see
	 * {@link BrowsedIndex#observedSinceIfKnown()} for the one case that produces it and why no instant may stand in
	 * for a missing window.
	 *
	 * @return when observation began, empty only when a remote server was too old to report it
	 */
	@Nonnull
	public Optional<OffsetDateTime> observedSinceIfKnown() {
		return Optional.ofNullable(this.observedSince);
	}

}
