/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.index.bPlusTree;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils.InsertionPosition;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.ToLongFunction;

import static io.evitadb.utils.ArrayUtils.computeInsertPositionOfObjInOrderedArray;

/**
 * Pluggable key (bucket value) column of a {@link TransactionalBucketBPlusTree} leaf. It abstracts the leaf's key
 * storage so a leaf can hold its keys in the cheapest representation for the attribute type — a boxed {@code Object[]}
 * ({@link BoxedObjectColumn}, the universal fallback), for numeric / temporal attributes a single primitive column
 * (an `int[]` or a `long[]`), for {@link io.evitadb.dataType.Range} attributes two parallel `long[]`
 * bound columns ({@link RangeValueColumn}, reachable only through {@link ValueColumnFactory#forFilterKey}), or for
 * {@link String} attributes a front-coded (prefix-compressed) variable-length {@code byte[]}-blob column
 * ({@link FrontCodedStringColumn}, selected for every {@link String} key regardless of comparator). The
 * {@code int[]} single-record column and the lazy
 * {@link io.evitadb.index.bitmap.TransactionalBitmap}{@code []} overflow column stay owned by the leaf and are not part
 * of this abstraction — only the key representation varies.
 *
 * Design contract (so the abstraction adds **no** allocation / boxing penalty over the boxed leaf it replaces):
 *
 * - The column **owns** ordered search ({@link #findKeyPosition}) and all bulk / single-slot array moves
 *   ({@link #copyRangeTo}, {@link #insertKeyAt}, {@link #removeKeyAt}, {@link #fillEmpty}) so the tree never pulls a
 *   *boxed key per element* on any hot path. The only boxing methods are {@link #keyAt} and {@link #asBoxedArray}, which
 *   are called exactly where the boxed leaf already materialized a key — once per visited bucket (`cursor.value()`),
 *   once per leaf for an internal-node separator, and on the cold consistency / `toString` paths.
 * - MVCC copy-on-write mirrors the boxed leaf line-for-line: {@link #duplicate} is a **deep** copy (new backing array,
 *   new column identity) used to decouple a transactional layer on first write; the leaf shares the *same* column
 *   reference into its transactional layer (`createLayer`) so the `layer.keys == this.keys` reference check fires
 *   exactly once, just as the array-identity check did before.
 * - {@link #copyRangeTo} assumes {@code dst} is the **same concrete kind** as this column — true within one tree (one
 *   attribute index = one value type); it is asserted defensively.
 *
 * ## Logical capacity, physical backing (the whole family, not just the front-coded column)
 *
 * {@link #capacity()} is the **logical** block size the column was created with and never moves. The **physical**
 * backing array is sized to the live content instead: an empty column allocates nothing, the first write allocates
 * {@code ColumnSizing.MIN_PHYSICAL_LENGTH} slots, and growth doubles up to the logical capacity. A leaf holding four
 * values therefore pays for four slots rather than for the whole block. {@code ColumnSizing} states the policy and
 * its numbers.
 *
 * Three consequences the implementations all share, and which every caller may rely on:
 *
 * - **A column is a logical run of {@code capacity()} slots of which the first {@link #size()} are materialized.**
 *   Everything at or beyond {@code size()} reads as empty — {@code null} for a reference column, {@code 0} for a
 *   primitive one — exactly as the fixed, zero-filled arrays this family used to allocate did. Slots in
 *   {@code [size(), physical length)} are kept cleared, so no stale key survives past the live tail.
 * - **{@link #clearAt} and {@link #fillEmpty} are size-authoritative**: they truncate the live run rather than
 *   poking a single slot, and are strict no-ops for anything already past it.
 * - **{@link #copyRangeTo} grows its destination**, and {@link #insertKeyAt} grows before it shifts. Neither
 *   requires the destination to have been pre-sized.
 *
 * **{@code size() == peek + 1} is the leaf's intended invariant, not a guarantee a reader may lean on.** Every
 * reader must bound itself by the leaf's {@code peek}, never by {@link #size()}. Two windows break it, both of them
 * transient and both inside a single leaf mutation:
 *
 * - {@code insertNewSingleBucket} and {@code insertNewBucket} grow the columns before incrementing {@code peek}, so
 *   between the first column write and the increment the columns report {@code peek + 2}.
 * - {@code deleteBucketAt} shrinks them before decrementing it, so its {@code clearAt(peek)} fires while the live
 *   run is already {@code peek} — which is precisely why {@link #clearAt} has to answer by {@link #size()} rather
 *   than by the leaf's {@code peek}.
 *
 * **Nothing may observe a leaf from inside one of those two windows**, and no third window survives them: a
 * **committed** column whose run disagrees with {@code peek} is an immediate error, enforced at every structural
 * exit by {@code BPlusLeafTreeNode.assertColumnsAlignedWithPeek}. A reader with no happens-before edge to the writer
 * needs one thing more than the {@code peek} bound — see {@link #observableLiveRun()}.
 *
 * @param <M> the (boxed) key type as seen by the tree's generic API
 */
