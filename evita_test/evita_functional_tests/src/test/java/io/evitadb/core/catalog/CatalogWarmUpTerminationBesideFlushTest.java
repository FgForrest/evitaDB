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
import io.evitadb.api.CommitProgress.CommitVersions;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.exception.InstanceTerminatedException;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.progress.ProgressingFuture;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.collection.EntityCollection;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static io.evitadb.api.query.QueryConstraints.attributeEquals;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.referenceContentAll;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.SESSION;
import static io.evitadb.test.TestTags.STORAGE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/**
 * Pins what {@link Catalog#terminate()} publishes when it terminates a `WARMING_UP` catalog while the flush of its
 * writer's session close is in progress - started, and not yet published.
 *
 * The warm-up branch of the session close validates the schema, builds the catalog flush - which pops the trapped
 * changes of every collection right there, on the closing thread - and dispatches one write per collection to the
 * transaction executor; the catalog header is stored only once every write has finished. When the writer is idle at
 * engine shutdown, the session drain runs that close on its own thread, which does not hold the warm-up call lock, and
 * waits five seconds for it. A flush that outlasts the wait leaves the drain to give up, and the engine then terminates
 * the catalog anyway while the transaction executor keeps running the remaining writes. A termination that published
 * then would find nothing trapped in a collection whose write has not run - the flush has popped it already - and
 * store a header naming indexes whose parts only that write would have stored: a catalog that no longer loads. The
 * termination therefore publishes nothing beside a flush in progress, and the flush publishes nothing once the
 * termination has begun; the last published state stays the catalog's state.
 *
 * The tests stand in for the drain by calling the same two methods the close calls, {@link Catalog#flush()} and
 * {@link ProgressingFuture#execute(Executor)}, and hand the flush an executor that holds the collection writes back,
 * which decides how far the flush has got when the catalog terminates. That is the only seam: the close dispatches to
 * the engine's own transaction executor, which a test cannot hold between two writes. Where the termination itself
 * has to be held, a spy pauses it once it has begun, at the point where it asks whether it may publish - the spy
 * shares every mutable holder of the catalog.
 *
 * One product upsert referencing a category writes into both collections: the product's own parts go to its
 * collection's storage directly, the reflected reference the category receives is trapped in the category's buffer,
 * and the indexes of both are trapped too - including the indexes the write creates, which for the first product of
 * the catalog are all the indexes of its collection. A category upserted beside it gives each collection a direct
 * write, so a termination that does publish stores its header whichever collection it finds changed. Every assertion
 * about what was published reads a fresh engine opened over a copy of the storage taken right after the termination.
 */
@DisplayName("Termination of a warming-up catalog beside a flush in progress")
@Tag(ENGINE)
@Tag(STORAGE)
@Tag(SESSION)
@Tag(REFERENCE)
class CatalogWarmUpTerminationBesideFlushTest implements EvitaTestSupport {

	/** The product's reference to categories. */
	private static final String REFERENCE_CATEGORIES = "categories";
	/** The category's reflection of {@link #REFERENCE_CATEGORIES}. */
	private static final String REFERENCE_PRODUCTS = "products";
	/** A filterable attribute of the category, answered from the category's attribute index. */
	private static final String ATTRIBUTE_LABEL = "label";
	/** Primary key of the category published by the set-up session, which the writer's product references. */
	private static final int REFERENCED_CATEGORY_PK = 1;
	/** Primary key of the product the writer stores - the first product of the catalog. */
	private static final int WRITTEN_PRODUCT_PK = 1;
	/** Primary key of the category the writer stores. */
	private static final int WRITTEN_CATEGORY_PK = 2;
	/** Label of the category the writer stores. */
	private static final String WRITTEN_CATEGORY_LABEL = "written";
	/** Bound of every positive wait of this test. */
	private static final long POSITIVE_WAIT_SECONDS = 30L;
	/**
	 * How long the termination is given to finish while a header write is held - a negative wait: a loaded machine can
	 * only make the termination slower, which can never fail the assertion that it waits.
	 */
	private static final long NEGATIVE_WAIT_MILLIS = 250L;
	/** Name prefix of the fixture's own threads, so a stack dump of a stuck run says which test owns them. */
	private static final String FIXTURE_THREAD_PREFIX = "catalog-warm-up-termination-beside-flush-test-";

