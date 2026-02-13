/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api.requestResponse.extraResult;

import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetGroupStatistics;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetStatistics;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.RequestImpact;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.dataType.Scope;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link FacetSummary} contract.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("FacetSummary")
class FacetSummaryTest implements EvitaTestSupport {

	@Nested
	@DisplayName("Map constructor")
	class MapConstructor {

		@Test
		@DisplayName("should create from map of reference statistics")
		void shouldCreateFromMapOfReferenceStatistics() {
			final FacetSummary facetSummary = createFacetSummaryViaMapConstructor();
			assertNotNull(facetSummary);
		}
	}

	@Nested
	@DisplayName("Collection constructor")
	class CollectionConstructor {

		@Test
		@DisplayName("should create from collection of facet group statistics")
		void shouldCreateFromCollectionOfFacetGroupStatistics() {
			final FacetSummary facetSummary = createFacetSummary();
			assertNotNull(facetSummary);
		}
	}

	@Nested
	@DisplayName("Getters")
	class Getters {

		@Test
		@DisplayName("should return facet group statistics by reference name")
		void shouldReturnFacetGroupStatisticsByReferenceName() {
			final FacetSummary facetSummary = createFacetSummary();
			// non-grouped statistics would be null since all groups have entities
			final FacetGroupStatistics stats = facetSummary.getFacetGroupStatistics("parameter");
			assertNull(stats);
		}

		@Test
		@DisplayName("should return facet group statistics by reference name and group id")
		void shouldReturnFacetGroupStatisticsByReferenceNameAndGroupId() {
			final FacetSummary facetSummary = createFacetSummary();
			final FacetGroupStatistics stats = facetSummary.getFacetGroupStatistics("parameter", 1);
			assertNotNull(stats);
			assertEquals(14, stats.getCount());
		}

		@Test
		@DisplayName("should return null for unknown reference name")
		void shouldReturnNullForUnknownReferenceName() {
			final FacetSummary facetSummary = createFacetSummary();
			assertNull(facetSummary.getFacetGroupStatistics("nonExistent"));
		}

		@Test
		@DisplayName("should return null for unknown group id")
		void shouldReturnNullForUnknownGroupId() {
			final FacetSummary facetSummary = createFacetSummary();
			assertNull(facetSummary.getFacetGroupStatistics("parameter", 999));
		}

		@Test
		@DisplayName("should return all reference statistics as collection")
		void shouldReturnAllReferenceStatisticsAsCollection() {
			final FacetSummary facetSummary = createFacetSummary();
			final Collection<FacetGroupStatistics> allStats = facetSummary.getReferenceStatistics();
			assertEquals(2, allStats.size());
		}
	}

	@Nested
	@DisplayName("equals and hashCode")
	class EqualsAndHashCode {

		@Test
		@DisplayName("should be equal when same data")
		void shouldBeEqualWhenSameData() {
			final FacetSummary one = createFacetSummary();
			final FacetSummary two = createFacetSummary();
			assertNotSame(one, two);
			assertEquals(one, two);
			assertEquals(one.hashCode(), two.hashCode());
		}

		@Test
		@DisplayName("should be equal to itself")
		void shouldBeEqualToItself() {
			final FacetSummary facetSummary = createFacetSummary();
			assertEquals(facetSummary, facetSummary);
		}

		@Test
		@DisplayName("should not be equal to null")
		void shouldNotBeEqualToNull() {
			final FacetSummary facetSummary = createFacetSummary();
			assertNotEquals(null, facetSummary);
		}

		@Test
		@DisplayName("should not be equal when different number of reference names")
		void shouldNotBeEqualWhenDifferentNumberOfReferenceNames() {
			final FacetSummary singleRef = createFacetSummaryViaMapConstructor();
			final FacetSummary twoRefs = createFacetSummaryWithTwoReferenceNames();
			// singleRef has 1 reference name, twoRefs has 2 different reference names
			assertNotEquals(singleRef, twoRefs);
			assertNotEquals(twoRefs, singleRef);
		}
	}

	@Nested
	@DisplayName("toString")
	class ToString {

		@Test
		@DisplayName("should produce summary string")
		void shouldProduceSummaryString() {
			final FacetSummary facetSummary = createFacetSummary();
			final String result = facetSummary.toString();
			assertNotNull(result);
			assertTrue(result.contains("Facet summary"));
		}
	}

	@Nested
	@DisplayName("RequestImpact")
	class RequestImpactTests {

		@Test
		@DisplayName("should store difference and match count")
		void shouldStoreDifferenceAndMatchCount() {
			final RequestImpact impact = new RequestImpact(5, 10, true);
			assertEquals(5, impact.difference());
			assertEquals(10, impact.matchCount());
			assertTrue(impact.hasSense());
		}

