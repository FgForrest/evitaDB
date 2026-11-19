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

import io.evitadb.api.query.expression.coalesce.NullCoalesceOperator;
import io.evitadb.api.query.expression.coalesce.SpreadNullCoalesceOperator;
import io.evitadb.api.query.expression.function.FunctionOperator;
import io.evitadb.api.query.expression.object.ElementAccessStep;
import io.evitadb.api.query.expression.object.MethodInvocationStep;
import io.evitadb.api.query.expression.object.ObjectAccessOperator;
import io.evitadb.api.query.expression.object.ObjectOperationStep;
import io.evitadb.api.query.expression.object.SpreadAccessStep;
import io.evitadb.api.query.expression.operand.VariableOperand;
import io.evitadb.api.query.expression.utility.NestedOperator;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.dataType.expression.ExpressionNodeVisitor;
import io.evitadb.dataType.expression.UnaryExpressionNode;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks that an expression is fit to **select text** from a stored value - the extraction expression of searchable
 * associated data, which runs on the write path for every value it indexes and again for every value it removes.
 *
 * What the type of the result will be cannot be decided before the expression meets a value - the shape of a stored
 * complex data object is not part of any schema - so the check is structural. It reports:
 *
 * - **a variable other than the root one.** The only variable bound at evaluation is the stored value; the bare `$`
 *   of a spread or a predicate is always allowed;
 * - **any function call.** The built-in functions compute numbers, which a text selection has no use for, and
 *   `random()` would make the extraction non-deterministic - removing a value re-extracts it, and must find exactly
 *   what adding it found;
 * - **a root that cannot produce text**: a boolean or a numeric operation, a constant, a chain ending with a method
 *   call (`size()`, `any(...)`). The root must navigate the value - an object access, a null coalescence of such, or
 *   the root variable itself.
 *
 * Usage:
 *
 * ```
 * final List<String> problems = TextExpressionChecker.findProblems(ExpressionFactory.parse("$value.title"), "value");
 * ```
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class TextExpressionChecker implements ExpressionNodeVisitor {

	/**
	 * Name of the only variable the expression may reference.
	 */
	@Nonnull private final String rootVariableName;
	/**
	 * Problems found so far, without duplicates and in the order they were found.
	 */
	@Nonnull private final Set<String> problems = new LinkedHashSet<>(4);

	/**
	 * Creates a checker allowing the passed variable.
	 *
	 * @param rootVariableName name of the only variable the expression may reference
	 */
	private TextExpressionChecker(@Nonnull String rootVariableName) {
		this.rootVariableName = rootVariableName;
	}

	/**
	 * Returns the problems that make `expression` unfit to select text from a value bound to `rootVariableName`.
	 *
	 * @param expression       the expression to check
	 * @param rootVariableName name of the variable the stored value is bound to, without the `$`
	 * @return one message per problem, empty when the expression is fit
	 */
	@Nonnull
	public static List<String> findProblems(@Nonnull ExpressionNode expression, @Nonnull String rootVariableName) {
		final TextExpressionChecker checker = new TextExpressionChecker(rootVariableName);
		checker.checkRoot(expression);
		expression.accept(checker);
		return new ArrayList<>(checker.problems);
	}

	@Override
	public void visit(@Nonnull ExpressionNode node) {
		if (node instanceof FunctionOperator) {
			this.problems.add(
				"Function `" + node + "` cannot be used: a text selection only navigates the stored value."
			);
		} else if (node instanceof VariableOperand variableOperand) {
			if (!variableOperand.isThis() && !this.rootVariableName.equals(variableOperand.getVariableName())) {
				this.problems.add(
					"Variable `$" + variableOperand.getVariableName() + "` is not available, only `$" +
						this.rootVariableName + "` (the stored value) and `$` (the item of a spread) are."
				);
			}
		} else if (node instanceof ObjectAccessOperator objectAccessOperator) {
			objectAccessOperator.getOperand().accept(this);
			ObjectOperationStep step = objectAccessOperator.getAccessChain();
			while (step != null) {
				if (step instanceof ElementAccessStep elementAccessStep) {
					elementAccessStep.getElementIdentifierOperand().accept(this);
				} else if (step instanceof SpreadAccessStep spreadAccessStep) {
					spreadAccessStep.getMappingExpression().accept(this);
				} else if (step instanceof MethodInvocationStep methodInvocationStep) {
					for (final ExpressionNode argument : methodInvocationStep.getArgumentOperands()) {
						argument.accept(this);
					}
				}
				// property access and null-safe steps carry no nested expression
				step = step.getNext();
			}
		} else {
			final ExpressionNode[] children = node.getChildren();
			if (children != null) {
				for (final ExpressionNode child : children) {
					child.accept(this);
				}
			}
		}
	}

	/**
	 * Checks the shape of the root operator, wrapper nodes ({@link Expression}, {@link NestedOperator}) unwrapped.
	 *
	 * @param expression the whole expression
	 */
	private void checkRoot(@Nonnull ExpressionNode expression) {
		ExpressionNode root = expression;
		while (root instanceof Expression || root instanceof NestedOperator) {
			root = ((UnaryExpressionNode) root).getOperand();
		}
		final boolean navigates;
		if (root instanceof ObjectAccessOperator objectAccessOperator) {
			// a chain ending with a method call computes a size or a boolean, never text
			ObjectOperationStep step = objectAccessOperator.getAccessChain();
			while (step.getNext() != null) {
				step = step.getNext();
			}
			navigates = !(step instanceof MethodInvocationStep);
		} else {
			navigates = root instanceof NullCoalesceOperator ||
				root instanceof SpreadNullCoalesceOperator ||
				(root instanceof VariableOperand variableOperand && !variableOperand.isThis());
		}
		if (!navigates) {
			this.problems.add(
				"Expression `" + expression + "` cannot produce text: its root must navigate the stored value " +
					"(e.g. `$" + this.rootVariableName + ".title` or `$" + this.rootVariableName +
					".blocks.*[$.text]`), not compute " +
					(BooleanExpressionChecker.isBooleanExpression(expression) ? "a boolean." : "a value of its own.")
			);
		}
	}

}
