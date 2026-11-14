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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.*;
import io.evitadb.core.Evita;
import graphql.schema.idl.SchemaParser;
import io.evitadb.externalApi.configuration.ApiOptions;
import io.evitadb.externalApi.configuration.CertificateOptions;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;
import io.evitadb.externalApi.rest.RestProvider;
import io.evitadb.externalApi.rest.configuration.RestOptions;
import io.evitadb.server.EvitaServer;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static io.evitadb.test.TestTags.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Black-box REST/GraphQL isolation during real engine lifecycle operations, using the server fixture's
 * `EvitaServer(Evita, ApiOptions)`, isolated TestPaths and shared PortManager. Pooled Armeria clients use
 * keep-alive HTTP/1.1 and prior-knowledge HTTP/2 on plain HTTP ports; no TLS or gRPC fixture is needed.
 * No routing implementation, observer internals or test-only production hook is referenced.
 *
 * Defaults: `api.churn.mode=correctness`, `api.churn.seconds=60`, `api.churn.clients=4`.
 * Opt-in `api.churn.mode=ramp` uses `api.churn.ramp=1,2,4,8,16,32,64` and
 * `api.churn.stepSeconds=10`. Times exclude fixture startup and bounded 30-second convergence waits.
 * Each step reports stable-request throughput, approximate latency percentiles (2% logarithmic bins),
 * exact maximum and a complete outcome histogram to stdout and `target/external-api-churn-<mode>.txt`.
 * Only correctness and evidence of overlap are asserted, never performance. Tests are enabled weekly;
 * manual execution and calibration belong on an otherwise idle machine.
 *
 * Lifecycle requests accept only checked data, the exact routing 404 body, or an error with an error code
 * in HTTP 400 JSON / HTTP 200 GraphQL errors. `lifecycleMessage` names the known ones; the rest is reported
 * as `other` in the outcome histogram, because the test guards routing, not engine lifecycle. A REST HTTP 500
 * "Session has been already closed" on a catalog being replaced is accepted until issue #1959 is fixed.
 * Every transport failure, malformed body, other error and every stable-catalog error fails.
 * A pair of refresh-only catalogs is never renamed/deleted. Once warmed, REST must remain 200 throughout.
 * A creation-only observation window closes under a fixture lock before rename/delete, so the GraphQL
 * data-then-schema assertion cannot accidentally test deletion between its two HTTP requests.
 * A separate replacement window opens after the target APIs are known to be registered and closes under a
 * fixture lock before deletion starts. Inside it, lifecycle responses remain permitted but ROUTING_MISS fails
 * for REST and both GraphQL endpoints, including during overwrite. Closing drains complete requests, so an
 * allowed deletion cannot race this assertion.
 *
 * Calibration mutation (the expected timing is an estimate): in `RestRouter#refreshCatalogApi`, replace the
 * method body with `unregisterCatalogApi(catalogName); registerCatalogApi(catalogName, api);`. The refresh-only REST
 * assertion should detect a 404 in the default 60-second run, expected within the first 10 seconds of
 * schema churn. Run with `mvn -P longRunning
 * -pl evita_test/evita_long_running_tests -am test -Dtest=LongRunningExternalApiChurnTest
 * -Dsurefire.failIfNoSpecifiedTests=false -DreleaseFixtures.skip=true -DroaringBitmap.skipTests=true`.
 * Restore and touch production source, then repeat. A surviving counterfactual invalidates the calibration.
 * Replacement calibration mutation: restore unregister-source, unregister-target, register-target in
 * `SystemRestRefreshingObserver` and `SystemGraphQLRefreshingObserver` for ModifyCatalogSchemaNameMutation.
 * With that order, one default 60-second run using the command above reported 589 ROUTING_MISS failures on
 * replace targets over REST and both GraphQL endpoints, from 1,918 protected probes. The unmutated tree reported
 * none from 976 protected probes. Restore and touch both observers before repeating. Making assembly faster
 * can reduce sensitivity and requires recalibration.
 * The reachable fast-loop neighbours are `CatalogRenameRoutingTest`, `RestRouterTest` and `GraphQLRouterTest`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(SERVER)
@Tag(EXTERNAL_API)
@Tag(REST)
@Tag(GRAPHQL)
@Tag(MANAGEMENT)
@Tag(SCHEMA)
@DisplayName("Long-running external API catalog churn")
class LongRunningExternalApiChurnTest implements EvitaTestSupport {
	private static final String[] STABLE = {"stableAlpha", "stableBeta", "stableGamma"};
	private static final String[] REFRESH = {"refreshAlpha", "refreshBeta"};
	private static final String ROUTING_MISS = "404 Not Found: The requested resource could not be found.";
	private static final String SESSION_CLOSED =
		"Session has been already closed. No one should access the session!";
	private static final ObjectMapper JSON = new ObjectMapper()
		.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
	private static final String GQL_QUERY =
		"{\"query\":\"{ getProduct(primaryKey: 1) { primaryKey attributes { code } } }\"}";
	private static final String REST_QUERY =
		"{\"filterBy\":{\"entityPrimaryKeyInSet\":[1]}," +
			"\"require\":{\"entityFetch\":{\"attributeContent\":[\"code\"]}}}";

