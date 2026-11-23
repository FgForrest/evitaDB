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

package io.evitadb.core.catalog;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.SESSION;
import static io.evitadb.test.TestTags.STORAGE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/**
 * Pins what {@link Catalog#terminate()} may publish when it terminates a `WARMING_UP` catalog whose read-write session
 * is still registered - which is what engine shutdown does when its session drain gives up on a call that has not
 * returned within the drain's budget, and terminates the catalogs anyway.
 *
 * A warm-up call changes the catalog in place, and the schema it leaves behind is validated only when its session
 * closes. Termination publishing while such a call is still running therefore publishes a state nobody checked - and
 * one the call can still be changing while the collections are flushed. The tests call {@link Catalog#terminate()}
 * directly rather than closing the engine: the engine path reaches it only after the five-second drain gives up, and
 * the termination it then runs is this one.
 *
 * Every assertion about what was published reads a fresh engine opened over a copy of the storage taken right after
 * the termination, because the in-memory catalog would answer for state that never reached the disk, and the original
 * engine still has a session registered on it.
 */
@DisplayName("Termination of a warming-up catalog with its writer still registered")
@Tag(ENGINE)
@Tag(STORAGE)
@Tag(SESSION)
class CatalogWarmUpTerminationTest implements EvitaTestSupport {

	/** A managed reference of the product, which the writers below declare. */
	private static final String REFERENCE_BRAND = "brand";
	/** An entity type never defined in the catalog, so a managed reference to it does not pass validation. */
	private static final String NON_EXISTING_ENTITY_TYPE = "nonExistingEntityType";
	/** Primary key of the product a finished call of the writer stores. */
	private static final int IDLE_WRITE_PK = 1;
	/** Primary key of the product a call of the writer is still storing when the catalog terminates. */
	private static final int RUNNING_WRITE_PK = 2;
	/** Bound of every positive wait of this test. */
	private static final long POSITIVE_WAIT_SECONDS = 30L;
	/**
	 * How long a call starting while termination decides is given to finish before the termination goes on - a
	 * detection window, never a synchronization: a loaded machine can only make the call slower, which makes the
	 * unfixed termination less likely to publish it and can never fail the fixed one.
	 */
	private static final long DETECTION_WINDOW_MILLIS = 250L;
	/** Name prefix of the fixture's own threads, so a stack dump of a stuck run says which test owns them. */
	private static final String FIXTURE_THREAD_PREFIX = "catalog-warm-up-termination-test-";

