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

package io.evitadb.api.functional.fulltext;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.TextMatches;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.query.QueryPlan;
import io.evitadb.core.query.QueryPlanner;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.fulltext.FulltextFormula;
import io.evitadb.core.query.algebra.reference.ReferencedOwnerExistenceFormula;
import io.evitadb.core.query.filter.FilterByVisitor;
import io.evitadb.core.query.algebra.fulltext.FulltextScoreAccessor;
import io.evitadb.core.query.algebra.fulltext.FulltextScoreCombiner;
import io.evitadb.core.query.algebra.fulltext.ReferenceOwnerFulltextScoreAccessor;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder;
import io.evitadb.core.query.algebra.utils.visitor.FormulaFinder.LookUp;
import io.evitadb.core.query.sort.fulltext.RelevanceTextSorter;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartitionIndexEntries;
import io.evitadb.index.fulltext.FulltextFieldKey;
import io.evitadb.index.fulltext.FulltextScoringPolicy;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.PlanPreference;
import net.openhft.hashing.LongHashFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.HIERARCHY;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.*;

/** Real-catalog regressions for processing-schema policies and reference score registration. */
@DisplayName("Fulltext policies across processing schemas")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(FULLTEXT)
@Tag(HIERARCHY)
class FulltextProcessingSchemaFunctionalTest implements EvitaTestSupport {
	private static final String OWNER = "product";
	private static final String TARGET = "category";
	private static final String REFERENCE = "placements";
	private static final String VIRTUAL_REFERENCE = "virtualPlacements";
	private TestPaths paths;
	private Evita evita;

