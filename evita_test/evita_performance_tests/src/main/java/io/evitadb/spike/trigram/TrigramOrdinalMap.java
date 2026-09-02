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

package io.evitadb.spike.trigram;

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * Build-time `trigram → ordinal` map. It exists so that inverting the corpus never boxes a key: on a corpus
 * the size of the production CMS one, the boxed `HashMap` a naive inversion would reach for makes the build's
 * own peak heap the run's limiting factor - and in B2 it is one of the things being measured, so building the
 * measurement with it would be circular.
 *
 * Shares {@link TrigramKeyIndex}'s sentinel and hashing, but keeps primitive `int` values and resizes,
 * because the key count is not known before the corpus has been read.
 *
 * Extracted from `TrigramPostingStoreSpike` (B2) so the B4 index builder inverts its corpus the same way.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class TrigramOrdinalMap {

	/**
	 * Marks an unoccupied slot; no packed trigram is ever negative.
	 */
	private static final long EMPTY_KEY = -1L;

	/**
	 * 2^64 divided by the golden ratio, rounded to an odd number - the Fibonacci-hashing multiplier.
	 */
	private static final long GOLDEN_RATIO = 0x9E3779B97F4A7C15L;

	/**
	 * Load factor the table grows at.
	 */
	private static final double MAX_LOAD_FACTOR = 0.6;

	private long[] keys;
	private int[] ordinals;
	private int mask;
	private int shift;
	private int size;
	private int growAt;

	/**
	 * @param initialCapacity initial number of slots, rounded up to a power of two
	 */
	TrigramOrdinalMap(int initialCapacity) {
		int capacity = 2;
		while (capacity < initialCapacity) {
			capacity <<= 1;
		}
		allocate(capacity);
	}

	/**
	 * Returns the ordinal already assigned to the key, or assigns it the offered one.
	 *
	 * @param key             packed trigram, which is never negative
	 * @param ordinalIfAbsent ordinal to assign when the key is new
	 * @return the key's ordinal - equal to `ordinalIfAbsent` exactly when the key was new
	 */
	int putIfAbsent(long key, int ordinalIfAbsent) {
		int slot = slotOf(key);
		while (true) {
			final long candidate = this.keys[slot];
			if (candidate == key) {
				return this.ordinals[slot];
			}
			if (candidate == EMPTY_KEY) {
				this.keys[slot] = key;
				this.ordinals[slot] = ordinalIfAbsent;
				this.size++;
				if (this.size >= this.growAt) {
					grow();
				}
				return ordinalIfAbsent;
			}
			slot = (slot + 1) & this.mask;
		}
	}

	/**
	 * @param key packed trigram that must be present
	 * @return its ordinal
	 */
	int get(long key) {
		int slot = slotOf(key);
		while (true) {
			final long candidate = this.keys[slot];
			if (candidate == key) {
				return this.ordinals[slot];
			}
			if (candidate == EMPTY_KEY) {
				throw new IllegalStateException(
					"Trigram key " + key + " was counted in the first inversion pass but is missing in the " +
						"second - the ordinal map lost a key!"
				);
			}
			slot = (slot + 1) & this.mask;
		}
	}

	/**
	 * Doubles the table and re-inserts every entry.
	 */
	private void grow() {
		final long[] previousKeys = this.keys;
		final int[] previousOrdinals = this.ordinals;
		allocate(previousKeys.length << 1);
		this.size = 0;
		for (int i = 0; i < previousKeys.length; i++) {
			if (previousKeys[i] != EMPTY_KEY) {
				putIfAbsent(previousKeys[i], previousOrdinals[i]);
			}
		}
	}

	/**
	 * Allocates a table of the given power-of-two capacity.
	 *
	 * @param capacity number of slots
	 */
	private void allocate(int capacity) {
		this.keys = new long[capacity];
		this.ordinals = new int[capacity];
		this.mask = capacity - 1;
		this.shift = 64 - Integer.numberOfTrailingZeros(capacity);
		this.growAt = (int) (capacity * MAX_LOAD_FACTOR);
		Arrays.fill(this.keys, EMPTY_KEY);
	}

	/**
	 * @param key packed trigram
	 * @return the slot the key hashes to
	 */
	private int slotOf(long key) {
		return (int) ((key * GOLDEN_RATIO) >>> this.shift);
	}
}
