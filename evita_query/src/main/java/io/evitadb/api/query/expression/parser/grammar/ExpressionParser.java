// Generated from Expression.g4 by ANTLR 4.9.2

    package io.evitadb.api.query.expression.parser.grammar;

import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.*;
import org.antlr.v4.runtime.tree.*;
import java.util.List;
import java.util.Iterator;
import java.util.ArrayList;

@SuppressWarnings({"all", "warnings", "unchecked", "unused", "cast"})
public class ExpressionParser extends Parser {
	static { RuntimeMetaData.checkVersion("4.9.2", RuntimeMetaData.VERSION); }

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
				if ((((_la) & ~0x3f) == 0 && ((1L << _la) & ((1L << LPAREN) | (1L << PLUS) | (1L << MINUS) | (1L << VARIABLE) | (1L << CEIL) | (1L << SQRT) | (1L << FLOOR) | (1L << ABS) | (1L << ROUND) | (1L << LOG) | (1L << MAX) | (1L << MIN) | (1L << RANDOM) | (1L << STRING) | (1L << INT) | (1L << FLOAT) | (1L << BOOLEAN))) != 0)) {
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
		"\3\u608b\ua72a\u8133\ub9ed\u417c\u3be7\u7786\u5964\3$\u00d0\4\2\t\2\4"+
		"\3\t\3\4\4\t\4\4\5\t\5\4\6\t\6\4\7\t\7\4\b\t\b\4\t\t\t\4\n\t\n\3\2\3\2"+
		"\3\2\3\2\3\2\3\2\3\2\3\2\3\2\3\2\3\2\3\2\3\2\3\2\3\2\3\2\3\2\3\2\3\2\3"+
		"\2\3\2\3\2\3\2\3\2\3\2\3\2\3\2\3\2\3\2\3\2\3\2\3\2\5\2\65\n\2\3\2\3\2"+
		"\3\2\3\2\3\2\3\2\3\2\3\2\3\2\6\2@\n\2\r\2\16\2A\3\2\3\2\3\2\6\2G\n\2\r"+
		"\2\16\2H\7\2K\n\2\f\2\16\2N\13\2\3\3\3\3\3\3\7\3S\n\3\f\3\16\3V\13\3\3"+
		"\3\3\3\3\3\7\3[\n\3\f\3\16\3^\13\3\5\3`\n\3\3\4\3\4\3\4\7\4e\n\4\f\4\16"+
		"\4h\13\4\3\4\3\4\3\4\7\4m\n\4\f\4\16\4p\13\4\3\4\3\4\3\4\7\4u\n\4\f\4"+
		"\16\4x\13\4\5\4z\n\4\3\5\3\5\3\5\7\5\177\n\5\f\5\16\5\u0082\13\5\3\6\3"+
		"\6\3\6\3\6\3\6\3\6\5\6\u008a\n\6\3\7\3\7\3\7\3\7\3\7\3\7\5\7\u0092\n\7"+
		"\3\b\3\b\3\t\3\t\3\t\3\t\3\t\3\t\3\t\3\t\3\t\3\t\3\t\3\t\3\t\3\t\3\t\3"+
		"\t\3\t\3\t\3\t\3\t\3\t\3\t\3\t\3\t\3\t\3\t\3\t\3\t\3\t\3\t\3\t\3\t\3\t"+
		"\3\t\3\t\3\t\3\t\3\t\3\t\3\t\3\t\3\t\3\t\3\t\3\t\3\t\3\t\5\t\u00c5\n\t"+
		"\3\t\5\t\u00c8\n\t\3\n\3\n\3\n\3\n\5\n\u00ce\n\n\3\n\2\3\2\13\2\4\6\b"+
		"\n\f\16\20\22\2\2\2\u00ef\2\64\3\2\2\2\4_\3\2\2\2\6y\3\2\2\2\b{\3\2\2"+
		"\2\n\u0089\3\2\2\2\f\u0091\3\2\2\2\16\u0093\3\2\2\2\20\u00c7\3\2\2\2\22"+
		"\u00cd\3\2\2\2\24\25\b\2\1\2\25\65\5\f\7\2\26\65\5\20\t\2\27\65\5\4\3"+
		"\2\30\31\7\20\2\2\31\32\7$\2\2\32\65\3\2\2\2\33\34\7\3\2\2\34\35\5\2\2"+
		"\2\35\36\7\4\2\2\36\65\3\2\2\2\37 \7\20\2\2 !\7\3\2\2!\"\5\2\2\2\"#\7"+
		"\4\2\2#\65\3\2\2\2$%\5\4\3\2%&\7\n\2\2&\'\5\4\3\2\'\65\3\2\2\2()\5\4\3"+
		"\2)*\7\13\2\2*+\5\4\3\2+\65\3\2\2\2,-\5\4\3\2-.\7\f\2\2./\5\4\3\2/\65"+
		"\3\2\2\2\60\61\5\4\3\2\61\62\7\r\2\2\62\63\5\4\3\2\63\65\3\2\2\2\64\24"+
		"\3\2\2\2\64\26\3\2\2\2\64\27\3\2\2\2\64\30\3\2\2\2\64\33\3\2\2\2\64\37"+
		"\3\2\2\2\64$\3\2\2\2\64(\3\2\2\2\64,\3\2\2\2\64\60\3\2\2\2\65L\3\2\2\2"+
		"\66\67\f\b\2\2\678\7\16\2\28K\5\2\2\t9:\f\7\2\2:;\7\17\2\2;K\5\2\2\b<"+
		"?\f\n\2\2=>\7\21\2\2>@\5\2\2\2?=\3\2\2\2@A\3\2\2\2A?\3\2\2\2AB\3\2\2\2"+
		"BK\3\2\2\2CF\f\t\2\2DE\7\22\2\2EG\5\2\2\2FD\3\2\2\2GH\3\2\2\2HF\3\2\2"+
		"\2HI\3\2\2\2IK\3\2\2\2J\66\3\2\2\2J9\3\2\2\2J<\3\2\2\2JC\3\2\2\2KN\3\2"+
		"\2\2LJ\3\2\2\2LM\3\2\2\2M\3\3\2\2\2NL\3\2\2\2OT\5\6\4\2PQ\7\5\2\2QS\5"+
		"\6\4\2RP\3\2\2\2SV\3\2\2\2TR\3\2\2\2TU\3\2\2\2U`\3\2\2\2VT\3\2\2\2W\\"+
		"\5\6\4\2XY\7\6\2\2Y[\5\6\4\2ZX\3\2\2\2[^\3\2\2\2\\Z\3\2\2\2\\]\3\2\2\2"+
		"]`\3\2\2\2^\\\3\2\2\2_O\3\2\2\2_W\3\2\2\2`\5\3\2\2\2af\5\b\5\2bc\7\7\2"+
		"\2ce\5\b\5\2db\3\2\2\2eh\3\2\2\2fd\3\2\2\2fg\3\2\2\2gz\3\2\2\2hf\3\2\2"+
		"\2in\5\b\5\2jk\7\b\2\2km\5\b\5\2lj\3\2\2\2mp\3\2\2\2nl\3\2\2\2no\3\2\2"+
		"\2oz\3\2\2\2pn\3\2\2\2qv\5\b\5\2rs\7\t\2\2su\5\b\5\2tr\3\2\2\2ux\3\2\2"+
		"\2vt\3\2\2\2vw\3\2\2\2wz\3\2\2\2xv\3\2\2\2ya\3\2\2\2yi\3\2\2\2yq\3\2\2"+
		"\2z\7\3\2\2\2{\u0080\5\n\6\2|}\7\25\2\2}\177\5\n\6\2~|\3\2\2\2\177\u0082"+
		"\3\2\2\2\u0080~\3\2\2\2\u0080\u0081\3\2\2\2\u0081\t\3\2\2\2\u0082\u0080"+
		"\3\2\2\2\u0083\u0084\7\5\2\2\u0084\u008a\5\n\6\2\u0085\u0086\7\6\2\2\u0086"+
		"\u008a\5\n\6\2\u0087\u008a\5\20\t\2\u0088\u008a\5\f\7\2\u0089\u0083\3"+
		"\2\2\2\u0089\u0085\3\2\2\2\u0089\u0087\3\2\2\2\u0089\u0088\3\2\2\2\u008a"+
		"\13\3\2\2\2\u008b\u0092\5\22\n\2\u008c\u0092\5\16\b\2\u008d\u008e\7\3"+
		"\2\2\u008e\u008f\5\4\3\2\u008f\u0090\7\4\2\2\u0090\u0092\3\2\2\2\u0091"+
		"\u008b\3\2\2\2\u0091\u008c\3\2\2\2\u0091\u008d\3\2\2\2\u0092\r\3\2\2\2"+
		"\u0093\u0094\7\26\2\2\u0094\17\3\2\2\2\u0095\u0096\7\30\2\2\u0096\u0097"+
		"\7\3\2\2\u0097\u0098\5\4\3\2\u0098\u0099\7\4\2\2\u0099\u00c8\3\2\2\2\u009a"+
		"\u009b\7\27\2\2\u009b\u009c\7\3\2\2\u009c\u009d\5\4\3\2\u009d\u009e\7"+
		"\4\2\2\u009e\u00c8\3\2\2\2\u009f\u00a0\7\31\2\2\u00a0\u00a1\7\3\2\2\u00a1"+
		"\u00a2\5\4\3\2\u00a2\u00a3\7\4\2\2\u00a3\u00c8\3\2\2\2\u00a4\u00a5\7\32"+
		"\2\2\u00a5\u00a6\7\3\2\2\u00a6\u00a7\5\4\3\2\u00a7\u00a8\7\4\2\2\u00a8"+
		"\u00c8\3\2\2\2\u00a9\u00aa\7\33\2\2\u00aa\u00ab\7\3\2\2\u00ab\u00ac\5"+
		"\4\3\2\u00ac\u00ad\7\4\2\2\u00ad\u00c8\3\2\2\2\u00ae\u00af\7\34\2\2\u00af"+
		"\u00b0\7\3\2\2\u00b0\u00b1\5\4\3\2\u00b1\u00b2\7\4\2\2\u00b2\u00c8\3\2"+
		"\2\2\u00b3\u00b4\7\36\2\2\u00b4\u00b5\7\3\2\2\u00b5\u00b6\5\4\3\2\u00b6"+
		"\u00b7\7\23\2\2\u00b7\u00b8\5\4\3\2\u00b8\u00b9\7\4\2\2\u00b9\u00c8\3"+
		"\2\2\2\u00ba\u00bb\7\35\2\2\u00bb\u00bc\7\3\2\2\u00bc\u00bd\5\4\3\2\u00bd"+
		"\u00be\7\23\2\2\u00be\u00bf\5\4\3\2\u00bf\u00c0\7\4\2\2\u00c0\u00c8\3"+
		"\2\2\2\u00c1\u00c2\7\37\2\2\u00c2\u00c4\7\3\2\2\u00c3\u00c5\5\4\3\2\u00c4"+
		"\u00c3\3\2\2\2\u00c4\u00c5\3\2\2\2\u00c5\u00c6\3\2\2\2\u00c6\u00c8\7\4"+
		"\2\2\u00c7\u0095\3\2\2\2\u00c7\u009a\3\2\2\2\u00c7\u009f\3\2\2\2\u00c7"+
		"\u00a4\3\2\2\2\u00c7\u00a9\3\2\2\2\u00c7\u00ae\3\2\2\2\u00c7\u00b3\3\2"+
		"\2\2\u00c7\u00ba\3\2\2\2\u00c7\u00c1\3\2\2\2\u00c8\21\3\2\2\2\u00c9\u00ce"+
		"\7!\2\2\u00ca\u00ce\7\"\2\2\u00cb\u00ce\7#\2\2\u00cc\u00ce\7$\2\2\u00cd"+
		"\u00c9\3\2\2\2\u00cd\u00ca\3\2\2\2\u00cd\u00cb\3\2\2\2\u00cd\u00cc\3\2"+
		"\2\2\u00ce\23\3\2\2\2\24\64AHJLT\\_fnvy\u0080\u0089\u0091\u00c4\u00c7"+
		"\u00cd";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
		_decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
		for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
			_decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
		}
	}
}