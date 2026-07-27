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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE.md
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.transaction;

import io.evitadb.api.CommitProgressRecord;
import io.evitadb.api.exception.RollbackException;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictKey;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.transaction.memory.TransactionalLayerMaintainer;
import io.evitadb.index.bPlusTree.AbstractTransactionalBPlusTree.BPlusTreeCorruptedException;
import io.evitadb.spi.store.catalog.shared.model.LogRecordReference;
import io.evitadb.spi.store.catalog.wal.IsolatedWalPersistenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Direct coverage of the pre-commit (pre-WAL) rejection path in {@link TransactionWalFinalizer#commit}. The tree unit
 * tests drive the DETECTION pipeline ({@code validateDirtyScopesBeforeCommit()} through isolated transactions); this
 * test exercises the finalizer's own catch block — the seam whose entire purpose is behaving well when the pre-commit
 * pass rejects. No production corruption-injection hook is needed: the maintainer is stubbed to throw the same
 * {@link BPlusTreeCorruptedException} a real corrupt scope produces, and a recording isolated-WAL stub proves the WAL
 * is released.
 */
@Tag(INDEXING)
@Tag(TRANSACTION)
@DisplayName("pre-commit rejection path in the transaction WAL finalizer")
class TransactionWalFinalizerTest {

	/**
	 * Recording {@link IsolatedWalPersistenceService} stub — the only behaviour under test is that {@link #close()} is
	 * invoked on a rejection, releasing the isolated WAL's off-heap region / temp file.
	 */
	private static final class RecordingWalService implements IsolatedWalPersistenceService {
		private final UUID transactionId;
		private final AtomicBoolean closed = new AtomicBoolean(false);

		RecordingWalService(@Nonnull UUID transactionId) {
			this.transactionId = transactionId;
		}

		@Nonnull
		@Override
		public UUID getTransactionId() {
			return this.transactionId;
		}

		@Override
		public int getMutationCount() {
			return 0;
		}

		@Override
		public long getMutationSizeInBytes() {
			return 0L;
		}

		@Override
		public void write(long catalogVersion, @Nonnull Mutation mutation) {
			// no-op: the test only needs the service to exist so its close() can be asserted
		}

		@Override
		public LogRecordReference getWalReference() {
			return null;
		}

		@Nonnull
		@Override
		public Set<ConflictKey> getConflictKeys() {
			return Collections.emptySet();
		}

		@Override
		public void close() {
			this.closed.set(true);
		}

		boolean isClosed() {
			return this.closed.get();
		}
	}

	/**
	 * An {@link Error} thrown from a registered closeable. Errors escape {@code closeRegisteredCloseables}' own
	 * {@code catch (Exception)}, reproducing the failure mode Finding 1 addresses.
	 */
	private static final class CloseFailure extends Error {
		CloseFailure() {
			super("simulated closeable failure during a pre-commit rejection");
		}
	}

	@Nonnull
	private static Catalog mockCatalog() {
		final Catalog catalog = mock(Catalog.class, RETURNS_DEEP_STUBS);
		when(catalog.getVersion()).thenReturn(1L);
		return catalog;
	}

	@Nonnull
	private static TransactionalLayerMaintainer maintainerRejectingPreCommit() {
		final TransactionalLayerMaintainer maintainer = mock(TransactionalLayerMaintainer.class);
		doThrow(new BPlusTreeCorruptedException("a B+ tree leaf overlaps its successor leaf boundary"))
			.when(maintainer).validateDirtyScopesBeforeCommit();
		return maintainer;
	}

	@Nonnull
	private static Mutation liveWalMutation(@Nonnull UUID transactionId) {
		// registering any mutation makes the lazily-created isolated WAL service live so its close() can be asserted;
		// Mutation is a sealed interface (not mockable), so a real TransactionMutation stands in
		return new TransactionMutation(transactionId, 1L, 1, 0L, OffsetDateTime.now());
	}

	@Test
	@DisplayName("a pre-commit rejection releases the isolated WAL, closes registered closeables, and completes the commit exceptionally")
	void shouldRejectCleanlyOnPreCommitValidationFailure() {
		final UUID transactionId = UUID.randomUUID();
		final RecordingWalService wal = new RecordingWalService(transactionId);
		final CommitProgressRecord commitProgress = new CommitProgressRecord();
		final TransactionWalFinalizer finalizer = new TransactionWalFinalizer(
			mockCatalog(), transactionId, id -> wal, commitProgress
		);
		final AtomicBoolean closeableClosed = new AtomicBoolean(false);
		finalizer.registerCloseable(() -> closeableClosed.set(true));
		// the isolated WAL service is created lazily on the first registered mutation
		finalizer.registerMutation(liveWalMutation(transactionId));

		// (a) the rejection must not escape commit(...) — a raw throw would hang the commit future
		assertDoesNotThrow(() -> finalizer.commit(maintainerRejectingPreCommit()));
		// (b) the isolated WAL is released so its off-heap region / temp file cannot leak
		assertTrue(wal.isClosed(), "the isolated WAL must be closed on a pre-commit rejection");
		// (c) registered transactional resources are closed
		assertTrue(closeableClosed.get(), "registered closeables must be closed on a pre-commit rejection");
		// (d) the commit future completes exceptionally with RollbackException chaining BPlusTreeCorruptedException
		final CompletableFuture<?> future = commitProgress.onWalAppended().toCompletableFuture();
		assertTrue(future.isCompletedExceptionally(), "the commit future must complete exceptionally");
		final ExecutionException executionException = assertThrows(ExecutionException.class, future::get);
		final Throwable rollback = executionException.getCause();
		assertInstanceOf(RollbackException.class, rollback, "the commit must fail with a RollbackException");
		assertInstanceOf(
			BPlusTreeCorruptedException.class, rollback.getCause(),
			"the RollbackException must chain the tree-level corruption as its cause"
		);
	}

	@Test
	@DisplayName("a closeable throwing during a pre-commit rejection still releases the WAL and completes the commit (Finding 1)")
	void shouldReleaseWalEvenWhenCloseableThrowsDuringRejection() {
		final UUID transactionId = UUID.randomUUID();
		final RecordingWalService wal = new RecordingWalService(transactionId);
		final CommitProgressRecord commitProgress = new CommitProgressRecord();
		final TransactionWalFinalizer finalizer = new TransactionWalFinalizer(
			mockCatalog(), transactionId, id -> wal, commitProgress
		);
		// a closeable that throws an Error — Errors escape closeRegisteredCloseables' own catch(Exception), so without
		// the try/finally the WAL close + commit completion would be skipped (the exact Finding-1 hazard)
		finalizer.registerCloseable(() -> {
			throw new CloseFailure();
		});
		finalizer.registerMutation(liveWalMutation(transactionId));

		// the closeable's Error still propagates (as it does out of rollback too) — but ONLY after the finally has
		// released the WAL and completed the commit future, so neither the hang nor the leak can occur
		assertThrows(CloseFailure.class, () -> finalizer.commit(maintainerRejectingPreCommit()));
		assertTrue(wal.isClosed(), "the isolated WAL must be released even when a closeable throws");
		assertTrue(
			commitProgress.onWalAppended().toCompletableFuture().isCompletedExceptionally(),
			"the commit future must complete exceptionally even when a closeable throws"
		);
	}

}
