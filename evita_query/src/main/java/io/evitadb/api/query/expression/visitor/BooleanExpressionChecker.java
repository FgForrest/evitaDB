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

import io.evitadb.api.query.expression.bool.BooleanOperator;
import io.evitadb.api.query.expression.utility.NestedOperator;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.dataType.expression.ExpressionNodeVisitor;
import io.evitadb.utils.Assert;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;

/**
 * Traverses an expression tree and checks whether the root node represents a boolean operation.
 * Boolean operations include comparison operators (`==`, `!=`, `>`, `>=`, `<`, `<=`) and
 * logical operators (`&&`, `||`, `^`, `!`). Wrapper nodes such as {@link Expression} and
 * {@link NestedOperator} are transparently unwrapped to reach the actual root operator.
 *
 * Usage example:
 *
 * ```
 * ExpressionNode node = ExpressionFactory.parse("1 > 2");
 * boolean result = BooleanExpressionChecker.isBooleanExpression(node); // true
 * ```
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class BooleanExpressionChecker implements ExpressionNodeVisitor {

	private boolean booleanExpression;

	/**
	 * Checks whether the given expression node represents a boolean expression at its root level.
	 * Wrapper nodes ({@link Expression}, {@link NestedOperator}) are unwrapped to find the actual
	 * root operator.
	 *
	 * @param expressionNode the root node of the expression tree to check
	 * @return `true` if the root operator is a boolean operation, `false` otherwise
	 */
	public static boolean isBooleanExpression(@Nonnull ExpressionNode expressionNode) {
		final BooleanExpressionChecker checker = new BooleanExpressionChecker();
		expressionNode.accept(checker);
		return checker.booleanExpression;
	}

	@Override
	public void visit(@Nonnull ExpressionNode node) {
		if (node instanceof Expression || node instanceof NestedOperator) {
			// unwrap wrapper nodes and check the inner node
			final ExpressionNode[] children = node.getChildren();
			Assert.isPremiseValid(
				children != null && children.length == 1,
				"Unsupported children length for node `" + node.getClass().getSimpleName() + "`."
			);
			children[0].accept(this);
		} else if (node instanceof BooleanOperator) {
			this.booleanExpression = true;
		}
		// everything else: booleanExpression stays false
	}
}
