/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

// Generated from Expression.g4 by ANTLR 4.13.2

    package io.evitadb.api.query.expression.parser.grammar;

import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNDeserializer;
import org.antlr.v4.runtime.atn.ParserATNSimulator;
import org.antlr.v4.runtime.atn.PredictionContextCache;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.ParseTreeVisitor;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.List;

@SuppressWarnings({"all", "warnings", "unchecked", "unused", "cast", "CheckReturnValue", "this-escape"})
public class ExpressionParser extends Parser {
	static { RuntimeMetaData.checkVersion("4.13.2", RuntimeMetaData.VERSION); }

	protected static final DFA[] _decisionToDFA;
	protected static final PredictionContextCache _sharedContextCache =
		new PredictionContextCache();
	public static final int
		LPAREN=1, RPAREN=2, PLUS=3, MINUS=4, TIMES=5, DIV=6, MOD=7, GT=8, GT_EQ=9,
		LT=10, LT_EQ=11, EQ=12, NOT_EQ=13, NOT=14, AND=15, OR=16, COMMA=17, POINT=18,
		POW=19, VARIABLE=20, CEIL=21, SQRT=22, FLOOR=23, ABS=24, ROUND=25, LOG=26,
		MAX=27, MIN=28, RANDOM=29, WS=30, STRING=31, INT=32, FLOAT=33, BOOLEAN=34;
	public static final int
		RULE_expression = 0, RULE_combinationExpression = 1, RULE_multiplyingExpression = 2,
		RULE_powExpression = 3, RULE_signedAtom = 4, RULE_atom = 5, RULE_variable = 6,
		RULE_function = 7, RULE_valueToken = 8;
	private static String[] makeRuleNames() {
		return new String[] {
			"expression", "combinationExpression", "multiplyingExpression", "powExpression",
			"signedAtom", "atom", "variable", "function", "valueToken"
		};
	}
	public static final String[] ruleNames = makeRuleNames();

