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
import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.exception.AssociatedDataNotFoundException;
import io.evitadb.api.exception.AttributeNotFoundException;
import io.evitadb.api.exception.ReferenceNotFoundException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.order.RelevanceText;
import io.evitadb.api.query.order.RelevanceTextEntitySpecificationOrderConstraint;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.cache.CacheAnteroom;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.cache.HeapMemoryCacheSupervisor;
import io.evitadb.core.cache.NoCacheSupervisor;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.query.QueryPlan;
import io.evitadb.core.query.QueryPlanner;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.fulltext.FulltextScoreAccessor;
import io.evitadb.core.query.algebra.fulltext.FulltextScoreCombiner;
import io.evitadb.core.query.sort.fulltext.RelevanceTextSorter;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.fulltext.FulltextScoringPolicy;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.requestResponse.schema.ReferenceSearchMode.COMPOSITION;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.*;

/** Real differential schema copies exercise query weights without changing matching or scoring rules. */
@Tag(ENGINE)
@Tag(FULLTEXT)
@Tag(ORDER)
@Tag(QUERY)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Fulltext query place weights")
class FulltextPlaceWeightsFunctionalTest implements EvitaTestSupport {
	private static final String[] TYPES = {"Base", "OwnAttribute", "OwnData", "ReferenceAttribute",
		"ComposedAttribute", "ComposedData"};
	private static final int[] ALL = {1, 2, 3, 4, 5, 6, 7};
	private static final int[] DEFAULT_ORDER = {7, 1, 2, 3, 4, 5, 6};
	private TestPaths paths;
	private Evita evita;
	private Scheduler scheduler;
	private HeapMemoryCacheSupervisor cache;

	/** Shares one small catalog, identical records and real cache infrastructure across the tests. */
	@BeforeAll
	void setUp() {
		this.paths = createTestPaths("FulltextPlaceWeightsFunctionalTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.scheduler = new Scheduler(ThreadPoolOptions.requestThreadPoolBuilder()
			.minThreadCount(1).maxThreadCount(2).build());
		this.cache = new HeapMemoryCacheSupervisor(CacheOptions.builder().enabled(true)
			.reevaluateEachSeconds(3600).anteroomRecordCount(10_000).minimalComplexityThreshold(1L)
			.minimalUsageThreshold(1).cacheSizeInBytes(1_000_000L).build(), this.scheduler);
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(TEST_CATALOG, session -> {
			for (int kind = 0; kind < TYPES.length; kind++) {
				final int placeKind = kind;
				final String target = TYPES[kind] + "Block";
				session.defineEntitySchema(target).withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
					.withAttribute("text", String.class, a -> a.localized().nullable()
						.searchableInScope(Scope.values()).lengthPivot(25)
						.searchWeight(placeKind == 4 ? 6f : 2f))
					.withAttribute("hidden", String.class, a -> a.localized().nullable())
					.withAttribute("liveOnly", String.class, a -> a.localized().nullable().searchable())
					.withAssociatedData("text", String.class, a -> a.localized().nullable()
						.searchableInScope(Scope.values()).lengthPivot(25)
						.searchWeight(placeKind == 5 ? 6f : 2f))
					.withAssociatedData("hidden", String.class, a -> a.localized().nullable())
					.withAssociatedData("liveOnly", String.class, a -> a.localized().nullable().searchable())
					.withReferenceToEntity("child", target, Cardinality.ZERO_OR_MORE, r -> r.indexedForFiltering())
					.withReferenceTo("tags", "Tag", Cardinality.ZERO_OR_MORE,
						r -> r.indexedForFilteringInScope(Scope.values())
						.withAttribute("text", String.class, a -> a.localized().nullable()
							.searchableInScope(Scope.values()).lengthPivot(25).searchWeight(4)))
					.updateVia(session);
				for (int id = 104; id <= 107; id++) {
					final EntityBuilder block = session.createNewEntity(target, id)
						.setAttribute("text", Locale.ENGLISH, id == 104 ? "quartz" : "plain")
						.setAssociatedData("text", Locale.ENGLISH, id == 105 ? "quartz" : "plain");
					if (id == 104) block.setAttribute("liveOnly", Locale.ENGLISH, "zircon")
						.setAssociatedData("liveOnly", Locale.ENGLISH, "jasper");
					if (id == 107) block.setReference("tags", 1,
						r -> r.setAttribute("text", Locale.ENGLISH, "quartz")).setReference("child", 104);
					session.upsertEntity(block);
				}
			}
			for (int kind = 0; kind <= TYPES.length; kind++) {
				final int placeKind = kind;
				final String type = kind == TYPES.length ? "Empty" : TYPES[kind];
				final String target = kind == TYPES.length ? "BaseBlock" : type + "Block";
				session.defineEntitySchema(type).withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
					.withAttribute("text", String.class, a -> a.localized().nullable()
						.searchableInScope(Scope.values()).lengthPivot(25)
						.searchWeight(placeKind == 1 ? 3f : 1f))
					.withAttribute("other", String.class, a -> a.localized().nullable().searchable())
					.withAttribute("scopeCounter", String[].class,
						a -> a.localized().nullable().searchableInScope(Scope.values()))
					.withAttribute("hidden", String.class, a -> a.localized().nullable())
					.withAttribute("liveOnly", String.class, a -> a.localized().nullable().searchable())
					.withAssociatedData("text", String.class, a -> a.localized().nullable()
						.searchableInScope(Scope.values()).lengthPivot(25)
						.searchWeight(placeKind == 2 ? 3f : 1f))
					.withAssociatedData("hidden", String.class, a -> a.localized().nullable())
					.withAssociatedData("liveOnly", String.class, a -> a.localized().nullable().searchable())
					.withReferenceToEntity("r", target, Cardinality.ZERO_OR_MORE,
						r -> r.indexedForFilteringInScope(Scope.values())
						.searchableThroughInScope(COMPOSITION, 0.5f, Scope.values())
						.withAttribute("text", String.class, a -> a.localized().nullable()
						.searchableInScope(Scope.values()).lengthPivot(25)
							.searchWeight(placeKind == 3 ? 3f : 1f))
						.withAttribute("hidden", String.class, a -> a.localized().nullable())
						.withAttribute("liveOnly", String.class, a -> a.localized().nullable().searchable()))
					.withReferenceToEntity("s", "BaseBlock", Cardinality.ZERO_OR_MORE,
						r -> r.indexedForFilteringInScope(Scope.values())
							.searchableThroughInScope(COMPOSITION, 0.5f, Scope.values()))
					.withReferenceToEntity("raw", target, Cardinality.ZERO_OR_MORE, r -> r.indexedForFiltering())
					.withReferenceToEntity("live", target, Cardinality.ZERO_OR_MORE,
						r -> r.indexedForFiltering().searchableThrough(COMPOSITION))
					.updateVia(session);
				if (type.equals("Empty")) continue;
				for (final int id : ALL) {
					final EntityBuilder owner = session.createNewEntity(type, id)
						.setAttribute("text", Locale.ENGLISH, id == 1 ? "quartz" : "plain")
						.setAssociatedData("text", Locale.ENGLISH, id == 2 ? "quartz" : "plain");
					if (id == 1) owner.setAttribute("scopeCounter", Locale.ENGLISH,
						new String[]{"cobalt", "ochre", "opal"});
					if (id == 2) owner.setAttribute("liveOnly", Locale.ENGLISH, "cobalt")
						.setAssociatedData("liveOnly", Locale.ENGLISH, "ochre")
						.setReference("r", 106, r -> r.setAttribute("liveOnly", Locale.ENGLISH, "opal"));
					if (id == 6) owner.setAttribute("scopeCounter", Locale.ENGLISH, new String[]{"zircon", "jasper"});
					if (id == 3) owner.setReference("r", 106,
						r -> r.setAttribute("text", Locale.ENGLISH, "quartz"));
					if (id == 4 || id == 5 || id == 7) owner.setReference("r", 100 + id);
					if (id == 6) owner.setReference("s", 104);
					session.upsertEntity(owner);
				}
			}
			// A self-reference must use schema defaults for its target even when the owner's own place is overridden.
			session.defineEntitySchema("Self").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
				.withAttribute("text", String.class, a -> a.localized().nullable().searchable())
				.withReferenceToEntity("r", "Self", Cardinality.ZERO_OR_MORE,
					r -> r.indexedForFilteringInScope(Scope.values())
							.searchableThroughInScope(COMPOSITION, 0.5f, Scope.values())).updateVia(session);
			session.upsertEntity(session.createNewEntity("Self", 1).setAttribute("text", Locale.ENGLISH, "quartz"));
			session.upsertEntity(session.createNewEntity("Self", 2)
				.setAttribute("text", Locale.ENGLISH, "plain").setReference("r", 1));
		});
	}

