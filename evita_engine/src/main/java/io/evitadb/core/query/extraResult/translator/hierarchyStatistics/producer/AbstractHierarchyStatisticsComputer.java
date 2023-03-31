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

package io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer;

import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.extraResult.HierarchyStatistics.LevelInfo;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.predicate.LocaleHierarchyEntityPredicate;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Producer class
 */
public abstract class AbstractHierarchyStatisticsComputer {
	/**
	 * Context captured at the moment the computer was created.
	 */
	@Nonnull protected final HierarchyProducerContext context;
	/**
	 * Contains function that converts hierarchy entity id to the requested data type (either it's left as primary
	 * key or converted to full-fledged entity).
	 */
	@Nonnull protected final HierarchyEntityFetcher entityFetcher;
	/**
	 * TODO JNO - DOCUMENT ME
	 */
	@Nonnull
	private final HierarchyEntityPredicate nodeFilter;
	/**
	 * TODO JNO - document me
	 */
	private final StatisticsBase statisticsBase;
	/**
	 * TODO JNO - document me
	 */
	protected final EnumSet<StatisticsType> statisticsType;

	public AbstractHierarchyStatisticsComputer(
		@Nonnull HierarchyProducerContext context,
		@Nonnull HierarchyEntityFetcher entityFetcher,
		@Nonnull HierarchyEntityPredicate nodeFilter,
		@Nullable StatisticsBase statisticsBase,
		@Nonnull EnumSet<StatisticsType> statisticsType
	) {
		this.context = context;
		this.entityFetcher = entityFetcher;
		this.nodeFilter = Optional.ofNullable(nodeFilter)
			.orElse(HierarchyEntityPredicate.MATCH_ALL);
		this.statisticsBase = statisticsBase;
		this.statisticsType = statisticsType;
	}

	/**
	 * Fabricates single collection of {@link LevelInfo} for requested hierarchical entity type. It respects
	 * the {@link EntityLocaleEquals} and {@link HierarchyWithin} constraints used in the query. It also uses
	 * `filteringFormula` to limit the reported cardinalities in level info objects.
	 */
	@Nonnull
	public final List<LevelInfo> createStatistics(
		@Nonnull Formula filteringFormula,
		@Nonnull Formula filteringFormulaWithoutUserFilter,
		@Nullable Locale language
	) {
		// get roaring bitmap of filtering entity ids
		final RoaringBitmap filteredEntityPks = RoaringBitmapBackedBitmap.getRoaringBitmap(
			statisticsBase == StatisticsBase.WITHOUT_USER_FILTER ?
				filteringFormulaWithoutUserFilter.compute() : filteringFormula.compute()
		);
		// the language predicate is used to filter out entities that doesn't have requested language variant
		final HierarchyEntityPredicate resolvedNodePredicate = language == null ?
			nodeFilter :
			nodeFilter.and(
				new HierarchyEntityPredicate(
					new LocaleHierarchyEntityPredicate(context.entityIndex(), language),
					(level, distance) -> true
				)
			);

		return createStatistics(filteredEntityPks, resolvedNodePredicate);
	}

	/**
	 * TODO JNO - DOCUMENT ME
	 * @param filteredEntityPks
	 * @param nodePredicate
	 * @return
	 */
	@Nonnull
	protected abstract List<LevelInfo> createStatistics(
		@Nonnull RoaringBitmap filteredEntityPks,
		@Nonnull HierarchyEntityPredicate nodePredicate
	);

}
