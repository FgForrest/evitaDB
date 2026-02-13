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
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.util.concurrent.CompletionStage;

/**
 * Tracks the progress of a transaction commit through evitaDB's multi-stage commit pipeline.
 *
 * **Purpose and Architecture**
 *
 * `CommitProgress` provides fine-grained observability into the asynchronous commit process by exposing
 * {@link CompletionStage}s for each major commit milestone. This allows clients to:
 * - React to specific stages completing (e.g., log after WAL persistence, update UI after visibility)
 * - Implement custom synchronization strategies (e.g., wait for durability but proceed before visibility)
 * - Handle partial failures gracefully (e.g., durability succeeded but indexing failed)
 *
 * **Commit Pipeline Stages**
 *
 * 1. **Conflict Resolution** ({@link #onConflictResolved()}): Optimistic lock check passed
 * 2. **WAL Persistence** ({@link #onWalAppended()}): Changes written to disk (durable)
 * 3. **Changes Visible** ({@link #onChangesVisible()}): Indexes updated, changes queryable
 *
 * **Completion Guarantees**
 *
 * 1. **Sequential completion**: Later stages never complete before earlier stages
 * 2. **Exception propagation**: If a stage fails, all later stages fail with the same exception immediately
 * 3. **Eventual completion**: All stages eventually complete (success or exception)
 * 4. **Durability boundary**: If WAL appending succeeds, changes are guaranteed durable even if indexing fails
 * 5. **Empty transaction optimization**: Read-only or no-op sessions complete all stages immediately
 *
 * **Durability vs. Visibility**
 *
 * Critical distinction:
 * - After {@link #onWalAppended()} completes successfully, changes are durable (survive crashes)
 * - If {@link #onChangesVisible()} fails after WAL success, changes may not be visible yet but will be recovered
 * on restart via WAL replay
 * - Client disconnect after WAL persistence does NOT lose changes, even if visibility stage wasn't observed
 *
 * **Usage Patterns**
 *
 * Wait for durability only:
 * ```
 * CommitProgress progress = session.closeNowWithProgress();
 * progress.onWalAppended().thenAccept(versions -> {
 * logger.info("Changes durable at catalog version {}", versions.catalogVersion());
 * // Proceed without waiting for indexing
 * });
 * ```
 *
 * Custom error handling:
 * ```
 * progress.onChangesVisible()
 * .thenAccept(versions -> updateUI(versions))
 * .exceptionally(ex -> {
 * if (isWalPersisted(progress)) {
 * // Changes are durable, retry will recover
 * scheduleRetry();
 * } else {
 * // Transaction lost, full rollback
 * handleFailure(ex);
 * }
 * return null;
 * });
 * ```
 *
 * Monitoring all stages:
 * ```
 * progress.onConflictResolved().thenRun(() -> metrics.recordConflictCheck());
 * progress.onWalAppended().thenRun(() -> metrics.recordWalPersistence());
 * progress.onChangesVisible().thenRun(() -> metrics.recordIndexUpdate());
 * ```
 *
 * **Thread-Safety**
 *
 * This interface is thread-safe. Multiple threads can safely register callbacks on the same `CommitProgress` instance.
 * Callbacks may be invoked from evitaDB internal threads (not the session thread).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface CommitProgress {

	/**
	 * Returns a {@link CompletionStage} that completes when optimistic lock conflict resolution succeeds.
	 *
	 * **Completion Criteria**
	 *
	 * This stage completes successfully when:
	 * - The transaction's mutations have been validated against concurrent transactions
	 * - No conflicting changes were detected (overlapping entity modifications)
	 * - The transaction has been accepted for further processing
	 *
	 * **Guarantees After Completion**
	 *
	 * - Transaction will not be rejected due to conflicts
	 * - Changes are queued for WAL persistence
	 *
	 * **No Guarantees**
	 *
	 * - Changes are NOT durable yet (not written to disk)
	 * - Changes are NOT visible to queries yet
	 *
	 * **Exception Cases**
	 *
	 * Completes exceptionally if:
	 * - Conflict with a concurrent transaction detected ({@link io.evitadb.api.exception.ConcurrentSchemaUpdateException}
	 * or similar)
	 * - Schema validation fails
	 * - Session already closed or catalog shutting down
	 *
	 * **Equivalent to**
	 *
	 * {@link TransactionContract.CommitBehavior#WAIT_FOR_CONFLICT_RESOLUTION}
	 *
	 * @return {@link CompletionStage} that resolves to {@link CommitVersions} when conflict resolution completes
	 */
	@Nonnull
	CompletionStage<CommitVersions> onConflictResolved();

	/**
	 * Returns a {@link CompletionStage} that completes when changes are persisted to the Write-Ahead Log (WAL).
	 *
	 * **Completion Criteria**
	 *
	 * This stage completes successfully when:
	 * - All mutations have been serialized to the WAL file
	 * - The WAL has been fsynced to disk (durable)
	 * - {@link #onConflictResolved()} has already completed successfully
	 *
	 * **Guarantees After Completion**
	 *
	 * - Changes are durable and survive server crashes
	 * - Changes will be recovered via WAL replay on restart if not yet indexed
	 * - Catalog version number has been assigned to this transaction
	 *
	 * **No Guarantees**
	 *
	 * - Changes are NOT visible to queries yet (indexing may still be in progress)
	 *
	 * **Exception Cases**
	 *
	 * Completes exceptionally if:
	 * - I/O error during WAL write or fsync
	 * - Disk full
	 * - Catalog corruption detected
	 * - {@link #onConflictResolved()} completed exceptionally (propagated)
	 *
	 * **Performance Note**
	 *
	 * evitaDB may batch fsync operations from multiple transactions to amortize disk I/O cost. This stage may complete
	 * faster than expected if batched with other concurrent commits.
	 *
	 * **Equivalent to**
	 *
	 * {@link TransactionContract.CommitBehavior#WAIT_FOR_WAL_PERSISTENCE}
	 *
	 * @return {@link CompletionStage} that resolves to {@link CommitVersions} when WAL persistence completes
	 */
	@Nonnull
	CompletionStage<CommitVersions> onWalAppended();

	/**
	 * Returns a {@link CompletionStage} that completes when changes are visible in all indexes.
	 *
	 * **Completion Criteria**
	 *
	 * This stage completes successfully when:
	 * - All entity mutations have been applied to entity storage
	 * - All attribute/reference/hierarchy indexes have been updated
	 * - Changes are visible to all subsequent queries in any session
	 * - {@link #onWalAppended()} has already completed successfully
	 *
	 * **Guarantees After Completion**
	 *
	 * - Changes are durable (survived {@link #onWalAppended()})
	 * - Changes are immediately queryable
	 * - Next query will see these changes
	 * - Strong consistency: read-your-writes guarantee across all sessions
	 *
	 * **Exception Cases**
	 *
	 * Completes exceptionally if:
	 * - Index update fails (e.g., uniqueness constraint violation during indexing)
	 * - Catalog shutdown initiated
	 * - {@link #onWalAppended()} completed exceptionally (propagated)
	 *
	 * **Critical Note on Durability**
	 *
	 * If {@link #onWalAppended()} succeeded but this stage fails, changes are still durable. The failure indicates
	 * indexing issues, but the data is safe in WAL and will be recovered/indexed on next restart.
	 *
	 * **Performance Note**
	 *
	 * This is the slowest stage, as it involves multiple index updates. Large transactions or complex schemas
	 * (many attributes/references) increase indexing time.
	 *
	 * **Equivalent to**
	 *
	 * {@link TransactionContract.CommitBehavior#WAIT_FOR_CHANGES_VISIBLE}
	 *
	 * @return {@link CompletionStage} that resolves to {@link CommitVersions} when changes become visible
	 */
	@Nonnull
	CompletionStage<CommitVersions> onChangesVisible();

	/**
	 * Returns the {@link CompletionStage} corresponding to the specified {@link CommitBehavior}.
	 *
	 * This convenience method maps commit behaviors to their corresponding progress stages:
	 * - {@link CommitBehavior#WAIT_FOR_CONFLICT_RESOLUTION} → {@link #onConflictResolved()}
	 * - {@link CommitBehavior#WAIT_FOR_WAL_PERSISTENCE} → {@link #onWalAppended()}
	 * - {@link CommitBehavior#WAIT_FOR_CHANGES_VISIBLE} → {@link #onChangesVisible()}
	 *
	 * **Use Cases**
	 *
	 * - Waiting for a specific stage based on session configuration
	 * - Implementing generic commit logic that respects {@link SessionTraits} commit behavior
	 *
	 * Example:
	 * ```
	 * CommitBehavior behavior = session.getCommitBehavior();
	 * progress.on(behavior).thenAccept(versions -> {
	 * logger.info("Reached configured commit stage: {}", behavior);
	 * });
	 * ```
	 *
	 * @param commitBehavior the behavior defining which stage to wait for
	 * @return the {@link CompletionStage} corresponding to the specified commit behavior
	 * @throws EvitaInvalidUsageException if an unsupported commit behavior is provided (should never happen with
	 *                                    standard enum values)
	 */
	default CompletionStage<CommitVersions> on(@Nonnull CommitBehavior commitBehavior) {
		switch (commitBehavior) {
			case WAIT_FOR_CONFLICT_RESOLUTION -> {
				return onConflictResolved();
			}
			case WAIT_FOR_WAL_PERSISTENCE -> {
				return onWalAppended();
			}
			case WAIT_FOR_CHANGES_VISIBLE -> {
				return onChangesVisible();
			}
			default -> throw new EvitaInvalidUsageException("Unsupported commit behavior: " + commitBehavior);
		}
	}

	/**
	 * Returns `true` if all commit stages have completed (either successfully or exceptionally).
	 *
	 * This method returns `true` when:
	 * - {@link #onConflictResolved()}, {@link #onWalAppended()}, and {@link #onChangesVisible()} are all done
	 * - Completion may be successful or exceptional (use {@link #isCompletedSuccessfully()} to distinguish)
	 *
	 * **Use Cases**
	 *
	 * - Polling to check if commit has finished (prefer {@link CompletionStage#whenComplete} for event-driven approach)
	 * - Verifying commit completion in tests
	 *
	 * @return `true` if all stages are done, `false` if any stage is still pending
	 */
	boolean isDone();

	/**
	 * Returns `true` if all commit stages completed successfully (no exceptions).
	 *
	 * This method returns `true` only when:
	 * - {@link #isDone()} returns `true` (all stages complete)
	 * - None of the stages completed exceptionally
	 *
	 * **Use Cases**
	 *
	 * - Verifying successful commit in tests
	 * - Implementing retry logic (retry only if NOT successfully completed)
	 *
	 * @return `true` if all stages completed successfully, `false` otherwise
	 */
	boolean isCompletedSuccessfully();

	/**
	 * Returns `true` if any commit stage completed exceptionally.
	 *
	 * This method returns `true` if:
	 * - {@link #onConflictResolved()} threw an exception (e.g., conflict detected), OR
	 * - {@link #onWalAppended()} threw an exception (e.g., I/O error), OR
	 * - {@link #onChangesVisible()} threw an exception (e.g., indexing failure)
	 *
	 * **Use Cases**
	 *
	 * - Quick failure detection without inspecting individual stages
	 * - Implementing fallback logic on any commit failure
	 *
	 * **Note**: If {@link #onWalAppended()} succeeded but {@link #onChangesVisible()} failed, this returns `true`,
	 * but changes are still durable (see {@link #onWalAppended()} documentation).
	 *
	 * @return `true` if any stage completed exceptionally, `false` otherwise
	 */
	boolean isCompletedExceptionally();

	/**
	 * Version identifiers assigned to a transaction when it commits successfully.
	 *
	 * **Purpose and Usage**
	 *
	 * `CommitVersions` captures the catalog version and schema version that were assigned when the transaction's
	 * changes were incorporated into the catalog. These version numbers uniquely identify a specific point-in-time
	 * state of the catalog.
	 *
	 * **Version Semantics**
	 *
	 * - **Catalog version**: Monotonically increasing counter incremented for each committed transaction that modifies
	 * entity data or schema. Used for MVCC (Multi-Version Concurrency Control) and snapshot isolation.
	 * - **Schema version**: Incremented only when schema mutations are applied (entity types, attributes, references).
	 * Remains unchanged for transactions that only modify entity data.
	 *
	 * **Visibility Guarantee**
	 *
	 * When you read a catalog at `catalogVersion`, you are guaranteed to see:
	 * - All changes from this transaction
	 * - All changes from prior transactions (lower version numbers)
	 * - Schema state matching `catalogSchemaVersion`
	 *
	 * **Use Cases**
	 *
	 * - Implementing read-after-write consistency across distributed clients
	 * - Snapshot isolation: open a new session at a specific `catalogVersion` to read consistent state
	 * - Change tracking: compare version numbers to detect intervening changes
	 * - Debugging: log version numbers to correlate application actions with catalog state
	 *
	 * **Example: Read-Your-Writes Across Sessions**
	 *
	 * ```
	 * // Write in session 1
	 * CommitVersions versions = session1.closeNow(CommitBehavior.WAIT_FOR_CHANGES_VISIBLE).toCompletableFuture().join();
	 *
	 * // Read in session 2, guaranteed to see the write
	 * EvitaSessionContract session2 = evita.createReadOnlySession("catalog");
	 * // session2 automatically sees versions.catalogVersion() or later
	 * ```
	 *
	 * **Thread-Safety**
	 *
	 * This record is immutable and thread-safe.
	 *
	 * @param catalogVersion       the catalog version number after the transaction committed (monotonically increasing,
	 *                             starts at 1 when catalog goes {@link CatalogState#ALIVE})
	 * @param catalogSchemaVersion the schema version number after the transaction committed (incremented only when
	 *                             schema mutations are applied)
	 */
	record CommitVersions(
		long catalogVersion,
		int catalogSchemaVersion
	) {
	}
}
