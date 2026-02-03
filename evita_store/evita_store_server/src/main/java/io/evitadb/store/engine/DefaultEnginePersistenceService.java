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
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.metric.event.storage.FileType;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.function.Functions;
import io.evitadb.spi.store.engine.EnginePersistenceService;
import io.evitadb.spi.store.engine.model.EngineState;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.model.reference.TransactionMutationWithWalFileReference;
import io.evitadb.store.offsetIndex.io.OffHeapMemoryManager;
import io.evitadb.store.offsetIndex.io.ReadOnlyFileHandle;
import io.evitadb.store.offsetIndex.io.WriteOnlyFileHandle;
import io.evitadb.store.offsetIndex.io.WriteOnlyOffHeapWithFileBackupHandle;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.wal.EngineMutationLog;
import io.evitadb.store.wal.WalKryoConfigurer;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
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
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
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
			this.engineState = syncEngineStateByFolderContents(this.storageSettings, this.engineState);
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

		// if the mutation log has different log file reference than the engine state, we need to update the engine state
		if (this.mutationLog != null && !Objects.equals(logFileRecordReference, this.mutationLog.getLogFileRecordReference())) {
			log.warn(
				"Engine state WAL file reference {} differs from the actual WAL file reference {}. " +
					"Updating engine state to reflect the actual WAL file reference.",
				logFileRecordReference,
				this.mutationLog.getLogFileRecordReference()
			);
			this.engineState = EngineState.builder(this.engineState)
				.walFileReference(this.mutationLog.getLogFileRecordReference())
				.build();
			storeEngineState(this.engineState);
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

	@Override
	public void storeEngineState(@Nonnull EngineState<LogFileRecordReference> engineState) {
		this.created = false;
		// Validate that the version is incremented by exactly one
		Assert.isPremiseValid(
			(this.engineState == null && engineState.version() == 1) ||
				(this.engineState != null && this.engineState.version() + 1 == engineState.version()),
			this.engineState == null ?
				"Engine state version must be 1 when creating new engine state!" :
				"Engine state version must be incremented by one when storing new engine state! " +
					"Current version: " + this.engineState.version() + ", new version: " + engineState.version()
		);

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

	@Nonnull
	@Override
	public TransactionMutationWithWalFileReference appendWal(
		long version,
		@Nonnull UUID transactionId,
		@Nonnull EngineMutation<?> mutation
	) {
		this.walWriteLock.lock();
		try {
			// Initialize WAL if it doesn't exist yet
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
					this.mutationLog.append(
						transactionMutation,
						// when reading is done, the off-heap memory will be released automatically
						logWriteHandle.toReadOffHeapWithFileBackupReference()
					),
					transactionMutation
				);
			}
		} finally {
			this.walWriteLock.unlock();
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
						"Failed to truncate en engine log file!",
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
	 * Synchronizes the current engine state with the actual contents on disk by analyzing the specified storage directory.
	 * It identifies catalogs present on disk, updates the active and inactive catalog lists in the current engine state,
	 * adds newly discovered catalogs as inactive, and persists any changes to storage.
	 *
	 * @param storageSettings configuration options for persistent storage, including the storage directory
	 * @param engineState the current engine state to be synchronized with the storage directory contents
	 * @return a new {@link EngineState} instance with updated active and inactive catalog lists,
	 *         or the original engine state if no changes are detected
	 */
	@Nonnull
	private EngineState<LogFileRecordReference> syncEngineStateByFolderContents(
		@Nonnull StorageSettings storageSettings,
		@Nonnull EngineState<LogFileRecordReference> engineState
	) {
		// Detect catalogs present on disk and reduce to only previously unknown ones
		final Path[] directories = FileUtils.listDirectories(storageSettings.storageDirectory());
		final LinkedHashSet<String> catalogsOnDisk = new LinkedHashSet<>(directories.length << 1);
		for (final Path dir : directories) {
			catalogsOnDisk.add(dir.getName(dir.getNameCount() - 1).toString());
		}

		// Build new active and inactive catalog lists using ArrayList for simplicity
		final String[] oldActive = engineState.activeCatalogs();
		final String[] oldInactive = engineState.inactiveCatalogs();

		final ArrayList<String> newActive = new ArrayList<>(oldActive.length);
		for (final String catalog : oldActive) {
			if (catalogsOnDisk.contains(catalog)) {
				newActive.add(catalog);
				// keep only unknown catalogs in the set
				catalogsOnDisk.remove(catalog);
			}
		}

		final ArrayList<String> newInactive = new ArrayList<>(oldInactive.length);
		for (final String catalog : oldInactive) {
			if (catalogsOnDisk.contains(catalog)) {
				newInactive.add(catalog);
				// keep only unknown catalogs in the set
				catalogsOnDisk.remove(catalog);
			}
		}
		// Add any previously unknown on-disk catalogs as inactive
		if (!catalogsOnDisk.isEmpty()) {
			for (String catalogName : catalogsOnDisk) {
				log.info("Discovered previously unknown catalog on disk (registering as inactive): {}", catalogName);
				newInactive.add(catalogName);
			}
		}

		// Determine if anything actually changed
		final boolean activeUnchanged = Arrays.equals(
			oldActive, newActive.toArray(ArrayUtils.EMPTY_STRING_ARRAY)
		);
		final boolean inactiveUnchanged = Arrays.equals(
			oldInactive, newInactive.toArray(ArrayUtils.EMPTY_STRING_ARRAY)
		);
		if (activeUnchanged && inactiveUnchanged) {
			// No change at all - return the original engine state
			return engineState;
		}

		// Create new engine state with updated catalogs
		final EngineState<LogFileRecordReference> newEngineState = EngineState.builder(engineState)
			.version(this.engineState.version() + 1)
			.activeCatalogs(newActive.toArray(ArrayUtils.EMPTY_STRING_ARRAY))
			.inactiveCatalogs(newInactive.toArray(ArrayUtils.EMPTY_STRING_ARRAY))
			.build();

		// Store the newly created engine state
		storeEngineState(newEngineState);

		return newEngineState;
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

}
