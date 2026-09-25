/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.index.bPlusTree;

import io.evitadb.dataType.ConsistencySensitiveDataStructure;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyReport;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.util.Random;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.evitadb.index.bPlusTree.AbstractTransactionalBPlusTree.deriveInternalNodeBlockSize;
import static io.evitadb.index.bPlusTree.AbstractTransactionalBPlusTree.deriveMinBlockSize;
import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the single-block-size convenience constructors of all five transactional B+ trees. Each of them derives
 * the minimum leaf size and both internal node sizes from the one `valueBlockSize` it is given, and that derivation
 * must satisfy the premises asserted by the full constructor for every size of at least 3 — even sizes included,
 * which the previous `valueBlockSize / 2` derivation rejected without exception.
 *
 * The functional tests fill every tree far enough to split its internal nodes and then drain it again, so that the
 * derived minimums are exercised by the steal / merge paths and not only by the constructor premises.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Single-block-size B+ tree constructors")
@Tag(INDEXING)
@Tag(DATA_TYPE)
class SingleBlockSizeConstructorTest {
	/**
	 * Seed of the shuffles, fixed so that a failure reproduces.
	 */
	private static final long SEED = 42L;
	/**
	 * Extracts the tree height from the message of a consistent tree's {@link ConsistencyReport}.
	 */
	private static final Pattern HEIGHT_PATTERN = Pattern.compile("height of (\\d+) levels");

	/**
	 * Returns the number of keys that guarantees a tree of at least three levels, i.e. one whose internal nodes have
	 * split. A two-level tree holds at most `(internalNodeBlockSize + 1) * valueBlockSize` keys and the internal node
	 * block size never exceeds `valueBlockSize`, so `2 * valueBlockSize^2 + 1` keys always overflow it.
	 *
	 * @param valueBlockSize maximum number of values in a leaf node
	 * @return the number of keys to insert
	 */
	private static int keyCountForThreeLevels(int valueBlockSize) {
		return 2 * valueBlockSize * valueBlockSize + 1;
	}

	/**
	 * Returns the keys `0 .. count - 1` in a random, but reproducible order.
	 *
	 * @param count  number of keys
	 * @param random source of randomness
	 * @return shuffled keys
	 */
	@Nonnull
	private static int[] shuffledKeys(int count, @Nonnull Random random) {
		final int[] keys = new int[count];
		for (int i = 0; i < count; i++) {
			keys[i] = i;
		}
		for (int i = count - 1; i > 0; i--) {
			final int j = random.nextInt(i + 1);
			final int swap = keys[i];
			keys[i] = keys[j];
			keys[j] = swap;
		}
		return keys;
	}

	/**
	 * Asserts that the tree reports a consistent state (balanced, every node above its minimum, keys ordered).
	 *
	 * @param tree the tree to verify
	 * @return the consistency report of the tree
	 */
	@Nonnull
	private static ConsistencyReport assertConsistent(@Nonnull ConsistencySensitiveDataStructure tree) {
		final ConsistencyReport report = tree.getConsistencyReport();
		assertEquals(ConsistencyState.CONSISTENT, report.state(), report::report);
		return report;
	}

	/**
	 * Inserts `2 * valueBlockSize^2 + 1` keys in random order, verifies all of them are found and the tree is
	 * consistent, then removes them in a different random order while checking consistency along the way, and
	 * finally verifies the tree is empty and still consistent.
	 *
	 * @param valueBlockSize maximum number of values in a leaf node
	 * @param tree           the tree under test, built through its single-block-size constructor
	 * @param insert         inserts a key
	 * @param delete         deletes a key
	 * @param contains       tests the presence of a key
	 * @param size           returns the number of keys in the tree
	 */
	private static void fillAndDrain(
		int valueBlockSize,
		@Nonnull ConsistencySensitiveDataStructure tree,
		@Nonnull IntConsumer insert,
		@Nonnull IntConsumer delete,
		@Nonnull IntPredicate contains,
		@Nonnull IntSupplier size
	) {
		final Random random = new Random(SEED);
		final int count = keyCountForThreeLevels(valueBlockSize);

		for (final int key : shuffledKeys(count, random)) {
			insert.accept(key);
		}
		assertEquals(count, size.getAsInt());
		for (int key = 0; key < count; key++) {
			assertTrue(contains.test(key), "Key " + key + " is missing.");
		}
		final ConsistencyReport filledReport = assertConsistent(tree);
		// the key count must have split the internal nodes, otherwise their derived sizes would go unexercised;
		// the reported height counts the levels above the leaves, so a three-level tree reports 2
		final Matcher heightMatcher = HEIGHT_PATTERN.matcher(filledReport.report());
		assertTrue(heightMatcher.find(), filledReport::report);
		assertTrue(Integer.parseInt(heightMatcher.group(1)) >= 2, filledReport::report);

		// verify consistency at a handful of checkpoints, so the steal / merge paths are observed mid-drain
		final int[] deletionOrder = shuffledKeys(count, random);
		final int checkpoint = Math.max(1, count / 8);
		for (int i = 0; i < deletionOrder.length; i++) {
			delete.accept(deletionOrder[i]);
			if (i % checkpoint == 0) {
				assertConsistent(tree);
			}
		}
		assertEquals(0, size.getAsInt());
		assertConsistent(tree);
	}

