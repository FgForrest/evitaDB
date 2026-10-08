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

package io.evitadb.driver;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.exception.ChangeCaptureResumePositionInvalidException;
import io.evitadb.api.exception.ChangeCaptureResumePositionInvalidException.Reason;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCapturePublisher;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureCriteria;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.dataType.ContainerType;
import io.evitadb.driver.config.ClientTimeoutOptions;
import io.evitadb.driver.config.ClientTlsOptions;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.HostDefinition;
import io.evitadb.externalApi.grpc.GrpcProvider;
import io.evitadb.externalApi.system.SystemProvider;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import io.evitadb.utils.CertificateUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.CDC;
import static io.evitadb.test.TestTags.DRIVER;
import static io.evitadb.test.TestTags.GRPC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies, end to end through the gRPC server and the Java driver, that a catalog change capture resume position
 * carries the identity of the catalog incarnation it was recorded on: the captures a driver consumer receives carry
 * it, a position of a replaced catalog is refused with a typed {@link ChangeCaptureResumePositionInvalidException}
 * - from `subscribe()` and in the consumer's `onError`, with every field intact - and history reads apply the same
 * identity check.
 *
 * The failure behind it: a consumer re-subscribed after `replaceCatalog` with its stored version, the new catalog
 * numbered its versions from scratch, and the subscription stayed open and silent while heartbeats kept flowing.
 * Over gRPC the refusal used to arrive, if at all, as an `UNKNOWN` status with no code, so a driver consumer could
 * not even tell it apart from a broken connection.
 *
 * Every test works with catalogs of its own, so the tests share one server.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Catalog change capture resume position over the Java driver")
@ExtendWith(EvitaParameterResolver.class)
@Tag(DRIVER)
@Tag(GRPC)
@Tag(CDC)
class EvitaClientChangeCaptureResumePositionTest {
	private static final String DATA_SET = "EvitaClientChangeCaptureResumePositionDataSet";
	/**
	 * Upper bound of every wait for an asynchronous signal - a positive wait returns as soon as it arrives.
	 */
	private static final long AWAIT_TIMEOUT_SECONDS = 30L;
	/**
	 * Source of unique catalog names, so that the tests never see each other's catalogs.
	 */
	private static final AtomicInteger CATALOG_SEQUENCE = new AtomicInteger();

	@DataSet(
		value = DATA_SET, openWebApi = {GrpcProvider.CODE, SystemProvider.CODE}, readOnly = false,
		destroyAfterClass = true
	)
	static EvitaClient initDataSet(EvitaServer evitaServer) {
		final ApiOptions apiOptions = evitaServer.getExternalApiServer().getApiOptions();
		final HostDefinition grpcHost = apiOptions.getEndpointConfiguration(GrpcProvider.CODE).getHost()[0];
		final HostDefinition systemHost = apiOptions.getEndpointConfiguration(SystemProvider.CODE).getHost()[0];
		final String serverCertificates = apiOptions.certificate().getFolderPath().toString();
		final int lastDash = serverCertificates.lastIndexOf('-');
		assertTrue(lastDash > 0, "Dash not found! Look at the evita-configuration.yml in test resources!");
		final Path clientCertificates = Path.of(serverCertificates.substring(0, lastDash) + "-client");
		return new EvitaClient(
			EvitaClientConfiguration.builder()
				.host(grpcHost.hostAddress())
				.port(grpcHost.port())
				.systemApiPort(systemHost.port())
				.tls(
					ClientTlsOptions.builder()
						.mtlsEnabled(false)
						.certificateFolderPath(clientCertificates)
						.certificateFileName(Path.of(CertificateUtils.getGeneratedClientCertificateFileName()))
						.certificateKeyFileName(
							Path.of(CertificateUtils.getGeneratedClientCertificatePrivateKeyFileName())
						)
						.build()
				)
				.timeouts(ClientTimeoutOptions.builder().timeout(1, TimeUnit.MINUTES).build())
				.build()
		);
	}

