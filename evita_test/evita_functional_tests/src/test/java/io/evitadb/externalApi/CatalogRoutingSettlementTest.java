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

import io.evitadb.api.CatalogState;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.exception.CatalogGoingLiveException;
import io.evitadb.core.Evita;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.externalApi.configuration.HeaderOptions;
import io.evitadb.externalApi.graphql.GraphQLManager;
import io.evitadb.externalApi.graphql.api.catalog.SystemGraphQLRefreshingObserver;
import io.evitadb.externalApi.graphql.configuration.GraphQLOptions;
import io.evitadb.externalApi.rest.RestManager;
import io.evitadb.externalApi.rest.api.catalog.SystemRestRefreshingObserver;
import io.evitadb.externalApi.rest.configuration.RestOptions;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.AbstractList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static io.evitadb.test.TestTags.GRAPHQL;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.REST;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the real catalog managers and their system change capture observers.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(GRAPHQL)
@Tag(REST)
@Tag(MANAGEMENT)
@DisplayName("Per-API catalog routing settlement")
class CatalogRoutingSettlementTest implements EvitaTestSupport {
	private static final String SOURCE = "source";
	private static final String TARGET = "target";
	private static final String HOST = "localhost:5555";
	private static final HeaderOptions HEADERS = new HeaderOptions();

	private TestPaths paths;
	private EvitaConfiguration configuration;

