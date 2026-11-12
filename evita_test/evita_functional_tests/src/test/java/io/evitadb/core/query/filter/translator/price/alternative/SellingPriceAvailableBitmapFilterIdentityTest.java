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
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.core.query.algebra.price.predicate.PriceContractPredicate;
import io.evitadb.core.query.algebra.price.predicate.PricePredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.PRICE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies detached identities of the selling-price prefetch alternative.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Selling price prefetch alternative identity")
@Tag(ENGINE)
@Tag(PRICE)
class SellingPriceAvailableBitmapFilterIdentityTest {
	/** Null and empty additional lists describe the same fetch requirement. */
	@Test
	@DisplayName("Normalizes absent lists and snapshots list order and content")
	void shouldSnapshotAdditionalPriceLists() {
		assertEquals(key(new SellingPriceAvailableBitmapFilter()),
			key(new SellingPriceAvailableBitmapFilter((String[]) null)));
		final String[] lists = {"first", "second"};
		final SellingPriceAvailableBitmapFilter filter = new SellingPriceAvailableBitmapFilter(lists);
		final FormulaKey captured = key(filter);
		lists[0] = "changed";
		assertEquals(captured, key(filter));
		assertEquals(captured, key(new SellingPriceAvailableBitmapFilter("first", "second")));
		assertNotEquals(captured, key(new SellingPriceAvailableBitmapFilter("second", "first")));
		assertNotEquals(captured, key(new SellingPriceAvailableBitmapFilter("first")));
	}

	/** Each price predicate value contributes to the alternative's value identity and structural hash. */
	@Test
	@DisplayName("Distinguishes every price predicate value input")
	void shouldDistinguishEveryPricePredicateInput() {
		final FormulaKey base = predicateKey(BigDecimal.ONE, BigDecimal.TEN, QueryPriceMode.WITH_TAX, 2);
		final FormulaKey[] variants = {
			predicateKey(BigDecimal.valueOf(2), BigDecimal.TEN, QueryPriceMode.WITH_TAX, 2),
			predicateKey(BigDecimal.ONE, BigDecimal.valueOf(20), QueryPriceMode.WITH_TAX, 2),
			predicateKey(BigDecimal.ONE, BigDecimal.TEN, QueryPriceMode.WITHOUT_TAX, 2),
			predicateKey(BigDecimal.ONE, BigDecimal.TEN, QueryPriceMode.WITH_TAX, 3)
		};
		for (final FormulaKey variant : variants) {
			assertNotEquals(base, variant);
			assertNotEquals(base.getHash64(), variant.getHash64());
		}
		assertEquals(base, predicateKey(BigDecimal.ONE, BigDecimal.TEN, QueryPriceMode.WITH_TAX, 2));
	}

	/** String content reaches the structural hash independently of Java's String hash. */
	@Test
	@DisplayName("Separates price-list names with equal Java string hashes")
	void shouldHashPriceListNamesByContent() {
		final FormulaKey first = key(new SellingPriceAvailableBitmapFilter("Aa"));
		final FormulaKey second = key(new SellingPriceAvailableBitmapFilter("BB"));
		assertNotEquals(first, second);
		assertNotEquals(first.getHash64(), second.getHash64());
	}

	/** Builds a real contract predicate and its owning alternative. */
	@Nonnull
	private static FormulaKey predicateKey(
		@Nonnull BigDecimal from, @Nonnull BigDecimal to, @Nonnull QueryPriceMode mode, int places
	) {
		final PriceContractPredicate predicate = new PricePredicate.PriceContractPredicate(from, to, mode, places);
		return key(new SellingPriceAvailableBitmapFilter(new String[]{"basic"}, predicate));
	}

	/** Builds the same leaf side key that a selection builds for its alternative. */
	@Nonnull
	private static FormulaKey key(@Nonnull SellingPriceAvailableBitmapFilter filter) {
		return FormulaKey.of(filter.identity(), filter.getClassId(), FormulaKey.NO_CHILDREN, filter);
	}
}
