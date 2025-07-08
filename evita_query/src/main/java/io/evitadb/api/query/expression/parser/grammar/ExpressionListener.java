// Generated from Expression.g4 by ANTLR 4.13.2

    package io.evitadb.api.query.expression.parser.grammar;

import org.antlr.v4.runtime.tree.ParseTreeListener;

/**
 * This interface defines a complete listener for a parse tree produced by
 * {@link ExpressionParser}.
 */
public interface ExpressionListener extends ParseTreeListener {
	/**
	 * Enter a parse tree produced by the {@code greaterThanExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterGreaterThanExpression(ExpressionParser.GreaterThanExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code greaterThanExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitGreaterThanExpression(ExpressionParser.GreaterThanExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code functionExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterFunctionExpression(ExpressionParser.FunctionExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code functionExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitFunctionExpression(ExpressionParser.FunctionExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code greaterThanEqualsExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterGreaterThanEqualsExpression(ExpressionParser.GreaterThanEqualsExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code greaterThanEqualsExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitGreaterThanEqualsExpression(ExpressionParser.GreaterThanEqualsExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code lessThanEqualsExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterLessThanEqualsExpression(ExpressionParser.LessThanEqualsExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code lessThanEqualsExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitLessThanEqualsExpression(ExpressionParser.LessThanEqualsExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code orExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterOrExpression(ExpressionParser.OrExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code orExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitOrExpression(ExpressionParser.OrExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code notEqualsExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterNotEqualsExpression(ExpressionParser.NotEqualsExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code notEqualsExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitNotEqualsExpression(ExpressionParser.NotEqualsExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code andExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterAndExpression(ExpressionParser.AndExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code andExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitAndExpression(ExpressionParser.AndExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code equalsExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterEqualsExpression(ExpressionParser.EqualsExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code equalsExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitEqualsExpression(ExpressionParser.EqualsExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code standaloneCombinationExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterStandaloneCombinationExpression(ExpressionParser.StandaloneCombinationExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code standaloneCombinationExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitStandaloneCombinationExpression(ExpressionParser.StandaloneCombinationExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code nestedExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterNestedExpression(ExpressionParser.NestedExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code nestedExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitNestedExpression(ExpressionParser.NestedExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code lessThanExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterLessThanExpression(ExpressionParser.LessThanExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code lessThanExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitLessThanExpression(ExpressionParser.LessThanExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code booleanNegatingExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterBooleanNegatingExpression(ExpressionParser.BooleanNegatingExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code booleanNegatingExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitBooleanNegatingExpression(ExpressionParser.BooleanNegatingExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code standaloneExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterStandaloneExpression(ExpressionParser.StandaloneExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code standaloneExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitStandaloneExpression(ExpressionParser.StandaloneExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code negatingExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterNegatingExpression(ExpressionParser.NegatingExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code negatingExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitNegatingExpression(ExpressionParser.NegatingExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code plusExpression}
	 * labeled alternative in {@link ExpressionParser#combinationExpression}.
	 * @param ctx the parse tree
	 */
	void enterPlusExpression(ExpressionParser.PlusExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code plusExpression}
	 * labeled alternative in {@link ExpressionParser#combinationExpression}.
	 * @param ctx the parse tree
	 */
	void exitPlusExpression(ExpressionParser.PlusExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code minusExpression}
	 * labeled alternative in {@link ExpressionParser#combinationExpression}.
	 * @param ctx the parse tree
	 */
	void enterMinusExpression(ExpressionParser.MinusExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code minusExpression}
	 * labeled alternative in {@link ExpressionParser#combinationExpression}.
	 * @param ctx the parse tree
	 */
	void exitMinusExpression(ExpressionParser.MinusExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code timesExpression}
	 * labeled alternative in {@link ExpressionParser#multiplyingExpression}.
	 * @param ctx the parse tree
	 */
	void enterTimesExpression(ExpressionParser.TimesExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code timesExpression}
	 * labeled alternative in {@link ExpressionParser#multiplyingExpression}.
	 * @param ctx the parse tree
	 */
	void exitTimesExpression(ExpressionParser.TimesExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code divExpression}
	 * labeled alternative in {@link ExpressionParser#multiplyingExpression}.
	 * @param ctx the parse tree
	 */
	void enterDivExpression(ExpressionParser.DivExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code divExpression}
	 * labeled alternative in {@link ExpressionParser#multiplyingExpression}.
	 * @param ctx the parse tree
	 */
	void exitDivExpression(ExpressionParser.DivExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code modExpression}
	 * labeled alternative in {@link ExpressionParser#multiplyingExpression}.
	 * @param ctx the parse tree
	 */
	void enterModExpression(ExpressionParser.ModExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code modExpression}
	 * labeled alternative in {@link ExpressionParser#multiplyingExpression}.
	 * @param ctx the parse tree
	 */
	void exitModExpression(ExpressionParser.ModExpressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link ExpressionParser#powExpression}.
	 * @param ctx the parse tree
	 */
	void enterPowExpression(ExpressionParser.PowExpressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link ExpressionParser#powExpression}.
	 * @param ctx the parse tree
	 */
	void exitPowExpression(ExpressionParser.PowExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code positiveSignedAtom}
	 * labeled alternative in {@link ExpressionParser#signedAtom}.
	 * @param ctx the parse tree
	 */
	void enterPositiveSignedAtom(ExpressionParser.PositiveSignedAtomContext ctx);
	/**
	 * Exit a parse tree produced by the {@code positiveSignedAtom}
	 * labeled alternative in {@link ExpressionParser#signedAtom}.
	 * @param ctx the parse tree
	 */
	void exitPositiveSignedAtom(ExpressionParser.PositiveSignedAtomContext ctx);
	/**
	 * Enter a parse tree produced by the {@code negativeSignedAtom}
	 * labeled alternative in {@link ExpressionParser#signedAtom}.
	 * @param ctx the parse tree
	 */
	void enterNegativeSignedAtom(ExpressionParser.NegativeSignedAtomContext ctx);
	/**
	 * Exit a parse tree produced by the {@code negativeSignedAtom}
	 * labeled alternative in {@link ExpressionParser#signedAtom}.
	 * @param ctx the parse tree
	 */
	void exitNegativeSignedAtom(ExpressionParser.NegativeSignedAtomContext ctx);
	/**
	 * Enter a parse tree produced by the {@code functionSignedAtom}
	 * labeled alternative in {@link ExpressionParser#signedAtom}.
	 * @param ctx the parse tree
	 */
	void enterFunctionSignedAtom(ExpressionParser.FunctionSignedAtomContext ctx);
	/**
	 * Exit a parse tree produced by the {@code functionSignedAtom}
	 * labeled alternative in {@link ExpressionParser#signedAtom}.
	 * @param ctx the parse tree
	 */
	void exitFunctionSignedAtom(ExpressionParser.FunctionSignedAtomContext ctx);
	/**
	 * Enter a parse tree produced by the {@code baseSignedAtom}
	 * labeled alternative in {@link ExpressionParser#signedAtom}.
	 * @param ctx the parse tree
	 */
	void enterBaseSignedAtom(ExpressionParser.BaseSignedAtomContext ctx);
	/**
	 * Exit a parse tree produced by the {@code baseSignedAtom}
	 * labeled alternative in {@link ExpressionParser#signedAtom}.
	 * @param ctx the parse tree
	 */
	void exitBaseSignedAtom(ExpressionParser.BaseSignedAtomContext ctx);
	/**
	 * Enter a parse tree produced by the {@code valueAtom}
	 * labeled alternative in {@link ExpressionParser#atom}.
	 * @param ctx the parse tree
	 */
	void enterValueAtom(ExpressionParser.ValueAtomContext ctx);
	/**
	 * Exit a parse tree produced by the {@code valueAtom}
	 * labeled alternative in {@link ExpressionParser#atom}.
	 * @param ctx the parse tree
	 */
	void exitValueAtom(ExpressionParser.ValueAtomContext ctx);
	/**
	 * Enter a parse tree produced by the {@code variableAtom}
	 * labeled alternative in {@link ExpressionParser#atom}.
	 * @param ctx the parse tree
	 */
	void enterVariableAtom(ExpressionParser.VariableAtomContext ctx);
	/**
	 * Exit a parse tree produced by the {@code variableAtom}
	 * labeled alternative in {@link ExpressionParser#atom}.
	 * @param ctx the parse tree
	 */
	void exitVariableAtom(ExpressionParser.VariableAtomContext ctx);
	/**
	 * Enter a parse tree produced by the {@code expressionAtom}
	 * labeled alternative in {@link ExpressionParser#atom}.
	 * @param ctx the parse tree
	 */
	void enterExpressionAtom(ExpressionParser.ExpressionAtomContext ctx);
	/**
	 * Exit a parse tree produced by the {@code expressionAtom}
	 * labeled alternative in {@link ExpressionParser#atom}.
	 * @param ctx the parse tree
	 */
	void exitExpressionAtom(ExpressionParser.ExpressionAtomContext ctx);
	/**
	 * Enter a parse tree produced by {@link ExpressionParser#variable}.
	 * @param ctx the parse tree
	 */
	void enterVariable(ExpressionParser.VariableContext ctx);
	/**
	 * Exit a parse tree produced by {@link ExpressionParser#variable}.
	 * @param ctx the parse tree
	 */
	void exitVariable(ExpressionParser.VariableContext ctx);
	/**
	 * Enter a parse tree produced by the {@code sqrtFunction}
	 * labeled alternative in {@link ExpressionParser#function}.
	 * @param ctx the parse tree
	 */
	void enterSqrtFunction(ExpressionParser.SqrtFunctionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code sqrtFunction}
	 * labeled alternative in {@link ExpressionParser#function}.
	 * @param ctx the parse tree
	 */
	void exitSqrtFunction(ExpressionParser.SqrtFunctionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code ceilFunction}
	 * labeled alternative in {@link ExpressionParser#function}.
	 * @param ctx the parse tree
	 */
	void enterCeilFunction(ExpressionParser.CeilFunctionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code ceilFunction}
	 * labeled alternative in {@link ExpressionParser#function}.
	 * @param ctx the parse tree
	 */
	void exitCeilFunction(ExpressionParser.CeilFunctionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code floorFunction}
	 * labeled alternative in {@link ExpressionParser#function}.
	 * @param ctx the parse tree
	 */
	void enterFloorFunction(ExpressionParser.FloorFunctionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code floorFunction}
	 * labeled alternative in {@link ExpressionParser#function}.
	 * @param ctx the parse tree
	 */
	void exitFloorFunction(ExpressionParser.FloorFunctionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code absFunction}
	 * labeled alternative in {@link ExpressionParser#function}.
	 * @param ctx the parse tree
	 */
	void enterAbsFunction(ExpressionParser.AbsFunctionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code absFunction}
	 * labeled alternative in {@link ExpressionParser#function}.
	 * @param ctx the parse tree
	 */
	void exitAbsFunction(ExpressionParser.AbsFunctionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code roundFunction}
	 * labeled alternative in {@link ExpressionParser#function}.
	 * @param ctx the parse tree
	 */
	void enterRoundFunction(ExpressionParser.RoundFunctionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code roundFunction}
	 * labeled alternative in {@link ExpressionParser#function}.
	 * @param ctx the parse tree
	 */
	void exitRoundFunction(ExpressionParser.RoundFunctionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code logFunction}
	 * labeled alternative in {@link ExpressionParser#function}.
	 * @param ctx the parse tree
	 */
	void enterLogFunction(ExpressionParser.LogFunctionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code logFunction}
	 * labeled alternative in {@link ExpressionParser#function}.
	 * @param ctx the parse tree
	 */
	void exitLogFunction(ExpressionParser.LogFunctionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code minFunction}
	 * labeled alternative in {@link ExpressionParser#function}.
	 * @param ctx the parse tree
	 */
	void enterMinFunction(ExpressionParser.MinFunctionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code minFunction}
	 * labeled alternative in {@link ExpressionParser#function}.
	 * @param ctx the parse tree
	 */
	void exitMinFunction(ExpressionParser.MinFunctionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code maxFunction}
	 * labeled alternative in {@link ExpressionParser#function}.
	 * @param ctx the parse tree
	 */
	void enterMaxFunction(ExpressionParser.MaxFunctionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code maxFunction}
	 * labeled alternative in {@link ExpressionParser#function}.
	 * @param ctx the parse tree
	 */
	void exitMaxFunction(ExpressionParser.MaxFunctionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code randomIntFunction}
	 * labeled alternative in {@link ExpressionParser#function}.
	 * @param ctx the parse tree
	 */
	void enterRandomIntFunction(ExpressionParser.RandomIntFunctionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code randomIntFunction}
	 * labeled alternative in {@link ExpressionParser#function}.
	 * @param ctx the parse tree
	 */
	void exitRandomIntFunction(ExpressionParser.RandomIntFunctionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code stringValueToken}
	 * labeled alternative in {@link ExpressionParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void enterStringValueToken(ExpressionParser.StringValueTokenContext ctx);
	/**
	 * Exit a parse tree produced by the {@code stringValueToken}
	 * labeled alternative in {@link ExpressionParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void exitStringValueToken(ExpressionParser.StringValueTokenContext ctx);
	/**
	 * Enter a parse tree produced by the {@code intValueToken}
	 * labeled alternative in {@link ExpressionParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void enterIntValueToken(ExpressionParser.IntValueTokenContext ctx);
	/**
	 * Exit a parse tree produced by the {@code intValueToken}
	 * labeled alternative in {@link ExpressionParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void exitIntValueToken(ExpressionParser.IntValueTokenContext ctx);
	/**
	 * Enter a parse tree produced by the {@code floatValueToken}
	 * labeled alternative in {@link ExpressionParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void enterFloatValueToken(ExpressionParser.FloatValueTokenContext ctx);
	/**
	 * Exit a parse tree produced by the {@code floatValueToken}
	 * labeled alternative in {@link ExpressionParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void exitFloatValueToken(ExpressionParser.FloatValueTokenContext ctx);
	/**
	 * Enter a parse tree produced by the {@code booleanValueToken}
	 * labeled alternative in {@link ExpressionParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void enterBooleanValueToken(ExpressionParser.BooleanValueTokenContext ctx);
	/**
	 * Exit a parse tree produced by the {@code booleanValueToken}
	 * labeled alternative in {@link ExpressionParser#valueToken}.
	 * @param ctx the parse tree
	 */
	void exitBooleanValueToken(ExpressionParser.BooleanValueTokenContext ctx);
}