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

package io.evitadb.store.offsetIndex;

import com.esotericsoftware.kryo.io.Input;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.store.entity.EntityStoragePartConfigurer;
import io.evitadb.store.entity.model.entity.EntityBodyStoragePart;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoFactory;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.offsetIndex.OffsetIndex.FileOffsetIndexStatistics;
import io.evitadb.store.offsetIndex.io.WriteOnlyFileHandle;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.schema.SchemaKryoConfigurer;
import io.evitadb.store.spi.model.EntityCollectionHeader;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies functionality of {@link OffsetIndex} operations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
class OffsetIndexTest implements TimeBoundedTestSupport {
	public static final String ENTITY_TYPE = "whatever";
	private final Path targetFile = Path.of(System.getProperty("java.io.tmpdir") + File.separator + "fileOffsetIndex.kryo");
	private final OffsetIndexRecordTypeRegistry fileOffsetIndexRecordTypeRegistry = new OffsetIndexRecordTypeRegistry();
	private final StorageOptions options = StorageOptions.temporary();
	private final ObservableOutputKeeper observableOutputKeeper = new ObservableOutputKeeper(options, Mockito.mock(ScheduledExecutorService.class));

	private static EntityBodyStoragePart getNonExisting(Set<Integer> recordIds, Set<Integer> touchedInThisRound, Random random) {
		int recPrimaryKey;
		do {
			recPrimaryKey = Math.abs(random.nextInt());
		} while (recPrimaryKey != 0 && (recordIds.contains(recPrimaryKey) || touchedInThisRound.contains(recPrimaryKey)));
		return new EntityBodyStoragePart(1, recPrimaryKey, null, Set.of(), Set.of(), Set.of());
	}

