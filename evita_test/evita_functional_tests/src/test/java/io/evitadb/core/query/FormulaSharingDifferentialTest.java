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
import io.evitadb.api.proxy.WithEntityContract;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.EvitaEntityResponse;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.Hierarchy;
import io.evitadb.api.requestResponse.extraResult.Histogram;
import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
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
import java.io.IOException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
		System.out.println("Non-vacuity witness: InconsistentResultsException: " + error.getPublicMessage());
		System.out.println("Merged defective formulas: 1 and 2; equal key hash=" + first.getKey().getHash64());
	}

	/** A sibling carries suppression into child contexts, even if their caller supplies a telemetry root. */
	@Test
	@DisplayName("Inherits key-free verification state in nested contexts")
	void shouldInheritVerificationStateInNestedContexts() {
		final SupersetIndex index = new SupersetIndex(() -> new OrFormula(leaf(1), leaf(2)));
		final QueryPlanningContext context = context(index, true);
		final QueryPlanningContext sibling = context.createVerificationSibling();
		final QueryPlanningContext nested = new QueryPlanningContext(
			sibling, (Catalog) this.evita.getCatalogInstanceOrThrowException(CATALOG),
			sibling.getEntityCollectionOrThrowException(PRODUCT, "test nested planning"),
			null, sibling.getEvitaRequest(), new QueryTelemetry(QueryPhase.OVERALL),
			Map.of(index.getIndexKey(), index), Map.of(index.getPrimaryKey(), index), NoCacheSupervisor.INSTANCE
		);
		assertTrue(context.isFormulaSharingVerificationRequired());
		assertTrue(context.isAlternativeIndexVerificationRequired());
		for (final QueryPlanningContext verification : List.of(sibling, nested)) {
			assertTrue(verification.isVerificationSibling());
			assertFalse(verification.isFormulaSharingEnabled());
			assertFalse(verification.isFormulaSharingVerificationRequired());
			assertFalse(verification.isAlternativeIndexVerificationRequired());
			assertFalse(verification.isTelemetryPlanCollected());
			assertNull(verification.getQueryFinishedEvent());
			assertSame(NoCacheSupervisor.INSTANCE, verification.getCacheSupervisor());
		}
		assertNull(QueryPlanner.planQuery(nested).getFilter().getMemoizedResult());
		assertNull(QueryPlanner.planNestedQuery(nested, () -> "nested verification baseline")
			.getFilter().getMemoizedResult());
		assertEquals(0, index.getActivity().getQueryCount());
	}

	/** Both verification modes must leave the session's event and telemetry for the real execution. */
	@ParameterizedTest
	@EnumSource(value = DebugMode.class, names = {"VERIFY_POSSIBLE_CACHING_TREES", "VERIFY_ALTERNATIVE_INDEX_RESULTS"})
	@DisplayName("Records one finished event and one execution profile with either verification mode")
	void shouldRecordOnlyRealExecution(@Nonnull DebugMode mode) throws IOException {
		this.evita.updateCatalog(CATALOG, session -> {
			session.defineEntitySchema(PRODUCT)
				.withAttribute("rank", Long.class, attribute -> attribute.sortable().nullable()).updateVia(session);
			for (int i = 1; i <= 2; i++) {
				session.getEntity(PRODUCT, i, attributeContentAll()).orElseThrow().openForWrite()
					.setAttribute("rank", (long) i)
					.upsertVia(session);
			}
		});
		try (final EvitaSessionContract session = this.evita.createReadOnlySession(CATALOG);
		     final Recording recording = new Recording()) {
			recording.enable("io.evitadb.query.Finished");
			recording.start();
			final Catalog catalog = (Catalog) this.evita.getCatalogInstanceOrThrowException(CATALOG);
			final EntityCollection collection = catalog.getCollectionForEntityInternal(PRODUCT).orElseThrow();
			final Query query = Query.query(
				collection(PRODUCT), filterBy(entityPrimaryKeyInSet(1, 2)), orderBy(attributeNatural("rank")),
				require(debug(mode, DebugMode.PREFER_INDEX_SCAN), queryTelemetry())
			);
			final QueryPlanningContext context = ((EvitaInternalSessionContract) session).execute(internal -> {
				return collection.createQueryContext(
				new EvitaRequest(query, OffsetDateTime.now(), EntityReference.class, null), internal);
			});
			assertNotNull(context.getQueryFinishedEvent());
			final QueryPlan plan = QueryPlanner.planQuery(context);
			assertAll(
				() -> assertNull(context.getQueryFinishedEvent().getPrefetched(), "Dry runs must not finish the event"),
				() -> assertEquals(0, context.getQueryFinishedEvent().getPlanned(),
					"Dry runs must not start the event"),
				() -> assertEquals(0, countExecutionSteps(context.getTelemetryRoot()),
					"Dry runs must not add execution steps")
			);
			assertEquals(0, countSortAnnotations(context.getTelemetryRoot()));
			final EvitaResponse<EntityReference> response = plan.execute();
			assertEquals(1, countExecutionSteps(response.getExtraResult(QueryTelemetry.class)), mode.toString());
			assertTrue(countSortAnnotations(response.getExtraResult(QueryTelemetry.class)) > 0,
				"The real execution must record its sort strategy");
			recording.stop();
			final Path dump = Path.of(System.getProperty("java.io.tmpdir"), "finished-" + mode + ".jfr");
			recording.dump(dump);
			assertEquals(1L, RecordingFile.readAllEvents(dump).stream()
				.filter(it -> it.getEventType().getName().equals("io.evitadb.query.Finished"))
				.filter(it -> it.getString("catalogName").equals(CATALOG)).count(), mode.toString());
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

	/** Four shuffled facets give a false-pass probability of (1/24)^12 for an unfrozen sorter. */
	@ParameterizedTest
	@EnumSource(value = DebugMode.class, names = {"VERIFY_POSSIBLE_CACHING_TREES", "VERIFY_ALTERNATIVE_INDEX_RESULTS"})
	@DisplayName("Freezes facet ordering for both verification modes and preserves ordinary randomness")
	void shouldFreezeSummaryRandomOrdering(@Nonnull DebugMode mode) {
		prepareBodies();
		assertRandomOrdering(PRODUCT, referenceSummaryOfReference(
			"categories", FacetStatisticsDepth.COUNTS, orderBy(random())
		), ReferenceSummary.class, mode);
	}

	/** At least four shuffled hierarchy roots give a false-pass bound of (1/24)^12. */
	@ParameterizedTest
	@EnumSource(value = DebugMode.class, names = {"VERIFY_POSSIBLE_CACHING_TREES", "VERIFY_ALTERNATIVE_INDEX_RESULTS"})
	@DisplayName("Freezes hierarchy ordering for both verification modes and preserves ordinary randomness")
	void shouldFreezeHierarchyRandomOrdering(@Nonnull DebugMode mode) {
		prepareBodies();
		assertRandomOrdering("category", hierarchyOfSelf(orderBy(random()), fromRoot("tree")), Hierarchy.class, mode);
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

	/** Runs the flagged query and then independently audits the same baseline production path. */
	private void assertBodyFetchKeyFree(@Nonnull String type, @Nonnull RequireConstraint requirement) {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstanceOrThrowException(CATALOG);
		final EntityCollection products = catalog.getCollectionForEntityInternal("target").orElseThrow();
		final List<InputBlindFormula> supplied = new ArrayList<>();
		final SupersetIndex index = new SupersetIndex("target", () -> {
			final InputBlindFormula formula = new InputBlindFormula(1);
			supplied.add(formula);
			return new OrFormula(formula, leaf(2));
		});
		index.insertFilterAttribute(null, products.getSchema().getAttribute("code").orElseThrow(), Set.of(),
			null, "one", 1, false);
		products.addIndex(index);
		try (final EvitaSessionContract session = this.evita.createReadOnlySession(CATALOG)) {
			((EvitaInternalSessionContract) session).execute(internal -> {
				final Query query = Query.query(collection(type), require(
					requirement, debug(DebugMode.VERIFY_POSSIBLE_CACHING_TREES), queryTelemetry()
				));
				final EntityCollection collection = catalog.getCollectionForEntityInternal(type).orElseThrow();
				final QueryPlanningContext context = collection.createQueryContext(
					new EvitaRequest(query, OffsetDateTime.now(), EntityReference.class, null), internal
				);
				QueryPlanner.planQuery(context).execute();
				supplied.clear();
				final long activityBefore = index.getActivity().getQueryCount();
				final long requestedBefore = requestedCount(products);
				final QueryPlan baseline = QueryPlanner.planQuery(context.createVerificationSibling());
				baseline.execute(RandomUtils.getFrozenRandom());
				assertFalse(supplied.isEmpty(), "The nested body filter must reach the counting index");
				assertAll(
					() -> assertEquals(0, supplied.stream().mapToInt(it -> it.keyRequests).sum()),
					() -> assertEquals(activityBefore, index.getActivity().getQueryCount()),
					() -> assertEquals(requestedBefore, requestedCount(products))
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

	/** Public response equality deliberately ignores the filtered references of an entity body. */
	@Test
	@DisplayName("Detects changed filtered references despite equal public responses")
	void shouldCompareFilteredReferences() {
		prepareBodies();
		try (final EvitaSessionContract session = this.evita.createReadOnlySession(CATALOG)) {
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
		try (final EvitaSessionContract session = this.evita.createReadOnlySession(CATALOG)) {
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
		try (final EvitaSessionContract session = this.evita.createReadOnlySession(CATALOG)) {
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
		this.evita.updateCatalog(CATALOG, session -> {
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
		try (final EvitaSessionContract session = this.evita.createReadOnlySession(CATALOG)) {
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
		try (final EvitaSessionContract session = this.evita.createReadOnlySession(CATALOG)) {
			assertContentDifference(
				fetchBody(session, entityFetch(referenceContent("categories", page(1, 2)))).orElseThrow(),
				fetchBody(session, entityFetch(referenceContent("categories", strip(0, 2)))).orElseThrow()
			);
		}
	}

	/** Histogram equality excludes both referenced anchor entities. */
	@Test
	@DisplayName("Detects changed histogram anchors despite equal public responses")
	void shouldCompareHistogramAnchors() {
		try (final EvitaSessionContract session = this.evita.createReadOnlySession(CATALOG)) {
			final SealedEntity first = session.getEntity(PRODUCT, 1).orElseThrow();
			final SealedEntity second = session.getEntity(PRODUCT, 2).orElseThrow();
			final Bucket[] buckets = {new Bucket(BigDecimal.ONE, 1, true, BigDecimal.valueOf(100))};
			assertContentDifference(first, first,
				new AttributeHistogram(Map.of("code", new Histogram(buckets, BigDecimal.TEN, first, first))),
				new AttributeHistogram(Map.of("code", new Histogram(buckets, BigDecimal.TEN, second, first)))
			);
		}
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
