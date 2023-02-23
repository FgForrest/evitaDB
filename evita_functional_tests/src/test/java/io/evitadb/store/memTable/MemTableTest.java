/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
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

package io.evitadb.store.memTable;

import com.esotericsoftware.kryo.io.Input;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.store.entity.EntityStoragePartConfigurer;
import io.evitadb.store.entity.model.entity.EntityBodyStoragePart;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.kryo.VersionedKryo;
import io.evitadb.store.kryo.VersionedKryoFactory;
import io.evitadb.store.kryo.VersionedKryoKeyInputs;
import io.evitadb.store.memTable.MemTable.MemTableFileStatistics;
import io.evitadb.store.memTable.model.MemTableRecordTypeRegistry;
import io.evitadb.store.schema.SchemaKryoConfigurer;
import io.evitadb.store.spi.model.EntityCollectionHeader;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import io.evitadb.utils.Assert;
import io.evitadb.utils.StringUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies functionality of {@link MemTable} operations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Slf4j
class MemTableTest implements TimeBoundedTestSupport {
	public static final String ENTITY_TYPE = "whatever";
	private final Path targetFile = Path.of(System.getProperty("java.io.tmpdir") + File.separator + "memtable.kryo");
	private final MemTableRecordTypeRegistry memTableRecordTypeRegistry = new MemTableRecordTypeRegistry();

	@BeforeEach
	void setUp() {
		targetFile.toFile().delete();
	}

	@AfterEach
	void tearDown() {
		targetFile.toFile().delete();
	}

	@DisplayName("Hundreds entities should be stored in MemTable and retrieved intact.")
	@Test
	void shouldSerializeAndReconstructBigMemTable() {
		final StorageOptions options = StorageOptions.temporary();
		final ObservableOutputKeeper observableOutputKeeper = new ObservableOutputKeeper(options);
		observableOutputKeeper.prepare();

		final MemTable memTable = new MemTable(
			targetFile,
			new MemTableDescriptor(
				new EntityCollectionHeader(ENTITY_TYPE, 1),
				createKryo(),
				false
			),
			options,
			memTableRecordTypeRegistry,
			observableOutputKeeper
		);
		final int recordCount = 600;

		final long transactionId = 1;
		for (int i = 1; i <= recordCount; i++) {
			memTable.put(transactionId, new EntityBodyStoragePart(i));
		}

		log.info("Flushing table (" + transactionId + ")");
		final MemTableDescriptor memTableInfo = memTable.flush(transactionId);
		final MemTable loadedMemTable = new MemTable(
			targetFile,
			new MemTableDescriptor(
				memTableInfo.getFileLocation(),
				memTableInfo
			),
			options,
			memTableRecordTypeRegistry,
			observableOutputKeeper
		);

		long duration = 0L;
		for (int i = 1; i <= recordCount; i++) {
			long start = System.nanoTime();
			final EntityBodyStoragePart actual = memTable.get(i, EntityBodyStoragePart.class);
			duration += System.nanoTime() - start;
			assertEquals(
				new EntityBodyStoragePart(i),
				actual
			);
		}

		observableOutputKeeper.free();

		assertTrue(memTable.memTableEquals(loadedMemTable));
		/* 600 records +1 record for th MemTable itself */
		assertEquals(601, memTable.verifyContents().getRecordCount());
		log.info("Average reads: " + StringUtils.formatRequestsPerSec(recordCount, duration));
	}

