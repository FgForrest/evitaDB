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

package io.evitadb.core.query.filter.translator.fulltext;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.ImpactView;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.NotThreadSafe;
import java.util.Objects;

/**
 * A field's bounded, worst-first term collector. Entries and their borrowed views may change on the next offer;
 * callers enumerate the heap only between scans. Distance, descending frequency and term define the order.
 * Repeated offers of a retained stem must carry the same distance; wider bands exclude earlier-band stems.
 */
@NotThreadSafe
final class FulltextTermHeap {
	/** Retained entries, with the worst at the root. */
	@Nonnull private final Entry[] heap;
	/** Occupied heap slots. */
	private int size;

	/**
	 * Creates a bounded field collector.
	 * @param capacity maximum retained entries, at least one
	 */
	FulltextTermHeap(int capacity) {
		Assert.isPremiseValid(capacity > 0, "A fulltext term collector needs positive capacity.");
		this.heap = new Entry[capacity];
	}

	/**
	 * Returns the occupied slot count.
	 * @return retained entries, bounded by the constructor capacity
	 */
	int size() {
		return this.size;
	}

	/**
	 * Returns a borrowed entry in heap order, valid until the next offer.
	 * @param index occupied heap slot
	 * @return the current entry at that slot
	 */
	@Nonnull
	Entry get(int index) {
		return this.heap[Objects.checkIndex(index, this.size)];
	}

	/**
	 * Keeps a competitive hit, reusing the worst slot on replacement.
	 * @param term dictionary stem
	 * @param postings borrowed posting view
	 * @param impacts aligned borrowed impacts
	 * @param distance accepted distance band
	 * @param frequency document frequency in this field
	 * @throws GenericEvitaInternalError when a retained stem is offered again at another distance
	 */
	void offer(@Nonnull String term, @Nonnull Bitmap postings, @Nonnull ImpactView impacts,
		int distance, int frequency) {
		final int hash = term.hashCode();
		// At most fifty retained slots: cached hashes avoid equals for almost every nonduplicate.
		for (int i = 0; i < this.size; i++) {
			final Entry existing = this.heap[i];
			if (existing.termHash == hash && existing.term.equals(term)) {
				if (distance != existing.distance) {
					throw new GenericEvitaInternalError("Fulltext term `" + term +
						"` was offered in different distance bands: " + existing.distance + " and " + distance + ".");
				}
				return;
			}
		}
		if (this.size < this.heap.length) {
			final Entry entry = new Entry(term, postings, impacts, distance, frequency);
			this.heap[this.size] = entry;
			siftUp(this.size++);
		} else {
			final Entry worst = this.heap[0];
			if (compare(distance, frequency, term, worst.distance, worst.frequency, worst.term) >= 0) {
				return;
			}
			worst.term = term;
			worst.termHash = hash;
			worst.postings = postings;
			worst.impacts = impacts;
			worst.distance = distance;
			worst.frequency = frequency;
			siftDown(0);
		}
	}

	/**
	 * Tests a borrowed lane term before freezing characters or acquiring posting/impact views. A term already
	 * retained needs no second offer: within one field its postings and impacts do not depend on the variant or
	 * surface form that reached it.
	 * @param term decoded borrowed stem
	 * @param distance accepted edit band
	 * @param frequency current field's document frequency
	 * @return true only when a subsequent offer with these values would retain a new or replacement entry
	 * @throws GenericEvitaInternalError when a retained duplicate carries a different distance
	 */
	boolean canOffer(@Nonnull CharSequence term, int distance, int frequency) {
		for (int i = 0; i < this.size; i++) {
			final Entry existing = this.heap[i];
			if (CharSequence.compare(term, existing.term) == 0) {
				if (distance != existing.distance) {
					throw new GenericEvitaInternalError(
						"A retained lane stem was offered in different distance bands.");
				}
				return false;
			}
		}
		if (this.size < this.heap.length) {
			return true;
		}
		final Entry worst = this.heap[0];
		final int byDistance = Integer.compare(distance, worst.distance);
		final int byFrequency = Integer.compare(worst.frequency, frequency);
		return byDistance < 0 || byDistance == 0 && (byFrequency < 0 ||
			byFrequency == 0 && CharSequence.compare(term, worst.term) < 0);
	}

