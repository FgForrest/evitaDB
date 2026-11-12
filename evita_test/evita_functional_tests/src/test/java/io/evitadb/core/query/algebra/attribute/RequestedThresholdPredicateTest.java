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

package io.evitadb.core.query.algebra.attribute;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.annotation.Nonnull;
import java.math.BigDecimal;

import static io.evitadb.test.TestTags.CACHE;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies normalized threshold value identities and exact comparison boundaries.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Requested threshold predicates")
@Tag(ENGINE)
@Tag(CACHE)
class RequestedThresholdPredicateTest {
	/** Checks every operator against a literal truth table for thresholds below, at and above the value 2. */
	@ParameterizedTest(name = "{0}")
	@CsvSource({
		"LESS_THAN,           true,  false, false",
		"LESS_THAN_EQUALS,    true,  true,  false",
		"GREATER_THAN,        false, false, true",
		"GREATER_THAN_EQUALS, false, true,  true"
	})
	@DisplayName("Evaluates every comparison operator with normalized values")
	void shouldEvaluateComparisons(@Nonnull ThresholdOperator operator, boolean below, boolean at, boolean above) {
		final ThresholdComparison predicate = new ThresholdComparison(operator, new BigDecimal("2.00"));

		assertEquals(below, predicate.test(BigDecimal.ONE));
		assertEquals(at, predicate.test(new BigDecimal("2")));
		assertEquals(above, predicate.test(new BigDecimal("3")));
		assertEquals(below, operator.accepts(Integer.MIN_VALUE));
		assertEquals(above, operator.accepts(Integer.MAX_VALUE));
		assertEquals(predicate, new ThresholdComparison(operator, new BigDecimal("2")));
		assertEquals(predicate.hashCode(), new ThresholdComparison(operator, new BigDecimal("2.0")).hashCode());
		assertTrue(new ThresholdComparison(operator, null).test(BigDecimal.ZERO));
	}

	/** Checks that integer-side trailing zeros and every scale of zero normalize to one value. */
	@Test
	@DisplayName("Normalizes trailing integer zeros and zero")
	void shouldNormalizeIntegerZerosAndZero() {
		final ThresholdComparison hundred = new ThresholdComparison(
			ThresholdOperator.LESS_THAN, new BigDecimal("100")
		);
		final ThresholdComparison scientific = new ThresholdComparison(
			ThresholdOperator.LESS_THAN, new BigDecimal("1E+2")
		);
		final ThresholdRange zero = new ThresholdRange(new BigDecimal("0.00"), new BigDecimal("0E+3"));

		assertEquals(hundred, scientific);
		assertEquals(hundred.hashCode(), scientific.hashCode());
		assertEquals(new ThresholdRange(BigDecimal.ZERO, BigDecimal.ZERO), zero);
		assertEquals(new ThresholdRange(BigDecimal.ZERO, BigDecimal.ZERO).hashCode(), zero.hashCode());
	}

	/** Checks that operators, an absent value and the position of each bound are part of the identity. */
	@Test
	@DisplayName("Distinguishes operators, absent values and bound positions")
	void shouldDistinguishConditions() {
		final BigDecimal one = BigDecimal.ONE;
		final BigDecimal two = new BigDecimal("2");

		assertNotEquals(
			new ThresholdComparison(ThresholdOperator.LESS_THAN, one),
			new ThresholdComparison(ThresholdOperator.LESS_THAN_EQUALS, one)
		);
		assertNotEquals(
			new ThresholdComparison(ThresholdOperator.LESS_THAN, null),
			new ThresholdComparison(ThresholdOperator.LESS_THAN, BigDecimal.ZERO)
		);
		assertNotEquals(new ThresholdRange(one, two), new ThresholdRange(two, one));
		assertNotEquals(new ThresholdRange(null, one), new ThresholdRange(one, null));
	}

	/** Checks inclusive and open-ended ranges and numeric equality. */
	@Test
	@DisplayName("Evaluates inclusive ranges with optional bounds")
	void shouldEvaluateRanges() {
		final ThresholdRange range = new ThresholdRange(new BigDecimal("1.00"), new BigDecimal("2.0"));
		assertEquals(range, new ThresholdRange(BigDecimal.ONE, new BigDecimal("2")));
		assertEquals(range.hashCode(), new ThresholdRange(BigDecimal.ONE, new BigDecimal("2")).hashCode());
		assertTrue(range.test(BigDecimal.ONE));
		assertTrue(range.test(new BigDecimal("2")));
		assertFalse(range.test(BigDecimal.ZERO));
		assertFalse(range.test(new BigDecimal("3")));
		assertTrue(new ThresholdRange(null, null).test(BigDecimal.TEN));
		assertTrue(new ThresholdRange(null, BigDecimal.ONE).test(BigDecimal.ZERO));
		assertTrue(new ThresholdRange(BigDecimal.ONE, null).test(BigDecimal.TEN));
	}
}
