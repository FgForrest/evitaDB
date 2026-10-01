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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.order.Segment;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.OrderBehaviour;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Currency;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.ORDER;
import static io.evitadb.test.TestTags.PRICE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the rule of the segment documentation for orderings beyond a natural attribute order: **an entity that
 * provides no data for the ordering of a segment is left out of that segment** and handed to the following segments
 * (here always a last segment ordering the rest by descending primary key). Every scenario runs on both routes - the
 * index route and the route of prefetched entities - and checks from the query telemetry that the forced route was
 * really taken, because a small dataset is easily answered from prefetched bodies.
 *
 * The fixture (`code` filterable and sortable in both scopes, `score` filterable and sortable in the live scope only,
 * `rank` sortable in both scopes; prices in EUR, price lists `basic` and `reference`; the compounds `rankCodeLive`
 * (indexed in the live scope) and `rankCodeBoth` (indexed in both scopes) of `rank` ascending with nulls first and
 * `code` ascending with nulls last - product 6 has no part of them and so no compound value at all):
 *
 * | product | scope    | code | score | rank | basic | reference |
 * |---------|----------|------|-------|------|-------|-----------|
 * | 1       | ARCHIVED | A    | 10    | 10   | -     | -         |
 * | 2       | LIVE     | B    | 20    | 20   | 200   | 250       |
 * | 3       | LIVE     | -    | -     | 30   | 100   | -         |
 * | 4       | LIVE     | Z    | 40    | -    | 150   | 300       |
 * | 5       | ARCHIVED | B    | 5     | 5    | -     | -         |
 * | 6       | LIVE     | -    | -     | -    | -     | -         |
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("A segment leaves out every entity that provides no data for its ordering")
@ExtendWith(EvitaParameterResolver.class)
@Tag(CONTRACT)
@Tag(ORDER)
@Tag(ATTRIBUTE)
@Tag(PRICE)
public class EntitySegmentsFunctionalTest {
	private static final String SEGMENT_PRODUCTS = "segmentProducts";
	private static final String ENTITY_PRODUCT = "product";
	private static final String ATTRIBUTE_CODE = "code";
	private static final String ATTRIBUTE_SCORE = "score";
	private static final String ATTRIBUTE_RANK = "rank";
	private static final String COMPOUND_RANK_CODE_LIVE = "rankCodeLive";
	private static final String COMPOUND_RANK_CODE_BOTH = "rankCodeBoth";
	private static final String PRICE_LIST_BASIC = "basic";
	private static final String PRICE_LIST_REFERENCE = "reference";
	private static final Currency EUR = Currency.getInstance("EUR");
	private static final Scope[] BOTH_SCOPES = {Scope.LIVE, Scope.ARCHIVED};
	private static final Integer[] ALL_PRODUCTS = {1, 2, 3, 4, 5, 6};

	/**
	 * The route the engine takes to order the products.
	 */
	private enum SortRoute {
		/**
		 * Sorted-records providers of the indexes.
		 */
		INDEX,
		/**
		 * Entities are prefetched and ordered by the entity comparators.
		 */
		PREFETCH
	}

