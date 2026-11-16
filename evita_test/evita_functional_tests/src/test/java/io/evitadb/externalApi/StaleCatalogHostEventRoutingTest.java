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

package io.evitadb.externalApi;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.evitadb.api.CatalogState;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.requestResponse.cdc.*;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.RemoveCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.SetCatalogStateMutation;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.graphql.GraphQLManager;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;
import io.evitadb.externalApi.rest.RestManager;
import io.evitadb.externalApi.rest.configuration.RestOptions;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real CDC delivery with a busy single request worker lets engine queue drains overtake older host tasks.
 * No captures are injected: both the recording subscriber and production managers use the engine's publisher.
 * A queued executor fence observes routing only after the submitted host tasks have finished.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(REST)
@Tag(GRAPHQL)
@Tag(CDC)
@DisplayName("Stale catalog host events through real CDC")
class StaleCatalogHostEventRoutingTest implements EvitaTestSupport {
	/** Independent storage for every permutation. */
	private TestPaths paths;
	/** Real engine whose request pool is deliberately limited to one worker after fixture creation. */
	private Evita evita;

	/** Settles initial catalogs before observers subscribe, excluding boot events from the experiment. */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("StaleCatalogHostEventRoutingTest");
		try (final Evita seed = new Evita(newTestEvitaConfigurationBuilder(this.paths).build())) {
			for (final String name : List.of("source", "target")) {
				seed.defineCatalog(name);
				seed.updateCatalog(name, session -> {
					session.defineEntitySchema("Product").updateVia(session);
					session.goLiveAndClose();
				});
			}
		}
		this.evita = new Evita(
			newTestEvitaConfigurationBuilder(this.paths).server(
				ServerOptions.builder().requestThreadPool(
					ThreadPoolOptions.requestThreadPoolBuilder().minThreadCount(1).maxThreadCount(1).build()
				).build()
			).build(), true, null, null, false
		);
		this.evita.waitUntilFullyInitialized();
	}

	/** Closes subscriptions and the real executor before removing the isolated storage. */
	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/** Proves later engine delivery precedes an older host event before asserting the real routing outcome. */
	@ParameterizedTest(name = "graphql={0}, scenario={1}")
	@CsvSource({
		"false, replace", "true, replace", "false, rename", "true, rename",
		"false, reactivate", "true, reactivate"
	})
	@DisplayName("Keep the current active catalog routed after an older inactive or removed host event")
	void shouldKeepActiveCatalogRoutedWhenEngineDrainOvertakesHostEvent(boolean graphql, @Nonnull String scenario)
		throws Exception {
		final HttpService router = createManager(graphql);
		final GatedCatalogCaptureSubscriber subscriber = new GatedCatalogCaptureSubscriber();
		try (final ChangeCapturePublisher<ChangeSystemCapture> publisher = subscribe(subscriber)) {
			try {
				this.evita.defineCatalog("gate");
				assertTrue(subscriber.entered.await(30, TimeUnit.SECONDS), "CDC worker did not enter the gate");
				retireTarget(scenario.equals("rename"));
				installTarget(scenario);
				assertEquals(CatalogState.ALIVE, this.evita.getCatalogState("target").orElseThrow());
			} finally {
				subscriber.release.countDown();
			}
			awaitDelivery();
			assertNull(subscriber.failure.get(), () -> "CDC subscriber failed: " + subscriber.failure.get());
			final String earlier = scenario.equals("rename") ? "remove" : "inactive";
			final String later = scenario.equals("reactivate") ? "activate" : "rename";
			final String host = scenario.equals("rename") ? "host-removed" : "host-INACTIVE";
			final List<String> order = subscriber.order;
			assertTrue(order.indexOf(earlier) >= 0, "Missing earlier mutation: " + order);
			assertTrue(order.indexOf(earlier) < order.indexOf(later), "Engine order changed: " + order);
			assertTrue(order.indexOf(later) < order.indexOf(host), "Later mutation did not overtake host: " + order);
			assertRouted(router, "/target", true);
			if (graphql) {
				assertRouted(router, "/target/schema", true);
			}
		}
	}

	/** Separately drained lifecycle steps still remove inactive/absent names and publish their successors. */
	@ParameterizedTest(name = "graphql={0}, remove={1}")
	@CsvSource({"false, false", "true, false", "false, true", "true, true"})
	@DisplayName("Unregister an inactive or removed catalog in normal order and register its successor")
	void shouldConvergeWhenLifecycleEventsArriveInNormalOrder(boolean graphql, boolean remove) throws Exception {
		final HttpService router = createManager(graphql);
		retireTarget(remove);
		awaitDelivery();
		assertRouted(router, "/target", false);
		if (graphql) {
			assertRouted(router, "/target/schema", false);
		}
		installTarget(remove ? "rename" : "reactivate");
		awaitDelivery();
		assertRouted(router, "/target", true);
		if (graphql) {
			assertRouted(router, "/target/schema", true);
		}
	}

	/** Creates the production manager, including its real system-CDC observer and catalog builders. */
	@Nonnull
	private HttpService createManager(boolean graphql) throws Exception {
		final HttpService router;
		if (graphql) {
			final GraphQLManager manager = new GraphQLManager(
				this.evita, new HeaderOptions(), new GraphQLOptions("localhost:5555")
			);
			assertTrue(manager.registerCatalog("source"));
			assertTrue(manager.registerCatalog("target"));
			router = manager.getGraphQLRouter();
		} else {
			final RestManager manager = new RestManager(
				this.evita, new HeaderOptions(), new RestOptions("localhost:5555")
			);
			assertTrue(manager.registerCatalog("source"));
			assertTrue(manager.registerCatalog("target"));
			router = manager.getRestRouter();
		}
		assertRouted(router, "/target", true);
		assertRouted(router, "/absent", false);
		return router;
	}

	/** Both public engine operations finish emitting their host notification before returning. */
	private void retireTarget(boolean remove) {
		if (remove) {
			this.evita.deleteCatalogIfExists("target");
			assertTrue(this.evita.getCatalogState("target").isEmpty());
		} else {
			this.evita.deactivateCatalog("target");
		}
	}

	/** Publishes a replacement, a renamed successor, or a reactivated instance through real engine operations. */
	private void installTarget(@Nonnull String scenario) {
		switch (scenario) {
			case "replace" -> this.evita.replaceCatalog("source", "target");
			case "rename" -> this.evita.renameCatalog("source", "target");
			case "reactivate" -> this.evita.activateCatalog("target");
			default -> throw new IllegalArgumentException("Unexpected scenario: " + scenario);
		}
	}

	/** A FIFO task behind all submitted CDC tasks supplies a bounded completion and visibility fence. */
	private void awaitDelivery() throws Exception {
		this.evita.getRequestExecutor().submit(() -> { }).get(30, TimeUnit.SECONDS);
	}

	/** Subscribes through the public engine API to both streams without replaying fixture creation. */
	@Nonnull
	private ChangeCapturePublisher<ChangeSystemCapture> subscribe(@Nonnull GatedCatalogCaptureSubscriber subscriber) {
		final ChangeCapturePublisher<ChangeSystemCapture> publisher = this.evita.registerSystemChangeCapture(
			ChangeSystemCaptureRequest.builder().sinceVersion(this.evita.getEngineState().version() + 1)
				.content(ChangeCaptureContent.BODY).criteria(
					new ChangeSystemCaptureCriteria(SystemCaptureArea.ENGINE),
					new ChangeSystemCaptureCriteria(SystemCaptureArea.HOST)
				).build()
		);
		publisher.subscribe(subscriber);
		return publisher;
	}

	/** TRACE is unsupported by bound APIs (405); an unbound catalog prefix answers 404. */
	private static void assertRouted(@Nonnull HttpService router, @Nonnull String path, boolean expected)
		throws Exception {
		final HttpRequest request = HttpRequest.of(HttpMethod.TRACE, path);
		assertEquals(
			expected ? HttpStatus.METHOD_NOT_ALLOWED : HttpStatus.NOT_FOUND,
			router.serve(ServiceRequestContext.of(request), request).aggregate().get(10, TimeUnit.SECONDS).status(),
			"Unexpected routing for " + path
		);
	}

	/**
	 * Holds a real subscription callback on a latch, then records the target's engine and host delivery order.
	 * All order writes happen on the sole request worker and are read only after its completion fence.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class GatedCatalogCaptureSubscriber implements Flow.Subscriber<ChangeSystemCapture> {
		/** Signals that the only CDC worker is inside an engine callback. */
		private final CountDownLatch entered = new CountDownLatch(1);
		/** Released once the later engine state and all its delivery tasks exist. */
		private final CountDownLatch release = new CountDownLatch(1);
		/** Delivery order observed by the real subscription. */
		private final List<String> order = new ArrayList<>();
		/** Subscriber failures must be observed on the test thread. */
		private final AtomicReference<Throwable> failure = new AtomicReference<>();
		/** Real subscription, replenished like the production refreshing observers. */
		private Flow.Subscription subscription;

		/** Starts demand using the same one-at-a-time pattern as production observers. */
		@Override
		public void onSubscribe(@Nonnull Flow.Subscription subscription) {
			this.subscription = subscription;
			subscription.request(1);
		}

		/** Blocks only the unrelated gate mutation; records target events before requesting the next item. */
		@Override
		public void onNext(@Nonnull ChangeSystemCapture capture) {
			try {
				switch (capture.body()) {
					case CreateCatalogSchemaMutation create when create.getCatalogName().equals("gate") -> {
						this.entered.countDown();
						if (!this.release.await(30, TimeUnit.SECONDS)) {
							throw new IllegalStateException("Test did not release the CDC gate within 30 seconds");
						}
					}
					case SetCatalogStateMutation state when state.getCatalogName().equals("target") ->
						this.order.add(state.isActive() ? "activate" : "inactive");
					case RemoveCatalogSchemaMutation remove when remove.getCatalogName().equals("target") ->
						this.order.add("remove");
					case ModifyCatalogSchemaNameMutation rename when rename.getNewCatalogName().equals("target") ->
						this.order.add("rename");
					case HostSystemEvent.CatalogInstalledIntoLiveView installed
						when installed.catalogName().equals("target") ->
						this.order.add("host-" + installed.observedState());
					case HostSystemEvent.CatalogRemovedFromLiveView removed
						when removed.catalogName().equals("target") -> this.order.add("host-removed");
					case null, default -> { }
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while holding the CDC gate", exception);
			} finally {
				this.subscription.request(1);
			}
		}

		/** Retains errors instead of allowing an asynchronous callback failure to disappear. */
		@Override
		public void onError(@Nonnull Throwable throwable) {
			this.failure.set(throwable);
		}

		/** No resources belong to this subscriber. */
		@Override
		public void onComplete() {
		}
	}
}
