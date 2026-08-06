/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.store.catalog;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.CatalogState;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.core.buffer.WarmUpDataStoreMemoryBuffer;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.export.file.ExportFileService;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.spi.store.catalog.persistence.storageParts.schema.CatalogSchemaStoragePart;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getCatalogBootstrapFileName;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a configured checkpoint interval actually defers the bootstrap record, and that the two version
 * accessors keep telling apart "written" from "durable" while it does.
 *
 * The distinction is the whole point of the design and is easy to get wrong in either direction: reporting the
 * applied version as persisted would make a pre-durability failure look like a post-durability one, while deciding
 * whether anything changed from the persisted version would make every round between two checkpoints look dirty.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Deferred checkpoint persistence")
@Tag(STORAGE)
@Tag(TRANSACTION)
class DeferredCheckpointPersistenceTest implements EvitaTestSupport {
	private static final String DIR_DEFERRED_CHECKPOINT_TEST = "deferredCheckpointTest";
	private static final String TX_DIR_DEFERRED_CHECKPOINT_TEST = "deferredCheckpointTest_tx";
	private static final String CATALOG_NAME = "deferredCheckpointCatalog";
	/**
	 * Long enough that no checkpoint can fire on its own during the test - every checkpoint observed here is one the
	 * test deliberately provoked.
	 */
	private static final long NEVER_ELAPSES_MILLIS = 3_600_000L;
	private static final CatalogSchema CATALOG_SCHEMA = CatalogSchema._internalBuild(
		CATALOG_NAME, NamingConvention.generate(CATALOG_NAME), null,
		EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE
	);

	@BeforeEach
	void setUp() {
		getTestDirectory().resolve(DIR_DEFERRED_CHECKPOINT_TEST).toFile().mkdirs();
	}

	@AfterEach
	void tearDown() throws IOException {
		cleanTestSubDirectory(DIR_DEFERRED_CHECKPOINT_TEST);
		cleanTestSubDirectory(TX_DIR_DEFERRED_CHECKPOINT_TEST);
	}

	@Test
	@DisplayName("should not advance the persisted version while a checkpoint is deferred")
	void shouldNotAdvancePersistedVersionWhileDeferred() {
		try (final DefaultCatalogPersistenceService cps = createService(NEVER_ELAPSES_MILLIS)) {
			assertNotNull(
				cps.getCheckpointCoordinator(),
				"Precondition: a positive interval with sync writes on must create a coordinator, or this test " +
					"would assert deferral against a service that never defers."
			);
			cps.getStoragePartPersistenceService(0L)
				.putStoragePart(0L, new CatalogSchemaStoragePart(CATALOG_SCHEMA));

			final long versionBeforeStore = cps.getLastCatalogVersion();
			storeHeaderAt(cps, 2L);

			assertEquals(
				2L, cps.getLastAppliedCatalogVersion(),
				"The applied version must advance as soon as the header is written."
			);
			assertEquals(
				versionBeforeStore, cps.getLastCatalogVersion(),
				"The persisted version must NOT advance while the checkpoint is still deferred - the bootstrap " +
					"record pointing at that version has not been written yet."
			);
		}
	}

	@Test
	@DisplayName("should advance the persisted version once a checkpoint is due")
	void shouldAdvancePersistedVersionOnceCheckpointDue() throws InterruptedException {
		// a tiny interval so the SECOND round finds the checkpoint due and settles the debt inline
		try (final DefaultCatalogPersistenceService cps = createService(50L)) {
			cps.getStoragePartPersistenceService(0L)
				.putStoragePart(0L, new CatalogSchemaStoragePart(CATALOG_SCHEMA));

			storeHeaderAt(cps, 2L);
			Thread.sleep(120);
			storeHeaderAt(cps, 3L);

			assertEquals(
				3L, cps.getLastAppliedCatalogVersion(),
				"The applied version must track the newest written header."
			);
			assertEquals(
				3L, cps.getLastCatalogVersion(),
				"The round that found the interval elapsed must have checkpointed, advancing the persisted version."
			);
		}
	}

