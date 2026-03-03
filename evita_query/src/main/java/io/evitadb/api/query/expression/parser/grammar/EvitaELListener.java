// Generated from EvitaEL.g4 by ANTLR 4.13.2

    package io.evitadb.api.query.expression.parser.grammar;

import org.antlr.v4.runtime.tree.ParseTreeListener;

/**
 * This interface defines a complete listener for a parse tree produced by
 * {@link EvitaELParser}.
 */
public interface EvitaELListener extends ParseTreeListener {
	/**
	 * Enter a parse tree produced by {@link EvitaELParser#root}.
	 * @param ctx the parse tree
	 */
	void enterRoot(EvitaELParser.RootContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaELParser#root}.
	 * @param ctx the parse tree
	 */
	void exitRoot(EvitaELParser.RootContext ctx);
	/**
	 * Enter a parse tree produced by the {@code objectAccessExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterObjectAccessExpression(EvitaELParser.ObjectAccessExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code objectAccessExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitObjectAccessExpression(EvitaELParser.ObjectAccessExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code spreadNullCoalesceExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterSpreadNullCoalesceExpression(EvitaELParser.SpreadNullCoalesceExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code spreadNullCoalesceExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitSpreadNullCoalesceExpression(EvitaELParser.SpreadNullCoalesceExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code negativeExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterNegativeExpression(EvitaELParser.NegativeExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code negativeExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitNegativeExpression(EvitaELParser.NegativeExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code additionExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterAdditionExpression(EvitaELParser.AdditionExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code additionExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitAdditionExpression(EvitaELParser.AdditionExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code moduloExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterModuloExpression(EvitaELParser.ModuloExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code moduloExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitModuloExpression(EvitaELParser.ModuloExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code xorExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterXorExpression(EvitaELParser.XorExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code xorExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitXorExpression(EvitaELParser.XorExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code multiplicationExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterMultiplicationExpression(EvitaELParser.MultiplicationExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code multiplicationExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitMultiplicationExpression(EvitaELParser.MultiplicationExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code greaterThanExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterGreaterThanExpression(EvitaELParser.GreaterThanExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code greaterThanExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitGreaterThanExpression(EvitaELParser.GreaterThanExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code variableExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterVariableExpression(EvitaELParser.VariableExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code variableExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitVariableExpression(EvitaELParser.VariableExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code nullCoalesceExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterNullCoalesceExpression(EvitaELParser.NullCoalesceExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code nullCoalesceExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitNullCoalesceExpression(EvitaELParser.NullCoalesceExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code functionExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterFunctionExpression(EvitaELParser.FunctionExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code functionExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitFunctionExpression(EvitaELParser.FunctionExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code greaterThanEqualsExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterGreaterThanEqualsExpression(EvitaELParser.GreaterThanEqualsExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code greaterThanEqualsExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitGreaterThanEqualsExpression(EvitaELParser.GreaterThanEqualsExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code lessThanEqualsExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterLessThanEqualsExpression(EvitaELParser.LessThanEqualsExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code lessThanEqualsExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitLessThanEqualsExpression(EvitaELParser.LessThanEqualsExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code orExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterOrExpression(EvitaELParser.OrExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code orExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitOrExpression(EvitaELParser.OrExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code substractionExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterSubstractionExpression(EvitaELParser.SubstractionExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code substractionExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitSubstractionExpression(EvitaELParser.SubstractionExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code notEqualsExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterNotEqualsExpression(EvitaELParser.NotEqualsExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code notEqualsExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitNotEqualsExpression(EvitaELParser.NotEqualsExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code andExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterAndExpression(EvitaELParser.AndExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code andExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitAndExpression(EvitaELParser.AndExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code equalsExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterEqualsExpression(EvitaELParser.EqualsExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code equalsExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitEqualsExpression(EvitaELParser.EqualsExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code nestedExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterNestedExpression(EvitaELParser.NestedExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code nestedExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitNestedExpression(EvitaELParser.NestedExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code positiveExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterPositiveExpression(EvitaELParser.PositiveExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code positiveExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitPositiveExpression(EvitaELParser.PositiveExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code lessThanExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterLessThanExpression(EvitaELParser.LessThanExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code lessThanExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitLessThanExpression(EvitaELParser.LessThanExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code literalExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterLiteralExpression(EvitaELParser.LiteralExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code literalExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitLiteralExpression(EvitaELParser.LiteralExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code negatingExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterNegatingExpression(EvitaELParser.NegatingExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code negatingExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitNegatingExpression(EvitaELParser.NegatingExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code divisionExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void enterDivisionExpression(EvitaELParser.DivisionExpressionContext ctx);
	/**
	 * Exit a parse tree produced by the {@code divisionExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 */
	void exitDivisionExpression(EvitaELParser.DivisionExpressionContext ctx);
	/**
	 * Enter a parse tree produced by the {@code literalCallOperand}
	 * labeled alternative in {@link EvitaELParser#operandOperationOperand}.
	 * @param ctx the parse tree
	 */
	void enterLiteralCallOperand(EvitaELParser.LiteralCallOperandContext ctx);
	/**
	 * Exit a parse tree produced by the {@code literalCallOperand}
	 * labeled alternative in {@link EvitaELParser#operandOperationOperand}.
	 * @param ctx the parse tree
	 */
	void exitLiteralCallOperand(EvitaELParser.LiteralCallOperandContext ctx);
	/**
	 * Enter a parse tree produced by the {@code variableCallOperand}
	 * labeled alternative in {@link EvitaELParser#operandOperationOperand}.
	 * @param ctx the parse tree
	 */
	void enterVariableCallOperand(EvitaELParser.VariableCallOperandContext ctx);
	/**
	 * Exit a parse tree produced by the {@code variableCallOperand}
	 * labeled alternative in {@link EvitaELParser#operandOperationOperand}.
	 * @param ctx the parse tree
	 */
	void exitVariableCallOperand(EvitaELParser.VariableCallOperandContext ctx);
	/**
	 * Enter a parse tree produced by the {@code nestedExpressionCallOperand}
	 * labeled alternative in {@link EvitaELParser#operandOperationOperand}.
	 * @param ctx the parse tree
	 */
	void enterNestedExpressionCallOperand(EvitaELParser.NestedExpressionCallOperandContext ctx);
	/**
	 * Exit a parse tree produced by the {@code nestedExpressionCallOperand}
	 * labeled alternative in {@link EvitaELParser#operandOperationOperand}.
	 * @param ctx the parse tree
	 */
	void exitNestedExpressionCallOperand(EvitaELParser.NestedExpressionCallOperandContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaELParser#elementAccessExpression}.
	 * @param ctx the parse tree
	 */
	void enterElementAccessExpression(EvitaELParser.ElementAccessExpressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaELParser#elementAccessExpression}.
	 * @param ctx the parse tree
	 */
	void exitElementAccessExpression(EvitaELParser.ElementAccessExpressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaELParser#propertyAccessExpression}.
	 * @param ctx the parse tree
	 */
	void enterPropertyAccessExpression(EvitaELParser.PropertyAccessExpressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaELParser#propertyAccessExpression}.
	 * @param ctx the parse tree
	 */
	void exitPropertyAccessExpression(EvitaELParser.PropertyAccessExpressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaELParser#spreadAccessExpression}.
	 * @param ctx the parse tree
	 */
	void enterSpreadAccessExpression(EvitaELParser.SpreadAccessExpressionContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaELParser#spreadAccessExpression}.
	 * @param ctx the parse tree
	 */
	void exitSpreadAccessExpression(EvitaELParser.SpreadAccessExpressionContext ctx);
	/**
	 * Enter a parse tree produced by {@link EvitaELParser#variable}.
	 * @param ctx the parse tree
	 */
	void enterVariable(EvitaELParser.VariableContext ctx);
	/**
	 * Exit a parse tree produced by {@link EvitaELParser#variable}.
	 * @param ctx the parse tree
	 */
	void exitVariable(EvitaELParser.VariableContext ctx);
	/**
	 * Enter a parse tree produced by the {@code stringValueToken}
	 * labeled alternative in {@link EvitaELParser#literal}.
	 * @param ctx the parse tree
	 */
	void enterStringValueToken(EvitaELParser.StringValueTokenContext ctx);
	/**
	 * Exit a parse tree produced by the {@code stringValueToken}
	 * labeled alternative in {@link EvitaELParser#literal}.
	 * @param ctx the parse tree
	 */
	void exitStringValueToken(EvitaELParser.StringValueTokenContext ctx);
	/**
	 * Enter a parse tree produced by the {@code intValueToken}
	 * labeled alternative in {@link EvitaELParser#literal}.
	 * @param ctx the parse tree
	 */
	void enterIntValueToken(EvitaELParser.IntValueTokenContext ctx);
	/**
	 * Exit a parse tree produced by the {@code intValueToken}
	 * labeled alternative in {@link EvitaELParser#literal}.
	 * @param ctx the parse tree
	 */
	void exitIntValueToken(EvitaELParser.IntValueTokenContext ctx);
	/**
	 * Enter a parse tree produced by the {@code floatValueToken}
	 * labeled alternative in {@link EvitaELParser#literal}.
	 * @param ctx the parse tree
	 */
	void enterFloatValueToken(EvitaELParser.FloatValueTokenContext ctx);
	/**
	 * Exit a parse tree produced by the {@code floatValueToken}
	 * labeled alternative in {@link EvitaELParser#literal}.
	 * @param ctx the parse tree
	 */
	void exitFloatValueToken(EvitaELParser.FloatValueTokenContext ctx);
	/**
	 * Enter a parse tree produced by the {@code booleanValueToken}
	 * labeled alternative in {@link EvitaELParser#literal}.
	 * @param ctx the parse tree
	 */
	void enterBooleanValueToken(EvitaELParser.BooleanValueTokenContext ctx);
	/**
	 * Exit a parse tree produced by the {@code booleanValueToken}
	 * labeled alternative in {@link EvitaELParser#literal}.
	 * @param ctx the parse tree
	 */
	void exitBooleanValueToken(EvitaELParser.BooleanValueTokenContext ctx);
}