	private static String[] makeLiteralNames() {
		return new String[] {
			null, "'('", "')'", "'+'", "'-'", "'*'", "'/'", "'%'", "'>'", "'>='",
			"'<'", "'<='", "'=='", "'!='", "'!'", "'&&'", "'||'", "','", "'.'", "'^'",
			null, "'ceil'", "'sqrt'", "'floor'", "'abs'", "'round'", "'log'", "'max'",
			"'min'", "'random'"
		};
	}
	private static final String[] _LITERAL_NAMES = makeLiteralNames();
	private static String[] makeSymbolicNames() {
		return new String[] {
			null, "LPAREN", "RPAREN", "PLUS", "MINUS", "TIMES", "DIV", "MOD", "GT",
			"GT_EQ", "LT", "LT_EQ", "EQ", "NOT_EQ", "NOT", "AND", "OR", "COMMA",
			"POINT", "POW", "VARIABLE", "CEIL", "SQRT", "FLOOR", "ABS", "ROUND",
			"LOG", "MAX", "MIN", "RANDOM", "WS", "STRING", "INT", "FLOAT", "BOOLEAN"
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
	public String getGrammarFileName() { return "Expression.g4"; }

	@Override
	public String[] getRuleNames() { return ruleNames; }

	@Override
	public String getSerializedATN() { return _serializedATN; }

	@Override
	public ATN getATN() { return _ATN; }

	public ExpressionParser(TokenStream input) {
		super(input);
		_interp = new ParserATNSimulator(this,_ATN,_decisionToDFA,_sharedContextCache);
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
	public static class GreaterThanExpressionContext extends ExpressionContext {
		public CombinationExpressionContext leftExpression;
		public CombinationExpressionContext rightExpression;
		public TerminalNode GT() { return getToken(ExpressionParser.GT, 0); }
		public List<CombinationExpressionContext> combinationExpression() {
			return getRuleContexts(CombinationExpressionContext.class);
		}
		public CombinationExpressionContext combinationExpression(int i) {
			return getRuleContext(CombinationExpressionContext.class,i);
		}
		public GreaterThanExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterGreaterThanExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitGreaterThanExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitGreaterThanExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class FunctionExpressionContext extends ExpressionContext {
		public FunctionContext function() {
			return getRuleContext(FunctionContext.class,0);
		}
		public FunctionExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterFunctionExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitFunctionExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitFunctionExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class GreaterThanEqualsExpressionContext extends ExpressionContext {
		public CombinationExpressionContext leftExpression;
		public CombinationExpressionContext rightExpression;
		public TerminalNode GT_EQ() { return getToken(ExpressionParser.GT_EQ, 0); }
		public List<CombinationExpressionContext> combinationExpression() {
			return getRuleContexts(CombinationExpressionContext.class);
		}
		public CombinationExpressionContext combinationExpression(int i) {
			return getRuleContext(CombinationExpressionContext.class,i);
		}
		public GreaterThanEqualsExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterGreaterThanEqualsExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitGreaterThanEqualsExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitGreaterThanEqualsExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class LessThanEqualsExpressionContext extends ExpressionContext {
		public CombinationExpressionContext leftExpression;
		public CombinationExpressionContext rightExpression;
		public TerminalNode LT_EQ() { return getToken(ExpressionParser.LT_EQ, 0); }
		public List<CombinationExpressionContext> combinationExpression() {
			return getRuleContexts(CombinationExpressionContext.class);
		}
		public CombinationExpressionContext combinationExpression(int i) {
			return getRuleContext(CombinationExpressionContext.class,i);
		}
		public LessThanEqualsExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterLessThanEqualsExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitLessThanEqualsExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitLessThanEqualsExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class OrExpressionContext extends ExpressionContext {
		public ExpressionContext leftExpression;
		public ExpressionContext rightExpression;
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public List<TerminalNode> OR() { return getTokens(ExpressionParser.OR); }
		public TerminalNode OR(int i) {
			return getToken(ExpressionParser.OR, i);
		}
		public OrExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterOrExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitOrExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitOrExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class NotEqualsExpressionContext extends ExpressionContext {
		public ExpressionContext leftExpression;
		public ExpressionContext rightExpression;
		public TerminalNode NOT_EQ() { return getToken(ExpressionParser.NOT_EQ, 0); }
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public NotEqualsExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterNotEqualsExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitNotEqualsExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitNotEqualsExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class AndExpressionContext extends ExpressionContext {
		public ExpressionContext leftExpression;
		public ExpressionContext rightExpression;
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public List<TerminalNode> AND() { return getTokens(ExpressionParser.AND); }
		public TerminalNode AND(int i) {
			return getToken(ExpressionParser.AND, i);
		}
		public AndExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterAndExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitAndExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitAndExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class EqualsExpressionContext extends ExpressionContext {
		public ExpressionContext leftExpression;
		public ExpressionContext rightExpression;
		public TerminalNode EQ() { return getToken(ExpressionParser.EQ, 0); }
		public List<ExpressionContext> expression() {
			return getRuleContexts(ExpressionContext.class);
		}
		public ExpressionContext expression(int i) {
			return getRuleContext(ExpressionContext.class,i);
		}
		public EqualsExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterEqualsExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitEqualsExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitEqualsExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class StandaloneCombinationExpressionContext extends ExpressionContext {
		public CombinationExpressionContext combinationExpression() {
			return getRuleContext(CombinationExpressionContext.class,0);
		}
		public StandaloneCombinationExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterStandaloneCombinationExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitStandaloneCombinationExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitStandaloneCombinationExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class NestedExpressionContext extends ExpressionContext {
		public TerminalNode LPAREN() { return getToken(ExpressionParser.LPAREN, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public TerminalNode RPAREN() { return getToken(ExpressionParser.RPAREN, 0); }
		public NestedExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterNestedExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitNestedExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitNestedExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class LessThanExpressionContext extends ExpressionContext {
		public CombinationExpressionContext leftExpression;
		public CombinationExpressionContext rightExpression;
		public TerminalNode LT() { return getToken(ExpressionParser.LT, 0); }
		public List<CombinationExpressionContext> combinationExpression() {
			return getRuleContexts(CombinationExpressionContext.class);
		}
		public CombinationExpressionContext combinationExpression(int i) {
			return getRuleContext(CombinationExpressionContext.class,i);
		}
		public LessThanExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterLessThanExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitLessThanExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitLessThanExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class BooleanNegatingExpressionContext extends ExpressionContext {
		public TerminalNode NOT() { return getToken(ExpressionParser.NOT, 0); }
		public TerminalNode BOOLEAN() { return getToken(ExpressionParser.BOOLEAN, 0); }
		public BooleanNegatingExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterBooleanNegatingExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitBooleanNegatingExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitBooleanNegatingExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class StandaloneExpressionContext extends ExpressionContext {
		public AtomContext atom() {
			return getRuleContext(AtomContext.class,0);
		}
		public StandaloneExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterStandaloneExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitStandaloneExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitStandaloneExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class NegatingExpressionContext extends ExpressionContext {
		public TerminalNode NOT() { return getToken(ExpressionParser.NOT, 0); }
		public TerminalNode LPAREN() { return getToken(ExpressionParser.LPAREN, 0); }
		public ExpressionContext expression() {
			return getRuleContext(ExpressionContext.class,0);
		}
		public TerminalNode RPAREN() { return getToken(ExpressionParser.RPAREN, 0); }
		public NegatingExpressionContext(ExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterNegatingExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitNegatingExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitNegatingExpression(this);
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
		int _startState = 0;
		enterRecursionRule(_localctx, 0, RULE_expression, _p);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(50);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,0,_ctx) ) {
			case 1:
				{
				_localctx = new StandaloneExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;

				setState(19);
				atom();
				}
				break;
			case 2:
				{
				_localctx = new FunctionExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(20);
				function();
				}
				break;
			case 3:
				{
				_localctx = new StandaloneCombinationExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(21);
				combinationExpression();
				}
				break;
			case 4:
				{
				_localctx = new BooleanNegatingExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(22);
				match(NOT);
				setState(23);
				match(BOOLEAN);
				{
				}
				}
				break;
			case 5:
				{
				_localctx = new NestedExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(25);
				match(LPAREN);
				setState(26);
				expression(0);
				setState(27);
				match(RPAREN);
				}
				break;
			case 6:
				{
				_localctx = new NegatingExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(29);
				match(NOT);
				setState(30);
				match(LPAREN);
				setState(31);
				expression(0);
				setState(32);
				match(RPAREN);
				}
				break;
			case 7:
				{
				_localctx = new GreaterThanExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(34);
				((GreaterThanExpressionContext)_localctx).leftExpression = combinationExpression();
				setState(35);
				match(GT);
				setState(36);
				((GreaterThanExpressionContext)_localctx).rightExpression = combinationExpression();
				}
				break;
			case 8:
				{
				_localctx = new GreaterThanEqualsExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(38);
				((GreaterThanEqualsExpressionContext)_localctx).leftExpression = combinationExpression();
				setState(39);
				match(GT_EQ);
				setState(40);
				((GreaterThanEqualsExpressionContext)_localctx).rightExpression = combinationExpression();
				}
				break;
			case 9:
				{
				_localctx = new LessThanExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(42);
				((LessThanExpressionContext)_localctx).leftExpression = combinationExpression();
				setState(43);
				match(LT);
				setState(44);
				((LessThanExpressionContext)_localctx).rightExpression = combinationExpression();
				}
				break;
			case 10:
				{
				_localctx = new LessThanEqualsExpressionContext(_localctx);
				_ctx = _localctx;
				_prevctx = _localctx;
				setState(46);
				((LessThanEqualsExpressionContext)_localctx).leftExpression = combinationExpression();
				setState(47);
				match(LT_EQ);
				setState(48);
				((LessThanEqualsExpressionContext)_localctx).rightExpression = combinationExpression();
				}
				break;
			}
			_ctx.stop = _input.LT(-1);
			setState(74);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,4,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					if ( _parseListeners!=null ) triggerExitRuleEvent();
					_prevctx = _localctx;
					{
					setState(72);
					_errHandler.sync(this);
					switch ( getInterpreter().adaptivePredict(_input,3,_ctx) ) {
					case 1:
						{
						_localctx = new EqualsExpressionContext(new ExpressionContext(_parentctx, _parentState));
						((EqualsExpressionContext)_localctx).leftExpression = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(52);
						if (!(precpred(_ctx, 6))) throw new FailedPredicateException(this, "precpred(_ctx, 6)");
						setState(53);
						match(EQ);
						setState(54);
						((EqualsExpressionContext)_localctx).rightExpression = expression(7);
						}
						break;
					case 2:
						{
						_localctx = new NotEqualsExpressionContext(new ExpressionContext(_parentctx, _parentState));
						((NotEqualsExpressionContext)_localctx).leftExpression = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(55);
						if (!(precpred(_ctx, 5))) throw new FailedPredicateException(this, "precpred(_ctx, 5)");
						setState(56);
						match(NOT_EQ);
						setState(57);
						((NotEqualsExpressionContext)_localctx).rightExpression = expression(6);
						}
						break;
					case 3:
						{
						_localctx = new AndExpressionContext(new ExpressionContext(_parentctx, _parentState));
						((AndExpressionContext)_localctx).leftExpression = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(58);
						if (!(precpred(_ctx, 8))) throw new FailedPredicateException(this, "precpred(_ctx, 8)");
						setState(61);
						_errHandler.sync(this);
						_alt = 1;
						do {
							switch (_alt) {
							case 1:
								{
								{
								setState(59);
								match(AND);
								setState(60);
								((AndExpressionContext)_localctx).rightExpression = expression(0);
								}
								}
								break;
							default:
								throw new NoViableAltException(this);
							}
							setState(63);
							_errHandler.sync(this);
							_alt = getInterpreter().adaptivePredict(_input,1,_ctx);
						} while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER );
						}
						break;
					case 4:
						{
						_localctx = new OrExpressionContext(new ExpressionContext(_parentctx, _parentState));
						((OrExpressionContext)_localctx).leftExpression = _prevctx;
						pushNewRecursionContext(_localctx, _startState, RULE_expression);
						setState(65);
						if (!(precpred(_ctx, 7))) throw new FailedPredicateException(this, "precpred(_ctx, 7)");
						setState(68);
						_errHandler.sync(this);
						_alt = 1;
						do {
							switch (_alt) {
							case 1:
								{
								{
								setState(66);
								match(OR);
								setState(67);
								((OrExpressionContext)_localctx).rightExpression = expression(0);
								}
								}
								break;
							default:
								throw new NoViableAltException(this);
							}
							setState(70);
							_errHandler.sync(this);
							_alt = getInterpreter().adaptivePredict(_input,2,_ctx);
						} while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER );
						}
						break;
					}
					}
				}
				setState(76);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,4,_ctx);
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
	public static class CombinationExpressionContext extends ParserRuleContext {
		public CombinationExpressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_combinationExpression; }

		public CombinationExpressionContext() { }
		public void copyFrom(CombinationExpressionContext ctx) {
			super.copyFrom(ctx);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class MinusExpressionContext extends CombinationExpressionContext {
		public List<MultiplyingExpressionContext> multiplyingExpression() {
			return getRuleContexts(MultiplyingExpressionContext.class);
		}
		public MultiplyingExpressionContext multiplyingExpression(int i) {
			return getRuleContext(MultiplyingExpressionContext.class,i);
		}
		public List<TerminalNode> MINUS() { return getTokens(ExpressionParser.MINUS); }
		public TerminalNode MINUS(int i) {
			return getToken(ExpressionParser.MINUS, i);
		}
		public MinusExpressionContext(CombinationExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterMinusExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitMinusExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitMinusExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class PlusExpressionContext extends CombinationExpressionContext {
		public List<MultiplyingExpressionContext> multiplyingExpression() {
			return getRuleContexts(MultiplyingExpressionContext.class);
		}
		public MultiplyingExpressionContext multiplyingExpression(int i) {
			return getRuleContext(MultiplyingExpressionContext.class,i);
		}
		public List<TerminalNode> PLUS() { return getTokens(ExpressionParser.PLUS); }
		public TerminalNode PLUS(int i) {
			return getToken(ExpressionParser.PLUS, i);
		}
		public PlusExpressionContext(CombinationExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterPlusExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitPlusExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitPlusExpression(this);
			else return visitor.visitChildren(this);
		}
	}

	public final CombinationExpressionContext combinationExpression() throws RecognitionException {
		CombinationExpressionContext _localctx = new CombinationExpressionContext(_ctx, getState());
		enterRule(_localctx, 2, RULE_combinationExpression);
		try {
			int _alt;
			setState(93);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,7,_ctx) ) {
			case 1:
				_localctx = new PlusExpressionContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(77);
				multiplyingExpression();
				setState(82);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,5,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(78);
						match(PLUS);
						setState(79);
						multiplyingExpression();
						}
						}
					}
					setState(84);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,5,_ctx);
				}
				}
				break;
			case 2:
				_localctx = new MinusExpressionContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(85);
				multiplyingExpression();
				setState(90);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,6,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(86);
						match(MINUS);
						setState(87);
						multiplyingExpression();
						}
						}
					}
					setState(92);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,6,_ctx);
				}
				}
				break;
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
	public static class MultiplyingExpressionContext extends ParserRuleContext {
		public MultiplyingExpressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_multiplyingExpression; }

		public MultiplyingExpressionContext() { }
		public void copyFrom(MultiplyingExpressionContext ctx) {
			super.copyFrom(ctx);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class TimesExpressionContext extends MultiplyingExpressionContext {
		public List<PowExpressionContext> powExpression() {
			return getRuleContexts(PowExpressionContext.class);
		}
		public PowExpressionContext powExpression(int i) {
			return getRuleContext(PowExpressionContext.class,i);
		}
		public List<TerminalNode> TIMES() { return getTokens(ExpressionParser.TIMES); }
		public TerminalNode TIMES(int i) {
			return getToken(ExpressionParser.TIMES, i);
		}
		public TimesExpressionContext(MultiplyingExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterTimesExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitTimesExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitTimesExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class ModExpressionContext extends MultiplyingExpressionContext {
		public List<PowExpressionContext> powExpression() {
			return getRuleContexts(PowExpressionContext.class);
		}
		public PowExpressionContext powExpression(int i) {
			return getRuleContext(PowExpressionContext.class,i);
		}
		public List<TerminalNode> MOD() { return getTokens(ExpressionParser.MOD); }
		public TerminalNode MOD(int i) {
			return getToken(ExpressionParser.MOD, i);
		}
		public ModExpressionContext(MultiplyingExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterModExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitModExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitModExpression(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class DivExpressionContext extends MultiplyingExpressionContext {
		public List<PowExpressionContext> powExpression() {
			return getRuleContexts(PowExpressionContext.class);
		}
		public PowExpressionContext powExpression(int i) {
			return getRuleContext(PowExpressionContext.class,i);
		}
		public List<TerminalNode> DIV() { return getTokens(ExpressionParser.DIV); }
		public TerminalNode DIV(int i) {
			return getToken(ExpressionParser.DIV, i);
		}
		public DivExpressionContext(MultiplyingExpressionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterDivExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitDivExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitDivExpression(this);
			else return visitor.visitChildren(this);
		}
	}

	public final MultiplyingExpressionContext multiplyingExpression() throws RecognitionException {
		MultiplyingExpressionContext _localctx = new MultiplyingExpressionContext(_ctx, getState());
		enterRule(_localctx, 4, RULE_multiplyingExpression);
		try {
			int _alt;
			setState(119);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,11,_ctx) ) {
			case 1:
				_localctx = new TimesExpressionContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(95);
				powExpression();
				setState(100);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,8,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(96);
						match(TIMES);
						setState(97);
						powExpression();
						}
						}
					}
					setState(102);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,8,_ctx);
				}
				}
				break;
			case 2:
				_localctx = new DivExpressionContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(103);
				powExpression();
				setState(108);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,9,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(104);
						match(DIV);
						setState(105);
						powExpression();
						}
						}
					}
					setState(110);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,9,_ctx);
				}
				}
				break;
			case 3:
				_localctx = new ModExpressionContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(111);
				powExpression();
				setState(116);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,10,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(112);
						match(MOD);
						setState(113);
						powExpression();
						}
						}
					}
					setState(118);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,10,_ctx);
				}
				}
				break;
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
	public static class PowExpressionContext extends ParserRuleContext {
		public List<SignedAtomContext> signedAtom() {
			return getRuleContexts(SignedAtomContext.class);
		}
		public SignedAtomContext signedAtom(int i) {
			return getRuleContext(SignedAtomContext.class,i);
		}
		public List<TerminalNode> POW() { return getTokens(ExpressionParser.POW); }
		public TerminalNode POW(int i) {
			return getToken(ExpressionParser.POW, i);
		}
		public PowExpressionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_powExpression; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterPowExpression(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitPowExpression(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitPowExpression(this);
			else return visitor.visitChildren(this);
		}
	}

	public final PowExpressionContext powExpression() throws RecognitionException {
		PowExpressionContext _localctx = new PowExpressionContext(_ctx, getState());
		enterRule(_localctx, 6, RULE_powExpression);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(121);
			signedAtom();
			setState(126);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,12,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(122);
					match(POW);
					setState(123);
					signedAtom();
					}
					}
				}
				setState(128);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,12,_ctx);
			}
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
	public static class SignedAtomContext extends ParserRuleContext {
		public SignedAtomContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_signedAtom; }

		public SignedAtomContext() { }
		public void copyFrom(SignedAtomContext ctx) {
			super.copyFrom(ctx);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class NegativeSignedAtomContext extends SignedAtomContext {
		public TerminalNode MINUS() { return getToken(ExpressionParser.MINUS, 0); }
		public SignedAtomContext signedAtom() {
			return getRuleContext(SignedAtomContext.class,0);
		}
		public NegativeSignedAtomContext(SignedAtomContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterNegativeSignedAtom(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitNegativeSignedAtom(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitNegativeSignedAtom(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class FunctionSignedAtomContext extends SignedAtomContext {
		public FunctionContext function() {
			return getRuleContext(FunctionContext.class,0);
		}
		public FunctionSignedAtomContext(SignedAtomContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterFunctionSignedAtom(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitFunctionSignedAtom(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitFunctionSignedAtom(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class BaseSignedAtomContext extends SignedAtomContext {
		public AtomContext atom() {
			return getRuleContext(AtomContext.class,0);
		}
		public BaseSignedAtomContext(SignedAtomContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterBaseSignedAtom(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitBaseSignedAtom(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitBaseSignedAtom(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class PositiveSignedAtomContext extends SignedAtomContext {
		public TerminalNode PLUS() { return getToken(ExpressionParser.PLUS, 0); }
		public SignedAtomContext signedAtom() {
			return getRuleContext(SignedAtomContext.class,0);
		}
		public PositiveSignedAtomContext(SignedAtomContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterPositiveSignedAtom(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitPositiveSignedAtom(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitPositiveSignedAtom(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SignedAtomContext signedAtom() throws RecognitionException {
		SignedAtomContext _localctx = new SignedAtomContext(_ctx, getState());
		enterRule(_localctx, 8, RULE_signedAtom);
		try {
			setState(135);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case PLUS:
				_localctx = new PositiveSignedAtomContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(129);
				match(PLUS);
				setState(130);
				signedAtom();
				}
				break;
			case MINUS:
				_localctx = new NegativeSignedAtomContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(131);
				match(MINUS);
				setState(132);
				signedAtom();
				}
				break;
			case CEIL:
			case SQRT:
			case FLOOR:
			case ABS:
			case ROUND:
			case LOG:
			case MAX:
			case MIN:
			case RANDOM:
				_localctx = new FunctionSignedAtomContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(133);
				function();
				}
				break;
			case LPAREN:
			case VARIABLE:
			case STRING:
			case INT:
			case FLOAT:
			case BOOLEAN:
				_localctx = new BaseSignedAtomContext(_localctx);
				enterOuterAlt(_localctx, 4);
				{
				setState(134);
				atom();
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
	public static class AtomContext extends ParserRuleContext {
		public AtomContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_atom; }

		public AtomContext() { }
		public void copyFrom(AtomContext ctx) {
			super.copyFrom(ctx);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class ValueAtomContext extends AtomContext {
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
		}
		public ValueAtomContext(AtomContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterValueAtom(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitValueAtom(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitValueAtom(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class VariableAtomContext extends AtomContext {
		public VariableContext variable() {
			return getRuleContext(VariableContext.class,0);
		}
		public VariableAtomContext(AtomContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterVariableAtom(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitVariableAtom(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitVariableAtom(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class ExpressionAtomContext extends AtomContext {
		public TerminalNode LPAREN() { return getToken(ExpressionParser.LPAREN, 0); }
		public CombinationExpressionContext combinationExpression() {
			return getRuleContext(CombinationExpressionContext.class,0);
		}
		public TerminalNode RPAREN() { return getToken(ExpressionParser.RPAREN, 0); }
		public ExpressionAtomContext(AtomContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterExpressionAtom(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitExpressionAtom(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitExpressionAtom(this);
			else return visitor.visitChildren(this);
		}
	}

	public final AtomContext atom() throws RecognitionException {
		AtomContext _localctx = new AtomContext(_ctx, getState());
		enterRule(_localctx, 10, RULE_atom);
		try {
			setState(143);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case STRING:
			case INT:
			case FLOAT:
			case BOOLEAN:
				_localctx = new ValueAtomContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(137);
				valueToken();
				}
				break;
			case VARIABLE:
				_localctx = new VariableAtomContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(138);
				variable();
				}
				break;
			case LPAREN:
				_localctx = new ExpressionAtomContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(139);
				match(LPAREN);
				setState(140);
				combinationExpression();
				setState(141);
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
	public static class VariableContext extends ParserRuleContext {
		public TerminalNode VARIABLE() { return getToken(ExpressionParser.VARIABLE, 0); }
		public VariableContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_variable; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterVariable(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitVariable(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitVariable(this);
			else return visitor.visitChildren(this);
		}
	}

	public final VariableContext variable() throws RecognitionException {
		VariableContext _localctx = new VariableContext(_ctx, getState());
		enterRule(_localctx, 12, RULE_variable);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(145);
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
	public static class FunctionContext extends ParserRuleContext {
		public FunctionContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_function; }

		public FunctionContext() { }
		public void copyFrom(FunctionContext ctx) {
			super.copyFrom(ctx);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class AbsFunctionContext extends FunctionContext {
		public TerminalNode ABS() { return getToken(ExpressionParser.ABS, 0); }
		public TerminalNode LPAREN() { return getToken(ExpressionParser.LPAREN, 0); }
		public CombinationExpressionContext combinationExpression() {
			return getRuleContext(CombinationExpressionContext.class,0);
		}
		public TerminalNode RPAREN() { return getToken(ExpressionParser.RPAREN, 0); }
		public AbsFunctionContext(FunctionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterAbsFunction(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitAbsFunction(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitAbsFunction(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class SqrtFunctionContext extends FunctionContext {
		public TerminalNode SQRT() { return getToken(ExpressionParser.SQRT, 0); }
		public TerminalNode LPAREN() { return getToken(ExpressionParser.LPAREN, 0); }
		public CombinationExpressionContext combinationExpression() {
			return getRuleContext(CombinationExpressionContext.class,0);
		}
		public TerminalNode RPAREN() { return getToken(ExpressionParser.RPAREN, 0); }
		public SqrtFunctionContext(FunctionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterSqrtFunction(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitSqrtFunction(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitSqrtFunction(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class CeilFunctionContext extends FunctionContext {
		public TerminalNode CEIL() { return getToken(ExpressionParser.CEIL, 0); }
		public TerminalNode LPAREN() { return getToken(ExpressionParser.LPAREN, 0); }
		public CombinationExpressionContext combinationExpression() {
			return getRuleContext(CombinationExpressionContext.class,0);
		}
		public TerminalNode RPAREN() { return getToken(ExpressionParser.RPAREN, 0); }
		public CeilFunctionContext(FunctionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterCeilFunction(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitCeilFunction(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitCeilFunction(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class LogFunctionContext extends FunctionContext {
		public TerminalNode LOG() { return getToken(ExpressionParser.LOG, 0); }
		public TerminalNode LPAREN() { return getToken(ExpressionParser.LPAREN, 0); }
		public CombinationExpressionContext combinationExpression() {
			return getRuleContext(CombinationExpressionContext.class,0);
		}
		public TerminalNode RPAREN() { return getToken(ExpressionParser.RPAREN, 0); }
		public LogFunctionContext(FunctionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterLogFunction(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitLogFunction(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitLogFunction(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class MinFunctionContext extends FunctionContext {
		public CombinationExpressionContext leftOperand;
		public CombinationExpressionContext rightOperand;
		public TerminalNode MIN() { return getToken(ExpressionParser.MIN, 0); }
		public TerminalNode LPAREN() { return getToken(ExpressionParser.LPAREN, 0); }
		public TerminalNode COMMA() { return getToken(ExpressionParser.COMMA, 0); }
		public TerminalNode RPAREN() { return getToken(ExpressionParser.RPAREN, 0); }
		public List<CombinationExpressionContext> combinationExpression() {
			return getRuleContexts(CombinationExpressionContext.class);
		}
		public CombinationExpressionContext combinationExpression(int i) {
			return getRuleContext(CombinationExpressionContext.class,i);
		}
		public MinFunctionContext(FunctionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterMinFunction(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitMinFunction(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitMinFunction(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class MaxFunctionContext extends FunctionContext {
		public CombinationExpressionContext leftOperand;
		public CombinationExpressionContext rightOperand;
		public TerminalNode MAX() { return getToken(ExpressionParser.MAX, 0); }
		public TerminalNode LPAREN() { return getToken(ExpressionParser.LPAREN, 0); }
		public TerminalNode COMMA() { return getToken(ExpressionParser.COMMA, 0); }
		public TerminalNode RPAREN() { return getToken(ExpressionParser.RPAREN, 0); }
		public List<CombinationExpressionContext> combinationExpression() {
			return getRuleContexts(CombinationExpressionContext.class);
		}
		public CombinationExpressionContext combinationExpression(int i) {
			return getRuleContext(CombinationExpressionContext.class,i);
		}
		public MaxFunctionContext(FunctionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterMaxFunction(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitMaxFunction(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitMaxFunction(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class RandomIntFunctionContext extends FunctionContext {
		public TerminalNode RANDOM() { return getToken(ExpressionParser.RANDOM, 0); }
		public TerminalNode LPAREN() { return getToken(ExpressionParser.LPAREN, 0); }
		public TerminalNode RPAREN() { return getToken(ExpressionParser.RPAREN, 0); }
		public CombinationExpressionContext combinationExpression() {
			return getRuleContext(CombinationExpressionContext.class,0);
		}
		public RandomIntFunctionContext(FunctionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterRandomIntFunction(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitRandomIntFunction(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitRandomIntFunction(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class FloorFunctionContext extends FunctionContext {
		public TerminalNode FLOOR() { return getToken(ExpressionParser.FLOOR, 0); }
		public TerminalNode LPAREN() { return getToken(ExpressionParser.LPAREN, 0); }
		public CombinationExpressionContext combinationExpression() {
			return getRuleContext(CombinationExpressionContext.class,0);
		}
		public TerminalNode RPAREN() { return getToken(ExpressionParser.RPAREN, 0); }
		public FloorFunctionContext(FunctionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterFloorFunction(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitFloorFunction(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitFloorFunction(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class RoundFunctionContext extends FunctionContext {
		public TerminalNode ROUND() { return getToken(ExpressionParser.ROUND, 0); }
		public TerminalNode LPAREN() { return getToken(ExpressionParser.LPAREN, 0); }
		public CombinationExpressionContext combinationExpression() {
			return getRuleContext(CombinationExpressionContext.class,0);
		}
		public TerminalNode RPAREN() { return getToken(ExpressionParser.RPAREN, 0); }
		public RoundFunctionContext(FunctionContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterRoundFunction(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitRoundFunction(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitRoundFunction(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FunctionContext function() throws RecognitionException {
		FunctionContext _localctx = new FunctionContext(_ctx, getState());
		enterRule(_localctx, 14, RULE_function);
		int _la;
		try {
			setState(197);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case SQRT:
				_localctx = new SqrtFunctionContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(147);
				match(SQRT);
				setState(148);
				match(LPAREN);
				setState(149);
				combinationExpression();
				setState(150);
				match(RPAREN);
				}
				break;
			case CEIL:
				_localctx = new CeilFunctionContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(152);
				match(CEIL);
				setState(153);
				match(LPAREN);
				setState(154);
				combinationExpression();
				setState(155);
				match(RPAREN);
				}
				break;
			case FLOOR:
				_localctx = new FloorFunctionContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(157);
				match(FLOOR);
				setState(158);
				match(LPAREN);
				setState(159);
				combinationExpression();
				setState(160);
				match(RPAREN);
				}
				break;
			case ABS:
				_localctx = new AbsFunctionContext(_localctx);
				enterOuterAlt(_localctx, 4);
				{
				setState(162);
				match(ABS);
				setState(163);
				match(LPAREN);
				setState(164);
				combinationExpression();
				setState(165);
				match(RPAREN);
				}
				break;
			case ROUND:
				_localctx = new RoundFunctionContext(_localctx);
				enterOuterAlt(_localctx, 5);
				{
				setState(167);
				match(ROUND);
				setState(168);
				match(LPAREN);
				setState(169);
				combinationExpression();
				setState(170);
				match(RPAREN);
				}
				break;
			case LOG:
				_localctx = new LogFunctionContext(_localctx);
				enterOuterAlt(_localctx, 6);
				{
				setState(172);
				match(LOG);
				setState(173);
				match(LPAREN);
				setState(174);
				combinationExpression();
				setState(175);
				match(RPAREN);
				}
				break;
			case MIN:
				_localctx = new MinFunctionContext(_localctx);
				enterOuterAlt(_localctx, 7);
				{
				setState(177);
				match(MIN);
				setState(178);
				match(LPAREN);
				setState(179);
				((MinFunctionContext)_localctx).leftOperand = combinationExpression();
				setState(180);
				match(COMMA);
				setState(181);
				((MinFunctionContext)_localctx).rightOperand = combinationExpression();
				setState(182);
				match(RPAREN);
				}
				break;
			case MAX:
				_localctx = new MaxFunctionContext(_localctx);
				enterOuterAlt(_localctx, 8);
				{
				setState(184);
				match(MAX);
				setState(185);
				match(LPAREN);
				setState(186);
				((MaxFunctionContext)_localctx).leftOperand = combinationExpression();
				setState(187);
				match(COMMA);
				setState(188);
				((MaxFunctionContext)_localctx).rightOperand = combinationExpression();
				setState(189);
				match(RPAREN);
				}
				break;
			case RANDOM:
				_localctx = new RandomIntFunctionContext(_localctx);
				enterOuterAlt(_localctx, 9);
				{
				setState(191);
				match(RANDOM);
				setState(192);
				match(LPAREN);
				setState(194);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if ((((_la) & ~0x3f) == 0 && ((1L << _la) & 33284947994L) != 0)) {
					{
					setState(193);
					combinationExpression();
					}
				}

				setState(196);
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
	public static class ValueTokenContext extends ParserRuleContext {
		public ValueTokenContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_valueToken; }

		public ValueTokenContext() { }
		public void copyFrom(ValueTokenContext ctx) {
			super.copyFrom(ctx);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class IntValueTokenContext extends ValueTokenContext {
		public TerminalNode INT() { return getToken(ExpressionParser.INT, 0); }
		public IntValueTokenContext(ValueTokenContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterIntValueToken(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitIntValueToken(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitIntValueToken(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class StringValueTokenContext extends ValueTokenContext {
		public TerminalNode STRING() { return getToken(ExpressionParser.STRING, 0); }
		public StringValueTokenContext(ValueTokenContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterStringValueToken(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitStringValueToken(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitStringValueToken(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class FloatValueTokenContext extends ValueTokenContext {
		public TerminalNode FLOAT() { return getToken(ExpressionParser.FLOAT, 0); }
		public FloatValueTokenContext(ValueTokenContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterFloatValueToken(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitFloatValueToken(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitFloatValueToken(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class BooleanValueTokenContext extends ValueTokenContext {
		public TerminalNode BOOLEAN() { return getToken(ExpressionParser.BOOLEAN, 0); }
		public BooleanValueTokenContext(ValueTokenContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).enterBooleanValueToken(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof ExpressionListener ) ((ExpressionListener)listener).exitBooleanValueToken(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof ExpressionVisitor ) return ((ExpressionVisitor<? extends T>)visitor).visitBooleanValueToken(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ValueTokenContext valueToken() throws RecognitionException {
		ValueTokenContext _localctx = new ValueTokenContext(_ctx, getState());
		enterRule(_localctx, 16, RULE_valueToken);
		try {
			setState(203);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case STRING:
				_localctx = new StringValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(199);
				match(STRING);
				}
				break;
			case INT:
				_localctx = new IntValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(200);
				match(INT);
				}
				break;
			case FLOAT:
				_localctx = new FloatValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(201);
				match(FLOAT);
				}
				break;
			case BOOLEAN:
				_localctx = new BooleanValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 4);
				{
				setState(202);
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
		case 0:
			return expression_sempred((ExpressionContext)_localctx, predIndex);
		}
		return true;
	}
	private boolean expression_sempred(ExpressionContext _localctx, int predIndex) {
		switch (predIndex) {
		case 0:
			return precpred(_ctx, 6);
		case 1:
			return precpred(_ctx, 5);
		case 2:
			return precpred(_ctx, 8);
		case 3:
			return precpred(_ctx, 7);
		}
		return true;
	}

	public static final String _serializedATN =
		"\u0004\u0001\"\u00ce\u0002\u0000\u0007\u0000\u0002\u0001\u0007\u0001\u0002"+
		"\u0002\u0007\u0002\u0002\u0003\u0007\u0003\u0002\u0004\u0007\u0004\u0002"+
		"\u0005\u0007\u0005\u0002\u0006\u0007\u0006\u0002\u0007\u0007\u0007\u0002"+
		"\b\u0007\b\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000"+
		"\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000"+
		"\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000"+
		"\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000"+
		"\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000"+
		"\u0001\u0000\u0001\u0000\u0001\u0000\u0003\u00003\b\u0000\u0001\u0000"+
		"\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0000"+
		"\u0001\u0000\u0001\u0000\u0004\u0000>\b\u0000\u000b\u0000\f\u0000?\u0001"+
		"\u0000\u0001\u0000\u0001\u0000\u0004\u0000E\b\u0000\u000b\u0000\f\u0000"+
		"F\u0005\u0000I\b\u0000\n\u0000\f\u0000L\t\u0000\u0001\u0001\u0001\u0001"+
		"\u0001\u0001\u0005\u0001Q\b\u0001\n\u0001\f\u0001T\t\u0001\u0001\u0001"+
		"\u0001\u0001\u0001\u0001\u0005\u0001Y\b\u0001\n\u0001\f\u0001\\\t\u0001"+
		"\u0003\u0001^\b\u0001\u0001\u0002\u0001\u0002\u0001\u0002\u0005\u0002"+
		"c\b\u0002\n\u0002\f\u0002f\t\u0002\u0001\u0002\u0001\u0002\u0001\u0002"+
		"\u0005\u0002k\b\u0002\n\u0002\f\u0002n\t\u0002\u0001\u0002\u0001\u0002"+
		"\u0001\u0002\u0005\u0002s\b\u0002\n\u0002\f\u0002v\t\u0002\u0003\u0002"+
		"x\b\u0002\u0001\u0003\u0001\u0003\u0001\u0003\u0005\u0003}\b\u0003\n\u0003"+
		"\f\u0003\u0080\t\u0003\u0001\u0004\u0001\u0004\u0001\u0004\u0001\u0004"+
		"\u0001\u0004\u0001\u0004\u0003\u0004\u0088\b\u0004\u0001\u0005\u0001\u0005"+
		"\u0001\u0005\u0001\u0005\u0001\u0005\u0001\u0005\u0003\u0005\u0090\b\u0005"+
		"\u0001\u0006\u0001\u0006\u0001\u0007\u0001\u0007\u0001\u0007\u0001\u0007"+
		"\u0001\u0007\u0001\u0007\u0001\u0007\u0001\u0007\u0001\u0007\u0001\u0007"+
		"\u0001\u0007\u0001\u0007\u0001\u0007\u0001\u0007\u0001\u0007\u0001\u0007"+
		"\u0001\u0007\u0001\u0007\u0001\u0007\u0001\u0007\u0001\u0007\u0001\u0007"+
		"\u0001\u0007\u0001\u0007\u0001\u0007\u0001\u0007\u0001\u0007\u0001\u0007"+
		"\u0001\u0007\u0001\u0007\u0001\u0007\u0001\u0007\u0001\u0007\u0001\u0007"+
		"\u0001\u0007\u0001\u0007\u0001\u0007\u0001\u0007\u0001\u0007\u0001\u0007"+
		"\u0001\u0007\u0001\u0007\u0001\u0007\u0001\u0007\u0001\u0007\u0001\u0007"+
		"\u0001\u0007\u0003\u0007\u00c3\b\u0007\u0001\u0007\u0003\u0007\u00c6\b"+
		"\u0007\u0001\b\u0001\b\u0001\b\u0001\b\u0003\b\u00cc\b\b\u0001\b\u0000"+
		"\u0001\u0000\t\u0000\u0002\u0004\u0006\b\n\f\u000e\u0010\u0000\u0000\u00ed"+
		"\u00002\u0001\u0000\u0000\u0000\u0002]\u0001\u0000\u0000\u0000\u0004w"+
		"\u0001\u0000\u0000\u0000\u0006y\u0001\u0000\u0000\u0000\b\u0087\u0001"+
		"\u0000\u0000\u0000\n\u008f\u0001\u0000\u0000\u0000\f\u0091\u0001\u0000"+
		"\u0000\u0000\u000e\u00c5\u0001\u0000\u0000\u0000\u0010\u00cb\u0001\u0000"+
		"\u0000\u0000\u0012\u0013\u0006\u0000\uffff\uffff\u0000\u00133\u0003\n"+
		"\u0005\u0000\u00143\u0003\u000e\u0007\u0000\u00153\u0003\u0002\u0001\u0000"+
		"\u0016\u0017\u0005\u000e\u0000\u0000\u0017\u0018\u0005\"\u0000\u0000\u0018"+
		"3\u0001\u0000\u0000\u0000\u0019\u001a\u0005\u0001\u0000\u0000\u001a\u001b"+
		"\u0003\u0000\u0000\u0000\u001b\u001c\u0005\u0002\u0000\u0000\u001c3\u0001"+
		"\u0000\u0000\u0000\u001d\u001e\u0005\u000e\u0000\u0000\u001e\u001f\u0005"+
		"\u0001\u0000\u0000\u001f \u0003\u0000\u0000\u0000 !\u0005\u0002\u0000"+
		"\u0000!3\u0001\u0000\u0000\u0000\"#\u0003\u0002\u0001\u0000#$\u0005\b"+
		"\u0000\u0000$%\u0003\u0002\u0001\u0000%3\u0001\u0000\u0000\u0000&\'\u0003"+
		"\u0002\u0001\u0000\'(\u0005\t\u0000\u0000()\u0003\u0002\u0001\u0000)3"+
		"\u0001\u0000\u0000\u0000*+\u0003\u0002\u0001\u0000+,\u0005\n\u0000\u0000"+
		",-\u0003\u0002\u0001\u0000-3\u0001\u0000\u0000\u0000./\u0003\u0002\u0001"+
		"\u0000/0\u0005\u000b\u0000\u000001\u0003\u0002\u0001\u000013\u0001\u0000"+
		"\u0000\u00002\u0012\u0001\u0000\u0000\u00002\u0014\u0001\u0000\u0000\u0000"+
		"2\u0015\u0001\u0000\u0000\u00002\u0016\u0001\u0000\u0000\u00002\u0019"+
		"\u0001\u0000\u0000\u00002\u001d\u0001\u0000\u0000\u00002\"\u0001\u0000"+
		"\u0000\u00002&\u0001\u0000\u0000\u00002*\u0001\u0000\u0000\u00002.\u0001"+
		"\u0000\u0000\u00003J\u0001\u0000\u0000\u000045\n\u0006\u0000\u000056\u0005"+
		"\f\u0000\u00006I\u0003\u0000\u0000\u000778\n\u0005\u0000\u000089\u0005"+
		"\r\u0000\u00009I\u0003\u0000\u0000\u0006:=\n\b\u0000\u0000;<\u0005\u000f"+
		"\u0000\u0000<>\u0003\u0000\u0000\u0000=;\u0001\u0000\u0000\u0000>?\u0001"+
		"\u0000\u0000\u0000?=\u0001\u0000\u0000\u0000?@\u0001\u0000\u0000\u0000"+
		"@I\u0001\u0000\u0000\u0000AD\n\u0007\u0000\u0000BC\u0005\u0010\u0000\u0000"+
		"CE\u0003\u0000\u0000\u0000DB\u0001\u0000\u0000\u0000EF\u0001\u0000\u0000"+
		"\u0000FD\u0001\u0000\u0000\u0000FG\u0001\u0000\u0000\u0000GI\u0001\u0000"+
		"\u0000\u0000H4\u0001\u0000\u0000\u0000H7\u0001\u0000\u0000\u0000H:\u0001"+
		"\u0000\u0000\u0000HA\u0001\u0000\u0000\u0000IL\u0001\u0000\u0000\u0000"+
		"JH\u0001\u0000\u0000\u0000JK\u0001\u0000\u0000\u0000K\u0001\u0001\u0000"+
		"\u0000\u0000LJ\u0001\u0000\u0000\u0000MR\u0003\u0004\u0002\u0000NO\u0005"+
		"\u0003\u0000\u0000OQ\u0003\u0004\u0002\u0000PN\u0001\u0000\u0000\u0000"+
		"QT\u0001\u0000\u0000\u0000RP\u0001\u0000\u0000\u0000RS\u0001\u0000\u0000"+
		"\u0000S^\u0001\u0000\u0000\u0000TR\u0001\u0000\u0000\u0000UZ\u0003\u0004"+
		"\u0002\u0000VW\u0005\u0004\u0000\u0000WY\u0003\u0004\u0002\u0000XV\u0001"+
		"\u0000\u0000\u0000Y\\\u0001\u0000\u0000\u0000ZX\u0001\u0000\u0000\u0000"+
		"Z[\u0001\u0000\u0000\u0000[^\u0001\u0000\u0000\u0000\\Z\u0001\u0000\u0000"+
		"\u0000]M\u0001\u0000\u0000\u0000]U\u0001\u0000\u0000\u0000^\u0003\u0001"+
		"\u0000\u0000\u0000_d\u0003\u0006\u0003\u0000`a\u0005\u0005\u0000\u0000"+
		"ac\u0003\u0006\u0003\u0000b`\u0001\u0000\u0000\u0000cf\u0001\u0000\u0000"+
		"\u0000db\u0001\u0000\u0000\u0000de\u0001\u0000\u0000\u0000ex\u0001\u0000"+
		"\u0000\u0000fd\u0001\u0000\u0000\u0000gl\u0003\u0006\u0003\u0000hi\u0005"+
		"\u0006\u0000\u0000ik\u0003\u0006\u0003\u0000jh\u0001\u0000\u0000\u0000"+
		"kn\u0001\u0000\u0000\u0000lj\u0001\u0000\u0000\u0000lm\u0001\u0000\u0000"+
		"\u0000mx\u0001\u0000\u0000\u0000nl\u0001\u0000\u0000\u0000ot\u0003\u0006"+
		"\u0003\u0000pq\u0005\u0007\u0000\u0000qs\u0003\u0006\u0003\u0000rp\u0001"+
		"\u0000\u0000\u0000sv\u0001\u0000\u0000\u0000tr\u0001\u0000\u0000\u0000"+
		"tu\u0001\u0000\u0000\u0000ux\u0001\u0000\u0000\u0000vt\u0001\u0000\u0000"+
		"\u0000w_\u0001\u0000\u0000\u0000wg\u0001\u0000\u0000\u0000wo\u0001\u0000"+
		"\u0000\u0000x\u0005\u0001\u0000\u0000\u0000y~\u0003\b\u0004\u0000z{\u0005"+
		"\u0013\u0000\u0000{}\u0003\b\u0004\u0000|z\u0001\u0000\u0000\u0000}\u0080"+
		"\u0001\u0000\u0000\u0000~|\u0001\u0000\u0000\u0000~\u007f\u0001\u0000"+
		"\u0000\u0000\u007f\u0007\u0001\u0000\u0000\u0000\u0080~\u0001\u0000\u0000"+
		"\u0000\u0081\u0082\u0005\u0003\u0000\u0000\u0082\u0088\u0003\b\u0004\u0000"+
		"\u0083\u0084\u0005\u0004\u0000\u0000\u0084\u0088\u0003\b\u0004\u0000\u0085"+
		"\u0088\u0003\u000e\u0007\u0000\u0086\u0088\u0003\n\u0005\u0000\u0087\u0081"+
		"\u0001\u0000\u0000\u0000\u0087\u0083\u0001\u0000\u0000\u0000\u0087\u0085"+
		"\u0001\u0000\u0000\u0000\u0087\u0086\u0001\u0000\u0000\u0000\u0088\t\u0001"+
		"\u0000\u0000\u0000\u0089\u0090\u0003\u0010\b\u0000\u008a\u0090\u0003\f"+
		"\u0006\u0000\u008b\u008c\u0005\u0001\u0000\u0000\u008c\u008d\u0003\u0002"+
		"\u0001\u0000\u008d\u008e\u0005\u0002\u0000\u0000\u008e\u0090\u0001\u0000"+
		"\u0000\u0000\u008f\u0089\u0001\u0000\u0000\u0000\u008f\u008a\u0001\u0000"+
		"\u0000\u0000\u008f\u008b\u0001\u0000\u0000\u0000\u0090\u000b\u0001\u0000"+
		"\u0000\u0000\u0091\u0092\u0005\u0014\u0000\u0000\u0092\r\u0001\u0000\u0000"+
		"\u0000\u0093\u0094\u0005\u0016\u0000\u0000\u0094\u0095\u0005\u0001\u0000"+
		"\u0000\u0095\u0096\u0003\u0002\u0001\u0000\u0096\u0097\u0005\u0002\u0000"+
		"\u0000\u0097\u00c6\u0001\u0000\u0000\u0000\u0098\u0099\u0005\u0015\u0000"+
		"\u0000\u0099\u009a\u0005\u0001\u0000\u0000\u009a\u009b\u0003\u0002\u0001"+
		"\u0000\u009b\u009c\u0005\u0002\u0000\u0000\u009c\u00c6\u0001\u0000\u0000"+
		"\u0000\u009d\u009e\u0005\u0017\u0000\u0000\u009e\u009f\u0005\u0001\u0000"+
		"\u0000\u009f\u00a0\u0003\u0002\u0001\u0000\u00a0\u00a1\u0005\u0002\u0000"+
		"\u0000\u00a1\u00c6\u0001\u0000\u0000\u0000\u00a2\u00a3\u0005\u0018\u0000"+
		"\u0000\u00a3\u00a4\u0005\u0001\u0000\u0000\u00a4\u00a5\u0003\u0002\u0001"+
		"\u0000\u00a5\u00a6\u0005\u0002\u0000\u0000\u00a6\u00c6\u0001\u0000\u0000"+
		"\u0000\u00a7\u00a8\u0005\u0019\u0000\u0000\u00a8\u00a9\u0005\u0001\u0000"+
		"\u0000\u00a9\u00aa\u0003\u0002\u0001\u0000\u00aa\u00ab\u0005\u0002\u0000"+
		"\u0000\u00ab\u00c6\u0001\u0000\u0000\u0000\u00ac\u00ad\u0005\u001a\u0000"+
		"\u0000\u00ad\u00ae\u0005\u0001\u0000\u0000\u00ae\u00af\u0003\u0002\u0001"+
		"\u0000\u00af\u00b0\u0005\u0002\u0000\u0000\u00b0\u00c6\u0001\u0000\u0000"+
		"\u0000\u00b1\u00b2\u0005\u001c\u0000\u0000\u00b2\u00b3\u0005\u0001\u0000"+
		"\u0000\u00b3\u00b4\u0003\u0002\u0001\u0000\u00b4\u00b5\u0005\u0011\u0000"+
		"\u0000\u00b5\u00b6\u0003\u0002\u0001\u0000\u00b6\u00b7\u0005\u0002\u0000"+
		"\u0000\u00b7\u00c6\u0001\u0000\u0000\u0000\u00b8\u00b9\u0005\u001b\u0000"+
		"\u0000\u00b9\u00ba\u0005\u0001\u0000\u0000\u00ba\u00bb\u0003\u0002\u0001"+
		"\u0000\u00bb\u00bc\u0005\u0011\u0000\u0000\u00bc\u00bd\u0003\u0002\u0001"+
		"\u0000\u00bd\u00be\u0005\u0002\u0000\u0000\u00be\u00c6\u0001\u0000\u0000"+
		"\u0000\u00bf\u00c0\u0005\u001d\u0000\u0000\u00c0\u00c2\u0005\u0001\u0000"+
		"\u0000\u00c1\u00c3\u0003\u0002\u0001\u0000\u00c2\u00c1\u0001\u0000\u0000"+
		"\u0000\u00c2\u00c3\u0001\u0000\u0000\u0000\u00c3\u00c4\u0001\u0000\u0000"+
		"\u0000\u00c4\u00c6\u0005\u0002\u0000\u0000\u00c5\u0093\u0001\u0000\u0000"+
		"\u0000\u00c5\u0098\u0001\u0000\u0000\u0000\u00c5\u009d\u0001\u0000\u0000"+
		"\u0000\u00c5\u00a2\u0001\u0000\u0000\u0000\u00c5\u00a7\u0001\u0000\u0000"+
		"\u0000\u00c5\u00ac\u0001\u0000\u0000\u0000\u00c5\u00b1\u0001\u0000\u0000"+
		"\u0000\u00c5\u00b8\u0001\u0000\u0000\u0000\u00c5\u00bf\u0001\u0000\u0000"+
		"\u0000\u00c6\u000f\u0001\u0000\u0000\u0000\u00c7\u00cc\u0005\u001f\u0000"+
		"\u0000\u00c8\u00cc\u0005 \u0000\u0000\u00c9\u00cc\u0005!\u0000\u0000\u00ca"+
		"\u00cc\u0005\"\u0000\u0000\u00cb\u00c7\u0001\u0000\u0000\u0000\u00cb\u00c8"+
		"\u0001\u0000\u0000\u0000\u00cb\u00c9\u0001\u0000\u0000\u0000\u00cb\u00ca"+
		"\u0001\u0000\u0000\u0000\u00cc\u0011\u0001\u0000\u0000\u0000\u00122?F"+
		"HJRZ]dltw~\u0087\u008f\u00c2\u00c5\u00cb";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
		_decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
		for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
			_decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
		}
	}
}