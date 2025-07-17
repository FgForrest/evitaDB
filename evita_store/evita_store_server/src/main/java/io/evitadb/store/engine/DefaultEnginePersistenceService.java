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
import io.evitadb.function.Functions;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.offsetIndex.io.OffHeapMemoryManager;
import io.evitadb.store.offsetIndex.io.ReadOnlyFileHandle;
import io.evitadb.store.offsetIndex.io.WriteOnlyFileHandle;
import io.evitadb.store.offsetIndex.io.WriteOnlyOffHeapHandle;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.store.spi.EnginePersistenceService;
import io.evitadb.store.spi.model.EngineState;
import io.evitadb.store.spi.model.reference.TransactionMutationWithWalFileReference;
import io.evitadb.store.wal.EngineWriteAheadLog;
import io.evitadb.store.wal.WalKryoConfigurer;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import io.evitadb.utils.FileUtils;
import io.evitadb.utils.FolderLock;
import io.evitadb.utils.IOUtils;
import io.evitadb.utils.UUIDUtil;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Optional;
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
public class DefaultEnginePersistenceService implements EnginePersistenceService {
	/**
	 * Storage configuration options.
	 */
	private final StorageOptions storageOptions;

	/**
	 * Transaction configuration options.
	 */
	private final TransactionOptions transactionOptions;

	/**
	 * Scheduler for asynchronous operations.
	 */
	private final Scheduler scheduler;

	/**
	 * The folder lock instance that is used for safeguarding exclusive access to the catalog storage directory.
	 */
	private final FolderLock folderLock;

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
	 * Handle for writing WAL data to off-heap memory.
	 */
	private final WriteOnlyOffHeapHandle walWriteHandle;

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
	@Nullable private EngineWriteAheadLog writeAheadLog;

	/**
	 * Current state of the engine.
	 */
	private EngineState engineState;

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
		this.storageOptions = storageOptions;
		this.transactionOptions = transactionOptions;
		this.scheduler = scheduler;

		// Initialize off-heap memory manager with transaction memory limits
		this.offHeapMemoryManager = new OffHeapMemoryManager(
			transactionOptions.transactionMemoryBufferLimitSizeBytes(),
			transactionOptions.transactionMemoryRegionCount()
		);

		// Create output keeper for observing and managing outputs
		this.observableOutputKeeper = new ObservableOutputKeeper(
			storageOptions,
			scheduler
		);

		// Initialize handle for writing WAL data to off-heap memory
		this.walWriteHandle = new WriteOnlyOffHeapHandle(
			storageOptions,
			this.observableOutputKeeper,
			this.offHeapMemoryManager
		);

		// Try to acquire lock over storage directory to ensure exclusive access
		this.folderLock = new FolderLock(storageOptions.storageDirectory());
		this.bootstrapFilePath = storageOptions.storageDirectory()
		                                       .resolve(EnginePersistenceService.getBootstrapFileName());

		// We need to do this before we create the write handle, because it will create the file if it doesn't exist.
		final boolean bootstrapFileExists = this.bootstrapFilePath.toFile().exists();

		// Either read existing engine state or create a new one
		if (bootstrapFileExists) {
			this.engineState = readEngineState();
			this.created = false;
		} else {
			this.engineState = createNewEngineState(storageOptions);
			this.created = true;
		}
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
	public EngineState getEngineState() {
		return this.engineState;
	}