	/**
	 * Returns a catalog name no other test uses.
	 *
	 * @param prefix readable part of the name
	 * @return the unique name
	 */
	@Nonnull
	private static String uniqueCatalogName(@Nonnull String prefix) {
		return prefix + CATALOG_SEQUENCE.incrementAndGet();
	}

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
			.content(ChangeCaptureContent.HEADER)
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
	 * Defines a catalog with the product entity type and lets it go live.
	 *
	 * @param evitaClient the client
	 * @param catalogName the name of the catalog
	 */
	private static void defineAliveCatalog(@Nonnull EvitaClient evitaClient, @Nonnull String catalogName) {
		evitaClient.defineCatalog(catalogName);
		evitaClient.updateCatalog(
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
	 * @param evitaClient the client
	 * @param catalogName the catalog to commit to
	 * @param primaryKey  the primary key of the product
	 * @return the catalog version the transaction produced
	 */
	private static long commitEntity(@Nonnull EvitaClient evitaClient, @Nonnull String catalogName, int primaryKey) {
		evitaClient.updateCatalog(
			catalogName,
			session -> {
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, primaryKey).setAttribute("code", "p" + primaryKey)
				);
			},
			CommitBehavior.WAIT_FOR_CHANGES_VISIBLE
		);
		return catalogVersion(evitaClient, catalogName);
	}

	/**
	 * Returns the current version of the catalog.
	 *
	 * @param evitaClient the client
	 * @param catalogName the catalog
	 * @return the version
	 */
	private static long catalogVersion(@Nonnull EvitaClient evitaClient, @Nonnull String catalogName) {
		return evitaClient.queryCatalog(catalogName, EvitaSessionContract::getCatalogVersion);
	}

	/**
	 * Returns the identity of the catalog currently carrying the name.
	 *
	 * @param evitaClient the client
	 * @param catalogName the catalog
	 * @return the identity
	 */
	@Nonnull
	private static UUID catalogId(@Nonnull EvitaClient evitaClient, @Nonnull String catalogName) {
		return evitaClient.queryCatalog(catalogName, EvitaSessionContract::getCatalogId);
	}

	/**
	 * Consumes the product changes of the passed number of transactions through a subscription from the head of
	 * the stream and records the resume position a consumer would store - the identity the captures carry and the
	 * version after the last one received.
	 *
	 * @param evitaClient      the client
	 * @param catalogName      the catalog to consume
	 * @param transactionCount number of transactions to commit and consume
	 * @return the recorded resume position
	 */
	@Nonnull
	private static Checkpoint consumeAndCheckpoint(
		@Nonnull EvitaClient evitaClient,
		@Nonnull String catalogName,
		int transactionCount
	) throws InterruptedException {
		final RecordingSubscriber subscriber = new RecordingSubscriber();
		final ChangeCapturePublisher<ChangeCatalogCapture> publisher = evitaClient.queryCatalog(
			catalogName,
			session -> {
				final ChangeCapturePublisher<ChangeCatalogCapture> thePublisher =
					session.registerChangeCatalogCapture(productRequest(null, null));
				thePublisher.subscribe(subscriber);
				return thePublisher;
			}
		);
		long lastVersion = -1L;
		for (int primaryKey = 1; primaryKey <= transactionCount; primaryKey++) {
			lastVersion = commitEntity(evitaClient, catalogName, primaryKey);
		}
		final long expectedVersion = lastVersion;
		assertTrue(
			subscriber.awaitItems(items -> items.stream().anyMatch(it -> it.version() == expectedVersion)),
			"The subscription did not deliver the committed changes - the fixture did not reach the state under test."
		);
		final ChangeCatalogCapture lastCapture = subscriber.items.get(subscriber.items.size() - 1);
		assertStampedWith(catalogId(evitaClient, catalogName), subscriber.items);
		publisher.close();
		return new Checkpoint(lastCapture.catalogId(), lastCapture.version() + 1);
	}

	/**
	 * Subscribes to the catalog with the passed request inside a session and expects the subscription to be
	 * refused - both by `subscribe()` and through the subscriber's `onError`, with the same exception type.
	 *
	 * @param evitaClient the client
	 * @param catalogName the catalog to subscribe to
	 * @param request     the request with the refused position
	 * @return the exception `subscribe()` failed with
	 */
	@Nonnull
	private static ChangeCaptureResumePositionInvalidException expectRefusedSubscription(
		@Nonnull EvitaClient evitaClient,
		@Nonnull String catalogName,
		@Nonnull ChangeCatalogCaptureRequest request
	) throws InterruptedException {
		final RecordingSubscriber subscriber = new RecordingSubscriber();
		final ChangeCaptureResumePositionInvalidException refusal = evitaClient.queryCatalog(
			catalogName,
			session -> {
				final ChangeCapturePublisher<ChangeCatalogCapture> publisher =
					session.registerChangeCatalogCapture(request);
				return assertThrows(
					ChangeCaptureResumePositionInvalidException.class,
					() -> publisher.subscribe(subscriber),
					"subscribe() must fail with the typed refusal - neither stay silent nor wrap it into an " +
						"internal error the consumer cannot react to."
				);
			}
		);
		assertTrue(subscriber.errorDelivered.await(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS), "No onError arrived.");
		final ChangeCaptureResumePositionInvalidException delivered = assertInstanceOf(
			ChangeCaptureResumePositionInvalidException.class, subscriber.error.get(),
			"The consumer's onError must receive the typed refusal too."
		);
		assertEquals(refusal.getReason(), delivered.getReason());
		assertEquals(refusal.getPublicMessage(), delivered.getPublicMessage());
		assertTrue(subscriber.items.isEmpty(), "A refused subscription delivered captures: " + subscriber.items);
		return refusal;
	}

