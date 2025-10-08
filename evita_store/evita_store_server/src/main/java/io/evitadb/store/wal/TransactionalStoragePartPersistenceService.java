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

package io.evitadb.store.wal;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.core.Catalog;
import io.evitadb.core.EntityCollection;
import io.evitadb.store.compressor.AggregatedKeyCompressor;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.model.PersistentStorageDescriptor;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.offsetIndex.OffsetIndex;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import io.evitadb.store.offsetIndex.io.CatalogOffHeapMemoryManager;
import io.evitadb.store.offsetIndex.io.WriteOnlyOffHeapWithFileBackupHandle;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.offsetIndex.model.RecordKey;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.StoragePartPersistenceService;
import io.evitadb.store.spi.model.PersistentStorageHeader;
import io.evitadb.utils.FileUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * This implementation of {@link StoragePartPersistenceService} wraps inner delegate of {@link StoragePartPersistenceService}
 * and adds a temporary layer of {@link OffsetIndex} that is used to store all the mutations that are performed in the
 * transaction. The layer is primarily backed by off-heap memory and is converted to file only when the transaction gets
 * too big or there is not enough memory to store all the mutations and changes in memory. The storage parts are binary
 * chunks that are not used for filtering or sorting entities and just contain the data that needs to be fetched when
 * the entity is found - but these data are natively directly written to a file and therefore this implementation allows
 * to imitate the original behavior of {@link StoragePartPersistenceService} that is used in {@link Catalog} and
 * {@link EntityCollection} persistence services.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class TransactionalStoragePartPersistenceService implements StoragePartPersistenceService {
	private final StoragePartPersistenceService delegate;
	private final Path targetFile;
	private final OffsetIndex offsetIndex;
	private final Set<RecordKey> removedStoragePartKeys = new HashSet<>(64);

	public TransactionalStoragePartPersistenceService(
		long catalogVersion,
		@Nonnull UUID transactionId,
		@Nonnull String name,
		@Nonnull StoragePartPersistenceService delegate,
		@Nonnull StorageOptions storageOptions,
		@Nonnull TransactionOptions transactionOptions,
		@Nonnull CatalogOffHeapMemoryManager offHeapMemoryManager,
		@Nonnull Function<VersionedKryoKeyInputs, VersionedKryo> kryoFactory,
		@Nonnull OffsetIndexRecordTypeRegistry offsetIndexRecordTypeRegistry,
		@Nonnull ObservableOutputKeeper observableOutputKeeper
	) {
		this.delegate = delegate;
		// we create a duplicate offset index that targets temporary file in tx related directory
		this.targetFile = transactionOptions.transactionWorkDirectory()
			.resolve(transactionId.toString())
			.resolve(name + ".tmp");
		this.offsetIndex = new OffsetIndex(
			catalogVersion + 1,
			new OffsetIndexDescriptor(
				new PersistentStorageHeader(1L, FileLocation.EMPTY, this.delegate.getReadOnlyKeyCompressor().getKeys()),
				kryoFactory,
				// we don't care here
				1.0, 0L
			),
			storageOptions,
			offsetIndexRecordTypeRegistry,
			new WriteOnlyOffHeapWithFileBackupHandle(
				this.targetFile,
				storageOptions,
				observableOutputKeeper,
				offHeapMemoryManager
			),
			nonFlushedBlock -> {
				// we don't care here
			},
			oldestRecordTimestamp -> {
				// we don't care here
			}
		);
	}

	@Nonnull
	@Override
	public StoragePartPersistenceService createTransactionalService(@Nonnull UUID transactionId) {
		throw new UnsupportedOperationException("Transactional service cannot be created from transactional service!");
	}

	@Nullable
	@Override
	public <T extends StoragePart> T getStoragePart(long catalogVersion, long storagePartPk, @Nonnull Class<T> containerType) {
		if (this.removedStoragePartKeys.contains(new RecordKey(this.offsetIndex.getIdForRecordType(containerType), storagePartPk))) {
			return null;
		}
		return ofNullable(this.offsetIndex.get(catalogVersion, storagePartPk, containerType))
			.orElseGet(() -> this.delegate.getStoragePart(catalogVersion, storagePartPk, containerType));
	}

	@Nullable
	@Override
	public <T extends StoragePart> byte[] getStoragePartAsBinary(long catalogVersion, long storagePartPk, @Nonnull Class<T> containerType) {
		if (this.removedStoragePartKeys.contains(new RecordKey(this.offsetIndex.getIdForRecordType(containerType), storagePartPk))) {
			return null;
		}
		return ofNullable(this.offsetIndex.getBinary(catalogVersion, storagePartPk, containerType))
			.orElseGet(() -> this.delegate.getStoragePartAsBinary(catalogVersion, storagePartPk, containerType));
	}

	@Override
	public <T extends StoragePart> long putStoragePart(long catalogVersion, @Nonnull T container) {
		// put into tx offset index
		final long storagePartPk = this.offsetIndex.put(catalogVersion, container);
		// delete from removed keys (if present)
		this.removedStoragePartKeys.remove(
			new RecordKey(this.offsetIndex.getIdForRecordType(container.getClass()), storagePartPk)
		);
		return storagePartPk;
	}

	@Override
	public <T extends StoragePart> boolean removeStoragePart(long catalogVersion, long storagePartPk, @Nonnull Class<T> containerType) {
		final boolean addedLayerContains = this.offsetIndex.contains(catalogVersion, storagePartPk, containerType);
		final boolean stableLayerContains = this.delegate.containsStoragePart(catalogVersion, storagePartPk, containerType);
		if (stableLayerContains || addedLayerContains) {
			if (stableLayerContains) {
				this.removedStoragePartKeys.add(new RecordKey(this.offsetIndex.getIdForRecordType(containerType), storagePartPk));
			}
			if (addedLayerContains) {
				return this.offsetIndex.remove(catalogVersion, storagePartPk, containerType);
			}
			return true;
		}
		return false;
	}

	@Override
	public <T extends StoragePart> boolean containsStoragePart(long catalogVersion, long primaryKey, @Nonnull Class<T> containerType) {
		return this.offsetIndex.contains(catalogVersion, primaryKey, containerType) ||
			(!this.removedStoragePartKeys.contains(new RecordKey(this.offsetIndex.getIdForRecordType(containerType), primaryKey))
				&& this.delegate.containsStoragePart(catalogVersion, primaryKey, containerType));
	}

	@Nonnull
	@Override
	public <T extends StoragePart> Stream<T> getEntryStream(@Nonnull Class<T> containerType) {
		final byte recType = this.offsetIndex.getIdForRecordType(containerType);
		final Set<Long> returnedPks = new HashSet<>(64);
		// this is going to be slow, but it's not used in production scenarios
		return Stream.concat(
			this.offsetIndex
				.getEntries()
				.stream()
				.filter(it -> it.getKey().recordType() == recType)
				.peek(it -> returnedPks.add(it.getKey().primaryKey()))
				.map(it -> this.offsetIndex.get(it.getValue(), containerType))
				.filter(Objects::nonNull),
			this.delegate.getEntryStream(containerType)
				.filter(it -> it.getStoragePartPK() != null)
				.filter(it -> !this.removedStoragePartKeys.contains(new RecordKey(recType, it.getStoragePartPK())))
				.filter(it -> !returnedPks.contains(it.getStoragePartPK()))
		);
	}

	@Override
	public int countStorageParts(long catalogVersion) {
		// this is going to be slow, but it's not used in production scenarios
		return this.offsetIndex.count(catalogVersion) + this.delegate.countStorageParts(catalogVersion) -
			this.removedStoragePartKeys.size();
	}

	@Override
	public <T extends StoragePart> int countStorageParts(long catalogVersion, @Nonnull Class<T> containerType) {
		final byte recType = this.offsetIndex.getIdForRecordType(containerType);
		// this is going to be slow, but it's not used in production scenarios
		return this.offsetIndex.count(catalogVersion, containerType) + this.delegate.countStorageParts(catalogVersion, containerType) -
			((int) this.removedStoragePartKeys.stream().filter(it -> it.recordType() == recType).count());
	}

	@Nonnull
	@Override
	public <T extends StoragePart> byte[] serializeStoragePart(@Nonnull T storagePart) {
		return this.delegate.serializeStoragePart(storagePart);
	}

	@Nonnull
	@Override
	public <T extends StoragePart> T deserializeStoragePart(@Nonnull byte[] storagePart, @Nonnull Class<T> containerType) {
		return this.delegate.deserializeStoragePart(storagePart, containerType);
	}

	@Nonnull
	@Override
	public KeyCompressor getReadOnlyKeyCompressor() {
		return new AggregatedKeyCompressor(
			this.offsetIndex.getReadOnlyKeyCompressor(),
			this.delegate.getReadOnlyKeyCompressor()
		);
	}

	@Override
	public long getVersion() {
		return this.delegate.getVersion() + 1;
	}

	@Nonnull
	@Override
	public PersistentStorageDescriptor flush(long catalogVersion) {
		return this.offsetIndex.flush(catalogVersion);
	}

	@Nonnull
	@Override
	public PersistentStorageDescriptor copySnapshotTo(
		long catalogVersion,
		@Nonnull OutputStream outputStream,
		@Nullable IntConsumer progressUpdater,
		@Nullable StoragePart... updatedStorageParts
	) {
		throw new UnsupportedOperationException(
			"Transactional storage part persistence service cannot copy snapshot to a new file!"
		);
	}

	@Override
	public boolean isNew() {
		return this.delegate.isNew();
	}

	@Override
	public void purgeHistoryOlderThan(long lastKnownMinimalActiveVersion) {
		// do nothing, transactional storage has always single reader
	}

	@Override
	public void forgetVolatileData() {
		this.offsetIndex.forgetVolatileData();
	}

	@Override
	public boolean isClosed() {
		return !this.offsetIndex.isOperative() || this.delegate.isClosed();
	}

	@Override
	public void close() {
		// when this service is closed the file is deleted
		this.offsetIndex.close();
		try {
			if (this.targetFile.toFile().exists()) {
				Files.delete(this.targetFile);
			}
		} catch (IOException e) {
			throw new IllegalStateException("Cannot delete temporary file (or its parent directory if empty): " + this.targetFile, e);
		}

		FileUtils.deleteFolderIfEmpty(this.targetFile.getParent());
	}

	@Override
	public String toString() {
		return "TransactionalStoragePartPersistenceService: `" + this.targetFile + '`';
	}
}
