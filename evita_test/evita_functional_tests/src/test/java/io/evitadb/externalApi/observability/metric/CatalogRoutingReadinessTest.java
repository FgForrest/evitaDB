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

package io.evitadb.externalApi.observability.metric;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.evitadb.api.CatalogState;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.api.system.ProbesProvider.CatalogRoutingSettlement;
import io.evitadb.externalApi.http.ExternalApiProvider;
import io.evitadb.externalApi.observability.configuration.ObservabilityOptions;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static io.evitadb.test.TestTags.OBSERVABILITY;
import static io.evitadb.test.TestTags.OBSERVABILITY_API;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives the startup catalog routing scan with a fake clock and API providers whose routing answers the test sets,
 * over a real engine holding two live catalogs and one deactivated catalog. The engine is never mutated after setup,
 * so every test shares it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Startup catalog routing scan")
@Tag(OBSERVABILITY_API)
@Tag(OBSERVABILITY)
@TestInstance(Lifecycle.PER_CLASS)
class CatalogRoutingReadinessTest implements EvitaTestSupport {
	/** Live catalog whose publication the tests usually leave owed. */
	private static final String FIRST = "routingScanFirst";
	/** Second live catalog, used to keep the scan unsettled while the first one changes. */
	private static final String SECOND = "routingScanSecond";
	/** Catalog deactivated during setup. */
	private static final String DEACTIVATED = "routingScanDeactivated";
	/** Publication budget of every scan under test. */
	private static final long BUDGET_MILLIS = 5_000L;
	/** Publication budget in the clock's unit. */
	private static final long BUDGET_NANOS = MILLISECONDS.toNanos(BUDGET_MILLIS);
	/** Clock reading of the first scan in each test. */
	private static final long START = SECONDS.toNanos(60);

	private final AtomicLong time = new AtomicLong();
	private TestPaths paths;
	private Evita evita;
	private RoutingProvider graphQL;
	private RoutingProvider rest;
	private CatalogRoutingReadiness scan;

	/**
	 * Creates the shared engine with two live catalogs and a deactivated one.
	 */
	@BeforeAll
	void setUpEngine() {
		this.paths = createTestPaths(getClass().getSimpleName());
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		for (final String catalogName : List.of(FIRST, SECOND, DEACTIVATED)) {
			this.evita.defineCatalog(catalogName);
			this.evita.updateCatalog(catalogName, session -> {
				session.defineEntitySchema("Product").updateVia(session);
				session.goLiveAndClose();
			});
		}
		this.evita.deactivateCatalog(DEACTIVATED);
	}

	/**
	 * Closes the shared engine and removes its storage.
	 */
	@AfterAll
	void tearDownEngine() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/**
	 * Starts each test with a fresh scan at {@link #START} and providers that have published every catalog.
	 */
	@BeforeEach
	void setUp() {
		this.time.set(START);
		this.scan = new CatalogRoutingReadiness(this.time::get, BUDGET_MILLIS);
		this.graphQL = new RoutingProvider("graphQL", FIRST, SECOND, DEACTIVATED);
		this.rest = new RoutingProvider("rest", FIRST, SECOND, DEACTIVATED);
	}

	/**
	 * Pins the budget boundary: one nanosecond short of it the publication is pending, at it the publication is
	 * overdue.
	 */
	@Test
	@DisplayName("Keeps an owed publication pending until the budget has fully elapsed")
	void shouldSettleOwedPublicationExactlyWhenBudgetElapses() {
		this.graphQL.owe(FIRST);

		assertPending(1, scanAt(START));
		assertPending(1, scanAt(START + BUDGET_NANOS - 1));
		assertTrue(scanAt(START + BUDGET_NANOS).settled(), "A publication owed for the whole budget is overdue");
	}

	/**
	 * Settles the first catalog in the middle of its budget, owes it again just before the original budget would
	 * expire, and checks it is still pending when that original budget has run out.
	 */
	@Test
	@DisplayName("Grants a fresh budget to a publication owed again after it settled")
	void shouldGrantFreshBudgetWhenPublicationIsOwedAgain() {
		this.graphQL.owe(FIRST);
		this.graphQL.owe(SECOND);
		assertPending(2, scanAt(START));

		this.graphQL.publish(FIRST);
		assertPending(1, scanAt(START + BUDGET_NANOS / 2));

		this.graphQL.owe(FIRST);
		assertPending(2, scanAt(START + BUDGET_NANOS - 1));

		// the second catalog is overdue now, while the first one counts from its renewed obligation
		assertPending(1, scanAt(START + BUDGET_NANOS));
	}

	/**
	 * Makes publications owed after the scan has settled and checks that the next scan neither reopens startup
	 * routing nor asks the providers.
	 */
	@Test
	@DisplayName("Stays settled without consulting providers once every catalog has settled")
	void shouldStaySettledOnceLatched() {
		assertTrue(scanAt(START).settled());
		this.graphQL.owe(FIRST);
		this.rest.owe(SECOND);
		this.graphQL.consultations.set(0);
		this.rest.consultations.set(0);

		final CatalogRoutingSettlement later = scanAt(START + 1);

		assertTrue(later.settled(), "A later obligation must not reopen settled startup routing");
		assertEquals(0, later.catalogsPending());
		assertEquals(0, this.graphQL.consultations.get() + this.rest.consultations.get());
	}

