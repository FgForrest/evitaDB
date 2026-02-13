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

// Generated from EvitaEL.g4 by ANTLR 4.13.2

    package io.evitadb.api.query.expression.parser.grammar;

import org.antlr.v4.runtime.tree.ParseTreeVisitor;

/**
 * This interface defines a complete generic visitor for a parse tree produced
 * by {@link EvitaELParser}.
 *
 * @param <T> The return type of the visit operation. Use {@link Void} for
 * operations with no return type.
 */
public interface EvitaELVisitor<T> extends ParseTreeVisitor<T> {
	/**
	 * Visit a parse tree produced by {@link EvitaELParser#root}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRoot(EvitaELParser.RootContext ctx);
	/**
	 * Visit a parse tree produced by the {@code objectAccessExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitObjectAccessExpression(EvitaELParser.ObjectAccessExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code spreadNullCoalesceExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSpreadNullCoalesceExpression(EvitaELParser.SpreadNullCoalesceExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code negativeExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNegativeExpression(EvitaELParser.NegativeExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code additionExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAdditionExpression(EvitaELParser.AdditionExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code moduloExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitModuloExpression(EvitaELParser.ModuloExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code xorExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitXorExpression(EvitaELParser.XorExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code multiplicationExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMultiplicationExpression(EvitaELParser.MultiplicationExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code greaterThanExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGreaterThanExpression(EvitaELParser.GreaterThanExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code variableExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitVariableExpression(EvitaELParser.VariableExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code nullCoalesceExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNullCoalesceExpression(EvitaELParser.NullCoalesceExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code functionExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFunctionExpression(EvitaELParser.FunctionExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code greaterThanEqualsExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGreaterThanEqualsExpression(EvitaELParser.GreaterThanEqualsExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code lessThanEqualsExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLessThanEqualsExpression(EvitaELParser.LessThanEqualsExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code orExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOrExpression(EvitaELParser.OrExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code substractionExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSubstractionExpression(EvitaELParser.SubstractionExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code notEqualsExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNotEqualsExpression(EvitaELParser.NotEqualsExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code andExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAndExpression(EvitaELParser.AndExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code equalsExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEqualsExpression(EvitaELParser.EqualsExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code nestedExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNestedExpression(EvitaELParser.NestedExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code positiveExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPositiveExpression(EvitaELParser.PositiveExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code lessThanExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLessThanExpression(EvitaELParser.LessThanExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code literalExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLiteralExpression(EvitaELParser.LiteralExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code negatingExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNegatingExpression(EvitaELParser.NegatingExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code divisionExpression}
	 * labeled alternative in {@link EvitaELParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDivisionExpression(EvitaELParser.DivisionExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code literalCallOperand}
	 * labeled alternative in {@link EvitaELParser#operandOperationOperand}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLiteralCallOperand(EvitaELParser.LiteralCallOperandContext ctx);
	/**
	 * Visit a parse tree produced by the {@code variableCallOperand}
	 * labeled alternative in {@link EvitaELParser#operandOperationOperand}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitVariableCallOperand(EvitaELParser.VariableCallOperandContext ctx);
	/**
	 * Visit a parse tree produced by the {@code nestedExpressionCallOperand}
	 * labeled alternative in {@link EvitaELParser#operandOperationOperand}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNestedExpressionCallOperand(EvitaELParser.NestedExpressionCallOperandContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaELParser#elementAccessExpression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitElementAccessExpression(EvitaELParser.ElementAccessExpressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaELParser#propertyAccessExpression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPropertyAccessExpression(EvitaELParser.PropertyAccessExpressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaELParser#spreadAccessExpression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSpreadAccessExpression(EvitaELParser.SpreadAccessExpressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link EvitaELParser#variable}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitVariable(EvitaELParser.VariableContext ctx);
	/**
	 * Visit a parse tree produced by the {@code stringValueToken}
	 * labeled alternative in {@link EvitaELParser#literal}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStringValueToken(EvitaELParser.StringValueTokenContext ctx);
	/**
	 * Visit a parse tree produced by the {@code intValueToken}
	 * labeled alternative in {@link EvitaELParser#literal}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIntValueToken(EvitaELParser.IntValueTokenContext ctx);
	/**
	 * Visit a parse tree produced by the {@code floatValueToken}
	 * labeled alternative in {@link EvitaELParser#literal}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFloatValueToken(EvitaELParser.FloatValueTokenContext ctx);
	/**
	 * Visit a parse tree produced by the {@code booleanValueToken}
	 * labeled alternative in {@link EvitaELParser#literal}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBooleanValueToken(EvitaELParser.BooleanValueTokenContext ctx);
}