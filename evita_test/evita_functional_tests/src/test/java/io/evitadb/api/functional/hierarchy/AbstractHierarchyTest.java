/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.api.functional.hierarchy;

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntSet;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.test.Entities;
import io.evitadb.utils.Assert;
import one.edee.oss.pmptt.model.Hierarchy;
import one.edee.oss.pmptt.model.HierarchyItem;
import org.junit.jupiter.params.provider.Arguments;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.generator.DataGenerator.CZECH_LOCALE;
import static java.util.Optional.ofNullable;

/**
 * The class contains shared logic for integration tests verifying hierarchy filtering and computation logic.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
abstract class AbstractHierarchyTest {

	protected static Stream<Arguments> statisticTypeVariants() {
		return Stream.of(
			Arguments.of(EnumSet.noneOf(StatisticsType.class)),
			Arguments.of(EnumSet.allOf(StatisticsType.class)),
			Arguments.of(EnumSet.of(StatisticsType.QUERIED_ENTITY_COUNT)),
			Arguments.of(EnumSet.of(StatisticsType.CHILDREN_COUNT))
		);
	}

	protected static Stream<Arguments> statisticTypeAndBaseVariants() {
		return Stream.of(
			Arguments.of(EnumSet.noneOf(StatisticsType.class), StatisticsBase.COMPLETE_FILTER),
			Arguments.of(EnumSet.noneOf(StatisticsType.class), StatisticsBase.WITHOUT_USER_FILTER),
			Arguments.of(EnumSet.allOf(StatisticsType.class), StatisticsBase.COMPLETE_FILTER),
			Arguments.of(EnumSet.allOf(StatisticsType.class), StatisticsBase.WITHOUT_USER_FILTER),
			Arguments.of(EnumSet.of(StatisticsType.QUERIED_ENTITY_COUNT), StatisticsBase.COMPLETE_FILTER),
			Arguments.of(EnumSet.of(StatisticsType.QUERIED_ENTITY_COUNT), StatisticsBase.WITHOUT_USER_FILTER),
			Arguments.of(EnumSet.of(StatisticsType.CHILDREN_COUNT), StatisticsBase.COMPLETE_FILTER),
			Arguments.of(EnumSet.of(StatisticsType.CHILDREN_COUNT), StatisticsBase.WITHOUT_USER_FILTER)
		);
	}
	@Nonnull
	protected List<LevelInfo> computeChildren(
		@Nonnull EvitaSessionContract session,
		@Nullable Integer parentCategoryId,
		@Nonnull Hierarchy categoryHierarchy,
		@Nullable CardinalityProvider categoryCardinalities,
		boolean excludeParent,
		boolean computeChildrenCount,
		boolean computeQueuedEntityStatistics,
		int... selectedCategoryIds
	) {
		return computeChildren(
			session, parentCategoryId, categoryHierarchy, categoryCardinalities,
			excludeParent, computeChildrenCount, computeQueuedEntityStatistics, null, selectedCategoryIds
		);
	}

	@Nonnull
	protected static List<LevelInfo> computeChildren(
		@Nonnull EvitaSessionContract session,
		@Nullable Integer parentCategoryId,
		@Nonnull Hierarchy categoryHierarchy,
		@Nullable CardinalityProvider categoryCardinalities,
		boolean excludeParent,
		boolean computeChildrenCount,
		boolean computeQueuedEntityStatistics,
		@Nullable Comparator<SealedEntity> sorter,
		int... selectedCategoryIds
	) {
		final LinkedList<LevelInfo> levelInfo = new LinkedList<>();
		final List<HierarchyItem> items;
		if (parentCategoryId == null) {
			items = categoryHierarchy.getRootItems();
		} else if (excludeParent) {
			items = categoryHierarchy.getChildItems(String.valueOf(parentCategoryId));
		} else {
			items = Collections.singletonList(categoryHierarchy.getItem(String.valueOf(parentCategoryId)));
		}

		final IntSet selectedCategoryIdsSet = new IntHashSet(selectedCategoryIds.length);
		Arrays.stream(selectedCategoryIds).forEach(selectedCategoryIdsSet::add);
		for (HierarchyItem rootItem : items) {
			final int categoryId = Integer.parseInt(rootItem.getCode());
			if (categoryCardinalities == null || categoryCardinalities.isValid(categoryId)) {
				final List<LevelInfo> childrenStatistics = fetchLevelInfo(
					session, categoryId, categoryHierarchy, categoryCardinalities,
					computeChildrenCount, computeQueuedEntityStatistics, sorter, selectedCategoryIds
				);
				levelInfo.add(
					new LevelInfo(
						fetchHierarchyStatisticsEntity(session, categoryId),
						selectedCategoryIdsSet.contains(categoryId),
						computeQueuedEntityStatistics ? ofNullable(categoryCardinalities).map(it -> it.getCardinality(categoryId)).orElse(null) : null,
						computeChildrenCount ? ofNullable(categoryCardinalities).map(it -> it.getChildrenCount(categoryId)).orElse(null) : null,
						childrenStatistics
					)
				);
			}
		}
		ofNullable(sorter)
			.ifPresent(it -> levelInfo.sort((o1, o2) -> sorter.compare((SealedEntity) o1.entity(), (SealedEntity) o2.entity())));
		return levelInfo;
	}

	@Nonnull
	protected List<LevelInfo> computeSiblings(
		@Nonnull EvitaSessionContract session,
		@Nullable Integer categoryId,
		@Nonnull Hierarchy categoryHierarchy,
		@Nullable CardinalityProvider categoryCardinalities,
		boolean computeChildrenCount,
		boolean computeQueriedEntities,
		int... selectedCategoryIds
	) {
		final LinkedList<LevelInfo> levelInfo = new LinkedList<>();
		final HierarchyItem parent;
		if (categoryId == null) {
			parent = null;
		} else {
			final HierarchyItem theNode = categoryHierarchy.getItem(String.valueOf(categoryId));
			Assert.notNull(theNode, "Node with id `" + categoryId + "` is not present!");
			parent = categoryHierarchy.getParentItem(String.valueOf(categoryId));
		}
		final List<HierarchyItem> items = parent == null ?
			categoryHierarchy.getRootItems() : categoryHierarchy.getChildItems(parent.getCode());

		final IntSet selectedCategoryIdsSet = new IntHashSet(selectedCategoryIds.length);
		Arrays.stream(selectedCategoryIds).forEach(selectedCategoryIdsSet::add);
		for (HierarchyItem rootItem : items) {
			final int cid = Integer.parseInt(rootItem.getCode());
			if (categoryCardinalities == null || categoryCardinalities.isValid(cid)) {
				final List<LevelInfo> childrenStatistics = fetchLevelInfo(
					session, cid, categoryHierarchy, categoryCardinalities, computeChildrenCount, computeQueriedEntities, selectedCategoryIds
				);
				levelInfo.add(
					new LevelInfo(
						fetchHierarchyStatisticsEntity(session, cid),
						selectedCategoryIdsSet.contains(cid),
						computeQueriedEntities ? ofNullable(categoryCardinalities).map(it -> it.getCardinality(cid)).orElse(null) : null,
						computeChildrenCount ? ofNullable(categoryCardinalities).map(it -> it.getChildrenCount(cid)).orElse(null) : null,
						childrenStatistics
					)
				);
			}
		}
		return levelInfo;
	}

	@Nonnull
	protected List<LevelInfo> computeParents(
		@Nonnull EvitaSessionContract session,
		int categoryId,
		@Nonnull Hierarchy categoryHierarchy,
		@Nullable CardinalityProvider categoryCardinalities,
		@Nullable Predicate<SealedEntity> siblingsPredicate,
		boolean computeChildrenCount,
		boolean computeQueriedEntities,
		int... selectedCategoryIds
	) {
		final HierarchyItem theNode = categoryHierarchy.getItem(String.valueOf(categoryId));
		Assert.notNull(theNode, "Node with id `" + categoryId + "` is not present!");
		final List<HierarchyItem> parentItems = categoryHierarchy.getParentItems(String.valueOf(categoryId));
		parentItems.add(theNode);

		final IntSet selectedCategoryIdsSet = new IntHashSet(selectedCategoryIds.length);
		Arrays.stream(selectedCategoryIds).forEach(selectedCategoryIdsSet::add);

		List<LevelInfo> nextLevel = new LinkedList<>();
		for (int i = parentItems.size() - 1; i >= 0; i--) {
			final HierarchyItem parentItem = parentItems.get(i);
			final int cid = Integer.parseInt(parentItem.getCode());
			final LevelInfo currentNodeInfo = new LevelInfo(
				fetchHierarchyStatisticsEntity(session, cid),
				selectedCategoryIdsSet.contains(cid),
				computeQueriedEntities ? ofNullable(categoryCardinalities).map(it -> it.getCardinality(cid)).orElse(null) : null,
				computeChildrenCount ? ofNullable(categoryCardinalities).map(it -> it.getChildrenCount(cid)).orElse(null) : null,
				nextLevel
			);
			if (siblingsPredicate == null) {
				nextLevel = List.of(currentNodeInfo);
			} else {
				final List<HierarchyItem> siblings = i == 0 ?
					categoryHierarchy.getRootItems() :
					categoryHierarchy.getChildItems(parentItems.get(i - 1).getCode());

				nextLevel = siblings
					.stream()
					.map(sibling -> {
						if (Objects.equals(sibling.getCode(), parentItem.getCode())) {
							return currentNodeInfo;
						} else {
							final int siblingCid = Integer.parseInt(sibling.getCode());
							final SealedEntity siblingEntity = fetchHierarchyStatisticsEntity(session, siblingCid);
							if (siblingsPredicate.test(siblingEntity)) {
								return new LevelInfo(
									siblingEntity,
									selectedCategoryIdsSet.contains(siblingCid),
									computeQueriedEntities ? ofNullable(categoryCardinalities).map(it -> it.getCardinality(siblingCid)).orElse(null) : null,
									computeChildrenCount ? ofNullable(categoryCardinalities).map(it -> it.getChildrenCount(siblingCid)).orElse(null) : null,
									fetchLevelInfo(session, siblingCid, categoryHierarchy, categoryCardinalities, computeChildrenCount, computeQueriedEntities, selectedCategoryIds)
								);
							} else {
								return null;
							}
						}
					})
					.filter(Objects::nonNull)
					.collect(Collectors.toList());
			}
		}
		return nextLevel;
	}

	private static List<LevelInfo> fetchLevelInfo(
		@Nonnull EvitaSessionContract session,
		int parentCategoryId,
		@Nonnull Hierarchy categoryHierarchy,
		@Nullable CardinalityProvider categoryCardinalities,
		boolean computeChildrenCount,
		boolean computeQueriedEntity,
		int... selectedCategoryIds
	) {
		return fetchLevelInfo(
			session, parentCategoryId, categoryHierarchy, categoryCardinalities,
			computeChildrenCount, computeQueriedEntity, null, selectedCategoryIds
		);
	}

	private static List<LevelInfo> fetchLevelInfo(
		@Nonnull EvitaSessionContract session,
		int parentCategoryId,
		@Nonnull Hierarchy categoryHierarchy,
		@Nullable CardinalityProvider categoryCardinalities,
		boolean computeChildrenCount,
		boolean computeQueriedEntities,
		@Nullable Comparator<SealedEntity> sorter,
		int... selectedCategoryIds
	) {
		final IntSet selectedCategoryIdsSet = new IntHashSet(selectedCategoryIds.length);
		Arrays.stream(selectedCategoryIds).forEach(selectedCategoryIdsSet::add);

		final LinkedList<LevelInfo> levelInfo = new LinkedList<>();
		for (HierarchyItem item : categoryHierarchy.getChildItems(String.valueOf(parentCategoryId))) {
			final int categoryId = Integer.parseInt(item.getCode());
			if (categoryCardinalities == null || categoryCardinalities.isValid(categoryId)) {
				final List<LevelInfo> childrenStatistics = fetchLevelInfo(
					session, categoryId, categoryHierarchy, categoryCardinalities,
					computeChildrenCount, computeQueriedEntities, sorter
				);
				levelInfo.add(
					new LevelInfo(
						fetchHierarchyStatisticsEntity(session, categoryId),
						selectedCategoryIdsSet.contains(categoryId),
						computeQueriedEntities ? ofNullable(categoryCardinalities).map(it -> it.getCardinality(categoryId)).orElse(null) : null,
						computeChildrenCount ? ofNullable(categoryCardinalities).map(it -> it.getChildrenCount(categoryId)).orElse(null) : null,
						childrenStatistics
					)
				);
			}
		}
		ofNullable(sorter)
			.ifPresent(it -> levelInfo.sort((o1, o2) -> sorter.compare((SealedEntity) o1.entity(), (SealedEntity) o2.entity())));
		return levelInfo;
	}

	@Nonnull
	private static SealedEntity fetchHierarchyStatisticsEntity(@Nonnull EvitaSessionContract session, int categoryId) {
		return session.query(
			query(
				collection(Entities.CATEGORY),
				filterBy(
					and(
						entityLocaleEquals(CZECH_LOCALE),
						entityPrimaryKeyInSet(categoryId)
					)
				),
				require(entityFetch(attributeContentAll()))
			),
			SealedEntity.class
		).getRecordData().get(0);
	}

	protected interface CardinalityProvider {

		boolean isValid(int categoryId);

		int getCardinality(int categoryId);

		int getChildrenCount(int categoryId);

	}

	record HierarchyStatisticsTuple(
		String name,
		List<LevelInfo> levelInfos
	) {
	}

}