sealed interface ValueColumn<M extends Comparable<M>>
	permits BoxedObjectColumn, LongValueColumn, IntValueColumn, RangeValueColumn, FrontCodedStringColumn {

	/**
	 * Returns the **logical** capacity — the leaf block size this column was created with, which no mutation ever
	 * changes. The physical backing array is usually shorter (see the interface javadoc); slots in
	 * {@code [size(), capacity())} are unused and read as empty.
	 *
	 * This is what the leaf's {@code isFull()} / {@code isNearlyFull()} / {@code capacity()} read to decide whether
	 * to split, so it must never be answered with the backing array's length: a column shortened to its content
	 * would otherwise make a five-value tree split, gain an internal root and start persisting leaf pages.
	 *
	 * @return the logical capacity (the leaf block size)
	 */
	int capacity();

	/**
	 * Returns the number of live keys in this column — the length of the materialized run, kept equal to the owning
	 * leaf's {@code peek + 1}. Everything from here to {@link #capacity()} reads as empty.
	 *
	 * @return the live key count
	 */
	int size();

	/**
	 * Returns the live run a reader holding **no happens-before edge** to the writer may bound itself by —
	 * {@code min(size(), physical length)} — rather than {@link #size()}.
	 *
	 * ## Why {@link #size()} alone is not safe there
	 *
	 * A column grows by two plain field stores: the longer backing array is published first, the live count is
	 * raised second. A reader that shares no lock, no volatile and no transaction with the writer may observe those
	 * two in either order, so it can read the **new** count against the **old**, shorter array — and an index taken
	 * from that count then runs off the end. That reader exists: the management and statistics API walks leaves with
	 * no session and no catalog-state guard, concurrently with a warm-up load growing the very columns it is
	 * walking, and {@code recordCount()} is its entry point.
	 *
	 * ## Why one reading of this bound stays valid for the whole walk
	 *
	 * In-place mutation only ever replaces a backing array with a **longer** one. Trimming and duplicating build a
	 * new column object rather than shortening this one's array, and a bulk load only ever fills a freshly created
	 * column. A bound taken once from {@code min(size, length)} is therefore never above the length of any array a
	 * later read of the same column can see, so the caller may take it once per leaf and index freely under it —
	 * which is what keeps this off the per-key path.
	 *
	 * Everything the bound leaves out is simply not counted yet, and the walk under-reports by that much. That is
	 * the same staleness the fixed-length columns produced, and it is what these readers are documented to accept.
	 *
	 * **The reordering is unreachable on x86 and reachable on AArch64**, so no stress test run on an x86 box can
	 * demonstrate that this method is needed. See the CALIBRATION section on
	 * {@code TransactionalBucketBPlusTree.observableLeafPeek}, the sole caller, for the measurement and for what
	 * pins this deterministically instead.
	 *
	 * @return the live run that is safe to index without synchronization
	 */
	int observableLiveRun();

	/**
	 * Creates a new **empty** column of the same concrete kind and the given **logical** capacity (split / layer
	 * target). The returned column allocates no backing storage until its first write.
	 *
	 * @param capacity the logical capacity of the new column (the leaf block size)
	 * @return a fresh empty column of the same kind
	 */
	@Nonnull
	ValueColumn<M> allocate(int capacity);

	/**
	 * Returns a column holding the same keys with its physical backing shrunk to the live content, or {@code this}
	 * when the slack does not justify the copy.
	 *
	 * This is the counterpart to the growth on the write path, and it belongs at exactly one place: the commit merge,
	 * on the branches that build a new committed leaf anyway. Calling it on the merge's {@code return this} fast path
	 * would rebuild every leaf of every commit and dirty every persistence page. The identity return is what lets the
	 * caller stay unconditional — the common case allocates nothing and hands the same reference back.
	 *
	 * A trim fires only when the live count has fallen to a quarter of the physical length, and lands on a power of
	 * two; see {@code ColumnSizing} for why the gap has to be that wide.
	 *
	 * @return a shrunk copy, or {@code this} when no shrink is warranted
	 */
	@Nonnull
	ValueColumn<M> trimmed();

	/**
	 * Creates an independent, non-aliasing copy of this column (new identity) used to decouple a transactional
	 * layer's key column from the shared base on first write. Most implementations deep-copy their backing array(s);
	 * one that mutates exclusively by whole-reference replacement (never edits bytes/elements of a retained array in
	 * place) may instead structurally share that backing state — see {@link FrontCodedStringColumn#duplicate()} for
	 * the concrete example and the invariant that safety depends on.
	 *
	 * **The copy keeps the source's physical length verbatim and never trims it** (use {@link #trimmed()} for that).
	 * This method is both the MVCC decouple primitive and the savepoint memento primitive: a decoupled layer is
	 * about to be written, so shrinking it would be undone one statement later, and a memento has to be a faithful
	 * pre-image — a rollback must not change the leaf's physical shape as a side effect.
	 *
	 * @return an independent copy of this column, safe to mutate without affecting the source
	 */
	@Nonnull
	ValueColumn<M> duplicate();

	/**
	 * The MVCC decouple's variant of {@link #duplicate()}, for the case where the layer's very first act on the copy
	 * will be an **insert**. Identical to {@link #duplicate()} in every respect — same live run, same content, same
	 * depth of copy — except that a column whose live run exactly fills its backing array is copied straight to the
	 * length its next insert would grow it to ({@code ColumnSizing.headroomLength}), so that insert lands in place.
	 *
	 * **Why the family carries two duplication methods.** A committed leaf whose columns are exactly full is the
	 * common case rather than a corner one: both halves of every split are born that way, and after a restart so is
	 * every bulk-loaded page. Copying such a column at its short length and growing it one statement later costs two
	 * allocations where the block-sized columns this family replaced paid one; the cursor-allocation benchmark
	 * measured that as +489 B per insert (+44 %) on its `insertBucketInTransaction` arm at block size 256.
	 *
	 * **The savepoint memento must never use this method.** A memento has to be a faithful pre-image, and a rollback
	 * that changed the leaf's physical shape would be a side effect of restoring it. `snapshot()` and `restore()`
	 * therefore stay on {@link #duplicate()}, as does every decouple whose pending mutation removes or rewrites
	 * rather than inserts.
	 *
	 * @return an independent copy of this column, sized to absorb one more entry without reallocating
	 */
	@Nonnull
	ValueColumn<M> duplicateForInsert();

	/**
	 * Returns the (boxed) key at the given index. Boxing boundary — call only where the boxed leaf already materialized
	 * a key (per-visited-bucket / per-leaf-separator / cold paths).
	 *
	 * Unlike the record column's readers this one is strict: the result is declared {@code @Nonnull}, so there is no
	 * value it could answer for an empty slot. {@code index} must be below {@link #size()}, which every caller
	 * satisfies by bounding its walk with the leaf's {@code peek}.
	 *
	 * @param index the live slot to read ({@code < size()})
	 * @return the boxed key at {@code index}
	 */
	@Nonnull
	M keyAt(int index);

	/**
	 * Whether {@link #containsUtf8At} can answer for this column without materialising the key as an `M`.
	 *
	 * Consulted once per query rather than per candidate, because it is a property of the column's storage rather
	 * than of the slot. Only {@link FrontCodedStringColumn} answers `true`: it is the only implementation that
	 * already holds its keys as WTF-8 bytes (see {@code Wtf8}), so it is the only one for which byte matching
	 * avoids work rather than inventing it.
	 *
	 * @return whether byte-level matching is available on this column
	 */
	default boolean supportsUtf8Matching() {
		return false;
	}

	/**
	 * Answers whether the key at `index` contains `patternUtf8` as a contiguous run of bytes, without materialising
	 * the key.
	 *
	 * ## Why a byte comparison answers a question about characters
	 *
	 * UTF-8 is self-synchronizing: a continuation byte can never begin a sequence, so a byte-level occurrence of one
	 * well-formed encoding inside another can only start at a character boundary. Byte containment and code-point
	 * containment are therefore the same predicate, and the answer holds for supplementary characters and for
	 * combining marks alike - the column's stored keys and the pattern have both passed through the same NFD
	 * normalizer before they reach here.
	 *
	 * A front-coded column stores its keys as WTF-8 rather than UTF-8 (see {@code Wtf8}), which changes nothing here:
	 * the two encodings differ only on unpaired surrogates, WTF-8 keeps the `10xxxxxx` continuation-byte form, and so
	 * self-synchronization - the whole basis of the argument above - holds for it identically.
	 *
	 * **The caller must rule out an unpaired surrogate in the pattern.** The pattern is encoded with
	 * `String#getBytes`, which substitutes `0x3F` (`'?'`) for one, so a pattern carrying one would match values that
	 * literally contain a question mark - a divergence from `String#contains`, which compares UTF-16 code units and
	 * would refuse them. A pattern that cannot be encoded faithfully must take the predicate path instead. Ruling it
	 * out also makes the comparison homogeneous: a surrogate-free pattern's UTF-8 bytes ARE its WTF-8 bytes, so
	 * pattern and stored key are being compared in one and the same encoding.
	 *
	 * A stored VALUE carrying an unpaired surrogate needs no guard, and for a stronger reason than it used to: the
	 * column now stores it faithfully as its own three-byte sequence, which the pattern's `'?'` cannot match - the
	 * same answer `String#contains` gives.
	 *
	 * @param index       the live slot whose key is tested
	 * @param patternUtf8 the pattern's UTF-8 bytes, already normalized exactly as the stored keys are
	 * @return whether the key at `index` contains the pattern
	 */
	default boolean containsUtf8At(int index, @Nonnull byte[] patternUtf8) {
		throw new GenericEvitaInternalError(
			"This column stores no UTF-8 keys, so it cannot match bytes - `supportsUtf8Matching` says so and must " +
				"be consulted before this method is called."
		);
	}

	/**
	 * Inserts {@code value} at {@code index}, shifting the live tail one slot to the right and raising {@link #size()}
	 * by one (the leaf grows {@code peek} afterwards).
	 *
	 * **Grows the physical backing first when the live run already fills it**, so the caller never has to pre-size the
	 * column. Only the live tail moves — {@code size() - index} slots — never the whole block.
	 *
	 * {@code index} must not **exceed** {@link #size()} — the leaf bounds it by {@code peek + 1}. Unlike
	 * {@link RecordColumn#insertAt}, which absorbs an index past its live run by degenerating to a plain write
	 * (shifting a run of zeroes right changes nothing), a key column cannot: there is no empty key to shift, so a
	 * violation corrupts the column rather than being absorbed.
	 *
	 * @param index the insertion position; must not exceed {@link #size()}
	 * @param value the key to insert
	 */
	void insertKeyAt(int index, @Nonnull M value);

	/**
	 * Bulk-populates this freshly-{@link #allocate}d (empty) column with {@code count} keys, already in ascending
	 * order, in a single pass — the load-time counterpart to {@code count} sequential {@link #insertKeyAt} calls
	 * (used when the full, already-sorted key set is known up front, e.g. loading a persisted leaf page). For most
	 * implementations this is no cheaper per element than the incremental path — but {@link #insertKeyAt} always
	 * shifts the tail out to {@link #capacity()} (not just the live count), so {@code count} sequential calls cost
	 * Θ(count²/2) element copies where this method costs O(count); the difference is dramatic for
	 * {@link FrontCodedStringColumn}, whose {@link #insertKeyAt} additionally decodes and re-encodes the *entire*
	 * column on every call (O(current size) per call, O(count²) total for `count` calls) — this method builds the
	 * same content with a single encode pass, O(count) total.
	 *
	 * **Sizes the physical backing exactly to {@code count}** and sets {@link #size()} to it, so every persisted page
	 * and every inline load lands at its exact footprint with no overshoot at all.
	 *
	 * @param keys  the ascending-ordered keys to load; only {@code keys[0, count)} are read
	 * @param count the number of live keys ({@code <= capacity()})
	 */
	void bulkLoad(@Nonnull Object[] keys, int count);

	/**
	 * Removes the key at {@code index}, shifting the live tail one slot to the left and lowering {@link #size()} by one
	 * (the leaf clears the freed last slot via {@link #clearAt} and shrinks {@code peek} afterwards). The vacated slot
	 * is cleared, so nothing stale survives past the live run.
	 *
	 * Removing a slot at or beyond {@link #size()} is a no-op rather than an error: that region is already empty, and
	 * dropping one empty slot out of it leaves it empty.
	 *
	 * @param index the slot to remove
	 */
	void removeKeyAt(int index);

	/**
	 * Truncates the live run to {@code index}, clearing everything from there on — used to release the freed last slot
	 * after a delete, and reached with a still-live slot when a downward {@code setPeek} shortens a leaf.
	 *
	 * **Size-authoritative, so it is a strict no-op for {@code index >= size()}.** That is what makes it safe on the
	 * committed column a transactional layer still aliases, and it is why the leaf may call it with its pre-decrement
	 * {@code peek} right after {@link #removeKeyAt} has already dropped the entry.
	 *
	 * @param index the first slot to release
	 */
	void clearAt(int index);

	/**
	 * Bulk lockstep move: copies {@code length} keys from {@code this[srcPos]} into {@code dst[dstPos]} (supports
	 * overlapping ranges when {@code dst == this}, like {@code System.arraycopy}). {@code dst} must be the same concrete
	 * kind as this column.
	 *
	 * **Grows the destination to {@code dstPos + length} before any key moves** and sets its {@link #size()} to
	 * {@code max(oldSize, dstPos + length)}, gap-clearing anything between the destination's old live end and
	 * {@code dstPos}. The destination therefore never has to be pre-sized, and the in-place right shift the leaf's
	 * steal-from-left performs ({@code dst == this}) becomes a copy into a larger array rather than an overlapping
	 * move.
	 *
	 * The **source** range must lie within this column's own live run ({@code srcPos + length <= size()}); the leaf
	 * always bounds it by {@code peek}. Only the destination is grown. **Every implementation refuses a violation**
	 * with a premise failure rather than substituting empty keys: a key column has no empty key, so absorbing it
	 * would turn a caller bug into a tree that silently holds wrong keys. {@link RecordColumn#copyRangeTo} takes the
	 * opposite view, and for a reason particular to it — see there.
	 *
	 * @param srcPos the start index in this column
	 * @param dst    the destination column (same concrete kind)
	 * @param dstPos the start index in the destination
	 * @param length the number of keys to copy
	 */
	void copyRangeTo(int srcPos, @Nonnull ValueColumn<M> dst, int dstPos, int length);

	/**
	 * Truncates the live run to {@code fromInclusive}, clearing everything from there on (truncated-tail cleanup on
	 * split / {@code setPeek}).
	 *
	 * **Size-authoritative: {@code toExclusive} bounds nothing and needs no relation to the physical length.** The
	 * split constructor passes {@link #capacity()} there, and {@code createLayer()} routes it onto the committed
	 * column, where the call has to be a harmless no-op rather than an out-of-bounds fill.
	 *
	 * @param fromInclusive the first slot to release (inclusive); a value at or beyond {@link #size()} is a no-op
	 * @param toExclusive   the caller's idea of where the released run ends; retained for call-site readability
	 */
	void fillEmpty(int fromInclusive, int toExclusive);

	/**
	 * Leaf-only ordered search done inside the column over its (possibly primitive) keys; the probe is boxed once by the
	 * caller. Internal nodes keep the boxed default {@code BPlusTreeNode#findKeyPosition(M, M[], int, int)}.
	 *
	 * @param key        the probe key
	 * @param from       the start index (inclusive)
	 * @param to         the end index (exclusive)
	 * @param comparator the key order, or {@code null} for natural order
	 * @return the insertion position (with {@code alreadyPresent} set when the key is found)
	 */
	@Nonnull
	InsertionPosition findKeyPosition(@Nonnull M key, int from, int to, @Nullable Comparator<M> comparator);

	/**
	 * Appends the key at {@code index} to the builder (verbose / debug rendering).
	 *
	 * @param sb    the builder to append to
	 * @param index the slot to render
	 */
	void appendKey(@Nonnull StringBuilder sb, int index);

	/**
	 * Returns the keys as a boxed array for the rare generic / cold callers (consistency verification). For
	 * {@link BoxedObjectColumn} this is the zero-copy backing array; primitive columns materialize on demand (cold
	 * paths only — never the query hot path).
	 *
	 * **The array's length is at least {@link #size()}; anything past the live run is {@code null}.** It is not
	 * {@link #capacity()} and the implementations deliberately disagree about how much longer than the live run they
	 * return — the front-coded column answers at its full logical capacity, the others at their live content. Every
	 * caller walks it bounded by the leaf's {@code peek}, so only the live prefix is ever read.
	 *
	 * @return the boxed key array, live keys first
	 */
	@Nonnull
	M[] asBoxedArray();

	/**
	 * Returns the heap this column occupies in bytes, **excluding whatever its slots point at**.
	 *
	 * The figure covers the column object and every backing array it owns, each at its *allocated* length — which now
	 * tracks the live content rather than the leaf block size, because the backing arrays are grown on demand and
	 * trimmed at the commit merge. **The figure therefore moves as keys are inserted and removed**, and an empty
	 * column costs its object alone: it parks on the JVM-wide shared empty arrays and owns no storage at all.
	 *
	 * The family is uniform in this. {@link FrontCodedStringColumn} used to be the one column whose figure followed
	 * its content; it is now simply the one that stores its content as a variable-length blob instead of as slots.
	 * Growth overshoots the live count by up to a factor of two between reallocations (see {@code ColumnSizing}), so
	 * the figure tracks content in steps rather than exactly.
	 *
	 * For the primitive-backed columns this is the whole story - their keys are values living inside the array. Only
	 * {@link BoxedObjectColumn} stores references, and here it charges the reference slots alone; use
	 * {@link #getHeapSizeInBytes(ToLongFunction)} to add the referenced objects where this column owns them.
	 *
	 * Backing state aliased with a **superseded** version of this column is charged in full - see
	 * {@link FrontCodedStringColumn#duplicate()}, the one structural share in this family. The predecessor is
	 * garbage-in-waiting and the survivor becomes its sole owner, so discounting it would under-report every
	 * committed column.
	 *
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	long getHeapSizeInBytes();

	/**
	 * Returns the heap this column occupies in bytes, **including the objects its slots point at**, each priced by
	 * `elementSizer`.
	 *
	 * The sizer decides ownership and the caller decides the sizer: return `0` for an element this column merely
	 * borrows - a JVM-cached {@link java.util.Locale} or {@link java.util.Currency}, or a value another index owns -
	 * and its real footprint for one this column owns. Nothing here hard-codes which elements are shared, because
	 * that answer belongs to the structure doing the asking rather than to the column.
	 *
	 * Only {@link BoxedObjectColumn} can differ from {@link #getHeapSizeInBytes()}. Every other implementation stores
	 * keys as primitive values or as encoded bytes, has no referenced elements at all, and ignores the sizer.
	 *
	 * @param elementSizer prices a single element; must return `0` for elements this column does not own
	 * @return the heap footprint in bytes, including alignment padding
	 */
	long getHeapSizeInBytes(@Nonnull ToLongFunction<? super M> elementSizer);
}