	/** Runs correctness by default; saturation is selected explicitly through the mode property. */
	@Test
	@DisplayName("Other catalogs always return their expected data while catalogs change")
	void shouldKeepStableCatalogsCorrectDuringLifecycleChurn() throws Exception {
		final String mode = System.getProperty("api.churn.mode", "correctness");
		assertTrue(mode.equals("correctness") || mode.equals("ramp"), "Unknown api.churn.mode: " + mode);
		final int seconds = Integer.getInteger(mode.equals("ramp") ?
			"api.churn.stepSeconds" : "api.churn.seconds", mode.equals("ramp") ? 10 : 60);
		final int[] steps = mode.equals("ramp") ? Arrays.stream(System.getProperty(
			"api.churn.ramp", "1,2,4,8,16,32,64").split(",")).mapToInt(Integer::parseInt).toArray() :
			new int[]{Integer.getInteger("api.churn.clients", 4)};
		assertTrue(seconds > 0 && steps.length > 0 && Arrays.stream(steps).allMatch(it -> it > 0),
			"Positive durations and client counts required");
		final TestPaths paths = createTestPaths("LongRunningExternalApiChurnTest");
		final String allocation = paths.storage().getFileName().toString();
		final int[] ports = getPortManager().allocatePorts(allocation, 2);
		final StringBuilder output = new StringBuilder(4096);
		output.append("mode clients seconds requests req/s p50-ms p95-ms p99-ms max-ms overlap outcomes\n");
		try (Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build());
		     ClientFactory factory = ClientFactory.builder().build()) {
			for (final String name : STABLE) {
				createCatalog(evita, name);
			}
			for (final String name : REFRESH) {
				createCatalog(evita, name);
			}
			final ApiOptions options = ApiOptions.builder()
				.certificate(CertificateOptions.builder().folderPath(paths.work().resolve("certificates").toString())
					.build())
				.enable(RestProvider.CODE, new RestOptions(true, "localhost:" + ports[0], null,
					"FORCE_NO_TLS", true, null, null))
				.enable(GraphQLProvider.CODE, new GraphQLOptions(true, "localhost:" + ports[1], null,
					"FORCE_NO_TLS", true, null, true, null)).build();
			final EvitaServer server = new EvitaServer(evita, options);
			try {
				server.run();
				final ChurnRun run = new ChurnRun(evita, factory, ports);
				for (final String name : evita.getCatalogNames()) {
					run.awaitCatalog(name, "code", true);
				}
				run.creationChecks.set(0);
				run.creationWindowChecks.set(0);
				run.totalOutcomes.clear();
				run.execute(mode, steps, seconds, output);
			} finally {
				server.stop().get(30, TimeUnit.SECONDS);
			}
		} finally {
			getPortManager().releasePorts(allocation);
			cleanupTestPaths(paths);
			final Path report = Path.of("target", "external-api-churn-" + mode + ".txt");
			Files.createDirectories(report.getParent());
			Files.writeString(report, output);
			System.out.print(output);
		}
	}

	/** Creates identical known data for every generation, including a collection and explicit primary keys. */
	private static void createCatalog(@Nonnull Evita evita, @Nonnull String name) {
		evita.defineCatalog(name);
		evita.updateCatalog(name, session -> {
			session.defineEntitySchema("Product").withoutGeneratedPrimaryKey()
				.withAttribute("code", String.class, attribute -> attribute.filterable()).updateVia(session);
			for (int i = 1; i <= 3; i++) {
				session.upsertEntity(session.createNewEntity("Product", i).setAttribute("code", knownCode(name, i)));
			}
			session.goLiveAndClose();
		});
	}

	/** Adds/removes an unused nullable attribute without changing the queried data or growing the schema forever. */
	private static void changeSchema(@Nonnull Evita evita, @Nonnull String name, boolean add) {
		evita.updateCatalog(name, session -> {
			if (add) {
				session.defineEntitySchema("Product").withAttribute("refreshExtra", String.class,
					attribute -> attribute.nullable()).updateVia(session);
			} else {
				session.defineEntitySchema("Product").withoutAttribute("refreshExtra").updateVia(session);
			}
		});
	}

	/**
	 * Owns only fixture state and public engine/HTTP interfaces for one server run.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class ChurnRun {
		private final Evita evita;
		private final WebClient[][] clients = new WebClient[2][2];
		private final AtomicBoolean stop = new AtomicBoolean();
		private final AtomicReference<String> active = new AtomicReference<>("absentChurn");
		private final AtomicReference<String> creating = new AtomicReference<>();
		private final ReentrantReadWriteLock creationWindow = new ReentrantReadWriteLock();
		/** Protects a known-registered replace target until all protected requests drain before deletion. */
		private final ReentrantReadWriteLock replacementWindow = new ReentrantReadWriteLock();
		/** Target whose routes are required until the fixture begins deletion. */
		private final AtomicReference<String> replacing = new AtomicReference<>();
		/** Completed protected requests, required to be nonzero. */
		private final AtomicLong replacementChecks = new AtomicLong();
		private final AtomicReferenceArray<String> operations = new AtomicReferenceArray<>(2);
		private final AtomicLongArray operationCounts = new AtomicLongArray(6);
		private final AtomicLong operationSequence = new AtomicLong();
		private final AtomicInteger inFlight = new AtomicInteger();
		private final AtomicLong failureCount = new AtomicLong();
		private final Queue<String> failures = new ConcurrentLinkedQueue<>();
		private final AtomicReference<ChurnMetrics> metrics = new AtomicReference<>(new ChurnMetrics());
		private final AtomicLong creationChecks = new AtomicLong();
		private final AtomicLong refreshChecks = new AtomicLong();
		private final AtomicLong refreshPublications = new AtomicLong();
		private final AtomicLong creationWindowChecks = new AtomicLong();
		private final AtomicReferenceArray<RefreshPublication> refreshTargets =
			new AtomicReferenceArray<>(REFRESH.length);
		private final ConcurrentMap<String, LongAdder> totalOutcomes = new ConcurrentSkipListMap<>();

		/** Reuses one connection pool across all clients and forces both wire protocols without an upgrade race. */
		private ChurnRun(@Nonnull Evita evita, @Nonnull ClientFactory factory, @Nonnull int[] ports) {
			this.evita = evita;
			for (int protocol = 0; protocol < 2; protocol++) {
				for (int api = 0; api < 2; api++) {
					this.clients[protocol][api] = WebClient.builder(
						(protocol == 0 ? "h1c" : "h2c") + "://localhost:" + ports[api])
						.factory(factory).responseTimeoutMillis(30_000).build();
				}
			}
		}

		/** Keeps writers and churn clients running across ramp steps, then drains before final convergence. */
		private void execute(
			@Nonnull String mode, @Nonnull int[] steps, int seconds, @Nonnull StringBuilder output
		) throws Exception {
			try {
				executeAndConverge(mode, steps, seconds, output);
			} finally {
				for (int i = 0; i < 6; i++) {
					output.append("operation[").append(i).append("]=").append(this.operationCounts.get(i)).append('\n');
				}
				output.append("outcomes-total=").append(this.totalOutcomes).append('\n');
				output.append("refreshPublications=").append(this.refreshPublications.get())
					.append(" creationWindowChecks=").append(this.creationWindowChecks.get()).append('\n');
				output.append("replacementChecks=").append(this.replacementChecks.get()).append('\n');
				output.append("refreshChecks=").append(this.refreshChecks.get()).append(" creationChecks=")
					.append(this.creationChecks.get()).append(" failures=").append(this.failureCount.get())
					.append('\n');
				for (final String failure : this.failures) {
					output.append(failure).append('\n');
				}
			}
		}

		/** Drains all workload before observing final API convergence and asserting the collected failures. */
		private void executeAndConverge(
			@Nonnull String mode, @Nonnull int[] steps, int seconds, @Nonnull StringBuilder output
		) throws Exception {
			final ExecutorService background = daemonPool(6);
			final CountDownLatch ready = new CountDownLatch(6);
			final CountDownLatch start = new CountDownLatch(1);
			final CountDownLatch done = new CountDownLatch(6);
			try {
				submit(background, ready, start, done, () -> { lifecycleLoop(); return null; });
				submit(background, ready, start, done, () -> { refreshLoop(); return null; });
				for (int protocol = 0; protocol < 2; protocol++) {
					final int wire = protocol;
					submit(background, ready, start, done, () -> { churnClient(wire); return null; });
					submit(background, ready, start, done, () -> { refreshClient(wire); return null; });
				}
				assertTrue(ready.await(30, TimeUnit.SECONDS), "Background workers did not start");
				start.countDown();
				for (final int count : steps) {
					runStep(mode, count, seconds, output);
				}
			} finally {
				this.stop.set(true);
				start.countDown();
				try {
					assertTrue(done.await(120, TimeUnit.SECONDS), "Background workers did not drain");
				} finally {
					background.shutdownNow();
					assertTrue(background.awaitTermination(30, TimeUnit.SECONDS), "Background pool leaked");
				}
			}
			// There is no observer completion latch in the public fixture. A unique schema field is a causal
			// HTTP watermark: both generated APIs must expose the final mutation, not merely an old 200 route.
			for (final String name : this.evita.getCatalogNames()) {
				this.evita.updateCatalog(name, session -> {
					session.defineEntitySchema("Product")
						.withAttribute("convergenceMarker", String.class, attribute -> attribute.nullable())
						.updateVia(session);
				});
				awaitCatalog(name, "convergenceMarker", false);
				for (int protocol = 0; protocol < 2; protocol++) {
					for (int kind = 0; kind < 5; kind++) {
						checkedRequest(name, kind, protocol, false, this.metrics.get());
					}
				}
			}
			for (int i = 0; i < 6; i++) {
				assertTrue(this.operationCounts.get(i) > 0, "Missing lifecycle operation " + i);
			}

			assertTrue(this.refreshChecks.get() >= 10 && this.creationChecks.get() > 0 &&
				this.creationWindowChecks.get() > 0 && this.refreshPublications.get() > 0 &&
				this.replacementChecks.get() > 0, "Missing window coverage");
			assertEquals(0, this.failureCount.get(), () -> "API churn failures: " + this.failures);
		}

		/** Runs a bounded client step; each client visits every stable catalog and each of the five endpoints. */
		private void runStep(@Nonnull String mode, int count, int seconds, @Nonnull StringBuilder output)
			throws Exception {
			final ChurnMetrics step = new ChurnMetrics();
			this.metrics.set(step);
			final ExecutorService pool = daemonPool(count);
			final CountDownLatch ready = new CountDownLatch(count);
			final CountDownLatch start = new CountDownLatch(1);
			final CountDownLatch done = new CountDownLatch(count);
			final long[] deadline = new long[1];
			long began = 0;
			try {
				for (int i = 0; i < count; i++) {
					final int client = i;
					submit(pool, ready, start, done, () -> {
						int request = client;
						while (System.nanoTime() < deadline[0]) {
							checkedRequest(STABLE[(request / 5) % STABLE.length], request % 5,
								request % 2, false, step);
							request++;
						}
						return null;
					});
				}
				assertTrue(ready.await(30, TimeUnit.SECONDS), "Stable clients did not start");
				began = System.nanoTime();
				deadline[0] = began + TimeUnit.SECONDS.toNanos(seconds);
				start.countDown();
				assertTrue(done.await(seconds + 30L, TimeUnit.SECONDS), "Stable clients did not drain");
			} finally {
				start.countDown();
				pool.shutdownNow();
				assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "Stable client pool leaked");
				final String row = step.row(mode, count, (System.nanoTime() - began) / 1e9);
				output.append(row).append('\n');
				// The complete table is printed by the fixture finally block, including failed runs.
			}
			assertTrue(step.completed.get() >= 10 && step.overlap.get() > 0,
				"No evidence that stable requests overlapped engine churn");
			for (final String name : STABLE) {
				for (int kind = 0; kind < 5; kind++) {
					for (int protocol = 0; protocol < 2; protocol++) {
						assertTrue(step.coverage.containsKey(name + "/" + protocol + "/" + kind),
							"Missing stable endpoint coverage: " + name + "/" + protocol + "/" + kind);
					}
				}
			}
		}

		/** Cycles a small pool of generation-specific names, preventing an old request reaching a new empty schema. */
		private void lifecycleLoop() throws Exception {
			int generation = 0;
			String previous = null;
			while (!this.stop.get() || this.operationCounts.get(4) == 0) {
				if (previous != null) {
					closeReplacementWindow();
					final String obsolete = previous;
					operation(0, 4, "delete " + obsolete, () -> this.evita.deleteCatalogIfExists(obsolete));
					if (this.stop.get()) {
						break;
					}
				}
				final String source = "churnSource" + generation;
				final String renamed = "churnRenamed" + generation;
				final String target = "churnTarget" + generation++;
				this.creating.set(source);
				operation(0, 0, "create " + source, () -> createCatalog(this.evita, source));
				awaitCatalog(source, "code", true);
				closeCreationWindow();
				this.active.set(source);
				operation(0, 1, "schema " + source, () -> changeSchema(this.evita, source, true));
				operation(0, 2, "rename " + source + " -> " + renamed,
					() -> this.evita.renameCatalog(source, renamed));
				awaitCatalog(renamed, "code", false);
				this.active.set(renamed);
				this.creating.set(target);
				operation(0, 0, "create " + target, () -> createCatalog(this.evita, target));
				awaitCatalog(target, "code", true);
				closeCreationWindow();
				this.active.set(target);
				this.replacing.set(target);
				operation(0, 3, "replace " + target + " with " + renamed,
					() -> this.evita.replaceCatalog(renamed, target));
				awaitCatalog(target, "code", false);
				// Retain until the next cycle; its first delete is required even after stop was requested.
				previous = target;
			}
		}

		/** Drains creation-only probes before the writer is permitted to rename or delete their catalog. */
		private void closeCreationWindow() {
			this.creationWindow.writeLock().lock();
			try {
				this.creating.set(null);
			} finally {
				this.creationWindow.writeLock().unlock();
			}
		}

		/** Drains protected requests before the lifecycle writer starts deleting the replace target. */
		private void closeReplacementWindow() {
			this.replacementWindow.writeLock().lock();
			try {
				this.replacing.set(null);
			} finally {
				this.replacementWindow.writeLock().unlock();
			}
		}

		/** Refreshes two warmed catalogs only; no lifecycle operation ever removes their routes or entities. */
		private void refreshLoop() throws Exception {
			int generation = 1;
			while (!this.stop.get()) {
				for (int catalog = 0; catalog < REFRESH.length && !this.stop.get(); catalog++) {
					final String name = REFRESH[catalog];
					final boolean add = (generation & 1) != 0;
					final RefreshPublication publication = new RefreshPublication(generation, new CountDownLatch(2));
					this.refreshTargets.set(catalog, publication);
					operation(1, 5, "refresh " + name + " generation=" + generation,
						() -> changeSchema(this.evita, name, add));
					// Wait for actual REST specification observations, not the asynchronous engine notification.
					if (!publication.observed.await(30, TimeUnit.SECONDS) && !this.stop.get()) {
						throw new AssertionError("REST refresh not observed during reader window: " + name);
					}
				}
				generation++;
			}
		}

		/** Exercises churned data and both GraphQL endpoints, retaining strict lifecycle-only allowances. */
		private void churnClient(int protocol) throws Exception {
			int request = 0;
			while (!this.stop.get()) {
				checkedRequest(this.active.get(), request++ % 5, protocol, true, this.metrics.get());
				this.replacementWindow.readLock().lock();
				try {
					final String target = this.replacing.get();
					if (target != null) {
						checkedRequest(target, request % 5, protocol, true, this.metrics.get(), true);
						this.replacementChecks.incrementAndGet();
					}
				} finally {
					this.replacementWindow.readLock().unlock();
				}
				this.creationWindow.readLock().lock();
				try {
					final String name = this.creating.get();
					if (name != null) {
						checkCreation(name, protocol);
						this.creationWindowChecks.incrementAndGet();
					}
				} finally {
					this.creationWindow.readLock().unlock();
				}
			}
		}

		/** Asserts the fixed REST window continuously over both protocols after successful warmup. */
		private void refreshClient(int protocol) throws Exception {
			final int[] observed = new int[REFRESH.length];
			int request = 0;
			while (!this.stop.get()) {
				final int catalog = request % REFRESH.length;
				checkedRequest(REFRESH[catalog], 0, protocol, false, this.metrics.get());
				final RefreshPublication publication = this.refreshTargets.get(catalog);
				if (publication != null && publication.generation > observed[catalog]) {
					final AggregatedHttpResponse specification = raw(protocol, 0, HttpMethod.GET,
						"/rest/" + REFRESH[catalog], null);
					assertEquals(200, specification.status().code(), specification.contentUtf8());
					final boolean extra = specification.contentUtf8().contains("refreshExtra");
					if (extra == ((publication.generation & 1) != 0)) {
						observed[catalog] = publication.generation;
						this.refreshPublications.incrementAndGet();
						publication.observed.countDown();
					}
				}
				this.refreshChecks.incrementAndGet();
				request++;
			}
		}

		/** Tracks the precise engine operation and monotonic sequence around each direct engine mutation. */
		private void operation(int writer, int kind, @Nonnull String description, @Nonnull Runnable action) {
			this.operations.set(writer, description);
			this.inFlight.incrementAndGet();
			this.operationSequence.incrementAndGet();
			try {
				action.run();
				this.operationCounts.incrementAndGet(kind);
			} catch (RuntimeException | Error ex) {
				failure("engine " + description, "direct engine call", ex);
				throw ex;
			} finally {
				this.operationSequence.incrementAndGet();
				this.inFlight.decrementAndGet();
				this.operations.set(writer, null);
			}
		}

		/** Submits guarded daemon work; every worker exception, including AssertionError, reaches final assertions. */
		private void submit(
			@Nonnull ExecutorService pool, @Nonnull CountDownLatch ready, @Nonnull CountDownLatch start,
			@Nonnull CountDownLatch done, @Nonnull Callable<?> action
		) {
			pool.execute(() -> {
				ready.countDown();
				try {
					if (!start.await(30, TimeUnit.SECONDS)) {
						throw new AssertionError("Worker start gate timed out");
					}
					action.call();
				} catch (Throwable ex) {
					failure("worker", "no HTTP response", ex);
				} finally {
					done.countDown();
				}
			});
		}

		/** Waits for generated APIs via bounded HTTP retries because no public observer completion signal exists. */
		private void awaitCatalog(@Nonnull String name, @Nonnull String watermark, boolean creation) throws Exception {
			final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
			String last = "no response";
			do {
				if (creation) {
					checkCreation(name, 0, deadline);
				}
				final AggregatedHttpResponse rest = send(name, 0, 0, deadline);
				final AggregatedHttpResponse graph = send(name, 3, 0, deadline);
				final AggregatedHttpResponse schema = send(name, 4, 0, deadline);
				final AggregatedHttpResponse specification = raw(0, 0, HttpMethod.GET, "/rest/" + name, null, deadline);
				final AggregatedHttpResponse dataSchema = raw(0, 1, HttpMethod.GET, "/gql/" + name, null, deadline);
				last = rest.status() + " / " + graph.status() + " / " + schema.status() +
					" watermark=" + watermark;
				if (rest.status().code() == 200 && graph.status().code() == 200 && schema.status().code() == 200 &&
					specification.status().code() == 200 && dataSchema.status().code() == 200 &&
					specification.contentUtf8().contains(watermark) && dataSchema.contentUtf8().contains(watermark) &&
					!JSON.readTree(graph.contentUtf8()).has("errors")) {
					validate(rest, name, 0);
					validate(graph, name, 3);
					validate(schema, name, 4);
					remaining(deadline);
					return;
				}
				// Retrying public HTTP is the observable condition, not sleep polling of private observer state.
				// The schema watermark prevents accepting an old generated API after the final mutation.
			} while (System.nanoTime() < deadline);
			throw new AssertionError("Observers did not converge for " + name + ": " + last);
		}

		/** Once the new DATA route is visible, SCHEMA must already be visible; deletion is excluded by the caller. */
		private void checkCreation(@Nonnull String name, int protocol) throws Exception {
			checkCreation(name, protocol, System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
		}

		/** Applies the caller's overall deadline to both creation-window requests. */
		private void checkCreation(@Nonnull String name, int protocol, long deadline) throws Exception {
			String evidence = "transport error";
			try {
				final AggregatedHttpResponse data = raw(protocol, 1, HttpMethod.GET, "/gql/" + name, null, deadline);
				evidence = "DATA " + data.status() + " " + excerpt(data.contentUtf8());
				if (data.status().code() == 404) {
					if (!ROUTING_MISS.equals(data.contentUtf8())) {
						throw new AssertionError("Malformed creation 404: " + data.contentUtf8());
					}
					this.totalOutcomes.computeIfAbsent("creation/" + protocol + "/404",
						ignored -> new LongAdder()).increment();
					return;
				}
				final AggregatedHttpResponse schema = send(name, 4, protocol, deadline);
				evidence += " SCHEMA " + schema.status() + " " + excerpt(schema.contentUtf8());
				validate(data, name, 4);
				validate(schema, name, 4);
				this.creationChecks.incrementAndGet();
				this.totalOutcomes.computeIfAbsent("creation/" + protocol + "/200",
					ignored -> new LongAdder()).increment();
			} catch (Exception | AssertionError ex) {
				failure("creation GET /gql/" + name + " then /schema protocol=" + protocol, evidence, ex);
				throw ex;
			}
		}

		/** Checks every HTTP response and preserves timestamp/request/body/operation evidence on any failure. */
		private void checkedRequest(
			@Nonnull String name, int kind, int protocol, boolean lifecycle, @Nonnull ChurnMetrics step
		) {
			checkedRequest(name, kind, protocol, lifecycle, step, false);
		}

		/** Rejects a routing miss for a registered replace target while deletion is excluded by the fixture. */
		private void checkedRequest(
			@Nonnull String name, int kind, int protocol, boolean lifecycle, @Nonnull ChurnMetrics step,
			boolean requireRoute
		) {
			final long begin = System.nanoTime();
			final long sequence = this.operationSequence.get();
			final boolean activeOperation = this.inFlight.get() > 0;
			final String target = switch (kind) {
				case 0 -> "GET /rest/" + name + "/Product/get?primaryKey=1&bodyFetch=true&attributeContent=code";
				case 1 -> "POST /rest/" + name + "/Product/list " + REST_QUERY;
				case 2 -> "POST /rest/" + name + "/Product/query " + REST_QUERY;
				case 3 -> "POST /gql/" + name + " " + GQL_QUERY;
				case 4 -> "GET /gql/" + name + "/schema";
				default -> throw new IllegalArgumentException("Unknown request kind " + kind);
			};
			final String request = (protocol == 0 ? "h1 " : "h2 ") + target;
			final String operation = this.operations.get(0) + "; " + this.operations.get(1);
			String detail = "transport error";
			String outcome = "transport";
			try {
				final AggregatedHttpResponse response = send(name, kind, protocol);
				detail = "status=" + response.status().code() + " body=" + excerpt(response.contentUtf8());
				outcome = "unexpected-" + response.status().code();
				outcome = classify(response, name, kind, lifecycle);
				if (requireRoute && response.status().code() == 404 && ROUTING_MISS.equals(response.contentUtf8())) {
					throw new AssertionError("ROUTING_MISS for registered replace target before deletion: " + name);
				}
			} catch (Throwable ex) {
				outcome = "failure/" + outcome + "/" + ex.getClass().getSimpleName();
				failure(request + " operation-at-start=" + operation, detail, ex);
			} finally {
				final String role = lifecycle ? "churn/" :
					Arrays.asList(STABLE).contains(name) ? "stable/" : "refresh/";
				final String key = role + protocol + "/" + kind + "/" + outcome;
				step.outcome(key);
				this.totalOutcomes.computeIfAbsent(key, ignored -> new LongAdder()).increment();
				if (!lifecycle && Arrays.asList(STABLE).contains(name)) {
					step.record(System.nanoTime() - begin);
					step.coverage.computeIfAbsent(name + "/" + protocol + "/" + kind,
						ignored -> new LongAdder()).increment();
					if (activeOperation && (this.inFlight.get() > 0 || this.operationSequence.get() != sequence)) {
						step.overlap.incrementAndGet();
					}
				}
			}
		}

		/** Captures all failures up to a bounded diagnostic sample while preserving the exact failure count. */
		private void failure(@Nonnull String request, @Nonnull String detail, @Nonnull Throwable cause) {
			final long number = this.failureCount.incrementAndGet();
			if (number <= 100) {
				this.failures.add(Instant.now() + " request=" + request + " " + detail + " in-flight=" +
					this.operations.get(0) + "; " + this.operations.get(1) + " cause=" + cause);
			}
		}

		/** Builds the same entity get/list/query and GraphQL query/schema requests used by functional fixtures. */
		@Nonnull
		private AggregatedHttpResponse send(@Nonnull String name, int kind, int protocol) throws Exception {
			return send(name, kind, protocol, System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
		}

		/** Uses the convergence attempt's remaining budget for each generated request. */
		@Nonnull
		private AggregatedHttpResponse send(@Nonnull String name, int kind, int protocol, long deadline)
			throws Exception {
			return switch (kind) {
				case 0 -> raw(protocol, 0, HttpMethod.GET,
					"/rest/" + name + "/Product/get?primaryKey=1&bodyFetch=true&attributeContent=code", null, deadline);
				case 1 -> raw(protocol, 0, HttpMethod.POST, "/rest/" + name + "/Product/list", REST_QUERY, deadline);
				case 2 -> raw(protocol, 0, HttpMethod.POST, "/rest/" + name + "/Product/query", REST_QUERY, deadline);
				case 3 -> raw(protocol, 1, HttpMethod.POST, "/gql/" + name, GQL_QUERY, deadline);
				case 4 -> raw(protocol, 1, HttpMethod.GET, "/gql/" + name + "/schema", null, deadline);
				default -> throw new IllegalArgumentException("Unknown request kind " + kind);
			};
		}

		/** Executes with keep-alive connections and bounded positive waits on actual HTTP completion. */
		@Nonnull
		private AggregatedHttpResponse raw(
			int protocol, int api, @Nonnull HttpMethod method, @Nonnull String path, @Nullable String body
		) throws Exception {
			return raw(protocol, api, method, path, body, System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
		}

		/** Checks the shared deadline before sending and bounds aggregation by its remaining duration. */
		@Nonnull
		private AggregatedHttpResponse raw(
			int protocol, int api, @Nonnull HttpMethod method, @Nonnull String path, @Nullable String body,
			long deadline
		) throws Exception {
			remaining(deadline);
			final RequestHeadersBuilder headers = RequestHeaders.builder(method, path)
				.add(HttpHeaderNames.ACCEPT, body == null && api == 1 ? "application/graphql" : "application/json");
			final HttpRequest request = body == null ? HttpRequest.of(headers.build()) :
				HttpRequest.of(headers.contentType(MediaType.JSON).build(), HttpData.ofUtf8(body));
			return this.clients[protocol][api].execute(request).aggregate()
				.get(remaining(deadline), TimeUnit.NANOSECONDS);
		}
	}

	/** Returns the overall attempt's remaining budget, rejecting late successful convergence as well. */
	private static long remaining(long deadline) throws TimeoutException {
		final long remaining = deadline - System.nanoTime();
		if (remaining <= 0) {
			throw new TimeoutException("Overall API convergence deadline exceeded");
		}
		return remaining;
	}

	/**
	 * One alternating schema publication acknowledged independently by the two wire-protocol readers.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private record RefreshPublication(int generation, @Nonnull CountDownLatch observed) {
	}

	/**
	 * Classifies lifecycle results; all success bodies still undergo semantic validation. A catalog under churn may
	 * answer any error carrying an error code; those outside `lifecycleMessage` are counted as `other`.
	 */
	@Nonnull
	private static String classify(
		@Nonnull AggregatedHttpResponse response, @Nonnull String name, int kind, boolean lifecycle
	)
		throws Exception {
		final int status = response.status().code();
		if (lifecycle && status == 404 && ROUTING_MISS.equals(response.contentUtf8())) {
			return "404/routing";
		}
		if (lifecycle && status == 500) {
			// issue #1959: the engine terminates the session of a catalog being replaced under the REST handler,
			// which reports it as an internal error; remove this branch when that issue is fixed
			final JsonNode json = JSON.readTree(response.contentUtf8());
			if (json.isObject() && json.path("errorCode").isTextual() &&
				SESSION_CLOSED.equals(json.path("message").asText())) {
				return "500/SessionClosed";
			}
		}
		if (lifecycle && (status == 400 || (status == 200 && kind == 3))) {
			final JsonNode json = JSON.readTree(response.contentUtf8());
			if (status == 400 && json.isObject() && json.path("errorCode").isTextual()) {
				// a catalog under churn may answer any identified client error; unlisted ones are only reported
				final String allowed = lifecycleMessage(json.path("message").asText());
				return "400/" + (allowed == null ? "other" : allowed);
			} else if (json.path("errors").isArray() && !json.path("errors").isEmpty()) {
				final SortedSet<String> allowed = new TreeSet<>();
				for (final JsonNode error : json.path("errors")) {
					final String message = lifecycleMessage(error.path("message").asText());
					if (!error.path("extensions").path("errorCode").isTextual()) {
						throw new AssertionError("Unidentified GraphQL error: " + error);
					}
					allowed.add(message == null ? "other" : message);
				}
				validatePartialData(json, name);
				return "200/errors/" + allowed;
			}
		}
		validate(response, name, kind);
		return "200/data";
	}

	/** Checks that lifecycle errors contain only null fields or values belonging to the requested fixture entity. */
	private static void validatePartialData(@Nonnull JsonNode response, @Nonnull String name) {
		final JsonNode data = response.get("data");
		if (data == null || (!data.isNull() && !data.isObject())) {
			throw new AssertionError("Malformed GraphQL lifecycle data: " + response);
		}
		final JsonNode entity = data.path("getProduct");
		if (!entity.isMissingNode() && !entity.isNull()) {
			final JsonNode primaryKey = entity.path("primaryKey");
			final JsonNode code = entity.path("attributes").path("code");
			if (!entity.isObject() || (!primaryKey.isNull() && primaryKey.asInt(-1) != 1) ||
				(!code.isNull() && !code.isMissingNode() && !knownCode(name, 1).equals(code.asText()))) {
				throw new AssertionError("Inconsistent partial GraphQL lifecycle data: " + response);
			}
		}
	}

	/** Matches public lifecycle messages exactly; status and error-envelope validation are separate requirements. */
	@Nullable
	private static String lifecycleMessage(@Nonnull String message) {
		if (message.matches("Catalog with name `churn[A-Za-z0-9]+` doesn't exist\\.")) {
			return "CatalogNotFoundException";
		}
		if (message.matches("Evita (catalog|session) has been already terminated! " +
			"No calls are accepted since all resources has been released\\.")) {
			return "InstanceTerminatedException";
		}
		if (message.matches("The catalog `churn[A-Za-z0-9]+` cannot be used because it is " +
			"(BEING_CREATED|BEING_DELETED|BEING_ACTIVATED)\\. You need to wait until the operation is finished\\.")) {
			return "CatalogTransitioningException";
		}
		if (message.equals("Method on session was called while the session was busy handling internal operation. " +
			"Please wait until the operation is finished and try again.")) {
			return "SessionBusyException";
		}
		if (message.matches("Catalog `churn[A-Za-z0-9]+` is in process of transition from warm-up state " +
			"to live state\\. Please wait until the process is finished and try again\\.")) {
			return "CatalogGoingLiveException";
		}
		return null;
	}

	/** Requires 200 plus exact known entity values or a recognizable generated GraphQL schema, with no errors. */
	private static void validate(@Nonnull AggregatedHttpResponse response, @Nonnull String name, int kind)
		throws Exception {
		if (response.status().code() != 200) {
			throw new AssertionError("Expected 200, received " + response.status());
		}
		if (kind == 4) {
			if (!new SchemaParser().parse(response.contentUtf8()).getType("Query").isPresent()) {
				throw new AssertionError("Malformed GraphQL schema: " + excerpt(response.contentUtf8()));
			}
			return;
		}
		final JsonNode json = JSON.readTree(response.contentUtf8());
		if (json == null || json.has("errors")) {
			throw new AssertionError("Malformed/error response: " + response.contentUtf8());
		}
		final JsonNode entity = switch (kind) {
			case 0 -> json;
			case 1 -> json.isArray() && json.size() == 1 ? json.get(0) : null;
			case 2 -> json.path("recordPage").path("data").size() == 1 ?
				json.path("recordPage").path("data").get(0) : null;
			case 3 -> json.path("data").path("getProduct");
			default -> throw new IllegalArgumentException("Unknown entity response kind " + kind);
		};
		final JsonNode attributes = entity == null ? null : entity.path("attributes");
		final JsonNode code = attributes == null ? null : kind == 3 ? attributes.path("code") :
			attributes.path("global").path("code");
		if (entity == null || entity.path("primaryKey").asInt(-1) != 1 ||
			code == null || !code.asText().equals(knownCode(name, 1))) {
			throw new AssertionError("Wrong entity data: " + excerpt(response.contentUtf8()));
		}
	}

	/** Distinguishes stable/refresh catalogs; lifecycle generations share data across rename and replacement. */
	@Nonnull
	private static String knownCode(@Nonnull String name, int primaryKey) {
		return (name.startsWith("churn") ? "known-churn-" : "known-" + name + "-") + primaryKey;
	}

	/** Bounds retained HTTP evidence while keeping the beginning of an error envelope readable. */
	@Nonnull
	private static String excerpt(@Nonnull String body) {
		return body.substring(0, Math.min(body.length(), 1000)).replace('\n', ' ');
	}

	/** Creates daemon workers; every owning method shuts its pool down in finally. */
	@Nonnull
	private static ExecutorService daemonPool(int threads) {
		return Executors.newFixedThreadPool(threads, task -> {
			final Thread thread = new Thread(task, "external-api-churn");
			thread.setDaemon(true);
			return thread;
		});
	}

	/**
	 * Bounded-memory logarithmic latency histogram and complete status/error histogram for a single step.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class ChurnMetrics {
		private final AtomicLong completed = new AtomicLong();
		private final AtomicLong maximum = new AtomicLong();
		private final AtomicLong overlap = new AtomicLong();
		private final AtomicLongArray latency = new AtomicLongArray(2048);
		private final ConcurrentMap<String, LongAdder> outcomes = new ConcurrentSkipListMap<>();
		private final ConcurrentMap<String, LongAdder> coverage = new ConcurrentSkipListMap<>();

		/** Records all stable request latencies, including failures; no throughput or latency threshold is asserted. */
		private void record(long nanos) {
			this.completed.incrementAndGet();
			this.maximum.accumulateAndGet(nanos, Math::max);
			final int bucket = Math.min(2047, (int) Math.ceil(Math.log(Math.max(1, nanos)) / Math.log(1.02)));
			this.latency.incrementAndGet(bucket);
		}

		/** Counts results by protocol, endpoint, catalog role, status and lifecycle class. */
		private void outcome(@Nonnull String outcome) {
			this.outcomes.computeIfAbsent(outcome, ignored -> new LongAdder()).increment();
		}

		/** Reports the upper bin edge, within 2% of the measured percentile, in milliseconds. */
		private double percentile(double quantile) {
			final long rank = Math.max(1, (long) Math.ceil(this.completed.get() * quantile));
			long seen = 0;
			for (int i = 0; i < this.latency.length(); i++) {
				seen += this.latency.get(i);
				if (seen >= rank) {
					return Math.pow(1.02, i) / 1e6;
				}
			}
			return 0;
		}

		/** Formats a compact, locale-independent table row and the complete outcome histogram. */
		@Nonnull
		private String row(@Nonnull String mode, int clients, double seconds) {
			return String.format(Locale.ROOT, "%s %d %.2f %d %.1f %.3f %.3f %.3f %.3f %d %s",
				mode, clients, seconds, this.completed.get(), this.completed.get() / Math.max(0.001, seconds),
				percentile(0.50), percentile(0.95), percentile(0.99), this.maximum.get() / 1e6,
				this.overlap.get(), this.outcomes);
		}
	}
}
