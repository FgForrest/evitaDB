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

import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.configuration.ChangeDataCaptureOptions;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.exception.ChangeCaptureResumePositionInvalidException;
import io.evitadb.api.exception.ChangeCaptureResumePositionInvalidException.Reason;
import io.evitadb.api.exception.TemporalDataNotAvailableException;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.api.CatalogContract;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.dataType.ContainerType;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.engine.exception.WriteAheadLogCorruptedException;
import io.evitadb.store.catalog.DefaultCatalogPersistenceService;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.wal.CatalogWriteAheadLog;
import io.evitadb.store.wal.WalRetentionTestSupport;
import io.evitadb.store.wal.WalRotationCrashTestSupport;
import io.evitadb.store.wal.WalRotationCrashTestSupport.RotationCrash;
import io.evitadb.store.wal.supplier.TransactionMutationWithLocation;
import io.evitadb.test.Entities;
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

import static io.evitadb.spi.store.catalog.persistence.PersistenceService.BOOT_FILE_SUFFIX;
import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.WAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a catalog change capture subscriber that has fallen behind the in-memory ring buffer, and therefore
 * catches up from the write-ahead log, is either served everything it was promised or told why it cannot be.
 *
 * A lagging subscriber used to be able to stop receiving captures with nothing in its stream saying so. The read of
 * the write-ahead log ended quietly on a failure, the subscriber's position did not move, and the next fill repeated
 * the same failing read forever. Each test below reaches one of those silent paths through the public registration
 * API of a real engine whose log rotates every few transactions:
 *
 * - a read failure in the middle of the range the subscriber reads must reach it as `onError`
 * - a position the log retention has already removed must reach it as {@link TemporalDataNotAvailableException}
 * - a subscriber whose criteria reject most transactions must not be mistaken for one that fell out of retention
 * - a subscriber lagging behind the ring buffer must be counted by the lagging-subscriber metric
 * - a log left by a crash between a rotation and the first append into the new file must still be read up to its
 *   last transaction, which then sits in the file before the empty active one
 *
 * A subscriber starting below the first transaction of a log that never lost a file - versions that belonged to
 * the warm-up phase and were never transactions - is served from the first transaction without an error. That case
 * is covered by `CatalogChangeObserverTest#shouldRegisterObserverAndReceiveAllExistingMutations`, which subscribes
 * from version zero to a catalog that went live right after warm-up.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Catalog change capture catching up from the write-ahead log")
@Tag(ENGINE)
@Tag(CDC)
@Tag(WAL)
class CatalogChangeCaptureWalCatchUpTest implements EvitaTestSupport {
	/**
	 * Attribute every committed entity carries, sized so that a few transactions fill a whole log file.
	 */
	private static final String ATTRIBUTE_PAYLOAD = "payload";
	/**
	 * Length of the payload written by each transaction - roughly a quarter of a rotating log file.
	 */
	private static final int PAYLOAD_LENGTH = 4_096;
	/**
	 * Size a log file may reach before it is rotated away when the test needs retention to remove files.
	 */
	private static final long ROTATING_WAL_FILE_SIZE_BYTES = 16_384L;
	/**
	 * Number of log files kept behind the active one.
	 */
	private static final int WAL_FILE_COUNT_KEPT = 2;
	/**
	 * Number of transactions that rotate the log often enough for the retention to remove the oldest files.
	 */
	private static final int ROTATING_TRANSACTION_COUNT = 30;
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

	private TestPaths paths;
	private Evita evita;
	/**
	 * Size a log file may reach before it is rotated away, as the engine was last started with - a restart reuses it.
	 */
	private long walFileSizeBytes;

	/**
	 * Creates criteria matching only the entity-level captures of the passed entity type.
	 *
	 * @param entityType the entity type to match
	 * @return the criteria
	 */
	@Nonnull
	private static ChangeCatalogCaptureCriteria entityCriteria(@Nonnull String entityType) {
		return ChangeCatalogCaptureCriteria.builder()
			.dataArea(site -> site.entityType(entityType).containerType(ContainerType.ENTITY))
			.build();
	}

