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

import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.CommitProgressRecord;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.exception.TransactionException;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executor;

/**
 * Index of in-flight [CommitProgressRecord]s keyed by the catalog version they are bound to. The
 * registry serves three purposes in the transaction pipeline, all three driven by the fact that the
 * registry is the only place that keeps a mapping from catalog version to the client-facing
 * progress record:
 *
 * 1. **Fan-out of visibility to greedy batches** — when trunk incorporation greedily processes
 *    multiple transactions in one batch the live catalog jumps past every version in the batch.
 *    Every registered record in the batch is then semantically "visible" and
 *    [#completeChangesVisibleInRange] completes their [CommitBehavior#WAIT_FOR_CHANGES_VISIBLE]
 *    stage right away instead of waiting for the publisher to re-deliver each individual trunk
 *    task. This is the hot-path use.
 *
 * 2. **Time-bounded watchdog for dangling records** — [#sweepRecordsOlderThan] fails records whose
 *    progress has been pending longer than the supplied age. Unlike a version-bounded sweep this
 *    is safe to run at any time because the cut-off is orthogonal to pipeline state: if a record
 *    has been registered for longer than the worst-case pipeline latency it is almost certainly
 *    dangling, and the client awaiting it benefits from a descriptive exception more than from
 *    silence. Intended to be driven by a periodic scheduler task.
 *
 * 3. **Shutdown safety net** — [#failAllPending] fails every still-registered record when the
 *    transaction manager is closed, so clients are not left hanging on completion stages that the
 *    pipeline will never touch again.
 *
 * Every registered record auto-deregisters through its own `onChangesVisible().whenComplete(...)`
 * callback, so the registry never grows unbounded in the happy path.
 *
 * **Thread-safety**
 *
 * Backed by a [ConcurrentSkipListMap], so registration, auto-removal, the range fan-out and the
 * time-based sweep may all run concurrently on different threads without external synchronisation.
 * Registration and auto-deregistration are O(log n) on the hot path; the range fan-out uses
 * [ConcurrentSkipListMap#subMap] to visit only the relevant prefix in O(log n + k) where k is the
 * number of records swept.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
public final class PendingCommitProgressRegistry {

	/**
	 * Map of catalog version → pending entry. [ConcurrentSkipListMap] is used so that range queries
	 * (used by [#completeChangesVisibleInRange]) only visit the interesting prefix in O(log n + k)
	 * and so the time-based sweep can short-circuit on the youngest side of the map once it hits
	 * the first record that is still within the age threshold.
	 */
	private final ConcurrentSkipListMap<Long, PendingEntry> pending = new ConcurrentSkipListMap<>();

	/**
	 * Registers a commit progress record under the catalog version it was bound to and stores the
	 * [CommitVersions] alongside the record so later fan-out through
	 * [#completeChangesVisibleInRange] can invoke the termination callback with the correct
	 * per-record versions.
	 *
	 * The registration wires an auto-deregistration callback on the record's `onChangesVisible`
	 * stage — so records that complete normally never require a manual removal and the registry
	 * stays small in steady state.
	 *
	 * @param catalogVersion the catalog version assigned by conflict resolution
	 * @param record         the progress record created by the session that is waiting for the
	 *                       transaction pipeline
	 * @param commitVersions the versions assigned to this record by conflict resolution; used when
	 *                       fanning out [CommitBehavior#WAIT_FOR_CHANGES_VISIBLE] during a greedy
	 *                       batch so the termination callback receives the correct versions
	 */
	public void register(
		long catalogVersion,
		@Nonnull CommitProgressRecord record,
		@Nonnull CommitVersions commitVersions
	) {
		this.pending.put(catalogVersion, new PendingEntry(record, commitVersions));
		// auto-deregister on completion (success or failure) so the registry doesn't leak entries
		record.onChangesVisible().whenComplete(
			(result, throwable) -> this.pending.remove(catalogVersion)
		);
	}

	/**
	 * Completes [CommitBehavior#WAIT_FOR_CHANGES_VISIBLE] for every registered record whose catalog
	 * version lies in `(exclusiveFrom, inclusiveTo]`. Intended to be called from the trunk
	 * incorporation stage right after the live catalog has advanced through a greedy batch: every
	 * registered record in that range is semantically visible, so completing them here unblocks
	 * clients immediately instead of forcing them to wait for the publisher to re-deliver each
	 * trunk task to the "already processed" branch.
	 *
	 * The operation is idempotent with respect to later pipeline completions: [CommitProgressRecord]
	 * guards against double-completion internally, so if the publisher eventually delivers the
	 * individual trunk task for a version we already completed here, its `complete` call becomes a
	 * no-op.
	 *
	 * The `commitVersions` passed to each `complete` call are the versions captured at registration
	 * time — this matters for records whose termination stage is [CommitBehavior#WAIT_FOR_CHANGES_VISIBLE]
	 * because the termination callback receives those versions.
	 *
	 * @param exclusiveFrom lower bound (exclusive) — typically the current trunk task's own version,
	 *                      which has already been completed separately and is auto-deregistered
	 * @param inclusiveTo   upper bound (inclusive) — typically the live catalog version after the
	 *                      greedy batch, i.e. the highest version whose changes are now visible
	 * @param executor      the executor used to run the completion callbacks asynchronously
	 */
	public void completeChangesVisibleInRange(
		long exclusiveFrom,
		long inclusiveTo,
		@Nonnull Executor executor
	) {
		if (exclusiveFrom >= inclusiveTo) {
			// empty or inverted range — nothing to fan out to
			return;
		}
		final Set<Entry<Long, PendingEntry>> entries = this.pending
			.subMap(exclusiveFrom, false, inclusiveTo, true)
			.entrySet();
		for (Map.Entry<Long, PendingEntry> entry : entries) {
			final PendingEntry pendingEntry = entry.getValue();
			pendingEntry.record().complete(
				CommitBehavior.WAIT_FOR_CHANGES_VISIBLE,
				pendingEntry.commitVersions(),
				executor
			);
		}
	}

	/**
	 * Fails every registered record whose [CommitProgressRecord#getCommitStartTime] is older than
	 * `maxAge`. This is the periodic watchdog: any record that has been pending longer than the
	 * worst-case pipeline latency is almost certainly dangling (missed completion path, async task
	 * dropped by a starved or shut-down executor, etc.) and the client awaiting it benefits from a
	 * descriptive exception more than from silence.
	 *
	 * Using wall-clock age rather than a catalog-version bound keeps this safe to run at any time:
	 * the cut-off does not depend on pipeline state, so the usual "must wait until the pipeline is
	 * guaranteed to have completed records at or below this version" invariant that a version-based
	 * sweep needs is avoided entirely.
	 *
	 * **That independence from pipeline state is also the limitation.** Elapsed time is a proxy for
	 * liveness, and the proxy breaks when the host is oversubscribed: a starved executor makes a
	 * healthy-but-slow commit indistinguishable from a genuinely dropped one, so this sweep will fail
	 * it and log the "missed completion path" warning below with nothing actually wrong. The warning is
	 * therefore evidence of a stall, not proof of a pipeline defect — rule out CPU contention first.
	 * The deadline is chosen by `TransactionManager#safetyDeadlineMs`, where the reasoning for leaving
	 * it wall-clock-based (and how a contended caller should widen it instead) is recorded.
	 *
	 * @param maxAge the maximum age a record may stay pending before being considered dangling
	 * @return the number of records failed by this sweep, useful for tests and observability
	 */
	public int sweepRecordsOlderThan(@Nonnull Duration maxAge) {
		final OffsetDateTime threshold = OffsetDateTime.now().minus(maxAge);
		int failed = 0;
		final Iterator<Map.Entry<Long, PendingEntry>> iterator = this.pending.entrySet().iterator();
		while (iterator.hasNext()) {
			final Map.Entry<Long, PendingEntry> entry = iterator.next();
			final CommitProgressRecord record = entry.getValue().record();
			if (record.getCommitStartTime().isAfter(threshold)) {
				// record is younger than the threshold — leave it alone; later entries may be older
				// or newer (the map is keyed by catalog version, not timestamp) so we can't safely
				// short-circuit on this condition alone
				continue;
			}
			if (!record.isDone()) {
				log.warn(
					"Sweeping dangling CommitProgressRecord for catalog version {} — the transaction " +
						"pipeline did not complete it within {}ms of the commit start time. This indicates " +
						"a missed completion path in the pipeline and should be investigated.",
					entry.getKey(), maxAge.toMillis()
				);
				record.completeExceptionally(
					new TransactionException(
						"Commit progress for catalog version " + entry.getKey() +
							" has been pending for more than " + maxAge.toMillis() + "ms. " +
							"The transaction pipeline dropped this record; failing it to unblock waiters."
					)
				);
				failed++;
			}
			iterator.remove();
		}
		return failed;
	}

	/**
	 * Fails every still-registered record with a [TransactionException] describing the supplied
	 * reason, then clears the registry. Intended for pipeline shutdown paths where no further
	 * completion can happen — [TransactionManager#close] being the primary caller.
	 *
	 * Calling this during normal operation would prematurely fail in-flight records, so it must only
	 * run once the caller is sure the pipeline will no longer process anything.
	 *
	 * @param reason human-readable description included in the exception message (e.g.
	 *               `"transaction manager is being closed"`)
	 */
	public void failAllPending(@Nonnull String reason) {
		final Iterator<Map.Entry<Long, PendingEntry>> iterator = this.pending.entrySet().iterator();
		while (iterator.hasNext()) {
			final Map.Entry<Long, PendingEntry> entry = iterator.next();
			final CommitProgressRecord record = entry.getValue().record();
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

	/**
	 * Entry stored alongside each pending record. Keeping the [CommitVersions] here avoids having
	 * to reconstruct them at fan-out time — [CommitProgressRecord#complete] needs them to invoke
	 * the termination callback with the correct per-record versions even though the underlying
	 * future chain derives its value from the previous stage.
	 *
	 * @param record         the in-flight commit progress record waiting for pipeline completion
	 * @param commitVersions the versions assigned to this record at conflict-resolution time
	 */
	private record PendingEntry(
		@Nonnull CommitProgressRecord record,
		@Nonnull CommitVersions commitVersions
	) {
	}
}
