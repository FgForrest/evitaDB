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

package io.evitadb.api.query.expression.parser.visitor;

import io.evitadb.api.query.expression.parser.grammar.ExpressionBaseVisitor;
import io.evitadb.api.query.expression.parser.grammar.ExpressionParser.*;
import io.evitadb.api.query.expression.parser.visitor.operators.ExpressionNode;
import io.evitadb.api.query.expression.parser.visitor.operators.boolOperator.ConjunctionOperator;
import io.evitadb.api.query.expression.parser.visitor.operators.boolOperator.DisjunctionOperator;
import io.evitadb.api.query.expression.parser.visitor.operators.boolOperator.EqualsOperator;
import io.evitadb.api.query.expression.parser.visitor.operators.boolOperator.GreaterThanEqualsOperator;
import io.evitadb.api.query.expression.parser.visitor.operators.boolOperator.GreaterThanOperator;
import io.evitadb.api.query.expression.parser.visitor.operators.boolOperator.InverseOperator;
import io.evitadb.api.query.expression.parser.visitor.operators.boolOperator.LesserThanEqualsOperator;
import io.evitadb.api.query.expression.parser.visitor.operators.boolOperator.LesserThanOperator;
import io.evitadb.api.query.expression.parser.visitor.operators.boolOperator.NotEqualsOperator;
import io.evitadb.api.query.expression.parser.visitor.operators.numericOperator.*;
import io.evitadb.api.query.expression.parser.visitor.operators.operand.ConstantOperand;
import io.evitadb.api.query.expression.parser.visitor.operators.operand.RandomOperand;
import io.evitadb.api.query.expression.parser.visitor.operators.operand.VariableOperand;
import io.evitadb.utils.StringUtils;

import java.math.BigDecimal;

/**
 * Implementation of the {@link io.evitadb.api.query.expression.parser.grammar.ExpressionVisitor} for parsing
 * expression into {@link ExpressionNode} object.
 *
 * @author Lukáš Hornych, 2024
 */
public class DefaultExpressionVisitor extends ExpressionBaseVisitor<ExpressionNode> {

