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

// Generated from ExpressionFactory.g4 by ANTLR 4.9.2

    package io.evitadb.api.query.expression.parser.grammar;

import org.antlr.v4.runtime.tree.ParseTreeVisitor;

/**
 * This interface defines a complete generic visitor for a parse tree produced
 * by {@link ExpressionParser}.
 *
 * @param <T> The return type of the visit operation. Use {@link Void} for
 * operations with no return type.
 */
public interface ExpressionVisitor<T> extends ParseTreeVisitor<T> {
	/**
	 * Visit a parse tree produced by the {@code greaterThanExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGreaterThanExpression(ExpressionParser.GreaterThanExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code functionExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFunctionExpression(ExpressionParser.FunctionExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code greaterThanEqualsExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGreaterThanEqualsExpression(ExpressionParser.GreaterThanEqualsExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code lessThanEqualsExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLessThanEqualsExpression(ExpressionParser.LessThanEqualsExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code orExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOrExpression(ExpressionParser.OrExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code notEqualsExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNotEqualsExpression(ExpressionParser.NotEqualsExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code andExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAndExpression(ExpressionParser.AndExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code equalsExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEqualsExpression(ExpressionParser.EqualsExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code standaloneCombinationExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStandaloneCombinationExpression(ExpressionParser.StandaloneCombinationExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code nestedExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNestedExpression(ExpressionParser.NestedExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code lessThanExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLessThanExpression(ExpressionParser.LessThanExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code booleanNegatingExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBooleanNegatingExpression(ExpressionParser.BooleanNegatingExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code standaloneExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStandaloneExpression(ExpressionParser.StandaloneExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code negatingExpression}
	 * labeled alternative in {@link ExpressionParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNegatingExpression(ExpressionParser.NegatingExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code plusExpression}
	 * labeled alternative in {@link ExpressionParser#combinationExpression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPlusExpression(ExpressionParser.PlusExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code minusExpression}
	 * labeled alternative in {@link ExpressionParser#combinationExpression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMinusExpression(ExpressionParser.MinusExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code timesExpression}
	 * labeled alternative in {@link ExpressionParser#multiplyingExpression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTimesExpression(ExpressionParser.TimesExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code divExpression}
	 * labeled alternative in {@link ExpressionParser#multiplyingExpression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDivExpression(ExpressionParser.DivExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code modExpression}
	 * labeled alternative in {@link ExpressionParser#multiplyingExpression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitModExpression(ExpressionParser.ModExpressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link ExpressionParser#powExpression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPowExpression(ExpressionParser.PowExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code positiveSignedAtom}
	 * labeled alternative in {@link ExpressionParser#signedAtom}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPositiveSignedAtom(ExpressionParser.PositiveSignedAtomContext ctx);
	/**
	 * Visit a parse tree produced by the {@code negativeSignedAtom}
	 * labeled alternative in {@link ExpressionParser#signedAtom}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNegativeSignedAtom(ExpressionParser.NegativeSignedAtomContext ctx);
	/**
	 * Visit a parse tree produced by the {@code functionSignedAtom}
	 * labeled alternative in {@link ExpressionParser#signedAtom}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFunctionSignedAtom(ExpressionParser.FunctionSignedAtomContext ctx);
	/**
	 * Visit a parse tree produced by the {@code baseSignedAtom}
	 * labeled alternative in {@link ExpressionParser#signedAtom}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBaseSignedAtom(ExpressionParser.BaseSignedAtomContext ctx);
	/**
	 * Visit a parse tree produced by the {@code valueAtom}
	 * labeled alternative in {@link ExpressionParser#atom}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitValueAtom(ExpressionParser.ValueAtomContext ctx);
	/**
	 * Visit a parse tree produced by the {@code variableAtom}
	 * labeled alternative in {@link ExpressionParser#atom}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitVariableAtom(ExpressionParser.VariableAtomContext ctx);
	/**
	 * Visit a parse tree produced by the {@code expressionAtom}
	 * labeled alternative in {@link ExpressionParser#atom}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExpressionAtom(ExpressionParser.ExpressionAtomContext ctx);
	/**
	 * Visit a parse tree produced by {@link ExpressionParser#variable}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitVariable(ExpressionParser.VariableContext ctx);
	/**
	 * Visit a parse tree produced by the {@code sqrtFunction}
	 * labeled alternative in {@link ExpressionParser#function}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitSqrtFunction(ExpressionParser.SqrtFunctionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code ceilFunction}
	 * labeled alternative in {@link ExpressionParser#function}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCeilFunction(ExpressionParser.CeilFunctionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code floorFunction}
	 * labeled alternative in {@link ExpressionParser#function}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFloorFunction(ExpressionParser.FloorFunctionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code randomIntFunction}
	 * labeled alternative in {@link ExpressionParser#function}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRandomIntFunction(ExpressionParser.RandomIntFunctionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code stringValueToken}
	 * labeled alternative in {@link ExpressionParser#valueToken}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitStringValueToken(ExpressionParser.StringValueTokenContext ctx);
	/**
	 * Visit a parse tree produced by the {@code intValueToken}
	 * labeled alternative in {@link ExpressionParser#valueToken}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitIntValueToken(ExpressionParser.IntValueTokenContext ctx);
	/**
	 * Visit a parse tree produced by the {@code floatValueToken}
	 * labeled alternative in {@link ExpressionParser#valueToken}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFloatValueToken(ExpressionParser.FloatValueTokenContext ctx);
	/**
	 * Visit a parse tree produced by the {@code booleanValueToken}
	 * labeled alternative in {@link ExpressionParser#valueToken}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBooleanValueToken(ExpressionParser.BooleanValueTokenContext ctx);
}