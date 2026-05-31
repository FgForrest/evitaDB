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

package io.evitadb.index.attribute;

import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.dataType.ChainableType;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyReport;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyState;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.attribute.ChainIndex.ChainElementState;
import io.evitadb.index.attribute.ChainIndex.ElementState;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexStoragePart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.ATTRIBUTE;

/**
 * This test verifies the contract of {@link ChainIndex} implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class ChainIndexTest {
	private static final int[] EXPECTED_CHAIN = {1, 2, 3, 4, 5};
	private static final Map<Integer, Predecessor> PREDECESSOR_MAP = Map.of(
		1, new Predecessor(),
		2, new Predecessor(1),
		3, new Predecessor(2),
		4, new Predecessor(3),
		5, new Predecessor(4)
	);

	private final ChainIndex index = new ChainIndex(new AttributeIndexKey(null, "a", null));

	@DisplayName("Create consistent chain when new items are added in different orders")
	@ParameterizedTest
	@MethodSource("createPermutationsForPredecessors")
	void shouldTryAddingInDifferentOrders(int[] order) {
		for (int pk : order) {
			final Predecessor predecessor = PREDECESSOR_MAP.get(pk);
			this.index.upsertPredecessor(predecessor, pk);
		}

		assertTrue(this.index.isConsistent());
		assertArrayEquals(EXPECTED_CHAIN, this.index.getUnorderedLookup().getArray());
	}

	@DisplayName("Create consistent chain when randomly reordered")
	@ParameterizedTest
	@MethodSource("createPermutationsForPredecessors")
	void shouldTryReordering(int[] order) {
		// fill the index initially with the expected chain
		for (int pk : EXPECTED_CHAIN) {
			this.index.upsertPredecessor(PREDECESSOR_MAP.get(pk), pk);
		}
		// now reorder randomly
		for (int i = 0; i < order.length; i++) {
			int pk = order[i];
			final Predecessor predecessor = i == 0 ? new Predecessor() : new Predecessor(order[i - 1]);
			this.index.upsertPredecessor(predecessor, pk);
		}

		assertTrue(this.index.isConsistent(), "Index is inconsistent.");
		assertArrayEquals(order, this.index.getUnorderedLookup().getArray());
	}

	@DisplayName("Create consistent chain when randomly removing elements and returning back")
	@ParameterizedTest
	@MethodSource("createPermutationsForPredecessors")
	void shouldTryRemovingSingleElementsAndReturnItBack(int[] order) {
		// fill the index initially with the expected chain
		for (int pk : EXPECTED_CHAIN) {
			this.index.upsertPredecessor(PREDECESSOR_MAP.get(pk), pk);
		}
		for (int count = 1; count <= order.length; count++) {
			// remove the first count elements
			for (int i = 0; i < count; i++) {
				this.index.removePredecessor(order[i]);
			}
			// now return them back in
			for (int i = 0; i < count; i++) {
				this.index.upsertPredecessor(PREDECESSOR_MAP.get(order[i]), order[i]);
			}

			assertTrue(this.index.isConsistent(), "Index is inconsistent.");
			assertArrayEquals(EXPECTED_CHAIN, this.index.getUnorderedLookup().getArray());
		}
	}

	@DisplayName("Create consistent chain when circular dependency is introduced and broken")
	@Test
	void shouldBreakCircularDependency() {
		// fill the index initially with the expected chain
		for (int pk : EXPECTED_CHAIN) {
			this.index.upsertPredecessor(PREDECESSOR_MAP.get(pk), pk);
		}
		// now reorder randomly
		this.index.upsertPredecessor(new Predecessor(3), 1);
		this.index.upsertPredecessor(new Predecessor(4), 2);
		this.index.upsertPredecessor(new Predecessor(2), 5);
		this.index.upsertPredecessor(new Predecessor(1), 4);

		assertFalse(this.index.isConsistent(), "Index is inconsistent.");
		assertArrayEquals(new int[] {5, 2, 3, 1, 4}, this.index.getUnorderedLookup().getArray());
		assertEquals(
			"""
			ChainIndex:
			   - chains:
			      - [2, 3, 1, 4]
			      - [5]
			   - elementStates:
			      - 1: SUCCESSOR of 3 🔗 2
			      - 2: CIRCULAR of 4 🔗 2
			      - 3: SUCCESSOR of 2 🔗 2
			      - 4: SUCCESSOR of 1 🔗 2
			      - 5: SUCCESSOR of 2 🔗 5""",
			this.index.toString()
		);

		this.index.upsertPredecessor(new Predecessor(), 3);

		assertTrue(this.index.isConsistent(), "Index is inconsistent.");
		assertArrayEquals(new int[] {3, 1, 4, 2, 5}, this.index.getUnorderedLookup().getArray());
	}

	@DisplayName("When adding a new element to the middle of the chain and then correcting it, the index should be consistent")
	@Test
	void shouldIntroduceSplitChainDuringIndexingAndThenCorrectIt() {
		// fill the index initially with the expected chain
		for (int pk : EXPECTED_CHAIN) {
			this.index.upsertPredecessor(PREDECESSOR_MAP.get(pk), pk);
		}
		// now reorder randomly
		this.index.upsertPredecessor(new Predecessor(3), 6);
		this.index.upsertPredecessor(new Predecessor(6), 4);

		assertTrue(this.index.isConsistent(), "Index is inconsistent.");
		assertArrayEquals(new int[] {1, 2, 3, 6, 4, 5}, this.index.getUnorderedLookup().getArray());
		assertEquals(
			"""
			ChainIndex:
			   - chains:
			      - [1, 2, 3, 6, 4, 5]
			   - elementStates:
			      - 1: HEAD 🔗 1
			      - 2: SUCCESSOR of 1 🔗 1
			      - 3: SUCCESSOR of 2 🔗 1
			      - 4: SUCCESSOR of 6 🔗 1
			      - 5: SUCCESSOR of 4 🔗 1
			      - 6: SUCCESSOR of 3 🔗 1""",
			this.index.toString()
		);
	}

	@DisplayName("When introducing a split chain, the longer chains should be favoured")
	@Test
	void shouldIntroduceReconnectSplitChainsFavouringLongerOne() {
		// fill the index initially with the expected chain
		for (int pk : EXPECTED_CHAIN) {
			this.index.upsertPredecessor(PREDECESSOR_MAP.get(pk), pk);
		}

		// now reorder randomly
		this.index.upsertPredecessor(new Predecessor(3), 6);
		this.index.upsertPredecessor(new Predecessor(3), 7);
		this.index.upsertPredecessor(new Predecessor(7), 8);

		assertFalse(this.index.isConsistent(), "Index is inconsistent.");
		assertArrayEquals(new int[] {1, 2, 3, 4, 5, 7, 8, 6}, this.index.getUnorderedLookup().getArray());
		assertEquals(
			"""
			ChainIndex:
			   - chains:
			      - [1, 2, 3, 4, 5]
			      - [6]
			      - [7, 8]
			   - elementStates:
			      - 1: HEAD 🔗 1
			      - 2: SUCCESSOR of 1 🔗 1
			      - 3: SUCCESSOR of 2 🔗 1
			      - 4: SUCCESSOR of 3 🔗 1
			      - 5: SUCCESSOR of 4 🔗 1
			      - 6: SUCCESSOR of 3 🔗 6
			      - 7: SUCCESSOR of 3 🔗 7
			      - 8: SUCCESSOR of 7 🔗 7""",
			this.index.toString()
		);
	}

	@DisplayName("When elements are removed the chains are properly collapsed")
	@Test
	void shouldCollapseChainsOnElementRemoval() {
		int[] initialState = {12, 7, 6, 2, 13, 5, 17, 1, 9};
		for (int i = 0; i < initialState.length; i++) {
			int pk = initialState[i];
			final Predecessor predecessor = i == 0 ? new Predecessor() : new Predecessor(initialState[i - 1]);
			this.index.upsertPredecessor(predecessor, pk);
		}

		assertTrue(this.index.isConsistent());

		this.index.upsertPredecessor(new Predecessor(12), 6);
		this.index.upsertPredecessor(new Predecessor(6), 5);
		this.index.removePredecessor(1);
		this.index.upsertPredecessor(new Predecessor(5), 13);
		this.index.upsertPredecessor(new Predecessor(13), 7);
		this.index.removePredecessor(17);
		this.index.upsertPredecessor(new Predecessor(7), 9);
		this.index.upsertPredecessor(new Predecessor(9), 19);
		this.index.upsertPredecessor(new Predecessor(19), 3);
		this.index.upsertPredecessor(new Predecessor(3), 21);
		this.index.removePredecessor(2);

		assertTrue(this.index.isConsistent());
	}

	@Test
	void shouldExecuteOperationsInTransactionAndStayConsistent() {
		int[] initialState = {23, 26, 8, 3, 2, 4, 7, 6, 9, 10, 5, 11};
		for (int i = 0; i < initialState.length; i++) {
			int pk = initialState[i];
			final Predecessor predecessor = i == 0 ? new Predecessor() : new Predecessor(initialState[i - 1]);
			this.index.upsertPredecessor(predecessor, pk);
		}

		assertStateAfterCommit(
			this.index,
			original -> {
				original.upsertPredecessor(new Predecessor(-1), 8);
				original.upsertPredecessor(new Predecessor(8), 2);
				original.upsertPredecessor(new Predecessor(2), 23);
				original.removePredecessor(11);
				original.upsertPredecessor(new Predecessor(23), 4);
				original.upsertPredecessor(new Predecessor(4), 26);
				original.removePredecessor(9);
				original.upsertPredecessor(new Predecessor(26), 3);
				original.upsertPredecessor(new Predecessor(3), 7);
				original.upsertPredecessor(new Predecessor(7), 10);
				original.removePredecessor(6);
				original.upsertPredecessor(new Predecessor(10), 5);
				original.upsertPredecessor(new Predecessor(5), 24);
				original.upsertPredecessor(new Predecessor(24), 19);

				assertTrue(original.isConsistent());
			},
			(chainIndex, chainIndex2) -> {
				assertTrue(chainIndex.isConsistent());
				assertTrue(chainIndex2.isConsistent());
			}
		);
	}

	@Test
	void shouldGenerateConsistencyReport() {
		shouldExecuteOperationsInTransactionAndStayConsistent();

		assertEquals(
			"""
			## Chains

				- 23, 26, 8, 3, 2, 4, 7, 6, 9, 10, 5, 11
			
			## No errors detected.""",
			this.index.getConsistencyReport().report()
		);
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

	/**
	 * Populates the shared `index` field with the standard 1-5 chain.
	 */
	private void populateStandardChain() {
		for (int pk : EXPECTED_CHAIN) {
			ChainIndexTest.this.index.upsertPredecessor(PREDECESSOR_MAP.get(pk), pk);
		}
	}

	/**
	 * Builds a HEAD element state for the given chain head.
	 */
	@Nonnull
	private static ChainElementState head(int chainHeadPk) {
		return new ChainElementState(chainHeadPk, ChainableType.HEAD_PK, ElementState.HEAD);
	}

	/**
	 * Builds a SUCCESSOR element state inside the given chain with the given predecessor.
	 */
	@Nonnull
	private static ChainElementState succ(int chainHeadPk, int predecessorPk) {
		return new ChainElementState(chainHeadPk, predecessorPk, ElementState.SUCCESSOR);
	}

	/**
	 * Builds a CIRCULAR element state inside the given chain with the given predecessor.
	 */
	@Nonnull
	private static ChainElementState circ(int chainHeadPk, int predecessorPk) {
		return new ChainElementState(chainHeadPk, predecessorPk, ElementState.CIRCULAR);
	}

	/**
	 * Provides deliberately corrupt {@link ChainIndex} configurations together with a human-readable label. Each one
	 * targets a distinct error-detection branch of {@link ChainIndex#getConsistencyReport()} that is reachable only by
	 * feeding crafted state to the deserialization constructor (the public mutation API can never produce them).
	 */
	@Nonnull
	static Stream<Arguments> brokenConfigurations() {
		return Stream.of(
			// predecessor recorded in the state does not match the actual previous element in the chain
			Arguments.of(
				"predecessor mismatch",
				new int[][]{{1, 2, 3}},
				Map.of(1, head(1), 2, succ(1, 7), 3, succ(1, 2))
			),
			// a head is flagged HEAD yet records a (non-head) predecessor - in the positional model membership can no
			// longer disagree with the structure, so corruption now surfaces as a head-state mismatch
			Arguments.of(
				"head flagged HEAD with a real predecessor",
				new int[][]{{1, 2}},
				Map.of(1, new ChainElementState(1, 7, ElementState.HEAD), 2, succ(1, 1))
			),
			// element present in a chain has no element state at all
			Arguments.of(
				"missing element state",
				new int[][]{{1, 2}},
				Map.of(1, head(1))
			),
			// a true head (no predecessor) is flagged SUCCESSOR
			Arguments.of(
				"head flagged SUCCESSOR without a predecessor",
				new int[][]{{1, 2}},
				Map.of(1, new ChainElementState(1, ChainableType.HEAD_PK, ElementState.SUCCESSOR), 2, succ(1, 1))
			),
			// circular head whose chain does not actually contain the predecessor it points at
			Arguments.of(
				"circular head without referenced element",
				new int[][]{{1, 2}},
				Map.of(1, circ(1, 99), 2, succ(1, 1))
			),
			// non-circular head whose chain does contain the predecessor it points at (an unflagged circular)
			Arguments.of(
				"unflagged circular head",
				new int[][]{{1, 2, 3}},
				Map.of(1, succ(1, 2), 2, succ(1, 1), 3, succ(1, 2))
			),
			// successor element that claims a chain it is not part of
			Arguments.of(
				"successor absent from its chain",
				new int[][]{{1, 2}},
				Map.of(1, head(1), 2, succ(1, 1), 5, succ(1, 1))
			),
			// the total number of chained elements does not match the number of element states
			Arguments.of(
				"element count mismatch",
				new int[][]{{1, 2}},
				Map.of(1, head(1), 2, succ(1, 1), 9, head(9))
			),
			// a clean head (no predecessor) is flagged CIRCULAR
			Arguments.of(
				"head flagged CIRCULAR without a predecessor",
				new int[][]{{1, 2}},
				Map.of(1, new ChainElementState(1, ChainableType.HEAD_PK, ElementState.CIRCULAR), 2, succ(1, 1))
			)
		);
	}

	/**
	 * Tests verifying construction and getter correctness for different ChainIndex constructors.
	 */
	@Nested
	@DisplayName("Construction and initialization")
	class ConstructionTest {

		@Test
		@DisplayName("two-arg constructor with RepresentativeReferenceKey sets getters correctly")
		void shouldConstructWithReferenceKeyAndVerifyGetters() {
			final RepresentativeReferenceKey refKey = new RepresentativeReferenceKey(
				new ReferenceKey("brand", 42)
			);
			final AttributeIndexKey attrKey = new AttributeIndexKey(null, "order", null);
			final ChainIndex idx = new ChainIndex(refKey, attrKey);

			assertSame(refKey, idx.getReferenceKey());
			assertSame(attrKey, idx.getAttributeIndexKey());
			assertTrue(idx.isEmpty());
			assertTrue(idx.isConsistent());
		}

		@Test
		@DisplayName("four-arg deserialization constructor provides correct unordered lookup")
		void shouldConstructFromDeserializedDataAndVerifyLookup() {
			final AttributeIndexKey attrKey = new AttributeIndexKey(null, "order", null);
			final int[][] chains = new int[][]{
				{10, 20, 30}
			};
			final Map<Integer, ChainElementState> elementStates = new HashMap<>(4);
			elementStates.put(
				10,
				new ChainElementState(10, ChainableType.HEAD_PK, ChainIndex.ElementState.HEAD)
			);
			elementStates.put(
				20,
				new ChainElementState(10, 10, ChainIndex.ElementState.SUCCESSOR)
			);
			elementStates.put(
				30,
				new ChainElementState(10, 20, ChainIndex.ElementState.SUCCESSOR)
			);

			final ChainIndex idx = new ChainIndex(attrKey, chains, elementStates);

			assertFalse(idx.isEmpty());
			assertTrue(idx.isConsistent());
			assertArrayEquals(new int[]{10, 20, 30}, idx.getUnorderedLookup().getArray());
			assertNull(idx.getReferenceKey());
		}

		@Test
		@DisplayName("four-arg deserialization constructor with reference key")
		void shouldConstructFromDeserializedDataWithReferenceKey() {
			final RepresentativeReferenceKey refKey = new RepresentativeReferenceKey(
				new ReferenceKey("category", 7)
			);
			final AttributeIndexKey attrKey = new AttributeIndexKey(null, "pos", null);
			final int[][] chains = new int[][]{
				{1, 2}
			};
			final Map<Integer, ChainElementState> elementStates = new HashMap<>(4);
			elementStates.put(
				1,
				new ChainElementState(1, ChainableType.HEAD_PK, ChainIndex.ElementState.HEAD)
			);
			elementStates.put(
				2,
				new ChainElementState(1, 1, ChainIndex.ElementState.SUCCESSOR)
			);

			final ChainIndex idx = new ChainIndex(refKey, attrKey, chains, elementStates);

			assertSame(refKey, idx.getReferenceKey());
			assertArrayEquals(new int[]{1, 2}, idx.getUnorderedLookup().getArray());
		}
	}

	/**
	 * Tests for STM invariants: id uniqueness, removeLayer cleanup, commit behavior,
	 * and createCopyWithMergedTransactionalMemory with null layer.
	 */
	@Nested
	@DisplayName("STM invariants")
	class StmInvariantsTest {

		@Test
		@DisplayName("getId() returns stable unique id across different instances")
		void shouldReturnStableUniqueId() {
			final ChainIndex idx1 = new ChainIndex(
				new AttributeIndexKey(null, "a", null)
			);
			final ChainIndex idx2 = new ChainIndex(
				new AttributeIndexKey(null, "b", null)
			);

			assertNotEquals(idx1.getId(), idx2.getId());
			// id is stable across multiple calls
			assertEquals(idx1.getId(), idx1.getId());
		}

		@Test
		@DisplayName("after commit, original and committed are not the same instance")
		void shouldReturnNewInstanceAfterCommit() {
			populateStandardChain();

			assertStateAfterCommit(
				ChainIndexTest.this.index,
				original -> {
					original.upsertPredecessor(new Predecessor(5), 6);
				},
				(original, committed) -> {
					assertNotSame(original, committed);
					// original still has the old 5-element chain
					assertArrayEquals(EXPECTED_CHAIN, original.getUnorderedLookup().getArray());
					// committed has the new 6-element chain
					assertArrayEquals(
						new int[]{1, 2, 3, 4, 5, 6},
						committed.getUnorderedLookup().getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("original order array is not mutated after commit")
		void shouldNotMutateOriginalAfterCommit() {
			populateStandardChain();
			final int[] originalArrayBefore = ChainIndexTest.this.index
				.getUnorderedLookup().getArray().clone();

			assertStateAfterCommit(
				ChainIndexTest.this.index,
				original -> {
					original.upsertPredecessor(new Predecessor(5), 6);
					original.removePredecessor(1);
				},
				(original, committed) -> {
					assertNotSame(original, committed);
					assertArrayEquals(
						originalArrayBefore,
						original.getUnorderedLookup().getArray()
					);
				}
			);
		}
	}

	/**
	 * Tests verifying rollback semantics -- transactional changes are discarded and
	 * the original index remains unchanged.
	 */
	@Nested
	@DisplayName("Transactional rollback")
	class TransactionalRollbackTest {

		@Test
		@DisplayName("rollback after upsert preserves original state")
		void shouldRollbackUpsert() {
			populateStandardChain();

			assertStateAfterRollback(
				ChainIndexTest.this.index,
				original -> {
					original.upsertPredecessor(new Predecessor(5), 6);
					assertArrayEquals(
						new int[]{1, 2, 3, 4, 5, 6},
						original.getUnorderedLookup().getArray()
					);
				},
				(original, committed) -> {
					assertNull(committed);
					assertArrayEquals(
						EXPECTED_CHAIN,
						original.getUnorderedLookup().getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("rollback after remove preserves original state")
		void shouldRollbackRemove() {
			populateStandardChain();

			assertStateAfterRollback(
				ChainIndexTest.this.index,
				original -> {
					original.removePredecessor(3);
				},
				(original, committed) -> {
					assertNull(committed);
					assertArrayEquals(
						EXPECTED_CHAIN,
						original.getUnorderedLookup().getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("rollback of circular dependency introduction preserves original state")
		void shouldRollbackCircularDependency() {
			populateStandardChain();

			assertStateAfterRollback(
				ChainIndexTest.this.index,
				original -> {
					// introduce circular: make element 1 depend on element 3
					original.upsertPredecessor(new Predecessor(3), 1);
				},
				(original, committed) -> {
					assertNull(committed);
					assertTrue(original.isConsistent());
					assertArrayEquals(
						EXPECTED_CHAIN,
						original.getUnorderedLookup().getArray()
					);
				}
			);
		}
	}

	/**
	 * Tests verifying non-transactional mode: operations applied directly without
	 * a transaction context.
	 */
	@Nested
	@DisplayName("Non-transactional mode")
	class NonTransactionalModeTest {

		@Test
		@DisplayName("upsert and remove outside transaction update state correctly")
		void shouldUpsertAndRemoveOutsideTransaction() {
			final ChainIndex idx = new ChainIndex(
				new AttributeIndexKey(null, "x", null)
			);

			idx.upsertPredecessor(new Predecessor(), 10);
			idx.upsertPredecessor(new Predecessor(10), 20);
			idx.upsertPredecessor(new Predecessor(20), 30);

			assertTrue(idx.isConsistent());
			assertArrayEquals(
				new int[]{10, 20, 30},
				idx.getUnorderedLookup().getArray()
			);

			idx.removePredecessor(20);

			assertArrayEquals(
				new int[]{10, 30},
				idx.getUnorderedLookup().getArray()
			);
		}
	}

	/**
	 * Tests verifying ReferencedEntityPredecessor support in the ChainIndex.
	 */
	@Nested
	@DisplayName("ReferencedEntityPredecessor support")
	class ReferencedEntityPredecessorTest {

		@Test
		@DisplayName("upsertPredecessor with ReferencedEntityPredecessor works correctly")
		void shouldUpsertWithReferencedEntityPredecessor() {
			final ChainIndex idx = new ChainIndex(
				new AttributeIndexKey(null, "refOrder", null)
			);

			idx.upsertPredecessor(new ReferencedEntityPredecessor(), 1);
			idx.upsertPredecessor(new ReferencedEntityPredecessor(1), 2);
			idx.upsertPredecessor(new ReferencedEntityPredecessor(2), 3);

			assertTrue(idx.isConsistent());
			assertArrayEquals(
				new int[]{1, 2, 3},
				idx.getUnorderedLookup().getArray()
			);
		}

		@Test
		@DisplayName("self-reference via ReferencedEntityPredecessor does NOT throw")
		void shouldAllowSelfReferenceWithReferencedEntityPredecessor() {
			final ChainIndex idx = new ChainIndex(
				new AttributeIndexKey(null, "refOrder", null)
			);

			idx.upsertPredecessor(new ReferencedEntityPredecessor(), 1);

			// self-reference is allowed for ReferencedEntityPredecessor
			assertDoesNotThrow(
				() -> idx.upsertPredecessor(new ReferencedEntityPredecessor(1), 1)
			);
		}
	}

	/**
	 * Tests verifying error paths: removing non-existent elements, self-referential Predecessor.
	 */
	@Nested
	@DisplayName("Error paths")
	class ErrorPathsTest {

		@Test
		@DisplayName("removePredecessor on non-existent element throws EvitaInvalidUsageException")
		void shouldThrowOnRemoveNonExistent() {
			final EvitaInvalidUsageException ex = assertThrows(
				EvitaInvalidUsageException.class,
				() -> ChainIndexTest.this.index.removePredecessor(999)
			);
			assertTrue(
				ex.getMessage().contains("999"),
				"Exception message should reference the missing pk"
			);
		}

		@Test
		@DisplayName("self-referential Predecessor throws EvitaInvalidUsageException")
		void shouldThrowOnSelfReferentialPredecessor() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> ChainIndexTest.this.index.upsertPredecessor(
					new Predecessor(5), 5
				)
			);
		}
	}

	/**
	 * Tests verifying SortedRecordsSupplier behavior for ascending and descending order.
	 */
	@Nested
	@DisplayName("SortedRecordsSupplier")
	class SortedRecordsSupplierTest {

		@Test
		@DisplayName("ascending order supplier returns correct order")
		void shouldReturnAscendingOrder() {
			populateStandardChain();

			final SortedRecordsSupplier asc =
				(SortedRecordsSupplier) ChainIndexTest.this.index
					.getAscendingOrderRecordsSupplier();

			assertArrayEquals(
				EXPECTED_CHAIN,
				asc.getSortedRecordIds()
			);
		}

		@Test
		@DisplayName("descending order supplier returns reverse order")
		void shouldReturnDescendingOrder() {
			populateStandardChain();

			final SortedRecordsSupplier desc =
				(SortedRecordsSupplier) ChainIndexTest.this.index
					.getDescendingOrderRecordsSupplier();

			assertArrayEquals(
				new int[]{5, 4, 3, 2, 1},
				desc.getSortedRecordIds()
			);
		}

		@Test
		@DisplayName("ascending supplier with referenceKey returns ReferenceSortedRecordsProvider")
		void shouldReturnReferenceSortedRecordsProviderWhenReferenceKeyPresent() {
			final RepresentativeReferenceKey refKey = new RepresentativeReferenceKey(
				new ReferenceKey("brand", 1)
			);
			final ChainIndex idx = new ChainIndex(
				refKey,
				new AttributeIndexKey(null, "order", null)
			);
			idx.upsertPredecessor(new Predecessor(), 10);
			idx.upsertPredecessor(new Predecessor(10), 20);

			final SortedRecordsSupplier asc =
				(SortedRecordsSupplier) idx.getAscendingOrderRecordsSupplier();

			assertInstanceOf(ReferenceSortedRecordsProvider.class, asc);
			assertArrayEquals(new int[]{10, 20}, asc.getSortedRecordIds());
		}

		@Test
		@DisplayName("cached supplier on repeated call without modification returns same instance")
		void shouldReturnCachedSupplier() {
			populateStandardChain();

			final SortedRecordsSupplier first =
				(SortedRecordsSupplier) ChainIndexTest.this.index
					.getAscendingOrderRecordsSupplier();
			final SortedRecordsSupplier second =
				(SortedRecordsSupplier) ChainIndexTest.this.index
					.getAscendingOrderRecordsSupplier();

			assertSame(first, second);
		}
	}

	/**
	 * Tests verifying dirty flag and storage part creation behavior.
	 */
	@Nested
	@DisplayName("Dirty flag and storage part")
	class DirtyFlagStoragePartTest {

		@Test
		@DisplayName("createStoragePart returns non-null after upsert")
		void shouldReturnStoragePartAfterUpsert() {
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(), 1);

			final StoragePart part = ChainIndexTest.this.index.createStoragePart(1);
			assertNotNull(part);
			assertInstanceOf(ChainIndexStoragePart.class, part);
		}

		@Test
		@DisplayName("createStoragePart returns null on fresh (non-dirty) index")
		void shouldReturnNullStoragePartOnFreshIndex() {
			final StoragePart part = ChainIndexTest.this.index.createStoragePart(1);
			assertNull(part);
		}

		@Test
		@DisplayName("resetDirty makes createStoragePart return null")
		void shouldReturnNullAfterResetDirty() {
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(), 1);
			// consume the dirty state
			final StoragePart firstPart = ChainIndexTest.this.index.createStoragePart(1);
			assertNotNull(firstPart);

			ChainIndexTest.this.index.resetDirty();

			final StoragePart secondPart = ChainIndexTest.this.index.createStoragePart(1);
			assertNull(secondPart);
		}
	}

	/**
	 * Tests verifying isEmpty behavior across lifecycle states.
	 */
	@Nested
	@DisplayName("isEmpty behavior")
	class IsEmptyTest {

		@Test
		@DisplayName("fresh index is empty")
		void shouldBeEmptyOnFreshIndex() {
			assertTrue(ChainIndexTest.this.index.isEmpty());
		}

		@Test
		@DisplayName("index is not empty after upsert")
		void shouldNotBeEmptyAfterUpsert() {
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(), 1);
			assertFalse(ChainIndexTest.this.index.isEmpty());
		}

		@Test
		@DisplayName("index is empty after all elements removed")
		void shouldBeEmptyAfterAllRemoved() {
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(), 1);
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(1), 2);

			ChainIndexTest.this.index.removePredecessor(2);
			ChainIndexTest.this.index.removePredecessor(1);

			assertTrue(ChainIndexTest.this.index.isEmpty());
		}
	}

	/**
	 * Tests verifying toString output for various index states.
	 */
	@Nested
	@DisplayName("toString representation")
	class ToStringTest {

		@Test
		@DisplayName("toString with referenceKey includes refKey label")
		void shouldIncludeRefKeyInToString() {
			final RepresentativeReferenceKey refKey = new RepresentativeReferenceKey(
				new ReferenceKey("brand", 5)
			);
			final ChainIndex idx = new ChainIndex(
				refKey,
				new AttributeIndexKey(null, "order", null)
			);
			idx.upsertPredecessor(new Predecessor(), 1);

			final String result = idx.toString();
			assertTrue(
				result.contains("(refKey:"),
				"toString should contain '(refKey:' prefix"
			);
		}

		@Test
		@DisplayName("toString for empty index produces valid output")
		void shouldProduceValidToStringForEmptyIndex() {
			final String result = ChainIndexTest.this.index.toString();
			assertNotNull(result);
			assertTrue(result.contains("ChainIndex"));
		}
	}

	/**
	 * Tests verifying consistency report states.
	 */
	@Nested
	@DisplayName("Consistency report")
	class ConsistencyReportTest {

		@Test
		@DisplayName("INCONSISTENT state when multiple chains exist")
		void shouldReportInconsistentWhenMultipleChainsExist() {
			populateStandardChain();

			// introduce a second chain that cannot be merged
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(3), 6);
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(3), 7);

			assertFalse(ChainIndexTest.this.index.isConsistent());

			final ConsistencyState state =
				ChainIndexTest.this.index.getConsistencyReport().state();
			assertEquals(ConsistencyState.INCONSISTENT, state);
		}

		@Test
		@DisplayName("report truncates a long chain listing with a remaining-count suffix")
		void shouldTruncateLongChainListing() {
			final ChainIndex idx = new ChainIndex(new AttributeIndexKey(null, "long", null));
			for (int pk = 1; pk <= 40; pk++) {
				idx.upsertPredecessor(pk == 1 ? Predecessor.HEAD : new Predecessor(pk - 1), pk);
			}

			final ConsistencyReport report = idx.getConsistencyReport();
			assertEquals(ConsistencyState.CONSISTENT, report.state());
			assertTrue(
				report.report().contains("more)"),
				"A long chain listing must be truncated with a '(N more)' suffix."
			);
		}
	}

	/**
	 * Asserts that the index is not in an internally corrupt ({@link ConsistencyState#BROKEN}) state. The
	 * {@link ChainIndex#getConsistencyReport()} independently re-derives correctness from {@link ChainIndex#chains}
	 * and {@link ChainIndex#predecessors}, so it is a strong, implementation-independent integrity oracle.
	 */
	private static void assertNotBroken(@Nonnull ChainIndex index) {
		final ConsistencyReport report = index.getConsistencyReport();
		assertNotEquals(
			ConsistencyState.BROKEN, report.state(),
			() -> "Index reported BROKEN internal state:\n" + report.report()
		);
	}

	/**
	 * Tests for the chain-split that happens when a successor in the middle of a chain is removed. The split is
	 * asserted in its inconsistent intermediate state (before any correcting mutation re-collapses it), which the
	 * existing removal tests never do.
	 */
	@Nested
	@DisplayName("Successor removal split")
	class SuccessorRemovalSplitTest {

		@Test
		@DisplayName("removing a mid-chain successor splits the chain and a reconnect collapses it back")
		void shouldSplitChainWhenMidElementRemovedAndCollapseOnReconnect() {
			final ChainIndex idx = new ChainIndex(new AttributeIndexKey(null, "split", null));
			for (int pk : EXPECTED_CHAIN) {
				idx.upsertPredecessor(PREDECESSOR_MAP.get(pk), pk);
			}
			assertTrue(idx.isConsistent());

			// remove the mid-chain successor 3 -> chain [1,2,3,4,5] splits into [1,2] and [4,5]
			idx.removePredecessor(3);

			assertFalse(idx.isConsistent(), "Removing a mid-chain element must leave the index inconsistent.");
			assertNotBroken(idx);
			assertEquals(2, idx.chains.size(), "The chain must split into exactly two chains.");
			assertNotNull(idx.chains.get(1), "The head chain [1,2] must remain.");
			assertNotNull(idx.chains.get(4), "The tail must become a new chain headed by 4.");
			// 4 keeps its (now dangling) predecessor 3 but becomes the head of its own split chain
			assertEquals(ElementState.SUCCESSOR, idx.getElementState(4).state());
			assertEquals(4, idx.getElementState(4).inChainOfHeadWithPrimaryKey());
			assertEquals(3, idx.getElementState(4).predecessorPrimaryKey());
			// heads first then the orphan successor chain: [1,2] then [4,5]
			assertArrayEquals(new int[]{1, 2, 4, 5}, idx.getUnorderedLookup().getArray());
			assertEquals(ConsistencyState.INCONSISTENT, idx.getConsistencyReport().state());

			// reconnect 4 to the tail of the head chain -> the two chains collapse into one
			idx.upsertPredecessor(new Predecessor(2), 4);

			assertTrue(idx.isConsistent(), "Reconnecting the split must restore consistency.");
			assertNotBroken(idx);
			assertEquals(1, idx.chains.size());
			assertArrayEquals(new int[]{1, 2, 4, 5}, idx.getUnorderedLookup().getArray());
			assertEquals(ConsistencyState.CONSISTENT, idx.getConsistencyReport().state());
		}

		@Test
		@DisplayName("removing the tail successor shortens the chain without splitting")
		void shouldShortenChainWhenTailRemoved() {
			final ChainIndex idx = new ChainIndex(new AttributeIndexKey(null, "split", null));
			for (int pk : EXPECTED_CHAIN) {
				idx.upsertPredecessor(PREDECESSOR_MAP.get(pk), pk);
			}

			idx.removePredecessor(5);

			assertTrue(idx.isConsistent(), "Removing the tail keeps a single chain.");
			assertNotBroken(idx);
			assertEquals(1, idx.chains.size());
			assertArrayEquals(new int[]{1, 2, 3, 4}, idx.getUnorderedLookup().getArray());
		}
	}

	/**
	 * Tests for the full circular-dependency lifecycle: creation via the pure insert path, creation via the update
	 * path, demotion back to SUCCESSOR when the perpetrator element is removed, and the consistency-report behaviour
	 * while a circular head is live. These transitions are the reason this data structure exists and were previously
	 * exercised by a single test only.
	 */
	@Nested
	@DisplayName("Circular dependency lifecycle")
	class CircularLifecycleTest {

		@Test
		@DisplayName("a circular dependency forms on the insert path when the closing element arrives last")
		void shouldFormCircularOnInsertPath() {
			final ChainIndex idx = new ChainIndex(new AttributeIndexKey(null, "circular", null));

			// 1 arrives first pointing at a not-yet-present predecessor 3 -> orphan SUCCESSOR chain head
			idx.upsertPredecessor(new Predecessor(3), 1);
			assertEquals(ElementState.SUCCESSOR, idx.getElementState(1).state());
			// 2 appended after 1
			idx.upsertPredecessor(new Predecessor(1), 2);
			assertEquals(ElementState.SUCCESSOR, idx.getElementState(1).state());

			// 3 now arrives for the FIRST time as successor of tail 2; the head 1 already points at 3 -> circular
			idx.upsertPredecessor(new Predecessor(2), 3);

			assertEquals(
				ElementState.CIRCULAR, idx.getElementState(1).state(),
				"The head must be flagged CIRCULAR via the insert path."
			);
			assertNotBroken(idx);
			assertEquals(1, idx.chains.size());
			assertArrayEquals(new int[]{1, 2, 3}, idx.getUnorderedLookup().getArray());

			// promote 1 to a real head -> the circular dependency is broken and the chain becomes consistent
			idx.upsertPredecessor(Predecessor.HEAD, 1);

			assertEquals(ElementState.HEAD, idx.getElementState(1).state());
			assertTrue(idx.isConsistent());
			assertNotBroken(idx);
			assertArrayEquals(new int[]{1, 2, 3}, idx.getUnorderedLookup().getArray());
			assertEquals(ConsistencyState.CONSISTENT, idx.getConsistencyReport().state());
		}

		@Test
		@DisplayName("removing the perpetrator tail demotes a circular head back to SUCCESSOR")
		void shouldDemoteCircularHeadWhenPerpetratorRemoved() {
			final ChainIndex idx = new ChainIndex(new AttributeIndexKey(null, "circular", null));
			// build a consistent chain 1 <- 2 <- 3
			idx.upsertPredecessor(Predecessor.HEAD, 1);
			idx.upsertPredecessor(new Predecessor(1), 2);
			idx.upsertPredecessor(new Predecessor(2), 3);

			// close the loop: 1 now depends on its own tail 3. The cycle 1->2->3->1 is rotated so the run becomes
			// [2,3,1] with 2 as the CIRCULAR head (2's predecessor 1 now sits in its own run); 1 keeps predecessor 3.
			// Which element heads the cycle is an implementation-defined rotation choice for a circular (semi-consistent)
			// chain - the contract is only that a single circular chain exists and the structure is not BROKEN.
			idx.upsertPredecessor(new Predecessor(3), 1);
			assertTrue(idx.isConsistent(), "A closed loop is a single (circular) chain.");
			assertEquals(ElementState.CIRCULAR, idx.getElementState(2).state());
			assertEquals(3, idx.getElementState(1).predecessorPrimaryKey());
			// the consistency report's circular-head verification branch must accept this as not broken
			assertNotBroken(idx);

			// remove the perpetrator (the tail 3 the head points at) -> head demotes CIRCULAR -> SUCCESSOR
			idx.removePredecessor(3);

			assertEquals(
				ElementState.SUCCESSOR, idx.getElementState(1).state(),
				"Removing the perpetrator must clear the CIRCULAR flag on the head."
			);
			assertNotBroken(idx);
			assertArrayEquals(new int[]{1, 2}, idx.getUnorderedLookup().getArray());

			// fully recover by making 1 a head again
			idx.upsertPredecessor(Predecessor.HEAD, 1);
			assertEquals(ElementState.HEAD, idx.getElementState(1).state());
			assertTrue(idx.isConsistent());
			assertNotBroken(idx);
			assertArrayEquals(new int[]{1, 2}, idx.getUnorderedLookup().getArray());
		}
	}

	/**
	 * Tests for updating an element so that it points at a predecessor which is not currently present in the index
	 * (its predecessor was removed, or has not arrived yet). Depending on the element's position this either splits
	 * its chain into a new orphan successor chain (body element) or merely re-flags the head as an orphan successor
	 * (head element). Both paths were previously unreached by the test suite.
	 */
	@Nested
	@DisplayName("Update to an absent predecessor")
	class AbsentPredecessorUpdateTest {

		@Test
		@DisplayName("a body element repointed at an absent predecessor splits off a new orphan successor chain")
		void shouldSplitWhenBodyElementRepointedAtAbsentPredecessor() {
			final ChainIndex idx = new ChainIndex(new AttributeIndexKey(null, "absent", null));
			idx.upsertPredecessor(Predecessor.HEAD, 1);
			idx.upsertPredecessor(new Predecessor(1), 2);
			idx.upsertPredecessor(new Predecessor(2), 3);

			// repoint the middle element 2 at predecessor 99 which is not present in the index
			idx.upsertPredecessor(new Predecessor(99), 2);

			assertFalse(idx.isConsistent());
			assertNotBroken(idx);
			assertEquals(2, idx.chains.size());
			// 2 becomes the head of its own split chain [2,3] and keeps the dangling predecessor 99
			assertEquals(2, idx.getElementState(2).inChainOfHeadWithPrimaryKey());
			assertEquals(99, idx.getElementState(2).predecessorPrimaryKey());
			assertEquals(ElementState.SUCCESSOR, idx.getElementState(2).state());
			assertEquals(2, idx.getElementState(3).inChainOfHeadWithPrimaryKey());
			// the head chain [1] precedes the orphan successor chain [2,3]
			assertArrayEquals(new int[]{1, 2, 3}, idx.getUnorderedLookup().getArray());

			// the missing predecessor 99 finally arrives at the tail of the head chain -> chains collapse into one
			idx.upsertPredecessor(new Predecessor(1), 99);
			assertTrue(idx.isConsistent());
			assertNotBroken(idx);
			assertArrayEquals(new int[]{1, 99, 2, 3}, idx.getUnorderedLookup().getArray());
		}

		@Test
		@DisplayName("a head element repointed at an absent predecessor becomes an orphan successor without splitting")
		void shouldOrphanHeadWhenRepointedAtAbsentPredecessor() {
			final ChainIndex idx = new ChainIndex(new AttributeIndexKey(null, "absent", null));
			idx.upsertPredecessor(Predecessor.HEAD, 1);
			idx.upsertPredecessor(new Predecessor(1), 2);
			idx.upsertPredecessor(new Predecessor(2), 3);

			// repoint the head element 1 at predecessor 99 which is not present in the index
			idx.upsertPredecessor(new Predecessor(99), 1);

			assertNotBroken(idx);
			assertEquals(1, idx.chains.size());
			// the chain is structurally unchanged, but its head is now an orphan SUCCESSOR of the absent 99
			assertEquals(ElementState.SUCCESSOR, idx.getElementState(1).state());
			assertEquals(1, idx.getElementState(1).inChainOfHeadWithPrimaryKey());
			assertEquals(99, idx.getElementState(1).predecessorPrimaryKey());
			assertArrayEquals(new int[]{1, 2, 3}, idx.getUnorderedLookup().getArray());

			// restore 1 as a real head -> fully consistent again
			idx.upsertPredecessor(Predecessor.HEAD, 1);
			assertTrue(idx.isConsistent());
			assertNotBroken(idx);
			assertEquals(ElementState.HEAD, idx.getElementState(1).state());
			assertArrayEquals(new int[]{1, 2, 3}, idx.getUnorderedLookup().getArray());
		}
	}

	/**
	 * Tests pinning the documented ordering contract of {@link ChainIndex#getUnorderedLookup()} across an index that
	 * simultaneously holds head chains, an orphan successor chain and a circular chain. The order is: head chains
	 * first, then successor chains, then circular chains (by {@link ElementState} ordinal), and within the same
	 * state the longer chains come first.
	 */
	@Nested
	@DisplayName("Unordered lookup ordering")
	class UnorderedLookupOrderingTest {

		@Test
		@DisplayName("chains are ordered by element-state tier and then by descending length")
		void shouldOrderByStateTierThenLength() {
			// two head chains (len 3 and 2), one orphan successor chain (len 4), one circular chain (len 2)
			final int[][] chains = {
				{1, 2, 3},
				{10, 11},
				{20, 21, 22, 23},
				{30, 31}
			};
			final Map<Integer, ChainElementState> states = new HashMap<>(16);
			states.put(1, new ChainElementState(1, ChainableType.HEAD_PK, ElementState.HEAD));
			states.put(2, new ChainElementState(1, 1, ElementState.SUCCESSOR));
			states.put(3, new ChainElementState(1, 2, ElementState.SUCCESSOR));
			states.put(10, new ChainElementState(10, ChainableType.HEAD_PK, ElementState.HEAD));
			states.put(11, new ChainElementState(10, 10, ElementState.SUCCESSOR));
			// orphan successor chain - head 20 points at an absent predecessor 99
			states.put(20, new ChainElementState(20, 99, ElementState.SUCCESSOR));
			states.put(21, new ChainElementState(20, 20, ElementState.SUCCESSOR));
			states.put(22, new ChainElementState(20, 21, ElementState.SUCCESSOR));
			states.put(23, new ChainElementState(20, 22, ElementState.SUCCESSOR));
			// circular chain - head 30 points at its own tail 31
			states.put(30, new ChainElementState(30, 31, ElementState.CIRCULAR));
			states.put(31, new ChainElementState(30, 30, ElementState.SUCCESSOR));

			final ChainIndex idx = new ChainIndex(new AttributeIndexKey(null, "order", null), chains, states);

			assertNotBroken(idx);
			assertFalse(idx.isConsistent());
			assertEquals(ConsistencyState.INCONSISTENT, idx.getConsistencyReport().state());
			// HEAD tier (len 3 then len 2) -> SUCCESSOR tier (len 4) -> CIRCULAR tier (len 2)
			assertArrayEquals(
				new int[]{1, 2, 3, 10, 11, 20, 21, 22, 23, 30, 31},
				idx.getUnorderedLookup().getArray()
			);
		}
	}

	/**
	 * Tests that deliberately corrupt {@link ChainIndex} configurations (built via the deserialization constructor)
	 * are detected and reported as {@link ConsistencyState#BROKEN}. No prior test exercised the BROKEN reporting path
	 * or the individual error-detection branches of {@link ChainIndex#getConsistencyReport()}. Each parameter set
	 * targets a distinct corruption; see {@link ChainIndexTest#brokenConfigurations()}.
	 */
	@Nested
	@DisplayName("Broken state detection")
	class BrokenStateDetectionTest {

		@DisplayName("corrupt configuration is detected and reported as BROKEN")
		@ParameterizedTest(name = "{0}")
		@MethodSource("io.evitadb.index.attribute.ChainIndexTest#brokenConfigurations")
		void shouldDetectBrokenConfiguration(
			String name,
			int[][] chains,
			Map<Integer, ChainElementState> states
		) {
			final ChainIndex idx = new ChainIndex(new AttributeIndexKey(null, "broken", null), chains, states);

			final ConsistencyReport report = idx.getConsistencyReport();
			assertEquals(ConsistencyState.BROKEN, report.state(), () -> "Expected BROKEN for: " + name);
			assertTrue(
				report.report().contains("Errors detected"),
				() -> "Report for `" + name + "` must list the detected errors."
			);
		}
	}

	/**
	 * Deterministic, seed-fixed randomized stress kept in the fast functional suite. It hammers a small element set
	 * with circular-inducing repointing, removals and re-insertions - the operation mix that drives the hard-to-reach
	 * collapse/circular branches (chain merge re-introducing a circular dependency, collapse blocked by a circular
	 * candidate, demotion of a previously circular merged head, and the "chain merged away" circular-resolution arm).
	 * After every single operation it asserts two invariants that hold regardless of the (implementation-defined)
	 * intermediate ordering: the structure is never internally BROKEN, and it contains exactly the live element set
	 * with no losses or duplicates. Finally it rebuilds a clean chain over the survivors and asserts the exact order.
	 */
	@Nested
	@DisplayName("Randomized invariant stress")
	class RandomizedInvariantTest {

		@Test
		@DisplayName("circular-heavy random churn never corrupts the structure and recovers to a consistent order")
		void shouldPreserveInvariantsUnderCircularHeavyChurnAndRecover() {
			final int n = 6;
			final ChainIndex idx = new ChainIndex(new AttributeIndexKey(null, "fuzz", null));
			// live[pk] tracks whether the element pk is currently present in the index
			final boolean[] live = new boolean[n + 1];
			final Random rnd = new Random(0xC0FFEEL);

			// seed a consistent chain 1..n
			for (int pk = 1; pk <= n; pk++) {
				idx.upsertPredecessor(pk == 1 ? Predecessor.HEAD : new Predecessor(pk - 1), pk);
				live[pk] = true;
			}
			assertNotBroken(idx);

			final int operations = 10_000;
			for (int op = 0; op < operations; op++) {
				final int roll = rnd.nextInt(10);
				if (roll < 2) {
					// remove a present element
					final int pk = pickPresent(rnd, live, n);
					if (pk > 0) {
						idx.removePredecessor(pk);
						live[pk] = false;
					}
				} else if (roll < 4) {
					// (re)insert an absent element as a head or as a successor of a present element
					final int pk = pickAbsent(rnd, live, n);
					if (pk > 0) {
						final int anchor = pickPresent(rnd, live, n);
						idx.upsertPredecessor(anchor < 0 ? Predecessor.HEAD : new Predecessor(anchor), pk);
						live[pk] = true;
					}
				} else {
					// repoint a present element at a head, another present element, or an ABSENT predecessor -
					// may split, orphan or close a loop
					final int pk = pickPresent(rnd, live, n);
					if (pk > 0) {
						final int dice = rnd.nextInt(10);
						final int anchor;
						if (dice == 0) {
							anchor = -1; // promote to head
						} else if (dice < 3) {
							// any pk in range, possibly absent -> exercises the update-to-absent-predecessor path
							final int candidate = 1 + rnd.nextInt(n);
							anchor = candidate == pk ? -1 : candidate;
						} else {
							anchor = pickPresentOtherThan(rnd, live, n, pk);
						}
						idx.upsertPredecessor(anchor < 0 ? Predecessor.HEAD : new Predecessor(anchor), pk);
					}
				}

				final int currentOp = op;
				// invariant 1 - the internal structure is never corrupt
				assertNotBroken(idx);
				// invariant 2 - the index holds exactly the live set, each element exactly once
				assertEquals(
					liveSet(live, n), lookupSet(idx),
					() -> "Element set diverged from the live set at operation " + currentOp
				);
			}

			// drive the survivors back into a single fully-specified consistent chain and assert the exact order
			final int[] survivors = liveArray(live, n);
			for (int i = 0; i < survivors.length; i++) {
				idx.upsertPredecessor(i == 0 ? Predecessor.HEAD : new Predecessor(survivors[i - 1]), survivors[i]);
			}
			assertTrue(idx.isConsistent(), "The index must be consistent after a clean rebuild.");
			assertNotBroken(idx);
			assertEquals(ConsistencyState.CONSISTENT, idx.getConsistencyReport().state());
			assertArrayEquals(survivors, idx.getUnorderedLookup().getArray());
		}

		/**
		 * Returns a random currently-present primary key, or -1 when none is present.
		 */
		private int pickPresent(@Nonnull Random rnd, @Nonnull boolean[] live, int n) {
			return pickMatching(rnd, live, n, -1, true);
		}

		/**
		 * Returns a random currently-absent primary key from the 1..n range, or -1 when all are present.
		 */
		private int pickAbsent(@Nonnull Random rnd, @Nonnull boolean[] live, int n) {
			return pickMatching(rnd, live, n, -1, false);
		}

		/**
		 * Returns a random present primary key other than `exclude`, or -1 when none qualifies.
		 */
		private int pickPresentOtherThan(@Nonnull Random rnd, @Nonnull boolean[] live, int n, int exclude) {
			return pickMatching(rnd, live, n, exclude, true);
		}

		/**
		 * Reservoir-samples a single primary key from 1..n whose liveness equals `wantPresent` and which is not
		 * equal to `exclude`. Returns -1 when no primary key qualifies.
		 */
		private int pickMatching(@Nonnull Random rnd, @Nonnull boolean[] live, int n, int exclude, boolean wantPresent) {
			int chosen = -1;
			int seen = 0;
			for (int pk = 1; pk <= n; pk++) {
				if (live[pk] == wantPresent && pk != exclude) {
					seen++;
					if (rnd.nextInt(seen) == 0) {
						chosen = pk;
					}
				}
			}
			return chosen;
		}

		/**
		 * Builds the set of currently-present primary keys.
		 */
		@Nonnull
		private Set<Integer> liveSet(@Nonnull boolean[] live, int n) {
			final Set<Integer> result = new TreeSet<>();
			for (int pk = 1; pk <= n; pk++) {
				if (live[pk]) {
					result.add(pk);
				}
			}
			return result;
		}

		/**
		 * Builds the set of primary keys currently returned by the index lookup.
		 */
		@Nonnull
		private Set<Integer> lookupSet(@Nonnull ChainIndex index) {
			final Set<Integer> result = new TreeSet<>();
			for (int pk : index.getUnorderedLookup().getArray()) {
				assertTrue(result.add(pk), "Duplicate primary key " + pk + " returned by the index!");
			}
			return result;
		}

		/**
		 * Builds the ascending array of currently-present primary keys.
		 */
		@Nonnull
		private int[] liveArray(@Nonnull boolean[] live, int n) {
			int count = 0;
			for (int pk = 1; pk <= n; pk++) {
				if (live[pk]) {
					count++;
				}
			}
			final int[] result = new int[count];
			int idx = 0;
			for (int pk = 1; pk <= n; pk++) {
				if (live[pk]) {
					result[idx++] = pk;
				}
			}
			return result;
		}
	}

}
