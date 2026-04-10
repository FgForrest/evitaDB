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

package io.evitadb.api.requestResponse;

import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.dataType.DataChunk;
import io.evitadb.dataType.PaginatedList;
import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EvitaResponse} verifying base functionality
 * including extra results management, IO fetch statistics,
 * equality, hashing, and string representation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("EvitaResponse base functionality")
class EvitaResponseTest {

	/**
	 * Creates a simple query targeting the "brand" collection.
	 */
	@Nonnull
	private static Query createBrandQuery() {
		return query(collection("brand"));
	}

	/**
	 * Creates an {@link EvitaEntityResponse} with an empty
	 * paginated list and no extra results.
	 */
	@Nonnull
	private static EvitaEntityResponse<SealedEntity> createEmptyResponse(
		@Nonnull Query sourceQuery
	) {
		return new EvitaEntityResponse<>(
			sourceQuery,
			PaginatedList.emptyList(),
			ArrayUtils.EMPTY_INT_ARRAY
		);
	}

	/**
	 * Creates an {@link EvitaEntityResponse} with an empty
	 * paginated list and the given extra results.
	 */
	@Nonnull
	private static EvitaEntityResponse<SealedEntity> createEmptyResponseWithExtras(
		@Nonnull Query sourceQuery,
		@Nonnull EvitaResponseExtraResult... extraResults
	) {
		return new EvitaEntityResponse<>(
			sourceQuery,
			PaginatedList.emptyList(),
			ArrayUtils.EMPTY_INT_ARRAY,
			extraResults
		);
	}

	/**
	 * Creates an {@link EvitaEntityResponse} with the given
	 * record page and no extra results.
	 */
	@Nonnull
	private static EvitaEntityResponse<MockFetchEntity> createResponseWith(
		@Nonnull Query sourceQuery,
		@Nonnull DataChunk<MockFetchEntity> recordPage
	) {
		return new EvitaEntityResponse<>(
			sourceQuery,
			recordPage,
			ArrayUtils.EMPTY_INT_ARRAY
		);
	}

	@Nested
	@DisplayName("Construction and basic accessors")
	class ConstructionTest {

		/**
		 * Verifies that the source query is returned correctly.
		 */
		@Test
		@DisplayName("returns source query")
		void shouldReturnSourceQuery() {
			final Query brandQuery = createBrandQuery();
			final EvitaEntityResponse<SealedEntity> response =
				createEmptyResponse(brandQuery);

			assertSame(brandQuery, response.getSourceQuery());
		}

		/**
		 * Verifies that the record page is returned correctly.
		 */
		@Test
		@DisplayName("returns record page")
		void shouldReturnRecordPage() {
			final PaginatedList<SealedEntity> emptyPage =
				PaginatedList.emptyList();
			final EvitaEntityResponse<SealedEntity> response =
				new EvitaEntityResponse<>(
					createBrandQuery(),
					emptyPage,
					ArrayUtils.EMPTY_INT_ARRAY
				);

			assertSame(emptyPage, response.getRecordPage());
		}

		/**
		 * Verifies that record data delegates to the chunk.
		 */
		@Test
		@DisplayName("returns record data from page")
		void shouldReturnRecordData() {
			final EvitaEntityResponse<SealedEntity> response =
				createEmptyResponse(createBrandQuery());

			final List<SealedEntity> data =
				response.getRecordData();

			assertNotNull(data);
			assertTrue(data.isEmpty());
		}

		/**
		 * Verifies total record count delegation.
		 */
		@Test
		@DisplayName("returns total record count")
		void shouldReturnTotalRecordCount() {
			final EvitaEntityResponse<SealedEntity> response =
				createEmptyResponse(createBrandQuery());

			assertEquals(0, response.getTotalRecordCount());
		}
	}

	@Nested
	@DisplayName("Extra results management")
	class ExtraResultsTest {

		/**
		 * Verifies empty types when none added.
		 */
		@Test
		@DisplayName("returns empty types when none added")
		void shouldReturnEmptyTypesWhenNoneAdded() {
			final EvitaEntityResponse<SealedEntity> response =
				createEmptyResponse(createBrandQuery());

			final Set<Class<? extends EvitaResponseExtraResult>>
				types = response.getExtraResultTypes();

			assertNotNull(types);
			assertTrue(types.isEmpty());
		}

