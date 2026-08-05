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

package io.evitadb.store.catalog;

import io.evitadb.core.executor.DelayedAsyncTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.metric.event.storage.CatalogCheckpointEvent;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.store.offsetIndex.io.PendingSyncRegistry;
import io.evitadb.store.offsetIndex.io.WriteOnlyHandle;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import io.evitadb.spi.store.catalog.persistence.DurabilitySnapshot;

import java.io.Closeable;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Decouples the cadence at which a trunk round makes its changes **visible** from the cadence at which they are made
 * **durable on the data files**.
 *
 * Without it, every trunk round ends by forcing each written data file to the device and writing a bootstrap record
 * that points at them - a fixed bill of `N_changed + 2` device flushes. That bill does not shrink with load; it is
 * merely divided among however many transactions the round managed to collapse, which is why its share of the round
 * is *largest* on a lightly loaded system (measured 57 % of the round at two concurrent writers, and nothing at
 * sixty-four).
 *
 * With it, rounds keep writing and flushing their bytes exactly as before - so everything downstream sees the same
 * state - but the device flush and the bootstrap record happen only every
 * {@link io.evitadb.api.configuration.TransactionOptions#checkpointIntervalInMillis()}.
 *
 * **This weakens no promise made to a client.** An acknowledged commit is durable because it is in the write-ahead
 * log, not because the data files were checkpointed: the bootstrap record is a *checkpoint pointer*, and anything
 * written after the last one is replayed from the WAL on restart. What the interval does cost is WAL retention and
 * restart replay time, both bounded by the interval itself.
 *
 * Two mechanisms keep the fence honest:
 *
 * - Handles **register themselves** through {@link PendingSyncRegistry} rather than being enumerated here, so the set
 *   of files owing a force cannot drift away from the set actually written.
 * - {@link #forcePendingSyncs()} is called from the one place that writes a bootstrap record, so the ordering
 *   invariant - *nothing may point at bytes that are not yet durable* - holds for every caller that writes one, not
 *   only for trunk rounds.
 *
 * A checkpoint is normally driven by a trunk round noticing the interval has elapsed. A **ticker** covers the case
 * the round-driven trigger structurally cannot: a system that falls silent right after a deferred round would
 * otherwise leave those changes uncheckpointed until the next write arrived, which may be never. The ticker is
 * a self-arming one-shot rather than a fixed-rate task, so an idle catalog fires exactly one timer after the last
 * transaction and then stops waking up.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
public class CheckpointCoordinator implements PendingSyncRegistry, Closeable {
	/**
	 * Name of the catalog this coordinator checkpoints - used for logging and observability.
	 */
	private final String catalogName;
	/**
	 * Minimal delay between two checkpoints, in milliseconds. Always positive - the persistence service does not
	 * create a coordinator at all when checkpointing is configured to happen at the end of every round.
	 */
	private final long checkpointIntervalMillis;
	/**
	 * Performs the checkpoint tail - writes the bootstrap record for the last applied version and advances the WAL
	 * processing marker. Supplied by the owning persistence service, which holds the state those steps need.
	 */
	private final Runnable checkpointAction;
	/**
	 * Handles that have written bytes reaching no further than the operating system page cache.
	 *
	 * Concurrent because the read path forces a soft flush when a caller reads a record still sitting in the write
	 * buffer, so entries arrive from request threads as well as from the thread driving trunk incorporation.
	 */
	private final Set<WriteOnlyHandle> pendingHandles = ConcurrentHashMap.newKeySet();
	/**
	 * Serialises a ticker-driven checkpoint against the trunk round's own end-of-round processing. Both advance the
	 * catalog offset index and may write a bootstrap record, and a bootstrap record naming version `V` must not be
	 * built from a descriptor that already contains version `V+1`.
	 *
	 * **Owned by the persistence service and handed in**, because the state it guards belongs to that service rather
	 * than to this class. Sharing one instance is what lets the round lock unconditionally while this class locks
	 * around its own bookkeeping.
	 *
	 * This is a leaf lock - everything taken beneath it (write handle locks, the bootstrap write lock, the WAL lock)
	 * is taken in the same order on both paths, and nothing beneath it reaches back here.
	 */
	private final ReentrantLock checkpointLock;
	/**
	 * The ticker that settles the debt of a catalog which went silent after a deferred round.
	 *
	 * A {@link DelayedAsyncTask} rather than a bare scheduler call so that it is observable alongside every other
	 * background task, and because its own guard - arm once, ignore further requests while armed - is exactly the
	 * one-shot semantics wanted here. The task pauses itself after each run, so the next deferred round re-arms it.
	 */
	private final DelayedAsyncTask ticker;
	/**
	 * Set once {@link #close()} has run. A {@link DelayedAsyncTask} refuses to be scheduled after it is closed, and
	 * a round finishing during shutdown must not be turned into a failed transaction by that refusal - nothing is
	 * lost, because closing forces everything still pending anyway.
	 */
	private final AtomicBoolean closed = new AtomicBoolean();
	/**
	 * Timestamp (epoch millis) at which the last checkpoint completed. Seeded at construction so that the first
	 * round after start-up is not immediately treated as overdue.
	 */
	private volatile long lastCheckpointCompletedAtMillis = System.currentTimeMillis();
	/**
	 * Timestamp (epoch millis) of the first round that deferred its checkpoint since the last completed checkpoint,
	 * or 0 when none is owed. This is the reference point for the fence depth - how long the oldest uncheckpointed
	 * change waited for the device.
	 */
	private volatile long checkpointOwedSinceMillis;
	/**
	 * What every {@link #forcePendingSyncs()} since the last completed checkpoint added up to, drained by the next
	 * one that completes.
	 *
	 * Accumulated rather than overwritten, and atomic rather than two fields, because the fence is reached from more
	 * threads than the checkpoint path: it sits inside the one method that writes a bootstrap record, so a schema
	 * update, a restore, a rename or a compaction reaches it without holding {@link #checkpointLock}. Overwriting
	 * would let one of those publish its numbers as the checkpoint's own, and two separate fields could be read as a
	 * mismatched pair. Summing is also the more truthful measure: an interval legitimately contains several forces,
	 * and their total is what the device actually cost.
	 */
	private final AtomicReference<ForceStats> forceStats = new AtomicReference<>(ForceStats.EMPTY);
	/**
	 * The failure that broke checkpointing, if any.
	 *
	 * A failed checkpoint covers every round since the previous one and later rounds are already writing behind it,
	 * so there is no single client to hand the exception to and no way to make the acknowledgements already given
	 * true again. Recorded here so the owning persistence service can refuse further work rather than keep
	 * acknowledging commits it can never checkpoint.
	 */
	private final AtomicReference<Throwable> failure = new AtomicReference<>();
	/**
	 * What the last completed checkpoint cost, retained for the `DURABILITY` statistics component.
	 *
	 * **A single reference rather than four fields, deliberately.** The four figures describe *one* checkpoint, and
	 * a statistics read that took them field by field could pair a cadence from one checkpoint with a fence depth
	 * from the next - a combination that never happened, which is exactly what an operator would then try to explain.
	 * One volatile write publishes them together. Same reasoning as the non-flushed block in `VolatileValues`.
	 *
	 * The figures are otherwise unobtainable after the fact: {@link #forceStats} is drained by the checkpoint that
	 * reports it, so without this they exist only inside the observability event.
	 */
	private volatile CheckpointStats lastCheckpoint = CheckpointStats.NONE;
	/**
	 * How many checkpoints have completed since this coordinator was constructed. Process-scoped, like the figures
	 * above - see {@link #countingSince}.
	 */
	private final AtomicLong checkpointsCompleted = new AtomicLong();
	/**
	 * The instant {@link #checkpointsCompleted} started from zero, which is what makes it readable: the counter is
	 * not persisted, so a client that reads it as "checkpoints this catalog has ever taken" is wrong by everything
	 * that happened before this coordinator was built.
	 */
	private final OffsetDateTime countingSince = OffsetDateTime.now();

	/**
	 * Creates a coordinator that checkpoints no more often than the given interval.
	 *
	 * @param catalogName             name of the catalog being checkpointed, for logging and observability
	 * @param checkpointIntervalMillis minimal delay between two checkpoints; must be positive, because
	 *                                "checkpoint at the end of every round" is expressed by not creating
	 *                                a coordinator at all rather than by an interval of zero
	 * @param scheduler               scheduler the ticker is armed on
	 * @param checkpointLock          lock owned by the persistence service, guarding the state both a round and
	 *                                a checkpoint touch; shared rather than created here so both paths take one lock
	 * @param checkpointAction        publishes the bootstrap record a round prepared and advances the write-ahead
	 *                                log marker; supplied by the persistence service, which holds that state
	 */
	public CheckpointCoordinator(
		@Nonnull String catalogName,
		long checkpointIntervalMillis,
		@Nonnull Scheduler scheduler,
		@Nonnull ReentrantLock checkpointLock,
		@Nonnull Runnable checkpointAction
	) {
		if (checkpointIntervalMillis <= 0) {
			throw new GenericEvitaInternalError(
				"Checkpoint coordinator requires a positive interval, got " + checkpointIntervalMillis +
					" - checkpointing at the end of every round is expressed by not creating a coordinator at all!"
			);
		}
		this.catalogName = catalogName;
		this.checkpointIntervalMillis = checkpointIntervalMillis;
		this.checkpointLock = checkpointLock;
		this.checkpointAction = checkpointAction;
		this.ticker = new DelayedAsyncTask(
			catalogName,
			"Catalog checkpoint",
			scheduler,
			this::runTickerCheckpoint,
			checkpointIntervalMillis,
			TimeUnit.MILLISECONDS,
			// two checkpoints are never wanted closer together than the configured interval, which is the same bound
			// the round-driven trigger applies
			checkpointIntervalMillis
		);
	}

	@Override
	public void noteSyncPending(@Nonnull WriteOnlyHandle handle) {
		this.pendingHandles.add(handle);
	}

	/**
	 * Forces every file written since the last checkpoint to the physical device.
	 *
	 * Called immediately before a bootstrap record is written, which is what makes the ordering invariant structural:
	 * a record pointing into a data file cannot become durable before the bytes it addresses have.
	 *
	 * The set is snapshotted, forced and then removed by identity rather than cleared. A handle written between the
	 * snapshot and the force must stay pending - clearing would drop it and leave a durable pointer to bytes that
	 * were never synced, whereas forcing it again in the next checkpoint costs a redundant device flush (measured at
	 * ~0.5 ms) and is harmless.
	 */
	public void forcePendingSyncs() {
		// deliberately no early return on an empty set: the counters below are what the next completed checkpoint
		// reports, and skipping the update would let it publish the numbers of some earlier unrelated force - a
		// compaction's, say - as its own
		final long startNanos = System.nanoTime();
		final WriteOnlyHandle[] snapshot = this.pendingHandles.toArray(WriteOnlyHandle[]::new);
		for (final WriteOnlyHandle handle : snapshot) {
			handle.forceDurable();
		}
		// remove exactly what was forced; anything registered in the meantime stays owed
		for (final WriteOnlyHandle handle : snapshot) {
			this.pendingHandles.remove(handle);
		}
		this.forceStats.accumulateAndGet(
			new ForceStats(snapshot.length, (System.nanoTime() - startNanos) / 1_000_000L),
			ForceStats::plus
		);
	}

	/**
	 * Tells whether the checkpoint interval has elapsed since the last completed checkpoint.
	 *
	 * @return true when the round that is finishing should checkpoint rather than defer
	 */
	public boolean isCheckpointDue() {
		return System.currentTimeMillis() - this.lastCheckpointCompletedAtMillis >= this.checkpointIntervalMillis;
	}

	/**
	 * Records that a round chose not to checkpoint, and arms the ticker so the change still reaches the device if no
	 * further round ever arrives.
	 */
	public void noteCheckpointDeferred() {
		this.checkpointLock.lock();
		try {
			if (this.checkpointOwedSinceMillis == 0L) {
				this.checkpointOwedSinceMillis = System.currentTimeMillis();
			}
			if (!this.closed.get()) {
				this.ticker.schedule();
			}
		} finally {
			this.checkpointLock.unlock();
		}
	}

	/**
	 * Records that a checkpoint completed: restarts the interval and reports the two intervals that describe how the
	 * fence is behaving.
	 *
	 * An armed ticker is deliberately left armed. It will fire, find nothing owed and pause itself, which is the
	 * normal outcome on a catalog busy enough to keep checkpointing inline.
	 */
	public void noteCheckpointCompleted() {
		this.checkpointLock.lock();
		try {
			final long now = System.currentTimeMillis();
			final long cadenceMillis = now - this.lastCheckpointCompletedAtMillis;
			// how long the OLDEST change covered by this checkpoint waited for the device; zero when the round
			// checkpointed inline and nothing was ever owed
			final long fenceDepthMillis = this.checkpointOwedSinceMillis == 0L ?
				0L : now - this.checkpointOwedSinceMillis;
			this.lastCheckpointCompletedAtMillis = now;
			this.checkpointOwedSinceMillis = 0L;
			// emitted here rather than at the end of the round that queued the work: the whole point of the interval
			// is that those two moments are no longer the same, so reporting at the round would report nothing about
			// durability
			final ForceStats forced = this.forceStats.getAndSet(ForceStats.EMPTY);
			// retained before the event is emitted and from the same locals, so the DURABILITY component and the
			// observability event can never describe different checkpoints
			this.lastCheckpoint = new CheckpointStats(
				cadenceMillis, fenceDepthMillis, forced.files(), forced.durationMillis(), now
			);
			this.checkpointsCompleted.incrementAndGet();
			new CatalogCheckpointEvent(this.catalogName)
				.finish(cadenceMillis, fenceDepthMillis, forced.files(), forced.durationMillis())
				.commit();
			if (log.isDebugEnabled()) {
				log.debug(
					"Catalog `{}` checkpointed - cadence {} ms, fence depth {} ms.",
					this.catalogName, cadenceMillis, fenceDepthMillis
				);
			}
		} finally {
			this.checkpointLock.unlock();
		}
	}

	/**
	 * Checkpoints immediately if one is owed, regardless of whether the interval has elapsed.
	 *
	 * This is how the operations that *require* a fully checkpointed catalog get one - notably integrity
	 * verification, which is followed by obsolete-file purging: purging write-ahead log files covering versions that
	 * were never checkpointed would discard the only record of them.
	 *
	 * Unlike the ticker, failures propagate - these callers have somewhere to report to.
	 */
	public void checkpointIfOwed() {
		this.checkpointLock.lock();
		try {
			if (this.checkpointOwedSinceMillis != 0L) {
				this.checkpointAction.run();
			}
		} finally {
			this.checkpointLock.unlock();
		}
	}

	/**
	 * Returns the failure that broke checkpointing, if any. Once set, the catalog can no longer make its data files
	 * durable and must stop acknowledging commits.
	 *
	 * @return the failure, or null while checkpointing is healthy
	 */
	@Nullable
	public Throwable getFailure() {
		return this.failure.get();
	}

	@Override
	public void close() {
		if (this.closed.compareAndSet(false, true)) {
			try {
				this.ticker.close();
			} catch (IOException ex) {
				throw new GenericEvitaInternalError(
					"Failed to close the checkpoint ticker of catalog `" + this.catalogName + "`!",
					"Failed to close the checkpoint ticker!",
					ex
				);
			}
		}
	}

	/**
	 * Body of the ticker: checkpoints the catalog if one is still owed by the time the timer fires.
	 *
	 * A round may have checkpointed in the meantime, in which case there is nothing to do - the timer is simply
	 * stale, which is the normal outcome on a busy catalog.
	 *
	 * @return always -1, which pauses the task: it is re-armed by the next round that defers, so an idle catalog
	 * stops waking up entirely once its debt is settled
	 */
	private long runTickerCheckpoint() {
		this.checkpointLock.lock();
		try {
			if (this.failure.get() == null) {
				checkpointIfOwed();
			}
		} catch (Throwable ex) {
			// there is no client waiting on this checkpoint to throw into - the transactions it covers were
			// acknowledged long ago. Record it so the catalog can stop acknowledging commits it cannot checkpoint,
			// and make sure it is visible rather than swallowed on a scheduler thread.
			this.failure.compareAndSet(null, ex);
			log.error(
				"Checkpoint of catalog `{}` failed - the data files can no longer be made durable!",
				this.catalogName, ex
			);
		} finally {
			this.checkpointLock.unlock();
		}
		return -1L;
	}

	/**
	 * Describes how this catalog's checkpoint fence is behaving, for the `DURABILITY` statistics component.
	 *
	 * All figures come from the last completed checkpoint, read off a single reference so they cannot straddle two of
	 * them. Free of file-system access - everything here is an in-memory read.
	 *
	 * @return the snapshot
	 */
	@Nonnull
	public DurabilitySnapshot describeDurability() {
		final CheckpointStats last = this.lastCheckpoint;
		return new DurabilitySnapshot(
			this.checkpointIntervalMillis,
			last.cadenceMillis(),
			last.fenceDepthMillis(),
			last.filesForced(),
			last.forceDurationMillis(),
			this.checkpointsCompleted.get(),
			last.completedAtMillis() == 0L ?
				null :
				OffsetDateTime.ofInstant(Instant.ofEpochMilli(last.completedAtMillis()), ZoneId.systemDefault()),
			this.countingSince
		);
	}

	/**
	 * What one completed checkpoint cost. Retained as a whole so a statistics read cannot mix figures from two
	 * different checkpoints - see {@link #lastCheckpoint}.
	 *
	 * @param cadenceMillis       time since the previous completed checkpoint
	 * @param fenceDepthMillis    how long the oldest change it covered waited for the device
	 * @param filesForced         number of files it forced
	 * @param forceDurationMillis wall-clock time those forces took
	 * @param completedAtMillis   when it completed, or `0` when none has completed yet
	 */
	private record CheckpointStats(
		long cadenceMillis,
		long fenceDepthMillis,
		int filesForced,
		long forceDurationMillis,
		long completedAtMillis
	) {
		private static final CheckpointStats NONE = new CheckpointStats(0L, 0L, 0, 0L, 0L);
	}

	/**
	 * Device-flush work accumulated since the last completed checkpoint.
	 *
	 * @param files          number of files forced
	 * @param durationMillis wall-clock time those forces took, in milliseconds
	 */
	private record ForceStats(int files, long durationMillis) {
		private static final ForceStats EMPTY = new ForceStats(0, 0L);

		/**
		 * Adds another force's work to this one.
		 *
		 * @param other the force to add
		 * @return the combined totals
		 */
		@Nonnull
		ForceStats plus(@Nonnull ForceStats other) {
			return new ForceStats(this.files + other.files, this.durationMillis + other.durationMillis);
		}
	}

}
