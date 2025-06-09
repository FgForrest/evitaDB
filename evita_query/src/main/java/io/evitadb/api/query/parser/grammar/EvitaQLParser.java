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
		T__94=95, T__95=96, T__96=97, T__97=98, T__98=99, T__99=100, T__100=101,
		T__101=102, T__102=103, T__103=104, T__104=105, T__105=106, T__106=107,
		T__107=108, T__108=109, T__109=110, T__110=111, T__111=112, T__112=113,
		T__113=114, T__114=115, T__115=116, POSITIONAL_PARAMETER=117, NAMED_PARAMETER=118,
		STRING=119, INT=120, FLOAT=121, BOOLEAN=122, DATE=123, TIME=124, DATE_TIME=125,
		OFFSET_DATE_TIME=126, FLOAT_NUMBER_RANGE=127, INT_NUMBER_RANGE=128, DATE_TIME_RANGE=129,
		UUID=130, ENUM=131, ARGS_OPENING=132, ARGS_CLOSING=133, ARGS_DELIMITER=134,
		COMMENT=135, WHITESPACE=136, UNEXPECTED_CHAR=137;
	public static final int
		RULE_queryUnit = 0, RULE_headConstraintListUnit = 1, RULE_filterConstraintListUnit = 2,
		RULE_orderConstraintListUnit = 3, RULE_requireConstraintListUnit = 4,
		RULE_valueTokenUnit = 5, RULE_query = 6, RULE_constraint = 7, RULE_headConstraint = 8,
		RULE_filterConstraint = 9, RULE_orderConstraint = 10, RULE_requireConstraint = 11,
		RULE_headConstraintList = 12, RULE_filterConstraintList = 13, RULE_orderConstraintList = 14,
		RULE_requireConstraintList = 15, RULE_argsOpening = 16, RULE_argsClosing = 17,
		RULE_constraintListArgs = 18, RULE_emptyArgs = 19, RULE_headConstraintListArgs = 20,
		RULE_filterConstraintListArgs = 21, RULE_filterConstraintArgs = 22, RULE_traverseOrderConstraintListArgs = 23,
		RULE_orderConstraintListArgs = 24, RULE_requireConstraintArgs = 25, RULE_requireConstraintListArgs = 26,
		RULE_classifierArgs = 27, RULE_classifierWithValueArgs = 28, RULE_classifierWithOptionalValueArgs = 29,
		RULE_classifierWithValueListArgs = 30, RULE_classifierWithOptionalValueListArgs = 31,
		RULE_classifierWithBetweenValuesArgs = 32, RULE_valueArgs = 33, RULE_valueListArgs = 34,
		RULE_betweenValuesArgs = 35, RULE_classifierListArgs = 36, RULE_classifierWithFilterConstraintArgs = 37,
		RULE_classifierWithTwoFilterConstraintArgs = 38, RULE_facetGroupRelationArgs = 39,
		RULE_facetCalculationRulesArgs = 40, RULE_classifierWithOrderConstraintListArgs = 41,
		RULE_hierarchyWithinConstraintArgs = 42, RULE_hierarchyWithinSelfConstraintArgs = 43,
		RULE_hierarchyWithinRootConstraintArgs = 44, RULE_hierarchyWithinRootSelfConstraintArgs = 45,
		RULE_attributeSetExactArgs = 46, RULE_pageConstraintArgs = 47, RULE_stripConstraintArgs = 48,
		RULE_priceContentArgs = 49, RULE_singleRefReferenceContent1Args = 50,
		RULE_singleRefReferenceContent2Args = 51, RULE_singleRefReferenceContent3Args = 52,
		RULE_singleRefReferenceContent4Args = 53, RULE_singleRefReferenceContent5Args = 54,
		RULE_singleRefReferenceContent6Args = 55, RULE_singleRefReferenceContent7Args = 56,
		RULE_singleRefReferenceContent8Args = 57, RULE_singleRefReferenceContentWithAttributes0Args = 58,
		RULE_singleRefReferenceContentWithAttributes1Args = 59, RULE_singleRefReferenceContentWithAttributes2Args = 60,
		RULE_singleRefReferenceContentWithAttributes3Args = 61, RULE_singleRefReferenceContentWithAttributes4Args = 62,
		RULE_singleRefReferenceContentWithAttributes5Args = 63, RULE_singleRefReferenceContentWithAttributes6Args = 64,
		RULE_singleRefReferenceContentWithAttributes7Args = 65, RULE_singleRefReferenceContentWithAttributes8Args = 66,
		RULE_multipleRefsReferenceContentArgs = 67, RULE_allRefsReferenceContentArgs = 68,
		RULE_allRefsWithAttributesReferenceContent1Args = 69, RULE_allRefsWithAttributesReferenceContent2Args = 70,
		RULE_allRefsWithAttributesReferenceContent3Args = 71, RULE_singleRequireHierarchyContentArgs = 72,
		RULE_allRequiresHierarchyContentArgs = 73, RULE_facetSummary1Args = 74,
		RULE_facetSummary2Args = 75, RULE_facetSummary3Args = 76, RULE_facetSummary4Args = 77,
		RULE_facetSummary5Args = 78, RULE_facetSummary6Args = 79, RULE_facetSummary7Args = 80,
		RULE_facetSummaryOfReference2Args = 81, RULE_facetSummaryRequirementsArgs = 82,
		RULE_facetSummaryFilterArgs = 83, RULE_facetSummaryOrderArgs = 84, RULE_attributeHistogramArgs = 85,
		RULE_priceHistogramArgs = 86, RULE_hierarchyStatisticsArgs = 87, RULE_hierarchyRequireConstraintArgs = 88,
		RULE_hierarchyFromNodeArgs = 89, RULE_fullHierarchyOfSelfArgs = 90, RULE_basicHierarchyOfReferenceArgs = 91,
		RULE_basicHierarchyOfReferenceWithBehaviourArgs = 92, RULE_fullHierarchyOfReferenceArgs = 93,
		RULE_fullHierarchyOfReferenceWithBehaviourArgs = 94, RULE_spacingRequireConstraintArgs = 95,
		RULE_gapRequireConstraintArgs = 96, RULE_segmentArgs = 97, RULE_inScopeFilterArgs = 98,
		RULE_inScopeOrderArgs = 99, RULE_inScopeRequireArgs = 100, RULE_positionalParameter = 101,
		RULE_namedParameter = 102, RULE_variadicValueTokens = 103, RULE_valueToken = 104;
	private static String[] makeRuleNames() {
		return new String[] {
			"queryUnit", "headConstraintListUnit", "filterConstraintListUnit", "orderConstraintListUnit",
			"requireConstraintListUnit", "valueTokenUnit", "query", "constraint",
			"headConstraint", "filterConstraint", "orderConstraint", "requireConstraint",
			"headConstraintList", "filterConstraintList", "orderConstraintList",
			"requireConstraintList", "argsOpening", "argsClosing", "constraintListArgs",
			"emptyArgs", "headConstraintListArgs", "filterConstraintListArgs", "filterConstraintArgs",
			"traverseOrderConstraintListArgs", "orderConstraintListArgs", "requireConstraintArgs",
			"requireConstraintListArgs", "classifierArgs", "classifierWithValueArgs",
			"classifierWithOptionalValueArgs", "classifierWithValueListArgs", "classifierWithOptionalValueListArgs",
			"classifierWithBetweenValuesArgs", "valueArgs", "valueListArgs", "betweenValuesArgs",
			"classifierListArgs", "classifierWithFilterConstraintArgs", "classifierWithTwoFilterConstraintArgs",
			"facetGroupRelationArgs", "facetCalculationRulesArgs", "classifierWithOrderConstraintListArgs",
			"hierarchyWithinConstraintArgs", "hierarchyWithinSelfConstraintArgs",
			"hierarchyWithinRootConstraintArgs", "hierarchyWithinRootSelfConstraintArgs",
			"attributeSetExactArgs", "pageConstraintArgs", "stripConstraintArgs",
			"priceContentArgs", "singleRefReferenceContent1Args", "singleRefReferenceContent2Args",
			"singleRefReferenceContent3Args", "singleRefReferenceContent4Args", "singleRefReferenceContent5Args",
			"singleRefReferenceContent6Args", "singleRefReferenceContent7Args", "singleRefReferenceContent8Args",
			"singleRefReferenceContentWithAttributes0Args", "singleRefReferenceContentWithAttributes1Args",
			"singleRefReferenceContentWithAttributes2Args", "singleRefReferenceContentWithAttributes3Args",
			"singleRefReferenceContentWithAttributes4Args", "singleRefReferenceContentWithAttributes5Args",
			"singleRefReferenceContentWithAttributes6Args", "singleRefReferenceContentWithAttributes7Args",
			"singleRefReferenceContentWithAttributes8Args", "multipleRefsReferenceContentArgs",
			"allRefsReferenceContentArgs", "allRefsWithAttributesReferenceContent1Args",
			"allRefsWithAttributesReferenceContent2Args", "allRefsWithAttributesReferenceContent3Args",
			"singleRequireHierarchyContentArgs", "allRequiresHierarchyContentArgs",
			"facetSummary1Args", "facetSummary2Args", "facetSummary3Args", "facetSummary4Args",
			"facetSummary5Args", "facetSummary6Args", "facetSummary7Args", "facetSummaryOfReference2Args",
			"facetSummaryRequirementsArgs", "facetSummaryFilterArgs", "facetSummaryOrderArgs",
			"attributeHistogramArgs", "priceHistogramArgs", "hierarchyStatisticsArgs",
			"hierarchyRequireConstraintArgs", "hierarchyFromNodeArgs", "fullHierarchyOfSelfArgs",
			"basicHierarchyOfReferenceArgs", "basicHierarchyOfReferenceWithBehaviourArgs",
			"fullHierarchyOfReferenceArgs", "fullHierarchyOfReferenceWithBehaviourArgs",
			"spacingRequireConstraintArgs", "gapRequireConstraintArgs", "segmentArgs",
			"inScopeFilterArgs", "inScopeOrderArgs", "inScopeRequireArgs", "positionalParameter",
			"namedParameter", "variadicValueTokens", "valueToken"
		};
	}
	public static final String[] ruleNames = makeRuleNames();

	private static String[] makeLiteralNames() {
		return new String[] {
			null, "'query'", "'head'", "'collection'", "'label'", "'filterBy'", "'filterGroupBy'",
			"'and'", "'or'", "'not'", "'userFilter'", "'attributeEquals'", "'attributeGreaterThan'",
			"'attributeGreaterThanEquals'", "'attributeLessThan'", "'attributeLessThanEquals'",
			"'attributeBetween'", "'attributeInSet'", "'attributeContains'", "'attributeStartsWith'",
			"'attributeEndsWith'", "'attributeEqualsTrue'", "'attributeEqualsFalse'",
			"'attributeIs'", "'attributeIsNull'", "'attributeIsNotNull'", "'attributeInRange'",
			"'attributeInRangeNow'", "'entityPrimaryKeyInSet'", "'entityLocaleEquals'",
			"'priceInCurrency'", "'priceInPriceLists'", "'priceValidInNow'", "'priceValidIn'",
			"'priceBetween'", "'facetHaving'", "'includingChildren'", "'includingChildrenHaving'",
			"'includingChildrenExcept'", "'referenceHaving'", "'hierarchyWithin'",
			"'hierarchyWithinSelf'", "'hierarchyWithinRoot'", "'hierarchyWithinRootSelf'",
			"'directRelation'", "'having'", "'excludingRoot'", "'excluding'", "'entityHaving'",
			"'inScope'", "'scope'", "'orderBy'", "'orderGroupBy'", "'attributeNatural'",
			"'attributeSetExact'", "'attributeSetInFilter'", "'priceNatural'", "'priceDiscount'",
			"'random'", "'randomWithSeed'", "'referenceProperty'", "'traverseByEntityProperty'",
			"'pickFirstByEntityProperty'", "'entityPrimaryKeyNatural'", "'entityPrimaryKeyExact'",
			"'entityPrimaryKeyInFilter'", "'entityProperty'", "'entityGroupProperty'",
			"'segments'", "'segment'", "'limit'", "'require'", "'page'", "'strip'",
			"'entityFetch'", "'entityGroupFetch'", "'attributeContent'", "'attributeContentAll'",
			"'priceContent'", "'priceContentAll'", "'priceContentRespectingFilter'",
			"'associatedDataContent'", "'associatedDataContentAll'", "'referenceContentAll'",
			"'referenceContent'", "'referenceContentAllWithAttributes'", "'referenceContentWithAttributes'",
			"'hierarchyContent'", "'defaultAccompanyingPrice'", "'accompanyingPriceContent'",
			"'priceType'", "'dataInLocalesAll'", "'dataInLocales'", "'facetSummary'",
			"'facetSummaryOfReference'", "'facetGroupsConjunction'", "'facetGroupsDisjunction'",
			"'facetGroupsNegation'", "'facetGroupsExclusivity'", "'facetCalculationRules'",
			"'attributeHistogram'", "'priceHistogram'", "'distance'", "'level'",
			"'node'", "'stopAt'", "'statistics'", "'fromRoot'", "'fromNode'", "'children'",
			"'siblings'", "'spacing'", "'gap'", "'parents'", "'hierarchyOfSelf'",
			"'hierarchyOfReference'", "'queryTelemetry'", "'?'", null, null, null,
			null, null, null, null, null, null, null, null, null, null, null, "'('",
			"')'", "','"
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
			null, null, null, null, null, null, null, null, null, null, null, null,
			null, null, null, null, null, null, null, null, null, "POSITIONAL_PARAMETER",
			"NAMED_PARAMETER", "STRING", "INT", "FLOAT", "BOOLEAN", "DATE", "TIME",
			"DATE_TIME", "OFFSET_DATE_TIME", "FLOAT_NUMBER_RANGE", "INT_NUMBER_RANGE",
			"DATE_TIME_RANGE", "UUID", "ENUM", "ARGS_OPENING", "ARGS_CLOSING", "ARGS_DELIMITER",
			"COMMENT", "WHITESPACE", "UNEXPECTED_CHAR"
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
			setState(210);
			query();
			setState(211);
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
			setState(213);
			headConstraintList();
			setState(214);
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
			setState(216);
			filterConstraintList();
			setState(217);
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
			setState(219);
			orderConstraintList();
			setState(220);
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
			setState(222);
			requireConstraintList();
			setState(223);
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
			setState(225);
			valueToken();
			setState(226);
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
			setState(228);
			match(T__0);
			setState(229);
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
			setState(235);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,0,_ctx) ) {
			case 1:
				enterOuterAlt(_localctx, 1);
				{
				setState(231);
				headConstraint();
				}
				break;
			case 2:
				enterOuterAlt(_localctx, 2);
				{
				setState(232);
				filterConstraint();
				}
				break;
			case 3:
				enterOuterAlt(_localctx, 3);
				{
				setState(233);
				orderConstraint();
				}
				break;
			case 4:
				enterOuterAlt(_localctx, 4);
				{
				setState(234);
				requireConstraint();
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
	public static class HeadContainerConstraintContext extends HeadConstraintContext {
		public HeadConstraintListArgsContext args;
		public HeadConstraintListArgsContext headConstraintListArgs() {
			return getRuleContext(HeadConstraintListArgsContext.class,0);
		}
		public HeadContainerConstraintContext(HeadConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHeadContainerConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHeadContainerConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHeadContainerConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class LabelConstraintContext extends HeadConstraintContext {
		public ClassifierWithValueArgsContext args;
		public ClassifierWithValueArgsContext classifierWithValueArgs() {
			return getRuleContext(ClassifierWithValueArgsContext.class,0);
		}
		public LabelConstraintContext(HeadConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterLabelConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitLabelConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitLabelConstraint(this);
			else return visitor.visitChildren(this);
		}
	}

	public final HeadConstraintContext headConstraint() throws RecognitionException {
		HeadConstraintContext _localctx = new HeadConstraintContext(_ctx, getState());
		enterRule(_localctx, 16, RULE_headConstraint);
		try {
			setState(243);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case T__1:
				_localctx = new HeadContainerConstraintContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(237);
				match(T__1);
				setState(238);
				((HeadContainerConstraintContext)_localctx).args = headConstraintListArgs();
				}
				break;
			case T__2:
				_localctx = new CollectionConstraintContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(239);
				match(T__2);
				setState(240);
				((CollectionConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case T__3:
				_localctx = new LabelConstraintContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(241);
				match(T__3);
				setState(242);
				((LabelConstraintContext)_localctx).args = classifierWithValueArgs();
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
	public static class EntityScopeConstraintContext extends FilterConstraintContext {
		public ValueListArgsContext args;
		public ValueListArgsContext valueListArgs() {
			return getRuleContext(ValueListArgsContext.class,0);
		}
		public EntityScopeConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterEntityScopeConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitEntityScopeConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitEntityScopeConstraint(this);
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
		public ClassifierWithTwoFilterConstraintArgsContext args;
		public ClassifierWithTwoFilterConstraintArgsContext classifierWithTwoFilterConstraintArgs() {
			return getRuleContext(ClassifierWithTwoFilterConstraintArgsContext.class,0);
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
	public static class FacetIncludingChildrenConstraintContext extends FilterConstraintContext {
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public FacetIncludingChildrenConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetIncludingChildrenConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetIncludingChildrenConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetIncludingChildrenConstraint(this);
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
	public static class FacetIncludingChildrenHavingConstraintContext extends FilterConstraintContext {
		public FilterConstraintArgsContext args;
		public FilterConstraintArgsContext filterConstraintArgs() {
			return getRuleContext(FilterConstraintArgsContext.class,0);
		}
		public FacetIncludingChildrenHavingConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetIncludingChildrenHavingConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetIncludingChildrenHavingConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetIncludingChildrenHavingConstraint(this);
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
	public static class FacetIncludingChildrenExceptConstraintContext extends FilterConstraintContext {
		public FilterConstraintArgsContext args;
		public FilterConstraintArgsContext filterConstraintArgs() {
			return getRuleContext(FilterConstraintArgsContext.class,0);
		}
		public FacetIncludingChildrenExceptConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetIncludingChildrenExceptConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetIncludingChildrenExceptConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetIncludingChildrenExceptConstraint(this);
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
	public static class FilterInScopeConstraintContext extends FilterConstraintContext {
		public InScopeFilterArgsContext args;
		public InScopeFilterArgsContext inScopeFilterArgs() {
			return getRuleContext(InScopeFilterArgsContext.class,0);
		}
		public FilterInScopeConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFilterInScopeConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFilterInScopeConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFilterInScopeConstraint(this);
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
			setState(358);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case T__4:
				_localctx = new FilterByConstraintContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(245);
				match(T__4);
				setState(246);
				((FilterByConstraintContext)_localctx).args = filterConstraintListArgs();
				}
				break;
			case T__5:
				_localctx = new FilterGroupByConstraintContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(247);
				match(T__5);
				setState(248);
				((FilterGroupByConstraintContext)_localctx).args = filterConstraintListArgs();
				}
				break;
			case T__6:
				_localctx = new AndConstraintContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(249);
				match(T__6);
				setState(252);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,2,_ctx) ) {
				case 1:
					{
					setState(250);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(251);
					((AndConstraintContext)_localctx).args = filterConstraintListArgs();
					}
					break;
				}
				}
				break;
			case T__7:
				_localctx = new OrConstraintContext(_localctx);
				enterOuterAlt(_localctx, 4);
				{
				setState(254);
				match(T__7);
				setState(257);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,3,_ctx) ) {
				case 1:
					{
					setState(255);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(256);
					((OrConstraintContext)_localctx).args = filterConstraintListArgs();
					}
					break;
				}
				}
				break;
			case T__8:
				_localctx = new NotConstraintContext(_localctx);
				enterOuterAlt(_localctx, 5);
				{
				setState(259);
				match(T__8);
				setState(260);
				((NotConstraintContext)_localctx).args = filterConstraintArgs();
				}
				break;
			case T__9:
				_localctx = new UserFilterConstraintContext(_localctx);
				enterOuterAlt(_localctx, 6);
				{
				setState(261);
				match(T__9);
				setState(264);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,4,_ctx) ) {
				case 1:
					{
					setState(262);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(263);
					((UserFilterConstraintContext)_localctx).args = filterConstraintListArgs();
					}
					break;
				}
				}
				break;
			case T__10:
				_localctx = new AttributeEqualsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 7);
				{
				setState(266);
				match(T__10);
				setState(267);
				((AttributeEqualsConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__11:
				_localctx = new AttributeGreaterThanConstraintContext(_localctx);
				enterOuterAlt(_localctx, 8);
				{
				setState(268);
				match(T__11);
				setState(269);
				((AttributeGreaterThanConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__12:
				_localctx = new AttributeGreaterThanEqualsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 9);
				{
				setState(270);
				match(T__12);
				setState(271);
				((AttributeGreaterThanEqualsConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__13:
				_localctx = new AttributeLessThanConstraintContext(_localctx);
				enterOuterAlt(_localctx, 10);
				{
				setState(272);
				match(T__13);
				setState(273);
				((AttributeLessThanConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__14:
				_localctx = new AttributeLessThanEqualsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 11);
				{
				setState(274);
				match(T__14);
				setState(275);
				((AttributeLessThanEqualsConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__15:
				_localctx = new AttributeBetweenConstraintContext(_localctx);
				enterOuterAlt(_localctx, 12);
				{
				setState(276);
				match(T__15);
				setState(277);
				((AttributeBetweenConstraintContext)_localctx).args = classifierWithBetweenValuesArgs();
				}
				break;
			case T__16:
				_localctx = new AttributeInSetConstraintContext(_localctx);
				enterOuterAlt(_localctx, 13);
				{
				setState(278);
				match(T__16);
				setState(279);
				((AttributeInSetConstraintContext)_localctx).args = classifierWithOptionalValueListArgs();
				}
				break;
			case T__17:
				_localctx = new AttributeContainsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 14);
				{
				setState(280);
				match(T__17);
				setState(281);
				((AttributeContainsConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__18:
				_localctx = new AttributeStartsWithConstraintContext(_localctx);
				enterOuterAlt(_localctx, 15);
				{
				setState(282);
				match(T__18);
				setState(283);
				((AttributeStartsWithConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__19:
				_localctx = new AttributeEndsWithConstraintContext(_localctx);
				enterOuterAlt(_localctx, 16);
				{
				setState(284);
				match(T__19);
				setState(285);
				((AttributeEndsWithConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__20:
				_localctx = new AttributeEqualsTrueConstraintContext(_localctx);
				enterOuterAlt(_localctx, 17);
				{
				setState(286);
				match(T__20);
				setState(287);
				((AttributeEqualsTrueConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case T__21:
				_localctx = new AttributeEqualsFalseConstraintContext(_localctx);
				enterOuterAlt(_localctx, 18);
				{
				setState(288);
				match(T__21);
				setState(289);
				((AttributeEqualsFalseConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case T__22:
				_localctx = new AttributeIsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 19);
				{
				setState(290);
				match(T__22);
				setState(291);
				((AttributeIsConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__23:
				_localctx = new AttributeIsNullConstraintContext(_localctx);
				enterOuterAlt(_localctx, 20);
				{
				setState(292);
				match(T__23);
				setState(293);
				((AttributeIsNullConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case T__24:
				_localctx = new AttributeIsNotNullConstraintContext(_localctx);
				enterOuterAlt(_localctx, 21);
				{
				setState(294);
				match(T__24);
				setState(295);
				((AttributeIsNotNullConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case T__25:
				_localctx = new AttributeInRangeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 22);
				{
				setState(296);
				match(T__25);
				setState(297);
				((AttributeInRangeConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__26:
				_localctx = new AttributeInRangeNowConstraintContext(_localctx);
				enterOuterAlt(_localctx, 23);
				{
				setState(298);
				match(T__26);
				setState(299);
				((AttributeInRangeNowConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case T__27:
				_localctx = new EntityPrimaryKeyInSetConstraintContext(_localctx);
				enterOuterAlt(_localctx, 24);
				{
				setState(300);
				match(T__27);
				setState(303);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,5,_ctx) ) {
				case 1:
					{
					setState(301);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(302);
					((EntityPrimaryKeyInSetConstraintContext)_localctx).args = valueListArgs();
					}
					break;
				}
				}
				break;
			case T__28:
				_localctx = new EntityLocaleEqualsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 25);
				{
				setState(305);
				match(T__28);
				setState(306);
				((EntityLocaleEqualsConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case T__29:
				_localctx = new PriceInCurrencyConstraintContext(_localctx);
				enterOuterAlt(_localctx, 26);
				{
				setState(307);
				match(T__29);
				setState(308);
				((PriceInCurrencyConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case T__30:
				_localctx = new PriceInPriceListsConstraintsContext(_localctx);
				enterOuterAlt(_localctx, 27);
				{
				setState(309);
				match(T__30);
				setState(312);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,6,_ctx) ) {
				case 1:
					{
					setState(310);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(311);
					((PriceInPriceListsConstraintsContext)_localctx).args = classifierListArgs();
					}
					break;
				}
				}
				break;
			case T__31:
				_localctx = new PriceValidInNowConstraintContext(_localctx);
				enterOuterAlt(_localctx, 28);
				{
				setState(314);
				match(T__31);
				setState(315);
				emptyArgs();
				}
				break;
			case T__32:
				_localctx = new PriceValidInConstraintContext(_localctx);
				enterOuterAlt(_localctx, 29);
				{
				setState(316);
				match(T__32);
				setState(317);
				((PriceValidInConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case T__33:
				_localctx = new PriceBetweenConstraintContext(_localctx);
				enterOuterAlt(_localctx, 30);
				{
				setState(318);
				match(T__33);
				setState(319);
				((PriceBetweenConstraintContext)_localctx).args = betweenValuesArgs();
				}
				break;
			case T__34:
				_localctx = new FacetHavingConstraintContext(_localctx);
				enterOuterAlt(_localctx, 31);
				{
				setState(320);
				match(T__34);
				setState(321);
				((FacetHavingConstraintContext)_localctx).args = classifierWithTwoFilterConstraintArgs();
				}
				break;
			case T__35:
				_localctx = new FacetIncludingChildrenConstraintContext(_localctx);
				enterOuterAlt(_localctx, 32);
				{
				setState(322);
				match(T__35);
				setState(323);
				emptyArgs();
				}
				break;
			case T__36:
				_localctx = new FacetIncludingChildrenHavingConstraintContext(_localctx);
				enterOuterAlt(_localctx, 33);
				{
				setState(324);
				match(T__36);
				setState(325);
				((FacetIncludingChildrenHavingConstraintContext)_localctx).args = filterConstraintArgs();
				}
				break;
			case T__37:
				_localctx = new FacetIncludingChildrenExceptConstraintContext(_localctx);
				enterOuterAlt(_localctx, 34);
				{
				setState(326);
				match(T__37);
				setState(327);
				((FacetIncludingChildrenExceptConstraintContext)_localctx).args = filterConstraintArgs();
				}
				break;
			case T__38:
				_localctx = new ReferenceHavingConstraintContext(_localctx);
				enterOuterAlt(_localctx, 35);
				{
				setState(328);
				match(T__38);
				setState(331);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,7,_ctx) ) {
				case 1:
					{
					setState(329);
					((ReferenceHavingConstraintContext)_localctx).args = classifierArgs();
					}
					break;
				case 2:
					{
					setState(330);
					classifierWithFilterConstraintArgs();
					}
					break;
				}
				}
				break;
			case T__39:
				_localctx = new HierarchyWithinConstraintContext(_localctx);
				enterOuterAlt(_localctx, 36);
				{
				setState(333);
				match(T__39);
				setState(334);
				((HierarchyWithinConstraintContext)_localctx).args = hierarchyWithinConstraintArgs();
				}
				break;
			case T__40:
				_localctx = new HierarchyWithinSelfConstraintContext(_localctx);
				enterOuterAlt(_localctx, 37);
				{
				setState(335);
				match(T__40);
				setState(336);
				((HierarchyWithinSelfConstraintContext)_localctx).args = hierarchyWithinSelfConstraintArgs();
				}
				break;
			case T__41:
				_localctx = new HierarchyWithinRootConstraintContext(_localctx);
				enterOuterAlt(_localctx, 38);
				{
				setState(337);
				match(T__41);
				setState(338);
				((HierarchyWithinRootConstraintContext)_localctx).args = hierarchyWithinRootConstraintArgs();
				}
				break;
			case T__42:
				_localctx = new HierarchyWithinRootSelfConstraintContext(_localctx);
				enterOuterAlt(_localctx, 39);
				{
				setState(339);
				match(T__42);
				setState(342);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,8,_ctx) ) {
				case 1:
					{
					setState(340);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(341);
					((HierarchyWithinRootSelfConstraintContext)_localctx).args = hierarchyWithinRootSelfConstraintArgs();
					}
					break;
				}
				}
				break;
			case T__43:
				_localctx = new HierarchyDirectRelationConstraintContext(_localctx);
				enterOuterAlt(_localctx, 40);
				{
				setState(344);
				match(T__43);
				setState(345);
				emptyArgs();
				}
				break;
			case T__44:
				_localctx = new HierarchyHavingConstraintContext(_localctx);
				enterOuterAlt(_localctx, 41);
				{
				setState(346);
				match(T__44);
				setState(347);
				((HierarchyHavingConstraintContext)_localctx).args = filterConstraintListArgs();
				}
				break;
			case T__45:
				_localctx = new HierarchyExcludingRootConstraintContext(_localctx);
				enterOuterAlt(_localctx, 42);
				{
				setState(348);
				match(T__45);
				setState(349);
				emptyArgs();
				}
				break;
			case T__46:
				_localctx = new HierarchyExcludingConstraintContext(_localctx);
				enterOuterAlt(_localctx, 43);
				{
				setState(350);
				match(T__46);
				setState(351);
				((HierarchyExcludingConstraintContext)_localctx).args = filterConstraintListArgs();
				}
				break;
			case T__47:
				_localctx = new EntityHavingConstraintContext(_localctx);
				enterOuterAlt(_localctx, 44);
				{
				setState(352);
				match(T__47);
				setState(353);
				((EntityHavingConstraintContext)_localctx).args = filterConstraintArgs();
				}
				break;
			case T__48:
				_localctx = new FilterInScopeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 45);
				{
				setState(354);
				match(T__48);
				setState(355);
				((FilterInScopeConstraintContext)_localctx).args = inScopeFilterArgs();
				}
				break;
			case T__49:
				_localctx = new EntityScopeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 46);
				{
				setState(356);
				match(T__49);
				setState(357);
				((EntityScopeConstraintContext)_localctx).args = valueListArgs();
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
	public static class SegmentConstraintContext extends OrderConstraintContext {
		public SegmentArgsContext args;
		public SegmentArgsContext segmentArgs() {
			return getRuleContext(SegmentArgsContext.class,0);
		}
		public SegmentConstraintContext(OrderConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSegmentConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSegmentConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSegmentConstraint(this);
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
	public static class SegmentLimitConstraintContext extends OrderConstraintContext {
		public ValueArgsContext args;
		public ValueArgsContext valueArgs() {
			return getRuleContext(ValueArgsContext.class,0);
		}
		public SegmentLimitConstraintContext(OrderConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSegmentLimitConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSegmentLimitConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSegmentLimitConstraint(this);
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
	public static class RandomWithSeedConstraintContext extends OrderConstraintContext {
		public ValueArgsContext args;
		public ValueArgsContext valueArgs() {
			return getRuleContext(ValueArgsContext.class,0);
		}
		public RandomWithSeedConstraintContext(OrderConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterRandomWithSeedConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitRandomWithSeedConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitRandomWithSeedConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	public static class TraverseByEntityPropertyConstraintContext extends OrderConstraintContext {
		public TraverseOrderConstraintListArgsContext args;
		public TraverseOrderConstraintListArgsContext traverseOrderConstraintListArgs() {
			return getRuleContext(TraverseOrderConstraintListArgsContext.class,0);
		}
		public TraverseByEntityPropertyConstraintContext(OrderConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterTraverseByEntityPropertyConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitTraverseByEntityPropertyConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitTraverseByEntityPropertyConstraint(this);
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
	public static class SegmentsConstraintContext extends OrderConstraintContext {
		public OrderConstraintListArgsContext args;
		public OrderConstraintListArgsContext orderConstraintListArgs() {
			return getRuleContext(OrderConstraintListArgsContext.class,0);
		}
		public SegmentsConstraintContext(OrderConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSegmentsConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSegmentsConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSegmentsConstraint(this);
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
	public static class PickFirstByByEntityPropertyConstraintContext extends OrderConstraintContext {
		public OrderConstraintListArgsContext args;
		public OrderConstraintListArgsContext orderConstraintListArgs() {
			return getRuleContext(OrderConstraintListArgsContext.class,0);
		}
		public PickFirstByByEntityPropertyConstraintContext(OrderConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterPickFirstByByEntityPropertyConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitPickFirstByByEntityPropertyConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitPickFirstByByEntityPropertyConstraint(this);
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
	public static class OrderInScopeConstraintContext extends OrderConstraintContext {
		public InScopeOrderArgsContext args;
		public InScopeOrderArgsContext inScopeOrderArgs() {
			return getRuleContext(InScopeOrderArgsContext.class,0);
		}
		public OrderInScopeConstraintContext(OrderConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterOrderInScopeConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitOrderInScopeConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitOrderInScopeConstraint(this);
			else return visitor.visitChildren(this);
		}
	}

	public final OrderConstraintContext orderConstraint() throws RecognitionException {
		OrderConstraintContext _localctx = new OrderConstraintContext(_ctx, getState());
		enterRule(_localctx, 20, RULE_orderConstraint);
		try {
			setState(414);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case T__50:
				_localctx = new OrderByConstraintContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(360);
				match(T__50);
				setState(363);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,10,_ctx) ) {
				case 1:
					{
					setState(361);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(362);
					((OrderByConstraintContext)_localctx).args = orderConstraintListArgs();
					}
					break;
				}
				}
				break;
			case T__51:
				_localctx = new OrderGroupByConstraintContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(365);
				match(T__51);
				setState(368);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,11,_ctx) ) {
				case 1:
					{
					setState(366);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(367);
					((OrderGroupByConstraintContext)_localctx).args = orderConstraintListArgs();
					}
					break;
				}
				}
				break;
			case T__52:
				_localctx = new AttributeNaturalConstraintContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(370);
				match(T__52);
				setState(371);
				((AttributeNaturalConstraintContext)_localctx).args = classifierWithOptionalValueArgs();
				}
				break;
			case T__53:
				_localctx = new AttributeSetExactConstraintContext(_localctx);
				enterOuterAlt(_localctx, 4);
				{
				setState(372);
				match(T__53);
				setState(373);
				((AttributeSetExactConstraintContext)_localctx).args = attributeSetExactArgs();
				}
				break;
			case T__54:
				_localctx = new AttributeSetInFilterConstraintContext(_localctx);
				enterOuterAlt(_localctx, 5);
				{
				setState(374);
				match(T__54);
				setState(375);
				((AttributeSetInFilterConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case T__55:
				_localctx = new PriceNaturalConstraintContext(_localctx);
				enterOuterAlt(_localctx, 6);
				{
				setState(376);
				match(T__55);
				setState(379);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,12,_ctx) ) {
				case 1:
					{
					setState(377);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(378);
					((PriceNaturalConstraintContext)_localctx).args = valueArgs();
					}
					break;
				}
				}
				break;
			case T__56:
				_localctx = new PriceDiscountConstraintContext(_localctx);
				enterOuterAlt(_localctx, 7);
				{
				setState(381);
				match(T__56);
				setState(382);
				((PriceDiscountConstraintContext)_localctx).args = valueListArgs();
				}
				break;
			case T__57:
				_localctx = new RandomConstraintContext(_localctx);
				enterOuterAlt(_localctx, 8);
				{
				setState(383);
				match(T__57);
				setState(384);
				emptyArgs();
				}
				break;
			case T__58:
				_localctx = new RandomWithSeedConstraintContext(_localctx);
				enterOuterAlt(_localctx, 9);
				{
				setState(385);
				match(T__58);
				setState(386);
				((RandomWithSeedConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case T__59:
				_localctx = new ReferencePropertyConstraintContext(_localctx);
				enterOuterAlt(_localctx, 10);
				{
				setState(387);
				match(T__59);
				setState(388);
				((ReferencePropertyConstraintContext)_localctx).args = classifierWithOrderConstraintListArgs();
				}
				break;
			case T__60:
				_localctx = new TraverseByEntityPropertyConstraintContext(_localctx);
				enterOuterAlt(_localctx, 11);
				{
				setState(389);
				match(T__60);
				setState(390);
				((TraverseByEntityPropertyConstraintContext)_localctx).args = traverseOrderConstraintListArgs();
				}
				break;
			case T__61:
				_localctx = new PickFirstByByEntityPropertyConstraintContext(_localctx);
				enterOuterAlt(_localctx, 12);
				{
				setState(391);
				match(T__61);
				setState(392);
				((PickFirstByByEntityPropertyConstraintContext)_localctx).args = orderConstraintListArgs();
				}
				break;
			case T__62:
				_localctx = new EntityPrimaryKeyExactNaturalContext(_localctx);
				enterOuterAlt(_localctx, 13);
				{
				setState(393);
				match(T__62);
				setState(396);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,13,_ctx) ) {
				case 1:
					{
					setState(394);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(395);
					((EntityPrimaryKeyExactNaturalContext)_localctx).args = valueArgs();
					}
					break;
				}
				}
				break;
			case T__63:
				_localctx = new EntityPrimaryKeyExactConstraintContext(_localctx);
				enterOuterAlt(_localctx, 14);
				{
				setState(398);
				match(T__63);
				setState(399);
				((EntityPrimaryKeyExactConstraintContext)_localctx).args = valueListArgs();
				}
				break;
			case T__64:
				_localctx = new EntityPrimaryKeyInFilterConstraintContext(_localctx);
				enterOuterAlt(_localctx, 15);
				{
				setState(400);
				match(T__64);
				setState(401);
				emptyArgs();
				}
				break;
			case T__65:
				_localctx = new EntityPropertyConstraintContext(_localctx);
				enterOuterAlt(_localctx, 16);
				{
				setState(402);
				match(T__65);
				setState(403);
				((EntityPropertyConstraintContext)_localctx).args = orderConstraintListArgs();
				}
				break;
			case T__66:
				_localctx = new EntityGroupPropertyConstraintContext(_localctx);
				enterOuterAlt(_localctx, 17);
				{
				setState(404);
				match(T__66);
				setState(405);
				((EntityGroupPropertyConstraintContext)_localctx).args = orderConstraintListArgs();
				}
				break;
			case T__67:
				_localctx = new SegmentsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 18);
				{
				setState(406);
				match(T__67);
				setState(407);
				((SegmentsConstraintContext)_localctx).args = orderConstraintListArgs();
				}
				break;
			case T__68:
				_localctx = new SegmentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 19);
				{
				setState(408);
				match(T__68);
				setState(409);
				((SegmentConstraintContext)_localctx).args = segmentArgs();
				}
				break;
			case T__69:
				_localctx = new SegmentLimitConstraintContext(_localctx);
				enterOuterAlt(_localctx, 20);
				{
				setState(410);
				match(T__69);
				setState(411);
				((SegmentLimitConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case T__48:
				_localctx = new OrderInScopeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 21);
				{
				setState(412);
				match(T__48);
				setState(413);
				((OrderInScopeConstraintContext)_localctx).args = inScopeOrderArgs();
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
		public FacetGroupRelationArgsContext args;
		public FacetGroupRelationArgsContext facetGroupRelationArgs() {
			return getRuleContext(FacetGroupRelationArgsContext.class,0);
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
	public static class SpacingConstraintContext extends RequireConstraintContext {
		public SpacingRequireConstraintArgsContext args;
		public SpacingRequireConstraintArgsContext spacingRequireConstraintArgs() {
			return getRuleContext(SpacingRequireConstraintArgsContext.class,0);
		}
		public SpacingConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSpacingConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSpacingConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSpacingConstraint(this);
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
	public static class FacetCalculationRulesConstraintContext extends RequireConstraintContext {
		public FacetCalculationRulesArgsContext args;
		public FacetCalculationRulesArgsContext facetCalculationRulesArgs() {
			return getRuleContext(FacetCalculationRulesArgsContext.class,0);
		}
		public FacetCalculationRulesConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetCalculationRulesConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetCalculationRulesConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetCalculationRulesConstraint(this);
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
	public static class SingleRefReferenceContentWithAttributes0ConstraintContext extends RequireConstraintContext {
		public SingleRefReferenceContentWithAttributes0ArgsContext args;
		public SingleRefReferenceContentWithAttributes0ArgsContext singleRefReferenceContentWithAttributes0Args() {
			return getRuleContext(SingleRefReferenceContentWithAttributes0ArgsContext.class,0);
		}
		public SingleRefReferenceContentWithAttributes0ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContentWithAttributes0Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContentWithAttributes0Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContentWithAttributes0Constraint(this);
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
		public FacetGroupRelationArgsContext args;
		public FacetGroupRelationArgsContext facetGroupRelationArgs() {
			return getRuleContext(FacetGroupRelationArgsContext.class,0);
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
	public static class AccompanyingPriceContentConstraintContext extends RequireConstraintContext {
		public ClassifierWithOptionalValueListArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public ClassifierWithOptionalValueListArgsContext classifierWithOptionalValueListArgs() {
			return getRuleContext(ClassifierWithOptionalValueListArgsContext.class,0);
		}
		public AccompanyingPriceContentConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAccompanyingPriceContentConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAccompanyingPriceContentConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAccompanyingPriceContentConstraint(this);
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
	public static class RequireInScopeConstraintContext extends RequireConstraintContext {
		public InScopeRequireArgsContext args;
		public InScopeRequireArgsContext inScopeRequireArgs() {
			return getRuleContext(InScopeRequireArgsContext.class,0);
		}
		public RequireInScopeConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterRequireInScopeConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitRequireInScopeConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitRequireInScopeConstraint(this);
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
	public static class GapConstraintContext extends RequireConstraintContext {
		public GapRequireConstraintArgsContext args;
		public GapRequireConstraintArgsContext gapRequireConstraintArgs() {
			return getRuleContext(GapRequireConstraintArgsContext.class,0);
		}
		public GapConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterGapConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitGapConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitGapConstraint(this);
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
	public static class FacetGroupsExclusivityConstraintContext extends RequireConstraintContext {
		public FacetGroupRelationArgsContext args;
		public FacetGroupRelationArgsContext facetGroupRelationArgs() {
			return getRuleContext(FacetGroupRelationArgsContext.class,0);
		}
		public FacetGroupsExclusivityConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetGroupsExclusivityConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetGroupsExclusivityConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetGroupsExclusivityConstraint(this);
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
	public static class DefaultAccompanyingPriceConstraintContext extends RequireConstraintContext {
		public ClassifierListArgsContext args;
		public ClassifierListArgsContext classifierListArgs() {
			return getRuleContext(ClassifierListArgsContext.class,0);
		}
		public DefaultAccompanyingPriceConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterDefaultAccompanyingPriceConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitDefaultAccompanyingPriceConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitDefaultAccompanyingPriceConstraint(this);
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
		public FacetGroupRelationArgsContext args;
		public FacetGroupRelationArgsContext facetGroupRelationArgs() {
			return getRuleContext(FacetGroupRelationArgsContext.class,0);
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
			setState(611);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,24,_ctx) ) {
			case 1:
				_localctx = new RequireContainerConstraintContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(416);
				match(T__70);
				setState(419);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,15,_ctx) ) {
				case 1:
					{
					setState(417);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(418);
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
				setState(421);
				match(T__71);
				setState(422);
				((PageConstraintContext)_localctx).args = pageConstraintArgs();
				}
				break;
			case 3:
				_localctx = new StripConstraintContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(423);
				match(T__72);
				setState(424);
				((StripConstraintContext)_localctx).args = stripConstraintArgs();
				}
				break;
			case 4:
				_localctx = new EntityFetchConstraintContext(_localctx);
				enterOuterAlt(_localctx, 4);
				{
				setState(425);
				match(T__73);
				setState(428);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,16,_ctx) ) {
				case 1:
					{
					setState(426);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(427);
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
				setState(430);
				match(T__74);
				setState(433);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,17,_ctx) ) {
				case 1:
					{
					setState(431);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(432);
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
				setState(435);
				match(T__75);
				setState(436);
				((AttributeContentConstraintContext)_localctx).args = classifierListArgs();
				}
				break;
			case 7:
				_localctx = new AttributeContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 7);
				{
				setState(437);
				match(T__76);
				setState(438);
				emptyArgs();
				}
				break;
			case 8:
				_localctx = new PriceContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 8);
				{
				setState(439);
				match(T__77);
				setState(440);
				((PriceContentConstraintContext)_localctx).args = priceContentArgs();
				}
				break;
			case 9:
				_localctx = new PriceContentAllConstraintContext(_localctx);
				enterOuterAlt(_localctx, 9);
				{
				setState(441);
				match(T__78);
				setState(442);
				emptyArgs();
				}
				break;
			case 10:
				_localctx = new PriceContentRespectingFilterConstraintContext(_localctx);
				enterOuterAlt(_localctx, 10);
				{
				setState(443);
				match(T__79);
				setState(446);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,18,_ctx) ) {
				case 1:
					{
					setState(444);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(445);
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
				setState(448);
				match(T__80);
				setState(449);
				((AssociatedDataContentConstraintContext)_localctx).args = classifierListArgs();
				}
				break;
			case 12:
				_localctx = new AssociatedDataContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 12);
				{
				setState(450);
				match(T__81);
				setState(451);
				emptyArgs();
				}
				break;
			case 13:
				_localctx = new AllRefsReferenceContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 13);
				{
				setState(452);
				match(T__82);
				setState(455);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,19,_ctx) ) {
				case 1:
					{
					setState(453);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(454);
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
				setState(457);
				match(T__83);
				setState(458);
				((MultipleRefsReferenceContentConstraintContext)_localctx).args = multipleRefsReferenceContentArgs();
				}
				break;
			case 15:
				_localctx = new SingleRefReferenceContent1ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 15);
				{
				setState(459);
				match(T__83);
				setState(460);
				((SingleRefReferenceContent1ConstraintContext)_localctx).args = singleRefReferenceContent1Args();
				}
				break;
			case 16:
				_localctx = new SingleRefReferenceContent2ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 16);
				{
				setState(461);
				match(T__83);
				setState(462);
				((SingleRefReferenceContent2ConstraintContext)_localctx).args = singleRefReferenceContent2Args();
				}
				break;
			case 17:
				_localctx = new SingleRefReferenceContent3ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 17);
				{
				setState(463);
				match(T__83);
				setState(464);
				((SingleRefReferenceContent3ConstraintContext)_localctx).args = singleRefReferenceContent3Args();
				}
				break;
			case 18:
				_localctx = new SingleRefReferenceContent4ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 18);
				{
				setState(465);
				match(T__83);
				setState(466);
				((SingleRefReferenceContent4ConstraintContext)_localctx).args = singleRefReferenceContent4Args();
				}
				break;
			case 19:
				_localctx = new SingleRefReferenceContent5ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 19);
				{
				setState(467);
				match(T__83);
				setState(468);
				((SingleRefReferenceContent5ConstraintContext)_localctx).args = singleRefReferenceContent5Args();
				}
				break;
			case 20:
				_localctx = new SingleRefReferenceContent6ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 20);
				{
				setState(469);
				match(T__83);
				setState(470);
				((SingleRefReferenceContent6ConstraintContext)_localctx).args = singleRefReferenceContent6Args();
				}
				break;
			case 21:
				_localctx = new SingleRefReferenceContent7ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 21);
				{
				setState(471);
				match(T__83);
				setState(472);
				((SingleRefReferenceContent7ConstraintContext)_localctx).args = singleRefReferenceContent7Args();
				}
				break;
			case 22:
				_localctx = new SingleRefReferenceContent8ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 22);
				{
				setState(473);
				match(T__83);
				setState(474);
				((SingleRefReferenceContent8ConstraintContext)_localctx).args = singleRefReferenceContent8Args();
				}
				break;
			case 23:
				_localctx = new AllRefsWithAttributesReferenceContent1ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 23);
				{
				setState(475);
				match(T__84);
				setState(478);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,20,_ctx) ) {
				case 1:
					{
					setState(476);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(477);
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
				setState(480);
				match(T__84);
				setState(481);
				((AllRefsWithAttributesReferenceContent2ConstraintContext)_localctx).args = allRefsWithAttributesReferenceContent2Args();
				}
				break;
			case 25:
				_localctx = new AllRefsWithAttributesReferenceContent3ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 25);
				{
				setState(482);
				match(T__84);
				setState(483);
				((AllRefsWithAttributesReferenceContent3ConstraintContext)_localctx).args = allRefsWithAttributesReferenceContent3Args();
				}
				break;
			case 26:
				_localctx = new SingleRefReferenceContentWithAttributes1ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 26);
				{
				setState(484);
				match(T__85);
				setState(485);
				((SingleRefReferenceContentWithAttributes1ConstraintContext)_localctx).args = singleRefReferenceContent1Args();
				}
				break;
			case 27:
				_localctx = new SingleRefReferenceContentWithAttributes0ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 27);
				{
				setState(486);
				match(T__85);
				setState(487);
				((SingleRefReferenceContentWithAttributes0ConstraintContext)_localctx).args = singleRefReferenceContentWithAttributes0Args();
				}
				break;
			case 28:
				_localctx = new SingleRefReferenceContentWithAttributes2ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 28);
				{
				setState(488);
				match(T__85);
				setState(489);
				((SingleRefReferenceContentWithAttributes2ConstraintContext)_localctx).args = singleRefReferenceContentWithAttributes1Args();
				}
				break;
			case 29:
				_localctx = new SingleRefReferenceContentWithAttributes3ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 29);
				{
				setState(490);
				match(T__85);
				setState(491);
				((SingleRefReferenceContentWithAttributes3ConstraintContext)_localctx).args = singleRefReferenceContentWithAttributes2Args();
				}
				break;
			case 30:
				_localctx = new SingleRefReferenceContentWithAttributes4ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 30);
				{
				setState(492);
				match(T__85);
				setState(493);
				((SingleRefReferenceContentWithAttributes4ConstraintContext)_localctx).args = singleRefReferenceContent3Args();
				}
				break;
			case 31:
				_localctx = new SingleRefReferenceContentWithAttributes5ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 31);
				{
				setState(494);
				match(T__85);
				setState(495);
				((SingleRefReferenceContentWithAttributes5ConstraintContext)_localctx).args = singleRefReferenceContentWithAttributes3Args();
				}
				break;
			case 32:
				_localctx = new SingleRefReferenceContentWithAttributes6ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 32);
				{
				setState(496);
				match(T__85);
				setState(497);
				((SingleRefReferenceContentWithAttributes6ConstraintContext)_localctx).args = singleRefReferenceContentWithAttributes4Args();
				}
				break;
			case 33:
				_localctx = new SingleRefReferenceContentWithAttributes7ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 33);
				{
				setState(498);
				match(T__85);
				setState(499);
				((SingleRefReferenceContentWithAttributes7ConstraintContext)_localctx).args = singleRefReferenceContent5Args();
				}
				break;
			case 34:
				_localctx = new SingleRefReferenceContentWithAttributes8ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 34);
				{
				setState(500);
				match(T__85);
				setState(501);
				((SingleRefReferenceContentWithAttributes8ConstraintContext)_localctx).args = singleRefReferenceContentWithAttributes5Args();
				}
				break;
			case 35:
				_localctx = new SingleRefReferenceContentWithAttributes9ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 35);
				{
				setState(502);
				match(T__85);
				setState(503);
				((SingleRefReferenceContentWithAttributes9ConstraintContext)_localctx).args = singleRefReferenceContentWithAttributes6Args();
				}
				break;
			case 36:
				_localctx = new SingleRefReferenceContentWithAttributes10ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 36);
				{
				setState(504);
				match(T__85);
				setState(505);
				((SingleRefReferenceContentWithAttributes10ConstraintContext)_localctx).args = singleRefReferenceContent7Args();
				}
				break;
			case 37:
				_localctx = new SingleRefReferenceContentWithAttributes11ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 37);
				{
				setState(506);
				match(T__85);
				setState(507);
				((SingleRefReferenceContentWithAttributes11ConstraintContext)_localctx).args = singleRefReferenceContentWithAttributes7Args();
				}
				break;
			case 38:
				_localctx = new SingleRefReferenceContentWithAttributes12ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 38);
				{
				setState(508);
				match(T__85);
				setState(509);
				((SingleRefReferenceContentWithAttributes12ConstraintContext)_localctx).args = singleRefReferenceContentWithAttributes8Args();
				}
				break;
			case 39:
				_localctx = new EmptyHierarchyContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 39);
				{
				setState(510);
				match(T__86);
				setState(511);
				emptyArgs();
				}
				break;
			case 40:
				_localctx = new SingleRequireHierarchyContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 40);
				{
				setState(512);
				match(T__86);
				setState(513);
				((SingleRequireHierarchyContentConstraintContext)_localctx).args = singleRequireHierarchyContentArgs();
				}
				break;
			case 41:
				_localctx = new AllRequiresHierarchyContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 41);
				{
				setState(514);
				match(T__86);
				setState(515);
				((AllRequiresHierarchyContentConstraintContext)_localctx).args = allRequiresHierarchyContentArgs();
				}
				break;
			case 42:
				_localctx = new DefaultAccompanyingPriceConstraintContext(_localctx);
				enterOuterAlt(_localctx, 42);
				{
				setState(516);
				match(T__87);
				setState(517);
				((DefaultAccompanyingPriceConstraintContext)_localctx).args = classifierListArgs();
				}
				break;
			case 43:
				_localctx = new AccompanyingPriceContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 43);
				{
				setState(518);
				match(T__88);
				setState(521);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,21,_ctx) ) {
				case 1:
					{
					setState(519);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(520);
					((AccompanyingPriceContentConstraintContext)_localctx).args = classifierWithOptionalValueListArgs();
					}
					break;
				}
				}
				break;
			case 44:
				_localctx = new PriceTypeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 44);
				{
				setState(523);
				match(T__89);
				setState(524);
				((PriceTypeConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case 45:
				_localctx = new DataInLocalesAllConstraintContext(_localctx);
				enterOuterAlt(_localctx, 45);
				{
				setState(525);
				match(T__90);
				setState(526);
				emptyArgs();
				}
				break;
			case 46:
				_localctx = new DataInLocalesConstraintContext(_localctx);
				enterOuterAlt(_localctx, 46);
				{
				setState(527);
				match(T__91);
				setState(528);
				((DataInLocalesConstraintContext)_localctx).args = valueListArgs();
				}
				break;
			case 47:
				_localctx = new FacetSummary1ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 47);
				{
				setState(529);
				match(T__92);
				setState(532);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,22,_ctx) ) {
				case 1:
					{
					setState(530);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(531);
					((FacetSummary1ConstraintContext)_localctx).args = facetSummary1Args();
					}
					break;
				}
				}
				break;
			case 48:
				_localctx = new FacetSummary2ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 48);
				{
				setState(534);
				match(T__92);
				setState(535);
				((FacetSummary2ConstraintContext)_localctx).args = facetSummary2Args();
				}
				break;
			case 49:
				_localctx = new FacetSummary3ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 49);
				{
				setState(536);
				match(T__92);
				setState(537);
				((FacetSummary3ConstraintContext)_localctx).args = facetSummary3Args();
				}
				break;
			case 50:
				_localctx = new FacetSummary4ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 50);
				{
				setState(538);
				match(T__92);
				setState(539);
				((FacetSummary4ConstraintContext)_localctx).args = facetSummary4Args();
				}
				break;
			case 51:
				_localctx = new FacetSummary5ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 51);
				{
				setState(540);
				match(T__92);
				setState(541);
				((FacetSummary5ConstraintContext)_localctx).args = facetSummary5Args();
				}
				break;
			case 52:
				_localctx = new FacetSummary6ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 52);
				{
				setState(542);
				match(T__92);
				setState(543);
				((FacetSummary6ConstraintContext)_localctx).args = facetSummary6Args();
				}
				break;
			case 53:
				_localctx = new FacetSummary7ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 53);
				{
				setState(544);
				match(T__92);
				setState(545);
				((FacetSummary7ConstraintContext)_localctx).args = facetSummary7Args();
				}
				break;
			case 54:
				_localctx = new FacetSummaryOfReference1ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 54);
				{
				setState(546);
				match(T__93);
				setState(547);
				((FacetSummaryOfReference1ConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case 55:
				_localctx = new FacetSummaryOfReference2ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 55);
				{
				setState(548);
				match(T__93);
				setState(549);
				((FacetSummaryOfReference2ConstraintContext)_localctx).args = facetSummaryOfReference2Args();
				}
				break;
			case 56:
				_localctx = new FacetGroupsConjunctionConstraintContext(_localctx);
				enterOuterAlt(_localctx, 56);
				{
				setState(550);
				match(T__94);
				setState(551);
				((FacetGroupsConjunctionConstraintContext)_localctx).args = facetGroupRelationArgs();
				}
				break;
			case 57:
				_localctx = new FacetGroupsDisjunctionConstraintContext(_localctx);
				enterOuterAlt(_localctx, 57);
				{
				setState(552);
				match(T__95);
				setState(553);
				((FacetGroupsDisjunctionConstraintContext)_localctx).args = facetGroupRelationArgs();
				}
				break;
			case 58:
				_localctx = new FacetGroupsNegationConstraintContext(_localctx);
				enterOuterAlt(_localctx, 58);
				{
				setState(554);
				match(T__96);
				setState(555);
				((FacetGroupsNegationConstraintContext)_localctx).args = facetGroupRelationArgs();
				}
				break;
			case 59:
				_localctx = new FacetGroupsExclusivityConstraintContext(_localctx);
				enterOuterAlt(_localctx, 59);
				{
				setState(556);
				match(T__97);
				setState(557);
				((FacetGroupsExclusivityConstraintContext)_localctx).args = facetGroupRelationArgs();
				}
				break;
			case 60:
				_localctx = new FacetCalculationRulesConstraintContext(_localctx);
				enterOuterAlt(_localctx, 60);
				{
				setState(558);
				match(T__98);
				setState(559);
				((FacetCalculationRulesConstraintContext)_localctx).args = facetCalculationRulesArgs();
				}
				break;
			case 61:
				_localctx = new AttributeHistogramConstraintContext(_localctx);
				enterOuterAlt(_localctx, 61);
				{
				setState(560);
				match(T__99);
				setState(561);
				((AttributeHistogramConstraintContext)_localctx).args = attributeHistogramArgs();
				}
				break;
			case 62:
				_localctx = new PriceHistogramConstraintContext(_localctx);
				enterOuterAlt(_localctx, 62);
				{
				setState(562);
				match(T__100);
				setState(563);
				((PriceHistogramConstraintContext)_localctx).args = priceHistogramArgs();
				}
				break;
			case 63:
				_localctx = new HierarchyDistanceConstraintContext(_localctx);
				enterOuterAlt(_localctx, 63);
				{
				setState(564);
				match(T__101);
				setState(565);
				((HierarchyDistanceConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case 64:
				_localctx = new HierarchyLevelConstraintContext(_localctx);
				enterOuterAlt(_localctx, 64);
				{
				setState(566);
				match(T__102);
				setState(567);
				((HierarchyLevelConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case 65:
				_localctx = new HierarchyNodeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 65);
				{
				setState(568);
				match(T__103);
				setState(569);
				((HierarchyNodeConstraintContext)_localctx).args = filterConstraintArgs();
				}
				break;
			case 66:
				_localctx = new HierarchyStopAtConstraintContext(_localctx);
				enterOuterAlt(_localctx, 66);
				{
				setState(570);
				match(T__104);
				setState(571);
				((HierarchyStopAtConstraintContext)_localctx).args = requireConstraintArgs();
				}
				break;
			case 67:
				_localctx = new HierarchyStatisticsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 67);
				{
				setState(572);
				match(T__105);
				setState(575);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,23,_ctx) ) {
				case 1:
					{
					setState(573);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(574);
					((HierarchyStatisticsConstraintContext)_localctx).args = hierarchyStatisticsArgs();
					}
					break;
				}
				}
				break;
			case 68:
				_localctx = new HierarchyFromRootConstraintContext(_localctx);
				enterOuterAlt(_localctx, 68);
				{
				setState(577);
				match(T__106);
				setState(578);
				((HierarchyFromRootConstraintContext)_localctx).args = hierarchyRequireConstraintArgs();
				}
				break;
			case 69:
				_localctx = new HierarchyFromNodeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 69);
				{
				setState(579);
				match(T__107);
				setState(580);
				((HierarchyFromNodeConstraintContext)_localctx).args = hierarchyFromNodeArgs();
				}
				break;
			case 70:
				_localctx = new HierarchyChildrenConstraintContext(_localctx);
				enterOuterAlt(_localctx, 70);
				{
				setState(581);
				match(T__108);
				setState(582);
				((HierarchyChildrenConstraintContext)_localctx).args = hierarchyRequireConstraintArgs();
				}
				break;
			case 71:
				_localctx = new EmptyHierarchySiblingsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 71);
				{
				setState(583);
				match(T__109);
				setState(584);
				emptyArgs();
				}
				break;
			case 72:
				_localctx = new BasicHierarchySiblingsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 72);
				{
				setState(585);
				match(T__109);
				setState(586);
				((BasicHierarchySiblingsConstraintContext)_localctx).args = requireConstraintListArgs();
				}
				break;
			case 73:
				_localctx = new FullHierarchySiblingsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 73);
				{
				setState(587);
				match(T__109);
				setState(588);
				((FullHierarchySiblingsConstraintContext)_localctx).args = hierarchyRequireConstraintArgs();
				}
				break;
			case 74:
				_localctx = new SpacingConstraintContext(_localctx);
				enterOuterAlt(_localctx, 74);
				{
				setState(589);
				match(T__110);
				setState(590);
				((SpacingConstraintContext)_localctx).args = spacingRequireConstraintArgs();
				}
				break;
			case 75:
				_localctx = new GapConstraintContext(_localctx);
				enterOuterAlt(_localctx, 75);
				{
				setState(591);
				match(T__111);
				setState(592);
				((GapConstraintContext)_localctx).args = gapRequireConstraintArgs();
				}
				break;
			case 76:
				_localctx = new HierarchyParentsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 76);
				{
				setState(593);
				match(T__112);
				setState(594);
				((HierarchyParentsConstraintContext)_localctx).args = hierarchyRequireConstraintArgs();
				}
				break;
			case 77:
				_localctx = new BasicHierarchyOfSelfConstraintContext(_localctx);
				enterOuterAlt(_localctx, 77);
				{
				setState(595);
				match(T__113);
				setState(596);
				((BasicHierarchyOfSelfConstraintContext)_localctx).args = requireConstraintListArgs();
				}
				break;
			case 78:
				_localctx = new FullHierarchyOfSelfConstraintContext(_localctx);
				enterOuterAlt(_localctx, 78);
				{
				setState(597);
				match(T__113);
				setState(598);
				((FullHierarchyOfSelfConstraintContext)_localctx).args = fullHierarchyOfSelfArgs();
				}
				break;
			case 79:
				_localctx = new BasicHierarchyOfReferenceConstraintContext(_localctx);
				enterOuterAlt(_localctx, 79);
				{
				setState(599);
				match(T__114);
				setState(600);
				((BasicHierarchyOfReferenceConstraintContext)_localctx).args = basicHierarchyOfReferenceArgs();
				}
				break;
			case 80:
				_localctx = new BasicHierarchyOfReferenceWithBehaviourConstraintContext(_localctx);
				enterOuterAlt(_localctx, 80);
				{
				setState(601);
				match(T__114);
				setState(602);
				((BasicHierarchyOfReferenceWithBehaviourConstraintContext)_localctx).args = basicHierarchyOfReferenceWithBehaviourArgs();
				}
				break;
			case 81:
				_localctx = new FullHierarchyOfReferenceConstraintContext(_localctx);
				enterOuterAlt(_localctx, 81);
				{
				setState(603);
				match(T__114);
				setState(604);
				((FullHierarchyOfReferenceConstraintContext)_localctx).args = fullHierarchyOfReferenceArgs();
				}
				break;
			case 82:
				_localctx = new FullHierarchyOfReferenceWithBehaviourConstraintContext(_localctx);
				enterOuterAlt(_localctx, 82);
				{
				setState(605);
				match(T__114);
				setState(606);
				((FullHierarchyOfReferenceWithBehaviourConstraintContext)_localctx).args = fullHierarchyOfReferenceWithBehaviourArgs();
				}
				break;
			case 83:
				_localctx = new QueryTelemetryConstraintContext(_localctx);
				enterOuterAlt(_localctx, 83);
				{
				setState(607);
				match(T__115);
				setState(608);
				emptyArgs();
				}
				break;
			case 84:
				_localctx = new RequireInScopeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 84);
				{
				setState(609);
				match(T__48);
				setState(610);
				((RequireInScopeConstraintContext)_localctx).args = inScopeRequireArgs();
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
			setState(613);
			((HeadConstraintListContext)_localctx).headConstraint = headConstraint();
			((HeadConstraintListContext)_localctx).constraints.add(((HeadConstraintListContext)_localctx).headConstraint);
			setState(618);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(614);
				match(ARGS_DELIMITER);
				setState(615);
				((HeadConstraintListContext)_localctx).headConstraint = headConstraint();
				((HeadConstraintListContext)_localctx).constraints.add(((HeadConstraintListContext)_localctx).headConstraint);
				}
				}
				setState(620);
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
			setState(621);
			((FilterConstraintListContext)_localctx).filterConstraint = filterConstraint();
			((FilterConstraintListContext)_localctx).constraints.add(((FilterConstraintListContext)_localctx).filterConstraint);
			setState(626);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(622);
				match(ARGS_DELIMITER);
				setState(623);
				((FilterConstraintListContext)_localctx).filterConstraint = filterConstraint();
				((FilterConstraintListContext)_localctx).constraints.add(((FilterConstraintListContext)_localctx).filterConstraint);
				}
				}
				setState(628);
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
			setState(629);
			((OrderConstraintListContext)_localctx).orderConstraint = orderConstraint();
			((OrderConstraintListContext)_localctx).constraints.add(((OrderConstraintListContext)_localctx).orderConstraint);
			setState(634);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(630);
				match(ARGS_DELIMITER);
				setState(631);
				((OrderConstraintListContext)_localctx).orderConstraint = orderConstraint();
				((OrderConstraintListContext)_localctx).constraints.add(((OrderConstraintListContext)_localctx).orderConstraint);
				}
				}
				setState(636);
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
			setState(637);
			((RequireConstraintListContext)_localctx).requireConstraint = requireConstraint();
			((RequireConstraintListContext)_localctx).constraints.add(((RequireConstraintListContext)_localctx).requireConstraint);
			setState(642);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(638);
				match(ARGS_DELIMITER);
				setState(639);
				((RequireConstraintListContext)_localctx).requireConstraint = requireConstraint();
				((RequireConstraintListContext)_localctx).constraints.add(((RequireConstraintListContext)_localctx).requireConstraint);
				}
				}
				setState(644);
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
			setState(645);
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
			setState(648);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==ARGS_DELIMITER) {
				{
				setState(647);
				match(ARGS_DELIMITER);
				}
			}

			setState(650);
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
			setState(652);
			argsOpening();
			setState(653);
			((ConstraintListArgsContext)_localctx).constraint = constraint();
			((ConstraintListArgsContext)_localctx).constraints.add(((ConstraintListArgsContext)_localctx).constraint);
			setState(658);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,30,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(654);
					match(ARGS_DELIMITER);
					setState(655);
					((ConstraintListArgsContext)_localctx).constraint = constraint();
					((ConstraintListArgsContext)_localctx).constraints.add(((ConstraintListArgsContext)_localctx).constraint);
					}
					}
				}
				setState(660);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,30,_ctx);
			}
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
			setState(663);
			argsOpening();
			setState(664);
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

	public static class HeadConstraintListArgsContext extends ParserRuleContext {
		public HeadConstraintContext headConstraint;
		public List<HeadConstraintContext> constraints = new ArrayList<HeadConstraintContext>();
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
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
		public HeadConstraintListArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_headConstraintListArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHeadConstraintListArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHeadConstraintListArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHeadConstraintListArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final HeadConstraintListArgsContext headConstraintListArgs() throws RecognitionException {
		HeadConstraintListArgsContext _localctx = new HeadConstraintListArgsContext(_ctx, getState());
		enterRule(_localctx, 40, RULE_headConstraintListArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(666);
			argsOpening();
			setState(667);
			((HeadConstraintListArgsContext)_localctx).headConstraint = headConstraint();
			((HeadConstraintListArgsContext)_localctx).constraints.add(((HeadConstraintListArgsContext)_localctx).headConstraint);
			setState(672);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,31,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(668);
					match(ARGS_DELIMITER);
					setState(669);
					((HeadConstraintListArgsContext)_localctx).headConstraint = headConstraint();
					((HeadConstraintListArgsContext)_localctx).constraints.add(((HeadConstraintListArgsContext)_localctx).headConstraint);
					}
					}
				}
				setState(674);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,31,_ctx);
			}
			setState(675);
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
		enterRule(_localctx, 42, RULE_filterConstraintListArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(677);
			argsOpening();
			setState(678);
			((FilterConstraintListArgsContext)_localctx).filterConstraint = filterConstraint();
			((FilterConstraintListArgsContext)_localctx).constraints.add(((FilterConstraintListArgsContext)_localctx).filterConstraint);
			setState(683);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,32,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(679);
					match(ARGS_DELIMITER);
					setState(680);
					((FilterConstraintListArgsContext)_localctx).filterConstraint = filterConstraint();
					((FilterConstraintListArgsContext)_localctx).constraints.add(((FilterConstraintListArgsContext)_localctx).filterConstraint);
					}
					}
				}
				setState(685);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,32,_ctx);
			}
			setState(686);
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
		enterRule(_localctx, 44, RULE_filterConstraintArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(688);
			argsOpening();
			setState(689);
			((FilterConstraintArgsContext)_localctx).filter = filterConstraint();
			setState(690);
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

	public static class TraverseOrderConstraintListArgsContext extends ParserRuleContext {
		public ValueTokenContext traversalMode;
		public OrderConstraintContext orderConstraint;
		public List<OrderConstraintContext> constraints = new ArrayList<OrderConstraintContext>();
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
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
		public TraverseOrderConstraintListArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_traverseOrderConstraintListArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterTraverseOrderConstraintListArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitTraverseOrderConstraintListArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitTraverseOrderConstraintListArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final TraverseOrderConstraintListArgsContext traverseOrderConstraintListArgs() throws RecognitionException {
		TraverseOrderConstraintListArgsContext _localctx = new TraverseOrderConstraintListArgsContext(_ctx, getState());
		enterRule(_localctx, 46, RULE_traverseOrderConstraintListArgs);
		int _la;
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(692);
			argsOpening();
			setState(707);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,35,_ctx) ) {
			case 1:
				{
				{
				setState(693);
				((TraverseOrderConstraintListArgsContext)_localctx).traversalMode = valueToken();
				}
				}
				break;
			case 2:
				{
				{
				setState(697);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (((((_la - 117)) & ~0x3f) == 0 && ((1L << (_la - 117)) & ((1L << (POSITIONAL_PARAMETER - 117)) | (1L << (NAMED_PARAMETER - 117)) | (1L << (STRING - 117)) | (1L << (INT - 117)) | (1L << (FLOAT - 117)) | (1L << (BOOLEAN - 117)) | (1L << (DATE - 117)) | (1L << (TIME - 117)) | (1L << (DATE_TIME - 117)) | (1L << (OFFSET_DATE_TIME - 117)) | (1L << (FLOAT_NUMBER_RANGE - 117)) | (1L << (INT_NUMBER_RANGE - 117)) | (1L << (DATE_TIME_RANGE - 117)) | (1L << (UUID - 117)) | (1L << (ENUM - 117)))) != 0)) {
					{
					setState(694);
					((TraverseOrderConstraintListArgsContext)_localctx).traversalMode = valueToken();
					setState(695);
					match(ARGS_DELIMITER);
					}
				}

				setState(699);
				((TraverseOrderConstraintListArgsContext)_localctx).orderConstraint = orderConstraint();
				((TraverseOrderConstraintListArgsContext)_localctx).constraints.add(((TraverseOrderConstraintListArgsContext)_localctx).orderConstraint);
				setState(704);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,34,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(700);
						match(ARGS_DELIMITER);
						setState(701);
						((TraverseOrderConstraintListArgsContext)_localctx).orderConstraint = orderConstraint();
						((TraverseOrderConstraintListArgsContext)_localctx).constraints.add(((TraverseOrderConstraintListArgsContext)_localctx).orderConstraint);
						}
						}
					}
					setState(706);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,34,_ctx);
				}
				}
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
		enterRule(_localctx, 48, RULE_orderConstraintListArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(711);
			argsOpening();
			setState(712);
			((OrderConstraintListArgsContext)_localctx).orderConstraint = orderConstraint();
			((OrderConstraintListArgsContext)_localctx).constraints.add(((OrderConstraintListArgsContext)_localctx).orderConstraint);
			setState(717);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,36,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(713);
					match(ARGS_DELIMITER);
					setState(714);
					((OrderConstraintListArgsContext)_localctx).orderConstraint = orderConstraint();
					((OrderConstraintListArgsContext)_localctx).constraints.add(((OrderConstraintListArgsContext)_localctx).orderConstraint);
					}
					}
				}
				setState(719);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,36,_ctx);
			}
			setState(720);
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
		enterRule(_localctx, 50, RULE_requireConstraintArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(722);
			argsOpening();
			setState(723);
			((RequireConstraintArgsContext)_localctx).requirement = requireConstraint();
			setState(724);
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
		enterRule(_localctx, 52, RULE_requireConstraintListArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(726);
			argsOpening();
			setState(727);
			((RequireConstraintListArgsContext)_localctx).requireConstraint = requireConstraint();
			((RequireConstraintListArgsContext)_localctx).requirements.add(((RequireConstraintListArgsContext)_localctx).requireConstraint);
			setState(732);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,37,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(728);
					match(ARGS_DELIMITER);
					setState(729);
					((RequireConstraintListArgsContext)_localctx).requireConstraint = requireConstraint();
					((RequireConstraintListArgsContext)_localctx).requirements.add(((RequireConstraintListArgsContext)_localctx).requireConstraint);
					}
					}
				}
				setState(734);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,37,_ctx);
			}
			setState(735);
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
		enterRule(_localctx, 54, RULE_classifierArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(737);
			argsOpening();
			setState(738);
			((ClassifierArgsContext)_localctx).classifier = valueToken();
			setState(739);
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
		enterRule(_localctx, 56, RULE_classifierWithValueArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(741);
			argsOpening();
			setState(742);
			((ClassifierWithValueArgsContext)_localctx).classifier = valueToken();
			setState(743);
			match(ARGS_DELIMITER);
			setState(744);
			((ClassifierWithValueArgsContext)_localctx).value = valueToken();
			setState(745);
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
		enterRule(_localctx, 58, RULE_classifierWithOptionalValueArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(747);
			argsOpening();
			setState(748);
			((ClassifierWithOptionalValueArgsContext)_localctx).classifier = valueToken();
			setState(751);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,38,_ctx) ) {
			case 1:
				{
				setState(749);
				match(ARGS_DELIMITER);
				setState(750);
				((ClassifierWithOptionalValueArgsContext)_localctx).value = valueToken();
				}
				break;
			}
			setState(753);
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
		enterRule(_localctx, 60, RULE_classifierWithValueListArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(755);
			argsOpening();
			setState(756);
			((ClassifierWithValueListArgsContext)_localctx).classifier = valueToken();
			setState(757);
			match(ARGS_DELIMITER);
			setState(758);
			((ClassifierWithValueListArgsContext)_localctx).values = variadicValueTokens();
			setState(759);
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
		enterRule(_localctx, 62, RULE_classifierWithOptionalValueListArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(761);
			argsOpening();
			setState(762);
			((ClassifierWithOptionalValueListArgsContext)_localctx).classifier = valueToken();
			setState(765);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,39,_ctx) ) {
			case 1:
				{
				setState(763);
				match(ARGS_DELIMITER);
				setState(764);
				((ClassifierWithOptionalValueListArgsContext)_localctx).values = variadicValueTokens();
				}
				break;
			}
			setState(767);
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
		enterRule(_localctx, 64, RULE_classifierWithBetweenValuesArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(769);
			argsOpening();
			setState(770);
			((ClassifierWithBetweenValuesArgsContext)_localctx).classifier = valueToken();
			setState(771);
			match(ARGS_DELIMITER);
			setState(772);
			((ClassifierWithBetweenValuesArgsContext)_localctx).valueFrom = valueToken();
			setState(773);
			match(ARGS_DELIMITER);
			setState(774);
			((ClassifierWithBetweenValuesArgsContext)_localctx).valueTo = valueToken();
			setState(775);
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
		enterRule(_localctx, 66, RULE_valueArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(777);
			argsOpening();
			setState(778);
			((ValueArgsContext)_localctx).value = valueToken();
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
		enterRule(_localctx, 68, RULE_valueListArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(781);
			argsOpening();
			setState(782);
			((ValueListArgsContext)_localctx).values = variadicValueTokens();
			setState(783);
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
		enterRule(_localctx, 70, RULE_betweenValuesArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(785);
			argsOpening();
			setState(786);
			((BetweenValuesArgsContext)_localctx).valueFrom = valueToken();
			setState(787);
			match(ARGS_DELIMITER);
			setState(788);
			((BetweenValuesArgsContext)_localctx).valueTo = valueToken();
			setState(789);
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
		enterRule(_localctx, 72, RULE_classifierListArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(791);
			argsOpening();
			setState(792);
			((ClassifierListArgsContext)_localctx).classifiers = variadicValueTokens();
			setState(793);
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
		enterRule(_localctx, 74, RULE_classifierWithFilterConstraintArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(795);
			argsOpening();
			setState(796);
			((ClassifierWithFilterConstraintArgsContext)_localctx).classifier = valueToken();
			setState(797);
			match(ARGS_DELIMITER);
			setState(798);
			((ClassifierWithFilterConstraintArgsContext)_localctx).filter = filterConstraint();
			setState(799);
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

	public static class ClassifierWithTwoFilterConstraintArgsContext extends ParserRuleContext {
		public ValueTokenContext classifier;
		public FilterConstraintContext filter1;
		public FilterConstraintContext filter2;
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
		public ClassifierWithTwoFilterConstraintArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_classifierWithTwoFilterConstraintArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterClassifierWithTwoFilterConstraintArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitClassifierWithTwoFilterConstraintArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitClassifierWithTwoFilterConstraintArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ClassifierWithTwoFilterConstraintArgsContext classifierWithTwoFilterConstraintArgs() throws RecognitionException {
		ClassifierWithTwoFilterConstraintArgsContext _localctx = new ClassifierWithTwoFilterConstraintArgsContext(_ctx, getState());
		enterRule(_localctx, 76, RULE_classifierWithTwoFilterConstraintArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(801);
			argsOpening();
			setState(802);
			((ClassifierWithTwoFilterConstraintArgsContext)_localctx).classifier = valueToken();
			setState(803);
			match(ARGS_DELIMITER);
			setState(804);
			((ClassifierWithTwoFilterConstraintArgsContext)_localctx).filter1 = filterConstraint();
			setState(807);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,40,_ctx) ) {
			case 1:
				{
				setState(805);
				match(ARGS_DELIMITER);
				setState(806);
				((ClassifierWithTwoFilterConstraintArgsContext)_localctx).filter2 = filterConstraint();
				}
				break;
			}
			setState(809);
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

	public static class FacetGroupRelationArgsContext extends ParserRuleContext {
		public ValueTokenContext classifier;
		public ValueTokenContext facetGroupRelationLevel;
		public FilterConstraintContext filter;
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
		public FilterConstraintContext filterConstraint() {
			return getRuleContext(FilterConstraintContext.class,0);
		}
		public FacetGroupRelationArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_facetGroupRelationArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetGroupRelationArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetGroupRelationArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetGroupRelationArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FacetGroupRelationArgsContext facetGroupRelationArgs() throws RecognitionException {
		FacetGroupRelationArgsContext _localctx = new FacetGroupRelationArgsContext(_ctx, getState());
		enterRule(_localctx, 78, RULE_facetGroupRelationArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(811);
			argsOpening();
			setState(812);
			((FacetGroupRelationArgsContext)_localctx).classifier = valueToken();
			setState(815);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,41,_ctx) ) {
			case 1:
				{
				setState(813);
				match(ARGS_DELIMITER);
				setState(814);
				((FacetGroupRelationArgsContext)_localctx).facetGroupRelationLevel = valueToken();
				}
				break;
			}
			setState(819);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,42,_ctx) ) {
			case 1:
				{
				setState(817);
				match(ARGS_DELIMITER);
				setState(818);
				((FacetGroupRelationArgsContext)_localctx).filter = filterConstraint();
				}
				break;
			}
			setState(821);
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

	public static class FacetCalculationRulesArgsContext extends ParserRuleContext {
		public ValueTokenContext facetsWithSameGroup;
		public ValueTokenContext facetsWithDifferentGroups;
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
		public FacetCalculationRulesArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_facetCalculationRulesArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterFacetCalculationRulesArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitFacetCalculationRulesArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitFacetCalculationRulesArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final FacetCalculationRulesArgsContext facetCalculationRulesArgs() throws RecognitionException {
		FacetCalculationRulesArgsContext _localctx = new FacetCalculationRulesArgsContext(_ctx, getState());
		enterRule(_localctx, 80, RULE_facetCalculationRulesArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(823);
			argsOpening();
			setState(824);
			((FacetCalculationRulesArgsContext)_localctx).facetsWithSameGroup = valueToken();
			setState(825);
			match(ARGS_DELIMITER);
			setState(826);
			((FacetCalculationRulesArgsContext)_localctx).facetsWithDifferentGroups = valueToken();
			setState(827);
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
		enterRule(_localctx, 82, RULE_classifierWithOrderConstraintListArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(829);
			argsOpening();
			setState(830);
			((ClassifierWithOrderConstraintListArgsContext)_localctx).classifier = valueToken();
			setState(833);
			_errHandler.sync(this);
			_alt = 1;
			do {
				switch (_alt) {
				case 1:
					{
					{
					setState(831);
					match(ARGS_DELIMITER);
					setState(832);
					((ClassifierWithOrderConstraintListArgsContext)_localctx).orderConstraint = orderConstraint();
					((ClassifierWithOrderConstraintListArgsContext)_localctx).constrains.add(((ClassifierWithOrderConstraintListArgsContext)_localctx).orderConstraint);
					}
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				setState(835);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,43,_ctx);
			} while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER );
			setState(837);
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
		enterRule(_localctx, 84, RULE_hierarchyWithinConstraintArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(839);
			argsOpening();
			setState(840);
			((HierarchyWithinConstraintArgsContext)_localctx).classifier = valueToken();
			setState(841);
			match(ARGS_DELIMITER);
			setState(842);
			((HierarchyWithinConstraintArgsContext)_localctx).ofParent = filterConstraint();
			setState(847);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,44,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(843);
					match(ARGS_DELIMITER);
					setState(844);
					((HierarchyWithinConstraintArgsContext)_localctx).filterConstraint = filterConstraint();
					((HierarchyWithinConstraintArgsContext)_localctx).constrains.add(((HierarchyWithinConstraintArgsContext)_localctx).filterConstraint);
					}
					}
				}
				setState(849);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,44,_ctx);
			}
			setState(850);
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
		enterRule(_localctx, 86, RULE_hierarchyWithinSelfConstraintArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(852);
			argsOpening();
			setState(853);
			((HierarchyWithinSelfConstraintArgsContext)_localctx).ofParent = filterConstraint();
			setState(858);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,45,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(854);
					match(ARGS_DELIMITER);
					setState(855);
					((HierarchyWithinSelfConstraintArgsContext)_localctx).filterConstraint = filterConstraint();
					((HierarchyWithinSelfConstraintArgsContext)_localctx).constrains.add(((HierarchyWithinSelfConstraintArgsContext)_localctx).filterConstraint);
					}
					}
				}
				setState(860);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,45,_ctx);
			}
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
		enterRule(_localctx, 88, RULE_hierarchyWithinRootConstraintArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(863);
			argsOpening();
			setState(873);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,47,_ctx) ) {
			case 1:
				{
				setState(864);
				((HierarchyWithinRootConstraintArgsContext)_localctx).classifier = valueToken();
				}
				break;
			case 2:
				{
				{
				setState(865);
				((HierarchyWithinRootConstraintArgsContext)_localctx).classifier = valueToken();
				setState(870);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,46,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(866);
						match(ARGS_DELIMITER);
						setState(867);
						((HierarchyWithinRootConstraintArgsContext)_localctx).filterConstraint = filterConstraint();
						((HierarchyWithinRootConstraintArgsContext)_localctx).constrains.add(((HierarchyWithinRootConstraintArgsContext)_localctx).filterConstraint);
						}
						}
					}
					setState(872);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,46,_ctx);
				}
				}
				}
				break;
			}
			setState(875);
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
		enterRule(_localctx, 90, RULE_hierarchyWithinRootSelfConstraintArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(877);
			argsOpening();
			setState(878);
			((HierarchyWithinRootSelfConstraintArgsContext)_localctx).filterConstraint = filterConstraint();
			((HierarchyWithinRootSelfConstraintArgsContext)_localctx).constrains.add(((HierarchyWithinRootSelfConstraintArgsContext)_localctx).filterConstraint);
			setState(883);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,48,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(879);
					match(ARGS_DELIMITER);
					setState(880);
					((HierarchyWithinRootSelfConstraintArgsContext)_localctx).filterConstraint = filterConstraint();
					((HierarchyWithinRootSelfConstraintArgsContext)_localctx).constrains.add(((HierarchyWithinRootSelfConstraintArgsContext)_localctx).filterConstraint);
					}
					}
				}
				setState(885);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,48,_ctx);
			}
			setState(886);
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
		enterRule(_localctx, 92, RULE_attributeSetExactArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(888);
			argsOpening();
			setState(889);
			((AttributeSetExactArgsContext)_localctx).attributeName = valueToken();
			setState(890);
			match(ARGS_DELIMITER);
			setState(891);
			((AttributeSetExactArgsContext)_localctx).attributeValues = variadicValueTokens();
			setState(892);
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
		public RequireConstraintContext constrain;
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
		public RequireConstraintContext requireConstraint() {
			return getRuleContext(RequireConstraintContext.class,0);
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
		enterRule(_localctx, 94, RULE_pageConstraintArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(894);
			argsOpening();
			setState(895);
			((PageConstraintArgsContext)_localctx).pageNumber = valueToken();
			setState(896);
			match(ARGS_DELIMITER);
			setState(897);
			((PageConstraintArgsContext)_localctx).pageSize = valueToken();
			setState(900);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,49,_ctx) ) {
			case 1:
				{
				setState(898);
				match(ARGS_DELIMITER);
				setState(899);
				((PageConstraintArgsContext)_localctx).constrain = requireConstraint();
				}
				break;
			}
			setState(902);
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
		enterRule(_localctx, 96, RULE_stripConstraintArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(904);
			argsOpening();
			setState(905);
			((StripConstraintArgsContext)_localctx).offset = valueToken();
			setState(906);
			match(ARGS_DELIMITER);
			setState(907);
			((StripConstraintArgsContext)_localctx).limit = valueToken();
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
		enterRule(_localctx, 98, RULE_priceContentArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(910);
			argsOpening();
			setState(911);
			((PriceContentArgsContext)_localctx).contentMode = valueToken();
			setState(914);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,50,_ctx) ) {
			case 1:
				{
				setState(912);
				match(ARGS_DELIMITER);
				setState(913);
				((PriceContentArgsContext)_localctx).priceLists = variadicValueTokens();
				}
				break;
			}
			setState(916);
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
		enterRule(_localctx, 100, RULE_singleRefReferenceContent1Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(918);
			argsOpening();
			setState(922);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,51,_ctx) ) {
			case 1:
				{
				setState(919);
				((SingleRefReferenceContent1ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(920);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(924);
			((SingleRefReferenceContent1ArgsContext)_localctx).classifier = valueToken();
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

	public static class SingleRefReferenceContent2ArgsContext extends ParserRuleContext {
		public ValueTokenContext managedReferencesBehaviour;
		public ValueTokenContext classifier;
		public RequireConstraintContext entityRequirement;
		public RequireConstraintContext groupEntityRequirement;
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
		enterRule(_localctx, 102, RULE_singleRefReferenceContent2Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(927);
			argsOpening();
			setState(931);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,52,_ctx) ) {
			case 1:
				{
				setState(928);
				((SingleRefReferenceContent2ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(929);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(933);
			((SingleRefReferenceContent2ArgsContext)_localctx).classifier = valueToken();
			setState(934);
			match(ARGS_DELIMITER);
			setState(935);
			((SingleRefReferenceContent2ArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(936);
			match(ARGS_DELIMITER);
			setState(937);
			((SingleRefReferenceContent2ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(940);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,53,_ctx) ) {
			case 1:
				{
				setState(938);
				match(ARGS_DELIMITER);
				setState(939);
				((SingleRefReferenceContent2ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(942);
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
		enterRule(_localctx, 104, RULE_singleRefReferenceContent3Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(944);
			argsOpening();
			setState(948);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,54,_ctx) ) {
			case 1:
				{
				setState(945);
				((SingleRefReferenceContent3ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(946);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(950);
			((SingleRefReferenceContent3ArgsContext)_localctx).classifier = valueToken();
			setState(951);
			match(ARGS_DELIMITER);
			setState(952);
			((SingleRefReferenceContent3ArgsContext)_localctx).filterBy = filterConstraint();
			setState(955);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,55,_ctx) ) {
			case 1:
				{
				setState(953);
				match(ARGS_DELIMITER);
				setState(954);
				((SingleRefReferenceContent3ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(957);
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
		public RequireConstraintContext entityRequirement;
		public RequireConstraintContext groupEntityRequirement;
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
		enterRule(_localctx, 106, RULE_singleRefReferenceContent4Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(959);
			argsOpening();
			setState(963);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,56,_ctx) ) {
			case 1:
				{
				setState(960);
				((SingleRefReferenceContent4ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(961);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(965);
			((SingleRefReferenceContent4ArgsContext)_localctx).classifier = valueToken();
			setState(966);
			match(ARGS_DELIMITER);
			setState(967);
			((SingleRefReferenceContent4ArgsContext)_localctx).filterBy = filterConstraint();
			setState(968);
			match(ARGS_DELIMITER);
			setState(969);
			((SingleRefReferenceContent4ArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(970);
			match(ARGS_DELIMITER);
			setState(971);
			((SingleRefReferenceContent4ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(974);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,57,_ctx) ) {
			case 1:
				{
				setState(972);
				match(ARGS_DELIMITER);
				setState(973);
				((SingleRefReferenceContent4ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(976);
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
		enterRule(_localctx, 108, RULE_singleRefReferenceContent5Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(978);
			argsOpening();
			setState(982);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,58,_ctx) ) {
			case 1:
				{
				setState(979);
				((SingleRefReferenceContent5ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(980);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(984);
			((SingleRefReferenceContent5ArgsContext)_localctx).classifier = valueToken();
			setState(985);
			match(ARGS_DELIMITER);
			setState(986);
			((SingleRefReferenceContent5ArgsContext)_localctx).orderBy = orderConstraint();
			setState(989);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,59,_ctx) ) {
			case 1:
				{
				setState(987);
				match(ARGS_DELIMITER);
				setState(988);
				((SingleRefReferenceContent5ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(991);
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
		public RequireConstraintContext entityRequirement;
		public RequireConstraintContext groupEntityRequirement;
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
		enterRule(_localctx, 110, RULE_singleRefReferenceContent6Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(993);
			argsOpening();
			setState(997);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,60,_ctx) ) {
			case 1:
				{
				setState(994);
				((SingleRefReferenceContent6ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(995);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(999);
			((SingleRefReferenceContent6ArgsContext)_localctx).classifier = valueToken();
			setState(1000);
			match(ARGS_DELIMITER);
			setState(1001);
			((SingleRefReferenceContent6ArgsContext)_localctx).orderBy = orderConstraint();
			setState(1002);
			match(ARGS_DELIMITER);
			setState(1003);
			((SingleRefReferenceContent6ArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(1004);
			match(ARGS_DELIMITER);
			setState(1005);
			((SingleRefReferenceContent6ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(1008);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,61,_ctx) ) {
			case 1:
				{
				setState(1006);
				match(ARGS_DELIMITER);
				setState(1007);
				((SingleRefReferenceContent6ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1010);
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
		enterRule(_localctx, 112, RULE_singleRefReferenceContent7Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1012);
			argsOpening();
			setState(1016);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,62,_ctx) ) {
			case 1:
				{
				setState(1013);
				((SingleRefReferenceContent7ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1014);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1018);
			((SingleRefReferenceContent7ArgsContext)_localctx).classifier = valueToken();
			setState(1019);
			match(ARGS_DELIMITER);
			setState(1020);
			((SingleRefReferenceContent7ArgsContext)_localctx).filterBy = filterConstraint();
			setState(1021);
			match(ARGS_DELIMITER);
			setState(1022);
			((SingleRefReferenceContent7ArgsContext)_localctx).orderBy = orderConstraint();
			setState(1025);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,63,_ctx) ) {
			case 1:
				{
				setState(1023);
				match(ARGS_DELIMITER);
				setState(1024);
				((SingleRefReferenceContent7ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1027);
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
		public RequireConstraintContext entityRequirement;
		public RequireConstraintContext groupEntityRequirement;
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
		enterRule(_localctx, 114, RULE_singleRefReferenceContent8Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1029);
			argsOpening();
			setState(1033);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,64,_ctx) ) {
			case 1:
				{
				setState(1030);
				((SingleRefReferenceContent8ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1031);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1035);
			((SingleRefReferenceContent8ArgsContext)_localctx).classifier = valueToken();
			setState(1036);
			match(ARGS_DELIMITER);
			setState(1037);
			((SingleRefReferenceContent8ArgsContext)_localctx).filterBy = filterConstraint();
			setState(1038);
			match(ARGS_DELIMITER);
			setState(1039);
			((SingleRefReferenceContent8ArgsContext)_localctx).orderBy = orderConstraint();
			setState(1040);
			match(ARGS_DELIMITER);
			setState(1041);
			((SingleRefReferenceContent8ArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(1042);
			match(ARGS_DELIMITER);
			setState(1043);
			((SingleRefReferenceContent8ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(1046);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,65,_ctx) ) {
			case 1:
				{
				setState(1044);
				match(ARGS_DELIMITER);
				setState(1045);
				((SingleRefReferenceContent8ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1048);
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

	public static class SingleRefReferenceContentWithAttributes0ArgsContext extends ParserRuleContext {
		public ValueTokenContext managedReferencesBehaviour;
		public ValueTokenContext classifier;
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
		public RequireConstraintContext requireConstraint() {
			return getRuleContext(RequireConstraintContext.class,0);
		}
		public SingleRefReferenceContentWithAttributes0ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_singleRefReferenceContentWithAttributes0Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContentWithAttributes0Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContentWithAttributes0Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContentWithAttributes0Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SingleRefReferenceContentWithAttributes0ArgsContext singleRefReferenceContentWithAttributes0Args() throws RecognitionException {
		SingleRefReferenceContentWithAttributes0ArgsContext _localctx = new SingleRefReferenceContentWithAttributes0ArgsContext(_ctx, getState());
		enterRule(_localctx, 116, RULE_singleRefReferenceContentWithAttributes0Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1050);
			argsOpening();
			setState(1054);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,66,_ctx) ) {
			case 1:
				{
				setState(1051);
				((SingleRefReferenceContentWithAttributes0ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1052);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1056);
			((SingleRefReferenceContentWithAttributes0ArgsContext)_localctx).classifier = valueToken();
			setState(1057);
			match(ARGS_DELIMITER);
			setState(1058);
			((SingleRefReferenceContentWithAttributes0ArgsContext)_localctx).requirement = requireConstraint();
			setState(1059);
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
		enterRule(_localctx, 118, RULE_singleRefReferenceContentWithAttributes1Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1061);
			argsOpening();
			setState(1065);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,67,_ctx) ) {
			case 1:
				{
				setState(1062);
				((SingleRefReferenceContentWithAttributes1ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1063);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1067);
			((SingleRefReferenceContentWithAttributes1ArgsContext)_localctx).classifier = valueToken();
			setState(1068);
			match(ARGS_DELIMITER);
			setState(1069);
			((SingleRefReferenceContentWithAttributes1ArgsContext)_localctx).requirement1 = requireConstraint();
			setState(1070);
			match(ARGS_DELIMITER);
			setState(1071);
			((SingleRefReferenceContentWithAttributes1ArgsContext)_localctx).requirement2 = requireConstraint();
			setState(1072);
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
		public RequireConstraintContext entityRequirement;
		public RequireConstraintContext groupEntityRequirement;
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
		enterRule(_localctx, 120, RULE_singleRefReferenceContentWithAttributes2Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1074);
			argsOpening();
			setState(1078);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,68,_ctx) ) {
			case 1:
				{
				setState(1075);
				((SingleRefReferenceContentWithAttributes2ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1076);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1080);
			((SingleRefReferenceContentWithAttributes2ArgsContext)_localctx).classifier = valueToken();
			setState(1081);
			match(ARGS_DELIMITER);
			setState(1082);
			((SingleRefReferenceContentWithAttributes2ArgsContext)_localctx).attributeContent = requireConstraint();
			setState(1083);
			match(ARGS_DELIMITER);
			setState(1084);
			((SingleRefReferenceContentWithAttributes2ArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(1085);
			match(ARGS_DELIMITER);
			setState(1086);
			((SingleRefReferenceContentWithAttributes2ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(1089);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,69,_ctx) ) {
			case 1:
				{
				setState(1087);
				match(ARGS_DELIMITER);
				setState(1088);
				((SingleRefReferenceContentWithAttributes2ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1091);
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
		enterRule(_localctx, 122, RULE_singleRefReferenceContentWithAttributes3Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1093);
			argsOpening();
			setState(1097);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,70,_ctx) ) {
			case 1:
				{
				setState(1094);
				((SingleRefReferenceContentWithAttributes3ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1095);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1099);
			((SingleRefReferenceContentWithAttributes3ArgsContext)_localctx).classifier = valueToken();
			setState(1100);
			match(ARGS_DELIMITER);
			setState(1101);
			((SingleRefReferenceContentWithAttributes3ArgsContext)_localctx).filterBy = filterConstraint();
			setState(1102);
			match(ARGS_DELIMITER);
			setState(1103);
			((SingleRefReferenceContentWithAttributes3ArgsContext)_localctx).requirement1 = requireConstraint();
			setState(1104);
			match(ARGS_DELIMITER);
			setState(1105);
			((SingleRefReferenceContentWithAttributes3ArgsContext)_localctx).requirement2 = requireConstraint();
			setState(1106);
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
		public RequireConstraintContext entityRequirement;
		public RequireConstraintContext groupEntityRequirement;
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
		enterRule(_localctx, 124, RULE_singleRefReferenceContentWithAttributes4Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1108);
			argsOpening();
			setState(1112);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,71,_ctx) ) {
			case 1:
				{
				setState(1109);
				((SingleRefReferenceContentWithAttributes4ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1110);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1114);
			((SingleRefReferenceContentWithAttributes4ArgsContext)_localctx).classifier = valueToken();
			setState(1115);
			match(ARGS_DELIMITER);
			setState(1116);
			((SingleRefReferenceContentWithAttributes4ArgsContext)_localctx).filterBy = filterConstraint();
			setState(1117);
			match(ARGS_DELIMITER);
			setState(1118);
			((SingleRefReferenceContentWithAttributes4ArgsContext)_localctx).attributeContent = requireConstraint();
			setState(1119);
			match(ARGS_DELIMITER);
			setState(1120);
			((SingleRefReferenceContentWithAttributes4ArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(1121);
			match(ARGS_DELIMITER);
			setState(1122);
			((SingleRefReferenceContentWithAttributes4ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(1125);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,72,_ctx) ) {
			case 1:
				{
				setState(1123);
				match(ARGS_DELIMITER);
				setState(1124);
				((SingleRefReferenceContentWithAttributes4ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1127);
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
		enterRule(_localctx, 126, RULE_singleRefReferenceContentWithAttributes5Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1129);
			argsOpening();
			setState(1133);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,73,_ctx) ) {
			case 1:
				{
				setState(1130);
				((SingleRefReferenceContentWithAttributes5ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1131);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1135);
			((SingleRefReferenceContentWithAttributes5ArgsContext)_localctx).classifier = valueToken();
			setState(1136);
			match(ARGS_DELIMITER);
			setState(1137);
			((SingleRefReferenceContentWithAttributes5ArgsContext)_localctx).orderBy = orderConstraint();
			setState(1138);
			match(ARGS_DELIMITER);
			setState(1139);
			((SingleRefReferenceContentWithAttributes5ArgsContext)_localctx).requirement1 = requireConstraint();
			setState(1140);
			match(ARGS_DELIMITER);
			setState(1141);
			((SingleRefReferenceContentWithAttributes5ArgsContext)_localctx).requirement2 = requireConstraint();
			setState(1142);
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
		public RequireConstraintContext entityRequirement;
		public RequireConstraintContext groupEntityRequirement;
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
		enterRule(_localctx, 128, RULE_singleRefReferenceContentWithAttributes6Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1144);
			argsOpening();
			setState(1148);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,74,_ctx) ) {
			case 1:
				{
				setState(1145);
				((SingleRefReferenceContentWithAttributes6ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1146);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1150);
			((SingleRefReferenceContentWithAttributes6ArgsContext)_localctx).classifier = valueToken();
			setState(1151);
			match(ARGS_DELIMITER);
			setState(1152);
			((SingleRefReferenceContentWithAttributes6ArgsContext)_localctx).orderBy = orderConstraint();
			setState(1153);
			match(ARGS_DELIMITER);
			setState(1154);
			((SingleRefReferenceContentWithAttributes6ArgsContext)_localctx).attributeContent = requireConstraint();
			setState(1155);
			match(ARGS_DELIMITER);
			setState(1156);
			((SingleRefReferenceContentWithAttributes6ArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(1157);
			match(ARGS_DELIMITER);
			setState(1158);
			((SingleRefReferenceContentWithAttributes6ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(1161);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,75,_ctx) ) {
			case 1:
				{
				setState(1159);
				match(ARGS_DELIMITER);
				setState(1160);
				((SingleRefReferenceContentWithAttributes6ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1163);
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
		enterRule(_localctx, 130, RULE_singleRefReferenceContentWithAttributes7Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1165);
			argsOpening();
			setState(1169);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,76,_ctx) ) {
			case 1:
				{
				setState(1166);
				((SingleRefReferenceContentWithAttributes7ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1167);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1171);
			((SingleRefReferenceContentWithAttributes7ArgsContext)_localctx).classifier = valueToken();
			setState(1172);
			match(ARGS_DELIMITER);
			setState(1173);
			((SingleRefReferenceContentWithAttributes7ArgsContext)_localctx).filterBy = filterConstraint();
			setState(1174);
			match(ARGS_DELIMITER);
			setState(1175);
			((SingleRefReferenceContentWithAttributes7ArgsContext)_localctx).orderBy = orderConstraint();
			setState(1176);
			match(ARGS_DELIMITER);
			setState(1177);
			((SingleRefReferenceContentWithAttributes7ArgsContext)_localctx).requirement1 = requireConstraint();
			setState(1178);
			match(ARGS_DELIMITER);
			setState(1179);
			((SingleRefReferenceContentWithAttributes7ArgsContext)_localctx).requirement2 = requireConstraint();
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

	public static class SingleRefReferenceContentWithAttributes8ArgsContext extends ParserRuleContext {
		public ValueTokenContext managedReferencesBehaviour;
		public ValueTokenContext classifier;
		public FilterConstraintContext filterBy;
		public OrderConstraintContext orderBy;
		public RequireConstraintContext attributeContent;
		public RequireConstraintContext entityRequirement;
		public RequireConstraintContext groupEntityRequirement;
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
		enterRule(_localctx, 132, RULE_singleRefReferenceContentWithAttributes8Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1182);
			argsOpening();
			setState(1186);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,77,_ctx) ) {
			case 1:
				{
				setState(1183);
				((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1184);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1188);
			((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).classifier = valueToken();
			setState(1189);
			match(ARGS_DELIMITER);
			setState(1190);
			((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).filterBy = filterConstraint();
			setState(1191);
			match(ARGS_DELIMITER);
			setState(1192);
			((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).orderBy = orderConstraint();
			setState(1193);
			match(ARGS_DELIMITER);
			setState(1194);
			((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).attributeContent = requireConstraint();
			setState(1195);
			match(ARGS_DELIMITER);
			setState(1196);
			((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(1197);
			match(ARGS_DELIMITER);
			setState(1198);
			((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(1201);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,78,_ctx) ) {
			case 1:
				{
				setState(1199);
				match(ARGS_DELIMITER);
				setState(1200);
				((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1203);
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
		public RequireConstraintContext entityRequirement;
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
		enterRule(_localctx, 134, RULE_multipleRefsReferenceContentArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1205);
			argsOpening();
			setState(1232);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,82,_ctx) ) {
			case 1:
				{
				{
				setState(1209);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,79,_ctx) ) {
				case 1:
					{
					setState(1206);
					((MultipleRefsReferenceContentArgsContext)_localctx).managedReferencesBehaviour = valueToken();
					setState(1207);
					match(ARGS_DELIMITER);
					}
					break;
				}
				setState(1211);
				((MultipleRefsReferenceContentArgsContext)_localctx).classifiers = variadicValueTokens();
				}
				}
				break;
			case 2:
				{
				{
				setState(1215);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,80,_ctx) ) {
				case 1:
					{
					setState(1212);
					((MultipleRefsReferenceContentArgsContext)_localctx).managedReferencesBehaviour = valueToken();
					setState(1213);
					match(ARGS_DELIMITER);
					}
					break;
				}
				setState(1217);
				((MultipleRefsReferenceContentArgsContext)_localctx).classifiers = variadicValueTokens();
				setState(1218);
				match(ARGS_DELIMITER);
				setState(1219);
				((MultipleRefsReferenceContentArgsContext)_localctx).requirement = requireConstraint();
				}
				}
				break;
			case 3:
				{
				{
				setState(1224);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,81,_ctx) ) {
				case 1:
					{
					setState(1221);
					((MultipleRefsReferenceContentArgsContext)_localctx).managedReferencesBehaviour = valueToken();
					setState(1222);
					match(ARGS_DELIMITER);
					}
					break;
				}
				setState(1226);
				((MultipleRefsReferenceContentArgsContext)_localctx).classifiers = variadicValueTokens();
				setState(1227);
				match(ARGS_DELIMITER);
				setState(1228);
				((MultipleRefsReferenceContentArgsContext)_localctx).entityRequirement = requireConstraint();
				setState(1229);
				match(ARGS_DELIMITER);
				setState(1230);
				((MultipleRefsReferenceContentArgsContext)_localctx).groupEntityRequirement = requireConstraint();
				}
				}
				break;
			}
			setState(1234);
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
		public RequireConstraintContext entityRequirement;
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
		enterRule(_localctx, 136, RULE_allRefsReferenceContentArgs);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1236);
			argsOpening();
			setState(1253);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,85,_ctx) ) {
			case 1:
				{
				{
				setState(1237);
				((AllRefsReferenceContentArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				}
				}
				break;
			case 2:
				{
				{
				setState(1241);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (((((_la - 117)) & ~0x3f) == 0 && ((1L << (_la - 117)) & ((1L << (POSITIONAL_PARAMETER - 117)) | (1L << (NAMED_PARAMETER - 117)) | (1L << (STRING - 117)) | (1L << (INT - 117)) | (1L << (FLOAT - 117)) | (1L << (BOOLEAN - 117)) | (1L << (DATE - 117)) | (1L << (TIME - 117)) | (1L << (DATE_TIME - 117)) | (1L << (OFFSET_DATE_TIME - 117)) | (1L << (FLOAT_NUMBER_RANGE - 117)) | (1L << (INT_NUMBER_RANGE - 117)) | (1L << (DATE_TIME_RANGE - 117)) | (1L << (UUID - 117)) | (1L << (ENUM - 117)))) != 0)) {
					{
					setState(1238);
					((AllRefsReferenceContentArgsContext)_localctx).managedReferencesBehaviour = valueToken();
					setState(1239);
					match(ARGS_DELIMITER);
					}
				}

				setState(1243);
				((AllRefsReferenceContentArgsContext)_localctx).requirement = requireConstraint();
				}
				}
				break;
			case 3:
				{
				{
				setState(1247);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (((((_la - 117)) & ~0x3f) == 0 && ((1L << (_la - 117)) & ((1L << (POSITIONAL_PARAMETER - 117)) | (1L << (NAMED_PARAMETER - 117)) | (1L << (STRING - 117)) | (1L << (INT - 117)) | (1L << (FLOAT - 117)) | (1L << (BOOLEAN - 117)) | (1L << (DATE - 117)) | (1L << (TIME - 117)) | (1L << (DATE_TIME - 117)) | (1L << (OFFSET_DATE_TIME - 117)) | (1L << (FLOAT_NUMBER_RANGE - 117)) | (1L << (INT_NUMBER_RANGE - 117)) | (1L << (DATE_TIME_RANGE - 117)) | (1L << (UUID - 117)) | (1L << (ENUM - 117)))) != 0)) {
					{
					setState(1244);
					((AllRefsReferenceContentArgsContext)_localctx).managedReferencesBehaviour = valueToken();
					setState(1245);
					match(ARGS_DELIMITER);
					}
				}

				setState(1249);
				((AllRefsReferenceContentArgsContext)_localctx).entityRequirement = requireConstraint();
				setState(1250);
				match(ARGS_DELIMITER);
				setState(1251);
				((AllRefsReferenceContentArgsContext)_localctx).groupEntityRequirement = requireConstraint();
				}
				}
				break;
			}
			setState(1255);
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
		enterRule(_localctx, 138, RULE_allRefsWithAttributesReferenceContent1Args);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1257);
			argsOpening();
			setState(1265);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,87,_ctx) ) {
			case 1:
				{
				{
				setState(1258);
				((AllRefsWithAttributesReferenceContent1ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				}
				}
				break;
			case 2:
				{
				setState(1262);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (((((_la - 117)) & ~0x3f) == 0 && ((1L << (_la - 117)) & ((1L << (POSITIONAL_PARAMETER - 117)) | (1L << (NAMED_PARAMETER - 117)) | (1L << (STRING - 117)) | (1L << (INT - 117)) | (1L << (FLOAT - 117)) | (1L << (BOOLEAN - 117)) | (1L << (DATE - 117)) | (1L << (TIME - 117)) | (1L << (DATE_TIME - 117)) | (1L << (OFFSET_DATE_TIME - 117)) | (1L << (FLOAT_NUMBER_RANGE - 117)) | (1L << (INT_NUMBER_RANGE - 117)) | (1L << (DATE_TIME_RANGE - 117)) | (1L << (UUID - 117)) | (1L << (ENUM - 117)))) != 0)) {
					{
					setState(1259);
					((AllRefsWithAttributesReferenceContent1ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
					setState(1260);
					match(ARGS_DELIMITER);
					}
				}

				setState(1264);
				((AllRefsWithAttributesReferenceContent1ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1267);
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
		enterRule(_localctx, 140, RULE_allRefsWithAttributesReferenceContent2Args);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1269);
			argsOpening();
			setState(1273);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (((((_la - 117)) & ~0x3f) == 0 && ((1L << (_la - 117)) & ((1L << (POSITIONAL_PARAMETER - 117)) | (1L << (NAMED_PARAMETER - 117)) | (1L << (STRING - 117)) | (1L << (INT - 117)) | (1L << (FLOAT - 117)) | (1L << (BOOLEAN - 117)) | (1L << (DATE - 117)) | (1L << (TIME - 117)) | (1L << (DATE_TIME - 117)) | (1L << (OFFSET_DATE_TIME - 117)) | (1L << (FLOAT_NUMBER_RANGE - 117)) | (1L << (INT_NUMBER_RANGE - 117)) | (1L << (DATE_TIME_RANGE - 117)) | (1L << (UUID - 117)) | (1L << (ENUM - 117)))) != 0)) {
				{
				setState(1270);
				((AllRefsWithAttributesReferenceContent2ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1271);
				match(ARGS_DELIMITER);
				}
			}

			setState(1275);
			((AllRefsWithAttributesReferenceContent2ArgsContext)_localctx).requirement1 = requireConstraint();
			setState(1276);
			match(ARGS_DELIMITER);
			setState(1277);
			((AllRefsWithAttributesReferenceContent2ArgsContext)_localctx).requirement2 = requireConstraint();
			setState(1278);
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
		public RequireConstraintContext entityRequirement;
		public RequireConstraintContext groupEntityRequirement;
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
		enterRule(_localctx, 142, RULE_allRefsWithAttributesReferenceContent3Args);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1280);
			argsOpening();
			setState(1284);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (((((_la - 117)) & ~0x3f) == 0 && ((1L << (_la - 117)) & ((1L << (POSITIONAL_PARAMETER - 117)) | (1L << (NAMED_PARAMETER - 117)) | (1L << (STRING - 117)) | (1L << (INT - 117)) | (1L << (FLOAT - 117)) | (1L << (BOOLEAN - 117)) | (1L << (DATE - 117)) | (1L << (TIME - 117)) | (1L << (DATE_TIME - 117)) | (1L << (OFFSET_DATE_TIME - 117)) | (1L << (FLOAT_NUMBER_RANGE - 117)) | (1L << (INT_NUMBER_RANGE - 117)) | (1L << (DATE_TIME_RANGE - 117)) | (1L << (UUID - 117)) | (1L << (ENUM - 117)))) != 0)) {
				{
				setState(1281);
				((AllRefsWithAttributesReferenceContent3ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1282);
				match(ARGS_DELIMITER);
				}
			}

			setState(1286);
			((AllRefsWithAttributesReferenceContent3ArgsContext)_localctx).attributeContent = requireConstraint();
			setState(1287);
			match(ARGS_DELIMITER);
			setState(1288);
			((AllRefsWithAttributesReferenceContent3ArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(1289);
			match(ARGS_DELIMITER);
			setState(1290);
			((AllRefsWithAttributesReferenceContent3ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(1293);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,90,_ctx) ) {
			case 1:
				{
				setState(1291);
				match(ARGS_DELIMITER);
				setState(1292);
				((AllRefsWithAttributesReferenceContent3ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1295);
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
		enterRule(_localctx, 144, RULE_singleRequireHierarchyContentArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1297);
			argsOpening();
			setState(1298);
			((SingleRequireHierarchyContentArgsContext)_localctx).requirement = requireConstraint();
			setState(1299);
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
		enterRule(_localctx, 146, RULE_allRequiresHierarchyContentArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1301);
			argsOpening();
			setState(1302);
			((AllRequiresHierarchyContentArgsContext)_localctx).stopAt = requireConstraint();
			setState(1303);
			match(ARGS_DELIMITER);
			setState(1304);
			((AllRequiresHierarchyContentArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(1305);
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
		enterRule(_localctx, 148, RULE_facetSummary1Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1307);
			argsOpening();
			setState(1308);
			((FacetSummary1ArgsContext)_localctx).depth = valueToken();
			setState(1309);
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
		enterRule(_localctx, 150, RULE_facetSummary2Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1311);
			argsOpening();
			setState(1312);
			((FacetSummary2ArgsContext)_localctx).depth = valueToken();
			setState(1313);
			match(ARGS_DELIMITER);
			setState(1314);
			((FacetSummary2ArgsContext)_localctx).filter = facetSummaryFilterArgs();
			setState(1317);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,91,_ctx) ) {
			case 1:
				{
				setState(1315);
				match(ARGS_DELIMITER);
				setState(1316);
				((FacetSummary2ArgsContext)_localctx).order = facetSummaryOrderArgs();
				}
				break;
			}
			setState(1321);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,92,_ctx) ) {
			case 1:
				{
				setState(1319);
				match(ARGS_DELIMITER);
				setState(1320);
				((FacetSummary2ArgsContext)_localctx).requirements = facetSummaryRequirementsArgs();
				}
				break;
			}
			setState(1323);
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
		enterRule(_localctx, 152, RULE_facetSummary3Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1325);
			argsOpening();
			setState(1326);
			((FacetSummary3ArgsContext)_localctx).depth = valueToken();
			setState(1327);
			match(ARGS_DELIMITER);
			setState(1328);
			((FacetSummary3ArgsContext)_localctx).order = facetSummaryOrderArgs();
			setState(1331);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,93,_ctx) ) {
			case 1:
				{
				setState(1329);
				match(ARGS_DELIMITER);
				setState(1330);
				((FacetSummary3ArgsContext)_localctx).requirements = facetSummaryRequirementsArgs();
				}
				break;
			}
			setState(1333);
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
		enterRule(_localctx, 154, RULE_facetSummary4Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1335);
			argsOpening();
			setState(1336);
			((FacetSummary4ArgsContext)_localctx).depth = valueToken();
			setState(1337);
			match(ARGS_DELIMITER);
			setState(1338);
			((FacetSummary4ArgsContext)_localctx).requirements = facetSummaryRequirementsArgs();
			setState(1339);
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
		enterRule(_localctx, 156, RULE_facetSummary5Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1341);
			argsOpening();
			setState(1342);
			((FacetSummary5ArgsContext)_localctx).filter = facetSummaryFilterArgs();
			setState(1345);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,94,_ctx) ) {
			case 1:
				{
				setState(1343);
				match(ARGS_DELIMITER);
				setState(1344);
				((FacetSummary5ArgsContext)_localctx).order = facetSummaryOrderArgs();
				}
				break;
			}
			setState(1349);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,95,_ctx) ) {
			case 1:
				{
				setState(1347);
				match(ARGS_DELIMITER);
				setState(1348);
				((FacetSummary5ArgsContext)_localctx).requirements = facetSummaryRequirementsArgs();
				}
				break;
			}
			setState(1351);
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
		enterRule(_localctx, 158, RULE_facetSummary6Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1353);
			argsOpening();
			setState(1354);
			((FacetSummary6ArgsContext)_localctx).order = facetSummaryOrderArgs();
			setState(1357);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,96,_ctx) ) {
			case 1:
				{
				setState(1355);
				match(ARGS_DELIMITER);
				setState(1356);
				((FacetSummary6ArgsContext)_localctx).requirements = facetSummaryRequirementsArgs();
				}
				break;
			}
			setState(1359);
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
		enterRule(_localctx, 160, RULE_facetSummary7Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1361);
			argsOpening();
			setState(1362);
			((FacetSummary7ArgsContext)_localctx).requirements = facetSummaryRequirementsArgs();
			setState(1363);
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
		enterRule(_localctx, 162, RULE_facetSummaryOfReference2Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1365);
			argsOpening();
			setState(1366);
			((FacetSummaryOfReference2ArgsContext)_localctx).referenceName = valueToken();
			setState(1369);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,97,_ctx) ) {
			case 1:
				{
				setState(1367);
				match(ARGS_DELIMITER);
				setState(1368);
				((FacetSummaryOfReference2ArgsContext)_localctx).depth = valueToken();
				}
				break;
			}
			setState(1373);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,98,_ctx) ) {
			case 1:
				{
				setState(1371);
				match(ARGS_DELIMITER);
				setState(1372);
				((FacetSummaryOfReference2ArgsContext)_localctx).filter = facetSummaryFilterArgs();
				}
				break;
			}
			setState(1377);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,99,_ctx) ) {
			case 1:
				{
				setState(1375);
				match(ARGS_DELIMITER);
				setState(1376);
				((FacetSummaryOfReference2ArgsContext)_localctx).order = facetSummaryOrderArgs();
				}
				break;
			}
			setState(1381);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,100,_ctx) ) {
			case 1:
				{
				setState(1379);
				match(ARGS_DELIMITER);
				setState(1380);
				((FacetSummaryOfReference2ArgsContext)_localctx).requirements = facetSummaryRequirementsArgs();
				}
				break;
			}
			setState(1383);
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
		enterRule(_localctx, 164, RULE_facetSummaryRequirementsArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1390);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,101,_ctx) ) {
			case 1:
				{
				{
				setState(1385);
				((FacetSummaryRequirementsArgsContext)_localctx).requirement = requireConstraint();
				}
				}
				break;
			case 2:
				{
				{
				setState(1386);
				((FacetSummaryRequirementsArgsContext)_localctx).facetEntityRequirement = requireConstraint();
				setState(1387);
				match(ARGS_DELIMITER);
				setState(1388);
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
		enterRule(_localctx, 166, RULE_facetSummaryFilterArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1397);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,102,_ctx) ) {
			case 1:
				{
				{
				setState(1392);
				((FacetSummaryFilterArgsContext)_localctx).filterBy = filterConstraint();
				}
				}
				break;
			case 2:
				{
				{
				setState(1393);
				((FacetSummaryFilterArgsContext)_localctx).filterBy = filterConstraint();
				setState(1394);
				match(ARGS_DELIMITER);
				setState(1395);
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
		enterRule(_localctx, 168, RULE_facetSummaryOrderArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1404);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,103,_ctx) ) {
			case 1:
				{
				{
				setState(1399);
				((FacetSummaryOrderArgsContext)_localctx).orderBy = orderConstraint();
				}
				}
				break;
			case 2:
				{
				{
				setState(1400);
				((FacetSummaryOrderArgsContext)_localctx).orderBy = orderConstraint();
				setState(1401);
				match(ARGS_DELIMITER);
				setState(1402);
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
		enterRule(_localctx, 170, RULE_attributeHistogramArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1406);
			argsOpening();
			setState(1407);
			((AttributeHistogramArgsContext)_localctx).requestedBucketCount = valueToken();
			setState(1408);
			match(ARGS_DELIMITER);
			setState(1409);
			((AttributeHistogramArgsContext)_localctx).values = variadicValueTokens();
			setState(1410);
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
		enterRule(_localctx, 172, RULE_priceHistogramArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1412);
			argsOpening();
			setState(1413);
			((PriceHistogramArgsContext)_localctx).requestedBucketCount = valueToken();
			setState(1416);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,104,_ctx) ) {
			case 1:
				{
				setState(1414);
				match(ARGS_DELIMITER);
				setState(1415);
				((PriceHistogramArgsContext)_localctx).behaviour = valueToken();
				}
				break;
			}
			setState(1418);
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
		enterRule(_localctx, 174, RULE_hierarchyStatisticsArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1420);
			argsOpening();
			setState(1421);
			((HierarchyStatisticsArgsContext)_localctx).settings = variadicValueTokens();
			setState(1422);
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
		enterRule(_localctx, 176, RULE_hierarchyRequireConstraintArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1424);
			argsOpening();
			setState(1425);
			((HierarchyRequireConstraintArgsContext)_localctx).outputName = valueToken();
			setState(1430);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,105,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(1426);
					match(ARGS_DELIMITER);
					setState(1427);
					((HierarchyRequireConstraintArgsContext)_localctx).requireConstraint = requireConstraint();
					((HierarchyRequireConstraintArgsContext)_localctx).requirements.add(((HierarchyRequireConstraintArgsContext)_localctx).requireConstraint);
					}
					}
				}
				setState(1432);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,105,_ctx);
			}
			setState(1433);
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
		enterRule(_localctx, 178, RULE_hierarchyFromNodeArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1435);
			argsOpening();
			setState(1436);
			((HierarchyFromNodeArgsContext)_localctx).outputName = valueToken();
			setState(1437);
			match(ARGS_DELIMITER);
			setState(1438);
			((HierarchyFromNodeArgsContext)_localctx).node = requireConstraint();
			setState(1443);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,106,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(1439);
					match(ARGS_DELIMITER);
					setState(1440);
					((HierarchyFromNodeArgsContext)_localctx).requireConstraint = requireConstraint();
					((HierarchyFromNodeArgsContext)_localctx).requirements.add(((HierarchyFromNodeArgsContext)_localctx).requireConstraint);
					}
					}
				}
				setState(1445);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,106,_ctx);
			}
			setState(1446);
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
		enterRule(_localctx, 180, RULE_fullHierarchyOfSelfArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1448);
			argsOpening();
			setState(1449);
			((FullHierarchyOfSelfArgsContext)_localctx).orderBy = orderConstraint();
			setState(1452);
			_errHandler.sync(this);
			_alt = 1;
			do {
				switch (_alt) {
				case 1:
					{
					{
					setState(1450);
					match(ARGS_DELIMITER);
					setState(1451);
					((FullHierarchyOfSelfArgsContext)_localctx).requireConstraint = requireConstraint();
					((FullHierarchyOfSelfArgsContext)_localctx).requirements.add(((FullHierarchyOfSelfArgsContext)_localctx).requireConstraint);
					}
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				setState(1454);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,107,_ctx);
			} while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER );
			setState(1456);
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
		enterRule(_localctx, 182, RULE_basicHierarchyOfReferenceArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1458);
			argsOpening();
			setState(1459);
			((BasicHierarchyOfReferenceArgsContext)_localctx).referenceName = valueToken();
			setState(1462);
			_errHandler.sync(this);
			_alt = 1;
			do {
				switch (_alt) {
				case 1:
					{
					{
					setState(1460);
					match(ARGS_DELIMITER);
					setState(1461);
					((BasicHierarchyOfReferenceArgsContext)_localctx).requireConstraint = requireConstraint();
					((BasicHierarchyOfReferenceArgsContext)_localctx).requirements.add(((BasicHierarchyOfReferenceArgsContext)_localctx).requireConstraint);
					}
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				setState(1464);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,108,_ctx);
			} while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER );
			setState(1466);
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
		enterRule(_localctx, 184, RULE_basicHierarchyOfReferenceWithBehaviourArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1468);
			argsOpening();
			setState(1469);
			((BasicHierarchyOfReferenceWithBehaviourArgsContext)_localctx).referenceName = valueToken();
			setState(1470);
			match(ARGS_DELIMITER);
			setState(1471);
			((BasicHierarchyOfReferenceWithBehaviourArgsContext)_localctx).emptyHierarchicalEntityBehaviour = valueToken();
			setState(1474);
			_errHandler.sync(this);
			_alt = 1;
			do {
				switch (_alt) {
				case 1:
					{
					{
					setState(1472);
					match(ARGS_DELIMITER);
					setState(1473);
					((BasicHierarchyOfReferenceWithBehaviourArgsContext)_localctx).requireConstraint = requireConstraint();
					((BasicHierarchyOfReferenceWithBehaviourArgsContext)_localctx).requirements.add(((BasicHierarchyOfReferenceWithBehaviourArgsContext)_localctx).requireConstraint);
					}
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				setState(1476);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,109,_ctx);
			} while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER );
			setState(1478);
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
		enterRule(_localctx, 186, RULE_fullHierarchyOfReferenceArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1480);
			argsOpening();
			setState(1481);
			((FullHierarchyOfReferenceArgsContext)_localctx).referenceName = valueToken();
			setState(1482);
			match(ARGS_DELIMITER);
			setState(1483);
			((FullHierarchyOfReferenceArgsContext)_localctx).orderBy = orderConstraint();
			setState(1486);
			_errHandler.sync(this);
			_alt = 1;
			do {
				switch (_alt) {
				case 1:
					{
					{
					setState(1484);
					match(ARGS_DELIMITER);
					setState(1485);
					((FullHierarchyOfReferenceArgsContext)_localctx).requireConstraint = requireConstraint();
					((FullHierarchyOfReferenceArgsContext)_localctx).requirements.add(((FullHierarchyOfReferenceArgsContext)_localctx).requireConstraint);
					}
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				setState(1488);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,110,_ctx);
			} while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER );
			setState(1490);
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
		enterRule(_localctx, 188, RULE_fullHierarchyOfReferenceWithBehaviourArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1492);
			argsOpening();
			setState(1493);
			((FullHierarchyOfReferenceWithBehaviourArgsContext)_localctx).referenceName = valueToken();
			setState(1494);
			match(ARGS_DELIMITER);
			setState(1495);
			((FullHierarchyOfReferenceWithBehaviourArgsContext)_localctx).emptyHierarchicalEntityBehaviour = valueToken();
			setState(1496);
			match(ARGS_DELIMITER);
			setState(1497);
			((FullHierarchyOfReferenceWithBehaviourArgsContext)_localctx).orderBy = orderConstraint();
			setState(1500);
			_errHandler.sync(this);
			_alt = 1;
			do {
				switch (_alt) {
				case 1:
					{
					{
					setState(1498);
					match(ARGS_DELIMITER);
					setState(1499);
					((FullHierarchyOfReferenceWithBehaviourArgsContext)_localctx).requireConstraint = requireConstraint();
					((FullHierarchyOfReferenceWithBehaviourArgsContext)_localctx).requirements.add(((FullHierarchyOfReferenceWithBehaviourArgsContext)_localctx).requireConstraint);
					}
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				setState(1502);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,111,_ctx);
			} while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER );
			setState(1504);
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

	public static class SpacingRequireConstraintArgsContext extends ParserRuleContext {
		public RequireConstraintContext requireConstraint;
		public List<RequireConstraintContext> constraints = new ArrayList<RequireConstraintContext>();
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
		public SpacingRequireConstraintArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_spacingRequireConstraintArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSpacingRequireConstraintArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSpacingRequireConstraintArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSpacingRequireConstraintArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SpacingRequireConstraintArgsContext spacingRequireConstraintArgs() throws RecognitionException {
		SpacingRequireConstraintArgsContext _localctx = new SpacingRequireConstraintArgsContext(_ctx, getState());
		enterRule(_localctx, 190, RULE_spacingRequireConstraintArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1506);
			argsOpening();
			setState(1507);
			((SpacingRequireConstraintArgsContext)_localctx).requireConstraint = requireConstraint();
			((SpacingRequireConstraintArgsContext)_localctx).constraints.add(((SpacingRequireConstraintArgsContext)_localctx).requireConstraint);
			setState(1512);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,112,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(1508);
					match(ARGS_DELIMITER);
					setState(1509);
					((SpacingRequireConstraintArgsContext)_localctx).requireConstraint = requireConstraint();
					((SpacingRequireConstraintArgsContext)_localctx).constraints.add(((SpacingRequireConstraintArgsContext)_localctx).requireConstraint);
					}
					}
				}
				setState(1514);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,112,_ctx);
			}
			setState(1515);
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

	public static class GapRequireConstraintArgsContext extends ParserRuleContext {
		public ValueTokenContext size;
		public ValueTokenContext expression;
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
		public GapRequireConstraintArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_gapRequireConstraintArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterGapRequireConstraintArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitGapRequireConstraintArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitGapRequireConstraintArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final GapRequireConstraintArgsContext gapRequireConstraintArgs() throws RecognitionException {
		GapRequireConstraintArgsContext _localctx = new GapRequireConstraintArgsContext(_ctx, getState());
		enterRule(_localctx, 192, RULE_gapRequireConstraintArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1517);
			argsOpening();
			setState(1518);
			((GapRequireConstraintArgsContext)_localctx).size = valueToken();
			setState(1519);
			match(ARGS_DELIMITER);
			setState(1520);
			((GapRequireConstraintArgsContext)_localctx).expression = valueToken();
			setState(1521);
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

	public static class SegmentArgsContext extends ParserRuleContext {
		public FilterConstraintContext entityHaving;
		public OrderConstraintContext orderBy;
		public OrderConstraintContext limit;
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
		public FilterConstraintContext filterConstraint() {
			return getRuleContext(FilterConstraintContext.class,0);
		}
		public SegmentArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_segmentArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSegmentArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSegmentArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSegmentArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SegmentArgsContext segmentArgs() throws RecognitionException {
		SegmentArgsContext _localctx = new SegmentArgsContext(_ctx, getState());
		enterRule(_localctx, 194, RULE_segmentArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1523);
			argsOpening();
			setState(1527);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,113,_ctx) ) {
			case 1:
				{
				setState(1524);
				((SegmentArgsContext)_localctx).entityHaving = filterConstraint();
				setState(1525);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1529);
			((SegmentArgsContext)_localctx).orderBy = orderConstraint();
			setState(1532);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,114,_ctx) ) {
			case 1:
				{
				setState(1530);
				match(ARGS_DELIMITER);
				setState(1531);
				((SegmentArgsContext)_localctx).limit = orderConstraint();
				}
				break;
			}
			setState(1534);
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

	public static class InScopeFilterArgsContext extends ParserRuleContext {
		public ValueTokenContext scope;
		public FilterConstraintContext filterConstraint;
		public List<FilterConstraintContext> filterConstraints = new ArrayList<FilterConstraintContext>();
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
		public InScopeFilterArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_inScopeFilterArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterInScopeFilterArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitInScopeFilterArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitInScopeFilterArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final InScopeFilterArgsContext inScopeFilterArgs() throws RecognitionException {
		InScopeFilterArgsContext _localctx = new InScopeFilterArgsContext(_ctx, getState());
		enterRule(_localctx, 196, RULE_inScopeFilterArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1536);
			argsOpening();
			setState(1537);
			((InScopeFilterArgsContext)_localctx).scope = valueToken();
			setState(1542);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,115,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(1538);
					match(ARGS_DELIMITER);
					setState(1539);
					((InScopeFilterArgsContext)_localctx).filterConstraint = filterConstraint();
					((InScopeFilterArgsContext)_localctx).filterConstraints.add(((InScopeFilterArgsContext)_localctx).filterConstraint);
					}
					}
				}
				setState(1544);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,115,_ctx);
			}
			setState(1545);
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

	public static class InScopeOrderArgsContext extends ParserRuleContext {
		public ValueTokenContext scope;
		public OrderConstraintContext orderConstraint;
		public List<OrderConstraintContext> orderConstraints = new ArrayList<OrderConstraintContext>();
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
		public InScopeOrderArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_inScopeOrderArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterInScopeOrderArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitInScopeOrderArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitInScopeOrderArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final InScopeOrderArgsContext inScopeOrderArgs() throws RecognitionException {
		InScopeOrderArgsContext _localctx = new InScopeOrderArgsContext(_ctx, getState());
		enterRule(_localctx, 198, RULE_inScopeOrderArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1547);
			argsOpening();
			setState(1548);
			((InScopeOrderArgsContext)_localctx).scope = valueToken();
			setState(1553);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,116,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(1549);
					match(ARGS_DELIMITER);
					setState(1550);
					((InScopeOrderArgsContext)_localctx).orderConstraint = orderConstraint();
					((InScopeOrderArgsContext)_localctx).orderConstraints.add(((InScopeOrderArgsContext)_localctx).orderConstraint);
					}
					}
				}
				setState(1555);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,116,_ctx);
			}
			setState(1556);
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

	public static class InScopeRequireArgsContext extends ParserRuleContext {
		public ValueTokenContext scope;
		public RequireConstraintContext requireConstraint;
		public List<RequireConstraintContext> requireConstraints = new ArrayList<RequireConstraintContext>();
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
		public InScopeRequireArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_inScopeRequireArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterInScopeRequireArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitInScopeRequireArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitInScopeRequireArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final InScopeRequireArgsContext inScopeRequireArgs() throws RecognitionException {
		InScopeRequireArgsContext _localctx = new InScopeRequireArgsContext(_ctx, getState());
		enterRule(_localctx, 200, RULE_inScopeRequireArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1558);
			argsOpening();
			setState(1559);
			((InScopeRequireArgsContext)_localctx).scope = valueToken();
			setState(1564);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,117,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(1560);
					match(ARGS_DELIMITER);
					setState(1561);
					((InScopeRequireArgsContext)_localctx).requireConstraint = requireConstraint();
					((InScopeRequireArgsContext)_localctx).requireConstraints.add(((InScopeRequireArgsContext)_localctx).requireConstraint);
					}
					}
				}
				setState(1566);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,117,_ctx);
			}
			setState(1567);
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
		enterRule(_localctx, 202, RULE_positionalParameter);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1569);
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
		enterRule(_localctx, 204, RULE_namedParameter);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1571);
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
		enterRule(_localctx, 206, RULE_variadicValueTokens);
		try {
			int _alt;
			setState(1583);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,119,_ctx) ) {
			case 1:
				_localctx = new PositionalParameterVariadicValueTokensContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(1573);
				positionalParameter();
				}
				break;
			case 2:
				_localctx = new NamedParameterVariadicValueTokensContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(1574);
				namedParameter();
				}
				break;
			case 3:
				_localctx = new ExplicitVariadicValueTokensContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(1575);
				((ExplicitVariadicValueTokensContext)_localctx).valueToken = valueToken();
				((ExplicitVariadicValueTokensContext)_localctx).valueTokens.add(((ExplicitVariadicValueTokensContext)_localctx).valueToken);
				setState(1580);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,118,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(1576);
						match(ARGS_DELIMITER);
						setState(1577);
						((ExplicitVariadicValueTokensContext)_localctx).valueToken = valueToken();
						((ExplicitVariadicValueTokensContext)_localctx).valueTokens.add(((ExplicitVariadicValueTokensContext)_localctx).valueToken);
						}
						}
					}
					setState(1582);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,118,_ctx);
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
		enterRule(_localctx, 208, RULE_valueToken);
		try {
			setState(1600);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case POSITIONAL_PARAMETER:
				_localctx = new PositionalParameterValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(1585);
				positionalParameter();
				}
				break;
			case NAMED_PARAMETER:
				_localctx = new NamedParameterValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(1586);
				namedParameter();
				}
				break;
			case STRING:
				_localctx = new StringValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(1587);
				match(STRING);
				}
				break;
			case INT:
				_localctx = new IntValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 4);
				{
				setState(1588);
				match(INT);
				}
				break;
			case FLOAT:
				_localctx = new FloatValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 5);
				{
				setState(1589);
				match(FLOAT);
				}
				break;
			case BOOLEAN:
				_localctx = new BooleanValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 6);
				{
				setState(1590);
				match(BOOLEAN);
				}
				break;
			case DATE:
				_localctx = new DateValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 7);
				{
				setState(1591);
				match(DATE);
				}
				break;
			case TIME:
				_localctx = new TimeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 8);
				{
				setState(1592);
				match(TIME);
				}
				break;
			case DATE_TIME:
				_localctx = new DateTimeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 9);
				{
				setState(1593);
				match(DATE_TIME);
				}
				break;
			case OFFSET_DATE_TIME:
				_localctx = new OffsetDateTimeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 10);
				{
				setState(1594);
				match(OFFSET_DATE_TIME);
				}
				break;
			case FLOAT_NUMBER_RANGE:
				_localctx = new FloatNumberRangeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 11);
				{
				setState(1595);
				match(FLOAT_NUMBER_RANGE);
				}
				break;
			case INT_NUMBER_RANGE:
				_localctx = new IntNumberRangeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 12);
				{
				setState(1596);
				match(INT_NUMBER_RANGE);
				}
				break;
			case DATE_TIME_RANGE:
				_localctx = new DateTimeRangeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 13);
				{
				setState(1597);
				match(DATE_TIME_RANGE);
				}
				break;
			case UUID:
				_localctx = new UuidValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 14);
				{
				setState(1598);
				match(UUID);
				}
				break;
			case ENUM:
				_localctx = new EnumValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 15);
				{
				setState(1599);
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
		"\3\u608b\ua72a\u8133\ub9ed\u417c\u3be7\u7786\u5964\3\u008b\u0645\4\2\t"+
		"\2\4\3\t\3\4\4\t\4\4\5\t\5\4\6\t\6\4\7\t\7\4\b\t\b\4\t\t\t\4\n\t\n\4\13"+
		"\t\13\4\f\t\f\4\r\t\r\4\16\t\16\4\17\t\17\4\20\t\20\4\21\t\21\4\22\t\22"+
		"\4\23\t\23\4\24\t\24\4\25\t\25\4\26\t\26\4\27\t\27\4\30\t\30\4\31\t\31"+
		"\4\32\t\32\4\33\t\33\4\34\t\34\4\35\t\35\4\36\t\36\4\37\t\37\4 \t \4!"+
		"\t!\4\"\t\"\4#\t#\4$\t$\4%\t%\4&\t&\4\'\t\'\4(\t(\4)\t)\4*\t*\4+\t+\4"+
		",\t,\4-\t-\4.\t.\4/\t/\4\60\t\60\4\61\t\61\4\62\t\62\4\63\t\63\4\64\t"+
		"\64\4\65\t\65\4\66\t\66\4\67\t\67\48\t8\49\t9\4:\t:\4;\t;\4<\t<\4=\t="+
		"\4>\t>\4?\t?\4@\t@\4A\tA\4B\tB\4C\tC\4D\tD\4E\tE\4F\tF\4G\tG\4H\tH\4I"+
		"\tI\4J\tJ\4K\tK\4L\tL\4M\tM\4N\tN\4O\tO\4P\tP\4Q\tQ\4R\tR\4S\tS\4T\tT"+
		"\4U\tU\4V\tV\4W\tW\4X\tX\4Y\tY\4Z\tZ\4[\t[\4\\\t\\\4]\t]\4^\t^\4_\t_\4"+
		"`\t`\4a\ta\4b\tb\4c\tc\4d\td\4e\te\4f\tf\4g\tg\4h\th\4i\ti\4j\tj\3\2\3"+
		"\2\3\2\3\3\3\3\3\3\3\4\3\4\3\4\3\5\3\5\3\5\3\6\3\6\3\6\3\7\3\7\3\7\3\b"+
		"\3\b\3\b\3\t\3\t\3\t\3\t\5\t\u00ee\n\t\3\n\3\n\3\n\3\n\3\n\3\n\5\n\u00f6"+
		"\n\n\3\13\3\13\3\13\3\13\3\13\3\13\3\13\5\13\u00ff\n\13\3\13\3\13\3\13"+
		"\5\13\u0104\n\13\3\13\3\13\3\13\3\13\3\13\5\13\u010b\n\13\3\13\3\13\3"+
		"\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3"+
		"\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3"+
		"\13\3\13\3\13\3\13\3\13\3\13\3\13\5\13\u0132\n\13\3\13\3\13\3\13\3\13"+
		"\3\13\3\13\3\13\5\13\u013b\n\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13"+
		"\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\5\13\u014e\n\13\3\13\3\13"+
		"\3\13\3\13\3\13\3\13\3\13\3\13\3\13\5\13\u0159\n\13\3\13\3\13\3\13\3\13"+
		"\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\3\13\5\13\u0169\n\13\3\f"+
		"\3\f\3\f\5\f\u016e\n\f\3\f\3\f\3\f\5\f\u0173\n\f\3\f\3\f\3\f\3\f\3\f\3"+
		"\f\3\f\3\f\3\f\5\f\u017e\n\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3"+
		"\f\3\f\3\f\3\f\3\f\5\f\u018f\n\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3\f\3"+
		"\f\3\f\3\f\3\f\3\f\3\f\3\f\5\f\u01a1\n\f\3\r\3\r\3\r\5\r\u01a6\n\r\3\r"+
		"\3\r\3\r\3\r\3\r\3\r\3\r\5\r\u01af\n\r\3\r\3\r\3\r\5\r\u01b4\n\r\3\r\3"+
		"\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\5\r\u01c1\n\r\3\r\3\r\3\r\3\r\3"+
		"\r\3\r\3\r\5\r\u01ca\n\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3"+
		"\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\5\r\u01e1\n\r\3\r\3\r\3\r\3\r\3"+
		"\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r"+
		"\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3"+
		"\r\3\r\5\r\u020c\n\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\5\r\u0217\n\r"+
		"\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3"+
		"\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r"+
		"\3\r\3\r\3\r\3\r\3\r\3\r\5\r\u0242\n\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r"+
		"\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3"+
		"\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\5\r\u0266\n\r\3\16\3\16\3\16\7\16\u026b"+
		"\n\16\f\16\16\16\u026e\13\16\3\17\3\17\3\17\7\17\u0273\n\17\f\17\16\17"+
		"\u0276\13\17\3\20\3\20\3\20\7\20\u027b\n\20\f\20\16\20\u027e\13\20\3\21"+
		"\3\21\3\21\7\21\u0283\n\21\f\21\16\21\u0286\13\21\3\22\3\22\3\23\5\23"+
		"\u028b\n\23\3\23\3\23\3\24\3\24\3\24\3\24\7\24\u0293\n\24\f\24\16\24\u0296"+
		"\13\24\3\24\3\24\3\25\3\25\3\25\3\26\3\26\3\26\3\26\7\26\u02a1\n\26\f"+
		"\26\16\26\u02a4\13\26\3\26\3\26\3\27\3\27\3\27\3\27\7\27\u02ac\n\27\f"+
		"\27\16\27\u02af\13\27\3\27\3\27\3\30\3\30\3\30\3\30\3\31\3\31\3\31\3\31"+
		"\3\31\5\31\u02bc\n\31\3\31\3\31\3\31\7\31\u02c1\n\31\f\31\16\31\u02c4"+
		"\13\31\5\31\u02c6\n\31\3\31\3\31\3\32\3\32\3\32\3\32\7\32\u02ce\n\32\f"+
		"\32\16\32\u02d1\13\32\3\32\3\32\3\33\3\33\3\33\3\33\3\34\3\34\3\34\3\34"+
		"\7\34\u02dd\n\34\f\34\16\34\u02e0\13\34\3\34\3\34\3\35\3\35\3\35\3\35"+
		"\3\36\3\36\3\36\3\36\3\36\3\36\3\37\3\37\3\37\3\37\5\37\u02f2\n\37\3\37"+
		"\3\37\3 \3 \3 \3 \3 \3 \3!\3!\3!\3!\5!\u0300\n!\3!\3!\3\"\3\"\3\"\3\""+
		"\3\"\3\"\3\"\3\"\3#\3#\3#\3#\3$\3$\3$\3$\3%\3%\3%\3%\3%\3%\3&\3&\3&\3"+
		"&\3\'\3\'\3\'\3\'\3\'\3\'\3(\3(\3(\3(\3(\3(\5(\u032a\n(\3(\3(\3)\3)\3"+
		")\3)\5)\u0332\n)\3)\3)\5)\u0336\n)\3)\3)\3*\3*\3*\3*\3*\3*\3+\3+\3+\3"+
		"+\6+\u0344\n+\r+\16+\u0345\3+\3+\3,\3,\3,\3,\3,\3,\7,\u0350\n,\f,\16,"+
		"\u0353\13,\3,\3,\3-\3-\3-\3-\7-\u035b\n-\f-\16-\u035e\13-\3-\3-\3.\3."+
		"\3.\3.\3.\7.\u0367\n.\f.\16.\u036a\13.\5.\u036c\n.\3.\3.\3/\3/\3/\3/\7"+
		"/\u0374\n/\f/\16/\u0377\13/\3/\3/\3\60\3\60\3\60\3\60\3\60\3\60\3\61\3"+
		"\61\3\61\3\61\3\61\3\61\5\61\u0387\n\61\3\61\3\61\3\62\3\62\3\62\3\62"+
		"\3\62\3\62\3\63\3\63\3\63\3\63\5\63\u0395\n\63\3\63\3\63\3\64\3\64\3\64"+
		"\3\64\5\64\u039d\n\64\3\64\3\64\3\64\3\65\3\65\3\65\3\65\5\65\u03a6\n"+
		"\65\3\65\3\65\3\65\3\65\3\65\3\65\3\65\5\65\u03af\n\65\3\65\3\65\3\66"+
		"\3\66\3\66\3\66\5\66\u03b7\n\66\3\66\3\66\3\66\3\66\3\66\5\66\u03be\n"+
		"\66\3\66\3\66\3\67\3\67\3\67\3\67\5\67\u03c6\n\67\3\67\3\67\3\67\3\67"+
		"\3\67\3\67\3\67\3\67\3\67\5\67\u03d1\n\67\3\67\3\67\38\38\38\38\58\u03d9"+
		"\n8\38\38\38\38\38\58\u03e0\n8\38\38\39\39\39\39\59\u03e8\n9\39\39\39"+
		"\39\39\39\39\39\39\59\u03f3\n9\39\39\3:\3:\3:\3:\5:\u03fb\n:\3:\3:\3:"+
		"\3:\3:\3:\3:\5:\u0404\n:\3:\3:\3;\3;\3;\3;\5;\u040c\n;\3;\3;\3;\3;\3;"+
		"\3;\3;\3;\3;\3;\3;\5;\u0419\n;\3;\3;\3<\3<\3<\3<\5<\u0421\n<\3<\3<\3<"+
		"\3<\3<\3=\3=\3=\3=\5=\u042c\n=\3=\3=\3=\3=\3=\3=\3=\3>\3>\3>\3>\5>\u0439"+
		"\n>\3>\3>\3>\3>\3>\3>\3>\3>\3>\5>\u0444\n>\3>\3>\3?\3?\3?\3?\5?\u044c"+
		"\n?\3?\3?\3?\3?\3?\3?\3?\3?\3?\3@\3@\3@\3@\5@\u045b\n@\3@\3@\3@\3@\3@"+
		"\3@\3@\3@\3@\3@\3@\5@\u0468\n@\3@\3@\3A\3A\3A\3A\5A\u0470\nA\3A\3A\3A"+
		"\3A\3A\3A\3A\3A\3A\3B\3B\3B\3B\5B\u047f\nB\3B\3B\3B\3B\3B\3B\3B\3B\3B"+
		"\3B\3B\5B\u048c\nB\3B\3B\3C\3C\3C\3C\5C\u0494\nC\3C\3C\3C\3C\3C\3C\3C"+
		"\3C\3C\3C\3C\3D\3D\3D\3D\5D\u04a5\nD\3D\3D\3D\3D\3D\3D\3D\3D\3D\3D\3D"+
		"\3D\3D\5D\u04b4\nD\3D\3D\3E\3E\3E\3E\5E\u04bc\nE\3E\3E\3E\3E\5E\u04c2"+
		"\nE\3E\3E\3E\3E\3E\3E\3E\5E\u04cb\nE\3E\3E\3E\3E\3E\3E\5E\u04d3\nE\3E"+
		"\3E\3F\3F\3F\3F\3F\5F\u04dc\nF\3F\3F\3F\3F\5F\u04e2\nF\3F\3F\3F\3F\5F"+
		"\u04e8\nF\3F\3F\3G\3G\3G\3G\3G\5G\u04f1\nG\3G\5G\u04f4\nG\3G\3G\3H\3H"+
		"\3H\3H\5H\u04fc\nH\3H\3H\3H\3H\3H\3I\3I\3I\3I\5I\u0507\nI\3I\3I\3I\3I"+
		"\3I\3I\3I\5I\u0510\nI\3I\3I\3J\3J\3J\3J\3K\3K\3K\3K\3K\3K\3L\3L\3L\3L"+
		"\3M\3M\3M\3M\3M\3M\5M\u0528\nM\3M\3M\5M\u052c\nM\3M\3M\3N\3N\3N\3N\3N"+
		"\3N\5N\u0536\nN\3N\3N\3O\3O\3O\3O\3O\3O\3P\3P\3P\3P\5P\u0544\nP\3P\3P"+
		"\5P\u0548\nP\3P\3P\3Q\3Q\3Q\3Q\5Q\u0550\nQ\3Q\3Q\3R\3R\3R\3R\3S\3S\3S"+
		"\3S\5S\u055c\nS\3S\3S\5S\u0560\nS\3S\3S\5S\u0564\nS\3S\3S\5S\u0568\nS"+
		"\3S\3S\3T\3T\3T\3T\3T\5T\u0571\nT\3U\3U\3U\3U\3U\5U\u0578\nU\3V\3V\3V"+
		"\3V\3V\5V\u057f\nV\3W\3W\3W\3W\3W\3W\3X\3X\3X\3X\5X\u058b\nX\3X\3X\3Y"+
		"\3Y\3Y\3Y\3Z\3Z\3Z\3Z\7Z\u0597\nZ\fZ\16Z\u059a\13Z\3Z\3Z\3[\3[\3[\3[\3"+
		"[\3[\7[\u05a4\n[\f[\16[\u05a7\13[\3[\3[\3\\\3\\\3\\\3\\\6\\\u05af\n\\"+
		"\r\\\16\\\u05b0\3\\\3\\\3]\3]\3]\3]\6]\u05b9\n]\r]\16]\u05ba\3]\3]\3^"+
		"\3^\3^\3^\3^\3^\6^\u05c5\n^\r^\16^\u05c6\3^\3^\3_\3_\3_\3_\3_\3_\6_\u05d1"+
		"\n_\r_\16_\u05d2\3_\3_\3`\3`\3`\3`\3`\3`\3`\3`\6`\u05df\n`\r`\16`\u05e0"+
		"\3`\3`\3a\3a\3a\3a\7a\u05e9\na\fa\16a\u05ec\13a\3a\3a\3b\3b\3b\3b\3b\3"+
		"b\3c\3c\3c\3c\5c\u05fa\nc\3c\3c\3c\5c\u05ff\nc\3c\3c\3d\3d\3d\3d\7d\u0607"+
		"\nd\fd\16d\u060a\13d\3d\3d\3e\3e\3e\3e\7e\u0612\ne\fe\16e\u0615\13e\3"+
		"e\3e\3f\3f\3f\3f\7f\u061d\nf\ff\16f\u0620\13f\3f\3f\3g\3g\3h\3h\3i\3i"+
		"\3i\3i\3i\7i\u062d\ni\fi\16i\u0630\13i\5i\u0632\ni\3j\3j\3j\3j\3j\3j\3"+
		"j\3j\3j\3j\3j\3j\3j\3j\3j\5j\u0643\nj\3j\2\2k\2\4\6\b\n\f\16\20\22\24"+
		"\26\30\32\34\36 \"$&(*,.\60\62\64\668:<>@BDFHJLNPRTVXZ\\^`bdfhjlnprtv"+
		"xz|~\u0080\u0082\u0084\u0086\u0088\u008a\u008c\u008e\u0090\u0092\u0094"+
		"\u0096\u0098\u009a\u009c\u009e\u00a0\u00a2\u00a4\u00a6\u00a8\u00aa\u00ac"+
		"\u00ae\u00b0\u00b2\u00b4\u00b6\u00b8\u00ba\u00bc\u00be\u00c0\u00c2\u00c4"+
		"\u00c6\u00c8\u00ca\u00cc\u00ce\u00d0\u00d2\2\2\2\u06f8\2\u00d4\3\2\2\2"+
		"\4\u00d7\3\2\2\2\6\u00da\3\2\2\2\b\u00dd\3\2\2\2\n\u00e0\3\2\2\2\f\u00e3"+
		"\3\2\2\2\16\u00e6\3\2\2\2\20\u00ed\3\2\2\2\22\u00f5\3\2\2\2\24\u0168\3"+
		"\2\2\2\26\u01a0\3\2\2\2\30\u0265\3\2\2\2\32\u0267\3\2\2\2\34\u026f\3\2"+
		"\2\2\36\u0277\3\2\2\2 \u027f\3\2\2\2\"\u0287\3\2\2\2$\u028a\3\2\2\2&\u028e"+
		"\3\2\2\2(\u0299\3\2\2\2*\u029c\3\2\2\2,\u02a7\3\2\2\2.\u02b2\3\2\2\2\60"+
		"\u02b6\3\2\2\2\62\u02c9\3\2\2\2\64\u02d4\3\2\2\2\66\u02d8\3\2\2\28\u02e3"+
		"\3\2\2\2:\u02e7\3\2\2\2<\u02ed\3\2\2\2>\u02f5\3\2\2\2@\u02fb\3\2\2\2B"+
		"\u0303\3\2\2\2D\u030b\3\2\2\2F\u030f\3\2\2\2H\u0313\3\2\2\2J\u0319\3\2"+
		"\2\2L\u031d\3\2\2\2N\u0323\3\2\2\2P\u032d\3\2\2\2R\u0339\3\2\2\2T\u033f"+
		"\3\2\2\2V\u0349\3\2\2\2X\u0356\3\2\2\2Z\u0361\3\2\2\2\\\u036f\3\2\2\2"+
		"^\u037a\3\2\2\2`\u0380\3\2\2\2b\u038a\3\2\2\2d\u0390\3\2\2\2f\u0398\3"+
		"\2\2\2h\u03a1\3\2\2\2j\u03b2\3\2\2\2l\u03c1\3\2\2\2n\u03d4\3\2\2\2p\u03e3"+
		"\3\2\2\2r\u03f6\3\2\2\2t\u0407\3\2\2\2v\u041c\3\2\2\2x\u0427\3\2\2\2z"+
		"\u0434\3\2\2\2|\u0447\3\2\2\2~\u0456\3\2\2\2\u0080\u046b\3\2\2\2\u0082"+
		"\u047a\3\2\2\2\u0084\u048f\3\2\2\2\u0086\u04a0\3\2\2\2\u0088\u04b7\3\2"+
		"\2\2\u008a\u04d6\3\2\2\2\u008c\u04eb\3\2\2\2\u008e\u04f7\3\2\2\2\u0090"+
		"\u0502\3\2\2\2\u0092\u0513\3\2\2\2\u0094\u0517\3\2\2\2\u0096\u051d\3\2"+
		"\2\2\u0098\u0521\3\2\2\2\u009a\u052f\3\2\2\2\u009c\u0539\3\2\2\2\u009e"+
		"\u053f\3\2\2\2\u00a0\u054b\3\2\2\2\u00a2\u0553\3\2\2\2\u00a4\u0557\3\2"+
		"\2\2\u00a6\u0570\3\2\2\2\u00a8\u0577\3\2\2\2\u00aa\u057e\3\2\2\2\u00ac"+
		"\u0580\3\2\2\2\u00ae\u0586\3\2\2\2\u00b0\u058e\3\2\2\2\u00b2\u0592\3\2"+
		"\2\2\u00b4\u059d\3\2\2\2\u00b6\u05aa\3\2\2\2\u00b8\u05b4\3\2\2\2\u00ba"+
		"\u05be\3\2\2\2\u00bc\u05ca\3\2\2\2\u00be\u05d6\3\2\2\2\u00c0\u05e4\3\2"+
		"\2\2\u00c2\u05ef\3\2\2\2\u00c4\u05f5\3\2\2\2\u00c6\u0602\3\2\2\2\u00c8"+
		"\u060d\3\2\2\2\u00ca\u0618\3\2\2\2\u00cc\u0623\3\2\2\2\u00ce\u0625\3\2"+
		"\2\2\u00d0\u0631\3\2\2\2\u00d2\u0642\3\2\2\2\u00d4\u00d5\5\16\b\2\u00d5"+
		"\u00d6\7\2\2\3\u00d6\3\3\2\2\2\u00d7\u00d8\5\32\16\2\u00d8\u00d9\7\2\2"+
		"\3\u00d9\5\3\2\2\2\u00da\u00db\5\34\17\2\u00db\u00dc\7\2\2\3\u00dc\7\3"+
		"\2\2\2\u00dd\u00de\5\36\20\2\u00de\u00df\7\2\2\3\u00df\t\3\2\2\2\u00e0"+
		"\u00e1\5 \21\2\u00e1\u00e2\7\2\2\3\u00e2\13\3\2\2\2\u00e3\u00e4\5\u00d2"+
		"j\2\u00e4\u00e5\7\2\2\3\u00e5\r\3\2\2\2\u00e6\u00e7\7\3\2\2\u00e7\u00e8"+
		"\5&\24\2\u00e8\17\3\2\2\2\u00e9\u00ee\5\22\n\2\u00ea\u00ee\5\24\13\2\u00eb"+
		"\u00ee\5\26\f\2\u00ec\u00ee\5\30\r\2\u00ed\u00e9\3\2\2\2\u00ed\u00ea\3"+
		"\2\2\2\u00ed\u00eb\3\2\2\2\u00ed\u00ec\3\2\2\2\u00ee\21\3\2\2\2\u00ef"+
		"\u00f0\7\4\2\2\u00f0\u00f6\5*\26\2\u00f1\u00f2\7\5\2\2\u00f2\u00f6\58"+
		"\35\2\u00f3\u00f4\7\6\2\2\u00f4\u00f6\5:\36\2\u00f5\u00ef\3\2\2\2\u00f5"+
		"\u00f1\3\2\2\2\u00f5\u00f3\3\2\2\2\u00f6\23\3\2\2\2\u00f7\u00f8\7\7\2"+
		"\2\u00f8\u0169\5,\27\2\u00f9\u00fa\7\b\2\2\u00fa\u0169\5,\27\2\u00fb\u00fe"+
		"\7\t\2\2\u00fc\u00ff\5(\25\2\u00fd\u00ff\5,\27\2\u00fe\u00fc\3\2\2\2\u00fe"+
		"\u00fd\3\2\2\2\u00ff\u0169\3\2\2\2\u0100\u0103\7\n\2\2\u0101\u0104\5("+
		"\25\2\u0102\u0104\5,\27\2\u0103\u0101\3\2\2\2\u0103\u0102\3\2\2\2\u0104"+
		"\u0169\3\2\2\2\u0105\u0106\7\13\2\2\u0106\u0169\5.\30\2\u0107\u010a\7"+
		"\f\2\2\u0108\u010b\5(\25\2\u0109\u010b\5,\27\2\u010a\u0108\3\2\2\2\u010a"+
		"\u0109\3\2\2\2\u010b\u0169\3\2\2\2\u010c\u010d\7\r\2\2\u010d\u0169\5:"+
		"\36\2\u010e\u010f\7\16\2\2\u010f\u0169\5:\36\2\u0110\u0111\7\17\2\2\u0111"+
		"\u0169\5:\36\2\u0112\u0113\7\20\2\2\u0113\u0169\5:\36\2\u0114\u0115\7"+
		"\21\2\2\u0115\u0169\5:\36\2\u0116\u0117\7\22\2\2\u0117\u0169\5B\"\2\u0118"+
		"\u0119\7\23\2\2\u0119\u0169\5@!\2\u011a\u011b\7\24\2\2\u011b\u0169\5:"+
		"\36\2\u011c\u011d\7\25\2\2\u011d\u0169\5:\36\2\u011e\u011f\7\26\2\2\u011f"+
		"\u0169\5:\36\2\u0120\u0121\7\27\2\2\u0121\u0169\58\35\2\u0122\u0123\7"+
		"\30\2\2\u0123\u0169\58\35\2\u0124\u0125\7\31\2\2\u0125\u0169\5:\36\2\u0126"+
		"\u0127\7\32\2\2\u0127\u0169\58\35\2\u0128\u0129\7\33\2\2\u0129\u0169\5"+
		"8\35\2\u012a\u012b\7\34\2\2\u012b\u0169\5:\36\2\u012c\u012d\7\35\2\2\u012d"+
		"\u0169\58\35\2\u012e\u0131\7\36\2\2\u012f\u0132\5(\25\2\u0130\u0132\5"+
		"F$\2\u0131\u012f\3\2\2\2\u0131\u0130\3\2\2\2\u0132\u0169\3\2\2\2\u0133"+
		"\u0134\7\37\2\2\u0134\u0169\5D#\2\u0135\u0136\7 \2\2\u0136\u0169\5D#\2"+
		"\u0137\u013a\7!\2\2\u0138\u013b\5(\25\2\u0139\u013b\5J&\2\u013a\u0138"+
		"\3\2\2\2\u013a\u0139\3\2\2\2\u013b\u0169\3\2\2\2\u013c\u013d\7\"\2\2\u013d"+
		"\u0169\5(\25\2\u013e\u013f\7#\2\2\u013f\u0169\5D#\2\u0140\u0141\7$\2\2"+
		"\u0141\u0169\5H%\2\u0142\u0143\7%\2\2\u0143\u0169\5N(\2\u0144\u0145\7"+
		"&\2\2\u0145\u0169\5(\25\2\u0146\u0147\7\'\2\2\u0147\u0169\5.\30\2\u0148"+
		"\u0149\7(\2\2\u0149\u0169\5.\30\2\u014a\u014d\7)\2\2\u014b\u014e\58\35"+
		"\2\u014c\u014e\5L\'\2\u014d\u014b\3\2\2\2\u014d\u014c\3\2\2\2\u014e\u0169"+
		"\3\2\2\2\u014f\u0150\7*\2\2\u0150\u0169\5V,\2\u0151\u0152\7+\2\2\u0152"+
		"\u0169\5X-\2\u0153\u0154\7,\2\2\u0154\u0169\5Z.\2\u0155\u0158\7-\2\2\u0156"+
		"\u0159\5(\25\2\u0157\u0159\5\\/\2\u0158\u0156\3\2\2\2\u0158\u0157\3\2"+
		"\2\2\u0159\u0169\3\2\2\2\u015a\u015b\7.\2\2\u015b\u0169\5(\25\2\u015c"+
		"\u015d\7/\2\2\u015d\u0169\5,\27\2\u015e\u015f\7\60\2\2\u015f\u0169\5("+
		"\25\2\u0160\u0161\7\61\2\2\u0161\u0169\5,\27\2\u0162\u0163\7\62\2\2\u0163"+
		"\u0169\5.\30\2\u0164\u0165\7\63\2\2\u0165\u0169\5\u00c6d\2\u0166\u0167"+
		"\7\64\2\2\u0167\u0169\5F$\2\u0168\u00f7\3\2\2\2\u0168\u00f9\3\2\2\2\u0168"+
		"\u00fb\3\2\2\2\u0168\u0100\3\2\2\2\u0168\u0105\3\2\2\2\u0168\u0107\3\2"+
		"\2\2\u0168\u010c\3\2\2\2\u0168\u010e\3\2\2\2\u0168\u0110\3\2\2\2\u0168"+
		"\u0112\3\2\2\2\u0168\u0114\3\2\2\2\u0168\u0116\3\2\2\2\u0168\u0118\3\2"+
		"\2\2\u0168\u011a\3\2\2\2\u0168\u011c\3\2\2\2\u0168\u011e\3\2\2\2\u0168"+
		"\u0120\3\2\2\2\u0168\u0122\3\2\2\2\u0168\u0124\3\2\2\2\u0168\u0126\3\2"+
		"\2\2\u0168\u0128\3\2\2\2\u0168\u012a\3\2\2\2\u0168\u012c\3\2\2\2\u0168"+
		"\u012e\3\2\2\2\u0168\u0133\3\2\2\2\u0168\u0135\3\2\2\2\u0168\u0137\3\2"+
		"\2\2\u0168\u013c\3\2\2\2\u0168\u013e\3\2\2\2\u0168\u0140\3\2\2\2\u0168"+
		"\u0142\3\2\2\2\u0168\u0144\3\2\2\2\u0168\u0146\3\2\2\2\u0168\u0148\3\2"+
		"\2\2\u0168\u014a\3\2\2\2\u0168\u014f\3\2\2\2\u0168\u0151\3\2\2\2\u0168"+
		"\u0153\3\2\2\2\u0168\u0155\3\2\2\2\u0168\u015a\3\2\2\2\u0168\u015c\3\2"+
		"\2\2\u0168\u015e\3\2\2\2\u0168\u0160\3\2\2\2\u0168\u0162\3\2\2\2\u0168"+
		"\u0164\3\2\2\2\u0168\u0166\3\2\2\2\u0169\25\3\2\2\2\u016a\u016d\7\65\2"+
		"\2\u016b\u016e\5(\25\2\u016c\u016e\5\62\32\2\u016d\u016b\3\2\2\2\u016d"+
		"\u016c\3\2\2\2\u016e\u01a1\3\2\2\2\u016f\u0172\7\66\2\2\u0170\u0173\5"+
		"(\25\2\u0171\u0173\5\62\32\2\u0172\u0170\3\2\2\2\u0172\u0171\3\2\2\2\u0173"+
		"\u01a1\3\2\2\2\u0174\u0175\7\67\2\2\u0175\u01a1\5<\37\2\u0176\u0177\7"+
		"8\2\2\u0177\u01a1\5^\60\2\u0178\u0179\79\2\2\u0179\u01a1\58\35\2\u017a"+
		"\u017d\7:\2\2\u017b\u017e\5(\25\2\u017c\u017e\5D#\2\u017d\u017b\3\2\2"+
		"\2\u017d\u017c\3\2\2\2\u017e\u01a1\3\2\2\2\u017f\u0180\7;\2\2\u0180\u01a1"+
		"\5F$\2\u0181\u0182\7<\2\2\u0182\u01a1\5(\25\2\u0183\u0184\7=\2\2\u0184"+
		"\u01a1\5D#\2\u0185\u0186\7>\2\2\u0186\u01a1\5T+\2\u0187\u0188\7?\2\2\u0188"+
		"\u01a1\5\60\31\2\u0189\u018a\7@\2\2\u018a\u01a1\5\62\32\2\u018b\u018e"+
		"\7A\2\2\u018c\u018f\5(\25\2\u018d\u018f\5D#\2\u018e\u018c\3\2\2\2\u018e"+
		"\u018d\3\2\2\2\u018f\u01a1\3\2\2\2\u0190\u0191\7B\2\2\u0191\u01a1\5F$"+
		"\2\u0192\u0193\7C\2\2\u0193\u01a1\5(\25\2\u0194\u0195\7D\2\2\u0195\u01a1"+
		"\5\62\32\2\u0196\u0197\7E\2\2\u0197\u01a1\5\62\32\2\u0198\u0199\7F\2\2"+
		"\u0199\u01a1\5\62\32\2\u019a\u019b\7G\2\2\u019b\u01a1\5\u00c4c\2\u019c"+
		"\u019d\7H\2\2\u019d\u01a1\5D#\2\u019e\u019f\7\63\2\2\u019f\u01a1\5\u00c8"+
		"e\2\u01a0\u016a\3\2\2\2\u01a0\u016f\3\2\2\2\u01a0\u0174\3\2\2\2\u01a0"+
		"\u0176\3\2\2\2\u01a0\u0178\3\2\2\2\u01a0\u017a\3\2\2\2\u01a0\u017f\3\2"+
		"\2\2\u01a0\u0181\3\2\2\2\u01a0\u0183\3\2\2\2\u01a0\u0185\3\2\2\2\u01a0"+
		"\u0187\3\2\2\2\u01a0\u0189\3\2\2\2\u01a0\u018b\3\2\2\2\u01a0\u0190\3\2"+
		"\2\2\u01a0\u0192\3\2\2\2\u01a0\u0194\3\2\2\2\u01a0\u0196\3\2\2\2\u01a0"+
		"\u0198\3\2\2\2\u01a0\u019a\3\2\2\2\u01a0\u019c\3\2\2\2\u01a0\u019e\3\2"+
		"\2\2\u01a1\27\3\2\2\2\u01a2\u01a5\7I\2\2\u01a3\u01a6\5(\25\2\u01a4\u01a6"+
		"\5\66\34\2\u01a5\u01a3\3\2\2\2\u01a5\u01a4\3\2\2\2\u01a6\u0266\3\2\2\2"+
		"\u01a7\u01a8\7J\2\2\u01a8\u0266\5`\61\2\u01a9\u01aa\7K\2\2\u01aa\u0266"+
		"\5b\62\2\u01ab\u01ae\7L\2\2\u01ac\u01af\5(\25\2\u01ad\u01af\5\66\34\2"+
		"\u01ae\u01ac\3\2\2\2\u01ae\u01ad\3\2\2\2\u01af\u0266\3\2\2\2\u01b0\u01b3"+
		"\7M\2\2\u01b1\u01b4\5(\25\2\u01b2\u01b4\5\66\34\2\u01b3\u01b1\3\2\2\2"+
		"\u01b3\u01b2\3\2\2\2\u01b4\u0266\3\2\2\2\u01b5\u01b6\7N\2\2\u01b6\u0266"+
		"\5J&\2\u01b7\u01b8\7O\2\2\u01b8\u0266\5(\25\2\u01b9\u01ba\7P\2\2\u01ba"+
		"\u0266\5d\63\2\u01bb\u01bc\7Q\2\2\u01bc\u0266\5(\25\2\u01bd\u01c0\7R\2"+
		"\2\u01be\u01c1\5(\25\2\u01bf\u01c1\5F$\2\u01c0\u01be\3\2\2\2\u01c0\u01bf"+
		"\3\2\2\2\u01c1\u0266\3\2\2\2\u01c2\u01c3\7S\2\2\u01c3\u0266\5J&\2\u01c4"+
		"\u01c5\7T\2\2\u01c5\u0266\5(\25\2\u01c6\u01c9\7U\2\2\u01c7\u01ca\5(\25"+
		"\2\u01c8\u01ca\5\u008aF\2\u01c9\u01c7\3\2\2\2\u01c9\u01c8\3\2\2\2\u01ca"+
		"\u0266\3\2\2\2\u01cb\u01cc\7V\2\2\u01cc\u0266\5\u0088E\2\u01cd\u01ce\7"+
		"V\2\2\u01ce\u0266\5f\64\2\u01cf\u01d0\7V\2\2\u01d0\u0266\5h\65\2\u01d1"+
		"\u01d2\7V\2\2\u01d2\u0266\5j\66\2\u01d3\u01d4\7V\2\2\u01d4\u0266\5l\67"+
		"\2\u01d5\u01d6\7V\2\2\u01d6\u0266\5n8\2\u01d7\u01d8\7V\2\2\u01d8\u0266"+
		"\5p9\2\u01d9\u01da\7V\2\2\u01da\u0266\5r:\2\u01db\u01dc\7V\2\2\u01dc\u0266"+
		"\5t;\2\u01dd\u01e0\7W\2\2\u01de\u01e1\5(\25\2\u01df\u01e1\5\u008cG\2\u01e0"+
		"\u01de\3\2\2\2\u01e0\u01df\3\2\2\2\u01e1\u0266\3\2\2\2\u01e2\u01e3\7W"+
		"\2\2\u01e3\u0266\5\u008eH\2\u01e4\u01e5\7W\2\2\u01e5\u0266\5\u0090I\2"+
		"\u01e6\u01e7\7X\2\2\u01e7\u0266\5f\64\2\u01e8\u01e9\7X\2\2\u01e9\u0266"+
		"\5v<\2\u01ea\u01eb\7X\2\2\u01eb\u0266\5x=\2\u01ec\u01ed\7X\2\2\u01ed\u0266"+
		"\5z>\2\u01ee\u01ef\7X\2\2\u01ef\u0266\5j\66\2\u01f0\u01f1\7X\2\2\u01f1"+
		"\u0266\5|?\2\u01f2\u01f3\7X\2\2\u01f3\u0266\5~@\2\u01f4\u01f5\7X\2\2\u01f5"+
		"\u0266\5n8\2\u01f6\u01f7\7X\2\2\u01f7\u0266\5\u0080A\2\u01f8\u01f9\7X"+
		"\2\2\u01f9\u0266\5\u0082B\2\u01fa\u01fb\7X\2\2\u01fb\u0266\5r:\2\u01fc"+
		"\u01fd\7X\2\2\u01fd\u0266\5\u0084C\2\u01fe\u01ff\7X\2\2\u01ff\u0266\5"+
		"\u0086D\2\u0200\u0201\7Y\2\2\u0201\u0266\5(\25\2\u0202\u0203\7Y\2\2\u0203"+
		"\u0266\5\u0092J\2\u0204\u0205\7Y\2\2\u0205\u0266\5\u0094K\2\u0206\u0207"+
		"\7Z\2\2\u0207\u0266\5J&\2\u0208\u020b\7[\2\2\u0209\u020c\5(\25\2\u020a"+
		"\u020c\5@!\2\u020b\u0209\3\2\2\2\u020b\u020a\3\2\2\2\u020c\u0266\3\2\2"+
		"\2\u020d\u020e\7\\\2\2\u020e\u0266\5D#\2\u020f\u0210\7]\2\2\u0210\u0266"+
		"\5(\25\2\u0211\u0212\7^\2\2\u0212\u0266\5F$\2\u0213\u0216\7_\2\2\u0214"+
		"\u0217\5(\25\2\u0215\u0217\5\u0096L\2\u0216\u0214\3\2\2\2\u0216\u0215"+
		"\3\2\2\2\u0217\u0266\3\2\2\2\u0218\u0219\7_\2\2\u0219\u0266\5\u0098M\2"+
		"\u021a\u021b\7_\2\2\u021b\u0266\5\u009aN\2\u021c\u021d\7_\2\2\u021d\u0266"+
		"\5\u009cO\2\u021e\u021f\7_\2\2\u021f\u0266\5\u009eP\2\u0220\u0221\7_\2"+
		"\2\u0221\u0266\5\u00a0Q\2\u0222\u0223\7_\2\2\u0223\u0266\5\u00a2R\2\u0224"+
		"\u0225\7`\2\2\u0225\u0266\58\35\2\u0226\u0227\7`\2\2\u0227\u0266\5\u00a4"+
		"S\2\u0228\u0229\7a\2\2\u0229\u0266\5P)\2\u022a\u022b\7b\2\2\u022b\u0266"+
		"\5P)\2\u022c\u022d\7c\2\2\u022d\u0266\5P)\2\u022e\u022f\7d\2\2\u022f\u0266"+
		"\5P)\2\u0230\u0231\7e\2\2\u0231\u0266\5R*\2\u0232\u0233\7f\2\2\u0233\u0266"+
		"\5\u00acW\2\u0234\u0235\7g\2\2\u0235\u0266\5\u00aeX\2\u0236\u0237\7h\2"+
		"\2\u0237\u0266\5D#\2\u0238\u0239\7i\2\2\u0239\u0266\5D#\2\u023a\u023b"+
		"\7j\2\2\u023b\u0266\5.\30\2\u023c\u023d\7k\2\2\u023d\u0266\5\64\33\2\u023e"+
		"\u0241\7l\2\2\u023f\u0242\5(\25\2\u0240\u0242\5\u00b0Y\2\u0241\u023f\3"+
		"\2\2\2\u0241\u0240\3\2\2\2\u0242\u0266\3\2\2\2\u0243\u0244\7m\2\2\u0244"+
		"\u0266\5\u00b2Z\2\u0245\u0246\7n\2\2\u0246\u0266\5\u00b4[\2\u0247\u0248"+
		"\7o\2\2\u0248\u0266\5\u00b2Z\2\u0249\u024a\7p\2\2\u024a\u0266\5(\25\2"+
		"\u024b\u024c\7p\2\2\u024c\u0266\5\66\34\2\u024d\u024e\7p\2\2\u024e\u0266"+
		"\5\u00b2Z\2\u024f\u0250\7q\2\2\u0250\u0266\5\u00c0a\2\u0251\u0252\7r\2"+
		"\2\u0252\u0266\5\u00c2b\2\u0253\u0254\7s\2\2\u0254\u0266\5\u00b2Z\2\u0255"+
		"\u0256\7t\2\2\u0256\u0266\5\66\34\2\u0257\u0258\7t\2\2\u0258\u0266\5\u00b6"+
		"\\\2\u0259\u025a\7u\2\2\u025a\u0266\5\u00b8]\2\u025b\u025c\7u\2\2\u025c"+
		"\u0266\5\u00ba^\2\u025d\u025e\7u\2\2\u025e\u0266\5\u00bc_\2\u025f\u0260"+
		"\7u\2\2\u0260\u0266\5\u00be`\2\u0261\u0262\7v\2\2\u0262\u0266\5(\25\2"+
		"\u0263\u0264\7\63\2\2\u0264\u0266\5\u00caf\2\u0265\u01a2\3\2\2\2\u0265"+
		"\u01a7\3\2\2\2\u0265\u01a9\3\2\2\2\u0265\u01ab\3\2\2\2\u0265\u01b0\3\2"+
		"\2\2\u0265\u01b5\3\2\2\2\u0265\u01b7\3\2\2\2\u0265\u01b9\3\2\2\2\u0265"+
		"\u01bb\3\2\2\2\u0265\u01bd\3\2\2\2\u0265\u01c2\3\2\2\2\u0265\u01c4\3\2"+
		"\2\2\u0265\u01c6\3\2\2\2\u0265\u01cb\3\2\2\2\u0265\u01cd\3\2\2\2\u0265"+
		"\u01cf\3\2\2\2\u0265\u01d1\3\2\2\2\u0265\u01d3\3\2\2\2\u0265\u01d5\3\2"+
		"\2\2\u0265\u01d7\3\2\2\2\u0265\u01d9\3\2\2\2\u0265\u01db\3\2\2\2\u0265"+
		"\u01dd\3\2\2\2\u0265\u01e2\3\2\2\2\u0265\u01e4\3\2\2\2\u0265\u01e6\3\2"+
		"\2\2\u0265\u01e8\3\2\2\2\u0265\u01ea\3\2\2\2\u0265\u01ec\3\2\2\2\u0265"+
		"\u01ee\3\2\2\2\u0265\u01f0\3\2\2\2\u0265\u01f2\3\2\2\2\u0265\u01f4\3\2"+
		"\2\2\u0265\u01f6\3\2\2\2\u0265\u01f8\3\2\2\2\u0265\u01fa\3\2\2\2\u0265"+
		"\u01fc\3\2\2\2\u0265\u01fe\3\2\2\2\u0265\u0200\3\2\2\2\u0265\u0202\3\2"+
		"\2\2\u0265\u0204\3\2\2\2\u0265\u0206\3\2\2\2\u0265\u0208\3\2\2\2\u0265"+
		"\u020d\3\2\2\2\u0265\u020f\3\2\2\2\u0265\u0211\3\2\2\2\u0265\u0213\3\2"+
		"\2\2\u0265\u0218\3\2\2\2\u0265\u021a\3\2\2\2\u0265\u021c\3\2\2\2\u0265"+
		"\u021e\3\2\2\2\u0265\u0220\3\2\2\2\u0265\u0222\3\2\2\2\u0265\u0224\3\2"+
		"\2\2\u0265\u0226\3\2\2\2\u0265\u0228\3\2\2\2\u0265\u022a\3\2\2\2\u0265"+
		"\u022c\3\2\2\2\u0265\u022e\3\2\2\2\u0265\u0230\3\2\2\2\u0265\u0232\3\2"+
		"\2\2\u0265\u0234\3\2\2\2\u0265\u0236\3\2\2\2\u0265\u0238\3\2\2\2\u0265"+
		"\u023a\3\2\2\2\u0265\u023c\3\2\2\2\u0265\u023e\3\2\2\2\u0265\u0243\3\2"+
		"\2\2\u0265\u0245\3\2\2\2\u0265\u0247\3\2\2\2\u0265\u0249\3\2\2\2\u0265"+
		"\u024b\3\2\2\2\u0265\u024d\3\2\2\2\u0265\u024f\3\2\2\2\u0265\u0251\3\2"+
		"\2\2\u0265\u0253\3\2\2\2\u0265\u0255\3\2\2\2\u0265\u0257\3\2\2\2\u0265"+
		"\u0259\3\2\2\2\u0265\u025b\3\2\2\2\u0265\u025d\3\2\2\2\u0265\u025f\3\2"+
		"\2\2\u0265\u0261\3\2\2\2\u0265\u0263\3\2\2\2\u0266\31\3\2\2\2\u0267\u026c"+
		"\5\22\n\2\u0268\u0269\7\u0088\2\2\u0269\u026b\5\22\n\2\u026a\u0268\3\2"+
		"\2\2\u026b\u026e\3\2\2\2\u026c\u026a\3\2\2\2\u026c\u026d\3\2\2\2\u026d"+
		"\33\3\2\2\2\u026e\u026c\3\2\2\2\u026f\u0274\5\24\13\2\u0270\u0271\7\u0088"+
		"\2\2\u0271\u0273\5\24\13\2\u0272\u0270\3\2\2\2\u0273\u0276\3\2\2\2\u0274"+
		"\u0272\3\2\2\2\u0274\u0275\3\2\2\2\u0275\35\3\2\2\2\u0276\u0274\3\2\2"+
		"\2\u0277\u027c\5\26\f\2\u0278\u0279\7\u0088\2\2\u0279\u027b\5\26\f\2\u027a"+
		"\u0278\3\2\2\2\u027b\u027e\3\2\2\2\u027c\u027a\3\2\2\2\u027c\u027d\3\2"+
		"\2\2\u027d\37\3\2\2\2\u027e\u027c\3\2\2\2\u027f\u0284\5\30\r\2\u0280\u0281"+
		"\7\u0088\2\2\u0281\u0283\5\30\r\2\u0282\u0280\3\2\2\2\u0283\u0286\3\2"+
		"\2\2\u0284\u0282\3\2\2\2\u0284\u0285\3\2\2\2\u0285!\3\2\2\2\u0286\u0284"+
		"\3\2\2\2\u0287\u0288\7\u0086\2\2\u0288#\3\2\2\2\u0289\u028b\7\u0088\2"+
		"\2\u028a\u0289\3\2\2\2\u028a\u028b\3\2\2\2\u028b\u028c\3\2\2\2\u028c\u028d"+
		"\7\u0087\2\2\u028d%\3\2\2\2\u028e\u028f\5\"\22\2\u028f\u0294\5\20\t\2"+
		"\u0290\u0291\7\u0088\2\2\u0291\u0293\5\20\t\2\u0292\u0290\3\2\2\2\u0293"+
		"\u0296\3\2\2\2\u0294\u0292\3\2\2\2\u0294\u0295\3\2\2\2\u0295\u0297\3\2"+
		"\2\2\u0296\u0294\3\2\2\2\u0297\u0298\5$\23\2\u0298\'\3\2\2\2\u0299\u029a"+
		"\5\"\22\2\u029a\u029b\5$\23\2\u029b)\3\2\2\2\u029c\u029d\5\"\22\2\u029d"+
		"\u02a2\5\22\n\2\u029e\u029f\7\u0088\2\2\u029f\u02a1\5\22\n\2\u02a0\u029e"+
		"\3\2\2\2\u02a1\u02a4\3\2\2\2\u02a2\u02a0\3\2\2\2\u02a2\u02a3\3\2\2\2\u02a3"+
		"\u02a5\3\2\2\2\u02a4\u02a2\3\2\2\2\u02a5\u02a6\5$\23\2\u02a6+\3\2\2\2"+
		"\u02a7\u02a8\5\"\22\2\u02a8\u02ad\5\24\13\2\u02a9\u02aa\7\u0088\2\2\u02aa"+
		"\u02ac\5\24\13\2\u02ab\u02a9\3\2\2\2\u02ac\u02af\3\2\2\2\u02ad\u02ab\3"+
		"\2\2\2\u02ad\u02ae\3\2\2\2\u02ae\u02b0\3\2\2\2\u02af\u02ad\3\2\2\2\u02b0"+
		"\u02b1\5$\23\2\u02b1-\3\2\2\2\u02b2\u02b3\5\"\22\2\u02b3\u02b4\5\24\13"+
		"\2\u02b4\u02b5\5$\23\2\u02b5/\3\2\2\2\u02b6\u02c5\5\"\22\2\u02b7\u02c6"+
		"\5\u00d2j\2\u02b8\u02b9\5\u00d2j\2\u02b9\u02ba\7\u0088\2\2\u02ba\u02bc"+
		"\3\2\2\2\u02bb\u02b8\3\2\2\2\u02bb\u02bc\3\2\2\2\u02bc\u02bd\3\2\2\2\u02bd"+
		"\u02c2\5\26\f\2\u02be\u02bf\7\u0088\2\2\u02bf\u02c1\5\26\f\2\u02c0\u02be"+
		"\3\2\2\2\u02c1\u02c4\3\2\2\2\u02c2\u02c0\3\2\2\2\u02c2\u02c3\3\2\2\2\u02c3"+
		"\u02c6\3\2\2\2\u02c4\u02c2\3\2\2\2\u02c5\u02b7\3\2\2\2\u02c5\u02bb\3\2"+
		"\2\2\u02c6\u02c7\3\2\2\2\u02c7\u02c8\5$\23\2\u02c8\61\3\2\2\2\u02c9\u02ca"+
		"\5\"\22\2\u02ca\u02cf\5\26\f\2\u02cb\u02cc\7\u0088\2\2\u02cc\u02ce\5\26"+
		"\f\2\u02cd\u02cb\3\2\2\2\u02ce\u02d1\3\2\2\2\u02cf\u02cd\3\2\2\2\u02cf"+
		"\u02d0\3\2\2\2\u02d0\u02d2\3\2\2\2\u02d1\u02cf\3\2\2\2\u02d2\u02d3\5$"+
		"\23\2\u02d3\63\3\2\2\2\u02d4\u02d5\5\"\22\2\u02d5\u02d6\5\30\r\2\u02d6"+
		"\u02d7\5$\23\2\u02d7\65\3\2\2\2\u02d8\u02d9\5\"\22\2\u02d9\u02de\5\30"+
		"\r\2\u02da\u02db\7\u0088\2\2\u02db\u02dd\5\30\r\2\u02dc\u02da\3\2\2\2"+
		"\u02dd\u02e0\3\2\2\2\u02de\u02dc\3\2\2\2\u02de\u02df\3\2\2\2\u02df\u02e1"+
		"\3\2\2\2\u02e0\u02de\3\2\2\2\u02e1\u02e2\5$\23\2\u02e2\67\3\2\2\2\u02e3"+
		"\u02e4\5\"\22\2\u02e4\u02e5\5\u00d2j\2\u02e5\u02e6\5$\23\2\u02e69\3\2"+
		"\2\2\u02e7\u02e8\5\"\22\2\u02e8\u02e9\5\u00d2j\2\u02e9\u02ea\7\u0088\2"+
		"\2\u02ea\u02eb\5\u00d2j\2\u02eb\u02ec\5$\23\2\u02ec;\3\2\2\2\u02ed\u02ee"+
		"\5\"\22\2\u02ee\u02f1\5\u00d2j\2\u02ef\u02f0\7\u0088\2\2\u02f0\u02f2\5"+
		"\u00d2j\2\u02f1\u02ef\3\2\2\2\u02f1\u02f2\3\2\2\2\u02f2\u02f3\3\2\2\2"+
		"\u02f3\u02f4\5$\23\2\u02f4=\3\2\2\2\u02f5\u02f6\5\"\22\2\u02f6\u02f7\5"+
		"\u00d2j\2\u02f7\u02f8\7\u0088\2\2\u02f8\u02f9\5\u00d0i\2\u02f9\u02fa\5"+
		"$\23\2\u02fa?\3\2\2\2\u02fb\u02fc\5\"\22\2\u02fc\u02ff\5\u00d2j\2\u02fd"+
		"\u02fe\7\u0088\2\2\u02fe\u0300\5\u00d0i\2\u02ff\u02fd\3\2\2\2\u02ff\u0300"+
		"\3\2\2\2\u0300\u0301\3\2\2\2\u0301\u0302\5$\23\2\u0302A\3\2\2\2\u0303"+
		"\u0304\5\"\22\2\u0304\u0305\5\u00d2j\2\u0305\u0306\7\u0088\2\2\u0306\u0307"+
		"\5\u00d2j\2\u0307\u0308\7\u0088\2\2\u0308\u0309\5\u00d2j\2\u0309\u030a"+
		"\5$\23\2\u030aC\3\2\2\2\u030b\u030c\5\"\22\2\u030c\u030d\5\u00d2j\2\u030d"+
		"\u030e\5$\23\2\u030eE\3\2\2\2\u030f\u0310\5\"\22\2\u0310\u0311\5\u00d0"+
		"i\2\u0311\u0312\5$\23\2\u0312G\3\2\2\2\u0313\u0314\5\"\22\2\u0314\u0315"+
		"\5\u00d2j\2\u0315\u0316\7\u0088\2\2\u0316\u0317\5\u00d2j\2\u0317\u0318"+
		"\5$\23\2\u0318I\3\2\2\2\u0319\u031a\5\"\22\2\u031a\u031b\5\u00d0i\2\u031b"+
		"\u031c\5$\23\2\u031cK\3\2\2\2\u031d\u031e\5\"\22\2\u031e\u031f\5\u00d2"+
		"j\2\u031f\u0320\7\u0088\2\2\u0320\u0321\5\24\13\2\u0321\u0322\5$\23\2"+
		"\u0322M\3\2\2\2\u0323\u0324\5\"\22\2\u0324\u0325\5\u00d2j\2\u0325\u0326"+
		"\7\u0088\2\2\u0326\u0329\5\24\13\2\u0327\u0328\7\u0088\2\2\u0328\u032a"+
		"\5\24\13\2\u0329\u0327\3\2\2\2\u0329\u032a\3\2\2\2\u032a\u032b\3\2\2\2"+
		"\u032b\u032c\5$\23\2\u032cO\3\2\2\2\u032d\u032e\5\"\22\2\u032e\u0331\5"+
		"\u00d2j\2\u032f\u0330\7\u0088\2\2\u0330\u0332\5\u00d2j\2\u0331\u032f\3"+
		"\2\2\2\u0331\u0332\3\2\2\2\u0332\u0335\3\2\2\2\u0333\u0334\7\u0088\2\2"+
		"\u0334\u0336\5\24\13\2\u0335\u0333\3\2\2\2\u0335\u0336\3\2\2\2\u0336\u0337"+
		"\3\2\2\2\u0337\u0338\5$\23\2\u0338Q\3\2\2\2\u0339\u033a\5\"\22\2\u033a"+
		"\u033b\5\u00d2j\2\u033b\u033c\7\u0088\2\2\u033c\u033d\5\u00d2j\2\u033d"+
		"\u033e\5$\23\2\u033eS\3\2\2\2\u033f\u0340\5\"\22\2\u0340\u0343\5\u00d2"+
		"j\2\u0341\u0342\7\u0088\2\2\u0342\u0344\5\26\f\2\u0343\u0341\3\2\2\2\u0344"+
		"\u0345\3\2\2\2\u0345\u0343\3\2\2\2\u0345\u0346\3\2\2\2\u0346\u0347\3\2"+
		"\2\2\u0347\u0348\5$\23\2\u0348U\3\2\2\2\u0349\u034a\5\"\22\2\u034a\u034b"+
		"\5\u00d2j\2\u034b\u034c\7\u0088\2\2\u034c\u0351\5\24\13\2\u034d\u034e"+
		"\7\u0088\2\2\u034e\u0350\5\24\13\2\u034f\u034d\3\2\2\2\u0350\u0353\3\2"+
		"\2\2\u0351\u034f\3\2\2\2\u0351\u0352\3\2\2\2\u0352\u0354\3\2\2\2\u0353"+
		"\u0351\3\2\2\2\u0354\u0355\5$\23\2\u0355W\3\2\2\2\u0356\u0357\5\"\22\2"+
		"\u0357\u035c\5\24\13\2\u0358\u0359\7\u0088\2\2\u0359\u035b\5\24\13\2\u035a"+
		"\u0358\3\2\2\2\u035b\u035e\3\2\2\2\u035c\u035a\3\2\2\2\u035c\u035d\3\2"+
		"\2\2\u035d\u035f\3\2\2\2\u035e\u035c\3\2\2\2\u035f\u0360\5$\23\2\u0360"+
		"Y\3\2\2\2\u0361\u036b\5\"\22\2\u0362\u036c\5\u00d2j\2\u0363\u0368\5\u00d2"+
		"j\2\u0364\u0365\7\u0088\2\2\u0365\u0367\5\24\13\2\u0366\u0364\3\2\2\2"+
		"\u0367\u036a\3\2\2\2\u0368\u0366\3\2\2\2\u0368\u0369\3\2\2\2\u0369\u036c"+
		"\3\2\2\2\u036a\u0368\3\2\2\2\u036b\u0362\3\2\2\2\u036b\u0363\3\2\2\2\u036c"+
		"\u036d\3\2\2\2\u036d\u036e\5$\23\2\u036e[\3\2\2\2\u036f\u0370\5\"\22\2"+
		"\u0370\u0375\5\24\13\2\u0371\u0372\7\u0088\2\2\u0372\u0374\5\24\13\2\u0373"+
		"\u0371\3\2\2\2\u0374\u0377\3\2\2\2\u0375\u0373\3\2\2\2\u0375\u0376\3\2"+
		"\2\2\u0376\u0378\3\2\2\2\u0377\u0375\3\2\2\2\u0378\u0379\5$\23\2\u0379"+
		"]\3\2\2\2\u037a\u037b\5\"\22\2\u037b\u037c\5\u00d2j\2\u037c\u037d\7\u0088"+
		"\2\2\u037d\u037e\5\u00d0i\2\u037e\u037f\5$\23\2\u037f_\3\2\2\2\u0380\u0381"+
		"\5\"\22\2\u0381\u0382\5\u00d2j\2\u0382\u0383\7\u0088\2\2\u0383\u0386\5"+
		"\u00d2j\2\u0384\u0385\7\u0088\2\2\u0385\u0387\5\30\r\2\u0386\u0384\3\2"+
		"\2\2\u0386\u0387\3\2\2\2\u0387\u0388\3\2\2\2\u0388\u0389\5$\23\2\u0389"+
		"a\3\2\2\2\u038a\u038b\5\"\22\2\u038b\u038c\5\u00d2j\2\u038c\u038d\7\u0088"+
		"\2\2\u038d\u038e\5\u00d2j\2\u038e\u038f\5$\23\2\u038fc\3\2\2\2\u0390\u0391"+
		"\5\"\22\2\u0391\u0394\5\u00d2j\2\u0392\u0393\7\u0088\2\2\u0393\u0395\5"+
		"\u00d0i\2\u0394\u0392\3\2\2\2\u0394\u0395\3\2\2\2\u0395\u0396\3\2\2\2"+
		"\u0396\u0397\5$\23\2\u0397e\3\2\2\2\u0398\u039c\5\"\22\2\u0399\u039a\5"+
		"\u00d2j\2\u039a\u039b\7\u0088\2\2\u039b\u039d\3\2\2\2\u039c\u0399\3\2"+
		"\2\2\u039c\u039d\3\2\2\2\u039d\u039e\3\2\2\2\u039e\u039f\5\u00d2j\2\u039f"+
		"\u03a0\5$\23\2\u03a0g\3\2\2\2\u03a1\u03a5\5\"\22\2\u03a2\u03a3\5\u00d2"+
		"j\2\u03a3\u03a4\7\u0088\2\2\u03a4\u03a6\3\2\2\2\u03a5\u03a2\3\2\2\2\u03a5"+
		"\u03a6\3\2\2\2\u03a6\u03a7\3\2\2\2\u03a7\u03a8\5\u00d2j\2\u03a8\u03a9"+
		"\7\u0088\2\2\u03a9\u03aa\5\30\r\2\u03aa\u03ab\7\u0088\2\2\u03ab\u03ae"+
		"\5\30\r\2\u03ac\u03ad\7\u0088\2\2\u03ad\u03af\5\30\r\2\u03ae\u03ac\3\2"+
		"\2\2\u03ae\u03af\3\2\2\2\u03af\u03b0\3\2\2\2\u03b0\u03b1\5$\23\2\u03b1"+
		"i\3\2\2\2\u03b2\u03b6\5\"\22\2\u03b3\u03b4\5\u00d2j\2\u03b4\u03b5\7\u0088"+
		"\2\2\u03b5\u03b7\3\2\2\2\u03b6\u03b3\3\2\2\2\u03b6\u03b7\3\2\2\2\u03b7"+
		"\u03b8\3\2\2\2\u03b8\u03b9\5\u00d2j\2\u03b9\u03ba\7\u0088\2\2\u03ba\u03bd"+
		"\5\24\13\2\u03bb\u03bc\7\u0088\2\2\u03bc\u03be\5\30\r\2\u03bd\u03bb\3"+
		"\2\2\2\u03bd\u03be\3\2\2\2\u03be\u03bf\3\2\2\2\u03bf\u03c0\5$\23\2\u03c0"+
		"k\3\2\2\2\u03c1\u03c5\5\"\22\2\u03c2\u03c3\5\u00d2j\2\u03c3\u03c4\7\u0088"+
		"\2\2\u03c4\u03c6\3\2\2\2\u03c5\u03c2\3\2\2\2\u03c5\u03c6\3\2\2\2\u03c6"+
		"\u03c7\3\2\2\2\u03c7\u03c8\5\u00d2j\2\u03c8\u03c9\7\u0088\2\2\u03c9\u03ca"+
		"\5\24\13\2\u03ca\u03cb\7\u0088\2\2\u03cb\u03cc\5\30\r\2\u03cc\u03cd\7"+
		"\u0088\2\2\u03cd\u03d0\5\30\r\2\u03ce\u03cf\7\u0088\2\2\u03cf\u03d1\5"+
		"\30\r\2\u03d0\u03ce\3\2\2\2\u03d0\u03d1\3\2\2\2\u03d1\u03d2\3\2\2\2\u03d2"+
		"\u03d3\5$\23\2\u03d3m\3\2\2\2\u03d4\u03d8\5\"\22\2\u03d5\u03d6\5\u00d2"+
		"j\2\u03d6\u03d7\7\u0088\2\2\u03d7\u03d9\3\2\2\2\u03d8\u03d5\3\2\2\2\u03d8"+
		"\u03d9\3\2\2\2\u03d9\u03da\3\2\2\2\u03da\u03db\5\u00d2j\2\u03db\u03dc"+
		"\7\u0088\2\2\u03dc\u03df\5\26\f\2\u03dd\u03de\7\u0088\2\2\u03de\u03e0"+
		"\5\30\r\2\u03df\u03dd\3\2\2\2\u03df\u03e0\3\2\2\2\u03e0\u03e1\3\2\2\2"+
		"\u03e1\u03e2\5$\23\2\u03e2o\3\2\2\2\u03e3\u03e7\5\"\22\2\u03e4\u03e5\5"+
		"\u00d2j\2\u03e5\u03e6\7\u0088\2\2\u03e6\u03e8\3\2\2\2\u03e7\u03e4\3\2"+
		"\2\2\u03e7\u03e8\3\2\2\2\u03e8\u03e9\3\2\2\2\u03e9\u03ea\5\u00d2j\2\u03ea"+
		"\u03eb\7\u0088\2\2\u03eb\u03ec\5\26\f\2\u03ec\u03ed\7\u0088\2\2\u03ed"+
		"\u03ee\5\30\r\2\u03ee\u03ef\7\u0088\2\2\u03ef\u03f2\5\30\r\2\u03f0\u03f1"+
		"\7\u0088\2\2\u03f1\u03f3\5\30\r\2\u03f2\u03f0\3\2\2\2\u03f2\u03f3\3\2"+
		"\2\2\u03f3\u03f4\3\2\2\2\u03f4\u03f5\5$\23\2\u03f5q\3\2\2\2\u03f6\u03fa"+
		"\5\"\22\2\u03f7\u03f8\5\u00d2j\2\u03f8\u03f9\7\u0088\2\2\u03f9\u03fb\3"+
		"\2\2\2\u03fa\u03f7\3\2\2\2\u03fa\u03fb\3\2\2\2\u03fb\u03fc\3\2\2\2\u03fc"+
		"\u03fd\5\u00d2j\2\u03fd\u03fe\7\u0088\2\2\u03fe\u03ff\5\24\13\2\u03ff"+
		"\u0400\7\u0088\2\2\u0400\u0403\5\26\f\2\u0401\u0402\7\u0088\2\2\u0402"+
		"\u0404\5\30\r\2\u0403\u0401\3\2\2\2\u0403\u0404\3\2\2\2\u0404\u0405\3"+
		"\2\2\2\u0405\u0406\5$\23\2\u0406s\3\2\2\2\u0407\u040b\5\"\22\2\u0408\u0409"+
		"\5\u00d2j\2\u0409\u040a\7\u0088\2\2\u040a\u040c\3\2\2\2\u040b\u0408\3"+
		"\2\2\2\u040b\u040c\3\2\2\2\u040c\u040d\3\2\2\2\u040d\u040e\5\u00d2j\2"+
		"\u040e\u040f\7\u0088\2\2\u040f\u0410\5\24\13\2\u0410\u0411\7\u0088\2\2"+
		"\u0411\u0412\5\26\f\2\u0412\u0413\7\u0088\2\2\u0413\u0414\5\30\r\2\u0414"+
		"\u0415\7\u0088\2\2\u0415\u0418\5\30\r\2\u0416\u0417\7\u0088\2\2\u0417"+
		"\u0419\5\30\r\2\u0418\u0416\3\2\2\2\u0418\u0419\3\2\2\2\u0419\u041a\3"+
		"\2\2\2\u041a\u041b\5$\23\2\u041bu\3\2\2\2\u041c\u0420\5\"\22\2\u041d\u041e"+
		"\5\u00d2j\2\u041e\u041f\7\u0088\2\2\u041f\u0421\3\2\2\2\u0420\u041d\3"+
		"\2\2\2\u0420\u0421\3\2\2\2\u0421\u0422\3\2\2\2\u0422\u0423\5\u00d2j\2"+
		"\u0423\u0424\7\u0088\2\2\u0424\u0425\5\30\r\2\u0425\u0426\5$\23\2\u0426"+
		"w\3\2\2\2\u0427\u042b\5\"\22\2\u0428\u0429\5\u00d2j\2\u0429\u042a\7\u0088"+
		"\2\2\u042a\u042c\3\2\2\2\u042b\u0428\3\2\2\2\u042b\u042c\3\2\2\2\u042c"+
		"\u042d\3\2\2\2\u042d\u042e\5\u00d2j\2\u042e\u042f\7\u0088\2\2\u042f\u0430"+
		"\5\30\r\2\u0430\u0431\7\u0088\2\2\u0431\u0432\5\30\r\2\u0432\u0433\5$"+
		"\23\2\u0433y\3\2\2\2\u0434\u0438\5\"\22\2\u0435\u0436\5\u00d2j\2\u0436"+
		"\u0437\7\u0088\2\2\u0437\u0439\3\2\2\2\u0438\u0435\3\2\2\2\u0438\u0439"+
		"\3\2\2\2\u0439\u043a\3\2\2\2\u043a\u043b\5\u00d2j\2\u043b\u043c\7\u0088"+
		"\2\2\u043c\u043d\5\30\r\2\u043d\u043e\7\u0088\2\2\u043e\u043f\5\30\r\2"+
		"\u043f\u0440\7\u0088\2\2\u0440\u0443\5\30\r\2\u0441\u0442\7\u0088\2\2"+
		"\u0442\u0444\5\30\r\2\u0443\u0441\3\2\2\2\u0443\u0444\3\2\2\2\u0444\u0445"+
		"\3\2\2\2\u0445\u0446\5$\23\2\u0446{\3\2\2\2\u0447\u044b\5\"\22\2\u0448"+
		"\u0449\5\u00d2j\2\u0449\u044a\7\u0088\2\2\u044a\u044c\3\2\2\2\u044b\u0448"+
		"\3\2\2\2\u044b\u044c\3\2\2\2\u044c\u044d\3\2\2\2\u044d\u044e\5\u00d2j"+
		"\2\u044e\u044f\7\u0088\2\2\u044f\u0450\5\24\13\2\u0450\u0451\7\u0088\2"+
		"\2\u0451\u0452\5\30\r\2\u0452\u0453\7\u0088\2\2\u0453\u0454\5\30\r\2\u0454"+
		"\u0455\5$\23\2\u0455}\3\2\2\2\u0456\u045a\5\"\22\2\u0457\u0458\5\u00d2"+
		"j\2\u0458\u0459\7\u0088\2\2\u0459\u045b\3\2\2\2\u045a\u0457\3\2\2\2\u045a"+
		"\u045b\3\2\2\2\u045b\u045c\3\2\2\2\u045c\u045d\5\u00d2j\2\u045d\u045e"+
		"\7\u0088\2\2\u045e\u045f\5\24\13\2\u045f\u0460\7\u0088\2\2\u0460\u0461"+
		"\5\30\r\2\u0461\u0462\7\u0088\2\2\u0462\u0463\5\30\r\2\u0463\u0464\7\u0088"+
		"\2\2\u0464\u0467\5\30\r\2\u0465\u0466\7\u0088\2\2\u0466\u0468\5\30\r\2"+
		"\u0467\u0465\3\2\2\2\u0467\u0468\3\2\2\2\u0468\u0469\3\2\2\2\u0469\u046a"+
		"\5$\23\2\u046a\177\3\2\2\2\u046b\u046f\5\"\22\2\u046c\u046d\5\u00d2j\2"+
		"\u046d\u046e\7\u0088\2\2\u046e\u0470\3\2\2\2\u046f\u046c\3\2\2\2\u046f"+
		"\u0470\3\2\2\2\u0470\u0471\3\2\2\2\u0471\u0472\5\u00d2j\2\u0472\u0473"+
		"\7\u0088\2\2\u0473\u0474\5\26\f\2\u0474\u0475\7\u0088\2\2\u0475\u0476"+
		"\5\30\r\2\u0476\u0477\7\u0088\2\2\u0477\u0478\5\30\r\2\u0478\u0479\5$"+
		"\23\2\u0479\u0081\3\2\2\2\u047a\u047e\5\"\22\2\u047b\u047c\5\u00d2j\2"+
		"\u047c\u047d\7\u0088\2\2\u047d\u047f\3\2\2\2\u047e\u047b\3\2\2\2\u047e"+
		"\u047f\3\2\2\2\u047f\u0480\3\2\2\2\u0480\u0481\5\u00d2j\2\u0481\u0482"+
		"\7\u0088\2\2\u0482\u0483\5\26\f\2\u0483\u0484\7\u0088\2\2\u0484\u0485"+
		"\5\30\r\2\u0485\u0486\7\u0088\2\2\u0486\u0487\5\30\r\2\u0487\u0488\7\u0088"+
		"\2\2\u0488\u048b\5\30\r\2\u0489\u048a\7\u0088\2\2\u048a\u048c\5\30\r\2"+
		"\u048b\u0489\3\2\2\2\u048b\u048c\3\2\2\2\u048c\u048d\3\2\2\2\u048d\u048e"+
		"\5$\23\2\u048e\u0083\3\2\2\2\u048f\u0493\5\"\22\2\u0490\u0491\5\u00d2"+
		"j\2\u0491\u0492\7\u0088\2\2\u0492\u0494\3\2\2\2\u0493\u0490\3\2\2\2\u0493"+
		"\u0494\3\2\2\2\u0494\u0495\3\2\2\2\u0495\u0496\5\u00d2j\2\u0496\u0497"+
		"\7\u0088\2\2\u0497\u0498\5\24\13\2\u0498\u0499\7\u0088\2\2\u0499\u049a"+
		"\5\26\f\2\u049a\u049b\7\u0088\2\2\u049b\u049c\5\30\r\2\u049c\u049d\7\u0088"+
		"\2\2\u049d\u049e\5\30\r\2\u049e\u049f\5$\23\2\u049f\u0085\3\2\2\2\u04a0"+
		"\u04a4\5\"\22\2\u04a1\u04a2\5\u00d2j\2\u04a2\u04a3\7\u0088\2\2\u04a3\u04a5"+
		"\3\2\2\2\u04a4\u04a1\3\2\2\2\u04a4\u04a5\3\2\2\2\u04a5\u04a6\3\2\2\2\u04a6"+
		"\u04a7\5\u00d2j\2\u04a7\u04a8\7\u0088\2\2\u04a8\u04a9\5\24\13\2\u04a9"+
		"\u04aa\7\u0088\2\2\u04aa\u04ab\5\26\f\2\u04ab\u04ac\7\u0088\2\2\u04ac"+
		"\u04ad\5\30\r\2\u04ad\u04ae\7\u0088\2\2\u04ae\u04af\5\30\r\2\u04af\u04b0"+
		"\7\u0088\2\2\u04b0\u04b3\5\30\r\2\u04b1\u04b2\7\u0088\2\2\u04b2\u04b4"+
		"\5\30\r\2\u04b3\u04b1\3\2\2\2\u04b3\u04b4\3\2\2\2\u04b4\u04b5\3\2\2\2"+
		"\u04b5\u04b6\5$\23\2\u04b6\u0087\3\2\2\2\u04b7\u04d2\5\"\22\2\u04b8\u04b9"+
		"\5\u00d2j\2\u04b9\u04ba\7\u0088\2\2\u04ba\u04bc\3\2\2\2\u04bb\u04b8\3"+
		"\2\2\2\u04bb\u04bc\3\2\2\2\u04bc\u04bd\3\2\2\2\u04bd\u04d3\5\u00d0i\2"+
		"\u04be\u04bf\5\u00d2j\2\u04bf\u04c0\7\u0088\2\2\u04c0\u04c2\3\2\2\2\u04c1"+
		"\u04be\3\2\2\2\u04c1\u04c2\3\2\2\2\u04c2\u04c3\3\2\2\2\u04c3\u04c4\5\u00d0"+
		"i\2\u04c4\u04c5\7\u0088\2\2\u04c5\u04c6\5\30\r\2\u04c6\u04d3\3\2\2\2\u04c7"+
		"\u04c8\5\u00d2j\2\u04c8\u04c9\7\u0088\2\2\u04c9\u04cb\3\2\2\2\u04ca\u04c7"+
		"\3\2\2\2\u04ca\u04cb\3\2\2\2\u04cb\u04cc\3\2\2\2\u04cc\u04cd\5\u00d0i"+
		"\2\u04cd\u04ce\7\u0088\2\2\u04ce\u04cf\5\30\r\2\u04cf\u04d0\7\u0088\2"+
		"\2\u04d0\u04d1\5\30\r\2\u04d1\u04d3\3\2\2\2\u04d2\u04bb\3\2\2\2\u04d2"+
		"\u04c1\3\2\2\2\u04d2\u04ca\3\2\2\2\u04d3\u04d4\3\2\2\2\u04d4\u04d5\5$"+
		"\23\2\u04d5\u0089\3\2\2\2\u04d6\u04e7\5\"\22\2\u04d7\u04e8\5\u00d2j\2"+
		"\u04d8\u04d9\5\u00d2j\2\u04d9\u04da\7\u0088\2\2\u04da\u04dc\3\2\2\2\u04db"+
		"\u04d8\3\2\2\2\u04db\u04dc\3\2\2\2\u04dc\u04dd\3\2\2\2\u04dd\u04e8\5\30"+
		"\r\2\u04de\u04df\5\u00d2j\2\u04df\u04e0\7\u0088\2\2\u04e0\u04e2\3\2\2"+
		"\2\u04e1\u04de\3\2\2\2\u04e1\u04e2\3\2\2\2\u04e2\u04e3\3\2\2\2\u04e3\u04e4"+
		"\5\30\r\2\u04e4\u04e5\7\u0088\2\2\u04e5\u04e6\5\30\r\2\u04e6\u04e8\3\2"+
		"\2\2\u04e7\u04d7\3\2\2\2\u04e7\u04db\3\2\2\2\u04e7\u04e1\3\2\2\2\u04e8"+
		"\u04e9\3\2\2\2\u04e9\u04ea\5$\23\2\u04ea\u008b\3\2\2\2\u04eb\u04f3\5\""+
		"\22\2\u04ec\u04f4\5\u00d2j\2\u04ed\u04ee\5\u00d2j\2\u04ee\u04ef\7\u0088"+
		"\2\2\u04ef\u04f1\3\2\2\2\u04f0\u04ed\3\2\2\2\u04f0\u04f1\3\2\2\2\u04f1"+
		"\u04f2\3\2\2\2\u04f2\u04f4\5\30\r\2\u04f3\u04ec\3\2\2\2\u04f3\u04f0\3"+
		"\2\2\2\u04f4\u04f5\3\2\2\2\u04f5\u04f6\5$\23\2\u04f6\u008d\3\2\2\2\u04f7"+
		"\u04fb\5\"\22\2\u04f8\u04f9\5\u00d2j\2\u04f9\u04fa\7\u0088\2\2\u04fa\u04fc"+
		"\3\2\2\2\u04fb\u04f8\3\2\2\2\u04fb\u04fc\3\2\2\2\u04fc\u04fd\3\2\2\2\u04fd"+
		"\u04fe\5\30\r\2\u04fe\u04ff\7\u0088\2\2\u04ff\u0500\5\30\r\2\u0500\u0501"+
		"\5$\23\2\u0501\u008f\3\2\2\2\u0502\u0506\5\"\22\2\u0503\u0504\5\u00d2"+
		"j\2\u0504\u0505\7\u0088\2\2\u0505\u0507\3\2\2\2\u0506\u0503\3\2\2\2\u0506"+
		"\u0507\3\2\2\2\u0507\u0508\3\2\2\2\u0508\u0509\5\30\r\2\u0509\u050a\7"+
		"\u0088\2\2\u050a\u050b\5\30\r\2\u050b\u050c\7\u0088\2\2\u050c\u050f\5"+
		"\30\r\2\u050d\u050e\7\u0088\2\2\u050e\u0510\5\30\r\2\u050f\u050d\3\2\2"+
		"\2\u050f\u0510\3\2\2\2\u0510\u0511\3\2\2\2\u0511\u0512\5$\23\2\u0512\u0091"+
		"\3\2\2\2\u0513\u0514\5\"\22\2\u0514\u0515\5\30\r\2\u0515\u0516\5$\23\2"+
		"\u0516\u0093\3\2\2\2\u0517\u0518\5\"\22\2\u0518\u0519\5\30\r\2\u0519\u051a"+
		"\7\u0088\2\2\u051a\u051b\5\30\r\2\u051b\u051c\5$\23\2\u051c\u0095\3\2"+
		"\2\2\u051d\u051e\5\"\22\2\u051e\u051f\5\u00d2j\2\u051f\u0520\5$\23\2\u0520"+
		"\u0097\3\2\2\2\u0521\u0522\5\"\22\2\u0522\u0523\5\u00d2j\2\u0523\u0524"+
		"\7\u0088\2\2\u0524\u0527\5\u00a8U\2\u0525\u0526\7\u0088\2\2\u0526\u0528"+
		"\5\u00aaV\2\u0527\u0525\3\2\2\2\u0527\u0528\3\2\2\2\u0528\u052b\3\2\2"+
		"\2\u0529\u052a\7\u0088\2\2\u052a\u052c\5\u00a6T\2\u052b\u0529\3\2\2\2"+
		"\u052b\u052c\3\2\2\2\u052c\u052d\3\2\2\2\u052d\u052e\5$\23\2\u052e\u0099"+
		"\3\2\2\2\u052f\u0530\5\"\22\2\u0530\u0531\5\u00d2j\2\u0531\u0532\7\u0088"+
		"\2\2\u0532\u0535\5\u00aaV\2\u0533\u0534\7\u0088\2\2\u0534\u0536\5\u00a6"+
		"T\2\u0535\u0533\3\2\2\2\u0535\u0536\3\2\2\2\u0536\u0537\3\2\2\2\u0537"+
		"\u0538\5$\23\2\u0538\u009b\3\2\2\2\u0539\u053a\5\"\22\2\u053a\u053b\5"+
		"\u00d2j\2\u053b\u053c\7\u0088\2\2\u053c\u053d\5\u00a6T\2\u053d\u053e\5"+
		"$\23\2\u053e\u009d\3\2\2\2\u053f\u0540\5\"\22\2\u0540\u0543\5\u00a8U\2"+
		"\u0541\u0542\7\u0088\2\2\u0542\u0544\5\u00aaV\2\u0543\u0541\3\2\2\2\u0543"+
		"\u0544\3\2\2\2\u0544\u0547\3\2\2\2\u0545\u0546\7\u0088\2\2\u0546\u0548"+
		"\5\u00a6T\2\u0547\u0545\3\2\2\2\u0547\u0548\3\2\2\2\u0548\u0549\3\2\2"+
		"\2\u0549\u054a\5$\23\2\u054a\u009f\3\2\2\2\u054b\u054c\5\"\22\2\u054c"+
		"\u054f\5\u00aaV\2\u054d\u054e\7\u0088\2\2\u054e\u0550\5\u00a6T\2\u054f"+
		"\u054d\3\2\2\2\u054f\u0550\3\2\2\2\u0550\u0551\3\2\2\2\u0551\u0552\5$"+
		"\23\2\u0552\u00a1\3\2\2\2\u0553\u0554\5\"\22\2\u0554\u0555\5\u00a6T\2"+
		"\u0555\u0556\5$\23\2\u0556\u00a3\3\2\2\2\u0557\u0558\5\"\22\2\u0558\u055b"+
		"\5\u00d2j\2\u0559\u055a\7\u0088\2\2\u055a\u055c\5\u00d2j\2\u055b\u0559"+
		"\3\2\2\2\u055b\u055c\3\2\2\2\u055c\u055f\3\2\2\2\u055d\u055e\7\u0088\2"+
		"\2\u055e\u0560\5\u00a8U\2\u055f\u055d\3\2\2\2\u055f\u0560\3\2\2\2\u0560"+
		"\u0563\3\2\2\2\u0561\u0562\7\u0088\2\2\u0562\u0564\5\u00aaV\2\u0563\u0561"+
		"\3\2\2\2\u0563\u0564\3\2\2\2\u0564\u0567\3\2\2\2\u0565\u0566\7\u0088\2"+
		"\2\u0566\u0568\5\u00a6T\2\u0567\u0565\3\2\2\2\u0567\u0568\3\2\2\2\u0568"+
		"\u0569\3\2\2\2\u0569\u056a\5$\23\2\u056a\u00a5\3\2\2\2\u056b\u0571\5\30"+
		"\r\2\u056c\u056d\5\30\r\2\u056d\u056e\7\u0088\2\2\u056e\u056f\5\30\r\2"+
		"\u056f\u0571\3\2\2\2\u0570\u056b\3\2\2\2\u0570\u056c\3\2\2\2\u0571\u00a7"+
		"\3\2\2\2\u0572\u0578\5\24\13\2\u0573\u0574\5\24\13\2\u0574\u0575\7\u0088"+
		"\2\2\u0575\u0576\5\24\13\2\u0576\u0578\3\2\2\2\u0577\u0572\3\2\2\2\u0577"+
		"\u0573\3\2\2\2\u0578\u00a9\3\2\2\2\u0579\u057f\5\26\f\2\u057a\u057b\5"+
		"\26\f\2\u057b\u057c\7\u0088\2\2\u057c\u057d\5\26\f\2\u057d\u057f\3\2\2"+
		"\2\u057e\u0579\3\2\2\2\u057e\u057a\3\2\2\2\u057f\u00ab\3\2\2\2\u0580\u0581"+
		"\5\"\22\2\u0581\u0582\5\u00d2j\2\u0582\u0583\7\u0088\2\2\u0583\u0584\5"+
		"\u00d0i\2\u0584\u0585\5$\23\2\u0585\u00ad\3\2\2\2\u0586\u0587\5\"\22\2"+
		"\u0587\u058a\5\u00d2j\2\u0588\u0589\7\u0088\2\2\u0589\u058b\5\u00d2j\2"+
		"\u058a\u0588\3\2\2\2\u058a\u058b\3\2\2\2\u058b\u058c\3\2\2\2\u058c\u058d"+
		"\5$\23\2\u058d\u00af\3\2\2\2\u058e\u058f\5\"\22\2\u058f\u0590\5\u00d0"+
		"i\2\u0590\u0591\5$\23\2\u0591\u00b1\3\2\2\2\u0592\u0593\5\"\22\2\u0593"+
		"\u0598\5\u00d2j\2\u0594\u0595\7\u0088\2\2\u0595\u0597\5\30\r\2\u0596\u0594"+
		"\3\2\2\2\u0597\u059a\3\2\2\2\u0598\u0596\3\2\2\2\u0598\u0599\3\2\2\2\u0599"+
		"\u059b\3\2\2\2\u059a\u0598\3\2\2\2\u059b\u059c\5$\23\2\u059c\u00b3\3\2"+
		"\2\2\u059d\u059e\5\"\22\2\u059e\u059f\5\u00d2j\2\u059f\u05a0\7\u0088\2"+
		"\2\u05a0\u05a5\5\30\r\2\u05a1\u05a2\7\u0088\2\2\u05a2\u05a4\5\30\r\2\u05a3"+
		"\u05a1\3\2\2\2\u05a4\u05a7\3\2\2\2\u05a5\u05a3\3\2\2\2\u05a5\u05a6\3\2"+
		"\2\2\u05a6\u05a8\3\2\2\2\u05a7\u05a5\3\2\2\2\u05a8\u05a9\5$\23\2\u05a9"+
		"\u00b5\3\2\2\2\u05aa\u05ab\5\"\22\2\u05ab\u05ae\5\26\f\2\u05ac\u05ad\7"+
		"\u0088\2\2\u05ad\u05af\5\30\r\2\u05ae\u05ac\3\2\2\2\u05af\u05b0\3\2\2"+
		"\2\u05b0\u05ae\3\2\2\2\u05b0\u05b1\3\2\2\2\u05b1\u05b2\3\2\2\2\u05b2\u05b3"+
		"\5$\23\2\u05b3\u00b7\3\2\2\2\u05b4\u05b5\5\"\22\2\u05b5\u05b8\5\u00d2"+
		"j\2\u05b6\u05b7\7\u0088\2\2\u05b7\u05b9\5\30\r\2\u05b8\u05b6\3\2\2\2\u05b9"+
		"\u05ba\3\2\2\2\u05ba\u05b8\3\2\2\2\u05ba\u05bb\3\2\2\2\u05bb\u05bc\3\2"+
		"\2\2\u05bc\u05bd\5$\23\2\u05bd\u00b9\3\2\2\2\u05be\u05bf\5\"\22\2\u05bf"+
		"\u05c0\5\u00d2j\2\u05c0\u05c1\7\u0088\2\2\u05c1\u05c4\5\u00d2j\2\u05c2"+
		"\u05c3\7\u0088\2\2\u05c3\u05c5\5\30\r\2\u05c4\u05c2\3\2\2\2\u05c5\u05c6"+
		"\3\2\2\2\u05c6\u05c4\3\2\2\2\u05c6\u05c7\3\2\2\2\u05c7\u05c8\3\2\2\2\u05c8"+
		"\u05c9\5$\23\2\u05c9\u00bb\3\2\2\2\u05ca\u05cb\5\"\22\2\u05cb\u05cc\5"+
		"\u00d2j\2\u05cc\u05cd\7\u0088\2\2\u05cd\u05d0\5\26\f\2\u05ce\u05cf\7\u0088"+
		"\2\2\u05cf\u05d1\5\30\r\2\u05d0\u05ce\3\2\2\2\u05d1\u05d2\3\2\2\2\u05d2"+
		"\u05d0\3\2\2\2\u05d2\u05d3\3\2\2\2\u05d3\u05d4\3\2\2\2\u05d4\u05d5\5$"+
		"\23\2\u05d5\u00bd\3\2\2\2\u05d6\u05d7\5\"\22\2\u05d7\u05d8\5\u00d2j\2"+
		"\u05d8\u05d9\7\u0088\2\2\u05d9\u05da\5\u00d2j\2\u05da\u05db\7\u0088\2"+
		"\2\u05db\u05de\5\26\f\2\u05dc\u05dd\7\u0088\2\2\u05dd\u05df\5\30\r\2\u05de"+
		"\u05dc\3\2\2\2\u05df\u05e0\3\2\2\2\u05e0\u05de\3\2\2\2\u05e0\u05e1\3\2"+
		"\2\2\u05e1\u05e2\3\2\2\2\u05e2\u05e3\5$\23\2\u05e3\u00bf\3\2\2\2\u05e4"+
		"\u05e5\5\"\22\2\u05e5\u05ea\5\30\r\2\u05e6\u05e7\7\u0088\2\2\u05e7\u05e9"+
		"\5\30\r\2\u05e8\u05e6\3\2\2\2\u05e9\u05ec\3\2\2\2\u05ea\u05e8\3\2\2\2"+
		"\u05ea\u05eb\3\2\2\2\u05eb\u05ed\3\2\2\2\u05ec\u05ea\3\2\2\2\u05ed\u05ee"+
		"\5$\23\2\u05ee\u00c1\3\2\2\2\u05ef\u05f0\5\"\22\2\u05f0\u05f1\5\u00d2"+
		"j\2\u05f1\u05f2\7\u0088\2\2\u05f2\u05f3\5\u00d2j\2\u05f3\u05f4\5$\23\2"+
		"\u05f4\u00c3\3\2\2\2\u05f5\u05f9\5\"\22\2\u05f6\u05f7\5\24\13\2\u05f7"+
		"\u05f8\7\u0088\2\2\u05f8\u05fa\3\2\2\2\u05f9\u05f6\3\2\2\2\u05f9\u05fa"+
		"\3\2\2\2\u05fa\u05fb\3\2\2\2\u05fb\u05fe\5\26\f\2\u05fc\u05fd\7\u0088"+
		"\2\2\u05fd\u05ff\5\26\f\2\u05fe\u05fc\3\2\2\2\u05fe\u05ff\3\2\2\2\u05ff"+
		"\u0600\3\2\2\2\u0600\u0601\5$\23\2\u0601\u00c5\3\2\2\2\u0602\u0603\5\""+
		"\22\2\u0603\u0608\5\u00d2j\2\u0604\u0605\7\u0088\2\2\u0605\u0607\5\24"+
		"\13\2\u0606\u0604\3\2\2\2\u0607\u060a\3\2\2\2\u0608\u0606\3\2\2\2\u0608"+
		"\u0609\3\2\2\2\u0609\u060b\3\2\2\2\u060a\u0608\3\2\2\2\u060b\u060c\5$"+
		"\23\2\u060c\u00c7\3\2\2\2\u060d\u060e\5\"\22\2\u060e\u0613\5\u00d2j\2"+
		"\u060f\u0610\7\u0088\2\2\u0610\u0612\5\26\f\2\u0611\u060f\3\2\2\2\u0612"+
		"\u0615\3\2\2\2\u0613\u0611\3\2\2\2\u0613\u0614\3\2\2\2\u0614\u0616\3\2"+
		"\2\2\u0615\u0613\3\2\2\2\u0616\u0617\5$\23\2\u0617\u00c9\3\2\2\2\u0618"+
		"\u0619\5\"\22\2\u0619\u061e\5\u00d2j\2\u061a\u061b\7\u0088\2\2\u061b\u061d"+
		"\5\30\r\2\u061c\u061a\3\2\2\2\u061d\u0620\3\2\2\2\u061e\u061c\3\2\2\2"+
		"\u061e\u061f\3\2\2\2\u061f\u0621\3\2\2\2\u0620\u061e\3\2\2\2\u0621\u0622"+
		"\5$\23\2\u0622\u00cb\3\2\2\2\u0623\u0624\7w\2\2\u0624\u00cd\3\2\2\2\u0625"+
		"\u0626\7x\2\2\u0626\u00cf\3\2\2\2\u0627\u0632\5\u00ccg\2\u0628\u0632\5"+
		"\u00ceh\2\u0629\u062e\5\u00d2j\2\u062a\u062b\7\u0088\2\2\u062b\u062d\5"+
		"\u00d2j\2\u062c\u062a\3\2\2\2\u062d\u0630\3\2\2\2\u062e\u062c\3\2\2\2"+
		"\u062e\u062f\3\2\2\2\u062f\u0632\3\2\2\2\u0630\u062e\3\2\2\2\u0631\u0627"+
		"\3\2\2\2\u0631\u0628\3\2\2\2\u0631\u0629\3\2\2\2\u0632\u00d1\3\2\2\2\u0633"+
		"\u0643\5\u00ccg\2\u0634\u0643\5\u00ceh\2\u0635\u0643\7y\2\2\u0636\u0643"+
		"\7z\2\2\u0637\u0643\7{\2\2\u0638\u0643\7|\2\2\u0639\u0643\7}\2\2\u063a"+
		"\u0643\7~\2\2\u063b\u0643\7\177\2\2\u063c\u0643\7\u0080\2\2\u063d\u0643"+
		"\7\u0081\2\2\u063e\u0643\7\u0082\2\2\u063f\u0643\7\u0083\2\2\u0640\u0643"+
		"\7\u0084\2\2\u0641\u0643\7\u0085\2\2\u0642\u0633\3\2\2\2\u0642\u0634\3"+
		"\2\2\2\u0642\u0635\3\2\2\2\u0642\u0636\3\2\2\2\u0642\u0637\3\2\2\2\u0642"+
		"\u0638\3\2\2\2\u0642\u0639\3\2\2\2\u0642\u063a\3\2\2\2\u0642\u063b\3\2"+
		"\2\2\u0642\u063c\3\2\2\2\u0642\u063d\3\2\2\2\u0642\u063e\3\2\2\2\u0642"+
		"\u063f\3\2\2\2\u0642\u0640\3\2\2\2\u0642\u0641\3\2\2\2\u0643\u00d3\3\2"+
		"\2\2{\u00ed\u00f5\u00fe\u0103\u010a\u0131\u013a\u014d\u0158\u0168\u016d"+
		"\u0172\u017d\u018e\u01a0\u01a5\u01ae\u01b3\u01c0\u01c9\u01e0\u020b\u0216"+
		"\u0241\u0265\u026c\u0274\u027c\u0284\u028a\u0294\u02a2\u02ad\u02bb\u02c2"+
		"\u02c5\u02cf\u02de\u02f1\u02ff\u0329\u0331\u0335\u0345\u0351\u035c\u0368"+
		"\u036b\u0375\u0386\u0394\u039c\u03a5\u03ae\u03b6\u03bd\u03c5\u03d0\u03d8"+
		"\u03df\u03e7\u03f2\u03fa\u0403\u040b\u0418\u0420\u042b\u0438\u0443\u044b"+
		"\u045a\u0467\u046f\u047e\u048b\u0493\u04a4\u04b3\u04bb\u04c1\u04ca\u04d2"+
		"\u04db\u04e1\u04e7\u04f0\u04f3\u04fb\u0506\u050f\u0527\u052b\u0535\u0543"+
		"\u0547\u054f\u055b\u055f\u0563\u0567\u0570\u0577\u057e\u058a\u0598\u05a5"+
		"\u05b0\u05ba\u05c6\u05d2\u05e0\u05ea\u05f9\u05fe\u0608\u0613\u061e\u062e"+
		"\u0631\u0642";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
		_decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
		for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
			_decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
		}
	}
}