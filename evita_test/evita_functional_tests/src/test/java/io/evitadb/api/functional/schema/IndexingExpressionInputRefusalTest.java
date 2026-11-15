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

package io.evitadb.api.functional.schema;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.functional.indexing.IndexingTestSupport;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.schema.dto.HistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReflectedReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedBucketedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedFacetedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedHistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaBucketedMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaFacetedMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.expression.trigger.IndexingExpressionInputCells.Cell;
import io.evitadb.core.expression.trigger.IndexingExpressionInputValidator.IndexingExpressionKind;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.ExceptionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.evitadb.core.expression.trigger.IndexingExpressionInputCells.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FACET;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Verifies through the public schema API that an indexing expression reading data evitaDB cannot keep its index
 * current with is refused when it is defined, before anything of the batch is applied: the refusal comes from
 * `Catalog#verifyEntitySchemaMutationsApplicable`, never from the deferred trigger rebuild, names the expression,
 * the refused read and the reason, and leaves no trace - a restart loads the catalog with the accepted definitions
 * only. A refusal before anything is exchanged costs a warming-up catalog nothing. The expressions the preflight admits
 * and the trigger build at the end of the schema change refuses are refused after the exchange, which deactivates a
 * warming-up catalog - `TriggerBuildRefusalInWarmUp` covers them.
 *
 * The cells come from the table of `IndexingExpressionInputCells`; each is defined in one expression slot, on an owner
 * type of its own.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Indexing expressions reading data evitaDB cannot track are refused at definition")
@Tag(ENGINE)
@Tag(SCHEMA)
@Tag(HISTOGRAM)
@Tag(FACET)
class IndexingExpressionInputRefusalTest implements EvitaTestSupport {

	private static final String BRAND = "brand";
	private static final String PARAMETER_GROUP = "parameterGroup";
	private static final String REFERENCE_NAME = "brand";
	private static final String HISTOGRAM_NAME = "shares";
	private static final String DEFAULT_VALUE = "$reference.attributes['share']";
	private static final String EVALUATED_OWNER = "evaluatedProduct";

	/**
	 * Histogram conditions the validator admits and the trigger build refuses: the primary key, the scope or the bare
	 * entity one hop away, beside an attribute read through it, a parenthesized prefix of a read through it, and a
	 * step or a predicate applied to the result of `*?` or `??`. They stay off the shared catalog of
	 * `shouldRefuseUntrackedReadsAtDefinitionAndLeaveNoTrace`, because the barrier their refusal raises in a
	 * warming-up catalog would deactivate it for every later cell; `TriggerBuildRefusalInWarmUp` defines them.
	 */
	private static final Set<String> REFUSED_BY_TRIGGER_BUILD = Set.of(
		"A-E15h", "A-N9", "A-N9k", "A-par-hop-prefix", "A-spread-hop-fallback", "A-spread-constant-fallback",
		"A-coalesce-element-index"
	);
	/**
	 * The histogram value cell the validator admits and the trigger build refuses: a numeric default its attribute
	 * type cannot hold. `TriggerBuildRefusalInWarmUp` defines it.
	 */
	private static final Set<String> VALUES_REFUSED_BY_TRIGGER_BUILD = Set.of("V-A7");
	/**
	 * The facet condition cells the validator admits and the trigger build refuses: arithmetic on an attribute of the
	 * referenced entity compared with a number, and a step or a predicate applied to the result of `*?` or `??`.
	 * `TriggerBuildRefusalInWarmUp` defines them.
	 */
	private static final Set<String> FACETS_REFUSED_BY_TRIGGER_BUILD = Set.of(
		"FA-11", "FA-coalesce-set-predicate", "FA-spread-translated", "FA-spread-predicate"
	);
	/**
	 * The refused cells defined through the engine: one or more of each refusal reason, each with its own owner type.
	 */
	private static final Set<String> REFUSALS_DEFINED = Set.of(
		"R-E4", "R-E7", "R-E10", "R-E13s", "R-E16", "R-E17", "R-E18", "R-E19", "R-E21", "R-R3", "R-R6", "R-R7",
		"R-R8", "R-N5", "R-N6", "R-C1", "R-C3", "R-X1", "R-X2", "R-coalesce-index", "R-coalesce-property",
		"R-spread-coalesce-index", "R-par-translated", "R-par-hop", "R-par-name", "R-coalesce-hop-pk", "FR-1",
		"FR-2", "FR-6", "FR-8", "FR-10", "FR-par-translated", "FR-coalesce-entity-version", "FR-spread-coalesce-element"
	);
	/**
	 * The histogram value cells defined through the engine. The cell the trigger build refuses is defined by
	 * `TriggerBuildRefusalInWarmUp` instead.
	 */
	private static final Set<String> VALUES_DEFINED = Set.of(
		"V-A1", "V-A2", "V-A3", "V-A4", "V-A5", "V-A6", "V-A8", "V-R1", "V-R3", "V-R8"
	);
	/**
	 * The accepted facet cells defined through the engine; the facet rules are exercised against entities by the
	 * conditional facet indexing tests. The cells the trigger build refuses are defined by
	 * `TriggerBuildRefusalInWarmUp` instead.
	 */
	private static final Set<String> FACET_ACCEPTANCES_DEFINED = Set.of(
		"FA-1", "FA-3", "FA-par-null-locales", "FA-spread-coalesce-own"
	);
	/**
	 * The cells defined through the engine: every accepted histogram condition, in both histogram slots, and the
	 * refused, value and facet cells selected above.
	 */
	private static final List<Cell> CELLS = definedCells();

