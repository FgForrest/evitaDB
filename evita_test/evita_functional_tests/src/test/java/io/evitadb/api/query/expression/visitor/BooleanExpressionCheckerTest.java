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

package io.evitadb.api.query.expression.visitor;

import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.dataType.expression.ExpressionNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link BooleanExpressionChecker}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@DisplayName("BooleanExpressionChecker")
public class BooleanExpressionCheckerTest {

	@ParameterizedTest(name = "\"{1}\" -> {0}")
	@MethodSource("expressions")
	@DisplayName("should correctly identify boolean expressions")
	void shouldCorrectlyIdentifyBooleanExpressions(boolean expectedResult, @Nonnull String expression) {
		final ExpressionNode root = ExpressionFactory.parse(expression);
		assertEquals(expectedResult, BooleanExpressionChecker.isBooleanExpression(root));
	}

	@Nonnull
	static Stream<Arguments> expressions() {
		return Stream.of(
			// comparison operators
			Arguments.of(true, "1 == 2"),
			Arguments.of(true, "1 != 2"),
			Arguments.of(true, "1 > 2"),
			Arguments.of(true, "1 >= 2"),
			Arguments.of(true, "1 < 2"),
			Arguments.of(true, "1 <= 2"),
			Arguments.of(true, "$entity.attributes['flag'] == true"),
			// logical operators
			Arguments.of(true, "true && false"),
			Arguments.of(true, "true || false"),
			Arguments.of(true, "true ^ false"),
			Arguments.of(true, "!true"),
			// nested expressions
			Arguments.of(true, "(1 > 2)"),
			Arguments.of(true, "((1 > 2))"),
			Arguments.of(true, "!!($entity.attributes['flag'])"),
			// compound boolean expression
			Arguments.of(true, "1 > 2 && 3 < 4"),
			// arithmetic operators (non-boolean)
			Arguments.of(false, "1 + 2"),
			Arguments.of(false, "1 - 2"),
			Arguments.of(false, "1 * 2"),
			Arguments.of(false, "1 / 2"),
			Arguments.of(false, "1 % 2"),
			// other non-boolean expressions
			Arguments.of(false, "42"),
			Arguments.of(false, "$x"),
			Arguments.of(false, "(1 + 2)"),
			Arguments.of(false, "random()")
		);
	}
}
