/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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
import com.esotericsoftware.kryo.serializers.DefaultSerializers.EnumSerializer;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.filter.*;
import io.evitadb.api.query.head.Collection;
import io.evitadb.api.query.order.*;
import io.evitadb.api.query.require.*;
import io.evitadb.store.query.serializer.CollectionSerializer;
import io.evitadb.store.query.serializer.QuerySerializer;
import io.evitadb.store.query.serializer.filter.*;
import io.evitadb.store.query.serializer.orderBy.*;
import io.evitadb.store.query.serializer.require.*;
import io.evitadb.utils.Assert;

import java.util.function.Consumer;

/**
 * This {@link Consumer} implementation takes default Kryo instance and registers additional serializers that are
 * required to (de)serialize {@link io.evitadb.api.query.Query}.
 */
public class QuerySerializationKryoConfigurer implements Consumer<Kryo> {
	private static final int QUERY_BASE = 1000;
	public static final QuerySerializationKryoConfigurer INSTANCE = new QuerySerializationKryoConfigurer();

	@Override
	public void accept(Kryo kryo) {
		int index = QUERY_BASE;
		kryo.register(Query.class, new QuerySerializer(), index++);

		kryo.register(AttributeSpecialValue.class, new EnumSerializer(AttributeSpecialValue.class), index++);
		kryo.register(DebugMode.class, new EnumSerializer(DebugMode.class), index++);
		kryo.register(EmptyHierarchicalEntityBehaviour.class, new EnumSerializer(EmptyHierarchicalEntityBehaviour.class), index++);
		kryo.register(FacetStatisticsDepth.class, new EnumSerializer(FacetStatisticsDepth.class), index++);
		kryo.register(HistogramBehavior.class, new EnumSerializer(HistogramBehavior.class), index++);
		kryo.register(OrderDirection.class, new EnumSerializer(OrderDirection.class), index++);
		kryo.register(PriceContentMode.class, new EnumSerializer(PriceContentMode.class), index++);
		kryo.register(QueryPriceMode.class, new EnumSerializer(QueryPriceMode.class), index++);
		kryo.register(StatisticsBase.class, new EnumSerializer(StatisticsBase.class), index++);
		kryo.register(StatisticsType.class, new EnumSerializer(StatisticsType.class), index++);

		kryo.register(Collection.class, new CollectionSerializer(), index++);

		kryo.register(And.class, new AndSerializer(), index++);
		kryo.register(AttributeBetween.class, new AttributeBetweenSerializer<>(), index++);
		kryo.register(AttributeContains.class, new AttributeContainsSerializer(), index++);
		kryo.register(HierarchyDirectRelation.class, new HierarchyDirectRelationSerializer(), index++);
		kryo.register(AttributeEndsWith.class, new AttributeEndsWithSerializer(), index++);
		kryo.register(AttributeEquals.class, new AttributeEqualsSerializer<>(), index++);
		kryo.register(HierarchyHaving.class, new HierarchyHavingSerializer(), index++);
		kryo.register(HierarchyExcluding.class, new HierarchyExcludingSerializer(), index++);
		kryo.register(HierarchyExcludingRoot.class, new HierarchyExcludingRootSerializer(), index++);
		kryo.register(FacetHaving.class, new FacetHavingSerializer(), index++);
		kryo.register(FilterBy.class, new FilterBySerializer(), index++);
		kryo.register(FilterGroupBy.class, new FilterGroupBySerializer(), index++);
		kryo.register(AttributeGreaterThan.class, new AttributeGreaterThanSerializer<>(), index++);
		kryo.register(AttributeGreaterThanEquals.class, new AttributeGreaterThanEqualsSerializer<>(), index++);
		kryo.register(AttributeInRange.class, new AttributeInRangeSerializer(), index++);
		kryo.register(AttributeInSet.class, new AttributeInSetSerializer<>(), index++);
		kryo.register(AttributeIs.class, new AttributeIsSerializer(), index++);
		kryo.register(EntityLocaleEquals.class, new EntityLocaleEqualsSerializer(), index++);
		kryo.register(EntityHaving.class, new EntityHavingSerializer(), index++);
		kryo.register(AttributeLessThan.class, new AttributeLessThanSerializer<>(), index++);
		kryo.register(AttributeLessThanEquals.class, new AttributeLessThanEqualsSerializer<>(), index++);
		kryo.register(Not.class, new NotSerializer(), index++);
		kryo.register(Or.class, new OrSerializer(), index++);
		kryo.register(PriceBetween.class, new PriceBetweenSerializer(), index++);
		kryo.register(PriceInCurrency.class, new PriceInCurrencySerializer(), index++);
		kryo.register(PriceInPriceLists.class, new PriceInPriceListsSerializer(), index++);
		kryo.register(PriceValidIn.class, new PriceValidInSerializer(), index++);
		kryo.register(EntityPrimaryKeyInSet.class, new EntityPrimaryKeyInSetSerializer(), index++);
		kryo.register(ReferenceHaving.class, new ReferenceHavingSerializer(), index++);
		kryo.register(AttributeStartsWith.class, new AttributeStartsWithSerializer(), index++);
		kryo.register(UserFilter.class, new UserFilterSerializer(), index++);
		kryo.register(HierarchyWithin.class, new HierarchyWithinSerializer(), index++);
		kryo.register(HierarchyWithinRoot.class, new HierarchyWithinRootSerializer(), index++);

		kryo.register(AttributeNatural.class, new AttributeNaturalSerializer(), index++);
		kryo.register(AttributeSetInFilter.class, new AttributeSetInFilterSerializer(), index++);
		kryo.register(AttributeSetExact.class, new AttributeSetExactSerializer(), index++);
		kryo.register(EntityPrimaryKeyInFilter.class, new EntityPrimaryKeyInFilterSerializer(), index++);
		kryo.register(EntityPrimaryKeyExact.class, new EntityPrimaryKeyExactSerializer(), index++);
		kryo.register(EntityPrimaryKeyNatural.class, new EntityPrimaryKeyNaturalSerializer(), index++);
		kryo.register(OrderBy.class, new OrderBySerializer(), index++);
		kryo.register(OrderGroupBy.class, new io.evitadb.store.query.serializer.OrderGroupBy.OrderGroupBySerializer(), index++);
		kryo.register(PriceNatural.class, new PriceNaturalSerializer(), index++);
		kryo.register(PriceDiscount.class, new PriceDiscountSerializer(), index++);
		kryo.register(Random.class, new RandomSerializer(), index++);
		kryo.register(ReferenceProperty.class, new ReferencePropertySerializer(), index++);

		kryo.register(AssociatedDataContent.class, new AssociatedDataContentSerializer(), index++);
		kryo.register(AttributeHistogram.class, new AttributeHistogramSerializer(), index++);
		kryo.register(DataInLocales.class, new DataInLocalesSerializer(), index++);
		kryo.register(EntityFetch.class, new EntityFetchSerializer(), index++);
		kryo.register(EntityGroupFetch.class, new EntityGroupFetchSerializer(), index++);
		kryo.register(FacetGroupsConjunction.class, new FacetGroupsConjunctionSerializer(), index++);
		kryo.register(FacetGroupsDisjunction.class, new FacetGroupsDisjunctionSerializer(), index++);
		kryo.register(FacetGroupsNegation.class, new FacetGroupsNegationSerializer(), index++);
		kryo.register(FacetSummary.class, new FacetSummarySerializer(), index++);
		kryo.register(FacetSummaryOfReference.class, new FacetSummaryOfReferenceSerializer(), index++);
		kryo.register(HierarchyOfSelf.class, new HierarchyOfSelfSerializer(), index++);
		kryo.register(Page.class, new PageSerializer(), index++);
		kryo.register(HierarchyOfSelf.class, new HierarchyOfSelfSerializer(), index++);
		kryo.register(HierarchyOfReference.class, new HierarchyOfReferenceSerializer(), index++);
		kryo.register(HierarchyChildren.class, new HierarchyChildrenSerializer(), index++);
		kryo.register(HierarchyDistance.class, new HierarchyDistanceSerializer(), index++);
		kryo.register(HierarchyFromNode.class, new HierarchyFromNodeSerializer(), index++);
		kryo.register(HierarchyFromRoot.class, new HierarchyFromRootSerializer(), index++);
		kryo.register(HierarchyLevel.class, new HierarchyLevelSerializer(), index++);
		kryo.register(HierarchyNode.class, new HierarchyNodeSerializer(), index++);
		kryo.register(HierarchyParents.class, new HierarchyParentsSerializer(), index++);
		kryo.register(HierarchyStopAt.class, new HierarchyStopAtSerializer(), index++);
		kryo.register(HierarchySiblings.class, new HierarchySiblingsSerializer(), index++);
		kryo.register(HierarchyStopAt.class, new HierarchyStopAtSerializer(), index++);
		kryo.register(HierarchyStatistics.class, new HierarchyStatisticsSerializer(), index++);
		kryo.register(PriceHistogram.class, new PriceHistogramSerializer(), index++);
		kryo.register(PriceContent.class, new PriceContentSerializer(), index++);
		kryo.register(ReferenceContent.class, new ReferenceContentSerializer(), index++);
		kryo.register(HierarchyContent.class, new HierarchyContentSerializer(), index++);
		kryo.register(Require.class, new RequireSerializer(), index++);
		kryo.register(Strip.class, new StripSerializer(), index++);
		kryo.register(PriceType.class, new PriceTypeSerializer(), index++);
		kryo.register(AttributeContent.class, new AttributeContentSerializer(), index++);
		kryo.register(QueryTelemetry.class, new QueryTelemetrySerializer(), index++);
		kryo.register(Debug.class, new DebugSerializer(), index++);

		kryo.register(Segments.class, new SegmentsSerializer(), index++);
		kryo.register(Segment.class, new SegmentSerializer(), index++);
		kryo.register(SegmentLimit.class, new SegmentLimitSerializer(), index++);

		Assert.isPremiseValid(index < 1100, "Index count overflow.");
	}

}