	/**
	 * Defines every cell on an owner type of its own, in a catalog in the given state, and checks each verdict; then
	 * restarts the engine and checks that the catalog loads with exactly the accepted definitions.
	 *
	 * @param state the state of the catalog the cells are defined in
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("each cell of the table is accepted or refused before anything is applied, and leaves no trace")
	void shouldRefuseUntrackedReadsAtDefinitionAndLeaveNoTrace(@Nonnull CatalogState state) {
		final TestPaths paths = createTestPaths("IndexingExpressionInputRefusal_" + state);
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths)
			.server(
				// every cell closes a session of its own, and the work a closed session leaves is queued in the
				// transaction pool faster than it drains when the cells are defined back to back
				ServerOptions.builder()
					.transactionThreadPool(ThreadPoolOptions.transactionThreadPoolBuilder().queueSize(1_000).build())
					.build()
			)
			.build();
		final List<Executable> checks = new ArrayList<>(CELLS.size() * 2);
		try {
			try (Evita evita = new Evita(configuration)) {
				defineCatalog(evita, state);
				for (final Cell cell : CELLS) {
					final RuntimeException failure = defineCatching(evita, entityTypeOf(cell), cell);
					recordVerdict(cell, failure, checks);
				}
			}
			try (Evita reopened = new Evita(configuration)) {
				final RuntimeException loadFailure = catching(reopened::waitUntilFullyInitialized);
				final CatalogState reloaded = reopened.getCatalogState(TEST_CATALOG).orElse(null);
				checks.add(() -> assertEquals(
					state, reloaded,
					"the catalog must load in its state after a restart" +
						(loadFailure == null ? "" : ": " + loadFailure)
				));
				if (reloaded == state) {
					reopened.queryCatalog(TEST_CATALOG, session -> {
						for (final Cell cell : CELLS) {
							recordStoredDefinition(session, cell, checks);
						}
					});
				}
			}
		} finally {
			cleanupTestPaths(paths);
		}
		assertAll(checks);
	}

	/**
	 * The other three mutations that put an expression into a schema, each submitted as a raw mutation: a facet
	 * condition set on an existing reference, a histogram set on an existing reference, and a reflected reference
	 * created with a facet condition.
	 */
	@Nested
	@DisplayName("each mutation carrying an expression")
	class PayloadRoutes {

