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

package io.evitadb.index.map;

import io.evitadb.core.exception.StaleTransactionMemoryException;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.core.transaction.memory.TransactionalObjectVersion;
import io.evitadb.dataType.champ.ChampMap;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static io.evitadb.test.TestTags.DATA_TYPE;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
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
 * Verifies the producer-valued {@link PersistentTransactionalProducerMap}: a {@link ChampMap}-backed STM map whose
 * values are {@link TransactionalLayerProducer}s. The focus is the Δ-union commit — untouched producer values (those
 * whose `createCopyWithMergedTransactionalMemory` returns `this`) must be **shared by reference** into the next
 * snapshot, while touched / inserted / removed keys are folded in — and the layer hygiene that keeps a commit free of
 * `StaleTransactionMemoryException` and a rollback free of orphaned nested layers.
 *
 * **The marking contract.** Because a producer value mutates through its OWN diff layer (invisible to the map), the
 * commit walks only the keys the caller declares via {@link PersistentTransactionalProducerMap#markValueMutated} (plus
 * the keys put/removed at the map level). So an in-place mutation here is always paired with a mark — exactly as the
 * real `AttributeIndex` mutation paths do (`{@link #markAndSet}` mirrors that pairing). The
 * {@link ForgottenMarkSafetyNetTest} proves the deliberate counter-case: an UNMARKED in-place mutation is caught loudly
 * at commit by the maintainer's sweep, never silently lost.
 *
 * The value type is a minimal {@link CountingProducer} that mirrors the identity-preservation contract of
 * {@link io.evitadb.index.invertedIndex.InvertedIndex} / {@link io.evitadb.index.range.RangeIndex}: it returns `this`
 * when it was not mutated in the transaction, and a fresh instance carrying the new value when it was. The generational
 * randomized (fuzz) proof lives in `LongRunningPersistentTransactionalProducerMapTest` in the long-running test module.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("PersistentTransactionalProducerMap")
@Tag(INDEXING)
@Tag(DATA_TYPE)
@Tag(TRANSACTION)
class PersistentTransactionalProducerMapTest {

	/**
	 * Builds a producer map seeded with the supplied producers, configured exactly as the attribute sub-index maps are
	 * (concrete producer class + identity wrapper, because the producers merge to themselves).
	 */
	@Nonnull
	private static PersistentTransactionalProducerMap<String, CountingProducer> mapOf(
		@Nonnull Map<String, CountingProducer> seed
	) {
		return new PersistentTransactionalProducerMap<>(seed, CountingProducer.class, Function.identity());
	}

	@Nonnull
	private static Map<String, CountingProducer> seed(@Nonnull String[] keys, @Nonnull int[] values) {
		final Map<String, CountingProducer> seed = new HashMap<>();
		for (int i = 0; i < keys.length; i++) {
			seed.put(keys[i], new CountingProducer(values[i]));
		}
		return seed;
	}

	/**
	 * Mutates the producer value under `key` in place AND declares the mutation to the map — the exact pairing every real
	 * `AttributeIndex` mutation path performs (mark the key, then write through the value's own layer). Use this instead
	 * of a bare `m.get(key).set(...)` whenever the test means to commit an in-place change.
	 */
	private static void markAndSet(
		@Nonnull PersistentTransactionalProducerMap<String, CountingProducer> map,
		@Nonnull String key,
		int newValue
	) {
		map.markValueMutated(key);
		map.get(key).set(newValue);
	}

	/**
	 * The constructor's fail-fast guard: the value type MUST implement {@link TransactionalLayerProducer}, otherwise the
	 * map would silently take the wrong (producer) commit path for plain values.
	 */
	@Nested
	@DisplayName("Constructor contract")
	class ConstructorContractTest {

