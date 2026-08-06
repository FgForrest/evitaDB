/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.store.engine;


import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.metric.event.storage.FileType;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.function.Functions;
import io.evitadb.spi.store.catalog.shared.model.TransactionMutationWithWalReference;
import io.evitadb.spi.store.engine.EnginePersistenceService;
import io.evitadb.spi.store.engine.model.AdoptableCatalogFolder;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.spi.store.engine.model.CatalogInventoryDivergence;
import io.evitadb.spi.store.engine.model.EngineState;
import io.evitadb.spi.store.engine.model.RetiredFolder;
import io.evitadb.spi.store.engine.model.UnprocessedTransactionRecord;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.model.reference.TransactionMutationWithWalFileReference;
import io.evitadb.store.offsetIndex.io.OffHeapMemoryManager;
import io.evitadb.store.offsetIndex.io.ReadOnlyFileHandle;
import io.evitadb.store.offsetIndex.io.WriteOnlyFileHandle;
import io.evitadb.store.offsetIndex.io.WriteOnlyOffHeapWithFileBackupHandle;
import io.evitadb.store.catalog.Migration_2026_1;
import io.evitadb.spi.store.engine.exception.WriteAheadLogCorruptedException;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.wal.EngineMutationLog;
import io.evitadb.store.wal.WalKryoConfigurer;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassifierUtils;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.FileUtils;
import io.evitadb.utils.FolderLock;
import io.evitadb.utils.IOUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.stream.Stream;

import static io.evitadb.store.offsetIndex.model.StorageRecord.read;