	@Test
	@DisplayName("should publish an owed checkpoint on close so a clean restart need not replay it")
	void shouldCheckpointPendingSyncsOnClose() {
		try (DefaultCatalogPersistenceService cps = createService(NEVER_ELAPSES_MILLIS)) {
			cps.getStoragePartPersistenceService(0L)
				.putStoragePart(0L, new CatalogSchemaStoragePart(CATALOG_SCHEMA));
			storeHeaderAt(cps, 2L);
			assertTrue(
				cps.getLastCatalogVersion() < cps.getLastAppliedCatalogVersion(),
				"Precondition: the checkpoint must still be outstanding."
			);
		}
		// must not throw - close settles the outstanding device flushes rather than abandoning them

		// The round that deferred had already built its bootstrap record and left the index at its own version, so
		// close publishes that state as it stands. It waits for nothing and drains nothing - `TransactionManager`
		// fails pending transactions on close rather than finishing them, so anything newer than the last completed
		// round remains the write-ahead log's business. What this buys is that a clean restart resumes here instead
		// of replaying from whichever checkpoint the ticker last happened to reach.
		try (final DefaultCatalogPersistenceService reopened = createLoadingService(NEVER_ELAPSES_MILLIS)) {
			assertEquals(
				2L, reopened.getLastCatalogVersion(),
				"The checkpoint owed when the service closed must have been published, so the restart resumes from it."
			);
		}
	}

	@Test
	@DisplayName("should write no bootstrap record on close when no checkpoint is owed")
	void shouldNotCheckpointOnCloseWhenNothingIsOwed() {
		final Path bootstrapFile = getTestDirectory()
			.resolve(DIR_DEFERRED_CHECKPOINT_TEST)
			.resolve(CATALOG_NAME)
			.resolve(getCatalogBootstrapFileName(CATALOG_NAME));

		final long lengthBeforeClose;
		// a real interval, so a coordinator genuinely exists - interval 0 creates none at all, and closing without
		// one would prove nothing about whether close checkpoints only when it is owed
		try (final DefaultCatalogPersistenceService cps = createService(NEVER_ELAPSES_MILLIS)) {
			cps.getStoragePartPersistenceService(0L)
				.putStoragePart(0L, new CatalogSchemaStoragePart(CATALOG_SCHEMA));
			storeHeaderAt(cps, 2L);
			final long lengthBeforeCheckpoint = bootstrapFile.toFile().length();
			// settle the debt before closing, exactly as the ticker or a backup would
			cps.checkpoint();
			assertEquals(
				2L, cps.getLastCatalogVersion(),
				"Precondition: the checkpoint must be settled, leaving nothing owed at close."
			);
			lengthBeforeClose = bootstrapFile.toFile().length();
			// `getLastCatalogVersion` answers from the in-memory `bootstrapUsed`, so on its own it cannot tell
			// "the record was written" from "the service merely thinks so". Proving the checkpoint above grew the
			// file is what makes the comparison after close meaningful: it establishes that this measurement
			// detects an appended record, so an unchanged length afterwards is evidence of no append rather than
			// evidence of a file nothing ever writes to.
			assertTrue(
				lengthBeforeClose > lengthBeforeCheckpoint,
				"Precondition: settling a checkpoint must append a bootstrap record."
			);
		}

		// the calibration for the test above: close publishes what is OWED, and a settled checkpoint owes nothing.
		// Records here are fixed-size, so an unchanged length is an unchanged record count - without this assertion
		// the test above would pass just as happily if close wrote a bootstrap record unconditionally.
		assertEquals(
			lengthBeforeClose, bootstrapFile.toFile().length(),
			"Close must not append a bootstrap record when the last round already checkpointed."
		);
	}

