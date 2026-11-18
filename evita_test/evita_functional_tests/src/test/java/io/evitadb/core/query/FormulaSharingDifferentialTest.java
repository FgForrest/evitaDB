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

package io.evitadb.core.query;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.proxy.WithEntityContract;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.ManagedReferencesBehaviour;
import io.evitadb.api.query.require.ReferenceContent;
import io.evitadb.api.requestResponse.EvitaEntityResponse;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.api.requestResponse.extraResult.Histogram;
import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.core.Evita;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.cache.FormulaCacheSupervisor;
import io.evitadb.core.cache.FormulaCacheTestSessions;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.exception.InconsistentResultsException;
import io.evitadb.core.query.algebra.AbstractFormula;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.AndFormula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.OrFormula;
import io.evitadb.core.query.algebra.identity.IdentityRecord;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.extraResult.CacheableEvitaResponseExtraResultComputer;
import io.evitadb.core.query.extraResult.EvitaResponseExtraResultComputer;
import io.evitadb.core.query.indexSelection.IndexSelectionVisitor;
import io.evitadb.core.session.EvitaInternalSessionContract;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.RandomUtils;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.IOException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises sharing verification through the planner with real contexts and a test-owned index supplying its trees.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Formula sharing differential verification")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(CACHE)
class FormulaSharingDifferentialTest implements EvitaTestSupport {
	/** Catalog owned by this test. */
	private static final String CATALOG = "formulaSharingDifferential";
	/** Collection whose primary keys the supplied index answers. */
	private static final String PRODUCT = "product";
	/** Isolated storage and export paths. */
	private TestPaths paths;
	/** Engine providing real schemas and a pinned catalog. */
	private Evita evita;