		/**
		 * Verifies empty map when none added.
		 */
		@Test
		@DisplayName("returns empty map when none added")
		void shouldReturnEmptyMapWhenNoneAdded() {
			final EvitaEntityResponse<SealedEntity> response =
				createEmptyResponse(createBrandQuery());

			final Map<
				Class<? extends EvitaResponseExtraResult>,
				EvitaResponseExtraResult
				> extras = response.getExtraResults();

			assertNotNull(extras);
			assertTrue(extras.isEmpty());
		}

		/**
		 * Verifies null for nonexistent extra result type.
		 */
		@Test
		@DisplayName("returns null for nonexistent type")
		void shouldReturnNullForNonexistentType() {
			final EvitaEntityResponse<SealedEntity> response =
				createEmptyResponse(createBrandQuery());

			assertNull(
				response.getExtraResult(
					MockExtraResultA.class
				)
			);
		}

		/**
		 * Verifies constructor/get round-trip.
		 */
		@Test
		@DisplayName("round-trips constructor and get")
		void shouldRoundTripConstructorAndGet() {
			final MockExtraResultA extra =
				new MockExtraResultA("hello");
			final EvitaEntityResponse<SealedEntity> response =
				createEmptyResponseWithExtras(
					createBrandQuery(), extra
				);

			final MockExtraResultA retrieved =
				response.getExtraResult(
					MockExtraResultA.class
				);
			assertNotNull(retrieved);
			assertEquals("hello", retrieved.data());
		}

		/**
		 * Verifies types keyset reflects constructor extras.
		 */
		@Test
		@DisplayName("types keyset reflects constructor extras")
		void shouldReflectTypesKeySet() {
			final EvitaEntityResponse<SealedEntity> response =
				createEmptyResponseWithExtras(
					createBrandQuery(),
					new MockExtraResultA("a"),
					new MockExtraResultB(42)
				);

			final Set<
				Class<? extends EvitaResponseExtraResult>
				> types = response.getExtraResultTypes();
			assertEquals(2, types.size());
			assertTrue(
				types.contains(MockExtraResultA.class)
			);
			assertTrue(
				types.contains(MockExtraResultB.class)
			);
		}

		/**
		 * Verifies the extra results map is unmodifiable.
		 */
		@Test
		@DisplayName("returns unmodifiable map")
		void shouldReturnUnmodifiableMap() {
			final EvitaEntityResponse<SealedEntity> response =
				createEmptyResponseWithExtras(
					createBrandQuery(),
					new MockExtraResultA("a")
				);

			final Map<
				Class<? extends EvitaResponseExtraResult>,
				EvitaResponseExtraResult
				> extras = response.getExtraResults();

			assertThrows(
				UnsupportedOperationException.class,
				() -> extras.put(
					MockExtraResultB.class,
					new MockExtraResultB(1)
				)
			);
		}

		/**
		 * Verifies that the last vararg wins when the same
		 * type is passed multiple times.
		 */
		@Test
		@DisplayName("last vararg wins for duplicate type")
		void shouldUseLastVarargWhenSameTypePassed() {
			final EvitaEntityResponse<SealedEntity> response =
				createEmptyResponseWithExtras(
					createBrandQuery(),
					new MockExtraResultA("first"),
					new MockExtraResultA("second")
				);

			final MockExtraResultA result =
				response.getExtraResult(
					MockExtraResultA.class
				);
			assertNotNull(result);
			assertEquals("second", result.data());
			assertEquals(
				1, response.getExtraResultTypes().size()
			);
		}

		/**
		 * Verifies extras passed via constructor.
		 */
		@Test
		@DisplayName("passes extras via constructor")
		void shouldPassExtrasViaConstructor() {
			final MockExtraResultA extraA =
				new MockExtraResultA("x");
			final MockExtraResultB extraB =
				new MockExtraResultB(7);

			final EvitaEntityResponse<SealedEntity> response =
				new EvitaEntityResponse<>(
					createBrandQuery(),
					PaginatedList.emptyList(),
					ArrayUtils.EMPTY_INT_ARRAY,
					extraA, extraB
				);

			assertEquals(
				2,
				response.getExtraResultTypes().size()
			);
			assertSame(
				extraA,
				response.getExtraResult(
					MockExtraResultA.class
				)
			);
			assertSame(
				extraB,
				response.getExtraResult(
					MockExtraResultB.class
				)
			);
		}
	}

