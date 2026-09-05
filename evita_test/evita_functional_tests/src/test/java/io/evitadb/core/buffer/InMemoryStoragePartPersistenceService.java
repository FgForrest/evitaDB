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

package io.evitadb.core.buffer;

import io.evitadb.spi.store.catalog.persistence.StorageDescriptor;
import io.evitadb.spi.store.catalog.persistence.StoragePartPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.compressor.KeyCompressorSnapshot;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.OutputStream;
import java.io.Serial;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

/**
 * In-memory {@link StoragePartPersistenceService} holding its records in a plain {@link Map} keyed by container type
 * and primary key, exactly as the real offset index files them.
 *
 * It exists so that a test can assert what the STORE holds — which is the state a warm-up savepoint has to rewind for
 * every write a root entity mutation issues through {@code DataStoreChanges#putStoragePart} /
 * {@code #removeStoragePart}. A service that only answers calls without remembering them cannot tell a record that was
 * rewound from one that was never written, so it would report a green result for a layer that leaves half-written
 * entity bodies behind.
 *
 * Only the four methods a storage-part write path actually exercises are implemented — read, write, remove and the
 * existence check. **Every other method throws** {@link UnsupportedOperationException} rather than returning a
 * plausible-looking default: a test that starts depending on flushing, compression or snapshotting must say so
 * explicitly and implement what it needs, instead of silently passing against a zero or a `null`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
class InMemoryStoragePartPersistenceService implements StoragePartPersistenceService<StorageDescriptor> {
	/**
	 * Never-matched ordinal, i.e. "no write fails". Writes are counted from one, so zero can never be reached.
	 */
	private static final int NEVER = 0;
	/**
	 * The key compressor handed to a part that has to compute its own primary key. It throws on every call: no
	 * stand-in part in these tests consults it, and one that starts to must be given a real compressor rather than
	 * silently receiving fabricated ids.
	 */
	private static final KeyCompressor UNSUPPORTED_KEY_COMPRESSOR = new UnsupportedKeyCompressor();

	/**
	 * The records currently held, filed by container type and primary key.
	 */
	private final Map<StoredRecordKey, StoragePart> records = new HashMap<>();
	/**
	 * Number of {@link #putStoragePart} calls served so far, counted from one — including the ones a savepoint
	 * rollback issues to put a pre-image back, which is what lets {@link #failOnPut(int)} target a single write.
	 */
	private int putCount;
	/**
	 * The ordinal of the write that must fail, or {@link #NEVER}. See {@link #failOnPut(int)}.
	 */
	private int failOnPutNumber = NEVER;

	/**
	 * Puts a record in directly, standing in for state persisted by an EARLIER entity mutation — the state a rollback
	 * has to restore rather than remove. Does not count towards {@link #failOnPut(int)}.
	 *
	 * @param part the storage part this service already holds
	 */
	void seed(@Nonnull StoragePart part) {
		this.records.put(
			new StoredRecordKey(part.getClass(), part.getStoragePartPKOrElseThrowException()),
			part
		);
	}

	/**
	 * Makes the given write fail, standing in for the storage failure that makes a multi-part entity fail part-way
	 * through {@code ContainerizedLocalMutationExecutor#commit}.
	 *
	 * The failure is aimed at ONE write by its ordinal rather than at a primary key, because a key that fails forever
	 * would also fail the write a rollback issues to put that key's pre-image back — turning every such test into an
	 * assertion about the catalog barrier instead of about the rollback. A single failing write is also the realistic
	 * shape: a value the store cannot write does not make the store unusable.
	 *
	 * @param ordinal the one-based ordinal of the {@link #putStoragePart} call that must throw
	 */
	void failOnPut(int ordinal) {
		this.failOnPutNumber = ordinal;
	}

	/**
	 * Reads the record currently held under the given container type and primary key.
	 *
	 * @param containerType the storage-part type the record is filed under
	 * @param primaryKey    the storage-part primary key
	 * @return the stored part, or `null` when none is held
	 */
	@Nullable
	StoragePart record(@Nonnull Class<? extends StoragePart> containerType, long primaryKey) {
		return this.records.get(new StoredRecordKey(containerType, primaryKey));
	}

	@Nullable
	@Override
	public <T extends StoragePart> T getStoragePart(
		long catalogVersion, long storagePartPk, @Nonnull Class<T> containerType
	) {
		return containerType.cast(this.records.get(new StoredRecordKey(containerType, storagePartPk)));
	}

	@Override
	public <T extends StoragePart> long putStoragePart(long catalogVersion, @Nonnull T container) {
		// the key is resolved BEFORE the failure check, mirroring the real store: `computeUniquePartIdAndSet` runs
		// inside the write and sets the key on the part, so a write that fails afterwards still leaves the part
		// carrying the key its inverse has to drop
		final Long assignedPrimaryKey = container.getStoragePartPK();
		final long primaryKey = assignedPrimaryKey == null ?
			container.computeUniquePartIdAndSet(UNSUPPORTED_KEY_COMPRESSOR) : assignedPrimaryKey;
		this.putCount++;
		if (this.putCount == this.failOnPutNumber) {
			throw new SimulatedWriteFailure(primaryKey);
		}
		this.records.put(new StoredRecordKey(container.getClass(), primaryKey), container);
		return primaryKey;
	}

	@Override
	public <T extends StoragePart> boolean removeStoragePart(
		long catalogVersion, long storagePartPk, @Nonnull Class<T> containerType
	) {
		return this.records.remove(new StoredRecordKey(containerType, storagePartPk)) != null;
	}

	@Override
	public <T extends StoragePart> boolean containsStoragePart(
		long catalogVersion, long primaryKey, @Nonnull Class<T> containerType
	) {
		return this.records.containsKey(new StoredRecordKey(containerType, primaryKey));
	}

	@Nonnull
	@Override
	public StoragePartPersistenceService<StorageDescriptor> createTransactionalService(@Nonnull UUID transactionId) {
		throw new UnsupportedOperationException("This in-memory service has no transactional view!");
	}

	@Nullable
	@Override
	public <T extends StoragePart> byte[] getStoragePartAsBinary(
		long catalogVersion, long storagePartPk, @Nonnull Class<T> containerType
	) {
		throw new UnsupportedOperationException("This in-memory service holds parts, not their binary form!");
	}

	@Nonnull
	@Override
	public <T extends StoragePart> Stream<T> getEntryStream(@Nonnull Class<T> containerType) {
		throw new UnsupportedOperationException("This in-memory service does not enumerate its records!");
	}

	@Override
	public int countStorageParts(long catalogVersion) {
		throw new UnsupportedOperationException("This in-memory service does not count its records!");
	}

	@Override
	public <T extends StoragePart> int countStorageParts(long catalogVersion, @Nonnull Class<T> containerType) {
		throw new UnsupportedOperationException("This in-memory service does not count its records!");
	}

	@Nonnull
	@Override
	public <T extends StoragePart> byte[] serializeStoragePart(@Nonnull T storagePart) {
		throw new UnsupportedOperationException("This in-memory service does not serialize!");
	}

	@Nonnull
	@Override
	public <T extends StoragePart> T deserializeStoragePart(
		@Nonnull byte[] storagePart, @Nonnull Class<T> containerType
	) {
		throw new UnsupportedOperationException("This in-memory service does not serialize!");
	}

	@Nonnull
	@Override
	public KeyCompressor getReadOnlyKeyCompressor() {
		return UNSUPPORTED_KEY_COMPRESSOR;
	}

	@Nonnull
	@Override
	public KeyCompressorSnapshot getKeyCompressorSnapshot() {
		throw new UnsupportedOperationException("This in-memory service compresses no keys!");
	}

	@Override
	public long getVersion() {
		throw new UnsupportedOperationException("This in-memory service is not versioned!");
	}

	@Override
	public void forgetVolatileData() {
		throw new UnsupportedOperationException("This in-memory service holds no volatile data!");
	}

	@Nonnull
	@Override
	public StorageDescriptor flush(long catalogVersion) {
		throw new UnsupportedOperationException("This in-memory service cannot be flushed!");
	}

	@Nonnull
	@Override
	public StorageDescriptor copySnapshotTo(
		long catalogVersion,
		@Nonnull OutputStream outputStream,
		@Nullable IntConsumer progressConsumer,
		@Nullable StoragePart... updatedStorageParts
	) {
		throw new UnsupportedOperationException("This in-memory service cannot be snapshotted!");
	}

	@Override
	public void purgeHistoryOlderThan(long lastKnownMinimalActiveVersion) {
		throw new UnsupportedOperationException("This in-memory service keeps no history!");
	}

	@Override
	public boolean isNew() {
		throw new UnsupportedOperationException("This in-memory service has no storage to be new!");
	}

	@Override
	public boolean isClosed() {
		throw new UnsupportedOperationException("This in-memory service cannot be closed!");
	}

	@Override
	public void close() {
		throw new UnsupportedOperationException("This in-memory service cannot be closed!");
	}

	/**
	 * Identifies one held record. Container type and primary key together, because the same primary key legitimately
	 * identifies different records of different types — an entity's body and its prices share the entity's key.
	 *
	 * @param containerType the storage-part type the record is filed under
	 * @param primaryKey    the storage-part primary key
	 */
	private record StoredRecordKey(@Nonnull Class<? extends StoragePart> containerType, long primaryKey) {
	}

	/**
	 * The storage failure raised for the write {@link #failOnPut(int)} selected. A dedicated type so a test can assert
	 * it caught the failure it injected rather than an unrelated one.
	 */
	static final class SimulatedWriteFailure extends RuntimeException {
		@Serial private static final long serialVersionUID = 1L;

		/**
		 * @param primaryKey the storage-part primary key whose write was refused
		 */
		SimulatedWriteFailure(long primaryKey) {
			super("Simulated storage failure writing the part with primary key " + primaryKey + "!");
		}
	}

	/**
	 * {@link KeyCompressor} that refuses every call. See {@link #UNSUPPORTED_KEY_COMPRESSOR}.
	 */
	private static final class UnsupportedKeyCompressor implements KeyCompressor {
		@Serial private static final long serialVersionUID = 1L;

		@Nonnull
		@Override
		public Map<Integer, Object> getKeys() {
			throw new UnsupportedOperationException("This key compressor holds no keys!");
		}

		@Override
		public <T extends Comparable<T>> int getId(@Nonnull T key) {
			throw new UnsupportedOperationException("This key compressor assigns no ids!");
		}

		@Nonnull
		@Override
		public <T extends Comparable<T>> OptionalInt getIdIfExists(@Nonnull T key) {
			throw new UnsupportedOperationException("This key compressor assigns no ids!");
		}

		@Nonnull
		@Override
		public <T extends Comparable<T>> T getKeyForId(int id) {
			throw new UnsupportedOperationException("This key compressor holds no keys!");
		}

		@Nullable
		@Override
		public <T extends Comparable<T>> T getKeyForIdIfExists(int id) {
			throw new UnsupportedOperationException("This key compressor holds no keys!");
		}

		@Override
		public int getPeakId() {
			throw new UnsupportedOperationException("This key compressor assigns no ids!");
		}
	}

}