	@Override
	public void storeEngineState(@Nonnull EngineState engineState) {
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
				this.storageOptions,
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
	public TransactionMutationWithWalFileReference appendWal(long version, @Nonnull EngineMutation mutation) {
		// Initialize WAL if it doesn't exist yet
		if (this.writeAheadLog == null) {
			this.writeAheadLog = new EngineWriteAheadLog(
				getVersion(),
				EnginePersistenceService::getWalFileName,
				this.storageOptions.storageDirectory(),
				this.walKryoPool,
				this.storageOptions,
				this.transactionOptions,
				this.scheduler
			);
		}

		// Allocate off-heap memory for the mutation, it will be released automatically on buffer release
		this.walWriteHandle.allocateOffHeapMemory();

		// Write the mutation to the WAL and get its size in bytes
		final int mutationSizeInBytes = this.walWriteHandle.checkAndExecute(
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
			UUIDUtil.randomUUID(), version, 1, mutationSizeInBytes, OffsetDateTime.now()
		);

		// Append the transaction mutation to the WAL
		return new TransactionMutationWithWalFileReference(
			this.writeAheadLog.append(
				transactionMutation,
				// when reading is done, the off-heap memory will be released automatically
				this.walWriteHandle.toReadOffHeapReference()
			),
			transactionMutation
		);
	}

	@Nonnull
	@Override
	public Optional<TransactionMutation> getFirstNonProcessedTransactionInWal(long version) {
		if (this.writeAheadLog == null) {
			// If WAL is not initialized, there are no transactions
			return Optional.empty();
		} else {
			// Get the first non-processed transaction from the WAL
			return this.writeAheadLog.getFirstNonProcessedTransaction(
				this.engineState.walFileReference()
			);
		}
	}

	@Nonnull
	@Override
	public Stream<EngineMutation<?>> getCommittedMutationStream(long version) {
		if (this.writeAheadLog == null) {
			// If WAL is not initialized, there are no mutations
			return Stream.empty();
		} else {
			// Get stream of committed mutations from the WAL
			return this.writeAheadLog.getCommittedMutationStream(version);
		}
	}

	@Nonnull
	@Override
	public Stream<EngineMutation<?>> getReversedCommittedMutationStream(@Nullable Long version) {
		if (this.writeAheadLog == null) {
			// If WAL is not initialized, there are no mutations
			return Stream.empty();
		} else {
			// Get stream of committed mutations in reverse order from the WAL
			return this.writeAheadLog.getCommittedReversedMutationStream(version == null ? getVersion() : version);
		}
	}

	@Override
	public long getLastVersionInMutationStream() {
		if (this.writeAheadLog == null) {
			// If WAL is not initialized, return 0 as the last version
			return 0L;
		} else {
			// Get the last written version from the WAL
			return this.writeAheadLog.getLastWrittenVersion();
		}
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			// Close all resources quietly (without throwing exceptions)
			IOUtils.closeQuietly(
				this.walWriteHandle::close,
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
	private EngineState readEngineState() {
		try (
			final ReadOnlyFileHandle readOnlyFileHandle = new ReadOnlyFileHandle(
				null,
				FileType.ENGINE,
				"engine",
				this.bootstrapFilePath,
				this.storageOptions
			)
		) {
			return readOnlyFileHandle.execute(
				observableInput ->
					read(
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
	}

	/**
	 * Creates a new engine state with default values.
	 *
	 * @param storageOptions storage configuration options
	 * @return newly created engine state
	 */
	@Nonnull
	private EngineState createNewEngineState(@Nonnull StorageOptions storageOptions) {
		// Get all directories in the storage directory to identify active catalogs
		final Path[] directories = FileUtils.listDirectories(storageOptions.storageDirectory());

		// Create new engine state with initial values
		final EngineState newEngineState = new EngineState(
			STORAGE_PROTOCOL_VERSION,
			1L,  // Initial version
			OffsetDateTime.now(),
			null,  // No WAL file reference initially
			// Extract directory names as active catalogs
			Arrays.stream(directories)
			      .map(it -> it.getName(it.getNameCount() - 1).toString())
			      .toArray(String[]::new),
			ArrayUtils.EMPTY_STRING_ARRAY,  // No inactive catalogs initially
			ArrayUtils.EMPTY_STRING_ARRAY   // No read-only catalogs initially
		);

		// Store the newly created engine state
		storeEngineState(newEngineState);
		return newEngineState;
	}

}