	@Test
	@DisplayName("should settle an owed checkpoint while a newer round is still writing the catalog index")
	void shouldSettleOwedCheckpointWhileNewerRoundWritesCatalogIndex() {
		try (final DefaultCatalogPersistenceService cps = createService(NEVER_ELAPSES_MILLIS)) {
			cps.getStoragePartPersistenceService(0L)
				.putStoragePart(0L, new CatalogSchemaStoragePart(CATALOG_SCHEMA));

			// a round finishes and defers its checkpoint
			storeHeaderAt(cps, 2L);

			// the next round starts: `flushTrappedUpdates` stamps catalog-level storage parts with ITS version and
			// writes them into the very same catalog offset index, well before it gets as far as storing its header.
			// This is not a contrived interleaving - it is what every round does, and it is the reason the offset
			// index must be advanced by the round itself rather than by whoever happens to checkpoint later.
			cps.getStoragePartPersistenceService(3L)
				.putStoragePart(3L, new CatalogSchemaStoragePart(CATALOG_SCHEMA));

			// a backup, an integrity check or the ticker now settles the outstanding debt from its own thread
			assertDoesNotThrow(
				cps::checkpoint,
				"A checkpoint must never be built from the catalog offset index as a newer round left it - the " +
					"bootstrap record would name one version while addressing another."
			);

			assertEquals(
				2L, cps.getLastCatalogVersion(),
				"The checkpoint must publish the version that was actually applied, not the one a round in flight " +
					"has merely started writing."
			);

			// the in-flight round must still be able to finish - the checkpoint may not have consumed state it owns
			assertDoesNotThrow(
				() -> storeHeaderAt(cps, 3L),
				"The round that was in flight during the checkpoint must still be able to store its header."
			);
		}
	}

	@Test
	@DisplayName("should never defer a checkpoint while the catalog is warming up")
	void shouldNeverDeferCheckpointDuringWarmUp() {
		try (final DefaultCatalogPersistenceService cps = createService(NEVER_ELAPSES_MILLIS)) {
			cps.getStoragePartPersistenceService(0L)
				.putStoragePart(0L, new CatalogSchemaStoragePart(CATALOG_SCHEMA));

			storeHeaderAt(cps, CatalogState.WARMING_UP, 2L);

			// warm-up has no trunk rounds to amortise a checkpoint across, and going live needs everything written
			// during it to be addressable from disk before the first transaction runs
			assertEquals(
				cps.getLastAppliedCatalogVersion(), cps.getLastCatalogVersion(),
				"A warm-up flush must checkpoint immediately, never defer."
			);
		}
	}

	@Test
	@DisplayName("should not defer anything when writes are never flushed to the device")
	void shouldNotDeferCheckpointWhenSyncWritesDisabled() {
		// with sync writes off there is no device flush to defer, so no coordinator is created at all - the two
		// settings stay orthogonal instead of one silently re-enabling the other
		try (final DefaultCatalogPersistenceService cps = createService(NEVER_ELAPSES_MILLIS, false)) {
			assertNull(
				cps.getCheckpointCoordinator(),
				"No checkpoint coordinator may be created when writes never reach the device."
			);

			cps.getStoragePartPersistenceService(0L)
				.putStoragePart(0L, new CatalogSchemaStoragePart(CATALOG_SCHEMA));

			storeHeaderAt(cps, 2L);

			assertEquals(
				2L, cps.getLastCatalogVersion(),
				"With sync writes disabled every round must checkpoint, regardless of the configured interval."
			);
		}
	}

	@Test
	@DisplayName("should checkpoint every round when the interval is switched off")
	void shouldCheckpointEveryRoundWhenIntervalIsZero() {
		// zero is the documented off-switch, and what `TransactionOptions.temporary()` relies on
		try (final DefaultCatalogPersistenceService cps = createService(0L)) {
			assertNull(
				cps.getCheckpointCoordinator(),
				"Interval 0 must be expressed by having no coordinator at all, not by a coordinator that never defers."
			);

			cps.getStoragePartPersistenceService(0L)
				.putStoragePart(0L, new CatalogSchemaStoragePart(CATALOG_SCHEMA));

			storeHeaderAt(cps, 2L);
			assertEquals(2L, cps.getLastCatalogVersion(), "Interval 0 must checkpoint at the end of every round.");

			storeHeaderAt(cps, 3L);
			assertEquals(3L, cps.getLastCatalogVersion(), "Interval 0 must checkpoint at the end of every round.");
		}
	}

