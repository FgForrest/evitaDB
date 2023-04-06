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

package io.evitadb.index.hierarchy;

import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.array.CompositeIntArray;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.hierarchy.predicate.MatchNodeIdHierarchyFilteringPredicate;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.stream.Collectors;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
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
		hierarchyIndex = new HierarchyIndex();
		hierarchyIndex.setHierarchyFor(0, 5, 1);
		hierarchyIndex.setHierarchyFor(1, 3, 2);
		hierarchyIndex.setHierarchyFor(2, 3, 1);
		hierarchyIndex.setHierarchyFor(3, 6, 2);
		hierarchyIndex.setHierarchyFor(4, 7, 1);
		hierarchyIndex.setHierarchyFor(5, 7, 2);
		hierarchyIndex.setHierarchyFor(6, null, 1);
		hierarchyIndex.setHierarchyFor(7, null, 2);
		hierarchyIndex.setHierarchyFor(8, 6, 1);
		hierarchyIndex.setHierarchyFor(9, 8, 1);
		hierarchyIndex.setHierarchyFor(10, 9, 3);
		hierarchyIndex.setHierarchyFor(11, 9, 2);
		hierarchyIndex.setHierarchyFor(12, 9, 1);
	}

	@Test
	void shouldListEntireTree() {
		final Bitmap nodeIds = hierarchyIndex.listHierarchyNodesFromRoot();
		assertArrayEquals(
			new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12},
			nodeIds.getArray()
		);
	}

	@Test
	void shouldFindParentNode() {
		assertEquals(6, hierarchyIndex.getParentNode(3).getAsInt());
		assertTrue(hierarchyIndex.getParentNode(6).isEmpty());
		assertThrows(EvitaInvalidUsageException.class, () -> hierarchyIndex.getParentNode(99));
	}

	@Test
	void shouldTraverseRootNodes() {
		final StringBuilder nodeIds = new StringBuilder("|");
		final StringBuilder levels = new StringBuilder("|");
		final StringBuilder distances = new StringBuilder("|");
		hierarchyIndex.traverseHierarchy(
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
		hierarchyIndex.traverseHierarchy(
			(node, level, distance, childrenTraverser) -> {
				nodeIds.append(node.entityPrimaryKey()).append("|");
				levels.append(level).append("|");
				distances.append(distance).append("|");
				childrenTraverser.run();
			}
		);
		assertEquals("|6|8|9|12|11|10|3|2|1|7|4|5|0|", nodeIds.toString());
		assertEquals("|1|2|3|4|4|4|2|3|3|1|2|2|3|", levels.toString());
		assertEquals("|1|2|3|4|4|4|2|3|3|1|2|2|3|", distances.toString());
	}

	@Test
	void shouldTraverseEntireTreeBottomUp() {
		final StringBuilder nodeIds = new StringBuilder("|");
		final StringBuilder levels = new StringBuilder("|");
		final StringBuilder distances = new StringBuilder("|");
		hierarchyIndex.traverseHierarchy(
			(node, level, distance, childrenTraverser) -> {
				childrenTraverser.run();
				nodeIds.append(node.entityPrimaryKey()).append("|");
				levels.append(level).append("|");
				distances.append(distance).append("|");
			}
		);
		assertEquals("|12|11|10|9|8|2|1|3|6|4|0|5|7|", nodeIds.toString());
		assertEquals("|4|4|4|3|2|3|3|2|1|2|3|2|1|", levels.toString());
		assertEquals("|4|4|4|3|2|3|3|2|1|2|3|2|1|", distances.toString());
	}

	@Test
	void shouldTraverseSubTreeExcludingInnerSubTree() {
		final StringBuilder nodeIds = new StringBuilder("|");
		final StringBuilder levels = new StringBuilder("|");
		final StringBuilder distances = new StringBuilder("|");
		hierarchyIndex.traverseHierarchyFromNode(
			(node, level, distance, childrenTraverser) -> {
				childrenTraverser.run();
				nodeIds.append(node.entityPrimaryKey()).append("|");
				levels.append(level).append("|");
				distances.append(distance).append("|");
			},
			6, false, new MatchNodeIdHierarchyFilteringPredicate(9)
		);
		assertEquals("|8|2|1|3|6|", nodeIds.toString());
		assertEquals("|2|3|3|2|1|", levels.toString());
		assertEquals("|1|2|2|1|0|", distances.toString());
	}

	@Test
	void shouldTraverseEntireTreeFromParent() {
		final StringBuilder nodeIds = new StringBuilder("|");
		final StringBuilder levels = new StringBuilder("|");
		final StringBuilder distances = new StringBuilder("|");
		hierarchyIndex.traverseHierarchyFromNode(
			(node, level, distance, childrenTraverser) -> {
				childrenTraverser.run();
				nodeIds.append(node.entityPrimaryKey()).append("|");
				levels.append(level).append("|");
				distances.append(distance).append("|");
			},
			9, false
		);
		assertEquals("|12|11|10|9|", nodeIds.toString());
		assertEquals("|4|4|4|3|", levels.toString());
		assertEquals("|1|1|1|0|", distances.toString());
	}

	@Test
	void shouldTraverseEntireTreeToRoot() {
		final StringBuilder nodeIds = new StringBuilder("|");
		final StringBuilder levels = new StringBuilder("|");
		final StringBuilder distances = new StringBuilder("|");
		hierarchyIndex.traverseHierarchyToRoot(
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
		hierarchyIndex.traverseHierarchyFromNode(
			(node, level, distance, childrenTraverser) -> {
				childrenTraverser.run();
				nodeIds.append(node.entityPrimaryKey()).append("|");
				levels.append(level).append("|");
				distances.append(distance).append("|");
			},
			9, true
		);
		assertEquals("|12|11|10|", nodeIds.toString());
		assertEquals("|3|3|3|", levels.toString());
		assertEquals("|1|1|1|", distances.toString());
	}

	@Test
	void shouldListEntireTreeInProperOrder() {
		assertEquals(
			"6\n" +
				"   8\n" +
				"      9\n" +
				"         12\n" +
				"         11\n" +
				"         10\n" +
				"   3\n" +
				"      2\n" +
				"      1\n" +
				"7\n" +
				"   4\n" +
				"   5\n" +
				"      0\n" +
				"Orphans: []",
			hierarchyIndex.toString()
		);
	}

	@Test
	void shouldListEntireTreeExcludingParts() {
		final Bitmap nodeIds = hierarchyIndex.listHierarchyNodesFromRoot(
			new MatchNodeIdHierarchyFilteringPredicate(9)
				.or(new MatchNodeIdHierarchyFilteringPredicate(5))
		);
		assertArrayEquals(
			new int[]{1, 2, 3, 4, 6, 7, 8},
			nodeIds.getArray()
		);
	}

	@Test
	void shouldListSubTreeIncludingItself() {
		final Bitmap nodeIds = hierarchyIndex.listHierarchyNodesFromParentIncludingItself(8);
		assertArrayEquals(
			new int[]{8, 9, 10, 11, 12},
			nodeIds.getArray()
		);
	}

	@Test
	void shouldListSubTreeIncludingItselfExcludingSubTrees() {
		final Bitmap nodeIds = hierarchyIndex.listHierarchyNodesFromParentIncludingItself(
			6,
			new MatchNodeIdHierarchyFilteringPredicate(3)
				.or(new MatchNodeIdHierarchyFilteringPredicate(9))
		);
		assertArrayEquals(
			new int[]{6, 8},
			nodeIds.getArray()
		);
	}

	@Test
	void shouldListSubTree() {
		final Bitmap nodeIds = hierarchyIndex.listHierarchyNodesFromParent(8);
		assertArrayEquals(
			new int[]{9, 10, 11, 12},
			nodeIds.getArray()
		);
	}

	@Test
	void shouldListSubTreeExcludingSubTrees() {
		final Bitmap nodeIds = hierarchyIndex.listHierarchyNodesFromParent(
			6,
			new MatchNodeIdHierarchyFilteringPredicate(3).or(new MatchNodeIdHierarchyFilteringPredicate(9))
		);
		assertArrayEquals(
			new int[]{8},
			nodeIds.getArray()
		);
	}

	@Test
	void shouldInsertNewNode() {
		hierarchyIndex.setHierarchyFor(20, 9, 4);
		final Bitmap nodeIds = hierarchyIndex.listHierarchyNodesFromParentDownTo(9, 1);
		assertArrayEquals(
			new int[]{10, 11, 12, 20},
			nodeIds.getArray()
		);
	}

	@Test
	void shouldInsertNewOrphanNodeAndThenInterleavingParentNode() {
		hierarchyIndex.setHierarchyFor(30, 20, 1);
		final Bitmap nodeIds = hierarchyIndex.listHierarchyNodesFromParent(9);
		assertArrayEquals(
			new int[]{10, 11, 12},
			nodeIds.getArray()
		);
		hierarchyIndex.setHierarchyFor(20, 9, 4);
		final Bitmap nodeIdsAgain = hierarchyIndex.listHierarchyNodesFromParent(9);
		assertArrayEquals(
			new int[]{10, 11, 12, 20, 30},
			nodeIdsAgain.getArray()
		);
	}

	@Test
	void shouldMoveExistingNodeWithSubTree() {
		hierarchyIndex.setHierarchyFor(9, 5, 2);
		final Bitmap nodeIds = hierarchyIndex.listHierarchyNodesFromParentIncludingItself(8);
		assertArrayEquals(
			new int[]{8},
			nodeIds.getArray()
		);
		final Bitmap nodeIdsFromDifferentTreePart = hierarchyIndex.listHierarchyNodesFromParentIncludingItself(5);
		assertArrayEquals(
			new int[]{0, 5, 9, 10, 11, 12},
			nodeIdsFromDifferentTreePart.getArray()
		);
	}

	@Test
	void shouldRemoveNodeAndLeaveOrphans() {
		hierarchyIndex.removeHierarchyFor(9);
		final Bitmap nodeIds = hierarchyIndex.listHierarchyNodesFromParentIncludingItself(8);
		assertArrayEquals(
			new int[]{8},
			nodeIds.getArray()
		);
		final Bitmap orphanNodes = hierarchyIndex.getOrphanHierarchyNodes();
		assertArrayEquals(
			new int[]{10, 11, 12},
			orphanNodes.getArray()
		);
	}

	@Test
	void shouldRelocateOrphan() {
		hierarchyIndex.removeHierarchyFor(9);
		final Bitmap nodeIds = hierarchyIndex.listHierarchyNodesFromParentIncludingItself(8);
		assertArrayEquals(
			new int[]{8},
			nodeIds.getArray()
		);
		hierarchyIndex.setHierarchyFor(10, 3, 3);
		final Bitmap nodeIdsFromDifferentTreePart = hierarchyIndex.listHierarchyNodesFromParentIncludingItself(3);
		assertArrayEquals(
			new int[]{1, 2, 3, 10},
			nodeIdsFromDifferentTreePart.getArray()
		);
		final Bitmap orphanNodes = hierarchyIndex.getOrphanHierarchyNodes();
		assertArrayEquals(
			new int[]{11, 12},
			orphanNodes.getArray()
		);
	}

	@Test
	void shouldDetectAndAvoidCircularReferences() {
		// original dependency chain was 6 <- 8 <- 9 <- 10
		hierarchyIndex.setHierarchyFor(8, 10, 1);
		// now we should be able to safely list node 6 without node 8 which becomes orphan
		final Bitmap nodeIds = hierarchyIndex.listHierarchyNodesFromParentIncludingItself(6);
		assertArrayEquals(
			new int[]{1, 2, 3, 6},
			nodeIds.getArray()
		);
		// now let's attach 9 to 6 again
		hierarchyIndex.setHierarchyFor(9, 6, 2);
		final Bitmap nodeIdsAgain = hierarchyIndex.listHierarchyNodesFromParentIncludingItself(6);
		assertArrayEquals(
			new int[]{1, 2, 3, 6, 8, 9, 10, 11, 12},
			nodeIdsAgain.getArray()
		);
	}

	@Test
	void shouldReturnRootNodes() {
		final Bitmap rootNodes = hierarchyIndex.getRootHierarchyNodes();
		assertArrayEquals(
			new int[]{6, 7},
			rootNodes.getArray()
		);
	}

	@Test
	void shouldReturnDirectChildrenOfParent() {
		final Bitmap children = hierarchyIndex.getHierarchyNodesForParent(9);
		assertArrayEquals(
			new int[]{10, 11, 12},
			children.getArray()
		);
	}

	@Test
	void shouldReturnPathToTheRoot() {
		assertArrayEquals(new Integer[]{6, 8, 9}, hierarchyIndex.listHierarchyNodesFromRootToTheNode(11));
		assertArrayEquals(new Integer[]{7, 5}, hierarchyIndex.listHierarchyNodesFromRootToTheNode(0));
		assertArrayEquals(new Integer[]{7}, hierarchyIndex.listHierarchyNodesFromRootToTheNode(4));
		assertArrayEquals(new Integer[0], hierarchyIndex.listHierarchyNodesFromRootToTheNode(7));
	}

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
			input,
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

	private void setHierarchyFor(HierarchyIndex hierarchyIndex, TestHierarchyNode testRoot, int entityPrimaryKey, Integer parent) {
		hierarchyIndex.setHierarchyFor(entityPrimaryKey, parent, 1);
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
		hierarchyIndex.removeHierarchyFor(entityPrimaryKey);
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
			final TestHierarchyNode newNode = new TestHierarchyNode(nodeId, id);
			children.add(newNode);
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
			if (id == lookedUpId) {
				return true;
			}
			for (TestHierarchyNode child : children) {
				if (child.contains(lookedUpId)) {
					return true;
				}
			}
			return false;
		}

		public TestHierarchyNode find(int nodeId, TestHierarchyNode rootNode) {
			if (id == nodeId) {
				return this;
			} else {
				for (TestHierarchyNode child : children) {
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
			for (TestHierarchyNode child : children) {
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
			toStringChildrenRecursively(children, 0, sb);
			sb.append("Orphans: ").append(Arrays.toString(orphans.keySet().stream().mapToInt(it -> it).sorted().toArray()));
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