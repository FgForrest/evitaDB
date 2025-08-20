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

package io.evitadb.index.hierarchy.suppliers;

import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.algebra.deferred.BitmapSupplier;
import io.evitadb.core.query.algebra.deferred.DeferredFormula;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.index.hierarchy.predicate.HierarchyFilteringPredicate;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;

/**
 * Implementation of {@link BitmapSupplier} that provides access to the data stored in {@link HierarchyIndex}
 * in a lazy fashion. The expensive computations happen in {@link #get()} method. This class is meant to be used in
 * combination with {@link DeferredFormula}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public class HierarchyRootsBitmapSupplier extends AbstractHierarchyBitmapSupplier {
	private static final long CLASS_ID = -946906775L;
	/**
	 * Contains set of entity primary keys whose subtrees should be excluded from listing.
	 */
	@Nonnull private final HierarchyFilteringPredicate excludedNodeTrees;

	public HierarchyRootsBitmapSupplier(@Nonnull HierarchyIndex hierarchyIndex, long[] transactionalId, @Nonnull HierarchyFilteringPredicate excludedNodeTrees) {
		super(hierarchyIndex, transactionalId);
		this.excludedNodeTrees = excludedNodeTrees;
		this.initFields();
	}

	@Override
	public void initialize(@Nonnull QueryExecutionContext executionContext) {
		this.excludedNodeTrees.initializeIfNotAlreadyInitialized(executionContext);
		super.initialize(executionContext);
	}

	@Override
	public long computeHash(@Nonnull LongHashFunction hashFunction) {
		return hashFunction.hashLongs(
			new long[]{CLASS_ID, this.excludedNodeTrees.getHash()}
		);
	}

	@Nonnull
	@Override
	protected Bitmap getInternal() {
		return this.hierarchyIndex.getRootHierarchyNodes(this.excludedNodeTrees);
	}

	@Override
	public int getEstimatedCardinality() {
		/* we don't use excluded node trees here, because it would trigger the formula computation */
		return this.hierarchyIndex.getRootHierarchyNodeCount(HierarchyFilteringPredicate.ACCEPT_ALL_NODES_PREDICATE);
	}

	@Override
	public String toString() {
		return "HIERARCHY FOR ROOTS " + this.excludedNodeTrees;
	}

}
