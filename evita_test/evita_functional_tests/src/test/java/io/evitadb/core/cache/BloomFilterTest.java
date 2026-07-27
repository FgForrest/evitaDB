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

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FILTER;

/**
 * This test verifies behaviour of {@link BloomFilter}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Tag(ENGINE)
@Tag(CACHE)
@Tag(FILTER)
class BloomFilterTest {

	@Disabled("This test verifies claim, that mod operation can be for power of two exchanged with faster bit shift: n % 2^i = n & (2^i - 1)")
	@Test
	void shouldVerifyFormula() {
		int acc = 0;
		final long start = System.nanoTime();
		for (long i = 0; i < 20_000_000_000L; i++) {
			acc += (int) (i % 8);
		}
		System.out.println(System.nanoTime() - start + ", " + acc);

		acc = 0;
		final long start2 = System.nanoTime();
		for (long i = 0; i < 20_000_000_000L; i++) {
			acc += (int) (i & (8 - 1));
		}
		System.out.println(System.nanoTime() - start2 + ", " + acc);
	}

}
