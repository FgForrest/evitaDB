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

package io.evitadb.store.offsetIndex;

import com.esotericsoftware.kryo.io.Input;
import com.github.javafaker.Faker;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.StorageOptions.Builder;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.store.entity.EntityStoragePartConfigurer;
import io.evitadb.store.entity.model.entity.EntityBodyStoragePart;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoFactory;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.offsetIndex.OffsetIndex.FileOffsetIndexStatistics;
import io.evitadb.store.offsetIndex.OffsetIndexSerializationService.FileLocationAndWrittenBytes;
import io.evitadb.store.offsetIndex.io.WriteOnlyFileHandle;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.offsetIndex.model.RecordKey;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.schema.SchemaKryoConfigurer;
import io.evitadb.store.spi.model.EntityCollectionHeader;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.IOUtils;
import io.evitadb.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.support.ParameterDeclarations;
import org.mockito.Mockito;
import org.opentest4j.AssertionFailedError;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.evitadb.store.offsetIndex.OffsetIndexSerializationService.computeExpectedRecordCount;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies functionality of {@link OffsetIndex} operations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
class OffsetIndexTest implements EvitaTestSupport, TimeBoundedTestSupport {
	public static final String ENTITY_TYPE = "whatever";
	private static final Locale[] AVAILABLE_LOCALES = new Locale[]{
		Locale.ENGLISH, Locale.FRENCH, Locale.GERMAN, new Locale("cs", "CZ")
	};
	private static final String TEST_FOLDER = "offsetIndexTest";
	private static final String TEST_FOLDER_EXPORT = "offsetIndexTest_export";
	private final Path targetFile = Path.of(System.getProperty("java.io.tmpdir") + File.separator + "fileOffsetIndex.kryo");
	private final OffsetIndexRecordTypeRegistry offsetIndexRecordTypeRegistry = new OffsetIndexRecordTypeRegistry();

	/**
	 * Generates a stream of arguments by combining all possible combinations
	 * of {@link OffsetIndexTest.Crc32Check} and {@link OffsetIndexTest.Compression} enum values.
	 *
	 * @return a {@link Stream} of {@link Arguments} containing every combination
	 * of {@link OffsetIndexTest.Crc32Check} and {@link OffsetIndexTest.Compression}.
	 */
	@Nonnull
	private static Stream<Arguments> combineSettings() {
		return Stream.of(OffsetIndexTest.Crc32Check.values())
			.flatMap(crc32Check -> Stream.of(OffsetIndexTest.Compression.values())
				.map(compression -> Arguments.of(crc32Check, compression)));
	}

	/**
	 * Custom argument provider that combines {@link TimeArgumentProvider} functionality
	 * with {@link Compression} enum values to provide parameterized test arguments
	 * for generational tests that need both time input and compression settings.
	 */
	public static class TimeAndCompressionArgumentProvider extends TimeArgumentProvider {

		@Override
		public Stream<? extends Arguments> provideArguments(ParameterDeclarations parameters, ExtensionContext context) {
			final Arguments arguments = super.provideArguments(parameters, context).findFirst().orElseThrow();
			return Stream.of(Compression.values())
				.map(compression -> Arguments.of(arguments.get()[0], compression));
		}
	}

