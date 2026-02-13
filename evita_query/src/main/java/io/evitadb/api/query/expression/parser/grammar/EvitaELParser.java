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

import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.*;
import org.antlr.v4.runtime.tree.*;
import java.util.List;
import java.util.Iterator;
import java.util.ArrayList;

@SuppressWarnings({"all", "warnings", "unchecked", "unused", "cast", "CheckReturnValue", "this-escape"})
public class EvitaELParser extends Parser {
	static { RuntimeMetaData.checkVersion("4.13.2", RuntimeMetaData.VERSION); }

	protected static final DFA[] _decisionToDFA;
	protected static final PredictionContextCache _sharedContextCache =
		new PredictionContextCache();
	public static final int
		LPAREN=1, RPAREN=2, LBRACKET=3, RBRACKET=4, DOUBLE_QUESTION_MARK=5, QUESTION_MARK=6,
		STAR_QUESTION_MARK=7, DOT_STAR=8, DOT=9, EXCLAMATION_MARK=10, COMMA=11,
		PLUS=12, MINUS=13, DIV=14, STAR=15, PERCENT=16, GT=17, GT_EQ=18, LT=19,
		LT_EQ=20, EQ=21, NOT_EQ=22, XOR=23, AND=24, OR=25, INT=26, FLOAT=27, BOOLEAN=28,
		IDENTIFIER=29, VARIABLE=30, STRING=31, WS=32;
	public static final int
		RULE_root = 0, RULE_expression = 1, RULE_operandOperationOperand = 2,
		RULE_elementAccessExpression = 3, RULE_propertyAccessExpression = 4, RULE_spreadAccessExpression = 5,
		RULE_variable = 6, RULE_literal = 7;
	private static String[] makeRuleNames() {
		return new String[] {
			"root", "expression", "operandOperationOperand", "elementAccessExpression",
			"propertyAccessExpression", "spreadAccessExpression", "variable", "literal"
		};
	}
	public static final String[] ruleNames = makeRuleNames();

	private static String[] makeLiteralNames() {
		return new String[] {
			null, "'('", "')'", "'['", "']'", "'??'", "'?'", "'*?'", "'.*'", "'.'",
			"'!'", "','", "'+'", "'-'", "'/'", "'*'", "'%'", "'>'", "'>='", "'<'",
			"'<='", "'=='", "'!='", "'^'", "'&&'", "'||'"
		};
	}
	private static final String[] _LITERAL_NAMES = makeLiteralNames();
	private static String[] makeSymbolicNames() {
		return new String[] {
			null, "LPAREN", "RPAREN", "LBRACKET", "RBRACKET", "DOUBLE_QUESTION_MARK",
			"QUESTION_MARK", "STAR_QUESTION_MARK", "DOT_STAR", "DOT", "EXCLAMATION_MARK",
			"COMMA", "PLUS", "MINUS", "DIV", "STAR", "PERCENT", "GT", "GT_EQ", "LT",
			"LT_EQ", "EQ", "NOT_EQ", "XOR", "AND", "OR", "INT", "FLOAT", "BOOLEAN",
			"IDENTIFIER", "VARIABLE", "STRING", "WS"
		};
	}
	private static final String[] _SYMBOLIC_NAMES = makeSymbolicNames();
	public static final Vocabulary VOCABULARY = new VocabularyImpl(_LITERAL_NAMES, _SYMBOLIC_NAMES);

	/**
	 * @deprecated Use {@link #VOCABULARY} instead.
	 */
	@Deprecated
	public static final String[] tokenNames;
	static {
		tokenNames = new String[_SYMBOLIC_NAMES.length];
		for (int i = 0; i < tokenNames.length; i++) {
			tokenNames[i] = VOCABULARY.getLiteralName(i);
			if (tokenNames[i] == null) {
				tokenNames[i] = VOCABULARY.getSymbolicName(i);
			}

			if (tokenNames[i] == null) {
				tokenNames[i] = "<INVALID>";
			}
		}
	}

	@Override
	@Deprecated
	public String[] getTokenNames() {
		return tokenNames;
	}

	@Override

	public Vocabulary getVocabulary() {
		return VOCABULARY;
	}

	@Override
	public String getGrammarFileName() { return "EvitaEL.g4"; }

	@Override
	public String[] getRuleNames() { return ruleNames; }

	@Override
	public String getSerializedATN() { return _serializedATN; }

	@Override
	public ATN getATN() { return _ATN; }

	public EvitaELParser(TokenStream input) {
		super(input);
		_interp = new ParserATNSimulator(this,_ATN,_decisionToDFA,_sharedContextCache);
	}

	@SuppressWarnings("CheckReturnValue")
	public static class RootContext extends ParserRuleContext {
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public TerminalNode EOF() { return getToken(EvitaELParser.EOF, 0); }
		public RootContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_root; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterRoot(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitRoot(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitRoot(this);
			else return visitor.visitChildren(this);
		}
	}

