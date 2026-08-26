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

package io.evitadb.index.trigram;

import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.index.bPlusTree.TransactionalLongBPlusTree;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;

/**
 * The `trigram -> posting` table of one {@link TrigramIndex}: a transactional, `long`-keyed B+ tree holding
 * {@link TrigramPostings postings} as its values.
 *
 * # Why a B+ tree and not a flat hash table
 *
 * A flat open-addressed table over `long[]` + `Object[]` is the faster probe in isolation — around 1.3 ns against
 * the 20–60 ns of a tree descent — and that is genuinely how this structure was first built. It loses on
 * everything else that matters here:
 *
 * - **Versioning cost.** A published table is immutable, so publishing a commit meant cloning both spine arrays:
 *   `O(K)` per touched index per commit, roughly 1.5 MB at the largest trigram key count measured on a production
 *   corpus, however few postings the commit actually changed. The tree shares every node the commit did not touch,
 *   so the same commit copies a handful of nodes.
 * - **Allocation shape.** Those spine arrays, plus the doubling rehash that periodically replaces both of them, are
 *   exactly the large short-lived allocation pattern this codebase moved its index structures onto B+ trees to get
 *   away from.
 * - **Deletion.** Linear probing cannot delete a key without tombstones, so a trigram that lost its last value id
 *   used to keep its slot until the next rehash swept it. A tree deletes, so the key is simply gone.
 * - **Persistence.** A tree's leaves are pages, which is the unit the granular write path already knows how to
 *   emit and reload; a flat table would need a second, unrelated representation to be persisted at all.
 *
 * The probe-speed argument does not survive contact with a whole query: a pattern yields between 2 and about 15
 * trigram lookups, so the tree's penalty is under a microsecond against a query whose verification phase alone
 * runs for tens to hundreds of microseconds.
 *
 * # Versioning
 *
 * The tree owns MVCC. A write inside a transaction lands in the tree's own node layers, invisible to every reader
 * of the published version, and the commit merge rebuilds only the path it touched. Nothing here holds a diff of
 * its own — which is sound only because a {@link TrigramPostings posting} is never mutated in place: the tree
 * restores REFERENCES on a savepoint rollback, so a posting whose content had been changed underneath one would
 * come back changed. See the mutation contract on {@link TrigramPostings}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NotThreadSafe
final class TrigramPostingStore {

	/**
	 * Leaf block size of the `trigram -> posting` tree.
	 *
	 * The tree is `long`-keyed with a single-reference value and is read by point lookup only — never by range scan
	 * — so the block size is the same read-vs-write trade-off {@link io.evitadb.index.range.RangeIndex} measured for
	 * its own threshold tree, and for the same reason: an in-leaf insert is a cheap primitive-plus-reference
	 * arraycopy, so a larger block buys a shallower tree at no meaningful write cost. `512` is taken from that
	 * measurement rather than from one of our own — the number to re-derive if the key structure is ever profiled on
	 * its own. At the largest trigram key count measured on a production corpus (62,068) it puts the whole tree at
	 * about 130 leaves under a single internal node, and keeps a leaf's arrays (a `long[512]` and an `Object[512]`)
	 * an order of magnitude below the humongous-allocation threshold.
	 */
	private static final int VALUE_BLOCK_SIZE = 512;
	private static final int MIN_VALUE_BLOCK_SIZE = VALUE_BLOCK_SIZE / 2 - 1;
	private static final int MIN_INTERNAL_NODE_BLOCK_SIZE = (int) (Math.ceil(MIN_VALUE_BLOCK_SIZE / 2.0) - 1);

	/**
	 * The postings, keyed by packed trigram.
	 *
	 * Declared over bare {@link Object} because a posting is one of two shapes discriminated by `instanceof` — see
	 * {@link TrigramPostings}. The tree is built WITHOUT a transactional-layer wrapper: a posting is plain data and
	 * never a {@link io.evitadb.core.transaction.memory.TransactionalLayerProducer}, so there is nothing to wrap on
	 * commit, and leaving the wrapper null is also what lets a JOL walk price this structure at all (a lambda is a
	 * hidden class whose field offsets JOL cannot read).
	 */
	@Nonnull private final TransactionalLongBPlusTree<Object> postings;

	/**
	 * Creates an empty table.
	 */
	TrigramPostingStore() {
		this(
			new TransactionalLongBPlusTree<>(
				// the internal node block must be ODD and no wider than the value block, so it takes
				// MIN_VALUE_BLOCK_SIZE - exactly the shape RangeIndex builds its own threshold tree with
				VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_VALUE_BLOCK_SIZE, MIN_INTERNAL_NODE_BLOCK_SIZE,
				Object.class
			)
		);
	}

	/**
	 * Adopts an already-built tree — the constructor the commit merge produces its result with.
	 *
	 * @param postings the tree to wrap
	 */
	private TrigramPostingStore(@Nonnull TransactionalLongBPlusTree<Object> postings) {
		this.postings = postings;
	}

	/**
	 * Looks a trigram's posting up, as the calling transaction sees it.
	 *
	 * @param trigram the packed trigram
	 * @return its posting, or `null` when no value contains that trigram
	 */
	@Nullable
	Object get(long trigram) {
		return this.postings.search(trigram).orElse(null);
	}

	/**
	 * Stores a trigram's posting, or removes the trigram outright when the posting has no value ids left.
	 *
	 * Dropping the key rather than parking an empty posting under it is what keeps the key set equal to the set of
	 * trigrams some value actually contains — so {@link #liveKeyCount()} is the tree's own `O(1)` size and a lookup
	 * of a dead trigram is answered by the descent itself rather than by inspecting what it found.
	 *
	 * @param trigram the packed trigram
	 * @param posting the posting to store
	 */
	void put(long trigram, @Nonnull Object posting) {
		if (TrigramPostings.cardinality(posting) == 0) {
			this.postings.delete(trigram);
		} else {
			this.postings.insert(trigram, posting);
		}
	}

	/**
	 * @return how many trigrams post against at least one value id — the tree's own size, so `O(1)` and
	 * transaction-aware
	 */
	int liveKeyCount() {
		return this.postings.size();
	}

	/**
	 * Returns the heap this table occupies, in bytes — the tree's node graph and every posting it holds.
	 *
	 * Postings are charged **in full** even though the merge shares them with the previous index version: only one
	 * version of an index is ever walked, the predecessor is garbage-in-waiting, and reporting the shared postings
	 * as belonging to nobody would understate the index by most of its size.
	 *
	 * Walking the node graph makes this `O(trigram keys / block size)` rather than a counter read, so it belongs to
	 * the index detail call and never to a query path.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	long heapSizeInBytes() {
		// this wrapper's own object (the tree reference) plus the tree and everything under it - the wrapper is a
		// real allocation that nothing else holds, and leaving it out under-reports every trigram index by its header
		return VMLayout.current().sizeOfObject(VMLayout.current().referenceSize())
			+ this.postings.getHeapSizeInBytes(TrigramPostings::heapSizeInBytes);
	}

	/**
	 * Produces the next published version of this table, sharing every node the transaction did not touch.
	 *
	 * @param transactionalLayer the maintainer holding this transaction's node layers
	 * @return the merged table
	 */
	@Nonnull
	TrigramPostingStore createCopyWithMergedTransactionalMemory(
		@Nonnull TransactionalLayerMaintainer transactionalLayer
	) {
		return new TrigramPostingStore(transactionalLayer.getStateCopyWithCommittedChanges(this.postings));
	}

	/**
	 * Drops the transactional layers this table registered — the tree's own, and those of every node it created.
	 *
	 * @param transactionalLayer the maintainer whose entries should be dropped
	 */
	void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
		this.postings.removeLayer(transactionalLayer);
	}

	@Override
	public String toString() {
		return "TrigramPostingStore(trigrams=" + liveKeyCount() + ')';
	}

}