	private static EntityBodyStoragePart getExisting(Map<Integer, EntityBodyStoragePart> records, Set<Integer> touchedInThisRound, Random random) {
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
			new EntityBodyStoragePart(records.get(adept).getVersion() + 1, adept, null, Set.of(), Set.of(), Set.of());
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

	@BeforeEach
	void setUp() {
		targetFile.toFile().delete();
	}

	@AfterEach
	void tearDown() {
		targetFile.toFile().delete();
		observableOutputKeeper.close();
	}

	@DisplayName("Hundreds entities should be stored in OffsetIndex and retrieved intact.")
	@Test
	void shouldSerializeAndReconstructBigFileOffsetIndex() {
		serializeAndReconstructBigFileOffsetIndex();
	}

	@DisplayName("Half of the entities should be removed, file offset index copied to different file and reconstructed.")
	@Test
	void shouldCopySnapshotOfTheBigFileOffsetIndexAndReconstruct() {
		final InsertionOutput insertionOutput = serializeAndReconstructBigFileOffsetIndex();
		final OffsetIndexDescriptor fileOffsetIndexDescriptor = insertionOutput.descriptor();
		final OffsetIndex sourceOffsetIndex = new OffsetIndex(
			0L,
			new OffsetIndexDescriptor(
				fileOffsetIndexDescriptor.fileLocation(),
				fileOffsetIndexDescriptor
			),
			options,
			fileOffsetIndexRecordTypeRegistry,
			new WriteOnlyFileHandle(targetFile, observableOutputKeeper)
		);

		int recordCount = sourceOffsetIndex.count(insertionOutput.catalogVersion());
		final long nextCatalogVersion = insertionOutput.catalogVersion() + 1;
		// delete every other record
		for (int i = 1; i <= recordCount; i = i + 2) {
			sourceOffsetIndex.remove(nextCatalogVersion, i, EntityBodyStoragePart.class);
		}

		final OffsetIndexDescriptor updatedOffsetIndexDescriptor = sourceOffsetIndex.flush(nextCatalogVersion);
		final OffsetIndex purgedSourceOffsetIndex = new OffsetIndex(
			0L,
			new OffsetIndexDescriptor(
				updatedOffsetIndexDescriptor.fileLocation(),
				updatedOffsetIndexDescriptor
			),
			options,
			fileOffsetIndexRecordTypeRegistry,
			new WriteOnlyFileHandle(targetFile, observableOutputKeeper)
		);

		// now create a snapshot of the file offset index
		final Path snapshotPath = Path.of(System.getProperty("java.io.tmpdir") + File.separator + "snapshot.kryo");
		try {
			final long finalCatalogVersion = nextCatalogVersion + 1;
			final OffsetIndexDescriptor snapshotBootstrapDescriptor = purgedSourceOffsetIndex.copySnapshotTo(snapshotPath, finalCatalogVersion);
			final OffsetIndex loadedFileOffsetIndex = new OffsetIndex(
				snapshotBootstrapDescriptor.version(),
				snapshotBootstrapDescriptor,
				options,
				fileOffsetIndexRecordTypeRegistry,
				new WriteOnlyFileHandle(snapshotPath, observableOutputKeeper)
			);

			assertEquals(purgedSourceOffsetIndex.count(finalCatalogVersion), loadedFileOffsetIndex.count(finalCatalogVersion));
			assertEquals(purgedSourceOffsetIndex.getTotalSize(), loadedFileOffsetIndex.getTotalSize());
			for (int i = 2; i <= recordCount; i = i + 2) {
				final EntityBodyStoragePart actual = loadedFileOffsetIndex.get(finalCatalogVersion, i, EntityBodyStoragePart.class);
				assertEquals(
					new EntityBodyStoragePart(i),
					actual
				);
			}
		} finally {
			snapshotPath.toFile().delete();
		}
	}

	@DisplayName("Existing record can be removed")
	@Test
	void shouldRemoveRecord() {
		// store 300 records in multiple chunks,
		final int recordCount = 50;
		final int removedRecords = 10;
		final int iterationCount = 6;

		final InsertionOutput insertionResult = createRecordsInFileOffsetIndex(
			options, observableOutputKeeper, recordCount, removedRecords, iterationCount
		);

		final OffsetIndexDescriptor fileOffsetIndexInfo = insertionResult.descriptor();

		final OffsetIndex loadedFileOffsetIndex = new OffsetIndex(
			0L,
			new OffsetIndexDescriptor(
				fileOffsetIndexInfo.fileLocation(),
				fileOffsetIndexInfo
			),
			options,
			fileOffsetIndexRecordTypeRegistry,
			new WriteOnlyFileHandle(targetFile, observableOutputKeeper)
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
		/* 300 records +6 record for th OffsetIndex itself */
		assertEquals(306, loadedFileOffsetIndex.verifyContents().getRecordCount());
	}

	@Test
	void shouldReadBinaryRecordAndDeserializeManually() {
		// store 300 records in multiple chunks,
		final int recordCount = 50;
		final int removedRecords = 10;
		final int iterationCount = 6;

		final InsertionOutput insertionResult = createRecordsInFileOffsetIndex(
			options, observableOutputKeeper, recordCount, removedRecords, iterationCount
		);

		final OffsetIndexDescriptor fileOffsetIndexDescriptor = insertionResult.descriptor();

		final OffsetIndex loadedFileOffsetIndex = new OffsetIndex(
			0L,
			new OffsetIndexDescriptor(
				fileOffsetIndexDescriptor.fileLocation(),
				fileOffsetIndexDescriptor
			),
			options,
			fileOffsetIndexRecordTypeRegistry,
			new WriteOnlyFileHandle(targetFile, observableOutputKeeper)
		);

		for (int i = 1; i <= recordCount * iterationCount; i++) {
			final byte[] actualBinary = loadedFileOffsetIndex.getBinary(insertionResult.catalogVersion(), i, EntityBodyStoragePart.class);
			if (i < recordCount * (iterationCount - 1) && i % recordCount < removedRecords && i % recordCount > 0) {
				assertNull(actualBinary);
			} else {
				assertNotNull(actualBinary);
				final VersionedKryo kryo = createKryo()
					.apply(
						new VersionedKryoKeyInputs(
							loadedFileOffsetIndex.getReadOnlyKeyCompressor(), 1
						)
					);
				assertEquals(
					new EntityBodyStoragePart(i),
					kryo.readObject(new Input(actualBinary), EntityBodyStoragePart.class)
				);
			}
		}

		assertTrue(insertionResult.fileOffsetIndex().fileOffsetIndexEquals(loadedFileOffsetIndex));
		/* 300 records +6 record for th OffsetIndex itself */
		assertEquals(306, loadedFileOffsetIndex.verifyContents().getRecordCount());
	}

	@DisplayName("No operation should be allowed after close")
	@Test
	void shouldRefuseOperationAfterClose() {
		final OffsetIndex fileOffsetIndex = new OffsetIndex(
			0L,
			new OffsetIndexDescriptor(
				new EntityCollectionHeader(ENTITY_TYPE, 1),
				createKryo()
			),
			options,
			fileOffsetIndexRecordTypeRegistry,
			new WriteOnlyFileHandle(targetFile, observableOutputKeeper)
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

	@ParameterizedTest(name = "OffsetIndex should survive generational randomized test applying modifications on it")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final OffsetIndex fileOffsetIndex = new OffsetIndex(
			0L,
			new OffsetIndexDescriptor(
				new EntityCollectionHeader(ENTITY_TYPE, 1),
				createKryo()
			),
			options,
			fileOffsetIndexRecordTypeRegistry,
			new WriteOnlyFileHandle(targetFile, observableOutputKeeper)
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
						operation = new RecordOperation(getNonExisting(currentSnapshot.keySet(), touchedInThisRound, random), Operation.INSERT);
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

				for (RecordOperation plannedOp : plannedOps) {
					switch (plannedOp.operation()) {
						case INSERT -> {
							final EntityBodyStoragePart existingContainer = fileOffsetIndex.get(transactionId, plannedOp.record().getPrimaryKey(), EntityBodyStoragePart.class);
							assertNull(existingContainer, "The container with id " + plannedOp.record().getPrimaryKey() + " unexpectedly found!");
							fileOffsetIndex.put(transactionId, plannedOp.record());
						}
						case UPDATE -> {
							final EntityBodyStoragePart existingContainer = fileOffsetIndex.get(transactionId, plannedOp.record().getPrimaryKey(), EntityBodyStoragePart.class);
							assertNotNull(existingContainer, "The container with id " + plannedOp.record().getPrimaryKey() + " unexpectedly not found!");
							fileOffsetIndex.put(transactionId, plannedOp.record());
						}
						case REMOVE -> {
							final EntityBodyStoragePart existingContainer = fileOffsetIndex.get(transactionId, plannedOp.record().getPrimaryKey(), EntityBodyStoragePart.class);
							assertNotNull(existingContainer, "The container with id " + plannedOp.record().getPrimaryKey() + " unexpectedly not found!");
							fileOffsetIndex.remove(transactionId, plannedOp.record.getPrimaryKey(), EntityBodyStoragePart.class);
						}
					}
				}

				final OffsetIndexDescriptor fileOffsetIndexDescriptor = fileOffsetIndex.flush(transactionId);

				long start = System.nanoTime();
				final OffsetIndex loadedFileOffsetIndex = new OffsetIndex(
					0L,
					new OffsetIndexDescriptor(
						fileOffsetIndexDescriptor.fileLocation(),
						fileOffsetIndexDescriptor
					),
					options,
					fileOffsetIndexRecordTypeRegistry,
					new WriteOnlyFileHandle(targetFile, observableOutputKeeper)
				);
				long end = System.nanoTime();

				assertTrue(fileOffsetIndex.fileOffsetIndexEquals(loadedFileOffsetIndex));

				final FileOffsetIndexStatistics stats = fileOffsetIndex.verifyContents();

				assertEquals(currentSnapshot.size(), fileOffsetIndex.count(transactionId));
				assertEquals(currentSnapshot.size(), fileOffsetIndex.count(transactionId, EntityBodyStoragePart.class));

				for (Entry<Integer, EntityBodyStoragePart> entry : currentSnapshot.entrySet()) {
					assertTrue(
						fileOffsetIndex.contains(transactionId, entry.getKey(), EntityBodyStoragePart.class),
						"Cnt " + entry.getKey() + " should be non null but was!"
					);
					assertEquals(
						entry.getValue(),
						fileOffsetIndex.get(transactionId, entry.getKey(), EntityBodyStoragePart.class)
					);
				}
				for (RecordOperation plannedOp : plannedOps) {
					if (plannedOp.operation == Operation.REMOVE) {
						assertFalse(
							fileOffsetIndex.contains(transactionId, plannedOp.record().getPrimaryKey(), EntityBodyStoragePart.class),
							"Cnt " + plannedOp.record().getPrimaryKey() + " should be null but was not!"
						);
						assertNull(
							fileOffsetIndex.get(transactionId, plannedOp.record().getPrimaryKey(), EntityBodyStoragePart.class),
							"Cnt " + plannedOp.record().getPrimaryKey() + " should be null but was not!"
						);
					}
				}

				// randomly verify the contents of the previous versions
				for (int i = 0; i < recordIdsHistory.size() / 2; i++) {
					final long historyTxId = transactionId - (random.nextInt(recordIdsHistory.size() - 1) + 1);
					final Map<Integer, EntityBodyStoragePart> historySnapshot = recordIdsHistory.get(historyTxId);
					assertEquals(
						historySnapshot.size(), fileOffsetIndex.count(historyTxId),
						"History snapshot #" + historyTxId + " size mismatch: expected " + historySnapshot.size() + " but was " + fileOffsetIndex.count(transactionId)
					);
					assertEquals(
						historySnapshot.size(), fileOffsetIndex.count(historyTxId, EntityBodyStoragePart.class),
						"History snapshot #" + historyTxId + " size mismatch: expected " + historySnapshot.size() + " but was " + fileOffsetIndex.count(transactionId)
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
									fileOffsetIndex.get(historyTxId, entry.getKey(), EntityBodyStoragePart.class)
								);
							}
						}
					}
				}

				recordIdsHistory.put(transactionId, currentSnapshot);
				recordIdsHistory.remove(transactionId - historySize);

				System.out.println(
					"Round trip #" + transactionId + " (loaded in " +
						StringUtils.formatNano(end - start) + ", " + loadedFileOffsetIndex.count(transactionId) +
						" living recs. / " + stats.getRecordCount() + " total recs.)"
				);

				return transactionId + 1;
			}
		);
	}

	@Nonnull
	private InsertionOutput serializeAndReconstructBigFileOffsetIndex() {
		final OffsetIndex fileOffsetIndex = new OffsetIndex(
			0L,
			new OffsetIndexDescriptor(
				new EntityCollectionHeader(ENTITY_TYPE, 1),
				createKryo()
			),
			options,
			fileOffsetIndexRecordTypeRegistry,
			new WriteOnlyFileHandle(targetFile, observableOutputKeeper)
		);
		final int recordCount = 600;

		final long transactionId = 1;
		for (int i = 1; i <= recordCount; i++) {
			fileOffsetIndex.put(transactionId, new EntityBodyStoragePart(i));
		}

		log.info("Flushing table (" + transactionId + ")");
		final OffsetIndexDescriptor fileOffsetIndexDescriptor = fileOffsetIndex.flush(transactionId);
		final OffsetIndex loadedFileOffsetIndex = new OffsetIndex(
			0L,
			new OffsetIndexDescriptor(
				fileOffsetIndexDescriptor.fileLocation(),
				fileOffsetIndexDescriptor
			),
			options,
			fileOffsetIndexRecordTypeRegistry,
			new WriteOnlyFileHandle(targetFile, observableOutputKeeper)
		);

		long duration = 0L;
		for (int i = 1; i <= recordCount; i++) {
			long start = System.nanoTime();
			final EntityBodyStoragePart actual = fileOffsetIndex.get(transactionId, i, EntityBodyStoragePart.class);
			duration += System.nanoTime() - start;
			assertEquals(
				new EntityBodyStoragePart(i),
				actual
			);
		}

		assertTrue(fileOffsetIndex.fileOffsetIndexEquals(loadedFileOffsetIndex));
		/* 600 records +1 record for th OffsetIndex itself */
		assertEquals(601, fileOffsetIndex.verifyContents().getRecordCount());
		log.info("Average reads: " + StringUtils.formatRequestsPerSec(recordCount, duration));

		return new InsertionOutput(fileOffsetIndex, fileOffsetIndexDescriptor, transactionId);
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
				new EntityCollectionHeader(ENTITY_TYPE, 1),
				createKryo()
			),
			options,
			fileOffsetIndexRecordTypeRegistry,
			new WriteOnlyFileHandle(targetFile, observableOutputKeeper)
		);

		OffsetIndexDescriptor fileOffsetIndexDescriptor = null;

		long transactionId = 0;
		for (int j = 0; j < iterationCount; j++) {
			transactionId++;
			if (j > 0) {
				for (int i = 1; i < removedRecords; i++) {
					final int primaryKey = i + (j - 1) * recordCount;
					log.info("Removal of rec with PK:   " + primaryKey);
					fileOffsetIndex.remove(transactionId, primaryKey, EntityBodyStoragePart.class);
				}
			}
			for (int i = 1; i <= recordCount; i++) {
				final int primaryKey = j * recordCount + i;
				log.info("Insertion of rec with PK (tx " + transactionId + "): " + primaryKey);
				fileOffsetIndex.put(transactionId, new EntityBodyStoragePart(primaryKey));
			}

			log.info("Flushing table (tx " + transactionId + ")");
			fileOffsetIndexDescriptor = fileOffsetIndex.flush(transactionId);
		}

		return new InsertionOutput(fileOffsetIndex, Objects.requireNonNull(fileOffsetIndexDescriptor), transactionId);
	}

	private enum Operation {
		INSERT, UPDATE, REMOVE
	}

	private record RecordOperation(
		@Nonnull EntityBodyStoragePart record,
		@Nonnull Operation operation
	) {
	}

	private record InsertionOutput(
		@Nonnull OffsetIndex fileOffsetIndex,
		@Nonnull OffsetIndexDescriptor descriptor,
		long catalogVersion
	) {
	}

}