	public final RootContext root() throws RecognitionException {
		RootContext _localctx = new RootContext(_ctx, getState());
		enterRule(_localctx, 0, RULE_root);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(16);
			expression(0);
			setState(17);
			match(EOF);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class ExpressionContext extends ParserRuleContext {
		public ExpressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_expression; }

		public ExpressionContext() { }
		public void copyFrom(ExpressionContext ctx) {
			super.copyFrom(ctx);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class ObjectAccessExpressionContext extends ExpressionContext {
		public OperandOperationOperandContext operand;
		public OperandOperationOperandContext operandOperationOperand() {
			return getRuleContext(OperandOperationOperandContext.class,0);
		}
		public List<ElementAccessExpressionContext> elementAccessExpression() {
			return getRuleContexts(ElementAccessExpressionContext.class);
		}
		public ElementAccessExpressionContext elementAccessExpression(int i) {
			return getRuleContext(ElementAccessExpressionContext.class,i);
		}
		public List<PropertyAccessExpressionContext> propertyAccessExpression() {
			return getRuleContexts(PropertyAccessExpressionContext.class);
		}
		public PropertyAccessExpressionContext propertyAccessExpression(int i) {
			return getRuleContext(PropertyAccessExpressionContext.class,i);
		}
		public List<SpreadAccessExpressionContext> spreadAccessExpression() {
			return getRuleContexts(SpreadAccessExpressionContext.class);
		}
		public SpreadAccessExpressionContext spreadAccessExpression(int i) {
			return getRuleContext(SpreadAccessExpressionContext.class,i);
		}
		public ObjectAccessExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterObjectAccessExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitObjectAccessExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitObjectAccessExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class SpreadNullCoalesceExpressionContext extends ExpressionContext {
		public ExpressionContext value;
		public Token nullSafe;
		public ExpressionContext defaultValue;
		public TerminalNode STAR_QUESTION_MARK() { return getToken(EvitaELParser.STAR_QUESTION_MARK, 0); }
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public TerminalNode QUESTION_MARK() { return getToken(EvitaELParser.QUESTION_MARK, 0); }
		public SpreadNullCoalesceExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterSpreadNullCoalesceExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitSpreadNullCoalesceExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitSpreadNullCoalesceExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class NegativeExpressionContext extends ExpressionContext {
		public ExpressionContext nested;
		public TerminalNode MINUS() { return getToken(EvitaELParser.MINUS, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public NegativeExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterNegativeExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitNegativeExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitNegativeExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class AdditionExpressionContext extends ExpressionContext {
		public ExpressionContext leftExpression;
		public ExpressionContext rightExpression;
		public TerminalNode PLUS() { return getToken(EvitaELParser.PLUS, 0); }
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public AdditionExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterAdditionExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitAdditionExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitAdditionExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class ModuloExpressionContext extends ExpressionContext {
		public ExpressionContext leftExpression;
		public ExpressionContext rightExpression;
		public TerminalNode PERCENT() { return getToken(EvitaELParser.PERCENT, 0); }
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public ModuloExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterModuloExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitModuloExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitModuloExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class XorExpressionContext extends ExpressionContext {
		public ExpressionContext leftExpression;
		public ExpressionContext rightExpression;
		public TerminalNode XOR() { return getToken(EvitaELParser.XOR, 0); }
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public XorExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterXorExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitXorExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitXorExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class MultiplicationExpressionContext extends ExpressionContext {
		public ExpressionContext leftExpression;
		public ExpressionContext rightExpression;
		public TerminalNode STAR() { return getToken(EvitaELParser.STAR, 0); }
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public MultiplicationExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterMultiplicationExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitMultiplicationExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitMultiplicationExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class GreaterThanExpressionContext extends ExpressionContext {
		public ExpressionContext leftExpression;
		public ExpressionContext rightExpression;
		public TerminalNode GT() { return getToken(EvitaELParser.GT, 0); }
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public GreaterThanExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterGreaterThanExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitGreaterThanExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitGreaterThanExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class VariableExpressionContext extends ExpressionContext {
		public VariableContext variable() {
			return getRuleContext(VariableContext.class,0);
		}
		public VariableExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterVariableExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitVariableExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitVariableExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class NullCoalesceExpressionContext extends ExpressionContext {
		public ExpressionContext value;
		public ExpressionContext defaultValue;
		public TerminalNode DOUBLE_QUESTION_MARK() { return getToken(EvitaELParser.DOUBLE_QUESTION_MARK, 0); }
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public NullCoalesceExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterNullCoalesceExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitNullCoalesceExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitNullCoalesceExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class FunctionExpressionContext extends ExpressionContext {
		public Token functionName;
		public ExpressionContext expression;
		public List<ExpressionContext> arguments = new ArrayList<ExpressionContext>();
		public TerminalNode LPAREN() { return getToken(EvitaELParser.LPAREN, 0); }
		public TerminalNode RPAREN() { return getToken(EvitaELParser.RPAREN, 0); }
		public TerminalNode IDENTIFIER() { return getToken(EvitaELParser.IDENTIFIER, 0); }
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public List<TerminalNode> COMMA() { return getTokens(EvitaELParser.COMMA); }
		public TerminalNode COMMA(int i) {
			return getToken(EvitaELParser.COMMA, i);
		}
		public FunctionExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterFunctionExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitFunctionExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitFunctionExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class GreaterThanEqualsExpressionContext extends ExpressionContext {
		public ExpressionContext leftExpression;
		public ExpressionContext rightExpression;
		public TerminalNode GT_EQ() { return getToken(EvitaELParser.GT_EQ, 0); }
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public GreaterThanEqualsExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterGreaterThanEqualsExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitGreaterThanEqualsExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitGreaterThanEqualsExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class LessThanEqualsExpressionContext extends ExpressionContext {
		public ExpressionContext leftExpression;
		public ExpressionContext rightExpression;
		public TerminalNode LT_EQ() { return getToken(EvitaELParser.LT_EQ, 0); }
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public LessThanEqualsExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterLessThanEqualsExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitLessThanEqualsExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitLessThanEqualsExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class OrExpressionContext extends ExpressionContext {
		public ExpressionContext leftExpression;
		public ExpressionContext rightExpression;
		public TerminalNode OR() { return getToken(EvitaELParser.OR, 0); }
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public OrExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterOrExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitOrExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitOrExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class SubstractionExpressionContext extends ExpressionContext {
		public ExpressionContext leftExpression;
		public ExpressionContext rightExpression;
		public TerminalNode MINUS() { return getToken(EvitaELParser.MINUS, 0); }
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public SubstractionExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterSubstractionExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitSubstractionExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitSubstractionExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class NotEqualsExpressionContext extends ExpressionContext {
		public ExpressionContext leftExpression;
		public ExpressionContext rightExpression;
		public TerminalNode NOT_EQ() { return getToken(EvitaELParser.NOT_EQ, 0); }
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public NotEqualsExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterNotEqualsExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitNotEqualsExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitNotEqualsExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class AndExpressionContext extends ExpressionContext {
		public ExpressionContext leftExpression;
		public ExpressionContext rightExpression;
		public TerminalNode AND() { return getToken(EvitaELParser.AND, 0); }
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public AndExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterAndExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitAndExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitAndExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class EqualsExpressionContext extends ExpressionContext {
		public ExpressionContext leftExpression;
		public ExpressionContext rightExpression;
		public TerminalNode EQ() { return getToken(EvitaELParser.EQ, 0); }
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public EqualsExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterEqualsExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitEqualsExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitEqualsExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class NestedExpressionContext extends ExpressionContext {
		public ExpressionContext nested;
		public TerminalNode LPAREN() { return getToken(EvitaELParser.LPAREN, 0); }
		public TerminalNode RPAREN() { return getToken(EvitaELParser.RPAREN, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public NestedExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterNestedExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitNestedExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitNestedExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class PositiveExpressionContext extends ExpressionContext {
		public ExpressionContext nested;
		public TerminalNode PLUS() { return getToken(EvitaELParser.PLUS, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public PositiveExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterPositiveExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitPositiveExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitPositiveExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class LessThanExpressionContext extends ExpressionContext {
		public ExpressionContext leftExpression;
		public ExpressionContext rightExpression;
		public TerminalNode LT() { return getToken(EvitaELParser.LT, 0); }
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public LessThanExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterLessThanExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitLessThanExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitLessThanExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class LiteralExpressionContext extends ExpressionContext {
		public LiteralContext literal() {
			return getRuleContext(LiteralContext.class,0);
		}
		public LiteralExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterLiteralExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitLiteralExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitLiteralExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class NegatingExpressionContext extends ExpressionContext {
		public ExpressionContext nested;
		public TerminalNode EXCLAMATION_MARK() { return getToken(EvitaELParser.EXCLAMATION_MARK, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public NegatingExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterNegatingExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitNegatingExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitNegatingExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class DivisionExpressionContext extends ExpressionContext {
		public ExpressionContext leftExpression;
		public ExpressionContext rightExpression;
		public TerminalNode DIV() { return getToken(EvitaELParser.DIV, 0); }
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public DivisionExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterDivisionExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitDivisionExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitDivisionExpression(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ExpressionContext expression() throws RecognitionException {
		return expression(0);
	}

	private ExpressionContext expression(int _p) throws RecognitionException {
		ParserRuleContext _parentctx = _ctx;
		int _parentState = getState();
		ExpressionContext _localctx = new ExpressionContext(_ctx, _parentState);
		ExpressionContext _prevctx = _localctx;
		int _startState = 2;
		enterRecursionRule(_localctx, 2, RULE_expression, _p);
		int _la;
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(54);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,4,_ctx) ) {
			case 1:
				{
				_localctx = new LiteralExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;

				setState(20);
				literal();
				}
				break;
			case 2:
				{
				_localctx = new VariableExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(21);
				variable();
				}
				break;
			case 3:
				{
				_localctx = new FunctionExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(22);
				((FunctionExpressionContext)_localctx).functionName = match(IDENTIFIER);
				setState(23);
				match(LPAREN);
				setState(32);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if ((((_la) & ~0x3f) == 0 && ((1L << _la) & 4227871746L) != 0)) {
					{
					setState(24);
					((FunctionExpressionContext)_localctx).expression = expression(0);
					((FunctionExpressionContext)_localctx).arguments.add(((FunctionExpressionContext)_localctx).expression);
					setState(29);
					_errHandler.sync(this);
					_la = _input.LA(1);
					while (_la==COMMA) {
						{
						{
						setState(25);
						match(COMMA);
						setState(26);
						((FunctionExpressionContext)_localctx).expression = expression(0);
						((FunctionExpressionContext)_localctx).arguments.add(((FunctionExpressionContext)_localctx).expression);
						}
						}
						setState(31);
						_errHandler.sync(this);
						_la = _input.LA(1);
					}
					}
				}

				setState(34);
				match(RPAREN);
				}
				break;
			case 4:
				{
				_localctx = new NestedExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(35);
				match(LPAREN);
				setState(36);
				((NestedExpressionContext)_localctx).nested = expression(0);
				setState(37);
				match(RPAREN);
				}
				break;
			case 5:
				{
				_localctx = new ObjectAccessExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(39);
				((ObjectAccessExpressionContext)_localctx).operand = operandOperationOperand();
				setState(45);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,3,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						setState(43);
						_errHandler.sync(this);
						switch ( getInterpreter().adaptivePredict(_input,2,_ctx) ) {
						case 1:
							{
							setState(40);
							elementAccessExpression();
							}
							break;
						case 2:
							{
							setState(41);
							propertyAccessExpression();
							}
							break;
						case 3:
							{
							setState(42);
							spreadAccessExpression();
							}
							break;
						}
						}
					}
					setState(47);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,3,_ctx);
				}
				}
				break;
			case 6:
				{
				_localctx = new NegatingExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(48);
				match(EXCLAMATION_MARK);
				setState(49);
				((NegatingExpressionContext)_localctx).nested = expression(19);
				}
				break;
			case 7:
				{
				_localctx = new PositiveExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(50);
				match(PLUS);
				setState(51);
				((PositiveExpressionContext)_localctx).nested = expression(18);
				}
				break;
			case 8:
				{
				_localctx = new NegativeExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(52);
				match(MINUS);
				setState(53);
				((NegativeExpressionContext)_localctx).nested = expression(17);
				}
				break;
			}
			_ctx.stop = _input.LT(-1);
			setState(109);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,7,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					if ( _parseListeners!=null ) triggerExitRuleEvent();
					_prevctx = _localctx;
					{
					setState(107);
					_errHandler.sync(this);
					switch ( getInterpreter().adaptivePredict(_input,6,_ctx) ) {
					case 1:
						{
						_localctx = new MultiplicationExpressionContext(new ExpressionContext(_parentctx, _parentState));
						((MultiplicationExpressionContext)_localctx).leftExpression = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(56);
						if (!(precpred(_ctx, 16))) throw new FailedPredicateException(this, "precpred(_ctx, 16)");
						setState(57);
						match(STAR);
						setState(58);
						((MultiplicationExpressionContext)_localctx).rightExpression = expression(17);
						}
						break;
					case 2:
						{
						_localctx = new DivisionExpressionContext(new ExpressionContext(_parentctx, _parentState));
						((DivisionExpressionContext)_localctx).leftExpression = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(59);
						if (!(precpred(_ctx, 15))) throw new FailedPredicateException(this, "precpred(_ctx, 15)");
						setState(60);
						match(DIV);
						setState(61);
						((DivisionExpressionContext)_localctx).rightExpression = expression(16);
						}
						break;
					case 3:
						{
						_localctx = new ModuloExpressionContext(new ExpressionContext(_parentctx, _parentState));
						((ModuloExpressionContext)_localctx).leftExpression = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(62);
						if (!(precpred(_ctx, 14))) throw new FailedPredicateException(this, "precpred(_ctx, 14)");
						setState(63);
						match(PERCENT);
						setState(64);
						((ModuloExpressionContext)_localctx).rightExpression = expression(15);
						}
						break;
					case 4:
						{
						_localctx = new AdditionExpressionContext(new ExpressionContext(_parentctx, _parentState));
						((AdditionExpressionContext)_localctx).leftExpression = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(65);
						if (!(precpred(_ctx, 13))) throw new FailedPredicateException(this, "precpred(_ctx, 13)");
						setState(66);
						match(PLUS);
						setState(67);
						((AdditionExpressionContext)_localctx).rightExpression = expression(14);
						}
						break;
					case 5:
						{
						_localctx = new SubstractionExpressionContext(new ExpressionContext(_parentctx, _parentState));
						((SubstractionExpressionContext)_localctx).leftExpression = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(68);
						if (!(precpred(_ctx, 12))) throw new FailedPredicateException(this, "precpred(_ctx, 12)");
						setState(69);
						match(MINUS);
						setState(70);
						((SubstractionExpressionContext)_localctx).rightExpression = expression(13);
						}
						break;
					case 6:
						{
						_localctx = new GreaterThanExpressionContext(new ExpressionContext(_parentctx, _parentState));
						((GreaterThanExpressionContext)_localctx).leftExpression = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(71);
						if (!(precpred(_ctx, 11))) throw new FailedPredicateException(this, "precpred(_ctx, 11)");
						setState(72);
						match(GT);
						setState(73);
						((GreaterThanExpressionContext)_localctx).rightExpression = expression(12);
						}
						break;
					case 7:
						{
						_localctx = new GreaterThanEqualsExpressionContext(new ExpressionContext(_parentctx, _parentState));
						((GreaterThanEqualsExpressionContext)_localctx).leftExpression = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(74);
						if (!(precpred(_ctx, 10))) throw new FailedPredicateException(this, "precpred(_ctx, 10)");
						setState(75);
						match(GT_EQ);
						setState(76);
						((GreaterThanEqualsExpressionContext)_localctx).rightExpression = expression(11);
						}
						break;
					case 8:
						{
						_localctx = new LessThanExpressionContext(new ExpressionContext(_parentctx, _parentState));
						((LessThanExpressionContext)_localctx).leftExpression = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(77);
						if (!(precpred(_ctx, 9))) throw new FailedPredicateException(this, "precpred(_ctx, 9)");
						setState(78);
						match(LT);
						setState(79);
						((LessThanExpressionContext)_localctx).rightExpression = expression(10);
						}
						break;
					case 9:
						{
						_localctx = new LessThanEqualsExpressionContext(new ExpressionContext(_parentctx, _parentState));
						((LessThanEqualsExpressionContext)_localctx).leftExpression = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(80);
						if (!(precpred(_ctx, 8))) throw new FailedPredicateException(this, "precpred(_ctx, 8)");
						setState(81);
						match(LT_EQ);
						setState(82);
						((LessThanEqualsExpressionContext)_localctx).rightExpression = expression(9);
						}
						break;
					case 10:
						{
						_localctx = new EqualsExpressionContext(new ExpressionContext(_parentctx, _parentState));
						((EqualsExpressionContext)_localctx).leftExpression = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(83);
						if (!(precpred(_ctx, 7))) throw new FailedPredicateException(this, "precpred(_ctx, 7)");
						setState(84);
						match(EQ);
						setState(85);
						((EqualsExpressionContext)_localctx).rightExpression = expression(8);
						}
						break;
					case 11:
						{
						_localctx = new NotEqualsExpressionContext(new ExpressionContext(_parentctx, _parentState));
						((NotEqualsExpressionContext)_localctx).leftExpression = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(86);
						if (!(precpred(_ctx, 6))) throw new FailedPredicateException(this, "precpred(_ctx, 6)");
						setState(87);
						match(NOT_EQ);
						setState(88);
						((NotEqualsExpressionContext)_localctx).rightExpression = expression(7);
						}
						break;
					case 12:
						{
						_localctx = new XorExpressionContext(new ExpressionContext(_parentctx, _parentState));
						((XorExpressionContext)_localctx).leftExpression = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(89);
						if (!(precpred(_ctx, 5))) throw new FailedPredicateException(this, "precpred(_ctx, 5)");
						setState(90);
						match(XOR);
						setState(91);
						((XorExpressionContext)_localctx).rightExpression = expression(6);
						}
						break;
					case 13:
						{
						_localctx = new AndExpressionContext(new ExpressionContext(_parentctx, _parentState));
						((AndExpressionContext)_localctx).leftExpression = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(92);
						if (!(precpred(_ctx, 4))) throw new FailedPredicateException(this, "precpred(_ctx, 4)");
						setState(93);
						match(AND);
						setState(94);
						((AndExpressionContext)_localctx).rightExpression = expression(5);
						}
						break;
					case 14:
						{
						_localctx = new OrExpressionContext(new ExpressionContext(_parentctx, _parentState));
						((OrExpressionContext)_localctx).leftExpression = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(95);
						if (!(precpred(_ctx, 3))) throw new FailedPredicateException(this, "precpred(_ctx, 3)");
						setState(96);
						match(OR);
						setState(97);
						((OrExpressionContext)_localctx).rightExpression = expression(4);
						}
						break;
					case 15:
						{
						_localctx = new SpreadNullCoalesceExpressionContext(new ExpressionContext(_parentctx, _parentState));
						((SpreadNullCoalesceExpressionContext)_localctx).value = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(98);
						if (!(precpred(_ctx, 2))) throw new FailedPredicateException(this, "precpred(_ctx, 2)");
						setState(100);
						_errHandler.sync(this);
						_la = _input.LA(1);
						if (_la==QUESTION_MARK) {
							{
							setState(99);
							((SpreadNullCoalesceExpressionContext)_localctx).nullSafe = match(QUESTION_MARK);
							}
						}

						setState(102);
						match(STAR_QUESTION_MARK);
						setState(103);
						((SpreadNullCoalesceExpressionContext)_localctx).defaultValue = expression(3);
						}
						break;
					case 16:
						{
						_localctx = new NullCoalesceExpressionContext(new ExpressionContext(_parentctx, _parentState));
						((NullCoalesceExpressionContext)_localctx).value = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(104);
						if (!(precpred(_ctx, 1))) throw new FailedPredicateException(this, "precpred(_ctx, 1)");
						setState(105);
						match(DOUBLE_QUESTION_MARK);
						setState(106);
						((NullCoalesceExpressionContext)_localctx).defaultValue = expression(2);
						}
						break;
					}
					}
				}
				setState(111);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,7,_ctx);
			}
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			unrollRecursionContexts(_parentctx);
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class OperandOperationOperandContext extends ParserRuleContext {
		public OperandOperationOperandContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_operandOperationOperand; }

		public OperandOperationOperandContext() { }
		public void copyFrom(OperandOperationOperandContext ctx) {
			super.copyFrom(ctx);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class VariableCallOperandContext extends OperandOperationOperandContext {
		public VariableContext variable() {
			return getRuleContext(VariableContext.class,0);
		}
		public VariableCallOperandContext(OperandOperationOperandContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterVariableCallOperand(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitVariableCallOperand(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitVariableCallOperand(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class NestedExpressionCallOperandContext extends OperandOperationOperandContext {
		public ExpressionContext nested;
		public TerminalNode LPAREN() { return getToken(EvitaELParser.LPAREN, 0); }
		public TerminalNode RPAREN() { return getToken(EvitaELParser.RPAREN, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public NestedExpressionCallOperandContext(OperandOperationOperandContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterNestedExpressionCallOperand(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitNestedExpressionCallOperand(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitNestedExpressionCallOperand(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class LiteralCallOperandContext extends OperandOperationOperandContext {
		public LiteralContext literal() {
			return getRuleContext(LiteralContext.class,0);
		}
		public LiteralCallOperandContext(OperandOperationOperandContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterLiteralCallOperand(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitLiteralCallOperand(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitLiteralCallOperand(this);
			else return visitor.visitChildren(this);
		}
	}

	public final OperandOperationOperandContext operandOperationOperand() throws RecognitionException {
		OperandOperationOperandContext _localctx = new OperandOperationOperandContext(_ctx, getState());
		enterRule(_localctx, 4, RULE_operandOperationOperand);
		try {
			setState(118);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case INT:
			case FLOAT:
			case BOOLEAN:
			case STRING:
				_localctx = new LiteralCallOperandContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(112);
				literal();
				}
				break;
			case VARIABLE:
				_localctx = new VariableCallOperandContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(113);
				variable();
				}
				break;
			case LPAREN:
				_localctx = new NestedExpressionCallOperandContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(114);
				match(LPAREN);
				setState(115);
				((NestedExpressionCallOperandContext)_localctx).nested = expression(0);
				setState(116);
				match(RPAREN);
				}
				break;
			default:
				throw new NoViableAltException(this);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class ElementAccessExpressionContext extends ParserRuleContext {
		public Token nullSafe;
		public ExpressionContext elementIdentifier;
		public TerminalNode LBRACKET() { return getToken(EvitaELParser.LBRACKET, 0); }
		public TerminalNode RBRACKET() { return getToken(EvitaELParser.RBRACKET, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public TerminalNode QUESTION_MARK() { return getToken(EvitaELParser.QUESTION_MARK, 0); }
		public ElementAccessExpressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_elementAccessExpression; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterElementAccessExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitElementAccessExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitElementAccessExpression(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ElementAccessExpressionContext elementAccessExpression() throws RecognitionException {
		ElementAccessExpressionContext _localctx = new ElementAccessExpressionContext(_ctx, getState());
		enterRule(_localctx, 6, RULE_elementAccessExpression);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(121);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==QUESTION_MARK) {
				{
				setState(120);
				((ElementAccessExpressionContext)_localctx).nullSafe = match(QUESTION_MARK);
				}
			}

			setState(123);
			match(LBRACKET);
			setState(124);
			((ElementAccessExpressionContext)_localctx).elementIdentifier = expression(0);
			setState(125);
			match(RBRACKET);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class PropertyAccessExpressionContext extends ParserRuleContext {
		public Token nullSafe;
		public Token propertyIdentifier;
		public TerminalNode DOT() { return getToken(EvitaELParser.DOT, 0); }
		public TerminalNode IDENTIFIER() { return getToken(EvitaELParser.IDENTIFIER, 0); }
		public TerminalNode QUESTION_MARK() { return getToken(EvitaELParser.QUESTION_MARK, 0); }
		public PropertyAccessExpressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_propertyAccessExpression; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterPropertyAccessExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitPropertyAccessExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitPropertyAccessExpression(this);
			else return visitor.visitChildren(this);
		}
	}

	public final PropertyAccessExpressionContext propertyAccessExpression() throws RecognitionException {
		PropertyAccessExpressionContext _localctx = new PropertyAccessExpressionContext(_ctx, getState());
		enterRule(_localctx, 8, RULE_propertyAccessExpression);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(128);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==QUESTION_MARK) {
				{
				setState(127);
				((PropertyAccessExpressionContext)_localctx).nullSafe = match(QUESTION_MARK);
				}
			}

			setState(130);
			match(DOT);
			setState(131);
			((PropertyAccessExpressionContext)_localctx).propertyIdentifier = match(IDENTIFIER);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class SpreadAccessExpressionContext extends ParserRuleContext {
		public Token nullSafe;
		public Token compact;
		public ExpressionContext itemAccessExpression;
		public TerminalNode DOT_STAR() { return getToken(EvitaELParser.DOT_STAR, 0); }
		public TerminalNode LBRACKET() { return getToken(EvitaELParser.LBRACKET, 0); }
		public TerminalNode RBRACKET() { return getToken(EvitaELParser.RBRACKET, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public TerminalNode QUESTION_MARK() { return getToken(EvitaELParser.QUESTION_MARK, 0); }
		public TerminalNode EXCLAMATION_MARK() { return getToken(EvitaELParser.EXCLAMATION_MARK, 0); }
		public SpreadAccessExpressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_spreadAccessExpression; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterSpreadAccessExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitSpreadAccessExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitSpreadAccessExpression(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SpreadAccessExpressionContext spreadAccessExpression() throws RecognitionException {
		SpreadAccessExpressionContext _localctx = new SpreadAccessExpressionContext(_ctx, getState());
		enterRule(_localctx, 10, RULE_spreadAccessExpression);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(134);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==QUESTION_MARK) {
				{
				setState(133);
				((SpreadAccessExpressionContext)_localctx).nullSafe = match(QUESTION_MARK);
				}
			}

			setState(136);
			match(DOT_STAR);
			setState(138);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==EXCLAMATION_MARK) {
				{
				setState(137);
				((SpreadAccessExpressionContext)_localctx).compact = match(EXCLAMATION_MARK);
				}
			}

			setState(140);
			match(LBRACKET);
			setState(141);
			((SpreadAccessExpressionContext)_localctx).itemAccessExpression = expression(0);
			setState(142);
			match(RBRACKET);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class VariableContext extends ParserRuleContext {
		public TerminalNode VARIABLE() { return getToken(EvitaELParser.VARIABLE, 0); }
		public VariableContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_variable; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterVariable(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitVariable(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitVariable(this);
			else return visitor.visitChildren(this);
		}
	}

	public final VariableContext variable() throws RecognitionException {
		VariableContext _localctx = new VariableContext(_ctx, getState());
		enterRule(_localctx, 12, RULE_variable);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(144);
			match(VARIABLE);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	@SuppressWarnings("CheckReturnValue")
	public static class LiteralContext extends ParserRuleContext {
		public LiteralContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_literal; }

		public LiteralContext() { }
		public void copyFrom(LiteralContext ctx) {
			super.copyFrom(ctx);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class IntValueTokenContext extends LiteralContext {
		public TerminalNode INT() { return getToken(EvitaELParser.INT, 0); }
		public IntValueTokenContext(LiteralContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterIntValueToken(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitIntValueToken(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitIntValueToken(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class StringValueTokenContext extends LiteralContext {
		public TerminalNode STRING() { return getToken(EvitaELParser.STRING, 0); }
		public StringValueTokenContext(LiteralContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterStringValueToken(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitStringValueToken(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitStringValueToken(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class FloatValueTokenContext extends LiteralContext {
		public TerminalNode FLOAT() { return getToken(EvitaELParser.FLOAT, 0); }
		public FloatValueTokenContext(LiteralContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterFloatValueToken(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitFloatValueToken(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitFloatValueToken(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class BooleanValueTokenContext extends LiteralContext {
		public TerminalNode BOOLEAN() { return getToken(EvitaELParser.BOOLEAN, 0); }
		public BooleanValueTokenContext(LiteralContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).enterBooleanValueToken(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaELListener ) ((EvitaELListener)listener).exitBooleanValueToken(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaELVisitor ) return ((EvitaELVisitor<? extends T>)visitor).visitBooleanValueToken(this);
			else return visitor.visitChildren(this);
		}
	}

	public final LiteralContext literal() throws RecognitionException {
		LiteralContext _localctx = new LiteralContext(_ctx, getState());
		enterRule(_localctx, 14, RULE_literal);
		try {
			setState(150);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case STRING:
				_localctx = new StringValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(146);
				match(STRING);
				}
				break;
			case INT:
				_localctx = new IntValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(147);
				match(INT);
				}
				break;
			case FLOAT:
				_localctx = new FloatValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(148);
				match(FLOAT);
				}
				break;
			case BOOLEAN:
				_localctx = new BooleanValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 4);
				{
				setState(149);
				match(BOOLEAN);
				}
				break;
			default:
				throw new NoViableAltException(this);
			}
		}
		catch (RecognitionException re) {
			_localctx.exception = re;
			_errHandler.reportError(this, re);
			_errHandler.recover(this, re);
		}
		finally {
			exitRule();
		}
		return _localctx;
	}

	public boolean sempred(RuleContext _localctx, int ruleIndex, int predIndex) {
		switch (ruleIndex) {
		case 1:
			return expression_sempred((ExpressionContext)_localctx, predIndex);
		}
		return true;
	}
	private boolean expression_sempred(ExpressionContext _localctx, int predIndex) {
		switch (predIndex) {
		case 0:
			return precpred(_ctx, 16);
		case 1:
			return precpred(_ctx, 15);
		case 2:
			return precpred(_ctx, 14);
		case 3:
			return precpred(_ctx, 13);
		case 4:
			return precpred(_ctx, 12);
		case 5:
			return precpred(_ctx, 11);
		case 6:
			return precpred(_ctx, 10);
		case 7:
			return precpred(_ctx, 9);
		case 8:
			return precpred(_ctx, 8);
		case 9:
			return precpred(_ctx, 7);
		case 10:
			return precpred(_ctx, 6);
		case 11:
			return precpred(_ctx, 5);
		case 12:
			return precpred(_ctx, 4);
		case 13:
			return precpred(_ctx, 3);
		case 14:
			return precpred(_ctx, 2);
		case 15:
			return precpred(_ctx, 1);
		}
		return true;
	}

	public static final String _serializedATN =
		"\u0004\u0001 \u0099\u0002\u0000\u0007\u0000\u0002\u0001\u0007\u0001\u0002"+
		"\u0002\u0007\u0002\u0002\u0003\u0007\u0003\u0002\u0004\u0007\u0004\u0002"+
		"\u0005\u0007\u0005\u0002\u0006\u0007\u0006\u0002\u0007\u0007\u0007\u0001"+
		"\u0000\u0001\u0000\u0001\u0000\u0001\u0001\u0001\u0001\u0001\u0001\u0001"+
		"\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0005\u0001\u001c"+
		"\b\u0001\n\u0001\f\u0001\u001f\t\u0001\u0003\u0001!\b\u0001\u0001\u0001"+
		"\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001"+
		"\u0001\u0001\u0001\u0001\u0005\u0001,\b\u0001\n\u0001\f\u0001/\t\u0001"+
		"\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001"+
		"\u0003\u00017\b\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001"+
		"\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001"+
		"\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001"+
		"\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001"+
		"\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001"+
		"\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001"+
		"\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001"+
		"\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0003\u0001e\b\u0001"+
		"\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0001\u0005\u0001"+
		"l\b\u0001\n\u0001\f\u0001o\t\u0001\u0001\u0002\u0001\u0002\u0001\u0002"+
		"\u0001\u0002\u0001\u0002\u0001\u0002\u0003\u0002w\b\u0002\u0001\u0003"+
		"\u0003\u0003z\b\u0003\u0001\u0003\u0001\u0003\u0001\u0003\u0001\u0003"+
		"\u0001\u0004\u0003\u0004\u0081\b\u0004\u0001\u0004\u0001\u0004\u0001\u0004"+
		"\u0001\u0005\u0003\u0005\u0087\b\u0005\u0001\u0005\u0001\u0005\u0003\u0005"+
		"\u008b\b\u0005\u0001\u0005\u0001\u0005\u0001\u0005\u0001\u0005\u0001\u0006"+
		"\u0001\u0006\u0001\u0007\u0001\u0007\u0001\u0007\u0001\u0007\u0003\u0007"+
		"\u0097\b\u0007\u0001\u0007\u0000\u0001\u0002\b\u0000\u0002\u0004\u0006"+
		"\b\n\f\u000e\u0000\u0000\u00b6\u0000\u0010\u0001\u0000\u0000\u0000\u0002"+
		"6\u0001\u0000\u0000\u0000\u0004v\u0001\u0000\u0000\u0000\u0006y\u0001"+
		"\u0000\u0000\u0000\b\u0080\u0001\u0000\u0000\u0000\n\u0086\u0001\u0000"+
		"\u0000\u0000\f\u0090\u0001\u0000\u0000\u0000\u000e\u0096\u0001\u0000\u0000"+
		"\u0000\u0010\u0011\u0003\u0002\u0001\u0000\u0011\u0012\u0005\u0000\u0000"+
		"\u0001\u0012\u0001\u0001\u0000\u0000\u0000\u0013\u0014\u0006\u0001\uffff"+
		"\uffff\u0000\u00147\u0003\u000e\u0007\u0000\u00157\u0003\f\u0006\u0000"+
		"\u0016\u0017\u0005\u001d\u0000\u0000\u0017 \u0005\u0001\u0000\u0000\u0018"+
		"\u001d\u0003\u0002\u0001\u0000\u0019\u001a\u0005\u000b\u0000\u0000\u001a"+
		"\u001c\u0003\u0002\u0001\u0000\u001b\u0019\u0001\u0000\u0000\u0000\u001c"+
		"\u001f\u0001\u0000\u0000\u0000\u001d\u001b\u0001\u0000\u0000\u0000\u001d"+
		"\u001e\u0001\u0000\u0000\u0000\u001e!\u0001\u0000\u0000\u0000\u001f\u001d"+
		"\u0001\u0000\u0000\u0000 \u0018\u0001\u0000\u0000\u0000 !\u0001\u0000"+
		"\u0000\u0000!\"\u0001\u0000\u0000\u0000\"7\u0005\u0002\u0000\u0000#$\u0005"+
		"\u0001\u0000\u0000$%\u0003\u0002\u0001\u0000%&\u0005\u0002\u0000\u0000"+
		"&7\u0001\u0000\u0000\u0000\'-\u0003\u0004\u0002\u0000(,\u0003\u0006\u0003"+
		"\u0000),\u0003\b\u0004\u0000*,\u0003\n\u0005\u0000+(\u0001\u0000\u0000"+
		"\u0000+)\u0001\u0000\u0000\u0000+*\u0001\u0000\u0000\u0000,/\u0001\u0000"+
		"\u0000\u0000-+\u0001\u0000\u0000\u0000-.\u0001\u0000\u0000\u0000.7\u0001"+
		"\u0000\u0000\u0000/-\u0001\u0000\u0000\u000001\u0005\n\u0000\u000017\u0003"+
		"\u0002\u0001\u001323\u0005\f\u0000\u000037\u0003\u0002\u0001\u001245\u0005"+
		"\r\u0000\u000057\u0003\u0002\u0001\u00116\u0013\u0001\u0000\u0000\u0000"+
		"6\u0015\u0001\u0000\u0000\u00006\u0016\u0001\u0000\u0000\u00006#\u0001"+
		"\u0000\u0000\u00006\'\u0001\u0000\u0000\u000060\u0001\u0000\u0000\u0000"+
		"62\u0001\u0000\u0000\u000064\u0001\u0000\u0000\u00007m\u0001\u0000\u0000"+
		"\u000089\n\u0010\u0000\u00009:\u0005\u000f\u0000\u0000:l\u0003\u0002\u0001"+
		"\u0011;<\n\u000f\u0000\u0000<=\u0005\u000e\u0000\u0000=l\u0003\u0002\u0001"+
		"\u0010>?\n\u000e\u0000\u0000?@\u0005\u0010\u0000\u0000@l\u0003\u0002\u0001"+
		"\u000fAB\n\r\u0000\u0000BC\u0005\f\u0000\u0000Cl\u0003\u0002\u0001\u000e"+
		"DE\n\f\u0000\u0000EF\u0005\r\u0000\u0000Fl\u0003\u0002\u0001\rGH\n\u000b"+
		"\u0000\u0000HI\u0005\u0011\u0000\u0000Il\u0003\u0002\u0001\fJK\n\n\u0000"+
		"\u0000KL\u0005\u0012\u0000\u0000Ll\u0003\u0002\u0001\u000bMN\n\t\u0000"+
		"\u0000NO\u0005\u0013\u0000\u0000Ol\u0003\u0002\u0001\nPQ\n\b\u0000\u0000"+
		"QR\u0005\u0014\u0000\u0000Rl\u0003\u0002\u0001\tST\n\u0007\u0000\u0000"+
		"TU\u0005\u0015\u0000\u0000Ul\u0003\u0002\u0001\bVW\n\u0006\u0000\u0000"+
		"WX\u0005\u0016\u0000\u0000Xl\u0003\u0002\u0001\u0007YZ\n\u0005\u0000\u0000"+
		"Z[\u0005\u0017\u0000\u0000[l\u0003\u0002\u0001\u0006\\]\n\u0004\u0000"+
		"\u0000]^\u0005\u0018\u0000\u0000^l\u0003\u0002\u0001\u0005_`\n\u0003\u0000"+
		"\u0000`a\u0005\u0019\u0000\u0000al\u0003\u0002\u0001\u0004bd\n\u0002\u0000"+
		"\u0000ce\u0005\u0006\u0000\u0000dc\u0001\u0000\u0000\u0000de\u0001\u0000"+
		"\u0000\u0000ef\u0001\u0000\u0000\u0000fg\u0005\u0007\u0000\u0000gl\u0003"+
		"\u0002\u0001\u0003hi\n\u0001\u0000\u0000ij\u0005\u0005\u0000\u0000jl\u0003"+
		"\u0002\u0001\u0002k8\u0001\u0000\u0000\u0000k;\u0001\u0000\u0000\u0000"+
		"k>\u0001\u0000\u0000\u0000kA\u0001\u0000\u0000\u0000kD\u0001\u0000\u0000"+
		"\u0000kG\u0001\u0000\u0000\u0000kJ\u0001\u0000\u0000\u0000kM\u0001\u0000"+
		"\u0000\u0000kP\u0001\u0000\u0000\u0000kS\u0001\u0000\u0000\u0000kV\u0001"+
		"\u0000\u0000\u0000kY\u0001\u0000\u0000\u0000k\\\u0001\u0000\u0000\u0000"+
		"k_\u0001\u0000\u0000\u0000kb\u0001\u0000\u0000\u0000kh\u0001\u0000\u0000"+
		"\u0000lo\u0001\u0000\u0000\u0000mk\u0001\u0000\u0000\u0000mn\u0001\u0000"+
		"\u0000\u0000n\u0003\u0001\u0000\u0000\u0000om\u0001\u0000\u0000\u0000"+
		"pw\u0003\u000e\u0007\u0000qw\u0003\f\u0006\u0000rs\u0005\u0001\u0000\u0000"+
		"st\u0003\u0002\u0001\u0000tu\u0005\u0002\u0000\u0000uw\u0001\u0000\u0000"+
		"\u0000vp\u0001\u0000\u0000\u0000vq\u0001\u0000\u0000\u0000vr\u0001\u0000"+
		"\u0000\u0000w\u0005\u0001\u0000\u0000\u0000xz\u0005\u0006\u0000\u0000"+
		"yx\u0001\u0000\u0000\u0000yz\u0001\u0000\u0000\u0000z{\u0001\u0000\u0000"+
		"\u0000{|\u0005\u0003\u0000\u0000|}\u0003\u0002\u0001\u0000}~\u0005\u0004"+
		"\u0000\u0000~\u0007\u0001\u0000\u0000\u0000\u007f\u0081\u0005\u0006\u0000"+
		"\u0000\u0080\u007f\u0001\u0000\u0000\u0000\u0080\u0081\u0001\u0000\u0000"+
		"\u0000\u0081\u0082\u0001\u0000\u0000\u0000\u0082\u0083\u0005\t\u0000\u0000"+
		"\u0083\u0084\u0005\u001d\u0000\u0000\u0084\t\u0001\u0000\u0000\u0000\u0085"+
		"\u0087\u0005\u0006\u0000\u0000\u0086\u0085\u0001\u0000\u0000\u0000\u0086"+
		"\u0087\u0001\u0000\u0000\u0000\u0087\u0088\u0001\u0000\u0000\u0000\u0088"+
		"\u008a\u0005\b\u0000\u0000\u0089\u008b\u0005\n\u0000\u0000\u008a\u0089"+
		"\u0001\u0000\u0000\u0000\u008a\u008b\u0001\u0000\u0000\u0000\u008b\u008c"+
		"\u0001\u0000\u0000\u0000\u008c\u008d\u0005\u0003\u0000\u0000\u008d\u008e"+
		"\u0003\u0002\u0001\u0000\u008e\u008f\u0005\u0004\u0000\u0000\u008f\u000b"+
		"\u0001\u0000\u0000\u0000\u0090\u0091\u0005\u001e\u0000\u0000\u0091\r\u0001"+
		"\u0000\u0000\u0000\u0092\u0097\u0005\u001f\u0000\u0000\u0093\u0097\u0005"+
		"\u001a\u0000\u0000\u0094\u0097\u0005\u001b\u0000\u0000\u0095\u0097\u0005"+
		"\u001c\u0000\u0000\u0096\u0092\u0001\u0000\u0000\u0000\u0096\u0093\u0001"+
		"\u0000\u0000\u0000\u0096\u0094\u0001\u0000\u0000\u0000\u0096\u0095\u0001"+
		"\u0000\u0000\u0000\u0097\u000f\u0001\u0000\u0000\u0000\u000e\u001d +-"+
		"6dkmvy\u0080\u0086\u008a\u0096";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
		_decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
		for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
			_decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
		}
	}
}