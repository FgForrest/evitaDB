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

package io.evitadb.core.cache;

import net.openhft.hashing.LongHashFunction;

import java.util.BitSet;

import static io.evitadb.utils.MemoryMeasuringConstants.*;

/**
 * Bloom filter is a <a href="https://en.wikipedia.org/wiki/Bloom_filter">data structure</a> that allows with less
 * overhead exactly say that certain value is not part of the data set and with high probability say that it might
 * be part of the data set (although here it may return false-positive answers).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class BloomFilter {
	/**
	 * Constant contains estimate of this class memory consumption.
	 */
	private static final int BASE_MEMORY_SIZE = 2 * OBJECT_HEADER_SIZE + REFERENCE_SIZE + 2 * INT_SIZE + ARRAY_BASE_SIZE + BYTE_SIZE;
	/**
	 * Constant represents number of hash functions used by this bloom filter. This value is used to compute the size
	 * of the bloom filter to achieve requested false-positive probability.
	 */
	private static final int HASH_FUNCTIONS_NUMBER = 5;
	/**
	 * The bitset that contains the data set values.
	 */
	private final BitSet bitSet;
	/**
	 * Contains size of the bitset to avoid frequent re-computations of calling {@link BitSet#size()}.
	 */
	private final int size;

	/**
	 * Method computes "estimated" memory consumption of Bloom filter based on usual memory requirements for used Java
	 * types. The result is not accurate, but it's the best effort estimate.
	 */
	public static int computeSizeInBytes(int expectedRecordCount, double reliability) {
		return BASE_MEMORY_SIZE + computeBitSize(expectedRecordCount, reliability);
	}

	/**
	 * Computes desired {@link BitSet} size in order to achieve requested `reliability` (i.e. avoiding false-positive
	 * answers) for desired `expectedRecordCount` that are expected to be stored in this dataset. The function uses
	 * also fixed number of hashes ({@link #HASH_FUNCTIONS_NUMBER}) that are used by this bloom filter.
	 *
	 * Computation taken from <a href="https://hur.st/bloomfilter/">Thomas Hurst</a>, thank you.
	 */
	static int computeBitSize(int expectedRecordCount, double reliability) {
		return Math.toIntExact(
			Math.round(
				Math.ceil(
					expectedRecordCount * -HASH_FUNCTIONS_NUMBER /
						Math.log(1 - Math.exp(Math.log(reliability) / HASH_FUNCTIONS_NUMBER))
				)
			)
		);
	}

	public BloomFilter(int expectedRecordCount, double reliability) {
		// initialize bitset with appropriate size for `expectedRecordCount` and `reliability`
		this.bitSet = new BitSet(computeBitSize(expectedRecordCount, reliability));
		// cache the size for further computations
		this.size = this.bitSet.size();
	}

	/**
	 * Method adds new value to the bloom filter.
	 */
	public void add(long value) {
		this.bitSet.set(Math.toIntExact(Math.abs(LongHashFunction.xx3().hashLong(value)) % this.size), true);
		this.bitSet.set(Math.toIntExact(Math.abs(LongHashFunction.murmur_3().hashLong(value)) % this.size), true);
		this.bitSet.set(Math.toIntExact(Math.abs(LongHashFunction.farmNa().hashLong(value)) % this.size), true);
		this.bitSet.set(Math.toIntExact(Math.abs(LongHashFunction.city_1_1().hashLong(value)) % this.size), true);
		this.bitSet.set(Math.toIntExact(Math.abs(LongHashFunction.metro().hashLong(value)) % this.size), true);
	}

	/**
	 * Method returns whether the value has been added to this bloom filter with 100% accuracy.
	 */
	public boolean isNotPresent(long value) {
		return !mightBePresent(value);
	}

	/**
	 * Method returns guess (with requested `reliability` used in the constructor of this bloom filter) whether the value
	 * has been ever added to this bloom filter instance.
	 */
	public boolean mightBePresent(long value) {
		return this.bitSet.get(Math.toIntExact(Math.abs(LongHashFunction.xx3().hashLong(value)) % this.size)) &&
			this.bitSet.get(Math.toIntExact(Math.abs(LongHashFunction.murmur_3().hashLong(value)) % this.size)) &&
			this.bitSet.get(Math.toIntExact(Math.abs(LongHashFunction.farmNa().hashLong(value)) % this.size)) &&
			this.bitSet.get(Math.toIntExact(Math.abs(LongHashFunction.city_1_1().hashLong(value)) % this.size)) &&
			this.bitSet.get(Math.toIntExact(Math.abs(LongHashFunction.metro().hashLong(value)) % this.size));
	}

	/**
	 * Returns number of bits in the internal {@link BitSet} field (the size of the bloom filter).
	 */
	public int size() {
		return this.size;
	}

	/**
	 * Method returns "estimated" memory consumption of Bloom filter based on usual memory requirements for used Java
	 * types. The result is not accurate, but it's the best effort estimate.
	 */
	public int getSizeInBytes() {
		return BASE_MEMORY_SIZE + this.size;
	}

}
