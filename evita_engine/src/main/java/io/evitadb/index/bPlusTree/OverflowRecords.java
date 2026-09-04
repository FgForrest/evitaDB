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

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.bitmap.SortedArrayBitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.VMLayout;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.util.Arrays;

/**
 * The record set of one **multi-record** bucket of a {@link TransactionalBucketBPlusTree} leaf, in the tiered
 * representation issue #1455 measured, together with every operation the leaf performs on it.
 *
 * ## The three tiers, and why there are three
 *
 * A bucket's record set is held in one of three shapes, and a slot of {@link OverflowColumn} carries the upper two:
 *
 * | slot | shape | cardinality |
 * |---|---|---|
 * | `null` | the lone record id in the leaf's primitive `records` column | 1 |
 * | `int[]` | a sorted, immutable array of ids | 2 .. {@link #SMALL_BUCKET_THRESHOLD} |
 * | {@link TransactionalBitmap} | a roaring bitmap, mutated in place | above the threshold |
 *
 * The middle tier is the one this class adds. A census of a production e-commerce catalog found 395,613
 * multi-record buckets costing 129.9 MB of roaring bitmaps to hold 8,782,760 record ids with a median cardinality
 * of five - almost all of it the bitmap's fixed overhead. The same ids as sorted arrays cost 39.5 MB.
 *
 * ## A slot is a bare `Object`, not a sealed interface
 *
 * An `int[]` cannot implement an interface, so a typed slot would need one wrapper object per multi-record bucket -
 * a second header and an indirection on hundreds of thousands of buckets - to buy type safety inside a
 * package-private column whose only caller is the leaf. {@link io.evitadb.index.trigram.TrigramPostings} makes the
 * same trade for the same reason, and the leaf already discriminates the single-record tier by a `null` slot rather
 * than by a type.
 *
 * **A bucket's representation is therefore not a function of its cardinality**, because the promote and demote
 * thresholds differ (see {@link #SMALL_BUCKET_DEMOTION_THRESHOLD}). Every consumer must dispatch on what the slot
 * *is* and never infer it from {@link #cardinality(Object)}.
 *
 * ## Mutation contract: an array slot is NEVER written in place
 *
 * Every mutator here returns the slot to store back, and the array arm always returns a *different* array when the
 * content changes. That is what lets the array tier inherit the leaf's own MVCC isolation with no transactional
 * wrapper of its own: a leaf mutation already runs on a decoupled transactional layer whose overflow column is a
 * private copy of the committed one, so replacing a reference in it is invisible to the committed leaf, and the
 * leaf's savepoint memento - a shallow copy of the reference array - restores the pre-image completely because the
 * arrays it points at were never touched. An array mutated in place would instead be seen changed by every older
 * index version sharing it, and a rollback would restore the reference while leaving the content changed.
 *
 * The bitmap arm keeps mutating in place, because a {@link TransactionalBitmap} owns a transactional diff layer that
 * provides exactly the same isolation more cheaply at high cardinality.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class OverflowRecords {

	/**
	 * The largest record set still held as a sorted `int[]`; a bucket that would grow past it becomes a
	 * {@link TransactionalBitmap}.
	 *
	 * This is the same number as {@link io.evitadb.index.trigram.TrigramPostings#SMALL_POSTING_THRESHOLD} and for the
	 * same measured reason - the #258 spike located the flat `int[]`-vs-roaring knee at 128 elements, within 1.7 % of
	 * every per-attribute optimum - but it is re-declared here rather than imported, because this package must not
	 * depend on the trigram package (the dependency runs the other way).
	 *
	 * On the bucket census the choice is worth ~95.9 MB against a ~96.4 MB per-bucket optimum; the alternative of 32
	 * captures ~90.2 MB, giving up the 33..128 band for a shorter worst-case array copy on 0.2 % of buckets.
	 */
	static final int SMALL_BUCKET_THRESHOLD = 128;

	/**
	 * The committed cardinality at or below which a {@link TransactionalBitmap} bucket falls back to a sorted `int[]`.
	 *
	 * Deliberately HALF of {@link #SMALL_BUCKET_THRESHOLD} rather than equal to it: demoting at the cardinality that
	 * promotes would make a bucket sitting on the boundary rebuild its representation on every write. The gap is the
	 * hysteresis that makes the representation change at most once per 64 net memberships.
	 */
	static final int SMALL_BUCKET_DEMOTION_THRESHOLD = SMALL_BUCKET_THRESHOLD / 2;

	/**
	 * The record set of a bucket that has lost every id it held - the shape {@link #remove} returns to say so.
	 *
	 * A real, shared, empty array rather than `null`, because `null` already means "single-record bucket" at a slot.
	 * The leaf turns it into a real deletion: the bucket leaves the tree rather than sitting in it empty.
	 */
	private static final int[] EMPTY_RECORDS = ArrayUtils.EMPTY_INT_ARRAY;

	private OverflowRecords() {
		throw new UnsupportedOperationException("OverflowRecords is a static utility and must not be instantiated!");
	}

	/**
	 * Builds the record set of a bucket promoted out of the single-record tier by a second, distinct record id.
	 *
	 * @param held  the id the bucket already holds
	 * @param added the second, distinct id
	 * @return the two ids as a sorted array slot
	 */
	@Nonnull
	static int[] promoteSingle(int held, int added) {
		return Integer.compareUnsigned(held, added) < 0 ? new int[]{held, added} : new int[]{added, held};
	}

	/**
	 * Builds the record set of a bucket promoted out of the single-record tier by several added ids, any of which may
	 * be the id the bucket already holds.
	 *
	 * The parameter is a plain array rather than varargs on purpose: a varargs overload would be indistinguishable
	 * from the two-argument one at a call site passing two `int`s.
	 *
	 * @param held  the id the bucket already holds
	 * @param added the ids to add; must be non-empty
	 * @return the union as a sorted array slot, or a {@link TransactionalBitmap} when it exceeds the threshold
	 */
	@Nonnull
	static Object promoteSingleWithAll(int held, @Nonnull int[] added) {
		return merge(new int[]{held}, added);
	}

	/**
	 * Builds the multi-record slot a persisted bucket must be replayed into, from the record set the page carried.
	 *
	 * A load has no prior representation to be hysteretic about, so the tier follows the cardinality directly: at or
	 * below {@link #SMALL_BUCKET_THRESHOLD} the ids are kept as the sorted array the bitmap already enumerates them
	 * in, above it a {@link TransactionalBitmap} is built. This is what lets an existing catalog come back in the
	 * tiered representation with no migration, and without first constructing the bitmaps it would only demote.
	 *
	 * The caller must have established that the bucket holds more than one record; a single-record bucket carries its
	 * id in the leaf's primitive column and has no slot here at all.
	 *
	 * @param recordIds the persisted bucket's record ids
	 * @return the slot to load into the overflow column
	 */
	@Nonnull
	public static Object loadedRecordSet(@Nonnull Bitmap recordIds) {
		if (recordIds.size() > SMALL_BUCKET_THRESHOLD) {
			return new TransactionalBitmap(recordIds);
		}
		return unsignedSortedArrayOf(recordIds);
	}

	/**
	 * Builds the record set of a bucket that is born multi-record - a leaf insert whose value arrives with several ids
	 * at once, and the load path replaying a persisted page.
	 *
	 * @param pks the record ids, in any order, possibly with repeats; must hold at least two distinct ids
	 * @return the ids as a sorted array slot, or a {@link TransactionalBitmap} above the threshold
	 */
	@Nonnull
	static Object ofDistinctUnordered(@Nonnull int... pks) {
		return merge(EMPTY_RECORDS, pks);
	}

	/**
	 * Adds one record id to a multi-record bucket.
	 *
	 * @param records the bucket's current record set (an `int[]` or a {@link TransactionalBitmap})
	 * @param pk      the record id to add
	 * @return the record set to store back - the very same instance when nothing changed or when the bitmap arm
	 * mutated in place, a different array otherwise
	 */
	@Nonnull
	static Object add(@Nonnull Object records, int pk) {
		if (records instanceof final int[] small) {
			final int position = SortedArrayBitmap.unsignedBinarySearch(small, pk);
			if (position >= 0) {
				// already a member - re-adding is a no-op and allocates nothing
				return small;
			}
			if (small.length + 1 > SMALL_BUCKET_THRESHOLD) {
				final TransactionalBitmap promoted = new TransactionalBitmap(small);
				promoted.add(pk);
				return promoted;
			}
			// the insertion point is what the search encodes in its negative return
			return ArrayUtils.insertIntIntoArrayOnIndex(pk, small, -position - 1);
		}
		// the bitmap arm keeps its in-place semantics: its own diff layer provides the isolation
		asBitmap(records).add(pk);
		return records;
	}

	/**
	 * Adds several record ids to a multi-record bucket.
	 *
	 * @param records the bucket's current record set (an `int[]` or a {@link TransactionalBitmap})
	 * @param pks     the record ids to add; must be non-empty, may repeat and may already be members
	 * @return the record set to store back
	 */
	@Nonnull
	static Object addAll(@Nonnull Object records, @Nonnull int... pks) {
		if (records instanceof final int[] small) {
			return merge(small, pks);
		}
		asBitmap(records).addAll(pks);
		return records;
	}

	/**
	 * Removes several record ids from a multi-record bucket. Ids that are not members are silently ignored, exactly as
	 * the bitmap arm has always ignored them.
	 *
	 * @param records the bucket's current record set (an `int[]` or a {@link TransactionalBitmap})
	 * @param pks     the record ids to remove; must be non-empty
	 * @return the record set to store back; {@link #cardinality(Object)} of `0` means the bucket drained and the leaf
	 * must delete it
	 */
	@Nonnull
	static Object remove(@Nonnull Object records, @Nonnull int... pks) {
		if (records instanceof final int[] small) {
			// mark the positions to drop in one pass, then compact once - removing one at a time would copy the whole
			// array per id
			final boolean[] dropped = new boolean[small.length];
			int dropCount = 0;
			for (final int pk : pks) {
				final int position = SortedArrayBitmap.unsignedBinarySearch(small, pk);
				if (position >= 0 && !dropped[position]) {
					dropped[position] = true;
					dropCount++;
				}
			}
			if (dropCount == 0) {
				return small;
			}
			if (dropCount == small.length) {
				return EMPTY_RECORDS;
			}
			final int[] survivors = new int[small.length - dropCount];
			int target = 0;
			for (int i = 0; i < small.length; i++) {
				if (!dropped[i]) {
					survivors[target++] = small[i];
				}
			}
			return survivors;
		}
		// the bitmap arm keeps its in-place semantics; demotion back to an array happens at the commit merge only
		asBitmap(records).removeAll(pks);
		return records;
	}

	/**
	 * Returns how many record ids a bucket holds, without materializing anything.
	 *
	 * @param records the bucket's record set (an `int[]` or a {@link TransactionalBitmap})
	 * @return the cardinality
	 */
	static int cardinality(@Nonnull Object records) {
		return records instanceof final int[] small ? small.length : asBitmap(records).size();
	}

	/**
	 * Exposes a bucket's record set as a {@link Bitmap} for the read path.
	 *
	 * The bitmap arm returns the **live** {@link TransactionalBitmap} exactly as it always has (so a caller keeps
	 * seeing the transaction's own changes); the array arm returns a fresh read-only {@link SortedArrayBitmap} view
	 * that shares the array without copying it. Both must be treated as read-only by the caller - the tree hands out
	 * its own storage.
	 *
	 * @param records the bucket's record set (an `int[]` or a {@link TransactionalBitmap})
	 * @return the record ids as a bitmap
	 */
	@Nonnull
	static Bitmap asBitmapView(@Nonnull Object records) {
		if (records instanceof final int[] small) {
			return small.length == 0 ? EmptyBitmap.INSTANCE : new SortedArrayBitmap(small);
		}
		return asBitmap(records);
	}

	/**
	 * Returns the greatest record id of a bucket that is at or below `fromValue` **in signed order** - the array
	 * tier's equivalent of {@link TransactionalBitmap#signedPreviousValue(int)}.
	 *
	 * The array is sorted by unsigned comparison, so every negative id sits in a suffix after every non-negative one;
	 * in signed order that suffix comes first. The two-half search below is the array form of the very same logic
	 * {@link RoaringBitmapBackedBitmap#signedPreviousValue} applies to a roaring bitmap, so the two tiers answer
	 * identically for the same record set.
	 *
	 * @param records   the bucket's record set (an `int[]` or a {@link TransactionalBitmap})
	 * @param fromValue inclusive upper bound in signed order
	 * @return the greatest signed record id at or below the bound, or
	 * {@link RoaringBitmapBackedBitmap#NO_PREVIOUS_VALUE} when the bucket holds none
	 */
	static long signedPreviousValue(@Nonnull Object records, int fromValue) {
		if (!(records instanceof final int[] small)) {
			return asBitmap(records).signedPreviousValue(fromValue);
		}
		if (small.length == 0) {
			return RoaringBitmapBackedBitmap.NO_PREVIOUS_VALUE;
		}
		final int firstNegative = SortedArrayBitmap.firstNegativeIndex(small);
		if (fromValue < 0) {
			// the negative half only - nothing non-negative is signed-smaller than a negative bound
			final int floor = floorIndex(small, firstNegative, small.length, fromValue);
			return floor < 0 ? RoaringBitmapBackedBitmap.NO_PREVIOUS_VALUE : small[floor];
		}
		final int floor = floorIndex(small, 0, firstNegative, fromValue);
		if (floor >= 0) {
			return small[floor];
		}
		// no non-negative id at or below the bound - fall back to the greatest negative one, which under unsigned
		// ordering is the last element of the array
		return firstNegative < small.length
			? small[small.length - 1] : RoaringBitmapBackedBitmap.NO_PREVIOUS_VALUE;
	}

	/**
	 * Returns the heap a bucket's record set occupies, excluding the slot in the overflow column that points at it -
	 * the column charges that itself.
	 *
	 * @param records the bucket's record set (an `int[]` or a {@link TransactionalBitmap})
	 * @return the owned heap footprint in bytes, including alignment padding
	 */
	static long heapSizeInBytes(@Nonnull Object records) {
		if (records instanceof final int[] small) {
			// the shared empty array is one instance for the whole JVM that no leaf owns
			return small.length == 0 ? 0L : VMLayout.current().sizeOfArray(small.length, Integer.BYTES);
		}
		return asBitmap(records).getHeapSizeInBytes();
	}

	/**
	 * Converts a **committed** record set into the representation its cardinality earns, at the leaf's commit merge.
	 * This is the only point at which a bucket is ever demoted, and the only point at which the committed content of a
	 * {@link TransactionalBitmap} is obtainable at all.
	 *
	 * @param committed the committed record set of the bucket
	 * @return the sorted array a small record set should be stored as, or `null` when the bitmap it already is should
	 * be kept
	 */
	@Nullable
	static int[] demotedToArray(@Nonnull Bitmap committed) {
		final int cardinality = committed.size();
		if (cardinality > SMALL_BUCKET_DEMOTION_THRESHOLD) {
			return null;
		}
		return unsignedSortedArrayOf(committed);
	}

	/**
	 * Extracts a bitmap's record ids in the **unsigned** ascending order an array slot is kept in.
	 *
	 * {@link Bitmap#getArray()} cannot be used: it answers in *signed* order (see
	 * {@link TransactionalBitmap#getArray()}), which puts the negative ids first, whereas roaring's own enumeration -
	 * the order this class sorts by - puts them last. Reading the roaring bitmap directly gives the right order
	 * without a re-sort, and every bitmap reaching this method is roaring-backed (a persisted bucket, or the committed
	 * state of a {@link TransactionalBitmap}).
	 *
	 * @param recordIds the bitmap to read
	 * @return a fresh array of its ids, sorted by {@link Integer#compareUnsigned}
	 */
	@Nonnull
	private static int[] unsignedSortedArrayOf(@Nonnull Bitmap recordIds) {
		return RoaringBitmapBackedBitmap.getRoaringBitmap(recordIds).toArray();
	}

	/**
	 * Merges `added` into the sorted `existing` array, promoting the result to a {@link TransactionalBitmap} when the
	 * union exceeds the threshold.
	 *
	 * @param existing the current sorted, distinct record ids
	 * @param added    the ids to add; may repeat, may already be members, in any order
	 * @return the union as a sorted array, or a {@link TransactionalBitmap} above the threshold
	 */
	@Nonnull
	private static Object merge(@Nonnull int[] existing, @Nonnull int... added) {
		// never sort the caller's array - `added` is a varargs argument the caller may still hold
		final int[] candidates = added.clone();
		sortUnsigned(candidates);
		// count the ids that are genuinely new, so the union array is allocated exactly once at its final length
		int newCount = 0;
		int previous = 0;
		for (int i = 0; i < candidates.length; i++) {
			if (i > 0 && candidates[i] == previous) {
				continue;
			}
			previous = candidates[i];
			if (SortedArrayBitmap.unsignedBinarySearch(existing, candidates[i]) < 0) {
				newCount++;
			}
		}
		if (newCount == 0) {
			return existing;
		}
		final int unionSize = existing.length + newCount;
		if (unionSize > SMALL_BUCKET_THRESHOLD) {
			final TransactionalBitmap promoted = new TransactionalBitmap(existing);
			promoted.addAll(added);
			return promoted;
		}
		final int[] union = new int[unionSize];
		int left = 0;
		int right = 0;
		int target = 0;
		while (left < existing.length || right < candidates.length) {
			if (right < candidates.length && right > 0 && candidates[right] == candidates[right - 1]) {
				right++;
			} else if (left >= existing.length) {
				union[target++] = candidates[right++];
			} else if (right >= candidates.length) {
				union[target++] = existing[left++];
			} else {
				final int comparison = Integer.compareUnsigned(existing[left], candidates[right]);
				if (comparison < 0) {
					union[target++] = existing[left++];
				} else if (comparison > 0) {
					union[target++] = candidates[right++];
				} else {
					// present in both - take it once
					union[target++] = existing[left++];
					right++;
				}
			}
		}
		if (target != unionSize) {
			throw new GenericEvitaInternalError(
				"Merged bucket record set holds " + target + " ids where " + unionSize + " were counted!"
			);
		}
		return union;
	}

	/**
	 * Sorts the array by {@link Integer#compareUnsigned}. Flipping the sign bit maps the unsigned order onto the
	 * signed one, so the JDK's primitive sort can be used unchanged, and flipping it back is its own inverse.
	 *
	 * @param records the array to sort in place
	 */
	private static void sortUnsigned(@Nonnull int[] records) {
		for (int i = 0; i < records.length; i++) {
			records[i] ^= Integer.MIN_VALUE;
		}
		Arrays.sort(records);
		for (int i = 0; i < records.length; i++) {
			records[i] ^= Integer.MIN_VALUE;
		}
	}

	/**
	 * Returns the index of the greatest record id in `[from, to)` that is at or below `key` under unsigned comparison,
	 * or `-1` when the range holds none. Both halves of an unsigned-sorted array are internally ordered the same way
	 * signed and unsigned, so a caller that has narrowed the range to one half gets the signed answer from this.
	 *
	 * @param records the unsigned-sorted record ids
	 * @param from    inclusive range start
	 * @param to      exclusive range end
	 * @param key     the inclusive upper bound
	 * @return the index of the greatest id at or below `key`, or `-1`
	 */
	private static int floorIndex(@Nonnull int[] records, int from, int to, int key) {
		int low = from;
		int high = to - 1;
		int result = -1;
		while (low <= high) {
			final int mid = (low + high) >>> 1;
			if (Integer.compareUnsigned(records[mid], key) <= 0) {
				result = mid;
				low = mid + 1;
			} else {
				high = mid - 1;
			}
		}
		return result;
	}

	/**
	 * Narrows a slot known not to be a bitmap to the sorted array it must then be, refusing anything else rather than
	 * letting an unexpected shape propagate as a {@link ClassCastException} from an unrelated line.
	 *
	 * @param records the bucket's record set
	 * @return the record set as a sorted `int[]`
	 */
	@Nonnull
	static int[] asRecordArray(@Nonnull Object records) {
		if (records instanceof final int[] small) {
			return small;
		}
		throw new GenericEvitaInternalError(
			"An overflow bucket is either a sorted int[] or a TransactionalBitmap, never a " +
				records.getClass().getName() + "!"
		);
	}

	/**
	 * Narrows a slot known not to be an array to the bitmap it must then be, refusing anything else rather than
	 * letting an unexpected shape propagate as a {@link ClassCastException} from an unrelated line.
	 *
	 * @param records the bucket's record set
	 * @return the record set as a {@link TransactionalBitmap}
	 */
	@Nonnull
	private static TransactionalBitmap asBitmap(@Nonnull Object records) {
		if (records instanceof final TransactionalBitmap bitmap) {
			return bitmap;
		}
		throw new GenericEvitaInternalError(
			"An overflow bucket is either a sorted int[] or a TransactionalBitmap, never a " +
				records.getClass().getName() + "!"
		);
	}

}
