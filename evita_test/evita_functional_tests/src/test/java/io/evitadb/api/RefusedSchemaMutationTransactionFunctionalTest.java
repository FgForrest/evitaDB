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

package io.evitadb.api;

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.TrafficRecordingOptions;
import io.evitadb.api.exception.IndexNotReady;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.RollbackException;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.AttributeFilterAccelerator;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ModifyAttributeSchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeFilterAccelerators;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaAcceleratedMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaNameMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.entity.ModifyEntitySchemaDescriptionMutation;
import io.evitadb.api.requestResponse.trafficRecording.MutationContainer;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecording;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest.TrafficRecordingType;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingContent;
import io.evitadb.core.Evita;
import io.evitadb.core.session.EvitaInternalSessionContract;
import io.evitadb.dataType.Scope;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static io.evitadb.api.TransactionContract.CommitBehavior.WAIT_FOR_CHANGES_VISIBLE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a caught schema refusal after registration discards the whole transaction before WAL publication.
 * Real engines are reopened over the same storage to exercise recovery as well as the next committed write. One case
 * also checks that the traffic recorder keeps a single record of a refused mutation.
 */
@DisplayName("Refused schema mutations discard their transaction")
@Tag(ENGINE)
@Tag(SCHEMA)
class RefusedSchemaMutationTransactionFunctionalTest implements EvitaTestSupport {

	/** Storage directories retained across engine restarts within a test. */
	@Nullable private TestPaths paths;
	/** Embedded engine serving the current test. */
	@Nullable private Evita evita;

