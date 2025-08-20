/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.dataType.trie;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link TrieNode} contract.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class TrieNodeTest {

	@Test
	void shouldAddNewValue() {
		final TrieNode<Integer> tested = new TrieNode<>(Integer[]::new);
		tested.addValue(Integer[]::new, 1);
		assertArrayEquals(new Integer[] {1}, tested.getValues());
		tested.addValue(Integer[]::new, new Integer[]{4, 8});
		tested.addValue(Integer[]::new, 89);
		assertArrayEquals(new Integer[] {1, 4, 8, 89}, tested.getValues());
	}

	@Test
	void shouldRemoveValue() {
		final TrieNode<Integer> tested = new TrieNode<>(Integer[]::new);
		tested.addValue(Integer[]::new, new Integer[]{7, 2, 1, 9, 12});
		assertFalse(tested.removeValue(Integer[]::new, 5));
		assertTrue(tested.removeValue(Integer[]::new, 9));
		assertTrue(tested.removeValue(Integer[]::new, 7));
		assertArrayEquals(new Integer[] {2, 1, 12}, tested.getValues());
	}

	@Test
	void shouldAddOrGetEdgeLabelIndex() {
		final TrieNode<Integer> tested = new TrieNode<>(Integer[]::new);
		tested.setChildrenAndEdgeLabel('c', new TrieNode<>(Integer[]::new), "c");
		tested.setChildrenAndEdgeLabel('a', new TrieNode<>(Integer[]::new), "a");
		tested.setChildrenAndEdgeLabel('b', new TrieNode<>(Integer[]::new), "b");
		assertEquals("c", tested.getEdgeLabel('c'));
		assertEquals("b", tested.getEdgeLabel('b'));
		assertEquals("a", tested.getEdgeLabel('a'));
	}

	@Test
	void shouldAddOrGetEdgeLabelMap() {
		final TrieNode<Integer> tested = new TrieNode<>(Integer[]::new);
		tested.setChildrenAndEdgeLabel('c', new TrieNode<>(Integer[]::new), "c");
		tested.setChildrenAndEdgeLabel('a', new TrieNode<>(Integer[]::new), "a");
		tested.setChildrenAndEdgeLabel('b', new TrieNode<>(Integer[]::new), "b");

		final Map<String, String> map = tested.getEdgeLabelAsMap();
		assertNotNull(map);

		assertEquals("a", map.get("a"));
		assertEquals("b", map.get("b"));
		assertEquals("c", map.get("c"));
	}

	@Test
	void shouldAddOrGetChildrenIndex() {
		final TrieNode<Integer> tested = new TrieNode<>(Integer[]::new);
		tested.setChildrenAndEdgeLabel('c', new TrieNode<>(Integer[]::new, 1), "c");
		tested.setChildrenAndEdgeLabel('a', new TrieNode<>(Integer[]::new, 2), "a");
		tested.setChildrenAndEdgeLabel('b', new TrieNode<>(Integer[]::new, 3), "b");
		assertArrayEquals(new Integer[]{1}, tested.getChildren('c').getValues());
		assertArrayEquals(new Integer[]{3}, tested.getChildren('b').getValues());
		assertArrayEquals(new Integer[]{2}, tested.getChildren('a').getValues());
	}

	@Test
	void shouldAddOrGetChildrenMap() {
		final TrieNode<Integer> tested = new TrieNode<>(Integer[]::new);
		tested.setChildrenAndEdgeLabel('c', new TrieNode<>(Integer[]::new, 1), "c");
		tested.setChildrenAndEdgeLabel('a', new TrieNode<>(Integer[]::new, 2), "a");
		tested.setChildrenAndEdgeLabel('b', new TrieNode<>(Integer[]::new, 3), "b");

		final Map<String, TrieNode<Integer>> map = tested.getChildrenAsMap();
		assertNotNull(map);

		assertArrayEquals(new Integer[]{1}, map.get("c").getValues());
		assertArrayEquals(new Integer[]{3}, map.get("b").getValues());
		assertArrayEquals(new Integer[]{2}, map.get("a").getValues());
	}

}
