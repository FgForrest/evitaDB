/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.store.offsetIndex;

import com.esotericsoftware.kryo.io.Input;
import com.github.javafaker.Faker;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.function.Functions;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;
import io.evitadb.store.entity.EntityStoragePartConfigurer;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.offsetIndex.OffsetIndexSerializationService.FileLocationAndWrittenBytes;
import io.evitadb.store.offsetIndex.io.WriteOnlyFileHandle;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.offsetIndex.model.RecordKey;
import io.evitadb.store.offsetIndex.model.RecordTypeUsage;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.schema.SchemaKryoConfigurer;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.store.shared.kryo.VersionedKryoFactory;
import io.evitadb.store.shared.model.FileLocation;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.IOUtils;
import io.evitadb.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.opentest4j.AssertionFailedError;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.evitadb.store.offsetIndex.OffsetIndexSerializationService.computeExpectedRecordCount;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.store.offsetIndex.OffsetIndexSerializationService.MEM_TABLE_RECORD_SIZE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies functionality of {@link OffsetIndex} operations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
@Tag(STORAGE)
@Tag(MANAGEMENT)
class OffsetIndexTest implements EvitaTestSupport {
	public static final String ENTITY_TYPE = "whatever";
	private static final Locale[] AVAILABLE_LOCALES = new Locale[]{
		Locale.ENGLISH, Locale.FRENCH, Locale.GERMAN, new Locale("cs", "CZ")
	};
	private static final String TEST_FOLDER = "offsetIndexTest";
	private static final Consumer<OffsetIndex.NonFlushedBlock> NO_OP_NON_FLUSHED_BLOCK_CALLBACK = Functions.noOpConsumer();
	private static final Consumer<Optional<OffsetDateTime>> NO_OP_OLDEST_RECORD_CALLBACK = Functions.noOpConsumer();
	private static final TransactionOptions DEFAULT_TRANSACTION_OPTIONS = TransactionOptions.builder().build();

	private final Path targetFile = Files.createTempFile("fileOffsetIndex", "kryo");
	private final OffsetIndexRecordTypeRegistry offsetIndexRecordTypeRegistry = new OffsetIndexRecordTypeRegistry();

	/**
	 * Generates a stream of arguments by combining all possible combinations
	 * of {@link ChecksumCheck} and {@link OffsetIndexTest.Compression} enum values.
	 *
	 * @return a {@link Stream} of {@link Arguments} containing every combination
	 * of {@link ChecksumCheck} and {@link OffsetIndexTest.Compression}.
	 */
	@Nonnull
	private static Stream<Arguments> combineSettings() {
		return Stream.of(ChecksumCheck.values())
			.flatMap(crc32Check -> Stream.of(OffsetIndexTest.Compression.values())
				.map(compression -> Arguments.of(crc32Check, compression)));
	}

	/**
	 * Creates an {@link EntityBodyStoragePart} with random associated data of varying size.
	 * The size of the data and its characteristics are determined by the given random object.
	 *
	 * @param random        a non-null {@link Random} instance used to generate random values
	 * @param recPrimaryKey an integer representing the primary key of the entity record
	 * @return a newly created instance of {@link EntityBodyStoragePart} containing randomly generated associated data
	 */
	@Nonnull
	private static EntityBodyStoragePart createEntityBodyStoragePartOfRandomSize(
		@Nonnull StorageSettings storageSettings,
		@Nonnull Random random,
		int recPrimaryKey
	) {
		// we need to generate some fake data to cross the 4096 bytes boundary
		final Faker faker = new Faker(random);
		final int associatedDataKeys = random.nextInt(storageSettings.compress() ? 4000 : 1000);
		final Set<AssociatedDataKey> associatedData = new HashSet<>(associatedDataKeys);
		for (int i = 0; i < associatedDataKeys; i++) {
			associatedData.add(
				new AssociatedDataKey(
					faker.funnyName().name(),
					AVAILABLE_LOCALES[random.nextInt(AVAILABLE_LOCALES.length)]
				)
			);
		}

		return new EntityBodyStoragePart(
			1, recPrimaryKey, Scope.LIVE, null, Set.of(), Set.of(), associatedData, 0
		);
	}

	@Nonnull
	private static Function<VersionedKryoKeyInputs, VersionedKryo> createKryo() {
		return (keyInputs) -> VersionedKryoFactory.createKryo(
			keyInputs.version(),
			SchemaKryoConfigurer.INSTANCE
				.andThen(
					new EntityStoragePartConfigurer(
						keyInputs.keyCompressor()
					)
				)
		);
	}

	/**
	 * Creates a mocked {@link ObservableOutputKeeper} with a mocked {@link Scheduler}.
	 *
	 * @return a new mocked {@link ObservableOutputKeeper} instance
	 */
	@Nonnull
	private static ObservableOutputKeeper createMockedObservableOutputKeeper() {
		return ObservableOutputKeeper._internalBuild(Mockito.mock(Scheduler.class));
	}

	/**
	 * Creates an initial {@link OffsetIndexDescriptor} for new offset indices.
	 *
	 * @return a new {@link OffsetIndexDescriptor} instance
	 */
	@Nonnull
	private static OffsetIndexDescriptor createInitialOffsetIndexDescriptor() {
		return new OffsetIndexDescriptor(
			new EntityCollectionFileHeader(ENTITY_TYPE, 1, 0),
			createKryo(),
			1.0, 0L
		);
	}

	/**
	 * Wraps an existing {@link OffsetIndexDescriptor} for loading from a file.
	 *
	 * @param descriptor the descriptor to wrap
	 * @return a new {@link OffsetIndexDescriptor} instance suitable for loading
	 */
	@Nonnull
	private static OffsetIndexDescriptor wrapDescriptorForLoading(@Nonnull OffsetIndexDescriptor descriptor) {
		return new OffsetIndexDescriptor(
			new FileLocationAndWrittenBytes(descriptor.fileLocation(), 0),
			descriptor,
			1.0,
			descriptor.getFileSize()
		);
	}

	/**
	 * Configures the provided {@link StorageOptions} by applying the specified CRC32 check
	 * and compression settings.
	 *
	 * @param options     the initial storage options to configure
	 * @param crc32Check  the CRC32 check setting to apply; {@link ChecksumCheck#YES} enables CRC32 computation
	 * @param compression the compression setting to apply; {@link Compression#YES} enables compression
	 * @return the configured {@link StorageSettings} instance
	 */
	@Nonnull
	private static StorageSettings configure(
		@Nonnull StorageOptions options,
		@Nonnull ChecksumCheck crc32Check,
		@Nonnull Compression compression
	) {
		return new StorageSettings(
			StorageOptions.builder(options)
				.computeCRC32(crc32Check == ChecksumCheck.YES)
				.compress(compression == Compression.YES)
				.build(),
			DEFAULT_TRANSACTION_OPTIONS
		);
	}

	/**
	 * Creates a {@link WriteOnlyFileHandle} with the given parameters.
	 *
	 * @param targetPath             the path to the target file
	 * @param storageSettings        the storage settings to use
	 * @param observableOutputKeeper the observable output keeper to use
	 * @return a new {@link WriteOnlyFileHandle} instance
	 */
	@Nonnull
	private static WriteOnlyFileHandle createWriteOnlyFileHandle(
		@Nonnull Path targetPath,
		@Nonnull StorageSettings storageSettings,
		@Nonnull ObservableOutputKeeper observableOutputKeeper
	) {
		return new WriteOnlyFileHandle(
			targetPath,
			storageSettings.outputBufferSize(),
			storageSettings.syncWrites(),
			storageSettings,
			storageSettings,
			observableOutputKeeper
		);
	}

	/**
	 * Creates a new {@link OffsetIndex} with default settings.
	 *
	 * @param catalogVersion            the catalog version
	 * @param storageSettings           the storage settings to use
	 * @param writeHandle               the write handle to use
	 * @param offsetIndexRecordRegistry the record type registry
	 * @return a new {@link OffsetIndex} instance
	 */
	@Nonnull
	private static OffsetIndex createNewOffsetIndex(
		long catalogVersion,
		@Nonnull StorageSettings storageSettings,
		@Nonnull WriteOnlyFileHandle writeHandle,
		@Nonnull OffsetIndexRecordTypeRegistry offsetIndexRecordRegistry
	) {
		return new OffsetIndex(
			catalogVersion,
			createInitialOffsetIndexDescriptor(),
			storageSettings.outputBufferSize(),
			storageSettings.maxOpenedReadHandlesOrDefault(),
			storageSettings.lockTimeoutSeconds(),
			storageSettings.waitOnCloseSeconds(),
			storageSettings,
			storageSettings,
			offsetIndexRecordRegistry,
			writeHandle,
			NO_OP_NON_FLUSHED_BLOCK_CALLBACK,
			NO_OP_OLDEST_RECORD_CALLBACK
		);
	}

	/**
	 * Creates a new {@link OffsetIndex} that reports the oldest-retained historical timestamp to the supplied
	 * observer. Mirrors {@link #createNewOffsetIndex} but threads a capturing observer in place of the no-op
	 * callback so a test can assert the observable history-retention contract.
	 *
	 * @param catalogVersion            the catalog version
	 * @param storageSettings           the storage settings to use
	 * @param writeHandle               the write handle to use
	 * @param offsetIndexRecordRegistry the record type registry
	 * @param historicalRecordObserver  observer notified with the oldest retained timestamp after a purge release
	 * @return a new {@link OffsetIndex} instance
	 */
	@Nonnull
	private static OffsetIndex createNewOffsetIndexWithObserver(
		long catalogVersion,
		@Nonnull StorageSettings storageSettings,
		@Nonnull WriteOnlyFileHandle writeHandle,
		@Nonnull OffsetIndexRecordTypeRegistry offsetIndexRecordRegistry,
		@Nonnull Consumer<Optional<OffsetDateTime>> historicalRecordObserver
	) {
		return new OffsetIndex(
			catalogVersion,
			createInitialOffsetIndexDescriptor(),
			storageSettings.outputBufferSize(),
			storageSettings.maxOpenedReadHandlesOrDefault(),
			storageSettings.lockTimeoutSeconds(),
			storageSettings.waitOnCloseSeconds(),
			storageSettings,
			storageSettings,
			offsetIndexRecordRegistry,
			writeHandle,
			NO_OP_NON_FLUSHED_BLOCK_CALLBACK,
			historicalRecordObserver
		);
	}

	/**
	 * Loads an {@link OffsetIndex} from an existing descriptor.
	 *
	 * @param catalogVersion            the catalog version
	 * @param descriptor                the descriptor to load from
	 * @param storageSettings           the storage settings to use
	 * @param writeHandle               the write handle to use
	 * @param offsetIndexRecordRegistry the record type registry
	 * @return a loaded {@link OffsetIndex} instance
	 */
	@Nonnull
	private static OffsetIndex loadOffsetIndex(
		long catalogVersion,
		@Nonnull OffsetIndexDescriptor descriptor,
		@Nonnull StorageSettings storageSettings,
		@Nonnull WriteOnlyFileHandle writeHandle,
		@Nonnull OffsetIndexRecordTypeRegistry offsetIndexRecordRegistry
	) {
		return new OffsetIndex(
			catalogVersion,
			wrapDescriptorForLoading(descriptor),
			storageSettings.outputBufferSize(),
			storageSettings.maxOpenedReadHandlesOrDefault(),
			storageSettings.lockTimeoutSeconds(),
			storageSettings.waitOnCloseSeconds(),
			storageSettings,
			storageSettings,
			offsetIndexRecordRegistry,
			writeHandle,
			NO_OP_NON_FLUSHED_BLOCK_CALLBACK,
			NO_OP_OLDEST_RECORD_CALLBACK
		);
	}

	@Nonnull
	private static InsertionOutput createRecordsInFileOffsetIndex(
		@Nonnull OffsetIndex fileOffsetIndex,
		int recordCount,
		int removedRecords,
		int iterationCount
	) {
		OffsetIndexDescriptor fileOffsetIndexDescriptor = null;
		int inserted = 0;
		int removed = 0;

		long transactionId = 0;
		for (int j = 0; j < iterationCount; j++) {
			transactionId++;
			if (j > 0) {
				for (int i = 1; i < removedRecords; i++) {
					final int primaryKey = i + (j - 1) * recordCount;
					log.info("Removal of rec with PK:   " + primaryKey);
					fileOffsetIndex.remove(transactionId, primaryKey, EntityBodyStoragePart.class);
					removed++;
				}
			}
			for (int i = 1; i <= recordCount; i++) {
				final int primaryKey = j * recordCount + i;
				log.info("Insertion of rec with PK (tx " + transactionId + "): " + primaryKey);
				fileOffsetIndex.put(transactionId, new EntityBodyStoragePart(primaryKey));
				inserted++;
			}

			log.info("Flushing table (tx " + transactionId + ")");
			fileOffsetIndexDescriptor = fileOffsetIndex.flush(transactionId);
		}

		return new InsertionOutput(
			fileOffsetIndex, Objects.requireNonNull(fileOffsetIndexDescriptor), transactionId, inserted, removed);
	}

	OffsetIndexTest() throws IOException {
	}

	@BeforeEach
	void setUp() throws IOException {
		this.targetFile.toFile().delete();
		cleanTestSubDirectory(TEST_FOLDER);
	}

	@AfterEach
	void tearDown() throws IOException {
		this.targetFile.toFile().delete();
		cleanTestSubDirectory(TEST_FOLDER);
	}

	/**
	 * Serialization round-trip: records written through the public API survive a flush, a reload
	 * from the persisted descriptor and a snapshot copy, and read back intact - covering empty
	 * indexes, bulk inserts, record removal and manual binary deserialization.
	 */
	@Nested
	@DisplayName("Persistence round-trip")
	class PersistenceRoundTrip {

		@DisplayName("Offset index can be stored empty.")
		@ParameterizedTest
		@MethodSource("io.evitadb.store.offsetIndex.OffsetIndexTest#combineSettings")
		void shouldSerializeAndReconstructEmptyOffsetIndex(ChecksumCheck crc32Check, Compression compression) {
			final StorageSettings storageOptions = configure(StorageOptions.temporary(), crc32Check, compression);
			try (final ObservableOutputKeeper observableOutputKeeper = createMockedObservableOutputKeeper()) {
				final InsertionOutput insertionOutput = shouldSerializeAndReconstructOffsetIndex(
					storageOptions, observableOutputKeeper, EntityBodyStoragePart::new, 0
				);
				IOUtils.closeQuietly(insertionOutput.fileOffsetIndex::close);
			}
		}

		@DisplayName("Offset index can be stored empty and then new records added.")
		@ParameterizedTest
		@MethodSource("io.evitadb.store.offsetIndex.OffsetIndexTest#combineSettings")
		void shouldSerializeEmptyOffsetIndexWithLaterAddingRecordsAndReconstructCorrectly(
			ChecksumCheck crc32Check, Compression compression) {
			final StorageSettings storageOptions = configure(StorageOptions.temporary(), crc32Check, compression);
			try (final ObservableOutputKeeper observableOutputKeeper = createMockedObservableOutputKeeper()) {
				final InsertionOutput insertionOutput = shouldSerializeAndReconstructOffsetIndex(
					storageOptions, observableOutputKeeper, EntityBodyStoragePart::new, 0
				);
				final OffsetIndex offsetIndex = insertionOutput.fileOffsetIndex();
				final InsertionOutput insertionOutput2 = createRecordsInFileOffsetIndex(offsetIndex, 100, 0, 1);
				/* input count records +1 record for the OffsetIndex itself */
				if (crc32Check == ChecksumCheck.YES) {
					assertEquals(
						/* 100 records, 1 empty header, 1 header with single fragment */
						100 + computeExpectedRecordCount(100, storageOptions.outputBufferSize()).fragments() + 1,
						offsetIndex.verifyContents().getRecordCount()
					);
				}
				assertEquals(
					100,
					offsetIndex.count(insertionOutput2.catalogVersion())
				);
				IOUtils.closeQuietly(offsetIndex::close);
			}
		}

