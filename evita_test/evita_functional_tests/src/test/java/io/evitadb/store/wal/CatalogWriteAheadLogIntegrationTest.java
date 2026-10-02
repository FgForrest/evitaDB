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
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
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
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.store.catalog.DefaultIsolatedWalService;
import io.evitadb.store.checksum.Crc32CChecksumFactory;
import io.evitadb.store.compression.CompressionFactory;
import io.evitadb.store.compression.ZipCompressionFactory;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.model.reference.TransactionMutationWithWalFileReference;
import io.evitadb.store.offsetIndex.io.CatalogOffHeapMemoryManager;
import io.evitadb.store.offsetIndex.io.OffHeapWithFileBackupReference;
import io.evitadb.store.offsetIndex.io.WriteOnlyOffHeapWithFileBackupHandle;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.shared.model.FileLocation;
import io.evitadb.store.wal.AbstractMutationLog.FirstAndLastVersionsInWalFile;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.WAL_FILE_SUFFIX;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getWalFileName;
import io.evitadb.spi.store.engine.exception.WriteAheadLogCorruptedException;

import static io.evitadb.store.wal.CatalogWriteAheadLog.getFirstAndLastVersionsFromWalFile;
import static io.evitadb.store.wal.CatalogWriteAheadLog.getIndexFromWalFileName;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.WAL;