	@Nested
	@DisplayName("IO fetch statistics")
	class IoFetchStatsTest {

		/**
		 * Verifies zero fetch count with no decorators.
		 */
		@Test
		@DisplayName(
			"returns zero count when no decorators"
		)
		void shouldReturnZeroCountWhenNoDecorators() {
			final EvitaEntityResponse<SealedEntity> response =
				createEmptyResponse(createBrandQuery());

			assertEquals(0, response.getIoFetchCount());
		}

		/**
		 * Verifies zero fetched bytes with no decorators.
		 */
		@Test
		@DisplayName(
			"returns zero bytes when no decorators"
		)
		void shouldReturnZeroBytesWhenNoDecorators() {
			final EvitaEntityResponse<SealedEntity> response =
				createEmptyResponse(createBrandQuery());

			assertEquals(
				0, response.getIoFetchedSizeBytes()
			);
		}

		/**
		 * Verifies fetch count aggregation from decorators.
		 */
		@Test
		@DisplayName(
			"aggregates fetch count from decorators"
		)
		void shouldAggregateFetchCount() {
			final MockFetchEntity entity1 =
				new MockFetchEntity(3, 100);
			final MockFetchEntity entity2 =
				new MockFetchEntity(5, 200);
			final PaginatedList<MockFetchEntity> page =
				new PaginatedList<>(
					1, 1, 10, 2,
					List.of(entity1, entity2)
				);

			final EvitaEntityResponse<MockFetchEntity>
				response = createResponseWith(
				createBrandQuery(), page
			);

			assertEquals(8, response.getIoFetchCount());
		}

		/**
		 * Verifies fetched bytes aggregation from decorators.
		 */
		@Test
		@DisplayName(
			"aggregates fetched bytes from decorators"
		)
		void shouldAggregateFetchedBytes() {
			final MockFetchEntity entity1 =
				new MockFetchEntity(3, 100);
			final MockFetchEntity entity2 =
				new MockFetchEntity(5, 200);
			final PaginatedList<MockFetchEntity> page =
				new PaginatedList<>(
					1, 1, 10, 2,
					List.of(entity1, entity2)
				);

			final EvitaEntityResponse<MockFetchEntity>
				response = createResponseWith(
				createBrandQuery(), page
			);

			assertEquals(
				300, response.getIoFetchedSizeBytes()
			);
		}

		/**
		 * Verifies IO stats memoization.
		 */
		@Test
		@DisplayName("memoizes IO fetch stats")
		void shouldMemoizeIoFetchStats() {
			final MockFetchEntity entity =
				new MockFetchEntity(2, 50);
			final PaginatedList<MockFetchEntity> page =
				new PaginatedList<>(
					1, 1, 10, 1,
					List.of(entity)
				);

			final EvitaEntityResponse<MockFetchEntity>
				response = createResponseWith(
				createBrandQuery(), page
			);

			// first call computes
			final int count1 = response.getIoFetchCount();
			final int bytes1 =
				response.getIoFetchedSizeBytes();
			// second call returns memoized
			final int count2 = response.getIoFetchCount();
			final int bytes2 =
				response.getIoFetchedSizeBytes();

			assertEquals(count1, count2);
			assertEquals(bytes1, bytes2);
			assertEquals(2, count1);
			assertEquals(50, bytes1);
		}
	}

	@Nested
	@DisplayName("Equality and hashing")
	class EqualityTest {

		/**
		 * Verifies reflexive equality.
		 */
		@Test
		@DisplayName("is reflexively equal")
		void shouldBeReflexivelyEqual() {
			final EvitaEntityResponse<SealedEntity> response =
				createEmptyResponse(createBrandQuery());

			assertEquals(response, response);
		}

		/**
		 * Verifies not equal to null.
		 */
		@Test
		@DisplayName("is not equal to null")
		void shouldNotEqualNull() {
			final EvitaEntityResponse<SealedEntity> response =
				createEmptyResponse(createBrandQuery());

			assertNotEquals(null, response);
		}

		/**
		 * Verifies not equal to a different type.
		 */
		@Test
		@DisplayName("is not equal to different type")
		void shouldNotEqualDifferentType() {
			final EvitaEntityResponse<SealedEntity> response =
				createEmptyResponse(createBrandQuery());

			assertNotEquals("string", response);
		}