		@Test
		@DisplayName("should format positive difference with plus sign")
		void shouldFormatPositiveDifferenceWithPlusSign() {
			final RequestImpact impact = new RequestImpact(5, 10, true);
			assertEquals("+5", impact.toString());
		}

		@Test
		@DisplayName("should format negative difference with minus sign")
		void shouldFormatNegativeDifferenceWithMinusSign() {
			final RequestImpact impact = new RequestImpact(-3, 7, true);
			assertEquals("-3", impact.toString());
		}

		@Test
		@DisplayName("should format zero difference")
		void shouldFormatZeroDifference() {
			final RequestImpact impact = new RequestImpact(0, 5, true);
			assertEquals("0", impact.toString());
		}

		@Test
		@DisplayName("should be equal when same data")
		void shouldBeEqualWhenSameData() {
			final RequestImpact one = new RequestImpact(5, 10, true);
			final RequestImpact two = new RequestImpact(5, 10, true);
			assertEquals(one, two);
			assertEquals(one.hashCode(), two.hashCode());
		}

		@Test
		@DisplayName("should not be equal when different data")
		void shouldNotBeEqualWhenDifferentData() {
			final RequestImpact one = new RequestImpact(5, 10, true);
			final RequestImpact two = new RequestImpact(3, 10, true);
			assertNotEquals(one, two);
		}
	}

	@Nested
	@DisplayName("FacetStatistics")
	class FacetStatisticsTests {

		@Test
		@DisplayName("should store all fields correctly")
		void shouldStoreAllFieldsCorrectly() {
			final FacetStatistics stats = new FacetStatistics(
				new EntityReference("parameter", 1),
				true, 5,
				new RequestImpact(3, 8, true)
			);
			assertEquals(new EntityReference("parameter", 1), stats.getFacetEntity());
			assertTrue(stats.isRequested());
			assertEquals(5, stats.getCount());
			assertNotNull(stats.getImpact());
		}

		@Test
		@DisplayName("should be equal when same data")
		void shouldBeEqualWhenSameData() {
			final FacetStatistics one = new FacetStatistics(
				new EntityReference("parameter", 1),
				true, 5, null
			);
			final FacetStatistics two = new FacetStatistics(
				new EntityReference("parameter", 1),
				true, 5, null
			);
			assertEquals(one, two);
			assertEquals(one.hashCode(), two.hashCode());
		}

		@Test
		@DisplayName("should compare by primary key")
		void shouldCompareByPrimaryKey() {
			final FacetStatistics lower = new FacetStatistics(
				new EntityReference("parameter", 1),
				false, 5, null
			);
			final FacetStatistics higher = new FacetStatistics(
				new EntityReference("parameter", 10),
				false, 3, null
			);
			assertTrue(lower.compareTo(higher) < 0);
			assertTrue(higher.compareTo(lower) > 0);
		}

		@Test
		@DisplayName("should produce readable toString")
		void shouldProduceReadableToString() {
			final FacetStatistics stats = new FacetStatistics(
				new EntityReference("parameter", 1),
				true, 5, null
			);
			final String result = stats.toString();
			assertTrue(result.contains("FacetStatistics"));
			assertTrue(result.contains("requested=true"));
			assertTrue(result.contains("count=5"));
		}
	}

	@Nested
	@DisplayName("FacetGroupStatistics")
	class FacetGroupStatisticsTests {

		@Test
		@DisplayName("should create from deserialization constructor")
		void shouldCreateFromDeserializationConstructor() {
			final FacetGroupStatistics groupStats = new FacetGroupStatistics(
				"parameter",
				new EntityReference("parameterGroup", 1),
				14,
				Map.of(
					1, new FacetStatistics(new EntityReference("parameter", 1), true, 5, null)
				)
			);
			assertEquals("parameter", groupStats.getReferenceName());
			assertNotNull(groupStats.getGroupEntity());
			assertEquals(14, groupStats.getCount());
			assertEquals(1, groupStats.getFacetStatistics().size());
		}

		@Test
		@DisplayName("should return facet statistics by id")
		void shouldReturnFacetStatisticsById() {
			final FacetGroupStatistics groupStats = createFacetGroupStatistics();
			assertNotNull(groupStats.getFacetStatistics(1));
			assertNull(groupStats.getFacetStatistics(999));
		}

		@Test
		@DisplayName("should return unmodifiable facet statistics collection")
		void shouldReturnUnmodifiableFacetStatisticsCollection() {
			final FacetGroupStatistics groupStats = createFacetGroupStatistics();
			final Collection<FacetStatistics> stats = groupStats.getFacetStatistics();
			assertThrows(UnsupportedOperationException.class, () ->
				((java.util.Collection<FacetStatistics>) stats).add(
					new FacetStatistics(new EntityReference("parameter", 99), false, 1, null)
				)
			);
		}

