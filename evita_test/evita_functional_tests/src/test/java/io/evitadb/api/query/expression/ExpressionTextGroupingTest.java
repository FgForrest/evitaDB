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
import io.evitadb.api.query.expression.bool.DisjunctionOperator;
import io.evitadb.api.query.expression.bool.EqualsOperator;
import io.evitadb.api.query.expression.bool.GreaterThanEqualsOperator;
import io.evitadb.api.query.expression.bool.GreaterThanOperator;
import io.evitadb.api.query.expression.bool.InverseOperator;
import io.evitadb.api.query.expression.bool.LesserThanEqualsOperator;
import io.evitadb.api.query.expression.bool.LesserThanOperator;
import io.evitadb.api.query.expression.bool.NotEqualsOperator;
import io.evitadb.api.query.expression.bool.XorOperator;
import io.evitadb.api.query.expression.coalesce.NullCoalesceOperator;
import io.evitadb.api.query.expression.coalesce.SpreadNullCoalesceOperator;
import io.evitadb.api.query.expression.numeric.AdditionOperator;
import io.evitadb.api.query.expression.numeric.DivisionOperator;
import io.evitadb.api.query.expression.numeric.ModuloOperator;
import io.evitadb.api.query.expression.numeric.MultiplicationOperator;
import io.evitadb.api.query.expression.numeric.NegativeOperator;
import io.evitadb.api.query.expression.numeric.PositiveOperator;
import io.evitadb.api.query.expression.numeric.SubtractionOperator;
import io.evitadb.api.query.expression.object.ObjectAccessOperator;
import io.evitadb.api.query.expression.object.ObjectOperationStep;
import io.evitadb.api.query.expression.utility.NestedOperator;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.dataType.expression.ExpressionNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.EXPRESSION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Verifies that an expression tree prints as text the parser reads back as the same tree. A parsed tree keeps its
 * explicit parentheses as a {@link NestedOperator}, while a tree composed in code has no node for the grouping it
 * implies - so the printer has to add exactly the parentheses the grammar needs, and no more, which keeps the text of
 * every parsed expression unchanged.
 *
 * The precedence levels below are a copy of the order of the alternatives in `EvitaEL.g4`, where the operators of one
 * level share an alternative (so `+` and `-` bind alike and group from left to right). The tests pin the printer to
 * that order.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Expression text should")
@Tag(CONTRACT)
@Tag(EXPRESSION)
class ExpressionTextGroupingTest {
	/**
	 * Every binary operator of the grammar with its precedence level, from the loosest to the tightest.
	 */
	private static final List<BinaryOperatorUnderTest> BINARY_OPERATORS = List.of(
		new BinaryOperatorUnderTest("*?", 1, (left, right) -> new SpreadNullCoalesceOperator(false, left, right)),
		new BinaryOperatorUnderTest("?*?", 1, (left, right) -> new SpreadNullCoalesceOperator(true, left, right)),
		new BinaryOperatorUnderTest("||", 2, DisjunctionOperator::new),
		new BinaryOperatorUnderTest("&&", 3, ConjunctionOperator::new),
		new BinaryOperatorUnderTest("^", 4, XorOperator::new),
		new BinaryOperatorUnderTest("!=", 5, NotEqualsOperator::new),
		new BinaryOperatorUnderTest("==", 5, EqualsOperator::new),
		new BinaryOperatorUnderTest("<=", 6, LesserThanEqualsOperator::new),
		new BinaryOperatorUnderTest("<", 6, LesserThanOperator::new),
		new BinaryOperatorUnderTest(">=", 6, GreaterThanEqualsOperator::new),
		new BinaryOperatorUnderTest(">", 6, GreaterThanOperator::new),
		new BinaryOperatorUnderTest("??", 7, NullCoalesceOperator::new),
		new BinaryOperatorUnderTest("-", 8, SubtractionOperator::new),
		new BinaryOperatorUnderTest("+", 8, AdditionOperator::new),
		new BinaryOperatorUnderTest("%", 9, ModuloOperator::new),
		new BinaryOperatorUnderTest("/", 9, DivisionOperator::new),
		new BinaryOperatorUnderTest("*", 9, MultiplicationOperator::new)
	);
	/**
	 * Every prefix operator of the grammar. All of them bind tighter than any binary operator.
	 */
	private static final List<PrefixOperatorUnderTest> PREFIX_OPERATORS = List.of(
		new PrefixOperatorUnderTest("!", InverseOperator::new),
		new PrefixOperatorUnderTest("+", PositiveOperator::new),
		new PrefixOperatorUnderTest("-", NegativeOperator::new)
	);

