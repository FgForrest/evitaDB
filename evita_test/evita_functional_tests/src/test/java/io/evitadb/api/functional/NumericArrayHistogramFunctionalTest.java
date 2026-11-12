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

package io.evitadb.api.functional;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.AttributeHistogram;
import io.evitadb.api.requestResponse.extraResult.HistogramContract;
import io.evitadb.api.requestResponse.extraResult.ReferenceSummary;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies public entity and reference histograms of numeric arrays against their element counts and scalar controls.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Numeric array histogram queries")
@Tag(CONTRACT)
@Tag(HISTOGRAM)
class NumericArrayHistogramFunctionalTest implements EvitaTestSupport {

	/**
	 * The type of the histogram attribute, with the values stored in it and the extremes they must produce. Every
	 * kind stores the same four elements - one at the minimum, two in the middle, one at the maximum.
	 */
	enum AttributeKind {
		/**
		 * Two products with an integer array each.
		 */
		INTEGER_ARRAY(
			Integer[].class, 0,
			new Serializable[]{new Integer[]{10, 20}, new Integer[]{20, 30}},
			"10", "30"
		),
		/**
		 * Two products with a decimal array each, indexed with two decimal places.
		 */
		DECIMAL_ARRAY(
			BigDecimal[].class, 2,
			new Serializable[]{
				new BigDecimal[]{new BigDecimal("1.25"), new BigDecimal("2.50")},
				new BigDecimal[]{new BigDecimal("2.50"), new BigDecimal("3.75")}
			},
			"1.25", "3.75"
		),
		/**
		 * The control: four products with one integer each.
		 */
		SCALAR(
			Integer.class, 0,
			new Serializable[]{10, 20, 20, 30},
			"10", "30"
		);

		/**
		 * The type of the attribute on the entity and on its reference.
		 */
		private final Class<? extends Serializable> attributeType;
		/**
		 * The decimal places the attribute is indexed with.
		 */
		private final int decimalPlaces;
		/**
		 * The attribute value of each product, in the order of their primary keys.
		 */
		private final Serializable[] values;
		/**
		 * The smallest element of all values.
		 */
		private final BigDecimal expectedMin;
		/**
		 * The largest element of all values.
		 */
		private final BigDecimal expectedMax;

		/**
		 * Creates the kind.
		 *
		 * @param attributeType the type of the attribute
		 * @param decimalPlaces the decimal places the attribute is indexed with
		 * @param values        the attribute value of each product
		 * @param expectedMin   the smallest element of all values
		 * @param expectedMax   the largest element of all values
		 */
		AttributeKind(
			@Nonnull Class<? extends Serializable> attributeType,
			int decimalPlaces,
			@Nonnull Serializable[] values,
			@Nonnull String expectedMin,
			@Nonnull String expectedMax
		) {
			this.attributeType = attributeType;
			this.decimalPlaces = decimalPlaces;
			this.values = values;
			this.expectedMin = new BigDecimal(expectedMin);
			this.expectedMax = new BigDecimal(expectedMax);
		}
	}

	/**
	 * The public query a histogram is read through.
	 */
	enum HistogramPath {
		/**
		 * The attribute histogram of the entity attribute.
		 */
		ENTITY,
		/**
		 * The histogram of the reference summary, over the reference attribute.
		 */
		REFERENCE
	}

	/**
	 * Covers both public histogram paths, decimal precision and a scalar control.
	 */
	@Nonnull
	static Stream<Arguments> scenarios() {
		final List<Arguments> arguments = new ArrayList<>(12);
		for (final CatalogState state : new CatalogState[]{CatalogState.WARMING_UP, CatalogState.ALIVE}) {
			for (final AttributeKind kind : AttributeKind.values()) {
				for (final HistogramPath path : HistogramPath.values()) {
					arguments.add(Arguments.of(state, kind, path));
				}
			}
		}
		return arguments.stream();
	}

	/**
	 * Asserts exact extrema, bucket occurrences and total occurrence counts for known input values.
	 */
	@ParameterizedTest(name = "{0} {1} {2}")
	@MethodSource("scenarios")
	@DisplayName("computes numeric element distributions with the declared indexed precision")
	void shouldComputeHistogramWhenAttributeIsNumericArrayOrScalar(
		@Nonnull CatalogState state, @Nonnull AttributeKind kind, @Nonnull HistogramPath path
	) {
		final TestPaths paths = createTestPaths("numericHistogram_" + state + "_" + kind + "_" + path);
		final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
		try (final Evita evita = new Evita(configuration)) {
			evita.defineCatalog(TEST_CATALOG);
			evita.updateCatalog(TEST_CATALOG, session -> {
				setUpCatalog(session, kind);
				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			});
			final HistogramContract histogram = evita.queryCatalog(TEST_CATALOG, session -> {
				if (path == HistogramPath.REFERENCE) {
					return session.query(query(collection("product"), require(
						referenceSummaryOfReferenceWithHistograms("brand", FacetStatisticsDepth.COUNTS, null, null,
							histogramStatistics(3, HistogramBehavior.STANDARD, "values")))), EntityReference.class)
						.getExtraResult(ReferenceSummary.class).getReferenceGroupStatistics("brand")
						.getHistogramStatistics("values");
				}
				return session.query(query(collection("product"), require(
					attributeHistogram(3, HistogramBehavior.STANDARD, "numbers"))), EntityReference.class)
					.getExtraResult(AttributeHistogram.class).getHistogram("numbers");
			});
			assertNotNull(histogram);
			assertEquals(
				0, histogram.getMin().compareTo(kind.expectedMin),
				() -> "min: expected " + kind.expectedMin + " but was " + histogram.getMin()
			);
			assertEquals(
				0, histogram.getMax().compareTo(kind.expectedMax),
				() -> "max: expected " + kind.expectedMax + " but was " + histogram.getMax()
			);
			final int[] occurrences = new int[histogram.getBuckets().length];
			for (int i = 0; i < occurrences.length; i++) {
				occurrences[i] = histogram.getBuckets()[i].occurrences();
			}
			assertArrayEquals(new int[]{1, 2, 1}, occurrences);
			assertEquals(4, histogram.getOverallCount());
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/**
	 * Stores identical element distributions on the entity and its reference; scalar values span four entities.
	 */
	private static void setUpCatalog(@Nonnull EvitaSessionContract session, @Nonnull AttributeKind kind) {
		session.defineEntitySchema("brand").withoutGeneratedPrimaryKey().updateVia(session);
		session.defineEntitySchema("product").withoutGeneratedPrimaryKey()
			.withAttribute(
				"numbers", kind.attributeType, attr -> attr.filterable().indexDecimalPlaces(kind.decimalPlaces)
			)
			.withReferenceToEntity("brand", "brand", Cardinality.ZERO_OR_MORE, ref -> ref
				.indexedForFiltering().faceted()
				.withAttribute(
					"numbers", kind.attributeType, attr -> attr.filterable().indexDecimalPlaces(kind.decimalPlaces)
				)
				.bucketed("values", ExpressionFactory.parse("$reference.attributes['numbers']")))
			.updateVia(session);
		session.createNewEntity("brand", 1).upsertVia(session);
		for (int i = 0; i < kind.values.length; i++) {
			final Serializable value = kind.values[i];
			session.createNewEntity("product", i + 1).setAttribute("numbers", value)
				.setReference("brand", 1, ref -> ref.setAttribute("numbers", value)).upsertVia(session);
		}
	}
}
