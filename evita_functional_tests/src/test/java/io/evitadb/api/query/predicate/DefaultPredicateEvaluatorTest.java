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

package io.evitadb.api.query.predicate;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, 2024
 */
public class DefaultPredicateEvaluatorTest {

	@ParameterizedTest
	@MethodSource("predicates")
	void shouldEvaluate(String predicate, Map<String, BigDecimal> variables, boolean result) {
		assertEquals(result, evaluate(predicate, variables));
	}

	static Stream<Arguments> predicates() {
		return Stream.of(
			Arguments.of("10 > 5", Map.of(), true),
			Arguments.of("5 < 10", Map.of(), true),
			Arguments.of("10 < 5", Map.of(), false),
			Arguments.of("5 > 10", Map.of(), false),
			Arguments.of("10 > 5 && 5 < 10", Map.of(), true),
			Arguments.of("10 > 5 || 5 > 10", Map.of(), true),
			Arguments.of("10 < 5 || 5 < 10", Map.of(), true),
			Arguments.of("10 < 5 || 5 > 10", Map.of(), false),
			Arguments.of("$pageNumber > 5", Map.of("pageNumber", BigDecimal.valueOf(10)), true),
			Arguments.of("$pageNumber > 5", Map.of("pageNumber", BigDecimal.valueOf(1)), false),
			Arguments.of("$pageNumber > 5 ", Map.of("pageNumber", BigDecimal.valueOf(1)), false),
			Arguments.of("$pageNumber > 2 && $pageNumber % 2 == 0 ", Map.of("pageNumber", BigDecimal.valueOf(1)), false),
			Arguments.of("$pageNumber > 2 && $pageNumber % 2 == 0 ", Map.of("pageNumber", BigDecimal.valueOf(2)), false),
			Arguments.of("$pageNumber > 2 && $pageNumber % 2 == 0 ", Map.of("pageNumber", BigDecimal.valueOf(3)), false),
			Arguments.of("$pageNumber > 2 && $pageNumber % 2 == 0 ", Map.of("pageNumber", BigDecimal.valueOf(4)), true),
			Arguments.of("ceil($pageNumber / 2) == 3", Map.of("pageNumber", BigDecimal.valueOf(5)), true),
			Arguments.of("floor($pageNumber / 2) == 2", Map.of("pageNumber", BigDecimal.valueOf(5)), true)
		);
	}

	private static boolean evaluate(@Nonnull String predicate, @Nonnull Map<String, BigDecimal> variables) {
		return DefaultPredicateEvaluator.getInstance().evaluate(predicate, variables);
	}
}
