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
import io.evitadb.store.entity.EntityStoragePartConfigurer;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.offsetIndex.OffsetIndexSerializationService.FileLocationAndWrittenBytes;
import io.evitadb.store.offsetIndex.io.WriteOnlyFileHandle;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.offsetIndex.model.RecordKey;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.schema.SchemaKryoConfigurer;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.store.shared.kryo.VersionedKryoFactory;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.IOUtils;
import io.evitadb.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.evitadb.store.offsetIndex.OffsetIndexSerializationService.computeExpectedRecordCount;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

	@DisplayName("Offset index can be stored empty.")
	@ParameterizedTest
	@MethodSource("combineSettings")
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
	@MethodSource("combineSettings")
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
	@MethodSource("combineSettings")
	void shouldSerializeAndReconstructBigFileOffsetIndex(ChecksumCheck crc32Check, Compression compression) {
		final InsertionOutput insertionOutput = serializeAndReconstructBigFileOffsetIndex(
			configure(StorageOptions.temporary(), crc32Check, compression), EntityBodyStoragePart::new);
		IOUtils.closeQuietly(insertionOutput.fileOffsetIndex()::close);
	}

	@DisplayName("Half of the entities should be removed, file offset index copied to different file and reconstructed.")
	@ParameterizedTest
	@MethodSource("combineSettings")
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
				createWriteOnlyFileHandle(this.targetFile, storageSettings, observableOutputKeeper),
				this.offsetIndexRecordTypeRegistry
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
				createWriteOnlyFileHandle(this.targetFile, storageSettings, observableOutputKeeper),
				this.offsetIndexRecordTypeRegistry
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
				this.offsetIndexRecordTypeRegistry,
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

	@DisplayName("Existing record can be removed")
	@ParameterizedTest
	@MethodSource("combineSettings")
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
				createWriteOnlyFileHandle(this.targetFile, storageOptions, observableOutputKeeper),
				this.offsetIndexRecordTypeRegistry
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

	@ParameterizedTest
	@MethodSource("combineSettings")
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
				createWriteOnlyFileHandle(this.targetFile, storageSettings, observableOutputKeeper),
				this.offsetIndexRecordTypeRegistry
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

	@ParameterizedTest
	@MethodSource("combineSettings")
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
					this.offsetIndexRecordTypeRegistry.idFor(EntityBodyStoragePart.class),
					i
				);

				final Supplier<EntityBodyStoragePart> entityBodySupplier = () -> OffsetIndex.readSingleRecord(
					storageSettings,
					storageSettings,
					this.targetFile,
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

	@DisplayName("count() should return the correct value at a catalog version that was never flushed (issue #1162)")
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
				createWriteOnlyFileHandle(this.targetFile, storageSettings, observableOutputKeeper),
				this.offsetIndexRecordTypeRegistry
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

	@DisplayName("count() should return the correct value at a catalog version older than every historical entry (issue #1162)")
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
				createWriteOnlyFileHandle(this.targetFile, storageSettings, observableOutputKeeper),
				this.offsetIndexRecordTypeRegistry
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
				createWriteOnlyFileHandle(this.targetFile, storageSettings, observableOutputKeeper),
				this.offsetIndexRecordTypeRegistry
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