		@DisplayName("Hundreds entities should be stored in OffsetIndex and retrieved intact.")
		@ParameterizedTest
		@MethodSource("io.evitadb.store.offsetIndex.OffsetIndexTest#combineSettings")
		void shouldSerializeAndReconstructBigFileOffsetIndex(ChecksumCheck crc32Check, Compression compression) {
			final InsertionOutput insertionOutput = serializeAndReconstructBigFileOffsetIndex(
				configure(StorageOptions.temporary(), crc32Check, compression), EntityBodyStoragePart::new);
			IOUtils.closeQuietly(insertionOutput.fileOffsetIndex()::close);
		}

		@DisplayName("Half of the entities should be removed, file offset index copied to different file and reconstructed.")
		@ParameterizedTest
		@MethodSource("io.evitadb.store.offsetIndex.OffsetIndexTest#combineSettings")
		void shouldCopySnapshotOfTheBigFileOffsetIndexAndReconstruct(ChecksumCheck crc32Check, Compression compression) {
			final Random random = new Random(42);
			final StorageSettings limitedBufferSettings = buildOptionsWithLimitedBuffer(crc32Check, compression);
			final Map<Integer, EntityBodyStoragePart> parts = new HashMap<>();
			final InsertionOutput insertionOutput = serializeAndReconstructBigFileOffsetIndex(
				limitedBufferSettings,
				pk -> parts.computeIfAbsent(
					pk, thePk -> createEntityBodyStoragePartOfRandomSize(limitedBufferSettings, random, thePk))
			);
			final OffsetIndexDescriptor fileOffsetIndexDescriptor = insertionOutput.descriptor();
			final StorageSettings storageSettings = configure(StorageOptions.temporary(), crc32Check, compression);
			IOUtils.closeQuietly(insertionOutput.fileOffsetIndex()::close);

			Path snapshotPath = null;
			OffsetIndex sourceOffsetIndex = null;
			OffsetIndex purgedSourceOffsetIndex = null;
			OffsetIndex loadedFileOffsetIndex = null;
			try (final ObservableOutputKeeper observableOutputKeeper = createMockedObservableOutputKeeper()) {
				sourceOffsetIndex = loadOffsetIndex(
					insertionOutput.catalogVersion(),
					fileOffsetIndexDescriptor,
					limitedBufferSettings,
					createWriteOnlyFileHandle(OffsetIndexTest.this.targetFile, storageSettings, observableOutputKeeper),
					OffsetIndexTest.this.offsetIndexRecordTypeRegistry
				);

				int recordCount = sourceOffsetIndex.count(insertionOutput.catalogVersion());
				final long nextCatalogVersion = insertionOutput.catalogVersion() + 1;
				// delete every other record
				for (int i = 1; i <= recordCount; i = i + 2) {
					sourceOffsetIndex.remove(nextCatalogVersion, i, EntityBodyStoragePart.class);
				}

				final OffsetIndexDescriptor updatedOffsetIndexDescriptor = sourceOffsetIndex.flush(nextCatalogVersion);
				purgedSourceOffsetIndex = loadOffsetIndex(
					nextCatalogVersion,
					updatedOffsetIndexDescriptor,
					limitedBufferSettings,
					createWriteOnlyFileHandle(OffsetIndexTest.this.targetFile, storageSettings, observableOutputKeeper),
					OffsetIndexTest.this.offsetIndexRecordTypeRegistry
				);

				// now create a snapshot of the file offset index
				snapshotPath = Path.of(System.getProperty("java.io.tmpdir") + File.separator + "snapshot.kryo");
				final long finalCatalogVersion = nextCatalogVersion + 1;
				final OffsetIndexDescriptor snapshotBootstrapDescriptor;
				try (final FileOutputStream fos = new FileOutputStream(snapshotPath.toFile())) {
					snapshotBootstrapDescriptor = purgedSourceOffsetIndex.copySnapshotTo(fos, null, finalCatalogVersion);
				} catch (IOException e) {
					throw new AssertionFailedError("IO exception!", e);
				}

				loadedFileOffsetIndex = new OffsetIndex(
					snapshotBootstrapDescriptor.version(),
					snapshotBootstrapDescriptor,
					limitedBufferSettings.outputBufferSize(),
					limitedBufferSettings.maxOpenedReadHandlesOrDefault(),
					limitedBufferSettings.lockTimeoutSeconds(),
					limitedBufferSettings.waitOnCloseSeconds(),
					limitedBufferSettings,
					limitedBufferSettings,
					OffsetIndexTest.this.offsetIndexRecordTypeRegistry,
					createWriteOnlyFileHandle(snapshotPath, storageSettings, observableOutputKeeper),
					NO_OP_NON_FLUSHED_BLOCK_CALLBACK,
					NO_OP_OLDEST_RECORD_CALLBACK
				);

				assertEquals(
					purgedSourceOffsetIndex.count(finalCatalogVersion), loadedFileOffsetIndex.count(finalCatalogVersion));
				assertEquals(purgedSourceOffsetIndex.getTotalSizeBytes(), loadedFileOffsetIndex.getTotalSizeBytes());
				for (int i = 2; i <= recordCount; i = i + 2) {
					final EntityBodyStoragePart actual = loadedFileOffsetIndex.get(
						finalCatalogVersion, i, EntityBodyStoragePart.class);
					assertEquals(
						parts.get(i),
						actual
					);
				}
			} finally {
				if (sourceOffsetIndex != null) {
					IOUtils.closeQuietly(sourceOffsetIndex::close);
				}
				if (purgedSourceOffsetIndex != null) {
					IOUtils.closeQuietly(purgedSourceOffsetIndex::close);
				}
				if (loadedFileOffsetIndex != null) {
					IOUtils.closeQuietly(loadedFileOffsetIndex::close);
				}
				if (snapshotPath != null) {
					snapshotPath.toFile().delete();
				}
			}
		}

		@DisplayName("Fully-live (gap-free) file offset index copied to different file and reconstructed.")
		@ParameterizedTest
		@MethodSource("io.evitadb.store.offsetIndex.OffsetIndexTest#combineSettings")
		void shouldCopySnapshotOfTheFullyLiveFileOffsetIndexAndReconstruct(ChecksumCheck crc32Check, Compression compression) {
			final Random random = new Random(42);
			final StorageSettings limitedBufferSettings = buildOptionsWithLimitedBuffer(crc32Check, compression);
			final Map<Integer, EntityBodyStoragePart> parts = new HashMap<>();
			final InsertionOutput insertionOutput = serializeAndReconstructBigFileOffsetIndex(
				limitedBufferSettings,
				pk -> parts.computeIfAbsent(
					pk, thePk -> createEntityBodyStoragePartOfRandomSize(limitedBufferSettings, random, thePk))
			);
			final OffsetIndexDescriptor fileOffsetIndexDescriptor = insertionOutput.descriptor();
			final StorageSettings storageSettings = configure(StorageOptions.temporary(), crc32Check, compression);
			IOUtils.closeQuietly(insertionOutput.fileOffsetIndex()::close);

			Path snapshotPath = null;
			OffsetIndex sourceOffsetIndex = null;
			OffsetIndex loadedFileOffsetIndex = null;
			try (final ObservableOutputKeeper observableOutputKeeper = createMockedObservableOutputKeeper()) {
				sourceOffsetIndex = loadOffsetIndex(
					insertionOutput.catalogVersion(),
					fileOffsetIndexDescriptor,
					limitedBufferSettings,
					createWriteOnlyFileHandle(OffsetIndexTest.this.targetFile, storageSettings, observableOutputKeeper),
					OffsetIndexTest.this.offsetIndexRecordTypeRegistry
				);

				final int recordCount = sourceOffsetIndex.count(insertionOutput.catalogVersion());
				// no record is removed, so every live record sits physically adjacent to the next one. The
				// position-sorted snapshot copy must therefore take the contiguity fast-path (skip the per-record
				// seek) for every record after the first and still reconstruct each payload byte-perfectly - the
				// complement of the gap-heavy test above, which forces a seek before every record.
				snapshotPath = Path.of(System.getProperty("java.io.tmpdir") + File.separator + "snapshot-fully-live.kryo");
				final long finalCatalogVersion = insertionOutput.catalogVersion() + 1;
				final OffsetIndexDescriptor snapshotBootstrapDescriptor;
				try (final FileOutputStream fos = new FileOutputStream(snapshotPath.toFile())) {
					snapshotBootstrapDescriptor = sourceOffsetIndex.copySnapshotTo(fos, null, finalCatalogVersion);
				} catch (IOException e) {
					throw new AssertionFailedError("IO exception!", e);
				}

				loadedFileOffsetIndex = new OffsetIndex(
					snapshotBootstrapDescriptor.version(),
					snapshotBootstrapDescriptor,
					limitedBufferSettings.outputBufferSize(),
					limitedBufferSettings.maxOpenedReadHandlesOrDefault(),
					limitedBufferSettings.lockTimeoutSeconds(),
					limitedBufferSettings.waitOnCloseSeconds(),
					limitedBufferSettings,
					limitedBufferSettings,
					OffsetIndexTest.this.offsetIndexRecordTypeRegistry,
					createWriteOnlyFileHandle(snapshotPath, storageSettings, observableOutputKeeper),
					NO_OP_NON_FLUSHED_BLOCK_CALLBACK,
					NO_OP_OLDEST_RECORD_CALLBACK
				);

				assertEquals(
					sourceOffsetIndex.count(finalCatalogVersion), loadedFileOffsetIndex.count(finalCatalogVersion));
				assertEquals(sourceOffsetIndex.getTotalSizeBytes(), loadedFileOffsetIndex.getTotalSizeBytes());
				for (int i = 1; i <= recordCount; i++) {
					final EntityBodyStoragePart actual = loadedFileOffsetIndex.get(
						finalCatalogVersion, i, EntityBodyStoragePart.class);
					assertEquals(
						parts.get(i),
						actual
					);
				}
			} finally {
				if (sourceOffsetIndex != null) {
					IOUtils.closeQuietly(sourceOffsetIndex::close);
				}
				if (loadedFileOffsetIndex != null) {
					IOUtils.closeQuietly(loadedFileOffsetIndex::close);
				}
				if (snapshotPath != null) {
					snapshotPath.toFile().delete();
				}
			}
		}

		@DisplayName("Partially-live (interleaved gaps) file offset index copied to different file and reconstructed.")
		@ParameterizedTest
		@MethodSource("io.evitadb.store.offsetIndex.OffsetIndexTest#combineSettings")
		void shouldCopySnapshotOfThePartiallyLiveFileOffsetIndexAndReconstruct(ChecksumCheck crc32Check, Compression compression) {
			final Random random = new Random(42);
			final StorageSettings limitedBufferSettings = buildOptionsWithLimitedBuffer(crc32Check, compression);
			final Map<Integer, EntityBodyStoragePart> parts = new HashMap<>();
			final InsertionOutput insertionOutput = serializeAndReconstructBigFileOffsetIndex(
				limitedBufferSettings,
				pk -> parts.computeIfAbsent(
					pk, thePk -> createEntityBodyStoragePartOfRandomSize(limitedBufferSettings, random, thePk))
			);
			final OffsetIndexDescriptor fileOffsetIndexDescriptor = insertionOutput.descriptor();
			final StorageSettings storageSettings = configure(StorageOptions.temporary(), crc32Check, compression);
			IOUtils.closeQuietly(insertionOutput.fileOffsetIndex()::close);

			Path snapshotPath = null;
			OffsetIndex sourceOffsetIndex = null;
			OffsetIndex purgedSourceOffsetIndex = null;
			OffsetIndex loadedFileOffsetIndex = null;
			try (final ObservableOutputKeeper observableOutputKeeper = createMockedObservableOutputKeeper()) {
				sourceOffsetIndex = loadOffsetIndex(
					insertionOutput.catalogVersion(),
					fileOffsetIndexDescriptor,
					limitedBufferSettings,
					createWriteOnlyFileHandle(OffsetIndexTest.this.targetFile, storageSettings, observableOutputKeeper),
					OffsetIndexTest.this.offsetIndexRecordTypeRegistry
				);

				final int recordCount = sourceOffsetIndex.count(insertionOutput.catalogVersion());
				final long nextCatalogVersion = insertionOutput.catalogVersion() + 1;
				// remove every third record, leaving runs of two contiguous live records separated by
				// single-record gaps. The position-sorted snapshot copy must therefore alternate between the
				// contiguity fast-path (within a run) and the per-record seek (across each gap) inside one
				// copy, mixing both branches the fully-live and gap-heavy tests exercise in isolation.
				for (int i = 1; i <= recordCount; i = i + 3) {
					sourceOffsetIndex.remove(nextCatalogVersion, i, EntityBodyStoragePart.class);
				}

				final OffsetIndexDescriptor updatedOffsetIndexDescriptor = sourceOffsetIndex.flush(nextCatalogVersion);
				purgedSourceOffsetIndex = loadOffsetIndex(
					nextCatalogVersion,
					updatedOffsetIndexDescriptor,
					limitedBufferSettings,
					createWriteOnlyFileHandle(OffsetIndexTest.this.targetFile, storageSettings, observableOutputKeeper),
					OffsetIndexTest.this.offsetIndexRecordTypeRegistry
				);

				// now create a snapshot of the file offset index
				snapshotPath = Path.of(System.getProperty("java.io.tmpdir") + File.separator + "snapshot-partial.kryo");
				final long finalCatalogVersion = nextCatalogVersion + 1;
				final OffsetIndexDescriptor snapshotBootstrapDescriptor;
				try (final FileOutputStream fos = new FileOutputStream(snapshotPath.toFile())) {
					snapshotBootstrapDescriptor = purgedSourceOffsetIndex.copySnapshotTo(fos, null, finalCatalogVersion);
				} catch (IOException e) {
					throw new AssertionFailedError("IO exception!", e);
				}

				loadedFileOffsetIndex = new OffsetIndex(
					snapshotBootstrapDescriptor.version(),
					snapshotBootstrapDescriptor,
					limitedBufferSettings.outputBufferSize(),
					limitedBufferSettings.maxOpenedReadHandlesOrDefault(),
					limitedBufferSettings.lockTimeoutSeconds(),
					limitedBufferSettings.waitOnCloseSeconds(),
					limitedBufferSettings,
					limitedBufferSettings,
					OffsetIndexTest.this.offsetIndexRecordTypeRegistry,
					createWriteOnlyFileHandle(snapshotPath, storageSettings, observableOutputKeeper),
					NO_OP_NON_FLUSHED_BLOCK_CALLBACK,
					NO_OP_OLDEST_RECORD_CALLBACK
				);

				assertEquals(
					purgedSourceOffsetIndex.count(finalCatalogVersion), loadedFileOffsetIndex.count(finalCatalogVersion));
				assertEquals(purgedSourceOffsetIndex.getTotalSizeBytes(), loadedFileOffsetIndex.getTotalSizeBytes());
				// every surviving record (the two records following each removed one) must reconstruct
				// byte-perfectly; the removed records are those where (i - 1) % 3 == 0
				for (int i = 1; i <= recordCount; i++) {
					if ((i - 1) % 3 == 0) {
						continue;
					}
					final EntityBodyStoragePart actual = loadedFileOffsetIndex.get(
						finalCatalogVersion, i, EntityBodyStoragePart.class);
					assertEquals(
						parts.get(i),
						actual
					);
				}
			} finally {
				if (sourceOffsetIndex != null) {
					IOUtils.closeQuietly(sourceOffsetIndex::close);
				}
				if (purgedSourceOffsetIndex != null) {
					IOUtils.closeQuietly(purgedSourceOffsetIndex::close);
				}
				if (loadedFileOffsetIndex != null) {
					IOUtils.closeQuietly(loadedFileOffsetIndex::close);
				}
				if (snapshotPath != null) {
					snapshotPath.toFile().delete();
				}
			}
		}

