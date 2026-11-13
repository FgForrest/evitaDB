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
import io.evitadb.api.query.Query;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.statistics.BrowsedIndex;
import io.evitadb.api.statistics.IndexBrowseCriteria;
import io.evitadb.api.statistics.IndexBrowseOrdering;
import io.evitadb.api.statistics.IndexBrowseResult;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.StepMetric;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins ordinary body-fetch observability and checks runtime planning under both verification modes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Body fetch and runtime verification accounting")
@Tag(ENGINE)
@Tag(QUERY)
class VerificationRecordingRegressionTest implements EvitaTestSupport {
	/** Isolated catalog. */
	private static final String CATALOG = "verificationRecording";
	/** Storage owned by this fixture. */
	private TestPaths paths;
	/** Engine with hierarchical referenced entities and sortable body content. */
	private Evita evita;

	/** Creates a parent-bound hierarchy and several sortable references. */
	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("VerificationRecordingRegressionTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).cache(
			io.evitadb.api.configuration.CacheOptions.builder()
				.enabled(Boolean.getBoolean("formulaCache.regression.enabled"))
				.minimalComplexityThreshold(0).cacheSizeInBytes(100_000_000L)
				.verifyOneHitIn(0).dropEntriesAfterSecondsOfInactivity(0).build()).build());
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(CATALOG, session -> {
			session.defineEntitySchema("Category").withoutGeneratedPrimaryKey().withHierarchy()
				.withAttribute("code", String.class, whichIs -> whichIs.sortable())
				.withAttribute("stop", Boolean.class, whichIs -> whichIs.filterable())
				.withReferenceToEntity("related", "Category", Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(Scope.LIVE)).updateVia(session);
			session.upsertEntity(session.createNewEntity("Category", 1)
				.setAttribute("code", "A").setAttribute("stop", true).setReference("related", 1));
			session.upsertEntity(session.createNewEntity("Category", 2).setParent(1)
				.setAttribute("code", "B").setAttribute("stop", false).setReference("related", 1));
			session.upsertEntity(session.createNewEntity("Category", 3).setParent(1)
				.setAttribute("code", "C").setAttribute("stop", false).setReference("related", 1));
			session.defineEntitySchema("Product").withoutGeneratedPrimaryKey()
				.withReferenceToEntity("categories", "Category", Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(Scope.LIVE)
						.facetedInScope(Scope.LIVE)).updateVia(session);
			session.upsertEntity(session.createNewEntity("Product", 1)
				.setReference("categories", 1).setReference("categories", 2).setReference("categories", 3));
		});
	}

	/** Releases the fixture and its storage. */
	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/** Expected trees and counter deltas are captured by running this same query with the original source files. */
	@Test
	@DisplayName("Preserves the ordinary body-fetch telemetry tree, capability counts and index activity")
	void shouldPreserveOrdinaryBodyFetchAccounting() {
		try (final EvitaSessionContract session = this.evita.createReadOnlySession(CATALOG)) {
			final Map<String, Long> capabilitiesBefore = capabilities();
			final Map<String, Long> activityBefore = activity();
			final EvitaResponse<EntityReference> response = session.query(Query.query(collection("Product"),
				filterBy(referenceHaving("categories", entityHaving(attributeEqualsTrue("stop")))),
				require(referenceSummaryOfReference("categories",
					io.evitadb.api.query.require.FacetStatisticsDepth.COUNTS,
					entityFetch(hierarchyContent(stopAt(node(filterBy(attributeEqualsTrue("stop"))))),
						referenceContent("related", filterBy(entityHaving(attributeEqualsTrue("stop")))))),
					queryTelemetry())), EntityReference.class);
			final String tree = tree(response.getExtraResult(QueryTelemetry.class));
			final Map<String, Long> requested = delta(capabilities(), capabilitiesBefore);
			final Map<String, Long> queried = delta(activity(), activityBefore);
			System.out.println("Ordinary telemetry=" + tree);
			System.out.println("Ordinary capabilities=" + requested);
			System.out.println("Ordinary activity=" + queried);
			System.out.println("Ordinary metrics=" + metrics(response.getExtraResult(QueryTelemetry.class)));
			final String expectedTree =
				"OVERALL[][" +
				"PLANNING[][" +
				"PLANNING_INDEX_USAGE[][" +
				"PLANNING_NESTED_QUERY[filtering reference `categories` by entity `Category` having: " +
				"attributeEquals('stop',true)][" +
				"PLANNING_INDEX_USAGE[][]" +
				"PLANNING_FILTER[Selected index: Index type: Live index: GLOBAL, estimated costs 2][" +
				"PLANNING_FILTER_ALTERNATIVE[Index type: Live index: GLOBAL, estimated costs 2][]]" +
				"PLANNING_SORT[][]]]" +
				"PLANNING_FILTER[Selected index: Index type: Live index: GLOBAL, estimated costs 1][" +
				"PLANNING_FILTER_ALTERNATIVE[Index type: Live index: GLOBAL, estimated costs 1][]" +
				"PLANNING_FILTER_ALTERNATIVE[Index type: REFERENCED_ENTITY composed of 1 indexes (not eligible " +
				"for separate query plan due to: HIGH_CARDINALITY)][]]" +
				"PLANNING_SORT[][]" +
				"PLANNING_EXTRA_RESULT_FABRICATION[][]]" +
				"EXECUTION[][" +
				"EXECUTION_FILTER[][]" +
				"EXECUTION_SORT_AND_SLICE[][]" +
				"EXTRA_RESULTS_FABRICATION[][" +
				"EXTRA_RESULT_ITEM_FABRICATION[ReferenceSummaryProducer][]]]]";
			final String expectedCapabilities =
				"{Category:SchemaCapabilityKey[elementKind=ATTRIBUTE, containerName=null, elementName=stop, " +
				"capability=FILTERABLE, scope=LIVE]=2, Product:SchemaCapabilityKey[elementKind=REFERENCE, " +
				"containerName=null, elementName=categories, capability=FACETED, scope=LIVE]=1}";
			assertAll(
				() -> assertEquals(expectedTree, tree),
				() -> assertEquals("{ESTIMATED_CARDINALITY=1, ACTUAL_CARDINALITY=1, ESTIMATED_COST=1, ACTUAL_COST=0, " +
					"RECORDS_RETURNED=1, IO_FETCH_COUNT=6, IO_FETCHED_SIZE_BYTES=311, PREFETCHED=0}",
					metrics(response.getExtraResult(QueryTelemetry.class)).toString()),
				() -> assertEquals(expectedCapabilities, requested.toString()),
				() -> assertEquals(Map.of("Category:GLOBAL:LIVE:null", 2L, "Product:GLOBAL:LIVE:null", 1L), queried));
		}
	}

	/** Pins deterministic results, telemetry, requested capabilities and index activity when the tier is bypassed. */
	@Test
	@DisplayName("Preserves parent recording with the tier disabled or the read-write gate closed")
	void shouldPreserveBypassedTierRecording() throws Exception {
		this.evita.updateCatalog(CATALOG, writer -> {
			writer.defineEntitySchema("Product").withAttribute("amount", Integer.class,
				attribute -> attribute.filterable()).updateVia(writer);
			writer.upsertEntity(writer.getEntity("Product", 1, attributeContentAll()).orElseThrow()
				.openForWrite().setAttribute("amount", 100));
			writer.goLiveAndClose();
		});
		final boolean enabled = Boolean.getBoolean("formulaCache.regression.enabled");
		final Query[] queries = {
			Query.query(collection("Product"),
				filterBy(referenceHaving("categories", entityHaving(attributeEqualsTrue("stop")))),
				require(referenceSummaryOfReference("categories", io.evitadb.api.query.require.FacetStatisticsDepth.COUNTS,
					entityFetch(hierarchyContent(stopAt(node(filterBy(attributeEqualsTrue("stop"))))),
						referenceContent("related", filterBy(entityHaving(attributeEqualsTrue("stop")))))),
					queryTelemetry(), debug(DebugMode.PREFER_INDEX_SCAN))),
			Query.query(collection("Product"), filterBy(attributeBetween("amount", 50, 150)),
				require(attributeHistogram(10, "amount"), queryTelemetry(), debug(DebugMode.PREFER_INDEX_SCAN)))
		};
		final StringBuilder recording = new StringBuilder(8_192);
		try (final EvitaSessionContract session = enabled ? this.evita.createReadWriteSession(CATALOG) :
			this.evita.createReadOnlySession(CATALOG)) {
			for (final Query query : queries) {
				final Map<String, Long> beforeCapabilities = capabilities();
				final Map<String, Long> beforeActivity = activity();
				final EvitaResponse<EntityReference> response = session.query(query, EntityReference.class);
				recording.append(query).append('\n').append(response.getRecordData()).append('\n')
					.append(response.getTotalRecordCount()).append('\n')
					.append(tree(response.getExtraResult(QueryTelemetry.class))).append('\n')
					.append(metrics(response.getExtraResult(QueryTelemetry.class))).append('\n')
					.append(delta(capabilities(), beforeCapabilities)).append('\n')
					.append(delta(activity(), beforeActivity)).append('\n');
				final io.evitadb.api.requestResponse.extraResult.ReferenceSummary summary =
					response.getExtraResult(io.evitadb.api.requestResponse.extraResult.ReferenceSummary.class);
				if (summary != null) {
					recording.append(summary.prettyPrint()).append('\n');
					for (final io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics group :
						summary.getReferenceStatistics()) {
						appendFetchedContent(recording, group.getGroupEntity());
						for (final io.evitadb.api.requestResponse.extraResult.ReferenceSummary.FacetStatistics facet :
							group.getFacetStatistics()) appendFetchedContent(recording, facet.getFacetEntity());
					}
				}
				final io.evitadb.api.requestResponse.extraResult.AttributeHistogram histogram =
					response.getExtraResult(io.evitadb.api.requestResponse.extraResult.AttributeHistogram.class);
				if (histogram != null) {
					for (final Map.Entry<String, io.evitadb.api.requestResponse.extraResult.HistogramContract> entry :
						histogram.getHistograms().entrySet()) {
						final io.evitadb.api.requestResponse.extraResult.HistogramContract value = entry.getValue();
						recording.append(entry.getKey()).append(':').append(value.getMin()).append(':')
							.append(value.getMax()).append(':').append(value.getOverallCount()).append('\n');
						for (final io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket bucket :
							value.getBuckets()) {
							recording.append(bucket.threshold()).append(':').append(bucket.occurrences()).append(':')
								.append(bucket.relativeFrequency()).append(':').append(bucket.requested()).append('\n');
						}
						appendFetchedContent(recording, value.getMinReferencedEntity().orElse(null));
						appendFetchedContent(recording, value.getMaxReferencedEntity().orElse(null));
					}
				}
			}
		}
		final String fingerprint = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
			.digest(recording.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		System.out.println("Bypassed recording enabled=" + enabled + " fingerprint=" + fingerprint);
		System.out.println(recording);
		assertEquals("7e9a8d7b289352752e882f0caf6d66f1873bfb3f1f827d3671e79f55332437fc",
			fingerprint, recording.toString());
	}

	/** A stop predicate is compiled during fetching and pushes steps directly on its planning context. */
	@ParameterizedTest
	@EnumSource(value = DebugMode.class, names = {"VERIFY_POSSIBLE_CACHING_TREES", "VERIFY_ALTERNATIVE_INDEX_RESULTS"})
	@DisplayName("Records the runtime hierarchy stop predicate once with either verification mode")
	void shouldRecordRuntimeParentPlanningOnce(@Nonnull DebugMode mode) {
		try (final EvitaSessionContract session = this.evita.createReadOnlySession(CATALOG)) {
			final Query plain = Query.query(collection("Category"), filterBy(entityPrimaryKeyInSet(2)),
				require(entityFetch(hierarchyContent(stopAt(node(filterBy(attributeEqualsTrue("stop")))))),
					queryTelemetry(), debug(DebugMode.PREFER_INDEX_SCAN)));
			final Query verified = Query.query(plain.getCollection(), plain.getFilterBy(), require(
				entityFetch(hierarchyContent(stopAt(node(filterBy(attributeEqualsTrue("stop")))))),
				queryTelemetry(), debug(mode, DebugMode.PREFER_INDEX_SCAN)));
			final String expected = tree(session.query(plain, SealedEntity.class).getExtraResult(QueryTelemetry.class));
			final String actual = tree(session.query(verified, SealedEntity.class)
				.getExtraResult(QueryTelemetry.class));
			assertEquals(expected, actual);
		}
	}

	/** Reference sorting builds a nested plan during execution, so only the real execution may count it. */
	@ParameterizedTest
	@EnumSource(value = DebugMode.class, names = {"VERIFY_POSSIBLE_CACHING_TREES", "VERIFY_ALTERNATIVE_INDEX_RESULTS"})
	@DisplayName("Records runtime reference sorting activity and capabilities once with either verification mode")
	void shouldRecordRuntimeReferenceSortingOnce(@Nonnull DebugMode mode) {
		try (final EvitaSessionContract session = this.evita.createReadOnlySession(CATALOG)) {
			final Query plain = Query.query(collection("Product"), filterBy(entityPrimaryKeyInSet(1)), require(
				entityFetch(referenceContent("categories", orderBy(entityProperty(attributeNatural("code")))))));
			final Map<String, Long> beforePlainActivity = activity();
			final Map<String, Long> beforePlainCapabilities = capabilities();
			session.query(plain, SealedEntity.class);
			final Map<String, Long> expectedActivity = delta(activity(), beforePlainActivity);
			final Map<String, Long> expectedCapabilities = delta(capabilities(), beforePlainCapabilities);
			assertEquals(1L, expectedActivity.get("Category:GLOBAL:LIVE:null"));
			final Map<String, Long> beforeVerifiedActivity = activity();
			final Map<String, Long> beforeVerifiedCapabilities = capabilities();
			session.query(Query.query(plain.getCollection(), plain.getFilterBy(), require(
				entityFetch(referenceContent("categories", orderBy(entityProperty(attributeNatural("code"))))),
				debug(mode))), SealedEntity.class);
			assertAll(
				() -> assertEquals(expectedActivity.get("Category:GLOBAL:LIVE:null"),
					delta(activity(), beforeVerifiedActivity).get("Category:GLOBAL:LIVE:null")),
				() -> assertEquals(expectedCapabilities, delta(capabilities(), beforeVerifiedCapabilities)));
		}
	}

	/** Reads every capability holder on both fixture collections. */
	@Nonnull
	private Map<String, Long> capabilities() {
		final Map<String, Long> counts = new TreeMap<>();
		for (final String type : List.of("Product", "Category")) {
			for (final io.evitadb.index.usage.SchemaCapabilityUsageRegistry.UsageEntry entry :
				collectionFor(type).getUsageRegistry().listUsages()) {
				counts.put(type + ":" + entry.key(), entry.usage().getRequestedCount());
			}
		}
		return counts;
	}

	/** Reads every physical index, including reduced indexes the planner considered but did not choose. */
	@Nonnull
	private Map<String, Long> activity() {
		final Map<String, Long> counts = new TreeMap<>();
		for (final String type : List.of("Product", "Category")) {
			final IndexBrowseResult indexes = collectionFor(type).browseIndexes(new IndexBrowseCriteria(
				1, IndexBrowseCriteria.MAX_PAGE_SIZE, IndexBrowseOrdering.MAP_ORDER, OrderDirection.ASC,
				Set.of(), Set.of(), Set.of()));
			assertEquals(indexes.totalRecordCount(), indexes.indexes().length, "The fixture must fit on one page");
			for (final BrowsedIndex index : indexes.indexes()) {
				assertTrue(index.measured());
				final String key = type + ":" + index.indexType() + ":" + index.scope() + ":" + index.discriminator();
				assertNull(counts.put(key, index.queryCount()), "Every fixture index must have its own counter");
			}
		}
		return counts;
	}

	/** Resolves the pinned fixture collection. */
	@Nonnull
	private EntityCollection collectionFor(@Nonnull String type) {
		return ((Catalog) this.evita.getCatalogInstanceOrThrowException(CATALOG))
			.getCollectionForEntityInternal(type).orElseThrow();
	}

	/** Computes counter increments without retaining inactive holders. */
	@Nonnull
	private static Map<String, Long> delta(@Nonnull Map<String, Long> after, @Nonnull Map<String, Long> before) {
		final Map<String, Long> result = new TreeMap<>();
		after.forEach((key, value) -> {
			final long increment = value - before.getOrDefault(key, 0L);
			if (increment != 0) result.put(key, increment);
		});
		return result;
	}

	/** Serializes this fixture's fetched values, availability, parent chains and referenced bodies recursively. */
	private static void appendFetchedContent(
		@Nonnull StringBuilder output,
		@javax.annotation.Nullable io.evitadb.api.requestResponse.data.EntityClassifier classifier
	) {
		if (classifier == null) {
			output.append("null\n");
			return;
		}
		output.append(classifier.getType()).append(':').append(classifier.getPrimaryKey()).append('\n');
		if (classifier instanceof io.evitadb.api.requestResponse.data.EntityContract entity) {
			output.append(entity.version()).append(':').append(entity.dropped()).append(':').append(entity.getScope())
				.append(':').append(entity.getLocales()).append(':').append(entity.getAllLocales()).append(':')
				.append(entity.parentAvailable()).append(':').append(entity.attributesAvailable()).append(':')
				.append(entity.associatedDataAvailable()).append(':').append(entity.pricesAvailable()).append(':')
				.append(entity.referencesAvailable()).append(':').append(entity.describe()).append('\n');
			if (entity.parentAvailable()) appendFetchedContent(output, entity.getParentEntity().orElse(null));
			if (entity.referencesAvailable()) {
				for (final io.evitadb.api.requestResponse.data.ReferenceContract reference : entity.getReferences()) {
					output.append(reference.getReferenceKey()).append(':').append(reference.dropped()).append(':')
						.append(reference.attributesAvailable()).append(':').append(reference.getGroup()).append('\n');
					appendFetchedContent(output, reference.getReferencedEntity().orElse(null));
					appendFetchedContent(output, reference.getGroupEntity().orElse(null));
				}
			}
		} else if (classifier instanceof io.evitadb.api.requestResponse.data.EntityClassifierWithParent parent) {
			appendFetchedContent(output, parent.getParentEntity().orElse(null));
		}
	}

	/** Collects deterministic root metrics and verifies that descendants carry no metrics or formula plans. */
	@Nonnull
	private static Map<StepMetric, Long> metrics(@Nonnull QueryTelemetry telemetry) {
		assertNull(telemetry.getPlan(), "Timing telemetry must not carry a formula plan");
		final Map<StepMetric, Long> values = new EnumMap<>(StepMetric.class);
		for (final StepMetric metric : StepMetric.values()) {
			telemetry.getMetric(metric).ifPresent(value -> values.put(metric, value));
		}
		for (final QueryTelemetry step : telemetry.getSteps()) assertTrue(metrics(step).isEmpty());
		return values;
	}

	/** Serializes the ordered telemetry tree without nondeterministic durations. */
	@Nonnull
	private static String tree(@Nonnull QueryTelemetry telemetry) {
		final StringBuilder result = new StringBuilder(256);
		result.append(telemetry.getOperation()).append(java.util.Arrays.toString(telemetry.getArguments())).append('[');
		for (final QueryTelemetry step : telemetry.getSteps()) result.append(tree(step));
		return result.append(']').toString();
	}
}
