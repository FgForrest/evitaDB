/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

// Generated from EvitaQL.g4 by ANTLR 4.9.2

package io.evitadb.api.query.parser.grammar;

import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.*;
import org.antlr.v4.runtime.tree.*;
import java.util.List;
import java.util.Iterator;
import java.util.ArrayList;

@SuppressWarnings({"all", "warnings", "unchecked", "unused", "cast"})
public class EvitaQLParser extends Parser {
	static { RuntimeMetaData.checkVersion("4.9.2", RuntimeMetaData.VERSION); }

	protected static final DFA[] _decisionToDFA;
	protected static final PredictionContextCache _sharedContextCache =
		new PredictionContextCache();
	public static final int
		T__0=1, T__1=2, T__2=3, T__3=4, T__4=5, T__5=6, T__6=7, T__7=8, T__8=9,
		T__9=10, T__10=11, T__11=12, T__12=13, T__13=14, T__14=15, T__15=16, T__16=17,
		T__17=18, T__18=19, T__19=20, T__20=21, T__21=22, T__22=23, T__23=24,
		T__24=25, T__25=26, T__26=27, T__27=28, T__28=29, T__29=30, T__30=31,
		T__31=32, T__32=33, T__33=34, T__34=35, T__35=36, T__36=37, T__37=38,
		T__38=39, T__39=40, T__40=41, T__41=42, T__42=43, T__43=44, T__44=45,
		T__45=46, T__46=47, T__47=48, T__48=49, T__49=50, T__50=51, T__51=52,
		T__52=53, T__53=54, T__54=55, T__55=56, T__56=57, T__57=58, T__58=59,
		T__59=60, T__60=61, T__61=62, T__62=63, T__63=64, T__64=65, T__65=66,
		T__66=67, T__67=68, T__68=69, T__69=70, T__70=71, T__71=72, T__72=73,
		T__73=74, T__74=75, T__75=76, T__76=77, T__77=78, T__78=79, POSITIONAL_PARAMETER=80,
		NAMED_PARAMETER=81, STRING=82, INT=83, FLOAT=84, BOOLEAN=85, DATE=86,
		TIME=87, DATE_TIME=88, OFFSET_DATE_TIME=89, FLOAT_NUMBER_RANGE=90, INT_NUMBER_RANGE=91,
		DATE_TIME_RANGE=92, ENUM=93, ARGS_OPENING=94, ARGS_CLOSING=95, ARGS_DELIMITER=96,
		MULTIPLE_OPENING=97, MULTIPLE_CLOSING=98, WHITESPACE=99, UNEXPECTED_CHAR=100;
	public static final int
		RULE_queryUnit = 0, RULE_headConstraintListUnit = 1, RULE_filterConstraintListUnit = 2,
		RULE_orderConstraintListUnit = 3, RULE_requireConstraintListUnit = 4,
		RULE_classifierTokenUnit = 5, RULE_valueTokenUnit = 6, RULE_query = 7,
		RULE_constraint = 8, RULE_headConstraint = 9, RULE_filterConstraint = 10,
		RULE_orderConstraint = 11, RULE_requireConstraint = 12, RULE_headConstraintList = 13,
		RULE_filterConstraintList = 14, RULE_orderConstraintList = 15, RULE_requireConstraintList = 16,
		RULE_constraintListArgs = 17, RULE_emptyArgs = 18, RULE_filterConstraintListArgs = 19,
		RULE_filterConstraintArgs = 20, RULE_orderConstraintListArgs = 21, RULE_requireConstraintArgs = 22,
		RULE_requireConstraintListArgs = 23, RULE_classifierArgs = 24, RULE_classifierWithValueArgs = 25,
		RULE_classifierWithOptionalValueArgs = 26, RULE_classifierWithValueListArgs = 27,
		RULE_classifierWithBetweenValuesArgs = 28, RULE_valueArgs = 29, RULE_valueListArgs = 30,
		RULE_betweenValuesArgs = 31, RULE_classifierListArgs = 32, RULE_valueWithClassifierListArgs = 33,
		RULE_classifierWithFilterConstraintArgs = 34, RULE_classifierWithOrderConstraintListArgs = 35,
		RULE_valueWithRequireConstraintListArgs = 36, RULE_hierarchyWithinConstraintArgs = 37,
		RULE_hierarchyWithinSelfConstraintArgs = 38, RULE_hierarchyWithinRootConstraintArgs = 39,
		RULE_hierarchyWithinRootSelfConstraintArgs = 40, RULE_pageConstraintArgs = 41,
		RULE_stripConstraintArgs = 42, RULE_singleRefReferenceContentArgs = 43,
		RULE_singleRefWithFilterReferenceContentArgs = 44, RULE_singleRefWithOrderReferenceContentArgs = 45,
		RULE_singleRefWithFilterAndOrderReferenceContentArgs = 46, RULE_multipleRefsReferenceContentArgs = 47,
		RULE_allRefsReferenceContentArgs = 48, RULE_singleRequireHierarchyContentArgs = 49,
		RULE_allRequiresHierarchyContentArgs = 50, RULE_facetSummaryArgs = 51,
		RULE_facetSummaryOfReferenceArgs = 52, RULE_fullHierarchyStatisticsArgs = 53,
		RULE_hierarchyRequireConstraintArgs = 54, RULE_hierarchyFromNodeArgs = 55,
		RULE_fullHierarchyOfSelfArgs = 56, RULE_basicHierarchyOfReferenceArgs = 57,
		RULE_fullHierarchyOfReferenceArgs = 58, RULE_positionalParameter = 59,
		RULE_namedParameter = 60, RULE_variadicClassifierTokens = 61, RULE_classifierToken = 62,
		RULE_variadicValueTokens = 63, RULE_valueToken = 64;
	private static String[] makeRuleNames() {
		return new String[] {
			"queryUnit", "headConstraintListUnit", "filterConstraintListUnit", "orderConstraintListUnit",
			"requireConstraintListUnit", "classifierTokenUnit", "valueTokenUnit",
			"query", "constraint", "headConstraint", "filterConstraint", "orderConstraint",
			"requireConstraint", "headConstraintList", "filterConstraintList", "orderConstraintList",
			"requireConstraintList", "constraintListArgs", "emptyArgs", "filterConstraintListArgs",
			"filterConstraintArgs", "orderConstraintListArgs", "requireConstraintArgs",
			"requireConstraintListArgs", "classifierArgs", "classifierWithValueArgs",
			"classifierWithOptionalValueArgs", "classifierWithValueListArgs", "classifierWithBetweenValuesArgs",
			"valueArgs", "valueListArgs", "betweenValuesArgs", "classifierListArgs",
			"valueWithClassifierListArgs", "classifierWithFilterConstraintArgs",
			"classifierWithOrderConstraintListArgs", "valueWithRequireConstraintListArgs",
			"hierarchyWithinConstraintArgs", "hierarchyWithinSelfConstraintArgs",
			"hierarchyWithinRootConstraintArgs", "hierarchyWithinRootSelfConstraintArgs",
			"pageConstraintArgs", "stripConstraintArgs", "singleRefReferenceContentArgs",
			"singleRefWithFilterReferenceContentArgs", "singleRefWithOrderReferenceContentArgs",
			"singleRefWithFilterAndOrderReferenceContentArgs", "multipleRefsReferenceContentArgs",
			"allRefsReferenceContentArgs", "singleRequireHierarchyContentArgs", "allRequiresHierarchyContentArgs",
			"facetSummaryArgs", "facetSummaryOfReferenceArgs", "fullHierarchyStatisticsArgs",
			"hierarchyRequireConstraintArgs", "hierarchyFromNodeArgs", "fullHierarchyOfSelfArgs",
			"basicHierarchyOfReferenceArgs", "fullHierarchyOfReferenceArgs", "positionalParameter",
			"namedParameter", "variadicClassifierTokens", "classifierToken", "variadicValueTokens",
			"valueToken"
		};
	}
	public static final String[] ruleNames = makeRuleNames();

