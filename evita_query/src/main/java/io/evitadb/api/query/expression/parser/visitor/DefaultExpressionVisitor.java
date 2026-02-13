/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.api.query.expression.parser.visitor;

import io.evitadb.api.query.expression.bool.*;
import io.evitadb.api.query.expression.function.processor.FunctionProcessor;
import io.evitadb.api.query.expression.function.processor.FunctionProcessorRegistry;
import io.evitadb.api.query.expression.exception.ParserException;
import io.evitadb.api.query.expression.parser.grammar.EvitaELBaseVisitor;
import io.evitadb.api.query.expression.parser.grammar.EvitaELParser.*;
import io.evitadb.api.query.expression.function.FunctionOperator;
import io.evitadb.api.query.expression.coalesce.NullCoalesceOperator;
import io.evitadb.api.query.expression.coalesce.SpreadNullCoalesceOperator;
import io.evitadb.api.query.expression.numeric.AdditionOperator;
import io.evitadb.api.query.expression.numeric.DivisionOperator;
import io.evitadb.api.query.expression.numeric.ModuloOperator;
import io.evitadb.api.query.expression.numeric.MultiplicationOperator;
import io.evitadb.api.query.expression.numeric.NegativeOperator;
import io.evitadb.api.query.expression.numeric.PositiveOperator;
import io.evitadb.api.query.expression.numeric.SubtractionOperator;
import io.evitadb.api.query.expression.object.ElementAccessStep;
import io.evitadb.api.query.expression.object.ObjectAccessOperator;
import io.evitadb.api.query.expression.object.ObjectAccessStep;
import io.evitadb.api.query.expression.object.NullSafeAccessStep;
import io.evitadb.api.query.expression.object.PropertyAccessStep;
import io.evitadb.api.query.expression.object.SpreadAccessStep;
import io.evitadb.api.query.expression.operand.ConstantOperand;
import io.evitadb.api.query.expression.operand.VariableOperand;
import io.evitadb.api.query.expression.utility.NestedOperator;
import io.evitadb.dataType.expression.ExpressionNode;
import io.evitadb.exception.ExpressionEvaluationException;
import io.evitadb.utils.StringUtils;
import org.antlr.v4.runtime.tree.ParseTree;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Implementation of the {@link io.evitadb.api.query.expression.parser.grammar.EvitaELVisitor} for parsing
 * expression into {@link ExpressionNode} object.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2024
 */
public class DefaultExpressionVisitor extends EvitaELBaseVisitor<ExpressionNode> {

	@Override
	public ExpressionNode visitLiteralExpression(LiteralExpressionContext ctx) {
		return ctx.literal().accept(this);
	}

	@Override
	public ExpressionNode visitVariableExpression(VariableExpressionContext ctx) {
		return ctx.variable().accept(this);
	}

	@Override
	public ExpressionNode visitFunctionExpression(FunctionExpressionContext ctx) {
		final String functionName = ctx.functionName.getText();
		final FunctionProcessorRegistry registry = FunctionProcessorRegistry.getInstance();
		final Optional<FunctionProcessor> functionProcessor = registry.getFunctionProcessor(functionName);

		if (functionProcessor.isEmpty()) {
			throw new ExpressionEvaluationException("Unknown function `" + functionName + "`.");
		}
		return new FunctionOperator(
			functionProcessor.get(),
			(ctx.arguments == null)
				? List.of()
				: ctx.arguments.stream().map(it -> it.accept(this)).toList()
		);
	}

	@Override
	public ExpressionNode visitNestedExpression(NestedExpressionContext ctx) {
		return new NestedOperator(ctx.nested.accept(this));
	}

