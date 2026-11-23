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
import javax.annotation.Nullable;
import java.time.Duration;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

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
 * 2. **Watchdog for dangling records** — [#sweepStalledRecords] fails records the pipeline has
 *    evidently lost: those it has made no progress towards for longer than the supplied bound, and
 *    those the live view has already moved past without completing them. The client awaiting such
 *    a record benefits from a descriptive exception more than from silence. Intended to be driven
 *    by a periodic scheduler task, while the pipeline reports its progress through
 *    [#recordPipelineProgress].
 *
 * 3. **Safety net when the pipeline stops** — [#failAllPending] fails every still-registered record
 *    when the catalog suspends, and [#failAllPendingAndLaterRegistrations] also fails every later
 *    registration once the pipeline will never run again (the transaction manager closes, or its
 *    catalog incarnation leaves the live view), so clients are not left hanging on completion stages
 *    that the pipeline will never touch again.
 *
 * Every registered record auto-deregisters through its own `onChangesVisible().whenComplete(...)`
 * callback, so the registry never grows unbounded in the happy path.
 *
 * **Visibility claim**
 *
 * The visibility stage of a registered record is claimed once, before its session termination callback
 * runs: the first of a successful publication (the record's own trunk task or a range fan-out), a
 * failing trunk task, the sweep and the failure of the whole pipeline wins, and the others leave the
 * record alone - so the session is terminated exactly once. A successful claim queues the completion of
 * the future on an executor; the sweep and a pipeline failure may still fail a future whose queued
 * completion never ran, without terminating the session again.
 *
 * **Thread-safety**
 *
 * Backed by a [ConcurrentSkipListMap] and a volatile progress mark, so registration, auto-removal, the
 * range fan-out, progress reports and the sweep may all run concurrently on different threads without
 * external synchronisation.
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
	 * (used by [#completeChangesVisibleInRange]) only visit the interesting prefix in O(log n + k).
	 */
	private final ConcurrentSkipListMap<Long, PendingEntry> pending = new ConcurrentSkipListMap<>();
	/**
	 * Source of monotonic time in nanoseconds - [System#nanoTime] outside tests.
	 */
	private final LongSupplier nanoClock;
	/**
	 * The [#nanoClock] reading of the last [#recordPipelineProgress] call, or of the construction of
	 * the registry before the first one.
	 */
	private volatile long lastProgressNanos;

	/**
	 * Why the pipeline stopped for good, set by [#failAllPendingAndLaterRegistrations]: every later
	 * registration is failed with it. `null` while the pipeline may still run.
	 */
	@Nullable
	private volatile String terminalReason;

	/**
	 * Creates a registry measuring time by [System#nanoTime].
	 */
	public PendingCommitProgressRegistry() {
		this(System::nanoTime);
	}

	/**
	 * Creates a registry measuring time by the supplied clock, so that tests can move time at will.
	 *
	 * @param nanoClock source of monotonic time in nanoseconds
	 */
	PendingCommitProgressRegistry(@Nonnull LongSupplier nanoClock) {
		this.nanoClock = nanoClock;
		this.lastProgressNanos = nanoClock.getAsLong();
	}

	/**
	 * Registers a commit progress record under the catalog version it was bound to and stores the
	 * [CommitVersions] alongside the record so later fan-out through
	 * [#completeChangesVisibleInRange] can invoke the termination callback with the correct
	 * per-record versions.
	 *
	 * The registration is removed when visibility settles. After
	 * [#failAllPendingAndLaterRegistrations], new registrations fail immediately.
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
		final PendingEntry entry = new PendingEntry(
			record, commitVersions, this.nanoClock.getAsLong(), new AtomicReference<>(), new AtomicLong()
		);
		this.pending.put(catalogVersion, entry);
		record.onChangesVisible().whenComplete(
			(result, throwable) -> this.pending.remove(catalogVersion, entry)
		);
		final String reason = this.terminalReason;
		if (reason != null) {
			failTerminatedEntry(catalogVersion, entry, reason);
		}
	}

	/**
	 * Completes the visibility of a trunk task's commit through the record's claim, so the session is
	 * terminated once even when a range fan-out or a failure competes for the same record. A record the
	 * registry does not hold under its version - never registered, already settled, or replaced by
	 * another record - is completed directly.
	 *
	 * @param record   the task's commit progress
	 * @param versions the versions assigned to the task
	 * @param executor executor that settles the visibility future
	 */
	public void completeChangesVisible(
		@Nonnull CommitProgressRecord record, @Nonnull CommitVersions versions, @Nonnull Executor executor
	) {
		final PendingEntry entry = this.pending.get(versions.catalogVersion());
		if (entry == null || entry.record() != record) {
			record.complete(CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, versions, executor);
		} else {
			completeEntry(entry, executor);
		}
	}

	/**
	 * Fails the visibility of a trunk task's commit through the record's claim. When another completion
	 * has claimed the record first the failure is discarded - after a successful claim the commit is
	 * visible and its session already terminated. A record the registry does not hold under that version
	 * is failed directly.
	 *
	 * @param catalogVersion the version assigned to the task
	 * @param record         the task's commit progress
	 * @param failure        the cause to report
	 */
	public void failChangesVisible(
		long catalogVersion, @Nonnull CommitProgressRecord record, @Nonnull Throwable failure
	) {
		final PendingEntry entry = this.pending.get(catalogVersion);
		if (entry == null || entry.record() != record || entry.claim().compareAndSet(null, Completion.FAILURE)) {
			record.completeExceptionally(failure);
		}
	}

	/**
	 * Completes [CommitBehavior#WAIT_FOR_CHANGES_VISIBLE] for every registered record whose catalog
	 * version lies in `(exclusiveFrom, inclusiveTo]`. Intended to be called from the trunk
	 * incorporation stage right after the live catalog has advanced through a greedy batch: every
	 * registered record in that range is semantically visible, so completing them here unblocks
	 * clients immediately instead of forcing them to wait for the publisher to re-deliver each
	 * trunk task to the "already processed" branch.
	 *
	 * Fan-out and an individual trunk task share the same claim, which precedes session termination and
	 * remains in force while asynchronous completion of the future is queued.
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
		for (final Map.Entry<Long, PendingEntry> entry : entries) {
			completeEntry(entry.getValue(), executor);
		}
	}

	/**
	 * Claims success before session termination, then records when the completion of the future was queued, so a
	 * stranded future can later be failed without terminating the session again. After a terminal failure of the
	 * pipeline a future the queued completion has not settled yet is failed at once.
	 *
	 * @param entry record whose transaction is visible
	 * @param executor executor that settles the visibility future
	 */
	private void completeEntry(@Nonnull PendingEntry entry, @Nonnull Executor executor) {
		if (entry.claim().compareAndSet(null, Completion.SUCCESS)) {
			entry.record().complete(CommitBehavior.WAIT_FOR_CHANGES_VISIBLE, entry.commitVersions(), executor);
			entry.successQueuedAtNanos().set(this.nanoClock.getAsLong());
			entry.claim().set(Completion.SUCCESS_QUEUED);
			final String reason = this.terminalReason;
			if (reason != null) {
				failTerminatedEntry(entry.commitVersions().catalogVersion(), entry, reason);
			}
		}
	}

	/**
	 * Records that trunk incorporation has just advanced - taken up a task or replayed a mutation.
	 * [#sweepStalledRecords] spares every record it keeps advancing towards, however long it takes, so a
	 * transaction that is merely slow to incorporate is never failed as a lost one.
	 *
	 * Only work that moves registered records towards completion may report progress. Every record is
	 * registered by conflict resolution, so that stage taking up a new commit merely queues another record
	 * behind them - were it to report progress, steady traffic would hide a stalled trunk incorporation for
	 * as long as commits keep arriving.
	 *
	 * Called once per replayed mutation, so it is a single clock read and a volatile write.
	 */
	public void recordPipelineProgress() {
		this.lastProgressNanos = this.nanoClock.getAsLong();
	}

	/**
	 * Fails every registered record the pipeline has evidently lost. This is the periodic watchdog:
	 * the client awaiting a lost record (missed completion path, async task dropped by a starved or
	 * shut-down executor, etc.) benefits from a descriptive exception more than from silence. A record
	 * pending for longer than `maxStall` since its registration is considered lost when either:
	 *
	 * - the pipeline has reported no progress for longer than `maxStall` either - nothing is working
	 *   towards the record any more, or
	 * - the live view has already reached its version - the pipeline moved past it without completing
	 *   it, which no amount of further progress repairs.
	 *
	 * A record whose successful completion has been queued is lost only once that completion has not run
	 * for `maxStall` either: until then it merely waits for its executor.
	 *
	 * The age counts from the registration, which is the moment the pipeline took the record over, not
	 * from [CommitProgressRecord#getCommitStartTime]: a session creates its record when it opens, so
	 * that time includes everything the session did before it committed. And a pipeline that keeps
	 * progressing spares the record, because a single large transaction can take longer to replay than
	 * any fixed bound on a loaded host - failing it then would tell the client that its commit was lost
	 * while the pipeline goes on to incorporate it.
	 *
	 * The progress mark is shared by the whole pipeline of the catalog, so a stalled pipeline fails
	 * every waiting record together, while one making progress fails none it has not moved past yet.
	 * Neither condition would do alone: a sweep by version could never fail a record lost while the
	 * pipeline stands idle, and a sweep by progress would spare a skipped record for as long as the
	 * pipeline keeps incorporating others.
	 *
	 * @param maxStall           how long a record may wait without the pipeline making progress before
	 *                           it is considered lost - also the minimum time it is left alone after its
	 *                           registration
	 * @param liveCatalogVersion the version of the catalog in the live view
	 * @return the number of records failed by this sweep, useful for tests and observability
	 */
	public int sweepStalledRecords(@Nonnull Duration maxStall, long liveCatalogVersion) {
		final long now = this.nanoClock.getAsLong();
		final long maxStallNanos = maxStall.toNanos();
		final long sinceProgressNanos = now - this.lastProgressNanos;
		int failed = 0;
		for (final Map.Entry<Long, PendingEntry> entry : this.pending.entrySet()) {
			final PendingEntry pendingEntry = entry.getValue();
			final CommitProgressRecord record = pendingEntry.record();
			if (!record.isDone()) {
				// every age is a difference of two clock readings, as the System#nanoTime contract requires
				final long sinceRegistrationNanos = now - pendingEntry.registeredAtNanos();
				if (sinceRegistrationNanos < maxStallNanos) {
					// too young to judge - the map is keyed by catalog version, not by time, so later entries
					// may be older and the iteration goes on
					continue;
				}
				if (pendingEntry.claim().get() == Completion.SUCCESS_QUEUED &&
					now - pendingEntry.successQueuedAtNanos().get() < maxStallNanos) {
					// a round has made the record visible and its completion waits for the request executor - only a
					// completion that has not run for the whole bound is stranded
					continue;
				}
				final boolean passedByLiveView = entry.getKey() <= liveCatalogVersion;
				if (!passedByLiveView && sinceProgressNanos < maxStallNanos) {
					// the pipeline is still working its way towards this record
					continue;
				}
				final long maxStallMillis = maxStall.toMillis();
				log.warn(
					"Sweeping dangling CommitProgressRecord for catalog version {} — {}. This indicates a " +
						"missed completion path in the pipeline, or a pipeline stalled outright, and should be " +
						"investigated.",
					entry.getKey(),
					passedByLiveView ?
						"the live view already reached version " + liveCatalogVersion + " without completing it" :
						"the transaction pipeline made no progress for more than " + maxStallMillis + "ms"
				);
				final TransactionException failure = new TransactionException(
					"Commit progress for catalog version " + entry.getKey() + " has been pending for more " +
						"than " + maxStallMillis + "ms " +
						(passedByLiveView ?
							"and the live view has already moved past it. " :
							"without the transaction pipeline making any progress. ") +
						"The transaction pipeline dropped this record; failing it to unblock waiters."
				);
				if (failStalledEntry(pendingEntry, failure)) {
					failed++;
				}
			}
			// failing the record has already deregistered it through its completion callback - this removes a
			// record that completed but whose callback has not run yet
			if (record.isDone()) {
				this.pending.remove(entry.getKey(), pendingEntry);
			}
		}
		return failed;
	}

	/**
	 * Fails every still-registered record with a [TransactionException] describing the supplied
	 * reason and removes it from the registry. Intended for a pipeline that will not complete the
	 * registered records - a suspended catalog, and through [#failAllPendingAndLaterRegistrations] a
	 * closed transaction manager or a retired catalog incarnation. Records registered later are not
	 * affected.
	 *
	 * A record whose successful completion is still running its session termination keeps that claim
	 * and stays registered until its completion settles it; one whose completion is only queued is
	 * failed without terminating its session again.
	 *
	 * @param reason human-readable description included in the exception message (e.g.
	 *               `"transaction manager is being closed"`)
	 */
	public void failAllPending(@Nonnull String reason) {
		for (final Map.Entry<Long, PendingEntry> entry : this.pending.entrySet()) {
			failTerminatedEntry(entry.getKey(), entry.getValue(), reason);
		}
	}

	/**
	 * Fails every still-registered record as [#failAllPending] does, and every record registered from now on.
	 * Meant for a pipeline that will never run again - a closed transaction manager, or one whose catalog
	 * incarnation left the live view - where a record registered concurrently would otherwise wait for nothing.
	 *
	 * A suspension must not use it: conflict resolution appends a commit to the WAL even when its registration
	 * fails, so failing a later registration would report a written commit as failed.
	 *
	 * @param reason human-readable description included in the exception message
	 */
	public void failAllPendingAndLaterRegistrations(@Nonnull String reason) {
		this.terminalReason = reason;
		failAllPending(reason);
	}

	/**
	 * Fails one record on pipeline termination, including a record registered concurrently with termination.
	 *
	 * @param version version assigned to the record
	 * @param entry pending record at that version
	 * @param reason why the pipeline stopped
	 */
	private void failTerminatedEntry(long version, @Nonnull PendingEntry entry, @Nonnull String reason) {
		if (!entry.record().isDone()) {
			log.warn("Failing dangling CommitProgressRecord for catalog version {} because {}.", version, reason);
			failStalledEntry(
				entry,
				new TransactionException(
					"Commit progress for catalog version " + version + " is being failed because " + reason +
						". The transaction pipeline will no longer complete this record."
				)
			);
		}
		if (entry.record().isDone()) {
			this.pending.remove(version, entry);
		}
	}

	/**
	 * Fails a stalled record. If success already claimed termination, fail any stranded futures directly so
	 * the watchdog or shutdown can still unblock clients without invoking termination a second time.
	 *
	 * @param entry stalled record and its claim
	 * @param failure cause to report
	 * @return whether this call failed the record
	 */
	private static boolean failStalledEntry(@Nonnull PendingEntry entry, @Nonnull Throwable failure) {
		final CommitProgressRecord record = entry.record();
		final Completion claimed = entry.claim().compareAndExchange(null, Completion.FAILURE);
		if (claimed == null) {
			record.completeExceptionally(failure);
			return true;
		} else if (claimed == Completion.SUCCESS_QUEUED) {
			record.onConflictResolved().toCompletableFuture().completeExceptionally(failure);
			record.onWalAppended().toCompletableFuture().completeExceptionally(failure);
			return record.onChangesVisible().toCompletableFuture().completeExceptionally(failure);
		}
		return false;
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
	 * @param record               the in-flight commit progress record waiting for pipeline completion
	 * @param commitVersions       the versions assigned to this record at conflict-resolution time
	 * @param registeredAtNanos    the [#nanoClock] reading at the registration - the moment the pipeline took
	 *                             the record over
	 * @param claim                holder of the completion that owns session termination; empty until a claim
	 * @param successQueuedAtNanos the [#nanoClock] reading when a successful claim queued the completion of the
	 *                             future, which lets the sweep tell a queued completion from a stranded one
	 */
	private record PendingEntry(
		@Nonnull CommitProgressRecord record,
		@Nonnull CommitVersions commitVersions,
		long registeredAtNanos,
		@Nonnull AtomicReference<Completion> claim,
		@Nonnull AtomicLong successQueuedAtNanos
	) {
	}

	/**
	 * The completion that owns the record's session termination callback.
	 */
	private enum Completion {
		/** The successful publisher owns session termination. */
		SUCCESS,
		/** Termination has returned and only the queued future completion may need recovery. */
		SUCCESS_QUEUED,
		/** The failing publisher owns session termination and future completion. */
		FAILURE
	}

}