	/**
	 * Returns every ordered pair of binary operators, a parent and the operator it groups.
	 */
	@Nonnull
	static Stream<Arguments> binaryOperatorPairs() {
		return BINARY_OPERATORS.stream()
			.flatMap(parent -> BINARY_OPERATORS.stream().map(child -> Arguments.of(parent, child)));
	}

	/**
	 * Returns every binary operator.
	 */
	@Nonnull
	static Stream<Arguments> binaryOperators() {
		return BINARY_OPERATORS.stream().map(Arguments::of);
	}

	/**
	 * Returns every prefix operator combined with every binary operator.
	 */
	@Nonnull
	static Stream<Arguments> prefixAndBinaryOperators() {
		return PREFIX_OPERATORS.stream()
			.flatMap(prefix -> BINARY_OPERATORS.stream().map(binary -> Arguments.of(prefix, binary)));
	}

	/**
	 * Returns every ordered pair of prefix operators, the outer and the inner one.
	 */
	@Nonnull
	static Stream<Arguments> prefixOperatorPairs() {
		return PREFIX_OPERATORS.stream()
			.flatMap(outer -> PREFIX_OPERATORS.stream().map(inner -> Arguments.of(outer, inner)));
	}

	/**
	 * Parses the expression and returns its root node.
	 */
	@Nonnull
	private static ExpressionNode parseRoot(@Nonnull String expression) {
		return ExpressionFactory.parse(expression).getOperand();
	}

	/**
	 * Returns the node wrapped in parentheses when `wrapped` is set, the way the parser represents them.
	 */
	@Nonnull
	private static ExpressionNode groupedIf(boolean wrapped, @Nonnull ExpressionNode node) {
		return wrapped ? new NestedOperator(node) : node;
	}

	/**
	 * Returns the access chain `.length()` the parser produces, to build object access over any base.
	 */
	@Nonnull
	private static ObjectOperationStep lengthAccessChain() {
		return assertInstanceOf(ObjectAccessOperator.class, parseRoot("$x.length()")).getAccessChain();
	}

	@Nested
	@DisplayName("keep the text of a parsed expression")
	class ParsedTextTest {

		@DisplayName("for two binary operators in a row, grouped as the grammar ranks them")
		@ParameterizedTest(name = "$a {0} $b {1} $c")
		@MethodSource("io.evitadb.api.query.expression.ExpressionTextGroupingTest#binaryOperatorPairs")
		void shouldKeepTextOfTwoBinaryOperatorsInRow(@Nonnull BinaryOperatorUnderTest first, @Nonnull BinaryOperatorUnderTest second) {
			final String text = "$a " + first.symbol() + " $b " + second.symbol() + " $c";
			final ExpressionNode parsed = parseRoot(text);

			assertEquals(text, parsed.toString());
			// a tighter second operator takes `$b` as its left operand, otherwise the first one is folded to the left
			final ExpressionNode expected = second.level() > first.level()
				? first.create(parseRoot("$a"), second.create(parseRoot("$b"), parseRoot("$c")))
				: second.create(first.create(parseRoot("$a"), parseRoot("$b")), parseRoot("$c"));
			assertEquals(expected, parsed);
		}

		@DisplayName("for explicit parentheses on either side")
		@ParameterizedTest(name = "{0} / {1}")
		@MethodSource("io.evitadb.api.query.expression.ExpressionTextGroupingTest#binaryOperatorPairs")
		void shouldKeepTextOfExplicitParentheses(@Nonnull BinaryOperatorUnderTest first, @Nonnull BinaryOperatorUnderTest second) {
			final String leftGrouped = "($a " + first.symbol() + " $b) " + second.symbol() + " $c";
			final String rightGrouped = "$a " + first.symbol() + " ($b " + second.symbol() + " $c)";

			assertEquals(leftGrouped, parseRoot(leftGrouped).toString());
			assertEquals(rightGrouped, parseRoot(rightGrouped).toString());
		}

