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

package io.evitadb.index.bitmap;

import com.carrotsearch.hppc.predicates.IntPredicate;
import io.evitadb.roaringbitmap.PeekableIntIterator;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBatchIterator;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;
import io.evitadb.utils.VMLayout;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.NotThreadSafe;
import java.io.Serial;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator.OfInt;
import java.util.stream.Collectors;

/**
 * IntegerBitmap implementation that is backed by {@link PersistentRoaringBitmap}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NotThreadSafe
public class BaseBitmap implements RoaringBitmapBackedBitmap {
	@Serial private static final long serialVersionUID = -8471705193727315151L;
	private final PersistentRoaringBitmap roaringBitmap;
	private int memoizedCardinality;
	/**
	 * Memoized result of {@link #getContentHash(LongHashFunction)} together with the function that produced it, or
	 * `null` when none has been computed for the current contents. Every mutator clears it, exactly as it
	 * invalidates {@link #memoizedCardinality}.
	 *
	 * The hash and its function travel as **one immutable object** on purpose, and splitting them back into two
	 * fields would be a correctness bug even with a volatile guard between them. Two threads hashing with
	 * *different* functions can interleave their writes so that the guard ends up naming one function while the
	 * value belongs to the other; a third caller then matches the guard and receives a hash that was never computed
	 * for the function it asked about — a wrong formula cache key, not merely a slow one. A record's final fields
	 * are safely published even through a data race, so a reader here sees either `null` or a consistent pair, and
	 * racing writers can only overwrite each other with individually-consistent ones.
	 *
	 * Keying on the function rather than assuming a single global one keeps the memo correct for callers that bring
	 * their own — {@code CacheEnforcingPolicy} builds a separate instance from the same factory as
	 * {@code TransactionalDataRelatedStructure#HASH_FUNCTION}.
	 */
	@Nullable private transient volatile ContentHash memoizedContentHash;

	public BaseBitmap() {
		this.roaringBitmap = new PersistentRoaringBitmap();
		this.memoizedCardinality = 0;
	}

	/**
	 * Builds a bitmap from a raw array of record ids, delegating to
	 * {@link RoaringBitmapBackedBitmap#fromArray} which adaptively picks the cheaper of the writer
	 * or incremental construction strategy depending on the size and density of the input — see
	 * that method for the full reasoning.
	 *
	 * @param recordIds the record ids to store; expected sorted ascending (the density probe reads
	 *                  only the ends, and falls back to the safe incremental path when they are not
	 *                  ascending)
	 */
	public BaseBitmap(@Nonnull int... recordIds) {
		this.roaringBitmap = RoaringBitmapBackedBitmap.fromArray(recordIds);
		this.memoizedCardinality = this.roaringBitmap.getCardinality();
	}

	public BaseBitmap(@Nonnull Bitmap bitmap) {
		final PersistentRoaringBitmap theRoaringBitmap;
		if (bitmap instanceof RoaringBitmapBackedBitmap) {
			theRoaringBitmap = ((RoaringBitmapBackedBitmap) bitmap).getRoaringBitmap().clone();
		} else {
			theRoaringBitmap = RoaringBitmapBackedBitmap.fromArray(bitmap.getArray());
		}
		this.roaringBitmap = theRoaringBitmap;
		this.memoizedCardinality = bitmap.size();
	}

	public BaseBitmap(@Nonnull PersistentRoaringBitmap bitmap) {
		this.roaringBitmap = bitmap;
		this.memoizedCardinality = bitmap.getCardinality();
	}

	@Nonnull
	@Override
	public PersistentRoaringBitmap getRoaringBitmap() {
		return this.roaringBitmap;
	}

	@Override
	public boolean add(int recordId) {
		final boolean added = this.roaringBitmap.checkedAdd(recordId);
		if (added) {
			this.memoizedCardinality = -1;
			this.memoizedContentHash = null;
		}
		return added;
	}

	@Override
	public void addAll(int... recordId) {
		this.roaringBitmap.add(recordId);
		this.memoizedCardinality = -1;
		this.memoizedContentHash = null;
	}

	@Override
	public void addAll(@Nonnull Bitmap recordIds) {
		this.roaringBitmap.add(recordIds.getArray());
		this.memoizedCardinality = -1;
		this.memoizedContentHash = null;
	}

	@Override
	public boolean remove(int recordId) {
		final boolean removed = this.roaringBitmap.checkedRemove(recordId);
		if (removed) {
			this.memoizedCardinality = -1;
			this.memoizedContentHash = null;
		}
		return removed;
	}

	@Override
	public void removeAll(int... recordId) {
		for (int recId : recordId) {
			this.roaringBitmap.remove(recId);
		}
		this.memoizedCardinality = -1;
		this.memoizedContentHash = null;
	}

	@Override
	public void removeAll(@Nonnull Bitmap recordIds) {
		if (recordIds instanceof RoaringBitmapBackedBitmap) {
			this.roaringBitmap.andNot(((RoaringBitmapBackedBitmap) recordIds).getRoaringBitmap());
		} else {
			final OfInt it = recordIds.iterator();
			while (it.hasNext()) {
				final int recordId = it.nextInt();
				this.roaringBitmap.remove(recordId);
			}
		}
		this.memoizedCardinality = -1;
		this.memoizedContentHash = null;
	}

	/**
	 * Removes all elements from the bitmap that satisfy the specified predicate.
	 *
	 * @param predicate a non-null predicate to test each element in the bitmap.
	 *                  Elements for which {@code predicate.apply(int)} returns {@code true} will be removed.
	 */
	public void removeAll(@Nonnull IntPredicate predicate) {
		final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		if (size() > 64) {
			final int[] buffer = new int[64];
			final RoaringBatchIterator batchIterator = this.roaringBitmap.getBatchIterator();
			while (batchIterator.hasNext()) {
				final int peek = batchIterator.nextBatch(buffer);
				for (int i = 0; i < peek; i++) {
					final int next = buffer[i];
					if (predicate.apply(next)) {
						writer.add(next);
					}
				}
			}
		} else {
			final PeekableIntIterator it = this.roaringBitmap.getIntIterator();
			while (it.hasNext()) {
				final int next = it.next();
				if (predicate.apply(next)) {
					writer.add(next);
				}
			}
		}
		this.roaringBitmap.andNot(writer.get());
		this.memoizedCardinality = -1;
		this.memoizedContentHash = null;
	}

	/**
	 * Retains only the elements in the bitmap that satisfy the specified predicate.
	 *
	 * @param predicate a non-null predicate that tests each element in the bitmap.
	 *                  Elements for which {@code predicate.apply(int)} returns {@code false} are removed.
	 *                  Elements for which it returns {@code true} are retained.
	 */
	public void retainAll(@Nonnull IntPredicate predicate) {
		final RoaringBitmapWriter<PersistentRoaringBitmap> writer = RoaringBitmapBackedBitmap.buildWriter();
		if (size() > 64) {
			final int[] buffer = new int[64];
			final RoaringBatchIterator batchIterator = this.roaringBitmap.getBatchIterator();
			while (batchIterator.hasNext()) {
				final int peek = batchIterator.nextBatch(buffer);
				for (int i = 0; i < peek; i++) {
					final int next = buffer[i];
					if (!predicate.apply(next)) {
						writer.add(next);
					}
				}
			}
		} else {
			final PeekableIntIterator it = this.roaringBitmap.getIntIterator();
			while (it.hasNext()) {
				final int next = it.next();
				if (!predicate.apply(next)) {
					writer.add(next);
				}
			}
		}
		this.roaringBitmap.andNot(writer.get());
		this.memoizedCardinality = -1;
		this.memoizedContentHash = null;
	}

	/**
	 * Clears all data in the bitmap.
	 * This method resets the internal bitmap structure, effectively removing all stored record IDs,
	 * and also resets the memoized cardinality to zero and discards the memoized content hash.
	 */
	public void clear() {
		this.roaringBitmap.clear();
		this.memoizedCardinality = 0;
		this.memoizedContentHash = null;
	}

	@Override
	public boolean contains(int recordId) {
		return this.roaringBitmap.contains(recordId);
	}

	@Override
	public int indexOf(int recordId) {
		return RoaringBitmapBackedBitmap.indexOf(this.roaringBitmap, recordId);
	}

	@Override
	public int get(int index) {
		try {
			return this.roaringBitmap.select(index);
		} catch (IllegalArgumentException ex) {
			throw new IndexOutOfBoundsException("Index: " + index + ", Size: " + size());
		}
	}

	@Override
	public int[] getRange(int start, int end) {
		try {
			final int length = end - start;
			final int[] result = new int[length];
			if (result.length == 0) {
				return result;
			}
			result[0] = this.roaringBitmap.select(start);
			final PeekableIntIterator it = this.roaringBitmap.getIntIterator();
			it.advanceIfNeeded(result[0]);
			it.next();
			for (int i = 1; i < length; i++) {
				if (it.hasNext()) {
					result[i] = it.next();
				} else {
					throw new IndexOutOfBoundsException("Index: " + (start + i) + ", Size: " + size());
				}
			}
			return result;
		} catch (IllegalArgumentException ex) {
			throw new IndexOutOfBoundsException("Index: " + start + ", Size: " + size());
		}
	}

	@Override
	public int getFirst() {
		try {
			return this.roaringBitmap.first();
		} catch (NoSuchElementException ex) {
			throw new IndexOutOfBoundsException("IntegerBitmap is empty!");
		}
	}

	@Override
	public int getLast() {
		try {
			return this.roaringBitmap.last();
		} catch (NoSuchElementException ex) {
			throw new IndexOutOfBoundsException("IntegerBitmap is empty!");
		}
	}

	@Override
	public int[] getArray() {
		return RoaringBitmapBackedBitmap.toSignedArray(this.roaringBitmap);
	}

	/**
	 * This wrapper's own object — the `roaringBitmap` reference, the memoized cardinality and the content-hash
	 * memo's reference slot — plus the roaring bitmap it exclusively owns, priced at its containers' allocated
	 * capacity, plus the memo's holder once one has been allocated.
	 *
	 * The holder is charged **conditionally** because it genuinely does not exist until something asks this bitmap
	 * for its content hash; a bitmap that is never hashed pays only the null slot. What is *not* charged either way
	 * is the {@link LongHashFunction} the holder points at: it is one JVM-wide instance every formula hashes with,
	 * so pricing it here would bill the same object once per bitmap in the catalog.
	 *
	 * Neither term needs an alignment correction for the holder's `long`, which a field sum does not obviously
	 * predict and a JOL walk settled twice: a `long` must start 8-aligned, but both objects carry a 4-byte field —
	 * {@link #memoizedCardinality} here, the function reference there — that the JVM slots into the gap a 12-byte
	 * header leaves, so the `long` lands aligned for free. Adding a padding term over-reports by 8 bytes.
	 */
	@Override
	public long getHeapSizeInBytes() {
		final VMLayout layout = VMLayout.current();
		final long ownSize = layout.sizeOfObject(2L * layout.referenceSize() + Integer.BYTES)
			+ this.roaringBitmap.getHeapSizeInBytes(ROARING_HEAP_LAYOUT);
		return this.memoizedContentHash == null ?
			ownSize : ownSize + layout.sizeOfObject(layout.referenceSize() + Long.BYTES);
	}

	/**
	 * {@inheritDoc}
	 *
	 * The result is memoized, so building a formula over the *same* bitmap instance repeatedly pays the `O(size)`
	 * walk once instead of once per formula. That is what makes an index able to memoize its all-records **bitmap**
	 * — which retains nothing query-scoped — and still hand out a fresh formula per call at the cost of one
	 * allocation; see `FilterIndex#memoizedAllRecords`.
	 *
	 * Concurrency: reads are safely publishable across threads even though this class is {@link NotThreadSafe} —
	 * the guard field is written last with volatile semantics, so a reader either misses the memo and recomputes an
	 * identical value, or sees a fully-published one. Mutating a bitmap while other threads read it was already
	 * outside this class' contract and remains so; a mutator clears the memo the same way it clears
	 * {@link #size()}'s.
	 */
	@Override
	public long getContentHash(@Nonnull LongHashFunction hashFunction) {
		final ContentHash memoized = this.memoizedContentHash;
		if (memoized != null && memoized.function() == hashFunction) {
			return memoized.hash();
		}
		final long contentHash = hashFunction.hashInts(getArray());
		// one reference write publishes the hash and its function together - see the field for why they must not
		// be assigned separately
		this.memoizedContentHash = new ContentHash(hashFunction, contentHash);
		return contentHash;
	}

	@Nonnull
	@Override
	public OfInt iterator() {
		return new RoaringBitmapBackedBitmap.RoaringIntIteratorAdapter(this.roaringBitmap.getIntIterator());
	}

	@Override
	public boolean isEmpty() {
		return this.roaringBitmap.isEmpty();
	}

	@Override
	public int size() {
		if (this.memoizedCardinality == -1) {
			this.memoizedCardinality = this.roaringBitmap.getCardinality();
		}
		return this.memoizedCardinality;
	}

	@Override
	public int hashCode() {
		return this.roaringBitmap.hashCode();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		BaseBitmap that = (BaseBitmap) o;
		return this.roaringBitmap.equals(that.roaringBitmap);
	}

	@Override
	public String toString() {
		// we need to unify the output with ArrayBitmap and other implementations
		return "[" + this.roaringBitmap.stream().mapToObj(Integer::toString).collect(Collectors.joining(", ")) + "]";
	}

	/**
	 * A content hash paired with the {@link LongHashFunction} that produced it, so that the two can never be
	 * observed out of step by a concurrent reader — see {@link #memoizedContentHash} for why that matters.
	 *
	 * @param function the function the hash was computed with, compared by identity
	 * @param hash     the hash of the bitmap's record ids as they stood when it was computed
	 */
	private record ContentHash(@Nonnull LongHashFunction function, long hash) {
	}
}
