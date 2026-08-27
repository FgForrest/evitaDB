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

import com.carrotsearch.hppc.LongIntHashMap;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.invertedIndex.InvertedIndex;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serializable;
import java.util.Arrays;

/**
 * Builds the whole `trigram -> posting` table of one attribute in one shot, for the load path.
 *
 * # Why this exists rather than a loop over {@link TrigramPostings#add}
 *
 * The incremental write path has to treat every posting as published: the transactional B+ tree behind
 * {@link TrigramPostingStore} versions the MAPPING and restores REFERENCES on a savepoint rollback, so a posting
 * mutated in place would be seen changed by every older index version sharing it. {@link TrigramPostings#add}
 * therefore copies the posting before every single new membership.
 *
 * On the load path none of that applies. Nothing outside the rebuild holds a reference to the table until it is
 * handed over, so no posting needs copying at all — and the difference is not marginal. Growing one 444 437-member
 * posting of `article.title` by copy-on-write costs ~491 ns per member against ~2 ns for a bulk append; over a
 * whole attribute the two tree descents per membership compound it further. Measured on a production CMS corpus
 * (943 410 distinct values, 61.7 M memberships, 62 079 trigram keys), rebuilding that one attribute through the
 * incremental path costs **~77 s** against **~4.0 s** here — the same table, verified member for member — and the
 * result occupies 118 MB against 151 MB, because each container is materialized once at its exact size instead of
 * being grown into.
 *
 * # How
 *
 * Two walks of the shared value tree, because the exact size of every posting is worth one extra trigram
 * extraction:
 *
 * 1. **count** — how many values contain each trigram. Establishes the trigram key set and its dense slot
 *    numbering.
 * 2. **collect** — append each value id into its trigram's buffer, every buffer allocated at its exact final
 *    size, so nothing is ever grown or trimmed.
 * 3. **materialize** — sort each buffer and build its posting once, then insert it once.
 *
 * A single walk into growable buffers was measured 1.2–1.7 s faster still, and was rejected: doubling growth
 * leaves the buffers holding up to twice the membership count at peak, and a load path that is a second quicker
 * but can need twice the transient heap is the wrong trade for a structure whose whole reason to exist is that it
 * costs no storage. As built, the peak transient cost is exactly `4 bytes * memberships` — 247 MB for the
 * attribute above — released as each posting is materialized.
 *
 * # Why only the small arm sorts
 *
 * {@link InvertedIndex#forEachValueId} walks the bucket cursor in COMPARATOR order, so value ids arrive
 * decorrelated from the walk and a buffer does NOT come out ascending. The small arm has to sort, because the
 * representation below {@link TrigramPostings#SMALL_POSTING_THRESHOLD} IS a sorted `int[]` — but those buffers are
 * tiny by definition. The bitmap arm needs no total order at all, and imposing one is expensive precisely because
 * that is where nearly all the members live: sorting every buffer and building through
 * {@link io.evitadb.index.bitmap.RoaringBitmapBackedBitmap#fromArray(int...)} was measured at 6.6 s against 4.0 s
 * for the writer, and left the result 133 MB rather than 118 MB, since `fromArray` falls back to incremental
 * appends for the many postings below its density threshold and those grow their containers into slack.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@NotThreadSafe
final class TrigramPostingAccumulator {

	/**
	 * Trigram keys the accumulator starts out sized for, doubled as needed.
	 *
	 * Deliberately not derived from the tree's value count: the key set is bounded by the corpus's alphabet rather
	 * than by how many values it has, so a guess from the value count would be wildly wrong in both directions —
	 * 62 079 keys for 943 410 values on one measured attribute, 966 488 values sharing far fewer on another.
	 */
	private static final int INITIAL_KEY_CAPACITY = 1 << 14;

	/**
	 * Maps a packed trigram to its dense slot in {@link #keys} / {@link #counts} / {@link #buffers} /
	 * {@link #cursors}. Primitive-keyed, because it is probed once per membership - 61.7 M times on the measured
	 * attribute - and a boxed `Long` key there is pure garbage.
	 */
	private final LongIntHashMap slots;

	/**
	 * The packed trigram of each slot, so {@link #materialize()} can key the table without walking the map.
	 */
	private long[] keys;

	/**
	 * How many values contain the trigram of each slot - the exact final length of its buffer.
	 */
	private int[] counts;

	/**
	 * The value ids collected for each slot, each array allocated at its exact final length. Entries are released
	 * one by one as {@link #materialize()} consumes them.
	 */
	private int[][] buffers;

	/**
	 * How far each slot's buffer has been filled - and, once filled, the assertion that it was filled exactly.
	 */
	private int[] cursors;

	/**
	 * How many distinct trigrams have been seen; the exclusive upper bound of every slot number.
	 */
	private int keyCount;

	/**
	 * Builds the complete posting table of one attribute from its shared value tree.
	 *
	 * Legal only outside a transaction, on the single thread loading the catalog: the table is populated in place
	 * and the tree is walked twice, which requires that nothing writes to it in between.
	 *
	 * @param sharedValueTree the reloaded tree, already carrying its value ids
	 * @return the fully populated table
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the tree carries no value ids at all, when it
	 * hands out a value that is not a `String`, or when the two walks disagree
	 */
	@Nonnull
	static TrigramPostingStore accumulate(@Nonnull InvertedIndex sharedValueTree) {
		final TrigramPostingAccumulator accumulator = new TrigramPostingAccumulator();
		sharedValueTree.forEachValueId(accumulator::count);
		accumulator.allocate();
		sharedValueTree.forEachValueId(accumulator::collect);
		return accumulator.materialize();
	}

	private TrigramPostingAccumulator() {
		this.slots = new LongIntHashMap(INITIAL_KEY_CAPACITY);
		this.keys = new long[INITIAL_KEY_CAPACITY];
		this.counts = new int[INITIAL_KEY_CAPACITY];
	}

	/**
	 * First walk: counts the values containing each trigram, and assigns each newly seen trigram its slot.
	 *
	 * @param normalizedValue the normalized value the shared value tree holds
	 * @param valueId         the id the tree allocated for it - unused here, the count is what is being taken
	 */
	private void count(@Nonnull Serializable normalizedValue, int valueId) {
		final long[] trigrams = TrigramCodec.extractUniqueTrigramsOfValue(normalizedValue);
		for (int i = 0; i < trigrams.length; i++) {
			// one probe serves both the hit and the miss - hppc invalidates the index on insert, so it must not be
			// reused across iterations
			final int index = this.slots.indexOf(trigrams[i]);
			if (this.slots.indexExists(index)) {
				this.counts[this.slots.indexGet(index)]++;
			} else {
				if (this.keyCount == this.keys.length) {
					this.keys = Arrays.copyOf(this.keys, this.keyCount << 1);
					this.counts = Arrays.copyOf(this.counts, this.keyCount << 1);
				}
				this.slots.indexInsert(index, trigrams[i], this.keyCount);
				this.keys[this.keyCount] = trigrams[i];
				this.counts[this.keyCount] = 1;
				this.keyCount++;
			}
		}
	}

	/**
	 * Allocates every buffer at the exact length the counting walk established.
	 */
	private void allocate() {
		this.buffers = new int[this.keyCount][];
		this.cursors = new int[this.keyCount];
		for (int slot = 0; slot < this.keyCount; slot++) {
			this.buffers[slot] = new int[this.counts[slot]];
		}
		// the counts live on inside the buffer lengths from here
		this.counts = null;
	}

	/**
	 * Second walk: appends each value id to the buffer of every trigram its value contains.
	 *
	 * @param normalizedValue the normalized value the shared value tree holds
	 * @param valueId         the id the tree allocated for it
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the value yields a trigram the counting walk
	 * never saw, which means the tree changed between the two walks
	 */
	private void collect(@Nonnull Serializable normalizedValue, int valueId) {
		final long[] trigrams = TrigramCodec.extractUniqueTrigramsOfValue(normalizedValue);
		for (int i = 0; i < trigrams.length; i++) {
			final int index = this.slots.indexOf(trigrams[i]);
			if (!this.slots.indexExists(index)) {
				// thrown rather than asserted through a Supplier: this runs once per membership - 61.7 M times on
				// one measured attribute - and a capturing lambda built to describe a failure that does not happen
				// is pure garbage on the happy path
				throw new GenericEvitaInternalError(
					"Trigram `" + TrigramCodec.toDisplayString(trigrams[i]) + "` appeared only on the second walk " +
						"of the shared value tree - the tree was modified while its trigram index was being rebuilt."
				);
			}
			final int slot = this.slots.indexGet(index);
			this.buffers[slot][this.cursors[slot]++] = valueId;
		}
	}

	/**
	 * Builds each posting once from its filled buffer and files it under its trigram.
	 *
	 * @return the fully populated table
	 * @throws io.evitadb.exception.GenericEvitaInternalError when a buffer was not filled to exactly the length the
	 * counting walk established, which means the tree changed between the two walks
	 */
	@Nonnull
	private TrigramPostingStore materialize() {
		final TrigramPostingStore store = new TrigramPostingStore();
		for (int slot = 0; slot < this.keyCount; slot++) {
			final int[] members = this.buffers[slot];
			final int filled = this.cursors[slot];
			final long trigram = this.keys[slot];
			// a short fill would otherwise leave the posting padded with value id 0 - a silent, and silently
			// wrong, index rather than a failed load
			Assert.isPremiseValid(
				filled == members.length,
				() -> "Trigram `" + TrigramCodec.toDisplayString(trigram) + "` was counted on " +
					members.length + " values but collected from " + filled + " - the shared value tree was " +
					"modified while its trigram index was being rebuilt."
			);
			if (members.length <= TrigramPostings.SMALL_POSTING_THRESHOLD) {
				// the small representation IS a sorted int[]
				Arrays.sort(members);
				store.put(trigram, members);
			} else {
				// the writer needs no total order over the members - it fills a word buffer and materializes each
				// container once - so the bitmap arm skips the sort entirely, which is where most of the members are
				final PersistentRoaringBitmap posting = PersistentRoaringBitmap.bitmapOfUnordered(members);
				// the writer canonicalizes a completely-full 65 536-wide container to a RunContainer where an
				// incremental build leaves a BitmapContainer, and the two - though equal - hash differently. Undoing
				// it keeps a posting rebuilt on load indistinguishable from the same posting grown one membership at
				// a time, which is the same normalization RoaringBitmapBackedBitmap#fromArray makes for the same
				// reason. Not hypothetical here: a trigram of a short, ubiquitous substring posts against every
				// value of its attribute on the measured corpora
				posting.removeRunCompression();
				store.put(trigram, posting);
			}
			// the buffer is now either owned by the posting or copied into it - either way this reference is the
			// last one holding the rest of the arena alive
			this.buffers[slot] = null;
		}
		return store;
	}

}