/**
 * Universal {@link ValueColumn} backed by a boxed {@code M[]}. It is behavior-identical to the inline boxed key array:
 * every operation delegates to the very same {@code ArrayUtils} / {@code System.arraycopy} primitives the leaf invoked
 * directly, so introducing it was a pure refactor.
 *
 * The backing array is sized to the live content rather than to the leaf block size: an empty column holds a
 * zero-length array, the first insert allocates {@code ColumnSizing.MIN_PHYSICAL_LENGTH} slots and growth doubles up
 * to {@link #capacity()}. Slots between {@link #size()} and the array's length are kept {@code null}, so the element
 * scan in {@link #getHeapSizeInBytes(ToLongFunction)} still sees exactly the live keys.
 *
 * **Why the empty array is a fresh zero-length one rather than the shared {@code ArrayUtils.EMPTY_OBJECT_ARRAY}.**
 * {@link #asBoxedArray()} hands the backing array out as an {@code M[]}, and its caller assigns it to an
 * {@code M[]}-typed local — a checkcast to the erased element type, which an {@code Object[]} fails. A zero-length
 * array of the real component type keeps that contract, and it also keeps this column's arithmetic and a JOL walk in
 * agreement without teaching the test-side shared-array exclusion list about a seventh constant. The price is one
 * empty array header per empty column, sixteen bytes, against the roughly one kilobyte the exact sizing saves on the
 * same leaf.
 *
 * @param <M> the key type
 */
