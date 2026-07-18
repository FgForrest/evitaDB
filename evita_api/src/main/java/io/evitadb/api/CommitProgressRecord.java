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

package io.evitadb.api;

import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.function.Functions;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/**
 * Mutable implementation of {@link CommitProgress} that tracks commit pipeline stages via {@link CompletableFuture}s.
 *
 * **Purpose and Architecture**
 *
 * `CommitProgressRecord` is the concrete implementation used internally by evitaDB to manage commit progress. It
 * provides methods for the transaction subsystem to signal completion of each commit stage, while exposing read-only
 * {@link CompletionStage}s to client code via the {@link CommitProgress} interface.
 *
 * **Internal vs. Public API**
 *
 * - **Public API** ({@link CommitProgress}): Clients observe progress via read-only {@link CompletionStage}s
 * - **Internal API** (this class): evitaDB transaction logic completes stages via `complete()` and
 * `completeExceptionally()` methods
 *
 * **Completion Contract**
 *
 * This implementation enforces {@link CommitProgress} guarantees:
 * 1. Stages complete sequentially (conflict resolution → WAL → visibility) — and so do any listeners a
 * consumer registers on them: a listener on an earlier stage always finishes running before a later
 * stage's listeners begin, even though completion itself happens asynchronously (see {@link #completionSequencer})
 * 2. Exception in any stage immediately propagates to all later stages
 * 3. All stages eventually complete (no hanging futures)
 * 4. Idempotent completion (multiple calls to `complete()` have no effect after first completion)
 *
 * **Termination Sequence**
 *
 * The {@link #terminationSequence} callback is invoked just before marking a stage as complete, based on the
 * {@link #terminationStage} setting. This allows session cleanup logic to execute at the appropriate commit milestone
 * (e.g., close session resources after WAL persistence but before indexing).
 *
 * **Asynchronous Completion**
 *
 * The `complete(CommitBehavior, CommitVersions, Executor)` variant completes futures asynchronously via
 * {@link #completionSequencer}, preventing the transaction pipeline thread from blocking on client callbacks
 * while still preserving stage-order listener firing. If the executor rejects a queued task, that stage's
 * completion falls back to synchronous mode.
 *
 * **Thread-Safety**
 *
 * This class is thread-safe. Multiple threads can safely call completion methods and observe stages concurrently.
 * Internal {@link CompletableFuture}s handle synchronization.
 *
 * **Typical Usage (Internal)**
 *
 * ```
 * CommitProgressRecord progress = new CommitProgressRecord();
 * progress.setTerminationStage(sessionTraits.commitBehaviour());
 *
 * // Stage 1: Conflict resolution
 * try {
 * resolveConflicts(transaction);
 * progress.complete(CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION, versions, executor);
 * } catch (Exception ex) {
 * progress.completeExceptionally(ex);
 * return;
 * }
 *
 * // Stage 2: WAL persistence
 * walPersistenceService.persist(mutations)
 * .thenAccept(v -> progress.complete(CommitBehavior.WAIT_FOR_WAL_PERSISTENCE, versions, executor))
 * .exceptionally(ex -> { progress.completeExceptionally(ex); return null; });
 *
 * // Stage 3: Index updates
 * indexUpdateService.apply(mutations)
 * .thenAccept(v -> progress.complete(CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, versions, executor))
 * .exceptionally(ex -> { progress.completeExceptionally(ex); return null; });
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
public class CommitProgressRecord implements CommitProgress {

	/**
	 * Timestamp marking when the commit process began.
	 *
	 * Captured when this `CommitProgressRecord` is created (typically when {@link EvitaSessionContract#closeNowWithProgress()}
	 * is called). Used for performance monitoring and timeout detection.
	 */
	@Getter private final OffsetDateTime commitStartTime = OffsetDateTime.now();

	/**
	 * Callback invoked just before marking a stage as complete, based on {@link #terminationStage}.
	 *
	 * This callback enables session cleanup logic to execute at the appropriate commit milestone. For example:
	 * - If `terminationStage` is {@link CommitBehavior#WAIT_FOR_WAL_PERSISTENCE}, callback runs after WAL persistence
	 * but before indexing
	 * - Session resources (connections, temp files) can be released at the configured stage
	 *
	 * **Signature**: `BiConsumer<CommitVersions, Throwable>`
	 * - First parameter: commit versions (null on exception)
	 * - Second parameter: exception (null on success)
	 *
	 * **Exception Handling**: This callback must not throw exceptions; any errors should be logged internally.
	 */
	public final BiConsumer<CommitVersions, Throwable> terminationSequence;

	/**
	 * Future that completes when optimistic lock conflict resolution finishes.
	 *
	 * Exposed publicly via {@link #onConflictResolved()}. Completed internally by evitaDB transaction logic when
	 * conflict check passes or fails.
	 */
	private final CompletableFuture<CommitVersions> onConflictResolved;

	/**
	 * Future that completes when changes are persisted to the Write-Ahead Log (WAL).
	 *
	 * Exposed publicly via {@link #onWalAppended()}. Completed internally after WAL fsync succeeds or I/O error occurs.
	 */
	private final CompletableFuture<CommitVersions> onWalAppended;

	/**
	 * Future that completes when changes are visible in all indexes.
	 *
	 * Exposed publicly via {@link #onChangesVisible()}. Completed internally after index updates finish or indexing
	 * fails.
	 */
	private final CompletableFuture<CommitVersions> onChangesVisible;

	/**
	 * The commit stage at which the {@link #terminationSequence} callback should be invoked.
	 *
	 * Defaults to {@link CommitBehavior#WAIT_FOR_CHANGES_VISIBLE}. Set to earlier stages (e.g.,
	 * {@link CommitBehavior#WAIT_FOR_WAL_PERSISTENCE}) to release session resources sooner.
	 *
	 * **Mutable**: This field is set after construction to match the session's {@link SessionTraits#commitBehaviour()}.
	 */
	@Getter @Setter public CommitBehavior terminationStage = CommitBehavior.WAIT_FOR_CHANGES_VISIBLE;

	/**
	 * Serial queue backing the async {@link #complete(CommitBehavior, CommitVersions, Executor)} variant.
	 *
	 * Every stage-completion action is appended here instead of being fired directly, so that a stage's
	 * actual `complete()` call — including the synchronous firing of every listener already registered on
	 * it — always finishes running before the next queued stage's `complete()` call begins.
	 *
	 * This exists because {@link CompletableFuture} does not guarantee listeners fire in registration
	 * order: when multiple actions are pending on the same stage at the moment it completes, they fire in
	 * an unspecified order (in practice, most-recently-registered-first). Without this queue, completing
	 * a later stage while an earlier stage is still pending registers a NEW "advance to the next stage"
	 * listener on that earlier stage — and since that listener is registered after a consumer's own
	 * listener (e.g. one attached right after this record was created), it can end up firing first,
	 * letting a later stage's client notification win the race and arrive before the earlier stage's
	 * notification, even though the underlying futures themselves always complete in the correct order.
	 * Routing every completion through this single-file queue makes listener-firing order match stage
	 * order too, for any consumer, regardless of executor timing.
	 *
	 * **Access**: only ever read or written inside the `synchronized` {@link #enqueueCompletion} — never
	 * a plain {@link java.util.concurrent.atomic.AtomicReference} with a `updateAndGet`/`getAndUpdate`
	 * style compare-and-swap, because the update here has a side effect (`thenRunAsync` registers a real
	 * listener on `previous`). A CAS-based update re-invokes its function on every lost race, and a lost
	 * attempt's already-registered listener cannot be un-registered — it fires later against a stale
	 * chain link regardless, reintroducing the exact out-of-order-firing bug this queue exists to prevent.
	 * A monitor makes the append a single, un-retried critical section instead.
	 */
	private CompletableFuture<Void> completionSequencer = CompletableFuture.completedFuture(null);

	/**
	 * Appends `stage.complete(commitVersions)` to the serial {@link #completionSequencer}, guaranteeing it
	 * runs only once every previously queued stage completion — and, transitively, every listener already
	 * registered on that earlier stage — has finished. Never blocks the caller on the queued work itself:
	 * the whole queue runs on `executor`; `synchronized` here only guards the (sub-microsecond) append of
	 * one more link, not the eventual `stage.complete(...)` call.
	 *
	 * @param stage          the stage to complete once its turn in the queue arrives
	 * @param commitVersions the versions to complete `stage` with
	 * @param executor       the executor the queued action (and its rejection recovery) runs on
	 */
	private synchronized void enqueueCompletion(
		@Nonnull CompletableFuture<CommitVersions> stage,
		@Nonnull CommitVersions commitVersions,
		@Nonnull Executor executor
	) {
		this.completionSequencer = this.completionSequencer
			.thenRunAsync(() -> stage.complete(commitVersions), executor)
			.exceptionally(ex -> {
				// the executor rejected the task (shutdown / saturated queue) — complete the stage
				// immediately so the pipeline can still terminate, and recover here so a rejection
				// can never poison the queue for later stages
				stage.complete(commitVersions);
				return null;
			});
	}

	/**
	 * Invokes the termination callback defensively.
	 *
	 * The contract says the callback "must not throw", but a misbehaving user-supplied implementation
	 * (e.g., a throwing {@link EvitaSessionTerminationCallback}) must not leave stages pending. This helper
	 * catches every throwable and logs it, then returns normally so the subsequent {@link CompletableFuture#complete}
	 * call can still fire.
	 *
	 * @param commitVersions the versions passed to the callback (null on exception paths)
	 * @param throwable      the exception passed to the callback (null on success paths)
	 */
	private void invokeTerminationSequence(
		@Nullable CommitVersions commitVersions,
		@Nullable Throwable throwable
	) {
		try {
			this.terminationSequence.accept(commitVersions, throwable);
		} catch (Throwable callbackException) {
			log.error(
				"Termination sequence callback threw an exception; the commit progress record will " +
					"continue to complete the stage regardless.",
				callbackException
			);
		}
	}

	/**
	 * Creates a new `CommitProgressRecord` with no termination callback.
	 *
	 * Equivalent to `new CommitProgressRecord(Functions.noOpBiConsumer())`. Used when no session cleanup logic
	 * is needed at commit milestones.
	 */
	public CommitProgressRecord() {
		this(Functions.noOpBiConsumer());
	}

	/**
	 * Creates a new `CommitProgressRecord` with the specified termination callback.
	 *
	 * The callback is invoked just before marking the stage configured in {@link #terminationStage} as complete.
	 * This allows session cleanup logic to execute at the appropriate commit milestone.
	 *
	 * @param terminationSequence callback invoked before completing the termination stage; must not throw exceptions
	 */
	public CommitProgressRecord(@Nonnull BiConsumer<CommitVersions, Throwable> terminationSequence) {
		this.terminationSequence = terminationSequence;
		this.onConflictResolved = new CompletableFuture<>();
		this.onWalAppended = new CompletableFuture<>();
		this.onChangesVisible = new CompletableFuture<>();
	}

	@Override
	@Nonnull
	public CompletionStage<CommitVersions> onConflictResolved() {
		return this.onConflictResolved;
	}

	@Override
	@Nonnull
	public CompletionStage<CommitVersions> onWalAppended() {
		return this.onWalAppended;
	}

	@Override
	@Nonnull
	public CompletionStage<CommitVersions> onChangesVisible() {
		return this.onChangesVisible;
	}

	@Override
	public boolean isDone() {
		return this.onConflictResolved.isDone() &&
			this.onWalAppended.isDone() &&
			this.onChangesVisible.isDone();
	}

	@Override
	public boolean isCompletedSuccessfully() {
		return this.onConflictResolved.isDone() &&
			this.onWalAppended.isDone() &&
			this.onChangesVisible.isDone() &&
			!this.onConflictResolved.isCompletedExceptionally() &&
			!this.onWalAppended.isCompletedExceptionally() &&
			!this.onChangesVisible.isCompletedExceptionally();
	}

	@Override
	public boolean isCompletedExceptionally() {
		return this.onConflictResolved.isCompletedExceptionally() ||
			this.onWalAppended.isCompletedExceptionally() ||
			this.onChangesVisible.isCompletedExceptionally();
	}

	/**
	 * Fails all incomplete stages with the specified exception, propagating failure through the pipeline.
	 *
	 * This method implements the guarantee that when any stage fails, all later stages fail immediately with the same
	 * exception. Invokes {@link #terminationSequence} callback with the exception when completing the configured
	 * {@link #terminationStage}.
	 *
	 * **Idempotency**: Already-completed stages are not affected; only incomplete stages are failed.
	 *
	 * **Use Cases**
	 *
	 * - Conflict detection fails: call with `ConcurrentSchemaUpdateException`
	 * - WAL I/O error: call with `IOException`
	 * - Catalog shutdown: call with `InstanceTerminatedException`
	 *
	 * @param exception the exception to fail all incomplete stages with
	 */
	public void completeExceptionally(@Nonnull Throwable exception) {
		if (!this.onConflictResolved.isDone()) {
			if (this.terminationStage == CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION) {
				invokeTerminationSequence(null, exception);
			}
			this.onConflictResolved.completeExceptionally(exception);
		}
		if (!this.onWalAppended.isDone()) {
			if (this.terminationStage == CommitBehavior.WAIT_FOR_WAL_PERSISTENCE) {
				invokeTerminationSequence(null, exception);
			}
			this.onWalAppended.completeExceptionally(exception);
		}
		if (!this.onChangesVisible.isDone()) {
			if (this.terminationStage == CommitBehavior.WAIT_FOR_CHANGES_VISIBLE) {
				invokeTerminationSequence(null, exception);
			}
			this.onChangesVisible.completeExceptionally(exception);
		}
	}

	/**
	 * Completes the stage corresponding to the specified commit behavior synchronously.
	 *
	 * This method marks the specified stage as complete with the provided versions. If the stage is already complete,
	 * this call has no effect (idempotent). Invokes {@link #terminationSequence} callback when completing the
	 * configured {@link #terminationStage}.
	 *
	 * **Synchronous Completion**: This variant completes the future immediately on the calling thread. Client callbacks
	 * registered via `thenAccept()` etc. will execute on this thread, potentially blocking the transaction pipeline.
	 * Prefer {@link #complete(CommitBehavior, CommitVersions, Executor)} for production use.
	 *
	 * **Use Cases**
	 *
	 * - Testing: deterministic completion order
	 * - Read-only sessions: no client callbacks expected
	 * - Empty transactions: immediate completion of all stages
	 *
	 * @param commitBehavior the stage to complete ({@link CommitBehavior#WAIT_FOR_CONFLICT_RESOLUTION},
	 *                       {@link CommitBehavior#WAIT_FOR_WAL_PERSISTENCE}, or
	 *                       {@link CommitBehavior#WAIT_FOR_CHANGES_VISIBLE})
	 * @param commitVersions the versions to complete the stage with
	 * @throws IllegalArgumentException if an unsupported commit behavior is provided (should never happen)
	 */
	public void complete(@Nonnull CommitBehavior commitBehavior, @Nonnull CommitVersions commitVersions) {
		switch (commitBehavior) {
			case WAIT_FOR_CONFLICT_RESOLUTION -> {
				if (!this.onConflictResolved.isDone()) {
					if (this.terminationStage == CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION) {
						invokeTerminationSequence(commitVersions, null);
					}
					this.onConflictResolved.complete(commitVersions);
				}
			}
			case WAIT_FOR_WAL_PERSISTENCE -> {
				if (!this.onWalAppended.isDone()) {
					if (this.terminationStage == CommitBehavior.WAIT_FOR_WAL_PERSISTENCE) {
						invokeTerminationSequence(commitVersions, null);
					}
					this.onWalAppended.complete(commitVersions);
				}
			}
			case WAIT_FOR_CHANGES_VISIBLE -> {
				if (!this.onChangesVisible.isDone()) {
					if (this.terminationStage == CommitBehavior.WAIT_FOR_CHANGES_VISIBLE) {
						invokeTerminationSequence(commitVersions, null);
					}
					this.onChangesVisible.complete(commitVersions);
				}
			}
			default -> throw new IllegalArgumentException("Unsupported commit behavior: " + commitBehavior);
		}
	}

	/**
	 * Completes the stage corresponding to the specified commit behavior asynchronously.
	 *
	 * This is the preferred method for production use. It completes the specified stage asynchronously in the provided
	 * executor to prevent client callbacks from blocking the transaction pipeline thread.
	 *
	 * **Asynchronous Behavior**
	 *
	 * Every call — regardless of which stage — appends its completion to {@link #completionSequencer}, so
	 * stage N's `complete()` call (and every listener already registered on stage N) always finishes
	 * running before stage N+1's `complete()` call begins, even if stage N was still pending when stage
	 * N+1 was requested. See {@link #completionSequencer} for why this is necessary.
	 *
	 * **Fallback**: If the executor rejects a queued task, that stage is completed immediately instead
	 * (see {@link #enqueueCompletion(CompletableFuture, CommitVersions, Executor)}).
	 *
	 * **Use Cases**
	 *
	 * - Production commit pipeline: offload client callbacks from transaction threads
	 * - High-throughput scenarios: prevent slow client callbacks from blocking commit processing
	 *
	 * @param commitBehavior the stage to complete ({@link CommitBehavior#WAIT_FOR_CONFLICT_RESOLUTION},
	 *                       {@link CommitBehavior#WAIT_FOR_WAL_PERSISTENCE}, or
	 *                       {@link CommitBehavior#WAIT_FOR_CHANGES_VISIBLE})
	 * @param commitVersions the versions to complete the stage with
	 * @param executor       the executor to run completion asynchronously (usually a dedicated callback executor)
	 * @throws IllegalArgumentException if an unsupported commit behavior is provided (should never happen)
	 */
	public void complete(
		@Nonnull CommitBehavior commitBehavior, @Nonnull CommitVersions commitVersions, @Nonnull Executor executor) {
		switch (commitBehavior) {
			case WAIT_FOR_CONFLICT_RESOLUTION -> {
				if (!this.onConflictResolved.isDone()) {
					if (this.terminationStage == CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION) {
						invokeTerminationSequence(commitVersions, null);
					}
					enqueueCompletion(this.onConflictResolved, commitVersions, executor);
				}
			}
			case WAIT_FOR_WAL_PERSISTENCE -> {
				if (!this.onWalAppended.isDone()) {
					if (this.terminationStage == CommitBehavior.WAIT_FOR_WAL_PERSISTENCE) {
						invokeTerminationSequence(commitVersions, null);
					}
					enqueueCompletion(this.onWalAppended, commitVersions, executor);
				}
			}
			case WAIT_FOR_CHANGES_VISIBLE -> {
				if (!this.onChangesVisible.isDone()) {
					if (this.terminationStage == CommitBehavior.WAIT_FOR_CHANGES_VISIBLE) {
						invokeTerminationSequence(commitVersions, null);
					}
					enqueueCompletion(this.onChangesVisible, commitVersions, executor);
				}
			}
			default -> throw new IllegalArgumentException("Unsupported commit behavior: " + commitBehavior);
		}
	}

	/**
	 * Immediately completes all incomplete stages successfully with the provided versions.
	 *
	 * This method is a convenience for completing all stages at once, typically used for:
	 * - Read-only sessions (no actual commit processing)
	 * - Empty transactions (no mutations to process)
	 * - Test scenarios requiring deterministic completion
	 *
	 * **Behavior**: Completes all incomplete stages synchronously in sequence:
	 * 1. `onConflictResolved`
	 * 2. `onWalAppended`
	 * 3. `onChangesVisible`
	 *
	 * **Idempotency**: Already-completed stages are skipped.
	 *
	 * **Termination Callback**: Invoked when completing the stage configured in {@link #terminationStage}.
	 *
	 * @param commitVersions the versions to complete all stages with
	 */
	public void complete(@Nonnull CommitVersions commitVersions) {
		if (!this.onConflictResolved.isDone()) {
			if (this.terminationStage == CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION) {
				invokeTerminationSequence(commitVersions, null);
			}
			this.onConflictResolved.complete(commitVersions);
		}
		if (!this.onWalAppended.isDone()) {
			if (this.terminationStage == CommitBehavior.WAIT_FOR_WAL_PERSISTENCE) {
				invokeTerminationSequence(commitVersions, null);
			}
			this.onWalAppended.complete(commitVersions);
		}
		if (!this.onChangesVisible.isDone()) {
			if (this.terminationStage == CommitBehavior.WAIT_FOR_CHANGES_VISIBLE) {
				invokeTerminationSequence(commitVersions, null);
			}
			this.onChangesVisible.complete(commitVersions);
		}
	}
}