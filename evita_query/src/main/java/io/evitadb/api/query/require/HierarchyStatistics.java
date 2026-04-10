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

package io.evitadb.api.query.require;

import io.evitadb.api.query.ConstraintWithDefaults;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.EnumSet;

/**
 * Requests statistical metadata for each hierarchy node returned by the enclosing traversal constraint
 * ({@link HierarchyFromRoot}, {@link HierarchyFromNode}, {@link HierarchyChildren}, {@link HierarchyParents},
 * or {@link HierarchySiblings}). Without this constraint, the hierarchy result contains only structural data
 * (node identity and fetched entity content); no counts are computed.
 *
 * **Statistics types (what to compute):**
 *
 * - {@link StatisticsType#CHILDREN_COUNT}: the count of child hierarchy nodes that exist below a given node,
 *   regardless of whether those children are themselves traversed or returned by the current constraint.
 *   Respects the {@link EmptyHierarchicalEntityBehaviour#REMOVE_EMPTY} setting: nodes that would otherwise be
 *   empty are not counted. Relatively inexpensive because dead branches can be pruned early.
 *
 * - {@link StatisticsType#QUERIED_ENTITY_COUNT}: the total number of entities that would appear in query results
 *   if `hierarchyWithin` were focused on this particular node (without `directRelation` or `excludingRoot`
 *   refinements). This requires counting all matching entities under each node and is significantly more expensive
 *   than `CHILDREN_COUNT`. Both types may be requested together.
 *
 * **Statistics base (which filter context to use):**
 *
 * - {@link StatisticsBase#WITHOUT_USER_FILTER} _(default)_: counts are computed against the filter excluding the
 *   contents of `userFilter`, making the counts stable as the user refines their facet selection. This is the
 *   expected behavior for faceted navigation where counts should show potential results, not just the current ones.
 *
 * - {@link StatisticsBase#COMPLETE_FILTER}: counts are computed against the complete `filterBy` constraint including
 *   any `userFilter` contents. Use this when you want counts that reflect the currently active filter exactly.
 *
 * - {@link StatisticsBase#COMPLETE_FILTER_EXCLUDING_SELF_IN_USER_FILTER}: similar to `COMPLETE_FILTER` but excludes
 *   `userFilter` constraints that filter on references of the same hierarchical entity type this constraint is
 *   applied to. Useful for hierarchical faceted navigation where the hierarchy constraint itself is one of the
 *   active user filters.
 *
 * **Important:** regardless of the `statisticsBase`, the `hierarchyWithin` pivot node is always ignored during
 * statistics calculation (the focused subtree is determined by the enclosing traversal requirement, not the filter).
 * The `having`, `anyHaving`, and `excluding` inner constraints of `hierarchyWithin` are, however, respected.
 *
 * **Performance warning:** `QUERIED_ENTITY_COUNT` for root-level nodes on large datasets is very expensive —
 * it requires aggregating across the entire indexed dataset. Even with in-memory indexes, this can be slow.
 * Restrict the traversal with {@link HierarchyStopAt} or cache the results whenever possible.
 *
 * **Example — statistics with default base (excluding user filter):**
 *
 * ```evitaql
 * require(
 *     hierarchyOfReference(
 *         "categories",
 *         children(
 *             "subcategories",
 *             stopAt(distance(1)),
 *             statistics(CHILDREN_COUNT, QUERIED_ENTITY_COUNT)
 *         )
 *     )
 * )
 * ```
 *
 * **Example — statistics based on the complete active filter:**
 *
 * ```evitaql
 * require(
 *     hierarchyOfReference(
 *         "categories",
 *         fromRoot(
 *             "megaMenu",
 *             statistics(COMPLETE_FILTER, CHILDREN_COUNT, QUERIED_ENTITY_COUNT)
 *         )
 *     )
 * )
 * ```
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/hierarchy#statistics)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@ConstraintDefinition(
	name = "statistics",
	shortDescription = "The constraint requests entity count statistics (queried and direct children counts) for each hierarchy node returned by the enclosing traversal.",
	userDocsLink = "/documentation/query/requirements/hierarchy#statistics",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchyStatistics extends AbstractRequireConstraintLeaf
	implements ConstraintWithDefaults<RequireConstraint>, HierarchyOutputRequireConstraint {
	@Serial private static final long serialVersionUID = 264601966496432983L;
	private static final String CONSTRAINT_NAME = "statistics";

	private HierarchyStatistics(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	public HierarchyStatistics() {
		super(CONSTRAINT_NAME, StatisticsBase.WITHOUT_USER_FILTER);
	}

	public HierarchyStatistics(
		@Nonnull StatisticsBase statisticsBase
	) {
		// because this query can be used only within some other hierarchy query, it would be
		// unnecessary to duplicate the hierarchy prefix
		super(
			CONSTRAINT_NAME,
			statisticsBase
		);
		Assert.isTrue(
			statisticsBase != null,
			"StatisticsBase is mandatory argument, yet it was not provided!"
		);
	}

	public HierarchyStatistics(
		@Nonnull StatisticsBase statisticsBase,
		@Nonnull StatisticsType... statisticsType
	) {
		// because this query can be used only within some other hierarchy query, it would be
		// unnecessary to duplicate the hierarchy prefix
		super(
			CONSTRAINT_NAME,
			ArrayUtils.mergeArrays(
				new Serializable[] {statisticsBase},
				ArrayUtils.isEmpty(statisticsType) ?
					new StatisticsType[0] :
					statisticsType
			)
		);
		Assert.isTrue(
			statisticsBase != null,
			"StatisticsBase is mandatory argument, yet it was not provided!"
		);
	}

	/**
	 * Internal factory method (mainly for external APIs) which applies default for missing arguments
	 */
	@Creator
	private static HierarchyStatistics createWithDefaults(
		@Nullable StatisticsBase statisticsBase,
		@Nonnull StatisticsType... statisticsType
	) {
		return new HierarchyStatistics(
			statisticsBase != null ? statisticsBase : StatisticsBase.WITHOUT_USER_FILTER,
			statisticsType
		);
	}

	/**
	 * Returns the enum signalizing whether the hierarchy statistics results will be based on a complete query
	 * filter by constraint or only the part without user defined filter.
	 */
	@Nonnull
	public StatisticsBase getStatisticsBase() {
		for (Serializable argument : getArguments()) {
			if (argument instanceof StatisticsBase sb) {
				return sb;
			}
		}
		throw new GenericEvitaInternalError("StatisticsBase is mandatory argument, yet it was not found!");
	}

	/**
	 * Returns the enum signalizing whether the hierarchy statistics will contain information about children count,
	 * queued entities count or both.
	 */
	@Nonnull
	public EnumSet<StatisticsType> getStatisticsType() {
		final EnumSet<StatisticsType> result = EnumSet.noneOf(StatisticsType.class);
		for (Serializable argument : getArguments()) {
			if (argument instanceof StatisticsType st) {
				result.add(st);
			}
		}
		return result;
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull() && getArguments().length >= 1;
	}

	@Nonnull
	@Override
	public Serializable[] getArgumentsExcludingDefaults() {
		return Arrays.stream(getArguments())
			.filter(it -> it != StatisticsBase.WITHOUT_USER_FILTER)
			.toArray(Serializable[]::new);
	}

	@Override
	public boolean isArgumentImplicit(@Nonnull Serializable serializable) {
		return serializable == StatisticsBase.WITHOUT_USER_FILTER;
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		Assert.isTrue(
			Arrays.stream(newArguments).anyMatch(StatisticsBase.class::isInstance),
			"HierarchyStatistics requires an argument of type StatisticsBase!"
		);
		Assert.isTrue(
			Arrays.stream(newArguments).allMatch(it -> it instanceof StatisticsBase || it instanceof StatisticsType),
			"HierarchyStatistics accepts only arguments of type StatisticsBase and StatisticsType!"
		);
		return new HierarchyStatistics(newArguments);
	}

}