	/** The owner strongly weights the shared name; the target gives it zero and weights its disjoint label. */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("FulltextProcessingSchemaFunctionalTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.defineEntitySchema(TARGET).withoutGeneratedPrimaryKey().withHierarchy().withLocale(Locale.ENGLISH)
				.withAttribute("name", String.class, a -> a.localized().searchable().searchWeight(0))
				.withAttribute("label", String.class, a -> a.localized().searchable().searchWeight(4))
				.withAttribute("enabled", Boolean.class, a -> a.filterable())
				.updateVia(session);
			session.defineEntitySchema(OWNER).withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
				.withAttribute("name", String.class, a -> a.localized().searchable().searchWeight(100))
				.withAttribute("ownerLabel", String.class, a -> a.localized().searchable().searchWeight(80))
				.withReferenceToEntity(REFERENCE, TARGET, Cardinality.ZERO_OR_MORE,
					r -> r.indexedForFilteringAndPartitioningInScope(Scope.LIVE, Scope.ARCHIVED).indexedWithComponents(
						ReferenceIndexedComponents.REFERENCED_ENTITY,
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY).withGroupTypeRelatedToEntity(TARGET))
				.withReferenceToEntity(VIRTUAL_REFERENCE, TARGET, Cardinality.ZERO_OR_MORE,
					r -> r.indexedWithComponents(ReferenceIndexedComponents.REFERENCED_ENTITY,
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY).withGroupTypeRelatedToEntity(TARGET))
				.updateVia(session);
			session.getEntitySchemaOrThrowException(TARGET).openForWrite()
				.withReflectedReferenceToEntity("products", OWNER, REFERENCE).updateVia(session);
			for (int pk = 1; pk <= 2; pk++) {
				final int key = pk;
				session.upsertEntity(session.createNewEntity(TARGET, pk)
					.setAttribute("name", Locale.ENGLISH, pk == 1 ? "green" : "amber")
					.setAttribute("label", Locale.ENGLISH, pk == 2 ? "green" : "amber")
					.setAttribute("enabled", true));
				session.upsertEntity(session.createNewEntity(OWNER, pk)
					.setAttribute("name", Locale.ENGLISH, "amber")
					.setAttribute("ownerLabel", Locale.ENGLISH, "amber")
					.setReference(REFERENCE, pk, r -> r.setGroup(TARGET, key))
					.setReference(VIRTUAL_REFERENCE, pk, r -> r.setGroup(TARGET, key)));
			}
			session.goLiveAndClose();
		});
	}

	/** Releases the catalog and its storage. */
	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/** Parent filtering translates the hierarchy schema even without any relevance ordering. */
	@Test
	@DisplayName("should filter hierarchy parents by a disjoint target field without relevance ordering")
	void shouldFilterHierarchyParentsWithDisjointFields() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final Query query = query(collection(OWNER), filterBy(entityLocaleEquals(Locale.ENGLISH),
				hierarchyWithin(REFERENCE, textMatches("green ", inAttribute("label")))));
			assertArrayEquals(new int[]{2}, keys(session, query));
		});
	}

	/** Zero target weight must survive translation instead of inheriting the owner's shared-name weight 100. */
	@Test
	@DisplayName("should use target weight for a shared hierarchy parent field")
	void shouldUseHierarchySchemaWeight() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final Query query = query(collection(OWNER), filterBy(entityLocaleEquals(Locale.ENGLISH),
				hierarchyWithin(REFERENCE, textMatches("green ", inAttribute("name")))));
			assertArrayEquals(new int[]{1}, keys(session, query));
			final QueryPlanningContext context = context(session, query);
			QueryPlanner.planQuery(context);
			final Map<?, Formula> roots = field(context, "rootHierarchyNodesFormula");
			assertNotNull(roots);
			boolean found = false;
			for (final Formula root : roots.values()) {
				for (final FulltextFormula formula : FormulaFinder.find(root, FulltextFormula.class, LookUp.DEEP)) {
					found = true;
					assertEquals(0f, formula.getTokens()[0][0].weight());
					assertEquals(0, FulltextScoringPolicy.strength(formula.getFulltextScores(new int[]{1})
						.composites()[0]));
				}
			}
			assertTrue(found, "The real hierarchy parent formula must carry the target weight");
		});
	}

	/** Both nested entry points rank the disjoint target label above the shared name despite the owner defaults. */
	@ParameterizedTest
	@CsvSource({"false,placements", "true,placements", "false,virtualPlacements", "true,virtualPlacements"})
	@DisplayName("should use the target shared and disjoint weights through entityHaving and groupHaving")
	void shouldUseNestedTargetWeights(boolean groups, String referenceName) {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final FilterConstraint search = textMatches("green ");
			final Query query = ranked(referenceHaving(referenceName,
				groups ? groupHaving(search) : entityHaving(search)));
			assertArrayEquals(new int[]{2, 1}, keys(session, query));
			assertArrayEquals(new int[]{1}, keys(session, ranked(referenceHaving(referenceName,
				groups ? groupHaving(textMatches("green ", inAttribute("name"))) :
					entityHaving(textMatches("green ", inAttribute("name")))))));
			assertArrayEquals(new int[]{2}, keys(session, ranked(referenceHaving(referenceName,
				groups ? groupHaving(textMatches("green ", inAttribute("label"))) :
					entityHaving(textMatches("green ", inAttribute("label")))))));
		});
	}

	/** A conventional nested attribute filter never asks either collection for a scoring policy. */
	@Test
	@DisplayName("should leave the policy cache absent for a plain nested attribute filter")
	void shouldKeepPlainReferencePoliciesLazy() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final Query query = query(collection(OWNER), filterBy(referenceHaving(REFERENCE,
				entityHaving(attributeEquals("enabled", true)))));
			final QueryPlanningContext context = context(session, query);
			assertNull(field(context, "fulltextScoringPolicies"));
			assertArrayEquals(new int[]{1, 2}, QueryPlanner.planQuery(context).getFilter().compute().getArray());
			assertNull(field(context, "fulltextScoringPolicies"));
		});
	}

	/** One memoized target formula still requires both entity and group owner mappings. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("should retain entity and group mappings of the same target formula in either branch order")
	void shouldRetainBothMappings(boolean groupsFirst) {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.upsertEntity(session.getEntity(OWNER, 1, attributeContentAll(),
				referenceContentAll(), dataInLocalesAll()).orElseThrow().openForWrite()
				.setReference(REFERENCE, 1, r -> r.setGroup(TARGET, 2)));
			session.upsertEntity(session.getEntity(OWNER, 2, attributeContentAll(),
				referenceContentAll(), dataInLocalesAll()).orElseThrow().openForWrite()
				.setReference(REFERENCE, 2, r -> r.setGroup(TARGET, 1)));
		});
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final TextMatches text = textMatches("green ", inAttribute("name"));
			final FilterConstraint entity = entityHaving(text);
			final FilterConstraint group = groupHaving(text);
			final Query query = ranked(referenceHaving(REFERENCE,
				groupsFirst ? or(group, entity) : or(entity, group)));
			assertArrayEquals(new int[]{1, 2}, keys(session, query));
			final QueryPlan plan = QueryPlanner.planQuery(context(session, query));
			final RelevanceTextSorter sorter = (RelevanceTextSorter) plan.getSorters().iterator().next();
			final FulltextScoreAccessor[] sources = field(sorter, "accessors");
			assertEquals(2, sources.length, "Entity and group mappings are separate reference places");
			assertSame(((ReferenceOwnerFulltextScoreAccessor) sources[0]).getTargetFilter(),
				((ReferenceOwnerFulltextScoreAccessor) sources[1]).getTargetFilter(),
				"The regression must exercise the same cached target filter with two mapping kinds");
			final long[] scores = FulltextScoreCombiner.combine(new int[]{1, 2}, sources,
				new FulltextScoringPolicy(Map.of())).composites();
			assertNotEquals(0, scores[0]);
			assertEquals(scores[0], scores[1]);
		});
	}

	/** A second mapping contributes an independent bonus even when it shares the reference name. */
	@Test
	@DisplayName("should combine entity and group places of one reference on both plans")
	void shouldCombineEntityAndGroupPlaces() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.upsertEntity(session.getEntity(OWNER, 1, entityFetchAllContent()).orElseThrow().openForWrite()
				.setReference(REFERENCE, 2, r -> r.setGroup(TARGET, 1)));
		});
		assertOnBothPlans(referenceHaving(REFERENCE, or(
			entityHaving(textMatches("green ", inAttribute("label"))),
			groupHaving(textMatches("green ", inAttribute("label"))))), false, 2, 1);
	}

	/** Separate nested text constraints contribute even if reached through the same entity mapping. */
	@Test
	@DisplayName("should combine distinct entityHaving constraints of one reference on both plans")
	void shouldCombineDistinctEntityConstraints() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.upsertEntity(session.getEntity(OWNER, 2, entityFetchAllContent()).orElseThrow().openForWrite()
				.setReference(REFERENCE, 1, r -> r.setGroup(TARGET, 1)));
		});
		assertOnBothPlans(or(
			referenceHaving(REFERENCE, entityHaving(textMatches("green ", inAttribute("label")))),
			referenceHaving(REFERENCE, entityHaving(textMatches("amber ", inAttribute("label"))))), false, 2, 1);
	}

	/** One constraint planned for two target scopes still has exactly one reference weight and strength. */
	@Test
	@DisplayName("should collapse live and archived copies of the same constraint on both plans")
	void shouldCollapseTargetScopes() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getEntitySchemaOrThrowException(TARGET).openForWrite()
				.withAttribute("label", String.class, a -> a.searchableInScope(Scope.LIVE, Scope.ARCHIVED))
				.updateVia(session);
			session.upsertEntity(session.createNewEntity(TARGET, 3).setScope(Scope.ARCHIVED)
				.setAttribute("name", Locale.ENGLISH, "amber")
				.setAttribute("label", Locale.ENGLISH, "green").setAttribute("enabled", true));
			session.upsertEntity(session.getEntity(OWNER, 1, entityFetchAllContent()).orElseThrow().openForWrite()
				.setReference(REFERENCE, 2, r -> r.setGroup(TARGET, 2))
				.setReference(REFERENCE, 3, r -> r.setGroup(TARGET, 3)));
		});
		assertOnBothPlans(referenceHaving(REFERENCE,
			entityHaving(textMatches("green ", inAttribute("label")))), true, 1, 2);
	}

	/** Repeated reduced-index translations and mixed representations preserve one multi-child place. */
	@ParameterizedTest
	@ValueSource(strings = {REFERENCE, VIRTUAL_REFERENCE})
	@DisplayName("should deduplicate multi-child translations on both plans and partition representations")
	void shouldKeepConjunctiveOriginIdentity(String referenceName) {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.upsertEntity(session.createNewEntity(TARGET, 3)
				.setAttribute("name", Locale.ENGLISH, "amber")
				.setAttribute("label", Locale.ENGLISH, "green").setAttribute("enabled", true));
			session.upsertEntity(session.getEntity(OWNER, 1, entityFetchAllContent()).orElseThrow().openForWrite()
				.setReference(referenceName, 2, r -> r.setGroup(TARGET, 2))
				.setReference(referenceName, 3, r -> r.setGroup(TARGET, 3)));
		});
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final FilterConstraint filter = referenceHaving(referenceName, entityHaving(
				textMatches("green ", inAttribute("label")), attributeEquals("enabled", true)));
			if (VIRTUAL_REFERENCE.equals(referenceName)) {
				final Map<EntityIndexKey, EntityIndex> indexes = field(context(session, ranked(filter))
					.getEntityCollection(OWNER).orElseThrow(), "indexes");
				final ReferencedTypeEntityIndex type = (ReferencedTypeEntityIndex) indexes.get(new EntityIndexKey(
					EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, referenceName));
				final Map<Integer, Long> virtual = new HashMap<>();
				VirtualReferencePartitionIndexEntries.forEachOwnerRow(type.getVirtualReferencePartitionIndex(),
					virtual::put);
				assertEquals(2, virtual.size(), "Targets 1 and 3 must remain single-owner virtual partitions");
				assertNotNull(indexes.get(new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY,
					Scope.LIVE, new RepresentativeReferenceKey(new ReferenceKey(referenceName, 2)))));
			}
			// One exact label hit: raw impact 191, target weight 4, scale 8, reference weight 0.5.
			final long score = 0x01ffff0000000000L | (3056L << FulltextScoringPolicy.STRENGTH_SHIFT);
			for (final PlanPreference preference : PlanPreference.values()) {
				assertPlanScores(session, preference, filter, 1, new long[]{score, score}, 1, 2);
			}
		});
	}

	/** Equal rebuilt constraints share one place, just as a shared immutable child does. */
	@Test
	@DisplayName("should collapse structurally equal reference constraints on both plans")
	void shouldCollapseEqualConstraints() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final FilterConstraint filter = or(
				referenceHaving(REFERENCE, entityHaving(textMatches("green ", inAttribute("label")))),
				referenceHaving(REFERENCE, entityHaving(textMatches("green ", inAttribute("label")))));
			final long score = 0x01ffff0000000000L | (3056L << FulltextScoringPolicy.STRENGTH_SHIFT);
			for (final PlanPreference preference : PlanPreference.values()) {
				assertPlanScores(session, preference, filter, 1, new long[]{0, score}, 2);
			}
		});
	}

	/** Rebuilding equal children used to add a duplicate reference bonus and reverse this fixture's order. */
	@Test
	@DisplayName("should give shared and rebuilt equal children identical sources scores and order on both plans")
	void shouldIgnoreEqualChildObjectSharing() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getEntitySchemaOrThrowException(OWNER).openForWrite()
				.withAttribute("name", String.class, a -> a.searchWeight(2.2f)).updateVia(session);
			session.upsertEntity(session.getEntity(OWNER, 1, entityFetchAllContent()).orElseThrow().openForWrite()
				.setAttribute("name", Locale.ENGLISH, "green"));
		});
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final FilterConstraint child = entityHaving(textMatches("green ", inAttribute("label")));
			final FilterConstraint shared = or(referenceHaving(REFERENCE, child), referenceHaving(REFERENCE, child),
				textMatches("green ", inAttribute("name")));
			final FilterConstraint rebuilt = or(
				referenceHaving(REFERENCE, entityHaving(textMatches("green ", inAttribute("label")))),
				referenceHaving(REFERENCE, entityHaving(textMatches("green ", inAttribute("label")))),
				textMatches("green ", inAttribute("name")));
			assertEquals(shared, rebuilt);
			assertEquals(shared.toString(), rebuilt.toString());
			final long quality = 0x01ffff0000000000L;
			final long[] expected = {quality | (3362L << FulltextScoringPolicy.STRENGTH_SHIFT),
				quality | (3056L << FulltextScoringPolicy.STRENGTH_SHIFT)};
			for (final PlanPreference preference : PlanPreference.values()) {
				assertPlanScores(session, preference, shared, 2, expected, 1, 2);
				assertPlanScores(session, preference, rebuilt, 2, expected, 1, 2);
			}
		});
	}

	/** The direct formula memo already collapses equal textMatches constraints into a single scoring source. */
	@Test
	@DisplayName("should count equal direct textMatches once on both plans")
	void shouldCollapseEqualDirectConstraints() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.upsertEntity(session.getEntity(OWNER, 1, entityFetchAllContent()).orElseThrow().openForWrite()
				.setAttribute("name", Locale.ENGLISH, "green"));
		});
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final FilterConstraint single = textMatches("green ", inAttribute("name"));
			final FilterConstraint repeated = or(textMatches("green ", inAttribute("name")),
				textMatches("green ", inAttribute("name")));
			final long score = 0x01ffff0000000000L | (65535L << FulltextScoringPolicy.STRENGTH_SHIFT);
			for (final PlanPreference preference : PlanPreference.values()) {
				assertPlanScores(session, preference, single, 1, new long[]{score, 0}, 1);
				assertPlanScores(session, preference, repeated, 1, new long[]{score, 0}, 1);
			}
		});
	}

	/** Checks the public result, actual execution plan, source count and complete candidate-aligned vector. */
	private void assertPlanScores(
		EvitaSessionContract session, PlanPreference preference, FilterConstraint filter, int sourceCount,
		long[] expectedScores, int... expectedOrder
	) {
		final Query query = query(collection(OWNER), filterBy(preference.filter(new int[]{1, 2},
			entityLocaleEquals(Locale.ENGLISH), filter)), orderBy(relevanceText()),
			require(page(1, 20), preference.debug(), queryTelemetry()));
		final EvitaResponse<EntityReference> response = session.query(query, EntityReference.class);
		preference.assertTaken(response);
		assertArrayEquals(expectedOrder, response.getRecordData().stream()
			.mapToInt(EntityReference::getPrimaryKey).toArray(), preference.name());
		final QueryPlan plan = QueryPlanner.planQuery(context(session, query));
		final RelevanceTextSorter sorter = (RelevanceTextSorter) plan.getSorters().iterator().next();
		final FulltextScoreAccessor[] sources = field(sorter, "accessors");
		assertEquals(sourceCount, sources.length, preference.name());
		assertArrayEquals(expectedScores, FulltextScoreCombiner.combine(new int[]{1, 2}, sources,
			new FulltextScoringPolicy(Map.of())).composites(), preference.name());
	}

	/** Uses public queries and telemetry to prove both execution plans and checks complete score vectors. */
	private void assertOnBothPlans(FilterConstraint filter, boolean allScopes, int... expected) {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			for (final PlanPreference preference : PlanPreference.values()) {
				final Query query = query(collection(OWNER), filterBy(preference.filter(new int[]{1, 2},
					entityLocaleEquals(Locale.ENGLISH),
					allScopes ? scope(Scope.LIVE, Scope.ARCHIVED) : scope(Scope.LIVE),
					filter)), orderBy(relevanceText()), require(page(1, 20), preference.debug(), queryTelemetry()));
				final EvitaResponse<EntityReference> response = session.query(query, EntityReference.class);
				preference.assertTaken(response);
				assertArrayEquals(expected, response.getRecordData().stream()
					.mapToInt(EntityReference::getPrimaryKey).toArray(), preference.name());
				final QueryPlan plan = QueryPlanner.planQuery(context(session, query));
				final RelevanceTextSorter sorter = (RelevanceTextSorter) plan.getSorters().iterator().next();
				final FulltextScoreAccessor[] sources = field(sorter, "accessors");
				assertEquals(allScopes ? 1 : 2, sources.length);
				final long[] scores = FulltextScoreCombiner.combine(new int[]{1, 2}, sources,
					new FulltextScoringPolicy(Map.of())).composites();
				assertEquals(scores[0] & FulltextScoringPolicy.QUALITY_MASK,
					scores[1] & FulltextScoringPolicy.QUALITY_MASK);
				if (allScopes) {
					assertEquals(scores[0], scores[1], "Additional target scopes never add strength");
				} else {
					assertTrue(Long.compareUnsigned(scores[1], scores[0]) > 0);
				}
			}
		});
	}

	/** The same filter and indexes must not share memoized formulas across different effective policies. */
	@Test
	@DisplayName("should distinguish effective policies in translator computeOnlyOnce keys")
	void shouldSeparateMemoizedPolicies() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.upsertEntity(session.getEntity(OWNER, 1, attributeContentAll(),
				referenceContentAll(), dataInLocalesAll()).orElseThrow().openForWrite()
				.setAttribute("name", Locale.ENGLISH, "green"));
		});
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final Query query = ranked(textMatches("green ", inAttribute("name")));
			final QueryPlanningContext context = context(session, query);
			context.getFulltextScoringPolicy();
			final Map<String, FulltextScoringPolicy> policies = field(context, "fulltextScoringPolicies");
			final FulltextScoringPolicy first = new FulltextScoringPolicy(
				Map.of(FulltextFieldKey.attribute("name"), 1f));
			final FulltextScoringPolicy second = new FulltextScoringPolicy(
				Map.of(FulltextFieldKey.attribute("name"), 2f));
			assertNotEquals(first.computeHash(LongHashFunction.xx3()), second.computeHash(LongHashFunction.xx3()));
			// Stand in for query overrides while keeping constraint, context and postings identical.
			policies.put(OWNER, first);
			final FulltextFormula one = translated(context, query);
			policies.put(OWNER, second);
			final FulltextFormula two = translated(context, query);
			assertNotSame(one, two);
			assertEquals(1f, one.getTokens()[0][0].weight());
			assertEquals(2f, two.getTokens()[0][0].weight());
			assertSame(two, translated(context, query));
		});
	}

	/** A small counterpart family and ten owner-side target buckets make the bidirectional rewrite reachable. */
	@Test
	@DisplayName("should retain target weights through the bidirectional entityHaving rewrite")
	void shouldUseTargetWeightsThroughBidirectionalRewrite() {
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			session.getEntitySchemaOrThrowException(TARGET).openForWrite()
				.withReflectedReferenceToEntity("products", OWNER, REFERENCE).updateVia(session);
			for (int pk = 3; pk <= 10; pk++) {
				session.upsertEntity(session.createNewEntity(TARGET, pk)
					.setAttribute("name", Locale.ENGLISH, "amber")
					.setAttribute("label", Locale.ENGLISH, "amber")
					.setAttribute("enabled", true));
				final int owner = pk % 2 + 1;
				session.upsertEntity(session.getEntity(OWNER, owner, attributeContentAll(),
					referenceContentAll(), dataInLocalesAll()).orElseThrow().openForWrite()
					.setReference(REFERENCE, pk));
			}
		});
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final Query query = ranked(referenceHaving(REFERENCE, entityHaving(textMatches("green "))));
			assertArrayEquals(new int[]{2, 1}, keys(session, query));
			final QueryPlan plan = QueryPlanner.planQuery(context(session, query));
			assertFalse(FormulaFinder.find(plan.getFilter(), ReferencedOwnerExistenceFormula.class, LookUp.DEEP)
				.isEmpty(), "The regression must actually execute the bidirectional rewrite");
		});
	}

	/** Translates the same constraint through the production memoization seam. */
	private static FulltextFormula translated(QueryPlanningContext context, Query query) {
		final Formula formula = FilterByVisitor.createFormulaForTheFilter(context, Set.of(Scope.LIVE),
			query.getFilterBy(), OWNER, () -> "effective policy regression");
		return FormulaFinder.find(formula, FulltextFormula.class, LookUp.DEEP).iterator().next();
	}

	/** Builds the real planner context from the catalog's indexes so hidden parent formulas remain observable. */
	private QueryPlanningContext context(EvitaSessionContract session, Query query) {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstanceOrThrowException(TEST_CATALOG);
		final EntityCollection collection = (EntityCollection) catalog.getCollectionForEntityOrThrowException(OWNER);
		final Map<EntityIndexKey, EntityIndex> indexes = field(collection, "indexes");
		final Map<Integer, EntityIndex> byPk = field(collection, "indexesByPrimaryKey");
		return new QueryPlanningContext(catalog, collection, session,
			new EvitaRequest(query, OffsetDateTime.now(), EntityReference.class, null), indexes, byPk,
			NoCacheSupervisor.INSTANCE);
	}

	/** Reads planning state solely for assertions; production exposes no mutable cache or sorter internals. */
	@SuppressWarnings("unchecked")
	private static <T> T field(Object instance, String name) {
		try {
			final Field field = instance.getClass().getDeclaredField(name);
			field.setAccessible(true);
			return (T) field.get(instance);
		} catch (ReflectiveOperationException ex) {
			throw new AssertionError(ex);
		}
	}

	/** A relevance-ordered owner query with the locale propagated into nested filters. */
	private static Query ranked(FilterConstraint filter) {
		return query(collection(OWNER), filterBy(entityLocaleEquals(Locale.ENGLISH), filter),
			orderBy(relevanceText()), require(page(1, 20)));
	}

	/** Queries the public functional entry point and returns primary keys in response order. */
	private static int[] keys(EvitaSessionContract session, Query query) {
		return session.query(query, EntityReference.class).getRecordData().stream()
			.mapToInt(EntityReference::getPrimaryKey).toArray();
	}
}
