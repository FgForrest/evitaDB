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

package io.evitadb.api.query.expression;

import io.evitadb.api.query.expression.evaluate.MultiVariableEvaluationContext;
import io.evitadb.dataType.expression.ExpressionNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test class for the {@link ExpressionFactory}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
public class ExpressionTest {

	@ParameterizedTest
	@MethodSource("predicates")
	void shouldEvaluate(String predicate, Map<String, Object> variables, boolean result) {
		assertEquals(result, evaluate(predicate, variables));
	}

	@ParameterizedTest
	@MethodSource("predicates")
	void shouldSerializeExpressionToString(String predicate, Map<String, Object> variables, boolean result) {
		assertEquals(predicate.trim(), ExpressionFactory.parse(predicate).toString());
	}

	static Stream<Arguments> predicates() {
		return Stream.of(
			Arguments.of("true", Map.of(), true),
			Arguments.of("!true", Map.of(), false),
			Arguments.of("true || false", Map.of(), true),
			Arguments.of("true && false", Map.of(), false),
			Arguments.of("true && (true || false)", Map.of(), true),
			Arguments.of("!true == false", Map.of(), true),
			Arguments.of("true == !(false && true)", Map.of(), true),
			Arguments.of("true == true", Map.of(), true),
			Arguments.of("true == false", Map.of(), false),
			Arguments.of("5 != 4", Map.of(), true),
			Arguments.of("5 == 5", Map.of(), true),
			Arguments.of("(5 == 5)", Map.of(), true),
			Arguments.of("true == (5 == 5)", Map.of(), true),
			Arguments.of("'abc' == 'abc'", Map.of(), true),
			Arguments.of("'abc' == 'def'", Map.of(), false),
			Arguments.of("10 > 5", Map.of(), true),
			Arguments.of("5 < 10", Map.of(), true),
			Arguments.of("10 < 5", Map.of(), false),
			Arguments.of("5 > 10", Map.of(), false),
			Arguments.of("10 > 5 && 5 < 10", Map.of(), true),
			Arguments.of("10 > 5 || 5 > 10", Map.of(), true),
			Arguments.of("10 < 5 || 5 < 10", Map.of(), true),
			Arguments.of("10 < 5 || 5 > 10", Map.of(), false),
			Arguments.of("10 <= 5 || 5 >= 10", Map.of(), false),
			Arguments.of("10 <= 5 || 6 >= 5", Map.of(), true),
			Arguments.of("1 + 3 + 5 == 9", Map.of(), true),
			Arguments.of("1 + 3 + 5 == 8", Map.of(), false),
			Arguments.of("1 + 3 + 5 != 8", Map.of(), true),
			Arguments.of("1 + 3 + 5 != 9", Map.of(), false),
			Arguments.of("-1.0 < -1.11", Map.of(), false),
			Arguments.of("-1.0 > -1.11", Map.of(), true),
			Arguments.of("-1 < +1", Map.of(), true),
			Arguments.of("-(1 + 2) < +1", Map.of(), true),
			Arguments.of("2 - 5 > +1", Map.of(), false),
			Arguments.of("2 * (8 - 4) == 8", Map.of(), true),
			Arguments.of("2 ^ 6 == 64", Map.of(), true),
			Arguments.of("(2 + 4) * 2 == (8 - 2) * 2", Map.of(), true),
			Arguments.of("random(5) > 8", Map.of(), false),
			Arguments.of("random() > 0 && 5 > 7", Map.of(), false),
			Arguments.of("sqrt(3 + 13) == 4", Map.of(), true),
			Arguments.of("$pageNumber > 5", Map.of("pageNumber", BigDecimal.valueOf(10)), true),
			Arguments.of("$pageNumber > 5", Map.of("pageNumber", BigDecimal.valueOf(1)), false),
			Arguments.of("$pageNumber > 5 ", Map.of("pageNumber", BigDecimal.valueOf(1)), false),
			Arguments.of("$pageNumber > 2 && $pageNumber % 2 == 0 ", Map.of("pageNumber", BigDecimal.valueOf(1)), false),
			Arguments.of("$pageNumber > 2 && $pageNumber % 2 == 0 ", Map.of("pageNumber", BigDecimal.valueOf(2)), false),
			Arguments.of("$pageNumber > 2 && $pageNumber % 2 == 0 ", Map.of("pageNumber", BigDecimal.valueOf(3)), false),
			Arguments.of("$pageNumber > 2 && $pageNumber % 2 == 0 ", Map.of("pageNumber", BigDecimal.valueOf(4)), true),
			Arguments.of("ceil($pageNumber / 2) == 3", Map.of("pageNumber", BigDecimal.valueOf(5)), true),
			Arguments.of("floor($pageNumber / 2) == 2", Map.of("pageNumber", BigDecimal.valueOf(5)), true),
			Arguments.of("floor(sqrt($pageNumber)) == 2", Map.of("pageNumber", BigDecimal.valueOf(4)), true),
			Arguments.of("floor(sqrt($pageNumber)) == 2", Map.of("pageNumber", BigDecimal.valueOf(5)), true),
			Arguments.of("floor(sqrt($pageNumber)) == 2", Map.of("pageNumber", BigDecimal.valueOf(16)), false)
		);
	}

	private static boolean evaluate(@Nonnull String predicate, @Nonnull Map<String, Object> variables) {
		final ExpressionNode operator = ExpressionFactory.parse(predicate);
		return operator.compute(new MultiVariableEvaluationContext(42, variables), Boolean.class);
	}
}