		/**
		 * Verifies equality with same page and no extras.
		 */
		@Test
		@DisplayName(
			"equal with same page and no extras"
		)
		void shouldEqualWithSamePageNoExtras() {
			final EvitaEntityResponse<SealedEntity> r1 =
				createEmptyResponse(createBrandQuery());
			final EvitaEntityResponse<SealedEntity> r2 =
				createEmptyResponse(createBrandQuery());

			assertEquals(r1, r2);
		}

		/**
		 * Verifies inequality with different pages,
		 * no extras.
		 */
		@Test
		@DisplayName(
			"not equal with different page no extras"
		)
		void shouldNotEqualDifferentPageNoExtras() {
			final EvitaEntityResponse<SealedEntity> r1 =
				createEmptyResponse(createBrandQuery());
			final PaginatedList<SealedEntity> nonEmpty =
				new PaginatedList<>(
					1, 1, 20, 5, List.of()
				);
			final EvitaEntityResponse<SealedEntity> r2 =
				new EvitaEntityResponse<>(
					createBrandQuery(),
					nonEmpty,
					ArrayUtils.EMPTY_INT_ARRAY
				);

			assertNotEquals(r1, r2);
		}

		/**
		 * Verifies equality with same page and extras.
		 */
		@Test
		@DisplayName(
			"equal with same page and same extras"
		)
		void shouldEqualWithSamePageSameExtras() {
			final EvitaEntityResponse<SealedEntity> r1 =
				createEmptyResponseWithExtras(
					createBrandQuery(),
					new MockExtraResultA("x")
				);

			final EvitaEntityResponse<SealedEntity> r2 =
				createEmptyResponseWithExtras(
					createBrandQuery(),
					new MockExtraResultA("x")
				);

			assertEquals(r1, r2);
		}

		/**
		 * Verifies QueryTelemetry is excluded from equals.
		 */
		@Test
		@DisplayName(
			"excludes QueryTelemetry from equals"
		)
		void shouldExcludeQueryTelemetryFromEquals() {
			final EvitaEntityResponse<SealedEntity> r1 =
				createEmptyResponseWithExtras(
					createBrandQuery(),
					new QueryTelemetry(QueryPhase.OVERALL)
				);

			final EvitaEntityResponse<SealedEntity> r2 =
				createEmptyResponseWithExtras(
					createBrandQuery(),
					new QueryTelemetry(QueryPhase.OVERALL)
				);

			assertEquals(r1, r2);
		}

		/**
		 * Verifies that responses with different record
		 * pages are not equal even when extra results
		 * match.
		 */
		@Test
		@DisplayName(
			"not equal when pages differ despite "
				+ "matching extras"
		)
		void shouldNotEqualWhenPagesDifferDespiteMatchingExtras() {
			final EvitaEntityResponse<SealedEntity> r1 =
				createEmptyResponseWithExtras(
					createBrandQuery(),
					new MockExtraResultA("x")
				);

			// different page (different total count)
			final PaginatedList<SealedEntity> diffPage =
				new PaginatedList<>(
					1, 1, 20, 99, List.of()
				);
			final EvitaEntityResponse<SealedEntity> r2 =
				new EvitaEntityResponse<>(
					createBrandQuery(),
					diffPage,
					ArrayUtils.EMPTY_INT_ARRAY,
					new MockExtraResultA("x")
				);

			assertNotEquals(r1, r2);
		}

		/**
		 * Verifies that responses with different non-QT
		 * extra results are not equal even when map
		 * sizes coincidentally match due to QT presence.
		 */
		@Test
		@DisplayName(
			"not equal when extras differ despite "
				+ "QT making sizes match"
		)
		void shouldNotEqualWhenExtrasDifferDespiteQtSizeMatch() {
			final EvitaEntityResponse<SealedEntity> r1 =
				createEmptyResponseWithExtras(
					createBrandQuery(),
					new MockExtraResultA("x"),
					new QueryTelemetry(QueryPhase.OVERALL)
				);

			final EvitaEntityResponse<SealedEntity> r2 =
				createEmptyResponseWithExtras(
					createBrandQuery(),
					new MockExtraResultA("x"),
					new MockExtraResultB(42)
				);

			// r1={A,QT}, r2={A,B}: different extras,
			// should not be equal
			assertNotEquals(r1, r2);
		}