final class BoxedObjectColumn<M extends Comparable<M>> implements ValueColumn<M> {
	/**
	 * The component type used to allocate fresh backing arrays of the same element kind.
	 */
	@Nonnull private final Class<M> keyType;
	/**
	 * The logical capacity — the leaf block size, fixed for the column's lifetime. See {@link #capacity()}.
	 */
	private final int capacity;
	/**
	 * The number of live keys held in {@link #keys}, normally equal to the owning leaf's {@code peek + 1} — see
	 * {@link ValueColumn} for the two transient windows in which it is not.
	 */
	private int size;
	/**
	 * The boxed key backing array, sized to the live content rather than to {@link #capacity}. Slots in
	 * {@code [size, keys.length)} are always {@code null}.
	 */
	@Nonnull private M[] keys;

	/**
	 * Creates an empty column with the given component type and logical capacity. No backing storage is allocated
	 * until the first write.
	 *
	 * @param keyType  the key component type
	 * @param capacity the logical capacity (the leaf block size)
	 */
	BoxedObjectColumn(@Nonnull Class<M> keyType, int capacity) {
		this.keyType = keyType;
		this.capacity = capacity;
		this.size = 0;
		this.keys = newArray(keyType, 0);
	}

	/**
	 * Wraps an existing backing array (duplicate / trim paths).
	 *
	 * @param keyType  the key component type
	 * @param capacity the logical capacity
	 * @param size     the live key count
	 * @param keys     the backing array to adopt
	 */
	private BoxedObjectColumn(@Nonnull Class<M> keyType, int capacity, int size, @Nonnull M[] keys) {
		this.keyType = keyType;
		this.capacity = capacity;
		this.size = size;
		this.keys = keys;
	}

