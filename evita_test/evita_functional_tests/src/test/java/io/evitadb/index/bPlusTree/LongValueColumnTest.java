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

import io.evitadb.utils.ArrayUtils.InsertionPosition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;

import static io.evitadb.index.bPlusTree.ValueColumnTestSupport.assertTreeMatchesOracle;
import static io.evitadb.index.bPlusTree.ValueColumnTestSupport.verifyConsistent;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the primitive `long[]` value column: the order-preserving {@link LongKeyCodec} bijections,
 * the {@link LongValueColumn} array operations (proven equivalent to the boxed {@link BoxedObjectColumn}), the
 * {@link ValueColumnFactory} selection rules, an end-to-end randomized workload on a long-keyed
 * {@link TransactionalBucketBPlusTree} matched against a {@link TreeMap} oracle, and the MVCC commit / rollback of a
 * long-keyed tree (so the primitive column's deep-copy duplicate / range moves run across a real transaction layer).
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Primitive long value column")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class LongValueColumnTest {

	private static final int BLOCK_SIZE = 8;

	/**
	 * Verifies the codec bijections and their monotonicity (encode preserves natural order).
	 */
	@Nested
	@DisplayName("LongKeyCodec bijections")
	class CodecTest {

		@Test
		@DisplayName("Integer round-trips and preserves order")
		void shouldRoundTripInteger() {
			assertRoundTripAndOrder(
				List.of(Integer.MIN_VALUE, -1, 0, 1, 42, Integer.MAX_VALUE), Integer.class
			);
		}

		@Test
		@DisplayName("Long round-trips and preserves order")
		void shouldRoundTripLong() {
			assertRoundTripAndOrder(
				List.of(Long.MIN_VALUE, -1L, 0L, 1L, 42L, Long.MAX_VALUE), Long.class
			);
		}

		@Test
		@DisplayName("Short round-trips and preserves order")
		void shouldRoundTripShort() {
			assertRoundTripAndOrder(
				List.of(Short.MIN_VALUE, (short) -1, (short) 0, (short) 1, Short.MAX_VALUE), Short.class
			);
		}

		@Test
		@DisplayName("Byte round-trips and preserves order")
		void shouldRoundTripByte() {
			assertRoundTripAndOrder(
				List.of(Byte.MIN_VALUE, (byte) -1, (byte) 0, (byte) 1, Byte.MAX_VALUE), Byte.class
			);
		}

		@Test
		@DisplayName("Boolean round-trips and preserves order")
		void shouldRoundTripBoolean() {
			assertRoundTripAndOrder(List.of(Boolean.FALSE, Boolean.TRUE), Boolean.class);
		}

		@Test
		@DisplayName("Character round-trips and preserves order")
		void shouldRoundTripCharacter() {
			assertRoundTripAndOrder(
				List.of(Character.MIN_VALUE, 'A', 'a', 'z', Character.MAX_VALUE), Character.class
			);
		}

		@Test
		@DisplayName("LocalDate round-trips and preserves order")
		void shouldRoundTripLocalDate() {
			assertRoundTripAndOrder(
				List.of(LocalDate.of(1900, 1, 1), LocalDate.of(2026, 6, 15), LocalDate.of(2999, 12, 31)),
				LocalDate.class
			);
		}

		@Test
		@DisplayName("LocalTime round-trips and preserves order")
		void shouldRoundTripLocalTime() {
			assertRoundTripAndOrder(
				List.of(LocalTime.MIN, LocalTime.NOON, LocalTime.of(13, 37, 42), LocalTime.MAX),
				LocalTime.class
			);
		}

		@Test
		@DisplayName("Instant round-trips and preserves order across the epoch")
		void shouldRoundTripInstant() {
			// the list deliberately straddles 1970: `toEpochMilli` FLOORS, so a pre-epoch instant is the one place a
			// truncate-toward-zero implementation would round the wrong way and break monotonicity
			assertRoundTripAndOrder(
				List.of(
					Instant.parse("1900-03-04T05:06:07.008Z"),
					Instant.parse("1969-12-31T23:59:59.999Z"),
					Instant.EPOCH,
					Instant.parse("1970-01-01T00:00:00.001Z"),
					Instant.parse("2026-05-20T12:19:26.123Z")
				),
				Instant.class
			);
		}

		@Test
		@DisplayName("Instant encodes to the millisecond BELOW, on both sides of the epoch")
		void shouldFloorInstantToTheMillisecondBelow() {
			// The codec's domain restriction, stated as an absolute number rather than as a round-trip: an instant
			// carrying sub-millisecond digits does not survive `decode(encode(v)) == v`, and the direction it moves is
			// DOWN — including before 1970, where `Instant.getNano()` is a positive offset above a more-negative
			// second and a naive `seconds * 1000 + nanos / 1_000_000` would round up instead.
			final LongKeyCodec codec = LongKeyCodec.forType(Instant.class);
			assertEquals(1_000L, codec.encode(Instant.parse("1970-01-01T00:00:01.000999999Z")));
			assertEquals(-1_000L, codec.encode(Instant.parse("1969-12-31T23:59:59.000999999Z")));
			assertEquals(-1L, codec.encode(Instant.parse("1969-12-31T23:59:59.999999999Z")));
			// and the two sub-millisecond twins of one millisecond really do land on the SAME slot - this is the
			// collapse the whole millisecond-truncation guarantee exists to keep out of the tree
			assertEquals(
				codec.encode(Instant.parse("2026-05-20T12:19:26.123000001Z")),
				codec.encode(Instant.parse("2026-05-20T12:19:26.123999999Z"))
			);
			assertEquals(
				Instant.parse("2026-05-20T12:19:26.123Z"),
				codec.decode(codec.encode(Instant.parse("2026-05-20T12:19:26.123999999Z")))
			);
		}

		/**
		 * Asserts that each value round-trips through encode/decode and that encode is monotonic with natural order over
		 * the (ascending) input list.
		 *
		 * @param ascending the values in ascending natural order
		 * @param type      the codec type
		 */
		private static void assertRoundTripAndOrder(
			@Nonnull List<? extends Comparable<?>> ascending, @Nonnull Class<?> type
		) {
			final LongKeyCodec codec = LongKeyCodec.forType(type);
			assertSame(type, codec.type());
			long previousRaw = Long.MIN_VALUE;
			boolean first = true;
			for (final Comparable<?> value : ascending) {
				final long raw = codec.encode(value);
				assertEquals(value, codec.decode(raw), "Round-trip mismatch for " + value);
				if (!first) {
					assertTrue(previousRaw < raw, "Encoding not strictly increasing at " + value);
				}
				previousRaw = raw;
				first = false;
			}
		}
	}

	/**
	 * Verifies that {@link LongValueColumn} performs the same key moves / searches as the boxed reference column.
	 */
	@Nested
	@DisplayName("LongValueColumn vs. boxed column parity")
	class ColumnParityTest {

		@Test
		@DisplayName("insert / remove / findKeyPosition / duplicate match the boxed column")
		void shouldBehaveLikeBoxedColumn() {
			final LongKeyCodec codec = LongKeyCodec.forType(Integer.class);
			final ValueColumn<Integer> primitive = new LongValueColumn<>(codec, BLOCK_SIZE);
			final ValueColumn<Integer> boxed = new BoxedObjectColumn<>(Integer.class, BLOCK_SIZE);

			// build the same ordered prefix [10, 20, 30, 40] in both columns
			final int[] inserted = {10, 20, 30, 40};
			for (int i = 0; i < inserted.length; i++) {
				primitive.insertKeyAt(i, inserted[i]);
				boxed.insertKeyAt(i, inserted[i]);
			}
			assertColumnsEqual(primitive, boxed, inserted.length);

			// insert 25 in the middle (position 2)
			final InsertionPosition pp = primitive.findKeyPosition(25, 0, inserted.length, null);
			final InsertionPosition bp = boxed.findKeyPosition(25, 0, inserted.length, null);
			assertEquals(bp.position(), pp.position());
			assertEquals(bp.alreadyPresent(), pp.alreadyPresent());
			assertFalse(pp.alreadyPresent());
			primitive.insertKeyAt(pp.position(), 25);
			boxed.insertKeyAt(bp.position(), 25);
			assertColumnsEqual(primitive, boxed, inserted.length + 1);

			// existing key lookup reports alreadyPresent at the same slot
			final InsertionPosition pHit = primitive.findKeyPosition(30, 0, inserted.length + 1, null);
			final InsertionPosition bHit = boxed.findKeyPosition(30, 0, inserted.length + 1, null);
			assertTrue(pHit.alreadyPresent());
			assertEquals(bHit.position(), pHit.position());

			// duplicate is an independent deep copy
			final ValueColumn<Integer> dup = primitive.duplicate();
			assertColumnsEqual(dup, boxed, inserted.length + 1);
			dup.insertKeyAt(0, -1);
			assertEquals(Integer.valueOf(25), primitive.keyAt(2), "Duplicate must not alias the source");

			// remove the middle key (position 2 == value 25) from both
			primitive.removeKeyAt(2);
			primitive.clearAt(inserted.length);
			boxed.removeKeyAt(2);
			boxed.clearAt(inserted.length);
			assertColumnsEqual(primitive, boxed, inserted.length);

			assertInstanceOf(LongValueColumn.class, primitive);
		}

		@Test
		@DisplayName("copyRangeTo moves a key block like the boxed column")
		void shouldCopyRangeLikeBoxedColumn() {
			final LongKeyCodec codec = LongKeyCodec.forType(Integer.class);
			final ValueColumn<Integer> srcPrimitive = new LongValueColumn<>(codec, BLOCK_SIZE);
			final ValueColumn<Integer> srcBoxed = new BoxedObjectColumn<>(Integer.class, BLOCK_SIZE);
			for (int i = 0; i < 4; i++) {
				srcPrimitive.insertKeyAt(i, (i + 1) * 7);
				srcBoxed.insertKeyAt(i, (i + 1) * 7);
			}

			final ValueColumn<Integer> dstPrimitive = srcPrimitive.allocate(BLOCK_SIZE);
			final ValueColumn<Integer> dstBoxed = srcBoxed.allocate(BLOCK_SIZE);
			srcPrimitive.copyRangeTo(1, dstPrimitive, 0, 3);
			srcBoxed.copyRangeTo(1, dstBoxed, 0, 3);
			assertColumnsEqual(dstPrimitive, dstBoxed, 3);

			// asBoxedArray (cold path) decodes to the same prefix the boxed column already holds
			final Integer[] primitiveBoxed = dstPrimitive.asBoxedArray();
			final Integer[] boxedArray = dstBoxed.asBoxedArray();
			assertEquals(boxedArray[0], primitiveBoxed[0]);
			assertEquals(boxedArray[1], primitiveBoxed[1]);
			assertEquals(boxedArray[2], primitiveBoxed[2]);
		}

		@Test
		@DisplayName("fillEmpty clears slots and appendKey renders the decoded key like the boxed column")
		void shouldClearSlotsAndRenderKeyLikeBoxedColumn() {
			final LongKeyCodec codec = LongKeyCodec.forType(Integer.class);
			final ValueColumn<Integer> primitive = new LongValueColumn<>(codec, BLOCK_SIZE);
			final ValueColumn<Integer> boxed = new BoxedObjectColumn<>(Integer.class, BLOCK_SIZE);
			final int[] inserted = {3, 6, 9, 12};
			for (int i = 0; i < inserted.length; i++) {
				primitive.insertKeyAt(i, inserted[i]);
				boxed.insertKeyAt(i, inserted[i]);
			}

			// appendKey renders each decoded key identically to the boxed column
			for (int i = 0; i < inserted.length; i++) {
				final StringBuilder primitiveKey = new StringBuilder(16);
				final StringBuilder boxedKey = new StringBuilder(16);
				primitive.appendKey(primitiveKey, i);
				boxed.appendKey(boxedKey, i);
				assertEquals(boxedKey.toString(), primitiveKey.toString(), "appendKey mismatch at slot " + i);
				assertEquals(String.valueOf(inserted[i]), primitiveKey.toString());
			}

			// fillEmpty clears the tail slots in both columns; the surviving prefix is unchanged and the cleared slots
			// round-trip to the codec's zero key (re-insert proves the slot is genuinely empty, no reflection needed)
			primitive.fillEmpty(2, inserted.length);
			boxed.fillEmpty(2, inserted.length);
			assertColumnsEqual(primitive, boxed, 2);
			primitive.insertKeyAt(2, 100);
			boxed.insertKeyAt(2, 100);
			assertEquals(Integer.valueOf(100), primitive.keyAt(2));
			assertColumnsEqual(primitive, boxed, 3);
		}

		/**
		 * Asserts the two columns hold the same decoded keys in `[0, size)`.
		 *
		 * @param actual   the column under test
		 * @param expected the boxed reference column
		 * @param size     the number of populated slots
		 */
		private static void assertColumnsEqual(
			@Nonnull ValueColumn<Integer> actual, @Nonnull ValueColumn<Integer> expected, int size
		) {
			assertEquals(expected.capacity(), actual.capacity());
			for (int i = 0; i < size; i++) {
				assertEquals(expected.keyAt(i), actual.keyAt(i), "Key mismatch at slot " + i);
			}
		}
	}

	/**
	 * Verifies the {@link ValueColumnFactory} selection rules and the end-to-end long-keyed tree workload.
	 */
	@Nested
	@DisplayName("ValueColumnFactory selection and tree workload")
	class FactoryAndTreeTest {

		@Test
		@DisplayName("integral / temporal natural-order keys select the primitive column")
		void shouldSelectPrimitiveColumnForIntegralNaturalOrder() {
			assertInstanceOf(
				LongValueColumn.class,
				ValueColumnFactory.forKey(Integer.class, Comparator.naturalOrder()).create(BLOCK_SIZE)
			);
			assertInstanceOf(
				LongValueColumn.class,
				ValueColumnFactory.forKey(Long.class, null).create(BLOCK_SIZE)
			);
			assertInstanceOf(
				LongValueColumn.class,
				ValueColumnFactory.forKey(LocalDate.class, null).create(BLOCK_SIZE)
			);
			// `Instant` is a key type in its own right, so the RAW key space reaches the single-`long` column too -
			// there is no separate temporal column any more
			assertInstanceOf(
				LongValueColumn.class,
				ValueColumnFactory.forKey(Instant.class, null).create(BLOCK_SIZE)
			);
			// a declared OffsetDateTime / LocalDateTime attribute reaches it only through the FILTER key space, which
			// is the one whose caller converts the values to `Instant` first (ValueColumnFactory#normalizedTypeOf)
			assertInstanceOf(
				LongValueColumn.class,
				ValueColumnFactory.forFilterKey(OffsetDateTime.class, null, 0).create(BLOCK_SIZE)
			);
			assertInstanceOf(
				LongValueColumn.class,
				ValueColumnFactory.forFilterKey(LocalDateTime.class, Comparator.naturalOrder(), 0).create(BLOCK_SIZE)
			);
		}

		@Test
		@DisplayName("the raw key space keeps a declared temporal attribute on the boxed column")
		void shouldKeepDeclaredTemporalTypesBoxedInTheRawKeySpace() {
			// forKey serves callers that store values verbatim (OwnerUniqueIndex / GlobalUniqueIndex keep RAW
			// values). Handing them the Instant-keyed column made LongKeyCodec#INSTANT cast an OffsetDateTime to
			// Instant on the very first write, so a unique temporal attribute threw a ClassCastException - see
			// UniqueIndexTest's "Temporal unique attributes" for the end-to-end proof.
			assertInstanceOf(
				BoxedObjectColumn.class,
				ValueColumnFactory.forKey(OffsetDateTime.class, null).create(BLOCK_SIZE)
			);
			assertInstanceOf(
				BoxedObjectColumn.class,
				ValueColumnFactory.forKey(LocalDateTime.class, Comparator.naturalOrder()).create(BLOCK_SIZE)
			);
		}

		@Test
		@DisplayName("an Instant-keyed tree collapses sub-millisecond twins onto one bucket")
		@SuppressWarnings({"unchecked", "rawtypes"})
		void shouldCollapseSubMillisecondTwinsInAnInstantKeyedTree() {
			// the column-level consequence of the codec's domain restriction, exercised through the tree rather than
			// through the column: the two twins must reach ONE bucket holding BOTH records, and the bucket must be
			// keyed by the truncated instant rather than by either input
			final ValueColumnFactory factory =
				ValueColumnFactory.forFilterKey(OffsetDateTime.class, Comparator.naturalOrder(), 0);
			final TransactionalBucketBPlusTree<Instant> tree = new TransactionalBucketBPlusTree<>(
				BLOCK_SIZE, 3, 7, 3, Instant.class, null, factory
			);
			tree.addRecord(Instant.parse("2026-05-20T12:19:26.123000001Z"), 1);
			tree.addRecord(Instant.parse("2026-05-20T12:19:26.123999999Z"), 2);
			tree.addRecord(Instant.parse("2026-05-20T12:19:26.124000000Z"), 3);

			assertEquals(2, tree.size(), "the twins must share one bucket, the neighbouring millisecond its own");
			// the oracle names the truncated keys outright: it holds neither of the two values that were inserted, so
			// it cannot be satisfied by a tree that merely echoed its input back
			final TreeMap<Instant, TreeSet<Integer>> oracle = new TreeMap<>();
			oracle.put(Instant.parse("2026-05-20T12:19:26.123Z"), new TreeSet<>(List.of(1, 2)));
			oracle.put(Instant.parse("2026-05-20T12:19:26.124Z"), new TreeSet<>(List.of(3)));
			assertTreeMatchesOracle(tree, oracle);
			verifyConsistent(tree);
		}

		@Test
		@DisplayName("non-integral types and non-natural orders fall back to the boxed column")
		void shouldFallBackToBoxedColumn() {
			// (String routes to the dedicated front-coded column instead — see FrontCodedStringColumnTest.)
			// non-codec plain types (Currency / Locale have no LongKeyCodec) fall back to the boxed column —
			// forKey is contracted on the plain element type (callers array-unwrap before invoking it)
			assertInstanceOf(
				BoxedObjectColumn.class,
				ValueColumnFactory.forKey(Currency.class, null).create(BLOCK_SIZE)
			);
			assertInstanceOf(
				BoxedObjectColumn.class,
				ValueColumnFactory.forKey(Locale.class, null).create(BLOCK_SIZE)
			);
			// integral type but a non-natural-order comparator ⇒ boxed (the long order would not match)
			assertInstanceOf(
				BoxedObjectColumn.class,
				ValueColumnFactory.forKey(Integer.class, Comparator.<Integer>reverseOrder()).create(BLOCK_SIZE)
			);
		}

		@Test
		@DisplayName("randomized add/remove workload on a long-keyed tree matches a TreeMap oracle")
		@SuppressWarnings({"unchecked", "rawtypes"})
		void shouldMatchOracleOnLongKeyedTree() {
			// build the tree via the factory so its leaves use the primitive LongValueColumn — proven below
			final ValueColumnFactory factory =
				ValueColumnFactory.forKey(Integer.class, Comparator.naturalOrder());
			assertInstanceOf(LongValueColumn.class, factory.create(BLOCK_SIZE));
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(
				BLOCK_SIZE, 3, 7, 3, Integer.class, null, factory
			);

			final TreeMap<Integer, TreeSet<Integer>> oracle = new TreeMap<>();
			final Random random = new Random(424242L);
			final int keyDomain = 60;

			for (int op = 0; op < 6_000; op++) {
				final int key = random.nextInt(keyDomain) - keyDomain / 2; // include negative keys (codec coverage)
				// record ids stay non-negative so the bitmap's unsigned order matches the oracle TreeSet's signed order
				final int recordId = random.nextInt(1_000);
				if (random.nextInt(100) < 65) {
					tree.addRecord(key, recordId);
					oracle.computeIfAbsent(key, k -> new TreeSet<>()).add(recordId);
				} else {
					final TreeSet<Integer> set = oracle.get(key);
					if (set != null && set.contains(recordId)) {
						tree.removeRecord(key, recordId);
						set.remove(recordId);
						if (set.isEmpty()) {
							oracle.remove(key);
						}
					}
				}
				if (op % 250 == 0) {
					assertTreeMatchesOracle(tree, oracle);
				}
			}
			assertTreeMatchesOracle(tree, oracle);
		}
	}

	/**
	 * Drives the long-keyed tree's primitive {@link LongValueColumn} across a real MVCC transaction layer: the
	 * non-transactional oracle tests never run {@link LongValueColumn#duplicate()} / {@link LongValueColumn#copyRangeTo}
	 * through a commit / rollback, so a layer-decoupling defect in the primitive copy path would be invisible without
	 * these tests.
	 */
	@Nested
	@DisplayName("Long-keyed tree across an MVCC transaction")
	class TransactionalTest {

		@Test
		@DisplayName("preserves a long-keyed tree across a commit that splits and merges leaves")
		@Tag(TRANSACTION)
		@SuppressWarnings({"unchecked", "rawtypes"})
		void shouldPreserveLongKeyedTreeAcrossCommit() {
			final ValueColumnFactory factory =
				ValueColumnFactory.forKey(Integer.class, Comparator.naturalOrder());
			assertInstanceOf(LongValueColumn.class, factory.create(BLOCK_SIZE),
				"Factory must back the tree with the primitive long column");
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(
				BLOCK_SIZE, 3, 7, 3, Integer.class, null, factory
			);

			// commit a base layout dense enough to span several leaves
			final TreeMap<Integer, TreeSet<Integer>> baseOracle = new TreeMap<>();
			for (int key = -20; key <= 20; key++) {
				tree.addRecord(key, key + 1_000);
				baseOracle.computeIfAbsent(key, k -> new TreeSet<>()).add(key + 1_000);
			}

			assertStateAfterCommit(
				tree,
				tested -> {
					// add and remove enough records inside the txn to force leaf splits, steals and merges so the
					// primitive column's duplicate() + copyRangeTo run across the transaction layer
					for (int key = 21; key <= 60; key++) {
						tested.addRecord(key, key + 1_000);
						tested.addRecord(key, key + 2_000);
					}
					for (int key = -20; key <= 5; key++) {
						tested.removeRecord(key, key + 1_000);
					}
				},
				(original, committed) -> {
					// the committed tree matches the post-mutation oracle
					final TreeMap<Integer, TreeSet<Integer>> oracle = new TreeMap<>(baseOracle);
					for (int key = 21; key <= 60; key++) {
						final TreeSet<Integer> set = new TreeSet<>();
						set.add(key + 1_000);
						set.add(key + 2_000);
						oracle.put(key, set);
					}
					for (int key = -20; key <= 5; key++) {
						oracle.remove(key);
					}
					assertTreeMatchesOracle(committed, oracle);
					verifyConsistent(committed);

					// the pre-commit base tree is unchanged — the transaction layer was decoupled
					assertTreeMatchesOracle(original, baseOracle);
				}
			);
		}

		@Test
		@DisplayName("discards long-keyed mutations on rollback, leaving the base tree untouched")
		@Tag(TRANSACTION)
		@SuppressWarnings({"unchecked", "rawtypes"})
		void shouldDiscardLongKeyedMutationsOnRollback() {
			final ValueColumnFactory factory =
				ValueColumnFactory.forKey(Integer.class, Comparator.naturalOrder());
			final TransactionalBucketBPlusTree<Integer> tree = new TransactionalBucketBPlusTree<>(
				BLOCK_SIZE, 3, 7, 3, Integer.class, null, factory
			);

			final TreeMap<Integer, TreeSet<Integer>> baseOracle = new TreeMap<>();
			for (int key = 0; key <= 15; key++) {
				tree.addRecord(key, key + 1_000);
				baseOracle.computeIfAbsent(key, k -> new TreeSet<>()).add(key + 1_000);
			}

			assertStateAfterRollback(
				tree,
				tested -> {
					for (int key = 16; key <= 40; key++) {
						tested.addRecord(key, key + 1_000);
					}
					for (int key = 0; key <= 10; key++) {
						tested.removeRecord(key, key + 1_000);
					}
				},
				(original, discarded) -> {
					// the rolled-back changes leave the base tree exactly as it was
					assertTreeMatchesOracle(original, baseOracle);
					verifyConsistent(original);
				}
			);
		}
	}
}
