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

package io.evitadb.store.wal;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogMutabilityMutation;
import io.evitadb.api.requestResponse.system.WriteAheadLogVersionDescriptor;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.store.catalog.DefaultIsolatedWalService;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.offsetIndex.io.CatalogOffHeapMemoryManager;
import io.evitadb.store.offsetIndex.io.WriteOnlyOffHeapWithFileBackupHandle;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.store.spi.EnginePersistenceService;
import io.evitadb.store.spi.IsolatedWalPersistenceService;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
import io.evitadb.store.wal.requestResponse.EngineTransactionChangesContainer;
import io.evitadb.store.wal.requestResponse.EngineTransactionChangesContainer.EngineTransactionChanges;
import io.evitadb.utils.FileUtils;
import io.evitadb.utils.UUIDUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests specific behaviour of EngineMutationLog that is not covered by the shared abstract WAL tests.
 *
 * This test verifies only the Engine-specific method getWriteAheadLogVersionDescriptor using
 * SetCatalogMutabilityMutation as engine-level mutation type.
 */
@DisplayName("EngineMutationLog specifics")
class EngineWriteAheadLogTest {
	private final Path walDirectory = Path.of(System.getProperty("java.io.tmpdir"))
		.resolve("evita")
		.resolve(getClass().getSimpleName());
	private final Pool<Kryo> kryoPool = new Pool<>(false, false, 1) {
		@Nonnull
		@Override
		protected Kryo create() {
			return KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		}
	};
	private final Path isolatedWalFilePath = this.walDirectory.resolve("isolatedWal.tmp");
	private final ObservableOutputKeeper observableOutputKeeper = new ObservableOutputKeeper(
		EnginePersistenceService.ENGINE_NAME,
		StorageOptions.builder()
			.compress(false)
			.build(),
		Mockito.mock(Scheduler.class)
	);
	private final CatalogOffHeapMemoryManager offHeapMemoryManager = new CatalogOffHeapMemoryManager(
		EnginePersistenceService.ENGINE_NAME, 10_000_000, 128
	);
	private EngineMutationLog wal;

	@BeforeEach
	void setUp() {
		// clear the WAL directory
		FileUtils.deleteDirectory(this.walDirectory);
		this.wal = createEngineWalWithLargeSize();
	}

	@AfterEach
	void tearDown() throws Exception {
		this.observableOutputKeeper.close();
		this.wal.close();
		// clear the WAL directory
		FileUtils.deleteDirectory(this.walDirectory);
	}

	/**
	 * Verifies that getWriteAheadLogVersionDescriptor returns proper EngineTransactionChangesContainer
	 * with accurate metadata and concatenated change strings for the requested version range.
	 */
	@Test
	@DisplayName("shouldReturnDescriptorWithEngineTransactionChangesWhenRangeValid")
	void shouldReturnDescriptorWithEngineTransactionChangesWhenRangeValid() {
		final int[] txSizes = new int[]{2, 1, 3, 2};
		final List<TxData> txData = writeEngineWal(txSizes, null);

		final long previousKnownVersion = 1L; // we want versions 2 and 3
		final long requestedVersion = 3L;
		final OffsetDateTime introducedAt = OffsetDateTime.now();

		final WriteAheadLogVersionDescriptor descriptor = this.wal.getWriteAheadLogVersionDescriptor(
			requestedVersion, previousKnownVersion, introducedAt
		);
		assertNotNull(descriptor);
		assertEquals(requestedVersion, descriptor.version());
		assertEquals(introducedAt, descriptor.processedTimestamp());

		final EngineTransactionChanges[] changes = ((EngineTransactionChangesContainer) descriptor.transactionChanges())
			.getTransactionChanges();

		assertEquals(2, changes.length);

		// verify version 2
		final TxData tx2 = txData.get(1);
		assertEquals(2L, changes[0].version());
		assertEquals(tx2.commitTimestamp(), changes[0].commitTimestamp());
		assertEquals(tx2.mutationCount(), changes[0].mutationCount());
		assertEquals(tx2.mutationSizeInBytes(), changes[0].mutationSizeInBytes());
		assertArrayEquals(new String[]{tx2.expectedConcatenatedChanges()}, changes[0].changes());

		// verify version 3
		final TxData tx3 = txData.get(2);
		assertEquals(3L, changes[1].version());
		assertEquals(tx3.commitTimestamp(), changes[1].commitTimestamp());
		assertEquals(tx3.mutationCount(), changes[1].mutationCount());
		assertEquals(tx3.mutationSizeInBytes(), changes[1].mutationSizeInBytes());
		assertArrayEquals(new String[]{tx3.expectedConcatenatedChanges()}, changes[1].changes());
	}

