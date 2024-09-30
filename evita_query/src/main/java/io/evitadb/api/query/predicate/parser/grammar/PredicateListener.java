// Generated from Predicate.g4 by ANTLR 4.9.2

package io.evitadb.api.query.predicate.parser.grammar;

import org.antlr.v4.runtime.tree.ParseTreeListener;

/**
 * This interface defines a complete listener for a parse tree produced by
 * {@link PredicateParser}.
 */
public interface PredicateListener extends ParseTreeListener {
	/**
	 * Enter a parse tree produced by the {@code greaterThanPredicate}
	 * labeled alternative in {@link PredicateParser#predicate}.
	 * @param ctx the parse tree
	 */
	void enterGreaterThanPredicate(PredicateParser.GreaterThanPredicateContext ctx);
	/**
	 * Exit a parse tree produced by the {@code greaterThanPredicate}
	 * labeled alternative in {@link PredicateParser#predicate}.
	 * @param ctx the parse tree
	 */
	void exitGreaterThanPredicate(PredicateParser.GreaterThanPredicateContext ctx);
	/**
	 * Enter a parse tree produced by the {@code equalsPredicate}
	 * labeled alternative in {@link PredicateParser#predicate}.
	 * @param ctx the parse tree
	 */
	void enterEqualsPredicate(PredicateParser.EqualsPredicateContext ctx);
	/**
	 * Exit a parse tree produced by the {@code equalsPredicate}
	 * labeled alternative in {@link PredicateParser#predicate}.
	 * @param ctx the parse tree
	 */
	void exitEqualsPredicate(PredicateParser.EqualsPredicateContext ctx);
	/**
	 * Enter a parse tree produced by the {@code orPredicate}
	 * labeled alternative in {@link PredicateParser#predicate}.
	 * @param ctx the parse tree
	 */
	void enterOrPredicate(PredicateParser.OrPredicateContext ctx);
	/**
	 * Exit a parse tree produced by the {@code orPredicate}
	 * labeled alternative in {@link PredicateParser#predicate}.
	 * @param ctx the parse tree
	 */
	void exitOrPredicate(PredicateParser.OrPredicateContext ctx);
	/**
	 * Enter a parse tree produced by the {@code nestedPredicate}
	 * labeled alternative in {@link PredicateParser#predicate}.
	 * @param ctx the parse tree
	 */
	void enterNestedPredicate(PredicateParser.NestedPredicateContext ctx);
	/**
	 * Exit a parse tree produced by the {@code nestedPredicate}
	 * labeled alternative in {@link PredicateParser#predicate}.
	 * @param ctx the parse tree
	 */
	void exitNestedPredicate(PredicateParser.NestedPredicateContext ctx);
	/**
	 * Enter a parse tree produced by the {@code negatingPredicate}
	 * labeled alternative in {@link PredicateParser#predicate}.
	 * @param ctx the parse tree
	 */
	void enterNegatingPredicate(PredicateParser.NegatingPredicateContext ctx);
	/**
	 * Exit a parse tree produced by the {@code negatingPredicate}
	 * labeled alternative in {@link PredicateParser#predicate}.
	 * @param ctx the parse tree
	 */
	void exitNegatingPredicate(PredicateParser.NegatingPredicateContext ctx);
	/**
	 * Enter a parse tree produced by the {@code notEqualsPredicate}
	 * labeled alternative in {@link PredicateParser#predicate}.
	 * @param ctx the parse tree
	 */
	void enterNotEqualsPredicate(PredicateParser.NotEqualsPredicateContext ctx);
	/**
	 * Exit a parse tree produced by the {@code notEqualsPredicate}
	 * labeled alternative in {@link PredicateParser#predicate}.
	 * @param ctx the parse tree
	 */
	void exitNotEqualsPredicate(PredicateParser.NotEqualsPredicateContext ctx);
	/**
	 * Enter a parse tree produced by the {@code lessThanPredicate}
	 * labeled alternative in {@link PredicateParser#predicate}.
	 * @param ctx the parse tree
	 */
	void enterLessThanPredicate(PredicateParser.LessThanPredicateContext ctx);
	/**
	 * Exit a parse tree produced by the {@code lessThanPredicate}
	 * labeled alternative in {@link PredicateParser#predicate}.
	 * @param ctx the parse tree
	 */
	void exitLessThanPredicate(PredicateParser.LessThanPredicateContext ctx);
	/**
	 * Enter a parse tree produced by the {@code andPredicate}
	 * labeled alternative in {@link PredicateParser#predicate}.
	 * @param ctx the parse tree
	 */
	void enterAndPredicate(PredicateParser.AndPredicateContext ctx);
	/**
	 * Exit a parse tree produced by the {@code andPredicate}
	 * labeled alternative in {@link PredicateParser#predicate}.
	 * @param ctx the parse tree
	 */
	void exitAndPredicate(PredicateParser.AndPredicateContext ctx);
	/**
	 * Enter a parse tree produced by the {@code plusExpression}
	 * labeled alternative in {@link PredicateParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterPlusExpression(PredicateParser.PlusExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code plusExpression}
	 * labeled alternative in {@link PredicateParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitPlusExpression(PredicateParser.PlusExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code minusExpression}
	 * labeled alternative in {@link PredicateParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterMinusExpression(PredicateParser.MinusExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code minusExpression}
	 * labeled alternative in {@link PredicateParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitMinusExpression(PredicateParser.MinusExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code timesExpression}
	 * labeled alternative in {@link PredicateParser#multiplyingExpression}.
	 * @param ctx the parse tree
	 */
	void enterTimesExpression(PredicateParser.TimesExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code timesExpression}
	 * labeled alternative in {@link PredicateParser#multiplyingExpression}.
	 * @param ctx the parse tree
	 */
	void exitTimesExpression(PredicateParser.TimesExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code divExpression}
	 * labeled alternative in {@link PredicateParser#multiplyingExpression}.
	 * @param ctx the parse tree
	 */
	void enterDivExpression(PredicateParser.DivExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code divExpression}
	 * labeled alternative in {@link PredicateParser#multiplyingExpression}.
	 * @param ctx the parse tree
	 */
	void exitDivExpression(PredicateParser.DivExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code modExpression}
	 * labeled alternative in {@link PredicateParser#multiplyingExpression}.
	 * @param ctx the parse tree
	 */
	void enterModExpression(PredicateParser.ModExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code modExpression}
	 * labeled alternative in {@link PredicateParser#multiplyingExpression}.
	 * @param ctx the parse tree
	 */
	void exitModExpression(PredicateParser.ModExpressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link PredicateParser#powExpression}.
	 * @param ctx the parse tree
	 */
	void enterPowExpression(PredicateParser.PowExpressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link PredicateParser#powExpression}.
	 * @param ctx the parse tree
	 */
	void exitPowExpression(PredicateParser.PowExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code positiveSignedAtom}
	 * labeled alternative in {@link PredicateParser#signedAtom}.
	 * @param ctx the parse tree
	 */
	void enterPositiveSignedAtom(PredicateParser.PositiveSignedAtomContext ctx);
	/**
	 * Exit a parse tree produced by the {@code positiveSignedAtom}
	 * labeled alternative in {@link PredicateParser#signedAtom}.
	 * @param ctx the parse tree
	 */
	void exitPositiveSignedAtom(PredicateParser.PositiveSignedAtomContext ctx);
	/**
	 * Enter a parse tree produced by the {@code negativeSignedAtom}
	 * labeled alternative in {@link PredicateParser#signedAtom}.
	 * @param ctx the parse tree
	 */
	void enterNegativeSignedAtom(PredicateParser.NegativeSignedAtomContext ctx);
	/**
	 * Exit a parse tree produced by the {@code negativeSignedAtom}
	 * labeled alternative in {@link PredicateParser#signedAtom}.
	 * @param ctx the parse tree
	 */
	void exitNegativeSignedAtom(PredicateParser.NegativeSignedAtomContext ctx);
	/**
	 * Enter a parse tree produced by the {@code functionSignedAtom}
	 * labeled alternative in {@link PredicateParser#signedAtom}.
	 * @param ctx the parse tree
	 */
	void enterFunctionSignedAtom(PredicateParser.FunctionSignedAtomContext ctx);
	/**
	 * Exit a parse tree produced by the {@code functionSignedAtom}
	 * labeled alternative in {@link PredicateParser#signedAtom}.
	 * @param ctx the parse tree
	 */
	void exitFunctionSignedAtom(PredicateParser.FunctionSignedAtomContext ctx);
	/**
	 * Enter a parse tree produced by the {@code baseSignedAtom}
	 * labeled alternative in {@link PredicateParser#signedAtom}.
	 * @param ctx the parse tree
	 */
	void enterBaseSignedAtom(PredicateParser.BaseSignedAtomContext ctx);
	/**
	 * Exit a parse tree produced by the {@code baseSignedAtom}
	 * labeled alternative in {@link PredicateParser#signedAtom}.
	 * @param ctx the parse tree
	 */
	void exitBaseSignedAtom(PredicateParser.BaseSignedAtomContext ctx);
	/**
	 * Enter a parse tree produced by the {@code scientificAtom}
	 * labeled alternative in {@link PredicateParser#atom}.
	 * @param ctx the parse tree
	 */
	void enterScientificAtom(PredicateParser.ScientificAtomContext ctx);
	/**
	 * Exit a parse tree produced by the {@code scientificAtom}
	 * labeled alternative in {@link PredicateParser#atom}.
	 * @param ctx the parse tree
	 */
	void exitScientificAtom(PredicateParser.ScientificAtomContext ctx);
	/**
	 * Enter a parse tree produced by the {@code variableAtom}
	 * labeled alternative in {@link PredicateParser#atom}.
	 * @param ctx the parse tree
	 */
	void enterVariableAtom(PredicateParser.VariableAtomContext ctx);
	/**
	 * Exit a parse tree produced by the {@code variableAtom}
	 * labeled alternative in {@link PredicateParser#atom}.
	 * @param ctx the parse tree
	 */
	void exitVariableAtom(PredicateParser.VariableAtomContext ctx);
	/**
	 * Enter a parse tree produced by the {@code expressionAtom}
	 * labeled alternative in {@link PredicateParser#atom}.
	 * @param ctx the parse tree
	 */
	void enterExpressionAtom(PredicateParser.ExpressionAtomContext ctx);
	/**
	 * Exit a parse tree produced by the {@code expressionAtom}
	 * labeled alternative in {@link PredicateParser#atom}.
	 * @param ctx the parse tree
	 */
	void exitExpressionAtom(PredicateParser.ExpressionAtomContext ctx);
	/**
	 * Enter a parse tree produced by {@link PredicateParser#scientific}.
	 * @param ctx the parse tree
	 */
	void enterScientific(PredicateParser.ScientificContext ctx);
	/**
	 * Exit a parse tree produced by {@link PredicateParser#scientific}.
	 * @param ctx the parse tree
	 */
	void exitScientific(PredicateParser.ScientificContext ctx);
	/**
	 * Enter a parse tree produced by {@link PredicateParser#variable}.
	 * @param ctx the parse tree
	 */
	void enterVariable(PredicateParser.VariableContext ctx);
	/**
	 * Exit a parse tree produced by {@link PredicateParser#variable}.
	 * @param ctx the parse tree
	 */
	void exitVariable(PredicateParser.VariableContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ceilFunction}
	 * labeled alternative in {@link PredicateParser#function}.
	 * @param ctx the parse tree
	 */
	void enterCeilFunction(PredicateParser.CeilFunctionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ceilFunction}
	 * labeled alternative in {@link PredicateParser#function}.
	 * @param ctx the parse tree
	 */
	void exitCeilFunction(PredicateParser.CeilFunctionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code floorFunction}
	 * labeled alternative in {@link PredicateParser#function}.
	 * @param ctx the parse tree
	 */
	void enterFloorFunction(PredicateParser.FloorFunctionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code floorFunction}
	 * labeled alternative in {@link PredicateParser#function}.
	 * @param ctx the parse tree
	 */
	void exitFloorFunction(PredicateParser.FloorFunctionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code randomIntFunction}
	 * labeled alternative in {@link PredicateParser#function}.
	 * @param ctx the parse tree
	 */
	void enterRandomIntFunction(PredicateParser.RandomIntFunctionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code randomIntFunction}
	 * labeled alternative in {@link PredicateParser#function}.
	 * @param ctx the parse tree
	 */
	void exitRandomIntFunction(PredicateParser.RandomIntFunctionContext ctx);
}