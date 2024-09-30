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

import io.evitadb.api.query.predicate.parser.grammar.PredicateBaseVisitor;
import io.evitadb.api.query.predicate.parser.grammar.PredicateParser.AndPredicateContext;
import io.evitadb.api.query.predicate.parser.grammar.PredicateParser.EqualsPredicateContext;
import io.evitadb.api.query.predicate.parser.grammar.PredicateParser.GreaterThanPredicateContext;
import io.evitadb.api.query.predicate.parser.grammar.PredicateParser.LessThanPredicateContext;
import io.evitadb.api.query.predicate.parser.grammar.PredicateParser.NegatingPredicateContext;
import io.evitadb.api.query.predicate.parser.grammar.PredicateParser.NestedPredicateContext;
import io.evitadb.api.query.predicate.parser.grammar.PredicateParser.NotEqualsPredicateContext;
import io.evitadb.api.query.predicate.parser.grammar.PredicateParser.OrPredicateContext;

import javax.annotation.Nonnull;
import java.math.BigDecimal;

/**
 * TODO lho docs
 *
 * @author Lukáš Hornych, 2024
 */
public class PredicateEvaluationVisitor extends PredicateBaseVisitor<Boolean> {

	private final PredicateExpressionVisitor expressionVisitor = new PredicateExpressionVisitor();

	private static <T extends Number & Comparable> int compareNumbers(@Nonnull T a, @Nonnull T b) {
		//noinspection unchecked
		return a.compareTo(b);
	}

	@Override
	public Boolean visitGreaterThanPredicate(GreaterThanPredicateContext ctx) {
		final BigDecimal leftExpression = ctx.leftExpression.accept(expressionVisitor);
		final BigDecimal rightExpression = ctx.rightExpression.accept(expressionVisitor);
		return compareNumbers(leftExpression, rightExpression) > 0;
	}

	@Override
	public Boolean visitEqualsPredicate(EqualsPredicateContext ctx) {
		final BigDecimal leftExpression = ctx.leftExpression.accept(expressionVisitor);
		final BigDecimal rightExpression = ctx.rightExpression.accept(expressionVisitor);
		return compareNumbers(leftExpression, rightExpression) == 0;
	}

	@Override
	public Boolean visitOrPredicate(OrPredicateContext ctx) {
		final boolean leftPredicate = ctx.leftPredicate.accept(this);
		final boolean rightPredicate = ctx.rightPredicate.accept(this);
		return leftPredicate || rightPredicate;
	}

	@Override
	public Boolean visitNestedPredicate(NestedPredicateContext ctx) {
		return ctx.predicate().accept(this);
	}

	@Override
	public Boolean visitNegatingPredicate(NegatingPredicateContext ctx) {
		final boolean nestedPredicate = ctx.predicate().accept(this);
		return !nestedPredicate;
	}

	@Override
	public Boolean visitNotEqualsPredicate(NotEqualsPredicateContext ctx) {
		final BigDecimal leftExpression = ctx.leftExpression.accept(expressionVisitor);
		final BigDecimal rightExpression = ctx.rightExpression.accept(expressionVisitor);
		return compareNumbers(leftExpression, rightExpression) != 0;
	}

	@Override
	public Boolean visitLessThanPredicate(LessThanPredicateContext ctx) {
		final BigDecimal leftExpression = ctx.leftExpression.accept(expressionVisitor);
		final BigDecimal rightExpression = ctx.rightExpression.accept(expressionVisitor);
		return compareNumbers(leftExpression, rightExpression) < 0;
	}

	@Override
	public Boolean visitAndPredicate(AndPredicateContext ctx) {
		final boolean leftPredicate = ctx.leftPredicate.accept(this);
		final boolean rightPredicate = ctx.rightPredicate.accept(this);
		return leftPredicate && rightPredicate;
	}
}