	/** Releases the call parked inside the writer's session; counted down by the tests and again by the tear-down. */
	private final CountDownLatch releaseRunningCall = new CountDownLatch(1);
	/** Numbers the fixture threads. */
	private final AtomicInteger fixtureThreadCounter = new AtomicInteger();
	private TestPaths paths;
	private TestPaths snapshotPaths;
	private Evita evita;
	private ExecutorService fixtureThreads;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("CatalogWarmUpTermination");
		this.snapshotPaths = createTestPaths("CatalogWarmUpTerminationSnapshot");
		this.fixtureThreads = Executors.newCachedThreadPool(
			runnable -> {
				final Thread thread = new Thread(
					runnable, FIXTURE_THREAD_PREFIX + this.fixtureThreadCounter.incrementAndGet()
				);
				thread.setDaemon(true);
				return thread;
			}
		);
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
		// published by the close of this session - the last published state every test starts from
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.BRAND)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
			}
		);
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		// a test that failed before releasing its parked call must not leave the call parked behind the engine close
		this.releaseRunningCall.countDown();
		this.fixtureThreads.shutdownNow();
		this.fixtureThreads.awaitTermination(POSITIVE_WAIT_SECONDS, SECONDS);
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
		cleanupTestPaths(this.snapshotPaths);
	}

	@Nested
	@DisplayName("when a call of the writer is running")
	class RunningCall {

		@Test
		@Tag(SCHEMA)
		@DisplayName("should not publish a schema evolved by a call still running when the catalog terminates")
		void shouldNotPublishSchemaEvolvedByCallStillRunningWhenCatalogTerminates() throws IOException {
			final CatalogWarmUpTerminationTest test = CatalogWarmUpTerminationTest.this;
			final Catalog catalog = test.catalog();
			final EvitaSessionContract writer = test.evita.createReadWriteSession(TEST_CATALOG);
			final CountDownLatch evolved = new CountDownLatch(1);
			final Future<?> runningCall = test.fixtureThreads.submit(
				() -> writer.upsertEntity(evolvingUpsert(RUNNING_WRITE_PK, evolved, test.releaseRunningCall))
			);
			awaitLatch(evolved, "the writer never evolved the product schema");
			// the evolution is applied in place, so the catalog holds a schema the session close would refuse
			assertTrue(
				catalog.getEntitySchema(Entities.PRODUCT).orElseThrow().getReference(REFERENCE_BRAND).isPresent(),
				"the running call must have evolved the schema before the catalog terminates"
			);

			final List<ILoggingEvent> warnings = captureCatalogWarnings(catalog::terminate);
			assertTrue(catalog.isTerminated());
			test.snapshotStorage();
			// what the call does once it is released is beside the point - it runs against a terminated catalog
			test.releaseRunningCall.countDown();
			awaitCompletion(runningCall);

			assertFalse(
				test.readSnapshotProductSchema().getReference(REFERENCE_BRAND).isPresent(),
				"a schema evolved by a call that was still running must not be published by the termination"
			);
			assertTrue(
				warnings.stream().anyMatch(
					event -> event.getFormattedMessage().contains(TEST_CATALOG)
						&& event.getFormattedMessage().contains("still running")
				),
				() -> "the skipped publication must be reported naming the catalog and the reason, but the warnings " +
					"were: " + warnings.stream().map(ILoggingEvent::getFormattedMessage).toList()
			);
			// the call the termination left running closes its session when it returns, and that close must not
			// publish the half-terminated instance either
			assertFalse(
				catalog.isPublishable(),
				"a termination that skipped its publication must leave the instance unable to publish"
			);
		}

		@Test
		@Tag(SCHEMA)
		@DisplayName("should not publish a schema evolved by a call starting after termination decided to publish")
		void shouldNotPublishSchemaEvolvedByCallStartingAfterTerminationDecided()
			throws IOException, InterruptedException {
			final CatalogWarmUpTerminationTest test = CatalogWarmUpTerminationTest.this;
			final Catalog catalog = test.catalog();
			final EvitaSessionContract writer = test.evita.createReadWriteSession(TEST_CATALOG);
			// a finished call of the healthy writer - its product is what the termination publishes
			writer.upsertEntity(writer.createNewEntity(Entities.PRODUCT, IDLE_WRITE_PK));

			// The only way to hold the termination between its decision and its header write: the spy shares every
			// mutable holder of the catalog, and pauses the termination once it has asked whether it may publish
			final CountDownLatch decided = new CountDownLatch(1);
			final CountDownLatch resumeTermination = new CountDownLatch(1);
			final AtomicInteger decisions = new AtomicInteger();
			final Catalog terminating = spy(catalog);
			doAnswer(
				invocation -> {
					final Object publishable = invocation.callRealMethod();
					if (decisions.incrementAndGet() == 1) {
						decided.countDown();
						awaitLatch(resumeTermination, "the termination was never resumed");
					}
					return publishable;
				}
			).when(terminating).isPublishable();
			final Future<?> termination = test.fixtureThreads.submit(terminating::terminate);
			awaitLatch(decided, "the termination never asked whether it may publish");

			final CountDownLatch lateCallFinished = new CountDownLatch(1);
			final Future<?> lateCall = test.fixtureThreads.submit(
				() -> {
					try {
						writer.upsertEntity(evolvingUpsert(RUNNING_WRITE_PK, null, null));
					} finally {
						lateCallFinished.countDown();
					}
				}
			);
			final boolean finishedWhileTerminationDecided = lateCallFinished.await(
				DETECTION_WINDOW_MILLIS, MILLISECONDS
			);
			resumeTermination.countDown();
			awaitCompletion(termination);
			assertTrue(catalog.isTerminated());
			awaitCompletion(lateCall);
			test.snapshotStorage();

			assertFalse(
				test.readSnapshotProductSchema().getReference(REFERENCE_BRAND).isPresent(),
				"a schema evolved after the termination decided to publish must not be published by it"
			);
			assertTrue(
				test.readSnapshotProductPresence(IDLE_WRITE_PK),
				"the finished call of the healthy writer must still be published"
			);
			assertFalse(
				finishedWhileTerminationDecided,
				"a call starting while the termination publishes must wait for the termination to finish"
			);
		}
	}

	@Nested
	@DisplayName("when no call of the writer is running")
	class IdleWriter {

		@Test
		@DisplayName("should publish the entity of a finished call when the catalog terminates")
		void shouldPublishEntityOfFinishedCallWhenCatalogTerminates() throws IOException {
			final CatalogWarmUpTerminationTest test = CatalogWarmUpTerminationTest.this;
			final Catalog catalog = test.catalog();
			final EvitaSessionContract writer = test.evita.createReadWriteSession(TEST_CATALOG);
			writer.upsertEntity(writer.createNewEntity(Entities.PRODUCT, IDLE_WRITE_PK));

			final List<ILoggingEvent> warnings = captureCatalogWarnings(catalog::terminate);
			assertTrue(catalog.isTerminated());
			test.snapshotStorage();

			assertTrue(
				test.readSnapshotProductPresence(IDLE_WRITE_PK),
				"the unpublished entity of an idle healthy writer must be published at termination"
			);
			assertTrue(
				warnings.isEmpty(),
				() -> "a termination that publishes must not warn, but it warned: " +
					warnings.stream().map(ILoggingEvent::getFormattedMessage).toList()
			);
			// a call of the writer queued behind the termination resumes before the persistence service is closed,
			// on an instance whose collections are released - nothing it does may be published
			assertFalse(
				catalog.isPublishable(),
				"a terminated warm-up instance must not publish anything a call queued behind the termination does"
			);
		}

		@Test
		@Tag(SCHEMA)
		@DisplayName("should publish a schema change that finished before the catalog terminates")
		void shouldPublishSchemaChangeFinishedBeforeCatalogTerminates() throws IOException {
			final CatalogWarmUpTerminationTest test = CatalogWarmUpTerminationTest.this;
			final Catalog catalog = test.catalog();
			final EvitaSessionContract writer = test.evita.createReadWriteSession(TEST_CATALOG);
			writer.updateEntitySchema(
				new ModifyEntitySchemaMutation(Entities.PRODUCT, brandReference(Entities.BRAND))
			);

			catalog.terminate();
			assertTrue(catalog.isTerminated());
			test.snapshotStorage();

			assertTrue(
				test.readSnapshotProductSchema().getReference(REFERENCE_BRAND).isPresent(),
				"a valid schema change of an idle healthy writer must be published at termination"
			);
		}
	}

	/**
	 * Creates a managed reference of the product to the given entity type.
	 *
	 * @param referencedEntityType the entity type the reference targets - one the catalog does not define makes the
	 *                             reference fail validation, which the session close would refuse
	 * @return the mutation creating the reference
	 */
	@Nonnull
	private static CreateReferenceSchemaMutation brandReference(@Nonnull String referencedEntityType) {
		return new CreateReferenceSchemaMutation(
			REFERENCE_BRAND, null, null, Cardinality.ZERO_OR_ONE,
			referencedEntityType, true,
			null, false,
			false, false
		);
	}

	/**
	 * Creates a product upsert that evolves the product schema with a managed reference to an entity type that does
	 * not exist - the shape implicit schema evolution takes in a warm-up import - and optionally parks once the
	 * evolution has been applied. `EntityCollection#upsertEntity` applies the evolution in place before it reads the
	 * local mutations, so a call parked there holds an evolved schema while it is still running.
	 *
	 * @param primaryKey primary key of the product the upsert creates
	 * @param evolved    counted down when the call parks, once the evolution has been applied; `null` not to park
	 * @param release    awaited by the parked call; `null` not to park
	 * @return the mutation to pass to {@link EvitaSessionContract#upsertEntity(EntityMutation)}
	 */
	@Nonnull
	private static EntityMutation evolvingUpsert(
		int primaryKey,
		@Nullable CountDownLatch evolved,
		@Nullable CountDownLatch release
	) {
		final AtomicBoolean evolutionRequested = new AtomicBoolean();
		final AtomicBoolean parked = new AtomicBoolean();
		return new EntityUpsertMutation(Entities.PRODUCT, primaryKey, EntityExistence.MUST_NOT_EXIST, List.of()) {
			@Nonnull
			@Override
			public Optional<LocalEntitySchemaMutation[]> verifyOrEvolveSchema(
				@Nonnull SealedCatalogSchema catalogSchema,
				@Nonnull SealedEntitySchema entitySchema,
				boolean entityCollectionEmpty
			) {
				evolutionRequested.set(true);
				return Optional.of(new LocalEntitySchemaMutation[]{brandReference(NON_EXISTING_ENTITY_TYPE)});
			}

			@Nonnull
			@Override
			public List<? extends LocalMutation<?, ?>> getLocalMutations() {
				final boolean parks = evolved != null && release != null && evolutionRequested.get();
				if (parks && parked.compareAndSet(false, true)) {
					evolved.countDown();
					awaitLatch(release, "the running call was never released");
				}
				return super.getLocalMutations();
			}
		};
	}

	/**
	 * Waits for a latch the fixture owns, failing when it is not counted down in time.
	 *
	 * @param latch   the latch to await
	 * @param message the failure reported when the wait runs out
	 */
	private static void awaitLatch(@Nonnull CountDownLatch latch, @Nonnull String message) {
		try {
			assertTrue(latch.await(POSITIVE_WAIT_SECONDS, SECONDS), message);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * Waits for a fixture task to finish, whatever its outcome - the calls this test releases run against a
	 * terminated catalog, and how they fail is not what the test asserts.
	 *
	 * @param task the task to await
	 */
	private static void awaitCompletion(@Nonnull Future<?> task) {
		try {
			task.get(POSITIVE_WAIT_SECONDS, SECONDS);
		} catch (ExecutionException ignored) {
			// the outcome of the call is not under test
		} catch (TimeoutException ex) {
			fail("a fixture task did not finish in " + POSITIVE_WAIT_SECONDS + " s", ex);
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * Runs the action and returns the warnings `Catalog` logged about the test catalog meanwhile.
	 *
	 * @param action the action to run
	 * @return the captured warnings; never null
	 */
	@Nonnull
	private static List<ILoggingEvent> captureCatalogWarnings(@Nonnull Runnable action) {
		final Logger logger = assertInstanceOf(
			Logger.class, LoggerFactory.getLogger(Catalog.class), "Logback backend required to capture log output."
		);
		final List<ILoggingEvent> warnings = Collections.synchronizedList(new ArrayList<>(4));
		final ListAppender<ILoggingEvent> appender = new ListAppender<>() {
			@Override
			protected void append(ILoggingEvent eventObject) {
				if (eventObject.getLevel() == Level.WARN && eventObject.getFormattedMessage().contains(TEST_CATALOG)) {
					warnings.add(eventObject);
				}
			}
		};
		appender.start();
		logger.addAppender(appender);
		try {
			action.run();
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
		return List.copyOf(warnings);
	}

	/**
	 * Returns the catalog instance the engine serves under the test catalog name.
	 *
	 * @return the catalog; never null
	 */
	@Nonnull
	private Catalog catalog() {
		return assertInstanceOf(Catalog.class, this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow());
	}

	/**
	 * Copies the whole storage directory of the running engine aside, so what reached the disk can be read by a
	 * fresh engine while the original one still has the writer registered.
	 *
	 * @throws IOException when the storage cannot be copied
	 */
	private void snapshotStorage() throws IOException {
		final Path source = this.paths.storage();
		final Path target = this.snapshotPaths.storage();
		final List<Path> entries;
		try (final Stream<Path> tree = Files.walk(source)) {
			entries = tree.toList();
		}
		for (final Path entry : entries) {
			final Path destination = target.resolve(source.relativize(entry).toString());
			if (Files.isDirectory(entry)) {
				Files.createDirectories(destination);
			} else {
				Files.createDirectories(destination.getParent());
				Files.copy(entry, destination, StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	/**
	 * Reads the product schema of the catalog stored in the storage snapshot.
	 *
	 * @return the product schema as it reached the disk; never null
	 */
	@Nonnull
	private EntitySchemaContract readSnapshotProductSchema() {
		return readSnapshot(session -> session.getEntitySchemaOrThrowException(Entities.PRODUCT));
	}

	/**
	 * Tells whether the catalog stored in the storage snapshot holds the product.
	 *
	 * @param primaryKey primary key of the product
	 * @return true when the product reached the disk
	 */
	private boolean readSnapshotProductPresence(int primaryKey) {
		return readSnapshot(session -> session.getEntity(Entities.PRODUCT, primaryKey).isPresent());
	}

	/**
	 * Opens a fresh engine over the storage snapshot, reads from the test catalog and closes the engine again.
	 *
	 * @param reader what to read; runs in a read-only session
	 * @param <T>    type of what is read
	 * @return what was read
	 */
	@Nonnull
	private <T> T readSnapshot(@Nonnull Function<EvitaSessionContract, T> reader) {
		try (final Evita snapshotEvita = new Evita(newTestEvitaConfigurationBuilder(this.snapshotPaths).build())) {
			snapshotEvita.waitUntilFullyInitialized();
			return snapshotEvita.queryCatalog(TEST_CATALOG, reader);
		}
	}

}
