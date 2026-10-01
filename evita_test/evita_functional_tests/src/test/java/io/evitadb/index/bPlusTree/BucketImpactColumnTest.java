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

import io.evitadb.core.transaction.Transaction;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bPlusTree.ImpactRecords.PendingImpacts;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BPlusLeafTreeNode;
import io.evitadb.index.bPlusTree.TransactionalBucketBPlusTree.BucketCursor;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PrimitiveIterator.OfInt;
import java.util.Random;
import java.util.function.Consumer;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertSavepointRollbackRestores;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static io.evitadb.utils.AssertionUtils.assertWarmUpSavepointRollbackRestores;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Randomized oracle test of the impact column of a {@link TransactionalBucketBPlusTree}: random insert / remove
 * sequences over a few hundred keys holding from one to tens of thousands of records each, replayed against a plain
 * `Map` oracle, asserting after every commit that the impacts read back for every bucket equal the oracle's in the
 * order the bucket enumerates its records, and that every impact slot has the shape the bucket's record tier
 * dictates. The sequences run outside a transaction, inside committed transactions and inside rolled-back ones, over
 * leaf block sizes small enough that the keys split, steal and merge leaves constantly, and with per-key record
 * caps that take buckets through every promotion and demotion.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
@DisplayName("Impact column of the bucket B+ tree")
class BucketImpactColumnTest {
	/**
	 * How many distinct keys the random sequences draw from.
	 */
	private static final int KEY_SPACE = 300;
	/**
	 * The key the burst phases fill with over ten thousand records - a "heavy" key whose ids are spread over
	 * hundreds of roaring containers.
	 */
	private static final int HEAVY_SPREAD_KEY = 9;
	/**
	 * A heavy key whose ids are dense, so its records share one roaring container.
	 */
	private static final int HEAVY_DENSE_KEY = 18;

	/**
	 * @param blockSize the leaf block size
	 * @return an empty, impact-carrying tree
	 */
	@Nonnull
	private static TransactionalBucketBPlusTree<Integer> emptyImpactTree(int blockSize) {
		final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(blockSize, Integer.class);
		tree.enableImpacts();
		return tree;
	}

	/**
	 * The most records a key may accumulate: seven keys in ten stay single or tiny (the primitive and array tiers),
	 * two in ten straddle the array / bitmap boundary, one in ten is heavy (thousands of records, the bitmap tier
	 * with many containers).
	 *
	 * @param key the bucket key
	 * @return the key's record cap
	 */
	private static int capOf(int key) {
		final int bucket = key % 10;
		if (bucket < 7) {
			return 1 + key % 4;
		}
		if (bucket < 9) {
			return 5 + (key * 37) % 300;
		}
		return 2_000 + (key * 101) % 18_001;
	}

	/**
	 * Derives the `ordinal`-th record id of `key`. Even keys are dense - consecutive ids inside one roaring
	 * container - and odd keys are spread over the whole signed range, so negative ids, the unsigned bucket order and
	 * container boundaries are all exercised.
	 *
	 * @param key     the bucket key
	 * @param ordinal the record's ordinal within the key
	 * @return the record id
	 */
	private static int pkOf(int key, int ordinal) {
		return (key & 1) == 0
			? (key << 16) + ordinal
			: (key * 1_000_003 + ordinal * 7_919) ^ 0x5bd1_e995;
	}

	/**
	 * Inserts `count` records with random impacts into `key`, on the tree and the oracle alike.
	 *
	 * @param random the impact source
	 * @param tree   the tree under test
	 * @param oracle the reference content
	 * @param key    the key to fill
	 * @param count  how many distinct ordinals to insert
	 */
	private static void burst(
		@Nonnull Random random,
		@Nonnull TransactionalBucketBPlusTree<Integer> tree,
		@Nonnull Map<Integer, Map<Integer, Byte>> oracle,
		int key,
		int count
	) {
		final Map<Integer, Byte> bucket = oracle.computeIfAbsent(key, k -> CollectionUtils.createHashMap(count));
		for (int ordinal = 0; ordinal < count; ordinal++) {
			final int pk = pkOf(key, ordinal);
			final byte impact = (byte) random.nextInt(256);
			tree.addRecord(key, pk, impact);
			bucket.put(pk, impact);
		}
	}