	@Override
	public ExpressionNode visitGreaterThanExpression(GreaterThanExpressionContext ctx) {
		return new GreaterThanOperator(
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
	public ExpressionNode visitOrExpression(OrExpressionContext ctx) {
		return new DisjunctionOperator(
			ctx.expression()
				.stream()
				.map(it -> it.accept(this))
				.toArray(ExpressionNode[]::new)
		);
	}

	@Override
	public ExpressionNode visitStandaloneExpression(StandaloneExpressionContext ctx) {
		return ctx.atom().accept(this);
	}

	@Override
	public ExpressionNode visitNestedExpression(NestedExpressionContext ctx) {
		return ctx.expression().accept(this);
	}

	@Override
	public ExpressionNode visitNegatingExpression(NegatingExpressionContext ctx) {
		return new InverseOperator(ctx.expression().accept(this));
	}

	@Override
	public ExpressionNode visitNotEqualsExpression(NotEqualsExpressionContext ctx) {
		return new NotEqualsOperator(
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
	public ExpressionNode visitAndExpression(AndExpressionContext ctx) {
		return new ConjunctionOperator(
			ctx.expression()
				.stream()
				.map(it -> it.accept(this))
				.toArray(ExpressionNode[]::new)
		);
	}

	@Override
	public ExpressionNode visitPlusExpression(PlusExpressionContext ctx) {
		if (ctx.getChildCount() == 1) {
			return ctx.multiplyingExpression(0).accept(this);
		} else {
			return new AddingOperator(
				ctx.multiplyingExpression()
					.stream()
					.map(it -> it.accept(this))
					.toArray(ExpressionNode[]::new)
			);
		}
	}

	@Override
	public ExpressionNode visitMinusExpression(MinusExpressionContext ctx) {
		if (ctx.getChildCount() == 1) {
			return ctx.multiplyingExpression(0).accept(this);
		} else {
			return new SubtractionOperator(
				ctx.multiplyingExpression()
					.stream()
					.map(it -> it.accept(this))
					.toArray(ExpressionNode[]::new)
			);
		}
	}

	@Override
	public ExpressionNode visitTimesExpression(TimesExpressionContext ctx) {
		if (ctx.getChildCount() == 1) {
			return ctx.powExpression(0).accept(this);
		} else {
			return new MultiplyOperator(
				ctx.powExpression()
					.stream()
					.map(it -> it.accept(this))
					.toArray(ExpressionNode[]::new)
			);
		}
	}

	@Override
	public ExpressionNode visitDivExpression(DivExpressionContext ctx) {
		return new DivisionOperator(
			ctx.powExpression()
				.stream()
				.map(it -> it.accept(this))
				.toArray(ExpressionNode[]::new)
		);
	}

	@Override
	public ExpressionNode visitModExpression(ModExpressionContext ctx) {
		return new ModOperator(
			ctx.powExpression()
				.stream()
				.map(it -> it.accept(this))
				.toArray(ExpressionNode[]::new)
		);
	}

	@Override
	public ExpressionNode visitPowExpression(PowExpressionContext ctx) {
		if (ctx.getChildCount() == 1) {
			return ctx.signedAtom(0).accept(this);
		} else {
			return new PowOperator(
				ctx.signedAtom()
					.stream()
					.map(it -> it.accept(this))
					.toArray(ExpressionNode[]::new)
			);
		}
	}

	@Override
	public ExpressionNode visitPositiveSignedAtom(PositiveSignedAtomContext ctx) {
		return ctx.signedAtom().accept(this);
	}

	@Override
	public ExpressionNode visitNegativeSignedAtom(NegativeSignedAtomContext ctx) {
		return new NegatingOperator(ctx.signedAtom().accept(this));
	}

	@Override
	public ExpressionNode visitFunctionSignedAtom(FunctionSignedAtomContext ctx) {
		return ctx.function().accept(this);
	}

	@Override
	public ExpressionNode visitBaseSignedAtom(BaseSignedAtomContext ctx) {
		return ctx.atom().accept(this);
	}

	@Override
	public ExpressionNode visitVariableAtom(VariableAtomContext ctx) {
		return ctx.variable().accept(this);
	}

	@Override
	public ExpressionNode visitValueAtom(ValueAtomContext ctx) {
		return ctx.valueToken().accept(this);
	}

	@Override
	public ExpressionNode visitExpressionAtom(ExpressionAtomContext ctx) {
		return ctx.combinationExpression().accept(this);
	}

	@Override
	public ExpressionNode visitBooleanNegatingExpression(BooleanNegatingExpressionContext ctx) {
		return new InverseOperator(new ConstantOperand(Boolean.parseBoolean(ctx.BOOLEAN().getText())));
	}

	@Override
	public ExpressionNode visitFunctionExpression(FunctionExpressionContext ctx) {
		return ctx.function().accept(this);
	}

	@Override
	public ExpressionNode visitStandaloneCombinationExpression(StandaloneCombinationExpressionContext ctx) {
		return ctx.combinationExpression().accept(this);
	}

	@Override
	public ExpressionNode visitVariable(VariableContext ctx) {
		return new VariableOperand(ctx.getText().substring(1));
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

	@Override
	public ExpressionNode visitSqrtFunction(SqrtFunctionContext ctx) {
		return new SqrtOperator(ctx.combinationExpression().accept(this));
	}

	@Override
	public ExpressionNode visitCeilFunction(CeilFunctionContext ctx) {
		return new CeilOperator(ctx.combinationExpression().accept(this));
	}

	@Override
	public ExpressionNode visitFloorFunction(FloorFunctionContext ctx) {
		return new FloorOperator(ctx.combinationExpression().accept(this));
	}

	@Override
	public ExpressionNode visitRandomIntFunction(RandomIntFunctionContext ctx) {
		return new RandomOperand(
			ctx.combinationExpression() == null ?
				null : ctx.combinationExpression().accept(this)
		);
	}

}
