/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.cache;

import io.evitadb.index.array.CompositeLongArray;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.text.DecimalFormat;
import java.util.PrimitiveIterator.OfLong;
import java.util.Random;

/**
 * This test verifies behaviour of {@link BloomFilter}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class BloomFilterTest {
	private static final long MAX_LONG = 10_000_000;
	private static final int COUNT = 1_000_000;
	private final Random rnd = new Random();
	private final CompositeLongArray randoms = new CompositeLongArray();
	private final BloomFilter bloomFilter = new BloomFilter(COUNT, 0.00001);

	@Disabled("Verification of bloom filter implementation test, too long to execute every time")
	@Test
	void shouldFillUpBloomFilter() {
		int generated = 0;
		do {
			final long recordId = nextRecordId();
			if (!randoms.contains(recordId)) {
				randoms.add(recordId);
				bloomFilter.add(recordId);
				generated++;
			}
		} while (generated < COUNT);

		final long start = System.nanoTime();
		int falseNegatives = 0;
		final OfLong it = randoms.iterator();
		while (it.hasNext()) {
			long presentValue = it.next();
			if (bloomFilter.isNotPresent(presentValue)) {
				falseNegatives++;
			}
		}

		int falsePositives = 0;
		for (int i = 0; i < MAX_LONG; i++) {
			final long randomValue = nextRecordId();
			if (bloomFilter.mightBePresent(randomValue)) {
				if (!randoms.contains(randomValue)) {
					falsePositives++;
				}
			}
		}

		System.out.println("Finished in: " + (System.nanoTime() - start));
		System.out.println("False negatives: " + falseNegatives);
		System.out.println("False positives: " + falsePositives + " out of " + MAX_LONG + " which is " + new DecimalFormat("0.################").format((double) falsePositives / (double) MAX_LONG));
	}

	@Disabled("This test verifies claim, that mod operation can be for power of two exchanged with faster bit shift: n % 2^i = n & (2^i - 1)")
	@Test
	void shouldVerifyFormula() {
		int acc = 0;
		final long start = System.nanoTime();
		for (long i = 0; i < 20_000_000_000L; i++) {
			acc += i % 8;
		}
		System.out.println(System.nanoTime() - start + ", " + acc);

		acc = 0;
		final long start2 = System.nanoTime();
		for (long i = 0; i < 20_000_000_000L; i++) {
			acc += i & (8 - 1);
		}
		System.out.println(System.nanoTime() - start2 + ", " + acc);
	}

	private long nextRecordId() {
		return Math.abs(rnd.nextLong()) % MAX_LONG;
	}
}