	@Test
	@DisplayName("should settle an owed checkpoint before verifying integrity")
	void shouldSettleOwedCheckpointBeforeVerifyingIntegrity() {
		try (final DefaultCatalogPersistenceService cps = createService(NEVER_ELAPSES_MILLIS)) {
			cps.getStoragePartPersistenceService(0L)
				.putStoragePart(0L, new CatalogSchemaStoragePart(CATALOG_SCHEMA));

			storeHeaderAt(cps, 2L);
			assertTrue(
				cps.getLastCatalogVersion() < 2L,
				"Precondition: the checkpoint must still be outstanding."
			);

			// integrity verification asserts the catalog is fully checkpointed AND its callers follow it with
			// `purgeAllObsoleteFiles()` - purging write-ahead log files covering versions that were never
			// checkpointed would discard the only record of them
			assertDoesNotThrow(cps::verifyIntegrity, "Verifying integrity must settle an outstanding checkpoint.");

			assertEquals(
				2L, cps.getLastCatalogVersion(),
				"Integrity verification must leave the catalog fully checkpointed."
			);
		}
	}

	@Test
	@DisplayName("should build on the compacted file index when a compaction happened inside a deferred round")
	void shouldBuildOnCompactedFileIndexWhenCompactionHappenedInDeferredRound() {
		// Compaction bumps the catalog file index INSIDE the record a deferred round prepared, while `bootstrapUsed`
		// still names the file from before it. The next round must therefore build on the prepared record, not on
		// `bootstrapUsed` - otherwise it reuses an index the previous round already took and the published record
		// ends up naming a file that is not the one holding its data.
		try (final DefaultCatalogPersistenceService cps = createService(NEVER_ELAPSES_MILLIS, true, true)) {
			assertNotNull(cps.getCheckpointCoordinator(), "Precondition: deferral must be active.");

			cps.getStoragePartPersistenceService(0L)
				.putStoragePart(0L, new CatalogSchemaStoragePart(CATALOG_SCHEMA));

			// first deferred round - compacts, so its prepared record carries file index 1
			storeHeaderAt(cps, 2L);
			assertTrue(
				catalogDataFileExists(1),
				"Precondition: the first round must actually have compacted, or this test proves nothing."
			);
			assertTrue(
				cps.getLastCatalogVersion() < 2L,
				"Precondition: the first round's checkpoint must still be outstanding."
			);

			// second deferred round - must build on index 1 and produce 2. Building on `bootstrapUsed` would
			// recompute index 1 and collide with the file the previous round just wrote.
			cps.getStoragePartPersistenceService(0L)
				.putStoragePart(0L, new CatalogSchemaStoragePart(CATALOG_SCHEMA));
			storeHeaderAt(cps, 3L);

			assertTrue(
				catalogDataFileExists(2),
				"The second deferred round must build on the file index its predecessor prepared, not on the one " +
					"the last written bootstrap record still names."
			);

			cps.checkpoint();
			assertEquals(
				3L, cps.getLastCatalogVersion(),
				"The settled checkpoint must publish the newest prepared record."
			);
		}

		// the published record must address a catalog file that exists, and reopening must accept it
		try (final DefaultCatalogPersistenceService reopened = createLoadingService(NEVER_ELAPSES_MILLIS)) {
			assertEquals(
				3L, reopened.getLastCatalogVersion(),
				"A reopened catalog must load from the compacted file the published record names."
			);
		}
	}

