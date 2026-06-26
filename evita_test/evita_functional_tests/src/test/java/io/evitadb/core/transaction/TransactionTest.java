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

package io.evitadb.core.transaction;

import io.evitadb.api.exception.TransactionException;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.exception.GenericEvitaInternalError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * This class verifies the {@link Transaction} contract - thread binding, the
 * {@link Transaction#executeInTransactionIfProvided} family of helpers, the {@link Transaction#close()} lifecycle
 * and rollback-cause propagation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(ENGINE)
@Tag(TRANSACTION)
@DisplayName("Transaction lifecycle and thread binding")
class TransactionTest {

	/**
	 * Removes any transaction that may have leaked into the current thread so that each test starts and ends with
	 * a clean {@link Transaction} thread-local.
	 */
	@AfterEach
	void tearDown() {
		Transaction.getTransaction().ifPresent(Transaction::unbindTransactionFromThread);
	}

	/**
	 * Creates a fresh {@link Transaction} backed by a {@link RecordingTransactionHandler} so that commit / rollback
	 * invocations can be asserted.
	 *
	 * @param handler the handler that records commit / rollback invocations
	 * @return the newly created transaction
	 */
	@Nonnull
	private static Transaction newTransaction(@Nonnull RecordingTransactionHandler handler) {
		return new Transaction(UUID.randomUUID(), handler, false);
	}

	@Nested
	@DisplayName("Thread binding")
	class ThreadBindingTest {

		@Test
		@DisplayName("binds transaction to thread and reports availability")
		void shouldBindTransactionToThreadAndReportAvailability() {
			final Transaction transaction = newTransaction(new RecordingTransactionHandler());

			assertFalse(Transaction.isTransactionAvailable());

			final boolean bound = transaction.bindTransactionToThread();

			assertTrue(bound);
			assertTrue(Transaction.isTransactionAvailable());
			assertSame(transaction, Transaction.getTransaction().orElseThrow());

			transaction.unbindTransactionFromThread();

			assertFalse(Transaction.isTransactionAvailable());
			assertTrue(Transaction.getTransaction().isEmpty());
		}

		@Test
		@DisplayName("returns false when the same transaction is bound twice")
		void shouldReturnFalseWhenBindingSameTransactionTwice() {
			final Transaction transaction = newTransaction(new RecordingTransactionHandler());

			final boolean firstBind = transaction.bindTransactionToThread();
			final boolean secondBind = transaction.bindTransactionToThread();

			assertTrue(firstBind);
			assertFalse(secondBind);
			assertSame(transaction, Transaction.getTransaction().orElseThrow());
		}

		@Test
		@DisplayName("rejects mixing different transactions on the same thread")
		void shouldRejectMixingDifferentTransactionsOnSameThread() {
			final Transaction transactionA = newTransaction(new RecordingTransactionHandler());
			final Transaction transactionB = newTransaction(new RecordingTransactionHandler());

			transactionA.bindTransactionToThread();

			final GenericEvitaInternalError ex = assertThrows(
				GenericEvitaInternalError.class,
				transactionB::bindTransactionToThread
			);
			assertTrue(ex.getPrivateMessage().contains("cannot mix calling different sessions"));
		}
	}

	@Nested
	@DisplayName("executeInTransactionIfProvided")
	class ExecuteInTransactionTest {

		@Test
		@DisplayName("runs the lambda directly when no transaction is provided")
		void shouldRunLambdaDirectlyWhenTransactionIsNull() {
			final boolean[] executed = {false};

			Transaction.executeInTransactionIfProvided(null, () -> executed[0] = true);

			assertTrue(executed[0]);
			assertFalse(Transaction.isTransactionAvailable());
		}

		@Test
		@DisplayName("binds and unbinds around a runnable")
		void shouldBindAndUnbindAroundRunnable() {
			final Transaction transaction = newTransaction(new RecordingTransactionHandler());
			final boolean[] availableInside = {false};

			Transaction.executeInTransactionIfProvided(
				transaction,
				() -> availableInside[0] = Transaction.isTransactionAvailable()
			);

			assertTrue(availableInside[0]);
			assertFalse(Transaction.isTransactionAvailable());
		}

		@Test
		@DisplayName("marks rollback-only when a runnable throws and the flag is enabled")
		void shouldMarkRollbackOnlyWhenRunnableThrowsAndFlagEnabled() {
			final Transaction transaction = newTransaction(new RecordingTransactionHandler());
			final RuntimeException cause = new RuntimeException("boom");

			final RuntimeException thrown = assertThrows(
				RuntimeException.class,
				() -> Transaction.executeInTransactionIfProvided(
					transaction,
					() -> {
						throw cause;
					},
					true
				)
			);

			assertSame(cause, thrown);
			assertTrue(transaction.isRollbackOnly());
			assertSame(cause, transaction.getRollbackCause());
			assertFalse(Transaction.isTransactionAvailable());
		}

		@Test
		@DisplayName("does not mark rollback-only when a runnable throws and the flag is disabled")
		void shouldNotMarkRollbackOnlyWhenRunnableThrowsAndFlagDisabled() {
			final Transaction transaction = newTransaction(new RecordingTransactionHandler());
			final RuntimeException cause = new RuntimeException("boom");

			final RuntimeException thrown = assertThrows(
				RuntimeException.class,
				() -> Transaction.executeInTransactionIfProvided(
					transaction,
					() -> {
						throw cause;
					},
					false
				)
			);

			assertSame(cause, thrown);
			assertFalse(transaction.isRollbackOnly());
			assertNull(transaction.getRollbackCause());
		}

		@Test
		@DisplayName("returns the supplier value when no exception occurs")
		void shouldReturnSupplierValueWhenNoException() {
			final Transaction transaction = newTransaction(new RecordingTransactionHandler());

			final String result = Transaction.executeInTransactionIfProvided(
				transaction,
				() -> "result"
			);

			assertEquals("result", result);
			assertFalse(Transaction.isTransactionAvailable());
		}

		@Test
		@DisplayName("always rolls back on TransactionException even when the flag is disabled")
		void shouldAlwaysRollbackOnTransactionExceptionEvenWhenFlagDisabled() {
			final Transaction transaction = newTransaction(new RecordingTransactionHandler());
			final TransactionException cause = new TransactionException("critical");

			final TransactionException thrown = assertThrows(
				TransactionException.class,
				() -> Transaction.executeInTransactionIfProvided(
					transaction,
					() -> {
						throw cause;
					},
					false
				)
			);

			assertSame(cause, thrown);
			assertTrue(transaction.isRollbackOnly());
			assertSame(cause, transaction.getRollbackCause());
		}

		@Test
		@DisplayName("marks rollback-only for a generic exception in a supplier when the flag is enabled")
		void shouldMarkRollbackOnlyForGenericExceptionInSupplierWhenFlagEnabled() {
			final Transaction transaction = newTransaction(new RecordingTransactionHandler());
			final IllegalStateException cause = new IllegalStateException("boom");

			final IllegalStateException thrown = assertThrows(
				IllegalStateException.class,
				() -> Transaction.executeInTransactionIfProvided(
					transaction,
					() -> {
						throw cause;
					},
					true
				)
			);

			assertSame(cause, thrown);
			assertTrue(transaction.isRollbackOnly());
			assertSame(cause, transaction.getRollbackCause());
		}

		@Test
		@DisplayName("does not mark rollback-only for a generic exception in a supplier when the flag is disabled")
		void shouldNotMarkRollbackOnlyForGenericExceptionInSupplierWhenFlagDisabled() {
			final Transaction transaction = newTransaction(new RecordingTransactionHandler());
			final IllegalStateException cause = new IllegalStateException("boom");

			final IllegalStateException thrown = assertThrows(
				IllegalStateException.class,
				() -> Transaction.executeInTransactionIfProvided(
					transaction,
					() -> {
						throw cause;
					},
					false
				)
			);

			assertSame(cause, thrown);
			assertFalse(transaction.isRollbackOnly());
			assertNull(transaction.getRollbackCause());
		}
	}

	@Nested
	@DisplayName("Close lifecycle")
	class CloseLifecycleTest {

		@Test
		@DisplayName("commits on close when not rollback-only")
		void shouldCommitOnCloseWhenNotRollbackOnly() {
			final RecordingTransactionHandler handler = new RecordingTransactionHandler();
			final Transaction transaction = newTransaction(handler);
			transaction.bindTransactionToThread();

			transaction.close();

			assertEquals(1, handler.getCommitCount());
			assertEquals(0, handler.getRollbackCount());
			assertTrue(transaction.isClosed());
			assertNotNull(transaction.getClosed());
			assertFalse(Transaction.isTransactionAvailable());
		}

		@Test
		@DisplayName("rolls back on close when rollback-only")
		void shouldRollbackOnCloseWhenRollbackOnly() {
			final RecordingTransactionHandler handler = new RecordingTransactionHandler();
			final Transaction transaction = newTransaction(handler);
			transaction.bindTransactionToThread();

			transaction.setRollbackOnly();
			transaction.close();

			assertEquals(0, handler.getCommitCount());
			assertEquals(1, handler.getRollbackCount());
			assertTrue(transaction.isClosed());
		}

		@Test
		@DisplayName("is idempotent on repeated close")
		void shouldBeIdempotentOnRepeatedClose() {
			final RecordingTransactionHandler handler = new RecordingTransactionHandler();
			final Transaction transaction = newTransaction(handler);
			transaction.bindTransactionToThread();

			transaction.close();
			final var firstClosedTimestamp = transaction.getClosed();
			transaction.close();

			assertEquals(1, handler.getCommitCount());
			assertEquals(0, handler.getRollbackCount());
			assertSame(firstClosedTimestamp, transaction.getClosed());
		}

		@Test
		@DisplayName("propagates the rollback cause to the rollback handler")
		void shouldPropagateRollbackCauseToRollbackHandler() {
			final RecordingTransactionHandler handler = new RecordingTransactionHandler();
			final Transaction transaction = newTransaction(handler);
			transaction.bindTransactionToThread();
			final RuntimeException cause = new RuntimeException("failure");

			transaction.setRollbackOnlyWithException(cause);
			transaction.close();

			assertEquals(1, handler.getRollbackCount());
			assertSame(cause, handler.getRollbackCause());
		}
	}

	@Nested
	@DisplayName("Rollback flag and identity")
	class RollbackFlagAndIdentityTest {

		@Test
		@DisplayName("keeps rollback-only set without a cause when invoked repeatedly")
		void shouldReportRollbackOnlyIdempotently() {
			final Transaction transaction = newTransaction(new RecordingTransactionHandler());

			transaction.setRollbackOnly();
			transaction.setRollbackOnly();

			assertTrue(transaction.isRollbackOnly());
			assertNull(transaction.getRollbackCause());
		}

		@Test
		@DisplayName("exposes the supplied id and created timestamp before close")
		void shouldExposeTransactionIdAndCreatedTimestamp() {
			final UUID id = UUID.randomUUID();
			final Transaction transaction = new Transaction(id, new RecordingTransactionHandler(), false);

			assertEquals(id, transaction.getTransactionId());
			assertNotNull(transaction.getCreated());
			assertNull(transaction.getClosed());
			assertFalse(transaction.isClosed());
		}
	}

	/**
	 * Test {@link TransactionHandler} that records how many times commit / rollback were invoked and captures the
	 * rollback cause so that the {@link Transaction#close()} commit / rollback selection can be asserted.
	 */
	private static class RecordingTransactionHandler implements TransactionHandler {
		private int commitCount;
		private int rollbackCount;
		private Throwable rollbackCause;

		int getCommitCount() {
			return this.commitCount;
		}

		int getRollbackCount() {
			return this.rollbackCount;
		}

		@Nullable
		Throwable getRollbackCause() {
			return this.rollbackCause;
		}

		@Override
		public void registerMutation(@Nonnull Mutation mutation) {
			fail("No mutation is expected to be registered in these tests.");
		}

		@Override
		public void commit(@Nonnull TransactionalLayerMaintainer transactionalLayer) {
			this.commitCount++;
		}

		@Override
		public void rollback(@Nonnull TransactionalLayerMaintainer transactionalLayer, @Nullable Throwable cause) {
			this.rollbackCount++;
			this.rollbackCause = cause;
		}
	}

}