	/**
	 * Applies `opCount` random operations to the tree and to the oracle alike: an insert of one or (for a heavy key)
	 * a handful of records with random impacts, replacing the impact when the record is already there; or a removal
	 * of one, a few, or - one time in ten - up to half of the records the key holds in a single call, which is what
	 * drives a bitmap bucket back down through the demotion threshold.
	 *
	 * @param random  the sequence source
	 * @param tree    the tree under test
	 * @param oracle  the reference content, `key -> (pk -> impact)`
	 * @param opCount how many operations to apply
	 */
	private static void applyRandomOperations(
		@Nonnull Random random,
		@Nonnull TransactionalBucketBPlusTree<Integer> tree,
		@Nonnull Map<Integer, Map<Integer, Byte>> oracle,
		int opCount
	) {
		for (int op = 0; op < opCount; op++) {
			final int key = random.nextInt(KEY_SPACE);
			final int cap = capOf(key);
			final Map<Integer, Byte> bucket = oracle.get(key);
			if (bucket == null || (bucket.size() < cap && random.nextInt(4) != 0)) {
				final int inserts = cap >= 2_000 ? 1 + random.nextInt(32) : 1;
				final Map<Integer, Byte> target = oracle.computeIfAbsent(
					key, k -> CollectionUtils.createHashMap(Math.min(cap, 64))
				);
				for (int i = 0; i < inserts; i++) {
					final int pk = pkOf(key, random.nextInt(cap));
					final byte impact = (byte) random.nextInt(256);
					tree.addRecord(key, pk, impact);
					target.put(pk, impact);
				}
			} else {
				final int removals = random.nextInt(10) == 0
					? Math.max(1, bucket.size() / 2) : 1 + random.nextInt(Math.min(3, bucket.size()));
				final int[] pks = new int[removals];
				final Iterator<Integer> it = bucket.keySet().iterator();
				for (int i = 0; i < removals; i++) {
					pks[i] = it.next();
				}
				tree.removeRecord(key, pks);
				for (final int pk : pks) {
					bucket.remove(pk);
				}
				if (bucket.isEmpty()) {
					oracle.remove(key);
				}
			}
		}
	}

	/**
	 * Asserts that the tree holds exactly the oracle's buckets, that every record's impact reads back as the oracle
	 * says in the order the bucket enumerates its records, that every impact slot has the shape of its record tier,
	 * and that every leaf's columns are aligned.
	 *
	 * @param tree   the tree to check
	 * @param oracle the reference content
	 */
	private static void assertMatchesOracle(
		@Nonnull TransactionalBucketBPlusTree<Integer> tree,
		@Nonnull Map<Integer, Map<Integer, Byte>> oracle
	) {
		assertEquals(oracle.size(), tree.size(), "the tree must hold exactly the oracle's buckets");
		for (final Entry<Integer, Map<Integer, Byte>> entry : oracle.entrySet()) {
			final int key = entry.getKey();
			final Map<Integer, Byte> expected = entry.getValue();
			final Bitmap records = tree.getRecordsEqualTo(key);
			final byte[] impacts = tree.impactsOf(key);
			assertEquals(expected.size(), records.size(), "record count differs at key " + key);
			assertEquals(records.size(), impacts.length, "impact count differs at key " + key);
			final OfInt it = records.iterator();
			int ordinal = 0;
			while (it.hasNext()) {
				final int pk = it.nextInt();
				final Byte expectedImpact = expected.get(pk);
				assertNotNull(expectedImpact, "record " + pk + " at key " + key + " is not in the oracle");
				assertEquals(
					expectedImpact.byteValue(), impacts[ordinal],
					"impact of record " + pk + " (ordinal " + ordinal + ") at key " + key
				);
				ordinal++;
			}
		}
		assertCursorImpactsMatch(tree);
		for (final BPlusLeafTreeNode<Integer> leaf : tree.enumerateLeaves()) {
			assertColumnsAligned(leaf);
			final OverflowColumn impacts = leaf.getImpacts();
			assertNotNull(impacts, "every leaf of an impact-carrying tree carries the column");
			final OverflowColumn overflow = leaf.getOverflow();
			for (int slot = 0; slot <= leaf.getPeek(); slot++) {
				assertSlotShape(
					overflow == null ? null : overflow.recordsAt(slot), impacts.recordsAt(slot), leaf.keyAt(slot)
				);
			}
		}
	}

	/**
	 * Asserts that every kind of bucket cursor - forward, reverse and the leaf-scoped one of the page handles - hands
	 * out, for every bucket it visits, the impacts {@link TransactionalBucketBPlusTree#impactsOf} reads with a descent,
	 * and that between them they visit every bucket.
	 *
	 * @param tree the tree to check
	 */
	private static void assertCursorImpactsMatch(@Nonnull TransactionalBucketBPlusTree<Integer> tree) {
		int forwardVisits = 0;
		final BucketCursor<Integer> forward = tree.cursor();
		while (forward.next()) {
			assertViewMatches(forward, tree);
			forwardVisits++;
		}
		int reverseVisits = 0;
		final BucketCursor<Integer> reverse = tree.reverseCursor();
		while (reverse.next()) {
			assertViewMatches(reverse, tree);
			reverseVisits++;
		}
		int leafVisits = 0;
		for (final TransactionalBucketBPlusTree.LeafPageHandle<Integer> handle : tree.leafPageHandles()) {
			final BucketCursor<Integer> leafCursor = handle.cursor();
			while (leafCursor.next()) {
				assertViewMatches(leafCursor, tree);
				leafVisits++;
			}
		}
		assertEquals(tree.size(), forwardVisits, "the forward cursor must visit every bucket");
		assertEquals(tree.size(), reverseVisits, "the reverse cursor must visit every bucket");
		assertEquals(tree.size(), leafVisits, "the leaf cursors must visit every bucket");
	}

