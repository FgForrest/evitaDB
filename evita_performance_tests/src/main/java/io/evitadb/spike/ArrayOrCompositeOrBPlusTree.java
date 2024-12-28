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

package io.evitadb.spike;


import io.evitadb.dataType.IntBPlusTree;
import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.StringUtils;

/**
 * This test aims to measure the write performance between Array, Composite and B+Tree indexes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class ArrayOrCompositeOrBPlusTree {
	public static final int AMOUNT = 1_000_000_000;

	public static void main(String[] args) {
		insertIntoBPlusTree();
	}

	private static void insertIntoSimpleArray() {
		final long start = System.nanoTime();
		int[] array = new int[0];
		for (int i = 0; i < AMOUNT; i++) {
			array = ArrayUtils.insertIntIntoOrderedArray(i, array);
			if (i % 100_000 == 0) {
				System.out.println("Inserted " + i + " elements into Array in " + StringUtils.formatPreciseNano(System.nanoTime() - start));
			}
		}
		System.out.println("Finished importing " + AMOUNT + " elements into Array in " + StringUtils.formatPreciseNano(System.nanoTime() - start));
	}

	private static void insertIntoComposite() {
		final long start = System.nanoTime();
		final CompositeIntArray array = new CompositeIntArray();
		for (int i = 0; i < AMOUNT; i++) {
			array.add(i);
			if (i % 100_000 == 0) {
				System.out.println("Inserted " + i + " elements into Array in " + StringUtils.formatPreciseNano(System.nanoTime() - start));
			}
		}
		System.out.println("Finished importing " + AMOUNT + " elements into Array in " + StringUtils.formatPreciseNano(System.nanoTime() - start));
	}

	private static void insertIntoBPlusTree() {
		final long start = System.nanoTime();
		final IntBPlusTree<Integer> array = new IntBPlusTree<>(63, Integer.class);
		for (int i = 0; i < AMOUNT; i++) {
			array.insert(i, i);
			if (i % 100_000 == 0) {
				System.out.println("Inserted " + i + " elements into Array in " + StringUtils.formatPreciseNano(System.nanoTime() - start));
			}
		}
		System.out.println("Finished importing " + AMOUNT + " elements into Array in " + StringUtils.formatPreciseNano(System.nanoTime() - start));
	}

}
