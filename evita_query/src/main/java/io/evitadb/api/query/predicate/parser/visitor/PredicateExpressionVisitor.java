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

package io.evitadb.api.query.predicate.parser.visitor;

import io.evitadb.api.query.predicate.parser.ParseContext;
import io.evitadb.api.query.predicate.parser.ParserExecutor;
import io.evitadb.api.query.predicate.parser.grammar.PredicateBaseVisitor;
import io.evitadb.api.query.predicate.parser.grammar.PredicateParser.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Random;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, 2024
 */
public class PredicateExpressionVisitor extends PredicateBaseVisitor<BigDecimal> {

	private final Random random = new Random();

	@Override
	public BigDecimal visitFunctionSignedAtom(FunctionSignedAtomContext ctx) {
		return ctx.function().accept(this);
	}

	@Override
	public BigDecimal visitCeilFunction(CeilFunctionContext ctx) {
		final BigDecimal nestedNumber = ctx.expression().accept(this);
		return nestedNumber.setScale(0, RoundingMode.CEILING);
	}

	@Override
	public BigDecimal visitFloorFunction(FloorFunctionContext ctx) {
		final BigDecimal nestedNumber = ctx.expression().accept(this);
		return nestedNumber.setScale(0, RoundingMode.FLOOR);
	}

	@Override
	public BigDecimal visitRandomIntFunction(RandomIntFunctionContext ctx) {
		final List<BigDecimal> args = ctx.expression()
			.stream()
			.map(it -> it.accept(this))
			.toList();

		final int randomInt;
		// todo lho exceptions
		if (args.size() == 1) {
			randomInt = random.nextInt(args.get(0).intValueExact());
		} else {
			randomInt = random.nextInt(args.get(0).intValueExact());
		}
		return new BigDecimal(randomInt);
	}

	@Override
	public BigDecimal visitPlusExpression(PlusExpressionContext ctx) {
		return ctx.multiplyingExpression()
			.stream()
			.map(it -> it.accept(this))
			.reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	@Override
	public BigDecimal visitMinusExpression(MinusExpressionContext ctx) {
		return ctx.multiplyingExpression()
			.stream()
			.map(it -> it.accept(this))
			.reduce(BigDecimal.ZERO, BigDecimal::subtract);
	}

	@Override
	public BigDecimal visitTimesExpression(TimesExpressionContext ctx) {
		// todo lho should be removed by default expression grammar and the others as well
		if (ctx.powExpression().size() == 1) {
			return ctx.powExpression().get(0).accept(this);
		}

		BigDecimal result = ctx.powExpression(0).accept(this);
		for (int i = 1; i < ctx.powExpression().size(); i++) {
			result = result.multiply(ctx.powExpression().get(i).accept(this));
		}
		return result;
	}

	@Override
	public BigDecimal visitDivExpression(DivExpressionContext ctx) {
		if (ctx.powExpression().size() == 1) {
			return ctx.powExpression().get(0).accept(this);
		}

		BigDecimal result = ctx.powExpression(0).accept(this);
		for (int i = 1; i < ctx.powExpression().size(); i++) {
			result = result.divide(ctx.powExpression(i).accept(this), 2, RoundingMode.HALF_UP);
		}
		return result;
	}

	@Override
	public BigDecimal visitModExpression(ModExpressionContext ctx) {
		if (ctx.powExpression().size() == 1) {
			return ctx.powExpression().get(0).accept(this);
		}

		BigDecimal result = ctx.powExpression(0).accept(this);
		for (int i = 1; i < ctx.powExpression().size(); i++) {
			result = result.remainder(ctx.powExpression(i).accept(this));
		}
		return result;
	}

	@Override
	public BigDecimal visitPowExpression(PowExpressionContext ctx) {
		if (ctx.signedAtom().size() == 1) {
			return ctx.signedAtom().get(0).accept(this);
		}

		// todo lho exception for non-int pow number
		BigDecimal result = ctx.signedAtom(0).accept(this);
		for (int i = 1; i < ctx.signedAtom().size(); i++) {
			result = result.pow(ctx.signedAtom(i).accept(this).intValueExact());
		}
		return result;
	}

	@Override
	public BigDecimal visitPositiveSignedAtom(PositiveSignedAtomContext ctx) {
		// todo lho not sure about this
		return ctx.signedAtom().accept(this);
	}

	@Override
	public BigDecimal visitNegativeSignedAtom(NegativeSignedAtomContext ctx) {
		return ctx.signedAtom().accept(this).negate();
	}

	@Override
	public BigDecimal visitBaseSignedAtom(BaseSignedAtomContext ctx) {
		return ctx.atom().accept(this);
	}

	@Override
	public BigDecimal visitScientificAtom(ScientificAtomContext ctx) {
		// todo lho probably unnecessary nested visitor
		return ctx.scientific().accept(this);
	}

	@Override
	public BigDecimal visitVariableAtom(VariableAtomContext ctx) {
		// todo lho probably unnecessary nested visitor
		return ctx.variable().accept(this);
	}

	@Override
	public BigDecimal visitExpressionAtom(ExpressionAtomContext ctx) {
		return ctx.expression().accept(this);
	}

	@Override
	public BigDecimal visitVariable(VariableContext ctx) {
		final ParseContext context = ParserExecutor.getContext();
		return context.getVariable(ctx.getText().substring(1));
	}

	@Override
	public BigDecimal visitScientific(ScientificContext ctx) {
		return new BigDecimal(ctx.getText());
	}

}
