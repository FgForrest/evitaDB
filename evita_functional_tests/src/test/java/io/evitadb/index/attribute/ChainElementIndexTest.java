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

package io.evitadb.index.attribute;

import io.evitadb.dataType.Predecessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies the contract of {@link ChainElementIndex} implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
class ChainElementIndexTest {
	private static final int[] EXPECTED_CHAIN = {1, 2, 3, 4, 5};
	private static final Map<Integer, Predecessor> PREDECESSOR_MAP = Map.of(
		1, new Predecessor(),
		2, new Predecessor(1),
		3, new Predecessor(2),
		4, new Predecessor(3),
		5, new Predecessor(4)
	);

	private final ChainElementIndex index = new ChainElementIndex();

	@DisplayName("Create consistent chain when new items are added in different orders")
	@ParameterizedTest
	@MethodSource("createPermutationsForPredecessors")
	void shouldTryAddingInDifferentOrders(int[] order) {
		for (int pk : order) {
			final Predecessor predecessor = PREDECESSOR_MAP.get(pk);
			System.out.println("Adding " + pk + " with predecessor " + predecessor + ".");
			index.upsertPredecessor(pk, predecessor);
		}

		assertTrue(index.isConsistent());
		assertArrayEquals(EXPECTED_CHAIN, index.getChainFormula().compute().getArray());
	}

	@DisplayName("Create consistent chain when randomly reordered")
	@ParameterizedTest
	@MethodSource("createPermutationsForPredecessors")
	void shouldTryReordering(int[] order) {
		// fill the index initially with the expected chain
		for (int pk : EXPECTED_CHAIN) {
			index.upsertPredecessor(pk, PREDECESSOR_MAP.get(pk));
		}
		// now reorder randomly
		for (int i = 0; i < order.length; i++) {
			int pk = order[i];
			final Predecessor predecessor = i == 0 ? new Predecessor() : new Predecessor(order[i - 1]);
			System.out.println("Adding " + pk + " with predecessor " + predecessor + ".");
			index.upsertPredecessor(pk, predecessor);
		}

		assertTrue(index.isConsistent(), "Index is inconsistent.");
		assertArrayEquals(order, index.getChainFormula().compute().getArray());
	}

	@DisplayName("Create consistent chain when circular dependency is introduced and broken")
	@Test
	void shouldBreakCircularDependency() {
		// fill the index initially with the expected chain
		for (int pk : EXPECTED_CHAIN) {
			index.upsertPredecessor(pk, PREDECESSOR_MAP.get(pk));
		}
		// now reorder randomly
		index.upsertPredecessor(1, new Predecessor(3));
		index.upsertPredecessor(2, new Predecessor(4));
		index.upsertPredecessor(5, new Predecessor(2));
		index.upsertPredecessor(4, new Predecessor(1));
		index.upsertPredecessor(3, new Predecessor());

		assertTrue(index.isConsistent(), "Index is inconsistent.");
		assertArrayEquals(new int[] {3, 1, 4, 2, 5}, index.getChainFormula().compute().getArray());
	}

	@DisplayName("When adding a new element to the middle of the chain and then correcting it, the index should be consistent")
	@Test
	void shouldIntroduceSplitChainDuringIndexingAndThenCorrectIt() {
		// fill the index initially with the expected chain
		for (int pk : EXPECTED_CHAIN) {
			index.upsertPredecessor(pk, PREDECESSOR_MAP.get(pk));
		}
		// now reorder randomly
		index.upsertPredecessor(6, new Predecessor(3));
		index.upsertPredecessor(4, new Predecessor(6));

		assertTrue(index.isConsistent(), "Index is inconsistent.");
		assertArrayEquals(new int[] {1, 2, 3, 6, 4, 5}, index.getChainFormula().compute().getArray());
	}

	@DisplayName("When introducing a split chain, the longer chains should be favoured")
	@Test
	void shouldIntroduceReconnectSplitChainsFavouringLongerOne() {
		// fill the index initially with the expected chain
		for (int pk : EXPECTED_CHAIN) {
			index.upsertPredecessor(pk, PREDECESSOR_MAP.get(pk));
		}

		// now reorder randomly
		index.upsertPredecessor(6, new Predecessor(3));
		index.upsertPredecessor(7, new Predecessor(3));
		index.upsertPredecessor(8, new Predecessor(7));

		assertFalse(index.isConsistent(), "Index is inconsistent.");
		assertArrayEquals(new int[] {1, 2, 3, 4, 5, 7, 8, 6}, index.getChainFormula().compute().getArray());
	}

	/**
	 * Creates all permutations of the expected chain.
	 * @return stream of arguments for the test method
	 */
	@Nonnull
	static Stream<Arguments> createPermutationsForPredecessors() {
		final List<int[]> result = new LinkedList<>();
		permute(Arrays.copyOf(EXPECTED_CHAIN, EXPECTED_CHAIN.length), 0, result);

		return result.stream()
				.map(Arguments::of);
	}

	/**
	 * Creates all permutations of the given array. The permutations are added to the result list.
	 * @param nums array to permute
	 * @param start index of the first element to permute
	 * @param result list to add the permutations to
	 */
	private static void permute(@Nonnull int[] nums, int start, @Nonnull List<int[]> result) {
		if (start == nums.length - 1) {
			result.add(Arrays.copyOf(nums, nums.length));
			return;
		}

		for (int i = start; i < nums.length; i++) {
			swap(nums, start, i);
			permute(nums, start + 1, result);
			swap(nums, start, i);  // backtrack
		}
	}

	/**
	 * Swaps two elements in the given array.
	 * @param nums array to swap elements in
	 * @param i index of the first element
	 * @param j index of the second element
	 */
	private static void swap(@Nonnull int[] nums, int i, int j) {
		int temp = nums[i];
		nums[i] = nums[j];
		nums[j] = temp;
	}

}