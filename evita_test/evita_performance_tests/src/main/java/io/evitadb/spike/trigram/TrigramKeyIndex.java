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

import io.evitadb.exception.GenericEvitaInternalError;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * The `trigram -> posting` key map the B2 sweep picked: a linear-probing hash table over primitive `long` keys with
 * a power-of-two capacity, holding its postings in a parallel `Object[]`. B2 measured ~1.3 ns per lookup at a load
 * factor of 0.75, against ~40 ns for the boxed `HashMap<Long, ...>` the brief §10 warns about.
 *
 * Empty slots are marked with `-1`, which no packed trigram can ever be - {@link TrigramCodec#pack} fills bits
 * `0..62` only, so every key is non-negative. That is why no occupancy bitset is needed; `0` would not have worked,
 * because a trigram of three `NUL` code points packs to exactly `0` and `NUL` is a legal character in an attribute
 * value.
 *
 * Slots are addressed by Fibonacci hashing - multiply by the 64-bit golden ratio and take the **high** bits, which
 * is where that product's entropy ends up. Trigram keys carry their third code point in the top bits and would
 * otherwise cluster badly under a plain mask of the low ones.
 *
 * The table is sized once from a known key count and **never resizes**: every consumer in this package builds it in
 * one pass over an already-inverted corpus. A production structure would be a persisted long-keyed tree instead,
 * which is why the growth path is deliberately absent rather than merely unimplemented.
 *
 * Extracted from `TrigramPostingStoreSpike` (B2) so that the B4 query pipeline uses the very structure B2 measured
 * rather than a copy of it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class TrigramKeyIndex {

	/**
	 * Marks an unoccupied slot; no packed trigram is ever negative.
	 */
	private static final long EMPTY_KEY = -1L;

	/**
	 * 2^64 divided by the golden ratio, rounded to an odd number - the Fibonacci-hashing multiplier.
	 */
	private static final long GOLDEN_RATIO = 0x9E3779B97F4A7C15L;

	private final long[] keys;
	private final Object[] values;
	private final int mask;
	private final int shift;
	private int size;

	/**
	 * @param expectedKeys  how many keys will be inserted
	 * @param maxLoadFactor the load factor the capacity is sized to stay at or below
	 */
	TrigramKeyIndex(int expectedKeys, double maxLoadFactor) {
		int capacity = 2;
		while (capacity * maxLoadFactor < expectedKeys) {
			capacity <<= 1;
		}
		this.keys = new long[capacity];
		this.values = new Object[capacity];
		this.mask = capacity - 1;
		this.shift = 64 - Integer.numberOfTrailingZeros(capacity);
		Arrays.fill(this.keys, EMPTY_KEY);
	}

	/**
	 * @return how many slots the table holds
	 */
	int capacity() {
		return this.keys.length;
	}

	/**
	 * @return how many keys the table holds
	 */
	int size() {
		return this.size;
	}

	/**
	 * Inserts one key, or replaces the posting of a key already present.
	 *
	 * @param key   packed trigram, which is never negative
	 * @param value its posting
	 */
	void put(long key, @Nonnull Object value) {
		if (key < 0L) {
			throw new GenericEvitaInternalError(
				"Key " + key + " is negative and collides with the empty-slot sentinel - a packed trigram " +
					"can never be negative, so the key did not come from TrigramCodec!",
				"Trigram key collides with the empty-slot sentinel!"
			);
		}
		if (this.size == this.keys.length) {
			throw new GenericEvitaInternalError(
				"The table is full - it is sized once from the known key count and never resizes!",
				"The trigram key table is full!"
			);
		}
		int slot = slotOf(key);
		while (true) {
			final long candidate = this.keys[slot];
			if (candidate == EMPTY_KEY) {
				this.keys[slot] = key;
				this.values[slot] = value;
				this.size++;
				return;
			}
			if (candidate == key) {
				this.values[slot] = value;
				return;
			}
			slot = (slot + 1) & this.mask;
		}
	}

	/**
	 * @param key packed trigram to look up
	 * @return its posting, or `null` when the key is absent
	 */
	@Nullable
	Object get(long key) {
		int slot = slotOf(key);
		while (true) {
			final long candidate = this.keys[slot];
			if (candidate == key) {
				return this.values[slot];
			}
			if (candidate == EMPTY_KEY) {
				return null;
			}
			slot = (slot + 1) & this.mask;
		}
	}

	/**
	 * @param key packed trigram
	 * @return the slot the key hashes to
	 */
	private int slotOf(long key) {
		return (int) ((key * GOLDEN_RATIO) >>> this.shift);
	}
}
