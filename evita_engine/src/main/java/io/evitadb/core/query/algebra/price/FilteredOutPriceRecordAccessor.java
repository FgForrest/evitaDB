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

package io.evitadb.core.query.algebra.price;

import io.evitadb.core.query.algebra.price.predicate.PriceAmountPredicate;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate;
import io.evitadb.core.query.extraResult.translator.histogram.producer.PriceHistogramProducer;

import javax.annotation.Nullable;

/**
 * Exposes the requested {@link PricePredicate price-range predicate} applied by a formula to price records or entities.
 * {@link PriceHistogramProducer} uses this metadata to mark requested histogram buckets.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface FilteredOutPriceRecordAccessor {

	/**
	 * Returns predicate that is able to mark price as within the requested price range.
	 * @return predicate that is able to mark price as within the requested price range.
	 */
	@Nullable
	PriceAmountPredicate getRequestedPredicate();

}
