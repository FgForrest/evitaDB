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

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;
import java.util.Random;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the additive value→single-`long` (UNIQUE) payload API of {@link TransactionalBucketBPlusTree} built via
 * {@link TransactionalBucketBPlusTree#withLongPayload}. Exercises insert / point-lookup / remove of `long` payloads
 * across the full 64-bit range (small, `> Integer.MAX_VALUE`, negative, packed bit-field, extreme), absent lookups,
 * leaf-split survival verified through an ordered {@link BucketCursor#longRecordId()} walk, the MVCC commit path, and
 * the mutual-exclusion guards that reject the `int` record-set API on a long tree (and vice versa).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Transactional bucket B+ tree — long payload")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class TransactionalBucketBPlusTreeLongPayloadTest {

	/**
	 * Builds an empty long-payload tree keyed by {@link Integer} in natural order with the given leaf / internal block
	 * size, selecting the primitive key column via {@link ValueColumnFactory#forKey}.
	 *
	 * @param blockSize the leaf and internal node block size (use a small value to force splits)
	 * @return a fresh empty long-payload tree
	 */
	@Nonnull
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static TransactionalBucketBPlusTree<Integer> newLongTree(int blockSize) {
		final ValueColumnFactory factory =
			ValueColumnFactory.forKey(Integer.class, (Comparator) Comparator.naturalOrder());
		//noinspection unchecked
		return TransactionalBucketBPlusTree.withLongPayload(
			blockSize, blockSize / 2,
			blockSize, blockSize / 2,
			Integer.class, null, factory
		);
	}

	/**
	 * Produces a deterministic `long` payload for the given key that spans the full 64-bit range as the key varies:
	 * small positive, `> Integer.MAX_VALUE`, negative, a packed bit-field, and an extreme near {@link Long#MIN_VALUE}.
	 *
	 * @param key the bucket key
	 * @return the payload to store for the key
	 */
	private static long payloadFor(int key) {
		return switch (Math.floorMod(key, 5)) {
			case 0 -> key;                                  // small positive (fits int)
			case 1 -> Integer.MAX_VALUE + (long) key + 1L;  // > Integer.MAX_VALUE
			case 2 -> -((long) key) - 1L;                   // negative (fits int)
			case 3 -> ((long) key << 40) | 0x0000_00AB_CDEFL; // packed bit-field, high bits set
			default -> Long.MIN_VALUE + key;                // extreme negative
		};
	}

	@Nested
	@DisplayName("non-transactional")
	class NonTransactional {

		@Test
		@DisplayName("round-trips every payload across the full 64-bit range")
		void shouldRoundTripFullRangePayloads() {
			final TransactionalBucketBPlusTree<Integer> tree = newLongTree(9);
			for (int key = 0; key < 200; key++) {
				tree.addLongRecord(key, payloadFor(key));
			}
			assertEquals(200, tree.size());
			assertEquals(200, tree.bucketCount());
			for (int key = 0; key < 200; key++) {
				final OptionalLong payload = tree.getLongRecordEqualTo(key);
				assertTrue(payload.isPresent(), "Payload for key " + key + " must be present!");
				assertEquals(payloadFor(key), payload.getAsLong(), "Payload mismatch for key " + key);
			}
		}

		@Test
		@DisplayName("stores the exact boundary values verbatim")
		void shouldStoreBoundaryValuesVerbatim() {
			final TransactionalBucketBPlusTree<Integer> tree = newLongTree(5);
			tree.addLongRecord(1, Long.MAX_VALUE);
			tree.addLongRecord(2, Long.MIN_VALUE);
			tree.addLongRecord(3, 0L);
			tree.addLongRecord(4, (long) Integer.MAX_VALUE + 1L);
			tree.addLongRecord(5, (long) Integer.MIN_VALUE - 1L);
			assertEquals(Long.MAX_VALUE, tree.getLongRecordEqualTo(1).getAsLong());
			assertEquals(Long.MIN_VALUE, tree.getLongRecordEqualTo(2).getAsLong());
			assertEquals(0L, tree.getLongRecordEqualTo(3).getAsLong());
			assertEquals((long) Integer.MAX_VALUE + 1L, tree.getLongRecordEqualTo(4).getAsLong());
			assertEquals((long) Integer.MIN_VALUE - 1L, tree.getLongRecordEqualTo(5).getAsLong());
		}

		@Test
		@DisplayName("returns empty for absent and null lookups")
		void shouldReturnEmptyForAbsent() {
			final TransactionalBucketBPlusTree<Integer> tree = newLongTree(5);
			tree.addLongRecord(10, 1_000L);
			tree.addLongRecord(20, 2_000L);
			assertEquals(OptionalLong.empty(), tree.getLongRecordEqualTo(15));
			assertEquals(OptionalLong.empty(), tree.getLongRecordEqualTo(-1));
			assertEquals(OptionalLong.empty(), tree.getLongRecordEqualTo(null));
			assertFalse(tree.getLongRecordEqualTo(15).isPresent());
		}

		@Test
		@DisplayName("removes a payload bucket and rebalances")
		void shouldRemovePayload() {
			final TransactionalBucketBPlusTree<Integer> tree = newLongTree(5);
			for (int key = 0; key < 50; key++) {
				tree.addLongRecord(key, payloadFor(key));
			}
			assertTrue(tree.removeLongRecord(25));
			assertEquals(49, tree.size());
			assertEquals(OptionalLong.empty(), tree.getLongRecordEqualTo(25));
			// removing an absent key is a no-op
			assertFalse(tree.removeLongRecord(25));
			assertFalse(tree.removeLongRecord(999));
			assertEquals(49, tree.size());
			// every surviving key still round-trips its payload
			for (int key = 0; key < 50; key++) {
				if (key == 25) {
					continue;
				}
				assertEquals(payloadFor(key), tree.getLongRecordEqualTo(key).getAsLong());
			}
		}

		@Test
		@DisplayName("rejects inserting an already-present key")
		void shouldRejectDuplicateKey() {
			final TransactionalBucketBPlusTree<Integer> tree = newLongTree(5);
			tree.addLongRecord(7, 700L);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> tree.addLongRecord(7, 999L),
				"A unique long-payload tree must reject a present key!"
			);
		}

		@Test
		@DisplayName("survives leaf splits with every payload intact and in order via a cursor")
		void shouldSurviveSplitsAndWalkInOrder() {
			// minimal block size forces many splits across a deep tree
			final TransactionalBucketBPlusTree<Integer> tree = newLongTree(3);
			final int count = 500;
			// insert in shuffled order so the split / rebalance machinery is genuinely exercised
			final List<Integer> keys = new ArrayList<>(count);
			for (int key = 0; key < count; key++) {
				keys.add(key);
			}
			Collections.shuffle(keys, new Random(42));
			for (final int key : keys) {
				tree.addLongRecord(key, payloadFor(key));
			}
			assertEquals(count, tree.size());

			// forward cursor must enumerate buckets ascending by key, each carrying its exact payload
			final BucketCursor<Integer> cursor = tree.cursor();
			int expectedKey = 0;
			while (cursor.next()) {
				assertTrue(cursor.isSingle(), "A long-payload bucket is always single!");
				assertEquals(expectedKey, cursor.value(), "Cursor must walk keys ascending!");
				assertEquals(payloadFor(expectedKey), cursor.longRecordId(), "Payload mismatch at key " + expectedKey);
				expectedKey++;
			}
			assertEquals(count, expectedKey, "Cursor must visit every bucket!");

			// reverse cursor must enumerate the same payloads descending
			final BucketCursor<Integer> reverse = tree.reverseCursor();
			int expectedReverse = count - 1;
			while (reverse.next()) {
				assertEquals(expectedReverse, reverse.value());
				assertEquals(payloadFor(expectedReverse), reverse.longRecordId());
				expectedReverse--;
			}
			assertEquals(-1, expectedReverse, "Reverse cursor must visit every bucket!");
		}
	}

	@Nested
	@DisplayName("transactional")
	class Transactional {

		@Test
		@DisplayName("commits inserted long payloads")
		void shouldCommitInsertedPayloads() {
			final TransactionalBucketBPlusTree<Integer> tree = newLongTree(3);
			assertStateAfterCommit(
				tree,
				tested -> {
					for (int key = 0; key < 100; key++) {
						tested.addLongRecord(key, payloadFor(key));
					}
				},
				(original, committed) -> {
					assertEquals(0, original.size());
					assertEquals(100, committed.size());
					for (int key = 0; key < 100; key++) {
						assertEquals(payloadFor(key), committed.getLongRecordEqualTo(key).getAsLong());
					}
					// the pre-commit snapshot stays empty (isolation)
					assertEquals(OptionalLong.empty(), original.getLongRecordEqualTo(50));
				}
			);
		}

		@Test
		@DisplayName("commits a mix of inserts and removals")
		void shouldCommitInsertsAndRemovals() {
			final TransactionalBucketBPlusTree<Integer> tree = newLongTree(3);
			// seed the committed baseline
			for (int key = 0; key < 60; key++) {
				tree.addLongRecord(key, payloadFor(key));
			}
			assertStateAfterCommit(
				tree,
				tested -> {
					// remove every even key, add a fresh band of high keys
					for (int key = 0; key < 60; key += 2) {
						assertTrue(tested.removeLongRecord(key));
					}
					for (int key = 100; key < 130; key++) {
						tested.addLongRecord(key, payloadFor(key));
					}
				},
				(original, committed) -> {
					assertEquals(60, original.size());
					// 60 - 30 removed + 30 added = 60
					assertEquals(60, committed.size());
					for (int key = 0; key < 60; key++) {
						if (key % 2 == 0) {
							assertEquals(OptionalLong.empty(), committed.getLongRecordEqualTo(key));
						} else {
							assertEquals(payloadFor(key), committed.getLongRecordEqualTo(key).getAsLong());
						}
					}
					for (int key = 100; key < 130; key++) {
						assertEquals(payloadFor(key), committed.getLongRecordEqualTo(key).getAsLong());
					}
					// the original snapshot is untouched
					assertEquals(payloadFor(0), original.getLongRecordEqualTo(0).getAsLong());
					assertEquals(OptionalLong.empty(), original.getLongRecordEqualTo(100));
				}
			);
		}
	}

	@Nested
	@DisplayName("mode guards")
	class ModeGuards {

		@Test
		@DisplayName("rejects the int record-set API on a long-payload tree")
		void shouldRejectIntApiOnLongTree() {
			final TransactionalBucketBPlusTree<Integer> tree = newLongTree(5);
			tree.addLongRecord(1, 111L);
			assertThrows(GenericEvitaInternalError.class, () -> tree.addRecord(2, 200));
			assertThrows(GenericEvitaInternalError.class, () -> tree.addRecord(2, 200, 300));
			assertThrows(GenericEvitaInternalError.class, () -> tree.removeRecord(1, 111));
			assertThrows(GenericEvitaInternalError.class, () -> tree.getRecordsEqualTo(1));
		}

		@Test
		@DisplayName("rejects the long-payload API on an int record-set tree")
		void shouldRejectLongApiOnIntTree() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(5, Integer.class);
			tree.addRecord(1, 100);
			assertThrows(GenericEvitaInternalError.class, () -> tree.addLongRecord(2, 222L));
			assertThrows(GenericEvitaInternalError.class, () -> tree.removeLongRecord(1));
			assertThrows(GenericEvitaInternalError.class, () -> tree.getLongRecordEqualTo(1));
		}
	}
}
