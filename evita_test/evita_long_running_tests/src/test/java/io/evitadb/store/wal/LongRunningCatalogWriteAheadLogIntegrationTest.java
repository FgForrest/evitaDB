/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictPolicy;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolution;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaDecorator;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.session.EvitaSession;
import io.evitadb.store.catalog.DefaultIsolatedWalService;
import io.evitadb.store.checksum.Crc32CChecksumFactory;
import io.evitadb.store.compression.CompressionFactory;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.offsetIndex.io.CatalogOffHeapMemoryManager;
import io.evitadb.store.offsetIndex.io.OffHeapWithFileBackupReference;
import io.evitadb.store.offsetIndex.io.WriteOnlyOffHeapWithFileBackupHandle;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.shared.model.FileLocation;
import io.evitadb.store.wal.supplier.MutationSupplier;
import io.evitadb.store.wal.supplier.TransactionMutationWithLocation;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.FileUtils;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.UUIDUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;

import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getWalFileName;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.WAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Long-running integration tests for {@link CatalogWriteAheadLog} verifying basic transaction
 * read/write operations and cache reuse on subsequent access using realistic transaction
 * payload sizes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@SuppressWarnings({"ResultOfMethodCallIgnored", "SameParameterValue"})
@Slf4j
@DisplayName("Catalog Write-Ahead Log Long-Running Tests")
@Tag(STORAGE)
@Tag(WAL)
public class LongRunningCatalogWriteAheadLogIntegrationTest implements EvitaTestSupport {
	private final Path walDirectory = getTestDirectory().resolve(getClass().getSimpleName());
	private final Pool<Kryo> catalogKryoPool = new Pool<>(false, false, 1) {
		@Override
		protected Kryo create() {
			return KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		}
	};
	private final Path isolatedWalFilePath = this.walDirectory.resolve("isolatedWal.tmp");
	private final ObservableOutputKeeper observableOutputKeeper = ObservableOutputKeeper._internalBuild(
		Mockito.mock(Scheduler.class)
	);
	private final CatalogOffHeapMemoryManager noOffHeapMemoryManager = new CatalogOffHeapMemoryManager(
		TEST_CATALOG, 0, 0, Crc32CChecksumFactory.INSTANCE
	);
	private final CatalogOffHeapMemoryManager bigOffHeapMemoryManager = new CatalogOffHeapMemoryManager(
		TEST_CATALOG, 10_000_000, 4, Crc32CChecksumFactory.INSTANCE
	);
	private final int[] txSizes = new int[]{1000, 2000, 3000, 4000, 5000, 7000, 9000};
	private final MockCatalogVersionConsumer offsetConsumer = new MockCatalogVersionConsumer();
	private CatalogWriteAheadLog wal;

