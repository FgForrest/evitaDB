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

import io.evitadb.dataType.DateTimeRange;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BPlusInternalTreeNode;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BPlusLeafTreeNode;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-checks ascending seeks against an unkeyed full walk, including the complete subsequent bucket sequence and
 * transactional leaf columns. Small leaves force boundaries, splits and merges in the fast suite.
 */
@DisplayName("Bucket B+ tree forward seek")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class BucketBPlusTreeSeekTest {

	/** Builds a boxed-key tree with single, small and bitmap buckets and stable value ids. */
	@Nonnull
	private static TransactionalBucketBPlusTree<String> tree(boolean impacts, int blockSize) {
		final TransactionalBucketBPlusTree<String> tree = new TransactionalBucketBPlusTree<>(blockSize, String.class);
		tree.installValueIdMinter(new AtomicInteger(1)::getAndIncrement);
		if (impacts) {
			tree.enableImpacts();
		}
		for (int i = 0; i < 80; i++) {
			final int count = postingCount(i);
			for (int record = 0; record < count; record++) {
				add(tree, key(i * 2), record * 65536 + i, impacts);
			}
		}
		return tree;
	}

	/** Gives every thirteenth key a bitmap bucket, every third a small multi bucket and the rest a single record. */
	private static int postingCount(int i) {
		if (i % 13 == 0) {
			return 100;
		} else if (i % 3 == 0) {
			return 3;
		}
		return 1;
	}

	/** Formats a synthetic dictionary key with a shared prefix. */
	@Nonnull
	private static String key(int value) {
		return "field\0shared-term-%04d".formatted(value);
	}

	/** Builds the production dictionary column with enough entries to span several front-code restart blocks. */
	@Nonnull
	@SuppressWarnings("unchecked")
	private static TransactionalBucketBPlusTree<String> dictionaryTree(
		boolean impacts, boolean supplementary, int blockSize
	) {
		final TransactionalBucketBPlusTree<String> tree = new TransactionalBucketBPlusTree<>(
			blockSize, blockSize / 2, 7, 3, String.class, null,
			(ValueColumnFactory<String>) ValueColumnFactory.forKey(String.class, null)
		);
		if (impacts) {
			tree.enableImpacts();
		}
		for (int i = 0; i < 160; i++) {
			add(tree, dictionaryKey(i * 2, supplementary), i, impacts);
			if (i % 3 == 0) {
				add(tree, dictionaryKey(i * 2, supplementary), i + 65536, impacts);
			}
		}
		assertFrontCodedColumns(tree);
		return tree;
	}

	/** Generates both BMP-only keys and a supplementary-character corpus that requires decoder comparisons. */
	@Nonnull
	private static String dictionaryKey(int value, boolean supplementary) {
		return (supplementary ? "field\0shared-\uD83D\uDE00-term-" : "field\0shared-term-") + "%04d".formatted(value);
	}

	/** Verifies the fixture really selected the column used by the production fulltext dictionary. */
	private static void assertFrontCodedColumns(@Nonnull TransactionalBucketBPlusTree<String> tree) {
		for (final BPlusLeafTreeNode<String> leaf : tree.enumerateLeaves()) {
			assertInstanceOf(FrontCodedStringColumn.class, leaf.getKeyColumn());
		}
	}

	/** Adds a posting, preserving the tree's impact mode. */
	private static void add(
		@Nonnull TransactionalBucketBPlusTree<String> tree, @Nonnull String key, int record, boolean impacts
	) {
		if (impacts) {
			tree.addRecord(key, record, (byte) (record * 31 + key.hashCode()));
		} else {
			tree.addRecord(key, record);
		}
	}

	/** Checks every exposed bucket column at the current position. */
	private static <K extends Comparable<K>> void assertBucket(
		@Nonnull BucketCursor<K> expected, @Nonnull BucketCursor<K> actual, boolean impacts
	) {
		assertEquals(expected.value(), actual.value());
		assertEquals(expected.currentLeafId(), actual.currentLeafId());
		assertEquals(expected.valueId(), actual.valueId());
		assertEquals(expected.isSingle(), actual.isSingle());
		assertEquals(expected.size(), actual.size());
		assertArrayEquals(expected.records().getArray(), actual.records().getArray());
		if (expected.isSingle()) {
			assertEquals(expected.singleRecordId(), actual.singleRecordId());
		}
		if (impacts) {
			assertArrayEquals(expected.impacts().toArray(), actual.impacts().toArray());
		} else {
			assertThrows(GenericEvitaInternalError.class, actual::impacts);
		}
	}

	/**
	 * Advances an unkeyed walk to the first bucket at or above `lowerBound` in the tree's comparator order. The walk
	 * never runs the keyed descent that seeks and keyed cursors share, so it is an independent expectation for both.
	 */
	private static <K extends Comparable<K>> boolean advanceToLowerBound(
		@Nonnull TransactionalBucketBPlusTree<K> tree, @Nonnull BucketCursor<K> walk, @Nonnull K lowerBound
	) {
		final Comparator<K> comparator =
			tree.getComparator() == null ? Comparator.naturalOrder() : tree.getComparator();
		while (walk.next()) {
			if (comparator.compare(walk.value(), lowerBound) >= 0) {
				return true;
			}
		}
		return false;
	}

	/** Walks both cursors to exhaustion, requiring the same bucket at every step. */
	private static <K extends Comparable<K>> void assertSameRemainingBuckets(
		@Nonnull BucketCursor<K> expected, @Nonnull BucketCursor<K> actual, boolean impacts
	) {
		while (expected.next()) {
			assertTrue(actual.next());
			assertBucket(expected, actual, impacts);
		}
		assertFalse(actual.next());
	}

	/** Proves immediate positioning and every later next() against a full walk, including repeated exhaustion. */
	private static <K extends Comparable<K>> void assertSeek(
		@Nonnull TransactionalBucketBPlusTree<K> tree, @Nonnull K start, @Nonnull K target, boolean impacts
	) {
		final BucketCursor<K> actual = tree.cursor(start);
		assertTrue(actual.next());
		final BucketCursor<K> expected = tree.cursor();
		final boolean found = advanceToLowerBound(tree, expected, target);
		assertEquals(found, actual.seek(target));
		if (found) {
			assertBucket(expected, actual, impacts);
		}
		assertSameRemainingBuckets(expected, actual, impacts);
		assertFalse(actual.next());
		assertThrows(GenericEvitaInternalError.class, actual::value);
		assertThrows(GenericEvitaInternalError.class, actual::records);
		assertThrows(GenericEvitaInternalError.class, actual::currentLeafId);
		assertThrows(GenericEvitaInternalError.class, () -> actual.seek(target));
	}

	/** Runs the seek check under a heading naming the parameter combination a looping caller would otherwise lose. */
	private static <K extends Comparable<K>> void assertSeek(
		@Nonnull TransactionalBucketBPlusTree<K> tree, @Nonnull K start, @Nonnull K target, boolean impacts,
		@Nonnull String context
	) {
		assertAll(context, () -> assertSeek(tree, start, target, impacts));
	}

	/** Proves a keyed cursor starts before the bucket a full walk reaches first, then stays aligned to exhaustion. */
	private static <K extends Comparable<K>> void assertKeyedCursor(
		@Nonnull TransactionalBucketBPlusTree<K> tree, @Nonnull K lowerBound, boolean impacts
	) {
		final BucketCursor<K> expected = tree.cursor();
		final boolean found = advanceToLowerBound(tree, expected, lowerBound);
		final BucketCursor<K> actual = tree.cursor(lowerBound);
		assertThrows(GenericEvitaInternalError.class, actual::value);
		assertEquals(found, actual.next());
		if (found) {
			assertBucket(expected, actual, impacts);
		}
		assertSameRemainingBuckets(expected, actual, impacts);
		assertFalse(actual.next());
	}

	/**
	 * Fills a tree over the given key column at even positions only and seeks from every key to absent and present
	 * targets, so in-leaf seeks search the column from inside the leaf rather than from its first slot.
	 */
	@SuppressWarnings("unchecked")
	private static <K extends Comparable<K>> void assertColumnSeeks(
		@Nonnull Class<K> keyType, @Nonnull ValueColumnFactory<?> columnFactory, @Nonnull Class<?> columnType,
		@Nonnull IntFunction<K> keyAtPosition
	) {
		for (final int blockSize : new int[]{5, 63}) {
			final TransactionalBucketBPlusTree<K> tree = new TransactionalBucketBPlusTree<>(
				blockSize, blockSize / 2, blockSize, blockSize / 2, keyType, null, (ValueColumnFactory<K>) columnFactory
			);
			for (int position = 0; position < 160; position += 2) {
				tree.addRecord(keyAtPosition.apply(position), position);
			}
			for (final BPlusLeafTreeNode<K> leaf : tree.enumerateLeaves()) {
				assertInstanceOf(columnType, leaf.getKeyColumn());
			}
			for (int start = 0; start < 160; start += 2) {
				for (final int step : new int[]{1, 2, 5}) {
					assertSeek(
						tree, keyAtPosition.apply(start), keyAtPosition.apply(start + step), false,
						columnType.getSimpleName() + ", block size " + blockSize + ", start " + start + ", step " + step
					);
				}
			}
		}
	}

	@Nested
	@DisplayName("Positioning and continuation")
	class Positioning {
		/** Exercises exact and absent positions in one leaf and across several leaves in both modes. */
		@Test
		@DisplayName("matches a full walk within leaves, at boundaries, across leaves and past the end")
		void shouldMatchFullWalk() {
			for (final boolean impacts : new boolean[]{false, true}) {
				for (final int blockSize : new int[]{5, 255}) {
					final TransactionalBucketBPlusTree<String> tree = tree(impacts, blockSize);
					final String combination = "impacts " + impacts + ", block size " + blockSize;
					for (final int target : new int[]{2, 3, 8, 26, 100, 158, 159, 200}) {
						assertSeek(tree, key(0), key(target), impacts, combination + ", target " + target);
					}
					for (final BPlusLeafTreeNode<String> leaf : tree.enumerateLeaves()) {
						final String boundary = leaf.getKeyColumn().keyAt(0);
						if (boundary.compareTo(key(0)) > 0) {
							assertSeek(tree, key(0), boundary, impacts, combination + ", leaf boundary " + boundary);
						}
					}
				}
			}
		}

		/** Counts descents rather than timing execution, proving the promised intra-leaf path is actually used. */
		@Test
		@DisplayName("keeps in-leaf seeks on the captured path and descends only beyond the leaf")
		void shouldAvoidDescentWithinLeaf() {
			final AtomicInteger descents = new AtomicInteger();
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(7, Integer.class) {
				@Nonnull
				@Override
				Cursor<Integer> createCursor(@Nonnull Integer key) {
					descents.incrementAndGet();
					return super.createCursor(key);
				}
			};
			for (int i = 0; i < 80; i++) {
				tree.addRecord(i * 2, i);
			}
			final BPlusLeafTreeNode<Integer> firstLeaf = tree.enumerateLeaves().getFirst();
			final int lastInLeaf = firstLeaf.getKeyColumn().keyAt(firstLeaf.getPeek());
			final BucketCursor<Integer> cursor = tree.cursor();
			assertTrue(cursor.next());
			descents.set(0);
			assertTrue(cursor.seek(1));
			assertEquals(2, cursor.value());
			assertTrue(cursor.seek(lastInLeaf));
			assertEquals(lastInLeaf, cursor.value());
			assertEquals(0, descents.get());
			assertTrue(cursor.seek(lastInLeaf + 1));
			assertEquals(lastInLeaf + 2, cursor.value());
			assertEquals(1, descents.get());
		}

		/** Advances repeatedly without replacing the cursor, then checks continuation after each target. */
		@Test
		@DisplayName("matches random ascending seek targets in random tree shapes")
		void shouldMatchRandomAscendingSeeks() {
			final Random random = new Random(258_6);
			for (int round = 0; round < 80; round++) {
				final boolean impacts = random.nextBoolean();
				final TransactionalBucketBPlusTree<Integer> tree =
					new TransactionalBucketBPlusTree<>(3 + 2 * random.nextInt(8), Integer.class);
				if (impacts) {
					tree.enableImpacts();
				}
				final List<Integer> keys = new ArrayList<>();
				for (int i = 0; i < 200; i++) {
					if (random.nextBoolean()) {
						keys.add(i * 2);
					}
				}
				Collections.shuffle(keys, random);
				for (final int key : keys) {
					if (impacts) {
						tree.addRecord(key, key + 1, (byte) key);
					} else {
						tree.addRecord(key, key + 1);
					}
				}
				final BucketCursor<Integer> actual = tree.cursor();
				assertTrue(actual.next());
				while (true) {
					final int start = actual.value();
					final int target = start + 1 + random.nextInt(25);
					assertSeek(tree, start, target, impacts);
					final BucketCursor<Integer> expected = tree.cursor(target);
					final boolean found = expected.next();
					assertEquals(found, actual.seek(target), "round " + round + ", target " + target);
					if (!found) {
						assertFalse(actual.next());
						break;
					}
					assertBucket(expected, actual, impacts);
					// Interleave next() and seek() on the same cursor too.
					final boolean hasNext = expected.next();
					assertEquals(hasNext, actual.next());
					if (!hasNext) {
						break;
					}
					assertBucket(expected, actual, impacts);
				}
			}
		}

		/** Proves the comparator, rather than Comparable, controls both bounds and binary search. */
		@Test
		@DisplayName("uses the tree comparator for advancement and in-leaf search")
		void shouldUseComparatorOrder() {
			final TransactionalBucketBPlusTree<Integer> tree =
				new TransactionalBucketBPlusTree<>(7, 3, 7, 3, Integer.class, Comparator.reverseOrder());
			for (int i = 0; i < 30; i++) {
				tree.addRecord(i * 2, i);
			}
			for (final int target : new int[]{56, 55, 40, 2, 0, -1}) {
				assertSeek(tree, 58, target, false);
			}
			final BucketCursor<Integer> cursor = tree.cursor();
			assertTrue(cursor.next());
			assertThrows(GenericEvitaInternalError.class, () -> cursor.seek(58));
			assertThrows(GenericEvitaInternalError.class, () -> cursor.seek(60));
		}

		/** Every present key covers each leaf's first and last key; odd values fall between keys and leaves. */
		@Test
		@DisplayName("starts keyed cursors at the inclusive lower bound, below the first key and past the last")
		void shouldStartAtInclusiveLowerBoundOfCursorKey() {
			for (final boolean impacts : new boolean[]{false, true}) {
				for (final int blockSize : new int[]{5, 255}) {
					final TransactionalBucketBPlusTree<String> tree = tree(impacts, blockSize);
					final String combination = "impacts " + impacts + ", block size " + blockSize;
					for (int value = -1; value <= 161; value++) {
						final String lowerBound = key(value);
						assertAll(
							combination + ", lower bound " + value,
							() -> assertKeyedCursor(tree, lowerBound, impacts)
						);
					}
				}
			}
		}
	}

	@Nested
	@DisplayName("Production front-coded dictionary columns")
	class FrontCodedDictionary {
		/** Checks exact and absent bounds on either side of the 16-entry restart boundaries. */
		@Test
		@DisplayName("seeks across restart boundaries with BMP and supplementary corpora")
		void shouldSeekFrontCodedRestartBoundaries() {
			for (final boolean impacts : new boolean[]{false, true}) {
				for (final boolean supplementary : new boolean[]{false, true}) {
					for (final int blockSize : new int[]{63, 255}) {
						final TransactionalBucketBPlusTree<String> tree =
							dictionaryTree(impacts, supplementary, blockSize);
						final String combination =
							"impacts " + impacts + ", supplementary " + supplementary + ", block size " + blockSize;
						for (final int target : new int[]{30, 31, 32, 33, 34, 62, 63, 64, 65, 66, 254, 255, 318, 319}) {
							assertSeek(
								tree, dictionaryKey(28, supplementary), dictionaryKey(target, supplementary), impacts,
								combination + ", target " + target
							);
						}
					}
				}
			}
		}

		/** All mutations precede cursor creation, as required by the cursor's write-invalidation contract. */
		@Test
		@DisplayName("seeks layered front-coded columns around changed restart positions")
		void shouldSeekFrontCodedTransactionalColumns() {
			final int[] changedRestartTargets = {30, 31, 32, 33, 34, 35, 62, 63, 64, 65, 66};
			for (final boolean impacts : new boolean[]{false, true}) {
				for (final boolean supplementary : new boolean[]{false, true}) {
					final TransactionalBucketBPlusTree<String> tree = dictionaryTree(impacts, supplementary, 255);
					final String combination = "impacts " + impacts + ", supplementary " + supplementary;
					final String start = dictionaryKey(28, supplementary);
					assertStateAfterCommit(
						tree,
						tested -> {
							add(tested, dictionaryKey(31, supplementary), 999, impacts);
							add(tested, dictionaryKey(33, supplementary), 1000, impacts);
							add(tested, dictionaryKey(32, supplementary), 1001, impacts);
							if (impacts) {
								tested.addRecord(dictionaryKey(30, supplementary), 15, (byte) 200);
							}
							final String removed = dictionaryKey(34, supplementary);
							for (final int record : tested.getRecordsEqualTo(removed).getArray()) {
								tested.removeRecord(removed, record);
							}
							assertFrontCodedColumns(tested);
							for (final int target : changedRestartTargets) {
								assertSeek(
									tested, start, dictionaryKey(target, supplementary), impacts,
									combination + ", uncommitted, target " + target
								);
							}
						},
						(original, committed) -> {
							assertFrontCodedColumns(committed);
							for (final int target : changedRestartTargets) {
								assertSeek(
									original, start, dictionaryKey(target, supplementary), impacts,
									combination + ", original, target " + target
								);
								assertSeek(
									committed, start, dictionaryKey(target, supplementary), impacts,
									combination + ", committed, target " + target
								);
							}
						}
					);
				}
			}
		}

		/** Identity checks detect repeated decoding without measuring allocations or elapsed time. */
		@Test
		@DisplayName("reuses decoded current keys and invalidates them on every navigation path")
		void shouldReuseDecodedCurrentKeys() {
			final TransactionalBucketBPlusTree<String> tree = dictionaryTree(true, true, 63);
			final BucketCursor<String> cursor = tree.cursor();
			assertTrue(cursor.next());
			final String first = cursor.value();
			assertSame(first, cursor.value(), "repeated value reads must reuse the decoded key");
			assertThrows(GenericEvitaInternalError.class, () -> cursor.seek(first));
			assertSame(first, cursor.value(), "a rejected seek must preserve the cached current key");
			final BPlusLeafTreeNode<String> firstLeaf = tree.enumerateLeaves().getFirst();
			final String lastInLeaf = firstLeaf.getKeyColumn().keyAt(firstLeaf.getPeek());
			// Seek without reading the new value before the next premise check.
			assertTrue(cursor.seek(dictionaryKey(2, true)));
			assertTrue(cursor.seek(dictionaryKey(4, true)));
			assertEquals(dictionaryKey(4, true), cursor.value());
			assertSame(cursor.value(), cursor.value());
			assertTrue(cursor.next());
			assertEquals(dictionaryKey(6, true), cursor.value());
			assertSame(cursor.value(), cursor.value());
			assertTrue(cursor.seek(lastInLeaf));
			assertEquals(lastInLeaf, cursor.value());
			assertTrue(cursor.next());
			final String nextLeafFirst = cursor.value();
			assertNotEquals(lastInLeaf, nextLeafFirst);
			assertSame(nextLeafFirst, cursor.value());
			assertTrue(cursor.seek(dictionaryKey(250, true)));
			assertEquals(dictionaryKey(250, true), cursor.value());
			assertSame(cursor.value(), cursor.value());
			assertFalse(cursor.seek(dictionaryKey(400, true)));
			assertThrows(GenericEvitaInternalError.class, cursor::value);
		}
	}

	@Nested
	@DisplayName("Primitive and range key columns")
	class PrimitiveAndRangeKeyColumns {
		/** In-leaf seeks are the only callers that binary-search these columns from a non-zero slot. */
		@Test
		@DisplayName("seeks long, int and range columns within and across leaves")
		void shouldSeekPrimitiveAndRangeColumns() {
			assertColumnSeeks(
				Long.class, ValueColumnFactory.forKey(Long.class, null), LongValueColumn.class,
				position -> position * 3L
			);
			assertColumnSeeks(
				Integer.class, ValueColumnFactory.forKey(BigDecimal.class, null), IntValueColumn.class,
				position -> position * 3 - 100
			);
			final OffsetDateTime base = OffsetDateTime.of(2024, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
			assertColumnSeeks(
				DateTimeRange.class, ValueColumnFactory.forFilterKey(DateTimeRange.class, null, 0),
				RangeValueColumn.class,
				position -> DateTimeRange.between(base.plusHours(position), base.plusHours(position + 5))
			);
		}
	}

	@Nested
	@DisplayName("Transactions and recovery")
	class Transactions {
		/** Mutates columns and tree shape before reading, then repeats on both published views. */
		@Test
		@DisplayName("sees uncommitted columns and continues correctly on the committed copy")
		void shouldSeekTransactionalAndCommittedColumns() {
			for (final boolean impacts : new boolean[]{false, true}) {
				final TransactionalBucketBPlusTree<String> tree = tree(impacts, 7);
				assertStateAfterCommit(
					tree,
					tested -> {
						for (int i = 10; i < 55; i++) {
							for (final int record : tested.getRecordsEqualTo(key(i * 2)).getArray()) {
								tested.removeRecord(key(i * 2), record);
							}
						}
						for (int i = 1; i < 160; i += 2) {
							add(tested, key(i), -i, impacts);
						}
						add(tested, key(2), 999, impacts);
						for (final int target : new int[]{1, 2, 3, 26, 100, 111, 158, 159, 160}) {
							assertSeek(tested, key(0), key(target), impacts);
						}
					},
					(original, committed) -> {
						for (final int target : new int[]{1, 2, 26, 100, 159, 160}) {
							assertSeek(original, key(0), key(target), impacts);
							assertSeek(committed, key(0), key(target), impacts);
						}
					}
				);
			}
		}

		/** Freezes the exact window between capturing the path and loading its now-emptied bottom level. */
		@Test
		@DisplayName("preserves the first recovered bucket when a captured descent loses its leaf")
		@SuppressWarnings("unchecked")
		void shouldSeekAfterCapturedDescentRecovery() throws ReflectiveOperationException {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			for (int i = 0; i < 80; i++) {
				tree.addRecord(i, i);
			}
			// Capture the full path before emptying the donor, exactly as a session-free reader can do mid-merge.
			final TransactionalBucketBPlusTree.Cursor<Integer> path = tree.createCursor(0);
			final Class<?> cursorType = tree.cursor().getClass();
			BPlusInternalTreeNode<Integer> parent = (BPlusInternalTreeNode<Integer>) tree.getRoot();
			BPlusInternalTreeNode<Integer> victim = (BPlusInternalTreeNode<Integer>) parent.getChildren()[0];
			while (victim.getChildren()[0] instanceof BPlusInternalTreeNode<?>) {
				parent = victim;
				victim = (BPlusInternalTreeNode<Integer>) victim.getChildren()[0];
			}
			final int firstSurvivingKey = parent.getChildren()[1].getLeftBoundaryKey();
			victim.setPeek(-1);
			assertNull(victim.getChildren()[0], "the captured bottom slot must actually lose its leaf");
			assertSame(victim, parent.getChildren()[0], "the parent has not unlinked the donor yet");
			// Reflection freezes a constructor interleaving without introducing a test-only hook in the tree.
			final Constructor<?> constructor = cursorType.getDeclaredConstructor(
				TransactionalBucketBPlusTree.class, TransactionalBucketBPlusTree.Cursor.class
			);
			constructor.setAccessible(true);
			final BucketCursor<Integer> recovered = (BucketCursor<Integer>) constructor.newInstance(tree, path);
			assertThrows(GenericEvitaInternalError.class, recovered::value);
			assertTrue(recovered.next());
			assertEquals(firstSurvivingKey, recovered.value(), "recovery must not skip its first bucket");
			assertTrue(recovered.seek(firstSurvivingKey + 1));
			final BucketCursor<Integer> expected = tree.cursor(firstSurvivingKey + 1);
			assertTrue(expected.next());
			assertBucket(expected, recovered, false);
			assertSameRemainingBuckets(expected, recovered, false);
		}

		/** Parks a merge donor before its parent unlinks it; no timing-dependent stress loop is needed. */
		@Test
		@DisplayName("seeks after recovering past an emptied leftmost subtree")
		void shouldSeekAfterLeftmostRecovery() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(3, Integer.class);
			for (int i = 0; i < 80; i++) {
				tree.addRecord(i, i);
			}
			final BPlusInternalTreeNode<Integer> root = (BPlusInternalTreeNode<Integer>) tree.getRoot();
			assertInstanceOf(BPlusInternalTreeNode.class, root.getChildren()[0], "the root needs an internal child");
			root.getChildren()[0].setPeek(-1);
			final BucketCursor<Integer> recovered = tree.cursor();
			assertTrue(recovered.next());
			final int start = recovered.value();
			assertTrue(start > 0);
			assertSeek(tree, start, start + 1, false);
			assertTrue(recovered.seek(start + 1));
			final BucketCursor<Integer> expected = tree.cursor(start + 1);
			assertTrue(expected.next());
			assertBucket(expected, recovered, false);
			assertSameRemainingBuckets(expected, recovered, false);
		}
	}

	@Nested
	@DisplayName("Programming errors")
	class ProgrammingErrors {
		/** Failed premises must leave the valid position intact. */
		@Test
		@DisplayName("refuses backward, equal, unpositioned and exhausted seeks")
		void shouldRejectInvalidSeeks() {
			final TransactionalBucketBPlusTree<String> tree = tree(false, 7);
			final BucketCursor<String> cursor = tree.cursor(key(20));
			assertThrows(GenericEvitaInternalError.class, () -> cursor.seek(key(22)));
			assertTrue(cursor.next());
			assertThrows(GenericEvitaInternalError.class, () -> cursor.seek(key(20)));
			assertThrows(GenericEvitaInternalError.class, () -> cursor.seek(key(18)));
			assertEquals(key(20), cursor.value());
			assertTrue(cursor.seek(key(22)));
			assertFalse(cursor.seek(key(200)));
			assertThrows(GenericEvitaInternalError.class, () -> cursor.seek(key(202)));
			final BucketCursor<String> empty = new TransactionalBucketBPlusTree<>(7, String.class).cursor();
			assertFalse(empty.next());
			assertThrows(GenericEvitaInternalError.class, () -> empty.seek(key(1)));
		}

		/** The page emitter and reverse walk must never silently accept the forward-seek contract. */
		@Test
		@DisplayName("explicitly refuses seeks on reverse and page-scoped cursors")
		void shouldRejectUnsupportedCursors() {
			final TransactionalBucketBPlusTree<String> tree = tree(false, 7);
			final BucketCursor<String> reverse = tree.reverseCursor();
			assertTrue(reverse.next());
			assertThrows(GenericEvitaInternalError.class, () -> reverse.seek(key(200)));
			final BucketCursor<String> page = tree.leafPageHandles().getFirst().cursor();
			assertTrue(page.next());
			assertThrows(GenericEvitaInternalError.class, () -> page.seek(key(2)));
		}
	}
}
