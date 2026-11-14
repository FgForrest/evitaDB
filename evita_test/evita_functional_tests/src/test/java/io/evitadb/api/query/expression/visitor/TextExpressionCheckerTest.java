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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.util.List;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.EXPRESSION;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TextExpressionChecker}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("TextExpressionChecker")
@Tag(CONTRACT)
@Tag(EXPRESSION)
class TextExpressionCheckerTest {

	/**
	 * Returns the problems the checker finds in the expression with `$value` as the root variable.
	 *
	 * @param expression the expression
	 * @return the problems
	 */
	@Nonnull
	private static List<String> problems(@Nonnull String expression) {
		return TextExpressionChecker.findProblems(ExpressionFactory.parse(expression), "value");
	}

	@ParameterizedTest(name = "\"{0}\"")
	@ValueSource(strings = {
		"$value",
		"$value.title",
		"$value['title']",
		"$value.blocks[0].text",
		"$value.blocks.*[$.text]",
		"$value.blocks.*![$.caption]",
		"$value.perex ?? $value.title",
		"$value.perex ?? 'fallback'",
		"$value.blocks.*[$.items.*[$.text]]",
		"$value?.title",
		"($value.title)"
	})
	@DisplayName("Accepts an expression that navigates the stored value")
	void shouldAcceptNavigation(@Nonnull String expression) {
		assertTrue(problems(expression).isEmpty(), () -> problems(expression).toString());
	}

	@ParameterizedTest(name = "\"{0}\"")
	@ValueSource(strings = {
		"$entity.title",
		"$value.blocks.*[$entity.title]",
		"random()",
		"$value.blocks[random(2)].text",
		"$value.title == 'x'",
		"$value.blocks.any($.text == 'x')",
		"1 + 2",
		"'constant'",
		"!true"
	})
	@DisplayName("Rejects another variable, a function or a root that cannot produce text")
	void shouldRejectUnfitExpression(@Nonnull String expression) {
		assertFalse(problems(expression).isEmpty(), expression);
	}

}