	@DisplayName("derived block sizes satisfy the premises for every size and match the old derivation for odd ones")
	@Test
	void shouldDeriveValidBlockSizesForEverySize() {
		for (int valueBlockSize = 3; valueBlockSize <= 1024; valueBlockSize++) {
			final int minValueBlockSize = deriveMinBlockSize(valueBlockSize);
			final int internalNodeBlockSize = deriveInternalNodeBlockSize(valueBlockSize);
			final int minInternalNodeBlockSize = deriveMinBlockSize(internalNodeBlockSize);
			final String context = "valueBlockSize = " + valueBlockSize;

			assertTrue(minValueBlockSize >= 1, context);
			assertTrue(minValueBlockSize <= Math.ceil(valueBlockSize / 2.0) - 1, context);
			assertTrue(internalNodeBlockSize >= 3, context);
			assertEquals(1, internalNodeBlockSize % 2, context);
			assertTrue(internalNodeBlockSize <= valueBlockSize, context);
			assertTrue(minInternalNodeBlockSize >= 1, context);
			assertTrue(minInternalNodeBlockSize <= Math.ceil(internalNodeBlockSize / 2.0) - 1, context);

			if (valueBlockSize % 2 == 1) {
				// odd sizes always worked; their tree shape must not change
				assertEquals(valueBlockSize / 2, minValueBlockSize, context);
				assertEquals(valueBlockSize, internalNodeBlockSize, context);
				assertEquals(valueBlockSize / 2, minInternalNodeBlockSize, context);
			} else {
				// even sizes keep the widest internal node the premises allow
				assertEquals(valueBlockSize - 1, internalNodeBlockSize, context);
			}
		}
	}

	@DisplayName("bucket tree is built through the single-size constructor and survives splits and merges")
	@ParameterizedTest(name = "valueBlockSize = {0}")
	@ValueSource(ints = {3, 4, 5, 6, 7, 8, 10, 64, 255, 256})
	void shouldBuildBucketTree(int valueBlockSize) {
		final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(
			valueBlockSize, Integer.class
		);
		assertEquals(valueBlockSize, tree.getValueBlockSize());
		assertEquals(deriveInternalNodeBlockSize(valueBlockSize), tree.getInternalNodeBlockSize());
		fillAndDrain(
			valueBlockSize, tree,
			key -> tree.addRecord(key, key),
			key -> tree.removeRecord(key, key),
			tree::contains,
			tree::size
		);
	}

	@DisplayName("element tree is built through the single-size constructor and survives splits and merges")
	@ParameterizedTest(name = "valueBlockSize = {0}")
	@ValueSource(ints = {3, 4, 5, 6, 7, 8, 10, 64, 255, 256})
	void shouldBuildElementTree(int valueBlockSize) {
		final TransactionalElementBPlusTree<Integer> tree = new TransactionalElementBPlusTree<>(
			valueBlockSize, Integer.class, Integer::intValue
		);
		assertEquals(valueBlockSize, tree.getValueBlockSize());
		assertEquals(deriveInternalNodeBlockSize(valueBlockSize), tree.getInternalNodeBlockSize());
		fillAndDrain(
			valueBlockSize, tree,
			tree::insert,
			tree::delete,
			key -> tree.search(key) != null,
			tree::size
		);
	}

	@DisplayName("int-to-long tree is built through the single-size constructor and survives splits and merges")
	@ParameterizedTest(name = "valueBlockSize = {0}")
	@ValueSource(ints = {3, 4, 5, 6, 7, 8, 10, 64, 255, 256})
	void shouldBuildIntToLongTree(int valueBlockSize) {
		final TransactionalIntToLongBPlusTree tree = new TransactionalIntToLongBPlusTree(valueBlockSize);
		assertEquals(valueBlockSize, tree.getValueBlockSize());
		assertEquals(deriveInternalNodeBlockSize(valueBlockSize), tree.getInternalNodeBlockSize());
		fillAndDrain(
			valueBlockSize, tree,
			key -> tree.insert(key, key * 10L),
			tree::delete,
			key -> tree.search(key).isPresent(),
			tree::size
		);
	}

	@DisplayName("long tree is built through the single-size constructor and survives splits and merges")
	@ParameterizedTest(name = "valueBlockSize = {0}")
	@ValueSource(ints = {3, 4, 5, 6, 7, 8, 10, 64, 255, 256})
	void shouldBuildLongTree(int valueBlockSize) {
		final TransactionalLongBPlusTree<String> tree = new TransactionalLongBPlusTree<>(valueBlockSize, String.class);
		assertEquals(valueBlockSize, tree.getValueBlockSize());
		assertEquals(deriveInternalNodeBlockSize(valueBlockSize), tree.getInternalNodeBlockSize());
		fillAndDrain(
			valueBlockSize, tree,
			key -> tree.insert(key, String.valueOf(key)),
			tree::delete,
			key -> tree.search(key).isPresent(),
			tree::size
		);
	}

	@DisplayName("object tree is built through the single-size constructor and survives splits and merges")
	@ParameterizedTest(name = "valueBlockSize = {0}")
	@ValueSource(ints = {3, 4, 5, 6, 7, 8, 10, 64, 255, 256})
	void shouldBuildObjectTree(int valueBlockSize) {
		final TransactionalObjectBPlusTree<Integer, String> tree = new TransactionalObjectBPlusTree<>(
			valueBlockSize, Integer.class, String.class
		);
		assertEquals(valueBlockSize, tree.getValueBlockSize());
		assertEquals(deriveInternalNodeBlockSize(valueBlockSize), tree.getInternalNodeBlockSize());
		fillAndDrain(
			valueBlockSize, tree,
			key -> tree.insert(key, String.valueOf(key)),
			tree::delete,
			key -> tree.search(key).isPresent(),
			tree::size
		);
	}

}