		@DisplayName("Existing record can be removed")
		@ParameterizedTest
		@MethodSource("io.evitadb.store.offsetIndex.OffsetIndexTest#combineSettings")
		void shouldRemoveRecord(ChecksumCheck crc32Check, Compression compression) {
			// store 300 records in multiple chunks,
			final int recordCount = 50;
			final int removedRecords = 10;
			final int iterationCount = 6;

			final StorageSettings storageOptions = configure(StorageOptions.temporary(), crc32Check, compression);
			InsertionOutput insertionResult = null;
			OffsetIndex loadedFileOffsetIndex = null;
			try (final ObservableOutputKeeper observableOutputKeeper = createMockedObservableOutputKeeper()) {
				insertionResult = createRecordsInFileOffsetIndex(
					storageOptions, observableOutputKeeper, recordCount, removedRecords, iterationCount
				);

				final OffsetIndexDescriptor fileOffsetIndexInfo = insertionResult.descriptor();
				loadedFileOffsetIndex = loadOffsetIndex(
					0L,
					fileOffsetIndexInfo,
					storageOptions,
					createWriteOnlyFileHandle(OffsetIndexTest.this.targetFile, storageOptions, observableOutputKeeper),
					OffsetIndexTest.this.offsetIndexRecordTypeRegistry
				);

				for (int i = 1; i <= recordCount * iterationCount; i++) {
					final EntityBodyStoragePart actual = loadedFileOffsetIndex.get(
						insertionResult.catalogVersion(), i, EntityBodyStoragePart.class);
					if (i < recordCount * (iterationCount - 1) && i % recordCount < removedRecords && i % recordCount > 0) {
						assertNull(actual);
					} else {
						assertEquals(
							new EntityBodyStoragePart(i),
							actual
						);
					}
				}

				assertTrue(insertionResult.fileOffsetIndex().fileOffsetIndexEquals(loadedFileOffsetIndex));
				if (crc32Check == ChecksumCheck.YES) {
					/* 300 records +6 record for th OffsetIndex itself */
					assertEquals(306, loadedFileOffsetIndex.verifyContents().getRecordCount());
				}
				assertEquals(
					insertionResult.insertedTotal() - insertionResult.removedTotal(),
					loadedFileOffsetIndex.count(0L)
				);
			} finally {
				if (insertionResult != null) {
					IOUtils.closeQuietly(insertionResult.fileOffsetIndex()::close);
				}
				if (loadedFileOffsetIndex != null) {
					IOUtils.closeQuietly(loadedFileOffsetIndex::close);
				}
			}
		}

		@DisplayName("Binary records can be read back and deserialized manually")
		@ParameterizedTest
		@MethodSource("io.evitadb.store.offsetIndex.OffsetIndexTest#combineSettings")
		void shouldReadBinaryRecordAndDeserializeManually(ChecksumCheck crc32Check, Compression compression) {
			// store 300 records in multiple chunks,
			final int recordCount = 50;
			final int removedRecords = 10;
			final int iterationCount = 6;

			final StorageSettings storageSettings = configure(StorageOptions.temporary(), crc32Check, compression);

			InsertionOutput insertionResult = null;
			OffsetIndex loadedFileOffsetIndex = null;
			try (final ObservableOutputKeeper observableOutputKeeper = createMockedObservableOutputKeeper()) {
				insertionResult = createRecordsInFileOffsetIndex(
					storageSettings, observableOutputKeeper, recordCount, removedRecords, iterationCount
				);

				final OffsetIndexDescriptor fileOffsetIndexDescriptor = insertionResult.descriptor();
				loadedFileOffsetIndex = loadOffsetIndex(
					0L,
					fileOffsetIndexDescriptor,
					storageSettings,
					createWriteOnlyFileHandle(OffsetIndexTest.this.targetFile, storageSettings, observableOutputKeeper),
					OffsetIndexTest.this.offsetIndexRecordTypeRegistry
				);

				final VersionedKryo kryo = createKryo()
					.apply(
						new VersionedKryoKeyInputs(
							loadedFileOffsetIndex.getReadOnlyKeyCompressor(), 1
						)
					);

				for (int i = 1; i <= recordCount * iterationCount; i++) {
					final byte[] actualBinary = loadedFileOffsetIndex.getBinary(
						insertionResult.catalogVersion(), i, EntityBodyStoragePart.class);
					if (i < recordCount * (iterationCount - 1) && i % recordCount < removedRecords && i % recordCount > 0) {
						assertNull(actualBinary);
					} else {
						assertNotNull(actualBinary);
						assertEquals(
							new EntityBodyStoragePart(i),
							kryo.readObject(new Input(actualBinary), EntityBodyStoragePart.class)
						);
					}
				}

				assertTrue(insertionResult.fileOffsetIndex().fileOffsetIndexEquals(loadedFileOffsetIndex));
				/* 300 records +6 record for th OffsetIndex itself */
				if (crc32Check == ChecksumCheck.YES) {
					assertEquals(306, loadedFileOffsetIndex.verifyContents().getRecordCount());
				}
				assertEquals(
					insertionResult.insertedTotal() - insertionResult.removedTotal(),
					loadedFileOffsetIndex.count(0L)
				);
			} finally {
				if (insertionResult != null) {
					IOUtils.closeQuietly(insertionResult.fileOffsetIndex()::close);
				}
				if (loadedFileOffsetIndex != null) {
					IOUtils.closeQuietly(loadedFileOffsetIndex::close);
				}
			}
		}

		@DisplayName("A single record can be read back and deserialized manually")
		@ParameterizedTest
		@MethodSource("io.evitadb.store.offsetIndex.OffsetIndexTest#combineSettings")
		void shouldReadSingleRecordAndUsingManualDeserialization(ChecksumCheck crc32Check, Compression compression) {
			// store 300 records in multiple chunks,
			final int recordCount = 50;
			final int removedRecords = 10;
			final int iterationCount = 6;

			final StorageSettings storageSettings = configure(StorageOptions.temporary(), crc32Check, compression);

			try (final ObservableOutputKeeper observableOutputKeeper = createMockedObservableOutputKeeper()) {
				final InsertionOutput insertionResult = createRecordsInFileOffsetIndex(
					storageSettings, observableOutputKeeper, recordCount, removedRecords, iterationCount
				);

				final OffsetIndexDescriptor offsetIndexDescriptor = insertionResult.descriptor();

				final VersionedKryo kryo = createKryo()
					.apply(
						new VersionedKryoKeyInputs(
							offsetIndexDescriptor.getReadOnlyKeyCompressor(), 1
						)
					);

				for (int i = 1; i <= recordCount * iterationCount; i++) {
					final RecordKey key = new RecordKey(
						OffsetIndexTest.this.offsetIndexRecordTypeRegistry.idFor(EntityBodyStoragePart.class),
						i
					);

					final Supplier<EntityBodyStoragePart> entityBodySupplier = () -> OffsetIndex.readSingleRecord(
						storageSettings,
						storageSettings,
						OffsetIndexTest.this.targetFile,
						offsetIndexDescriptor.fileLocation(),
						key,
						(offsetIndexBuilder, input) -> offsetIndexBuilder.getFileLocationFor(key)
							.map(fileLocation -> StorageRecord.read(
								input, fileLocation,
								(theInput, length, control) -> kryo.readObject(theInput, EntityBodyStoragePart.class)
							).payload())
							.orElse(null)
					);
					if (i < recordCount * (iterationCount - 1) && i % recordCount < removedRecords && i % recordCount > 0) {
						assertThrows(NullPointerException.class, entityBodySupplier::get);
					} else {
						final EntityBodyStoragePart entityBody = entityBodySupplier.get();
						assertNotNull(entityBody);
						assertEquals(
							new EntityBodyStoragePart(i),
							entityBody
						);
					}
				}
			}
		}
	}

	/**
	 * Lifecycle guards: once the index is closed every public read/write operation must fail fast
	 * rather than operating on released resources.
	 */
	@Nested
	@DisplayName("Lifecycle")
	class Lifecycle {

		@DisplayName("No operation should be allowed after close")
		@Test
		void shouldRefuseOperationAfterClose() {
			final StorageSettings storageSettings = new StorageSettings(
				StorageOptions.temporary(),
				DEFAULT_TRANSACTION_OPTIONS
			);
			try (final ObservableOutputKeeper observableOutputKeeper = createMockedObservableOutputKeeper()) {
				final OffsetIndex fileOffsetIndex = createNewOffsetIndex(
					0L,
					storageSettings,
					createWriteOnlyFileHandle(OffsetIndexTest.this.targetFile, storageSettings, observableOutputKeeper),
					OffsetIndexTest.this.offsetIndexRecordTypeRegistry
				);
				fileOffsetIndex.put(0L, new EntityBodyStoragePart(1));
				fileOffsetIndex.close();

				assertThrows(EvitaInternalError.class, () -> fileOffsetIndex.get(0L, 1, EntityBodyStoragePart.class));
				assertThrows(EvitaInternalError.class, () -> fileOffsetIndex.put(0L, new EntityBodyStoragePart(2)));
				assertThrows(EvitaInternalError.class, fileOffsetIndex::getEntries);
				assertThrows(EvitaInternalError.class, fileOffsetIndex::getKeys);
				assertThrows(EvitaInternalError.class, fileOffsetIndex::getFileLocations);
				assertThrows(EvitaInternalError.class, () -> fileOffsetIndex.flush(0L));
			}
		}
	}

	/**
	 * The growth half of the compaction forecast's input. Growth is deliberately read off the data file's own end
	 * position rather than summed from the record bodies a flush promotes, and this pins the case that distinguishes
	 * the two.
	 */
	@Nested
	@DisplayName("Growth sampling")
	class GrowthSampling {

		@DisplayName("A flush that only removes records still registers the bytes the file gained")
		@Test
		void shouldRegisterGrowthOfARemovalOnlyFlush() {
			final StorageSettings storageSettings = new StorageSettings(
				StorageOptions.temporary(),
				DEFAULT_TRANSACTION_OPTIONS
			);
			try (final ObservableOutputKeeper observableOutputKeeper = createMockedObservableOutputKeeper()) {
				final OffsetIndex offsetIndex = createNewOffsetIndex(
					0L,
					storageSettings,
					createWriteOnlyFileHandle(OffsetIndexTest.this.targetFile, storageSettings, observableOutputKeeper),
					OffsetIndexTest.this.offsetIndexRecordTypeRegistry
				);
				try {
					offsetIndex.put(1L, new EntityBodyStoragePart(1));
					offsetIndex.put(1L, new EntityBodyStoragePart(2));
					offsetIndex.flush(1L);
					final long sizeAfterWrites = offsetIndex.getFileSize();
					final long appendedAfterWrites = offsetIndex.getWasteAccumulation().fileBytesAppended();

					// a removal appends no record body at all - it writes a tombstone into the offset index fragment
					// that every flush appends anyway. Summing the promoted bodies therefore scored this flush as
					// zero growth, which made a delete-only store look like a file that never lengthens and left its
					// compaction unforecast
					offsetIndex.remove(2L, 1, EntityBodyStoragePart.class);
					offsetIndex.remove(2L, 2, EntityBodyStoragePart.class);
					offsetIndex.flush(2L);

					final long sizeAfterRemovals = offsetIndex.getFileSize();
					assertTrue(
						sizeAfterRemovals > sizeAfterWrites,
						"a removal-only flush still lengthens the file"
					);
					assertEquals(
						sizeAfterRemovals - sizeAfterWrites,
						offsetIndex.getWasteAccumulation().fileBytesAppended() - appendedAfterWrites,
						"growth must account for exactly what the file gained"
					);
				} finally {
					offsetIndex.close();
				}
			}
		}
	}

	/**
	 * Black-box characterization of {@link OffsetIndex} multi-version concurrency control. Every assertion
	 * is expressed solely through the public read/write surface (`put`, `remove`, `flush`, `get`,
	 * `getBinary`, `contains`, `count`, `getEntries`, the loading constructor). No internal version-tracking
	 * machinery is referenced, so this suite stays valid as an oracle even when the historical-version
	 * subsystem is reimplemented.
	 *
	 * The index resolves reads against three conceptual tiers and these tests pin the observable outcome of
	 * each: in-flight changes not yet flushed, historical snapshots for catalog versions preceding the
	 * current one, and the current state filtered by the per-record generation id.
	 */
	@Nested
	@DisplayName("MVCC version resolution")
	class MvccVersionResolution {

		/**
		 * Non-flushed (in-flight) reads: changes performed at a catalog version must be visible at that same
		 * version even before any flush, while older flushed versions keep their prior view.
		 */
		@Nested
		@DisplayName("In-flight changes before flush")
		class InFlightChanges {

			@DisplayName("get/getBinary/contains see a freshly put record before any flush")
			@Test
			void shouldSeeFreshlyPutRecordBeforeFlush() {
				runWithIndex((offsetIndex, decoder) -> {
					final EntityBodyStoragePart r1 = bodyPartWithLocale(1, 1, Locale.ENGLISH);

					offsetIndex.put(1L, r1);

					assertEquals(
						r1,
						offsetIndex.get(1L, 1, EntityBodyStoragePart.class),
						"get at v1 should return the non-flushed record"
					);
					assertEquals(
						r1,
						decoder.decode(offsetIndex.getBinary(1L, 1, EntityBodyStoragePart.class)),
						"getBinary at v1 should return the non-flushed record"
					);
					assertTrue(
						offsetIndex.contains(1L, 1, EntityBodyStoragePart.class),
						"contains at v1 should be true for the non-flushed record"
					);
				});
			}

			@DisplayName("removing a flushed record before flush hides it at the new version but not the old")
			@Test
			void shouldHideFlushedRecordRemovedBeforeFlushAtNewVersionOnly() {
				runWithIndex((offsetIndex, decoder) -> {
					final EntityBodyStoragePart r1 = bodyPartWithLocale(1, 1, Locale.ENGLISH);

					offsetIndex.put(1L, r1);
					offsetIndex.flush(1L);
					offsetIndex.remove(2L, 1, EntityBodyStoragePart.class);

					assertNull(
						offsetIndex.get(2L, 1, EntityBodyStoragePart.class),
						"get at v2 should return null for the record removed before flush"
					);
					assertFalse(
						offsetIndex.contains(2L, 1, EntityBodyStoragePart.class),
						"contains at v2 should be false for the record removed before flush"
					);
					assertEquals(
						r1,
						offsetIndex.get(1L, 1, EntityBodyStoragePart.class),
						"get at v1 should still return the record flushed at the prior version"
					);
					assertTrue(
						offsetIndex.contains(1L, 1, EntityBodyStoragePart.class),
						"contains at v1 should still be true for the record flushed at the prior version"
					);
				});
			}

			@DisplayName("put then remove of the same key in the same version before flush hides the record")
			@Test
			void shouldHideRecordPutAndRemovedInSameVersionBeforeFlush() {
				runWithIndex((offsetIndex, decoder) -> {
					offsetIndex.put(1L, bodyPartWithLocale(1, 1, Locale.ENGLISH));
					offsetIndex.remove(1L, 1, EntityBodyStoragePart.class);

					assertNull(
						offsetIndex.get(1L, 1, EntityBodyStoragePart.class),
						"get at v1 should be null after put-then-remove in the same version"
					);
					assertFalse(
						offsetIndex.contains(1L, 1, EntityBodyStoragePart.class),
						"contains at v1 should be false after put-then-remove in the same version"
					);
					// the two operations fold into a no-op, so the in-flight cardinality must be zero - never
					// negative, which is what recording the removal of a record that was never published gives
					assertEquals(
						0, offsetIndex.count(1L),
						"count at v1 should be zero after put-then-remove in the same version"
					);
				});
			}

			@DisplayName("remove then re-put of the same key in the same version before flush returns the record")
			@Test
			void shouldReturnRecordRePutAfterRemoveInSameVersionBeforeFlush() {
				runWithIndex((offsetIndex, decoder) -> {
					final EntityBodyStoragePart original = bodyPartWithLocale(1, 1, Locale.ENGLISH);
					final EntityBodyStoragePart rePut = bodyPartWithLocale(2, 1, Locale.GERMAN);

					offsetIndex.put(1L, original);
					offsetIndex.flush(1L);
					offsetIndex.remove(2L, 1, EntityBodyStoragePart.class);
					offsetIndex.put(2L, rePut);

					assertEquals(
						rePut,
						offsetIndex.get(2L, 1, EntityBodyStoragePart.class),
						"get at v2 should return the re-put payload"
					);
					assertTrue(
						offsetIndex.contains(2L, 1, EntityBodyStoragePart.class),
						"contains at v2 should be true after remove-then-reput"
					);
					// the removal is undone by the re-put, so the record stays counted - the in-flight count
					// must agree with what the flush is about to publish
					assertEquals(
						1, offsetIndex.count(2L),
						"count at v2 should still be one after remove-then-reput in the same version"
					);
				});
			}

