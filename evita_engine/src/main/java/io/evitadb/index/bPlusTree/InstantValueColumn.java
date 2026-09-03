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

package io.evitadb.index.bPlusTree;

import io.evitadb.utils.ArrayUtils.InsertionPosition;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.ToLongFunction;

import static io.evitadb.utils.ArrayUtils.EMPTY_INT_ARRAY;
import static io.evitadb.utils.ArrayUtils.EMPTY_LONG_ARRAY;

/**
 * Primitive {@link ValueColumn} backed by **two** parallel arrays — a {@code long[]} of epoch-seconds and an
 * {@code int[]} of nanoseconds — for temporal attribute keys. The inverted-index normalizer converts every
 * {@code OffsetDateTime} attribute value — and every {@code LocalDateTime} one, anchored at UTC — to an
 * {@link Instant} before it becomes a bucket key (see {@code FilterIndex.getNormalizer}), so the tree stores
 * {@code Instant} keys ordered by natural order.
 *
 * An {@link Instant} is exactly {@code epochSecond} (a {@code long}) plus {@code nano} (an {@code int} in
 * {@code [0, 999_999_999]}); decomposing it into the pair {@code (seconds, nanos)} is therefore a **lossless
 * bijection**, and natural {@code Instant} order equals the lexicographic order of {@code (seconds, nanos)}. That makes
 * the parallel-array layout an exact, order-preserving representation — {@code getRecordsEqualTo} and ordered iteration
 * stay correct without ever boxing an {@code Instant} on a hot path.
 *
 * Zero-allocation invariant: the only boxing happens at the decode boundary ({@link #keyAt}, {@link #asBoxedArray}) and
 * when the probe is decomposed once in {@link #findKeyPosition} — exactly the places the boxed leaf already materialized
 * a key. All bulk / single-slot moves operate in **lockstep** on the two primitive arrays, so no per-element boxing ever
 * occurs and the two arrays can never drift apart (every mutation touches both, with identical indices/lengths).
 *
 * Both arrays follow the live content rather than the leaf block size and are grown, trimmed and cleared **in
 * lockstep**, so they always share one physical length: an empty column parks both on the JVM-wide shared empty arrays
 * and owns nothing, the first insert allocates {@code ColumnSizing.MIN_PHYSICAL_LENGTH} slots in each, and growth
 * doubles up to {@link #capacity()}. See {@link ValueColumn} for the family-wide contract that follows from it.
 *
 * Selected only when the tree comparator is natural order and the normalized key type is {@link Instant} (i.e. the
 * declared attribute type is {@code OffsetDateTime}, {@code Instant} or {@code LocalDateTime}); see
 * {@link ValueColumnFactory}. Otherwise the
 * leaf keeps the universal {@link BoxedObjectColumn}.
 *
 * @param <M> the boxed key type as seen by the tree's generic API (always {@link Instant} at runtime)
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class InstantValueColumn<M extends Comparable<M>> implements ValueColumn<M> {
	/**
	 * The logical capacity — the leaf block size, fixed for the column's lifetime. See {@link #capacity()}.
	 */
	private final int capacity;
	/**
	 * The number of live keys held in the two backing arrays, normally equal to the owning leaf's {@code peek + 1}
	 * — see {@link ValueColumn} for the two transient windows in which it is not.
	 */
	private int size;
	/**
	 * The epoch-second component of each key (parallel with {@link #nanos}, always the same physical length). Slots in
	 * {@code [size, seconds.length)} are always zero.
	 */
	@Nonnull private long[] seconds;
	/**
	 * The nano-of-second component of each key, each in {@code [0, 999_999_999]} (parallel with {@link #seconds},
	 * always the same physical length). Slots in {@code [size, nanos.length)} are always zero.
	 */
	@Nonnull private int[] nanos;

	/**
	 * Creates an empty column for a leaf of the given block size. No backing storage is allocated until the first
	 * write — both array fields park on the JVM-wide shared empty arrays.
	 *
	 * @param capacity the logical capacity (the leaf block size)
	 */
	InstantValueColumn(int capacity) {
		this.capacity = capacity;
		this.size = 0;
		this.seconds = EMPTY_LONG_ARRAY;
		this.nanos = EMPTY_INT_ARRAY;
	}

	/**
	 * Internal constructor adopting pre-built state (duplicate / trim paths). The two arrays must have the same
	 * length; slots are kept in lockstep by every mutation.
	 *
	 * @param capacity the logical capacity
	 * @param size     the live key count
	 * @param seconds  the epoch-second backing array to adopt
	 * @param nanos    the nano-of-second backing array to adopt (same length as {@code seconds})
	 */
	private InstantValueColumn(int capacity, int size, @Nonnull long[] seconds, @Nonnull int[] nanos) {
		this.capacity = capacity;
		this.size = size;
		this.seconds = seconds;
		this.nanos = nanos;
	}

	@Override
	public int capacity() {
		return this.capacity;
	}

	@Override
	public int size() {
		return this.size;
	}

	/**
	 * {@inheritDoc}
	 *
	 * Bounded by the SHORTER of the two parallel arrays. They are grown together, but by two separate
	 * reallocations, so a reader with no happens-before edge can catch the pair mid-grow.
	 */
	@Override
	public int observableLiveRun() {
		return Math.min(this.size, Math.min(this.seconds.length, this.nanos.length));
	}

	@Nonnull
	@Override
	public ValueColumn<M> allocate(int capacity) {
		return new InstantValueColumn<>(capacity);
	}

	@Nonnull
	@Override
	public ValueColumn<M> trimmed() {
		final int target = ColumnSizing.trimmedLength(this.size, this.seconds.length, this.capacity);
		if (target == this.seconds.length) {
			return this;
		}
		return new InstantValueColumn<>(
			this.capacity, this.size, Arrays.copyOf(this.seconds, target), Arrays.copyOf(this.nanos, target)
		);
	}

	@Nonnull
	@Override
	public ValueColumn<M> duplicate() {
		// an empty column keeps the shared empty arrays rather than cloning them into private zero-length ones - the
		// clones would cost two object headers and break the shared-array identity every heap walk subtracts
		return new InstantValueColumn<>(
			this.capacity, this.size,
			this.seconds.length == 0 ? this.seconds : this.seconds.clone(),
			this.nanos.length == 0 ? this.nanos : this.nanos.clone()
		);
	}

	@Nonnull
	@Override
	public ValueColumn<M> duplicateForInsert() {
		// both arrays take the SAME target length: the internal constructor demands they stay equal, and every reader
		// bounds itself by the shorter of the two, so growing only one of them would hide half the headroom
		final int target = ColumnSizing.headroomLength(this.size, this.seconds.length, this.capacity);
		return new InstantValueColumn<>(
			this.capacity, this.size,
			this.seconds.length == 0 ? this.seconds : Arrays.copyOf(this.seconds, target),
			this.nanos.length == 0 ? this.nanos : Arrays.copyOf(this.nanos, target)
		);
	}

	@Nonnull
	@Override
	@SuppressWarnings("unchecked")
	public M keyAt(int index) {
		// boxing boundary — decoded exactly where the boxed leaf would have materialized the key
		return (M) Instant.ofEpochSecond(this.seconds[index], this.nanos[index]);
	}

	@Override
	public void insertKeyAt(int index, @Nonnull M value) {
		if (this.size == this.seconds.length) {
			growAndInsertKeyAt(index, value);
			return;
		}
		// lockstep right-shift of BOTH arrays, then write both components of the new key
		System.arraycopy(this.seconds, index, this.seconds, index + 1, this.size - index);
		System.arraycopy(this.nanos, index, this.nanos, index + 1, this.size - index);
		final Instant inst = (Instant) value;
		this.seconds[index] = inst.getEpochSecond();
		this.nanos[index] = inst.getNano();
		this.size++;
	}

	@Override
	public void bulkLoad(@Nonnull Object[] keys, int count) {
		ColumnSizing.assertLoadFitsCapacity(count, this.capacity);
		// always fresh arrays: the contract says this column is freshly allocated, and reusing the existing backing
		// would make this the one mutator in the family that writes into arrays it did not allocate
		final long[] targetSeconds = newLongArray(count);
		final int[] targetNanos = newIntArray(count);
		for (int i = 0; i < count; i++) {
			final Instant inst = (Instant) keys[i];
			targetSeconds[i] = inst.getEpochSecond();
			targetNanos[i] = inst.getNano();
		}
		this.seconds = targetSeconds;
		this.nanos = targetNanos;
		this.size = count;
	}

	@Override
	public void removeKeyAt(int index) {
		if (index >= this.size) {
			// the run past `size` is already empty - dropping one empty slot out of it leaves it empty
			return;
		}
		// lockstep left-shift of BOTH arrays
		System.arraycopy(this.seconds, index + 1, this.seconds, index, this.size - index - 1);
		System.arraycopy(this.nanos, index + 1, this.nanos, index, this.size - index - 1);
		this.size--;
		this.seconds[this.size] = 0L;
		this.nanos[this.size] = 0;
	}

	@Override
	public void clearAt(int index) {
		if (index < this.size) {
			Arrays.fill(this.seconds, index, this.size, 0L);
			Arrays.fill(this.nanos, index, this.size, 0);
			this.size = index;
		}
	}

	@Override
	public void copyRangeTo(int srcPos, @Nonnull ValueColumn<M> dst, int dstPos, int length) {
		assertSourceRangeIsLive(srcPos, length);
		// lockstep bulk move of BOTH arrays (overlap-safe, like System.arraycopy)
		final InstantValueColumn<M> target = asSameKind(dst);
		final int oldSize = target.size;
		final int required = dstPos + length;
		target.ensurePhysicalLength(required);
		if (dstPos > oldSize) {
			// a right shift opens a hole between the destination's old live end and dstPos; it must read as empty
			Arrays.fill(target.seconds, oldSize, dstPos, 0L);
			Arrays.fill(target.nanos, oldSize, dstPos, 0);
		}
		System.arraycopy(this.seconds, srcPos, target.seconds, dstPos, length);
		System.arraycopy(this.nanos, srcPos, target.nanos, dstPos, length);
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
			Arrays.fill(this.seconds, fromInclusive, this.size, 0L);
			Arrays.fill(this.nanos, fromInclusive, this.size, 0);
			this.size = fromInclusive;
		}
	}

	@Nonnull
	@Override
	public InsertionPosition findKeyPosition(@Nonnull M key, int from, int to, @Nullable Comparator<M> comparator) {
		// the comparator is intentionally ignored: the factory only selects this column for natural-order trees, and the
		// (seconds, nanos) decomposition is lexicographically monotonic with natural Instant order; the probe is
		// decomposed once into its two primitive components
		final Instant probe = (Instant) key;
		final long probeSec = probe.getEpochSecond();
		final int probeNano = probe.getNano();
		// binary search over [from, to) comparing (seconds, nanos) lexicographically; the return encoding mirrors
		// ArrayUtils.computeInsertPositionOfLongInOrderedArray exactly (empty-range ⇒ position 0, not present)
		if (to <= from) {
			return new InsertionPosition(0, false);
		}
		int low = from;
		int high = to - 1;
		while (low <= high) {
			final int mid = (low + high) >>> 1;
			final int cmp = compareAt(mid, probeSec, probeNano);
			if (cmp < 0) {
				low = mid + 1;
			} else if (cmp > 0) {
				high = mid - 1;
			} else {
				return new InsertionPosition(mid, true);
			}
		}
		// on a miss `low` is already the positive insertion point — the same positive position() the other columns and
		// the ArrayUtils.compute… helpers expose (they decode their negative search result before constructing this)
		return new InsertionPosition(low, false);
	}

	@Override
	public void appendKey(@Nonnull StringBuilder sb, int index) {
		sb.append(keyAt(index));
	}

	@Nonnull
	@Override
	@SuppressWarnings("unchecked")
	public M[] asBoxedArray() {
		// cold path only (consistency verification / toString) — never the query hot path; the live run is the whole
		// array, which satisfies the interface's "length >= size, tail empty" contract exactly
		final Instant[] boxed = new Instant[this.size];
		for (int i = 0; i < this.size; i++) {
			boxed[i] = Instant.ofEpochSecond(this.seconds[i], this.nanos[i]);
		}
		return (M[]) boxed;
	}

	@Override
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		// two parallel arrays, always the same length, both following the live content
		long size = layout.sizeOfObject(2L * layout.referenceSize() + 2L * Integer.BYTES);
		// an empty column parks both fields on the JVM-wide shared empty arrays, which cost it nothing beyond the
		// two slots already counted above
		if (this.seconds != EMPTY_LONG_ARRAY) {
			size += layout.sizeOfArray(this.seconds.length, Long.BYTES);
		}
		if (this.nanos != EMPTY_INT_ARRAY) {
			size += layout.sizeOfArray(this.nanos.length, Integer.BYTES);
		}
		return size;
	}

	@Override
	public long getHeapSizeInBytes(@Nonnull ToLongFunction<? super M> elementSizer) {
		// keys decompose into primitive (seconds, nanos) slots - the Instant is materialized on demand and never
		// retained, so there is nothing for the sizer to price
		return getHeapSizeInBytes();
	}

	/**
	 * Reallocates both backing arrays so each holds at least {@code requiredLength} slots, carrying the live keys
	 * across. Kept out of the mutators so their steady-state path stays a single field compare against the array
	 * length — the cursor-free insert path's escape analysis depends on that path staying small.
	 *
	 * @param requiredLength the number of slots the caller is about to address
	 */
	private void ensurePhysicalLength(int requiredLength) {
		if (requiredLength > this.seconds.length) {
			final int grown = ColumnSizing.grownLength(this.seconds.length, requiredLength, this.capacity);
			this.seconds = Arrays.copyOf(this.seconds, grown);
			this.nanos = Arrays.copyOf(this.nanos, grown);
		}
	}

	/**
	 * The out-of-line half of {@link #insertKeyAt}: grows both backing arrays, then performs the very same lockstep
	 * shift-and-set the fast path performs.
	 *
	 * @param index the insertion position
	 * @param value the key to insert
	 */
	private void growAndInsertKeyAt(int index, @Nonnull M value) {
		ensurePhysicalLength(this.size + 1);
		System.arraycopy(this.seconds, index, this.seconds, index + 1, this.size - index);
		System.arraycopy(this.nanos, index, this.nanos, index + 1, this.size - index);
		final Instant inst = (Instant) value;
		this.seconds[index] = inst.getEpochSecond();
		this.nanos[index] = inst.getNano();
		this.size++;
	}

	/**
	 * Allocates an epoch-second backing array of the given length, keeping a zero length on the shared empty array.
	 *
	 * @param length the array length
	 * @return the fresh array
	 */
	@Nonnull
	private static long[] newLongArray(int length) {
		return length == 0 ? EMPTY_LONG_ARRAY : new long[length];
	}

	/**
	 * Allocates a nano-of-second backing array of the given length, keeping a zero length on the shared empty array.
	 *
	 * @param length the array length
	 * @return the fresh array
	 */
	@Nonnull
	private static int[] newIntArray(int length) {
		return length == 0 ? EMPTY_INT_ARRAY : new int[length];
	}

	/**
	 * Compares the key stored at {@code index} with the probe {@code (probeSec, probeNano)} lexicographically — by
	 * epoch-second first, then by nano-of-second — which matches natural {@link Instant} order exactly.
	 *
	 * @param index     the slot to compare
	 * @param probeSec  the probe's epoch-second
	 * @param probeNano the probe's nano-of-second
	 * @return a negative / zero / positive value as the stored key is less than / equal to / greater than the probe
	 */
	private int compareAt(int index, long probeSec, int probeNano) {
		final int secCmp = Long.compare(this.seconds[index], probeSec);
		return secCmp != 0 ? secCmp : Integer.compare(this.nanos[index], probeNano);
	}

	/**
	 * Narrows a sibling column to the same concrete kind (one attribute index = one value type ⇒ always holds).
	 *
	 * @param other the sibling column
	 * @return {@code other} as an {@link InstantValueColumn}
	 */
	@Nonnull
	private InstantValueColumn<M> asSameKind(@Nonnull ValueColumn<M> other) {
		if (other instanceof InstantValueColumn<M> instant) {
			return instant;
		}
		throw new IllegalArgumentException(
			"Cannot mix value column kinds within one tree: " + other.getClass().getName());
	}
}