/**
 * Default implementation of the {@link EnginePersistenceService} interface that provides functionality
 * for managing engine state, WAL operations, and transactions.
 *
 * This service is responsible for:
 * - Reading and writing engine state to persistent storage
 * - Managing Write-Ahead Log (WAL) operations
 * - Handling transactions and mutations
 * - Providing access to committed mutations
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
public class DefaultEnginePersistenceService implements EnginePersistenceService<LogFileRecordReference> {
	/**
	 * Storage configuration options.
	 */
	private final StorageSettings storageSettings;

	/**
	 * Scheduler for asynchronous operations.
	 */
	private final Scheduler scheduler;

	/**
	 * The folder lock instance that is used for safeguarding exclusive access to the catalog storage directory.
	 */
	private final FolderLock folderLock;

	/**
	 * This lock synchronizes the access to the write ahead log file.
	 */
	private final ReentrantLock walWriteLock = new ReentrantLock();

	/**
	 * Pool contains instances of {@link Kryo} that are used for serializing mutations in WAL.
	 */
	private final Pool<Kryo> walKryoPool = new Pool<>(true, false, 16) {
		@Override
		protected Kryo create() {
			return KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		}
	};

	/**
	 * Pool contains instances of {@link Kryo} that are used for serializing engine state data.
	 */
	private final Pool<Kryo> dataKryoPool = new Pool<>(true, false, 16) {
		@Override
		protected Kryo create() {
			return KryoFactory.createKryo(EngineKryoConfigurer.INSTANCE);
		}
	};

	/**
	 * Manager for off-heap memory allocation and deallocation.
	 */
	private final OffHeapMemoryManager offHeapMemoryManager;

	/**
	 * Path to the bootstrap file that contains the engine state.
	 */
	private final Path bootstrapFilePath;

	/**
	 * Keeper for managing observable outputs during write operations.
	 */
	private final ObservableOutputKeeper observableOutputKeeper;

	/**
	 * Write-ahead log for storing mutations before they are committed to the main storage.
	 */
	@Nullable private EngineMutationLog mutationLog;

	/**
	 * Current state of the engine.
	 */
	private EngineState<LogFileRecordReference> engineState;

	/**
	 * Boot-time divergence between the persisted {@link EngineState}'s catalog inventory and the catalog folders
	 * that are actually present on disk. Computed once during construction by `computeCatalogInventoryDivergence`,
	 * and exposed to `Evita` through {@link #getPendingCatalogInventoryDivergence()} so it can be drained as
	 * WAL-backed engine mutations once {@code EngineTransactionManager} is available. Stays at
	 * {@link CatalogInventoryDivergence#EMPTY} for fresh services or when the persisted state already matches the
	 * on-disk reality.
	 */
	@Nonnull private CatalogInventoryDivergence pendingCatalogInventoryDivergence = CatalogInventoryDivergence.EMPTY;

	/**
	 * Flag indicating whether the engine state was newly created.
	 */
	@Getter private boolean created;

	/**
	 * Flag indicating whether the service has been closed.
	 */
	@Getter private boolean closed;

	/**
	 * Creates a new instance of DefaultEnginePersistenceService.
	 *
	 * @param storageOptions     configuration options for storage
	 * @param transactionOptions configuration options for transactions
	 * @param scheduler          scheduler for asynchronous operations
	 */
	public DefaultEnginePersistenceService(
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull Scheduler scheduler
	) {
		this.storageSettings = new StorageSettings(storageOptions, transactionOptions);
		this.scheduler = scheduler;

		// Initialize off-heap memory manager with transaction memory limits
		this.offHeapMemoryManager = new OffHeapMemoryManager(
			this.storageSettings.transactionMemoryBufferLimitSizeBytes(),
			this.storageSettings.transactionMemoryRegionCount(),
			this.storageSettings
		);

		// Create output keeper for observing and managing outputs
		this.observableOutputKeeper = new ObservableOutputKeeper(
			this.storageSettings.outputBufferSize(),
			this.storageSettings.lockTimeoutSeconds(),
			this.storageSettings.waitOnCloseSeconds(),
			scheduler
		);

		// Try to acquire lock over storage directory to ensure exclusive access
		this.folderLock = new FolderLock(this.storageSettings.storageDirectory());
		this.bootstrapFilePath = this.storageSettings.storageDirectory()
		                                       .resolve(EnginePersistenceService.getBootstrapFileName());

		// We need to do this before we create the write handle, because it will create the file if it doesn't exist.
		final boolean bootstrapFileExists = this.bootstrapFilePath.toFile().exists();

		// Either read existing engine state or create a new one
		if (bootstrapFileExists) {
			this.engineState = readEngineState();
			this.created = false;
			// Migrate WAL files if needed (version 4 -> 5)
			if (this.engineState.storageProtocolVersion() < STORAGE_PROTOCOL_VERSION) {
				final LogFileRecordReference correctedWalRef = Migration_2026_1.upgradeEngineWalFiles(
					this.storageSettings.storageDirectory(),
					this.engineState.walReference(),
					this.storageSettings
				);
				// Update engine state with new storage protocol version and corrected WAL reference.
				// Storage protocol version is orthogonal to the logical version counter — a migration
				// is not a WAL-backed mutation, so the version counter must not advance here.
				// Everything else is carried forward through the builder rather than re-listed: enumerating the
				// fields here silently dropped whichever buckets were added after this line was written, and the
				// catalog-to-folder bindings are one such bucket that cannot be reconstructed once lost.
				final EngineState<LogFileRecordReference> newEngineState = EngineState.builder(this.engineState)
					.storageProtocolVersion(STORAGE_PROTOCOL_VERSION)
					.walFileReference(correctedWalRef != null ? correctedWalRef : this.engineState.walReference())
					.build();
				rewriteEngineStateInPlace(newEngineState);
			}
		} else {
			this.engineState = createNewEngineState(this.storageSettings);
			this.created = true;
		}

		final LogFileRecordReference logFileRecordReference = this.engineState.walReference() == null ?
			new LogFileRecordReference(EnginePersistenceService::getWalFileName) : this.engineState.walReference();

		// Initialize the write-ahead log if there are any WAL files present
		this.mutationLog = createWalIfAnyWalFilePresent(
			this.engineState.version(),
			logFileRecordReference,
			this.storageSettings,
			scheduler,
			this.walKryoPool
		);

		// Fail-loud startup invariant check.
		//
		// The engine state bootstrap file and the WAL are kept in lock-step by EngineTransactionManager: the WAL is
		// appended first, then the bootstrap file is rewritten with the matching version. Any disagreement between
		// the two on startup signals that the previous run left persistent state in an inconsistent shape —
		// typically either a crash between the two writes, or a WAL/state file that has been externally tampered
		// with. Silently recovering from drift would mask the root cause and can wedge the engine in subtle ways.
		// We surface the condition immediately.
		//
		// Legitimate non-matching cases permitted here:
		//
		// 1. `walVersion == stateVersion` — the normal post-commit steady state.
		// 2. `walVersion == 0 && stateVersion == 1` — a never-used service whose initial engine state is stored at
		//    version 1 before any WAL file exists (also covers a rebooted fresh service that never saw a committed
		//    mutation).
		// 3. `walVersion == stateVersion + 1` — an OS-level crash inside the fused critical section of
		//    `appendWalAndStoreState` between the WAL append and the bootstrap rewrite. The WAL entry is durable,
		//    the work-phase side effects already happened before the fused call, but the bootstrap still reports
		//    the old version. Forward WAL replay reconciles the bootstrap at a higher layer
		//    (`EngineTransactionManager.replayCrashedMutationIfNeeded`) without appending to the WAL.
		//
		// Any other combination still throws — those indicate actual corruption or tampering and require operator
		// intervention.
		//
		// This check runs BEFORE `syncEngineStateByFolderContents` so a drifted state cannot be
		// silently "healed" by the reconciliation (which rewrites the bootstrap file in place).
		// Preserving the drift on disk is critical for post-mortem diagnosis.
		final long walVersion = this.mutationLog == null ? 0L : this.mutationLog.getLastWrittenVersion();
		final long stateVersion = this.engineState.version();
		Assert.isPremiseValid(
			walVersion == stateVersion
				|| (walVersion == 0L && stateVersion == 1L)
				|| walVersion == stateVersion + 1L,
			() -> "Engine state / WAL version drift detected on startup! " +
				"WAL lastWrittenVersion=" + walVersion + ", engineState.version=" + stateVersion + ". " +
				"Refusing to boot — the on-disk state is inconsistent and requires operator intervention. " +
				"Allowed combinations are: walVersion == stateVersion, " +
				"(walVersion == 0 && stateVersion == 1), or walVersion == stateVersion + 1 " +
				"(the single-mutation crash window recovered by forward WAL replay)."
		);

		// The reconciliation here is a pure value computation: we capture which catalogs are now
		// missing on disk, which previously-missing catalogs reappeared, and which folders are
		// brand new. The persisted bootstrap is **not** rewritten. `Evita` later drains this
		// divergence as proper WAL-backed engine mutations once `EngineTransactionManager` exists,
		// preserving the WAL-first invariant and making the boot-time reconciliation observable through CDC.
		if (!this.created) {
			// Classify once and use the verdicts twice: removing abandoned folders is a side effect and has to
			// stay out of the divergence computation, which must remain a pure value.
			final List<CatalogFolderClassification> classifications = CatalogFolderClassifier.classify(
				this.storageSettings.storageDirectory(), this.engineState
			);
			final List<String> removedFolders = CatalogFolderCleaner.drain(
				this.storageSettings.storageDirectory(), classifications
			);
			this.pendingCatalogInventoryDivergence = computeCatalogInventoryDivergence(
				classifications, removedFolders, this.engineState
			);
		}
	}

	/**
	 * Creates a CatalogWriteAheadLog if there are any WAL files present in the catalog file path.
	 *
	 * @param version            the version of the engine
	 * @param storageSettings     the storage options
	 * @param scheduler          the executor service
	 * @param kryoPool           the Kryo pool
	 * @return a EngineMutationLog object if WAL files are present, otherwise null
	 */
	@Nullable
	static EngineMutationLog createWalIfAnyWalFilePresent(
		long version,
		@Nonnull LogFileRecordReference logFileRecordReference,
		@Nonnull StorageSettings storageSettings,
		@Nonnull Scheduler scheduler,
		@Nonnull Pool<Kryo> kryoPool
	) {
		final Path storageFolder = storageSettings.storageDirectory();
		final File[] walFiles = storageFolder
			.toFile()
			.listFiles((dir, name) -> name.endsWith(WAL_FILE_SUFFIX));
		return walFiles == null || walFiles.length == 0 ?
			null :
			new EngineMutationLog(
				version, logFileRecordReference, storageFolder, kryoPool,
				storageSettings, scheduler
			);
	}

	@Override
	public boolean isNew() {
		return this.created;
	}

	@Override
	public long getVersion() {
		return this.engineState.version();
	}

	@Nonnull
	@Override
	public EngineState<LogFileRecordReference> getEngineState() {
		return this.engineState;
	}

	@Nonnull
	@Override
	public CatalogInventoryDivergence getPendingCatalogInventoryDivergence() {
		return this.pendingCatalogInventoryDivergence;
	}

	@Override
	public void storeEngineState(@Nonnull EngineState<LogFileRecordReference> engineState) {
		this.created = false;
		// Validate that the version is incremented by exactly one — this is the
		// public entry point for WAL-backed state transitions, so the new version
		// must correspond to exactly one appended mutation.
		Assert.isPremiseValid(
			(this.engineState == null && engineState.version() == 1) ||
				(this.engineState != null && this.engineState.version() + 1 == engineState.version()),
			this.engineState == null ?
				"Engine state version must be 1 when creating new engine state!" :
				"Engine state version must be incremented by one when storing new engine state! " +
					"Current version: " + this.engineState.version() + ", new version: " + engineState.version()
		);
		writeBootstrapFile(engineState);
	}

	/**
	 * Rewrites the bootstrap file in place without advancing the engine version counter.
	 *
	 * This is used for non-mutation reconciliation paths where only storage-layer metadata changes (e.g. storage
	 * protocol upgrade, catalog list reconciliation against on-disk contents). Such changes are **not** backed by
	 * WAL entries, so bumping the version counter here would drift the engine state version ahead of the WAL and
	 * permanently wedge the engine.
	 *
	 * @param engineState the reconciled engine state to persist; must have the
	 *                    same version as the current in-memory state
	 */
	private void rewriteEngineStateInPlace(@Nonnull EngineState<LogFileRecordReference> engineState) {
		Assert.isPremiseValid(
			this.engineState != null && this.engineState.version() == engineState.version(),
			() -> "In-place engine state rewrite must preserve the version counter! " +
				"Current version: " + (this.engineState == null ? "<none>" : this.engineState.version()) +
				", new version: " + engineState.version()
		);
		writeBootstrapFile(engineState);
	}

	/**
	 * Forward-replay reconciliation of the bootstrap file.
	 *
	 * Only callable during forward WAL replay. The WAL must already contain a committed mutation
	 * at `newState.version()` — this method just reconciles the bootstrap file with that committed
	 * entry without appending to WAL.
	 *
	 * This path exists to recover from the single OS-crash window inside the fused critical section
	 * of `appendWalAndStoreState`, in which the WAL entry was durably written but the bootstrap
	 * rewrite never completed. The caller (`EngineTransactionManager.replayCrashedMutationIfNeeded`)
	 * has already re-applied the committed mutation to the in-memory `ExpandedEngineState`; this
	 * method persists the reconciled snapshot.
	 *
	 * Preconditions (asserted via `Assert.isPremiseValid`):
	 *
	 * - `newState.version() == this.engineState.version() + 1`
	 * - `this.mutationLog != null && this.mutationLog.getLastWrittenVersion() == newState.version()`
	 *
	 * Holds the WAL write lock to prevent any interleaving append from observing the intermediate
	 * state; the lock is always released before returning.
	 *
	 * @param newState engine state at the next version to persist; must not be null
	 */
	@Override
	public void rewriteEngineStateAtNextVersion(@Nonnull EngineState<LogFileRecordReference> newState) {
		this.walWriteLock.lock();
		try {
			final long currentVersion = this.engineState.version();
			final long targetVersion = newState.version();
			Assert.isPremiseValid(
				currentVersion + 1L == targetVersion,
				() -> "Forward-replay bootstrap rewrite must advance engine state by exactly one version! " +
					"Current version: " + currentVersion + ", target version: " + targetVersion
			);
			Assert.isPremiseValid(
				this.mutationLog != null
					&& this.mutationLog.getLastWrittenVersion() == targetVersion,
				() -> "Forward-replay bootstrap rewrite requires the WAL to already contain a committed " +
					"mutation at the target version! Target version: " + targetVersion +
					", WAL lastWrittenVersion=" +
					(this.mutationLog == null ? "<no WAL>" : this.mutationLog.getLastWrittenVersion())
			);
			// WAL already holds the committed mutation — we just reconcile the bootstrap.
			writeBootstrapFile(newState);
		} finally {
			this.walWriteLock.unlock();
		}
	}

	/**
	 * Serializes the given engine state into the bootstrap file via a tmp-rename
	 * swap and updates the in-memory reference. The caller is responsible for
	 * enforcing the version-counter invariant appropriate for the call site.
	 */
	private void writeBootstrapFile(@Nonnull EngineState<LogFileRecordReference> engineState) {
		// Initialize handle for writing engine state data to file
		final Path tmpFile = this.bootstrapFilePath.getParent().resolve(
			this.bootstrapFilePath.getName(this.bootstrapFilePath.getNameCount() - 1) + ".tmp");
		FileUtils.deleteFileIfExists(tmpFile);
		try (
			final WriteOnlyFileHandle writeHandle = new WriteOnlyFileHandle(
				FileType.ENGINE,
				"engine",
				tmpFile,
				this.storageSettings.outputBufferSize(),
				this.storageSettings.syncWrites(),
				this.storageSettings,
				this.storageSettings,
				this.observableOutputKeeper
			)
		) {
			// Write the engine state to persistent storage
			writeHandle.checkAndExecute(
				"write engine state",
				Functions.noOpRunnable(),
				observableOutput -> {
					final Kryo writeKryo = this.dataKryoPool.obtain();
					try {
						// Create a storage record with the engine state
						return new StorageRecord<>(
							writeKryo,
							observableOutput,
							engineState.version(),
							true,
							engineState
						);
					} finally {
						// Return Kryo instance to the pool
						this.dataKryoPool.free(writeKryo);
					}
				}
			);
		}

		// rename the temporary file to the actual bootstrap file with overwrite existing file
		FileUtils.renameOrReplaceFile(tmpFile, this.bootstrapFilePath);

		// Update the current engine state
		this.engineState = engineState;
	}

	/**
	 * Appends a transaction mutation to the WAL **without** advancing the engine state. Test-only — production
	 * code must use {@link #appendWalAndStoreState} so the WAL append and the matching bootstrap rewrite happen
	 * atomically and the WAL-first invariant cannot be violated by mistake.
	 *
	 * Retained on the concrete implementation (and removed from the SPI) so tests exercising deliberate WAL ↔
	 * engine-state desynchronization — startup-invariant negative tests and forward-replay setup — can construct
	 * the desync state that the fused primitive prevents by construction. Any caller outside {@code src/test}
	 * is a bug.
	 */
	@Nonnull
	public TransactionMutationWithWalFileReference appendWal(
		long version,
		@Nonnull UUID transactionId,
		@Nonnull EngineMutation<?> mutation
	) {
		this.walWriteLock.lock();
		try {
			return doAppendWalLocked(version, transactionId, mutation);
		} finally {
			this.walWriteLock.unlock();
		}
	}

	/**
	 * Fused WAL-first commit.
	 *
	 * Appends the transaction mutation to the WAL AND writes the matching engine state to the bootstrap file as one
	 * indivisible critical section guarded by `walWriteLock`. This removes the window in which a caller could,
	 * through a refactoring mistake, advance engine state without a matching WAL append and silently violate the
	 * startup invariant.
	 *
	 * On success both `getLastVersionInMutationStream()` and `getEngineState().version()` equal `version` by
	 * construction.
	 *
	 * Failure semantics (all-or-nothing): if `stateFactory` throws, the WAL append is rolled back (WAL file
	 * truncated to the pre-append position and the in-memory mutation log reset), then the throwable is rethrown.
	 */
	@Nonnull
	@Override
	public TransactionMutationWithWalReference appendWalAndStoreState(
		long version,
		@Nonnull UUID transactionId,
		@Nonnull EngineMutation<?> mutation,
		@Nonnull Function<TransactionMutationWithWalReference, EngineState<LogFileRecordReference>> stateFactory
	) {
		this.walWriteLock.lock();
		try {
			// Validate the WAL-backed version invariant BEFORE any side-effect so
			// a non-incremental call cannot leave the WAL or bootstrap file partially
			// advanced.
			final long currentVersion = this.engineState.version();
			Assert.isPremiseValid(
				currentVersion + 1 == version,
				() -> "Engine state version must be incremented by one when storing new engine state! " +
					"Current version: " + currentVersion + ", new version: " + version
			);

			// Initialize WAL up front so we can read its file path for the rollback
			// snapshot before the append touches it.
			@SuppressWarnings("resource") final EngineMutationLog log = ensureMutationLogInitialized();

			// Capture pre-append state for rollback on factory failure (Option A).
			// We remember the WAL file path and its physical size so we can
			// truncate back exactly to the pre-append offset if the state factory
			// throws. When the pre-append size is zero the WAL file was freshly
			// created by this call and will be deleted instead of truncated.
			final Path preAppendWalFilePath = log.getWalFilePath();
			final long preAppendWalFileSize = preAppendWalFilePath.toFile().length();
			final LogFileRecordReference preAppendWalReference = this.engineState.walReference();

			final TransactionMutationWithWalFileReference txRef = doAppendWalLocked(version, transactionId, mutation);

			// Invoke the state factory and store the resulting engine state. If
			// the factory throws we roll the WAL append back and rethrow.
			try {
				final EngineState<LogFileRecordReference> newEngineState = stateFactory.apply(txRef);
				Assert.isPremiseValid(
					newEngineState != null && newEngineState.version() == version,
					() -> "State factory must return a non-null engine state at version " + version +
						", but returned " + (newEngineState == null ? "<null>" : "version=" + newEngineState.version())
				);
				// We already hold walWriteLock and have already enforced the
				// `current + 1 == version` invariant up front, so bypass the
				// public storeEngineState re-check and write the bootstrap file
				// directly. This keeps the WAL append + bootstrap write as a
				// single fused critical section.
				this.created = false;
				writeBootstrapFile(newEngineState);
				return txRef;
			} catch (Throwable t) {
				rollbackWalAppend(preAppendWalFilePath, preAppendWalFileSize, preAppendWalReference);
				throw t;
			}
		} finally {
			this.walWriteLock.unlock();
		}
	}

	/**
	 * Lazily initialises {@link #mutationLog} on first use and returns the non-null reference so
	 * callers can chain accesses without re-reading the field (which would force them through a
	 * nullable read again). Idempotent — subsequent calls return the existing log. Caller must
	 * hold {@code walWriteLock} so the assignment is safely published to other threads that
	 * observe the field through the lock.
	 */
	@Nonnull
	private EngineMutationLog ensureMutationLogInitialized() {
		if (this.mutationLog == null) {
			this.mutationLog = new EngineMutationLog(
				getVersion(),
				new LogFileRecordReference(EnginePersistenceService::getWalFileName),
				this.storageSettings.storageDirectory(),
				this.walKryoPool,
				this.storageSettings,
				this.scheduler
			);
		}
		return this.mutationLog;
	}

	/**
	 * Shared WAL-append primitive used by both {@link #appendWal} (test-only desync helper) and
	 * {@link #appendWalAndStoreState} (production fused commit). Serializes the mutation into an
	 * off-heap buffer, builds the {@link TransactionMutation} header and appends the pair to the
	 * mutation log. Caller must hold {@code walWriteLock}.
	 */
	@Nonnull
	private TransactionMutationWithWalFileReference doAppendWalLocked(
		long version,
		@Nonnull UUID transactionId,
		@Nonnull EngineMutation<?> mutation
	) {
		@SuppressWarnings("resource") final EngineMutationLog log = ensureMutationLogInitialized();

		// Initialize handle for writing WAL data to off-heap memory
		try (
			final WriteOnlyOffHeapWithFileBackupHandle logWriteHandle = new WriteOnlyOffHeapWithFileBackupHandle(
				this.storageSettings.transactionWorkDirectory().resolve(transactionId + ".tmp"),
				this.storageSettings.outputBufferSize(),
				this.storageSettings.syncWrites(),
				this.observableOutputKeeper,
				this.offHeapMemoryManager,
				this.storageSettings,
				this.storageSettings
			)
		) {
			// Write the mutation to the WAL and get its size in bytes
			final int mutationSizeInBytes = logWriteHandle.checkAndExecute(
				"write mutation",
				() -> {
				},
				output -> {
					final Kryo writeKryo = this.walKryoPool.obtain();
					try {
						// Create a storage record with the mutation
						final StorageRecord<Mutation> record = new StorageRecord<>(
							output, version, true,
							theOutput -> {
								// Serialize the mutation using Kryo
								writeKryo.writeClassAndObject(output, mutation);
								return mutation;
							}
						);
						// Return the size of the record
						return record.fileLocation().recordLength();
					} finally {
						// Return Kryo instance to the pool
						this.walKryoPool.free(writeKryo);
					}
				}
			);

			// Create a transaction mutation with the mutation size
			final TransactionMutation transactionMutation = new TransactionMutation(
				transactionId, version, 1, mutationSizeInBytes, OffsetDateTime.now()
			);

			// Append the transaction mutation to the WAL
			return new TransactionMutationWithWalFileReference(
				log.append(
					transactionMutation,
					// when reading is done, the off-heap memory will be released automatically
					logWriteHandle.toReadOffHeapWithFileBackupReference()
				),
				transactionMutation
			);
		}
	}

	/**
	 * Rolls the most recent WAL append back to the captured pre-append position.
	 *
	 * The rollback implements Option A all-or-nothing semantics of
	 * `appendWalAndStoreState`: on a failure after a successful WAL append the
	 * WAL file is truncated back to `preAppendFileSize` (or deleted when the
	 * file was freshly created by the failing call) and the in-memory
	 * `mutationLog` is closed so that the next append reopens it and
	 * re-derives its state from the truncated file.
	 *
	 * This is called under `walWriteLock` so no concurrent append can observe
	 * the intermediate state.
	 */
	private void rollbackWalAppend(
		@Nonnull Path preAppendWalFilePath,
		long preAppendWalFileSize,
		@Nullable LogFileRecordReference preAppendWalReference
	) {
		// Close the current mutation log so the next legitimate append rebuilds
		// it from the truncated file — this guarantees the in-memory
		// lastWrittenVersion and cumulative checksum are consistent with disk.
		if (this.mutationLog != null) {
			IOUtils.closeQuietly(this.mutationLog::close);
			this.mutationLog = null;
		}
		if (preAppendWalFileSize > 0L && preAppendWalReference != null && preAppendWalReference.fileLocation() != null) {
			// A prior WAL record existed — truncate back to its end position using
			// the public helper that already implements this operation.
			truncateWriteAheadLog(preAppendWalReference);
		} else {
			// The WAL file was freshly created by the failing call; deleting it
			// leaves the service in the same shape as before the call.
			FileUtils.deleteFileIfExists(preAppendWalFilePath);
		}
	}

	@Nonnull
	@Override
	public Optional<TransactionMutation> getFirstNonProcessedTransactionInWal(long version) {
		if (this.mutationLog == null) {
			// If WAL is not initialized, there are no transactions
			return Optional.empty();
		} else {
			// Get the first non-processed transaction from the WAL
			return this.mutationLog.getFirstNonProcessedTransaction(this.engineState.walReference())
			                       .map(TransactionMutationWithWalFileReference::transactionMutation);
		}
	}

	@Nonnull
	@Override
	public Stream<EngineMutation<?>> getCommittedMutationStream(long version) {
		if (this.mutationLog == null) {
			// If WAL is not initialized, there are no mutations
			return Stream.empty();
		} else {
			// Get stream of committed mutations from the WAL
			return this.mutationLog.getCommittedMutationStream(version);
		}
	}

	@Nonnull
	@Override
	public Optional<UnprocessedTransactionRecord<LogFileRecordReference>> getUnprocessedTransaction() {
		// Forward-replay helper. Composes `mutationLog.getFirstNonProcessedTransaction` (which returns the
		// transaction header + WAL reference past the engine state's walRef) with the engine-mutation body read
		// at the same version, so the caller gets the full replay payload (`version`, `mutation`, `walReference`)
		// in one round-trip.
		//
		// Contract: empty result is reserved for legitimate "no work to do" cases — the WAL was never
		// initialised, or the engine state's walReference already covers everything in the WAL. Detecting
		// a transaction header without a matching engine-mutation body is a structural data-integrity
		// violation (every committed `TransactionMutation` header in the engine WAL must be followed by
		// exactly one body); we surface that as `WriteAheadLogCorruptedException` (WalKind.ENGINE) so the corruption
		// is visible at the persistence boundary rather than being re-encoded as an absent return value
		// the caller would have to translate back into an error.
		if (this.mutationLog == null) {
			return Optional.empty();
		}
		final Optional<TransactionMutationWithWalFileReference> headerOpt =
			this.mutationLog.getFirstNonProcessedTransaction(this.engineState.walReference());
		if (headerOpt.isEmpty()) {
			return Optional.empty();
		}
		final TransactionMutationWithWalFileReference header = headerOpt.get();
		final long version = header.transactionMutation().getVersion();

		// Locate the first business mutation that immediately follows the transaction header. For
		// engine-level transactions there is exactly one business mutation per version, so we return the first
		// non-header mutation whose preceding header matches the target version.
		try (final Stream<EngineMutation<?>> stream = this.mutationLog.getCommittedMutationStream(version)) {
			TransactionMutation headerAtTargetVersion = null;
			for (final Iterator<EngineMutation<?>> iterator = stream.iterator(); iterator.hasNext(); ) {
				final EngineMutation<?> next = iterator.next();
				if (next instanceof TransactionMutation txMutation) {
					if (txMutation.getVersion() == version) {
						headerAtTargetVersion = txMutation;
					} else {
						// We have crossed into the next transaction without finding the body for our header
						// at `version`. The WAL is structurally malformed — header present, body absent.
						throw WriteAheadLogCorruptedException.headerWithoutBody(version, txMutation.getVersion());
					}
				} else if (headerAtTargetVersion != null) {
					return Optional.of(new UnprocessedTransactionRecord<>(version, next, header.walReference()));
				}
			}
			// Iterator exhausted before we found a body for the header at `version`. Either the WAL was
			// truncated mid-record or the body was never durably written. Either way the WAL is
			// malformed at this version.
			throw WriteAheadLogCorruptedException.truncatedMidRecord(version);
		}
	}

	@Nonnull
	@Override
	public Stream<EngineMutation<?>> getReversedCommittedMutationStream(@Nullable Long version) {
		if (this.mutationLog == null) {
			// If WAL is not initialized, there are no mutations
			return Stream.empty();
		} else {
			// Get stream of committed mutations in reverse order from the WAL
			return this.mutationLog.getCommittedReversedMutationStream(version == null ? getVersion() : version);
		}
	}

	@Override
	public void truncateWriteAheadLog(@Nonnull LogFileRecordReference walReference) {
		if (walReference.fileLocation() != null) {
			final Path filePath = walReference.toFilePath(this.storageSettings.storageDirectory());
			if (filePath.toFile().length() > walReference.fileLocation().endPosition()) {
				try (RandomAccessFile randomAccessFile = new RandomAccessFile(filePath.toFile(), "rw")) {
					log.info(
						"Engine log file contains more data than expected, truncating it to {} bytes: {}",
						walReference.fileLocation().endPosition(), filePath
					);
					randomAccessFile.setLength(walReference.fileLocation().endPosition());
				} catch (IOException ex) {
					throw new UnexpectedIOException(
						"Failed to truncate an engine log file: " + filePath,
						"Failed to truncate an engine log file!",
						ex
					);
				}
			}
		}
	}

	@Override
	public long getLastVersionInMutationStream() {
		if (this.mutationLog == null) {
			// If WAL is not initialized, return 0 as the last version
			return 0L;
		} else {
			// Get the last written version from the WAL
			return this.mutationLog.getLastWrittenVersion();
		}
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			if (this.mutationLog != null) {
				this.walWriteLock.lock();
				try {
					IOUtils.closeQuietly(this.mutationLog::close);
				} finally {
					this.walWriteLock.unlock();
				}
			}
			// Close all resources quietly (without throwing exceptions)
			IOUtils.closeQuietly(
				this.offHeapMemoryManager::close,
				this.folderLock::close
			);
		}
	}

	/**
	 * Reads the engine state from the persistent storage.
	 *
	 * @return the engine state read from storage
	 */
	@Nonnull
	private EngineState<LogFileRecordReference> readEngineState() {
		final EngineState<LogFileRecordReference> engineState;
		try (
			final ReadOnlyFileHandle readOnlyFileHandle = new ReadOnlyFileHandle(
				null,
				FileType.ENGINE,
				"engine",
				this.bootstrapFilePath,
				this.storageSettings,
				this.storageSettings
			)
		) {
			//noinspection unchecked
			engineState = readOnlyFileHandle.execute(
				observableInput ->
					(EngineState<LogFileRecordReference>) read(
						observableInput,
						(theInput, recordLength) -> {
							final Kryo readKryo = this.dataKryoPool.obtain();
							try {
								// Deserialize the EngineState object from the input stream
								return readKryo.readObject(theInput, EngineState.class);
							} finally {
								// Return Kryo instance to the pool
								this.dataKryoPool.free(readKryo);
							}
						}
					).payload()
			);
		}

		return engineState;
	}

	/**
	 * Computes the divergence between the persisted engine state and the catalog folders that are actually on
	 * disk — without performing any side effects.
	 *
	 * Three categories are detected:
	 *
	 * - Catalogs registered as active or inactive whose folder is no longer present → `becomeMissing`.
	 * - Catalogs that previously sat in the `missingCatalogs` bucket whose folder has reappeared → `reappeared`.
	 * - Folders on disk that are unknown to the engine state **and offered for adoption** → `autoDiscovered`.
	 *
	 * A registered catalog is looked up through its **binding**, never by assuming its folder is named after it —
	 * that assumption is exactly what this line of work removes. An unreferenced folder is put through
	 * {@link CatalogFolderClassifier}, and only a {@link CatalogFolderState#FOREIGN} one is adoptable; everything
	 * else is reported and left where it is. Before this, *every* unknown directory was registered as a catalog,
	 * which turned an operator's stray folder into a catalog the engine claimed to own.
	 *
	 * Each list is sorted alphabetically so the resulting WAL trail is deterministic across reboots over the same
	 * on-disk shape. The persistence service does not rewrite the bootstrap here — `Evita` drains the divergence
	 * after `EngineTransactionManager` is wired by emitting one engine mutation per entry, which preserves the
	 * WAL-first invariant and makes the reconciliation observable through CDC.
	 *
	 * A fourth category is reported rather than detected: the tombstoned folders that are provably gone, either
	 * because the drain that ran just before this removed them or because they were already absent. They produce
	 * no mutation — the engine simply needs to know, so that the next engine-state commit stops carrying their
	 * tombstones.
	 *
	 * @param classifications verdicts {@link CatalogFolderClassifier} reached for the storage directory
	 * @param removedFolders  folders the drain removed, in the order it processed them
	 * @param engineState the current engine state to compare against the on-disk folder contents
	 * @return divergence record; never null, possibly {@link CatalogInventoryDivergence#EMPTY}
	 */
	@Nonnull
	private static CatalogInventoryDivergence computeCatalogInventoryDivergence(
		@Nonnull List<CatalogFolderClassification> classifications,
		@Nonnull List<String> removedFolders,
		@Nonnull EngineState<LogFileRecordReference> engineState
	) {
		final Set<String> foldersOnDisk = CollectionUtils.createHashSet(classifications.size());
		for (final CatalogFolderClassification classification : classifications) {
			foldersOnDisk.add(classification.folderName());
		}
		final Set<String> removedFolderNames = Set.copyOf(removedFolders);
		// every name the engine already knows, in any bucket - a folder whose bare name collides with one
		// of these cannot be adopted under that name, whatever folder the catalog itself lives in
		final Set<String> registeredCatalogNames = CollectionUtils.createHashSet(
			engineState.activeCatalogs().length + engineState.inactiveCatalogs().length +
				engineState.missingCatalogs().length
		);
		Collections.addAll(registeredCatalogNames, engineState.activeCatalogs());
		Collections.addAll(registeredCatalogNames, engineState.inactiveCatalogs());
		Collections.addAll(registeredCatalogNames, engineState.missingCatalogs());

		// Tombstones whose folder is provably gone. Both terms are needed and neither subsumes the other: the
		// drain covers a folder removed a moment ago, while the absence check covers one whose removal succeeded
		// on an earlier run that never got to record the fact - a crash between the delete and the next commit.
		final List<CatalogFolderId> drainedFolders = new ArrayList<>(engineState.retiredFolders().length);
		for (final RetiredFolder retiredFolder : engineState.retiredFolders()) {
			final String folderName = retiredFolder.folderId().id();
			if (removedFolderNames.contains(folderName) || !foldersOnDisk.contains(folderName)) {
				drainedFolders.add(retiredFolder.folderId());
			}
		}

		final ArrayList<String> becomeMissing = new ArrayList<>(16);
		final ArrayList<String> reappeared = new ArrayList<>(16);
		final ArrayList<AdoptableCatalogFolder> autoDiscovered = new ArrayList<>(16);

		// Active / inactive catalogs whose bound folder vanished while the engine was down.
		for (final String catalog : engineState.activeCatalogs()) {
			if (boundFolderPresent(engineState, foldersOnDisk, catalog)) {
				continue;
			}
			log.warn("Registered active catalog `{}` is missing on disk — staging MISSING transition.", catalog);
			becomeMissing.add(catalog);
		}
		for (final String catalog : engineState.inactiveCatalogs()) {
			if (boundFolderPresent(engineState, foldersOnDisk, catalog)) {
				continue;
			}
			log.warn("Registered inactive catalog `{}` is missing on disk — staging MISSING transition.", catalog);
			becomeMissing.add(catalog);
		}

		// Previously-missing catalogs whose folder reappeared — they will be demoted back to INACTIVE.
		for (final String catalog : engineState.missingCatalogs()) {
			if (boundFolderPresent(engineState, foldersOnDisk, catalog)) {
				log.info("Previously missing catalog `{}` has reappeared on disk — staging INACTIVE restoration.", catalog);
				reappeared.add(catalog);
			}
		}

		// Everything the engine state does not reference, sorted into what may be adopted and what may not.
		for (final CatalogFolderClassification classification : classifications) {
			final String folderName = classification.folderName();
			switch (classification.state()) {
				case REFERENCED -> {
					// already accounted for above, through the binding rather than through the folder name
				}
				case FOREIGN -> {
					// Checked *before* the folder is touched. Adoption renames the folder into the shape the
					// engine allocates and only then dispatches a registration mutation, which validates the
					// name - so a folder whose name is not a usable catalog name would be moved first and
					// rejected second, failing boot reconciliation outright and leaving the operator's import
					// renamed. A folder we cannot adopt must be left exactly as it was found.
					if (!isAdoptableCatalogName(folderName, registeredCatalogNames)) {
						continue;
					}
					log.info("Discovered previously unknown catalog on disk — staging INACTIVE registration: {}",
						folderName);
					// the folder name doubles as the catalog name here, and only here: a FOREIGN folder is
					// suffix-free by definition, so the two are the same string. Reading the name from the
					// catalog's own header instead — which is what would let a folder/header mismatch be
					// *detected* rather than silently accepted — needs an open offset index, and boot
					// classification has no persistence service to get one from. Recorded as a known gap.
					autoDiscovered.add(new AdoptableCatalogFolder(folderName, new CatalogFolderId(folderName)));
				}
				case UNCLAIMED -> log.warn(
					"Storage folder `{}` is shaped like one evitaDB allocated but no catalog claims it — leaving " +
						"it untouched. Rename it to a name without the `_<number>` suffix to have it adopted.",
					folderName
				);
				case JUNK -> log.warn(
					"Storage folder `{}` holds no catalog bootstrap file and is not referenced — leaving it " +
						"untouched.", folderName
				);
				case PROVISIONAL -> {
					// already removed by the drain that ran before this, or left in place for the next boot
					// because the removal failed - either way it is not part of the divergence
				}
				case RETIRED -> {
					// removed by the drain that ran before this, or left in place for the next boot because the
					// removal failed - a removed one is reported through `drainedFolders`, a surviving one keeps
					// its tombstone so the next boot tries again
				}
			}
		}

		if (becomeMissing.isEmpty() && reappeared.isEmpty() && autoDiscovered.isEmpty() && drainedFolders.isEmpty()) {
			return CatalogInventoryDivergence.EMPTY;
		}

		// Sort each bucket so the WAL trail is deterministic across reboots over the same on-disk shape.
		Collections.sort(becomeMissing);
		Collections.sort(reappeared);
		autoDiscovered.sort(Comparator.comparing(AdoptableCatalogFolder::catalogName));
		return new CatalogInventoryDivergence(becomeMissing, reappeared, autoDiscovered, drainedFolders);
	}

	/**
	 * Tells whether a discovered folder may be adopted under its own name.
	 *
	 * Two ways it may not. The name may not be a legal catalog name at all — a directory in the storage root is
	 * whatever an operator called it, while a catalog name has to satisfy the classifier format. Or the name may
	 * already belong to a registered catalog, which lives in a folder of its own; adopting would then be a second
	 * catalog claiming a taken name.
	 *
	 * Both are reported and the folder is left untouched, because both are situations only a human can resolve —
	 * and because the alternative is worse than doing nothing: adoption renames the folder before the mutation
	 * that would reject it ever runs, so the failure would arrive after the import had already been moved.
	 *
	 * @param folderName              name of the discovered folder, which doubles as the candidate catalog name
	 * @param registeredCatalogNames  every catalog name the engine state already knows, in any bucket
	 * @return true when the folder can be offered for adoption
	 */
	private static boolean isAdoptableCatalogName(
		@Nonnull String folderName,
		@Nonnull Set<String> registeredCatalogNames
	) {
		if (registeredCatalogNames.contains(folderName)) {
			log.warn(
				"Storage folder `{}` looks like a catalog placed here by hand, but a catalog of that name is " +
					"already registered and lives elsewhere — leaving the folder untouched. Rename it to a free " +
					"catalog name to have it adopted.",
				folderName
			);
			return false;
		}
		try {
			ClassifierUtils.validateClassifierFormat(ClassifierType.CATALOG, folderName);
			return true;
		} catch (RuntimeException ex) {
			log.warn(
				"Storage folder `{}` looks like a catalog placed here by hand, but its name cannot be a catalog " +
					"name ({}) — leaving the folder untouched. Rename it to a valid catalog name to have it " +
					"adopted.",
				folderName, ex.getMessage()
			);
			return false;
		}
	}

	/**
	 * Tells whether the folder a catalog is bound to is actually present on disk.
	 *
	 * The lookup goes through the binding rather than assuming the folder carries the catalog's name. Once a
	 * folder outlives a rename the two differ, and matching by name would report a perfectly healthy catalog as
	 * missing — which stages a MISSING transition and takes it out of service.
	 *
	 * @param engineState   state carrying the name-to-folder bindings
	 * @param foldersOnDisk names of every directory found under the storage root
	 * @param catalogName   catalog whose folder is being looked for
	 * @return true when the catalog is bound to a folder that exists
	 */
	private static boolean boundFolderPresent(
		@Nonnull EngineState<LogFileRecordReference> engineState,
		@Nonnull Set<String> foldersOnDisk,
		@Nonnull String catalogName
	) {
		final CatalogFolderId folderId = engineState.boundFolderIdFor(catalogName);
		return folderId != null && foldersOnDisk.contains(folderId.id());
	}

	/**
	 * Creates a new engine state with default values.
	 *
	 * @param storageSettings storage configuration options
	 * @return newly created engine state
	 */
	@Nonnull
	private EngineState<LogFileRecordReference> createNewEngineState(@Nonnull StorageSettings storageSettings) {
		// Get all directories in the storage directory to identify active catalogs
		final Path[] directories = FileUtils.listDirectories(storageSettings.storageDirectory());

		// Create new engine state with initial values
		final EngineState<LogFileRecordReference> newEngineState = new EngineState<>(
			STORAGE_PROTOCOL_VERSION,
			1L,  // Initial version
			OffsetDateTime.now(),
			null,  // No WAL file reference initially
			// Extract directory names as active catalogs
			Arrays.stream(directories)
			      .map(it -> it.getName(it.getNameCount() - 1).toString())
			      .sorted()
			      .toArray(String[]::new),
			ArrayUtils.EMPTY_STRING_ARRAY,  // No inactive catalogs initially
			ArrayUtils.EMPTY_STRING_ARRAY   // No read-only catalogs initially
		);

		// Store the newly created engine state
		storeEngineState(newEngineState);
		return newEngineState;
	}

	/**
	 * Joins a catalog folder token onto the configured storage root.
	 *
	 * This is the only place the join is performed for whole-folder operations, and it is deliberately private
	 * — the engine hands down opaque tokens and must never learn the join rule. {@link CatalogFolderId}
	 * validates at construction that a token is a single path segment, so the result cannot escape the root.
	 *
	 * @param folderId token identifying the catalog folder
	 * @return directory the token denotes, which is not guaranteed to exist
	 */
	@Nonnull
	private Path pathOf(@Nonnull CatalogFolderId folderId) {
		return this.storageSettings.storageDirectory().resolve(folderId.id());
	}

	@Override
	public boolean catalogFolderExists(@Nonnull CatalogFolderId folderId) {
		return pathOf(folderId).toFile().exists();
	}

	@Override
	public void dropCatalogFolder(@Nonnull CatalogFolderId folderId) {
		final Path folder = pathOf(folderId);
		if (folder.toFile().exists()) {
			FileUtils.deleteDirectory(folder);
		}
	}

	@Override
	public long catalogFolderSize(@Nonnull CatalogFolderId folderId) {
		final Path folder = pathOf(folderId);
		return folder.toFile().exists() ? FileUtils.getDirectorySize(folder) : 0L;
	}

	@Nonnull
	@Override
	public CatalogFolderId allocateCatalogFolder(
		@Nonnull String catalogName,
		@Nonnull IntSupplier generationSupplier
	) {
		return CatalogFolderAllocator.allocate(
			this.storageSettings.storageDirectory(), catalogName, generationSupplier
		);
	}

	@Nonnull
	@Override
	public CatalogFolderId adoptCatalogFolder(
		@Nonnull CatalogFolderId folderId,
		@Nonnull String catalogName,
		@Nonnull IntSupplier generationSupplier
	) {
		return CatalogFolderAllocator.adopt(
			this.storageSettings.storageDirectory(), folderId, catalogName, generationSupplier
		);
	}

	@Override
	public void recordCatalogNameInFolder(@Nonnull CatalogFolderId folderId, @Nonnull String catalogName) {
		CatalogFolderAllocator.writeCatalogNameMarker(pathOf(folderId), catalogName);
	}

	@Nonnull
	@Override
	public Map<String, Integer> observedFolderGenerationPeaks() {
		return CatalogFolderAllocator.observedPeaks(this.storageSettings.storageDirectory());
	}

	@Override
	public void clearProvisionalCatalogFolderMarker(@Nonnull CatalogFolderId folderId) {
		CatalogFolderAllocator.clearProvisionalMarker(pathOf(folderId));
	}

}