			@DisplayName("remove then re-put of a still-unflushed record keeps the in-flight count at one")
			@Test
			void shouldCountRecordRePutAfterRemoveOverUnflushedRecord() {
				runWithIndex((offsetIndex, decoder) -> {
					// same shape as above, except the record key 1 stands on is itself still in-flight: it was
					// created at v1 and never flushed on its own, so the fold at v2 has to be judged against
					// v1's pending creation rather than against the (still empty) published state
					offsetIndex.put(1L, bodyPartWithLocale(1, 1, Locale.ENGLISH));
					offsetIndex.remove(2L, 1, EntityBodyStoragePart.class);
					offsetIndex.put(2L, bodyPartWithLocale(2, 1, Locale.GERMAN));

					assertEquals(
						1, offsetIndex.count(1L),
						"count at v1 should be one - the record created there is untouched by v2's fold"
					);
					assertEquals(
						1, offsetIndex.count(2L),
						"count at v2 should be one after remove-then-reput over a still-unflushed record"
					);
				});
			}

			@DisplayName("rewriting an existing record before flush returns the new payload at that version")
			@Test
			void shouldReturnRewrittenPayloadBeforeFlush() {
				runWithIndex((offsetIndex, decoder) -> {
					final EntityBodyStoragePart original = bodyPartWithLocale(1, 1, Locale.ENGLISH);
					final EntityBodyStoragePart rewritten = bodyPartWithLocale(2, 1, Locale.GERMAN);

					offsetIndex.put(1L, original);
					offsetIndex.flush(1L);
					offsetIndex.put(2L, rewritten);

					assertEquals(
						rewritten,
						offsetIndex.get(2L, 1, EntityBodyStoragePart.class),
						"get at v2 should return the rewritten payload before flush"
					);
					assertEquals(
						rewritten,
						decoder.decode(offsetIndex.getBinary(2L, 1, EntityBodyStoragePart.class)),
						"getBinary at v2 should return the rewritten payload before flush"
					);
				});
			}
		}

		/**
		 * Historical reads after flushes: each catalog version preceding the current one must expose exactly
		 * the state that existed as of that version.
		 */
		@Nested
		@DisplayName("Historical snapshots across flushes")
		class HistoricalSnapshots {

			@DisplayName("a record rewritten across four flushed versions resolves to each version's payload")
			@Test
			void shouldResolveEachFlushedVersionPayloadForRewrittenRecord() {
				runWithIndex((offsetIndex, decoder) -> {
					final EntityBodyStoragePart v1Payload = bodyPartWithLocale(1, 1, Locale.ENGLISH);
					final EntityBodyStoragePart v2Payload = bodyPartWithLocale(2, 1, Locale.GERMAN);
					final EntityBodyStoragePart v3Payload = bodyPartWithLocale(3, 1, Locale.FRENCH);
					final EntityBodyStoragePart v4Payload = bodyPartWithLocale(4, 1, AVAILABLE_LOCALES[3]);

					offsetIndex.put(1L, v1Payload);
					offsetIndex.flush(1L);
					offsetIndex.put(2L, v2Payload);
					offsetIndex.flush(2L);
					offsetIndex.put(3L, v3Payload);
					offsetIndex.flush(3L);
					offsetIndex.put(4L, v4Payload);
					offsetIndex.flush(4L);

					assertEquals(v1Payload, offsetIndex.get(1L, 1, EntityBodyStoragePart.class),
						"get at v1 should return the v1 payload");
					assertEquals(v2Payload, offsetIndex.get(2L, 1, EntityBodyStoragePart.class),
						"get at v2 should return the v2 payload");
					assertEquals(v3Payload, offsetIndex.get(3L, 1, EntityBodyStoragePart.class),
						"get at v3 should return the v3 payload");
					assertEquals(v4Payload, offsetIndex.get(4L, 1, EntityBodyStoragePart.class),
						"get at the current version should return the latest payload");
				});
			}

			@DisplayName("a record added at v2 is absent at the earlier flushed v1")
			@Test
			void shouldNotSeeRecordAtVersionPrecedingItsInsertion() {
				runWithIndex((offsetIndex, decoder) -> {
					final EntityBodyStoragePart added = bodyPartWithLocale(1, 7, Locale.ENGLISH);

					// establish an earlier flushed version so v1 precedes the record's insertion
					offsetIndex.put(1L, bodyPartWithLocale(1, 99, Locale.GERMAN));
					offsetIndex.flush(1L);
					offsetIndex.put(2L, added);
					offsetIndex.flush(2L);

					assertNull(offsetIndex.get(1L, 7, EntityBodyStoragePart.class),
						"get at v1 should be null for a record added at v2");
					assertFalse(offsetIndex.contains(1L, 7, EntityBodyStoragePart.class),
						"contains at v1 should be false for a record added at v2");
					assertEquals(added, offsetIndex.get(2L, 7, EntityBodyStoragePart.class),
						"get at v2 should return the added record");
				});
			}

			@DisplayName("a record removed at v3 is still visible at the prior flushed v2")
			@Test
			void shouldStillSeeRecordAtVersionPrecedingItsRemoval() {
				runWithIndex((offsetIndex, decoder) -> {
					final EntityBodyStoragePart preRemoval = bodyPartWithLocale(1, 1, Locale.ENGLISH);

					offsetIndex.put(1L, bodyPartWithLocale(1, 50, Locale.GERMAN));
					offsetIndex.flush(1L);
					offsetIndex.put(2L, preRemoval);
					offsetIndex.flush(2L);
					offsetIndex.remove(3L, 1, EntityBodyStoragePart.class);
					offsetIndex.flush(3L);

					assertEquals(preRemoval, offsetIndex.get(2L, 1, EntityBodyStoragePart.class),
						"get at v2 should still return the pre-removal value");
					assertTrue(offsetIndex.contains(2L, 1, EntityBodyStoragePart.class),
						"contains at v2 should still be true before the removal version");
					assertNull(offsetIndex.get(3L, 1, EntityBodyStoragePart.class),
						"get at v3 should be null after removal");
					assertFalse(offsetIndex.contains(3L, 1, EntityBodyStoragePart.class),
						"contains at v3 should be false after removal");
				});
			}

			@DisplayName("create, remove, then re-create with a new payload resolves to each version's state")
			@Test
			void shouldResolveCreateRemoveRecreateLifecycleAcrossVersions() {
				runWithIndex((offsetIndex, decoder) -> {
					final EntityBodyStoragePart originalR1 = bodyPartWithLocale(1, 1, Locale.ENGLISH);
					final EntityBodyStoragePart recreatedR1 = bodyPartWithLocale(2, 1, Locale.GERMAN);

					offsetIndex.put(1L, originalR1);
					offsetIndex.flush(1L);
					offsetIndex.remove(2L, 1, EntityBodyStoragePart.class);
					offsetIndex.flush(2L);
					offsetIndex.put(3L, recreatedR1);
					offsetIndex.flush(3L);

					assertEquals(originalR1, offsetIndex.get(1L, 1, EntityBodyStoragePart.class),
						"get at v1 should return the original payload");
					assertNull(offsetIndex.get(2L, 1, EntityBodyStoragePart.class),
						"get at v2 should be null while the record was removed");
					assertFalse(offsetIndex.contains(2L, 1, EntityBodyStoragePart.class),
						"contains at v2 should be false while the record was removed");
					assertEquals(recreatedR1, offsetIndex.get(3L, 1, EntityBodyStoragePart.class),
						"get at v3 should return the re-created payload");
				});
			}

			@DisplayName("get() should resolve a record at past catalog versions across flushes")
			@Test
			void shouldResolveGetAtPastCatalogVersionAcrossFlushes() {
				final StorageSettings storageSettings = new StorageSettings(
					StorageOptions.temporary(),
					DEFAULT_TRANSACTION_OPTIONS
				);
				try (final ObservableOutputKeeper observableOutputKeeper = createMockedObservableOutputKeeper()) {
					final OffsetIndex offsetIndex = createNewOffsetIndex(
						0L,
						storageSettings,
						createWriteOnlyFileHandle(OffsetIndexTest.this.targetFile, storageSettings, observableOutputKeeper),
						OffsetIndexTest.this.offsetIndexRecordTypeRegistry
					);
					try {
						final EntityBodyStoragePart originalR1 = bodyPartWithLocale(1, 1, Locale.ENGLISH);
						final EntityBodyStoragePart updatedR1 = bodyPartWithLocale(2, 1, Locale.GERMAN);

						// version 1: insert R1, flush
						offsetIndex.put(1L, originalR1);
						offsetIndex.flush(1L);
						// version 2: overwrite R1 with a distinguishable payload, flush
						offsetIndex.put(2L, updatedR1);
						offsetIndex.flush(2L);
						// version 3: remove an unrelated record R2 so the historical state advances again
						offsetIndex.put(3L, new EntityBodyStoragePart(2));
						offsetIndex.remove(3L, 2, EntityBodyStoragePart.class);
						offsetIndex.flush(3L);

						assertEquals(
							originalR1,
							offsetIndex.get(1L, 1, EntityBodyStoragePart.class),
							"get at v1 should return the original payload"
						);
						assertEquals(
							updatedR1,
							offsetIndex.get(2L, 1, EntityBodyStoragePart.class),
							"get at v2 should return the overwritten payload"
						);
						assertEquals(
							updatedR1,
							offsetIndex.get(3L, 1, EntityBodyStoragePart.class),
							"get at the current version should return the latest payload"
						);
					} finally {
						IOUtils.closeQuietly(offsetIndex::close);
					}
				}
			}

			@DisplayName("contains() should resolve presence at past and future catalog versions")
			@Test
			void shouldResolveContainsAtPastAndFutureCatalogVersions() {
				final StorageSettings storageSettings = new StorageSettings(
					StorageOptions.temporary(),
					DEFAULT_TRANSACTION_OPTIONS
				);
				try (final ObservableOutputKeeper observableOutputKeeper = createMockedObservableOutputKeeper()) {
					final OffsetIndex offsetIndex = createNewOffsetIndex(
						0L,
						storageSettings,
						createWriteOnlyFileHandle(OffsetIndexTest.this.targetFile, storageSettings, observableOutputKeeper),
						OffsetIndexTest.this.offsetIndexRecordTypeRegistry
					);
					try {
						// establish an earlier flushed version so v1 precedes the record's insertion
						offsetIndex.put(1L, new EntityBodyStoragePart(9));
						offsetIndex.flush(1L);
						// version 2: insert R, flush
						offsetIndex.put(2L, new EntityBodyStoragePart(1));
						offsetIndex.flush(2L);

						assertFalse(
							offsetIndex.contains(1L, 1, EntityBodyStoragePart.class),
							"contains at v1 should be false (R was added at v2)"
						);
						assertTrue(
							offsetIndex.contains(2L, 1, EntityBodyStoragePart.class),
							"contains at v2 should be true"
						);

						// version 3: remove R, flush
						offsetIndex.remove(3L, 1, EntityBodyStoragePart.class);
						offsetIndex.flush(3L);

						assertTrue(
							offsetIndex.contains(2L, 1, EntityBodyStoragePart.class),
							"contains at v2 should still be true after later removal"
						);
						assertFalse(
							offsetIndex.contains(3L, 1, EntityBodyStoragePart.class),
							"contains at v3 should be false after removal"
						);
					} finally {
						IOUtils.closeQuietly(offsetIndex::close);
					}
				}
			}

			@DisplayName("getBinary() should resolve a record at past catalog versions across flushes")
			@Test
			void shouldResolveGetBinaryAtPastCatalogVersionAcrossFlushes() {
				final StorageSettings storageSettings = new StorageSettings(
					StorageOptions.temporary(),
					DEFAULT_TRANSACTION_OPTIONS
				);
				try (final ObservableOutputKeeper observableOutputKeeper = createMockedObservableOutputKeeper()) {
					final OffsetIndex offsetIndex = createNewOffsetIndex(
						0L,
						storageSettings,
						createWriteOnlyFileHandle(OffsetIndexTest.this.targetFile, storageSettings, observableOutputKeeper),
						OffsetIndexTest.this.offsetIndexRecordTypeRegistry
					);
					try {
						final EntityBodyStoragePart originalR1 = bodyPartWithLocale(1, 1, Locale.ENGLISH);
						final EntityBodyStoragePart updatedR1 = bodyPartWithLocale(2, 1, Locale.GERMAN);

						// version 1: insert R1, flush
						offsetIndex.put(1L, originalR1);
						offsetIndex.flush(1L);
						// version 2: overwrite R1 with a distinguishable payload, flush
						offsetIndex.put(2L, updatedR1);
						offsetIndex.flush(2L);
						// version 3: remove an unrelated record R2 so the historical state advances again
						offsetIndex.put(3L, new EntityBodyStoragePart(2));
						offsetIndex.remove(3L, 2, EntityBodyStoragePart.class);
						offsetIndex.flush(3L);

						final VersionedKryo kryo = createKryo()
							.apply(new VersionedKryoKeyInputs(offsetIndex.getReadOnlyKeyCompressor(), 1));

						assertEquals(
							originalR1,
							deserialize(kryo, offsetIndex.getBinary(1L, 1, EntityBodyStoragePart.class)),
							"getBinary at v1 should return the original payload"
						);
						assertEquals(
							updatedR1,
							deserialize(kryo, offsetIndex.getBinary(2L, 1, EntityBodyStoragePart.class)),
							"getBinary at v2 should return the overwritten payload"
						);
						assertEquals(
							updatedR1,
							deserialize(kryo, offsetIndex.getBinary(3L, 1, EntityBodyStoragePart.class)),
							"getBinary at the current version should return the latest payload"
						);
					} finally {
						IOUtils.closeQuietly(offsetIndex::close);
					}
				}
			}
		}

		/**
		 * Multiple committed versions batched into a single flush. While the batch is still in-flight the
		 * index resolves every intermediate version precisely. Once a single `flush` promotes the whole
		 * batch to the current state, only the flush version retains its own snapshot: the intermediate
		 * versions that were never an explicit flush target collapse, because the promotion records a single
		 * transition rather than one per intermediate version. These tests pin both observable behaviors.
		 */
		@Nested
		@DisplayName("Batched versions in a single flush")
		class BatchedVersionsSingleFlush {

			@DisplayName("in-flight reads resolve every intermediate version precisely before the flush")
			@Test
			void shouldResolveEveryIntermediateVersionWhileInFlight() {
				runWithIndex((offsetIndex, decoder) -> {
					final EntityBodyStoragePart aV1 = bodyPartWithLocale(1, 1, Locale.ENGLISH);
					final EntityBodyStoragePart aV3 = bodyPartWithLocale(3, 1, Locale.FRENCH);
					final EntityBodyStoragePart b = bodyPartWithLocale(1, 2, Locale.GERMAN);
					final EntityBodyStoragePart c = bodyPartWithLocale(1, 3, AVAILABLE_LOCALES[3]);

					// three committed versions with distinct keys plus an overwrite of key 1 at v3, no flush
					offsetIndex.put(1L, aV1);
					offsetIndex.put(2L, b);
					offsetIndex.put(3L, c);
					offsetIndex.put(3L, aV3);

					// version 1: only key 1 exists, with its v1 payload
					assertEquals(aV1, offsetIndex.get(1L, 1, EntityBodyStoragePart.class),
						"get key 1 at v1 in-flight");
					assertFalse(offsetIndex.contains(1L, 2, EntityBodyStoragePart.class),
						"key 2 absent at v1 in-flight");
					assertFalse(offsetIndex.contains(1L, 3, EntityBodyStoragePart.class),
						"key 3 absent at v1 in-flight");

					// version 2: key 1 (still v1 payload) and key 2 exist
					assertEquals(aV1, offsetIndex.get(2L, 1, EntityBodyStoragePart.class),
						"get key 1 at v2 in-flight");
					assertEquals(b, offsetIndex.get(2L, 2, EntityBodyStoragePart.class),
						"get key 2 at v2 in-flight");
					assertFalse(offsetIndex.contains(2L, 3, EntityBodyStoragePart.class),
						"key 3 absent at v2 in-flight");

					// version 3: key 1 overwritten, keys 2 and 3 present
					assertEquals(aV3, offsetIndex.get(3L, 1, EntityBodyStoragePart.class),
						"get key 1 at v3 in-flight");
					assertEquals(b, offsetIndex.get(3L, 2, EntityBodyStoragePart.class),
						"get key 2 at v3 in-flight");
					assertEquals(c, offsetIndex.get(3L, 3, EntityBodyStoragePart.class),
						"get key 3 at v3 in-flight");
				});
			}