	/**
	 * Asserts that every passed capture carries the passed identity.
	 *
	 * @param catalogId the identity the captures must carry
	 * @param captures  the captures
	 */
	private static void assertStampedWith(@Nonnull UUID catalogId, @Nonnull List<ChangeCatalogCapture> captures) {
		assertFalse(captures.isEmpty(), "No capture was delivered - nothing to check.");
		for (ChangeCatalogCapture capture : captures) {
			assertEquals(
				catalogId, capture.catalogId(),
				"The capture of version " + capture.version() + "/" + capture.index() + " received through the " +
					"driver does not carry the identity of its catalog incarnation."
			);
		}
	}

	@Nested
	@DisplayName("Subscription after a catalog replacement")
	class ReplacedCatalog {

		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("should refuse the checkpoint with typed errors when the replacing catalog is behind it")
		void shouldRefuseCheckpointWhenReplacingCatalogIsBehind(EvitaClient evitaClient) throws Exception {
			final String catalogName = uniqueCatalogName("replacedBehind");
			final String replacingName = uniqueCatalogName("replacingBehind");
			defineAliveCatalog(evitaClient, catalogName);
			final Checkpoint checkpoint = consumeAndCheckpoint(evitaClient, catalogName, 3);
			defineAliveCatalog(evitaClient, replacingName);
			final long replacingVersion = commitEntity(evitaClient, replacingName, 1);
			final UUID replacingCatalogId = catalogId(evitaClient, replacingName);
			assertTrue(
				replacingVersion + 1 < checkpoint.sinceVersion(),
				"The replacing catalog must be behind the checkpoint - it is at " + replacingVersion +
					", the checkpoint at " + checkpoint.sinceVersion() + "."
			);

			evitaClient.replaceCatalog(replacingName, catalogName);

			final ChangeCaptureResumePositionInvalidException refusal = expectRefusedSubscription(
				evitaClient, catalogName, productRequest(checkpoint.catalogId(), checkpoint.sinceVersion())
			);
			assertEquals(Reason.DIFFERENT_INCARNATION, refusal.getReason());
			assertEquals(replacingCatalogId, refusal.getCatalogId());
			assertEquals(replacingVersion, refusal.getCurrentCatalogVersion());
			assertEquals(checkpoint.catalogId(), refusal.getRequestedCatalogId());
			assertEquals(checkpoint.sinceVersion(), refusal.getRequestedSinceVersion());

			// a consumer that never stored the identity is protected by the version alone here
			final ChangeCaptureResumePositionInvalidException aheadRefusal = expectRefusedSubscription(
				evitaClient, catalogName, productRequest(null, checkpoint.sinceVersion())
			);
			assertEquals(Reason.AHEAD_OF_CATALOG, aheadRefusal.getReason());
			assertEquals(replacingCatalogId, aheadRefusal.getCatalogId());
			assertNull(aheadRefusal.getRequestedCatalogId());

			// the remedy the exception names - rebuild from one session's snapshot and resume right after it - works
			// through the driver as well: a change committed while the rebuild runs is delivered, and the captures
			// carry the new identity
			final Checkpoint snapshot = evitaClient.queryCatalog(
				catalogName,
				session -> {
					return new Checkpoint(session.getCatalogId(), session.getCatalogVersion() + 1);
				}
			);
			assertEquals(replacingCatalogId, snapshot.catalogId());
			final long committedDuringRebuild = commitEntity(evitaClient, catalogName, 100);
			assertEquals(snapshot.sinceVersion(), committedDuringRebuild);
			final RecordingSubscriber renewed = new RecordingSubscriber();
			evitaClient.queryCatalog(
				catalogName,
				session -> {
					session.registerChangeCatalogCapture(productRequest(snapshot.catalogId(), snapshot.sinceVersion()))
						.subscribe(renewed);
				}
			);
			final long nextVersion = commitEntity(evitaClient, catalogName, 101);
			assertTrue(
				renewed.awaitItems(items -> items.stream().anyMatch(it -> it.version() == nextVersion)),
				"The renewed subscription did not deliver the next change of the replacing catalog."
			);
			assertEquals(
				List.of(committedDuringRebuild, nextVersion),
				renewed.items.stream().map(ChangeCatalogCapture::version).distinct().toList(),
				"The change committed while the consumer rebuilt its state must be delivered, not skipped."
			);
			assertStampedWith(replacingCatalogId, renewed.items);
		}

		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("should refuse the checkpoint with a typed error when the replacing catalog is past it")
		void shouldRefuseCheckpointWhenReplacingCatalogIsPastIt(EvitaClient evitaClient) throws Exception {
			final String catalogName = uniqueCatalogName("replacedPast");
			final String replacingName = uniqueCatalogName("replacingPast");
			defineAliveCatalog(evitaClient, catalogName);
			final Checkpoint checkpoint = consumeAndCheckpoint(evitaClient, catalogName, 2);
			defineAliveCatalog(evitaClient, replacingName);
			long replacingVersion = catalogVersion(evitaClient, replacingName);
			for (int primaryKey = 1; replacingVersion < checkpoint.sinceVersion() + 1; primaryKey++) {
				replacingVersion = commitEntity(evitaClient, replacingName, primaryKey);
			}

			evitaClient.replaceCatalog(replacingName, catalogName);

			// the versions of the new catalog cover the checkpoint, so only the identity tells them apart
			final ChangeCaptureResumePositionInvalidException refusal = expectRefusedSubscription(
				evitaClient, catalogName, productRequest(checkpoint.catalogId(), checkpoint.sinceVersion())
			);
			assertEquals(Reason.DIFFERENT_INCARNATION, refusal.getReason());
			assertEquals(replacingVersion, refusal.getCurrentCatalogVersion());
		}

	}

