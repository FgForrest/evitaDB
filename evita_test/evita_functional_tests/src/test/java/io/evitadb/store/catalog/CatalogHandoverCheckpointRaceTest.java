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

import io.evitadb.api.CatalogState;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.core.buffer.WarmUpDataStoreMemoryBuffer;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.export.file.ExportFileService;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.schema.CatalogSchemaStoragePart;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.store.model.header.EntityCollectionFileHeader;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.offsetIndex.io.ReadOnlyHandle;
import io.evitadb.store.offsetIndex.io.WriteOnlyHandle;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.getCatalogBootstrapFileName;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that relabelling a catalog survives a checkpoint the ticker publishes while the relabel is writing its own
 * bootstrap record.
 *
 * A round running under a checkpoint interval leaves a prepared bootstrap record behind, and that record addresses the
 * catalog header as it stood at that round. The relabel then writes a header carrying the incoming name, publishes a
 * record pointing at it and reopens the folder from the last record of the bootstrap file. That file is read back by
 * position rather than by version, and both records name the same version - so if the round's record lands behind the
 * relabel's, the reopened service finds the former name in the header and refuses the folder.
 *
 * The interleaving is forced rather than waited for, so one run decides the outcome:
 *
 * - **The ticker is the real one.** The deferring round arms it through the scheduler it was given, and the test runs
 *   what was armed on a thread of its own instead of waiting out the interval.
 * - **The moment is the fence of the relabel's bootstrap write.** A bootstrap record is written only after
 *   {@link CheckpointCoordinator#forcePendingSyncs()}, with the write lock of the bootstrap file held. A handle
 *   registered as owing a device flush is forced right there, on the relabelling thread, so its
 *   {@link WriteOnlyHandle#forceDurable()} starts the ticker and lets the relabel go on only when the ticker is parked
 *   or finished. In production that moment lasts as long as the real device flush.
 *
 * A ticker that finishes without ever parking is accepted on purpose: a correct implementation may have retracted the
 * debt by then, leaving the ticker nothing to publish. What is asserted is the outcome, not who waited for whom.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Catalog relabel racing a deferred checkpoint on the ticker")
@Tag(STORAGE)
@Tag(TRANSACTION)
@Tag(MANAGEMENT)
class CatalogHandoverCheckpointRaceTest implements EvitaTestSupport {
	private static final String DIR_HANDOVER_CHECKPOINT_RACE_TEST = "catalogHandoverCheckpointRaceTest";
	private static final String TX_DIR_HANDOVER_CHECKPOINT_RACE_TEST = "catalogHandoverCheckpointRaceTest_tx";
	private static final String FORMER_NAME = "handoverFormerName";
	private static final String INCOMING_NAME = "handoverIncomingName";
	/**
	 * The version the deferring round is stored at and the relabel is performed at, so that the records of both name
	 * the same version and only their position in the bootstrap file tells them apart.
	 */
	private static final long ROUND_VERSION = 2L;
	/**
	 * Long enough that no checkpoint fires on its own during the test, and a value no other task of the service is
	 * scheduled with - it is what tells the ticker apart in {@link TickerCapturingExecutor}.
	 */
	private static final long CHECKPOINT_INTERVAL_MILLIS = 3_599_999L;
	/**
	 * Bound of every positive wait of the test body: the two joins and the spin in {@link TickerStartingHandle}. Each
	 * returns as soon as the awaited thread gets there, so it costs nothing on a passing run.
	 *
	 * An expired wait fails the test but does not end it: leaving the body closes the service, and that blocks on the
	 * checkpoint lock for as long as a stalled relabel holds it. What bounds that is the `@Timeout` of the test method,
	 * which is set above what these waits add up to, so that it fires only on a genuine hang.
	 */
	private static final int WAIT_SECONDS = 30;

	private final TickerCapturingExecutor executor = new TickerCapturingExecutor();

	/**
	 * Creates a daemon thread, so a fixture that gets stuck cannot keep the test JVM alive.
	 *
	 * @param task the task the thread runs
	 * @param name the name of the thread
	 * @return the thread, not started yet
	 */
	@Nonnull
	private static Thread newDaemonThread(@Nonnull Runnable task, @Nonnull String name) {
		final Thread thread = new Thread(task, name);
		thread.setDaemon(true);
		return thread;
	}

	/**
	 * Builds a schema of a catalog with no entity types.
	 *
	 * @param catalogName the name of the catalog
	 * @return the schema
	 */
	@Nonnull
	private static CatalogSchema catalogSchema(@Nonnull String catalogName) {
		return CatalogSchema._internalBuild(
			catalogName, NamingConvention.generate(catalogName), null,
			EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE
		);
	}

	@BeforeEach
	void setUp() {
		getStorageDirectory().toFile().mkdirs();
	}

	@AfterEach
	void tearDown() throws IOException {
		this.executor.shutdownNow();
		cleanTestSubDirectory(DIR_HANDOVER_CHECKPOINT_RACE_TEST);
		cleanTestSubDirectory(TX_DIR_HANDOVER_CHECKPOINT_RACE_TEST);
	}

	@Test
	@Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
	@DisplayName("should reopen under the new name when the ticker checkpoints during the relabel's bootstrap write")
	void shouldReopenUnderNewNameWhenTickerCheckpointsDuringBootstrapWriteOfRelabel() throws InterruptedException {
		try (final DefaultCatalogPersistenceService service = createService()) {
			service.getStoragePartPersistenceService(0L)
				.putStoragePart(0L, new CatalogSchemaStoragePart(catalogSchema(FORMER_NAME)));
			// the interval has not elapsed, so this round builds its bootstrap record and leaves it for the ticker
			service.storeHeader(
				UUID.randomUUID(),
				CatalogState.ALIVE,
				ROUND_VERSION,
				0,
				null,
				Collections.emptyList(),
				new WarmUpDataStoreMemoryBuffer(service.getStoragePartPersistenceService(0L))
			);

			final CheckpointCoordinator coordinator = service.getCheckpointCoordinator();
			assertNotNull(
				coordinator, "Precondition: a positive interval with sync writes on must create a coordinator."
			);
			assertTrue(
				service.getLastCatalogVersion() < service.getLastAppliedCatalogVersion(),
				"Precondition: the checkpoint of the round must still be owed, or the ticker has nothing to publish."
			);
			final Runnable ticker = this.executor.getTicker();
			assertNotNull(ticker, "Precondition: the round that deferred its checkpoint must have armed the ticker.");

			final Thread tickerThread = newDaemonThread(ticker, "checkpoint-ticker");
			final TickerStartingHandle tickerStartingHandle = new TickerStartingHandle(tickerThread);
			coordinator.noteSyncPending(tickerStartingHandle);
			final int recordsBefore = countBootstrapRecords();

			final FutureTask<
				CatalogPersistenceService<LogFileRecordReference, CollectionFileReference, EntityCollectionFileHeader>
			> relabel = new FutureTask<>(
				() -> service.replaceWith(
					ROUND_VERSION,
					INCOMING_NAME,
					NamingConvention.generate(INCOMING_NAME),
					catalogSchema(INCOMING_NAME),
					new WarmUpDataStoreMemoryBuffer(service.getStoragePartPersistenceService(0L)),
					(done, total) -> {
					}
				)
			);
			final Thread relabelThread = newDaemonThread(relabel, "catalog-relabel");
			relabelThread.start();

			// both threads are waited for before the outcome is judged, so that a failed relabel can be told apart
			// from a run that never reached the interleaving
			relabelThread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
			assertFalse(relabelThread.isAlive(), "The relabel did not finish in " + WAIT_SECONDS + " seconds.");
			tickerThread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
			assertFalse(tickerThread.isAlive(), "The ticker did not finish in " + WAIT_SECONDS + " seconds.");
			final String tickerWhereabouts = tickerStartingHandle.getTickerWhereabouts();
			assertNotNull(
				tickerWhereabouts,
				"The relabel must have reached its bootstrap write with the ticker parked or finished, or this run " +
					"exercised nothing."
			);

			// the relabel has ended by now, so reading its outcome cannot block
			try (
				final CatalogPersistenceService<
					LogFileRecordReference, CollectionFileReference, EntityCollectionFileHeader
				> relabelled = assertDoesNotThrow(
					() -> relabel.get(),
					() -> "The relabel must survive a ticker caught during its bootstrap write. The ticker was: " +
						tickerWhereabouts + ". Records the relabel and the ticker appended to the bootstrap file: " +
						(countBootstrapRecords() - recordsBefore) + "."
				)
			) {
				assertEquals(
					INCOMING_NAME,
					relabelled.getCatalogHeader(relabelled.getLastCatalogVersion()).catalogName(),
					"The folder must answer under the incoming name once the relabel has finished."
				);
			}
			assertNull(
				coordinator.getFailure(),
				"A ticker that ran into the relabel must not end up reporting that checkpointing is broken."
			);
		}
	}

	/**
	 * Returns the storage directory of the service, which the folder of the catalog is created in.
	 *
	 * @return the directory
	 */
	@Nonnull
	private Path getStorageDirectory() {
		return getTestDirectory().resolve(DIR_HANDOVER_CHECKPOINT_RACE_TEST);
	}

	/**
	 * Counts the records in the bootstrap file of the catalog. The file keeps the name it had at the start, because
	 * a relabel renames no file.
	 *
	 * @return the number of records
	 */
	private int countBootstrapRecords() {
		return CatalogBootstrap.getRecordCount(
			getStorageDirectory()
				.resolve(FORMER_NAME)
				.resolve(getCatalogBootstrapFileName(FORMER_NAME))
				.toFile()
				.length()
		);
	}

	/**
	 * Creates a new catalog in warm-up, wired to the scheduler that lets the test take hold of the ticker.
	 *
	 * @return the service
	 */
	@Nonnull
	private DefaultCatalogPersistenceService createService() {
		final Path directory = getStorageDirectory();
		return new DefaultCatalogPersistenceService(
			FORMER_NAME,
			new CatalogFolderId(FORMER_NAME),
			StorageOptions.builder()
				.storageDirectory(directory)
				.workDirectory(directory)
				// with sync writes off there is no device flush to defer, and consequently no coordinator
				.syncWrites(true)
				.timeTravelEnabled(false)
				.build(),
			TransactionOptions.builder()
				.transactionWorkDirectory(getTestDirectory().resolve(TX_DIR_HANDOVER_CHECKPOINT_RACE_TEST))
				.checkpointIntervalInMillis(CHECKPOINT_INTERVAL_MILLIS)
				.build(),
			new Scheduler(this.executor),
			Mockito.mock(ExportFileService.class)
		);
	}

	/**
	 * Real executor that remembers what the checkpoint ticker armed, so the test can run it at the chosen moment on
	 * a thread of its own. The real timer stays armed as well and never elapses within a test.
	 */
	private static final class TickerCapturingExecutor extends ScheduledThreadPoolExecutor {
		private final AtomicReference<Runnable> ticker = new AtomicReference<>();

		TickerCapturingExecutor() {
			super(
				1,
				task -> newDaemonThread(task, "handover-race-scheduler")
			);
		}

		/**
		 * Returns the body of the checkpoint ticker.
		 *
		 * @return the body, or null when no round has armed the ticker yet
		 */
		@Nullable
		Runnable getTicker() {
			return this.ticker.get();
		}

		@Nonnull
		@Override
		public ScheduledFuture<?> schedule(@Nonnull Runnable command, long delay, @Nonnull TimeUnit unit) {
			if (delay == CHECKPOINT_INTERVAL_MILLIS && unit == TimeUnit.MILLISECONDS) {
				this.ticker.set(command);
			}
			return super.schedule(command, delay, unit);
		}
	}

	/**
	 * Handle owing a device flush, whose flush starts the ticker and returns only once the ticker parked or finished.
	 *
	 * The coordinator forces it from the fence of the first bootstrap write after its registration, and removes it from
	 * the pending set beforehand, so it runs exactly once and on the relabelling thread. It never throws: anything
	 * thrown here would be reported as a failed handover and be mistaken for the very failure under test. A wait that
	 * expires leaves {@link #getTickerWhereabouts()} null instead, for the test to assert.
	 */
	private static final class TickerStartingHandle implements WriteOnlyHandle {
		private final Thread ticker;
		@Nullable private volatile String tickerWhereabouts;

		TickerStartingHandle(@Nonnull Thread ticker) {
			this.ticker = ticker;
		}

		/**
		 * Describes where a parked thread is, for the failure message. It lists every frame in the package of the
		 * coordinator and below, which includes the persistence service, and requires one of them to be in the
		 * coordinator itself, because that is what tells a thread parked on a checkpoint from one parked anywhere else.
		 *
		 * @param frames the stack of the thread
		 * @return the method names of those frames, innermost first, or null when none of them is in the coordinator
		 */
		@Nullable
		private static String describeCheckpointFrames(@Nonnull StackTraceElement[] frames) {
			final String checkpointPackage = CheckpointCoordinator.class.getPackageName();
			final StringBuilder description = new StringBuilder(128);
			boolean inCheckpoint = false;
			for (final StackTraceElement frame : frames) {
				if (frame.getClassName().startsWith(checkpointPackage)) {
					inCheckpoint = inCheckpoint || frame.getClassName().equals(CheckpointCoordinator.class.getName());
					if (!description.isEmpty()) {
						description.append(" <- ");
					}
					description.append(frame.getMethodName());
				}
			}
			return inCheckpoint ? description.toString() : null;
		}

		/**
		 * Tells where the ticker was when the relabel was let go on.
		 *
		 * @return the description, or null when the wait expired or the relabel never reached this handle
		 */
		@Nullable
		String getTickerWhereabouts() {
			return this.tickerWhereabouts;
		}

		@Override
		public void forceDurable() {
			this.ticker.start();
			final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
			while (System.nanoTime() < deadline) {
				final Thread.State state = this.ticker.getState();
				if (state == Thread.State.TERMINATED) {
					this.tickerWhereabouts = "finished without parking";
					return;
				}
				if (state == Thread.State.WAITING) {
					final String checkpointFrames = describeCheckpointFrames(this.ticker.getStackTrace());
					if (checkpointFrames != null) {
						this.tickerWhereabouts = "parked in " + checkpointFrames;
						return;
					}
				}
				Thread.onSpinWait();
			}
		}

		@Override
		public <T> T checkAndExecute(
			@Nonnull String operation,
			@Nonnull Runnable premise,
			@Nonnull Function<ObservableOutput<?>, T> logic
		) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void checkAndExecuteAndSync(
			@Nonnull String operation,
			@Nonnull Runnable premise,
			@Nonnull Consumer<ObservableOutput<?>> logic
		) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <S, T> T checkAndExecuteAndSync(
			@Nonnull String operation,
			@Nonnull Runnable premise,
			@Nonnull Function<ObservableOutput<?>, S> logic,
			@Nonnull BiFunction<ObservableOutput<?>, S, T> postExecutionLogic
		) {
			throw new UnsupportedOperationException();
		}

		@Override
		public long getLastWrittenPosition() {
			return 0;
		}

		@Nonnull
		@Override
		public ReadOnlyHandle toReadOnlyHandle() {
			throw new UnsupportedOperationException();
		}

		@Override
		public void close() {
			// holds no resource
		}
	}

}
