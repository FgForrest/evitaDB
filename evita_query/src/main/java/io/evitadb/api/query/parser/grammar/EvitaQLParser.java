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

// Generated from EvitaQL.g4 by ANTLR 4.9.2

package io.evitadb.api.query.parser.grammar;

import org.antlr.v4.runtime.NoViableAltException;
import org.antlr.v4.runtime.Parser;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.RuntimeMetaData;
import org.antlr.v4.runtime.TokenStream;
import org.antlr.v4.runtime.Vocabulary;
import org.antlr.v4.runtime.VocabularyImpl;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNDeserializer;
import org.antlr.v4.runtime.atn.ParserATNSimulator;
import org.antlr.v4.runtime.atn.PredictionContextCache;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.ParseTreeVisitor;
import org.antlr.v4.runtime.tree.TerminalNode;

import java.util.ArrayList;
import java.util.List;

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
		T__73=74, T__74=75, T__75=76, T__76=77, T__77=78, T__78=79, T__79=80,
		T__80=81, T__81=82, T__82=83, T__83=84, T__84=85, T__85=86, T__86=87,
		T__87=88, T__88=89, T__89=90, T__90=91, T__91=92, T__92=93, T__93=94,
		T__94=95, T__95=96, T__96=97, POSITIONAL_PARAMETER=98, NAMED_PARAMETER=99,
		STRING=100, INT=101, FLOAT=102, BOOLEAN=103, DATE=104, TIME=105, DATE_TIME=106,
		OFFSET_DATE_TIME=107, FLOAT_NUMBER_RANGE=108, INT_NUMBER_RANGE=109, DATE_TIME_RANGE=110,
		UUID=111, ENUM=112, ARGS_OPENING=113, ARGS_CLOSING=114, ARGS_DELIMITER=115,
		COMMENT=116, WHITESPACE=117, UNEXPECTED_CHAR=118;
	public static final int
		RULE_queryUnit = 0, RULE_headConstraintListUnit = 1, RULE_filterConstraintListUnit = 2,
		RULE_orderConstraintListUnit = 3, RULE_requireConstraintListUnit = 4,
		RULE_valueTokenUnit = 5, RULE_query = 6, RULE_constraint = 7, RULE_headConstraint = 8,
		RULE_filterConstraint = 9, RULE_orderConstraint = 10, RULE_requireConstraint = 11,
		RULE_headConstraintList = 12, RULE_filterConstraintList = 13, RULE_orderConstraintList = 14,
		RULE_requireConstraintList = 15, RULE_argsOpening = 16, RULE_argsClosing = 17,
		RULE_constraintListArgs = 18, RULE_emptyArgs = 19, RULE_filterConstraintListArgs = 20,
		RULE_filterConstraintArgs = 21, RULE_orderConstraintListArgs = 22, RULE_requireConstraintArgs = 23,
		RULE_requireConstraintListArgs = 24, RULE_classifierArgs = 25, RULE_classifierWithValueArgs = 26,
		RULE_classifierWithOptionalValueArgs = 27, RULE_classifierWithValueListArgs = 28,
		RULE_classifierWithOptionalValueListArgs = 29, RULE_classifierWithBetweenValuesArgs = 30,
		RULE_valueArgs = 31, RULE_valueListArgs = 32, RULE_betweenValuesArgs = 33,
		RULE_classifierListArgs = 34, RULE_classifierWithFilterConstraintArgs = 35,
		RULE_classifierWithOptionalFilterConstraintArgs = 36, RULE_classifierWithOrderConstraintListArgs = 37,
		RULE_valueWithRequireConstraintListArgs = 38, RULE_hierarchyWithinConstraintArgs = 39,
		RULE_hierarchyWithinSelfConstraintArgs = 40, RULE_hierarchyWithinRootConstraintArgs = 41,
		RULE_hierarchyWithinRootSelfConstraintArgs = 42, RULE_attributeSetExactArgs = 43,
		RULE_pageConstraintArgs = 44, RULE_stripConstraintArgs = 45, RULE_priceContentArgs = 46,
		RULE_singleRefReferenceContent1Args = 47, RULE_singleRefReferenceContent2Args = 48,
		RULE_singleRefReferenceContent3Args = 49, RULE_singleRefReferenceContent4Args = 50,
		RULE_singleRefReferenceContent5Args = 51, RULE_singleRefReferenceContent6Args = 52,
		RULE_singleRefReferenceContent7Args = 53, RULE_singleRefReferenceContent8Args = 54,
		RULE_singleRefReferenceContentWithAttributes1Args = 55, RULE_singleRefReferenceContentWithAttributes2Args = 56,
		RULE_singleRefReferenceContentWithAttributes3Args = 57, RULE_singleRefReferenceContentWithAttributes4Args = 58,
		RULE_singleRefReferenceContentWithAttributes5Args = 59, RULE_singleRefReferenceContentWithAttributes6Args = 60,
		RULE_singleRefReferenceContentWithAttributes7Args = 61, RULE_singleRefReferenceContentWithAttributes8Args = 62,
		RULE_multipleRefsReferenceContentArgs = 63, RULE_allRefsReferenceContentArgs = 64,
		RULE_allRefsWithAttributesReferenceContent1Args = 65, RULE_allRefsWithAttributesReferenceContent2Args = 66,
		RULE_allRefsWithAttributesReferenceContent3Args = 67, RULE_singleRequireHierarchyContentArgs = 68,
		RULE_allRequiresHierarchyContentArgs = 69, RULE_facetSummary1Args = 70,
		RULE_facetSummary2Args = 71, RULE_facetSummary3Args = 72, RULE_facetSummary4Args = 73,
		RULE_facetSummary5Args = 74, RULE_facetSummary6Args = 75, RULE_facetSummary7Args = 76,
		RULE_facetSummaryOfReference2Args = 77, RULE_facetSummaryRequirementsArgs = 78,
		RULE_facetSummaryFilterArgs = 79, RULE_facetSummaryOrderArgs = 80, RULE_attributeHistogramArgs = 81,
		RULE_priceHistogramArgs = 82, RULE_hierarchyStatisticsArgs = 83, RULE_hierarchyRequireConstraintArgs = 84,
		RULE_hierarchyFromNodeArgs = 85, RULE_fullHierarchyOfSelfArgs = 86, RULE_basicHierarchyOfReferenceArgs = 87,
		RULE_basicHierarchyOfReferenceWithBehaviourArgs = 88, RULE_fullHierarchyOfReferenceArgs = 89,
		RULE_fullHierarchyOfReferenceWithBehaviourArgs = 90, RULE_positionalParameter = 91,
		RULE_namedParameter = 92, RULE_variadicValueTokens = 93, RULE_valueToken = 94;
	private static String[] makeRuleNames() {
		return new String[] {
			"queryUnit", "headConstraintListUnit", "filterConstraintListUnit", "orderConstraintListUnit",
			"requireConstraintListUnit", "valueTokenUnit", "query", "constraint",
			"headConstraint", "filterConstraint", "orderConstraint", "requireConstraint",
			"headConstraintList", "filterConstraintList", "orderConstraintList",
			"requireConstraintList", "argsOpening", "argsClosing", "constraintListArgs",
			"emptyArgs", "filterConstraintListArgs", "filterConstraintArgs", "orderConstraintListArgs",
			"requireConstraintArgs", "requireConstraintListArgs", "classifierArgs",
			"classifierWithValueArgs", "classifierWithOptionalValueArgs", "classifierWithValueListArgs",
			"classifierWithOptionalValueListArgs", "classifierWithBetweenValuesArgs",
			"valueArgs", "valueListArgs", "betweenValuesArgs", "classifierListArgs",
			"classifierWithFilterConstraintArgs", "classifierWithOptionalFilterConstraintArgs",
			"classifierWithOrderConstraintListArgs", "valueWithRequireConstraintListArgs",
			"hierarchyWithinConstraintArgs", "hierarchyWithinSelfConstraintArgs",
			"hierarchyWithinRootConstraintArgs", "hierarchyWithinRootSelfConstraintArgs",
			"attributeSetExactArgs", "pageConstraintArgs", "stripConstraintArgs",
			"priceContentArgs", "singleRefReferenceContent1Args", "singleRefReferenceContent2Args",
			"singleRefReferenceContent3Args", "singleRefReferenceContent4Args", "singleRefReferenceContent5Args",
			"singleRefReferenceContent6Args", "singleRefReferenceContent7Args", "singleRefReferenceContent8Args",
			"singleRefReferenceContentWithAttributes1Args", "singleRefReferenceContentWithAttributes2Args",
			"singleRefReferenceContentWithAttributes3Args", "singleRefReferenceContentWithAttributes4Args",
			"singleRefReferenceContentWithAttributes5Args", "singleRefReferenceContentWithAttributes6Args",
			"singleRefReferenceContentWithAttributes7Args", "singleRefReferenceContentWithAttributes8Args",
			"multipleRefsReferenceContentArgs", "allRefsReferenceContentArgs", "allRefsWithAttributesReferenceContent1Args",
			"allRefsWithAttributesReferenceContent2Args", "allRefsWithAttributesReferenceContent3Args",
			"singleRequireHierarchyContentArgs", "allRequiresHierarchyContentArgs",
			"facetSummary1Args", "facetSummary2Args", "facetSummary3Args", "facetSummary4Args",
			"facetSummary5Args", "facetSummary6Args", "facetSummary7Args", "facetSummaryOfReference2Args",
			"facetSummaryRequirementsArgs", "facetSummaryFilterArgs", "facetSummaryOrderArgs",
			"attributeHistogramArgs", "priceHistogramArgs", "hierarchyStatisticsArgs",
			"hierarchyRequireConstraintArgs", "hierarchyFromNodeArgs", "fullHierarchyOfSelfArgs",
			"basicHierarchyOfReferenceArgs", "basicHierarchyOfReferenceWithBehaviourArgs",
			"fullHierarchyOfReferenceArgs", "fullHierarchyOfReferenceWithBehaviourArgs",
			"positionalParameter", "namedParameter", "variadicValueTokens", "valueToken"
		};
	}
	public static final String[] ruleNames = makeRuleNames();

	private static String[] makeLiteralNames() {
		return new String[] {
			null, "'query'", "'collection'", "'filterBy'", "'filterGroupBy'", "'and'",
			"'or'", "'not'", "'userFilter'", "'attributeEquals'", "'attributeGreaterThan'",
			"'attributeGreaterThanEquals'", "'attributeLessThan'", "'attributeLessThanEquals'",
			"'attributeBetween'", "'attributeInSet'", "'attributeContains'", "'attributeStartsWith'",
			"'attributeEndsWith'", "'attributeEqualsTrue'", "'attributeEqualsFalse'",
			"'attributeIs'", "'attributeIsNull'", "'attributeIsNotNull'", "'attributeInRange'",
			"'attributeInRangeNow'", "'entityPrimaryKeyInSet'", "'entityLocaleEquals'",
			"'priceInCurrency'", "'priceInPriceLists'", "'priceValidInNow'", "'priceValidIn'",
			"'priceBetween'", "'facetHaving'", "'referenceHaving'", "'hierarchyWithin'",
			"'hierarchyWithinSelf'", "'hierarchyWithinRoot'", "'hierarchyWithinRootSelf'",
			"'directRelation'", "'having'", "'excludingRoot'", "'excluding'", "'entityHaving'",
			"'orderBy'", "'orderGroupBy'", "'attributeNatural'", "'attributeSetExact'",
			"'attributeSetInFilter'", "'priceNatural'", "'priceDiscount'", "'random'",
			"'referenceProperty'", "'entityPrimaryKeyNatural'", "'entityPrimaryKeyExact'",
			"'entityPrimaryKeyInFilter'", "'entityProperty'", "'entityGroupProperty'",
			"'require'", "'page'", "'strip'", "'entityFetch'", "'entityGroupFetch'",
			"'attributeContent'", "'attributeContentAll'", "'priceContent'", "'priceContentAll'",
			"'priceContentRespectingFilter'", "'associatedDataContent'", "'associatedDataContentAll'",
			"'referenceContentAll'", "'referenceContent'", "'referenceContentAllWithAttributes'",
			"'referenceContentWithAttributes'", "'hierarchyContent'", "'priceType'",
			"'dataInLocalesAll'", "'dataInLocales'", "'facetSummary'", "'facetSummaryOfReference'",
			"'facetGroupsConjunction'", "'facetGroupsDisjunction'", "'facetGroupsNegation'",
			"'attributeHistogram'", "'priceHistogram'", "'distance'", "'level'",
			"'node'", "'stopAt'", "'statistics'", "'fromRoot'", "'fromNode'", "'children'",
			"'siblings'", "'parents'", "'hierarchyOfSelf'", "'hierarchyOfReference'",
			"'queryTelemetry'", "'?'", null, null, null, null, null, null, null,
			null, null, null, null, null, null, null, "'('", "')'", "','"
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
			null, null, null, null, null, null, null, null, null, null, null, null,
			null, null, null, null, null, null, null, null, null, null, null, null,
			null, null, "POSITIONAL_PARAMETER", "NAMED_PARAMETER", "STRING", "INT",
			"FLOAT", "BOOLEAN", "DATE", "TIME", "DATE_TIME", "OFFSET_DATE_TIME",
			"FLOAT_NUMBER_RANGE", "INT_NUMBER_RANGE", "DATE_TIME_RANGE", "UUID",
			"ENUM", "ARGS_OPENING", "ARGS_CLOSING", "ARGS_DELIMITER", "COMMENT",
			"WHITESPACE", "UNEXPECTED_CHAR"
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
			setState(190);
			query();
			setState(191);
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
			setState(193);
			headConstraintList();
			setState(194);
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
			setState(196);
			filterConstraintList();
			setState(197);
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
			setState(199);
			orderConstraintList();
			setState(200);
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
			setState(202);
			requireConstraintList();
			setState(203);
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
		enterRule(_localctx, 10, RULE_valueTokenUnit);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(205);
			valueToken();
			setState(206);
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
		enterRule(_localctx, 12, RULE_query);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(208);
			match(T__0);
			setState(209);
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
		enterRule(_localctx, 14, RULE_constraint);
		try {
			setState(215);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case T__1:
				enterOuterAlt(_localctx, 1);
				{
				setState(211);
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
			case T__40:
			case T__41:
			case T__42:
				enterOuterAlt(_localctx, 2);
				{
				setState(212);
				filterConstraint();
				}
				break;
			case T__43:
			case T__44:
			case T__45:
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
				enterOuterAlt(_localctx, 3);
				{
				setState(213);
				orderConstraint();
				}
				break;
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
			case T__79:
			case T__80:
			case T__81:
			case T__82:
			case T__83:
			case T__84:
			case T__85:
			case T__86:
			case T__87:
			case T__88:
			case T__89:
			case T__90:
			case T__91:
			case T__92:
			case T__93:
			case T__94:
			case T__95:
			case T__96:
				enterOuterAlt(_localctx, 4);
				{
				setState(214);
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
		enterRule(_localctx, 16, RULE_headConstraint);
		try {
			_localctx = new CollectionConstraintContext(_localctx);
			enterOuterAlt(_localctx, 1);
			{
			setState(217);
			match(T__1);
			setState(218);
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
	public static class PriceValidInNowConstraintContext extends FilterConstraintContext {
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public PriceValidInNowConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterPriceValidInNowConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitPriceValidInNowConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitPriceValidInNowConstraint(this);
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
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
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
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
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
		public ClassifierWithOptionalValueListArgsContext args;
		public ClassifierWithOptionalValueListArgsContext classifierWithOptionalValueListArgs() {
			return getRuleContext(ClassifierWithOptionalValueListArgsContext.class,0);
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
	public static class FilterGroupByConstraintContext extends FilterConstraintContext {
		public FilterConstraintListArgsContext args;
		public FilterConstraintListArgsContext filterConstraintListArgs() {
			return getRuleContext(FilterConstraintListArgsContext.class,0);
		}
		public FilterGroupByConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFilterGroupByConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFilterGroupByConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFilterGroupByConstraint(this);
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
		public ClassifierArgsContext args;
		public ClassifierWithFilterConstraintArgsContext classifierWithFilterConstraintArgs() {
			return getRuleContext(ClassifierWithFilterConstraintArgsContext.class,0);
		}
		public ClassifierArgsContext classifierArgs() {
			return getRuleContext(ClassifierArgsContext.class,0);
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
	public static class HierarchyHavingConstraintContext extends FilterConstraintContext {
		public FilterConstraintListArgsContext args;
		public FilterConstraintListArgsContext filterConstraintListArgs() {
			return getRuleContext(FilterConstraintListArgsContext.class,0);
		}
		public HierarchyHavingConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHierarchyHavingConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHierarchyHavingConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHierarchyHavingConstraint(this);
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
	public static class AttributeInRangeNowConstraintContext extends FilterConstraintContext {
		public ClassifierArgsContext args;
		public ClassifierArgsContext classifierArgs() {
			return getRuleContext(ClassifierArgsContext.class,0);
		}
		public AttributeInRangeNowConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAttributeInRangeNowConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAttributeInRangeNowConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAttributeInRangeNowConstraint(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FilterConstraintContext filterConstraint() throws RecognitionException {
		FilterConstraintContext _localctx = new FilterConstraintContext(_ctx, getState());
		enterRule(_localctx, 18, RULE_filterConstraint);
		try {
			setState(323);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case T__2:
				_localctx = new FilterByConstraintContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(220);
				match(T__2);
				setState(221);
				((FilterByConstraintContext)_localctx).args = filterConstraintListArgs();
				}
				break;
			case T__3:
				_localctx = new FilterGroupByConstraintContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(222);
				match(T__3);
				setState(223);
				((FilterGroupByConstraintContext)_localctx).args = filterConstraintListArgs();
				}
				break;
			case T__4:
				_localctx = new AndConstraintContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(224);
				match(T__4);
				setState(227);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,1,_ctx) ) {
				case 1:
					{
					setState(225);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(226);
					((AndConstraintContext)_localctx).args = filterConstraintListArgs();
					}
					break;
				}
				}
				break;
			case T__5:
				_localctx = new OrConstraintContext(_localctx);
				enterOuterAlt(_localctx, 4);
				{
				setState(229);
				match(T__5);
				setState(232);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,2,_ctx) ) {
				case 1:
					{
					setState(230);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(231);
					((OrConstraintContext)_localctx).args = filterConstraintListArgs();
					}
					break;
				}
				}
				break;
			case T__6:
				_localctx = new NotConstraintContext(_localctx);
				enterOuterAlt(_localctx, 5);
				{
				setState(234);
				match(T__6);
				setState(235);
				((NotConstraintContext)_localctx).args = filterConstraintArgs();
				}
				break;
			case T__7:
				_localctx = new UserFilterConstraintContext(_localctx);
				enterOuterAlt(_localctx, 6);
				{
				setState(236);
				match(T__7);
				setState(239);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,3,_ctx) ) {
				case 1:
					{
					setState(237);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(238);
					((UserFilterConstraintContext)_localctx).args = filterConstraintListArgs();
					}
					break;
				}
				}
				break;
			case T__8:
				_localctx = new AttributeEqualsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 7);
				{
				setState(241);
				match(T__8);
				setState(242);
				((AttributeEqualsConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__9:
				_localctx = new AttributeGreaterThanConstraintContext(_localctx);
				enterOuterAlt(_localctx, 8);
				{
				setState(243);
				match(T__9);
				setState(244);
				((AttributeGreaterThanConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__10:
				_localctx = new AttributeGreaterThanEqualsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 9);
				{
				setState(245);
				match(T__10);
				setState(246);
				((AttributeGreaterThanEqualsConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__11:
				_localctx = new AttributeLessThanConstraintContext(_localctx);
				enterOuterAlt(_localctx, 10);
				{
				setState(247);
				match(T__11);
				setState(248);
				((AttributeLessThanConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__12:
				_localctx = new AttributeLessThanEqualsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 11);
				{
				setState(249);
				match(T__12);
				setState(250);
				((AttributeLessThanEqualsConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__13:
				_localctx = new AttributeBetweenConstraintContext(_localctx);
				enterOuterAlt(_localctx, 12);
				{
				setState(251);
				match(T__13);
				setState(252);
				((AttributeBetweenConstraintContext)_localctx).args = classifierWithBetweenValuesArgs();
				}
				break;
			case T__14:
				_localctx = new AttributeInSetConstraintContext(_localctx);
				enterOuterAlt(_localctx, 13);
				{
				setState(253);
				match(T__14);
				setState(254);
				((AttributeInSetConstraintContext)_localctx).args = classifierWithOptionalValueListArgs();
				}
				break;
			case T__15:
				_localctx = new AttributeContainsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 14);
				{
				setState(255);
				match(T__15);
				setState(256);
				((AttributeContainsConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__16:
				_localctx = new AttributeStartsWithConstraintContext(_localctx);
				enterOuterAlt(_localctx, 15);
				{
				setState(257);
				match(T__16);
				setState(258);
				((AttributeStartsWithConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__17:
				_localctx = new AttributeEndsWithConstraintContext(_localctx);
				enterOuterAlt(_localctx, 16);
				{
				setState(259);
				match(T__17);
				setState(260);
				((AttributeEndsWithConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__18:
				_localctx = new AttributeEqualsTrueConstraintContext(_localctx);
				enterOuterAlt(_localctx, 17);
				{
				setState(261);
				match(T__18);
				setState(262);
				((AttributeEqualsTrueConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case T__19:
				_localctx = new AttributeEqualsFalseConstraintContext(_localctx);
				enterOuterAlt(_localctx, 18);
				{
				setState(263);
				match(T__19);
				setState(264);
				((AttributeEqualsFalseConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case T__20:
				_localctx = new AttributeIsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 19);
				{
				setState(265);
				match(T__20);
				setState(266);
				((AttributeIsConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__21:
				_localctx = new AttributeIsNullConstraintContext(_localctx);
				enterOuterAlt(_localctx, 20);
				{
				setState(267);
				match(T__21);
				setState(268);
				((AttributeIsNullConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case T__22:
				_localctx = new AttributeIsNotNullConstraintContext(_localctx);
				enterOuterAlt(_localctx, 21);
				{
				setState(269);
				match(T__22);
				setState(270);
				((AttributeIsNotNullConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case T__23:
				_localctx = new AttributeInRangeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 22);
				{
				setState(271);
				match(T__23);
				setState(272);
				((AttributeInRangeConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__24:
				_localctx = new AttributeInRangeNowConstraintContext(_localctx);
				enterOuterAlt(_localctx, 23);
				{
				setState(273);
				match(T__24);
				setState(274);
				((AttributeInRangeNowConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case T__25:
				_localctx = new EntityPrimaryKeyInSetConstraintContext(_localctx);
				enterOuterAlt(_localctx, 24);
				{
				setState(275);
				match(T__25);
				setState(278);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,4,_ctx) ) {
				case 1:
					{
					setState(276);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(277);
					((EntityPrimaryKeyInSetConstraintContext)_localctx).args = valueListArgs();
					}
					break;
				}
				}
				break;
			case T__26:
				_localctx = new EntityLocaleEqualsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 25);
				{
				setState(280);
				match(T__26);
				setState(281);
				((EntityLocaleEqualsConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case T__27:
				_localctx = new PriceInCurrencyConstraintContext(_localctx);
				enterOuterAlt(_localctx, 26);
				{
				setState(282);
				match(T__27);
				setState(283);
				((PriceInCurrencyConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case T__28:
				_localctx = new PriceInPriceListsConstraintsContext(_localctx);
				enterOuterAlt(_localctx, 27);
				{
				setState(284);
				match(T__28);
				setState(287);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,5,_ctx) ) {
				case 1:
					{
					setState(285);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(286);
					((PriceInPriceListsConstraintsContext)_localctx).args = classifierListArgs();
					}
					break;
				}
				}
				break;
			case T__29:
				_localctx = new PriceValidInNowConstraintContext(_localctx);
				enterOuterAlt(_localctx, 28);
				{
				setState(289);
				match(T__29);
				setState(290);
				emptyArgs();
				}
				break;
			case T__30:
				_localctx = new PriceValidInConstraintContext(_localctx);
				enterOuterAlt(_localctx, 29);
				{
				setState(291);
				match(T__30);
				setState(292);
				((PriceValidInConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case T__31:
				_localctx = new PriceBetweenConstraintContext(_localctx);
				enterOuterAlt(_localctx, 30);
				{
				setState(293);
				match(T__31);
				setState(294);
				((PriceBetweenConstraintContext)_localctx).args = betweenValuesArgs();
				}
				break;
			case T__32:
				_localctx = new FacetHavingConstraintContext(_localctx);
				enterOuterAlt(_localctx, 31);
				{
				setState(295);
				match(T__32);
				setState(296);
				((FacetHavingConstraintContext)_localctx).args = classifierWithFilterConstraintArgs();
				}
				break;
			case T__33:
				_localctx = new ReferenceHavingConstraintContext(_localctx);
				enterOuterAlt(_localctx, 32);
				{
				setState(297);
				match(T__33);
				setState(300);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,6,_ctx) ) {
				case 1:
					{
					setState(298);
					((ReferenceHavingConstraintContext)_localctx).args = classifierArgs();
					}
					break;
				case 2:
					{
					setState(299);
					classifierWithFilterConstraintArgs();
					}
					break;
				}
				}
				break;
			case T__34:
				_localctx = new HierarchyWithinConstraintContext(_localctx);
				enterOuterAlt(_localctx, 33);
				{
				setState(302);
				match(T__34);
				setState(303);
				((HierarchyWithinConstraintContext)_localctx).args = hierarchyWithinConstraintArgs();
				}
				break;
			case T__35:
				_localctx = new HierarchyWithinSelfConstraintContext(_localctx);
				enterOuterAlt(_localctx, 34);
				{
				setState(304);
				match(T__35);
				setState(305);
				((HierarchyWithinSelfConstraintContext)_localctx).args = hierarchyWithinSelfConstraintArgs();
				}
				break;
			case T__36:
				_localctx = new HierarchyWithinRootConstraintContext(_localctx);
				enterOuterAlt(_localctx, 35);
				{
				setState(306);
				match(T__36);
				setState(307);
				((HierarchyWithinRootConstraintContext)_localctx).args = hierarchyWithinRootConstraintArgs();
				}
				break;
			case T__37:
				_localctx = new HierarchyWithinRootSelfConstraintContext(_localctx);
				enterOuterAlt(_localctx, 36);
				{
				setState(308);
				match(T__37);
				setState(311);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,7,_ctx) ) {
				case 1:
					{
					setState(309);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(310);
					((HierarchyWithinRootSelfConstraintContext)_localctx).args = hierarchyWithinRootSelfConstraintArgs();
					}
					break;
				}
				}
				break;
			case T__38:
				_localctx = new HierarchyDirectRelationConstraintContext(_localctx);
				enterOuterAlt(_localctx, 37);
				{
				setState(313);
				match(T__38);
				setState(314);
				emptyArgs();
				}
				break;
			case T__39:
				_localctx = new HierarchyHavingConstraintContext(_localctx);
				enterOuterAlt(_localctx, 38);
				{
				setState(315);
				match(T__39);
				setState(316);
				((HierarchyHavingConstraintContext)_localctx).args = filterConstraintListArgs();
				}
				break;
			case T__40:
				_localctx = new HierarchyExcludingRootConstraintContext(_localctx);
				enterOuterAlt(_localctx, 39);
				{
				setState(317);
				match(T__40);
				setState(318);
				emptyArgs();
				}
				break;
			case T__41:
				_localctx = new HierarchyExcludingConstraintContext(_localctx);
				enterOuterAlt(_localctx, 40);
				{
				setState(319);
				match(T__41);
				setState(320);
				((HierarchyExcludingConstraintContext)_localctx).args = filterConstraintListArgs();
				}
				break;
			case T__42:
				_localctx = new EntityHavingConstraintContext(_localctx);
				enterOuterAlt(_localctx, 41);
				{
				setState(321);
				match(T__42);
				setState(322);
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
	public static class OrderGroupByConstraintContext extends OrderConstraintContext {
		public OrderConstraintListArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public OrderConstraintListArgsContext orderConstraintListArgs() {
			return getRuleContext(OrderConstraintListArgsContext.class,0);
		}
		public OrderGroupByConstraintContext(OrderConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterOrderGroupByConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitOrderGroupByConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitOrderGroupByConstraint(this);
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
	public static class AttributeSetExactConstraintContext extends OrderConstraintContext {
		public AttributeSetExactArgsContext args;
		public AttributeSetExactArgsContext attributeSetExactArgs() {
			return getRuleContext(AttributeSetExactArgsContext.class,0);
		}
		public AttributeSetExactConstraintContext(OrderConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAttributeSetExactConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAttributeSetExactConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAttributeSetExactConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class EntityPrimaryKeyInFilterConstraintContext extends OrderConstraintContext {
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public EntityPrimaryKeyInFilterConstraintContext(OrderConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterEntityPrimaryKeyInFilterConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitEntityPrimaryKeyInFilterConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitEntityPrimaryKeyInFilterConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class EntityPrimaryKeyExactConstraintContext extends OrderConstraintContext {
		public ValueListArgsContext args;
		public ValueListArgsContext valueListArgs() {
			return getRuleContext(ValueListArgsContext.class,0);
		}
		public EntityPrimaryKeyExactConstraintContext(OrderConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterEntityPrimaryKeyExactConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitEntityPrimaryKeyExactConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitEntityPrimaryKeyExactConstraint(this);
			else return visitor.visitChildren(this);
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
	public static class PriceDiscountConstraintContext extends OrderConstraintContext {
		public ValueListArgsContext args;
		public ValueListArgsContext valueListArgs() {
			return getRuleContext(ValueListArgsContext.class,0);
		}
		public PriceDiscountConstraintContext(OrderConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterPriceDiscountConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitPriceDiscountConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitPriceDiscountConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class EntityGroupPropertyConstraintContext extends OrderConstraintContext {
		public OrderConstraintListArgsContext args;
		public OrderConstraintListArgsContext orderConstraintListArgs() {
			return getRuleContext(OrderConstraintListArgsContext.class,0);
		}
		public EntityGroupPropertyConstraintContext(OrderConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterEntityGroupPropertyConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitEntityGroupPropertyConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitEntityGroupPropertyConstraint(this);
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
	public static class AttributeSetInFilterConstraintContext extends OrderConstraintContext {
		public ClassifierArgsContext args;
		public ClassifierArgsContext classifierArgs() {
			return getRuleContext(ClassifierArgsContext.class,0);
		}
		public AttributeSetInFilterConstraintContext(OrderConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAttributeSetInFilterConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAttributeSetInFilterConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAttributeSetInFilterConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class EntityPrimaryKeyExactNaturalContext extends OrderConstraintContext {
		public ValueArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public ValueArgsContext valueArgs() {
			return getRuleContext(ValueArgsContext.class,0);
		}
		public EntityPrimaryKeyExactNaturalContext(OrderConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterEntityPrimaryKeyExactNatural(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitEntityPrimaryKeyExactNatural(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitEntityPrimaryKeyExactNatural(this);
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
		enterRule(_localctx, 20, RULE_orderConstraint);
		try {
			setState(365);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case T__43:
				_localctx = new OrderByConstraintContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(325);
				match(T__43);
				setState(328);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,9,_ctx) ) {
				case 1:
					{
					setState(326);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(327);
					((OrderByConstraintContext)_localctx).args = orderConstraintListArgs();
					}
					break;
				}
				}
				break;
			case T__44:
				_localctx = new OrderGroupByConstraintContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(330);
				match(T__44);
				setState(333);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,10,_ctx) ) {
				case 1:
					{
					setState(331);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(332);
					((OrderGroupByConstraintContext)_localctx).args = orderConstraintListArgs();
					}
					break;
				}
				}
				break;
			case T__45:
				_localctx = new AttributeNaturalConstraintContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(335);
				match(T__45);
				setState(336);
				((AttributeNaturalConstraintContext)_localctx).args = classifierWithOptionalValueArgs();
				}
				break;
			case T__46:
				_localctx = new AttributeSetExactConstraintContext(_localctx);
				enterOuterAlt(_localctx, 4);
				{
				setState(337);
				match(T__46);
				setState(338);
				((AttributeSetExactConstraintContext)_localctx).args = attributeSetExactArgs();
				}
				break;
			case T__47:
				_localctx = new AttributeSetInFilterConstraintContext(_localctx);
				enterOuterAlt(_localctx, 5);
				{
				setState(339);
				match(T__47);
				setState(340);
				((AttributeSetInFilterConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case T__48:
				_localctx = new PriceNaturalConstraintContext(_localctx);
				enterOuterAlt(_localctx, 6);
				{
				setState(341);
				match(T__48);
				setState(344);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,11,_ctx) ) {
				case 1:
					{
					setState(342);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(343);
					((PriceNaturalConstraintContext)_localctx).args = valueArgs();
					}
					break;
				}
				}
				break;
			case T__49:
				_localctx = new PriceDiscountConstraintContext(_localctx);
				enterOuterAlt(_localctx, 7);
				{
				setState(346);
				match(T__49);
				setState(347);
				((PriceDiscountConstraintContext)_localctx).args = valueListArgs();
				}
				break;
			case T__50:
				_localctx = new RandomConstraintContext(_localctx);
				enterOuterAlt(_localctx, 8);
				{
				setState(348);
				match(T__50);
				setState(349);
				emptyArgs();
				}
				break;
			case T__51:
				_localctx = new ReferencePropertyConstraintContext(_localctx);
				enterOuterAlt(_localctx, 9);
				{
				setState(350);
				match(T__51);
				setState(351);
				((ReferencePropertyConstraintContext)_localctx).args = classifierWithOrderConstraintListArgs();
				}
				break;
			case T__52:
				_localctx = new EntityPrimaryKeyExactNaturalContext(_localctx);
				enterOuterAlt(_localctx, 10);
				{
				setState(352);
				match(T__52);
				setState(355);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,12,_ctx) ) {
				case 1:
					{
					setState(353);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(354);
					((EntityPrimaryKeyExactNaturalContext)_localctx).args = valueArgs();
					}
					break;
				}
				}
				break;
			case T__53:
				_localctx = new EntityPrimaryKeyExactConstraintContext(_localctx);
				enterOuterAlt(_localctx, 11);
				{
				setState(357);
				match(T__53);
				setState(358);
				((EntityPrimaryKeyExactConstraintContext)_localctx).args = valueListArgs();
				}
				break;
			case T__54:
				_localctx = new EntityPrimaryKeyInFilterConstraintContext(_localctx);
				enterOuterAlt(_localctx, 12);
				{
				setState(359);
				match(T__54);
				setState(360);
				emptyArgs();
				}
				break;
			case T__55:
				_localctx = new EntityPropertyConstraintContext(_localctx);
				enterOuterAlt(_localctx, 13);
				{
				setState(361);
				match(T__55);
				setState(362);
				((EntityPropertyConstraintContext)_localctx).args = orderConstraintListArgs();
				}
				break;
			case T__56:
				_localctx = new EntityGroupPropertyConstraintContext(_localctx);
				enterOuterAlt(_localctx, 14);
				{
				setState(363);
				match(T__56);
				setState(364);
				((EntityGroupPropertyConstraintContext)_localctx).args = orderConstraintListArgs();
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
	public static class FacetSummary4ConstraintContext extends RequireConstraintContext {
		public FacetSummary4ArgsContext args;
		public FacetSummary4ArgsContext facetSummary4Args() {
			return getRuleContext(FacetSummary4ArgsContext.class,0);
		}
		public FacetSummary4ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetSummary4Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetSummary4Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetSummary4Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class HierarchyStatisticsConstraintContext extends RequireConstraintContext {
		public HierarchyStatisticsArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public HierarchyStatisticsArgsContext hierarchyStatisticsArgs() {
			return getRuleContext(HierarchyStatisticsArgsContext.class,0);
		}
		public HierarchyStatisticsConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHierarchyStatisticsConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHierarchyStatisticsConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHierarchyStatisticsConstraint(this);
			else return visitor.visitChildren(this);
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
		public ClassifierWithOptionalFilterConstraintArgsContext args;
		public ClassifierWithOptionalFilterConstraintArgsContext classifierWithOptionalFilterConstraintArgs() {
			return getRuleContext(ClassifierWithOptionalFilterConstraintArgsContext.class,0);
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
	public static class SingleRefReferenceContent7ConstraintContext extends RequireConstraintContext {
		public SingleRefReferenceContent7ArgsContext args;
		public SingleRefReferenceContent7ArgsContext singleRefReferenceContent7Args() {
			return getRuleContext(SingleRefReferenceContent7ArgsContext.class,0);
		}
		public SingleRefReferenceContent7ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContent7Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContent7Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContent7Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class SingleRefReferenceContentWithAttributes6ConstraintContext extends RequireConstraintContext {
		public SingleRefReferenceContentWithAttributes4ArgsContext args;
		public SingleRefReferenceContentWithAttributes4ArgsContext singleRefReferenceContentWithAttributes4Args() {
			return getRuleContext(SingleRefReferenceContentWithAttributes4ArgsContext.class,0);
		}
		public SingleRefReferenceContentWithAttributes6ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContentWithAttributes6Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContentWithAttributes6Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContentWithAttributes6Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class FullHierarchyOfReferenceWithBehaviourConstraintContext extends RequireConstraintContext {
		public FullHierarchyOfReferenceWithBehaviourArgsContext args;
		public FullHierarchyOfReferenceWithBehaviourArgsContext fullHierarchyOfReferenceWithBehaviourArgs() {
			return getRuleContext(FullHierarchyOfReferenceWithBehaviourArgsContext.class,0);
		}
		public FullHierarchyOfReferenceWithBehaviourConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFullHierarchyOfReferenceWithBehaviourConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFullHierarchyOfReferenceWithBehaviourConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFullHierarchyOfReferenceWithBehaviourConstraint(this);
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
	public static class SingleRefReferenceContent3ConstraintContext extends RequireConstraintContext {
		public SingleRefReferenceContent3ArgsContext args;
		public SingleRefReferenceContent3ArgsContext singleRefReferenceContent3Args() {
			return getRuleContext(SingleRefReferenceContent3ArgsContext.class,0);
		}
		public SingleRefReferenceContent3ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContent3Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContent3Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContent3Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class AllRefsWithAttributesReferenceContent3ConstraintContext extends RequireConstraintContext {
		public AllRefsWithAttributesReferenceContent3ArgsContext args;
		public AllRefsWithAttributesReferenceContent3ArgsContext allRefsWithAttributesReferenceContent3Args() {
			return getRuleContext(AllRefsWithAttributesReferenceContent3ArgsContext.class,0);
		}
		public AllRefsWithAttributesReferenceContent3ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAllRefsWithAttributesReferenceContent3Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAllRefsWithAttributesReferenceContent3Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAllRefsWithAttributesReferenceContent3Constraint(this);
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
	public static class BasicHierarchyOfReferenceWithBehaviourConstraintContext extends RequireConstraintContext {
		public BasicHierarchyOfReferenceWithBehaviourArgsContext args;
		public BasicHierarchyOfReferenceWithBehaviourArgsContext basicHierarchyOfReferenceWithBehaviourArgs() {
			return getRuleContext(BasicHierarchyOfReferenceWithBehaviourArgsContext.class,0);
		}
		public BasicHierarchyOfReferenceWithBehaviourConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterBasicHierarchyOfReferenceWithBehaviourConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitBasicHierarchyOfReferenceWithBehaviourConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitBasicHierarchyOfReferenceWithBehaviourConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class SingleRefReferenceContent8ConstraintContext extends RequireConstraintContext {
		public SingleRefReferenceContent8ArgsContext args;
		public SingleRefReferenceContent8ArgsContext singleRefReferenceContent8Args() {
			return getRuleContext(SingleRefReferenceContent8ArgsContext.class,0);
		}
		public SingleRefReferenceContent8ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContent8Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContent8Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContent8Constraint(this);
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
	public static class SingleRefReferenceContentWithAttributes12ConstraintContext extends RequireConstraintContext {
		public SingleRefReferenceContentWithAttributes8ArgsContext args;
		public SingleRefReferenceContentWithAttributes8ArgsContext singleRefReferenceContentWithAttributes8Args() {
			return getRuleContext(SingleRefReferenceContentWithAttributes8ArgsContext.class,0);
		}
		public SingleRefReferenceContentWithAttributes12ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContentWithAttributes12Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContentWithAttributes12Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContentWithAttributes12Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class SingleRefReferenceContentWithAttributes7ConstraintContext extends RequireConstraintContext {
		public SingleRefReferenceContent5ArgsContext args;
		public SingleRefReferenceContent5ArgsContext singleRefReferenceContent5Args() {
			return getRuleContext(SingleRefReferenceContent5ArgsContext.class,0);
		}
		public SingleRefReferenceContentWithAttributes7ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContentWithAttributes7Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContentWithAttributes7Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContentWithAttributes7Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class FacetSummary7ConstraintContext extends RequireConstraintContext {
		public FacetSummary7ArgsContext args;
		public FacetSummary7ArgsContext facetSummary7Args() {
			return getRuleContext(FacetSummary7ArgsContext.class,0);
		}
		public FacetSummary7ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetSummary7Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetSummary7Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetSummary7Constraint(this);
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
	public static class FacetSummary1ConstraintContext extends RequireConstraintContext {
		public FacetSummary1ArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public FacetSummary1ArgsContext facetSummary1Args() {
			return getRuleContext(FacetSummary1ArgsContext.class,0);
		}
		public FacetSummary1ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetSummary1Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetSummary1Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetSummary1Constraint(this);
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
	public static class SingleRefReferenceContent1ConstraintContext extends RequireConstraintContext {
		public SingleRefReferenceContent1ArgsContext args;
		public SingleRefReferenceContent1ArgsContext singleRefReferenceContent1Args() {
			return getRuleContext(SingleRefReferenceContent1ArgsContext.class,0);
		}
		public SingleRefReferenceContent1ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContent1Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContent1Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContent1Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class SingleRefReferenceContent4ConstraintContext extends RequireConstraintContext {
		public SingleRefReferenceContent4ArgsContext args;
		public SingleRefReferenceContent4ArgsContext singleRefReferenceContent4Args() {
			return getRuleContext(SingleRefReferenceContent4ArgsContext.class,0);
		}
		public SingleRefReferenceContent4ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContent4Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContent4Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContent4Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class DataInLocalesAllConstraintContext extends RequireConstraintContext {
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public DataInLocalesAllConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterDataInLocalesAllConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitDataInLocalesAllConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitDataInLocalesAllConstraint(this);
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
	public static class FacetGroupsConjunctionConstraintContext extends RequireConstraintContext {
		public ClassifierWithOptionalFilterConstraintArgsContext args;
		public ClassifierWithOptionalFilterConstraintArgsContext classifierWithOptionalFilterConstraintArgs() {
			return getRuleContext(ClassifierWithOptionalFilterConstraintArgsContext.class,0);
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
	public static class AttributeContentConstraintContext extends RequireConstraintContext {
		public ClassifierListArgsContext args;
		public ClassifierListArgsContext classifierListArgs() {
			return getRuleContext(ClassifierListArgsContext.class,0);
		}
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
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
	public static class SingleRefReferenceContentWithAttributes3ConstraintContext extends RequireConstraintContext {
		public SingleRefReferenceContentWithAttributes2ArgsContext args;
		public SingleRefReferenceContentWithAttributes2ArgsContext singleRefReferenceContentWithAttributes2Args() {
			return getRuleContext(SingleRefReferenceContentWithAttributes2ArgsContext.class,0);
		}
		public SingleRefReferenceContentWithAttributes3ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContentWithAttributes3Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContentWithAttributes3Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContentWithAttributes3Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class SingleRefReferenceContentWithAttributes1ConstraintContext extends RequireConstraintContext {
		public SingleRefReferenceContent1ArgsContext args;
		public SingleRefReferenceContent1ArgsContext singleRefReferenceContent1Args() {
			return getRuleContext(SingleRefReferenceContent1ArgsContext.class,0);
		}
		public SingleRefReferenceContentWithAttributes1ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContentWithAttributes1Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContentWithAttributes1Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContentWithAttributes1Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class FacetSummaryOfReference1ConstraintContext extends RequireConstraintContext {
		public ClassifierArgsContext args;
		public ClassifierArgsContext classifierArgs() {
			return getRuleContext(ClassifierArgsContext.class,0);
		}
		public FacetSummaryOfReference1ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetSummaryOfReference1Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetSummaryOfReference1Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetSummaryOfReference1Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class SingleRefReferenceContentWithAttributes8ConstraintContext extends RequireConstraintContext {
		public SingleRefReferenceContentWithAttributes5ArgsContext args;
		public SingleRefReferenceContentWithAttributes5ArgsContext singleRefReferenceContentWithAttributes5Args() {
			return getRuleContext(SingleRefReferenceContentWithAttributes5ArgsContext.class,0);
		}
		public SingleRefReferenceContentWithAttributes8ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContentWithAttributes8Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContentWithAttributes8Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContentWithAttributes8Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class SingleRefReferenceContent2ConstraintContext extends RequireConstraintContext {
		public SingleRefReferenceContent2ArgsContext args;
		public SingleRefReferenceContent2ArgsContext singleRefReferenceContent2Args() {
			return getRuleContext(SingleRefReferenceContent2ArgsContext.class,0);
		}
		public SingleRefReferenceContent2ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContent2Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContent2Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContent2Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class SingleRefReferenceContentWithAttributes11ConstraintContext extends RequireConstraintContext {
		public SingleRefReferenceContentWithAttributes7ArgsContext args;
		public SingleRefReferenceContentWithAttributes7ArgsContext singleRefReferenceContentWithAttributes7Args() {
			return getRuleContext(SingleRefReferenceContentWithAttributes7ArgsContext.class,0);
		}
		public SingleRefReferenceContentWithAttributes11ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContentWithAttributes11Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContentWithAttributes11Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContentWithAttributes11Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class AllRefsWithAttributesReferenceContent1ConstraintContext extends RequireConstraintContext {
		public AllRefsWithAttributesReferenceContent1ArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public AllRefsWithAttributesReferenceContent1ArgsContext allRefsWithAttributesReferenceContent1Args() {
			return getRuleContext(AllRefsWithAttributesReferenceContent1ArgsContext.class,0);
		}
		public AllRefsWithAttributesReferenceContent1ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAllRefsWithAttributesReferenceContent1Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAllRefsWithAttributesReferenceContent1Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAllRefsWithAttributesReferenceContent1Constraint(this);
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
	public static class FacetSummary6ConstraintContext extends RequireConstraintContext {
		public FacetSummary6ArgsContext args;
		public FacetSummary6ArgsContext facetSummary6Args() {
			return getRuleContext(FacetSummary6ArgsContext.class,0);
		}
		public FacetSummary6ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetSummary6Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetSummary6Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetSummary6Constraint(this);
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
	public static class PriceContentRespectingFilterConstraintContext extends RequireConstraintContext {
		public ValueListArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public ValueListArgsContext valueListArgs() {
			return getRuleContext(ValueListArgsContext.class,0);
		}
		public PriceContentRespectingFilterConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterPriceContentRespectingFilterConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitPriceContentRespectingFilterConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitPriceContentRespectingFilterConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class SingleRefReferenceContent5ConstraintContext extends RequireConstraintContext {
		public SingleRefReferenceContent5ArgsContext args;
		public SingleRefReferenceContent5ArgsContext singleRefReferenceContent5Args() {
			return getRuleContext(SingleRefReferenceContent5ArgsContext.class,0);
		}
		public SingleRefReferenceContent5ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContent5Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContent5Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContent5Constraint(this);
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
	public static class SingleRefReferenceContentWithAttributes4ConstraintContext extends RequireConstraintContext {
		public SingleRefReferenceContent3ArgsContext args;
		public SingleRefReferenceContent3ArgsContext singleRefReferenceContent3Args() {
			return getRuleContext(SingleRefReferenceContent3ArgsContext.class,0);
		}
		public SingleRefReferenceContentWithAttributes4ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContentWithAttributes4Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContentWithAttributes4Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContentWithAttributes4Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class FacetSummary3ConstraintContext extends RequireConstraintContext {
		public FacetSummary3ArgsContext args;
		public FacetSummary3ArgsContext facetSummary3Args() {
			return getRuleContext(FacetSummary3ArgsContext.class,0);
		}
		public FacetSummary3ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetSummary3Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetSummary3Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetSummary3Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class SingleRefReferenceContentWithAttributes9ConstraintContext extends RequireConstraintContext {
		public SingleRefReferenceContentWithAttributes6ArgsContext args;
		public SingleRefReferenceContentWithAttributes6ArgsContext singleRefReferenceContentWithAttributes6Args() {
			return getRuleContext(SingleRefReferenceContentWithAttributes6ArgsContext.class,0);
		}
		public SingleRefReferenceContentWithAttributes9ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContentWithAttributes9Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContentWithAttributes9Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContentWithAttributes9Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class PriceContentConstraintContext extends RequireConstraintContext {
		public PriceContentArgsContext args;
		public PriceContentArgsContext priceContentArgs() {
			return getRuleContext(PriceContentArgsContext.class,0);
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
	public static class SingleRefReferenceContentWithAttributes10ConstraintContext extends RequireConstraintContext {
		public SingleRefReferenceContent7ArgsContext args;
		public SingleRefReferenceContent7ArgsContext singleRefReferenceContent7Args() {
			return getRuleContext(SingleRefReferenceContent7ArgsContext.class,0);
		}
		public SingleRefReferenceContentWithAttributes10ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContentWithAttributes10Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContentWithAttributes10Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContentWithAttributes10Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class FacetSummary5ConstraintContext extends RequireConstraintContext {
		public FacetSummary5ArgsContext args;
		public FacetSummary5ArgsContext facetSummary5Args() {
			return getRuleContext(FacetSummary5ArgsContext.class,0);
		}
		public FacetSummary5ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetSummary5Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetSummary5Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetSummary5Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class AssociatedDataContentConstraintContext extends RequireConstraintContext {
		public ClassifierListArgsContext args;
		public ClassifierListArgsContext classifierListArgs() {
			return getRuleContext(ClassifierListArgsContext.class,0);
		}
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
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
	public static class SingleRefReferenceContentWithAttributes2ConstraintContext extends RequireConstraintContext {
		public SingleRefReferenceContentWithAttributes1ArgsContext args;
		public SingleRefReferenceContentWithAttributes1ArgsContext singleRefReferenceContentWithAttributes1Args() {
			return getRuleContext(SingleRefReferenceContentWithAttributes1ArgsContext.class,0);
		}
		public SingleRefReferenceContentWithAttributes2ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContentWithAttributes2Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContentWithAttributes2Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContentWithAttributes2Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class SingleRefReferenceContent6ConstraintContext extends RequireConstraintContext {
		public SingleRefReferenceContent6ArgsContext args;
		public SingleRefReferenceContent6ArgsContext singleRefReferenceContent6Args() {
			return getRuleContext(SingleRefReferenceContent6ArgsContext.class,0);
		}
		public SingleRefReferenceContent6ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContent6Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContent6Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContent6Constraint(this);
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
	public static class AllRefsWithAttributesReferenceContent2ConstraintContext extends RequireConstraintContext {
		public AllRefsWithAttributesReferenceContent2ArgsContext args;
		public AllRefsWithAttributesReferenceContent2ArgsContext allRefsWithAttributesReferenceContent2Args() {
			return getRuleContext(AllRefsWithAttributesReferenceContent2ArgsContext.class,0);
		}
		public AllRefsWithAttributesReferenceContent2ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAllRefsWithAttributesReferenceContent2Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAllRefsWithAttributesReferenceContent2Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAllRefsWithAttributesReferenceContent2Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class SingleRefReferenceContentWithAttributes5ConstraintContext extends RequireConstraintContext {
		public SingleRefReferenceContentWithAttributes3ArgsContext args;
		public SingleRefReferenceContentWithAttributes3ArgsContext singleRefReferenceContentWithAttributes3Args() {
			return getRuleContext(SingleRefReferenceContentWithAttributes3ArgsContext.class,0);
		}
		public SingleRefReferenceContentWithAttributes5ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContentWithAttributes5Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContentWithAttributes5Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContentWithAttributes5Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class PriceHistogramConstraintContext extends RequireConstraintContext {
		public PriceHistogramArgsContext args;
		public PriceHistogramArgsContext priceHistogramArgs() {
			return getRuleContext(PriceHistogramArgsContext.class,0);
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
	public static class FacetSummary2ConstraintContext extends RequireConstraintContext {
		public FacetSummary2ArgsContext args;
		public FacetSummary2ArgsContext facetSummary2Args() {
			return getRuleContext(FacetSummary2ArgsContext.class,0);
		}
		public FacetSummary2ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetSummary2Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetSummary2Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetSummary2Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class FacetSummaryOfReference2ConstraintContext extends RequireConstraintContext {
		public FacetSummaryOfReference2ArgsContext args;
		public FacetSummaryOfReference2ArgsContext facetSummaryOfReference2Args() {
			return getRuleContext(FacetSummaryOfReference2ArgsContext.class,0);
		}
		public FacetSummaryOfReference2ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetSummaryOfReference2Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetSummaryOfReference2Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetSummaryOfReference2Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class AttributeHistogramConstraintContext extends RequireConstraintContext {
		public AttributeHistogramArgsContext args;
		public AttributeHistogramArgsContext attributeHistogramArgs() {
			return getRuleContext(AttributeHistogramArgsContext.class,0);
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
	public static class FacetGroupsNegationConstraintContext extends RequireConstraintContext {
		public ClassifierWithOptionalFilterConstraintArgsContext args;
		public ClassifierWithOptionalFilterConstraintArgsContext classifierWithOptionalFilterConstraintArgs() {
			return getRuleContext(ClassifierWithOptionalFilterConstraintArgsContext.class,0);
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
		enterRule(_localctx, 22, RULE_requireConstraint);
		try {
			setState(543);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,22,_ctx) ) {
			case 1:
				_localctx = new RequireContainerConstraintContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(367);
				match(T__57);
				setState(370);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,14,_ctx) ) {
				case 1:
					{
					setState(368);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(369);
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
				setState(372);
				match(T__58);
				setState(373);
				((PageConstraintContext)_localctx).args = pageConstraintArgs();
				}
				break;
			case 3:
				_localctx = new StripConstraintContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(374);
				match(T__59);
				setState(375);
				((StripConstraintContext)_localctx).args = stripConstraintArgs();
				}
				break;
			case 4:
				_localctx = new EntityFetchConstraintContext(_localctx);
				enterOuterAlt(_localctx, 4);
				{
				setState(376);
				match(T__60);
				setState(379);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,15,_ctx) ) {
				case 1:
					{
					setState(377);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(378);
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
				setState(381);
				match(T__61);
				setState(384);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,16,_ctx) ) {
				case 1:
					{
					setState(382);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(383);
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
				setState(386);
				match(T__62);
				setState(387);
				((AttributeContentConstraintContext)_localctx).args = classifierListArgs();
				}
				break;
			case 7:
				_localctx = new AttributeContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 7);
				{
				setState(388);
				match(T__63);
				setState(389);
				emptyArgs();
				}
				break;
			case 8:
				_localctx = new PriceContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 8);
				{
				setState(390);
				match(T__64);
				setState(391);
				((PriceContentConstraintContext)_localctx).args = priceContentArgs();
				}
				break;
			case 9:
				_localctx = new PriceContentAllConstraintContext(_localctx);
				enterOuterAlt(_localctx, 9);
				{
				setState(392);
				match(T__65);
				setState(393);
				emptyArgs();
				}
				break;
			case 10:
				_localctx = new PriceContentRespectingFilterConstraintContext(_localctx);
				enterOuterAlt(_localctx, 10);
				{
				setState(394);
				match(T__66);
				setState(397);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,17,_ctx) ) {
				case 1:
					{
					setState(395);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(396);
					((PriceContentRespectingFilterConstraintContext)_localctx).args = valueListArgs();
					}
					break;
				}
				}
				break;
			case 11:
				_localctx = new AssociatedDataContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 11);
				{
				setState(399);
				match(T__67);
				setState(400);
				((AssociatedDataContentConstraintContext)_localctx).args = classifierListArgs();
				}
				break;
			case 12:
				_localctx = new AssociatedDataContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 12);
				{
				setState(401);
				match(T__68);
				setState(402);
				emptyArgs();
				}
				break;
			case 13:
				_localctx = new AllRefsReferenceContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 13);
				{
				setState(403);
				match(T__69);
				setState(406);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,18,_ctx) ) {
				case 1:
					{
					setState(404);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(405);
					((AllRefsReferenceContentConstraintContext)_localctx).args = allRefsReferenceContentArgs();
					}
					break;
				}
				}
				break;
			case 14:
				_localctx = new MultipleRefsReferenceContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 14);
				{
				setState(408);
				match(T__70);
				setState(409);
				((MultipleRefsReferenceContentConstraintContext)_localctx).args = multipleRefsReferenceContentArgs();
				}
				break;
			case 15:
				_localctx = new SingleRefReferenceContent1ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 15);
				{
				setState(410);
				match(T__70);
				setState(411);
				((SingleRefReferenceContent1ConstraintContext)_localctx).args = singleRefReferenceContent1Args();
				}
				break;
			case 16:
				_localctx = new SingleRefReferenceContent2ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 16);
				{
				setState(412);
				match(T__70);
				setState(413);
				((SingleRefReferenceContent2ConstraintContext)_localctx).args = singleRefReferenceContent2Args();
				}
				break;
			case 17:
				_localctx = new SingleRefReferenceContent3ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 17);
				{
				setState(414);
				match(T__70);
				setState(415);
				((SingleRefReferenceContent3ConstraintContext)_localctx).args = singleRefReferenceContent3Args();
				}
				break;
			case 18:
				_localctx = new SingleRefReferenceContent4ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 18);
				{
				setState(416);
				match(T__70);
				setState(417);
				((SingleRefReferenceContent4ConstraintContext)_localctx).args = singleRefReferenceContent4Args();
				}
				break;
			case 19:
				_localctx = new SingleRefReferenceContent5ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 19);
				{
				setState(418);
				match(T__70);
				setState(419);
				((SingleRefReferenceContent5ConstraintContext)_localctx).args = singleRefReferenceContent5Args();
				}
				break;
			case 20:
				_localctx = new SingleRefReferenceContent6ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 20);
				{
				setState(420);
				match(T__70);
				setState(421);
				((SingleRefReferenceContent6ConstraintContext)_localctx).args = singleRefReferenceContent6Args();
				}
				break;
			case 21:
				_localctx = new SingleRefReferenceContent7ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 21);
				{
				setState(422);
				match(T__70);
				setState(423);
				((SingleRefReferenceContent7ConstraintContext)_localctx).args = singleRefReferenceContent7Args();
				}
				break;
			case 22:
				_localctx = new SingleRefReferenceContent8ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 22);
				{
				setState(424);
				match(T__70);
				setState(425);
				((SingleRefReferenceContent8ConstraintContext)_localctx).args = singleRefReferenceContent8Args();
				}
				break;
			case 23:
				_localctx = new AllRefsWithAttributesReferenceContent1ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 23);
				{
				setState(426);
				match(T__71);
				setState(429);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,19,_ctx) ) {
				case 1:
					{
					setState(427);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(428);
					((AllRefsWithAttributesReferenceContent1ConstraintContext)_localctx).args = allRefsWithAttributesReferenceContent1Args();
					}
					break;
				}
				}
				break;
			case 24:
				_localctx = new AllRefsWithAttributesReferenceContent2ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 24);
				{
				setState(431);
				match(T__71);
				setState(432);
				((AllRefsWithAttributesReferenceContent2ConstraintContext)_localctx).args = allRefsWithAttributesReferenceContent2Args();
				}
				break;
			case 25:
				_localctx = new AllRefsWithAttributesReferenceContent3ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 25);
				{
				setState(433);
				match(T__71);
				setState(434);
				((AllRefsWithAttributesReferenceContent3ConstraintContext)_localctx).args = allRefsWithAttributesReferenceContent3Args();
				}
				break;
			case 26:
				_localctx = new SingleRefReferenceContentWithAttributes1ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 26);
				{
				setState(435);
				match(T__72);
				setState(436);
				((SingleRefReferenceContentWithAttributes1ConstraintContext)_localctx).args = singleRefReferenceContent1Args();
				}
				break;
			case 27:
				_localctx = new SingleRefReferenceContentWithAttributes2ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 27);
				{
				setState(437);
				match(T__72);
				setState(438);
				((SingleRefReferenceContentWithAttributes2ConstraintContext)_localctx).args = singleRefReferenceContentWithAttributes1Args();
				}
				break;
			case 28:
				_localctx = new SingleRefReferenceContentWithAttributes3ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 28);
				{
				setState(439);
				match(T__72);
				setState(440);
				((SingleRefReferenceContentWithAttributes3ConstraintContext)_localctx).args = singleRefReferenceContentWithAttributes2Args();
				}
				break;
			case 29:
				_localctx = new SingleRefReferenceContentWithAttributes4ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 29);
				{
				setState(441);
				match(T__72);
				setState(442);
				((SingleRefReferenceContentWithAttributes4ConstraintContext)_localctx).args = singleRefReferenceContent3Args();
				}
				break;
			case 30:
				_localctx = new SingleRefReferenceContentWithAttributes5ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 30);
				{
				setState(443);
				match(T__72);
				setState(444);
				((SingleRefReferenceContentWithAttributes5ConstraintContext)_localctx).args = singleRefReferenceContentWithAttributes3Args();
				}
				break;
			case 31:
				_localctx = new SingleRefReferenceContentWithAttributes6ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 31);
				{
				setState(445);
				match(T__72);
				setState(446);
				((SingleRefReferenceContentWithAttributes6ConstraintContext)_localctx).args = singleRefReferenceContentWithAttributes4Args();
				}
				break;
			case 32:
				_localctx = new SingleRefReferenceContentWithAttributes7ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 32);
				{
				setState(447);
				match(T__72);
				setState(448);
				((SingleRefReferenceContentWithAttributes7ConstraintContext)_localctx).args = singleRefReferenceContent5Args();
				}
				break;
			case 33:
				_localctx = new SingleRefReferenceContentWithAttributes8ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 33);
				{
				setState(449);
				match(T__72);
				setState(450);
				((SingleRefReferenceContentWithAttributes8ConstraintContext)_localctx).args = singleRefReferenceContentWithAttributes5Args();
				}
				break;
			case 34:
				_localctx = new SingleRefReferenceContentWithAttributes9ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 34);
				{
				setState(451);
				match(T__72);
				setState(452);
				((SingleRefReferenceContentWithAttributes9ConstraintContext)_localctx).args = singleRefReferenceContentWithAttributes6Args();
				}
				break;
			case 35:
				_localctx = new SingleRefReferenceContentWithAttributes10ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 35);
				{
				setState(453);
				match(T__72);
				setState(454);
				((SingleRefReferenceContentWithAttributes10ConstraintContext)_localctx).args = singleRefReferenceContent7Args();
				}
				break;
			case 36:
				_localctx = new SingleRefReferenceContentWithAttributes11ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 36);
				{
				setState(455);
				match(T__72);
				setState(456);
				((SingleRefReferenceContentWithAttributes11ConstraintContext)_localctx).args = singleRefReferenceContentWithAttributes7Args();
				}
				break;
			case 37:
				_localctx = new SingleRefReferenceContentWithAttributes12ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 37);
				{
				setState(457);
				match(T__72);
				setState(458);
				((SingleRefReferenceContentWithAttributes12ConstraintContext)_localctx).args = singleRefReferenceContentWithAttributes8Args();
				}
				break;
			case 38:
				_localctx = new EmptyHierarchyContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 38);
				{
				setState(459);
				match(T__73);
				setState(460);
				emptyArgs();
				}
				break;
			case 39:
				_localctx = new SingleRequireHierarchyContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 39);
				{
				setState(461);
				match(T__73);
				setState(462);
				((SingleRequireHierarchyContentConstraintContext)_localctx).args = singleRequireHierarchyContentArgs();
				}
				break;
			case 40:
				_localctx = new AllRequiresHierarchyContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 40);
				{
				setState(463);
				match(T__73);
				setState(464);
				((AllRequiresHierarchyContentConstraintContext)_localctx).args = allRequiresHierarchyContentArgs();
				}
				break;
			case 41:
				_localctx = new PriceTypeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 41);
				{
				setState(465);
				match(T__74);
				setState(466);
				((PriceTypeConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case 42:
				_localctx = new DataInLocalesAllConstraintContext(_localctx);
				enterOuterAlt(_localctx, 42);
				{
				setState(467);
				match(T__75);
				setState(468);
				emptyArgs();
				}
				break;
			case 43:
				_localctx = new DataInLocalesConstraintContext(_localctx);
				enterOuterAlt(_localctx, 43);
				{
				setState(469);
				match(T__76);
				setState(470);
				((DataInLocalesConstraintContext)_localctx).args = valueListArgs();
				}
				break;
			case 44:
				_localctx = new FacetSummary1ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 44);
				{
				setState(471);
				match(T__77);
				setState(474);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,20,_ctx) ) {
				case 1:
					{
					setState(472);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(473);
					((FacetSummary1ConstraintContext)_localctx).args = facetSummary1Args();
					}
					break;
				}
				}
				break;
			case 45:
				_localctx = new FacetSummary2ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 45);
				{
				setState(476);
				match(T__77);
				setState(477);
				((FacetSummary2ConstraintContext)_localctx).args = facetSummary2Args();
				}
				break;
			case 46:
				_localctx = new FacetSummary3ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 46);
				{
				setState(478);
				match(T__77);
				setState(479);
				((FacetSummary3ConstraintContext)_localctx).args = facetSummary3Args();
				}
				break;
			case 47:
				_localctx = new FacetSummary4ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 47);
				{
				setState(480);
				match(T__77);
				setState(481);
				((FacetSummary4ConstraintContext)_localctx).args = facetSummary4Args();
				}
				break;
			case 48:
				_localctx = new FacetSummary5ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 48);
				{
				setState(482);
				match(T__77);
				setState(483);
				((FacetSummary5ConstraintContext)_localctx).args = facetSummary5Args();
				}
				break;
			case 49:
				_localctx = new FacetSummary6ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 49);
				{
				setState(484);
				match(T__77);
				setState(485);
				((FacetSummary6ConstraintContext)_localctx).args = facetSummary6Args();
				}
				break;
			case 50:
				_localctx = new FacetSummary7ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 50);
				{
				setState(486);
				match(T__77);
				setState(487);
				((FacetSummary7ConstraintContext)_localctx).args = facetSummary7Args();
				}
				break;
			case 51:
				_localctx = new FacetSummaryOfReference1ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 51);
				{
				setState(488);
				match(T__78);
				setState(489);
				((FacetSummaryOfReference1ConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case 52:
				_localctx = new FacetSummaryOfReference2ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 52);
				{
				setState(490);
				match(T__78);
				setState(491);
				((FacetSummaryOfReference2ConstraintContext)_localctx).args = facetSummaryOfReference2Args();
				}
				break;
			case 53:
				_localctx = new FacetGroupsConjunctionConstraintContext(_localctx);
				enterOuterAlt(_localctx, 53);
				{
				setState(492);
				match(T__79);
				setState(493);
				((FacetGroupsConjunctionConstraintContext)_localctx).args = classifierWithOptionalFilterConstraintArgs();
				}
				break;
			case 54:
				_localctx = new FacetGroupsDisjunctionConstraintContext(_localctx);
				enterOuterAlt(_localctx, 54);
				{
				setState(494);
				match(T__80);
				setState(495);
				((FacetGroupsDisjunctionConstraintContext)_localctx).args = classifierWithOptionalFilterConstraintArgs();
				}
				break;
			case 55:
				_localctx = new FacetGroupsNegationConstraintContext(_localctx);
				enterOuterAlt(_localctx, 55);
				{
				setState(496);
				match(T__81);
				setState(497);
				((FacetGroupsNegationConstraintContext)_localctx).args = classifierWithOptionalFilterConstraintArgs();
				}
				break;
			case 56:
				_localctx = new AttributeHistogramConstraintContext(_localctx);
				enterOuterAlt(_localctx, 56);
				{
				setState(498);
				match(T__82);
				setState(499);
				((AttributeHistogramConstraintContext)_localctx).args = attributeHistogramArgs();
				}
				break;
			case 57:
				_localctx = new PriceHistogramConstraintContext(_localctx);
				enterOuterAlt(_localctx, 57);
				{
				setState(500);
				match(T__83);
				setState(501);
				((PriceHistogramConstraintContext)_localctx).args = priceHistogramArgs();
				}
				break;
			case 58:
				_localctx = new HierarchyDistanceConstraintContext(_localctx);
				enterOuterAlt(_localctx, 58);
				{
				setState(502);
				match(T__84);
				setState(503);
				((HierarchyDistanceConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case 59:
				_localctx = new HierarchyLevelConstraintContext(_localctx);
				enterOuterAlt(_localctx, 59);
				{
				setState(504);
				match(T__85);
				setState(505);
				((HierarchyLevelConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case 60:
				_localctx = new HierarchyNodeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 60);
				{
				setState(506);
				match(T__86);
				setState(507);
				((HierarchyNodeConstraintContext)_localctx).args = filterConstraintArgs();
				}
				break;
			case 61:
				_localctx = new HierarchyStopAtConstraintContext(_localctx);
				enterOuterAlt(_localctx, 61);
				{
				setState(508);
				match(T__87);
				setState(509);
				((HierarchyStopAtConstraintContext)_localctx).args = requireConstraintArgs();
				}
				break;
			case 62:
				_localctx = new HierarchyStatisticsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 62);
				{
				setState(510);
				match(T__88);
				setState(513);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,21,_ctx) ) {
				case 1:
					{
					setState(511);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(512);
					((HierarchyStatisticsConstraintContext)_localctx).args = hierarchyStatisticsArgs();
					}
					break;
				}
				}
				break;
			case 63:
				_localctx = new HierarchyFromRootConstraintContext(_localctx);
				enterOuterAlt(_localctx, 63);
				{
				setState(515);
				match(T__89);
				setState(516);
				((HierarchyFromRootConstraintContext)_localctx).args = hierarchyRequireConstraintArgs();
				}
				break;
			case 64:
				_localctx = new HierarchyFromNodeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 64);
				{
				setState(517);
				match(T__90);
				setState(518);
				((HierarchyFromNodeConstraintContext)_localctx).args = hierarchyFromNodeArgs();
				}
				break;
			case 65:
				_localctx = new HierarchyChildrenConstraintContext(_localctx);
				enterOuterAlt(_localctx, 65);
				{
				setState(519);
				match(T__91);
				setState(520);
				((HierarchyChildrenConstraintContext)_localctx).args = hierarchyRequireConstraintArgs();
				}
				break;
			case 66:
				_localctx = new EmptyHierarchySiblingsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 66);
				{
				setState(521);
				match(T__92);
				setState(522);
				emptyArgs();
				}
				break;
			case 67:
				_localctx = new BasicHierarchySiblingsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 67);
				{
				setState(523);
				match(T__92);
				setState(524);
				((BasicHierarchySiblingsConstraintContext)_localctx).args = requireConstraintListArgs();
				}
				break;
			case 68:
				_localctx = new FullHierarchySiblingsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 68);
				{
				setState(525);
				match(T__92);
				setState(526);
				((FullHierarchySiblingsConstraintContext)_localctx).args = hierarchyRequireConstraintArgs();
				}
				break;
			case 69:
				_localctx = new HierarchyParentsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 69);
				{
				setState(527);
				match(T__93);
				setState(528);
				((HierarchyParentsConstraintContext)_localctx).args = hierarchyRequireConstraintArgs();
				}
				break;
			case 70:
				_localctx = new BasicHierarchyOfSelfConstraintContext(_localctx);
				enterOuterAlt(_localctx, 70);
				{
				setState(529);
				match(T__94);
				setState(530);
				((BasicHierarchyOfSelfConstraintContext)_localctx).args = requireConstraintListArgs();
				}
				break;
			case 71:
				_localctx = new FullHierarchyOfSelfConstraintContext(_localctx);
				enterOuterAlt(_localctx, 71);
				{
				setState(531);
				match(T__94);
				setState(532);
				((FullHierarchyOfSelfConstraintContext)_localctx).args = fullHierarchyOfSelfArgs();
				}
				break;
			case 72:
				_localctx = new BasicHierarchyOfReferenceConstraintContext(_localctx);
				enterOuterAlt(_localctx, 72);
				{
				setState(533);
				match(T__95);
				setState(534);
				((BasicHierarchyOfReferenceConstraintContext)_localctx).args = basicHierarchyOfReferenceArgs();
				}
				break;
			case 73:
				_localctx = new BasicHierarchyOfReferenceWithBehaviourConstraintContext(_localctx);
				enterOuterAlt(_localctx, 73);
				{
				setState(535);
				match(T__95);
				setState(536);
				((BasicHierarchyOfReferenceWithBehaviourConstraintContext)_localctx).args = basicHierarchyOfReferenceWithBehaviourArgs();
				}
				break;
			case 74:
				_localctx = new FullHierarchyOfReferenceConstraintContext(_localctx);
				enterOuterAlt(_localctx, 74);
				{
				setState(537);
				match(T__95);
				setState(538);
				((FullHierarchyOfReferenceConstraintContext)_localctx).args = fullHierarchyOfReferenceArgs();
				}
				break;
			case 75:
				_localctx = new FullHierarchyOfReferenceWithBehaviourConstraintContext(_localctx);
				enterOuterAlt(_localctx, 75);
				{
				setState(539);
				match(T__95);
				setState(540);
				((FullHierarchyOfReferenceWithBehaviourConstraintContext)_localctx).args = fullHierarchyOfReferenceWithBehaviourArgs();
				}
				break;
			case 76:
				_localctx = new QueryTelemetryConstraintContext(_localctx);
				enterOuterAlt(_localctx, 76);
				{
				setState(541);
				match(T__96);
				setState(542);
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
		enterRule(_localctx, 24, RULE_headConstraintList);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(545);
			((HeadConstraintListContext)_localctx).headConstraint = headConstraint();
			((HeadConstraintListContext)_localctx).constraints.add(((HeadConstraintListContext)_localctx).headConstraint);
			setState(550);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(546);
				match(ARGS_DELIMITER);
				setState(547);
				((HeadConstraintListContext)_localctx).headConstraint = headConstraint();
				((HeadConstraintListContext)_localctx).constraints.add(((HeadConstraintListContext)_localctx).headConstraint);
				}
				}
				setState(552);
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
		enterRule(_localctx, 26, RULE_filterConstraintList);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(553);
			((FilterConstraintListContext)_localctx).filterConstraint = filterConstraint();
			((FilterConstraintListContext)_localctx).constraints.add(((FilterConstraintListContext)_localctx).filterConstraint);
			setState(558);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(554);
				match(ARGS_DELIMITER);
				setState(555);
				((FilterConstraintListContext)_localctx).filterConstraint = filterConstraint();
				((FilterConstraintListContext)_localctx).constraints.add(((FilterConstraintListContext)_localctx).filterConstraint);
				}
				}
				setState(560);
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
		enterRule(_localctx, 28, RULE_orderConstraintList);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(561);
			((OrderConstraintListContext)_localctx).orderConstraint = orderConstraint();
			((OrderConstraintListContext)_localctx).constraints.add(((OrderConstraintListContext)_localctx).orderConstraint);
			setState(566);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(562);
				match(ARGS_DELIMITER);
				setState(563);
				((OrderConstraintListContext)_localctx).orderConstraint = orderConstraint();
				((OrderConstraintListContext)_localctx).constraints.add(((OrderConstraintListContext)_localctx).orderConstraint);
				}
				}
				setState(568);
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
		enterRule(_localctx, 30, RULE_requireConstraintList);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(569);
			((RequireConstraintListContext)_localctx).requireConstraint = requireConstraint();
			((RequireConstraintListContext)_localctx).constraints.add(((RequireConstraintListContext)_localctx).requireConstraint);
			setState(574);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(570);
				match(ARGS_DELIMITER);
				setState(571);
				((RequireConstraintListContext)_localctx).requireConstraint = requireConstraint();
				((RequireConstraintListContext)_localctx).constraints.add(((RequireConstraintListContext)_localctx).requireConstraint);
				}
				}
				setState(576);
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

	public static class ArgsOpeningContext extends ParserRuleContext {
		public TerminalNode ARGS_OPENING() { return getToken(EvitaQLParser.ARGS_OPENING, 0); }
		public ArgsOpeningContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_argsOpening; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterArgsOpening(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitArgsOpening(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitArgsOpening(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ArgsOpeningContext argsOpening() throws RecognitionException {
		ArgsOpeningContext _localctx = new ArgsOpeningContext(_ctx, getState());
		enterRule(_localctx, 32, RULE_argsOpening);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(577);
			match(ARGS_OPENING);
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

	public static class ArgsClosingContext extends ParserRuleContext {
		public TerminalNode ARGS_CLOSING() { return getToken(EvitaQLParser.ARGS_CLOSING, 0); }
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public ArgsClosingContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_argsClosing; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterArgsClosing(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitArgsClosing(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitArgsClosing(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ArgsClosingContext argsClosing() throws RecognitionException {
		ArgsClosingContext _localctx = new ArgsClosingContext(_ctx, getState());
		enterRule(_localctx, 34, RULE_argsClosing);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(580);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==ARGS_DELIMITER) {
				{
				setState(579);
				match(ARGS_DELIMITER);
				}
			}

			setState(582);
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

	public static class ConstraintListArgsContext extends ParserRuleContext {
		public ConstraintContext constraint;
		public List<ConstraintContext> constraints = new ArrayList<ConstraintContext>();
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
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
		enterRule(_localctx, 36, RULE_constraintListArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(584);
			argsOpening();
			setState(585);
			((ConstraintListArgsContext)_localctx).constraint = constraint();
			((ConstraintListArgsContext)_localctx).constraints.add(((ConstraintListArgsContext)_localctx).constraint);
			setState(590);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,28,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(586);
					match(ARGS_DELIMITER);
					setState(587);
					((ConstraintListArgsContext)_localctx).constraint = constraint();
					((ConstraintListArgsContext)_localctx).constraints.add(((ConstraintListArgsContext)_localctx).constraint);
					}
					}
				}
				setState(592);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,28,_ctx);
			}
			setState(593);
			argsClosing();
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
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
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
		enterRule(_localctx, 38, RULE_emptyArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(595);
			argsOpening();
			setState(596);
			argsClosing();
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
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
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
		enterRule(_localctx, 40, RULE_filterConstraintListArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(598);
			argsOpening();
			setState(599);
			((FilterConstraintListArgsContext)_localctx).filterConstraint = filterConstraint();
			((FilterConstraintListArgsContext)_localctx).constraints.add(((FilterConstraintListArgsContext)_localctx).filterConstraint);
			setState(604);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,29,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(600);
					match(ARGS_DELIMITER);
					setState(601);
					((FilterConstraintListArgsContext)_localctx).filterConstraint = filterConstraint();
					((FilterConstraintListArgsContext)_localctx).constraints.add(((FilterConstraintListArgsContext)_localctx).filterConstraint);
					}
					}
				}
				setState(606);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,29,_ctx);
			}
			setState(607);
			argsClosing();
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
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
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
		enterRule(_localctx, 42, RULE_filterConstraintArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(609);
			argsOpening();
			setState(610);
			((FilterConstraintArgsContext)_localctx).filter = filterConstraint();
			setState(611);
			argsClosing();
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
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
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
		enterRule(_localctx, 44, RULE_orderConstraintListArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(613);
			argsOpening();
			setState(614);
			((OrderConstraintListArgsContext)_localctx).orderConstraint = orderConstraint();
			((OrderConstraintListArgsContext)_localctx).constraints.add(((OrderConstraintListArgsContext)_localctx).orderConstraint);
			setState(619);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,30,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(615);
					match(ARGS_DELIMITER);
					setState(616);
					((OrderConstraintListArgsContext)_localctx).orderConstraint = orderConstraint();
					((OrderConstraintListArgsContext)_localctx).constraints.add(((OrderConstraintListArgsContext)_localctx).orderConstraint);
					}
					}
				}
				setState(621);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,30,_ctx);
			}
			setState(622);
			argsClosing();
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
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
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
		enterRule(_localctx, 46, RULE_requireConstraintArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(624);
			argsOpening();
			setState(625);
			((RequireConstraintArgsContext)_localctx).requirement = requireConstraint();
			setState(626);
			argsClosing();
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
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
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
		enterRule(_localctx, 48, RULE_requireConstraintListArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(628);
			argsOpening();
			setState(629);
			((RequireConstraintListArgsContext)_localctx).requireConstraint = requireConstraint();
			((RequireConstraintListArgsContext)_localctx).requirements.add(((RequireConstraintListArgsContext)_localctx).requireConstraint);
			setState(634);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,31,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(630);
					match(ARGS_DELIMITER);
					setState(631);
					((RequireConstraintListArgsContext)_localctx).requireConstraint = requireConstraint();
					((RequireConstraintListArgsContext)_localctx).requirements.add(((RequireConstraintListArgsContext)_localctx).requireConstraint);
					}
					}
				}
				setState(636);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,31,_ctx);
			}
			setState(637);
			argsClosing();
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
		public ValueTokenContext classifier;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
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
		enterRule(_localctx, 50, RULE_classifierArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(639);
			argsOpening();
			setState(640);
			((ClassifierArgsContext)_localctx).classifier = valueToken();
			setState(641);
			argsClosing();
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
		public ValueTokenContext classifier;
		public ValueTokenContext value;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public List<ValueTokenContext> valueToken() {
			return getRuleContexts(ValueTokenContext.class);
		}
		public ValueTokenContext valueToken(int i) {
			return getRuleContext(ValueTokenContext.class,i);
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
		enterRule(_localctx, 52, RULE_classifierWithValueArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(643);
			argsOpening();
			setState(644);
			((ClassifierWithValueArgsContext)_localctx).classifier = valueToken();
			setState(645);
			match(ARGS_DELIMITER);
			setState(646);
			((ClassifierWithValueArgsContext)_localctx).value = valueToken();
			setState(647);
			argsClosing();
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
		public ValueTokenContext classifier;
		public ValueTokenContext value;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public List<ValueTokenContext> valueToken() {
			return getRuleContexts(ValueTokenContext.class);
		}
		public ValueTokenContext valueToken(int i) {
			return getRuleContext(ValueTokenContext.class,i);
		}
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
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
		enterRule(_localctx, 54, RULE_classifierWithOptionalValueArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(649);
			argsOpening();
			setState(650);
			((ClassifierWithOptionalValueArgsContext)_localctx).classifier = valueToken();
			setState(653);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,32,_ctx) ) {
			case 1:
				{
				setState(651);
				match(ARGS_DELIMITER);
				setState(652);
				((ClassifierWithOptionalValueArgsContext)_localctx).value = valueToken();
				}
				break;
			}
			setState(655);
			argsClosing();
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
		public ValueTokenContext classifier;
		public VariadicValueTokensContext values;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
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
		enterRule(_localctx, 56, RULE_classifierWithValueListArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(657);
			argsOpening();
			setState(658);
			((ClassifierWithValueListArgsContext)_localctx).classifier = valueToken();
			setState(659);
			match(ARGS_DELIMITER);
			setState(660);
			((ClassifierWithValueListArgsContext)_localctx).values = variadicValueTokens();
			setState(661);
			argsClosing();
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

	public static class ClassifierWithOptionalValueListArgsContext extends ParserRuleContext {
		public ValueTokenContext classifier;
		public VariadicValueTokensContext values;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
		}
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public VariadicValueTokensContext variadicValueTokens() {
			return getRuleContext(VariadicValueTokensContext.class,0);
		}
		public ClassifierWithOptionalValueListArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_classifierWithOptionalValueListArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterClassifierWithOptionalValueListArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitClassifierWithOptionalValueListArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitClassifierWithOptionalValueListArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ClassifierWithOptionalValueListArgsContext classifierWithOptionalValueListArgs() throws RecognitionException {
		ClassifierWithOptionalValueListArgsContext _localctx = new ClassifierWithOptionalValueListArgsContext(_ctx, getState());
		enterRule(_localctx, 58, RULE_classifierWithOptionalValueListArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(663);
			argsOpening();
			setState(664);
			((ClassifierWithOptionalValueListArgsContext)_localctx).classifier = valueToken();
			setState(667);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,33,_ctx) ) {
			case 1:
				{
				setState(665);
				match(ARGS_DELIMITER);
				setState(666);
				((ClassifierWithOptionalValueListArgsContext)_localctx).values = variadicValueTokens();
				}
				break;
			}
			setState(669);
			argsClosing();
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
		public ValueTokenContext classifier;
		public ValueTokenContext valueFrom;
		public ValueTokenContext valueTo;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
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
		enterRule(_localctx, 60, RULE_classifierWithBetweenValuesArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(671);
			argsOpening();
			setState(672);
			((ClassifierWithBetweenValuesArgsContext)_localctx).classifier = valueToken();
			setState(673);
			match(ARGS_DELIMITER);
			setState(674);
			((ClassifierWithBetweenValuesArgsContext)_localctx).valueFrom = valueToken();
			setState(675);
			match(ARGS_DELIMITER);
			setState(676);
			((ClassifierWithBetweenValuesArgsContext)_localctx).valueTo = valueToken();
			setState(677);
			argsClosing();
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
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
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
		enterRule(_localctx, 62, RULE_valueArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(679);
			argsOpening();
			setState(680);
			((ValueArgsContext)_localctx).value = valueToken();
			setState(681);
			argsClosing();
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
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
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
		enterRule(_localctx, 64, RULE_valueListArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(683);
			argsOpening();
			setState(684);
			((ValueListArgsContext)_localctx).values = variadicValueTokens();
			setState(685);
			argsClosing();
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
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
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
		enterRule(_localctx, 66, RULE_betweenValuesArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(687);
			argsOpening();
			setState(688);
			((BetweenValuesArgsContext)_localctx).valueFrom = valueToken();
			setState(689);
			match(ARGS_DELIMITER);
			setState(690);
			((BetweenValuesArgsContext)_localctx).valueTo = valueToken();
			setState(691);
			argsClosing();
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
		public VariadicValueTokensContext classifiers;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public VariadicValueTokensContext variadicValueTokens() {
			return getRuleContext(VariadicValueTokensContext.class,0);
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
		enterRule(_localctx, 68, RULE_classifierListArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(693);
			argsOpening();
			setState(694);
			((ClassifierListArgsContext)_localctx).classifiers = variadicValueTokens();
			setState(695);
			argsClosing();
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
		public ValueTokenContext classifier;
		public FilterConstraintContext filter;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
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
		enterRule(_localctx, 70, RULE_classifierWithFilterConstraintArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(697);
			argsOpening();
			setState(698);
			((ClassifierWithFilterConstraintArgsContext)_localctx).classifier = valueToken();
			setState(699);
			match(ARGS_DELIMITER);
			setState(700);
			((ClassifierWithFilterConstraintArgsContext)_localctx).filter = filterConstraint();
			setState(701);
			argsClosing();
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

	public static class ClassifierWithOptionalFilterConstraintArgsContext extends ParserRuleContext {
		public ValueTokenContext classifier;
		public FilterConstraintContext filter;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
		}
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public FilterConstraintContext filterConstraint() {
			return getRuleContext(FilterConstraintContext.class,0);
		}
		public ClassifierWithOptionalFilterConstraintArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_classifierWithOptionalFilterConstraintArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterClassifierWithOptionalFilterConstraintArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitClassifierWithOptionalFilterConstraintArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitClassifierWithOptionalFilterConstraintArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ClassifierWithOptionalFilterConstraintArgsContext classifierWithOptionalFilterConstraintArgs() throws RecognitionException {
		ClassifierWithOptionalFilterConstraintArgsContext _localctx = new ClassifierWithOptionalFilterConstraintArgsContext(_ctx, getState());
		enterRule(_localctx, 72, RULE_classifierWithOptionalFilterConstraintArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(703);
			argsOpening();
			setState(704);
			((ClassifierWithOptionalFilterConstraintArgsContext)_localctx).classifier = valueToken();
			setState(707);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,34,_ctx) ) {
			case 1:
				{
				setState(705);
				match(ARGS_DELIMITER);
				setState(706);
				((ClassifierWithOptionalFilterConstraintArgsContext)_localctx).filter = filterConstraint();
				}
				break;
			}
			setState(709);
			argsClosing();
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
		public ValueTokenContext classifier;
		public OrderConstraintContext orderConstraint;
		public List<OrderConstraintContext> constrains = new ArrayList<OrderConstraintContext>();
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
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
		enterRule(_localctx, 74, RULE_classifierWithOrderConstraintListArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(711);
			argsOpening();
			setState(712);
			((ClassifierWithOrderConstraintListArgsContext)_localctx).classifier = valueToken();
			setState(715);
			_errHandler.sync(this);
			_alt = 1;
			do {
				switch (_alt) {
				case 1:
					{
					{
					setState(713);
					match(ARGS_DELIMITER);
					setState(714);
					((ClassifierWithOrderConstraintListArgsContext)_localctx).orderConstraint = orderConstraint();
					((ClassifierWithOrderConstraintListArgsContext)_localctx).constrains.add(((ClassifierWithOrderConstraintListArgsContext)_localctx).orderConstraint);
					}
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				setState(717);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,35,_ctx);
			} while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER );
			setState(719);
			argsClosing();
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
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
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
		enterRule(_localctx, 76, RULE_valueWithRequireConstraintListArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(721);
			argsOpening();
			setState(722);
			((ValueWithRequireConstraintListArgsContext)_localctx).value = valueToken();
			setState(727);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,36,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(723);
					match(ARGS_DELIMITER);
					setState(724);
					((ValueWithRequireConstraintListArgsContext)_localctx).requireConstraint = requireConstraint();
					((ValueWithRequireConstraintListArgsContext)_localctx).requirements.add(((ValueWithRequireConstraintListArgsContext)_localctx).requireConstraint);
					}
					}
				}
				setState(729);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,36,_ctx);
			}
			setState(730);
			argsClosing();
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
		public ValueTokenContext classifier;
		public FilterConstraintContext ofParent;
		public FilterConstraintContext filterConstraint;
		public List<FilterConstraintContext> constrains = new ArrayList<FilterConstraintContext>();
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
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
		enterRule(_localctx, 78, RULE_hierarchyWithinConstraintArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(732);
			argsOpening();
			setState(733);
			((HierarchyWithinConstraintArgsContext)_localctx).classifier = valueToken();
			setState(734);
			match(ARGS_DELIMITER);
			setState(735);
			((HierarchyWithinConstraintArgsContext)_localctx).ofParent = filterConstraint();
			setState(740);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,37,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(736);
					match(ARGS_DELIMITER);
					setState(737);
					((HierarchyWithinConstraintArgsContext)_localctx).filterConstraint = filterConstraint();
					((HierarchyWithinConstraintArgsContext)_localctx).constrains.add(((HierarchyWithinConstraintArgsContext)_localctx).filterConstraint);
					}
					}
				}
				setState(742);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,37,_ctx);
			}
			setState(743);
			argsClosing();
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
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
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
		enterRule(_localctx, 80, RULE_hierarchyWithinSelfConstraintArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(745);
			argsOpening();
			setState(746);
			((HierarchyWithinSelfConstraintArgsContext)_localctx).ofParent = filterConstraint();
			setState(751);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,38,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(747);
					match(ARGS_DELIMITER);
					setState(748);
					((HierarchyWithinSelfConstraintArgsContext)_localctx).filterConstraint = filterConstraint();
					((HierarchyWithinSelfConstraintArgsContext)_localctx).constrains.add(((HierarchyWithinSelfConstraintArgsContext)_localctx).filterConstraint);
					}
					}
				}
				setState(753);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,38,_ctx);
			}
			setState(754);
			argsClosing();
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
		public ValueTokenContext classifier;
		public FilterConstraintContext filterConstraint;
		public List<FilterConstraintContext> constrains = new ArrayList<FilterConstraintContext>();
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
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
		enterRule(_localctx, 82, RULE_hierarchyWithinRootConstraintArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(756);
			argsOpening();
			setState(766);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,40,_ctx) ) {
			case 1:
				{
				setState(757);
				((HierarchyWithinRootConstraintArgsContext)_localctx).classifier = valueToken();
				}
				break;
			case 2:
				{
				{
				setState(758);
				((HierarchyWithinRootConstraintArgsContext)_localctx).classifier = valueToken();
				setState(763);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,39,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(759);
						match(ARGS_DELIMITER);
						setState(760);
						((HierarchyWithinRootConstraintArgsContext)_localctx).filterConstraint = filterConstraint();
						((HierarchyWithinRootConstraintArgsContext)_localctx).constrains.add(((HierarchyWithinRootConstraintArgsContext)_localctx).filterConstraint);
						}
						}
					}
					setState(765);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,39,_ctx);
				}
				}
				}
				break;
			}
			setState(768);
			argsClosing();
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
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
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
		enterRule(_localctx, 84, RULE_hierarchyWithinRootSelfConstraintArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(770);
			argsOpening();
			setState(771);
			((HierarchyWithinRootSelfConstraintArgsContext)_localctx).filterConstraint = filterConstraint();
			((HierarchyWithinRootSelfConstraintArgsContext)_localctx).constrains.add(((HierarchyWithinRootSelfConstraintArgsContext)_localctx).filterConstraint);
			setState(776);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,41,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(772);
					match(ARGS_DELIMITER);
					setState(773);
					((HierarchyWithinRootSelfConstraintArgsContext)_localctx).filterConstraint = filterConstraint();
					((HierarchyWithinRootSelfConstraintArgsContext)_localctx).constrains.add(((HierarchyWithinRootSelfConstraintArgsContext)_localctx).filterConstraint);
					}
					}
				}
				setState(778);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,41,_ctx);
			}
			setState(779);
			argsClosing();
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

	public static class AttributeSetExactArgsContext extends ParserRuleContext {
		public ValueTokenContext attributeName;
		public VariadicValueTokensContext attributeValues;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
		}
		public VariadicValueTokensContext variadicValueTokens() {
			return getRuleContext(VariadicValueTokensContext.class,0);
		}
		public AttributeSetExactArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_attributeSetExactArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAttributeSetExactArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAttributeSetExactArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAttributeSetExactArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final AttributeSetExactArgsContext attributeSetExactArgs() throws RecognitionException {
		AttributeSetExactArgsContext _localctx = new AttributeSetExactArgsContext(_ctx, getState());
		enterRule(_localctx, 86, RULE_attributeSetExactArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(781);
			argsOpening();
			setState(782);
			((AttributeSetExactArgsContext)_localctx).attributeName = valueToken();
			setState(783);
			match(ARGS_DELIMITER);
			setState(784);
			((AttributeSetExactArgsContext)_localctx).attributeValues = variadicValueTokens();
			setState(785);
			argsClosing();
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
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
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
		enterRule(_localctx, 88, RULE_pageConstraintArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(787);
			argsOpening();
			setState(788);
			((PageConstraintArgsContext)_localctx).pageNumber = valueToken();
			setState(789);
			match(ARGS_DELIMITER);
			setState(790);
			((PageConstraintArgsContext)_localctx).pageSize = valueToken();
			setState(791);
			argsClosing();
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
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
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
		enterRule(_localctx, 90, RULE_stripConstraintArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(793);
			argsOpening();
			setState(794);
			((StripConstraintArgsContext)_localctx).offset = valueToken();
			setState(795);
			match(ARGS_DELIMITER);
			setState(796);
			((StripConstraintArgsContext)_localctx).limit = valueToken();
			setState(797);
			argsClosing();
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

	public static class PriceContentArgsContext extends ParserRuleContext {
		public ValueTokenContext contentMode;
		public VariadicValueTokensContext priceLists;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
		}
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public VariadicValueTokensContext variadicValueTokens() {
			return getRuleContext(VariadicValueTokensContext.class,0);
		}
		public PriceContentArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_priceContentArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterPriceContentArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitPriceContentArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitPriceContentArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final PriceContentArgsContext priceContentArgs() throws RecognitionException {
		PriceContentArgsContext _localctx = new PriceContentArgsContext(_ctx, getState());
		enterRule(_localctx, 92, RULE_priceContentArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(799);
			argsOpening();
			setState(800);
			((PriceContentArgsContext)_localctx).contentMode = valueToken();
			setState(803);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,42,_ctx) ) {
			case 1:
				{
				setState(801);
				match(ARGS_DELIMITER);
				setState(802);
				((PriceContentArgsContext)_localctx).priceLists = variadicValueTokens();
				}
				break;
			}
			setState(805);
			argsClosing();
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

	public static class SingleRefReferenceContent1ArgsContext extends ParserRuleContext {
		public ValueTokenContext managedReferencesBehaviour;
		public ValueTokenContext classifier;
		public RequireConstraintContext requirement;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
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
		public RequireConstraintContext requireConstraint() {
			return getRuleContext(RequireConstraintContext.class,0);
		}
		public SingleRefReferenceContent1ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_singleRefReferenceContent1Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContent1Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContent1Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContent1Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SingleRefReferenceContent1ArgsContext singleRefReferenceContent1Args() throws RecognitionException {
		SingleRefReferenceContent1ArgsContext _localctx = new SingleRefReferenceContent1ArgsContext(_ctx, getState());
		enterRule(_localctx, 94, RULE_singleRefReferenceContent1Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(807);
			argsOpening();
			setState(811);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,43,_ctx) ) {
			case 1:
				{
				setState(808);
				((SingleRefReferenceContent1ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(809);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(813);
			((SingleRefReferenceContent1ArgsContext)_localctx).classifier = valueToken();
			setState(816);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,44,_ctx) ) {
			case 1:
				{
				setState(814);
				match(ARGS_DELIMITER);
				setState(815);
				((SingleRefReferenceContent1ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(818);
			argsClosing();
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

	public static class SingleRefReferenceContent2ArgsContext extends ParserRuleContext {
		public ValueTokenContext managedReferencesBehaviour;
		public ValueTokenContext classifier;
		public RequireConstraintContext facetEntityRequirement;
		public RequireConstraintContext groupEntityRequirement;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public List<ValueTokenContext> valueToken() {
			return getRuleContexts(ValueTokenContext.class);
		}
		public ValueTokenContext valueToken(int i) {
			return getRuleContext(ValueTokenContext.class,i);
		}
		public List<RequireConstraintContext> requireConstraint() {
			return getRuleContexts(RequireConstraintContext.class);
		}
		public RequireConstraintContext requireConstraint(int i) {
			return getRuleContext(RequireConstraintContext.class,i);
		}
		public SingleRefReferenceContent2ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_singleRefReferenceContent2Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContent2Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContent2Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContent2Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SingleRefReferenceContent2ArgsContext singleRefReferenceContent2Args() throws RecognitionException {
		SingleRefReferenceContent2ArgsContext _localctx = new SingleRefReferenceContent2ArgsContext(_ctx, getState());
		enterRule(_localctx, 96, RULE_singleRefReferenceContent2Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(820);
			argsOpening();
			setState(824);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,45,_ctx) ) {
			case 1:
				{
				setState(821);
				((SingleRefReferenceContent2ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(822);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(826);
			((SingleRefReferenceContent2ArgsContext)_localctx).classifier = valueToken();
			setState(827);
			match(ARGS_DELIMITER);
			setState(828);
			((SingleRefReferenceContent2ArgsContext)_localctx).facetEntityRequirement = requireConstraint();
			setState(829);
			match(ARGS_DELIMITER);
			setState(830);
			((SingleRefReferenceContent2ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(831);
			argsClosing();
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

	public static class SingleRefReferenceContent3ArgsContext extends ParserRuleContext {
		public ValueTokenContext managedReferencesBehaviour;
		public ValueTokenContext classifier;
		public FilterConstraintContext filterBy;
		public RequireConstraintContext requirement;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public List<ValueTokenContext> valueToken() {
			return getRuleContexts(ValueTokenContext.class);
		}
		public ValueTokenContext valueToken(int i) {
			return getRuleContext(ValueTokenContext.class,i);
		}
		public FilterConstraintContext filterConstraint() {
			return getRuleContext(FilterConstraintContext.class,0);
		}
		public RequireConstraintContext requireConstraint() {
			return getRuleContext(RequireConstraintContext.class,0);
		}
		public SingleRefReferenceContent3ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_singleRefReferenceContent3Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContent3Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContent3Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContent3Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SingleRefReferenceContent3ArgsContext singleRefReferenceContent3Args() throws RecognitionException {
		SingleRefReferenceContent3ArgsContext _localctx = new SingleRefReferenceContent3ArgsContext(_ctx, getState());
		enterRule(_localctx, 98, RULE_singleRefReferenceContent3Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(833);
			argsOpening();
			setState(837);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,46,_ctx) ) {
			case 1:
				{
				setState(834);
				((SingleRefReferenceContent3ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(835);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(839);
			((SingleRefReferenceContent3ArgsContext)_localctx).classifier = valueToken();
			setState(840);
			match(ARGS_DELIMITER);
			setState(841);
			((SingleRefReferenceContent3ArgsContext)_localctx).filterBy = filterConstraint();
			setState(844);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,47,_ctx) ) {
			case 1:
				{
				setState(842);
				match(ARGS_DELIMITER);
				setState(843);
				((SingleRefReferenceContent3ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(846);
			argsClosing();
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

	public static class SingleRefReferenceContent4ArgsContext extends ParserRuleContext {
		public ValueTokenContext managedReferencesBehaviour;
		public ValueTokenContext classifier;
		public FilterConstraintContext filterBy;
		public RequireConstraintContext facetEntityRequirement;
		public RequireConstraintContext groupEntityRequirement;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public List<ValueTokenContext> valueToken() {
			return getRuleContexts(ValueTokenContext.class);
		}
		public ValueTokenContext valueToken(int i) {
			return getRuleContext(ValueTokenContext.class,i);
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
		public SingleRefReferenceContent4ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_singleRefReferenceContent4Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContent4Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContent4Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContent4Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SingleRefReferenceContent4ArgsContext singleRefReferenceContent4Args() throws RecognitionException {
		SingleRefReferenceContent4ArgsContext _localctx = new SingleRefReferenceContent4ArgsContext(_ctx, getState());
		enterRule(_localctx, 100, RULE_singleRefReferenceContent4Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(848);
			argsOpening();
			setState(852);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,48,_ctx) ) {
			case 1:
				{
				setState(849);
				((SingleRefReferenceContent4ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(850);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(854);
			((SingleRefReferenceContent4ArgsContext)_localctx).classifier = valueToken();
			setState(855);
			match(ARGS_DELIMITER);
			setState(856);
			((SingleRefReferenceContent4ArgsContext)_localctx).filterBy = filterConstraint();
			setState(857);
			match(ARGS_DELIMITER);
			setState(858);
			((SingleRefReferenceContent4ArgsContext)_localctx).facetEntityRequirement = requireConstraint();
			setState(859);
			match(ARGS_DELIMITER);
			setState(860);
			((SingleRefReferenceContent4ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(861);
			argsClosing();
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

	public static class SingleRefReferenceContent5ArgsContext extends ParserRuleContext {
		public ValueTokenContext managedReferencesBehaviour;
		public ValueTokenContext classifier;
		public OrderConstraintContext orderBy;
		public RequireConstraintContext requirement;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public List<ValueTokenContext> valueToken() {
			return getRuleContexts(ValueTokenContext.class);
		}
		public ValueTokenContext valueToken(int i) {
			return getRuleContext(ValueTokenContext.class,i);
		}
		public OrderConstraintContext orderConstraint() {
			return getRuleContext(OrderConstraintContext.class,0);
		}
		public RequireConstraintContext requireConstraint() {
			return getRuleContext(RequireConstraintContext.class,0);
		}
		public SingleRefReferenceContent5ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_singleRefReferenceContent5Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContent5Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContent5Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContent5Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SingleRefReferenceContent5ArgsContext singleRefReferenceContent5Args() throws RecognitionException {
		SingleRefReferenceContent5ArgsContext _localctx = new SingleRefReferenceContent5ArgsContext(_ctx, getState());
		enterRule(_localctx, 102, RULE_singleRefReferenceContent5Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(863);
			argsOpening();
			setState(867);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,49,_ctx) ) {
			case 1:
				{
				setState(864);
				((SingleRefReferenceContent5ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(865);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(869);
			((SingleRefReferenceContent5ArgsContext)_localctx).classifier = valueToken();
			setState(870);
			match(ARGS_DELIMITER);
			setState(871);
			((SingleRefReferenceContent5ArgsContext)_localctx).orderBy = orderConstraint();
			setState(874);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,50,_ctx) ) {
			case 1:
				{
				setState(872);
				match(ARGS_DELIMITER);
				setState(873);
				((SingleRefReferenceContent5ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(876);
			argsClosing();
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

	public static class SingleRefReferenceContent6ArgsContext extends ParserRuleContext {
		public ValueTokenContext managedReferencesBehaviour;
		public ValueTokenContext classifier;
		public OrderConstraintContext orderBy;
		public RequireConstraintContext facetEntityRequirement;
		public RequireConstraintContext groupEntityRequirement;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public List<ValueTokenContext> valueToken() {
			return getRuleContexts(ValueTokenContext.class);
		}
		public ValueTokenContext valueToken(int i) {
			return getRuleContext(ValueTokenContext.class,i);
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
		public SingleRefReferenceContent6ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_singleRefReferenceContent6Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContent6Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContent6Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContent6Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SingleRefReferenceContent6ArgsContext singleRefReferenceContent6Args() throws RecognitionException {
		SingleRefReferenceContent6ArgsContext _localctx = new SingleRefReferenceContent6ArgsContext(_ctx, getState());
		enterRule(_localctx, 104, RULE_singleRefReferenceContent6Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(878);
			argsOpening();
			setState(882);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,51,_ctx) ) {
			case 1:
				{
				setState(879);
				((SingleRefReferenceContent6ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(880);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(884);
			((SingleRefReferenceContent6ArgsContext)_localctx).classifier = valueToken();
			setState(885);
			match(ARGS_DELIMITER);
			setState(886);
			((SingleRefReferenceContent6ArgsContext)_localctx).orderBy = orderConstraint();
			setState(887);
			match(ARGS_DELIMITER);
			setState(888);
			((SingleRefReferenceContent6ArgsContext)_localctx).facetEntityRequirement = requireConstraint();
			setState(889);
			match(ARGS_DELIMITER);
			setState(890);
			((SingleRefReferenceContent6ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(891);
			argsClosing();
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

	public static class SingleRefReferenceContent7ArgsContext extends ParserRuleContext {
		public ValueTokenContext managedReferencesBehaviour;
		public ValueTokenContext classifier;
		public FilterConstraintContext filterBy;
		public OrderConstraintContext orderBy;
		public RequireConstraintContext requirement;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public List<ValueTokenContext> valueToken() {
			return getRuleContexts(ValueTokenContext.class);
		}
		public ValueTokenContext valueToken(int i) {
			return getRuleContext(ValueTokenContext.class,i);
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
		public SingleRefReferenceContent7ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_singleRefReferenceContent7Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContent7Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContent7Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContent7Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SingleRefReferenceContent7ArgsContext singleRefReferenceContent7Args() throws RecognitionException {
		SingleRefReferenceContent7ArgsContext _localctx = new SingleRefReferenceContent7ArgsContext(_ctx, getState());
		enterRule(_localctx, 106, RULE_singleRefReferenceContent7Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(893);
			argsOpening();
			setState(897);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,52,_ctx) ) {
			case 1:
				{
				setState(894);
				((SingleRefReferenceContent7ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(895);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(899);
			((SingleRefReferenceContent7ArgsContext)_localctx).classifier = valueToken();
			setState(900);
			match(ARGS_DELIMITER);
			setState(901);
			((SingleRefReferenceContent7ArgsContext)_localctx).filterBy = filterConstraint();
			setState(902);
			match(ARGS_DELIMITER);
			setState(903);
			((SingleRefReferenceContent7ArgsContext)_localctx).orderBy = orderConstraint();
			setState(906);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,53,_ctx) ) {
			case 1:
				{
				setState(904);
				match(ARGS_DELIMITER);
				setState(905);
				((SingleRefReferenceContent7ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(908);
			argsClosing();
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

	public static class SingleRefReferenceContent8ArgsContext extends ParserRuleContext {
		public ValueTokenContext managedReferencesBehaviour;
		public ValueTokenContext classifier;
		public FilterConstraintContext filterBy;
		public OrderConstraintContext orderBy;
		public RequireConstraintContext facetEntityRequirement;
		public RequireConstraintContext groupEntityRequirement;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public List<ValueTokenContext> valueToken() {
			return getRuleContexts(ValueTokenContext.class);
		}
		public ValueTokenContext valueToken(int i) {
			return getRuleContext(ValueTokenContext.class,i);
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
		public SingleRefReferenceContent8ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_singleRefReferenceContent8Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContent8Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContent8Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContent8Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SingleRefReferenceContent8ArgsContext singleRefReferenceContent8Args() throws RecognitionException {
		SingleRefReferenceContent8ArgsContext _localctx = new SingleRefReferenceContent8ArgsContext(_ctx, getState());
		enterRule(_localctx, 108, RULE_singleRefReferenceContent8Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(910);
			argsOpening();
			setState(914);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,54,_ctx) ) {
			case 1:
				{
				setState(911);
				((SingleRefReferenceContent8ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(912);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(916);
			((SingleRefReferenceContent8ArgsContext)_localctx).classifier = valueToken();
			setState(917);
			match(ARGS_DELIMITER);
			setState(918);
			((SingleRefReferenceContent8ArgsContext)_localctx).filterBy = filterConstraint();
			setState(919);
			match(ARGS_DELIMITER);
			setState(920);
			((SingleRefReferenceContent8ArgsContext)_localctx).orderBy = orderConstraint();
			setState(921);
			match(ARGS_DELIMITER);
			setState(922);
			((SingleRefReferenceContent8ArgsContext)_localctx).facetEntityRequirement = requireConstraint();
			setState(923);
			match(ARGS_DELIMITER);
			setState(924);
			((SingleRefReferenceContent8ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(925);
			argsClosing();
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

	public static class SingleRefReferenceContentWithAttributes1ArgsContext extends ParserRuleContext {
		public ValueTokenContext managedReferencesBehaviour;
		public ValueTokenContext classifier;
		public RequireConstraintContext requirement1;
		public RequireConstraintContext requirement2;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public List<ValueTokenContext> valueToken() {
			return getRuleContexts(ValueTokenContext.class);
		}
		public ValueTokenContext valueToken(int i) {
			return getRuleContext(ValueTokenContext.class,i);
		}
		public List<RequireConstraintContext> requireConstraint() {
			return getRuleContexts(RequireConstraintContext.class);
		}
		public RequireConstraintContext requireConstraint(int i) {
			return getRuleContext(RequireConstraintContext.class,i);
		}
		public SingleRefReferenceContentWithAttributes1ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_singleRefReferenceContentWithAttributes1Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContentWithAttributes1Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContentWithAttributes1Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContentWithAttributes1Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SingleRefReferenceContentWithAttributes1ArgsContext singleRefReferenceContentWithAttributes1Args() throws RecognitionException {
		SingleRefReferenceContentWithAttributes1ArgsContext _localctx = new SingleRefReferenceContentWithAttributes1ArgsContext(_ctx, getState());
		enterRule(_localctx, 110, RULE_singleRefReferenceContentWithAttributes1Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(927);
			argsOpening();
			setState(931);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,55,_ctx) ) {
			case 1:
				{
				setState(928);
				((SingleRefReferenceContentWithAttributes1ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(929);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(933);
			((SingleRefReferenceContentWithAttributes1ArgsContext)_localctx).classifier = valueToken();
			setState(934);
			match(ARGS_DELIMITER);
			setState(935);
			((SingleRefReferenceContentWithAttributes1ArgsContext)_localctx).requirement1 = requireConstraint();
			setState(936);
			match(ARGS_DELIMITER);
			setState(937);
			((SingleRefReferenceContentWithAttributes1ArgsContext)_localctx).requirement2 = requireConstraint();
			setState(938);
			argsClosing();
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

	public static class SingleRefReferenceContentWithAttributes2ArgsContext extends ParserRuleContext {
		public ValueTokenContext managedReferencesBehaviour;
		public ValueTokenContext classifier;
		public RequireConstraintContext attributeContent;
		public RequireConstraintContext facetEntityRequirement;
		public RequireConstraintContext groupEntityRequirement;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public List<ValueTokenContext> valueToken() {
			return getRuleContexts(ValueTokenContext.class);
		}
		public ValueTokenContext valueToken(int i) {
			return getRuleContext(ValueTokenContext.class,i);
		}
		public List<RequireConstraintContext> requireConstraint() {
			return getRuleContexts(RequireConstraintContext.class);
		}
		public RequireConstraintContext requireConstraint(int i) {
			return getRuleContext(RequireConstraintContext.class,i);
		}
		public SingleRefReferenceContentWithAttributes2ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_singleRefReferenceContentWithAttributes2Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContentWithAttributes2Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContentWithAttributes2Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContentWithAttributes2Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SingleRefReferenceContentWithAttributes2ArgsContext singleRefReferenceContentWithAttributes2Args() throws RecognitionException {
		SingleRefReferenceContentWithAttributes2ArgsContext _localctx = new SingleRefReferenceContentWithAttributes2ArgsContext(_ctx, getState());
		enterRule(_localctx, 112, RULE_singleRefReferenceContentWithAttributes2Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(940);
			argsOpening();
			setState(944);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,56,_ctx) ) {
			case 1:
				{
				setState(941);
				((SingleRefReferenceContentWithAttributes2ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(942);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(946);
			((SingleRefReferenceContentWithAttributes2ArgsContext)_localctx).classifier = valueToken();
			setState(947);
			match(ARGS_DELIMITER);
			setState(948);
			((SingleRefReferenceContentWithAttributes2ArgsContext)_localctx).attributeContent = requireConstraint();
			setState(949);
			match(ARGS_DELIMITER);
			setState(950);
			((SingleRefReferenceContentWithAttributes2ArgsContext)_localctx).facetEntityRequirement = requireConstraint();
			setState(951);
			match(ARGS_DELIMITER);
			setState(952);
			((SingleRefReferenceContentWithAttributes2ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(953);
			argsClosing();
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

	public static class SingleRefReferenceContentWithAttributes3ArgsContext extends ParserRuleContext {
		public ValueTokenContext managedReferencesBehaviour;
		public ValueTokenContext classifier;
		public FilterConstraintContext filterBy;
		public RequireConstraintContext requirement1;
		public RequireConstraintContext requirement2;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public List<ValueTokenContext> valueToken() {
			return getRuleContexts(ValueTokenContext.class);
		}
		public ValueTokenContext valueToken(int i) {
			return getRuleContext(ValueTokenContext.class,i);
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
		public SingleRefReferenceContentWithAttributes3ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_singleRefReferenceContentWithAttributes3Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContentWithAttributes3Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContentWithAttributes3Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContentWithAttributes3Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SingleRefReferenceContentWithAttributes3ArgsContext singleRefReferenceContentWithAttributes3Args() throws RecognitionException {
		SingleRefReferenceContentWithAttributes3ArgsContext _localctx = new SingleRefReferenceContentWithAttributes3ArgsContext(_ctx, getState());
		enterRule(_localctx, 114, RULE_singleRefReferenceContentWithAttributes3Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(955);
			argsOpening();
			setState(959);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,57,_ctx) ) {
			case 1:
				{
				setState(956);
				((SingleRefReferenceContentWithAttributes3ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(957);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(961);
			((SingleRefReferenceContentWithAttributes3ArgsContext)_localctx).classifier = valueToken();
			setState(962);
			match(ARGS_DELIMITER);
			setState(963);
			((SingleRefReferenceContentWithAttributes3ArgsContext)_localctx).filterBy = filterConstraint();
			setState(964);
			match(ARGS_DELIMITER);
			setState(965);
			((SingleRefReferenceContentWithAttributes3ArgsContext)_localctx).requirement1 = requireConstraint();
			setState(966);
			match(ARGS_DELIMITER);
			setState(967);
			((SingleRefReferenceContentWithAttributes3ArgsContext)_localctx).requirement2 = requireConstraint();
			setState(968);
			argsClosing();
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

	public static class SingleRefReferenceContentWithAttributes4ArgsContext extends ParserRuleContext {
		public ValueTokenContext managedReferencesBehaviour;
		public ValueTokenContext classifier;
		public FilterConstraintContext filterBy;
		public RequireConstraintContext attributeContent;
		public RequireConstraintContext facetEntityRequirement;
		public RequireConstraintContext groupEntityRequirement;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public List<ValueTokenContext> valueToken() {
			return getRuleContexts(ValueTokenContext.class);
		}
		public ValueTokenContext valueToken(int i) {
			return getRuleContext(ValueTokenContext.class,i);
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
		public SingleRefReferenceContentWithAttributes4ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_singleRefReferenceContentWithAttributes4Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContentWithAttributes4Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContentWithAttributes4Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContentWithAttributes4Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SingleRefReferenceContentWithAttributes4ArgsContext singleRefReferenceContentWithAttributes4Args() throws RecognitionException {
		SingleRefReferenceContentWithAttributes4ArgsContext _localctx = new SingleRefReferenceContentWithAttributes4ArgsContext(_ctx, getState());
		enterRule(_localctx, 116, RULE_singleRefReferenceContentWithAttributes4Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(970);
			argsOpening();
			setState(974);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,58,_ctx) ) {
			case 1:
				{
				setState(971);
				((SingleRefReferenceContentWithAttributes4ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(972);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(976);
			((SingleRefReferenceContentWithAttributes4ArgsContext)_localctx).classifier = valueToken();
			setState(977);
			match(ARGS_DELIMITER);
			setState(978);
			((SingleRefReferenceContentWithAttributes4ArgsContext)_localctx).filterBy = filterConstraint();
			setState(979);
			match(ARGS_DELIMITER);
			setState(980);
			((SingleRefReferenceContentWithAttributes4ArgsContext)_localctx).attributeContent = requireConstraint();
			setState(981);
			match(ARGS_DELIMITER);
			setState(982);
			((SingleRefReferenceContentWithAttributes4ArgsContext)_localctx).facetEntityRequirement = requireConstraint();
			setState(983);
			match(ARGS_DELIMITER);
			setState(984);
			((SingleRefReferenceContentWithAttributes4ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(985);
			argsClosing();
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

	public static class SingleRefReferenceContentWithAttributes5ArgsContext extends ParserRuleContext {
		public ValueTokenContext managedReferencesBehaviour;
		public ValueTokenContext classifier;
		public OrderConstraintContext orderBy;
		public RequireConstraintContext requirement1;
		public RequireConstraintContext requirement2;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public List<ValueTokenContext> valueToken() {
			return getRuleContexts(ValueTokenContext.class);
		}
		public ValueTokenContext valueToken(int i) {
			return getRuleContext(ValueTokenContext.class,i);
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
		public SingleRefReferenceContentWithAttributes5ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_singleRefReferenceContentWithAttributes5Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContentWithAttributes5Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContentWithAttributes5Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContentWithAttributes5Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SingleRefReferenceContentWithAttributes5ArgsContext singleRefReferenceContentWithAttributes5Args() throws RecognitionException {
		SingleRefReferenceContentWithAttributes5ArgsContext _localctx = new SingleRefReferenceContentWithAttributes5ArgsContext(_ctx, getState());
		enterRule(_localctx, 118, RULE_singleRefReferenceContentWithAttributes5Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(987);
			argsOpening();
			setState(991);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,59,_ctx) ) {
			case 1:
				{
				setState(988);
				((SingleRefReferenceContentWithAttributes5ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(989);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(993);
			((SingleRefReferenceContentWithAttributes5ArgsContext)_localctx).classifier = valueToken();
			setState(994);
			match(ARGS_DELIMITER);
			setState(995);
			((SingleRefReferenceContentWithAttributes5ArgsContext)_localctx).orderBy = orderConstraint();
			setState(996);
			match(ARGS_DELIMITER);
			setState(997);
			((SingleRefReferenceContentWithAttributes5ArgsContext)_localctx).requirement1 = requireConstraint();
			setState(998);
			match(ARGS_DELIMITER);
			setState(999);
			((SingleRefReferenceContentWithAttributes5ArgsContext)_localctx).requirement2 = requireConstraint();
			setState(1000);
			argsClosing();
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

	public static class SingleRefReferenceContentWithAttributes6ArgsContext extends ParserRuleContext {
		public ValueTokenContext managedReferencesBehaviour;
		public ValueTokenContext classifier;
		public OrderConstraintContext orderBy;
		public RequireConstraintContext attributeContent;
		public RequireConstraintContext facetEntityRequirement;
		public RequireConstraintContext groupEntityRequirement;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public List<ValueTokenContext> valueToken() {
			return getRuleContexts(ValueTokenContext.class);
		}
		public ValueTokenContext valueToken(int i) {
			return getRuleContext(ValueTokenContext.class,i);
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
		public SingleRefReferenceContentWithAttributes6ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_singleRefReferenceContentWithAttributes6Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContentWithAttributes6Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContentWithAttributes6Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContentWithAttributes6Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SingleRefReferenceContentWithAttributes6ArgsContext singleRefReferenceContentWithAttributes6Args() throws RecognitionException {
		SingleRefReferenceContentWithAttributes6ArgsContext _localctx = new SingleRefReferenceContentWithAttributes6ArgsContext(_ctx, getState());
		enterRule(_localctx, 120, RULE_singleRefReferenceContentWithAttributes6Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1002);
			argsOpening();
			setState(1006);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,60,_ctx) ) {
			case 1:
				{
				setState(1003);
				((SingleRefReferenceContentWithAttributes6ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1004);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1008);
			((SingleRefReferenceContentWithAttributes6ArgsContext)_localctx).classifier = valueToken();
			setState(1009);
			match(ARGS_DELIMITER);
			setState(1010);
			((SingleRefReferenceContentWithAttributes6ArgsContext)_localctx).orderBy = orderConstraint();
			setState(1011);
			match(ARGS_DELIMITER);
			setState(1012);
			((SingleRefReferenceContentWithAttributes6ArgsContext)_localctx).attributeContent = requireConstraint();
			setState(1013);
			match(ARGS_DELIMITER);
			setState(1014);
			((SingleRefReferenceContentWithAttributes6ArgsContext)_localctx).facetEntityRequirement = requireConstraint();
			setState(1015);
			match(ARGS_DELIMITER);
			setState(1016);
			((SingleRefReferenceContentWithAttributes6ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(1017);
			argsClosing();
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

	public static class SingleRefReferenceContentWithAttributes7ArgsContext extends ParserRuleContext {
		public ValueTokenContext managedReferencesBehaviour;
		public ValueTokenContext classifier;
		public FilterConstraintContext filterBy;
		public OrderConstraintContext orderBy;
		public RequireConstraintContext requirement1;
		public RequireConstraintContext requirement2;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public List<ValueTokenContext> valueToken() {
			return getRuleContexts(ValueTokenContext.class);
		}
		public ValueTokenContext valueToken(int i) {
			return getRuleContext(ValueTokenContext.class,i);
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
		public SingleRefReferenceContentWithAttributes7ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_singleRefReferenceContentWithAttributes7Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContentWithAttributes7Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContentWithAttributes7Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContentWithAttributes7Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SingleRefReferenceContentWithAttributes7ArgsContext singleRefReferenceContentWithAttributes7Args() throws RecognitionException {
		SingleRefReferenceContentWithAttributes7ArgsContext _localctx = new SingleRefReferenceContentWithAttributes7ArgsContext(_ctx, getState());
		enterRule(_localctx, 122, RULE_singleRefReferenceContentWithAttributes7Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1019);
			argsOpening();
			setState(1023);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,61,_ctx) ) {
			case 1:
				{
				setState(1020);
				((SingleRefReferenceContentWithAttributes7ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1021);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1025);
			((SingleRefReferenceContentWithAttributes7ArgsContext)_localctx).classifier = valueToken();
			setState(1026);
			match(ARGS_DELIMITER);
			setState(1027);
			((SingleRefReferenceContentWithAttributes7ArgsContext)_localctx).filterBy = filterConstraint();
			setState(1028);
			match(ARGS_DELIMITER);
			setState(1029);
			((SingleRefReferenceContentWithAttributes7ArgsContext)_localctx).orderBy = orderConstraint();
			setState(1030);
			match(ARGS_DELIMITER);
			setState(1031);
			((SingleRefReferenceContentWithAttributes7ArgsContext)_localctx).requirement1 = requireConstraint();
			setState(1032);
			match(ARGS_DELIMITER);
			setState(1033);
			((SingleRefReferenceContentWithAttributes7ArgsContext)_localctx).requirement2 = requireConstraint();
			setState(1034);
			argsClosing();
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

	public static class SingleRefReferenceContentWithAttributes8ArgsContext extends ParserRuleContext {
		public ValueTokenContext managedReferencesBehaviour;
		public ValueTokenContext classifier;
		public FilterConstraintContext filterBy;
		public OrderConstraintContext orderBy;
		public RequireConstraintContext attributeContent;
		public RequireConstraintContext facetEntityRequirement;
		public RequireConstraintContext groupEntityRequirement;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public List<ValueTokenContext> valueToken() {
			return getRuleContexts(ValueTokenContext.class);
		}
		public ValueTokenContext valueToken(int i) {
			return getRuleContext(ValueTokenContext.class,i);
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
		public SingleRefReferenceContentWithAttributes8ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_singleRefReferenceContentWithAttributes8Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContentWithAttributes8Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContentWithAttributes8Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContentWithAttributes8Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SingleRefReferenceContentWithAttributes8ArgsContext singleRefReferenceContentWithAttributes8Args() throws RecognitionException {
		SingleRefReferenceContentWithAttributes8ArgsContext _localctx = new SingleRefReferenceContentWithAttributes8ArgsContext(_ctx, getState());
		enterRule(_localctx, 124, RULE_singleRefReferenceContentWithAttributes8Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1036);
			argsOpening();
			setState(1040);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,62,_ctx) ) {
			case 1:
				{
				setState(1037);
				((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1038);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1042);
			((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).classifier = valueToken();
			setState(1043);
			match(ARGS_DELIMITER);
			setState(1044);
			((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).filterBy = filterConstraint();
			setState(1045);
			match(ARGS_DELIMITER);
			setState(1046);
			((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).orderBy = orderConstraint();
			setState(1047);
			match(ARGS_DELIMITER);
			setState(1048);
			((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).attributeContent = requireConstraint();
			setState(1049);
			match(ARGS_DELIMITER);
			setState(1050);
			((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).facetEntityRequirement = requireConstraint();
			setState(1051);
			match(ARGS_DELIMITER);
			setState(1052);
			((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(1053);
			argsClosing();
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
		public ValueTokenContext managedReferencesBehaviour;
		public VariadicValueTokensContext classifiers;
		public RequireConstraintContext requirement;
		public RequireConstraintContext facetEntityRequirement;
		public RequireConstraintContext groupEntityRequirement;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public VariadicValueTokensContext variadicValueTokens() {
			return getRuleContext(VariadicValueTokensContext.class,0);
		}
		public List<RequireConstraintContext> requireConstraint() {
			return getRuleContexts(RequireConstraintContext.class);
		}
		public RequireConstraintContext requireConstraint(int i) {
			return getRuleContext(RequireConstraintContext.class,i);
		}
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
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
		enterRule(_localctx, 126, RULE_multipleRefsReferenceContentArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1055);
			argsOpening();
			setState(1077);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,66,_ctx) ) {
			case 1:
				{
				{
				setState(1059);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,63,_ctx) ) {
				case 1:
					{
					setState(1056);
					((MultipleRefsReferenceContentArgsContext)_localctx).managedReferencesBehaviour = valueToken();
					setState(1057);
					match(ARGS_DELIMITER);
					}
					break;
				}
				setState(1061);
				((MultipleRefsReferenceContentArgsContext)_localctx).classifiers = variadicValueTokens();
				setState(1064);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,64,_ctx) ) {
				case 1:
					{
					setState(1062);
					match(ARGS_DELIMITER);
					setState(1063);
					((MultipleRefsReferenceContentArgsContext)_localctx).requirement = requireConstraint();
					}
					break;
				}
				}
				}
				break;
			case 2:
				{
				{
				setState(1069);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,65,_ctx) ) {
				case 1:
					{
					setState(1066);
					((MultipleRefsReferenceContentArgsContext)_localctx).managedReferencesBehaviour = valueToken();
					setState(1067);
					match(ARGS_DELIMITER);
					}
					break;
				}
				setState(1071);
				((MultipleRefsReferenceContentArgsContext)_localctx).classifiers = variadicValueTokens();
				setState(1072);
				match(ARGS_DELIMITER);
				setState(1073);
				((MultipleRefsReferenceContentArgsContext)_localctx).facetEntityRequirement = requireConstraint();
				setState(1074);
				match(ARGS_DELIMITER);
				setState(1075);
				((MultipleRefsReferenceContentArgsContext)_localctx).groupEntityRequirement = requireConstraint();
				}
				}
				break;
			}
			setState(1079);
			argsClosing();
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
		public ValueTokenContext managedReferencesBehaviour;
		public RequireConstraintContext requirement;
		public RequireConstraintContext facetEntityRequirement;
		public RequireConstraintContext groupEntityRequirement;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
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
		enterRule(_localctx, 128, RULE_allRefsReferenceContentArgs);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1081);
			argsOpening();
			setState(1098);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,69,_ctx) ) {
			case 1:
				{
				{
				setState(1082);
				((AllRefsReferenceContentArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				}
				}
				break;
			case 2:
				{
				{
				setState(1086);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (((((_la - 98)) & ~0x3f) == 0 && ((1L << (_la - 98)) & ((1L << (POSITIONAL_PARAMETER - 98)) | (1L << (NAMED_PARAMETER - 98)) | (1L << (STRING - 98)) | (1L << (INT - 98)) | (1L << (FLOAT - 98)) | (1L << (BOOLEAN - 98)) | (1L << (DATE - 98)) | (1L << (TIME - 98)) | (1L << (DATE_TIME - 98)) | (1L << (OFFSET_DATE_TIME - 98)) | (1L << (FLOAT_NUMBER_RANGE - 98)) | (1L << (INT_NUMBER_RANGE - 98)) | (1L << (DATE_TIME_RANGE - 98)) | (1L << (UUID - 98)) | (1L << (ENUM - 98)))) != 0)) {
					{
					setState(1083);
					((AllRefsReferenceContentArgsContext)_localctx).managedReferencesBehaviour = valueToken();
					setState(1084);
					match(ARGS_DELIMITER);
					}
				}

				setState(1088);
				((AllRefsReferenceContentArgsContext)_localctx).requirement = requireConstraint();
				}
				}
				break;
			case 3:
				{
				setState(1092);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (((((_la - 98)) & ~0x3f) == 0 && ((1L << (_la - 98)) & ((1L << (POSITIONAL_PARAMETER - 98)) | (1L << (NAMED_PARAMETER - 98)) | (1L << (STRING - 98)) | (1L << (INT - 98)) | (1L << (FLOAT - 98)) | (1L << (BOOLEAN - 98)) | (1L << (DATE - 98)) | (1L << (TIME - 98)) | (1L << (DATE_TIME - 98)) | (1L << (OFFSET_DATE_TIME - 98)) | (1L << (FLOAT_NUMBER_RANGE - 98)) | (1L << (INT_NUMBER_RANGE - 98)) | (1L << (DATE_TIME_RANGE - 98)) | (1L << (UUID - 98)) | (1L << (ENUM - 98)))) != 0)) {
					{
					setState(1089);
					((AllRefsReferenceContentArgsContext)_localctx).managedReferencesBehaviour = valueToken();
					setState(1090);
					match(ARGS_DELIMITER);
					}
				}

				{
				setState(1094);
				((AllRefsReferenceContentArgsContext)_localctx).facetEntityRequirement = requireConstraint();
				setState(1095);
				match(ARGS_DELIMITER);
				setState(1096);
				((AllRefsReferenceContentArgsContext)_localctx).groupEntityRequirement = requireConstraint();
				}
				}
				break;
			}
			setState(1100);
			argsClosing();
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

	public static class AllRefsWithAttributesReferenceContent1ArgsContext extends ParserRuleContext {
		public ValueTokenContext managedReferencesBehaviour;
		public RequireConstraintContext requirement;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public RequireConstraintContext requireConstraint() {
			return getRuleContext(RequireConstraintContext.class,0);
		}
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
		}
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public AllRefsWithAttributesReferenceContent1ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_allRefsWithAttributesReferenceContent1Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAllRefsWithAttributesReferenceContent1Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAllRefsWithAttributesReferenceContent1Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAllRefsWithAttributesReferenceContent1Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final AllRefsWithAttributesReferenceContent1ArgsContext allRefsWithAttributesReferenceContent1Args() throws RecognitionException {
		AllRefsWithAttributesReferenceContent1ArgsContext _localctx = new AllRefsWithAttributesReferenceContent1ArgsContext(_ctx, getState());
		enterRule(_localctx, 130, RULE_allRefsWithAttributesReferenceContent1Args);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1102);
			argsOpening();
			setState(1110);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,71,_ctx) ) {
			case 1:
				{
				{
				setState(1103);
				((AllRefsWithAttributesReferenceContent1ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				}
				}
				break;
			case 2:
				{
				setState(1107);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (((((_la - 98)) & ~0x3f) == 0 && ((1L << (_la - 98)) & ((1L << (POSITIONAL_PARAMETER - 98)) | (1L << (NAMED_PARAMETER - 98)) | (1L << (STRING - 98)) | (1L << (INT - 98)) | (1L << (FLOAT - 98)) | (1L << (BOOLEAN - 98)) | (1L << (DATE - 98)) | (1L << (TIME - 98)) | (1L << (DATE_TIME - 98)) | (1L << (OFFSET_DATE_TIME - 98)) | (1L << (FLOAT_NUMBER_RANGE - 98)) | (1L << (INT_NUMBER_RANGE - 98)) | (1L << (DATE_TIME_RANGE - 98)) | (1L << (UUID - 98)) | (1L << (ENUM - 98)))) != 0)) {
					{
					setState(1104);
					((AllRefsWithAttributesReferenceContent1ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
					setState(1105);
					match(ARGS_DELIMITER);
					}
				}

				setState(1109);
				((AllRefsWithAttributesReferenceContent1ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1112);
			argsClosing();
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

	public static class AllRefsWithAttributesReferenceContent2ArgsContext extends ParserRuleContext {
		public ValueTokenContext managedReferencesBehaviour;
		public RequireConstraintContext requirement1;
		public RequireConstraintContext requirement2;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public List<RequireConstraintContext> requireConstraint() {
			return getRuleContexts(RequireConstraintContext.class);
		}
		public RequireConstraintContext requireConstraint(int i) {
			return getRuleContext(RequireConstraintContext.class,i);
		}
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
		}
		public AllRefsWithAttributesReferenceContent2ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_allRefsWithAttributesReferenceContent2Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAllRefsWithAttributesReferenceContent2Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAllRefsWithAttributesReferenceContent2Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAllRefsWithAttributesReferenceContent2Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final AllRefsWithAttributesReferenceContent2ArgsContext allRefsWithAttributesReferenceContent2Args() throws RecognitionException {
		AllRefsWithAttributesReferenceContent2ArgsContext _localctx = new AllRefsWithAttributesReferenceContent2ArgsContext(_ctx, getState());
		enterRule(_localctx, 132, RULE_allRefsWithAttributesReferenceContent2Args);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1114);
			argsOpening();
			setState(1118);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (((((_la - 98)) & ~0x3f) == 0 && ((1L << (_la - 98)) & ((1L << (POSITIONAL_PARAMETER - 98)) | (1L << (NAMED_PARAMETER - 98)) | (1L << (STRING - 98)) | (1L << (INT - 98)) | (1L << (FLOAT - 98)) | (1L << (BOOLEAN - 98)) | (1L << (DATE - 98)) | (1L << (TIME - 98)) | (1L << (DATE_TIME - 98)) | (1L << (OFFSET_DATE_TIME - 98)) | (1L << (FLOAT_NUMBER_RANGE - 98)) | (1L << (INT_NUMBER_RANGE - 98)) | (1L << (DATE_TIME_RANGE - 98)) | (1L << (UUID - 98)) | (1L << (ENUM - 98)))) != 0)) {
				{
				setState(1115);
				((AllRefsWithAttributesReferenceContent2ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1116);
				match(ARGS_DELIMITER);
				}
			}

			setState(1120);
			((AllRefsWithAttributesReferenceContent2ArgsContext)_localctx).requirement1 = requireConstraint();
			setState(1121);
			match(ARGS_DELIMITER);
			setState(1122);
			((AllRefsWithAttributesReferenceContent2ArgsContext)_localctx).requirement2 = requireConstraint();
			setState(1123);
			argsClosing();
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

	public static class AllRefsWithAttributesReferenceContent3ArgsContext extends ParserRuleContext {
		public ValueTokenContext managedReferencesBehaviour;
		public RequireConstraintContext attributeContent;
		public RequireConstraintContext facetEntityRequirement;
		public RequireConstraintContext groupEntityRequirement;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public List<RequireConstraintContext> requireConstraint() {
			return getRuleContexts(RequireConstraintContext.class);
		}
		public RequireConstraintContext requireConstraint(int i) {
			return getRuleContext(RequireConstraintContext.class,i);
		}
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
		}
		public AllRefsWithAttributesReferenceContent3ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_allRefsWithAttributesReferenceContent3Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAllRefsWithAttributesReferenceContent3Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAllRefsWithAttributesReferenceContent3Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAllRefsWithAttributesReferenceContent3Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final AllRefsWithAttributesReferenceContent3ArgsContext allRefsWithAttributesReferenceContent3Args() throws RecognitionException {
		AllRefsWithAttributesReferenceContent3ArgsContext _localctx = new AllRefsWithAttributesReferenceContent3ArgsContext(_ctx, getState());
		enterRule(_localctx, 134, RULE_allRefsWithAttributesReferenceContent3Args);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1125);
			argsOpening();
			setState(1129);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (((((_la - 98)) & ~0x3f) == 0 && ((1L << (_la - 98)) & ((1L << (POSITIONAL_PARAMETER - 98)) | (1L << (NAMED_PARAMETER - 98)) | (1L << (STRING - 98)) | (1L << (INT - 98)) | (1L << (FLOAT - 98)) | (1L << (BOOLEAN - 98)) | (1L << (DATE - 98)) | (1L << (TIME - 98)) | (1L << (DATE_TIME - 98)) | (1L << (OFFSET_DATE_TIME - 98)) | (1L << (FLOAT_NUMBER_RANGE - 98)) | (1L << (INT_NUMBER_RANGE - 98)) | (1L << (DATE_TIME_RANGE - 98)) | (1L << (UUID - 98)) | (1L << (ENUM - 98)))) != 0)) {
				{
				setState(1126);
				((AllRefsWithAttributesReferenceContent3ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1127);
				match(ARGS_DELIMITER);
				}
			}

			setState(1131);
			((AllRefsWithAttributesReferenceContent3ArgsContext)_localctx).attributeContent = requireConstraint();
			setState(1132);
			match(ARGS_DELIMITER);
			setState(1133);
			((AllRefsWithAttributesReferenceContent3ArgsContext)_localctx).facetEntityRequirement = requireConstraint();
			setState(1134);
			match(ARGS_DELIMITER);
			setState(1135);
			((AllRefsWithAttributesReferenceContent3ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(1136);
			argsClosing();
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
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
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
		enterRule(_localctx, 136, RULE_singleRequireHierarchyContentArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1138);
			argsOpening();
			setState(1139);
			((SingleRequireHierarchyContentArgsContext)_localctx).requirement = requireConstraint();
			setState(1140);
			argsClosing();
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
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
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
		enterRule(_localctx, 138, RULE_allRequiresHierarchyContentArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1142);
			argsOpening();
			setState(1143);
			((AllRequiresHierarchyContentArgsContext)_localctx).stopAt = requireConstraint();
			setState(1144);
			match(ARGS_DELIMITER);
			setState(1145);
			((AllRequiresHierarchyContentArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(1146);
			argsClosing();
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

	public static class FacetSummary1ArgsContext extends ParserRuleContext {
		public ValueTokenContext depth;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
		}
		public FacetSummary1ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_facetSummary1Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetSummary1Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetSummary1Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetSummary1Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FacetSummary1ArgsContext facetSummary1Args() throws RecognitionException {
		FacetSummary1ArgsContext _localctx = new FacetSummary1ArgsContext(_ctx, getState());
		enterRule(_localctx, 140, RULE_facetSummary1Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1148);
			argsOpening();
			setState(1149);
			((FacetSummary1ArgsContext)_localctx).depth = valueToken();
			setState(1150);
			argsClosing();
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

	public static class FacetSummary2ArgsContext extends ParserRuleContext {
		public ValueTokenContext depth;
		public FacetSummaryFilterArgsContext filter;
		public FacetSummaryOrderArgsContext order;
		public FacetSummaryRequirementsArgsContext requirements;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
		}
		public FacetSummaryFilterArgsContext facetSummaryFilterArgs() {
			return getRuleContext(FacetSummaryFilterArgsContext.class,0);
		}
		public FacetSummaryOrderArgsContext facetSummaryOrderArgs() {
			return getRuleContext(FacetSummaryOrderArgsContext.class,0);
		}
		public FacetSummaryRequirementsArgsContext facetSummaryRequirementsArgs() {
			return getRuleContext(FacetSummaryRequirementsArgsContext.class,0);
		}
		public FacetSummary2ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_facetSummary2Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetSummary2Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetSummary2Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetSummary2Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FacetSummary2ArgsContext facetSummary2Args() throws RecognitionException {
		FacetSummary2ArgsContext _localctx = new FacetSummary2ArgsContext(_ctx, getState());
		enterRule(_localctx, 142, RULE_facetSummary2Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1152);
			argsOpening();
			setState(1153);
			((FacetSummary2ArgsContext)_localctx).depth = valueToken();
			setState(1154);
			match(ARGS_DELIMITER);
			setState(1155);
			((FacetSummary2ArgsContext)_localctx).filter = facetSummaryFilterArgs();
			setState(1158);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,74,_ctx) ) {
			case 1:
				{
				setState(1156);
				match(ARGS_DELIMITER);
				setState(1157);
				((FacetSummary2ArgsContext)_localctx).order = facetSummaryOrderArgs();
				}
				break;
			}
			setState(1162);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,75,_ctx) ) {
			case 1:
				{
				setState(1160);
				match(ARGS_DELIMITER);
				setState(1161);
				((FacetSummary2ArgsContext)_localctx).requirements = facetSummaryRequirementsArgs();
				}
				break;
			}
			setState(1164);
			argsClosing();
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

	public static class FacetSummary3ArgsContext extends ParserRuleContext {
		public ValueTokenContext depth;
		public FacetSummaryOrderArgsContext order;
		public FacetSummaryRequirementsArgsContext requirements;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
		}
		public FacetSummaryOrderArgsContext facetSummaryOrderArgs() {
			return getRuleContext(FacetSummaryOrderArgsContext.class,0);
		}
		public FacetSummaryRequirementsArgsContext facetSummaryRequirementsArgs() {
			return getRuleContext(FacetSummaryRequirementsArgsContext.class,0);
		}
		public FacetSummary3ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_facetSummary3Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetSummary3Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetSummary3Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetSummary3Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FacetSummary3ArgsContext facetSummary3Args() throws RecognitionException {
		FacetSummary3ArgsContext _localctx = new FacetSummary3ArgsContext(_ctx, getState());
		enterRule(_localctx, 144, RULE_facetSummary3Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1166);
			argsOpening();
			setState(1167);
			((FacetSummary3ArgsContext)_localctx).depth = valueToken();
			setState(1168);
			match(ARGS_DELIMITER);
			setState(1169);
			((FacetSummary3ArgsContext)_localctx).order = facetSummaryOrderArgs();
			setState(1172);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,76,_ctx) ) {
			case 1:
				{
				setState(1170);
				match(ARGS_DELIMITER);
				setState(1171);
				((FacetSummary3ArgsContext)_localctx).requirements = facetSummaryRequirementsArgs();
				}
				break;
			}
			setState(1174);
			argsClosing();
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

	public static class FacetSummary4ArgsContext extends ParserRuleContext {
		public ValueTokenContext depth;
		public FacetSummaryRequirementsArgsContext requirements;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
		}
		public FacetSummaryRequirementsArgsContext facetSummaryRequirementsArgs() {
			return getRuleContext(FacetSummaryRequirementsArgsContext.class,0);
		}
		public FacetSummary4ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_facetSummary4Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetSummary4Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetSummary4Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetSummary4Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FacetSummary4ArgsContext facetSummary4Args() throws RecognitionException {
		FacetSummary4ArgsContext _localctx = new FacetSummary4ArgsContext(_ctx, getState());
		enterRule(_localctx, 146, RULE_facetSummary4Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1176);
			argsOpening();
			setState(1177);
			((FacetSummary4ArgsContext)_localctx).depth = valueToken();
			setState(1178);
			match(ARGS_DELIMITER);
			setState(1179);
			((FacetSummary4ArgsContext)_localctx).requirements = facetSummaryRequirementsArgs();
			setState(1180);
			argsClosing();
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

	public static class FacetSummary5ArgsContext extends ParserRuleContext {
		public FacetSummaryFilterArgsContext filter;
		public FacetSummaryOrderArgsContext order;
		public FacetSummaryRequirementsArgsContext requirements;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public FacetSummaryFilterArgsContext facetSummaryFilterArgs() {
			return getRuleContext(FacetSummaryFilterArgsContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public FacetSummaryOrderArgsContext facetSummaryOrderArgs() {
			return getRuleContext(FacetSummaryOrderArgsContext.class,0);
		}
		public FacetSummaryRequirementsArgsContext facetSummaryRequirementsArgs() {
			return getRuleContext(FacetSummaryRequirementsArgsContext.class,0);
		}
		public FacetSummary5ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_facetSummary5Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetSummary5Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetSummary5Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetSummary5Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FacetSummary5ArgsContext facetSummary5Args() throws RecognitionException {
		FacetSummary5ArgsContext _localctx = new FacetSummary5ArgsContext(_ctx, getState());
		enterRule(_localctx, 148, RULE_facetSummary5Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1182);
			argsOpening();
			setState(1183);
			((FacetSummary5ArgsContext)_localctx).filter = facetSummaryFilterArgs();
			setState(1186);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,77,_ctx) ) {
			case 1:
				{
				setState(1184);
				match(ARGS_DELIMITER);
				setState(1185);
				((FacetSummary5ArgsContext)_localctx).order = facetSummaryOrderArgs();
				}
				break;
			}
			setState(1190);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,78,_ctx) ) {
			case 1:
				{
				setState(1188);
				match(ARGS_DELIMITER);
				setState(1189);
				((FacetSummary5ArgsContext)_localctx).requirements = facetSummaryRequirementsArgs();
				}
				break;
			}
			setState(1192);
			argsClosing();
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

	public static class FacetSummary6ArgsContext extends ParserRuleContext {
		public FacetSummaryOrderArgsContext order;
		public FacetSummaryRequirementsArgsContext requirements;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public FacetSummaryOrderArgsContext facetSummaryOrderArgs() {
			return getRuleContext(FacetSummaryOrderArgsContext.class,0);
		}
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public FacetSummaryRequirementsArgsContext facetSummaryRequirementsArgs() {
			return getRuleContext(FacetSummaryRequirementsArgsContext.class,0);
		}
		public FacetSummary6ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_facetSummary6Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetSummary6Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetSummary6Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetSummary6Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FacetSummary6ArgsContext facetSummary6Args() throws RecognitionException {
		FacetSummary6ArgsContext _localctx = new FacetSummary6ArgsContext(_ctx, getState());
		enterRule(_localctx, 150, RULE_facetSummary6Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1194);
			argsOpening();
			setState(1195);
			((FacetSummary6ArgsContext)_localctx).order = facetSummaryOrderArgs();
			setState(1198);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,79,_ctx) ) {
			case 1:
				{
				setState(1196);
				match(ARGS_DELIMITER);
				setState(1197);
				((FacetSummary6ArgsContext)_localctx).requirements = facetSummaryRequirementsArgs();
				}
				break;
			}
			setState(1200);
			argsClosing();
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

	public static class FacetSummary7ArgsContext extends ParserRuleContext {
		public FacetSummaryRequirementsArgsContext requirements;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public FacetSummaryRequirementsArgsContext facetSummaryRequirementsArgs() {
			return getRuleContext(FacetSummaryRequirementsArgsContext.class,0);
		}
		public FacetSummary7ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_facetSummary7Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetSummary7Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetSummary7Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetSummary7Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FacetSummary7ArgsContext facetSummary7Args() throws RecognitionException {
		FacetSummary7ArgsContext _localctx = new FacetSummary7ArgsContext(_ctx, getState());
		enterRule(_localctx, 152, RULE_facetSummary7Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1202);
			argsOpening();
			setState(1203);
			((FacetSummary7ArgsContext)_localctx).requirements = facetSummaryRequirementsArgs();
			setState(1204);
			argsClosing();
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

	public static class FacetSummaryOfReference2ArgsContext extends ParserRuleContext {
		public ValueTokenContext referenceName;
		public ValueTokenContext depth;
		public FacetSummaryFilterArgsContext filter;
		public FacetSummaryOrderArgsContext order;
		public FacetSummaryRequirementsArgsContext requirements;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
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
		public FacetSummaryFilterArgsContext facetSummaryFilterArgs() {
			return getRuleContext(FacetSummaryFilterArgsContext.class,0);
		}
		public FacetSummaryOrderArgsContext facetSummaryOrderArgs() {
			return getRuleContext(FacetSummaryOrderArgsContext.class,0);
		}
		public FacetSummaryRequirementsArgsContext facetSummaryRequirementsArgs() {
			return getRuleContext(FacetSummaryRequirementsArgsContext.class,0);
		}
		public FacetSummaryOfReference2ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_facetSummaryOfReference2Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetSummaryOfReference2Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetSummaryOfReference2Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetSummaryOfReference2Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FacetSummaryOfReference2ArgsContext facetSummaryOfReference2Args() throws RecognitionException {
		FacetSummaryOfReference2ArgsContext _localctx = new FacetSummaryOfReference2ArgsContext(_ctx, getState());
		enterRule(_localctx, 154, RULE_facetSummaryOfReference2Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1206);
			argsOpening();
			setState(1207);
			((FacetSummaryOfReference2ArgsContext)_localctx).referenceName = valueToken();
			setState(1210);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,80,_ctx) ) {
			case 1:
				{
				setState(1208);
				match(ARGS_DELIMITER);
				setState(1209);
				((FacetSummaryOfReference2ArgsContext)_localctx).depth = valueToken();
				}
				break;
			}
			setState(1214);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,81,_ctx) ) {
			case 1:
				{
				setState(1212);
				match(ARGS_DELIMITER);
				setState(1213);
				((FacetSummaryOfReference2ArgsContext)_localctx).filter = facetSummaryFilterArgs();
				}
				break;
			}
			setState(1218);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,82,_ctx) ) {
			case 1:
				{
				setState(1216);
				match(ARGS_DELIMITER);
				setState(1217);
				((FacetSummaryOfReference2ArgsContext)_localctx).order = facetSummaryOrderArgs();
				}
				break;
			}
			setState(1222);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,83,_ctx) ) {
			case 1:
				{
				setState(1220);
				match(ARGS_DELIMITER);
				setState(1221);
				((FacetSummaryOfReference2ArgsContext)_localctx).requirements = facetSummaryRequirementsArgs();
				}
				break;
			}
			setState(1224);
			argsClosing();
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

	public static class FacetSummaryRequirementsArgsContext extends ParserRuleContext {
		public RequireConstraintContext requirement;
		public RequireConstraintContext facetEntityRequirement;
		public RequireConstraintContext groupEntityRequirement;
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public List<RequireConstraintContext> requireConstraint() {
			return getRuleContexts(RequireConstraintContext.class);
		}
		public RequireConstraintContext requireConstraint(int i) {
			return getRuleContext(RequireConstraintContext.class,i);
		}
		public FacetSummaryRequirementsArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_facetSummaryRequirementsArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetSummaryRequirementsArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetSummaryRequirementsArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetSummaryRequirementsArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FacetSummaryRequirementsArgsContext facetSummaryRequirementsArgs() throws RecognitionException {
		FacetSummaryRequirementsArgsContext _localctx = new FacetSummaryRequirementsArgsContext(_ctx, getState());
		enterRule(_localctx, 156, RULE_facetSummaryRequirementsArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1231);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,84,_ctx) ) {
			case 1:
				{
				{
				setState(1226);
				((FacetSummaryRequirementsArgsContext)_localctx).requirement = requireConstraint();
				}
				}
				break;
			case 2:
				{
				{
				setState(1227);
				((FacetSummaryRequirementsArgsContext)_localctx).facetEntityRequirement = requireConstraint();
				setState(1228);
				match(ARGS_DELIMITER);
				setState(1229);
				((FacetSummaryRequirementsArgsContext)_localctx).groupEntityRequirement = requireConstraint();
				}
				}
				break;
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

	public static class FacetSummaryFilterArgsContext extends ParserRuleContext {
		public FilterConstraintContext filterBy;
		public FilterConstraintContext filterGroupBy;
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public List<FilterConstraintContext> filterConstraint() {
			return getRuleContexts(FilterConstraintContext.class);
		}
		public FilterConstraintContext filterConstraint(int i) {
			return getRuleContext(FilterConstraintContext.class,i);
		}
		public FacetSummaryFilterArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_facetSummaryFilterArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetSummaryFilterArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetSummaryFilterArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetSummaryFilterArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FacetSummaryFilterArgsContext facetSummaryFilterArgs() throws RecognitionException {
		FacetSummaryFilterArgsContext _localctx = new FacetSummaryFilterArgsContext(_ctx, getState());
		enterRule(_localctx, 158, RULE_facetSummaryFilterArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1238);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,85,_ctx) ) {
			case 1:
				{
				{
				setState(1233);
				((FacetSummaryFilterArgsContext)_localctx).filterBy = filterConstraint();
				}
				}
				break;
			case 2:
				{
				{
				setState(1234);
				((FacetSummaryFilterArgsContext)_localctx).filterBy = filterConstraint();
				setState(1235);
				match(ARGS_DELIMITER);
				setState(1236);
				((FacetSummaryFilterArgsContext)_localctx).filterGroupBy = filterConstraint();
				}
				}
				break;
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

	public static class FacetSummaryOrderArgsContext extends ParserRuleContext {
		public OrderConstraintContext orderBy;
		public OrderConstraintContext orderGroupBy;
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public List<OrderConstraintContext> orderConstraint() {
			return getRuleContexts(OrderConstraintContext.class);
		}
		public OrderConstraintContext orderConstraint(int i) {
			return getRuleContext(OrderConstraintContext.class,i);
		}
		public FacetSummaryOrderArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_facetSummaryOrderArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetSummaryOrderArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetSummaryOrderArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetSummaryOrderArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FacetSummaryOrderArgsContext facetSummaryOrderArgs() throws RecognitionException {
		FacetSummaryOrderArgsContext _localctx = new FacetSummaryOrderArgsContext(_ctx, getState());
		enterRule(_localctx, 160, RULE_facetSummaryOrderArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1245);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,86,_ctx) ) {
			case 1:
				{
				{
				setState(1240);
				((FacetSummaryOrderArgsContext)_localctx).orderBy = orderConstraint();
				}
				}
				break;
			case 2:
				{
				{
				setState(1241);
				((FacetSummaryOrderArgsContext)_localctx).orderBy = orderConstraint();
				setState(1242);
				match(ARGS_DELIMITER);
				setState(1243);
				((FacetSummaryOrderArgsContext)_localctx).orderGroupBy = orderConstraint();
				}
				}
				break;
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

	public static class AttributeHistogramArgsContext extends ParserRuleContext {
		public ValueTokenContext requestedBucketCount;
		public VariadicValueTokensContext values;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
		}
		public VariadicValueTokensContext variadicValueTokens() {
			return getRuleContext(VariadicValueTokensContext.class,0);
		}
		public AttributeHistogramArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_attributeHistogramArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAttributeHistogramArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAttributeHistogramArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAttributeHistogramArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final AttributeHistogramArgsContext attributeHistogramArgs() throws RecognitionException {
		AttributeHistogramArgsContext _localctx = new AttributeHistogramArgsContext(_ctx, getState());
		enterRule(_localctx, 162, RULE_attributeHistogramArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1247);
			argsOpening();
			setState(1248);
			((AttributeHistogramArgsContext)_localctx).requestedBucketCount = valueToken();
			setState(1249);
			match(ARGS_DELIMITER);
			setState(1250);
			((AttributeHistogramArgsContext)_localctx).values = variadicValueTokens();
			setState(1251);
			argsClosing();
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

	public static class PriceHistogramArgsContext extends ParserRuleContext {
		public ValueTokenContext requestedBucketCount;
		public ValueTokenContext behaviour;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public List<ValueTokenContext> valueToken() {
			return getRuleContexts(ValueTokenContext.class);
		}
		public ValueTokenContext valueToken(int i) {
			return getRuleContext(ValueTokenContext.class,i);
		}
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
		public PriceHistogramArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_priceHistogramArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterPriceHistogramArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitPriceHistogramArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitPriceHistogramArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final PriceHistogramArgsContext priceHistogramArgs() throws RecognitionException {
		PriceHistogramArgsContext _localctx = new PriceHistogramArgsContext(_ctx, getState());
		enterRule(_localctx, 164, RULE_priceHistogramArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1253);
			argsOpening();
			setState(1254);
			((PriceHistogramArgsContext)_localctx).requestedBucketCount = valueToken();
			setState(1257);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,87,_ctx) ) {
			case 1:
				{
				setState(1255);
				match(ARGS_DELIMITER);
				setState(1256);
				((PriceHistogramArgsContext)_localctx).behaviour = valueToken();
				}
				break;
			}
			setState(1259);
			argsClosing();
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

	public static class HierarchyStatisticsArgsContext extends ParserRuleContext {
		public VariadicValueTokensContext settings;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public VariadicValueTokensContext variadicValueTokens() {
			return getRuleContext(VariadicValueTokensContext.class,0);
		}
		public HierarchyStatisticsArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_hierarchyStatisticsArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHierarchyStatisticsArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHierarchyStatisticsArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHierarchyStatisticsArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final HierarchyStatisticsArgsContext hierarchyStatisticsArgs() throws RecognitionException {
		HierarchyStatisticsArgsContext _localctx = new HierarchyStatisticsArgsContext(_ctx, getState());
		enterRule(_localctx, 166, RULE_hierarchyStatisticsArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1261);
			argsOpening();
			setState(1262);
			((HierarchyStatisticsArgsContext)_localctx).settings = variadicValueTokens();
			setState(1263);
			argsClosing();
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
		public ValueTokenContext outputName;
		public RequireConstraintContext requireConstraint;
		public List<RequireConstraintContext> requirements = new ArrayList<RequireConstraintContext>();
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
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
		enterRule(_localctx, 168, RULE_hierarchyRequireConstraintArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1265);
			argsOpening();
			setState(1266);
			((HierarchyRequireConstraintArgsContext)_localctx).outputName = valueToken();
			setState(1271);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,88,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(1267);
					match(ARGS_DELIMITER);
					setState(1268);
					((HierarchyRequireConstraintArgsContext)_localctx).requireConstraint = requireConstraint();
					((HierarchyRequireConstraintArgsContext)_localctx).requirements.add(((HierarchyRequireConstraintArgsContext)_localctx).requireConstraint);
					}
					}
				}
				setState(1273);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,88,_ctx);
			}
			setState(1274);
			argsClosing();
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
		public ValueTokenContext outputName;
		public RequireConstraintContext node;
		public RequireConstraintContext requireConstraint;
		public List<RequireConstraintContext> requirements = new ArrayList<RequireConstraintContext>();
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
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
		enterRule(_localctx, 170, RULE_hierarchyFromNodeArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1276);
			argsOpening();
			setState(1277);
			((HierarchyFromNodeArgsContext)_localctx).outputName = valueToken();
			setState(1278);
			match(ARGS_DELIMITER);
			setState(1279);
			((HierarchyFromNodeArgsContext)_localctx).node = requireConstraint();
			setState(1284);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,89,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(1280);
					match(ARGS_DELIMITER);
					setState(1281);
					((HierarchyFromNodeArgsContext)_localctx).requireConstraint = requireConstraint();
					((HierarchyFromNodeArgsContext)_localctx).requirements.add(((HierarchyFromNodeArgsContext)_localctx).requireConstraint);
					}
					}
				}
				setState(1286);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,89,_ctx);
			}
			setState(1287);
			argsClosing();
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
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
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
		enterRule(_localctx, 172, RULE_fullHierarchyOfSelfArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1289);
			argsOpening();
			setState(1290);
			((FullHierarchyOfSelfArgsContext)_localctx).orderBy = orderConstraint();
			setState(1293);
			_errHandler.sync(this);
			_alt = 1;
			do {
				switch (_alt) {
				case 1:
					{
					{
					setState(1291);
					match(ARGS_DELIMITER);
					setState(1292);
					((FullHierarchyOfSelfArgsContext)_localctx).requireConstraint = requireConstraint();
					((FullHierarchyOfSelfArgsContext)_localctx).requirements.add(((FullHierarchyOfSelfArgsContext)_localctx).requireConstraint);
					}
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				setState(1295);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,90,_ctx);
			} while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER );
			setState(1297);
			argsClosing();
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
		public ValueTokenContext referenceName;
		public RequireConstraintContext requireConstraint;
		public List<RequireConstraintContext> requirements = new ArrayList<RequireConstraintContext>();
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
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
		enterRule(_localctx, 174, RULE_basicHierarchyOfReferenceArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1299);
			argsOpening();
			setState(1300);
			((BasicHierarchyOfReferenceArgsContext)_localctx).referenceName = valueToken();
			setState(1303);
			_errHandler.sync(this);
			_alt = 1;
			do {
				switch (_alt) {
				case 1:
					{
					{
					setState(1301);
					match(ARGS_DELIMITER);
					setState(1302);
					((BasicHierarchyOfReferenceArgsContext)_localctx).requireConstraint = requireConstraint();
					((BasicHierarchyOfReferenceArgsContext)_localctx).requirements.add(((BasicHierarchyOfReferenceArgsContext)_localctx).requireConstraint);
					}
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				setState(1305);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,91,_ctx);
			} while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER );
			setState(1307);
			argsClosing();
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

	public static class BasicHierarchyOfReferenceWithBehaviourArgsContext extends ParserRuleContext {
		public ValueTokenContext referenceName;
		public ValueTokenContext emptyHierarchicalEntityBehaviour;
		public RequireConstraintContext requireConstraint;
		public List<RequireConstraintContext> requirements = new ArrayList<RequireConstraintContext>();
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public List<ValueTokenContext> valueToken() {
			return getRuleContexts(ValueTokenContext.class);
		}
		public ValueTokenContext valueToken(int i) {
			return getRuleContext(ValueTokenContext.class,i);
		}
		public List<RequireConstraintContext> requireConstraint() {
			return getRuleContexts(RequireConstraintContext.class);
		}
		public RequireConstraintContext requireConstraint(int i) {
			return getRuleContext(RequireConstraintContext.class,i);
		}
		public BasicHierarchyOfReferenceWithBehaviourArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_basicHierarchyOfReferenceWithBehaviourArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterBasicHierarchyOfReferenceWithBehaviourArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitBasicHierarchyOfReferenceWithBehaviourArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitBasicHierarchyOfReferenceWithBehaviourArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final BasicHierarchyOfReferenceWithBehaviourArgsContext basicHierarchyOfReferenceWithBehaviourArgs() throws RecognitionException {
		BasicHierarchyOfReferenceWithBehaviourArgsContext _localctx = new BasicHierarchyOfReferenceWithBehaviourArgsContext(_ctx, getState());
		enterRule(_localctx, 176, RULE_basicHierarchyOfReferenceWithBehaviourArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1309);
			argsOpening();
			setState(1310);
			((BasicHierarchyOfReferenceWithBehaviourArgsContext)_localctx).referenceName = valueToken();
			setState(1311);
			match(ARGS_DELIMITER);
			setState(1312);
			((BasicHierarchyOfReferenceWithBehaviourArgsContext)_localctx).emptyHierarchicalEntityBehaviour = valueToken();
			setState(1315);
			_errHandler.sync(this);
			_alt = 1;
			do {
				switch (_alt) {
				case 1:
					{
					{
					setState(1313);
					match(ARGS_DELIMITER);
					setState(1314);
					((BasicHierarchyOfReferenceWithBehaviourArgsContext)_localctx).requireConstraint = requireConstraint();
					((BasicHierarchyOfReferenceWithBehaviourArgsContext)_localctx).requirements.add(((BasicHierarchyOfReferenceWithBehaviourArgsContext)_localctx).requireConstraint);
					}
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				setState(1317);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,92,_ctx);
			} while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER );
			setState(1319);
			argsClosing();
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
		public ValueTokenContext referenceName;
		public OrderConstraintContext orderBy;
		public RequireConstraintContext requireConstraint;
		public List<RequireConstraintContext> requirements = new ArrayList<RequireConstraintContext>();
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
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
		enterRule(_localctx, 178, RULE_fullHierarchyOfReferenceArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1321);
			argsOpening();
			setState(1322);
			((FullHierarchyOfReferenceArgsContext)_localctx).referenceName = valueToken();
			setState(1323);
			match(ARGS_DELIMITER);
			setState(1324);
			((FullHierarchyOfReferenceArgsContext)_localctx).orderBy = orderConstraint();
			setState(1327);
			_errHandler.sync(this);
			_alt = 1;
			do {
				switch (_alt) {
				case 1:
					{
					{
					setState(1325);
					match(ARGS_DELIMITER);
					setState(1326);
					((FullHierarchyOfReferenceArgsContext)_localctx).requireConstraint = requireConstraint();
					((FullHierarchyOfReferenceArgsContext)_localctx).requirements.add(((FullHierarchyOfReferenceArgsContext)_localctx).requireConstraint);
					}
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				setState(1329);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,93,_ctx);
			} while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER );
			setState(1331);
			argsClosing();
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

	public static class FullHierarchyOfReferenceWithBehaviourArgsContext extends ParserRuleContext {
		public ValueTokenContext referenceName;
		public ValueTokenContext emptyHierarchicalEntityBehaviour;
		public OrderConstraintContext orderBy;
		public RequireConstraintContext requireConstraint;
		public List<RequireConstraintContext> requirements = new ArrayList<RequireConstraintContext>();
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public List<TerminalNode> ARGS_DELIMITER() { return getTokens(EvitaQLParser.ARGS_DELIMITER); }
		public TerminalNode ARGS_DELIMITER(int i) {
			return getToken(EvitaQLParser.ARGS_DELIMITER, i);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public List<ValueTokenContext> valueToken() {
			return getRuleContexts(ValueTokenContext.class);
		}
		public ValueTokenContext valueToken(int i) {
			return getRuleContext(ValueTokenContext.class,i);
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
		public FullHierarchyOfReferenceWithBehaviourArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_fullHierarchyOfReferenceWithBehaviourArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFullHierarchyOfReferenceWithBehaviourArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFullHierarchyOfReferenceWithBehaviourArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFullHierarchyOfReferenceWithBehaviourArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FullHierarchyOfReferenceWithBehaviourArgsContext fullHierarchyOfReferenceWithBehaviourArgs() throws RecognitionException {
		FullHierarchyOfReferenceWithBehaviourArgsContext _localctx = new FullHierarchyOfReferenceWithBehaviourArgsContext(_ctx, getState());
		enterRule(_localctx, 180, RULE_fullHierarchyOfReferenceWithBehaviourArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1333);
			argsOpening();
			setState(1334);
			((FullHierarchyOfReferenceWithBehaviourArgsContext)_localctx).referenceName = valueToken();
			setState(1335);
			match(ARGS_DELIMITER);
			setState(1336);
			((FullHierarchyOfReferenceWithBehaviourArgsContext)_localctx).emptyHierarchicalEntityBehaviour = valueToken();
			setState(1337);
			match(ARGS_DELIMITER);
			setState(1338);
			((FullHierarchyOfReferenceWithBehaviourArgsContext)_localctx).orderBy = orderConstraint();
			setState(1341);
			_errHandler.sync(this);
			_alt = 1;
			do {
				switch (_alt) {
				case 1:
					{
					{
					setState(1339);
					match(ARGS_DELIMITER);
					setState(1340);
					((FullHierarchyOfReferenceWithBehaviourArgsContext)_localctx).requireConstraint = requireConstraint();
					((FullHierarchyOfReferenceWithBehaviourArgsContext)_localctx).requirements.add(((FullHierarchyOfReferenceWithBehaviourArgsContext)_localctx).requireConstraint);
					}
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				setState(1343);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,94,_ctx);
			} while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER );
			setState(1345);
			argsClosing();
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
		enterRule(_localctx, 182, RULE_positionalParameter);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1347);
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
		enterRule(_localctx, 184, RULE_namedParameter);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1349);
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
		enterRule(_localctx, 186, RULE_variadicValueTokens);
		try {
			int _alt;
			setState(1361);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,96,_ctx) ) {
			case 1:
				_localctx = new PositionalParameterVariadicValueTokensContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(1351);
				positionalParameter();
				}
				break;
			case 2:
				_localctx = new NamedParameterVariadicValueTokensContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(1352);
				namedParameter();
				}
				break;
			case 3:
				_localctx = new ExplicitVariadicValueTokensContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(1353);
				((ExplicitVariadicValueTokensContext)_localctx).valueToken = valueToken();
				((ExplicitVariadicValueTokensContext)_localctx).valueTokens.add(((ExplicitVariadicValueTokensContext)_localctx).valueToken);
				setState(1358);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,95,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(1354);
						match(ARGS_DELIMITER);
						setState(1355);
						((ExplicitVariadicValueTokensContext)_localctx).valueToken = valueToken();
						((ExplicitVariadicValueTokensContext)_localctx).valueTokens.add(((ExplicitVariadicValueTokensContext)_localctx).valueToken);
						}
						}
					}
					setState(1360);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,95,_ctx);
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
	public static class UuidValueTokenContext extends ValueTokenContext {
		public TerminalNode UUID() { return getToken(EvitaQLParser.UUID, 0); }
		public UuidValueTokenContext(ValueTokenContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterUuidValueToken(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitUuidValueToken(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitUuidValueToken(this);
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
		enterRule(_localctx, 188, RULE_valueToken);
		try {
			setState(1378);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case POSITIONAL_PARAMETER:
				_localctx = new PositionalParameterValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(1363);
				positionalParameter();
				}
				break;
			case NAMED_PARAMETER:
				_localctx = new NamedParameterValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(1364);
				namedParameter();
				}
				break;
			case STRING:
				_localctx = new StringValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(1365);
				match(STRING);
				}
				break;
			case INT:
				_localctx = new IntValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 4);
				{
				setState(1366);
				match(INT);
				}
				break;
			case FLOAT:
				_localctx = new FloatValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 5);
				{
				setState(1367);
				match(FLOAT);
				}
				break;
			case BOOLEAN:
				_localctx = new BooleanValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 6);
				{
				setState(1368);
				match(BOOLEAN);
				}
				break;
			case DATE:
				_localctx = new DateValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 7);
				{
				setState(1369);
				match(DATE);
				}
				break;
			case TIME:
				_localctx = new TimeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 8);
				{
				setState(1370);
				match(TIME);
				}
				break;
			case DATE_TIME:
				_localctx = new DateTimeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 9);
				{
				setState(1371);
				match(DATE_TIME);
				}
				break;
			case OFFSET_DATE_TIME:
				_localctx = new OffsetDateTimeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 10);
				{
				setState(1372);
				match(OFFSET_DATE_TIME);
				}
				break;
			case FLOAT_NUMBER_RANGE:
				_localctx = new FloatNumberRangeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 11);
				{
				setState(1373);
				match(FLOAT_NUMBER_RANGE);
				}
				break;
			case INT_NUMBER_RANGE:
				_localctx = new IntNumberRangeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 12);
				{
				setState(1374);
				match(INT_NUMBER_RANGE);
				}
				break;
			case DATE_TIME_RANGE:
				_localctx = new DateTimeRangeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 13);
				{
				setState(1375);
				match(DATE_TIME_RANGE);
				}
				break;
			case UUID:
				_localctx = new UuidValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 14);
				{
				setState(1376);
				match(UUID);
				}
				break;
			case ENUM:
				_localctx = new EnumValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 15);
				{
				setState(1377);
				match(ENUM);
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
		"\3\u608b\ua72a\u8133\ub9ed\u417c\u3be7\u7786\u5964\3x\u0567\4\2\t\2\4"+
		"\3\t\3\4\4\t\4\4\5\t\5\4\6\t\6\4\7\t\7\4\b\t\b\4\t\t\t\4\n\t\n\4\13\t"+
		"\13\4\f\t\f\4\r\t\r\4\16\t\16\4\17\t\17\4\20\t\20\4\21\t\21\4\22\t\22"+
		"\4\23\t\23\4\24\t\24\4\25\t\25\4\26\t\26\4\27\t\27\4\30\t\30\4\31\t\31"+
		"\4\32\t\32\4\33\t\33\4\34\t\34\4\35\t\35\4\36\t\36\4\37\t\37\4 \t \4!"+
		"\t!\4\"\t\"\4#\t#\4$\t$\4%\t%\4&\t&\4\'\t\'\4(\t(\4)\t)\4*\t*\4+\t+\4"+
		",\t,\4-\t-\4.\t.\4/\t/\4\60\t\60\4\61\t\61\4\62\t\62\4\63\t\63\4\64\t"+
		"\64\4\65\t\65\4\66\t\66\4\67\t\67\48\t8\49\t9\4:\t:\4;\t;\4<\t<\4=\t="+
		"\4>\t>\4?\t?\4@\t@\4A\tA\4B\tB\4C\tC\4D\tD\4E\tE\4F\tF\4G\tG\4H\tH\4I"+
		"\tI\4J\tJ\4K\tK\4L\tL\4M\tM\4N\tN\4O\tO\4P\tP\4Q\tQ\4R\tR\4S\tS\4T\tT"+
		"\4U\tU\4V\tV\4W\tW\4X\tX\4Y\tY\4Z\tZ\4[\t[\4\\\t\\\4]\t]\4^\t^\4_\t_\4"+
		"`\t`\3\2\3\2\3\2\3\3\3\3\3\3\3\4\3\4\3\4\3\5\3\5\3\5\3\6\3\6\3\6\3\7\3"+
		"\7\3\7\3\b\3\b\3\b\3\t\3\t\3\t\3\t\5\t\u00da\n\t\3\n\3\n\3\n\3\13\3\13"+
		"\3\13\3\13\3\13\3\13\3\13\5\13\u00e6\n\13\3\13\3\13\3\13\5\13\u00eb\n"+
		"\13\3\13\3\13\3\13\3\13\3\13\5\13\u00f2\n\13\3\13\3\13\3\13\3\13\3\13"+
		"\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13"+
		"\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13"+
		"\3\13\3\13\3\13\3\13\5\13\u0119\n\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13"+
		"\5\13\u0122\n\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13"+
		"\5\13\u012f\n\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\5\13\u013a"+
		"\n\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\5\13\u0146\n\13"+
		"\3\f\3\f\3\f\5\f\u014b\n\f\3\f\3\f\3\f\5\f\u0150\n\f\3\f\3\f\3\f\3\f\3"+
		"\f\3\f\3\f\3\f\3\f\5\f\u015b\n\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\5"+
		"\f\u0166\n\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\5\f\u0170\n\f\3\r\3\r\3\r"+
		"\5\r\u0175\n\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\5\r\u017e\n\r\3\r\3\r\3\r\5"+
		"\r\u0183\n\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\5\r\u0190\n\r"+
		"\3\r\3\r\3\r\3\r\3\r\3\r\3\r\5\r\u0199\n\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r"+
		"\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\5\r\u01b0\n\r"+
		"\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3"+
		"\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r"+
		"\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\5\r\u01dd\n\r\3\r\3\r\3\r\3\r\3\r\3\r"+
		"\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3"+
		"\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\5\r\u0204\n\r\3"+
		"\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r"+
		"\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\5\r\u0222\n\r\3\16\3\16\3\16"+
		"\7\16\u0227\n\16\f\16\16\16\u022a\13\16\3\17\3\17\3\17\7\17\u022f\n\17"+
		"\f\17\16\17\u0232\13\17\3\20\3\20\3\20\7\20\u0237\n\20\f\20\16\20\u023a"+
		"\13\20\3\21\3\21\3\21\7\21\u023f\n\21\f\21\16\21\u0242\13\21\3\22\3\22"+
		"\3\23\5\23\u0247\n\23\3\23\3\23\3\24\3\24\3\24\3\24\7\24\u024f\n\24\f"+
		"\24\16\24\u0252\13\24\3\24\3\24\3\25\3\25\3\25\3\26\3\26\3\26\3\26\7\26"+
		"\u025d\n\26\f\26\16\26\u0260\13\26\3\26\3\26\3\27\3\27\3\27\3\27\3\30"+
		"\3\30\3\30\3\30\7\30\u026c\n\30\f\30\16\30\u026f\13\30\3\30\3\30\3\31"+
		"\3\31\3\31\3\31\3\32\3\32\3\32\3\32\7\32\u027b\n\32\f\32\16\32\u027e\13"+
		"\32\3\32\3\32\3\33\3\33\3\33\3\33\3\34\3\34\3\34\3\34\3\34\3\34\3\35\3"+
		"\35\3\35\3\35\5\35\u0290\n\35\3\35\3\35\3\36\3\36\3\36\3\36\3\36\3\36"+
		"\3\37\3\37\3\37\3\37\5\37\u029e\n\37\3\37\3\37\3 \3 \3 \3 \3 \3 \3 \3"+
		" \3!\3!\3!\3!\3\"\3\"\3\"\3\"\3#\3#\3#\3#\3#\3#\3$\3$\3$\3$\3%\3%\3%\3"+
		"%\3%\3%\3&\3&\3&\3&\5&\u02c6\n&\3&\3&\3\'\3\'\3\'\3\'\6\'\u02ce\n\'\r"+
		"\'\16\'\u02cf\3\'\3\'\3(\3(\3(\3(\7(\u02d8\n(\f(\16(\u02db\13(\3(\3(\3"+
		")\3)\3)\3)\3)\3)\7)\u02e5\n)\f)\16)\u02e8\13)\3)\3)\3*\3*\3*\3*\7*\u02f0"+
		"\n*\f*\16*\u02f3\13*\3*\3*\3+\3+\3+\3+\3+\7+\u02fc\n+\f+\16+\u02ff\13"+
		"+\5+\u0301\n+\3+\3+\3,\3,\3,\3,\7,\u0309\n,\f,\16,\u030c\13,\3,\3,\3-"+
		"\3-\3-\3-\3-\3-\3.\3.\3.\3.\3.\3.\3/\3/\3/\3/\3/\3/\3\60\3\60\3\60\3\60"+
		"\5\60\u0326\n\60\3\60\3\60\3\61\3\61\3\61\3\61\5\61\u032e\n\61\3\61\3"+
		"\61\3\61\5\61\u0333\n\61\3\61\3\61\3\62\3\62\3\62\3\62\5\62\u033b\n\62"+
		"\3\62\3\62\3\62\3\62\3\62\3\62\3\62\3\63\3\63\3\63\3\63\5\63\u0348\n\63"+
		"\3\63\3\63\3\63\3\63\3\63\5\63\u034f\n\63\3\63\3\63\3\64\3\64\3\64\3\64"+
		"\5\64\u0357\n\64\3\64\3\64\3\64\3\64\3\64\3\64\3\64\3\64\3\64\3\65\3\65"+
		"\3\65\3\65\5\65\u0366\n\65\3\65\3\65\3\65\3\65\3\65\5\65\u036d\n\65\3"+
		"\65\3\65\3\66\3\66\3\66\3\66\5\66\u0375\n\66\3\66\3\66\3\66\3\66\3\66"+
		"\3\66\3\66\3\66\3\66\3\67\3\67\3\67\3\67\5\67\u0384\n\67\3\67\3\67\3\67"+
		"\3\67\3\67\3\67\3\67\5\67\u038d\n\67\3\67\3\67\38\38\38\38\58\u0395\n"+
		"8\38\38\38\38\38\38\38\38\38\38\38\39\39\39\39\59\u03a6\n9\39\39\39\3"+
		"9\39\39\39\3:\3:\3:\3:\5:\u03b3\n:\3:\3:\3:\3:\3:\3:\3:\3:\3:\3;\3;\3"+
		";\3;\5;\u03c2\n;\3;\3;\3;\3;\3;\3;\3;\3;\3;\3<\3<\3<\3<\5<\u03d1\n<\3"+
		"<\3<\3<\3<\3<\3<\3<\3<\3<\3<\3<\3=\3=\3=\3=\5=\u03e2\n=\3=\3=\3=\3=\3"+
		"=\3=\3=\3=\3=\3>\3>\3>\3>\5>\u03f1\n>\3>\3>\3>\3>\3>\3>\3>\3>\3>\3>\3"+
		">\3?\3?\3?\3?\5?\u0402\n?\3?\3?\3?\3?\3?\3?\3?\3?\3?\3?\3?\3@\3@\3@\3"+
		"@\5@\u0413\n@\3@\3@\3@\3@\3@\3@\3@\3@\3@\3@\3@\3@\3@\3A\3A\3A\3A\5A\u0426"+
		"\nA\3A\3A\3A\5A\u042b\nA\3A\3A\3A\5A\u0430\nA\3A\3A\3A\3A\3A\3A\5A\u0438"+
		"\nA\3A\3A\3B\3B\3B\3B\3B\5B\u0441\nB\3B\3B\3B\3B\5B\u0447\nB\3B\3B\3B"+
		"\3B\5B\u044d\nB\3B\3B\3C\3C\3C\3C\3C\5C\u0456\nC\3C\5C\u0459\nC\3C\3C"+
		"\3D\3D\3D\3D\5D\u0461\nD\3D\3D\3D\3D\3D\3E\3E\3E\3E\5E\u046c\nE\3E\3E"+
		"\3E\3E\3E\3E\3E\3F\3F\3F\3F\3G\3G\3G\3G\3G\3G\3H\3H\3H\3H\3I\3I\3I\3I"+
		"\3I\3I\5I\u0489\nI\3I\3I\5I\u048d\nI\3I\3I\3J\3J\3J\3J\3J\3J\5J\u0497"+
		"\nJ\3J\3J\3K\3K\3K\3K\3K\3K\3L\3L\3L\3L\5L\u04a5\nL\3L\3L\5L\u04a9\nL"+
		"\3L\3L\3M\3M\3M\3M\5M\u04b1\nM\3M\3M\3N\3N\3N\3N\3O\3O\3O\3O\5O\u04bd"+
		"\nO\3O\3O\5O\u04c1\nO\3O\3O\5O\u04c5\nO\3O\3O\5O\u04c9\nO\3O\3O\3P\3P"+
		"\3P\3P\3P\5P\u04d2\nP\3Q\3Q\3Q\3Q\3Q\5Q\u04d9\nQ\3R\3R\3R\3R\3R\5R\u04e0"+
		"\nR\3S\3S\3S\3S\3S\3S\3T\3T\3T\3T\5T\u04ec\nT\3T\3T\3U\3U\3U\3U\3V\3V"+
		"\3V\3V\7V\u04f8\nV\fV\16V\u04fb\13V\3V\3V\3W\3W\3W\3W\3W\3W\7W\u0505\n"+
		"W\fW\16W\u0508\13W\3W\3W\3X\3X\3X\3X\6X\u0510\nX\rX\16X\u0511\3X\3X\3"+
		"Y\3Y\3Y\3Y\6Y\u051a\nY\rY\16Y\u051b\3Y\3Y\3Z\3Z\3Z\3Z\3Z\3Z\6Z\u0526\n"+
		"Z\rZ\16Z\u0527\3Z\3Z\3[\3[\3[\3[\3[\3[\6[\u0532\n[\r[\16[\u0533\3[\3["+
		"\3\\\3\\\3\\\3\\\3\\\3\\\3\\\3\\\6\\\u0540\n\\\r\\\16\\\u0541\3\\\3\\"+
		"\3]\3]\3^\3^\3_\3_\3_\3_\3_\7_\u054f\n_\f_\16_\u0552\13_\5_\u0554\n_\3"+
		"`\3`\3`\3`\3`\3`\3`\3`\3`\3`\3`\3`\3`\3`\3`\5`\u0565\n`\3`\2\2a\2\4\6"+
		"\b\n\f\16\20\22\24\26\30\32\34\36 \"$&(*,.\60\62\64\668:<>@BDFHJLNPRT"+
		"VXZ\\^`bdfhjlnprtvxz|~\u0080\u0082\u0084\u0086\u0088\u008a\u008c\u008e"+
		"\u0090\u0092\u0094\u0096\u0098\u009a\u009c\u009e\u00a0\u00a2\u00a4\u00a6"+
		"\u00a8\u00aa\u00ac\u00ae\u00b0\u00b2\u00b4\u00b6\u00b8\u00ba\u00bc\u00be"+
		"\2\2\2\u05f7\2\u00c0\3\2\2\2\4\u00c3\3\2\2\2\6\u00c6\3\2\2\2\b\u00c9\3"+
		"\2\2\2\n\u00cc\3\2\2\2\f\u00cf\3\2\2\2\16\u00d2\3\2\2\2\20\u00d9\3\2\2"+
		"\2\22\u00db\3\2\2\2\24\u0145\3\2\2\2\26\u016f\3\2\2\2\30\u0221\3\2\2\2"+
		"\32\u0223\3\2\2\2\34\u022b\3\2\2\2\36\u0233\3\2\2\2 \u023b\3\2\2\2\"\u0243"+
		"\3\2\2\2$\u0246\3\2\2\2&\u024a\3\2\2\2(\u0255\3\2\2\2*\u0258\3\2\2\2,"+
		"\u0263\3\2\2\2.\u0267\3\2\2\2\60\u0272\3\2\2\2\62\u0276\3\2\2\2\64\u0281"+
		"\3\2\2\2\66\u0285\3\2\2\28\u028b\3\2\2\2:\u0293\3\2\2\2<\u0299\3\2\2\2"+
		">\u02a1\3\2\2\2@\u02a9\3\2\2\2B\u02ad\3\2\2\2D\u02b1\3\2\2\2F\u02b7\3"+
		"\2\2\2H\u02bb\3\2\2\2J\u02c1\3\2\2\2L\u02c9\3\2\2\2N\u02d3\3\2\2\2P\u02de"+
		"\3\2\2\2R\u02eb\3\2\2\2T\u02f6\3\2\2\2V\u0304\3\2\2\2X\u030f\3\2\2\2Z"+
		"\u0315\3\2\2\2\\\u031b\3\2\2\2^\u0321\3\2\2\2`\u0329\3\2\2\2b\u0336\3"+
		"\2\2\2d\u0343\3\2\2\2f\u0352\3\2\2\2h\u0361\3\2\2\2j\u0370\3\2\2\2l\u037f"+
		"\3\2\2\2n\u0390\3\2\2\2p\u03a1\3\2\2\2r\u03ae\3\2\2\2t\u03bd\3\2\2\2v"+
		"\u03cc\3\2\2\2x\u03dd\3\2\2\2z\u03ec\3\2\2\2|\u03fd\3\2\2\2~\u040e\3\2"+
		"\2\2\u0080\u0421\3\2\2\2\u0082\u043b\3\2\2\2\u0084\u0450\3\2\2\2\u0086"+
		"\u045c\3\2\2\2\u0088\u0467\3\2\2\2\u008a\u0474\3\2\2\2\u008c\u0478\3\2"+
		"\2\2\u008e\u047e\3\2\2\2\u0090\u0482\3\2\2\2\u0092\u0490\3\2\2\2\u0094"+
		"\u049a\3\2\2\2\u0096\u04a0\3\2\2\2\u0098\u04ac\3\2\2\2\u009a\u04b4\3\2"+
		"\2\2\u009c\u04b8\3\2\2\2\u009e\u04d1\3\2\2\2\u00a0\u04d8\3\2\2\2\u00a2"+
		"\u04df\3\2\2\2\u00a4\u04e1\3\2\2\2\u00a6\u04e7\3\2\2\2\u00a8\u04ef\3\2"+
		"\2\2\u00aa\u04f3\3\2\2\2\u00ac\u04fe\3\2\2\2\u00ae\u050b\3\2\2\2\u00b0"+
		"\u0515\3\2\2\2\u00b2\u051f\3\2\2\2\u00b4\u052b\3\2\2\2\u00b6\u0537\3\2"+
		"\2\2\u00b8\u0545\3\2\2\2\u00ba\u0547\3\2\2\2\u00bc\u0553\3\2\2\2\u00be"+
		"\u0564\3\2\2\2\u00c0\u00c1\5\16\b\2\u00c1\u00c2\7\2\2\3\u00c2\3\3\2\2"+
		"\2\u00c3\u00c4\5\32\16\2\u00c4\u00c5\7\2\2\3\u00c5\5\3\2\2\2\u00c6\u00c7"+
		"\5\34\17\2\u00c7\u00c8\7\2\2\3\u00c8\7\3\2\2\2\u00c9\u00ca\5\36\20\2\u00ca"+
		"\u00cb\7\2\2\3\u00cb\t\3\2\2\2\u00cc\u00cd\5 \21\2\u00cd\u00ce\7\2\2\3"+
		"\u00ce\13\3\2\2\2\u00cf\u00d0\5\u00be`\2\u00d0\u00d1\7\2\2\3\u00d1\r\3"+
		"\2\2\2\u00d2\u00d3\7\3\2\2\u00d3\u00d4\5&\24\2\u00d4\17\3\2\2\2\u00d5"+
		"\u00da\5\22\n\2\u00d6\u00da\5\24\13\2\u00d7\u00da\5\26\f\2\u00d8\u00da"+
		"\5\30\r\2\u00d9\u00d5\3\2\2\2\u00d9\u00d6\3\2\2\2\u00d9\u00d7\3\2\2\2"+
		"\u00d9\u00d8\3\2\2\2\u00da\21\3\2\2\2\u00db\u00dc\7\4\2\2\u00dc\u00dd"+
		"\5\64\33\2\u00dd\23\3\2\2\2\u00de\u00df\7\5\2\2\u00df\u0146\5*\26\2\u00e0"+
		"\u00e1\7\6\2\2\u00e1\u0146\5*\26\2\u00e2\u00e5\7\7\2\2\u00e3\u00e6\5("+
		"\25\2\u00e4\u00e6\5*\26\2\u00e5\u00e3\3\2\2\2\u00e5\u00e4\3\2\2\2\u00e6"+
		"\u0146\3\2\2\2\u00e7\u00ea\7\b\2\2\u00e8\u00eb\5(\25\2\u00e9\u00eb\5*"+
		"\26\2\u00ea\u00e8\3\2\2\2\u00ea\u00e9\3\2\2\2\u00eb\u0146\3\2\2\2\u00ec"+
		"\u00ed\7\t\2\2\u00ed\u0146\5,\27\2\u00ee\u00f1\7\n\2\2\u00ef\u00f2\5("+
		"\25\2\u00f0\u00f2\5*\26\2\u00f1\u00ef\3\2\2\2\u00f1\u00f0\3\2\2\2\u00f2"+
		"\u0146\3\2\2\2\u00f3\u00f4\7\13\2\2\u00f4\u0146\5\66\34\2\u00f5\u00f6"+
		"\7\f\2\2\u00f6\u0146\5\66\34\2\u00f7\u00f8\7\r\2\2\u00f8\u0146\5\66\34"+
		"\2\u00f9\u00fa\7\16\2\2\u00fa\u0146\5\66\34\2\u00fb\u00fc\7\17\2\2\u00fc"+
		"\u0146\5\66\34\2\u00fd\u00fe\7\20\2\2\u00fe\u0146\5> \2\u00ff\u0100\7"+
		"\21\2\2\u0100\u0146\5<\37\2\u0101\u0102\7\22\2\2\u0102\u0146\5\66\34\2"+
		"\u0103\u0104\7\23\2\2\u0104\u0146\5\66\34\2\u0105\u0106\7\24\2\2\u0106"+
		"\u0146\5\66\34\2\u0107\u0108\7\25\2\2\u0108\u0146\5\64\33\2\u0109\u010a"+
		"\7\26\2\2\u010a\u0146\5\64\33\2\u010b\u010c\7\27\2\2\u010c\u0146\5\66"+
		"\34\2\u010d\u010e\7\30\2\2\u010e\u0146\5\64\33\2\u010f\u0110\7\31\2\2"+
		"\u0110\u0146\5\64\33\2\u0111\u0112\7\32\2\2\u0112\u0146\5\66\34\2\u0113"+
		"\u0114\7\33\2\2\u0114\u0146\5\64\33\2\u0115\u0118\7\34\2\2\u0116\u0119"+
		"\5(\25\2\u0117\u0119\5B\"\2\u0118\u0116\3\2\2\2\u0118\u0117\3\2\2\2\u0119"+
		"\u0146\3\2\2\2\u011a\u011b\7\35\2\2\u011b\u0146\5@!\2\u011c\u011d\7\36"+
		"\2\2\u011d\u0146\5@!\2\u011e\u0121\7\37\2\2\u011f\u0122\5(\25\2\u0120"+
		"\u0122\5F$\2\u0121\u011f\3\2\2\2\u0121\u0120\3\2\2\2\u0122\u0146\3\2\2"+
		"\2\u0123\u0124\7 \2\2\u0124\u0146\5(\25\2\u0125\u0126\7!\2\2\u0126\u0146"+
		"\5@!\2\u0127\u0128\7\"\2\2\u0128\u0146\5D#\2\u0129\u012a\7#\2\2\u012a"+
		"\u0146\5H%\2\u012b\u012e\7$\2\2\u012c\u012f\5\64\33\2\u012d\u012f\5H%"+
		"\2\u012e\u012c\3\2\2\2\u012e\u012d\3\2\2\2\u012f\u0146\3\2\2\2\u0130\u0131"+
		"\7%\2\2\u0131\u0146\5P)\2\u0132\u0133\7&\2\2\u0133\u0146\5R*\2\u0134\u0135"+
		"\7\'\2\2\u0135\u0146\5T+\2\u0136\u0139\7(\2\2\u0137\u013a\5(\25\2\u0138"+
		"\u013a\5V,\2\u0139\u0137\3\2\2\2\u0139\u0138\3\2\2\2\u013a\u0146\3\2\2"+
		"\2\u013b\u013c\7)\2\2\u013c\u0146\5(\25\2\u013d\u013e\7*\2\2\u013e\u0146"+
		"\5*\26\2\u013f\u0140\7+\2\2\u0140\u0146\5(\25\2\u0141\u0142\7,\2\2\u0142"+
		"\u0146\5*\26\2\u0143\u0144\7-\2\2\u0144\u0146\5,\27\2\u0145\u00de\3\2"+
		"\2\2\u0145\u00e0\3\2\2\2\u0145\u00e2\3\2\2\2\u0145\u00e7\3\2\2\2\u0145"+
		"\u00ec\3\2\2\2\u0145\u00ee\3\2\2\2\u0145\u00f3\3\2\2\2\u0145\u00f5\3\2"+
		"\2\2\u0145\u00f7\3\2\2\2\u0145\u00f9\3\2\2\2\u0145\u00fb\3\2\2\2\u0145"+
		"\u00fd\3\2\2\2\u0145\u00ff\3\2\2\2\u0145\u0101\3\2\2\2\u0145\u0103\3\2"+
		"\2\2\u0145\u0105\3\2\2\2\u0145\u0107\3\2\2\2\u0145\u0109\3\2\2\2\u0145"+
		"\u010b\3\2\2\2\u0145\u010d\3\2\2\2\u0145\u010f\3\2\2\2\u0145\u0111\3\2"+
		"\2\2\u0145\u0113\3\2\2\2\u0145\u0115\3\2\2\2\u0145\u011a\3\2\2\2\u0145"+
		"\u011c\3\2\2\2\u0145\u011e\3\2\2\2\u0145\u0123\3\2\2\2\u0145\u0125\3\2"+
		"\2\2\u0145\u0127\3\2\2\2\u0145\u0129\3\2\2\2\u0145\u012b\3\2\2\2\u0145"+
		"\u0130\3\2\2\2\u0145\u0132\3\2\2\2\u0145\u0134\3\2\2\2\u0145\u0136\3\2"+
		"\2\2\u0145\u013b\3\2\2\2\u0145\u013d\3\2\2\2\u0145\u013f\3\2\2\2\u0145"+
		"\u0141\3\2\2\2\u0145\u0143\3\2\2\2\u0146\25\3\2\2\2\u0147\u014a\7.\2\2"+
		"\u0148\u014b\5(\25\2\u0149\u014b\5.\30\2\u014a\u0148\3\2\2\2\u014a\u0149"+
		"\3\2\2\2\u014b\u0170\3\2\2\2\u014c\u014f\7/\2\2\u014d\u0150\5(\25\2\u014e"+
		"\u0150\5.\30\2\u014f\u014d\3\2\2\2\u014f\u014e\3\2\2\2\u0150\u0170\3\2"+
		"\2\2\u0151\u0152\7\60\2\2\u0152\u0170\58\35\2\u0153\u0154\7\61\2\2\u0154"+
		"\u0170\5X-\2\u0155\u0156\7\62\2\2\u0156\u0170\5\64\33\2\u0157\u015a\7"+
		"\63\2\2\u0158\u015b\5(\25\2\u0159\u015b\5@!\2\u015a\u0158\3\2\2\2\u015a"+
		"\u0159\3\2\2\2\u015b\u0170\3\2\2\2\u015c\u015d\7\64\2\2\u015d\u0170\5"+
		"B\"\2\u015e\u015f\7\65\2\2\u015f\u0170\5(\25\2\u0160\u0161\7\66\2\2\u0161"+
		"\u0170\5L\'\2\u0162\u0165\7\67\2\2\u0163\u0166\5(\25\2\u0164\u0166\5@"+
		"!\2\u0165\u0163\3\2\2\2\u0165\u0164\3\2\2\2\u0166\u0170\3\2\2\2\u0167"+
		"\u0168\78\2\2\u0168\u0170\5B\"\2\u0169\u016a\79\2\2\u016a\u0170\5(\25"+
		"\2\u016b\u016c\7:\2\2\u016c\u0170\5.\30\2\u016d\u016e\7;\2\2\u016e\u0170"+
		"\5.\30\2\u016f\u0147\3\2\2\2\u016f\u014c\3\2\2\2\u016f\u0151\3\2\2\2\u016f"+
		"\u0153\3\2\2\2\u016f\u0155\3\2\2\2\u016f\u0157\3\2\2\2\u016f\u015c\3\2"+
		"\2\2\u016f\u015e\3\2\2\2\u016f\u0160\3\2\2\2\u016f\u0162\3\2\2\2\u016f"+
		"\u0167\3\2\2\2\u016f\u0169\3\2\2\2\u016f\u016b\3\2\2\2\u016f\u016d\3\2"+
		"\2\2\u0170\27\3\2\2\2\u0171\u0174\7<\2\2\u0172\u0175\5(\25\2\u0173\u0175"+
		"\5\62\32\2\u0174\u0172\3\2\2\2\u0174\u0173\3\2\2\2\u0175\u0222\3\2\2\2"+
		"\u0176\u0177\7=\2\2\u0177\u0222\5Z.\2\u0178\u0179\7>\2\2\u0179\u0222\5"+
		"\\/\2\u017a\u017d\7?\2\2\u017b\u017e\5(\25\2\u017c\u017e\5\62\32\2\u017d"+
		"\u017b\3\2\2\2\u017d\u017c\3\2\2\2\u017e\u0222\3\2\2\2\u017f\u0182\7@"+
		"\2\2\u0180\u0183\5(\25\2\u0181\u0183\5\62\32\2\u0182\u0180\3\2\2\2\u0182"+
		"\u0181\3\2\2\2\u0183\u0222\3\2\2\2\u0184\u0185\7A\2\2\u0185\u0222\5F$"+
		"\2\u0186\u0187\7B\2\2\u0187\u0222\5(\25\2\u0188\u0189\7C\2\2\u0189\u0222"+
		"\5^\60\2\u018a\u018b\7D\2\2\u018b\u0222\5(\25\2\u018c\u018f\7E\2\2\u018d"+
		"\u0190\5(\25\2\u018e\u0190\5B\"\2\u018f\u018d\3\2\2\2\u018f\u018e\3\2"+
		"\2\2\u0190\u0222\3\2\2\2\u0191\u0192\7F\2\2\u0192\u0222\5F$\2\u0193\u0194"+
		"\7G\2\2\u0194\u0222\5(\25\2\u0195\u0198\7H\2\2\u0196\u0199\5(\25\2\u0197"+
		"\u0199\5\u0082B\2\u0198\u0196\3\2\2\2\u0198\u0197\3\2\2\2\u0199\u0222"+
		"\3\2\2\2\u019a\u019b\7I\2\2\u019b\u0222\5\u0080A\2\u019c\u019d\7I\2\2"+
		"\u019d\u0222\5`\61\2\u019e\u019f\7I\2\2\u019f\u0222\5b\62\2\u01a0\u01a1"+
		"\7I\2\2\u01a1\u0222\5d\63\2\u01a2\u01a3\7I\2\2\u01a3\u0222\5f\64\2\u01a4"+
		"\u01a5\7I\2\2\u01a5\u0222\5h\65\2\u01a6\u01a7\7I\2\2\u01a7\u0222\5j\66"+
		"\2\u01a8\u01a9\7I\2\2\u01a9\u0222\5l\67\2\u01aa\u01ab\7I\2\2\u01ab\u0222"+
		"\5n8\2\u01ac\u01af\7J\2\2\u01ad\u01b0\5(\25\2\u01ae\u01b0\5\u0084C\2\u01af"+
		"\u01ad\3\2\2\2\u01af\u01ae\3\2\2\2\u01b0\u0222\3\2\2\2\u01b1\u01b2\7J"+
		"\2\2\u01b2\u0222\5\u0086D\2\u01b3\u01b4\7J\2\2\u01b4\u0222\5\u0088E\2"+
		"\u01b5\u01b6\7K\2\2\u01b6\u0222\5`\61\2\u01b7\u01b8\7K\2\2\u01b8\u0222"+
		"\5p9\2\u01b9\u01ba\7K\2\2\u01ba\u0222\5r:\2\u01bb\u01bc\7K\2\2\u01bc\u0222"+
		"\5d\63\2\u01bd\u01be\7K\2\2\u01be\u0222\5t;\2\u01bf\u01c0\7K\2\2\u01c0"+
		"\u0222\5v<\2\u01c1\u01c2\7K\2\2\u01c2\u0222\5h\65\2\u01c3\u01c4\7K\2\2"+
		"\u01c4\u0222\5x=\2\u01c5\u01c6\7K\2\2\u01c6\u0222\5z>\2\u01c7\u01c8\7"+
		"K\2\2\u01c8\u0222\5l\67\2\u01c9\u01ca\7K\2\2\u01ca\u0222\5|?\2\u01cb\u01cc"+
		"\7K\2\2\u01cc\u0222\5~@\2\u01cd\u01ce\7L\2\2\u01ce\u0222\5(\25\2\u01cf"+
		"\u01d0\7L\2\2\u01d0\u0222\5\u008aF\2\u01d1\u01d2\7L\2\2\u01d2\u0222\5"+
		"\u008cG\2\u01d3\u01d4\7M\2\2\u01d4\u0222\5@!\2\u01d5\u01d6\7N\2\2\u01d6"+
		"\u0222\5(\25\2\u01d7\u01d8\7O\2\2\u01d8\u0222\5B\"\2\u01d9\u01dc\7P\2"+
		"\2\u01da\u01dd\5(\25\2\u01db\u01dd\5\u008eH\2\u01dc\u01da\3\2\2\2\u01dc"+
		"\u01db\3\2\2\2\u01dd\u0222\3\2\2\2\u01de\u01df\7P\2\2\u01df\u0222\5\u0090"+
		"I\2\u01e0\u01e1\7P\2\2\u01e1\u0222\5\u0092J\2\u01e2\u01e3\7P\2\2\u01e3"+
		"\u0222\5\u0094K\2\u01e4\u01e5\7P\2\2\u01e5\u0222\5\u0096L\2\u01e6\u01e7"+
		"\7P\2\2\u01e7\u0222\5\u0098M\2\u01e8\u01e9\7P\2\2\u01e9\u0222\5\u009a"+
		"N\2\u01ea\u01eb\7Q\2\2\u01eb\u0222\5\64\33\2\u01ec\u01ed\7Q\2\2\u01ed"+
		"\u0222\5\u009cO\2\u01ee\u01ef\7R\2\2\u01ef\u0222\5J&\2\u01f0\u01f1\7S"+
		"\2\2\u01f1\u0222\5J&\2\u01f2\u01f3\7T\2\2\u01f3\u0222\5J&\2\u01f4\u01f5"+
		"\7U\2\2\u01f5\u0222\5\u00a4S\2\u01f6\u01f7\7V\2\2\u01f7\u0222\5\u00a6"+
		"T\2\u01f8\u01f9\7W\2\2\u01f9\u0222\5@!\2\u01fa\u01fb\7X\2\2\u01fb\u0222"+
		"\5@!\2\u01fc\u01fd\7Y\2\2\u01fd\u0222\5,\27\2\u01fe\u01ff\7Z\2\2\u01ff"+
		"\u0222\5\60\31\2\u0200\u0203\7[\2\2\u0201\u0204\5(\25\2\u0202\u0204\5"+
		"\u00a8U\2\u0203\u0201\3\2\2\2\u0203\u0202\3\2\2\2\u0204\u0222\3\2\2\2"+
		"\u0205\u0206\7\\\2\2\u0206\u0222\5\u00aaV\2\u0207\u0208\7]\2\2\u0208\u0222"+
		"\5\u00acW\2\u0209\u020a\7^\2\2\u020a\u0222\5\u00aaV\2\u020b\u020c\7_\2"+
		"\2\u020c\u0222\5(\25\2\u020d\u020e\7_\2\2\u020e\u0222\5\62\32\2\u020f"+
		"\u0210\7_\2\2\u0210\u0222\5\u00aaV\2\u0211\u0212\7`\2\2\u0212\u0222\5"+
		"\u00aaV\2\u0213\u0214\7a\2\2\u0214\u0222\5\62\32\2\u0215\u0216\7a\2\2"+
		"\u0216\u0222\5\u00aeX\2\u0217\u0218\7b\2\2\u0218\u0222\5\u00b0Y\2\u0219"+
		"\u021a\7b\2\2\u021a\u0222\5\u00b2Z\2\u021b\u021c\7b\2\2\u021c\u0222\5"+
		"\u00b4[\2\u021d\u021e\7b\2\2\u021e\u0222\5\u00b6\\\2\u021f\u0220\7c\2"+
		"\2\u0220\u0222\5(\25\2\u0221\u0171\3\2\2\2\u0221\u0176\3\2\2\2\u0221\u0178"+
		"\3\2\2\2\u0221\u017a\3\2\2\2\u0221\u017f\3\2\2\2\u0221\u0184\3\2\2\2\u0221"+
		"\u0186\3\2\2\2\u0221\u0188\3\2\2\2\u0221\u018a\3\2\2\2\u0221\u018c\3\2"+
		"\2\2\u0221\u0191\3\2\2\2\u0221\u0193\3\2\2\2\u0221\u0195\3\2\2\2\u0221"+
		"\u019a\3\2\2\2\u0221\u019c\3\2\2\2\u0221\u019e\3\2\2\2\u0221\u01a0\3\2"+
		"\2\2\u0221\u01a2\3\2\2\2\u0221\u01a4\3\2\2\2\u0221\u01a6\3\2\2\2\u0221"+
		"\u01a8\3\2\2\2\u0221\u01aa\3\2\2\2\u0221\u01ac\3\2\2\2\u0221\u01b1\3\2"+
		"\2\2\u0221\u01b3\3\2\2\2\u0221\u01b5\3\2\2\2\u0221\u01b7\3\2\2\2\u0221"+
		"\u01b9\3\2\2\2\u0221\u01bb\3\2\2\2\u0221\u01bd\3\2\2\2\u0221\u01bf\3\2"+
		"\2\2\u0221\u01c1\3\2\2\2\u0221\u01c3\3\2\2\2\u0221\u01c5\3\2\2\2\u0221"+
		"\u01c7\3\2\2\2\u0221\u01c9\3\2\2\2\u0221\u01cb\3\2\2\2\u0221\u01cd\3\2"+
		"\2\2\u0221\u01cf\3\2\2\2\u0221\u01d1\3\2\2\2\u0221\u01d3\3\2\2\2\u0221"+
		"\u01d5\3\2\2\2\u0221\u01d7\3\2\2\2\u0221\u01d9\3\2\2\2\u0221\u01de\3\2"+
		"\2\2\u0221\u01e0\3\2\2\2\u0221\u01e2\3\2\2\2\u0221\u01e4\3\2\2\2\u0221"+
		"\u01e6\3\2\2\2\u0221\u01e8\3\2\2\2\u0221\u01ea\3\2\2\2\u0221\u01ec\3\2"+
		"\2\2\u0221\u01ee\3\2\2\2\u0221\u01f0\3\2\2\2\u0221\u01f2\3\2\2\2\u0221"+
		"\u01f4\3\2\2\2\u0221\u01f6\3\2\2\2\u0221\u01f8\3\2\2\2\u0221\u01fa\3\2"+
		"\2\2\u0221\u01fc\3\2\2\2\u0221\u01fe\3\2\2\2\u0221\u0200\3\2\2\2\u0221"+
		"\u0205\3\2\2\2\u0221\u0207\3\2\2\2\u0221\u0209\3\2\2\2\u0221\u020b\3\2"+
		"\2\2\u0221\u020d\3\2\2\2\u0221\u020f\3\2\2\2\u0221\u0211\3\2\2\2\u0221"+
		"\u0213\3\2\2\2\u0221\u0215\3\2\2\2\u0221\u0217\3\2\2\2\u0221\u0219\3\2"+
		"\2\2\u0221\u021b\3\2\2\2\u0221\u021d\3\2\2\2\u0221\u021f\3\2\2\2\u0222"+
		"\31\3\2\2\2\u0223\u0228\5\22\n\2\u0224\u0225\7u\2\2\u0225\u0227\5\22\n"+
		"\2\u0226\u0224\3\2\2\2\u0227\u022a\3\2\2\2\u0228\u0226\3\2\2\2\u0228\u0229"+
		"\3\2\2\2\u0229\33\3\2\2\2\u022a\u0228\3\2\2\2\u022b\u0230\5\24\13\2\u022c"+
		"\u022d\7u\2\2\u022d\u022f\5\24\13\2\u022e\u022c\3\2\2\2\u022f\u0232\3"+
		"\2\2\2\u0230\u022e\3\2\2\2\u0230\u0231\3\2\2\2\u0231\35\3\2\2\2\u0232"+
		"\u0230\3\2\2\2\u0233\u0238\5\26\f\2\u0234\u0235\7u\2\2\u0235\u0237\5\26"+
		"\f\2\u0236\u0234\3\2\2\2\u0237\u023a\3\2\2\2\u0238\u0236\3\2\2\2\u0238"+
		"\u0239\3\2\2\2\u0239\37\3\2\2\2\u023a\u0238\3\2\2\2\u023b\u0240\5\30\r"+
		"\2\u023c\u023d\7u\2\2\u023d\u023f\5\30\r\2\u023e\u023c\3\2\2\2\u023f\u0242"+
		"\3\2\2\2\u0240\u023e\3\2\2\2\u0240\u0241\3\2\2\2\u0241!\3\2\2\2\u0242"+
		"\u0240\3\2\2\2\u0243\u0244\7s\2\2\u0244#\3\2\2\2\u0245\u0247\7u\2\2\u0246"+
		"\u0245\3\2\2\2\u0246\u0247\3\2\2\2\u0247\u0248\3\2\2\2\u0248\u0249\7t"+
		"\2\2\u0249%\3\2\2\2\u024a\u024b\5\"\22\2\u024b\u0250\5\20\t\2\u024c\u024d"+
		"\7u\2\2\u024d\u024f\5\20\t\2\u024e\u024c\3\2\2\2\u024f\u0252\3\2\2\2\u0250"+
		"\u024e\3\2\2\2\u0250\u0251\3\2\2\2\u0251\u0253\3\2\2\2\u0252\u0250\3\2"+
		"\2\2\u0253\u0254\5$\23\2\u0254\'\3\2\2\2\u0255\u0256\5\"\22\2\u0256\u0257"+
		"\5$\23\2\u0257)\3\2\2\2\u0258\u0259\5\"\22\2\u0259\u025e\5\24\13\2\u025a"+
		"\u025b\7u\2\2\u025b\u025d\5\24\13\2\u025c\u025a\3\2\2\2\u025d\u0260\3"+
		"\2\2\2\u025e\u025c\3\2\2\2\u025e\u025f\3\2\2\2\u025f\u0261\3\2\2\2\u0260"+
		"\u025e\3\2\2\2\u0261\u0262\5$\23\2\u0262+\3\2\2\2\u0263\u0264\5\"\22\2"+
		"\u0264\u0265\5\24\13\2\u0265\u0266\5$\23\2\u0266-\3\2\2\2\u0267\u0268"+
		"\5\"\22\2\u0268\u026d\5\26\f\2\u0269\u026a\7u\2\2\u026a\u026c\5\26\f\2"+
		"\u026b\u0269\3\2\2\2\u026c\u026f\3\2\2\2\u026d\u026b\3\2\2\2\u026d\u026e"+
		"\3\2\2\2\u026e\u0270\3\2\2\2\u026f\u026d\3\2\2\2\u0270\u0271\5$\23\2\u0271"+
		"/\3\2\2\2\u0272\u0273\5\"\22\2\u0273\u0274\5\30\r\2\u0274\u0275\5$\23"+
		"\2\u0275\61\3\2\2\2\u0276\u0277\5\"\22\2\u0277\u027c\5\30\r\2\u0278\u0279"+
		"\7u\2\2\u0279\u027b\5\30\r\2\u027a\u0278\3\2\2\2\u027b\u027e\3\2\2\2\u027c"+
		"\u027a\3\2\2\2\u027c\u027d\3\2\2\2\u027d\u027f\3\2\2\2\u027e\u027c\3\2"+
		"\2\2\u027f\u0280\5$\23\2\u0280\63\3\2\2\2\u0281\u0282\5\"\22\2\u0282\u0283"+
		"\5\u00be`\2\u0283\u0284\5$\23\2\u0284\65\3\2\2\2\u0285\u0286\5\"\22\2"+
		"\u0286\u0287\5\u00be`\2\u0287\u0288\7u\2\2\u0288\u0289\5\u00be`\2\u0289"+
		"\u028a\5$\23\2\u028a\67\3\2\2\2\u028b\u028c\5\"\22\2\u028c\u028f\5\u00be"+
		"`\2\u028d\u028e\7u\2\2\u028e\u0290\5\u00be`\2\u028f\u028d\3\2\2\2\u028f"+
		"\u0290\3\2\2\2\u0290\u0291\3\2\2\2\u0291\u0292\5$\23\2\u02929\3\2\2\2"+
		"\u0293\u0294\5\"\22\2\u0294\u0295\5\u00be`\2\u0295\u0296\7u\2\2\u0296"+
		"\u0297\5\u00bc_\2\u0297\u0298\5$\23\2\u0298;\3\2\2\2\u0299\u029a\5\"\22"+
		"\2\u029a\u029d\5\u00be`\2\u029b\u029c\7u\2\2\u029c\u029e\5\u00bc_\2\u029d"+
		"\u029b\3\2\2\2\u029d\u029e\3\2\2\2\u029e\u029f\3\2\2\2\u029f\u02a0\5$"+
		"\23\2\u02a0=\3\2\2\2\u02a1\u02a2\5\"\22\2\u02a2\u02a3\5\u00be`\2\u02a3"+
		"\u02a4\7u\2\2\u02a4\u02a5\5\u00be`\2\u02a5\u02a6\7u\2\2\u02a6\u02a7\5"+
		"\u00be`\2\u02a7\u02a8\5$\23\2\u02a8?\3\2\2\2\u02a9\u02aa\5\"\22\2\u02aa"+
		"\u02ab\5\u00be`\2\u02ab\u02ac\5$\23\2\u02acA\3\2\2\2\u02ad\u02ae\5\"\22"+
		"\2\u02ae\u02af\5\u00bc_\2\u02af\u02b0\5$\23\2\u02b0C\3\2\2\2\u02b1\u02b2"+
		"\5\"\22\2\u02b2\u02b3\5\u00be`\2\u02b3\u02b4\7u\2\2\u02b4\u02b5\5\u00be"+
		"`\2\u02b5\u02b6\5$\23\2\u02b6E\3\2\2\2\u02b7\u02b8\5\"\22\2\u02b8\u02b9"+
		"\5\u00bc_\2\u02b9\u02ba\5$\23\2\u02baG\3\2\2\2\u02bb\u02bc\5\"\22\2\u02bc"+
		"\u02bd\5\u00be`\2\u02bd\u02be\7u\2\2\u02be\u02bf\5\24\13\2\u02bf\u02c0"+
		"\5$\23\2\u02c0I\3\2\2\2\u02c1\u02c2\5\"\22\2\u02c2\u02c5\5\u00be`\2\u02c3"+
		"\u02c4\7u\2\2\u02c4\u02c6\5\24\13\2\u02c5\u02c3\3\2\2\2\u02c5\u02c6\3"+
		"\2\2\2\u02c6\u02c7\3\2\2\2\u02c7\u02c8\5$\23\2\u02c8K\3\2\2\2\u02c9\u02ca"+
		"\5\"\22\2\u02ca\u02cd\5\u00be`\2\u02cb\u02cc\7u\2\2\u02cc\u02ce\5\26\f"+
		"\2\u02cd\u02cb\3\2\2\2\u02ce\u02cf\3\2\2\2\u02cf\u02cd\3\2\2\2\u02cf\u02d0"+
		"\3\2\2\2\u02d0\u02d1\3\2\2\2\u02d1\u02d2\5$\23\2\u02d2M\3\2\2\2\u02d3"+
		"\u02d4\5\"\22\2\u02d4\u02d9\5\u00be`\2\u02d5\u02d6\7u\2\2\u02d6\u02d8"+
		"\5\30\r\2\u02d7\u02d5\3\2\2\2\u02d8\u02db\3\2\2\2\u02d9\u02d7\3\2\2\2"+
		"\u02d9\u02da\3\2\2\2\u02da\u02dc\3\2\2\2\u02db\u02d9\3\2\2\2\u02dc\u02dd"+
		"\5$\23\2\u02ddO\3\2\2\2\u02de\u02df\5\"\22\2\u02df\u02e0\5\u00be`\2\u02e0"+
		"\u02e1\7u\2\2\u02e1\u02e6\5\24\13\2\u02e2\u02e3\7u\2\2\u02e3\u02e5\5\24"+
		"\13\2\u02e4\u02e2\3\2\2\2\u02e5\u02e8\3\2\2\2\u02e6\u02e4\3\2\2\2\u02e6"+
		"\u02e7\3\2\2\2\u02e7\u02e9\3\2\2\2\u02e8\u02e6\3\2\2\2\u02e9\u02ea\5$"+
		"\23\2\u02eaQ\3\2\2\2\u02eb\u02ec\5\"\22\2\u02ec\u02f1\5\24\13\2\u02ed"+
		"\u02ee\7u\2\2\u02ee\u02f0\5\24\13\2\u02ef\u02ed\3\2\2\2\u02f0\u02f3\3"+
		"\2\2\2\u02f1\u02ef\3\2\2\2\u02f1\u02f2\3\2\2\2\u02f2\u02f4\3\2\2\2\u02f3"+
		"\u02f1\3\2\2\2\u02f4\u02f5\5$\23\2\u02f5S\3\2\2\2\u02f6\u0300\5\"\22\2"+
		"\u02f7\u0301\5\u00be`\2\u02f8\u02fd\5\u00be`\2\u02f9\u02fa\7u\2\2\u02fa"+
		"\u02fc\5\24\13\2\u02fb\u02f9\3\2\2\2\u02fc\u02ff\3\2\2\2\u02fd\u02fb\3"+
		"\2\2\2\u02fd\u02fe\3\2\2\2\u02fe\u0301\3\2\2\2\u02ff\u02fd\3\2\2\2\u0300"+
		"\u02f7\3\2\2\2\u0300\u02f8\3\2\2\2\u0301\u0302\3\2\2\2\u0302\u0303\5$"+
		"\23\2\u0303U\3\2\2\2\u0304\u0305\5\"\22\2\u0305\u030a\5\24\13\2\u0306"+
		"\u0307\7u\2\2\u0307\u0309\5\24\13\2\u0308\u0306\3\2\2\2\u0309\u030c\3"+
		"\2\2\2\u030a\u0308\3\2\2\2\u030a\u030b\3\2\2\2\u030b\u030d\3\2\2\2\u030c"+
		"\u030a\3\2\2\2\u030d\u030e\5$\23\2\u030eW\3\2\2\2\u030f\u0310\5\"\22\2"+
		"\u0310\u0311\5\u00be`\2\u0311\u0312\7u\2\2\u0312\u0313\5\u00bc_\2\u0313"+
		"\u0314\5$\23\2\u0314Y\3\2\2\2\u0315\u0316\5\"\22\2\u0316\u0317\5\u00be"+
		"`\2\u0317\u0318\7u\2\2\u0318\u0319\5\u00be`\2\u0319\u031a\5$\23\2\u031a"+
		"[\3\2\2\2\u031b\u031c\5\"\22\2\u031c\u031d\5\u00be`\2\u031d\u031e\7u\2"+
		"\2\u031e\u031f\5\u00be`\2\u031f\u0320\5$\23\2\u0320]\3\2\2\2\u0321\u0322"+
		"\5\"\22\2\u0322\u0325\5\u00be`\2\u0323\u0324\7u\2\2\u0324\u0326\5\u00bc"+
		"_\2\u0325\u0323\3\2\2\2\u0325\u0326\3\2\2\2\u0326\u0327\3\2\2\2\u0327"+
		"\u0328\5$\23\2\u0328_\3\2\2\2\u0329\u032d\5\"\22\2\u032a\u032b\5\u00be"+
		"`\2\u032b\u032c\7u\2\2\u032c\u032e\3\2\2\2\u032d\u032a\3\2\2\2\u032d\u032e"+
		"\3\2\2\2\u032e\u032f\3\2\2\2\u032f\u0332\5\u00be`\2\u0330\u0331\7u\2\2"+
		"\u0331\u0333\5\30\r\2\u0332\u0330\3\2\2\2\u0332\u0333\3\2\2\2\u0333\u0334"+
		"\3\2\2\2\u0334\u0335\5$\23\2\u0335a\3\2\2\2\u0336\u033a\5\"\22\2\u0337"+
		"\u0338\5\u00be`\2\u0338\u0339\7u\2\2\u0339\u033b\3\2\2\2\u033a\u0337\3"+
		"\2\2\2\u033a\u033b\3\2\2\2\u033b\u033c\3\2\2\2\u033c\u033d\5\u00be`\2"+
		"\u033d\u033e\7u\2\2\u033e\u033f\5\30\r\2\u033f\u0340\7u\2\2\u0340\u0341"+
		"\5\30\r\2\u0341\u0342\5$\23\2\u0342c\3\2\2\2\u0343\u0347\5\"\22\2\u0344"+
		"\u0345\5\u00be`\2\u0345\u0346\7u\2\2\u0346\u0348\3\2\2\2\u0347\u0344\3"+
		"\2\2\2\u0347\u0348\3\2\2\2\u0348\u0349\3\2\2\2\u0349\u034a\5\u00be`\2"+
		"\u034a\u034b\7u\2\2\u034b\u034e\5\24\13\2\u034c\u034d\7u\2\2\u034d\u034f"+
		"\5\30\r\2\u034e\u034c\3\2\2\2\u034e\u034f\3\2\2\2\u034f\u0350\3\2\2\2"+
		"\u0350\u0351\5$\23\2\u0351e\3\2\2\2\u0352\u0356\5\"\22\2\u0353\u0354\5"+
		"\u00be`\2\u0354\u0355\7u\2\2\u0355\u0357\3\2\2\2\u0356\u0353\3\2\2\2\u0356"+
		"\u0357\3\2\2\2\u0357\u0358\3\2\2\2\u0358\u0359\5\u00be`\2\u0359\u035a"+
		"\7u\2\2\u035a\u035b\5\24\13\2\u035b\u035c\7u\2\2\u035c\u035d\5\30\r\2"+
		"\u035d\u035e\7u\2\2\u035e\u035f\5\30\r\2\u035f\u0360\5$\23\2\u0360g\3"+
		"\2\2\2\u0361\u0365\5\"\22\2\u0362\u0363\5\u00be`\2\u0363\u0364\7u\2\2"+
		"\u0364\u0366\3\2\2\2\u0365\u0362\3\2\2\2\u0365\u0366\3\2\2\2\u0366\u0367"+
		"\3\2\2\2\u0367\u0368\5\u00be`\2\u0368\u0369\7u\2\2\u0369\u036c\5\26\f"+
		"\2\u036a\u036b\7u\2\2\u036b\u036d\5\30\r\2\u036c\u036a\3\2\2\2\u036c\u036d"+
		"\3\2\2\2\u036d\u036e\3\2\2\2\u036e\u036f\5$\23\2\u036fi\3\2\2\2\u0370"+
		"\u0374\5\"\22\2\u0371\u0372\5\u00be`\2\u0372\u0373\7u\2\2\u0373\u0375"+
		"\3\2\2\2\u0374\u0371\3\2\2\2\u0374\u0375\3\2\2\2\u0375\u0376\3\2\2\2\u0376"+
		"\u0377\5\u00be`\2\u0377\u0378\7u\2\2\u0378\u0379\5\26\f\2\u0379\u037a"+
		"\7u\2\2\u037a\u037b\5\30\r\2\u037b\u037c\7u\2\2\u037c\u037d\5\30\r\2\u037d"+
		"\u037e\5$\23\2\u037ek\3\2\2\2\u037f\u0383\5\"\22\2\u0380\u0381\5\u00be"+
		"`\2\u0381\u0382\7u\2\2\u0382\u0384\3\2\2\2\u0383\u0380\3\2\2\2\u0383\u0384"+
		"\3\2\2\2\u0384\u0385\3\2\2\2\u0385\u0386\5\u00be`\2\u0386\u0387\7u\2\2"+
		"\u0387\u0388\5\24\13\2\u0388\u0389\7u\2\2\u0389\u038c\5\26\f\2\u038a\u038b"+
		"\7u\2\2\u038b\u038d\5\30\r\2\u038c\u038a\3\2\2\2\u038c\u038d\3\2\2\2\u038d"+
		"\u038e\3\2\2\2\u038e\u038f\5$\23\2\u038fm\3\2\2\2\u0390\u0394\5\"\22\2"+
		"\u0391\u0392\5\u00be`\2\u0392\u0393\7u\2\2\u0393\u0395\3\2\2\2\u0394\u0391"+
		"\3\2\2\2\u0394\u0395\3\2\2\2\u0395\u0396\3\2\2\2\u0396\u0397\5\u00be`"+
		"\2\u0397\u0398\7u\2\2\u0398\u0399\5\24\13\2\u0399\u039a\7u\2\2\u039a\u039b"+
		"\5\26\f\2\u039b\u039c\7u\2\2\u039c\u039d\5\30\r\2\u039d\u039e\7u\2\2\u039e"+
		"\u039f\5\30\r\2\u039f\u03a0\5$\23\2\u03a0o\3\2\2\2\u03a1\u03a5\5\"\22"+
		"\2\u03a2\u03a3\5\u00be`\2\u03a3\u03a4\7u\2\2\u03a4\u03a6\3\2\2\2\u03a5"+
		"\u03a2\3\2\2\2\u03a5\u03a6\3\2\2\2\u03a6\u03a7\3\2\2\2\u03a7\u03a8\5\u00be"+
		"`\2\u03a8\u03a9\7u\2\2\u03a9\u03aa\5\30\r\2\u03aa\u03ab\7u\2\2\u03ab\u03ac"+
		"\5\30\r\2\u03ac\u03ad\5$\23\2\u03adq\3\2\2\2\u03ae\u03b2\5\"\22\2\u03af"+
		"\u03b0\5\u00be`\2\u03b0\u03b1\7u\2\2\u03b1\u03b3\3\2\2\2\u03b2\u03af\3"+
		"\2\2\2\u03b2\u03b3\3\2\2\2\u03b3\u03b4\3\2\2\2\u03b4\u03b5\5\u00be`\2"+
		"\u03b5\u03b6\7u\2\2\u03b6\u03b7\5\30\r\2\u03b7\u03b8\7u\2\2\u03b8\u03b9"+
		"\5\30\r\2\u03b9\u03ba\7u\2\2\u03ba\u03bb\5\30\r\2\u03bb\u03bc\5$\23\2"+
		"\u03bcs\3\2\2\2\u03bd\u03c1\5\"\22\2\u03be\u03bf\5\u00be`\2\u03bf\u03c0"+
		"\7u\2\2\u03c0\u03c2\3\2\2\2\u03c1\u03be\3\2\2\2\u03c1\u03c2\3\2\2\2\u03c2"+
		"\u03c3\3\2\2\2\u03c3\u03c4\5\u00be`\2\u03c4\u03c5\7u\2\2\u03c5\u03c6\5"+
		"\24\13\2\u03c6\u03c7\7u\2\2\u03c7\u03c8\5\30\r\2\u03c8\u03c9\7u\2\2\u03c9"+
		"\u03ca\5\30\r\2\u03ca\u03cb\5$\23\2\u03cbu\3\2\2\2\u03cc\u03d0\5\"\22"+
		"\2\u03cd\u03ce\5\u00be`\2\u03ce\u03cf\7u\2\2\u03cf\u03d1\3\2\2\2\u03d0"+
		"\u03cd\3\2\2\2\u03d0\u03d1\3\2\2\2\u03d1\u03d2\3\2\2\2\u03d2\u03d3\5\u00be"+
		"`\2\u03d3\u03d4\7u\2\2\u03d4\u03d5\5\24\13\2\u03d5\u03d6\7u\2\2\u03d6"+
		"\u03d7\5\30\r\2\u03d7\u03d8\7u\2\2\u03d8\u03d9\5\30\r\2\u03d9\u03da\7"+
		"u\2\2\u03da\u03db\5\30\r\2\u03db\u03dc\5$\23\2\u03dcw\3\2\2\2\u03dd\u03e1"+
		"\5\"\22\2\u03de\u03df\5\u00be`\2\u03df\u03e0\7u\2\2\u03e0\u03e2\3\2\2"+
		"\2\u03e1\u03de\3\2\2\2\u03e1\u03e2\3\2\2\2\u03e2\u03e3\3\2\2\2\u03e3\u03e4"+
		"\5\u00be`\2\u03e4\u03e5\7u\2\2\u03e5\u03e6\5\26\f\2\u03e6\u03e7\7u\2\2"+
		"\u03e7\u03e8\5\30\r\2\u03e8\u03e9\7u\2\2\u03e9\u03ea\5\30\r\2\u03ea\u03eb"+
		"\5$\23\2\u03eby\3\2\2\2\u03ec\u03f0\5\"\22\2\u03ed\u03ee\5\u00be`\2\u03ee"+
		"\u03ef\7u\2\2\u03ef\u03f1\3\2\2\2\u03f0\u03ed\3\2\2\2\u03f0\u03f1\3\2"+
		"\2\2\u03f1\u03f2\3\2\2\2\u03f2\u03f3\5\u00be`\2\u03f3\u03f4\7u\2\2\u03f4"+
		"\u03f5\5\26\f\2\u03f5\u03f6\7u\2\2\u03f6\u03f7\5\30\r\2\u03f7\u03f8\7"+
		"u\2\2\u03f8\u03f9\5\30\r\2\u03f9\u03fa\7u\2\2\u03fa\u03fb\5\30\r\2\u03fb"+
		"\u03fc\5$\23\2\u03fc{\3\2\2\2\u03fd\u0401\5\"\22\2\u03fe\u03ff\5\u00be"+
		"`\2\u03ff\u0400\7u\2\2\u0400\u0402\3\2\2\2\u0401\u03fe\3\2\2\2\u0401\u0402"+
		"\3\2\2\2\u0402\u0403\3\2\2\2\u0403\u0404\5\u00be`\2\u0404\u0405\7u\2\2"+
		"\u0405\u0406\5\24\13\2\u0406\u0407\7u\2\2\u0407\u0408\5\26\f\2\u0408\u0409"+
		"\7u\2\2\u0409\u040a\5\30\r\2\u040a\u040b\7u\2\2\u040b\u040c\5\30\r\2\u040c"+
		"\u040d\5$\23\2\u040d}\3\2\2\2\u040e\u0412\5\"\22\2\u040f\u0410\5\u00be"+
		"`\2\u0410\u0411\7u\2\2\u0411\u0413\3\2\2\2\u0412\u040f\3\2\2\2\u0412\u0413"+
		"\3\2\2\2\u0413\u0414\3\2\2\2\u0414\u0415\5\u00be`\2\u0415\u0416\7u\2\2"+
		"\u0416\u0417\5\24\13\2\u0417\u0418\7u\2\2\u0418\u0419\5\26\f\2\u0419\u041a"+
		"\7u\2\2\u041a\u041b\5\30\r\2\u041b\u041c\7u\2\2\u041c\u041d\5\30\r\2\u041d"+
		"\u041e\7u\2\2\u041e\u041f\5\30\r\2\u041f\u0420\5$\23\2\u0420\177\3\2\2"+
		"\2\u0421\u0437\5\"\22\2\u0422\u0423\5\u00be`\2\u0423\u0424\7u\2\2\u0424"+
		"\u0426\3\2\2\2\u0425\u0422\3\2\2\2\u0425\u0426\3\2\2\2\u0426\u0427\3\2"+
		"\2\2\u0427\u042a\5\u00bc_\2\u0428\u0429\7u\2\2\u0429\u042b\5\30\r\2\u042a"+
		"\u0428\3\2\2\2\u042a\u042b\3\2\2\2\u042b\u0438\3\2\2\2\u042c\u042d\5\u00be"+
		"`\2\u042d\u042e\7u\2\2\u042e\u0430\3\2\2\2\u042f\u042c\3\2\2\2\u042f\u0430"+
		"\3\2\2\2\u0430\u0431\3\2\2\2\u0431\u0432\5\u00bc_\2\u0432\u0433\7u\2\2"+
		"\u0433\u0434\5\30\r\2\u0434\u0435\7u\2\2\u0435\u0436\5\30\r\2\u0436\u0438"+
		"\3\2\2\2\u0437\u0425\3\2\2\2\u0437\u042f\3\2\2\2\u0438\u0439\3\2\2\2\u0439"+
		"\u043a\5$\23\2\u043a\u0081\3\2\2\2\u043b\u044c\5\"\22\2\u043c\u044d\5"+
		"\u00be`\2\u043d\u043e\5\u00be`\2\u043e\u043f\7u\2\2\u043f\u0441\3\2\2"+
		"\2\u0440\u043d\3\2\2\2\u0440\u0441\3\2\2\2\u0441\u0442\3\2\2\2\u0442\u044d"+
		"\5\30\r\2\u0443\u0444\5\u00be`\2\u0444\u0445\7u\2\2\u0445\u0447\3\2\2"+
		"\2\u0446\u0443\3\2\2\2\u0446\u0447\3\2\2\2\u0447\u0448\3\2\2\2\u0448\u0449"+
		"\5\30\r\2\u0449\u044a\7u\2\2\u044a\u044b\5\30\r\2\u044b\u044d\3\2\2\2"+
		"\u044c\u043c\3\2\2\2\u044c\u0440\3\2\2\2\u044c\u0446\3\2\2\2\u044d\u044e"+
		"\3\2\2\2\u044e\u044f\5$\23\2\u044f\u0083\3\2\2\2\u0450\u0458\5\"\22\2"+
		"\u0451\u0459\5\u00be`\2\u0452\u0453\5\u00be`\2\u0453\u0454\7u\2\2\u0454"+
		"\u0456\3\2\2\2\u0455\u0452\3\2\2\2\u0455\u0456\3\2\2\2\u0456\u0457\3\2"+
		"\2\2\u0457\u0459\5\30\r\2\u0458\u0451\3\2\2\2\u0458\u0455\3\2\2\2\u0459"+
		"\u045a\3\2\2\2\u045a\u045b\5$\23\2\u045b\u0085\3\2\2\2\u045c\u0460\5\""+
		"\22\2\u045d\u045e\5\u00be`\2\u045e\u045f\7u\2\2\u045f\u0461\3\2\2\2\u0460"+
		"\u045d\3\2\2\2\u0460\u0461\3\2\2\2\u0461\u0462\3\2\2\2\u0462\u0463\5\30"+
		"\r\2\u0463\u0464\7u\2\2\u0464\u0465\5\30\r\2\u0465\u0466\5$\23\2\u0466"+
		"\u0087\3\2\2\2\u0467\u046b\5\"\22\2\u0468\u0469\5\u00be`\2\u0469\u046a"+
		"\7u\2\2\u046a\u046c\3\2\2\2\u046b\u0468\3\2\2\2\u046b\u046c\3\2\2\2\u046c"+
		"\u046d\3\2\2\2\u046d\u046e\5\30\r\2\u046e\u046f\7u\2\2\u046f\u0470\5\30"+
		"\r\2\u0470\u0471\7u\2\2\u0471\u0472\5\30\r\2\u0472\u0473\5$\23\2\u0473"+
		"\u0089\3\2\2\2\u0474\u0475\5\"\22\2\u0475\u0476\5\30\r\2\u0476\u0477\5"+
		"$\23\2\u0477\u008b\3\2\2\2\u0478\u0479\5\"\22\2\u0479\u047a\5\30\r\2\u047a"+
		"\u047b\7u\2\2\u047b\u047c\5\30\r\2\u047c\u047d\5$\23\2\u047d\u008d\3\2"+
		"\2\2\u047e\u047f\5\"\22\2\u047f\u0480\5\u00be`\2\u0480\u0481\5$\23\2\u0481"+
		"\u008f\3\2\2\2\u0482\u0483\5\"\22\2\u0483\u0484\5\u00be`\2\u0484\u0485"+
		"\7u\2\2\u0485\u0488\5\u00a0Q\2\u0486\u0487\7u\2\2\u0487\u0489\5\u00a2"+
		"R\2\u0488\u0486\3\2\2\2\u0488\u0489\3\2\2\2\u0489\u048c\3\2\2\2\u048a"+
		"\u048b\7u\2\2\u048b\u048d\5\u009eP\2\u048c\u048a\3\2\2\2\u048c\u048d\3"+
		"\2\2\2\u048d\u048e\3\2\2\2\u048e\u048f\5$\23\2\u048f\u0091\3\2\2\2\u0490"+
		"\u0491\5\"\22\2\u0491\u0492\5\u00be`\2\u0492\u0493\7u\2\2\u0493\u0496"+
		"\5\u00a2R\2\u0494\u0495\7u\2\2\u0495\u0497\5\u009eP\2\u0496\u0494\3\2"+
		"\2\2\u0496\u0497\3\2\2\2\u0497\u0498\3\2\2\2\u0498\u0499\5$\23\2\u0499"+
		"\u0093\3\2\2\2\u049a\u049b\5\"\22\2\u049b\u049c\5\u00be`\2\u049c\u049d"+
		"\7u\2\2\u049d\u049e\5\u009eP\2\u049e\u049f\5$\23\2\u049f\u0095\3\2\2\2"+
		"\u04a0\u04a1\5\"\22\2\u04a1\u04a4\5\u00a0Q\2\u04a2\u04a3\7u\2\2\u04a3"+
		"\u04a5\5\u00a2R\2\u04a4\u04a2\3\2\2\2\u04a4\u04a5\3\2\2\2\u04a5\u04a8"+
		"\3\2\2\2\u04a6\u04a7\7u\2\2\u04a7\u04a9\5\u009eP\2\u04a8\u04a6\3\2\2\2"+
		"\u04a8\u04a9\3\2\2\2\u04a9\u04aa\3\2\2\2\u04aa\u04ab\5$\23\2\u04ab\u0097"+
		"\3\2\2\2\u04ac\u04ad\5\"\22\2\u04ad\u04b0\5\u00a2R\2\u04ae\u04af\7u\2"+
		"\2\u04af\u04b1\5\u009eP\2\u04b0\u04ae\3\2\2\2\u04b0\u04b1\3\2\2\2\u04b1"+
		"\u04b2\3\2\2\2\u04b2\u04b3\5$\23\2\u04b3\u0099\3\2\2\2\u04b4\u04b5\5\""+
		"\22\2\u04b5\u04b6\5\u009eP\2\u04b6\u04b7\5$\23\2\u04b7\u009b\3\2\2\2\u04b8"+
		"\u04b9\5\"\22\2\u04b9\u04bc\5\u00be`\2\u04ba\u04bb\7u\2\2\u04bb\u04bd"+
		"\5\u00be`\2\u04bc\u04ba\3\2\2\2\u04bc\u04bd\3\2\2\2\u04bd\u04c0\3\2\2"+
		"\2\u04be\u04bf\7u\2\2\u04bf\u04c1\5\u00a0Q\2\u04c0\u04be\3\2\2\2\u04c0"+
		"\u04c1\3\2\2\2\u04c1\u04c4\3\2\2\2\u04c2\u04c3\7u\2\2\u04c3\u04c5\5\u00a2"+
		"R\2\u04c4\u04c2\3\2\2\2\u04c4\u04c5\3\2\2\2\u04c5\u04c8\3\2\2\2\u04c6"+
		"\u04c7\7u\2\2\u04c7\u04c9\5\u009eP\2\u04c8\u04c6\3\2\2\2\u04c8\u04c9\3"+
		"\2\2\2\u04c9\u04ca\3\2\2\2\u04ca\u04cb\5$\23\2\u04cb\u009d\3\2\2\2\u04cc"+
		"\u04d2\5\30\r\2\u04cd\u04ce\5\30\r\2\u04ce\u04cf\7u\2\2\u04cf\u04d0\5"+
		"\30\r\2\u04d0\u04d2\3\2\2\2\u04d1\u04cc\3\2\2\2\u04d1\u04cd\3\2\2\2\u04d2"+
		"\u009f\3\2\2\2\u04d3\u04d9\5\24\13\2\u04d4\u04d5\5\24\13\2\u04d5\u04d6"+
		"\7u\2\2\u04d6\u04d7\5\24\13\2\u04d7\u04d9\3\2\2\2\u04d8\u04d3\3\2\2\2"+
		"\u04d8\u04d4\3\2\2\2\u04d9\u00a1\3\2\2\2\u04da\u04e0\5\26\f\2\u04db\u04dc"+
		"\5\26\f\2\u04dc\u04dd\7u\2\2\u04dd\u04de\5\26\f\2\u04de\u04e0\3\2\2\2"+
		"\u04df\u04da\3\2\2\2\u04df\u04db\3\2\2\2\u04e0\u00a3\3\2\2\2\u04e1\u04e2"+
		"\5\"\22\2\u04e2\u04e3\5\u00be`\2\u04e3\u04e4\7u\2\2\u04e4\u04e5\5\u00bc"+
		"_\2\u04e5\u04e6\5$\23\2\u04e6\u00a5\3\2\2\2\u04e7\u04e8\5\"\22\2\u04e8"+
		"\u04eb\5\u00be`\2\u04e9\u04ea\7u\2\2\u04ea\u04ec\5\u00be`\2\u04eb\u04e9"+
		"\3\2\2\2\u04eb\u04ec\3\2\2\2\u04ec\u04ed\3\2\2\2\u04ed\u04ee\5$\23\2\u04ee"+
		"\u00a7\3\2\2\2\u04ef\u04f0\5\"\22\2\u04f0\u04f1\5\u00bc_\2\u04f1\u04f2"+
		"\5$\23\2\u04f2\u00a9\3\2\2\2\u04f3\u04f4\5\"\22\2\u04f4\u04f9\5\u00be"+
		"`\2\u04f5\u04f6\7u\2\2\u04f6\u04f8\5\30\r\2\u04f7\u04f5\3\2\2\2\u04f8"+
		"\u04fb\3\2\2\2\u04f9\u04f7\3\2\2\2\u04f9\u04fa\3\2\2\2\u04fa\u04fc\3\2"+
		"\2\2\u04fb\u04f9\3\2\2\2\u04fc\u04fd\5$\23\2\u04fd\u00ab\3\2\2\2\u04fe"+
		"\u04ff\5\"\22\2\u04ff\u0500\5\u00be`\2\u0500\u0501\7u\2\2\u0501\u0506"+
		"\5\30\r\2\u0502\u0503\7u\2\2\u0503\u0505\5\30\r\2\u0504\u0502\3\2\2\2"+
		"\u0505\u0508\3\2\2\2\u0506\u0504\3\2\2\2\u0506\u0507\3\2\2\2\u0507\u0509"+
		"\3\2\2\2\u0508\u0506\3\2\2\2\u0509\u050a\5$\23\2\u050a\u00ad\3\2\2\2\u050b"+
		"\u050c\5\"\22\2\u050c\u050f\5\26\f\2\u050d\u050e\7u\2\2\u050e\u0510\5"+
		"\30\r\2\u050f\u050d\3\2\2\2\u0510\u0511\3\2\2\2\u0511\u050f\3\2\2\2\u0511"+
		"\u0512\3\2\2\2\u0512\u0513\3\2\2\2\u0513\u0514\5$\23\2\u0514\u00af\3\2"+
		"\2\2\u0515\u0516\5\"\22\2\u0516\u0519\5\u00be`\2\u0517\u0518\7u\2\2\u0518"+
		"\u051a\5\30\r\2\u0519\u0517\3\2\2\2\u051a\u051b\3\2\2\2\u051b\u0519\3"+
		"\2\2\2\u051b\u051c\3\2\2\2\u051c\u051d\3\2\2\2\u051d\u051e\5$\23\2\u051e"+
		"\u00b1\3\2\2\2\u051f\u0520\5\"\22\2\u0520\u0521\5\u00be`\2\u0521\u0522"+
		"\7u\2\2\u0522\u0525\5\u00be`\2\u0523\u0524\7u\2\2\u0524\u0526\5\30\r\2"+
		"\u0525\u0523\3\2\2\2\u0526\u0527\3\2\2\2\u0527\u0525\3\2\2\2\u0527\u0528"+
		"\3\2\2\2\u0528\u0529\3\2\2\2\u0529\u052a\5$\23\2\u052a\u00b3\3\2\2\2\u052b"+
		"\u052c\5\"\22\2\u052c\u052d\5\u00be`\2\u052d\u052e\7u\2\2\u052e\u0531"+
		"\5\26\f\2\u052f\u0530\7u\2\2\u0530\u0532\5\30\r\2\u0531\u052f\3\2\2\2"+
		"\u0532\u0533\3\2\2\2\u0533\u0531\3\2\2\2\u0533\u0534\3\2\2\2\u0534\u0535"+
		"\3\2\2\2\u0535\u0536\5$\23\2\u0536\u00b5\3\2\2\2\u0537\u0538\5\"\22\2"+
		"\u0538\u0539\5\u00be`\2\u0539\u053a\7u\2\2\u053a\u053b\5\u00be`\2\u053b"+
		"\u053c\7u\2\2\u053c\u053f\5\26\f\2\u053d\u053e\7u\2\2\u053e\u0540\5\30"+
		"\r\2\u053f\u053d\3\2\2\2\u0540\u0541\3\2\2\2\u0541\u053f\3\2\2\2\u0541"+
		"\u0542\3\2\2\2\u0542\u0543\3\2\2\2\u0543\u0544\5$\23\2\u0544\u00b7\3\2"+
		"\2\2\u0545\u0546\7d\2\2\u0546\u00b9\3\2\2\2\u0547\u0548\7e\2\2\u0548\u00bb"+
		"\3\2\2\2\u0549\u0554\5\u00b8]\2\u054a\u0554\5\u00ba^\2\u054b\u0550\5\u00be"+
		"`\2\u054c\u054d\7u\2\2\u054d\u054f\5\u00be`\2\u054e\u054c\3\2\2\2\u054f"+
		"\u0552\3\2\2\2\u0550\u054e\3\2\2\2\u0550\u0551\3\2\2\2\u0551\u0554\3\2"+
		"\2\2\u0552\u0550\3\2\2\2\u0553\u0549\3\2\2\2\u0553\u054a\3\2\2\2\u0553"+
		"\u054b\3\2\2\2\u0554\u00bd\3\2\2\2\u0555\u0565\5\u00b8]\2\u0556\u0565"+
		"\5\u00ba^\2\u0557\u0565\7f\2\2\u0558\u0565\7g\2\2\u0559\u0565\7h\2\2\u055a"+
		"\u0565\7i\2\2\u055b\u0565\7j\2\2\u055c\u0565\7k\2\2\u055d\u0565\7l\2\2"+
		"\u055e\u0565\7m\2\2\u055f\u0565\7n\2\2\u0560\u0565\7o\2\2\u0561\u0565"+
		"\7p\2\2\u0562\u0565\7q\2\2\u0563\u0565\7r\2\2\u0564\u0555\3\2\2\2\u0564"+
		"\u0556\3\2\2\2\u0564\u0557\3\2\2\2\u0564\u0558\3\2\2\2\u0564\u0559\3\2"+
		"\2\2\u0564\u055a\3\2\2\2\u0564\u055b\3\2\2\2\u0564\u055c\3\2\2\2\u0564"+
		"\u055d\3\2\2\2\u0564\u055e\3\2\2\2\u0564\u055f\3\2\2\2\u0564\u0560\3\2"+
		"\2\2\u0564\u0561\3\2\2\2\u0564\u0562\3\2\2\2\u0564\u0563\3\2\2\2\u0565"+
		"\u00bf\3\2\2\2d\u00d9\u00e5\u00ea\u00f1\u0118\u0121\u012e\u0139\u0145"+
		"\u014a\u014f\u015a\u0165\u016f\u0174\u017d\u0182\u018f\u0198\u01af\u01dc"+
		"\u0203\u0221\u0228\u0230\u0238\u0240\u0246\u0250\u025e\u026d\u027c\u028f"+
		"\u029d\u02c5\u02cf\u02d9\u02e6\u02f1\u02fd\u0300\u030a\u0325\u032d\u0332"+
		"\u033a\u0347\u034e\u0356\u0365\u036c\u0374\u0383\u038c\u0394\u03a5\u03b2"+
		"\u03c1\u03d0\u03e1\u03f0\u0401\u0412\u0425\u042a\u042f\u0437\u0440\u0446"+
		"\u044c\u0455\u0458\u0460\u046b\u0488\u048c\u0496\u04a4\u04a8\u04b0\u04bc"+
		"\u04c0\u04c4\u04c8\u04d1\u04d8\u04df\u04eb\u04f9\u0506\u0511\u051b\u0527"+
		"\u0533\u0541\u0550\u0553\u0564";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
		_decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
		for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
			_decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
		}
	}
}