	@Nested
	@DisplayName("Position ahead of the catalog")
	class AheadOfCatalog {

		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("should refuse a position more than one version ahead with a typed error and accept the next version")
		void shouldRefusePositionAheadAndAcceptNextVersion(EvitaClient evitaClient) throws Exception {
			final String catalogName = uniqueCatalogName("ahead");
			defineAliveCatalog(evitaClient, catalogName);
			final long liveVersion = commitEntity(evitaClient, catalogName, 1);
			final UUID catalogId = catalogId(evitaClient, catalogName);

			final ChangeCaptureResumePositionInvalidException refusal = expectRefusedSubscription(
				evitaClient, catalogName, productRequest(catalogId, liveVersion + 2)
			);
			assertEquals(Reason.AHEAD_OF_CATALOG, refusal.getReason());
			assertEquals(liveVersion, refusal.getCurrentCatalogVersion());
			assertEquals(catalogId, refusal.getCatalogId());

			final RecordingSubscriber subscriber = new RecordingSubscriber();
			evitaClient.queryCatalog(
				catalogName,
				session -> {
					session.registerChangeCatalogCapture(productRequest(catalogId, liveVersion + 1))
						.subscribe(subscriber);
				}
			);
			final long nextVersion = commitEntity(evitaClient, catalogName, 2);
			assertTrue(subscriber.awaitItems(items -> items.stream().anyMatch(it -> it.version() == nextVersion)));
			assertNull(subscriber.error.get());
			assertStampedWith(catalogId, subscriber.items);
		}

	}

	@Nested
	@DisplayName("Streams of one publisher")
	class SharedPublisher {

		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("should stamp the captures of a stream registered in a session re-opened by name")
		void shouldStampCapturesOfStreamRegisteredInReopenedSession(EvitaClient evitaClient) throws Exception {
			final String catalogName = uniqueCatalogName("shared");
			defineAliveCatalog(evitaClient, catalogName);
			final UUID catalogId = catalogId(evitaClient, catalogName);
			final RecordingSubscriber inSession = new RecordingSubscriber();
			final ChangeCapturePublisher<ChangeCatalogCapture> publisher = evitaClient.queryCatalog(
				catalogName,
				session -> {
					final ChangeCapturePublisher<ChangeCatalogCapture> thePublisher =
						session.registerChangeCatalogCapture(productRequest(catalogId, null));
					thePublisher.subscribe(inSession);
					return thePublisher;
				}
			);
			// the registering session is closed now - the driver opens a new one by name for this stream
			final RecordingSubscriber afterSession = new RecordingSubscriber();
			publisher.subscribe(afterSession);

			final long nextVersion = commitEntity(evitaClient, catalogName, 1);

			for (RecordingSubscriber subscriber : new RecordingSubscriber[]{inSession, afterSession}) {
				assertTrue(subscriber.awaitItems(items -> items.stream().anyMatch(it -> it.version() == nextVersion)));
				assertStampedWith(catalogId, subscriber.items);
			}
			publisher.close();
		}

	}

