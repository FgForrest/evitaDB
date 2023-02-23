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

package io.evitadb.index.hierarchy.suppliers;

import io.evitadb.core.query.algebra.deferred.BitmapSupplier;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.hierarchy.HierarchyIndex;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Implementation of {@link BitmapSupplier} that provides access to the data stored in {@link HierarchyIndex}
 * in a lazy fashion. The expensive computations happen in {@link #get()} method. This class is meant to be used in
 * combination with {@link DeferredFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class HierarchyByParentDownToLevelWithExcludesBitmapSupplier extends AbstractHierarchyBitmapSupplier {
	private static final int CLASS_ID = -116188477;
	/**
	 * Contains information about the parent node requested in original {@link io.evitadb.api.query.FilterConstraint}.
	 */
	private final int parentNode;
	/**
	 * Contains count of tree levels of the parent node that should be returned (i.e. depth of the returned tree).
	 */
	private final int levels;
	/**
	 * Contains set of entity primary keys whose subtrees should be excluded from listing.
	 */
	private final int[] excludedNodeTrees;

	public HierarchyByParentDownToLevelWithExcludesBitmapSupplier(HierarchyIndex hierarchyIndex, long[] transactionalId, int parentNode, int levels, int[] excludedNodeTrees) {
		super(hierarchyIndex, transactionalId);
		this.parentNode = parentNode;
		this.levels = levels;
		this.excludedNodeTrees = excludedNodeTrees;
	}

	@Override
	public long computeHash(@Nonnull LongHashFunction hashFunction) {
		return hashFunction.hashInts(
			Stream.of(
				IntStream.of(CLASS_ID, parentNode, levels),
				IntStream.of(excludedNodeTrees).sorted()
			).flatMapToInt(it -> it).toArray()
		);
	}

	@Override
	public Bitmap get() {
		return hierarchyIndex.listHierarchyNodesFromParentDownTo(parentNode, levels, excludedNodeTrees);
	}

	@Override
	public int getEstimatedCardinality() {
		return hierarchyIndex.getHierarchyNodeCountFromParentDownTo(parentNode, levels, excludedNodeTrees);
	}
}
