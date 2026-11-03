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

import io.evitadb.api.query.expression.bool.InverseOperator;
import io.evitadb.api.query.expression.function.FunctionOperator;
import io.evitadb.api.query.expression.numeric.NegativeOperator;
import io.evitadb.api.query.expression.numeric.PositiveOperator;
import io.evitadb.api.query.expression.object.ObjectAccessOperator;
import io.evitadb.api.query.expression.operand.ConstantOperand;
import io.evitadb.api.query.expression.operand.VariableOperand;
import io.evitadb.api.query.expression.utility.NestedOperator;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.dataType.expression.ExpressionNode;

import javax.annotation.Nonnull;

/**
 * Precedence levels of the EvitaEL operators, and the rules that print an operand of an operator so that the text of
 * an expression tree parses back to the same tree.
 *
 * A parsed tree keeps its explicit parentheses as a {@link NestedOperator}, so it never needs more of them. A tree
 * composed in code has no node for the grouping it implies, and printing its operands side by side would let the
 * parser group them differently - `and(parse("$a || $b"), parse("$c"))` would read back as `$a || ($b && $c)`. The
 * printer therefore adds exactly the parentheses the grammar needs: none to a parsed tree, whose text stays unchanged,
 * and the implied ones to a composed tree.
 *
 * The levels copy the order of the alternatives of the `expression` rule in `EvitaEL.g4`. ANTLR gives every
 * alternative a level of its own and the first one binds tightest; the operators of one level share an alternative,
 * so `10 - 2 + 3` is `(10 - 2) + 3`, as in the conventional arithmetic. The levels have to change in the same commit
 * as the grammar, and `ExpressionTextGroupingTest` pins them. Every binary operator is left-associative.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class ExpressionPrecedence {
	/**
	 * Level of the spread null coalescing operators `*?` and `?*?`, the loosest of all.
	 */
	public static final int SPREAD_NULL_COALESCE = 1;
	/**
	 * Level of the disjunction `||`.
	 */
	public static final int DISJUNCTION = 2;
	/**
	 * Level of the conjunction `&&`.
	 */
	public static final int CONJUNCTION = 3;
	/**
	 * Level of the exclusive disjunction `^`.
	 */
	public static final int XOR = 4;
	/**
	 * Level of the equality operators `==` and `!=`.
	 */
	public static final int EQUALITY = 5;
	/**
	 * Level of the relational operators `>`, `>=`, `<` and `<=`.
	 */
	public static final int RELATIONAL = 6;
	/**
	 * Level of the null coalescing operator `??`.
	 */
	public static final int NULL_COALESCE = 7;
	/**
	 * Level of the additive operators `+` and `-`.
	 */
	public static final int ADDITIVE = 8;
	/**
	 * Level of the multiplicative operators `*`, `/` and `%`, the tightest of the binary operators.
	 */
	public static final int MULTIPLICATIVE = 9;
	/**
	 * Level of the prefix operators `!`, `+` and `-`, which bind tighter than any binary operator. The grammar ranks
	 * them among themselves too, but a prefix operator parses its operand above all three, so one prefix operator
	 * never needs parentheses inside another.
	 */
	public static final int PREFIX = 10;
	/**
	 * Level of the nodes that never need parentheses: operands, function calls, object access and the parentheses
	 * themselves.
	 */
	public static final int PRIMARY = Integer.MAX_VALUE;
	/**
	 * Level of a node this class does not know. Such a node is always parenthesized, which is safe because
	 * parentheses never change the meaning of an expression.
	 */
	private static final int UNKNOWN = 0;

	private ExpressionPrecedence() {
		// utility class
	}

	/**
	 * Returns the precedence level of the node; a higher level binds tighter. An {@link Expression} has the level of
	 * its root.
	 *
	 * @param node the node to rank
	 * @return the level of the node
	 */
	public static int of(@Nonnull ExpressionNode node) {
		if (node instanceof Expression expression) {
			return of(expression.getOperand());
		} else if (node instanceof AbstractBinaryOperator binaryOperator) {
			return binaryOperator.getPrecedence();
		} else if (node instanceof InverseOperator || node instanceof PositiveOperator || node instanceof NegativeOperator) {
			return PREFIX;
		} else if (
			node instanceof ConstantOperand || node instanceof VariableOperand || node instanceof NestedOperator ||
				node instanceof FunctionOperator || node instanceof ObjectAccessOperator
		) {
			return PRIMARY;
		} else {
			return UNKNOWN;
		}
	}

	/**
	 * Returns whether the node needs parentheses as the left operand of a binary operator of the given level. A left
	 * operand of the same level needs none, because every binary operator is left-associative.
	 *
	 * @param operand            the left operand
	 * @param operatorPrecedence the level of the binary operator
	 * @return true when the operand has to be parenthesized
	 */
	public static boolean needsParenthesesAsLeftOperand(@Nonnull ExpressionNode operand, int operatorPrecedence) {
		return of(operand) < operatorPrecedence;
	}

	/**
	 * Returns whether the node needs parentheses as the right operand of a binary operator of the given level. A right
	 * operand of the same level needs them, because without them the parser would fold it to the left.
	 *
	 * @param operand            the right operand
	 * @param operatorPrecedence the level of the binary operator
	 * @return true when the operand has to be parenthesized
	 */
	public static boolean needsParenthesesAsRightOperand(@Nonnull ExpressionNode operand, int operatorPrecedence) {
		return of(operand) <= operatorPrecedence;
	}

	/**
	 * Returns the text of the left operand of a binary operator of the given level, parenthesized where needed.
	 *
	 * @param operand            the left operand
	 * @param operatorPrecedence the level of the binary operator
	 * @return the operand text
	 */
	@Nonnull
	public static String leftOperandText(@Nonnull ExpressionNode operand, int operatorPrecedence) {
		return textOf(operand, needsParenthesesAsLeftOperand(operand, operatorPrecedence));
	}

	/**
	 * Returns the text of the right operand of a binary operator of the given level, parenthesized where needed.
	 *
	 * @param operand            the right operand
	 * @param operatorPrecedence the level of the binary operator
	 * @return the operand text
	 */
	@Nonnull
	public static String rightOperandText(@Nonnull ExpressionNode operand, int operatorPrecedence) {
		return textOf(operand, needsParenthesesAsRightOperand(operand, operatorPrecedence));
	}

	/**
	 * Returns the text of the operand of a prefix operator, parenthesized when the operand binds looser than a prefix
	 * operator - which is every binary operator.
	 *
	 * @param operand the operand of the prefix operator
	 * @return the operand text
	 */
	@Nonnull
	public static String prefixOperandText(@Nonnull ExpressionNode operand) {
		return textOf(operand, of(operand) < PREFIX);
	}

	/**
	 * Returns the text of the base of an object access, parenthesized unless the base is one the grammar accepts as
	 * is: a literal, a variable or a parenthesized expression. Everything else - a function call or another object
	 * access included - would otherwise read back with the access applied to a different node.
	 *
	 * @param base the base the access chain is applied to
	 * @return the base text
	 */
	@Nonnull
	public static String objectAccessBaseText(@Nonnull ExpressionNode base) {
		final ExpressionNode baseNode = base instanceof Expression expression ? expression.getOperand() : base;
		final boolean acceptedAsIs = baseNode instanceof ConstantOperand ||
			baseNode instanceof VariableOperand ||
			baseNode instanceof NestedOperator;
		return textOf(base, !acceptedAsIs);
	}

	/**
	 * Returns the operand wrapped in a {@link NestedOperator} when it needs parentheses as the left operand of a binary
	 * operator of the given level, otherwise the operand itself. The result is the node the parser would produce for
	 * the operand in that position.
	 *
	 * @param operand            the left operand
	 * @param operatorPrecedence the level of the binary operator
	 * @return the operand, grouped where needed
	 */
	@Nonnull
	public static ExpressionNode groupedAsLeftOperand(@Nonnull ExpressionNode operand, int operatorPrecedence) {
		return needsParenthesesAsLeftOperand(operand, operatorPrecedence) ? new NestedOperator(operand) : operand;
	}

	/**
	 * Returns the operand wrapped in a {@link NestedOperator} when it needs parentheses as the right operand of a
	 * binary operator of the given level, otherwise the operand itself. The result is the node the parser would
	 * produce for the operand in that position.
	 *
	 * @param operand            the right operand
	 * @param operatorPrecedence the level of the binary operator
	 * @return the operand, grouped where needed
	 */
	@Nonnull
	public static ExpressionNode groupedAsRightOperand(@Nonnull ExpressionNode operand, int operatorPrecedence) {
		return needsParenthesesAsRightOperand(operand, operatorPrecedence) ? new NestedOperator(operand) : operand;
	}

	/**
	 * Returns the text of the node, parenthesized when asked. An {@link Expression} prints its raw text, because its
	 * own {@link Expression#toString()} quotes it as a value.
	 *
	 * @param node          the node to print
	 * @param parenthesized whether to wrap the text in parentheses
	 * @return the node text
	 */
	@Nonnull
	private static String textOf(@Nonnull ExpressionNode node, boolean parenthesized) {
		final String text = node instanceof Expression expression ? expression.toExpressionString() : node.toString();
		return parenthesized ? "(" + text + ")" : text;
	}

}
