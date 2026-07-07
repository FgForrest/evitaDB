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

import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.TransactionHandler;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer.Savepoint;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.index.bool.TransactionalBoolean;
import io.evitadb.index.list.TransactionalList;
import io.evitadb.index.map.TransactionalMap;
import io.evitadb.index.reference.TransactionalReference;
import io.evitadb.index.set.TransactionalSet;
import io.evitadb.index.array.TransactionalIntArray;
import io.evitadb.index.array.TransactionalObjArray;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the savepoint mechanism on {@link TransactionalLayerMaintainer} together with the {@link Snapshotable}
 * implementations on the transactional diff layers. Each test opens a savepoint inside a live transaction, mutates a
 * real transactional structure, and asserts that {@link TransactionalLayerMaintainer#rollbackSavepoint(Savepoint)}
 * restores the exact pre-savepoint state (while the surrounding transaction keeps running) — the per-entity partial
 * rollback capability.
 *
 * The assertions are state-based: the merged committed copy obtained via
 * {@link TransactionalLayerMaintainer#getStateCopyWithCommittedChangesWithoutDiscardingState} is captured before the
 * savepoint, after the savepoint mutation, and after the rollback, proving the rollback returns the structure to its
 * pre-savepoint state.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(ENGINE)
@Tag(TRANSACTION)
@DisplayName("Transactional layer maintainer savepoint (per-entity rollback)")
class TransactionalLayerMaintainerSavepointTest {

	@Test
	@DisplayName("TransactionalMap: rollbackSavepoint reverts puts/removes made in the savepoint")
	void shouldRollbackSavepointForMap() {
		final Map<String, Integer> delegate = new HashMap<>();
		delegate.put("a", 1);
		final TransactionalMap<String, Integer> map = new TransactionalMap<>(delegate);
		assertSavepointRollbackRestores(
			map,
			it -> {
				it.put("b", 2);
			},
			it -> {
				it.put("c", 3);
				it.remove("a");
				it.put("b", 20);
			},
			Objects::equals
		);
	}

	@Test
	@DisplayName("TransactionalMap: a layer created inside the savepoint is removed on rollback")
	void shouldRemoveCreatedMapLayerOnRollback() {
		final Map<String, Integer> delegate = new HashMap<>();
		delegate.put("a", 1);
		final TransactionalMap<String, Integer> map = new TransactionalMap<>(delegate);
		assertSavepointRemovesCreatedLayer(
			map,
			it -> {
				it.put("b", 2);
				it.remove("a");
			},
			Objects::equals
		);
	}

	@Test
	@DisplayName("TransactionalMap: commitSavepoint keeps the savepoint changes")
	void shouldKeepChangesOnCommitSavepointForMap() {
		final Map<String, Integer> delegate = new HashMap<>();
		delegate.put("a", 1);
		final TransactionalMap<String, Integer> map = new TransactionalMap<>(delegate);
		assertCommitSavepointKeepsChanges(
			map,
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

	@Test
	@DisplayName("TransactionalSet: rollbackSavepoint reverts adds/removes made in the savepoint")
	void shouldRollbackSavepointForSet() {
		final Set<String> delegate = new HashSet<>();
		delegate.add("a");
		final TransactionalSet<String> set = new TransactionalSet<>(delegate);
		assertSavepointRollbackRestores(
			set,
			it -> it.add("b"),
			it -> {
				it.add("c");
				it.remove("a");
			},
			Objects::equals
		);
	}

	@Test
	@DisplayName("TransactionalList: rollbackSavepoint reverts adds/removes made in the savepoint")
	void shouldRollbackSavepointForList() {
		final List<String> delegate = new ArrayList<>();
		delegate.add("a");
		final TransactionalList<String> list = new TransactionalList<>(delegate);
		assertSavepointRollbackRestores(
			list,
			it -> it.add("b"),
			it -> {
				it.add("c");
				it.remove("a");
			},
			Objects::equals
		);
	}

	@Test
	@DisplayName("TransactionalBitmap: rollbackSavepoint reverts adds/removes made in the savepoint")
	void shouldRollbackSavepointForBitmap() {
		final TransactionalBitmap bitmap = new TransactionalBitmap(1, 2, 3);
		assertSavepointRollbackRestores(
			bitmap,
			it -> it.add(10),
			it -> {
				it.add(20);
				it.remove(1);
			},
			(a, b) -> Arrays.equals(a.getArray(), b.getArray())
		);
	}

	@Test
	@DisplayName("TransactionalBoolean: rollbackSavepoint reverts the value flipped in the savepoint")
	void shouldRollbackSavepointForBoolean() {
		final TransactionalBoolean bool = new TransactionalBoolean(false);
		assertSavepointRollbackRestores(
			bool,
			TransactionalBoolean::setToTrue,
			TransactionalBoolean::setToFalse,
			Objects::equals
		);
	}

	@Test
	@DisplayName("TransactionalReference: rollbackSavepoint reverts the reference set in the savepoint")
	void shouldRollbackSavepointForReference() {
		final TransactionalReference<String> reference = new TransactionalReference<>("a");
		assertSavepointRollbackRestores(
			reference,
			it -> it.set("b"),
			it -> it.set("c"),
			Objects::equals
		);
	}

	@Test
	@DisplayName("TransactionalIntArray: rollbackSavepoint reverts adds/removes made in the savepoint")
	void shouldRollbackSavepointForIntArray() {
		final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 2, 3});
		assertSavepointRollbackRestores(
			array,
			it -> it.add(10),
			it -> {
				it.add(20);
				it.remove(1);
			},
			Arrays::equals
		);
	}

	@Test
	@DisplayName("TransactionalObjArray: rollbackSavepoint reverts adds/removes made in the savepoint")
	void shouldRollbackSavepointForObjArray() {
		final TransactionalObjArray<String> array = new TransactionalObjArray<>(
			new String[]{"a", "c", "e"}, Comparator.naturalOrder()
		);
		assertSavepointRollbackRestores(
			array,
			it -> it.add("g"),
			it -> {
				it.add("b");
				it.add("d");
				it.remove("c");
			},
			Arrays::equals
		);
	}

	@Test
	@DisplayName("TransactionalObjArray: a layer created inside the savepoint is removed on rollback")
	void shouldRemoveCreatedObjArrayLayerOnRollback() {
		final TransactionalObjArray<String> array = new TransactionalObjArray<>(
			new String[]{"a", "c", "e"}, Comparator.naturalOrder()
		);
		assertSavepointRemovesCreatedLayer(
			array,
			it -> {
				it.add("b");
				it.remove("c");
			},
			Arrays::equals
		);
	}

	@Test
	@DisplayName("TransactionalSet: an undo-journal memento can be restored repeatedly and stays faithful")
	void shouldSupportRepeatRestoreForSet() {
		final Set<String> delegate = new HashSet<>();
		delegate.add("a");
		final TransactionalSet<String> set = new TransactionalSet<>(delegate);
		assertRepeatRestoreSafe(
			set,
			it -> it.add("b"),
			it -> {
				it.add("c");
				it.remove("a");
				it.add("d");
			},
			Objects::equals
		);
	}

	@Test
	@DisplayName("TransactionalMap: an undo-journal memento can be restored repeatedly and stays faithful")
	void shouldSupportRepeatRestoreForMap() {
		final Map<String, Integer> delegate = new HashMap<>();
		delegate.put("a", 1);
		final TransactionalMap<String, Integer> map = new TransactionalMap<>(delegate);
		assertRepeatRestoreSafe(
			map,
			it -> it.put("b", 2),
			it -> {
				it.put("c", 3);
				it.remove("a");
				it.put("b", 20);
			},
			Objects::equals
		);
	}

	@Test
	@DisplayName("TransactionalList: an undo-journal memento can be restored repeatedly and stays faithful")
	void shouldSupportRepeatRestoreForList() {
		final List<String> delegate = new ArrayList<>();
		delegate.add("a");
		final TransactionalList<String> list = new TransactionalList<>(delegate);
		assertRepeatRestoreSafe(
			list,
			it -> it.add(1, "b"),
			it -> {
				it.add(2, "c");
				it.remove("a");
			},
			Objects::equals
		);
	}

	@Test
	@DisplayName("IntArrayChanges: a memento can be restored repeatedly and stays faithful (no aliasing)")
	void shouldSupportRepeatRestoreForIntArray() {
		// 50 and 60 both sort into the same gap (between 1 and 100), so the second add reuses the existing
		// insertion bucket via an in-place outer-slot write — the exact path that would corrupt an aliased memento
		final TransactionalIntArray array = new TransactionalIntArray(new int[]{1, 100});
		assertRepeatRestoreSafe(
			array,
			it -> it.add(50),
			it -> it.add(60),
			Arrays::equals
		);
	}

	@Test
	@DisplayName("TransactionalSet: rollbackSavepoint reverts a removeAll of a created key made in the savepoint")
	void shouldRollbackSavepointForSetRemoveAllOfCreatedKey() {
		final Set<String> delegate = new HashSet<>();
		delegate.add("a");
		final TransactionalSet<String> set = new TransactionalSet<>(delegate);
		assertSavepointRollbackRestores(
			set,
			it -> it.add("b"),
			// removeAll drives the merged iterator's remove(), which drops the created key straight from the diff
			// layer's created-keys set — the rollback must reinstate it exactly like a plain remove("b") would
			it -> it.removeAll(Set.of("b")),
			Objects::equals
		);
	}

	@Test
	@DisplayName("TransactionalSet: iterator removal as the first savepoint touch is reverted on rollback")
	void shouldRollbackSavepointForSetIteratorRemoveAsFirstSavepointTouch() {
		final Set<String> delegate = new HashSet<>();
		delegate.add("a");
		final TransactionalSet<String> set = new TransactionalSet<>(delegate);
		runInTransaction(() -> {
			final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
			// the diff layer exists before the savepoint opens
			set.add("b");
			final Set<String> before = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(set);

			final Savepoint savepoint = maintainer.openSavepoint();
			// the FIRST (and only) layer touch inside the savepoint is a removal through the merged iterator
			final Iterator<String> it = set.iterator();
			while (it.hasNext()) {
				if ("a".equals(it.next())) {
					it.remove();
					break;
				}
			}
			final Set<String> during = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(set);
			assertNotEquals(
				before, during,
				"Savepoint mutation should have changed the visible state - the test would be vacuous otherwise."
			);

			maintainer.rollbackSavepoint(savepoint);
			final Set<String> after = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(set);
			assertEquals(
				before, after,
				"a removal made solely through the merged iterator must be reverted by rollbackSavepoint"
			);
		});
	}

	@Test
	@DisplayName("TransactionalMap: a removal through an entry iterator obtained before the savepoint is reverted")
	void shouldRollbackSavepointForMapIteratorRemoveWhenIteratorPredatesSavepoint() {
		final Map<String, Integer> delegate = new HashMap<>();
		delegate.put("a", 1);
		final TransactionalMap<String, Integer> map = new TransactionalMap<>(delegate);
		runInTransaction(() -> {
			final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
			map.put("x", 9);
			map.put("b", 2);
			final Map<String, Integer> before = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(map);

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
			final Map<String, Integer> during = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(map);
			assertNotEquals(
				before, during,
				"Savepoint mutation should have changed the visible state - the test would be vacuous otherwise."
			);

			maintainer.rollbackSavepoint(savepoint);
			final Map<String, Integer> after = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(map);
			assertEquals(
				before, after,
				"a removal made through a pre-savepoint entry iterator must be reverted by rollbackSavepoint"
			);
		});
	}

	@Test
	@DisplayName("TransactionalList: iterator removal as the first savepoint touch is reverted on rollback")
	void shouldRollbackSavepointForListIteratorRemoveAsFirstSavepointTouch() {
		final List<String> delegate = new ArrayList<>();
		delegate.add("a");
		final TransactionalList<String> list = new TransactionalList<>(delegate);
		runInTransaction(() -> {
			final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
			// the diff layer exists before the savepoint opens
			list.add("b");
			final List<String> before = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(list);

			final Savepoint savepoint = maintainer.openSavepoint();
			// the FIRST (and only) layer touch inside the savepoint is a removal through the merged list iterator
			final Iterator<String> it = list.iterator();
			while (it.hasNext()) {
				if ("a".equals(it.next())) {
					it.remove();
					break;
				}
			}
			final List<String> during = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(list);
			assertNotEquals(
				before, during,
				"Savepoint mutation should have changed the visible state - the test would be vacuous otherwise."
			);

			maintainer.rollbackSavepoint(savepoint);
			final List<String> after = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(list);
			assertEquals(
				before, after,
				"a removal made solely through the list iterator must be reverted by rollbackSavepoint"
			);
		});
	}

	@Test
	@DisplayName("TransactionalList: listIterator set as the first savepoint touch is reverted on rollback")
	void shouldRollbackSavepointForListIteratorSetAsFirstSavepointTouch() {
		final List<String> delegate = new ArrayList<>();
		delegate.add("a");
		final TransactionalList<String> list = new TransactionalList<>(delegate);
		runInTransaction(() -> {
			final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
			list.add("b");
			final List<String> before = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(list);

			final Savepoint savepoint = maintainer.openSavepoint();
			// the FIRST layer touch inside the savepoint is an in-place set through the list iterator
			final ListIterator<String> it = list.listIterator();
			it.next();
			it.set("z");
			final List<String> during = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(list);
			assertNotEquals(
				before, during,
				"Savepoint mutation should have changed the visible state - the test would be vacuous otherwise."
			);

			maintainer.rollbackSavepoint(savepoint);
			final List<String> after = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(list);
			assertEquals(
				before, after,
				"an in-place set made solely through the list iterator must be reverted by rollbackSavepoint"
			);
		});
	}

	@Test
	@DisplayName("TransactionalList: listIterator add as the first savepoint touch is reverted on rollback")
	void shouldRollbackSavepointForListIteratorAddAsFirstSavepointTouch() {
		final List<String> delegate = new ArrayList<>();
		delegate.add("a");
		final TransactionalList<String> list = new TransactionalList<>(delegate);
		runInTransaction(() -> {
			final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
			list.add("b");
			final List<String> before = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(list);

			final Savepoint savepoint = maintainer.openSavepoint();
			// the FIRST layer touch inside the savepoint is an insertion through the list iterator
			final ListIterator<String> it = list.listIterator();
			it.add("z");
			final List<String> during = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(list);
			assertNotEquals(
				before, during,
				"Savepoint mutation should have changed the visible state - the test would be vacuous otherwise."
			);

			maintainer.rollbackSavepoint(savepoint);
			final List<String> after = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(list);
			assertEquals(
				before, after,
				"an insertion made solely through the list iterator must be reverted by rollbackSavepoint"
			);
		});
	}

	@Test
	@DisplayName("TransactionalMap: entry setValue as the first savepoint touch is reverted on rollback")
	void shouldRollbackSavepointForMapEntrySetValueAsFirstSavepointTouch() {
		final Map<String, Integer> delegate = new HashMap<>();
		delegate.put("a", 1);
		final TransactionalMap<String, Integer> map = new TransactionalMap<>(delegate);
		runInTransaction(() -> {
			final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
			// the diff layer exists before the savepoint opens
			map.put("b", 2);
			final Map<String, Integer> before = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(map);

			final Savepoint savepoint = maintainer.openSavepoint();
			// the FIRST layer touch inside the savepoint overwrites an existing entry's value in place through the
			// entry-set view's setValue proxy
			for (final Entry<String, Integer> entry : map.entrySet()) {
				if ("a".equals(entry.getKey())) {
					entry.setValue(99);
					break;
				}
			}
			final Map<String, Integer> during = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(map);
			assertNotEquals(
				before, during,
				"Savepoint mutation should have changed the visible state - the test would be vacuous otherwise."
			);

			maintainer.rollbackSavepoint(savepoint);
			final Map<String, Integer> after = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(map);
			assertEquals(
				before, after,
				"an in-place entry.setValue must be reverted by rollbackSavepoint"
			);
		});
	}

	@Test
	@DisplayName("TransactionalMap: entry setValue on a created (layer) entry is reverted on rollback")
	void shouldRollbackSavepointForMapEntrySetValueOnCreatedEntry() {
		// empty delegate: the key put below lives only in the diff layer, so the entry-set iterator yields it as a raw
		// modifiedKeys entry (not a delegate wrapper) - the setValue path that mutates the layer map in place
		final TransactionalMap<String, Integer> map = new TransactionalMap<>(new HashMap<>());
		runInTransaction(() -> {
			final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
			// "a" is a created key held in the diff layer (modifiedKeys), not in the delegate
			map.put("a", 1);
			final Map<String, Integer> before = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(map);

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
			final Map<String, Integer> during = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(map);
			assertNotEquals(
				before, during,
				"Savepoint mutation should have changed the visible state - the test would be vacuous otherwise."
			);

			maintainer.rollbackSavepoint(savepoint);
			final Map<String, Integer> after = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(map);
			assertEquals(
				before, after,
				"an in-place setValue on a created (layer) entry must be reverted by rollbackSavepoint"
			);
		});
	}

	@Test
	@DisplayName("TransactionalMap: keySet().clear() as the first savepoint touch is reverted on rollback")
	void shouldRollbackSavepointForMapKeySetClearAsFirstSavepointTouch() {
		final Map<String, Integer> delegate = new HashMap<>();
		delegate.put("a", 1);
		final TransactionalMap<String, Integer> map = new TransactionalMap<>(delegate);
		runInTransaction(() -> {
			final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
			map.put("b", 2);
			final Map<String, Integer> before = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(map);

			final Savepoint savepoint = maintainer.openSavepoint();
			// the FIRST layer touch inside the savepoint clears the whole map through its key-set view, which forwards
			// to MapChanges#cleanAll without going through the maintainer's write-touch hook
			map.keySet().clear();
			final Map<String, Integer> during = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(map);
			assertNotEquals(
				before, during,
				"Savepoint mutation should have changed the visible state - the test would be vacuous otherwise."
			);

			maintainer.rollbackSavepoint(savepoint);
			final Map<String, Integer> after = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(map);
			assertEquals(
				before, after,
				"a clear() through the key-set view must be reverted by rollbackSavepoint"
			);
		});
	}

	/**
	 * Directly exercises the {@link Snapshotable} contract's repeat-restore invariant on a structure's diff layer:
	 * snapshot once, then twice over {perturb the layer; restore the same memento} must both return the structure to
	 * the snapshot state. This catches mementos that alias the live layer (so a post-restore mutation corrupts them).
	 */
	private static <S, X, T extends TransactionalLayerProducer<X, S>> void assertRepeatRestoreSafe(
		@Nonnull T structure,
		@Nonnull Consumer<T> createLayerMutation,
		@Nonnull Consumer<T> perturbation,
		@Nonnull BiPredicate<S, S> stateEquals
	) {
		runInTransaction(() -> {
			final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
			createLayerMutation.accept(structure);
			final Object layer = maintainer.getTransactionalMemoryLayerIfExists(structure);
			assertInstanceOf(Snapshotable.class, layer, "the diff layer must implement Snapshotable");
			@SuppressWarnings("unchecked") final Snapshotable<Object> snapshotable = (Snapshotable<Object>) layer;

			final Object memento = snapshotable.snapshot();
			final S captured = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(structure);

			for (int round = 0; round < 2; round++) {
				perturbation.accept(structure);
				snapshotable.restore(memento);
				final S after = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(structure);
				assertTrue(
					stateEquals.test(captured, after),
					"restore from the same memento must stay faithful on round " + round
				);
			}
		});
	}

	/**
	 * Opens a savepoint, applies `preSavepointMutation` before it and `savepointMutation` inside it, then asserts
	 * `rollbackSavepoint` returns the structure to the exact pre-savepoint merged state.
	 */
	private static <S, X, T extends TransactionalLayerProducer<X, S>> void assertSavepointRollbackRestores(
		@Nonnull T structure,
		@Nonnull Consumer<T> preSavepointMutation,
		@Nonnull Consumer<T> savepointMutation,
		@Nonnull BiPredicate<S, S> stateEquals
	) {
		runInTransaction(() -> {
			final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
			preSavepointMutation.accept(structure);
			final S before = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(structure);

			final Savepoint savepoint = maintainer.openSavepoint();
			savepointMutation.accept(structure);
			final S during = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(structure);
			assertFalse(
				stateEquals.test(before, during),
				"Savepoint mutation should have changed the visible state - the test would be vacuous otherwise."
			);

			maintainer.rollbackSavepoint(savepoint);
			final S after = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(structure);
			assertTrue(
				stateEquals.test(before, after),
				"rollbackSavepoint must restore the exact pre-savepoint state."
			);
		});
	}

	/**
	 * Asserts that a structure first mutated *inside* the savepoint (so its diff layer is created within the
	 * savepoint) is fully reverted to its baseline on rollback, and that its layer is removed afterwards.
	 */
	private static <S, X, T extends TransactionalLayerProducer<X, S>> void assertSavepointRemovesCreatedLayer(
		@Nonnull T structure,
		@Nonnull Consumer<T> savepointMutation,
		@Nonnull BiPredicate<S, S> stateEquals
	) {
		runInTransaction(() -> {
			final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
			// no layer exists yet - this captures the untouched baseline
			final S baseline = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(structure);
			assertNull(
				maintainer.getTransactionalMemoryLayerIfExists(structure),
				"No diff layer should exist before the structure is first mutated."
			);

			final Savepoint savepoint = maintainer.openSavepoint();
			savepointMutation.accept(structure);
			final S during = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(structure);
			assertFalse(
				stateEquals.test(baseline, during),
				"Savepoint mutation should have changed the visible state."
			);

			maintainer.rollbackSavepoint(savepoint);
			final S after = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(structure);
			assertTrue(
				stateEquals.test(baseline, after),
				"rollbackSavepoint must revert a layer created inside the savepoint to the baseline."
			);
			assertNull(
				maintainer.getTransactionalMemoryLayerIfExists(structure),
				"The diff layer created inside the savepoint must be removed on rollback."
			);
		});
	}

	/**
	 * Asserts that {@code commitSavepoint} keeps the changes made while the savepoint was open.
	 */
	private static <S, X, T extends TransactionalLayerProducer<X, S>> void assertCommitSavepointKeepsChanges(
		@Nonnull T structure,
		@Nonnull Consumer<T> preSavepointMutation,
		@Nonnull Consumer<T> savepointMutation,
		@Nonnull BiPredicate<S, S> stateEquals
	) {
		runInTransaction(() -> {
			final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
			preSavepointMutation.accept(structure);
			final S before = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(structure);

			final Savepoint savepoint = maintainer.openSavepoint();
			savepointMutation.accept(structure);
			maintainer.commitSavepoint(savepoint);

			final S after = maintainer.getStateCopyWithCommittedChangesWithoutDiscardingState(structure);
			assertFalse(
				stateEquals.test(before, after),
				"commitSavepoint must keep the changes made while the savepoint was open."
			);
		});
	}

	/**
	 * Runs the given body inside a fresh, thread-bound transaction that is always rolled back afterwards (the
	 * assertions are made within the transaction, so the commit / rollback outcome is irrelevant).
	 */
	private static void runInTransaction(@Nonnull Runnable body) {
		Transaction.executeInTransactionIfProvided(
			new Transaction(UUID.randomUUID(), new NoOpTransactionHandler(), false),
			() -> {
				final Transaction transaction = Transaction.getTransaction().orElseThrow();
				try {
					body.run();
				} finally {
					transaction.setRollbackOnly();
					transaction.close();
				}
			}
		);
	}

	/**
	 * Minimal {@link TransactionHandler} that performs no commit / rollback work — these tests assert savepoint
	 * behavior within the live transaction and never rely on the final commit / rollback outcome.
	 */
	private static class NoOpTransactionHandler implements TransactionHandler {

		@Override
		public void registerMutation(@Nonnull Mutation mutation) {
			// no-op
		}

		@Override
		public void commit(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			// no-op
		}

		@Override
		public void rollback(@Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Throwable cause) {
			// no-op
		}
	}

}
