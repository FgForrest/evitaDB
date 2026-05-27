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

package io.evitadb.api.requestResponse.schema.mutation.reference;

import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link ScopedHistogramIndexDefinition} verifying construction, accessor exposure
 * for the per-histogram condition slot, null-argument validation, record equality, and the
 * shared empty-array sentinel.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ScopedHistogramIndexDefinition")
@Tag(CONTRACT)
@Tag(SCHEMA)
@Tag(HISTOGRAM)
class ScopedHistogramIndexDefinitionTest {

	/**
	 * Reusable expression fixture for tests that need a non-null `Expression` value. Parsed
	 * once because parsing is allocation-heavy and the expression is treated as immutable.
	 */
	private static final Expression EXPR_A = ExpressionFactory.parse("$entity.attributes['x'] > 0");

	/**
	 * Verifies that the canonical 4-arg constructor populates every component and that
	 * each accessor returns the value supplied at construction. This pins the basic
	 * record contract — accidental component reordering would surface here immediately.
	 */
	@Test
	@DisplayName("should construct with all four components")
	void shouldConstructWithAllFourComponents() {
		final Expression valueExpr = ExpressionFactory.parse("$reference.attributes['price']");

		final ScopedHistogramIndexDefinition def = new ScopedHistogramIndexDefinition(
			Scope.LIVE, "priceHistogram", valueExpr, EXPR_A
		);

		assertEquals(Scope.LIVE, def.scope());
		assertEquals("priceHistogram", def.nameOfTheIndex());
		assertNotNull(def.valueExpression());
		assertEquals(valueExpr.toExpressionString(), def.valueExpression().toExpressionString());
		assertNotNull(def.assignedWhen());
		assertEquals(EXPR_A.toExpressionString(), def.assignedWhen().toExpressionString());
	}

	/**
	 * Verifies that supplying `null` for `assignedWhen` is allowed and that the
	 * accessor returns `null` — and that this does not silently spill into the
	 * `valueExpression` slot.
	 */
	@Test
	@DisplayName("should default assignedWhen to null when not supplied")
	void shouldDefaultAssignedWhenToNullWhenNotSupplied() {
		final Expression valueExpr = ExpressionFactory.parse("$reference.attributes['price']");

		final ScopedHistogramIndexDefinition def = new ScopedHistogramIndexDefinition(
			Scope.LIVE, "priceHistogram", valueExpr, null
		);

		assertNull(def.assignedWhen());
		assertNotNull(
			def.valueExpression(),
			"valueExpression must be independent of assignedWhen — a null in one slot must not erase the other"
		);
	}

	/**
	 * Verifies the two expression slots are independent: a `null` `valueExpression` may
	 * coexist with a non-null `assignedWhen`. Guards against a future refactor that
	 * mistakenly couples the two fields.
	 */
	@Test
	@DisplayName("should allow null valueExpression and non-null assignedWhen")
	void shouldAllowNullValueExpressionAndNonNullAssignedWhen() {
		final ScopedHistogramIndexDefinition def = new ScopedHistogramIndexDefinition(
			Scope.LIVE, "priceHistogram", null, EXPR_A
		);

		assertNull(def.valueExpression());
		assertNotNull(def.assignedWhen());
		assertEquals(EXPR_A.toExpressionString(), def.assignedWhen().toExpressionString());
	}

	/**
	 * Verifies the compact constructor rejects a `null` scope with
	 * {@link EvitaInvalidUsageException} — `scope` is the keying component of this record
	 * and must never be null.
	 */
	@Test
	@DisplayName("should reject null scope")
	void shouldRejectNullScope() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> new ScopedHistogramIndexDefinition(null, "priceHistogram", null, null)
		);
	}

	/**
	 * Verifies the compact constructor rejects a `null` name with
	 * {@link EvitaInvalidUsageException} — without a name the histogram cannot be
	 * referenced from queries.
	 */
	@Test
	@DisplayName("should reject null nameOfTheIndex")
	void shouldRejectNullNameOfTheIndex() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> new ScopedHistogramIndexDefinition(Scope.LIVE, null, null, null)
		);
	}

	/**
	 * Verifies the compact constructor rejects a blank name with
	 * {@link EvitaInvalidUsageException}. Mirrors the validation contract of the peer
	 * {@link io.evitadb.api.requestResponse.schema.dto.HistogramIndexDefinition} so callers
	 * cannot smuggle a whitespace-only name through the scoped wrapper.
	 */
	@Test
	@DisplayName("should reject blank nameOfTheIndex")
	void shouldRejectBlankNameOfTheIndex() {
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> new ScopedHistogramIndexDefinition(Scope.LIVE, "  ", null, null)
		);
		assertThrows(
			EvitaInvalidUsageException.class,
			() -> new ScopedHistogramIndexDefinition(Scope.LIVE, "", null, null)
		);
	}

	/**
	 * Verifies that the {@link ScopedHistogramIndexDefinition#EMPTY} sentinel is not
	 * null and has zero length. The sentinel exists to avoid repeated zero-length
	 * array allocations on hot paths.
	 */
	@Test
	@DisplayName("should expose non-null empty sentinel")
	void shouldExposeNonNullEmptySentinel() {
		assertNotNull(ScopedHistogramIndexDefinition.EMPTY);
		assertEquals(0, ScopedHistogramIndexDefinition.EMPTY.length);
	}

}
