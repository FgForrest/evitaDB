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
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.algebra.price.predicate;

import io.evitadb.api.query.require.QueryPriceMode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Represents a predicate used to filter price amounts based on specified criteria.
 *
 * @param queryPriceMode     the mode of the price query
 * @param from               the lower bound of the price amount
 * @param to                 the upper bound of the price amount
 * @param indexedPricePlaces the number of decimal places to which the price amount is indexed
 * @param predicate          the predicate used to filter the price amount
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public record PriceAmountPredicate(
	@Nullable QueryPriceMode queryPriceMode,
	@Nullable BigDecimal from,
	@Nullable BigDecimal to,
	int indexedPricePlaces,
	@Nonnull Predicate<BigDecimal> predicate
) implements Predicate<BigDecimal>, Serializable {

	public static final PriceAmountPredicate ALL = new PriceAmountPredicate(
		null, null, null, 0,
		amount -> true
	);

	@Override
	public boolean test(BigDecimal bigDecimal) {
		return predicate.test(bigDecimal);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		PriceAmountPredicate that = (PriceAmountPredicate) o;

		if (indexedPricePlaces != that.indexedPricePlaces) return false;
		if (queryPriceMode != that.queryPriceMode) return false;
		if (!Objects.equals(from, that.from)) return false;
		return Objects.equals(to, that.to);
	}

	@Override
	public int hashCode() {
		int result = queryPriceMode != null ? queryPriceMode.hashCode() : 0;
		result = 31 * result + (from != null ? from.hashCode() : 0);
		result = 31 * result + (to != null ? to.hashCode() : 0);
		result = 31 * result + indexedPricePlaces;
		return result;
	}
}