	@Override
	public ExpressionNode visitObjectAccessExpression(ObjectAccessExpressionContext ctx) {
		final ExpressionNode operand = ctx.operand.accept(this);

		final int childrenCount = ctx.getChildCount();
		ObjectAccessStep accessChain = null;
		for (int i = childrenCount - 1; i >= 1; i--) {
			final ParseTree child = ctx.getChild(i);
			if (child instanceof PropertyAccessExpressionContext propertyAccess) {
				final PropertyAccessStep step = new PropertyAccessStep(
					propertyAccess.propertyIdentifier.getText(),
					accessChain
				);

				if (propertyAccess.nullSafe != null) {
					accessChain = new NullSafeAccessStep(step);
				} else {
					accessChain = step;
				}
			} else if (child instanceof ElementAccessExpressionContext elementAccess) {
				final ElementAccessStep step = new ElementAccessStep(
					elementAccess.elementIdentifier.accept(this),
					accessChain
				);

				if (elementAccess.nullSafe != null) {
					accessChain = new NullSafeAccessStep(step);
				} else {
					accessChain = step;
				}
			} else if (child instanceof SpreadAccessExpressionContext spreadAccess) {
				final SpreadAccessStep step = new SpreadAccessStep(
					spreadAccess.itemAccessExpression.accept(this),
					spreadAccess.compact != null,
					accessChain
				);

				if (spreadAccess.nullSafe != null) {
					accessChain = new NullSafeAccessStep(step);
				} else {
					accessChain = step;
				}
			} else {
				throw new ExpressionEvaluationException("Unsupported access operator.");
			}
		}

		if (accessChain == null) {
			throw new ParserException("Access expression parts cannot be null.");
		}
		return new ObjectAccessOperator(operand, accessChain);
	}

	@Override
	public ExpressionNode visitNegatingExpression(NegatingExpressionContext ctx) {
		return new InverseOperator(ctx.nested.accept(this));
	}

	@Override
	public ExpressionNode visitPositiveExpression(PositiveExpressionContext ctx) {
		return new PositiveOperator(ctx.nested.accept(this));
	}

	@Override
	public ExpressionNode visitNegativeExpression(NegativeExpressionContext ctx) {
		return new NegativeOperator(ctx.nested.accept(this));
	}

	@Override
	public ExpressionNode visitMultiplicationExpression(MultiplicationExpressionContext ctx) {
		return new MultiplicationOperator(
			ctx.leftExpression.accept(this),
			ctx.rightExpression.accept(this)
		);
	}

	@Override
	public ExpressionNode visitDivisionExpression(DivisionExpressionContext ctx) {
		return new DivisionOperator(
			ctx.leftExpression.accept(this),
			ctx.rightExpression.accept(this)
		);
	}

	@Override
	public ExpressionNode visitModuloExpression(ModuloExpressionContext ctx) {
		return new ModuloOperator(
			ctx.leftExpression.accept(this),
			ctx.rightExpression.accept(this)
		);
	}

	@Override
	public ExpressionNode visitAdditionExpression(AdditionExpressionContext ctx) {
		return new AdditionOperator(
			ctx.leftExpression.accept(this),
			ctx.rightExpression.accept(this)
		);
	}

	@Override
	public ExpressionNode visitSubstractionExpression(SubstractionExpressionContext ctx) {
		return new SubtractionOperator(
			ctx.leftExpression.accept(this),
			ctx.rightExpression.accept(this)
		);
	}

	@Override
	public ExpressionNode visitGreaterThanExpression(GreaterThanExpressionContext ctx) {
		return new GreaterThanOperator(
			ctx.leftExpression.accept(this),
			ctx.rightExpression.accept(this)
		);
	}

	@Override
	public ExpressionNode visitLessThanExpression(LessThanExpressionContext ctx) {
		return new LesserThanOperator(
			ctx.leftExpression.accept(this),
			ctx.rightExpression.accept(this)
		);
	}

	@Override
	public ExpressionNode visitLessThanEqualsExpression(LessThanEqualsExpressionContext ctx) {
		return new LesserThanEqualsOperator(
			ctx.leftExpression.accept(this),
			ctx.rightExpression.accept(this)
		);
	}

	@Override
	public ExpressionNode visitGreaterThanEqualsExpression(GreaterThanEqualsExpressionContext ctx) {
		return new GreaterThanEqualsOperator(
			ctx.leftExpression.accept(this),
			ctx.rightExpression.accept(this)
		);
	}