	@DataSet(value = SEGMENT_PRODUCTS, destroyAfterClass = true)
	void setUp(EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withoutGeneratedPrimaryKey()
			.withPrice()
			.withAttribute(
				ATTRIBUTE_CODE, String.class,
				thatIs -> thatIs.filterableInScope(BOTH_SCOPES).sortableInScope(BOTH_SCOPES).nullable()
			)
			.withAttribute(
				ATTRIBUTE_SCORE, Integer.class,
				thatIs -> thatIs.filterableInScope(Scope.LIVE).sortableInScope(Scope.LIVE).nullable()
			)
			.withAttribute(ATTRIBUTE_RANK, Integer.class, thatIs -> thatIs.sortableInScope(BOTH_SCOPES).nullable())
			.withSortableAttributeCompound(
				COMPOUND_RANK_CODE_LIVE,
				new AttributeElement[]{
					new AttributeElement(ATTRIBUTE_RANK, OrderDirection.ASC, OrderBehaviour.NULLS_FIRST),
					new AttributeElement(ATTRIBUTE_CODE, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
				},
				thatIs -> thatIs.indexedInScope(Scope.LIVE)
			)
			.withSortableAttributeCompound(
				COMPOUND_RANK_CODE_BOTH,
				new AttributeElement[]{
					new AttributeElement(ATTRIBUTE_RANK, OrderDirection.ASC, OrderBehaviour.NULLS_FIRST),
					new AttributeElement(ATTRIBUTE_CODE, OrderDirection.ASC, OrderBehaviour.NULLS_LAST)
				},
				thatIs -> thatIs.indexedInScope(BOTH_SCOPES)
			)
			.updateVia(session);

		upsertProduct(session, 1, "A", 10, 10, null, null);
		upsertProduct(session, 2, "B", 20, 20, 200, 250);
		upsertProduct(session, 3, null, null, 30, 100, null);
		upsertProduct(session, 4, "Z", 40, null, 150, 300);
		upsertProduct(session, 5, "B", 5, 5, null, null);
		upsertProduct(session, 6, null, null, null, null, null);
		session.archiveEntity(ENTITY_PRODUCT, 1);
		session.archiveEntity(ENTITY_PRODUCT, 5);
	}

	/**
	 * Upserts one product with the passed attributes and prices, skipping every `null`.
	 */
	private static void upsertProduct(
		@Nonnull EvitaSessionContract session,
		int primaryKey,
		@Nullable String code,
		@Nullable Integer score,
		@Nullable Integer rank,
		@Nullable Integer basicPrice,
		@Nullable Integer referencePrice
	) {
		final EntityBuilder builder = session.createNewEntity(ENTITY_PRODUCT, primaryKey);
		if (code != null) {
			builder.setAttribute(ATTRIBUTE_CODE, code);
		}
		if (score != null) {
			builder.setAttribute(ATTRIBUTE_SCORE, score);
		}
		if (rank != null) {
			builder.setAttribute(ATTRIBUTE_RANK, rank);
		}
		if (basicPrice != null) {
			final BigDecimal price = BigDecimal.valueOf(basicPrice);
			builder.setPrice(1, PRICE_LIST_BASIC, EUR, null, price, BigDecimal.ZERO, price, true);
		}
		if (referencePrice != null) {
			final BigDecimal price = BigDecimal.valueOf(referencePrice);
			builder.setPrice(2, PRICE_LIST_REFERENCE, EUR, null, price, BigDecimal.ZERO, price, true);
		}
		builder.upsertVia(session);
	}

	/**
	 * Runs a query on the forced route, asserts the route was taken and returns the product primary keys in result
	 * order. The filter always holds every product primary key - without a primary key set the plan cannot prefetch
	 * at all - next to the passed `filter`.
	 */
	@Nonnull
	private static int[] queryOrder(
		@Nonnull EvitaSessionContract session,
		@Nonnull SortRoute route,
		@Nullable FilterConstraint filter,
		@Nonnull OrderConstraint... orderings
	) {
		return queryOrderExactly(session, route, and(entityPrimaryKeyInSet(ALL_PRODUCTS), filter), orderings);
	}

	/**
	 * Runs a query with exactly the passed filter on the forced route, asserts the route was taken and returns the
	 * product primary keys in result order.
	 */
	@Nonnull
	private static int[] queryOrderExactly(
		@Nonnull EvitaSessionContract session,
		@Nonnull SortRoute route,
		@Nonnull FilterConstraint filter,
		@Nonnull OrderConstraint... orderings
	) {
		final EvitaResponse<EntityReference> response = session.query(
			query(
				collection(ENTITY_PRODUCT),
				filterBy(filter),
				orderBy(orderings),
				require(
					page(1, 100),
					queryTelemetry(),
					debug(route == SortRoute.PREFETCH ? DebugMode.PREFER_PREFETCHING : DebugMode.PREFER_INDEX_SCAN)
				)
			),
			EntityReference.class
		);
		final QueryTelemetry telemetry = response.getExtraResult(QueryTelemetry.class);
		assertNotNull(telemetry, "Query telemetry must be present - it is the only proof of the route taken!");
		assertEquals(
			route == SortRoute.PREFETCH, containsPhase(telemetry, QueryPhase.EXECUTION_PREFETCH),
			"the query did not take the " + route + " route"
		);
		return response.getRecordData().stream().mapToInt(EntityReference::getPrimaryKey).toArray();
	}

	/**
	 * Runs a query whose segments are `firstSegment` followed by a segment ordering the rest by descending primary
	 * key.
	 */
	@Nonnull
	private static int[] querySegments(
		@Nonnull EvitaSessionContract session,
		@Nonnull SortRoute route,
		@Nullable FilterConstraint filter,
		@Nonnull Segment firstSegment
	) {
		return queryOrder(
			session, route, filter,
			segments(firstSegment, segment(orderBy(entityPrimaryKeyNatural(OrderDirection.DESC))))
		);
	}

	/**
	 * Walks the telemetry tree looking for a step of the given phase.
	 */
	private static boolean containsPhase(@Nonnull QueryTelemetry telemetry, @Nonnull QueryPhase phase) {
		if (telemetry.getOperation() == phase) {
			return true;
		}
		for (QueryTelemetry step : telemetry.getSteps()) {
			if (containsPhase(step, phase)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Asserts the product order and reports both full arrays on a mismatch.
	 */
	private static void assertOrder(@Nonnull int[] expected, @Nonnull int[] actual, @Nonnull String label) {
		assertArrayEquals(
			expected, actual,
			() -> label + ": expected " + Arrays.toString(expected) + " but was " + Arrays.toString(actual)
		);
	}

	@DisplayName("An exact attribute order leaves out the entities without a listed value")
	@UseDataSet(SEGMENT_PRODUCTS)
	@ParameterizedTest(name = "{0}")
	@EnumSource(SortRoute.class)
	void shouldLeaveEntitiesWithoutListedValueOutOfExactAttributeSegment(
		SortRoute route, EvitaSessionContract session
	) {
		// live products 2 (B), 3 (no code), 4 (Z, unlisted), 6 (no code)
		assertAll(
			() -> assertOrder(
				new int[]{6, 4, 3, 2},
				querySegments(
					session, route, null,
					segment(
						entityHaving(entityPrimaryKeyInSet(3)),
						orderBy(attributeSetExact(ATTRIBUTE_CODE, "B", "A"))
					)
				),
				"lone product without the attribute"
			),
			() -> assertOrder(
				new int[]{6, 4, 3, 2},
				querySegments(
					session, route, null,
					segment(
						entityHaving(entityPrimaryKeyInSet(4)),
						orderBy(attributeSetExact(ATTRIBUTE_CODE, "B", "A"))
					)
				),
				"lone product with an unlisted value"
			),
			() -> assertOrder(
				new int[]{2, 6, 4, 3},
				querySegments(session, route, null, segment(orderBy(attributeSetExact(ATTRIBUTE_CODE, "B", "A")))),
				"listed, unlisted and missing values"
			),
			() -> assertOrder(
				new int[]{4, 2, 6, 3},
				querySegments(session, route, null, segment(orderBy(attributeSetExact(ATTRIBUTE_CODE, "Z", "B")))),
				"two listed values"
			)
		);
	}

	@DisplayName("An order by the values of a filter leaves out the entities the filter selected by another branch")
	@UseDataSet(SEGMENT_PRODUCTS)
	@ParameterizedTest(name = "{0}")
	@EnumSource(SortRoute.class)
	void shouldLeaveEntitiesSelectedByAnotherBranchOutOfInFilterSegment(
		SortRoute route, EvitaSessionContract session
	) {
		// the filter selects products 4 and 2 by the listed values and 3 and 6 by the other branch of `or`
		assertAll(
			() -> assertOrder(
				new int[]{4, 2, 6, 3},
				querySegments(
					session, route,
					or(attributeInSet(ATTRIBUTE_CODE, "Z", "B"), attributeIsNull(ATTRIBUTE_CODE)),
					segment(orderBy(attributeSetInFilter(ATTRIBUTE_CODE)))
				),
				"attribute values of the filter"
			),
			() -> assertOrder(
				new int[]{4, 2, 6, 3},
				querySegments(session, route, null, segment(orderBy(entityPrimaryKeyExact(4, 2)))),
				"exact primary keys"
			)
		);
	}

	@DisplayName("An order by the primary keys of a filter leaves out the entities selected by another branch")
	@UseDataSet(SEGMENT_PRODUCTS)
	@Test
	void shouldLeaveEntitiesSelectedByAnotherBranchOutOfPrimaryKeyInFilterSegment(EvitaSessionContract session) {
		// the order follows the first primary key set of the filter, so the filter can hold no other - and without
		// one covering every selected product the plan cannot prefetch: the index route is the only one; the sorter
		// orders primary keys alone and is the same on both routes
		assertOrder(
			new int[]{4, 2, 6, 3},
			queryOrderExactly(
				session, SortRoute.INDEX,
				or(entityPrimaryKeyInSet(4, 2), attributeIsNull(ATTRIBUTE_SCORE)),
				segments(
					segment(orderBy(entityPrimaryKeyInFilter())),
					segment(orderBy(entityPrimaryKeyNatural(OrderDirection.DESC)))
				)
			),
			"primary keys of the filter"
		);
	}

	@DisplayName("A scoped natural attribute order leaves out the entities of the other scope")
	@UseDataSet(SEGMENT_PRODUCTS)
	@ParameterizedTest(name = "{0}")
	@EnumSource(SortRoute.class)
	void shouldLeaveEntitiesOfOtherScopeOutOfScopedAttributeSegment(SortRoute route, EvitaSessionContract session) {
		// archived product 1 carries `score`, which is sortable in the live scope only - the archived index has no sort
		// index for it, and the prefetched comparator must not sort the product either
		final FilterConstraint bothScopes = scope(BOTH_SCOPES);
		assertAll(
			() -> assertOrder(
				new int[]{2, 6, 5, 4, 3, 1},
				querySegments(
					session, route, bothScopes,
					segment(
						entityHaving(entityPrimaryKeyInSet(1, 2)),
						orderBy(inScope(Scope.LIVE, attributeNatural(ATTRIBUTE_SCORE)))
					)
				),
				"attribute sortable in the live scope only"
			),
			() -> assertOrder(
				new int[]{2, 4, 6, 5, 3, 1},
				queryOrder(
					session, route, bothScopes,
					inScope(Scope.LIVE, attributeNatural(ATTRIBUTE_SCORE)), entityPrimaryKeyNatural(OrderDirection.DESC)
				),
				"outside segments, the archived products are left to the next sorter as well"
			)
		);
	}

	@DisplayName("A scoped ascending primary key order takes only the entities of its scope")
	@UseDataSet(SEGMENT_PRODUCTS)
	@ParameterizedTest(name = "{0}")
	@EnumSource(SortRoute.class)
	void shouldTakeOnlyEntitiesOfItsScopeIntoScopedPrimaryKeySegment(SortRoute route, EvitaSessionContract session) {
		final FilterConstraint bothScopes = scope(BOTH_SCOPES);
		assertAll(
			() -> assertOrder(
				new int[]{2, 6, 5, 4, 3, 1},
				querySegments(
					session, route, bothScopes,
					segment(orderBy(inScope(Scope.LIVE, entityPrimaryKeyNatural(OrderDirection.ASC))), limit(1))
				),
				"live scope"
			),
			() -> assertOrder(
				new int[]{1, 6, 5, 4, 3, 2},
				querySegments(
					session, route, bothScopes,
					segment(orderBy(inScope(Scope.ARCHIVED, entityPrimaryKeyNatural(OrderDirection.ASC))), limit(1))
				),
				"archived scope"
			),
			() -> assertOrder(
				new int[]{1, 5, 6, 4, 3, 2},
				querySegments(
					session, route, bothScopes,
					segment(orderBy(inScope(Scope.ARCHIVED, entityPrimaryKeyNatural(OrderDirection.ASC))))
				),
				"archived scope without limit"
			)
		);
	}

	@DisplayName("An ascending primary key order places the entities at its declared position in the segment")
	@UseDataSet(SEGMENT_PRODUCTS)
	@ParameterizedTest(name = "{0}")
	@EnumSource(SortRoute.class)
	void shouldPlaceAscendingPrimaryKeyOrderAtItsDeclaredPosition(SortRoute route, EvitaSessionContract session) {
		// the primary key order comes first, so it fills the one-entity segment with the smallest primary key - the
		// descending `score` order (4 has the highest one) is never reached
		assertAll(
			() -> assertOrder(
				new int[]{2, 6, 4, 3},
				querySegments(
					session, route, null,
					segment(
						orderBy(
							entityPrimaryKeyNatural(OrderDirection.ASC),
							attributeNatural(ATTRIBUTE_SCORE, OrderDirection.DESC)
						),
						limit(1)
					)
				),
				"ascending primary key before a descending attribute"
			),
			() -> assertOrder(
				new int[]{2, 6, 5, 4, 3, 1},
				querySegments(
					session, route, scope(BOTH_SCOPES),
					segment(
						orderBy(
							inScope(Scope.LIVE, entityPrimaryKeyNatural(OrderDirection.ASC)),
							inScope(Scope.LIVE, attributeNatural(ATTRIBUTE_SCORE, OrderDirection.DESC))
						),
						limit(1)
					)
				),
				"scoped ascending primary key before a scoped descending attribute"
			)
		);
	}

	@DisplayName("A scope container around the segments restricts the ascending primary key order in them")
	@UseDataSet(SEGMENT_PRODUCTS)
	@ParameterizedTest(name = "{0}")
	@EnumSource(SortRoute.class)
	void shouldRestrictAscendingPrimaryKeyOrderByScopeEnclosingSegments(SortRoute route, EvitaSessionContract session) {
		// the live scope encloses the whole segments container, so the first segment takes live product 2, not the
		// archived product 1 with a smaller primary key, and the second one orders only the live products - the
		// archived ones follow all segments in ascending primary key order
		assertOrder(
			new int[]{2, 6, 4, 3, 1, 5},
			queryOrder(
				session, route, scope(BOTH_SCOPES),
				inScope(
					Scope.LIVE,
					segments(
						segment(orderBy(entityPrimaryKeyNatural(OrderDirection.ASC)), limit(1)),
						segment(orderBy(entityPrimaryKeyNatural(OrderDirection.DESC)))
					)
				)
			),
			"segments in a live scope container"
		);
	}

	@DisplayName("Segments nested in the ordering of a segment are rejected when the query runs")
	@UseDataSet(SEGMENT_PRODUCTS)
	@Test
	void shouldRejectNestedSegmentsWhenQueryRuns(EvitaSessionContract session) {
		// the constraints themselves accept the shape - a stored query must stay readable - so the query fails only
		// when it is planned; the segments of the control query sit side by side and inside a scope container
		final EvitaInvalidUsageException direct = assertThrows(
			EvitaInvalidUsageException.class,
			() -> queryOrder(
				session, SortRoute.INDEX, null,
				segments(
					segment(orderBy(segments(segment(orderBy(entityPrimaryKeyNatural(OrderDirection.DESC))))), limit(1))
				)
			)
		);
		assertTrue(direct.getMessage().contains("Nested `segments`"), direct.getMessage());
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> queryOrder(
				session, SortRoute.INDEX, scope(BOTH_SCOPES),
				segments(
					segment(
						orderBy(inScope(Scope.LIVE, segments(segment(orderBy(attributeNatural(ATTRIBUTE_RANK))))))
					)
				)
			)
		);
		assertOrder(
			new int[]{2, 6, 4, 3, 1, 5},
			queryOrder(
				session, SortRoute.INDEX, scope(BOTH_SCOPES),
				inScope(
					Scope.LIVE,
					segments(
						segment(orderBy(inScope(Scope.LIVE, attributeNatural(ATTRIBUTE_SCORE))), limit(1)),
						segment(orderBy(entityPrimaryKeyNatural(OrderDirection.DESC)))
					)
				)
			),
			"segments side by side in a scope container"
		);
	}

	@DisplayName("A scoped attribute order sorts only the entities of its scope")
	@UseDataSet(SEGMENT_PRODUCTS)
	@ParameterizedTest(name = "{0}")
	@EnumSource(SortRoute.class)
	void shouldApplyScopedAttributeOrderOnlyToItsScope(SortRoute route, EvitaSessionContract session) {
		// `rank` is sortable in both scopes, yet an order in the live scope sorts only the live products 2 (20) and
		// 3 (30); the archived products 1 (10) and 5 (5) are left to the next order
		final FilterConstraint bothScopes = scope(BOTH_SCOPES);
		assertAll(
			() -> assertOrder(
				new int[]{2, 3, 1, 4, 5, 6},
				queryOrder(session, route, bothScopes, inScope(Scope.LIVE, attributeNatural(ATTRIBUTE_RANK))),
				"live scope only"
			),
			() -> assertOrder(
				new int[]{2, 3, 1, 5, 4, 6},
				queryOrder(
					session, route, bothScopes,
					inScope(Scope.LIVE, attributeNatural(ATTRIBUTE_RANK)),
					inScope(Scope.ARCHIVED, attributeNatural(ATTRIBUTE_RANK, OrderDirection.DESC))
				),
				"each scope by its own order"
			),
			() -> assertOrder(
				new int[]{2, 6, 5, 4, 3, 1},
				querySegments(
					session, route, bothScopes,
					segment(
						entityHaving(entityPrimaryKeyInSet(1, 2)),
						orderBy(inScope(Scope.LIVE, attributeNatural(ATTRIBUTE_RANK)))
					)
				),
				"segment ordered in the live scope"
			),
			() -> assertOrder(
				new int[]{2, 1, 3, 4, 5, 6},
				queryOrder(
					session, route, bothScopes, inScope(Scope.LIVE, attributeSetExact(ATTRIBUTE_CODE, "B", "A"))
				),
				"exact attribute order in the live scope"
			),
			() -> assertOrder(
				new int[]{5, 1, 2, 3, 4, 6},
				queryOrder(session, route, bothScopes, attributeNatural(ATTRIBUTE_RANK)),
				"without a scope container both scopes are sorted"
			)
		);
	}

	@DisplayName("A scoped primary key order sorts only the entities of its scope at its declared position")
	@UseDataSet(SEGMENT_PRODUCTS)
	@ParameterizedTest(name = "{0}")
	@EnumSource(SortRoute.class)
	void shouldApplyScopedPrimaryKeyOrderOnlyToItsScope(SortRoute route, EvitaSessionContract session) {
		// live products 2, 3, 4, 6; archived products 1 (rank 10) and 5 (rank 5)
		final FilterConstraint bothScopes = scope(BOTH_SCOPES);
		assertAll(
			() -> assertOrder(
				new int[]{6, 4, 3, 2, 5, 1},
				queryOrder(
					session, route, bothScopes,
					inScope(Scope.LIVE, entityPrimaryKeyNatural(OrderDirection.DESC)),
					inScope(Scope.ARCHIVED, attributeNatural(ATTRIBUTE_RANK))
				),
				"descending primary key in the live scope"
			),
			() -> assertOrder(
				new int[]{2, 3, 4, 6, 5, 1},
				queryOrder(
					session, route, bothScopes,
					inScope(Scope.LIVE, entityPrimaryKeyNatural(OrderDirection.ASC)),
					inScope(Scope.ARCHIVED, attributeNatural(ATTRIBUTE_RANK))
				),
				"ascending primary key in the live scope"
			),
			() -> assertOrder(
				new int[]{5, 6, 4, 3, 2, 1},
				querySegments(
					session, route, bothScopes,
					segment(orderBy(inScope(Scope.ARCHIVED, entityPrimaryKeyNatural(OrderDirection.DESC))), limit(1))
				),
				"segment ordered by descending primary key in the archived scope"
			),
			() -> assertOrder(
				new int[]{6, 5, 4, 3, 2, 1},
				queryOrder(session, route, bothScopes, entityPrimaryKeyNatural(OrderDirection.DESC)),
				"without a scope container both scopes are sorted"
			)
		);
	}

	@DisplayName("A compound order sorts only the entities the compound index holds")
	@UseDataSet(SEGMENT_PRODUCTS)
	@ParameterizedTest(name = "{0}")
	@EnumSource(SortRoute.class)
	void shouldSortCompoundLikeTheIndexRoute(SortRoute route, EvitaSessionContract session) {
		// the compound index holds no entry for product 6 (no part present) and none for the scopes it is not indexed
		// in - nulls first puts 4 (no rank, code Z) before 2 (20, B) and 3 (30, no code)
		final FilterConstraint bothScopes = scope(BOTH_SCOPES);
		assertAll(
			() -> assertOrder(
				new int[]{4, 2, 3, 6},
				queryOrder(session, route, null, attributeNatural(COMPOUND_RANK_CODE_LIVE)),
				"product without any part of the compound"
			),
			() -> assertOrder(
				new int[]{4, 2, 3, 1, 5, 6},
				queryOrder(session, route, bothScopes, inScope(Scope.LIVE, attributeNatural(COMPOUND_RANK_CODE_LIVE))),
				"compound indexed in the live scope only"
			),
			() -> assertOrder(
				new int[]{4, 5, 1, 2, 3, 6},
				queryOrder(session, route, bothScopes, attributeNatural(COMPOUND_RANK_CODE_BOTH)),
				"compound indexed in both scopes"
			),
			() -> assertOrder(
				new int[]{4, 2, 3, 1, 5, 6},
				queryOrder(session, route, bothScopes, inScope(Scope.LIVE, attributeNatural(COMPOUND_RANK_CODE_BOTH))),
				"compound indexed in both scopes, ordered in the live scope"
			),
			() -> assertOrder(
				new int[]{2, 6, 4, 3},
				querySegments(
					session, route, null,
					segment(
						entityHaving(entityPrimaryKeyInSet(2, 6)),
						orderBy(attributeNatural(COMPOUND_RANK_CODE_LIVE))
					)
				),
				"segment leaves out the product without any part of the compound"
			)
		);
	}

	@DisplayName("A price order leaves out the entities without the price it sorts by")
	@UseDataSet(SEGMENT_PRODUCTS)
	@ParameterizedTest(name = "{0}")
	@EnumSource(SortRoute.class)
	void shouldLeaveEntitiesWithoutPriceOutOfPriceSegment(SortRoute route, EvitaSessionContract session) {
		// the price filter selects live products 2, 3 and 4 - product 3 has no reference price, so no discount
		final FilterConstraint sellingPrice = and(priceInPriceLists(PRICE_LIST_BASIC), priceInCurrency(EUR));
		assertAll(
			() -> assertOrder(
				new int[]{4, 3, 2},
				querySegments(
					session, route, sellingPrice,
					segment(
						entityHaving(entityPrimaryKeyInSet(3)),
						orderBy(priceDiscount(OrderDirection.ASC, PRICE_LIST_REFERENCE))
					)
				),
				"lone product without a discount"
			),
			() -> assertOrder(
				new int[]{2, 4, 3},
				querySegments(
					session, route, sellingPrice,
					segment(orderBy(priceDiscount(OrderDirection.ASC, PRICE_LIST_REFERENCE)))
				),
				"discounts 50 and 150, product 3 without one"
			),
			() -> assertOrder(
				new int[]{3, 4, 2},
				querySegments(session, route, sellingPrice, segment(orderBy(priceNatural(OrderDirection.ASC)))),
				"every selected product has a selling price"
			),
			() -> assertOrder(
				new int[]{6, 4, 3, 2},
				querySegments(session, route, null, segment(orderBy(priceNatural(OrderDirection.ASC)))),
				"no selling price without a price filter"
			),
			() -> assertOrder(
				new int[]{6, 4, 3, 2},
				querySegments(
					session, route, null,
					segment(orderBy(priceDiscount(OrderDirection.ASC, PRICE_LIST_REFERENCE)))
				),
				"no discount without a price filter"
			)
		);
	}

	@DisplayName("A price order outside segments keeps its fallback for entities without price data")
	@UseDataSet(SEGMENT_PRODUCTS)
	@Test
	void shouldKeepPriceFallbackOutsideSegments(EvitaSessionContract session) {
		// the index route only: on the prefetch route the same queries fail with a ContextMissingException (the
		// comparators read the price for sale of a query without a price filter) - a defect outside segments, which
		// the segment rule deliberately does not touch
		final SortRoute route = SortRoute.INDEX;
		assertAll(
			() -> assertOrder(
				new int[]{2, 3, 4, 6},
				queryOrder(
					session, route, null,
					priceNatural(OrderDirection.ASC), entityPrimaryKeyNatural(OrderDirection.DESC)
				),
				"natural price without a price filter"
			),
			() -> assertOrder(
				new int[]{2, 3, 4, 6},
				queryOrder(
					session, route, null,
					priceDiscount(OrderDirection.ASC, PRICE_LIST_REFERENCE),
					entityPrimaryKeyNatural(OrderDirection.DESC)
				),
				"discount without a price filter"
			)
		);
	}

}
