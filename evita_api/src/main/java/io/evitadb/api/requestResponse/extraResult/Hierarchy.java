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

package io.evitadb.api.requestResponse.extraResult;

import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.query.filter.HierarchyWithinRoot;
import io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour;
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
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * This DTO contains hierarchical structure of entities either directly queried or referenced by the entities targeted
 * by the query. It copies hierarchical structure of those entities and contains their identification or full body as
 * well as information on cardinality of referencing entities.
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
 *             attributeEquals('visible', true),
 *             attributeInRange('valid', 2020-07-30T20:37:50+00:00),
 *             priceInCurrency('USD'),
 *             priceValidIn(2020-07-30T20:37:50+00:00),
 *             priceInPriceLists('vip', 'standard'),
 *             hierarchyWithinRoot('categories', excluding(entityPrimaryKeyInSet(3, 7)))
 *         )
 *     ),
 *     require(
 *         page(1, 20),
 *         hierarchyStatisticsOfReference('categories', entityFetch(attributeContentAll()))
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
 * after the arrow represents the count of the products that are referencing this category (either directly or some of
 * its children).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @see LevelInfo for the list of all available information for each hierarchical entity
 */
@RequiredArgsConstructor
@ThreadSafe
public class Hierarchy implements EvitaResponseExtraResult {
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
	private final Map<String, Map<String, List<LevelInfo>>> referenceHierarchies;

	/**
	 * Compares two lists of {@link LevelInfo} objects for equality.
	 */
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
	public Map<String, List<LevelInfo>> getSelfHierarchy() {
		return ofNullable(this.selfStatistics).orElse(Collections.emptyMap());
	}

	/**
	 * Method returns the cardinality statistics for the top most level of queried hierarchical entities.
	 * Level is either the root level if {@link HierarchyWithinRoot} query or no hierarchical filtering query
	 * was used at all. Or it's the level requested by {@link HierarchyWithin} query.
	 */
	@Nonnull
	public List<LevelInfo> getSelfHierarchy(@Nonnull String outputName) {
		return ofNullable(this.selfStatistics)
			.map(it -> it.get(outputName))
			.orElse(Collections.emptyList());
	}

	/**
	 * Method returns the cardinality statistics for the top most level of referenced hierarchical entities.
	 * Level is either the root level if {@link HierarchyWithinRoot} query or no hierarchical filtering query
	 * was used at all. Or it's the level requested by {@link HierarchyWithin} query.
	 */
	@Nonnull
	public List<LevelInfo> getReferenceHierarchy(@Nonnull String referenceName, @Nonnull String outputName) {
		return ofNullable(this.referenceHierarchies.get(referenceName))
			.map(it -> it.get(outputName))
			.orElse(Collections.emptyList());
	}

	/**
	 * Returns statistics for reference of specified name.
	 */
	@Nonnull
	public Map<String, List<LevelInfo>> getReferenceHierarchy(@Nonnull String referenceName) {
		return ofNullable(this.referenceHierarchies.get(referenceName)).orElse(Collections.emptyMap());
	}

	/**
	 * Returns statistics for all references.
	 */
	@Nonnull
	public Map<String, Map<String, List<LevelInfo>>> getReferenceHierarchies() {
		return this.referenceHierarchies;
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.selfStatistics, this.referenceHierarchies);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		final Hierarchy that = (Hierarchy) o;

		if (this.selfStatistics == null && that.selfStatistics != null && !that.selfStatistics.isEmpty()) {
			return false;
		} else if (this.selfStatistics != null && !this.selfStatistics.isEmpty() && that.selfStatistics == null) {
			return false;
		}

		if (this.selfStatistics != null) {
			for (Entry<String, List<LevelInfo>> entry : this.selfStatistics.entrySet()) {
				final List<LevelInfo> stats = entry.getValue();
				final List<LevelInfo> otherStats = that.selfStatistics.get(entry.getKey());

				if (otherStats == null || stats.size() != otherStats.size()) {
					return false;
				}

				if (notEquals(stats, otherStats)) {
					return false;
				}
			}
		}

		for (Entry<String, Map<String, List<LevelInfo>>> statisticsEntry : this.referenceHierarchies.entrySet()) {
			final Map<String, List<LevelInfo>> stats = statisticsEntry.getValue();
			final Map<String, List<LevelInfo>> otherStats = that.referenceHierarchies.get(statisticsEntry.getKey());

			if (stats.size() != ofNullable(otherStats).map(Map::size).orElse(0)) {
				return false;
			}

			for (Entry<String, List<LevelInfo>> entry : stats.entrySet()) {
				final List<LevelInfo> innerStats = entry.getValue();
				final List<LevelInfo> innerOtherStats = otherStats.get(entry.getKey());

				if (innerOtherStats == null || innerStats.size() != innerOtherStats.size()) {
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
		final StringBuilder treeBuilder = new StringBuilder(128);

		if (this.selfStatistics != null) {
			for (Map.Entry<String, List<LevelInfo>> statsByOutputName : this.selfStatistics.entrySet()) {
				treeBuilder.append(statsByOutputName.getKey()).append(System.lineSeparator());

				for (LevelInfo levelInfo : statsByOutputName.getValue()) {
					appendLevelInfoTreeString(treeBuilder, levelInfo, 1);
				}
			}
		}

		for (Entry<String, Map<String, List<LevelInfo>>> statisticsEntry : this.referenceHierarchies.entrySet()) {
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
	private static void appendLevelInfoTreeString(@Nonnull StringBuilder treeBuilder, @Nonnull LevelInfo levelInfo, int currentLevel) {
		treeBuilder.append("    ".repeat(currentLevel))
			.append(levelInfo)
			.append(System.lineSeparator());

		for (LevelInfo child : levelInfo.children()) {
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
	 * @param requested          true in case the entity was filtered by {@link HierarchyWithin}
	 * @param queriedEntityCount Contains the number of queried entities that refer directly to this {@link #entity} or to any of its children
	 *                           entities.
	 * @param childrenCount      Contains number of hierarchical entities that are referring to this {@link #entity} as its parent.
	 *                           The count will respect {@link EmptyHierarchicalEntityBehaviour} settings and will not
	 *                           count empty children in case {@link EmptyHierarchicalEntityBehaviour#REMOVE_EMPTY} is
	 *                           used for computation.
	 * @param children           Contains hierarchy info of the entities that are subordinate (children) of this {@link #entity}.
	 */

	public record LevelInfo(
		@Nonnull EntityClassifier entity,
		boolean requested,
		@Nullable Integer queriedEntityCount,
		@Nullable Integer childrenCount,
		@Nonnull List<LevelInfo> children
	) {

		public LevelInfo(@Nonnull LevelInfo levelInfo, @Nonnull List<LevelInfo> children) {
			this(levelInfo.entity, levelInfo.requested, levelInfo.queriedEntityCount, levelInfo.childrenCount, children);
		}

		/**
		 * Returns the list of self and all children of this level info that match the passed predicate.
		 *
		 * @param predicate predicate to match
		 * @return stream of all children that match the predicate including self
		 */
		@Nonnull
		public Stream<LevelInfo> collectAll(@Nonnull Predicate<LevelInfo> predicate) {
			return Stream.concat(
				Stream.of(this).filter(predicate),
				this.children.stream().flatMap(it -> it.collectAll(predicate))
			);
		}

		@Nonnull
		@Override
		public String toString() {
			if (this.queriedEntityCount == null && this.childrenCount == null) {
				return this.entity + (this.requested ? " (requested)" : "");
			} else {
				return "[" + this.queriedEntityCount + ":" + this.childrenCount + "] " + this.entity + (this.requested ? " (requested)" : "");
			}
		}

	}

}
