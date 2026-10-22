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

import io.evitadb.api.CatalogContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.configuration.ChangeDataCaptureOptions;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.exception.ChangeCaptureResumePositionInvalidException;
import io.evitadb.api.exception.ChangeCaptureResumePositionInvalidException.Reason;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.session.EvitaInternalSessionContract;
import io.evitadb.core.transaction.TransactionManager;
import io.evitadb.dataType.ContainerType;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a catalog change capture resume position - catalog identity, version and index - is either served
 * by the catalog incarnation it is applied to or refused with {@link ChangeCaptureResumePositionInvalidException},
 * and that every capture carries the identity a consumer has to store with its position.
 *
 * The failure this exists for: a consumer re-subscribed after `replaceCatalog` with the version of its last capture.
 * The new incarnation numbered its versions from scratch, so the position lay far ahead of it; the subscription was
 * accepted, stayed open and healthy, and delivered nothing for a day - until the new catalog happened to reach that
 * version. The tests reach that situation through the public API of a real engine and assert the refusal instead,
 * and pin the positions that must stay acceptable: the next version, a renamed catalog, a restarted engine, and the
 * next version while the registering session and the change observer still describe an older one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Catalog change capture resume position")
@Tag(ENGINE)
@Tag(CDC)
class CatalogChangeCaptureResumePositionTest implements EvitaTestSupport {
	/**
	 * Name of the catalog that replaces {@link #TEST_CATALOG}, the way a full reindex ends.
	 */
	private static final String REPLACING_CATALOG = "replacingCatalog";
	/**
	 * Name {@link #TEST_CATALOG} is renamed to.
	 */
	private static final String RENAMED_CATALOG = "renamedCatalog";
	/**
	 * Name of a catalog that is defined and left warming up.
	 */
	private static final String WARMING_UP_CATALOG = "warmingUpCatalog";
	/**
	 * Upper bound of every wait for a signal. A positive wait returns as soon as the signal arrives.
	 */
	private static final long AWAIT_TIMEOUT_SECONDS = 30L;
	/**
	 * Captures the shared publisher keeps in memory - small, so that a subscriber starting in the past reads the
	 * write-ahead log.
	 */
	private static final int RECENT_EVENTS_CACHE_LIMIT = 2;

	private TestPaths paths;
	private Evita evita;