	/**
	 * Persists a catalog before opening the engine under test.
	 */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths(getClass().getSimpleName());
		this.configuration = newTestEvitaConfigurationBuilder(this.paths).build();
		try (final Evita seed = new Evita(this.configuration)) {
			seed.defineCatalog(SOURCE);
			seed.updateCatalog(SOURCE, session -> {
				session.defineEntitySchema("Product").updateVia(session);
				session.goLiveAndClose();
			});
		}
	}

	/**
	 * Removes the storage after the scenario's engine closes.
	 */
	@AfterEach
	void tearDown() {
		cleanupTestPaths(this.paths);
	}

	/**
	 * Covers managers attached before the engine starts loading.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	@Nested
	@DisplayName("Publication owed")
	class OwedCatalogRouting {

		/**
		 * Checks that failure settles routing and a successful retry clears the failure mark.
		 *
		 * @param graphql whether to exercise GraphQL instead of REST
		 * @param internal whether the injected failure is an internal error
		 */
		@ParameterizedTest(name = "GraphQL={0}, internal error={1}")
		@CsvSource({"true,true", "true,false", "false,true", "false,false"})
		@DisplayName("Settles after failure and clears the failure mark on successful registration")
		void shouldSettleAfterFailedPublication(boolean graphql, boolean internal) {
			try (final Evita evita = new Evita(CatalogRoutingSettlementTest.this.configuration, false)) {
				final RoutingSettlementFixture fixture = createSettlementFixture(evita, graphql);
				fixture.finishLoading(evita);
				assertFalse(fixture.settled().test(SOURCE), "An owed publication must start pending");

				final RuntimeException failure = internal ? new GenericEvitaInternalError("Publication failed") :
					new IllegalStateException("Publication failed");
				fixture.seam().failure = failure;
				if (internal) {
					assertFalse(fixture.register().test(SOURCE));
				} else {
					assertSame(
						failure, assertThrows(IllegalStateException.class, () -> fixture.register().test(SOURCE))
					);
				}
				assertFalse(fixture.registered().test(SOURCE));
				assertTrue(fixture.settled().test(SOURCE), "Failed publication must settle routing");

				fixture.seam().failure = null;
				assertTrue(fixture.register().test(SOURCE));
				assertTrue(fixture.registered().test(SOURCE));
				assertTrue(fixture.settled().test(SOURCE), "Published routes must settle routing");
				fixture.unregister().accept(SOURCE);
				assertFalse(fixture.settled().test(SOURCE), "Successful registration must clear the failure mark");
			}
		}

		/**
		 * Drives an actual rename capture and observes both names after the observer's cleanup.
		 *
		 * @param graphql whether to exercise GraphQL instead of REST
		 * @param internal whether the injected failure is an internal error
		 * @throws InterruptedException if interrupted while waiting for the rename observer
		 */
		@ParameterizedTest(name = "GraphQL={0}, internal error={1}")
		@CsvSource({"true,true", "true,false", "false,true", "false,false"})
		@DisplayName("Keeps the failed publication settled after rename cleanup")
		void shouldPreserveFailureAfterRename(boolean graphql, boolean internal) throws InterruptedException {
			try (final Evita evita = new Evita(CatalogRoutingSettlementTest.this.configuration, false)) {
				final RoutingSettlementFixture fixture = createSettlementFixture(evita, graphql);
				fixture.finishLoading(evita);
				assertTrue(fixture.register().test(SOURCE));
				fixture.seam().failure = internal ? new GenericEvitaInternalError("Rename publication failed") :
					new IllegalStateException("Rename publication failed");

				evita.renameCatalog(SOURCE, TARGET);
				assertTrue(fixture.seam().sourceRemoved.await(30, SECONDS), "Rename cleanup never reached the source");
				assertEquals(CatalogState.ALIVE, evita.getCatalogState(TARGET).orElseThrow());
				assertFalse(fixture.registered().test(SOURCE));
				assertFalse(fixture.registered().test(TARGET));
				assertTrue(fixture.settled().test(TARGET), "Rename cleanup must preserve the failed publication mark");
			}
		}

		/**
		 * Replaces a catalog whose routes are registered and fails the rebuild that would keep them serving.
		 *
		 * @param graphql whether to exercise GraphQL instead of REST
		 * @param internal whether the injected failure is an internal error
		 * @throws InterruptedException if interrupted while waiting for the rename observer
		 */
		@ParameterizedTest(name = "GraphQL={0}, internal error={1}")
		@CsvSource({"true,true", "true,false", "false,true", "false,false"})
		@DisplayName("Settles a replaced target whose rebuild failed")
		void shouldPreserveFailureAfterReplacingRegisteredTarget(boolean graphql, boolean internal)
			throws InterruptedException {
			try (final Evita evita = new Evita(CatalogRoutingSettlementTest.this.configuration, false)) {
				final RoutingSettlementFixture fixture = createSettlementFixture(evita, graphql);
				fixture.finishLoading(evita);
				assertTrue(fixture.register().test(SOURCE));
				evita.defineCatalog(TARGET);
				evita.updateCatalog(TARGET, session -> {
					session.defineEntitySchema("Product").updateVia(session);
					session.goLiveAndClose();
				});
				if (!fixture.registered().test(TARGET)) {
					assertTrue(fixture.register().test(TARGET));
				}
				assertTrue(fixture.registered().test(TARGET), "The replaced target must start with published routes");
				fixture.seam().failure = internal ? new GenericEvitaInternalError("Replacement publication failed") :
					new IllegalStateException("Replacement publication failed");

				evita.replaceCatalog(SOURCE, TARGET);
				assertTrue(
					fixture.seam().sourceRemoved.await(30, SECONDS), "Replacement cleanup never reached the source"
				);
				assertEquals(CatalogState.ALIVE, evita.getCatalogState(TARGET).orElseThrow());
				assertFalse(fixture.registered().test(TARGET));
				assertTrue(fixture.settled().test(TARGET), "A failed rebuild of a replaced target must settle routing");
			}
		}

		/**
		 * Exercises skipped registration against the real placeholder and the go-live exception from a build seam.
		 *
		 * @param graphql whether to exercise GraphQL instead of REST
		 */
		@ParameterizedTest(name = "GraphQL={0}")
		@ValueSource(booleans = {true, false})
		@DisplayName("Leaves an unusable catalog or a go-live interruption pending")
		void shouldSkipUnusableAndGoingLiveCatalogs(boolean graphql) {
			try (final Evita evita = new Evita(CatalogRoutingSettlementTest.this.configuration, false)) {
				final RoutingSettlementFixture fixture = createSettlementFixture(evita, graphql);
				assertEquals(CatalogState.BEING_ACTIVATED, evita.getCatalogState(SOURCE).orElseThrow());
				assertFalse(fixture.register().test(SOURCE), "Registration must skip the real boot placeholder");
				assertFalse(fixture.settled().test(SOURCE), "Skipping an unusable catalog must leave routing pending");
				fixture.finishLoading(evita);

				final CatalogGoingLiveException failure = new CatalogGoingLiveException(SOURCE);
				fixture.seam().failure = failure;
				assertSame(
					failure, assertThrows(CatalogGoingLiveException.class, () -> fixture.register().test(SOURCE))
				);
				assertFalse(fixture.settled().test(SOURCE), "Going live must leave routing pending");
			}
		}

		/**
		 * Delivers subscription failure through the production observer's terminal callback.
		 *
		 * @param graphql whether to exercise GraphQL instead of REST
		 */
		@ParameterizedTest(name = "GraphQL={0}")
		@ValueSource(booleans = {true, false})
		@DisplayName("Settles owed publication when the subscription fails")
		void shouldSettleUnpublishedCatalogsOnSubscriptionError(boolean graphql) {
			try (final Evita evita = new Evita(CatalogRoutingSettlementTest.this.configuration, false)) {
				final RoutingSettlementFixture fixture = createSettlementFixture(evita, graphql);
				fixture.finishLoading(evita);
				assertFalse(fixture.settled().test(SOURCE));
				fixture.subscriptionFailure().accept(new IllegalStateException("Catalog subscription failed"));
				assertFalse(fixture.registered().test(SOURCE));
				assertTrue(fixture.settled().test(SOURCE), "A terminated subscription owes no publication");
			}
		}
	}

	/**
	 * Checks the already-active catalog snapshot against an engine loaded before subscribing.
	 *
	 * @param graphql whether to exercise GraphQL instead of REST
	 */
	@ParameterizedTest(name = "GraphQL={0}")
	@ValueSource(booleans = {true, false})
	@DisplayName("Settles an already active catalog without registering its routes")
	void shouldSettleWhenAlreadyActive(boolean graphql) {
		try (final Evita evita = new Evita(this.configuration)) {
			evita.waitUntilFullyInitialized();
			assertEquals(CatalogState.ALIVE, evita.getCatalogState(SOURCE).orElseThrow());
			final RoutingSettlementFixture fixture = createSettlementFixture(evita, graphql);
			assertFalse(fixture.registered().test(SOURCE));
			assertTrue(fixture.settled().test(SOURCE), "An already active catalog owes no publication");
		}
	}

	/**
	 * Creates a manager with a build-failure seam and suppresses the initial automatic publication of the source.
	 *
	 * @param evita engine whose CDC stream the manager subscribes to
	 * @param graphql whether to build the GraphQL manager instead of REST
	 * @return manager operations and the seam controlling the publication under test
	 */
	@Nonnull
	private static RoutingSettlementFixture createSettlementFixture(@Nonnull Evita evita, boolean graphql) {
		final SettlementBuildSeam seam = new SettlementBuildSeam();
		if (graphql) {
			final GraphQLOptions options = new GraphQLOptions(HOST) {
				/**
				 * Reads the real fetcher option or throws the armed build failure.
				 *
				 * @return whether GraphQL execution is parallelized
				 * @throws RuntimeException if a publication failure is armed
				 */
				@Override
				public boolean isParallelize() {
					seam.checkPublicationFailure();
					return super.isParallelize();
				}
			};
			final GraphQLManager manager = new GraphQLManager(evita, HEADERS, options) {
				/**
				 * Leaves the initial CDC publication owed so the test can trigger it explicitly.
				 *
				 * @param catalogName name being registered
				 * @return true when the catalog was published
				 */
				@Override
				public boolean registerCatalog(@Nonnull String catalogName) {
					return !seam.suppressBootRegistration && super.registerCatalog(catalogName);
				}

				/**
				 * Signals that rename cleanup removed the old name after processing the new name.
				 *
				 * @param catalogName name being removed
				 * @return whether the old routes were registered
				 */
				@Override
				public boolean unregisterCatalog(@Nonnull String catalogName) {
					final boolean unregistered = super.unregisterCatalog(catalogName);
					if (SOURCE.equals(catalogName)) {
						seam.sourceRemoved.countDown();
					}
					return unregistered;
				}
			};
			return new RoutingSettlementFixture(
				seam, manager::registerCatalog, manager::unregisterCatalog, manager::isCatalogRegistered,
				manager::isCatalogRoutingSettled, new SystemGraphQLRefreshingObserver(manager)::onError
			);
		}
		final HeaderOptions headers = new HeaderOptions(
			HEADERS.forwardedUri(), HEADERS.forwardedFor(), HEADERS.label(), HEADERS.clientId(),
			new AbstractList<>() {
				/**
				 * Supplies the configured trace headers during real REST CORS construction.
				 *
				 * @param index index of the configured header
				 * @return header value
				 * @throws RuntimeException if a publication failure is armed
				 */
				@Override
				public String get(int index) {
					seam.checkPublicationFailure();
					return HEADERS.traceParent().get(index);
				}

				/**
				 * Returns the size of the configured trace header list.
				 *
				 * @return configured number of headers
				 */
				@Override
				public int size() {
					return HEADERS.traceParent().size();
				}
			}
		);
		final RestManager manager = new RestManager(evita, headers, new RestOptions(HOST)) {
			/**
			 * Leaves the initial CDC publication owed so the test can trigger it explicitly.
			 *
			 * @param catalogName name being registered
			 * @return true when the catalog was published
			 */
			@Override
			public boolean registerCatalog(@Nonnull String catalogName) {
				return !seam.suppressBootRegistration && super.registerCatalog(catalogName);
			}

			/**
			 * Signals that rename cleanup removed the old name after processing the new name.
			 *
			 * @param catalogName name being removed
			 */
			@Override
			public void unregisterCatalog(@Nonnull String catalogName) {
				super.unregisterCatalog(catalogName);
				if (SOURCE.equals(catalogName)) {
					seam.sourceRemoved.countDown();
				}
			}
		};
		return new RoutingSettlementFixture(
			seam, manager::registerCatalog, manager::unregisterCatalog, manager::isCatalogRegistered,
			manager::isCatalogRoutingSettled, new SystemRestRefreshingObserver(manager)::onError
		);
	}

	/**
	 * Controls the real configuration reads at the GraphQL and REST build seams.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class SettlementBuildSeam {
		private final CountDownLatch sourceRemoved = new CountDownLatch(1);
		private volatile boolean suppressBootRegistration;
		@Nullable private volatile RuntimeException failure;

		/**
		 * Throws the failure selected for the current publication.
		 *
		 * @throws RuntimeException when a failure is armed
		 */
		private void checkPublicationFailure() {
			final RuntimeException current = this.failure;
			if (current != null) {
				throw current;
			}
		}
	}

	/**
	 * Common operations on the API manager selected by the test.
	 *
	 * @param seam configuration read and cleanup observations
	 * @param register builds and publishes a catalog
	 * @param unregister removes catalog routes
	 * @param registered tests whether routes are registered
	 * @param settled tests whether this manager still owes a publication
	 * @param subscriptionFailure sends an error to the observer's terminal callback
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private record RoutingSettlementFixture(
		@Nonnull SettlementBuildSeam seam,
		@Nonnull Predicate<String> register,
		@Nonnull Consumer<String> unregister,
		@Nonnull Predicate<String> registered,
		@Nonnull Predicate<String> settled,
		@Nonnull Consumer<Throwable> subscriptionFailure
	) {

		/**
		 * Loads the engine while intentionally leaving the catalog's publication owed.
		 *
		 * @param evita engine with a manager already subscribed to catalog events
		 */
		private void finishLoading(@Nonnull Evita evita) {
			this.seam.suppressBootRegistration = true;
			try {
				// The embedded test engine runs its capture executor inline.
				evita.scheduleInitialCatalogLoading();
				evita.waitUntilFullyInitialized();
			} finally {
				this.seam.suppressBootRegistration = false;
			}
			assertEquals(CatalogState.ALIVE, evita.getCatalogState(SOURCE).orElseThrow());
			assertFalse(this.registered.test(SOURCE));
		}
	}
}
