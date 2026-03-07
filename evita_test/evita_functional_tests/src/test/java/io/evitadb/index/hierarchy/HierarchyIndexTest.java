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

package io.evitadb.index.hierarchy;

import io.evitadb.api.query.order.TraversalMode;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.EmptyBitmap;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.index.hierarchy.predicate.MatchNodeIdHierarchyFilteringPredicate;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.HierarchyIndexStoragePart;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link HierarchyIndex} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class HierarchyIndexTest implements TimeBoundedTestSupport {
	private HierarchyIndex hierarchyIndex;

	/**
	 * Creates base tree of following structure:
	 *
	 * 6/
	 * ├─ 3/
	 * │  ├─ 1/
	 * │  ├─ 2/
	 * ├─ 8/
	 * │  ├─ 9/
	 * │  │  ├─ 10/
	 * │  │  ├─ 11/
	 * │  │  ├─ 12/
	 * 7/
	 * ├─ 4/
	 * ├─ 5/
	 * │  ├─ 0/
	 */
	@BeforeEach
	void setUp() {
		this.hierarchyIndex = new HierarchyIndex();
		this.hierarchyIndex.addNode(0, 5);
		this.hierarchyIndex.addNode(1, 3);
		this.hierarchyIndex.addNode(2, 3);
		this.hierarchyIndex.addNode(3, 6);
		this.hierarchyIndex.addNode(4, 7);
		this.hierarchyIndex.addNode(5, 7);
		this.hierarchyIndex.addNode(6, null);
		this.hierarchyIndex.addNode(7, null);
		this.hierarchyIndex.addNode(8, 6);
		this.hierarchyIndex.addNode(9, 8);
		this.hierarchyIndex.addNode(10, 9);
		this.hierarchyIndex.addNode(11, 9);
		this.hierarchyIndex.addNode(12, 9);
	}

	@Nested
	@DisplayName("Ordered listing from roots")
	class TraversalOrderListingTest {

		@Test
		void shouldListEntireTree() {
			final Bitmap nodeIds = HierarchyIndexTest.this.hierarchyIndex.listHierarchyNodesFromRoot();
			assertArrayEquals(
				new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12},
				nodeIds.getArray()
			);
		}

		@Test
		void shouldReturnBreadthFirstArray() {
			assertArrayEquals(
				new int[] {6, 7, 3, 8, 4, 5, 1, 2, 9, 0, 10, 11, 12},
				HierarchyIndexTest.this.hierarchyIndex.listHierarchyNodesFromRoot(TraversalMode.BREADTH_FIRST, UnaryOperator.identity()).getArray()
			);
		}

		@Test
		void shouldReturnDepthFirstArray() {
			assertArrayEquals(
				new int[] {6, 3, 1, 2, 8, 9, 10, 11, 12, 7, 4, 5, 0},
				HierarchyIndexTest.this.hierarchyIndex.listHierarchyNodesFromRoot(TraversalMode.DEPTH_FIRST, UnaryOperator.identity()).getArray()
			);
		}

		@Test
		void shouldReturnBreadthFirstArrayReversed() {
			assertArrayEquals(
				new int[] {7, 6, 5, 4, 8, 3, 0, 9, 2, 1, 12, 11, 10},
				HierarchyIndexTest.this.hierarchyIndex.listHierarchyNodesFromRoot(TraversalMode.BREADTH_FIRST, ArrayUtils::reverse).getArray()
			);
		}

		@Test
		void shouldReturnDepthFirstArrayReversed() {
			assertArrayEquals(
				new int[] {7, 5, 0, 4, 6, 8, 9, 12, 11, 10, 3, 2, 1},
				HierarchyIndexTest.this.hierarchyIndex.listHierarchyNodesFromRoot(TraversalMode.DEPTH_FIRST, ArrayUtils::reverse).getArray()
			);
		}
	}

	@Nested
	@DisplayName("Node navigation")
	class NodeNavigationTest {

		@Test
		void shouldComputeAllHierarchyNodesExceptOrphans() {
			HierarchyIndexTest.this.hierarchyIndex.addNode(100, 1000);
			HierarchyIndexTest.this.hierarchyIndex.addNode(101, 1000);
			HierarchyIndexTest.this.hierarchyIndex.addNode(103, 999);

			final Formula allNodesFormula = HierarchyIndexTest.this.hierarchyIndex.getAllHierarchyNodesFormula();
			assertArrayEquals(
				new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12},
				allNodesFormula.compute().getArray()
			);
		}

		@Test
		void shouldFindParentNode() {
			assertEquals(6, HierarchyIndexTest.this.hierarchyIndex.getParentNode(3).getAsInt());
			assertTrue(HierarchyIndexTest.this.hierarchyIndex.getParentNode(6).isEmpty());
			assertThrows(EvitaInvalidUsageException.class, () -> HierarchyIndexTest.this.hierarchyIndex.getParentNode(99));
		}
	}

	@Nested
	@DisplayName("Visitor traversal")
	class VisitorTraversalTest {

		@Test
		void shouldTraverseRootNodes() {
			final StringBuilder nodeIds = new StringBuilder("|");
			final StringBuilder levels = new StringBuilder("|");
			final StringBuilder distances = new StringBuilder("|");
			HierarchyIndexTest.this.hierarchyIndex.traverseHierarchy(
				(node, level, distance, childrenTraverser) -> {
					nodeIds.append(node.entityPrimaryKey()).append("|");
					levels.append(level).append("|");
					distances.append(distance).append("|");
				}
			);
			assertEquals("|6|7|", nodeIds.toString());
			assertEquals("|1|1|", levels.toString());
			assertEquals("|1|1|", distances.toString());
		}

		@Test
		void shouldTraverseEntireTreeTopDown() {
			final StringBuilder nodeIds = new StringBuilder("|");
			final StringBuilder levels = new StringBuilder("|");
			final StringBuilder distances = new StringBuilder("|");
			HierarchyIndexTest.this.hierarchyIndex.traverseHierarchy(
				(node, level, distance, childrenTraverser) -> {
					nodeIds.append(node.entityPrimaryKey()).append("|");
					levels.append(level).append("|");
					distances.append(distance).append("|");
					childrenTraverser.run();
				}
			);
			assertEquals("|6|3|1|2|8|9|10|11|12|7|4|5|0|", nodeIds.toString());
			assertEquals("|1|2|3|3|2|3|4|4|4|1|2|2|3|", levels.toString());
			assertEquals("|1|2|3|3|2|3|4|4|4|1|2|2|3|", distances.toString());
		}

		@Test
		void shouldTraverseEntireTreeBottomUp() {
			final StringBuilder nodeIds = new StringBuilder("|");
			final StringBuilder levels = new StringBuilder("|");
			final StringBuilder distances = new StringBuilder("|");
			HierarchyIndexTest.this.hierarchyIndex.traverseHierarchy(
				(node, level, distance, childrenTraverser) -> {
					childrenTraverser.run();
					nodeIds.append(node.entityPrimaryKey()).append("|");
					levels.append(level).append("|");
					distances.append(distance).append("|");
				}
			);
			assertEquals("|1|2|3|10|11|12|9|8|6|4|0|5|7|", nodeIds.toString());
			assertEquals("|3|3|2|4|4|4|3|2|1|2|3|2|1|", levels.toString());
			assertEquals("|3|3|2|4|4|4|3|2|1|2|3|2|1|", distances.toString());
		}

		@Test
		void shouldTraverseSubTreeExcludingInnerSubTree() {
			final StringBuilder nodeIds = new StringBuilder("|");
			final StringBuilder levels = new StringBuilder("|");
			final StringBuilder distances = new StringBuilder("|");
			HierarchyIndexTest.this.hierarchyIndex.traverseHierarchyFromNode(
				(node, level, distance, childrenTraverser) -> {
					childrenTraverser.run();
					nodeIds.append(node.entityPrimaryKey()).append("|");
					levels.append(level).append("|");
					distances.append(distance).append("|");
				},
				6, false, new MatchNodeIdHierarchyFilteringPredicate(9).negate()
			);
			assertEquals("|1|2|3|8|6|", nodeIds.toString());
			assertEquals("|3|3|2|2|1|", levels.toString());
			assertEquals("|2|2|1|1|0|", distances.toString());
		}

		@Test
		void shouldTraverseEntireTreeFromParent() {
			final StringBuilder nodeIds = new StringBuilder("|");
			final StringBuilder levels = new StringBuilder("|");
			final StringBuilder distances = new StringBuilder("|");
			HierarchyIndexTest.this.hierarchyIndex.traverseHierarchyFromNode(
				(node, level, distance, childrenTraverser) -> {
					childrenTraverser.run();
					nodeIds.append(node.entityPrimaryKey()).append("|");
					levels.append(level).append("|");
					distances.append(distance).append("|");
				},
				9, false
			);
			assertEquals("|10|11|12|9|", nodeIds.toString());
			assertEquals("|4|4|4|3|", levels.toString());
			assertEquals("|1|1|1|0|", distances.toString());
		}

		@Test
		void shouldTraverseEntireTreeToRoot() {
			final StringBuilder nodeIds = new StringBuilder("|");
			final StringBuilder levels = new StringBuilder("|");
			final StringBuilder distances = new StringBuilder("|");
			HierarchyIndexTest.this.hierarchyIndex.traverseHierarchyToRoot(
				(node, level, distance, childrenTraverser) -> {
					childrenTraverser.run();
					nodeIds.append(node.entityPrimaryKey()).append("|");
					levels.append(level).append("|");
					distances.append(distance).append("|");
				},
				12
			);
			assertEquals("|6|8|9|12|", nodeIds.toString());
			assertEquals("|1|2|3|4|", levels.toString());
			assertEquals("|3|2|1|0|", distances.toString());
		}

		@Test
		void shouldTraverseEntireTreeFromParentExcludingIt() {
			final StringBuilder nodeIds = new StringBuilder("|");
			final StringBuilder levels = new StringBuilder("|");
			final StringBuilder distances = new StringBuilder("|");
			HierarchyIndexTest.this.hierarchyIndex.traverseHierarchyFromNode(
				(node, level, distance, childrenTraverser) -> {
					childrenTraverser.run();
					nodeIds.append(node.entityPrimaryKey()).append("|");
					levels.append(level).append("|");
					distances.append(distance).append("|");
				},
				9, true
			);
			assertEquals("|10|11|12|", nodeIds.toString());
			assertEquals("|3|3|3|", levels.toString());
			assertEquals("|1|1|1|", distances.toString());
		}
	}

	@Nested
	@DisplayName("Filtered listing from roots and ancestor traversal")
	class FilteredListingTest {

		@Test
		void shouldListEntireTreeExcludingParts() {
			final Bitmap nodeIds = HierarchyIndexTest.this.hierarchyIndex.listHierarchyNodesFromRoot(
				new MatchNodeIdHierarchyFilteringPredicate(9)
					.or(new MatchNodeIdHierarchyFilteringPredicate(5))
					.negate()
			);
			assertArrayEquals(
				new int[]{1, 2, 3, 4, 6, 7, 8},
				nodeIds.getArray()
			);
		}

		@Test
		void shouldListNodesIncludingParents() {
			// Create a bitmap with nodes 1, 10, and 0
			final Bitmap inputNodes = new BaseBitmap(1, 10, 0);

			// Call the method being tested
			final Bitmap resultNodes = HierarchyIndexTest.this.hierarchyIndex.listNodesIncludingParents(inputNodes);

			// Verify the result contains all the expected nodes
			// Node 1 and its parent 3 and grandparent 6
			// Node 10 and its parent 9, grandparent 8, and great-grandparent 6
			// Node 0 and its parent 5 and grandparent 7
			assertArrayEquals(
				new int[]{0, 1, 3, 5, 6, 7, 8, 9, 10},
				resultNodes.getArray()
			);
		}
	}

	@Nested
	@DisplayName("Subtree listing")
	class SubTreeListingTest {

		@Test
		void shouldListSubTreeIncludingItself() {
			final Bitmap nodeIds = HierarchyIndexTest.this.hierarchyIndex.listHierarchyNodesFromParentIncludingItself(8);
			assertArrayEquals(
				new int[]{8, 9, 10, 11, 12},
				nodeIds.getArray()
			);
		}

		@Test
		void shouldListSubTreeIncludingItselfExcludingSubTrees() {
			final Bitmap nodeIds = HierarchyIndexTest.this.hierarchyIndex.listHierarchyNodesFromParentIncludingItself(
				6,
				new MatchNodeIdHierarchyFilteringPredicate(3)
					.or(new MatchNodeIdHierarchyFilteringPredicate(9))
					.negate()
			);
			assertArrayEquals(
				new int[]{6, 8},
				nodeIds.getArray()
			);
		}

		@Test
		void shouldListSubTree() {
			final Bitmap nodeIds = HierarchyIndexTest.this.hierarchyIndex.listHierarchyNodesFromParent(8);
			assertArrayEquals(
				new int[]{9, 10, 11, 12},
				nodeIds.getArray()
			);
		}

		@Test
		void shouldListSubTreeExcludingSubTrees() {
			final Bitmap nodeIds = HierarchyIndexTest.this.hierarchyIndex.listHierarchyNodesFromParent(
				6,
				new MatchNodeIdHierarchyFilteringPredicate(3)
					.or(new MatchNodeIdHierarchyFilteringPredicate(9))
					.negate()
			);
			assertArrayEquals(
				new int[]{8},
				nodeIds.getArray()
			);
		}
	}

	@Nested
	@DisplayName("Mutation")
	class MutationTest {

		@Test
		void shouldInsertNewNode() {
			HierarchyIndexTest.this.hierarchyIndex.addNode(20, 9);
			final Bitmap nodeIds = HierarchyIndexTest.this.hierarchyIndex.listHierarchyNodesFromParentDownTo(9, 1);
			assertArrayEquals(
				new int[]{10, 11, 12, 20},
				nodeIds.getArray()
			);
		}

		@Test
		void shouldInsertNewOrphanNodeAndThenInterleavingParentNode() {
			HierarchyIndexTest.this.hierarchyIndex.addNode(30, 20);
			final Bitmap nodeIds = HierarchyIndexTest.this.hierarchyIndex.listHierarchyNodesFromParent(9);
			assertArrayEquals(
				new int[]{10, 11, 12},
				nodeIds.getArray()
			);
			HierarchyIndexTest.this.hierarchyIndex.addNode(20, 9);
			final Bitmap nodeIdsAgain = HierarchyIndexTest.this.hierarchyIndex.listHierarchyNodesFromParent(9);
			assertArrayEquals(
				new int[]{10, 11, 12, 20, 30},
				nodeIdsAgain.getArray()
			);
		}

		@Test
		void shouldMoveExistingNodeWithSubTree() {
			HierarchyIndexTest.this.hierarchyIndex.addNode(9, 5);
			final Bitmap nodeIds = HierarchyIndexTest.this.hierarchyIndex.listHierarchyNodesFromParentIncludingItself(8);
			assertArrayEquals(
				new int[]{8},
				nodeIds.getArray()
			);
			final Bitmap nodeIdsFromDifferentTreePart = HierarchyIndexTest.this.hierarchyIndex.listHierarchyNodesFromParentIncludingItself(5);
			assertArrayEquals(
				new int[]{0, 5, 9, 10, 11, 12},
				nodeIdsFromDifferentTreePart.getArray()
			);
		}

		@Test
		void shouldRemoveNodeAndLeaveOrphans() {
			HierarchyIndexTest.this.hierarchyIndex.removeNode(9);
			final Bitmap nodeIds = HierarchyIndexTest.this.hierarchyIndex.listHierarchyNodesFromParentIncludingItself(8);
			assertArrayEquals(
				new int[]{8},
				nodeIds.getArray()
			);
			final Bitmap orphanNodes = HierarchyIndexTest.this.hierarchyIndex.getOrphanHierarchyNodes();
			assertArrayEquals(
				new int[]{10, 11, 12},
				orphanNodes.getArray()
			);
		}

		@Test
		void shouldRelocateOrphan() {
			HierarchyIndexTest.this.hierarchyIndex.removeNode(9);
			final Bitmap nodeIds = HierarchyIndexTest.this.hierarchyIndex.listHierarchyNodesFromParentIncludingItself(8);
			assertArrayEquals(
				new int[]{8},
				nodeIds.getArray()
			);
			HierarchyIndexTest.this.hierarchyIndex.addNode(10, 3);
			final Bitmap nodeIdsFromDifferentTreePart = HierarchyIndexTest.this.hierarchyIndex.listHierarchyNodesFromParentIncludingItself(3);
			assertArrayEquals(
				new int[]{1, 2, 3, 10},
				nodeIdsFromDifferentTreePart.getArray()
			);
			final Bitmap orphanNodes = HierarchyIndexTest.this.hierarchyIndex.getOrphanHierarchyNodes();
			assertArrayEquals(
				new int[]{11, 12},
				orphanNodes.getArray()
			);
		}

		@Test
		void shouldDetectAndAvoidCircularReferences() {
			// original dependency chain was 6 <- 8 <- 9 <- 10
			HierarchyIndexTest.this.hierarchyIndex.addNode(8, 10);
			// now we should be able to safely list node 6 without node 8 which becomes orphan
			final Bitmap nodeIds = HierarchyIndexTest.this.hierarchyIndex.listHierarchyNodesFromParentIncludingItself(6);
			assertArrayEquals(
				new int[]{1, 2, 3, 6},
				nodeIds.getArray()
			);
			// now let's attach 9 to 6 again
			HierarchyIndexTest.this.hierarchyIndex.addNode(9, 6);
			final Bitmap nodeIdsAgain = HierarchyIndexTest.this.hierarchyIndex.listHierarchyNodesFromParentIncludingItself(6);
			assertArrayEquals(
				new int[]{1, 2, 3, 6, 8, 9, 10, 11, 12},
				nodeIdsAgain.getArray()
			);
		}
	}

	@Nested
	@DisplayName("Root and parent group query")
	class RootAndParentGroupQueryTest {

		@Test
		void shouldReturnRootNodes() {
			final Bitmap rootNodes = HierarchyIndexTest.this.hierarchyIndex.getRootHierarchyNodes();
			assertArrayEquals(
				new int[]{6, 7},
				rootNodes.getArray()
			);
		}

		@Test
		void shouldReturnDirectChildrenOfParent() {
			final Bitmap children = HierarchyIndexTest.this.hierarchyIndex.getHierarchyNodesForParent(9);
			assertArrayEquals(
				new int[]{9, 10, 11, 12},
				children.getArray()
			);
		}
	}

	@Nested
	@DisplayName("Generational tests")
	class GenerationalTest {

		@Test
		void shouldGenerationalTest1() {
			final HierarchyIndex hierarchyIndex = new HierarchyIndex();
			final TestHierarchyNode testRoot = new TestHierarchyNode();
			setHierarchyFor(hierarchyIndex, testRoot, 4, null);
			setHierarchyFor(hierarchyIndex, testRoot, 52, 4);
			setHierarchyFor(hierarchyIndex, testRoot, 3, 52);

			setHierarchyFor(hierarchyIndex, testRoot, 3, null);
			setHierarchyFor(hierarchyIndex, testRoot, 41, 3);

			assertEquals(testRoot.toString(), hierarchyIndex.toString());
			testRoot.assertIdentical(hierarchyIndex, "Fuck");
		}

		@ParameterizedTest(name = "HierarchyIndex should survive generational randomized test applying modifications on it")
		@Tag(LONG_RUNNING_TEST)
		@ArgumentsSource(TimeArgumentProvider.class)
		void generationalProofTest(GenerationalTestInput input) {
			final int maxNodes = 50;
			final TestHierarchyNode testHierarchyNode = new TestHierarchyNode();

			runFor(
				new GenerationalTestInput(1, 1),
				1_000,
				new TestState(
					new StringBuilder(),
					new HierarchyIndex()
				),
				(random, testState) -> {
					final StringBuilder codeBuffer = new StringBuilder();
					codeBuffer.append("final HierarchyIndex hierarchyIndex = new HierarchyIndex();\n")
						.append(testHierarchyNode.getAllChildren().stream()
							.map(it ->
								"setHierarchyFor(hierarchyIndex, testRoot, " +
									it.getId() + ", " +
									(it.getParentId() == Integer.MIN_VALUE ? "null" : it.getParentId()) +
									");"
							)
							.collect(Collectors.joining("\n")))
						.append("\nOps:\n");

					final HierarchyIndex hierarchyIndex = testState.initialState();
					final AtomicReference<HierarchyIndex> committedResult = new AtomicReference<>();

					assertStateAfterCommit(
						hierarchyIndex,
						original -> {
							final int operationsInTransaction = random.nextInt(10);
							for (int i = 0; i < operationsInTransaction; i++) {
								final int length = hierarchyIndex.getHierarchySizeIncludingOrphans();
								final int operation = random.nextInt(3);
								if (length < maxNodes && (operation == 0 || length < 10)) {
									// insert new item
									int newNodeId;
									do {
										newNodeId = random.nextInt(maxNodes * 2);
									} while (testHierarchyNode.contains(newNodeId));

									final int[] childrenIds = testHierarchyNode.getChildrenIds();
									int parentNodeId;
									do {
										final int rndForParent = random.nextInt(childrenIds.length + 1);
										if (rndForParent == 0) {
											parentNodeId = Integer.MIN_VALUE;
										} else {
											parentNodeId = childrenIds[rndForParent - 1];
										}
									} while (newNodeId == parentNodeId);

									codeBuffer.append("setHierarchyFor(hierarchyIndex, testRoot, ")
										.append(newNodeId).append(",")
										.append(parentNodeId == Integer.MIN_VALUE ? "null" : parentNodeId)
										.append(");\n");

									try {
										setHierarchyFor(hierarchyIndex, testHierarchyNode, newNodeId, parentNodeId == Integer.MIN_VALUE ? null : parentNodeId);
									} catch (Exception ex) {
										fail(ex.getMessage() + "\n" + codeBuffer, ex);
									}
								} else if (operation == 1) {
									// move existing item
									final int[] childrenIds = testHierarchyNode.getChildrenIds();
									final int rndNo = random.nextInt(childrenIds.length);
									final int nodeIdToMove = childrenIds[rndNo];

									int parentNodeId;
									do {
										final int rndForParent = random.nextInt(childrenIds.length + 1);
										if (rndForParent == 0) {
											parentNodeId = Integer.MIN_VALUE;
										} else {
											parentNodeId = childrenIds[rndForParent - 1];
										}
									} while (nodeIdToMove == parentNodeId);

									codeBuffer.append("setHierarchyFor(hierarchyIndex, testRoot, ")
										.append(nodeIdToMove).append(",")
										.append(parentNodeId == Integer.MIN_VALUE ? "null" : parentNodeId)
										.append(");\n");

									try {
										setHierarchyFor(hierarchyIndex, testHierarchyNode, nodeIdToMove, parentNodeId == Integer.MIN_VALUE ? null : parentNodeId);
									} catch (Exception ex) {
										fail(ex.getMessage() + "\n" + codeBuffer, ex);
									}
								} else {
									// remove existing item
									final int[] childrenIds = testHierarchyNode.getChildrenIds();
									final int rndNo = random.nextInt(childrenIds.length);
									final int nodeIdToRemove = childrenIds[rndNo];

									codeBuffer.append("removeHierarchyFor(hierarchyIndex, testRoot, ")
										.append(nodeIdToRemove)
										.append(");\n");

									try {
										removeHierarchyFor(hierarchyIndex, testHierarchyNode, nodeIdToRemove);
									} catch (Exception ex) {
										fail(ex.getMessage() + "\n" + codeBuffer, ex);
									}
								}
							}
						},
						(original, committed) -> {
							testHierarchyNode.assertIdentical(
								committed,
								"\nExpected: " + testHierarchyNode + "\n" +
									"Actual:   " + committed + "\n\n" +
									codeBuffer
							);
							committedResult.set(committed);
						}
					);

					return new TestState(
						new StringBuilder(),
						committedResult.get()
					);
				}
			);
		}

	}

	@Nested
	@DisplayName("STM commit behavior")
	class StmCommitTest {

		@Test
		@DisplayName("committed state reflects root-level addNode")
		void shouldCommitRootNodeAddition() {
			final HierarchyIndex original = new HierarchyIndex();
			assertStateAfterCommit(
				original,
				index -> {
					index.addNode(1, null);
					index.addNode(2, null);
				},
				(orig, committed) -> {
					assertNotSame(orig, committed);
					assertArrayEquals(
						new int[]{1, 2},
						committed.getRootHierarchyNodes().getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("committed state reflects orphan creation")
		void shouldCommitOrphanCreation() {
			final HierarchyIndex original = new HierarchyIndex();
			assertStateAfterCommit(
				original,
				index -> {
					index.addNode(10, 99);
					index.addNode(11, 99);
				},
				(orig, committed) -> {
					assertNotSame(orig, committed);
					assertArrayEquals(
						new int[]{10, 11},
						committed.getOrphanHierarchyNodes().getArray()
					);
					assertEquals(0, committed.getHierarchySize());
				}
			);
		}

		@Test
		@DisplayName("committed state reflects node removal")
		void shouldCommitNodeRemoval() {
			assertStateAfterCommit(
				HierarchyIndexTest.this.hierarchyIndex,
				index -> index.removeNode(9),
				(orig, committed) -> {
					assertNotSame(orig, committed);
					// node 9 removed, its children become orphans
					assertArrayEquals(
						new int[]{8},
						committed.listHierarchyNodesFromParentIncludingItself(8).getArray()
					);
					assertArrayEquals(
						new int[]{10, 11, 12},
						committed.getOrphanHierarchyNodes().getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("committed state reflects orphan adoption when parent is added")
		void shouldCommitOrphanAdoption() {
			final HierarchyIndex original = new HierarchyIndex();
			assertStateAfterCommit(
				original,
				index -> {
					// add orphans first (parent 50 does not exist)
					index.addNode(30, 50);
					index.addNode(31, 50);
					// now add the parent as root
					index.addNode(50, null);
				},
				(orig, committed) -> {
					assertNotSame(orig, committed);
					assertArrayEquals(
						new int[]{50},
						committed.getRootHierarchyNodes().getArray()
					);
					assertArrayEquals(
						new int[]{30, 31},
						committed.listHierarchyNodesFromParentDownTo(50, 1).getArray()
					);
					assertTrue(committed.getOrphanHierarchyNodes().isEmpty());
				}
			);
		}

		@Test
		@DisplayName("committed state reflects node re-parenting")
		void shouldCommitNodeReParenting() {
			assertStateAfterCommit(
				HierarchyIndexTest.this.hierarchyIndex,
				index -> {
					// move node 9 (child of 8) to be child of 7
					index.addNode(9, 7);
				},
				(orig, committed) -> {
					assertNotSame(orig, committed);
					// node 9 should now be under 7
					final Bitmap subtree7 = committed.listHierarchyNodesFromParent(7);
					assertTrue(Arrays.stream(subtree7.getArray()).anyMatch(id -> id == 9));
					// node 8 should have no children
					assertArrayEquals(
						new int[]{8},
						committed.listHierarchyNodesFromParentIncludingItself(8).getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("not dirty index returns same instance from createCopyWithMergedTransactionalMemory")
		void shouldReturnSameInstanceWhenNotDirty() {
			final HierarchyIndex cleanIndex = new HierarchyIndex();
			assertStateAfterCommit(
				cleanIndex,
				index -> {
					// perform no modifications
				},
				(orig, committed) -> assertSame(orig, committed)
			);
		}

		@Test
		@DisplayName("null layer path in createCopyWithMergedTransactionalMemory")
		void shouldHandleNullLayerPath() {
			// VoidTransactionMemoryProducer always passes null as layer
			// when no modifications done, dirty=false, returns this
			final HierarchyIndex index = new HierarchyIndex();
			assertStateAfterCommit(
				index,
				original -> {
					// no changes, verifying the null layer code path
				},
				(original, committed) -> assertSame(original, committed)
			);
		}

		@Test
		@DisplayName("original unchanged after commit (explicit assertion)")
		void shouldLeaveOriginalUnchangedAfterCommit() {
			final HierarchyIndex original = new HierarchyIndex();
			original.addNode(1, null);
			original.addNode(2, 1);
			original.resetDirty();

			final int sizeBefore = original.getHierarchySize();
			final int[] rootsBefore = original.getRootHierarchyNodes().getArray();

			assertStateAfterCommit(
				original,
				index -> {
					index.addNode(99, null);
					index.addNode(100, 99);
				},
				(orig, committed) -> {
					// original data structures untouched
					assertEquals(sizeBefore, orig.getHierarchySize());
					assertArrayEquals(rootsBefore, orig.getRootHierarchyNodes().getArray());
					// committed has new data
					assertTrue(committed.getHierarchySize() > sizeBefore);
				}
			);
		}
	}

	@Nested
	@DisplayName("STM rollback behavior")
	class StmRollbackTest {

		@Test
		@DisplayName("rollback discards root-node addition")
		void shouldRollbackRootNodeAddition() {
			final HierarchyIndex original = new HierarchyIndex();
			assertStateAfterRollback(
				original,
				index -> {
					index.addNode(1, null);
					index.addNode(2, 1);
				},
				(orig, committed) -> {
					assertNull(committed);
					assertTrue(orig.isHierarchyIndexEmpty());
				}
			);
		}

		@Test
		@DisplayName("rollback discards orphan creation")
		void shouldRollbackOrphanCreation() {
			final HierarchyIndex original = new HierarchyIndex();
			assertStateAfterRollback(
				original,
				index -> index.addNode(10, 99),
				(orig, committed) -> {
					assertNull(committed);
					assertTrue(orig.isHierarchyIndexEmpty());
				}
			);
		}

		@Test
		@DisplayName("rollback discards node removal")
		void shouldRollbackNodeRemoval() {
			final int sizeBefore = HierarchyIndexTest.this.hierarchyIndex.getHierarchySize();
			assertStateAfterRollback(
				HierarchyIndexTest.this.hierarchyIndex,
				index -> index.removeNode(9),
				(orig, committed) -> {
					assertNull(committed);
					assertEquals(sizeBefore, orig.getHierarchySize());
					// node 9 should still be present
					assertArrayEquals(
						new int[]{10, 11, 12},
						orig.listHierarchyNodesFromParentDownTo(9, 1).getArray()
					);
				}
			);
		}

		@Test
		@DisplayName("rollback discards orphan promotion (parent added)")
		void shouldRollbackOrphanPromotion() {
			final HierarchyIndex original = new HierarchyIndex();
			original.addNode(30, 50);
			original.resetDirty();

			assertStateAfterRollback(
				original,
				index -> index.addNode(50, null),
				(orig, committed) -> {
					assertNull(committed);
					// node 30 is still orphan
					assertArrayEquals(
						new int[]{30},
						orig.getOrphanHierarchyNodes().getArray()
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("STM removeLayer and identity")
	class StmRemoveLayerAndIdentityTest {

		@Test
		@DisplayName("getId() returns unique sequence value (field overrides VoidTransactionMemoryProducer default)")
		void shouldReturnUniqueIdFromSequence() {
			final HierarchyIndex index1 = new HierarchyIndex();
			final HierarchyIndex index2 = new HierarchyIndex();
			// each instance gets a unique id from TransactionalObjectVersion.SEQUENCE
			assertNotEquals(index1.getId(), index2.getId());
		}

		@Test
		@DisplayName("createLayer() throws UnsupportedOperationException")
		void shouldThrowOnCreateLayer() {
			final HierarchyIndex index = new HierarchyIndex();
			assertThrows(
				UnsupportedOperationException.class,
				index::createLayer
			);
		}

		@Test
		@DisplayName("removeLayer cleans all nested producers by rolling back")
		void shouldRemoveLayerCleansNestedProducers() {
			// rollback internally calls removeLayer on all nested producers
			final HierarchyIndex original = new HierarchyIndex();
			original.addNode(1, null);
			original.resetDirty();

			assertStateAfterRollback(
				original,
				index -> {
					index.addNode(99, null);
					index.addNode(100, 99);
				},
				(orig, committed) -> {
					assertNull(committed);
					// original should still have only node 1
					assertEquals(1, orig.getHierarchySize());
					assertArrayEquals(
						new int[]{1},
						orig.getRootHierarchyNodes().getArray()
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("Construction and initialization")
	class ConstructionTest {

		@Test
		@DisplayName("deserialization constructor produces identical query results")
		void shouldConstructFromDeserializationArgs() {
			// build an equivalent index using the (int[], Map, Map, int[]) constructor
			final Map<Integer, HierarchyNode> itemIndex = new HashMap<>(16);
			itemIndex.put(1, new HierarchyNode(1, 6));
			itemIndex.put(6, new HierarchyNode(6, null));
			itemIndex.put(2, new HierarchyNode(2, 6));
			itemIndex.put(10, new HierarchyNode(10, 99));

			final HierarchyIndexStoragePart.LevelIndex[] levelIndex = {
				new HierarchyIndexStoragePart.LevelIndex(6, new int[]{1, 2}),
				new HierarchyIndexStoragePart.LevelIndex(1, new int[]{}),
				new HierarchyIndexStoragePart.LevelIndex(2, new int[]{})
			};

			final HierarchyIndex fromDeserialization = new HierarchyIndex(
				new int[]{6},
				levelIndex,
				itemIndex,
				new int[]{10}
			);

			assertArrayEquals(
				new int[]{6},
				fromDeserialization.getRootHierarchyNodes().getArray()
			);
			assertArrayEquals(
				new int[]{1, 2},
				fromDeserialization.listHierarchyNodesFromParentDownTo(6, 1).getArray()
			);
			assertArrayEquals(
				new int[]{10},
				fromDeserialization.getOrphanHierarchyNodes().getArray()
			);
			assertEquals(3, fromDeserialization.getHierarchySize());
		}

		@Test
		@DisplayName("initRootNodes bootstrap on empty index")
		void shouldInitRootNodesOnEmptyIndex() {
			final HierarchyIndex index = new HierarchyIndex();
			index.initRootNodes(new BaseBitmap(10, 20, 30));

			assertArrayEquals(
				new int[]{10, 20, 30},
				index.getRootHierarchyNodes().getArray()
			);
			assertEquals(3, index.getHierarchySize());
			assertFalse(index.isHierarchyIndexEmpty());
		}

		@Test
		@DisplayName("initRootNodes on non-empty index throws")
		void shouldThrowWhenInitRootNodesOnNonEmptyIndex() {
			// hierarchyIndex is already populated from setUp()
			assertThrows(
				Exception.class,
				() -> HierarchyIndexTest.this.hierarchyIndex.initRootNodes(
					new BaseBitmap(99)
				)
			);
		}

		@Test
		@DisplayName("empty index state: isHierarchyIndexEmpty, getHierarchySize, getAllHierarchyNodesFormula")
		void shouldReportEmptyIndexState() {
			final HierarchyIndex emptyIndex = new HierarchyIndex();
			assertTrue(emptyIndex.isHierarchyIndexEmpty());
			assertEquals(0, emptyIndex.getHierarchySize());
			final Formula allNodes = emptyIndex.getAllHierarchyNodesFormula();
			assertInstanceOf(EmptyFormula.class, allNodes);
		}
	}

	@Nested
	@DisplayName("Size-counting methods")
	class SizeCountingTest {

		@Test
		@DisplayName("getHierarchySize with mix of orphans and tree-reachable nodes")
		void shouldCountSizeExcludingOrphans() {
			final HierarchyIndex index = new HierarchyIndex();
			index.addNode(1, null);
			index.addNode(2, 1);
			index.addNode(3, 99); // orphan

			assertEquals(2, index.getHierarchySize());
			assertEquals(3, index.getHierarchySizeIncludingOrphans());
		}

		@Test
		@DisplayName("isHierarchyIndexEmpty true and false paths")
		void shouldReportEmptyAndNonEmpty() {
			final HierarchyIndex empty = new HierarchyIndex();
			assertTrue(empty.isHierarchyIndexEmpty());

			empty.addNode(1, null);
			assertFalse(empty.isHierarchyIndexEmpty());
		}

		@Test
		@DisplayName("getHierarchyNodeCountFromRootDownTo with levels=0, 1, MAX_VALUE")
		void shouldCountFromRootDownToVariousLevels() {
			final HierarchyFilteringPredicate acceptAll =
				HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE;

			// levels=0: roots + their direct children (countRecursively with -1 still counts children)
			final int countLevel0 = HierarchyIndexTest.this.hierarchyIndex
				.getHierarchyNodeCountFromRootDownTo(0, acceptAll);
			// roots: 6, 7 (2) + countRecursively(levels=-1) counts children but no deeper
			// children of 6: 3, 8 (2); children of 7: 4, 5 (2) => 2 + 4 = 6
			assertEquals(6, countLevel0);

			// levels=1: roots + direct children
			final int countLevel1 = HierarchyIndexTest.this.hierarchyIndex
				.getHierarchyNodeCountFromRootDownTo(1, acceptAll);
			// roots: 6, 7; children of 6: 3, 8; children of 7: 4, 5
			assertEquals(6, countLevel1);

			// levels=MAX_VALUE: all nodes
			final int countAll = HierarchyIndexTest.this.hierarchyIndex
				.getHierarchyNodeCountFromRootDownTo(Integer.MAX_VALUE, acceptAll);
			assertEquals(13, countAll);
		}

		@Test
		@DisplayName("getHierarchyNodeCountFromParent")
		void shouldCountFromParent() {
			final HierarchyFilteringPredicate acceptAll =
				HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE;

			// node 9 has children: 10, 11, 12
			final int count = HierarchyIndexTest.this.hierarchyIndex
				.getHierarchyNodeCountFromParent(9, acceptAll);
			assertEquals(3, count);
		}

		@Test
		@DisplayName("getHierarchyNodeCountFromParentDownTo + absent node guard")
		void shouldCountFromParentDownToAndThrowOnAbsent() {
			final HierarchyFilteringPredicate acceptAll =
				HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE;

			// node 6 -> children: 3, 8
			final int count = HierarchyIndexTest.this.hierarchyIndex
				.getHierarchyNodeCountFromParentDownTo(6, 1, acceptAll);
			// children.getLength()=2 + countRecursively([3,8], levels=1):
			//   3: 1 + countRec([1,2],0) = 1+2 = 3; 8: 1 + countRec([9],0) = 1+1 = 2
			//   total recursive = 5 => 2 + 5 = 7
			assertEquals(7, count);

			// absent node
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> HierarchyIndexTest.this.hierarchyIndex
					.getHierarchyNodeCountFromParentDownTo(999, 1, acceptAll)
			);
		}

		@Test
		@DisplayName("getRootHierarchyNodeCount")
		void shouldCountRootNodes() {
			final HierarchyFilteringPredicate acceptAll =
				HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE;

			assertEquals(
				2,
				HierarchyIndexTest.this.hierarchyIndex
					.getRootHierarchyNodeCount(acceptAll)
			);
		}

		@Test
		@DisplayName("getHierarchyNodeCountForParent -- leaf vs node with children")
		void shouldCountForParentLeafVsNonLeaf() {
			final HierarchyFilteringPredicate acceptAll =
				HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE;

			// leaf node 1 has no children: returns self only = 1
			final int leafCount = HierarchyIndexTest.this.hierarchyIndex
				.getHierarchyNodeCountForParent(1, acceptAll);
			assertEquals(1, leafCount);

			// node 9 has children 10, 11, 12: returns 9 + 10, 11, 12 = 4
			final int parentCount = HierarchyIndexTest.this.hierarchyIndex
				.getHierarchyNodeCountForParent(9, acceptAll);
			assertEquals(4, parentCount);
		}
	}

	@Nested
	@DisplayName("Depth-limited listing")
	class DepthLimitedListingTest {

		@Test
		@DisplayName("listHierarchyNodesFromRootDownTo with levels=1, 2, MAX_VALUE")
		void shouldListFromRootDownToVariousLevels() {
			final HierarchyFilteringPredicate acceptAll =
				HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE;

			// levels=1: roots + direct children
			final Bitmap level1 = HierarchyIndexTest.this.hierarchyIndex
				.listHierarchyNodesFromRootDownTo(1, acceptAll);
			assertArrayEquals(
				new int[]{3, 4, 5, 6, 7, 8},
				level1.getArray()
			);

			// levels=2: roots + 2 levels of children
			final Bitmap level2 = HierarchyIndexTest.this.hierarchyIndex
				.listHierarchyNodesFromRootDownTo(2, acceptAll);
			assertArrayEquals(
				new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
				level2.getArray()
			);

			// levels=MAX_VALUE: all nodes
			final Bitmap allNodes = HierarchyIndexTest.this.hierarchyIndex
				.listHierarchyNodesFromRootDownTo(Integer.MAX_VALUE, acceptAll);
			assertArrayEquals(
				new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12},
				allNodes.getArray()
			);
		}

		@Test
		@DisplayName("listHierarchyNodesFromParentIncludingItselfDownTo")
		void shouldListFromParentIncludingSelfDownTo() {
			final HierarchyFilteringPredicate acceptAll =
				HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE;

			// node 6 with levels=1: 6 + direct children (3, 8)
			final Bitmap result = HierarchyIndexTest.this.hierarchyIndex
				.listHierarchyNodesFromParentIncludingItselfDownTo(6, 1, acceptAll);
			assertArrayEquals(
				new int[]{3, 6, 8},
				result.getArray()
			);
		}

		@Test
		@DisplayName("listHierarchyNodesFromParentDownTo with predicate + absent node guard")
		void shouldListFromParentDownToWithPredicateAndGuard() {
			final HierarchyFilteringPredicate excludeNode9 =
				new MatchNodeIdHierarchyFilteringPredicate(9).negate();

			// node 8 children: 9; but 9 is excluded, so empty
			final Bitmap result = HierarchyIndexTest.this.hierarchyIndex
				.listHierarchyNodesFromParentDownTo(8, 1, excludeNode9);
			assertArrayEquals(new int[]{}, result.getArray());

			// absent node
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> HierarchyIndexTest.this.hierarchyIndex
					.listHierarchyNodesFromParentDownTo(999, 1, excludeNode9)
			);
		}

		@Test
		@DisplayName("listHierarchyNodesFromParentDownTo with levels=0")
		void shouldListFromParentDownToWithZeroLevels() {
			final HierarchyFilteringPredicate acceptAll =
				HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE;

			// levels=0 means direct children only (no recursion deeper)
			final Bitmap result = HierarchyIndexTest.this.hierarchyIndex
				.listHierarchyNodesFromParentDownTo(6, 0, acceptAll);
			assertArrayEquals(
				new int[]{3, 8},
				result.getArray()
			);
		}
	}

	@Nested
	@DisplayName("Formula and DeferredFormula methods")
	class FormulaTest {

		@Test
		@DisplayName("getListHierarchyNodesFromRootFormula (no-arg)")
		void shouldReturnDeferredFormulaFromRoot() {
			final Formula formula = HierarchyIndexTest.this.hierarchyIndex
				.getListHierarchyNodesFromRootFormula();
			assertInstanceOf(DeferredFormula.class, formula);
			assertArrayEquals(
				new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12},
				formula.compute().getArray()
			);
		}

		@Test
		@DisplayName("getListHierarchyNodesFromRootFormula with predicate")
		void shouldReturnDeferredFormulaFromRootWithPredicate() {
			final HierarchyFilteringPredicate excludeNode9 =
				new MatchNodeIdHierarchyFilteringPredicate(9).negate();

			final Formula formula = HierarchyIndexTest.this.hierarchyIndex
				.getListHierarchyNodesFromRootFormula(excludeNode9);
			assertInstanceOf(DeferredFormula.class, formula);
			// node 9 and its children (10, 11, 12) excluded
			final int[] result = formula.compute().getArray();
			assertFalse(Arrays.stream(result).anyMatch(id -> id == 9));
		}

		@Test
		@DisplayName("getListHierarchyNodesFromParentIncludingItselfFormula")
		void shouldReturnDeferredFormulaFromParentIncludingSelf() {
			final Formula formulaNoArg = HierarchyIndexTest.this.hierarchyIndex
				.getListHierarchyNodesFromParentIncludingItselfFormula(8);
			assertInstanceOf(DeferredFormula.class, formulaNoArg);
			assertArrayEquals(
				new int[]{8, 9, 10, 11, 12},
				formulaNoArg.compute().getArray()
			);

			final HierarchyFilteringPredicate excludeNode9 =
				new MatchNodeIdHierarchyFilteringPredicate(9).negate();
			final Formula formulaWithPredicate = HierarchyIndexTest.this.hierarchyIndex
				.getListHierarchyNodesFromParentIncludingItselfFormula(8, excludeNode9);
			assertInstanceOf(DeferredFormula.class, formulaWithPredicate);
			assertArrayEquals(
				new int[]{8},
				formulaWithPredicate.compute().getArray()
			);
		}

		@Test
		@DisplayName("getListHierarchyNodesFromParentFormula")
		void shouldReturnDeferredFormulaFromParent() {
			final Formula formulaNoArg = HierarchyIndexTest.this.hierarchyIndex
				.getListHierarchyNodesFromParentFormula(8);
			assertInstanceOf(DeferredFormula.class, formulaNoArg);
			assertArrayEquals(
				new int[]{9, 10, 11, 12},
				formulaNoArg.compute().getArray()
			);

			final HierarchyFilteringPredicate excludeNode9 =
				new MatchNodeIdHierarchyFilteringPredicate(9).negate();
			final Formula formulaWithPredicate = HierarchyIndexTest.this.hierarchyIndex
				.getListHierarchyNodesFromParentFormula(8, excludeNode9);
			assertInstanceOf(DeferredFormula.class, formulaWithPredicate);
			assertArrayEquals(
				new int[]{},
				formulaWithPredicate.compute().getArray()
			);
		}

		@Test
		@DisplayName("getRootHierarchyNodesFormula")
		void shouldReturnDeferredFormulaForRootNodes() {
			final Formula formulaNoArg = HierarchyIndexTest.this.hierarchyIndex
				.getRootHierarchyNodesFormula();
			assertInstanceOf(DeferredFormula.class, formulaNoArg);
			assertArrayEquals(
				new int[]{6, 7},
				formulaNoArg.compute().getArray()
			);

			final HierarchyFilteringPredicate onlyNode6 =
				new MatchNodeIdHierarchyFilteringPredicate(6);
			final Formula formulaWithPredicate = HierarchyIndexTest.this.hierarchyIndex
				.getRootHierarchyNodesFormula(onlyNode6);
			assertInstanceOf(DeferredFormula.class, formulaWithPredicate);
			assertArrayEquals(
				new int[]{6},
				formulaWithPredicate.compute().getArray()
			);
		}

		@Test
		@DisplayName("getHierarchyNodesForParentFormula")
		void shouldReturnDeferredFormulaForParent() {
			final Formula formulaNoArg = HierarchyIndexTest.this.hierarchyIndex
				.getHierarchyNodesForParentFormula(9);
			assertInstanceOf(DeferredFormula.class, formulaNoArg);
			assertArrayEquals(
				new int[]{9, 10, 11, 12},
				formulaNoArg.compute().getArray()
			);

			final HierarchyFilteringPredicate excludeNode10 =
				new MatchNodeIdHierarchyFilteringPredicate(10).negate();
			final Formula formulaWithPredicate = HierarchyIndexTest.this.hierarchyIndex
				.getHierarchyNodesForParentFormula(9, excludeNode10);
			assertInstanceOf(DeferredFormula.class, formulaWithPredicate);
			final int[] result = formulaWithPredicate.compute().getArray();
			assertTrue(Arrays.stream(result).anyMatch(id -> id == 9));
			assertFalse(Arrays.stream(result).anyMatch(id -> id == 10));
		}

		@Test
		@DisplayName("getAllHierarchyNodesFormula memoization: same reference on second call")
		void shouldMemoizeAllNodesFormula() {
			final Formula first = HierarchyIndexTest.this.hierarchyIndex
				.getAllHierarchyNodesFormula();
			final Formula second = HierarchyIndexTest.this.hierarchyIndex
				.getAllHierarchyNodesFormula();
			assertSame(first, second);
		}

		@Test
		@DisplayName("getAllHierarchyNodesFormula on empty index returns EmptyFormula")
		void shouldReturnEmptyFormulaForEmptyIndex() {
			final HierarchyIndex emptyIndex = new HierarchyIndex();
			final Formula formula = emptyIndex.getAllHierarchyNodesFormula();
			assertInstanceOf(EmptyFormula.class, formula);
		}

		@Test
		@DisplayName("getAllHierarchyNodesFormula dirty transaction returns fresh formula")
		void shouldBypassCacheInDirtyTransaction() {
			final HierarchyIndex index = new HierarchyIndex();
			index.addNode(1, null);
			index.resetDirty();

			final Formula before = index.getAllHierarchyNodesFormula();

			assertStateAfterCommit(
				index,
				original -> {
					original.addNode(2, null);
					// inside transaction with dirty flag, should create fresh formula
					final Formula duringTx = original.getAllHierarchyNodesFormula();
					// the formula computed during dirty transaction should include 2
					final int[] computed = duringTx.compute().getArray();
					assertTrue(
						Arrays.stream(computed).anyMatch(id -> id == 2),
						"Fresh formula should include newly added node 2"
					);
				},
				(original, committed) -> {
					// after commit, the memoized formula should be different
					assertNotNull(committed);
				}
			);
		}
	}

	@Nested
	@DisplayName("Non-transactional mode")
	class NonTransactionalModeTest {

		@Test
		@DisplayName("memoized formula reset after non-transactional addNode")
		void shouldResetMemoizedFormulaOnAdd() {
			final HierarchyIndex index = new HierarchyIndex();
			index.addNode(1, null);

			final Formula first = index.getAllHierarchyNodesFormula();
			assertArrayEquals(new int[]{1}, first.compute().getArray());

			// add another node outside transaction
			index.addNode(2, null);

			final Formula second = index.getAllHierarchyNodesFormula();
			assertArrayEquals(new int[]{1, 2}, second.compute().getArray());
			// formula should have been refreshed
			assertNotSame(first, second);
		}

		@Test
		@DisplayName("memoized formula reset after non-transactional removeNode")
		void shouldResetMemoizedFormulaOnRemove() {
			final HierarchyIndex index = new HierarchyIndex();
			index.addNode(1, null);
			index.addNode(2, 1);

			final Formula first = index.getAllHierarchyNodesFormula();
			assertArrayEquals(new int[]{1, 2}, first.compute().getArray());

			// remove node outside transaction
			index.removeNode(2);

			final Formula second = index.getAllHierarchyNodesFormula();
			assertArrayEquals(new int[]{1}, second.compute().getArray());
			assertNotSame(first, second);
		}
	}

	@Nested
	@DisplayName("Persistence")
	class PersistenceTest {

		@Test
		@DisplayName("createStoragePart returns null when not dirty")
		void shouldReturnNullStoragePartWhenNotDirty() {
			final HierarchyIndex index = new HierarchyIndex();
			index.addNode(1, null);
			index.resetDirty();

			final StoragePart storagePart = index.createStoragePart(42);
			assertNull(storagePart);
		}

		@Test
		@DisplayName("createStoragePart returns non-null HierarchyIndexStoragePart when dirty")
		void shouldReturnStoragePartWhenDirty() {
			final HierarchyIndex index = new HierarchyIndex();
			index.addNode(1, null);
			// dirty flag is set by addNode

			final StoragePart storagePart = index.createStoragePart(42);
			assertNotNull(storagePart);
			assertInstanceOf(HierarchyIndexStoragePart.class, storagePart);
		}

		@Test
		@DisplayName("resetDirty causes subsequent createStoragePart to return null")
		void shouldReturnNullAfterResetDirty() {
			final HierarchyIndex index = new HierarchyIndex();
			index.addNode(1, null);

			// before reset: should be non-null
			assertNotNull(index.createStoragePart(1));

			index.resetDirty();

			// after reset: should be null
			assertNull(index.createStoragePart(1));
		}
	}

	@Nested
	@DisplayName("Error paths")
	class ErrorPathTest {

		@Test
		@DisplayName("addNode where entityPK == parentPK throws")
		void shouldThrowOnSelfReference() {
			final HierarchyIndex index = new HierarchyIndex();
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> index.addNode(5, 5)
			);
		}

		@Test
		@DisplayName("removeNode on non-existent entity throws")
		void shouldThrowOnRemoveNonExistent() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> HierarchyIndexTest.this.hierarchyIndex.removeNode(999)
			);
		}

		@Test
		@DisplayName("listHierarchyNodesFromParentDownTo on absent node throws")
		void shouldThrowOnListFromAbsentParent() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> HierarchyIndexTest.this.hierarchyIndex
					.listHierarchyNodesFromParentDownTo(
						999, 1,
						HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE
					)
			);
		}

		@Test
		@DisplayName("listNodesIncludingParents with empty bitmap returns EmptyBitmap")
		void shouldReturnEmptyBitmapForEmptyInput() {
			final Bitmap result = HierarchyIndexTest.this.hierarchyIndex
				.listNodesIncludingParents(EmptyBitmap.INSTANCE);
			assertInstanceOf(EmptyBitmap.class, result);
		}

		@Test
		@DisplayName("listNodesIncludingParents with node not in index throws")
		void shouldThrowWhenNodeNotInIndex() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> HierarchyIndexTest.this.hierarchyIndex
					.listNodesIncludingParents(new BaseBitmap(999))
			);
		}

		@Test
		@DisplayName("listNodesIncludingParents with shared ancestor de-duplicates")
		void shouldDeduplicateSharedAncestors() {
			// nodes 1 and 2 share parent 3, grandparent 6
			final Bitmap result = HierarchyIndexTest.this.hierarchyIndex
				.listNodesIncludingParents(new BaseBitmap(1, 2));
			// result: 1, 2, 3, 6 (no duplicates)
			assertArrayEquals(
				new int[]{1, 2, 3, 6},
				result.getArray()
			);
		}
	}

	@Nested
	@DisplayName("Edge cases")
	class EdgeCaseTest {

		@Test
		@DisplayName("all query methods on empty index")
		void shouldHandleEmptyIndex() {
			final HierarchyIndex emptyIndex = new HierarchyIndex();
			final HierarchyFilteringPredicate acceptAll =
				HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE;

			assertTrue(emptyIndex.isHierarchyIndexEmpty());
			assertEquals(0, emptyIndex.getHierarchySize());
			assertEquals(0, emptyIndex.getRootHierarchyNodeCount(acceptAll));
			assertEquals(0, emptyIndex.getHierarchyNodeCountFromRootDownTo(1, acceptAll));

			final Bitmap roots = emptyIndex.getRootHierarchyNodes();
			assertInstanceOf(EmptyBitmap.class, roots);

			final Bitmap fromRoot = emptyIndex.listHierarchyNodesFromRoot();
			assertArrayEquals(new int[]{}, fromRoot.getArray());

			final Bitmap orphans = emptyIndex.getOrphanHierarchyNodes();
			assertArrayEquals(new int[]{}, orphans.getArray());
		}

		@Test
		@DisplayName("single root node with no children")
		void shouldHandleSingleRootNodeNoChildren() {
			final HierarchyIndex index = new HierarchyIndex();
			index.addNode(42, null);

			assertArrayEquals(
				new int[]{42},
				index.getRootHierarchyNodes().getArray()
			);
			assertArrayEquals(
				new int[]{42},
				index.listHierarchyNodesFromParentIncludingItself(42).getArray()
			);
			assertArrayEquals(
				new int[]{},
				index.listHierarchyNodesFromParent(42).getArray()
			);
			assertEquals(1, index.getHierarchySize());
		}

		@Test
		@DisplayName("leaf node for listHierarchyNodesFromParent returns empty bitmap")
		void shouldReturnEmptyForLeafNodeChildren() {
			// node 1 is a leaf
			final Bitmap children = HierarchyIndexTest.this.hierarchyIndex
				.listHierarchyNodesFromParent(1);
			assertArrayEquals(new int[]{}, children.getArray());
		}

		@Test
		@DisplayName("getHierarchyNodesForParent for leaf returns self only")
		void shouldReturnSelfOnlyForLeafParent() {
			// node 1 is a leaf with no children
			final Bitmap result = HierarchyIndexTest.this.hierarchyIndex
				.getHierarchyNodesForParent(1);
			assertArrayEquals(new int[]{1}, result.getArray());
		}

		@Test
		@DisplayName("getRootHierarchyNodes with predicate excluding all roots returns empty bitmap")
		void shouldReturnEmptyWhenPredicateExcludesAllRoots() {
			final Bitmap result = HierarchyIndexTest.this.hierarchyIndex
				.getRootHierarchyNodes(
					HierarchyFilteringPredicate.REJECT_ALL_NODES_PREDICATE
				);
			// when roots is non-empty but predicate rejects all, result is BaseBitmap with empty array
			assertTrue(result.isEmpty());
			assertArrayEquals(new int[]{}, result.getArray());
		}

		@Test
		@DisplayName("traverseHierarchyToRoot for node not in index skips silently")
		void shouldSilentlySkipTraverseToRootForAbsentNode() {
			final StringBuilder visited = new StringBuilder();
			// node 999 is not in the index
			HierarchyIndexTest.this.hierarchyIndex.traverseHierarchyToRoot(
				(node, level, distance, childrenTraverser) -> visited.append(node.entityPrimaryKey()),
				999
			);
			assertEquals("", visited.toString(), "Visitor should never be called for absent node");
		}

		@Test
		@DisplayName("traverseHierarchyFromNode for absent rootNode traverses nothing")
		void shouldTraverseNothingForAbsentRootNode() {
			final StringBuilder visited = new StringBuilder();
			// node 999 does not exist
			HierarchyIndexTest.this.hierarchyIndex.traverseHierarchyFromNode(
				(node, level, distance, childrenTraverser) -> visited.append(node.entityPrimaryKey()),
				999, false,
				HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE
			);
			assertEquals("", visited.toString(), "Visitor should never be called for absent root node");
		}

		@Test
		@DisplayName("listHierarchyNodesFromParentIncludingItself for absent parent returns EmptyBitmap")
		void shouldReturnEmptyBitmapForAbsentParentIncludingSelf() {
			final Bitmap result = HierarchyIndexTest.this.hierarchyIndex
				.listHierarchyNodesFromParentIncludingItself(999,
					HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE);
			assertInstanceOf(EmptyBitmap.class, result);
		}

		@Test
		@DisplayName("getHierarchyNodesForParent for absent parent returns EmptyBitmap")
		void shouldReturnEmptyBitmapForAbsentParentGroup() {
			final Bitmap result = HierarchyIndexTest.this.hierarchyIndex
				.getHierarchyNodesForParent(999,
					HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE);
			assertInstanceOf(EmptyBitmap.class, result);
		}

		@Test
		@DisplayName("getParentNode for an orphan node returns the orphan's parent PK")
		void shouldReturnParentPkForOrphanNode() {
			// add an orphan whose parent (999) is not registered
			HierarchyIndexTest.this.hierarchyIndex.addNode(100, 999);
			// node 100 is an orphan but IS in itemIndex — getParentNode should return 999
			assertEquals(999, HierarchyIndexTest.this.hierarchyIndex.getParentNode(100).getAsInt());
		}

		@Test
		@DisplayName("listNodesIncludingParents for a root node returns only the root itself")
		void shouldReturnRootAloneWhenRootHasNoParent() {
			// node 6 is a root (no parent)
			final Bitmap result = HierarchyIndexTest.this.hierarchyIndex
				.listNodesIncludingParents(new BaseBitmap(6));
			assertArrayEquals(new int[]{6}, result.getArray());
		}
	}

	@Nested
	@DisplayName("toString output")
	class ToStringTest {

		@Test
		@DisplayName("toString on populated tree produces indented tree structure")
		void shouldListEntireTreeInProperOrder() {
			assertEquals("""
				6
				   3
				      1
				      2
				   8
				      9
				         10
				         11
				         12
				7
				   4
				   5
				      0
				Orphans: []""",
				HierarchyIndexTest.this.hierarchyIndex.toString()
			);
		}

		@Test
		@DisplayName("toString on empty index")
		void shouldPrintEmptyIndex() {
			final HierarchyIndex emptyIndex = new HierarchyIndex();
			final String output = emptyIndex.toString();
			assertEquals("Orphans: []", output);
		}

		@Test
		@DisplayName("toString with orphans in output")
		void shouldPrintWithOrphans() {
			final HierarchyIndex index = new HierarchyIndex();
			index.addNode(1, null);
			index.addNode(10, 99);
			index.addNode(11, 99);

			final String output = index.toString();
			assertTrue(output.contains("1"));
			assertTrue(output.contains("Orphans: [10, 11]"));
		}
	}

	private void setHierarchyFor(HierarchyIndex hierarchyIndex, TestHierarchyNode testRoot, int entityPrimaryKey, Integer parent) {
		hierarchyIndex.addNode(entityPrimaryKey, parent);
		// first remove the node if already exists
		if (testRoot.find(entityPrimaryKey, testRoot) != null) {
			testRoot.removeNode(entityPrimaryKey, testRoot);
		}
		if (parent == null) {
			testRoot.addChild(entityPrimaryKey, testRoot);
		} else {
			// now place it on the proper place
			final TestHierarchyNode parentNode = testRoot.find(parent, testRoot);
			if (parentNode == null) {
				testRoot.addOrphan(entityPrimaryKey, parent);
			} else {
				parentNode.addChild(entityPrimaryKey, testRoot);
			}
		}
	}

	private void removeHierarchyFor(HierarchyIndex hierarchyIndex, TestHierarchyNode testRoot, int entityPrimaryKey) {
		hierarchyIndex.removeNode(entityPrimaryKey);
		testRoot.removeNode(entityPrimaryKey, testRoot);
	}

	@RequiredArgsConstructor
	private static class TestHierarchyNode {
		@Getter private final int id;
		@Getter private final int parentId;
		@Getter private final List<TestHierarchyNode> children = new LinkedList<>();
		private final Map<Integer, TestHierarchyNode> orphans;

		public TestHierarchyNode() {
			this.id = Integer.MIN_VALUE;
			this.parentId = Integer.MIN_VALUE;
			this.orphans = new HashMap<>();
		}

		public TestHierarchyNode(int id, int parentId) {
			this.id = id;
			this.parentId = parentId;
			this.orphans = Collections.emptyMap();
		}

		public void addChild(int nodeId, TestHierarchyNode rootNode) {
			final TestHierarchyNode newNode = new TestHierarchyNode(nodeId, this.id);
			this.children.add(newNode);
			rootNode.orphans.remove(nodeId);
			placeOrphansRecursively(newNode, rootNode);
		}

		public int[] getChildrenIds() {
			final CompositeIntArray intArray = new CompositeIntArray();
			appendIdRecursively(this, intArray);
			this.orphans.keySet().stream().mapToInt(it -> it).forEach(intArray::add);
			return intArray.toArray();
		}

		public void removeNode(int nodeId, TestHierarchyNode rootNode) {
			final TestHierarchyNode removedOrphan = rootNode.orphans.remove(nodeId);
			if (removedOrphan == null) {
				final TestHierarchyNode nodeInTree = find(nodeId, rootNode);
				Assert.notNull(nodeInTree, "Node " + nodeId + " not found in the tree!");
				final TestHierarchyNode nodeParent = find(nodeInTree.getParentId(), rootNode);
				Assert.notNull(nodeParent, "Node parent " + nodeId + " not found in the tree!");
				nodeParent.children.removeIf(it -> it.getId() == nodeId);
				makeOrphansRecursively(nodeInTree, rootNode);
			}
		}

		public boolean contains(int lookedUpId) {
			if (this.id == lookedUpId) {
				return true;
			}
			for (TestHierarchyNode child : this.children) {
				if (child.contains(lookedUpId)) {
					return true;
				}
			}
			return false;
		}

		public TestHierarchyNode find(int nodeId, TestHierarchyNode rootNode) {
			if (this.id == nodeId) {
				return this;
			} else {
				for (TestHierarchyNode child : this.children) {
					final TestHierarchyNode foundNode = child.find(nodeId, rootNode);
					if (foundNode != null) {
						return foundNode;
					}
				}
			}
			return null;
		}

		public Collection<TestHierarchyNode> getAllChildren() {
			final List<TestHierarchyNode> result = new LinkedList<>();
			addChildrenRecursively(this, result);
			result.addAll(this.orphans.values());
			return result;
		}

		public void assertIdentical(HierarchyIndex theIndex, String errorMessage) {
			for (TestHierarchyNode child : this.children) {
				assertIdenticalChildrenRecursively(child, theIndex, errorMessage);
			}
			final int[] thisOrphans = this.orphans.keySet().stream().sorted().mapToInt(it -> it).toArray();
			final int[] thatOrphans = theIndex.getOrphanHierarchyNodes().getArray();
			assertArrayEquals(
				thisOrphans,
				thatOrphans,
				errorMessage
			);
		}

		@Override
		public String toString() {
			final StringBuilder sb = new StringBuilder();
			toStringChildrenRecursively(this.children, 0, sb);
			sb.append("Orphans: ").append(Arrays.toString(this.orphans.keySet().stream().mapToInt(it -> it).sorted().toArray()));
			return sb.toString();
		}

		public void addOrphan(int entityPrimaryKey, int parentPrimaryKey) {
			this.orphans.put(entityPrimaryKey, new TestHierarchyNode(entityPrimaryKey, parentPrimaryKey));
		}

		private void assertIdenticalChildrenRecursively(TestHierarchyNode theNode, HierarchyIndex theIndex, String errorMessage) {
			final int[] thisChildrenIds = theNode.children.stream().mapToInt(TestHierarchyNode::getId).sorted().toArray();
			final int[] thatChildrenIds = theIndex.listHierarchyNodesFromParentDownTo(theNode.getId(), 0).getArray();
			assertArrayEquals(
				thisChildrenIds,
				thatChildrenIds,
				errorMessage
			);
			for (TestHierarchyNode child : theNode.children) {
				assertIdenticalChildrenRecursively(child, theIndex, errorMessage);
			}
		}

		private void addChildrenRecursively(TestHierarchyNode node, List<TestHierarchyNode> result) {
			result.addAll(node.children);
			for (TestHierarchyNode child : node.children) {
				addChildrenRecursively(child, result);
			}
		}

		private void toStringChildrenRecursively(List<TestHierarchyNode> nodeIds, int indent, StringBuilder sb) {
			nodeIds
				.stream()
				.sorted(Comparator.comparingInt(TestHierarchyNode::getId))
				.forEach(node -> {
					sb.append(" ".repeat(3 * indent)).append(node.getId()).append("\n");
					toStringChildrenRecursively(node.children, indent + 1, sb);
				});
		}

		private void makeOrphansRecursively(TestHierarchyNode nodeInTree, TestHierarchyNode rootNode) {
			nodeInTree.getChildren()
				.forEach(it -> {
					rootNode.orphans.put(it.getId(), new TestHierarchyNode(it.getId(), it.getParentId()));
					makeOrphansRecursively(it, rootNode);
				});

		}

		private void placeOrphansRecursively(TestHierarchyNode newNode, TestHierarchyNode rootNode) {
			final Iterator<TestHierarchyNode> it = rootNode.orphans.values().iterator();
			while (it.hasNext()) {
				final TestHierarchyNode orphan = it.next();
				if (orphan.getParentId() == newNode.id) {
					it.remove();
					newNode.children.add(orphan);
				}
			}
			for (TestHierarchyNode child : newNode.children) {
				placeOrphansRecursively(child, rootNode);
			}
		}

		private void appendIdRecursively(TestHierarchyNode parentNode, CompositeIntArray intArray) {
			for (TestHierarchyNode child : parentNode.getChildren()) {
				intArray.add(child.getId());
				appendIdRecursively(child, intArray);
			}
		}
	}

	private record TestState(
		StringBuilder code,
		HierarchyIndex initialState
	) {
	}

}