	@Override
	public int capacity() {
		return this.capacity;
	}

	@Override
	public int size() {
		return this.size;
	}

	@Override
	public int observableLiveRun() {
		return Math.min(this.size, this.keys.length);
	}

	@Nonnull
	@Override
	public ValueColumn<M> allocate(int capacity) {
		return new BoxedObjectColumn<>(this.keyType, capacity);
	}

	@Nonnull
	@Override
	public ValueColumn<M> trimmed() {
		final int target = ColumnSizing.trimmedLength(this.size, this.keys.length, this.capacity);
		if (target == this.keys.length) {
			return this;
		}
		return new BoxedObjectColumn<>(this.keyType, this.capacity, this.size, Arrays.copyOf(this.keys, target));
	}

	@Nonnull
	@Override
	public ValueColumn<M> duplicate() {
		// the copy owns its array even when that array is empty. Sharing a zero-length one would be safe - there is
		// no element to mutate - but this column cannot use the shared-array exclusion the primitive columns use
		// (`asBoxedArray` has to hand out the erased component type), so `getHeapSizeInBytes` charges the array
		// unconditionally and a shared one would be billed twice, once by each holder
		return new BoxedObjectColumn<>(this.keyType, this.capacity, this.size, this.keys.clone());
	}

	@Nonnull
	@Override
	public ValueColumn<M> duplicateForInsert() {
		return new BoxedObjectColumn<>(
			this.keyType, this.capacity, this.size,
			Arrays.copyOf(this.keys, ColumnSizing.headroomLength(this.size, this.keys.length, this.capacity))
		);
	}

