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

package io.evitadb.core.query.response;

import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongSet;
import io.evitadb.core.cache.CacheEden;
import io.evitadb.core.cache.CacheSupervisor;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.deferred.BitmapSupplier;
import io.evitadb.core.transaction.memory.TransactionalLayerCreator;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import java.util.Map;

/**
 * This interface unifies parts of the formula tree that are either {@link Formula} directly or objects that provide
 * access directly to bitmaps linked to transactional data store (such as {@link BitmapSupplier} extensions).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface TransactionalDataRelatedStructure {
	/**
	 * Defines threshold that should trigger returning entire "index" transactional id instead of exact bitmap
	 * transactional ids in method {@link #gatherTransactionalIds()}. Storing excessive amount of long ids would allocate
	 * too much memory in {@link CacheEden} that is better invalidate less precisely on each index
	 * change than to allocate a lot of memory for precise invalidation.
	 */
	int EXCESSIVE_HIGH_CARDINALITY = 100;

	/**
	 * Initializes internal ids and cost estimations. This method is necessary to be called prior to calling any of
	 * these methods:
	 *
	 * - {@link #getHash()}
	 * - {@link #getTransactionalIdHash()}
	 * - {@link #getEstimatedCost()}
	 * - {@link #getCost()}
	 * - {@link #getCostToPerformanceRatio()}
	 *
	 * @param calculationContext calculation context to use for initialization
	 */
	void initialize(@Nonnull CalculationContext calculationContext);

	/**
	 * Hash identifies the formula and it's contents. The hash must be different for formulas with logically different
	 * contents (we are going to rely on some hash function with low rate of collisions) and must be equal with another
	 * formula instance that has logically same contents. For example these formulas must return same hash:
	 *
	 * 1. and(eq(name,'Jan'),greaterThanEq(age,18))
	 * 2. and(eq(name,'Jan'),greaterThanEq(age,18))
	 *
	 * But these must return different hashes:
	 *
	 * 1. and(eq(name,'Jan'),greaterThanEq(age,18))
	 * 2. or(eq(name,'Jan'),greaterThanEq(age,18))
	 */
	long getHash();

	/**
	 * Transactional id hash is a has computed from the output of {@link #gatherTransactionalIds()}. The hash must be
	 * computed from distinct, sorted transactional ids of all transactional data sources (bitmaps / indexes). The hash
	 * must be same for two formula/computer instances that build from same sources and must differ for formula instance
	 * that has at least single difference in the array contents (we are going to rely on some hash function with low
	 * rate of collisions).
	 */
	long getTransactionalIdHash();

	/**
	 * Returns {@link TransactionalLayerCreator#getId()} of all bitmaps used by this formula. Should any of those ids
	 * become obsolete the formula is also obsolete. The returned array may contain duplicates and may not be sorted.
	 */
	@Nonnull
	long[] gatherTransactionalIds();

	/**
	 * Estimated effort of the operation without prior the result of the computation. From bottom to up it's getting less
	 * useful because operation at the top of the formula doesn't count bitmap cardinality reduction that happens when
	 * formula is processed. For precise cost computation use {@link #getCost()}  operation that takes
	 * this into an account but also requires formula to be fully computed.
	 */
	long getEstimatedCost();

	/**
	 * Cost of the operation based on computation result. Sum of bitmap sizes of referenced bitmaps multiplied by known
	 * {@link #getOperationCost()} of this operation. This method triggers formula computation.
	 */
	long getCost();

	/**
	 * Returns estimated costs of this operation. The number should be product of real measurement of this operation
	 * compared to simple no operation formula that just rewrites all number to a new bitmap.
	 */
	long getOperationCost();

	/**
	 * Returns cost to performance ratio. Default implementation is sums cost to performance ratio of all inner formulas
	 * and adds ratio of this operation that is computed as ratio of its cost to output bitmap size. I.e. when large
	 * bitmap is greatly reduced to a small one, this ratio gets bigger and thus caching output of this formula saves
	 * more resources than caching outputs of formulas with lesser ratio.
	 */
	long getCostToPerformanceRatio();

	/**
	 * Defines the types of calculations that can be performed, which define a key in {@link CalculationContext}.
	 */
	enum CalculationType {
		ESTIMATED_COST,
		COST
	}

	/**
	 * Context that allows to skip already visited formulas of the tree.
	 */
	class CalculationContext {
		/**
		 * Implementation that doesn't allocate and always returns true for each passed element.
		 */
		public static final CalculationContext NO_CACHING_INSTANCE = new NoOpCalculationContext();
		/**
		 * Contains hashes of already visited elements.
		 */
		private final Map<CalculationType, LongSet> visitedElements = CollectionUtils.createHashMap(8);
		/**
		 * The hash function to use for hash calculation.
		 */
		@Nonnull @Getter private final LongHashFunction hashFunction = CacheSupervisor.createHashFunction();

		/**
		 * Visits the element and returns true if it was not visited before.
		 *
		 * @param element to visit
		 * @return true if the element was not visited before
		 */
		public boolean visit(@Nonnull CalculationType calculationType, @Nonnull TransactionalDataRelatedStructure element) {
			return visitedElements
				.computeIfAbsent(calculationType, key -> new LongHashSet(64))
				.add(element.getHash());
		}

		/**
		 * Internal implementation that doesn't allocate and always returns true for each passed element.
		 */
		private static class NoOpCalculationContext extends CalculationContext {

			@Override
			public boolean visit(@Nonnull CalculationType calculationType, @Nonnull TransactionalDataRelatedStructure element) {
				return true;
			}
		}
	}

}
