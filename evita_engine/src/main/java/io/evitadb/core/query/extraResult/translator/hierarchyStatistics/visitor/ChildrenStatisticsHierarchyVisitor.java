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

package io.evitadb.core.query.extraResult.translator.hierarchyStatistics.visitor;

import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.extraResult.Hierarchy.LevelInfo;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyEntityFetcher;
import io.evitadb.index.hierarchy.HierarchyNode;
import io.evitadb.index.hierarchy.HierarchyVisitor;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import io.evitadb.index.hierarchy.predicate.HierarchyTraversalPredicate;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * This {@link HierarchyVisitor} implementation is called for each hierarchical entity and cooperates
 * with {@link Accumulator} to compose a tree of {@link LevelInfo} objects.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public class ChildrenStatisticsHierarchyVisitor implements HierarchyVisitor {
	/**
	 * Contains true if hierarchy statistics should be stripped of results with zero occurrences.
	 */
	private final boolean removeEmptyResults;
	/**
	 * The number contains a number that needs to be added to a `distance` variable when the real root is different
	 * to the first visited hierarchy node. It is usually used for siblings computation when the parent node needs
	 * to be omitted and thus the distance needs to be lowered by one.
	 */
	private final int distanceCompensation;
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
	@Nonnull private final Deque<Accumulator> accumulator;
	/**
	 * Function that allows to fetch {@link SealedEntity} for `entityType` + `primaryKey` combination. SealedEntity
	 * is fetched to the depth specified by {@link RequireConstraint[]}.
	 */
	@Nonnull private final HierarchyEntityFetcher entityFetcher;
	/**
	 * Field contains set of all {@link StatisticsType} required by the input query.
	 */
	@Nonnull private final EnumSet<StatisticsType> statisticsType;
	/**
	 * Internal function that creates a formula that computes the number of queried entities linked to the processed
	 * {@link HierarchyNode}.
	 */
	private final IntFunction<Formula> queriedEntityComputer;
	/**
	 * The root accumulator that holds the computation result.
	 */
	private final Accumulator rootAccumulator;

	public ChildrenStatisticsHierarchyVisitor(
		boolean removeEmptyResults,
		int distanceCompensation,
		@Nonnull HierarchyTraversalPredicate scopePredicate,
		@Nonnull HierarchyFilteringPredicate filterPredicate,
		@Nonnull IntFunction<Formula> queuedEntityComputer,
		@Nonnull HierarchyEntityFetcher entityFetcher,
		@Nonnull EnumSet<StatisticsType> statisticsType
	) {
		this.removeEmptyResults = removeEmptyResults;
		this.distanceCompensation = distanceCompensation;
		this.scopePredicate = scopePredicate;
		this.filterPredicate = filterPredicate;
		this.accumulator = new LinkedList<>();

		// accumulator is used to gather information about its children gradually
		this.rootAccumulator = new Accumulator(null, () -> EmptyFormula.INSTANCE);
		accumulator.add(rootAccumulator);

		this.entityFetcher = entityFetcher;
		this.statisticsType = statisticsType;
		this.queriedEntityComputer = queuedEntityComputer;
	}

	@Nonnull
	public List<LevelInfo> getResult(@Nonnull EnumSet<StatisticsType> statisticsTypes) {
		return rootAccumulator.getChildrenAsLevelInfo(statisticsTypes);
	}

	@Nonnull
	public List<Accumulator> getAccumulators() {
		return rootAccumulator.getChildren();
	}

	@Override
	public void visit(@Nonnull HierarchyNode node, int level, int distance, @Nonnull Runnable traverser) {
		final int entityPrimaryKey = node.entityPrimaryKey();
		if (filterPredicate.test(entityPrimaryKey)) {
			// get current accumulator
			final Accumulator topAccumulator = Objects.requireNonNull(accumulator.peek());
			if (topAccumulator.isInOmissionBlock()) {
				// we can short-circuit when the caller wants only StatisticsType.CHILDREN_COUNT and accumulator
				// already registered some cardinality
				if (statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT) || !topAccumulator.hasQueriedEntity()) {
					// in omission block compute only cardinality of queued entities
					topAccumulator.registerOmittedCardinality(
						queriedEntityComputer.apply(node.entityPrimaryKey())
					);
					// we can short-circuit when the caller wants only StatisticsType.CHILDREN_COUNT and accumulator
					// already registered some cardinality
					if (statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT) || !topAccumulator.hasQueriedEntity()) {
						traverser.run();
					}
				}
			} else {
				if (scopePredicate.test(entityPrimaryKey, level, distance + distanceCompensation)) {
					// and create element in accumulator that will be filled in
					accumulator.push(
						new Accumulator(
							entityFetcher.apply(entityPrimaryKey),
							() -> queriedEntityComputer.apply(node.entityPrimaryKey())
						)
					);
					// traverse subtree - filling up the accumulator on previous row
					traverser.run();
					// now remove current accumulator from stack
					final Accumulator finalizedAccumulator = accumulator.pop();
					// and if its cardinality is greater than zero (contains at least one queried entity)
					// add it to the result
					if (removeEmptyResults) {
						if (statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT)) {
							// we need to fully compute cardinality of queried entities
							if (!finalizedAccumulator.getQueriedEntitiesFormula().compute().isEmpty()) {
								topAccumulator.add(finalizedAccumulator);
							}
						} else {
							// we may choose more optimal path finding at least one queried entity
							if (finalizedAccumulator.hasQueriedEntity()) {
								topAccumulator.add(finalizedAccumulator);
							}
						}
					} else {
						topAccumulator.add(finalizedAccumulator);
					}
				} else if (!statisticsType.isEmpty()) {
					// and create element in accumulator that will be filled in
					final Accumulator theOmmissionAccumulator = new Accumulator(
						() -> queriedEntityComputer.apply(node.entityPrimaryKey())
					);
					accumulator.push(theOmmissionAccumulator);
					// if we need to compute statistics, but the positional predicate stops traversal
					// we need to traverse the rest of the tree in the omission block
					theOmmissionAccumulator.executeOmissionBlock(traverser);
					// now remove current accumulator from stack
					accumulator.pop();
					// when we exit the omission block we may resolve the children count
					if (statisticsType.contains(StatisticsType.CHILDREN_COUNT)) {
						if (removeEmptyResults) {
							// we need to fully compute cardinality of queried entities
							if (theOmmissionAccumulator.hasQueriedEntity()) {
								topAccumulator.registerOmittedChild();
							}
						} else {
							topAccumulator.registerOmittedChild();
						}
					}
					// and compute overall cardinality
					if (statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT)) {
						// propagated all formulas with omitted children
						theOmmissionAccumulator.getOmittedQueuedEntities().forEach(topAccumulator::registerOmittedCardinality);
						// and now register omitted cardinality for this node as well
						topAccumulator.registerOmittedCardinality(queriedEntityComputer.apply(node.entityPrimaryKey()));
					}
				}
			}
		}
	}
}