	/**
	 * Registers a settlement action before the first scan and counts its runs across a pending and two settled scans.
	 */
	@Test
	@DisplayName("Runs the settlement action once, and only after settlement")
	void shouldRunSettlementActionOnceAfterLatch() {
		final AtomicInteger runs = new AtomicInteger();
		this.rest.owe(FIRST);
		this.scan.whenSettled(runs::incrementAndGet);

		assertPending(1, scanAt(START));
		assertEquals(0, runs.get(), "The action must wait for settlement");

		this.rest.publish(FIRST);
		assertTrue(scanAt(START + 1).settled());
		assertTrue(scanAt(START + 2).settled());
		assertEquals(1, runs.get());
	}

	/**
	 * Lets both APIs miss the first catalog's budget while a later obligation on the second catalog keeps scanning,
	 * and captures the overdue errors the scans log.
	 */
	@Test
	@DisplayName("Logs each overdue API of a catalog once across repeated scans")
	void shouldLogOverdueApiOncePerCatalog() {
		final Logger logger = (Logger) LoggerFactory.getLogger(CatalogRoutingReadiness.class);
		final ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			this.graphQL.owe(FIRST);
			this.rest.owe(FIRST);
			assertPending(1, scanAt(START));

			// the second catalog keeps the scan unsettled while the first one stays overdue
			this.graphQL.owe(SECOND);
			assertPending(1, scanAt(START + BUDGET_NANOS));
			assertPending(1, scanAt(START + BUDGET_NANOS + 1));
			assertPending(1, scanAt(START + BUDGET_NANOS + 2));

			assertEquals(
				List.of("graphQL", "rest"),
				appender.list.stream()
					.filter(event -> event.getLevel() == Level.ERROR)
					.map(ILoggingEvent::getFormattedMessage)
					.filter(message -> message.contains("activating catalog `" + FIRST + "`"))
					.map(message -> message.split("`")[1])
					.sorted()
					.toList()
			);
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	/**
	 * Leaves the deactivated catalog unpublished by both APIs and checks that it does not hold startup routing open.
	 */
	@Test
	@DisplayName("Does not wait for a deactivated catalog that no API has published")
	void shouldNotWaitForInactiveCatalog() {
		assertEquals(CatalogState.INACTIVE, this.evita.getCatalogState(DEACTIVATED).orElseThrow());
		assertTrue(this.evita.getEngineState().catalogs().containsKey(DEACTIVATED));
		this.graphQL.owe(DEACTIVATED);
		this.rest.owe(DEACTIVATED);

		final CatalogRoutingSettlement outcome = scanAt(START);

		assertTrue(outcome.settled(), "An inactive catalog owes no publication");
		assertEquals(0, outcome.catalogsPending());
	}

	/**
	 * Moves the clock and runs one scan over the shared engine and both providers.
	 *
	 * @param nanos clock reading for the scan
	 * @return outcome of the scan
	 */
	@Nonnull
	private CatalogRoutingSettlement scanAt(long nanos) {
		this.time.set(nanos);
		return this.scan.evaluateCatalogRouting(this.evita, List.of(this.graphQL, this.rest));
	}

	/**
	 * Asserts that a scan left startup routing open with the given number of pending catalogs.
	 *
	 * @param expected expected pending catalog count
	 * @param outcome outcome of the scan
	 */
	private static void assertPending(int expected, @Nonnull CatalogRoutingSettlement outcome) {
		assertFalse(outcome.settled(), "Startup routing must stay open while a catalog is pending");
		assertEquals(expected, outcome.catalogsPending());
	}

	/**
	 * API provider whose per-catalog routing answer the test sets, counting how often the scan asks.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class RoutingProvider implements ExternalApiProvider<ObservabilityOptions> {
		private final String code;
		private final ObservabilityOptions configuration = new ObservabilityOptions();
		private final Set<String> publishedCatalogs = ConcurrentHashMap.newKeySet();
		private final AtomicInteger consultations = new AtomicInteger();

		/**
		 * Creates a provider that has published the given catalogs.
		 *
		 * @param code API code reported to the scan
		 * @param publishedCatalogs catalogs whose routing has settled
		 */
		private RoutingProvider(@Nonnull String code, @Nonnull String... publishedCatalogs) {
			this.code = code;
			this.publishedCatalogs.addAll(List.of(publishedCatalogs));
		}

		/**
		 * Makes the catalog's publication owed.
		 *
		 * @param catalogName catalog whose routing becomes unsettled
		 */
		private void owe(@Nonnull String catalogName) {
			this.publishedCatalogs.remove(catalogName);
		}

		/**
		 * Settles the catalog's publication.
		 *
		 * @param catalogName catalog whose routing becomes settled
		 */
		private void publish(@Nonnull String catalogName) {
			this.publishedCatalogs.add(catalogName);
		}

		/**
		 * Returns the API code the scan logs for an overdue publication.
		 *
		 * @return API code given to the constructor
		 */
		@Nonnull
		@Override
		public String getCode() {
			return this.code;
		}

		/**
		 * Returns default options; the scan never reads them.
		 *
		 * @return default observability options
		 */
		@Nonnull
		@Override
		public ObservabilityOptions getConfiguration() {
			return this.configuration;
		}

		/**
		 * Serves no HTTP endpoint.
		 *
		 * @return an empty array
		 */
		@Nonnull
		@Override
		public HttpServiceDefinition[] getHttpServiceDefinitions() {
			return new HttpServiceDefinition[0];
		}

		/**
		 * Reports the API as ready; the scan never asks.
		 *
		 * @return always true
		 */
		@Override
		public boolean isReady() {
			return true;
		}

		/**
		 * Answers from the catalogs the test has published and counts the question.
		 *
		 * @param catalogName name whose routing is being checked
		 * @return true if the test has published the catalog
		 */
		@Override
		public boolean isCatalogRoutingSettled(@Nonnull String catalogName) {
			this.consultations.incrementAndGet();
			return this.publishedCatalogs.contains(catalogName);
		}
	}
}
