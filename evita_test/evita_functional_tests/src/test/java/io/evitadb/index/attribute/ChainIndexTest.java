/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

import com.carrotsearch.hppc.IntArrayList;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.dataType.ChainableType;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyReport;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyState;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.array.TransactionalUnorderedIntArray;
import io.evitadb.index.array.UnorderedLookupTree;
import io.evitadb.index.attribute.ChainIndex.ChainDescriptor;
import io.evitadb.index.attribute.ChainIndex.ChainElementState;
import io.evitadb.index.attribute.ChainIndex.ElementState;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexLeafPageRemoval;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexStoragePart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies the contract of {@link ChainIndex} implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
@DisplayName("ChainIndex")
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
	 * Populates the shared {@link #index} field with the standard 1-5 chain.
	 */
	private void populateStandardChain() {
		for (int pk : EXPECTED_CHAIN) {
			this.index.upsertPredecessor(PREDECESSOR_MAP.get(pk), pk);
		}
	}

	/**
	 * Builds the documented sample chain {@code 23, 26, 8, ... , 11} on the shared index and applies a transactional
	 * reordering batch, asserting both the in-transaction and committed states remain consistent. Shared by the
	 * transactional-behaviour test and the consistency-report test so neither has to invoke the other.
	 */
	private void buildCommittedConsistentChain() {
		final int[] initialState = {23, 26, 8, 3, 2, 4, 7, 6, 9, 10, 5, 11};
		for (int i = 0; i < initialState.length; i++) {
			final int pk = initialState[i];
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
	 * Cross-checks the element array's internal chain-head bitmask against the authoritative head set
	 * ({@link ChainIndex#chains} keys): every chain head must be marked at its own position and no other position may
	 * be marked. This is the one invariant the index's read paths cannot see - getUnorderedLookup and
	 * getConsistencyReport resolve heads by {@code indexOf} and never consult the bitmask - so a missing or stray
	 * {@code markAsHead}/{@code unmarkAsHead} at any mutation site would stay invisible until a later {@code findRun}
	 * trips over it. Asserting it directly pins a head-mark desync to the operation that caused it. A single `O(N·log N)`
	 * walk catches both directions: a missing mark makes {@code findHeadCovering} return an earlier head; a stray mark
	 * makes it return the stray position.
	 */
	private static void assertHeadMarksMatchChains(@Nonnull ChainIndex index) {
		final int length = index.elements.getLength();
		if (length == 0) {
			assertEquals(0, index.chains.size(), "Empty element array must carry no chain descriptors.");
			return;
		}
		final boolean[] expectedHead = new boolean[length];
		for (final Integer headPk : index.chains.keySet()) {
			expectedHead[index.elements.indexOf(headPk)] = true;
		}
		assertTrue(expectedHead[0], "Logical position 0 must be a chain head.");
		int expectedCovering = -1;
		for (int p = 0; p < length; p++) {
			if (expectedHead[p]) {
				expectedCovering = p;
			}
			final TransactionalUnorderedIntArray.HeadLocation head = index.elements.findHeadCovering(p);
			assertEquals(
				expectedCovering, head.headPosition(),
				"Head-mark bitmask disagrees with chains.keySet() at position " + p
			);
			assertEquals(
				index.elements.get(expectedCovering), head.recordId(),
				"Covering-head record id mismatch at position " + p
			);
		}
	}

	/**
	 * Asserts the index is FULLY COLLAPSED: no successor head remains whose declared predecessor is present and is the
	 * tail of a different run. This is exactly the merge condition the former full-rescan {@code collapse} drove to a
	 * fixpoint, replicated here independently of the work-queue's seeds from the public / package-visible state. If the
	 * seeded {@link ChainIndex#collapse(IntArrayList)} ever left such a pair, the old scan would
	 * have merged it - so this oracle pins any under-collapse regression to the operation that caused it.
	 */
	private static void assertFullyCollapsed(@Nonnull ChainIndex index) {
		final int length = index.elements.getLength();
		for (final Entry<Integer, ChainDescriptor> entry : index.chains.entrySet()) {
			final Integer headPk = entry.getKey();
			final ChainIndex.ChainDescriptor descriptor = entry.getValue();
			if (descriptor.state() != ElementState.SUCCESSOR) {
				// only successor heads can follow another run
				continue;
			}
			final Integer predecessorRef = index.predecessors.get(headPk);
			assertNotNull(predecessorRef, () -> "Successor head " + headPk + " has no predecessor entry.");
			final int predecessor = predecessorRef;
			if (predecessor == ChainableType.HEAD_PK) {
				continue;
			}
			final int predecessorPos = index.elements.indexOf(predecessor);
			if (predecessorPos == Integer.MIN_VALUE) {
				// predecessor absent - this run is legitimately a standing orphan
				continue;
			}
			// the predecessor is a tail iff it is the array's last element or the next element starts a new run (the
			// heads are exactly chains.keySet()); the old scan would have merged this run onto it unless it is the
			// same run (a circular head, handled by state)
			final boolean predecessorIsTail = predecessorPos == length - 1
				|| index.chains.containsKey(index.elements.get(predecessorPos + 1));
			final int headPos = index.elements.indexOf(headPk);
			final boolean sameRun = predecessorPos >= headPos && predecessorPos < headPos + descriptor.length();
			assertFalse(
				predecessorIsTail && !sameRun,
				() -> "Collapse left a mergeable pair: successor head " + headPk + " declares predecessor " +
					predecessor + " which is the tail of another run - the work-queue under-collapsed."
			);
		}
	}

	/**
	 * Asserts the {@link ChainIndex#successorsByPredecessor} inverse is an exact, sparse mirror of
	 * {@link ChainIndex#predecessors}. This is strictly stronger than the cross-check inside
	 * {@link ChainIndex#getConsistencyReport()}: the report only proves the two maps agree on the entries that exist,
	 * whereas this oracle additionally pins the sparsity contract the report cannot see - an emptied bucket must be
	 * dropped, and a true head must never link into the inverse - so a leaked or never-cleared bucket is caught at the
	 * mutation that produced it. The whole inverse is re-derived from `predecessors` alone and compared bucket for
	 * bucket:
	 *
	 * 1. no bucket key is {@link ChainableType#HEAD_PK} (heads never collapse via their predecessor),
	 * 2. no bucket is empty (an emptied bucket must have been removed to keep the map as sparse as the chain set),
	 * 3. the bucket key set equals exactly the set of present non-`HEAD_PK` predecessors declared in `predecessors`,
	 * 4. each bucket holds exactly the elements that declare that predecessor.
	 */
	private static void assertInverseIsExactAndSparse(@Nonnull ChainIndex index) {
		// re-derive the expected inverse purely from predecessors: each non-HEAD predecessor maps to the exact set of
		// elements that declare it
		final Map<Integer, Set<Integer>> expected = new HashMap<>();
		for (final Entry<Integer, Integer> entry : index.predecessors.entrySet()) {
			final int predecessorPk = entry.getValue();
			if (predecessorPk != ChainableType.HEAD_PK) {
				expected.computeIfAbsent(predecessorPk, p -> new TreeSet<>()).add(entry.getKey());
			}
		}
		// (1) a true head never links into the inverse, so HEAD_PK must never be a bucket key
		assertFalse(
			index.successorsByPredecessor.containsKey(ChainableType.HEAD_PK),
			"successorsByPredecessor must never carry a HEAD_PK bucket."
		);
		// (3) the bucket key set must be exactly the present non-HEAD predecessors - no stale, no missing bucket
		assertEquals(
			new TreeSet<>(expected.keySet()),
			new TreeSet<>(index.successorsByPredecessor.keySet()),
			"successorsByPredecessor key set must equal the set of declared non-HEAD predecessors."
		);
		for (final Entry<Integer, TransactionalBitmap> entry : index.successorsByPredecessor.entrySet()) {
			final int predecessorPk = entry.getKey();
			final TransactionalBitmap bucket = entry.getValue();
			// (2) an emptied bucket must have been dropped, keeping the inverse as sparse as the chain set
			assertFalse(
				bucket.isEmpty(),
				() -> "Inverse bucket for " + predecessorPk + " is empty and must have been dropped."
			);
			// (4) the bucket must equal the exact set of elements declaring that predecessor
			final Set<Integer> actual = new TreeSet<>();
			for (final int successorPk : bucket.getArray()) {
				actual.add(successorPk);
			}
			assertEquals(
				expected.get(predecessorPk), actual,
				() -> "Inverse bucket for " + predecessorPk + " must equal its exact declaring set."
			);
		}
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
			assertHeadMarksMatchChains(idx);
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
			assertHeadMarksMatchChains(idx);
		}

		@Test
		@DisplayName("four-arg constructor marks every head when reloading all-singleton chains")
		void shouldMarkEveryHeadWhenReloadingAllSingletonChains() {
			// every run is a singleton head, so bulkLoadWithHeads must mark EVERY position as a head (dense case)
			final AttributeIndexKey attrKey = new AttributeIndexKey(null, "order", null);
			final int[][] chains = new int[][]{{7}, {8}, {9}};
			final Map<Integer, ChainElementState> elementStates = new HashMap<>(6);
			elementStates.put(7, new ChainElementState(7, ChainableType.HEAD_PK, ChainIndex.ElementState.HEAD));
			elementStates.put(8, new ChainElementState(8, ChainableType.HEAD_PK, ChainIndex.ElementState.HEAD));
			elementStates.put(9, new ChainElementState(9, ChainableType.HEAD_PK, ChainIndex.ElementState.HEAD));

			final ChainIndex idx = new ChainIndex(attrKey, chains, elementStates);
			assertEquals(3, idx.chains.size());
			assertHeadMarksMatchChains(idx);

			// the reloaded marks must be usable by findRun: repointing 8 at 7 merges two singletons into one run
			idx.upsertPredecessor(new Predecessor(7), 8);
			assertNotBroken(idx);
			assertHeadMarksMatchChains(idx);
		}
	}

	/**
	 * Tests verifying the chain stays consistent while elements are inserted, reordered and removed/re-inserted in
	 * arbitrary orders.
	 */
	@Nested
	@DisplayName("Building and reordering a consistent chain")
	class PredecessorOrderingTest {

		@DisplayName("Create consistent chain when new items are added in different orders")
		@ParameterizedTest
		@MethodSource("io.evitadb.index.attribute.ChainIndexTest#createPermutationsForPredecessors")
		void shouldTryAddingInDifferentOrders(int[] order) {
			for (int pk : order) {
				final Predecessor predecessor = PREDECESSOR_MAP.get(pk);
				ChainIndexTest.this.index.upsertPredecessor(predecessor, pk);
			}

			assertTrue(ChainIndexTest.this.index.isConsistent());
			assertArrayEquals(EXPECTED_CHAIN, ChainIndexTest.this.index.getUnorderedLookup().getArray());
		}

		@DisplayName("Create consistent chain when randomly reordered")
		@ParameterizedTest
		@MethodSource("io.evitadb.index.attribute.ChainIndexTest#createPermutationsForPredecessors")
		void shouldTryReordering(int[] order) {
			// fill the index initially with the expected chain
			for (int pk : EXPECTED_CHAIN) {
				ChainIndexTest.this.index.upsertPredecessor(PREDECESSOR_MAP.get(pk), pk);
			}
			// now reorder randomly
			for (int i = 0; i < order.length; i++) {
				int pk = order[i];
				final Predecessor predecessor = i == 0 ? new Predecessor() : new Predecessor(order[i - 1]);
				ChainIndexTest.this.index.upsertPredecessor(predecessor, pk);
			}

			assertTrue(ChainIndexTest.this.index.isConsistent(), "Index is inconsistent.");
			assertArrayEquals(order, ChainIndexTest.this.index.getUnorderedLookup().getArray());
		}

		@DisplayName("Create consistent chain when randomly removing elements and returning back")
		@ParameterizedTest
		@MethodSource("io.evitadb.index.attribute.ChainIndexTest#createPermutationsForPredecessors")
		void shouldTryRemovingSingleElementsAndReturnItBack(int[] order) {
			// fill the index initially with the expected chain
			for (int pk : EXPECTED_CHAIN) {
				ChainIndexTest.this.index.upsertPredecessor(PREDECESSOR_MAP.get(pk), pk);
			}
			for (int count = 1; count <= order.length; count++) {
				// remove the first count elements
				for (int i = 0; i < count; i++) {
					ChainIndexTest.this.index.removePredecessor(order[i]);
				}
				// now return them back in
				for (int i = 0; i < count; i++) {
					ChainIndexTest.this.index.upsertPredecessor(PREDECESSOR_MAP.get(order[i]), order[i]);
				}

				assertTrue(ChainIndexTest.this.index.isConsistent(), "Index is inconsistent.");
				assertArrayEquals(EXPECTED_CHAIN, ChainIndexTest.this.index.getUnorderedLookup().getArray());
			}
		}
	}

	/**
	 * Tests for the chain-collapse bookkeeping when elements are inserted into the middle of a chain, when split
	 * sub-chains are reconnected, and when elements are removed.
	 */
	@Nested
	@DisplayName("Chain collapse on insertion and removal")
	class ChainCollapseTest {

		@DisplayName("When adding a new element to the middle of the chain and then correcting it, the index should be consistent")
		@Test
		void shouldIntroduceSplitChainDuringIndexingAndThenCorrectIt() {
			// fill the index initially with the expected chain
			for (int pk : EXPECTED_CHAIN) {
				ChainIndexTest.this.index.upsertPredecessor(PREDECESSOR_MAP.get(pk), pk);
			}
			// now reorder randomly
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(3), 6);
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(6), 4);

			assertTrue(ChainIndexTest.this.index.isConsistent(), "Index is inconsistent.");
			assertArrayEquals(new int[] {1, 2, 3, 6, 4, 5}, ChainIndexTest.this.index.getUnorderedLookup().getArray());
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
				ChainIndexTest.this.index.toString()
			);
		}

		@DisplayName("When introducing a split chain, the longer chains should be favoured")
		@Test
		void shouldIntroduceReconnectSplitChainsFavouringLongerOne() {
			// fill the index initially with the expected chain
			for (int pk : EXPECTED_CHAIN) {
				ChainIndexTest.this.index.upsertPredecessor(PREDECESSOR_MAP.get(pk), pk);
			}

			// now reorder randomly
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(3), 6);
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(3), 7);
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(7), 8);

			assertFalse(ChainIndexTest.this.index.isConsistent());
			assertArrayEquals(new int[] {1, 2, 3, 4, 5, 7, 8, 6}, ChainIndexTest.this.index.getUnorderedLookup().getArray());
			// the split produced three chains: the head chain plus the two orphan sub-chains hanging off element 3
			assertEquals(3, ChainIndexTest.this.index.chains.size());
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
				ChainIndexTest.this.index.toString()
			);
		}

		@DisplayName("When elements are removed the chains are properly collapsed")
		@Test
		void shouldCollapseChainsOnElementRemoval() {
			final int[] initialState = {12, 7, 6, 2, 13, 5, 17, 1, 9};
			for (int i = 0; i < initialState.length; i++) {
				final int pk = initialState[i];
				final Predecessor predecessor = i == 0 ? new Predecessor() : new Predecessor(initialState[i - 1]);
				ChainIndexTest.this.index.upsertPredecessor(predecessor, pk);
			}

			assertTrue(ChainIndexTest.this.index.isConsistent());

			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(12), 6);
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(6), 5);
			ChainIndexTest.this.index.removePredecessor(1);
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(5), 13);
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(13), 7);
			ChainIndexTest.this.index.removePredecessor(17);
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(7), 9);
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(9), 19);
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(19), 3);
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(3), 21);
			ChainIndexTest.this.index.removePredecessor(2);

			assertTrue(ChainIndexTest.this.index.isConsistent());
		}
	}

	/**
	 * Tests for the chain-split that happens when a successor in the middle of a chain is removed. The split is
	 * asserted in its inconsistent intermediate state (before any correcting mutation re-collapses it), which the
	 * basic removal tests never do.
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
	 * Tests for updating an element so that it points at a predecessor which is not currently present in the index
	 * (its predecessor was removed, or has not arrived yet). Depending on the element's position this either splits
	 * its chain into a new orphan successor chain (body element) or merely re-flags the head as an orphan successor
	 * (head element).
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
	 * Tests for the full circular-dependency lifecycle: detection via the {@code toString} dump, creation via the
	 * pure insert path, creation via the update path, demotion back to SUCCESSOR when the perpetrator element is
	 * removed, and the consistency-report behaviour while a circular head is live. These transitions are the reason
	 * this data structure exists.
	 */
	@Nested
	@DisplayName("Circular dependency detection and lifecycle")
	class CircularDependencyTest {

		@DisplayName("Create consistent chain when circular dependency is introduced and broken")
		@Test
		void shouldBreakCircularDependency() {
			// fill the index initially with the expected chain
			for (int pk : EXPECTED_CHAIN) {
				ChainIndexTest.this.index.upsertPredecessor(PREDECESSOR_MAP.get(pk), pk);
			}
			// now reorder randomly
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(3), 1);
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(4), 2);
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(2), 5);
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(1), 4);

			// The four reordered elements form ONE circular chain (the directed cycle 1->4->2->3->1); which element
			// heads that cycle is an implementation-defined rotation choice (see
			// shouldDemoteCircularHeadWhenPerpetratorRemoved), so the contract is only that the index settles into a
			// not-BROKEN, fully-collapsed, inconsistent state holding exactly {1..5} as a length-4 circular run plus
			// the length-1 successor run [5] - not the exact physical rotation the former collapse happened to pick.
			assertFalse(ChainIndexTest.this.index.isConsistent(), "Index is inconsistent.");
			assertNotBroken(ChainIndexTest.this.index);
			assertFullyCollapsed(ChainIndexTest.this.index);
			assertEquals(2, ChainIndexTest.this.index.chains.size(), "A circular run plus the orphan [5].");
			final int[] snapshot = ChainIndexTest.this.index.getUnorderedLookup().getArray();
			final int[] elementSet = snapshot.clone();
			Arrays.sort(elementSet);
			assertArrayEquals(new int[]{1, 2, 3, 4, 5}, elementSet, "The index must still hold exactly {1..5}.");
			// [5] is the sole SUCCESSOR run (predecessor 2 is present but not a tail) and sorts ahead of the CIRCULAR
			// run; the remaining four are the single circular chain over {1,2,3,4} in some rotation
			assertEquals(5, snapshot[0], "The singleton successor run [5] sorts ahead of the circular run.");
			final int[] cycle = Arrays.copyOfRange(snapshot, 1, snapshot.length);
			Arrays.sort(cycle);
			assertArrayEquals(new int[]{1, 2, 3, 4}, cycle, "The remaining four elements form the single circular chain.");

			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(), 3);

			assertTrue(ChainIndexTest.this.index.isConsistent(), "Index is inconsistent.");
			assertArrayEquals(new int[] {3, 1, 4, 2, 5}, ChainIndexTest.this.index.getUnorderedLookup().getArray());
		}

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
	 * Characterization tests that pin the **current** transient ordering produced by {@link ChainIndex} while the
	 * index is in a committed inconsistent ({@code isConsistent() == false}) state.
	 *
	 * The move towards a positional model changes a single {@code upsertPredecessor(x, P_new)} from "drag x's whole
	 * suffix" to "move only x and heal the gap". That change alters ONLY the shape of the transient inconsistent
	 * window; the eventually-consistent single-chain order is byte-identical. These tests record the current (drag)
	 * behaviour so that any future change to the transient-window semantics is detected and consciously rebaselined
	 * here, rather than silently regressing some other test.
	 *
	 * The order captured here is implementation-defined per the contract docs ({@link io.evitadb.dataType.ChainableType}
	 * "semi-consistent" wording and {@link ChainIndex} class JavaDoc), and is NOT a client-visible API contract.
	 * Queries over a {@code Predecessor}-ordered attribute always observe the eventually-consistent single chain.
	 */
	@Nested
	@DisplayName("Inconsistent-window ordering characterization")
	class InconsistentWindowCharacterizationTest {

		@DisplayName("Capture current drag ordering when an element is re-pointed at an absent (forward) predecessor")
		@Test
		void shouldCaptureDragOrderingForDanglingForwardPredecessor() {
			final ChainIndex idx = new ChainIndex(new AttributeIndexKey(null, "order", null));
			// build a fully consistent chain 1 -> 2 -> 3 -> 4 -> 5
			idx.upsertPredecessor(new Predecessor(), 1);
			idx.upsertPredecessor(new Predecessor(1), 2);
			idx.upsertPredecessor(new Predecessor(2), 3);
			idx.upsertPredecessor(new Predecessor(3), 4);
			idx.upsertPredecessor(new Predecessor(4), 5);

			assertTrue(idx.isConsistent(), "Pre-condition: the freshly built chain must be consistent.");
			assertArrayEquals(new int[] {1, 2, 3, 4, 5}, idx.getUnorderedLookup().getArray());

			// now re-point element 3 at a predecessor (999) that is NOT present in the index (dangling / forward ref)
			idx.upsertPredecessor(new Predecessor(999), 3);

			// the index is now in a committed INCONSISTENT window (more than one chain)
			assertFalse(
				idx.isConsistent(),
				"Re-pointing an element at an absent predecessor must leave the index inconsistent."
			);

			// CURRENT (drag) behaviour anchor: the whole suffix [3, 4, 5] is dragged into the orphan chain, head stays [1, 2]
			assertArrayEquals(
				new int[] {1, 2, 3, 4, 5},
				idx.getUnorderedLookup().getArray(),
				"Current drag semantics: head chain [1,2] then orphan suffix chain [3,4,5]."
			);
			// document the internal fragmentation that the redesign will reshape (head chain + orphan suffix chain)
			assertEquals(2, idx.chains.size());
		}
	}

	/**
	 * Tests verifying the full transactional contract of {@link ChainIndex}: STM id invariants and copy-on-commit
	 * behaviour, rollback semantics, and a representative consistent reordering batch applied within a transaction.
	 */
	@Nested
	@DisplayName("Transactional commit and rollback")
	class TransactionalBehaviorTest {

		@Test
		@DisplayName("a consistent reordering batch applied in a transaction commits cleanly")
		void shouldExecuteOperationsInTransactionAndStayConsistent() {
			buildCommittedConsistentChain();
		}

		/**
		 * Tests for STM invariants: id uniqueness, commit behavior, and that the committed original is not mutated.
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
		 * Tests verifying rollback semantics -- transactional changes are discarded and the original index remains
		 * unchanged.
		 */
		@Nested
		@DisplayName("Rollback")
		class RollbackTest {

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
	}

	/**
	 * Regression for a copy-on-write defect in the value-index B+ tree backing the unordered lookup
	 * ({@code TransactionalIntToLongBPlusTree}, reached through {@link io.evitadb.index.array.TransactionalUnorderedIntArray}).
	 *
	 * The value index keeps its leaves in 64-wide blocks. When a transaction split a committed leaf, the split
	 * offspring was a transaction-local node ({@code transactionalLayer == false}). If a later remove underflowed
	 * that transaction-local node below the minimum fill, it borrowed entries from its right sibling via
	 * {@code stealFromRight} - and when that right sibling was a committed (shared) leaf, the borrow shifted the
	 * committed leaf's backing arrays **in place**, silently dropping a run of records from the committed
	 * (pre-transaction) base index. The fix (commit {@code 2c9d189fc}) routes the sibling shift through the
	 * {@code ...ForUpdate} accessors, which decouple a committed sibling inside a transaction (and are in-place
	 * no-ops outside one).
	 *
	 * The scenario below reproduces exactly that topology deterministically: a base chain of 120 elements (large
	 * enough for the value index to span several leaf blocks) is built and committed, then a single transaction
	 * applies a relocation batch whose interleaved moves and removes drive a committed-leaf split followed by an
	 * underflow that borrows from a committed sibling. After commit the committed base must be byte-identical and
	 * consistent.
	 */
	@Nested
	@DisplayName("Transactional B+ tree regressions")
	class TransactionalRegressionTest {

		/**
		 * Initial committed chain order. Each element is chained directly after its predecessor in this array, so the
		 * index is built as one consistent chain of 120 elements - large enough for the value index (keyed by primary
		 * key) to span several 64-wide leaf blocks, which is the precondition for a borrow between leaves to occur.
		 */
		private final int[] initialChainOrder = {
			24, 145, 288, 239, 128, 85, 94, 250, 126, 174, 236, 209, 7, 92, 112, 19, 150, 156, 185, 42, 17, 137, 33,
			281, 193, 49, 285, 105, 101, 221, 175, 23, 170, 184, 228, 48, 18, 226, 269, 167, 238, 154, 247, 120, 157,
			102, 31, 186, 56, 164, 206, 231, 187, 82, 115, 218, 50, 65, 182, 216, 62, 152, 72, 81, 73, 210, 74, 39,
			297, 283, 26, 38, 172, 29, 113, 195, 44, 263, 131, 267, 46, 179, 284, 249, 45, 47, 260, 32, 148, 176, 79,
			219, 60, 146, 134, 30, 264, 223, 181, 220, 243, 235, 55, 41, 3, 282, 40, 177, 76, 153, 130, 64, 262, 275,
			9, 214, 16, 133, 295, 69
		};

		@Test
		@DisplayName("committed base index survives a transactional relocation batch without in-place corruption")
		void shouldNotCorruptCommittedBaseWhenTransactionStealsFromCommittedSibling() {
			final ChainIndex index = new ChainIndex(new AttributeIndexKey(null, "a", null));
			for (int i = 0; i < this.initialChainOrder.length; i++) {
				final int pk = this.initialChainOrder[i];
				final Predecessor predecessor = i == 0 ? Predecessor.HEAD : new Predecessor(this.initialChainOrder[i - 1]);
				index.upsertPredecessor(predecessor, pk);
			}
			assertEquals(
				ConsistencyState.CONSISTENT, index.getConsistencyReport().state(),
				"pre-condition: the base chain must build consistently"
			);
			final int[] baseArrayBefore = index.getUnorderedLookup().getArray();

			assertStateAfterCommit(
				index,
				original -> {
					for (final Op op : this.relocationBatch) {
						op.applyTo(original);
					}
				},
				(original, committed) -> {
					// the committed base (original) must NOT have been mutated in place by the transaction
					assertArrayEquals(
						baseArrayBefore, original.getUnorderedLookup().getArray(),
						"committed base array mutated in place by the transaction"
					);
					assertEquals(
						ConsistencyState.CONSISTENT, original.getConsistencyReport().state(),
						() -> "committed base corrupted by the transaction: " + original.getConsistencyReport()
					);
					// the committed result itself must be consistent too
					assertEquals(
						ConsistencyState.CONSISTENT, committed.getConsistencyReport().state(),
						() -> "committed result inconsistent: " + committed.getConsistencyReport()
					);
					// the transactional merge/re-wrap must preserve the exact, sparse inverse on the committed copy
					assertInverseIsExactAndSparse(committed);
				}
			);
		}

		/**
		 * A single mutation in the relocation batch: either a {@link #move(int, int)} (re-point {@code pk} after
		 * {@code predecessorPk}) or a {@link #remove(int)} (drop {@code pk}). Modelled explicitly so the batch below
		 * reads as a sequence of named operations rather than an opaque numeric table.
		 */
		private record Op(boolean removal, int predecessorPk, int pk) {
			void applyTo(@Nonnull ChainIndex index) {
				if (this.removal) {
					index.removePredecessor(this.pk);
				} else {
					index.upsertPredecessor(new Predecessor(this.predecessorPk), this.pk);
				}
			}
		}

		/**
		 * Re-points {@code pk} so that it directly follows {@code predecessorPk} in the chain.
		 */
		@Nonnull
		private static Op move(int predecessorPk, int pk) {
			return new Op(false, predecessorPk, pk);
		}

		/**
		 * Removes {@code pk} from the chain.
		 */
		@Nonnull
		private static Op remove(int pk) {
			return new Op(true, 0, pk);
		}

		/**
		 * The deterministic relocation batch applied inside a single transaction. The interleaved moves and removes
		 * drive the value-index B+ tree through a committed-leaf split followed by an underflow that borrows from a
		 * committed sibling leaf (steal-from-right), which is the exact path the fix guards.
		 */
		private final Op[] relocationBatch = {
			move(24, 145), move(145, 63), move(63, 141), move(141, 85), remove(130), move(85, 148), move(148, 262),
			move(262, 217), move(217, 174), move(174, 19), move(19, 31), move(31, 92), remove(30), move(92, 33),
			move(33, 236), move(236, 74), remove(263), move(74, 156), move(156, 185), move(185, 288), remove(39),
			move(288, 195), move(195, 137), move(137, 172), move(172, 282), move(282, 120), move(120, 285),
			move(285, 17), move(17, 101), move(101, 175), move(175, 176), move(176, 231), move(231, 184),
			move(184, 228), move(228, 69), move(69, 18), move(18, 226), move(226, 73), move(73, 238), move(238, 42),
			move(42, 193), move(193, 131), move(131, 152), move(152, 7), move(7, 41), move(41, 284), move(284, 164),
			move(164, 267), move(267, 187), move(187, 115), move(115, 105), move(105, 50), move(50, 210),
			move(210, 26), move(26, 216), move(216, 102), remove(62), move(102, 72), move(72, 167), remove(82),
			move(167, 81), move(81, 138), move(138, 100), remove(206), move(100, 150), move(150, 283), move(283, 170),
			move(170, 38), remove(154), move(38, 112), move(112, 29), move(29, 113), move(113, 218), remove(269),
			move(218, 44), move(44, 157), move(157, 182), move(182, 46), move(46, 179), move(179, 56), remove(221),
			move(56, 249), move(249, 45), move(45, 47), move(47, 260), move(260, 32), move(32, 94), move(94, 23),
			move(23, 79), move(79, 219), remove(49), move(219, 60), remove(209), move(60, 146), move(146, 134),
			move(134, 264), move(264, 223), move(223, 181), move(181, 220), move(220, 243), move(243, 235),
			move(235, 55), move(55, 186), remove(128), move(186, 3), move(3, 281), move(281, 40), move(40, 177),
			move(177, 76), move(76, 153), move(153, 64), move(64, 250), move(250, 275), move(275, 9), move(9, 214),
			move(214, 16), move(16, 133), move(133, 295), move(295, 48), move(48, 126), move(126, 163), move(163, 159),
			move(159, 280), move(280, 297), move(297, 239), move(239, 75), move(75, 247), move(247, 278), move(278, 97),
			move(97, 88), move(88, 211), move(211, 65)
		};
	}

	/**
	 * Tests verifying non-transactional mode: operations applied directly without a transaction context.
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
	@DisplayName("Sorted records supplier")
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
		@DisplayName("repeated supplier requests return fresh, equivalent instances (no cross-call caching)")
		void shouldReturnFreshEquivalentSupplierPerCall() {
			populateStandardChain();

			final SortedRecordsSupplier first =
				(SortedRecordsSupplier) ChainIndexTest.this.index
					.getAscendingOrderRecordsSupplier();
			final SortedRecordsSupplier second =
				(SortedRecordsSupplier) ChainIndexTest.this.index
					.getAscendingOrderRecordsSupplier();

			// a consistent chain is served straight from the live element tree, so each call builds a fresh
			// lightweight wrapper rather than memoizing one instance across calls
			assertNotSame(first, second);
			assertArrayEquals(first.getSortedRecordIds(), second.getSortedRecordIds());
			assertArrayEquals(EXPECTED_CHAIN, second.getSortedRecordIds());
		}

		@Test
		@DisplayName("ascending and descending suppliers expose the same record ids, distinct cache ids, and reveal mutations")
		void shouldExposeDistinctIdsAndRevealMutationsAcrossDirections() {
			populateStandardChain();

			final SortedRecordsSupplier asc =
				(SortedRecordsSupplier) ChainIndexTest.this.index.getAscendingOrderRecordsSupplier();
			final SortedRecordsSupplier desc =
				(SortedRecordsSupplier) ChainIndexTest.this.index.getDescendingOrderRecordsSupplier();

			// both directions expose the same direction-independent record-id set, each read from the element tree
			assertArrayEquals(
				asc.getAllRecords().getArray(), desc.getAllRecords().getArray(),
				"Ascending and descending suppliers must expose the same record-id set."
			);
			// the two orderings carry distinct, stable cache identities so they never collide downstream
			assertNotEquals(
				asc.getTransactionalId(), desc.getTransactionalId(),
				"Ascending and descending suppliers must expose distinct transactional ids."
			);
			assertArrayEquals(EXPECTED_CHAIN, asc.getSortedRecordIds());
			assertArrayEquals(new int[]{5, 4, 3, 2, 1}, desc.getSortedRecordIds());

			// a mutation must be reflected by freshly requested suppliers (they read the live element tree)
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(5), 6);

			final SortedRecordsSupplier ascAfter =
				(SortedRecordsSupplier) ChainIndexTest.this.index.getAscendingOrderRecordsSupplier();
			final SortedRecordsSupplier descAfter =
				(SortedRecordsSupplier) ChainIndexTest.this.index.getDescendingOrderRecordsSupplier();

			assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6}, ascAfter.getSortedRecordIds());
			assertArrayEquals(new int[]{6, 5, 4, 3, 2, 1}, descAfter.getSortedRecordIds());
		}

		@Test
		@DisplayName("descending supplier requested first still yields the correct ascending order for a later read")
		void shouldYieldCorrectOrderWhenDescendingSupplierRequestedFirst() {
			populateStandardChain();

			// request the descending supplier FIRST so any request-order dependency would surface
			final SortedRecordsSupplier desc =
				(SortedRecordsSupplier) ChainIndexTest.this.index.getDescendingOrderRecordsSupplier();
			final SortedRecordsSupplier asc =
				(SortedRecordsSupplier) ChainIndexTest.this.index.getAscendingOrderRecordsSupplier();

			// both directions expose the same record-id set regardless of which was requested first
			assertArrayEquals(
				desc.getAllRecords().getArray(), asc.getAllRecords().getArray(),
				"Ascending and descending suppliers must expose the same record-id set."
			);
			// the two orderings still carry distinct, stable cache identities
			assertNotEquals(
				asc.getTransactionalId(), desc.getTransactionalId(),
				"Ascending and descending suppliers must expose distinct transactional ids."
			);
			// the ascending order is correct even though the descending supplier was requested first
			assertArrayEquals(EXPECTED_CHAIN, asc.getSortedRecordIds());
			assertArrayEquals(new int[]{5, 4, 3, 2, 1}, desc.getSortedRecordIds());
		}

		@Test
		@DisplayName("suppliers reflect the new order after a transactional commit")
		void shouldRefreshSuppliersAfterTransactionalCommit() {
			populateStandardChain();
			// read the committed supplier order before the transaction
			assertArrayEquals(
				EXPECTED_CHAIN,
				((SortedRecordsSupplier) ChainIndexTest.this.index.getAscendingOrderRecordsSupplier())
					.getSortedRecordIds()
			);

			assertStateAfterCommit(
				ChainIndexTest.this.index,
				original -> original.upsertPredecessor(new Predecessor(5), 6),
				(original, committed) -> {
					final SortedRecordsSupplier asc =
						(SortedRecordsSupplier) committed.getAscendingOrderRecordsSupplier();
					final SortedRecordsSupplier desc =
						(SortedRecordsSupplier) committed.getDescendingOrderRecordsSupplier();
					assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6}, asc.getSortedRecordIds());
					assertArrayEquals(new int[]{6, 5, 4, 3, 2, 1}, desc.getSortedRecordIds());
					// both directions expose the same record-id set on the merged copy
					assertArrayEquals(asc.getAllRecords().getArray(), desc.getAllRecords().getArray());
				}
			);
		}

		@Test
		@DisplayName("suppliers on the original index are unchanged after a transactional rollback")
		void shouldKeepSuppliersAfterTransactionalRollback() {
			populateStandardChain();
			// pre-warm the committed supplier caches so the memoized lookup/bitmap fields are live before the transaction
			assertArrayEquals(
				EXPECTED_CHAIN,
				((SortedRecordsSupplier) ChainIndexTest.this.index.getAscendingOrderRecordsSupplier())
					.getSortedRecordIds()
			);

			assertStateAfterRollback(
				ChainIndexTest.this.index,
				original -> original.upsertPredecessor(new Predecessor(5), 6),
				(original, committed) -> {
					assertNull(committed);
					assertArrayEquals(
						EXPECTED_CHAIN,
						((SortedRecordsSupplier) original.getAscendingOrderRecordsSupplier()).getSortedRecordIds()
					);
				}
			);
		}
	}

	/**
	 * Tests verifying dirty flag and storage part creation behavior.
	 */
	@Nested
	@DisplayName("Dirty flag and storage part")
	class DirtyFlagStoragePartTest {

		@Test
		@DisplayName("appendStorageParts emits a part after upsert")
		void shouldEmitStoragePartAfterUpsert() {
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(), 1);

			final TrappedChanges trappedChanges = new TrappedChanges();
			ChainIndexTest.this.index.appendStorageParts(1, trappedChanges);
			assertEquals(1, trappedChanges.getTrappedChangesCount());
			final StoragePart part = trappedChanges.getTrappedChangesIterator().next();
			assertInstanceOf(ChainIndexStoragePart.class, part);
		}

		@Test
		@DisplayName("appendStorageParts emits nothing on a fresh (non-dirty) index")
		void shouldEmitNothingOnFreshIndex() {
			final TrappedChanges trappedChanges = new TrappedChanges();
			ChainIndexTest.this.index.appendStorageParts(1, trappedChanges);
			assertEquals(0, trappedChanges.getTrappedChangesCount());
		}

		@Test
		@DisplayName("resetDirty makes appendStorageParts emit nothing")
		void shouldEmitNothingAfterResetDirty() {
			ChainIndexTest.this.index.upsertPredecessor(new Predecessor(), 1);
			// a dirty index emits its part(s)
			final TrappedChanges firstChanges = new TrappedChanges();
			ChainIndexTest.this.index.appendStorageParts(1, firstChanges);
			assertEquals(1, firstChanges.getTrappedChangesCount());

			ChainIndexTest.this.index.resetDirty();

			// after resetDirty the (now clean) index emits nothing
			final TrappedChanges secondChanges = new TrappedChanges();
			ChainIndexTest.this.index.appendStorageParts(1, secondChanges);
			assertEquals(0, secondChanges.getTrappedChangesCount());
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
	 * Tests verifying consistency-report states and the detection of deliberately corrupt configurations. Corrupt
	 * configurations are built via the deserialization constructor; each parameter set targets a distinct
	 * error-detection branch of {@link ChainIndex#getConsistencyReport()} that the public mutation API can never
	 * produce - see {@link ChainIndexTest#brokenConfigurations()}.
	 */
	@Nested
	@DisplayName("Consistency and broken-state reporting")
	class ConsistencyReportingTest {

		@Test
		@DisplayName("clean transactional batch produces a CONSISTENT report listing the single chain")
		void shouldGenerateConsistencyReport() {
			buildCommittedConsistentChain();

			assertEquals(
				"""
				## Chains

					- 23, 26, 8, 3, 2, 4, 7, 6, 9, 10, 5, 11

				## No errors detected.""",
				ChainIndexTest.this.index.getConsistencyReport().report()
			);
		}

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

		@Test
		@DisplayName("report is BROKEN when the successor inverse drops a declared successor")
		void shouldReportBrokenWhenInverseMissesADeclaredSuccessor() {
			final ChainIndex idx = new ChainIndex(new AttributeIndexKey(null, "inverse", null));
			idx.upsertPredecessor(Predecessor.HEAD, 1);
			idx.upsertPredecessor(new Predecessor(1), 2);
			assertNotBroken(idx);

			// desync ONLY the inverse: 2 still declares predecessor 1 in `predecessors`, but drop 2 from 1's
			// successor bucket so the inverse no longer records the declared edge
			idx.successorsByPredecessor.get(1).remove(2);

			final ConsistencyReport report = idx.getConsistencyReport();
			assertEquals(ConsistencyState.BROKEN, report.state());
			assertTrue(
				report.report().contains("missing from that predecessor's successor inverse"),
				() -> "Report must flag the successor dropped from the inverse:\n" + report.report()
			);
		}

		@Test
		@DisplayName("report is BROKEN when the successor inverse holds a stray successor")
		void shouldReportBrokenWhenInverseHoldsAStraySuccessor() {
			final ChainIndex idx = new ChainIndex(new AttributeIndexKey(null, "inverse", null));
			idx.upsertPredecessor(Predecessor.HEAD, 1);
			idx.upsertPredecessor(new Predecessor(1), 2);
			assertNotBroken(idx);

			// inject a stray successor into 1's inverse bucket - 999 declares no predecessor at all in `predecessors`
			idx.successorsByPredecessor.computeIfAbsent(1, p -> new TransactionalBitmap()).add(999);

			final ConsistencyReport report = idx.getConsistencyReport();
			assertEquals(ConsistencyState.BROKEN, report.state());
			assertTrue(
				report.report().contains("its declared predecessor is"),
				() -> "Report must flag the stray successor in the inverse:\n" + report.report()
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
				// invariant 1b - the tree head marks stay in sync with the chain head set at every mutation site
				assertHeadMarksMatchChains(idx);
				// invariant 1c - the seeded work-queue collapse leaves nothing the old full-rescan would have merged
				assertFullyCollapsed(idx);
				// invariant 1d - the successorsByPredecessor inverse stays an exact, sparse mirror of predecessors
				assertInverseIsExactAndSparse(idx);
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
			assertHeadMarksMatchChains(idx);
			assertEquals(ConsistencyState.CONSISTENT, idx.getConsistencyReport().state());
			assertArrayEquals(survivors, idx.getUnorderedLookup().getArray());
		}

		/**
		 * Returns a random currently-present primary key, or -1 when none is present.
		 */
		private static int pickPresent(@Nonnull Random rnd, @Nonnull boolean[] live, int n) {
			return pickMatching(rnd, live, n, -1, true);
		}

		/**
		 * Returns a random currently-absent primary key from the 1..n range, or -1 when all are present.
		 */
		private static int pickAbsent(@Nonnull Random rnd, @Nonnull boolean[] live, int n) {
			return pickMatching(rnd, live, n, -1, false);
		}

		/**
		 * Returns a random present primary key other than `exclude`, or -1 when none qualifies.
		 */
		private static int pickPresentOtherThan(@Nonnull Random rnd, @Nonnull boolean[] live, int n, int exclude) {
			return pickMatching(rnd, live, n, exclude, true);
		}

		/**
		 * Reservoir-samples a single primary key from 1..n whose liveness equals `wantPresent` and which is not
		 * equal to `exclude`. Returns -1 when no primary key qualifies.
		 */
		private static int pickMatching(
			@Nonnull Random rnd, @Nonnull boolean[] live, int n, int exclude, boolean wantPresent) {
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
		private static Set<Integer> liveSet(@Nonnull boolean[] live, int n) {
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
		private static Set<Integer> lookupSet(@Nonnull ChainIndex index) {
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
		private static int[] liveArray(@Nonnull boolean[] live, int n) {
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

	/**
	 * Targets the seeded work-queue {@link ChainIndex#collapse(IntArrayList)} directly: the
	 * fork/star shape it must handle without spurious merges (and without the former `O(C)` rescan), the
	 * absent-then-present cascade it must chase to a single chain, and a larger randomized differential that asserts -
	 * after every mutation - the index is left with nothing the former full-rescan collapse would have merged
	 * ({@link #assertFullyCollapsed}).
	 */
	@Nested
	@DisplayName("Work-queue collapse")
	class WorkQueueCollapseTest {

		@Test
		@DisplayName("fork/star declaring a present non-tail predecessor stays uncollapsed with no spurious merges")
		void shouldNotMergeForkStarOnNonTailPredecessor() {
			final ChainIndex idx = new ChainIndex(new AttributeIndexKey(null, "fork", null));
			// chain [1, 2]: 1 is a head, 2 is its tail, so 1 is NOT a run tail
			idx.upsertPredecessor(Predecessor.HEAD, 1);
			idx.upsertPredecessor(new Predecessor(1), 2);
			// a star of successors all declaring 1 (a present, non-tail element) - each must form its own orphan run
			final int forks = 50;
			for (int i = 0; i < forks; i++) {
				idx.upsertPredecessor(new Predecessor(1), 100 + i);
				// after every insert the work-queue must have found nothing mergeable (1 is not a tail)
				assertFullyCollapsed(idx);
				assertNotBroken(idx);
			}
			// [1,2] plus one run per fork - nothing collapsed onto the non-tail predecessor
			assertEquals(forks + 1, idx.chains.size(), "Fork/star on a non-tail predecessor must not merge.");
			assertHeadMarksMatchChains(idx);
		}

		@Test
		@DisplayName("a fork collapses onto its predecessor only once that predecessor becomes a run tail")
		void shouldCollapseForkOncePredecessorBecomesTail() {
			final ChainIndex idx = new ChainIndex(new AttributeIndexKey(null, "fork", null));
			idx.upsertPredecessor(Predecessor.HEAD, 1);
			idx.upsertPredecessor(new Predecessor(1), 2);
			idx.upsertPredecessor(new Predecessor(1), 10);
			idx.upsertPredecessor(new Predecessor(1), 11);
			// 1 is not a tail (2 follows it) - the two forks stand as orphans
			assertEquals(3, idx.chains.size());
			assertFullyCollapsed(idx);
			// remove 2 so 1 becomes a run tail - exactly one waiting fork must now collapse onto it
			idx.removePredecessor(2);
			assertFullyCollapsed(idx);
			assertNotBroken(idx);
			assertHeadMarksMatchChains(idx);
			// 1 can host a single positional successor; the other fork remains a standing orphan
			assertEquals(2, idx.chains.size(), "Exactly one fork may merge onto the newly exposed tail.");
		}

		@Test
		@DisplayName("absent-then-present predecessors cascade-collapse into a single consistent chain")
		void shouldCascadeCollapseWhenPredecessorsArriveLate() {
			final ChainIndex idx = new ChainIndex(new AttributeIndexKey(null, "cascade", null));
			// declare 1 as head, then two successors whose (even) predecessors are not present yet
			idx.upsertPredecessor(Predecessor.HEAD, 1);
			idx.upsertPredecessor(new Predecessor(2), 3);
			idx.upsertPredecessor(new Predecessor(4), 5);
			assertFullyCollapsed(idx);
			assertEquals(3, idx.chains.size(), "Absent predecessors leave three standing orphan runs.");
			// now supply the missing members in order; each arrival is a new tail its waiting successor merges onto,
			// cascading the chain forward
			idx.upsertPredecessor(new Predecessor(1), 2);
			assertFullyCollapsed(idx);
			idx.upsertPredecessor(new Predecessor(3), 4);
			assertFullyCollapsed(idx);
			idx.upsertPredecessor(new Predecessor(5), 6);
			assertFullyCollapsed(idx);
			assertTrue(idx.isConsistent(), "All predecessors present - the chain must fully collapse.");
			assertArrayEquals(new int[]{1, 2, 3, 4, 5, 6}, idx.getUnorderedLookup().getArray());
			assertHeadMarksMatchChains(idx);
		}

		@Test
		@DisplayName("one mutation drives a multi-run cascade collapse to a single chain in a single drain")
		void shouldCascadeMultipleMergesInASingleMutation() {
			final ChainIndex idx = new ChainIndex(new AttributeIndexKey(null, "cascade", null));
			// present head run [1, 2, 3] whose tail is 3
			idx.upsertPredecessor(Predecessor.HEAD, 1);
			idx.upsertPredecessor(new Predecessor(1), 2);
			idx.upsertPredecessor(new Predecessor(2), 3);
			// two orphan successor runs stacked on absent predecessors: 10 waits on 20, 20 waits on the still-absent 30
			idx.upsertPredecessor(new Predecessor(20), 10);
			idx.upsertPredecessor(new Predecessor(30), 20);
			assertFullyCollapsed(idx);
			assertInverseIsExactAndSparse(idx);

			// 30 arrives declaring the present tail 3, so a SINGLE upsert must drain the whole collapse work-queue to
			// a fixpoint: the head run absorbs 30 and becomes longer, then every orphan run that (transitively) waited
			// on 30 cascades onto the growing chain via the newly exposed tails - more than one run merges in this one
			// mutation, which the seeded work-queue must chase without a full rescan
			idx.upsertPredecessor(new Predecessor(3), 30);

			assertFullyCollapsed(idx);
			assertNotBroken(idx);
			assertHeadMarksMatchChains(idx);
			assertInverseIsExactAndSparse(idx);
			assertEquals(1, idx.chains.size(), "The cascade must leave exactly one fully merged chain.");
			// order beyond the set is implementation-defined; pin only that every element survives exactly once
			final int[] elementSet = idx.getUnorderedLookup().getArray().clone();
			Arrays.sort(elementSet);
			assertArrayEquals(new int[]{1, 2, 3, 10, 20, 30}, elementSet);
		}

		@Test
		@DisplayName("large randomized churn is left fully collapsed after every mutation")
		void shouldLeaveIndexFullyCollapsedUnderLargeChurn() {
			final int n = 40;
			final ChainIndex idx = new ChainIndex(new AttributeIndexKey(null, "churn", null));
			final boolean[] live = new boolean[n + 1];
			final Random rnd = new Random(0x5EED1234L);
			final int operations = 3_000;
			for (int op = 0; op < operations; op++) {
				final int pk = 1 + rnd.nextInt(n);
				final int roll = rnd.nextInt(10);
				if (roll < 3 && live[pk]) {
					idx.removePredecessor(pk);
					live[pk] = false;
				} else {
					// point at HEAD, or at another (possibly still-absent) element; never at itself
					final int anchor = rnd.nextInt(n + 1);
					final ChainableType predecessor = anchor == 0 || anchor == pk
						? Predecessor.HEAD : new Predecessor(anchor);
					idx.upsertPredecessor(predecessor, pk);
					live[pk] = true;
				}
				// the decisive invariant: the seeded collapse reaches the same fixpoint as the old full rescan every
				// single time (nothing mergeable left behind)
				assertFullyCollapsed(idx);
				assertNotBroken(idx);
				assertHeadMarksMatchChains(idx);
				assertInverseIsExactAndSparse(idx);
			}
		}
	}

	/**
	 * Exercises the two non-adjacent {@link ChainIndex} run-merge relocate branches (the adjacent branch is the
	 * ubiquitous default covered everywhere). A relocate (remove + re-insert) resets a moved run's head to non-head, so
	 * these tests pin that {@code mergeRunAfter}'s idempotent {@code markAsHead(target)} / {@code unmarkAsHead(follower)}
	 * restores the exact head-mark set - asserted directly via {@link #assertHeadMarksMatchChains}.
	 */
	@Nested
	@DisplayName("Run merge relocation")
	class RunMergeRelocationTest {

		@Test
		@DisplayName("relocates the shorter follower after the longer target when merging non-adjacent runs")
		void shouldRelocateShorterFollowerAfterLongerTarget() {
			final ChainIndex idx = new ChainIndex(new AttributeIndexKey(null, "order", null));
			// follower orphan [20] (pred 2, absent), spacer orphan [50] (pred 88, absent), head [1]
			idx.upsertPredecessor(new Predecessor(2), 20);
			idx.upsertPredecessor(new Predecessor(88), 50);
			idx.upsertPredecessor(new Predecessor(), 1);
			// extending [1] with 2 makes 20's predecessor (2) present at a run tail; collapse merges [20] after [1,2].
			// followerLength(1) <= targetLength(2) => the follower run [20] is the one relocated
			idx.upsertPredecessor(new Predecessor(1), 2);

			assertNotBroken(idx);
			assertHeadMarksMatchChains(idx);
			// the merged run [1,2,20] plus the still-orphan spacer [50] leaves exactly two chains
			assertEquals(2, idx.chains.size());
			assertSortedContentEquals(new int[]{1, 2, 20, 50}, idx);
		}

		@Test
		@DisplayName("relocates the shorter target before the longer follower when merging non-adjacent runs")
		void shouldRelocateShorterTargetBeforeLongerFollower() {
			final ChainIndex idx = new ChainIndex(new AttributeIndexKey(null, "order", null));
			// long follower orphan [20,21,22] (head 20 pred 2, absent), spacer orphan [50], short head [1]
			idx.upsertPredecessor(new Predecessor(2), 20);
			idx.upsertPredecessor(new Predecessor(20), 21);
			idx.upsertPredecessor(new Predecessor(21), 22);
			idx.upsertPredecessor(new Predecessor(88), 50);
			idx.upsertPredecessor(new Predecessor(), 1);
			// extending [1] with 2 makes 20's predecessor present; collapse merges [20,21,22] after [1,2].
			// followerLength(3) > targetLength(2) => the target run [1,2] is relocated - its head mark is reset by the
			// relocate and must be restored by mergeRunAfter's markAsHead(target)
			idx.upsertPredecessor(new Predecessor(1), 2);

			assertNotBroken(idx);
			assertHeadMarksMatchChains(idx);
			// the merged run [1,2,20,21,22] plus the still-orphan spacer [50] leaves exactly two chains
			assertEquals(2, idx.chains.size());
			assertSortedContentEquals(new int[]{1, 2, 20, 21, 22, 50}, idx);
		}

		/**
		 * Asserts the index holds exactly the expected element set (order-independent).
		 */
		private static void assertSortedContentEquals(@Nonnull int[] expectedSorted, @Nonnull ChainIndex idx) {
			final int[] actual = idx.getUnorderedLookup().getArray().clone();
			Arrays.sort(actual);
			assertArrayEquals(expectedSorted, actual);
		}
	}

	/**
	 * Verifies the boundary-stable PAGED reload path ({@link ChainIndex#fromPersistedPages}) WITHOUT going through the
	 * storage-part loader: for a live index we manually harvest its persisted leaf pages straight from the element
	 * array's {@code leafPageHandles()} (record ids + head mask, plus each head's predecessor read from the live
	 * {@code predecessors} map), rebuild via {@code fromPersistedPages} and assert the reconstruction is IDENTICAL —
	 * same physical element order, same chains, same predecessors, same successor inverse — and that the freshly
	 * reloaded index emits NO pages on a first flush (the zero-emission proxy).
	 */
	@Nested
	@DisplayName("PAGED reload round-trip (fromPersistedPages)")
	class PagedReloadRoundTripTest {

		@Test
		@DisplayName("a single consistent chain spanning several pages reloads identically")
		void shouldReloadSingleLongChainSpanningMultiplePages() {
			final ChainIndex idx = new ChainIndex(new AttributeIndexKey(null, "reload-single", null));
			// 3000 > 2*PAGE_RECORDS (1024) => at least 3 leaf pages; one consistent head-first chain 1 -> 2 -> ... -> 3000
			for (int pk = 1; pk <= 3000; pk++) {
				idx.upsertPredecessor(pk == 1 ? new Predecessor() : new Predecessor(pk - 1), pk);
			}
			assertTrue(idx.isConsistent());
			final List<ChainIndexLeafPagePart> pages = harvestPages(idx);
			assertTrue(pages.size() >= 3, "The chain must span at least three leaf pages.");
			// the boundary-non-head case: every page after the first must begin with a NON-head body record whose
			// predecessor is the last record of the previous page (reconstructed positionally across the boundary)
			for (int p = 1; p < pages.size(); p++) {
				final ChainIndexLeafPagePart page = pages.get(p);
				assertEquals(0L, page.getHeadWords()[0] & 1L, "Page " + p + " must start with a non-head body record.");
			}
			assertReloadIdentical(idx, pages, "single long chain", true);
		}

		@Test
		@DisplayName("thousands of disconnected singleton orphans reload identically")
		void shouldReloadManySingletonOrphans() {
			final ChainIndex idx = new ChainIndex(new AttributeIndexKey(null, "reload-orphans", null));
			// each element points at a permanently-absent predecessor => a length-1 SUCCESSOR orphan run (no merges);
			// 3000 orphans span >=3 pages and every position is a chain head (the head-at-page-boundary case)
			for (int pk = 1; pk <= 3000; pk++) {
				idx.upsertPredecessor(new Predecessor(pk + 1_000_000), pk);
			}
			assertFalse(idx.isConsistent());
			assertEquals(3000, idx.chains.size());
			final List<ChainIndexLeafPagePart> pages = harvestPages(idx);
			assertTrue(pages.size() >= 3, "The orphans must span at least three leaf pages.");
			// order among equal (state, length) runs is tie-broken by map iteration order => the semi-consistent lookup
			// order is not a stable oracle here; the physical order + chains + predecessors + inverse still are
			assertReloadIdentical(idx, pages, "singleton orphans", false);
		}

		@Test
		@DisplayName("a run whose head sits in a later page with its body crossing the next boundary reloads identically")
		void shouldReloadRunHeadInLaterPageWithBodyCrossingBoundary() {
			// build a deterministic two-run inconsistent index via the deserialization (array) constructor so the leaf
			// boundaries are the fully-filled 1024-record pages: run A = 1..1100 (fills page 0, spills into page 1),
			// run B's head 1101 therefore lands at global position 1100 (page 1) with its body crossing into page 2
			final int runALength = 1100;
			final int runBLength = 1400;
			final int[] runA = new int[runALength];
			for (int i = 0; i < runALength; i++) {
				runA[i] = i + 1;
			}
			final int[] runB = new int[runBLength];
			for (int i = 0; i < runBLength; i++) {
				runB[i] = runALength + 1 + i;
			}
			final Map<Integer, ChainElementState> states = new HashMap<>();
			states.put(runA[0], head(runA[0]));
			for (int i = 1; i < runALength; i++) {
				states.put(runA[i], succ(runA[0], runA[i - 1]));
			}
			// run B's head points at a permanently-absent predecessor => a SUCCESSOR head (valid, un-collapsible)
			states.put(runB[0], succ(runB[0], 9_999_999));
			for (int i = 1; i < runBLength; i++) {
				states.put(runB[i], succ(runB[0], runB[i - 1]));
			}
			final ChainIndex idx = new ChainIndex(
				new AttributeIndexKey(null, "reload-two-run", null), new int[][]{runA, runB}, states
			);
			assertNotBroken(idx);
			assertFalse(idx.isConsistent());

			final List<ChainIndexLeafPagePart> pages = harvestPages(idx);
			assertTrue(pages.size() >= 3, "The two runs must span at least three leaf pages.");
			// run B's head must NOT be in the first page (its head bit falls in a later page) and page 1 must start
			// with a non-head boundary body record from run A
			assertEquals(0L, pages.get(1).getHeadWords()[0] & 1L, "Page 1 must start with run A's non-head body.");
			assertReloadIdentical(idx, pages, "run head in later page", true);
		}

		@Test
		@DisplayName("a run head whose predecessor is a present record in another run reloads as SUCCESSOR, not CIRCULAR")
		void shouldReloadRunHeadWhosePredecessorIsPresentInAnotherRun() {
			// two runs on a single page: run A = 1..5 (a genuine HEAD chain) and run B = 101..103 whose head declares its
			// predecessor as record 2 - a record that IS present, but lives inside run A (not run B). Because record 2's
			// global position sits OUTSIDE run B's own positional span, the head is a cross-run SUCCESSOR (a fork off
			// record 2), never a CIRCULAR head - the reload must recompute this the same way the live index does
			final int[] runA = {1, 2, 3, 4, 5};
			final int[] runB = {101, 102, 103};
			final int runBHead = runB[0];
			final int presentExternalPredecessor = runA[1]; // record 2, a mid-run-A element that is present
			final Map<Integer, ChainElementState> states = new HashMap<>();
			states.put(runA[0], head(runA[0]));
			for (int i = 1; i < runA.length; i++) {
				states.put(runA[i], succ(runA[0], runA[i - 1]));
			}
			// run B's head points at the PRESENT record 2 (which belongs to run A); its body follows the head normally
			states.put(runBHead, succ(runBHead, presentExternalPredecessor));
			for (int i = 1; i < runB.length; i++) {
				states.put(runB[i], succ(runBHead, runB[i - 1]));
			}
			final ChainIndex idx = new ChainIndex(
				new AttributeIndexKey(null, "reload-cross-run-successor", null), new int[][]{runA, runB}, states
			);
			assertNotBroken(idx);
			assertFalse(idx.isConsistent(), "two forked runs must leave the index inconsistent");
			// guard the fixture: the live index classifies run B's head as a SUCCESSOR (present-but-external predecessor)
			assertEquals(ElementState.SUCCESSOR, idx.getElementState(runBHead).state());

			// the reload must RECOMPUTE run B's head from its persisted predecessor and land on SUCCESSOR (not CIRCULAR):
			// predecessor 2's global position falls in run A, outside run B's own span, so no cycle is ever inferred
			assertReloadIdentical(idx, harvestPages(idx), "successor head with present external predecessor", false);
		}

		@Test
		@DisplayName("a circular chain reloads identically (CIRCULAR head state recomputed)")
		void shouldReloadCircularChain() {
			final ChainIndex idx = new ChainIndex(new AttributeIndexKey(null, "reload-circular", null));
			// 1 -> 2 -> 3 with 3 closing back onto 1 forms one circular run headed by 1 (CIRCULAR)
			idx.upsertPredecessor(new Predecessor(3), 1);
			idx.upsertPredecessor(new Predecessor(1), 2);
			idx.upsertPredecessor(new Predecessor(2), 3);
			assertEquals(ElementState.CIRCULAR, idx.getElementState(1).state());
			assertTrue(idx.isConsistent(), "A single circular run is a consistent (single-chain) index.");
			assertReloadIdentical(idx, harvestPages(idx), "circular chain", true);
		}

		@Test
		@DisplayName("an empty index reloads to an empty index emitting no pages")
		void shouldReloadEmptyIndex() {
			final ChainIndex idx = new ChainIndex(new AttributeIndexKey(null, "reload-empty", null));
			final List<ChainIndexLeafPagePart> pages = harvestPages(idx);
			assertTrue(pages.isEmpty(), "An empty index has no leaf pages.");
			final ChainIndex reconstructed = ChainIndex.fromPersistedPages(
				idx.getReferenceKey(), idx.getAttributeIndexKey(), pages, highWaterOf(pages)
			);
			assertEquals(0, reconstructed.elements.getLength());
			assertEquals(0, reconstructed.chains.size());
			assertEquals(0, reconstructed.predecessors.size());
			assertTrue(reconstructed.elements.collectChangedPages().isEmpty(), "Empty reload must emit no pages.");
		}

		/**
		 * Harvests the persisted leaf-page representation of a live index straight from the element array's page
		 * handles, WITHOUT going through {@code appendStorageParts}: each handle yields the page's ordered record ids
		 * and head-mask words; the per-head predecessor pk (aligned with the set head bits) is read from the live
		 * {@code predecessors} map. The head-mask is sliced down to the {@code ceil(recordIds.length / 64)} meaningful
		 * words the persisted page carries. Page sequences are synthesized `0, 1, 2, …` in leaf order (a never-flushed
		 * live index leaves them {@code UNASSIGNED}), mirroring what a real first flush would allocate, so the reload's
		 * page-stream registry restores from valid (non-negative) sequences.
		 */
		@Nonnull
		private static List<ChainIndexLeafPagePart> harvestPages(@Nonnull ChainIndex index) {
			final List<UnorderedLookupTree.LeafPageHandle> handles = index.elements.leafPageHandles();
			final List<ChainIndexLeafPagePart> pages = new ArrayList<>(handles.size());
			int pageSequence = 0;
			for (final UnorderedLookupTree.LeafPageHandle handle : handles) {
				final int[] recordIds = handle.recordIds();
				final long[] fullMask = handle.headMask();
				final int words = (recordIds.length + 63) / 64;
				final long[] headWords = Arrays.copyOf(fullMask, words);
				int headCount = 0;
				for (int i = 0; i < recordIds.length; i++) {
					if (((headWords[i >>> 6] >>> (i & 63)) & 1L) != 0L) {
						headCount++;
					}
				}
				final int[] headPredecessorPks = new int[headCount];
				int cursor = 0;
				for (int i = 0; i < recordIds.length; i++) {
					if (((headWords[i >>> 6] >>> (i & 63)) & 1L) != 0L) {
						headPredecessorPks[cursor++] = index.predecessors.get(recordIds[i]);
					}
				}
				// read-path ctor: fromPersistedPages consumes only pageSequence / recordIds / headWords / predecessors
				pages.add(new ChainIndexLeafPagePart(
					0, pageSequence++, recordIds, headWords, headPredecessorPks, 0L
				));
			}
			return pages;
		}

		/**
		 * The page-stream high-water a real flush would persist for these harvested pages: the maximum assigned page
		 * sequence, or `-1` (the `NO_PAGE` sentinel) when the index has no pages. Passed to
		 * {@link ChainIndex#fromPersistedPages} so its registry restore sees a coherent high-water envelope.
		 */
		private static int highWaterOf(@Nonnull List<ChainIndexLeafPagePart> pages) {
			int highWater = -1;
			for (final ChainIndexLeafPagePart page : pages) {
				highWater = Math.max(highWater, page.getPageSequence());
			}
			return highWater;
		}

		/**
		 * Reloads `original` from the harvested `pages` and asserts the reconstruction is identical: same physical
		 * element order (always deterministic), same chains (head set + each descriptor's length + state), same
		 * predecessors, same successor inverse, not BROKEN, head marks coherent, and a first flush emits no pages. When
		 * `deterministicLookupOrder` the semi-consistent {@code getUnorderedLookup} order is compared exactly; otherwise
		 * (equal (state, length) ties make that order map-iteration-dependent) only the element multiset is compared.
		 */
		private static void assertReloadIdentical(
			@Nonnull ChainIndex original,
			@Nonnull List<ChainIndexLeafPagePart> pages,
			@Nonnull String label,
			boolean deterministicLookupOrder
		) {
			final ChainIndex reconstructed = ChainIndex.fromPersistedPages(
				original.getReferenceKey(), original.getAttributeIndexKey(), pages, highWaterOf(pages)
			);

			assertNotBroken(reconstructed);
			assertHeadMarksMatchChains(reconstructed);
			assertEquals(original.isConsistent(), reconstructed.isConsistent(), label + ": consistency flag");

			// physical element order is the core reconstruction guarantee - always deterministic and identical
			assertArrayEquals(
				original.elements.getArray(), reconstructed.elements.getArray(), label + ": physical element order"
			);

			// chains: identical head set, and identical (length, state) per head
			assertEquals(original.chains.keySet(), reconstructed.chains.keySet(), label + ": chain head set");
			for (final Entry<Integer, ChainDescriptor> entry : original.chains.entrySet()) {
				final Integer headPk = entry.getKey();
				final ChainDescriptor expected = entry.getValue();
				final ChainDescriptor actual = reconstructed.chains.get(headPk);
				assertEquals(expected, actual, label + ": descriptor for head " + headPk);
			}

			// predecessors: identical map
			assertEquals(
				original.predecessors.keySet(), reconstructed.predecessors.keySet(), label + ": predecessor key set"
			);
			for (final Entry<Integer, Integer> entry : original.predecessors.entrySet()) {
				final Integer pk = entry.getKey();
				assertEquals(
					entry.getValue(), reconstructed.predecessors.get(pk), label + ": predecessor of " + pk
				);
			}

			// successorsByPredecessor: identical inverse (key set + bitmap contents per key)
			assertEquals(
				original.successorsByPredecessor.keySet(), reconstructed.successorsByPredecessor.keySet(),
				label + ": successor-inverse key set"
			);
			for (final Entry<Integer, TransactionalBitmap> entry : original.successorsByPredecessor.entrySet()) {
				final Integer predPk = entry.getKey();
				assertArrayEquals(
					entry.getValue().getArray(),
					reconstructed.successorsByPredecessor.get(predPk).getArray(),
					label + ": successor inverse for predecessor " + predPk
				);
			}

			// semi-consistent lookup order (exact only when tie-free)
			final int[] originalLookup = original.getUnorderedLookup().getArray();
			final int[] reconstructedLookup = reconstructed.getUnorderedLookup().getArray();
			if (deterministicLookupOrder) {
				assertArrayEquals(originalLookup, reconstructedLookup, label + ": semi-consistent lookup order");
			} else {
				final int[] originalSorted = originalLookup.clone();
				final int[] reconstructedSorted = reconstructedLookup.clone();
				Arrays.sort(originalSorted);
				Arrays.sort(reconstructedSorted);
				assertArrayEquals(originalSorted, reconstructedSorted, label + ": semi-consistent lookup element set");
			}

			// zero-emission proxy: the freshly reloaded index re-emits no pages on a first flush
			assertTrue(
				reconstructed.elements.collectChangedPages().isEmpty(),
				label + ": a freshly reloaded index must emit no pages"
			);
		}
	}

	/**
	 * Exercises the FULL granular flush + reload path through the real {@link ChainIndex#appendStorageParts} write seam
	 * (not the hand-harvested pages of {@link PagedReloadRoundTripTest}): a multi-page index is flushed into an in-memory
	 * {@link ChainStore} that models the append-only OffsetIndex (leaf pages superseded by page sequence, the SINGLE/PAGED
	 * root superseded at its stable PK, freed pages removed), reloaded exactly as {@code AttributeIndexLoader.fetchChain}
	 * would, and asserted identical.
	 *
	 * A "commit" is modeled by {@link ChainStore#reload}: the reloaded index restores its page-stream live baseline via
	 * {@link ChainIndex#fromPersistedPages} (as a disk round-trip would), so the NEXT flush's freed-page / removal
	 * bookkeeping is correct without the transactional commit-merge publish step — the same trick
	 * {@code SortIndexOwnerPagingRoundTripTest} uses (reload-between-flushes). The tests prove: a large chain round-trips
	 * identically and re-emits NOTHING on the first post-load flush (true zero-emission); a single-element mutation
	 * re-emits only the touched leaf; and the SINGLE⇄PAGED transitions leak no pages (PAGED→SINGLE removes every prior leaf
	 * page, SINGLE→PAGED supersedes the inline root).
	 */
	@Nested
	@DisplayName("Granular flush + reload round-trip (appendStorageParts)")
	class GranularFlushReloadRoundTripTest {

		/** Owning entity index pk used for the flush emission (arbitrary; only the CHAIN sub-index identity matters). */
		private static final int ENTITY_INDEX_PK = 42;

		@Test
		@DisplayName("a large multi-page chain flushes PAGED, reloads identically, and re-emits nothing on the next flush")
		void shouldFlushReloadLargeChainAndReEmitNothing() {
			final AttributeIndexKey key = new AttributeIndexKey(null, "flush-large", null);
			final ChainIndex idx = new ChainIndex(key);
			appendConsecutiveChain(idx, 1, 3200); // > 3 leaves (leafCapacity = 1024)
			assertTrue(idx.isConsistent());

			final ChainStore store = new ChainStore();
			store.flush(idx);
			assertTrue(store.root.isPaged(), "a >3-page chain must persist as PAGED");
			final int livePages = store.root.getPageSequencesOrThrowException().length;
			assertTrue(livePages >= 3, "the chain must span at least three live leaf pages");
			assertEquals(livePages, store.lastLeafPageCount, "a first PAGED flush must emit every live leaf page");
			assertEquals(0, store.lastRemovalCount, "a first PAGED flush frees no pages");

			final ChainIndex reloaded = store.reload(idx.getReferenceKey(), key);
			assertReloadMatches(idx, reloaded, "large chain");

			// TRUE zero-emission: the freshly reloaded (clean) index emits NOTHING on its first flush - no leaf pages, no root
			assertZeroEmission(reloaded);
		}

		@Test
		@DisplayName("after reload a single-element mutation re-emits only the touched leaf, not the whole index")
		void shouldReEmitOnlyTheTouchedLeafAfterReload() {
			final AttributeIndexKey key = new AttributeIndexKey(null, "flush-incremental", null);
			final ChainIndex idx = new ChainIndex(key);
			appendConsecutiveChain(idx, 1, 3200);

			final ChainStore store = new ChainStore();
			store.flush(idx);
			final int totalPages = store.root.getPageSequencesOrThrowException().length;
			assertTrue(totalPages >= 3);

			// commit = reload (restores the live-page baseline), then mutate one element (append a new tail into the last leaf)
			final ChainIndex reloaded = store.reload(idx.getReferenceKey(), key);
			reloaded.upsertPredecessor(new Predecessor(3200), 3201);

			store.flush(reloaded);
			assertTrue(store.root.isPaged(), "a still-large chain stays PAGED");
			assertTrue(
				store.lastLeafPageCount >= 1 && store.lastLeafPageCount < totalPages,
				"a single-element mutation must re-emit only the touched leaf(s), far fewer than all " + totalPages + " pages"
			);
			assertEquals(0, store.lastRemovalCount, "a tail append frees no pages");

			final ChainIndex reReloaded = store.reload(reloaded.getReferenceKey(), key);
			assertReloadMatches(reloaded, reReloaded, "incremental");
			assertZeroEmission(reReloaded);
		}

		@Test
		@DisplayName("growing a SINGLE chain past one leaf flips to PAGED, superseding the inline root (no orphan)")
		void shouldGrowFromSingleToPagedWithoutOrphan() {
			final AttributeIndexKey key = new AttributeIndexKey(null, "grow", null);
			final ChainIndex small = new ChainIndex(key);
			appendConsecutiveChain(small, 1, 500); // < one leaf -> SINGLE
			final ChainStore store = new ChainStore();
			store.flush(small);
			assertFalse(store.root.isPaged(), "a <1-page chain must persist SINGLE");
			assertEquals(0, store.lastLeafPageCount, "a SINGLE flush emits no leaf pages");

			// commit = reload the SINGLE root, then grow past one leaf so the tree flips to PAGED
			final ChainIndex grown = store.reload(small.getReferenceKey(), key);
			appendConsecutiveChain(grown, 501, 2500);
			store.flush(grown);
			assertTrue(store.root.isPaged(), "growing past one leaf must flip the root to PAGED");
			// the PAGED root reuses the SINGLE root's storage-part PK (both a ChainIndexStoragePart of type CHAIN => an
			// identical computeUniquePartId), so it SUPERSEDES the inline record - the store keeps a single root slot, no
			// orphan remains
			assertEquals(AttributeIndexType.CHAIN, store.root.getIndexType());

			final ChainIndex reloaded = store.reload(grown.getReferenceKey(), key);
			assertReloadMatches(grown, reloaded, "single->paged");
			assertZeroEmission(reloaded);
		}

		@Test
		@DisplayName("shrinking a PAGED chain below one leaf collapses to SINGLE, removing every prior leaf page")
		void shouldCollapseFromPagedToSingleRemovingEveryPriorLeafPage() {
			final AttributeIndexKey key = new AttributeIndexKey(null, "shrink", null);
			final ChainIndex big = new ChainIndex(key);
			appendConsecutiveChain(big, 1, 2500); // > two leaves -> PAGED
			final ChainStore store = new ChainStore();
			store.flush(big);
			assertTrue(store.root.isPaged());
			final int livePagesBeforeCollapse = store.root.getPageSequencesOrThrowException().length;
			assertTrue(livePagesBeforeCollapse >= 2);

			// commit = reload (restores the live-page baseline), then shrink from the TAIL so the chain stays consistent and
			// every trailing leaf empties and is dropped, collapsing the tree to a single leaf (SINGLE)
			final ChainIndex shrunk = store.reload(big.getReferenceKey(), key);
			for (int pk = 2500; pk >= 201; pk--) {
				shrunk.removePredecessor(pk);
			}
			store.flush(shrunk);
			assertFalse(store.root.isPaged(), "shrinking below one leaf must collapse to the SINGLE root");
			assertEquals(0, store.lastLeafPageCount, "a SINGLE collapse emits no leaf pages");
			assertEquals(
				livePagesBeforeCollapse, store.lastRemovalCount,
				"a PAGED->SINGLE collapse must remove every previously-live leaf page (no leak)"
			);

			final ChainIndex reloaded = store.reload(shrunk.getReferenceKey(), key);
			assertReloadMatches(shrunk, reloaded, "paged->single");
			assertZeroEmission(reloaded);
		}

		@Test
		@DisplayName("a mid-life shrink that frees a leaf but stays PAGED emits a leaf-page removal and keeps a PAGED root")
		void shouldEmitLeafPageRemovalWhenMidLifeShrinkFreesLeafButStaysPaged() {
			final AttributeIndexKey key = new AttributeIndexKey(null, "mid-life-shrink", null);
			final ChainIndex big = new ChainIndex(key);
			appendConsecutiveChain(big, 1, 3200); // > 3 leaves (leafCapacity = 1024)
			final ChainStore store = new ChainStore();
			store.flush(big);
			assertTrue(store.root.isPaged(), "a >3-page chain must persist as PAGED");
			final int livePagesBeforeShrink = store.root.getPageSequencesOrThrowException().length;
			assertTrue(livePagesBeforeShrink >= 3, "the chain must span at least three live leaf pages");

			// commit = reload (restores the live-page baseline), then shrink from the TAIL so the chain stays ONE
			// consistent chain (1 -> 2 -> ... -> 1300) yet empties and drops its trailing leaf pages - a MID-LIFE shrink
			// that stays comfortably above one leaf, so the root stays PAGED while at least one freed page is removed
			final ChainIndex shrunk = store.reload(big.getReferenceKey(), key);
			for (int pk = 3200; pk >= 1301; pk--) {
				shrunk.removePredecessor(pk);
			}
			store.flush(shrunk);

			assertTrue(store.root.isPaged(), "1300 elements still span more than one leaf, so the root stays PAGED");
			final int livePagesAfterShrink = store.root.getPageSequencesOrThrowException().length;
			assertTrue(livePagesAfterShrink >= 2, "1300 elements must still occupy at least two live leaf pages");
			assertTrue(
				livePagesAfterShrink < livePagesBeforeShrink, "the shrink must free at least one previously-live leaf page"
			);
			// rebalancing makes the exact removal count brittle - assert only that at least one freed page was removed and
			// at least one surviving (touched) leaf was re-emitted, never exact counts
			assertTrue(store.lastRemovalCount > 0, "a mid-life shrink that empties a trailing leaf must emit a removal");
			assertTrue(store.lastLeafPageCount >= 1, "the touched surviving leaf(s) must be re-emitted");

			final ChainIndex reloaded = store.reload(shrunk.getReferenceKey(), key);
			assertReloadMatches(shrunk, reloaded, "mid-life shrink");
			assertZeroEmission(reloaded);
		}

		@Test
		@DisplayName(
			"a leaf dropped by one warm-up flush and never published before the next warm-up flush still reports " +
				"the removal and re-emits the changed PAGED root"
		)
		void shouldReportFreedLeafPagesWhenTwoWarmUpFlushesRunBackToBackWithoutAnIntermediatePublish() {
			// every other test in this nested class models a "commit" as flush + store.reload(...): the reload rebuilds
			// the index via ChainIndex#fromPersistedPages, which reseeds the page-stream registry from scratch and so
			// incidentally sidesteps the very bug this test targets. A warm-up (bulk-load) flush never goes through a
			// reload or a transactional commit-merge (see ChainIndex#createCopyWithMergedTransactionalMemory, the only
			// place that publishes a staged page set) - appendStorageParts() is simply called again, flush after flush,
			// directly on the SAME instance. This test reproduces exactly that: two flushes back to back, no reload (and
			// therefore no publish) in between.
			final AttributeIndexKey key = new AttributeIndexKey(null, "warm-up-merge", null);
			final ChainIndex big = new ChainIndex(key);
			appendConsecutiveChain(big, 1, 3200); // > 3 leaves (leafCapacity = 1024)

			final ChainStore store = new ChainStore();
			// FIRST warm-up flush: stages the live leaf-page set (never published).
			store.flush(big);
			assertTrue(store.root.isPaged(), "a >3-page chain must persist as PAGED");
			final int[] pagesBeforeShrink = store.root.getPageSequencesOrThrowException();
			assertTrue(pagesBeforeShrink.length >= 3, "the chain must span at least three live leaf pages");

			// shrink from the TAIL so the chain stays ONE consistent chain (1 -> 2 -> ... -> 1300) yet empties and drops
			// its trailing leaf pages - the same mutation the mid-life-shrink test above uses, proven to free at least
			// one leaf page while staying comfortably above one leaf (root stays PAGED); applied directly to `big`
			// (not a reloaded copy) so nothing publishes the first flush's staged baseline in between
			for (int pk = 3200; pk >= 1301; pk--) {
				big.removePredecessor(pk);
			}
			// SECOND warm-up flush: collects again on the very same instance with the first flush's set still staged.
			store.flush(big);

			assertTrue(store.root.isPaged(), "1300 elements still span more than one leaf, so the root must stay PAGED");
			assertTrue(
				store.lastRemovalCount > 0,
				"the leaf page the shrink dropped must be reported as freed, not silently kept on the persisted root!"
			);
			final int[] pagesAfterShrink = store.root.getPageSequencesOrThrowException();
			assertTrue(
				pagesAfterShrink.length < pagesBeforeShrink.length,
				"the persisted root must be re-emitted to reflect the drop, not skipped as unchanged!"
			);
		}

		/**
		 * Appends a consecutive head-first chain `fromPk -> fromPk+1 -> … -> toPk` to `idx` (pk 1 is the head; every other
		 * pk follows its immediate predecessor), one {@link ChainIndex#upsertPredecessor} per element.
		 */
		private static void appendConsecutiveChain(@Nonnull ChainIndex idx, int fromPk, int toPk) {
			for (int pk = fromPk; pk <= toPk; pk++) {
				idx.upsertPredecessor(pk == 1 ? new Predecessor() : new Predecessor(pk - 1), pk);
			}
		}

		/**
		 * Asserts `actual` (a reloaded index) matches `expected`: not BROKEN, same physical element order, same chain head
		 * set and per-head descriptor, same predecessors and same consistency flag.
		 */
		private static void assertReloadMatches(
			@Nonnull ChainIndex expected, @Nonnull ChainIndex actual, @Nonnull String label) {
			assertNotBroken(actual);
			assertArrayEquals(
				expected.elements.getArray(), actual.elements.getArray(), label + ": physical element order"
			);
			assertEquals(expected.chains.keySet(), actual.chains.keySet(), label + ": chain head set");
			for (final Entry<Integer, ChainDescriptor> entry : expected.chains.entrySet()) {
				final Integer headPk = entry.getKey();
				assertEquals(
					entry.getValue(), actual.chains.get(headPk), label + ": descriptor for head " + headPk
				);
			}
			assertEquals(expected.predecessors.keySet(), actual.predecessors.keySet(), label + ": predecessor key set");
			for (final Entry<Integer, Integer> entry : expected.predecessors.entrySet()) {
				final Integer pk = entry.getKey();
				assertEquals(
					entry.getValue(), actual.predecessors.get(pk), label + ": predecessor of " + pk
				);
			}
			assertEquals(expected.isConsistent(), actual.isConsistent(), label + ": consistency flag");
		}

		/**
		 * Asserts the freshly reloaded (clean) index emits ZERO storage parts on its first flush - no leaf pages, no root:
		 * the load leaves the index non-dirty so the flush is suppressed entirely (true zero-emission).
		 */
		private static void assertZeroEmission(@Nonnull ChainIndex reloaded) {
			final TrappedChanges sink = new TrappedChanges();
			reloaded.appendStorageParts(ENTITY_INDEX_PK, sink);
			assertEquals(
				0, sink.getTrappedChangesCount(),
				"a freshly reloaded index must emit zero storage parts on its first flush"
			);
		}

		/**
		 * In-memory model of the append-only OffsetIndex for one chain sub-index: leaf pages superseded by page sequence,
		 * the SINGLE/PAGED root superseded at its stable PK (one slot). {@link #reload} rebuilds the index exactly as
		 * {@code AttributeIndexLoader.fetchChain} would (PAGED via the root's ordered page list, else the inline SINGLE
		 * part), and — being a real reload — restores the page-stream live baseline so a subsequent flush's freed-page
		 * bookkeeping is correct.
		 */
		private static final class ChainStore {
			private final Map<Integer, ChainIndexLeafPagePart> leafPages = new HashMap<>();
			@Nullable private ChainIndexStoragePart root;
			private int lastLeafPageCount;
			private int lastRemovalCount;

			/**
			 * Flushes `idx` through {@link ChainIndex#appendStorageParts} and folds the emitted parts into this store,
			 * mirroring the writer: leaf pages are stored (superseded) by page sequence, removals are counted and the
			 * SINGLE/PAGED root replaces the prior root. Records this flush's leaf-page / removal counts for assertions.
			 */
			void flush(@Nonnull ChainIndex idx) {
				final TrappedChanges sink = new TrappedChanges();
				idx.appendStorageParts(ENTITY_INDEX_PK, sink);
				this.lastLeafPageCount = 0;
				this.lastRemovalCount = 0;
				final Iterator<StoragePart> it = sink.getTrappedChangesIterator();
				while (it.hasNext()) {
					final StoragePart part = it.next();
					if (part instanceof ChainIndexLeafPagePart leafPage) {
						this.leafPages.put(leafPage.getPageSequence(), leafPage);
						this.lastLeafPageCount++;
					} else if (part instanceof ChainIndexLeafPageRemoval) {
						this.lastRemovalCount++;
					} else if (part instanceof ChainIndexStoragePart rootPart) {
						this.root = rootPart;
					}
				}
				idx.resetDirty();
			}

			/**
			 * Reloads the stored state exactly as {@code AttributeIndexLoader.fetchChain} does: a PAGED root's ordered leaf
			 * pages are looked up (each must be present) and handed to {@link ChainIndex#fromPersistedPages}; a SINGLE root is
			 * rebuilt from its inline chains + element states.
			 */
			@Nonnull
			ChainIndex reload(@Nullable RepresentativeReferenceKey refKey, @Nonnull AttributeIndexKey attrKey) {
				final ChainIndexStoragePart rootPart = this.root;
				assertNotNull(rootPart, "nothing has been flushed into the store yet");
				if (rootPart.isPaged()) {
					final int[] orderedPageSequences = rootPart.getPageSequencesOrThrowException();
					final List<ChainIndexLeafPagePart> pages = new ArrayList<>(orderedPageSequences.length);
					for (final int pageSequence : orderedPageSequences) {
						final ChainIndexLeafPagePart page = this.leafPages.get(pageSequence);
						assertNotNull(page, "live leaf page " + pageSequence + " must be present in the store");
						pages.add(page);
					}
					return ChainIndex.fromPersistedPages(refKey, attrKey, pages, rootPart.getHighWaterPageSequence());
				}
				return new ChainIndex(refKey, attrKey, rootPart.getChains(), rootPart.getElementStates());
			}
		}
	}

}
