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

package io.evitadb.core.query.algebra.price.predicate;

import io.evitadb.api.query.require.QueryPriceMode;
import io.evitadb.core.query.algebra.identity.FormulaKey;
import io.evitadb.core.query.algebra.identity.IdentityRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.PRICE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the detached predicate value independently of any price formula's other inputs.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Price predicate identity values")
@Tag(ENGINE)
@Tag(PRICE)
class PricePredicateIdentityTest {
	/** Equal predicate values have equal detached identities across contract and indexed-record implementations. */
	@Test
	@DisplayName("Matches contract and record predicate value inputs")
	void shouldMatchContractAndRecordPredicateInputs() {
		assertEquals(key(predicate(BigDecimal.ONE, BigDecimal.TEN, QueryPriceMode.WITH_TAX, 2)),
			key(new PricePredicate.PriceContractPredicate(BigDecimal.ONE, BigDecimal.TEN, QueryPriceMode.WITH_TAX, 2)));
		assertEquals(key(PricePredicate.ALL_RECORD_FILTER), key(PricePredicate.ALL_CONTRACT_FILTER));
	}

	/** Tax mode contributes even when the indexed bounds stay unchanged. */
	@Test
	@DisplayName("Includes tax mode")
	void shouldIncludeQueryPriceMode() {
		assertDifferent(predicate(BigDecimal.ONE, BigDecimal.TEN, QueryPriceMode.WITH_TAX, 2),
			predicate(BigDecimal.ONE, BigDecimal.TEN, QueryPriceMode.WITHOUT_TAX, 2));
		assertDifferent(PricePredicate.ALL_RECORD_FILTER, predicate(null, null, QueryPriceMode.WITH_TAX, 0));
	}

	/** Decimal places remain an input when rescaled external bounds produce the very same indexed integers. */
	@Test
	@DisplayName("Includes decimal places independently of indexed bounds")
	void shouldIncludeIndexedPricePlacesIndependently() {
		final PricePredicate.PriceRecordPredicate base =
			predicate(BigDecimal.ONE, BigDecimal.TEN, QueryPriceMode.WITH_TAX, 2);
		final PricePredicate.PriceRecordPredicate rescaled =
			predicate(new BigDecimal("0.1"), BigDecimal.ONE, QueryPriceMode.WITH_TAX, 3);
		assertEquals(base.identity().fromAsInt(), rescaled.identity().fromAsInt());
		assertEquals(base.identity().toAsInt(), rescaled.identity().toAsInt());
		assertDifferent(base, rescaled);
	}

	/** The lower bound contributes without changing the upper bound, precision or tax mode. */
	@Test
	@DisplayName("Includes the lower bound")
	void shouldIncludeFromAsInt() {
		assertDifferent(predicate(BigDecimal.ONE, BigDecimal.TEN, QueryPriceMode.WITH_TAX, 2),
			predicate(BigDecimal.valueOf(2), BigDecimal.TEN, QueryPriceMode.WITH_TAX, 2));
	}

	/** The upper bound contributes without changing the lower bound, precision or tax mode. */
	@Test
	@DisplayName("Includes the upper bound")
	void shouldIncludeToAsInt() {
		assertDifferent(predicate(BigDecimal.ONE, BigDecimal.TEN, QueryPriceMode.WITH_TAX, 2),
			predicate(BigDecimal.ONE, BigDecimal.valueOf(20), QueryPriceMode.WITH_TAX, 2));
	}

	/** Constructs real predicates whose inclusive bounds are converted to indexed integers. */
	@Nonnull
	private static PricePredicate.PriceRecordPredicate predicate(
		@Nullable BigDecimal from, @Nullable BigDecimal to,
		@Nonnull QueryPriceMode mode, int places
	) {
		return new PricePredicate.PriceRecordPredicate(from, to, mode, places);
	}

	/** Requires equality and its structural prefilter to distinguish the two value inputs. */
	private static void assertDifferent(@Nonnull PricePredicateContract first, @Nonnull PricePredicateContract second) {
		final FormulaKey firstKey = key(first);
		final FormulaKey secondKey = key(second);
		assertNotEquals(firstKey, secondKey);
		assertNotEquals(firstKey.getHash64(), secondKey.getHash64());
	}

	/** Builds a detached leaf key of the nested value record using its declaring identifier. */
	@Nonnull
	private static FormulaKey key(@Nonnull PricePredicateContract predicate) {
		final IdentityRecord identity = predicate.identity();
		return FormulaKey.of(identity, identity.classId(), FormulaKey.NO_CHILDREN, predicate);
	}
}
