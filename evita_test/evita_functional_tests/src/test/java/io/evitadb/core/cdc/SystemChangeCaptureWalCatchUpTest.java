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

package io.evitadb.core.cdc;

import io.evitadb.api.configuration.ChangeDataCaptureOptions;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.exception.ChangeCaptureResumePositionInvalidException;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCapture;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeSystemCaptureRequest;
import io.evitadb.api.requestResponse.cdc.HostSystemEvent;
import io.evitadb.api.requestResponse.cdc.SystemCaptureArea;
import io.evitadb.api.requestResponse.mutation.EngineMutation;
import io.evitadb.core.Evita;
import io.evitadb.spi.store.engine.EnginePersistenceService;
import io.evitadb.spi.store.engine.exception.WriteAheadLogCorruptedException;
import io.evitadb.store.wal.supplier.TransactionMutationWithLocation;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.WAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a system change capture subscriber catching up from the engine write-ahead log is either served
 * everything it was promised or told why it cannot be - the engine-level counterpart of
 * {@link CatalogChangeCaptureWalCatchUpTest}.
 *
 * The engine log records catalog-level operations - catalogs created, made alive, renamed or removed - and its
 * subscribers fall back to it in the same way catalog subscribers fall back to the catalog log: once the in-memory
 * ring buffer no longer holds their position. The read used to end quietly on a failure there too, so each test
 * below drives the engine log into one of the silent paths through the public registration API:
 *
 * - a read failure in the middle of the range the subscriber reads must reach it as `onError`
 * - a position the log retention has already removed must reach it as {@link TemporalDataNotAvailableException}
 * - a subscriber whose criteria reject every engine mutation must not be mistaken for one that fell behind the
 *   retention, because its position follows what it examined rather than only what it received
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("System change capture catching up from the engine write-ahead log")
@Tag(ENGINE)
@Tag(CDC)
@Tag(WAL)
class SystemChangeCaptureWalCatchUpTest implements EvitaTestSupport {
	/**
	 * Size an engine log file may reach before it is rotated away when the test needs retention to remove files -
	 * a few engine transactions each.
	 */
	private static final long ROTATING_WAL_FILE_SIZE_BYTES = 1_024L;
	/**
	 * Number of log files kept behind the active one.
	 */
	private static final int WAL_FILE_COUNT_KEPT = 2;
	/**
	 * Captures the shared publisher keeps in memory - small, so that a subscriber that starts in the past is
	 * behind the ring buffer and has to read the log.
	 */
	private static final int RECENT_EVENTS_CACHE_LIMIT = 2;
	/**
	 * Captures buffered per subscriber - one, so that every fill delivers at most one capture and the captures
	 * read before a failure reach the subscriber in fills of their own.
	 */
	private static final int SUBSCRIBER_BUFFER_SIZE = 1;
	/**
	 * Upper bound of every wait for a signal. A positive wait returns as soon as the signal arrives.
	 */
	private static final long AWAIT_TIMEOUT_SECONDS = 30L;
	/**
	 * Small lie written over a transaction's 4-byte content-length prefix - small enough to pass the reader's
	 * coarse length check, so the read reaches the consistency check that compares it with the records read.
	 */
	private static final int LYING_CONTENT_LENGTH = 4;
	/**
	 * Pause between two looks at the first replayable version while waiting for the scheduled log retention.
	 */
	private static final long RETENTION_POLL_INTERVAL_MILLIS = 50L;

	private TestPaths paths;
	private EvitaConfiguration configuration;
	private Evita evita;

	/**
	 * Creates a request for every engine mutation, starting at the passed version.
	 *
	 * @param sinceVersion the engine version to start with
	 * @return the request
	 */
	@Nonnull
	private static ChangeSystemCaptureRequest requestSince(long sinceVersion) {
		return new ChangeSystemCaptureRequest(sinceVersion, 0, null, ChangeCaptureContent.BODY);
	}

	/**
	 * Returns the distinct versions of the passed captures, in delivery order.
	 *
	 * @param captures the captures
	 * @return the versions the captures belong to
	 */
	@Nonnull
	private static List<Long> versionsOf(@Nonnull List<ChangeSystemCapture> captures) {
		return captures.stream().map(ChangeSystemCapture::version).distinct().toList();
	}

