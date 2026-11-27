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

package io.evitadb.index.array;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.DATA_TYPE;

/**
 * This test verifies contract of {@link UnorderedLookup}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Tag(INDEXING)
@Tag(DATA_TYPE)
class UnorderedLookupTest {

	/**
	 * Checks how the constructor maps ascending record ids back to the original positions.
	 */
	@Test
	void shouldCreateLookupAndFindPositionsProperly() {
		final UnorderedLookup lookup = new UnorderedLookup(new int[]{4, 2, 3, 1});
		assertArrayEquals(new int[]{1, 2, 3, 4}, lookup.getRecordIds());
		assertArrayEquals(new int[]{3, 1, 2, 0}, lookup.getPositions());
	}

	/**
	 * Checks that the snapshot preserves the supplied record order.
	 */
	@Test
	void shouldCreateLookupAndReturnArrayUntouched() {
		final int[] inputArray = {4, 2, 3, 1};
		final UnorderedLookup tested = new UnorderedLookup(inputArray);
		assertArrayEquals(inputArray, tested.getArray());
	}

	@Test
	@DisplayName("primitive constructor matches a brute-force oracle for random distinct arrays")
	void shouldMatchOracleForRandomDistinctArrays() {
		final Random random = new Random(42);
		for (final int size : new int[] {0, 1, 2, 5, 17, 128, 1000}) {
			assertLookupMatchesOracle(distinctShuffledArray(size, random));
		}
	}

	@Test
	@DisplayName("constructor handles zero and negative record ids")
	void shouldHandleZeroAndNegativeRecordIds() {
		assertLookupMatchesOracle(new int[] {0, -5, 7, -1, 3, -100, 42});
	}

	/**
	 * Verifies every public read of a freshly-built {@link UnorderedLookup} against an independent brute-force oracle
	 * derived directly from the (distinct-valued) input array.
	 *
	 * @param input the distinct record ids in logical order
	 */
	private static void assertLookupMatchesOracle(@Nonnull int[] input) {
		final UnorderedLookup lookup = new UnorderedLookup(input);
		// getArray() returns the unordered array verbatim
		assertArrayEquals(input, lookup.getArray());
		// getRecordIds() is the input in ascending order
		final int[] expectedAscending = input.clone();
		Arrays.sort(expectedAscending);
		assertArrayEquals(expectedAscending, lookup.getRecordIds());
		final int[] positions = lookup.getPositions();
		assertEquals(input.length, positions.length);
		for (int i = 0; i < expectedAscending.length; i++) {
			final int recordId = expectedAscending[i];
			int expectedPosition = 0;
			while (input[expectedPosition] != recordId) {
				expectedPosition++;
			}
			assertEquals(expectedPosition, positions[i], "Position of record " + recordId);
		}
	}

	/**
	 * Builds an array of {@code size} distinct record ids spanning negative and positive values and shuffles it with
	 * the supplied (seeded) random generator, so the constructor's position mapping is exercised on an unordered input.
	 */
	@Nonnull
	private static int[] distinctShuffledArray(int size, @Nonnull Random random) {
		final Set<Integer> distinct = new LinkedHashSet<>();
		final int span = Math.max(1, size) << 2;
		while (distinct.size() < size) {
			distinct.add(random.nextInt(2 * span + 1) - span);
		}
		final int[] result = new int[size];
		int index = 0;
		for (final int value : distinct) {
			result[index++] = value;
		}
		// Fisher-Yates shuffle to remove any ordering bias from the set iteration order
		for (int i = result.length - 1; i > 0; i--) {
			final int j = random.nextInt(i + 1);
			final int tmp = result[i];
			result[i] = result[j];
			result[j] = tmp;
		}
		return result;
	}

}