	/** Releases whatever a test parked - a termination or a header write; counted down again by the tear-down. */
	private final CountDownLatch releaseParked = new CountDownLatch(1);
	/** Counted down once a parked termination has begun and reached its publication decision. */
	private final CountDownLatch terminationParked = new CountDownLatch(1);
	/** Numbers the fixture threads. */
	private final AtomicInteger fixtureThreadCounter = new AtomicInteger();
	private TestPaths paths;
	private TestPaths snapshotPaths;
	private Evita evita;
	private ExecutorService fixtureThreads;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("CatalogWarmUpTerminationBesideFlush");
		this.snapshotPaths = createTestPaths("CatalogWarmUpTerminationBesideFlushSnapshot");
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
				session.defineEntitySchema(Entities.CATEGORY)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_LABEL, String.class, thatIs -> thatIs.filterable().nullable())
					.withReflectedReferenceToEntity(
						REFERENCE_PRODUCTS, Entities.PRODUCT, REFERENCE_CATEGORIES,
						thatIs -> thatIs.indexedForFiltering()
					)
					.updateVia(session);
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(
						REFERENCE_CATEGORIES, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
						thatIs -> thatIs.indexedForFiltering()
					)
					.updateVia(session);
				session.createNewEntity(Entities.CATEGORY, REFERENCED_CATEGORY_PK).upsertVia(session);
			}
		);
	}

	@AfterEach
	void tearDown() throws InterruptedException {
		// a test that failed before releasing what it parked must not leave it parked behind the engine close
		this.releaseParked.countDown();
		this.fixtureThreads.shutdownNow();
		this.fixtureThreads.awaitTermination(POSITIVE_WAIT_SECONDS, SECONDS);
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
		cleanupTestPaths(this.snapshotPaths);
	}

	@Nested
	@DisplayName("when the termination meets a flush that has written only some collections")
	class FlushInProgress {

		@Test
		@DisplayName("should publish nothing, and still release the catalog and complete every future")
		void shouldPublishNothingAndStillReleaseEverythingWhenFlushIsInProgress() throws IOException {
			final CatalogWarmUpTerminationBesideFlushTest test = CatalogWarmUpTerminationBesideFlushTest.this;
			final Catalog catalog = test.catalog();
			final EvitaSessionContract writer = test.evita.createReadWriteSession(TEST_CATALOG);
			writeProductAndCategory(writer);
			final InFlightFlush flush = startFlush(catalog::flush);
			final List<EntityCollection> collections = collections(catalog);
			final List<Long> versionsBeforeWrite = collections.stream().map(EntityCollection::getVersion).toList();
			flush.heldBack().runNext();
			final List<String> heldBackEntityTypes = heldBackEntityTypes(collections, versionsBeforeWrite, flush);

			final List<ILoggingEvent> warnings = captureCatalogWarnings(catalog::terminate);
			assertTrue(catalog.isTerminated(), "the storage must be closed although nothing was published");
			test.snapshotStorage();
			// the write the termination did not wait for runs against released collections
			flush.heldBack().runRemaining();
			final CompletableFuture<CommitVersions> writerClose = writer
				.closeNow(CommitBehavior.WAIT_FOR_CONFLICT_RESOLUTION)
				.toCompletableFuture();
			awaitCompletion(writerClose);

			// what reached the disk first - a torn publication does not even load
			final Map<String, Boolean> published = test.readPublishedWrite(heldBackEntityTypes);
			assertTrue(
				published.values().stream().noneMatch(Boolean::booleanValue),
				() -> "a termination beside a flush in progress must leave the last published state, but the " +
					"published state holds: " + published + " " + describeHeldBackWrites(heldBackEntityTypes)
			);
			assertAll(
				() -> assertTrue(
					collections.stream().allMatch(EntityCollection::isTerminated),
					"every collection must be released although nothing was published"
				),
				() -> assertTrue(
					catalog.getCollectionForEntityInternal(Entities.PRODUCT).isEmpty(),
					"the terminated catalog must hold no collection any more"
				),
				() -> assertTrue(
					flush.future().isCompletedExceptionally(),
					"the flush the termination met must complete, exceptionally, rather than stay pending"
				),
				() -> assertTrue(
					writerClose.isDone(), "the close of the writer left registered must complete rather than hang"
				),
				() -> assertTrue(
					warnings.stream().anyMatch(
						event -> event.getFormattedMessage().contains(TEST_CATALOG)
							&& event.getFormattedMessage().contains("dropped")
					),
					() -> "the termination must report that the unpublished work of the flushing session is " +
						"dropped, but the warnings were: " +
						warnings.stream().map(ILoggingEvent::getFormattedMessage).toList()
				)
			);
		}
	}

	@Nested
	@DisplayName("when the flush finishes writing after the termination began")
	class FlushFinishingAfterTerminationBegan {

		@Test
		@DisplayName("should not let the flush publish")
		void shouldNotPublishFlushFinishingAfterTerminationBegan() throws IOException {
			final CatalogWarmUpTerminationBesideFlushTest test = CatalogWarmUpTerminationBesideFlushTest.this;
			final Catalog catalog = test.catalog();
			final EvitaSessionContract writer = test.evita.createReadWriteSession(TEST_CATALOG);
			writeProductAndCategory(writer);
			final InFlightFlush flush = startFlush(catalog::flush);

			final Future<?> termination = test.fixtureThreads.submit(test.parkingTermination(catalog)::terminate);
			awaitLatch(test.terminationParked, "the termination never reached its publication decision");
			// the termination has begun - now every collection is written and the flush reaches its header write
			flush.heldBack().runRemaining();
			final Throwable flushFailure = flush.future().handle((result, ex) -> ex).join();
			test.releaseParked.countDown();
			awaitCompletion(termination);
			assertTrue(catalog.isTerminated());
			test.snapshotStorage();

			assertInstanceOf(
				InstanceTerminatedException.class, rootCause(flushFailure),
				"a flush reaching its header write after the termination began must be refused, but it ended with: " +
					flushFailure
			);
			final Map<String, Boolean> published = test.readPublishedWrite(List.of());
			assertTrue(
				published.values().stream().noneMatch(Boolean::booleanValue),
				() -> "neither the flush nor the termination may publish, but the published state holds: " + published
			);
		}
	}

	@Nested
	@DisplayName("when a flush starts after the termination began")
	class FlushStartingAfterTerminationBegan {

		@Test
		@DisplayName("should refuse the flush before it pops anything")
		void shouldRefuseFlushStartingAfterTerminationBegan() throws IOException {
			final CatalogWarmUpTerminationBesideFlushTest test = CatalogWarmUpTerminationBesideFlushTest.this;
			final Catalog catalog = test.catalog();
			final EvitaSessionContract writer = test.evita.createReadWriteSession(TEST_CATALOG);
			writeProductAndCategory(writer);

			final Future<?> termination = test.fixtureThreads.submit(test.parkingTermination(catalog)::terminate);
			awaitLatch(test.terminationParked, "the termination never reached its publication decision");
			// what a session close the drain runs without the warm-up call lock does at this moment
			assertThrows(
				InstanceTerminatedException.class, catalog::flush,
				"a flush starting after the termination began must be refused"
			);
			test.releaseParked.countDown();
			awaitCompletion(termination);
			assertTrue(catalog.isTerminated());
			test.snapshotStorage();

			final Map<String, Boolean> published = test.readPublishedWrite(List.of());
			assertTrue(
				published.values().stream().allMatch(Boolean::booleanValue),
				() -> "the termination must publish the write whole, which the refused flush must have left " +
					"trapped, but the published state holds: " + published
			);
		}
	}

	@Nested
	@DisplayName("when the flush is writing the catalog header")
	class HeaderWriteUnderWay {

		@Test
		@DisplayName("should let the termination wait for the header write")
		void shouldWaitForHeaderWriteUnderWayBeforeTerminating() throws IOException, InterruptedException {
			final CatalogWarmUpTerminationBesideFlushTest test = CatalogWarmUpTerminationBesideFlushTest.this;
			final Catalog catalog = test.catalog();
			final EvitaSessionContract writer = test.evita.createReadWriteSession(TEST_CATALOG);
			writeProductAndCategory(writer);

			// The only way to hold a flush inside its publication: the flush asks whether it may still publish once
			// when it starts and once more inside the section that ends with the header write - the spy pauses the
			// second question, with the section entered
			final CountDownLatch headerWriteEntered = new CountDownLatch(1);
			final AtomicInteger publishabilityChecks = new AtomicInteger();
			final Catalog flushing = spy(catalog);
			doAnswer(
				invocation -> {
					if (publishabilityChecks.incrementAndGet() == 2) {
						headerWriteEntered.countDown();
						awaitLatch(test.releaseParked, "the header write was never released");
					}
					return invocation.callRealMethod();
				}
			).when(flushing).assertPublishable();
			final InFlightFlush flush = startFlush(flushing::flush);
			flush.heldBack().runNext();
			// the last write completes the flush's writes on its own thread, which goes on into the publication
			final Future<?> lastWrite = test.fixtureThreads.submit(flush.heldBack()::runRemaining);
			awaitLatch(headerWriteEntered, "the flush never reached its header write");

			final CountDownLatch terminated = new CountDownLatch(1);
			final Future<?> termination = test.fixtureThreads.submit(
				() -> {
					try {
						catalog.terminate();
					} finally {
						terminated.countDown();
					}
				}
			);
			final boolean terminatedDuringHeaderWrite = terminated.await(NEGATIVE_WAIT_MILLIS, MILLISECONDS);
			test.releaseParked.countDown();
			awaitCompletion(lastWrite);
			awaitCompletion(termination);
			assertTrue(catalog.isTerminated());
			test.snapshotStorage();

			assertFalse(
				terminatedDuringHeaderWrite,
				"the termination must wait for a header write under way rather than close the storage beneath it"
			);
			assertTrue(
				flush.future().isDone() && !flush.future().isCompletedExceptionally(),
				"the flush whose header write the termination waited for must complete"
			);
			final Map<String, Boolean> published = test.readPublishedWrite(List.of());
			assertTrue(
				published.values().stream().allMatch(Boolean::booleanValue),
				() -> "the header write the termination waited for must be published whole, but the published " +
					"state holds: " + published
			);
		}
	}

	@Nested
	@DisplayName("when the flush has written every collection before the termination")
	class FlushFinishedBeforeTermination {

		@Test
		@DisplayName("should publish the write whole")
		void shouldPublishWriteWholeWhenFlushFinishedBeforeCatalogTerminates() throws IOException {
			final CatalogWarmUpTerminationBesideFlushTest test = CatalogWarmUpTerminationBesideFlushTest.this;
			final Catalog catalog = test.catalog();
			final EvitaSessionContract writer = test.evita.createReadWriteSession(TEST_CATALOG);
			writeProductAndCategory(writer);
			final InFlightFlush flush = startFlush(catalog::flush);
			flush.heldBack().runRemaining();
			assertTrue(
				flush.future().isDone() && !flush.future().isCompletedExceptionally(), "the flush must have completed"
			);

			catalog.terminate();
			assertTrue(catalog.isTerminated());
			test.snapshotStorage();

			final Map<String, Boolean> published = test.readPublishedWrite(List.of());
			assertTrue(
				published.values().stream().allMatch(Boolean::booleanValue),
				() -> "the write of a flush that finished must be published whole, but the published state holds: " +
					published
			);
		}
	}

	/**
	 * Stores, in the writer's session, a product referencing the published category and a category of its own.
	 *
	 * @param writer the read-write session the writer keeps open
	 */
	private static void writeProductAndCategory(@Nonnull EvitaSessionContract writer) {
		writer.createNewEntity(Entities.PRODUCT, WRITTEN_PRODUCT_PK)
			.setReference(REFERENCE_CATEGORIES, REFERENCED_CATEGORY_PK)
			.upsertVia(writer);
		writer.createNewEntity(Entities.CATEGORY, WRITTEN_CATEGORY_PK)
			.setAttribute(ATTRIBUTE_LABEL, WRITTEN_CATEGORY_LABEL)
			.upsertVia(writer);
	}

	/**
	 * Starts the flush the writer's session close would start, and executes it on an executor that holds its
	 * collection writes back until the test runs them.
	 *
	 * @param flushStarter calls {@link Catalog#flush()} on the catalog, or on a spy of it
	 * @return the flush, with its held-back writes
	 */
	@Nonnull
	private static InFlightFlush startFlush(@Nonnull Supplier<ProgressingFuture<Void>> flushStarter) {
		final HeldBackExecutor heldBack = new HeldBackExecutor();
		final ProgressingFuture<Void> future = flushStarter.get();
		future.execute(heldBack);
		assertEquals(2, heldBack.size(), "the flush must dispatch one write per collection");
		assertFalse(future.isDone(), "the flush must wait for its collection writes");
		return new InFlightFlush(future, heldBack);
	}

	/**
	 * Returns the entity types whose collection write the flush has not run yet - a collection whose write has run
	 * has a new header, so its version moved.
	 *
	 * @param collections         the collections of the catalog
	 * @param versionsBeforeWrite the version of each of them before any write of the flush ran, in the same order
	 * @param flush               the flush in progress
	 * @return the held-back entity types, for failure messages
	 */
	@Nonnull
	private static List<String> heldBackEntityTypes(
		@Nonnull List<EntityCollection> collections,
		@Nonnull List<Long> versionsBeforeWrite,
		@Nonnull InFlightFlush flush
	) {
		final List<String> heldBack = new ArrayList<>(2);
		for (int i = 0; i < collections.size(); i++) {
			if (collections.get(i).getVersion() == versionsBeforeWrite.get(i)) {
				heldBack.add(collections.get(i).getEntityType());
			}
		}
		assertEquals(flush.heldBack().size(), heldBack.size(), "every held-back write must leave its version");
		return heldBack;
	}

	/**
	 * Returns the collections of the catalog, the product's first.
	 *
	 * @param catalog the catalog
	 * @return the collections
	 */
	@Nonnull
	private static List<EntityCollection> collections(@Nonnull Catalog catalog) {
		return List.of(
			catalog.getCollectionForEntityOrThrowException(Entities.PRODUCT),
			catalog.getCollectionForEntityOrThrowException(Entities.CATEGORY)
		);
	}

	/**
	 * Describes which collection writes the flush had not run when the catalog terminated, for failure messages.
	 *
	 * @param heldBackEntityTypes entity types whose write was held back
	 * @return the description
	 */
	@Nonnull
	private static String describeHeldBackWrites(@Nonnull List<String> heldBackEntityTypes) {
		return "(the flush had not yet written " + heldBackEntityTypes + " when the catalog terminated)";
	}

	/**
	 * Returns the innermost cause of a failure.
	 *
	 * @param failure the failure, or `null`
	 * @return the innermost cause, or `null` when there was no failure
	 */
	@Nullable
	private static Throwable rootCause(@Nullable Throwable failure) {
		Throwable cause = failure;
		while (cause != null && cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause;
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
	 * Waits for a future to complete, whatever its outcome - the tests assert how it completed separately.
	 *
	 * @param task the future to await
	 */
	private static void awaitCompletion(@Nonnull Future<?> task) {
		try {
			task.get(POSITIVE_WAIT_SECONDS, SECONDS);
		} catch (ExecutionException ignored) {
			// the outcome is asserted by the caller where it matters
		} catch (TimeoutException ex) {
			fail("a future did not complete in " + POSITIVE_WAIT_SECONDS + " s", ex);
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
	 * Returns a spy of the catalog whose termination parks once it has begun, at the point where it asks whether it
	 * may publish, until {@link #releaseParked} is counted down. The spy shares every mutable holder of the catalog,
	 * so a flush of the catalog itself meets the termination of the spy.
	 *
	 * @param catalog the catalog
	 * @return the spy to terminate
	 */
	@Nonnull
	private Catalog parkingTermination(@Nonnull Catalog catalog) {
		final AtomicInteger decisions = new AtomicInteger();
		final Catalog terminating = spy(catalog);
		doAnswer(
			invocation -> {
				final Object publishable = invocation.callRealMethod();
				if (decisions.incrementAndGet() == 1) {
					this.terminationParked.countDown();
					awaitLatch(this.releaseParked, "the termination was never resumed");
				}
				return publishable;
			}
		).when(terminating).isPublishable();
		return terminating;
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
	 * Reads every observable trace of the writer's write from the snapshot: the stored parts of both entities on both
	 * sides of the reference, and what the indexes of both collections answer about them. A publication that is whole
	 * answers `true` everywhere, one that published nothing of the write answers `false` everywhere. A snapshot whose
	 * catalog does not load fails the test right here, naming why.
	 *
	 * @param heldBackEntityTypes entity types whose write the flush had not run when the catalog terminated, for the
	 *                            failure message
	 * @return the observations, named so that a failure says which side is missing
	 */
	@Nonnull
	private Map<String, Boolean> readPublishedWrite(@Nonnull List<String> heldBackEntityTypes) {
		try (final Evita snapshotEvita = new Evita(newTestEvitaConfigurationBuilder(this.snapshotPaths).build())) {
			try {
				snapshotEvita.waitUntilFullyInitialized();
			} catch (CompletionException ex) {
				return fail(
					"the published state must load, but loading the catalog failed: " + rootCause(ex) + " " +
						describeHeldBackWrites(heldBackEntityTypes),
					ex
				);
			}
			return snapshotEvita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final Map<String, Boolean> observations = new LinkedHashMap<>(8);
					final EntityContentRequire references = referenceContentAll();
					final Optional<SealedEntity> product = session.getEntity(
						Entities.PRODUCT, WRITTEN_PRODUCT_PK, references
					);
					final Optional<SealedEntity> category = session.getEntity(
						Entities.CATEGORY, REFERENCED_CATEGORY_PK, references
					);
					observations.put(
						"product stores its reference",
						product.flatMap(it -> it.getReference(REFERENCE_CATEGORIES, REFERENCED_CATEGORY_PK)).isPresent()
					);
					observations.put(
						"category stores the reflection",
						category.flatMap(it -> it.getReference(REFERENCE_PRODUCTS, WRITTEN_PRODUCT_PK)).isPresent()
					);
					observations.put(
						"product index finds the reference",
						!session.queryListOfEntityReferences(
							Query.query(
								collection(Entities.PRODUCT),
								filterBy(
									entityPrimaryKeyInSet(WRITTEN_PRODUCT_PK),
									referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(REFERENCED_CATEGORY_PK))
								)
							)
						).isEmpty()
					);
					observations.put(
						"category index finds the reflection",
						!session.queryListOfEntityReferences(
							Query.query(
								collection(Entities.CATEGORY),
								filterBy(
									entityPrimaryKeyInSet(REFERENCED_CATEGORY_PK),
									referenceHaving(REFERENCE_PRODUCTS, entityPrimaryKeyInSet(WRITTEN_PRODUCT_PK))
								)
							)
						).isEmpty()
					);
					observations.put(
						"written category is stored",
						session.getEntity(Entities.CATEGORY, WRITTEN_CATEGORY_PK).isPresent()
					);
					observations.put(
						"category index finds the written category",
						!session.queryListOfEntityReferences(
							Query.query(
								collection(Entities.CATEGORY),
								filterBy(attributeEquals(ATTRIBUTE_LABEL, WRITTEN_CATEGORY_LABEL))
							)
						).isEmpty()
					);
					return observations;
				}
			);
		}
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
	 * A flush started and executed on a {@link HeldBackExecutor}.
	 *
	 * @param future   the flush future
	 * @param heldBack the executor holding the flush's collection writes
	 */
	private record InFlightFlush(@Nonnull ProgressingFuture<Void> future, @Nonnull HeldBackExecutor heldBack) {
	}

	/**
	 * An executor that runs nothing until it is told to, and then runs the dispatched tasks on the calling thread.
	 * The flush dispatches one write per collection, and holding them here decides which of them has run when the
	 * catalog terminates - each write is parked before it starts, never inside the storage layer.
	 */
	private static final class HeldBackExecutor implements Executor {
		/** Tasks dispatched and not yet run, in dispatch order; the flush dispatches on the thread that executes it. */
		private final List<Runnable> tasks = Collections.synchronizedList(new ArrayList<>(4));

		@Override
		public void execute(@Nonnull Runnable command) {
			this.tasks.add(command);
		}

		/**
		 * Returns how many dispatched tasks have not run yet.
		 *
		 * @return the number of held tasks
		 */
		int size() {
			return this.tasks.size();
		}

		/**
		 * Runs the task dispatched first among those not run yet, on the calling thread.
		 */
		void runNext() {
			this.tasks.remove(0).run();
		}

		/**
		 * Runs every task not run yet, in dispatch order, on the calling thread.
		 */
		void runRemaining() {
			while (size() > 0) {
				runNext();
			}
		}
	}

}
