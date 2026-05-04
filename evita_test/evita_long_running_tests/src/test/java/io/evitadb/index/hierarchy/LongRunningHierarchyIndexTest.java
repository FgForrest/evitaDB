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

import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Tag;
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

import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Long-running generational randomized proof test for {@link HierarchyIndex}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Tag(INDEXING)
@Tag(HIERARCHY)
class LongRunningHierarchyIndexTest implements TimeBoundedTestSupport {

	@ParameterizedTest(name = "HierarchyIndex should survive generational randomized test applying modifications on it")
	@Tag(SLOW)
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