	@DisplayName("Existing record can be removed")
	@Test
	void shouldRemoveRecord() {
		final StorageOptions options = StorageOptions.temporary();
		final ObservableOutputKeeper observableOutputKeeper = new ObservableOutputKeeper(options);
		observableOutputKeeper.prepare();

		// store 300 records in multiple chunks,
		final int recordCount = 50;
		final int removedRecords = 10;
		final int iterationCount = 6;

		final InsertionOutput insertionResult = createRecordsInMemTable(
			options, observableOutputKeeper, recordCount, removedRecords, iterationCount
		);

		final MemTableDescriptor memTableInfo = insertionResult.descriptor();

		final MemTable loadedMemTable = new MemTable(
			targetFile,
			new MemTableDescriptor(
				memTableInfo.getFileLocation(),
				memTableInfo
			),
			options,
			memTableRecordTypeRegistry,
			observableOutputKeeper
		);

		for (int i = 1; i <= recordCount * iterationCount; i++) {
			final EntityBodyStoragePart actual = loadedMemTable.get(i, EntityBodyStoragePart.class);
			if (i < recordCount * (iterationCount - 1) && i % recordCount < removedRecords && i % recordCount > 0) {
				assertNull(actual);
			} else {
				assertEquals(
					new EntityBodyStoragePart(i),
					actual
				);
			}
		}

		observableOutputKeeper.free();

		assertTrue(insertionResult.memTable().memTableEquals(loadedMemTable));
		/* 300 records +6 record for th MemTable itself */
		assertEquals(306, loadedMemTable.verifyContents().getRecordCount());
	}

	@Test
	void shouldReadBinaryRecordAndDeserializeManually() {
		final StorageOptions options = StorageOptions.temporary();
		final ObservableOutputKeeper observableOutputKeeper = new ObservableOutputKeeper(options);
		observableOutputKeeper.prepare();

		// store 300 records in multiple chunks,
		final int recordCount = 50;
		final int removedRecords = 10;
		final int iterationCount = 6;

		final InsertionOutput insertionResult = createRecordsInMemTable(
			options, observableOutputKeeper, recordCount, removedRecords, iterationCount
		);

		final MemTableDescriptor memTableInfo = insertionResult.descriptor();

		final MemTable loadedMemTable = new MemTable(
			targetFile,
			new MemTableDescriptor(
				memTableInfo.getFileLocation(),
				memTableInfo
			),
			options,
			memTableRecordTypeRegistry,
			observableOutputKeeper
		);

		for (int i = 1; i <= recordCount * iterationCount; i++) {
			final byte[] actualBinary = loadedMemTable.getBinary(i, EntityBodyStoragePart.class);
			if (i < recordCount * (iterationCount - 1) && i % recordCount < removedRecords && i % recordCount > 0) {
				assertNull(actualBinary);
			} else {
				assertNotNull(actualBinary);
				final VersionedKryo kryo = createKryo()
					.apply(
						new VersionedKryoKeyInputs(
							loadedMemTable.getReadOnlyKeyCompressor(), 1
						)
					);
				assertEquals(
					new EntityBodyStoragePart(i),
					kryo.readObject(new Input(actualBinary), EntityBodyStoragePart.class)
				);
			}
		}

		observableOutputKeeper.free();

		assertTrue(insertionResult.memTable().memTableEquals(loadedMemTable));
		/* 300 records +6 record for th MemTable itself */
		assertEquals(306, loadedMemTable.verifyContents().getRecordCount());
	}

	@DisplayName("No operation should be allowed after close")
	@Test
	void shouldRefuseOperationAfterClose() {
		final StorageOptions options = StorageOptions.temporary();
		final ObservableOutputKeeper outputKeeper = new ObservableOutputKeeper(options);
		outputKeeper.prepare();

		final MemTable memTable = new MemTable(
			targetFile,
			new MemTableDescriptor(
				new EntityCollectionHeader(ENTITY_TYPE, 1),
				createKryo(),
				false
			),
			options,
			memTableRecordTypeRegistry,
			outputKeeper
		);
		memTable.put(0L, new EntityBodyStoragePart(1));
		memTable.close();
		outputKeeper.free();

		assertThrows(IllegalStateException.class, () -> memTable.get(1, EntityBodyStoragePart.class));
		assertThrows(IllegalStateException.class, () -> memTable.put(0L, new EntityBodyStoragePart(2)));
		assertThrows(IllegalStateException.class, memTable::getEntries);
		assertThrows(IllegalStateException.class, memTable::getKeys);
		assertThrows(IllegalStateException.class, memTable::getFileLocations);
		assertThrows(IllegalStateException.class, () -> memTable.flush(0L));
	}