	private static String[] makeLiteralNames() {
		return new String[] {
			null, "'query'", "'collection'", "'filterBy'", "'and'", "'or'", "'not'",
			"'userFilter'", "'attributeEquals'", "'attributeGreaterThan'", "'attributeGreaterThanEquals'",
			"'attributeLessThan'", "'attributeLessThanEquals'", "'attributeBetween'",
			"'attributeInSet'", "'attributeContains'", "'attributeStartsWith'", "'attributeEndsWith'",
			"'attributeEqualsTrue'", "'attributeEqualsFalse'", "'attributeIs'", "'attributeIsNull'",
			"'attributeIsNotNull'", "'attributeInRange'", "'entityPrimaryKeyInSet'",
			"'entityLocaleEquals'", "'priceInCurrency'", "'priceInPriceLists'", "'priceValidNow'",
			"'priceValidIn'", "'priceBetween'", "'facetHaving'", "'referenceHaving'",
			"'hierarchyWithin'", "'hierarchyWithinSelf'", "'hierarchyWithinRoot'",
			"'hierarchyWithinRootSelf'", "'directRelation'", "'excludingRoot'", "'excluding'",
			"'entityHaving'", "'orderBy'", "'attributeNatural'", "'priceNatural'",
			"'random'", "'referenceProperty'", "'entityProperty'", "'require'", "'page'",
			"'strip'", "'entityFetch'", "'entityGroupFetch'", "'attributeContent'",
			"'priceContent'", "'priceContentAll'", "'associatedDataContent'", "'referenceContent'",
			"'hierarchyContent'", "'priceType'", "'dataInLocales'", "'facetSummary'",
			"'facetSummaryOfReference'", "'facetGroupsConjunction'", "'facetGroupsDisjunction'",
			"'facetGroupsNegation'", "'attributeHistogram'", "'priceHistogram'",
			"'distance'", "'level'", "'node'", "'stopAt'", "'statistics'", "'fromRoot'",
			"'fromNode'", "'children'", "'siblings'", "'parents'", "'hierarchyOfSelf'",
			"'hierarchyOfReference'", "'queryTelemetry'", "'?'", null, null, null,
			null, null, null, null, null, null, null, null, null, null, "'('", "')'",
			"','", "'{'", "'}'"
		};
	}
	private static final String[] _LITERAL_NAMES = makeLiteralNames();
	private static String[] makeSymbolicNames() {
		return new String[] {
			null, null, null, null, null, null, null, null, null, null, null, null,
			null, null, null, null, null, null, null, null, null, null, null, null,
			null, null, null, null, null, null, null, null, null, null, null, null,
			null, null, null, null, null, null, null, null, null, null, null, null,
			null, null, null, null, null, null, null, null, null, null, null, null,
			null, null, null, null, null, null, null, null, null, null, null, null,
			null, null, null, null, null, null, null, null, "POSITIONAL_PARAMETER",
			"NAMED_PARAMETER", "STRING", "INT", "FLOAT", "BOOLEAN", "DATE", "TIME",
			"DATE_TIME", "OFFSET_DATE_TIME", "FLOAT_NUMBER_RANGE", "INT_NUMBER_RANGE",
			"DATE_TIME_RANGE", "ENUM", "ARGS_OPENING", "ARGS_CLOSING", "ARGS_DELIMITER",
			"MULTIPLE_OPENING", "MULTIPLE_CLOSING", "WHITESPACE", "UNEXPECTED_CHAR"
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
	public String getGrammarFileName() { return "EvitaQL.g4"; }

	@Override
	public String[] getRuleNames() { return ruleNames; }

	@Override
	public String getSerializedATN() { return _serializedATN; }

	@Override
	public ATN getATN() { return _ATN; }

	public EvitaQLParser(TokenStream input) {
		super(input);
		_interp = new ParserATNSimulator(this,_ATN,_decisionToDFA,_sharedContextCache);
	}

	public static class QueryUnitContext extends ParserRuleContext {
		public QueryContext query() {
			return getRuleContext(QueryContext.class,0);
		}
		public TerminalNode EOF() { return getToken(EvitaQLParser.EOF, 0); }
		public QueryUnitContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_queryUnit; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterQueryUnit(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitQueryUnit(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitQueryUnit(this);
			else return visitor.visitChildren(this);
		}
	}

	public final QueryUnitContext queryUnit() throws RecognitionException {
		QueryUnitContext _localctx = new QueryUnitContext(_ctx, getState());
		enterRule(_localctx, 0, RULE_queryUnit);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(130);
			query();
			setState(131);
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

	public static class HeadConstraintListUnitContext extends ParserRuleContext {
		public HeadConstraintListContext headConstraintList() {
			return getRuleContext(HeadConstraintListContext.class,0);
		}
		public TerminalNode EOF() { return getToken(EvitaQLParser.EOF, 0); }
		public HeadConstraintListUnitContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_headConstraintListUnit; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHeadConstraintListUnit(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHeadConstraintListUnit(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHeadConstraintListUnit(this);
			else return visitor.visitChildren(this);
		}
	}

	public final HeadConstraintListUnitContext headConstraintListUnit() throws RecognitionException {
		HeadConstraintListUnitContext _localctx = new HeadConstraintListUnitContext(_ctx, getState());
		enterRule(_localctx, 2, RULE_headConstraintListUnit);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(133);
			headConstraintList();
			setState(134);
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

	public static class FilterConstraintListUnitContext extends ParserRuleContext {
		public FilterConstraintListContext filterConstraintList() {
			return getRuleContext(FilterConstraintListContext.class,0);
		}
		public TerminalNode EOF() { return getToken(EvitaQLParser.EOF, 0); }
		public FilterConstraintListUnitContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_filterConstraintListUnit; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFilterConstraintListUnit(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFilterConstraintListUnit(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFilterConstraintListUnit(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FilterConstraintListUnitContext filterConstraintListUnit() throws RecognitionException {
		FilterConstraintListUnitContext _localctx = new FilterConstraintListUnitContext(_ctx, getState());
		enterRule(_localctx, 4, RULE_filterConstraintListUnit);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(136);
			filterConstraintList();
			setState(137);
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

	public static class OrderConstraintListUnitContext extends ParserRuleContext {
		public OrderConstraintListContext orderConstraintList() {
			return getRuleContext(OrderConstraintListContext.class,0);
		}
		public TerminalNode EOF() { return getToken(EvitaQLParser.EOF, 0); }
		public OrderConstraintListUnitContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_orderConstraintListUnit; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterOrderConstraintListUnit(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitOrderConstraintListUnit(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitOrderConstraintListUnit(this);
			else return visitor.visitChildren(this);
		}
	}

	public final OrderConstraintListUnitContext orderConstraintListUnit() throws RecognitionException {
		OrderConstraintListUnitContext _localctx = new OrderConstraintListUnitContext(_ctx, getState());
		enterRule(_localctx, 6, RULE_orderConstraintListUnit);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(139);
			orderConstraintList();
			setState(140);
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

	public static class RequireConstraintListUnitContext extends ParserRuleContext {
		public RequireConstraintListContext requireConstraintList() {
			return getRuleContext(RequireConstraintListContext.class,0);
		}
		public TerminalNode EOF() { return getToken(EvitaQLParser.EOF, 0); }
		public RequireConstraintListUnitContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_requireConstraintListUnit; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterRequireConstraintListUnit(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitRequireConstraintListUnit(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitRequireConstraintListUnit(this);
			else return visitor.visitChildren(this);
		}
	}

	public final RequireConstraintListUnitContext requireConstraintListUnit() throws RecognitionException {
		RequireConstraintListUnitContext _localctx = new RequireConstraintListUnitContext(_ctx, getState());
		enterRule(_localctx, 8, RULE_requireConstraintListUnit);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(142);
			requireConstraintList();
			setState(143);
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

	public static class ClassifierTokenUnitContext extends ParserRuleContext {
		public ClassifierTokenContext classifierToken() {
			return getRuleContext(ClassifierTokenContext.class,0);
		}
		public TerminalNode EOF() { return getToken(EvitaQLParser.EOF, 0); }
		public ClassifierTokenUnitContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_classifierTokenUnit; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterClassifierTokenUnit(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitClassifierTokenUnit(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitClassifierTokenUnit(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ClassifierTokenUnitContext classifierTokenUnit() throws RecognitionException {
		ClassifierTokenUnitContext _localctx = new ClassifierTokenUnitContext(_ctx, getState());
		enterRule(_localctx, 10, RULE_classifierTokenUnit);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(145);
			classifierToken();
			setState(146);
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

	public static class ValueTokenUnitContext extends ParserRuleContext {
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
		}
		public TerminalNode EOF() { return getToken(EvitaQLParser.EOF, 0); }
		public ValueTokenUnitContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_valueTokenUnit; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterValueTokenUnit(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitValueTokenUnit(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitValueTokenUnit(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ValueTokenUnitContext valueTokenUnit() throws RecognitionException {
		ValueTokenUnitContext _localctx = new ValueTokenUnitContext(_ctx, getState());
		enterRule(_localctx, 12, RULE_valueTokenUnit);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(148);
			valueToken();
			setState(149);
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

	public static class QueryContext extends ParserRuleContext {
		public ConstraintListArgsContext args;
		public ConstraintListArgsContext constraintListArgs() {
			return getRuleContext(ConstraintListArgsContext.class,0);
		}
		public QueryContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_query; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterQuery(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitQuery(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitQuery(this);
			else return visitor.visitChildren(this);
		}
	}

	public final QueryContext query() throws RecognitionException {
		QueryContext _localctx = new QueryContext(_ctx, getState());
		enterRule(_localctx, 14, RULE_query);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(151);
			match(T__0);
			setState(152);
			((QueryContext)_localctx).args = constraintListArgs();
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

	public static class ConstraintContext extends ParserRuleContext {
		public HeadConstraintContext headConstraint() {
			return getRuleContext(HeadConstraintContext.class,0);
		}
		public FilterConstraintContext filterConstraint() {
			return getRuleContext(FilterConstraintContext.class,0);
		}
		public OrderConstraintContext orderConstraint() {
			return getRuleContext(OrderConstraintContext.class,0);
		}
		public RequireConstraintContext requireConstraint() {
			return getRuleContext(RequireConstraintContext.class,0);
		}
		public ConstraintContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_constraint; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitConstraint(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ConstraintContext constraint() throws RecognitionException {
		ConstraintContext _localctx = new ConstraintContext(_ctx, getState());
		enterRule(_localctx, 16, RULE_constraint);
		try {
			setState(158);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case T__1:
				enterOuterAlt(_localctx, 1);
				{
				setState(154);
				headConstraint();
				}
				break;
			case T__2:
			case T__3:
			case T__4:
			case T__5:
			case T__6:
			case T__7:
			case T__8:
			case T__9:
			case T__10:
			case T__11:
			case T__12:
			case T__13:
			case T__14:
			case T__15:
			case T__16:
			case T__17:
			case T__18:
			case T__19:
			case T__20:
			case T__21:
			case T__22:
			case T__23:
			case T__24:
			case T__25:
			case T__26:
			case T__27:
			case T__28:
			case T__29:
			case T__30:
			case T__31:
			case T__32:
			case T__33:
			case T__34:
			case T__35:
			case T__36:
			case T__37:
			case T__38:
			case T__39:
				enterOuterAlt(_localctx, 2);
				{
				setState(155);
				filterConstraint();
				}
				break;
			case T__40:
			case T__41:
			case T__42:
			case T__43:
			case T__44:
			case T__45:
				enterOuterAlt(_localctx, 3);
				{
				setState(156);
				orderConstraint();
				}
				break;
			case T__46:
			case T__47:
			case T__48:
			case T__49:
			case T__50:
			case T__51:
			case T__52:
			case T__53:
			case T__54:
			case T__55:
			case T__56:
			case T__57:
			case T__58:
			case T__59:
			case T__60:
			case T__61:
			case T__62:
			case T__63:
			case T__64:
			case T__65:
			case T__66:
			case T__67:
			case T__68:
			case T__69:
			case T__70:
			case T__71:
			case T__72:
			case T__73:
			case T__74:
			case T__75:
			case T__76:
			case T__77:
			case T__78:
				enterOuterAlt(_localctx, 4);
				{
				setState(157);
				requireConstraint();
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

	public static class HeadConstraintContext extends ParserRuleContext {
		public HeadConstraintContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_headConstraint; }

		public HeadConstraintContext() { }
		public void copyFrom(HeadConstraintContext ctx) {
			super.copyFrom(ctx);
		}
	}
	public static class CollectionConstraintContext extends HeadConstraintContext {
		public ClassifierArgsContext args;
		public ClassifierArgsContext classifierArgs() {
			return getRuleContext(ClassifierArgsContext.class,0);
		}
		public CollectionConstraintContext(HeadConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterCollectionConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitCollectionConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitCollectionConstraint(this);
			else return visitor.visitChildren(this);
		}
	}

	public final HeadConstraintContext headConstraint() throws RecognitionException {
		HeadConstraintContext _localctx = new HeadConstraintContext(_ctx, getState());
		enterRule(_localctx, 18, RULE_headConstraint);
		try {
			_localctx = new CollectionConstraintContext(_localctx);
			enterOuterAlt(_localctx, 1);
			{
			setState(160);
			match(T__1);
			setState(161);
			((CollectionConstraintContext)_localctx).args = classifierArgs();
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

	public static class FilterConstraintContext extends ParserRuleContext {
		public FilterConstraintContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_filterConstraint; }

		public FilterConstraintContext() { }
		public void copyFrom(FilterConstraintContext ctx) {
			super.copyFrom(ctx);
		}
	}
	public static class AttributeBetweenConstraintContext extends FilterConstraintContext {
		public ClassifierWithBetweenValuesArgsContext args;
		public ClassifierWithBetweenValuesArgsContext classifierWithBetweenValuesArgs() {
			return getRuleContext(ClassifierWithBetweenValuesArgsContext.class,0);
		}
		public AttributeBetweenConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAttributeBetweenConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAttributeBetweenConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAttributeBetweenConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class HierarchyWithinConstraintContext extends FilterConstraintContext {
		public HierarchyWithinConstraintArgsContext args;
		public HierarchyWithinConstraintArgsContext hierarchyWithinConstraintArgs() {
			return getRuleContext(HierarchyWithinConstraintArgsContext.class,0);
		}
		public HierarchyWithinConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHierarchyWithinConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHierarchyWithinConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHierarchyWithinConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class AttributeIsNotNullConstraintContext extends FilterConstraintContext {
		public ClassifierArgsContext args;
		public ClassifierArgsContext classifierArgs() {
			return getRuleContext(ClassifierArgsContext.class,0);
		}
		public AttributeIsNotNullConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAttributeIsNotNullConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAttributeIsNotNullConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAttributeIsNotNullConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class AttributeInRangeConstraintContext extends FilterConstraintContext {
		public ClassifierWithValueArgsContext args;
		public ClassifierWithValueArgsContext classifierWithValueArgs() {
			return getRuleContext(ClassifierWithValueArgsContext.class,0);
		}
		public AttributeInRangeConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAttributeInRangeConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAttributeInRangeConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAttributeInRangeConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class AttributeEndsWithConstraintContext extends FilterConstraintContext {
		public ClassifierWithValueArgsContext args;
		public ClassifierWithValueArgsContext classifierWithValueArgs() {
			return getRuleContext(ClassifierWithValueArgsContext.class,0);
		}
		public AttributeEndsWithConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAttributeEndsWithConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAttributeEndsWithConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAttributeEndsWithConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class HierarchyWithinRootSelfConstraintContext extends FilterConstraintContext {
		public HierarchyWithinRootSelfConstraintArgsContext args;
		public HierarchyWithinRootSelfConstraintArgsContext hierarchyWithinRootSelfConstraintArgs() {
			return getRuleContext(HierarchyWithinRootSelfConstraintArgsContext.class,0);
		}
		public HierarchyWithinRootSelfConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHierarchyWithinRootSelfConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHierarchyWithinRootSelfConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHierarchyWithinRootSelfConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class UserFilterConstraintContext extends FilterConstraintContext {
		public FilterConstraintListArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public FilterConstraintListArgsContext filterConstraintListArgs() {
			return getRuleContext(FilterConstraintListArgsContext.class,0);
		}
		public UserFilterConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterUserFilterConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitUserFilterConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitUserFilterConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class HierarchyDirectRelationConstraintContext extends FilterConstraintContext {
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public HierarchyDirectRelationConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHierarchyDirectRelationConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHierarchyDirectRelationConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHierarchyDirectRelationConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class HierarchyExcludingRootConstraintContext extends FilterConstraintContext {
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public HierarchyExcludingRootConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHierarchyExcludingRootConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHierarchyExcludingRootConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHierarchyExcludingRootConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class AttributeGreaterThanEqualsConstraintContext extends FilterConstraintContext {
		public ClassifierWithValueArgsContext args;
		public ClassifierWithValueArgsContext classifierWithValueArgs() {
			return getRuleContext(ClassifierWithValueArgsContext.class,0);
		}
		public AttributeGreaterThanEqualsConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAttributeGreaterThanEqualsConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAttributeGreaterThanEqualsConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAttributeGreaterThanEqualsConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class PriceValidInConstraintContext extends FilterConstraintContext {
		public ValueArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public ValueArgsContext valueArgs() {
			return getRuleContext(ValueArgsContext.class,0);
		}
		public PriceValidInConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterPriceValidInConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitPriceValidInConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitPriceValidInConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class EntityPrimaryKeyInSetConstraintContext extends FilterConstraintContext {
		public ValueListArgsContext args;
		public ValueListArgsContext valueListArgs() {
			return getRuleContext(ValueListArgsContext.class,0);
		}
		public EntityPrimaryKeyInSetConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterEntityPrimaryKeyInSetConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitEntityPrimaryKeyInSetConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitEntityPrimaryKeyInSetConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class FilterByConstraintContext extends FilterConstraintContext {
		public FilterConstraintListArgsContext args;
		public FilterConstraintListArgsContext filterConstraintListArgs() {
			return getRuleContext(FilterConstraintListArgsContext.class,0);
		}
		public FilterByConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFilterByConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFilterByConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFilterByConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class HierarchyWithinSelfConstraintContext extends FilterConstraintContext {
		public HierarchyWithinSelfConstraintArgsContext args;
		public HierarchyWithinSelfConstraintArgsContext hierarchyWithinSelfConstraintArgs() {
			return getRuleContext(HierarchyWithinSelfConstraintArgsContext.class,0);
		}
		public HierarchyWithinSelfConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHierarchyWithinSelfConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHierarchyWithinSelfConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHierarchyWithinSelfConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class PriceInPriceListsConstraintsContext extends FilterConstraintContext {
		public ClassifierListArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public ClassifierListArgsContext classifierListArgs() {
			return getRuleContext(ClassifierListArgsContext.class,0);
		}
		public PriceInPriceListsConstraintsContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterPriceInPriceListsConstraints(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitPriceInPriceListsConstraints(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitPriceInPriceListsConstraints(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class AttributeEqualsTrueConstraintContext extends FilterConstraintContext {
		public ClassifierArgsContext args;
		public ClassifierArgsContext classifierArgs() {
			return getRuleContext(ClassifierArgsContext.class,0);
		}
		public AttributeEqualsTrueConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAttributeEqualsTrueConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAttributeEqualsTrueConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAttributeEqualsTrueConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class FacetHavingConstraintContext extends FilterConstraintContext {
		public ClassifierWithFilterConstraintArgsContext args;
		public ClassifierWithFilterConstraintArgsContext classifierWithFilterConstraintArgs() {
			return getRuleContext(ClassifierWithFilterConstraintArgsContext.class,0);
		}
		public FacetHavingConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetHavingConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetHavingConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetHavingConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class AndConstraintContext extends FilterConstraintContext {
		public FilterConstraintListArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public FilterConstraintListArgsContext filterConstraintListArgs() {
			return getRuleContext(FilterConstraintListArgsContext.class,0);
		}
		public AndConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAndConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAndConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAndConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class AttributeContainsConstraintContext extends FilterConstraintContext {
		public ClassifierWithValueArgsContext args;
		public ClassifierWithValueArgsContext classifierWithValueArgs() {
			return getRuleContext(ClassifierWithValueArgsContext.class,0);
		}
		public AttributeContainsConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAttributeContainsConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAttributeContainsConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAttributeContainsConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class PriceInCurrencyConstraintContext extends FilterConstraintContext {
		public ValueArgsContext args;
		public ValueArgsContext valueArgs() {
			return getRuleContext(ValueArgsContext.class,0);
		}
		public PriceInCurrencyConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterPriceInCurrencyConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitPriceInCurrencyConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitPriceInCurrencyConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class AttributeInSetConstraintContext extends FilterConstraintContext {
		public ClassifierWithValueListArgsContext args;
		public ClassifierWithValueListArgsContext classifierWithValueListArgs() {
			return getRuleContext(ClassifierWithValueListArgsContext.class,0);
		}
		public AttributeInSetConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAttributeInSetConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAttributeInSetConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAttributeInSetConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class AttributeLessThanEqualsConstraintContext extends FilterConstraintContext {
		public ClassifierWithValueArgsContext args;
		public ClassifierWithValueArgsContext classifierWithValueArgs() {
			return getRuleContext(ClassifierWithValueArgsContext.class,0);
		}
		public AttributeLessThanEqualsConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAttributeLessThanEqualsConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAttributeLessThanEqualsConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAttributeLessThanEqualsConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class PriceBetweenConstraintContext extends FilterConstraintContext {
		public BetweenValuesArgsContext args;
		public BetweenValuesArgsContext betweenValuesArgs() {
			return getRuleContext(BetweenValuesArgsContext.class,0);
		}
		public PriceBetweenConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterPriceBetweenConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitPriceBetweenConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitPriceBetweenConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class HierarchyWithinRootConstraintContext extends FilterConstraintContext {
		public HierarchyWithinRootConstraintArgsContext args;
		public HierarchyWithinRootConstraintArgsContext hierarchyWithinRootConstraintArgs() {
			return getRuleContext(HierarchyWithinRootConstraintArgsContext.class,0);
		}
		public HierarchyWithinRootConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHierarchyWithinRootConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHierarchyWithinRootConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHierarchyWithinRootConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class HierarchyExcludingConstraintContext extends FilterConstraintContext {
		public FilterConstraintListArgsContext args;
		public FilterConstraintListArgsContext filterConstraintListArgs() {
			return getRuleContext(FilterConstraintListArgsContext.class,0);
		}
		public HierarchyExcludingConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHierarchyExcludingConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHierarchyExcludingConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHierarchyExcludingConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class AttributeLessThanConstraintContext extends FilterConstraintContext {
		public ClassifierWithValueArgsContext args;
		public ClassifierWithValueArgsContext classifierWithValueArgs() {
			return getRuleContext(ClassifierWithValueArgsContext.class,0);
		}
		public AttributeLessThanConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAttributeLessThanConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAttributeLessThanConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAttributeLessThanConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class AttributeIsConstraintContext extends FilterConstraintContext {
		public ClassifierWithValueArgsContext args;
		public ClassifierWithValueArgsContext classifierWithValueArgs() {
			return getRuleContext(ClassifierWithValueArgsContext.class,0);
		}
		public AttributeIsConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAttributeIsConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAttributeIsConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAttributeIsConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class EntityHavingConstraintContext extends FilterConstraintContext {
		public FilterConstraintArgsContext args;
		public FilterConstraintArgsContext filterConstraintArgs() {
			return getRuleContext(FilterConstraintArgsContext.class,0);
		}
		public EntityHavingConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterEntityHavingConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitEntityHavingConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitEntityHavingConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class PriceValidNowConstraintContext extends FilterConstraintContext {
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public PriceValidNowConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterPriceValidNowConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitPriceValidNowConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitPriceValidNowConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class AttributeIsNullConstraintContext extends FilterConstraintContext {
		public ClassifierArgsContext args;
		public ClassifierArgsContext classifierArgs() {
			return getRuleContext(ClassifierArgsContext.class,0);
		}
		public AttributeIsNullConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAttributeIsNullConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAttributeIsNullConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAttributeIsNullConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class EntityLocaleEqualsConstraintContext extends FilterConstraintContext {
		public ValueArgsContext args;
		public ValueArgsContext valueArgs() {
			return getRuleContext(ValueArgsContext.class,0);
		}
		public EntityLocaleEqualsConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterEntityLocaleEqualsConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitEntityLocaleEqualsConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitEntityLocaleEqualsConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class AttributeEqualsConstraintContext extends FilterConstraintContext {
		public ClassifierWithValueArgsContext args;
		public ClassifierWithValueArgsContext classifierWithValueArgs() {
			return getRuleContext(ClassifierWithValueArgsContext.class,0);
		}
		public AttributeEqualsConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAttributeEqualsConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAttributeEqualsConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAttributeEqualsConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class AttributeStartsWithConstraintContext extends FilterConstraintContext {
		public ClassifierWithValueArgsContext args;
		public ClassifierWithValueArgsContext classifierWithValueArgs() {
			return getRuleContext(ClassifierWithValueArgsContext.class,0);
		}
		public AttributeStartsWithConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAttributeStartsWithConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAttributeStartsWithConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAttributeStartsWithConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class AttributeGreaterThanConstraintContext extends FilterConstraintContext {
		public ClassifierWithValueArgsContext args;
		public ClassifierWithValueArgsContext classifierWithValueArgs() {
			return getRuleContext(ClassifierWithValueArgsContext.class,0);
		}
		public AttributeGreaterThanConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAttributeGreaterThanConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAttributeGreaterThanConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAttributeGreaterThanConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class AttributeEqualsFalseConstraintContext extends FilterConstraintContext {
		public ClassifierArgsContext args;
		public ClassifierArgsContext classifierArgs() {
			return getRuleContext(ClassifierArgsContext.class,0);
		}
		public AttributeEqualsFalseConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAttributeEqualsFalseConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAttributeEqualsFalseConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAttributeEqualsFalseConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class OrConstraintContext extends FilterConstraintContext {
		public FilterConstraintListArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public FilterConstraintListArgsContext filterConstraintListArgs() {
			return getRuleContext(FilterConstraintListArgsContext.class,0);
		}
		public OrConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterOrConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitOrConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitOrConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class ReferenceHavingConstraintContext extends FilterConstraintContext {
		public ClassifierWithFilterConstraintArgsContext args;
		public ClassifierWithFilterConstraintArgsContext classifierWithFilterConstraintArgs() {
			return getRuleContext(ClassifierWithFilterConstraintArgsContext.class,0);
		}
		public ReferenceHavingConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceHavingConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceHavingConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceHavingConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class NotConstraintContext extends FilterConstraintContext {
		public FilterConstraintArgsContext args;
		public FilterConstraintArgsContext filterConstraintArgs() {
			return getRuleContext(FilterConstraintArgsContext.class,0);
		}
		public NotConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterNotConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitNotConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitNotConstraint(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FilterConstraintContext filterConstraint() throws RecognitionException {
		FilterConstraintContext _localctx = new FilterConstraintContext(_ctx, getState());
		enterRule(_localctx, 20, RULE_filterConstraint);
		try {
			setState(254);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case T__2:
				_localctx = new FilterByConstraintContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(163);
				match(T__2);
				setState(164);
				((FilterByConstraintContext)_localctx).args = filterConstraintListArgs();
				}
				break;
			case T__3:
				_localctx = new AndConstraintContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(165);
				match(T__3);
				setState(168);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,1,_ctx) ) {
				case 1:
					{
					setState(166);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(167);
					((AndConstraintContext)_localctx).args = filterConstraintListArgs();
					}
					break;
				}
				}
				break;
			case T__4:
				_localctx = new OrConstraintContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(170);
				match(T__4);
				setState(173);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,2,_ctx) ) {
				case 1:
					{
					setState(171);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(172);
					((OrConstraintContext)_localctx).args = filterConstraintListArgs();
					}
					break;
				}
				}
				break;
			case T__5:
				_localctx = new NotConstraintContext(_localctx);
				enterOuterAlt(_localctx, 4);
				{
				setState(175);
				match(T__5);
				setState(176);
				((NotConstraintContext)_localctx).args = filterConstraintArgs();
				}
				break;
			case T__6:
				_localctx = new UserFilterConstraintContext(_localctx);
				enterOuterAlt(_localctx, 5);
				{
				setState(177);
				match(T__6);
				setState(180);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,3,_ctx) ) {
				case 1:
					{
					setState(178);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(179);
					((UserFilterConstraintContext)_localctx).args = filterConstraintListArgs();
					}
					break;
				}
				}
				break;
			case T__7:
				_localctx = new AttributeEqualsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 6);
				{
				setState(182);
				match(T__7);
				setState(183);
				((AttributeEqualsConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__8:
				_localctx = new AttributeGreaterThanConstraintContext(_localctx);
				enterOuterAlt(_localctx, 7);
				{
				setState(184);
				match(T__8);
				setState(185);
				((AttributeGreaterThanConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__9:
				_localctx = new AttributeGreaterThanEqualsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 8);
				{
				setState(186);
				match(T__9);
				setState(187);
				((AttributeGreaterThanEqualsConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__10:
				_localctx = new AttributeLessThanConstraintContext(_localctx);
				enterOuterAlt(_localctx, 9);
				{
				setState(188);
				match(T__10);
				setState(189);
				((AttributeLessThanConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__11:
				_localctx = new AttributeLessThanEqualsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 10);
				{
				setState(190);
				match(T__11);
				setState(191);
				((AttributeLessThanEqualsConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__12:
				_localctx = new AttributeBetweenConstraintContext(_localctx);
				enterOuterAlt(_localctx, 11);
				{
				setState(192);
				match(T__12);
				setState(193);
				((AttributeBetweenConstraintContext)_localctx).args = classifierWithBetweenValuesArgs();
				}
				break;
			case T__13:
				_localctx = new AttributeInSetConstraintContext(_localctx);
				enterOuterAlt(_localctx, 12);
				{
				setState(194);
				match(T__13);
				setState(195);
				((AttributeInSetConstraintContext)_localctx).args = classifierWithValueListArgs();
				}
				break;
			case T__14:
				_localctx = new AttributeContainsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 13);
				{
				setState(196);
				match(T__14);
				setState(197);
				((AttributeContainsConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__15:
				_localctx = new AttributeStartsWithConstraintContext(_localctx);
				enterOuterAlt(_localctx, 14);
				{
				setState(198);
				match(T__15);
				setState(199);
				((AttributeStartsWithConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__16:
				_localctx = new AttributeEndsWithConstraintContext(_localctx);
				enterOuterAlt(_localctx, 15);
				{
				setState(200);
				match(T__16);
				setState(201);
				((AttributeEndsWithConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__17:
				_localctx = new AttributeEqualsTrueConstraintContext(_localctx);
				enterOuterAlt(_localctx, 16);
				{
				setState(202);
				match(T__17);
				setState(203);
				((AttributeEqualsTrueConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case T__18:
				_localctx = new AttributeEqualsFalseConstraintContext(_localctx);
				enterOuterAlt(_localctx, 17);
				{
				setState(204);
				match(T__18);
				setState(205);
				((AttributeEqualsFalseConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case T__19:
				_localctx = new AttributeIsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 18);
				{
				setState(206);
				match(T__19);
				setState(207);
				((AttributeIsConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__20:
				_localctx = new AttributeIsNullConstraintContext(_localctx);
				enterOuterAlt(_localctx, 19);
				{
				setState(208);
				match(T__20);
				setState(209);
				((AttributeIsNullConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case T__21:
				_localctx = new AttributeIsNotNullConstraintContext(_localctx);
				enterOuterAlt(_localctx, 20);
				{
				setState(210);
				match(T__21);
				setState(211);
				((AttributeIsNotNullConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case T__22:
				_localctx = new AttributeInRangeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 21);
				{
				setState(212);
				match(T__22);
				setState(213);
				((AttributeInRangeConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__23:
				_localctx = new EntityPrimaryKeyInSetConstraintContext(_localctx);
				enterOuterAlt(_localctx, 22);
				{
				setState(214);
				match(T__23);
				setState(215);
				((EntityPrimaryKeyInSetConstraintContext)_localctx).args = valueListArgs();
				}
				break;
			case T__24:
				_localctx = new EntityLocaleEqualsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 23);
				{
				setState(216);
				match(T__24);
				setState(217);
				((EntityLocaleEqualsConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case T__25:
				_localctx = new PriceInCurrencyConstraintContext(_localctx);
				enterOuterAlt(_localctx, 24);
				{
				setState(218);
				match(T__25);
				setState(219);
				((PriceInCurrencyConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case T__26:
				_localctx = new PriceInPriceListsConstraintsContext(_localctx);
				enterOuterAlt(_localctx, 25);
				{
				setState(220);
				match(T__26);
				setState(223);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,4,_ctx) ) {
				case 1:
					{
					setState(221);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(222);
					((PriceInPriceListsConstraintsContext)_localctx).args = classifierListArgs();
					}
					break;
				}
				}
				break;
			case T__27:
				_localctx = new PriceValidNowConstraintContext(_localctx);
				enterOuterAlt(_localctx, 26);
				{
				setState(225);
				match(T__27);
				setState(226);
				emptyArgs();
				}
				break;
			case T__28:
				_localctx = new PriceValidInConstraintContext(_localctx);
				enterOuterAlt(_localctx, 27);
				{
				setState(227);
				match(T__28);
				setState(230);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,5,_ctx) ) {
				case 1:
					{
					setState(228);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(229);
					((PriceValidInConstraintContext)_localctx).args = valueArgs();
					}
					break;
				}
				}
				break;
			case T__29:
				_localctx = new PriceBetweenConstraintContext(_localctx);
				enterOuterAlt(_localctx, 28);
				{
				setState(232);
				match(T__29);
				setState(233);
				((PriceBetweenConstraintContext)_localctx).args = betweenValuesArgs();
				}
				break;
			case T__30:
				_localctx = new FacetHavingConstraintContext(_localctx);
				enterOuterAlt(_localctx, 29);
				{
				setState(234);
				match(T__30);
				setState(235);
				((FacetHavingConstraintContext)_localctx).args = classifierWithFilterConstraintArgs();
				}
				break;
			case T__31:
				_localctx = new ReferenceHavingConstraintContext(_localctx);
				enterOuterAlt(_localctx, 30);
				{
				setState(236);
				match(T__31);
				setState(237);
				((ReferenceHavingConstraintContext)_localctx).args = classifierWithFilterConstraintArgs();
				}
				break;
			case T__32:
				_localctx = new HierarchyWithinConstraintContext(_localctx);
				enterOuterAlt(_localctx, 31);
				{
				setState(238);
				match(T__32);
				setState(239);
				((HierarchyWithinConstraintContext)_localctx).args = hierarchyWithinConstraintArgs();
				}
				break;
			case T__33:
				_localctx = new HierarchyWithinSelfConstraintContext(_localctx);
				enterOuterAlt(_localctx, 32);
				{
				setState(240);
				match(T__33);
				setState(241);
				((HierarchyWithinSelfConstraintContext)_localctx).args = hierarchyWithinSelfConstraintArgs();
				}
				break;
			case T__34:
				_localctx = new HierarchyWithinRootConstraintContext(_localctx);
				enterOuterAlt(_localctx, 33);
				{
				setState(242);
				match(T__34);
				setState(243);
				((HierarchyWithinRootConstraintContext)_localctx).args = hierarchyWithinRootConstraintArgs();
				}
				break;
			case T__35:
				_localctx = new HierarchyWithinRootSelfConstraintContext(_localctx);
				enterOuterAlt(_localctx, 34);
				{
				setState(244);
				match(T__35);
				setState(245);
				((HierarchyWithinRootSelfConstraintContext)_localctx).args = hierarchyWithinRootSelfConstraintArgs();
				}
				break;
			case T__36:
				_localctx = new HierarchyDirectRelationConstraintContext(_localctx);
				enterOuterAlt(_localctx, 35);
				{
				setState(246);
				match(T__36);
				setState(247);
				emptyArgs();
				}
				break;
			case T__37:
				_localctx = new HierarchyExcludingRootConstraintContext(_localctx);
				enterOuterAlt(_localctx, 36);
				{
				setState(248);
				match(T__37);
				setState(249);
				emptyArgs();
				}
				break;
			case T__38:
				_localctx = new HierarchyExcludingConstraintContext(_localctx);
				enterOuterAlt(_localctx, 37);
				{
				setState(250);
				match(T__38);
				setState(251);
				((HierarchyExcludingConstraintContext)_localctx).args = filterConstraintListArgs();
				}
				break;
			case T__39:
				_localctx = new EntityHavingConstraintContext(_localctx);
				enterOuterAlt(_localctx, 38);
				{
				setState(252);
				match(T__39);
				setState(253);
				((EntityHavingConstraintContext)_localctx).args = filterConstraintArgs();
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

	public static class OrderConstraintContext extends ParserRuleContext {
		public OrderConstraintContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_orderConstraint; }

		public OrderConstraintContext() { }
		public void copyFrom(OrderConstraintContext ctx) {
			super.copyFrom(ctx);
		}
	}
	public static class RandomConstraintContext extends OrderConstraintContext {
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public RandomConstraintContext(OrderConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterRandomConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitRandomConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitRandomConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class PriceNaturalConstraintContext extends OrderConstraintContext {
		public ValueArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public ValueArgsContext valueArgs() {
			return getRuleContext(ValueArgsContext.class,0);
		}
		public PriceNaturalConstraintContext(OrderConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterPriceNaturalConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitPriceNaturalConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitPriceNaturalConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class EntityPropertyConstraintContext extends OrderConstraintContext {
		public OrderConstraintListArgsContext args;
		public OrderConstraintListArgsContext orderConstraintListArgs() {
			return getRuleContext(OrderConstraintListArgsContext.class,0);
		}
		public EntityPropertyConstraintContext(OrderConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterEntityPropertyConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitEntityPropertyConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitEntityPropertyConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class ReferencePropertyConstraintContext extends OrderConstraintContext {
		public ClassifierWithOrderConstraintListArgsContext args;
		public ClassifierWithOrderConstraintListArgsContext classifierWithOrderConstraintListArgs() {
			return getRuleContext(ClassifierWithOrderConstraintListArgsContext.class,0);
		}
		public ReferencePropertyConstraintContext(OrderConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferencePropertyConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferencePropertyConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferencePropertyConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class OrderByConstraintContext extends OrderConstraintContext {
		public OrderConstraintListArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public OrderConstraintListArgsContext orderConstraintListArgs() {
			return getRuleContext(OrderConstraintListArgsContext.class,0);
		}
		public OrderByConstraintContext(OrderConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterOrderByConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitOrderByConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitOrderByConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class AttributeNaturalConstraintContext extends OrderConstraintContext {
		public ClassifierWithOptionalValueArgsContext args;
		public ClassifierWithOptionalValueArgsContext classifierWithOptionalValueArgs() {
			return getRuleContext(ClassifierWithOptionalValueArgsContext.class,0);
		}
		public AttributeNaturalConstraintContext(OrderConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAttributeNaturalConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAttributeNaturalConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAttributeNaturalConstraint(this);
			else return visitor.visitChildren(this);
		}
	}

	public final OrderConstraintContext orderConstraint() throws RecognitionException {
		OrderConstraintContext _localctx = new OrderConstraintContext(_ctx, getState());
		enterRule(_localctx, 22, RULE_orderConstraint);
		try {
			setState(274);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case T__40:
				_localctx = new OrderByConstraintContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(256);
				match(T__40);
				setState(259);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,7,_ctx) ) {
				case 1:
					{
					setState(257);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(258);
					((OrderByConstraintContext)_localctx).args = orderConstraintListArgs();
					}
					break;
				}
				}
				break;
			case T__41:
				_localctx = new AttributeNaturalConstraintContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(261);
				match(T__41);
				setState(262);
				((AttributeNaturalConstraintContext)_localctx).args = classifierWithOptionalValueArgs();
				}
				break;
			case T__42:
				_localctx = new PriceNaturalConstraintContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(263);
				match(T__42);
				setState(266);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,8,_ctx) ) {
				case 1:
					{
					setState(264);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(265);
					((PriceNaturalConstraintContext)_localctx).args = valueArgs();
					}
					break;
				}
				}
				break;
			case T__43:
				_localctx = new RandomConstraintContext(_localctx);
				enterOuterAlt(_localctx, 4);
				{
				setState(268);
				match(T__43);
				setState(269);
				emptyArgs();
				}
				break;
			case T__44:
				_localctx = new ReferencePropertyConstraintContext(_localctx);
				enterOuterAlt(_localctx, 5);
				{
				setState(270);
				match(T__44);
				setState(271);
				((ReferencePropertyConstraintContext)_localctx).args = classifierWithOrderConstraintListArgs();
				}
				break;
			case T__45:
				_localctx = new EntityPropertyConstraintContext(_localctx);
				enterOuterAlt(_localctx, 6);
				{
				setState(272);
				match(T__45);
				setState(273);
				((EntityPropertyConstraintContext)_localctx).args = orderConstraintListArgs();
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

	public static class RequireConstraintContext extends ParserRuleContext {
		public RequireConstraintContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_requireConstraint; }

		public RequireConstraintContext() { }
		public void copyFrom(RequireConstraintContext ctx) {
			super.copyFrom(ctx);
		}
	}
	public static class MultipleRefsReferenceContentConstraintContext extends RequireConstraintContext {
		public MultipleRefsReferenceContentArgsContext args;
		public MultipleRefsReferenceContentArgsContext multipleRefsReferenceContentArgs() {
			return getRuleContext(MultipleRefsReferenceContentArgsContext.class,0);
		}
		public MultipleRefsReferenceContentConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterMultipleRefsReferenceContentConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitMultipleRefsReferenceContentConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitMultipleRefsReferenceContentConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class HierarchyNodeConstraintContext extends RequireConstraintContext {
		public FilterConstraintArgsContext args;
		public FilterConstraintArgsContext filterConstraintArgs() {
			return getRuleContext(FilterConstraintArgsContext.class,0);
		}
		public HierarchyNodeConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHierarchyNodeConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHierarchyNodeConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHierarchyNodeConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class FacetGroupsDisjunctionConstraintContext extends RequireConstraintContext {
		public ClassifierWithFilterConstraintArgsContext args;
		public ClassifierWithFilterConstraintArgsContext classifierWithFilterConstraintArgs() {
			return getRuleContext(ClassifierWithFilterConstraintArgsContext.class,0);
		}
		public FacetGroupsDisjunctionConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetGroupsDisjunctionConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetGroupsDisjunctionConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetGroupsDisjunctionConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class EmptyHierarchySiblingsConstraintContext extends RequireConstraintContext {
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public EmptyHierarchySiblingsConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterEmptyHierarchySiblingsConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitEmptyHierarchySiblingsConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitEmptyHierarchySiblingsConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class SingleRefWithFilterReferenceContentConstraintContext extends RequireConstraintContext {
		public SingleRefWithFilterReferenceContentArgsContext args;
		public SingleRefWithFilterReferenceContentArgsContext singleRefWithFilterReferenceContentArgs() {
			return getRuleContext(SingleRefWithFilterReferenceContentArgsContext.class,0);
		}
		public SingleRefWithFilterReferenceContentConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefWithFilterReferenceContentConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefWithFilterReferenceContentConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefWithFilterReferenceContentConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class EmptyHierarchyContentConstraintContext extends RequireConstraintContext {
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public EmptyHierarchyContentConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterEmptyHierarchyContentConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitEmptyHierarchyContentConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitEmptyHierarchyContentConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class QueryTelemetryConstraintContext extends RequireConstraintContext {
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public QueryTelemetryConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterQueryTelemetryConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitQueryTelemetryConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitQueryTelemetryConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class PriceContentAllConstraintContext extends RequireConstraintContext {
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public PriceContentAllConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterPriceContentAllConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitPriceContentAllConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitPriceContentAllConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class SingleRefWithFilterAndOrderReferenceContentConstraintContext extends RequireConstraintContext {
		public SingleRefWithFilterAndOrderReferenceContentArgsContext args;
		public SingleRefWithFilterAndOrderReferenceContentArgsContext singleRefWithFilterAndOrderReferenceContentArgs() {
			return getRuleContext(SingleRefWithFilterAndOrderReferenceContentArgsContext.class,0);
		}
		public SingleRefWithFilterAndOrderReferenceContentConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefWithFilterAndOrderReferenceContentConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefWithFilterAndOrderReferenceContentConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefWithFilterAndOrderReferenceContentConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class BasicHierarchySiblingsConstraintContext extends RequireConstraintContext {
		public RequireConstraintListArgsContext args;
		public RequireConstraintListArgsContext requireConstraintListArgs() {
			return getRuleContext(RequireConstraintListArgsContext.class,0);
		}
		public BasicHierarchySiblingsConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterBasicHierarchySiblingsConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitBasicHierarchySiblingsConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitBasicHierarchySiblingsConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class PageConstraintContext extends RequireConstraintContext {
		public PageConstraintArgsContext args;
		public PageConstraintArgsContext pageConstraintArgs() {
			return getRuleContext(PageConstraintArgsContext.class,0);
		}
		public PageConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterPageConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitPageConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitPageConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class FullHierarchyOfReferenceConstraintContext extends RequireConstraintContext {
		public FullHierarchyOfReferenceArgsContext args;
		public FullHierarchyOfReferenceArgsContext fullHierarchyOfReferenceArgs() {
			return getRuleContext(FullHierarchyOfReferenceArgsContext.class,0);
		}
		public FullHierarchyOfReferenceConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFullHierarchyOfReferenceConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFullHierarchyOfReferenceConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFullHierarchyOfReferenceConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class StripConstraintContext extends RequireConstraintContext {
		public StripConstraintArgsContext args;
		public StripConstraintArgsContext stripConstraintArgs() {
			return getRuleContext(StripConstraintArgsContext.class,0);
		}
		public StripConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterStripConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitStripConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitStripConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class HierarchyFromNodeConstraintContext extends RequireConstraintContext {
		public HierarchyFromNodeArgsContext args;
		public HierarchyFromNodeArgsContext hierarchyFromNodeArgs() {
			return getRuleContext(HierarchyFromNodeArgsContext.class,0);
		}
		public HierarchyFromNodeConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHierarchyFromNodeConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHierarchyFromNodeConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHierarchyFromNodeConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class FacetSummaryOfReferenceConstraintContext extends RequireConstraintContext {
		public FacetSummaryOfReferenceArgsContext args;
		public FacetSummaryOfReferenceArgsContext facetSummaryOfReferenceArgs() {
			return getRuleContext(FacetSummaryOfReferenceArgsContext.class,0);
		}
		public FacetSummaryOfReferenceConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetSummaryOfReferenceConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetSummaryOfReferenceConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetSummaryOfReferenceConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class SingleRequireHierarchyContentConstraintContext extends RequireConstraintContext {
		public SingleRequireHierarchyContentArgsContext args;
		public SingleRequireHierarchyContentArgsContext singleRequireHierarchyContentArgs() {
			return getRuleContext(SingleRequireHierarchyContentArgsContext.class,0);
		}
		public SingleRequireHierarchyContentConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRequireHierarchyContentConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRequireHierarchyContentConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRequireHierarchyContentConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class EntityFetchConstraintContext extends RequireConstraintContext {
		public RequireConstraintListArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public RequireConstraintListArgsContext requireConstraintListArgs() {
			return getRuleContext(RequireConstraintListArgsContext.class,0);
		}
		public EntityFetchConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterEntityFetchConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitEntityFetchConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitEntityFetchConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class PriceContentConstraintContext extends RequireConstraintContext {
		public ValueListArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public ValueListArgsContext valueListArgs() {
			return getRuleContext(ValueListArgsContext.class,0);
		}
		public PriceContentConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterPriceContentConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitPriceContentConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitPriceContentConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class EntityGroupFetchConstraintContext extends RequireConstraintContext {
		public RequireConstraintListArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public RequireConstraintListArgsContext requireConstraintListArgs() {
			return getRuleContext(RequireConstraintListArgsContext.class,0);
		}
		public EntityGroupFetchConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterEntityGroupFetchConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitEntityGroupFetchConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitEntityGroupFetchConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class AllRefsReferenceContentConstraintContext extends RequireConstraintContext {
		public AllRefsReferenceContentArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public AllRefsReferenceContentArgsContext allRefsReferenceContentArgs() {
			return getRuleContext(AllRefsReferenceContentArgsContext.class,0);
		}
		public AllRefsReferenceContentConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAllRefsReferenceContentConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAllRefsReferenceContentConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAllRefsReferenceContentConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class FullHierarchySiblingsConstraintContext extends RequireConstraintContext {
		public HierarchyRequireConstraintArgsContext args;
		public HierarchyRequireConstraintArgsContext hierarchyRequireConstraintArgs() {
			return getRuleContext(HierarchyRequireConstraintArgsContext.class,0);
		}
		public FullHierarchySiblingsConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFullHierarchySiblingsConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFullHierarchySiblingsConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFullHierarchySiblingsConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class HierarchyStopAtConstraintContext extends RequireConstraintContext {
		public RequireConstraintArgsContext args;
		public RequireConstraintArgsContext requireConstraintArgs() {
			return getRuleContext(RequireConstraintArgsContext.class,0);
		}
		public HierarchyStopAtConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHierarchyStopAtConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHierarchyStopAtConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHierarchyStopAtConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class FullHierarchyOfSelfConstraintContext extends RequireConstraintContext {
		public FullHierarchyOfSelfArgsContext args;
		public FullHierarchyOfSelfArgsContext fullHierarchyOfSelfArgs() {
			return getRuleContext(FullHierarchyOfSelfArgsContext.class,0);
		}
		public FullHierarchyOfSelfConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFullHierarchyOfSelfConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFullHierarchyOfSelfConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFullHierarchyOfSelfConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class RequireContainerConstraintContext extends RequireConstraintContext {
		public RequireConstraintListArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public RequireConstraintListArgsContext requireConstraintListArgs() {
			return getRuleContext(RequireConstraintListArgsContext.class,0);
		}
		public RequireContainerConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterRequireContainerConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitRequireContainerConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitRequireContainerConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class AssociatedDataContentConstraintContext extends RequireConstraintContext {
		public ClassifierListArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public ClassifierListArgsContext classifierListArgs() {
			return getRuleContext(ClassifierListArgsContext.class,0);
		}
		public AssociatedDataContentConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAssociatedDataContentConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAssociatedDataContentConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAssociatedDataContentConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class PriceTypeConstraintContext extends RequireConstraintContext {
		public ValueArgsContext args;
		public ValueArgsContext valueArgs() {
			return getRuleContext(ValueArgsContext.class,0);
		}
		public PriceTypeConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterPriceTypeConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitPriceTypeConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitPriceTypeConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class HierarchyChildrenConstraintContext extends RequireConstraintContext {
		public HierarchyRequireConstraintArgsContext args;
		public HierarchyRequireConstraintArgsContext hierarchyRequireConstraintArgs() {
			return getRuleContext(HierarchyRequireConstraintArgsContext.class,0);
		}
		public HierarchyChildrenConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHierarchyChildrenConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHierarchyChildrenConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHierarchyChildrenConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class BasicHierarchyOfReferenceConstraintContext extends RequireConstraintContext {
		public BasicHierarchyOfReferenceArgsContext args;
		public BasicHierarchyOfReferenceArgsContext basicHierarchyOfReferenceArgs() {
			return getRuleContext(BasicHierarchyOfReferenceArgsContext.class,0);
		}
		public BasicHierarchyOfReferenceConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterBasicHierarchyOfReferenceConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitBasicHierarchyOfReferenceConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitBasicHierarchyOfReferenceConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class FullHierarchyStatisticsConstraintContext extends RequireConstraintContext {
		public FullHierarchyStatisticsArgsContext args;
		public FullHierarchyStatisticsArgsContext fullHierarchyStatisticsArgs() {
			return getRuleContext(FullHierarchyStatisticsArgsContext.class,0);
		}
		public FullHierarchyStatisticsConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFullHierarchyStatisticsConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFullHierarchyStatisticsConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFullHierarchyStatisticsConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class AllRequiresHierarchyContentConstraintContext extends RequireConstraintContext {
		public AllRequiresHierarchyContentArgsContext args;
		public AllRequiresHierarchyContentArgsContext allRequiresHierarchyContentArgs() {
			return getRuleContext(AllRequiresHierarchyContentArgsContext.class,0);
		}
		public AllRequiresHierarchyContentConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAllRequiresHierarchyContentConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAllRequiresHierarchyContentConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAllRequiresHierarchyContentConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class PriceHistogramConstraintContext extends RequireConstraintContext {
		public ValueArgsContext args;
		public ValueArgsContext valueArgs() {
			return getRuleContext(ValueArgsContext.class,0);
		}
		public PriceHistogramConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterPriceHistogramConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitPriceHistogramConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitPriceHistogramConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class HierarchyLevelConstraintContext extends RequireConstraintContext {
		public ValueArgsContext args;
		public ValueArgsContext valueArgs() {
			return getRuleContext(ValueArgsContext.class,0);
		}
		public HierarchyLevelConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHierarchyLevelConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHierarchyLevelConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHierarchyLevelConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class DataInLocalesConstraintContext extends RequireConstraintContext {
		public ValueListArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public ValueListArgsContext valueListArgs() {
			return getRuleContext(ValueListArgsContext.class,0);
		}
		public DataInLocalesConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterDataInLocalesConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitDataInLocalesConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitDataInLocalesConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class BasicHierarchyOfSelfConstraintContext extends RequireConstraintContext {
		public RequireConstraintListArgsContext args;
		public RequireConstraintListArgsContext requireConstraintListArgs() {
			return getRuleContext(RequireConstraintListArgsContext.class,0);
		}
		public BasicHierarchyOfSelfConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterBasicHierarchyOfSelfConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitBasicHierarchyOfSelfConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitBasicHierarchyOfSelfConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class HierarchyDistanceConstraintContext extends RequireConstraintContext {
		public ValueArgsContext args;
		public ValueArgsContext valueArgs() {
			return getRuleContext(ValueArgsContext.class,0);
		}
		public HierarchyDistanceConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHierarchyDistanceConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHierarchyDistanceConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHierarchyDistanceConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class FacetGroupsConjunctionConstraintContext extends RequireConstraintContext {
		public ClassifierWithFilterConstraintArgsContext args;
		public ClassifierWithFilterConstraintArgsContext classifierWithFilterConstraintArgs() {
			return getRuleContext(ClassifierWithFilterConstraintArgsContext.class,0);
		}
		public FacetGroupsConjunctionConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetGroupsConjunctionConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetGroupsConjunctionConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetGroupsConjunctionConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class SingleRefWithOrderReferenceContentConstraintContext extends RequireConstraintContext {
		public SingleRefWithOrderReferenceContentArgsContext args;
		public SingleRefWithOrderReferenceContentArgsContext singleRefWithOrderReferenceContentArgs() {
			return getRuleContext(SingleRefWithOrderReferenceContentArgsContext.class,0);
		}
		public SingleRefWithOrderReferenceContentConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefWithOrderReferenceContentConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefWithOrderReferenceContentConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefWithOrderReferenceContentConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class AttributeContentConstraintContext extends RequireConstraintContext {
		public ClassifierListArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public ClassifierListArgsContext classifierListArgs() {
			return getRuleContext(ClassifierListArgsContext.class,0);
		}
		public AttributeContentConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAttributeContentConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAttributeContentConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAttributeContentConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class AttributeHistogramConstraintContext extends RequireConstraintContext {
		public ValueWithClassifierListArgsContext args;
		public ValueWithClassifierListArgsContext valueWithClassifierListArgs() {
			return getRuleContext(ValueWithClassifierListArgsContext.class,0);
		}
		public AttributeHistogramConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAttributeHistogramConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAttributeHistogramConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAttributeHistogramConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class EmptyHierarchyStatisticsConstraintContext extends RequireConstraintContext {
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public EmptyHierarchyStatisticsConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterEmptyHierarchyStatisticsConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitEmptyHierarchyStatisticsConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitEmptyHierarchyStatisticsConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class HierarchyFromRootConstraintContext extends RequireConstraintContext {
		public HierarchyRequireConstraintArgsContext args;
		public HierarchyRequireConstraintArgsContext hierarchyRequireConstraintArgs() {
			return getRuleContext(HierarchyRequireConstraintArgsContext.class,0);
		}
		public HierarchyFromRootConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHierarchyFromRootConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHierarchyFromRootConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHierarchyFromRootConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class HierarchyParentsConstraintContext extends RequireConstraintContext {
		public HierarchyRequireConstraintArgsContext args;
		public HierarchyRequireConstraintArgsContext hierarchyRequireConstraintArgs() {
			return getRuleContext(HierarchyRequireConstraintArgsContext.class,0);
		}
		public HierarchyParentsConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHierarchyParentsConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHierarchyParentsConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHierarchyParentsConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class SingleRefReferenceContentConstraintContext extends RequireConstraintContext {
		public SingleRefReferenceContentArgsContext args;
		public SingleRefReferenceContentArgsContext singleRefReferenceContentArgs() {
			return getRuleContext(SingleRefReferenceContentArgsContext.class,0);
		}
		public SingleRefReferenceContentConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContentConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContentConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContentConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class FacetSummaryConstraintContext extends RequireConstraintContext {
		public FacetSummaryArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public FacetSummaryArgsContext facetSummaryArgs() {
			return getRuleContext(FacetSummaryArgsContext.class,0);
		}
		public FacetSummaryConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetSummaryConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetSummaryConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetSummaryConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class FacetGroupsNegationConstraintContext extends RequireConstraintContext {
		public ClassifierWithFilterConstraintArgsContext args;
		public ClassifierWithFilterConstraintArgsContext classifierWithFilterConstraintArgs() {
			return getRuleContext(ClassifierWithFilterConstraintArgsContext.class,0);
		}
		public FacetGroupsNegationConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetGroupsNegationConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetGroupsNegationConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetGroupsNegationConstraint(this);
			else return visitor.visitChildren(this);
		}
	}

	public final RequireConstraintContext requireConstraint() throws RecognitionException {
		RequireConstraintContext _localctx = new RequireConstraintContext(_ctx, getState());
		enterRule(_localctx, 24, RULE_requireConstraint);
		try {
			setState(393);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,19,_ctx) ) {
			case 1:
				_localctx = new RequireContainerConstraintContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(276);
				match(T__46);
				setState(279);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,10,_ctx) ) {
				case 1:
					{
					setState(277);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(278);
					((RequireContainerConstraintContext)_localctx).args = requireConstraintListArgs();
					}
					break;
				}
				}
				break;
			case 2:
				_localctx = new PageConstraintContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(281);
				match(T__47);
				setState(282);
				((PageConstraintContext)_localctx).args = pageConstraintArgs();
				}
				break;
			case 3:
				_localctx = new StripConstraintContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(283);
				match(T__48);
				setState(284);
				((StripConstraintContext)_localctx).args = stripConstraintArgs();
				}
				break;
			case 4:
				_localctx = new EntityFetchConstraintContext(_localctx);
				enterOuterAlt(_localctx, 4);
				{
				setState(285);
				match(T__49);
				setState(288);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,11,_ctx) ) {
				case 1:
					{
					setState(286);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(287);
					((EntityFetchConstraintContext)_localctx).args = requireConstraintListArgs();
					}
					break;
				}
				}
				break;
			case 5:
				_localctx = new EntityGroupFetchConstraintContext(_localctx);
				enterOuterAlt(_localctx, 5);
				{
				setState(290);
				match(T__50);
				setState(293);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,12,_ctx) ) {
				case 1:
					{
					setState(291);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(292);
					((EntityGroupFetchConstraintContext)_localctx).args = requireConstraintListArgs();
					}
					break;
				}
				}
				break;
			case 6:
				_localctx = new AttributeContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 6);
				{
				setState(295);
				match(T__51);
				setState(298);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,13,_ctx) ) {
				case 1:
					{
					setState(296);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(297);
					((AttributeContentConstraintContext)_localctx).args = classifierListArgs();
					}
					break;
				}
				}
				break;
			case 7:
				_localctx = new PriceContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 7);
				{
				setState(300);
				match(T__52);
				setState(303);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,14,_ctx) ) {
				case 1:
					{
					setState(301);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(302);
					((PriceContentConstraintContext)_localctx).args = valueListArgs();
					}
					break;
				}
				}
				break;
			case 8:
				_localctx = new PriceContentAllConstraintContext(_localctx);
				enterOuterAlt(_localctx, 8);
				{
				setState(305);
				match(T__53);
				setState(306);
				emptyArgs();
				}
				break;
			case 9:
				_localctx = new AssociatedDataContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 9);
				{
				setState(307);
				match(T__54);
				setState(310);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,15,_ctx) ) {
				case 1:
					{
					setState(308);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(309);
					((AssociatedDataContentConstraintContext)_localctx).args = classifierListArgs();
					}
					break;
				}
				}
				break;
			case 10:
				_localctx = new AllRefsReferenceContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 10);
				{
				setState(312);
				match(T__55);
				setState(315);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,16,_ctx) ) {
				case 1:
					{
					setState(313);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(314);
					((AllRefsReferenceContentConstraintContext)_localctx).args = allRefsReferenceContentArgs();
					}
					break;
				}
				}
				break;
			case 11:
				_localctx = new MultipleRefsReferenceContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 11);
				{
				setState(317);
				match(T__55);
				setState(318);
				((MultipleRefsReferenceContentConstraintContext)_localctx).args = multipleRefsReferenceContentArgs();
				}
				break;
			case 12:
				_localctx = new SingleRefReferenceContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 12);
				{
				setState(319);
				match(T__55);
				setState(320);
				((SingleRefReferenceContentConstraintContext)_localctx).args = singleRefReferenceContentArgs();
				}
				break;
			case 13:
				_localctx = new SingleRefWithFilterReferenceContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 13);
				{
				setState(321);
				match(T__55);
				setState(322);
				((SingleRefWithFilterReferenceContentConstraintContext)_localctx).args = singleRefWithFilterReferenceContentArgs();
				}
				break;
			case 14:
				_localctx = new SingleRefWithOrderReferenceContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 14);
				{
				setState(323);
				match(T__55);
				setState(324);
				((SingleRefWithOrderReferenceContentConstraintContext)_localctx).args = singleRefWithOrderReferenceContentArgs();
				}
				break;
			case 15:
				_localctx = new SingleRefWithFilterAndOrderReferenceContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 15);
				{
				setState(325);
				match(T__55);
				setState(326);
				((SingleRefWithFilterAndOrderReferenceContentConstraintContext)_localctx).args = singleRefWithFilterAndOrderReferenceContentArgs();
				}
				break;
			case 16:
				_localctx = new EmptyHierarchyContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 16);
				{
				setState(327);
				match(T__56);
				setState(328);
				emptyArgs();
				}
				break;
			case 17:
				_localctx = new SingleRequireHierarchyContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 17);
				{
				setState(329);
				match(T__56);
				setState(330);
				((SingleRequireHierarchyContentConstraintContext)_localctx).args = singleRequireHierarchyContentArgs();
				}
				break;
			case 18:
				_localctx = new AllRequiresHierarchyContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 18);
				{
				setState(331);
				match(T__56);
				setState(332);
				((AllRequiresHierarchyContentConstraintContext)_localctx).args = allRequiresHierarchyContentArgs();
				}
				break;
			case 19:
				_localctx = new PriceTypeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 19);
				{
				setState(333);
				match(T__57);
				setState(334);
				((PriceTypeConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case 20:
				_localctx = new DataInLocalesConstraintContext(_localctx);
				enterOuterAlt(_localctx, 20);
				{
				setState(335);
				match(T__58);
				setState(338);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,17,_ctx) ) {
				case 1:
					{
					setState(336);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(337);
					((DataInLocalesConstraintContext)_localctx).args = valueListArgs();
					}
					break;
				}
				}
				break;
			case 21:
				_localctx = new FacetSummaryConstraintContext(_localctx);
				enterOuterAlt(_localctx, 21);
				{
				setState(340);
				match(T__59);
				setState(343);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,18,_ctx) ) {
				case 1:
					{
					setState(341);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(342);
					((FacetSummaryConstraintContext)_localctx).args = facetSummaryArgs();
					}
					break;
				}
				}
				break;
			case 22:
				_localctx = new FacetSummaryOfReferenceConstraintContext(_localctx);
				enterOuterAlt(_localctx, 22);
				{
				setState(345);
				match(T__60);
				setState(346);
				((FacetSummaryOfReferenceConstraintContext)_localctx).args = facetSummaryOfReferenceArgs();
				}
				break;
			case 23:
				_localctx = new FacetGroupsConjunctionConstraintContext(_localctx);
				enterOuterAlt(_localctx, 23);
				{
				setState(347);
				match(T__61);
				setState(348);
				((FacetGroupsConjunctionConstraintContext)_localctx).args = classifierWithFilterConstraintArgs();
				}
				break;
			case 24:
				_localctx = new FacetGroupsDisjunctionConstraintContext(_localctx);
				enterOuterAlt(_localctx, 24);
				{
				setState(349);
				match(T__62);
				setState(350);
				((FacetGroupsDisjunctionConstraintContext)_localctx).args = classifierWithFilterConstraintArgs();
				}
				break;
			case 25:
				_localctx = new FacetGroupsNegationConstraintContext(_localctx);
				enterOuterAlt(_localctx, 25);
				{
				setState(351);
				match(T__63);
				setState(352);
				((FacetGroupsNegationConstraintContext)_localctx).args = classifierWithFilterConstraintArgs();
				}
				break;
			case 26:
				_localctx = new AttributeHistogramConstraintContext(_localctx);
				enterOuterAlt(_localctx, 26);
				{
				setState(353);
				match(T__64);
				setState(354);
				((AttributeHistogramConstraintContext)_localctx).args = valueWithClassifierListArgs();
				}
				break;
			case 27:
				_localctx = new PriceHistogramConstraintContext(_localctx);
				enterOuterAlt(_localctx, 27);
				{
				setState(355);
				match(T__65);
				setState(356);
				((PriceHistogramConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case 28:
				_localctx = new HierarchyDistanceConstraintContext(_localctx);
				enterOuterAlt(_localctx, 28);
				{
				setState(357);
				match(T__66);
				setState(358);
				((HierarchyDistanceConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case 29:
				_localctx = new HierarchyLevelConstraintContext(_localctx);
				enterOuterAlt(_localctx, 29);
				{
				setState(359);
				match(T__67);
				setState(360);
				((HierarchyLevelConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case 30:
				_localctx = new HierarchyNodeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 30);
				{
				setState(361);
				match(T__68);
				setState(362);
				((HierarchyNodeConstraintContext)_localctx).args = filterConstraintArgs();
				}
				break;
			case 31:
				_localctx = new HierarchyStopAtConstraintContext(_localctx);
				enterOuterAlt(_localctx, 31);
				{
				setState(363);
				match(T__69);
				setState(364);
				((HierarchyStopAtConstraintContext)_localctx).args = requireConstraintArgs();
				}
				break;
			case 32:
				_localctx = new EmptyHierarchyStatisticsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 32);
				{
				setState(365);
				match(T__70);
				setState(366);
				emptyArgs();
				}
				break;
			case 33:
				_localctx = new FullHierarchyStatisticsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 33);
				{
				setState(367);
				match(T__70);
				setState(368);
				((FullHierarchyStatisticsConstraintContext)_localctx).args = fullHierarchyStatisticsArgs();
				}
				break;
			case 34:
				_localctx = new HierarchyFromRootConstraintContext(_localctx);
				enterOuterAlt(_localctx, 34);
				{
				setState(369);
				match(T__71);
				setState(370);
				((HierarchyFromRootConstraintContext)_localctx).args = hierarchyRequireConstraintArgs();
				}
				break;
			case 35:
				_localctx = new HierarchyFromNodeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 35);
				{
				setState(371);
				match(T__72);
				setState(372);
				((HierarchyFromNodeConstraintContext)_localctx).args = hierarchyFromNodeArgs();
				}
				break;
			case 36:
				_localctx = new HierarchyChildrenConstraintContext(_localctx);
				enterOuterAlt(_localctx, 36);
				{
				setState(373);
				match(T__73);
				setState(374);
				((HierarchyChildrenConstraintContext)_localctx).args = hierarchyRequireConstraintArgs();
				}
				break;
			case 37:
				_localctx = new EmptyHierarchySiblingsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 37);
				{
				setState(375);
				match(T__74);
				setState(376);
				emptyArgs();
				}
				break;
			case 38:
				_localctx = new BasicHierarchySiblingsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 38);
				{
				setState(377);
				match(T__74);
				setState(378);
				((BasicHierarchySiblingsConstraintContext)_localctx).args = requireConstraintListArgs();
				}
				break;
			case 39:
				_localctx = new FullHierarchySiblingsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 39);
				{
				setState(379);
				match(T__74);
				setState(380);
				((FullHierarchySiblingsConstraintContext)_localctx).args = hierarchyRequireConstraintArgs();
				}
				break;
			case 40:
				_localctx = new HierarchyParentsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 40);
				{
				setState(381);
				match(T__75);
				setState(382);
				((HierarchyParentsConstraintContext)_localctx).args = hierarchyRequireConstraintArgs();
				}
				break;
			case 41:
				_localctx = new BasicHierarchyOfSelfConstraintContext(_localctx);
				enterOuterAlt(_localctx, 41);
				{
				setState(383);
				match(T__76);
				setState(384);
				((BasicHierarchyOfSelfConstraintContext)_localctx).args = requireConstraintListArgs();
				}
				break;
			case 42:
				_localctx = new FullHierarchyOfSelfConstraintContext(_localctx);
				enterOuterAlt(_localctx, 42);
				{
				setState(385);
				match(T__76);
				setState(386);
				((FullHierarchyOfSelfConstraintContext)_localctx).args = fullHierarchyOfSelfArgs();
				}
				break;
			case 43:
				_localctx = new BasicHierarchyOfReferenceConstraintContext(_localctx);
				enterOuterAlt(_localctx, 43);
				{
				setState(387);
				match(T__77);
				setState(388);
				((BasicHierarchyOfReferenceConstraintContext)_localctx).args = basicHierarchyOfReferenceArgs();
				}
				break;
			case 44:
				_localctx = new FullHierarchyOfReferenceConstraintContext(_localctx);
				enterOuterAlt(_localctx, 44);
				{
				setState(389);
				match(T__77);
				setState(390);
				((FullHierarchyOfReferenceConstraintContext)_localctx).args = fullHierarchyOfReferenceArgs();
				}
				break;
			case 45:
				_localctx = new QueryTelemetryConstraintContext(_localctx);
				enterOuterAlt(_localctx, 45);
				{
				setState(391);
				match(T__78);
				setState(392);
				emptyArgs();
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

	public static class HeadConstraintListContext extends ParserRuleContext {
		public HeadConstraintContext headConstraint;
		public List<HeadConstraintContext> constraints = new ArrayList<HeadConstraintContext>();
		public List<HeadConstraintContext> headConstraint() {
			return getRuleContexts(HeadConstraintContext.class);
		}
		public HeadConstraintContext headConstraint(int i) {
			return getRuleContext(HeadConstraintContext.class,i);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public HeadConstraintListContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_headConstraintList; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHeadConstraintList(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHeadConstraintList(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHeadConstraintList(this);
			else return visitor.visitChildren(this);
		}
	}

	public final HeadConstraintListContext headConstraintList() throws RecognitionException {
		HeadConstraintListContext _localctx = new HeadConstraintListContext(_ctx, getState());
		enterRule(_localctx, 26, RULE_headConstraintList);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(395);
			((HeadConstraintListContext)_localctx).headConstraint = headConstraint();
			((HeadConstraintListContext)_localctx).constraints.add(((HeadConstraintListContext)_localctx).headConstraint);
			setState(400);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(396);
				match(ARGS_DELIMITER);
				setState(397);
				((HeadConstraintListContext)_localctx).headConstraint = headConstraint();
				((HeadConstraintListContext)_localctx).constraints.add(((HeadConstraintListContext)_localctx).headConstraint);
				}
				}
				setState(402);
				_errHandler.sync(this);
				_la = _input.LA(1);
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

	public static class FilterConstraintListContext extends ParserRuleContext {
		public FilterConstraintContext filterConstraint;
		public List<FilterConstraintContext> constraints = new ArrayList<FilterConstraintContext>();
		public List<FilterConstraintContext> filterConstraint() {
			return getRuleContexts(FilterConstraintContext.class);
		}
		public FilterConstraintContext filterConstraint(int i) {
			return getRuleContext(FilterConstraintContext.class,i);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public FilterConstraintListContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_filterConstraintList; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFilterConstraintList(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFilterConstraintList(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFilterConstraintList(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FilterConstraintListContext filterConstraintList() throws RecognitionException {
		FilterConstraintListContext _localctx = new FilterConstraintListContext(_ctx, getState());
		enterRule(_localctx, 28, RULE_filterConstraintList);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(403);
			((FilterConstraintListContext)_localctx).filterConstraint = filterConstraint();
			((FilterConstraintListContext)_localctx).constraints.add(((FilterConstraintListContext)_localctx).filterConstraint);
			setState(408);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(404);
				match(ARGS_DELIMITER);
				setState(405);
				((FilterConstraintListContext)_localctx).filterConstraint = filterConstraint();
				((FilterConstraintListContext)_localctx).constraints.add(((FilterConstraintListContext)_localctx).filterConstraint);
				}
				}
				setState(410);
				_errHandler.sync(this);
				_la = _input.LA(1);
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

	public static class OrderConstraintListContext extends ParserRuleContext {
		public OrderConstraintContext orderConstraint;
		public List<OrderConstraintContext> constraints = new ArrayList<OrderConstraintContext>();
		public List<OrderConstraintContext> orderConstraint() {
			return getRuleContexts(OrderConstraintContext.class);
		}
		public OrderConstraintContext orderConstraint(int i) {
			return getRuleContext(OrderConstraintContext.class,i);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public OrderConstraintListContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_orderConstraintList; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterOrderConstraintList(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitOrderConstraintList(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitOrderConstraintList(this);
			else return visitor.visitChildren(this);
		}
	}

	public final OrderConstraintListContext orderConstraintList() throws RecognitionException {
		OrderConstraintListContext _localctx = new OrderConstraintListContext(_ctx, getState());
		enterRule(_localctx, 30, RULE_orderConstraintList);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(411);
			((OrderConstraintListContext)_localctx).orderConstraint = orderConstraint();
			((OrderConstraintListContext)_localctx).constraints.add(((OrderConstraintListContext)_localctx).orderConstraint);
			setState(416);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(412);
				match(ARGS_DELIMITER);
				setState(413);
				((OrderConstraintListContext)_localctx).orderConstraint = orderConstraint();
				((OrderConstraintListContext)_localctx).constraints.add(((OrderConstraintListContext)_localctx).orderConstraint);
				}
				}
				setState(418);
				_errHandler.sync(this);
				_la = _input.LA(1);
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

	public static class RequireConstraintListContext extends ParserRuleContext {
		public RequireConstraintContext requireConstraint;
		public List<RequireConstraintContext> constraints = new ArrayList<RequireConstraintContext>();
		public List<RequireConstraintContext> requireConstraint() {
			return getRuleContexts(RequireConstraintContext.class);
		}
		public RequireConstraintContext requireConstraint(int i) {
			return getRuleContext(RequireConstraintContext.class,i);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public RequireConstraintListContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_requireConstraintList; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterRequireConstraintList(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitRequireConstraintList(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitRequireConstraintList(this);
			else return visitor.visitChildren(this);
		}
	}

	public final RequireConstraintListContext requireConstraintList() throws RecognitionException {
		RequireConstraintListContext _localctx = new RequireConstraintListContext(_ctx, getState());
		enterRule(_localctx, 32, RULE_requireConstraintList);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(419);
			((RequireConstraintListContext)_localctx).requireConstraint = requireConstraint();
			((RequireConstraintListContext)_localctx).constraints.add(((RequireConstraintListContext)_localctx).requireConstraint);
			setState(424);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(420);
				match(ARGS_DELIMITER);
				setState(421);
				((RequireConstraintListContext)_localctx).requireConstraint = requireConstraint();
				((RequireConstraintListContext)_localctx).constraints.add(((RequireConstraintListContext)_localctx).requireConstraint);
				}
				}
				setState(426);
				_errHandler.sync(this);
				_la = _input.LA(1);
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

	public static class ConstraintListArgsContext extends ParserRuleContext {
		public ConstraintContext constraint;
		public List<ConstraintContext> constraints = new ArrayList<ConstraintContext>();
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public List<ConstraintContext> constraint() {
			return getRuleContexts(ConstraintContext.class);
		}
		public ConstraintContext constraint(int i) {
			return getRuleContext(ConstraintContext.class,i);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ConstraintListArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_constraintListArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterConstraintListArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitConstraintListArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitConstraintListArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ConstraintListArgsContext constraintListArgs() throws RecognitionException {
		ConstraintListArgsContext _localctx = new ConstraintListArgsContext(_ctx, getState());
		enterRule(_localctx, 34, RULE_constraintListArgs);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(427);
			match(ARGS_OPENING);
			setState(428);
			((ConstraintListArgsContext)_localctx).constraint = constraint();
			((ConstraintListArgsContext)_localctx).constraints.add(((ConstraintListArgsContext)_localctx).constraint);
			setState(433);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(429);
				match(ARGS_DELIMITER);
				setState(430);
				((ConstraintListArgsContext)_localctx).constraint = constraint();
				((ConstraintListArgsContext)_localctx).constraints.add(((ConstraintListArgsContext)_localctx).constraint);
				}
				}
				setState(435);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			setState(436);
			match(ARGS_CLOSING);
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

	public static class EmptyArgsContext extends ParserRuleContext {
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public EmptyArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_emptyArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterEmptyArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitEmptyArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitEmptyArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final EmptyArgsContext emptyArgs() throws RecognitionException {
		EmptyArgsContext _localctx = new EmptyArgsContext(_ctx, getState());
		enterRule(_localctx, 36, RULE_emptyArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(438);
			match(ARGS_OPENING);
			setState(439);
			match(ARGS_CLOSING);
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

	public static class FilterConstraintListArgsContext extends ParserRuleContext {
		public FilterConstraintContext filterConstraint;
		public List<FilterConstraintContext> constraints = new ArrayList<FilterConstraintContext>();
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public List<FilterConstraintContext> filterConstraint() {
			return getRuleContexts(FilterConstraintContext.class);
		}
		public FilterConstraintContext filterConstraint(int i) {
			return getRuleContext(FilterConstraintContext.class,i);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public FilterConstraintListArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_filterConstraintListArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFilterConstraintListArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFilterConstraintListArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFilterConstraintListArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FilterConstraintListArgsContext filterConstraintListArgs() throws RecognitionException {
		FilterConstraintListArgsContext _localctx = new FilterConstraintListArgsContext(_ctx, getState());
		enterRule(_localctx, 38, RULE_filterConstraintListArgs);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(441);
			match(ARGS_OPENING);
			setState(442);
			((FilterConstraintListArgsContext)_localctx).filterConstraint = filterConstraint();
			((FilterConstraintListArgsContext)_localctx).constraints.add(((FilterConstraintListArgsContext)_localctx).filterConstraint);
			setState(447);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(443);
				match(ARGS_DELIMITER);
				setState(444);
				((FilterConstraintListArgsContext)_localctx).filterConstraint = filterConstraint();
				((FilterConstraintListArgsContext)_localctx).constraints.add(((FilterConstraintListArgsContext)_localctx).filterConstraint);
				}
				}
				setState(449);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			setState(450);
			match(ARGS_CLOSING);
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

	public static class FilterConstraintArgsContext extends ParserRuleContext {
		public FilterConstraintContext filter;
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public FilterConstraintContext filterConstraint() {
			return getRuleContext(FilterConstraintContext.class,0);
		}
		public FilterConstraintArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_filterConstraintArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFilterConstraintArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFilterConstraintArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFilterConstraintArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FilterConstraintArgsContext filterConstraintArgs() throws RecognitionException {
		FilterConstraintArgsContext _localctx = new FilterConstraintArgsContext(_ctx, getState());
		enterRule(_localctx, 40, RULE_filterConstraintArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(452);
			match(ARGS_OPENING);
			setState(453);
			((FilterConstraintArgsContext)_localctx).filter = filterConstraint();
			setState(454);
			match(ARGS_CLOSING);
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

	public static class OrderConstraintListArgsContext extends ParserRuleContext {
		public OrderConstraintContext orderConstraint;
		public List<OrderConstraintContext> constraints = new ArrayList<OrderConstraintContext>();
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public List<OrderConstraintContext> orderConstraint() {
			return getRuleContexts(OrderConstraintContext.class);
		}
		public OrderConstraintContext orderConstraint(int i) {
			return getRuleContext(OrderConstraintContext.class,i);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public OrderConstraintListArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_orderConstraintListArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterOrderConstraintListArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitOrderConstraintListArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitOrderConstraintListArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final OrderConstraintListArgsContext orderConstraintListArgs() throws RecognitionException {
		OrderConstraintListArgsContext _localctx = new OrderConstraintListArgsContext(_ctx, getState());
		enterRule(_localctx, 42, RULE_orderConstraintListArgs);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(456);
			match(ARGS_OPENING);
			setState(457);
			((OrderConstraintListArgsContext)_localctx).orderConstraint = orderConstraint();
			((OrderConstraintListArgsContext)_localctx).constraints.add(((OrderConstraintListArgsContext)_localctx).orderConstraint);
			setState(462);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(458);
				match(ARGS_DELIMITER);
				setState(459);
				((OrderConstraintListArgsContext)_localctx).orderConstraint = orderConstraint();
				((OrderConstraintListArgsContext)_localctx).constraints.add(((OrderConstraintListArgsContext)_localctx).orderConstraint);
				}
				}
				setState(464);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			setState(465);
			match(ARGS_CLOSING);
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

	public static class RequireConstraintArgsContext extends ParserRuleContext {
		public RequireConstraintContext requirement;
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public RequireConstraintContext requireConstraint() {
			return getRuleContext(RequireConstraintContext.class,0);
		}
		public RequireConstraintArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_requireConstraintArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterRequireConstraintArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitRequireConstraintArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitRequireConstraintArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final RequireConstraintArgsContext requireConstraintArgs() throws RecognitionException {
		RequireConstraintArgsContext _localctx = new RequireConstraintArgsContext(_ctx, getState());
		enterRule(_localctx, 44, RULE_requireConstraintArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(467);
			match(ARGS_OPENING);
			setState(468);
			((RequireConstraintArgsContext)_localctx).requirement = requireConstraint();
			setState(469);
			match(ARGS_CLOSING);
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

	public static class RequireConstraintListArgsContext extends ParserRuleContext {
		public RequireConstraintContext requireConstraint;
		public List<RequireConstraintContext> requirements = new ArrayList<RequireConstraintContext>();
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public List<RequireConstraintContext> requireConstraint() {
			return getRuleContexts(RequireConstraintContext.class);
		}
		public RequireConstraintContext requireConstraint(int i) {
			return getRuleContext(RequireConstraintContext.class,i);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public RequireConstraintListArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_requireConstraintListArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterRequireConstraintListArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitRequireConstraintListArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitRequireConstraintListArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final RequireConstraintListArgsContext requireConstraintListArgs() throws RecognitionException {
		RequireConstraintListArgsContext _localctx = new RequireConstraintListArgsContext(_ctx, getState());
		enterRule(_localctx, 46, RULE_requireConstraintListArgs);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(471);
			match(ARGS_OPENING);
			setState(472);
			((RequireConstraintListArgsContext)_localctx).requireConstraint = requireConstraint();
			((RequireConstraintListArgsContext)_localctx).requirements.add(((RequireConstraintListArgsContext)_localctx).requireConstraint);
			setState(477);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(473);
				match(ARGS_DELIMITER);
				setState(474);
				((RequireConstraintListArgsContext)_localctx).requireConstraint = requireConstraint();
				((RequireConstraintListArgsContext)_localctx).requirements.add(((RequireConstraintListArgsContext)_localctx).requireConstraint);
				}
				}
				setState(479);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			setState(480);
			match(ARGS_CLOSING);
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

	public static class ClassifierArgsContext extends ParserRuleContext {
		public ClassifierTokenContext classifier;
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public ClassifierTokenContext classifierToken() {
			return getRuleContext(ClassifierTokenContext.class,0);
		}
		public ClassifierArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_classifierArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterClassifierArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitClassifierArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitClassifierArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ClassifierArgsContext classifierArgs() throws RecognitionException {
		ClassifierArgsContext _localctx = new ClassifierArgsContext(_ctx, getState());
		enterRule(_localctx, 48, RULE_classifierArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(482);
			match(ARGS_OPENING);
			setState(483);
			((ClassifierArgsContext)_localctx).classifier = classifierToken();
			setState(484);
			match(ARGS_CLOSING);
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

	public static class ClassifierWithValueArgsContext extends ParserRuleContext {
		public ClassifierTokenContext classifier;
		public ValueTokenContext value;
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public ClassifierTokenContext classifierToken() {
			return getRuleContext(ClassifierTokenContext.class,0);
		}
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
		}
		public ClassifierWithValueArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_classifierWithValueArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterClassifierWithValueArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitClassifierWithValueArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitClassifierWithValueArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ClassifierWithValueArgsContext classifierWithValueArgs() throws RecognitionException {
		ClassifierWithValueArgsContext _localctx = new ClassifierWithValueArgsContext(_ctx, getState());
		enterRule(_localctx, 50, RULE_classifierWithValueArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(486);
			match(ARGS_OPENING);
			setState(487);
			((ClassifierWithValueArgsContext)_localctx).classifier = classifierToken();
			setState(488);
			match(ARGS_DELIMITER);
			setState(489);
			((ClassifierWithValueArgsContext)_localctx).value = valueToken();
			setState(490);
			match(ARGS_CLOSING);
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

	public static class ClassifierWithOptionalValueArgsContext extends ParserRuleContext {
		public ClassifierTokenContext classifier;
		public ValueTokenContext value;
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public ClassifierTokenContext classifierToken() {
			return getRuleContext(ClassifierTokenContext.class,0);
		}
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
		}
		public ClassifierWithOptionalValueArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_classifierWithOptionalValueArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterClassifierWithOptionalValueArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitClassifierWithOptionalValueArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitClassifierWithOptionalValueArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ClassifierWithOptionalValueArgsContext classifierWithOptionalValueArgs() throws RecognitionException {
		ClassifierWithOptionalValueArgsContext _localctx = new ClassifierWithOptionalValueArgsContext(_ctx, getState());
		enterRule(_localctx, 52, RULE_classifierWithOptionalValueArgs);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(492);
			match(ARGS_OPENING);
			setState(493);
			((ClassifierWithOptionalValueArgsContext)_localctx).classifier = classifierToken();
			setState(496);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==ARGS_DELIMITER) {
				{
				setState(494);
				match(ARGS_DELIMITER);
				setState(495);
				((ClassifierWithOptionalValueArgsContext)_localctx).value = valueToken();
				}
			}

			setState(498);
			match(ARGS_CLOSING);
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

	public static class ClassifierWithValueListArgsContext extends ParserRuleContext {
		public ClassifierTokenContext classifier;
		public VariadicValueTokensContext values;
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public ClassifierTokenContext classifierToken() {
			return getRuleContext(ClassifierTokenContext.class,0);
		}
		public VariadicValueTokensContext variadicValueTokens() {
			return getRuleContext(VariadicValueTokensContext.class,0);
		}
		public ClassifierWithValueListArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_classifierWithValueListArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterClassifierWithValueListArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitClassifierWithValueListArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitClassifierWithValueListArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ClassifierWithValueListArgsContext classifierWithValueListArgs() throws RecognitionException {
		ClassifierWithValueListArgsContext _localctx = new ClassifierWithValueListArgsContext(_ctx, getState());
		enterRule(_localctx, 54, RULE_classifierWithValueListArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(500);
			match(ARGS_OPENING);
			setState(501);
			((ClassifierWithValueListArgsContext)_localctx).classifier = classifierToken();
			setState(502);
			match(ARGS_DELIMITER);
			setState(503);
			((ClassifierWithValueListArgsContext)_localctx).values = variadicValueTokens();
			setState(504);
			match(ARGS_CLOSING);
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

	public static class ClassifierWithBetweenValuesArgsContext extends ParserRuleContext {
		public ClassifierTokenContext classifier;
		public ValueTokenContext valueFrom;
		public ValueTokenContext valueTo;
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public ClassifierTokenContext classifierToken() {
			return getRuleContext(ClassifierTokenContext.class,0);
		}
		public List<ValueTokenContext> valueToken() {
			return getRuleContexts(ValueTokenContext.class);
		}
		public ValueTokenContext valueToken(int i) {
			return getRuleContext(ValueTokenContext.class,i);
		}
		public ClassifierWithBetweenValuesArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_classifierWithBetweenValuesArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterClassifierWithBetweenValuesArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitClassifierWithBetweenValuesArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitClassifierWithBetweenValuesArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ClassifierWithBetweenValuesArgsContext classifierWithBetweenValuesArgs() throws RecognitionException {
		ClassifierWithBetweenValuesArgsContext _localctx = new ClassifierWithBetweenValuesArgsContext(_ctx, getState());
		enterRule(_localctx, 56, RULE_classifierWithBetweenValuesArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(506);
			match(ARGS_OPENING);
			setState(507);
			((ClassifierWithBetweenValuesArgsContext)_localctx).classifier = classifierToken();
			setState(508);
			match(ARGS_DELIMITER);
			setState(509);
			((ClassifierWithBetweenValuesArgsContext)_localctx).valueFrom = valueToken();
			setState(510);
			match(ARGS_DELIMITER);
			setState(511);
			((ClassifierWithBetweenValuesArgsContext)_localctx).valueTo = valueToken();
			setState(512);
			match(ARGS_CLOSING);
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

	public static class ValueArgsContext extends ParserRuleContext {
		public ValueTokenContext value;
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
		}
		public ValueArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_valueArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterValueArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitValueArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitValueArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ValueArgsContext valueArgs() throws RecognitionException {
		ValueArgsContext _localctx = new ValueArgsContext(_ctx, getState());
		enterRule(_localctx, 58, RULE_valueArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(514);
			match(ARGS_OPENING);
			setState(515);
			((ValueArgsContext)_localctx).value = valueToken();
			setState(516);
			match(ARGS_CLOSING);
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

	public static class ValueListArgsContext extends ParserRuleContext {
		public VariadicValueTokensContext values;
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public VariadicValueTokensContext variadicValueTokens() {
			return getRuleContext(VariadicValueTokensContext.class,0);
		}
		public ValueListArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_valueListArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterValueListArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitValueListArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitValueListArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ValueListArgsContext valueListArgs() throws RecognitionException {
		ValueListArgsContext _localctx = new ValueListArgsContext(_ctx, getState());
		enterRule(_localctx, 60, RULE_valueListArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(518);
			match(ARGS_OPENING);
			setState(519);
			((ValueListArgsContext)_localctx).values = variadicValueTokens();
			setState(520);
			match(ARGS_CLOSING);
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

	public static class BetweenValuesArgsContext extends ParserRuleContext {
		public ValueTokenContext valueFrom;
		public ValueTokenContext valueTo;
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public List<ValueTokenContext> valueToken() {
			return getRuleContexts(ValueTokenContext.class);
		}
		public ValueTokenContext valueToken(int i) {
			return getRuleContext(ValueTokenContext.class,i);
		}
		public BetweenValuesArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_betweenValuesArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterBetweenValuesArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitBetweenValuesArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitBetweenValuesArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final BetweenValuesArgsContext betweenValuesArgs() throws RecognitionException {
		BetweenValuesArgsContext _localctx = new BetweenValuesArgsContext(_ctx, getState());
		enterRule(_localctx, 62, RULE_betweenValuesArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(522);
			match(ARGS_OPENING);
			setState(523);
			((BetweenValuesArgsContext)_localctx).valueFrom = valueToken();
			setState(524);
			match(ARGS_DELIMITER);
			setState(525);
			((BetweenValuesArgsContext)_localctx).valueTo = valueToken();
			setState(526);
			match(ARGS_CLOSING);
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

	public static class ClassifierListArgsContext extends ParserRuleContext {
		public VariadicClassifierTokensContext classifiers;
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public VariadicClassifierTokensContext variadicClassifierTokens() {
			return getRuleContext(VariadicClassifierTokensContext.class,0);
		}
		public ClassifierListArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_classifierListArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterClassifierListArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitClassifierListArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitClassifierListArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ClassifierListArgsContext classifierListArgs() throws RecognitionException {
		ClassifierListArgsContext _localctx = new ClassifierListArgsContext(_ctx, getState());
		enterRule(_localctx, 64, RULE_classifierListArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(528);
			match(ARGS_OPENING);
			setState(529);
			((ClassifierListArgsContext)_localctx).classifiers = variadicClassifierTokens();
			setState(530);
			match(ARGS_CLOSING);
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

	public static class ValueWithClassifierListArgsContext extends ParserRuleContext {
		public ValueTokenContext value;
		public VariadicClassifierTokensContext classifiers;
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
		}
		public VariadicClassifierTokensContext variadicClassifierTokens() {
			return getRuleContext(VariadicClassifierTokensContext.class,0);
		}
		public ValueWithClassifierListArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_valueWithClassifierListArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterValueWithClassifierListArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitValueWithClassifierListArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitValueWithClassifierListArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ValueWithClassifierListArgsContext valueWithClassifierListArgs() throws RecognitionException {
		ValueWithClassifierListArgsContext _localctx = new ValueWithClassifierListArgsContext(_ctx, getState());
		enterRule(_localctx, 66, RULE_valueWithClassifierListArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(532);
			match(ARGS_OPENING);
			setState(533);
			((ValueWithClassifierListArgsContext)_localctx).value = valueToken();
			setState(534);
			match(ARGS_DELIMITER);
			setState(535);
			((ValueWithClassifierListArgsContext)_localctx).classifiers = variadicClassifierTokens();
			setState(536);
			match(ARGS_CLOSING);
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

	public static class ClassifierWithFilterConstraintArgsContext extends ParserRuleContext {
		public ClassifierTokenContext classifier;
		public FilterConstraintContext filter;
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public ClassifierTokenContext classifierToken() {
			return getRuleContext(ClassifierTokenContext.class,0);
		}
		public FilterConstraintContext filterConstraint() {
			return getRuleContext(FilterConstraintContext.class,0);
		}
		public ClassifierWithFilterConstraintArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_classifierWithFilterConstraintArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterClassifierWithFilterConstraintArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitClassifierWithFilterConstraintArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitClassifierWithFilterConstraintArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ClassifierWithFilterConstraintArgsContext classifierWithFilterConstraintArgs() throws RecognitionException {
		ClassifierWithFilterConstraintArgsContext _localctx = new ClassifierWithFilterConstraintArgsContext(_ctx, getState());
		enterRule(_localctx, 68, RULE_classifierWithFilterConstraintArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(538);
			match(ARGS_OPENING);
			setState(539);
			((ClassifierWithFilterConstraintArgsContext)_localctx).classifier = classifierToken();
			setState(540);
			match(ARGS_DELIMITER);
			setState(541);
			((ClassifierWithFilterConstraintArgsContext)_localctx).filter = filterConstraint();
			setState(542);
			match(ARGS_CLOSING);
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

	public static class ClassifierWithOrderConstraintListArgsContext extends ParserRuleContext {
		public ClassifierTokenContext classifier;
		public OrderConstraintContext orderConstraint;
		public List<OrderConstraintContext> constrains = new ArrayList<OrderConstraintContext>();
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public ClassifierTokenContext classifierToken() {
			return getRuleContext(ClassifierTokenContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public List<OrderConstraintContext> orderConstraint() {
			return getRuleContexts(OrderConstraintContext.class);
		}
		public OrderConstraintContext orderConstraint(int i) {
			return getRuleContext(OrderConstraintContext.class,i);
		}
		public ClassifierWithOrderConstraintListArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_classifierWithOrderConstraintListArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterClassifierWithOrderConstraintListArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitClassifierWithOrderConstraintListArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitClassifierWithOrderConstraintListArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ClassifierWithOrderConstraintListArgsContext classifierWithOrderConstraintListArgs() throws RecognitionException {
		ClassifierWithOrderConstraintListArgsContext _localctx = new ClassifierWithOrderConstraintListArgsContext(_ctx, getState());
		enterRule(_localctx, 70, RULE_classifierWithOrderConstraintListArgs);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(544);
			match(ARGS_OPENING);
			setState(545);
			((ClassifierWithOrderConstraintListArgsContext)_localctx).classifier = classifierToken();
			setState(548);
			_errHandler.sync(this);
			_la = _input.LA(1);
			do {
				{
				{
				setState(546);
				match(ARGS_DELIMITER);
				setState(547);
				((ClassifierWithOrderConstraintListArgsContext)_localctx).orderConstraint = orderConstraint();
				((ClassifierWithOrderConstraintListArgsContext)_localctx).constrains.add(((ClassifierWithOrderConstraintListArgsContext)_localctx).orderConstraint);
				}
				}
				setState(550);
				_errHandler.sync(this);
				_la = _input.LA(1);
			} while ( _la==ARGS_DELIMITER );
			setState(552);
			match(ARGS_CLOSING);
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

	public static class ValueWithRequireConstraintListArgsContext extends ParserRuleContext {
		public ValueTokenContext value;
		public RequireConstraintContext requireConstraint;
		public List<RequireConstraintContext> requirements = new ArrayList<RequireConstraintContext>();
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public List<RequireConstraintContext> requireConstraint() {
			return getRuleContexts(RequireConstraintContext.class);
		}
		public RequireConstraintContext requireConstraint(int i) {
			return getRuleContext(RequireConstraintContext.class,i);
		}
		public ValueWithRequireConstraintListArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_valueWithRequireConstraintListArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterValueWithRequireConstraintListArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitValueWithRequireConstraintListArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitValueWithRequireConstraintListArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ValueWithRequireConstraintListArgsContext valueWithRequireConstraintListArgs() throws RecognitionException {
		ValueWithRequireConstraintListArgsContext _localctx = new ValueWithRequireConstraintListArgsContext(_ctx, getState());
		enterRule(_localctx, 72, RULE_valueWithRequireConstraintListArgs);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(554);
			match(ARGS_OPENING);
			setState(555);
			((ValueWithRequireConstraintListArgsContext)_localctx).value = valueToken();
			setState(560);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(556);
				match(ARGS_DELIMITER);
				setState(557);
				((ValueWithRequireConstraintListArgsContext)_localctx).requireConstraint = requireConstraint();
				((ValueWithRequireConstraintListArgsContext)_localctx).requirements.add(((ValueWithRequireConstraintListArgsContext)_localctx).requireConstraint);
				}
				}
				setState(562);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			setState(563);
			match(ARGS_CLOSING);
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

	public static class HierarchyWithinConstraintArgsContext extends ParserRuleContext {
		public ClassifierTokenContext classifier;
		public FilterConstraintContext ofParent;
		public FilterConstraintContext filterConstraint;
		public List<FilterConstraintContext> constrains = new ArrayList<FilterConstraintContext>();
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public ClassifierTokenContext classifierToken() {
			return getRuleContext(ClassifierTokenContext.class,0);
		}
		public List<FilterConstraintContext> filterConstraint() {
			return getRuleContexts(FilterConstraintContext.class);
		}
		public FilterConstraintContext filterConstraint(int i) {
			return getRuleContext(FilterConstraintContext.class,i);
		}
		public HierarchyWithinConstraintArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_hierarchyWithinConstraintArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHierarchyWithinConstraintArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHierarchyWithinConstraintArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHierarchyWithinConstraintArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final HierarchyWithinConstraintArgsContext hierarchyWithinConstraintArgs() throws RecognitionException {
		HierarchyWithinConstraintArgsContext _localctx = new HierarchyWithinConstraintArgsContext(_ctx, getState());
		enterRule(_localctx, 74, RULE_hierarchyWithinConstraintArgs);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(565);
			match(ARGS_OPENING);
			setState(566);
			((HierarchyWithinConstraintArgsContext)_localctx).classifier = classifierToken();
			setState(567);
			match(ARGS_DELIMITER);
			setState(568);
			((HierarchyWithinConstraintArgsContext)_localctx).ofParent = filterConstraint();
			setState(573);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(569);
				match(ARGS_DELIMITER);
				setState(570);
				((HierarchyWithinConstraintArgsContext)_localctx).filterConstraint = filterConstraint();
				((HierarchyWithinConstraintArgsContext)_localctx).constrains.add(((HierarchyWithinConstraintArgsContext)_localctx).filterConstraint);
				}
				}
				setState(575);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			setState(576);
			match(ARGS_CLOSING);
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

	public static class HierarchyWithinSelfConstraintArgsContext extends ParserRuleContext {
		public FilterConstraintContext ofParent;
		public FilterConstraintContext filterConstraint;
		public List<FilterConstraintContext> constrains = new ArrayList<FilterConstraintContext>();
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public List<FilterConstraintContext> filterConstraint() {
			return getRuleContexts(FilterConstraintContext.class);
		}
		public FilterConstraintContext filterConstraint(int i) {
			return getRuleContext(FilterConstraintContext.class,i);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public HierarchyWithinSelfConstraintArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_hierarchyWithinSelfConstraintArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHierarchyWithinSelfConstraintArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHierarchyWithinSelfConstraintArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHierarchyWithinSelfConstraintArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final HierarchyWithinSelfConstraintArgsContext hierarchyWithinSelfConstraintArgs() throws RecognitionException {
		HierarchyWithinSelfConstraintArgsContext _localctx = new HierarchyWithinSelfConstraintArgsContext(_ctx, getState());
		enterRule(_localctx, 76, RULE_hierarchyWithinSelfConstraintArgs);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(578);
			match(ARGS_OPENING);
			setState(579);
			((HierarchyWithinSelfConstraintArgsContext)_localctx).ofParent = filterConstraint();
			setState(584);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(580);
				match(ARGS_DELIMITER);
				setState(581);
				((HierarchyWithinSelfConstraintArgsContext)_localctx).filterConstraint = filterConstraint();
				((HierarchyWithinSelfConstraintArgsContext)_localctx).constrains.add(((HierarchyWithinSelfConstraintArgsContext)_localctx).filterConstraint);
				}
				}
				setState(586);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			setState(587);
			match(ARGS_CLOSING);
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

	public static class HierarchyWithinRootConstraintArgsContext extends ParserRuleContext {
		public ClassifierTokenContext classifier;
		public FilterConstraintContext filterConstraint;
		public List<FilterConstraintContext> constrains = new ArrayList<FilterConstraintContext>();
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public ClassifierTokenContext classifierToken() {
			return getRuleContext(ClassifierTokenContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public List<FilterConstraintContext> filterConstraint() {
			return getRuleContexts(FilterConstraintContext.class);
		}
		public FilterConstraintContext filterConstraint(int i) {
			return getRuleContext(FilterConstraintContext.class,i);
		}
		public HierarchyWithinRootConstraintArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_hierarchyWithinRootConstraintArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHierarchyWithinRootConstraintArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHierarchyWithinRootConstraintArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHierarchyWithinRootConstraintArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final HierarchyWithinRootConstraintArgsContext hierarchyWithinRootConstraintArgs() throws RecognitionException {
		HierarchyWithinRootConstraintArgsContext _localctx = new HierarchyWithinRootConstraintArgsContext(_ctx, getState());
		enterRule(_localctx, 78, RULE_hierarchyWithinRootConstraintArgs);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(589);
			match(ARGS_OPENING);
			setState(599);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,34,_ctx) ) {
			case 1:
				{
				setState(590);
				((HierarchyWithinRootConstraintArgsContext)_localctx).classifier = classifierToken();
				}
				break;
			case 2:
				{
				{
				setState(591);
				((HierarchyWithinRootConstraintArgsContext)_localctx).classifier = classifierToken();
				setState(596);
				_errHandler.sync(this);
				_la = _input.LA(1);
				while (_la==ARGS_DELIMITER) {
					{
					{
					setState(592);
					match(ARGS_DELIMITER);
					setState(593);
					((HierarchyWithinRootConstraintArgsContext)_localctx).filterConstraint = filterConstraint();
					((HierarchyWithinRootConstraintArgsContext)_localctx).constrains.add(((HierarchyWithinRootConstraintArgsContext)_localctx).filterConstraint);
					}
					}
					setState(598);
					_errHandler.sync(this);
					_la = _input.LA(1);
				}
				}
				}
				break;
			}
			setState(601);
			match(ARGS_CLOSING);
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

	public static class HierarchyWithinRootSelfConstraintArgsContext extends ParserRuleContext {
		public FilterConstraintContext filterConstraint;
		public List<FilterConstraintContext> constrains = new ArrayList<FilterConstraintContext>();
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public List<FilterConstraintContext> filterConstraint() {
			return getRuleContexts(FilterConstraintContext.class);
		}
		public FilterConstraintContext filterConstraint(int i) {
			return getRuleContext(FilterConstraintContext.class,i);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public HierarchyWithinRootSelfConstraintArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_hierarchyWithinRootSelfConstraintArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHierarchyWithinRootSelfConstraintArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHierarchyWithinRootSelfConstraintArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHierarchyWithinRootSelfConstraintArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final HierarchyWithinRootSelfConstraintArgsContext hierarchyWithinRootSelfConstraintArgs() throws RecognitionException {
		HierarchyWithinRootSelfConstraintArgsContext _localctx = new HierarchyWithinRootSelfConstraintArgsContext(_ctx, getState());
		enterRule(_localctx, 80, RULE_hierarchyWithinRootSelfConstraintArgs);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(603);
			match(ARGS_OPENING);
			setState(604);
			((HierarchyWithinRootSelfConstraintArgsContext)_localctx).filterConstraint = filterConstraint();
			((HierarchyWithinRootSelfConstraintArgsContext)_localctx).constrains.add(((HierarchyWithinRootSelfConstraintArgsContext)_localctx).filterConstraint);
			setState(609);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(605);
				match(ARGS_DELIMITER);
				setState(606);
				((HierarchyWithinRootSelfConstraintArgsContext)_localctx).filterConstraint = filterConstraint();
				((HierarchyWithinRootSelfConstraintArgsContext)_localctx).constrains.add(((HierarchyWithinRootSelfConstraintArgsContext)_localctx).filterConstraint);
				}
				}
				setState(611);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			setState(612);
			match(ARGS_CLOSING);
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

	public static class PageConstraintArgsContext extends ParserRuleContext {
		public ValueTokenContext pageNumber;
		public ValueTokenContext pageSize;
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public List<ValueTokenContext> valueToken() {
			return getRuleContexts(ValueTokenContext.class);
		}
		public ValueTokenContext valueToken(int i) {
			return getRuleContext(ValueTokenContext.class,i);
		}
		public PageConstraintArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_pageConstraintArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterPageConstraintArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitPageConstraintArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitPageConstraintArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final PageConstraintArgsContext pageConstraintArgs() throws RecognitionException {
		PageConstraintArgsContext _localctx = new PageConstraintArgsContext(_ctx, getState());
		enterRule(_localctx, 82, RULE_pageConstraintArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(614);
			match(ARGS_OPENING);
			setState(615);
			((PageConstraintArgsContext)_localctx).pageNumber = valueToken();
			setState(616);
			match(ARGS_DELIMITER);
			setState(617);
			((PageConstraintArgsContext)_localctx).pageSize = valueToken();
			setState(618);
			match(ARGS_CLOSING);
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

	public static class StripConstraintArgsContext extends ParserRuleContext {
		public ValueTokenContext offset;
		public ValueTokenContext limit;
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public List<ValueTokenContext> valueToken() {
			return getRuleContexts(ValueTokenContext.class);
		}
		public ValueTokenContext valueToken(int i) {
			return getRuleContext(ValueTokenContext.class,i);
		}
		public StripConstraintArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_stripConstraintArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterStripConstraintArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitStripConstraintArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitStripConstraintArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final StripConstraintArgsContext stripConstraintArgs() throws RecognitionException {
		StripConstraintArgsContext _localctx = new StripConstraintArgsContext(_ctx, getState());
		enterRule(_localctx, 84, RULE_stripConstraintArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(620);
			match(ARGS_OPENING);
			setState(621);
			((StripConstraintArgsContext)_localctx).offset = valueToken();
			setState(622);
			match(ARGS_DELIMITER);
			setState(623);
			((StripConstraintArgsContext)_localctx).limit = valueToken();
			setState(624);
			match(ARGS_CLOSING);
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

	public static class SingleRefReferenceContentArgsContext extends ParserRuleContext {
		public ClassifierTokenContext classifier;
		public RequireConstraintContext requirement;
		public RequireConstraintContext facetEntityRequirement;
		public RequireConstraintContext groupEntityRequirement;
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ClassifierTokenContext classifierToken() {
			return getRuleContext(ClassifierTokenContext.class,0);
		}
		public List<RequireConstraintContext> requireConstraint() {
			return getRuleContexts(RequireConstraintContext.class);
		}
		public RequireConstraintContext requireConstraint(int i) {
			return getRuleContext(RequireConstraintContext.class,i);
		}
		public SingleRefReferenceContentArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_singleRefReferenceContentArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContentArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContentArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContentArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SingleRefReferenceContentArgsContext singleRefReferenceContentArgs() throws RecognitionException {
		SingleRefReferenceContentArgsContext _localctx = new SingleRefReferenceContentArgsContext(_ctx, getState());
		enterRule(_localctx, 86, RULE_singleRefReferenceContentArgs);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(626);
			match(ARGS_OPENING);
			setState(638);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,37,_ctx) ) {
			case 1:
				{
				{
				setState(627);
				((SingleRefReferenceContentArgsContext)_localctx).classifier = classifierToken();
				setState(630);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==ARGS_DELIMITER) {
					{
					setState(628);
					match(ARGS_DELIMITER);
					setState(629);
					((SingleRefReferenceContentArgsContext)_localctx).requirement = requireConstraint();
					}
				}

				}
				}
				break;
			case 2:
				{
				{
				setState(632);
				((SingleRefReferenceContentArgsContext)_localctx).classifier = classifierToken();
				setState(633);
				match(ARGS_DELIMITER);
				setState(634);
				((SingleRefReferenceContentArgsContext)_localctx).facetEntityRequirement = requireConstraint();
				setState(635);
				match(ARGS_DELIMITER);
				setState(636);
				((SingleRefReferenceContentArgsContext)_localctx).groupEntityRequirement = requireConstraint();
				}
				}
				break;
			}
			setState(640);
			match(ARGS_CLOSING);
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

	public static class SingleRefWithFilterReferenceContentArgsContext extends ParserRuleContext {
		public ClassifierTokenContext classifier;
		public FilterConstraintContext filterBy;
		public RequireConstraintContext requirement;
		public RequireConstraintContext facetEntityRequirement;
		public RequireConstraintContext groupEntityRequirement;
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ClassifierTokenContext classifierToken() {
			return getRuleContext(ClassifierTokenContext.class,0);
		}
		public FilterConstraintContext filterConstraint() {
			return getRuleContext(FilterConstraintContext.class,0);
		}
		public List<RequireConstraintContext> requireConstraint() {
			return getRuleContexts(RequireConstraintContext.class);
		}
		public RequireConstraintContext requireConstraint(int i) {
			return getRuleContext(RequireConstraintContext.class,i);
		}
		public SingleRefWithFilterReferenceContentArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_singleRefWithFilterReferenceContentArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefWithFilterReferenceContentArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefWithFilterReferenceContentArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefWithFilterReferenceContentArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SingleRefWithFilterReferenceContentArgsContext singleRefWithFilterReferenceContentArgs() throws RecognitionException {
		SingleRefWithFilterReferenceContentArgsContext _localctx = new SingleRefWithFilterReferenceContentArgsContext(_ctx, getState());
		enterRule(_localctx, 88, RULE_singleRefWithFilterReferenceContentArgs);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(642);
			match(ARGS_OPENING);
			setState(658);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,39,_ctx) ) {
			case 1:
				{
				{
				setState(643);
				((SingleRefWithFilterReferenceContentArgsContext)_localctx).classifier = classifierToken();
				setState(644);
				match(ARGS_DELIMITER);
				setState(645);
				((SingleRefWithFilterReferenceContentArgsContext)_localctx).filterBy = filterConstraint();
				setState(648);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==ARGS_DELIMITER) {
					{
					setState(646);
					match(ARGS_DELIMITER);
					setState(647);
					((SingleRefWithFilterReferenceContentArgsContext)_localctx).requirement = requireConstraint();
					}
				}

				}
				}
				break;
			case 2:
				{
				{
				setState(650);
				((SingleRefWithFilterReferenceContentArgsContext)_localctx).classifier = classifierToken();
				setState(651);
				match(ARGS_DELIMITER);
				setState(652);
				((SingleRefWithFilterReferenceContentArgsContext)_localctx).filterBy = filterConstraint();
				setState(653);
				match(ARGS_DELIMITER);
				setState(654);
				((SingleRefWithFilterReferenceContentArgsContext)_localctx).facetEntityRequirement = requireConstraint();
				setState(655);
				match(ARGS_DELIMITER);
				setState(656);
				((SingleRefWithFilterReferenceContentArgsContext)_localctx).groupEntityRequirement = requireConstraint();
				}
				}
				break;
			}
			setState(660);
			match(ARGS_CLOSING);
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

	public static class SingleRefWithOrderReferenceContentArgsContext extends ParserRuleContext {
		public ClassifierTokenContext classifier;
		public OrderConstraintContext orderBy;
		public RequireConstraintContext requirement;
		public RequireConstraintContext facetEntityRequirement;
		public RequireConstraintContext groupEntityRequirement;
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ClassifierTokenContext classifierToken() {
			return getRuleContext(ClassifierTokenContext.class,0);
		}
		public List<RequireConstraintContext> requireConstraint() {
			return getRuleContexts(RequireConstraintContext.class);
		}
		public RequireConstraintContext requireConstraint(int i) {
			return getRuleContext(RequireConstraintContext.class,i);
		}
		public OrderConstraintContext orderConstraint() {
			return getRuleContext(OrderConstraintContext.class,0);
		}
		public SingleRefWithOrderReferenceContentArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_singleRefWithOrderReferenceContentArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefWithOrderReferenceContentArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefWithOrderReferenceContentArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefWithOrderReferenceContentArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SingleRefWithOrderReferenceContentArgsContext singleRefWithOrderReferenceContentArgs() throws RecognitionException {
		SingleRefWithOrderReferenceContentArgsContext _localctx = new SingleRefWithOrderReferenceContentArgsContext(_ctx, getState());
		enterRule(_localctx, 90, RULE_singleRefWithOrderReferenceContentArgs);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(662);
			match(ARGS_OPENING);
			setState(682);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,43,_ctx) ) {
			case 1:
				{
				{
				setState(663);
				((SingleRefWithOrderReferenceContentArgsContext)_localctx).classifier = classifierToken();
				setState(666);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,40,_ctx) ) {
				case 1:
					{
					setState(664);
					match(ARGS_DELIMITER);
					setState(665);
					((SingleRefWithOrderReferenceContentArgsContext)_localctx).orderBy = orderConstraint();
					}
					break;
				}
				setState(670);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==ARGS_DELIMITER) {
					{
					setState(668);
					match(ARGS_DELIMITER);
					setState(669);
					((SingleRefWithOrderReferenceContentArgsContext)_localctx).requirement = requireConstraint();
					}
				}

				}
				}
				break;
			case 2:
				{
				{
				setState(672);
				((SingleRefWithOrderReferenceContentArgsContext)_localctx).classifier = classifierToken();
				setState(675);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,42,_ctx) ) {
				case 1:
					{
					setState(673);
					match(ARGS_DELIMITER);
					setState(674);
					((SingleRefWithOrderReferenceContentArgsContext)_localctx).orderBy = orderConstraint();
					}
					break;
				}
				setState(677);
				match(ARGS_DELIMITER);
				setState(678);
				((SingleRefWithOrderReferenceContentArgsContext)_localctx).facetEntityRequirement = requireConstraint();
				setState(679);
				match(ARGS_DELIMITER);
				setState(680);
				((SingleRefWithOrderReferenceContentArgsContext)_localctx).groupEntityRequirement = requireConstraint();
				}
				}
				break;
			}
			setState(684);
			match(ARGS_CLOSING);
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

	public static class SingleRefWithFilterAndOrderReferenceContentArgsContext extends ParserRuleContext {
		public ClassifierTokenContext classifier;
		public FilterConstraintContext filterBy;
		public OrderConstraintContext orderBy;
		public RequireConstraintContext requirement;
		public RequireConstraintContext facetEntityRequirement;
		public RequireConstraintContext groupEntityRequirement;
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ClassifierTokenContext classifierToken() {
			return getRuleContext(ClassifierTokenContext.class,0);
		}
		public FilterConstraintContext filterConstraint() {
			return getRuleContext(FilterConstraintContext.class,0);
		}
		public OrderConstraintContext orderConstraint() {
			return getRuleContext(OrderConstraintContext.class,0);
		}
		public List<RequireConstraintContext> requireConstraint() {
			return getRuleContexts(RequireConstraintContext.class);
		}
		public RequireConstraintContext requireConstraint(int i) {
			return getRuleContext(RequireConstraintContext.class,i);
		}
		public SingleRefWithFilterAndOrderReferenceContentArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_singleRefWithFilterAndOrderReferenceContentArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefWithFilterAndOrderReferenceContentArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefWithFilterAndOrderReferenceContentArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefWithFilterAndOrderReferenceContentArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SingleRefWithFilterAndOrderReferenceContentArgsContext singleRefWithFilterAndOrderReferenceContentArgs() throws RecognitionException {
		SingleRefWithFilterAndOrderReferenceContentArgsContext _localctx = new SingleRefWithFilterAndOrderReferenceContentArgsContext(_ctx, getState());
		enterRule(_localctx, 92, RULE_singleRefWithFilterAndOrderReferenceContentArgs);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(686);
			match(ARGS_OPENING);
			setState(706);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,45,_ctx) ) {
			case 1:
				{
				{
				setState(687);
				((SingleRefWithFilterAndOrderReferenceContentArgsContext)_localctx).classifier = classifierToken();
				setState(688);
				match(ARGS_DELIMITER);
				setState(689);
				((SingleRefWithFilterAndOrderReferenceContentArgsContext)_localctx).filterBy = filterConstraint();
				setState(690);
				match(ARGS_DELIMITER);
				setState(691);
				((SingleRefWithFilterAndOrderReferenceContentArgsContext)_localctx).orderBy = orderConstraint();
				setState(694);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==ARGS_DELIMITER) {
					{
					setState(692);
					match(ARGS_DELIMITER);
					setState(693);
					((SingleRefWithFilterAndOrderReferenceContentArgsContext)_localctx).requirement = requireConstraint();
					}
				}

				}
				}
				break;
			case 2:
				{
				{
				setState(696);
				((SingleRefWithFilterAndOrderReferenceContentArgsContext)_localctx).classifier = classifierToken();
				setState(697);
				match(ARGS_DELIMITER);
				setState(698);
				((SingleRefWithFilterAndOrderReferenceContentArgsContext)_localctx).filterBy = filterConstraint();
				setState(699);
				match(ARGS_DELIMITER);
				setState(700);
				((SingleRefWithFilterAndOrderReferenceContentArgsContext)_localctx).orderBy = orderConstraint();
				setState(701);
				match(ARGS_DELIMITER);
				setState(702);
				((SingleRefWithFilterAndOrderReferenceContentArgsContext)_localctx).facetEntityRequirement = requireConstraint();
				setState(703);
				match(ARGS_DELIMITER);
				setState(704);
				((SingleRefWithFilterAndOrderReferenceContentArgsContext)_localctx).groupEntityRequirement = requireConstraint();
				}
				}
				break;
			}
			setState(708);
			match(ARGS_CLOSING);
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

	public static class MultipleRefsReferenceContentArgsContext extends ParserRuleContext {
		public VariadicClassifierTokensContext classifiers;
		public RequireConstraintContext requirement;
		public RequireConstraintContext facetEntityRequirement;
		public RequireConstraintContext groupEntityRequirement;
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public VariadicClassifierTokensContext variadicClassifierTokens() {
			return getRuleContext(VariadicClassifierTokensContext.class,0);
		}
		public List<RequireConstraintContext> requireConstraint() {
			return getRuleContexts(RequireConstraintContext.class);
		}
		public RequireConstraintContext requireConstraint(int i) {
			return getRuleContext(RequireConstraintContext.class,i);
		}
		public MultipleRefsReferenceContentArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_multipleRefsReferenceContentArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterMultipleRefsReferenceContentArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitMultipleRefsReferenceContentArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitMultipleRefsReferenceContentArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final MultipleRefsReferenceContentArgsContext multipleRefsReferenceContentArgs() throws RecognitionException {
		MultipleRefsReferenceContentArgsContext _localctx = new MultipleRefsReferenceContentArgsContext(_ctx, getState());
		enterRule(_localctx, 94, RULE_multipleRefsReferenceContentArgs);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(710);
			match(ARGS_OPENING);
			setState(722);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,47,_ctx) ) {
			case 1:
				{
				{
				setState(711);
				((MultipleRefsReferenceContentArgsContext)_localctx).classifiers = variadicClassifierTokens();
				setState(714);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (_la==ARGS_DELIMITER) {
					{
					setState(712);
					match(ARGS_DELIMITER);
					setState(713);
					((MultipleRefsReferenceContentArgsContext)_localctx).requirement = requireConstraint();
					}
				}

				}
				}
				break;
			case 2:
				{
				{
				setState(716);
				((MultipleRefsReferenceContentArgsContext)_localctx).classifiers = variadicClassifierTokens();
				setState(717);
				match(ARGS_DELIMITER);
				setState(718);
				((MultipleRefsReferenceContentArgsContext)_localctx).facetEntityRequirement = requireConstraint();
				setState(719);
				match(ARGS_DELIMITER);
				setState(720);
				((MultipleRefsReferenceContentArgsContext)_localctx).groupEntityRequirement = requireConstraint();
				}
				}
				break;
			}
			setState(724);
			match(ARGS_CLOSING);
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

	public static class AllRefsReferenceContentArgsContext extends ParserRuleContext {
		public RequireConstraintContext requirement;
		public RequireConstraintContext facetEntityRequirement;
		public RequireConstraintContext groupEntityRequirement;
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public List<RequireConstraintContext> requireConstraint() {
			return getRuleContexts(RequireConstraintContext.class);
		}
		public RequireConstraintContext requireConstraint(int i) {
			return getRuleContext(RequireConstraintContext.class,i);
		}
		public AllRefsReferenceContentArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_allRefsReferenceContentArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAllRefsReferenceContentArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAllRefsReferenceContentArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAllRefsReferenceContentArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final AllRefsReferenceContentArgsContext allRefsReferenceContentArgs() throws RecognitionException {
		AllRefsReferenceContentArgsContext _localctx = new AllRefsReferenceContentArgsContext(_ctx, getState());
		enterRule(_localctx, 96, RULE_allRefsReferenceContentArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(726);
			match(ARGS_OPENING);
			setState(732);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,48,_ctx) ) {
			case 1:
				{
				{
				setState(727);
				((AllRefsReferenceContentArgsContext)_localctx).requirement = requireConstraint();
				}
				}
				break;
			case 2:
				{
				{
				setState(728);
				((AllRefsReferenceContentArgsContext)_localctx).facetEntityRequirement = requireConstraint();
				setState(729);
				match(ARGS_DELIMITER);
				setState(730);
				((AllRefsReferenceContentArgsContext)_localctx).groupEntityRequirement = requireConstraint();
				}
				}
				break;
			}
			setState(734);
			match(ARGS_CLOSING);
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

	public static class SingleRequireHierarchyContentArgsContext extends ParserRuleContext {
		public RequireConstraintContext requirement;
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public RequireConstraintContext requireConstraint() {
			return getRuleContext(RequireConstraintContext.class,0);
		}
		public SingleRequireHierarchyContentArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_singleRequireHierarchyContentArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRequireHierarchyContentArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRequireHierarchyContentArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRequireHierarchyContentArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SingleRequireHierarchyContentArgsContext singleRequireHierarchyContentArgs() throws RecognitionException {
		SingleRequireHierarchyContentArgsContext _localctx = new SingleRequireHierarchyContentArgsContext(_ctx, getState());
		enterRule(_localctx, 98, RULE_singleRequireHierarchyContentArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(736);
			match(ARGS_OPENING);
			setState(737);
			((SingleRequireHierarchyContentArgsContext)_localctx).requirement = requireConstraint();
			setState(738);
			match(ARGS_CLOSING);
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

	public static class AllRequiresHierarchyContentArgsContext extends ParserRuleContext {
		public RequireConstraintContext stopAt;
		public RequireConstraintContext entityRequirement;
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public List<RequireConstraintContext> requireConstraint() {
			return getRuleContexts(RequireConstraintContext.class);
		}
		public RequireConstraintContext requireConstraint(int i) {
			return getRuleContext(RequireConstraintContext.class,i);
		}
		public AllRequiresHierarchyContentArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_allRequiresHierarchyContentArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAllRequiresHierarchyContentArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAllRequiresHierarchyContentArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAllRequiresHierarchyContentArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final AllRequiresHierarchyContentArgsContext allRequiresHierarchyContentArgs() throws RecognitionException {
		AllRequiresHierarchyContentArgsContext _localctx = new AllRequiresHierarchyContentArgsContext(_ctx, getState());
		enterRule(_localctx, 100, RULE_allRequiresHierarchyContentArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(740);
			match(ARGS_OPENING);
			setState(741);
			((AllRequiresHierarchyContentArgsContext)_localctx).stopAt = requireConstraint();
			setState(742);
			match(ARGS_DELIMITER);
			setState(743);
			((AllRequiresHierarchyContentArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(744);
			match(ARGS_CLOSING);
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

	public static class FacetSummaryArgsContext extends ParserRuleContext {
		public ValueTokenContext depth;
		public RequireConstraintContext requirement;
		public RequireConstraintContext facetEntityRequirement;
		public RequireConstraintContext groupEntityRequirement;
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
		}
		public List<RequireConstraintContext> requireConstraint() {
			return getRuleContexts(RequireConstraintContext.class);
		}
		public RequireConstraintContext requireConstraint(int i) {
			return getRuleContext(RequireConstraintContext.class,i);
		}
		public FacetSummaryArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_facetSummaryArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetSummaryArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetSummaryArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetSummaryArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FacetSummaryArgsContext facetSummaryArgs() throws RecognitionException {
		FacetSummaryArgsContext _localctx = new FacetSummaryArgsContext(_ctx, getState());
		enterRule(_localctx, 102, RULE_facetSummaryArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(746);
			match(ARGS_OPENING);
			setState(758);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,49,_ctx) ) {
			case 1:
				{
				{
				setState(747);
				((FacetSummaryArgsContext)_localctx).depth = valueToken();
				}
				}
				break;
			case 2:
				{
				{
				setState(748);
				((FacetSummaryArgsContext)_localctx).depth = valueToken();
				setState(749);
				match(ARGS_DELIMITER);
				setState(750);
				((FacetSummaryArgsContext)_localctx).requirement = requireConstraint();
				}
				}
				break;
			case 3:
				{
				{
				setState(752);
				((FacetSummaryArgsContext)_localctx).depth = valueToken();
				setState(753);
				match(ARGS_DELIMITER);
				setState(754);
				((FacetSummaryArgsContext)_localctx).facetEntityRequirement = requireConstraint();
				setState(755);
				match(ARGS_DELIMITER);
				setState(756);
				((FacetSummaryArgsContext)_localctx).groupEntityRequirement = requireConstraint();
				}
				}
				break;
			}
			setState(760);
			match(ARGS_CLOSING);
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

	public static class FacetSummaryOfReferenceArgsContext extends ParserRuleContext {
		public ClassifierTokenContext referenceName;
		public ValueTokenContext depth;
		public RequireConstraintContext requirement;
		public RequireConstraintContext facetEntityRequirement;
		public RequireConstraintContext groupEntityRequirement;
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ClassifierTokenContext classifierToken() {
			return getRuleContext(ClassifierTokenContext.class,0);
		}
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
		}
		public List<RequireConstraintContext> requireConstraint() {
			return getRuleContexts(RequireConstraintContext.class);
		}
		public RequireConstraintContext requireConstraint(int i) {
			return getRuleContext(RequireConstraintContext.class,i);
		}
		public FacetSummaryOfReferenceArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_facetSummaryOfReferenceArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetSummaryOfReferenceArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetSummaryOfReferenceArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetSummaryOfReferenceArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FacetSummaryOfReferenceArgsContext facetSummaryOfReferenceArgs() throws RecognitionException {
		FacetSummaryOfReferenceArgsContext _localctx = new FacetSummaryOfReferenceArgsContext(_ctx, getState());
		enterRule(_localctx, 104, RULE_facetSummaryOfReferenceArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(762);
			match(ARGS_OPENING);
			setState(782);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,50,_ctx) ) {
			case 1:
				{
				{
				setState(763);
				((FacetSummaryOfReferenceArgsContext)_localctx).referenceName = classifierToken();
				}
				}
				break;
			case 2:
				{
				{
				setState(764);
				((FacetSummaryOfReferenceArgsContext)_localctx).referenceName = classifierToken();
				setState(765);
				match(ARGS_DELIMITER);
				setState(766);
				((FacetSummaryOfReferenceArgsContext)_localctx).depth = valueToken();
				}
				}
				break;
			case 3:
				{
				{
				setState(768);
				((FacetSummaryOfReferenceArgsContext)_localctx).referenceName = classifierToken();
				setState(769);
				match(ARGS_DELIMITER);
				setState(770);
				((FacetSummaryOfReferenceArgsContext)_localctx).depth = valueToken();
				setState(771);
				match(ARGS_DELIMITER);
				setState(772);
				((FacetSummaryOfReferenceArgsContext)_localctx).requirement = requireConstraint();
				}
				}
				break;
			case 4:
				{
				{
				setState(774);
				((FacetSummaryOfReferenceArgsContext)_localctx).referenceName = classifierToken();
				setState(775);
				match(ARGS_DELIMITER);
				setState(776);
				((FacetSummaryOfReferenceArgsContext)_localctx).depth = valueToken();
				setState(777);
				match(ARGS_DELIMITER);
				setState(778);
				((FacetSummaryOfReferenceArgsContext)_localctx).facetEntityRequirement = requireConstraint();
				setState(779);
				match(ARGS_DELIMITER);
				setState(780);
				((FacetSummaryOfReferenceArgsContext)_localctx).groupEntityRequirement = requireConstraint();
				}
				}
				break;
			}
			setState(784);
			match(ARGS_CLOSING);
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

	public static class FullHierarchyStatisticsArgsContext extends ParserRuleContext {
		public ValueTokenContext statisticsBase;
		public VariadicValueTokensContext statisticsTypes;
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
		}
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public VariadicValueTokensContext variadicValueTokens() {
			return getRuleContext(VariadicValueTokensContext.class,0);
		}
		public FullHierarchyStatisticsArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_fullHierarchyStatisticsArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFullHierarchyStatisticsArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFullHierarchyStatisticsArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFullHierarchyStatisticsArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FullHierarchyStatisticsArgsContext fullHierarchyStatisticsArgs() throws RecognitionException {
		FullHierarchyStatisticsArgsContext _localctx = new FullHierarchyStatisticsArgsContext(_ctx, getState());
		enterRule(_localctx, 106, RULE_fullHierarchyStatisticsArgs);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(786);
			match(ARGS_OPENING);
			setState(787);
			((FullHierarchyStatisticsArgsContext)_localctx).statisticsBase = valueToken();
			setState(790);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==ARGS_DELIMITER) {
				{
				setState(788);
				match(ARGS_DELIMITER);
				setState(789);
				((FullHierarchyStatisticsArgsContext)_localctx).statisticsTypes = variadicValueTokens();
				}
			}

			setState(792);
			match(ARGS_CLOSING);
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

	public static class HierarchyRequireConstraintArgsContext extends ParserRuleContext {
		public ClassifierTokenContext outputName;
		public RequireConstraintContext requireConstraint;
		public List<RequireConstraintContext> requirements = new ArrayList<RequireConstraintContext>();
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public ClassifierTokenContext classifierToken() {
			return getRuleContext(ClassifierTokenContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public List<RequireConstraintContext> requireConstraint() {
			return getRuleContexts(RequireConstraintContext.class);
		}
		public RequireConstraintContext requireConstraint(int i) {
			return getRuleContext(RequireConstraintContext.class,i);
		}
		public HierarchyRequireConstraintArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_hierarchyRequireConstraintArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHierarchyRequireConstraintArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHierarchyRequireConstraintArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHierarchyRequireConstraintArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final HierarchyRequireConstraintArgsContext hierarchyRequireConstraintArgs() throws RecognitionException {
		HierarchyRequireConstraintArgsContext _localctx = new HierarchyRequireConstraintArgsContext(_ctx, getState());
		enterRule(_localctx, 108, RULE_hierarchyRequireConstraintArgs);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(794);
			match(ARGS_OPENING);
			setState(795);
			((HierarchyRequireConstraintArgsContext)_localctx).outputName = classifierToken();
			setState(800);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(796);
				match(ARGS_DELIMITER);
				setState(797);
				((HierarchyRequireConstraintArgsContext)_localctx).requireConstraint = requireConstraint();
				((HierarchyRequireConstraintArgsContext)_localctx).requirements.add(((HierarchyRequireConstraintArgsContext)_localctx).requireConstraint);
				}
				}
				setState(802);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			setState(803);
			match(ARGS_CLOSING);
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

	public static class HierarchyFromNodeArgsContext extends ParserRuleContext {
		public ClassifierTokenContext outputName;
		public RequireConstraintContext node;
		public RequireConstraintContext requireConstraint;
		public List<RequireConstraintContext> requirements = new ArrayList<RequireConstraintContext>();
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public ClassifierTokenContext classifierToken() {
			return getRuleContext(ClassifierTokenContext.class,0);
		}
		public List<RequireConstraintContext> requireConstraint() {
			return getRuleContexts(RequireConstraintContext.class);
		}
		public RequireConstraintContext requireConstraint(int i) {
			return getRuleContext(RequireConstraintContext.class,i);
		}
		public HierarchyFromNodeArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_hierarchyFromNodeArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHierarchyFromNodeArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHierarchyFromNodeArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHierarchyFromNodeArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final HierarchyFromNodeArgsContext hierarchyFromNodeArgs() throws RecognitionException {
		HierarchyFromNodeArgsContext _localctx = new HierarchyFromNodeArgsContext(_ctx, getState());
		enterRule(_localctx, 110, RULE_hierarchyFromNodeArgs);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(805);
			match(ARGS_OPENING);
			setState(806);
			((HierarchyFromNodeArgsContext)_localctx).outputName = classifierToken();
			setState(807);
			match(ARGS_DELIMITER);
			setState(808);
			((HierarchyFromNodeArgsContext)_localctx).node = requireConstraint();
			setState(813);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(809);
				match(ARGS_DELIMITER);
				setState(810);
				((HierarchyFromNodeArgsContext)_localctx).requireConstraint = requireConstraint();
				((HierarchyFromNodeArgsContext)_localctx).requirements.add(((HierarchyFromNodeArgsContext)_localctx).requireConstraint);
				}
				}
				setState(815);
				_errHandler.sync(this);
				_la = _input.LA(1);
			}
			setState(816);
			match(ARGS_CLOSING);
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

	public static class FullHierarchyOfSelfArgsContext extends ParserRuleContext {
		public OrderConstraintContext orderBy;
		public RequireConstraintContext requireConstraint;
		public List<RequireConstraintContext> requirements = new ArrayList<RequireConstraintContext>();
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public OrderConstraintContext orderConstraint() {
			return getRuleContext(OrderConstraintContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public List<RequireConstraintContext> requireConstraint() {
			return getRuleContexts(RequireConstraintContext.class);
		}
		public RequireConstraintContext requireConstraint(int i) {
			return getRuleContext(RequireConstraintContext.class,i);
		}
		public FullHierarchyOfSelfArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_fullHierarchyOfSelfArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFullHierarchyOfSelfArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFullHierarchyOfSelfArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFullHierarchyOfSelfArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FullHierarchyOfSelfArgsContext fullHierarchyOfSelfArgs() throws RecognitionException {
		FullHierarchyOfSelfArgsContext _localctx = new FullHierarchyOfSelfArgsContext(_ctx, getState());
		enterRule(_localctx, 112, RULE_fullHierarchyOfSelfArgs);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(818);
			match(ARGS_OPENING);
			setState(819);
			((FullHierarchyOfSelfArgsContext)_localctx).orderBy = orderConstraint();
			setState(822);
			_errHandler.sync(this);
			_la = _input.LA(1);
			do {
				{
				{
				setState(820);
				match(ARGS_DELIMITER);
				setState(821);
				((FullHierarchyOfSelfArgsContext)_localctx).requireConstraint = requireConstraint();
				((FullHierarchyOfSelfArgsContext)_localctx).requirements.add(((FullHierarchyOfSelfArgsContext)_localctx).requireConstraint);
				}
				}
				setState(824);
				_errHandler.sync(this);
				_la = _input.LA(1);
			} while ( _la==ARGS_DELIMITER );
			setState(826);
			match(ARGS_CLOSING);
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

	public static class BasicHierarchyOfReferenceArgsContext extends ParserRuleContext {
		public VariadicClassifierTokensContext referenceNames;
		public ValueTokenContext emptyHierarchicalEntityBehaviour;
		public RequireConstraintContext requireConstraint;
		public List<RequireConstraintContext> requirements = new ArrayList<RequireConstraintContext>();
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public VariadicClassifierTokensContext variadicClassifierTokens() {
			return getRuleContext(VariadicClassifierTokensContext.class,0);
		}
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
		}
		public List<RequireConstraintContext> requireConstraint() {
			return getRuleContexts(RequireConstraintContext.class);
		}
		public RequireConstraintContext requireConstraint(int i) {
			return getRuleContext(RequireConstraintContext.class,i);
		}
		public BasicHierarchyOfReferenceArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_basicHierarchyOfReferenceArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterBasicHierarchyOfReferenceArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitBasicHierarchyOfReferenceArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitBasicHierarchyOfReferenceArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final BasicHierarchyOfReferenceArgsContext basicHierarchyOfReferenceArgs() throws RecognitionException {
		BasicHierarchyOfReferenceArgsContext _localctx = new BasicHierarchyOfReferenceArgsContext(_ctx, getState());
		enterRule(_localctx, 114, RULE_basicHierarchyOfReferenceArgs);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(828);
			match(ARGS_OPENING);
			setState(829);
			((BasicHierarchyOfReferenceArgsContext)_localctx).referenceNames = variadicClassifierTokens();
			setState(830);
			match(ARGS_DELIMITER);
			setState(831);
			((BasicHierarchyOfReferenceArgsContext)_localctx).emptyHierarchicalEntityBehaviour = valueToken();
			setState(834);
			_errHandler.sync(this);
			_la = _input.LA(1);
			do {
				{
				{
				setState(832);
				match(ARGS_DELIMITER);
				setState(833);
				((BasicHierarchyOfReferenceArgsContext)_localctx).requireConstraint = requireConstraint();
				((BasicHierarchyOfReferenceArgsContext)_localctx).requirements.add(((BasicHierarchyOfReferenceArgsContext)_localctx).requireConstraint);
				}
				}
				setState(836);
				_errHandler.sync(this);
				_la = _input.LA(1);
			} while ( _la==ARGS_DELIMITER );
			setState(838);
			match(ARGS_CLOSING);
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

	public static class FullHierarchyOfReferenceArgsContext extends ParserRuleContext {
		public VariadicClassifierTokensContext referenceNames;
		public ValueTokenContext emptyHierarchicalEntityBehaviour;
		public OrderConstraintContext orderBy;
		public RequireConstraintContext requireConstraint;
		public List<RequireConstraintContext> requirements = new ArrayList<RequireConstraintContext>();
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public VariadicClassifierTokensContext variadicClassifierTokens() {
			return getRuleContext(VariadicClassifierTokensContext.class,0);
		}
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
		}
		public OrderConstraintContext orderConstraint() {
			return getRuleContext(OrderConstraintContext.class,0);
		}
		public List<RequireConstraintContext> requireConstraint() {
			return getRuleContexts(RequireConstraintContext.class);
		}
		public RequireConstraintContext requireConstraint(int i) {
			return getRuleContext(RequireConstraintContext.class,i);
		}
		public FullHierarchyOfReferenceArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_fullHierarchyOfReferenceArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFullHierarchyOfReferenceArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFullHierarchyOfReferenceArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFullHierarchyOfReferenceArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FullHierarchyOfReferenceArgsContext fullHierarchyOfReferenceArgs() throws RecognitionException {
		FullHierarchyOfReferenceArgsContext _localctx = new FullHierarchyOfReferenceArgsContext(_ctx, getState());
		enterRule(_localctx, 116, RULE_fullHierarchyOfReferenceArgs);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(840);
			match(ARGS_OPENING);
			setState(841);
			((FullHierarchyOfReferenceArgsContext)_localctx).referenceNames = variadicClassifierTokens();
			setState(842);
			match(ARGS_DELIMITER);
			setState(843);
			((FullHierarchyOfReferenceArgsContext)_localctx).emptyHierarchicalEntityBehaviour = valueToken();
			setState(844);
			match(ARGS_DELIMITER);
			setState(845);
			((FullHierarchyOfReferenceArgsContext)_localctx).orderBy = orderConstraint();
			setState(848);
			_errHandler.sync(this);
			_la = _input.LA(1);
			do {
				{
				{
				setState(846);
				match(ARGS_DELIMITER);
				setState(847);
				((FullHierarchyOfReferenceArgsContext)_localctx).requireConstraint = requireConstraint();
				((FullHierarchyOfReferenceArgsContext)_localctx).requirements.add(((FullHierarchyOfReferenceArgsContext)_localctx).requireConstraint);
				}
				}
				setState(850);
				_errHandler.sync(this);
				_la = _input.LA(1);
			} while ( _la==ARGS_DELIMITER );
			setState(852);
			match(ARGS_CLOSING);
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

	public static class PositionalParameterContext extends ParserRuleContext {
		public TerminalNode POSITIONAL_PARAMETER() { return getToken(EvitaQLParser.POSITIONAL_PARAMETER, 0); }
		public PositionalParameterContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_positionalParameter; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterPositionalParameter(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitPositionalParameter(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitPositionalParameter(this);
			else return visitor.visitChildren(this);
		}
	}

	public final PositionalParameterContext positionalParameter() throws RecognitionException {
		PositionalParameterContext _localctx = new PositionalParameterContext(_ctx, getState());
		enterRule(_localctx, 118, RULE_positionalParameter);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(854);
			match(POSITIONAL_PARAMETER);
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

	public static class NamedParameterContext extends ParserRuleContext {
		public TerminalNode NAMED_PARAMETER() { return getToken(EvitaQLParser.NAMED_PARAMETER, 0); }
		public NamedParameterContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_namedParameter; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterNamedParameter(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitNamedParameter(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitNamedParameter(this);
			else return visitor.visitChildren(this);
		}
	}

	public final NamedParameterContext namedParameter() throws RecognitionException {
		NamedParameterContext _localctx = new NamedParameterContext(_ctx, getState());
		enterRule(_localctx, 120, RULE_namedParameter);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(856);
			match(NAMED_PARAMETER);
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

	public static class VariadicClassifierTokensContext extends ParserRuleContext {
		public VariadicClassifierTokensContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_variadicClassifierTokens; }

		public VariadicClassifierTokensContext() { }
		public void copyFrom(VariadicClassifierTokensContext ctx) {
			super.copyFrom(ctx);
		}
	}
	public static class PositionalParameterVariadicClassifierTokensContext extends VariadicClassifierTokensContext {
		public PositionalParameterContext positionalParameter() {
			return getRuleContext(PositionalParameterContext.class,0);
		}
		public PositionalParameterVariadicClassifierTokensContext(VariadicClassifierTokensContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterPositionalParameterVariadicClassifierTokens(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitPositionalParameterVariadicClassifierTokens(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitPositionalParameterVariadicClassifierTokens(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class ExplicitVariadicClassifierTokensContext extends VariadicClassifierTokensContext {
		public ClassifierTokenContext classifierToken;
		public List<ClassifierTokenContext> classifierTokens = new ArrayList<ClassifierTokenContext>();
		public List<ClassifierTokenContext> classifierToken() {
			return getRuleContexts(ClassifierTokenContext.class);
		}
		public ClassifierTokenContext classifierToken(int i) {
			return getRuleContext(ClassifierTokenContext.class,i);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ExplicitVariadicClassifierTokensContext(VariadicClassifierTokensContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterExplicitVariadicClassifierTokens(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitExplicitVariadicClassifierTokens(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitExplicitVariadicClassifierTokens(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class NamedParameterVariadicClassifierTokensContext extends VariadicClassifierTokensContext {
		public NamedParameterContext namedParameter() {
			return getRuleContext(NamedParameterContext.class,0);
		}
		public NamedParameterVariadicClassifierTokensContext(VariadicClassifierTokensContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterNamedParameterVariadicClassifierTokens(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitNamedParameterVariadicClassifierTokens(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitNamedParameterVariadicClassifierTokens(this);
			else return visitor.visitChildren(this);
		}
	}

	public final VariadicClassifierTokensContext variadicClassifierTokens() throws RecognitionException {
		VariadicClassifierTokensContext _localctx = new VariadicClassifierTokensContext(_ctx, getState());
		enterRule(_localctx, 122, RULE_variadicClassifierTokens);
		try {
			int _alt;
			setState(868);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,58,_ctx) ) {
			case 1:
				_localctx = new PositionalParameterVariadicClassifierTokensContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(858);
				positionalParameter();
				}
				break;
			case 2:
				_localctx = new NamedParameterVariadicClassifierTokensContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(859);
				namedParameter();
				}
				break;
			case 3:
				_localctx = new ExplicitVariadicClassifierTokensContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(860);
				((ExplicitVariadicClassifierTokensContext)_localctx).classifierToken = classifierToken();
				((ExplicitVariadicClassifierTokensContext)_localctx).classifierTokens.add(((ExplicitVariadicClassifierTokensContext)_localctx).classifierToken);
				setState(865);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,57,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(861);
						match(ARGS_DELIMITER);
						setState(862);
						((ExplicitVariadicClassifierTokensContext)_localctx).classifierToken = classifierToken();
						((ExplicitVariadicClassifierTokensContext)_localctx).classifierTokens.add(((ExplicitVariadicClassifierTokensContext)_localctx).classifierToken);
						}
						}
					}
					setState(867);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,57,_ctx);
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

	public static class ClassifierTokenContext extends ParserRuleContext {
		public ClassifierTokenContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_classifierToken; }

		public ClassifierTokenContext() { }
		public void copyFrom(ClassifierTokenContext ctx) {
			super.copyFrom(ctx);
		}
	}
	public static class PositionalParameterClassifierTokenContext extends ClassifierTokenContext {
		public PositionalParameterContext positionalParameter() {
			return getRuleContext(PositionalParameterContext.class,0);
		}
		public PositionalParameterClassifierTokenContext(ClassifierTokenContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterPositionalParameterClassifierToken(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitPositionalParameterClassifierToken(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitPositionalParameterClassifierToken(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class NamedParameterClassifierTokenContext extends ClassifierTokenContext {
		public NamedParameterContext namedParameter() {
			return getRuleContext(NamedParameterContext.class,0);
		}
		public NamedParameterClassifierTokenContext(ClassifierTokenContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterNamedParameterClassifierToken(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitNamedParameterClassifierToken(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitNamedParameterClassifierToken(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class StringClassifierTokenContext extends ClassifierTokenContext {
		public TerminalNode STRING() { return getToken(EvitaQLParser.STRING, 0); }
		public StringClassifierTokenContext(ClassifierTokenContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterStringClassifierToken(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitStringClassifierToken(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitStringClassifierToken(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ClassifierTokenContext classifierToken() throws RecognitionException {
		ClassifierTokenContext _localctx = new ClassifierTokenContext(_ctx, getState());
		enterRule(_localctx, 124, RULE_classifierToken);
		try {
			setState(873);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case POSITIONAL_PARAMETER:
				_localctx = new PositionalParameterClassifierTokenContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(870);
				positionalParameter();
				}
				break;
			case NAMED_PARAMETER:
				_localctx = new NamedParameterClassifierTokenContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(871);
				namedParameter();
				}
				break;
			case STRING:
				_localctx = new StringClassifierTokenContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(872);
				match(STRING);
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

	public static class VariadicValueTokensContext extends ParserRuleContext {
		public VariadicValueTokensContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_variadicValueTokens; }

		public VariadicValueTokensContext() { }
		public void copyFrom(VariadicValueTokensContext ctx) {
			super.copyFrom(ctx);
		}
	}
	public static class PositionalParameterVariadicValueTokensContext extends VariadicValueTokensContext {
		public PositionalParameterContext positionalParameter() {
			return getRuleContext(PositionalParameterContext.class,0);
		}
		public PositionalParameterVariadicValueTokensContext(VariadicValueTokensContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterPositionalParameterVariadicValueTokens(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitPositionalParameterVariadicValueTokens(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitPositionalParameterVariadicValueTokens(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class NamedParameterVariadicValueTokensContext extends VariadicValueTokensContext {
		public NamedParameterContext namedParameter() {
			return getRuleContext(NamedParameterContext.class,0);
		}
		public NamedParameterVariadicValueTokensContext(VariadicValueTokensContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterNamedParameterVariadicValueTokens(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitNamedParameterVariadicValueTokens(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitNamedParameterVariadicValueTokens(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class ExplicitVariadicValueTokensContext extends VariadicValueTokensContext {
		public ValueTokenContext valueToken;
		public List<ValueTokenContext> valueTokens = new ArrayList<ValueTokenContext>();
		public List<ValueTokenContext> valueToken() {
			return getRuleContexts(ValueTokenContext.class);
		}
		public ValueTokenContext valueToken(int i) {
			return getRuleContext(ValueTokenContext.class,i);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ExplicitVariadicValueTokensContext(VariadicValueTokensContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterExplicitVariadicValueTokens(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitExplicitVariadicValueTokens(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitExplicitVariadicValueTokens(this);
			else return visitor.visitChildren(this);
		}
	}

	public final VariadicValueTokensContext variadicValueTokens() throws RecognitionException {
		VariadicValueTokensContext _localctx = new VariadicValueTokensContext(_ctx, getState());
		enterRule(_localctx, 126, RULE_variadicValueTokens);
		int _la;
		try {
			setState(885);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,61,_ctx) ) {
			case 1:
				_localctx = new PositionalParameterVariadicValueTokensContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(875);
				positionalParameter();
				}
				break;
			case 2:
				_localctx = new NamedParameterVariadicValueTokensContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(876);
				namedParameter();
				}
				break;
			case 3:
				_localctx = new ExplicitVariadicValueTokensContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(877);
				((ExplicitVariadicValueTokensContext)_localctx).valueToken = valueToken();
				((ExplicitVariadicValueTokensContext)_localctx).valueTokens.add(((ExplicitVariadicValueTokensContext)_localctx).valueToken);
				setState(882);
				_errHandler.sync(this);
				_la = _input.LA(1);
				while (_la==ARGS_DELIMITER) {
					{
					{
					setState(878);
					match(ARGS_DELIMITER);
					setState(879);
					((ExplicitVariadicValueTokensContext)_localctx).valueToken = valueToken();
					((ExplicitVariadicValueTokensContext)_localctx).valueTokens.add(((ExplicitVariadicValueTokensContext)_localctx).valueToken);
					}
					}
					setState(884);
					_errHandler.sync(this);
					_la = _input.LA(1);
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
	public static class DateTimeValueTokenContext extends ValueTokenContext {
		public TerminalNode DATE_TIME() { return getToken(EvitaQLParser.DATE_TIME, 0); }
		public DateTimeValueTokenContext(ValueTokenContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterDateTimeValueToken(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitDateTimeValueToken(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitDateTimeValueToken(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class IntNumberRangeValueTokenContext extends ValueTokenContext {
		public TerminalNode INT_NUMBER_RANGE() { return getToken(EvitaQLParser.INT_NUMBER_RANGE, 0); }
		public IntNumberRangeValueTokenContext(ValueTokenContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterIntNumberRangeValueToken(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitIntNumberRangeValueToken(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitIntNumberRangeValueToken(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class IntValueTokenContext extends ValueTokenContext {
		public TerminalNode INT() { return getToken(EvitaQLParser.INT, 0); }
		public IntValueTokenContext(ValueTokenContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterIntValueToken(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitIntValueToken(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitIntValueToken(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class NamedParameterValueTokenContext extends ValueTokenContext {
		public NamedParameterContext namedParameter() {
			return getRuleContext(NamedParameterContext.class,0);
		}
		public NamedParameterValueTokenContext(ValueTokenContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterNamedParameterValueToken(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitNamedParameterValueToken(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitNamedParameterValueToken(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class FloatNumberRangeValueTokenContext extends ValueTokenContext {
		public TerminalNode FLOAT_NUMBER_RANGE() { return getToken(EvitaQLParser.FLOAT_NUMBER_RANGE, 0); }
		public FloatNumberRangeValueTokenContext(ValueTokenContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFloatNumberRangeValueToken(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFloatNumberRangeValueToken(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFloatNumberRangeValueToken(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class DateTimeRangeValueTokenContext extends ValueTokenContext {
		public TerminalNode DATE_TIME_RANGE() { return getToken(EvitaQLParser.DATE_TIME_RANGE, 0); }
		public DateTimeRangeValueTokenContext(ValueTokenContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterDateTimeRangeValueToken(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitDateTimeRangeValueToken(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitDateTimeRangeValueToken(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class BooleanValueTokenContext extends ValueTokenContext {
		public TerminalNode BOOLEAN() { return getToken(EvitaQLParser.BOOLEAN, 0); }
		public BooleanValueTokenContext(ValueTokenContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterBooleanValueToken(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitBooleanValueToken(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitBooleanValueToken(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class TimeValueTokenContext extends ValueTokenContext {
		public TerminalNode TIME() { return getToken(EvitaQLParser.TIME, 0); }
		public TimeValueTokenContext(ValueTokenContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterTimeValueToken(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitTimeValueToken(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitTimeValueToken(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class OffsetDateTimeValueTokenContext extends ValueTokenContext {
		public TerminalNode OFFSET_DATE_TIME() { return getToken(EvitaQLParser.OFFSET_DATE_TIME, 0); }
		public OffsetDateTimeValueTokenContext(ValueTokenContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterOffsetDateTimeValueToken(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitOffsetDateTimeValueToken(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitOffsetDateTimeValueToken(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class MultipleValueTokenContext extends ValueTokenContext {
		public VariadicValueTokensContext values;
		public TerminalNode MULTIPLE_OPENING() { return getToken(EvitaQLParser.MULTIPLE_OPENING, 0); }
		public TerminalNode MULTIPLE_CLOSING() { return getToken(EvitaQLParser.MULTIPLE_CLOSING, 0); }
		public VariadicValueTokensContext variadicValueTokens() {
			return getRuleContext(VariadicValueTokensContext.class,0);
		}
		public MultipleValueTokenContext(ValueTokenContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterMultipleValueToken(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitMultipleValueToken(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitMultipleValueToken(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class EnumValueTokenContext extends ValueTokenContext {
		public TerminalNode ENUM() { return getToken(EvitaQLParser.ENUM, 0); }
		public EnumValueTokenContext(ValueTokenContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterEnumValueToken(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitEnumValueToken(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitEnumValueToken(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class StringValueTokenContext extends ValueTokenContext {
		public TerminalNode STRING() { return getToken(EvitaQLParser.STRING, 0); }
		public StringValueTokenContext(ValueTokenContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterStringValueToken(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitStringValueToken(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitStringValueToken(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class DateValueTokenContext extends ValueTokenContext {
		public TerminalNode DATE() { return getToken(EvitaQLParser.DATE, 0); }
		public DateValueTokenContext(ValueTokenContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterDateValueToken(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitDateValueToken(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitDateValueToken(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class PositionalParameterValueTokenContext extends ValueTokenContext {
		public PositionalParameterContext positionalParameter() {
			return getRuleContext(PositionalParameterContext.class,0);
		}
		public PositionalParameterValueTokenContext(ValueTokenContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterPositionalParameterValueToken(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitPositionalParameterValueToken(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitPositionalParameterValueToken(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class FloatValueTokenContext extends ValueTokenContext {
		public TerminalNode FLOAT() { return getToken(EvitaQLParser.FLOAT, 0); }
		public FloatValueTokenContext(ValueTokenContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFloatValueToken(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFloatValueToken(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFloatValueToken(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ValueTokenContext valueToken() throws RecognitionException {
		ValueTokenContext _localctx = new ValueTokenContext(_ctx, getState());
		enterRule(_localctx, 128, RULE_valueToken);
		try {
			setState(905);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case POSITIONAL_PARAMETER:
				_localctx = new PositionalParameterValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(887);
				positionalParameter();
				}
				break;
			case NAMED_PARAMETER:
				_localctx = new NamedParameterValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(888);
				namedParameter();
				}
				break;
			case STRING:
				_localctx = new StringValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(889);
				match(STRING);
				}
				break;
			case INT:
				_localctx = new IntValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 4);
				{
				setState(890);
				match(INT);
				}
				break;
			case FLOAT:
				_localctx = new FloatValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 5);
				{
				setState(891);
				match(FLOAT);
				}
				break;
			case BOOLEAN:
				_localctx = new BooleanValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 6);
				{
				setState(892);
				match(BOOLEAN);
				}
				break;
			case DATE:
				_localctx = new DateValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 7);
				{
				setState(893);
				match(DATE);
				}
				break;
			case TIME:
				_localctx = new TimeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 8);
				{
				setState(894);
				match(TIME);
				}
				break;
			case DATE_TIME:
				_localctx = new DateTimeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 9);
				{
				setState(895);
				match(DATE_TIME);
				}
				break;
			case OFFSET_DATE_TIME:
				_localctx = new OffsetDateTimeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 10);
				{
				setState(896);
				match(OFFSET_DATE_TIME);
				}
				break;
			case FLOAT_NUMBER_RANGE:
				_localctx = new FloatNumberRangeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 11);
				{
				setState(897);
				match(FLOAT_NUMBER_RANGE);
				}
				break;
			case INT_NUMBER_RANGE:
				_localctx = new IntNumberRangeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 12);
				{
				setState(898);
				match(INT_NUMBER_RANGE);
				}
				break;
			case DATE_TIME_RANGE:
				_localctx = new DateTimeRangeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 13);
				{
				setState(899);
				match(DATE_TIME_RANGE);
				}
				break;
			case ENUM:
				_localctx = new EnumValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 14);
				{
				setState(900);
				match(ENUM);
				}
				break;
			case MULTIPLE_OPENING:
				_localctx = new MultipleValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 15);
				{
				setState(901);
				match(MULTIPLE_OPENING);
				setState(902);
				((MultipleValueTokenContext)_localctx).values = variadicValueTokens();
				setState(903);
				match(MULTIPLE_CLOSING);
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

	public static final String _serializedATN =
		"\3\u608b\ua72a\u8133\ub9ed\u417c\u3be7\u7786\u5964\3f\u038e\4\2\t\2\4"+
		"\3\t\3\4\4\t\4\4\5\t\5\4\6\t\6\4\7\t\7\4\b\t\b\4\t\t\t\4\n\t\n\4\13\t"+
		"\13\4\f\t\f\4\r\t\r\4\16\t\16\4\17\t\17\4\20\t\20\4\21\t\21\4\22\t\22"+
		"\4\23\t\23\4\24\t\24\4\25\t\25\4\26\t\26\4\27\t\27\4\30\t\30\4\31\t\31"+
		"\4\32\t\32\4\33\t\33\4\34\t\34\4\35\t\35\4\36\t\36\4\37\t\37\4 \t \4!"+
		"\t!\4\"\t\"\4#\t#\4$\t$\4%\t%\4&\t&\4\'\t\'\4(\t(\4)\t)\4*\t*\4+\t+\4"+
		",\t,\4-\t-\4.\t.\4/\t/\4\60\t\60\4\61\t\61\4\62\t\62\4\63\t\63\4\64\t"+
		"\64\4\65\t\65\4\66\t\66\4\67\t\67\48\t8\49\t9\4:\t:\4;\t;\4<\t<\4=\t="+
		"\4>\t>\4?\t?\4@\t@\4A\tA\4B\tB\3\2\3\2\3\2\3\3\3\3\3\3\3\4\3\4\3\4\3\5"+
		"\3\5\3\5\3\6\3\6\3\6\3\7\3\7\3\7\3\b\3\b\3\b\3\t\3\t\3\t\3\n\3\n\3\n\3"+
		"\n\5\n\u00a1\n\n\3\13\3\13\3\13\3\f\3\f\3\f\3\f\3\f\5\f\u00ab\n\f\3\f"+
		"\3\f\3\f\5\f\u00b0\n\f\3\f\3\f\3\f\3\f\3\f\5\f\u00b7\n\f\3\f\3\f\3\f\3"+
		"\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f"+
		"\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3"+
		"\f\3\f\3\f\5\f\u00e2\n\f\3\f\3\f\3\f\3\f\3\f\5\f\u00e9\n\f\3\f\3\f\3\f"+
		"\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3"+
		"\f\3\f\5\f\u0101\n\f\3\r\3\r\3\r\5\r\u0106\n\r\3\r\3\r\3\r\3\r\3\r\5\r"+
		"\u010d\n\r\3\r\3\r\3\r\3\r\3\r\3\r\5\r\u0115\n\r\3\16\3\16\3\16\5\16\u011a"+
		"\n\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16\5\16\u0123\n\16\3\16\3\16\3\16"+
		"\5\16\u0128\n\16\3\16\3\16\3\16\5\16\u012d\n\16\3\16\3\16\3\16\5\16\u0132"+
		"\n\16\3\16\3\16\3\16\3\16\3\16\5\16\u0139\n\16\3\16\3\16\3\16\5\16\u013e"+
		"\n\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16"+
		"\3\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16\5\16\u0155\n\16\3\16\3\16\3\16"+
		"\5\16\u015a\n\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16"+
		"\3\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16"+
		"\3\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16"+
		"\3\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16\3\16\5\16\u018c\n\16\3\17\3\17"+
		"\3\17\7\17\u0191\n\17\f\17\16\17\u0194\13\17\3\20\3\20\3\20\7\20\u0199"+
		"\n\20\f\20\16\20\u019c\13\20\3\21\3\21\3\21\7\21\u01a1\n\21\f\21\16\21"+
		"\u01a4\13\21\3\22\3\22\3\22\7\22\u01a9\n\22\f\22\16\22\u01ac\13\22\3\23"+
		"\3\23\3\23\3\23\7\23\u01b2\n\23\f\23\16\23\u01b5\13\23\3\23\3\23\3\24"+
		"\3\24\3\24\3\25\3\25\3\25\3\25\7\25\u01c0\n\25\f\25\16\25\u01c3\13\25"+
		"\3\25\3\25\3\26\3\26\3\26\3\26\3\27\3\27\3\27\3\27\7\27\u01cf\n\27\f\27"+
		"\16\27\u01d2\13\27\3\27\3\27\3\30\3\30\3\30\3\30\3\31\3\31\3\31\3\31\7"+
		"\31\u01de\n\31\f\31\16\31\u01e1\13\31\3\31\3\31\3\32\3\32\3\32\3\32\3"+
		"\33\3\33\3\33\3\33\3\33\3\33\3\34\3\34\3\34\3\34\5\34\u01f3\n\34\3\34"+
		"\3\34\3\35\3\35\3\35\3\35\3\35\3\35\3\36\3\36\3\36\3\36\3\36\3\36\3\36"+
		"\3\36\3\37\3\37\3\37\3\37\3 \3 \3 \3 \3!\3!\3!\3!\3!\3!\3\"\3\"\3\"\3"+
		"\"\3#\3#\3#\3#\3#\3#\3$\3$\3$\3$\3$\3$\3%\3%\3%\3%\6%\u0227\n%\r%\16%"+
		"\u0228\3%\3%\3&\3&\3&\3&\7&\u0231\n&\f&\16&\u0234\13&\3&\3&\3\'\3\'\3"+
		"\'\3\'\3\'\3\'\7\'\u023e\n\'\f\'\16\'\u0241\13\'\3\'\3\'\3(\3(\3(\3(\7"+
		"(\u0249\n(\f(\16(\u024c\13(\3(\3(\3)\3)\3)\3)\3)\7)\u0255\n)\f)\16)\u0258"+
		"\13)\5)\u025a\n)\3)\3)\3*\3*\3*\3*\7*\u0262\n*\f*\16*\u0265\13*\3*\3*"+
		"\3+\3+\3+\3+\3+\3+\3,\3,\3,\3,\3,\3,\3-\3-\3-\3-\5-\u0279\n-\3-\3-\3-"+
		"\3-\3-\3-\5-\u0281\n-\3-\3-\3.\3.\3.\3.\3.\3.\5.\u028b\n.\3.\3.\3.\3."+
		"\3.\3.\3.\3.\5.\u0295\n.\3.\3.\3/\3/\3/\3/\5/\u029d\n/\3/\3/\5/\u02a1"+
		"\n/\3/\3/\3/\5/\u02a6\n/\3/\3/\3/\3/\3/\5/\u02ad\n/\3/\3/\3\60\3\60\3"+
		"\60\3\60\3\60\3\60\3\60\3\60\5\60\u02b9\n\60\3\60\3\60\3\60\3\60\3\60"+
		"\3\60\3\60\3\60\3\60\3\60\5\60\u02c5\n\60\3\60\3\60\3\61\3\61\3\61\3\61"+
		"\5\61\u02cd\n\61\3\61\3\61\3\61\3\61\3\61\3\61\5\61\u02d5\n\61\3\61\3"+
		"\61\3\62\3\62\3\62\3\62\3\62\3\62\5\62\u02df\n\62\3\62\3\62\3\63\3\63"+
		"\3\63\3\63\3\64\3\64\3\64\3\64\3\64\3\64\3\65\3\65\3\65\3\65\3\65\3\65"+
		"\3\65\3\65\3\65\3\65\3\65\3\65\5\65\u02f9\n\65\3\65\3\65\3\66\3\66\3\66"+
		"\3\66\3\66\3\66\3\66\3\66\3\66\3\66\3\66\3\66\3\66\3\66\3\66\3\66\3\66"+
		"\3\66\3\66\3\66\5\66\u0311\n\66\3\66\3\66\3\67\3\67\3\67\3\67\5\67\u0319"+
		"\n\67\3\67\3\67\38\38\38\38\78\u0321\n8\f8\168\u0324\138\38\38\39\39\3"+
		"9\39\39\39\79\u032e\n9\f9\169\u0331\139\39\39\3:\3:\3:\3:\6:\u0339\n:"+
		"\r:\16:\u033a\3:\3:\3;\3;\3;\3;\3;\3;\6;\u0345\n;\r;\16;\u0346\3;\3;\3"+
		"<\3<\3<\3<\3<\3<\3<\3<\6<\u0353\n<\r<\16<\u0354\3<\3<\3=\3=\3>\3>\3?\3"+
		"?\3?\3?\3?\7?\u0362\n?\f?\16?\u0365\13?\5?\u0367\n?\3@\3@\3@\5@\u036c"+
		"\n@\3A\3A\3A\3A\3A\7A\u0373\nA\fA\16A\u0376\13A\5A\u0378\nA\3B\3B\3B\3"+
		"B\3B\3B\3B\3B\3B\3B\3B\3B\3B\3B\3B\3B\3B\3B\5B\u038c\nB\3B\2\2C\2\4\6"+
		"\b\n\f\16\20\22\24\26\30\32\34\36 \"$&(*,.\60\62\64\668:<>@BDFHJLNPRT"+
		"VXZ\\^`bdfhjlnprtvxz|~\u0080\u0082\2\2\2\u03f3\2\u0084\3\2\2\2\4\u0087"+
		"\3\2\2\2\6\u008a\3\2\2\2\b\u008d\3\2\2\2\n\u0090\3\2\2\2\f\u0093\3\2\2"+
		"\2\16\u0096\3\2\2\2\20\u0099\3\2\2\2\22\u00a0\3\2\2\2\24\u00a2\3\2\2\2"+
		"\26\u0100\3\2\2\2\30\u0114\3\2\2\2\32\u018b\3\2\2\2\34\u018d\3\2\2\2\36"+
		"\u0195\3\2\2\2 \u019d\3\2\2\2\"\u01a5\3\2\2\2$\u01ad\3\2\2\2&\u01b8\3"+
		"\2\2\2(\u01bb\3\2\2\2*\u01c6\3\2\2\2,\u01ca\3\2\2\2.\u01d5\3\2\2\2\60"+
		"\u01d9\3\2\2\2\62\u01e4\3\2\2\2\64\u01e8\3\2\2\2\66\u01ee\3\2\2\28\u01f6"+
		"\3\2\2\2:\u01fc\3\2\2\2<\u0204\3\2\2\2>\u0208\3\2\2\2@\u020c\3\2\2\2B"+
		"\u0212\3\2\2\2D\u0216\3\2\2\2F\u021c\3\2\2\2H\u0222\3\2\2\2J\u022c\3\2"+
		"\2\2L\u0237\3\2\2\2N\u0244\3\2\2\2P\u024f\3\2\2\2R\u025d\3\2\2\2T\u0268"+
		"\3\2\2\2V\u026e\3\2\2\2X\u0274\3\2\2\2Z\u0284\3\2\2\2\\\u0298\3\2\2\2"+
		"^\u02b0\3\2\2\2`\u02c8\3\2\2\2b\u02d8\3\2\2\2d\u02e2\3\2\2\2f\u02e6\3"+
		"\2\2\2h\u02ec\3\2\2\2j\u02fc\3\2\2\2l\u0314\3\2\2\2n\u031c\3\2\2\2p\u0327"+
		"\3\2\2\2r\u0334\3\2\2\2t\u033e\3\2\2\2v\u034a\3\2\2\2x\u0358\3\2\2\2z"+
		"\u035a\3\2\2\2|\u0366\3\2\2\2~\u036b\3\2\2\2\u0080\u0377\3\2\2\2\u0082"+
		"\u038b\3\2\2\2\u0084\u0085\5\20\t\2\u0085\u0086\7\2\2\3\u0086\3\3\2\2"+
		"\2\u0087\u0088\5\34\17\2\u0088\u0089\7\2\2\3\u0089\5\3\2\2\2\u008a\u008b"+
		"\5\36\20\2\u008b\u008c\7\2\2\3\u008c\7\3\2\2\2\u008d\u008e\5 \21\2\u008e"+
		"\u008f\7\2\2\3\u008f\t\3\2\2\2\u0090\u0091\5\"\22\2\u0091\u0092\7\2\2"+
		"\3\u0092\13\3\2\2\2\u0093\u0094\5~@\2\u0094\u0095\7\2\2\3\u0095\r\3\2"+
		"\2\2\u0096\u0097\5\u0082B\2\u0097\u0098\7\2\2\3\u0098\17\3\2\2\2\u0099"+
		"\u009a\7\3\2\2\u009a\u009b\5$\23\2\u009b\21\3\2\2\2\u009c\u00a1\5\24\13"+
		"\2\u009d\u00a1\5\26\f\2\u009e\u00a1\5\30\r\2\u009f\u00a1\5\32\16\2\u00a0"+
		"\u009c\3\2\2\2\u00a0\u009d\3\2\2\2\u00a0\u009e\3\2\2\2\u00a0\u009f\3\2"+
		"\2\2\u00a1\23\3\2\2\2\u00a2\u00a3\7\4\2\2\u00a3\u00a4\5\62\32\2\u00a4"+
		"\25\3\2\2\2\u00a5\u00a6\7\5\2\2\u00a6\u0101\5(\25\2\u00a7\u00aa\7\6\2"+
		"\2\u00a8\u00ab\5&\24\2\u00a9\u00ab\5(\25\2\u00aa\u00a8\3\2\2\2\u00aa\u00a9"+
		"\3\2\2\2\u00ab\u0101\3\2\2\2\u00ac\u00af\7\7\2\2\u00ad\u00b0\5&\24\2\u00ae"+
		"\u00b0\5(\25\2\u00af\u00ad\3\2\2\2\u00af\u00ae\3\2\2\2\u00b0\u0101\3\2"+
		"\2\2\u00b1\u00b2\7\b\2\2\u00b2\u0101\5*\26\2\u00b3\u00b6\7\t\2\2\u00b4"+
		"\u00b7\5&\24\2\u00b5\u00b7\5(\25\2\u00b6\u00b4\3\2\2\2\u00b6\u00b5\3\2"+
		"\2\2\u00b7\u0101\3\2\2\2\u00b8\u00b9\7\n\2\2\u00b9\u0101\5\64\33\2\u00ba"+
		"\u00bb\7\13\2\2\u00bb\u0101\5\64\33\2\u00bc\u00bd\7\f\2\2\u00bd\u0101"+
		"\5\64\33\2\u00be\u00bf\7\r\2\2\u00bf\u0101\5\64\33\2\u00c0\u00c1\7\16"+
		"\2\2\u00c1\u0101\5\64\33\2\u00c2\u00c3\7\17\2\2\u00c3\u0101\5:\36\2\u00c4"+
		"\u00c5\7\20\2\2\u00c5\u0101\58\35\2\u00c6\u00c7\7\21\2\2\u00c7\u0101\5"+
		"\64\33\2\u00c8\u00c9\7\22\2\2\u00c9\u0101\5\64\33\2\u00ca\u00cb\7\23\2"+
		"\2\u00cb\u0101\5\64\33\2\u00cc\u00cd\7\24\2\2\u00cd\u0101\5\62\32\2\u00ce"+
		"\u00cf\7\25\2\2\u00cf\u0101\5\62\32\2\u00d0\u00d1\7\26\2\2\u00d1\u0101"+
		"\5\64\33\2\u00d2\u00d3\7\27\2\2\u00d3\u0101\5\62\32\2\u00d4\u00d5\7\30"+
		"\2\2\u00d5\u0101\5\62\32\2\u00d6\u00d7\7\31\2\2\u00d7\u0101\5\64\33\2"+
		"\u00d8\u00d9\7\32\2\2\u00d9\u0101\5> \2\u00da\u00db\7\33\2\2\u00db\u0101"+
		"\5<\37\2\u00dc\u00dd\7\34\2\2\u00dd\u0101\5<\37\2\u00de\u00e1\7\35\2\2"+
		"\u00df\u00e2\5&\24\2\u00e0\u00e2\5B\"\2\u00e1\u00df\3\2\2\2\u00e1\u00e0"+
		"\3\2\2\2\u00e2\u0101\3\2\2\2\u00e3\u00e4\7\36\2\2\u00e4\u0101\5&\24\2"+
		"\u00e5\u00e8\7\37\2\2\u00e6\u00e9\5&\24\2\u00e7\u00e9\5<\37\2\u00e8\u00e6"+
		"\3\2\2\2\u00e8\u00e7\3\2\2\2\u00e9\u0101\3\2\2\2\u00ea\u00eb\7 \2\2\u00eb"+
		"\u0101\5@!\2\u00ec\u00ed\7!\2\2\u00ed\u0101\5F$\2\u00ee\u00ef\7\"\2\2"+
		"\u00ef\u0101\5F$\2\u00f0\u00f1\7#\2\2\u00f1\u0101\5L\'\2\u00f2\u00f3\7"+
		"$\2\2\u00f3\u0101\5N(\2\u00f4\u00f5\7%\2\2\u00f5\u0101\5P)\2\u00f6\u00f7"+
		"\7&\2\2\u00f7\u0101\5R*\2\u00f8\u00f9\7\'\2\2\u00f9\u0101\5&\24\2\u00fa"+
		"\u00fb\7(\2\2\u00fb\u0101\5&\24\2\u00fc\u00fd\7)\2\2\u00fd\u0101\5(\25"+
		"\2\u00fe\u00ff\7*\2\2\u00ff\u0101\5*\26\2\u0100\u00a5\3\2\2\2\u0100\u00a7"+
		"\3\2\2\2\u0100\u00ac\3\2\2\2\u0100\u00b1\3\2\2\2\u0100\u00b3\3\2\2\2\u0100"+
		"\u00b8\3\2\2\2\u0100\u00ba\3\2\2\2\u0100\u00bc\3\2\2\2\u0100\u00be\3\2"+
		"\2\2\u0100\u00c0\3\2\2\2\u0100\u00c2\3\2\2\2\u0100\u00c4\3\2\2\2\u0100"+
		"\u00c6\3\2\2\2\u0100\u00c8\3\2\2\2\u0100\u00ca\3\2\2\2\u0100\u00cc\3\2"+
		"\2\2\u0100\u00ce\3\2\2\2\u0100\u00d0\3\2\2\2\u0100\u00d2\3\2\2\2\u0100"+
		"\u00d4\3\2\2\2\u0100\u00d6\3\2\2\2\u0100\u00d8\3\2\2\2\u0100\u00da\3\2"+
		"\2\2\u0100\u00dc\3\2\2\2\u0100\u00de\3\2\2\2\u0100\u00e3\3\2\2\2\u0100"+
		"\u00e5\3\2\2\2\u0100\u00ea\3\2\2\2\u0100\u00ec\3\2\2\2\u0100\u00ee\3\2"+
		"\2\2\u0100\u00f0\3\2\2\2\u0100\u00f2\3\2\2\2\u0100\u00f4\3\2\2\2\u0100"+
		"\u00f6\3\2\2\2\u0100\u00f8\3\2\2\2\u0100\u00fa\3\2\2\2\u0100\u00fc\3\2"+
		"\2\2\u0100\u00fe\3\2\2\2\u0101\27\3\2\2\2\u0102\u0105\7+\2\2\u0103\u0106"+
		"\5&\24\2\u0104\u0106\5,\27\2\u0105\u0103\3\2\2\2\u0105\u0104\3\2\2\2\u0106"+
		"\u0115\3\2\2\2\u0107\u0108\7,\2\2\u0108\u0115\5\66\34\2\u0109\u010c\7"+
		"-\2\2\u010a\u010d\5&\24\2\u010b\u010d\5<\37\2\u010c\u010a\3\2\2\2\u010c"+
		"\u010b\3\2\2\2\u010d\u0115\3\2\2\2\u010e\u010f\7.\2\2\u010f\u0115\5&\24"+
		"\2\u0110\u0111\7/\2\2\u0111\u0115\5H%\2\u0112\u0113\7\60\2\2\u0113\u0115"+
		"\5,\27\2\u0114\u0102\3\2\2\2\u0114\u0107\3\2\2\2\u0114\u0109\3\2\2\2\u0114"+
		"\u010e\3\2\2\2\u0114\u0110\3\2\2\2\u0114\u0112\3\2\2\2\u0115\31\3\2\2"+
		"\2\u0116\u0119\7\61\2\2\u0117\u011a\5&\24\2\u0118\u011a\5\60\31\2\u0119"+
		"\u0117\3\2\2\2\u0119\u0118\3\2\2\2\u011a\u018c\3\2\2\2\u011b\u011c\7\62"+
		"\2\2\u011c\u018c\5T+\2\u011d\u011e\7\63\2\2\u011e\u018c\5V,\2\u011f\u0122"+
		"\7\64\2\2\u0120\u0123\5&\24\2\u0121\u0123\5\60\31\2\u0122\u0120\3\2\2"+
		"\2\u0122\u0121\3\2\2\2\u0123\u018c\3\2\2\2\u0124\u0127\7\65\2\2\u0125"+
		"\u0128\5&\24\2\u0126\u0128\5\60\31\2\u0127\u0125\3\2\2\2\u0127\u0126\3"+
		"\2\2\2\u0128\u018c\3\2\2\2\u0129\u012c\7\66\2\2\u012a\u012d\5&\24\2\u012b"+
		"\u012d\5B\"\2\u012c\u012a\3\2\2\2\u012c\u012b\3\2\2\2\u012d\u018c\3\2"+
		"\2\2\u012e\u0131\7\67\2\2\u012f\u0132\5&\24\2\u0130\u0132\5> \2\u0131"+
		"\u012f\3\2\2\2\u0131\u0130\3\2\2\2\u0132\u018c\3\2\2\2\u0133\u0134\78"+
		"\2\2\u0134\u018c\5&\24\2\u0135\u0138\79\2\2\u0136\u0139\5&\24\2\u0137"+
		"\u0139\5B\"\2\u0138\u0136\3\2\2\2\u0138\u0137\3\2\2\2\u0139\u018c\3\2"+
		"\2\2\u013a\u013d\7:\2\2\u013b\u013e\5&\24\2\u013c\u013e\5b\62\2\u013d"+
		"\u013b\3\2\2\2\u013d\u013c\3\2\2\2\u013e\u018c\3\2\2\2\u013f\u0140\7:"+
		"\2\2\u0140\u018c\5`\61\2\u0141\u0142\7:\2\2\u0142\u018c\5X-\2\u0143\u0144"+
		"\7:\2\2\u0144\u018c\5Z.\2\u0145\u0146\7:\2\2\u0146\u018c\5\\/\2\u0147"+
		"\u0148\7:\2\2\u0148\u018c\5^\60\2\u0149\u014a\7;\2\2\u014a\u018c\5&\24"+
		"\2\u014b\u014c\7;\2\2\u014c\u018c\5d\63\2\u014d\u014e\7;\2\2\u014e\u018c"+
		"\5f\64\2\u014f\u0150\7<\2\2\u0150\u018c\5<\37\2\u0151\u0154\7=\2\2\u0152"+
		"\u0155\5&\24\2\u0153\u0155\5> \2\u0154\u0152\3\2\2\2\u0154\u0153\3\2\2"+
		"\2\u0155\u018c\3\2\2\2\u0156\u0159\7>\2\2\u0157\u015a\5&\24\2\u0158\u015a"+
		"\5h\65\2\u0159\u0157\3\2\2\2\u0159\u0158\3\2\2\2\u015a\u018c\3\2\2\2\u015b"+
		"\u015c\7?\2\2\u015c\u018c\5j\66\2\u015d\u015e\7@\2\2\u015e\u018c\5F$\2"+
		"\u015f\u0160\7A\2\2\u0160\u018c\5F$\2\u0161\u0162\7B\2\2\u0162\u018c\5"+
		"F$\2\u0163\u0164\7C\2\2\u0164\u018c\5D#\2\u0165\u0166\7D\2\2\u0166\u018c"+
		"\5<\37\2\u0167\u0168\7E\2\2\u0168\u018c\5<\37\2\u0169\u016a\7F\2\2\u016a"+
		"\u018c\5<\37\2\u016b\u016c\7G\2\2\u016c\u018c\5*\26\2\u016d\u016e\7H\2"+
		"\2\u016e\u018c\5.\30\2\u016f\u0170\7I\2\2\u0170\u018c\5&\24\2\u0171\u0172"+
		"\7I\2\2\u0172\u018c\5l\67\2\u0173\u0174\7J\2\2\u0174\u018c\5n8\2\u0175"+
		"\u0176\7K\2\2\u0176\u018c\5p9\2\u0177\u0178\7L\2\2\u0178\u018c\5n8\2\u0179"+
		"\u017a\7M\2\2\u017a\u018c\5&\24\2\u017b\u017c\7M\2\2\u017c\u018c\5\60"+
		"\31\2\u017d\u017e\7M\2\2\u017e\u018c\5n8\2\u017f\u0180\7N\2\2\u0180\u018c"+
		"\5n8\2\u0181\u0182\7O\2\2\u0182\u018c\5\60\31\2\u0183\u0184\7O\2\2\u0184"+
		"\u018c\5r:\2\u0185\u0186\7P\2\2\u0186\u018c\5t;\2\u0187\u0188\7P\2\2\u0188"+
		"\u018c\5v<\2\u0189\u018a\7Q\2\2\u018a\u018c\5&\24\2\u018b\u0116\3\2\2"+
		"\2\u018b\u011b\3\2\2\2\u018b\u011d\3\2\2\2\u018b\u011f\3\2\2\2\u018b\u0124"+
		"\3\2\2\2\u018b\u0129\3\2\2\2\u018b\u012e\3\2\2\2\u018b\u0133\3\2\2\2\u018b"+
		"\u0135\3\2\2\2\u018b\u013a\3\2\2\2\u018b\u013f\3\2\2\2\u018b\u0141\3\2"+
		"\2\2\u018b\u0143\3\2\2\2\u018b\u0145\3\2\2\2\u018b\u0147\3\2\2\2\u018b"+
		"\u0149\3\2\2\2\u018b\u014b\3\2\2\2\u018b\u014d\3\2\2\2\u018b\u014f\3\2"+
		"\2\2\u018b\u0151\3\2\2\2\u018b\u0156\3\2\2\2\u018b\u015b\3\2\2\2\u018b"+
		"\u015d\3\2\2\2\u018b\u015f\3\2\2\2\u018b\u0161\3\2\2\2\u018b\u0163\3\2"+
		"\2\2\u018b\u0165\3\2\2\2\u018b\u0167\3\2\2\2\u018b\u0169\3\2\2\2\u018b"+
		"\u016b\3\2\2\2\u018b\u016d\3\2\2\2\u018b\u016f\3\2\2\2\u018b\u0171\3\2"+
		"\2\2\u018b\u0173\3\2\2\2\u018b\u0175\3\2\2\2\u018b\u0177\3\2\2\2\u018b"+
		"\u0179\3\2\2\2\u018b\u017b\3\2\2\2\u018b\u017d\3\2\2\2\u018b\u017f\3\2"+
		"\2\2\u018b\u0181\3\2\2\2\u018b\u0183\3\2\2\2\u018b\u0185\3\2\2\2\u018b"+
		"\u0187\3\2\2\2\u018b\u0189\3\2\2\2\u018c\33\3\2\2\2\u018d\u0192\5\24\13"+
		"\2\u018e\u018f\7b\2\2\u018f\u0191\5\24\13\2\u0190\u018e\3\2\2\2\u0191"+
		"\u0194\3\2\2\2\u0192\u0190\3\2\2\2\u0192\u0193\3\2\2\2\u0193\35\3\2\2"+
		"\2\u0194\u0192\3\2\2\2\u0195\u019a\5\26\f\2\u0196\u0197\7b\2\2\u0197\u0199"+
		"\5\26\f\2\u0198\u0196\3\2\2\2\u0199\u019c\3\2\2\2\u019a\u0198\3\2\2\2"+
		"\u019a\u019b\3\2\2\2\u019b\37\3\2\2\2\u019c\u019a\3\2\2\2\u019d\u01a2"+
		"\5\30\r\2\u019e\u019f\7b\2\2\u019f\u01a1\5\30\r\2\u01a0\u019e\3\2\2\2"+
		"\u01a1\u01a4\3\2\2\2\u01a2\u01a0\3\2\2\2\u01a2\u01a3\3\2\2\2\u01a3!\3"+
		"\2\2\2\u01a4\u01a2\3\2\2\2\u01a5\u01aa\5\32\16\2\u01a6\u01a7\7b\2\2\u01a7"+
		"\u01a9\5\32\16\2\u01a8\u01a6\3\2\2\2\u01a9\u01ac\3\2\2\2\u01aa\u01a8\3"+
		"\2\2\2\u01aa\u01ab\3\2\2\2\u01ab#\3\2\2\2\u01ac\u01aa\3\2\2\2\u01ad\u01ae"+
		"\7`\2\2\u01ae\u01b3\5\22\n\2\u01af\u01b0\7b\2\2\u01b0\u01b2\5\22\n\2\u01b1"+
		"\u01af\3\2\2\2\u01b2\u01b5\3\2\2\2\u01b3\u01b1\3\2\2\2\u01b3\u01b4\3\2"+
		"\2\2\u01b4\u01b6\3\2\2\2\u01b5\u01b3\3\2\2\2\u01b6\u01b7\7a\2\2\u01b7"+
		"%\3\2\2\2\u01b8\u01b9\7`\2\2\u01b9\u01ba\7a\2\2\u01ba\'\3\2\2\2\u01bb"+
		"\u01bc\7`\2\2\u01bc\u01c1\5\26\f\2\u01bd\u01be\7b\2\2\u01be\u01c0\5\26"+
		"\f\2\u01bf\u01bd\3\2\2\2\u01c0\u01c3\3\2\2\2\u01c1\u01bf\3\2\2\2\u01c1"+
		"\u01c2\3\2\2\2\u01c2\u01c4\3\2\2\2\u01c3\u01c1\3\2\2\2\u01c4\u01c5\7a"+
		"\2\2\u01c5)\3\2\2\2\u01c6\u01c7\7`\2\2\u01c7\u01c8\5\26\f\2\u01c8\u01c9"+
		"\7a\2\2\u01c9+\3\2\2\2\u01ca\u01cb\7`\2\2\u01cb\u01d0\5\30\r\2\u01cc\u01cd"+
		"\7b\2\2\u01cd\u01cf\5\30\r\2\u01ce\u01cc\3\2\2\2\u01cf\u01d2\3\2\2\2\u01d0"+
		"\u01ce\3\2\2\2\u01d0\u01d1\3\2\2\2\u01d1\u01d3\3\2\2\2\u01d2\u01d0\3\2"+
		"\2\2\u01d3\u01d4\7a\2\2\u01d4-\3\2\2\2\u01d5\u01d6\7`\2\2\u01d6\u01d7"+
		"\5\32\16\2\u01d7\u01d8\7a\2\2\u01d8/\3\2\2\2\u01d9\u01da\7`\2\2\u01da"+
		"\u01df\5\32\16\2\u01db\u01dc\7b\2\2\u01dc\u01de\5\32\16\2\u01dd\u01db"+
		"\3\2\2\2\u01de\u01e1\3\2\2\2\u01df\u01dd\3\2\2\2\u01df\u01e0\3\2\2\2\u01e0"+
		"\u01e2\3\2\2\2\u01e1\u01df\3\2\2\2\u01e2\u01e3\7a\2\2\u01e3\61\3\2\2\2"+
		"\u01e4\u01e5\7`\2\2\u01e5\u01e6\5~@\2\u01e6\u01e7\7a\2\2\u01e7\63\3\2"+
		"\2\2\u01e8\u01e9\7`\2\2\u01e9\u01ea\5~@\2\u01ea\u01eb\7b\2\2\u01eb\u01ec"+
		"\5\u0082B\2\u01ec\u01ed\7a\2\2\u01ed\65\3\2\2\2\u01ee\u01ef\7`\2\2\u01ef"+
		"\u01f2\5~@\2\u01f0\u01f1\7b\2\2\u01f1\u01f3\5\u0082B\2\u01f2\u01f0\3\2"+
		"\2\2\u01f2\u01f3\3\2\2\2\u01f3\u01f4\3\2\2\2\u01f4\u01f5\7a\2\2\u01f5"+
		"\67\3\2\2\2\u01f6\u01f7\7`\2\2\u01f7\u01f8\5~@\2\u01f8\u01f9\7b\2\2\u01f9"+
		"\u01fa\5\u0080A\2\u01fa\u01fb\7a\2\2\u01fb9\3\2\2\2\u01fc\u01fd\7`\2\2"+
		"\u01fd\u01fe\5~@\2\u01fe\u01ff\7b\2\2\u01ff\u0200\5\u0082B\2\u0200\u0201"+
		"\7b\2\2\u0201\u0202\5\u0082B\2\u0202\u0203\7a\2\2\u0203;\3\2\2\2\u0204"+
		"\u0205\7`\2\2\u0205\u0206\5\u0082B\2\u0206\u0207\7a\2\2\u0207=\3\2\2\2"+
		"\u0208\u0209\7`\2\2\u0209\u020a\5\u0080A\2\u020a\u020b\7a\2\2\u020b?\3"+
		"\2\2\2\u020c\u020d\7`\2\2\u020d\u020e\5\u0082B\2\u020e\u020f\7b\2\2\u020f"+
		"\u0210\5\u0082B\2\u0210\u0211\7a\2\2\u0211A\3\2\2\2\u0212\u0213\7`\2\2"+
		"\u0213\u0214\5|?\2\u0214\u0215\7a\2\2\u0215C\3\2\2\2\u0216\u0217\7`\2"+
		"\2\u0217\u0218\5\u0082B\2\u0218\u0219\7b\2\2\u0219\u021a\5|?\2\u021a\u021b"+
		"\7a\2\2\u021bE\3\2\2\2\u021c\u021d\7`\2\2\u021d\u021e\5~@\2\u021e\u021f"+
		"\7b\2\2\u021f\u0220\5\26\f\2\u0220\u0221\7a\2\2\u0221G\3\2\2\2\u0222\u0223"+
		"\7`\2\2\u0223\u0226\5~@\2\u0224\u0225\7b\2\2\u0225\u0227\5\30\r\2\u0226"+
		"\u0224\3\2\2\2\u0227\u0228\3\2\2\2\u0228\u0226\3\2\2\2\u0228\u0229\3\2"+
		"\2\2\u0229\u022a\3\2\2\2\u022a\u022b\7a\2\2\u022bI\3\2\2\2\u022c\u022d"+
		"\7`\2\2\u022d\u0232\5\u0082B\2\u022e\u022f\7b\2\2\u022f\u0231\5\32\16"+
		"\2\u0230\u022e\3\2\2\2\u0231\u0234\3\2\2\2\u0232\u0230\3\2\2\2\u0232\u0233"+
		"\3\2\2\2\u0233\u0235\3\2\2\2\u0234\u0232\3\2\2\2\u0235\u0236\7a\2\2\u0236"+
		"K\3\2\2\2\u0237\u0238\7`\2\2\u0238\u0239\5~@\2\u0239\u023a\7b\2\2\u023a"+
		"\u023f\5\26\f\2\u023b\u023c\7b\2\2\u023c\u023e\5\26\f\2\u023d\u023b\3"+
		"\2\2\2\u023e\u0241\3\2\2\2\u023f\u023d\3\2\2\2\u023f\u0240\3\2\2\2\u0240"+
		"\u0242\3\2\2\2\u0241\u023f\3\2\2\2\u0242\u0243\7a\2\2\u0243M\3\2\2\2\u0244"+
		"\u0245\7`\2\2\u0245\u024a\5\26\f\2\u0246\u0247\7b\2\2\u0247\u0249\5\26"+
		"\f\2\u0248\u0246\3\2\2\2\u0249\u024c\3\2\2\2\u024a\u0248\3\2\2\2\u024a"+
		"\u024b\3\2\2\2\u024b\u024d\3\2\2\2\u024c\u024a\3\2\2\2\u024d\u024e\7a"+
		"\2\2\u024eO\3\2\2\2\u024f\u0259\7`\2\2\u0250\u025a\5~@\2\u0251\u0256\5"+
		"~@\2\u0252\u0253\7b\2\2\u0253\u0255\5\26\f\2\u0254\u0252\3\2\2\2\u0255"+
		"\u0258\3\2\2\2\u0256\u0254\3\2\2\2\u0256\u0257\3\2\2\2\u0257\u025a\3\2"+
		"\2\2\u0258\u0256\3\2\2\2\u0259\u0250\3\2\2\2\u0259\u0251\3\2\2\2\u025a"+
		"\u025b\3\2\2\2\u025b\u025c\7a\2\2\u025cQ\3\2\2\2\u025d\u025e\7`\2\2\u025e"+
		"\u0263\5\26\f\2\u025f\u0260\7b\2\2\u0260\u0262\5\26\f\2\u0261\u025f\3"+
		"\2\2\2\u0262\u0265\3\2\2\2\u0263\u0261\3\2\2\2\u0263\u0264\3\2\2\2\u0264"+
		"\u0266\3\2\2\2\u0265\u0263\3\2\2\2\u0266\u0267\7a\2\2\u0267S\3\2\2\2\u0268"+
		"\u0269\7`\2\2\u0269\u026a\5\u0082B\2\u026a\u026b\7b\2\2\u026b\u026c\5"+
		"\u0082B\2\u026c\u026d\7a\2\2\u026dU\3\2\2\2\u026e\u026f\7`\2\2\u026f\u0270"+
		"\5\u0082B\2\u0270\u0271\7b\2\2\u0271\u0272\5\u0082B\2\u0272\u0273\7a\2"+
		"\2\u0273W\3\2\2\2\u0274\u0280\7`\2\2\u0275\u0278\5~@\2\u0276\u0277\7b"+
		"\2\2\u0277\u0279\5\32\16\2\u0278\u0276\3\2\2\2\u0278\u0279\3\2\2\2\u0279"+
		"\u0281\3\2\2\2\u027a\u027b\5~@\2\u027b\u027c\7b\2\2\u027c\u027d\5\32\16"+
		"\2\u027d\u027e\7b\2\2\u027e\u027f\5\32\16\2\u027f\u0281\3\2\2\2\u0280"+
		"\u0275\3\2\2\2\u0280\u027a\3\2\2\2\u0281\u0282\3\2\2\2\u0282\u0283\7a"+
		"\2\2\u0283Y\3\2\2\2\u0284\u0294\7`\2\2\u0285\u0286\5~@\2\u0286\u0287\7"+
		"b\2\2\u0287\u028a\5\26\f\2\u0288\u0289\7b\2\2\u0289\u028b\5\32\16\2\u028a"+
		"\u0288\3\2\2\2\u028a\u028b\3\2\2\2\u028b\u0295\3\2\2\2\u028c\u028d\5~"+
		"@\2\u028d\u028e\7b\2\2\u028e\u028f\5\26\f\2\u028f\u0290\7b\2\2\u0290\u0291"+
		"\5\32\16\2\u0291\u0292\7b\2\2\u0292\u0293\5\32\16\2\u0293\u0295\3\2\2"+
		"\2\u0294\u0285\3\2\2\2\u0294\u028c\3\2\2\2\u0295\u0296\3\2\2\2\u0296\u0297"+
		"\7a\2\2\u0297[\3\2\2\2\u0298\u02ac\7`\2\2\u0299\u029c\5~@\2\u029a\u029b"+
		"\7b\2\2\u029b\u029d\5\30\r\2\u029c\u029a\3\2\2\2\u029c\u029d\3\2\2\2\u029d"+
		"\u02a0\3\2\2\2\u029e\u029f\7b\2\2\u029f\u02a1\5\32\16\2\u02a0\u029e\3"+
		"\2\2\2\u02a0\u02a1\3\2\2\2\u02a1\u02ad\3\2\2\2\u02a2\u02a5\5~@\2\u02a3"+
		"\u02a4\7b\2\2\u02a4\u02a6\5\30\r\2\u02a5\u02a3\3\2\2\2\u02a5\u02a6\3\2"+
		"\2\2\u02a6\u02a7\3\2\2\2\u02a7\u02a8\7b\2\2\u02a8\u02a9\5\32\16\2\u02a9"+
		"\u02aa\7b\2\2\u02aa\u02ab\5\32\16\2\u02ab\u02ad\3\2\2\2\u02ac\u0299\3"+
		"\2\2\2\u02ac\u02a2\3\2\2\2\u02ad\u02ae\3\2\2\2\u02ae\u02af\7a\2\2\u02af"+
		"]\3\2\2\2\u02b0\u02c4\7`\2\2\u02b1\u02b2\5~@\2\u02b2\u02b3\7b\2\2\u02b3"+
		"\u02b4\5\26\f\2\u02b4\u02b5\7b\2\2\u02b5\u02b8\5\30\r\2\u02b6\u02b7\7"+
		"b\2\2\u02b7\u02b9\5\32\16\2\u02b8\u02b6\3\2\2\2\u02b8\u02b9\3\2\2\2\u02b9"+
		"\u02c5\3\2\2\2\u02ba\u02bb\5~@\2\u02bb\u02bc\7b\2\2\u02bc\u02bd\5\26\f"+
		"\2\u02bd\u02be\7b\2\2\u02be\u02bf\5\30\r\2\u02bf\u02c0\7b\2\2\u02c0\u02c1"+
		"\5\32\16\2\u02c1\u02c2\7b\2\2\u02c2\u02c3\5\32\16\2\u02c3\u02c5\3\2\2"+
		"\2\u02c4\u02b1\3\2\2\2\u02c4\u02ba\3\2\2\2\u02c5\u02c6\3\2\2\2\u02c6\u02c7"+
		"\7a\2\2\u02c7_\3\2\2\2\u02c8\u02d4\7`\2\2\u02c9\u02cc\5|?\2\u02ca\u02cb"+
		"\7b\2\2\u02cb\u02cd\5\32\16\2\u02cc\u02ca\3\2\2\2\u02cc\u02cd\3\2\2\2"+
		"\u02cd\u02d5\3\2\2\2\u02ce\u02cf\5|?\2\u02cf\u02d0\7b\2\2\u02d0\u02d1"+
		"\5\32\16\2\u02d1\u02d2\7b\2\2\u02d2\u02d3\5\32\16\2\u02d3\u02d5\3\2\2"+
		"\2\u02d4\u02c9\3\2\2\2\u02d4\u02ce\3\2\2\2\u02d5\u02d6\3\2\2\2\u02d6\u02d7"+
		"\7a\2\2\u02d7a\3\2\2\2\u02d8\u02de\7`\2\2\u02d9\u02df\5\32\16\2\u02da"+
		"\u02db\5\32\16\2\u02db\u02dc\7b\2\2\u02dc\u02dd\5\32\16\2\u02dd\u02df"+
		"\3\2\2\2\u02de\u02d9\3\2\2\2\u02de\u02da\3\2\2\2\u02df\u02e0\3\2\2\2\u02e0"+
		"\u02e1\7a\2\2\u02e1c\3\2\2\2\u02e2\u02e3\7`\2\2\u02e3\u02e4\5\32\16\2"+
		"\u02e4\u02e5\7a\2\2\u02e5e\3\2\2\2\u02e6\u02e7\7`\2\2\u02e7\u02e8\5\32"+
		"\16\2\u02e8\u02e9\7b\2\2\u02e9\u02ea\5\32\16\2\u02ea\u02eb\7a\2\2\u02eb"+
		"g\3\2\2\2\u02ec\u02f8\7`\2\2\u02ed\u02f9\5\u0082B\2\u02ee\u02ef\5\u0082"+
		"B\2\u02ef\u02f0\7b\2\2\u02f0\u02f1\5\32\16\2\u02f1\u02f9\3\2\2\2\u02f2"+
		"\u02f3\5\u0082B\2\u02f3\u02f4\7b\2\2\u02f4\u02f5\5\32\16\2\u02f5\u02f6"+
		"\7b\2\2\u02f6\u02f7\5\32\16\2\u02f7\u02f9\3\2\2\2\u02f8\u02ed\3\2\2\2"+
		"\u02f8\u02ee\3\2\2\2\u02f8\u02f2\3\2\2\2\u02f9\u02fa\3\2\2\2\u02fa\u02fb"+
		"\7a\2\2\u02fbi\3\2\2\2\u02fc\u0310\7`\2\2\u02fd\u0311\5~@\2\u02fe\u02ff"+
		"\5~@\2\u02ff\u0300\7b\2\2\u0300\u0301\5\u0082B\2\u0301\u0311\3\2\2\2\u0302"+
		"\u0303\5~@\2\u0303\u0304\7b\2\2\u0304\u0305\5\u0082B\2\u0305\u0306\7b"+
		"\2\2\u0306\u0307\5\32\16\2\u0307\u0311\3\2\2\2\u0308\u0309\5~@\2\u0309"+
		"\u030a\7b\2\2\u030a\u030b\5\u0082B\2\u030b\u030c\7b\2\2\u030c\u030d\5"+
		"\32\16\2\u030d\u030e\7b\2\2\u030e\u030f\5\32\16\2\u030f\u0311\3\2\2\2"+
		"\u0310\u02fd\3\2\2\2\u0310\u02fe\3\2\2\2\u0310\u0302\3\2\2\2\u0310\u0308"+
		"\3\2\2\2\u0311\u0312\3\2\2\2\u0312\u0313\7a\2\2\u0313k\3\2\2\2\u0314\u0315"+
		"\7`\2\2\u0315\u0318\5\u0082B\2\u0316\u0317\7b\2\2\u0317\u0319\5\u0080"+
		"A\2\u0318\u0316\3\2\2\2\u0318\u0319\3\2\2\2\u0319\u031a\3\2\2\2\u031a"+
		"\u031b\7a\2\2\u031bm\3\2\2\2\u031c\u031d\7`\2\2\u031d\u0322\5~@\2\u031e"+
		"\u031f\7b\2\2\u031f\u0321\5\32\16\2\u0320\u031e\3\2\2\2\u0321\u0324\3"+
		"\2\2\2\u0322\u0320\3\2\2\2\u0322\u0323\3\2\2\2\u0323\u0325\3\2\2\2\u0324"+
		"\u0322\3\2\2\2\u0325\u0326\7a\2\2\u0326o\3\2\2\2\u0327\u0328\7`\2\2\u0328"+
		"\u0329\5~@\2\u0329\u032a\7b\2\2\u032a\u032f\5\32\16\2\u032b\u032c\7b\2"+
		"\2\u032c\u032e\5\32\16\2\u032d\u032b\3\2\2\2\u032e\u0331\3\2\2\2\u032f"+
		"\u032d\3\2\2\2\u032f\u0330\3\2\2\2\u0330\u0332\3\2\2\2\u0331\u032f\3\2"+
		"\2\2\u0332\u0333\7a\2\2\u0333q\3\2\2\2\u0334\u0335\7`\2\2\u0335\u0338"+
		"\5\30\r\2\u0336\u0337\7b\2\2\u0337\u0339\5\32\16\2\u0338\u0336\3\2\2\2"+
		"\u0339\u033a\3\2\2\2\u033a\u0338\3\2\2\2\u033a\u033b\3\2\2\2\u033b\u033c"+
		"\3\2\2\2\u033c\u033d\7a\2\2\u033ds\3\2\2\2\u033e\u033f\7`\2\2\u033f\u0340"+
		"\5|?\2\u0340\u0341\7b\2\2\u0341\u0344\5\u0082B\2\u0342\u0343\7b\2\2\u0343"+
		"\u0345\5\32\16\2\u0344\u0342\3\2\2\2\u0345\u0346\3\2\2\2\u0346\u0344\3"+
		"\2\2\2\u0346\u0347\3\2\2\2\u0347\u0348\3\2\2\2\u0348\u0349\7a\2\2\u0349"+
		"u\3\2\2\2\u034a\u034b\7`\2\2\u034b\u034c\5|?\2\u034c\u034d\7b\2\2\u034d"+
		"\u034e\5\u0082B\2\u034e\u034f\7b\2\2\u034f\u0352\5\30\r\2\u0350\u0351"+
		"\7b\2\2\u0351\u0353\5\32\16\2\u0352\u0350\3\2\2\2\u0353\u0354\3\2\2\2"+
		"\u0354\u0352\3\2\2\2\u0354\u0355\3\2\2\2\u0355\u0356\3\2\2\2\u0356\u0357"+
		"\7a\2\2\u0357w\3\2\2\2\u0358\u0359\7R\2\2\u0359y\3\2\2\2\u035a\u035b\7"+
		"S\2\2\u035b{\3\2\2\2\u035c\u0367\5x=\2\u035d\u0367\5z>\2\u035e\u0363\5"+
		"~@\2\u035f\u0360\7b\2\2\u0360\u0362\5~@\2\u0361\u035f\3\2\2\2\u0362\u0365"+
		"\3\2\2\2\u0363\u0361\3\2\2\2\u0363\u0364\3\2\2\2\u0364\u0367\3\2\2\2\u0365"+
		"\u0363\3\2\2\2\u0366\u035c\3\2\2\2\u0366\u035d\3\2\2\2\u0366\u035e\3\2"+
		"\2\2\u0367}\3\2\2\2\u0368\u036c\5x=\2\u0369\u036c\5z>\2\u036a\u036c\7"+
		"T\2\2\u036b\u0368\3\2\2\2\u036b\u0369\3\2\2\2\u036b\u036a\3\2\2\2\u036c"+
		"\177\3\2\2\2\u036d\u0378\5x=\2\u036e\u0378\5z>\2\u036f\u0374\5\u0082B"+
		"\2\u0370\u0371\7b\2\2\u0371\u0373\5\u0082B\2\u0372\u0370\3\2\2\2\u0373"+
		"\u0376\3\2\2\2\u0374\u0372\3\2\2\2\u0374\u0375\3\2\2\2\u0375\u0378\3\2"+
		"\2\2\u0376\u0374\3\2\2\2\u0377\u036d\3\2\2\2\u0377\u036e\3\2\2\2\u0377"+
		"\u036f\3\2\2\2\u0378\u0081\3\2\2\2\u0379\u038c\5x=\2\u037a\u038c\5z>\2"+
		"\u037b\u038c\7T\2\2\u037c\u038c\7U\2\2\u037d\u038c\7V\2\2\u037e\u038c"+
		"\7W\2\2\u037f\u038c\7X\2\2\u0380\u038c\7Y\2\2\u0381\u038c\7Z\2\2\u0382"+
		"\u038c\7[\2\2\u0383\u038c\7\\\2\2\u0384\u038c\7]\2\2\u0385\u038c\7^\2"+
		"\2\u0386\u038c\7_\2\2\u0387\u0388\7c\2\2\u0388\u0389\5\u0080A\2\u0389"+
		"\u038a\7d\2\2\u038a\u038c\3\2\2\2\u038b\u0379\3\2\2\2\u038b\u037a\3\2"+
		"\2\2\u038b\u037b\3\2\2\2\u038b\u037c\3\2\2\2\u038b\u037d\3\2\2\2\u038b"+
		"\u037e\3\2\2\2\u038b\u037f\3\2\2\2\u038b\u0380\3\2\2\2\u038b\u0381\3\2"+
		"\2\2\u038b\u0382\3\2\2\2\u038b\u0383\3\2\2\2\u038b\u0384\3\2\2\2\u038b"+
		"\u0385\3\2\2\2\u038b\u0386\3\2\2\2\u038b\u0387\3\2\2\2\u038c\u0083\3\2"+
		"\2\2A\u00a0\u00aa\u00af\u00b6\u00e1\u00e8\u0100\u0105\u010c\u0114\u0119"+
		"\u0122\u0127\u012c\u0131\u0138\u013d\u0154\u0159\u018b\u0192\u019a\u01a2"+
		"\u01aa\u01b3\u01c1\u01d0\u01df\u01f2\u0228\u0232\u023f\u024a\u0256\u0259"+
		"\u0263\u0278\u0280\u028a\u0294\u029c\u02a0\u02a5\u02ac\u02b8\u02c4\u02cc"+
		"\u02d4\u02de\u02f8\u0310\u0318\u0322\u032f\u033a\u0346\u0354\u0363\u0366"+
		"\u036b\u0374\u0377\u038b";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
		_decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
		for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
			_decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
		}
	}
}