/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

import io.evitadb.core.query.algebra.deferred.BitmapSupplier;
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.core.transaction.memory.TransactionalLayerProducer;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.utils.Assert;
import lombok.RequiredArgsConstructor;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * Abstract - base implementation for {@link BitmapSupplier}. Generalizes logic connected with
 * {@link TransactionalDataRelatedStructure} implementation
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
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
	private final long[] transactionalIds;
	/**
	 * Contains memoized value of {@link #gatherTransactionalIds()} computed hash.
	 */
	private Long transactionalIdHash;

	@Override
	public void initialize(@Nonnull CalculationContext calculationContext) {
		if (this.hash == null) {
			this.hash = computeHash(calculationContext.getHashFunction());
		}
		if (this.transactionalIdHash == null) {
			this.transactionalIdHash = calculationContext.getHashFunction().hashLongs(
				Arrays.stream(gatherTransactionalIds())
					.distinct()
					.sorted()
					.toArray()
			);
		}
		if (this.estimatedCost == null) {
			if (calculationContext.visit(CalculationType.ESTIMATED_COST, this)) {
				this.estimatedCost = getEstimatedCardinality() * 12L;
			} else {
				this.estimatedCost = 0L;
			}
		}
		if (this.cost == null) {
			if (calculationContext.visit(CalculationType.COST, this)) {
				this.cost = hierarchyIndex.getHierarchySize() * getOperationCost();
				this.costToPerformance = getCost() / (get().size() * getOperationCost());
			} else {
				this.cost = 0L;
				this.costToPerformance = Long.MAX_VALUE;

			}
		}
	}

	@Override
	public long getHash() {
		if (this.hash == null) {
			initialize(CalculationContext.NO_CACHING_INSTANCE);
		}
		return this.hash;
	}

	@Override
	public long getTransactionalIdHash() {
		if (this.transactionalIdHash == null) {
			initialize(CalculationContext.NO_CACHING_INSTANCE);
		}
		return this.transactionalIdHash;

	}

	@Nonnull
	@Override
	public long[] gatherTransactionalIds() {
		if (this.transactionalIds == null) {
			initialize(CalculationContext.NO_CACHING_INSTANCE);
		}
		return transactionalIds;
	}

	@Override
	public long getEstimatedCost() {
		if (this.estimatedCost == null) {
			initialize(CalculationContext.NO_CACHING_INSTANCE);
		}
		return this.estimatedCost;
	}

	@Override
	public long getCost() {
		if (this.cost == null) {
			initialize(CalculationContext.NO_CACHING_INSTANCE);
			Assert.isPremiseValid(this.cost != null, "Formula results haven't been computed!");
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
			initialize(CalculationContext.NO_CACHING_INSTANCE);
		}
		return costToPerformance;
	}

	/**
	 * Computes the hash value using the specified hash function.
	 *
	 * @param hashFunction the hash function to use for computing the hash value
	 * @return the computed hash value
	 */
	protected abstract long computeHash(@Nonnull LongHashFunction hashFunction);

}
