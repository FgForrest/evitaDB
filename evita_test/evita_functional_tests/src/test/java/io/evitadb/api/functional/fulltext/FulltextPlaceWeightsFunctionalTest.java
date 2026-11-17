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
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.fulltext.RelevanceTextSorter;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.fulltext.FulltextScoringPolicy;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.api.query.order.OrderDirection.ASC;
import static io.evitadb.api.requestResponse.schema.ReferenceSearchMode.COMPOSITION;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.QUERY;
import static java.math.BigDecimal.valueOf;
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
						.searchWeight(placeKind == 4 ? valueOf(6) : valueOf(2)))
					.withAttribute("hidden", String.class, a -> a.localized().nullable())
					.withAttribute("liveOnly", String.class, a -> a.localized().nullable().searchable())
					.withAssociatedData("text", String.class, a -> a.localized().nullable()
						.searchableInScope(Scope.values()).lengthPivot(25)
						.searchWeight(placeKind == 5 ? valueOf(6) : valueOf(2)))
					.withAssociatedData("hidden", String.class, a -> a.localized().nullable())
					.withAssociatedData("liveOnly", String.class, a -> a.localized().nullable().searchable())
					.withReferenceToEntity("child", target, Cardinality.ZERO_OR_MORE, r -> r.indexedForFiltering())
					.withReferenceTo("tags", "Tag", Cardinality.ZERO_OR_MORE,
						r -> r.indexedForFilteringInScope(Scope.values())
						.withAttribute("text", String.class, a -> a.localized().nullable()
							.searchableInScope(Scope.values()).lengthPivot(25).searchWeight(valueOf(4))))
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
				session.defineEntitySchema(type).withoutGeneratedPrimaryKey().withHierarchy().withLocale(Locale.ENGLISH)
					.withAttribute("text", String.class, a -> a.localized().nullable()
						.searchableInScope(Scope.values()).lengthPivot(25)
						.searchWeight(placeKind == 1 ? valueOf(3) : valueOf(1)))
					.withAttribute("other", String.class, a -> a.localized().nullable().searchable())
					.withAttribute("scopeCounter", String[].class,
						a -> a.localized().nullable().searchableInScope(Scope.values()))
					.withAttribute("hidden", String.class, a -> a.localized().nullable())
					.withAttribute("liveOnly", String.class, a -> a.localized().nullable().searchable())
					.withAssociatedData("text", String.class, a -> a.localized().nullable()
						.searchableInScope(Scope.values()).lengthPivot(25)
						.searchWeight(placeKind == 2 ? valueOf(3) : valueOf(1)))
					.withAssociatedData("hidden", String.class, a -> a.localized().nullable())
					.withAssociatedData("liveOnly", String.class, a -> a.localized().nullable().searchable())
					.withReferenceToEntity("r", target, Cardinality.ZERO_OR_MORE,
						r -> r.indexedForFilteringInScope(Scope.values())
						.searchableThroughInScope(COMPOSITION, valueOf(0.5), Scope.values())
						.withAttribute("text", String.class, a -> a.localized().nullable()
						.searchableInScope(Scope.values()).lengthPivot(25)
							.searchWeight(placeKind == 3 ? valueOf(3) : valueOf(1)))
						.withAttribute("hidden", String.class, a -> a.localized().nullable())
						.withAttribute("liveOnly", String.class, a -> a.localized().nullable().searchable()))
					.withReferenceToEntity("s", "BaseBlock", Cardinality.ZERO_OR_MORE,
						r -> r.indexedForFilteringInScope(Scope.values())
							.searchableThroughInScope(COMPOSITION, valueOf(0.5), Scope.values()))
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
			session.defineEntitySchema("Scopes").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
				.withAttribute("text", String.class, a -> a.localized().nullable().searchableInScope(Scope.values()))
				.withAssociatedData("text", String.class,
					a -> a.localized().nullable().searchableInScope(Scope.values()))
				.updateVia(session);
			for (int id = 1; id <= 4; id++) {
				final EntityBuilder entity = session.createNewEntity("Scopes", id);
				if (id % 2 == 1) entity.setAttribute("text", Locale.ENGLISH, "quartz");
				else entity.setAssociatedData("text", Locale.ENGLISH, "quartz");
				session.upsertEntity(entity);
				if (id >= 3) session.archiveEntity("Scopes", id);
			}
			session.defineEntitySchema("Predicate").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
				.withReferenceTo("r", "Tag", Cardinality.ZERO_OR_MORE, r -> r.indexedForFiltering()
					.withAttribute("a", String.class, a -> a.localized().nullable().searchable())
					.withAttribute("b", String.class, a -> a.localized().nullable().searchable()))
				.updateVia(session);
			for (int id = 1; id <= 3; id++) {
				final int key = id;
				session.upsertEntity(session.createNewEntity("Predicate", id).setReference("r", 100 + id,
					r -> r.setAttribute("a", Locale.ENGLISH, key == 2 ? "plain" : "quartz")
						.setAttribute("b", Locale.ENGLISH, key == 1 ? "plain" : "quartz")));
			}
			session.defineEntitySchema("FacetTree").withoutGeneratedPrimaryKey().withHierarchy()
				.withLocale(Locale.ENGLISH)
				.withAttribute("targetOnly", String.class, a -> a.localized().nullable().searchable())
				.withAssociatedData("text", String.class, a -> a.localized().nullable().searchable())
				.updateVia(session);
			for (final String type : new String[]{"FacetOwner", "EmptyFacetOwner"}) {
				session.defineEntitySchema(type).withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
					.withAttribute("text", String.class, a -> a.localized().nullable().searchable())
					.withAssociatedData("text", String.class, a -> a.localized().nullable().searchable())
					.withReferenceToEntity("r", "FacetTree", Cardinality.ZERO_OR_MORE,
						r -> r.indexedForFiltering().faceted()).updateVia(session);
			}
			session.upsertEntity(session.createNewEntity("FacetTree", 104)
				.setAttribute("targetOnly", Locale.ENGLISH, "quartz")
				.setAssociatedData("text", Locale.ENGLISH, "quartz"));
			session.upsertEntity(session.createNewEntity("FacetTree", 105).setParent(104)
				.setAttribute("targetOnly", Locale.ENGLISH, "quartz")
				.setAssociatedData("text", Locale.ENGLISH, "quartz"));
			session.upsertEntity(session.createNewEntity("FacetOwner", 1).setReference("r", 104)
				.setAttribute("text", Locale.ENGLISH, "quartz"));
			session.upsertEntity(session.createNewEntity("FacetOwner", 2).setReference("r", 105)
				.setAssociatedData("text", Locale.ENGLISH, "quartz"));
			// A self-reference must use schema defaults for its target even when the owner's own place is overridden.
			session.defineEntitySchema("Self").withoutGeneratedPrimaryKey().withLocale(Locale.ENGLISH)
				.withAttribute("text", String.class, a -> a.localized().nullable().searchable())
				.withReferenceToEntity("r", "Self", Cardinality.ZERO_OR_MORE,
					r -> r.indexedForFilteringInScope(Scope.values())
							.searchableThroughInScope(COMPOSITION, valueOf(0.5), Scope.values())).updateVia(session);
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

	/**
	 * Maps each schema-copy change to its query override, including both composed place kinds.
	 * @param kind expected kind
	 * @param weight decimal ranking contribution supplied by this case
	 */
	@Nonnull
	private static RelevanceText override(int kind, @Nonnull BigDecimal weight) {
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
			final Query weighted = request("Base", textMatches("quartz "), override(kind, valueOf(3)));
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
			final Query weighted = request("Base", filter, override(3, valueOf(3)));
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
				relevanceText(attributeWeight("text", valueOf(3)),
					referenceWeight("r", entityWeight(attributeWeight("text", valueOf(3))))));
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
				referenceWeight("r", entityWeight(attributeWeight("text", valueOf(3)))),
				referenceWeight("s", entityWeight(attributeWeight("text", valueOf(5))))));
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
			final Query weighted = request("Base", textMatches("quartz "), override(kind, BigDecimal.ZERO));
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
					final Query weighted = request("Base", textMatches("quartz "), override(kind, valueOf(3)));
					final Query baseline = request("Base", textMatches("quartz "), override(kind, BigDecimal.ONE));
					final QueryPlanningContext weightedContext = context(session, weighted, this.cache);
					final QueryPlanningContext baselineContext = context(session, baseline, this.cache);
					final QueryPlan weightedPlan = QueryPlanner.planQuery(weightedContext);
					final QueryPlan baselinePlan = QueryPlanner.planQuery(baselineContext);
					assertEquals(weightedContext.getFulltextScoringPolicy()
						.computeHash(net.openhft.hashing.LongHashFunction.xx3()),
						baselineContext.getFulltextScoringPolicy()
						.computeHash(net.openhft.hashing.LongHashFunction.xx3()));
					final int[] expected = switch (kind) {
						case 1 -> new int[]{1, 7, 2, 3, 4, 5, 6};
						case 2 -> new int[]{2, 7, 1, 3, 4, 5, 6};
						case 3 -> new int[]{3, 7, 1, 2, 4, 5, 6};
						case 4 -> new int[]{4, 7, 1, 2, 3, 5, 6};
						case 5 -> new int[]{5, 7, 1, 2, 3, 4, 6};
						default -> throw new IllegalArgumentException("Unknown place kind " + kind);
					};
					assertArrayEquals(expected, execute(weightedPlan));
					assertArrayEquals(DEFAULT_ORDER, execute(baselinePlan));
					assertArrayEquals(weightedPlan.getFilter().compute().getArray(),
						baselinePlan.getFilter().compute().getArray());
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
				relevanceText(attributeWeight("text", valueOf(3)))), new int[]{1, 2});
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
			final long[] weighted = scores(session, request("Self", filter, override(1, valueOf(3))), new int[]{1, 2});
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
				attributeWeight("text", valueOf(2)), associatedDataWeight("text", valueOf(3)))), limit(1)),
				segment(orderBy(relevanceText(associatedDataWeight("text", valueOf(3)),
					attributeWeight("text", valueOf(2)))))));
			assertArrayEquals(new int[]{1, 2}, ids(session, request("Base", filter, relevanceText())));
			assertArrayEquals(new int[]{2, 1}, ids(session, query));
		});
	}

	/** Equivalent place maps retain the same ranking despite different reference-container grouping. */
	@Test
	@DisplayName("Accepts equivalent policies with split reference containers")
	void shouldAcceptEquivalentReferencePolicies() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final RelevanceText grouped = relevanceText(referenceWeight("r", attributeWeight("text", valueOf(2)),
				entityWeight(attributeWeight("text", valueOf(3)))));
			final RelevanceText split = relevanceText(
				referenceWeight("r", entityWeight(attributeWeight("text", valueOf(3)))),
				referenceWeight("r", attributeWeight("text", valueOf(2))));
			assertArrayEquals(new int[]{4, 3, 7, 1, 2, 5, 6}, ids(session, request("Base", textMatches("quartz "),
				segments(segment(orderBy(grouped), limit(1)), segment(orderBy(split))))));
		});
	}

	/** Segment predicate weights are local; a later segment reads only main-filter sources. */
	@Test
	@DisplayName("Accepts a place searched by a segment predicate")
	void shouldRecognizePlacesSearchedBySegments() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final RelevanceText weights = override(2, valueOf(3));
			final Query query = request("Base",
				or(textMatches("quartz ", inAttribute("text")), entityPrimaryKeyInSet(2)),
				segments(segment(entityHaving(textMatches("quartz ", inAssociatedData("text"))),
					orderBy(weights, entityPrimaryKeyNatural(ASC))),
				segment(orderBy(relevanceText(), entityPrimaryKeyNatural(ASC)))));
			assertArrayEquals(new int[]{2, 1}, ids(session, query));
		});
	}

	/** Auxiliary locale contexts retain schema defaults rather than capturing an outer ordering's weights. */
	@Test
	@DisplayName("Keeps auxiliary locale preparation independent of ordering weights")
	void shouldKeepLocalePreparationAtSchemaDefaults() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final Query query = request("Base", textMatches("quartz "),
				relevanceText(attributeWeight("text", valueOf(3)),
				referenceWeight("r", entityWeight(attributeWeight("text", valueOf(3))))));
			final QueryPlanningContext context = context(session, query, NoCacheSupervisor.INSTANCE);
			QueryPlanner.planQuery(context);
			final QueryPlanningContext localized = context.getContextInLocale("Base", filterBy(textMatches("quartz ")),
				Locale.FRENCH, java.util.Set.of(Scope.LIVE), "resolve auxiliary locale");
			final FulltextScoringPolicy policy = localized.getFulltextScoringPolicy();
			assertEquals(1f, policy.weight(io.evitadb.index.fulltext.FulltextFieldKey.attribute("text")));
			assertEquals(2, context.getFulltextWeights((RelevanceText) query.getOrderBy().getChildren()[0]).size());
		});
	}

	/** Planning validates query weights while keeping the prepared search policy at schema defaults. */
	@Test
	@DisplayName("Keeps schema defaults before and after planning")
	void shouldKeepPreparationDefaultsAfterPlanning() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final Query query = request("Base", textMatches("quartz "), override(1, valueOf(3)));
			final QueryPlanningContext context = context(session, query, NoCacheSupervisor.INSTANCE);
			assertEquals(1f, context.getFulltextScoringPolicy()
				.weight(io.evitadb.index.fulltext.FulltextFieldKey.attribute("text")));
			assertSame(context.getFulltextSchemaScoringPolicy(context.getSchema()), context.getFulltextScoringPolicy());
			QueryPlanner.planQuery(context);
			assertEquals(1f, context.getFulltextScoringPolicy()
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
			final Query weighted = request("Self", filter, relevanceText(attributeWeight("text", BigDecimal.ZERO)));
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
			assertArrayEquals(ALL, ids(session, request("Base", null, override(1, valueOf(3)))));
			final QueryPlanningContext context = context(session, request("Base", null, relevanceText()),
				NoCacheSupervisor.INSTANCE);
			QueryPlanner.planQuery(context);
			assertNull(field(context, "fulltextScoringPolicies"));
		});
	}

	/** Float overflow and underflow fail at planning for every place, before empty-index shortcuts. */
	@ParameterizedTest
	@CsvSource({"1E+39, overflows", "1E-100, underflows"})
	@DisplayName("Refuses weights outside the internal float range on populated and empty collections")
	void shouldRejectWeightsOutsideFloatRange(@Nonnull String number, @Nonnull String reason) {
		final BigDecimal value = new BigDecimal(number);
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			for (final String type : new String[]{"Base", "Empty"}) {
				for (final FilterConstraint filter : new FilterConstraint[]{textMatches("quartz "), null}) {
					for (int kind = 1; kind <= 5; kind++) {
						final RelevanceText weight = override(kind, value);
						final Query query = request(type, filter, weight);
						final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
							() -> ids(session, query));
						assertTrue(error.getMessage().contains(deepest(weight).toString()), error.getMessage());
						assertTrue(error.getMessage().contains(reason), error.getMessage());
					}
				}
			}
		});
	}

	/** Zero and finite subnormal weights are valid at planning, also with no matching data. */
	@ParameterizedTest
	@ValueSource(strings = {"0", "0.0000001", "1.4E-45", "3.4028235E+38"})
	@DisplayName("Accepts zero and representable weights at the internal float boundaries")
	void shouldAcceptWeightsInsideFloatRange(@Nonnull String number) {
		final BigDecimal value = new BigDecimal(number);
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			for (final String type : new String[]{"Base", "Empty"}) {
				final Query query = request(type, null, override(1, value));
				assertArrayEquals(type.equals("Empty") ? new int[0] : ALL,
					assertDoesNotThrow(() -> ids(session, query)));
			}
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
			attributeWeight("hidden", valueOf(2)), associatedDataWeight("hidden", valueOf(2)),
			referenceWeight("r", attributeWeight("hidden", valueOf(2))),
			referenceWeight("r", entityWeight(attributeWeight("hidden", valueOf(2)))),
			referenceWeight("r", entityWeight(associatedDataWeight("hidden", valueOf(2)))),
			referenceWeight("raw", entityWeight(attributeWeight("text", valueOf(2)))), referenceWeight("r"),
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
		cases.add(new InvalidCase(relevanceText(attributeWeight("missing", valueOf(2))), textMatches("quartz "),
			"attributeWeight('missing',2)", AttributeNotFoundException.class));
		cases.add(new InvalidCase(relevanceText(associatedDataWeight("missing", valueOf(2))), textMatches("quartz "),
			"associatedDataWeight('missing',2)", AssociatedDataNotFoundException.class));
		cases.add(new InvalidCase(relevanceText(referenceWeight("missing", attributeWeight("text", valueOf(2)))),
			textMatches("quartz "), "referenceWeight('missing',attributeWeight('text',2))",
			ReferenceNotFoundException.class));
		cases.add(new InvalidCase(relevanceText(referenceWeight("r", attributeWeight("missing", valueOf(2)))),
			textMatches("quartz "), "attributeWeight('missing',2)", AttributeNotFoundException.class));
		cases.add(new InvalidCase(
			relevanceText(referenceWeight("r", entityWeight(attributeWeight("missing", valueOf(2))))),
			textMatches("quartz "), "attributeWeight('missing',2)", AttributeNotFoundException.class));
		cases.add(new InvalidCase(
			relevanceText(referenceWeight("r", entityWeight(associatedDataWeight("missing", valueOf(2))))),
			textMatches("quartz "), "associatedDataWeight('missing',2)", AssociatedDataNotFoundException.class));
		for (final RelevanceTextEntitySpecificationOrderConstraint weight :
			new RelevanceTextEntitySpecificationOrderConstraint[]{
			attributeWeight("text", valueOf(2)), associatedDataWeight("text", valueOf(2)),
			referenceWeight("r", attributeWeight("text", valueOf(2))),
			referenceWeight("r", entityWeight(attributeWeight("text", valueOf(2))))
		}) {
			cases.add(new InvalidCase(relevanceText(weight, weight), textMatches("quartz "),
				deepest(weight).toString(), EvitaInvalidUsageException.class));
			cases.add(new InvalidCase(relevanceText(weight), textMatches("quartz ", inAttribute("other")),
				deepest(weight).toString(), EvitaInvalidUsageException.class));
		}
		cases.add(new InvalidCase(relevanceText(attributeWeight("text", valueOf(2))),
			referenceHaving("r", entityHaving(textMatches("quartz "))), "attributeWeight('text',2)",
			EvitaInvalidUsageException.class));
		cases.add(new InvalidCase(relevanceText(referenceWeight("r", attributeWeight("text", valueOf(2)),
			attributeWeight("text", valueOf(3)))), textMatches("quartz "), "attributeWeight('text',3)",
			EvitaInvalidUsageException.class));
		cases.add(new InvalidCase(relevanceText(referenceWeight("r", entityWeight(attributeWeight("text", valueOf(2))),
			entityWeight(attributeWeight("text", valueOf(3))))), textMatches("quartz "), "attributeWeight('text',3)",
			EvitaInvalidUsageException.class));

		cases.add(new InvalidCase(segments(segment(entityHaving(textMatches("quartz ", inAssociatedData("text"))),
			orderBy(override(1, valueOf(2)), entityPrimaryKeyNatural(ASC)))), null, "attributeWeight('text',2)",
			EvitaInvalidUsageException.class));

		for (final OrderConstraint stray : new OrderConstraint[]{attributeWeight("text", valueOf(2)),
			associatedDataWeight("text", valueOf(2)), referenceWeight("r", attributeWeight("text", valueOf(2))),
			entityWeight(attributeWeight("text", valueOf(2))), referenceProperty("r", override(1, valueOf(2))),
			entityProperty(override(1, valueOf(2))), entityGroupProperty(override(1, valueOf(2))),
			traverseByEntityProperty(override(1, valueOf(2))), pickFirstByEntityProperty(override(1, valueOf(2)))}) {
			cases.add(new InvalidCase(stray, textMatches("quartz "),
				stray instanceof RelevanceTextEntitySpecificationOrderConstraint ||
					stray instanceof io.evitadb.api.query.order.EntityWeight ?
					stray.toString() : override(1, valueOf(2)).toString(),
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
				attributeWeight("missing", valueOf(2)),
				referenceWeight("r", attributeWeight("missing", valueOf(2))),
				referenceWeight("r", entityWeight(attributeWeight("missing", valueOf(2))))
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
				for (final OrderConstraint child : new OrderConstraint[]{attributeWeight("text", valueOf(2)),
					associatedDataWeight("text", valueOf(2)), referenceWeight("r", attributeWeight("text", valueOf(2))),
					entityWeight(attributeWeight("text", valueOf(2))), override(1, valueOf(2))}) {
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
					new RelevanceTextEntitySpecificationOrderConstraint[]{attributeWeight("liveOnly", valueOf(2)),
						associatedDataWeight("liveOnly", valueOf(2)),
						referenceWeight("r", attributeWeight("liveOnly", valueOf(2))),
						referenceWeight("r", entityWeight(attributeWeight("liveOnly", valueOf(2)))),
						referenceWeight("r", entityWeight(associatedDataWeight("liveOnly", valueOf(2)))),
						referenceWeight("live", entityWeight(attributeWeight("text", valueOf(2))))}) {
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
				final Query weighted = fetchedSearch(type, relevanceText(attributeWeight("other", valueOf(3))));
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

	/** Every weighted place must be searchable throughout the ordering's processed scopes, even without data. */
	@ParameterizedTest
	@ValueSource(ints = {1, 2, 3, 4, 5})
	@DisplayName("Refuses partial scope capabilities and accepts the narrowed ordering")
	void shouldRequireEveryOrderingScope(int kind) {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final String word = new String[]{"cobalt", "ochre", "opal", "zircon", "jasper"}[kind - 1];
			final RelevanceText weighted = oneScopeWeight(kind);
			for (final String type : new String[]{"Base", "Empty"}) {
				for (final FilterConstraint filter : new FilterConstraint[]{null, textMatches(word + " ")}) {
					final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
						() -> ids(session, scopedSearch(type, filter, weighted)));
					assertTrue(error.getMessage().contains("ARCHIVED"), error.getMessage());
					assertTrue(error.getMessage().contains("inScope"), error.getMessage());
				}
			}
			final Query narrowed = query(collection("Base"), filterBy(scope(Scope.LIVE, Scope.ARCHIVED),
				entityLocaleEquals(Locale.ENGLISH), textMatches(word + " ")),
				orderBy(inScope(Scope.LIVE, weighted), inScope(Scope.ARCHIVED, relevanceText())));
			assertArrayEquals(kind <= 3 ? new int[]{2, 1} : new int[]{4, 6}, ids(session, narrowed));
			assertArrayEquals(new int[0], ids(session, query(collection("Empty"), narrowed.getFilterBy(),
				narrowed.getOrderBy())));
		});
	}

	/** Composition itself must be declared in every processed ordering scope. */
	@Test
	@DisplayName("Requires composition in every ordering scope on populated and empty collections")
	void shouldRequireCompositionInEveryScope() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final RelevanceText weight = relevanceText(
				referenceWeight("live", entityWeight(attributeWeight("text", valueOf(3)))));
			for (final String type : new String[]{"Base", "Empty"}) {
				final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
					() -> ids(session, scopedSearch(type, null, weight)));
				assertTrue(error.getMessage().contains("ARCHIVED"), error.getMessage());
				assertTrue(error.getMessage().contains("COMPOSITION"), error.getMessage());
				assertTrue(error.getMessage().contains("inScope"), error.getMessage());
			}
		});
	}

	/** Both sorters read the same prepared formula; the second must not inherit the first expansion memo. */
	@Test
	@DisplayName("Ranks two segments by their own schema-equivalent weights without score memo leakage")
	void shouldRankSegmentsWithIndependentWeights() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final FilterConstraint text = textMatches("quartz ");
			final int[] first = ids(session, request("OwnAttribute", text, relevanceText()));
			final int[] second = ids(session, request("OwnData", and(text, not(entityPrimaryKeyInSet(1))),
				relevanceText()));
			assertArrayEquals(new int[]{1, 7, 2, 3, 4, 5, 6}, first);
			assertArrayEquals(new int[]{2, 7, 3, 4, 5, 6}, second);
			final Query query = request("Base", text, segments(
				segment(orderBy(override(1, valueOf(3))), limit(1)),
				segment(orderBy(override(2, valueOf(3))))));
			assertArrayEquals(new int[]{1, 2, 7, 3, 4, 5, 6}, ids(session, query));
			assertEquals(first[0], ids(session, query)[0]);
			assertArrayEquals(second, Arrays.copyOfRange(ids(session, query), 1, 7));
		});
	}

	/** Ordering scopes carry independent override maps while sharing one filter preparation. */
	@Test
	@DisplayName("Ranks LIVE and ARCHIVED with different relevance weights")
	void shouldRankOrderingScopesWithIndependentWeights() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final Query query = query(collection("Scopes"), filterBy(scope(Scope.LIVE, Scope.ARCHIVED),
				entityLocaleEquals(Locale.ENGLISH), textMatches("quartz ")),
				orderBy(inScope(Scope.LIVE, override(2, valueOf(3))),
					inScope(Scope.ARCHIVED, override(1, valueOf(3)))));
			assertArrayEquals(new int[]{2, 1, 3, 4}, ids(session, query));
		});
	}

	/** Segment-only searches rank, and memoized predicates retain direct-reference additional score sources. */
	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2})
	@DisplayName("Ranks segment-only own, reference-body and composed text with independent weights")
	void shouldScoreSegmentPredicatesAndMemoHits(int kind) {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final FilterConstraint predicate = switch (kind) {
				case 1 -> referenceHaving("r", textMatches("quartz ", inAttribute("text")));
				case 2 -> textMatches("quartz ", inReference("r", inEntity(inAttribute("text"))));
				default -> textMatches("quartz ");
			};
			final RelevanceText weighted = kind == 1 ? override(3, valueOf(3)) :
				kind == 2 ? override(4, valueOf(3)) : override(2, valueOf(3));
			final Query query = request("Base", null, segments(
				segment(entityHaving(predicate), orderBy(weighted), limit(1)),
				segment(entityHaving(predicate), orderBy(relevanceText()))));
			assertArrayEquals(switch (kind) {
				case 1 -> new int[]{3, 1, 2, 4, 5, 6, 7};
				case 2 -> new int[]{4, 1, 2, 3, 5, 6, 7};
				default -> new int[]{2, 7, 1, 3, 4, 5, 6};
			}, ids(session, query));
		});
	}

	/** Repeated predicates must carry additional reference sources on memo hits and accept different weight sets. */
	@Test
	@DisplayName("Retains reference-body score sources across repeated segment predicate memo hits")
	void shouldRetainAdditionalSegmentScoresAcrossMemoHits() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final FilterConstraint predicate = referenceHaving("r", textMatches("quartz "));
			final RelevanceText first = relevanceText(referenceWeight("r",
				attributeWeight("a", valueOf(3)), attributeWeight("b", BigDecimal.ZERO)));
			final RelevanceText second = relevanceText(referenceWeight("r",
				attributeWeight("a", BigDecimal.ZERO), attributeWeight("b", valueOf(3))));
			assertArrayEquals(new int[]{3, 1, 2}, ids(session, request("Predicate", null,
				segments(segment(entityHaving(predicate), orderBy(relevanceText()))))));
			assertArrayEquals(new int[]{1, 3, 2}, ids(session, request("Predicate", null, segments(
				segment(entityHaving(predicate), orderBy(first), limit(1)),
				segment(entityHaving(predicate), orderBy(relevanceText()))))));
			assertArrayEquals(new int[]{1, 2, 3}, ids(session, request("Predicate", null, segments(
				segment(entityHaving(predicate), orderBy(first), limit(1)),
				segment(entityHaving(predicate), orderBy(second))))));
		});
	}

	/** Equal reference searches remain one source when the main filter and a segment both reach them. */
	@Test
	@DisplayName("Combines segment and main reference sources without counting an equal place twice")
	void shouldCollapseEqualMainAndSegmentReferenceSources() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final FilterConstraint predicate = referenceHaving("r", textMatches("quartz "));
			assertArrayEquals(new int[]{3, 1, 2}, ids(session, request("Predicate", null,
				segments(segment(entityHaving(predicate), orderBy(relevanceText()))))));
			final Query query = request("Predicate", predicate,
				segments(segment(entityHaving(predicate), orderBy(relevanceText()))));
			assertArrayEquals(new int[]{3, 1, 2}, ids(session, query));
			final QueryPlanningContext context = context(session, query, NoCacheSupervisor.INSTANCE);
			final QueryPlan plan = QueryPlanner.planQuery(context);
			plan.execute();
			final List<Sorter> embedded =
				field(plan.getSorters().iterator().next(), "embeddedSorters");
			final FulltextScoreAccessor[] sources = field(embedded.getFirst(), "accessors");
			assertEquals(1, sources.length);
			assertArrayEquals(scores(session, request("Predicate", predicate, relevanceText()), new int[]{1, 2, 3}),
				FulltextScoreCombiner.combine(new int[]{1, 2, 3}, sources,
					context.getFulltextScoringPolicy(), Map.of()).composites());
		});
	}

	/**
	 * A predicate adds its evidence to the main filter; another segment and later top-level relevance cannot read it.
	 */
	@Test
	@DisplayName("Combines main and segment scores and keeps predicate sources private")
	void shouldCombineMainAndSegmentScoresLocally() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final FilterConstraint main = or(textMatches("quartz ", inAttribute("text")),
				entityPrimaryKeyInSet(2));
			final FilterConstraint predicate = textMatches("quartz ", inAssociatedData("text"));
			assertArrayEquals(new int[]{2, 1, 3, 4, 5, 6, 7}, ids(session, request("Base", null,
				segments(segment(entityHaving(predicate), orderBy(relevanceText())),
					segment(orderBy(relevanceText()))))));
			assertArrayEquals(new int[]{1, 2}, ids(session, request("Base", main,
				segments(segment(entityHaving(or(predicate, entityPrimaryKeyInSet(1))),
					orderBy(override(1, valueOf(3))))))));
			assertArrayEquals(new int[]{2, 1}, ids(session, request("Base", main,
				segments(segment(entityHaving(or(predicate, entityPrimaryKeyInSet(1))),
					orderBy(override(2, valueOf(3))))))));
			assertArrayEquals(new int[]{2, 1, 3, 4, 5, 6, 7}, ids(session, request("Base", null, segments(
				segment(entityHaving(textMatches("quartz ")), orderBy(override(2, valueOf(3))), limit(1)),
				segment(orderBy(relevanceText()))))));
			assertArrayEquals(new int[]{2, 1, 3, 4, 5, 6, 7}, ids(session, request("Base", null, orderBy(
				segments(segment(entityHaving(textMatches("quartz ")),
					orderBy(override(2, valueOf(3))), limit(1))), relevanceText()))));
		});
	}

	/** Another segment and a negative direct reference occurrence cannot consume this ordering's weights. */
	@Test
	@DisplayName("Rejects weights searched only in another segment or in negative reference text")
	void shouldRejectWeightsOutsideScoringReach() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			for (final String type : new String[]{"Base", "Empty"}) {
				final Query otherSegment = request(type, textMatches("quartz ", inAttribute("text")), segments(
					segment(entityHaving(textMatches("quartz ", inAssociatedData("text"))), orderBy(relevanceText())),
					segment(orderBy(override(2, valueOf(3))))));
				final Query negative = request(type, or(textMatches("quartz ", inAttribute("text")),
					referenceHaving("r", not(textMatches("quartz ", inAttribute("text"))))),
					override(3, valueOf(3)));
				for (final Query query : new Query[]{otherSegment, negative}) {
					final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
						() -> ids(session, query));
					assertTrue(error.getMessage().contains("not scored"), error.getMessage());
				}
				assertDoesNotThrow(() -> ids(session, request(type,
					referenceHaving("r", not(not(textMatches("quartz ", inAttribute("text"))))),
					override(3, valueOf(3)))));
				final Query hierarchyInSegment = request(type, null, segments(segment(
					entityHaving(hierarchyWithinRootSelf(having(textMatches("quartz ")))), orderBy(relevanceText()))));
				final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
					() -> ids(session, hierarchyInSegment));
				assertTrue(error.getMessage().contains("filters the tree and is not scored"), error.getMessage());
			}
		});
	}

	/**
	 * A `not` outside `referenceHaving` leaves the reference text a scoring search, exactly like negated own text:
	 * inside a disjunction its matching owners can be part of the result, so a weight on that place stays accepted.
	 */
	@Test
	@DisplayName("Accepts a reference attribute weight when the reference search stands under an outer negation")
	void shouldAcceptWeightOfReferenceTextUnderOuterNegation() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			for (final String type : new String[]{"Base", "Empty"}) {
				final FilterConstraint negated = not(referenceHaving("r", textMatches("quartz ", inAttribute("text"))));
				assertDoesNotThrow(() -> ids(session, request(type, negated, override(3, valueOf(3)))));
				assertDoesNotThrow(() -> ids(session, request(type,
					or(negated, entityPrimaryKeyInSet(1, 2, 3, 4, 5, 6, 7)), override(3, valueOf(3)))));
			}
		});
	}

	/** Pass-through relevance keeps the previous weight checks when all text is in require or another segment. */
	@Test
	@DisplayName("Preserves weight validation for pass-through relevance with only external text searches")
	void shouldPreservePassThroughWeightValidation() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			for (final String type : new String[]{"Base", "Empty"}) {
				final int[] expected = type.equals("Empty") ? new int[0] : ALL;
				final Query elsewhere = request(type, null, orderBy(override(1, valueOf(3)),
					segments(segment(entityHaving(textMatches("quartz ")), orderBy(entityPrimaryKeyNatural(ASC))))));
				assertArrayEquals(expected, ids(session, elsewhere));
				final Query requireOnly = query(collection(type), filterBy(entityLocaleEquals(Locale.ENGLISH)),
					orderBy(override(1, valueOf(3))), require(entityFetch(referenceContent("r",
						filterBy(entityHaving(textMatches("quartz ")))))));
				assertThrows(EvitaInvalidUsageException.class, () -> session.query(requireOnly, SealedEntity.class));
				final Query childless = query(collection(type), requireOnly.getFilterBy(), orderBy(relevanceText()),
					requireOnly.getRequire());
				assertArrayEquals(expected, session.query(childless, SealedEntity.class).getRecordData().stream()
					.mapToInt(SealedEntity::getPrimaryKey).toArray());
			}
		});
	}

	/** Hierarchy searches filter tree membership; they never establish a scored place, even on empty data. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("Rejects hierarchy-only relevance and weights read only by hierarchy having or excluding")
	void shouldRejectNonScoringHierarchyText(boolean exclude) {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			for (final String type : new String[]{"Base", "Empty"}) {
				final FilterConstraint tree = hierarchyWithinRootSelf(exclude ?
					excluding(textMatches("quartz ", inAssociatedData("text"))) :
					having(textMatches("quartz ", inAssociatedData("text"))));
				final EvitaInvalidUsageException only = assertThrows(EvitaInvalidUsageException.class,
					() -> ids(session, request(type, tree, relevanceText())));
				assertTrue(only.getMessage().contains("filters the tree and is not scored"), only.getMessage());
				final EvitaInvalidUsageException unread = assertThrows(EvitaInvalidUsageException.class,
					() -> ids(session, request(type, and(textMatches("quartz ", inAttribute("text")), tree),
						override(2, valueOf(3)))));
				assertTrue(unread.getMessage().contains("no scoring textMatches"), unread.getMessage());
				assertTrue(unread.getMessage().contains("not scored"), unread.getMessage());
				assertArrayEquals(type.equals("Empty") ? new int[0] : new int[]{1}, ids(session,
					request(type, hierarchyWithinSelf(textMatches("quartz ", inAttribute("text"))), relevanceText())));
			}
			assertArrayEquals(new int[0], ids(session,
				request("Empty", textMatches("quartz "), override(1, valueOf(3)))));
		});
	}

	/** Equal searches prepared in different scope sets are one scoring source, for own and composed places. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("Collapses an equal main and narrowed predicate search without changing the public order")
	void shouldCollapseScopeNarrowedSearchCopies(boolean composed) {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final FilterConstraint first = composed ?
				textMatches("quartz ", inReference("r", inEntity(inAttribute("text")))) :
				textMatches("quartz ", inAttribute("text"));
			final FilterConstraint second = textMatches("quartz ", inAssociatedData("text"));
			final var filter = filterBy(scope(Scope.values()), entityLocaleEquals(Locale.ENGLISH), or(first, second));
			final RelevanceText relevance = relevanceText(associatedDataWeight("text", new BigDecimal("1.1")));
			final Query control = query(collection("Base"), filter, orderBy(inScope(Scope.LIVE, relevance)),
				require(page(1, 100)));
			final Query duplicate = query(collection("Base"), filter, orderBy(inScope(Scope.LIVE,
				segments(segment(entityHaving(or(first, entityPrimaryKeyInSet(2))), orderBy(relevance))))),
				require(page(1, 100)));
			final int[] expected = composed ? new int[]{2, 4} : new int[]{2, 1};
			assertArrayEquals(expected, ids(session, control));
			assertArrayEquals(expected, ids(session, duplicate));
		});
	}

	/** The same preparation hazard occurs inside one visitor when overlapping scope containers repeat a search. */
	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	@DisplayName("Collapses equal searches prepared twice inside one main filter")
	void shouldCollapseEqualSearchesWithinOneFilter(boolean composed) {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final FilterConstraint first = composed ?
				textMatches("quartz ", inReference("r", inEntity(inAttribute("text")))) :
				textMatches("quartz ", inAttribute("text"));
			final Query duplicate = query(collection("Base"), filterBy(scope(Scope.values()),
				entityLocaleEquals(Locale.ENGLISH), or(first, inScope(Scope.LIVE, first),
					textMatches("quartz ", inAssociatedData("text")))),
				orderBy(inScope(Scope.LIVE,
					relevanceText(associatedDataWeight("text", new BigDecimal("1.1"))))));
			assertArrayEquals(composed ? new int[]{2, 4} : new int[]{2, 1}, ids(session, duplicate));
			final Query twoContainers = query(collection("Base"), filterBy(scope(Scope.values()),
				entityLocaleEquals(Locale.ENGLISH), or(inScope(Scope.LIVE, first),
					inScope(Scope.LIVE, or(first, entityPrimaryKeyInSet(2))),
					textMatches("quartz ", inAssociatedData("text")))), duplicate.getOrderBy());
			assertArrayEquals(composed ? new int[]{2, 4} : new int[]{2, 1}, ids(session, twoContainers));
		});
	}

	/** Target-tree selectors must stay outside the owner's schema namespace, even when the owner is empty. */
	@ParameterizedTest
	@org.junit.jupiter.params.provider.CsvSource({"FacetOwner,false", "EmptyFacetOwner,false",
		"FacetOwner,true", "EmptyFacetOwner,true"})
	@DisplayName("Accepts target-only facet descendant selectors with relevance ordering")
	void shouldKeepFacetDescendantsInTargetTree(@Nonnull String type, boolean exclude) {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final FilterConstraint tree = textMatches("quartz ", inAttribute("targetOnly"));
			final FilterConstraint filter = or(textMatches("quartz ", inAttribute("text")),
				facetHaving("r", entityPrimaryKeyInSet(104),
					exclude ? includingChildrenExcept(tree) : includingChildrenHaving(tree)));
			final int[] expected = type.equals("EmptyFacetOwner") ? new int[0] :
				exclude ? new int[]{1} : new int[]{1, 2};
			assertArrayEquals(expected, ids(session, request(type, filter, entityPrimaryKeyNatural(ASC))));
			assertArrayEquals(expected, ids(session, request(type, filter, relevanceText())));
		});
	}

	/** Facet descendant text only filters the target tree and cannot consume an owner-associated-data weight. */
	@ParameterizedTest
	@org.junit.jupiter.params.provider.CsvSource({"FacetOwner,false", "EmptyFacetOwner,false",
		"FacetOwner,true", "EmptyFacetOwner,true"})
	@DisplayName("Refuses owner weights searched only by facet descendant predicates")
	void shouldRejectFacetDescendantOwnerWeights(@Nonnull String type, boolean exclude) {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final FilterConstraint tree = textMatches("quartz ", inAssociatedData("text"));
			final Query query = request(type, or(textMatches("quartz ", inAttribute("text")),
				facetHaving("r", entityPrimaryKeyInSet(104),
					exclude ? includingChildrenExcept(tree) : includingChildrenHaving(tree))),
				relevanceText(associatedDataWeight("text", valueOf(3))));
			final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
				() -> ids(session, query));
			assertTrue(error.getMessage().contains("not scored"), error.getMessage());
		});
	}

	/** A compact extreme-scale parameter must produce a bounded usage refusal before rendering a query. */
	@Test
	@DisplayName("Refuses extreme underflow without fatal query formatting")
	void shouldRejectExtremeUnderflowSafely() {
		this.evita.queryCatalog(TEST_CATALOG, session -> {
			final EvitaInvalidUsageException error = assertThrows(EvitaInvalidUsageException.class,
				() -> ids(session, request("Base", null, relevanceText(attributeWeight("text",
					new BigDecimal(BigInteger.ONE, Integer.MAX_VALUE))))));
			assertTrue(error.getMessage().length() < 500, error.getMessage());
		});
	}

	/** Selects the same LIVE-only place kind at each supported weight level. */
	@Nonnull
	private static RelevanceText oneScopeWeight(int kind) {
		return switch (kind) {
			case 1 -> relevanceText(attributeWeight("liveOnly", valueOf(3)));
			case 2 -> relevanceText(associatedDataWeight("liveOnly", valueOf(3)));
			case 3 -> relevanceText(referenceWeight("r", attributeWeight("liveOnly", valueOf(3))));
			case 4 -> relevanceText(referenceWeight("r", entityWeight(attributeWeight("liveOnly", valueOf(3)))));
			case 5 -> relevanceText(referenceWeight("r", entityWeight(associatedDataWeight("liveOnly", valueOf(3)))));
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

	/** Executes a production plan using the cache supervisor supplied to its planning context. */
	@Nonnull
	private static int[] execute(@Nonnull QueryPlan plan) {
		return plan.execute().getRecordData().stream()
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
		return FulltextScoreCombiner.combine(
			candidates, sources, context.getFulltextScoringPolicy(), field(sorter, "weights")
		).composites();
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
