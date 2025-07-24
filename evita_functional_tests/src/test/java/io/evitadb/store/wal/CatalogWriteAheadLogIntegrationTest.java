/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaDecorator;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.EvitaSession;
import io.evitadb.core.async.Scheduler;
import io.evitadb.store.catalog.DefaultIsolatedWalService;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.offsetIndex.io.OffHeapMemoryManager;
import io.evitadb.store.offsetIndex.io.WriteOnlyOffHeapWithFileBackupHandle;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.store.spi.IsolatedWalPersistenceService;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
import io.evitadb.store.spi.model.reference.WalFileReference;
import io.evitadb.store.wal.CatalogWriteAheadLog.FirstAndLastCatalogVersions;
import io.evitadb.store.wal.supplier.MutationSupplier;
import io.evitadb.store.wal.supplier.TransactionMutationWithLocation;
import io.evitadb.test.TestConstants;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.FileUtils;
import io.evitadb.utils.NamingConvention;
import io.evitadb.utils.UUIDUtil;
import lombok.Getter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;

import static io.evitadb.store.spi.CatalogPersistenceService.WAL_FILE_SUFFIX;
import static io.evitadb.store.spi.CatalogPersistenceService.getIndexFromWalFileName;
import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test verifying the behaviour of {@link CatalogWriteAheadLog}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class CatalogWriteAheadLogIntegrationTest {
	private final Path walDirectory = Path.of(System.getProperty("java.io.tmpdir")).resolve("evita").resolve(getClass().getSimpleName());
	private final Pool<Kryo> catalogKryoPool = new Pool<>(false, false, 1) {
		@Override
		protected Kryo create() {
			return KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		}
	};
	private final Path isolatedWalFilePath = walDirectory.resolve("isolatedWal.tmp");
	private final ObservableOutputKeeper observableOutputKeeper = new ObservableOutputKeeper(
		TEST_CATALOG,
		StorageOptions.builder()
			/* there are tests that rely on standard size of mutations on disk in this class */
			.compress(false)
			.build(),
		Mockito.mock(Scheduler.class)
	);
	private final OffHeapMemoryManager noOffHeapMemoryManager = new OffHeapMemoryManager(TEST_CATALOG, 0, 0);
	private final OffHeapMemoryManager bigOffHeapMemoryManager = new OffHeapMemoryManager(TEST_CATALOG, 10_000_000, 128);
	private final int[] txSizes = new int[]{2000, 3000, 4000, 5000, 7000, 9000, 1_000};
	private final MockCatalogVersionConsumer offsetConsumer = new MockCatalogVersionConsumer();
	private CatalogWriteAheadLog wal;

	@BeforeEach
	void setUp() {
		// clear the WAL directory
		FileUtils.deleteDirectory(walDirectory);
		wal = createCatalogWriteAheadLogOfLargeEnoughSize();
	}

	@AfterEach
	void tearDown() throws IOException {
		observableOutputKeeper.close();
		wal.close();
		// clear the WAL directory
		FileUtils.deleteDirectory(walDirectory);
	}

	@Tag(LONG_RUNNING_TEST)
	@Test
	void shouldWriteAndRealSmallAmountOfTransactionsAndReuseCacheOnNextAccess() {
		final int[] aFewTransactions = {1, 2, 3, 2, 1};
		final Map<Long, List<Mutation>> txInMutations = writeWal(bigOffHeapMemoryManager, aFewTransactions);
		readAndVerifyWal(txInMutations, aFewTransactions, 0);

		createCachedSupplierReadAndVerifyFrom(txInMutations, aFewTransactions, 4);
		createCachedSupplierReadAndVerifyFrom(txInMutations, aFewTransactions, 3);
		createCachedSupplierReadAndVerifyFrom(txInMutations, aFewTransactions, 2);
		createCachedSupplierReadAndVerifyFrom(txInMutations, aFewTransactions, 1);
		createCachedSupplierReadAndVerifyFrom(txInMutations, aFewTransactions, 0);
	}

	@Test
	void shouldWriteAndReadWalOverMultipleFiles() throws IOException {
		this.wal.close();
		this.wal = createCatalogWriteAheadLogOfSmallSize();

		final int[] transactionSizes = {10, 15, 20, 15, 10};
		final Map<Long, List<Mutation>> txInMutations = writeWal(bigOffHeapMemoryManager, transactionSizes);
		readAndVerifyWal(txInMutations, transactionSizes, 0);

		createCachedSupplierReadAndVerifyFrom(txInMutations, transactionSizes, 4);
		createCachedSupplierReadAndVerifyFrom(txInMutations, transactionSizes, 3);
		createCachedSupplierReadAndVerifyFrom(txInMutations, transactionSizes, 2);
		createCachedSupplierReadAndVerifyFrom(txInMutations, transactionSizes, 1);
		createCachedSupplierReadAndVerifyFrom(txInMutations, transactionSizes, 0);
	}

	@Test
	void shouldReadFirstAndLastCatalogVersionOfPreviousWalFiles() throws IOException {
		this.wal.close();
		wal = createCatalogWriteAheadLogOfSmallSize();

		final int[] transactionSizes = {10, 15, 20, 15, 10};
		final Map<Long, List<Mutation>> txInMutations = writeWal(bigOffHeapMemoryManager, transactionSizes);

		// list all existing WAL files and remove the oldest ones when their count exceeds the limit
		final File[] walFiles = this.walDirectory.toFile().listFiles(
			(dir, name) -> name.endsWith(WAL_FILE_SUFFIX)
		);
		// first sort the files from oldest to newest according to their index in the file name
		Arrays.sort(
			walFiles,
			Comparator.comparingInt(f -> getIndexFromWalFileName(TEST_CATALOG, f.getName()))
		);

		assertEquals(3, walFiles.length);
		final FirstAndLastCatalogVersions versionFirstFile = CatalogWriteAheadLog.getFirstAndLastCatalogVersionsFromWalFile(walFiles[0]);
		assertEquals(1, versionFirstFile.firstCatalogVersion());
		assertEquals(2, versionFirstFile.lastCatalogVersion());

		final FirstAndLastCatalogVersions versionsSecondFile = CatalogWriteAheadLog.getFirstAndLastCatalogVersionsFromWalFile(walFiles[1]);
		assertEquals(3, versionsSecondFile.firstCatalogVersion());
		assertEquals(3, versionsSecondFile.lastCatalogVersion());
	}

	@Test
	void shouldWriteAndReadWalOverMultipleFilesInReversedOrder() throws IOException {
		this.wal.close();
		wal = createCatalogWriteAheadLogOfSmallSize();

		final int[] transactionSizes = {10, 15, 20, 15, 10};
		final Map<Long, List<Mutation>> txInMutations = writeWal(bigOffHeapMemoryManager, transactionSizes);
		readAndVerifyWalInReverse(txInMutations, transactionSizes, 4);
	}

	@Test
	void shouldFindProperTransactionUUID() {
		final int[] aFewTransactions = {1, 2, 3, 2, 1};
		final Map<Long, List<Mutation>> txInMutations = writeWal(bigOffHeapMemoryManager, aFewTransactions);

		for (int i = 1; i < aFewTransactions.length; i++) {
			final List<Mutation> mutations = txInMutations.get((long) i);
			final List<Mutation> nextMutations = txInMutations.get((long) i + 1);
			final TransactionMutationWithLocation transactionMutation = (TransactionMutationWithLocation) mutations.get(0);
			final Optional<TransactionMutation> txId = wal.getFirstNonProcessedTransaction(
				new WalFileReference(
					TEST_CATALOG,
					transactionMutation.getWalFileIndex(),
					transactionMutation.getTransactionSpan()
				)
			);
			assertTrue(txId.isPresent());
			assertEquals(nextMutations.get(0), txId.get());
		}

		// last transaction must return empty value (there is no next transaction to transition to)
		final List<Mutation> mutations = txInMutations.get((long) aFewTransactions.length);
		final TransactionMutationWithLocation transactionMutation = (TransactionMutationWithLocation) mutations.get(0);
		final Optional<TransactionMutation> txId = wal.getFirstNonProcessedTransaction(
			new WalFileReference(
				TEST_CATALOG,
				transactionMutation.getWalFileIndex(),
				transactionMutation.getTransactionSpan()
			)
		);
		assertFalse(txId.isPresent());
	}

	@Tag(LONG_RUNNING_TEST)
	@Test
	void shouldReadAllTransactionsUsingOffHeapIsolatedWal() {
		final Map<Long, List<Mutation>> txInMutations = writeWal(bigOffHeapMemoryManager, txSizes);
		readAndVerifyWal(txInMutations, txSizes, 0);
	}

	@Tag(LONG_RUNNING_TEST)
	@Test
	void shouldReadAllTransactionsUsingFileIsolatedWal() {
		final Map<Long, List<Mutation>> txInMutations = writeWal(noOffHeapMemoryManager, txSizes);
		readAndVerifyWal(txInMutations, txSizes, 0);
	}

	@Test
	void shouldCorrectlyReportFirstAvailableTimestamp() throws IOException {
		this.wal.close();
		this.wal = createCatalogWriteAheadLogOfSmallSize();

		final int justEnoughSize = 20;
		final int[] transactionSizes = new int[7];
		Arrays.fill(transactionSizes, justEnoughSize);

		final OffsetDateTime initialTimestamp = OffsetDateTime.now();
		writeWal(bigOffHeapMemoryManager, transactionSizes, initialTimestamp);
		this.wal.walProcessedUntil(Long.MAX_VALUE);
		this.wal.removeWalFiles();

		// only one call would occur with the latest version possible
		assertEquals(1, offsetConsumer.getCatalogVersions().size());
		assertEquals(3, offsetConsumer.getCatalogVersions().get(0));
	}

	@Nonnull
	private CatalogWriteAheadLog createCatalogWriteAheadLogOfSmallSize() {
		return new CatalogWriteAheadLog(
			0L,
			TEST_CATALOG,
			walDirectory,
			catalogKryoPool,
			StorageOptions.builder()
				/* there are tests that rely on standard size of mutations on disk in this class */
				.compress(false)
				.build(),
			TransactionOptions.builder()
				.walFileCountKept(5)
				.walFileSizeBytes(16_384)
				.build(),
			Mockito.mock(Scheduler.class),
			offsetConsumer,
			firstActiveCatalogVersion -> {}
		);
	}

	@Nonnull
	private CatalogWriteAheadLog createCatalogWriteAheadLogOfLargeEnoughSize() {
		return new CatalogWriteAheadLog(
			0L,
			TEST_CATALOG,
			walDirectory,
			catalogKryoPool,
			StorageOptions.builder()
				/* there are tests that rely on standard size of mutations on disk in this class */
				.compress(false)
				.build(),
			TransactionOptions.builder().walFileSizeBytes(Long.MAX_VALUE).build(),
			Mockito.mock(Scheduler.class),
			offsetConsumer,
			firstActiveCatalogVersion -> {}
		);
	}

	private void createCachedSupplierReadAndVerifyFrom(Map<Long, List<Mutation>> txInMutations, int[] aFewTransactions, int index) {
		try (final MutationSupplier supplier = wal.createSupplier(index + 1, null)) {
			assertEquals(1, supplier.getTransactionsRead());
			readAndVerifyWal(txInMutations, aFewTransactions, index);
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
	private Map<Long, List<Mutation>> writeWal(@Nonnull OffHeapMemoryManager offHeapMemoryManager, int[] transactionSizes) {
		return writeWal(offHeapMemoryManager, transactionSizes, null);
	}

	/**
	 * Writes the Write-Ahead Log (WAL) using the provided off-heap memory manager.
	 *
	 * @param offHeapMemoryManager the off-heap memory manager to use
	 * @param transactionSizes     an array of transaction sizes
	 * @return a map of catalog versions to corresponding mutations
	 */
	@Nonnull
	private Map<Long, List<Mutation>> writeWal(@Nonnull OffHeapMemoryManager offHeapMemoryManager, int[] transactionSizes, @Nullable OffsetDateTime initialTimestamp) {
		final DataGenerator dataGenerator = new DataGenerator.Builder()
			.withPriceLists(DataGenerator.PRICE_LIST_BASIC)
			.withCurrencies(DataGenerator.CURRENCY_CZK)
			.build();
		final CatalogSchema catalogSchema = CatalogSchema._internalBuild(
			TestConstants.TEST_CATALOG,
			NamingConvention.generate(TestConstants.TEST_CATALOG),
			EnumSet.allOf(CatalogEvolutionMode.class),
			EmptyEntitySchemaAccessor.INSTANCE
		);
		final EvitaSession mockSession = Mockito.mock(EvitaSession.class);
		Mockito.when(mockSession.getCatalogSchema()).thenReturn(new CatalogSchemaDecorator(catalogSchema));

		final IsolatedWalPersistenceService walPersistenceService = new DefaultIsolatedWalService(
			UUID.randomUUID(),
			KryoFactory.createKryo(WalKryoConfigurer.INSTANCE),
			new WriteOnlyOffHeapWithFileBackupHandle(
				isolatedWalFilePath,
				StorageOptions.builder(StorageOptions.temporary())
					/* there are tests that rely on standard size of mutations on disk in this class */
					.compress(false)
					.build(),
				observableOutputKeeper,
				offHeapMemoryManager
			)
		);

		OffsetDateTime timestamp = initialTimestamp == null ? OffsetDateTime.now() : initialTimestamp;
		final Map<Long, List<Mutation>> txInMutations = CollectionUtils.createHashMap(transactionSizes.length);
		for (int i = 0; i < transactionSizes.length; i++) {
			int txSize = transactionSizes[i];
			final LinkedList<Mutation> mutations = dataGenerator.generateEntities(
					dataGenerator.getSampleProductSchema(
						mockSession,
						EntitySchemaBuilder::toInstance
					),
					(serializable, faker) -> null,
					42
				)
				.limit(txSize)
				.map(EntityBuilder::toMutation)
				.flatMap(Optional::stream)
				.collect(Collectors.toCollection(LinkedList::new));

			final long catalogVersion = i + 1;
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

			final long start = this.wal.getWalFilePath().toFile().length();
			this.wal.append(
				transactionMutation,
				walReference
			);

			mutations.addFirst(
				new TransactionMutationWithLocation(
					transactionMutation,
					new FileLocation(start, (int) (this.wal.getWalFilePath().toFile().length() - start)),
					this.wal.getWalFileIndex()
				)
			);
			txInMutations.put(catalogVersion, mutations);

			timestamp = timestamp.plusMinutes(1);
		}
		return txInMutations;
	}

	/**
	 * Reads and verifies the Write-Ahead Log (WAL) using the provided transaction
	 * mutations map.
	 *
	 * @param txInMutations    a map of catalog versions to corresponding mutations
	 * @param transactionSizes an array of transaction sizes
	 */
	private void readAndVerifyWal(@Nonnull Map<Long, List<Mutation>> txInMutations, int[] transactionSizes, int startIndex) {
		long lastCatalogVersion = startIndex;
		final Iterator<Mutation> mutationIterator = wal.getCommittedMutationStream(startIndex + 1).iterator();
		int txRead = 0;
		while (mutationIterator.hasNext()) {
			txRead++;
			final Mutation mutation = mutationIterator.next();
			assertInstanceOf(TransactionMutation.class, mutation);

			final TransactionMutation transactionMutation = (TransactionMutation) mutation;
			final List<Mutation> mutationsInTx = txInMutations.get(transactionMutation.getCatalogVersion());
			assertEquals(mutationsInTx.get(0), transactionMutation);
			for (int i = 0; i < transactionMutation.getMutationCount(); i++) {
				final Mutation mutationInTx = mutationIterator.next();
				assertEquals(mutationsInTx.get(i + 1), mutationInTx);
			}

			lastCatalogVersion = transactionMutation.getCatalogVersion();
		}

		assertEquals(transactionSizes.length, lastCatalogVersion);
		assertEquals(txRead, transactionSizes.length - startIndex);
	}

	/**
	 * Reads and verifies the Write-Ahead Log (WAL) using the provided transaction
	 * mutations map in backward fashion.
	 *
	 * @param txInMutations    a map of catalog versions to corresponding mutations
	 * @param transactionSizes an array of transaction sizes
	 */
	private void readAndVerifyWalInReverse(@Nonnull Map<Long, List<Mutation>> txInMutations, int[] transactionSizes, int startIndex) {
		long firstCatalogVersion = -1L;
		long catalogVersion = startIndex + 1;
		final Iterator<Mutation> mutationIterator = wal.getCommittedReversedMutationStream(catalogVersion).iterator();
		int txRead = 0;
		while (mutationIterator.hasNext()) {
			txRead++;
			final List<Mutation> mutationsInTx = txInMutations.get(catalogVersion);
			TransactionMutation transactionMutation = null;
			for (int i = mutationsInTx.size(); i > 0; i--) {
				final Mutation mutationInTx = mutationIterator.next();
				if (mutationInTx instanceof TransactionMutation txMut) {
					transactionMutation = txMut;
					assertEquals(mutationsInTx.get(i - transactionMutation.getMutationCount() - 1), mutationInTx);
				} else {
					assertEquals(mutationsInTx.get(i), mutationInTx);
				}
			}

			assertNotNull(transactionMutation);
			if (firstCatalogVersion == -1L) {
				firstCatalogVersion = transactionMutation.getCatalogVersion();
			}
			catalogVersion--;
		}

		assertEquals(transactionSizes.length, firstCatalogVersion);
		assertEquals(transactionSizes.length - (transactionSizes.length - startIndex) + 1, txRead);
	}

	private static class MockCatalogVersionConsumer implements LongConsumer {
		@Getter private final List<Long> catalogVersions = new LinkedList<>();

		@Override
		public void accept(long value) {
			this.catalogVersions.add(value);
		}

	}

}