	/**
	 * Verifies that getWriteAheadLogVersionDescriptor returns null when there are no transactions
	 * available from previousKnownVersion + 1.
	 */
	@Test
	@DisplayName("shouldReturnNullDescriptorWhenNoTransactionsAvailableFromRange")
	void shouldReturnNullDescriptorWhenNoTransactionsAvailableFromRange() {
		final int[] txSizes = new int[]{1, 2, 1};
		final List<TxData> txData = writeEngineWal(txSizes, null);
		assertEquals(3, txData.size());

		final long previousKnownVersion = 3L; // start would be 4 which doesn't exist
		final long requestedVersion = 3L;
		final OffsetDateTime introducedAt = OffsetDateTime.now();

		final WriteAheadLogVersionDescriptor descriptor = this.wal.getWriteAheadLogVersionDescriptor(
			requestedVersion, previousKnownVersion, introducedAt
		);
		assertNull(descriptor);
	}

	@Nonnull
	private EngineMutationLog createEngineWalWithLargeSize() {
		return new EngineMutationLog(
			0L,
			EnginePersistenceService::getWalFileName,
			this.walDirectory,
			this.kryoPool,
			StorageOptions.builder()
				.compress(false)
				.build(),
			TransactionOptions.builder().walFileSizeBytes(Long.MAX_VALUE).build(),
			Mockito.mock(Scheduler.class)
		);
	}

	/**
	 * Writes engine WAL entries composed of SetCatalogMutabilityMutation and appends TransactionMutations to WAL.
	 * Returns list of TxData for each written transaction to verify descriptor content later.
	 */
	@Nonnull
	private List<TxData> writeEngineWal(@Nonnull int[] transactionSizes, @Nullable OffsetDateTime initialTimestamp) {
		final IsolatedWalPersistenceService walPersistenceService = new DefaultIsolatedWalService(
			UUID.randomUUID(),
			KryoFactory.createKryo(WalKryoConfigurer.INSTANCE),
			new WriteOnlyOffHeapWithFileBackupHandle(
				this.isolatedWalFilePath,
				StorageOptions.builder(StorageOptions.temporary())
					.compress(false)
					.build(),
				this.observableOutputKeeper,
				this.offHeapMemoryManager
			)
		);

		final List<TxData> result = new ArrayList<>(transactionSizes.length);
		OffsetDateTime timestamp = initialTimestamp == null ? OffsetDateTime.now() : initialTimestamp;
		for (int i = 0; i < transactionSizes.length; i++) {
			final int txSize = transactionSizes[i];
			final long version = i + 1L;
			final StringBuilder expectedConcat = new StringBuilder(txSize * 32);

			for (int j = 0; j < txSize; j++) {
				final boolean mutable = ((i + j) & 1) == 0;
				final SetCatalogMutabilityMutation mutation = new SetCatalogMutabilityMutation("engine-cat", mutable);
				// write mutation into isolated WAL
				walPersistenceService.write(version, mutation);
				// expected string is simple concatenation of toString() values (no separators)
				expectedConcat.append(mutation.toString());
			}

			final OffHeapWithFileBackupReference walReference = walPersistenceService.getWalReference();
			final TransactionMutation txMutation = new TransactionMutation(
				UUIDUtil.randomUUID(),
				version,
				txSize,
				walReference.getContentLength(),
				timestamp
			);

			// append to main WAL
			this.wal.append(txMutation, walReference);

			result.add(new TxData(version, timestamp, txSize, walReference.getContentLength(), expectedConcat.toString()));
			timestamp = timestamp.plusMinutes(1);
		}

		return result;
	}

	/**
	 * Immutable holder for expected data of a single transaction used for assertions.
	 */
	private record TxData(
		long version,
		@Nonnull OffsetDateTime commitTimestamp,
		int mutationCount,
		long mutationSizeInBytes,
		@Nonnull String expectedConcatenatedChanges
	) {
	}
}