	/**
	 * Retrieves a non-existing record primary key and generates an {@code EntityBodyStoragePart} object
	 * associated with that primary key. The method ensures that the generated primary key is unique and
	 * does not exist in the provided sets of record IDs and recently touched IDs.
	 *
	 * @param recordIds          the set of existing record primary keys that must not be reused
	 * @param touchedInThisRound the set of record primary keys that were interacted with in the current operation
	 * @param random             an instance of {@code Random} used to generate a random primary key
	 * @return an {@code EntityBodyStoragePart} object initialized with the generated unique primary key
	 */
	private static EntityBodyStoragePart getNonExisting(
		@Nonnull Set<Integer> recordIds,
		@Nonnull Set<Integer> touchedInThisRound,
		@Nonnull StorageOptions storageOptions,
		@Nonnull Random random
	) {
		int recPrimaryKey;
		do {
			recPrimaryKey = Math.abs(random.nextInt());
		} while (recPrimaryKey != 0 && (recordIds.contains(recPrimaryKey) || touchedInThisRound.contains(recPrimaryKey)));

		return createEntityBodyStoragePartOfRandomSize(storageOptions, random, recPrimaryKey);
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
		@Nonnull StorageOptions storageOptions,
		@Nonnull Random random,
		int recPrimaryKey
	) {
		// we need to generate some fake data to cross the 4096 bytes boundary
		final Faker faker = new Faker(random);
		final int associatedDataKeys = random.nextInt(storageOptions.compress() ? 4000 : 1000);
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

	/**
	 * Retrieves an existing entity body storage part based on a set of records and a set of recently used identifiers.
	 * The method randomly selects an entry from the provided map, ensuring that the selected entry has not already been
	 * touched in the current round. If the selected entry has been used, the method recursively retries selection.
	 *
	 * @param records            a map of record IDs to their corresponding {@link EntityBodyStoragePart} objects
	 * @param touchedInThisRound a set of IDs that have been accessed in the current round
	 * @param random             an instance of {@link Random} used for randomized selection of entries
	 * @return a new {@link EntityBodyStoragePart} derived from the selected entity, incrementing its version
	 */
	private static EntityBodyStoragePart getExisting(
		@Nonnull Map<Integer, EntityBodyStoragePart> records,
		@Nonnull Set<Integer> touchedInThisRound,
		@Nonnull Random random
	) {
		final Iterator<Integer> it = records.keySet().iterator();
		final int bound = records.size() - 1;
		if (bound > 0) {
			final int steps = random.nextInt(bound);
			for (int i = 0; i < steps; i++) {
				it.next();
			}
		}
		final Integer adept = it.next();
		// retry if this id was picked already in this round
		return touchedInThisRound.contains(adept) ?
			getExisting(records, touchedInThisRound, random) :
			new EntityBodyStoragePart(records.get(adept).getVersion() + 1, adept, Scope.LIVE, null, Set.of(), Set.of(), Set.of(), 0);
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
	 * Configures the provided {@link StorageOptions} by applying the specified CRC32 check
	 * and compression settings.
	 *
	 * @param options     the initial storage options to configure
	 * @param crc32Check  the CRC32 check setting to apply; {@link Crc32Check#YES} enables CRC32 computation
	 * @param compression the compression setting to apply; {@link Compression#YES} enables compression
	 * @return the configured {@link StorageOptions} instance
	 */
	@Nonnull
	private static StorageOptions configure(@Nonnull StorageOptions options, @Nonnull Crc32Check crc32Check, @Nonnull Compression compression) {
		final Builder builder = StorageOptions.builder(options);
		builder.computeCRC32(crc32Check == Crc32Check.YES);
		builder.compress(compression == Compression.YES);
		return builder.build();
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

		return new InsertionOutput(fileOffsetIndex, Objects.requireNonNull(fileOffsetIndexDescriptor), transactionId, inserted, removed);
	}

	@BeforeEach
	void setUp() throws IOException {
		this.targetFile.toFile().delete();
		cleanTestSubDirectory(TEST_FOLDER);
		cleanTestSubDirectory(TEST_FOLDER_EXPORT);
	}

	@AfterEach
	void tearDown() throws IOException {
		this.targetFile.toFile().delete();
		cleanTestSubDirectory(TEST_FOLDER);
		cleanTestSubDirectory(TEST_FOLDER_EXPORT);
	}

	@DisplayName("Offset index can be stored empty.")
	@ParameterizedTest
	@MethodSource("combineSettings")
	void shouldSerializeAndReconstructEmptyOffsetIndex(Crc32Check crc32Check, Compression compression) {
		final InsertionOutput insertionOutput = shouldSerializeAndReconstructOffsetIndex(
			configure(StorageOptions.temporary(), crc32Check, compression), EntityBodyStoragePart::new, 0
		);
		IOUtils.closeQuietly(insertionOutput.fileOffsetIndex::close);
	}

	@DisplayName("Offset index can be stored empty and then new records added.")
	@ParameterizedTest
	@MethodSource("combineSettings")
	void shouldSerializeEmptyOffsetIndexWithLaterAddingRecordsAndReconstructCorrectly(Crc32Check crc32Check, Compression compression) {
		final StorageOptions storageOptions = configure(StorageOptions.temporary(), crc32Check, compression);
		final InsertionOutput insertionOutput = shouldSerializeAndReconstructOffsetIndex(storageOptions, EntityBodyStoragePart::new, 0);
		final OffsetIndex offsetIndex = insertionOutput.fileOffsetIndex();
		final InsertionOutput insertionOutput2 = createRecordsInFileOffsetIndex(offsetIndex, 100, 0, 1);
		/* input count records +1 record for the OffsetIndex itself */
		if (crc32Check == Crc32Check.YES) {
			assertEquals(
				/* 100 records, 1 empty header, 1 header with single fragment */
				100 + computeExpectedRecordCount(storageOptions, 100).fragments() + 1,
				offsetIndex.verifyContents().getRecordCount()
			);
		}
		assertEquals(
			100,
			offsetIndex.count(insertionOutput2.catalogVersion())
		);
		IOUtils.closeQuietly(offsetIndex::close);
	}

	@DisplayName("Hundreds entities should be stored in OffsetIndex and retrieved intact.")
	@ParameterizedTest
	@MethodSource("combineSettings")
	void shouldSerializeAndReconstructBigFileOffsetIndex(Crc32Check crc32Check, Compression compression) {
		final InsertionOutput insertionOutput = serializeAndReconstructBigFileOffsetIndex(configure(StorageOptions.temporary(), crc32Check, compression), EntityBodyStoragePart::new);
		IOUtils.closeQuietly(insertionOutput.fileOffsetIndex()::close);
	}

	@DisplayName("Half of the entities should be removed, file offset index copied to different file and reconstructed.")
	@ParameterizedTest
	@MethodSource("combineSettings")
	void shouldCopySnapshotOfTheBigFileOffsetIndexAndReconstruct(Crc32Check crc32Check, Compression compression) {
		final Random random = new Random(42);
		final StorageOptions limitedBufferOptions = buildOptionsWithLimitedBuffer(crc32Check, compression);
		final Map<Integer, EntityBodyStoragePart> parts = new HashMap<>();
		final InsertionOutput insertionOutput = serializeAndReconstructBigFileOffsetIndex(
			limitedBufferOptions,
			pk -> parts.computeIfAbsent(pk, thePk -> createEntityBodyStoragePartOfRandomSize(limitedBufferOptions, random, thePk))
		);
		final OffsetIndexDescriptor fileOffsetIndexDescriptor = insertionOutput.descriptor();
		final StorageOptions storageOptions = configure(StorageOptions.temporary(), crc32Check, compression);
		IOUtils.closeQuietly(insertionOutput.fileOffsetIndex()::close);

		Path snapshotPath = null;
		OffsetIndex sourceOffsetIndex = null;
		OffsetIndex purgedSourceOffsetIndex = null;
		OffsetIndex loadedFileOffsetIndex = null;
		try (
			final ObservableOutputKeeper observableOutputKeeper = new ObservableOutputKeeper(TEST_CATALOG, storageOptions, Mockito.mock(Scheduler.class))
		) {
			sourceOffsetIndex = new OffsetIndex(
				insertionOutput.catalogVersion(),
				new OffsetIndexDescriptor(
					new FileLocationAndWrittenBytes(fileOffsetIndexDescriptor.fileLocation(), 0, ""),
					fileOffsetIndexDescriptor,
					1.0,
					fileOffsetIndexDescriptor.getFileSize()
				),
				limitedBufferOptions,
				this.offsetIndexRecordTypeRegistry,
				new WriteOnlyFileHandle(this.targetFile, storageOptions, observableOutputKeeper),
				nonFlushedBlock -> {
				},
				oldestRecordTimestamp -> {
				}
			);

			int recordCount = sourceOffsetIndex.count(insertionOutput.catalogVersion());
			final long nextCatalogVersion = insertionOutput.catalogVersion() + 1;
			// delete every other record
			for (int i = 1; i <= recordCount; i = i + 2) {
				sourceOffsetIndex.remove(nextCatalogVersion, i, EntityBodyStoragePart.class);
			}

			final OffsetIndexDescriptor updatedOffsetIndexDescriptor = sourceOffsetIndex.flush(nextCatalogVersion);
			purgedSourceOffsetIndex = new OffsetIndex(
				nextCatalogVersion,
				new OffsetIndexDescriptor(
					new FileLocationAndWrittenBytes(updatedOffsetIndexDescriptor.fileLocation(), 0, ""),
					updatedOffsetIndexDescriptor,
					1.0,
					updatedOffsetIndexDescriptor.getFileSize()
				),
				limitedBufferOptions,
				this.offsetIndexRecordTypeRegistry,
				new WriteOnlyFileHandle(this.targetFile, storageOptions, observableOutputKeeper),
				nonFlushedBlock -> {
				},
				oldestRecordTimestamp -> {
				}
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
				limitedBufferOptions,
				this.offsetIndexRecordTypeRegistry,
				new WriteOnlyFileHandle(snapshotPath, storageOptions, observableOutputKeeper),
				nonFlushedBlock -> {
				},
				oldestRecordTimestamp -> {
				}
			);

			assertEquals(purgedSourceOffsetIndex.count(finalCatalogVersion), loadedFileOffsetIndex.count(finalCatalogVersion));
			assertEquals(purgedSourceOffsetIndex.getTotalSizeBytes(), loadedFileOffsetIndex.getTotalSizeBytes());
			for (int i = 2; i <= recordCount; i = i + 2) {
				final EntityBodyStoragePart actual = loadedFileOffsetIndex.get(finalCatalogVersion, i, EntityBodyStoragePart.class);
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
	void shouldRemoveRecord(Crc32Check crc32Check, Compression compression) {
		// store 300 records in multiple chunks,
		final int recordCount = 50;
		final int removedRecords = 10;
		final int iterationCount = 6;

		final StorageOptions storageOptions = configure(StorageOptions.temporary(), crc32Check, compression);
		InsertionOutput insertionResult = null;
		OffsetIndex loadedFileOffsetIndex = null;
		try (
			final ObservableOutputKeeper observableOutputKeeper = new ObservableOutputKeeper(TEST_CATALOG, storageOptions, Mockito.mock(Scheduler.class))
		) {
			insertionResult = createRecordsInFileOffsetIndex(
				storageOptions, observableOutputKeeper, recordCount, removedRecords, iterationCount
			);

			final OffsetIndexDescriptor fileOffsetIndexInfo = insertionResult.descriptor();

			loadedFileOffsetIndex = new OffsetIndex(
				0L,
				new OffsetIndexDescriptor(
					new FileLocationAndWrittenBytes(fileOffsetIndexInfo.fileLocation(), 0, ""),
					fileOffsetIndexInfo,
					1.0,
					fileOffsetIndexInfo.getFileSize()
				),
				storageOptions,
				this.offsetIndexRecordTypeRegistry,
				new WriteOnlyFileHandle(this.targetFile, storageOptions, observableOutputKeeper),
				nonFlushedBlock -> {
				},
				oldestRecordTimestamp -> {
				}
			);

			for (int i = 1; i <= recordCount * iterationCount; i++) {
				final EntityBodyStoragePart actual = loadedFileOffsetIndex.get(insertionResult.catalogVersion(), i, EntityBodyStoragePart.class);
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
			if (crc32Check == Crc32Check.YES) {
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
	void shouldReadBinaryRecordAndDeserializeManually(Crc32Check crc32Check, Compression compression) {
		// store 300 records in multiple chunks,
		final int recordCount = 50;
		final int removedRecords = 10;
		final int iterationCount = 6;

		final StorageOptions storageOptions = configure(StorageOptions.temporary(), crc32Check, compression);

		InsertionOutput insertionResult = null;
		OffsetIndex loadedFileOffsetIndex = null;
		try (
			final ObservableOutputKeeper observableOutputKeeper = new ObservableOutputKeeper(TEST_CATALOG, storageOptions, Mockito.mock(Scheduler.class))
		) {
			insertionResult = createRecordsInFileOffsetIndex(
				storageOptions, observableOutputKeeper, recordCount, removedRecords, iterationCount
			);

			final OffsetIndexDescriptor fileOffsetIndexDescriptor = insertionResult.descriptor();

			loadedFileOffsetIndex = new OffsetIndex(
				0L,
				new OffsetIndexDescriptor(
					new FileLocationAndWrittenBytes(fileOffsetIndexDescriptor.fileLocation(), 0, ""),
					fileOffsetIndexDescriptor,
					1.0,
					fileOffsetIndexDescriptor.getFileSize()
				),
				storageOptions,
				this.offsetIndexRecordTypeRegistry,
				new WriteOnlyFileHandle(this.targetFile, storageOptions, observableOutputKeeper),
				nonFlushedBlock -> {
				},
				oldestRecordTimestamp -> {
				}
			);

			final VersionedKryo kryo = createKryo()
				.apply(
					new VersionedKryoKeyInputs(
						loadedFileOffsetIndex.getReadOnlyKeyCompressor(), 1
					)
				);

			for (int i = 1; i <= recordCount * iterationCount; i++) {
				final byte[] actualBinary = loadedFileOffsetIndex.getBinary(insertionResult.catalogVersion(), i, EntityBodyStoragePart.class);
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
			if (crc32Check == Crc32Check.YES) {
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
	void shouldReadSingleRecordAndUsingManualDeserialization(Crc32Check crc32Check, Compression compression) {
		// store 300 records in multiple chunks,
		final int recordCount = 50;
		final int removedRecords = 10;
		final int iterationCount = 6;

		final StorageOptions storageOptions = configure(StorageOptions.temporary(), crc32Check, compression);

		try (
			final ObservableOutputKeeper observableOutputKeeper = new ObservableOutputKeeper(TEST_CATALOG, storageOptions, Mockito.mock(Scheduler.class))
		) {
			final InsertionOutput insertionResult = createRecordsInFileOffsetIndex(
				storageOptions, observableOutputKeeper, recordCount, removedRecords, iterationCount
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
					storageOptions,
					this.targetFile,
					offsetIndexDescriptor.fileLocation(),
					key,
					(offsetIndexBuilder, input) -> offsetIndexBuilder.getFileLocationFor(key)
						.map(fileLocation -> StorageRecord.read(
							input, fileLocation, (theInput, length, control) -> kryo.readObject(theInput, EntityBodyStoragePart.class)
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

	@DisplayName("No operation should be allowed after close")
	@Test
	void shouldRefuseOperationAfterClose() {
		final StorageOptions storageOptions = StorageOptions.temporary();
		try (
			final ObservableOutputKeeper observableOutputKeeper = new ObservableOutputKeeper(TEST_CATALOG, storageOptions, Mockito.mock(Scheduler.class))
		) {
			final OffsetIndex fileOffsetIndex = new OffsetIndex(
				0L,
				new OffsetIndexDescriptor(
					new EntityCollectionHeader(ENTITY_TYPE, 1, 0),
					createKryo(),
					1.0, 0L
				),
				storageOptions,
				this.offsetIndexRecordTypeRegistry,
				new WriteOnlyFileHandle(this.targetFile, storageOptions, observableOutputKeeper),
				nonFlushedBlock -> {
				},
				oldestRecordTimestamp -> {
				}
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

	@ParameterizedTest(name = "OffsetIndex should survive generational randomized test applying modifications on it, compression: {1}")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeAndCompressionArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input, Compression compression) {
		final AtomicReference<Path> currentFilePath = new AtomicReference<>(this.targetFile);
		final StorageOptions storageOptions = buildOptionsWithLimitedBuffer(Crc32Check.YES, compression);
		try (
			final ObservableOutputKeeper observableOutputKeeper = new ObservableOutputKeeper(TEST_CATALOG, storageOptions, Mockito.mock(Scheduler.class))
		) {
			final AtomicReference<OffsetIndex> fileOffsetIndex = new AtomicReference<>(
				new OffsetIndex(
					0L,
					new OffsetIndexDescriptor(
						new EntityCollectionHeader(ENTITY_TYPE, 1, 0),
						createKryo(),
						1.0, 0L
					),
					storageOptions,
					this.offsetIndexRecordTypeRegistry,
					new WriteOnlyFileHandle(this.targetFile, storageOptions, observableOutputKeeper),
					nonFlushedBlock -> {
					},
					oldestRecordTimestamp -> {
					}
				)
			);

			final int maximalRecordCount = 10_000;
			final int minimalRecordCount = 1_000;
			final int historySize = 10;
			final Map<Long, Map<Integer, EntityBodyStoragePart>> recordIdsHistory = CollectionUtils.createHashMap(historySize);

			runFor(
				input,
				1L,
				(random, transactionId) -> {
					final Map<Integer, EntityBodyStoragePart> currentSnapshot = ofNullable(recordIdsHistory.get(transactionId - 1))
						.map(HashMap::new)
						.orElseGet(() -> CollectionUtils.createHashMap(maximalRecordCount));

					final int recordCountToTouch = random.nextInt(minimalRecordCount);
					final List<RecordOperation> plannedOps = new ArrayList<>(recordCountToTouch);
					final Set<Integer> touchedInThisRound = new HashSet<>();
					for (int i = 1; i <= recordCountToTouch; i++) {
						final int rndOp = random.nextInt(3);
						final RecordOperation operation;
						if (currentSnapshot.isEmpty() || (rndOp == 0 && currentSnapshot.size() < maximalRecordCount)) {
							operation = new RecordOperation(getNonExisting(currentSnapshot.keySet(), touchedInThisRound, storageOptions, random), Operation.INSERT);
							currentSnapshot.put(operation.record().getPrimaryKey(), operation.record());
						} else if (currentSnapshot.size() - touchedInThisRound.size() > minimalRecordCount && rndOp == 1) {
							operation = new RecordOperation(getExisting(currentSnapshot, touchedInThisRound, random), Operation.UPDATE);
							currentSnapshot.put(operation.record().getPrimaryKey(), operation.record());
						} else if (currentSnapshot.size() - touchedInThisRound.size() > minimalRecordCount && rndOp == 2) {
							operation = new RecordOperation(getExisting(currentSnapshot, touchedInThisRound, random), Operation.REMOVE);
							currentSnapshot.remove(operation.record().getPrimaryKey());
						} else {
							continue;
						}
						touchedInThisRound.add(operation.record().getPrimaryKey());
						plannedOps.add(operation);
					}

					final OffsetIndex currentOffsetIndex = fileOffsetIndex.get();
					for (RecordOperation plannedOp : plannedOps) {
						switch (plannedOp.operation()) {
							case INSERT -> {
								final EntityBodyStoragePart existingContainer = currentOffsetIndex.get(transactionId, plannedOp.record().getPrimaryKey(), EntityBodyStoragePart.class);
								assertNull(existingContainer, "The container with id " + plannedOp.record().getPrimaryKey() + " unexpectedly found!");
								currentOffsetIndex.put(transactionId, plannedOp.record());
							}
							case UPDATE -> {
								final EntityBodyStoragePart existingContainer = currentOffsetIndex.get(transactionId, plannedOp.record().getPrimaryKey(), EntityBodyStoragePart.class);
								assertNotNull(existingContainer, "The container with id " + plannedOp.record().getPrimaryKey() + " unexpectedly not found!");
								currentOffsetIndex.put(transactionId, plannedOp.record());
							}
							case REMOVE -> {
								final EntityBodyStoragePart existingContainer = currentOffsetIndex.get(transactionId, plannedOp.record().getPrimaryKey(), EntityBodyStoragePart.class);
								assertNotNull(existingContainer, "The container with id " + plannedOp.record().getPrimaryKey() + " unexpectedly not found!");
								currentOffsetIndex.remove(transactionId, plannedOp.record.getPrimaryKey(), EntityBodyStoragePart.class);
							}
						}
					}

					final OffsetIndexDescriptor fileOffsetIndexDescriptor = currentOffsetIndex.flush(transactionId);

					long start = System.nanoTime();
					final OffsetIndex loadedFileOffsetIndex = new OffsetIndex(
						transactionId,
						new OffsetIndexDescriptor(
							new FileLocationAndWrittenBytes(fileOffsetIndexDescriptor.fileLocation(), 0, ""),
							fileOffsetIndexDescriptor,
							1.0,
							fileOffsetIndexDescriptor.getFileSize()
						),
						storageOptions,
						this.offsetIndexRecordTypeRegistry,
						new WriteOnlyFileHandle(currentFilePath.get(), storageOptions, observableOutputKeeper),
						nonFlushedBlock -> {
						},
						oldestRecordTimestamp -> {
						}
					);
					long end = System.nanoTime();

					assertTrue(currentOffsetIndex.fileOffsetIndexEquals(loadedFileOffsetIndex));

					final FileOffsetIndexStatistics stats = currentOffsetIndex.verifyContents();

					assertEquals(currentSnapshot.size(), currentOffsetIndex.count(transactionId));
					assertEquals(currentSnapshot.size(), currentOffsetIndex.count(transactionId, EntityBodyStoragePart.class));

					for (Entry<Integer, EntityBodyStoragePart> entry : currentSnapshot.entrySet()) {
						assertTrue(
							currentOffsetIndex.contains(transactionId, entry.getKey(), EntityBodyStoragePart.class),
							"Cnt " + entry.getKey() + " should be non null but was!"
						);
						assertEquals(
							entry.getValue(),
							currentOffsetIndex.get(transactionId, entry.getKey(), EntityBodyStoragePart.class)
						);
					}
					for (RecordOperation plannedOp : plannedOps) {
						if (plannedOp.operation == Operation.REMOVE) {
							assertFalse(
								currentOffsetIndex.contains(transactionId, plannedOp.record().getPrimaryKey(), EntityBodyStoragePart.class),
								"Cnt " + plannedOp.record().getPrimaryKey() + " should be null but was not!"
							);
							assertNull(
								currentOffsetIndex.get(transactionId, plannedOp.record().getPrimaryKey(), EntityBodyStoragePart.class),
								"Cnt " + plannedOp.record().getPrimaryKey() + " should be null but was not!"
							);
						}
					}

					// randomly verify the contents of the previous versions
					for (int i = 0; i < recordIdsHistory.size() / 2; i++) {
						final long historyTxId = transactionId - (random.nextInt(recordIdsHistory.size() - 1) + 1);
						final Map<Integer, EntityBodyStoragePart> historySnapshot = recordIdsHistory.get(historyTxId);
						assertEquals(
							historySnapshot.size(), currentOffsetIndex.count(historyTxId),
							"History snapshot #" + historyTxId + " size mismatch: expected " + historySnapshot.size() + " but was " + currentOffsetIndex.count(transactionId)
						);
						assertEquals(
							historySnapshot.size(), currentOffsetIndex.count(historyTxId, EntityBodyStoragePart.class),
							"History snapshot #" + historyTxId + " size mismatch: expected " + historySnapshot.size() + " but was " + currentOffsetIndex.count(transactionId)
						);
						final int averageSkip = recordIdsHistory.size() / 1000;
						if (averageSkip > 0) {
							final Iterator<Entry<Long, Map<Integer, EntityBodyStoragePart>>> it = recordIdsHistory.entrySet().iterator();
							int index = 0;
							while (it.hasNext()) {
								final Entry<Long, Map<Integer, EntityBodyStoragePart>> entry = it.next();
								if (index++ % averageSkip == 0) {
									assertEquals(
										entry.getValue(),
										currentOffsetIndex.get(historyTxId, entry.getKey(), EntityBodyStoragePart.class)
									);
								}
							}
						}
					}

					System.out.println(
						"Round trip #" + transactionId + " (loaded in " +
							StringUtils.formatNano(end - start) + ", " + loadedFileOffsetIndex.count(transactionId) +
							" living recs. / " + stats.getRecordCount() + " total recs.)"
					);

					if (stats.getActiveRecordShare() < 0.5) {
						System.out.println("Living object share is below 50%! Compacting ...");
						final long compactionStart = System.currentTimeMillis();

						final Path pathToCompact = currentFilePath.get();
						final Path newPath = pathToCompact.getParent().resolve("fileOffsetIndex_" + transactionId + ".kryo");
						currentFilePath.set(newPath);

						final OffsetIndexDescriptor compactedDescriptor = currentOffsetIndex.compact(newPath);
						final OffsetIndex newOffsetIndex = new OffsetIndex(
							transactionId,
							new OffsetIndexDescriptor(
								new FileLocationAndWrittenBytes(compactedDescriptor.fileLocation(), 0, ""),
								compactedDescriptor,
								1.0,
								compactedDescriptor.getFileSize()
							),
							storageOptions,
							this.offsetIndexRecordTypeRegistry,
							new WriteOnlyFileHandle(newPath, storageOptions, observableOutputKeeper),
							nonFlushedBlock -> {
							},
							oldestRecordTimestamp -> {
							}
						);
						final FileOffsetIndexStatistics newStats = newOffsetIndex.verifyContents();
						assertTrue(newStats.getActiveRecordShare() > 0.5);
						fileOffsetIndex.set(newOffsetIndex);

						System.out.println(
							"Compaction from " + StringUtils.formatByteSize(pathToCompact.toFile().length()) +
								" to " + StringUtils.formatByteSize(newPath.toFile().length()) + " took " +
								StringUtils.formatNano(System.currentTimeMillis() - compactionStart)
						);

						// delete previous file
						pathToCompact.toFile().delete();

						// and collected history
						recordIdsHistory.clear();
					}

					recordIdsHistory.put(transactionId, currentSnapshot);
					recordIdsHistory.remove(transactionId - historySize);

					return transactionId + 1;
				}
			);
		}
	}

	@Nonnull
	private StorageOptions buildOptionsWithLimitedBuffer(Crc32Check crc32Check, Compression compression) {
		return StorageOptions.builder()
			.storageDirectory(getTestDirectory().resolve(TEST_FOLDER))
			.exportDirectory(getTestDirectory().resolve(TEST_FOLDER_EXPORT))
			.waitOnCloseSeconds(5)
			.lockTimeoutSeconds(5)
			.outputBufferSize(4096)
			.maxOpenedReadHandles(Runtime.getRuntime().availableProcessors())
			.computeCRC32(crc32Check == Crc32Check.YES)
			.compress(compression == Compression.YES)
			.build();
	}

	@Nonnull
	private InsertionOutput serializeAndReconstructBigFileOffsetIndex(
		@Nonnull StorageOptions storageOptions,
		@Nonnull IntFunction<EntityBodyStoragePart> bodyPartFactory
	) {
		return shouldSerializeAndReconstructOffsetIndex(storageOptions, bodyPartFactory, 600);
	}

	@Nonnull
	private InsertionOutput shouldSerializeAndReconstructOffsetIndex(
		@Nonnull StorageOptions storageOptions,
		@Nonnull IntFunction<EntityBodyStoragePart> bodyPartFactory,
		int recordCount
	) {
		OffsetIndex loadedFileOffsetIndex = null;
		try (
			final ObservableOutputKeeper observableOutputKeeper = new ObservableOutputKeeper(TEST_CATALOG, storageOptions, Mockito.mock(Scheduler.class));
			final WriteOnlyFileHandle writeHandle = new WriteOnlyFileHandle(this.targetFile, storageOptions, observableOutputKeeper);
		) {
			final OffsetIndex fileOffsetIndex = new OffsetIndex(
				0L,
				new OffsetIndexDescriptor(
					new EntityCollectionHeader(ENTITY_TYPE, 1, 0),
					createKryo(),
					1.0, 0L
				),
				storageOptions,
				this.offsetIndexRecordTypeRegistry,
				writeHandle,
				nonFlushedBlock -> {
				},
				oldestRecordTimestamp -> {
				}
			);

			int inserted = 0;
			final long transactionId = 0L;
			for (int i = 1; i <= recordCount; i++) {
				fileOffsetIndex.put(transactionId, bodyPartFactory.apply(i));
				inserted++;
			}

			log.info("Flushing table (" + transactionId + ")");
			final OffsetIndexDescriptor fileOffsetIndexDescriptor = fileOffsetIndex.flush(transactionId);
			loadedFileOffsetIndex = new OffsetIndex(
				0L,
				new OffsetIndexDescriptor(
					new FileLocationAndWrittenBytes(fileOffsetIndexDescriptor.fileLocation(), 0, ""),
					fileOffsetIndexDescriptor,
					1.0,
					fileOffsetIndexDescriptor.getFileSize()
				),
				storageOptions,
				this.offsetIndexRecordTypeRegistry,
				writeHandle,
				nonFlushedBlock -> {
				},
				oldestRecordTimestamp -> {
				}
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
			if (storageOptions.computeCRC32C()) {
				assertEquals(
					recordCount + Math.max(1, computeExpectedRecordCount(storageOptions, recordCount).fragments()),
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
		StorageOptions options,
		ObservableOutputKeeper observableOutputKeeper,
		int recordCount,
		int removedRecords,
		int iterationCount
	) {
		final OffsetIndex fileOffsetIndex = new OffsetIndex(
			0L,
			new OffsetIndexDescriptor(
				new EntityCollectionHeader(ENTITY_TYPE, 1, 0),
				createKryo(),
				1.0, 0L
			),
			options,
			this.offsetIndexRecordTypeRegistry,
			new WriteOnlyFileHandle(this.targetFile, options, observableOutputKeeper),
			nonFlushedBlock -> {
			},
			oldestRecordTimestamp -> {
			}
		);

		return createRecordsInFileOffsetIndex(fileOffsetIndex, recordCount, removedRecords, iterationCount);
	}

	private enum Operation {
		INSERT, UPDATE, REMOVE
	}

	private enum Crc32Check {
		YES, NO
	}

	private enum Compression {
		YES, NO
	}

	private record RecordOperation(
		@Nonnull EntityBodyStoragePart record,
		@Nonnull Operation operation
	) {
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