	@Nonnull
	@Override
	public M keyAt(int index) {
		return this.keys[index];
	}

	@Override
	public void insertKeyAt(int index, @Nonnull M value) {
		if (this.size == this.keys.length) {
			growAndInsertKeyAt(index, value);
			return;
		}
		System.arraycopy(this.keys, index, this.keys, index + 1, this.size - index);
		this.keys[index] = value;
		this.size++;
	}

	@Override
	@SuppressWarnings("unchecked")
	public void bulkLoad(@Nonnull Object[] keys, int count) {
		ColumnSizing.assertLoadFitsCapacity(count, this.capacity);
		// always a fresh array: the contract says this column is freshly allocated, and reusing the existing backing
		// would make this the one mutator in the family that writes into an array it did not allocate
		final M[] target = newArray(this.keyType, count);
		for (int i = 0; i < count; i++) {
			target[i] = (M) keys[i];
		}
		this.keys = target;
		this.size = count;
	}

	@Override
	public void removeKeyAt(int index) {
		if (index >= this.size) {
			// the run past `size` is already empty - dropping one empty slot out of it leaves it empty
			return;
		}
		System.arraycopy(this.keys, index + 1, this.keys, index, this.size - index - 1);
		this.size--;
		this.keys[this.size] = null;
	}