	/**
	 * Returns whether the passed capture carries a host event about the passed catalog.
	 *
	 * @param capture     the capture
	 * @param catalogName name of the catalog
	 * @return true when the capture is a host event about the catalog
	 */
	private static boolean isHostEventOf(@Nonnull ChangeSystemCapture capture, @Nonnull String catalogName) {
		return capture.body() instanceof HostSystemEvent hostEvent && catalogName.equals(hostEvent.catalogName());
	}

	/**
	 * Overwrites the 4-byte content-length prefix of the transaction starting at the passed position.
	 *
	 * @param walFile          the log file holding the transaction
	 * @param startingPosition the position of the transaction's prefix
	 */
	private static void falsifyContentLengthPrefix(@Nonnull Path walFile, long startingPosition) throws IOException {
		try (final RandomAccessFile raf = new RandomAccessFile(walFile.toFile(), "rw")) {
			raf.seek(startingPosition);
			raf.write(
				ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
					.putInt(LYING_CONTENT_LENGTH).array()
			);
		}
	}

	@BeforeEach
	void setUp() throws IOException {
		this.paths = createTestPaths(SystemChangeCaptureWalCatchUpTest.class.getSimpleName());
		Files.createDirectories(this.paths.storage());
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("should report an engine write-ahead log read failure in the middle of the caught-up range through onError")
	void shouldReportWalReadFailureInTheMiddleOfTheCaughtUpRange() throws Exception {
		// a single log file, so the damaged transaction is reached by advancing from its predecessor rather than by
		// opening the next file
		startEvita(TransactionOptions.DEFAULT_WAL_SIZE_BYTES);
		final List<Long> versions = createCatalogs(0, 10);
		final long startVersion = versions.get(0);
		final long damagedVersion = versions.get(6);

		final TransactionMutationWithLocation damagedTransaction = locateTransaction(damagedVersion);
		final Path damagedWalFile = this.paths.storage()
			.resolve(EnginePersistenceService.getWalFileName(damagedTransaction.getWalFileIndex()));
		assertTrue(Files.isRegularFile(damagedWalFile), "The engine log file `" + damagedWalFile + "` must exist!");
		falsifyContentLengthPrefix(damagedWalFile, damagedTransaction.getTransactionSpan().startingPosition());

		final AwaitableCaptureSubscriber<ChangeSystemCapture> subscriber = AwaitableCaptureSubscriber.unbounded();
		try (
			final ChangeCapturePublisher<ChangeSystemCapture> publisher =
				this.evita.registerSystemChangeCapture(requestSince(startVersion))
		) {
			publisher.subscribe(subscriber);
			subscriber.awaitUntil(items -> false, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}

		final Throwable error = subscriber.getError();
		assertNotNull(
			error,
			"The subscriber reading the engine log from version " + startVersion + " was never told that " +
				"transaction " + damagedVersion + " cannot be read. It received the captures of versions " +
				versionsOf(subscriber.getItems()) + " and then nothing - every later fill repeats the same " +
				"failing read, or skips past it, without anyone being told."
		);
		final WriteAheadLogCorruptedException corruption = assertInstanceOf(
			WriteAheadLogCorruptedException.class, error,
			"A transaction the engine has published and cannot read back is damage to its write-ahead log."
		);
		assertNotNull(
			corruption.getCause(),
			"The report must carry the failure of the read that ran into the damage."
		);
		assertFalse(subscriber.isCompleted(), "A failed read is not an orderly end of the capture stream.");

		// a capture the failing fill had already queued is intact and owed, so it is delivered before the error -
		// otherwise a resubscription from the last delivered position would read the same stretch and fail again,
		// and the captures right before the damage could never be consumed
		assertEquals(
			versions.subList(0, versions.indexOf(damagedVersion)),
			versionsOf(subscriber.getItems()),
			"Every transaction before the damaged one is intact and must have been delivered, in order and without " +
				"a gap, and nothing of the damaged transaction or after it."
		);
	}

	@Test
	@DisplayName("should report a position the engine write-ahead log retention has removed as temporal data not available")
	void shouldReportPositionRemovedByRetentionAsTemporalDataNotAvailable() throws Exception {
		startEvita(ROTATING_WAL_FILE_SIZE_BYTES);
		final long removedVersion = createAndRemoveCatalogs(0, 30);
		// the removals queued by rotation run on the scheduler, so the test drains them deterministically by closing
		// the log - which removes only files whose versions the published engine state has moved past while the
		// engine ran: a log that kept the version it was opened at would remove nothing here
		restartEvita();

		final long firstReplayableVersion = this.evita.getFirstReplayableVersion();
		assertTrue(
			firstReplayableVersion > removedVersion,
			"The retention must have removed the engine log file holding version " + removedVersion + " - the " +
				"fixture depends on it - but the first replayable version is " + firstReplayableVersion + "."
		);

		final AwaitableCaptureSubscriber<ChangeSystemCapture> subscriber = AwaitableCaptureSubscriber.unbounded();
		try (
			final ChangeCapturePublisher<ChangeSystemCapture> publisher =
				this.evita.registerSystemChangeCapture(requestSince(removedVersion))
		) {
			publisher.subscribe(subscriber);
			subscriber.awaitUntil(items -> false, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		}

		final Throwable error = subscriber.getError();
		assertNotNull(
			error,
			"The subscriber asked for engine version " + removedVersion + ", which the log retention has removed - " +
				"the oldest version left is " + firstReplayableVersion + ". It received " +
				versionsOf(subscriber.getItems()) + " and was never told it cannot be served."
		);
		final TemporalDataNotAvailableException temporalDataNotAvailable = assertInstanceOf(
			TemporalDataNotAvailableException.class, error,
			"A position removed by retention is not damage - the subscriber merely fell too far behind."
		);
		assertEquals(
			firstReplayableVersion,
			temporalDataNotAvailable.getCatalogVersion(),
			"The error must name the oldest version the subscriber can still resume from."
		);
		assertFalse(
			temporalDataNotAvailable instanceof ChangeCaptureResumePositionInvalidException,
			"The engine stream belongs to no catalog incarnation, so it has no catalog identity to report - its " +
				"retention error stays the plain type."
		);
		assertTrue(
			subscriber.getItems().isEmpty(),
			"Nothing may be delivered from a position that is no longer in the log."
		);
	}

	@Test
	@DisplayName("should keep a subscriber whose criteria reject every engine mutation ahead of the log retention")
	void shouldKeepSelectiveSubscriberAheadOfRetention() throws Exception {
		startEvita(ROTATING_WAL_FILE_SIZE_BYTES);
		final long sinceVersion = createCatalogs(0, 1).get(0);

		final AwaitableCaptureSubscriber<ChangeSystemCapture> subscriber = AwaitableCaptureSubscriber.unbounded();
		try (
			final ChangeCapturePublisher<ChangeSystemCapture> publisher = this.evita.registerSystemChangeCapture(
				new ChangeSystemCaptureRequest(
					sinceVersion, 0,
					new ChangeSystemCaptureCriteria[]{new ChangeSystemCaptureCriteria(SystemCaptureArea.HOST)},
					ChangeCaptureContent.BODY
				)
			)
		) {
			publisher.subscribe(subscriber);

			// every one of these is an engine mutation the subscriber's criteria reject, so none of them moves its
			// position by being delivered - and enough of them are committed for the retention to remove the log
			// file holding the version it started at
			createAndRemoveCatalogs(1, 31);
			final long firstReplayableVersion = awaitRetentionPast(sinceVersion);

			final String lastCatalogName = TEST_CATALOG + "_last";
			this.evita.defineCatalog(lastCatalogName);
			final boolean lastHostEventDelivered = subscriber.awaitUntil(
				items -> items.stream().anyMatch(it -> isHostEventOf(it, lastCatalogName)),
				AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
			);

			assertNull(
				subscriber.getError(),
				"A subscriber that has examined every engine transaction since version " + sinceVersion + " - and " +
					"missed none it asked for - was reported as having lost history once the retention moved the " +
					"oldest replayable version to " + firstReplayableVersion + ". Its position must follow what it " +
					"examined, not only what it received."
			);
			assertTrue(
				lastHostEventDelivered,
				"The host event of catalog `" + lastCatalogName + "` was never delivered."
			);
		}
		assertTrue(
			subscriber.getItems().stream().allMatch(it -> it.body() instanceof HostSystemEvent),
			"A subscriber asking for host events only must receive no engine mutation."
		);
	}

	/**
	 * Waits until the engine log retention has removed the file holding the passed version.
	 *
	 * Every engine commit reports its version as processed, which schedules the removal of the files rotated away -
	 * but the removal runs on the scheduler after a minimal gap, and the engine log offers the test no seam to run
	 * it on its own thread without closing the log, which would end the subscription under test.
	 *
	 * @param removedVersion the version that must no longer be replayable
	 * @return the first version the log can still replay
	 */
	private long awaitRetentionPast(long removedVersion) throws InterruptedException {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_TIMEOUT_SECONDS);
		long firstReplayableVersion = this.evita.getFirstReplayableVersion();
		while (firstReplayableVersion <= removedVersion && System.nanoTime() < deadline) {
			Thread.sleep(RETENTION_POLL_INTERVAL_MILLIS);
			firstReplayableVersion = this.evita.getFirstReplayableVersion();
		}
		assertTrue(
			firstReplayableVersion > removedVersion,
			"The retention must have removed the engine log file holding version " + removedVersion + " - the " +
				"fixture depends on it - but the first replayable version is " + firstReplayableVersion + "."
		);
		return firstReplayableVersion;
	}

	/**
	 * Boots the engine with the passed log file size and small change capture buffers.
	 *
	 * @param walFileSizeBytes size a log file may reach before it is rotated away
	 */
	private void startEvita(long walFileSizeBytes) {
		this.configuration = newTestEvitaConfigurationBuilder(this.paths)
			.server(
				ServerOptions.builder()
					.changeDataCapture(
						ChangeDataCaptureOptions.builder()
							.recentEventsCacheLimit(RECENT_EVENTS_CACHE_LIMIT)
							.subscriberBufferSize(SUBSCRIBER_BUFFER_SIZE)
							.build()
					)
					.build()
			)
			.transaction(
				TransactionOptions.builder()
					.walFileSizeBytes(walFileSizeBytes)
					.walFileCountKept(WAL_FILE_COUNT_KEPT)
					.build()
			)
			.build();
		this.evita = new Evita(this.configuration);
		this.evita.waitUntilFullyInitialized();
	}

	/**
	 * Closes the engine and boots it again over the same storage.
	 */
	private void restartEvita() {
		this.evita.close();
		this.evita = new Evita(this.configuration);
		this.evita.waitUntilFullyInitialized();
	}

	/**
	 * Creates catalogs with indexes in the passed half-open range - one engine transaction each.
	 *
	 * @param fromIndex the first index
	 * @param toIndex   the index after the last one
	 * @return the engine versions the transactions produced, in commit order
	 */
	@Nonnull
	private List<Long> createCatalogs(int fromIndex, int toIndex) {
		final List<Long> versions = new ArrayList<>(toIndex - fromIndex);
		for (int i = fromIndex; i < toIndex; i++) {
			this.evita.defineCatalog(TEST_CATALOG + "_" + i);
			versions.add(this.evita.getEngineState().version());
		}
		return versions;
	}

	/**
	 * Creates and removes again catalogs with indexes in the passed half-open range - two engine transactions each.
	 *
	 * @param fromIndex the first index
	 * @param toIndex   the index after the last one
	 * @return the engine version the first transaction produced
	 */
	private long createAndRemoveCatalogs(int fromIndex, int toIndex) {
		long firstVersion = -1L;
		for (int i = fromIndex; i < toIndex; i++) {
			final String catalogName = TEST_CATALOG + "_" + i;
			this.evita.defineCatalog(catalogName);
			if (firstVersion == -1L) {
				firstVersion = this.evita.getEngineState().version();
			}
			this.evita.deleteCatalogIfExists(catalogName);
		}
		return firstVersion;
	}

	/**
	 * Reads the leading mutation of the engine transaction producing the passed version, with its place in the log.
	 *
	 * @param version the engine version of the transaction
	 * @return the leading mutation with its location
	 */
	@Nonnull
	private TransactionMutationWithLocation locateTransaction(long version) {
		try (final Stream<EngineMutation<?>> mutations = this.evita.getCommittedMutationStream(version)) {
			return mutations
				.filter(TransactionMutationWithLocation.class::isInstance)
				.map(TransactionMutationWithLocation.class::cast)
				.filter(it -> it.getVersion() == version)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Engine transaction " + version + " is not in the log!"));
		}
	}

}
