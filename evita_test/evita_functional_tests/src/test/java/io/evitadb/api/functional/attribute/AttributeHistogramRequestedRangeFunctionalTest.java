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

package io.evitadb.api.functional.attribute;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.HistogramContract.Bucket;
import io.evitadb.core.Evita;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the `requested` flags of an attribute histogram whose user filter range selects the same attribute values as
 * a different range elsewhere in the filter. A bucket is requested when the range of the user filter holds its
 * threshold, so two ranges selecting the same values still request different buckets once a bucket threshold falls
 * between their upper bounds.
 *
 * ## The fixture
 *
 * | product | width | code   |
 * |---------|-------|--------|
 * | 1       | 10    | narrow |
 * | 2       | 20    | medium |
 * | 3       | 40    | wide   |
 * | 4       | 50    | huge   |
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Requested buckets of an attribute histogram")
@ExtendWith(EvitaParameterResolver.class)
@Tag(ENGINE)
@Tag(ATTRIBUTE)
@Tag(HISTOGRAM)
public class AttributeHistogramRequestedRangeFunctionalTest {
	private static final String ATTRIBUTE_HISTOGRAM_REQUESTED_RANGE = "attributeHistogramRequestedRange";
	private static final String ENTITY_PRODUCT = "histogramRangeProduct";
	private static final String ATTRIBUTE_WIDTH = "width";
	private static final String ATTRIBUTE_CODE = "code";
	private static final int[] PRODUCT_WIDTHS = {10, 20, 40, 50};
	private static final String[] PRODUCT_CODES = {"narrow", "medium", "wide", "huge"};

	/**
	 * Builds the fixture described on the class.
	 *
	 * @param evita the engine instance provided by the test extension
	 */
	@DataSet(value = ATTRIBUTE_HISTOGRAM_REQUESTED_RANGE, destroyAfterClass = true)
	void setUpAttributeHistogramRequestedRange(@Nonnull Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_WIDTH, Integer.class, whichIs -> whichIs.filterable())
					.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.filterable())
					.updateVia(session);
				for (int i = 0; i < PRODUCT_WIDTHS.length; i++) {
					session.upsertEntity(
						session.createNewEntity(ENTITY_PRODUCT, i + 1)
							.setAttribute(ATTRIBUTE_WIDTH, PRODUCT_WIDTHS[i])
							.setAttribute(ATTRIBUTE_CODE, PRODUCT_CODES[i])
					);
				}
			}
		);
	}

	/**
	 * The user filter selects widths 10 to 20, and a disjunction outside it selects widths 10 to 35 or the wide
	 * product. Both ranges select the widths 10 and 20 only, so both range formulas wrap the same slice of the
	 * attribute index and share their hash and transactional ids - the bounds and the requested-bucket predicate are
	 * folded into neither. The formula deduplicator, which runs on every query, then may put the range formula of the
	 * disjunction into the user filter, and the histogram reads the requested-bucket predicate of the wrong range.
	 * The expected histogram comes from the same query with the disjunction selecting widths 10 and 20 by
	 * `attributeInSet`, whose formula cannot share an identity with a range formula. Both orders of the two
	 * constraints are queried, because the deduplicator keeps whichever formula it visits first.
	 */
	@DisplayName("Should request buckets of the user filter range when another range selects the same values")
	@UseDataSet(ATTRIBUTE_HISTOGRAM_REQUESTED_RANGE)
	@Test
	void shouldRequestBucketsOfUserFilterRangeWhenAnotherRangeSelectsSameValues(@Nonnull Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final FilterConstraint userRange = userFilter(attributeBetween(ATTRIBUTE_WIDTH, 10, 20));
				final List<Bucket> expectedBuckets = queryWidthHistogram(
					session,
					or(attributeInSet(ATTRIBUTE_WIDTH, 10, 20), attributeEquals(ATTRIBUTE_CODE, "wide")),
					userRange
				);
				// a bucket threshold between the upper bounds of the two ranges is what tells their predicates apart
				assertTrue(
					expectedBuckets.stream().anyMatch(
						it -> it.threshold().compareTo(BigDecimal.valueOf(20)) > 0 &&
							it.threshold().compareTo(BigDecimal.valueOf(35)) <= 0 && !it.requested()
					),
					"the histogram must hold an unrequested bucket between 20 and 35: " + expectedBuckets
				);

				final FilterConstraint widerRange = or(
					attributeBetween(ATTRIBUTE_WIDTH, 10, 35), attributeEquals(ATTRIBUTE_CODE, "wide")
				);
				final List<Bucket> widerRangeFirst = queryWidthHistogram(session, widerRange, userRange);
				final List<Bucket> userFilterFirst = queryWidthHistogram(session, userRange, widerRange);
				assertAll(
					() -> assertEquals(expectedBuckets, widerRangeFirst, "the wider range precedes the user filter"),
					() -> assertEquals(expectedBuckets, userFilterFirst, "the user filter precedes the wider range")
				);
				return null;
			}
		);
	}

	/**
	 * Returns the buckets of the width histogram of the products matching the passed filter constraints.
	 *
	 * @param session     the session to query in
	 * @param constraints the constraints of the filter
	 * @return the buckets of the width histogram
	 */
	@Nonnull
	private static List<Bucket> queryWidthHistogram(
		@Nonnull EvitaSessionContract session,
		@Nonnull FilterConstraint... constraints
	) {
		final AttributeHistogram attributeHistogram = session.query(
				query(
					collection(ENTITY_PRODUCT),
					filterBy(constraints),
					require(
						page(1, PRODUCT_WIDTHS.length),
						attributeHistogram(3, ATTRIBUTE_WIDTH)
					)
				),
				EntityReference.class
			)
			.getExtraResult(AttributeHistogram.class);
		assertNotNull(attributeHistogram, "the attribute histogram must be computed");
		final HistogramContract histogram = attributeHistogram.getHistogram(ATTRIBUTE_WIDTH);
		assertNotNull(histogram, "the width histogram must be computed");
		return Arrays.asList(histogram.getBuckets());
	}

}