/**
 * Integration tests for verifying the behavior of {@link CatalogWriteAheadLog}.
 *
 * These tests exercise the full WAL lifecycle including writing transactions with realistic
 * entity mutations, reading them back via mutation streams, and verifying correct behavior
 * across multiple WAL files.
 *
 * The tests are organized into nested classes by feature area:
 * - Transaction Read/Write Tests: verify basic WAL read/write operations and cache reuse
 * - Multi-File WAL Tests: verify WAL rotation and reading across multiple files
 * - Transaction Lookup Tests: verify finding transactions by UUID
 * - Timestamp Reporting Tests: verify correct timestamp reporting for WAL files
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@SuppressWarnings({"ResultOfMethodCallIgnored", "SameParameterValue"})
@Slf4j
@DisplayName("Catalog Write-Ahead Log Integration Tests")
@Tag(STORAGE)
@Tag(WAL)
public class CatalogWriteAheadLogIntegrationTest implements EvitaTestSupport {
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
	private final CatalogOffHeapMemoryManager bigOffHeapMemoryManager = new CatalogOffHeapMemoryManager(
		TEST_CATALOG, 10_000_000, 4, Crc32CChecksumFactory.INSTANCE
	);
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
	private CatalogWriteAheadLog createCatalogWriteAheadLogOfSmallSize() {
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
					.walFileCountKept(5)
					.walFileSizeBytes(16_384)
					.build()
			),
			Mockito.mock(Scheduler.class),
			this.offsetConsumer,
			firstActiveCatalogVersion -> {
			}
		);
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

	/**
	 * Reads and verifies the Write-Ahead Log (WAL) using the provided transaction mutations map in backward fashion.
	 *
	 * @param txInMutations    a map of catalog versions to corresponding mutations
	 * @param transactionSizes an array of transaction sizes
	 * @param startIndex       the index to start reading from
	 */
	private void readAndVerifyWalInReverse(
		@Nonnull Map<Long, List<Mutation>> txInMutations,
		int[] transactionSizes,
		int startIndex
	) {
		long firstCatalogVersion = -1L;
		long catalogVersion = startIndex + 1;
		final Iterator<CatalogBoundMutation> mutationIterator = this.wal
			.getCommittedReversedMutationStream(catalogVersion)
			.iterator();
		int txRead = 0;
		while (mutationIterator.hasNext()) {
			txRead++;
			final List<Mutation> mutationsInTx = txInMutations.get(catalogVersion);
			TransactionMutation transactionMutation = null;
			for (int i = mutationsInTx.size(); i > 0; i--) {
				final Mutation mutationInTx = mutationIterator.next();
				if (mutationInTx instanceof TransactionMutation txMut) {
					transactionMutation = txMut;
					assertTransactionMutationEquals(
						(TransactionMutation) mutationsInTx.get(i - transactionMutation.getMutationCount() - 1),
						txMut
					);
				} else {
					assertEquals(mutationsInTx.get(i), mutationInTx);
				}
			}

			assertNotNull(transactionMutation);
			if (firstCatalogVersion == -1L) {
				firstCatalogVersion = transactionMutation.getVersion();
			}
			catalogVersion--;
		}

		assertEquals(transactionSizes.length, firstCatalogVersion);
		assertEquals(transactionSizes.length - (transactionSizes.length - startIndex) + 1, txRead);
	}

	/**
	 * Nested tests for multi-file WAL operations.
	 */
	@Nested
	@DisplayName("Multi-File WAL Tests")
	class MultiFileWalTests {

		@Test
		@DisplayName("should write and read WAL over multiple files")
		void shouldWriteAndReadWalOverMultipleFiles() throws IOException {
			CatalogWriteAheadLogIntegrationTest.this.wal.close();
			CatalogWriteAheadLogIntegrationTest.this.wal = createCatalogWriteAheadLogOfSmallSize();

			final int[] transactionSizes = {10, 15, 20, 15, 10};
			final Map<Long, List<Mutation>> txInMutations = writeWal(
				CatalogWriteAheadLogIntegrationTest.this.bigOffHeapMemoryManager, transactionSizes);
			readAndVerifyWal(txInMutations, transactionSizes, 0);

			createCachedSupplierReadAndVerifyFrom(txInMutations, transactionSizes, 4);
			createCachedSupplierReadAndVerifyFrom(txInMutations, transactionSizes, 3);
			createCachedSupplierReadAndVerifyFrom(txInMutations, transactionSizes, 2);
			createCachedSupplierReadAndVerifyFrom(txInMutations, transactionSizes, 1);
			createCachedSupplierReadAndVerifyFrom(txInMutations, transactionSizes, 0);
		}

		@Test
		@DisplayName("should read first and last catalog version of previous WAL files")
		void shouldReadFirstAndLastCatalogVersionOfPreviousWalFiles() throws IOException {
			CatalogWriteAheadLogIntegrationTest.this.wal.close();
			CatalogWriteAheadLogIntegrationTest.this.wal = createCatalogWriteAheadLogOfSmallSize();

			final int[] transactionSizes = {10, 15, 20, 15, 10};
			writeWal(CatalogWriteAheadLogIntegrationTest.this.bigOffHeapMemoryManager, transactionSizes);

			final File[] walFiles = CatalogWriteAheadLogIntegrationTest.this.walDirectory.toFile().listFiles(
				(dir, name) -> name.endsWith(WAL_FILE_SUFFIX)
			);
			Arrays.sort(
				walFiles,
				Comparator.comparingInt(f -> getIndexFromWalFileName(f.getName()))
			);

			assertEquals(3, walFiles.length);
			final FirstAndLastVersionsInWalFile versionFirstFile = getFirstAndLastVersionsFromWalFile(walFiles[0], WriteAheadLogCorruptedException.WalKind.CATALOG);
			assertEquals(1, versionFirstFile.firstVersion());
			assertEquals(2, versionFirstFile.lastVersion());

			final FirstAndLastVersionsInWalFile versionsSecondFile = getFirstAndLastVersionsFromWalFile(walFiles[1], WriteAheadLogCorruptedException.WalKind.CATALOG);
			assertEquals(3, versionsSecondFile.firstVersion());
			assertEquals(3, versionsSecondFile.lastVersion());
		}

		@Test
		@DisplayName("should write and read WAL over multiple files in reversed order")
		void shouldWriteAndReadWalOverMultipleFilesInReversedOrder() throws IOException {
			CatalogWriteAheadLogIntegrationTest.this.wal.close();
			CatalogWriteAheadLogIntegrationTest.this.wal = createCatalogWriteAheadLogOfSmallSize();

			final int[] transactionSizes = {10, 15, 20, 15, 10};
			final Map<Long, List<Mutation>> txInMutations = writeWal(
				CatalogWriteAheadLogIntegrationTest.this.bigOffHeapMemoryManager, transactionSizes);
			readAndVerifyWalInReverse(txInMutations, transactionSizes, 4);
		}
	}

	/**
	 * Nested tests for transaction lookup functionality.
	 */
	@Nested
	@DisplayName("Transaction Lookup Tests")
	class TransactionLookupTests {

		@Test
		@DisplayName("should find proper transaction UUID")
		void shouldFindProperTransactionUUID() {
			final int[] aFewTransactions = {1, 2, 3, 2, 1};
			final Map<Long, List<Mutation>> txInMutations = writeWal(
				CatalogWriteAheadLogIntegrationTest.this.bigOffHeapMemoryManager, aFewTransactions);

			for (int i = 1; i < aFewTransactions.length; i++) {
				final List<Mutation> mutations = txInMutations.get((long) i);
				final List<Mutation> nextMutations = txInMutations.get((long) i + 1);
				final TransactionMutationWithLocation transactionMutation =
					(TransactionMutationWithLocation) mutations.get(0);
				final Optional<TransactionMutationWithWalFileReference> txId = CatalogWriteAheadLogIntegrationTest.this.wal.getFirstNonProcessedTransaction(
					new LogFileRecordReference(
						index -> CatalogPersistenceService.getWalFileName(TEST_CATALOG, index),
						transactionMutation.getWalFileIndex(),
						transactionMutation.getTransactionSpan(),
						transactionMutation.getCumulativeChecksumOrThrow()
					)
				);
				assertTrue(txId.isPresent());
				assertInstanceOf(TransactionMutation.class, nextMutations.get(0));
				assertTransactionMutationEquals((TransactionMutation) nextMutations.get(0), txId.get().transactionMutation());
			}

			// last transaction must return empty value (there is no next transaction to transition to)
			final List<Mutation> mutations = txInMutations.get((long) aFewTransactions.length);
			final TransactionMutationWithLocation transactionMutation = (TransactionMutationWithLocation) mutations.get(0);
			final Optional<TransactionMutationWithWalFileReference> txId = CatalogWriteAheadLogIntegrationTest.this.wal.getFirstNonProcessedTransaction(
				new LogFileRecordReference(
					index -> CatalogPersistenceService.getWalFileName(TEST_CATALOG, index),
					transactionMutation.getWalFileIndex(),
					transactionMutation.getTransactionSpan(),
					transactionMutation.getCumulativeChecksumOrThrow()
				)
			);
			assertFalse(txId.isPresent());
		}
	}

	/**
	 * Reproduces a reader advancing past an already-delivered transaction into a next transaction record
	 * that is genuinely truncated or underflowing — the same shape of failure a reader would hit if it
	 * observed a concurrent, in-progress write mid-flush. The corruption is a real Kryo-level buffer
	 * underflow during deserialization (not merely a coarse length pre-check failure), triggered while
	 * {@link MutationSupplier#get()} advances past an already-delivered transaction to look for the next
	 * one.
	 */
	@Nested
	@DisplayName("Forward-read visibility race: advancing into a genuinely underflowing transaction")
	class MisalignedReadSwallowTests {

		/**
		 * Writes two genuine transactions (real entity mutations, not synthetic bytes), then corrupts
		 * transaction 2's on-disk record surgically: its 4-byte content-length prefix is overwritten
		 * with a small LIE, and the file is truncated to exactly match that lie. This makes the coarse
		 * length pre-check that gates deserialization pass — a plausible content length, with enough raw
		 * bytes on disk per that (lying) declaration — while the REAL, unmodified leading bytes of
		 * transaction 2's serialized {@code TransactionMutation} genuinely run out mid-deserialization,
		 * because a `TransactionMutation` needs far more than a handful of bytes (a UUID alone is 16).
		 * This is a deterministic stand-in for what a torn/partially-visible concurrent write of
		 * transaction 2 would look like to a reader mid-scan.
		 *
		 * Transaction 1 is left completely untouched and must be delivered in full. Only reading
		 * PAST it — advancing into transaction 2 inside {@link MutationSupplier#get()}'s
		 * checksum-validation-and-advancement step — hits the corruption.
		 */
		@Test
		@DisplayName("must not silently end the stream when advancing into a genuinely underflowing transaction")
		void shouldNotSilentlyEndStreamWhenAdvancingIntoAGenuinelyUnderflowingTransaction() throws IOException {
			final int[] txSizes = {2, 3};
			final Map<Long, List<Mutation>> txInMutations = writeWal(
				CatalogWriteAheadLogIntegrationTest.this.bigOffHeapMemoryManager, txSizes
			);

			final TransactionMutationWithLocation tx2Location =
				(TransactionMutationWithLocation) txInMutations.get(2L).get(0);
			final long tx2Start = tx2Location.getTransactionSpan().startingPosition();

			final Path walFilePath = CatalogWriteAheadLogIntegrationTest.this.wal.getWalFilePath();
			try (RandomAccessFile raf = new RandomAccessFile(walFilePath.toFile(), "rw")) {
				// lie about transaction 2's declared content length so the coarse length pre-check
				// is satisfied by a handful of genuine (unmodified) leading bytes, then truncate the
				// file to match the lie - Kryo will start deserializing real bytes and run out
				// part-way through, exactly like a torn concurrent write would look
				final int lyingContentLength = 4;
				raf.seek(tx2Start);
				final byte[] prefix = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
					.putInt(lyingContentLength).array();
				raf.write(prefix);
				raf.setLength(tx2Start + 4 + lyingContentLength + AbstractMutationLog.CUMULATIVE_CRC32_SIZE);
			}

			List<CatalogBoundMutation> mutations = null;
			Exception thrown = null;
			try (
				final Stream<CatalogBoundMutation> stream = CatalogWriteAheadLogIntegrationTest.this.wal
					.getCommittedMutationStreamAvoidingPartiallyWrittenBuffer(1L, 2L)
			) {
				mutations = stream.toList();
			} catch (Exception ex) {
				thrown = ex;
			}

			assertNotNull(
				thrown,
				"getCommittedMutationStreamAvoidingPartiallyWrittenBuffer(1, 2) silently ended the " +
					"stream after " + (mutations == null ? "?" : mutations.size()) + " element(s) " +
					"(transaction 1 only) instead of surfacing the read failure it hit while advancing " +
					"into transaction 2's genuinely underflowing header. A broad catch-and-return-null " +
					"around the checksum-validation-and-advancement step swallowed whatever underflow or " +
					"deserialization error the buffered input produced and reported an exhausted stream " +
					"instead of a failure. A caller that already believes the requested version was " +
					"durably written cannot distinguish this from \"nothing more to process\" and would " +
					"silently finalize at a stale version instead of retrying or failing loudly."
			);
		}
	}

	/**
	 * Nested tests for timestamp reporting functionality.
	 */
	@Nested
	@DisplayName("Timestamp Reporting Tests")
	class TimestampReportingTests {

		@Test
		@DisplayName("should correctly report first available timestamp")
		void shouldCorrectlyReportFirstAvailableTimestamp() throws IOException {
			CatalogWriteAheadLogIntegrationTest.this.wal.close();
			CatalogWriteAheadLogIntegrationTest.this.wal = createCatalogWriteAheadLogOfSmallSize();

			final int justEnoughSize = 20;
			final int[] transactionSizes = new int[7];
			Arrays.fill(transactionSizes, justEnoughSize);

			final OffsetDateTime initialTimestamp = OffsetDateTime.now();
			writeWal(
				CatalogWriteAheadLogIntegrationTest.this.bigOffHeapMemoryManager, transactionSizes, initialTimestamp,
				CatalogWriteAheadLogIntegrationTest.this.isolatedWalFilePath,
				CatalogWriteAheadLogIntegrationTest.this.observableOutputKeeper, CatalogWriteAheadLogIntegrationTest.this.wal
			);
			CatalogWriteAheadLogIntegrationTest.this.wal.walProcessedUntil(Long.MAX_VALUE);
			CatalogWriteAheadLogIntegrationTest.this.wal.removeWalFiles();

			// only one call would occur with the latest version possible
			assertEquals(1, CatalogWriteAheadLogIntegrationTest.this.offsetConsumer.getCatalogVersions().size());
			assertEquals(3, CatalogWriteAheadLogIntegrationTest.this.offsetConsumer.getCatalogVersions().get(0));
		}
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
	 * Tests reading a WAL whose records are compressed.
	 *
	 * The WAL reader checks every transaction's framing prefix against the size it measured for the leading
	 * {@link TransactionMutation} record, measured as the difference of {@link io.evitadb.store.kryo.ObservableInput}
	 * stream offsets. When the compressed leading record straddles the end of the reader's raw buffer, the inflater
	 * refills that buffer in the middle of the record - and the stream offset restored after the record once
	 * forgot the bytes the refill discarded. The intact file was then refused as `Invalid WAL file on position`,
	 * which a greedy forward read (CDC catch-up) reported as a silent early end of the stream.
	 *
	 * Only a sequential advance into the next transaction can meet the straddle - a reader seeking to a
	 * transaction starts its buffer at that transaction - so the WAL must hold enough transactions for the
	 * leading records to land across the buffer's edge several times; with the fixed seed below they do.
	 */
	@Nested
	@DisplayName("Compressed WAL Tests")
	class CompressedWalTests {
		/**
		 * Words the compressible payloads are assembled from - a small vocabulary deflates well, so the mutations
		 * and the leading transaction records keep their compression bit.
		 */
		private static final String[] VOCABULARY = {
			"product", "variant", "price", "stock", "category", "brand", "attribute", "reference", "locale",
			"currency", "delivery", "warranty", "color", "material", "dimensions", "weight", "the", "and", "with"
		};
		/**
		 * Number of transactions written - enough for the leading records to straddle the reader's raw buffer edge
		 * repeatedly.
		 */
		private static final int TRANSACTION_COUNT = 1_500;

		@Test
		@DisplayName("should read every transaction forward when compressed leading records span raw buffer refills")
		void shouldReadCompressedWalForwardAcrossRawBufferRefills() throws IOException {
			final CatalogWriteAheadLogIntegrationTest outer = CatalogWriteAheadLogIntegrationTest.this;
			outer.wal.close();
			outer.wal = new CatalogWriteAheadLog(
				0L,
				TEST_CATALOG,
				new LogFileRecordReference(index -> getWalFileName(TEST_CATALOG, index)),
				outer.walDirectory,
				outer.catalogKryoPool,
				new StorageSettings(
					StorageOptions.builder().compress(true).build(),
					TransactionOptions.builder().walFileSizeBytes(Long.MAX_VALUE).build()
				),
				Mockito.mock(Scheduler.class),
				outer.offsetConsumer,
				firstActiveCatalogVersion -> {
				}
			);

			final Map<Long, List<Mutation>> txInMutations = writeCompressibleTransactions(new Random(1687L));

			// a reader that names the version it expects must deliver every transaction, or fail loudly
			final List<Long> deliveredVersions = new ArrayList<>(TRANSACTION_COUNT);
			try (
				final Stream<CatalogBoundMutation> stream = outer.wal.getCommittedMutationStreamAvoidingPartiallyWrittenBuffer(
					1L, TRANSACTION_COUNT
				)
			) {
				final Iterator<CatalogBoundMutation> it = stream.iterator();
				while (it.hasNext()) {
					final TransactionMutation txMutation = assertInstanceOf(TransactionMutation.class, it.next());
					final List<Mutation> written = txInMutations.get(txMutation.getVersion());
					assertTransactionMutationEquals((TransactionMutation) written.get(0), txMutation);
					for (int i = 1; i <= txMutation.getMutationCount(); i++) {
						assertEquals(written.get(i), it.next(), "Mutation " + i + " of transaction " + txMutation.getVersion());
					}
					deliveredVersions.add(txMutation.getVersion());
				}
			}
			assertEquals(TRANSACTION_COUNT, deliveredVersions.size());
			assertEquals(TRANSACTION_COUNT, deliveredVersions.get(deliveredVersions.size() - 1));

			// the greedy read CDC catches up with must not end early either - it turns every failure into an end
			try (final Stream<CatalogBoundMutation> stream = outer.wal.getCommittedMutationStream(1L)) {
				assertEquals(
					TRANSACTION_COUNT,
					stream.filter(TransactionMutation.class::isInstance).count(),
					"The greedy forward read ended before the last transaction of an intact WAL."
				);
			}
		}

		/**
		 * Writes {@link #TRANSACTION_COUNT} transactions of one to three compressible upsert mutations each. Every
		 * transaction gets its own isolated WAL handle, as in production, and a fixed id and timestamp, so the WAL
		 * bytes - and with them the buffer edges the test depends on - are the same in every run.
		 *
		 * @param random seeded random number generator shaping the transactions
		 * @return written mutations by catalog version, the leading transaction mutation first
		 */
		@Nonnull
		private Map<Long, List<Mutation>> writeCompressibleTransactions(@Nonnull Random random) {
			final CatalogWriteAheadLogIntegrationTest outer = CatalogWriteAheadLogIntegrationTest.this;
			final Map<Long, List<Mutation>> txInMutations = CollectionUtils.createHashMap(TRANSACTION_COUNT);
			final OffsetDateTime firstTimestamp = OffsetDateTime.of(2026, 10, 1, 12, 0, 0, 0, ZoneOffset.UTC);
			for (int i = 0; i < TRANSACTION_COUNT; i++) {
				final long version = i + 1;
				final List<Mutation> mutations = new ArrayList<>(4);
				final DefaultIsolatedWalService isolatedWal = new DefaultIsolatedWalService(
					TEST_CATALOG,
					new UUID(0L, version),
					new ConflictResolution(ConflictPolicy.NONE),
					KryoFactory.createKryo(WalKryoConfigurer.INSTANCE),
					new WriteOnlyOffHeapWithFileBackupHandle(
						outer.walDirectory.resolve("isolatedWal-" + version + ".tmp"),
						StorageOptions.DEFAULT_OUTPUT_BUFFER_SIZE,
						false,
						outer.observableOutputKeeper,
						outer.bigOffHeapMemoryManager,
						Crc32CChecksumFactory.INSTANCE,
						ZipCompressionFactory.INSTANCE
					)
				);
				try {
					final int mutationCount = 1 + random.nextInt(3);
					for (int m = 1; m <= mutationCount; m++) {
						final Mutation mutation = new EntityUpsertMutation(
							"product", m, EntityExistence.MAY_EXIST,
							new UpsertAttributeMutation(
								"description", Locale.ENGLISH, generateCompressibleText(10 + random.nextInt(3_000), random)
							)
						);
						isolatedWal.write(version, mutation);
						mutations.add(mutation);
					}
					final OffHeapWithFileBackupReference walReference = isolatedWal.getWalReference();
					final TransactionMutation txMutation = new TransactionMutation(
						new UUID(1L, version), version, mutations.size(), walReference.getContentLength(),
						firstTimestamp.plusSeconds(i)
					);
					outer.wal.append(txMutation, walReference);
					mutations.add(0, txMutation);
				} finally {
					isolatedWal.close();
				}
				txInMutations.put(version, mutations);
			}
			return txInMutations;
		}

		/**
		 * Generates text of words from {@link #VOCABULARY} that deflates well.
		 *
		 * @param length minimal length of the generated text
		 * @param random random number generator picking the words
		 * @return compressible text at least `length` characters long
		 */
		@Nonnull
		private static String generateCompressibleText(int length, @Nonnull Random random) {
			final StringBuilder sb = new StringBuilder(length + 16);
			while (sb.length() < length) {
				sb.append(VOCABULARY[random.nextInt(VOCABULARY.length)]).append(' ');
			}
			return sb.toString();
		}
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
