/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.utils;

import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.TransactionHandler;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer.Savepoint;
import io.evitadb.core.transaction.memory.TransactionalStateProducer;
import io.evitadb.core.transaction.memory.WarmUpSavepoint;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.PrimitiveIterator.OfInt;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Class contains shared assertions across functional tests.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AssertionUtils {

	/**
	 * Compares computation result of the formula with the expected contents and reports failure when the contents
	 * differ from expected ones.
	 */
	public static void assertFormulaResultsIn(Formula formula, int[] expectedContents) {
		assertIteratorContains(formula.compute().iterator(), expectedContents);
	}

	/**
	 * Compares iterator contents with the expected contents and reports failure when the contents
	 * differ from expected ones.
	 */
	public static <T> void assertIteratorContains(Iterator<T> it, T[] expectedContents) {
		int index = -1;
		while (it.hasNext()) {
			final T nextObj = it.next();
			assertTrue(expectedContents.length > index + 1);
			assertEquals(expectedContents[++index], nextObj);
		}
		assertEquals(
			expectedContents.length, index + 1,
			"There are more expected objects than int array produced by iterator!"
		);
	}

	/**
	 * Compares iterator contents with the expected contents and reports failure when the contents
	 * differ from expected ones.
	 */
	public static void assertIteratorContains(OfInt it, int[] expectedContents) {
		int index = -1;
		while (it.hasNext()) {
			final int nextInt = it.next();
			assertTrue(expectedContents.length > index + 1);
			assertEquals(expectedContents[++index], nextInt);
		}
		assertEquals(
			expectedContents.length, index + 1,
			"There are more expected objects than int array produced by iterator!"
		);
	}

	/**
	 * This method executes operation in lambda `doInTransaction` on `tested` instance and verifies the results visible
	 * after transactional memory is committed in `verifyAfterCommit` lambda.
	 */
	public static <S, T extends TransactionalStateProducer<S>> void assertStateAfterCommit(
		@Nonnull T tested,
		@Nonnull Consumer<T> doInTransaction,
		@Nonnull BiConsumer<T, S> verifyAfterCommit
	) {
		final TestTransactionHandler<S, T> transactionHandler = new TestTransactionHandler<>(tested);
		Transaction.executeInTransactionIfProvided(
			new Transaction(
				UUID.randomUUID(),
				transactionHandler,
				false
			),
			() -> {
				final Transaction transaction = Transaction.getTransaction().orElseThrow();
				try {
					doInTransaction.accept(tested);
				} catch (Throwable ex) {
					transaction.setRollbackOnly();
					throw ex;
				} finally {
					transaction.close();
				}
			}
		);

		verifyAfterCommit.accept(tested, transactionHandler.getCommitted());
	}

	/**
	 * This method executes operation in lambda `doInTransaction` on `tested` instance and verifies the results visible
	 * after transactional memory is committed in `verifyAfterCommit` lambda.
	 */
	public static <S, T extends TransactionalStateProducer<S>> void assertStateAfterCommit(
		@Nonnull List<T> tested,
		@Nonnull Consumer<List<T>> doInTransaction,
		@Nonnull BiConsumer<List<T>, List<S>> verifyAfterCommit
	) {
		final TestTransactionHandlerWithMultipleValues<S, T> transactionHandler = new TestTransactionHandlerWithMultipleValues<>(tested);
		Transaction.executeInTransactionIfProvided(
			new Transaction(
				UUID.randomUUID(),
				transactionHandler,
				false
			),
			() -> {
				final Transaction transaction = Transaction.getTransaction().orElseThrow();
				try {
					doInTransaction.accept(tested);
				} catch (Throwable ex) {
					transaction.setRollbackOnly();
					throw ex;
				} finally {
					transaction.close();
				}
			}
		);

		verifyAfterCommit.accept(tested, transactionHandler.getCommitted());
	}

	/**
	 * This method executes operation in lambda `doInTransaction` on `tested` instance and verifies the results visible
	 * after transactional memory is rollbacked in `verifyAfterRollback` lambda.
	 */
	public static <S, T extends TransactionalStateProducer<S>> void assertStateAfterRollback(
		@Nonnull T tested,
		@Nonnull Consumer<T> doInTransaction,
		@Nonnull BiConsumer<T, S> verifyAfterRollback
	) {
		final TestTransactionHandler<S, T> transactionHandler = new TestTransactionHandler<>(tested);
		Transaction.executeInTransactionIfProvided(
			new Transaction(
				UUID.randomUUID(),
				transactionHandler,
				false
			),
			() -> {
				final Transaction transaction = Transaction.getTransaction().orElseThrow();

				doInTransaction.accept(tested);

				transaction.setRollbackOnly();
				transaction.close();
			}
		);

		verifyAfterRollback.accept(tested, transactionHandler.getCommitted());
	}

	/**
	 * This method executes operation in lambda `doInTransaction` on `tested` instance and verifies the results visible
	 * after transactional memory is rollbacked in `verifyAfterRollback` lambda.
	 */
	public static <S, T extends TransactionalStateProducer<S>> void assertStateAfterRollback(
		@Nonnull List<T> tested,
		@Nonnull Consumer<List<T>> doInTransaction,
		@Nonnull BiConsumer<List<T>, List<S>> verifyAfterRollback
	) {
		final TestTransactionHandlerWithMultipleValues<S, T> transactionHandler = new TestTransactionHandlerWithMultipleValues<>(tested);
		Transaction.executeInTransactionIfProvided(
			new Transaction(
				UUID.randomUUID(),
				transactionHandler,
				false
			),
			() -> {
				final Transaction transaction = Transaction.getTransaction().orElseThrow();

				doInTransaction.accept(tested);

				transaction.setRollbackOnly();
				transaction.close();
			}
		);

		verifyAfterRollback.accept(tested, transactionHandler.getCommitted());
	}

	/**
	 * Per-entity savepoint **rollback fidelity** assertion. Runs inside a real transaction:
	 *
	 * 1. `baselineOps` mutate `tested` — these stand for changes of *prior* entities in the same transaction and
	 *    must SURVIVE the savepoint rollback;
	 * 2. the logical content is captured via `oracleReader` (an `.equals`-comparable reference value);
	 * 3. a savepoint is opened, `savepointOps` mutate `tested` — these stand for the *failing* entity and must be
	 *    REVERTED;
	 * 4. `rollbackSavepoint` runs and the content is asserted equal to the captured reference — exactly;
	 * 5. the transaction then commits normally, so the commit-time layer-sweep verification
	 *    ({@link TransactionalLayerMaintainer#verifyLayerWasFullySwept()}) proves the restore left no dangling or
	 *    stale layer (post-rollback usability).
	 *
	 * `openSavepoint` is intentionally called *after* `baselineOps` so the savepoint's snapshot-on-first-touch
	 * captures the post-baseline diff — the state the rollback must return to.
	 *
	 * @param tested       the transactional structure under test
	 * @param baselineOps  mutations applied before the savepoint (must survive)
	 * @param oracleReader reads the structure's logical content into an `.equals`-comparable reference value
	 * @param savepointOps mutations applied while the savepoint is open (must be reverted)
	 */
	public static <S, T extends TransactionalStateProducer<S>, R> void assertSavepointRollbackRestores(
		@Nonnull T tested,
		@Nonnull Consumer<T> baselineOps,
		@Nonnull Function<T, R> oracleReader,
		@Nonnull Consumer<T> savepointOps
	) {
		final TestTransactionHandler<S, T> transactionHandler = new TestTransactionHandler<>(tested);
		Transaction.executeInTransactionIfProvided(
			new Transaction(UUID.randomUUID(), transactionHandler, false),
			() -> {
				final Transaction transaction = Transaction.getTransaction().orElseThrow();
				try {
					final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
					baselineOps.accept(tested);
					final R expected = oracleReader.apply(tested);
					final Savepoint savepoint = maintainer.openSavepoint();
					savepointOps.accept(tested);
					maintainer.rollbackSavepoint(savepoint);
					assertEquals(
						expected, oracleReader.apply(tested),
						"Savepoint rollback must restore the exact pre-savepoint logical state!"
					);
				} catch (Throwable ex) {
					transaction.setRollbackOnly();
					throw ex;
				} finally {
					transaction.close();
				}
			}
		);
	}

	/**
	 * Per-entity savepoint **commit fidelity** assertion. Mirrors
	 * {@link #assertSavepointRollbackRestores} but commits the savepoint instead of rolling it back: the content
	 * captured *after* `savepointOps` must remain unchanged once `commitSavepoint` runs (committing a savepoint only
	 * drops bookkeeping; no diff layer is modified).
	 *
	 * @param tested       the transactional structure under test
	 * @param baselineOps  mutations applied before the savepoint
	 * @param oracleReader reads the structure's logical content into an `.equals`-comparable reference value
	 * @param savepointOps mutations applied while the savepoint is open (must be kept)
	 */
	public static <S, T extends TransactionalStateProducer<S>, R> void assertSavepointCommitKeeps(
		@Nonnull T tested,
		@Nonnull Consumer<T> baselineOps,
		@Nonnull Function<T, R> oracleReader,
		@Nonnull Consumer<T> savepointOps
	) {
		final TestTransactionHandler<S, T> transactionHandler = new TestTransactionHandler<>(tested);
		Transaction.executeInTransactionIfProvided(
			new Transaction(UUID.randomUUID(), transactionHandler, false),
			() -> {
				final Transaction transaction = Transaction.getTransaction().orElseThrow();
				try {
					final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
					baselineOps.accept(tested);
					final Savepoint savepoint = maintainer.openSavepoint();
					savepointOps.accept(tested);
					final R expected = oracleReader.apply(tested);
					maintainer.commitSavepoint(savepoint);
					assertEquals(
						expected, oracleReader.apply(tested),
						"Savepoint commit must keep all changes made while it was open!"
					);
				} catch (Throwable ex) {
					transaction.setRollbackOnly();
					throw ex;
				} finally {
					transaction.close();
				}
			}
		);
	}

	/**
	 * WARM_UP counterpart of {@link #assertSavepointRollbackRestores}: the same rollback-fidelity assertion for a
	 * structure being written NON-transactionally, where the writes go straight to the delegate and a
	 * {@link WarmUpSavepoint} — not a {@link TransactionalLayerMaintainer} — is what has to rewind them.
	 *
	 * There is no transaction, so the type bound is dropped (a warm-up participant need not be a
	 * {@link TransactionalStateProducer}) and there is no commit-time layer sweep to verify — the delegate IS the
	 * committed state. Everything else is the same four-step shape: baseline mutations that must survive, an oracle
	 * reading, in-savepoint mutations that must be reverted, and an exact comparison after the rollback.
	 *
	 * @param tested       the structure under test
	 * @param baselineOps  mutations applied before the savepoint (must survive)
	 * @param oracleReader reads the structure's logical content into an `.equals`-comparable reference value
	 * @param savepointOps mutations applied while the savepoint is open (must be reverted)
	 */
	public static <T, R> void assertWarmUpSavepointRollbackRestores(
		@Nonnull T tested,
		@Nonnull Consumer<T> baselineOps,
		@Nonnull Function<T, R> oracleReader,
		@Nonnull Consumer<T> savepointOps
	) {
		baselineOps.accept(tested);
		final R expected = oracleReader.apply(tested);
		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		boolean closed = false;
		try {
			savepointOps.accept(tested);
			savepoint.rollback();
			closed = true;
		} finally {
			// a savepoint is thread-bound, so one leaked by a failing batch would break every later assertion in this
			// fork with a bogus "already open" error rather than the real failure
			if (!closed && WarmUpSavepoint.getIfOpen() == savepoint) {
				savepoint.commit();
			}
		}
		assertEquals(
			expected, oracleReader.apply(tested),
			"Warm-up savepoint rollback must restore the exact pre-savepoint logical state!"
		);
	}

	/**
	 * WARM_UP counterpart of {@link #assertSavepointCommitKeeps}: the content captured *after* `savepointOps` must
	 * remain unchanged once the {@link WarmUpSavepoint} is committed, since committing only discards the recorded
	 * inverses and touches no state.
	 *
	 * @param tested       the structure under test
	 * @param baselineOps  mutations applied before the savepoint
	 * @param oracleReader reads the structure's logical content into an `.equals`-comparable reference value
	 * @param savepointOps mutations applied while the savepoint is open (must be kept)
	 */
	public static <T, R> void assertWarmUpSavepointCommitKeeps(
		@Nonnull T tested,
		@Nonnull Consumer<T> baselineOps,
		@Nonnull Function<T, R> oracleReader,
		@Nonnull Consumer<T> savepointOps
	) {
		baselineOps.accept(tested);
		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		final R expected;
		boolean closed = false;
		try {
			savepointOps.accept(tested);
			expected = oracleReader.apply(tested);
			savepoint.commit();
			closed = true;
		} finally {
			if (!closed && WarmUpSavepoint.getIfOpen() == savepoint) {
				savepoint.commit();
			}
		}
		assertEquals(
			expected, oracleReader.apply(tested),
			"Warm-up savepoint commit must keep all changes made while it was open!"
		);
	}

	/**
	 * Verifies that `originalEntities` filtered by `predicate` match exactly contents of the `resultToVerify`.
	 */
	public static void assertResultIs(@Nonnull List<SealedEntity> originalEntities, @Nonnull Predicate<SealedEntity> predicate, @Nonnull List<EntityReference> resultToVerify) {
		assertResultIs(null, originalEntities, predicate, resultToVerify);
	}

	/**
	 * Verifies that `originalEntities` filtered by `predicate` match exactly contents of the `resultToVerify`.
	 */
	public static void assertResultIs(@Nullable String message, @Nonnull List<SealedEntity> originalEntities, @Nonnull Predicate<SealedEntity> predicate, @Nonnull List<EntityReference> resultToVerify) {
		@SuppressWarnings("ConstantConditions") final int[] expectedResult = originalEntities.stream().filter(predicate).mapToInt(EntityContract::getPrimaryKey).toArray();
		assertFalse(ArrayUtils.isEmpty(expectedResult), "Expected result should never be empty - this would cause false positive tests!");
		assertResultEquals(
			message,
			resultToVerify,
			expectedResult
		);
	}

	/**
	 * Verifies that `record` primary keys exactly match passed `reference` ids. Both lists are sorted naturally before
	 * the comparison is executed.
	 */
	public static void assertResultEquals(@Nullable String message, @Nonnull List<EntityReference> records, @Nonnull int... reference) {
		final List<Integer> recordsCopy = records.stream().map(EntityReference::getPrimaryKey).sorted().collect(Collectors.toList());
		Arrays.sort(reference);

		assertSortedResultEquals(message, recordsCopy, reference);
	}

	/**
	 * Verifies that `record` primary keys exactly match passed `reference` ids in the exactly same ordering.
	 */
	public static void assertSortedResultEquals(@Nullable String message, @Nonnull List<Integer> records, @Nonnull int... reference) {
		final Set<Integer> expectedRecords = new HashSet<>(records);
		final Set<Integer> foundRecords = Arrays.stream(reference).boxed().collect(Collectors.toSet());
		final Set<Integer> difference = foundRecords.size() > expectedRecords.size() ? new HashSet<>(foundRecords) : new HashSet<>(expectedRecords);
		difference.removeAll(foundRecords.size() > expectedRecords.size() ? expectedRecords : foundRecords);
		assertEquals(
			reference.length, records.size(),
			ofNullable(message).map(it -> it + "\n").orElse("") +
				"\nExpected ids: " + Arrays.stream(reference).mapToObj(String::valueOf).collect(Collectors.joining(", ")) + "\n" +
				"  Actual ids: " + records.stream().map(String::valueOf).collect(Collectors.joining(", ")) + "\n" +
				"Unsorted arrays: " + (expectedRecords.equals(foundRecords) ? "match" : "differ " + difference.stream().map(Object::toString).collect(Collectors.joining(", ")))
		);
		for (int i = 0; i < reference.length; i++) {
			assertEquals(
				reference[i], records.get(i),
				ofNullable(message).map(it -> it + "\n").orElse("") +
					"\nExpected ids: " + Arrays.stream(reference).mapToObj(String::valueOf).collect(Collectors.joining(", ")) + "\n" +
					"  Actual ids: " + records.stream().map(String::valueOf).collect(Collectors.joining(", ")) + "\n" +
					"Unsorted arrays: " + (expectedRecords.equals(foundRecords) ? "match" : "differ " + difference.stream().map(Object::toString).collect(Collectors.joining(", ")))
			);
		}
	}

	/**
	 * Verifies that `record` primary keys exactly match passed `reference` ids in the exactly same ordering.
	 */
	public static void assertSortedResultEquals(@Nonnull List<Integer> records, @Nonnull int... reference) {
		assertSortedResultEquals(null, records, reference);
	}

	/**
	 * Verifies whether the passed arrays differ one from another. Arrays are expected to be of the same size.
	 */
	public static void assertArrayAreDifferent(@Nonnull int[] arrayA, @Nonnull int[] arrayB) {
		assertEquals(arrayA.length, arrayB.length, "Both arrays should have same size.");
		for (int i = 0; i < arrayA.length; i++) {
			int i1 = arrayA[i];
			int i2 = arrayB[i];
			if (i1 != i2) {
				return;
			}
		}
		fail("Arrays are exactly the same!");
	}

	/**
	 * TestTransactionHandlerWithMultipleValues handles the transaction commit / rollback actions in the tests.
	 * It simply creates instance of the tested item in case of commit and provides access to it in `committed` field.
	 *
	 * @param <S> the type of the state
	 * @param <T> a subtype of {@link TransactionalStateProducer}
	 */
	private static class TestTransactionHandler<S, T extends TransactionalStateProducer<S>> implements TransactionHandler {
		private final T tested;
		private S committed;

		public TestTransactionHandler(@Nonnull T tested) {
			this.tested = tested;
		}

		public S getCommitted() {
			return this.committed;
		}

		@Override
		public void registerMutation(@Nonnull Mutation mutation) {
			// do nothing
		}

		@Override
		public void commit(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.committed = transactionalLayer.getStateCopyWithCommittedChanges(this.tested);
			transactionalLayer.verifyLayerWasFullySwept();
		}

		@Override
		public void rollback(@Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Throwable cause) {
			// do nothing
		}
	}

	/**
	 * TestTransactionHandlerWithMultipleValues handles the transaction commit / rollback actions in the tests.
	 * It simply iterates over the list of tested items and creates new instances of them in case of commit.
	 * New versions of those items are provided in `committed` field.
	 *
	 * @param <S> the type of state
	 * @param <T> a transactional state producer that extends TransactionalStateProducer<S>
	 */
	private static class TestTransactionHandlerWithMultipleValues<S, T extends TransactionalStateProducer<S>> implements TransactionHandler {
		private final List<T> tested;
		private List<S> committed;

		public TestTransactionHandlerWithMultipleValues(@Nonnull List<T> tested) {
			this.tested = tested;
		}

		public List<S> getCommitted() {
			return this.committed;
		}

		@Override
		public void registerMutation(@Nonnull Mutation mutation) {
			// do nothing
		}

		@Override
		public void commit(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.committed = new ArrayList<>(this.tested.size());
			for (T testedItem : this.tested) {
				this.committed.add(transactionalLayer.getStateCopyWithCommittedChanges(testedItem));
			}
			transactionalLayer.verifyLayerWasFullySwept();
		}

		@Override
		public void rollback(@Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Throwable cause) {
			this.committed = new ArrayList<>(this.tested.size());
			for (int i = 0; i < this.tested.size(); i++) {
				this.committed.add(null);
			}
		}
	}

}