	/** Creates two products independently of all other test fixtures. */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("FormulaSharingDifferentialTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(CATALOG, session -> {
			session.defineEntitySchema(PRODUCT).withoutGeneratedPrimaryKey().updateVia(session);
			session.upsertEntity(session.createNewEntity(PRODUCT, 1));
			session.upsertEntity(session.createNewEntity(PRODUCT, 2));
		});
	}

	/** Closes the test-owned engine and removes its directories. */
	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/**
	 * Replaces the default engine with one whose formula result cache serves and samples every hit, and installs a
	 * supplied index whose leaves `supplied` collects.
	 */
	@Nonnull
	private EntityCollection startEngineWithResultCache(@Nonnull List<InputBlindFormula> supplied) {
		this.evita.close();
		cleanupTestPaths(this.paths);
		this.paths = createTestPaths("FormulaSharingDifferentialTest-resultCache");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).cache(
			CacheOptions.builder().enabled(true).minimalComplexityThreshold(0)
				.cacheSizeInBytes(100_000_000L).verifyOneHitIn(1).dropEntriesAfterSecondsOfInactivity(0).build()
		).build());
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(CATALOG, session -> {
			session.defineEntitySchema(PRODUCT).withoutGeneratedPrimaryKey().updateVia(session);
			session.upsertEntity(session.createNewEntity(PRODUCT, 1));
			session.upsertEntity(session.createNewEntity(PRODUCT, 2));
			session.goLiveAndClose();
		});
		final SupersetIndex index = new SupersetIndex(() -> {
			final InputBlindFormula leaf = new InputBlindFormula(1);
			supplied.add(leaf);
			return new OrFormula(leaf, leaf(2));
		});
		final EntityCollection collection = catalog().getCollectionForEntityInternal(PRODUCT).orElseThrow();
		collection.addIndex(index);
		return collection;
	}

	/** Returns the counters of the test-owned engine's formula result cache. */
	@Nonnull
	private FormulaCacheSupervisor.Statistics cacheStatistics() {
		final FormulaCacheSupervisor.Statistics statistics = this.evita.getFormulaCacheStatistics();
		assertNotNull(statistics);
		return statistics;
	}

	/** Opens a read-only session that executes queries outside any transaction, so the cache gate can open. */
	@Nonnull
	private EvitaSessionContract cacheEligibleSession() {
		return FormulaCacheTestSessions.readOnly(this.evita, CATALOG);
	}

	/** Returns the effective cache state the test-owned engine reports to its clients. */
	private boolean queryCacheEnabled() {
		return this.evita.management().getEngineSettings().queryCacheEnabled();
	}

	/** Opens a read-only session on the test-owned catalog. */
	@Nonnull
	private EvitaSessionContract readOnlySession() {
		return this.evita.createReadOnlySession(CATALOG);
	}

	/** Applies a schema or data change to the test-owned catalog in its own read-write session. */
	private void updateCatalog(@Nonnull Consumer<EvitaSessionContract> update) {
		this.evita.updateCatalog(CATALOG, update);
	}

	/** Returns the engine's pinned instance of the test-owned catalog. */
	@Nonnull
	private Catalog catalog() {
		return (Catalog) this.evita.getCatalogInstanceOrThrowException(CATALOG);
	}

	/**
	 * The planner's comparison of the preferred plan with the key-free baseline, and the state of the sibling context.
	 */
	@Nested
	@DisplayName("Planner verification")
	class PlannerVerification {
		/** Verifies an actual merge, response equality and deterministic randomized ordering. */
		@Test
		@DisplayName("Matches the key-free response when equal subtrees really share an instance")
		void shouldMatchKeyFreeResponseWhenSubtreesMerge() {
			final SupersetIndex index = new SupersetIndex(() -> new OrFormula(
				new AndFormula(leaf(1, 2), leaf(1)),
				new AndFormula(leaf(1, 2), leaf(2))
			));
			final QueryPlanningContext context = context(index, true);
			final QueryPlan preferred = QueryPlanner.planQuery(context);
			final QueryPlan baseline = QueryPlanner.planQuery(context.createVerificationSibling());
			final OrFormula sharedTree = FormulaFinder.find(preferred.getFilter(), OrFormula.class, LookUp.SHALLOW)
				.iterator().next();
			final OrFormula unsharedTree = FormulaFinder.find(baseline.getFilter(), OrFormula.class, LookUp.SHALLOW)
				.iterator().next();
			assertSame(
				sharedTree.getInnerFormulas()[0].getInnerFormulas()[0],
				sharedTree.getInnerFormulas()[1].getInnerFormulas()[0]
			);
			assertNotSame(
				unsharedTree.getInnerFormulas()[0].getInnerFormulas()[0],
				unsharedTree.getInnerFormulas()[1].getInnerFormulas()[0]
			);
			final byte[] random = RandomUtils.getFrozenRandom();
			final EvitaResponse<EntityReference> response = preferred.execute(random);
			assertEquals(response, baseline.execute(random));
			assertEquals(2, response.getTotalRecordCount());
		}

		/** Planning without the verification flag leaves the filtering computation for the caller. */
		@Test
		@DisplayName("Leaves execution deferred when formula-sharing verification was not requested")
		void shouldDeferExecutionWithoutVerificationFlag() {
			final QueryPlanningContext context = context(
				new SupersetIndex(() -> new OrFormula(leaf(1), leaf(2))), false
			);
			final QueryPlan plan = QueryPlanner.planQuery(context);
			assertFalse(context.isFormulaSharingVerificationRequired());
			assertNull(plan.getFilter().getMemoizedResult());
		}

		/** An omitted identity input must make the flagged planner fail, while the baseline never requests its key. */
		@Test
		@DisplayName("Rejects a flagged query when a defective identity merges different results")
		void shouldRejectQueryWhenIdentityOmitsAnInput() {
			final InputBlindFormula first = new InputBlindFormula(1);
			final InputBlindFormula second = new InputBlindFormula(2);
			assertNotEquals(first.compute(), second.compute());
			assertEquals(first.getKey(), second.getKey());
			final List<InputBlindFormula> supplied = new ArrayList<>(8);
			final SupersetIndex index = new SupersetIndex(() -> {
				final InputBlindFormula left = new InputBlindFormula(1);
				final InputBlindFormula right = new InputBlindFormula(2);
				supplied.add(left);
				supplied.add(right);
				return new OrFormula(left, right);
			});

			final InconsistentResultsException error = assertThrows(
				InconsistentResultsException.class, () -> QueryPlanner.planQuery(context(index, true))
			);

			assertTrue(error.getMessage().contains("key-free baseline"), error.getMessage());
			assertTrue(error.getMessage().contains("preferred plan"), error.getMessage());
			assertEquals(4, supplied.size(), "One independent pair is planned on each side");
			assertTrue(supplied.get(0).keyRequests > 0, "Sharing must request the first operand's key");
			assertTrue(supplied.get(1).keyRequests > 0, "Sharing must request the second operand's key");
			for (final InputBlindFormula baselineOperand : supplied.subList(2, 4)) {
				assertEquals(0, baselineOperand.keyRequests, "The baseline must request no keys");
				assertNotNull(baselineOperand.getMemoizedResult(), "Every key-free operand must actually compute");
			}
		}

		/** Public response equality compares entity identities only, so this difference is visible to content alone. */
		@Test
		@DisplayName("Rejects a flagged query when sharing changes only the fetched reference rows")
		void shouldRejectQueryWhenSharingChangesOnlyFetchedReferences() {
			prepareBodies();
			updateCatalog(session -> session.getEntity("category", 1, referenceContentAll()).orElseThrow()
				.openForWrite().setReference("target", 2).upsertVia(session));
			final List<InputBlindFormula> supplied = new ArrayList<>(8);
			final Catalog catalog = catalog();
			catalog.getCollectionForEntityInternal("target").orElseThrow().addIndex(new SupersetIndex("target", () -> {
				final InputBlindFormula first = new InputBlindFormula(1);
				final InputBlindFormula second = new InputBlindFormula(2);
				supplied.add(first);
				supplied.add(second);
				return new OrFormula(first, second);
			}));
			try (final EvitaSessionContract session = readOnlySession()) {
				((EvitaInternalSessionContract) session).execute(internal -> {
					final Query query = Query.query(
						collection("category"), filterBy(entityPrimaryKeyInSet(1)),
						require(
							entityFetch(referenceContent(
								"target", filterBy(entityHaving(not(entityPrimaryKeyInSet(3))))
							)),
							debug(DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					);
					final QueryPlanningContext context = catalog.getCollectionForEntityInternal("category")
						.orElseThrow()
						.createQueryContext(
							new EvitaRequest(query, OffsetDateTime.now(), SealedEntity.class, null), internal
						);

					final InconsistentResultsException error = assertThrows(
						InconsistentResultsException.class, () -> QueryPlanner.planQuery(context)
					);

					assertTrue(error.getMessage().contains("key-free baseline"), error.getMessage());
					assertTrue(supplied.stream().anyMatch(it -> it.keyRequests > 0),
						"The preferred body filter must share the defective formulas");
				});
			}
		}

		/** A sibling replaces a supplied telemetry root and supervisor, and its descendants inherit that state. */
		@Test
		@DisplayName("Inherits key-free verification state in nested contexts")
		void shouldInheritVerificationStateInNestedContexts() {
			final SupersetIndex index = new SupersetIndex(() -> new OrFormula(leaf(1), leaf(2)));
			final QueryPlanningContext context = context(index, true);
			final QueryPlanningContext sibling = context.createVerificationSibling();
			final Catalog catalog = catalog();
			final EntityCollection collection = sibling.getEntityCollectionOrThrowException(
				PRODUCT, "test nested planning"
			);
			final QueryPlanningContext nested = new QueryPlanningContext(
				sibling, catalog, collection, null, sibling.getEvitaRequest(), new QueryTelemetry(QueryPhase.OVERALL),
				Map.of(index.getIndexKey(), index), Map.of(index.getPrimaryKey(), index),
				new PassThroughCacheSupervisor()
			);
			assertTrue(context.isFormulaSharingVerificationRequired());
			assertTrue(context.isAlternativeIndexVerificationRequired());
			for (final QueryPlanningContext verification : List.of(sibling, nested)) {
				assertAll(
					() -> assertTrue(verification.isVerificationSibling()),
					() -> assertFalse(verification.isFormulaSharingEnabled()),
					() -> assertFalse(verification.isFormulaSharingVerificationRequired()),
					() -> assertFalse(verification.isAlternativeIndexVerificationRequired()),
					() -> assertNull(verification.getCurrentStep(), "A supplied telemetry root must be dropped"),
					() -> assertSame(NoCacheSupervisor.INSTANCE, verification.getCacheSupervisor(),
						"A supplied supervisor must be replaced by the one that never caches")
				);
			}
			assertNull(QueryPlanner.planQuery(nested).getFilter().getMemoizedResult());
			assertNull(QueryPlanner.planNestedQuery(nested, () -> "nested verification baseline")
				.getFilter().getMemoizedResult());
			assertEquals(0, index.getActivity().getQueryCount());

			final QueryPlanningContext control = new QueryPlanningContext(
				context, catalog, collection, null, context.getEvitaRequest(), null,
				Map.of(index.getIndexKey(), index), Map.of(index.getPrimaryKey(), index), NoCacheSupervisor.INSTANCE
			);
			QueryPlanner.planNestedQuery(control, () -> "nested planning of the query itself");
			assertTrue(index.getActivity().getQueryCount() > 0, "Planning outside the sibling must record activity");
		}

		/**
		 * The verification dry runs inside planning are executions of the query's own plans, so a populated cache
		 * serves and samples them before the real execution starts; the key-free baseline stays off the cache.
		 */
		@ParameterizedTest
		@EnumSource(
			value = DebugMode.class, names = {"VERIFY_POSSIBLE_CACHING_TREES", "VERIFY_ALTERNATIVE_INDEX_RESULTS"}
		)
		@DisplayName("Serves verification dry runs from a populated result cache and keeps the baseline key-free")
		void shouldServeDryRunsFromPopulatedCacheAndKeepBaselineKeyFree(@Nonnull DebugMode mode) {
			final List<InputBlindFormula> supplied = new ArrayList<>(8);
			final EntityCollection collection = startEngineWithResultCache(supplied);
			try (final EvitaSessionContract session = cacheEligibleSession()) {
				final Query warmup = Query.query(collection(PRODUCT), require(debug(DebugMode.PREFER_INDEX_SCAN)));
				session.query(warmup, EntityReference.class);
				final FormulaCacheSupervisor.Statistics warm = cacheStatistics();
				assertTrue(warm.admissions() > 0);
				assertTrue(queryCacheEnabled());
				QueryPlanner.planQuery(collection.createQueryContext(
					new EvitaRequest(warmup, OffsetDateTime.now(), EntityReference.class, null), session));
				assertEquals(warm.hits(), cacheStatistics().hits(), "Planning an unflagged query must not look up");
				assertEquals(warm.misses(), cacheStatistics().misses());
				assertEquals(warm.admissions(), cacheStatistics().admissions());

				// the alternative-index mode does not favour bitmaps on its own; prefetch must not keep the cache out
				final Query flagged = Query.query(collection(PRODUCT),
					require(debug(mode, DebugMode.PREFER_INDEX_SCAN)));
				final QueryPlanningContext context = collection.createQueryContext(
					new EvitaRequest(flagged, OffsetDateTime.now(), EntityReference.class, null), session);
				final QueryPlan plan = QueryPlanner.planQuery(context);
				final FormulaCacheSupervisor.Statistics planned = cacheStatistics();
				assertTrue(planned.hits() > warm.hits(), "The dry runs inside planning must be served from the cache");
				assertTrue(planned.verifiedHits() > warm.verifiedHits(), "The dry runs' hits must be sampled");
				plan.execute();

				if (mode == DebugMode.VERIFY_POSSIBLE_CACHING_TREES) {
					supplied.clear();
					final long probes = cacheStatistics().hits() + cacheStatistics().misses();
					QueryPlanner.planQuery(context.createVerificationSibling()).execute(RandomUtils.getFrozenRandom());
					assertFalse(supplied.isEmpty());
					assertEquals(0, supplied.stream().mapToInt(it -> it.keyRequests).sum());
					assertEquals(probes, cacheStatistics().hits() + cacheStatistics().misses());
				}
			}
		}
	}

	/**
	 * Verification dry runs leave the event, the telemetry and the counters of the query to its real execution.
	 */
	@Nested
	@DisplayName("Suppressed side effects")
	class SuppressedSideEffects {
		/** Verification leaves the event and telemetry to the real execution, recorded as for an unverified query. */
		@ParameterizedTest
		@EnumSource(
			value = DebugMode.class,
			names = {"VERIFY_POSSIBLE_CACHING_TREES", "VERIFY_ALTERNATIVE_INDEX_RESULTS", "PREFER_INDEX_SCAN"}
		)
		@DisplayName("Records one finished event and one execution profile with or without verification")
		void shouldRecordOnlyRealExecution(@Nonnull DebugMode mode) throws IOException {
			updateCatalog(session -> {
				session.defineEntitySchema(PRODUCT)
					.withAttribute("rank", Long.class, attribute -> attribute.sortable().nullable()).updateVia(session);
				for (int i = 1; i <= 2; i++) {
					session.getEntity(PRODUCT, i, attributeContentAll()).orElseThrow().openForWrite()
						.setAttribute("rank", (long) i)
						.upsertVia(session);
				}
			});
			try (
				final EvitaSessionContract session = readOnlySession();
				final Recording recording = new Recording()
			) {
				recording.enable("io.evitadb.query.Finished");
				recording.start();
				final EntityCollection collection = catalog().getCollectionForEntityInternal(PRODUCT).orElseThrow();
				final Query query = Query.query(
					collection(PRODUCT), filterBy(entityPrimaryKeyInSet(1, 2)), orderBy(attributeNatural("rank")),
					require(
						debug(EnumSet.of(mode, DebugMode.PREFER_INDEX_SCAN).toArray(DebugMode[]::new)),
						queryTelemetry()
					)
				);
				final QueryPlanningContext context = ((EvitaInternalSessionContract) session).execute(internal -> {
					return collection.createQueryContext(
						new EvitaRequest(query, OffsetDateTime.now(), EntityReference.class, null), internal);
				});
				assertNotNull(context.getQueryFinishedEvent());
				final QueryPlan plan = QueryPlanner.planQuery(context);
				if (mode != DebugMode.PREFER_INDEX_SCAN) {
					assertNotNull(plan.getFilter().getMemoizedResult(), "Verification must execute the plan");
				}
				assertAll(
					() -> assertNull(context.getQueryFinishedEvent().getPrefetched(),
						"Dry runs must not finish the event"),
					() -> assertTrue(context.getQueryFinishedEvent().getPlanned() > 0,
						"Building the preferred plan must start the event"),
					() -> assertEquals(0, countExecutionSteps(context.getTelemetryRoot()),
						"Dry runs must not add execution steps")
				);
				assertEquals(0, countSortAnnotations(context.getTelemetryRoot()));
				final long planned = context.getQueryFinishedEvent().getPlanned();
				final EvitaResponse<EntityReference> response = plan.execute();
				assertEquals(planned, context.getQueryFinishedEvent().getPlanned());
				assertEquals(1, countExecutionSteps(response.getExtraResult(QueryTelemetry.class)), mode.toString());
				assertTrue(countSortAnnotations(response.getExtraResult(QueryTelemetry.class)) > 0,
					"The real execution must record its sort strategy");
				recording.stop();
				final Path dump = FormulaSharingDifferentialTest.this.paths.work().resolve("finished-" + mode + ".jfr");
				Files.createDirectories(dump.getParent());
				recording.dump(dump);
				assertEquals(1L, RecordingFile.readAllEvents(dump).stream()
					.filter(it -> it.getEventType().getName().equals("io.evitadb.query.Finished"))
					.filter(it -> it.getString("catalogName").equals(CATALOG)).count(), mode.toString());
			}
		}

		/** Exercises the hierarchy producer's direct body fetch, including a nested entity filter. */
		@Test
		@DisplayName("Keeps hierarchy statistics body fetching key-free and unrecorded in the baseline")
		void shouldKeepHierarchyBodiesKeyFree() {
			prepareBodies();
			assertBodyFetchKeyFree("category", hierarchyOfSelf(fromRoot("tree", filteredBody())));
		}

		/** Exercises both bulk facet bodies and bulk group bodies through the summary producer. */
		@Test
		@DisplayName("Keeps fetched facet and group bodies key-free and unrecorded in the baseline")
		void shouldKeepSummaryBodiesKeyFree() {
			prepareBodies();
			assertBodyFetchKeyFree(PRODUCT, referenceSummaryOfReference(
				"categories", FacetStatisticsDepth.COUNTS, filteredBody(),
				entityGroupFetch(filteredBody().getRequirements())
			));
		}
	}

	/**
	 * Both verification executions share one frozen random state, while ordinary queries stay random.
	 */
	@Nested
	@DisplayName("Frozen randomness")
	class FrozenRandomness {
		/** Four shuffled facets give a false-pass probability of (1/24)^12 for an unfrozen sorter. */
		@ParameterizedTest
		@EnumSource(
			value = DebugMode.class, names = {"VERIFY_POSSIBLE_CACHING_TREES", "VERIFY_ALTERNATIVE_INDEX_RESULTS"}
		)
		@DisplayName("Freezes facet ordering for both verification modes and preserves ordinary randomness")
		void shouldFreezeSummaryRandomOrdering(@Nonnull DebugMode mode) {
			prepareBodies();
			assertRandomOrdering(PRODUCT, referenceSummaryOfReference(
				"categories", FacetStatisticsDepth.COUNTS, orderBy(random())
			), ReferenceSummary.class, mode);
		}

		/** At least four shuffled hierarchy roots give a false-pass bound of (1/24)^12. */
		@ParameterizedTest
		@EnumSource(
			value = DebugMode.class, names = {"VERIFY_POSSIBLE_CACHING_TREES", "VERIFY_ALTERNATIVE_INDEX_RESULTS"}
		)
		@DisplayName("Freezes hierarchy ordering for both verification modes and preserves ordinary randomness")
		void shouldFreezeHierarchyRandomOrdering(@Nonnull DebugMode mode) {
			prepareBodies();
			assertRandomOrdering(
				"category", hierarchyOfSelf(orderBy(random()), fromRoot("tree")), Hierarchy.class, mode
			);
		}
	}

	/**
	 * Response comparison by content, including bodies and extra results that public equality compares by identity.
	 */
	@Nested
	@DisplayName("Response comparison")
	class ResponseComparison {
		/** Public response equality deliberately ignores the filtered references of an entity body. */
		@Test
		@DisplayName("Detects changed filtered references despite equal public responses")
		void shouldCompareFilteredReferences() {
			prepareBodies();
			try (final EvitaSessionContract session = readOnlySession()) {
				assertContentDifference(
					fetchBody(session, entityFetch(referenceContent("categories"))).orElseThrow(),
					fetchBody(session, entityFetch(referenceContent("categories",
						filterBy(entityPrimaryKeyInSet(1, 2, 3))))).orElseThrow()
				);
			}
		}

		/** Referenced entities retain their identities while a nested reference filter changes their contents. */
		@Test
		@DisplayName("Detects changed nested referenced bodies despite equal public responses")
		void shouldCompareNestedBodies() {
			prepareBodies();
			try (final EvitaSessionContract session = readOnlySession()) {
				assertContentDifference(
					fetchBody(session, entityFetch(referenceContent("categories",
						entityFetch(referenceContent("target"))))).orElseThrow(),
					fetchBody(session, entityFetch(referenceContent("categories",
						entityFetch(referenceContent("target", filterBy(entityPrimaryKeyInSet(2))))))).orElseThrow()
				);
			}
		}

		/** Client views exposing their materialized entity must receive the same deep comparison. */
		@Test
		@DisplayName("Compares bodies exposed by entity projection contracts")
		void shouldCompareProjectionBodies() {
			prepareBodies();
			try (final EvitaSessionContract session = readOnlySession()) {
				final EvitaResponse<MaterializedEntityView> first = bodyResponse(new MaterializedEntityView(
					fetchBody(session, entityFetch(referenceContent("categories"))).orElseThrow()));
				final EvitaResponse<MaterializedEntityView> second = bodyResponse(new MaterializedEntityView(
					fetchBody(session, entityFetch(referenceContent("categories",
						filterBy(entityPrimaryKeyInSet(1, 2, 3))))).orElseThrow()));
				assertEquals(first, second);
				assertFalse(QueryResponseComparator.matches(first, second));
			}
		}

		/** Duplicate rows retain their internal row identities during the API content comparison. */
		@Test
		@DisplayName("Compares duplicate reference rows by materialized content")
		void shouldCompareDuplicateReferenceBodies() {
			prepareBodies();
			updateCatalog(session -> {
				session.defineEntitySchema(PRODUCT)
					.withReferenceToEntity("duplicates", "category", Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
						whichIs -> whichIs.withAttribute("code", String.class, attribute -> attribute.representative()))
					.updateVia(session);
				session.getEntity(PRODUCT, 1, referenceContentAll()).orElseThrow().openForWrite()
					.setOrUpdateReference("duplicates", 1, reference -> false,
						reference -> reference.setAttribute("code", "one"))
					.setOrUpdateReference("duplicates", 1, reference -> false,
						reference -> reference.setAttribute("code", "two"))
					.upsertVia(session);
			});
			try (final EvitaSessionContract session = readOnlySession()) {
				final EvitaResponse<SealedEntity> first = bodyResponse(fetchBody(session,
					entityFetch(referenceContentWithAttributes("duplicates"))).orElseThrow());
				final EvitaResponse<SealedEntity> second = bodyResponse(fetchBody(session,
					entityFetch(referenceContentWithAttributes("duplicates"))).orElseThrow());
				assertEquals(2, first.getRecordData().getFirst().getReferences("duplicates").size());
				assertEquals(first, second);
				assertTrue(QueryResponseComparator.matches(first, second));
				assertContentDifference(
					fetchBody(session, entityFetch(referenceContentWithAttributes("duplicates",
						filterBy(attributeEquals("code", "one"))))).orElseThrow(),
					fetchBody(session, entityFetch(referenceContentWithAttributes("duplicates",
						filterBy(attributeEquals("code", "two"))))).orElseThrow()
				);
			}
		}

		/** Identical visible reference rows can carry different page/strip metadata. */
		@Test
		@DisplayName("Compares reference chunk metadata despite equal visible reference rows")
		void shouldCompareReferenceChunkMetadata() {
			prepareBodies();
			try (final EvitaSessionContract session = readOnlySession()) {
				assertContentDifference(
					fetchBody(session, entityFetch(referenceContent("categories", page(1, 2)))).orElseThrow(),
					fetchBody(session, entityFetch(referenceContent("categories", strip(0, 2)))).orElseThrow()
				);
			}
		}

		/** Named-only reference chunks must be compared even though the ordinary reference view is empty. */
		@Test
		@DisplayName("Compares named reference rows, keys, pagination and nested named bodies")
		void shouldCompareNamedReferences() {
			prepareBodies();
			try (final EvitaSessionContract session = readOnlySession()) {
				final SealedEntity baseline = fetchBody(session, entityFetch(namedReferences(
					"selection", "categories", new RequireConstraint[0], filterBy(entityPrimaryKeyInSet(1, 2))
				))).orElseThrow();
				assertTrue(baseline.getReferences().isEmpty(), "The witness must have no unnamed rows");
				assertTrue(QueryResponseComparator.matches(bodyResponse(baseline), bodyResponse(
					fetchBody(session, entityFetch(namedReferences("selection", "categories", new RequireConstraint[0],
						filterBy(entityPrimaryKeyInSet(1, 2))))).orElseThrow())));
				assertContentDifference(baseline, fetchBody(session, entityFetch(namedReferences(
					"selection", "categories", new RequireConstraint[0], filterBy(entityPrimaryKeyInSet(1))
				))).orElseThrow());
				assertContentDifference(baseline, fetchBody(session, entityFetch(namedReferences(
					"other", "categories", new RequireConstraint[0], filterBy(entityPrimaryKeyInSet(1, 2))
				))).orElseThrow());
				assertContentDifference(
					fetchBody(session, entityFetch(namedReferences("selection", "categories",
						new RequireConstraint[]{page(1, 2)}))).orElseThrow(),
					fetchBody(session, entityFetch(namedReferences("selection", "categories",
						new RequireConstraint[]{strip(0, 2)}))).orElseThrow());
				assertContentDifference(
					fetchBody(session, entityFetch(namedReferences("selection", "categories",
						new RequireConstraint[0], orderBy(entityPrimaryKeyNatural(
							io.evitadb.api.query.order.OrderDirection.ASC))))).orElseThrow(),
					fetchBody(session, entityFetch(namedReferences("selection", "categories",
						new RequireConstraint[0], orderBy(entityPrimaryKeyNatural(
							io.evitadb.api.query.order.OrderDirection.DESC))))).orElseThrow());
				assertContentDifference(
					fetchBody(session, entityFetch(namedReferences("selection", "categories", new RequireConstraint[]{
						entityFetch(referenceContent("target"))}))).orElseThrow(),
					fetchBody(session, entityFetch(namedReferences("selection", "categories", new RequireConstraint[]{
						entityFetch(referenceContent("target", filterBy(entityPrimaryKeyInSet(2))))}))).orElseThrow());

				assertContentDifference(
					fetchBody(session, entityFetch(referenceContent("categories", entityFetch(namedReferences(
						"nested", "target", new RequireConstraint[]{entityFetch(attributeContentAll())}
					))))).orElseThrow(),
					fetchBody(session, entityFetch(referenceContent("categories", entityFetch(namedReferences(
						"nested", "target", new RequireConstraint[]{entityFetch(attributeContentAll())},
						filterBy(entityPrimaryKeyInSet(2))
					))))).orElseThrow());
			}
		}

		/** Different valid projections must report a difference without asking either view for an unfetched key. */
		@Test
		@DisplayName("Compares different attribute and associated-data projections without fetch-context errors")
		void shouldCompareDifferentProjections() {
			updateCatalog(session -> {
				session.defineEntitySchema(PRODUCT)
					.withAttribute("code", String.class)
					.withAttribute("name", String.class).updateVia(session);
				session.getEntity(PRODUCT, 1, attributeContentAll(), associatedDataContentAll())
					.orElseThrow().openForWrite()
					.setAttribute("code", "one").setAttribute("name", "One")
					.setAssociatedData("code", "one").setAssociatedData("name", "One").upsertVia(session);
			});
			try (final EvitaSessionContract session = readOnlySession()) {
				assertContentDifference(
					fetchBody(session, entityFetch(attributeContent("code"))).orElseThrow(),
					fetchBody(session, entityFetch(attributeContent("name"))).orElseThrow());
				assertContentDifference(
					fetchBody(session, entityFetch(associatedDataContent("code"))).orElseThrow(),
					fetchBody(session, entityFetch(associatedDataContent("name"))).orElseThrow());
			}
		}

		/** Materialized price maps may contain equal numbers of prices with disjoint currency keys. */
		@Test
		@DisplayName("Compares prices held by different currency projections")
		void shouldCompareDifferentPriceProjections() {
			updateCatalog(session -> {
				session.defineEntitySchema(PRODUCT).withPrice().updateVia(session);
				session.getEntity(PRODUCT, 1, priceContentAll()).orElseThrow().openForWrite()
					.setPrice(1, "basic", Currency.getInstance("USD"),
						BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, true)
					.setPrice(1, "basic", Currency.getInstance("EUR"),
						BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, true)
					.upsertVia(session);
			});
			try (final EvitaSessionContract session = readOnlySession()) {
				final SealedEntity first = session.query(Query.query(collection(PRODUCT),
					filterBy(entityPrimaryKeyInSet(1), priceInCurrency("USD")),
					require(entityFetch(priceContentRespectingFilter()))), SealedEntity.class)
				.getRecordData().getFirst();
				final SealedEntity second = session.query(Query.query(collection(PRODUCT),
					filterBy(entityPrimaryKeyInSet(1), priceInCurrency("EUR")),
					require(entityFetch(priceContentRespectingFilter()))), SealedEntity.class)
				.getRecordData().getFirst();
				assertEquals(1, first.getPrices().size());
				assertEquals(1, second.getPrices().size());
				assertContentDifference(first, second);
			}
		}

		/** Histogram equality excludes both referenced anchor entities. */
		@Test
		@DisplayName("Detects changed histogram anchors despite equal public responses")
		void shouldCompareHistogramAnchors() {
			try (final EvitaSessionContract session = readOnlySession()) {
				final SealedEntity first = session.getEntity(PRODUCT, 1).orElseThrow();
				final SealedEntity second = session.getEntity(PRODUCT, 2).orElseThrow();
				final Bucket[] buckets = {new Bucket(BigDecimal.ONE, 1, true, BigDecimal.valueOf(100))};
				assertContentDifference(first, first,
					new AttributeHistogram(Map.of("code", new Histogram(buckets, BigDecimal.TEN, first, first))),
					new AttributeHistogram(Map.of("code", new Histogram(buckets, BigDecimal.TEN, second, first)))
				);
			}
		}

		/** Hierarchy equality compares its node entities by identity only. */
		@Test
		@DisplayName("Detects changed hierarchy node bodies despite equal public responses")
		void shouldCompareHierarchyBodies() {
			prepareBodies();
			try (final EvitaSessionContract session = readOnlySession()) {
				final SealedEntity product = session.getEntity(PRODUCT, 1).orElseThrow();
				assertContentDifference(product, product,
					selfHierarchy(fetchEntity(session, "category", 1, referenceContent("target"))),
					selfHierarchy(fetchEntity(session, "category", 1, withoutTargets())));
			}
		}

		/** Facet statistics compare their facet entity by identity only. */
		@Test
		@DisplayName("Detects changed facet bodies in a reference summary despite equal public responses")
		void shouldCompareSummaryFacetBodies() {
			prepareBodies();
			try (final EvitaSessionContract session = readOnlySession()) {
				final SealedEntity product = session.getEntity(PRODUCT, 1).orElseThrow();
				assertContentDifference(product, product,
					summary(null, fetchEntity(session, "category", 1, referenceContent("target"))),
					summary(null, fetchEntity(session, "category", 1, withoutTargets())));
			}
		}

		/** Group statistics compare their group entity by identity only. */
		@Test
		@DisplayName("Detects changed group bodies in a reference summary despite equal public responses")
		void shouldCompareSummaryGroupBodies() {
			prepareBodies();
			try (final EvitaSessionContract session = readOnlySession()) {
				final SealedEntity product = session.getEntity(PRODUCT, 1).orElseThrow();
				final EntityReference facet = new EntityReference("category", 1);
				assertContentDifference(product, product,
					summary(fetchEntity(session, "group", 1, referenceContent("target")), facet),
					summary(fetchEntity(session, "group", 1, withoutTargets()), facet));
			}
		}

		/** A reference compares its fetched group entity by identity only. */
		@Test
		@DisplayName("Detects changed group bodies of references despite equal public responses")
		void shouldCompareReferenceGroupBodies() {
			prepareBodies();
			try (final EvitaSessionContract session = readOnlySession()) {
				assertContentDifference(
					fetchBody(session, entityFetch(referenceContent("categories",
						entityGroupFetch(referenceContent("target"))))).orElseThrow(),
					fetchBody(session, entityFetch(referenceContent("categories",
						entityGroupFetch(withoutTargets())))).orElseThrow()
				);
			}
		}

		/** An entity compares its fetched parent by identity only. */
		@Test
		@DisplayName("Detects changed parent bodies despite equal public responses")
		void shouldCompareParentBodies() {
			prepareBodies();
			updateCatalog(session -> session.upsertEntity(session.createNewEntity("category", 5).setParent(1)));
			try (final EvitaSessionContract session = readOnlySession()) {
				assertContentDifference(
					fetchEntity(session, "category", 5, hierarchyContent(entityFetch(referenceContent("target")))),
					fetchEntity(session, "category", 5, hierarchyContent(entityFetch(withoutTargets())))
				);
			}
		}

		/** Telemetry describes how a response was computed, not what it contains. */
		@Test
		@DisplayName("Ignores query telemetry present on one side only or differing between the sides")
		void shouldIgnoreQueryTelemetry() {
			try (final EvitaSessionContract session = readOnlySession()) {
				final SealedEntity product = session.getEntity(PRODUCT, 1).orElseThrow();
				final QueryTelemetry planned = new QueryTelemetry(QueryPhase.OVERALL);
				planned.addStep(QueryPhase.PLANNING);
				final EvitaResponse<SealedEntity> plain = bodyResponse(product);
				final EvitaResponse<SealedEntity> profiled = bodyResponse(
					product, new QueryTelemetry(QueryPhase.OVERALL)
				);
				final EvitaResponse<SealedEntity> otherProfile = bodyResponse(product, planned);
				assertAll(
					() -> assertTrue(QueryResponseComparator.matches(profiled, plain)),
					() -> assertTrue(QueryResponseComparator.matches(plain, profiled)),
					() -> assertTrue(QueryResponseComparator.matches(profiled, otherProfile))
				);
			}
		}

		/** An extra result other than telemetry present on one side only is a difference in either direction. */
		@Test
		@DisplayName("Detects an extra result present on one side only")
		void shouldDetectOneSidedExtraResult() {
			try (final EvitaSessionContract session = readOnlySession()) {
				final SealedEntity product = session.getEntity(PRODUCT, 1).orElseThrow();
				final EvitaResponse<SealedEntity> plain = bodyResponse(product);
				final EvitaResponse<SealedEntity> withHierarchy = bodyResponse(
					product, new Hierarchy(Map.of(), Map.of())
				);
				assertAll(
					() -> assertFalse(QueryResponseComparator.matches(plain, withHierarchy)),
					() -> assertFalse(QueryResponseComparator.matches(withHierarchy, plain))
				);
			}
		}

		/** Equal rows can still be cut by a different page size or strip limit. */
		@Test
		@DisplayName("Compares page size and strip limit of reference chunks holding the same rows")
		void shouldCompareSameClassChunkMetadata() {
			prepareBodies();
			try (final EvitaSessionContract session = readOnlySession()) {
				assertAll(
					() -> assertContentDifference(
						fetchBody(session, entityFetch(referenceContent("categories", page(1, 4)))).orElseThrow(),
						fetchBody(session, entityFetch(referenceContent("categories", page(1, 5)))).orElseThrow()),
					() -> assertContentDifference(
						fetchBody(session, entityFetch(referenceContent("categories", strip(0, 4)))).orElseThrow(),
						fetchBody(session, entityFetch(referenceContent("categories", strip(0, 5)))).orElseThrow())
				);
			}
		}
	}

	/** Counts all execution roots, including accidentally nested verification executions. */
	private static int countExecutionSteps(@Nonnull QueryTelemetry telemetry) {
		int count = telemetry.getOperation() == QueryPhase.EXECUTION ? 1 : 0;
		for (final QueryTelemetry step : telemetry.getSteps()) {
			count += countExecutionSteps(step);
		}
		return count;
	}

	/** Requires every sort annotation to belong to an actual execution sort step, and counts them. */
	private static int countSortAnnotations(@Nonnull QueryTelemetry telemetry) {
		int count = 0;
		for (final String argument : telemetry.getArguments()) {
			if (argument.startsWith("sortResolution=")) {
				assertEquals(QueryPhase.EXECUTION_SORT_AND_SLICE, telemetry.getOperation(),
					"Verification must not annotate the real planning context");
				count++;
			}
		}
		for (final QueryTelemetry step : telemetry.getSteps()) {
			count += countSortAnnotations(step);
		}
		return count;
	}

	/** Creates independent hierarchical facets and groups whose reference filters query the target entities. */
	private void prepareBodies() {
		this.evita.updateCatalog(CATALOG, session -> {
			session.defineEntitySchema("target").withoutGeneratedPrimaryKey()
				.withAttribute("code", String.class, whichIs -> whichIs.filterable())
				.updateVia(session);
			session.upsertEntity(session.createNewEntity("target", 1).setAttribute("code", "one"));
			session.upsertEntity(session.createNewEntity("target", 2).setAttribute("code", "two"));
			for (final String type : new String[]{"category", "group"}) {
				session.defineEntitySchema(type).withoutGeneratedPrimaryKey().withHierarchy()
					.withReferenceToEntity("target", "target", Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(Scope.LIVE))
					.updateVia(session);
				for (int i = 1; i <= 4; i++) {
					session.upsertEntity(session.createNewEntity(type, i).setReference("target", 1));
				}
			}
			session.defineEntitySchema(PRODUCT)
				.withReferenceToEntity("categories", "category", Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(Scope.LIVE).facetedInScope(Scope.LIVE)
						.withGroupTypeRelatedToEntity("group"))
				.updateVia(session);
			for (int i = 1; i <= 2; i++) {
				final EntityBuilder product = session.getEntity(
					PRODUCT, i, entityFetchAllContent()).orElseThrow().openForWrite();
				for (int j = 1; j <= 4; j++) {
					final int group = 1;
					product.setReference("categories", j, whichIs -> whichIs.setGroup(group));
				}
				session.upsertEntity(product);
			}
		});
	}

	/** Returns a fetched body whose references require nested planning. */
	@Nonnull
	private static EntityFetch filteredBody() {
		return entityFetch(referenceContent("target", filterBy(
			entityHaving(not(entityPrimaryKeyInSet(2)), attributeEquals("code", "one"))
		)));
	}

	/** Runs the flagged query, checks that it moved every audited counter, then audits the baseline path alone. */
	private void assertBodyFetchKeyFree(@Nonnull String type, @Nonnull RequireConstraint requirement) {
		final Catalog catalog = catalog();
		final EntityCollection targets = catalog.getCollectionForEntityInternal("target").orElseThrow();
		final List<InputBlindFormula> supplied = new ArrayList<>();
		final SupersetIndex index = new SupersetIndex("target", () -> {
			final InputBlindFormula formula = new InputBlindFormula(1);
			supplied.add(formula);
			return new OrFormula(formula, leaf(2));
		});
		index.insertFilterAttribute(null, targets.getSchema().getAttribute("code").orElseThrow(), Set.of(),
			null, "one", 1, false);
		targets.addIndex(index);
		try (final EvitaSessionContract session = readOnlySession()) {
			((EvitaInternalSessionContract) session).execute(internal -> {
				final Query query = Query.query(collection(type), require(
					requirement, debug(DebugMode.VERIFY_POSSIBLE_CACHING_TREES), queryTelemetry()
				));
				final EntityCollection collection = catalog.getCollectionForEntityInternal(type).orElseThrow();
				final QueryPlanningContext context = collection.createQueryContext(
					new EvitaRequest(query, OffsetDateTime.now(), EntityReference.class, null), internal
				);
				final long activityBeforeQuery = index.getActivity().getQueryCount();
				final long requestedBeforeQuery = requestedCount(targets);
				QueryPlanner.planQuery(context).execute();
				assertAll(
					() -> assertTrue(supplied.stream().anyMatch(it -> it.keyRequests > 0),
						"The flagged query must request keys of the nested body filter"),
					() -> assertTrue(index.getActivity().getQueryCount() > activityBeforeQuery,
						"The flagged query must record activity of the nested body filter"),
					() -> assertTrue(requestedCount(targets) > requestedBeforeQuery,
						"The flagged query must count capabilities of the nested body filter")
				);
				supplied.clear();
				final long activityBefore = index.getActivity().getQueryCount();
				final long requestedBefore = requestedCount(targets);
				final QueryPlan baseline = QueryPlanner.planQuery(context.createVerificationSibling());
				baseline.execute(RandomUtils.getFrozenRandom());
				assertFalse(supplied.isEmpty(), "The nested body filter must reach the counting index");
				assertAll(
					() -> assertEquals(0, supplied.stream().mapToInt(it -> it.keyRequests).sum()),
					() -> assertEquals(activityBefore, index.getActivity().getQueryCount()),
					() -> assertEquals(requestedBefore, requestedCount(targets))
				);
			});
		}
	}

	/** Reads the collection's live capability counters without changing them. */
	private static long requestedCount(@Nonnull EntityCollection collection) {
		return collection.getUsageRegistry().listUsages().stream()
			.mapToLong(it -> it.usage().getRequestedCount()).sum();
	}

	/** Compares independently executed nested sorters, and checks the unflagged result still varies. */
	private void assertRandomOrdering(
		@Nonnull String type, @Nonnull RequireConstraint requirement,
		@Nonnull Class<? extends EvitaResponseExtraResult> extraType, @Nonnull DebugMode mode
	) {
		// Reduced plans are eligible only when their owners cover at most half the collection.
		this.evita.updateCatalog(CATALOG, session -> {
			for (int i = 3; i <= 4; i++) {
				session.upsertEntity(session.createNewEntity(PRODUCT, i));
			}
			for (int i = 5; i <= 8; i++) {
				session.upsertEntity(session.createNewEntity("category", i).setParent(1));
			}
		});
		try (final EvitaSessionContract session = this.evita.createReadOnlySession(CATALOG)) {
			final Query query = Query.query(collection(type),
				filterBy(referenceHaving(PRODUCT.equals(type) ? "categories" : "target", entityPrimaryKeyInSet(1))),
				require(requirement, debug(mode)));
			((EvitaInternalSessionContract) session).execute(internal -> {
				final Catalog catalog = (Catalog) this.evita.getCatalogInstanceOrThrowException(CATALOG);
				final EntityCollection collection = catalog.getCollectionForEntityInternal(type).orElseThrow();
				final QueryPlanningContext context = collection.createQueryContext(
					new EvitaRequest(query, OffsetDateTime.now(), EntityReference.class, null), internal);
				final IndexSelectionVisitor visitor = new IndexSelectionVisitor(context);
				context.getFilterBy().accept(visitor);
				assertTrue(visitor.getTargetIndexes().stream()
					.filter(io.evitadb.core.query.indexSelection.TargetIndexes::isEligibleForSeparateQueryPlan)
					.count() > 1,
					"Alternative verification must compare multiple plans");
			});
			for (int i = 0; i < 12; i++) {
				assertDoesNotThrow(() -> session.query(query, EntityReference.class));
			}
			final List<Object> results = new ArrayList<>();
			for (int i = 0; i < 12; i++) {
				results.add(session.query(
					Query.query(collection(type), query.getFilterBy(), require(requirement)), EntityReference.class
				).getExtraResult(extraType));
			}
			assertTrue(results.stream().distinct().count() > 1,
				"Ordinary nested random ordering must still vary");
		}
	}

	/** Uses the same named-instance constructor as the server's reference projection. */
	@Nonnull
	private static ReferenceContent namedReferences(
		@Nonnull String instance, @Nonnull String reference, @Nonnull RequireConstraint[] requirements,
		@Nonnull Constraint<?>... children
	) {
		return new ReferenceContent(instance, ManagedReferencesBehaviour.ANY,
			new String[]{reference}, requirements, children);
	}

	/** Fetches a body view while retaining its stored identity and version. */
	@Nonnull
	private static Optional<SealedEntity> fetchBody(
		@Nonnull EvitaSessionContract session, @Nonnull EntityFetch requirements
	) {
		return session.getEntity(PRODUCT, 1, requirements.getRequirements());
	}

	/** Wraps real materialized bodies in responses with identical page identity and order. */
	private static void assertContentDifference(@Nonnull SealedEntity first, @Nonnull SealedEntity second,
		@Nonnull EvitaResponseExtraResult... extras) {
		final EvitaResponse<SealedEntity> left = bodyResponse(first,
			extras.length == 0 ? new EvitaResponseExtraResult[0] : new EvitaResponseExtraResult[]{extras[0]});
		final EvitaResponse<SealedEntity> right = bodyResponse(second,
			extras.length == 0 ? new EvitaResponseExtraResult[0] : new EvitaResponseExtraResult[]{extras[1]});
		assertEquals(left, right, "The witness must be invisible to public response equality");
		assertTrue(QueryResponseComparator.matches(left, left));
		assertFalse(QueryResponseComparator.matches(left, right));
		assertFalse(QueryResponseComparator.matches(right, left));
	}

	/** Places a real body view in a response without altering its identity or version. */
	@Nonnull
	private static <T extends Serializable> EvitaResponse<T> bodyResponse(
		@Nonnull T body, @Nonnull EvitaResponseExtraResult... extras
	) {
		return new EvitaEntityResponse<>(Query.query(collection(PRODUCT)),
			new PaginatedList<>(1, 20, 1, List.of(body)), new int[]{1}, extras);
	}

	/** Builds a session-optional real planning context over a supplied global index and both verification flags. */
	@Nonnull
	private QueryPlanningContext context(@Nonnull SupersetIndex index, boolean verify) {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstanceOrThrowException(CATALOG);
		final EntityCollection collection = catalog.getCollectionForEntityInternal(PRODUCT).orElseThrow();
		final Query query = Query.query(
			collection(PRODUCT), filterBy(entityPrimaryKeyInSet(1, 2)), orderBy(random()),
			require(debug(verify ? new DebugMode[]{
				DebugMode.VERIFY_POSSIBLE_CACHING_TREES, DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS
			} : new DebugMode[]{DebugMode.PREFER_INDEX_SCAN}))
		);
		return new QueryPlanningContext(
			catalog, collection, null, new EvitaRequest(query, OffsetDateTime.now(), EntityReference.class, null),
			Map.of(index.getIndexKey(), index), Map.of(index.getPrimaryKey(), index), NoCacheSupervisor.INSTANCE
		);
	}

	/** Supplies an independent constant operand. */
	@Nonnull
	private static Formula leaf(@Nonnull int... primaryKeys) {
		return new ConstantFormula(new BaseBitmap(primaryKeys));
	}

	/** Fetches a body of any test entity with the passed requirements. */
	@Nonnull
	private static SealedEntity fetchEntity(
		@Nonnull EvitaSessionContract session, @Nonnull String type, int primaryKey,
		@Nonnull EntityContentRequire... requirements
	) {
		return session.getEntity(type, primaryKey, requirements).orElseThrow();
	}

	/** Requests target references through a filter dropping the only target the fixture bodies reference. */
	@Nonnull
	private static ReferenceContent withoutTargets() {
		return referenceContent("target", filterBy(entityPrimaryKeyInSet(2)));
	}

	/** Wraps one node in a self hierarchy. */
	@Nonnull
	private static Hierarchy selfHierarchy(@Nonnull EntityClassifier node) {
		return new Hierarchy(Map.of("tree", List.of(new LevelInfo(node, true, 1, 0, List.of()))), Map.of());
	}

	/** Builds a summary of one facet of the `categories` reference, in the passed group or ungrouped. */
	@Nonnull
	private static ReferenceSummary summary(@Nullable EntityClassifier group, @Nonnull EntityClassifier facet) {
		return new ReferenceSummary(List.of(new ReferenceGroupStatistics(
			"categories", group, 1, Map.of(1, new FacetStatistics(facet, false, 1, null))
		)));
	}

	/**
	 * Supervisor a caller may hand to a nested context. It returns everything unchanged, so only the sibling's own
	 * override can make a nested context of the sibling report the supervisor that never caches.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class PassThroughCacheSupervisor implements CacheSupervisor {
		/** Returns the formula unchanged. */
		@Nonnull
		@Override
		public <T extends Formula> T analyse(
			@Nonnull EvitaSessionContract evitaSession, @Nonnull String entityType, @Nonnull T filterFormula
		) {
			return filterFormula;
		}

		/** Returns the computer unchanged. */
		@Nonnull
		@Override
		public <U, T extends CacheableEvitaResponseExtraResultComputer<U>> EvitaResponseExtraResultComputer<U> analyse(
			@Nonnull EvitaSessionContract evitaSession, @Nonnull String entityType, @Nonnull T extraResultComputer,
			@Nonnull QueryExecutionContext context
		) {
			return extraResultComputer;
		}

		/** Holds no resources. */
		@Override
		public void close() {
			// nothing to release
		}
	}

	/**
	 * Entity projection whose ordinary equality retains the entity API's identity semantics.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private record MaterializedEntityView(@Nonnull EntityContract entity) implements WithEntityContract, Serializable {
	}

	/**
	 * Index seam that supplies fresh formula instances on every planning pass over the same two primary keys.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class SupersetIndex extends GlobalEntityIndex {
		/** Creates each pass's independent formula tree. */
		private final Supplier<Formula> formulas;

		/** Initializes the index's actual primary-key set and its formula supplier. */
		private SupersetIndex(@Nonnull Supplier<Formula> formulas) {
			this(PRODUCT, formulas);
		}

		/** Supplies an instrumented index for a nested collection. */
		private SupersetIndex(@Nonnull String type, @Nonnull Supplier<Formula> formulas) {
			super(1, type, new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE), true);
			this.formulas = formulas;
			insertPrimaryKeyIfMissing(1);
			insertPrimaryKeyIfMissing(2);
		}

		/** Supplies the tree before the production optimizer and deduplicator run. */
		@Nonnull
		@Override
		public Formula getAllPrimaryKeysFormula() {
			return this.formulas.get();
		}
	}

	/**
	 * Deliberately omits the primary key it returns from its identity, producing equal keys for different results.
	 *
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class InputBlindFormula extends AbstractFormula {
		/** Stable discriminator generated from the formula's binary name. */
		private static final long CLASS_ID = -3944212450679939487L;
		/** Computation input deliberately absent from the identity. */
		private final int primaryKey;
		/** Counts identity evaluation to prove the baseline never requests a key. */
		private int keyRequests;

		/** Captures the input and initializes the leaf's cost. */
		private InputBlindFormula(int primaryKey) {
			this.primaryKey = primaryKey;
			initFields();
		}

		/** Deliberately drops the computation input. */
		@Nonnull
		@Override
		protected IdentityRecord identity() {
			this.keyRequests++;
			return new Identity();
		}

		/** Returns this formula's own discriminator. */
		@Override
		protected long getClassId() {
			return CLASS_ID;
		}

		/** Produces the result determined by the omitted input. */
		@Nonnull
		@Override
		protected Bitmap computeInternal() {
			return new BaseBitmap(this.primaryKey);
		}

		/** The leaf always returns one primary key. */
		@Override
		public int getEstimatedCardinality() {
			return 1;
		}

		/** Gives this small leaf a nonzero cost. */
		@Override
		public long getOperationCost() {
			return 1;
		}

		/** A terminal formula has no children to replace. */
		@Nonnull
		@Override
		public Formula getCloneWithInnerFormulas(@Nonnull Formula... innerFormulas) {
			assertEquals(0, innerFormulas.length);
			return this;
		}

		/**
		 * Empty identity used solely to prove that response verification catches an omitted input.
		 *
		 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
		 */
		private record Identity() implements IdentityRecord {
			/** Retains the correct class discriminator while omitting the primary key. */
			@Override
			public long classId() {
				return CLASS_ID;
			}
		}
	}
}
