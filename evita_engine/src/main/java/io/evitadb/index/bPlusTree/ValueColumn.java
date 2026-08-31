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
import static io.evitadb.utils.ArrayUtils.insertRecordIntoSameArrayOnIndex;
import static io.evitadb.utils.ArrayUtils.removeRecordFromSameArrayOnIndex;

/**
 * Pluggable key (bucket value) column of a {@link TransactionalBucketBPlusTree} leaf. It abstracts the leaf's key
 * storage so a leaf can hold its keys in the cheapest representation for the attribute type — a boxed {@code Object[]}
 * ({@link BoxedObjectColumn}, the universal fallback), for numeric / temporal attributes a primitive column
 * (`long[]` and parallel-array variants), or for {@link String} attributes a front-coded (prefix-compressed)
 * variable-length {@code byte[]}-blob column ({@link FrontCodedStringColumn}, selected for every {@link String} key
 * regardless of comparator). The {@code int[]} single-record column and the lazy
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
 * @param <M> the (boxed) key type as seen by the tree's generic API
 */
sealed interface ValueColumn<M extends Comparable<M>>
	permits BoxedObjectColumn, LongValueColumn, InstantValueColumn, IntValueColumn, FrontCodedStringColumn {

	/**
	 * Returns the backing capacity (== the leaf's block size); slots in {@code [size, capacity)} are unused.
	 *
	 * @return the backing capacity
	 */
	int capacity();

	/**
	 * Creates a new **empty** column of the same concrete kind and the given capacity (split / layer target).
	 *
	 * @param capacity the capacity of the new column
	 * @return a fresh empty column of the same kind
	 */
	@Nonnull
	ValueColumn<M> allocate(int capacity);

	/**
	 * Creates an independent, non-aliasing copy of this column (new identity) used to decouple a transactional
	 * layer's key column from the shared base on first write. Most implementations deep-copy their backing array(s);
	 * one that mutates exclusively by whole-reference replacement (never edits bytes/elements of a retained array in
	 * place) may instead structurally share that backing state — see {@link FrontCodedStringColumn#duplicate()} for
	 * the concrete example and the invariant that safety depends on.
	 *
	 * @return an independent copy of this column, safe to mutate without affecting the source
	 */
	@Nonnull
	ValueColumn<M> duplicate();

	/**
	 * Returns the (boxed) key at the given index. Boxing boundary — call only where the boxed leaf already materialized
	 * a key (per-visited-bucket / per-leaf-separator / cold paths).
	 *
	 * @param index the slot to read
	 * @return the boxed key at {@code index}
	 */
	@Nonnull
	M keyAt(int index);

	/**
	 * Whether {@link #containsUtf8At} can answer for this column without materialising the key as an `M`.
	 *
	 * Consulted once per query rather than per candidate, because it is a property of the column's storage rather
	 * than of the slot. Only {@link FrontCodedStringColumn} answers `true`: it is the only implementation that
	 * already holds its keys as UTF-8 bytes, so it is the only one for which byte matching avoids work rather than
	 * inventing it.
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
	 * Inserts {@code value} at {@code index}, shifting the tail one slot to the right (the leaf grows {@code peek}
	 * afterwards). Mirrors {@code ArrayUtils.insertRecordIntoSameArrayOnIndex} on the key array.
	 *
	 * @param index the insertion position
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
	 * @param keys  the ascending-ordered keys to load; only {@code keys[0, count)} are read
	 * @param count the number of live keys ({@code <= capacity()})
	 */
	void bulkLoad(@Nonnull Object[] keys, int count);

	/**
	 * Removes the key at {@code index}, shifting the tail one slot to the left (the leaf clears the freed last slot via
	 * {@link #clearAt} and shrinks {@code peek} afterwards). Mirrors {@code ArrayUtils.removeRecordFromSameArrayOnIndex}.
	 *
	 * @param index the slot to remove
	 */
	void removeKeyAt(int index);

	/**
	 * Clears (nulls / zeroes) the slot at {@code index} — used to release the freed last slot after a delete or a
	 * downward {@code setPeek}.
	 *
	 * @param index the slot to clear
	 */
	void clearAt(int index);

	/**
	 * Bulk lockstep move: copies {@code length} keys from {@code this[srcPos]} into {@code dst[dstPos]} (supports
	 * overlapping ranges when {@code dst == this}, like {@code System.arraycopy}). {@code dst} must be the same concrete
	 * kind as this column.
	 *
	 * @param srcPos the start index in this column
	 * @param dst    the destination column (same concrete kind)
	 * @param dstPos the start index in the destination
	 * @param length the number of keys to copy
	 */
	void copyRangeTo(int srcPos, @Nonnull ValueColumn<M> dst, int dstPos, int length);

	/**
	 * Clears the slots in {@code [fromInclusive, toExclusive)} (truncated-tail cleanup on split / {@code setPeek}).
	 *
	 * @param fromInclusive the first slot to clear (inclusive)
	 * @param toExclusive   the slot to stop at (exclusive)
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
	 * @return the boxed key array (length == {@link #capacity()})
	 */
	@Nonnull
	M[] asBoxedArray();

	/**
	 * Returns the heap this column occupies in bytes, **excluding whatever its slots point at**.
	 *
	 * The figure covers the column object and every backing array it owns, each at its *allocated* length rather than
	 * its live entry count: a column keeps the capacity it was allocated with, so the slots in `[size, capacity)` are
	 * paid for even while they hold nothing. That is the honest number for a leaf block, which is sized once and then
	 * fills up — so for these columns the figure does **not** move as keys are inserted.
	 *
	 * {@link FrontCodedStringColumn} is the one exception, and deliberately so: it allocates no per-slot storage at
	 * all, encoding its keys into a variable-length blob that is re-trimmed on every write. Its figure therefore
	 * *does* grow with the content it holds. Do not assume uniformity across the family here.
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
 * directly, so introducing it is a pure refactor.
 *
 * @param <M> the key type
 */
final class BoxedObjectColumn<M extends Comparable<M>> implements ValueColumn<M> {
	/**
	 * The component type used to allocate fresh backing arrays of the same element kind.
	 */
	@Nonnull private final Class<M> keyType;
	/**
	 * The boxed key backing array.
	 */
	@Nonnull private final M[] keys;

	/**
	 * Creates an empty column with the given component type and capacity.
	 *
	 * @param keyType  the key component type
	 * @param capacity the backing capacity (block size)
	 */
	BoxedObjectColumn(@Nonnull Class<M> keyType, int capacity) {
		this.keyType = keyType;
		//noinspection unchecked
		this.keys = (M[]) Array.newInstance(keyType, capacity);
	}

	/**
	 * Wraps an existing backing array (split / duplicate paths).
	 *
	 * @param keyType the key component type
	 * @param keys    the backing array to adopt
	 */
	private BoxedObjectColumn(@Nonnull Class<M> keyType, @Nonnull M[] keys) {
		this.keyType = keyType;
		this.keys = keys;
	}

	@Override
	public int capacity() {
		return this.keys.length;
	}

	@Nonnull
	@Override
	public ValueColumn<M> allocate(int capacity) {
		return new BoxedObjectColumn<>(this.keyType, capacity);
	}

	@Nonnull
	@Override
	public ValueColumn<M> duplicate() {
		return new BoxedObjectColumn<>(this.keyType, this.keys.clone());
	}

	@Nonnull
	@Override
	public M keyAt(int index) {
		return this.keys[index];
	}

	@Override
	public void insertKeyAt(int index, @Nonnull M value) {
		insertRecordIntoSameArrayOnIndex(value, this.keys, index);
	}

	@Override
	@SuppressWarnings("unchecked")
	public void bulkLoad(@Nonnull Object[] keys, int count) {
		for (int i = 0; i < count; i++) {
			this.keys[i] = (M) keys[i];
		}
	}

	@Override
	public void removeKeyAt(int index) {
		removeRecordFromSameArrayOnIndex(this.keys, index);
	}

	@Override
	public void clearAt(int index) {
		this.keys[index] = null;
	}

	@Override
	public void copyRangeTo(int srcPos, @Nonnull ValueColumn<M> dst, int dstPos, int length) {
		System.arraycopy(this.keys, srcPos, asSameKind(dst).keys, dstPos, length);
	}

	@Override
	public void fillEmpty(int fromInclusive, int toExclusive) {
		Arrays.fill(this.keys, fromInclusive, toExclusive, null);
	}

	@Nonnull
	@Override
	public InsertionPosition findKeyPosition(@Nonnull M key, int from, int to, @Nullable Comparator<M> comparator) {
		return comparator == null
			? computeInsertPositionOfObjInOrderedArray(key, this.keys, from, to)
			: computeInsertPositionOfObjInOrderedArray(key, this.keys, from, to, comparator);
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
		// the column itself: the `keyType` and `keys` references. `keyType` addresses a Class object, which the JVM
		// owns for the lifetime of its class loader and shares with every other holder - only the slot is charged
		return layout.sizeOfObject(2L * layout.referenceSize())
			+ layout.sizeOfArray(this.keys.length, layout.referenceSize());
	}

	/**
	 * {@inheritDoc}
	 *
	 * Unlike the primitive columns, which answer in `O(1)`, this one scans the backing array: the column does not
	 * track its own live count, so the null slots are what distinguishes the tail. That makes the cost `O(capacity)`
	 * — one leaf block, not one index — and it **depends on the leaf nulling the truncated tail** through
	 * {@link #fillEmpty}. Should `peek` ever shrink without that call, stale references would survive past the live
	 * range and be priced here, over-charging the column.
	 */
	@Override
	public long getHeapSizeInBytes(@Nonnull ToLongFunction<? super M> elementSizer) {
		long size = getHeapSizeInBytes();
		for (int i = 0; i < this.keys.length; i++) {
			final M key = this.keys[i];
			if (key != null) {
				size += elementSizer.applyAsLong(key);
			}
		}
		return size;
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
