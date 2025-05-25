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
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.index.hierarchy.HierarchyIndex;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * Abstract - base implementation for {@link BitmapSupplier}. Generalizes logic connected with
 * {@link TransactionalDataRelatedStructure} implementation
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public abstract class AbstractHierarchyBitmapSupplier implements BitmapSupplier {
	/**
	 * Reference to the {@link HierarchyIndex} that will be used for gathering the data.
	 */
	protected final HierarchyIndex hierarchyIndex;
	/**
	 * Contains memoized value of {@link #getEstimatedCost()}  of this formula.
	 */
	private Long estimatedCost;
	/**
	 * Contains memoized value of {@link #getCost()}  of this formula.
	 */
	private Long cost;
	/**
	 * Contains memoized value of {@link #getCostToPerformanceRatio()} of this formula.
	 */
	private Long costToPerformance;
	/**
	 * Contains memoized value of {@link #getHash()} method.
	 */
	private Long hash;
	/**
	 * Set of {@link TransactionalLayerProducer#getId()} that are involved in data computation.
	 */
	private long[] transactionalIds;
	/**
	 * Contains memoized value of {@link #gatherTransactionalIds()} computed hash.
	 */
	private Long transactionalIdHash;
	/**
	 * Contains memoized result of the supplier.
	 */
	private Bitmap memoizedResult;

	public AbstractHierarchyBitmapSupplier(@Nonnull HierarchyIndex hierarchyIndex, long[] transactionalIds) {
		this.hierarchyIndex = hierarchyIndex;
		this.transactionalIds = transactionalIds;
	}

	/**
	 * Initializes the fields of the supplier.
	 *
	 * TOBEDONE when upgrading to Java 22 with https://openjdk.org/jeps/447, switch fields to final and do this in the constructor
	 */
	protected void initFields() {
		this.hash = computeHash(HASH_FUNCTION);
		this.transactionalIdHash = HASH_FUNCTION.hashLongs(
			Arrays.stream(gatherTransactionalIds())
				.distinct()
				.sorted()
				.toArray()
		);
		this.estimatedCost = getEstimatedCardinality() * 12L;
	}

	@Override
	public void initialize(@Nonnull QueryExecutionContext executionContext) {
		// do nothing
	}

	@Override
	public long getHash() {
		return this.hash;
	}

	@Override
	public long getTransactionalIdHash() {
		return this.transactionalIdHash;

	}

	@Nonnull
	@Override
	public long[] gatherTransactionalIds() {
		return this.transactionalIds;
	}

	@Override
	public long getEstimatedCost() {
		return this.estimatedCost;
	}

	@Override
	public long getCost() {
		if (this.cost == null) {
			if (this.memoizedResult != null) {
				this.cost = this.hierarchyIndex.getHierarchySize() * getOperationCost();
			} else {
				return Long.MAX_VALUE;
			}
		}
		return this.cost;
	}

	@Override
	public long getOperationCost() {
		return 1;
	}

	@Override
	public long getCostToPerformanceRatio() {
		if (this.costToPerformance == null) {
			if (this.memoizedResult != null) {
				this.costToPerformance = getCost() / (get().size() * getOperationCost());
			} else {
				return Long.MAX_VALUE;
			}
		}
		return this.costToPerformance;
	}

	@Override
	public final Bitmap get() {
		if (this.memoizedResult == null) {
			this.memoizedResult = getInternal();
		}
		return this.memoizedResult;
	}

	/**
	 * Calculates the result.
	 * @return the result
	 */
	@Nonnull
	protected abstract Bitmap getInternal();

	/**
	 * Computes the hash value using the specified hash function.
	 *
	 * @param hashFunction the hash function to use for computing the hash value
	 * @return the computed hash value
	 */
	protected abstract long computeHash(@Nonnull LongHashFunction hashFunction);

}