			@DisplayName("after one flush every intermediate version still resolves to its own snapshot")
			@Test
			void shouldResolveEveryIntermediateVersionAfterSingleBatchedFlush() {
				runWithIndex((offsetIndex, decoder) -> {
					final EntityBodyStoragePart aV1 = bodyPartWithLocale(1, 1, Locale.ENGLISH);
					final EntityBodyStoragePart aV3 = bodyPartWithLocale(3, 1, Locale.FRENCH);
					final EntityBodyStoragePart b = bodyPartWithLocale(1, 2, Locale.GERMAN);
					final EntityBodyStoragePart c = bodyPartWithLocale(1, 3, AVAILABLE_LOCALES[3]);

					offsetIndex.put(1L, aV1);
					offsetIndex.put(2L, b);
					offsetIndex.put(3L, c);
					offsetIndex.put(3L, aV3);

					// a single flush promotes the whole batch, retaining a snapshot per committed version
					offsetIndex.flush(3L);

					// every intermediate version resolves to exactly the state it held - the same answers the
					// in-flight reads gave before the flush (promotion preserves per-version snapshots)
					assertEquals(aV1, offsetIndex.get(1L, 1, EntityBodyStoragePart.class),
						"get key 1 at v1 resolves its v1 payload");
					assertFalse(offsetIndex.contains(1L, 2, EntityBodyStoragePart.class),
						"key 2 absent at v1");
					assertFalse(offsetIndex.contains(1L, 3, EntityBodyStoragePart.class),
						"key 3 absent at v1");

					assertEquals(aV1, offsetIndex.get(2L, 1, EntityBodyStoragePart.class),
						"get key 1 at v2 still resolves its v1 payload");
					assertEquals(b, offsetIndex.get(2L, 2, EntityBodyStoragePart.class),
						"get key 2 at v2 resolves its payload");
					assertFalse(offsetIndex.contains(2L, 3, EntityBodyStoragePart.class),
						"key 3 absent at v2");

					assertEquals(aV3, offsetIndex.get(3L, 1, EntityBodyStoragePart.class),
						"get key 1 at v3 resolves the overwrite");
					assertEquals(b, offsetIndex.get(3L, 2, EntityBodyStoragePart.class),
						"get key 2 at v3 resolves its payload");
					assertEquals(c, offsetIndex.get(3L, 3, EntityBodyStoragePart.class),
						"get key 3 at v3 resolves its payload");

					assertEquals(1, offsetIndex.count(1L), "count at v1 sees one key");
					assertEquals(2, offsetIndex.count(2L), "count at v2 sees two keys");
					assertEquals(3, offsetIndex.count(3L), "count at v3 sees three keys");
				});
			}

			@DisplayName("get and contains agree at every version after a batched flush")
			@Test
			void shouldResolveContainsConsistentlyWithGetAfterBatchedFlush() {
				runWithIndex((offsetIndex, decoder) -> {
					final EntityBodyStoragePart aV1 = bodyPartWithLocale(1, 1, Locale.ENGLISH);
					final EntityBodyStoragePart aV3 = bodyPartWithLocale(3, 1, Locale.FRENCH);

					// key 1 first written at v1 and overwritten at v3, promoted in a single flush at v3
					offsetIndex.put(1L, aV1);
					offsetIndex.put(3L, aV3);
					offsetIndex.flush(3L);

					// the v1 snapshot still holds key 1 with its v1 payload, and contains agrees with get there
					assertEquals(aV1, offsetIndex.get(1L, 1, EntityBodyStoragePart.class),
						"get key 1 at v1 resolves its v1 payload after the batched flush");
					assertTrue(offsetIndex.contains(1L, 1, EntityBodyStoragePart.class),
						"contains key 1 at v1 agrees with get");
					// and the overwrite resolves at v3, again consistently between get and contains
					assertEquals(aV3, offsetIndex.get(3L, 1, EntityBodyStoragePart.class),
						"get key 1 at v3 resolves the overwrite");
					assertTrue(offsetIndex.contains(3L, 1, EntityBodyStoragePart.class),
						"contains key 1 at v3 agrees with get");
				});
			}

			@DisplayName("count at an in-flight version isolates that version's snapshot")
			@Test
			void shouldIsolatePerVersionCountWhileInFlight() {
				runWithIndex((offsetIndex, decoder) -> {
					// three committed versions adding keys 1, 2 and 3, with key 1 overwritten at v3, no flush
					offsetIndex.put(1L, bodyPartWithLocale(1, 1, Locale.ENGLISH));
					offsetIndex.put(2L, bodyPartWithLocale(1, 2, Locale.GERMAN));
					offsetIndex.put(3L, bodyPartWithLocale(1, 3, AVAILABLE_LOCALES[3]));
					offsetIndex.put(3L, bodyPartWithLocale(3, 1, Locale.FRENCH));

					// count at an in-flight version reflects exactly that version's live set (the v3 overwrite is
					// not a new key, so v3 still holds three keys)
					assertEquals(1, offsetIndex.count(1L), "count at v1 sees only key 1");
					assertEquals(2, offsetIndex.count(2L), "count at v2 sees keys 1 and 2");
					assertEquals(3, offsetIndex.count(3L), "count at v3 sees keys 1, 2 and 3");
				});
			}

			@DisplayName("a flushed record removed at one version and re-added at the next survives the batch")
			@Test
			void shouldPromoteRemovalAndReAddOfFlushedRecordInOneBatch() {
				runWithIndex((offsetIndex, decoder) -> {
					final EntityBodyStoragePart original = bodyPartWithLocale(1, 1, Locale.ENGLISH);
					final EntityBodyStoragePart reAdded = bodyPartWithLocale(3, 1, Locale.FRENCH);

					// key 1 is published by its own flush, then dropped at v2 and re-created at v3 - both
					// promoted by one flush. The re-add is a creation, not an overwrite: by the time v3 is
					// applied, v2 has already taken the key out of the root the batch is folding into.
					offsetIndex.put(1L, original);
					offsetIndex.flush(1L);
					offsetIndex.remove(2L, 1, EntityBodyStoragePart.class);
					offsetIndex.put(3L, reAdded);
					offsetIndex.flush(3L);

					assertEquals(reAdded, offsetIndex.get(3L, 1, EntityBodyStoragePart.class),
						"get key 1 at v3 resolves the re-added payload");
					assertTrue(offsetIndex.contains(3L, 1, EntityBodyStoragePart.class),
						"contains key 1 at v3 agrees with get");
					assertFalse(offsetIndex.contains(2L, 1, EntityBodyStoragePart.class),
						"key 1 stays absent at v2, where it was removed");
					assertEquals(original, offsetIndex.get(1L, 1, EntityBodyStoragePart.class),
						"get key 1 at v1 still resolves the payload flushed there");
					assertEquals(1, offsetIndex.count(1L), "count at v1 sees key 1");
					assertEquals(0, offsetIndex.count(2L), "count at v2 sees no key");
					assertEquals(1, offsetIndex.count(3L), "count at v3 sees the re-added key 1");
				});
			}

			@DisplayName("an in-flight record removed and re-added inside one later version survives the batch")
			@Test
			void shouldPromoteRemovalAndReAddOfInFlightRecordWithinOneVersion() {
				runWithIndex((offsetIndex, decoder) -> {
					final EntityBodyStoragePart original = bodyPartWithLocale(1, 1, Locale.ENGLISH);
					final EntityBodyStoragePart reAdded = bodyPartWithLocale(2, 1, Locale.GERMAN);

					// key 1 is created at v1 but never flushed on its own; v2 drops and immediately re-creates
					// it, folding both into a single entry. That entry is an overwrite, not a creation: v1 puts
					// the key into the root first when the whole batch is promoted by one flush.
					offsetIndex.put(1L, original);
					offsetIndex.remove(2L, 1, EntityBodyStoragePart.class);
					offsetIndex.put(2L, reAdded);
					offsetIndex.flush(2L);

					assertEquals(reAdded, offsetIndex.get(2L, 1, EntityBodyStoragePart.class),
						"get key 1 at v2 resolves the re-added payload");
					assertTrue(offsetIndex.contains(2L, 1, EntityBodyStoragePart.class),
						"contains key 1 at v2 agrees with get");
					assertEquals(original, offsetIndex.get(1L, 1, EntityBodyStoragePart.class),
						"get key 1 at v1 still resolves the payload written there");
					assertEquals(1, offsetIndex.count(1L), "count at v1 sees key 1");
					assertEquals(1, offsetIndex.count(2L), "count at v2 sees the re-added key 1");
				});
			}
		}

		/**
		 * Current-state reads filtered by the per-record generation id: a record first written at a later
		 * version must be invisible to reads at earlier versions, while reads at versions newer than the
		 * current key catalog version resolve to the latest value.
		 */
		@Nested
		@DisplayName("Current state with generation filter")
		class CurrentStateGenerationFilter {

			@DisplayName("a record first written at v5 is invisible at an earlier version v3")
			@Test
			void shouldHideRecordFromVersionPrecedingItsGeneration() {
				runWithIndex((offsetIndex, decoder) -> {
					final EntityBodyStoragePart late = bodyPartWithLocale(1, 1, Locale.ENGLISH);

					offsetIndex.put(5L, late);
					offsetIndex.flush(5L);

					assertNull(offsetIndex.get(3L, 1, EntityBodyStoragePart.class),
						"get at v3 should be null for a record first written at v5");
					assertFalse(offsetIndex.contains(3L, 1, EntityBodyStoragePart.class),
						"contains at v3 should be false for a record first written at v5");
				});
			}

			@DisplayName("a read at a version newer than the current state resolves to the latest value")
			@Test
			void shouldResolveLatestValueAtFutureVersion() {
				runWithIndex((offsetIndex, decoder) -> {
					final EntityBodyStoragePart latest = bodyPartWithLocale(1, 1, Locale.ENGLISH);

					offsetIndex.put(5L, latest);
					offsetIndex.flush(5L);

					assertEquals(latest, offsetIndex.get(9L, 1, EntityBodyStoragePart.class),
						"get at a future version should resolve the latest value");
					assertTrue(offsetIndex.contains(9L, 1, EntityBodyStoragePart.class),
						"contains at a future version should be true for the latest value");
					assertEquals(1, offsetIndex.count(9L),
						"count at a future version should match the current state");
				});
			}
		}

		/**
		 * Count and per-type statistics resolved across all tiers and at versions that were never flushed.
		 */
		@Nested
		@DisplayName("Counts and statistics across tiers")
		class CountsAcrossTiers {

			@DisplayName("count and per-type count resolve at current, historical, future and gap versions")
			@Test
			void shouldResolveCountsAcrossAllTiers() {
				runWithIndex((offsetIndex, decoder) -> {
					// v1: one body part
					offsetIndex.put(1L, bodyPartWithLocale(1, 1, Locale.ENGLISH));
					offsetIndex.flush(1L);
					// v2: a second body part plus a references part sharing a primary key with neither
					offsetIndex.put(2L, bodyPartWithLocale(1, 2, Locale.GERMAN));
					offsetIndex.put(2L, new ReferencesStoragePart(8));
					offsetIndex.flush(2L);
					// gap at v3 (no writes)
					// v4: remove the first body part
					offsetIndex.remove(4L, 1, EntityBodyStoragePart.class);
					offsetIndex.flush(4L);

					// total counts per version
					assertEquals(1, offsetIndex.count(1L), "total count at v1");
					assertEquals(3, offsetIndex.count(2L), "total count at v2");
					assertEquals(3, offsetIndex.count(3L), "total count at gap version v3 matches v2");
					assertEquals(2, offsetIndex.count(4L), "total count at v4 after one removal");
					assertEquals(2, offsetIndex.count(9L), "total count at a future version matches current");

					// per-type counts at the current version
					assertEquals(1, offsetIndex.count(4L, EntityBodyStoragePart.class),
						"body part count at v4 after one removal");
					assertEquals(1, offsetIndex.count(4L, ReferencesStoragePart.class),
						"references part count at v4");

					// per-type counts at historical versions
					assertEquals(1, offsetIndex.count(1L, EntityBodyStoragePart.class),
						"body part count at v1");
					assertEquals(0, offsetIndex.count(1L, ReferencesStoragePart.class),
						"references part count at v1 before it was added");
					assertEquals(2, offsetIndex.count(2L, EntityBodyStoragePart.class),
						"body part count at v2");
					assertEquals(1, offsetIndex.count(2L, ReferencesStoragePart.class),
						"references part count at v2");
				});
			}

			@DisplayName("count isolates each version's snapshot with pending non-flushed changes")
			@Test
			void shouldIsolatePerVersionCountWithPendingChangesBeforeFlush() {
				runWithIndex((offsetIndex, decoder) -> {
					offsetIndex.put(1L, bodyPartWithLocale(1, 1, Locale.ENGLISH));
					offsetIndex.flush(1L);

					// pending additions across two uncommitted versions, no flush
					offsetIndex.put(2L, bodyPartWithLocale(1, 2, Locale.GERMAN));
					offsetIndex.put(3L, bodyPartWithLocale(1, 3, Locale.FRENCH));

					// each version's count reflects exactly the keys visible at that version: the flushed base
					// plus the pending additions up to and including that version
					assertEquals(1, offsetIndex.count(1L),
						"count at v1 sees only the flushed record");
					assertEquals(2, offsetIndex.count(2L),
						"count at v2 sees the flushed record plus the v2 addition");
					assertEquals(3, offsetIndex.count(3L),
						"count at v3 sees the flushed record plus the v2 and v3 additions");
				});
			}

			@DisplayName("count() should return the correct value at a catalog version that was never flushed")
			@Test
			void shouldReturnCorrectCountAtUnflushedCatalogVersionBetweenHistoricalVersions() {
				final StorageSettings storageSettings = new StorageSettings(
					StorageOptions.temporary(),
					DEFAULT_TRANSACTION_OPTIONS
				);
				try (final ObservableOutputKeeper observableOutputKeeper = createMockedObservableOutputKeeper()) {
					final OffsetIndex offsetIndex = createNewOffsetIndex(
						0L,
						storageSettings,
						createWriteOnlyFileHandle(OffsetIndexTest.this.targetFile, storageSettings, observableOutputKeeper),
						OffsetIndexTest.this.offsetIndexRecordTypeRegistry
					);
					try {
						// version 1: insert R1, flush
						offsetIndex.put(1L, new EntityBodyStoragePart(1));
						offsetIndex.flush(1L);
						// version 2: insert R2, flush
						offsetIndex.put(2L, new EntityBodyStoragePart(2));
						offsetIndex.flush(2L);
						// no activity at version 3 — create a gap in historicalVersions
						// version 4: insert R4, flush
						offsetIndex.put(4L, new EntityBodyStoragePart(4));
						offsetIndex.flush(4L);

						// sanity: count at each recorded version
						assertEquals(1, offsetIndex.count(1L), "count at v1 should see only R1");
						assertEquals(2, offsetIndex.count(2L), "count at v2 should see R1, R2");
						assertEquals(3, offsetIndex.count(4L), "count at v4 should see R1, R2, R4");

						// the bug: querying the gap version 3 incorrectly returned 3 (the current keyToLocations size)
						// because the binary search insertion point was excluded from the diff-subtraction loop
						assertEquals(
							2,
							offsetIndex.count(3L),
							"count at gap version 3 should match count at v2 (no records were added at v3)"
						);
					} finally {
						IOUtils.closeQuietly(offsetIndex::close);
					}
				}
			}