		/**
		 * Proves that each of the three raw mutations is refused by the payload preflight, and that the reference
		 * keeps its accepted facet condition, gets no histogram condition and no reflected reference is created.
		 *
		 * @param state the state of the catalog the mutations are submitted to
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("is refused before anything is applied")
		void shouldRefuseEveryMutationCarryingAnExpression(@Nonnull CatalogState state) {
			final TestPaths paths = createTestPaths("IndexingExpressionInputRefusalRoutes_" + state);
			final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
			final Cell facet = refused("M-facet", "$entity.version > 1", "$entity.version", ENGINE_MAINTAINED)
				.withKind(IndexingExpressionKind.FACETED_PARTIALLY);
			final Cell bucket = refused("M-bucket", "$entity.version >= 3", "$entity.version", ENGINE_MAINTAINED);
			final Cell reflected = refused("M-reflected", "$entity.version > 1", "$entity.version", ENGINE_MAINTAINED)
				.withKind(IndexingExpressionKind.FACETED_PARTIALLY);
			final Cell plain = accepted("M-plain", "$entity.attributes['status'] == 'A'")
				.withKind(IndexingExpressionKind.FACETED_PARTIALLY);
			final List<Executable> checks = new ArrayList<>(8);
			try (Evita evita = new Evita(configuration)) {
				defineCatalog(evita, state);
				final String owner = entityTypeOf(plain);
				evita.updateCatalog(TEST_CATALOG, session -> {
					configureOwner(session.defineEntitySchema(owner), plain).updateVia(session);
				});
				final RuntimeException facetFailure = catching(() -> evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.updateEntitySchema(new ModifyEntitySchemaMutation(
							owner,
							new SetReferenceSchemaFacetedMutation(
								REFERENCE_NAME, new Scope[]{Scope.LIVE},
								new ScopedFacetedPartially[]{
									new ScopedFacetedPartially(Scope.LIVE, ExpressionFactory.parse(facet.expression()))
								}
							)
						));
					}
				));
				checks.add(() -> assertRefusedByPreflight(facet, owner, facetFailure));
				final RuntimeException bucketFailure = catching(() -> evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.updateEntitySchema(new ModifyEntitySchemaMutation(
							owner,
							new SetReferenceSchemaBucketedMutation(
								REFERENCE_NAME,
								new ScopedHistogramIndexDefinition[]{
									new ScopedHistogramIndexDefinition(
										Scope.LIVE, HISTOGRAM_NAME, ExpressionFactory.parse(DEFAULT_VALUE), null
									)
								},
								new ScopedBucketedPartially[]{
									new ScopedBucketedPartially(
										Scope.LIVE, ExpressionFactory.parse(bucket.expression())
									)
								}
							)
						));
					}
				));
				checks.add(() -> assertRefusedByPreflight(bucket, owner, bucketFailure));
				final RuntimeException reflectedFailure = catching(() -> evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.updateEntitySchema(new ModifyEntitySchemaMutation(
							BRAND,
							new CreateReflectedReferenceSchemaMutation(
								"owners", null, null, Cardinality.ZERO_OR_MORE, owner, REFERENCE_NAME,
								null, null, new Scope[]{Scope.LIVE},
								new ScopedFacetedPartially[]{
									new ScopedFacetedPartially(
										Scope.LIVE, ExpressionFactory.parse(reflected.expression())
									)
								},
								null, null, AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT, new String[0]
							)
						));
					}
				));
				checks.add(() -> assertRefusedByPreflight(reflected, BRAND, reflectedFailure));
				evita.queryCatalog(TEST_CATALOG, session -> {
					final ReferenceSchemaContract reference = session.getEntitySchemaOrThrowException(owner)
						.getReference(REFERENCE_NAME)
						.orElseThrow();
					checks.add(() -> assertEquals(
						plain.expression(), storedExpression(reference, IndexingExpressionKind.FACETED_PARTIALLY),
						"the refused facet condition must leave the accepted one in place"
					));
					checks.add(() -> assertTrue(
						reference.getBucketedPartiallyInScopes().isEmpty(),
						"the refused histogram condition must not be defined"
					));
					final boolean reflectedCreated =
						session.getEntitySchemaOrThrowException(BRAND).getReference("owners").isPresent();
					checks.add(() -> assertFalse(
						reflectedCreated, "the refused reflected reference must not be created"
					));
				});
			} finally {
				cleanupTestPaths(paths);
			}
			assertAll(checks);
		}
	}

	/**
	 * A batch that creates an owner type and defines its reference in one call: the collection does not exist yet
	 * when the batch is checked, and the refusal must still come before anything is applied.
	 */
	@Nested
	@DisplayName("a batch creating the collection")
	class OneBatch {

		/**
		 * Proves that a refused expression in a batch that also creates its owner type is refused by the payload
		 * preflight, and that the batch creates no collection.
		 *
		 * @param state the state of the catalog the batch is submitted to
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
		@DisplayName("refuses a read of a collection created in the same batch, and creates nothing")
		void shouldRefuseReadOfCollectionCreatedInTheSameBatch(@Nonnull CatalogState state) {
			final TestPaths paths = createTestPaths("IndexingExpressionInputRefusalBatch_" + state);
			final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
			final Cell cell = refused("B-1", "$entity.version >= 3", "$entity.version", ENGINE_MAINTAINED);
			try (Evita evita = new Evita(configuration)) {
				defineCatalog(evita, state);
				final RuntimeException failure = catching(() -> evita.updateCatalog(
					TEST_CATALOG,
					session -> {
						session.getCatalogSchema().openForWrite()
							.withEntitySchema("batchProduct", builder -> configureOwner(builder, cell))
							.updateVia(session);
					}
				));
				assertRefusedByPreflight(cell, "batchProduct", failure);
				assertFalse(
					evita.queryCatalog(TEST_CATALOG, session -> {
						return session.getEntitySchema("batchProduct");
					}).isPresent(),
					"the refused batch must not create the collection"
				);
			} finally {
				cleanupTestPaths(paths);
			}
		}
	}

	/**
	 * A read refused by the payload preflight is refused before any exchange, costs no deactivation, and a session
	 * closed without any repair publishes nothing of it; a refusal after the exchange raises the barrier
	 * (`TriggerBuildRefusalInWarmUp`).
	 */
	@Nested
	@DisplayName("a warming-up catalog")
	class WarmUpPublication {

