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
import io.evitadb.core.query.response.TransactionalDataRelatedStructure;
import io.evitadb.index.hierarchy.HierarchyIndex;
import io.evitadb.index.transactionalMemory.TransactionalLayerProducer;
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
	 * Set of {@link TransactionalLayerProducer#getId()} that are involved in data computation.
	 */
	private final long[] transactionalId;

	@Override
	public long computeTransactionalIdHash(@Nonnull LongHashFunction hashFunction) {
		return hashFunction.hashLongs(
			Arrays.stream(gatherTransactionalIds())
				.distinct()
				.sorted()
				.toArray()
		);
	}

	@Nonnull
	@Override
	public long[] gatherTransactionalIds() {
		return transactionalId;
	}

	@Override
	public long getEstimatedCost(@Nonnull CalculationContext calculationContext) {
		if (calculationContext.visit(CalculationType.ESTIMATED_COST, this)) {
			return getEstimatedCardinality() * 12L;
		} else {
			return 0L;
		}
	}

	@Override
	public long getCost(@Nonnull CalculationContext calculationContext) {
		if (calculationContext.visit(CalculationType.COST, this)) {
			return hierarchyIndex.getHierarchySize() * getOperationCost();
		} else {
			return 0L;
		}
	}

	@Override
	public long getOperationCost() {
		return 1;
	}

	@Override
	public long getCostToPerformanceRatio(@Nonnull CalculationContext calculationContext) {
		return getCost(calculationContext) / (get().size() * getOperationCost());
	}

}