	/**
	 * Creates a request for the entity-level captures of the passed entity type, starting at the passed version.
	 *
	 * @param entityType   the entity type to capture
	 * @param sinceVersion the version to start with
	 * @return the request
	 */
	@Nonnull
	private static ChangeCatalogCaptureRequest entityRequest(@Nonnull String entityType, long sinceVersion) {
		return ChangeCatalogCaptureRequest.builder()
			.sinceVersion(sinceVersion)
			.content(ChangeCaptureContent.BODY)
			.criteria(entityCriteria(entityType))
			.build();
	}

	/**
	 * Returns the distinct versions of the passed captures, in delivery order.
	 *
	 * @param captures the captures
	 * @return the versions the captures belong to
	 */
	@Nonnull
	private static List<Long> versionsOf(@Nonnull List<ChangeCatalogCapture> captures) {
		return captures.stream().map(ChangeCatalogCapture::version).distinct().toList();
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
		this.paths = createTestPaths(CatalogChangeCaptureWalCatchUpTest.class.getSimpleName());
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
	@DisplayName("should report a write-ahead log read failure in the middle of the caught-up range through onError")
	void shouldReportWalReadFailureInTheMiddleOfTheCaughtUpRange() throws Exception {
		// a single log file, so the damaged transaction is reached by advancing from its predecessor - the
		// sequential step the production failure happened in - rather than by opening the next file
		startEvita(TransactionOptions.DEFAULT_WAL_SIZE_BYTES);
		final List<Long> versions = commitEntities(Entities.PRODUCT, 10);
		final long startVersion = versions.get(0);
		final long damagedVersion = versions.get(6);

		final TransactionMutationWithLocation damagedTransaction = locateTransaction(damagedVersion);
		final Path damagedWalFile = walFile(damagedTransaction.getWalFileIndex());
		falsifyContentLengthPrefix(damagedWalFile, damagedTransaction.getTransactionSpan().startingPosition());

		final AwaitableCaptureSubscriber<ChangeCatalogCapture> subscriber = AwaitableCaptureSubscriber.unbounded();
		register(entityRequest(Entities.PRODUCT, startVersion)).subscribe(subscriber);
		subscriber.awaitUntil(items -> false, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

		final Throwable error = subscriber.getError();
		assertNotNull(
			error,
			"The subscriber reading the write-ahead log from version " + startVersion + " was never told that " +
				"transaction " + damagedVersion + " cannot be read. It received the captures of versions " +
				versionsOf(subscriber.getItems()) + " and then nothing - the silent stall this test exists for: " +
				"every later fill repeats the same failing read, or skips past it, without anyone being told."
		);
		final WriteAheadLogCorruptedException corruption = assertInstanceOf(
			WriteAheadLogCorruptedException.class, error,
			"A transaction the catalog has published and cannot read back is damage to its write-ahead log."
		);
		assertNotNull(
			corruption.getCause(),
			"The report must carry the failure of the read that ran into the damage - it is what tells an " +
				"operator where the log broke."
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
	@DisplayName("should report a position the write-ahead log retention has removed as temporal data not available")
	void shouldReportPositionRemovedByRetentionAsTemporalDataNotAvailable() throws Exception {
		startEvita(ROTATING_WAL_FILE_SIZE_BYTES);
		final List<Long> versions = commitEntities(Entities.PRODUCT, ROTATING_TRANSACTION_COUNT);
		final long removedVersion = versions.get(0);
		final long firstReplayableVersion = forceWalPurge(removedVersion);

		final AwaitableCaptureSubscriber<ChangeCatalogCapture> subscriber = AwaitableCaptureSubscriber.unbounded();
		register(entityRequest(Entities.PRODUCT, removedVersion)).subscribe(subscriber);
		subscriber.awaitUntil(items -> false, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

		final Throwable error = subscriber.getError();
		assertNotNull(
			error,
			"The subscriber asked for version " + removedVersion + ", which the log retention has removed - the " +
				"oldest version left is " + firstReplayableVersion + ". It received " +
				versionsOf(subscriber.getItems()) + " and was never told it cannot be served, so it would wait " +
				"forever and miss every later capture as well."
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
		// the catalog stream reports it as an invalid resume position, so that one handler covers every reason a
		// consumer's checkpoint can stop being servable - the handlers written for the plain type still catch it
		final ChangeCaptureResumePositionInvalidException invalidPosition = assertInstanceOf(
			ChangeCaptureResumePositionInvalidException.class, error,
			"A catalog subscriber must be told which catalog incarnation could not serve its position."
		);
		assertEquals(Reason.OUTSIDE_RETENTION, invalidPosition.getReason());
		assertEquals(liveCatalog().getCatalogId(), invalidPosition.getCatalogId());
		assertEquals(liveCatalog().getVersion(), invalidPosition.getCurrentCatalogVersion());
		assertEquals(removedVersion, invalidPosition.getRequestedSinceVersion());
		assertTrue(
			subscriber.getItems().isEmpty(),
			"Nothing may be delivered from a position that is no longer in the log - the first capture after it " +
				"would hide that everything in between is gone."
		);
	}

	@Test
	@DisplayName("should keep a subscriber whose criteria reject most transactions ahead of the log retention")
	void shouldKeepSelectiveSubscriberAheadOfRetention() throws Exception {
		startEvita(ROTATING_WAL_FILE_SIZE_BYTES);
		final long firstBrandVersion = commitEntity(Entities.BRAND, 1);

		final AwaitableCaptureSubscriber<ChangeCatalogCapture> subscriber = AwaitableCaptureSubscriber.unbounded();
		register(entityRequest(Entities.BRAND, firstBrandVersion)).subscribe(subscriber);
		assertTrue(
			subscriber.awaitUntil(items -> !items.isEmpty(), AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
			"The first brand capture was never delivered - the fixture did not reach the state under test."
		);

		// none of these match the subscriber's criteria, so none of them is ever delivered to it - and enough of
		// them are committed for the retention to remove the log file holding the last capture it received
		commitEntities(Entities.PRODUCT, ROTATING_TRANSACTION_COUNT);
		forceWalPurge(firstBrandVersion);

		final long lastBrandVersion = commitEntity(Entities.BRAND, 2);
		final boolean lastBrandDelivered = subscriber.awaitUntil(
			items -> versionsOf(items).contains(lastBrandVersion), AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
		);

		assertNull(
			subscriber.getError(),
			"A subscriber that has examined every transaction since the last brand capture - and missed none it " +
				"asked for - was reported as having lost history once the retention removed the log file holding " +
				"that capture. Its position must follow what it examined, not only what it received."
		);
		assertTrue(
			lastBrandDelivered,
			"The brand capture of version " + lastBrandVersion + " was never delivered; the subscriber received " +
				versionsOf(subscriber.getItems()) + "."
		);
		assertEquals(List.of(firstBrandVersion, lastBrandVersion), versionsOf(subscriber.getItems()));
	}

	@Test
	@DisplayName("should count a subscriber reading the write-ahead log behind the ring buffer as lagging")
	void shouldCountSubscriberBehindTheRingBufferAsLagging() throws Exception {
		startEvita(TransactionOptions.DEFAULT_WAL_SIZE_BYTES);
		final long firstVersion = commitEntity(Entities.PRODUCT, 1);

		// a subscriber at the head of the stream is what makes the shared publisher fill its ring buffer with the
		// transactions committed from now on
		final AwaitableCaptureSubscriber<ChangeCatalogCapture> headSubscriber = AwaitableCaptureSubscriber.unbounded();
		final ChangeCatalogCapturePublisher headPublisher = (ChangeCatalogCapturePublisher) register(
			ChangeCatalogCaptureRequest.builder()
				.content(ChangeCaptureContent.BODY)
				.criteria(entityCriteria(Entities.PRODUCT))
				.build()
		);
		headPublisher.subscribe(headSubscriber);
		final List<Long> headVersions = new ArrayList<>(commitEntities(Entities.PRODUCT, 2, 10));
		assertTrue(
			headSubscriber.awaitUntil(
				items -> versionsOf(items).equals(headVersions), AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
			),
			"The subscriber at the head of the stream did not receive the transactions committed after it " +
				"subscribed - the fixture did not reach the state under test."
		);

		// asks for a single capture from the very first transaction - long since evicted from the ring buffer -
		// and then stops, which leaves it parked behind the ring buffer, reading the log
		final AwaitableCaptureSubscriber<ChangeCatalogCapture> laggingSubscriber = new AwaitableCaptureSubscriber<>(1L);
		final ChangeCatalogCapturePublisher laggingPublisher =
			(ChangeCatalogCapturePublisher) register(entityRequest(Entities.PRODUCT, firstVersion));
		laggingPublisher.subscribe(laggingSubscriber);
		assertTrue(
			laggingSubscriber.awaitUntil(items -> items.size() == 1, AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS),
			"The lagging subscriber did not receive the single capture it asked for."
		);
		final ChangeCatalogCaptureSharedPublisher sharedPublisher = headPublisher.getSharedPublisher();
		assertSame(
			sharedPublisher, laggingPublisher.getSharedPublisher(),
			"Both subscribers use the same criteria, so they must share one publisher and one ring buffer."
		);

		// the observer's periodic cleaner - in production it runs every minute
		final CatalogChangeObserver observer =
			(CatalogChangeObserver) liveCatalog().getTransactionManager().getChangeObserver();
		observer.cleanInactivePublishers();

		assertEquals(
			1,
			sharedPublisher.getLaggingSubscribersCount(),
			"The subscriber reading the log behind the ring buffer is exactly what the lagging-subscriber metric " +
				"exists to report, but the cleanup sweep dropped its position from the statistics and the metric " +
				"read zero."
		);
	}

	@Test
	@DisplayName("should serve every transaction of a log left by a crash between rotation and the first append")
	void shouldServeEverythingOwedFromALogLeftByACrashBetweenRotationAndTheFirstAppend() throws Exception {
		startEvita(TransactionOptions.DEFAULT_WAL_SIZE_BYTES);
		final List<Long> versions = commitEntities(Entities.PRODUCT, 5);
		crashAfterRotatingTheLogAway();

		// no transaction is committed after the restart - the active log file stays empty, which is the whole point
		final AwaitableCaptureSubscriber<ChangeCatalogCapture> subscriber = AwaitableCaptureSubscriber.unbounded();
		register(entityRequest(Entities.PRODUCT, versions.get(0))).subscribe(subscriber);
		final boolean everythingDelivered = subscriber.awaitUntil(
			items -> versionsOf(items).equals(versions), AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
		);

		assertNull(
			subscriber.getError(),
			"Every transaction the subscriber asked for is intact in the log file before the empty active one."
		);
		assertTrue(
			everythingDelivered,
			"The subscriber asked for versions " + versions + ", all of them published and all of them in the log, " +
				"and received " + versionsOf(subscriber.getItems()) + " without being told why - the read was " +
				"bounded by the last version of the empty active file instead of the last version of the log."
		);
	}

	@Test
	@DisplayName("should continue the version sequence after a crash between rotation and the first append")
	void shouldContinueTheVersionSequenceAfterACrashBetweenRotationAndTheFirstAppend() throws Exception {
		startEvita(TransactionOptions.DEFAULT_WAL_SIZE_BYTES);
		// a checkpoint published at the first transaction, so the record the catalog restarts from references the
		// log - every later checkpoint is lost with the crash
		final List<Long> versions = new ArrayList<>(commitEntities(Entities.PRODUCT, 1));
		checkpoint();
		assertCatalogRecoversFromARotationCrash(versions, publishedCheckpointCount());
	}

	@Test
	@DisplayName("should replay a log no published checkpoint references after a crash between rotation and the first append")
	void shouldReplayALogNoCheckpointReferencesAfterACrashBetweenRotationAndTheFirstAppend() throws Exception {
		startEvita(TransactionOptions.DEFAULT_WAL_SIZE_BYTES);
		// the record the catalog restarts from is the one published when it went live, which references no
		// transaction of the log at all - so the whole log, the finalized file before the empty active one
		// included, is still to be replayed
		assertCatalogRecoversFromARotationCrash(new ArrayList<>(), publishedCheckpointCount());
	}

	/**
	 * Commits further transactions, crashes the engine between a rotation of the catalog's log and the first append
	 * into the new file with every checkpoint published after the passed one lost, and verifies the restarted
	 * catalog: it replays the log up to its last transaction, continues the version sequence after it, still opens
	 * after one more restart, and serves every transaction to a change capture subscriber.
	 *
	 * @param versions            versions committed so far, extended by the transactions committed here
	 * @param keptCheckpointCount number of bootstrap records that survive the crash
	 */
	private void assertCatalogRecoversFromARotationCrash(
		@Nonnull List<Long> versions,
		int keptCheckpointCount
	) throws IOException, InterruptedException {
		versions.addAll(commitEntities(Entities.PRODUCT, versions.size() + 1, versions.size() + 5));
		final long lastVersion = versions.get(versions.size() - 1);
		crashAfterRotatingTheLogAway(catalogFolder -> unpublishCheckpointsAfter(catalogFolder, keptCheckpointCount));

		assertEquals(
			lastVersion,
			liveCatalog().getVersion(),
			"The restarted catalog must have replayed the log up to its last transaction, which sits in the file " +
				"before the empty active one."
		);
		// the bootstrap record now lags behind the log, so the next version can only be taken from the log - taking
		// it from the record would hand out a version the previous log file already holds
		final long nextVersion = commitEntity(Entities.PRODUCT, versions.size() + 1);
		assertEquals(
			lastVersion + 1, nextVersion,
			"The first transaction after the restart must continue the log's version sequence."
		);
		versions.add(nextVersion);

		// the log must still open: its files have to continue one another, the new transaction included
		this.evita.close();
		this.evita = newEvita();
		assertEquals(nextVersion, liveCatalog().getVersion());

		final AwaitableCaptureSubscriber<ChangeCatalogCapture> subscriber = AwaitableCaptureSubscriber.unbounded();
		register(entityRequest(Entities.PRODUCT, versions.get(0))).subscribe(subscriber);
		final boolean everythingDelivered = subscriber.awaitUntil(
			items -> versionsOf(items).equals(versions), AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
		);
		assertNull(subscriber.getError());
		assertTrue(
			everythingDelivered,
			"Expected versions " + versions + ", received " + versionsOf(subscriber.getItems()) + "."
		);
	}

	/**
	 * Boots the engine with the passed log file size, small change capture buffers, and a catalog holding the two
	 * entity types the tests write, already alive.
	 *
	 * @param walFileSizeBytes size a log file may reach before it is rotated away
	 */
	private void startEvita(long walFileSizeBytes) {
		this.walFileSizeBytes = walFileSizeBytes;
		this.evita = newEvita();
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_PAYLOAD, String.class)
					.updateVia(session);
				session.defineEntitySchema(Entities.BRAND)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_PAYLOAD, String.class)
					.updateVia(session);
				session.goLiveAndClose();
			}
		);
	}

	/**
	 * Creates an engine over the test storage with the log file size the engine was last started with and small
	 * change capture buffers, and waits until it has loaded whatever the storage holds.
	 *
	 * @return the started engine
	 */
	@Nonnull
	private Evita newEvita() {
		final Evita theEvita = new Evita(
			newTestEvitaConfigurationBuilder(this.paths)
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
						.walFileSizeBytes(this.walFileSizeBytes)
						.walFileCountKept(WAL_FILE_COUNT_KEPT)
						.build()
				)
				.build()
		);
		theEvita.waitUntilFullyInitialized();
		return theEvita;
	}

	/**
	 * Stops the engine as a crash between a rotation of the catalog's log and the first append into the new file
	 * would, and starts it again.
	 *
	 * @see WalRotationCrashTestSupport
	 */
	private void crashAfterRotatingTheLogAway() throws IOException {
		crashAfterRotatingTheLogAway(catalogFolder -> {
		});
	}

	/**
	 * Stops the engine as a crash between a rotation of the catalog's log and the first append into the new file
	 * would, lets the passed action alter the stopped storage further, and starts the engine again.
	 *
	 * @param alsoOnCrash additional damage done to the catalog folder while the engine is stopped
	 * @see WalRotationCrashTestSupport
	 */
	private void crashAfterRotatingTheLogAway(@Nonnull CrashDamage alsoOnCrash) throws IOException {
		final RotationCrash rotationCrash = WalRotationCrashTestSupport.captureRotationCrash(catalogWal());
		this.evita.close();
		rotationCrash.write();
		alsoOnCrash.apply(rotationCrash.activeWalFile().getParent());
		this.evita = newEvita();
	}

	/**
	 * Publishes the checkpoint the commits so far may have deferred.
	 */
	private void checkpoint() {
		final CatalogPersistenceService<?, ?, ?> persistenceService = liveCatalog().getPersistenceService();
		((DefaultCatalogPersistenceService) persistenceService).checkpoint();
	}

	/**
	 * Returns the number of records in the bootstrap file of the live catalog.
	 *
	 * @return the number of published checkpoints, the one written when the catalog went live included
	 */
	private int publishedCheckpointCount() throws IOException {
		// the catalog may have written no log yet, so its folder is found through the bootstrap file it always has -
		// the engine keeps its own one directly in the storage folder
		final Path storageFolder = this.paths.storage();
		try (final Stream<Path> files = Files.walk(storageFolder, 2)) {
			final List<Path> catalogBootstrapFiles = files
				.filter(file -> file.getFileName().toString().endsWith(BOOT_FILE_SUFFIX))
				.filter(file -> !storageFolder.equals(file.getParent()))
				.toList();
			assertEquals(1, catalogBootstrapFiles.size(), "Expected the bootstrap file of the only catalog.");
			return CatalogBootstrap.getRecordCount(Files.size(catalogBootstrapFiles.get(0)));
		}
	}

	/**
	 * Removes every record of the catalog's bootstrap file after the first `keptCheckpointCount` ones - the state of
	 * a crash that came after the log appended its transactions but before the checkpoints deferred past them were
	 * published. Nothing is damaged by that: the newest record kept still describes a complete state, and the log
	 * holds everything after it.
	 *
	 * @param catalogFolder       the folder of the stopped catalog
	 * @param keptCheckpointCount the number of records to keep
	 */
	private static void unpublishCheckpointsAfter(
		@Nonnull Path catalogFolder,
		int keptCheckpointCount
	) throws IOException {
		final Path bootstrapFile = bootstrapFile(catalogFolder);
		final int recordCount = CatalogBootstrap.getRecordCount(Files.size(bootstrapFile));
		assertTrue(
			recordCount > keptCheckpointCount,
			"Some checkpoint must have been published after the one the catalog is to restart from - the bootstrap " +
				"file holds " + recordCount + " record(s) and " + keptCheckpointCount + " are to be kept."
		);
		try (final RandomAccessFile raf = new RandomAccessFile(bootstrapFile.toFile(), "rw")) {
			raf.setLength(CatalogBootstrap.getPositionForRecord(keptCheckpointCount));
		}
	}

	/**
	 * Finds the bootstrap file in the passed catalog folder.
	 *
	 * @param catalogFolder the folder of the catalog
	 * @return the path of the bootstrap file
	 */
	@Nonnull
	private static Path bootstrapFile(@Nonnull Path catalogFolder) throws IOException {
		try (final Stream<Path> files = Files.list(catalogFolder)) {
			return files
				.filter(file -> file.getFileName().toString().endsWith(BOOT_FILE_SUFFIX))
				.findFirst()
				.orElseThrow(() -> new AssertionError("The catalog folder `" + catalogFolder + "` has no bootstrap file!"));
		}
	}

	/**
	 * Commits one transaction upserting a single entity and waits until it is visible.
	 *
	 * @param entityType the type of the entity
	 * @param primaryKey the primary key of the entity
	 * @return the catalog version the transaction produced
	 */
	private long commitEntity(@Nonnull String entityType, int primaryKey) {
		final String payload = "x".repeat(PAYLOAD_LENGTH) + primaryKey;
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(
					session.createNewEntity(entityType, primaryKey).setAttribute(ATTRIBUTE_PAYLOAD, payload)
				);
			},
			CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
		);
		return liveCatalog().getVersion();
	}