		/**
		 * Proves that a definition refused in a warming-up catalog is refused by the payload preflight, and that the
		 * catalog loads after a restart without the refused reference.
		 */
		@Test
		@DisplayName("publishes nothing of a refused definition and loads after a restart without a repair")
		void shouldPublishNothingOfRefusedDefinitionInWarmUp() {
			final TestPaths paths = createTestPaths("IndexingExpressionInputRefusalWarmUp");
			final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
			final Cell cell = refused("F1-1", "$entity.version >= 3", "$entity.version", ENGINE_MAINTAINED);
			try {
				try (Evita evita = new Evita(configuration)) {
					defineCatalog(evita, CatalogState.WARMING_UP);
					final RuntimeException failure = defineCatching(evita, entityTypeOf(cell), cell);
					assertRefusedByPreflight(cell, entityTypeOf(cell), failure);
				}
				try (Evita reopened = new Evita(configuration)) {
					reopened.waitUntilFullyInitialized();
					assertEquals(
						CatalogState.WARMING_UP, reopened.getCatalogState(TEST_CATALOG).orElse(null),
						"the catalog must load: the refused definition must not have been published"
					);
					final Optional<ReferenceSchemaContract> reference = reopened.queryCatalog(
						TEST_CATALOG,
						session -> {
							return session.getEntitySchema(entityTypeOf(cell))
								.flatMap(schema -> schema.getReference(REFERENCE_NAME));
						}
					);
					assertFalse(reference.isPresent(), "the refused reference must not be stored: " + reference);
				}
			} finally {
				cleanupTestPaths(paths);
			}
		}
	}

	/**
	 * Expressions the payload preflight admits and the trigger build at the end of the schema change refuses. In a
	 * warming-up catalog the schema change is already applied when the build refuses, so the refusal raises the
	 * unpublishable barrier: nothing further is published, the catalog is deactivated, and a restart loads the last
	 * state it published. Each cell is defined in a catalog of its own, because the barrier deactivates it.
	 */
	@Nested
	@DisplayName("a definition the trigger build refuses in a warming-up catalog")
	class TriggerBuildRefusalInWarmUp {

		/**
		 * Proves that the definition is refused by the trigger build and not by the payload preflight, that the
		 * refusal deactivates the catalog, and that the restarted catalog loads without the refused reference while it
		 * keeps the entity, published by a session of its own, and the owner type, published by the inline flush of
		 * the create that precedes the refused definition in the same session.
		 *
		 * @param cell a cell the payload preflight admits and the trigger build refuses
		 */
		@ParameterizedTest(name = "{0}")
		@MethodSource("io.evitadb.api.functional.schema.IndexingExpressionInputRefusalTest#triggerBuildRefusedCells")
		@DisplayName("deactivates the catalog, and a restart loads the last published state without the reference")
		void shouldDeactivateAndLoadLastPublishedStateWhenTriggerBuildRefuses(@Nonnull Cell cell) {
			final String owner = entityTypeOf(cell);
			final TestPaths paths = createTestPaths("IndexingExpressionTriggerBuildRefusal_" + owner);
			final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
			final List<Executable> checks = new ArrayList<>(8);
			try {
				try (Evita evita = new Evita(configuration)) {
					defineCatalog(evita, CatalogState.WARMING_UP);
					// published by the close of its own session, before the refusal
					evita.updateCatalog(TEST_CATALOG, session -> {
						session.createNewEntity(BRAND, 1).setAttribute("status", "A").upsertVia(session);
					});
					final RuntimeException failure;
					final Throwable deactivationFailure;
					try (
						final CatalogUnpublishableBarrierAssertions.SettledStateLatch deactivation =
							CatalogUnpublishableBarrierAssertions.subscribeToSettledState(
								evita, TEST_CATALOG, CatalogState.INACTIVE
							)
					) {
						failure = defineCatching(evita, owner, cell);
						// only a refusal of the trigger build deactivates; an accepted definition or a refusal of the
						// payload preflight fails the verdict check below without waiting out the budget
						deactivationFailure = isRefusedByTriggerBuild(failure) ? failureOf(deactivation::await) : null;
					}
					checks.add(() -> assertRefusedByTriggerBuild(cell, failure));
					checks.add(() -> assertNull(
						deactivationFailure, cell + ": the refusal must deactivate the catalog: " + deactivationFailure
					));
				}
				try (Evita reopened = new Evita(configuration)) {
					final Throwable loadFailure = failureOf(reopened::waitUntilFullyInitialized);
					checks.add(() -> assertNull(loadFailure, cell + ": the restart must load: " + loadFailure));
					// whether the deactivation was stored before the engine closed is a race; both sides read the
					// same bootstrap record, the newest state that reached the disk
					final Throwable activationFailure = failureOf(
						() -> CatalogUnpublishableBarrierAssertions.activateIfDeactivated(reopened, TEST_CATALOG)
					);
					checks.add(() -> assertNull(
						activationFailure, cell + ": the deactivated catalog must activate: " + activationFailure
					));
					final CatalogState reloaded = reopened.getCatalogState(TEST_CATALOG).orElse(null);
					checks.add(() -> assertEquals(
						CatalogState.WARMING_UP, reloaded,
						cell + ": the restarted catalog must load the last state it published, never `CORRUPTED`"
					));
					if (reloaded == CatalogState.WARMING_UP) {
						reopened.queryCatalog(TEST_CATALOG, session -> {
							final Optional<SealedEntitySchema> ownerSchema = session.getEntitySchema(owner);
							final boolean brandPresent = session.getEntity(BRAND, 1).isPresent();
							checks.add(() -> assertTrue(
								ownerSchema.isPresent(),
								cell + ": the owner type, published by the inline flush of its create, must survive"
							));
							checks.add(() -> assertFalse(
								ownerSchema.flatMap(schema -> schema.getReference(REFERENCE_NAME)).isPresent(),
								cell + ": the refused reference must not be stored"
							));
							checks.add(() -> assertTrue(
								brandPresent, cell + ": the entity published before the refusal must survive"
							));
						});
					}
				}
			} finally {
				cleanupTestPaths(paths);
			}
			assertAll(checks);
		}
	}