	@Test
	@DisplayName("should refuse to store a header once checkpointing has failed")
	void shouldRefuseStoreHeaderWhenCheckpointFailed() {
		try (final DefaultCatalogPersistenceService cps = createService(NEVER_ELAPSES_MILLIS)) {
			cps.getStoragePartPersistenceService(0L)
				.putStoragePart(0L, new CatalogSchemaStoragePart(CATALOG_SCHEMA));

			// a real round defers first - the state a checkpoint failure is actually discovered in
			storeHeaderAt(cps, 2L);

			final CheckpointCoordinator coordinator = cps.getCheckpointCoordinator();
			assertNotNull(coordinator, "Precondition: a deferring service must have a coordinator.");
			final IllegalStateException checkpointFailure = new IllegalStateException("device is gone");
			recordCheckpointFailure(coordinator, checkpointFailure);
			assertSame(
				checkpointFailure, coordinator.getFailure(),
				"Precondition: the failure must be visible to the persistence service."
			);

			final UnexpectedIOException thrown = assertThrows(
				UnexpectedIOException.class,
				() -> storeHeaderAt(cps, 3L),
				"A catalog that can no longer checkpoint kept accepting headers - each one is an acknowledgement " +
					"it can never make durable."
			);
			assertSame(
				checkpointFailure, thrown.getCause(),
				"The original failure must reach whoever suspends the catalog, not be replaced by it."
			);
			assertEquals(
				2L, cps.getLastAppliedCatalogVersion(),
				"The refusal must come before anything is written - a header that got as far as advancing the " +
					"applied version was accepted, not refused."
			);
		}
	}

	/**
	 * Records a checkpoint failure on the coordinator the way a failed ticker checkpoint would.
	 *
	 * Reached through the field rather than through the ticker because the service here is built with a mocked
	 * scheduler - which is what makes every test in this class deterministic - so no ticker ever fires. That the
	 * ticker records what it catches is covered by `CheckpointCoordinatorTest`; what is asserted here is only what
	 * the persistence service does once the failure is there.
	 *
	 * @param coordinator the coordinator to poison
	 * @param failure     the failure to record
	 */
	@SuppressWarnings("unchecked")
	private static void recordCheckpointFailure(
		@Nonnull CheckpointCoordinator coordinator,
		@Nonnull Throwable failure
	) {
		try {
			final Field field = CheckpointCoordinator.class.getDeclaredField("failure");
			field.setAccessible(true);
			((AtomicReference<Throwable>) field.get(coordinator)).set(failure);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("Failed to record the checkpoint failure", ex);
		}
	}

	/**
	 * Tells whether the catalog data file with the given index exists on disk.
	 *
	 * @param fileIndex the catalog file index to look for
	 * @return true when the file exists
	 */
	private boolean catalogDataFileExists(int fileIndex) {
		return getTestDirectory()
			.resolve(DIR_DEFERRED_CHECKPOINT_TEST)
			.resolve(CATALOG_NAME)
			.resolve(CatalogPersistenceService.getCatalogDataStoreFileName(CATALOG_NAME, fileIndex))
			.toFile()
			.exists();
	}

	/**
	 * Writes a catalog header for the given version in the ALIVE state - the state deferral applies to.
	 */
	private static void storeHeaderAt(@Nonnull DefaultCatalogPersistenceService cps, long catalogVersion) {
		storeHeaderAt(cps, CatalogState.ALIVE, catalogVersion);
	}

	/**
	 * Writes a catalog header for the given version in the given catalog state.
	 */
	private static void storeHeaderAt(
		@Nonnull DefaultCatalogPersistenceService cps,
		@Nonnull CatalogState catalogState,
		long catalogVersion
	) {
		cps.storeHeader(
			UUID.randomUUID(),
			catalogState,
			catalogVersion,
			0,
			null,
			Collections.emptyList(),
			new WarmUpDataStoreMemoryBuffer(cps.getStoragePartPersistenceService(0L))
		);
	}

	@Nonnull
	private DefaultCatalogPersistenceService createService(long checkpointIntervalMillis) {
		return createService(checkpointIntervalMillis, true);
	}

