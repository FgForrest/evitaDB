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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.extraResult.translator.hierarchyStatistics.visitor;

import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyEntityFetcher;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.SiblingsStatisticsTravelingComputer;
import io.evitadb.index.hierarchy.HierarchyNode;
import io.evitadb.index.hierarchy.HierarchyVisitor;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.index.hierarchy.predicate.HierarchyTraversalPredicate;
import io.evitadb.index.hierarchy.predicate.HierarchyTraversalPredicate.SelfTraversingPredicate;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This {@link HierarchyVisitor} implementation is called for each hierarchical entity and cooperates
 * with {@link Accumulator} to compose a tree of {@link LevelInfo} objects.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class ParentStatisticsHierarchyVisitor implements HierarchyVisitor {
	/**
	 * Predicate that will mark the produced {@link LevelInfo} as requested.
	 */
	@Nonnull
	private final IntPredicate requestedPredicate;
	/**
	 * The predicate that controls the scope that will be returned in the form of {@link LevelInfo}.
	 */
	@Nonnull
	private final HierarchyTraversalPredicate scopePredicate;
	/**
	 * The predicate controlling which hierarchical entities will be taken into an account
	 * in {@link LevelInfo#childrenCount()} and {@link LevelInfo#queriedEntityCount()}.
	 */
	@Nonnull
	private final HierarchyFilteringPredicate filterPredicate;
	/**
	 * Deque of accumulators allow to compose a tree of results
	 */
	@Nonnull private final Deque<Accumulator> accumulator = new ArrayDeque<>(16);
	/**
	 * Function that allows to fetch {@link SealedEntity} for `entityType` + `primaryKey` combination. SealedEntity
	 * is fetched to the depth specified by {@link RequireConstraint[]}.
	 */
	@Nonnull private final HierarchyEntityFetcher entityFetcher;
	/**
	 * Optional siblings computer allowing to compute siblings statistics for each traversed parent hierarchy node.
	 */
	@Nullable private final SiblingsStatisticsTravelingComputer siblingsStatisticsComputer;
	/**
	 * Internal function that creates a formula that computes the number of queried entities linked to the processed
	 * {@link HierarchyNode}.
	 */
	@Nonnull private final IntFunction<Formula> queriedEntityComputer;
	/**
	 * Internal flag that instead of registering siblings to the output {@link LevelInfo} children, it only increases
	 * the counters for their parents.
	 */
	private final boolean omitSiblings;

	public ParentStatisticsHierarchyVisitor(
		@Nonnull IntPredicate requestedPredicate,
		@Nonnull HierarchyTraversalPredicate scopePredicate,
		@Nonnull HierarchyFilteringPredicate filterPredicate,
		@Nonnull IntFunction<Formula> queriedEntityComputer,
		@Nonnull HierarchyEntityFetcher entityFetcher,
		@Nullable SiblingsStatisticsTravelingComputer siblingsStatisticsComputer,
		boolean omitSiblings
	) {
		this.requestedPredicate = requestedPredicate;
		this.scopePredicate = scopePredicate;
		this.filterPredicate = filterPredicate;
		this.entityFetcher = entityFetcher;
		this.queriedEntityComputer = queriedEntityComputer;
		this.siblingsStatisticsComputer = siblingsStatisticsComputer;
		this.omitSiblings = omitSiblings;
	}

	@Nonnull
	public List<Accumulator> getResult(@Nonnull Accumulator startNode) {
		final Iterator<Accumulator> it = accumulator.iterator();
		Accumulator current = startNode;

		while (it.hasNext()) {
			final Accumulator next = it.next();
			next.add(current);
			if (siblingsStatisticsComputer != null) {
				final List<Accumulator> siblings = siblingsStatisticsComputer.createStatistics(
					filterPredicate,
					next.getEntity().getPrimaryKey(),
					current.getEntity().getPrimaryKey()
				);
				if (omitSiblings) {
					siblings.forEach(s -> {
						next.registerOmittedChild();
						next.registerOmittedCardinality(s.getQueriedEntitiesFormula());
					});
				} else {
					siblings.forEach(next::add);
				}
			}
			current = next;
		}

		if (siblingsStatisticsComputer == null || omitSiblings) {
			return Collections.singletonList(current);
		} else {
			return Stream.concat(
					siblingsStatisticsComputer.createStatistics(
						filterPredicate, null,
						current.getEntity().getPrimaryKey()
					).stream(),
					Stream.of(current)
				)
				.sorted(Comparator.comparingInt(levelInfo -> levelInfo.getEntity().getPrimaryKey()))
				.collect(Collectors.toList());
		}
	}

	@Override
	public void visit(@Nonnull HierarchyNode node, int level, int distance, @Nonnull Runnable traverser) {
		// traverse parents - filling up the accumulator
		final int entityPrimaryKey = node.entityPrimaryKey();
		if (scopePredicate instanceof SelfTraversingPredicate selfTraversingPredicate) {
			selfTraversingPredicate.traverse(entityPrimaryKey, level, distance, traverser);
		} else {
			traverser.run();
		}

		if (scopePredicate.test(entityPrimaryKey, level, distance)) {
			if (filterPredicate.test(entityPrimaryKey)) {
				// and create element in accumulator that will be filled in
				accumulator.push(
					new Accumulator(
						requestedPredicate.test(entityPrimaryKey),
						entityFetcher.apply(entityPrimaryKey),
						() -> queriedEntityComputer.apply(node.entityPrimaryKey())
					)
				);
			}
		}
	}

}
