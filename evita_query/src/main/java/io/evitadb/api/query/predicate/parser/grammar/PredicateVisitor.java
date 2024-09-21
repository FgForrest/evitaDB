// Generated from Predicate.g4 by ANTLR 4.9.2

package io.evitadb.api.query.predicate.parser.grammar;

import org.antlr.v4.runtime.tree.ParseTreeVisitor;

/**
 * This interface defines a complete generic visitor for a parse tree produced
 * by {@link PredicateParser}.
 *
 * @param <T> The return type of the visit operation. Use {@link Void} for
 * operations with no return type.
 */
public interface PredicateVisitor<T> extends ParseTreeVisitor<T> {
	/**
	 * Visit a parse tree produced by the {@code greaterThanPredicate}
	 * labeled alternative in {@link PredicateParser#predicate}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitGreaterThanPredicate(PredicateParser.GreaterThanPredicateContext ctx);
	/**
	 * Visit a parse tree produced by the {@code equalsPredicate}
	 * labeled alternative in {@link PredicateParser#predicate}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitEqualsPredicate(PredicateParser.EqualsPredicateContext ctx);
	/**
	 * Visit a parse tree produced by the {@code orPredicate}
	 * labeled alternative in {@link PredicateParser#predicate}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitOrPredicate(PredicateParser.OrPredicateContext ctx);
	/**
	 * Visit a parse tree produced by the {@code nestedPredicate}
	 * labeled alternative in {@link PredicateParser#predicate}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNestedPredicate(PredicateParser.NestedPredicateContext ctx);
	/**
	 * Visit a parse tree produced by the {@code negatingPredicate}
	 * labeled alternative in {@link PredicateParser#predicate}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNegatingPredicate(PredicateParser.NegatingPredicateContext ctx);
	/**
	 * Visit a parse tree produced by the {@code notEqualsPredicate}
	 * labeled alternative in {@link PredicateParser#predicate}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNotEqualsPredicate(PredicateParser.NotEqualsPredicateContext ctx);
	/**
	 * Visit a parse tree produced by the {@code lessThanPredicate}
	 * labeled alternative in {@link PredicateParser#predicate}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitLessThanPredicate(PredicateParser.LessThanPredicateContext ctx);
	/**
	 * Visit a parse tree produced by the {@code andPredicate}
	 * labeled alternative in {@link PredicateParser#predicate}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitAndPredicate(PredicateParser.AndPredicateContext ctx);
	/**
	 * Visit a parse tree produced by the {@code plusExpression}
	 * labeled alternative in {@link PredicateParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPlusExpression(PredicateParser.PlusExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code minusExpression}
	 * labeled alternative in {@link PredicateParser#expression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitMinusExpression(PredicateParser.MinusExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code timesExpression}
	 * labeled alternative in {@link PredicateParser#multiplyingExpression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitTimesExpression(PredicateParser.TimesExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code divExpression}
	 * labeled alternative in {@link PredicateParser#multiplyingExpression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitDivExpression(PredicateParser.DivExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code modExpression}
	 * labeled alternative in {@link PredicateParser#multiplyingExpression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitModExpression(PredicateParser.ModExpressionContext ctx);
	/**
	 * Visit a parse tree produced by {@link PredicateParser#powExpression}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPowExpression(PredicateParser.PowExpressionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code positiveSignedAtom}
	 * labeled alternative in {@link PredicateParser#signedAtom}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitPositiveSignedAtom(PredicateParser.PositiveSignedAtomContext ctx);
	/**
	 * Visit a parse tree produced by the {@code negativeSignedAtom}
	 * labeled alternative in {@link PredicateParser#signedAtom}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitNegativeSignedAtom(PredicateParser.NegativeSignedAtomContext ctx);
	/**
	 * Visit a parse tree produced by the {@code functionSignedAtom}
	 * labeled alternative in {@link PredicateParser#signedAtom}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFunctionSignedAtom(PredicateParser.FunctionSignedAtomContext ctx);
	/**
	 * Visit a parse tree produced by the {@code baseSignedAtom}
	 * labeled alternative in {@link PredicateParser#signedAtom}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitBaseSignedAtom(PredicateParser.BaseSignedAtomContext ctx);
	/**
	 * Visit a parse tree produced by the {@code scientificAtom}
	 * labeled alternative in {@link PredicateParser#atom}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitScientificAtom(PredicateParser.ScientificAtomContext ctx);
	/**
	 * Visit a parse tree produced by the {@code variableAtom}
	 * labeled alternative in {@link PredicateParser#atom}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitVariableAtom(PredicateParser.VariableAtomContext ctx);
	/**
	 * Visit a parse tree produced by the {@code expressionAtom}
	 * labeled alternative in {@link PredicateParser#atom}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitExpressionAtom(PredicateParser.ExpressionAtomContext ctx);
	/**
	 * Visit a parse tree produced by {@link PredicateParser#scientific}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitScientific(PredicateParser.ScientificContext ctx);
	/**
	 * Visit a parse tree produced by {@link PredicateParser#variable}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitVariable(PredicateParser.VariableContext ctx);
	/**
	 * Visit a parse tree produced by the {@code ceilFunction}
	 * labeled alternative in {@link PredicateParser#function}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitCeilFunction(PredicateParser.CeilFunctionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code floorFunction}
	 * labeled alternative in {@link PredicateParser#function}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitFloorFunction(PredicateParser.FloorFunctionContext ctx);
	/**
	 * Visit a parse tree produced by the {@code randomIntFunction}
	 * labeled alternative in {@link PredicateParser#function}.
	 * @param ctx the parse tree
	 * @return the visitor result
	 */
	T visitRandomIntFunction(PredicateParser.RandomIntFunctionContext ctx);
}