		@Test
		@DisplayName("should be equal when same data")
		void shouldBeEqualWhenSameData() {
			final FacetGroupStatistics one = createFacetGroupStatistics();
			final FacetGroupStatistics two = createFacetGroupStatistics();
			assertEquals(one, two);
			assertEquals(one.hashCode(), two.hashCode());
		}

		@Test
		@DisplayName("should not be equal when different count")
		void shouldNotBeEqualWhenDifferentCount() {
			final FacetGroupStatistics one = createFacetGroupStatistics();
			final FacetGroupStatistics two = new FacetGroupStatistics(
				"parameter",
				new EntityReference("parameterGroup", 1),
				999,
				Map.of(
					1, new FacetStatistics(new EntityReference("parameter", 1), true, 5, null),
					2, new FacetStatistics(new EntityReference("parameter", 2), false, 6, new RequestImpact(6, 11, true)),
					3, new FacetStatistics(new EntityReference("parameter", 3), false, 3, new RequestImpact(3, 8, true))
				)
			);
			assertNotEquals(one, two);
		}
	}

	@Nonnull
	private static ReferenceSchema createParameterSchema() {
		return ReferenceSchema._internalBuild(
			"parameter",
			"parameter", false, Cardinality.ZERO_OR_MORE,
			"parameterGroup", false,
			new ScopedReferenceIndexType[]{new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING)},
			new Scope[]{Scope.LIVE}
		);
	}

	@Nonnull
	private static FacetGroupStatistics createFacetGroupStatistics() {
		return new FacetGroupStatistics(
			"parameter",
			new EntityReference("parameterGroup", 1),
			14,
			Map.of(
				1, new FacetStatistics(new EntityReference("parameter", 1), true, 5, null),
				2, new FacetStatistics(new EntityReference("parameter", 2), false, 6, new RequestImpact(6, 11, true)),
				3, new FacetStatistics(new EntityReference("parameter", 3), false, 3, new RequestImpact(3, 8, true))
			)
		);
	}

	@Nonnull
	private static FacetSummary createFacetSummary() {
		final ReferenceSchema parameter = createParameterSchema();
		return new FacetSummary(
			Arrays.asList(
				new FacetGroupStatistics(
					parameter,
					new EntityReference("parameterGroup", 1),
					14,
					Arrays.asList(
						new FacetStatistics(new EntityReference("parameter", 1), true, 5, null),
						new FacetStatistics(new EntityReference("parameter", 2), false, 6, new RequestImpact(6, 11, true)),
						new FacetStatistics(new EntityReference("parameter", 3), false, 3, new RequestImpact(3, 8, true))
					)
				),
				new FacetGroupStatistics(
					parameter,
					new EntityReference("parameterGroup", 2),
					14,
					Arrays.asList(
						new FacetStatistics(new EntityReference("parameter", 4), true, 5, null),
						new FacetStatistics(new EntityReference("parameter", 5), false, 6, new RequestImpact(6, 11, true)),
						new FacetStatistics(new EntityReference("parameter", 6), false, 3, new RequestImpact(3, 8, true))
					)
				)
			)
		);
	}

	@Nonnull
	private static FacetSummary createFacetSummaryWithTwoReferenceNames() {
		return new FacetSummary(
			Map.of(
				"parameter",
				Arrays.asList(
					new FacetGroupStatistics(
						"parameter",
						new EntityReference("parameterGroup", 1),
						14,
						Map.of(
							1, new FacetStatistics(new EntityReference("parameter", 1), true, 5, null),
							2, new FacetStatistics(new EntityReference("parameter", 2), false, 6, new RequestImpact(6, 11, true))
						)
					)
				),
				"brand",
				Arrays.asList(
					new FacetGroupStatistics(
						"brand",
						new EntityReference("brandGroup", 1),
						7,
						Map.of(
							10, new FacetStatistics(new EntityReference("brand", 10), false, 7, null)
						)
					)
				)
			)
		);
	}

	@Nonnull
	private static FacetSummary createFacetSummaryViaMapConstructor() {
		return new FacetSummary(
			Map.of(
				"parameter",
				Arrays.asList(
					new FacetGroupStatistics(
						"parameter",
						new EntityReference("parameterGroup", 1),
						14,
						Map.of(
							1, new FacetStatistics(new EntityReference("parameter", 1), true, 5, null),
							2, new FacetStatistics(new EntityReference("parameter", 2), false, 6, new RequestImpact(6, 11, true))
						)
					)
				)
			)
		);
	}
}
