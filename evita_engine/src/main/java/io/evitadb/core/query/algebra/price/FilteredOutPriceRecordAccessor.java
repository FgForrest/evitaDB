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

import io.evitadb.api.query.require.PriceHistogram;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.price.predicate.PriceAmountPredicate;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate;
import io.evitadb.core.query.extraResult.translator.histogram.producer.PriceHistogramProducer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * This interface marks all formulas that apply {@link PricePredicate} onto already collected price records / entities.
 * These formulas interact with {@link PriceHistogramProducer} allowing it to collect all data while reusing results
 * of the most calculations already executed.
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

	/**
	 * Returns clone of this formula pre-initialized in such way, that it produces remainder to the original compute
	 * method call which was filtered out by predicate. In other words the created clone returns the complement entity
	 * keys to the original computational result - it returns the entity primary keys excluded by {@link PricePredicate}
	 * predicate.
	 *
	 * We do this, because when computing {@link PriceHistogram} we need information about all entities - even those
	 * that didn't pass the {@link PricePredicate} test. On the other hand we want to reuse current results
	 * as much as possible. This formula allows us to compute only the remainder necessary for price histogram.
	 */
	@Nonnull
	Formula getCloneWithPricePredicateFilteredOutResults();

}
