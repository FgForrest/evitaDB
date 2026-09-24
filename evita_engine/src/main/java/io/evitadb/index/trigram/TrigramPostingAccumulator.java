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
import io.evitadb.index.invertedIndex.ValueIdAllocator;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;
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
 * On the load path none of that applies, and the reason is unpublishedness rather than single-threadedness. The
 * accumulator and the store it fills are local to one {@link #accumulate} invocation, so no thread can observe a
 * posting between its allocation and its handover and no posting needs copying at all — and the difference is not
 * marginal. Growing one 444 437-member posting of `article.title` by copy-on-write costs ~491 ns per member
 * against ~2 ns for a bulk append; over a whole attribute the two tree descents per membership compound it
 * further. Measured end to end on a production CMS corpus (943 410 distinct values, 61.7 M memberships, 62 079
 * trigram keys), with both paths fed by walking a real {@link InvertedIndex} exactly as the load path does,
 * rebuilding that one attribute through the incremental path costs **~135 s** against **~8.8 s** here — the
 * same table, verified member for member across all 62 079 keys — and the result occupies 118 MB against
 * 151 MB, because each container is materialized once at its exact size instead of being grown into.
 *
 * # How
 *
 * Two walks of the shared value tree, because the exact size of every posting is worth one extra trigram
 * extraction:
 *
 * 1. **count** — how many values contain each trigram. Establishes the trigram key set and its dense slot
 *    numbering.
 * 2. **collect** — place each value id into its trigram's buffer, every buffer allocated at its exact final
 *    size, so nothing is ever grown or trimmed.
 * 3. **materialize** — sort each buffer and build its posting once, then insert it once.
 *
 * A single walk into growable buffers was measured 1.2–1.7 s faster still, and was rejected: doubling growth
 * leaves the buffers holding up to twice the membership count at peak, and a load path that is a second quicker
 * but can need twice the transient heap is the wrong trade for a structure whose whole reason to exist is that it
 * costs no storage. As built, the dominant peak transient cost is `4 bytes * memberships` — 247 MB for the
 * attribute above — released as each posting is materialized; on top of it sits one scratch copy of the largest
 * posting being materialized, allocated by the writer's radix sort (~1.7 MB on that attribute, under 1% — see the
 * note in {@link #materialize()}), and underneath it the fixed key-table floor of {@link #INITIAL_KEY_CAPACITY},
 * which is all a small attribute pays and is why that floor is kept low.
 *
 * **That peak is per rebuild in flight, not per server.** A rebuild is not confined to one thread — see the
 * concurrency note below — so several substring-indexed attributes loading at once each carry their own arena, and
 * the figures above multiply. Anyone sizing a startup heap from this must count the concurrent loads, not one.
 *
 * # Concurrency
 *
 * "The catalog loads on one thread" is NOT true and must not be relied on. The catalog's LIVE global index is read
 * inside a composite `ProgressingFuture`'s nested-future factory, which does run inline — but every other used
 * index arrives as a LEAF future, and a leaf wraps its lambda in `CompletableFuture#runAsync`. An ARCHIVED global
 * index therefore reaches `readEntityIndex` → {@link TrigramIndex#rebuildAll} → {@link #accumulate} on a pool
 * thread, concurrently with the same work for other collections. What keeps that safe is the unpublishedness above
 * plus the requirement that nothing writes to the tree being walked — never a thread count.
 *
 * # Why only the small arm sorts
 *
 * {@link InvertedIndex#forEachValueId} walks the bucket cursor in COMPARATOR order, so value ids arrive
 * decorrelated from the walk and a buffer does NOT come out ascending. The small arm has to sort, because the
 * representation below {@link TrigramPostings#SMALL_POSTING_THRESHOLD} IS a sorted `int[]` — but those buffers are
 * tiny by definition. The bitmap arm needs no total order at all, and imposing one is expensive precisely because
 * that is where nearly all the members live: sorting every buffer and building through
 * {@link io.evitadb.index.bitmap.RoaringBitmapBackedBitmap#fromArray(int...)} was measured at 6.6 s against 4.0 s
 * for the writer — an arm-to-arm comparison taken on a harness that fed the values from arrays instead of walking
 * them, so those two are comparable to each other but not to the end-to-end figures above — and left the result
 * 133 MB rather than 118 MB, since `fromArray` falls back to incremental appends for the many postings below its
 * density threshold and those grow their containers into slack.
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
	 *
	 * That argument says only where the number may NOT come from; what fixes it this low is that it is a floor
	 * every accumulator pays in full, however few trigrams its attribute holds, and one accumulator is built per
	 * `(attribute, locale)` rebuilt on load. The two key arrays plus the map's buckets cost ~37 kB at this size
	 * against ~590 kB at `1 << 14`, and growing past it is an amortized-O(1) doubling of the key arrays alone.
	 */
	private static final int INITIAL_KEY_CAPACITY = 1 << 10;

	/**
	 * The buffer table before {@link #allocate()} has sized it. Shared and empty rather than `null`, because before
	 * the counting walk finishes there genuinely are no buffers - this states that, where a null would instead state
	 * that {@link #buffers} may be absent at any point, which it may not.
	 */
	private static final int[][] NO_BUFFERS = new int[0][];

	/**
	 * Maps a packed trigram to its dense slot in {@link #keys} / {@link #counts} / {@link #buffers}. Primitive-keyed,
	 * because it is probed once per membership - 61.7 M times on the measured attribute - and a boxed `Long` key
	 * there is pure garbage.
	 */
	@Nonnull private final LongIntHashMap slots;

	/**
	 * The packed trigram of each slot, so {@link #materialize()} can key the table without walking the map.
	 */
	@Nonnull private long[] keys;

	/**
	 * Two readings of one array, and the two never overlap: until {@link #allocate()} this is how many values contain
	 * the trigram of each slot - the exact final length of its buffer - and from there on it is how many of those
	 * members the slot has yet to receive, decremented by {@link #collect} as it fills the buffer from the end. A
	 * remainder other than zero when {@link #materialize()} reaches the slot is a buffer the second walk left short.
	 *
	 * Carrying the count and the fill cursor in one array is what keeps the field non-null for the accumulator's whole
	 * life. Nothing reads a count once `allocate` has handed it to a buffer length, and nothing reads a cursor before
	 * that, so a second array would buy only a phase in which one of the two is meaningless - which is precisely the
	 * phase that would otherwise have to be spelled `null`. It also saves the allocation and keeps the hot store in
	 * {@link #collect} touching one array rather than two.
	 */
	@Nonnull private int[] counts;

	/**
	 * The value ids collected for each slot, each array allocated at its exact final length by {@link #allocate()};
	 * entries are released one by one as {@link #materialize()} consumes them, since this reference is the last one
	 * holding that slice of the arena alive.
	 */
	@Nonnull private int[][] buffers = NO_BUFFERS;

	/**
	 * How many distinct trigrams have been seen; the exclusive upper bound of every slot number.
	 */
	private int keyCount;

	/**
	 * Builds the complete posting table of one attribute from its shared value tree.
	 *
	 * Legal only outside a transaction, and only while nothing writes to `sharedValueTree`: the table is populated
	 * in place and the tree is walked twice, so the two walks have to see the same tree. What licenses the in-place
	 * build is NOT that one thread is loading the catalog - several may be, see the note on concurrency in the class
	 * documentation - but that the table is UNPUBLISHED: the accumulator and the store it fills are local to this
	 * invocation and unreachable from anywhere else until the result is handed back.
	 *
	 * The checks below are a guard against the caller breaking that stillness, not a proof it was kept: all they
	 * compare is how many values carried each trigram on the first walk against how many offered it on the second,
	 * so a modification that leaves every count intact - one value replaced by another carrying the same trigrams -
	 * is undetectable here and yields a table holding the wrong value id. Keeping the tree still is the caller's
	 * obligation; these only catch the shapes that change a count.
	 *
	 * @param sharedValueTree the reloaded tree, already carrying its value ids
	 * @return the fully populated table
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the tree carries no value ids at all, when it
	 * hands out a value that is not a `String`, when the second walk yields a trigram the first never saw, or when
	 * it leaves a buffer SHORT of the length the first counted
	 * @throws ArrayIndexOutOfBoundsException when the second walk offers a slot MORE members than the first counted
	 * for it - see {@link #collect} for why that one shape is left to the JVM's own bounds check rather than
	 * diagnosed. Both shapes mean the same thing: the tree was modified while its index was being rebuilt
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
		for (final long trigram : trigrams) {
			// one probe serves both the hit and the miss - hppc invalidates the index on insert, so it must not be
			// reused across iterations
			final int index = this.slots.indexOf(trigram);
			if (this.slots.indexExists(index)) {
				this.counts[this.slots.indexGet(index)]++;
			} else {
				if (this.keyCount == this.keys.length) {
					this.keys = Arrays.copyOf(this.keys, this.keyCount << 1);
					this.counts = Arrays.copyOf(this.counts, this.keyCount << 1);
				}
				this.slots.indexInsert(index, trigram, this.keyCount);
				this.keys[this.keyCount] = trigram;
				this.counts[this.keyCount] = 1;
				this.keyCount++;
			}
		}
	}

	/**
	 * Allocates every buffer at the exact length the counting walk established, after which {@link #counts} reads as
	 * the countdown of members each slot has yet to receive rather than as a count.
	 */
	private void allocate() {
		this.buffers = new int[this.keyCount][];
		for (int slot = 0; slot < this.keyCount; slot++) {
			this.buffers[slot] = new int[this.counts[slot]];
		}
	}

	/**
	 * Second walk: appends each value id to the buffer of every trigram its value contains.
	 *
	 * @param normalizedValue the normalized value the shared value tree holds
	 * @param valueId         the id the tree allocated for it
	 * @throws io.evitadb.exception.GenericEvitaInternalError when the value yields a trigram the counting walk
	 * never saw, which means the tree changed between the two walks
	 * @throws ArrayIndexOutOfBoundsException when a slot is offered more members than the counting walk counted for
	 * it - its countdown reaches zero and the next store indexes -1. The over-fill carries no diagnosis of its own,
	 * because the store below runs once per membership and the JVM already bounds-checks it; the mirror-image SHORT
	 * fill IS diagnosed, in {@link #materialize()}
	 */
	private void collect(@Nonnull Serializable normalizedValue, int valueId) {
		// the same premise `InvertedIndex#notifyValueCreated` states for the incremental path, which this bulk one
		// must agree with: `0` is the unassigned sentinel, so posting it would file a phantom candidate under every
		// trigram of this value that nothing can ever remove - the removal path refuses the very same id first. It
		// costs one comparison per value, once per rebuild, and it is checked HERE rather than in `count` so the
		// message can name the walk that would have stored it
		Assert.isPremiseValid(
			valueId != ValueIdAllocator.UNASSIGNED_VALUE_ID,
			() -> "Value `" + normalizedValue + "` of the shared value tree carries no value id - a trigram index " +
				"can only be built over a tree that stamps every bucket it creates."
		);
		final long[] trigrams = TrigramCodec.extractUniqueTrigramsOfValue(normalizedValue);
		for (final long trigram : trigrams) {
			final int index = this.slots.indexOf(trigram);
			if (!this.slots.indexExists(index)) {
				// thrown rather than asserted through a Supplier: this runs once per membership - 61.7 M times on
				// one measured attribute - and a capturing lambda built to describe a failure that does not happen
				// is pure garbage on the happy path
				throw new GenericEvitaInternalError(
					"Trigram `" + TrigramCodec.toDisplayString(trigram) + "` appeared only on the second walk " +
						"of the shared value tree - the tree was modified while its trigram index was being rebuilt."
				);
			}
			final int slot = this.slots.indexGet(index);
			// the count doubles as the cursor, so the buffer fills from the end. The direction is immaterial to the
			// result - the small arm sorts, and the bitmap arm's writer sets bits in a word buffer - and it is what lets
			// one array serve both walks
			this.buffers[slot][--this.counts[slot]] = valueId;
		}
	}

	/**
	 * Builds each posting once from its filled buffer and files it under its trigram.
	 *
	 * @return the fully populated table
	 * @throws io.evitadb.exception.GenericEvitaInternalError when a buffer was filled SHORT of the length the
	 * counting walk established, which means the tree changed between the two walks. An over-fill never reaches
	 * here - it fails the store in {@link #collect} first
	 */
	@Nonnull
	private TrigramPostingStore materialize() {
		final TrigramPostingStore store = new TrigramPostingStore();
		for (int slot = 0; slot < this.keyCount; slot++) {
			final int[] members = this.buffers[slot];
			final int outstanding = this.counts[slot];
			final long trigram = this.keys[slot];
			// a short fill would otherwise leave the posting padded with value id 0 - a silent, and silently
			// wrong, index rather than a failed load. Note what this does and does not catch: it compares the
			// NUMBER of values carrying the trigram across the two walks, so a change that leaves every count
			// intact - one value swapped for another that carries the same trigrams - passes both this check and
			// the one in collect while storing the wrong value id. Nothing here can detect that; only the caller's
			// obligation to keep the tree still can
			Assert.isPremiseValid(
				outstanding == 0,
				() -> "Trigram `" + TrigramCodec.toDisplayString(trigram) + "` was counted on " +
					members.length + " values but collected from " + (members.length - outstanding) + " - the shared " +
					"value tree was modified while its trigram index was being rebuilt."
			);
			if (members.length <= TrigramPostings.SMALL_POSTING_THRESHOLD) {
				// the small representation IS a sorted int[]
				Arrays.sort(members);
				store.put(trigram, members);
			} else {
				// the writer needs no total order over the members - it fills a word buffer and materializes each
				// container once - so the bitmap arm skips the sort entirely, which is where most of the members are
				final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapWriter.writer()
					.constantMemory()
					.doPartialRadixSort()
					.runCompress(false)
					.get();
				// addMany radix-sorts `members` IN PLACE (Util#partialRadixSort) and then reads it once into the word
				// buffer - the array itself is never adopted by the posting. That sort allocates an int[] as long as
				// `members` whenever the posting spans more than one 65 536-wide chunk, and skips the allocation
				// entirely when it does not, so the arena's peak carries one such copy of the largest posting - about
				// 1.7 MB on the measured attribute, under 1% of the arena rather than a doubling of it
				writer.addMany(members);
				writer.flush();
				final PersistentRoaringBitmap posting = writer.getUnderlying();
				// the writer canonicalizes a completely-full 65 536-wide container to a RunContainer where an
				// incremental build leaves a BitmapContainer, and the two - though equal - hash differently. Undoing
				// it keeps a posting rebuilt on load indistinguishable from the same posting grown one membership at
				// a time, which is the same normalization RoaringBitmapBackedBitmap#fromArray makes for the same
				// reason. Not hypothetical here: a trigram of a short, ubiquitous substring posts against every
				// value of its attribute on the measured corpora. `runCompress` is off so that undoing stays limited
				// to that one container: with it on, the appender run-optimizes EVERY container it materializes -
				// one extra scan and allocation each - and this call converts them all straight back
				posting.removeRunCompression();
				store.put(trigram, posting);
			}
			// the small arm hands the buffer to the posting to own; the bitmap arm read it and dropped it. Either
			// way nothing further needs it, and this reference is the last one holding that slice of the arena alive
			this.buffers[slot] = null;
		}
		return store;
	}

}