	@Nonnull
	private DefaultCatalogPersistenceService createService(long checkpointIntervalMillis, boolean syncWrites) {
		return createService(checkpointIntervalMillis, syncWrites, false);
	}

	@Nonnull
	private DefaultCatalogPersistenceService createService(
		long checkpointIntervalMillis,
		boolean syncWrites,
		boolean compactEagerly
	) {
		return new DefaultCatalogPersistenceService(
			CATALOG_NAME,
			new CatalogFolderId(CATALOG_NAME),
			storageOptions(syncWrites, compactEagerly),
			transactionOptions(checkpointIntervalMillis),
			Mockito.mock(Scheduler.class),
			Mockito.mock(ExportFileService.class)
		);
	}

	@Nonnull
	private DefaultCatalogPersistenceService createLoadingService(long checkpointIntervalMillis) {
		return new DefaultCatalogPersistenceService(
			Mockito.mock(CatalogContract.class),
			CATALOG_NAME,
			new CatalogFolderId(CATALOG_NAME),
			storageOptions(),
			transactionOptions(checkpointIntervalMillis),
			Mockito.mock(Scheduler.class),
			Mockito.mock(ExportFileService.class)
		);
	}

	@Nonnull
	private StorageOptions storageOptions() {
		// syncWrites MUST be true - with it off there is no device flush to defer and no coordinator is created
		return storageOptions(true);
	}

	/**
	 * Storage settings for the test catalog.
	 *
	 * Built through the builder rather than the positional constructor deliberately: the tests below differ from
	 * each other in one field at a time, which a twelve-argument call renders unreadable and any new field breaks.
	 *
	 * @param syncWrites whether writes are flushed to the device at all - with this off no coordinator is created
	 *                   and nothing is ever deferred
	 * @return the storage settings
	 */
	@Nonnull
	private StorageOptions storageOptions(boolean syncWrites) {
		return storageOptions(syncWrites, false);
	}

	/**
	 * Storage settings for the test catalog.
	 *
	 * @param syncWrites    whether writes are flushed to the device at all - with this off no coordinator is created
	 *                      and nothing is ever deferred
	 * @param compactEagerly when true the catalog-file compaction thresholds are opened so that every prepared
	 *                      bootstrap record compacts; `shouldCompact` fires on `activeRecordShare < maxWasteActiveShare`
	 *                      alone, so no compaction-cadence gate has to be defeated
	 * @return the storage settings
	 */
	@Nonnull
	private StorageOptions storageOptions(boolean syncWrites, boolean compactEagerly) {
		final Path directory = getTestDirectory().resolve(DIR_DEFERRED_CHECKPOINT_TEST);
		return StorageOptions.builder()
			.storageDirectory(directory)
			.workDirectory(directory)
			.lockTimeoutSeconds(60)
			.waitOnCloseSeconds(60)
			.outputBufferSize(StorageOptions.DEFAULT_OUTPUT_BUFFER_SIZE)
			.maxOpenedReadHandles(1)
			.syncWrites(syncWrites)
			.compress(false)
			.computeCRC32(true)
			.minimalActiveRecordShare(compactEagerly ? 1.0 : 0.01)
			.maxWasteActiveShare(compactEagerly ? 1.1 : StorageOptions.DEFAULT_MAX_WASTE_ACTIVE_SHARE)
			.fileSizeCompactionThresholdBytes(compactEagerly ? 1L : 1_000_000L)
			.minCompactionIntervalMilliseconds(compactEagerly ? 0L : StorageOptions.DEFAULT_MIN_COMPACTION_INTERVAL_MILLISECONDS)
			.timeTravelEnabled(false)
			.build();
	}

	@Nonnull
	private TransactionOptions transactionOptions(long checkpointIntervalMillis) {
		return TransactionOptions.builder()
			.transactionWorkDirectory(getTestDirectory().resolve(TX_DIR_DEFERRED_CHECKPOINT_TEST))
			.checkpointIntervalInMillis(checkpointIntervalMillis)
			.build();
	}

}