			@DisplayName("count() should return the correct value at a catalog version older than every historical entry")
			@Test
			void shouldReturnCorrectCountAtCatalogVersionPrecedingAllHistoricalVersions() {
				final StorageSettings storageSettings = new StorageSettings(
					StorageOptions.temporary(),
					DEFAULT_TRANSACTION_OPTIONS
				);
				try (final ObservableOutputKeeper observableOutputKeeper = createMockedObservableOutputKeeper()) {
					final OffsetIndex offsetIndex = createNewOffsetIndex(
						0L,
						storageSettings,
						createWriteOnlyFileHandle(OffsetIndexTest.this.targetFile, storageSettings, observableOutputKeeper),
						OffsetIndexTest.this.offsetIndexRecordTypeRegistry
					);
					try {
						// only one flushed version — simulates the state right after compaction discards older
						// historicalVersions while keyToLocations still holds records
						offsetIndex.put(5L, new EntityBodyStoragePart(1));
						offsetIndex.put(5L, new EntityBodyStoragePart(2));
						offsetIndex.flush(5L);

						// querying any catalog version preceding hv[0]=5 should subtract every diff and return
						// the state that existed before flush(5) — empty
						assertEquals(0, offsetIndex.count(3L), "count at v3 should be 0 (nothing was added before v5)");
						assertEquals(0, offsetIndex.count(4L), "count at v4 should be 0 (nothing was added before v5)");
						assertEquals(2, offsetIndex.count(5L), "count at v5 should see both records");
					} finally {
						IOUtils.closeQuietly(offsetIndex::close);
					}
				}
			}
		}

		/**
		 * Snapshot immutability: a captured past version must keep returning a stable, consistent view no
		 * matter how many later mutations and flushes are applied.
		 */
		@Nested
		@DisplayName("Snapshot immutability")
		class SnapshotImmutability {

			@DisplayName("a captured past version stays stable across many later put/remove/flush cycles")
			@Test
			void shouldKeepPastVersionStableAcrossManyLaterCycles() {
				runWithIndex((offsetIndex, decoder) -> {
					final EntityBodyStoragePart keptR1 = bodyPartWithLocale(1, 1, Locale.ENGLISH);
					final EntityBodyStoragePart keptR2 = bodyPartWithLocale(1, 2, Locale.GERMAN);

					// snapshot version v1 holds exactly keys 1 and 2
					offsetIndex.put(1L, keptR1);
					offsetIndex.put(1L, keptR2);
					offsetIndex.flush(1L);

					// many later mutations that overwrite, remove and add unrelated records
					long version = 1L;
					for (int cycle = 0; cycle < 12; cycle++) {
						version++;
						final int newKey = 100 + cycle;
						offsetIndex.put(version, bodyPartWithLocale(cycle + 2, 1, Locale.FRENCH));
						offsetIndex.put(version, bodyPartWithLocale(cycle + 2, newKey, AVAILABLE_LOCALES[3]));
						if (cycle % 2 == 0) {
							offsetIndex.remove(version, 2, EntityBodyStoragePart.class);
						} else {
							offsetIndex.put(version, keptR2);
						}
						offsetIndex.flush(version);

						// the v1 snapshot must remain stable throughout
						assertEquals(keptR1, offsetIndex.get(1L, 1, EntityBodyStoragePart.class),
							"key 1 at v1 should remain stable in cycle " + cycle);
						assertEquals(keptR2, offsetIndex.get(1L, 2, EntityBodyStoragePart.class),
							"key 2 at v1 should remain stable in cycle " + cycle);
						assertTrue(offsetIndex.contains(1L, 1, EntityBodyStoragePart.class),
							"key 1 at v1 should remain present in cycle " + cycle);
						assertTrue(offsetIndex.contains(1L, 2, EntityBodyStoragePart.class),
							"key 2 at v1 should remain present in cycle " + cycle);
						assertEquals(2, offsetIndex.count(1L),
							"count at v1 should remain 2 in cycle " + cycle);
					}
				});
			}
		}

		/**
		 * Multiple record types sharing a primary key resolve independently per (type, primary key).
		 */
		@Nested
		@DisplayName("Multiple record types sharing a primary key")
		class MultipleRecordTypes {

			@DisplayName("two types with the same primary key resolve independently across versions")
			@Test
			void shouldResolveDistinctTypesIndependentlyForSamePrimaryKey() {
				runWithIndex((offsetIndex, decoder) -> {
					final EntityBodyStoragePart body = bodyPartWithLocale(1, 1, Locale.ENGLISH);
					final ReferencesStoragePart references = new ReferencesStoragePart(1);

					// v1: only the body part for key 1
					offsetIndex.put(1L, body);
					offsetIndex.flush(1L);
					// v2: add the references part for the same key 1
					offsetIndex.put(2L, references);
					offsetIndex.flush(2L);
					// v3: remove only the body part, leave the references part
					offsetIndex.remove(3L, 1, EntityBodyStoragePart.class);
					offsetIndex.flush(3L);

					// v1: body present, references absent
					assertEquals(body, offsetIndex.get(1L, 1, EntityBodyStoragePart.class),
						"body part present at v1");
					assertFalse(offsetIndex.contains(1L, 1, ReferencesStoragePart.class),
						"references part absent at v1");

					// v2: both types present and independent
					assertEquals(body, offsetIndex.get(2L, 1, EntityBodyStoragePart.class),
						"body part present at v2");
					assertEquals(references, offsetIndex.get(2L, 1, ReferencesStoragePart.class),
						"references part present at v2");

					// v3: body removed, references untouched
					assertNull(offsetIndex.get(3L, 1, EntityBodyStoragePart.class),
						"body part removed at v3");
					assertFalse(offsetIndex.contains(3L, 1, EntityBodyStoragePart.class),
						"body part absent at v3");
					assertEquals(references, offsetIndex.get(3L, 1, ReferencesStoragePart.class),
						"references part still present at v3");
					assertTrue(offsetIndex.contains(3L, 1, ReferencesStoragePart.class),
						"references part still contained at v3");
				});
			}
		}

		/**
		 * Reload-from-disk parity: the current state visible after a sequence of flushes must survive a
		 * fresh load from the persisted descriptor. Historical pre-reload versions are not expected to be
		 * reconstructed and are therefore not asserted.
		 */
		@Nested
		@DisplayName("Reload-from-disk parity")
		class ReloadParity {

			@DisplayName("current-version reads and counts match after loading a fresh index from disk")
			@Test
			void shouldMatchCurrentStateAfterReloadFromDisk() {
				final StorageSettings storageSettings = new StorageSettings(
					StorageOptions.temporary(),
					DEFAULT_TRANSACTION_OPTIONS
				);
				try (final ObservableOutputKeeper observableOutputKeeper = createMockedObservableOutputKeeper()) {
					final EntityBodyStoragePart bodyKept = bodyPartWithLocale(2, 1, Locale.GERMAN);
					final EntityBodyStoragePart bodyAdded = bodyPartWithLocale(1, 3, Locale.FRENCH);
					final ReferencesStoragePart references = new ReferencesStoragePart(1);
					OffsetIndexDescriptor descriptor = null;

					final OffsetIndex offsetIndex = createNewOffsetIndex(
						0L,
						storageSettings,
						createWriteOnlyFileHandle(OffsetIndexTest.this.targetFile, storageSettings, observableOutputKeeper),
						OffsetIndexTest.this.offsetIndexRecordTypeRegistry
					);
					try {
						offsetIndex.put(1L, bodyPartWithLocale(1, 1, Locale.ENGLISH));
						offsetIndex.put(1L, bodyPartWithLocale(1, 2, Locale.ENGLISH));
						offsetIndex.flush(1L);
						offsetIndex.put(2L, bodyKept);
						offsetIndex.put(2L, references);
						offsetIndex.remove(2L, 2, EntityBodyStoragePart.class);
						offsetIndex.put(2L, bodyAdded);
						descriptor = offsetIndex.flush(2L);
					} finally {
						IOUtils.closeQuietly(offsetIndex::close);
					}

					OffsetIndex reloaded = null;
					try {
						reloaded = loadOffsetIndex(
							2L,
							descriptor,
							storageSettings,
							createWriteOnlyFileHandle(OffsetIndexTest.this.targetFile, storageSettings, observableOutputKeeper),
							OffsetIndexTest.this.offsetIndexRecordTypeRegistry
						);

						assertEquals(3, reloaded.count(2L),
							"reloaded total count should match the pre-reload current state");
						assertEquals(2, reloaded.count(2L, EntityBodyStoragePart.class),
							"reloaded body part count should match the pre-reload current state");
						assertEquals(1, reloaded.count(2L, ReferencesStoragePart.class),
							"reloaded references part count should match the pre-reload current state");
						assertEquals(bodyKept, reloaded.get(2L, 1, EntityBodyStoragePart.class),
							"reloaded key 1 body part should match");
						assertEquals(references, reloaded.get(2L, 1, ReferencesStoragePart.class),
							"reloaded key 1 references part should match");
						assertEquals(bodyAdded, reloaded.get(2L, 3, EntityBodyStoragePart.class),
							"reloaded key 3 body part should match");
						assertNull(reloaded.get(2L, 2, EntityBodyStoragePart.class),
							"reloaded removed key 2 should be absent");
						assertFalse(reloaded.contains(2L, 2, EntityBodyStoragePart.class),
							"reloaded removed key 2 should not be contained");
					} finally {
						if (reloaded != null) {
							IOUtils.closeQuietly(reloaded::close);
						}
					}
				}
			}
		}

		/**
		 * Boundary cases: an empty index, lookups for never-existent keys, and empty flushes that advance
		 * the version while preserving the existing reads.
		 */
		@Nested
		@DisplayName("Boundary cases")
		class Boundaries {

			@DisplayName("an empty index returns null, false and zero for reads and counts")
			@Test
			void shouldReturnEmptyResultsForEmptyIndex() {
				runWithIndex((offsetIndex, decoder) -> {
					assertNull(offsetIndex.get(0L, 1, EntityBodyStoragePart.class),
						"get on an empty index should be null");
					assertNull(offsetIndex.getBinary(0L, 1, EntityBodyStoragePart.class),
						"getBinary on an empty index should be null");
					assertFalse(offsetIndex.contains(0L, 1, EntityBodyStoragePart.class),
						"contains on an empty index should be false");
					assertEquals(0, offsetIndex.count(0L),
						"count on an empty index should be zero");
					assertEquals(0, offsetIndex.count(0L, EntityBodyStoragePart.class),
						"per-type count on an empty index should be zero");
					assertTrue(offsetIndex.getEntries().isEmpty(),
						"entries on an empty index should be empty");
				});
			}

			@DisplayName("a lookup for a never-existent key returns null and false")
			@Test
			void shouldReturnNullForNeverExistentKey() {
				runWithIndex((offsetIndex, decoder) -> {
					offsetIndex.put(1L, bodyPartWithLocale(1, 1, Locale.ENGLISH));
					offsetIndex.flush(1L);

					assertNull(offsetIndex.get(1L, 999, EntityBodyStoragePart.class),
						"get for a never-existent key should be null");
					assertFalse(offsetIndex.contains(1L, 999, EntityBodyStoragePart.class),
						"contains for a never-existent key should be false");
				});
			}

			@DisplayName("an empty flush advances the version while preserving reads and counts")
			@Test
			void shouldPreserveReadsAcrossEmptyFlush() {
				runWithIndex((offsetIndex, decoder) -> {
					final EntityBodyStoragePart r1 = bodyPartWithLocale(1, 1, Locale.ENGLISH);

					offsetIndex.put(1L, r1);
					offsetIndex.flush(1L);
					// empty flush advancing the version with no writes
					offsetIndex.flush(2L);

					assertEquals(r1, offsetIndex.get(1L, 1, EntityBodyStoragePart.class),
						"get at v1 should still return the record after an empty flush");
					assertEquals(r1, offsetIndex.get(2L, 1, EntityBodyStoragePart.class),
						"get at the advanced version should resolve the record from current state");
					assertEquals(1, offsetIndex.count(2L),
						"count at the advanced version should still see the record");
				});
			}

			@DisplayName("flush() with no intervening writes should advance the key catalog version")
			@Test
			void shouldAdvanceKeyCatalogVersionOnEmptyFlush() {
				final StorageSettings storageSettings = new StorageSettings(
					StorageOptions.temporary(),
					DEFAULT_TRANSACTION_OPTIONS
				);
				try (final ObservableOutputKeeper observableOutputKeeper = createMockedObservableOutputKeeper()) {
					final OffsetIndex offsetIndex = createNewOffsetIndex(
						0L,
						storageSettings,
						createWriteOnlyFileHandle(OffsetIndexTest.this.targetFile, storageSettings, observableOutputKeeper),
						OffsetIndexTest.this.offsetIndexRecordTypeRegistry
					);
					try {
						final EntityBodyStoragePart r1 = new EntityBodyStoragePart(1);
						// version 1: insert R1, flush
						offsetIndex.put(1L, r1);
						offsetIndex.flush(1L);
						// version 2: empty flush — advances the key catalog version with no writes
						offsetIndex.flush(2L);

						assertEquals(
							r1,
							offsetIndex.get(1L, 1, EntityBodyStoragePart.class),
							"get at v1 should still return R1 after an empty flush"
						);
						assertEquals(
							r1,
							offsetIndex.get(2L, 1, EntityBodyStoragePart.class),
							"get at the advanced version should resolve R1 from current state"
						);
						assertEquals(1, offsetIndex.count(2L), "count at v2 should still see R1");
					} finally {
						IOUtils.closeQuietly(offsetIndex::close);
					}
				}
			}
		}

		/**
		 * History purge: releasing versions a catalog no longer references must never disturb the versions
		 * that are still retained, nor the current state. Reads at a released version degrade gracefully
		 * (they resolve to the oldest still-retained snapshot) rather than throwing.
		 */
		@Nested
		@DisplayName("History purge")
		class HistoryPurge {

			@DisplayName("purging old versions preserves the retained and current snapshots")
			@Test
			void shouldPreserveRetainedAndCurrentSnapshotsAfterPurge() {
				runWithIndex((offsetIndex, decoder) -> {
					final EntityBodyStoragePart v1 = bodyPartWithLocale(1, 1, Locale.ENGLISH);
					final EntityBodyStoragePart v2 = bodyPartWithLocale(2, 1, Locale.GERMAN);
					final EntityBodyStoragePart v3 = bodyPartWithLocale(3, 1, Locale.FRENCH);

					// key 1 rewritten across three flushed versions
					offsetIndex.put(1L, v1);
					offsetIndex.flush(1L);
					offsetIndex.put(2L, v2);
					offsetIndex.flush(2L);
					offsetIndex.put(3L, v3);
					offsetIndex.flush(3L);

					// sanity: every version resolves before the purge
					assertEquals(v1, offsetIndex.get(1L, 1, EntityBodyStoragePart.class), "v1 before purge");
					assertEquals(v2, offsetIndex.get(2L, 1, EntityBodyStoragePart.class), "v2 before purge");
					assertEquals(v3, offsetIndex.get(3L, 1, EntityBodyStoragePart.class), "v3 before purge");

					// a catalog with no client below v3 releases everything up to v2; the release is applied
					// on the next flush
					offsetIndex.purge(2L);
					offsetIndex.put(4L, bodyPartWithLocale(4, 2, Locale.ENGLISH));
					offsetIndex.flush(4L);

					// the retained version and the current state must resolve exactly - purge must never
					// corrupt data that is still reachable
					assertEquals(v3, offsetIndex.get(3L, 1, EntityBodyStoragePart.class),
						"v3 must still resolve exactly after purge");
					assertEquals(v3, offsetIndex.get(4L, 1, EntityBodyStoragePart.class),
						"key 1 at the current version resolves its latest payload");
					assertTrue(offsetIndex.contains(4L, 2, EntityBodyStoragePart.class),
						"the key added at the current version is present");
					assertEquals(2, offsetIndex.count(4L),
						"count at the current version is exact after purge");
					assertEquals(1, offsetIndex.count(3L),
						"count at the retained version is exact after purge");

					// reads at a released version must degrade gracefully (no exception); the exact result is
					// not contractually guaranteed once a version has been released
					assertDoesNotThrow(() -> offsetIndex.get(1L, 1, EntityBodyStoragePart.class),
						"reading a released version must not throw");
					assertDoesNotThrow(() -> offsetIndex.count(1L),
						"counting at a released version must not throw");
				});
			}

