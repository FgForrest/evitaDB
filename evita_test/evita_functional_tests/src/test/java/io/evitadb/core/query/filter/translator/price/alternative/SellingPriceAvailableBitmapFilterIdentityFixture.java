/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.core.query.filter.translator.price.alternative;

import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate;
import io.evitadb.test.identity.*;
import javax.annotation.Nonnull;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static io.evitadb.core.query.algebra.price.PriceIdentityFixtureSupport.*;
import static io.evitadb.test.identity.CloneOutcome.*;

/**
 * Real construction inputs and clone contracts of {@link SellingPriceAvailableBitmapFilter}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class SellingPriceAvailableBitmapFilterIdentityFixture
	implements IdentityFixture<SellingPriceAvailableBitmapFilter> {
	/** Covers the holder's constructor and factory modes with independently changed inputs. */
	@Nonnull
	@Override
	public Map<String, ConstructionMode<SellingPriceAvailableBitmapFilter>> modes() {
		return Map.of(
			"additional lists", new ConstructionMode<>(
				() -> new SellingPriceAvailableBitmapFilter(new String[]{"basic"}, PricePredicate.ALL_CONTRACT_FILTER),
				Map.of("lists", () -> new SellingPriceAvailableBitmapFilter(new String[]{"other"},
					PricePredicate.ALL_CONTRACT_FILTER), "filter", () -> new SellingPriceAvailableBitmapFilter(
					new String[]{"basic"}, new PricePredicate.PriceContractPredicate(
						BigDecimal.ONE, BigDecimal.TEN, QueryPriceMode.WITH_TAX, 2))),
				Map.of(), Map.of(), Map.of(), Set.of(), Set.of(), Set.of()),
			"no additional lists", new ConstructionMode<>(
				() -> new SellingPriceAvailableBitmapFilter(),
				Map.of("lists", () -> new SellingPriceAvailableBitmapFilter("basic"),
					"filter", () -> new SellingPriceAvailableBitmapFilter(null, new PricePredicate.PriceContractPredicate(
						BigDecimal.ONE, BigDecimal.TEN, QueryPriceMode.WITH_TAX, 2))),
				Map.of(), Map.of(), Map.of(), Set.of(), Set.of(), Set.of())
		);
	}
}
