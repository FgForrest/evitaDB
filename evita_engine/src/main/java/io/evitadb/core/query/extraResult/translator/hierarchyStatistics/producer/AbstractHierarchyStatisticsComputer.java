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

package io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer;

import io.evitadb.api.query.filter.EntityLocaleEquals;
import io.evitadb.api.query.filter.HierarchyAnyHaving;
import io.evitadb.api.query.filter.HierarchyExcluding;
import io.evitadb.api.query.filter.HierarchyHaving;
import io.evitadb.api.query.filter.HierarchyWithin;
import io.evitadb.api.query.require.StatisticsBase;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.visitor.Accumulator;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.index.hierarchy.predicate.HierarchyTraversalPredicate;
import io.evitadb.index.hierarchy.predicate.LocaleHierarchyEntityPredicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

import static java.util.Optional.ofNullable;

/**
 * Abstract ancestor for hierarchy statistics computers. Contains shared logic and data.
 */
abstract class AbstractHierarchyStatisticsComputer {

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
	 * Contains the set of statistics that are required to be computed.
	 *
	 * - {@link StatisticsType#CHILDREN_COUNT} triggers {@link LevelInfo#childrenCount()} computation
	 * - {@link StatisticsType#QUERIED_ENTITY_COUNT} triggers {@link LevelInfo#queriedEntityCount()} computation
	 */
	@Nonnull
	protected final EnumSet<StatisticsType> statisticsType;
	/**
	 * The predicate that controls the scope that will be returned in the form of {@link LevelInfo}.
	 */
	@Nonnull
	protected final HierarchyTraversalPredicate scopePredicate;
	/**
	 * The lambda allowing to create inclusion predicate controlling which hierarchical entities will be taken into
	 * an account in {@link LevelInfo#childrenCount()} and {@link LevelInfo#queriedEntityCount()} respecting the
	 * {@link StatisticsBase}.
	 */
	@Nullable
	private final Function<StatisticsBase, HierarchyFilteringPredicate> hierarchyFilterPredicateProducer;
	/**
	 * The predicate controlling which hierarchical entities will be taken into an account
	 * in {@link LevelInfo#childrenCount()} and {@link LevelInfo#queriedEntityCount()}. The predicate is driven
	 * by {@link HierarchyExcluding} / {@link HierarchyHaving} / {@link HierarchyAnyHaving} filtering constraint.
	 */
	@Nullable
	protected final HierarchyFilteringPredicate havingPredicate;
	/**
	 * Controls the scope of the query filter by, that should be used for computing the queried entity count
	 * in the {@link LevelInfo#queriedEntityCount()} statistics. Might be null if the count is not required to be
	 * computed at all.
	 */
	@Nonnull
	protected final StatisticsBase statisticsBase;

	public AbstractHierarchyStatisticsComputer(
		@Nonnull HierarchyProducerContext context,
		@Nonnull HierarchyEntityFetcher entityFetcher,
		@Nullable Function<StatisticsBase, HierarchyFilteringPredicate> hierarchyFilterPredicateProducer,
		@Nullable HierarchyFilteringPredicate havingPredicate,
		@Nullable HierarchyTraversalPredicate scopePredicate,
		@Nullable StatisticsBase statisticsBase,
		@Nonnull EnumSet<StatisticsType> statisticsType
	) {
		this.context = context;
		this.entityFetcher = entityFetcher;
		this.hierarchyFilterPredicateProducer = hierarchyFilterPredicateProducer;
		this.havingPredicate = havingPredicate;
		this.scopePredicate = ofNullable(scopePredicate).orElse(HierarchyTraversalPredicate.NEVER_STOP_PREDICATE);
		this.statisticsBase = statisticsBase == null ? StatisticsBase.WITHOUT_USER_FILTER : statisticsBase;
		this.statisticsType = statisticsType;
	}

	/**
	 * Fabricates single collection of {@link LevelInfo} for requested hierarchical entity type. It respects
	 * the {@link EntityLocaleEquals} and {@link HierarchyWithin} constraints used in the query. It also uses
	 * `filteringFormula` to limit the reported cardinalities in level info objects.
	 */
	@Nonnull
	public final List<LevelInfo> createStatistics(
		@Nonnull QueryExecutionContext executionContext,
		@Nullable Locale language
	) {
		HierarchyFilteringPredicate filteringPredicate;
		if (this.hierarchyFilterPredicateProducer == null) {
			filteringPredicate = Objects.requireNonNullElse(
				this.havingPredicate, HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE
			);
			if (language != null) {
				if (filteringPredicate == HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE) {
					filteringPredicate = new LocaleHierarchyEntityPredicate(this.context.entityIndex(), language);
				} else {
					filteringPredicate.and(new LocaleHierarchyEntityPredicate(this.context.entityIndex(), language));
				}
			}
		} else {
			filteringPredicate = ofNullable(this.hierarchyFilterPredicateProducer.apply(this.statisticsBase))
				.map(it -> {
					it.initializeIfNotAlreadyInitialized(executionContext);
					return it;
				})
				.orElse(HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE);
		}
		// init the predicate
		this.scopePredicate.initializeIfNotAlreadyInitialized(executionContext);
		filteringPredicate.initializeIfNotAlreadyInitialized(executionContext);
		// the language predicate is used to filter out entities that doesn't have requested language variant
		return createStatistics(
			executionContext,
			this.scopePredicate,
			filteringPredicate
		)
			.stream()
			.map(it -> it.toLevelInfo(this.statisticsType))
			.toList();
	}

	/**
	 * Method implementation differs across different computer types.
	 */
	@Nonnull
	protected abstract List<Accumulator> createStatistics(
		@Nonnull QueryExecutionContext executionContext,
		@Nonnull HierarchyTraversalPredicate scopePredicate,
		@Nonnull HierarchyFilteringPredicate filterPredicate
	);

}