	/**
	 * Writes the Write-Ahead Log (WAL) using the provided off-heap memory manager.
	 *
	 * @param isolatedWalFilePath    the path to the isolated WAL file
	 * @param observableOutputKeeper the observable output keeper
	 * @param wal                    the Write-Ahead Log to write to
	 * @param offHeapMemoryManager   the off-heap memory manager to use
	 * @param transactionSizes       an array of transaction sizes
	 * @return a map of catalog versions to corresponding mutations
	 */
	@Nonnull
	public static Map<Long, List<Mutation>> writeWal(
		@Nonnull CatalogOffHeapMemoryManager offHeapMemoryManager,
		int[] transactionSizes,
		@Nullable OffsetDateTime initialTimestamp,
		@Nonnull Path isolatedWalFilePath,
		@Nonnull ObservableOutputKeeper observableOutputKeeper,
		@Nonnull CatalogWriteAheadLog wal
	) {
		final DataGenerator dataGenerator = new DataGenerator.Builder()
			.withPriceLists(DataGenerator.PRICE_LIST_BASIC)
			.withCurrencies(DataGenerator.CURRENCY_CZK)
			.build();
		final CatalogSchema catalogSchema = CatalogSchema._internalBuild(
			TEST_CATALOG,
			NamingConvention.generate(TEST_CATALOG),
			null,
			EnumSet.allOf(CatalogEvolutionMode.class),
			EmptyEntitySchemaAccessor.INSTANCE
		);
		final EvitaSession mockSession = Mockito.mock(EvitaSession.class);
		Mockito.when(mockSession.getCatalogSchema()).thenReturn(new CatalogSchemaDecorator(catalogSchema));

		final DefaultIsolatedWalService walPersistenceService = new DefaultIsolatedWalService(
			TEST_CATALOG,
			UUID.randomUUID(),
			new ConflictResolution(ConflictPolicy.NONE),
			KryoFactory.createKryo(WalKryoConfigurer.INSTANCE),
			new WriteOnlyOffHeapWithFileBackupHandle(
				isolatedWalFilePath,
				StorageOptions.DEFAULT_OUTPUT_BUFFER_SIZE,
				false,
				observableOutputKeeper,
				offHeapMemoryManager,
				Crc32CChecksumFactory.INSTANCE,
				CompressionFactory.NO_COMPRESSION
			)
		);

		final long lastWrittenCatalogVersion = wal.getLastWrittenVersion();
		OffsetDateTime timestamp = initialTimestamp == null ? OffsetDateTime.now() : initialTimestamp;
		final Map<Long, List<Mutation>> txInMutations = CollectionUtils.createHashMap(transactionSizes.length);
		for (int i = 0; i < transactionSizes.length; i++) {
			int txSize = transactionSizes[i];
			final LinkedList<Mutation> mutations = dataGenerator
				.generateEntities(
					dataGenerator.getSampleProductSchema(
						mockSession,
						EntitySchemaBuilder::toInstance
					),
					(serializable, faker) -> null,
					42 + lastWrittenCatalogVersion
				)
				.limit(txSize)
				.map(EntityBuilder::toMutation)
				.flatMap(Optional::stream)
				.collect(Collectors.toCollection(LinkedList::new));

			final long catalogVersion = Math.max(0, lastWrittenCatalogVersion) + i + 1;
			for (Mutation mutation : mutations) {
				walPersistenceService.write(catalogVersion, mutation);
			}

			final OffHeapWithFileBackupReference walReference = walPersistenceService.getWalReference();
			final TransactionMutation transactionMutation = new TransactionMutation(
				UUIDUtil.randomUUID(),
				catalogVersion,
				mutations.size(),
				walReference.getContentLength(),
				timestamp
			);

			final long start = wal.getWalFilePath().toFile().length();
			final LogFileRecordReference reference = wal.append(
				transactionMutation,
				walReference
			);

			final TransactionMutationWithLocation txMutation = new TransactionMutationWithLocation(
				transactionMutation,
				new FileLocation(start, (int) (wal.getWalFilePath().toFile().length() - start)),
				wal.getWalFileIndex()
			);
			txMutation.withCumulativeChecksum(reference.cumulativeChecksum());
			mutations.addFirst(txMutation);
			txInMutations.put(catalogVersion, mutations);

			timestamp = timestamp.plusMinutes(1);
		}
		return txInMutations;
	}

	@BeforeEach
	void setUp() throws IOException {
		cleanTestSubDirectory(getClass().getSimpleName());
		this.walDirectory.toFile().mkdirs();
		this.wal = createCatalogWriteAheadLogOfLargeEnoughSize();
	}

	@AfterEach
	void tearDown() throws IOException {
		this.observableOutputKeeper.close();
		this.wal.close();
		FileUtils.deleteDirectory(this.walDirectory);
	}

	@Nonnull
	private CatalogWriteAheadLog createCatalogWriteAheadLogOfLargeEnoughSize() {
		return new CatalogWriteAheadLog(
			0L,
			TEST_CATALOG,
			new LogFileRecordReference(index -> getWalFileName(TEST_CATALOG, index)),
			this.walDirectory,
			this.catalogKryoPool,
			new StorageSettings(
				StorageOptions.builder()
					.compress(false)
					.build(),
				TransactionOptions.builder()
					.walFileSizeBytes(Long.MAX_VALUE)
					.build()
			),
			Mockito.mock(Scheduler.class),
			this.offsetConsumer,
			firstActiveCatalogVersion -> {
			}
		);
	}

	private void createCachedSupplierReadAndVerifyFrom(
		Map<Long, List<Mutation>> txInMutations, int[] transactionSizes, int index
	) {
		try (final MutationSupplier<CatalogBoundMutation> supplier = this.wal.createSupplier(index + 1, null)) {
			assertEquals(1, supplier.getTransactionsRead());
			readAndVerifyWal(txInMutations, transactionSizes, index);
		}
	}

	/**
	 * Writes the Write-Ahead Log (WAL) using the provided off-heap memory manager.
	 *
	 * @param offHeapMemoryManager the off-heap memory manager to use
	 * @param transactionSizes     an array of transaction sizes
	 * @return a map of catalog versions to corresponding mutations
	 */
	@Nonnull
	private Map<Long, List<Mutation>> writeWal(
		@Nonnull CatalogOffHeapMemoryManager offHeapMemoryManager, int[] transactionSizes
	) {
		return writeWal(
			offHeapMemoryManager, transactionSizes, null, this.isolatedWalFilePath, this.observableOutputKeeper,
			this.wal
		);
	}

