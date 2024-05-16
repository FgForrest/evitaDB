/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.offsetIndex.OffsetIndex;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import io.evitadb.store.offsetIndex.io.OffHeapMemoryManager;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.StoragePartPersistenceService;
import io.evitadb.store.spi.exception.PersistenceServiceClosed;
import io.evitadb.store.wal.TransactionalStoragePartPersistenceService;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * This implementation of {@link StoragePartPersistenceService} stores the parts into {@link OffsetIndex}, that is
 * mapped to a single file on disk.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class OffsetIndexStoragePartPersistenceService implements StoragePartPersistenceService {
	/**
	 * Logical name of the file that backs the {@link OffsetIndex}.
	 */
	protected final String name;
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
	@Nonnull protected final OffHeapMemoryManager offHeapMemoryManager;
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

	public OffsetIndexStoragePartPersistenceService(
		long catalogVersion,
		@Nonnull String name,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull OffsetIndex offsetIndex,
		@Nonnull OffHeapMemoryManager offHeapMemoryManager,
		@Nonnull ObservableOutputKeeper observableOutputKeeper,
		@Nonnull Function<VersionedKryoKeyInputs, VersionedKryo> kryoFactory
	) {
		this.catalogVersion = catalogVersion;
		this.name = name;
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
		if (offsetIndex.isOperative()) {
			return this.offsetIndex.get(catalogVersion, storagePartPk, containerType);
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Nullable
	@Override
	public <T extends StoragePart> byte[] getStoragePartAsBinary(long catalogVersion, long storagePartPk, @Nonnull Class<T> containerType) {
		if (offsetIndex.isOperative()) {
			return this.offsetIndex.getBinary(catalogVersion, storagePartPk, containerType);
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Override
	public <T extends StoragePart> long putStoragePart(long catalogVersion, @Nonnull T container) {
		if (offsetIndex.isOperative()) {
			return this.offsetIndex.put(catalogVersion, container);
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Override
	public <T extends StoragePart> boolean removeStoragePart(long catalogVersion, long storagePartPk, @Nonnull Class<T> containerType) {
		if (offsetIndex.isOperative()) {
			return offsetIndex.remove(catalogVersion, storagePartPk, containerType);
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Override
	public <T extends StoragePart> boolean containsStoragePart(long catalogVersion, long primaryKey, @Nonnull Class<T> containerType) {
		if (offsetIndex.isOperative()) {
			return offsetIndex.contains(catalogVersion, primaryKey, containerType);
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Nonnull
	@Override
	public <T extends StoragePart> Stream<T> getEntryStream(@Nonnull Class<T> containerType) {
		if (offsetIndex.isOperative()) {
			final byte recType = offsetIndex.getIdForRecordType(containerType);
			return offsetIndex
				.getEntries()
				.stream()
				.filter(it -> it.getKey().recordType() == recType)
				.map(it -> offsetIndex.get(it.getValue(), containerType))
				.filter(Objects::nonNull);
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Override
	public <T extends StoragePart> int countStorageParts(long catalogVersion, @Nonnull Class<T> containerType) {
		if (offsetIndex.isOperative()) {
			return this.offsetIndex.count(catalogVersion, containerType);
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Nonnull
	@Override
	public <T extends StoragePart> byte[] serializeStoragePart(@Nonnull T storagePart) {
		return offsetIndex.executeWithKryo(
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
		return offsetIndex.executeWithKryo(
			kryo -> kryo.readObject(
				new Input(storagePart), containerType
			)
		);
	}

	@Nonnull
	@Override
	public KeyCompressor getReadOnlyKeyCompressor() {
		if (offsetIndex.isOperative()) {
			return this.offsetIndex.getReadOnlyKeyCompressor();
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Override
	public long getVersion() {
		if (offsetIndex.isOperative()) {
			return offsetIndex.getVersion();
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Override
	public void forgetVolatileData() {
		if (offsetIndex.isOperative()) {
			this.offsetIndex.forgetVolatileData();
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Nonnull
	@Override
	public OffsetIndexDescriptor flush(long catalogVersion) {
		if (offsetIndex.isOperative()) {
			return this.offsetIndex.flush(catalogVersion);
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Nonnull
	@Override
	public OffsetIndexDescriptor copySnapshotTo(@Nonnull Path newFilePath, long catalogVersion) {
		if (offsetIndex.isOperative()) {
			return this.offsetIndex.copySnapshotTo(newFilePath, catalogVersion);
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Override
	public boolean isNew() {
		if (offsetIndex.isOperative()) {
			return this.offsetIndex.getFileOffsetIndexLocation() == null;
		} else {
			throw new PersistenceServiceClosed();
		}
	}

	@Override
	public void purgeHistoryEqualAndLaterThan(@Nullable Long minimalActiveCatalogVersion) {
		if (offsetIndex.isOperative()) {
			this.offsetIndex.purge(catalogVersion);
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