	@ParameterizedTest(name = "MemTable should survive generational randomized test applying modifications on it")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		final StorageOptions options = StorageOptions.temporary();
		final ObservableOutputKeeper observableOutputKeeper = new ObservableOutputKeeper(options);
		observableOutputKeeper.prepare();

		final MemTable memTable = new MemTable(
			targetFile,
			new MemTableDescriptor(
				new EntityCollectionHeader(ENTITY_TYPE, 1),
				createKryo(),
				false
			),
			options,
			memTableRecordTypeRegistry,
			observableOutputKeeper
		);

		observableOutputKeeper.free();

		Set<Integer> recordIds = new HashSet<>();

		runFor(
			input,
			new TestState(1L, 1),
			(random, testState) -> {
				observableOutputKeeper.prepare();

				final int recordCount = random.nextInt(10_000);
				final List<RecordOperation> plannedOps = new ArrayList<>(recordCount);
				final Set<Integer> touchedInThisRound = new HashSet<>();
				for (int i = 1; i <= recordCount; i++) {
					final int rndOp = random.nextInt(3);
					final RecordOperation operation;
					if (recordIds.isEmpty() || (rndOp == 0 && recordIds.size() < 100_000)) {
						operation = new RecordOperation(getNonExisting(recordIds, touchedInThisRound, random), Operation.INSERT);
						recordIds.add(operation.getRecordId());
					} else if (recordIds.size() - touchedInThisRound.size() > 10_000 && rndOp == 1) {
						operation = new RecordOperation(getExisting(recordIds, touchedInThisRound, random), Operation.UPDATE);
					} else if (recordIds.size() - touchedInThisRound.size() > 10_000 && rndOp == 2) {
						operation = new RecordOperation(getExisting(recordIds, touchedInThisRound, random), Operation.REMOVE);
						recordIds.remove(operation.getRecordId());
					} else {
						continue;
					}
					touchedInThisRound.add(operation.getRecordId());
					plannedOps.add(operation);
				}

				long transactionId = testState.transactionId();
				for (RecordOperation plannedOp : plannedOps) {
					switch (plannedOp.getOperation()) {
						case INSERT -> {
							memTable.put(transactionId, new EntityBodyStoragePart(plannedOp.getRecordId()));
							plannedOp.setVersion(1);
						}
						case UPDATE -> {
							final EntityBodyStoragePart existingContainer = memTable.get(plannedOp.getRecordId(), EntityBodyStoragePart.class);
							Assert.notNull(existingContainer, "The container with id " + plannedOp.getRecordId() + " unexpectedly not found!");
							memTable.put(transactionId, new EntityBodyStoragePart(existingContainer.getVersion() + 1, existingContainer.getPrimaryKey(), existingContainer.getHierarchicalPlacement(), existingContainer.getLocales(), existingContainer.getAttributeLocales(), existingContainer.getAssociatedDataKeys()));
							plannedOp.setVersion(existingContainer.getVersion() + 1);
						}
						case REMOVE -> {
							memTable.remove(plannedOp.getRecordId(), EntityBodyStoragePart.class);
						}
					}
				}

				final MemTableDescriptor memTableInfo = memTable.flush(transactionId++);

				long start = System.nanoTime();
				final MemTable loadedMemTable = new MemTable(
					targetFile,
					new MemTableDescriptor(
						memTableInfo.getFileLocation(),
						memTableInfo
					),
					options,
					memTableRecordTypeRegistry,
					observableOutputKeeper
				);
				long end = System.nanoTime();

				observableOutputKeeper.free();

				assertTrue(memTable.memTableEquals(loadedMemTable));

				final MemTableFileStatistics stats = memTable.verifyContents();
				for (RecordOperation plannedOp : plannedOps) {
					final EntityBodyStoragePart entityStorageContainer = memTable.get(plannedOp.getRecordId(), EntityBodyStoragePart.class);
					if (plannedOp.getOperation() == Operation.REMOVE) {
						Assert.isTrue(entityStorageContainer == null, "Cnt " + plannedOp.getRecordId() + " should be null but was not!");
					} else {
						Assert.notNull(entityStorageContainer, "Cnt " + plannedOp.getRecordId() + " was not found!");
						assertEquals(plannedOp.getVersion(), entityStorageContainer.getVersion());
					}
				}

				System.out.println(
					"Round trip #" + testState.roundTrip() + " (loaded in " +
						StringUtils.formatNano(end - start) + ", " + loadedMemTable.count() +
						" living recs. / " + stats.getRecordCount() + " total recs.)"
				);

				return new TestState(
					transactionId, testState.roundTrip() + 1
				);
			}
		);
	}

	private InsertionOutput createRecordsInMemTable(
		StorageOptions options,
		ObservableOutputKeeper observableOutputKeeper,
		int recordCount,
		int removedRecords,
		int iterationCount
	) {
		final MemTable memTable = new MemTable(
			targetFile,
			new MemTableDescriptor(
				new EntityCollectionHeader(ENTITY_TYPE, 1),
				createKryo(),
				false
			),
			options,
			memTableRecordTypeRegistry,
			observableOutputKeeper
		);

		MemTableDescriptor memTableInfo = null;

		long transactionId = 0;
		for (int j = 0; j < iterationCount; j++) {
			transactionId++;
			if (j > 0) {
				for (int i = 1; i < removedRecords; i++) {
					final int primaryKey = i + (j - 1) * recordCount;
					log.info("Removal of rec with PK:   " + primaryKey);
					memTable.remove(primaryKey, EntityBodyStoragePart.class);
				}
			}
			for (int i = 1; i <= recordCount; i++) {
				final int primaryKey = j * recordCount + i;
				log.info("Insertion of rec with PK (tx " + transactionId + "): " + primaryKey);
				memTable.put(
					transactionId,
					new EntityBodyStoragePart(primaryKey));
			}

			log.info("Flushing table (tx " + transactionId + ")");
			memTableInfo = memTable.flush(transactionId);
		}

		return new InsertionOutput(memTable, Objects.requireNonNull(memTableInfo));
	}

	private int getNonExisting(Set<Integer> recordIds, Set<Integer> touchedInThisRound, Random random) {
		int recPrimaryKey;
		do {
			recPrimaryKey = Math.abs(random.nextInt());
		} while (recPrimaryKey != 0 && (recordIds.contains(recPrimaryKey) || touchedInThisRound.contains(recPrimaryKey)));
		return recPrimaryKey;
	}

	private int getExisting(Set<Integer> recordIds, Set<Integer> touchedInThisRound, Random random) {
		final Iterator<Integer> it = recordIds.iterator();
		final int bound = recordIds.size() - 1;
		if (bound > 0) {
			final int steps = random.nextInt(bound);
			for (int i = 0; i < steps; i++) {
				it.next();
			}
		}
		final Integer adept = it.next();
		// retry if this id was picked already in this round
		return touchedInThisRound.contains(adept) ? getExisting(recordIds, touchedInThisRound, random) : adept;
	}

	@Nonnull
	private Function<VersionedKryoKeyInputs, VersionedKryo> createKryo() {
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

	private enum Operation {
		INSERT, UPDATE, REMOVE
	}

	@Data
	private static class RecordOperation {
		private final int recordId;
		private final Operation operation;
		private int version;

	}

	private record InsertionOutput(@Nonnull MemTable memTable, @Nonnull MemTableDescriptor descriptor) {}

	private record TestState(long transactionId, int roundTrip) {
	}
}