	/** Releases the shared catalog, cache, scheduler and temporary fixture files. */
	@AfterAll
	void tearDown() {
		this.cache.close();
		this.scheduler.shutdown();
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/** Maps each schema-copy change to its query override, including both composed place kinds. */
	@Nonnull
	private static RelevanceText override(int kind, float weight) {
		return switch (kind) {
			case 1 -> relevanceText(attributeWeight("text", weight));
			case 2 -> relevanceText(associatedDataWeight("text", weight));
			case 3 -> relevanceText(referenceWeight("r", attributeWeight("text", weight)));
			case 4 -> relevanceText(referenceWeight("r", entityWeight(attributeWeight("text", weight))));
			case 5 -> relevanceText(referenceWeight("r", entityWeight(associatedDataWeight("text", weight))));
			default -> throw new IllegalArgumentException("Unknown place kind " + kind);
		};
	}

	/** Exact ids and score bits must agree with a schema copy declaring the corresponding effective weight. */
	@ParameterizedTest
	@ValueSource(ints = {1, 2, 3, 4, 5})
	@DisplayName("Matches schema searchWeight membership, scores and literal order for every place kind")
	void shouldMatchSchemaWeightForEveryPlaceKind(int kind) {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final Query weighted = request("Base", textMatches("quartz "), override(kind, 3));
			final Query schemaCopy = request(TYPES[kind], textMatches("quartz "), relevanceText());
			final int[] expected = Stream.concat(Stream.of(kind, 7),
				Arrays.stream(ALL).boxed().filter(id -> id != kind && id != 7))
				.mapToInt(Integer::intValue).toArray();
			assertArrayEquals(expected, ids(session, weighted));
			assertArrayEquals(expected, ids(session, schemaCopy));
			assertArrayEquals(scores(session, schemaCopy, ALL), scores(session, weighted, ALL));
			assertFalse(Arrays.equals(ids(session, request("Base", textMatches("quartz "), relevanceText())), expected),
				"The fixture must visibly reorder without the production override");
		});
	}

	/** Reference body scoring consumes the very same reference-attribute effective weight as the entity union. */
	@Test
	@DisplayName("Applies reference attribute overrides inside referenceHaving textMatches")
	void shouldOverrideReferenceBodyAttribute() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final FilterConstraint filter = or(textMatches("quartz ", inAttribute("text")),
				referenceHaving("r", textMatches("quartz ", inAttribute("text"))));
			final Query weighted = request("Base", filter, override(3, 3));
			assertArrayEquals(new int[]{3, 1}, ids(session, weighted));
			assertArrayEquals(scores(session, request("ReferenceAttribute", filter, relevanceText()), ALL),
				scores(session, weighted, ALL));
		});
	}

	/** Composition must use final three, not 3 times the target's two or the reference's half. */
	@Test
	@DisplayName("Uses entityWeight as the final weight independently of target and reference weights")
	void shouldUseFinalComposedWeight() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final Query weighted = request("Base", textMatches("quartz "),
				relevanceText(attributeWeight("text", 3),
					referenceWeight("r", entityWeight(attributeWeight("text", 3)))));
			final long[] composites = scores(session, weighted, ALL);
			assertEquals(composites[0], composites[3]);
			assertArrayEquals(new int[]{1, 4, 7, 2, 3, 5, 6}, ids(session, weighted));
		});
	}

	/** Two references to one collection remain independent; unaddressable target reference attributes stay default. */
	@Test
	@DisplayName("Weights two composing references independently and preserves default target reference attributes")
	void shouldKeepComposingReferencesIndependent() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final Query weighted = request("Base", textMatches("quartz "), relevanceText(
				referenceWeight("r", entityWeight(attributeWeight("text", 3))),
				referenceWeight("s", entityWeight(attributeWeight("text", 5)))));
			assertArrayEquals(new int[]{6, 4, 7, 1, 2, 3, 5}, ids(session, weighted));
			assertEquals(scores(session, request("Base", textMatches("quartz "), relevanceText()), ALL)[6],
				scores(session, weighted, ALL)[6]);
		});
	}

	/** Zero affects only strength, with membership and every quality lane retained. */
	@ParameterizedTest
	@ValueSource(ints = {1, 2, 3, 4, 5})
	@DisplayName("Keeps zero-weight matches and quality lanes for every place kind")
	void shouldPreserveMembershipAndQualityAtZero(int kind) {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final Query plain = request("Base", textMatches("quartz "), relevanceText());
			final Query weighted = request("Base", textMatches("quartz "), override(kind, 0));
			final long[] before = scores(session, plain, ALL);
			final long[] after = scores(session, weighted, ALL);
			final int[] expected = Stream.concat(
					Arrays.stream(DEFAULT_ORDER).boxed().filter(id -> id != kind), Stream.of(kind))
				.mapToInt(Integer::intValue).toArray();
			assertArrayEquals(expected, ids(session, weighted));
			for (int i = 0; i < ALL.length; i++) {
				assertEquals(before[i] & FulltextScoringPolicy.QUALITY_MASK,
					after[i] & FulltextScoringPolicy.QUALITY_MASK);
			}
			assertEquals(0, FulltextScoringPolicy.strength(after[kind - 1]));
		});
	}

	/** Cache promotion is explicit, and alternating weights run in the same read-only session. */
	@Test
	@DisplayName("Separates different effective weights through the real formula cache")
	void shouldSeparateWeightsInOneCachedSession() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			for (int pass = 0; pass < 3; pass++) {
				for (final int kind : new int[]{1, 2, 3, 4, 5}) {
					final Query weighted = request("Base", textMatches("quartz "), override(kind, 3));
					final Query baseline = request("Base", textMatches("quartz "), override(kind, 1));
					final QueryPlanningContext weightedContext = context(session, weighted, this.cache);
					final QueryPlanningContext baselineContext = context(session, baseline, this.cache);
					final QueryPlan weightedPlan = QueryPlanner.planQuery(weightedContext);
					final QueryPlan baselinePlan = QueryPlanner.planQuery(baselineContext);
					assertNotEquals(weightedContext.getFulltextScoringPolicy()
						.computeHash(net.openhft.hashing.LongHashFunction.xx3()),
						baselineContext.getFulltextScoringPolicy()
						.computeHash(net.openhft.hashing.LongHashFunction.xx3()));
					assertNotEquals(weightedPlan.getFilter().getHash(), baselinePlan.getFilter().getHash());
					final int[] expected = switch (kind) {
						case 1 -> new int[]{1, 7, 2, 3, 4, 5, 6};
						case 2 -> new int[]{2, 7, 1, 3, 4, 5, 6};
						case 3 -> new int[]{3, 7, 1, 2, 4, 5, 6};
						case 4 -> new int[]{4, 7, 1, 2, 3, 5, 6};
						case 5 -> new int[]{5, 7, 1, 2, 3, 4, 6};
						default -> throw new IllegalArgumentException("Unknown place kind " + kind);
					};
					assertArrayEquals(expected, execute(session, weighted, this.cache));
					assertArrayEquals(DEFAULT_ORDER, execute(session, baseline, this.cache));
					final CacheAnteroom anteroom = field(this.cache, "cacheAnteroom");
					anteroom.evaluateAssociatesSynchronously();
				}
			}
		});
	}

	/** Self-composition reads default target weights rather than the effective owner's overridden own place. */
	@Test
	@DisplayName("Does not leak own attribute overrides into self-composed targets")
	void shouldKeepSelfCompositionDefaults() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final long[] plain = scores(session, request("Self", textMatches("quartz "), relevanceText()),
				new int[]{1, 2});
			final long[] weighted = scores(session, request("Self", textMatches("quartz "),
				relevanceText(attributeWeight("text", 3))), new int[]{1, 2});
			assertEquals(plain[1], weighted[1]);
			assertNotEquals(plain[0], weighted[0]);
		});
	}

	/** An entityHaving source uses target defaults and its existing engine factor even for self-references. */
	@Test
	@DisplayName("Keeps the fixed referenced-entity source policy unchanged")
	void shouldKeepFixedReferencedEntityWeights() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final FilterConstraint filter = or(textMatches("quartz ", inAttribute("text")),
				referenceHaving("r", entityHaving(textMatches("quartz ", inAttribute("text")))));
			final long[] baseline = scores(session, request("Self", filter, relevanceText()), new int[]{1, 2});
			final long[] weighted = scores(session, request("Self", filter, override(1, 3)), new int[]{1, 2});
			assertEquals(baseline[1], weighted[1]);
			assertNotEquals(baseline[0], weighted[0]);
		});
	}

	/** Segment relevance scores the main filter's sources; agreeing weights reverse their unweighted tie order. */
	@Test
	@DisplayName("Accepts agreeing segment policies and coverage spread across textMatches selectors")
	void shouldAcceptAgreeingSegmentPolicies() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final FilterConstraint filter = or(textMatches("quartz ", inAttribute("text")),
				textMatches("quartz ", inAssociatedData("text")));
			final Query query = request("Base", filter, segments(segment(orderBy(relevanceText(
				attributeWeight("text", 2), associatedDataWeight("text", 3))), limit(1)),
				segment(orderBy(relevanceText(associatedDataWeight("text", 3), attributeWeight("text", 2))))));
			assertArrayEquals(new int[]{1, 2}, ids(session, request("Base", filter, relevanceText())));
			assertArrayEquals(new int[]{2, 1}, ids(session, query));
		});
	}

	/** Policy agreement compares place sets rather than the spelling or grouping of reference containers. */
	@Test
	@DisplayName("Accepts equivalent policies with split reference containers")
	void shouldAcceptEquivalentReferencePolicies() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final RelevanceText grouped = relevanceText(referenceWeight("r", attributeWeight("text", 2),
				entityWeight(attributeWeight("text", 3))));
			final RelevanceText split = relevanceText(referenceWeight("r", entityWeight(attributeWeight("text", 3))),
				referenceWeight("r", attributeWeight("text", 2)));
			assertArrayEquals(new int[]{4, 3, 7, 1, 2, 5, 6}, ids(session, request("Base", textMatches("quartz "),
				segments(segment(orderBy(grouped), limit(1)), segment(orderBy(split))))));
		});
	}

	/**
	 * Checks acceptance and coverage only: segment-predicate scores do not feed segment relevance. The predicate
	 * and explicit primary-key fallback determine this order, independently of its associated-data override.
	 */
	@Test
	@DisplayName("Accepts a place searched by a segment predicate")
	void shouldRecognizePlacesSearchedBySegments() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final RelevanceText weights = override(2, 3);
			final Query query = request("Base",
				or(textMatches("quartz ", inAttribute("text")), entityPrimaryKeyInSet(2)),
				segments(segment(entityHaving(textMatches("quartz ", inAssociatedData("text"))),
					orderBy(weights, entityPrimaryKeyNatural(ASC))),
				segment(orderBy(weights, entityPrimaryKeyNatural(ASC)))));
			assertArrayEquals(new int[]{2, 1}, ids(session, query));
		});
	}

	/** Auxiliary locale contexts of the same collection still belong to the query's single weight policy. */
	@Test
	@DisplayName("Preserves query weights in auxiliary locale contexts")
	void shouldPreserveWeightsInLocaleContexts() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final Query query = request("Base", textMatches("quartz "), relevanceText(attributeWeight("text", 3),
				referenceWeight("r", entityWeight(attributeWeight("text", 3)))));
			final QueryPlanningContext context = context(session, query, NoCacheSupervisor.INSTANCE);
			QueryPlanner.planQuery(context);
			final QueryPlanningContext localized = context.getContextInLocale("Base", filterBy(textMatches("quartz ")),
				Locale.FRENCH, java.util.Set.of(Scope.LIVE), "resolve auxiliary locale");
			final FulltextScoringPolicy policy = localized.getFulltextScoringPolicy();
			assertEquals(3f, policy.weight(io.evitadb.index.fulltext.FulltextFieldKey.attribute("text")));
			assertEquals(3f,
				policy.composedWeight("r", io.evitadb.index.fulltext.FulltextFieldKey.attribute("text"), 1f));
		});
	}

	/** Reading an unplanned request returns defaults; only the public planner may activate its query overrides. */
	@Test
	@DisplayName("Reads schema defaults until the planner resolves query weights")
	void shouldReadDefaultsUntilThePlanningSeam() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final Query query = request("Base", textMatches("quartz "), override(1, 3));
			final QueryPlanningContext context = context(session, query, NoCacheSupervisor.INSTANCE);
			assertEquals(1f, context.getFulltextScoringPolicy()
				.weight(io.evitadb.index.fulltext.FulltextFieldKey.attribute("text")));
			assertSame(context.getFulltextSchemaScoringPolicy(context.getSchema()), context.getFulltextScoringPolicy());
			QueryPlanner.planQuery(context);
			assertEquals(3f, context.getFulltextScoringPolicy()
				.weight(io.evitadb.index.fulltext.FulltextFieldKey.attribute("text")));
		});
	}

	/** Equal Boolean containers in owner/self-reference searches keep owner overrides and target defaults separate. */
	@Test
	@DisplayName("Separates equal owner and self-reference search containers through public queries")
	void shouldSeparateEqualSelfReferenceSearchContainers() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final FilterConstraint enclosing = or(textMatches("quartz ", inAttribute("text")),
				entityPrimaryKeyInSet(2));
			final FilterConstraint filter = or(enclosing, referenceHaving("r", entityHaving(enclosing)));
			final Query plain = request("Self", filter, relevanceText());
			final Query weighted = request("Self", filter, relevanceText(attributeWeight("text", 0)));
			assertArrayEquals(new int[]{1, 2}, ids(session, plain));
			assertArrayEquals(new int[]{2, 1}, ids(session, weighted));
			final long[] baseline = scores(session, plain, new int[]{1, 2});
			final long[] actual = scores(session, weighted, new int[]{1, 2});
			assertEquals(0, FulltextScoringPolicy.strength(actual[0]));
			assertEquals(baseline[1], actual[1]);
		});
	}

	/** Without fulltext, nonempty weights still validate; a childless relevance constraint resolves no policy. */
	@Test
	@DisplayName("Validates weights without textMatches while keeping plain relevanceText free of policy resolution")
	void shouldValidateSchemaWithoutTextMatches() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			assertArrayEquals(ALL, ids(session, request("Base", null, override(1, 3))));
			final QueryPlanningContext context = context(session, request("Base", null, relevanceText()),
				NoCacheSupervisor.INSTANCE);
			QueryPlanner.planQuery(context);
			assertNull(field(context, "effectiveFulltextScoringPolicy"));
			assertNull(field(context, "fulltextScoringPolicies"));
		});
	}

	/** One refusal scenario, retaining the exact offending constraint for diagnostic assertions. */
	private record InvalidCase(
		@Nonnull OrderConstraint order, @Nullable FilterConstraint filter, @Nonnull String offending,
		@Nonnull Class<? extends EvitaInvalidUsageException> exception
	) { }

	/** Covers schema, policy, duplicate, coverage and placement errors with literal offending constraints. */
	@Nonnull
	static Stream<InvalidCase> invalidQueries() {
		final Stream.Builder<InvalidCase> cases = Stream.builder();
		for (final RelevanceTextEntitySpecificationOrderConstraint weight :
			new RelevanceTextEntitySpecificationOrderConstraint[]{
			attributeWeight("text", -1), associatedDataWeight("text", -1),
			referenceWeight("r", attributeWeight("text", -1)),
			referenceWeight("r", entityWeight(attributeWeight("text", -1))),
			referenceWeight("r", entityWeight(associatedDataWeight("text", -1))),
			attributeWeight("hidden", 2), associatedDataWeight("hidden", 2),
			referenceWeight("r", attributeWeight("hidden", 2)),
			referenceWeight("r", entityWeight(attributeWeight("hidden", 2))),
			referenceWeight("r", entityWeight(associatedDataWeight("hidden", 2))),
			referenceWeight("raw", entityWeight(attributeWeight("text", 2))), referenceWeight("r"),
			referenceWeight("r", entityWeight())
		}) {
			for (final FilterConstraint filter : new FilterConstraint[]{textMatches("quartz "), null}) {
				cases.add(new InvalidCase(relevanceText(weight), filter,
					weight instanceof io.evitadb.api.query.order.ReferenceWeight ref &&
						ref.getReferenceName().equals("raw") ?
						ref.getChildren()[0].toString() : deepest(weight).toString(),
					EvitaInvalidUsageException.class));
			}
		}
		cases.add(new InvalidCase(relevanceText(attributeWeight("missing", 2)), textMatches("quartz "),
			"attributeWeight('missing',2.0)", AttributeNotFoundException.class));
		cases.add(new InvalidCase(relevanceText(associatedDataWeight("missing", 2)), textMatches("quartz "),
			"associatedDataWeight('missing',2.0)", AssociatedDataNotFoundException.class));
		cases.add(new InvalidCase(relevanceText(referenceWeight("missing", attributeWeight("text", 2))),
			textMatches("quartz "), "referenceWeight('missing',attributeWeight('text',2.0))",
			ReferenceNotFoundException.class));
		cases.add(new InvalidCase(relevanceText(referenceWeight("r", attributeWeight("missing", 2))),
			textMatches("quartz "), "attributeWeight('missing',2.0)", AttributeNotFoundException.class));
		cases.add(new InvalidCase(relevanceText(referenceWeight("r", entityWeight(attributeWeight("missing", 2)))),
			textMatches("quartz "), "attributeWeight('missing',2.0)", AttributeNotFoundException.class));
		cases.add(new InvalidCase(relevanceText(referenceWeight("r", entityWeight(associatedDataWeight("missing", 2)))),
			textMatches("quartz "), "associatedDataWeight('missing',2.0)", AssociatedDataNotFoundException.class));
		for (final RelevanceTextEntitySpecificationOrderConstraint weight :
			new RelevanceTextEntitySpecificationOrderConstraint[]{
			attributeWeight("text", 2), associatedDataWeight("text", 2),
			referenceWeight("r", attributeWeight("text", 2)),
			referenceWeight("r", entityWeight(attributeWeight("text", 2)))
		}) {
			cases.add(new InvalidCase(relevanceText(weight, weight), textMatches("quartz "),
				deepest(weight).toString(), EvitaInvalidUsageException.class));
			cases.add(new InvalidCase(relevanceText(weight), textMatches("quartz ", inAttribute("other")),
				deepest(weight).toString(), EvitaInvalidUsageException.class));
		}
		cases.add(new InvalidCase(relevanceText(attributeWeight("text", 2)),
			referenceHaving("r", entityHaving(textMatches("quartz "))), "attributeWeight('text',2.0)",
			EvitaInvalidUsageException.class));
		cases.add(new InvalidCase(segments(segment(orderBy(override(1, 2)), limit(1)),
			segment(orderBy(override(1, 3)))), textMatches("quartz "), override(1, 3).toString(),
			EvitaInvalidUsageException.class));
		cases.add(new InvalidCase(orderBy(override(1, 2), relevanceText()), textMatches("quartz "), "relevanceText()",
			EvitaInvalidUsageException.class));
		cases.add(new InvalidCase(orderBy(override(1, 2), segments(segment(orderBy(override(1, 3))))),
			textMatches("quartz "), override(1, 3).toString(), EvitaInvalidUsageException.class));
		cases.add(new InvalidCase(relevanceText(referenceWeight("r", attributeWeight("text", 2),
			attributeWeight("text", 3))), textMatches("quartz "), "attributeWeight('text',3.0)",
			EvitaInvalidUsageException.class));
		cases.add(new InvalidCase(relevanceText(referenceWeight("r", entityWeight(attributeWeight("text", 2)),
			entityWeight(attributeWeight("text", 3)))), textMatches("quartz "), "attributeWeight('text',3.0)",
			EvitaInvalidUsageException.class));

		cases.add(new InvalidCase(segments(segment(entityHaving(textMatches("quartz ", inAssociatedData("text"))),
			orderBy(override(1, 2), entityPrimaryKeyNatural(ASC)))), null, "attributeWeight('text',2.0)",
			EvitaInvalidUsageException.class));

		for (final OrderConstraint stray : new OrderConstraint[]{attributeWeight("text", 2),
			associatedDataWeight("text", 2), referenceWeight("r", attributeWeight("text", 2)),
			entityWeight(attributeWeight("text", 2)), referenceProperty("r", override(1, 2)),
			entityProperty(override(1, 2)), entityGroupProperty(override(1, 2)),
			traverseByEntityProperty(override(1, 2)), pickFirstByEntityProperty(override(1, 2))}) {
			cases.add(new InvalidCase(stray, textMatches("quartz "),
				stray instanceof io.evitadb.api.query.order.RelevanceTextEntitySpecificationOrderConstraint ||
					stray instanceof io.evitadb.api.query.order.EntityWeight ?
					stray.toString() : override(1, 2).toString(),
				EvitaInvalidUsageException.class));
		}
		return cases.build();
	}

	/** Identifies the leaf whose schema, value or duplicate occurrence is refused. */
	@Nonnull
	private static OrderConstraint deepest(@Nonnull OrderConstraint constraint) {
		if (constraint instanceof io.evitadb.api.query.ConstraintContainer<?> container &&
			container.getChildrenCount() > 0) {
			return deepest((OrderConstraint) container.getChildren()[0]);
		}
		return constraint;
	}

	/** Each exact same query shape fails identically before data lookup on populated and empty collections. */
	@ParameterizedTest
	@MethodSource("invalidQueries")
	@DisplayName("Rejects invalid policies with the same exception and diagnostic on populated and empty data")
	void shouldRejectInvalidPolicyOnPopulatedAndEmptyCollections(@Nonnull InvalidCase invalid) {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			String diagnostic = null;
			for (final String type : new String[]{"Base", "Empty"}) {
				final EvitaInvalidUsageException error = assertThrows(invalid.exception(),
					() -> ids(session, request(type, invalid.filter(), invalid.order())));
				assertTrue(error.getMessage().contains(invalid.offending()), error.getMessage());
				final String normalized = error.getMessage().replace("BaseBlock", "Target").replace(type, "Collection");
				if (diagnostic != null) assertEquals(diagnostic, normalized);
				diagnostic = normalized;
			}
		});
	}

	/** A missing attribute names the schema it was looked up in, so the owner, reference and target stay apart. */
	@Test
	@DisplayName("Names the owner, reference or target schema in which a weighted attribute is missing")
	void shouldNameTheSchemaLevelOfAMissingAttribute() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final RelevanceTextEntitySpecificationOrderConstraint[] weights = {
				attributeWeight("missing", 2),
				referenceWeight("r", attributeWeight("missing", 2)),
				referenceWeight("r", entityWeight(attributeWeight("missing", 2)))
			};
			final String[] expected = {
				"is not present in schema of entity `Base`.",
				"is not present in schema of reference `r` of entity `Base`.",
				"is not present in schema of entity `BaseBlock`."
			};

			for (int i = 0; i < weights.length; i++) {
				final Query query = request("Base", textMatches("quartz "), relevanceText(weights[i]));
				final AttributeNotFoundException error = assertThrows(AttributeNotFoundException.class,
					() -> ids(session, query));

				assertTrue(error.getMessage().endsWith(expected[i]), error.getMessage());
			}
		});
	}

	/** Fetched-reference orderings cannot consume main-query relevance or any standalone weight constraint. */
	@Test
	@DisplayName("Refuses weighted relevance and stray weights in fetched-reference orderings " +
		"on empty and populated data")
	void shouldRefuseWeightsInFetchedReferenceOrdering() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			for (final String type : new String[]{"Base", "Empty"}) {
				for (final OrderConstraint child : new OrderConstraint[]{attributeWeight("text", 2),
					associatedDataWeight("text", 2), referenceWeight("r", attributeWeight("text", 2)),
					entityWeight(attributeWeight("text", 2)), override(1, 2)}) {
					for (final FilterConstraint text : new FilterConstraint[]{textMatches("quartz "), null}) {
						final Query query = query(collection(type), filterBy(entityLocaleEquals(Locale.ENGLISH), text),
							require(entityFetch(referenceContent("r", orderBy(child)))));
						final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
							() -> QueryPlanner.planQuery(context(session, query, NoCacheSupervisor.INSTANCE)));
						assertTrue(error.getMessage().contains(child.toString()), error.getMessage());
					}
				}
			}
		});
	}

	/** Refuses weights only when no processed scope exposes the place or composition declaration. */
	@Test
	@DisplayName("Refuses non-searchable processed scopes on populated and empty collections")
	void shouldRejectNonSearchableProcessedScope() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			for (final String type : new String[]{"Base", "Empty"}) {
				for (final RelevanceTextEntitySpecificationOrderConstraint weight :
					new RelevanceTextEntitySpecificationOrderConstraint[]{attributeWeight("liveOnly", 2),
						associatedDataWeight("liveOnly", 2), referenceWeight("r", attributeWeight("liveOnly", 2)),
						referenceWeight("r", entityWeight(attributeWeight("liveOnly", 2))),
						referenceWeight("r", entityWeight(associatedDataWeight("liveOnly", 2))),
						referenceWeight("live", entityWeight(attributeWeight("text", 2)))}) {
					final Query query = query(collection(type), filterBy(scope(Scope.ARCHIVED)),
						orderBy(relevanceText(weight)));
					assertThrows(EvitaInvalidUsageException.class, () -> ids(session, query));
				}
			}
		});
	}

	/** An inherited outer ordering must never resolve its owner attribute against a fetched target collection. */
	@Test
	@DisplayName("Keeps outer query weights out of nested fetched-reference searches " +
		"on empty and populated collections")
	void shouldKeepOwnerWeightsOutOfFetchedCollections() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			for (final String type : new String[]{"Empty", "Base"}) {
				final Query plain = fetchedSearch(type, relevanceText());
				final Query weighted = fetchedSearch(type, relevanceText(attributeWeight("other", 3)));
				final var expected = session.query(plain, SealedEntity.class).getRecordData();
				final EvitaResponse<SealedEntity> response = assertDoesNotThrow(
					() -> session.query(weighted, SealedEntity.class));
				final var actual = response.getRecordData();
				final int[] expectedIds = type.equals("Empty") ? new int[0] : DEFAULT_ORDER;
				assertArrayEquals(expectedIds, expected.stream().mapToInt(SealedEntity::getPrimaryKey).toArray());
				assertArrayEquals(expectedIds, actual.stream().mapToInt(SealedEntity::getPrimaryKey).toArray());
				if (!actual.isEmpty()) {
					final SealedEntity owner = actual.stream().filter(it -> it.getPrimaryKey() == 7).findFirst()
						.orElseThrow();
					final SealedEntity target = owner.getReference("r", 107).orElseThrow()
						.getReferencedEntity().orElseThrow();
					assertEquals(1, target.getReferences("child").size());
					assertEquals(104, target.getReferences("child").iterator().next().getReferencedPrimaryKey());
				}
			}
		});
	}

	/** Builds the public nested fetch whose target schema deliberately has no outer `other` attribute. */
	@Nonnull
	private static Query fetchedSearch(@Nonnull String type, @Nonnull RelevanceText relevance) {
		return query(collection(type), filterBy(entityLocaleEquals(Locale.ENGLISH), textMatches("quartz ")),
			orderBy(relevance), require(entityFetch(referenceContent("r", entityFetch(referenceContent("child",
				filterBy(entityHaving(textMatches("quartz ", inAttribute("text"))))))))));
	}

	/**
	 * Each weight changes literal order although its place is searchable only in LIVE and both scopes are
	 * processed.
	 */
	@ParameterizedTest
	@ValueSource(ints = {1, 2, 3, 4, 5})
	@DisplayName("Applies own, reference and composed weights searchable in just one processed scope")
	void shouldApplyWeightsSearchableInOneProcessedScope(int kind) {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final String word = new String[]{"cobalt", "ochre", "opal", "zircon", "jasper"}[kind - 1];
			final RelevanceText weighted = oneScopeWeight(kind);
			final Query plain = scopedSearch("Base", textMatches(word + " "), relevanceText());
			final Query query = scopedSearch("Base", textMatches(word + " "), weighted);
			assertArrayEquals(kind <= 3 ? new int[]{1, 2} : new int[]{6, 4}, ids(session, plain));
			assertArrayEquals(kind <= 3 ? new int[]{2, 1} : new int[]{4, 6},
				assertDoesNotThrow(() -> ids(session, query)));
			assertArrayEquals(ALL, ids(session, scopedSearch("Base", null, weighted)));
			assertArrayEquals(new int[0], ids(session, scopedSearch("Empty", null, weighted)));
			assertArrayEquals(new int[0], ids(session, scopedSearch("Empty", textMatches(word + " "), weighted)));
		});
	}

	/** A composition declaration in LIVE suffices even when ARCHIVED is processed too. */
	@Test
	@DisplayName("Accepts composition declared in at least one processed scope")
	void shouldAcceptCompositionInOneProcessedScope() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final RelevanceText weight = relevanceText(
				referenceWeight("live", entityWeight(attributeWeight("text", 3))));
			assertArrayEquals(ALL, ids(session, scopedSearch("Base", null, weight)));
			assertArrayEquals(new int[0], ids(session, scopedSearch("Empty", null, weight)));
		});
	}

	/** Selects the same LIVE-only place kind at each supported weight level. */
	@Nonnull
	private static RelevanceText oneScopeWeight(int kind) {
		return switch (kind) {
			case 1 -> relevanceText(attributeWeight("liveOnly", 3));
			case 2 -> relevanceText(associatedDataWeight("liveOnly", 3));
			case 3 -> relevanceText(referenceWeight("r", attributeWeight("liveOnly", 3)));
			case 4 -> relevanceText(referenceWeight("r", entityWeight(attributeWeight("liveOnly", 3))));
			case 5 -> relevanceText(referenceWeight("r", entityWeight(associatedDataWeight("liveOnly", 3))));
			default -> throw new IllegalArgumentException("Unknown place kind " + kind);
		};
	}

	/** Processes both scopes without imposing an explicit selector's stricter searchability rule. */
	@Nonnull
	private static Query scopedSearch(
		@Nonnull String type, @Nullable FilterConstraint filter, @Nonnull RelevanceText relevance
	) {
		return query(collection(type), filterBy(scope(Scope.LIVE, Scope.ARCHIVED),
			entityLocaleEquals(Locale.ENGLISH), filter), orderBy(relevance), require(page(1, 100)));
	}

	/** Builds the public query with an optional text filter and arbitrary ordering, preserving child order. */
	@Nonnull
	private static Query request(
		@Nonnull String type, @Nullable FilterConstraint filter, @Nonnull OrderConstraint order
	) {
		return query(collection(type), filterBy(entityLocaleEquals(Locale.ENGLISH), filter),
			orderBy(order), require(page(1, 100)));
	}

	/** Reads literal public relevance order from the real engine session. */
	@Nonnull
	private static int[] ids(@Nonnull EvitaSessionContract session, @Nonnull Query query) {
		return session.query(query, EntityReference.class).getRecordData().stream()
			.mapToInt(EntityReference::getPrimaryKey).toArray();
	}

	/** Executes a plan through the requested real cache supervisor. */
	@Nonnull
	private int[] execute(
		@Nonnull EvitaSessionContract session, @Nonnull Query query, @Nonnull CacheSupervisor supervisor
	) {
		return QueryPlanner.planQuery(context(session, query, supervisor)).execute().getRecordData().stream()
			.mapToInt(entity -> ((EntityReference) entity).getPrimaryKey()).toArray();
	}

	/** Reads actual scorer bits from the sorter's production accessors, including hidden reference sources. */
	@Nonnull
	private long[] scores(@Nonnull EvitaSessionContract session, @Nonnull Query query, @Nonnull int[] candidates) {
		final QueryPlanningContext context = context(session, query, NoCacheSupervisor.INSTANCE);
		final QueryPlan plan = QueryPlanner.planQuery(context);
		plan.execute();
		final RelevanceTextSorter sorter = (RelevanceTextSorter) plan.getSorters().iterator().next();
		final FulltextScoreAccessor[] sources = field(sorter, "accessors");
		return FulltextScoreCombiner.combine(candidates, sources, context.getFulltextScoringPolicy()).composites();
	}

	/** Constructs the same production planning context while allowing deterministic cache promotion in tests. */
	@Nonnull
	private QueryPlanningContext context(
		@Nonnull EvitaSessionContract session, @Nonnull Query query, @Nonnull CacheSupervisor supervisor
	) {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstanceOrThrowException(TEST_CATALOG);
		final EntityCollection collection = (EntityCollection) catalog.getCollectionForEntityOrThrowException(
			query.getCollection().getEntityType());
		final EvitaSessionContract actual = Proxy.isProxyClass(session.getClass()) ?
			field(Proxy.getInvocationHandler(session), "evitaSession") : session;
		return new QueryPlanningContext(catalog, collection, actual,
			new EvitaRequest(query, OffsetDateTime.now(), EntityReference.class, null),
			QueryTelemetry.root(QueryPhase.OVERALL),
			field(collection, "indexes"), field(collection, "indexesByPrimaryKey"), supervisor);
	}

	/** Inspects actual planner state unavailable in the public entity response without mocking collaborators. */
	@Nullable
	@SuppressWarnings("unchecked")
	private static <T> T field(@Nonnull Object instance, @Nonnull String name) {
		try {
			final Field field = instance.getClass().getDeclaredField(name);
			field.setAccessible(true);
			return (T) field.get(instance);
		} catch (ReflectiveOperationException ex) {
			throw new AssertionError(ex);
		}
	}
}
