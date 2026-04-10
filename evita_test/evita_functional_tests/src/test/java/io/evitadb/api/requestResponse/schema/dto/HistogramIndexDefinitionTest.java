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

package io.evitadb.api.requestResponse.schema.dto;

import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HistogramIndexDefinition}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("HistogramIndexDefinition")
class HistogramIndexDefinitionTest {

	/**
	 * Verifies that a {@link HistogramIndexDefinition} can be constructed with both
	 * a non-null name and a non-null expression, and that both fields are accessible.
	 */
	@Test
	@DisplayName("should construct with non-null name and expression")
	void shouldConstructHistogramIndexDefinition() {
		final Expression expr = ExpressionFactory.parse("$price * $quantity");
		final HistogramIndexDefinition def = new HistogramIndexDefinition("priceHistogram", expr);

		assertEquals("priceHistogram", def.nameOfTheIndex());
		assertNotNull(def.valueExpression());
		assertEquals(expr.toExpressionString(), def.valueExpression().toExpressionString());
	}

	/**
	 * Verifies that a null valueExpression is allowed and accessible after construction.
	 */
	@Test
	@DisplayName("should allow null value expression")
	void shouldAllowNullValueExpression() {
		final HistogramIndexDefinition def = new HistogramIndexDefinition("hist", null);

		assertEquals("hist", def.nameOfTheIndex());
		assertNull(def.valueExpression());
	}

	/**
	 * Verifies that constructing with a null nameOfTheIndex throws {@link EvitaInvalidUsageException}
	 * with a message indicating the name must not be null.
	 */
	@Test
	@DisplayName("should reject null nameOfTheIndex")
	void shouldRejectNullNameOfTheIndex() {
		final EvitaInvalidUsageException exception = assertThrows(
			EvitaInvalidUsageException.class,
			() -> new HistogramIndexDefinition(null, null)
		);
		assertTrue(
			exception.getMessage().contains("must not be null"),
			"Expected message to contain 'must not be null' but was: " + exception.getMessage()
		);
	}

	/**
	 * Verifies that constructing with a blank nameOfTheIndex throws {@link EvitaInvalidUsageException}
	 * with a message indicating the name must not be blank.
	 */
	@Test
	@DisplayName("should reject blank nameOfTheIndex")
	void shouldRejectBlankNameOfTheIndex() {
		final EvitaInvalidUsageException exception = assertThrows(
			EvitaInvalidUsageException.class,
			() -> new HistogramIndexDefinition("  ", null)
		);
		assertTrue(
			exception.getMessage().contains("must not be blank"),
			"Expected message to contain 'must not be blank' but was: " + exception.getMessage()
		);
	}

	/**
	 * Verifies that two definitions with the same name and expression are equal and have consistent
	 * hash codes, while two definitions with different names are not equal.
	 */
	@Test
	@DisplayName("should obey record equals and hashCode")
	void shouldObeyRecordEqualsAndHashCode() {
		final Expression expr = ExpressionFactory.parse("$price");
		final HistogramIndexDefinition a = new HistogramIndexDefinition("hist", expr);
		final HistogramIndexDefinition b = new HistogramIndexDefinition("hist", expr);
		final HistogramIndexDefinition c = new HistogramIndexDefinition("other", expr);

		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		assertNotEquals(a, c);
	}
}
