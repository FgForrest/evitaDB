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
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.catalog.wal.VersionSource;
import io.evitadb.spi.store.engine.exception.WriteAheadLogCorruptedException;
import io.evitadb.store.catalog.DefaultIsolatedWalService;
import io.evitadb.store.checksum.Crc32CChecksumFactory;
import io.evitadb.store.compression.CompressionFactory;
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
import java.util.ArrayList;
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
import java.util.stream.Stream;

import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.WAL_FILE_SUFFIX;
import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getWalFileName;

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

	/**
	 * Built with the same shape production uses - `new Pool<>(true, false, 16)`, a
	 * {@link java.util.concurrent.LinkedBlockingQueue} ({@code DefaultCatalogPersistenceService:505},
	 * {@code DefaultEnginePersistenceService:134}) - and not the `new Pool<>(false, false, 1)` the WAL tests
	 * carried before, which is a bare {@link java.util.ArrayDeque} with unsynchronized `poll()`/`offer()`.
	 *
	 * The writer and every reader draw their Kryo from this one pool ({@code AbstractMutationLog:1345},
	 * {@code AbstractMutationSupplier}'s constructor), so under the unsynchronized shape a racing obtain hands
	 * ONE Kryo to two threads - and a Kryo is not thread-safe. The garbage that follows is indistinguishable from
	 * a WAL read defect while proving nothing about one, and it cost this line of work a day of misattributed evidence.
	 * Nothing here needs the single-threaded shape, so no test gets to opt into it. The identity probe that caught
	 * the original double hand-out lives next to the only reader that can make it fire -
	 * {@code LongRunningConcurrentWalTailReadStressTest} in `evita_long_running_tests`.
	 */
	private final Pool<Kryo> catalogKryoPool = new Pool<>(true, false, 16) {
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

	/**
	 * Deliberately small lie written over a transaction's 4-byte content-length prefix. It has to be small enough
	 * to satisfy the coarse pre-check that precedes deserialization, so that execution reaches the consistency
	 * check under test rather than bailing out earlier.
	 */
	private static final int LYING_CONTENT_LENGTH = 4;

	/**
	 * Overwrites the content-length prefix of the transaction starting at `startingPosition` with
	 * {@link #LYING_CONTENT_LENGTH}, leaving every other byte of the file untouched.
	 *
	 * Shared by every test that needs the lie, so the two of them cannot drift apart into two subtly different
	 * falsifications and then be read as covering the same thing.
	 *
	 * @param startingPosition byte offset of the transaction whose prefix is falsified
	 */
	private void falsifyContentLengthPrefix(long startingPosition) throws IOException {
		try (final RandomAccessFile raf = new RandomAccessFile(this.wal.getWalFilePath().toFile(), "rw")) {
			raf.seek(startingPosition);
			raf.write(
				ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
					.putInt(LYING_CONTENT_LENGTH).array()
			);
		}
	}

	/**
	 * Cuts the current WAL file down to `length` bytes, standing in for a tail that is not (yet) on disk.
	 *
	 * @param length the length the WAL file is truncated to
	 */
	private void truncateWalFileTo(long length) throws IOException {
		try (final RandomAccessFile raf = new RandomAccessFile(this.wal.getWalFilePath().toFile(), "rw")) {
			raf.setLength(length);
		}
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
			this.offsetConsumer
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
			this.offsetConsumer
		);
	}

	private void createCachedSupplierReadAndVerifyFrom(
		Map<Long, List<Mutation>> txInMutations, int[] transactionSizes, int index
	) {
		try (
			final MutationSupplier<CatalogBoundMutation> supplier = this.wal.createSupplier(
				index + 1, null, VersionSource.INTERNAL
			)
		) {
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

		/**
		 * A version whose WAL file retention has already reclaimed must be reported, not answered with an
		 * empty stream — and this is the path on which that most often goes wrong.
		 *
		 * `AbstractMutationLog#createSupplier` resolves the file index through `resolveWalFileIndex`, which
		 * returns **-1** once the file holding the start version is gone. `-1` is a perfectly well-formed
		 * file index, so the name it produces is a file that simply does not exist, and the supplier's
		 * constructor takes its "no WAL file here" branch rather than the forward scan. The scan is where the
		 * obvious not-found guard lives, so without a second guard on the missing-file branch this case stays
		 * silent — which is how it read before, and it is the case `VersionSource` was introduced for.
		 *
		 * Silence here is not merely an under-reported error. `TransactionManager` re-derives the conflict
		 * keys of aged-out transactions by reading this range, so an empty stream lets a commit through
		 * without the conflict scan it was supposed to pass, with nothing anywhere saying so.
		 *
		 * The third read is the negative control and is the reason the other two can be trusted: on the same
		 * trimmed log, a caller whose start version sits *above* its own ceiling has asked for an empty
		 * interval and must still get an empty stream. Without it, a guard that threw on every trimmed log —
		 * including for callers who asked for nothing — would pass the first two assertions.
		 */
		@Test
		@DisplayName("a version whose WAL file retention has reclaimed must be reported, not silently empty")
		void shouldReportAVersionWhoseWalFileHasBeenReclaimedByRetention() throws IOException {
			CatalogWriteAheadLogIntegrationTest.this.wal.close();
			CatalogWriteAheadLogIntegrationTest.this.wal = createCatalogWriteAheadLogOfSmallSize();

			final int[] transactionSizes = {10, 15, 20, 15, 10};
			writeWal(CatalogWriteAheadLogIntegrationTest.this.bigOffHeapMemoryManager, transactionSizes);

			final File[] walFiles = sortedWalFiles();
			assertTrue(
				walFiles.length > 1,
				"this test needs the WAL to have rotated, so that deleting the oldest file leaves a log whose " +
					"earliest versions are genuinely unreachable; it produced " + walFiles.length + " file(s)"
			);
			assertTrue(
				walFiles[0].delete(),
				"the oldest WAL file was supposed to be deletable, standing in for a retention sweep"
			);

			final long lastVersion = transactionSizes.length;

			// the engine asking for a version it believes it wrote: damage
			assertThrows(
				WriteAheadLogCorruptedException.class,
				() -> {
					try (
						final Stream<CatalogBoundMutation> stream = CatalogWriteAheadLogIntegrationTest.this.wal
							.getCommittedLiveMutationStream(1L, lastVersion, VersionSource.INTERNAL)
					) {
						stream.toList();
					}
				},
				"Version 1's WAL file is gone, and the caller named a range starting there. An empty stream " +
					"tells the engine \"nothing to process\", which is indistinguishable from \"everything is " +
					"processed\" - and on the conflict-detection path that silently accepts a commit whose " +
					"aged-out conflict scan never ran."
			);

			// a client asking for the same thing: a bad argument, and never an internal error
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> {
					try (
						final Stream<CatalogBoundMutation> stream = CatalogWriteAheadLogIntegrationTest.this.wal
							.getCommittedLiveMutationStream(1L, lastVersion, VersionSource.CLIENT)
					) {
						stream.toList();
					}
				},
				"A subscriber whose pointer has fallen out of retention is asking for something that is not " +
					"there. It has to be told - but it is not a fault an operator can clear, so it must not " +
					"reach the internal-error metric."
			);

			// negative control: an empty interval is not a missing version, trimmed log or not
			final List<CatalogBoundMutation> emptyInterval = assertDoesNotThrow(
				() -> {
					try (
						final Stream<CatalogBoundMutation> stream = CatalogWriteAheadLogIntegrationTest.this.wal
							.getCommittedLiveMutationStream(lastVersion, 1L, VersionSource.CLIENT)
					) {
						return stream.toList();
					}
				},
				"A start version above its own ceiling describes an empty interval, which is what a " +
					"mutation-history query whose time frame begins after the last commit resolves to. There " +
					"is nothing in that range by construction, so it is an empty answer and not an error."
			);
			assertTrue(
				emptyInterval.isEmpty(),
				"An empty interval returned " + emptyInterval.size() + " mutation(s)."
			);
		}

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
		@DisplayName("should report the first replayable version only once rotation has actually purged a file")
		void shouldReportTheFirstReplayableVersionOnlyAfterAPurge() throws IOException {
			CatalogWriteAheadLogIntegrationTest.this.wal.close();
			CatalogWriteAheadLogIntegrationTest.this.wal = createCatalogWriteAheadLogOfSmallSize();

			final int[] transactionSizes = {10, 15, 20, 15, 10};
			writeWal(CatalogWriteAheadLogIntegrationTest.this.bigOffHeapMemoryManager, transactionSizes);

			// nothing has been deleted yet, so no floor was ever reported and there is none to recover. Answering
			// with the first version of file `0` instead would be a floor nobody derived - and a catalog whose
			// newest published bootstrap record sits below it loses the persistence service that record needs
			assertEquals(-1L, CatalogWriteAheadLogIntegrationTest.this.wal.getFirstReplayableVersion());

			final File[] walFiles = CatalogWriteAheadLogIntegrationTest.this.walDirectory.toFile().listFiles(
				(dir, name) -> name.endsWith(WAL_FILE_SUFFIX)
			);
			Arrays.sort(
				walFiles,
				Comparator.comparingInt(f -> getIndexFromWalFileName(f.getName()))
			);
			assertEquals(3, walFiles.length);

			// what rotation leaves behind once the removal it queued is executed: the oldest file is gone, and the
			// floor it implied - `lastVersion + 1` of that file - is the first version of the one that survived
			final long expectedFirstReplayableVersion = getFirstAndLastVersionsFromWalFile(
				walFiles[0], WriteAheadLogCorruptedException.WalKind.CATALOG
			).lastVersion() + 1L;
			assertTrue(walFiles[0].delete());

			assertEquals(
				expectedFirstReplayableVersion,
				CatalogWriteAheadLogIntegrationTest.this.wal.getFirstReplayableVersion()
			);
			assertEquals(
				getFirstAndLastVersionsFromWalFile(
					walFiles[1], WriteAheadLogCorruptedException.WalKind.CATALOG
				).firstVersion(),
				CatalogWriteAheadLogIntegrationTest.this.wal.getFirstReplayableVersion(),
				"the surviving file's own first version is the floor, and it must be read from its head - the " +
					"trailer this compares against exists only because that file was rotated away in its turn"
			);
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

		/**
		 * Pins the end-of-file guards of the FIRST record of a rotated file, which is the one record whose
		 * delivery is decided immediately after {@link MutationSupplier#get()} has swapped WAL files.
		 *
		 * Every other rotation test in this repository is a happy path, and the guards only ever make a delivery
		 * stricter - so all of them pass whether the length `get()` hands to
		 * `readAndRecordTransactionMutation` and to its own `canProceed` test describes the new file or still
		 * describes the previous one. This test separates the two, because a rotated file is shorter than the
		 * rotation threshold exactly while it is still being filled: the last file is cut back to the content
		 * end of its first transaction, so the previous file's length vouches for a trailing checksum that is
		 * not on disk while the new file's own length does not.
		 */
		@Test
		@DisplayName("must not deliver a rotated file's first transaction using the stale file length")
		void shouldNotDeliverARotatedFilesFirstTransactionUsingTheStaleFileLength() throws IOException {
			CatalogWriteAheadLogIntegrationTest.this.wal.close();
			CatalogWriteAheadLogIntegrationTest.this.wal = createCatalogWriteAheadLogOfSmallSize();

			final int[] transactionSizes = {10, 15, 20, 15, 10};
			writeWal(CatalogWriteAheadLogIntegrationTest.this.bigOffHeapMemoryManager, transactionSizes);

			final File[] walFiles = sortedWalFiles();
			assertEquals(3, walFiles.length, "the fixture is supposed to produce three WAL files");
			final File lastWalFile = walFiles[walFiles.length - 1];
			final File previousWalFile = walFiles[walFiles.length - 2];
			final long firstVersionInLastFile = CatalogWriteAheadLogIntegrationTest.this.wal.getFirstVersionOf(
				getIndexFromWalFileName(lastWalFile.getName())
			);

			// cut the last file back to the content end of its first transaction: every byte the transaction
			// declares is on disk, its trailing cumulative checksum is not - the state a file is in between the
			// writer flushing a record and flushing the checksum that closes it
			final long contentEnd = AbstractMutationLog.CUMULATIVE_CRC32_SIZE + 4 +
				readContentLengthPrefixAt(lastWalFile, AbstractMutationLog.CUMULATIVE_CRC32_SIZE);
			final long requiredEndPosition = contentEnd + AbstractMutationLog.CUMULATIVE_CRC32_SIZE;
			assertTrue(
				previousWalFile.length() >= requiredEndPosition,
				"the previous WAL file is no longer long enough to vouch for the truncated transaction, so this " +
					"test can no longer tell a stale file length from a fresh one - resize the fixture"
			);
			try (final RandomAccessFile raf = new RandomAccessFile(lastWalFile, "rw")) {
				raf.setLength(contentEnd);
			}

			final List<Long> deliveredVersions = new ArrayList<>(transactionSizes.length);
			try (
				final Stream<CatalogBoundMutation> stream = CatalogWriteAheadLogIntegrationTest.this.wal
					.getCommittedMutationStream(1L)
			) {
				stream.forEach(
					mutation -> {
						if (mutation instanceof TransactionMutation transactionMutation) {
							deliveredVersions.add(transactionMutation.getVersion());
						}
					}
				);
			}

			final List<Long> expectedVersions = new ArrayList<>(transactionSizes.length);
			for (long version = 1L; version < firstVersionInLastFile; version++) {
				expectedVersions.add(version);
			}
			assertEquals(
				expectedVersions,
				deliveredVersions,
				"A greedy read delivered transaction " + firstVersionInLastFile + " even though its trailing " +
					"cumulative checksum is not on disk. The only length that says it is complete is the " +
					"PREVIOUS WAL file's - the one the reader was looking at before it rotated - so the " +
					"end-of-file guards were answered about the wrong file, and the caller was handed a " +
					"transaction whose tail may still be being written."
			);
		}

		/**
		 * Rotation creates the next WAL file and only afterwards writes the 8-byte seed cumulative checksum into
		 * it, so a reader that crosses the boundary in that window meets a file too short to carry it - and a
		 * crash between the two leaves one behind for good. A reader decides to rotate exactly when the file it
		 * holds has its tail written, which is to say inside that window by construction rather than by
		 * coincidence.
		 *
		 * The window has to read as "the next file is not there yet", the same as no next file at all: the seed
		 * read is what would otherwise fail, and it fails as a raw Kryo buffer underflow on a supplier whose WAL
		 * file, index and position have already been swapped to the stub - a recoverable transient, reported as
		 * a hard read failure, on a half-rotated reader. Rejecting the stub before any of that is reassigned is
		 * also what keeps the input for it from being opened, and so from being leaked when the read fails.
		 */
		@Test
		@DisplayName("a next WAL file that is still an empty stub must read as no next file at all")
		void shouldNotRaiseARawKryoFailureWhenTheNextWalFileIsStillAnEmptyStub() throws IOException {
			CatalogWriteAheadLogIntegrationTest.this.wal.close();
			CatalogWriteAheadLogIntegrationTest.this.wal = createCatalogWriteAheadLogOfSmallSize();

			final int[] transactionSizes = {10, 15, 20, 15, 10};
			writeWal(CatalogWriteAheadLogIntegrationTest.this.bigOffHeapMemoryManager, transactionSizes);

			final File[] walFiles = sortedWalFiles();
			final int stubIndex = getIndexFromWalFileName(walFiles[walFiles.length - 1].getName()) + 1;
			final File stubWalFile = CatalogWriteAheadLogIntegrationTest.this.walDirectory
				.resolve(getWalFileName(TEST_CATALOG, stubIndex)).toFile();
			assertTrue(stubWalFile.createNewFile(), "the stub WAL file was supposed to be created by this test");

			final List<CatalogBoundMutation> greedilyRead = assertDoesNotThrow(
				() -> {
					try (
						final Stream<CatalogBoundMutation> stream = CatalogWriteAheadLogIntegrationTest.this.wal
							.getCommittedMutationStream(transactionSizes.length + 1L)
					) {
						return stream.toList();
					}
				},
				"A WAL file that rotation has created but not yet seeded raised a read failure instead of " +
					"reading as a file that is not there yet. The reader crosses the boundary precisely while " +
					"the writer is inside that window, so this is a transient a retry clears - and it arrives " +
					"as a raw Kryo buffer underflow, on a supplier already swapped over to the stub."
			);
			assertTrue(
				greedilyRead.isEmpty(),
				"A transaction was delivered out of a WAL file that holds no record at all."
			);

			// The live entry point is bounded by the LAST WRITTEN version, not by one past it: a caller naming a
			// version is asserting that version is durable, and `transactionSizes.length + 1` never existed, so
			// naming it would be a false assertion rather than a rotation transient (the read below pins what
			// happens then). Bounded honestly, the reader still walks off the end of the last real file and into
			// the stub - which is the boundary crossing this test is about - and must come back cleanly.
			final List<CatalogBoundMutation> liveRead = assertDoesNotThrow(
				() -> {
					try (
						final Stream<CatalogBoundMutation> stream = CatalogWriteAheadLogIntegrationTest.this.wal
							.getCommittedLiveMutationStream(
								(long) transactionSizes.length, (long) transactionSizes.length,
								VersionSource.INTERNAL
							)
					) {
						return stream.toList();
					}
				},
				"The same rotation stub raised a read failure on the live-stream entry point, which is the one " +
					"the transaction manager and the session read a moving tail through. The bound names the " +
					"last durably written version, so the stub is reached by advancing past it - exactly as a " +
					"tailing reader reaches it in production - and must read as a file that is not there yet."
			);
			assertFalse(
				liveRead.isEmpty(),
				"The live read was bounded by version " + transactionSizes.length + ", which is durably on " +
					"disk, so it had to be delivered. An empty result means the stub swallowed a transaction " +
					"that precedes it."
			);
			assertTrue(
				liveRead.stream()
					.filter(TransactionMutation.class::isInstance)
					.map(TransactionMutation.class::cast)
					.noneMatch(it -> it.getVersion() > transactionSizes.length),
				"A transaction beyond the requested bound was delivered, which means the reader read into the " +
					"stub rather than stopping at it. Delivered versions: " + liveRead.stream()
					.filter(TransactionMutation.class::isInstance)
					.map(it -> String.valueOf(((TransactionMutation) it).getVersion()))
					.toList()
			);

			// the other half of the contract, and the reason the bound above had to be honest: a caller that
			// names a version the log does not hold is making a false assertion of durability, and that is
			// reported rather than answered with an empty stream - otherwise the caller cannot tell "your
			// version is gone" from "nothing left to process", which is the confusion this whole line of work
			// exists to remove. The stub must not turn that into a raw Kryo failure either.
			final WriteAheadLogCorruptedException corrupted = assertThrows(
				WriteAheadLogCorruptedException.class,
				() -> {
					try (
						final Stream<CatalogBoundMutation> stream = CatalogWriteAheadLogIntegrationTest.this.wal
							.getCommittedLiveMutationStream(
								transactionSizes.length + 1L, transactionSizes.length + 1L,
								VersionSource.INTERNAL
							)
					) {
						stream.toList();
					}
				},
				"A read bounded by version " + (transactionSizes.length + 1) + " - which was never written - " +
					"ended silently instead of reporting that the version it was promised is not in the log."
			);
			assertTrue(
				corrupted.getMessage().contains(String.valueOf(transactionSizes.length + 1)),
				"The failure must name the version that could not be found. Message was: " +
					corrupted.getMessage()
			);
		}

		/**
		 * Lists the WAL files of the test directory ordered by their file index.
		 *
		 * @return the WAL files, oldest first
		 */
		@Nonnull
		private File[] sortedWalFiles() {
			final File[] walFiles = CatalogWriteAheadLogIntegrationTest.this.walDirectory.toFile().listFiles(
				(dir, name) -> name.endsWith(WAL_FILE_SUFFIX)
			);
			assertNotNull(walFiles, "the WAL directory could not be listed");
			Arrays.sort(walFiles, Comparator.comparingInt(f -> getIndexFromWalFileName(f.getName())));
			return walFiles;
		}

		/**
		 * Reads the 4-byte little-endian content-length prefix a transaction record starts with.
		 *
		 * @param walFile  the WAL file to read from
		 * @param position byte offset of the transaction record
		 * @return the declared content length
		 */
		private int readContentLengthPrefixAt(@Nonnull File walFile, long position) throws IOException {
			try (final RandomAccessFile raf = new RandomAccessFile(walFile, "r")) {
				raf.seek(position);
				final byte[] prefix = new byte[4];
				raf.readFully(prefix);
				return ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN).getInt();
			}
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

			// lie about transaction 2's declared content length so the coarse length pre-check is satisfied by a
			// handful of genuine (unmodified) leading bytes, then truncate the file to match the lie - Kryo will
			// start deserializing real bytes and run out part-way through, exactly like a torn concurrent write
			// would look
			falsifyContentLengthPrefix(tx2Start);
			truncateWalFileTo(
				tx2Start + 4 + LYING_CONTENT_LENGTH + AbstractMutationLog.CUMULATIVE_CRC32_SIZE
			);

			List<CatalogBoundMutation> mutations = null;
			Exception thrown = null;
			try (
				final Stream<CatalogBoundMutation> stream = CatalogWriteAheadLogIntegrationTest.this.wal
					.getCommittedLiveMutationStream(1L, 2L, VersionSource.INTERNAL)
			) {
				mutations = stream.toList();
			} catch (Exception ex) {
				thrown = ex;
			}

			assertNotNull(
				thrown,
				"getCommittedLiveMutationStream(1, 2) silently ended the " +
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
	 * Pins the version bound a caller names when it asks for a live mutation stream.
	 *
	 * `getCommittedLiveMutationStream(start, requested)` carries two promises at once: the requested transaction
	 * is durably written (which licenses delivering a record whose trailing checksum has not landed yet), and the
	 * stream stops at it. The second one is the easier to lose, because every other test in the repository asks
	 * for a version at or past the end of the file, where stopping at it and running out of data are the same
	 * outcome. These tests ask for one strictly short of the end, where they are not.
	 */
	@Nested
	@DisplayName("Live mutation stream: the requested version is an upper bound, not a hint")
	class LiveMutationStreamBoundsTests {

		@Test
		@DisplayName("must stop at the requested version even when later transactions are on disk")
		void shouldStopAtTheRequestedVersionWhenLaterTransactionsExist() {
			final int[] transactionSizes = {2, 3, 4, 3, 2};
			writeWal(CatalogWriteAheadLogIntegrationTest.this.bigOffHeapMemoryManager, transactionSizes);

			final long requestedVersion = 3L;
			final List<Long> deliveredVersions = new ArrayList<>(transactionSizes.length);
			try (
				final Stream<CatalogBoundMutation> stream = CatalogWriteAheadLogIntegrationTest.this.wal
					.getCommittedLiveMutationStream(1L, requestedVersion, VersionSource.INTERNAL)
			) {
				stream.forEach(
					mutation -> {
						if (mutation instanceof TransactionMutation transactionMutation) {
							deliveredVersions.add(transactionMutation.getVersion());
						}
					}
				);
			}

			assertEquals(
				List.of(1L, 2L, 3L),
				deliveredVersions,
				"getCommittedLiveMutationStream(1, " + requestedVersion + ") did not stop at the version it was " +
					"asked for even though transactions 4 and 5 are fully durable behind it. The requested " +
					"version is the caller's assertion about what it knows to be written, not a floor: a caller " +
					"that receives transactions past it advances its own pointer beyond what it was prepared to " +
					"process."
			);
		}
	}

	/**
	 * Tests for the content-length consistency check in
	 * {@link io.evitadb.store.wal.supplier.AbstractMutationSupplier#readAndRecordTransactionMutation(long, long)}.
	 *
	 * The check compares the transaction's 4-byte framing prefix against the leading record's own declared
	 * length plus the declared size of the individual mutations.
	 *
	 * The check was once believed to be met routinely by a reader tailing a live WAL - that such a reader had
	 * read a prefix the writer had not finished backing with bytes - and that it therefore had to learn to tell
	 * an unfinished append from real damage. It does not: the writer
	 * emits the 4-byte prefix and the whole leading record from one buffer in a single write loop, and a file
	 * too short for the transaction is rejected by an earlier guard - so a mismatch reaching this point means
	 * the bytes are all present and disagree. The real cause was a reader miscounting its own buffer, fixed in
	 * {@link io.evitadb.store.kryo.ObservableInput}. What remains to be tested here is simply that genuine
	 * corruption still fails loudly.
	 *
	 * The test drives {@link CatalogWriteAheadLog#getCommittedMutationStream(long)} positioned so the mismatch
	 * is met while the supplier's constructor scans for the requested version - the one path on which the
	 * outcome is externally observable, since the constructor catches only `BufferUnderflowException` while
	 * `MutationSupplier#get()` swallows everything into a graceful end-of-stream.
	 */
	@Nested
	@DisplayName("Content-length mismatch must fail loudly")
	class ContentLengthMismatchTests {
		/**
		 * Writes three transactions and returns the on-disk span of the last one, whose prefix the tests then
		 * falsify. The last transaction is used so that truncating the file cannot damage any other.
		 */
		@Nonnull
		private TransactionMutationWithLocation writeThreeTransactionsAndReturnLastSpan() {
			final int[] txSizes = {2, 3, 4};
			final Map<Long, List<Mutation>> txInMutations = writeWal(
				CatalogWriteAheadLogIntegrationTest.this.bigOffHeapMemoryManager, txSizes
			);
			return (TransactionMutationWithLocation) txInMutations.get((long) txSizes.length).get(0);
		}

		@Test
		@DisplayName("must still fail loudly when the whole transaction is present and the prefix disagrees")
		void shouldStillFailWhenContentLengthDisagreesOnAFullyVisibleTransaction() throws IOException {
			final TransactionMutationWithLocation lastTx = writeThreeTransactionsAndReturnLastSpan();

			// The file is left wholly intact - every byte the leading record declares is on disk - so the
			// falsified prefix contradicts a record that passed its own length and CRC32C checks, which no
			// concurrent append can explain.
			falsifyContentLengthPrefix(lastTx.getTransactionSpan().startingPosition());

			Exception thrown = null;
			try (
				final Stream<CatalogBoundMutation> stream = CatalogWriteAheadLogIntegrationTest.this.wal
					.getCommittedMutationStream(lastTx.getVersion())
			) {
				stream.forEach(it -> {
				});
			} catch (Exception ex) {
				thrown = ex;
			}

			assertNotNull(
				thrown,
				"A content-length prefix that disagrees with a fully-visible transaction was accepted " +
					"silently. This is genuine corruption - durable bytes contradicting a record that passed " +
					"its own length and checksum checks - and it must stay loud: the premise here is the only " +
					"thing standing between a damaged WAL and a clean-looking end-of-stream."
			);
			// Naming the class and the message is the whole point. Falsifying the prefix also poisons the
			// cumulative checksum, so a WriteAheadLogCorruptedException raised later by the trailing-checksum
			// comparison would satisfy a bare assertNotNull while saying nothing about the premise under test -
			// and WHICH error class a WAL read mints is precisely what is under test.
			assertInstanceOf(
				GenericEvitaInternalError.class,
				thrown,
				"The content-length premise no longer decides this read - something else failed first, so the " +
					"guard this test exists for was never reached. Observed: " + thrown
			);
			assertTrue(
				thrown.getMessage() != null && thrown.getMessage().contains("Invalid WAL file on position"),
				"The failure did not come from the content-length premise in " +
					"AbstractMutationSupplier#readAndRecordTransactionMutation. Observed message: " +
					thrown.getMessage()
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
