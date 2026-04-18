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

package io.evitadb.core.transaction;

import io.evitadb.api.CommitProgressRecord;
import io.evitadb.api.exception.TransactionException;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watchdog registry that tracks in-flight {@link CommitProgressRecord}s by the catalog version they
 * are bound to and fails any record that is still dangling when the pipeline is torn down.
 *
 * **Why this exists**
 *
 * The normal transaction pipeline is expected to complete every `CommitProgressRecord` — either
 * successfully via {@link CommitProgressRecord#complete} or exceptionally via
 * {@link CommitProgressRecord#completeExceptionally}. Subtle conditions can cause a record to be
 * dropped on the floor — for example a request executor accepting an async completion task that is
 * then drained by `shutdownNow()` before running, or a session-level exception path that fails to
 * propagate to the progress record.
 *
 * Without a watchdog any such gap leaves the record's `CompletionStage`s pending forever, and every
 * client awaiting the record hangs. This registry provides a last-resort failure: once the pipeline
 * is shut down it is guaranteed not to touch any registered record anymore, so the registry fails
 * each remaining record with a descriptive {@link TransactionException} to unblock waiters.
 *
 * **Lifecycle**
 *
 * 1. After `resolveConflicts` has assigned a catalog version, the stage calls
 *    {@link #register(long, CommitProgressRecord)} to enrol the record under its version.
 * 2. The record's own `onChangesVisible().whenComplete(...)` callback auto-removes the entry on
 *    successful (or exceptional) completion, so the registry never grows unbounded in the happy path.
 * 3. When the transaction manager shuts down — or the pipeline is otherwise abandoned — the owner
 *    calls {@link #failAllPending(String)} to fail every still-registered record so clients do not
 *    hang on CompletionStages that will never be touched again. {@link #sweepUpTo(long)} is also
 *    available for targeted sweeps bounded by a catalog version, though it must only be called when
 *    the caller is certain the pipeline will not complete records at or below that threshold.
 *
 * **Thread-safety**
 *
 * Backed by a {@link ConcurrentHashMap}, so registration, auto-removal, and sweep may run
 * concurrently on different threads without external synchronisation. The hot path (register +
 * auto-deregister) is O(1); sweeps iterate the full map but run on cold paths (shutdown, targeted
 * cleanup) where O(n) is acceptable.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
public final class PendingCommitProgressRegistry {

	/**
	 * Map of catalog version → pending commit progress record. {@link ConcurrentHashMap} gives O(1)
	 * registration and auto-deregistration on the hot path; the sweeps ({@link #sweepUpTo} and
	 * {@link #failAllPending}) iterate the whole map but run only on cold paths (targeted cleanup
	 * and shutdown) where O(n) is acceptable.
	 */
	private final ConcurrentHashMap<Long, CommitProgressRecord> pending = new ConcurrentHashMap<>();

	/**
	 * Registers a commit progress record under the catalog version it was bound to.
	 *
	 * The registration wires an auto-deregistration callback on the record's `onChangesVisible`
	 * stage — so records that complete normally never require a manual removal and the registry
	 * stays small in steady state.
	 *
	 * @param catalogVersion the catalog version assigned by conflict resolution
	 * @param record         the progress record created by the session that is waiting for the
	 *                       transaction pipeline
	 */
	public void register(long catalogVersion, @Nonnull CommitProgressRecord record) {
		this.pending.put(catalogVersion, record);
		// auto-deregister on completion (success or failure) so the registry doesn't leak entries
		record.onChangesVisible().whenComplete(
			(result, throwable) -> this.pending.remove(catalogVersion)
		);
	}

	/**
	 * Sweeps every pending record with `catalogVersion &lt;= uptoVersion`, failing any that the
	 * transaction pipeline did not complete in time.
	 *
	 * The caller must invoke this method after — and only after — the catalog has actually advanced
	 * to `uptoVersion` on the live view. Calling it prematurely would fail records that the pipeline
	 * is still about to complete, corrupting the client's view of a successful commit.
	 *
	 * @param uptoVersion the new catalog version; every pending record with version at or below this
	 *                    threshold is considered stale
	 */
	public void sweepUpTo(long uptoVersion) {
		final Iterator<Map.Entry<Long, CommitProgressRecord>> iterator = this.pending.entrySet().iterator();
		while (iterator.hasNext()) {
			final Map.Entry<Long, CommitProgressRecord> entry = iterator.next();
			if (entry.getKey() > uptoVersion) {
				// entry is above the sweep threshold — leave it in the registry
				continue;
			}
			final CommitProgressRecord record = entry.getValue();
			if (!record.isDone()) {
				log.warn(
					"Swept dangling CommitProgressRecord for catalog version {} — the transaction " +
						"pipeline did not complete it before the catalog advanced to version {}. " +
						"This indicates a missed completion path or a race condition in the " +
						"transaction pipeline and should be investigated.",
					entry.getKey(), uptoVersion
				);
				record.completeExceptionally(
					new TransactionException(
						"Commit progress for catalog version " + entry.getKey() +
							" was not completed before the catalog advanced to version " + uptoVersion +
							". The transaction pipeline dropped this record; failing it to unblock waiters."
					)
				);
			}
			iterator.remove();
		}
	}

	/**
	 * Fails every still-registered record with a {@link TransactionException} describing the supplied
	 * reason, then clears the registry. Intended for pipeline shutdown paths where no further
	 * completion can happen — {@link TransactionManager#close()} being the primary caller.
	 *
	 * Calling this during normal operation would prematurely fail in-flight records, so it must only
	 * run once the caller is sure the pipeline will no longer process anything.
	 *
	 * @param reason human-readable description included in the exception message (e.g.
	 *               `"transaction manager is being closed"`)
	 */
	public void failAllPending(@Nonnull String reason) {
		final Iterator<Map.Entry<Long, CommitProgressRecord>> iterator = this.pending.entrySet().iterator();
		while (iterator.hasNext()) {
			final Map.Entry<Long, CommitProgressRecord> entry = iterator.next();
			final CommitProgressRecord record = entry.getValue();
			if (!record.isDone()) {
				log.warn(
					"Failing dangling CommitProgressRecord for catalog version {} because {}.",
					entry.getKey(), reason
				);
				record.completeExceptionally(
					new TransactionException(
						"Commit progress for catalog version " + entry.getKey() +
							" is being failed because " + reason +
							". The transaction pipeline will no longer complete this record."
					)
				);
			}
			iterator.remove();
		}
	}

	/**
	 * Returns the number of records currently registered and not yet completed. Intended for tests
	 * and observability — not on a hot path.
	 *
	 * @return the count of pending entries in the registry
	 */
	public int size() {
		return this.pending.size();
	}
}
