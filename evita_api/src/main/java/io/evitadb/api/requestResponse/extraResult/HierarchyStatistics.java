/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse.extraResult;

import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.query.filter.HierarchyWithinRoot;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.HierarchyOfSelf;
import io.evitadb.api.requestResponse.EvitaResponseExtraResult;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.SealedEntity;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import static java.util.Optional.ofNullable;

/**
 * TODO JNO - replace javadoc
 *
 * This DTO contains hierarchical structure of entities referenced by the entities required by the query. It copies
 * hierarchical structure of those entities and contains their identification or full body as well as information on
 * cardinality of referencing entities.
 *
 * For example when we need to render menu for entire e-commerce site, but we want to take excluded subtrees into
 * an account and also reflect the filtering conditions that may filter out dozens of products (and thus leading to
 * empty categories) we can invoke following query:
 *
 * <pre>
 * query(
 *     entities('PRODUCT'),
 *     filterBy(
 *         and(
 *             eq('visible', true),
 *             inRange('valid', 2020-07-30T20:37:50+00:00),
 *             priceInCurrency('USD'),
 *             priceValidIn(2020-07-30T20:37:50+00:00),
 *             priceInPriceLists('vip', 'standard'),
 *             withinRootHierarchy('CATEGORY', excluding(3, 7))
 *         )
 *     ),
 *     require(
 *         page(1, 20),
 *         hierarchyStatisticsOfReference('CATEGORY', entityBody(), attributes())
 *     )
 * )
 * </pre>
 *
 * This query would return first page with 20 products (omitting hundreds of others on additional pages) but also
 * returns a HierarchyStatistics in additional data. Statistics respect hierarchical constraints specified in the filter
 * of the query. In our example sub-trees with ids 3 and 7 will be omitted from the statistics.
 *
 * This object may contain following structure:
 *
 * <pre>
 * Electronics -> 1789
 *     TV -> 126
 *         LED -> 90
 *         CRT -> 36
 *     Washing machines -> 190
 *         Slim -> 40
 *         Standard -> 40
 *         With drier -> 23
 *         Top filling -> 42
 *         Smart -> 45
 *     Cell phones -> 350
 *     Audio / Video -> 230
 *     Printers -> 80
 * </pre>
 *
 * The tree will contain category entities loaded with `attributes` instead the names you see in the example. The number
 * after the arrow represents the count of the products that are referencing this category (either directly or some of its
 * children). You can see there are only categories that are valid for the passed query - excluded category subtree will
 * not be part of the category listing (query filters out all products with excluded category tree) and there is also no
 * category that happens to be empty (e.g. contains no products or only products that don't match the filter query).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
@ThreadSafe
public class HierarchyStatistics implements EvitaResponseExtraResult {
	@Serial private static final long serialVersionUID = -5337743162562869243L;

	/**
	 * Contains list of statistics for the single level (probably root or whatever is filtered by the query) of
	 * the queried hierarchy entity.
	 */
	private final Map<String, List<LevelInfo>> selfStatistics;
	/**
	 * Index holds the statistics for particular references that target hierarchy entity types.
	 * Key is the identification of the reference name, value contains list of statistics for the single level (probably
	 * root or whatever is filtered by the query) of the hierarchy entity.
	 */
	private final Map<String, Map<String, List<LevelInfo>>> statistics;

	private static boolean notEquals(@Nonnull List<LevelInfo> stats, @Nonnull List<LevelInfo> otherStats) {
		for (int i = 0; i < stats.size(); i++) {
			final LevelInfo levelInfo = stats.get(i);
			final LevelInfo otherLevelInfo = otherStats.get(i);

			if (!Objects.equals(levelInfo, otherLevelInfo)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Method returns the cardinality statistics for the top most level of queried hierarchical entities.
	 * Level is either the root level if {@link HierarchyWithinRoot} query or no hierarchical filtering query
	 * was used at all. Or it's the level requested by {@link HierarchyWithin} query.
	 */
	@Nonnull
	public Map<String, List<LevelInfo>> getSelfStatistics() {
		return ofNullable(selfStatistics).orElse(Collections.emptyMap());
	}

	/**
	 * Method returns the cardinality statistics for the top most level of referenced hierarchical entities.
	 * Level is either the root level if {@link HierarchyWithinRoot} query or no hierarchical filtering query
	 * was used at all. Or it's the level requested by {@link HierarchyWithin} query.
	 */
	@Nonnull
	public List<LevelInfo> getStatistics(@Nonnull String referenceName, @Nonnull String outputName) {
		return ofNullable(statistics.get(referenceName))
			.map(it -> it.get(outputName))
			.orElse(Collections.emptyList());
	}

	/**
	 * Returns statistics for reference of specified name.
	 */
	@Nonnull
	public Map<String, List<LevelInfo>> getStatistics(@Nonnull String referenceName) {
		return ofNullable(statistics.get(referenceName)).orElse(Collections.emptyMap());
	}

	/**
	 * Returns statistics for all references.
	 */
	@Nonnull
	public Map<String, Map<String, List<LevelInfo>>> getStatistics() {
		return statistics;
	}

	@Override
	public int hashCode() {
		return Objects.hash(selfStatistics, statistics);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		final HierarchyStatistics that = (HierarchyStatistics) o;

		for (Entry<String, List<LevelInfo>> entry : selfStatistics.entrySet()) {
			final List<LevelInfo> stats = entry.getValue();
			final List<LevelInfo> otherStats = that.selfStatistics.get(entry.getKey());

			if (stats.size() != ofNullable(otherStats).map(List::size).orElse(0)) {
				return false;
			}

			if (notEquals(stats, otherStats)) {
				return false;
			}
		}

		for (Entry<String, Map<String, List<LevelInfo>>> statisticsEntry : statistics.entrySet()) {
			final Map<String, List<LevelInfo>> stats = statisticsEntry.getValue();
			final Map<String, List<LevelInfo>> otherStats = that.statistics.get(statisticsEntry.getKey());

			if (stats.size() != ofNullable(otherStats).map(Map::size).orElse(0)) {
				return false;
			}

			for (Entry<String, List<LevelInfo>> entry : stats.entrySet()) {
				final List<LevelInfo> innerStats = entry.getValue();
				final List<LevelInfo> innerOtherStats = otherStats.get(entry.getKey());

				if (innerStats.size() != ofNullable(innerOtherStats).map(List::size).orElse(0)) {
					return false;
				}

				if (notEquals(innerStats, innerOtherStats)) {
					return false;
				}

			}

		}

		return true;
	}

	@Override
	public String toString() {
		final StringBuilder treeBuilder = new StringBuilder();

		if (selfStatistics != null) {
			for (Map.Entry<String, List<LevelInfo>> statsByOutputName : selfStatistics.entrySet()) {
				treeBuilder.append(statsByOutputName.getKey()).append(System.lineSeparator());

				for (LevelInfo levelInfo : statsByOutputName.getValue()) {
					appendLevelInfoTreeString(treeBuilder, levelInfo, 1);
				}
			}
		}

		for (Entry<String, Map<String, List<LevelInfo>>> statisticsEntry : statistics.entrySet()) {
			treeBuilder.append(statisticsEntry.getKey()).append(System.lineSeparator());
			for (Map.Entry<String, List<LevelInfo>> statisticsByType : statisticsEntry.getValue().entrySet()) {
				treeBuilder.append("    ").append(statisticsByType.getKey()).append(System.lineSeparator());

				for (LevelInfo levelInfo : statisticsByType.getValue()) {
					appendLevelInfoTreeString(treeBuilder, levelInfo, 2);
				}
			}
		}

		return treeBuilder.toString();
	}

	/**
	 * Creates string representation of subtree of passed level info
	 *
	 * @param treeBuilder  string builder to which the string will be appended to
	 * @param levelInfo    level info to render
	 * @param currentLevel level on which passed level info is being placed
	 */
	private void appendLevelInfoTreeString(@Nonnull StringBuilder treeBuilder, @Nonnull LevelInfo levelInfo, int currentLevel) {
		treeBuilder.append("    ".repeat(currentLevel))
			.append(levelInfo)
			.append(System.lineSeparator());

		for (LevelInfo child : levelInfo.childrenStatistics()) {
			appendLevelInfoTreeString(treeBuilder, child, currentLevel + 1);
		}
	}

	/**
	 * This DTO represents single hierarchical entity in the statistics tree. It contains identification of the entity,
	 * the cardinality of queried entities that refer to it and information about children level.
	 *
	 * @param entity             Hierarchical entity identification - it may be {@link Integer} representing primary key of the entity if no
	 *                           {@link EntityContentRequire} requirements were passed within {@link HierarchyOfSelf}
	 *                           query, or it may be rich {@link SealedEntity} object if the richer requirements were specified.
	 * @param queriedEntityCount Contains the number of queried entities that refer directly to this {@link #entity} or to any of its children
	 *                           entities.
	 * @param childrenStatistics Contains statistics of the entities that are subordinate (children) of this {@link #entity}.
	 */

	public record LevelInfo(
		@Nonnull int order,
		@Nonnull EntityClassifier entity,
		@Nullable Integer queriedEntityCount,
		@Nullable Integer childrenCount,
		@Nonnull List<LevelInfo> childrenStatistics
	) implements Comparable<LevelInfo> {
		@Override
		public String toString() {
			if (queriedEntityCount == null && childrenCount == null) {
				return entity.toString();
			} else {
				return "[" + queriedEntityCount + ":" + childrenCount + "] " + entity;
			}
		}

		@Override
		public int compareTo(@Nonnull LevelInfo other) {
			return Integer.compare(order, other.order);
		}

	}

}
