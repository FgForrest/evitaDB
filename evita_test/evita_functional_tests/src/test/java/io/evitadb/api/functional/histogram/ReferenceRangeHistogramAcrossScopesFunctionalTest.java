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

package io.evitadb.api.functional.histogram;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary.ReferenceGroupStatistics;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeHistogram;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.histogramStatistics;
import static io.evitadb.api.query.QueryConstraints.referenceSummaryOfReferenceWithHistograms;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.scope;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Exercises range histograms through the public query API. The reference histogram of two overlapping ranges
 * must agree whether the records are stored together in the live scope or split between live and archived scopes.
 * The entity attribute histogram requires a numeric attribute.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Range histograms across scopes")
@Tag(CONTRACT)
@Tag(HISTOGRAM)
class ReferenceRangeHistogramAcrossScopesFunctionalTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_BRAND = "brand";
	private static final String REFERENCE_BRAND = "brand";
	private static final String ATTRIBUTE_VALIDITY = "validity";
	private static final String HISTOGRAM_VALIDITY = "validityHistogram";
	private static final Scope[] BOTH_SCOPES = {Scope.LIVE, Scope.ARCHIVED};
	private static final int BUCKET_COUNT = 5;
	private static final IntegerNumberRange WIDE_RANGE = IntegerNumberRange.between(1, 10);
	private static final IntegerNumberRange NARROW_RANGE = IntegerNumberRange.between(4, 6);

	/**
	 * Compares the reference histogram of one record per scope with that of both records in the live scope.
	 *
	 * @param behavior the histogram behavior to compare
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(HistogramBehavior.class)
	@Tag(REFERENCE)
	@DisplayName("should return the same reference histogram when records are split between scopes")
	void shouldReturnSameReferenceHistogramWhenRecordsAreSplitBetweenScopes(@Nonnull HistogramBehavior behavior) {
		final HistogramContract expected = inCatalog(
			"referenceRangeHistogramLive",
			session -> setUpReferences(session, Scope.LIVE),
			session -> queryReferenceHistogram(session, behavior, Scope.LIVE)
		);
		final HistogramContract actual = inCatalog(
			"referenceRangeHistogramSplit",
			session -> setUpReferences(session, Scope.ARCHIVED),
			session -> queryReferenceHistogram(session, behavior, BOTH_SCOPES)
		);
		final List<BucketCount> expectedBuckets = bucketCounts(expected);
		assertFalse(expectedBuckets.isEmpty(), "The live-only histogram must have buckets");
		assertAll(
			() -> assertEquals(expected.getMin().stripTrailingZeros(), actual.getMin().stripTrailingZeros(), "minimum"),
			() -> assertEquals(expected.getMax().stripTrailingZeros(), actual.getMax().stripTrailingZeros(), "maximum"),
			() -> assertEquals(expected.getOverallCount(), actual.getOverallCount(), "overall count"),
			() -> assertEquals(expectedBuckets, bucketCounts(actual), "bucket thresholds and occurrences")
		);
	}

	/**
	 * Verifies that a filterable range entity attribute is refused as a source for an entity attribute histogram.
	 *
	 * @param behavior the histogram behavior requested by the query
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(HistogramBehavior.class)
	@Tag(ATTRIBUTE)
	@DisplayName("should refuse an entity attribute histogram when the source is a range")
	void shouldRefuseEntityAttributeHistogramWhenSourceIsRange(@Nonnull HistogramBehavior behavior) {
		final EvitaInvalidUsageException exception = inCatalog(
			"entityRangeHistogram",
			session -> {
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(
						ATTRIBUTE_VALIDITY, IntegerNumberRange.class,
						whichIs -> whichIs.filterableInScope(BOTH_SCOPES).nullable()
					)
					.updateVia(session);
				session.createNewEntity(ENTITY_PRODUCT, 1)
					.setAttribute(ATTRIBUTE_VALIDITY, WIDE_RANGE)
					.upsertVia(session);
				session.createNewEntity(ENTITY_PRODUCT, 2)
					.setScope(Scope.ARCHIVED)
					.setAttribute(ATTRIBUTE_VALIDITY, NARROW_RANGE)
					.upsertVia(session);
			},
			session -> assertThrows(
				EvitaInvalidUsageException.class,
				() -> session.query(
					query(
						collection(ENTITY_PRODUCT),
						filterBy(scope(BOTH_SCOPES)),
						require(attributeHistogram(BUCKET_COUNT, behavior, ATTRIBUTE_VALIDITY))
					),
					EntityReference.class
				)
			)
		);
		assertEquals(
			"Attribute `" + ATTRIBUTE_VALIDITY + "` must be a number in order to compute histogram!",
			exception.getMessage()
		);
	}

	/**
	 * Defines a brand reference histogram over the validity range in both scopes and seeds two products,
	 * each referencing its own brand. The wider range is always in the live scope.
	 *
	 * @param session the session used to define the schema and seed the records
	 * @param narrowRangeScope the scope of the product with the narrower range
	 */
	private static void setUpReferences(@Nonnull EvitaSessionContract session, @Nonnull Scope narrowRangeScope) {
		session.defineEntitySchema(ENTITY_BRAND).withoutGeneratedPrimaryKey().updateVia(session);
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withReferenceToEntity(
				REFERENCE_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_MORE,
				whichIs -> {
					whichIs.indexedForFilteringInScope(BOTH_SCOPES)
						.facetedInScope(BOTH_SCOPES)
						.withAttribute(
							ATTRIBUTE_VALIDITY, IntegerNumberRange.class,
							thatIs -> thatIs.filterableInScope(BOTH_SCOPES).nullable()
						);
					for (final Scope scope : BOTH_SCOPES) {
						whichIs.bucketedInScope(
							scope, HISTOGRAM_VALIDITY,
							ExpressionFactory.parse("$reference.attributes['" + ATTRIBUTE_VALIDITY + "']")
						);
					}
				}
			)
			.updateVia(session);
		session.createNewEntity(ENTITY_BRAND, 10).upsertVia(session);
		session.createNewEntity(ENTITY_BRAND, 11).upsertVia(session);
		session.createNewEntity(ENTITY_PRODUCT, 1)
			.setReference(REFERENCE_BRAND, 10, whichIs -> whichIs.setAttribute(ATTRIBUTE_VALIDITY, WIDE_RANGE))
			.upsertVia(session);
		session.createNewEntity(ENTITY_PRODUCT, 2)
			.setScope(narrowRangeScope)
			.setReference(REFERENCE_BRAND, 11, whichIs -> whichIs.setAttribute(ATTRIBUTE_VALIDITY, NARROW_RANGE))
			.upsertVia(session);
	}

	/**
	 * Requests the brand reference histogram in the given scopes and asserts that it is present.
	 *
	 * @param session the session used to query the catalog
	 * @param behavior the requested histogram behavior
	 * @param scopes the scopes selected by the query
	 * @return the validity histogram for the brand reference
	 */
	@Nonnull
	private static HistogramContract queryReferenceHistogram(
		@Nonnull EvitaSessionContract session,
		@Nonnull HistogramBehavior behavior,
		@Nonnull Scope... scopes
	) {
		final EvitaResponse<EntityReference> response = session.query(
			query(
				collection(ENTITY_PRODUCT),
				filterBy(scope(scopes)),
				require(
					referenceSummaryOfReferenceWithHistograms(
						REFERENCE_BRAND, FacetStatisticsDepth.COUNTS, null, null,
						histogramStatistics(BUCKET_COUNT, behavior, HISTOGRAM_VALIDITY)
					)
				)
			),
			EntityReference.class
		);
		final ReferenceSummary summary = response.getExtraResult(ReferenceSummary.class);
		assertNotNull(summary, "Reference summary");
		final ReferenceGroupStatistics statistics = summary.getReferenceGroupStatistics(REFERENCE_BRAND);
		assertNotNull(statistics, "Brand reference statistics");
		final HistogramContract histogram = statistics.getHistogramStatistics(HISTOGRAM_VALIDITY);
		assertNotNull(histogram, "Validity histogram");
		return histogram;
	}

	/**
	 * Runs a query against a fresh catalog seeded in warm-up mode.
	 *
	 * @param folder label for the temporary test paths
	 * @param setUp defines the schema and seeds the records
	 * @param read queries the seeded catalog
	 * @param <T> the type returned by the query callback
	 * @return the result of the query callback
	 */
	@Nonnull
	private <T> T inCatalog(
		@Nonnull String folder,
		@Nonnull Consumer<EvitaSessionContract> setUp,
		@Nonnull Function<EvitaSessionContract, T> read
	) {
		final TestPaths paths = createTestPaths(folder);
		try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
			evita.defineCatalog(TEST_CATALOG);
			evita.updateCatalog(TEST_CATALOG, setUp);
			return evita.queryCatalog(TEST_CATALOG, read);
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/**
	 * Extracts the ordered threshold and occurrence count of every bucket for comparison.
	 *
	 * @param histogram the source histogram
	 * @return the buckets with thresholds normalized for numerical equality
	 */
	@Nonnull
	private static List<BucketCount> bucketCounts(@Nonnull HistogramContract histogram) {
		return Arrays.stream(histogram.getBuckets())
			.map(bucket -> new BucketCount(bucket.threshold().stripTrailingZeros(), bucket.occurrences()))
			.toList();
	}

	/**
	 * A bucket's threshold and occurrence count.
	 *
	 * @param threshold the normalized lower bound of the bucket
	 * @param occurrences the number of occurrences in the bucket
	 */
	private record BucketCount(@Nonnull BigDecimal threshold, int occurrences) {
	}
}