	/**
	 * Accepted histogram conditions evaluated against entities, one for each kind of read the histogram is kept current
	 * with: an attribute of the owner, of the owner's parent and of the referenced entity. The entities are inserted in
	 * a warming-up catalog; the owner whose data meets the condition is in the histogram, the other one is absent.
	 */
	@Nested
	@DisplayName("an accepted histogram condition")
	class AcceptedConditionEvaluation {

		/**
		 * Proves that the histogram holds the owner whose data meets the accepted condition, in its bucket, and does
		 * not hold the owner whose data does not.
		 *
		 * @param cellId the identifier of the accepted histogram condition cell
		 */
		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {"A-E2", "A-E15", "A-N2"})
		@DisplayName("indexes the owner it holds for and leaves out the owner it does not hold for")
		void shouldIndexOnlyTheOwnerTheConditionHoldsFor(@Nonnull String cellId) {
			final Cell cell = HISTOGRAM_CONDITION_CELLS.stream()
				.filter(candidate -> candidate.id().equals(cellId))
				.findFirst()
				.orElseThrow();
			final TestPaths paths = createTestPaths("IndexingExpressionInputEvaluation_" + entityTypeOf(cell));
			try (Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
				evita.defineCatalog(TEST_CATALOG);
				evita.updateCatalog(TEST_CATALOG, session -> {
					session.defineEntitySchema(BRAND).withoutGeneratedPrimaryKey()
						.withAttribute("status", String.class, attribute -> attribute.filterable().nullable())
						.updateVia(session);
					session.defineEntitySchema(EVALUATED_OWNER).withoutGeneratedPrimaryKey()
						.withHierarchy()
						.withAttribute("status", String.class, attribute -> attribute.filterable().nullable())
						.withReferenceToEntity(REFERENCE_NAME, BRAND, Cardinality.ZERO_OR_MORE, reference -> reference
							.indexedForFilteringAndPartitioning()
							.withAttribute("share", Integer.class, attribute -> attribute.filterable().nullable())
							.bucketed(HISTOGRAM_NAME, ExpressionFactory.parse(DEFAULT_VALUE))
							.bucketedPartially(ExpressionFactory.parse(cell.expression()))
						)
						.updateVia(session);
					session.createNewEntity(BRAND, 1).setAttribute("status", "A").upsertVia(session);
					session.createNewEntity(BRAND, 2).setAttribute("status", "B").upsertVia(session);
					session.createNewEntity(EVALUATED_OWNER, 10).setAttribute("status", "ACTIVE").upsertVia(session);
					// owner 1 meets every condition: its own status, its parent's and its brand's
					session.createNewEntity(EVALUATED_OWNER, 1)
						.setParent(10)
						.setAttribute("status", "A")
						.setReference(REFERENCE_NAME, 1, reference -> reference.setAttribute("share", 5))
						.upsertVia(session);
					// owner 2 meets none of them
					session.createNewEntity(EVALUATED_OWNER, 2)
						.setAttribute("status", "B")
						.setReference(REFERENCE_NAME, 2, reference -> reference.setAttribute("share", 5))
						.upsertVia(session);
				});

				final FilterIndex histogram = histogramFilterIndex(evita);

				assertTrue(histogram.getRecordsEqualTo(5).contains(1), cell + ": owner 1 must be in bucket 5");
				assertFalse(histogram.getAllRecords().contains(2), cell + ": owner 2 must not be in the histogram");
			} finally {
				cleanupTestPaths(paths);
			}
		}

		/**
		 * Returns the filter index of the histogram `shares` of the reference `brand` of the evaluated owner type.
		 *
		 * @param evita the engine
		 * @return the filter index
		 */
		@Nonnull
		private static FilterIndex histogramFilterIndex(@Nonnull Evita evita) {
			final EntityCollectionContract collection = evita.getCatalogInstance(TEST_CATALOG)
				.orElseThrow()
				.getCollectionForEntity(EVALUATED_OWNER)
				.orElseThrow();
			final EntityIndex index = IndexingTestSupport.getReferencedEntityTypeIndex(
				collection, Scope.LIVE, REFERENCE_NAME
			);
			final ReferencedTypeEntityIndex typeIndex = assertInstanceOf(ReferencedTypeEntityIndex.class, index);
			final FilterIndex filterIndex = typeIndex.getHistogramFilterIndex(HISTOGRAM_NAME, null);
			assertNotNull(filterIndex, "the histogram `" + HISTOGRAM_NAME + "` must have a filter index");
			return filterIndex;
		}
	}

