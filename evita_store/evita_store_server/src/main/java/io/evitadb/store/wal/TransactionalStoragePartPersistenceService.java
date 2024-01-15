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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.wal;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.core.Catalog;
import io.evitadb.core.EntityCollection;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.model.PersistentStorageDescriptor;
import io.evitadb.store.model.StoragePart;
import io.evitadb.store.offsetIndex.OffsetIndex;
import io.evitadb.store.offsetIndex.OffsetIndexDescriptor;
import io.evitadb.store.offsetIndex.io.OffHeapMemoryManager;
import io.evitadb.store.offsetIndex.io.WriteOnlyOffHeapWithFileBackupHandle;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.offsetIndex.model.RecordKey;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.StoragePartPersistenceService;
import io.evitadb.store.spi.model.PersistentStorageHeader;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
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
 * TODO In January 2024:
 * ---------------------
 * - rewire EntityCollection + Catalog to use this StoragePartPersistenceService in their persistence services inside transaction
 * - contents of this storage part layer will be always completely removed on transaction commit / rollback
 * - separately there will be a WAL which will record all the mutations in its own offset index and that will copy
 *   the data directly to shared WAL in single thread
 * - the WAL logic (maybe of name TransactionManager?!) will be also responsible for conflict detection and consistency
 *   checks
 * - then there will be a WAL processor that will replay those mutations using standard implementations of StoragePartPersistenceService
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class TransactionalStoragePartPersistenceService implements StoragePartPersistenceService {
	private final StoragePartPersistenceService delegate;
	private final Path targetFile;
	private final OffsetIndex offsetIndex;
	private final Set<RecordKey> removedStoragePartKeys = new HashSet<>(64);

	public TransactionalStoragePartPersistenceService(
		@Nonnull UUID transactionId,
		@Nonnull String name,
		@Nonnull StoragePartPersistenceService delegate,
		@Nonnull StorageOptions storageOptions,
		@Nonnull OffHeapMemoryManager offHeapMemoryManager,
		@Nonnull Function<VersionedKryoKeyInputs, VersionedKryo> kryoFactory,
		@Nonnull OffsetIndexRecordTypeRegistry offsetIndexRecordTypeRegistry,
		@Nonnull ObservableOutputKeeper observableOutputKeeper
	) {
		this.delegate = delegate;
		// we create a duplicate offset index that targets temporary file in tx related directory
		this.targetFile = storageOptions.transactionWorkDirectory()
			.resolve(transactionId.toString())
			.resolve(name + ".tmp");
		this.offsetIndex = new OffsetIndex(
			new OffsetIndexDescriptor(
				new PersistentStorageHeader(1L, null, Collections.emptyMap()),
				kryoFactory
			),
			storageOptions,
			offsetIndexRecordTypeRegistry,
			new WriteOnlyOffHeapWithFileBackupHandle(
				this.targetFile,
				observableOutputKeeper,
				offHeapMemoryManager
			)
		);
	}

	@Nonnull
	@Override
	public StoragePartPersistenceService createTransactionalService(@Nonnull UUID transactionId) {
		throw new UnsupportedOperationException("Transactional service cannot be created from transactional service!");
	}

	@Nullable
	@Override
	public <T extends StoragePart> T getStoragePart(long storagePartPk, @Nonnull Class<T> containerType) {
		if (this.removedStoragePartKeys.contains(new RecordKey(this.offsetIndex.getIdForRecordType(containerType), storagePartPk))) {
			return null;
		}
		return ofNullable(this.offsetIndex.get(storagePartPk, containerType))
			.orElseGet(() -> this.delegate.getStoragePart(storagePartPk, containerType));
	}

	@Nullable
	@Override
	public <T extends StoragePart> byte[] getStoragePartAsBinary(long storagePartPk, @Nonnull Class<T> containerType) {
		if (this.removedStoragePartKeys.contains(new RecordKey(this.offsetIndex.getIdForRecordType(containerType), storagePartPk))) {
			return null;
		}
		return ofNullable(this.offsetIndex.getBinary(storagePartPk, containerType))
			.orElseGet(() -> this.delegate.getStoragePartAsBinary(storagePartPk, containerType));
	}

	@Override
	public <T extends StoragePart> long putStoragePart(long catalogVersion, @Nonnull T container) {
		// delete from removed keys (if present)
		this.removedStoragePartKeys.remove(
			new RecordKey(this.offsetIndex.getIdForRecordType(container.getClass()), catalogVersion)
		);
		// put into tx offset index
		return this.offsetIndex.put(catalogVersion, container);
	}

	@Override
	public <T extends StoragePart> boolean removeStoragePart(long storagePartPk, @Nonnull Class<T> containerType) {
		final boolean addedLayerContains = this.offsetIndex.contains(storagePartPk, containerType);
		final boolean stableLayerContains = this.delegate.containsStoragePart(storagePartPk, containerType);
		if (stableLayerContains || addedLayerContains) {
			if (stableLayerContains) {
				this.removedStoragePartKeys.add(new RecordKey(this.offsetIndex.getIdForRecordType(containerType), storagePartPk));
				this.delegate.removeStoragePart(storagePartPk, containerType);
			}
			if (addedLayerContains) {
				return this.offsetIndex.remove(storagePartPk, containerType);
			}
			return true;
		}
		return false;
	}

	@Override
	public <T extends StoragePart> boolean containsStoragePart(long primaryKey, @Nonnull Class<T> containerType) {
		return this.offsetIndex.contains(primaryKey, containerType) ||
			(!this.removedStoragePartKeys.contains(new RecordKey(offsetIndex.getIdForRecordType(containerType), primaryKey))
				&& this.delegate.containsStoragePart(primaryKey, containerType));
	}

	@Nonnull
	@Override
	public <T extends StoragePart> Stream<T> getEntryStream(@Nonnull Class<T> containerType) {
		final byte recType = offsetIndex.getIdForRecordType(containerType);
		final Set<Long> returnedPks = new HashSet<>(64);
		// this is going to be slow, but it's not used in production scenarios
		return Stream.concat(
			this.offsetIndex
				.getEntries()
				.stream()
				.filter(it -> it.getKey().recordType() == recType)
				.peek(it -> returnedPks.add(it.getKey().primaryKey()))
				.map(it -> offsetIndex.get(it.getValue(), containerType))
				.filter(Objects::nonNull),
			this.delegate.getEntryStream(containerType)
				.filter(it -> !this.removedStoragePartKeys.contains(new RecordKey(recType, it.getStoragePartPK())))
				.filter(it -> !returnedPks.contains(it.getStoragePartPK()))
		);
	}

	@Override
	public <T extends StoragePart> int countStorageParts(@Nonnull Class<T> containerType) {
		final byte recType = offsetIndex.getIdForRecordType(containerType);
		// this is going to be slow, but it's not used in production scenarios
		return this.offsetIndex.count(containerType) + this.delegate.countStorageParts(containerType) -
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
		return delegate.deserializeStoragePart(storagePart, containerType);
	}

	@Nonnull
	@Override
	public KeyCompressor getReadOnlyKeyCompressor() {
		return this.offsetIndex.getReadOnlyKeyCompressor();
	}

	@Override
	public long getVersion() {
		return delegate.getVersion() + 1;
	}

	@Nonnull
	@Override
	public PersistentStorageDescriptor flush(long catalogVersion) {
		return this.offsetIndex.flush(catalogVersion);
	}

	@Override
	public boolean isNew() {
		return this.delegate.isNew();
	}

	@Override
	public boolean isClosed() {
		return !this.offsetIndex.isOperative() || this.delegate.isClosed();
	}

	@Override
	public void close() {
		// when this service is closed the file is deleted
		this.offsetIndex.close();
		if (this.targetFile.toFile().exists()) {
			Assert.isPremiseValid(
				this.targetFile.toFile().delete(),
				"Cannot delete temporary file: " + this.targetFile
			);
		}
	}

	@Override
	public String toString() {
		return "TransactionalStoragePartPersistenceService: `" + targetFile + '`';
	}
}