	/**
	 * Creates a request for the entity-level product captures with the passed resume position.
	 *
	 * @param catalogId    the expected catalog identity, if any
	 * @param sinceVersion the version to resume from, if any
	 * @return the request
	 */
	@Nonnull
	private static ChangeCatalogCaptureRequest productRequest(@Nullable UUID catalogId, @Nullable Long sinceVersion) {
		final ChangeCatalogCaptureRequest.Builder builder = ChangeCatalogCaptureRequest.builder()
			.catalogId(catalogId)
			.content(ChangeCaptureContent.BODY)
			.criteria(
				ChangeCatalogCaptureCriteria.builder()
					.dataArea(site -> site.entityType(Entities.PRODUCT).containerType(ContainerType.ENTITY))
					.build()
			);
		if (sinceVersion != null) {
			builder.sinceVersion(sinceVersion);
		}
		return builder.build();
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
	 * Asserts that every passed capture carries the passed catalog identity.
	 *
	 * @param catalogId the identity the captures must carry
	 * @param captures  the captures
	 * @param path      the delivery path the captures came through, for the failure message
	 */
	private static void assertStampedWith(
		@Nonnull UUID catalogId,
		@Nonnull List<ChangeCatalogCapture> captures,
		@Nonnull String path
	) {
		assertTrue(!captures.isEmpty(), "No capture was delivered through " + path + " - nothing to check.");
		for (ChangeCatalogCapture capture : captures) {
			assertEquals(
				catalogId, capture.catalogId(),
				"The capture of version " + capture.version() + "/" + capture.index() + " delivered through " +
					path + " does not carry the identity of the catalog incarnation it belongs to - a consumer " +
					"storing it as its resume position could not be protected against a replaced catalog."
			);
		}
	}

	@BeforeEach
	void setUp() throws IOException {
		this.paths = createTestPaths(CatalogChangeCaptureResumePositionTest.class.getSimpleName());
		Files.createDirectories(this.paths.storage());
		this.evita = newEvita();
		defineAliveCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@Nested
	@DisplayName("Replaced catalog")
	class ReplacedCatalog {

		@Test
		@DisplayName("should refuse a checkpoint of the replaced catalog when the replacing one is behind it")
		void shouldRefuseCheckpointOfReplacedCatalogWhenReplacingOneIsBehind() throws Exception {
			final Checkpoint checkpoint = consumeAndCheckpoint(3);
			defineAliveCatalog(REPLACING_CATALOG);
			commitEntity(REPLACING_CATALOG, 1);
			final long replacingVersion = liveCatalog(REPLACING_CATALOG).getVersion();
			assertTrue(
				replacingVersion + 1 < checkpoint.sinceVersion(),
				"The replacing catalog must be behind the checkpoint for this test - it is at " + replacingVersion +
					", the checkpoint at " + checkpoint.sinceVersion() + "."
			);
			final UUID replacingCatalogId = liveCatalog(REPLACING_CATALOG).getCatalogId();

			CatalogChangeCaptureResumePositionTest.this.evita.replaceCatalog(REPLACING_CATALOG, TEST_CATALOG);

			final ChangeCaptureResumePositionInvalidException refusal = assertThrows(
				ChangeCaptureResumePositionInvalidException.class,
				() -> register(TEST_CATALOG, productRequest(checkpoint.catalogId(), checkpoint.sinceVersion())),
				"A checkpoint of the replaced catalog was accepted by its replacement - the subscription would stay " +
					"open and silent until the new catalog reached version " + checkpoint.sinceVersion() + "."
			);
			assertEquals(Reason.DIFFERENT_INCARNATION, refusal.getReason());
			assertEquals(replacingCatalogId, refusal.getCatalogId());
			assertEquals(replacingVersion, refusal.getCurrentCatalogVersion());
			assertEquals(checkpoint.catalogId(), refusal.getRequestedCatalogId());
			assertEquals(checkpoint.sinceVersion(), refusal.getRequestedSinceVersion());

			// a consumer that never stored the identity is still protected here - the position lies ahead of the
			// new catalog, which no position recorded on it can
			final ChangeCaptureResumePositionInvalidException aheadRefusal = assertThrows(
				ChangeCaptureResumePositionInvalidException.class,
				() -> register(TEST_CATALOG, productRequest(null, checkpoint.sinceVersion()))
			);
			assertEquals(Reason.AHEAD_OF_CATALOG, aheadRefusal.getReason());
			assertEquals(replacingCatalogId, aheadRefusal.getCatalogId());

			// the remedy the exception names - rebuild from one session's snapshot and resume right after it - loses
			// nothing, not even a change committed while the rebuild runs
			final Checkpoint snapshot = CatalogChangeCaptureResumePositionTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return new Checkpoint(session.getCatalogId(), session.getCatalogVersion() + 1);
				}
			);
			assertEquals(replacingCatalogId, snapshot.catalogId());
			final long committedDuringRebuild = commitEntity(TEST_CATALOG, 100);
			assertEquals(snapshot.sinceVersion(), committedDuringRebuild);
			final Registration renewed = register(
				TEST_CATALOG, productRequest(snapshot.catalogId(), snapshot.sinceVersion())
			);
			final AwaitableCaptureSubscriber<ChangeCatalogCapture> subscriber = AwaitableCaptureSubscriber.unbounded();
			renewed.publisher().subscribe(subscriber);
			final long nextVersion = commitEntity(TEST_CATALOG, 101);
			assertTrue(
				subscriber.awaitUntil(
					items -> versionsOf(items).contains(nextVersion), AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
				),
				"The renewed subscription did not deliver the next change of the replacing catalog."
			);
			assertEquals(
				List.of(committedDuringRebuild, nextVersion), versionsOf(subscriber.getItems()),
				"The change committed while the consumer rebuilt its state must be delivered, not skipped."
			);
			assertStampedWith(replacingCatalogId, subscriber.getItems(), "the renewed subscription");
		}

		@Test
		@DisplayName("should refuse a checkpoint of the replaced catalog when the replacing one is past it")
		void shouldRefuseCheckpointOfReplacedCatalogWhenReplacingOneIsPastIt() throws Exception {
			final Checkpoint checkpoint = consumeAndCheckpoint(2);
			defineAliveCatalog(REPLACING_CATALOG);
			final int replacingCommits = (int) checkpoint.sinceVersion() + 2;
			for (int primaryKey = 1; primaryKey <= replacingCommits; primaryKey++) {
				commitEntity(REPLACING_CATALOG, primaryKey);
			}
			final long replacingVersion = liveCatalog(REPLACING_CATALOG).getVersion();
			assertTrue(
				replacingVersion >= checkpoint.sinceVersion(),
				"The replacing catalog must be past the checkpoint for this test - it is at " + replacingVersion +
					", the checkpoint at " + checkpoint.sinceVersion() + "."
			);

			CatalogChangeCaptureResumePositionTest.this.evita.replaceCatalog(REPLACING_CATALOG, TEST_CATALOG);

			// the versions of the new catalog cover the checkpoint, so only the identity can tell them apart
			final ChangeCaptureResumePositionInvalidException refusal = assertThrows(
				ChangeCaptureResumePositionInvalidException.class,
				() -> register(TEST_CATALOG, productRequest(checkpoint.catalogId(), checkpoint.sinceVersion())),
				"A checkpoint of the replaced catalog was accepted by a replacement that happens to be past it - the " +
					"consumer would replay versions of an unrelated lineage without being told."
			);
			assertEquals(Reason.DIFFERENT_INCARNATION, refusal.getReason());
			assertEquals(replacingVersion, refusal.getCurrentCatalogVersion());
		}

		@Test
		@DisplayName("should refuse a history read expecting the replaced catalog")
		void shouldRefuseHistoryReadExpectingReplacedCatalog() throws Exception {
			final Checkpoint checkpoint = consumeAndCheckpoint(2);
			defineAliveCatalog(REPLACING_CATALOG);
			commitEntity(REPLACING_CATALOG, 1);
			CatalogChangeCaptureResumePositionTest.this.evita.replaceCatalog(REPLACING_CATALOG, TEST_CATALOG);

			final ChangeCatalogCaptureRequest request = productRequest(checkpoint.catalogId(), 0L);
			CatalogChangeCaptureResumePositionTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					assertEquals(
						Reason.DIFFERENT_INCARNATION,
						assertThrows(
							ChangeCaptureResumePositionInvalidException.class,
							() -> session.getMutationsHistoryForward(request)
						).getReason()
					);
					assertEquals(
						Reason.DIFFERENT_INCARNATION,
						assertThrows(
							ChangeCaptureResumePositionInvalidException.class,
							() -> session.getMutationsHistoryReversed(request)
						).getReason()
					);
				}
			);
		}

	}

	@Nested
	@DisplayName("Position ahead of the catalog")
	class AheadOfCatalog {

		@Test
		@DisplayName("should refuse a position more than one version past the catalog, with or without identity")
		void shouldRefusePositionMoreThanOneVersionAhead() {
			commitEntity(TEST_CATALOG, 1);
			final Catalog catalog = liveCatalog(TEST_CATALOG);
			final long liveVersion = catalog.getVersion();

			for (UUID catalogId : new UUID[]{null, catalog.getCatalogId()}) {
				final ChangeCaptureResumePositionInvalidException refusal = assertThrows(
					ChangeCaptureResumePositionInvalidException.class,
					() -> register(TEST_CATALOG, productRequest(catalogId, liveVersion + 2)),
					"A subscription starting at version " + (liveVersion + 2) + " of a catalog at version " +
						liveVersion + " was accepted - it would wait for a version nobody knows will ever come."
				);
				assertEquals(Reason.AHEAD_OF_CATALOG, refusal.getReason());
				assertEquals(liveVersion, refusal.getCurrentCatalogVersion());
				assertEquals(catalog.getCatalogId(), refusal.getCatalogId());
			}
		}

		@Test
		@DisplayName("should accept the version the next change carries right after going live, and deliver it")
		void shouldAcceptNextVersionRightAfterGoingLive() throws Exception {
			final Catalog catalog = liveCatalog(TEST_CATALOG);
			final long liveVersion = catalog.getVersion();

			assertDoesNotThrow(() -> register(TEST_CATALOG, productRequest(null, liveVersion)));
			final Registration registration = assertDoesNotThrow(
				() -> register(TEST_CATALOG, productRequest(catalog.getCatalogId(), liveVersion + 1))
			);

			final AwaitableCaptureSubscriber<ChangeCatalogCapture> subscriber = AwaitableCaptureSubscriber.unbounded();
			registration.publisher().subscribe(subscriber);
			final long nextVersion = commitEntity(TEST_CATALOG, 1);
			assertEquals(liveVersion + 1, nextVersion);
			assertTrue(
				subscriber.awaitUntil(
					items -> versionsOf(items).contains(nextVersion), AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
				),
				"The subscription starting at the next version did not deliver it."
			);
		}

		@Test
		@DisplayName("should accept the next version in a session opened before the latest commit")
		void shouldAcceptNextVersionInSessionOlderThanObserver() throws Exception {
			final AwaitableCaptureSubscriber<ChangeCatalogCapture> subscriber = AwaitableCaptureSubscriber.unbounded();
			final long sessionVersion;
			final long committedVersion;
			try (final EvitaSessionContract olderSession = CatalogChangeCaptureResumePositionTest.this.evita
				.createReadOnlySession(TEST_CATALOG)) {
				sessionVersion = olderSession.getCatalogVersion();
				committedVersion = commitEntity(TEST_CATALOG, 1);
				assertEquals(
					sessionVersion, olderSession.getCatalogVersion(),
					"The session must still read the version before the commit for this test."
				);
				final ChangeCapturePublisher<ChangeCatalogCapture> publisher = assertDoesNotThrow(
					() -> olderSession.registerChangeCatalogCapture(productRequest(null, committedVersion + 1)),
					"The version right after the latest commit was refused because the session reads the version " +
						"before it - the live version is the last one the catalog finalized."
				);
				publisher.subscribe(subscriber);
			}
			final long nextVersion = commitEntity(TEST_CATALOG, 2);
			assertEquals(committedVersion + 1, nextVersion);
			assertTrue(
				subscriber.awaitUntil(
					items -> versionsOf(items).contains(nextVersion), AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
				),
				"The subscription did not deliver the version it was registered for."
			);
			assertEquals(List.of(nextVersion), versionsOf(subscriber.getItems()));
		}

		@Test
		@DisplayName("should accept the next version while both the session and the change observer lag behind it")
		void shouldAcceptNextVersionWhileSessionAndObserverLag() throws Exception {
			final Catalog olderCatalog = liveCatalog(TEST_CATALOG);
			final AwaitableCaptureSubscriber<ChangeCatalogCapture> subscriber = AwaitableCaptureSubscriber.unbounded();
			final long committedVersion;
			try (final EvitaSessionContract olderSession = CatalogChangeCaptureResumePositionTest.this.evita
				.createReadOnlySession(TEST_CATALOG)) {
				committedVersion = commitEntity(TEST_CATALOG, 1);
				final CatalogChangeObserver observer =
					(CatalogChangeObserver) liveCatalog(TEST_CATALOG).getTransactionManager().getChangeObserver();
				// reproduces the window between publishing a new catalog version to sessions and notifying the change
				// observer about it (`Evita#replaceCatalogReference`): a consumer learnt the new version from a fresh
				// session, while the session it registers in and the observer both still describe the version before
				observer.notifyCatalogPresentInLiveView(olderCatalog);
				final ChangeCapturePublisher<ChangeCatalogCapture> publisher;
				try {
					assertEquals(
						committedVersion - 1, olderSession.getCatalogVersion(),
						"The session must still read the version before the commit for this test."
					);
					publisher = assertDoesNotThrow(
						() -> olderSession.registerChangeCatalogCapture(productRequest(null, committedVersion + 1)),
						"The version right after the latest commit was refused because neither the session nor the " +
							"change observer has caught up with that commit yet - the live version is the last one " +
							"the catalog finalized, which precedes both."
					);
					assertDoesNotThrow(
						() -> register(TEST_CATALOG, productRequest(null, committedVersion + 1)).publisher().close(),
						"A fresh session sees the commit, the observer does not - the position must pass as well."
					);
				} finally {
					observer.notifyCatalogPresentInLiveView(liveCatalog(TEST_CATALOG));
				}
				publisher.subscribe(subscriber);
			}
			final long nextVersion = commitEntity(TEST_CATALOG, 2);
			assertEquals(committedVersion + 1, nextVersion);
			assertTrue(
				subscriber.awaitUntil(
					items -> versionsOf(items).contains(nextVersion), AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
				),
				"The subscription did not deliver the version it was registered for."
			);
			assertEquals(List.of(nextVersion), versionsOf(subscriber.getItems()));
		}

	}

	@Nested
	@DisplayName("Registration directly with the transaction manager")
	class TransactionManagerRegistration {

		@Test
		@DisplayName("should verify the resume position at the transaction manager, which every registration passes")
		void shouldVerifyResumePositionAtTransactionManager() {
			commitEntity(TEST_CATALOG, 1);
			final Catalog catalog = liveCatalog(TEST_CATALOG);
			final TransactionManager transactionManager = catalog.getTransactionManager();
			final long liveVersion = catalog.getVersion();

			final ChangeCaptureResumePositionInvalidException foreignRefusal = assertThrows(
				ChangeCaptureResumePositionInvalidException.class,
				() -> transactionManager.registerObserver(productRequest(UUID.randomUUID(), liveVersion)),
				"A position of another incarnation registered with the transaction manager directly was accepted - " +
					"the publisher would deliver this catalog's captures to a consumer of another one."
			);
			assertEquals(Reason.DIFFERENT_INCARNATION, foreignRefusal.getReason());
			assertEquals(catalog.getCatalogId(), foreignRefusal.getCatalogId());
			assertEquals(liveVersion, foreignRefusal.getCurrentCatalogVersion());

			final ChangeCaptureResumePositionInvalidException aheadRefusal = assertThrows(
				ChangeCaptureResumePositionInvalidException.class,
				() -> transactionManager.registerObserver(productRequest(null, liveVersion + 2)),
				"A position ahead of the catalog registered with the transaction manager directly was accepted."
			);
			assertEquals(Reason.AHEAD_OF_CATALOG, aheadRefusal.getReason());

			final ChangeCapturePublisher<ChangeCatalogCapture> accepted = assertDoesNotThrow(
				() -> transactionManager.registerObserver(productRequest(catalog.getCatalogId(), liveVersion + 1))
			);
			accepted.close();
		}

	}

	@Nested
	@DisplayName("Closed transaction manager")
	class ClosedTransactionManager {

		@Test
		@DisplayName("should refuse a publication after the catalog was deactivated, before touching any state")
		void shouldRefusePublicationAfterDeactivation() {
			commitEntity(TEST_CATALOG, 1);
			final Catalog terminatedCatalog = liveCatalog(TEST_CATALOG);
			final TransactionManager transactionManager = terminatedCatalog.getTransactionManager();
			assertTrue(terminatedCatalog.getVersion() > 0L, "The catalog must be past version 0 for this test.");

			CatalogChangeCaptureResumePositionTest.this.evita.deactivateCatalog(TEST_CATALOG);
			assertNull(transactionManager.getLivingCatalog(), "Deactivation did not close the transaction manager.");

			// a publication racing the termination - a commit or a write-ahead log replay finishing late - must be
			// told the manager is gone, not fail on the state the close released or bring that state back
			assertThrows(InstanceTerminatedException.class, terminatedCatalog::notifyCatalogPresentInLiveView);
			assertNull(transactionManager.getLivingCatalog(), "The closed manager got a living catalog back.");
			assertThrows(
				InstanceTerminatedException.class, transactionManager::getLastFinalizedCatalog,
				"The closed manager got a finalized catalog back."
			);
		}

		@Test
		@DisplayName("should refuse a publication of a warming-up catalog after shutdown, before touching any state")
		void shouldRefuseWarmingUpPublicationAfterShutdown() {
			CatalogChangeCaptureResumePositionTest.this.evita.defineCatalog(WARMING_UP_CATALOG);
			final Catalog terminatedCatalog = liveCatalog(WARMING_UP_CATALOG);
			final TransactionManager transactionManager = terminatedCatalog.getTransactionManager();
			assertEquals(0L, terminatedCatalog.getVersion(), "The catalog must be at version 0 for this test.");

			CatalogChangeCaptureResumePositionTest.this.evita.close();
			assertNull(transactionManager.getLivingCatalog(), "Shutdown did not close the transaction manager.");

			// version 0 skips the ordering checks, so nothing but the closed state itself can refuse this one
			assertThrows(InstanceTerminatedException.class, terminatedCatalog::notifyCatalogPresentInLiveView);
			assertNull(transactionManager.getLivingCatalog(), "The closed manager got a living catalog back.");
			assertThrows(
				InstanceTerminatedException.class, transactionManager::getLastFinalizedCatalog,
				"The closed manager got a finalized catalog back."
			);
		}

		@Test
		@DisplayName("should refuse a write-ahead log drain after the catalog was deactivated")
		void shouldRefuseWriteAheadLogDrainAfterDeactivation() {
			final long committedVersion = commitEntity(TEST_CATALOG, 1);
			final TransactionManager transactionManager = liveCatalog(TEST_CATALOG).getTransactionManager();

			CatalogChangeCaptureResumePositionTest.this.evita.deactivateCatalog(TEST_CATALOG);
			assertNull(transactionManager.getLivingCatalog(), "Deactivation did not close the transaction manager.");

			// a drain that starts once its manager is closed - the background drainer or a trunk incorporation
			// task running late - has no catalog left to incorporate into, and must say so
			assertThrows(
				InstanceTerminatedException.class,
				() -> transactionManager.processEntireWriteAheadLog(committedVersion + 1, version -> {})
			);
		}

		@Test
		@DisplayName("should attach the termination to a resume position refused by a closed manager")
		void shouldAttachTerminationToResumePositionRefusedByClosedManager() {
			final long committedVersion = commitEntity(TEST_CATALOG, 1);
			final Catalog catalog = liveCatalog(TEST_CATALOG);
			final TransactionManager transactionManager = catalog.getTransactionManager();

			CatalogChangeCaptureResumePositionTest.this.evita.deactivateCatalog(TEST_CATALOG);
			assertNull(transactionManager.getLivingCatalog(), "Deactivation did not close the transaction manager.");

			// the refusal is decided before the oldest available version is looked up; the lookup is the part that
			// needs the closed state, and its failure travels with the refusal rather than replacing it
			final ChangeCaptureResumePositionInvalidException refusal = assertThrows(
				ChangeCaptureResumePositionInvalidException.class,
				() -> transactionManager.registerObserver(productRequest(null, committedVersion + 2))
			);
			assertEquals(Reason.AHEAD_OF_CATALOG, refusal.getReason());
			assertNull(refusal.getCatalogVersion(), "A closed manager cannot know the oldest available version.");
			assertEquals(1, refusal.getSuppressed().length, "The failed lookup was not attached to the refusal.");
			assertInstanceOf(
				InstanceTerminatedException.class, refusal.getSuppressed()[0],
				"The lookup failed for a reason other than the closed manager."
			);
		}

		@Test
		@DisplayName("should refuse commit pipeline work that needs the living catalog after deactivation")
		void shouldRefuseCommitPipelineWorkAfterDeactivation() {
			final long committedVersion = commitEntity(TEST_CATALOG, 1);
			final TransactionManager transactionManager = liveCatalog(TEST_CATALOG).getTransactionManager();

			CatalogChangeCaptureResumePositionTest.this.evita.deactivateCatalog(TEST_CATALOG);
			assertNull(transactionManager.getLivingCatalog(), "Deactivation did not close the transaction manager.");

			// a commit still in the pipeline when its catalog went away reaches these late - each of them needs the
			// living catalog the close released, and must say the manager is gone rather than fail on its absence
			assertAll(
				() -> assertThrows(InstanceTerminatedException.class, transactionManager::syncWal, "WAL sync"),
				() -> assertThrows(
					InstanceTerminatedException.class,
					() -> transactionManager.waitUntilLiveVersionReaches(committedVersion + 1),
					"waiting for the live view"
				),
				() -> assertThrows(
					InstanceTerminatedException.class,
					() -> transactionManager.identifyConflicts(
						committedVersion, transactionManager.getLastAssignedCatalogVersion() + 1,
						OffsetDateTime.now(), Set.of()
					),
					"conflict resolution"
				)
			);
		}

		@Test
		@DisplayName("should release a reserved catalog version after deactivation")
		void shouldReleaseReservedCatalogVersionAfterDeactivation() {
			commitEntity(TEST_CATALOG, 1);
			final TransactionManager transactionManager = liveCatalog(TEST_CATALOG).getTransactionManager();

			CatalogChangeCaptureResumePositionTest.this.evita.deactivateCatalog(TEST_CATALOG);
			assertNull(transactionManager.getLivingCatalog(), "Deactivation did not close the transaction manager.");

			// releasing a reservation is the cleanup of a commit that failed - often because the manager closed
			// under it - so it must go through rather than replace that failure with one of its own
			final long lastAssignedVersion = transactionManager.getLastAssignedCatalogVersion();
			transactionManager.getNextCatalogVersionToAssign();
			assertDoesNotThrow(() -> transactionManager.notifyCatalogVersionDropped(1, 0));
			assertEquals(
				lastAssignedVersion, transactionManager.getLastAssignedCatalogVersion(),
				"The reserved catalog version was not released."
			);
		}

	}

	@Nested
	@DisplayName("Positions that remain valid")
	class ValidPositions {

		@Test
		@DisplayName("should accept the checkpoint of a renamed catalog, which keeps its identity")
		void shouldAcceptCheckpointOfRenamedCatalog() throws Exception {
			final Checkpoint checkpoint = consumeAndCheckpoint(2);
			CatalogChangeCaptureResumePositionTest.this.evita.renameCatalog(TEST_CATALOG, RENAMED_CATALOG);

			final Registration registration = assertDoesNotThrow(
				() -> register(RENAMED_CATALOG, productRequest(checkpoint.catalogId(), checkpoint.sinceVersion())),
				"Renaming keeps the catalog incarnation and its version sequence, so its checkpoints stay valid."
			);
			assertEquals(checkpoint.catalogId(), registration.catalogId());
			final AwaitableCaptureSubscriber<ChangeCatalogCapture> subscriber = AwaitableCaptureSubscriber.unbounded();
			registration.publisher().subscribe(subscriber);
			final long nextVersion = commitEntity(RENAMED_CATALOG, 100);
			assertTrue(
				subscriber.awaitUntil(
					items -> versionsOf(items).contains(nextVersion), AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
				),
				"The subscription on the renamed catalog did not deliver its next change."
			);
			assertStampedWith(checkpoint.catalogId(), subscriber.getItems(), "the renamed catalog");
		}

		@Test
		@DisplayName("should accept a checkpoint after an engine restart and catch up from the write-ahead log")
		void shouldAcceptCheckpointAfterRestartAndCatchUpFromWal() throws Exception {
			final UUID catalogId = liveCatalog(TEST_CATALOG).getCatalogId();
			final List<Long> versions = List.of(
				commitEntity(TEST_CATALOG, 1), commitEntity(TEST_CATALOG, 2), commitEntity(TEST_CATALOG, 3)
			);
			CatalogChangeCaptureResumePositionTest.this.evita.close();
			CatalogChangeCaptureResumePositionTest.this.evita = newEvita();
			assertEquals(catalogId, liveCatalog(TEST_CATALOG).getCatalogId(), "The identity is persisted.");

			// nothing was committed since the restart, so the shared publisher has no ring buffer yet and every
			// capture below is read back from the write-ahead log
			final Registration registration = assertDoesNotThrow(
				() -> register(TEST_CATALOG, productRequest(catalogId, versions.get(0)))
			);
			final AwaitableCaptureSubscriber<ChangeCatalogCapture> subscriber = AwaitableCaptureSubscriber.unbounded();
			registration.publisher().subscribe(subscriber);
			assertTrue(
				subscriber.awaitUntil(
					items -> versionsOf(items).equals(versions), AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
				),
				"Expected versions " + versions + ", received " + versionsOf(subscriber.getItems()) + "."
			);
			assertNull(subscriber.getError());
			assertStampedWith(catalogId, subscriber.getItems(), "the write-ahead log catch-up");

			final ChangeCaptureResumePositionInvalidException refusal = assertThrows(
				ChangeCaptureResumePositionInvalidException.class,
				() -> register(TEST_CATALOG, productRequest(UUID.randomUUID(), versions.get(0)))
			);
			assertEquals(Reason.DIFFERENT_INCARNATION, refusal.getReason());
		}

	}

	@Nested
	@DisplayName("Catalog identity on captures")
	class CaptureIdentity {

		@Test
		@DisplayName("should stamp the catalog identity on every capture delivered live")
		void shouldStampCatalogIdOnLiveCaptures() throws Exception {
			final Registration registration = register(TEST_CATALOG, productRequest(null, null));
			final AwaitableCaptureSubscriber<ChangeCatalogCapture> subscriber = AwaitableCaptureSubscriber.unbounded();
			registration.publisher().subscribe(subscriber);
			final long version = commitEntity(TEST_CATALOG, 1);
			assertTrue(
				subscriber.awaitUntil(
					items -> versionsOf(items).contains(version), AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
				),
				"The live subscription did not deliver the committed change."
			);

			assertStampedWith(registration.catalogId(), subscriber.getItems(), "the live ring buffer");
		}

		@Test
		@DisplayName("should refuse to publish a catalog of another incarnation before touching any state")
		void shouldRefuseToPublishAnotherIncarnationBeforeTouchingAnyState() {
			defineAliveCatalog(REPLACING_CATALOG);
			final ChangeCatalogCapturePublisher publisher = (ChangeCatalogCapturePublisher) register(
				TEST_CATALOG, productRequest(null, null)
			).publisher();
			publisher.subscribe(AwaitableCaptureSubscriber.unbounded());
			final ChangeCatalogCaptureSharedPublisher sharedPublisher = publisher.getSharedPublisher();
			final TransactionManager transactionManager = liveCatalog(TEST_CATALOG).getTransactionManager();
			final Catalog livingBefore = transactionManager.getLivingCatalog();
			final Catalog finalizedBefore = transactionManager.getLastFinalizedCatalog();
			final long finalizedVersionBefore = transactionManager.getLastFinalizedCatalogVersion();
			final Catalog otherIncarnation = liveCatalog(REPLACING_CATALOG);
			assertTrue(
				livingBefore.getVersion() <= otherIncarnation.getVersion() &&
					otherIncarnation.getVersion() <= finalizedVersionBefore,
				"The other incarnation must pass the version checks for this test, so that only its identity can " +
					"refuse it - it is at " + otherIncarnation.getVersion() + ", the transaction manager at " +
					livingBefore.getVersion() + " (finalized " + finalizedVersionBefore + ")."
			);

			// the shared publishers stamp every capture with the identity they were created with, so another
			// incarnation reaching them is a programming error - and it must be refused before the transaction
			// manager or the change observer moved on, or each of them would describe a different catalog
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> transactionManager.notifyCatalogPresentInLiveView(otherIncarnation)
			);
			assertTrue(error.getMessage().contains(otherIncarnation.getCatalogId().toString()), error.getMessage());

			assertSame(livingBefore, transactionManager.getLivingCatalog(), "The living catalog was exchanged.");
			assertSame(
				finalizedBefore, transactionManager.getLastFinalizedCatalog(), "The finalized catalog was exchanged."
			);
			assertEquals(finalizedVersionBefore, transactionManager.getLastFinalizedCatalogVersion());
			assertEquals(TEST_CATALOG, transactionManager.getCatalogName(), "The catalog name was exchanged.");
			assertSame(
				livingBefore, sharedPublisher.getCatalog(),
				"The change observer passed the refused catalog on to its shared publishers."
			);
		}

		@Test
		@DisplayName("should stamp the catalog identity on history captures and honour the expected identity")
		void shouldStampCatalogIdOnHistoryCaptures() {
			final UUID catalogId = liveCatalog(TEST_CATALOG).getCatalogId();
			final long firstVersion = commitEntity(TEST_CATALOG, 1);
			final long lastVersion = commitEntity(TEST_CATALOG, 2);

			CatalogChangeCaptureResumePositionTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final List<ChangeCatalogCapture> forward = readAll(
						session.getMutationsHistoryForward(productRequest(catalogId, firstVersion))
					);
					assertEquals(List.of(firstVersion, lastVersion), versionsOf(forward));
					assertStampedWith(catalogId, forward, "the forward history");

					final List<ChangeCatalogCapture> reversed = readAll(
						session.getMutationsHistoryReversed(productRequest(catalogId, lastVersion))
					);
					assertEquals(List.of(lastVersion, firstVersion), versionsOf(reversed));
					assertStampedWith(catalogId, reversed, "the reversed history");

					// unlike a subscription, the forward history keeps reading a future version as an empty stream
					final long futureVersion = lastVersion + 10;
					assertTrue(
						readAll(session.getMutationsHistoryForward(productRequest(catalogId, futureVersion))).isEmpty()
					);
					assertTrue(
						readAll(session.getMutationsHistoryForward(productRequest(null, futureVersion))).isEmpty()
					);
				}
			);
		}

	}

	@Nested
	@DisplayName("Live catalog version reported by heartbeats")
	class HeartbeatVersion {

		@Test
		@DisplayName("should follow the incarnation through commits and a rename, never the catalog name")
		void shouldFollowIncarnationThroughCommitsAndRename() {
			final LongSupplier versionSupplier = liveVersionSupplier(TEST_CATALOG);
			assertEquals(liveCatalog(TEST_CATALOG).getVersion(), versionSupplier.getAsLong());

			final long committedVersion = commitEntity(TEST_CATALOG, 1);
			assertEquals(
				committedVersion, versionSupplier.getAsLong(),
				"The supplier outlives the session it was created in and must follow the commits after it."
			);

			CatalogChangeCaptureResumePositionTest.this.evita.renameCatalog(TEST_CATALOG, RENAMED_CATALOG);
			final long renamedVersion = commitEntity(RENAMED_CATALOG, 2);
			assertEquals(renamedVersion, versionSupplier.getAsLong(), "A rename keeps the incarnation.");

			// the old name is taken by an unrelated catalog that moves past the renamed one
			defineAliveCatalog(TEST_CATALOG);
			for (int primaryKey = 1; liveCatalog(TEST_CATALOG).getVersion() <= renamedVersion; primaryKey++) {
				commitEntity(TEST_CATALOG, primaryKey);
			}
			assertEquals(
				renamedVersion, versionSupplier.getAsLong(),
				"The supplier followed the name to catalog version " + liveCatalog(TEST_CATALOG).getVersion() +
					" of an unrelated catalog."
			);
		}

		@Test
		@DisplayName("should keep the last version of a replaced incarnation and follow the replacing one under its new name")
		void shouldKeepLastVersionOfReplacedIncarnation() {
			commitEntity(TEST_CATALOG, 1);
			final long replacedVersion = liveCatalog(TEST_CATALOG).getVersion();
			final LongSupplier replacedSupplier = liveVersionSupplier(TEST_CATALOG);
			defineAliveCatalog(REPLACING_CATALOG);
			for (int primaryKey = 1; liveCatalog(REPLACING_CATALOG).getVersion() <= replacedVersion; primaryKey++) {
				commitEntity(REPLACING_CATALOG, primaryKey);
			}
			final LongSupplier replacingSupplier = liveVersionSupplier(REPLACING_CATALOG);

			CatalogChangeCaptureResumePositionTest.this.evita.replaceCatalog(REPLACING_CATALOG, TEST_CATALOG);

			final long reportedAfterReplacement = assertDoesNotThrow(
				replacedSupplier::getAsLong,
				"The heartbeat calls the supplier unguarded - a replaced incarnation must not make it throw."
			);
			assertEquals(
				replacedVersion, reportedAfterReplacement,
				"The supplier of the replaced incarnation must keep reporting its last version - neither switch to " +
					"the replacing catalog, which carries its name now, nor give up."
			);
			final long nextVersion = commitEntity(TEST_CATALOG, 100);
			assertEquals(replacedVersion, replacedSupplier.getAsLong());
			assertEquals(
				nextVersion, replacingSupplier.getAsLong(),
				"The replacing incarnation lives on under the replaced name and its supplier must keep following it."
			);
		}

	}

	/**
	 * Creates the live catalog version supplier of a session opened on the passed catalog - the way the gRPC server
	 * creates it for the heartbeats of a change capture stream. The session is closed right away; the supplier
	 * must keep working without it.
	 *
	 * @param catalogName the catalog to open the session on
	 * @return the supplier
	 */
	@Nonnull
	private LongSupplier liveVersionSupplier(@Nonnull String catalogName) {
		return this.evita.queryCatalog(
			catalogName,
			session -> {
				return assertInstanceOf(EvitaInternalSessionContract.class, session).createLiveCatalogVersionSupplier();
			}
		);
	}

	/**
	 * Consumes the product changes of the passed number of transactions through a subscription from the head of
	 * the stream, and records the resume position a consumer would store: the identity the captures carry and the
	 * version after the last one received.
	 *
	 * @param transactionCount number of transactions to commit and consume
	 * @return the recorded resume position
	 */
	@Nonnull
	private Checkpoint consumeAndCheckpoint(int transactionCount) throws InterruptedException {
		final Registration registration = register(TEST_CATALOG, productRequest(null, null));
		final AwaitableCaptureSubscriber<ChangeCatalogCapture> subscriber = AwaitableCaptureSubscriber.unbounded();
		registration.publisher().subscribe(subscriber);
		long lastVersion = -1L;
		for (int primaryKey = 1; primaryKey <= transactionCount; primaryKey++) {
			lastVersion = commitEntity(TEST_CATALOG, primaryKey);
		}
		final long expectedVersion = lastVersion;
		assertTrue(
			subscriber.awaitUntil(
				items -> versionsOf(items).contains(expectedVersion), AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS
			),
			"The subscription did not deliver the committed changes - the fixture did not reach the state under test."
		);
		final List<ChangeCatalogCapture> items = subscriber.getItems();
		final ChangeCatalogCapture lastCapture = items.get(items.size() - 1);
		assertEquals(registration.catalogId(), lastCapture.catalogId());
		registration.publisher().close();
		return new Checkpoint(lastCapture.catalogId(), lastCapture.version() + 1);
	}

	/**
	 * Registers a change capture publisher for the request in a session that is closed right away, and reports the
	 * identity of the catalog the session was bound to - read in the same session, as a consumer must.
	 *
	 * @param catalogName the catalog to register with
	 * @param request     the change capture request
	 * @return the publisher and the catalog identity
	 */
	@Nonnull
	private Registration register(@Nonnull String catalogName, @Nonnull ChangeCatalogCaptureRequest request) {
		return this.evita.queryCatalog(
			catalogName,
			session -> {
				return new Registration(session.getCatalogId(), session.registerChangeCatalogCapture(request));
			}
		);
	}

	/**
	 * Reads the whole passed history stream and closes it.
	 *
	 * @param history the history stream
	 * @return the captures of the stream
	 */
	@Nonnull
	private static List<ChangeCatalogCapture> readAll(@Nonnull Stream<ChangeCatalogCapture> history) {
		try (history) {
			return history.toList();
		}
	}

	/**
	 * Creates an engine over the test storage with a small change capture ring buffer, and waits until it has loaded
	 * whatever the storage holds.
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
								.build()
						)
						.build()
				)
				.build()
		);
		theEvita.waitUntilFullyInitialized();
		return theEvita;
	}

	/**
	 * Defines a catalog with the product entity type and lets it go live.
	 *
	 * @param catalogName the name of the catalog
	 */
	private void defineAliveCatalog(@Nonnull String catalogName) {
		this.evita.defineCatalog(catalogName);
		this.evita.updateCatalog(
			catalogName,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute("code", String.class)
					.updateVia(session);
				session.goLiveAndClose();
			}
		);
	}

	/**
	 * Commits one transaction upserting a single product and waits until it is visible.
	 *
	 * @param catalogName the catalog to commit to
	 * @param primaryKey  the primary key of the product
	 * @return the catalog version the transaction produced
	 */
	private long commitEntity(@Nonnull String catalogName, int primaryKey) {
		this.evita.updateCatalog(
			catalogName,
			session -> {
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, primaryKey).setAttribute("code", "p" + primaryKey)
				);
			},
			CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
		);
		return liveCatalog(catalogName).getVersion();
	}

	/**
	 * Returns the catalog instance currently in the live view.
	 *
	 * @param catalogName the name of the catalog
	 * @return the live catalog
	 */
	@Nonnull
	private Catalog liveCatalog(@Nonnull String catalogName) {
		final CatalogContract catalog = this.evita.getCatalogInstance(catalogName).orElseThrow();
		return assertInstanceOf(
			Catalog.class, catalog,
			"The catalog did not load - it is `" + catalog.getClass().getSimpleName() + "`."
		);
	}

	/**
	 * The resume position a consumer stores.
	 *
	 * @param catalogId    the identity of the catalog incarnation the captures came from
	 * @param sinceVersion the version to resume from
	 */
	private record Checkpoint(@Nonnull UUID catalogId, long sinceVersion) {
	}

	/**
	 * A registered publisher together with the identity of the catalog the registering session was bound to.
	 *
	 * @param catalogId the identity of the catalog the session was bound to
	 * @param publisher the registered publisher
	 */
	private record Registration(
		@Nonnull UUID catalogId,
		@Nonnull ChangeCapturePublisher<ChangeCatalogCapture> publisher
	) {
	}

}