	/**
	 * Selects the cells of the shared table defined through the engine.
	 *
	 * @return the cells
	 */
	@Nonnull
	private static List<Cell> definedCells() {
		final List<Cell> cells = new ArrayList<>(HISTOGRAM_CONDITION_CELLS.size() * 2 + 32);
		for (final Cell cell : HISTOGRAM_CONDITION_CELLS) {
			if (cell.refused() ? REFUSALS_DEFINED.contains(cell.id()) : !REFUSED_BY_TRIGGER_BUILD.contains(cell.id())) {
				cells.add(cell);
				cells.add(cell.withKind(IndexingExpressionKind.ASSIGNED_WHEN));
			}
		}
		for (final Cell cell : HISTOGRAM_VALUE_CELLS) {
			if (VALUES_DEFINED.contains(cell.id())) {
				cells.add(cell);
			}
		}
		for (final Cell cell : FACET_CONDITION_CELLS) {
			if (cell.refused() ? REFUSALS_DEFINED.contains(cell.id()) : FACET_ACCEPTANCES_DEFINED.contains(cell.id())) {
				cells.add(cell);
			}
		}
		return List.copyOf(cells);
	}

	/**
	 * Selects the cells the payload preflight admits and the trigger build refuses: the histogram conditions in both
	 * histogram slots, the histogram value cell and the facet condition cells.
	 *
	 * @return the cells
	 */
	@Nonnull
	static List<Cell> triggerBuildRefusedCells() {
		final List<Cell> cells = new ArrayList<>(REFUSED_BY_TRIGGER_BUILD.size() * 2 + 8);
		for (final Cell cell : HISTOGRAM_CONDITION_CELLS) {
			if (REFUSED_BY_TRIGGER_BUILD.contains(cell.id())) {
				cells.add(cell);
				cells.add(cell.withKind(IndexingExpressionKind.ASSIGNED_WHEN));
			}
		}
		for (final Cell cell : HISTOGRAM_VALUE_CELLS) {
			if (VALUES_REFUSED_BY_TRIGGER_BUILD.contains(cell.id())) {
				cells.add(cell);
			}
		}
		for (final Cell cell : FACET_CONDITION_CELLS) {
			if (FACETS_REFUSED_BY_TRIGGER_BUILD.contains(cell.id())) {
				cells.add(cell);
			}
		}
		// an identifier missing from the table must fail the run rather than silently drop its cell
		final int expectedCount = REFUSED_BY_TRIGGER_BUILD.size() * 2 + VALUES_REFUSED_BY_TRIGGER_BUILD.size() +
			FACETS_REFUSED_BY_TRIGGER_BUILD.size();
		if (cells.size() != expectedCount) {
			throw new IllegalStateException(
				"Expected " + expectedCount + " cells refused by the trigger build, found " + cells.size() + ": " +
					cells
			);
		}
		return List.copyOf(cells);
	}

	/**
	 * Returns the owner entity type the cell defines its reference on, distinct for every cell and slot.
	 *
	 * @param cell the cell
	 * @return the entity type
	 */
	@Nonnull
	private static String entityTypeOf(@Nonnull Cell cell) {
		return "product" + cell.id().replaceAll("[^A-Za-z0-9]", "") + "In" + cell.kind().ordinal();
	}

