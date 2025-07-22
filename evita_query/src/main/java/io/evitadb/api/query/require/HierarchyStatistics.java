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
 * The statistics constraint allows you to retrieve statistics about the hierarchy nodes that are returned by the
 * current query. When used it triggers computation of the queriedEntityCount, childrenCount statistics, or both for
 * each hierarchy node in the returned hierarchy tree.
 *
 * It requires mandatory argument of type {@link StatisticsType} enum that specifies which statistics to compute:
 *
 * - {@link StatisticsType#CHILDREN_COUNT}: triggers calculation of the count of child hierarchy nodes that exist in
 *   the hierarchy tree below the given node; the count is correct regardless of whether the children themselves are
 *   requested/traversed by the constraint definition, and respects hierarchyOfReference settings for automatic removal
 *   of hierarchy nodes that would contain empty result set of queried entities ({@link EmptyHierarchicalEntityBehaviour#REMOVE_EMPTY})
 * - {@link StatisticsType#QUERIED_ENTITY_COUNT}: triggers the calculation of the total number of queried entities that
 *   will be returned if the current query is focused on this particular hierarchy node using the hierarchyWithin filter
 *   constraint (the possible refining constraint in the form of directRelation and excluding-root is not taken into
 *   account).
 *
 * And optional argument of type {@link StatisticsBase} enum allowing you to specify the base queried entity set that
 * is the source for statistics calculations:
 *
 * - {@link StatisticsBase#COMPLETE_FILTER}: complete filtering query constraint
 * - {@link StatisticsBase#WITHOUT_USER_FILTER}: filtering query constraint where the contents of optional userFilter
 *    are ignored
 *
 * The calculation always ignores hierarchyWithin because the focused part of the hierarchy tree is defined on
 * the requirement constraint level, but including having/excluding constraints. The having/excluding constraints are
 * crucial for the calculation of queriedEntityCount (and therefore also affects the value of childrenCount
 * transitively).
 *
 * <strong>Computational complexity of statistical data calculation</strong>
 *
 * The performance price paid for calculating statistics is not negligible. The calculation of {@link StatisticsType#CHILDREN_COUNT}
 * is cheaper because it allows to eliminate "dead branches" early and thus conserve the computation cycles.
 * The calculation of the {@link StatisticsType#QUERIED_ENTITY_COUNT} is more expensive because it requires counting
 * items up to the last one and must be precise.
 *
 * We strongly recommend that you avoid using {@link StatisticsType#QUERIED_ENTITY_COUNT} for root hierarchy nodes for
 * large datasets.
 *
 * This query actually has to filter and aggregate all the records in the database, which is obviously quite expensive,
 * even considering that all the indexes are in-memory. Caching is probably the only way out if you really need
 * to crunch these numbers.
 *
 * <p><a href="https://evitadb.io/documentation/query/requirements/hierarchy#statistics">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@ConstraintDefinition(
	name = "statistics",
	shortDescription = "The constraint triggers computing the count of children for each returned hierarchy node.",
	userDocsLink = "/documentation/query/requirements/hierarchy#statistics",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchyStatistics extends AbstractRequireConstraintLeaf implements ConstraintWithDefaults<RequireConstraint>, HierarchyOutputRequireConstraint {
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