	/**
	 * Reads and verifies the Write-Ahead Log (WAL) using the provided transaction mutations map.
	 *
	 * @param txInMutations    a map of catalog versions to corresponding mutations
	 * @param transactionSizes an array of transaction sizes
	 * @param startIndex       the index to start reading from
	 */
	private void readAndVerifyWal(
		@Nonnull Map<Long, List<Mutation>> txInMutations, int[] transactionSizes, int startIndex
	) {
		long lastCatalogVersion = startIndex;
		final Iterator<CatalogBoundMutation> mutationIterator = this.wal.getCommittedMutationStream(startIndex + 1)
			.iterator();
		int txRead = 0;
		while (mutationIterator.hasNext()) {
			txRead++;
			final Mutation mutation = mutationIterator.next();
			assertInstanceOf(TransactionMutation.class, mutation);

			final TransactionMutation transactionMutation = (TransactionMutation) mutation;
			final List<Mutation> mutationsInTx = txInMutations.get(transactionMutation.getVersion());
			assertTransactionMutationEquals(
				(TransactionMutation) mutationsInTx.get(0),
				transactionMutation
			);
			for (int i = 0; i < transactionMutation.getMutationCount(); i++) {
				final Mutation mutationInTx = mutationIterator.next();
				assertEquals(mutationsInTx.get(i + 1), mutationInTx);
			}

			lastCatalogVersion = transactionMutation.getVersion();
			log.info("Transaction {} verified.", transactionMutation.getVersion());
		}

		assertEquals(transactionSizes.length, lastCatalogVersion);
		assertEquals(txRead, transactionSizes.length - startIndex);
	}

	@Tag(SLOW)
	@Test
	@DisplayName("should write and read small amount of transactions and reuse cache on next access")
	void shouldWriteAndReadSmallAmountOfTransactionsAndReuseCacheOnNextAccess() {
		final int[] aFewTransactions = {1, 2, 3, 2, 1};
		final Map<Long, List<Mutation>> txInMutations = writeWal(
			this.bigOffHeapMemoryManager, aFewTransactions);
		readAndVerifyWal(txInMutations, aFewTransactions, 0);

		createCachedSupplierReadAndVerifyFrom(txInMutations, aFewTransactions, 4);
		createCachedSupplierReadAndVerifyFrom(txInMutations, aFewTransactions, 3);
		createCachedSupplierReadAndVerifyFrom(txInMutations, aFewTransactions, 2);
		createCachedSupplierReadAndVerifyFrom(txInMutations, aFewTransactions, 1);
		createCachedSupplierReadAndVerifyFrom(txInMutations, aFewTransactions, 0);
	}

	@Tag(SLOW)
	@Test
	@DisplayName("should read all transactions using off-heap isolated WAL")
	void shouldReadAllTransactionsUsingOffHeapIsolatedWal() {
		final Map<Long, List<Mutation>> txInMutations = writeWal(
			this.bigOffHeapMemoryManager,
			this.txSizes
		);
		readAndVerifyWal(txInMutations, this.txSizes, 0);
	}

	@Tag(SLOW)
	@Test
	@DisplayName("should read all transactions using file isolated WAL")
	void shouldReadAllTransactionsUsingFileIsolatedWal() {
		final Map<Long, List<Mutation>> txInMutations = writeWal(
			this.noOffHeapMemoryManager,
			this.txSizes
		);
		readAndVerifyWal(txInMutations, this.txSizes, 0);
	}

	/**
	 * Compares two {@link TransactionMutation} instances by their logical transaction
	 * fields only, ignoring location-specific fields like {@code transactionSpan} and
	 * {@code walFileIndex} that may differ between write and read.
	 */
	private static void assertTransactionMutationEquals(
		@Nonnull TransactionMutation expected,
		@Nonnull TransactionMutation actual
	) {
		assertEquals(expected.getTransactionId(), actual.getTransactionId());
		assertEquals(expected.getVersion(), actual.getVersion());
		assertEquals(expected.getMutationCount(), actual.getMutationCount());
		assertEquals(expected.getWalSizeInBytes(), actual.getWalSizeInBytes());
		assertEquals(expected.getCommitTimestamp(), actual.getCommitTimestamp());
	}

	/**
	 * Mock consumer for catalog version updates.
	 */
	private static class MockCatalogVersionConsumer implements LongConsumer {
		@Getter
		private final List<Long> catalogVersions = new LinkedList<>();

		@Override
		public void accept(long value) {
			this.catalogVersions.add(value);
		}
	}
}