	@Override
	public void clearAt(int index) {
		if (index < this.size) {
			Arrays.fill(this.keys, index, this.size, null);
			this.size = index;
		}
	}

	@Override
	public void copyRangeTo(int srcPos, @Nonnull ValueColumn<M> dst, int dstPos, int length) {
		assertSourceRangeIsLive(srcPos, length);
		final BoxedObjectColumn<M> target = asSameKind(dst);
		final int oldSize = target.size;
		final int required = dstPos + length;
		target.ensurePhysicalLength(required);
		if (dstPos > oldSize) {
			// a right shift opens a hole between the destination's old live end and dstPos; it must read as empty
			Arrays.fill(target.keys, oldSize, dstPos, null);
		}
		System.arraycopy(this.keys, srcPos, target.keys, dstPos, length);
		target.size = Math.max(oldSize, required);
	}

	/**
	 * Refuses a source range that reaches past this column's live run. A key column has no empty key it could
	 * substitute, so absorbing the violation would turn a caller bug into a tree that silently holds wrong keys —
	 * the failure mode the leaf's split-range argument already warns about, where half a leaf can vanish with no
	 * exception at all.
	 *
	 * @param srcPos the start index the caller is reading from
	 * @param length the number of keys the caller is reading
	 */
	private void assertSourceRangeIsLive(int srcPos, int length) {
		if (srcPos < 0 || srcPos + length > this.size) {
			throwSourceRangeNotLive(srcPos, length);
		}
	}

	/**
	 * Builds and throws the out-of-range report. Kept out of {@link #assertSourceRangeIsLive} so the check itself is
	 * a pair of integer compares that allocates nothing: it runs on every range copy, and `createLayer()` performs one
	 * per column on the first transactional touch of every leaf, so a message supplier here would allocate thousands
	 * of objects per commit for a check that never fails.
	 *
	 * @param srcPos the start index the caller was reading from
	 * @param length the number of keys the caller was reading
	 */
	private void throwSourceRangeNotLive(int srcPos, int length) {
		throw new GenericEvitaInternalError(
			"Key column source range [" + srcPos + ", " + (srcPos + length) + ") runs past its live run ("
				+ this.size + ") — a key column has no empty key to substitute."
		);
	}

