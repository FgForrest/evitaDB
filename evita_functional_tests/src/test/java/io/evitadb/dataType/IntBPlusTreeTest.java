/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.dataType;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test verifies the correctness of the {@link IntBPlusTree} implementation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class IntBPlusTreeTest {

	@Test
	void shouldOverwriteDuplicateKeys() {
		IntBPlusTree<String> bPlusTree = new IntBPlusTree<>(3, String.class);
		bPlusTree.insert(5, "Value5");
		bPlusTree.insert(5, "NewValue5");
		assertEquals("NewValue5", bPlusTree.search(5).orElse(null));
	}

	@Test
	void shouldPrintVerboseSimpleTree() {
		IntBPlusTree<String> bPlusTree = new IntBPlusTree<>(3, String.class);
		bPlusTree.insert(5, "Value5");
		bPlusTree.insert(15, "Value50");
		assertEquals(
			"5:Value5, 15:Value50",
			bPlusTree.toString()
		);
	}

	@Test
	void shouldSplitNodeWhenFull() {
		IntBPlusTree<String> bPlusTree = new IntBPlusTree<>(3, String.class);
		bPlusTree.insert(1, "Value1");
		bPlusTree.insert(2, "Value2");
		bPlusTree.insert(3, "Value3");  // This should cause a split
		bPlusTree.insert(4, "Value4");
		assertEquals("Value1", bPlusTree.search(1).orElse(null));
		assertEquals("Value2", bPlusTree.search(2).orElse(null));
		assertEquals("Value3", bPlusTree.search(3).orElse(null));
        assertEquals("Value4", bPlusTree.search(4).orElse(null));
    }

	@Test
	void shouldPrintComplexTree() {
		IntBPlusTree<String> bPlusTree = new IntBPlusTree<>(3, String.class);
		bPlusTree.insert(1, "Value1");
		bPlusTree.insert(2, "Value2");
		bPlusTree.insert(3, "Value3");  // This should cause a split
		bPlusTree.insert(4, "Value4");
		assertEquals(
			"""
			< 3:
			   1:Value1, 2:Value2
			>=3:
			   3:Value3, 4:Value4
			""",
			bPlusTree.toString()
		);
	}

}