	/**
	 * Asserts that the impacts a cursor hands out for its current bucket equal the ones read with a descent: as a
	 * copy, through one reader ascending - the access of a merge - and through the same reader descending, which makes
	 * a chunked view seek backwards across its chunk boundaries.
	 *
	 * @param cursor the cursor, positioned at a bucket
	 * @param tree   the tree the cursor walks
	 */
	private static void assertViewMatches(
		@Nonnull BucketCursor<Integer> cursor,
		@Nonnull TransactionalBucketBPlusTree<Integer> tree
	) {
		final Integer key = cursor.value();
		final byte[] expected = tree.impactsOf(key);
		final ImpactView view = cursor.impacts();
		assertEquals(cursor.size(), view.size(), "view size at key " + key);
		assertArrayEquals(expected, view.toArray(), "impacts at key " + key);
		final ImpactView.Reader reader = view.reader();
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], reader.impactAt(i), "ascending read " + i + " at key " + key);
		}
		for (int i = expected.length - 1; i >= 0; i--) {
			assertEquals(expected[i], reader.impactAt(i), "descending read " + i + " at key " + key);
		}
	}

	/**
	 * Asserts that an impact slot has the shape the bucket's record tier dictates: a cached `Byte` beside a single
	 * record, a `byte[]` of the same length beside a sorted `int[]`, and beside a bitmap one `byte[]` chunk per
	 * roaring container holding exactly that container's cardinality - or, inside an open transaction that wrote to
	 * the bucket, the pending delta that is aligned at commit.
	 *
	 * @param records the bucket's record slot (`null` for a single record)
	 * @param impacts the bucket's impact slot
	 * @param key     the bucket key, for the failure message
	 */
	private static void assertSlotShape(@Nullable Object records, @Nullable Object impacts, @Nonnull Integer key) {
		if (records == null) {
			assertInstanceOf(Byte.class, impacts, "a single-record bucket holds a cached Byte at key " + key);
		} else if (records instanceof final int[] small) {
			final byte[] array = assertInstanceOf(
				byte[].class, impacts, "an array bucket holds a byte[] at key " + key
			);
			assertEquals(small.length, array.length, "the impact array must be index-parallel at key " + key);
		} else if (records instanceof final TransactionalBitmap bitmap) {
			if (impacts instanceof PendingImpacts) {
				assertTrue(
					Transaction.isTransactionAvailable(),
					"a pending delta may exist only inside an open transaction, found one at key " + key
				);
				return;
			}
			final byte[][] chunks = assertInstanceOf(
				byte[][].class, impacts, "a bitmap bucket holds one chunk per container at key " + key
			);
			final OfInt it = bitmap.iterator();
			int chunk = -1;
			int previousContainer = -1;
			int inContainer = 0;
			while (it.hasNext()) {
				final int container = it.nextInt() >>> 16;
				if (container != previousContainer) {
					if (chunk >= 0) {
						assertEquals(inContainer, chunks[chunk].length, "chunk " + chunk + " length at key " + key);
					}
					chunk++;
					previousContainer = container;
					inContainer = 0;
				}
				inContainer++;
			}
			assertEquals(chunk + 1, chunks.length, "the chunk count must equal the container count at key " + key);
			if (chunk >= 0) {
				assertEquals(inContainer, chunks[chunk].length, "last chunk length at key " + key);
			}
		} else {
			throw new AssertionError("unexpected record slot " + records.getClass() + " at key " + key);
		}
	}

	/**
	 * Asserts the leaf-column alignment invariant: each column's live run covers exactly the buckets the leaf holds.
	 *
	 * @param leaf the leaf to check
	 */
	private static void assertColumnsAligned(@Nonnull BPlusLeafTreeNode<Integer> leaf) {
		final int expected = leaf.getPeek() + 1;
		assertEquals(expected, leaf.getKeyColumn().size(), "key column misaligned");
		assertEquals(expected, leaf.getRecords().size(), "record column misaligned");
		final OverflowColumn impacts = leaf.getImpacts();
		if (impacts != null) {
			assertEquals(expected, impacts.size(), "impact column misaligned");
		}
		final OverflowColumn overflow = leaf.getOverflow();
		if (overflow != null) {
			assertEquals(expected, overflow.size(), "overflow column misaligned");
		}
	}

	/**
	 * Deep-copies the oracle so a rolled-back round can be compared against the content it started from.
	 *
	 * @param oracle the oracle to copy
	 * @return an independent copy
	 */
	@Nonnull
	private static Map<Integer, Map<Integer, Byte>> copyOf(@Nonnull Map<Integer, Map<Integer, Byte>> oracle) {
		final Map<Integer, Map<Integer, Byte>> copy = CollectionUtils.createHashMap(oracle.size());
		for (final Entry<Integer, Map<Integer, Byte>> entry : oracle.entrySet()) {
			final Map<Integer, Byte> bucket = CollectionUtils.createHashMap(entry.getValue().size());
			bucket.putAll(entry.getValue());
			copy.put(entry.getKey(), bucket);
		}
		return copy;
	}

	/**
	 * Reads the raw impact slot of `key`'s bucket.
	 *
	 * @param tree the tree to look into
	 * @param key  the bucket key
	 * @return the slot content
	 */
	@Nullable
	private static Object impactSlot(@Nonnull TransactionalBucketBPlusTree<Integer> tree, int key) {
		final BPlusLeafTreeNode<Integer> leaf = tree.findLeafNode(key);
		final OverflowColumn impacts = leaf.getImpacts();
		assertNotNull(impacts);
		return impacts.recordsAt(leaf.getValueIndex(key));
	}

	@Nested
	@DisplayName("Randomized oracle")
	class RandomizedOracle {

		@ParameterizedTest(name = "seed {0}")
		@ValueSource(longs = {42L, 7L, 1_234L})
		@DisplayName("impacts stay aligned through random inserts and removals outside a transaction")
		void shouldKeepImpactsAlignedOutsideTransactions(long seed) {
			for (final int blockSize : new int[]{5, 16}) {
				final Random random = new Random(seed);
				final TransactionalBucketBPlusTree<Integer> tree = emptyImpactTree(blockSize);
				final Map<Integer, Map<Integer, Byte>> oracle = CollectionUtils.createHashMap(KEY_SPACE);
				// two heavy buckets up front: one spread over hundreds of containers, one dense in a single one
				burst(random, tree, oracle, HEAVY_SPREAD_KEY, 12_000);
				burst(random, tree, oracle, HEAVY_DENSE_KEY, 12_000);
				assertMatchesOracle(tree, oracle);
				for (int round = 0; round < 10; round++) {
					applyRandomOperations(random, tree, oracle, 1_500);
					assertMatchesOracle(tree, oracle);
				}
				assertFalse(
					oracle.isEmpty(), "the sequence must have left buckets behind for the check to mean anything"
				);
			}
		}

		@ParameterizedTest(name = "seed {0}")
		@ValueSource(longs = {42L, 7L, 1_234L})
		@DisplayName("impacts stay aligned across committed transactions")
		void shouldKeepImpactsAlignedAcrossCommits(long seed) {
			for (final int blockSize : new int[]{5, 16}) {
				final Random random = new Random(seed);
				final Map<Integer, Map<Integer, Byte>> oracle = CollectionUtils.createHashMap(KEY_SPACE);
				TransactionalBucketBPlusTree<Integer> tree = emptyImpactTree(blockSize);
				// a warm-up prefix so the transactions start from a populated tree with every tier present
				burst(random, tree, oracle, HEAVY_SPREAD_KEY, 4_000);
				applyRandomOperations(random, tree, oracle, 3_000);
				assertMatchesOracle(tree, oracle);
				for (int round = 0; round < 8; round++) {
					final Map<Integer, Map<Integer, Byte>> before = copyOf(oracle);
					final TransactionalBucketBPlusTree<Integer>[] next = new TransactionalBucketBPlusTree[1];
					final int theRound = round;
					assertStateAfterCommit(
						tree,
						t -> {
							if (theRound == 2) {
								// a transaction that pours thousands of records into a heavy bucket: the pending
								// delta is as large as the committed bitmap it is aligned with
								burst(random, t, oracle, HEAVY_DENSE_KEY, 3_000);
							}
							applyRandomOperations(random, t, oracle, 800);
							// the writing transaction sees its own impacts, pending deltas included
							assertMatchesOracle(t, oracle);
						},
						(original, committed) -> {
							assertMatchesOracle(committed, oracle);
							assertMatchesOracle(original, before);
							next[0] = committed;
						}
					);
					tree = next[0];
				}
			}
		}

		@ParameterizedTest(name = "seed {0}")
		@ValueSource(longs = {42L, 7L, 1_234L})
		@DisplayName("a rolled-back transaction leaves the committed impacts untouched")
		void shouldDiscardImpactsOnRollback(long seed) {
			for (final int blockSize : new int[]{5, 16}) {
				final Random random = new Random(seed);
				final Map<Integer, Map<Integer, Byte>> oracle = CollectionUtils.createHashMap(KEY_SPACE);
				final TransactionalBucketBPlusTree<Integer> tree = emptyImpactTree(blockSize);
				burst(random, tree, oracle, HEAVY_SPREAD_KEY, 4_000);
				applyRandomOperations(random, tree, oracle, 3_000);
				for (int round = 0; round < 5; round++) {
					final Map<Integer, Map<Integer, Byte>> scratch = copyOf(oracle);
					assertStateAfterRollback(
						tree,
						t -> {
							applyRandomOperations(random, t, scratch, 800);
							// the writing transaction sees its own impacts
							assertMatchesOracle(t, scratch);
						},
						(original, committed) -> assertMatchesOracle(original, oracle)
					);
				}
			}
		}
	}

	@Nested
	@DisplayName("Tier transitions")
	class TierTransitions {

		@Test
		@DisplayName("the second record carries both impacts into the array tier in unsigned id order")
		void shouldPromoteSingleToArrayWithImpacts() {
			final TransactionalBucketBPlusTree<Integer> tree = emptyImpactTree(5);
			tree.addRecord(1, 20, (byte) 7);
			tree.addRecord(1, -3, (byte) 9);
			final byte[] array = assertInstanceOf(byte[].class, impactSlot(tree, 1));
			// -3 is the larger id under unsigned order, so it sits second - exactly where the record array puts it
			assertArrayEquals(new byte[]{7, 9}, array);
			assertArrayEquals(new byte[]{7, 9}, tree.impactsOf(1));
		}

		@Test
		@DisplayName("a second record that sorts before the held one puts its impact first, in unsigned id order")
		void shouldPromoteSingleToArrayWithAddedRecordFirst() {
			final TransactionalBucketBPlusTree<Integer> tree = emptyImpactTree(5);
			// 5 sorts before the held 20 under either order
			tree.addRecord(1, 20, (byte) 7);
			tree.addRecord(1, 5, (byte) 9);
			assertArrayEquals(new byte[]{9, 7}, assertInstanceOf(byte[].class, impactSlot(tree, 1)));
			// 20 sorts before the held -3 only under unsigned order - a signed comparison would keep -3 first
			tree.addRecord(2, -3, (byte) 11);
			tree.addRecord(2, 20, (byte) 13);
			assertArrayEquals(new byte[]{13, 11}, assertInstanceOf(byte[].class, impactSlot(tree, 2)));
			// the bucket enumerates 20 first too (`getArray` would not do as a check - it answers in signed order)
			assertEquals(20, tree.getRecordsEqualTo(2).getFirst());
			// the same promotion inside a transaction settles identically at commit
			final TransactionalBucketBPlusTree<Integer> held = emptyImpactTree(5);
			held.addRecord(3, -3, (byte) 11);
			assertStateAfterCommit(
				held,
				t -> {
					t.addRecord(3, 20, (byte) 13);
					assertArrayEquals(new byte[]{13, 11}, t.impactsOf(3));
				},
				(original, committed) -> {
					assertArrayEquals(new byte[]{13, 11}, committed.impactsOf(3));
					assertEquals(
						Byte.valueOf((byte) 11), impactSlot(original, 3), "the pre-commit instance is untouched"
					);
				}
			);
		}

		@Test
		@DisplayName("crossing the array threshold chunks the impacts by roaring container")
		void shouldPromoteArrayToBitmapWithChunkedImpacts() {
			final int threshold = OverflowRecords.SMALL_BUCKET_THRESHOLD;
			final TransactionalBucketBPlusTree<Integer> tree = emptyImpactTree(5);
			// two containers: ids 0..63 and 65536..65536+64
			for (int i = 0; i <= threshold; i++) {
				final int pk = i < 64 ? i : 65_536 + i;
				tree.addRecord(1, pk, (byte) i);
			}
			final byte[][] chunks = assertInstanceOf(byte[][].class, impactSlot(tree, 1));
			assertEquals(2, chunks.length);
			assertEquals(64, chunks[0].length);
			assertEquals(threshold + 1 - 64, chunks[1].length);
			final byte[] aligned = tree.impactsOf(1);
			for (int i = 0; i <= threshold; i++) {
				assertEquals((byte) i, aligned[i], "impact of ordinal " + i);
			}
		}

		@Test
		@DisplayName("a bitmap drained to the demotion threshold settles into an impact array at commit")
		void shouldDemoteBitmapToArrayWithImpactsAtCommit() {
			final int threshold = OverflowRecords.SMALL_BUCKET_THRESHOLD;
			final int demotion = OverflowRecords.SMALL_BUCKET_DEMOTION_THRESHOLD;
			final TransactionalBucketBPlusTree<Integer> tree = emptyImpactTree(5);
			for (int i = 1; i <= threshold + 1; i++) {
				tree.addRecord(1, i * 1_000, (byte) i);
			}
			assertInstanceOf(byte[][].class, impactSlot(tree, 1));
			final int[] doomed = new int[threshold + 1 - demotion];
			for (int i = 0; i < doomed.length; i++) {
				doomed[i] = (demotion + 1 + i) * 1_000;
			}
			assertStateAfterCommit(
				tree,
				t -> {
					t.removeRecord(1, doomed);
					// a replacement on a surviving record rides in the same delta
					t.addRecord(1, 3_000, (byte) 77);
					assertInstanceOf(PendingImpacts.class, impactSlot(t, 1));
				},
				(original, committed) -> {
					final byte[] demoted = assertInstanceOf(byte[].class, impactSlot(committed, 1));
					assertEquals(demotion, demoted.length);
					final byte[] aligned = committed.impactsOf(1);
					for (int i = 1; i <= demotion; i++) {
						assertEquals(i == 3 ? (byte) 77 : (byte) i, aligned[i - 1], "impact of record " + i * 1_000);
					}
					assertInstanceOf(byte[][].class, impactSlot(original, 1), "the pre-commit instance is untouched");
				}
			);
		}

		@Test
		@DisplayName("a bitmap drained to one record returns to a cached Byte at commit")
		void shouldDemoteBitmapToSingleWithImpactAtCommit() {
			final int threshold = OverflowRecords.SMALL_BUCKET_THRESHOLD;
			final TransactionalBucketBPlusTree<Integer> tree = emptyImpactTree(5);
			final int[] all = new int[threshold + 1];
			for (int i = 0; i <= threshold; i++) {
				all[i] = i * 70_000;
				tree.addRecord(1, all[i], (byte) (i + 1));
			}
			final int[] doomed = new int[threshold];
			System.arraycopy(all, 1, doomed, 0, threshold);
			assertStateAfterCommit(
				tree,
				t -> t.removeRecord(1, doomed),
				(original, committed) -> {
					assertEquals(Byte.valueOf((byte) 1), impactSlot(committed, 1));
					final TransactionalBucketBPlusTree.BPlusLeafTreeNode<Integer> leaf = committed.findLeafNode(1);
					assertNull(
						leaf.getOverflow() == null ? null : leaf.getOverflow().recordsAt(leaf.getValueIndex(1))
					);
					assertArrayEquals(new byte[]{1}, committed.impactsOf(1));
				}
			);
		}

		@Test
		@DisplayName("removing a whole container outside a transaction drops its chunk from the spine")
		void shouldDropAnEmptiedContainerChunk() {
			final int threshold = OverflowRecords.SMALL_BUCKET_THRESHOLD;
			final TransactionalBucketBPlusTree<Integer> tree = emptyImpactTree(5);
			// three containers of ~43 ids each
			for (int i = 0; i <= threshold; i++) {
				tree.addRecord(1, (i % 3) * 65_536 + i, (byte) i);
			}
			assertEquals(3, assertInstanceOf(byte[][].class, impactSlot(tree, 1)).length);
			final int[] middle = new int[(threshold + 1) / 3 + 1];
			int n = 0;
			for (int i = 0; i <= threshold; i++) {
				if (i % 3 == 1) {
					middle[n++] = 65_536 + i;
				}
			}
			tree.removeRecord(1, java.util.Arrays.copyOf(middle, n));
			final byte[][] chunks = assertInstanceOf(byte[][].class, impactSlot(tree, 1));
			assertEquals(2, chunks.length, "the emptied middle container must leave the spine");
			final byte[] aligned = tree.impactsOf(1);
			final OfInt it = tree.getRecordsEqualTo(1).iterator();
			int ordinal = 0;
			while (it.hasNext()) {
				final int pk = it.nextInt();
				assertEquals((byte) (pk & 0xFFFF), aligned[ordinal++], "impact of record " + pk);
			}
		}
	}

	@Nested
	@DisplayName("Savepoints")
	class Savepoints {

		@Test
		@DisplayName("a savepoint rollback rewinds a pending delta the transaction opened before the savepoint")
		void shouldRewindPendingDeltaOpenedBeforeSavepoint() {
			final int threshold = OverflowRecords.SMALL_BUCKET_THRESHOLD;
			final TransactionalBucketBPlusTree<Integer> tree = emptyImpactTree(5);
			for (int i = 0; i <= threshold; i++) {
				tree.addRecord(1, i, (byte) (i + 1));
			}
			assertSavepointRollbackRestores(
				tree,
				// an earlier entity of the transaction: opens the bucket's pending delta
				t -> {
					t.addRecord(1, 5_000, (byte) 7);
					assertInstanceOf(PendingImpacts.class, impactSlot(t, 1));
				},
				t -> contentOf(t, 1),
				// the failing entity: writes into that same delta - a replacement, an insert and a removal
				t -> {
					t.addRecord(1, 0, (byte) 99);
					t.addRecord(1, 6_000, (byte) 50);
					t.addRecord(1, 5_000, (byte) 8);
					t.removeRecord(1, 3);
				}
			);
		}

		@Test
		@DisplayName("a savepoint rollback rewinds an impact replaced in a delta a split carried into a new leaf")
		void shouldRewindASplitLeafDeltaWhenTheSavepointReplacesAnImpact() {
			assertSplitLeafDeltaRewinds(t -> t.addRecord(1, 0, (byte) 99));
		}

		@Test
		@DisplayName("a savepoint rollback rewinds a removal from a delta a split carried into a new leaf")
		void shouldRewindASplitLeafDeltaWhenTheSavepointRemovesARecord() {
			assertSplitLeafDeltaRewinds(t -> t.removeRecord(1, 5_000));
		}

		@Test
		@DisplayName("a savepoint rollback rewinds a delta a split carried into a new leaf the savepoint rebalanced")
		void shouldRewindASplitLeafDeltaWhenTheSavepointRebalancesTheLeaf() {
			assertSplitLeafDeltaRewinds(
				t -> {
					// the removal underflows the split-born leaf, so its layer is first created by the rebalancing
					t.removeRecord(2, 2);
					t.addRecord(1, 0, (byte) 99);
					t.addRecord(1, 6_000, (byte) 50);
				}
			);
		}

		@ParameterizedTest(name = "seed {0}")
		@ValueSource(longs = {42L, 7L, 1_234L})
		@DisplayName("a savepoint rollback inside a transaction restores every impact written before it")
		void shouldRestoreImpactsOnSavepointRollback(long seed) {
			for (final int blockSize : new int[]{5, 16}) {
				final Random random = new Random(seed);
				final Map<Integer, Map<Integer, Byte>> oracle = CollectionUtils.createHashMap(KEY_SPACE);
				final TransactionalBucketBPlusTree<Integer> tree = emptyImpactTree(blockSize);
				burst(random, tree, oracle, HEAVY_SPREAD_KEY, 4_000);
				applyRandomOperations(random, tree, oracle, 3_000);
				final Map<Integer, Map<Integer, Byte>> scratch = copyOf(oracle);
				assertSavepointRollbackRestores(
					tree,
					t -> applyRandomOperations(random, t, scratch, 600),
					BucketImpactColumnTest::contentOf,
					t -> applyRandomOperations(random, t, scratch, 600)
				);
			}
		}

		@ParameterizedTest(name = "seed {0}")
		@ValueSource(longs = {42L, 7L, 1_234L})
		@DisplayName("a warm-up savepoint rollback restores every impact written before it")
		void shouldRestoreImpactsOnWarmUpSavepointRollback(long seed) {
			for (final int blockSize : new int[]{5, 16}) {
				final Random random = new Random(seed);
				final Map<Integer, Map<Integer, Byte>> oracle = CollectionUtils.createHashMap(KEY_SPACE);
				final TransactionalBucketBPlusTree<Integer> tree = emptyImpactTree(blockSize);
				burst(random, tree, oracle, HEAVY_SPREAD_KEY, 4_000);
				final Map<Integer, Map<Integer, Byte>> scratch = copyOf(oracle);
				for (int round = 0; round < 5; round++) {
					assertWarmUpSavepointRollbackRestores(
						tree,
						t -> applyRandomOperations(random, t, scratch, 400),
						BucketImpactColumnTest::contentOf,
						t -> applyRandomOperations(random, t, scratch, 400)
					);
					// the oracle saw the rolled-back operations too, so re-read it from the restored tree
					scratch.clear();
					scratch.putAll(contentAsOracle(tree));
					assertMatchesOracle(tree, scratch);
				}
			}
		}
	}

	/**
	 * Asserts that a savepoint rollback restores a bitmap bucket whose pending delta a split carried into a leaf born
	 * in the running transaction. The transaction opens the delta, then fills the leaf until it splits as its LAST
	 * write before the savepoint, so the bucket lands in a fresh leaf that holds the delta in its base column and has
	 * no layer yet - the savepoint's first write to it creates one, which no memento ever captures.
	 *
	 * @param savepointOps the failing entity's writes, which must be reverted
	 */
	private static void assertSplitLeafDeltaRewinds(
		@Nonnull Consumer<TransactionalBucketBPlusTree<Integer>> savepointOps
	) {
		final int threshold = OverflowRecords.SMALL_BUCKET_THRESHOLD;
		final TransactionalBucketBPlusTree<Integer> tree = emptyImpactTree(5);
		for (int i = 0; i <= threshold; i++) {
			tree.addRecord(1, i, (byte) (i + 1));
		}
		tree.addRecord(2, 2, (byte) 2);
		tree.addRecord(3, 3, (byte) 3);
		tree.addRecord(4, 4, (byte) 4);
		assertSavepointRollbackRestores(
			tree,
			t -> {
				t.addRecord(1, 5_000, (byte) 7);
				// the fifth key fills the leaf, which splits: key 1 moves into the left half, a leaf born here
				t.addRecord(5, 5, (byte) 5);
				final BPlusLeafTreeNode<Integer> leaf = t.findLeafNode(1);
				assertNotSame(leaf, t.findLeafNode(5), "the leaf must have split");
				assertNull(
					Transaction.getTransactionalMemoryLayerIfExists(leaf),
					"the split-born leaf must enter the savepoint without a layer of its own"
				);
				assertInstanceOf(PendingImpacts.class, impactSlot(t, 1), "the split must carry the delta along");
			},
			BucketImpactColumnTest::contentOf,
			savepointOps
		);
	}

	/**
	 * Reads the whole key space of a tree into an `equals`-comparable form that is sensitive to the pairing of records
	 * and impacts: per key, the records in enumeration order, each packed with its impact.
	 *
	 * @param tree the tree to read
	 * @return key to the packed `(pk, impact)` pairs, in the order the bucket enumerates them
	 */
	@Nonnull
	private static Map<Integer, List<Long>> contentOf(@Nonnull TransactionalBucketBPlusTree<Integer> tree) {
		final Map<Integer, List<Long>> content = CollectionUtils.createHashMap(KEY_SPACE);
		for (int key = 0; key < KEY_SPACE; key++) {
			final List<Long> bucket = contentOf(tree, key);
			if (!bucket.isEmpty()) {
				content.put(key, bucket);
			}
		}
		return content;
	}

	/**
	 * Reads one bucket into the packed form {@link #contentOf(TransactionalBucketBPlusTree)} uses.
	 *
	 * @param tree the tree to read
	 * @param key  the bucket key
	 * @return the packed `(pk, impact)` pairs, in the order the bucket enumerates them
	 */
	@Nonnull
	private static List<Long> contentOf(@Nonnull TransactionalBucketBPlusTree<Integer> tree, int key) {
		final Bitmap records = tree.getRecordsEqualTo(key);
		final byte[] impacts = tree.impactsOf(key);
		assertEquals(records.size(), impacts.length, "impact count differs at key " + key);
		final List<Long> bucket = new ArrayList<>(records.size());
		final OfInt it = records.iterator();
		int ordinal = 0;
		while (it.hasNext()) {
			bucket.add(((long) it.nextInt() << 8) | (impacts[ordinal++] & 0xFF));
		}
		return bucket;
	}

	/**
	 * Converts a tree's content back to the oracle shape, for a test that lost track of its oracle on purpose.
	 *
	 * @param tree the tree to read
	 * @return key to `pk -> impact`
	 */
	@Nonnull
	private static Map<Integer, Map<Integer, Byte>> contentAsOracle(
		@Nonnull TransactionalBucketBPlusTree<Integer> tree
	) {
		final Map<Integer, Map<Integer, Byte>> oracle = CollectionUtils.createHashMap(KEY_SPACE);
		for (final Entry<Integer, List<Long>> entry : contentOf(tree).entrySet()) {
			final Map<Integer, Byte> bucket = CollectionUtils.createHashMap(entry.getValue().size());
			for (final long packed : entry.getValue()) {
				bucket.put((int) (packed >> 8), (byte) packed);
			}
			oracle.put(entry.getKey(), bucket);
		}
		return oracle;
	}

	@Nested
	@DisplayName("Contract")
	class Contract {

		@Test
		@DisplayName("a populated tree refuses to switch impacts on")
		void shouldRefuseEnablingImpactsOnPopulatedTree() {
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(5, Integer.class);
			tree.addRecord(1, 10);
			assertThrows(GenericEvitaInternalError.class, tree::enableImpacts);
			assertFalse(tree.carriesImpacts());
		}

		@Test
		@DisplayName("an impact-carrying tree refuses a bulk-loaded page, which carries no impacts")
		void shouldRefuseBulkLoadingAPageIntoAnImpactCarryingTree() {
			final TransactionalBucketBPlusTree<Integer> tree = emptyImpactTree(5);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> tree.bulkLoadPage(new Object[]{10, 20, 30}, new long[]{1, 2, 3}, null, null, 3)
			);
			assertEquals(0, tree.size(), "the refused page must not have been attached");
		}

		@Test
		@DisplayName("an impact-carrying tree refuses the impact-less inserts, and a plain tree refuses the impact one")
		void shouldRefuseTheWrongInsertApi() {
			final TransactionalBucketBPlusTree<Integer> impactTree = emptyImpactTree(5);
			assertThrows(GenericEvitaInternalError.class, () -> impactTree.addRecord(1, 10));
			assertThrows(GenericEvitaInternalError.class, () -> impactTree.addRecord(1, 10, 11));
			final TransactionalBucketBPlusTree<Integer> plainTree =
				new TransactionalBucketBPlusTree<>(5, Integer.class);
			assertThrows(GenericEvitaInternalError.class, () -> plainTree.addRecord(1, 10, (byte) 3));
			assertThrows(GenericEvitaInternalError.class, () -> plainTree.impactsOf(1));
		}

		@Test
		@DisplayName("re-adding a record the bucket already holds replaces its impact in every tier")
		void shouldReplaceImpactOfExistingRecord() {
			final TransactionalBucketBPlusTree<Integer> tree = emptyImpactTree(5);
			tree.addRecord(1, 10, (byte) 3);
			assertArrayEquals(new byte[]{3}, tree.impactsOf(1));
			tree.addRecord(1, 10, (byte) 200);
			assertArrayEquals(new byte[]{(byte) 200}, tree.impactsOf(1));
			tree.addRecord(1, 11, (byte) 4);
			tree.addRecord(1, 10, (byte) 5);
			assertArrayEquals(new byte[]{5, 4}, tree.impactsOf(1));
			assertEquals(2, tree.getRecordsEqualTo(1).size());
		}

		@Test
		@DisplayName("a cursor over a tree without impacts refuses to read them")
		void shouldRefuseCursorImpactsOnPlainTree() {
			final TransactionalBucketBPlusTree<Integer> plainTree =
				new TransactionalBucketBPlusTree<>(5, Integer.class);
			plainTree.addRecord(1, 10);
			final BucketCursor<Integer> cursor = plainTree.cursor();
			assertTrue(cursor.next());
			assertThrows(GenericEvitaInternalError.class, cursor::impacts);
		}

		@Test
		@DisplayName("a cursor hands out the impact of a single bucket as a shared view")
		void shouldShareSingleBucketViews() {
			final TransactionalBucketBPlusTree<Integer> tree = emptyImpactTree(5);
			tree.addRecord(1, 10, (byte) 200);
			tree.addRecord(2, 10, (byte) 200);
			tree.addRecord(3, 10, (byte) 7);
			tree.addRecord(3, 11, (byte) 8);
			final BucketCursor<Integer> cursor = tree.cursor();
			assertTrue(cursor.next());
			final ImpactView first = cursor.impacts();
			assertTrue(cursor.next());
			// the single-record views are interned per impact value, so a walk over single buckets allocates none
			assertSame(first, cursor.impacts());
			assertTrue(cursor.next());
			final ImpactView array = cursor.impacts();
			assertArrayEquals(new byte[]{7, 8}, array.toArray());
			assertEquals(2, array.size());
		}

		@Test
		@DisplayName("an impact view refuses an index outside its records, and the empty view holds none")
		void shouldRefuseReadOutsideTheView() {
			final ImpactView.Reader reader = ImpactView.of(new byte[]{1, 2}).reader();
			assertEquals(2, reader.impactAt(1));
			assertThrows(GenericEvitaInternalError.class, () -> reader.impactAt(2));
			assertThrows(GenericEvitaInternalError.class, () -> reader.impactAt(-1));
			assertEquals(0, ImpactView.EMPTY.size());
			assertEquals(0, ImpactView.EMPTY.toArray().length);
			assertSame(ImpactView.EMPTY, ImpactView.of(new byte[0]));
			assertThrows(GenericEvitaInternalError.class, () -> ImpactView.EMPTY.reader().impactAt(0));
		}

		@Test
		@DisplayName("an absent value reads as no impacts at all")
		void shouldReadNothingForAnAbsentValue() {
			final TransactionalBucketBPlusTree<Integer> tree = emptyImpactTree(5);
			tree.addRecord(1, 10, (byte) 3);
			assertEquals(0, tree.impactsOf(2).length);
			assertTrue(tree.carriesImpacts());
		}
	}
}