		/**
		 * Verifies that responses differing only in
		 * QueryTelemetry are equal and have the same
		 * hashCode, satisfying the equals/hashCode
		 * contract.
		 */
		@Test
		@DisplayName(
			"QT-only difference produces equal "
				+ "objects with same hashCode"
		)
		void shouldBeEqualAndSameHashWhenOnlyQtDiffers() {
			final EvitaEntityResponse<SealedEntity> r1 =
				createEmptyResponseWithExtras(
					createBrandQuery(),
					new MockExtraResultA("x"),
					new QueryTelemetry(QueryPhase.OVERALL)
				);

			final EvitaEntityResponse<SealedEntity> r2 =
				createEmptyResponseWithExtras(
					createBrandQuery(),
					new MockExtraResultA("x")
				);
			// r2 has no QT -- should still be equal

			assertEquals(r1, r2);
			assertEquals(
				r1.hashCode(), r2.hashCode(),
				"equal objects must have same hashCode"
			);
		}

		/**
		 * Verifies consistent hashCode across calls.
		 */
		@Test
		@DisplayName("hashCode is consistent")
		void shouldHaveConsistentHashCode() {
			final EvitaEntityResponse<SealedEntity>
				response = createEmptyResponseWithExtras(
				createBrandQuery(),
				new MockExtraResultA("test")
			);

			final int hash1 = response.hashCode();
			final int hash2 = response.hashCode();

			assertEquals(hash1, hash2);
		}

		/**
		 * Verifies equal objects produce same hashCode.
		 */
		@Test
		@DisplayName(
			"equal objects have same hashCode"
		)
		void shouldHaveSameHashCodeWhenEqual() {
			final EvitaEntityResponse<SealedEntity> r1 =
				createEmptyResponse(createBrandQuery());
			final EvitaEntityResponse<SealedEntity> r2 =
				createEmptyResponse(createBrandQuery());

			assertEquals(r1, r2);
			assertEquals(
				r1.hashCode(), r2.hashCode()
			);
		}
	}

	@Nested
	@DisplayName("String representation")
	class ToStringTest {

		/**
		 * Verifies toString without extra results.
		 */
		@Test
		@DisplayName("formats without extra results")
		void shouldFormatWithoutExtras() {
			final EvitaEntityResponse<SealedEntity>
				response =
				createEmptyResponse(createBrandQuery());

			final String str = response.toString();

			assertTrue(str.contains("EvitaResponse:"));
			assertTrue(str.contains("sourceQuery:"));
			assertTrue(str.contains("result:"));
			assertFalse(str.contains("extraResults"));
		}

		/**
		 * Verifies toString includes extras when present.
		 */
		@Test
		@DisplayName("formats with extra results")
		void shouldFormatWithExtras() {
			final EvitaEntityResponse<SealedEntity>
				response = createEmptyResponseWithExtras(
				createBrandQuery(),
				new MockExtraResultA("data")
			);

			final String str = response.toString();

			assertTrue(str.contains("EvitaResponse:"));
			assertTrue(str.contains("extraResults"));
		}
	}

	/**
	 * Mock extra result type A for testing.
	 */
	private record MockExtraResultA(
		String data
	) implements EvitaResponseExtraResult {
		@Serial
		private static final long serialVersionUID =
			133944519712518780L;
	}

	/**
	 * Mock extra result type B for testing.
	 */
	private record MockExtraResultB(
		int value
	) implements EvitaResponseExtraResult {
		@Serial
		private static final long serialVersionUID =
			233944519712518781L;
	}

	/**
	 * Mock entity implementing both {@link Serializable}
	 * and {@link EntityFetchAwareDecorator} for testing IO
	 * fetch statistics aggregation.
	 */
	private record MockFetchEntity(
		int ioFetchCount,
		int ioFetchedBytes
	) implements Serializable, EntityFetchAwareDecorator {
		@Serial
		private static final long serialVersionUID =
			333944519712518782L;

		@Override
		public int getIoFetchCount() {
			return this.ioFetchCount;
		}

		@Override
		public int getIoFetchedBytes() {
			return this.ioFetchedBytes;
		}
	}
}
