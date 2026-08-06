/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

import io.evitadb.core.catalog.CatalogConsumersListener;
import io.evitadb.core.executor.DelayedAsyncTask;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.EntityTypePrimaryKeyAndFileIndex;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.wal.AbstractMutationLog.WalPurgeCallback;
import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.IOUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.CATALOG_FILE_SUFFIX;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.ENTITY_COLLECTION_FILE_SUFFIX;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getEntityPrimaryKeyAndIndexFromEntityCollectionFileName;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getIndexFromCatalogFileName;
import static java.util.Optional.ofNullable;

/**
 * This class is responsible for clearing all the files that were made obsolete either by deleting or renaming to
 * different names. We can't remove the files right away because there might be some active sessions that are still
 * reading the files. We need to wait until all the active sessions are done reading the files and then we can remove
 * the files.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class ObsoleteFileMaintainer implements CatalogConsumersListener, Closeable {
	/**
	 * When time travel is enabled the files are not removed immediately but are kept until the WAL history is purged.
	 */
	private final boolean timeTravelEnabled;
	/**
	 * Folder where the catalog files are stored.
	 */
	private final Path catalogStoragePath;
	/**
	 * Asynchronous task that purges obsolete files.
	 */
	private final DelayedAsyncTask purgeTask;
	/**
	 * List of files that are maintained until they are no longer used and can be purged.
	 */
	private final List<MaintainedFile> maintainedFiles = new CopyOnWriteArrayList<>();
	/**
	 * The first catalog version whose file was added to the maintained files. This variable optimizes the number of
	 * purge task executions.
	 */
	private final AtomicLong firstCatalogVersion = new AtomicLong(0L);
	/**
	 * The last catalog version whose file was added to the maintained files. This variable guards the monotonicity of
	 * the maintained files list.
	 */
	private final AtomicLong lastCatalogVersion = new AtomicLong(0L);
	/**
	 * The catalog version that is no longer used and all files with the version less or equal to this value can be
	 * safely purged.
	 */
	private final AtomicLong lastKnownMinimalActiveVersion = new AtomicLong(0L);
	/**
	 * The minimal catalog version that is still referenced by an active reader (open session) or writer. This floor is
	 * tracked even when time travel is enabled (where it does not gate the {@link #purgeTask}) so that the WAL-rotation
	 * purge can be clamped by it and never delete a catalog data file that an active reader still needs.
	 */
	private final AtomicLong activeReaderFloor = new AtomicLong(0L);
	/**
	 * Catalog versions explicitly pinned by a live consumer, to the number of consumers holding each. Unlike
	 * {@link #activeReaderFloor} this can point into the past - a point-in-time backup pins the version of the
	 * bootstrap record it copies - and it drops back as consumers finish.
	 */
	private final ConcurrentHashMap<Long, Integer> pinnedCatalogVersions = CollectionUtils.createConcurrentHashMap(16);
	/**
	 * Number of consumers currently reading the catalog folder **by listing it** rather than by following a bootstrap
	 * record. While any of them is running no file may be removed from the folder at all.
	 *
	 * This is deliberately not expressed as a pinned catalog version, because the thing being protected is not a
	 * version: a full backup copies whatever `Files.walk` finds, so it reads files that no bootstrap record points at -
	 * and during warm-up it reads little else, since every flush rewrites the bootstrap down to a single record and
	 * strands the generation before it. No value of {@link #getRetentionFloor()} can describe that need.
	 */
	private final AtomicInteger directoryReadHolds = new AtomicInteger();
	/**
	 * Makes taking a hold mutually exclusive with a deletion pass, which the counter alone cannot do.
	 *
	 * Reading {@link #directoryReadHolds} and then deleting is check-then-act: a hold taken after the count was read
	 * as zero but before the files were unlinked observes a folder that is already being emptied. Every deleter
	 * therefore runs its whole pass under this lock, and acquisition takes it too.
	 *
	 * Deleters use {@code tryLock} and give up rather than wait - all of them are opportunistic housekeeping that the
	 * last release reschedules, and one of them ({@link #removeFileWhenNotUsed}) runs on the commit thread while the
	 * catalog persistence service lock is held, where blocking would invert the lock order.
	 */
	private final ReentrantLock directoryAccessLock = new ReentrantLock();
	/**
	 * Files the warm-up eager path had to hand over because the folder was held at the moment it wanted to delete.
	 *
	 * This is the one deleter with no driver of its own to come back on - it fires inline from a compaction and is
	 * never retried - so what it defers has to be parked somewhere until a pass can take it. Drained by the next
	 * deletion pass that gets the folder to itself, and by {@link #close()} so that nothing is left on disk.
	 */
	private final List<MaintainedFile> deferredEagerPurges = new CopyOnWriteArrayList<>();
	/**
	 * Set when a sweep of the files no retained bootstrap record can reach had to be given up because another pass held
	 * the folder, so that the next pass which does get it performs the sweep on its behalf.
	 *
	 * The sweep is the one deletion pass whose driver is outside this class - it is triggered by the size guard and by
	 * the write-ahead log purge - so a round it loses to a *competing deleter* is otherwise simply skipped while the
	 * horizon advances as if it had reclaimed. Losing to a directory **hold** is different and needs no flag: releasing
	 * the hold drives the guard again. Setting it in both cases costs one idempotent sweep and removes the distinction
	 * from the reasoning.
	 */
	private final AtomicBoolean pendingUnreachableSweep = new AtomicBoolean();
	/**
	 * The supplier of the catalog header and bootstrap record for the oldest catalog version that is still retained on
	 * disk (the first record in the bootstrap file). The catalog data file referenced by this bootstrap is, by
	 * definition, the lowest index that is kept and therefore is guaranteed not to have been purged.
	 */
	private final Supplier<DataFilesBulkInfo> oldestDataFilesInfoSupplier;
	/**
	 * Flag indicating whether the maintainer has been closed.
	 */
	private final AtomicBoolean closed = new AtomicBoolean(false);

	public ObsoleteFileMaintainer(
		@Nonnull String catalogName,
		@Nonnull Scheduler scheduler,
		@Nonnull Path catalogStoragePath,
		boolean timeTravelEnabled,
		@Nonnull Supplier<DataFilesBulkInfo> oldestDataFilesInfoSupplier
	) {
		this.catalogStoragePath = catalogStoragePath;
		this.timeTravelEnabled = timeTravelEnabled;
		this.oldestDataFilesInfoSupplier = oldestDataFilesInfoSupplier;
		// purge task is not present when time travel is enabled
		// in this situation the files are removed in the synchronous manner when the WAL history is purged
		this.purgeTask = new DelayedAsyncTask(
			catalogName, "Obsolete files purger",
			scheduler,
			this::purgeObsoleteFiles,
			0L, TimeUnit.MILLISECONDS
		);
	}

	/**
	 * Removes a file when it is no longer used. The file is associated with a catalog version and a path.
	 *
	 * @param catalogVersion the catalog version associated with the file
	 * @param path           the path of the file
	 * @param removalLambda  the lambda function to be executed when the file is removed
	 */
	public void removeFileWhenNotUsed(
		long catalogVersion,
		@Nonnull Path path,
		@Nonnull Runnable removalLambda
	) {
		assertNotClosed();
		final MaintainedFile fileToMaintain = new MaintainedFile(catalogVersion, path, removalLambda);
		if (catalogVersion <= 0L) {
			// version 0L represents catalog in WARM-UP (non-transactional) state where we apply all changes immediately
			// - but "immediately" still has to yield to a consumer reading the folder. This is the *other* door into
			// `purgeFile`, and warm-up is exactly when a backup is most exposed: it holds the folder precisely because
			// every flush strands the generation before it. Deferring costs a delayed unlink and a persistence service
			// that stays registered a little longer; not deferring unlinks a file a backup is copying
			if (runWithDirectoryExclusivity(() -> purgeFile(fileToMaintain)) != DeletionPassOutcome.RAN) {
				this.deferredEagerPurges.add(fileToMaintain);
				// the entry is parked *before* the flag is read, which is what makes this hand-over total: a `close()`
				// that has not yet flipped the flag when we read it must flip it afterwards, and its own drain then
				// finds this entry; a `close()` that already flipped it may have drained before the park, so the
				// parking thread drains instead. Both draining is harmless - the removal is the claim token
				if (this.closed.get()) {
					drainDeferredEagerPurges();
				} else {
					this.purgeTask.trySchedule();
				}
			}
		} else {
			// if the first catalog version is not set, set it to the current catalog version
			this.firstCatalogVersion.compareAndExchange(0, catalogVersion);
			this.lastCatalogVersion.accumulateAndGet(
				catalogVersion,
				(previous, updatedValue) -> {
					Assert.isPremiseValid(previous <= updatedValue, "Catalog version must be increasing");
					return updatedValue;
				}
			);
			this.maintainedFiles.add(fileToMaintain);
		}
	}

	/**
	 * Updates the catalog version that is no longer used by any active session. On every call it records the
	 * active-reader floor (the minimal still-active catalog version, monotonically increasing), which is later used
	 * to clamp the WAL-rotation purge so that files still needed by an active reader are never deleted. Only the
	 * immediate, synchronous file purge is suppressed when time travel is enabled - in that mode purging is driven
	 * later by the {@link ObsoleteWalPurgeCallback} callback when WAL files are removed.
	 *
	 * @param lastKnownMinimalActiveVersionRead the minimal catalog version that is still being read from
	 * @param lastKnownMinimalActiveVersionWritten the minimal catalog version that is still being written on top of
	 */
	@Override
	public void catalogConsumersLeft(
		long lastKnownMinimalActiveVersionRead,
		long lastKnownMinimalActiveVersionWritten
	) {
		assertNotClosed();
		final long lastKnownMinimalActiveVersion = Math.min(
			lastKnownMinimalActiveVersionRead,
			lastKnownMinimalActiveVersionWritten
		);
		// always record the active-reader floor (monotonically increasing) - even with time travel enabled it is used
		// to clamp the WAL-rotation purge so that files still needed by an active reader are never deleted
		if (lastKnownMinimalActiveVersion > 0L) {
			this.activeReaderFloor.accumulateAndGet(
				lastKnownMinimalActiveVersion,
				Math::max
			);
		}
		// immediate file purging on catalog version exchange is not used when time travel is enabled
		if (!this.timeTravelEnabled) {
			if (lastKnownMinimalActiveVersion > 0L && this.firstCatalogVersion.get() < lastKnownMinimalActiveVersion) {
				this.lastKnownMinimalActiveVersion.accumulateAndGet(
					lastKnownMinimalActiveVersion,
					Math::max
				);
				this.purgeTask.schedule();
			}
		}
	}

	/**
	 * Returns the minimal catalog version that is still referenced by an active reader or writer, or {@code 0} when no
	 * active version has been observed yet. The WAL-rotation purge is clamped by this floor so that a catalog data file
	 * holding records for a version with active readers is never deleted.
	 *
	 * @return the active-reader floor (minimal active catalog version), or {@code 0} when unknown
	 */
	public long getActiveReaderFloor() {
		return this.activeReaderFloor.get();
	}

	/**
	 * Records that a consumer started using the given catalog version. The version is held against reclamation until
	 * as many {@link #catalogVersionReleased(long)} calls have arrived as pins.
	 *
	 * Counting rather than tracking a single minimum is what makes this race-free: two consumers pinning and releasing
	 * different versions concurrently each touch their own map entry, whereas any single accumulated value can be
	 * overwritten by a competing update and silently drop the lower pin - which is the exact failure this guards.
	 *
	 * @param catalogVersion the catalog version that must remain readable
	 */
	public void catalogVersionPinned(long catalogVersion) {
		this.pinnedCatalogVersions.merge(catalogVersion, 1, Integer::sum);
	}

	/**
	 * Releases one pin previously recorded by {@link #catalogVersionPinned(long)}.
	 *
	 * @param catalogVersion the catalog version that no longer needs to remain readable
	 */
	public void catalogVersionReleased(long catalogVersion) {
		// the flag has to be set inside the remapping function - `computeIfPresent` returns `null` both for a version
		// that was never pinned and for the last pin of one that was, and only the first of those is a defect
		final AtomicBoolean paired = new AtomicBoolean();
		this.pinnedCatalogVersions.computeIfPresent(
			catalogVersion,
			(version, pinCount) -> {
				paired.set(true);
				return pinCount <= 1 ? null : pinCount - 1;
			}
		);
		if (!paired.get()) {
			// an unpaired release means the pin and its release did not meet - a double release, or a consumer whose
			// two calls resolved to different maintainer instances across a catalog replacement. Neither can be
			// repaired from here, but both leave a phantom pin freezing retention on some *other* instance, and that
			// is invisible unless it is said out loud. Not an exception: this runs on task tear-down paths that must
			// complete
			log.warn(
				"Catalog version {} was released without a matching pin - retention on another catalog instance may " +
					"be held by a pin that will never be given back.",
				catalogVersion
			);
		}
	}

	/**
	 * Returns the lowest catalog version below which nothing may be reclaimed - the minimum of the active-reader floor
	 * and of every version explicitly pinned by a consumer, or {@code -1} when neither is known.
	 *
	 * The two are combined rather than one replacing the other because they answer different questions: the floor is
	 * the newest *departure* report and only ever rises, while the pins are the versions consumers hold **right now**
	 * and can sit arbitrarily far in the past.
	 *
	 * The absent case is {@code -1} rather than {@code 0}, because **{@code 0} is a pinnable version**: it is the one
	 * a catalog goes live with, and it is what a full backup pins whenever no history has been given up yet. Reporting
	 * it as "nothing is held" - which is what the active-reader floor alone means by {@code 0} - would let a purge run
	 * unclamped over the files that very consumer is reading.
	 *
	 * @return the retention floor, or {@code -1} when nothing is known to be in use
	 */
	public long getRetentionFloor() {
		final long readerFloor = this.activeReaderFloor.get();
		long pinFloor = Long.MAX_VALUE;
		for (Long pinnedVersion : this.pinnedCatalogVersions.keySet()) {
			if (pinnedVersion < pinFloor) {
				pinFloor = pinnedVersion;
			}
		}
		if (pinFloor == Long.MAX_VALUE) {
			// no pins at all - the departure-driven reader floor is all there is, and it uses 0 for "no reader"
			return readerFloor > 0L ? readerFloor : -1L;
		}
		return readerFloor > 0L ? Math.min(readerFloor, pinFloor) : pinFloor;
	}

	/**
	 * Creates the WAL purge callback that is used to remove all files that are no longer used. The callback is used
	 * when the WAL history is purged.
	 *
	 * @return the WAL purge callback
	 */
	@Nonnull
	public WalPurgeCallback createWalPurgeCallback() {
		assertNotClosed();
		if (this.timeTravelEnabled) {
			return new ObsoleteWalPurgeCallback(
				this::purgeMaintainedFilesOlderThan,
				this::reclaimUnreachableFiles
			);
		} else {
			return WalPurgeCallback.NO_OP;
		}
	}

	@Override
	public void close() {
		if (this.closed.compareAndSet(false, true)) {
			IOUtils.closeQuietly(this.purgeTask::close);
			// closing the task is `cancel(false)`, so a pass that is already executing keeps going. Taking the lock is
			// what stops the loop below from walking the same lists alongside it and running a removal lambda a second
			// time - and a lambda that closes a persistence service resolves *closest below* the version it is given,
			// so the second run does not no-op, it closes a different service that is still registered.
			//
			// Waiting here is safe, and this is the one deleter allowed to: `close()` holds nothing else and a pass is
			// bounded. The constraint that makes it true is that nobody calls this while holding the catalog
			// persistence service lock, which the removal lambdas take - verified at every call site.
			this.directoryAccessLock.lock();
			try {
				// clear all files immediately - the catalog is going away, so nothing that could still be reading them
				// has anywhere to read them from. This is the one deleter that deliberately ignores the directory
				// **hold** (it still takes the lock, see above): a consumer walking a folder whose catalog is closing
				// has already lost, and it finds out loudly. See the deleter matrix in
				// `documentation/adr/2026-08-06-time-travel-disk-budget.md`
				this.lastKnownMinimalActiveVersion.set(0L);
				final List<MaintainedFile> filesToPurge = List.copyOf(this.maintainedFiles);
				// removed before they are purged, so a file handed over concurrently is not dropped unpurged by a
				// blanket `clear()` of a list that has grown since the snapshot was taken
				this.maintainedFiles.removeAll(filesToPurge);
				for (MaintainedFile maintainedFile : filesToPurge) {
					purgeFile(maintainedFile);
				}
				// anything the eager warm-up path parked would otherwise stay on disk with nothing left to collect it
				drainDeferredEagerPurges();
			} finally {
				this.directoryAccessLock.unlock();
			}
			// a warm-up purge that lost the lock to the block above parks *after* it drained - claim whatever landed
			// there in the meantime. Anything arriving after this point sees `closed` and drains itself
			drainDeferredEagerPurges();
		}
	}

	/**
	 * Runs and forgets every purge the warm-up eager path parked.
	 *
	 * The removal from the list is the claim token and its result is checked: `close()` and a concurrently parking
	 * commit thread can both reach here for the same entry, and a removal lambda that runs twice closes a persistence
	 * service that is still registered, because
	 * {@link DefaultCatalogPersistenceService#removeCatalogPersistenceServiceForVersion(long)} resolves the closest
	 * service at or **below** the version it is given.
	 */
	private void drainDeferredEagerPurges() {
		for (MaintainedFile deferredPurge : this.deferredEagerPurges) {
			if (this.deferredEagerPurges.remove(deferredPurge)) {
				try {
					purgeFile(deferredPurge);
				} catch (RuntimeException ex) {
					// this is somebody else's work being carried by whichever pass got the folder - including the
					// commit thread - so a failure is reported here rather than handed to a caller that has nothing
					// to do with it. Deliberately **not** re-parked: the removal lambda has already run, and running
					// it a second time closes a persistence service that is still registered
					log.error("Failed to purge the deferred obsolete file `{}`", deferredPurge.path(), ex);
				}
			}
		}
	}

	/**
	 * Asserts that the maintainer is not closed.
	 */
	private void assertNotClosed() {
		Assert.isPremiseValid(!this.closed.get(), "ObsoleteFileMaintainer is closed");
	}

	/**
	 * Method is called from {@link ObsoleteWalPurgeCallback} that is invoked in case time travel is enabled when
	 * the bootstrap file gets reduced and old history is purged. This method first purges all maintained files and calls
	 * their respective removal lambdas.
	 */
	private void purgeMaintainedFilesOlderThan(long firstActiveCatalogVersion) {
		this.lastKnownMinimalActiveVersion.set(firstActiveCatalogVersion);
		this.purgeObsoleteFiles();
	}

	/**
	 * Method is called from {@link #purgeTask} to remove all files that are no longer used. The method iterates over
	 * all maintained files and removes the files whose catalog version is less or equal to the last catalog version
	 * that is no longer used.
	 *
	 * @return the next scheduled time for the purge task (always -1L - i.e. do not schedule again)
	 */
	private long purgeObsoleteFiles() {
		// with time travel off this is the deleter that actually unlinks a retired data file, and it is driven purely
		// by the departure-reported active version - it never looks at pins. A consumer walking the folder is
		// therefore invisible to it, which was masked only by a full backup registering itself as if it were a
		// read-write session. The hold is what makes that registration removable.
		// Nothing is lost by deferring: `releaseDirectoryReadHold` reschedules this task
		if (runWithDirectoryExclusivity(this::purgeObsoleteFilesUnguarded) == DeletionPassOutcome.LOCK_CONTENDED) {
			// a hold turning this pass away is rescheduled by the last release; another *deleter* turning it away is
			// rescheduled by nobody, and this task is the only driver these files have
			this.purgeTask.trySchedule();
		}
		return -1L;
	}

	/**
	 * The body of {@link #purgeObsoleteFiles()}, to be run only with the folder held exclusively.
	 */
	private void purgeObsoleteFilesUnguarded() {
		final long lastKnownMinimalActiveVersion = this.lastKnownMinimalActiveVersion.get();
		/* TOBEDONE JNO - this is only for debugging purposes, we should rely on events instead */
		if (!this.maintainedFiles.isEmpty()) {
			log.info(
				"Purging obsolete files - last known minimal active version: {}\nFiles waiting for removal:\n{}",
				lastKnownMinimalActiveVersion,
				this.maintainedFiles.stream()
					.map(MaintainedFile::path)
					.map(Path::toString)
					.map(path -> "\t - " + path)
					.collect(Collectors.joining("\n"))
			);
		}
		final List<MaintainedFile> itemsToRemove = new LinkedList<>();
		long newFirstCatalogVersion = 0L;
		for (MaintainedFile maintainedFile : this.maintainedFiles) {
			if (maintainedFile.catalogVersion() < lastKnownMinimalActiveVersion) {
				purgeFile(maintainedFile);
				itemsToRemove.add(maintainedFile);
			} else {
				newFirstCatalogVersion = maintainedFile.catalogVersion();
				// the list is sorted by the catalog version, so we can break the loop
				break;
			}
		}
		this.maintainedFiles.removeAll(itemsToRemove);
		this.firstCatalogVersion.set(newFirstCatalogVersion);
	}

	/**
	 * Deletes every data file that no retained bootstrap record can reach any more.
	 *
	 * The threshold is not passed in - it is re-derived from the oldest record left in the bootstrap file, whose
	 * catalog data file is by definition the lowest index kept and therefore guaranteed to be present. That makes this
	 * method idempotent and independent of *how* the bootstrap file came to be trimmed, which is what lets the size
	 * guard call it directly: during warm-up every bootstrap record carries catalog version `0`, so a version-keyed
	 * entry point like {@link ObsoleteWalPurgeCallback#purgeFilesUpTo(long)} would refuse every call after the first.
	 */
	public void reclaimUnreachableFiles() {
		assertNotClosed();
		if (!this.timeTravelEnabled) {
			// without time travel nothing is ever left behind to reclaim - files go as soon as their last reader leaves
			return;
		}
		// "no retained bootstrap record reaches this file" is not the same as "nobody is reading it" - a consumer
		// that walks the folder reads files no record points at. It gives up nothing to wait: this is opportunistic
		// housekeeping and releasing the hold reschedules it.
		//
		// Note this gate is *not* the one protecting ordinary sessions, and must never be widened into one. A
		// session resolves its reads through the bootstrap record serving its version; the trim that decides which
		// records are retained is clamped by the retention floor, which includes that session's own pin; so every
		// file a session can reach is still reachable from a retained record, and this sweep deletes only files
		// that are not. Gating here on pins instead - which every session takes - stops reclamation for as long as
		// anything is connected, and during warm-up that is the whole of a bulk import
		if (runWithDirectoryExclusivity(this::sweepUnreachableFiles) != DeletionPassOutcome.RAN) {
			// unlike the maintained-file purge this sweep has no task of its own, so a round it gives up is otherwise
			// simply lost while the horizon advances as if it had reclaimed. Hand it to the purge task instead
			this.pendingUnreachableSweep.set(true);
			this.purgeTask.trySchedule();
		}
	}

	/**
	 * The body of {@link #reclaimUnreachableFiles()}, to be run only with the folder held exclusively.
	 */
	private void sweepUnreachableFiles() {
		reclaimFilesUnreachableFrom(this.oldestDataFilesInfoSupplier.get(), this.catalogStoragePath);
	}

	/**
	 * Runs a deletion pass with the catalog folder held exclusively, or reports that it must not run at all right now.
	 *
	 * This is the single gate every deleter that unlinks a file in the catalog folder goes through, and it is what
	 * makes {@link CatalogDirectoryReadHold} mean what its name says: a hold cannot be taken part-way through a pass,
	 * and a pass cannot start once a hold is open.
	 *
	 * The lock is only ever attempted, never waited on. Every caller is opportunistic work that the last release
	 * reschedules, and one of them runs on the commit thread underneath the catalog persistence service lock - which
	 * a deletion pass may itself need, so waiting here would invert the order.
	 *
	 * The two ways a pass can be turned away are reported apart, because only one of them has somebody to bring it
	 * back. A folder **hold** is rescheduled by its own release; losing the lock to a *competing deleter* is
	 * rescheduled by nothing, and the round would simply be skipped.
	 *
	 * @param deletionPass the pass to run while no consumer is reading the folder
	 * @return how the attempt ended
	 */
	@Nonnull
	private DeletionPassOutcome runWithDirectoryExclusivity(@Nonnull Runnable deletionPass) {
		if (!this.directoryAccessLock.tryLock()) {
			return DeletionPassOutcome.LOCK_CONTENDED;
		}
		try {
			if (this.directoryReadHolds.get() > 0) {
				return DeletionPassOutcome.FOLDER_HELD;
			}
			// whatever the eager warm-up path handed over goes first - it is the only deleter with no driver of its
			// own to bring it back, so this is where its work gets done
			drainDeferredEagerPurges();
			// and so does a sweep a competing pass turned away - see `pendingUnreachableSweep`
			if (this.pendingUnreachableSweep.compareAndSet(true, false)) {
				try {
					sweepUnreachableFiles();
				} catch (RuntimeException ex) {
					// borrowed work must not be able to fail its host. One of the passes that carries it is the
					// warm-up eager purge, which runs inline on the commit thread under the catalog persistence
					// service lock - and this sweep reads the bootstrap file and a catalog header, so it has real
					// I/O to fail at. Re-armed rather than dropped: the round it belongs to still has to happen
					this.pendingUnreachableSweep.set(true);
					log.error(
						"Failed to reclaim the unreachable files of catalog folder `{}` on behalf of another pass - " +
							"the sweep stays pending and the next pass will carry it.",
						this.catalogStoragePath, ex
					);
				}
			}
			deletionPass.run();
			return DeletionPassOutcome.RAN;
		} finally {
			this.directoryAccessLock.unlock();
		}
	}

	/**
	 * How an attempt to run a deletion pass with the catalog folder to itself ended.
	 */
	private enum DeletionPassOutcome {
		/**
		 * The pass ran with the folder held exclusively.
		 */
		RAN,
		/**
		 * A consumer is reading the folder. The last {@link #releaseDirectoryReadHold()} brings the work back.
		 */
		FOLDER_HELD,
		/**
		 * Another deletion pass held the lock. Nothing brings the work back on its own - the caller must.
		 */
		LOCK_CONTENDED
	}

	/**
	 * Records that a consumer started reading the catalog folder by listing it. Every acquisition must be matched by
	 * exactly one {@link #releaseDirectoryReadHold()}.
	 */
	void acquireDirectoryReadHold() {
		// taken under the same lock every deletion pass runs under, so the hold either predates a pass entirely or
		// waits for it to finish - it can never land in the middle of one. This is the only caller that waits, and it
		// can afford to: it runs while a backup task is being constructed, holding nothing else
		this.directoryAccessLock.lock();
		try {
			this.directoryReadHolds.incrementAndGet();
		} finally {
			this.directoryAccessLock.unlock();
		}
	}

	/**
	 * Releases one hold taken by {@link #acquireDirectoryReadHold()}. The last release reschedules the purge that the
	 * hold turned away - the deferred work would otherwise wait for the next unrelated event to drive it.
	 */
	void releaseDirectoryReadHold() {
		final boolean lastOne;
		this.directoryAccessLock.lock();
		try {
			lastOne = this.directoryReadHolds.decrementAndGet() == 0;
		} finally {
			this.directoryAccessLock.unlock();
		}
		if (lastOne) {
			// this also drains whatever the eager warm-up path parked while the folder was held - see
			// `runWithDirectoryExclusivity`. `trySchedule` rather than a `closed` check followed by `schedule`: a
			// hold is routinely given back on a task tear-down that runs after its catalog has been closed, and that
			// path must not be the one that throws
			this.purgeTask.trySchedule();
		}
	}

	/**
	 * Tells whether any consumer is currently reading the catalog folder by listing it.
	 *
	 * Not consulted by the deleters - they go through {@link #runWithDirectoryExclusivity(Runnable)}, which has to
	 * hold the lock while it decides. This exists so that a hold which was leaked rather than released can be seen
	 * at all: its only symptom is reclamation quietly never happening again.
	 *
	 * @return true when at least one consumer is reading the folder
	 */
	boolean isCatalogDirectoryHeld() {
		return this.directoryReadHolds.get() > 0;
	}

	/**
	 * Deletes every catalog and entity collection data file that the given oldest retained generation cannot reach.
	 * The survivor rules are shared with the size guard through {@link TimeTravelRetention} so the two cannot drift
	 * apart - one predicts the bytes the other actually reclaims.
	 *
	 * @param activeFiles        the oldest retained bootstrap record and its catalog header, or `null` when unavailable
	 * @param catalogStoragePath folder holding the catalog files
	 */
	private static void reclaimFilesUnreachableFrom(
		@Nullable DataFilesBulkInfo activeFiles,
		@Nonnull Path catalogStoragePath
	) {
		if (activeFiles == null) {
			return;
		}
		final int firstUsedCatalogDataFileIndex = activeFiles.bootstrapRecord().catalogFileIndex();
		final Map<Integer, Integer> entityFileIndex = activeFiles
			.catalogHeader()
			.getEntityTypeFileIndexes()
			.stream()
			.collect(
				Collectors.toMap(
					CollectionFileReference::entityTypePrimaryKey,
					CollectionFileReference::fileIndex
				)
			);
		// The highest entity type primary key that had been assigned at the oldest retained version. Entity type
		// primary keys come from a monotonic sequence and are never reused, so this cleanly separates the two reasons
		// a key can be missing from the header above: above the watermark the collection did not exist yet, below it
		// the collection existed once and was dropped.
		final int lastEntityTypePrimaryKeyAtOldestVersion = activeFiles
			.catalogHeader()
			.lastEntityCollectionPrimaryKey();

		ofNullable(
			catalogStoragePath.toFile()
				.listFiles((dir, name) -> name.endsWith(CATALOG_FILE_SUFFIX))
		)
			.stream()
			.flatMap(Arrays::stream)
			.filter(
				file -> TimeTravelRetention.isCatalogDataFileObsolete(
					getIndexFromCatalogFileName(file.getName()), firstUsedCatalogDataFileIndex))
			.forEach(file -> {
				if (file.delete()) {
					log.debug("Deleted obsolete catalog file `{}`", file.getAbsolutePath());
				} else {
					log.warn("Could not delete obsolete catalog file `{}`", file.getAbsolutePath());
				}
			});

		ofNullable(
			catalogStoragePath.toFile()
				.listFiles((dir, name) -> name.endsWith(ENTITY_COLLECTION_FILE_SUFFIX))
		)
			.stream()
			.flatMap(Arrays::stream)
			.filter(file -> {
				final EntityTypePrimaryKeyAndFileIndex result = getEntityPrimaryKeyAndIndexFromEntityCollectionFileName(
					file.getName());
				return TimeTravelRetention.isEntityCollectionFileObsolete(
					result.entityTypePrimaryKey(), result.fileIndex(),
					entityFileIndex, lastEntityTypePrimaryKeyAtOldestVersion
				);
			})
			.forEach(file -> {
				if (file.delete()) {
					log.debug("Deleted obsolete entity collection file `{}`", file.getAbsolutePath());
				} else {
					log.warn("Could not delete entity collection file `{}`", file.getAbsolutePath());
				}
			});
	}

	/**
	 * Purges the specified maintained file.
	 *
	 * This method deletes the specified maintained file from the file system. If the deletion is successful,
	 * the removalLambda associated with the maintained file is executed.
	 *
	 * @param maintainedFile the maintained file to be purged
	 */
	private void purgeFile(@Nonnull MaintainedFile maintainedFile) {
		maintainedFile.removalLambda().run();
		// when time travel is enabled, the files are removed only when bootstrap records is purged
		if (!this.timeTravelEnabled) {
			if (maintainedFile.path().toFile().delete()) {
				if (log.isDebugEnabled()) {
					log.debug("Deleted obsolete file: {}", maintainedFile.path(), new RuntimeException("Stack trace"));
				}
			} else {
				log.warn("Could not delete the obsolete file {}", maintainedFile.path());
			}
		}
	}

	/**
	 * Record that represents a single entry of the maintained file.
	 *
	 * @param catalogVersion the last catalog version that may use the file
	 * @param path           the path of the file
	 * @param removalLambda  the lambda function to be executed when the file is removed
	 */
	private record MaintainedFile(
		long catalogVersion,
		@Nonnull Path path,
		@Nonnull Runnable removalLambda
	) {
	}

	/**
	 * This record contains vital information for collecting the indexes of the first data files that are needed for
	 * time traveling snapshots. All previous records are considered obsolete and can be removed.
	 *
	 * @param bootstrapRecord bootstrap record
	 * @param catalogHeader   catalog header from particular version
	 */
	public record DataFilesBulkInfo(
		@Nonnull CatalogBootstrap bootstrapRecord,
		@Nonnull CatalogHeader<LogFileRecordReference, CollectionFileReference> catalogHeader
	) {

	}

	/**
	 * Callback synchronously removes all files which indexes are lower than the indexes mentioned in {@link CatalogHeader}
	 * of the currently first available catalog version. This callback is used only when time travel is enabled.
	 */
	@RequiredArgsConstructor
	private static class ObsoleteWalPurgeCallback implements WalPurgeCallback {
		/**
		 * The callback that is called when the catalog version is purged.
		 */
		private final LongConsumer maintainedFilePurgeCallback;
		/**
		 * Sweeps the data files no retained bootstrap record can reach. Routed through the maintainer rather than
		 * called statically, so that this door into the sweep obeys the same pin gate as the size guard's - the sweep
		 * is reachability-keyed, and the version clamp above cannot describe a consumer that reads the folder itself.
		 */
		private final Runnable unreachableFileSweep;
		/**
		 * The last catalog version that was observed. This variable is used to ignore calls with lower catalog version
		 * than were already processed.
		 */
		private long lastObservedCatalogVersion = -1L;

		@Override
		public void purgeFilesUpTo(long firstActiveCatalogVersion) {
			if (firstActiveCatalogVersion > this.lastObservedCatalogVersion) {
				// first purge all maintained files
				this.maintainedFilePurgeCallback.accept(firstActiveCatalogVersion);
				// then purge all obsolete files in the folders - the threshold is derived from the oldest bootstrap
				// record still retained on disk (whose data file is guaranteed to exist), not from an exact-version
				// lookup that may reference an already purged file
				this.unreachableFileSweep.run();
				// recorded only once both steps have returned, for the same reason `advanceHistoryHorizon` sets its
				// marker last: a round that threw half-way is retried with the same version, and a marker moved up
				// front makes that retry fall into the `else` below - the trim then no-ops as well, and the horizon
				// is recorded as reached with the purge never having run
				this.lastObservedCatalogVersion = firstActiveCatalogVersion;
			} else {
				// this callback was already called with this or newer catalog version
			}
		}
	}
}
