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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
 * already traverses, so it cannot change the call's cost class. A future field that does not satisfy it would silently
 * turn a bounded call into an unbounded one, which is the failure this whole surface is shaped to prevent.
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
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see BrowsedIndex
 * @see CollectionIndexCardinality
 */
public record IndexDetail(
	@Nullable String entityType,
	int indexPrimaryKey,
	long heapSizeInBytes,
	@Nonnull IndexCardinality cardinality
) {
}
