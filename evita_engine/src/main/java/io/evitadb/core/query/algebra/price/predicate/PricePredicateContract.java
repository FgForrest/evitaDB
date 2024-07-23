/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.core.query.algebra.price.predicate;

import io.evitadb.api.query.require.QueryPriceMode;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;

/**
 * Unified contract for price predicates that can be used to filter out price records that are not in the filter range.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface PricePredicateContract {

	/**
	 * Retrieves the price mode used for filtering.
	 *
	 * @return the price mode used for filtering, or null if not set
	 */
	@Nullable
	QueryPriceMode getQueryPriceMode();

	/**
	 * Returns lower threshold of the filter.
	 *
	 * @return lower threshold of the filter if any
	 */
	@Nullable
	BigDecimal getFrom();

	/**
	 * Returns upper threshold of the filter.
	 *
	 * @return upper threshold of the filter if any
	 */
	@Nullable
	BigDecimal getTo();

	/**
	 * Retrieves the number of decimal places used for indexing prices.
	 *
	 * @return the number of decimal places used for indexing prices
	 */
	int getIndexedPricePlaces();

	/**
	 * Returns predicate that can be used to filter out price records that are not in the filter range.
	 *
	 * @return predicate that can be used to filter out price records that are not in the filter range
	 */
	@Nonnull
	PriceAmountPredicate getRequestedPredicate();

	/**
	 * Method is expected to compute unique hash for this particular instance of predicate. This hash needs much better
	 * collision rate than common {@link Object#hashCode()} and therefore passed hash function is expected to be utilized.
	 * Hash is targeted to be used in cache key.
	 */
	long computeHash(@Nonnull LongHashFunction hashFunction);
}