			@DisplayName("purging below the oldest version leaves every snapshot intact")
			@Test
			void shouldLeaveAllSnapshotsIntactWhenPurgingBelowOldest() {
				runWithIndex((offsetIndex, decoder) -> {
					final EntityBodyStoragePart v1 = bodyPartWithLocale(1, 1, Locale.ENGLISH);
					final EntityBodyStoragePart v2 = bodyPartWithLocale(2, 1, Locale.GERMAN);

					offsetIndex.put(1L, v1);
					offsetIndex.flush(1L);
					offsetIndex.put(2L, v2);
					offsetIndex.flush(2L);

					// the release request is below every retained version, so nothing is dropped
					offsetIndex.purge(0L);
					offsetIndex.flush(3L);

					assertEquals(v1, offsetIndex.get(1L, 1, EntityBodyStoragePart.class),
						"v1 intact after a no-op purge");
					assertEquals(v2, offsetIndex.get(2L, 1, EntityBodyStoragePart.class),
						"v2 intact after a no-op purge");
				});
			}

			@DisplayName("two releases between flushes retain only the highest released version")
			@Test
			void shouldRetainOnlyHighestReleasedVersionAfterMultiPurge() {
				runWithIndex((offsetIndex, decoder) -> {
					final EntityBodyStoragePart v5 = bodyPartWithLocale(5, 1, Locale.ENGLISH);

					// key 1 rewritten across five separately flushed versions, each retaining its own snapshot
					offsetIndex.put(1L, bodyPartWithLocale(1, 1, Locale.ENGLISH));
					offsetIndex.flush(1L);
					offsetIndex.put(2L, bodyPartWithLocale(2, 1, Locale.GERMAN));
					offsetIndex.flush(2L);
					offsetIndex.put(3L, bodyPartWithLocale(3, 1, Locale.FRENCH));
					offsetIndex.flush(3L);
					offsetIndex.put(4L, bodyPartWithLocale(4, 1, AVAILABLE_LOCALES[3]));
					offsetIndex.flush(4L);
					offsetIndex.put(5L, v5);
					offsetIndex.flush(5L);

					// every client has released through v4: a release of v2 followed by a release of v4 between
					// value-flushes; the next promotion drops every version up to and including the HIGHEST
					// released version (v4), keeping only v5 and later
					offsetIndex.purge(2L);
					offsetIndex.purge(4L);
					// a value-flush at v6 triggers the deferred purge under the serialized writer
					offsetIndex.put(6L, bodyPartWithLocale(6, 2, Locale.GERMAN));
					offsetIndex.flush(6L);

					// released versions (<= v4) are dropped, so reads at v3/v4 clamp to the oldest retained
					// snapshot (v5) rather than resolving their own released payloads
					assertEquals(v5, offsetIndex.get(3L, 1, EntityBodyStoragePart.class),
						"v3 is released, so the read floors to the oldest retained snapshot v5");
					assertEquals(v5, offsetIndex.get(4L, 1, EntityBodyStoragePart.class),
						"v4 is released, so the read floors to the oldest retained snapshot v5");
				});
			}
		}

		/**
		 * Per-version entries snapshot: {@link OffsetIndex#getEntries(long)} must expose exactly the live
		 * (record key, file location) set as of the queried version, resolved through the versioned-root
		 * registry with per-version precision and a graceful floor for gap versions.
		 */
		@Nested
		@DisplayName("Per-version entries snapshot")
		class PerVersionEntries {

			@DisplayName("getEntries at the current version exposes exactly the live key set")
			@Test
			void shouldResolveEntriesAtCurrentVersion() {
				runWithIndex((offsetIndex, decoder) -> {
					// v1: add keys 1 and 2, flush
					offsetIndex.put(1L, bodyPartWithLocale(1, 1, Locale.ENGLISH));
					offsetIndex.put(1L, bodyPartWithLocale(1, 2, Locale.GERMAN));
					offsetIndex.flush(1L);
					// v2: add key 3, remove key 2, flush
					offsetIndex.put(2L, bodyPartWithLocale(1, 3, Locale.FRENCH));
					offsetIndex.remove(2L, 2, EntityBodyStoragePart.class);
					offsetIndex.flush(2L);

					final long currentVersion = 2L;
					final Collection<Entry<RecordKey, FileLocation>> entries =
						offsetIndex.getEntries(currentVersion);

					final byte bodyType =
						OffsetIndexTest.this.offsetIndexRecordTypeRegistry.idFor(EntityBodyStoragePart.class);
					final Set<RecordKey> liveKeys = collectKeys(entries);
					assertEquals(
						Set.of(new RecordKey(bodyType, 1), new RecordKey(bodyType, 3)),
						liveKeys,
						"entries at the current version expose exactly the live keys 1 and 3"
					);

					// the entries collection agrees with the count and with per-key lookups at the same version
					assertEquals(offsetIndex.count(currentVersion), entries.size(),
						"entries size matches count at the current version");
					assertNotNull(offsetIndex.get(currentVersion, 1, EntityBodyStoragePart.class),
						"key 1 from the entries set resolves via get");
					assertNotNull(offsetIndex.get(currentVersion, 3, EntityBodyStoragePart.class),
						"key 3 from the entries set resolves via get");
					assertNull(offsetIndex.get(currentVersion, 2, EntityBodyStoragePart.class),
						"removed key 2 is absent from both entries and get");
				});
			}

			@DisplayName("getEntries resolves historical versions precisely and floors gap versions")
			@Test
			void shouldResolveEntriesAtHistoricalAndGapVersion() {
				runWithIndex((offsetIndex, decoder) -> {
					final byte bodyType =
						OffsetIndexTest.this.offsetIndexRecordTypeRegistry.idFor(EntityBodyStoragePart.class);

					// v1: key 1 only
					offsetIndex.put(1L, bodyPartWithLocale(1, 1, Locale.ENGLISH));
					offsetIndex.flush(1L);
					// v2: add key 2
					offsetIndex.put(2L, bodyPartWithLocale(1, 2, Locale.GERMAN));
					offsetIndex.flush(2L);
					// gap at v3 (no writes)
					// v4: add key 3
					offsetIndex.put(4L, bodyPartWithLocale(1, 3, Locale.FRENCH));
					offsetIndex.flush(4L);

					assertEquals(
						Set.of(new RecordKey(bodyType, 1)),
						collectKeys(offsetIndex.getEntries(1L)),
						"entries at v1 expose only key 1"
					);
					final Set<RecordKey> v2Keys =
						Set.of(new RecordKey(bodyType, 1), new RecordKey(bodyType, 2));
					assertEquals(v2Keys, collectKeys(offsetIndex.getEntries(2L)),
						"entries at v2 expose keys 1 and 2");
					// the gap version v3 floors to the v2 snapshot
					assertEquals(v2Keys, collectKeys(offsetIndex.getEntries(3L)),
						"entries at the gap version v3 floor to the v2 snapshot");
				});
			}

			@DisplayName("the versioned getEntries collection rejects mutation")
			@Test
			void shouldReturnUnmodifiableEntriesFromVersionedOverload() {
				runWithIndex((offsetIndex, decoder) -> {
					offsetIndex.put(1L, bodyPartWithLocale(1, 1, Locale.ENGLISH));
					offsetIndex.flush(1L);

					final Collection<Entry<RecordKey, FileLocation>> entries = offsetIndex.getEntries(1L);
					assertFalse(entries.isEmpty(), "the snapshot is not empty");

					final Entry<RecordKey, FileLocation> firstEntry = entries.iterator().next();
					assertThrows(
						UnsupportedOperationException.class,
						() -> firstEntry.setValue(firstEntry.getValue()),
						"Entry.setValue on the published snapshot must be rejected"
					);
					assertThrows(
						UnsupportedOperationException.class,
						entries::clear,
						"clearing the published snapshot must be rejected"
					);
				});
			}
		}

		/**
		 * Oldest-retained timestamp observability: {@link OffsetIndex#getOldestRecordKeptTimestamp()} is empty
		 * while only the current version is retained and present once historical versions are kept, flipping
		 * back to empty when a purge collapses retention to a single version.
		 */
		@Nested
		@DisplayName("Oldest retained timestamp observability")
		class OldestRetainedTimestamp {

			@DisplayName("the oldest retained timestamp is empty while only the current version is retained")
			@Test
			void shouldReportEmptyOldestTimestampWhenOnlyCurrentVersionRetained() {
				runWithIndex((offsetIndex, decoder) -> {
					// a fresh index retains only the current (genesis) version
					assertTrue(offsetIndex.getOldestRecordKeptTimestamp().isEmpty(),
						"a fresh index keeps no history");

					// writing and flushing at the genesis version supersedes the genesis snapshot instead of
					// appending a new one, so a single version remains and no history is kept
					offsetIndex.put(0L, bodyPartWithLocale(1, 1, Locale.ENGLISH));
					offsetIndex.flush(0L);
					assertTrue(offsetIndex.getOldestRecordKeptTimestamp().isEmpty(),
						"flushing at the genesis version keeps a single version, so no history is kept");
				});
			}

			@DisplayName("the oldest retained timestamp is present once history is retained")
			@Test
			void shouldReportPresentOldestTimestampWhenHistoryRetained() {
				runWithIndex((offsetIndex, decoder) -> {
					offsetIndex.put(1L, bodyPartWithLocale(1, 1, Locale.ENGLISH));
					offsetIndex.flush(1L);
					offsetIndex.put(2L, bodyPartWithLocale(2, 1, Locale.GERMAN));
					offsetIndex.flush(2L);

					final Optional<OffsetDateTime> oldest = offsetIndex.getOldestRecordKeptTimestamp();
					assertTrue(oldest.isPresent(),
						"two retained versions keep the oldest historical timestamp");
					assertFalse(oldest.get().isAfter(OffsetDateTime.now()),
						"the retained timestamp is not in the future");
				});
			}

			@DisplayName("the oldest retained timestamp returns to empty when a purge collapses to one version")
			@Test
			void shouldFlipOldestTimestampBackToEmptyWhenPurgeCollapsesToSingleVersion() {
				runWithIndex((offsetIndex, decoder) -> {
					offsetIndex.put(1L, bodyPartWithLocale(1, 1, Locale.ENGLISH));
					offsetIndex.flush(1L);
					offsetIndex.put(2L, bodyPartWithLocale(2, 1, Locale.GERMAN));
					offsetIndex.flush(2L);
					offsetIndex.put(3L, bodyPartWithLocale(3, 1, Locale.FRENCH));
					offsetIndex.flush(3L);

					assertTrue(offsetIndex.getOldestRecordKeptTimestamp().isPresent(),
						"history is retained across three flushed versions");

					// release everything up to v3 so the next promotion drops all but the newest version
					offsetIndex.purge(3L);
					offsetIndex.put(4L, bodyPartWithLocale(4, 2, Locale.GERMAN));
					offsetIndex.flush(4L);

					assertTrue(offsetIndex.getOldestRecordKeptTimestamp().isEmpty(),
						"collapsing retention to a single version keeps no history");
				});
			}
		}

		/**
		 * Histogram view: {@link OffsetIndex#getHistogram()} maps each record type's simple name to the exact
		 * live count of that type in the current state, and to the bytes those records occupy.
		 */
		@Nested
		@DisplayName("Histogram view")
		class HistogramView {

			@DisplayName("the histogram maps each record type's simple name to its exact live count")
			@Test
			void shouldExposeLatestHistogramByRecordTypeSimpleName() {
				runWithIndex((offsetIndex, decoder) -> {
					// v1: two body parts
					offsetIndex.put(1L, bodyPartWithLocale(1, 1, Locale.ENGLISH));
					offsetIndex.put(1L, bodyPartWithLocale(1, 2, Locale.GERMAN));
					offsetIndex.flush(1L);
					// v2: add three references parts, remove one body part
					offsetIndex.put(2L, new ReferencesStoragePart(1));
					offsetIndex.put(2L, new ReferencesStoragePart(2));
					offsetIndex.put(2L, new ReferencesStoragePart(3));
					offsetIndex.remove(2L, 2, EntityBodyStoragePart.class);
					offsetIndex.flush(2L);

					final long currentVersion = 2L;
					final Map<String, RecordTypeUsage> histogram = offsetIndex.getHistogram();

					assertEquals(
						offsetIndex.count(currentVersion, EntityBodyStoragePart.class),
						histogram.get(EntityBodyStoragePart.class.getSimpleName()).count(),
						"the histogram body part count matches the per-type count"
					);
					assertEquals(
						offsetIndex.count(currentVersion, ReferencesStoragePart.class),
						histogram.get(ReferencesStoragePart.class.getSimpleName()).count(),
						"the histogram references part count matches the per-type count"
					);
					assertEquals(1, histogram.get(EntityBodyStoragePart.class.getSimpleName()).count(),
						"one body part survives the removal");
					assertEquals(3, histogram.get(ReferencesStoragePart.class.getSimpleName()).count(),
						"three references parts are live");
				});
			}

			/**
			 * The per-type byte totals are not an independent measurement - they are accumulated at the same
			 * statements as the index-wide `totalSizeBytes`, in every branch including the count-neutral update.
			 * Summing them back to that one number is therefore the check that the pairing did not drift: drop the
			 * byte delta from any single branch of the promotion loop and this fails, while every count assertion
			 * above still passes.
			 */
			@DisplayName("the per-type byte totals sum back to the index-wide total size")
			@Test
			void shouldAccountForEveryByteInThePerTypeBreakdown() {
				runWithIndex((offsetIndex, decoder) -> {
					// v1: adds only
					offsetIndex.put(1L, bodyPartWithLocales(1, 1, Locale.ENGLISH));
					offsetIndex.put(1L, bodyPartWithLocales(1, 2, Locale.GERMAN, Locale.FRENCH, Locale.ITALIAN));
					offsetIndex.put(1L, new ReferencesStoragePart(1));
					offsetIndex.flush(1L);
					// v2: an update that grows a record, an update that shrinks one, an add and a removal - so every
					// branch of the promotion loop contributes a byte delta
					// deliberately asymmetric - a grow of two locales against a shrink of one, so dropping the byte
					// delta from the update branch cannot cancel itself out across the two records
					offsetIndex.put(2L, bodyPartWithLocales(2, 1, Locale.ENGLISH, Locale.GERMAN, Locale.FRENCH));
					offsetIndex.put(2L, bodyPartWithLocales(2, 2, Locale.GERMAN, Locale.FRENCH));
					offsetIndex.put(2L, new ReferencesStoragePart(2));
					offsetIndex.remove(2L, 1, ReferencesStoragePart.class);
					offsetIndex.flush(2L);

					final Map<String, RecordTypeUsage> histogram = offsetIndex.getHistogram();
					long summedBytes = 0L;
					int summedCount = 0;
					for (final RecordTypeUsage usage : histogram.values()) {
						assertTrue(usage.totalBytes() >= 0, "no record type may report negative bytes");
						summedBytes += usage.totalBytes();
						summedCount += usage.count();
					}

					// the per-type figures carry record payload only; the index's own per-record entry overhead belongs
					// to no storage part type, so it is added back here rather than attributed to one
					assertEquals(
						offsetIndex.getTotalSizeBytes(),
						summedBytes + (long) summedCount * MEM_TABLE_RECORD_SIZE,
						"the per-type byte totals must sum back to the index-wide total size"
					);
					assertEquals(
						offsetIndex.count(2L), summedCount,
						"the per-type counts must sum back to the index-wide record count"
					);
				});
			}

			/**
			 * A record type whose last record is removed keeps a zero entry in the histogram - the promotion loop
			 * folds signed deltas in and never drops a key. That is deliberate, because it is what lets the flush
			 * path see the count-went-to-zero transition and emit `OffsetIndexRecordTypeCountChangedEvent` for it.
			 * It is also why the statistics breakdown built on top filters zero-count types out rather than having
			 * them removed here: dropping the key would silence that metric.
			 */
			@DisplayName("a record type whose last record is removed stays in the histogram as a zero entry")
			@Test
			void shouldKeepAZeroEntryForAnEmptiedRecordType() {
				runWithIndex((offsetIndex, decoder) -> {
					offsetIndex.put(1L, bodyPartWithLocales(1, 1, Locale.ENGLISH));
					offsetIndex.put(1L, new ReferencesStoragePart(1));
					offsetIndex.flush(1L);
					offsetIndex.remove(2L, 1, ReferencesStoragePart.class);
					offsetIndex.flush(2L);

					final RecordTypeUsage emptied = offsetIndex.getHistogram()
						.get(ReferencesStoragePart.class.getSimpleName());
					assertNotNull(emptied, "The emptied record type must keep its histogram entry");
					assertEquals(0, emptied.count(), "The emptied record type must report no record");
					assertEquals(0L, emptied.totalBytes(), "The emptied record type must report no bytes");
					assertEquals(
						0, offsetIndex.count(2L, ReferencesStoragePart.class),
						"The per-type count must agree with the zeroed histogram entry"
					);
				});
			}