		@DisplayName("for a prefix operator in front of another one")
		@ParameterizedTest(name = "{0}{1}$a")
		@MethodSource("io.evitadb.api.query.expression.ExpressionTextGroupingTest#prefixOperatorPairs")
		void shouldKeepTextOfPrefixInsidePrefix(@Nonnull PrefixOperatorUnderTest outer, @Nonnull PrefixOperatorUnderTest inner) {
			final String text = outer.symbol() + inner.symbol() + "$a";

			assertEquals(text, parseRoot(text).toString());
		}

		@DisplayName("for object access over a variable, a literal and a parenthesized expression")
		@Test
		void shouldKeepTextOfObjectAccess() {
			for (String text : List.of("$a.length() + $b", "'abc'.length()", "($a + $b).length()", "$a?.b.c", "$a.b * -$c.d")) {
				assertEquals(text, parseRoot(text).toString(), text);
			}
		}

	}

	@Nested
	@DisplayName("print a composed tree so that it parses back to itself")
	class ComposedTreeTest {

		@DisplayName("when a binary operator is the left operand of another one")
		@ParameterizedTest(name = "({1}) {0}")
		@MethodSource("io.evitadb.api.query.expression.ExpressionTextGroupingTest#binaryOperatorPairs")
		void shouldGroupLeftOperandOnlyWhenNeeded(@Nonnull BinaryOperatorUnderTest parent, @Nonnull BinaryOperatorUnderTest child) {
			final ExpressionNode composed = parent.create(child.create(parseRoot("$a"), parseRoot("$b")), parseRoot("$c"));

			// every operator is left-associative, so a left operand of the same level needs no parentheses
			final ExpressionNode expected = parent.create(
				groupedIf(child.level() < parent.level(), child.create(parseRoot("$a"), parseRoot("$b"))),
				parseRoot("$c")
			);
			assertEquals(expected, parseRoot(composed.toString()), composed.toString());
		}

		@DisplayName("when a binary operator is the right operand of another one")
		@ParameterizedTest(name = "{0} ({1})")
		@MethodSource("io.evitadb.api.query.expression.ExpressionTextGroupingTest#binaryOperatorPairs")
		void shouldGroupRightOperandOnlyWhenNeeded(@Nonnull BinaryOperatorUnderTest parent, @Nonnull BinaryOperatorUnderTest child) {
			final ExpressionNode composed = parent.create(parseRoot("$a"), child.create(parseRoot("$b"), parseRoot("$c")));

			// a right operand of the same level would be folded to the left without parentheses
			final ExpressionNode expected = parent.create(
				parseRoot("$a"),
				groupedIf(child.level() <= parent.level(), child.create(parseRoot("$b"), parseRoot("$c")))
			);
			assertEquals(expected, parseRoot(composed.toString()), composed.toString());
		}

		@DisplayName("when a binary operator is the operand of a prefix operator")
		@ParameterizedTest(name = "{0}({1})")
		@MethodSource("io.evitadb.api.query.expression.ExpressionTextGroupingTest#prefixAndBinaryOperators")
		void shouldGroupBinaryOperandOfPrefixOperator(@Nonnull PrefixOperatorUnderTest prefix, @Nonnull BinaryOperatorUnderTest binary) {
			final ExpressionNode composed = prefix.create(binary.create(parseRoot("$a"), parseRoot("$b")));

			assertEquals(prefix.symbol() + "($a " + binary.symbol() + " $b)", composed.toString());
			assertEquals(
				prefix.create(new NestedOperator(binary.create(parseRoot("$a"), parseRoot("$b")))),
				parseRoot(composed.toString())
			);
		}

		@DisplayName("when a prefix operator is the operand of another one, without parentheses")
		@ParameterizedTest(name = "{0}{1}$a")
		@MethodSource("io.evitadb.api.query.expression.ExpressionTextGroupingTest#prefixOperatorPairs")
		void shouldNotGroupPrefixOperandOfPrefixOperator(@Nonnull PrefixOperatorUnderTest outer, @Nonnull PrefixOperatorUnderTest inner) {
			final ExpressionNode composed = outer.create(inner.create(parseRoot("$a")));

			assertEquals(outer.symbol() + inner.symbol() + "$a", composed.toString());
			assertEquals(composed, parseRoot(composed.toString()));
		}

