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

package io.evitadb.api.query.expression;

import io.evitadb.api.query.expression.bool.ConjunctionOperator;
import io.evitadb.dataType.expression.Expression;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.EXPRESSION;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for {@link ExpressionFactory} static helpers — currently the null-safe
 * {@link ExpressionFactory#and(Expression, Expression) and(...)} combinator that AND-merges two
 * optional expressions while preserving identity for the trivial passthrough cases.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ExpressionFactory")
@Tag(CONTRACT)
@Tag(EXPRESSION)
class ExpressionFactoryTest {

	@Nested
	@DisplayName("Null-safe and(...)")
	class AndNullSafetyTest {

		@Test
		@DisplayName("Should return null when both operands are null")
		void shouldReturnNullWhenBothOperandsNull() {
			assertNull(ExpressionFactory.and(null, null));
		}

		@Test
		@DisplayName("Should return right operand unchanged when left is null")
		void shouldReturnRightWhenLeftIsNull() {
			final Expression right = ExpressionFactory.parse("$entity.attributes['code'] == 'A'");
			final Expression combined = ExpressionFactory.and(null, right);
			// passthrough: helper must return the exact same instance, not a wrapper
			assertSame(right, combined);
		}

		@Test
		@DisplayName("Should return left operand unchanged when right is null")
		void shouldReturnLeftWhenRightIsNull() {
			final Expression left = ExpressionFactory.parse("$reference.attributes['order'] > 0");
			final Expression combined = ExpressionFactory.and(left, null);
			// passthrough: helper must return the exact same instance, not a wrapper
			assertSame(left, combined);
		}

		@Test
		@DisplayName("Should wrap both operands in ConjunctionOperator when both non-null")
		void shouldWrapBothOperandsInConjunctionWhenBothNonNull() {
			final Expression left = ExpressionFactory.parse("$entity.attributes['code'] == 'A'");
			final Expression right = ExpressionFactory.parse("$reference.attributes['order'] > 0");

			final Expression combined = ExpressionFactory.and(left, right);

			assertNotNull(combined);
			final ConjunctionOperator conjunction =
				assertInstanceOf(ConjunctionOperator.class, combined.getOperand());
			// operand identity must be preserved -- the helper should not clone or re-parse
			assertSame(left.getOperand(), conjunction.getLeftOperand());
			assertSame(right.getOperand(), conjunction.getRightOperand());
		}

	}

}