	@Nested
	@DisplayName("History reads")
	class HistoryReads {

		@Test
		@UseDataSet(DATA_SET)
		@DisplayName("should refuse history reads expecting a replaced catalog and stamp the captures of the others")
		void shouldRefuseHistoryOfReplacedCatalogAndStampOthers(EvitaClient evitaClient) throws Exception {
			final String catalogName = uniqueCatalogName("history");
			final String replacingName = uniqueCatalogName("historyReplacing");
			defineAliveCatalog(evitaClient, catalogName);
			final Checkpoint checkpoint = consumeAndCheckpoint(evitaClient, catalogName, 2);
			defineAliveCatalog(evitaClient, replacingName);
			commitEntity(evitaClient, replacingName, 1);
			final UUID replacingCatalogId = catalogId(evitaClient, replacingName);
			evitaClient.replaceCatalog(replacingName, catalogName);

			evitaClient.queryCatalog(
				catalogName,
				session -> {
					final ChangeCatalogCaptureRequest staleRequest = productRequest(checkpoint.catalogId(), 0L);
					final ChangeCaptureResumePositionInvalidException forwardRefusal = assertThrows(
						ChangeCaptureResumePositionInvalidException.class,
						() -> drain(session.getMutationsHistoryForward(staleRequest))
					);
					assertEquals(Reason.DIFFERENT_INCARNATION, forwardRefusal.getReason());
					assertEquals(replacingCatalogId, forwardRefusal.getCatalogId());
					assertEquals(checkpoint.catalogId(), forwardRefusal.getRequestedCatalogId());
					final ChangeCaptureResumePositionInvalidException reversedRefusal = assertThrows(
						ChangeCaptureResumePositionInvalidException.class,
						() -> drain(session.getMutationsHistoryReversed(productRequest(checkpoint.catalogId(), null)))
					);
					assertEquals(Reason.DIFFERENT_INCARNATION, reversedRefusal.getReason());

					assertStampedWith(
						replacingCatalogId,
						drain(session.getMutationsHistoryForward(productRequest(replacingCatalogId, 0L)))
					);
					assertStampedWith(replacingCatalogId, drain(session.getMutationsHistoryReversed(productRequest(null, null))));
				}
			);
		}

		/**
		 * Reads the whole history stream and closes it.
		 *
		 * @param history the history stream
		 * @return its captures
		 */
		@Nonnull
		private static List<ChangeCatalogCapture> drain(@Nonnull Stream<ChangeCatalogCapture> history) {
			try (history) {
				return history.toList();
			}
		}

	}

	/**
	 * The resume position a consumer stores.
	 *
	 * @param catalogId    the identity the captures carried
	 * @param sinceVersion the version to resume from
	 */
	private record Checkpoint(@Nonnull UUID catalogId, long sinceVersion) {
	}

	/**
	 * Subscriber requesting everything and recording what it receives, with waits for both.
	 */
	private static final class RecordingSubscriber implements Flow.Subscriber<ChangeCatalogCapture> {
		final List<ChangeCatalogCapture> items = new CopyOnWriteArrayList<>();
		final AtomicReference<Throwable> error = new AtomicReference<>();
		final CountDownLatch errorDelivered = new CountDownLatch(1);
		private final Object monitor = new Object();

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			subscription.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(ChangeCatalogCapture item) {
			this.items.add(item);
			synchronized (this.monitor) {
				this.monitor.notifyAll();
			}
		}

		@Override
		public void onError(Throwable throwable) {
			this.error.compareAndSet(null, throwable);
			this.errorDelivered.countDown();
		}

		@Override
		public void onComplete() {
			// the tests close their publishers themselves
		}

		/**
		 * Waits until the received captures satisfy the condition.
		 *
		 * @param condition the condition over all captures received so far
		 * @return `true` when satisfied within the timeout
		 */
		boolean awaitItems(@Nonnull Predicate<List<ChangeCatalogCapture>> condition) throws InterruptedException {
			final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(AWAIT_TIMEOUT_SECONDS);
			synchronized (this.monitor) {
				while (!condition.test(this.items)) {
					final long remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
					if (remainingMillis <= 0) {
						return false;
					}
					this.monitor.wait(Math.min(remainingMillis, 100L));
				}
			}
			return true;
		}
	}

}