	/**
	 * Commits one transaction per entity with primary keys from one to `count`.
	 *
	 * @param entityType the type of the entities
	 * @param count      the number of transactions
	 * @return the catalog versions the transactions produced, in commit order
	 */
	@Nonnull
	private List<Long> commitEntities(@Nonnull String entityType, int count) {
		return commitEntities(entityType, 1, count);
	}

	/**
	 * Commits one transaction per entity with primary keys in the passed inclusive range.
	 *
	 * @param entityType the type of the entities
	 * @param fromKey    the first primary key
	 * @param toKey      the last primary key
	 * @return the catalog versions the transactions produced, in commit order
	 */
	@Nonnull
	private List<Long> commitEntities(@Nonnull String entityType, int fromKey, int toKey) {
		final List<Long> versions = new ArrayList<>(toKey - fromKey + 1);
		for (int primaryKey = fromKey; primaryKey <= toKey; primaryKey++) {
			versions.add(commitEntity(entityType, primaryKey));
		}
		return versions;
	}

	/**
	 * Registers a change capture publisher for the request in a session that is closed right away, so that no
	 * session keeps a catalog version pinned while the tests rotate and purge the log.
	 *
	 * @param request the change capture request
	 * @return the publisher to subscribe to
	 */
	@Nonnull
	private ChangeCapturePublisher<ChangeCatalogCapture> register(@Nonnull ChangeCatalogCaptureRequest request) {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				return session.registerChangeCatalogCapture(request);
			}
		);
	}

	/**
	 * Returns the catalog instance currently in the live view.
	 *
	 * @return the live catalog
	 */
	@Nonnull
	private Catalog liveCatalog() {
		final CatalogContract catalog = this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		return assertInstanceOf(
			Catalog.class, catalog,
			"The catalog did not load - it is `" + catalog.getClass().getSimpleName() + "`."
		);
	}

	/**
	 * Returns the catalog's write-ahead log.
	 *
	 * @return the log the catalog appends to
	 */
	@Nonnull
	private CatalogWriteAheadLog catalogWal() {
		// through the wildcard: `Catalog` holds the service under the interface's `LogRecordReference`
		// parameterisation while the implementation declares `LogFileRecordReference`
		final CatalogPersistenceService<?, ?, ?> persistenceService = liveCatalog().getPersistenceService();
		final CatalogWriteAheadLog catalogWal = ((DefaultCatalogPersistenceService) persistenceService).getCatalogWal();
		assertNotNull(catalogWal, "The catalog must have gone live and written a log!");
		return catalogWal;
	}

	/**
	 * Resolves the log file with the passed index.
	 *
	 * @param walFileIndex the index of the file
	 * @return the path of the file, which exists
	 */
	@Nonnull
	private Path walFile(int walFileIndex) {
		final Path walFile = catalogWal().getWalFilePath().getParent()
			.resolve(CatalogPersistenceService.getWalFileName(TEST_CATALOG, walFileIndex));
		assertTrue(Files.isRegularFile(walFile), "The log file `" + walFile + "` must exist!");
		return walFile;
	}

	/**
	 * Reads the leading mutation of the transaction producing the passed version, with its place in the log.
	 *
	 * @param version the catalog version of the transaction
	 * @return the leading mutation with its location
	 */
	@Nonnull
	private TransactionMutationWithLocation locateTransaction(long version) {
		try (final Stream<CatalogBoundMutation> mutations = liveCatalog().getCommittedMutationStream(version)) {
			return mutations
				.filter(TransactionMutationWithLocation.class::isInstance)
				.map(TransactionMutationWithLocation.class::cast)
				.filter(it -> it.getVersion() == version)
				.findFirst()
				.orElseThrow(() -> new AssertionError("Transaction " + version + " is not in the log!"));
		}
	}

	/**
	 * Makes the log retention remove the files it has rotated away, rather than waiting for its scheduled task, and
	 * asserts the passed version is gone afterwards.
	 *
	 * Both steps are needed. A rotated file may only be removed once every version in it has been processed, and
	 * processing advances with a checkpoint rather than with a commit - the checkpoint publishes the one the commits
	 * may have deferred, which makes the queued removals eligible. The removal itself is scheduled work that runs
	 * on another thread after a minimal gap, so the test performs it on its own thread.
	 *
	 * @param removedVersion the version that must no longer be replayable afterwards
	 * @return the first version the log can still replay
	 */
	private long forceWalPurge(long removedVersion) {
		final CatalogPersistenceService<?, ?, ?> persistenceService = liveCatalog().getPersistenceService();
		((DefaultCatalogPersistenceService) persistenceService).checkpoint();
		WalRetentionTestSupport.removeEligibleWalFiles(catalogWal());
		final long firstReplayableVersion = liveCatalog().getFirstReplayableCatalogVersion();
		assertTrue(
			firstReplayableVersion > removedVersion,
			"The retention must have removed the log file holding version " + removedVersion + " - the fixture " +
				"depends on it - but the first replayable version is " + firstReplayableVersion + ". Raise " +
				"ROTATING_TRANSACTION_COUNT or lower ROTATING_WAL_FILE_SIZE_BYTES if the write path got cheaper."
		);
		return firstReplayableVersion;
	}

	/**
	 * Damage done to the folder of a stopped catalog, on top of the crash itself.
	 */
	@FunctionalInterface
	private interface CrashDamage {

		/**
		 * Alters the stopped catalog's files.
		 *
		 * @param catalogFolder the folder of the stopped catalog
		 */
		void apply(@Nonnull Path catalogFolder) throws IOException;

	}

}
