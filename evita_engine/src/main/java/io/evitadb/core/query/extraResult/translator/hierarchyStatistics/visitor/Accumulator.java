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

import io.evitadb.api.query.require.StatisticsType;
import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.extraResult.HierarchyStatistics.LevelInfo;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nonnull;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.stream.IntStream;

/**
 * Accumulator serves to aggregate information about children before creating immutable statistics result.
 */
@RequiredArgsConstructor
public class Accumulator {
	/**
	 * The hierarchical entity in proper form.
	 */
	private final EntityClassifier entity;
	/**
	 * The count of queried entities directly referencing this hierarchical entity (respecting current query filter).
	 */
	private final IntSupplier queriedEntityCount;
	/**
	 * Mutable container for gradually added children.
	 */
	@Getter private final List<LevelInfo> children = new LinkedList<>();
	/**
	 * Counter for the children that would be returned in case the level predicate didn't stop the traversal.
	 */
	private int omittedChildren;
	/**
	 * Counter for the queried entities that would be returned in case the level predicate didn't stop the traversal.
	 */
	private int omittedQueuedEntityCount;
	/**
	 * Flag signalizing that the accumulator traverses the omission block.
	 */
	private boolean omissionBlock;

	/**
	 * Adds information about this hierarchy node children statistics.
	 */
	public void add(@Nonnull LevelInfo nodeAsLevel) {
		this.children.add(nodeAsLevel);
	}

	/**
	 * Converts accumulator data to immutable {@link LevelInfo} DTO.
	 */
	@Nonnull
	public LevelInfo toLevelInfo(@Nonnull EnumSet<StatisticsType> statisticsTypes) {
		return new LevelInfo(
			entity,
			statisticsTypes.contains(StatisticsType.QUERIED_ENTITY_COUNT) ? getQueriedEntityCount() : null,
			statisticsTypes.contains(StatisticsType.CHILDREN_COUNT) ? getChildrenCount() : null,
			children
		);
	}

	public int getQueriedEntityCount() {
		if (children.isEmpty()) {
			return queriedEntityCount.getAsInt() + omittedQueuedEntityCount;
		} else {
			return IntStream.concat(
				IntStream.of(queriedEntityCount.getAsInt()),
				children.stream().mapToInt(LevelInfo::queriedEntityCount)
			).sum();
		}
	}

	public int getChildrenCount() {
		return children.isEmpty() ? omittedChildren : children.size();
	}

	/**
	 * TODO JNO - document me
	 */
	public void registerOmittedChild() {
		omittedChildren++;
	}

	public void registerOmittedCardinality(int cardinality) {
		omittedQueuedEntityCount += cardinality;
	}

	public void executeOmissionBlock(Runnable runnable) {
		try {
			Assert.isPremiseValid(!omissionBlock, "Already in omission block!");
			omissionBlock = true;
			runnable.run();
		} finally {
			omissionBlock = false;
		}
	}

	public boolean isInOmissionBlock() {
		return omissionBlock;
	}
}
