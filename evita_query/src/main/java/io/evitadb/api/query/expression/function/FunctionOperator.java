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

package io.evitadb.api.query.expression.function;

import io.evitadb.api.query.expression.function.processor.FunctionProcessor;
import io.evitadb.api.query.expression.function.processor.NumericFunctionProcessor;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.dataType.expression.ExpressionEvaluationContext;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.dataType.expression.ExpressionNodeVisitor;
import io.evitadb.exception.ExpressionEvaluationException;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * An {@link ExpressionNode} that delegates computation to a {@link FunctionProcessor}. During
 * evaluation, it computes all argument operands and passes the resulting values to the function
 * processor via {@link FunctionProcessor#process(List)}.
 *
 * For numeric functions (when the processor is a {@link NumericFunctionProcessor}), the operator
 * also delegates possible range determination to
 * {@link NumericFunctionProcessor#determinePossibleRange(List)}, allowing the expression engine
 * to reason about the value bounds of the function result.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class FunctionOperator implements ExpressionNode {

	@Serial private static final long serialVersionUID = -7377835303003418997L;

	@Nonnull private final FunctionProcessor functionProcessor;
	@Nonnull private final List<ExpressionNode> argumentOperands;
	@Getter
	private final ExpressionNode[] children;

	public FunctionOperator(
		@Nonnull FunctionProcessor functionProcessor,
		@Nonnull List<ExpressionNode> argumentOperands
	) {
		this.functionProcessor = functionProcessor;
		this.argumentOperands = argumentOperands;
		this.children = this.argumentOperands.toArray(new ExpressionNode[0]);
	}

	@Nullable
	@Override
	public Serializable compute(@Nonnull ExpressionEvaluationContext context) throws ExpressionEvaluationException {
		final List<Serializable> arguments = this.argumentOperands.stream()
			.map(operand -> operand.compute(context))
			.toList();
		return this.functionProcessor.process(arguments);
	}

	@Nonnull
	@Override
	public BigDecimalNumberRange determinePossibleRange() throws UnsupportedDataTypeException {
		if (this.functionProcessor instanceof NumericFunctionProcessor numericFunctionProcessor) {
			return numericFunctionProcessor.determinePossibleRange(
				this.argumentOperands.stream()
					.map(ExpressionNode::determinePossibleRange)
					.toList()
			);
		} else {
			return BigDecimalNumberRange.INFINITE;
		}
	}

	@Override
	public void accept(@Nonnull ExpressionNodeVisitor visitor) {
		visitor.visit(this);
	}

	@Override
	public String toString() {
		return this.functionProcessor.getName() + "(" +
			this.argumentOperands.stream()
				.map(ExpressionNode::toString)
				.collect(java.util.stream.Collectors.joining(", ")) +
			")";
	}
}