	/** Creates a catalog with an existing collection and a managed reference to another collection. */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("RefusedSchemaMutationTransaction");
		this.evita = new Evita(configuration());
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("brand").withoutGeneratedPrimaryKey().updateVia(session);
			session.defineEntitySchema("product").withoutGeneratedPrimaryKey()
				.withAttribute("name", String.class, attribute -> attribute.filterable().nullable())
				.withReferenceToEntity("brand", "brand", Cardinality.ZERO_OR_MORE,
					reference -> reference.indexedForFilteringAndPartitioning())
				.updateVia(session);
		});
	}

	/** Releases the embedded engine and its test directories. */
	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		if (this.paths != null) {
			cleanupTestPaths(this.paths);
		}
	}

	/** Caught collection and deferred validation failures must prevent every write in their transaction. */
	@ParameterizedTest(name = "histogram validation: {0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("Caught schema refusals roll back and leave storage reloadable")
	void shouldRollBackCaughtSchemaRefusal(boolean histogram) {
		this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);
		final AtomicBoolean rollbackOnly = new AtomicBoolean();
		final AtomicReference<InvalidSchemaMutationException> refusal = new AtomicReference<>();
		final RuntimeException closeException = assertThrows(RuntimeException.class, () ->
			this.evita.updateCatalog(TEST_CATALOG, session -> {
				session.upsertEntity(session.createNewEntity("product", 1).setAttribute("name", "before"));
				refusal.set(refuseMutation(session, histogram));
				rollbackOnly.set(session.isRollbackOnly());
				continueWriting(session, histogram);
			}, WAIT_FOR_CHANGES_VISIBLE)
		);
		assertAll(
			() -> assertTrue(rollbackOnly.get(), "A registered schema refusal must mark the transaction rollback-only"),
			() -> assertInstanceOf(RollbackException.class, closeException),
			() -> assertEquals("Transaction changes have been rolled back due to a previous exception.",
				closeException.getMessage()),
			() -> assertSame(refusal.get(), closeException.getCause())
		);
		assertStoredState(null, false);
		describeBrandAndRestart("before restart");
		assertStoredState("before restart", false);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.upsertEntity(session.createNewEntity("product", 2).setAttribute("name", "next"));
			session.getEntitySchemaOrThrowException("brand").openForWrite()
				.withDescription("next session").updateVia(session);
		}, WAIT_FOR_CHANGES_VISIBLE);
		restart();
		assertStoredState("next session", false);
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertTrue(session.getEntity("product", 2).isPresent());
		});
	}

	/** Warm-up has no transaction to poison and retains its existing catch-and-continue behavior. */
	@ParameterizedTest(name = "histogram validation: {0}")
	@ValueSource(booleans = {false, true})
	@DisplayName("Caught warm-up schema refusals still allow later writes")
	void shouldContinueAfterWarmUpSchemaRefusal(boolean histogram) {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			refuseMutation(session, histogram);
			assertFalse(session.isRollbackOnly());
			continueWriting(session, histogram);
		});
		restart();
		assertStoredState("continued", histogram);
	}

	/** Preflight rejects an accelerator on a populated collection before any mutation is registered. */
	@Test
	@DisplayName("A preflight refusal leaves the transaction usable")
	void shouldContinueAfterPreflightRefusal() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.upsertEntity(session.createNewEntity("product", 1).setAttribute("name", "existing"));
			session.goLiveAndClose();
		});
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			final InvalidSchemaMutationException exception = assertThrows(InvalidSchemaMutationException.class, () ->
				session.updateEntitySchema(new ModifyEntitySchemaMutation("product",
					new SetAttributeSchemaAcceleratedMutation("name",
						new ScopedAttributeFilterAccelerators(
							Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH))))
			);
			assertTrue(exception.getMessage().contains("already contains entities"));
			assertFalse(session.isRollbackOnly());
			continueWriting(session, false);
		}, WAIT_FOR_CHANGES_VISIBLE);
		restart();
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertEquals("continued", session.getEntitySchemaOrThrowException("brand").getDescription());
			assertTrue(session.getEntitySchemaOrThrowException("product")
				.getAttribute("name").orElseThrow().getAccelerators().isEmpty());
			assertTrue(session.getEntity("product", 3).isPresent());
		});
	}

	/** A recursive preflight runs after its catalog-level parent mutation has already been registered. */
	@Test
	@DisplayName("A refused catalog cascade rolls back its registered parent")
	void shouldRollBackRegisteredParentWhenCascadePreflightRefuses() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getCatalogSchema().openForWrite()
				.withAttribute("shared", String.class, attribute -> attribute.filterable().nullable())
				.updateVia(session);
			session.getEntitySchemaOrThrowException("product").openForWrite()
				.withGlobalAttribute("shared").updateVia(session);
			session.upsertEntity(session.createNewEntity("product", 1).setAttribute("shared", "existing"));
			session.goLiveAndClose();
		});
		final AtomicBoolean rollbackOnly = new AtomicBoolean();
		final RuntimeException closeException = assertThrows(RuntimeException.class, () ->
			this.evita.updateCatalog(TEST_CATALOG, session -> {
				final InvalidSchemaMutationException exception = assertThrows(
					InvalidSchemaMutationException.class, () -> session.updateCatalogSchema(
						new SetAttributeSchemaAcceleratedMutation("shared",
							new ScopedAttributeFilterAccelerators(
								Scope.LIVE, AttributeFilterAccelerator.SUBSTRING_SEARCH)))
				);
				assertTrue(exception.getMessage().contains("already contains entities"));
				rollbackOnly.set(session.isRollbackOnly());
				continueWriting(session, false);
			}, WAIT_FOR_CHANGES_VISIBLE)
		);
		assertAll(
			() -> assertTrue(rollbackOnly.get()),
			() -> assertInstanceOf(RollbackException.class, closeException)
		);
		restart();
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertNull(session.getEntitySchemaOrThrowException("brand").getDescription());
			assertTrue(session.getCatalogSchema().getAttribute("shared").orElseThrow().getAccelerators().isEmpty());
			assertFalse(session.getEntity("product", 3).isPresent());
		});
		describeBrandAndRestart("next session");
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertEquals("next session", session.getEntitySchemaOrThrowException("brand").getDescription());
		});
	}

	/** A refusal late in a batch cannot undo earlier collection map and schema changes in the same session. */
	@Test
	@DisplayName("A refused batch discards earlier collection creation removal rename and modification")
	void shouldDiscardPartialSchemaBatch() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema("obsolete").updateVia(session);
			session.defineEntitySchema("source").updateVia(session);
			session.goLiveAndClose();
		});
		final AtomicBoolean rollbackOnly = new AtomicBoolean();
		final RuntimeException closeException = assertThrows(RuntimeException.class, () ->
			this.evita.updateCatalog(TEST_CATALOG, session -> {
				assertThrows(InvalidSchemaMutationException.class, () -> session.updateCatalogSchema(
					new CreateEntitySchemaMutation("temporary"),
					new RemoveEntitySchemaMutation("obsolete"),
					new ModifyEntitySchemaNameMutation("source", "renamed", false),
					new ModifyEntitySchemaMutation("brand", new ModifyEntitySchemaDescriptionMutation("partial")),
					new ModifyEntitySchemaMutation("product",
						new ModifyAttributeSchemaNameMutation("missing", "renamed"))
				));
				assertEquals(Set.of("product", "brand", "temporary", "renamed"), session.getAllEntityTypes());
				assertEquals("partial", session.getEntitySchemaOrThrowException("brand").getDescription());
				rollbackOnly.set(session.isRollbackOnly());
				continueWriting(session, false);
			}, WAIT_FOR_CHANGES_VISIBLE)
		);
		assertAll(
			() -> assertTrue(rollbackOnly.get()),
			() -> assertInstanceOf(RollbackException.class, closeException)
		);
		restart();
		assertStoredState(null, false);
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertEquals(Set.of("product", "brand", "obsolete", "source"), session.getAllEntityTypes());
		});
		describeBrandAndRestart("next session");
		assertStoredState("next session", false);
	}

	/** A refusal raised after the last mutation of a batch was applied must not record that mutation a second time. */
	@Test
	@DisplayName("A deferred validation refusal records its schema mutation once")
	void shouldRecordMutationOnceWhenDeferredValidationRefuses() {
		this.evita.updateCatalog(TEST_CATALOG, EvitaSessionContract::goLiveAndClose);
		this.evita.close();
		this.evita = new Evita(trafficRecordingConfiguration());
		this.evita.waitUntilFullyInitialized();
		final AtomicReference<UUID> sessionId = new AtomicReference<>();
		assertThrows(RollbackException.class, () ->
			this.evita.updateCatalog(TEST_CATALOG, session -> {
				sessionId.set(session.getId());
				refuseMutation(session, true);
			}, WAIT_FOR_CHANGES_VISIBLE)
		);

		final List<MutationContainer> recordedMutations = await()
			.atMost(30, TimeUnit.SECONDS)
			.pollInterval(50, TimeUnit.MILLISECONDS)
			.ignoreExceptionsInstanceOf(IndexNotReady.class)
			.until(() -> readRecordedMutations(sessionId.get()), mutations -> !mutations.isEmpty());

		assertEquals(1, recordedMutations.size());
		assertEquals("product",
			assertInstanceOf(ModifyEntitySchemaMutation.class, recordedMutations.get(0).mutation()).getName());
	}

	/** Submits a raw missing-attribute mutation or a cross-entity expression rejected in the deferred rebuild. */
	@Nonnull
	private static InvalidSchemaMutationException refuseMutation(
		@Nonnull EvitaSessionContract session, boolean histogram
	) {
		final InvalidSchemaMutationException exception = assertThrows(InvalidSchemaMutationException.class, () -> {
			if (histogram) {
				session.getEntitySchemaOrThrowException("product").openForWrite()
					.withReferenceToEntity("brand", "brand", Cardinality.ZERO_OR_MORE, reference -> reference.bucketed(
						"value", ExpressionFactory.parse("$reference.referencedEntity?.attributes['missing']")))
					.updateVia(session);
			} else {
				session.updateEntitySchema(new ModifyEntitySchemaMutation("product",
					new ModifyAttributeSchemaNameMutation("missing", "renamed")));
			}
		});
		assertTrue(exception.getMessage().contains(histogram ? "does not exist on entity type" : "is not defined"));
		assertTrue(Arrays.stream(exception.getStackTrace()).anyMatch(frame ->
			frame.getMethodName().equals(histogram ? "drainTriggerRebuildFrame" : "updateSchema") &&
			frame.getClassName().equals(histogram ? "io.evitadb.core.catalog.Catalog" :
				"io.evitadb.core.collection.EntityCollection")));
		return exception;
	}

	/** Writes after the caught refusal, completing the missing expression dependency when testing a histogram. */
	private static void continueWriting(@Nonnull EvitaSessionContract session, boolean histogram) {
		if (histogram) {
			session.getEntitySchemaOrThrowException("brand").openForWrite()
				.withAttribute("missing", Integer.class, attribute -> attribute.filterable().nullable())
				.updateVia(session);
			session.getEntitySchemaOrThrowException("product").openForWrite()
				.withDescription("rebuild after dependency supplied").updateVia(session);
		}
		session.getEntitySchemaOrThrowException("brand").openForWrite().withDescription("continued").updateVia(session);
		session.upsertEntity(session.createNewEntity("product", 3).setAttribute("name", "after"));
	}

	/** Checks committed schemas and the writes made before and after the refused call. */
	private void assertStoredState(@Nullable String description, boolean histogram) {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertEquals(description, session.getEntitySchemaOrThrowException("brand").getDescription());
			assertEquals(histogram, session.getEntitySchemaOrThrowException("brand")
				.getAttribute("missing").isPresent());
			assertEquals(histogram, session.getEntitySchemaOrThrowException("product")
				.getReferenceOrThrowException("brand").getAllHistogramIndexDefinitions().containsKey(Scope.LIVE));
			assertFalse(session.getEntity("product", 1).isPresent());
			assertEquals("continued".equals(description), session.getEntity("product", 3).isPresent());
		});
	}

	/** Commits a later brand description and reopens the engine, proving the catalog accepts and reloads new writes. */
	private void describeBrandAndRestart(@Nonnull String description) {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getEntitySchemaOrThrowException("brand").openForWrite()
				.withDescription(description).updateVia(session);
		}, WAIT_FOR_CHANGES_VISIBLE);
		restart();
	}

	/** Reopens the engine over the same directories and joins catalog initialization. */
	private void restart() {
		this.evita.close();
		this.evita = new Evita(configuration());
		this.evita.waitUntilFullyInitialized();
	}

	/** Builds the same storage configuration for initial startup and every restart. */
	@Nonnull
	private EvitaConfiguration configuration() {
		return newTestEvitaConfigurationBuilder(this.paths).build();
	}

	/** Adds traffic recording, which a live catalog starts on load, flushing closed sessions without delay. */
	@Nonnull
	private EvitaConfiguration trafficRecordingConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.server(
				ServerOptions.builder()
					.trafficRecording(
						TrafficRecordingOptions.builder()
							.enabled(true)
							.trafficFlushIntervalInMilliseconds(0L)
							.build()
					)
					.build()
			)
			.build();
	}

	/** Reads a session's recorded mutations: empty until it is flushed to disk, `IndexNotReady` until it is indexed. */
	@Nonnull
	private List<MutationContainer> readRecordedMutations(@Nonnull UUID sessionId) {
		return this.evita.queryCatalog(TEST_CATALOG, session -> {
			try (
				final Stream<TrafficRecording> recordings = ((EvitaInternalSessionContract) session).getRecordings(
					TrafficRecordingCaptureRequest.builder()
						.content(TrafficRecordingContent.BODY)
						.sessionId(sessionId)
						.type(TrafficRecordingType.MUTATION)
						.build()
				)
			) {
				return recordings.map(MutationContainer.class::cast).toList();
			}
		});
	}

}
