/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import com.esotericsoftware.kryo.io.ByteBufferOutput;
import com.esotericsoftware.kryo.io.Input;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.core.metric.event.storage.FileType;
import io.evitadb.core.metric.event.storage.OffsetIndexFlushEvent;
import io.evitadb.core.metric.event.storage.OffsetIndexRecordTypeCountChangedEvent;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.offsetIndex.OffsetIndex;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import io.evitadb.store.offsetIndex.io.CatalogOffHeapMemoryManager;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.StoragePartPersistenceService;
import io.evitadb.store.spi.exception.PersistenceServiceClosed;
import io.evitadb.store.wal.TransactionalStoragePartPersistenceService;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.OutputStream;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

/**
 * This implementation of {@link StoragePartPersistenceService} stores the parts into {@link OffsetIndex}, that is
 * mapped to a single file on disk.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class OffsetIndexStoragePartPersistenceService implements StoragePartPersistenceService {
	/**
	 * Name of the catalog the persistence service relates to - used for observability.
	 */
	private final String catalogName;
	/**
	 * Logical name of the file that backs the {@link OffsetIndex} - used for observability.
	 */
	protected final String name;
	/**
	 * Type of the file that backs the {@link OffsetIndex} - used for observability.
	 */
	private final FileType fileType;
	/**
	 * Version of the catalog the storage parts are related to.
	 */
	protected final long catalogVersion;
	/**
	 * Configuration settings related to transaction.
	 */
	@Nonnull
	protected final TransactionOptions transactionOptions;
	/**
	 * Memory key-value store for entities.
	 */
	@Nonnull protected final OffsetIndex offsetIndex;
	/**
	 * Memory manager for off-heap memory.
	 */
	@Nonnull protected final CatalogOffHeapMemoryManager offHeapMemoryManager;
	/**
	 * This instance keeps references to the {@link ObservableOutput} instances that internally keep large buffers in
	 * {@link ObservableOutput#getBuffer()} to use them for serialization. There buffers are not necessary when there are
	 * no updates to the catalog / collection, so it's wise to get rid of them if there is no actual need.
	 */
	@Nonnull protected final ObservableOutputKeeper observableOutputKeeper;
	/**
	 * Factory for {@link VersionedKryo} instances.
	 */
	@Nonnull protected final Function<VersionedKryoKeyInputs, VersionedKryo> kryoFactory;
	/**
	 * Last observed histogram of record types.
	 */
	private Map<String, Integer> lastObservedHistogram;

	public OffsetIndexStoragePartPersistenceService(
		long catalogVersion,
		@Nonnull String catalogName,
		@Nonnull String name,
		@Nonnull FileType fileType,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull OffsetIndex offsetIndex,
		@Nonnull CatalogOffHeapMemoryManager offHeapMemoryManager,
		@Nonnull ObservableOutputKeeper observableOutputKeeper,
		@Nonnull Function<VersionedKryoKeyInputs, VersionedKryo> kryoFactory
	) {
		this.catalogVersion = catalogVersion;
		this.catalogName = catalogName;
		this.name = name;
		this.fileType = fileType;
		this.transactionOptions = transactionOptions;
		this.offsetIndex = offsetIndex;
		this.offHeapMemoryManager = offHeapMemoryManager;
		this.observableOutputKeeper = observableOutputKeeper;
		this.kryoFactory = kryoFactory;
	}

	@Nonnull
	@Override
	public StoragePartPersistenceService createTransactionalService(@Nonnull UUID transactionId) {
		return new TransactionalStoragePartPersistenceService(
			this.catalogVersion,
			transactionId,
			this.name,
			this,
			this.offsetIndex.getStorageOptions(),
			this.transactionOptions,
			this.offHeapMemoryManager,
			this.kryoFactory,
			this.offsetIndex.getRecordTypeRegistry(),
			this.observableOutputKeeper
		);
	}

	@Override
	public <T extends StoragePart> T getStoragePart(long catalogVersion, long storagePartPk, @Nonnull Class<T> containerType) {
		if (this.offsetIndex.isOperative()) {
			return this.offsetIndex.get(catalogVersion, storagePartPk, containerType);
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Nullable
	@Override
	public <T extends StoragePart> byte[] getStoragePartAsBinary(long catalogVersion, long storagePartPk, @Nonnull Class<T> containerType) {
		if (this.offsetIndex.isOperative()) {
			return this.offsetIndex.getBinary(catalogVersion, storagePartPk, containerType);
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Override
	public <T extends StoragePart> long putStoragePart(long catalogVersion, @Nonnull T container) {
		if (this.offsetIndex.isOperative()) {
			return this.offsetIndex.put(catalogVersion, container);
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Override
	public <T extends StoragePart> boolean removeStoragePart(long catalogVersion, long storagePartPk, @Nonnull Class<T> containerType) {
		if (this.offsetIndex.isOperative()) {
			return this.offsetIndex.remove(catalogVersion, storagePartPk, containerType);
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Override
	public <T extends StoragePart> boolean containsStoragePart(long catalogVersion, long primaryKey, @Nonnull Class<T> containerType) {
		if (this.offsetIndex.isOperative()) {
			return this.offsetIndex.contains(catalogVersion, primaryKey, containerType);
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Nonnull
	@Override
	public <T extends StoragePart> Stream<T> getEntryStream(@Nonnull Class<T> containerType) {
		if (this.offsetIndex.isOperative()) {
			final byte recType = this.offsetIndex.getIdForRecordType(containerType);
			return this.offsetIndex
				.getEntries()
				.stream()
				.filter(it -> it.getKey().recordType() == recType)
				.map(it -> this.offsetIndex.get(it.getValue(), containerType))
				.filter(Objects::nonNull);
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Override
	public <T extends StoragePart> int countStorageParts(long catalogVersion) {
		if (this.offsetIndex.isOperative()) {
			return this.offsetIndex.count(catalogVersion);
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Override
	public <T extends StoragePart> int countStorageParts(long catalogVersion, @Nonnull Class<T> containerType) {
		if (this.offsetIndex.isOperative()) {
			return this.offsetIndex.count(catalogVersion, containerType);
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Nonnull
	@Override
	public <T extends StoragePart> byte[] serializeStoragePart(@Nonnull T storagePart) {
		return this.offsetIndex.executeWithKryo(
			kryo -> {
				final ByteBufferOutput output = new ByteBufferOutput(8192, -1);
				kryo.writeObject(output, storagePart);
				return output.toBytes();
			}
		);
	}

	@Nonnull
	@Override
	public <T extends StoragePart> T deserializeStoragePart(@Nonnull byte[] storagePart, @Nonnull Class<T> containerType) {
		return this.offsetIndex.executeWithKryo(
			kryo -> kryo.readObject(
				new Input(storagePart), containerType
			)
		);
	}

	@Nonnull
	@Override
	public KeyCompressor getReadOnlyKeyCompressor() {
		if (this.offsetIndex.isOperative()) {
			return this.offsetIndex.getReadOnlyKeyCompressor();
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Override
	public long getVersion() {
		if (this.offsetIndex.isOperative()) {
			return this.offsetIndex.getVersion();
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Override
	public void forgetVolatileData() {
		if (this.offsetIndex.isOperative()) {
			this.offsetIndex.forgetVolatileData();
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Nonnull
	@Override
	public OffsetIndexDescriptor flush(long catalogVersion) {
		if (this.offsetIndex.isOperative()) {
			final OffsetIndexFlushEvent event = new OffsetIndexFlushEvent(
				this.catalogName,
				this.fileType,
				this.name
			);

			final long previousVersion = this.offsetIndex.getVersion();
			final OffsetIndexDescriptor newDescriptor = this.offsetIndex.flush(catalogVersion);

			// emit events only if the version has changed (i.e. the file was flushed)
			if (newDescriptor.version() > previousVersion) {
				// emit event
				event.finish(
					this.offsetIndex.count(catalogVersion),
					this.offsetIndex.getTotalSizeIncludingVolatileData(),
					this.offsetIndex.getMaxRecordSizeBytes(),
					newDescriptor.getFileSize(),
					this.offsetIndex.getTotalSizeBytes(),
					this.offsetIndex.getOldestRecordKeptTimestamp().orElse(null)
				).commit();

				// emit event for record type count changes
				final Map<String, Integer> histogram = this.offsetIndex.getHistogram();
				histogram.forEach(
					(recordType, count) -> {
						final int lastCount = this.lastObservedHistogram == null ?
							0 : this.lastObservedHistogram.getOrDefault(recordType, 0);
						if (lastCount != count) {
							// emit event
							new OffsetIndexRecordTypeCountChangedEvent(
								this.catalogName,
								this.fileType,
								this.name,
								recordType,
								count
							).commit();
						}
					}
				);
				this.lastObservedHistogram = histogram;
			}

			return newDescriptor;
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Nonnull
	@Override
	public OffsetIndexDescriptor copySnapshotTo(
		long catalogVersion,
		@Nonnull OutputStream outputStream,
		@Nullable IntConsumer progressConsumer,
		@Nullable StoragePart... updatedStorageParts
	) {
		if (this.offsetIndex.isOperative()) {
			return this.offsetIndex.copySnapshotTo(outputStream, progressConsumer, catalogVersion, updatedStorageParts);
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Override
	public boolean isNew() {
		if (this.offsetIndex.isOperative()) {
			return this.offsetIndex.getFileOffsetIndexLocation() == FileLocation.EMPTY;
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Override
	public void purgeHistoryOlderThan(long lastKnownMinimalActiveVersion) {
		if (this.offsetIndex.isOperative()) {
			this.offsetIndex.purge(lastKnownMinimalActiveVersion - 1L);
		}
	}

	@Override
	public boolean isClosed() {
		return !this.offsetIndex.isOperative();
	}

	@Override
	public void close() {
		if (this.offsetIndex.isOperative()) {
			this.offsetIndex.close();
		}
	}

}
