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
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.ToLongFunction;

/**
 * Primitive {@link ValueColumn} backed by **two** parallel arrays — a {@code long[]} of epoch-seconds and an
 * {@code int[]} of nanoseconds — for temporal attribute keys. The inverted-index normalizer converts every
 * {@code OffsetDateTime} attribute value to an {@link Instant} before it becomes a bucket key (see
 * {@code FilterIndex.getNormalizer}), so the tree stores {@code Instant} keys ordered by natural order.
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
 * Selected only when the tree comparator is natural order and the normalized key type is {@link Instant} (i.e. the
 * declared attribute type is {@code OffsetDateTime} or {@code Instant}); see {@link ValueColumnFactory}. Otherwise the
 * leaf keeps the universal {@link BoxedObjectColumn}.
 *
 * @param <M> the boxed key type as seen by the tree's generic API (always {@link Instant} at runtime)
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class InstantValueColumn<M extends Comparable<M>> implements ValueColumn<M> {
	/**
	 * The epoch-second component of each key (parallel with {@link #nanos}).
	 */
	@Nonnull private final long[] seconds;
	/**
	 * The nano-of-second component of each key, each in {@code [0, 999_999_999]} (parallel with {@link #seconds}).
	 */
	@Nonnull private final int[] nanos;

	/**
	 * Creates a column wrapping the given parallel backing arrays (allocate / duplicate / split paths). The two arrays
	 * must have the same length (== the leaf block size); slots are kept in lockstep by every mutation.
	 *
	 * @param seconds the epoch-second backing array to adopt
	 * @param nanos   the nano-of-second backing array to adopt (same length as {@code seconds})
	 */
	InstantValueColumn(@Nonnull long[] seconds, @Nonnull int[] nanos) {
		this.seconds = seconds;
		this.nanos = nanos;
	}

	@Override
	public int capacity() {
		return this.seconds.length;
	}

	@Nonnull
	@Override
	public ValueColumn<M> allocate(int capacity) {
		return new InstantValueColumn<>(new long[capacity], new int[capacity]);
	}

	@Nonnull
	@Override
	public ValueColumn<M> duplicate() {
		return new InstantValueColumn<>(this.seconds.clone(), this.nanos.clone());
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
		// lockstep right-shift of BOTH arrays, then write both components of the new key
		System.arraycopy(this.seconds, index, this.seconds, index + 1, this.seconds.length - index - 1);
		System.arraycopy(this.nanos, index, this.nanos, index + 1, this.nanos.length - index - 1);
		final Instant inst = (Instant) value;
		this.seconds[index] = inst.getEpochSecond();
		this.nanos[index] = inst.getNano();
	}

	@Override
	public void bulkLoad(@Nonnull Object[] keys, int count) {
		for (int i = 0; i < count; i++) {
			final Instant inst = (Instant) keys[i];
			this.seconds[i] = inst.getEpochSecond();
			this.nanos[i] = inst.getNano();
		}
	}

	@Override
	public void removeKeyAt(int index) {
		// lockstep left-shift of BOTH arrays
		System.arraycopy(this.seconds, index + 1, this.seconds, index, this.seconds.length - index - 1);
		System.arraycopy(this.nanos, index + 1, this.nanos, index, this.nanos.length - index - 1);
	}

	@Override
	public void clearAt(int index) {
		this.seconds[index] = 0L;
		this.nanos[index] = 0;
	}

	@Override
	public void copyRangeTo(int srcPos, @Nonnull ValueColumn<M> dst, int dstPos, int length) {
		// lockstep bulk move of BOTH arrays (overlap-safe, like System.arraycopy)
		final InstantValueColumn<M> target = asSameKind(dst);
		System.arraycopy(this.seconds, srcPos, target.seconds, dstPos, length);
		System.arraycopy(this.nanos, srcPos, target.nanos, dstPos, length);
	}

	@Override
	public void fillEmpty(int fromInclusive, int toExclusive) {
		Arrays.fill(this.seconds, fromInclusive, toExclusive, 0L);
		Arrays.fill(this.nanos, fromInclusive, toExclusive, 0);
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
		// cold path only (consistency verification / toString) — never the query hot path
		final Instant[] boxed = new Instant[this.seconds.length];
		for (int i = 0; i < this.seconds.length; i++) {
			boxed[i] = Instant.ofEpochSecond(this.seconds[i], this.nanos[i]);
		}
		return (M[]) boxed;
	}

	@Override
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		// two parallel arrays, both allocated at the leaf block size and always the same length
		return layout.sizeOfObject(2L * layout.referenceSize())
			+ layout.sizeOfArray(this.seconds.length, Long.BYTES)
			+ layout.sizeOfArray(this.nanos.length, Integer.BYTES);
	}

	@Override
	public long getHeapSizeInBytes(@Nonnull ToLongFunction<? super M> elementSizer) {
		// keys decompose into primitive (seconds, nanos) slots - the Instant is materialized on demand and never
		// retained, so there is nothing for the sizer to price
		return getHeapSizeInBytes();
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