			/**
			 * The same reconciliation has to survive a reload, which rebuilds the histogram by scanning the file
			 * through `CollectingOffsetIndexBuilder` instead of by promoting flushes - a completely separate
			 * accumulation of the same two numbers, and the only one that runs on server start-up.
			 */
			@DisplayName("the per-type breakdown is rebuilt identically when the index is loaded from disk")
			@Test
			void shouldRebuildThePerTypeBreakdownOnReload() {
				final StorageSettings storageSettings = new StorageSettings(
					StorageOptions.temporary(),
					DEFAULT_TRANSACTION_OPTIONS
				);
				try (final ObservableOutputKeeper observableOutputKeeper = createMockedObservableOutputKeeper()) {
					final Map<String, RecordTypeUsage> beforeReload;
					final long totalSizeBeforeReload;
					OffsetIndexDescriptor descriptor = null;

					final OffsetIndex offsetIndex = createNewOffsetIndex(
						0L,
						storageSettings,
						createWriteOnlyFileHandle(OffsetIndexTest.this.targetFile, storageSettings, observableOutputKeeper),
						OffsetIndexTest.this.offsetIndexRecordTypeRegistry
					);
					try {
						offsetIndex.put(1L, bodyPartWithLocales(1, 1, Locale.ENGLISH));
						offsetIndex.put(1L, bodyPartWithLocales(1, 2, Locale.GERMAN, Locale.FRENCH));
						offsetIndex.put(1L, new ReferencesStoragePart(1));
						offsetIndex.flush(1L);
						offsetIndex.put(2L, bodyPartWithLocales(2, 1, Locale.ENGLISH, Locale.ITALIAN));
						offsetIndex.remove(2L, 2, EntityBodyStoragePart.class);
						offsetIndex.flush(2L);
						beforeReload = offsetIndex.getHistogram();
						totalSizeBeforeReload = offsetIndex.getTotalSizeBytes();
						descriptor = offsetIndex.flush(2L);
					} finally {
						IOUtils.closeQuietly(offsetIndex::close);
					}

					OffsetIndex reloaded = null;
					try {
						reloaded = loadOffsetIndex(
							2L,
							descriptor,
							storageSettings,
							createWriteOnlyFileHandle(OffsetIndexTest.this.targetFile, storageSettings, observableOutputKeeper),
							OffsetIndexTest.this.offsetIndexRecordTypeRegistry
						);

						final Map<String, RecordTypeUsage> afterReload = reloaded.getHistogram();
						assertEquals(
							beforeReload, afterReload,
							"the rebuilt histogram must match the one the promotions produced, bytes included"
						);
						long summedBytes = 0L;
						int summedCount = 0;
						for (final RecordTypeUsage usage : afterReload.values()) {
							summedBytes += usage.totalBytes();
							summedCount += usage.count();
						}
						assertEquals(
							reloaded.getTotalSizeBytes(),
							summedBytes + (long) summedCount * MEM_TABLE_RECORD_SIZE,
							"the rebuilt per-type byte totals must sum back to the index-wide total size"
						);
						assertEquals(
							totalSizeBeforeReload, reloaded.getTotalSizeBytes(),
							"reloading must not change the total size"
						);
					} finally {
						if (reloaded != null) {
							IOUtils.closeQuietly(reloaded::close);
						}
					}
				}
			}
		}

		/**
		 * History-kept observer: the historical-record observer passed at construction must be notified with
		 * the advanced oldest-retained timestamp once a purge release is applied by a promotion.
		 */
		@Nested
		@DisplayName("History-kept observer")
		class HistoryKeptObserver {

			@DisplayName("the observer is notified with the advanced oldest-retained timestamp after a purge")
			@Test
			void shouldNotifyHistoricalRecordObserverWhenPurgeAdvancesOldestRetained() {
				final StorageSettings storageSettings = new StorageSettings(
					StorageOptions.temporary(),
					DEFAULT_TRANSACTION_OPTIONS
				);
				final AtomicReference<Optional<OffsetDateTime>> observed =
					new AtomicReference<>(Optional.empty());
				try (final ObservableOutputKeeper observableOutputKeeper = createMockedObservableOutputKeeper()) {
					final OffsetIndex offsetIndex = createNewOffsetIndexWithObserver(
						0L,
						storageSettings,
						createWriteOnlyFileHandle(
							OffsetIndexTest.this.targetFile, storageSettings, observableOutputKeeper),
						OffsetIndexTest.this.offsetIndexRecordTypeRegistry,
						observed::set
					);
					try {
						// build at least three flushed versions so history is retained
						offsetIndex.put(1L, bodyPartWithLocale(1, 1, Locale.ENGLISH));
						offsetIndex.flush(1L);
						offsetIndex.put(2L, bodyPartWithLocale(2, 1, Locale.GERMAN));
						offsetIndex.flush(2L);
						offsetIndex.put(3L, bodyPartWithLocale(3, 1, Locale.FRENCH));
						offsetIndex.flush(3L);

						// release the oldest version and let the next promotion advance the retained window
						offsetIndex.purge(1L);
						offsetIndex.put(4L, bodyPartWithLocale(4, 2, Locale.GERMAN));
						offsetIndex.flush(4L);

						// the observer reports the advanced oldest-retained timestamp, consistent with the getter
						final Optional<OffsetDateTime> notified = observed.get();
						assertTrue(notified.isPresent(),
							"the observer was notified with a present oldest-retained timestamp after the purge");
						assertEquals(offsetIndex.getOldestRecordKeptTimestamp(), notified,
							"the observed timestamp is consistent with getOldestRecordKeptTimestamp");
					} finally {
						IOUtils.closeQuietly(offsetIndex::close);
					}
				}
			}
		}

		/**
		 * Collects the record keys exposed by a versioned {@link OffsetIndex#getEntries(long)} snapshot into a
		 * set for order-independent comparison against the expected live key set.
		 *
		 * @param entries the published per-version entries snapshot
		 * @return the set of record keys contained in the snapshot
		 */
		@Nonnull
		private static Set<RecordKey> collectKeys(@Nonnull Collection<Entry<RecordKey, FileLocation>> entries) {
			final Set<RecordKey> keys = new HashSet<>(entries.size());
			for (final Entry<RecordKey, FileLocation> entry : entries) {
				keys.add(entry.getKey());
			}
			return keys;
		}

		/**
		 * Creates a temporary {@link OffsetIndex}, hands it together with a {@link BinaryDecoder} bound to
		 * its live key compressor to the supplied scenario, and guarantees the index is closed afterwards.
		 * The decoder lets scenarios turn `getBinary` payloads back into objects for comparison; it builds a
		 * fresh Kryo on demand so it always observes the compressor state current at the point of decoding.
		 *
		 * @param scenario the black-box scenario to run against the freshly created index
		 */
		private void runWithIndex(@Nonnull MvccScenario scenario) {
			final StorageSettings storageSettings = new StorageSettings(
				StorageOptions.temporary(),
				DEFAULT_TRANSACTION_OPTIONS
			);
			try (final ObservableOutputKeeper observableOutputKeeper = createMockedObservableOutputKeeper()) {
				final OffsetIndex offsetIndex = createNewOffsetIndex(
					0L,
					storageSettings,
					createWriteOnlyFileHandle(OffsetIndexTest.this.targetFile, storageSettings, observableOutputKeeper),
					OffsetIndexTest.this.offsetIndexRecordTypeRegistry
				);
				try {
					final BinaryDecoder decoder = binary -> {
						final VersionedKryo kryo = createKryo()
							.apply(new VersionedKryoKeyInputs(offsetIndex.getReadOnlyKeyCompressor(), 1));
						return deserialize(kryo, binary);
					};
					scenario.run(offsetIndex, decoder);
				} finally {
					IOUtils.closeQuietly(offsetIndex::close);
				}
			}
		}

		/**
		 * A black-box MVCC scenario executed against a freshly created {@link OffsetIndex}. Receives the
		 * index and a {@link BinaryDecoder} able to deserialize binary payloads for assertions.
		 */
		@FunctionalInterface
		private interface MvccScenario {

			/**
			 * Runs the scenario against the supplied index.
			 *
			 * @param offsetIndex the index under test
			 * @param decoder     a decoder turning `getBinary` payloads back into objects
			 */
			void run(@Nonnull OffsetIndex offsetIndex, @Nonnull BinaryDecoder decoder);
		}

		/**
		 * Turns a binary payload returned by {@link OffsetIndex#getBinary(long, long, Class)} back into an
		 * {@link EntityBodyStoragePart}, rebuilding the Kryo against the index's current key compressor.
		 */
		@FunctionalInterface
		private interface BinaryDecoder {

			/**
			 * Decodes the supplied binary payload.
			 *
			 * @param binary the serialized payload
			 * @return the deserialized payload
			 */
			@Nonnull
			EntityBodyStoragePart decode(@Nonnull byte[] binary);
		}
	}

	@Nonnull
	private StorageSettings buildOptionsWithLimitedBuffer(ChecksumCheck crc32Check, Compression compression) {
		return new StorageSettings(
			StorageOptions.builder()
				.storageDirectory(getTestDirectory().resolve(TEST_FOLDER))
				.waitOnCloseSeconds(5)
				.lockTimeoutSeconds(5)
				.outputBufferSize(4096)
				.maxOpenedReadHandles(Runtime.getRuntime().availableProcessors())
				.computeCRC32(crc32Check == ChecksumCheck.YES)
				.compress(compression == Compression.YES)
				.build(),
			DEFAULT_TRANSACTION_OPTIONS
		);
	}

	@Nonnull
	private InsertionOutput serializeAndReconstructBigFileOffsetIndex(
		@Nonnull StorageSettings storageSettings,
		@Nonnull IntFunction<EntityBodyStoragePart> bodyPartFactory
	) {
		try (final ObservableOutputKeeper observableOutputKeeper = createMockedObservableOutputKeeper()) {
			return shouldSerializeAndReconstructOffsetIndex(
				storageSettings, observableOutputKeeper, bodyPartFactory, 600
			);
		}
	}

	@Nonnull
	private InsertionOutput shouldSerializeAndReconstructOffsetIndex(
		@Nonnull StorageSettings storageSettings,
		@Nonnull ObservableOutputKeeper observableOutputKeeper,
		@Nonnull IntFunction<EntityBodyStoragePart> bodyPartFactory,
		int recordCount
	) {
		OffsetIndex loadedFileOffsetIndex = null;
		try (
			final WriteOnlyFileHandle writeHandle = createWriteOnlyFileHandle(
				this.targetFile, storageSettings, observableOutputKeeper
			)
		) {
			final OffsetIndex fileOffsetIndex = createNewOffsetIndex(
				0L, storageSettings, writeHandle, this.offsetIndexRecordTypeRegistry
			);

			int inserted = 0;
			final long transactionId = 0L;
			for (int i = 1; i <= recordCount; i++) {
				fileOffsetIndex.put(transactionId, bodyPartFactory.apply(i));
				inserted++;
			}

			log.info("Flushing table (" + transactionId + ")");
			final OffsetIndexDescriptor fileOffsetIndexDescriptor = fileOffsetIndex.flush(transactionId);
			loadedFileOffsetIndex = loadOffsetIndex(
				0L, fileOffsetIndexDescriptor, storageSettings, writeHandle, this.offsetIndexRecordTypeRegistry
			);

			long duration = 0L;
			for (int i = 1; i <= recordCount; i++) {
				long start = System.nanoTime();
				final EntityBodyStoragePart actual = fileOffsetIndex.get(transactionId, i, EntityBodyStoragePart.class);
				duration += System.nanoTime() - start;
				assertEquals(
					bodyPartFactory.apply(i),
					actual
				);
			}

			assertTrue(fileOffsetIndex.fileOffsetIndexEquals(loadedFileOffsetIndex));
			/* input count records +1 record for the OffsetIndex itself */
			if (storageSettings.computeCRC32C()) {
				assertEquals(
					recordCount + Math.max(
						1, computeExpectedRecordCount(recordCount, storageSettings.outputBufferSize()).fragments()),
					fileOffsetIndex.verifyContents().getRecordCount()
				);
			}
			log.info("Average reads: " + StringUtils.formatRequestsPerSec(recordCount, duration));

			return new InsertionOutput(fileOffsetIndex, fileOffsetIndexDescriptor, transactionId, inserted, 0);
		} finally {
			if (loadedFileOffsetIndex != null) {
				IOUtils.closeQuietly(loadedFileOffsetIndex::close);
			}
		}
	}

	private InsertionOutput createRecordsInFileOffsetIndex(
		@Nonnull StorageSettings storageSettings,
		@Nonnull ObservableOutputKeeper observableOutputKeeper,
		int recordCount,
		int removedRecords,
		int iterationCount
	) {
		final OffsetIndex fileOffsetIndex = createNewOffsetIndex(
			0L,
			storageSettings,
			createWriteOnlyFileHandle(this.targetFile, storageSettings, observableOutputKeeper),
			this.offsetIndexRecordTypeRegistry
		);
		return createRecordsInFileOffsetIndex(fileOffsetIndex, recordCount, removedRecords, iterationCount);
	}

	/**
	 * Builds an {@link EntityBodyStoragePart} that is distinguishable by its `version` and `locales`
	 * while sharing the supplied `primaryKey`. This lets a test overwrite a record at the same key
	 * with a payload that is not {@link Object#equals(Object) equal} to the previous one.
	 *
	 * @param version    entity version stored in the payload
	 * @param primaryKey primary key shared across overwrites of the same record
	 * @param locale     single locale used to make the payload distinguishable
	 * @return a new, immutable payload instance
	 */
	/**
	 * Same as {@link #bodyPartWithLocale(int, int, Locale)} but with a controllable number of locales, so the
	 * serialized payload has a controllable size - which is what lets a test tell a byte delta apart from a count
	 * delta when a record is overwritten.
	 *
	 * @param version    entity version stored in the payload
	 * @param primaryKey primary key shared across overwrites of the same record
	 * @param locales    locales making up the payload, and therefore its size
	 * @return a new, immutable payload instance
	 */
	@Nonnull
	private static EntityBodyStoragePart bodyPartWithLocales(int version, int primaryKey,
		@Nonnull Locale... locales) {
		return new EntityBodyStoragePart(
			version,
			primaryKey,
			Scope.LIVE,
			null,
			new HashSet<>(Arrays.asList(locales)),
			new HashSet<>(),
			new HashSet<>(),
			-1
		);
	}

	@Nonnull
	private static EntityBodyStoragePart bodyPartWithLocale(int version, int primaryKey,
		@Nonnull Locale locale) {
		return new EntityBodyStoragePart(
			version,
			primaryKey,
			Scope.LIVE,
			null,
			new HashSet<>(Set.of(locale)),
			new HashSet<>(),
			new HashSet<>(),
			-1
		);
	}

	/**
	 * Deserializes the binary payload returned by {@link OffsetIndex#getBinary(long, long, Class)}
	 * back into an {@link EntityBodyStoragePart} using the supplied configured {@link VersionedKryo}.
	 *
	 * @param kryo   configured Kryo instance bound to the index key compressor
	 * @param binary serialized payload (never `null` in the covered scenarios)
	 * @return the deserialized payload
	 */
	@Nonnull
	private static EntityBodyStoragePart deserialize(@Nonnull VersionedKryo kryo,
		@Nonnull byte[] binary) {
		return kryo.readObject(new Input(binary), EntityBodyStoragePart.class);
	}

	private enum ChecksumCheck {
		YES, NO
	}

	private enum Compression {
		YES, NO
	}

	private record InsertionOutput(
		@Nonnull OffsetIndex fileOffsetIndex,
		@Nonnull OffsetIndexDescriptor descriptor,
		long catalogVersion,
		int insertedTotal,
		int removedTotal
	) {
	}

}