		@DisplayName("when the base of an object access is not a variable, a literal or a parenthesized expression")
		@Test
		void shouldGroupBaseOfObjectAccessOnlyWhenNeeded() {
			final ObjectOperationStep length = lengthAccessChain();
			final List<ExpressionNode> basesToGroup = List.of(
				parseRoot("$a + $b"),
				parseRoot("-$a"),
				parseRoot("abs($a)"),
				parseRoot("$a.b")
			);
			for (ExpressionNode base : basesToGroup) {
				final ExpressionNode composed = new ObjectAccessOperator(base, length);
				assertEquals("(" + base + ").length()", composed.toString());
				assertEquals(new ObjectAccessOperator(new NestedOperator(base), length), parseRoot(composed.toString()));
			}
			final List<ExpressionNode> basesToKeep = List.of(parseRoot("$a"), parseRoot("'abc'"), parseRoot("($a + $b)"));
			for (ExpressionNode base : basesToKeep) {
				final ExpressionNode composed = new ObjectAccessOperator(base, length);
				assertEquals(base + ".length()", composed.toString());
				assertEquals(composed, parseRoot(composed.toString()));
			}
		}

		@DisplayName("when an operand is a whole expression, printed without its quotes")
		@Test
		void shouldPrintExpressionOperandWithoutQuotes() {
			assertEquals(
				"$a && $b",
				new ConjunctionOperator(ExpressionFactory.parse("$a"), ExpressionFactory.parse("$b")).toString()
			);
			assertEquals(
				"($a || $b) && $c",
				new ConjunctionOperator(ExpressionFactory.parse("$a || $b"), ExpressionFactory.parse("$c")).toString()
			);
			assertEquals("!($a || $b)", new InverseOperator(ExpressionFactory.parse("$a || $b")).toString());
		}

	}

	@Nested
	@DisplayName("compose a conjunction that equals the parse of its own text")
	class ConjunctionTest {

		@DisplayName("for a disjunction on the left")
		@Test
		void shouldComposeConjunctionOverDisjunction() {
			final Expression composed = ExpressionFactory.and(
				ExpressionFactory.parse("$a || $b"), ExpressionFactory.parse("$c")
			);

			assertNotNull(composed);
			assertEquals("($a || $b) && $c", composed.toExpressionString());
			assertEquals(ExpressionFactory.parse(composed.toExpressionString()), composed);
		}

		@DisplayName("for operands of any level on both sides")
		@ParameterizedTest(name = "($a {0} $b) && ($c {0} $d)")
		@MethodSource("io.evitadb.api.query.expression.ExpressionTextGroupingTest#binaryOperators")
		void shouldComposeConjunctionOverAnyOperands(@Nonnull BinaryOperatorUnderTest operator) {
			final Expression composed = ExpressionFactory.and(
				ExpressionFactory.parse("$a " + operator.symbol() + " $b"),
				ExpressionFactory.parse("$c " + operator.symbol() + " $d")
			);

			assertNotNull(composed);
			assertEquals(ExpressionFactory.parse(composed.toExpressionString()), composed, composed.toExpressionString());
		}

	}

	/**
	 * A binary operator of the grammar: its symbol, its precedence level and how to build it.
	 *
	 * @param symbol  the operator as written in an expression
	 * @param level   the precedence level, higher binds tighter
	 * @param factory creates the operator over the left and the right operand
	 */
	record BinaryOperatorUnderTest(
		@Nonnull String symbol,
		int level,
		@Nonnull BinaryOperator<ExpressionNode> factory
	) {

		/**
		 * Creates the operator over the given operands.
		 */
		@Nonnull
		ExpressionNode create(@Nonnull ExpressionNode left, @Nonnull ExpressionNode right) {
			return this.factory.apply(left, right);
		}

		@Nonnull
		@Override
		public String toString() {
			return this.symbol;
		}

	}

	/**
	 * A prefix operator of the grammar: its symbol and how to build it.
	 *
	 * @param symbol  the operator as written in an expression
	 * @param factory creates the operator over its operand
	 */
	record PrefixOperatorUnderTest(
		@Nonnull String symbol,
		@Nonnull UnaryOperator<ExpressionNode> factory
	) {

		/**
		 * Creates the operator over the given operand.
		 */
		@Nonnull
		ExpressionNode create(@Nonnull ExpressionNode operand) {
			return this.factory.apply(operand);
		}

		@Nonnull
		@Override
		public String toString() {
			return this.symbol;
		}

	}

}