	/**
	 * Defines the types every owner refers to, and brings the catalog to the requested state.
	 *
	 * @param evita the engine
	 * @param state the requested state
	 */
	private static void defineCatalog(@Nonnull Evita evita, @Nonnull CatalogState state) {
		evita.defineCatalog(TEST_CATALOG);
		evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(BRAND).withoutGeneratedPrimaryKey()
				.withLocale(Locale.ENGLISH)
				.withAttribute("status", String.class, attribute -> attribute.filterable().nullable())
				.withAttribute("weight", Integer.class, attribute -> attribute.filterable().nullable())
				.withAttribute("lweight", Integer.class, attribute -> attribute.filterable().localized().nullable())
				.updateVia(session);
			session.defineEntitySchema(PARAMETER_GROUP).withoutGeneratedPrimaryKey()
				.withAttribute("type", String.class, attribute -> attribute.filterable().nullable())
				.withAttribute("weight", Integer.class, attribute -> attribute.filterable().nullable())
				.updateVia(session);
			if (state == CatalogState.ALIVE) {
				session.goLiveAndClose();
			}
		});
	}

	/**
	 * Defines the cell's owner type in its own session and returns the failure the definition ended with.
	 *
	 * @param evita      the engine
	 * @param entityType the owner type
	 * @param cell       the cell
	 * @return the failure, or `null` when the definition was accepted
	 */
	@Nullable
	private static RuntimeException defineCatching(
		@Nonnull Evita evita, @Nonnull String entityType, @Nonnull Cell cell
	) {
		return catching(() -> evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				configureOwner(session.defineEntitySchema(entityType), cell).updateVia(session);
			}
		));
	}

	/**
	 * Configures an owner type with every attribute, associated data and the hierarchy the cells read, and the
	 * reference `brand` carrying the cell's expression in its slot.
	 *
	 * @param builder the builder of the owner type
	 * @param cell    the cell
	 * @return the same builder
	 */
	@Nonnull
	private static EntitySchemaBuilder configureOwner(@Nonnull EntitySchemaBuilder builder, @Nonnull Cell cell) {
		final Expression expression = ExpressionFactory.parse(cell.expression());
		return builder.withoutGeneratedPrimaryKey()
			.withHierarchy()
			.withLocale(Locale.ENGLISH)
			.withAttribute("status", String.class, attribute -> attribute.filterable().nullable())
			.withAttribute("tags", String[].class, attribute -> attribute.filterable().nullable())
			.withAttribute("fallbackTags", String[].class, attribute -> attribute.filterable().nullable())
			.withAttribute("validity", DateTimeRange.class, attribute -> attribute.filterable().nullable())
			.withAttribute("fallbackValidity", DateTimeRange.class, attribute -> attribute.filterable().nullable())
			.withAttribute("code", String.class, attribute -> attribute.nullable())
			.withAttribute("delta", Integer.class, attribute -> attribute.filterable().nullable())
			.withAttribute("name", String.class, attribute -> attribute.localized().nullable())
			.withAssociatedData("flag", String.class, data -> data.nullable())
			.withAssociatedData("label", String.class, data -> data.localized().nullable())
			.withAssociatedData("rows", String[].class, data -> data.nullable())
			.withReferenceToEntity(REFERENCE_NAME, BRAND, Cardinality.ZERO_OR_MORE, reference -> {
				reference.withGroupTypeRelatedToEntity(PARAMETER_GROUP)
					.indexedForFiltering()
					.indexedWithComponents(ReferenceIndexedComponents.values())
					.faceted()
					.withAttribute("share", Integer.class, attribute -> attribute.filterable().nullable())
					.withAttribute("lshare", Integer.class, attribute -> attribute.filterable().localized().nullable());
				switch (cell.kind()) {
					case BUCKETED_PARTIALLY -> reference
						.bucketedInScope(Scope.LIVE, HISTOGRAM_NAME, ExpressionFactory.parse(DEFAULT_VALUE), null)
						.bucketedPartiallyInScope(Scope.LIVE, expression);
					case ASSIGNED_WHEN -> reference.bucketedInScope(
						Scope.LIVE, HISTOGRAM_NAME, ExpressionFactory.parse(DEFAULT_VALUE), expression
					);
					case HISTOGRAM_VALUE -> reference.bucketedInScope(Scope.LIVE, HISTOGRAM_NAME, expression, null);
					case FACETED_PARTIALLY -> reference.facetedPartiallyInScope(Scope.LIVE, expression);
				}
			});
	}

	/**
	 * Records the check of one cell's verdict at definition.
	 *
	 * @param cell    the cell
	 * @param failure the failure the definition ended with, or `null`
	 * @param checks  the checks
	 */
	private static void recordVerdict(
		@Nonnull Cell cell, @Nullable RuntimeException failure, @Nonnull List<Executable> checks
	) {
		if (cell.refused()) {
			checks.add(() -> assertRefusedByPreflight(cell, entityTypeOf(cell), failure));
		} else {
			checks.add(() -> {
				if (failure != null) {
					fail(cell + ": must be accepted, but was refused: " + failure, failure);
				}
			});
		}
	}

	/**
	 * Records the check that the restarted catalog holds the cell's expression exactly when the cell was accepted.
	 *
	 * @param session the session of the restarted catalog
	 * @param cell    the cell
	 * @param checks  the checks
	 */
	private static void recordStoredDefinition(
		@Nonnull EvitaSessionContract session, @Nonnull Cell cell, @Nonnull List<Executable> checks
	) {
		final Optional<ReferenceSchemaContract> reference = session.getEntitySchema(entityTypeOf(cell))
			.flatMap(schema -> schema.getReference(REFERENCE_NAME));
		if (cell.refused()) {
			checks.add(() -> assertFalse(
				reference.isPresent(), cell + ": the refused reference must not be stored after a restart"
			));
		} else {
			checks.add(() -> {
				assertTrue(reference.isPresent(), cell + ": the accepted reference must survive a restart");
				assertEquals(
					ExpressionFactory.parse(cell.expression()).toExpressionString(),
					storedExpression(reference.get(), cell.kind()),
					cell + ": the stored expression"
				);
			});
		}
	}

	/**
	 * Returns the text of the expression stored in the slot, or `null` when the slot is empty.
	 *
	 * @param reference the stored reference
	 * @param kind      the slot
	 * @return the expression text
	 */
	@Nullable
	private static String storedExpression(
		@Nonnull ReferenceSchemaContract reference, @Nonnull IndexingExpressionKind kind
	) {
		final Expression stored = switch (kind) {
			case BUCKETED_PARTIALLY -> reference.getBucketedPartiallyInScopes().get(Scope.LIVE);
			case ASSIGNED_WHEN -> histogramOf(reference).assignedWhen();
			case HISTOGRAM_VALUE -> histogramOf(reference).valueExpression();
			case FACETED_PARTIALLY -> reference.getFacetedPartiallyInScopes().get(Scope.LIVE);
		};
		return stored == null ? null : stored.toExpressionString();
	}

	/**
	 * Returns the histogram `shares` of the reference in scope LIVE.
	 *
	 * @param reference the reference
	 * @return the histogram definition
	 */
	@Nonnull
	private static HistogramIndexDefinition histogramOf(@Nonnull ReferenceSchemaContract reference) {
		final HistogramIndexDefinition definition =
			reference.getAllHistogramIndexDefinitions().getOrDefault(Scope.LIVE, Map.of()).get(HISTOGRAM_NAME);
		assertNotNull(definition, "the histogram `" + HISTOGRAM_NAME + "` must be stored");
		return definition;
	}

	/**
	 * Asserts that the definition was refused by the payload preflight, with the message naming the expression, the
	 * read and the reason, and not by the deferred trigger rebuild.
	 *
	 * @param cell       the cell
	 * @param entityType the owner type the cell was defined on
	 * @param failure    the failure the definition ended with
	 */
	private static void assertRefusedByPreflight(
		@Nonnull Cell cell, @Nonnull String entityType, @Nullable RuntimeException failure
	) {
		assertNotNull(failure, cell + ": must be refused, but was accepted");
		final InvalidSchemaMutationException refusal =
			ExceptionUtils.findInCauseChain(failure, InvalidSchemaMutationException.class);
		if (refusal == null) {
			fail(cell + ": must be refused by an InvalidSchemaMutationException, was: " + failure, failure);
		}
		final String message = refusal.getMessage();
		assertTrue(message.contains(cell.reason()), cell + ": the reason is missing in: " + message);
		assertTrue(
			message.contains("`" + ExpressionFactory.parse(cell.expression()).toExpressionString() + "`"),
			cell + ": the expression is missing in: " + message
		);
		if (cell.read() != null) {
			assertTrue(message.contains("cannot read `" + cell.read() + "`"),
				cell + ": the read is missing in: " + message);
		}
		if (!hasCatalogFrame(refusal, PREFLIGHT_FRAME) || hasCatalogFrame(refusal, TRIGGER_BUILD_FRAME)) {
			fail(cell + ": the refusal must come from the payload preflight, not the deferred trigger rebuild: " +
				message, refusal);
		}
		assertTrue(message.contains("`" + entityType + "`"), cell + ": the entity type is missing in: " + message);
	}

	/**
	 * Asserts that the definition was refused, and that the refusal came from the trigger build at the end of the
	 * schema change rather than from the payload preflight. The whole cause chain is searched, because a refusal that
	 * is not an evitaDB exception reaches the caller wrapped.
	 *
	 * @param cell    the cell
	 * @param failure the failure the definition ended with
	 */
	private static void assertRefusedByTriggerBuild(@Nonnull Cell cell, @Nullable RuntimeException failure) {
		assertNotNull(failure, cell + ": must be refused by the trigger build, but was accepted");
		if (!isRefusedByTriggerBuild(failure)) {
			fail(
				cell + ": the refusal must come from the trigger build, not the payload preflight: " + failure,
				failure
			);
		}
	}

	/**
	 * Tells whether the failure is a refusal of the trigger build at the end of the schema change rather than of the
	 * payload preflight. The whole cause chain is searched, because a refusal that is not an evitaDB exception reaches
	 * the caller wrapped.
	 *
	 * @param failure the failure the definition ended with, or `null` when it was accepted
	 * @return true when a cause comes from the trigger build and none from the payload preflight
	 */
	private static boolean isRefusedByTriggerBuild(@Nullable Throwable failure) {
		boolean fromTriggerBuild = false;
		boolean fromPreflight = false;
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			fromTriggerBuild = fromTriggerBuild || hasCatalogFrame(cause, TRIGGER_BUILD_FRAME);
			fromPreflight = fromPreflight || hasCatalogFrame(cause, PREFLIGHT_FRAME);
		}
		return fromTriggerBuild && !fromPreflight;
	}

	/**
	 * Runs the action and returns whatever it ended with, an assertion failure included, so that the checks recorded
	 * after it still run.
	 *
	 * @param action the action
	 * @return the throwable, or `null` when the action completed
	 */
	@Nullable
	private static Throwable failureOf(@Nonnull Executable action) {
		try {
			action.execute();
			return null;
		} catch (Throwable e) {
			return e;
		}
	}

	/**
	 * Runs the action and returns the runtime exception it ended with.
	 *
	 * @param action the action
	 * @return the exception, or `null` when the action completed
	 */
	@Nullable
	private static RuntimeException catching(@Nonnull Runnable action) {
		try {
			action.run();
			return null;
		} catch (RuntimeException e) {
			return e;
		}
	}

}