	@Override
	public void fillEmpty(int fromInclusive, int toExclusive) {
		if (fromInclusive < this.size) {
			Arrays.fill(this.keys, fromInclusive, this.size, null);
			this.size = fromInclusive;
		}
	}

	@Nonnull
	@Override
	public InsertionPosition findKeyPosition(@Nonnull M key, int from, int to, @Nullable Comparator<M> comparator) {
		// `to` is the caller's `peek + 1`, read BEFORE this call; the key array is read HERE, after it. A reader
		// sharing no happens-before edge with a warm-up writer can therefore pair a count raised by a grow with the
		// array as it stood before that grow, and index past its end. Binding the search to the length of the very
		// array it will index closes that whichever of the two the reader observed first - the same rule
		// `observableLeafPeek` applies one level up, applied here because the array is not visible up there.
		// On any consistent observer `keys.length >= peek + 1` holds and the bound returns `to` unchanged.
		final M[] theKeys = this.keys;
		final int bound = Math.min(to, theKeys.length);
		return comparator == null
			? computeInsertPositionOfObjInOrderedArray(key, theKeys, from, bound)
			: computeInsertPositionOfObjInOrderedArray(key, theKeys, from, bound, comparator);
	}

	@Override
	public void appendKey(@Nonnull StringBuilder sb, int index) {
		sb.append(this.keys[index]);
	}

	@Nonnull
	@Override
	public M[] asBoxedArray() {
		return this.keys;
	}

	@Override
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		// the column itself: the `keyType` and `keys` references plus the two ints. `keyType` addresses a Class
		// object, which the JVM owns for the lifetime of its class loader and shares with every other holder - only
		// the slot is charged
		return layout.sizeOfObject(2L * layout.referenceSize() + 2L * Integer.BYTES)
			+ layout.sizeOfArray(this.keys.length, layout.referenceSize());
	}

	/**
	 * {@inheritDoc}
	 *
	 * Unlike the primitive columns, which answer in `O(1)`, this one scans the backing array: the null slots are what
	 * distinguishes the tail from the live run, so the scan follows the same rule the reference walk does. That makes
	 * the cost `O(physical length)` — which now follows the live content rather than the whole leaf block — and it
	 * **depends on every mutator clearing the slots it releases**. Should a slot ever be freed without being nulled,
	 * a stale reference would survive past the live range and be priced here, over-charging the column.
	 */
	@Override
	public long getHeapSizeInBytes(@Nonnull ToLongFunction<? super M> elementSizer) {
		long size = getHeapSizeInBytes();
		for (final M key : this.keys) {
			if (key != null) {
				size += elementSizer.applyAsLong(key);
			}
		}
		return size;
	}

	/**
	 * Reallocates {@link #keys} so it holds at least {@code requiredLength} slots, carrying the live keys across. Kept
	 * out of the mutators so their steady-state path stays a single field compare against the array length — the
	 * cursor-free insert path's escape analysis depends on that path staying small.
	 *
	 * @param requiredLength the number of slots the caller is about to address
	 */
	private void ensurePhysicalLength(int requiredLength) {
		if (requiredLength > this.keys.length) {
			this.keys = Arrays.copyOf(
				this.keys, ColumnSizing.grownLength(this.keys.length, requiredLength, this.capacity)
			);
		}
	}

	/**
	 * The out-of-line half of {@link #insertKeyAt}: grows the backing array, then performs the very same shift-and-set
	 * the fast path performs.
	 *
	 * @param index the insertion position
	 * @param value the key to insert
	 */
	private void growAndInsertKeyAt(int index, @Nonnull M value) {
		ensurePhysicalLength(this.size + 1);
		System.arraycopy(this.keys, index, this.keys, index + 1, this.size - index);
		this.keys[index] = value;
		this.size++;
	}

	/**
	 * Allocates a zero-filled array of the column's component type.
	 *
	 * @param keyType the component type
	 * @param length  the array length
	 * @param <M>     the key type
	 * @return the fresh array
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	private static <M extends Comparable<M>> M[] newArray(@Nonnull Class<M> keyType, int length) {
		return (M[]) Array.newInstance(keyType, length);
	}

	/**
	 * Narrows a sibling column to the same concrete kind (one attribute index = one value type ⇒ always holds).
	 *
	 * @param other the sibling column
	 * @return {@code other} as a {@link BoxedObjectColumn}
	 */
	@Nonnull
	private BoxedObjectColumn<M> asSameKind(@Nonnull ValueColumn<M> other) {
		if (other instanceof BoxedObjectColumn<M> boxed) {
			return boxed;
		}
		throw new IllegalArgumentException(
			"Cannot mix value column kinds within one tree: " + other.getClass().getName());
	}
}
