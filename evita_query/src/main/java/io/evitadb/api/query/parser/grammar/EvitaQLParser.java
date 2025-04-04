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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

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
		T__113=114, POSITIONAL_PARAMETER=115, NAMED_PARAMETER=116, STRING=117,
		INT=118, FLOAT=119, BOOLEAN=120, DATE=121, TIME=122, DATE_TIME=123, OFFSET_DATE_TIME=124,
		FLOAT_NUMBER_RANGE=125, INT_NUMBER_RANGE=126, DATE_TIME_RANGE=127, UUID=128,
		ENUM=129, ARGS_OPENING=130, ARGS_CLOSING=131, ARGS_DELIMITER=132, COMMENT=133,
		WHITESPACE=134, UNEXPECTED_CHAR=135;
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
			"'hierarchyContent'", "'priceType'", "'dataInLocalesAll'", "'dataInLocales'",
			"'facetSummary'", "'facetSummaryOfReference'", "'facetGroupsConjunction'",
			"'facetGroupsDisjunction'", "'facetGroupsNegation'", "'facetGroupsExclusivity'",
			"'facetCalculationRules'", "'attributeHistogram'", "'priceHistogram'",
			"'distance'", "'level'", "'node'", "'stopAt'", "'statistics'", "'fromRoot'",
			"'fromNode'", "'children'", "'siblings'", "'spacing'", "'gap'", "'parents'",
			"'hierarchyOfSelf'", "'hierarchyOfReference'", "'queryTelemetry'", "'?'",
			null, null, null, null, null, null, null, null, null, null, null, null,
			null, null, "'('", "')'", "','"
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
			null, null, null, null, null, null, null, "POSITIONAL_PARAMETER", "NAMED_PARAMETER",
			"STRING", "INT", "FLOAT", "BOOLEAN", "DATE", "TIME", "DATE_TIME", "OFFSET_DATE_TIME",
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
			setState(604);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,23,_ctx) ) {
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
				_localctx = new PriceTypeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 42);
				{
				setState(516);
				match(T__87);
				setState(517);
				((PriceTypeConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case 43:
				_localctx = new DataInLocalesAllConstraintContext(_localctx);
				enterOuterAlt(_localctx, 43);
				{
				setState(518);
				match(T__88);
				setState(519);
				emptyArgs();
				}
				break;
			case 44:
				_localctx = new DataInLocalesConstraintContext(_localctx);
				enterOuterAlt(_localctx, 44);
				{
				setState(520);
				match(T__89);
				setState(521);
				((DataInLocalesConstraintContext)_localctx).args = valueListArgs();
				}
				break;
			case 45:
				_localctx = new FacetSummary1ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 45);
				{
				setState(522);
				match(T__90);
				setState(525);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,21,_ctx) ) {
				case 1:
					{
					setState(523);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(524);
					((FacetSummary1ConstraintContext)_localctx).args = facetSummary1Args();
					}
					break;
				}
				}
				break;
			case 46:
				_localctx = new FacetSummary2ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 46);
				{
				setState(527);
				match(T__90);
				setState(528);
				((FacetSummary2ConstraintContext)_localctx).args = facetSummary2Args();
				}
				break;
			case 47:
				_localctx = new FacetSummary3ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 47);
				{
				setState(529);
				match(T__90);
				setState(530);
				((FacetSummary3ConstraintContext)_localctx).args = facetSummary3Args();
				}
				break;
			case 48:
				_localctx = new FacetSummary4ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 48);
				{
				setState(531);
				match(T__90);
				setState(532);
				((FacetSummary4ConstraintContext)_localctx).args = facetSummary4Args();
				}
				break;
			case 49:
				_localctx = new FacetSummary5ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 49);
				{
				setState(533);
				match(T__90);
				setState(534);
				((FacetSummary5ConstraintContext)_localctx).args = facetSummary5Args();
				}
				break;
			case 50:
				_localctx = new FacetSummary6ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 50);
				{
				setState(535);
				match(T__90);
				setState(536);
				((FacetSummary6ConstraintContext)_localctx).args = facetSummary6Args();
				}
				break;
			case 51:
				_localctx = new FacetSummary7ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 51);
				{
				setState(537);
				match(T__90);
				setState(538);
				((FacetSummary7ConstraintContext)_localctx).args = facetSummary7Args();
				}
				break;
			case 52:
				_localctx = new FacetSummaryOfReference1ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 52);
				{
				setState(539);
				match(T__91);
				setState(540);
				((FacetSummaryOfReference1ConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case 53:
				_localctx = new FacetSummaryOfReference2ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 53);
				{
				setState(541);
				match(T__91);
				setState(542);
				((FacetSummaryOfReference2ConstraintContext)_localctx).args = facetSummaryOfReference2Args();
				}
				break;
			case 54:
				_localctx = new FacetGroupsConjunctionConstraintContext(_localctx);
				enterOuterAlt(_localctx, 54);
				{
				setState(543);
				match(T__92);
				setState(544);
				((FacetGroupsConjunctionConstraintContext)_localctx).args = facetGroupRelationArgs();
				}
				break;
			case 55:
				_localctx = new FacetGroupsDisjunctionConstraintContext(_localctx);
				enterOuterAlt(_localctx, 55);
				{
				setState(545);
				match(T__93);
				setState(546);
				((FacetGroupsDisjunctionConstraintContext)_localctx).args = facetGroupRelationArgs();
				}
				break;
			case 56:
				_localctx = new FacetGroupsNegationConstraintContext(_localctx);
				enterOuterAlt(_localctx, 56);
				{
				setState(547);
				match(T__94);
				setState(548);
				((FacetGroupsNegationConstraintContext)_localctx).args = facetGroupRelationArgs();
				}
				break;
			case 57:
				_localctx = new FacetGroupsExclusivityConstraintContext(_localctx);
				enterOuterAlt(_localctx, 57);
				{
				setState(549);
				match(T__95);
				setState(550);
				((FacetGroupsExclusivityConstraintContext)_localctx).args = facetGroupRelationArgs();
				}
				break;
			case 58:
				_localctx = new FacetCalculationRulesConstraintContext(_localctx);
				enterOuterAlt(_localctx, 58);
				{
				setState(551);
				match(T__96);
				setState(552);
				((FacetCalculationRulesConstraintContext)_localctx).args = facetCalculationRulesArgs();
				}
				break;
			case 59:
				_localctx = new AttributeHistogramConstraintContext(_localctx);
				enterOuterAlt(_localctx, 59);
				{
				setState(553);
				match(T__97);
				setState(554);
				((AttributeHistogramConstraintContext)_localctx).args = attributeHistogramArgs();
				}
				break;
			case 60:
				_localctx = new PriceHistogramConstraintContext(_localctx);
				enterOuterAlt(_localctx, 60);
				{
				setState(555);
				match(T__98);
				setState(556);
				((PriceHistogramConstraintContext)_localctx).args = priceHistogramArgs();
				}
				break;
			case 61:
				_localctx = new HierarchyDistanceConstraintContext(_localctx);
				enterOuterAlt(_localctx, 61);
				{
				setState(557);
				match(T__99);
				setState(558);
				((HierarchyDistanceConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case 62:
				_localctx = new HierarchyLevelConstraintContext(_localctx);
				enterOuterAlt(_localctx, 62);
				{
				setState(559);
				match(T__100);
				setState(560);
				((HierarchyLevelConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case 63:
				_localctx = new HierarchyNodeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 63);
				{
				setState(561);
				match(T__101);
				setState(562);
				((HierarchyNodeConstraintContext)_localctx).args = filterConstraintArgs();
				}
				break;
			case 64:
				_localctx = new HierarchyStopAtConstraintContext(_localctx);
				enterOuterAlt(_localctx, 64);
				{
				setState(563);
				match(T__102);
				setState(564);
				((HierarchyStopAtConstraintContext)_localctx).args = requireConstraintArgs();
				}
				break;
			case 65:
				_localctx = new HierarchyStatisticsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 65);
				{
				setState(565);
				match(T__103);
				setState(568);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,22,_ctx) ) {
				case 1:
					{
					setState(566);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(567);
					((HierarchyStatisticsConstraintContext)_localctx).args = hierarchyStatisticsArgs();
					}
					break;
				}
				}
				break;
			case 66:
				_localctx = new HierarchyFromRootConstraintContext(_localctx);
				enterOuterAlt(_localctx, 66);
				{
				setState(570);
				match(T__104);
				setState(571);
				((HierarchyFromRootConstraintContext)_localctx).args = hierarchyRequireConstraintArgs();
				}
				break;
			case 67:
				_localctx = new HierarchyFromNodeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 67);
				{
				setState(572);
				match(T__105);
				setState(573);
				((HierarchyFromNodeConstraintContext)_localctx).args = hierarchyFromNodeArgs();
				}
				break;
			case 68:
				_localctx = new HierarchyChildrenConstraintContext(_localctx);
				enterOuterAlt(_localctx, 68);
				{
				setState(574);
				match(T__106);
				setState(575);
				((HierarchyChildrenConstraintContext)_localctx).args = hierarchyRequireConstraintArgs();
				}
				break;
			case 69:
				_localctx = new EmptyHierarchySiblingsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 69);
				{
				setState(576);
				match(T__107);
				setState(577);
				emptyArgs();
				}
				break;
			case 70:
				_localctx = new BasicHierarchySiblingsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 70);
				{
				setState(578);
				match(T__107);
				setState(579);
				((BasicHierarchySiblingsConstraintContext)_localctx).args = requireConstraintListArgs();
				}
				break;
			case 71:
				_localctx = new FullHierarchySiblingsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 71);
				{
				setState(580);
				match(T__107);
				setState(581);
				((FullHierarchySiblingsConstraintContext)_localctx).args = hierarchyRequireConstraintArgs();
				}
				break;
			case 72:
				_localctx = new SpacingConstraintContext(_localctx);
				enterOuterAlt(_localctx, 72);
				{
				setState(582);
				match(T__108);
				setState(583);
				((SpacingConstraintContext)_localctx).args = spacingRequireConstraintArgs();
				}
				break;
			case 73:
				_localctx = new GapConstraintContext(_localctx);
				enterOuterAlt(_localctx, 73);
				{
				setState(584);
				match(T__109);
				setState(585);
				((GapConstraintContext)_localctx).args = gapRequireConstraintArgs();
				}
				break;
			case 74:
				_localctx = new HierarchyParentsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 74);
				{
				setState(586);
				match(T__110);
				setState(587);
				((HierarchyParentsConstraintContext)_localctx).args = hierarchyRequireConstraintArgs();
				}
				break;
			case 75:
				_localctx = new BasicHierarchyOfSelfConstraintContext(_localctx);
				enterOuterAlt(_localctx, 75);
				{
				setState(588);
				match(T__111);
				setState(589);
				((BasicHierarchyOfSelfConstraintContext)_localctx).args = requireConstraintListArgs();
				}
				break;
			case 76:
				_localctx = new FullHierarchyOfSelfConstraintContext(_localctx);
				enterOuterAlt(_localctx, 76);
				{
				setState(590);
				match(T__111);
				setState(591);
				((FullHierarchyOfSelfConstraintContext)_localctx).args = fullHierarchyOfSelfArgs();
				}
				break;
			case 77:
				_localctx = new BasicHierarchyOfReferenceConstraintContext(_localctx);
				enterOuterAlt(_localctx, 77);
				{
				setState(592);
				match(T__112);
				setState(593);
				((BasicHierarchyOfReferenceConstraintContext)_localctx).args = basicHierarchyOfReferenceArgs();
				}
				break;
			case 78:
				_localctx = new BasicHierarchyOfReferenceWithBehaviourConstraintContext(_localctx);
				enterOuterAlt(_localctx, 78);
				{
				setState(594);
				match(T__112);
				setState(595);
				((BasicHierarchyOfReferenceWithBehaviourConstraintContext)_localctx).args = basicHierarchyOfReferenceWithBehaviourArgs();
				}
				break;
			case 79:
				_localctx = new FullHierarchyOfReferenceConstraintContext(_localctx);
				enterOuterAlt(_localctx, 79);
				{
				setState(596);
				match(T__112);
				setState(597);
				((FullHierarchyOfReferenceConstraintContext)_localctx).args = fullHierarchyOfReferenceArgs();
				}
				break;
			case 80:
				_localctx = new FullHierarchyOfReferenceWithBehaviourConstraintContext(_localctx);
				enterOuterAlt(_localctx, 80);
				{
				setState(598);
				match(T__112);
				setState(599);
				((FullHierarchyOfReferenceWithBehaviourConstraintContext)_localctx).args = fullHierarchyOfReferenceWithBehaviourArgs();
				}
				break;
			case 81:
				_localctx = new QueryTelemetryConstraintContext(_localctx);
				enterOuterAlt(_localctx, 81);
				{
				setState(600);
				match(T__113);
				setState(601);
				emptyArgs();
				}
				break;
			case 82:
				_localctx = new RequireInScopeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 82);
				{
				setState(602);
				match(T__48);
				setState(603);
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
			setState(606);
			((HeadConstraintListContext)_localctx).headConstraint = headConstraint();
			((HeadConstraintListContext)_localctx).constraints.add(((HeadConstraintListContext)_localctx).headConstraint);
			setState(611);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(607);
				match(ARGS_DELIMITER);
				setState(608);
				((HeadConstraintListContext)_localctx).headConstraint = headConstraint();
				((HeadConstraintListContext)_localctx).constraints.add(((HeadConstraintListContext)_localctx).headConstraint);
				}
				}
				setState(613);
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
			setState(614);
			((FilterConstraintListContext)_localctx).filterConstraint = filterConstraint();
			((FilterConstraintListContext)_localctx).constraints.add(((FilterConstraintListContext)_localctx).filterConstraint);
			setState(619);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(615);
				match(ARGS_DELIMITER);
				setState(616);
				((FilterConstraintListContext)_localctx).filterConstraint = filterConstraint();
				((FilterConstraintListContext)_localctx).constraints.add(((FilterConstraintListContext)_localctx).filterConstraint);
				}
				}
				setState(621);
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
			setState(622);
			((OrderConstraintListContext)_localctx).orderConstraint = orderConstraint();
			((OrderConstraintListContext)_localctx).constraints.add(((OrderConstraintListContext)_localctx).orderConstraint);
			setState(627);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(623);
				match(ARGS_DELIMITER);
				setState(624);
				((OrderConstraintListContext)_localctx).orderConstraint = orderConstraint();
				((OrderConstraintListContext)_localctx).constraints.add(((OrderConstraintListContext)_localctx).orderConstraint);
				}
				}
				setState(629);
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
			setState(630);
			((RequireConstraintListContext)_localctx).requireConstraint = requireConstraint();
			((RequireConstraintListContext)_localctx).constraints.add(((RequireConstraintListContext)_localctx).requireConstraint);
			setState(635);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(631);
				match(ARGS_DELIMITER);
				setState(632);
				((RequireConstraintListContext)_localctx).requireConstraint = requireConstraint();
				((RequireConstraintListContext)_localctx).constraints.add(((RequireConstraintListContext)_localctx).requireConstraint);
				}
				}
				setState(637);
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
			setState(638);
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
			setState(641);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==ARGS_DELIMITER) {
				{
				setState(640);
				match(ARGS_DELIMITER);
				}
			}

			setState(643);
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
			setState(645);
			argsOpening();
			setState(646);
			((ConstraintListArgsContext)_localctx).constraint = constraint();
			((ConstraintListArgsContext)_localctx).constraints.add(((ConstraintListArgsContext)_localctx).constraint);
			setState(651);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,29,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(647);
					match(ARGS_DELIMITER);
					setState(648);
					((ConstraintListArgsContext)_localctx).constraint = constraint();
					((ConstraintListArgsContext)_localctx).constraints.add(((ConstraintListArgsContext)_localctx).constraint);
					}
					}
				}
				setState(653);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,29,_ctx);
			}
			setState(654);
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
			setState(656);
			argsOpening();
			setState(657);
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
			setState(659);
			argsOpening();
			setState(660);
			((HeadConstraintListArgsContext)_localctx).headConstraint = headConstraint();
			((HeadConstraintListArgsContext)_localctx).constraints.add(((HeadConstraintListArgsContext)_localctx).headConstraint);
			setState(665);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,30,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(661);
					match(ARGS_DELIMITER);
					setState(662);
					((HeadConstraintListArgsContext)_localctx).headConstraint = headConstraint();
					((HeadConstraintListArgsContext)_localctx).constraints.add(((HeadConstraintListArgsContext)_localctx).headConstraint);
					}
					}
				}
				setState(667);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,30,_ctx);
			}
			setState(668);
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
			setState(670);
			argsOpening();
			setState(671);
			((FilterConstraintListArgsContext)_localctx).filterConstraint = filterConstraint();
			((FilterConstraintListArgsContext)_localctx).constraints.add(((FilterConstraintListArgsContext)_localctx).filterConstraint);
			setState(676);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,31,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(672);
					match(ARGS_DELIMITER);
					setState(673);
					((FilterConstraintListArgsContext)_localctx).filterConstraint = filterConstraint();
					((FilterConstraintListArgsContext)_localctx).constraints.add(((FilterConstraintListArgsContext)_localctx).filterConstraint);
					}
					}
				}
				setState(678);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,31,_ctx);
			}
			setState(679);
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
			setState(681);
			argsOpening();
			setState(682);
			((FilterConstraintArgsContext)_localctx).filter = filterConstraint();
			setState(683);
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
			setState(685);
			argsOpening();
			setState(700);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,34,_ctx) ) {
			case 1:
				{
				{
				setState(686);
				((TraverseOrderConstraintListArgsContext)_localctx).traversalMode = valueToken();
				}
				}
				break;
			case 2:
				{
				{
				setState(690);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (((((_la - 115)) & ~0x3f) == 0 && ((1L << (_la - 115)) & ((1L << (POSITIONAL_PARAMETER - 115)) | (1L << (NAMED_PARAMETER - 115)) | (1L << (STRING - 115)) | (1L << (INT - 115)) | (1L << (FLOAT - 115)) | (1L << (BOOLEAN - 115)) | (1L << (DATE - 115)) | (1L << (TIME - 115)) | (1L << (DATE_TIME - 115)) | (1L << (OFFSET_DATE_TIME - 115)) | (1L << (FLOAT_NUMBER_RANGE - 115)) | (1L << (INT_NUMBER_RANGE - 115)) | (1L << (DATE_TIME_RANGE - 115)) | (1L << (UUID - 115)) | (1L << (ENUM - 115)))) != 0)) {
					{
					setState(687);
					((TraverseOrderConstraintListArgsContext)_localctx).traversalMode = valueToken();
					setState(688);
					match(ARGS_DELIMITER);
					}
				}

				setState(692);
				((TraverseOrderConstraintListArgsContext)_localctx).orderConstraint = orderConstraint();
				((TraverseOrderConstraintListArgsContext)_localctx).constraints.add(((TraverseOrderConstraintListArgsContext)_localctx).orderConstraint);
				setState(697);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,33,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(693);
						match(ARGS_DELIMITER);
						setState(694);
						((TraverseOrderConstraintListArgsContext)_localctx).orderConstraint = orderConstraint();
						((TraverseOrderConstraintListArgsContext)_localctx).constraints.add(((TraverseOrderConstraintListArgsContext)_localctx).orderConstraint);
						}
						}
					}
					setState(699);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,33,_ctx);
				}
				}
				}
				break;
			}
			setState(702);
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
			setState(704);
			argsOpening();
			setState(705);
			((OrderConstraintListArgsContext)_localctx).orderConstraint = orderConstraint();
			((OrderConstraintListArgsContext)_localctx).constraints.add(((OrderConstraintListArgsContext)_localctx).orderConstraint);
			setState(710);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,35,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(706);
					match(ARGS_DELIMITER);
					setState(707);
					((OrderConstraintListArgsContext)_localctx).orderConstraint = orderConstraint();
					((OrderConstraintListArgsContext)_localctx).constraints.add(((OrderConstraintListArgsContext)_localctx).orderConstraint);
					}
					}
				}
				setState(712);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,35,_ctx);
			}
			setState(713);
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
			setState(715);
			argsOpening();
			setState(716);
			((RequireConstraintArgsContext)_localctx).requirement = requireConstraint();
			setState(717);
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
			setState(719);
			argsOpening();
			setState(720);
			((RequireConstraintListArgsContext)_localctx).requireConstraint = requireConstraint();
			((RequireConstraintListArgsContext)_localctx).requirements.add(((RequireConstraintListArgsContext)_localctx).requireConstraint);
			setState(725);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,36,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(721);
					match(ARGS_DELIMITER);
					setState(722);
					((RequireConstraintListArgsContext)_localctx).requireConstraint = requireConstraint();
					((RequireConstraintListArgsContext)_localctx).requirements.add(((RequireConstraintListArgsContext)_localctx).requireConstraint);
					}
					}
				}
				setState(727);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,36,_ctx);
			}
			setState(728);
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
			setState(730);
			argsOpening();
			setState(731);
			((ClassifierArgsContext)_localctx).classifier = valueToken();
			setState(732);
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
			setState(734);
			argsOpening();
			setState(735);
			((ClassifierWithValueArgsContext)_localctx).classifier = valueToken();
			setState(736);
			match(ARGS_DELIMITER);
			setState(737);
			((ClassifierWithValueArgsContext)_localctx).value = valueToken();
			setState(738);
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
			setState(740);
			argsOpening();
			setState(741);
			((ClassifierWithOptionalValueArgsContext)_localctx).classifier = valueToken();
			setState(744);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,37,_ctx) ) {
			case 1:
				{
				setState(742);
				match(ARGS_DELIMITER);
				setState(743);
				((ClassifierWithOptionalValueArgsContext)_localctx).value = valueToken();
				}
				break;
			}
			setState(746);
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
			setState(748);
			argsOpening();
			setState(749);
			((ClassifierWithValueListArgsContext)_localctx).classifier = valueToken();
			setState(750);
			match(ARGS_DELIMITER);
			setState(751);
			((ClassifierWithValueListArgsContext)_localctx).values = variadicValueTokens();
			setState(752);
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
			setState(754);
			argsOpening();
			setState(755);
			((ClassifierWithOptionalValueListArgsContext)_localctx).classifier = valueToken();
			setState(758);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,38,_ctx) ) {
			case 1:
				{
				setState(756);
				match(ARGS_DELIMITER);
				setState(757);
				((ClassifierWithOptionalValueListArgsContext)_localctx).values = variadicValueTokens();
				}
				break;
			}
			setState(760);
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
			setState(762);
			argsOpening();
			setState(763);
			((ClassifierWithBetweenValuesArgsContext)_localctx).classifier = valueToken();
			setState(764);
			match(ARGS_DELIMITER);
			setState(765);
			((ClassifierWithBetweenValuesArgsContext)_localctx).valueFrom = valueToken();
			setState(766);
			match(ARGS_DELIMITER);
			setState(767);
			((ClassifierWithBetweenValuesArgsContext)_localctx).valueTo = valueToken();
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
			setState(770);
			argsOpening();
			setState(771);
			((ValueArgsContext)_localctx).value = valueToken();
			setState(772);
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
			setState(774);
			argsOpening();
			setState(775);
			((ValueListArgsContext)_localctx).values = variadicValueTokens();
			setState(776);
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
			setState(778);
			argsOpening();
			setState(779);
			((BetweenValuesArgsContext)_localctx).valueFrom = valueToken();
			setState(780);
			match(ARGS_DELIMITER);
			setState(781);
			((BetweenValuesArgsContext)_localctx).valueTo = valueToken();
			setState(782);
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
			setState(784);
			argsOpening();
			setState(785);
			((ClassifierListArgsContext)_localctx).classifiers = variadicValueTokens();
			setState(786);
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
			setState(788);
			argsOpening();
			setState(789);
			((ClassifierWithFilterConstraintArgsContext)_localctx).classifier = valueToken();
			setState(790);
			match(ARGS_DELIMITER);
			setState(791);
			((ClassifierWithFilterConstraintArgsContext)_localctx).filter = filterConstraint();
			setState(792);
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
			setState(794);
			argsOpening();
			setState(795);
			((ClassifierWithTwoFilterConstraintArgsContext)_localctx).classifier = valueToken();
			setState(796);
			match(ARGS_DELIMITER);
			setState(797);
			((ClassifierWithTwoFilterConstraintArgsContext)_localctx).filter1 = filterConstraint();
			setState(800);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,39,_ctx) ) {
			case 1:
				{
				setState(798);
				match(ARGS_DELIMITER);
				setState(799);
				((ClassifierWithTwoFilterConstraintArgsContext)_localctx).filter2 = filterConstraint();
				}
				break;
			}
			setState(802);
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
			setState(804);
			argsOpening();
			setState(805);
			((FacetGroupRelationArgsContext)_localctx).classifier = valueToken();
			setState(808);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,40,_ctx) ) {
			case 1:
				{
				setState(806);
				match(ARGS_DELIMITER);
				setState(807);
				((FacetGroupRelationArgsContext)_localctx).facetGroupRelationLevel = valueToken();
				}
				break;
			}
			setState(812);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,41,_ctx) ) {
			case 1:
				{
				setState(810);
				match(ARGS_DELIMITER);
				setState(811);
				((FacetGroupRelationArgsContext)_localctx).filter = filterConstraint();
				}
				break;
			}
			setState(814);
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
			setState(816);
			argsOpening();
			setState(817);
			((FacetCalculationRulesArgsContext)_localctx).facetsWithSameGroup = valueToken();
			setState(818);
			match(ARGS_DELIMITER);
			setState(819);
			((FacetCalculationRulesArgsContext)_localctx).facetsWithDifferentGroups = valueToken();
			setState(820);
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
			setState(822);
			argsOpening();
			setState(823);
			((ClassifierWithOrderConstraintListArgsContext)_localctx).classifier = valueToken();
			setState(826);
			_errHandler.sync(this);
			_alt = 1;
			do {
				switch (_alt) {
				case 1:
					{
					{
					setState(824);
					match(ARGS_DELIMITER);
					setState(825);
					((ClassifierWithOrderConstraintListArgsContext)_localctx).orderConstraint = orderConstraint();
					((ClassifierWithOrderConstraintListArgsContext)_localctx).constrains.add(((ClassifierWithOrderConstraintListArgsContext)_localctx).orderConstraint);
					}
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				setState(828);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,42,_ctx);
			} while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER );
			setState(830);
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
			setState(832);
			argsOpening();
			setState(833);
			((HierarchyWithinConstraintArgsContext)_localctx).classifier = valueToken();
			setState(834);
			match(ARGS_DELIMITER);
			setState(835);
			((HierarchyWithinConstraintArgsContext)_localctx).ofParent = filterConstraint();
			setState(840);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,43,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(836);
					match(ARGS_DELIMITER);
					setState(837);
					((HierarchyWithinConstraintArgsContext)_localctx).filterConstraint = filterConstraint();
					((HierarchyWithinConstraintArgsContext)_localctx).constrains.add(((HierarchyWithinConstraintArgsContext)_localctx).filterConstraint);
					}
					}
				}
				setState(842);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,43,_ctx);
			}
			setState(843);
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
			setState(845);
			argsOpening();
			setState(846);
			((HierarchyWithinSelfConstraintArgsContext)_localctx).ofParent = filterConstraint();
			setState(851);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,44,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(847);
					match(ARGS_DELIMITER);
					setState(848);
					((HierarchyWithinSelfConstraintArgsContext)_localctx).filterConstraint = filterConstraint();
					((HierarchyWithinSelfConstraintArgsContext)_localctx).constrains.add(((HierarchyWithinSelfConstraintArgsContext)_localctx).filterConstraint);
					}
					}
				}
				setState(853);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,44,_ctx);
			}
			setState(854);
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
			setState(856);
			argsOpening();
			setState(866);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,46,_ctx) ) {
			case 1:
				{
				setState(857);
				((HierarchyWithinRootConstraintArgsContext)_localctx).classifier = valueToken();
				}
				break;
			case 2:
				{
				{
				setState(858);
				((HierarchyWithinRootConstraintArgsContext)_localctx).classifier = valueToken();
				setState(863);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,45,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(859);
						match(ARGS_DELIMITER);
						setState(860);
						((HierarchyWithinRootConstraintArgsContext)_localctx).filterConstraint = filterConstraint();
						((HierarchyWithinRootConstraintArgsContext)_localctx).constrains.add(((HierarchyWithinRootConstraintArgsContext)_localctx).filterConstraint);
						}
						}
					}
					setState(865);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,45,_ctx);
				}
				}
				}
				break;
			}
			setState(868);
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
			setState(870);
			argsOpening();
			setState(871);
			((HierarchyWithinRootSelfConstraintArgsContext)_localctx).filterConstraint = filterConstraint();
			((HierarchyWithinRootSelfConstraintArgsContext)_localctx).constrains.add(((HierarchyWithinRootSelfConstraintArgsContext)_localctx).filterConstraint);
			setState(876);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,47,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(872);
					match(ARGS_DELIMITER);
					setState(873);
					((HierarchyWithinRootSelfConstraintArgsContext)_localctx).filterConstraint = filterConstraint();
					((HierarchyWithinRootSelfConstraintArgsContext)_localctx).constrains.add(((HierarchyWithinRootSelfConstraintArgsContext)_localctx).filterConstraint);
					}
					}
				}
				setState(878);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,47,_ctx);
			}
			setState(879);
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
			setState(881);
			argsOpening();
			setState(882);
			((AttributeSetExactArgsContext)_localctx).attributeName = valueToken();
			setState(883);
			match(ARGS_DELIMITER);
			setState(884);
			((AttributeSetExactArgsContext)_localctx).attributeValues = variadicValueTokens();
			setState(885);
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
			setState(887);
			argsOpening();
			setState(888);
			((PageConstraintArgsContext)_localctx).pageNumber = valueToken();
			setState(889);
			match(ARGS_DELIMITER);
			setState(890);
			((PageConstraintArgsContext)_localctx).pageSize = valueToken();
			setState(893);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,48,_ctx) ) {
			case 1:
				{
				setState(891);
				match(ARGS_DELIMITER);
				setState(892);
				((PageConstraintArgsContext)_localctx).constrain = requireConstraint();
				}
				break;
			}
			setState(895);
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
			setState(897);
			argsOpening();
			setState(898);
			((StripConstraintArgsContext)_localctx).offset = valueToken();
			setState(899);
			match(ARGS_DELIMITER);
			setState(900);
			((StripConstraintArgsContext)_localctx).limit = valueToken();
			setState(901);
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
			setState(903);
			argsOpening();
			setState(904);
			((PriceContentArgsContext)_localctx).contentMode = valueToken();
			setState(907);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,49,_ctx) ) {
			case 1:
				{
				setState(905);
				match(ARGS_DELIMITER);
				setState(906);
				((PriceContentArgsContext)_localctx).priceLists = variadicValueTokens();
				}
				break;
			}
			setState(909);
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
			setState(911);
			argsOpening();
			setState(915);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,50,_ctx) ) {
			case 1:
				{
				setState(912);
				((SingleRefReferenceContent1ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(913);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(917);
			((SingleRefReferenceContent1ArgsContext)_localctx).classifier = valueToken();
			setState(918);
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
			setState(920);
			argsOpening();
			setState(924);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,51,_ctx) ) {
			case 1:
				{
				setState(921);
				((SingleRefReferenceContent2ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(922);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(926);
			((SingleRefReferenceContent2ArgsContext)_localctx).classifier = valueToken();
			setState(927);
			match(ARGS_DELIMITER);
			setState(928);
			((SingleRefReferenceContent2ArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(929);
			match(ARGS_DELIMITER);
			setState(930);
			((SingleRefReferenceContent2ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(933);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,52,_ctx) ) {
			case 1:
				{
				setState(931);
				match(ARGS_DELIMITER);
				setState(932);
				((SingleRefReferenceContent2ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(935);
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
			setState(937);
			argsOpening();
			setState(941);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,53,_ctx) ) {
			case 1:
				{
				setState(938);
				((SingleRefReferenceContent3ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(939);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(943);
			((SingleRefReferenceContent3ArgsContext)_localctx).classifier = valueToken();
			setState(944);
			match(ARGS_DELIMITER);
			setState(945);
			((SingleRefReferenceContent3ArgsContext)_localctx).filterBy = filterConstraint();
			setState(948);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,54,_ctx) ) {
			case 1:
				{
				setState(946);
				match(ARGS_DELIMITER);
				setState(947);
				((SingleRefReferenceContent3ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(950);
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
			setState(952);
			argsOpening();
			setState(956);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,55,_ctx) ) {
			case 1:
				{
				setState(953);
				((SingleRefReferenceContent4ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(954);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(958);
			((SingleRefReferenceContent4ArgsContext)_localctx).classifier = valueToken();
			setState(959);
			match(ARGS_DELIMITER);
			setState(960);
			((SingleRefReferenceContent4ArgsContext)_localctx).filterBy = filterConstraint();
			setState(961);
			match(ARGS_DELIMITER);
			setState(962);
			((SingleRefReferenceContent4ArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(963);
			match(ARGS_DELIMITER);
			setState(964);
			((SingleRefReferenceContent4ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(967);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,56,_ctx) ) {
			case 1:
				{
				setState(965);
				match(ARGS_DELIMITER);
				setState(966);
				((SingleRefReferenceContent4ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(969);
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
			setState(971);
			argsOpening();
			setState(975);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,57,_ctx) ) {
			case 1:
				{
				setState(972);
				((SingleRefReferenceContent5ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(973);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(977);
			((SingleRefReferenceContent5ArgsContext)_localctx).classifier = valueToken();
			setState(978);
			match(ARGS_DELIMITER);
			setState(979);
			((SingleRefReferenceContent5ArgsContext)_localctx).orderBy = orderConstraint();
			setState(982);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,58,_ctx) ) {
			case 1:
				{
				setState(980);
				match(ARGS_DELIMITER);
				setState(981);
				((SingleRefReferenceContent5ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(984);
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
			setState(986);
			argsOpening();
			setState(990);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,59,_ctx) ) {
			case 1:
				{
				setState(987);
				((SingleRefReferenceContent6ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(988);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(992);
			((SingleRefReferenceContent6ArgsContext)_localctx).classifier = valueToken();
			setState(993);
			match(ARGS_DELIMITER);
			setState(994);
			((SingleRefReferenceContent6ArgsContext)_localctx).orderBy = orderConstraint();
			setState(995);
			match(ARGS_DELIMITER);
			setState(996);
			((SingleRefReferenceContent6ArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(997);
			match(ARGS_DELIMITER);
			setState(998);
			((SingleRefReferenceContent6ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(1001);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,60,_ctx) ) {
			case 1:
				{
				setState(999);
				match(ARGS_DELIMITER);
				setState(1000);
				((SingleRefReferenceContent6ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1003);
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
			setState(1005);
			argsOpening();
			setState(1009);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,61,_ctx) ) {
			case 1:
				{
				setState(1006);
				((SingleRefReferenceContent7ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1007);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1011);
			((SingleRefReferenceContent7ArgsContext)_localctx).classifier = valueToken();
			setState(1012);
			match(ARGS_DELIMITER);
			setState(1013);
			((SingleRefReferenceContent7ArgsContext)_localctx).filterBy = filterConstraint();
			setState(1014);
			match(ARGS_DELIMITER);
			setState(1015);
			((SingleRefReferenceContent7ArgsContext)_localctx).orderBy = orderConstraint();
			setState(1018);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,62,_ctx) ) {
			case 1:
				{
				setState(1016);
				match(ARGS_DELIMITER);
				setState(1017);
				((SingleRefReferenceContent7ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1020);
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
			setState(1022);
			argsOpening();
			setState(1026);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,63,_ctx) ) {
			case 1:
				{
				setState(1023);
				((SingleRefReferenceContent8ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1024);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1028);
			((SingleRefReferenceContent8ArgsContext)_localctx).classifier = valueToken();
			setState(1029);
			match(ARGS_DELIMITER);
			setState(1030);
			((SingleRefReferenceContent8ArgsContext)_localctx).filterBy = filterConstraint();
			setState(1031);
			match(ARGS_DELIMITER);
			setState(1032);
			((SingleRefReferenceContent8ArgsContext)_localctx).orderBy = orderConstraint();
			setState(1033);
			match(ARGS_DELIMITER);
			setState(1034);
			((SingleRefReferenceContent8ArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(1035);
			match(ARGS_DELIMITER);
			setState(1036);
			((SingleRefReferenceContent8ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(1039);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,64,_ctx) ) {
			case 1:
				{
				setState(1037);
				match(ARGS_DELIMITER);
				setState(1038);
				((SingleRefReferenceContent8ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1041);
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
			setState(1043);
			argsOpening();
			setState(1047);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,65,_ctx) ) {
			case 1:
				{
				setState(1044);
				((SingleRefReferenceContentWithAttributes0ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1045);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1049);
			((SingleRefReferenceContentWithAttributes0ArgsContext)_localctx).classifier = valueToken();
			setState(1050);
			match(ARGS_DELIMITER);
			setState(1051);
			((SingleRefReferenceContentWithAttributes0ArgsContext)_localctx).requirement = requireConstraint();
			setState(1052);
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
			setState(1054);
			argsOpening();
			setState(1058);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,66,_ctx) ) {
			case 1:
				{
				setState(1055);
				((SingleRefReferenceContentWithAttributes1ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1056);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1060);
			((SingleRefReferenceContentWithAttributes1ArgsContext)_localctx).classifier = valueToken();
			setState(1061);
			match(ARGS_DELIMITER);
			setState(1062);
			((SingleRefReferenceContentWithAttributes1ArgsContext)_localctx).requirement1 = requireConstraint();
			setState(1063);
			match(ARGS_DELIMITER);
			setState(1064);
			((SingleRefReferenceContentWithAttributes1ArgsContext)_localctx).requirement2 = requireConstraint();
			setState(1065);
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
			setState(1067);
			argsOpening();
			setState(1071);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,67,_ctx) ) {
			case 1:
				{
				setState(1068);
				((SingleRefReferenceContentWithAttributes2ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1069);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1073);
			((SingleRefReferenceContentWithAttributes2ArgsContext)_localctx).classifier = valueToken();
			setState(1074);
			match(ARGS_DELIMITER);
			setState(1075);
			((SingleRefReferenceContentWithAttributes2ArgsContext)_localctx).attributeContent = requireConstraint();
			setState(1076);
			match(ARGS_DELIMITER);
			setState(1077);
			((SingleRefReferenceContentWithAttributes2ArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(1078);
			match(ARGS_DELIMITER);
			setState(1079);
			((SingleRefReferenceContentWithAttributes2ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(1082);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,68,_ctx) ) {
			case 1:
				{
				setState(1080);
				match(ARGS_DELIMITER);
				setState(1081);
				((SingleRefReferenceContentWithAttributes2ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1084);
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
			setState(1086);
			argsOpening();
			setState(1090);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,69,_ctx) ) {
			case 1:
				{
				setState(1087);
				((SingleRefReferenceContentWithAttributes3ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1088);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1092);
			((SingleRefReferenceContentWithAttributes3ArgsContext)_localctx).classifier = valueToken();
			setState(1093);
			match(ARGS_DELIMITER);
			setState(1094);
			((SingleRefReferenceContentWithAttributes3ArgsContext)_localctx).filterBy = filterConstraint();
			setState(1095);
			match(ARGS_DELIMITER);
			setState(1096);
			((SingleRefReferenceContentWithAttributes3ArgsContext)_localctx).requirement1 = requireConstraint();
			setState(1097);
			match(ARGS_DELIMITER);
			setState(1098);
			((SingleRefReferenceContentWithAttributes3ArgsContext)_localctx).requirement2 = requireConstraint();
			setState(1099);
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
			setState(1101);
			argsOpening();
			setState(1105);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,70,_ctx) ) {
			case 1:
				{
				setState(1102);
				((SingleRefReferenceContentWithAttributes4ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1103);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1107);
			((SingleRefReferenceContentWithAttributes4ArgsContext)_localctx).classifier = valueToken();
			setState(1108);
			match(ARGS_DELIMITER);
			setState(1109);
			((SingleRefReferenceContentWithAttributes4ArgsContext)_localctx).filterBy = filterConstraint();
			setState(1110);
			match(ARGS_DELIMITER);
			setState(1111);
			((SingleRefReferenceContentWithAttributes4ArgsContext)_localctx).attributeContent = requireConstraint();
			setState(1112);
			match(ARGS_DELIMITER);
			setState(1113);
			((SingleRefReferenceContentWithAttributes4ArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(1114);
			match(ARGS_DELIMITER);
			setState(1115);
			((SingleRefReferenceContentWithAttributes4ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(1118);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,71,_ctx) ) {
			case 1:
				{
				setState(1116);
				match(ARGS_DELIMITER);
				setState(1117);
				((SingleRefReferenceContentWithAttributes4ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1120);
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
			setState(1122);
			argsOpening();
			setState(1126);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,72,_ctx) ) {
			case 1:
				{
				setState(1123);
				((SingleRefReferenceContentWithAttributes5ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1124);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1128);
			((SingleRefReferenceContentWithAttributes5ArgsContext)_localctx).classifier = valueToken();
			setState(1129);
			match(ARGS_DELIMITER);
			setState(1130);
			((SingleRefReferenceContentWithAttributes5ArgsContext)_localctx).orderBy = orderConstraint();
			setState(1131);
			match(ARGS_DELIMITER);
			setState(1132);
			((SingleRefReferenceContentWithAttributes5ArgsContext)_localctx).requirement1 = requireConstraint();
			setState(1133);
			match(ARGS_DELIMITER);
			setState(1134);
			((SingleRefReferenceContentWithAttributes5ArgsContext)_localctx).requirement2 = requireConstraint();
			setState(1135);
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
			setState(1137);
			argsOpening();
			setState(1141);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,73,_ctx) ) {
			case 1:
				{
				setState(1138);
				((SingleRefReferenceContentWithAttributes6ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1139);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1143);
			((SingleRefReferenceContentWithAttributes6ArgsContext)_localctx).classifier = valueToken();
			setState(1144);
			match(ARGS_DELIMITER);
			setState(1145);
			((SingleRefReferenceContentWithAttributes6ArgsContext)_localctx).orderBy = orderConstraint();
			setState(1146);
			match(ARGS_DELIMITER);
			setState(1147);
			((SingleRefReferenceContentWithAttributes6ArgsContext)_localctx).attributeContent = requireConstraint();
			setState(1148);
			match(ARGS_DELIMITER);
			setState(1149);
			((SingleRefReferenceContentWithAttributes6ArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(1150);
			match(ARGS_DELIMITER);
			setState(1151);
			((SingleRefReferenceContentWithAttributes6ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(1154);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,74,_ctx) ) {
			case 1:
				{
				setState(1152);
				match(ARGS_DELIMITER);
				setState(1153);
				((SingleRefReferenceContentWithAttributes6ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1156);
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
			setState(1158);
			argsOpening();
			setState(1162);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,75,_ctx) ) {
			case 1:
				{
				setState(1159);
				((SingleRefReferenceContentWithAttributes7ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1160);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1164);
			((SingleRefReferenceContentWithAttributes7ArgsContext)_localctx).classifier = valueToken();
			setState(1165);
			match(ARGS_DELIMITER);
			setState(1166);
			((SingleRefReferenceContentWithAttributes7ArgsContext)_localctx).filterBy = filterConstraint();
			setState(1167);
			match(ARGS_DELIMITER);
			setState(1168);
			((SingleRefReferenceContentWithAttributes7ArgsContext)_localctx).orderBy = orderConstraint();
			setState(1169);
			match(ARGS_DELIMITER);
			setState(1170);
			((SingleRefReferenceContentWithAttributes7ArgsContext)_localctx).requirement1 = requireConstraint();
			setState(1171);
			match(ARGS_DELIMITER);
			setState(1172);
			((SingleRefReferenceContentWithAttributes7ArgsContext)_localctx).requirement2 = requireConstraint();
			setState(1173);
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
			setState(1175);
			argsOpening();
			setState(1179);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,76,_ctx) ) {
			case 1:
				{
				setState(1176);
				((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1177);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1181);
			((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).classifier = valueToken();
			setState(1182);
			match(ARGS_DELIMITER);
			setState(1183);
			((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).filterBy = filterConstraint();
			setState(1184);
			match(ARGS_DELIMITER);
			setState(1185);
			((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).orderBy = orderConstraint();
			setState(1186);
			match(ARGS_DELIMITER);
			setState(1187);
			((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).attributeContent = requireConstraint();
			setState(1188);
			match(ARGS_DELIMITER);
			setState(1189);
			((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(1190);
			match(ARGS_DELIMITER);
			setState(1191);
			((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(1194);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,77,_ctx) ) {
			case 1:
				{
				setState(1192);
				match(ARGS_DELIMITER);
				setState(1193);
				((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1196);
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
			setState(1198);
			argsOpening();
			setState(1225);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,81,_ctx) ) {
			case 1:
				{
				{
				setState(1202);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,78,_ctx) ) {
				case 1:
					{
					setState(1199);
					((MultipleRefsReferenceContentArgsContext)_localctx).managedReferencesBehaviour = valueToken();
					setState(1200);
					match(ARGS_DELIMITER);
					}
					break;
				}
				setState(1204);
				((MultipleRefsReferenceContentArgsContext)_localctx).classifiers = variadicValueTokens();
				}
				}
				break;
			case 2:
				{
				{
				setState(1208);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,79,_ctx) ) {
				case 1:
					{
					setState(1205);
					((MultipleRefsReferenceContentArgsContext)_localctx).managedReferencesBehaviour = valueToken();
					setState(1206);
					match(ARGS_DELIMITER);
					}
					break;
				}
				setState(1210);
				((MultipleRefsReferenceContentArgsContext)_localctx).classifiers = variadicValueTokens();
				setState(1211);
				match(ARGS_DELIMITER);
				setState(1212);
				((MultipleRefsReferenceContentArgsContext)_localctx).requirement = requireConstraint();
				}
				}
				break;
			case 3:
				{
				{
				setState(1217);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,80,_ctx) ) {
				case 1:
					{
					setState(1214);
					((MultipleRefsReferenceContentArgsContext)_localctx).managedReferencesBehaviour = valueToken();
					setState(1215);
					match(ARGS_DELIMITER);
					}
					break;
				}
				setState(1219);
				((MultipleRefsReferenceContentArgsContext)_localctx).classifiers = variadicValueTokens();
				setState(1220);
				match(ARGS_DELIMITER);
				setState(1221);
				((MultipleRefsReferenceContentArgsContext)_localctx).entityRequirement = requireConstraint();
				setState(1222);
				match(ARGS_DELIMITER);
				setState(1223);
				((MultipleRefsReferenceContentArgsContext)_localctx).groupEntityRequirement = requireConstraint();
				}
				}
				break;
			}
			setState(1227);
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
			setState(1229);
			argsOpening();
			setState(1246);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,84,_ctx) ) {
			case 1:
				{
				{
				setState(1230);
				((AllRefsReferenceContentArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				}
				}
				break;
			case 2:
				{
				{
				setState(1234);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (((((_la - 115)) & ~0x3f) == 0 && ((1L << (_la - 115)) & ((1L << (POSITIONAL_PARAMETER - 115)) | (1L << (NAMED_PARAMETER - 115)) | (1L << (STRING - 115)) | (1L << (INT - 115)) | (1L << (FLOAT - 115)) | (1L << (BOOLEAN - 115)) | (1L << (DATE - 115)) | (1L << (TIME - 115)) | (1L << (DATE_TIME - 115)) | (1L << (OFFSET_DATE_TIME - 115)) | (1L << (FLOAT_NUMBER_RANGE - 115)) | (1L << (INT_NUMBER_RANGE - 115)) | (1L << (DATE_TIME_RANGE - 115)) | (1L << (UUID - 115)) | (1L << (ENUM - 115)))) != 0)) {
					{
					setState(1231);
					((AllRefsReferenceContentArgsContext)_localctx).managedReferencesBehaviour = valueToken();
					setState(1232);
					match(ARGS_DELIMITER);
					}
				}

				setState(1236);
				((AllRefsReferenceContentArgsContext)_localctx).requirement = requireConstraint();
				}
				}
				break;
			case 3:
				{
				{
				setState(1240);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (((((_la - 115)) & ~0x3f) == 0 && ((1L << (_la - 115)) & ((1L << (POSITIONAL_PARAMETER - 115)) | (1L << (NAMED_PARAMETER - 115)) | (1L << (STRING - 115)) | (1L << (INT - 115)) | (1L << (FLOAT - 115)) | (1L << (BOOLEAN - 115)) | (1L << (DATE - 115)) | (1L << (TIME - 115)) | (1L << (DATE_TIME - 115)) | (1L << (OFFSET_DATE_TIME - 115)) | (1L << (FLOAT_NUMBER_RANGE - 115)) | (1L << (INT_NUMBER_RANGE - 115)) | (1L << (DATE_TIME_RANGE - 115)) | (1L << (UUID - 115)) | (1L << (ENUM - 115)))) != 0)) {
					{
					setState(1237);
					((AllRefsReferenceContentArgsContext)_localctx).managedReferencesBehaviour = valueToken();
					setState(1238);
					match(ARGS_DELIMITER);
					}
				}

				setState(1242);
				((AllRefsReferenceContentArgsContext)_localctx).entityRequirement = requireConstraint();
				setState(1243);
				match(ARGS_DELIMITER);
				setState(1244);
				((AllRefsReferenceContentArgsContext)_localctx).groupEntityRequirement = requireConstraint();
				}
				}
				break;
			}
			setState(1248);
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
			setState(1250);
			argsOpening();
			setState(1258);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,86,_ctx) ) {
			case 1:
				{
				{
				setState(1251);
				((AllRefsWithAttributesReferenceContent1ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				}
				}
				break;
			case 2:
				{
				setState(1255);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (((((_la - 115)) & ~0x3f) == 0 && ((1L << (_la - 115)) & ((1L << (POSITIONAL_PARAMETER - 115)) | (1L << (NAMED_PARAMETER - 115)) | (1L << (STRING - 115)) | (1L << (INT - 115)) | (1L << (FLOAT - 115)) | (1L << (BOOLEAN - 115)) | (1L << (DATE - 115)) | (1L << (TIME - 115)) | (1L << (DATE_TIME - 115)) | (1L << (OFFSET_DATE_TIME - 115)) | (1L << (FLOAT_NUMBER_RANGE - 115)) | (1L << (INT_NUMBER_RANGE - 115)) | (1L << (DATE_TIME_RANGE - 115)) | (1L << (UUID - 115)) | (1L << (ENUM - 115)))) != 0)) {
					{
					setState(1252);
					((AllRefsWithAttributesReferenceContent1ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
					setState(1253);
					match(ARGS_DELIMITER);
					}
				}

				setState(1257);
				((AllRefsWithAttributesReferenceContent1ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1260);
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
			setState(1262);
			argsOpening();
			setState(1266);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (((((_la - 115)) & ~0x3f) == 0 && ((1L << (_la - 115)) & ((1L << (POSITIONAL_PARAMETER - 115)) | (1L << (NAMED_PARAMETER - 115)) | (1L << (STRING - 115)) | (1L << (INT - 115)) | (1L << (FLOAT - 115)) | (1L << (BOOLEAN - 115)) | (1L << (DATE - 115)) | (1L << (TIME - 115)) | (1L << (DATE_TIME - 115)) | (1L << (OFFSET_DATE_TIME - 115)) | (1L << (FLOAT_NUMBER_RANGE - 115)) | (1L << (INT_NUMBER_RANGE - 115)) | (1L << (DATE_TIME_RANGE - 115)) | (1L << (UUID - 115)) | (1L << (ENUM - 115)))) != 0)) {
				{
				setState(1263);
				((AllRefsWithAttributesReferenceContent2ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1264);
				match(ARGS_DELIMITER);
				}
			}

			setState(1268);
			((AllRefsWithAttributesReferenceContent2ArgsContext)_localctx).requirement1 = requireConstraint();
			setState(1269);
			match(ARGS_DELIMITER);
			setState(1270);
			((AllRefsWithAttributesReferenceContent2ArgsContext)_localctx).requirement2 = requireConstraint();
			setState(1271);
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
			setState(1273);
			argsOpening();
			setState(1277);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (((((_la - 115)) & ~0x3f) == 0 && ((1L << (_la - 115)) & ((1L << (POSITIONAL_PARAMETER - 115)) | (1L << (NAMED_PARAMETER - 115)) | (1L << (STRING - 115)) | (1L << (INT - 115)) | (1L << (FLOAT - 115)) | (1L << (BOOLEAN - 115)) | (1L << (DATE - 115)) | (1L << (TIME - 115)) | (1L << (DATE_TIME - 115)) | (1L << (OFFSET_DATE_TIME - 115)) | (1L << (FLOAT_NUMBER_RANGE - 115)) | (1L << (INT_NUMBER_RANGE - 115)) | (1L << (DATE_TIME_RANGE - 115)) | (1L << (UUID - 115)) | (1L << (ENUM - 115)))) != 0)) {
				{
				setState(1274);
				((AllRefsWithAttributesReferenceContent3ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1275);
				match(ARGS_DELIMITER);
				}
			}

			setState(1279);
			((AllRefsWithAttributesReferenceContent3ArgsContext)_localctx).attributeContent = requireConstraint();
			setState(1280);
			match(ARGS_DELIMITER);
			setState(1281);
			((AllRefsWithAttributesReferenceContent3ArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(1282);
			match(ARGS_DELIMITER);
			setState(1283);
			((AllRefsWithAttributesReferenceContent3ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(1286);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,89,_ctx) ) {
			case 1:
				{
				setState(1284);
				match(ARGS_DELIMITER);
				setState(1285);
				((AllRefsWithAttributesReferenceContent3ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1288);
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
			setState(1290);
			argsOpening();
			setState(1291);
			((SingleRequireHierarchyContentArgsContext)_localctx).requirement = requireConstraint();
			setState(1292);
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
			setState(1294);
			argsOpening();
			setState(1295);
			((AllRequiresHierarchyContentArgsContext)_localctx).stopAt = requireConstraint();
			setState(1296);
			match(ARGS_DELIMITER);
			setState(1297);
			((AllRequiresHierarchyContentArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(1298);
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
			setState(1300);
			argsOpening();
			setState(1301);
			((FacetSummary1ArgsContext)_localctx).depth = valueToken();
			setState(1302);
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
			setState(1304);
			argsOpening();
			setState(1305);
			((FacetSummary2ArgsContext)_localctx).depth = valueToken();
			setState(1306);
			match(ARGS_DELIMITER);
			setState(1307);
			((FacetSummary2ArgsContext)_localctx).filter = facetSummaryFilterArgs();
			setState(1310);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,90,_ctx) ) {
			case 1:
				{
				setState(1308);
				match(ARGS_DELIMITER);
				setState(1309);
				((FacetSummary2ArgsContext)_localctx).order = facetSummaryOrderArgs();
				}
				break;
			}
			setState(1314);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,91,_ctx) ) {
			case 1:
				{
				setState(1312);
				match(ARGS_DELIMITER);
				setState(1313);
				((FacetSummary2ArgsContext)_localctx).requirements = facetSummaryRequirementsArgs();
				}
				break;
			}
			setState(1316);
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
			setState(1318);
			argsOpening();
			setState(1319);
			((FacetSummary3ArgsContext)_localctx).depth = valueToken();
			setState(1320);
			match(ARGS_DELIMITER);
			setState(1321);
			((FacetSummary3ArgsContext)_localctx).order = facetSummaryOrderArgs();
			setState(1324);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,92,_ctx) ) {
			case 1:
				{
				setState(1322);
				match(ARGS_DELIMITER);
				setState(1323);
				((FacetSummary3ArgsContext)_localctx).requirements = facetSummaryRequirementsArgs();
				}
				break;
			}
			setState(1326);
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
			setState(1328);
			argsOpening();
			setState(1329);
			((FacetSummary4ArgsContext)_localctx).depth = valueToken();
			setState(1330);
			match(ARGS_DELIMITER);
			setState(1331);
			((FacetSummary4ArgsContext)_localctx).requirements = facetSummaryRequirementsArgs();
			setState(1332);
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
			setState(1334);
			argsOpening();
			setState(1335);
			((FacetSummary5ArgsContext)_localctx).filter = facetSummaryFilterArgs();
			setState(1338);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,93,_ctx) ) {
			case 1:
				{
				setState(1336);
				match(ARGS_DELIMITER);
				setState(1337);
				((FacetSummary5ArgsContext)_localctx).order = facetSummaryOrderArgs();
				}
				break;
			}
			setState(1342);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,94,_ctx) ) {
			case 1:
				{
				setState(1340);
				match(ARGS_DELIMITER);
				setState(1341);
				((FacetSummary5ArgsContext)_localctx).requirements = facetSummaryRequirementsArgs();
				}
				break;
			}
			setState(1344);
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
			setState(1346);
			argsOpening();
			setState(1347);
			((FacetSummary6ArgsContext)_localctx).order = facetSummaryOrderArgs();
			setState(1350);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,95,_ctx) ) {
			case 1:
				{
				setState(1348);
				match(ARGS_DELIMITER);
				setState(1349);
				((FacetSummary6ArgsContext)_localctx).requirements = facetSummaryRequirementsArgs();
				}
				break;
			}
			setState(1352);
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
			setState(1354);
			argsOpening();
			setState(1355);
			((FacetSummary7ArgsContext)_localctx).requirements = facetSummaryRequirementsArgs();
			setState(1356);
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
			setState(1358);
			argsOpening();
			setState(1359);
			((FacetSummaryOfReference2ArgsContext)_localctx).referenceName = valueToken();
			setState(1362);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,96,_ctx) ) {
			case 1:
				{
				setState(1360);
				match(ARGS_DELIMITER);
				setState(1361);
				((FacetSummaryOfReference2ArgsContext)_localctx).depth = valueToken();
				}
				break;
			}
			setState(1366);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,97,_ctx) ) {
			case 1:
				{
				setState(1364);
				match(ARGS_DELIMITER);
				setState(1365);
				((FacetSummaryOfReference2ArgsContext)_localctx).filter = facetSummaryFilterArgs();
				}
				break;
			}
			setState(1370);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,98,_ctx) ) {
			case 1:
				{
				setState(1368);
				match(ARGS_DELIMITER);
				setState(1369);
				((FacetSummaryOfReference2ArgsContext)_localctx).order = facetSummaryOrderArgs();
				}
				break;
			}
			setState(1374);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,99,_ctx) ) {
			case 1:
				{
				setState(1372);
				match(ARGS_DELIMITER);
				setState(1373);
				((FacetSummaryOfReference2ArgsContext)_localctx).requirements = facetSummaryRequirementsArgs();
				}
				break;
			}
			setState(1376);
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
			setState(1383);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,100,_ctx) ) {
			case 1:
				{
				{
				setState(1378);
				((FacetSummaryRequirementsArgsContext)_localctx).requirement = requireConstraint();
				}
				}
				break;
			case 2:
				{
				{
				setState(1379);
				((FacetSummaryRequirementsArgsContext)_localctx).facetEntityRequirement = requireConstraint();
				setState(1380);
				match(ARGS_DELIMITER);
				setState(1381);
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
			setState(1390);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,101,_ctx) ) {
			case 1:
				{
				{
				setState(1385);
				((FacetSummaryFilterArgsContext)_localctx).filterBy = filterConstraint();
				}
				}
				break;
			case 2:
				{
				{
				setState(1386);
				((FacetSummaryFilterArgsContext)_localctx).filterBy = filterConstraint();
				setState(1387);
				match(ARGS_DELIMITER);
				setState(1388);
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
			setState(1397);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,102,_ctx) ) {
			case 1:
				{
				{
				setState(1392);
				((FacetSummaryOrderArgsContext)_localctx).orderBy = orderConstraint();
				}
				}
				break;
			case 2:
				{
				{
				setState(1393);
				((FacetSummaryOrderArgsContext)_localctx).orderBy = orderConstraint();
				setState(1394);
				match(ARGS_DELIMITER);
				setState(1395);
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
			setState(1399);
			argsOpening();
			setState(1400);
			((AttributeHistogramArgsContext)_localctx).requestedBucketCount = valueToken();
			setState(1401);
			match(ARGS_DELIMITER);
			setState(1402);
			((AttributeHistogramArgsContext)_localctx).values = variadicValueTokens();
			setState(1403);
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
			setState(1405);
			argsOpening();
			setState(1406);
			((PriceHistogramArgsContext)_localctx).requestedBucketCount = valueToken();
			setState(1409);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,103,_ctx) ) {
			case 1:
				{
				setState(1407);
				match(ARGS_DELIMITER);
				setState(1408);
				((PriceHistogramArgsContext)_localctx).behaviour = valueToken();
				}
				break;
			}
			setState(1411);
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
			setState(1413);
			argsOpening();
			setState(1414);
			((HierarchyStatisticsArgsContext)_localctx).settings = variadicValueTokens();
			setState(1415);
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
			setState(1417);
			argsOpening();
			setState(1418);
			((HierarchyRequireConstraintArgsContext)_localctx).outputName = valueToken();
			setState(1423);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,104,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(1419);
					match(ARGS_DELIMITER);
					setState(1420);
					((HierarchyRequireConstraintArgsContext)_localctx).requireConstraint = requireConstraint();
					((HierarchyRequireConstraintArgsContext)_localctx).requirements.add(((HierarchyRequireConstraintArgsContext)_localctx).requireConstraint);
					}
					}
				}
				setState(1425);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,104,_ctx);
			}
			setState(1426);
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
			setState(1428);
			argsOpening();
			setState(1429);
			((HierarchyFromNodeArgsContext)_localctx).outputName = valueToken();
			setState(1430);
			match(ARGS_DELIMITER);
			setState(1431);
			((HierarchyFromNodeArgsContext)_localctx).node = requireConstraint();
			setState(1436);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,105,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(1432);
					match(ARGS_DELIMITER);
					setState(1433);
					((HierarchyFromNodeArgsContext)_localctx).requireConstraint = requireConstraint();
					((HierarchyFromNodeArgsContext)_localctx).requirements.add(((HierarchyFromNodeArgsContext)_localctx).requireConstraint);
					}
					}
				}
				setState(1438);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,105,_ctx);
			}
			setState(1439);
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
			setState(1441);
			argsOpening();
			setState(1442);
			((FullHierarchyOfSelfArgsContext)_localctx).orderBy = orderConstraint();
			setState(1445);
			_errHandler.sync(this);
			_alt = 1;
			do {
				switch (_alt) {
				case 1:
					{
					{
					setState(1443);
					match(ARGS_DELIMITER);
					setState(1444);
					((FullHierarchyOfSelfArgsContext)_localctx).requireConstraint = requireConstraint();
					((FullHierarchyOfSelfArgsContext)_localctx).requirements.add(((FullHierarchyOfSelfArgsContext)_localctx).requireConstraint);
					}
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				setState(1447);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,106,_ctx);
			} while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER );
			setState(1449);
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
			setState(1451);
			argsOpening();
			setState(1452);
			((BasicHierarchyOfReferenceArgsContext)_localctx).referenceName = valueToken();
			setState(1455);
			_errHandler.sync(this);
			_alt = 1;
			do {
				switch (_alt) {
				case 1:
					{
					{
					setState(1453);
					match(ARGS_DELIMITER);
					setState(1454);
					((BasicHierarchyOfReferenceArgsContext)_localctx).requireConstraint = requireConstraint();
					((BasicHierarchyOfReferenceArgsContext)_localctx).requirements.add(((BasicHierarchyOfReferenceArgsContext)_localctx).requireConstraint);
					}
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				setState(1457);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,107,_ctx);
			} while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER );
			setState(1459);
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
			setState(1461);
			argsOpening();
			setState(1462);
			((BasicHierarchyOfReferenceWithBehaviourArgsContext)_localctx).referenceName = valueToken();
			setState(1463);
			match(ARGS_DELIMITER);
			setState(1464);
			((BasicHierarchyOfReferenceWithBehaviourArgsContext)_localctx).emptyHierarchicalEntityBehaviour = valueToken();
			setState(1467);
			_errHandler.sync(this);
			_alt = 1;
			do {
				switch (_alt) {
				case 1:
					{
					{
					setState(1465);
					match(ARGS_DELIMITER);
					setState(1466);
					((BasicHierarchyOfReferenceWithBehaviourArgsContext)_localctx).requireConstraint = requireConstraint();
					((BasicHierarchyOfReferenceWithBehaviourArgsContext)_localctx).requirements.add(((BasicHierarchyOfReferenceWithBehaviourArgsContext)_localctx).requireConstraint);
					}
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				setState(1469);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,108,_ctx);
			} while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER );
			setState(1471);
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
			setState(1473);
			argsOpening();
			setState(1474);
			((FullHierarchyOfReferenceArgsContext)_localctx).referenceName = valueToken();
			setState(1475);
			match(ARGS_DELIMITER);
			setState(1476);
			((FullHierarchyOfReferenceArgsContext)_localctx).orderBy = orderConstraint();
			setState(1479);
			_errHandler.sync(this);
			_alt = 1;
			do {
				switch (_alt) {
				case 1:
					{
					{
					setState(1477);
					match(ARGS_DELIMITER);
					setState(1478);
					((FullHierarchyOfReferenceArgsContext)_localctx).requireConstraint = requireConstraint();
					((FullHierarchyOfReferenceArgsContext)_localctx).requirements.add(((FullHierarchyOfReferenceArgsContext)_localctx).requireConstraint);
					}
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				setState(1481);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,109,_ctx);
			} while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER );
			setState(1483);
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
			setState(1485);
			argsOpening();
			setState(1486);
			((FullHierarchyOfReferenceWithBehaviourArgsContext)_localctx).referenceName = valueToken();
			setState(1487);
			match(ARGS_DELIMITER);
			setState(1488);
			((FullHierarchyOfReferenceWithBehaviourArgsContext)_localctx).emptyHierarchicalEntityBehaviour = valueToken();
			setState(1489);
			match(ARGS_DELIMITER);
			setState(1490);
			((FullHierarchyOfReferenceWithBehaviourArgsContext)_localctx).orderBy = orderConstraint();
			setState(1493);
			_errHandler.sync(this);
			_alt = 1;
			do {
				switch (_alt) {
				case 1:
					{
					{
					setState(1491);
					match(ARGS_DELIMITER);
					setState(1492);
					((FullHierarchyOfReferenceWithBehaviourArgsContext)_localctx).requireConstraint = requireConstraint();
					((FullHierarchyOfReferenceWithBehaviourArgsContext)_localctx).requirements.add(((FullHierarchyOfReferenceWithBehaviourArgsContext)_localctx).requireConstraint);
					}
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				setState(1495);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,110,_ctx);
			} while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER );
			setState(1497);
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
			setState(1499);
			argsOpening();
			setState(1500);
			((SpacingRequireConstraintArgsContext)_localctx).requireConstraint = requireConstraint();
			((SpacingRequireConstraintArgsContext)_localctx).constraints.add(((SpacingRequireConstraintArgsContext)_localctx).requireConstraint);
			setState(1505);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,111,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(1501);
					match(ARGS_DELIMITER);
					setState(1502);
					((SpacingRequireConstraintArgsContext)_localctx).requireConstraint = requireConstraint();
					((SpacingRequireConstraintArgsContext)_localctx).constraints.add(((SpacingRequireConstraintArgsContext)_localctx).requireConstraint);
					}
					}
				}
				setState(1507);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,111,_ctx);
			}
			setState(1508);
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
			setState(1510);
			argsOpening();
			setState(1511);
			((GapRequireConstraintArgsContext)_localctx).size = valueToken();
			setState(1512);
			match(ARGS_DELIMITER);
			setState(1513);
			((GapRequireConstraintArgsContext)_localctx).expression = valueToken();
			setState(1514);
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
			setState(1516);
			argsOpening();
			setState(1520);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,112,_ctx) ) {
			case 1:
				{
				setState(1517);
				((SegmentArgsContext)_localctx).entityHaving = filterConstraint();
				setState(1518);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1522);
			((SegmentArgsContext)_localctx).orderBy = orderConstraint();
			setState(1525);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,113,_ctx) ) {
			case 1:
				{
				setState(1523);
				match(ARGS_DELIMITER);
				setState(1524);
				((SegmentArgsContext)_localctx).limit = orderConstraint();
				}
				break;
			}
			setState(1527);
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
			setState(1529);
			argsOpening();
			setState(1530);
			((InScopeFilterArgsContext)_localctx).scope = valueToken();
			setState(1535);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,114,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(1531);
					match(ARGS_DELIMITER);
					setState(1532);
					((InScopeFilterArgsContext)_localctx).filterConstraint = filterConstraint();
					((InScopeFilterArgsContext)_localctx).filterConstraints.add(((InScopeFilterArgsContext)_localctx).filterConstraint);
					}
					}
				}
				setState(1537);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,114,_ctx);
			}
			setState(1538);
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
			setState(1540);
			argsOpening();
			setState(1541);
			((InScopeOrderArgsContext)_localctx).scope = valueToken();
			setState(1546);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,115,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(1542);
					match(ARGS_DELIMITER);
					setState(1543);
					((InScopeOrderArgsContext)_localctx).orderConstraint = orderConstraint();
					((InScopeOrderArgsContext)_localctx).orderConstraints.add(((InScopeOrderArgsContext)_localctx).orderConstraint);
					}
					}
				}
				setState(1548);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,115,_ctx);
			}
			setState(1549);
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
			setState(1551);
			argsOpening();
			setState(1552);
			((InScopeRequireArgsContext)_localctx).scope = valueToken();
			setState(1557);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,116,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(1553);
					match(ARGS_DELIMITER);
					setState(1554);
					((InScopeRequireArgsContext)_localctx).requireConstraint = requireConstraint();
					((InScopeRequireArgsContext)_localctx).requireConstraints.add(((InScopeRequireArgsContext)_localctx).requireConstraint);
					}
					}
				}
				setState(1559);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,116,_ctx);
			}
			setState(1560);
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
			setState(1562);
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
			setState(1564);
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
			setState(1576);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,118,_ctx) ) {
			case 1:
				_localctx = new PositionalParameterVariadicValueTokensContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(1566);
				positionalParameter();
				}
				break;
			case 2:
				_localctx = new NamedParameterVariadicValueTokensContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(1567);
				namedParameter();
				}
				break;
			case 3:
				_localctx = new ExplicitVariadicValueTokensContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(1568);
				((ExplicitVariadicValueTokensContext)_localctx).valueToken = valueToken();
				((ExplicitVariadicValueTokensContext)_localctx).valueTokens.add(((ExplicitVariadicValueTokensContext)_localctx).valueToken);
				setState(1573);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,117,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(1569);
						match(ARGS_DELIMITER);
						setState(1570);
						((ExplicitVariadicValueTokensContext)_localctx).valueToken = valueToken();
						((ExplicitVariadicValueTokensContext)_localctx).valueTokens.add(((ExplicitVariadicValueTokensContext)_localctx).valueToken);
						}
						}
					}
					setState(1575);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,117,_ctx);
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
			setState(1593);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case POSITIONAL_PARAMETER:
				_localctx = new PositionalParameterValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(1578);
				positionalParameter();
				}
				break;
			case NAMED_PARAMETER:
				_localctx = new NamedParameterValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(1579);
				namedParameter();
				}
				break;
			case STRING:
				_localctx = new StringValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(1580);
				match(STRING);
				}
				break;
			case INT:
				_localctx = new IntValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 4);
				{
				setState(1581);
				match(INT);
				}
				break;
			case FLOAT:
				_localctx = new FloatValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 5);
				{
				setState(1582);
				match(FLOAT);
				}
				break;
			case BOOLEAN:
				_localctx = new BooleanValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 6);
				{
				setState(1583);
				match(BOOLEAN);
				}
				break;
			case DATE:
				_localctx = new DateValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 7);
				{
				setState(1584);
				match(DATE);
				}
				break;
			case TIME:
				_localctx = new TimeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 8);
				{
				setState(1585);
				match(TIME);
				}
				break;
			case DATE_TIME:
				_localctx = new DateTimeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 9);
				{
				setState(1586);
				match(DATE_TIME);
				}
				break;
			case OFFSET_DATE_TIME:
				_localctx = new OffsetDateTimeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 10);
				{
				setState(1587);
				match(OFFSET_DATE_TIME);
				}
				break;
			case FLOAT_NUMBER_RANGE:
				_localctx = new FloatNumberRangeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 11);
				{
				setState(1588);
				match(FLOAT_NUMBER_RANGE);
				}
				break;
			case INT_NUMBER_RANGE:
				_localctx = new IntNumberRangeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 12);
				{
				setState(1589);
				match(INT_NUMBER_RANGE);
				}
				break;
			case DATE_TIME_RANGE:
				_localctx = new DateTimeRangeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 13);
				{
				setState(1590);
				match(DATE_TIME_RANGE);
				}
				break;
			case UUID:
				_localctx = new UuidValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 14);
				{
				setState(1591);
				match(UUID);
				}
				break;
			case ENUM:
				_localctx = new EnumValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 15);
				{
				setState(1592);
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
		"\3\u608b\ua72a\u8133\ub9ed\u417c\u3be7\u7786\u5964\3\u0089\u063e\4\2\t"+
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
		"\r\3\r\3\r\3\r\3\r\3\r\5\r\u0210\n\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3"+
		"\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r"+
		"\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\5\r\u023b"+
		"\n\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3"+
		"\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r\3\r"+
		"\5\r\u025f\n\r\3\16\3\16\3\16\7\16\u0264\n\16\f\16\16\16\u0267\13\16\3"+
		"\17\3\17\3\17\7\17\u026c\n\17\f\17\16\17\u026f\13\17\3\20\3\20\3\20\7"+
		"\20\u0274\n\20\f\20\16\20\u0277\13\20\3\21\3\21\3\21\7\21\u027c\n\21\f"+
		"\21\16\21\u027f\13\21\3\22\3\22\3\23\5\23\u0284\n\23\3\23\3\23\3\24\3"+
		"\24\3\24\3\24\7\24\u028c\n\24\f\24\16\24\u028f\13\24\3\24\3\24\3\25\3"+
		"\25\3\25\3\26\3\26\3\26\3\26\7\26\u029a\n\26\f\26\16\26\u029d\13\26\3"+
		"\26\3\26\3\27\3\27\3\27\3\27\7\27\u02a5\n\27\f\27\16\27\u02a8\13\27\3"+
		"\27\3\27\3\30\3\30\3\30\3\30\3\31\3\31\3\31\3\31\3\31\5\31\u02b5\n\31"+
		"\3\31\3\31\3\31\7\31\u02ba\n\31\f\31\16\31\u02bd\13\31\5\31\u02bf\n\31"+
		"\3\31\3\31\3\32\3\32\3\32\3\32\7\32\u02c7\n\32\f\32\16\32\u02ca\13\32"+
		"\3\32\3\32\3\33\3\33\3\33\3\33\3\34\3\34\3\34\3\34\7\34\u02d6\n\34\f\34"+
		"\16\34\u02d9\13\34\3\34\3\34\3\35\3\35\3\35\3\35\3\36\3\36\3\36\3\36\3"+
		"\36\3\36\3\37\3\37\3\37\3\37\5\37\u02eb\n\37\3\37\3\37\3 \3 \3 \3 \3 "+
		"\3 \3!\3!\3!\3!\5!\u02f9\n!\3!\3!\3\"\3\"\3\"\3\"\3\"\3\"\3\"\3\"\3#\3"+
		"#\3#\3#\3$\3$\3$\3$\3%\3%\3%\3%\3%\3%\3&\3&\3&\3&\3\'\3\'\3\'\3\'\3\'"+
		"\3\'\3(\3(\3(\3(\3(\3(\5(\u0323\n(\3(\3(\3)\3)\3)\3)\5)\u032b\n)\3)\3"+
		")\5)\u032f\n)\3)\3)\3*\3*\3*\3*\3*\3*\3+\3+\3+\3+\6+\u033d\n+\r+\16+\u033e"+
		"\3+\3+\3,\3,\3,\3,\3,\3,\7,\u0349\n,\f,\16,\u034c\13,\3,\3,\3-\3-\3-\3"+
		"-\7-\u0354\n-\f-\16-\u0357\13-\3-\3-\3.\3.\3.\3.\3.\7.\u0360\n.\f.\16"+
		".\u0363\13.\5.\u0365\n.\3.\3.\3/\3/\3/\3/\7/\u036d\n/\f/\16/\u0370\13"+
		"/\3/\3/\3\60\3\60\3\60\3\60\3\60\3\60\3\61\3\61\3\61\3\61\3\61\3\61\5"+
		"\61\u0380\n\61\3\61\3\61\3\62\3\62\3\62\3\62\3\62\3\62\3\63\3\63\3\63"+
		"\3\63\5\63\u038e\n\63\3\63\3\63\3\64\3\64\3\64\3\64\5\64\u0396\n\64\3"+
		"\64\3\64\3\64\3\65\3\65\3\65\3\65\5\65\u039f\n\65\3\65\3\65\3\65\3\65"+
		"\3\65\3\65\3\65\5\65\u03a8\n\65\3\65\3\65\3\66\3\66\3\66\3\66\5\66\u03b0"+
		"\n\66\3\66\3\66\3\66\3\66\3\66\5\66\u03b7\n\66\3\66\3\66\3\67\3\67\3\67"+
		"\3\67\5\67\u03bf\n\67\3\67\3\67\3\67\3\67\3\67\3\67\3\67\3\67\3\67\5\67"+
		"\u03ca\n\67\3\67\3\67\38\38\38\38\58\u03d2\n8\38\38\38\38\38\58\u03d9"+
		"\n8\38\38\39\39\39\39\59\u03e1\n9\39\39\39\39\39\39\39\39\39\59\u03ec"+
		"\n9\39\39\3:\3:\3:\3:\5:\u03f4\n:\3:\3:\3:\3:\3:\3:\3:\5:\u03fd\n:\3:"+
		"\3:\3;\3;\3;\3;\5;\u0405\n;\3;\3;\3;\3;\3;\3;\3;\3;\3;\3;\3;\5;\u0412"+
		"\n;\3;\3;\3<\3<\3<\3<\5<\u041a\n<\3<\3<\3<\3<\3<\3=\3=\3=\3=\5=\u0425"+
		"\n=\3=\3=\3=\3=\3=\3=\3=\3>\3>\3>\3>\5>\u0432\n>\3>\3>\3>\3>\3>\3>\3>"+
		"\3>\3>\5>\u043d\n>\3>\3>\3?\3?\3?\3?\5?\u0445\n?\3?\3?\3?\3?\3?\3?\3?"+
		"\3?\3?\3@\3@\3@\3@\5@\u0454\n@\3@\3@\3@\3@\3@\3@\3@\3@\3@\3@\3@\5@\u0461"+
		"\n@\3@\3@\3A\3A\3A\3A\5A\u0469\nA\3A\3A\3A\3A\3A\3A\3A\3A\3A\3B\3B\3B"+
		"\3B\5B\u0478\nB\3B\3B\3B\3B\3B\3B\3B\3B\3B\3B\3B\5B\u0485\nB\3B\3B\3C"+
		"\3C\3C\3C\5C\u048d\nC\3C\3C\3C\3C\3C\3C\3C\3C\3C\3C\3C\3D\3D\3D\3D\5D"+
		"\u049e\nD\3D\3D\3D\3D\3D\3D\3D\3D\3D\3D\3D\3D\3D\5D\u04ad\nD\3D\3D\3E"+
		"\3E\3E\3E\5E\u04b5\nE\3E\3E\3E\3E\5E\u04bb\nE\3E\3E\3E\3E\3E\3E\3E\5E"+
		"\u04c4\nE\3E\3E\3E\3E\3E\3E\5E\u04cc\nE\3E\3E\3F\3F\3F\3F\3F\5F\u04d5"+
		"\nF\3F\3F\3F\3F\5F\u04db\nF\3F\3F\3F\3F\5F\u04e1\nF\3F\3F\3G\3G\3G\3G"+
		"\3G\5G\u04ea\nG\3G\5G\u04ed\nG\3G\3G\3H\3H\3H\3H\5H\u04f5\nH\3H\3H\3H"+
		"\3H\3H\3I\3I\3I\3I\5I\u0500\nI\3I\3I\3I\3I\3I\3I\3I\5I\u0509\nI\3I\3I"+
		"\3J\3J\3J\3J\3K\3K\3K\3K\3K\3K\3L\3L\3L\3L\3M\3M\3M\3M\3M\3M\5M\u0521"+
		"\nM\3M\3M\5M\u0525\nM\3M\3M\3N\3N\3N\3N\3N\3N\5N\u052f\nN\3N\3N\3O\3O"+
		"\3O\3O\3O\3O\3P\3P\3P\3P\5P\u053d\nP\3P\3P\5P\u0541\nP\3P\3P\3Q\3Q\3Q"+
		"\3Q\5Q\u0549\nQ\3Q\3Q\3R\3R\3R\3R\3S\3S\3S\3S\5S\u0555\nS\3S\3S\5S\u0559"+
		"\nS\3S\3S\5S\u055d\nS\3S\3S\5S\u0561\nS\3S\3S\3T\3T\3T\3T\3T\5T\u056a"+
		"\nT\3U\3U\3U\3U\3U\5U\u0571\nU\3V\3V\3V\3V\3V\5V\u0578\nV\3W\3W\3W\3W"+
		"\3W\3W\3X\3X\3X\3X\5X\u0584\nX\3X\3X\3Y\3Y\3Y\3Y\3Z\3Z\3Z\3Z\7Z\u0590"+
		"\nZ\fZ\16Z\u0593\13Z\3Z\3Z\3[\3[\3[\3[\3[\3[\7[\u059d\n[\f[\16[\u05a0"+
		"\13[\3[\3[\3\\\3\\\3\\\3\\\6\\\u05a8\n\\\r\\\16\\\u05a9\3\\\3\\\3]\3]"+
		"\3]\3]\6]\u05b2\n]\r]\16]\u05b3\3]\3]\3^\3^\3^\3^\3^\3^\6^\u05be\n^\r"+
		"^\16^\u05bf\3^\3^\3_\3_\3_\3_\3_\3_\6_\u05ca\n_\r_\16_\u05cb\3_\3_\3`"+
		"\3`\3`\3`\3`\3`\3`\3`\6`\u05d8\n`\r`\16`\u05d9\3`\3`\3a\3a\3a\3a\7a\u05e2"+
		"\na\fa\16a\u05e5\13a\3a\3a\3b\3b\3b\3b\3b\3b\3c\3c\3c\3c\5c\u05f3\nc\3"+
		"c\3c\3c\5c\u05f8\nc\3c\3c\3d\3d\3d\3d\7d\u0600\nd\fd\16d\u0603\13d\3d"+
		"\3d\3e\3e\3e\3e\7e\u060b\ne\fe\16e\u060e\13e\3e\3e\3f\3f\3f\3f\7f\u0616"+
		"\nf\ff\16f\u0619\13f\3f\3f\3g\3g\3h\3h\3i\3i\3i\3i\3i\7i\u0626\ni\fi\16"+
		"i\u0629\13i\5i\u062b\ni\3j\3j\3j\3j\3j\3j\3j\3j\3j\3j\3j\3j\3j\3j\3j\5"+
		"j\u063c\nj\3j\2\2k\2\4\6\b\n\f\16\20\22\24\26\30\32\34\36 \"$&(*,.\60"+
		"\62\64\668:<>@BDFHJLNPRTVXZ\\^`bdfhjlnprtvxz|~\u0080\u0082\u0084\u0086"+
		"\u0088\u008a\u008c\u008e\u0090\u0092\u0094\u0096\u0098\u009a\u009c\u009e"+
		"\u00a0\u00a2\u00a4\u00a6\u00a8\u00aa\u00ac\u00ae\u00b0\u00b2\u00b4\u00b6"+
		"\u00b8\u00ba\u00bc\u00be\u00c0\u00c2\u00c4\u00c6\u00c8\u00ca\u00cc\u00ce"+
		"\u00d0\u00d2\2\2\2\u06ee\2\u00d4\3\2\2\2\4\u00d7\3\2\2\2\6\u00da\3\2\2"+
		"\2\b\u00dd\3\2\2\2\n\u00e0\3\2\2\2\f\u00e3\3\2\2\2\16\u00e6\3\2\2\2\20"+
		"\u00ed\3\2\2\2\22\u00f5\3\2\2\2\24\u0168\3\2\2\2\26\u01a0\3\2\2\2\30\u025e"+
		"\3\2\2\2\32\u0260\3\2\2\2\34\u0268\3\2\2\2\36\u0270\3\2\2\2 \u0278\3\2"+
		"\2\2\"\u0280\3\2\2\2$\u0283\3\2\2\2&\u0287\3\2\2\2(\u0292\3\2\2\2*\u0295"+
		"\3\2\2\2,\u02a0\3\2\2\2.\u02ab\3\2\2\2\60\u02af\3\2\2\2\62\u02c2\3\2\2"+
		"\2\64\u02cd\3\2\2\2\66\u02d1\3\2\2\28\u02dc\3\2\2\2:\u02e0\3\2\2\2<\u02e6"+
		"\3\2\2\2>\u02ee\3\2\2\2@\u02f4\3\2\2\2B\u02fc\3\2\2\2D\u0304\3\2\2\2F"+
		"\u0308\3\2\2\2H\u030c\3\2\2\2J\u0312\3\2\2\2L\u0316\3\2\2\2N\u031c\3\2"+
		"\2\2P\u0326\3\2\2\2R\u0332\3\2\2\2T\u0338\3\2\2\2V\u0342\3\2\2\2X\u034f"+
		"\3\2\2\2Z\u035a\3\2\2\2\\\u0368\3\2\2\2^\u0373\3\2\2\2`\u0379\3\2\2\2"+
		"b\u0383\3\2\2\2d\u0389\3\2\2\2f\u0391\3\2\2\2h\u039a\3\2\2\2j\u03ab\3"+
		"\2\2\2l\u03ba\3\2\2\2n\u03cd\3\2\2\2p\u03dc\3\2\2\2r\u03ef\3\2\2\2t\u0400"+
		"\3\2\2\2v\u0415\3\2\2\2x\u0420\3\2\2\2z\u042d\3\2\2\2|\u0440\3\2\2\2~"+
		"\u044f\3\2\2\2\u0080\u0464\3\2\2\2\u0082\u0473\3\2\2\2\u0084\u0488\3\2"+
		"\2\2\u0086\u0499\3\2\2\2\u0088\u04b0\3\2\2\2\u008a\u04cf\3\2\2\2\u008c"+
		"\u04e4\3\2\2\2\u008e\u04f0\3\2\2\2\u0090\u04fb\3\2\2\2\u0092\u050c\3\2"+
		"\2\2\u0094\u0510\3\2\2\2\u0096\u0516\3\2\2\2\u0098\u051a\3\2\2\2\u009a"+
		"\u0528\3\2\2\2\u009c\u0532\3\2\2\2\u009e\u0538\3\2\2\2\u00a0\u0544\3\2"+
		"\2\2\u00a2\u054c\3\2\2\2\u00a4\u0550\3\2\2\2\u00a6\u0569\3\2\2\2\u00a8"+
		"\u0570\3\2\2\2\u00aa\u0577\3\2\2\2\u00ac\u0579\3\2\2\2\u00ae\u057f\3\2"+
		"\2\2\u00b0\u0587\3\2\2\2\u00b2\u058b\3\2\2\2\u00b4\u0596\3\2\2\2\u00b6"+
		"\u05a3\3\2\2\2\u00b8\u05ad\3\2\2\2\u00ba\u05b7\3\2\2\2\u00bc\u05c3\3\2"+
		"\2\2\u00be\u05cf\3\2\2\2\u00c0\u05dd\3\2\2\2\u00c2\u05e8\3\2\2\2\u00c4"+
		"\u05ee\3\2\2\2\u00c6\u05fb\3\2\2\2\u00c8\u0606\3\2\2\2\u00ca\u0611\3\2"+
		"\2\2\u00cc\u061c\3\2\2\2\u00ce\u061e\3\2\2\2\u00d0\u062a\3\2\2\2\u00d2"+
		"\u063b\3\2\2\2\u00d4\u00d5\5\16\b\2\u00d5\u00d6\7\2\2\3\u00d6\3\3\2\2"+
		"\2\u00d7\u00d8\5\32\16\2\u00d8\u00d9\7\2\2\3\u00d9\5\3\2\2\2\u00da\u00db"+
		"\5\34\17\2\u00db\u00dc\7\2\2\3\u00dc\7\3\2\2\2\u00dd\u00de\5\36\20\2\u00de"+
		"\u00df\7\2\2\3\u00df\t\3\2\2\2\u00e0\u00e1\5 \21\2\u00e1\u00e2\7\2\2\3"+
		"\u00e2\13\3\2\2\2\u00e3\u00e4\5\u00d2j\2\u00e4\u00e5\7\2\2\3\u00e5\r\3"+
		"\2\2\2\u00e6\u00e7\7\3\2\2\u00e7\u00e8\5&\24\2\u00e8\17\3\2\2\2\u00e9"+
		"\u00ee\5\22\n\2\u00ea\u00ee\5\24\13\2\u00eb\u00ee\5\26\f\2\u00ec\u00ee"+
		"\5\30\r\2\u00ed\u00e9\3\2\2\2\u00ed\u00ea\3\2\2\2\u00ed\u00eb\3\2\2\2"+
		"\u00ed\u00ec\3\2\2\2\u00ee\21\3\2\2\2\u00ef\u00f0\7\4\2\2\u00f0\u00f6"+
		"\5*\26\2\u00f1\u00f2\7\5\2\2\u00f2\u00f6\58\35\2\u00f3\u00f4\7\6\2\2\u00f4"+
		"\u00f6\5:\36\2\u00f5\u00ef\3\2\2\2\u00f5\u00f1\3\2\2\2\u00f5\u00f3\3\2"+
		"\2\2\u00f6\23\3\2\2\2\u00f7\u00f8\7\7\2\2\u00f8\u0169\5,\27\2\u00f9\u00fa"+
		"\7\b\2\2\u00fa\u0169\5,\27\2\u00fb\u00fe\7\t\2\2\u00fc\u00ff\5(\25\2\u00fd"+
		"\u00ff\5,\27\2\u00fe\u00fc\3\2\2\2\u00fe\u00fd\3\2\2\2\u00ff\u0169\3\2"+
		"\2\2\u0100\u0103\7\n\2\2\u0101\u0104\5(\25\2\u0102\u0104\5,\27\2\u0103"+
		"\u0101\3\2\2\2\u0103\u0102\3\2\2\2\u0104\u0169\3\2\2\2\u0105\u0106\7\13"+
		"\2\2\u0106\u0169\5.\30\2\u0107\u010a\7\f\2\2\u0108\u010b\5(\25\2\u0109"+
		"\u010b\5,\27\2\u010a\u0108\3\2\2\2\u010a\u0109\3\2\2\2\u010b\u0169\3\2"+
		"\2\2\u010c\u010d\7\r\2\2\u010d\u0169\5:\36\2\u010e\u010f\7\16\2\2\u010f"+
		"\u0169\5:\36\2\u0110\u0111\7\17\2\2\u0111\u0169\5:\36\2\u0112\u0113\7"+
		"\20\2\2\u0113\u0169\5:\36\2\u0114\u0115\7\21\2\2\u0115\u0169\5:\36\2\u0116"+
		"\u0117\7\22\2\2\u0117\u0169\5B\"\2\u0118\u0119\7\23\2\2\u0119\u0169\5"+
		"@!\2\u011a\u011b\7\24\2\2\u011b\u0169\5:\36\2\u011c\u011d\7\25\2\2\u011d"+
		"\u0169\5:\36\2\u011e\u011f\7\26\2\2\u011f\u0169\5:\36\2\u0120\u0121\7"+
		"\27\2\2\u0121\u0169\58\35\2\u0122\u0123\7\30\2\2\u0123\u0169\58\35\2\u0124"+
		"\u0125\7\31\2\2\u0125\u0169\5:\36\2\u0126\u0127\7\32\2\2\u0127\u0169\5"+
		"8\35\2\u0128\u0129\7\33\2\2\u0129\u0169\58\35\2\u012a\u012b\7\34\2\2\u012b"+
		"\u0169\5:\36\2\u012c\u012d\7\35\2\2\u012d\u0169\58\35\2\u012e\u0131\7"+
		"\36\2\2\u012f\u0132\5(\25\2\u0130\u0132\5F$\2\u0131\u012f\3\2\2\2\u0131"+
		"\u0130\3\2\2\2\u0132\u0169\3\2\2\2\u0133\u0134\7\37\2\2\u0134\u0169\5"+
		"D#\2\u0135\u0136\7 \2\2\u0136\u0169\5D#\2\u0137\u013a\7!\2\2\u0138\u013b"+
		"\5(\25\2\u0139\u013b\5J&\2\u013a\u0138\3\2\2\2\u013a\u0139\3\2\2\2\u013b"+
		"\u0169\3\2\2\2\u013c\u013d\7\"\2\2\u013d\u0169\5(\25\2\u013e\u013f\7#"+
		"\2\2\u013f\u0169\5D#\2\u0140\u0141\7$\2\2\u0141\u0169\5H%\2\u0142\u0143"+
		"\7%\2\2\u0143\u0169\5N(\2\u0144\u0145\7&\2\2\u0145\u0169\5(\25\2\u0146"+
		"\u0147\7\'\2\2\u0147\u0169\5.\30\2\u0148\u0149\7(\2\2\u0149\u0169\5.\30"+
		"\2\u014a\u014d\7)\2\2\u014b\u014e\58\35\2\u014c\u014e\5L\'\2\u014d\u014b"+
		"\3\2\2\2\u014d\u014c\3\2\2\2\u014e\u0169\3\2\2\2\u014f\u0150\7*\2\2\u0150"+
		"\u0169\5V,\2\u0151\u0152\7+\2\2\u0152\u0169\5X-\2\u0153\u0154\7,\2\2\u0154"+
		"\u0169\5Z.\2\u0155\u0158\7-\2\2\u0156\u0159\5(\25\2\u0157\u0159\5\\/\2"+
		"\u0158\u0156\3\2\2\2\u0158\u0157\3\2\2\2\u0159\u0169\3\2\2\2\u015a\u015b"+
		"\7.\2\2\u015b\u0169\5(\25\2\u015c\u015d\7/\2\2\u015d\u0169\5,\27\2\u015e"+
		"\u015f\7\60\2\2\u015f\u0169\5(\25\2\u0160\u0161\7\61\2\2\u0161\u0169\5"+
		",\27\2\u0162\u0163\7\62\2\2\u0163\u0169\5.\30\2\u0164\u0165\7\63\2\2\u0165"+
		"\u0169\5\u00c6d\2\u0166\u0167\7\64\2\2\u0167\u0169\5F$\2\u0168\u00f7\3"+
		"\2\2\2\u0168\u00f9\3\2\2\2\u0168\u00fb\3\2\2\2\u0168\u0100\3\2\2\2\u0168"+
		"\u0105\3\2\2\2\u0168\u0107\3\2\2\2\u0168\u010c\3\2\2\2\u0168\u010e\3\2"+
		"\2\2\u0168\u0110\3\2\2\2\u0168\u0112\3\2\2\2\u0168\u0114\3\2\2\2\u0168"+
		"\u0116\3\2\2\2\u0168\u0118\3\2\2\2\u0168\u011a\3\2\2\2\u0168\u011c\3\2"+
		"\2\2\u0168\u011e\3\2\2\2\u0168\u0120\3\2\2\2\u0168\u0122\3\2\2\2\u0168"+
		"\u0124\3\2\2\2\u0168\u0126\3\2\2\2\u0168\u0128\3\2\2\2\u0168\u012a\3\2"+
		"\2\2\u0168\u012c\3\2\2\2\u0168\u012e\3\2\2\2\u0168\u0133\3\2\2\2\u0168"+
		"\u0135\3\2\2\2\u0168\u0137\3\2\2\2\u0168\u013c\3\2\2\2\u0168\u013e\3\2"+
		"\2\2\u0168\u0140\3\2\2\2\u0168\u0142\3\2\2\2\u0168\u0144\3\2\2\2\u0168"+
		"\u0146\3\2\2\2\u0168\u0148\3\2\2\2\u0168\u014a\3\2\2\2\u0168\u014f\3\2"+
		"\2\2\u0168\u0151\3\2\2\2\u0168\u0153\3\2\2\2\u0168\u0155\3\2\2\2\u0168"+
		"\u015a\3\2\2\2\u0168\u015c\3\2\2\2\u0168\u015e\3\2\2\2\u0168\u0160\3\2"+
		"\2\2\u0168\u0162\3\2\2\2\u0168\u0164\3\2\2\2\u0168\u0166\3\2\2\2\u0169"+
		"\25\3\2\2\2\u016a\u016d\7\65\2\2\u016b\u016e\5(\25\2\u016c\u016e\5\62"+
		"\32\2\u016d\u016b\3\2\2\2\u016d\u016c\3\2\2\2\u016e\u01a1\3\2\2\2\u016f"+
		"\u0172\7\66\2\2\u0170\u0173\5(\25\2\u0171\u0173\5\62\32\2\u0172\u0170"+
		"\3\2\2\2\u0172\u0171\3\2\2\2\u0173\u01a1\3\2\2\2\u0174\u0175\7\67\2\2"+
		"\u0175\u01a1\5<\37\2\u0176\u0177\78\2\2\u0177\u01a1\5^\60\2\u0178\u0179"+
		"\79\2\2\u0179\u01a1\58\35\2\u017a\u017d\7:\2\2\u017b\u017e\5(\25\2\u017c"+
		"\u017e\5D#\2\u017d\u017b\3\2\2\2\u017d\u017c\3\2\2\2\u017e\u01a1\3\2\2"+
		"\2\u017f\u0180\7;\2\2\u0180\u01a1\5F$\2\u0181\u0182\7<\2\2\u0182\u01a1"+
		"\5(\25\2\u0183\u0184\7=\2\2\u0184\u01a1\5D#\2\u0185\u0186\7>\2\2\u0186"+
		"\u01a1\5T+\2\u0187\u0188\7?\2\2\u0188\u01a1\5\60\31\2\u0189\u018a\7@\2"+
		"\2\u018a\u01a1\5\62\32\2\u018b\u018e\7A\2\2\u018c\u018f\5(\25\2\u018d"+
		"\u018f\5D#\2\u018e\u018c\3\2\2\2\u018e\u018d\3\2\2\2\u018f\u01a1\3\2\2"+
		"\2\u0190\u0191\7B\2\2\u0191\u01a1\5F$\2\u0192\u0193\7C\2\2\u0193\u01a1"+
		"\5(\25\2\u0194\u0195\7D\2\2\u0195\u01a1\5\62\32\2\u0196\u0197\7E\2\2\u0197"+
		"\u01a1\5\62\32\2\u0198\u0199\7F\2\2\u0199\u01a1\5\62\32\2\u019a\u019b"+
		"\7G\2\2\u019b\u01a1\5\u00c4c\2\u019c\u019d\7H\2\2\u019d\u01a1\5D#\2\u019e"+
		"\u019f\7\63\2\2\u019f\u01a1\5\u00c8e\2\u01a0\u016a\3\2\2\2\u01a0\u016f"+
		"\3\2\2\2\u01a0\u0174\3\2\2\2\u01a0\u0176\3\2\2\2\u01a0\u0178\3\2\2\2\u01a0"+
		"\u017a\3\2\2\2\u01a0\u017f\3\2\2\2\u01a0\u0181\3\2\2\2\u01a0\u0183\3\2"+
		"\2\2\u01a0\u0185\3\2\2\2\u01a0\u0187\3\2\2\2\u01a0\u0189\3\2\2\2\u01a0"+
		"\u018b\3\2\2\2\u01a0\u0190\3\2\2\2\u01a0\u0192\3\2\2\2\u01a0\u0194\3\2"+
		"\2\2\u01a0\u0196\3\2\2\2\u01a0\u0198\3\2\2\2\u01a0\u019a\3\2\2\2\u01a0"+
		"\u019c\3\2\2\2\u01a0\u019e\3\2\2\2\u01a1\27\3\2\2\2\u01a2\u01a5\7I\2\2"+
		"\u01a3\u01a6\5(\25\2\u01a4\u01a6\5\66\34\2\u01a5\u01a3\3\2\2\2\u01a5\u01a4"+
		"\3\2\2\2\u01a6\u025f\3\2\2\2\u01a7\u01a8\7J\2\2\u01a8\u025f\5`\61\2\u01a9"+
		"\u01aa\7K\2\2\u01aa\u025f\5b\62\2\u01ab\u01ae\7L\2\2\u01ac\u01af\5(\25"+
		"\2\u01ad\u01af\5\66\34\2\u01ae\u01ac\3\2\2\2\u01ae\u01ad\3\2\2\2\u01af"+
		"\u025f\3\2\2\2\u01b0\u01b3\7M\2\2\u01b1\u01b4\5(\25\2\u01b2\u01b4\5\66"+
		"\34\2\u01b3\u01b1\3\2\2\2\u01b3\u01b2\3\2\2\2\u01b4\u025f\3\2\2\2\u01b5"+
		"\u01b6\7N\2\2\u01b6\u025f\5J&\2\u01b7\u01b8\7O\2\2\u01b8\u025f\5(\25\2"+
		"\u01b9\u01ba\7P\2\2\u01ba\u025f\5d\63\2\u01bb\u01bc\7Q\2\2\u01bc\u025f"+
		"\5(\25\2\u01bd\u01c0\7R\2\2\u01be\u01c1\5(\25\2\u01bf\u01c1\5F$\2\u01c0"+
		"\u01be\3\2\2\2\u01c0\u01bf\3\2\2\2\u01c1\u025f\3\2\2\2\u01c2\u01c3\7S"+
		"\2\2\u01c3\u025f\5J&\2\u01c4\u01c5\7T\2\2\u01c5\u025f\5(\25\2\u01c6\u01c9"+
		"\7U\2\2\u01c7\u01ca\5(\25\2\u01c8\u01ca\5\u008aF\2\u01c9\u01c7\3\2\2\2"+
		"\u01c9\u01c8\3\2\2\2\u01ca\u025f\3\2\2\2\u01cb\u01cc\7V\2\2\u01cc\u025f"+
		"\5\u0088E\2\u01cd\u01ce\7V\2\2\u01ce\u025f\5f\64\2\u01cf\u01d0\7V\2\2"+
		"\u01d0\u025f\5h\65\2\u01d1\u01d2\7V\2\2\u01d2\u025f\5j\66\2\u01d3\u01d4"+
		"\7V\2\2\u01d4\u025f\5l\67\2\u01d5\u01d6\7V\2\2\u01d6\u025f\5n8\2\u01d7"+
		"\u01d8\7V\2\2\u01d8\u025f\5p9\2\u01d9\u01da\7V\2\2\u01da\u025f\5r:\2\u01db"+
		"\u01dc\7V\2\2\u01dc\u025f\5t;\2\u01dd\u01e0\7W\2\2\u01de\u01e1\5(\25\2"+
		"\u01df\u01e1\5\u008cG\2\u01e0\u01de\3\2\2\2\u01e0\u01df\3\2\2\2\u01e1"+
		"\u025f\3\2\2\2\u01e2\u01e3\7W\2\2\u01e3\u025f\5\u008eH\2\u01e4\u01e5\7"+
		"W\2\2\u01e5\u025f\5\u0090I\2\u01e6\u01e7\7X\2\2\u01e7\u025f\5f\64\2\u01e8"+
		"\u01e9\7X\2\2\u01e9\u025f\5v<\2\u01ea\u01eb\7X\2\2\u01eb\u025f\5x=\2\u01ec"+
		"\u01ed\7X\2\2\u01ed\u025f\5z>\2\u01ee\u01ef\7X\2\2\u01ef\u025f\5j\66\2"+
		"\u01f0\u01f1\7X\2\2\u01f1\u025f\5|?\2\u01f2\u01f3\7X\2\2\u01f3\u025f\5"+
		"~@\2\u01f4\u01f5\7X\2\2\u01f5\u025f\5n8\2\u01f6\u01f7\7X\2\2\u01f7\u025f"+
		"\5\u0080A\2\u01f8\u01f9\7X\2\2\u01f9\u025f\5\u0082B\2\u01fa\u01fb\7X\2"+
		"\2\u01fb\u025f\5r:\2\u01fc\u01fd\7X\2\2\u01fd\u025f\5\u0084C\2\u01fe\u01ff"+
		"\7X\2\2\u01ff\u025f\5\u0086D\2\u0200\u0201\7Y\2\2\u0201\u025f\5(\25\2"+
		"\u0202\u0203\7Y\2\2\u0203\u025f\5\u0092J\2\u0204\u0205\7Y\2\2\u0205\u025f"+
		"\5\u0094K\2\u0206\u0207\7Z\2\2\u0207\u025f\5D#\2\u0208\u0209\7[\2\2\u0209"+
		"\u025f\5(\25\2\u020a\u020b\7\\\2\2\u020b\u025f\5F$\2\u020c\u020f\7]\2"+
		"\2\u020d\u0210\5(\25\2\u020e\u0210\5\u0096L\2\u020f\u020d\3\2\2\2\u020f"+
		"\u020e\3\2\2\2\u0210\u025f\3\2\2\2\u0211\u0212\7]\2\2\u0212\u025f\5\u0098"+
		"M\2\u0213\u0214\7]\2\2\u0214\u025f\5\u009aN\2\u0215\u0216\7]\2\2\u0216"+
		"\u025f\5\u009cO\2\u0217\u0218\7]\2\2\u0218\u025f\5\u009eP\2\u0219\u021a"+
		"\7]\2\2\u021a\u025f\5\u00a0Q\2\u021b\u021c\7]\2\2\u021c\u025f\5\u00a2"+
		"R\2\u021d\u021e\7^\2\2\u021e\u025f\58\35\2\u021f\u0220\7^\2\2\u0220\u025f"+
		"\5\u00a4S\2\u0221\u0222\7_\2\2\u0222\u025f\5P)\2\u0223\u0224\7`\2\2\u0224"+
		"\u025f\5P)\2\u0225\u0226\7a\2\2\u0226\u025f\5P)\2\u0227\u0228\7b\2\2\u0228"+
		"\u025f\5P)\2\u0229\u022a\7c\2\2\u022a\u025f\5R*\2\u022b\u022c\7d\2\2\u022c"+
		"\u025f\5\u00acW\2\u022d\u022e\7e\2\2\u022e\u025f\5\u00aeX\2\u022f\u0230"+
		"\7f\2\2\u0230\u025f\5D#\2\u0231\u0232\7g\2\2\u0232\u025f\5D#\2\u0233\u0234"+
		"\7h\2\2\u0234\u025f\5.\30\2\u0235\u0236\7i\2\2\u0236\u025f\5\64\33\2\u0237"+
		"\u023a\7j\2\2\u0238\u023b\5(\25\2\u0239\u023b\5\u00b0Y\2\u023a\u0238\3"+
		"\2\2\2\u023a\u0239\3\2\2\2\u023b\u025f\3\2\2\2\u023c\u023d\7k\2\2\u023d"+
		"\u025f\5\u00b2Z\2\u023e\u023f\7l\2\2\u023f\u025f\5\u00b4[\2\u0240\u0241"+
		"\7m\2\2\u0241\u025f\5\u00b2Z\2\u0242\u0243\7n\2\2\u0243\u025f\5(\25\2"+
		"\u0244\u0245\7n\2\2\u0245\u025f\5\66\34\2\u0246\u0247\7n\2\2\u0247\u025f"+
		"\5\u00b2Z\2\u0248\u0249\7o\2\2\u0249\u025f\5\u00c0a\2\u024a\u024b\7p\2"+
		"\2\u024b\u025f\5\u00c2b\2\u024c\u024d\7q\2\2\u024d\u025f\5\u00b2Z\2\u024e"+
		"\u024f\7r\2\2\u024f\u025f\5\66\34\2\u0250\u0251\7r\2\2\u0251\u025f\5\u00b6"+
		"\\\2\u0252\u0253\7s\2\2\u0253\u025f\5\u00b8]\2\u0254\u0255\7s\2\2\u0255"+
		"\u025f\5\u00ba^\2\u0256\u0257\7s\2\2\u0257\u025f\5\u00bc_\2\u0258\u0259"+
		"\7s\2\2\u0259\u025f\5\u00be`\2\u025a\u025b\7t\2\2\u025b\u025f\5(\25\2"+
		"\u025c\u025d\7\63\2\2\u025d\u025f\5\u00caf\2\u025e\u01a2\3\2\2\2\u025e"+
		"\u01a7\3\2\2\2\u025e\u01a9\3\2\2\2\u025e\u01ab\3\2\2\2\u025e\u01b0\3\2"+
		"\2\2\u025e\u01b5\3\2\2\2\u025e\u01b7\3\2\2\2\u025e\u01b9\3\2\2\2\u025e"+
		"\u01bb\3\2\2\2\u025e\u01bd\3\2\2\2\u025e\u01c2\3\2\2\2\u025e\u01c4\3\2"+
		"\2\2\u025e\u01c6\3\2\2\2\u025e\u01cb\3\2\2\2\u025e\u01cd\3\2\2\2\u025e"+
		"\u01cf\3\2\2\2\u025e\u01d1\3\2\2\2\u025e\u01d3\3\2\2\2\u025e\u01d5\3\2"+
		"\2\2\u025e\u01d7\3\2\2\2\u025e\u01d9\3\2\2\2\u025e\u01db\3\2\2\2\u025e"+
		"\u01dd\3\2\2\2\u025e\u01e2\3\2\2\2\u025e\u01e4\3\2\2\2\u025e\u01e6\3\2"+
		"\2\2\u025e\u01e8\3\2\2\2\u025e\u01ea\3\2\2\2\u025e\u01ec\3\2\2\2\u025e"+
		"\u01ee\3\2\2\2\u025e\u01f0\3\2\2\2\u025e\u01f2\3\2\2\2\u025e\u01f4\3\2"+
		"\2\2\u025e\u01f6\3\2\2\2\u025e\u01f8\3\2\2\2\u025e\u01fa\3\2\2\2\u025e"+
		"\u01fc\3\2\2\2\u025e\u01fe\3\2\2\2\u025e\u0200\3\2\2\2\u025e\u0202\3\2"+
		"\2\2\u025e\u0204\3\2\2\2\u025e\u0206\3\2\2\2\u025e\u0208\3\2\2\2\u025e"+
		"\u020a\3\2\2\2\u025e\u020c\3\2\2\2\u025e\u0211\3\2\2\2\u025e\u0213\3\2"+
		"\2\2\u025e\u0215\3\2\2\2\u025e\u0217\3\2\2\2\u025e\u0219\3\2\2\2\u025e"+
		"\u021b\3\2\2\2\u025e\u021d\3\2\2\2\u025e\u021f\3\2\2\2\u025e\u0221\3\2"+
		"\2\2\u025e\u0223\3\2\2\2\u025e\u0225\3\2\2\2\u025e\u0227\3\2\2\2\u025e"+
		"\u0229\3\2\2\2\u025e\u022b\3\2\2\2\u025e\u022d\3\2\2\2\u025e\u022f\3\2"+
		"\2\2\u025e\u0231\3\2\2\2\u025e\u0233\3\2\2\2\u025e\u0235\3\2\2\2\u025e"+
		"\u0237\3\2\2\2\u025e\u023c\3\2\2\2\u025e\u023e\3\2\2\2\u025e\u0240\3\2"+
		"\2\2\u025e\u0242\3\2\2\2\u025e\u0244\3\2\2\2\u025e\u0246\3\2\2\2\u025e"+
		"\u0248\3\2\2\2\u025e\u024a\3\2\2\2\u025e\u024c\3\2\2\2\u025e\u024e\3\2"+
		"\2\2\u025e\u0250\3\2\2\2\u025e\u0252\3\2\2\2\u025e\u0254\3\2\2\2\u025e"+
		"\u0256\3\2\2\2\u025e\u0258\3\2\2\2\u025e\u025a\3\2\2\2\u025e\u025c\3\2"+
		"\2\2\u025f\31\3\2\2\2\u0260\u0265\5\22\n\2\u0261\u0262\7\u0086\2\2\u0262"+
		"\u0264\5\22\n\2\u0263\u0261\3\2\2\2\u0264\u0267\3\2\2\2\u0265\u0263\3"+
		"\2\2\2\u0265\u0266\3\2\2\2\u0266\33\3\2\2\2\u0267\u0265\3\2\2\2\u0268"+
		"\u026d\5\24\13\2\u0269\u026a\7\u0086\2\2\u026a\u026c\5\24\13\2\u026b\u0269"+
		"\3\2\2\2\u026c\u026f\3\2\2\2\u026d\u026b\3\2\2\2\u026d\u026e\3\2\2\2\u026e"+
		"\35\3\2\2\2\u026f\u026d\3\2\2\2\u0270\u0275\5\26\f\2\u0271\u0272\7\u0086"+
		"\2\2\u0272\u0274\5\26\f\2\u0273\u0271\3\2\2\2\u0274\u0277\3\2\2\2\u0275"+
		"\u0273\3\2\2\2\u0275\u0276\3\2\2\2\u0276\37\3\2\2\2\u0277\u0275\3\2\2"+
		"\2\u0278\u027d\5\30\r\2\u0279\u027a\7\u0086\2\2\u027a\u027c\5\30\r\2\u027b"+
		"\u0279\3\2\2\2\u027c\u027f\3\2\2\2\u027d\u027b\3\2\2\2\u027d\u027e\3\2"+
		"\2\2\u027e!\3\2\2\2\u027f\u027d\3\2\2\2\u0280\u0281\7\u0084\2\2\u0281"+
		"#\3\2\2\2\u0282\u0284\7\u0086\2\2\u0283\u0282\3\2\2\2\u0283\u0284\3\2"+
		"\2\2\u0284\u0285\3\2\2\2\u0285\u0286\7\u0085\2\2\u0286%\3\2\2\2\u0287"+
		"\u0288\5\"\22\2\u0288\u028d\5\20\t\2\u0289\u028a\7\u0086\2\2\u028a\u028c"+
		"\5\20\t\2\u028b\u0289\3\2\2\2\u028c\u028f\3\2\2\2\u028d\u028b\3\2\2\2"+
		"\u028d\u028e\3\2\2\2\u028e\u0290\3\2\2\2\u028f\u028d\3\2\2\2\u0290\u0291"+
		"\5$\23\2\u0291\'\3\2\2\2\u0292\u0293\5\"\22\2\u0293\u0294\5$\23\2\u0294"+
		")\3\2\2\2\u0295\u0296\5\"\22\2\u0296\u029b\5\22\n\2\u0297\u0298\7\u0086"+
		"\2\2\u0298\u029a\5\22\n\2\u0299\u0297\3\2\2\2\u029a\u029d\3\2\2\2\u029b"+
		"\u0299\3\2\2\2\u029b\u029c\3\2\2\2\u029c\u029e\3\2\2\2\u029d\u029b\3\2"+
		"\2\2\u029e\u029f\5$\23\2\u029f+\3\2\2\2\u02a0\u02a1\5\"\22\2\u02a1\u02a6"+
		"\5\24\13\2\u02a2\u02a3\7\u0086\2\2\u02a3\u02a5\5\24\13\2\u02a4\u02a2\3"+
		"\2\2\2\u02a5\u02a8\3\2\2\2\u02a6\u02a4\3\2\2\2\u02a6\u02a7\3\2\2\2\u02a7"+
		"\u02a9\3\2\2\2\u02a8\u02a6\3\2\2\2\u02a9\u02aa\5$\23\2\u02aa-\3\2\2\2"+
		"\u02ab\u02ac\5\"\22\2\u02ac\u02ad\5\24\13\2\u02ad\u02ae\5$\23\2\u02ae"+
		"/\3\2\2\2\u02af\u02be\5\"\22\2\u02b0\u02bf\5\u00d2j\2\u02b1\u02b2\5\u00d2"+
		"j\2\u02b2\u02b3\7\u0086\2\2\u02b3\u02b5\3\2\2\2\u02b4\u02b1\3\2\2\2\u02b4"+
		"\u02b5\3\2\2\2\u02b5\u02b6\3\2\2\2\u02b6\u02bb\5\26\f\2\u02b7\u02b8\7"+
		"\u0086\2\2\u02b8\u02ba\5\26\f\2\u02b9\u02b7\3\2\2\2\u02ba\u02bd\3\2\2"+
		"\2\u02bb\u02b9\3\2\2\2\u02bb\u02bc\3\2\2\2\u02bc\u02bf\3\2\2\2\u02bd\u02bb"+
		"\3\2\2\2\u02be\u02b0\3\2\2\2\u02be\u02b4\3\2\2\2\u02bf\u02c0\3\2\2\2\u02c0"+
		"\u02c1\5$\23\2\u02c1\61\3\2\2\2\u02c2\u02c3\5\"\22\2\u02c3\u02c8\5\26"+
		"\f\2\u02c4\u02c5\7\u0086\2\2\u02c5\u02c7\5\26\f\2\u02c6\u02c4\3\2\2\2"+
		"\u02c7\u02ca\3\2\2\2\u02c8\u02c6\3\2\2\2\u02c8\u02c9\3\2\2\2\u02c9\u02cb"+
		"\3\2\2\2\u02ca\u02c8\3\2\2\2\u02cb\u02cc\5$\23\2\u02cc\63\3\2\2\2\u02cd"+
		"\u02ce\5\"\22\2\u02ce\u02cf\5\30\r\2\u02cf\u02d0\5$\23\2\u02d0\65\3\2"+
		"\2\2\u02d1\u02d2\5\"\22\2\u02d2\u02d7\5\30\r\2\u02d3\u02d4\7\u0086\2\2"+
		"\u02d4\u02d6\5\30\r\2\u02d5\u02d3\3\2\2\2\u02d6\u02d9\3\2\2\2\u02d7\u02d5"+
		"\3\2\2\2\u02d7\u02d8\3\2\2\2\u02d8\u02da\3\2\2\2\u02d9\u02d7\3\2\2\2\u02da"+
		"\u02db\5$\23\2\u02db\67\3\2\2\2\u02dc\u02dd\5\"\22\2\u02dd\u02de\5\u00d2"+
		"j\2\u02de\u02df\5$\23\2\u02df9\3\2\2\2\u02e0\u02e1\5\"\22\2\u02e1\u02e2"+
		"\5\u00d2j\2\u02e2\u02e3\7\u0086\2\2\u02e3\u02e4\5\u00d2j\2\u02e4\u02e5"+
		"\5$\23\2\u02e5;\3\2\2\2\u02e6\u02e7\5\"\22\2\u02e7\u02ea\5\u00d2j\2\u02e8"+
		"\u02e9\7\u0086\2\2\u02e9\u02eb\5\u00d2j\2\u02ea\u02e8\3\2\2\2\u02ea\u02eb"+
		"\3\2\2\2\u02eb\u02ec\3\2\2\2\u02ec\u02ed\5$\23\2\u02ed=\3\2\2\2\u02ee"+
		"\u02ef\5\"\22\2\u02ef\u02f0\5\u00d2j\2\u02f0\u02f1\7\u0086\2\2\u02f1\u02f2"+
		"\5\u00d0i\2\u02f2\u02f3\5$\23\2\u02f3?\3\2\2\2\u02f4\u02f5\5\"\22\2\u02f5"+
		"\u02f8\5\u00d2j\2\u02f6\u02f7\7\u0086\2\2\u02f7\u02f9\5\u00d0i\2\u02f8"+
		"\u02f6\3\2\2\2\u02f8\u02f9\3\2\2\2\u02f9\u02fa\3\2\2\2\u02fa\u02fb\5$"+
		"\23\2\u02fbA\3\2\2\2\u02fc\u02fd\5\"\22\2\u02fd\u02fe\5\u00d2j\2\u02fe"+
		"\u02ff\7\u0086\2\2\u02ff\u0300\5\u00d2j\2\u0300\u0301\7\u0086\2\2\u0301"+
		"\u0302\5\u00d2j\2\u0302\u0303\5$\23\2\u0303C\3\2\2\2\u0304\u0305\5\"\22"+
		"\2\u0305\u0306\5\u00d2j\2\u0306\u0307\5$\23\2\u0307E\3\2\2\2\u0308\u0309"+
		"\5\"\22\2\u0309\u030a\5\u00d0i\2\u030a\u030b\5$\23\2\u030bG\3\2\2\2\u030c"+
		"\u030d\5\"\22\2\u030d\u030e\5\u00d2j\2\u030e\u030f\7\u0086\2\2\u030f\u0310"+
		"\5\u00d2j\2\u0310\u0311\5$\23\2\u0311I\3\2\2\2\u0312\u0313\5\"\22\2\u0313"+
		"\u0314\5\u00d0i\2\u0314\u0315\5$\23\2\u0315K\3\2\2\2\u0316\u0317\5\"\22"+
		"\2\u0317\u0318\5\u00d2j\2\u0318\u0319\7\u0086\2\2\u0319\u031a\5\24\13"+
		"\2\u031a\u031b\5$\23\2\u031bM\3\2\2\2\u031c\u031d\5\"\22\2\u031d\u031e"+
		"\5\u00d2j\2\u031e\u031f\7\u0086\2\2\u031f\u0322\5\24\13\2\u0320\u0321"+
		"\7\u0086\2\2\u0321\u0323\5\24\13\2\u0322\u0320\3\2\2\2\u0322\u0323\3\2"+
		"\2\2\u0323\u0324\3\2\2\2\u0324\u0325\5$\23\2\u0325O\3\2\2\2\u0326\u0327"+
		"\5\"\22\2\u0327\u032a\5\u00d2j\2\u0328\u0329\7\u0086\2\2\u0329\u032b\5"+
		"\u00d2j\2\u032a\u0328\3\2\2\2\u032a\u032b\3\2\2\2\u032b\u032e\3\2\2\2"+
		"\u032c\u032d\7\u0086\2\2\u032d\u032f\5\24\13\2\u032e\u032c\3\2\2\2\u032e"+
		"\u032f\3\2\2\2\u032f\u0330\3\2\2\2\u0330\u0331\5$\23\2\u0331Q\3\2\2\2"+
		"\u0332\u0333\5\"\22\2\u0333\u0334\5\u00d2j\2\u0334\u0335\7\u0086\2\2\u0335"+
		"\u0336\5\u00d2j\2\u0336\u0337\5$\23\2\u0337S\3\2\2\2\u0338\u0339\5\"\22"+
		"\2\u0339\u033c\5\u00d2j\2\u033a\u033b\7\u0086\2\2\u033b\u033d\5\26\f\2"+
		"\u033c\u033a\3\2\2\2\u033d\u033e\3\2\2\2\u033e\u033c\3\2\2\2\u033e\u033f"+
		"\3\2\2\2\u033f\u0340\3\2\2\2\u0340\u0341\5$\23\2\u0341U\3\2\2\2\u0342"+
		"\u0343\5\"\22\2\u0343\u0344\5\u00d2j\2\u0344\u0345\7\u0086\2\2\u0345\u034a"+
		"\5\24\13\2\u0346\u0347\7\u0086\2\2\u0347\u0349\5\24\13\2\u0348\u0346\3"+
		"\2\2\2\u0349\u034c\3\2\2\2\u034a\u0348\3\2\2\2\u034a\u034b\3\2\2\2\u034b"+
		"\u034d\3\2\2\2\u034c\u034a\3\2\2\2\u034d\u034e\5$\23\2\u034eW\3\2\2\2"+
		"\u034f\u0350\5\"\22\2\u0350\u0355\5\24\13\2\u0351\u0352\7\u0086\2\2\u0352"+
		"\u0354\5\24\13\2\u0353\u0351\3\2\2\2\u0354\u0357\3\2\2\2\u0355\u0353\3"+
		"\2\2\2\u0355\u0356\3\2\2\2\u0356\u0358\3\2\2\2\u0357\u0355\3\2\2\2\u0358"+
		"\u0359\5$\23\2\u0359Y\3\2\2\2\u035a\u0364\5\"\22\2\u035b\u0365\5\u00d2"+
		"j\2\u035c\u0361\5\u00d2j\2\u035d\u035e\7\u0086\2\2\u035e\u0360\5\24\13"+
		"\2\u035f\u035d\3\2\2\2\u0360\u0363\3\2\2\2\u0361\u035f\3\2\2\2\u0361\u0362"+
		"\3\2\2\2\u0362\u0365\3\2\2\2\u0363\u0361\3\2\2\2\u0364\u035b\3\2\2\2\u0364"+
		"\u035c\3\2\2\2\u0365\u0366\3\2\2\2\u0366\u0367\5$\23\2\u0367[\3\2\2\2"+
		"\u0368\u0369\5\"\22\2\u0369\u036e\5\24\13\2\u036a\u036b\7\u0086\2\2\u036b"+
		"\u036d\5\24\13\2\u036c\u036a\3\2\2\2\u036d\u0370\3\2\2\2\u036e\u036c\3"+
		"\2\2\2\u036e\u036f\3\2\2\2\u036f\u0371\3\2\2\2\u0370\u036e\3\2\2\2\u0371"+
		"\u0372\5$\23\2\u0372]\3\2\2\2\u0373\u0374\5\"\22\2\u0374\u0375\5\u00d2"+
		"j\2\u0375\u0376\7\u0086\2\2\u0376\u0377\5\u00d0i\2\u0377\u0378\5$\23\2"+
		"\u0378_\3\2\2\2\u0379\u037a\5\"\22\2\u037a\u037b\5\u00d2j\2\u037b\u037c"+
		"\7\u0086\2\2\u037c\u037f\5\u00d2j\2\u037d\u037e\7\u0086\2\2\u037e\u0380"+
		"\5\30\r\2\u037f\u037d\3\2\2\2\u037f\u0380\3\2\2\2\u0380\u0381\3\2\2\2"+
		"\u0381\u0382\5$\23\2\u0382a\3\2\2\2\u0383\u0384\5\"\22\2\u0384\u0385\5"+
		"\u00d2j\2\u0385\u0386\7\u0086\2\2\u0386\u0387\5\u00d2j\2\u0387\u0388\5"+
		"$\23\2\u0388c\3\2\2\2\u0389\u038a\5\"\22\2\u038a\u038d\5\u00d2j\2\u038b"+
		"\u038c\7\u0086\2\2\u038c\u038e\5\u00d0i\2\u038d\u038b\3\2\2\2\u038d\u038e"+
		"\3\2\2\2\u038e\u038f\3\2\2\2\u038f\u0390\5$\23\2\u0390e\3\2\2\2\u0391"+
		"\u0395\5\"\22\2\u0392\u0393\5\u00d2j\2\u0393\u0394\7\u0086\2\2\u0394\u0396"+
		"\3\2\2\2\u0395\u0392\3\2\2\2\u0395\u0396\3\2\2\2\u0396\u0397\3\2\2\2\u0397"+
		"\u0398\5\u00d2j\2\u0398\u0399\5$\23\2\u0399g\3\2\2\2\u039a\u039e\5\"\22"+
		"\2\u039b\u039c\5\u00d2j\2\u039c\u039d\7\u0086\2\2\u039d\u039f\3\2\2\2"+
		"\u039e\u039b\3\2\2\2\u039e\u039f\3\2\2\2\u039f\u03a0\3\2\2\2\u03a0\u03a1"+
		"\5\u00d2j\2\u03a1\u03a2\7\u0086\2\2\u03a2\u03a3\5\30\r\2\u03a3\u03a4\7"+
		"\u0086\2\2\u03a4\u03a7\5\30\r\2\u03a5\u03a6\7\u0086\2\2\u03a6\u03a8\5"+
		"\30\r\2\u03a7\u03a5\3\2\2\2\u03a7\u03a8\3\2\2\2\u03a8\u03a9\3\2\2\2\u03a9"+
		"\u03aa\5$\23\2\u03aai\3\2\2\2\u03ab\u03af\5\"\22\2\u03ac\u03ad\5\u00d2"+
		"j\2\u03ad\u03ae\7\u0086\2\2\u03ae\u03b0\3\2\2\2\u03af\u03ac\3\2\2\2\u03af"+
		"\u03b0\3\2\2\2\u03b0\u03b1\3\2\2\2\u03b1\u03b2\5\u00d2j\2\u03b2\u03b3"+
		"\7\u0086\2\2\u03b3\u03b6\5\24\13\2\u03b4\u03b5\7\u0086\2\2\u03b5\u03b7"+
		"\5\30\r\2\u03b6\u03b4\3\2\2\2\u03b6\u03b7\3\2\2\2\u03b7\u03b8\3\2\2\2"+
		"\u03b8\u03b9\5$\23\2\u03b9k\3\2\2\2\u03ba\u03be\5\"\22\2\u03bb\u03bc\5"+
		"\u00d2j\2\u03bc\u03bd\7\u0086\2\2\u03bd\u03bf\3\2\2\2\u03be\u03bb\3\2"+
		"\2\2\u03be\u03bf\3\2\2\2\u03bf\u03c0\3\2\2\2\u03c0\u03c1\5\u00d2j\2\u03c1"+
		"\u03c2\7\u0086\2\2\u03c2\u03c3\5\24\13\2\u03c3\u03c4\7\u0086\2\2\u03c4"+
		"\u03c5\5\30\r\2\u03c5\u03c6\7\u0086\2\2\u03c6\u03c9\5\30\r\2\u03c7\u03c8"+
		"\7\u0086\2\2\u03c8\u03ca\5\30\r\2\u03c9\u03c7\3\2\2\2\u03c9\u03ca\3\2"+
		"\2\2\u03ca\u03cb\3\2\2\2\u03cb\u03cc\5$\23\2\u03ccm\3\2\2\2\u03cd\u03d1"+
		"\5\"\22\2\u03ce\u03cf\5\u00d2j\2\u03cf\u03d0\7\u0086\2\2\u03d0\u03d2\3"+
		"\2\2\2\u03d1\u03ce\3\2\2\2\u03d1\u03d2\3\2\2\2\u03d2\u03d3\3\2\2\2\u03d3"+
		"\u03d4\5\u00d2j\2\u03d4\u03d5\7\u0086\2\2\u03d5\u03d8\5\26\f\2\u03d6\u03d7"+
		"\7\u0086\2\2\u03d7\u03d9\5\30\r\2\u03d8\u03d6\3\2\2\2\u03d8\u03d9\3\2"+
		"\2\2\u03d9\u03da\3\2\2\2\u03da\u03db\5$\23\2\u03dbo\3\2\2\2\u03dc\u03e0"+
		"\5\"\22\2\u03dd\u03de\5\u00d2j\2\u03de\u03df\7\u0086\2\2\u03df\u03e1\3"+
		"\2\2\2\u03e0\u03dd\3\2\2\2\u03e0\u03e1\3\2\2\2\u03e1\u03e2\3\2\2\2\u03e2"+
		"\u03e3\5\u00d2j\2\u03e3\u03e4\7\u0086\2\2\u03e4\u03e5\5\26\f\2\u03e5\u03e6"+
		"\7\u0086\2\2\u03e6\u03e7\5\30\r\2\u03e7\u03e8\7\u0086\2\2\u03e8\u03eb"+
		"\5\30\r\2\u03e9\u03ea\7\u0086\2\2\u03ea\u03ec\5\30\r\2\u03eb\u03e9\3\2"+
		"\2\2\u03eb\u03ec\3\2\2\2\u03ec\u03ed\3\2\2\2\u03ed\u03ee\5$\23\2\u03ee"+
		"q\3\2\2\2\u03ef\u03f3\5\"\22\2\u03f0\u03f1\5\u00d2j\2\u03f1\u03f2\7\u0086"+
		"\2\2\u03f2\u03f4\3\2\2\2\u03f3\u03f0\3\2\2\2\u03f3\u03f4\3\2\2\2\u03f4"+
		"\u03f5\3\2\2\2\u03f5\u03f6\5\u00d2j\2\u03f6\u03f7\7\u0086\2\2\u03f7\u03f8"+
		"\5\24\13\2\u03f8\u03f9\7\u0086\2\2\u03f9\u03fc\5\26\f\2\u03fa\u03fb\7"+
		"\u0086\2\2\u03fb\u03fd\5\30\r\2\u03fc\u03fa\3\2\2\2\u03fc\u03fd\3\2\2"+
		"\2\u03fd\u03fe\3\2\2\2\u03fe\u03ff\5$\23\2\u03ffs\3\2\2\2\u0400\u0404"+
		"\5\"\22\2\u0401\u0402\5\u00d2j\2\u0402\u0403\7\u0086\2\2\u0403\u0405\3"+
		"\2\2\2\u0404\u0401\3\2\2\2\u0404\u0405\3\2\2\2\u0405\u0406\3\2\2\2\u0406"+
		"\u0407\5\u00d2j\2\u0407\u0408\7\u0086\2\2\u0408\u0409\5\24\13\2\u0409"+
		"\u040a\7\u0086\2\2\u040a\u040b\5\26\f\2\u040b\u040c\7\u0086\2\2\u040c"+
		"\u040d\5\30\r\2\u040d\u040e\7\u0086\2\2\u040e\u0411\5\30\r\2\u040f\u0410"+
		"\7\u0086\2\2\u0410\u0412\5\30\r\2\u0411\u040f\3\2\2\2\u0411\u0412\3\2"+
		"\2\2\u0412\u0413\3\2\2\2\u0413\u0414\5$\23\2\u0414u\3\2\2\2\u0415\u0419"+
		"\5\"\22\2\u0416\u0417\5\u00d2j\2\u0417\u0418\7\u0086\2\2\u0418\u041a\3"+
		"\2\2\2\u0419\u0416\3\2\2\2\u0419\u041a\3\2\2\2\u041a\u041b\3\2\2\2\u041b"+
		"\u041c\5\u00d2j\2\u041c\u041d\7\u0086\2\2\u041d\u041e\5\30\r\2\u041e\u041f"+
		"\5$\23\2\u041fw\3\2\2\2\u0420\u0424\5\"\22\2\u0421\u0422\5\u00d2j\2\u0422"+
		"\u0423\7\u0086\2\2\u0423\u0425\3\2\2\2\u0424\u0421\3\2\2\2\u0424\u0425"+
		"\3\2\2\2\u0425\u0426\3\2\2\2\u0426\u0427\5\u00d2j\2\u0427\u0428\7\u0086"+
		"\2\2\u0428\u0429\5\30\r\2\u0429\u042a\7\u0086\2\2\u042a\u042b\5\30\r\2"+
		"\u042b\u042c\5$\23\2\u042cy\3\2\2\2\u042d\u0431\5\"\22\2\u042e\u042f\5"+
		"\u00d2j\2\u042f\u0430\7\u0086\2\2\u0430\u0432\3\2\2\2\u0431\u042e\3\2"+
		"\2\2\u0431\u0432\3\2\2\2\u0432\u0433\3\2\2\2\u0433\u0434\5\u00d2j\2\u0434"+
		"\u0435\7\u0086\2\2\u0435\u0436\5\30\r\2\u0436\u0437\7\u0086\2\2\u0437"+
		"\u0438\5\30\r\2\u0438\u0439\7\u0086\2\2\u0439\u043c\5\30\r\2\u043a\u043b"+
		"\7\u0086\2\2\u043b\u043d\5\30\r\2\u043c\u043a\3\2\2\2\u043c\u043d\3\2"+
		"\2\2\u043d\u043e\3\2\2\2\u043e\u043f\5$\23\2\u043f{\3\2\2\2\u0440\u0444"+
		"\5\"\22\2\u0441\u0442\5\u00d2j\2\u0442\u0443\7\u0086\2\2\u0443\u0445\3"+
		"\2\2\2\u0444\u0441\3\2\2\2\u0444\u0445\3\2\2\2\u0445\u0446\3\2\2\2\u0446"+
		"\u0447\5\u00d2j\2\u0447\u0448\7\u0086\2\2\u0448\u0449\5\24\13\2\u0449"+
		"\u044a\7\u0086\2\2\u044a\u044b\5\30\r\2\u044b\u044c\7\u0086\2\2\u044c"+
		"\u044d\5\30\r\2\u044d\u044e\5$\23\2\u044e}\3\2\2\2\u044f\u0453\5\"\22"+
		"\2\u0450\u0451\5\u00d2j\2\u0451\u0452\7\u0086\2\2\u0452\u0454\3\2\2\2"+
		"\u0453\u0450\3\2\2\2\u0453\u0454\3\2\2\2\u0454\u0455\3\2\2\2\u0455\u0456"+
		"\5\u00d2j\2\u0456\u0457\7\u0086\2\2\u0457\u0458\5\24\13\2\u0458\u0459"+
		"\7\u0086\2\2\u0459\u045a\5\30\r\2\u045a\u045b\7\u0086\2\2\u045b\u045c"+
		"\5\30\r\2\u045c\u045d\7\u0086\2\2\u045d\u0460\5\30\r\2\u045e\u045f\7\u0086"+
		"\2\2\u045f\u0461\5\30\r\2\u0460\u045e\3\2\2\2\u0460\u0461\3\2\2\2\u0461"+
		"\u0462\3\2\2\2\u0462\u0463\5$\23\2\u0463\177\3\2\2\2\u0464\u0468\5\"\22"+
		"\2\u0465\u0466\5\u00d2j\2\u0466\u0467\7\u0086\2\2\u0467\u0469\3\2\2\2"+
		"\u0468\u0465\3\2\2\2\u0468\u0469\3\2\2\2\u0469\u046a\3\2\2\2\u046a\u046b"+
		"\5\u00d2j\2\u046b\u046c\7\u0086\2\2\u046c\u046d\5\26\f\2\u046d\u046e\7"+
		"\u0086\2\2\u046e\u046f\5\30\r\2\u046f\u0470\7\u0086\2\2\u0470\u0471\5"+
		"\30\r\2\u0471\u0472\5$\23\2\u0472\u0081\3\2\2\2\u0473\u0477\5\"\22\2\u0474"+
		"\u0475\5\u00d2j\2\u0475\u0476\7\u0086\2\2\u0476\u0478\3\2\2\2\u0477\u0474"+
		"\3\2\2\2\u0477\u0478\3\2\2\2\u0478\u0479\3\2\2\2\u0479\u047a\5\u00d2j"+
		"\2\u047a\u047b\7\u0086\2\2\u047b\u047c\5\26\f\2\u047c\u047d\7\u0086\2"+
		"\2\u047d\u047e\5\30\r\2\u047e\u047f\7\u0086\2\2\u047f\u0480\5\30\r\2\u0480"+
		"\u0481\7\u0086\2\2\u0481\u0484\5\30\r\2\u0482\u0483\7\u0086\2\2\u0483"+
		"\u0485\5\30\r\2\u0484\u0482\3\2\2\2\u0484\u0485\3\2\2\2\u0485\u0486\3"+
		"\2\2\2\u0486\u0487\5$\23\2\u0487\u0083\3\2\2\2\u0488\u048c\5\"\22\2\u0489"+
		"\u048a\5\u00d2j\2\u048a\u048b\7\u0086\2\2\u048b\u048d\3\2\2\2\u048c\u0489"+
		"\3\2\2\2\u048c\u048d\3\2\2\2\u048d\u048e\3\2\2\2\u048e\u048f\5\u00d2j"+
		"\2\u048f\u0490\7\u0086\2\2\u0490\u0491\5\24\13\2\u0491\u0492\7\u0086\2"+
		"\2\u0492\u0493\5\26\f\2\u0493\u0494\7\u0086\2\2\u0494\u0495\5\30\r\2\u0495"+
		"\u0496\7\u0086\2\2\u0496\u0497\5\30\r\2\u0497\u0498\5$\23\2\u0498\u0085"+
		"\3\2\2\2\u0499\u049d\5\"\22\2\u049a\u049b\5\u00d2j\2\u049b\u049c\7\u0086"+
		"\2\2\u049c\u049e\3\2\2\2\u049d\u049a\3\2\2\2\u049d\u049e\3\2\2\2\u049e"+
		"\u049f\3\2\2\2\u049f\u04a0\5\u00d2j\2\u04a0\u04a1\7\u0086\2\2\u04a1\u04a2"+
		"\5\24\13\2\u04a2\u04a3\7\u0086\2\2\u04a3\u04a4\5\26\f\2\u04a4\u04a5\7"+
		"\u0086\2\2\u04a5\u04a6\5\30\r\2\u04a6\u04a7\7\u0086\2\2\u04a7\u04a8\5"+
		"\30\r\2\u04a8\u04a9\7\u0086\2\2\u04a9\u04ac\5\30\r\2\u04aa\u04ab\7\u0086"+
		"\2\2\u04ab\u04ad\5\30\r\2\u04ac\u04aa\3\2\2\2\u04ac\u04ad\3\2\2\2\u04ad"+
		"\u04ae\3\2\2\2\u04ae\u04af\5$\23\2\u04af\u0087\3\2\2\2\u04b0\u04cb\5\""+
		"\22\2\u04b1\u04b2\5\u00d2j\2\u04b2\u04b3\7\u0086\2\2\u04b3\u04b5\3\2\2"+
		"\2\u04b4\u04b1\3\2\2\2\u04b4\u04b5\3\2\2\2\u04b5\u04b6\3\2\2\2\u04b6\u04cc"+
		"\5\u00d0i\2\u04b7\u04b8\5\u00d2j\2\u04b8\u04b9\7\u0086\2\2\u04b9\u04bb"+
		"\3\2\2\2\u04ba\u04b7\3\2\2\2\u04ba\u04bb\3\2\2\2\u04bb\u04bc\3\2\2\2\u04bc"+
		"\u04bd\5\u00d0i\2\u04bd\u04be\7\u0086\2\2\u04be\u04bf\5\30\r\2\u04bf\u04cc"+
		"\3\2\2\2\u04c0\u04c1\5\u00d2j\2\u04c1\u04c2\7\u0086\2\2\u04c2\u04c4\3"+
		"\2\2\2\u04c3\u04c0\3\2\2\2\u04c3\u04c4\3\2\2\2\u04c4\u04c5\3\2\2\2\u04c5"+
		"\u04c6\5\u00d0i\2\u04c6\u04c7\7\u0086\2\2\u04c7\u04c8\5\30\r\2\u04c8\u04c9"+
		"\7\u0086\2\2\u04c9\u04ca\5\30\r\2\u04ca\u04cc\3\2\2\2\u04cb\u04b4\3\2"+
		"\2\2\u04cb\u04ba\3\2\2\2\u04cb\u04c3\3\2\2\2\u04cc\u04cd\3\2\2\2\u04cd"+
		"\u04ce\5$\23\2\u04ce\u0089\3\2\2\2\u04cf\u04e0\5\"\22\2\u04d0\u04e1\5"+
		"\u00d2j\2\u04d1\u04d2\5\u00d2j\2\u04d2\u04d3\7\u0086\2\2\u04d3\u04d5\3"+
		"\2\2\2\u04d4\u04d1\3\2\2\2\u04d4\u04d5\3\2\2\2\u04d5\u04d6\3\2\2\2\u04d6"+
		"\u04e1\5\30\r\2\u04d7\u04d8\5\u00d2j\2\u04d8\u04d9\7\u0086\2\2\u04d9\u04db"+
		"\3\2\2\2\u04da\u04d7\3\2\2\2\u04da\u04db\3\2\2\2\u04db\u04dc\3\2\2\2\u04dc"+
		"\u04dd\5\30\r\2\u04dd\u04de\7\u0086\2\2\u04de\u04df\5\30\r\2\u04df\u04e1"+
		"\3\2\2\2\u04e0\u04d0\3\2\2\2\u04e0\u04d4\3\2\2\2\u04e0\u04da\3\2\2\2\u04e1"+
		"\u04e2\3\2\2\2\u04e2\u04e3\5$\23\2\u04e3\u008b\3\2\2\2\u04e4\u04ec\5\""+
		"\22\2\u04e5\u04ed\5\u00d2j\2\u04e6\u04e7\5\u00d2j\2\u04e7\u04e8\7\u0086"+
		"\2\2\u04e8\u04ea\3\2\2\2\u04e9\u04e6\3\2\2\2\u04e9\u04ea\3\2\2\2\u04ea"+
		"\u04eb\3\2\2\2\u04eb\u04ed\5\30\r\2\u04ec\u04e5\3\2\2\2\u04ec\u04e9\3"+
		"\2\2\2\u04ed\u04ee\3\2\2\2\u04ee\u04ef\5$\23\2\u04ef\u008d\3\2\2\2\u04f0"+
		"\u04f4\5\"\22\2\u04f1\u04f2\5\u00d2j\2\u04f2\u04f3\7\u0086\2\2\u04f3\u04f5"+
		"\3\2\2\2\u04f4\u04f1\3\2\2\2\u04f4\u04f5\3\2\2\2\u04f5\u04f6\3\2\2\2\u04f6"+
		"\u04f7\5\30\r\2\u04f7\u04f8\7\u0086\2\2\u04f8\u04f9\5\30\r\2\u04f9\u04fa"+
		"\5$\23\2\u04fa\u008f\3\2\2\2\u04fb\u04ff\5\"\22\2\u04fc\u04fd\5\u00d2"+
		"j\2\u04fd\u04fe\7\u0086\2\2\u04fe\u0500\3\2\2\2\u04ff\u04fc\3\2\2\2\u04ff"+
		"\u0500\3\2\2\2\u0500\u0501\3\2\2\2\u0501\u0502\5\30\r\2\u0502\u0503\7"+
		"\u0086\2\2\u0503\u0504\5\30\r\2\u0504\u0505\7\u0086\2\2\u0505\u0508\5"+
		"\30\r\2\u0506\u0507\7\u0086\2\2\u0507\u0509\5\30\r\2\u0508\u0506\3\2\2"+
		"\2\u0508\u0509\3\2\2\2\u0509\u050a\3\2\2\2\u050a\u050b\5$\23\2\u050b\u0091"+
		"\3\2\2\2\u050c\u050d\5\"\22\2\u050d\u050e\5\30\r\2\u050e\u050f\5$\23\2"+
		"\u050f\u0093\3\2\2\2\u0510\u0511\5\"\22\2\u0511\u0512\5\30\r\2\u0512\u0513"+
		"\7\u0086\2\2\u0513\u0514\5\30\r\2\u0514\u0515\5$\23\2\u0515\u0095\3\2"+
		"\2\2\u0516\u0517\5\"\22\2\u0517\u0518\5\u00d2j\2\u0518\u0519\5$\23\2\u0519"+
		"\u0097\3\2\2\2\u051a\u051b\5\"\22\2\u051b\u051c\5\u00d2j\2\u051c\u051d"+
		"\7\u0086\2\2\u051d\u0520\5\u00a8U\2\u051e\u051f\7\u0086\2\2\u051f\u0521"+
		"\5\u00aaV\2\u0520\u051e\3\2\2\2\u0520\u0521\3\2\2\2\u0521\u0524\3\2\2"+
		"\2\u0522\u0523\7\u0086\2\2\u0523\u0525\5\u00a6T\2\u0524\u0522\3\2\2\2"+
		"\u0524\u0525\3\2\2\2\u0525\u0526\3\2\2\2\u0526\u0527\5$\23\2\u0527\u0099"+
		"\3\2\2\2\u0528\u0529\5\"\22\2\u0529\u052a\5\u00d2j\2\u052a\u052b\7\u0086"+
		"\2\2\u052b\u052e\5\u00aaV\2\u052c\u052d\7\u0086\2\2\u052d\u052f\5\u00a6"+
		"T\2\u052e\u052c\3\2\2\2\u052e\u052f\3\2\2\2\u052f\u0530\3\2\2\2\u0530"+
		"\u0531\5$\23\2\u0531\u009b\3\2\2\2\u0532\u0533\5\"\22\2\u0533\u0534\5"+
		"\u00d2j\2\u0534\u0535\7\u0086\2\2\u0535\u0536\5\u00a6T\2\u0536\u0537\5"+
		"$\23\2\u0537\u009d\3\2\2\2\u0538\u0539\5\"\22\2\u0539\u053c\5\u00a8U\2"+
		"\u053a\u053b\7\u0086\2\2\u053b\u053d\5\u00aaV\2\u053c\u053a\3\2\2\2\u053c"+
		"\u053d\3\2\2\2\u053d\u0540\3\2\2\2\u053e\u053f\7\u0086\2\2\u053f\u0541"+
		"\5\u00a6T\2\u0540\u053e\3\2\2\2\u0540\u0541\3\2\2\2\u0541\u0542\3\2\2"+
		"\2\u0542\u0543\5$\23\2\u0543\u009f\3\2\2\2\u0544\u0545\5\"\22\2\u0545"+
		"\u0548\5\u00aaV\2\u0546\u0547\7\u0086\2\2\u0547\u0549\5\u00a6T\2\u0548"+
		"\u0546\3\2\2\2\u0548\u0549\3\2\2\2\u0549\u054a\3\2\2\2\u054a\u054b\5$"+
		"\23\2\u054b\u00a1\3\2\2\2\u054c\u054d\5\"\22\2\u054d\u054e\5\u00a6T\2"+
		"\u054e\u054f\5$\23\2\u054f\u00a3\3\2\2\2\u0550\u0551\5\"\22\2\u0551\u0554"+
		"\5\u00d2j\2\u0552\u0553\7\u0086\2\2\u0553\u0555\5\u00d2j\2\u0554\u0552"+
		"\3\2\2\2\u0554\u0555\3\2\2\2\u0555\u0558\3\2\2\2\u0556\u0557\7\u0086\2"+
		"\2\u0557\u0559\5\u00a8U\2\u0558\u0556\3\2\2\2\u0558\u0559\3\2\2\2\u0559"+
		"\u055c\3\2\2\2\u055a\u055b\7\u0086\2\2\u055b\u055d\5\u00aaV\2\u055c\u055a"+
		"\3\2\2\2\u055c\u055d\3\2\2\2\u055d\u0560\3\2\2\2\u055e\u055f\7\u0086\2"+
		"\2\u055f\u0561\5\u00a6T\2\u0560\u055e\3\2\2\2\u0560\u0561\3\2\2\2\u0561"+
		"\u0562\3\2\2\2\u0562\u0563\5$\23\2\u0563\u00a5\3\2\2\2\u0564\u056a\5\30"+
		"\r\2\u0565\u0566\5\30\r\2\u0566\u0567\7\u0086\2\2\u0567\u0568\5\30\r\2"+
		"\u0568\u056a\3\2\2\2\u0569\u0564\3\2\2\2\u0569\u0565\3\2\2\2\u056a\u00a7"+
		"\3\2\2\2\u056b\u0571\5\24\13\2\u056c\u056d\5\24\13\2\u056d\u056e\7\u0086"+
		"\2\2\u056e\u056f\5\24\13\2\u056f\u0571\3\2\2\2\u0570\u056b\3\2\2\2\u0570"+
		"\u056c\3\2\2\2\u0571\u00a9\3\2\2\2\u0572\u0578\5\26\f\2\u0573\u0574\5"+
		"\26\f\2\u0574\u0575\7\u0086\2\2\u0575\u0576\5\26\f\2\u0576\u0578\3\2\2"+
		"\2\u0577\u0572\3\2\2\2\u0577\u0573\3\2\2\2\u0578\u00ab\3\2\2\2\u0579\u057a"+
		"\5\"\22\2\u057a\u057b\5\u00d2j\2\u057b\u057c\7\u0086\2\2\u057c\u057d\5"+
		"\u00d0i\2\u057d\u057e\5$\23\2\u057e\u00ad\3\2\2\2\u057f\u0580\5\"\22\2"+
		"\u0580\u0583\5\u00d2j\2\u0581\u0582\7\u0086\2\2\u0582\u0584\5\u00d2j\2"+
		"\u0583\u0581\3\2\2\2\u0583\u0584\3\2\2\2\u0584\u0585\3\2\2\2\u0585\u0586"+
		"\5$\23\2\u0586\u00af\3\2\2\2\u0587\u0588\5\"\22\2\u0588\u0589\5\u00d0"+
		"i\2\u0589\u058a\5$\23\2\u058a\u00b1\3\2\2\2\u058b\u058c\5\"\22\2\u058c"+
		"\u0591\5\u00d2j\2\u058d\u058e\7\u0086\2\2\u058e\u0590\5\30\r\2\u058f\u058d"+
		"\3\2\2\2\u0590\u0593\3\2\2\2\u0591\u058f\3\2\2\2\u0591\u0592\3\2\2\2\u0592"+
		"\u0594\3\2\2\2\u0593\u0591\3\2\2\2\u0594\u0595\5$\23\2\u0595\u00b3\3\2"+
		"\2\2\u0596\u0597\5\"\22\2\u0597\u0598\5\u00d2j\2\u0598\u0599\7\u0086\2"+
		"\2\u0599\u059e\5\30\r\2\u059a\u059b\7\u0086\2\2\u059b\u059d\5\30\r\2\u059c"+
		"\u059a\3\2\2\2\u059d\u05a0\3\2\2\2\u059e\u059c\3\2\2\2\u059e\u059f\3\2"+
		"\2\2\u059f\u05a1\3\2\2\2\u05a0\u059e\3\2\2\2\u05a1\u05a2\5$\23\2\u05a2"+
		"\u00b5\3\2\2\2\u05a3\u05a4\5\"\22\2\u05a4\u05a7\5\26\f\2\u05a5\u05a6\7"+
		"\u0086\2\2\u05a6\u05a8\5\30\r\2\u05a7\u05a5\3\2\2\2\u05a8\u05a9\3\2\2"+
		"\2\u05a9\u05a7\3\2\2\2\u05a9\u05aa\3\2\2\2\u05aa\u05ab\3\2\2\2\u05ab\u05ac"+
		"\5$\23\2\u05ac\u00b7\3\2\2\2\u05ad\u05ae\5\"\22\2\u05ae\u05b1\5\u00d2"+
		"j\2\u05af\u05b0\7\u0086\2\2\u05b0\u05b2\5\30\r\2\u05b1\u05af\3\2\2\2\u05b2"+
		"\u05b3\3\2\2\2\u05b3\u05b1\3\2\2\2\u05b3\u05b4\3\2\2\2\u05b4\u05b5\3\2"+
		"\2\2\u05b5\u05b6\5$\23\2\u05b6\u00b9\3\2\2\2\u05b7\u05b8\5\"\22\2\u05b8"+
		"\u05b9\5\u00d2j\2\u05b9\u05ba\7\u0086\2\2\u05ba\u05bd\5\u00d2j\2\u05bb"+
		"\u05bc\7\u0086\2\2\u05bc\u05be\5\30\r\2\u05bd\u05bb\3\2\2\2\u05be\u05bf"+
		"\3\2\2\2\u05bf\u05bd\3\2\2\2\u05bf\u05c0\3\2\2\2\u05c0\u05c1\3\2\2\2\u05c1"+
		"\u05c2\5$\23\2\u05c2\u00bb\3\2\2\2\u05c3\u05c4\5\"\22\2\u05c4\u05c5\5"+
		"\u00d2j\2\u05c5\u05c6\7\u0086\2\2\u05c6\u05c9\5\26\f\2\u05c7\u05c8\7\u0086"+
		"\2\2\u05c8\u05ca\5\30\r\2\u05c9\u05c7\3\2\2\2\u05ca\u05cb\3\2\2\2\u05cb"+
		"\u05c9\3\2\2\2\u05cb\u05cc\3\2\2\2\u05cc\u05cd\3\2\2\2\u05cd\u05ce\5$"+
		"\23\2\u05ce\u00bd\3\2\2\2\u05cf\u05d0\5\"\22\2\u05d0\u05d1\5\u00d2j\2"+
		"\u05d1\u05d2\7\u0086\2\2\u05d2\u05d3\5\u00d2j\2\u05d3\u05d4\7\u0086\2"+
		"\2\u05d4\u05d7\5\26\f\2\u05d5\u05d6\7\u0086\2\2\u05d6\u05d8\5\30\r\2\u05d7"+
		"\u05d5\3\2\2\2\u05d8\u05d9\3\2\2\2\u05d9\u05d7\3\2\2\2\u05d9\u05da\3\2"+
		"\2\2\u05da\u05db\3\2\2\2\u05db\u05dc\5$\23\2\u05dc\u00bf\3\2\2\2\u05dd"+
		"\u05de\5\"\22\2\u05de\u05e3\5\30\r\2\u05df\u05e0\7\u0086\2\2\u05e0\u05e2"+
		"\5\30\r\2\u05e1\u05df\3\2\2\2\u05e2\u05e5\3\2\2\2\u05e3\u05e1\3\2\2\2"+
		"\u05e3\u05e4\3\2\2\2\u05e4\u05e6\3\2\2\2\u05e5\u05e3\3\2\2\2\u05e6\u05e7"+
		"\5$\23\2\u05e7\u00c1\3\2\2\2\u05e8\u05e9\5\"\22\2\u05e9\u05ea\5\u00d2"+
		"j\2\u05ea\u05eb\7\u0086\2\2\u05eb\u05ec\5\u00d2j\2\u05ec\u05ed\5$\23\2"+
		"\u05ed\u00c3\3\2\2\2\u05ee\u05f2\5\"\22\2\u05ef\u05f0\5\24\13\2\u05f0"+
		"\u05f1\7\u0086\2\2\u05f1\u05f3\3\2\2\2\u05f2\u05ef\3\2\2\2\u05f2\u05f3"+
		"\3\2\2\2\u05f3\u05f4\3\2\2\2\u05f4\u05f7\5\26\f\2\u05f5\u05f6\7\u0086"+
		"\2\2\u05f6\u05f8\5\26\f\2\u05f7\u05f5\3\2\2\2\u05f7\u05f8\3\2\2\2\u05f8"+
		"\u05f9\3\2\2\2\u05f9\u05fa\5$\23\2\u05fa\u00c5\3\2\2\2\u05fb\u05fc\5\""+
		"\22\2\u05fc\u0601\5\u00d2j\2\u05fd\u05fe\7\u0086\2\2\u05fe\u0600\5\24"+
		"\13\2\u05ff\u05fd\3\2\2\2\u0600\u0603\3\2\2\2\u0601\u05ff\3\2\2\2\u0601"+
		"\u0602\3\2\2\2\u0602\u0604\3\2\2\2\u0603\u0601\3\2\2\2\u0604\u0605\5$"+
		"\23\2\u0605\u00c7\3\2\2\2\u0606\u0607\5\"\22\2\u0607\u060c\5\u00d2j\2"+
		"\u0608\u0609\7\u0086\2\2\u0609\u060b\5\26\f\2\u060a\u0608\3\2\2\2\u060b"+
		"\u060e\3\2\2\2\u060c\u060a\3\2\2\2\u060c\u060d\3\2\2\2\u060d\u060f\3\2"+
		"\2\2\u060e\u060c\3\2\2\2\u060f\u0610\5$\23\2\u0610\u00c9\3\2\2\2\u0611"+
		"\u0612\5\"\22\2\u0612\u0617\5\u00d2j\2\u0613\u0614\7\u0086\2\2\u0614\u0616"+
		"\5\30\r\2\u0615\u0613\3\2\2\2\u0616\u0619\3\2\2\2\u0617\u0615\3\2\2\2"+
		"\u0617\u0618\3\2\2\2\u0618\u061a\3\2\2\2\u0619\u0617\3\2\2\2\u061a\u061b"+
		"\5$\23\2\u061b\u00cb\3\2\2\2\u061c\u061d\7u\2\2\u061d\u00cd\3\2\2\2\u061e"+
		"\u061f\7v\2\2\u061f\u00cf\3\2\2\2\u0620\u062b\5\u00ccg\2\u0621\u062b\5"+
		"\u00ceh\2\u0622\u0627\5\u00d2j\2\u0623\u0624\7\u0086\2\2\u0624\u0626\5"+
		"\u00d2j\2\u0625\u0623\3\2\2\2\u0626\u0629\3\2\2\2\u0627\u0625\3\2\2\2"+
		"\u0627\u0628\3\2\2\2\u0628\u062b\3\2\2\2\u0629\u0627\3\2\2\2\u062a\u0620"+
		"\3\2\2\2\u062a\u0621\3\2\2\2\u062a\u0622\3\2\2\2\u062b\u00d1\3\2\2\2\u062c"+
		"\u063c\5\u00ccg\2\u062d\u063c\5\u00ceh\2\u062e\u063c\7w\2\2\u062f\u063c"+
		"\7x\2\2\u0630\u063c\7y\2\2\u0631\u063c\7z\2\2\u0632\u063c\7{\2\2\u0633"+
		"\u063c\7|\2\2\u0634\u063c\7}\2\2\u0635\u063c\7~\2\2\u0636\u063c\7\177"+
		"\2\2\u0637\u063c\7\u0080\2\2\u0638\u063c\7\u0081\2\2\u0639\u063c\7\u0082"+
		"\2\2\u063a\u063c\7\u0083\2\2\u063b\u062c\3\2\2\2\u063b\u062d\3\2\2\2\u063b"+
		"\u062e\3\2\2\2\u063b\u062f\3\2\2\2\u063b\u0630\3\2\2\2\u063b\u0631\3\2"+
		"\2\2\u063b\u0632\3\2\2\2\u063b\u0633\3\2\2\2\u063b\u0634\3\2\2\2\u063b"+
		"\u0635\3\2\2\2\u063b\u0636\3\2\2\2\u063b\u0637\3\2\2\2\u063b\u0638\3\2"+
		"\2\2\u063b\u0639\3\2\2\2\u063b\u063a\3\2\2\2\u063c\u00d3\3\2\2\2z\u00ed"+
		"\u00f5\u00fe\u0103\u010a\u0131\u013a\u014d\u0158\u0168\u016d\u0172\u017d"+
		"\u018e\u01a0\u01a5\u01ae\u01b3\u01c0\u01c9\u01e0\u020f\u023a\u025e\u0265"+
		"\u026d\u0275\u027d\u0283\u028d\u029b\u02a6\u02b4\u02bb\u02be\u02c8\u02d7"+
		"\u02ea\u02f8\u0322\u032a\u032e\u033e\u034a\u0355\u0361\u0364\u036e\u037f"+
		"\u038d\u0395\u039e\u03a7\u03af\u03b6\u03be\u03c9\u03d1\u03d8\u03e0\u03eb"+
		"\u03f3\u03fc\u0404\u0411\u0419\u0424\u0431\u043c\u0444\u0453\u0460\u0468"+
		"\u0477\u0484\u048c\u049d\u04ac\u04b4\u04ba\u04c3\u04cb\u04d4\u04da\u04e0"+
		"\u04e9\u04ec\u04f4\u04ff\u0508\u0520\u0524\u052e\u053c\u0540\u0548\u0554"+
		"\u0558\u055c\u0560\u0569\u0570\u0577\u0583\u0591\u059e\u05a9\u05b3\u05bf"+
		"\u05cb\u05d9\u05e3\u05f2\u05f7\u0601\u060c\u0617\u0627\u062a\u063b";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
		_decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
		for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
			_decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
		}
	}
}