	/**
	 * Compares a hit without allocating an entry for it.
	 * @param leftDistance first distance
	 * @param leftFrequency first frequency
	 * @param leftTerm first stem
	 * @param rightDistance second distance
	 * @param rightFrequency second frequency
	 * @param rightTerm second stem
	 * @return negative when the first hit ranks better
	 */
	static int compare(int leftDistance, int leftFrequency, @Nonnull String leftTerm,
		int rightDistance, int rightFrequency, @Nonnull String rightTerm) {
		final int distance = Integer.compare(leftDistance, rightDistance);
		if (distance != 0) {
			return distance;
		}
		final int frequency = Integer.compare(rightFrequency, leftFrequency);
		return frequency == 0 ? leftTerm.compareTo(rightTerm) : frequency;
	}

	/**
	 * Restores worst-first order after appending an entry.
	 * @param start inserted slot
	 */
	private void siftUp(int start) {
		int position = start;
		while (position > 0) {
			final int parent = (position - 1) >>> 1;
			if (compareEntries(this.heap[position], this.heap[parent]) <= 0) {
				break;
			}
			swap(position, parent);
			position = parent;
		}
	}

	/**
	 * Restores worst-first order after replacing the root.
	 * @param start changed slot
	 */
	private void siftDown(int start) {
		int position = start;
		while (2 * position + 1 < this.size) {
			final int left = 2 * position + 1;
			final int child = left + 1 < this.size && compareEntries(this.heap[left + 1], this.heap[left]) > 0
				? left + 1 : left;
			if (compareEntries(this.heap[position], this.heap[child]) >= 0) {
				break;
			}
			swap(position, child);
			position = child;
		}
	}

	/**
	 * Exchanges occupied slots.
	 * @param left first slot
	 * @param right second slot
	 */
	private void swap(int left, int right) {
		final Entry saved = this.heap[left];
		this.heap[left] = this.heap[right];
		this.heap[right] = saved;
	}

	/**
	 * Compares retained entries in the same order as incoming hits.
	 * @param left first entry
	 * @param right second entry
	 * @return negative when the first entry ranks better
	 */
	private static int compareEntries(@Nonnull Entry left, @Nonnull Entry right) {
		return compare(left.distance, left.frequency, left.term, right.distance, right.frequency, right.term);
	}

	/** Mutable retained slot; its payload is borrowed from the dictionary. */
	@NotThreadSafe
	static final class Entry {
		/** Stem occupying the slot. */
		@Nonnull private String term;
		/** Its posting view. */
		@Nonnull private Bitmap postings;
		/** Its aligned impacts. */
		@Nonnull private ImpactView impacts;
		/** Its accepted distance. */
		private int distance;
		/** Its field document frequency. */
		private int frequency;
		/** Cached hash of the current stem for allocation-free duplicate detection. */
		private int termHash;

		/**
		 * Starts one retained slot.
		 * @param term dictionary stem
		 * @param postings borrowed postings
		 * @param impacts aligned impacts
		 * @param distance accepted band
		 * @param frequency field frequency
		 */
		private Entry(@Nonnull String term, @Nonnull Bitmap postings, @Nonnull ImpactView impacts,
			int distance, int frequency) {
			this.term = term;
			this.termHash = term.hashCode();
			this.postings = postings;
			this.impacts = impacts;
			this.distance = distance;
			this.frequency = frequency;
		}

		/**
		 * Returns the current stem in this retained slot.
		 * @return current stem in this retained slot
		 */
		@Nonnull
		String term() {
			return this.term;
		}

		/**
		 * Returns the borrowed posting view of the current stem.
		 * @return borrowed posting view of the current stem
		 */
		@Nonnull
		Bitmap postings() {
			return this.postings;
		}

		/**
		 * Returns the aligned borrowed impacts of the current stem.
		 * @return aligned borrowed impacts of the current stem
		 */
		@Nonnull
		ImpactView impacts() {
			return this.impacts;
		}

		/**
		 * Returns the accepted distance of the current stem.
		 * @return accepted distance of the current stem
		 */
		int distance() {
			return this.distance;
		}

		/**
		 * Returns the field document frequency of the current stem.
		 * @return field document frequency of the current stem
		 */
		int frequency() {
			return this.frequency;
		}
	}
}
