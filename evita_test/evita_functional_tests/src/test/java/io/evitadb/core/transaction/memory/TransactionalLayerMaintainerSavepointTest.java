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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
