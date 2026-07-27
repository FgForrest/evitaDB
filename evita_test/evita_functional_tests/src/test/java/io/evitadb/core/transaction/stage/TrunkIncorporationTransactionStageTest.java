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

package io.evitadb.core.transaction.stage;

import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.CommitProgressRecord;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.core.executor.ObservableExecutorService;
import io.evitadb.core.transaction.TransactionManager;
import io.evitadb.core.transaction.stage.TrunkIncorporationTransactionStage.TrunkIncorporationTransactionTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.TRANSACTION;

/**
 * Unit tests for {@link TrunkIncorporationTransactionStage} focused on completion guarantees of the
 * associated {@link CommitProgressRecord}.
 *
 * The stage must complete the record on every exit path — otherwise clients waiting on
 * {@link CommitProgressRecord#onChangesVisible()} hang forever.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Trunk incorporation commit progress completion")
@Tag(ENGINE)
@Tag(TRANSACTION)
class TrunkIncorporationTransactionStageTest {

	private static final String CATALOG_NAME = "testCatalog";

	/**
	 * When {@link TransactionManager#processTransactions} returns {@link Optional#empty()} — typically
	 * because a concurrent trunk-incorporation task already processed our version while we waited for
	 * the lock — the stage must still complete `commitProgress`. Without completion the record would
	 * be left with `onChangesVisible` pending forever, and any client awaiting this stage would hang.
	 */
	@Test
	@DisplayName("should complete commit progress when processTransactions returns empty Optional")
	void shouldCompleteCommitProgressWhenProcessTransactionsReturnsEmpty() {
		final TransactionManager tm = mock(TransactionManager.class);
		when(tm.getCatalogName()).thenReturn(CATALOG_NAME);
		when(tm.getLastFinalizedCatalogVersion()).thenReturn(10L);
		// processTransactions returns empty — our target version (20) is past the finalized one, but
		// the mutation stream yielded nothing (a racing task drained it)
		when(tm.processTransactions(anyLong(), anyLong(), anyBoolean(), anyBoolean(), any()))
			.thenReturn(Optional.empty());
		final ObservableExecutorService synchronousExecutor = mock(ObservableExecutorService.class);
		doAnswer(inv -> {
			((Runnable) inv.getArgument(0)).run();
			return null;
		}).when(synchronousExecutor).execute(any(Runnable.class));
		when(tm.getRequestExecutor()).thenReturn(synchronousExecutor);

		final TrunkIncorporationTransactionStage stage = new TrunkIncorporationTransactionStage(
			tm, 1_000L, (task, ex) -> {}
		);

		final CommitProgressRecord progress = new CommitProgressRecord();
		// simulate the upstream stage having already completed stages 1 and 2 — in the real pipeline the
		// task only reaches trunk-incorporation after ConflictResolutionAndWalAppendingTransactionStage
		// has completed both conflict resolution and WAL appending, so the chain used by the fix can
		// propagate the final completion synchronously
		final CommitVersions versions = new CommitVersions(20L, 1);
		progress.complete(CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION, versions, synchronousExecutor);
		progress.complete(CommitBehavior.WAIT_FOR_WAL_PERSISTENCE, versions, synchronousExecutor);

		final TrunkIncorporationTransactionTask task = new TrunkIncorporationTransactionTask(
			CATALOG_NAME, 20L, 1, UUID.randomUUID(), progress
		);

		stage.handleNext(task);

		assertTrue(
			progress.onChangesVisible().toCompletableFuture().isDone(),
			"Commit progress onChangesVisible must be completed even when processTransactions " +
				"returns empty Optional — otherwise clients awaiting the record hang forever"
		);
		assertFalse(
			progress.onChangesVisible().toCompletableFuture().isCompletedExceptionally(),
			"The empty-Optional path reflects a concurrent task completing our transaction, which is " +
				"not a failure — the commit progress should complete successfully"
		);
	}

	/**
	 * The configured `flushFrequencyInMillis` budget has to reach
	 * {@link TransactionManager#processTransactions(long, long, boolean, boolean, java.util.function.LongConsumer)}
	 * in **milliseconds**, because that is the unit its `timeoutMs` argument is compared in (against a
	 * `System.currentTimeMillis()` delta).
	 *
	 * This previously passed nanoseconds, which inflated the default 1 s budget to roughly 11.6 days —
	 * the greedy batching loop then never terminated on time and drained the whole WAL backlog present
	 * at round open regardless of the configured budget, leaving the knob silently inert.
	 */
	@Test
	@DisplayName("should hand the greedy batching budget to processTransactions in milliseconds")
	void shouldPassGreedyBatchingBudgetInMilliseconds() {
		final TransactionManager tm = mock(TransactionManager.class);
		when(tm.getCatalogName()).thenReturn(CATALOG_NAME);
		when(tm.getLastFinalizedCatalogVersion()).thenReturn(10L);
		when(tm.processTransactions(anyLong(), anyLong(), anyBoolean(), anyBoolean(), any()))
			.thenReturn(Optional.empty());
		final ObservableExecutorService synchronousExecutor = mock(ObservableExecutorService.class);
		doAnswer(inv -> {
			((Runnable) inv.getArgument(0)).run();
			return null;
		}).when(synchronousExecutor).execute(any(Runnable.class));
		when(tm.getRequestExecutor()).thenReturn(synchronousExecutor);

		final long flushFrequencyInMillis = 1_000L;
		final TrunkIncorporationTransactionStage stage = new TrunkIncorporationTransactionStage(
			tm, flushFrequencyInMillis, (task, ex) -> {}
		);

		final CommitProgressRecord progress = new CommitProgressRecord();
		final CommitVersions versions = new CommitVersions(20L, 1);
		progress.complete(CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION, versions, synchronousExecutor);
		progress.complete(CommitBehavior.WAIT_FOR_WAL_PERSISTENCE, versions, synchronousExecutor);

		stage.handleNext(
			new TrunkIncorporationTransactionTask(
				CATALOG_NAME, 20L, 1, UUID.randomUUID(), progress
			)
		);

		final ArgumentCaptor<Long> timeoutCaptor = ArgumentCaptor.forClass(Long.class);
		verify(tm).processTransactions(
			anyLong(), timeoutCaptor.capture(), anyBoolean(), anyBoolean(), any()
		);
		assertEquals(
			flushFrequencyInMillis, timeoutCaptor.getValue(),
			"The greedy batching budget must be passed in milliseconds — processTransactions compares " +
				"it against a System.currentTimeMillis() delta, so any other unit makes the knob inert"
		);
	}

}
