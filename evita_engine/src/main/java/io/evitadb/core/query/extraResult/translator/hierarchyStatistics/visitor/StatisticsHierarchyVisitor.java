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
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyEntityFetcher;
import io.evitadb.core.query.extraResult.translator.hierarchyStatistics.producer.HierarchyEntityPredicate;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.hierarchy.HierarchyNode;
import io.evitadb.index.hierarchy.HierarchyVisitor;
import org.roaringbitmap.RoaringBitmap;

import javax.annotation.Nonnull;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

/**
 * This {@link HierarchyVisitor} implementation is called for each hierarchical entity and cooperates
 * with {@link Accumulator} to compose a tree of {@link LevelInfo} objects.
 */
public class StatisticsHierarchyVisitor implements HierarchyVisitor {
	/**
	 * Contains true if hierarchy statistics should be stripped of results with zero occurrences.
	 */
	private final boolean removeEmptyResults;
	/**
	 * Predicate is used to filter out hierarchical entities that doesn't match the language requirement.
	 */
	@Nonnull private final HierarchyEntityPredicate entityPredicate;
	/**
	 * Contains bitmap of entity primary keys that fulfills the filter of the query.
	 */
	@Nonnull private final RoaringBitmap filteredEntityPks;
	/**
	 * Deque of accumulators allow to compose a tree of results
	 */
	@Nonnull private final Deque<Accumulator> accumulator;
	/**
	 * Contains a function that produces bitmap of queried entity ids connected with particular hierarchical entity.
	 */
	@Nonnull private final IntFunction<Bitmap> hierarchyReferencingEntityPks;
	/**
	 * Function that allows to fetch {@link SealedEntity} for `entityType` + `primaryKey` combination. SealedEntity
	 * is fetched to the depth specified by {@link RequireConstraint[]}.
	 */
	@Nonnull private final HierarchyEntityFetcher entityFetcher;
	/**
	 * TODO JNO - document me
	 */
	@Nonnull private final EnumSet<StatisticsType> statisticsType;
	/**
	 * TODO JNO - document me
	 */
	private final ToIntFunction<HierarchyNode> queuedEntityComputer;

	public StatisticsHierarchyVisitor(boolean removeEmptyResults, @Nonnull HierarchyEntityPredicate entityPredicate, @Nonnull RoaringBitmap filteredEntityPks, @Nonnull Deque<Accumulator> accumulator, @Nonnull IntFunction<Bitmap> hierarchyReferencingEntityPks, @Nonnull HierarchyEntityFetcher entityFetcher, @Nonnull EnumSet<StatisticsType> statisticsType) {
		this.removeEmptyResults = removeEmptyResults;
		this.entityPredicate = entityPredicate;
		this.filteredEntityPks = filteredEntityPks;
		this.accumulator = accumulator;
		this.hierarchyReferencingEntityPks = hierarchyReferencingEntityPks;
		this.entityFetcher = entityFetcher;
		this.statisticsType = statisticsType;
		this.queuedEntityComputer = hierarchyNode -> {
			// get all queried entity primary keys that refer to this hierarchical node
			final Bitmap allEntitiesReferencingEntity = hierarchyReferencingEntityPks.apply(hierarchyNode.entityPrimaryKey());
			// now combine them with primary keys that are really returned by the query and compute matching count
			return RoaringBitmap.and(
				RoaringBitmapBackedBitmap.getRoaringBitmap(allEntitiesReferencingEntity),
				filteredEntityPks
			).getCardinality();
		};
	}

	@Override
	public void visit(@Nonnull HierarchyNode node, int level, int distance, @Nonnull Runnable childrenTraverser) {
		final int entityPrimaryKey = node.entityPrimaryKey();
		// get current accumulator
		final Accumulator topAccumulator = Objects.requireNonNull(accumulator.peek());
		if (topAccumulator.isInOmissionBlock() && entityPredicate.test(entityPrimaryKey)) {
			// in omission block compute only cardinality of queued entities
			topAccumulator.registerOmittedCardinality(
				queuedEntityComputer.applyAsInt(node)
			);
			// but deeply
			childrenTraverser.run();
		} else if (entityPredicate.test(entityPrimaryKey, level, distance)) {
			// now fetch the appropriate form of the hierarchical entity
			final EntityClassifier hierarchyEntity = entityFetcher.apply(entityPrimaryKey);
			// and create element in accumulator that will be filled in
			accumulator.push(new Accumulator(hierarchyEntity, () -> queuedEntityComputer.applyAsInt(node)));
			// traverse subtree - filling up the accumulator on previous row
			childrenTraverser.run();
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
		} else if (!statisticsType.isEmpty() && entityPredicate.test(entityPrimaryKey)) {
			// if we need to compute statistics, but the positional predicate stops traversal
			// we need to traverse the rest of the tree in the omission block
			topAccumulator.executeOmissionBlock(childrenTraverser);
			// when we exit the omission block we may resolve the children count
			if (statisticsType.contains(StatisticsType.CHILDREN_COUNT)) {
				if (!(topAccumulator.getChildrenCount() == 0 && removeEmptyResults)) {
					topAccumulator.registerOmittedChild();
				}
			}
			// and compute overall cardinality
			if (statisticsType.contains(StatisticsType.QUERIED_ENTITY_COUNT)) {
				topAccumulator.registerOmittedCardinality(
					queuedEntityComputer.applyAsInt(node)
				);
			}
		}
	}
}
