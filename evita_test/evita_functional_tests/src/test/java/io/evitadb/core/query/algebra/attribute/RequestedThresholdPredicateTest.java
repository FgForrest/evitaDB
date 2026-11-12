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
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.CACHE;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;

/**
 * Verifies normalized threshold value identities and exact comparison boundaries.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Requested threshold predicates")
@Tag(ENGINE)
@Tag(CACHE)
class RequestedThresholdPredicateTest {
	/** Checks all operator signs, normalized equality and absent bounds. */
	@Test
	@DisplayName("Evaluates every comparison operator with normalized values")
	void shouldEvaluateComparisons() {
		for (final ThresholdOperator operator : ThresholdOperator.values()) {
			final ThresholdComparison predicate = new ThresholdComparison(operator, new BigDecimal("2.00"));
			assertEquals(predicate, new ThresholdComparison(operator, new BigDecimal("2")));
			assertEquals(predicate.hashCode(), new ThresholdComparison(operator, new BigDecimal("2.0")).hashCode());
			assertEquals(operator.accepts(-7), predicate.test(BigDecimal.ONE));
			assertEquals(operator.accepts(0), predicate.test(new BigDecimal("2")));
			assertEquals(operator.accepts(9), predicate.test(new BigDecimal("3")));
			assertTrue(new ThresholdComparison(operator, null).test(BigDecimal.ZERO));
		}
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
