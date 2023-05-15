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

package io.evitadb.api.query.require;

import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.api.query.descriptor.annotation.Value;
import io.evitadb.exception.EvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.EnumSet;

/**
 * TOBEDONE JNO: docs
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@ConstraintDefinition(
	name = "statistics",
	shortDescription = "The constraint triggers computing the count of children for each returned hierarchy node.",
	supportedIn = ConstraintDomain.HIERARCHY
)
public class HierarchyStatistics extends AbstractRequireConstraintLeaf implements HierarchyOutputRequireConstraint {
	@Serial private static final long serialVersionUID = 264601966496432983L;

	private HierarchyStatistics(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	public HierarchyStatistics() {
		super("statistics", StatisticsBase.WITHOUT_USER_FILTER);
	}

	public HierarchyStatistics(
		@Nonnull StatisticsBase statisticsBase
	) {
		// because this query can be used only within some other hierarchy query, it would be
		// unnecessary to duplicate the hierarchy prefix
		super(
			"statistics",
			statisticsBase == null
				? StatisticsBase.WITHOUT_USER_FILTER
				: statisticsBase
		);
	}

	@Creator
	public HierarchyStatistics(
		@Nonnull @Value StatisticsBase statisticsBase,
		@Nonnull @Value StatisticsType... statisticsType
	) {
		// because this query can be used only within some other hierarchy query, it would be
		// unnecessary to duplicate the hierarchy prefix
		super(
			"statistics",
			ArrayUtils.mergeArrays(
				statisticsBase == null ?
					new Serializable[] {StatisticsBase.WITHOUT_USER_FILTER} : new Serializable[] {statisticsBase},
				ArrayUtils.isEmpty(statisticsType) ?
					new StatisticsType[0] :
					statisticsType
			)
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
		throw new EvitaInternalError("StatisticsBase is mandatory argument, yet it was not found!");
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