		@Test
		@DisplayName("rejects a value type that is not a TransactionalLayerProducer")
		@SuppressWarnings({"unchecked", "rawtypes", "ResultOfObjectAllocationIgnored"})
		void shouldRejectNonProducerValueType() {
			final Map<String, CountingProducer> seed = seed(new String[]{"a"}, new int[]{1});
			// raw types deliberately bypass the compile-time producer bound to exercise the runtime Assert.isTrue guard
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new PersistentTransactionalProducerMap(seed, String.class, Function.identity())
			);
		}
	}

	/**
	 * The headline guarantee: a producer value not mutated in the transaction is shared by reference into the committed
	 * snapshot, while a mutated one is replaced by a fresh instance carrying the new value.
	 */
	@Nested
	@DisplayName("Structural sharing on commit")
	class StructuralSharingTest {

		@Test
		@DisplayName("untouched producer value is shared by reference; touched one is replaced")
		void shouldShareUntouchedValueAndReplaceTouched() {
			final CountingProducer pa = new CountingProducer(1);
			final CountingProducer pb = new CountingProducer(2);
			final Map<String, CountingProducer> seed = new HashMap<>();
			seed.put("a", pa);
			seed.put("b", pb);
			final PersistentTransactionalProducerMap<String, CountingProducer> map = mapOf(seed);

			assertStateAfterCommit(
				map,
				// mutate only "a" in place and declare it (mark + write, as AttributeIndex does) → the MAP's diff layer
				// is created by the mark, the Δ-walk visits "a" and shares "b"
				m -> markAndSet(m, "a", 99),
				(m, committed) -> {
					assertInstanceOf(ChampMap.class, committed);
					assertEquals(99, committed.get("a").committedValue());
					assertEquals(2, committed.get("b").committedValue());
					// "a" was touched → fresh instance; "b" untouched → same instance shared
					assertNotSame(pa, committed.get("a"));
					assertSame(pb, committed.get("b"));
				}
			);
		}

		@Test
		@DisplayName("a transaction touching nothing shares every value by reference")
		void shouldShareEveryValueWhenNothingMutated() {
			final Map<String, CountingProducer> seed = seed(new String[]{"a", "b", "c"}, new int[]{1, 2, 3});
			final CountingProducer pa = seed.get("a");
			final CountingProducer pb = seed.get("b");
			final CountingProducer pc = seed.get("c");
			final PersistentTransactionalProducerMap<String, CountingProducer> map = mapOf(seed);

			assertStateAfterCommit(
				map,
				m -> {
					// only reads
					assertEquals(1, m.get("a").committedValue());
					assertTrue(m.containsKey("b"));
				},
				(m, committed) -> {
					assertInstanceOf(ChampMap.class, committed);
					assertSame(pa, committed.get("a"));
					assertSame(pb, committed.get("b"));
					assertSame(pc, committed.get("c"));
				}
			);
		}

		@Test
		@DisplayName("only the mutated keys diverge; the rest stay shared at scale")
		void shouldShareAllButMutatedAtScale() {
			final String[] keys = new String[16];
			final int[] values = new int[16];
			for (int i = 0; i < 16; i++) {
				//noinspection StringConcatenationMissingWhitespace
				keys[i] = "k" + i;
				values[i] = i;
			}
			final Map<String, CountingProducer> seed = seed(keys, values);
			final Map<String, CountingProducer> originals = new HashMap<>(seed);
			final PersistentTransactionalProducerMap<String, CountingProducer> map = mapOf(seed);

			assertStateAfterCommit(
				map,
				m -> {
					markAndSet(m, "k3", 300);
					markAndSet(m, "k7", 700);
				},
				(m, committed) -> {
					for (final String key : keys) {
						if (key.equals("k3") || key.equals("k7")) {
							assertNotSame(originals.get(key), committed.get(key));
						} else {
							assertSame(originals.get(key), committed.get(key), "value for " + key + " should be shared");
						}
					}
					assertEquals(300, committed.get("k3").committedValue());
					assertEquals(700, committed.get("k7").committedValue());
				}
			);
		}

		@Test
		@DisplayName("a key marked but whose producer merges back to itself is shared, not path-copied")
		void shouldShareValueMarkedButNotMutated() {
			final Map<String, CountingProducer> seed = seed(new String[]{"a"}, new int[]{1});
			final CountingProducer pa = seed.get("a");
			final PersistentTransactionalProducerMap<String, CountingProducer> map = mapOf(seed);

			assertStateAfterCommit(
				map,
				// declare "a" as mutated but DON'T actually change the producer → it merges back to `this`, so the
				// Δ-walk's identity short-circuit (`committed != value` is false) must leave the node shared in place
				m -> m.markValueMutated("a"),
				(m, committed) -> {
					assertInstanceOf(ChampMap.class, committed);
					assertEquals(1, committed.get("a").committedValue());
					// shared by reference - the marked-but-unchanged value was not path-copied
					assertSame(pa, committed.get("a"));
				}
			);
		}
	}

	/**
	 * Insert and remove at the map level commit correctly and keep nested layers tidy.
	 */
	@Nested
	@DisplayName("Insert, remove and replace")
	class InsertRemoveTest {

		@Test
		@DisplayName("a newly inserted producer is committed and reads its mutated value")
		void shouldCommitInsertedProducer() {
			final Map<String, CountingProducer> seed = seed(new String[]{"a"}, new int[]{1});
			final PersistentTransactionalProducerMap<String, CountingProducer> map = mapOf(seed);

			assertStateAfterCommit(
				map,
				m -> {
					final CountingProducer fresh = new CountingProducer(5);
					m.put("b", fresh);
					m.get("b").set(50);
				},
				(m, committed) -> {
					assertEquals(1, committed.get("a").committedValue());
					assertEquals(50, committed.get("b").committedValue());
				}
			);
		}

		@Test
		@DisplayName("a removed key is gone from the committed snapshot")
		void shouldCommitRemoval() {
			final Map<String, CountingProducer> seed = seed(new String[]{"a", "b"}, new int[]{1, 2});
			final CountingProducer pb = seed.get("b");
			final PersistentTransactionalProducerMap<String, CountingProducer> map = mapOf(seed);

			assertStateAfterCommit(
				map,
				m -> m.remove("a"),
				(m, committed) -> {
					assertNull(committed.get("a"));
					assertFalse(committed.containsKey("a"));
					// untouched survivor is still shared
					assertSame(pb, committed.get("b"));
				}
			);
		}

		@Test
		@DisplayName("insert, in-place mutate and remove combined produce the correct committed snapshot")
		void shouldCommitMixedChanges() {
			final Map<String, CountingProducer> seed = seed(new String[]{"a", "b", "c"}, new int[]{1, 2, 3});
			final PersistentTransactionalProducerMap<String, CountingProducer> map = mapOf(seed);

			assertStateAfterCommit(
				map,
				m -> {
					markAndSet(m, "a", 11);
					m.remove("b");
					m.put("d", new CountingProducer(4));
				},
				(m, committed) -> {
					assertEquals(11, committed.get("a").committedValue());
					assertFalse(committed.containsKey("b"));
					assertEquals(3, committed.get("c").committedValue());
					assertEquals(4, committed.get("d").committedValue());
				}
			);
		}

		@Test
		@DisplayName("replacing an existing key with a fresh producer commits the new instance")
		void shouldReplaceExistingProducerWithFreshInstance() {
			final Map<String, CountingProducer> seed = seed(new String[]{"a"}, new int[]{1});
			final CountingProducer original = seed.get("a");
			final CountingProducer replacement = new CountingProducer(42);
			final PersistentTransactionalProducerMap<String, CountingProducer> map = mapOf(seed);

			assertStateAfterCommit(
				map,
				// full replacement at the map level (not an in-place mutation) → "a" lands in modifiedKeys, the Δ-walk
				// folds the fresh instance and the previous one is dropped without leaving an orphaned layer
				m -> m.put("a", replacement),
				(m, committed) -> {
					assertEquals(42, committed.get("a").committedValue());
					assertNotSame(original, committed.get("a"));
					assertSame(replacement, committed.get("a"));
				}
			);
		}
	}

	/**
	 * Rollback discards every change and leaves no orphaned nested producer layer.
	 */
	@Nested
	@DisplayName("Rollback")
	class RollbackTest {

		@Test
		@DisplayName("rollback restores the original snapshot and releases nested layers")
		void shouldRollbackCleanly() {
			final Map<String, CountingProducer> seed = seed(new String[]{"a", "b"}, new int[]{1, 2});
			final PersistentTransactionalProducerMap<String, CountingProducer> map = mapOf(seed);

			assertStateAfterRollback(
				map,
				m -> {
					m.get("a").set(99);
					m.put("c", new CountingProducer(3));
					m.remove("b");
				},
				(m, ignored) -> {
					// the live map still sees the pre-transaction state
					assertEquals(1, m.get("a").committedValue());
					assertEquals(2, m.get("b").committedValue());
					assertFalse(m.containsKey("c"));
				}
			);
		}
	}

	/**
	 * The de-risking guarantee that makes the whole `markValueMutated` design safe: a forgotten mark is LOUD, never
	 * silent. If a value mutates in place but its key is never declared (and nothing else touches the map), the map's
	 * diff layer is never created, commit short-circuits to the sealed snapshot without sweeping that value, and the
	 * maintainer's `verifyLayerWasFullySwept` throws — so a missed mark surfaces as a hard test failure rather than
	 * silent staleness.
	 */
	@Nested
	@DisplayName("Forgotten-mark safety net")
	class ForgottenMarkSafetyNetTest {

		@Test
		@DisplayName("an unmarked in-place mutation orphans the value layer and throws at commit")
		void shouldThrowWhenInPlaceMutationNotMarked() {
			final Map<String, CountingProducer> seed = seed(new String[]{"a"}, new int[]{1});
			final PersistentTransactionalProducerMap<String, CountingProducer> map = mapOf(seed);

			// mutate "a" in place WITHOUT marking it → the map layer is never created → commit cannot sweep "a"'s layer
			assertThrows(
				StaleTransactionMemoryException.class,
				() -> assertStateAfterCommit(
					map,
					m -> m.get("a").set(99),
					(m, committed) -> {
						// unreachable: the sweep throws before the verify callback runs
					}
				)
			);
		}

		@Test
		@DisplayName("the same mutation, correctly marked, commits cleanly")
		void shouldCommitWhenInPlaceMutationMarked() {
			final Map<String, CountingProducer> seed = seed(new String[]{"a"}, new int[]{1});
			final PersistentTransactionalProducerMap<String, CountingProducer> map = mapOf(seed);

			assertStateAfterCommit(
				map,
				m -> markAndSet(m, "a", 99),
				(m, committed) -> assertEquals(99, committed.get("a").committedValue())
			);
		}
	}

	/**
	 * Nested-layer hygiene of the Δ-union commit: the two branches that the generational fuzz cannot reach (it touches
	 * each key at most once per transaction and never aliases an instance across keys). Both guard against orphaned or
	 * prematurely-released nested transactional layers — the exact bug class that surfaces as a
	 * {@link StaleTransactionMemoryException} or silent staleness in real `AttributeIndex` commits.
	 */
	@Nested
	@DisplayName("Layer hygiene on commit")
	class LayerHygieneTest {

		@Test
		@DisplayName("a layer shared with a surviving key is not released when another key holding the same instance is removed")
		void shouldNotReleaseLayerSharedWithSurvivingKey() {
			final Map<String, CountingProducer> seed = seed(new String[]{"a"}, new int[]{1});
			final CountingProducer shared = seed.get("a");
			final PersistentTransactionalProducerMap<String, CountingProducer> map = mapOf(seed);

			assertStateAfterCommit(
				map,
				m -> {
					// alias the SAME instance under a second key in the diff layer, mutate it (creating its single layer),
					// then remove the original key. The removal's survivor guard must see "b" still references the instance
					// and NOT release its layer - otherwise "b"'s commit-time sweep would discard an already-discarded layer.
					m.put("b", shared);
					m.get("b").set(99);
					m.remove("a");
				},
				(m, committed) -> {
					// reaching the verify callback at all proves no premature release threw during the sweep
					assertNull(committed.get("a"));
					assertFalse(committed.containsKey("a"));
					assertNotNull(committed.get("b"));
					assertEquals(99, committed.get("b").committedValue());
				}
			);
		}

		@Test
		@DisplayName("a producer created and then removed within one transaction has its orphaned layer released")
		void shouldReleaseLayerOfProducerCreatedThenRemovedInSameTransaction() {
			final Map<String, CountingProducer> seed = seed(new String[]{"a"}, new int[]{1});
			final PersistentTransactionalProducerMap<String, CountingProducer> map = mapOf(seed);

			assertStateAfterCommit(
				map,
				m -> {
					// create a brand-new producer, give it a live nested layer, then remove the same key in the same tx.
					// The instance now lives under no key, so the deferred created-then-removed sweep must release its
					// layer - otherwise it orphans and the commit-time verifyLayerWasFullySwept throws.
					m.put("x", new CountingProducer(5));
					m.get("x").set(50);
					m.remove("x");
				},
				(m, committed) -> {
					// no StaleTransactionMemoryException means the orphaned layer was swept; "a" survives untouched
					assertFalse(committed.containsKey("x"));
					assertEquals(1, committed.get("a").committedValue());
				}
			);
		}

		@Test
		@DisplayName("clear within a transaction releases the nested layers of producers added that transaction")
		void shouldReleaseNestedLayersOnClearWithinTransaction() {
			final Map<String, CountingProducer> seed = seed(new String[]{"a"}, new int[]{1});
			final PersistentTransactionalProducerMap<String, CountingProducer> map = mapOf(seed);

			assertStateAfterCommit(
				map,
				m -> {
					// add a producer and give it a live nested layer, then clear the whole map in the same tx. clear()'s
					// cleanAll must release that layer - otherwise it orphans and commit's verifyLayerWasFullySwept throws.
					m.put("c", new CountingProducer(3));
					m.get("c").set(30);
					m.clear();
				},
				(m, committed) -> {
					// committed snapshot is empty and no stale-layer exception was thrown during the sweep
					assertTrue(committed.isEmpty());
					assertFalse(committed.containsKey("a"));
					assertFalse(committed.containsKey("c"));
				}
			);
		}
	}

	/**
	 * Edge semantics of the {@link PersistentTransactionalProducerMap#markValueMutated} marking contract: the
	 * outside-transaction no-op, precedence when a marked key is also removed, and a mark on a key that does not exist.
	 */
	@Nested
	@DisplayName("Marking semantics")
	class MarkingSemanticsTest {

		@Test
		@DisplayName("markValueMutated outside a transaction is a no-op")
		void shouldNoOpWhenMarkValueMutatedOutsideTransaction() {
			final Map<String, CountingProducer> seed = seed(new String[]{"a"}, new int[]{1});
			final PersistentTransactionalProducerMap<String, CountingProducer> map = mapOf(seed);

			// no active transaction → no layer to record into → the call must quietly do nothing and leave state intact
			map.markValueMutated("a");

			assertEquals(1, map.get("a").committedValue());
			assertEquals(1, map.size());
		}

		@Test
		@DisplayName("a key both marked and removed in one transaction is removed cleanly (removal takes precedence)")
		void shouldPreferRemovalOverMarkForSameKey() {
			final Map<String, CountingProducer> seed = seed(new String[]{"a", "b"}, new int[]{1, 2});
			final PersistentTransactionalProducerMap<String, CountingProducer> map = mapOf(seed);

			assertStateAfterCommit(
				map,
				m -> {
					// mark + mutate "a" in place, then remove it: the removal pass releases the instance's layer and the
					// value-mutated pass must skip the now-removed key (removed > valueMutated precedence) - no double sweep
					markAndSet(m, "a", 99);
					m.remove("a");
				},
				(m, committed) -> {
					assertFalse(committed.containsKey("a"));
					assertNull(committed.get("a"));
					assertEquals(2, committed.get("b").committedValue());
				}
			);
		}

		@Test
		@DisplayName("marking a non-existent key is a harmless no-op at commit")
		void shouldIgnoreMarkOnNonExistentKey() {
			final Map<String, CountingProducer> seed = seed(new String[]{"a"}, new int[]{1});
			final PersistentTransactionalProducerMap<String, CountingProducer> map = mapOf(seed);

			assertStateAfterCommit(
				map,
				// declaring a key that holds no value must not NPE or corrupt the merge - the value-mutated pass skips it
				m -> m.markValueMutated("ghost"),
				(m, committed) -> {
					assertEquals(1, committed.get("a").committedValue());
					assertFalse(committed.containsKey("ghost"));
					assertEquals(1, committed.size());
				}
			);
		}
	}

	/**
	 * Clone hygiene: cloning inside a transaction must carry this map's diff state — including the producer-specific
	 * `valueMutatedKeys` set ({@link ProducerMapChanges#copyState}) — into the clone's own layer, and the clone must be an
	 * independent instance.
	 */
	@Nested
	@DisplayName("Clone")
	class CloneTest {

		@Test
		@DisplayName("clone inside a transaction copies the diff state (incl. marked keys) into an independent instance")
		void shouldCarryMarkingStateOnCloneInsideTransaction() {
			final Map<String, CountingProducer> seed = seed(new String[]{"a", "b"}, new int[]{1, 2});
			final PersistentTransactionalProducerMap<String, CountingProducer> map = mapOf(seed);

			assertStateAfterCommit(
				map,
				original -> {
					original.put("c", new CountingProducer(3));
					// mark (without a per-instance mutation, so no shared layer is double-swept) → copyState must carry
					// the valueMutatedKeys set into the clone's layer, exercising the producer-specific override
					original.markValueMutated("a");
					try {
						@SuppressWarnings("unchecked")
						final PersistentTransactionalProducerMap<String, CountingProducer> clone =
							(PersistentTransactionalProducerMap<String, CountingProducer>) original.clone();
						// the clone is an independent instance that sees the in-transaction membership
						assertNotSame(original, clone);
						assertTrue(clone.containsKey("a"));
						assertTrue(clone.containsKey("b"));
						assertTrue(clone.containsKey("c"));
					} catch (CloneNotSupportedException ex) {
						throw new IllegalStateException("Clone should be supported!", ex);
					}
				},
				(original, committed) -> {
					assertEquals(1, committed.get("a").committedValue());
					assertEquals(2, committed.get("b").committedValue());
					assertEquals(3, committed.get("c").committedValue());
				}
			);
		}
	}

	/**
	 * Minimal {@link TransactionalLayerProducer} that mirrors the identity-preservation contract of the real
	 * attribute-index producers: an untouched instance merges to itself (`this`), a touched one to a fresh instance
	 * carrying the new value. Its diff layer is an `int[]{newValue, touchedFlag}`.
	 */
	private static final class CountingProducer implements TransactionalLayerProducer<int[], CountingProducer> {
		private final long id = TransactionalObjectVersion.SEQUENCE.nextId();
		private int value;

		CountingProducer(int value) {
			this.value = value;
		}

		@Override
		public long getId() {
			return this.id;
		}

		/**
		 * Returns the committed (non-transactional) value held by this producer.
		 */
		int committedValue() {
			return this.value;
		}

		/**
		 * Records a new value in this producer's own diff layer when a transaction is open, otherwise applies it
		 * directly. Routing the mutation through the producer's own layer (never the enclosing map) is exactly how the
		 * real attribute indexes mutate in place.
		 */
		void set(int newValue) {
			final int[] layer = Transaction.getOrCreateTransactionalMemoryLayer(this);
			if (layer == null) {
				this.value = newValue;
			} else {
				layer[0] = newValue;
				layer[1] = 1;
			}
		}

		@Nonnull
		@Override
		public int[] createLayer() {
			// [newValue, touchedFlag] - starts untouched, carrying the current value
			return new int[]{this.value, 0};
		}

		@Nonnull
		@Override
		public CountingProducer createCopyWithMergedTransactionalMemory(
			@Nullable int[] layer,
			@Nonnull TransactionalLayerMaintainer transactionalLayer
		) {
			if (layer == null || layer[1] == 0) {
				// not mutated in this transaction → preserve identity so the enclosing map can structurally share it
				return this;
			}
			return new CountingProducer(layer[0]);
		}

		@Override
		public void removeLayer(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			transactionalLayer.removeTransactionalMemoryLayerIfExists(this);
		}
	}
}
