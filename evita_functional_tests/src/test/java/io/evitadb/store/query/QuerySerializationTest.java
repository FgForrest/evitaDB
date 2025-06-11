/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.store.query;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.AttributeSpecialValue;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.query.require.HistogramBehavior;
import io.evitadb.api.query.require.ManagedReferencesBehaviour;
import io.evitadb.api.query.require.PriceContentMode;
import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.dataType.Scope;
import io.evitadb.store.service.KryoFactory;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Locale;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test tries to serialize and deserialize all variants of a query object and its constraints.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class QuerySerializationTest {
	private final Kryo kryo = KryoFactory.createKryo(QuerySerializationKryoConfigurer.INSTANCE);

	@Test
	void shouldSerializeQuery() {
		assertSerializationRound(Query.query(collection("a")));
		assertSerializationRound(Query.query(collection("a"), filterBy(attributeEquals("a", "b"))));
		assertSerializationRound(Query.query(collection("a"), filterBy(attributeEquals("a", "b")), orderBy(attributeNatural("a", OrderDirection.ASC))));
		assertSerializationRound(Query.query(collection("a"), orderBy(attributeNatural("a", OrderDirection.ASC), attributeNatural("b", OrderDirection.DESC))));
		assertSerializationRound(Query.query(collection("a"), filterBy(attributeEquals("a", "b")), orderBy(attributeNatural("a", OrderDirection.ASC)), require(debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS), entityFetchAll())));
		assertSerializationRound(Query.query(collection("a"), require(debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS), entityFetchAll())));
	}

	@Test
	void shouldSerializeHeadConstraints() {
		assertSerializationRound(collection("a"));
		assertSerializationRound(label("a", "b"));
		assertSerializationRound(head(collection("a"), label("a", "b"), label("c", "d")));
	}

	@Test
	void shouldSerializeFilteringConstraints() {
		assertSerializationRound(filterBy(attributeEquals("a", "b")));
		assertSerializationRound(filterBy(attributeEquals("a", "b"), attributeIs("d", AttributeSpecialValue.NULL)));
		assertSerializationRound(and(attributeEquals("a", "b"), attributeEquals("c", "d")));
		assertSerializationRound(or(attributeEquals("a", "b"), attributeEquals("c", "d")));
		assertSerializationRound(not(attributeEquals("a", "b")));
		assertSerializationRound(entityPrimaryKeyInSet(1, 2, 3));
		assertSerializationRound(attributeEquals("a", "b"));
		assertSerializationRound(attributeLessThan("a", "b"));
		assertSerializationRound(attributeLessThanEquals("a", "b"));
		assertSerializationRound(attributeGreaterThan("a", "b"));
		assertSerializationRound(attributeGreaterThanEquals("a", "b"));
		assertSerializationRound(attributeBetween("a", BigDecimal.ZERO, BigDecimal.ONE));
		assertSerializationRound(attributeInRange("a", OffsetDateTime.now()));
		assertSerializationRound(attributeInRange("a", 12L));
		assertSerializationRound(attributeInRangeNow("a"));
		assertSerializationRound(attributeInSet("a", "b", "c"));
		assertSerializationRound(attributeIs("a", AttributeSpecialValue.NULL));
		assertSerializationRound(attributeIs("a", AttributeSpecialValue.NOT_NULL));
		assertSerializationRound(attributeContains("a", "b"));
		assertSerializationRound(attributeStartsWith("a", "b"));
		assertSerializationRound(attributeEndsWith("a", "b"));
		assertSerializationRound(entityLocaleEquals(Locale.ENGLISH));
		assertSerializationRound(entityHaving(attributeEquals("a", "b")));
		assertSerializationRound(hierarchyWithin("d", attributeEquals("code", "a")));
		assertSerializationRound(hierarchyWithin("d", attributeEquals("code", "a"), excluding(attributeEquals("code", "a"))));
		assertSerializationRound(hierarchyWithin("d", attributeEquals("code", "a"), having(attributeEquals("code", "a"))));
		assertSerializationRound(hierarchyWithin("d", attributeEquals("code", "a"), directRelation()));
		assertSerializationRound(hierarchyWithin("d", attributeEquals("code", "a"), excludingRoot()));
		assertSerializationRound(hierarchyWithinRoot("d"));
		assertSerializationRound(hierarchyWithinRoot("d", excluding(attributeEquals("code", "a"))));
		assertSerializationRound(hierarchyWithinRoot("d", having(attributeEquals("code", "a"))));
		assertSerializationRound(inScope(Scope.LIVE, attributeEquals("a", "b")));
		assertSerializationRound(facetHaving("d", or(attributeEquals("code", "a"), attributeEquals("code", "b"))));
		assertSerializationRound(priceInCurrency(Currency.getInstance("USD")));
		assertSerializationRound(priceValidIn(OffsetDateTime.now()));
		assertSerializationRound(priceValidInNow());
		assertSerializationRound(priceInPriceLists("basic", "vip"));
		assertSerializationRound(priceBetween(BigDecimal.ZERO, BigDecimal.ONE));
		assertSerializationRound(priceBetween(null, BigDecimal.ONE));
		assertSerializationRound(priceBetween(BigDecimal.ONE, null));
		assertSerializationRound(referenceHaving("d", attributeEquals("a", "b")));
		assertSerializationRound(scope(Scope.LIVE, Scope.ARCHIVED));
		assertSerializationRound(userFilter(attributeEquals("a", "b"), priceBetween(BigDecimal.ZERO, BigDecimal.ONE)));
	}

	@Test
	void shouldSerializeOrderingConstraints() {
		assertSerializationRound(orderBy(attributeNatural("a", OrderDirection.ASC)));
		assertSerializationRound(orderBy(attributeNatural("a", OrderDirection.ASC), attributeNatural("b", OrderDirection.DESC)));
		assertSerializationRound(attributeNatural("a", OrderDirection.DESC));
		assertSerializationRound(attributeSetExact("a", "b", "c"));
		assertSerializationRound(attributeSetInFilter("a"));
		assertSerializationRound(entityPrimaryKeyInFilter());
		assertSerializationRound(entityPrimaryKeyNatural(OrderDirection.DESC));
		assertSerializationRound(entityPrimaryKeyExact(1, 8, 10, 3));
		assertSerializationRound(inScope(Scope.LIVE, attributeNatural("a", OrderDirection.ASC)));
		assertSerializationRound(priceNatural(OrderDirection.ASC));
		assertSerializationRound(random());
		assertSerializationRound(randomWithSeed(42));
		assertSerializationRound(referenceProperty("d", attributeNatural("a", OrderDirection.ASC)));
		assertSerializationRound(limit(10));
		assertSerializationRound(segment(orderBy(attributeNatural("a", OrderDirection.DESC))));
		assertSerializationRound(segment(entityHaving(attributeEquals("a", "b")), orderBy(attributeNatural("a", OrderDirection.DESC))));
		assertSerializationRound(
			segments(
				segment(orderBy(attributeNatural("a", OrderDirection.DESC))),
				segment(orderBy(attributeNatural("a", OrderDirection.DESC)), limit(10)),
				segment(entityHaving(attributeEquals("a", "b")), orderBy(attributeNatural("a", OrderDirection.DESC))),
				segment(entityHaving(attributeEquals("a", "b")), orderBy(attributeNatural("a", OrderDirection.DESC)), limit(10))
			)
		);
	}

	@Test
	void shouldSerializeRequireConstraints() {
		assertSerializationRound(require(debug(DebugMode.PREFER_PREFETCHING)));
		assertSerializationRound(debug(DebugMode.PREFER_PREFETCHING, DebugMode.VERIFY_POSSIBLE_CACHING_TREES, DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS));

		assertSerializationRound(entityFetch());
		assertSerializationRound(entityFetch(attributeContentAll()));
		assertSerializationRound(entityFetch(attributeContent("a", "b", "c")));
		assertSerializationRound(entityFetch(associatedDataContentAll()));
		assertSerializationRound(entityFetch(associatedDataContent("a", "b", "c")));
		assertSerializationRound(entityFetch(referenceContentAll()));
		assertSerializationRound(entityFetch(referenceContent("a", "b", "c")));
		assertSerializationRound(entityFetch(referenceContent("a", entityFetchAll())));
		assertSerializationRound(entityFetch(referenceContent(new String[] {"a", "b"}, entityFetchAll())));
		assertSerializationRound(entityFetch(referenceContent("a", entityGroupFetchAll())));
		assertSerializationRound(entityFetch(referenceContent(new String[] {"a", "b"}, entityGroupFetchAll())));
		assertSerializationRound(entityFetch(referenceContent("a", entityFetchAll(), entityGroupFetchAll())));
		assertSerializationRound(entityFetch(referenceContent(new String[] {"a", "b"}, entityFetchAll(), entityGroupFetchAll())));
		assertSerializationRound(entityFetch(referenceContent("a", filterBy(attributeEquals("a", "b")))));
		assertSerializationRound(entityFetch(referenceContent("a", filterBy(attributeEquals("a", "b")), entityFetchAll())));
		assertSerializationRound(entityFetch(referenceContent("a", filterBy(attributeEquals("a", "b")), entityFetchAll(), page(1, 20))));
		assertSerializationRound(entityFetch(referenceContentAllWithAttributes()));
		assertSerializationRound(entityFetch(referenceContentAllWithAttributes(attributeContent("a", "b", "c"))));
		assertSerializationRound(entityFetch(referenceContentAllWithAttributes(entityFetchAll())));
		assertSerializationRound(entityFetch(referenceContentAllWithAttributes(attributeContent("a", "b", "c"), entityFetchAll())));
		assertSerializationRound(entityFetch(referenceContentAllWithAttributes(entityGroupFetchAll())));
		assertSerializationRound(entityFetch(referenceContentAllWithAttributes(attributeContent("a", "b", "c"), entityGroupFetchAll())));
		assertSerializationRound(entityFetch(referenceContentAllWithAttributes(entityFetchAll(), entityGroupFetchAll())));
		assertSerializationRound(entityFetch(referenceContentAllWithAttributes(attributeContent("a", "b", "c"), entityFetchAll(), entityGroupFetchAll())));
		assertSerializationRound(entityFetch(referenceContentAllWithAttributes(ManagedReferencesBehaviour.ANY)));
		assertSerializationRound(entityFetch(referenceContentAllWithAttributes(ManagedReferencesBehaviour.ANY, attributeContent("a", "b", "c"))));
		assertSerializationRound(entityFetch(referenceContentAllWithAttributes(ManagedReferencesBehaviour.ANY, attributeContent("a", "b", "c"), page(1, 20))));
		assertSerializationRound(entityFetch(referenceContentWithAttributes("a")));
		assertSerializationRound(entityFetch(referenceContentWithAttributes("a", "b", "c")));
		assertSerializationRound(entityFetch(referenceContentWithAttributes("a", attributeContent("b", "c"))));
		assertSerializationRound(entityFetch(referenceContentWithAttributes("a", entityFetchAll())));
		assertSerializationRound(entityFetch(referenceContentWithAttributes("a", attributeContent("b", "c"), entityFetchAll())));
		assertSerializationRound(entityFetch(referenceContentWithAttributes("a", entityGroupFetchAll())));
		assertSerializationRound(entityFetch(referenceContentWithAttributes("a", attributeContent("b", "c"), entityGroupFetchAll())));
		assertSerializationRound(entityFetch(referenceContentWithAttributes("a", entityFetchAll(), entityGroupFetchAll())));
		assertSerializationRound(entityFetch(referenceContentWithAttributes("a", attributeContent("b", "c"), entityFetchAll(), entityGroupFetchAll())));
		assertSerializationRound(entityFetch(referenceContentWithAttributes("a", filterBy(attributeEquals("a", "b")))));
		assertSerializationRound(entityFetch(referenceContentWithAttributes("a", filterBy(attributeEquals("a", "b")), entityFetchAll())));
		assertSerializationRound(entityFetch(referenceContentWithAttributes("a", filterBy(attributeEquals("a", "b")), entityFetchAll(), page(1, 20))));
		assertSerializationRound(entityFetch(priceContentAll()));
		assertSerializationRound(entityFetch(priceContent(PriceContentMode.NONE)));
		assertSerializationRound(entityFetch(priceContent(PriceContentMode.RESPECTING_FILTER, "a", "b", "c")));
		assertSerializationRound(entityFetch(hierarchyContent()));
		assertSerializationRound(entityFetch(hierarchyContent(stopAt(distance(1)))));
		assertSerializationRound(entityFetch(hierarchyContent(stopAt(level(1)))));
		assertSerializationRound(entityFetch(hierarchyContent(stopAt(node(filterBy(attributeEquals("a", "b")))))));
		assertSerializationRound(entityFetch(hierarchyContent(entityFetchAll())));
		assertSerializationRound(entityFetch(hierarchyContent(stopAt(distance(1)), entityFetchAll())));
		assertSerializationRound(entityFetch(hierarchyContent(stopAt(level(1)), entityFetchAll())));
		assertSerializationRound(entityFetch(hierarchyContent(stopAt(node(filterBy(attributeEquals("a", "b")))), entityFetchAll())));
		assertSerializationRound(entityFetch(dataInLocalesAll()));
		assertSerializationRound(entityFetch(dataInLocales(Locale.ENGLISH, Locale.FRENCH)));
		assertSerializationRound(entityFetchAll());
		assertSerializationRound(entityGroupFetchAll());
		assertSerializationRound(entityGroupFetch(attributeContentAll(), priceContentAll()));
		assertSerializationRound(inScope(Scope.LIVE, facetSummary()));

		assertSerializationRound(facetSummary((FacetStatisticsDepth) null));
		assertSerializationRound(facetSummary(FacetStatisticsDepth.IMPACT));
		assertSerializationRound(facetSummary(FacetStatisticsDepth.IMPACT, entityFetchAll()));
		assertSerializationRound(facetSummary(FacetStatisticsDepth.IMPACT, entityFetchAll(), entityGroupFetchAll()));
		assertSerializationRound(facetSummary(null, filterBy(attributeEquals("a", "b"))));
		assertSerializationRound(facetSummary(null, filterBy(attributeEquals("a", "b")), filterGroupBy(attributeEquals("c", "d"))));
		assertSerializationRound(facetSummary(null, filterBy(attributeEquals("a", "b")), filterGroupBy(attributeEquals("c", "d")), entityFetchAll()));
		assertSerializationRound(facetSummary(null, filterBy(attributeEquals("a", "b")), entityFetchAll()));
		assertSerializationRound(facetSummary(null, filterBy(attributeEquals("a", "b")), entityFetchAll(), entityGroupFetchAll()));
		assertSerializationRound(facetSummary(null, filterBy(attributeEquals("a", "b")), entityFetchAll(), entityGroupFetchAll()));
		assertSerializationRound(facetSummary(null, filterBy(attributeEquals("a", "b")), orderBy(random())));
		assertSerializationRound(facetSummary(null, filterBy(attributeEquals("a", "b")), orderBy(random()), entityFetchAll()));
		assertSerializationRound(facetSummary(null, filterBy(attributeEquals("a", "b")), orderBy(random()), entityFetchAll(), entityGroupFetchAll()));
		assertSerializationRound(facetSummary(null, filterBy(attributeEquals("a", "b")), orderBy(random()), entityFetchAll(), entityGroupFetchAll()));
		assertSerializationRound(facetSummary(null, filterBy(attributeEquals("a", "b")), filterGroupBy(attributeEquals("d", "e"))));
		assertSerializationRound(facetSummary(null, filterBy(attributeEquals("a", "b")), filterGroupBy(attributeEquals("d", "e")), entityFetchAll(), entityGroupFetchAll()));
		assertSerializationRound(facetSummary(null, filterBy(attributeEquals("a", "b")), filterGroupBy(attributeEquals("d", "e")), orderBy(random()), entityFetchAll(), entityGroupFetchAll()));
		assertSerializationRound(facetSummary(null, filterBy(attributeEquals("a", "b")), filterGroupBy(attributeEquals("d", "e")), orderBy(random()), orderGroupBy(attributeNatural("d", OrderDirection.DESC)), entityFetchAll(), entityGroupFetchAll()));
		assertSerializationRound(facetSummary((FacetStatisticsDepth) null, orderGroupBy(attributeNatural("a", OrderDirection.DESC))));
		assertSerializationRound(facetSummary((FacetStatisticsDepth) null, orderGroupBy(attributeNatural("a", OrderDirection.DESC)), entityFetchAll()));
		assertSerializationRound(facetSummary((FacetStatisticsDepth) null, orderBy(random()), orderGroupBy(attributeNatural("a", OrderDirection.DESC))));
		assertSerializationRound(facetSummary((FacetStatisticsDepth) null, orderBy(random()), orderGroupBy(attributeNatural("a", OrderDirection.DESC)), entityFetchAll()));

		assertSerializationRound(facetSummaryOfReference("a"));
		assertSerializationRound(facetSummaryOfReference("a", FacetStatisticsDepth.IMPACT));
		assertSerializationRound(facetSummaryOfReference("a", FacetStatisticsDepth.IMPACT, entityFetchAll()));
		assertSerializationRound(facetSummaryOfReference("a", FacetStatisticsDepth.IMPACT, entityFetchAll(), entityGroupFetchAll()));
		assertSerializationRound(facetSummaryOfReference("a", null, filterBy(attributeEquals("a", "b"))));
		assertSerializationRound(facetSummaryOfReference("a", null, filterBy(attributeEquals("a", "b")), filterGroupBy(attributeEquals("c", "d"))));
		assertSerializationRound(facetSummaryOfReference("a", null, filterBy(attributeEquals("a", "b")), filterGroupBy(attributeEquals("c", "d")), entityFetchAll()));
		assertSerializationRound(facetSummaryOfReference("a", null, filterBy(attributeEquals("a", "b")), entityFetchAll()));
		assertSerializationRound(facetSummaryOfReference("a", null, filterBy(attributeEquals("a", "b")), entityFetchAll(), entityGroupFetchAll()));
		assertSerializationRound(facetSummaryOfReference("a", null, filterBy(attributeEquals("a", "b")), entityFetchAll(), entityGroupFetchAll()));
		assertSerializationRound(facetSummaryOfReference("a", null, filterBy(attributeEquals("a", "b")), orderBy(random())));
		assertSerializationRound(facetSummaryOfReference("a", null, filterBy(attributeEquals("a", "b")), orderBy(random()), entityFetchAll()));
		assertSerializationRound(facetSummaryOfReference("a", null, filterBy(attributeEquals("a", "b")), orderBy(random()), entityFetchAll(), entityGroupFetchAll()));
		assertSerializationRound(facetSummaryOfReference("a", null, filterBy(attributeEquals("a", "b")), orderBy(random()), entityFetchAll(), entityGroupFetchAll()));
		assertSerializationRound(facetSummaryOfReference("a", null, filterBy(attributeEquals("a", "b")), filterGroupBy(attributeEquals("d", "e"))));
		assertSerializationRound(facetSummaryOfReference("a", null, filterBy(attributeEquals("a", "b")), filterGroupBy(attributeEquals("d", "e")), entityFetchAll(), entityGroupFetchAll()));
		assertSerializationRound(facetSummaryOfReference("a", null, filterBy(attributeEquals("a", "b")), filterGroupBy(attributeEquals("d", "e")), orderBy(random()), entityFetchAll(), entityGroupFetchAll()));
		assertSerializationRound(facetSummaryOfReference("a", null, filterBy(attributeEquals("a", "b")), filterGroupBy(attributeEquals("d", "e")), orderBy(random()), orderGroupBy(attributeNatural("d", OrderDirection.DESC)), entityFetchAll(), entityGroupFetchAll()));
		assertSerializationRound(facetSummaryOfReference("a", orderGroupBy(attributeNatural("a", OrderDirection.DESC))));
		assertSerializationRound(facetSummaryOfReference("a", orderGroupBy(attributeNatural("a", OrderDirection.DESC)), entityFetchAll()));
		assertSerializationRound(facetSummaryOfReference("a", orderBy(random()), orderGroupBy(attributeNatural("a", OrderDirection.DESC))));
		assertSerializationRound(facetSummaryOfReference("a", orderBy(random()), orderGroupBy(attributeNatural("a", OrderDirection.DESC)), entityFetchAll()));

		assertSerializationRound(priceType(QueryPriceMode.WITHOUT_TAX));

		assertSerializationRound(defaultAccompanyingPriceLists("a"));
		assertSerializationRound(defaultAccompanyingPriceLists("a", "b"));

		assertSerializationRound(accompanyingPriceContentDefault());
		assertSerializationRound(accompanyingPriceContent("a"));
		assertSerializationRound(accompanyingPriceContent("a", "b"));

		assertSerializationRound(priceHistogram(20));
		assertSerializationRound(priceHistogram(20, HistogramBehavior.OPTIMIZED));

		assertSerializationRound(attributeHistogram(20, "a", "b"));
		assertSerializationRound(attributeHistogram(20, HistogramBehavior.OPTIMIZED, "a", "b"));

		assertSerializationRound(hierarchyOfSelf(fromRoot("a")));
		assertSerializationRound(hierarchyOfSelf(fromRoot("a", stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfSelf(fromRoot("a", stopAt(distance(1)), statistics(StatisticsBase.COMPLETE_FILTER, StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfSelf(fromRoot("a", entityFetchAll(), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT))));
		assertSerializationRound(hierarchyOfSelf(fromNode("b", node(filterBy(attributeEquals("a", "b"))))));
		assertSerializationRound(hierarchyOfSelf(fromNode("b", node(filterBy(attributeEquals("a", "b"))), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfSelf(fromNode("b", node(filterBy(attributeEquals("a", "b"))), entityFetchAll(), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfSelf(children("c")));
		assertSerializationRound(hierarchyOfSelf(children("c", stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfSelf(children("c", entityFetchAll(), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfSelf(parents("d")));
		assertSerializationRound(hierarchyOfSelf(parents("d", stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfSelf(parents("d", siblings(stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT)), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfSelf(parents("d", siblings(entityFetchAll(), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT)), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfSelf(parents("d", entityFetchAll(), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfSelf(parents("d", entityFetchAll(), siblings(stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT)), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfSelf(siblings("e")));
		assertSerializationRound(hierarchyOfSelf(siblings("e", stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfSelf(siblings("e", entityFetchAll(), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));

		assertSerializationRound(hierarchyOfReference("a", fromRoot("a")));
		assertSerializationRound(hierarchyOfReference("a", fromRoot("a", stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfReference("a", fromRoot("a", stopAt(distance(1)), statistics(StatisticsBase.COMPLETE_FILTER, StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfReference("a", fromRoot("a", entityFetchAll(), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT))));
		assertSerializationRound(hierarchyOfReference("a", fromNode("b", node(filterBy(attributeEquals("a", "b"))))));
		assertSerializationRound(hierarchyOfReference("a", fromNode("b", node(filterBy(attributeEquals("a", "b"))), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfReference("a", fromNode("b", node(filterBy(attributeEquals("a", "b"))), entityFetchAll(), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfReference("a", children("c")));
		assertSerializationRound(hierarchyOfReference("a", children("c", stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfReference("a", children("c", entityFetchAll(), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfReference("a", parents("d")));
		assertSerializationRound(hierarchyOfReference("a", parents("d", stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfReference("a", parents("d", siblings(stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT)), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfReference("a", parents("d", siblings(entityFetchAll(), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT)), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfReference("a", parents("d", entityFetchAll(), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfReference("a", parents("d", entityFetchAll(), siblings(stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT)), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfReference("a", siblings("e")));
		assertSerializationRound(hierarchyOfReference("a", siblings("e", stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfReference("a", siblings("e", entityFetchAll(), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));

		assertSerializationRound(hierarchyOfReference(new String[] {"a", "b"}, fromRoot("a")));
		assertSerializationRound(hierarchyOfReference(new String[] {"a", "b"}, fromRoot("a", stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfReference(new String[] {"a", "b"}, fromRoot("a", stopAt(distance(1)), statistics(StatisticsBase.COMPLETE_FILTER, StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfReference(new String[] {"a", "b"}, fromRoot("a", entityFetchAll(), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT, StatisticsType.CHILDREN_COUNT))));
		assertSerializationRound(hierarchyOfReference(new String[] {"a", "b"}, fromNode("b", node(filterBy(attributeEquals("a", "b"))))));
		assertSerializationRound(hierarchyOfReference(new String[] {"a", "b"}, fromNode("b", node(filterBy(attributeEquals("a", "b"))), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfReference(new String[] {"a", "b"}, fromNode("b", node(filterBy(attributeEquals("a", "b"))), entityFetchAll(), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfReference(new String[] {"a", "b"}, children("c")));
		assertSerializationRound(hierarchyOfReference(new String[] {"a", "b"}, children("c", stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfReference(new String[] {"a", "b"}, children("c", entityFetchAll(), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfReference(new String[] {"a", "b"}, parents("d")));
		assertSerializationRound(hierarchyOfReference(new String[] {"a", "b"}, parents("d", stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfReference(new String[] {"a", "b"}, parents("d", siblings(stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT)), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfReference(new String[] {"a", "b"}, parents("d", siblings(entityFetchAll(), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT)), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfReference(new String[] {"a", "b"}, parents("d", entityFetchAll(), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfReference(new String[] {"a", "b"}, parents("d", entityFetchAll(), siblings(stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT)), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfReference(new String[] {"a", "b"}, siblings("e")));
		assertSerializationRound(hierarchyOfReference(new String[] {"a", "b"}, siblings("e", stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
		assertSerializationRound(hierarchyOfReference(new String[] {"a", "b"}, siblings("e", entityFetchAll(), stopAt(distance(1)), statistics(StatisticsType.QUERIED_ENTITY_COUNT))));
	}

	private void assertSerializationRound(@Nonnull Object object) {
		final ByteArrayOutputStream os = new ByteArrayOutputStream(4_096);
		try (final Output output = new Output(os, 4_096)) {
			this.kryo.writeObject(output, object);
		}
		try (final Input input = new Input(os.toByteArray())) {
			final Object deserialized = this.kryo.readObject(input, object.getClass());
			assertEquals(object, deserialized);
		}
	}

}
