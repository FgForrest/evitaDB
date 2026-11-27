/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.core.transaction.memory;

import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer.Savepoint;
import io.evitadb.index.array.TransactionalIntArray;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.reference.TransactionalReference;
import io.evitadb.index.set.TransactionalSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the savepoint mechanism on {@link TransactionalLayerMaintainer} together with the {@link Snapshotable}
 * implementations on the transactional diff layers. Each test opens a savepoint inside a live transaction, mutates a
 * real transactional structure, and asserts that {@link TransactionalLayerMaintainer#rollbackSavepoint(Savepoint)}
 * restores the exact pre-savepoint state (while the surrounding transaction keeps running) — the per-entity partial
 * rollback capability.
 *
 * Tests read the structure's visible state through its own API while the transaction is active, then verify that
 * the surrounding transaction commits the expected state.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(ENGINE)
@Tag(TRANSACTION)
@DisplayName("Transactional layer maintainer savepoint (per-entity rollback)")
class TransactionalLayerMaintainerSavepointTest {

	/**
	 * Verifies that a rollback restores map entries inserted, removed or overwritten in the savepoint.
	 */
	@Test
	@DisplayName("TransactionalMap: rollbackSavepoint reverts puts/removes made in the savepoint")
	void shouldRollbackSavepointForMap() {
		final Map<String, Integer> delegate = new HashMap<>();
		delegate.put("a", 1);
		final TransactionalMap<String, Integer> map = new TransactionalMap<>(delegate);
		assertSavepointRollbackRestores(
			map,
			HashMap::new,
			it -> {
				it.put("b", 2);
			},
			it -> {
				it.put("c", 3);
				it.remove("a");
				it.put("b", 20);
			},
			Objects::equals,
			Function.identity()
		);
	}

	/**
	 * Verifies that rollback removes a map diff created inside the savepoint.
	 */
	@Test
	@DisplayName("TransactionalMap: a layer created inside the savepoint is removed on rollback")
	void shouldRemoveCreatedMapLayerOnRollback() {
		final Map<String, Integer> delegate = new HashMap<>();
		delegate.put("a", 1);
		final TransactionalMap<String, Integer> map = new TransactionalMap<>(delegate);
		assertSavepointRemovesCreatedLayer(
			map,
			HashMap::new,
			it -> {
				it.put("b", 2);
				it.remove("a");
			},
			Objects::equals
		);
	}

	/**
	 * Verifies that committing a savepoint retains map changes through the transaction commit.
	 */
	@Test
	@DisplayName("TransactionalMap: commitSavepoint keeps the savepoint changes")
	void shouldKeepChangesOnCommitSavepointForMap() {
		final Map<String, Integer> delegate = new HashMap<>();
		delegate.put("a", 1);
		final TransactionalMap<String, Integer> map = new TransactionalMap<>(delegate);
		assertCommitSavepointKeepsChanges(
			map,
			HashMap::new,
			it -> {
				it.put("b", 2);
			},
			it -> {
				it.put("c", 3);
				it.remove("a");
			},
			Objects::equals
		);
	}

	/**
	 * Verifies that rollback restores set membership after additions and removals.
	 */
	@Test
	@DisplayName("TransactionalSet: rollbackSavepoint reverts adds/removes made in the savepoint")
	void shouldRollbackSavepointForSet() {
		final Set<String> delegate = new HashSet<>();
		delegate.add("a");
		final TransactionalSet<String> set = new TransactionalSet<>(delegate);
		assertSavepointRollbackRestores(
			set,
			Set::copyOf,
			it -> it.add("b"),
			it -> {
				it.add("c");
				it.remove("a");
			},
			Objects::equals,
			Function.identity()
		);
	}

	/**
	 * Verifies that rollback restores the exact bitmap contents after an add and a remove.
	 */
	@Test
	@DisplayName("TransactionalBitmap: rollbackSavepoint reverts adds/removes made in the savepoint")
	void shouldRollbackSavepointForBitmap() {
		final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2, 3);
		assertSavepointRollbackRestores(
			bitmap,
			TransactionalBitmap::getArray,
			it -> it.add(10),
			it -> {
				it.add(20);
				it.remove(1);
			},
			Arrays::equals,
			Bitmap::getArray
		);
	}

	/**
	 * Verifies that rollback restores the boolean value visible before the savepoint.
	 */
	@Test
	@DisplayName("TransactionalBoolean: rollbackSavepoint reverts the value flipped in the savepoint")
	void shouldRollbackSavepointForBoolean() {
		final TransactionalBoolean bool = new TransactionalBoolean(false);
		assertSavepointRollbackRestores(
			bool,
			TransactionalBoolean::isTrue,
			TransactionalBoolean::setToTrue,
			TransactionalBoolean::setToFalse,
			Objects::equals,
			Function.identity()
		);
	}

	/**
	 * Verifies that rollback restores the reference visible before the savepoint.
	 */
	@Test
	@DisplayName("TransactionalReference: rollbackSavepoint reverts the reference set in the savepoint")
	void shouldRollbackSavepointForReference() {
		final TransactionalReference<String> reference = new TransactionalReference<>("a");
		assertSavepointRollbackRestores(
			reference,
			it -> Optional.ofNullable(it.get()),
			it -> it.set("b"),
			it -> it.set("c"),
			Objects::equals,
			Function.identity()
		);
	}

	/**
	 * Verifies that rollback restores the sorted array after an add and a remove.
	 */
	@Test
	@DisplayName("TransactionalIntArray: rollbackSavepoint reverts adds/removes made in the savepoint")
	void shouldRollbackSavepointForIntArray() {
		final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 2, 3});
		assertSavepointRollbackRestores(
			array,
			TransactionalIntArray::getArray,
			it -> it.add(10),
			it -> {
				it.add(20);
				it.remove(1);
			},
			Arrays::equals,
			Function.identity()
		);
	}

	/**
	 * Verifies that rollback discards a positional array diff created after the savepoint opens.
	 */
	@Test
	@DisplayName("TransactionalIntArray: a layer created inside the savepoint is removed on rollback")
	void shouldRemoveCreatedIntArrayLayerOnRollback() {
		final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 3, 5});
		assertSavepointRemovesCreatedLayer(
			array,
			TransactionalIntArray::getArray,
			it -> {
				it.add(2);
				it.remove(3);
			},
			Arrays::equals
		);
	}

	/**
	 * Verifies that repeated memento restores reproduce the same set membership.
	 */
	@Test
	@DisplayName("TransactionalSet: an undo-journal memento can be restored repeatedly and stays faithful")
	void shouldSupportRepeatRestoreForSet() {
		final Set<String> delegate = new HashSet<>();
		delegate.add("a");
		final TransactionalSet<String> set = new TransactionalSet<>(delegate);
		assertRepeatRestoreSafe(
			set,
			Set::copyOf,
			it -> it.add("b"),
			it -> {
				it.add("c");
				it.remove("a");
				it.add("d");
			},
			Objects::equals
		);
	}

	/**
	 * Verifies that repeated memento restores reproduce the same map contents.
	 */
	@Test
	@DisplayName("TransactionalMap: an undo-journal memento can be restored repeatedly and stays faithful")
	void shouldSupportRepeatRestoreForMap() {
		final Map<String, Integer> delegate = new HashMap<>();
		delegate.put("a", 1);
		final TransactionalMap<String, Integer> map = new TransactionalMap<>(delegate);
		assertRepeatRestoreSafe(
			map,
			HashMap::new,
			it -> it.put("b", 2),
			it -> {
				it.put("c", 3);
				it.remove("a");
				it.put("b", 20);
			},
			Objects::equals
		);
	}

	/**
	 * Verifies that repeated memento restores reproduce the same sorted array.
	 */
	@Test
	@DisplayName("IntArrayChanges: a memento can be restored repeatedly and stays faithful (no aliasing)")
	void shouldSupportRepeatRestoreForIntArray() {
		// 50 and 60 both sort into the same gap (between 1 and 100), so the second add reuses the existing
		// insertion bucket via an in-place outer-slot write — the exact path that would corrupt an aliased memento
		final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 100});
		assertRepeatRestoreSafe(
			array,
			TransactionalIntArray::getArray,
			it -> it.add(50),
			it -> it.add(60),
			Arrays::equals
		);
	}

	/**
	 * Verifies that rollback reinstates a created set key removed through removeAll.
	 */
	@Test
	@DisplayName("TransactionalSet: rollbackSavepoint reverts a removeAll of a created key made in the savepoint")
	void shouldRollbackSavepointForSetRemoveAllOfCreatedKey() {
		final Set<String> delegate = new HashSet<>();
		delegate.add("a");
		final TransactionalSet<String> set = new TransactionalSet<>(delegate);
		assertSavepointRollbackRestores(
			set,
			Set::copyOf,
			it -> it.add("b"),
			// removeAll drives the merged iterator's remove(), which drops the created key straight from the diff
			// layer's created-keys set — the rollback must reinstate it exactly like a plain remove("b") would
			it -> it.removeAll(Set.of("b")),
			Objects::equals,
			Function.identity()
		);
	}

	/**
	 * Verifies that rollback reinstates a set key removed by the iterator on first touch.
	 */
	@Test
	@DisplayName("TransactionalSet: iterator removal as the first savepoint touch is reverted on rollback")
	void shouldRollbackSavepointForSetIteratorRemoveAsFirstSavepointTouch() {
		final Set<String> delegate = new HashSet<>();
		delegate.add("a");
		final TransactionalSet<String> set = new TransactionalSet<>(delegate);
		assertCommittedState(set, maintainer -> {
			// the diff layer exists before the savepoint opens
			set.add("b");
			final Set<String> before = Set.copyOf(set);

			final Savepoint savepoint = maintainer.openSavepoint();
			// the FIRST (and only) layer touch inside the savepoint is a removal through the merged iterator
			final Iterator<String> it = set.iterator();
			while (it.hasNext()) {
				if ("a".equals(it.next())) {
					it.remove();
					break;
				}
			}
			final Set<String> during = Set.copyOf(set);
			assertNotEquals(
				before, during,
				"Savepoint mutation should have changed the visible state - the test would be vacuous otherwise."
			);

			maintainer.rollbackSavepoint(savepoint);
			final Set<String> after = Set.copyOf(set);
			assertEquals(
				before, after,
				"a removal made solely through the merged iterator must be reverted by rollbackSavepoint"
			);
			return before;
		}, Objects::equals);
	}

	/**
	 * Verifies that rollback reinstates a key removed through a pre-savepoint map iterator.
	 */
	@Test
	@DisplayName("TransactionalMap: a removal through an entry iterator obtained before the savepoint is reverted")
	void shouldRollbackSavepointForMapIteratorRemoveWhenIteratorPredatesSavepoint() {
		final Map<String, Integer> delegate = new HashMap<>();
		delegate.put("a", 1);
		final TransactionalMap<String, Integer> map = new TransactionalMap<>(delegate);
		assertCommittedState(map, maintainer -> {
			map.put("x", 9);
			map.put("b", 2);
			final Map<String, Integer> before = new HashMap<>(map);

			// the entry iterator is obtained BEFORE the savepoint opens (the layer exists, no journal is active yet)
			// and positioned on the created key "b"
			final Iterator<Entry<String, Integer>> it = map.entrySet().iterator();
			Entry<String, Integer> current = null;
			while (it.hasNext()) {
				current = it.next();
				if ("b".equals(current.getKey())) {
					break;
				}
			}
			assertNotNull(current);

			final Savepoint savepoint = maintainer.openSavepoint();
			// write-touch inside the savepoint: records the layer snapshot and activates its journal; the put only
			// overwrites an existing diff entry, so the iterator held above stays structurally valid
			map.put("x", 10);
			// ... and the pre-savepoint iterator now removes "b" from the diff layer
			it.remove();
			final Map<String, Integer> during = new HashMap<>(map);
			assertFalse(during.containsKey("b"));
			assertNotEquals(
				before, during,
				"Savepoint mutation should have changed the visible state - the test would be vacuous otherwise."
			);

			maintainer.rollbackSavepoint(savepoint);
			final Map<String, Integer> after = new HashMap<>(map);
			assertEquals(
				before, after,
				"a removal made through a pre-savepoint entry iterator must be reverted by rollbackSavepoint"
			);
			return before;
		}, Objects::equals);
	}

	/**
	 * Verifies that rollback restores an existing entry updated through setValue on first touch.
	 */
	@Test
	@DisplayName("TransactionalMap: entry setValue as the first savepoint touch is reverted on rollback")
	void shouldRollbackSavepointForMapEntrySetValueAsFirstSavepointTouch() {
		final Map<String, Integer> delegate = new HashMap<>();
		delegate.put("a", 1);
		final TransactionalMap<String, Integer> map = new TransactionalMap<>(delegate);
		assertCommittedState(map, maintainer -> {
			// the diff layer exists before the savepoint opens
			map.put("b", 2);
			final Map<String, Integer> before = new HashMap<>(map);

			final Savepoint savepoint = maintainer.openSavepoint();
			// the FIRST layer touch inside the savepoint overwrites an existing entry's value in place through the
			// entry-set view's setValue proxy
			for (final Entry<String, Integer> entry : map.entrySet()) {
				if ("a".equals(entry.getKey())) {
					entry.setValue(99);
					break;
				}
			}
			final Map<String, Integer> during = new HashMap<>(map);
			assertNotEquals(
				before, during,
				"Savepoint mutation should have changed the visible state - the test would be vacuous otherwise."
			);

			maintainer.rollbackSavepoint(savepoint);
			final Map<String, Integer> after = new HashMap<>(map);
			assertEquals(
				before, after,
				"an in-place entry.setValue must be reverted by rollbackSavepoint"
			);
			return before;
		}, Objects::equals);
	}

	/**
	 * Verifies that rollback restores a created entry updated through setValue.
	 */
	@Test
	@DisplayName("TransactionalMap: entry setValue on a created (layer) entry is reverted on rollback")
	void shouldRollbackSavepointForMapEntrySetValueOnCreatedEntry() {
		// empty delegate: the key put below lives only in the diff layer, so the entry-set iterator yields it as a raw
		// modifiedKeys entry (not a delegate wrapper) - the setValue path that mutates the layer map in place
		final TransactionalMap<String, Integer> map = new TransactionalMap<>(new HashMap<>());
		assertCommittedState(map, maintainer -> {
			// "a" is a created key held in the diff layer (modifiedKeys), not in the delegate
			map.put("a", 1);
			final Map<String, Integer> before = new HashMap<>(map);

			final Savepoint savepoint = maintainer.openSavepoint();
			// marker write-touch: records the layer snapshot and activates its undo journal for this savepoint
			map.put("m", 9);
			// overwrite the created entry's value in place through the entry-set view's setValue proxy
			for (final Entry<String, Integer> entry : map.entrySet()) {
				if ("a".equals(entry.getKey())) {
					entry.setValue(99);
					break;
				}
			}
			final Map<String, Integer> during = new HashMap<>(map);
			assertEquals(99, during.get("a"));
			assertNotEquals(
				before, during,
				"Savepoint mutation should have changed the visible state - the test would be vacuous otherwise."
			);

			maintainer.rollbackSavepoint(savepoint);
			final Map<String, Integer> after = new HashMap<>(map);
			assertEquals(
				before, after,
				"an in-place setValue on a created (layer) entry must be reverted by rollbackSavepoint"
			);
			return before;
		}, Objects::equals);
	}

	/**
	 * Verifies that rollback restores a map cleared through its key set on first touch.
	 */
	@Test
	@DisplayName("TransactionalMap: keySet().clear() as the first savepoint touch is reverted on rollback")
	void shouldRollbackSavepointForMapKeySetClearAsFirstSavepointTouch() {
		final Map<String, Integer> delegate = new HashMap<>();
		delegate.put("a", 1);
		final TransactionalMap<String, Integer> map = new TransactionalMap<>(delegate);
		assertCommittedState(map, maintainer -> {
			map.put("b", 2);
			final Map<String, Integer> before = new HashMap<>(map);

			final Savepoint savepoint = maintainer.openSavepoint();
			// the FIRST layer touch inside the savepoint clears the whole map through its key-set view, which forwards
			// to MapChanges#cleanAll without going through the maintainer's write-touch hook
			map.keySet().clear();
			final Map<String, Integer> during = new HashMap<>(map);
			assertNotEquals(
				before, during,
				"Savepoint mutation should have changed the visible state - the test would be vacuous otherwise."
			);

			maintainer.rollbackSavepoint(savepoint);
			final Map<String, Integer> after = new HashMap<>(map);
			assertEquals(
				before, after,
				"a clear() through the key-set view must be reverted by rollbackSavepoint"
			);
			return before;
		}, Objects::equals);
	}

	/**
	 * Directly exercises the {@link Snapshotable} contract's repeat-restore invariant on a structure's diff layer:
	 * snapshot once, then twice over {perturb the layer; restore the same memento} must both return the structure to
	 * the snapshot state. This catches mementos that alias the live layer (so a post-restore mutation corrupts them).
	 *
	 * @param structure the structure under test
	 * @param readState captures the visible state
	 * @param createLayerMutation establishes the diff layer and its initial state
	 * @param perturbation mutates the layer before each restore
	 * @param stateEquals compares snapshots for exact equality
	 */
	private static <S, X, T extends TransactionalLayerProducer<X, S>> void assertRepeatRestoreSafe(
		@Nonnull T structure,
		@Nonnull Function<T, S> readState,
		@Nonnull Consumer<T> createLayerMutation,
		@Nonnull Consumer<T> perturbation,
		@Nonnull BiPredicate<S, S> stateEquals
	) {
		assertCommittedState(structure, maintainer -> {
			createLayerMutation.accept(structure);
			final Object layer = maintainer.getTransactionalMemoryLayerIfExists(structure);
			assertInstanceOf(Snapshotable.class, layer, "the diff layer must implement Snapshotable");
			@SuppressWarnings("unchecked") final Snapshotable<Object> snapshotable = (Snapshotable<Object>) layer;

			final Object memento = snapshotable.snapshot();
			final S captured = readState.apply(structure);

			for (int round = 0; round < 2; round++) {
				perturbation.accept(structure);
				snapshotable.restore(memento);
				final S after = readState.apply(structure);
				assertTrue(
					stateEquals.test(captured, after),
					"restore from the same memento must stay faithful on round " + round
				);
			}
			return captured;
		}, stateEquals);
	}

	/**
	 * Opens a savepoint, applies `preSavepointMutation` before it and `savepointMutation` inside it, then asserts
	 * `rollbackSavepoint` returns the structure to the exact pre-savepoint state.
	 *
	 * @param structure the structure under test
	 * @param readState captures the visible state
	 * @param preSavepointMutation establishes the visible state before the savepoint
	 * @param savepointMutation mutates the structure while the savepoint is open
	 * @param stateEquals compares snapshots for exact equality
	 * @param readCommittedState reads the committed state in the same form as the snapshots
	 */
	private static <S, T extends TransactionalLayerProducer<?, S>, R> void assertSavepointRollbackRestores(
		@Nonnull T structure,
		@Nonnull Function<T, R> readState,
		@Nonnull Consumer<T> preSavepointMutation,
		@Nonnull Consumer<T> savepointMutation,
		@Nonnull BiPredicate<R, R> stateEquals,
		@Nonnull Function<S, R> readCommittedState
	) {
		assertCommittedState(structure, maintainer -> {
			preSavepointMutation.accept(structure);
			final R before = readState.apply(structure);

			final Savepoint savepoint = maintainer.openSavepoint();
			savepointMutation.accept(structure);
			final R during = readState.apply(structure);
			assertFalse(
				stateEquals.test(before, during),
				"Savepoint mutation should have changed the visible state - the test would be vacuous otherwise."
			);

			maintainer.rollbackSavepoint(savepoint);
			final R after = readState.apply(structure);
			assertTrue(
				stateEquals.test(before, after),
				"rollbackSavepoint must restore the exact pre-savepoint state."
			);
			return before;
		}, (expected, committed) -> stateEquals.test(expected, readCommittedState.apply(committed)));
	}

	/**
	 * Asserts that a structure first mutated *inside* the savepoint (so its diff layer is created within the
	 * savepoint) is fully reverted to its baseline on rollback, and that its layer is removed afterwards.
	 *
	 * @param structure the structure under test
	 * @param readState captures the visible state
	 * @param savepointMutation mutates the structure while the savepoint is open
	 * @param stateEquals compares snapshots for exact equality
	 */
	private static <S, X, T extends TransactionalLayerProducer<X, S>> void assertSavepointRemovesCreatedLayer(
		@Nonnull T structure,
		@Nonnull Function<T, S> readState,
		@Nonnull Consumer<T> savepointMutation,
		@Nonnull BiPredicate<S, S> stateEquals
	) {
		assertCommittedState(structure, maintainer -> {
			// no layer exists yet - this captures the untouched baseline
			final S baseline = readState.apply(structure);
			assertNull(
				maintainer.getTransactionalMemoryLayerIfExists(structure),
				"No diff layer should exist before the structure is first mutated."
			);

			final Savepoint savepoint = maintainer.openSavepoint();
			savepointMutation.accept(structure);
			final S during = readState.apply(structure);
			assertFalse(
				stateEquals.test(baseline, during),
				"Savepoint mutation should have changed the visible state."
			);

			maintainer.rollbackSavepoint(savepoint);
			final S after = readState.apply(structure);
			assertTrue(
				stateEquals.test(baseline, after),
				"rollbackSavepoint must revert a layer created inside the savepoint to the baseline."
			);
			assertNull(
				maintainer.getTransactionalMemoryLayerIfExists(structure),
				"The diff layer created inside the savepoint must be removed on rollback."
			);
			return baseline;
		}, stateEquals);
	}

	/**
	 * Asserts that {@code commitSavepoint} keeps the changes made while the savepoint was open.
	 *
	 * @param structure the structure under test
	 * @param readState captures the visible state
	 * @param preSavepointMutation establishes the visible state before the savepoint
	 * @param savepointMutation mutates the structure while the savepoint is open
	 * @param stateEquals compares snapshots for exact equality
	 */
	private static <S, T extends TransactionalLayerProducer<?, S>> void assertCommitSavepointKeepsChanges(
		@Nonnull T structure,
		@Nonnull Function<T, S> readState,
		@Nonnull Consumer<T> preSavepointMutation,
		@Nonnull Consumer<T> savepointMutation,
		@Nonnull BiPredicate<S, S> stateEquals
	) {
		assertCommittedState(structure, maintainer -> {
			preSavepointMutation.accept(structure);
			final S before = readState.apply(structure);

			final Savepoint savepoint = maintainer.openSavepoint();
			savepointMutation.accept(structure);
			final S during = readState.apply(structure);
			assertFalse(
				stateEquals.test(before, during),
				"Savepoint mutation should have changed the visible state."
			);

			maintainer.commitSavepoint(savepoint);
			final S after = readState.apply(structure);
			assertTrue(
				stateEquals.test(during, after),
				"commitSavepoint must keep the changes made while the savepoint was open."
			);
			return during;
		}, stateEquals);
	}

	/**
	 * Runs the test body inside a transaction, then compares the committed result with the expected state
	 * returned by the body.
	 *
	 * @param structure the structure to commit
	 * @param body checks the live transaction and returns the expected committed state
	 * @param stateEquals compares the expected state with the committed result
	 */
	private static <S, R> void assertCommittedState(
		@Nonnull TransactionalStateProducer<S> structure,
		@Nonnull Function<TransactionalLayerMaintainer, R> body,
		@Nonnull BiPredicate<R, S> stateEquals
	) {
		final AtomicReference<R> expected = new AtomicReference<>();
		assertStateAfterCommit(
			structure,
			it -> expected.set(body.apply(Transaction.getTransactionalLayerMaintainer())),
			(it, committed) -> assertTrue(
				stateEquals.test(expected.get(), committed),
				"The transaction must commit the expected state."
			)
		);
	}

}
