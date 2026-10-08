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

package io.evitadb.core.buffer;

import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.scope.SetEntityScopeMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.core.transaction.Transaction;
import io.evitadb.core.transaction.TransactionHandler;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer.Savepoint;
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.mutation.storagePart.ContainerizedLocalMutationExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.UUID;

import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link ContainerizedLocalMutationExecutor#journalPartsHeldBeforeSavepoint()} refuses to run outside
 * the window it is correct in: after the root mutation's savepoint opens and before the first mutation is applied.
 *
 * Both violations would otherwise be silent. Before the savepoint opens there is no journal, so the call records
 * nothing and a failed mutation leaves its writes in a trapped part. After a mutation the held parts already carry
 * its changes, so the call journals them as the pre-image and a rollback restores the wrong content. Each refusal is
 * paired with an acceptance in the same savepoint kind, so neither assertion can pass by refusing everything.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(TRANSACTION)
@DisplayName("Preconditions of journalling the parts an executor held before its savepoint")
class HeldPartsJournalPreconditionTest {
	private static final long CATALOG_VERSION = 1L;
	private static final int PRIMARY_KEY = 7;

	/**
	 * Closes a warm-up savepoint a failing test might have left bound to this thread.
	 */
	@AfterEach
	void closeLeakedSavepoint() {
		final WarmUpSavepoint leaked = WarmUpSavepoint.getIfOpen();
		if (leaked != null) {
			leaked.commit();
		}
	}

	@Test
	@DisplayName("Journalling inside an open warm-up savepoint before any mutation is accepted")
	void shouldAcceptJournallingInsideWarmUpSavepointBeforeFirstMutation() {
		final ContainerizedLocalMutationExecutor executor = createExecutor();

		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		try {
			assertDoesNotThrow(executor::journalPartsHeldBeforeSavepoint);
		} finally {
			savepoint.commit();
		}
	}

	@Test
	@DisplayName("Journalling without any open savepoint is refused")
	void shouldRefuseJournallingWithoutOpenSavepoint() {
		final ContainerizedLocalMutationExecutor executor = createExecutor();

		assertRefused(executor, "while the savepoint is open");
	}

	@Test
	@DisplayName("Journalling after the first mutation was applied is refused")
	void shouldRefuseJournallingAfterFirstMutation() {
		final ContainerizedLocalMutationExecutor executor = createExecutor();

		final WarmUpSavepoint savepoint = WarmUpSavepoint.open();
		try {
			executor.applyMutation(new SetEntityScopeMutation(Scope.LIVE));
			assertRefused(executor, "before the first mutation is applied");
		} finally {
			savepoint.commit();
		}
	}

	@Test
	@DisplayName("Journalling inside an open transactional savepoint is accepted")
	void shouldAcceptJournallingInsideTransactionalSavepoint() {
		runInTransaction(() -> {
			final ContainerizedLocalMutationExecutor executor = createExecutor();
			final TransactionalLayerMaintainer maintainer = Transaction.getTransactionalLayerMaintainer();
			final Savepoint savepoint = maintainer.openSavepoint();
			try {
				assertDoesNotThrow(executor::journalPartsHeldBeforeSavepoint);
			} finally {
				maintainer.commitSavepoint(savepoint);
			}
		});
	}

	@Test
	@DisplayName("Journalling inside a transaction with no open savepoint is refused")
	void shouldRefuseJournallingInTransactionWithoutSavepoint() {
		runInTransaction(() -> assertRefused(createExecutor(), "while the savepoint is open"));
	}

	/**
	 * Asserts that the executor refuses to journal its held parts, for the reason named by `expectedReason`.
	 *
	 * @param executor       the executor to call
	 * @param expectedReason a fragment of the refusal message identifying the violated precondition
	 */
	private static void assertRefused(@Nonnull ContainerizedLocalMutationExecutor executor, @Nonnull String expectedReason) {
		final GenericEvitaInternalError error = assertThrows(
			GenericEvitaInternalError.class,
			executor::journalPartsHeldBeforeSavepoint
		);
		assertTrue(
			error.getMessage().contains(expectedReason),
			"Refused for the wrong reason: " + error.getMessage()
		);
	}

	/**
	 * Creates an executor over an empty warm-up buffer. The entity does not exist, so it holds a fresh body that is
	 * not trapped — the preconditions are checked before any part is looked at, so nothing has to be trapped here.
	 *
	 * @return the executor for entity {@link #PRIMARY_KEY}
	 */
	@Nonnull
	private static ContainerizedLocalMutationExecutor createExecutor() {
		final WarmUpDataStoreMemoryBuffer buffer = new WarmUpDataStoreMemoryBuffer(new InMemoryStoragePartPersistenceService());
		final EntitySchema schema = EntitySchema._internalBuild("product");
		return new ContainerizedLocalMutationExecutor(
			buffer,
			buffer,
			CATALOG_VERSION,
			PRIMARY_KEY,
			EntityExistence.MAY_EXIST,
			() -> {
				throw new UnsupportedOperationException("The catalog schema is not consulted by these tests.");
			},
			() -> schema,
			entityType -> null,
			() -> 1,
			false
		);
	}

	/**
	 * Runs `body` inside a transaction bound to this thread and rolls the transaction back afterwards.
	 *
	 * @param body the code to run inside the transaction
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
	 * Minimal {@link TransactionHandler} that performs no commit / rollback work — these tests only need a
	 * transaction bound to the thread, never its outcome.
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
