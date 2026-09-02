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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the temporal parallel-array value column: the lossless
 * {@code Instant ↔ (seconds, nanos)} decomposition, the {@link InstantValueColumn} array operations (proven equivalent
 * to the boxed {@link BoxedObjectColumn}, including the nano tiebreak), the {@link ValueColumnFactory} selection of the
 * column for {@code OffsetDateTime} / {@code Instant} / {@code LocalDateTime} natural-order keys (and its
 * deliberate non-selection for {@code LocalDate} / {@code LocalTime}, which stay on the cheaper single-long
 * column), an end-to-end randomized workload on an
 * {@link Instant}-keyed {@link TransactionalBucketBPlusTree} matched against a {@link TreeMap} oracle, and the MVCC
 * commit / rollback of such a tree (so the two-array lockstep deep copy runs across a real transaction layer).
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Temporal Instant value column")
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class InstantValueColumnTest {

	private static final int BLOCK_SIZE = 8;

	/**
	 * Verifies the lossless decomposition / round-trip and that the column is lexicographically ordered, including the
	 * same-second different-nano tiebreak.
	 */
	@Nested
	@DisplayName("Instant decomposition and ordering")
	class DecompositionTest {

		@Test
		@DisplayName("Instant round-trips including non-zero nanos")
		void shouldRoundTripInstantWithNanos() {
			final ValueColumn<Instant> column = new InstantValueColumn<>(BLOCK_SIZE);
			final List<Instant> values = List.of(
				Instant.ofEpochSecond(-1_000_000L, 123),
				Instant.ofEpochSecond(0L, 0),
				Instant.ofEpochSecond(0L, 999_999_999),
				Instant.ofEpochSecond(1_700_000_000L, 1),
				Instant.ofEpochSecond(1_700_000_000L, 500_000_000)
			);
			for (int i = 0; i < values.size(); i++) {
				column.insertKeyAt(i, values.get(i));
			}
			for (int i = 0; i < values.size(); i++) {
				assertEquals(values.get(i), column.keyAt(i), "Round-trip mismatch at slot " + i);
			}
		}

		@Test
		@DisplayName("lexicographic order matches natural Instant order including nano tiebreak")
		void shouldOrderLexicographicallyWithNanoTiebreak() {
			// two keys share the same epoch-second and differ only in nanos — the nano tiebreak must order them
			final Instant earlierNano = Instant.ofEpochSecond(42L, 100);
			final Instant laterNano = Instant.ofEpochSecond(42L, 900);
			final Instant nextSecond = Instant.ofEpochSecond(43L, 0);
			assertTrue(earlierNano.compareTo(laterNano) < 0);
			assertTrue(laterNano.compareTo(nextSecond) < 0);

			final ValueColumn<Instant> column = new InstantValueColumn<>(BLOCK_SIZE);
			column.insertKeyAt(0, earlierNano);
			column.insertKeyAt(1, laterNano);
			column.insertKeyAt(2, nextSecond);

			// the later-nano key sits strictly between earlier-nano and the next second
			final InsertionPosition between = column.findKeyPosition(
				Instant.ofEpochSecond(42L, 500), 0, 3, null
			);
			assertFalse(between.alreadyPresent());
			assertEquals(1, between.position(), "nano tiebreak must place the probe between the two slot-42 keys");

			// exact same-second different-nano hit lands on its own slot, not the sibling's
			final InsertionPosition hitLater = column.findKeyPosition(laterNano, 0, 3, null);
			assertTrue(hitLater.alreadyPresent());
			assertEquals(1, hitLater.position());
		}
	}

	/**
	 * Verifies that {@link InstantValueColumn} performs the same key searches as the boxed reference column on a shared
	 * dataset (exact-match and insertion-point parity).
	 */
	@Nested
	@DisplayName("InstantValueColumn vs. boxed column parity")
	class ColumnParityTest {

		@Test
		@DisplayName("findKeyPosition matches the boxed column on a shared dataset")
		void shouldMatchBoxedColumnFindKeyPosition() {
			// a shared, ordered dataset with same-second different-nano neighbours
			final Instant[] dataset = {
				Instant.ofEpochSecond(-5L, 0),
				Instant.ofEpochSecond(0L, 0),
				Instant.ofEpochSecond(0L, 250),
				Instant.ofEpochSecond(0L, 999_999_999),
				Instant.ofEpochSecond(10L, 0),
				Instant.ofEpochSecond(10L, 1)
			};
			final ValueColumn<Instant> primitive = new InstantValueColumn<>(BLOCK_SIZE);
			final ValueColumn<Instant> boxed = new BoxedObjectColumn<>(Instant.class, BLOCK_SIZE);
			for (int i = 0; i < dataset.length; i++) {
				primitive.insertKeyAt(i, dataset[i]);
				boxed.insertKeyAt(i, dataset[i]);
			}

			// probe both present keys and absent gaps (before, between same-second neighbours, after)
			final Instant[] probes = {
				Instant.ofEpochSecond(-10L, 0),
				dataset[0], dataset[2], dataset[3], dataset[5],
				Instant.ofEpochSecond(0L, 125),
				Instant.ofEpochSecond(0L, 500_000_000),
				Instant.ofEpochSecond(10L, 2),
				Instant.ofEpochSecond(100L, 0)
			};
			for (final Instant probe : probes) {
				final InsertionPosition pp = primitive.findKeyPosition(probe, 0, dataset.length, null);
				final InsertionPosition bp = boxed.findKeyPosition(probe, 0, dataset.length, null);
				assertEquals(bp.position(), pp.position(), "position mismatch for probe " + probe);
				assertEquals(
					bp.alreadyPresent(), pp.alreadyPresent(), "alreadyPresent mismatch for probe " + probe
				);
			}

			// empty-range handling mirrors ArrayUtils.computeInsertPositionOfLongInOrderedArray (position 0, not
			// present) — the same convention LongValueColumn uses; the tree never searches an empty leaf slice, so
			// this only pins the documented encoding (the boxed Obj variant happens not to short-circuit empty ranges)
			final InsertionPosition pEmpty = primitive.findKeyPosition(dataset[0], 2, 2, null);
			assertEquals(0, pEmpty.position());
			assertFalse(pEmpty.alreadyPresent());

			assertInstanceOf(InstantValueColumn.class, primitive);
		}

		@Test
		@DisplayName("fillEmpty clears seconds and nanos in lockstep and appendKey renders like the boxed column")
		void shouldClearBothArraysInLockstepAndRenderKeyLikeBoxedColumn() {
			// neighbours that share an epoch-second but differ in nanos force both backing arrays to carry information
			final Instant[] dataset = {
				Instant.ofEpochSecond(0L, 0),
				Instant.ofEpochSecond(5L, 1),
				Instant.ofEpochSecond(5L, 999_999_999),
				Instant.ofEpochSecond(9L, 500_000_000)
			};
			final ValueColumn<Instant> primitive = new InstantValueColumn<>(BLOCK_SIZE);
			final ValueColumn<Instant> boxed = new BoxedObjectColumn<>(Instant.class, BLOCK_SIZE);
			for (int i = 0; i < dataset.length; i++) {
				primitive.insertKeyAt(i, dataset[i]);
				boxed.insertKeyAt(i, dataset[i]);
			}

			// appendKey renders each decoded key identically to the boxed column
			for (int i = 0; i < dataset.length; i++) {
				final StringBuilder primitiveKey = new StringBuilder(32);
				final StringBuilder boxedKey = new StringBuilder(32);
				primitive.appendKey(primitiveKey, i);
				boxed.appendKey(boxedKey, i);
				assertEquals(boxedKey.toString(), primitiveKey.toString(), "appendKey mismatch at slot " + i);
				assertEquals(dataset[i].toString(), primitiveKey.toString());
			}

			// clear the same-second different-nano tail; the surviving prefix is unchanged
			primitive.fillEmpty(2, dataset.length);
			boxed.fillEmpty(2, dataset.length);
			for (int i = 0; i < 2; i++) {
				assertEquals(boxed.keyAt(i), primitive.keyAt(i), "surviving prefix mismatch at slot " + i);
			}

			// re-inserting a key whose nanos differ from the cleared remnant proves BOTH seconds and nanos were cleared
			// in lockstep — a stale nano left behind would surface as a wrong sub-second on round-trip
			final Instant reinserted = Instant.ofEpochSecond(5L, 123_456_789);
			primitive.insertKeyAt(2, reinserted);
			boxed.insertKeyAt(2, reinserted);
			assertEquals(reinserted, primitive.keyAt(2), "cleared slot must round-trip the re-inserted key exactly");
			assertEquals(boxed.keyAt(2), primitive.keyAt(2));
		}
	}

	/**
	 * Verifies the {@link ValueColumnFactory} selection and the end-to-end {@link Instant}-keyed tree workload.
	 */
	@Nested
	@DisplayName("ValueColumnFactory selection and tree workload")
	class FactoryAndTreeTest {

		@Test
		@DisplayName("OffsetDateTime / Instant natural-order keys select the Instant column")
		void shouldSelectInstantColumnForTemporalNaturalOrder() {
			assertInstanceOf(
				InstantValueColumn.class,
				ValueColumnFactory.forKey(OffsetDateTime.class, Comparator.naturalOrder()).create(BLOCK_SIZE)
			);
			assertInstanceOf(
				InstantValueColumn.class,
				ValueColumnFactory.forKey(Instant.class, null).create(BLOCK_SIZE)
			);
		}

		@Test
		@DisplayName("LocalDateTime natural-order keys select the Instant column too")
		void shouldSelectInstantColumnForLocalDateTime() {
			// `LocalDateTime` is normalized to an `Instant` at UTC by `FilterIndex#getNormalizer`, so the column
			// selection in `ValueColumnFactory#normalizedTypeOf` has to agree - the two must stay in lockstep or the
			// tree would be handed `Instant` keys while sizing itself for a boxed column
			assertInstanceOf(
				InstantValueColumn.class,
				ValueColumnFactory.forKey(LocalDateTime.class, Comparator.naturalOrder()).create(BLOCK_SIZE)
			);
			assertInstanceOf(
				InstantValueColumn.class,
				ValueColumnFactory.forKey(LocalDateTime.class, null).create(BLOCK_SIZE)
			);
		}

		@Test
		@DisplayName("LocalDate / LocalTime keep the cheaper single-long column")
		void shouldKeepLongColumnForLocalDateAndLocalTime() {
			// both fit losslessly in one `long` (epoch-day / nano-of-day), so routing them through `Instant` would
			// cost an extra all-but-unused `int[]` per leaf - they must NOT be swept into the temporal branch
			assertInstanceOf(
				LongValueColumn.class,
				ValueColumnFactory.forKey(LocalDate.class, Comparator.naturalOrder()).create(BLOCK_SIZE)
			);
			assertInstanceOf(
				LongValueColumn.class,
				ValueColumnFactory.forKey(LocalTime.class, null).create(BLOCK_SIZE)
			);
		}

		@Test
		@DisplayName("non-natural-order temporal keys fall back to the boxed column")
		void shouldFallBackToBoxedColumnForReverseOrder() {
			assertInstanceOf(
				BoxedObjectColumn.class,
				ValueColumnFactory.forKey(OffsetDateTime.class, Comparator.<OffsetDateTime>reverseOrder())
					.create(BLOCK_SIZE)
			);
		}

		@Test
		@DisplayName("randomized add/remove workload on an Instant-keyed tree matches a TreeMap oracle")
		@SuppressWarnings({"unchecked", "rawtypes"})
		void shouldMatchOracleOnInstantKeyedTree() {
			// build the tree via the OffsetDateTime factory so its leaves use the primitive InstantValueColumn
			final ValueColumnFactory factory =
				ValueColumnFactory.forKey(OffsetDateTime.class, Comparator.naturalOrder());
			assertInstanceOf(InstantValueColumn.class, factory.create(BLOCK_SIZE));
			final TransactionalBucketBPlusTree<Instant> tree = new TransactionalBucketBPlusTree<>(
				BLOCK_SIZE, 3, 7, 3, Instant.class, null, factory
			);

			// a small key domain that deliberately reuses epoch-seconds across distinct nanos to exercise the tiebreak
			final List<Instant> keyDomain = new ArrayList<>(40);
			for (long sec = 0; sec < 10; sec++) {
				keyDomain.add(Instant.ofEpochSecond(1_700_000_000L + sec, 0));
				keyDomain.add(Instant.ofEpochSecond(1_700_000_000L + sec, 1));
				keyDomain.add(Instant.ofEpochSecond(1_700_000_000L + sec, 500_000_000));
				keyDomain.add(Instant.ofEpochSecond(1_700_000_000L + sec, 999_999_999));
			}

			final TreeMap<Instant, TreeSet<Integer>> oracle = new TreeMap<>();
			final Random random = new Random(424242L);

			for (int op = 0; op < 6_000; op++) {
				final Instant key = keyDomain.get(random.nextInt(keyDomain.size()));
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
	 * Drives the {@link Instant}-keyed tree's primitive {@link InstantValueColumn} across a real MVCC transaction layer.
	 * The key domain deliberately reuses epoch-seconds across distinct nanos so the two-array lockstep deep copy
	 * ({@link InstantValueColumn#duplicate()}) and parallel-array steal / merge move BOTH the {@code seconds} and the
	 * {@code nanos} arrays through the commit — a seconds/nanos drift in that copy path is invisible to every
	 * non-transactional test.
	 */
	@Nested
	@DisplayName("Instant-keyed tree across an MVCC transaction")
	class TransactionalTest {

		@Test
		@DisplayName("preserves an Instant-keyed tree across a commit that splits and merges leaves")
		@Tag(TRANSACTION)
		@SuppressWarnings({"unchecked", "rawtypes"})
		void shouldPreserveInstantKeyedTreeAcrossCommit() {
			final List<Instant> domain = sameSecondDifferentNanoDomain();
			final ValueColumnFactory factory =
				ValueColumnFactory.forKey(OffsetDateTime.class, Comparator.naturalOrder());
			assertInstanceOf(InstantValueColumn.class, factory.create(BLOCK_SIZE),
				"Factory must back the tree with the primitive Instant column");
			final TransactionalBucketBPlusTree<Instant> tree = new TransactionalBucketBPlusTree<>(
				BLOCK_SIZE, 3, 7, 3, Instant.class, null, factory
			);

			// commit a base layout over the first half of the domain
			final TreeMap<Instant, TreeSet<Integer>> baseOracle = new TreeMap<>();
			final int half = domain.size() / 2;
			for (int i = 0; i < half; i++) {
				tree.addRecord(domain.get(i), 1_000 + i);
				baseOracle.computeIfAbsent(domain.get(i), k -> new TreeSet<>()).add(1_000 + i);
			}

			assertStateAfterCommit(
				tree,
				tested -> {
					// add the rest of the domain (forces splits) and remove an early stretch (forces steals / merges),
					// driving the lockstep seconds/nanos copy through the transaction layer
					for (int i = half; i < domain.size(); i++) {
						tested.addRecord(domain.get(i), 1_000 + i);
						tested.addRecord(domain.get(i), 2_000 + i);
					}
					for (int i = 0; i < half / 2; i++) {
						tested.removeRecord(domain.get(i), 1_000 + i);
					}
				},
				(original, committed) -> {
					final TreeMap<Instant, TreeSet<Integer>> oracle = new TreeMap<>(baseOracle);
					for (int i = half; i < domain.size(); i++) {
						final TreeSet<Integer> set = new TreeSet<>();
						set.add(1_000 + i);
						set.add(2_000 + i);
						oracle.put(domain.get(i), set);
					}
					for (int i = 0; i < half / 2; i++) {
						oracle.remove(domain.get(i));
					}
					assertTreeMatchesOracle(committed, oracle);
					verifyConsistent(committed);

					// the pre-commit base tree is unchanged — proves the layer decoupled (nanos included)
					assertTreeMatchesOracle(original, baseOracle);
				}
			);
		}

		@Test
		@DisplayName("discards Instant-keyed mutations on rollback, leaving the base tree untouched")
		@Tag(TRANSACTION)
		@SuppressWarnings({"unchecked", "rawtypes"})
		void shouldDiscardInstantKeyedMutationsOnRollback() {
			final List<Instant> domain = sameSecondDifferentNanoDomain();
			final ValueColumnFactory factory =
				ValueColumnFactory.forKey(OffsetDateTime.class, Comparator.naturalOrder());
			final TransactionalBucketBPlusTree<Instant> tree = new TransactionalBucketBPlusTree<>(
				BLOCK_SIZE, 3, 7, 3, Instant.class, null, factory
			);

			final TreeMap<Instant, TreeSet<Integer>> baseOracle = new TreeMap<>();
			final int half = domain.size() / 2;
			for (int i = 0; i < half; i++) {
				tree.addRecord(domain.get(i), 1_000 + i);
				baseOracle.computeIfAbsent(domain.get(i), k -> new TreeSet<>()).add(1_000 + i);
			}

			assertStateAfterRollback(
				tree,
				tested -> {
					for (int i = half; i < domain.size(); i++) {
						tested.addRecord(domain.get(i), 1_000 + i);
					}
					for (int i = 0; i < half / 2; i++) {
						tested.removeRecord(domain.get(i), 1_000 + i);
					}
				},
				(original, discarded) -> {
					assertTreeMatchesOracle(original, baseOracle);
					verifyConsistent(original);
				}
			);
		}

		/**
		 * Builds an ascending {@link Instant} key domain that reuses each epoch-second across four distinct nanos, so the
		 * tree's nano tiebreak and the lockstep seconds/nanos array moves are both exercised.
		 *
		 * @return the ascending key domain
		 */
		@Nonnull
		private static List<Instant> sameSecondDifferentNanoDomain() {
			final List<Instant> domain = new ArrayList<>(40);
			for (long sec = 0; sec < 10; sec++) {
				domain.add(Instant.ofEpochSecond(1_700_000_000L + sec, 0));
				domain.add(Instant.ofEpochSecond(1_700_000_000L + sec, 1));
				domain.add(Instant.ofEpochSecond(1_700_000_000L + sec, 500_000_000));
				domain.add(Instant.ofEpochSecond(1_700_000_000L + sec, 999_999_999));
			}
			return domain;
		}
	}
}
