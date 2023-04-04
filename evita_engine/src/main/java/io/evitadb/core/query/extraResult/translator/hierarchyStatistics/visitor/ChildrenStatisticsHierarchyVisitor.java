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
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.extraResult.HierarchyStatistics.LevelInfo;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyEntityFetcher;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyFilteringPredicate;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyTraversalPredicate;
import io.evitadb.index.hierarchy.HierarchyNode;
import io.evitadb.index.hierarchy.HierarchyVisitor;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.ToIntBiFunction;

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
	private final ToIntBiFunction<HierarchyNode, Formula> queriedEntityComputer;
	/**
	 * The root accumulator that holds the computation result.
	 */
	private final Accumulator rootAccumulator;

	public ChildrenStatisticsHierarchyVisitor(
		boolean removeEmptyResults,
		@Nonnull HierarchyTraversalPredicate scopePredicate,
		@Nonnull HierarchyFilteringPredicate filterPredicate,
		@Nonnull Formula filteredEntityPks,
		@Nonnull IntFunction<Formula> hierarchyReferencingEntityPks,
		@Nonnull HierarchyEntityFetcher entityFetcher,
		@Nonnull EnumSet<StatisticsType> statisticsType
	) {
		this.removeEmptyResults = removeEmptyResults;
		this.scopePredicate = scopePredicate;
		this.filterPredicate = filterPredicate;
		this.accumulator = new LinkedList<>();

		// accumulator is used to gather information about its children gradually
		this.rootAccumulator = new Accumulator(null, null, () -> 0);
		accumulator.add(rootAccumulator);

		this.entityFetcher = entityFetcher;
		this.statisticsType = statisticsType;
		this.queriedEntityComputer = (hierarchyNode, predicateFilteringFormula) -> {
			// get all queried entity primary keys that refer to this hierarchical node
			final Formula allEntitiesReferencingEntity = hierarchyReferencingEntityPks.apply(hierarchyNode.entityPrimaryKey());
			// now combine them with primary keys that are really returned by the query and compute matching count
			final Formula completedFormula;
			if (predicateFilteringFormula == null) {
				completedFormula = FormulaFactory.and(
					allEntitiesReferencingEntity,
					filteredEntityPks
				);
			} else {
				completedFormula = FormulaFactory.and(
					allEntitiesReferencingEntity,
					predicateFilteringFormula,
					filteredEntityPks
				);
			}
			return completedFormula.compute().size();
		};
	}

	@Nonnull
	public List<LevelInfo> getResult() {
		return rootAccumulator.getChildren();
	}

	@Override
	public void visit(@Nonnull HierarchyNode node, int level, int distance, @Nonnull Runnable traverser) {
		final int entityPrimaryKey = node.entityPrimaryKey();
		// get current accumulator
		final Accumulator topAccumulator = Objects.requireNonNull(accumulator.peek());

		if (topAccumulator.isInOmissionBlock()) {
			if (filterPredicate.test(entityPrimaryKey)) {
				// in omission block compute only cardinality of queued entities
				topAccumulator.registerOmittedCardinality(
					queriedEntityComputer.applyAsInt(node, filterPredicate.getFilteringFormula())
				);
			}
			// traverse sub-tree
			traverser.run();
		} else {
			if (filterPredicate.test(entityPrimaryKey)) {
				if (scopePredicate.test(entityPrimaryKey, level, distance)) {
					// now fetch the appropriate form of the hierarchical entity
					final EntityClassifier hierarchyEntity = entityFetcher.apply(entityPrimaryKey);
					// and create element in accumulator that will be filled in
					accumulator.push(
						new Accumulator(
							node, hierarchyEntity,
							() -> queriedEntityComputer.applyAsInt(node, filterPredicate.getFilteringFormula())
						)
					);
					// traverse subtree - filling up the accumulator on previous row
					traverser.run();
					// now remove current accumulator from stack
					final Accumulator finalizedAccumulator = accumulator.pop();
					// and if its cardinality is greater than zero (contains at least one queried entity)
					// add it to the result
					if (removeEmptyResults) {
						Optional.of(finalizedAccumulator.toLevelInfo(statisticsType))
							.filter(it -> it.queriedEntityCount() > 0)
							.ifPresent(topAccumulator::add);
					} else {
						topAccumulator.add(
							finalizedAccumulator.toLevelInfo(statisticsType)
						);
					}
				} else if (!statisticsType.isEmpty()) {
					// if we need to compute statistics, but the positional predicate stops traversal
					// we need to traverse the rest of the tree in the omission block
					topAccumulator.executeOmissionBlock(traverser);
					// when we exit the omission block we may resolve the children count
					if (statisticsType.contains(StatisticsType.CHILDREN_COUNT)) {
						if (!(topAccumulator.getChildrenCount() == 0 && removeEmptyResults)) {
							topAccumulator.registerOmittedChild();
						}
					}
					// and compute overall cardinality
					if (statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT)) {
						topAccumulator.registerOmittedCardinality(
							queriedEntityComputer.applyAsInt(node, filterPredicate.getFilteringFormula())
						);
					}
				}
			}
		}
	}
}