	@Override
	public ExpressionNode visitEqualsExpression(EqualsExpressionContext ctx) {
		return new EqualsOperator(
			ctx.leftExpression.accept(this),
			ctx.rightExpression.accept(this)
		);
	}

	@Override
	public ExpressionNode visitNotEqualsExpression(NotEqualsExpressionContext ctx) {
		return new NotEqualsOperator(
			ctx.leftExpression.accept(this),
			ctx.rightExpression.accept(this)
		);
	}

	@Override
	public ExpressionNode visitXorExpression(XorExpressionContext ctx) {
		return new XorOperator(
			ctx.leftExpression.accept(this),
			ctx.rightExpression.accept(this)
		);
	}

	@Override
	public ExpressionNode visitAndExpression(AndExpressionContext ctx) {
		return new ConjunctionOperator(
			ctx.leftExpression.accept(this),
			ctx.rightExpression.accept(this)
		);
	}

	@Override
	public ExpressionNode visitOrExpression(OrExpressionContext ctx) {
		return new DisjunctionOperator(
			ctx.leftExpression.accept(this),
			ctx.rightExpression.accept(this)
		);
	}

	@Override
	public ExpressionNode visitSpreadNullCoalesceExpression(SpreadNullCoalesceExpressionContext ctx) {
		return new SpreadNullCoalesceOperator(
			ctx.nullSafe != null,
			ctx.value.accept(this),
			ctx.defaultValue.accept(this)
		);
	}

	@Override
	public ExpressionNode visitNullCoalesceExpression(NullCoalesceExpressionContext ctx) {
		return new NullCoalesceOperator(
			ctx.value.accept(this),
			ctx.defaultValue.accept(this)
		);
	}

	@Override
	public ExpressionNode visitLiteralCallOperand(LiteralCallOperandContext ctx) {
		return ctx.literal().accept(this);
	}

	@Override
	public ExpressionNode visitVariableCallOperand(VariableCallOperandContext ctx) {
		return ctx.variable().accept(this);
	}

	@Override
	public ExpressionNode visitNestedExpressionCallOperand(NestedExpressionCallOperandContext ctx) {
		return new NestedOperator(ctx.nested.accept(this));
	}

	@Override
	public ExpressionNode visitElementAccessExpression(ElementAccessExpressionContext ctx) {
		throw new ExpressionEvaluationException(
			"Unsupported element access operation. Cannot process standalone element access expression.",
			"Unsupported element access operation."
		);
	}

	@Override
	public ExpressionNode visitPropertyAccessExpression(PropertyAccessExpressionContext ctx) {
		throw new ExpressionEvaluationException(
			"Unsupported property access operation. Cannot process standalone property access expression.",
			"Unsupported property access operation."
		);
	}

	@Override
	public ExpressionNode visitSpreadAccessExpression(SpreadAccessExpressionContext ctx) {
		throw new ExpressionEvaluationException(
			"Unsupported spread access operation. Cannot process standalone spread access expression.",
			"Unsupported spread access operation."
		);
	}

	@Override
	public ExpressionNode visitVariable(VariableContext ctx) {
		final String variableName = ctx.getText().substring(1);
		if (variableName.isBlank()) {
			return new VariableOperand(null);
		} else {
			return new VariableOperand(variableName);
		}
	}

	@Override
	public ExpressionNode visitStringValueToken(StringValueTokenContext ctx) {
		return new ConstantOperand(StringUtils.translateEscapes(ctx.getText().substring(1, ctx.getText().length() - 1)));
	}

	@Override
	public ExpressionNode visitIntValueToken(IntValueTokenContext ctx) {
		return new ConstantOperand(Long.valueOf(ctx.getText()));
	}

	@Override
	public ExpressionNode visitFloatValueToken(FloatValueTokenContext ctx) {
		return new ConstantOperand(new BigDecimal(ctx.getText()));
	}

	@Override
	public ExpressionNode visitBooleanValueToken(BooleanValueTokenContext ctx) {
		return new ConstantOperand(Boolean.parseBoolean(ctx.getText()));
	}

}
