// Generated from EvitaQL.g4 by ANTLR 4.13.2

package io.evitadb.api.query.parser.grammar;

import org.antlr.v4.runtime.atn.*;
import org.antlr.v4.runtime.dfa.DFA;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.misc.*;
import org.antlr.v4.runtime.tree.*;
import java.util.List;
import java.util.Iterator;
import java.util.ArrayList;

@SuppressWarnings({"all", "warnings", "unchecked", "unused", "cast", "CheckReturnValue", "this-escape"})
public class EvitaQLParser extends Parser {
	static { RuntimeMetaData.checkVersion("4.13.2", RuntimeMetaData.VERSION); }

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
		T__113=114, T__114=115, T__115=116, T__116=117, T__117=118, T__118=119, 
		T__119=120, T__120=121, T__121=122, T__122=123, T__123=124, T__124=125, 
		T__125=126, T__126=127, T__127=128, T__128=129, T__129=130, T__130=131, 
		T__131=132, T__132=133, T__133=134, T__134=135, T__135=136, T__136=137, 
		T__137=138, T__138=139, T__139=140, T__140=141, T__141=142, POSITIONAL_PARAMETER=143, 
		NAMED_PARAMETER=144, STRING=145, INT=146, FLOAT=147, BOOLEAN=148, DATE=149, 
		TIME=150, DATE_TIME=151, OFFSET_DATE_TIME=152, FLOAT_NUMBER_RANGE=153, 
		INT_NUMBER_RANGE=154, DATE_TIME_RANGE=155, UUID=156, ENUM=157, ARGS_OPENING=158, 
		ARGS_CLOSING=159, ARGS_DELIMITER=160, COMMENT=161, WHITESPACE=162, UNEXPECTED_CHAR=163;
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
		RULE_classifierWithTwoFilterConstraintArgs = 38, RULE_classifierWithHistogramHavingArgs = 39, 
		RULE_facetGroupRelationArgs = 40, RULE_facetCalculationRulesArgs = 41, 
		RULE_referenceWeightArgs = 42, RULE_classifierWithOrderConstraintListArgs = 43, 
		RULE_hierarchyWithinConstraintArgs = 44, RULE_hierarchyWithinSelfConstraintArgs = 45, 
		RULE_hierarchyWithinRootConstraintArgs = 46, RULE_hierarchyWithinRootSelfConstraintArgs = 47, 
		RULE_attributeSetExactArgs = 48, RULE_pageConstraintArgs = 49, RULE_stripConstraintArgs = 50, 
		RULE_priceContentArgs = 51, RULE_singleRefReferenceContent1Args = 52, 
		RULE_singleRefReferenceContent2Args = 53, RULE_singleRefReferenceContent3Args = 54, 
		RULE_singleRefReferenceContent4Args = 55, RULE_singleRefReferenceContent5Args = 56, 
		RULE_singleRefReferenceContent6Args = 57, RULE_singleRefReferenceContent7Args = 58, 
		RULE_singleRefReferenceContent8Args = 59, RULE_singleRefReferenceContentWithAttributes0Args = 60, 
		RULE_singleRefReferenceContentWithAttributes1Args = 61, RULE_singleRefReferenceContentWithAttributes2Args = 62, 
		RULE_singleRefReferenceContentWithAttributes3Args = 63, RULE_singleRefReferenceContentWithAttributes4Args = 64, 
		RULE_singleRefReferenceContentWithAttributes5Args = 65, RULE_singleRefReferenceContentWithAttributes6Args = 66, 
		RULE_singleRefReferenceContentWithAttributes7Args = 67, RULE_singleRefReferenceContentWithAttributes8Args = 68, 
		RULE_multipleRefsReferenceContentArgs = 69, RULE_allRefsReferenceContentArgs = 70, 
		RULE_allRefsWithAttributesReferenceContent1Args = 71, RULE_allRefsWithAttributesReferenceContent2Args = 72, 
		RULE_allRefsWithAttributesReferenceContent3Args = 73, RULE_singleRequireHierarchyContentArgs = 74, 
		RULE_allRequiresHierarchyContentArgs = 75, RULE_facetSummary1Args = 76, 
		RULE_facetSummary2Args = 77, RULE_facetSummary3Args = 78, RULE_facetSummary4Args = 79, 
		RULE_facetSummary5Args = 80, RULE_facetSummary6Args = 81, RULE_facetSummary7Args = 82, 
		RULE_facetSummaryOfReference2Args = 83, RULE_facetSummaryRequirementsArgs = 84, 
		RULE_facetSummaryFilterArgs = 85, RULE_facetSummaryOrderArgs = 86, RULE_referenceSummary1Args = 87, 
		RULE_referenceSummary2Args = 88, RULE_referenceSummary3Args = 89, RULE_referenceSummary4Args = 90, 
		RULE_referenceSummary5Args = 91, RULE_referenceSummary6Args = 92, RULE_referenceSummary7Args = 93, 
		RULE_referenceSummaryOfReference2Args = 94, RULE_referenceSummaryRequirementsArgs = 95, 
		RULE_histogramStatistics1Args = 96, RULE_histogramStatistics2Args = 97, 
		RULE_attributeHistogramArgs = 98, RULE_priceHistogramArgs = 99, RULE_hierarchyStatisticsArgs = 100, 
		RULE_hierarchyRequireConstraintArgs = 101, RULE_hierarchyFromNodeArgs = 102, 
		RULE_fullHierarchyOfSelfArgs = 103, RULE_basicHierarchyOfReferenceArgs = 104, 
		RULE_basicHierarchyOfReferenceWithBehaviourArgs = 105, RULE_fullHierarchyOfReferenceArgs = 106, 
		RULE_fullHierarchyOfReferenceWithBehaviourArgs = 107, RULE_spacingRequireConstraintArgs = 108, 
		RULE_gapRequireConstraintArgs = 109, RULE_segmentArgs = 110, RULE_inScopeFilterArgs = 111, 
		RULE_textReferenceArgs = 112, RULE_textMatchesArgs = 113, RULE_inScopeOrderArgs = 114, 
		RULE_inScopeRequireArgs = 115, RULE_positionalParameter = 116, RULE_namedParameter = 117, 
		RULE_variadicValueTokens = 118, RULE_valueToken = 119, RULE_singleRefReferenceContentWithAttributeNamesArgs = 120;
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
			"classifierWithHistogramHavingArgs", "facetGroupRelationArgs", "facetCalculationRulesArgs", 
			"referenceWeightArgs", "classifierWithOrderConstraintListArgs", "hierarchyWithinConstraintArgs", 
			"hierarchyWithinSelfConstraintArgs", "hierarchyWithinRootConstraintArgs", 
			"hierarchyWithinRootSelfConstraintArgs", "attributeSetExactArgs", "pageConstraintArgs", 
			"stripConstraintArgs", "priceContentArgs", "singleRefReferenceContent1Args", 
			"singleRefReferenceContent2Args", "singleRefReferenceContent3Args", "singleRefReferenceContent4Args", 
			"singleRefReferenceContent5Args", "singleRefReferenceContent6Args", "singleRefReferenceContent7Args", 
			"singleRefReferenceContent8Args", "singleRefReferenceContentWithAttributes0Args", 
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
			"referenceSummary1Args", "referenceSummary2Args", "referenceSummary3Args", 
			"referenceSummary4Args", "referenceSummary5Args", "referenceSummary6Args", 
			"referenceSummary7Args", "referenceSummaryOfReference2Args", "referenceSummaryRequirementsArgs", 
			"histogramStatistics1Args", "histogramStatistics2Args", "attributeHistogramArgs", 
			"priceHistogramArgs", "hierarchyStatisticsArgs", "hierarchyRequireConstraintArgs", 
			"hierarchyFromNodeArgs", "fullHierarchyOfSelfArgs", "basicHierarchyOfReferenceArgs", 
			"basicHierarchyOfReferenceWithBehaviourArgs", "fullHierarchyOfReferenceArgs", 
			"fullHierarchyOfReferenceWithBehaviourArgs", "spacingRequireConstraintArgs", 
			"gapRequireConstraintArgs", "segmentArgs", "inScopeFilterArgs", "textReferenceArgs", 
			"textMatchesArgs", "inScopeOrderArgs", "inScopeRequireArgs", "positionalParameter", 
			"namedParameter", "variadicValueTokens", "valueToken", "singleRefReferenceContentWithAttributeNamesArgs"
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
			"'attributeInRangeNow'", "'entityPrimaryKeyInSet'", "'entityPrimaryKeyGreaterThan'", 
			"'entityPrimaryKeyGreaterThanEquals'", "'entityPrimaryKeyLessThan'", 
			"'entityPrimaryKeyLessThanEquals'", "'entityPrimaryKeyBetween'", "'entityLocaleEquals'", 
			"'priceInCurrency'", "'priceInPriceLists'", "'priceValidInNow'", "'priceValidIn'", 
			"'priceBetween'", "'facetHaving'", "'histogramHaving'", "'includingChildren'", 
			"'includingChildrenHaving'", "'includingChildrenExcept'", "'referenceHaving'", 
			"'hierarchyWithin'", "'hierarchyWithinSelf'", "'hierarchyWithinRoot'", 
			"'hierarchyWithinRootSelf'", "'directRelation'", "'having'", "'anyHaving'", 
			"'excludingRoot'", "'excluding'", "'entityHaving'", "'groupHaving'", 
			"'inScope'", "'scope'", "'textMatches'", "'inAttribute'", "'inAttributeAll'", 
			"'inAssociatedData'", "'inAssociatedDataAll'", "'inReference'", "'inEntity'", 
			"'orderBy'", "'orderGroupBy'", "'attributeNatural'", "'attributeSetExact'", 
			"'attributeSetInFilter'", "'priceNatural'", "'priceDiscount'", "'random'", 
			"'randomWithSeed'", "'referenceProperty'", "'traverseByEntityProperty'", 
			"'pickFirstByEntityProperty'", "'entityPrimaryKeyNatural'", "'entityPrimaryKeyExact'", 
			"'entityPrimaryKeyInFilter'", "'entityProperty'", "'entityGroupProperty'", 
			"'segments'", "'segment'", "'limit'", "'relevanceText'", "'attributeWeight'", 
			"'associatedDataWeight'", "'referenceWeight'", "'entityWeight'", "'require'", 
			"'page'", "'strip'", "'entityFetch'", "'entityGroupFetch'", "'attributeContent'", 
			"'attributeContentAll'", "'priceContent'", "'priceContentAll'", "'priceContentRespectingFilter'", 
			"'associatedDataContent'", "'associatedDataContentAll'", "'referenceContentAll'", 
			"'referenceContent'", "'referenceContentAllWithAttributes'", "'referenceContentWithAttributes'", 
			"'hierarchyContent'", "'defaultAccompanyingPriceLists'", "'accompanyingPriceContentDefault'", 
			"'accompanyingPriceContent'", "'priceType'", "'dataInLocalesAll'", "'dataInLocales'", 
			"'facetSummary'", "'facetSummaryOfReference'", "'referenceSummary'", 
			"'referenceSummaryWithHistograms'", "'referenceSummaryOfReference'", 
			"'referenceSummaryOfReferenceWithHistograms'", "'histogramStatistics'", 
			"'facetGroupsConjunction'", "'facetGroupsDisjunction'", "'facetGroupsNegation'", 
			"'facetGroupsExclusivity'", "'facetCalculationRules'", "'attributeHistogram'", 
			"'priceHistogram'", "'distance'", "'level'", "'node'", "'stopAt'", "'statistics'", 
			"'fromRoot'", "'fromNode'", "'children'", "'siblings'", "'spacing'", 
			"'gap'", "'parents'", "'hierarchyOfSelf'", "'hierarchyOfReference'", 
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
			null, null, null, null, null, null, null, null, null, null, null, null, 
			null, null, null, null, null, null, null, null, null, null, null, null, 
			null, null, null, null, null, null, null, null, null, null, null, null, 
			null, null, null, null, null, null, null, null, null, null, null, "POSITIONAL_PARAMETER", 
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(242);
			query();
			setState(243);
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
			setState(245);
			headConstraintList();
			setState(246);
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
			setState(248);
			filterConstraintList();
			setState(249);
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
			setState(251);
			orderConstraintList();
			setState(252);
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
			setState(254);
			requireConstraintList();
			setState(255);
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
			setState(257);
			valueToken();
			setState(258);
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
			setState(260);
			match(T__0);
			setState(261);
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(267);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,0,_ctx) ) {
			case 1:
				enterOuterAlt(_localctx, 1);
				{
				setState(263);
				headConstraint();
				}
				break;
			case 2:
				enterOuterAlt(_localctx, 2);
				{
				setState(264);
				filterConstraint();
				}
				break;
			case 3:
				enterOuterAlt(_localctx, 3);
				{
				setState(265);
				orderConstraint();
				}
				break;
			case 4:
				enterOuterAlt(_localctx, 4);
				{
				setState(266);
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

	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
			setState(275);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case T__1:
				_localctx = new HeadContainerConstraintContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(269);
				match(T__1);
				setState(270);
				((HeadContainerConstraintContext)_localctx).args = headConstraintListArgs();
				}
				break;
			case T__2:
				_localctx = new CollectionConstraintContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(271);
				match(T__2);
				setState(272);
				((CollectionConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case T__3:
				_localctx = new LabelConstraintContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(273);
				match(T__3);
				setState(274);
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

	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class InAssociatedDataConstraintContext extends FilterConstraintContext {
		public ClassifierListArgsContext args;
		public ClassifierListArgsContext classifierListArgs() {
			return getRuleContext(ClassifierListArgsContext.class,0);
		}
		public InAssociatedDataConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterInAssociatedDataConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitInAssociatedDataConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitInAssociatedDataConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class EntityPrimaryKeyLessThanEqualsConstraintContext extends FilterConstraintContext {
		public ValueArgsContext args;
		public ValueArgsContext valueArgs() {
			return getRuleContext(ValueArgsContext.class,0);
		}
		public EntityPrimaryKeyLessThanEqualsConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterEntityPrimaryKeyLessThanEqualsConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitEntityPrimaryKeyLessThanEqualsConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitEntityPrimaryKeyLessThanEqualsConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class InAttributeAllConstraintContext extends FilterConstraintContext {
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public InAttributeAllConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterInAttributeAllConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitInAttributeAllConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitInAttributeAllConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class InReferenceConstraintContext extends FilterConstraintContext {
		public TextReferenceArgsContext args;
		public TextReferenceArgsContext textReferenceArgs() {
			return getRuleContext(TextReferenceArgsContext.class,0);
		}
		public InReferenceConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterInReferenceConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitInReferenceConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitInReferenceConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class InAttributeConstraintContext extends FilterConstraintContext {
		public ClassifierListArgsContext args;
		public ClassifierListArgsContext classifierListArgs() {
			return getRuleContext(ClassifierListArgsContext.class,0);
		}
		public InAttributeConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterInAttributeConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitInAttributeConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitInAttributeConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class InAssociatedDataAllConstraintContext extends FilterConstraintContext {
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public InAssociatedDataAllConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterInAssociatedDataAllConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitInAssociatedDataAllConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitInAssociatedDataAllConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class HierarchyAnyHavingConstraintContext extends FilterConstraintContext {
		public FilterConstraintListArgsContext args;
		public FilterConstraintListArgsContext filterConstraintListArgs() {
			return getRuleContext(FilterConstraintListArgsContext.class,0);
		}
		public HierarchyAnyHavingConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHierarchyAnyHavingConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHierarchyAnyHavingConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHierarchyAnyHavingConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class InEntityConstraintContext extends FilterConstraintContext {
		public FilterConstraintListArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public FilterConstraintListArgsContext filterConstraintListArgs() {
			return getRuleContext(FilterConstraintListArgsContext.class,0);
		}
		public InEntityConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterInEntityConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitInEntityConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitInEntityConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class HistogramHavingConstraintContext extends FilterConstraintContext {
		public ClassifierWithHistogramHavingArgsContext args;
		public ClassifierWithHistogramHavingArgsContext classifierWithHistogramHavingArgs() {
			return getRuleContext(ClassifierWithHistogramHavingArgsContext.class,0);
		}
		public HistogramHavingConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHistogramHavingConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHistogramHavingConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHistogramHavingConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class EntityHavingConstraintContext extends FilterConstraintContext {
		public FilterConstraintListArgsContext args;
		public FilterConstraintListArgsContext filterConstraintListArgs() {
			return getRuleContext(FilterConstraintListArgsContext.class,0);
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
	@SuppressWarnings("CheckReturnValue")
	public static class GroupHavingConstraintContext extends FilterConstraintContext {
		public FilterConstraintListArgsContext args;
		public FilterConstraintListArgsContext filterConstraintListArgs() {
			return getRuleContext(FilterConstraintListArgsContext.class,0);
		}
		public GroupHavingConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterGroupHavingConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitGroupHavingConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitGroupHavingConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class EntityPrimaryKeyGreaterThanConstraintContext extends FilterConstraintContext {
		public ValueArgsContext args;
		public ValueArgsContext valueArgs() {
			return getRuleContext(ValueArgsContext.class,0);
		}
		public EntityPrimaryKeyGreaterThanConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterEntityPrimaryKeyGreaterThanConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitEntityPrimaryKeyGreaterThanConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitEntityPrimaryKeyGreaterThanConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class TextMatchesConstraintContext extends FilterConstraintContext {
		public TextMatchesArgsContext args;
		public TextMatchesArgsContext textMatchesArgs() {
			return getRuleContext(TextMatchesArgsContext.class,0);
		}
		public TextMatchesConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterTextMatchesConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitTextMatchesConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitTextMatchesConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class EntityPrimaryKeyGreaterThanEqualsConstraintContext extends FilterConstraintContext {
		public ValueArgsContext args;
		public ValueArgsContext valueArgs() {
			return getRuleContext(ValueArgsContext.class,0);
		}
		public EntityPrimaryKeyGreaterThanEqualsConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterEntityPrimaryKeyGreaterThanEqualsConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitEntityPrimaryKeyGreaterThanEqualsConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitEntityPrimaryKeyGreaterThanEqualsConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class EntityPrimaryKeyLessThanConstraintContext extends FilterConstraintContext {
		public ValueArgsContext args;
		public ValueArgsContext valueArgs() {
			return getRuleContext(ValueArgsContext.class,0);
		}
		public EntityPrimaryKeyLessThanConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterEntityPrimaryKeyLessThanConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitEntityPrimaryKeyLessThanConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitEntityPrimaryKeyLessThanConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class EntityPrimaryKeyBetweenConstraintContext extends FilterConstraintContext {
		public BetweenValuesArgsContext args;
		public BetweenValuesArgsContext betweenValuesArgs() {
			return getRuleContext(BetweenValuesArgsContext.class,0);
		}
		public EntityPrimaryKeyBetweenConstraintContext(FilterConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterEntityPrimaryKeyBetweenConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitEntityPrimaryKeyBetweenConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitEntityPrimaryKeyBetweenConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
			setState(423);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case T__4:
				_localctx = new FilterByConstraintContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(277);
				match(T__4);
				setState(278);
				((FilterByConstraintContext)_localctx).args = filterConstraintListArgs();
				}
				break;
			case T__5:
				_localctx = new FilterGroupByConstraintContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(279);
				match(T__5);
				setState(280);
				((FilterGroupByConstraintContext)_localctx).args = filterConstraintListArgs();
				}
				break;
			case T__6:
				_localctx = new AndConstraintContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(281);
				match(T__6);
				setState(284);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,2,_ctx) ) {
				case 1:
					{
					setState(282);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(283);
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
				setState(286);
				match(T__7);
				setState(289);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,3,_ctx) ) {
				case 1:
					{
					setState(287);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(288);
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
				setState(291);
				match(T__8);
				setState(292);
				((NotConstraintContext)_localctx).args = filterConstraintArgs();
				}
				break;
			case T__9:
				_localctx = new UserFilterConstraintContext(_localctx);
				enterOuterAlt(_localctx, 6);
				{
				setState(293);
				match(T__9);
				setState(296);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,4,_ctx) ) {
				case 1:
					{
					setState(294);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(295);
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
				setState(298);
				match(T__10);
				setState(299);
				((AttributeEqualsConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__11:
				_localctx = new AttributeGreaterThanConstraintContext(_localctx);
				enterOuterAlt(_localctx, 8);
				{
				setState(300);
				match(T__11);
				setState(301);
				((AttributeGreaterThanConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__12:
				_localctx = new AttributeGreaterThanEqualsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 9);
				{
				setState(302);
				match(T__12);
				setState(303);
				((AttributeGreaterThanEqualsConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__13:
				_localctx = new AttributeLessThanConstraintContext(_localctx);
				enterOuterAlt(_localctx, 10);
				{
				setState(304);
				match(T__13);
				setState(305);
				((AttributeLessThanConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__14:
				_localctx = new AttributeLessThanEqualsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 11);
				{
				setState(306);
				match(T__14);
				setState(307);
				((AttributeLessThanEqualsConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__15:
				_localctx = new AttributeBetweenConstraintContext(_localctx);
				enterOuterAlt(_localctx, 12);
				{
				setState(308);
				match(T__15);
				setState(309);
				((AttributeBetweenConstraintContext)_localctx).args = classifierWithBetweenValuesArgs();
				}
				break;
			case T__16:
				_localctx = new AttributeInSetConstraintContext(_localctx);
				enterOuterAlt(_localctx, 13);
				{
				setState(310);
				match(T__16);
				setState(311);
				((AttributeInSetConstraintContext)_localctx).args = classifierWithOptionalValueListArgs();
				}
				break;
			case T__17:
				_localctx = new AttributeContainsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 14);
				{
				setState(312);
				match(T__17);
				setState(313);
				((AttributeContainsConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__18:
				_localctx = new AttributeStartsWithConstraintContext(_localctx);
				enterOuterAlt(_localctx, 15);
				{
				setState(314);
				match(T__18);
				setState(315);
				((AttributeStartsWithConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__19:
				_localctx = new AttributeEndsWithConstraintContext(_localctx);
				enterOuterAlt(_localctx, 16);
				{
				setState(316);
				match(T__19);
				setState(317);
				((AttributeEndsWithConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__20:
				_localctx = new AttributeEqualsTrueConstraintContext(_localctx);
				enterOuterAlt(_localctx, 17);
				{
				setState(318);
				match(T__20);
				setState(319);
				((AttributeEqualsTrueConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case T__21:
				_localctx = new AttributeEqualsFalseConstraintContext(_localctx);
				enterOuterAlt(_localctx, 18);
				{
				setState(320);
				match(T__21);
				setState(321);
				((AttributeEqualsFalseConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case T__22:
				_localctx = new AttributeIsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 19);
				{
				setState(322);
				match(T__22);
				setState(323);
				((AttributeIsConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__23:
				_localctx = new AttributeIsNullConstraintContext(_localctx);
				enterOuterAlt(_localctx, 20);
				{
				setState(324);
				match(T__23);
				setState(325);
				((AttributeIsNullConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case T__24:
				_localctx = new AttributeIsNotNullConstraintContext(_localctx);
				enterOuterAlt(_localctx, 21);
				{
				setState(326);
				match(T__24);
				setState(327);
				((AttributeIsNotNullConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case T__25:
				_localctx = new AttributeInRangeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 22);
				{
				setState(328);
				match(T__25);
				setState(329);
				((AttributeInRangeConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__26:
				_localctx = new AttributeInRangeNowConstraintContext(_localctx);
				enterOuterAlt(_localctx, 23);
				{
				setState(330);
				match(T__26);
				setState(331);
				((AttributeInRangeNowConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case T__27:
				_localctx = new EntityPrimaryKeyInSetConstraintContext(_localctx);
				enterOuterAlt(_localctx, 24);
				{
				setState(332);
				match(T__27);
				setState(335);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,5,_ctx) ) {
				case 1:
					{
					setState(333);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(334);
					((EntityPrimaryKeyInSetConstraintContext)_localctx).args = valueListArgs();
					}
					break;
				}
				}
				break;
			case T__28:
				_localctx = new EntityPrimaryKeyGreaterThanConstraintContext(_localctx);
				enterOuterAlt(_localctx, 25);
				{
				setState(337);
				match(T__28);
				setState(338);
				((EntityPrimaryKeyGreaterThanConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case T__29:
				_localctx = new EntityPrimaryKeyGreaterThanEqualsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 26);
				{
				setState(339);
				match(T__29);
				setState(340);
				((EntityPrimaryKeyGreaterThanEqualsConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case T__30:
				_localctx = new EntityPrimaryKeyLessThanConstraintContext(_localctx);
				enterOuterAlt(_localctx, 27);
				{
				setState(341);
				match(T__30);
				setState(342);
				((EntityPrimaryKeyLessThanConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case T__31:
				_localctx = new EntityPrimaryKeyLessThanEqualsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 28);
				{
				setState(343);
				match(T__31);
				setState(344);
				((EntityPrimaryKeyLessThanEqualsConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case T__32:
				_localctx = new EntityPrimaryKeyBetweenConstraintContext(_localctx);
				enterOuterAlt(_localctx, 29);
				{
				setState(345);
				match(T__32);
				setState(346);
				((EntityPrimaryKeyBetweenConstraintContext)_localctx).args = betweenValuesArgs();
				}
				break;
			case T__33:
				_localctx = new EntityLocaleEqualsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 30);
				{
				setState(347);
				match(T__33);
				setState(348);
				((EntityLocaleEqualsConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case T__34:
				_localctx = new PriceInCurrencyConstraintContext(_localctx);
				enterOuterAlt(_localctx, 31);
				{
				setState(349);
				match(T__34);
				setState(350);
				((PriceInCurrencyConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case T__35:
				_localctx = new PriceInPriceListsConstraintsContext(_localctx);
				enterOuterAlt(_localctx, 32);
				{
				setState(351);
				match(T__35);
				setState(354);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,6,_ctx) ) {
				case 1:
					{
					setState(352);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(353);
					((PriceInPriceListsConstraintsContext)_localctx).args = classifierListArgs();
					}
					break;
				}
				}
				break;
			case T__36:
				_localctx = new PriceValidInNowConstraintContext(_localctx);
				enterOuterAlt(_localctx, 33);
				{
				setState(356);
				match(T__36);
				setState(357);
				emptyArgs();
				}
				break;
			case T__37:
				_localctx = new PriceValidInConstraintContext(_localctx);
				enterOuterAlt(_localctx, 34);
				{
				setState(358);
				match(T__37);
				setState(359);
				((PriceValidInConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case T__38:
				_localctx = new PriceBetweenConstraintContext(_localctx);
				enterOuterAlt(_localctx, 35);
				{
				setState(360);
				match(T__38);
				setState(361);
				((PriceBetweenConstraintContext)_localctx).args = betweenValuesArgs();
				}
				break;
			case T__39:
				_localctx = new FacetHavingConstraintContext(_localctx);
				enterOuterAlt(_localctx, 36);
				{
				setState(362);
				match(T__39);
				setState(363);
				((FacetHavingConstraintContext)_localctx).args = classifierWithTwoFilterConstraintArgs();
				}
				break;
			case T__40:
				_localctx = new HistogramHavingConstraintContext(_localctx);
				enterOuterAlt(_localctx, 37);
				{
				setState(364);
				match(T__40);
				setState(365);
				((HistogramHavingConstraintContext)_localctx).args = classifierWithHistogramHavingArgs();
				}
				break;
			case T__41:
				_localctx = new FacetIncludingChildrenConstraintContext(_localctx);
				enterOuterAlt(_localctx, 38);
				{
				setState(366);
				match(T__41);
				setState(367);
				emptyArgs();
				}
				break;
			case T__42:
				_localctx = new FacetIncludingChildrenHavingConstraintContext(_localctx);
				enterOuterAlt(_localctx, 39);
				{
				setState(368);
				match(T__42);
				setState(369);
				((FacetIncludingChildrenHavingConstraintContext)_localctx).args = filterConstraintArgs();
				}
				break;
			case T__43:
				_localctx = new FacetIncludingChildrenExceptConstraintContext(_localctx);
				enterOuterAlt(_localctx, 40);
				{
				setState(370);
				match(T__43);
				setState(371);
				((FacetIncludingChildrenExceptConstraintContext)_localctx).args = filterConstraintArgs();
				}
				break;
			case T__44:
				_localctx = new ReferenceHavingConstraintContext(_localctx);
				enterOuterAlt(_localctx, 41);
				{
				setState(372);
				match(T__44);
				setState(375);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,7,_ctx) ) {
				case 1:
					{
					setState(373);
					((ReferenceHavingConstraintContext)_localctx).args = classifierArgs();
					}
					break;
				case 2:
					{
					setState(374);
					classifierWithFilterConstraintArgs();
					}
					break;
				}
				}
				break;
			case T__45:
				_localctx = new HierarchyWithinConstraintContext(_localctx);
				enterOuterAlt(_localctx, 42);
				{
				setState(377);
				match(T__45);
				setState(378);
				((HierarchyWithinConstraintContext)_localctx).args = hierarchyWithinConstraintArgs();
				}
				break;
			case T__46:
				_localctx = new HierarchyWithinSelfConstraintContext(_localctx);
				enterOuterAlt(_localctx, 43);
				{
				setState(379);
				match(T__46);
				setState(380);
				((HierarchyWithinSelfConstraintContext)_localctx).args = hierarchyWithinSelfConstraintArgs();
				}
				break;
			case T__47:
				_localctx = new HierarchyWithinRootConstraintContext(_localctx);
				enterOuterAlt(_localctx, 44);
				{
				setState(381);
				match(T__47);
				setState(382);
				((HierarchyWithinRootConstraintContext)_localctx).args = hierarchyWithinRootConstraintArgs();
				}
				break;
			case T__48:
				_localctx = new HierarchyWithinRootSelfConstraintContext(_localctx);
				enterOuterAlt(_localctx, 45);
				{
				setState(383);
				match(T__48);
				setState(386);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,8,_ctx) ) {
				case 1:
					{
					setState(384);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(385);
					((HierarchyWithinRootSelfConstraintContext)_localctx).args = hierarchyWithinRootSelfConstraintArgs();
					}
					break;
				}
				}
				break;
			case T__49:
				_localctx = new HierarchyDirectRelationConstraintContext(_localctx);
				enterOuterAlt(_localctx, 46);
				{
				setState(388);
				match(T__49);
				setState(389);
				emptyArgs();
				}
				break;
			case T__50:
				_localctx = new HierarchyHavingConstraintContext(_localctx);
				enterOuterAlt(_localctx, 47);
				{
				setState(390);
				match(T__50);
				setState(391);
				((HierarchyHavingConstraintContext)_localctx).args = filterConstraintListArgs();
				}
				break;
			case T__51:
				_localctx = new HierarchyAnyHavingConstraintContext(_localctx);
				enterOuterAlt(_localctx, 48);
				{
				setState(392);
				match(T__51);
				setState(393);
				((HierarchyAnyHavingConstraintContext)_localctx).args = filterConstraintListArgs();
				}
				break;
			case T__52:
				_localctx = new HierarchyExcludingRootConstraintContext(_localctx);
				enterOuterAlt(_localctx, 49);
				{
				setState(394);
				match(T__52);
				setState(395);
				emptyArgs();
				}
				break;
			case T__53:
				_localctx = new HierarchyExcludingConstraintContext(_localctx);
				enterOuterAlt(_localctx, 50);
				{
				setState(396);
				match(T__53);
				setState(397);
				((HierarchyExcludingConstraintContext)_localctx).args = filterConstraintListArgs();
				}
				break;
			case T__54:
				_localctx = new EntityHavingConstraintContext(_localctx);
				enterOuterAlt(_localctx, 51);
				{
				setState(398);
				match(T__54);
				setState(399);
				((EntityHavingConstraintContext)_localctx).args = filterConstraintListArgs();
				}
				break;
			case T__55:
				_localctx = new GroupHavingConstraintContext(_localctx);
				enterOuterAlt(_localctx, 52);
				{
				setState(400);
				match(T__55);
				setState(401);
				((GroupHavingConstraintContext)_localctx).args = filterConstraintListArgs();
				}
				break;
			case T__56:
				_localctx = new FilterInScopeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 53);
				{
				setState(402);
				match(T__56);
				setState(403);
				((FilterInScopeConstraintContext)_localctx).args = inScopeFilterArgs();
				}
				break;
			case T__57:
				_localctx = new EntityScopeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 54);
				{
				setState(404);
				match(T__57);
				setState(405);
				((EntityScopeConstraintContext)_localctx).args = valueListArgs();
				}
				break;
			case T__58:
				_localctx = new TextMatchesConstraintContext(_localctx);
				enterOuterAlt(_localctx, 55);
				{
				setState(406);
				match(T__58);
				setState(407);
				((TextMatchesConstraintContext)_localctx).args = textMatchesArgs();
				}
				break;
			case T__59:
				_localctx = new InAttributeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 56);
				{
				setState(408);
				match(T__59);
				setState(409);
				((InAttributeConstraintContext)_localctx).args = classifierListArgs();
				}
				break;
			case T__60:
				_localctx = new InAttributeAllConstraintContext(_localctx);
				enterOuterAlt(_localctx, 57);
				{
				setState(410);
				match(T__60);
				setState(411);
				emptyArgs();
				}
				break;
			case T__61:
				_localctx = new InAssociatedDataConstraintContext(_localctx);
				enterOuterAlt(_localctx, 58);
				{
				setState(412);
				match(T__61);
				setState(413);
				((InAssociatedDataConstraintContext)_localctx).args = classifierListArgs();
				}
				break;
			case T__62:
				_localctx = new InAssociatedDataAllConstraintContext(_localctx);
				enterOuterAlt(_localctx, 59);
				{
				setState(414);
				match(T__62);
				setState(415);
				emptyArgs();
				}
				break;
			case T__63:
				_localctx = new InReferenceConstraintContext(_localctx);
				enterOuterAlt(_localctx, 60);
				{
				setState(416);
				match(T__63);
				setState(417);
				((InReferenceConstraintContext)_localctx).args = textReferenceArgs();
				}
				break;
			case T__64:
				_localctx = new InEntityConstraintContext(_localctx);
				enterOuterAlt(_localctx, 61);
				{
				setState(418);
				match(T__64);
				setState(421);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,9,_ctx) ) {
				case 1:
					{
					setState(419);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(420);
					((InEntityConstraintContext)_localctx).args = filterConstraintListArgs();
					}
					break;
				}
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class AssociatedDataWeightConstraintContext extends OrderConstraintContext {
		public ClassifierWithValueArgsContext args;
		public ClassifierWithValueArgsContext classifierWithValueArgs() {
			return getRuleContext(ClassifierWithValueArgsContext.class,0);
		}
		public AssociatedDataWeightConstraintContext(OrderConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAssociatedDataWeightConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAssociatedDataWeightConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAssociatedDataWeightConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class EntityWeightConstraintContext extends OrderConstraintContext {
		public OrderConstraintListArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public OrderConstraintListArgsContext orderConstraintListArgs() {
			return getRuleContext(OrderConstraintListArgsContext.class,0);
		}
		public EntityWeightConstraintContext(OrderConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterEntityWeightConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitEntityWeightConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitEntityWeightConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceWeightConstraintContext extends OrderConstraintContext {
		public ReferenceWeightArgsContext args;
		public ReferenceWeightArgsContext referenceWeightArgs() {
			return getRuleContext(ReferenceWeightArgsContext.class,0);
		}
		public ReferenceWeightConstraintContext(OrderConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceWeightConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceWeightConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceWeightConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class RelevanceTextConstraintContext extends OrderConstraintContext {
		public OrderConstraintListArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public OrderConstraintListArgsContext orderConstraintListArgs() {
			return getRuleContext(OrderConstraintListArgsContext.class,0);
		}
		public RelevanceTextConstraintContext(OrderConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterRelevanceTextConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitRelevanceTextConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitRelevanceTextConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class AttributeWeightConstraintContext extends OrderConstraintContext {
		public ClassifierWithValueArgsContext args;
		public ClassifierWithValueArgsContext classifierWithValueArgs() {
			return getRuleContext(ClassifierWithValueArgsContext.class,0);
		}
		public AttributeWeightConstraintContext(OrderConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAttributeWeightConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAttributeWeightConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAttributeWeightConstraint(this);
			else return visitor.visitChildren(this);
		}
	}

	public final OrderConstraintContext orderConstraint() throws RecognitionException {
		OrderConstraintContext _localctx = new OrderConstraintContext(_ctx, getState());
		enterRule(_localctx, 20, RULE_orderConstraint);
		try {
			setState(495);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case T__65:
				_localctx = new OrderByConstraintContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(425);
				match(T__65);
				setState(428);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,11,_ctx) ) {
				case 1:
					{
					setState(426);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(427);
					((OrderByConstraintContext)_localctx).args = orderConstraintListArgs();
					}
					break;
				}
				}
				break;
			case T__66:
				_localctx = new OrderGroupByConstraintContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(430);
				match(T__66);
				setState(433);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,12,_ctx) ) {
				case 1:
					{
					setState(431);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(432);
					((OrderGroupByConstraintContext)_localctx).args = orderConstraintListArgs();
					}
					break;
				}
				}
				break;
			case T__67:
				_localctx = new AttributeNaturalConstraintContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(435);
				match(T__67);
				setState(436);
				((AttributeNaturalConstraintContext)_localctx).args = classifierWithOptionalValueArgs();
				}
				break;
			case T__68:
				_localctx = new AttributeSetExactConstraintContext(_localctx);
				enterOuterAlt(_localctx, 4);
				{
				setState(437);
				match(T__68);
				setState(438);
				((AttributeSetExactConstraintContext)_localctx).args = attributeSetExactArgs();
				}
				break;
			case T__69:
				_localctx = new AttributeSetInFilterConstraintContext(_localctx);
				enterOuterAlt(_localctx, 5);
				{
				setState(439);
				match(T__69);
				setState(440);
				((AttributeSetInFilterConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case T__70:
				_localctx = new PriceNaturalConstraintContext(_localctx);
				enterOuterAlt(_localctx, 6);
				{
				setState(441);
				match(T__70);
				setState(444);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,13,_ctx) ) {
				case 1:
					{
					setState(442);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(443);
					((PriceNaturalConstraintContext)_localctx).args = valueArgs();
					}
					break;
				}
				}
				break;
			case T__71:
				_localctx = new PriceDiscountConstraintContext(_localctx);
				enterOuterAlt(_localctx, 7);
				{
				setState(446);
				match(T__71);
				setState(447);
				((PriceDiscountConstraintContext)_localctx).args = valueListArgs();
				}
				break;
			case T__72:
				_localctx = new RandomConstraintContext(_localctx);
				enterOuterAlt(_localctx, 8);
				{
				setState(448);
				match(T__72);
				setState(449);
				emptyArgs();
				}
				break;
			case T__73:
				_localctx = new RandomWithSeedConstraintContext(_localctx);
				enterOuterAlt(_localctx, 9);
				{
				setState(450);
				match(T__73);
				setState(451);
				((RandomWithSeedConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case T__74:
				_localctx = new ReferencePropertyConstraintContext(_localctx);
				enterOuterAlt(_localctx, 10);
				{
				setState(452);
				match(T__74);
				setState(453);
				((ReferencePropertyConstraintContext)_localctx).args = classifierWithOrderConstraintListArgs();
				}
				break;
			case T__75:
				_localctx = new TraverseByEntityPropertyConstraintContext(_localctx);
				enterOuterAlt(_localctx, 11);
				{
				setState(454);
				match(T__75);
				setState(455);
				((TraverseByEntityPropertyConstraintContext)_localctx).args = traverseOrderConstraintListArgs();
				}
				break;
			case T__76:
				_localctx = new PickFirstByByEntityPropertyConstraintContext(_localctx);
				enterOuterAlt(_localctx, 12);
				{
				setState(456);
				match(T__76);
				setState(457);
				((PickFirstByByEntityPropertyConstraintContext)_localctx).args = orderConstraintListArgs();
				}
				break;
			case T__77:
				_localctx = new EntityPrimaryKeyExactNaturalContext(_localctx);
				enterOuterAlt(_localctx, 13);
				{
				setState(458);
				match(T__77);
				setState(461);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,14,_ctx) ) {
				case 1:
					{
					setState(459);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(460);
					((EntityPrimaryKeyExactNaturalContext)_localctx).args = valueArgs();
					}
					break;
				}
				}
				break;
			case T__78:
				_localctx = new EntityPrimaryKeyExactConstraintContext(_localctx);
				enterOuterAlt(_localctx, 14);
				{
				setState(463);
				match(T__78);
				setState(464);
				((EntityPrimaryKeyExactConstraintContext)_localctx).args = valueListArgs();
				}
				break;
			case T__79:
				_localctx = new EntityPrimaryKeyInFilterConstraintContext(_localctx);
				enterOuterAlt(_localctx, 15);
				{
				setState(465);
				match(T__79);
				setState(466);
				emptyArgs();
				}
				break;
			case T__80:
				_localctx = new EntityPropertyConstraintContext(_localctx);
				enterOuterAlt(_localctx, 16);
				{
				setState(467);
				match(T__80);
				setState(468);
				((EntityPropertyConstraintContext)_localctx).args = orderConstraintListArgs();
				}
				break;
			case T__81:
				_localctx = new EntityGroupPropertyConstraintContext(_localctx);
				enterOuterAlt(_localctx, 17);
				{
				setState(469);
				match(T__81);
				setState(470);
				((EntityGroupPropertyConstraintContext)_localctx).args = orderConstraintListArgs();
				}
				break;
			case T__82:
				_localctx = new SegmentsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 18);
				{
				setState(471);
				match(T__82);
				setState(472);
				((SegmentsConstraintContext)_localctx).args = orderConstraintListArgs();
				}
				break;
			case T__83:
				_localctx = new SegmentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 19);
				{
				setState(473);
				match(T__83);
				setState(474);
				((SegmentConstraintContext)_localctx).args = segmentArgs();
				}
				break;
			case T__84:
				_localctx = new SegmentLimitConstraintContext(_localctx);
				enterOuterAlt(_localctx, 20);
				{
				setState(475);
				match(T__84);
				setState(476);
				((SegmentLimitConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case T__56:
				_localctx = new OrderInScopeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 21);
				{
				setState(477);
				match(T__56);
				setState(478);
				((OrderInScopeConstraintContext)_localctx).args = inScopeOrderArgs();
				}
				break;
			case T__85:
				_localctx = new RelevanceTextConstraintContext(_localctx);
				enterOuterAlt(_localctx, 22);
				{
				setState(479);
				match(T__85);
				setState(482);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,15,_ctx) ) {
				case 1:
					{
					setState(480);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(481);
					((RelevanceTextConstraintContext)_localctx).args = orderConstraintListArgs();
					}
					break;
				}
				}
				break;
			case T__86:
				_localctx = new AttributeWeightConstraintContext(_localctx);
				enterOuterAlt(_localctx, 23);
				{
				setState(484);
				match(T__86);
				setState(485);
				((AttributeWeightConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__87:
				_localctx = new AssociatedDataWeightConstraintContext(_localctx);
				enterOuterAlt(_localctx, 24);
				{
				setState(486);
				match(T__87);
				setState(487);
				((AssociatedDataWeightConstraintContext)_localctx).args = classifierWithValueArgs();
				}
				break;
			case T__88:
				_localctx = new ReferenceWeightConstraintContext(_localctx);
				enterOuterAlt(_localctx, 25);
				{
				setState(488);
				match(T__88);
				setState(489);
				((ReferenceWeightConstraintContext)_localctx).args = referenceWeightArgs();
				}
				break;
			case T__89:
				_localctx = new EntityWeightConstraintContext(_localctx);
				enterOuterAlt(_localctx, 26);
				{
				setState(490);
				match(T__89);
				setState(493);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,16,_ctx) ) {
				case 1:
					{
					setState(491);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(492);
					((EntityWeightConstraintContext)_localctx).args = orderConstraintListArgs();
					}
					break;
				}
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceSummaryWithHistograms5ConstraintContext extends RequireConstraintContext {
		public ReferenceSummary5ArgsContext args;
		public ReferenceSummary5ArgsContext referenceSummary5Args() {
			return getRuleContext(ReferenceSummary5ArgsContext.class,0);
		}
		public ReferenceSummaryWithHistograms5ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceSummaryWithHistograms5Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceSummaryWithHistograms5Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceSummaryWithHistograms5Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceSummary1ConstraintContext extends RequireConstraintContext {
		public ReferenceSummary1ArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public ReferenceSummary1ArgsContext referenceSummary1Args() {
			return getRuleContext(ReferenceSummary1ArgsContext.class,0);
		}
		public ReferenceSummary1ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceSummary1Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceSummary1Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceSummary1Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceSummaryWithHistograms6ConstraintContext extends RequireConstraintContext {
		public ReferenceSummary6ArgsContext args;
		public ReferenceSummary6ArgsContext referenceSummary6Args() {
			return getRuleContext(ReferenceSummary6ArgsContext.class,0);
		}
		public ReferenceSummaryWithHistograms6ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceSummaryWithHistograms6Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceSummaryWithHistograms6Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceSummaryWithHistograms6Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceSummaryOfReferenceWithHistograms1ConstraintContext extends RequireConstraintContext {
		public ClassifierArgsContext args;
		public ClassifierArgsContext classifierArgs() {
			return getRuleContext(ClassifierArgsContext.class,0);
		}
		public ReferenceSummaryOfReferenceWithHistograms1ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceSummaryOfReferenceWithHistograms1Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceSummaryOfReferenceWithHistograms1Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceSummaryOfReferenceWithHistograms1Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class DefaultAccompanyingPriceListsConstraintContext extends RequireConstraintContext {
		public ClassifierListArgsContext args;
		public ClassifierListArgsContext classifierListArgs() {
			return getRuleContext(ClassifierListArgsContext.class,0);
		}
		public DefaultAccompanyingPriceListsConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterDefaultAccompanyingPriceListsConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitDefaultAccompanyingPriceListsConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitDefaultAccompanyingPriceListsConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceSummaryWithHistograms3ConstraintContext extends RequireConstraintContext {
		public ReferenceSummary3ArgsContext args;
		public ReferenceSummary3ArgsContext referenceSummary3Args() {
			return getRuleContext(ReferenceSummary3ArgsContext.class,0);
		}
		public ReferenceSummaryWithHistograms3ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceSummaryWithHistograms3Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceSummaryWithHistograms3Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceSummaryWithHistograms3Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceSummaryOfReference2ConstraintContext extends RequireConstraintContext {
		public ReferenceSummaryOfReference2ArgsContext args;
		public ReferenceSummaryOfReference2ArgsContext referenceSummaryOfReference2Args() {
			return getRuleContext(ReferenceSummaryOfReference2ArgsContext.class,0);
		}
		public ReferenceSummaryOfReference2ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceSummaryOfReference2Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceSummaryOfReference2Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceSummaryOfReference2Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceSummary3ConstraintContext extends RequireConstraintContext {
		public ReferenceSummary3ArgsContext args;
		public ReferenceSummary3ArgsContext referenceSummary3Args() {
			return getRuleContext(ReferenceSummary3ArgsContext.class,0);
		}
		public ReferenceSummary3ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceSummary3Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceSummary3Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceSummary3Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceSummaryWithHistograms2ConstraintContext extends RequireConstraintContext {
		public ReferenceSummary2ArgsContext args;
		public ReferenceSummary2ArgsContext referenceSummary2Args() {
			return getRuleContext(ReferenceSummary2ArgsContext.class,0);
		}
		public ReferenceSummaryWithHistograms2ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceSummaryWithHistograms2Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceSummaryWithHistograms2Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceSummaryWithHistograms2Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceSummary6ConstraintContext extends RequireConstraintContext {
		public ReferenceSummary6ArgsContext args;
		public ReferenceSummary6ArgsContext referenceSummary6Args() {
			return getRuleContext(ReferenceSummary6ArgsContext.class,0);
		}
		public ReferenceSummary6ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceSummary6Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceSummary6Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceSummary6Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class HistogramStatistics1ConstraintContext extends RequireConstraintContext {
		public HistogramStatistics1ArgsContext args;
		public HistogramStatistics1ArgsContext histogramStatistics1Args() {
			return getRuleContext(HistogramStatistics1ArgsContext.class,0);
		}
		public HistogramStatistics1ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHistogramStatistics1Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHistogramStatistics1Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHistogramStatistics1Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceSummary5ConstraintContext extends RequireConstraintContext {
		public ReferenceSummary5ArgsContext args;
		public ReferenceSummary5ArgsContext referenceSummary5Args() {
			return getRuleContext(ReferenceSummary5ArgsContext.class,0);
		}
		public ReferenceSummary5ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceSummary5Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceSummary5Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceSummary5Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceSummaryWithHistograms1ConstraintContext extends RequireConstraintContext {
		public ReferenceSummary1ArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public ReferenceSummary1ArgsContext referenceSummary1Args() {
			return getRuleContext(ReferenceSummary1ArgsContext.class,0);
		}
		public ReferenceSummaryWithHistograms1ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceSummaryWithHistograms1Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceSummaryWithHistograms1Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceSummaryWithHistograms1Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceSummary4ConstraintContext extends RequireConstraintContext {
		public ReferenceSummary4ArgsContext args;
		public ReferenceSummary4ArgsContext referenceSummary4Args() {
			return getRuleContext(ReferenceSummary4ArgsContext.class,0);
		}
		public ReferenceSummary4ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceSummary4Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceSummary4Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceSummary4Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class AccompanyingPriceContentDefaultConstraintContext extends RequireConstraintContext {
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public AccompanyingPriceContentDefaultConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterAccompanyingPriceContentDefaultConstraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitAccompanyingPriceContentDefaultConstraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitAccompanyingPriceContentDefaultConstraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class AccompanyingPriceContentConstraintContext extends RequireConstraintContext {
		public ClassifierWithOptionalValueListArgsContext args;
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
	@SuppressWarnings("CheckReturnValue")
	public static class SingleRefReferenceContentWithAttributes1ConstraintContext extends RequireConstraintContext {
		public SingleRefReferenceContentWithAttributeNamesArgsContext args;
		public SingleRefReferenceContentWithAttributeNamesArgsContext singleRefReferenceContentWithAttributeNamesArgs() {
			return getRuleContext(SingleRefReferenceContentWithAttributeNamesArgsContext.class,0);
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
	@SuppressWarnings("CheckReturnValue")
	public static class HistogramStatistics2ConstraintContext extends RequireConstraintContext {
		public HistogramStatistics2ArgsContext args;
		public HistogramStatistics2ArgsContext histogramStatistics2Args() {
			return getRuleContext(HistogramStatistics2ArgsContext.class,0);
		}
		public HistogramStatistics2ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHistogramStatistics2Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHistogramStatistics2Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHistogramStatistics2Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceSummary7ConstraintContext extends RequireConstraintContext {
		public ReferenceSummary7ArgsContext args;
		public ReferenceSummary7ArgsContext referenceSummary7Args() {
			return getRuleContext(ReferenceSummary7ArgsContext.class,0);
		}
		public ReferenceSummary7ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceSummary7Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceSummary7Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceSummary7Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class QueryTelemetryConstraintContext extends RequireConstraintContext {
		public ValueArgsContext args;
		public EmptyArgsContext emptyArgs() {
			return getRuleContext(EmptyArgsContext.class,0);
		}
		public ValueArgsContext valueArgs() {
			return getRuleContext(ValueArgsContext.class,0);
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceSummaryWithHistograms7ConstraintContext extends RequireConstraintContext {
		public ReferenceSummary7ArgsContext args;
		public ReferenceSummary7ArgsContext referenceSummary7Args() {
			return getRuleContext(ReferenceSummary7ArgsContext.class,0);
		}
		public ReferenceSummaryWithHistograms7ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceSummaryWithHistograms7Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceSummaryWithHistograms7Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceSummaryWithHistograms7Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceSummaryOfReferenceWithHistograms2ConstraintContext extends RequireConstraintContext {
		public ReferenceSummaryOfReference2ArgsContext args;
		public ReferenceSummaryOfReference2ArgsContext referenceSummaryOfReference2Args() {
			return getRuleContext(ReferenceSummaryOfReference2ArgsContext.class,0);
		}
		public ReferenceSummaryOfReferenceWithHistograms2ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceSummaryOfReferenceWithHistograms2Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceSummaryOfReferenceWithHistograms2Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceSummaryOfReferenceWithHistograms2Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceSummaryWithHistograms4ConstraintContext extends RequireConstraintContext {
		public ReferenceSummary4ArgsContext args;
		public ReferenceSummary4ArgsContext referenceSummary4Args() {
			return getRuleContext(ReferenceSummary4ArgsContext.class,0);
		}
		public ReferenceSummaryWithHistograms4ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceSummaryWithHistograms4Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceSummaryWithHistograms4Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceSummaryWithHistograms4Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceSummary2ConstraintContext extends RequireConstraintContext {
		public ReferenceSummary2ArgsContext args;
		public ReferenceSummary2ArgsContext referenceSummary2Args() {
			return getRuleContext(ReferenceSummary2ArgsContext.class,0);
		}
		public ReferenceSummary2ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceSummary2Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceSummary2Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceSummary2Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceSummaryOfReference1ConstraintContext extends RequireConstraintContext {
		public ClassifierArgsContext args;
		public ClassifierArgsContext classifierArgs() {
			return getRuleContext(ClassifierArgsContext.class,0);
		}
		public ReferenceSummaryOfReference1ConstraintContext(RequireConstraintContext ctx) { copyFrom(ctx); }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceSummaryOfReference1Constraint(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceSummaryOfReference1Constraint(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceSummaryOfReference1Constraint(this);
			else return visitor.visitChildren(this);
		}
	}
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
			setState(740);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,29,_ctx) ) {
			case 1:
				_localctx = new RequireContainerConstraintContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(497);
				match(T__90);
				setState(500);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,18,_ctx) ) {
				case 1:
					{
					setState(498);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(499);
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
				setState(502);
				match(T__91);
				setState(503);
				((PageConstraintContext)_localctx).args = pageConstraintArgs();
				}
				break;
			case 3:
				_localctx = new StripConstraintContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(504);
				match(T__92);
				setState(505);
				((StripConstraintContext)_localctx).args = stripConstraintArgs();
				}
				break;
			case 4:
				_localctx = new EntityFetchConstraintContext(_localctx);
				enterOuterAlt(_localctx, 4);
				{
				setState(506);
				match(T__93);
				setState(509);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,19,_ctx) ) {
				case 1:
					{
					setState(507);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(508);
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
				setState(511);
				match(T__94);
				setState(514);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,20,_ctx) ) {
				case 1:
					{
					setState(512);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(513);
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
				setState(516);
				match(T__95);
				setState(517);
				((AttributeContentConstraintContext)_localctx).args = classifierListArgs();
				}
				break;
			case 7:
				_localctx = new AttributeContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 7);
				{
				setState(518);
				match(T__96);
				setState(519);
				emptyArgs();
				}
				break;
			case 8:
				_localctx = new PriceContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 8);
				{
				setState(520);
				match(T__97);
				setState(521);
				((PriceContentConstraintContext)_localctx).args = priceContentArgs();
				}
				break;
			case 9:
				_localctx = new PriceContentAllConstraintContext(_localctx);
				enterOuterAlt(_localctx, 9);
				{
				setState(522);
				match(T__98);
				setState(523);
				emptyArgs();
				}
				break;
			case 10:
				_localctx = new PriceContentRespectingFilterConstraintContext(_localctx);
				enterOuterAlt(_localctx, 10);
				{
				setState(524);
				match(T__99);
				setState(527);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,21,_ctx) ) {
				case 1:
					{
					setState(525);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(526);
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
				setState(529);
				match(T__100);
				setState(530);
				((AssociatedDataContentConstraintContext)_localctx).args = classifierListArgs();
				}
				break;
			case 12:
				_localctx = new AssociatedDataContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 12);
				{
				setState(531);
				match(T__101);
				setState(532);
				emptyArgs();
				}
				break;
			case 13:
				_localctx = new AllRefsReferenceContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 13);
				{
				setState(533);
				match(T__102);
				setState(536);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,22,_ctx) ) {
				case 1:
					{
					setState(534);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(535);
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
				setState(538);
				match(T__103);
				setState(539);
				((MultipleRefsReferenceContentConstraintContext)_localctx).args = multipleRefsReferenceContentArgs();
				}
				break;
			case 15:
				_localctx = new SingleRefReferenceContent1ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 15);
				{
				setState(540);
				match(T__103);
				setState(541);
				((SingleRefReferenceContent1ConstraintContext)_localctx).args = singleRefReferenceContent1Args();
				}
				break;
			case 16:
				_localctx = new SingleRefReferenceContent2ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 16);
				{
				setState(542);
				match(T__103);
				setState(543);
				((SingleRefReferenceContent2ConstraintContext)_localctx).args = singleRefReferenceContent2Args();
				}
				break;
			case 17:
				_localctx = new SingleRefReferenceContent3ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 17);
				{
				setState(544);
				match(T__103);
				setState(545);
				((SingleRefReferenceContent3ConstraintContext)_localctx).args = singleRefReferenceContent3Args();
				}
				break;
			case 18:
				_localctx = new SingleRefReferenceContent4ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 18);
				{
				setState(546);
				match(T__103);
				setState(547);
				((SingleRefReferenceContent4ConstraintContext)_localctx).args = singleRefReferenceContent4Args();
				}
				break;
			case 19:
				_localctx = new SingleRefReferenceContent5ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 19);
				{
				setState(548);
				match(T__103);
				setState(549);
				((SingleRefReferenceContent5ConstraintContext)_localctx).args = singleRefReferenceContent5Args();
				}
				break;
			case 20:
				_localctx = new SingleRefReferenceContent6ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 20);
				{
				setState(550);
				match(T__103);
				setState(551);
				((SingleRefReferenceContent6ConstraintContext)_localctx).args = singleRefReferenceContent6Args();
				}
				break;
			case 21:
				_localctx = new SingleRefReferenceContent7ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 21);
				{
				setState(552);
				match(T__103);
				setState(553);
				((SingleRefReferenceContent7ConstraintContext)_localctx).args = singleRefReferenceContent7Args();
				}
				break;
			case 22:
				_localctx = new SingleRefReferenceContent8ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 22);
				{
				setState(554);
				match(T__103);
				setState(555);
				((SingleRefReferenceContent8ConstraintContext)_localctx).args = singleRefReferenceContent8Args();
				}
				break;
			case 23:
				_localctx = new AllRefsWithAttributesReferenceContent1ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 23);
				{
				setState(556);
				match(T__104);
				setState(559);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,23,_ctx) ) {
				case 1:
					{
					setState(557);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(558);
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
				setState(561);
				match(T__104);
				setState(562);
				((AllRefsWithAttributesReferenceContent2ConstraintContext)_localctx).args = allRefsWithAttributesReferenceContent2Args();
				}
				break;
			case 25:
				_localctx = new AllRefsWithAttributesReferenceContent3ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 25);
				{
				setState(563);
				match(T__104);
				setState(564);
				((AllRefsWithAttributesReferenceContent3ConstraintContext)_localctx).args = allRefsWithAttributesReferenceContent3Args();
				}
				break;
			case 26:
				_localctx = new SingleRefReferenceContentWithAttributes1ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 26);
				{
				setState(565);
				match(T__105);
				setState(566);
				((SingleRefReferenceContentWithAttributes1ConstraintContext)_localctx).args = singleRefReferenceContentWithAttributeNamesArgs();
				}
				break;
			case 27:
				_localctx = new SingleRefReferenceContentWithAttributes0ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 27);
				{
				setState(567);
				match(T__105);
				setState(568);
				((SingleRefReferenceContentWithAttributes0ConstraintContext)_localctx).args = singleRefReferenceContentWithAttributes0Args();
				}
				break;
			case 28:
				_localctx = new SingleRefReferenceContentWithAttributes2ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 28);
				{
				setState(569);
				match(T__105);
				setState(570);
				((SingleRefReferenceContentWithAttributes2ConstraintContext)_localctx).args = singleRefReferenceContentWithAttributes1Args();
				}
				break;
			case 29:
				_localctx = new SingleRefReferenceContentWithAttributes3ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 29);
				{
				setState(571);
				match(T__105);
				setState(572);
				((SingleRefReferenceContentWithAttributes3ConstraintContext)_localctx).args = singleRefReferenceContentWithAttributes2Args();
				}
				break;
			case 30:
				_localctx = new SingleRefReferenceContentWithAttributes4ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 30);
				{
				setState(573);
				match(T__105);
				setState(574);
				((SingleRefReferenceContentWithAttributes4ConstraintContext)_localctx).args = singleRefReferenceContent3Args();
				}
				break;
			case 31:
				_localctx = new SingleRefReferenceContentWithAttributes5ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 31);
				{
				setState(575);
				match(T__105);
				setState(576);
				((SingleRefReferenceContentWithAttributes5ConstraintContext)_localctx).args = singleRefReferenceContentWithAttributes3Args();
				}
				break;
			case 32:
				_localctx = new SingleRefReferenceContentWithAttributes6ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 32);
				{
				setState(577);
				match(T__105);
				setState(578);
				((SingleRefReferenceContentWithAttributes6ConstraintContext)_localctx).args = singleRefReferenceContentWithAttributes4Args();
				}
				break;
			case 33:
				_localctx = new SingleRefReferenceContentWithAttributes7ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 33);
				{
				setState(579);
				match(T__105);
				setState(580);
				((SingleRefReferenceContentWithAttributes7ConstraintContext)_localctx).args = singleRefReferenceContent5Args();
				}
				break;
			case 34:
				_localctx = new SingleRefReferenceContentWithAttributes8ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 34);
				{
				setState(581);
				match(T__105);
				setState(582);
				((SingleRefReferenceContentWithAttributes8ConstraintContext)_localctx).args = singleRefReferenceContentWithAttributes5Args();
				}
				break;
			case 35:
				_localctx = new SingleRefReferenceContentWithAttributes9ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 35);
				{
				setState(583);
				match(T__105);
				setState(584);
				((SingleRefReferenceContentWithAttributes9ConstraintContext)_localctx).args = singleRefReferenceContentWithAttributes6Args();
				}
				break;
			case 36:
				_localctx = new SingleRefReferenceContentWithAttributes10ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 36);
				{
				setState(585);
				match(T__105);
				setState(586);
				((SingleRefReferenceContentWithAttributes10ConstraintContext)_localctx).args = singleRefReferenceContent7Args();
				}
				break;
			case 37:
				_localctx = new SingleRefReferenceContentWithAttributes11ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 37);
				{
				setState(587);
				match(T__105);
				setState(588);
				((SingleRefReferenceContentWithAttributes11ConstraintContext)_localctx).args = singleRefReferenceContentWithAttributes7Args();
				}
				break;
			case 38:
				_localctx = new SingleRefReferenceContentWithAttributes12ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 38);
				{
				setState(589);
				match(T__105);
				setState(590);
				((SingleRefReferenceContentWithAttributes12ConstraintContext)_localctx).args = singleRefReferenceContentWithAttributes8Args();
				}
				break;
			case 39:
				_localctx = new EmptyHierarchyContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 39);
				{
				setState(591);
				match(T__106);
				setState(592);
				emptyArgs();
				}
				break;
			case 40:
				_localctx = new SingleRequireHierarchyContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 40);
				{
				setState(593);
				match(T__106);
				setState(594);
				((SingleRequireHierarchyContentConstraintContext)_localctx).args = singleRequireHierarchyContentArgs();
				}
				break;
			case 41:
				_localctx = new AllRequiresHierarchyContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 41);
				{
				setState(595);
				match(T__106);
				setState(596);
				((AllRequiresHierarchyContentConstraintContext)_localctx).args = allRequiresHierarchyContentArgs();
				}
				break;
			case 42:
				_localctx = new DefaultAccompanyingPriceListsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 42);
				{
				setState(597);
				match(T__107);
				setState(598);
				((DefaultAccompanyingPriceListsConstraintContext)_localctx).args = classifierListArgs();
				}
				break;
			case 43:
				_localctx = new AccompanyingPriceContentDefaultConstraintContext(_localctx);
				enterOuterAlt(_localctx, 43);
				{
				setState(599);
				match(T__108);
				setState(600);
				emptyArgs();
				}
				break;
			case 44:
				_localctx = new AccompanyingPriceContentConstraintContext(_localctx);
				enterOuterAlt(_localctx, 44);
				{
				setState(601);
				match(T__109);
				setState(602);
				((AccompanyingPriceContentConstraintContext)_localctx).args = classifierWithOptionalValueListArgs();
				}
				break;
			case 45:
				_localctx = new PriceTypeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 45);
				{
				setState(603);
				match(T__110);
				setState(604);
				((PriceTypeConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case 46:
				_localctx = new DataInLocalesAllConstraintContext(_localctx);
				enterOuterAlt(_localctx, 46);
				{
				setState(605);
				match(T__111);
				setState(606);
				emptyArgs();
				}
				break;
			case 47:
				_localctx = new DataInLocalesConstraintContext(_localctx);
				enterOuterAlt(_localctx, 47);
				{
				setState(607);
				match(T__112);
				setState(608);
				((DataInLocalesConstraintContext)_localctx).args = valueListArgs();
				}
				break;
			case 48:
				_localctx = new FacetSummary1ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 48);
				{
				setState(609);
				match(T__113);
				setState(612);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,24,_ctx) ) {
				case 1:
					{
					setState(610);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(611);
					((FacetSummary1ConstraintContext)_localctx).args = facetSummary1Args();
					}
					break;
				}
				}
				break;
			case 49:
				_localctx = new FacetSummary2ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 49);
				{
				setState(614);
				match(T__113);
				setState(615);
				((FacetSummary2ConstraintContext)_localctx).args = facetSummary2Args();
				}
				break;
			case 50:
				_localctx = new FacetSummary3ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 50);
				{
				setState(616);
				match(T__113);
				setState(617);
				((FacetSummary3ConstraintContext)_localctx).args = facetSummary3Args();
				}
				break;
			case 51:
				_localctx = new FacetSummary4ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 51);
				{
				setState(618);
				match(T__113);
				setState(619);
				((FacetSummary4ConstraintContext)_localctx).args = facetSummary4Args();
				}
				break;
			case 52:
				_localctx = new FacetSummary5ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 52);
				{
				setState(620);
				match(T__113);
				setState(621);
				((FacetSummary5ConstraintContext)_localctx).args = facetSummary5Args();
				}
				break;
			case 53:
				_localctx = new FacetSummary6ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 53);
				{
				setState(622);
				match(T__113);
				setState(623);
				((FacetSummary6ConstraintContext)_localctx).args = facetSummary6Args();
				}
				break;
			case 54:
				_localctx = new FacetSummary7ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 54);
				{
				setState(624);
				match(T__113);
				setState(625);
				((FacetSummary7ConstraintContext)_localctx).args = facetSummary7Args();
				}
				break;
			case 55:
				_localctx = new FacetSummaryOfReference1ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 55);
				{
				setState(626);
				match(T__114);
				setState(627);
				((FacetSummaryOfReference1ConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case 56:
				_localctx = new FacetSummaryOfReference2ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 56);
				{
				setState(628);
				match(T__114);
				setState(629);
				((FacetSummaryOfReference2ConstraintContext)_localctx).args = facetSummaryOfReference2Args();
				}
				break;
			case 57:
				_localctx = new ReferenceSummary1ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 57);
				{
				setState(630);
				match(T__115);
				setState(633);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,25,_ctx) ) {
				case 1:
					{
					setState(631);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(632);
					((ReferenceSummary1ConstraintContext)_localctx).args = referenceSummary1Args();
					}
					break;
				}
				}
				break;
			case 58:
				_localctx = new ReferenceSummary2ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 58);
				{
				setState(635);
				match(T__115);
				setState(636);
				((ReferenceSummary2ConstraintContext)_localctx).args = referenceSummary2Args();
				}
				break;
			case 59:
				_localctx = new ReferenceSummary3ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 59);
				{
				setState(637);
				match(T__115);
				setState(638);
				((ReferenceSummary3ConstraintContext)_localctx).args = referenceSummary3Args();
				}
				break;
			case 60:
				_localctx = new ReferenceSummary4ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 60);
				{
				setState(639);
				match(T__115);
				setState(640);
				((ReferenceSummary4ConstraintContext)_localctx).args = referenceSummary4Args();
				}
				break;
			case 61:
				_localctx = new ReferenceSummary5ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 61);
				{
				setState(641);
				match(T__115);
				setState(642);
				((ReferenceSummary5ConstraintContext)_localctx).args = referenceSummary5Args();
				}
				break;
			case 62:
				_localctx = new ReferenceSummary6ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 62);
				{
				setState(643);
				match(T__115);
				setState(644);
				((ReferenceSummary6ConstraintContext)_localctx).args = referenceSummary6Args();
				}
				break;
			case 63:
				_localctx = new ReferenceSummary7ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 63);
				{
				setState(645);
				match(T__115);
				setState(646);
				((ReferenceSummary7ConstraintContext)_localctx).args = referenceSummary7Args();
				}
				break;
			case 64:
				_localctx = new ReferenceSummaryWithHistograms1ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 64);
				{
				setState(647);
				match(T__116);
				setState(650);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,26,_ctx) ) {
				case 1:
					{
					setState(648);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(649);
					((ReferenceSummaryWithHistograms1ConstraintContext)_localctx).args = referenceSummary1Args();
					}
					break;
				}
				}
				break;
			case 65:
				_localctx = new ReferenceSummaryWithHistograms2ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 65);
				{
				setState(652);
				match(T__116);
				setState(653);
				((ReferenceSummaryWithHistograms2ConstraintContext)_localctx).args = referenceSummary2Args();
				}
				break;
			case 66:
				_localctx = new ReferenceSummaryWithHistograms3ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 66);
				{
				setState(654);
				match(T__116);
				setState(655);
				((ReferenceSummaryWithHistograms3ConstraintContext)_localctx).args = referenceSummary3Args();
				}
				break;
			case 67:
				_localctx = new ReferenceSummaryWithHistograms4ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 67);
				{
				setState(656);
				match(T__116);
				setState(657);
				((ReferenceSummaryWithHistograms4ConstraintContext)_localctx).args = referenceSummary4Args();
				}
				break;
			case 68:
				_localctx = new ReferenceSummaryWithHistograms5ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 68);
				{
				setState(658);
				match(T__116);
				setState(659);
				((ReferenceSummaryWithHistograms5ConstraintContext)_localctx).args = referenceSummary5Args();
				}
				break;
			case 69:
				_localctx = new ReferenceSummaryWithHistograms6ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 69);
				{
				setState(660);
				match(T__116);
				setState(661);
				((ReferenceSummaryWithHistograms6ConstraintContext)_localctx).args = referenceSummary6Args();
				}
				break;
			case 70:
				_localctx = new ReferenceSummaryWithHistograms7ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 70);
				{
				setState(662);
				match(T__116);
				setState(663);
				((ReferenceSummaryWithHistograms7ConstraintContext)_localctx).args = referenceSummary7Args();
				}
				break;
			case 71:
				_localctx = new ReferenceSummaryOfReference1ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 71);
				{
				setState(664);
				match(T__117);
				setState(665);
				((ReferenceSummaryOfReference1ConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case 72:
				_localctx = new ReferenceSummaryOfReference2ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 72);
				{
				setState(666);
				match(T__117);
				setState(667);
				((ReferenceSummaryOfReference2ConstraintContext)_localctx).args = referenceSummaryOfReference2Args();
				}
				break;
			case 73:
				_localctx = new ReferenceSummaryOfReferenceWithHistograms1ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 73);
				{
				setState(668);
				match(T__118);
				setState(669);
				((ReferenceSummaryOfReferenceWithHistograms1ConstraintContext)_localctx).args = classifierArgs();
				}
				break;
			case 74:
				_localctx = new ReferenceSummaryOfReferenceWithHistograms2ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 74);
				{
				setState(670);
				match(T__118);
				setState(671);
				((ReferenceSummaryOfReferenceWithHistograms2ConstraintContext)_localctx).args = referenceSummaryOfReference2Args();
				}
				break;
			case 75:
				_localctx = new HistogramStatistics1ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 75);
				{
				setState(672);
				match(T__119);
				setState(673);
				((HistogramStatistics1ConstraintContext)_localctx).args = histogramStatistics1Args();
				}
				break;
			case 76:
				_localctx = new HistogramStatistics2ConstraintContext(_localctx);
				enterOuterAlt(_localctx, 76);
				{
				setState(674);
				match(T__119);
				setState(675);
				((HistogramStatistics2ConstraintContext)_localctx).args = histogramStatistics2Args();
				}
				break;
			case 77:
				_localctx = new FacetGroupsConjunctionConstraintContext(_localctx);
				enterOuterAlt(_localctx, 77);
				{
				setState(676);
				match(T__120);
				setState(677);
				((FacetGroupsConjunctionConstraintContext)_localctx).args = facetGroupRelationArgs();
				}
				break;
			case 78:
				_localctx = new FacetGroupsDisjunctionConstraintContext(_localctx);
				enterOuterAlt(_localctx, 78);
				{
				setState(678);
				match(T__121);
				setState(679);
				((FacetGroupsDisjunctionConstraintContext)_localctx).args = facetGroupRelationArgs();
				}
				break;
			case 79:
				_localctx = new FacetGroupsNegationConstraintContext(_localctx);
				enterOuterAlt(_localctx, 79);
				{
				setState(680);
				match(T__122);
				setState(681);
				((FacetGroupsNegationConstraintContext)_localctx).args = facetGroupRelationArgs();
				}
				break;
			case 80:
				_localctx = new FacetGroupsExclusivityConstraintContext(_localctx);
				enterOuterAlt(_localctx, 80);
				{
				setState(682);
				match(T__123);
				setState(683);
				((FacetGroupsExclusivityConstraintContext)_localctx).args = facetGroupRelationArgs();
				}
				break;
			case 81:
				_localctx = new FacetCalculationRulesConstraintContext(_localctx);
				enterOuterAlt(_localctx, 81);
				{
				setState(684);
				match(T__124);
				setState(685);
				((FacetCalculationRulesConstraintContext)_localctx).args = facetCalculationRulesArgs();
				}
				break;
			case 82:
				_localctx = new AttributeHistogramConstraintContext(_localctx);
				enterOuterAlt(_localctx, 82);
				{
				setState(686);
				match(T__125);
				setState(687);
				((AttributeHistogramConstraintContext)_localctx).args = attributeHistogramArgs();
				}
				break;
			case 83:
				_localctx = new PriceHistogramConstraintContext(_localctx);
				enterOuterAlt(_localctx, 83);
				{
				setState(688);
				match(T__126);
				setState(689);
				((PriceHistogramConstraintContext)_localctx).args = priceHistogramArgs();
				}
				break;
			case 84:
				_localctx = new HierarchyDistanceConstraintContext(_localctx);
				enterOuterAlt(_localctx, 84);
				{
				setState(690);
				match(T__127);
				setState(691);
				((HierarchyDistanceConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case 85:
				_localctx = new HierarchyLevelConstraintContext(_localctx);
				enterOuterAlt(_localctx, 85);
				{
				setState(692);
				match(T__128);
				setState(693);
				((HierarchyLevelConstraintContext)_localctx).args = valueArgs();
				}
				break;
			case 86:
				_localctx = new HierarchyNodeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 86);
				{
				setState(694);
				match(T__129);
				setState(695);
				((HierarchyNodeConstraintContext)_localctx).args = filterConstraintArgs();
				}
				break;
			case 87:
				_localctx = new HierarchyStopAtConstraintContext(_localctx);
				enterOuterAlt(_localctx, 87);
				{
				setState(696);
				match(T__130);
				setState(697);
				((HierarchyStopAtConstraintContext)_localctx).args = requireConstraintArgs();
				}
				break;
			case 88:
				_localctx = new HierarchyStatisticsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 88);
				{
				setState(698);
				match(T__131);
				setState(701);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,27,_ctx) ) {
				case 1:
					{
					setState(699);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(700);
					((HierarchyStatisticsConstraintContext)_localctx).args = hierarchyStatisticsArgs();
					}
					break;
				}
				}
				break;
			case 89:
				_localctx = new HierarchyFromRootConstraintContext(_localctx);
				enterOuterAlt(_localctx, 89);
				{
				setState(703);
				match(T__132);
				setState(704);
				((HierarchyFromRootConstraintContext)_localctx).args = hierarchyRequireConstraintArgs();
				}
				break;
			case 90:
				_localctx = new HierarchyFromNodeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 90);
				{
				setState(705);
				match(T__133);
				setState(706);
				((HierarchyFromNodeConstraintContext)_localctx).args = hierarchyFromNodeArgs();
				}
				break;
			case 91:
				_localctx = new HierarchyChildrenConstraintContext(_localctx);
				enterOuterAlt(_localctx, 91);
				{
				setState(707);
				match(T__134);
				setState(708);
				((HierarchyChildrenConstraintContext)_localctx).args = hierarchyRequireConstraintArgs();
				}
				break;
			case 92:
				_localctx = new EmptyHierarchySiblingsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 92);
				{
				setState(709);
				match(T__135);
				setState(710);
				emptyArgs();
				}
				break;
			case 93:
				_localctx = new BasicHierarchySiblingsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 93);
				{
				setState(711);
				match(T__135);
				setState(712);
				((BasicHierarchySiblingsConstraintContext)_localctx).args = requireConstraintListArgs();
				}
				break;
			case 94:
				_localctx = new FullHierarchySiblingsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 94);
				{
				setState(713);
				match(T__135);
				setState(714);
				((FullHierarchySiblingsConstraintContext)_localctx).args = hierarchyRequireConstraintArgs();
				}
				break;
			case 95:
				_localctx = new SpacingConstraintContext(_localctx);
				enterOuterAlt(_localctx, 95);
				{
				setState(715);
				match(T__136);
				setState(716);
				((SpacingConstraintContext)_localctx).args = spacingRequireConstraintArgs();
				}
				break;
			case 96:
				_localctx = new GapConstraintContext(_localctx);
				enterOuterAlt(_localctx, 96);
				{
				setState(717);
				match(T__137);
				setState(718);
				((GapConstraintContext)_localctx).args = gapRequireConstraintArgs();
				}
				break;
			case 97:
				_localctx = new HierarchyParentsConstraintContext(_localctx);
				enterOuterAlt(_localctx, 97);
				{
				setState(719);
				match(T__138);
				setState(720);
				((HierarchyParentsConstraintContext)_localctx).args = hierarchyRequireConstraintArgs();
				}
				break;
			case 98:
				_localctx = new BasicHierarchyOfSelfConstraintContext(_localctx);
				enterOuterAlt(_localctx, 98);
				{
				setState(721);
				match(T__139);
				setState(722);
				((BasicHierarchyOfSelfConstraintContext)_localctx).args = requireConstraintListArgs();
				}
				break;
			case 99:
				_localctx = new FullHierarchyOfSelfConstraintContext(_localctx);
				enterOuterAlt(_localctx, 99);
				{
				setState(723);
				match(T__139);
				setState(724);
				((FullHierarchyOfSelfConstraintContext)_localctx).args = fullHierarchyOfSelfArgs();
				}
				break;
			case 100:
				_localctx = new BasicHierarchyOfReferenceConstraintContext(_localctx);
				enterOuterAlt(_localctx, 100);
				{
				setState(725);
				match(T__140);
				setState(726);
				((BasicHierarchyOfReferenceConstraintContext)_localctx).args = basicHierarchyOfReferenceArgs();
				}
				break;
			case 101:
				_localctx = new BasicHierarchyOfReferenceWithBehaviourConstraintContext(_localctx);
				enterOuterAlt(_localctx, 101);
				{
				setState(727);
				match(T__140);
				setState(728);
				((BasicHierarchyOfReferenceWithBehaviourConstraintContext)_localctx).args = basicHierarchyOfReferenceWithBehaviourArgs();
				}
				break;
			case 102:
				_localctx = new FullHierarchyOfReferenceConstraintContext(_localctx);
				enterOuterAlt(_localctx, 102);
				{
				setState(729);
				match(T__140);
				setState(730);
				((FullHierarchyOfReferenceConstraintContext)_localctx).args = fullHierarchyOfReferenceArgs();
				}
				break;
			case 103:
				_localctx = new FullHierarchyOfReferenceWithBehaviourConstraintContext(_localctx);
				enterOuterAlt(_localctx, 103);
				{
				setState(731);
				match(T__140);
				setState(732);
				((FullHierarchyOfReferenceWithBehaviourConstraintContext)_localctx).args = fullHierarchyOfReferenceWithBehaviourArgs();
				}
				break;
			case 104:
				_localctx = new QueryTelemetryConstraintContext(_localctx);
				enterOuterAlt(_localctx, 104);
				{
				setState(733);
				match(T__141);
				setState(736);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,28,_ctx) ) {
				case 1:
					{
					setState(734);
					emptyArgs();
					}
					break;
				case 2:
					{
					setState(735);
					((QueryTelemetryConstraintContext)_localctx).args = valueArgs();
					}
					break;
				}
				}
				break;
			case 105:
				_localctx = new RequireInScopeConstraintContext(_localctx);
				enterOuterAlt(_localctx, 105);
				{
				setState(738);
				match(T__56);
				setState(739);
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(742);
			((HeadConstraintListContext)_localctx).headConstraint = headConstraint();
			((HeadConstraintListContext)_localctx).constraints.add(((HeadConstraintListContext)_localctx).headConstraint);
			setState(747);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(743);
				match(ARGS_DELIMITER);
				setState(744);
				((HeadConstraintListContext)_localctx).headConstraint = headConstraint();
				((HeadConstraintListContext)_localctx).constraints.add(((HeadConstraintListContext)_localctx).headConstraint);
				}
				}
				setState(749);
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(750);
			((FilterConstraintListContext)_localctx).filterConstraint = filterConstraint();
			((FilterConstraintListContext)_localctx).constraints.add(((FilterConstraintListContext)_localctx).filterConstraint);
			setState(755);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(751);
				match(ARGS_DELIMITER);
				setState(752);
				((FilterConstraintListContext)_localctx).filterConstraint = filterConstraint();
				((FilterConstraintListContext)_localctx).constraints.add(((FilterConstraintListContext)_localctx).filterConstraint);
				}
				}
				setState(757);
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(758);
			((OrderConstraintListContext)_localctx).orderConstraint = orderConstraint();
			((OrderConstraintListContext)_localctx).constraints.add(((OrderConstraintListContext)_localctx).orderConstraint);
			setState(763);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(759);
				match(ARGS_DELIMITER);
				setState(760);
				((OrderConstraintListContext)_localctx).orderConstraint = orderConstraint();
				((OrderConstraintListContext)_localctx).constraints.add(((OrderConstraintListContext)_localctx).orderConstraint);
				}
				}
				setState(765);
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(766);
			((RequireConstraintListContext)_localctx).requireConstraint = requireConstraint();
			((RequireConstraintListContext)_localctx).constraints.add(((RequireConstraintListContext)_localctx).requireConstraint);
			setState(771);
			_errHandler.sync(this);
			_la = _input.LA(1);
			while (_la==ARGS_DELIMITER) {
				{
				{
				setState(767);
				match(ARGS_DELIMITER);
				setState(768);
				((RequireConstraintListContext)_localctx).requireConstraint = requireConstraint();
				((RequireConstraintListContext)_localctx).constraints.add(((RequireConstraintListContext)_localctx).requireConstraint);
				}
				}
				setState(773);
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(774);
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(777);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (_la==ARGS_DELIMITER) {
				{
				setState(776);
				match(ARGS_DELIMITER);
				}
			}

			setState(779);
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(781);
			argsOpening();
			setState(782);
			((ConstraintListArgsContext)_localctx).constraint = constraint();
			((ConstraintListArgsContext)_localctx).constraints.add(((ConstraintListArgsContext)_localctx).constraint);
			setState(787);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,35,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(783);
					match(ARGS_DELIMITER);
					setState(784);
					((ConstraintListArgsContext)_localctx).constraint = constraint();
					((ConstraintListArgsContext)_localctx).constraints.add(((ConstraintListArgsContext)_localctx).constraint);
					}
					} 
				}
				setState(789);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,35,_ctx);
			}
			setState(790);
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(792);
			argsOpening();
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(795);
			argsOpening();
			setState(796);
			((HeadConstraintListArgsContext)_localctx).headConstraint = headConstraint();
			((HeadConstraintListArgsContext)_localctx).constraints.add(((HeadConstraintListArgsContext)_localctx).headConstraint);
			setState(801);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,36,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(797);
					match(ARGS_DELIMITER);
					setState(798);
					((HeadConstraintListArgsContext)_localctx).headConstraint = headConstraint();
					((HeadConstraintListArgsContext)_localctx).constraints.add(((HeadConstraintListArgsContext)_localctx).headConstraint);
					}
					} 
				}
				setState(803);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,36,_ctx);
			}
			setState(804);
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(806);
			argsOpening();
			setState(807);
			((FilterConstraintListArgsContext)_localctx).filterConstraint = filterConstraint();
			((FilterConstraintListArgsContext)_localctx).constraints.add(((FilterConstraintListArgsContext)_localctx).filterConstraint);
			setState(812);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,37,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(808);
					match(ARGS_DELIMITER);
					setState(809);
					((FilterConstraintListArgsContext)_localctx).filterConstraint = filterConstraint();
					((FilterConstraintListArgsContext)_localctx).constraints.add(((FilterConstraintListArgsContext)_localctx).filterConstraint);
					}
					} 
				}
				setState(814);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,37,_ctx);
			}
			setState(815);
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(817);
			argsOpening();
			setState(818);
			((FilterConstraintArgsContext)_localctx).filter = filterConstraint();
			setState(819);
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(821);
			argsOpening();
			setState(836);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,40,_ctx) ) {
			case 1:
				{
				{
				setState(822);
				((TraverseOrderConstraintListArgsContext)_localctx).traversalMode = valueToken();
				}
				}
				break;
			case 2:
				{
				{
				setState(826);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (((((_la - 143)) & ~0x3f) == 0 && ((1L << (_la - 143)) & 32767L) != 0)) {
					{
					setState(823);
					((TraverseOrderConstraintListArgsContext)_localctx).traversalMode = valueToken();
					setState(824);
					match(ARGS_DELIMITER);
					}
				}

				setState(828);
				((TraverseOrderConstraintListArgsContext)_localctx).orderConstraint = orderConstraint();
				((TraverseOrderConstraintListArgsContext)_localctx).constraints.add(((TraverseOrderConstraintListArgsContext)_localctx).orderConstraint);
				setState(833);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,39,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(829);
						match(ARGS_DELIMITER);
						setState(830);
						((TraverseOrderConstraintListArgsContext)_localctx).orderConstraint = orderConstraint();
						((TraverseOrderConstraintListArgsContext)_localctx).constraints.add(((TraverseOrderConstraintListArgsContext)_localctx).orderConstraint);
						}
						} 
					}
					setState(835);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,39,_ctx);
				}
				}
				}
				break;
			}
			setState(838);
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(840);
			argsOpening();
			setState(841);
			((OrderConstraintListArgsContext)_localctx).orderConstraint = orderConstraint();
			((OrderConstraintListArgsContext)_localctx).constraints.add(((OrderConstraintListArgsContext)_localctx).orderConstraint);
			setState(846);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,41,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(842);
					match(ARGS_DELIMITER);
					setState(843);
					((OrderConstraintListArgsContext)_localctx).orderConstraint = orderConstraint();
					((OrderConstraintListArgsContext)_localctx).constraints.add(((OrderConstraintListArgsContext)_localctx).orderConstraint);
					}
					} 
				}
				setState(848);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,41,_ctx);
			}
			setState(849);
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(851);
			argsOpening();
			setState(852);
			((RequireConstraintArgsContext)_localctx).requirement = requireConstraint();
			setState(853);
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(855);
			argsOpening();
			setState(856);
			((RequireConstraintListArgsContext)_localctx).requireConstraint = requireConstraint();
			((RequireConstraintListArgsContext)_localctx).requirements.add(((RequireConstraintListArgsContext)_localctx).requireConstraint);
			setState(861);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,42,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(857);
					match(ARGS_DELIMITER);
					setState(858);
					((RequireConstraintListArgsContext)_localctx).requireConstraint = requireConstraint();
					((RequireConstraintListArgsContext)_localctx).requirements.add(((RequireConstraintListArgsContext)_localctx).requireConstraint);
					}
					} 
				}
				setState(863);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,42,_ctx);
			}
			setState(864);
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(866);
			argsOpening();
			setState(867);
			((ClassifierArgsContext)_localctx).classifier = valueToken();
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(870);
			argsOpening();
			setState(871);
			((ClassifierWithValueArgsContext)_localctx).classifier = valueToken();
			setState(872);
			match(ARGS_DELIMITER);
			setState(873);
			((ClassifierWithValueArgsContext)_localctx).value = valueToken();
			setState(874);
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(876);
			argsOpening();
			setState(877);
			((ClassifierWithOptionalValueArgsContext)_localctx).classifier = valueToken();
			setState(880);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,43,_ctx) ) {
			case 1:
				{
				setState(878);
				match(ARGS_DELIMITER);
				setState(879);
				((ClassifierWithOptionalValueArgsContext)_localctx).value = valueToken();
				}
				break;
			}
			setState(882);
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(884);
			argsOpening();
			setState(885);
			((ClassifierWithValueListArgsContext)_localctx).classifier = valueToken();
			setState(886);
			match(ARGS_DELIMITER);
			setState(887);
			((ClassifierWithValueListArgsContext)_localctx).values = variadicValueTokens();
			setState(888);
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(890);
			argsOpening();
			setState(891);
			((ClassifierWithOptionalValueListArgsContext)_localctx).classifier = valueToken();
			setState(894);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,44,_ctx) ) {
			case 1:
				{
				setState(892);
				match(ARGS_DELIMITER);
				setState(893);
				((ClassifierWithOptionalValueListArgsContext)_localctx).values = variadicValueTokens();
				}
				break;
			}
			setState(896);
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(898);
			argsOpening();
			setState(899);
			((ClassifierWithBetweenValuesArgsContext)_localctx).classifier = valueToken();
			setState(900);
			match(ARGS_DELIMITER);
			setState(901);
			((ClassifierWithBetweenValuesArgsContext)_localctx).valueFrom = valueToken();
			setState(902);
			match(ARGS_DELIMITER);
			setState(903);
			((ClassifierWithBetweenValuesArgsContext)_localctx).valueTo = valueToken();
			setState(904);
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(906);
			argsOpening();
			setState(907);
			((ValueArgsContext)_localctx).value = valueToken();
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(910);
			argsOpening();
			setState(911);
			((ValueListArgsContext)_localctx).values = variadicValueTokens();
			setState(912);
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(914);
			argsOpening();
			setState(915);
			((BetweenValuesArgsContext)_localctx).valueFrom = valueToken();
			setState(916);
			match(ARGS_DELIMITER);
			setState(917);
			((BetweenValuesArgsContext)_localctx).valueTo = valueToken();
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(920);
			argsOpening();
			setState(921);
			((ClassifierListArgsContext)_localctx).classifiers = variadicValueTokens();
			setState(922);
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(924);
			argsOpening();
			setState(925);
			((ClassifierWithFilterConstraintArgsContext)_localctx).classifier = valueToken();
			setState(926);
			match(ARGS_DELIMITER);
			setState(927);
			((ClassifierWithFilterConstraintArgsContext)_localctx).filter = filterConstraint();
			setState(928);
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

	@SuppressWarnings("CheckReturnValue")
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
			setState(930);
			argsOpening();
			setState(931);
			((ClassifierWithTwoFilterConstraintArgsContext)_localctx).classifier = valueToken();
			setState(932);
			match(ARGS_DELIMITER);
			setState(933);
			((ClassifierWithTwoFilterConstraintArgsContext)_localctx).filter1 = filterConstraint();
			setState(936);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,45,_ctx) ) {
			case 1:
				{
				setState(934);
				match(ARGS_DELIMITER);
				setState(935);
				((ClassifierWithTwoFilterConstraintArgsContext)_localctx).filter2 = filterConstraint();
				}
				break;
			}
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

	@SuppressWarnings("CheckReturnValue")
	public static class ClassifierWithHistogramHavingArgsContext extends ParserRuleContext {
		public ValueTokenContext classifier;
		public ValueTokenContext histogramName;
		public ValueTokenContext valueFrom;
		public ValueTokenContext valueTo;
		public FilterConstraintContext groupSelector;
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
		public ClassifierWithHistogramHavingArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_classifierWithHistogramHavingArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterClassifierWithHistogramHavingArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitClassifierWithHistogramHavingArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitClassifierWithHistogramHavingArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ClassifierWithHistogramHavingArgsContext classifierWithHistogramHavingArgs() throws RecognitionException {
		ClassifierWithHistogramHavingArgsContext _localctx = new ClassifierWithHistogramHavingArgsContext(_ctx, getState());
		enterRule(_localctx, 78, RULE_classifierWithHistogramHavingArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(940);
			argsOpening();
			setState(941);
			((ClassifierWithHistogramHavingArgsContext)_localctx).classifier = valueToken();
			setState(944);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,46,_ctx) ) {
			case 1:
				{
				setState(942);
				match(ARGS_DELIMITER);
				setState(943);
				((ClassifierWithHistogramHavingArgsContext)_localctx).histogramName = valueToken();
				}
				break;
			}
			setState(951);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,47,_ctx) ) {
			case 1:
				{
				setState(946);
				match(ARGS_DELIMITER);
				setState(947);
				((ClassifierWithHistogramHavingArgsContext)_localctx).valueFrom = valueToken();
				setState(948);
				match(ARGS_DELIMITER);
				setState(949);
				((ClassifierWithHistogramHavingArgsContext)_localctx).valueTo = valueToken();
				}
				break;
			}
			setState(955);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,48,_ctx) ) {
			case 1:
				{
				setState(953);
				match(ARGS_DELIMITER);
				setState(954);
				((ClassifierWithHistogramHavingArgsContext)_localctx).groupSelector = filterConstraint();
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 80, RULE_facetGroupRelationArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(959);
			argsOpening();
			setState(960);
			((FacetGroupRelationArgsContext)_localctx).classifier = valueToken();
			setState(963);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,49,_ctx) ) {
			case 1:
				{
				setState(961);
				match(ARGS_DELIMITER);
				setState(962);
				((FacetGroupRelationArgsContext)_localctx).facetGroupRelationLevel = valueToken();
				}
				break;
			}
			setState(967);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,50,_ctx) ) {
			case 1:
				{
				setState(965);
				match(ARGS_DELIMITER);
				setState(966);
				((FacetGroupRelationArgsContext)_localctx).filter = filterConstraint();
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 82, RULE_facetCalculationRulesArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(971);
			argsOpening();
			setState(972);
			((FacetCalculationRulesArgsContext)_localctx).facetsWithSameGroup = valueToken();
			setState(973);
			match(ARGS_DELIMITER);
			setState(974);
			((FacetCalculationRulesArgsContext)_localctx).facetsWithDifferentGroups = valueToken();
			setState(975);
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

	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceWeightArgsContext extends ParserRuleContext {
		public ValueTokenContext classifier;
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
		public ReferenceWeightArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_referenceWeightArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceWeightArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceWeightArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceWeightArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ReferenceWeightArgsContext referenceWeightArgs() throws RecognitionException {
		ReferenceWeightArgsContext _localctx = new ReferenceWeightArgsContext(_ctx, getState());
		enterRule(_localctx, 84, RULE_referenceWeightArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(977);
			argsOpening();
			setState(978);
			((ReferenceWeightArgsContext)_localctx).classifier = valueToken();
			setState(983);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,51,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(979);
					match(ARGS_DELIMITER);
					setState(980);
					((ReferenceWeightArgsContext)_localctx).orderConstraint = orderConstraint();
					((ReferenceWeightArgsContext)_localctx).constraints.add(((ReferenceWeightArgsContext)_localctx).orderConstraint);
					}
					} 
				}
				setState(985);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,51,_ctx);
			}
			setState(986);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 86, RULE_classifierWithOrderConstraintListArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(988);
			argsOpening();
			setState(989);
			((ClassifierWithOrderConstraintListArgsContext)_localctx).classifier = valueToken();
			setState(992); 
			_errHandler.sync(this);
			_alt = 1;
			do {
				switch (_alt) {
				case 1:
					{
					{
					setState(990);
					match(ARGS_DELIMITER);
					setState(991);
					((ClassifierWithOrderConstraintListArgsContext)_localctx).orderConstraint = orderConstraint();
					((ClassifierWithOrderConstraintListArgsContext)_localctx).constrains.add(((ClassifierWithOrderConstraintListArgsContext)_localctx).orderConstraint);
					}
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				setState(994); 
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,52,_ctx);
			} while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER );
			setState(996);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 88, RULE_hierarchyWithinConstraintArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(998);
			argsOpening();
			setState(999);
			((HierarchyWithinConstraintArgsContext)_localctx).classifier = valueToken();
			setState(1000);
			match(ARGS_DELIMITER);
			setState(1001);
			((HierarchyWithinConstraintArgsContext)_localctx).ofParent = filterConstraint();
			setState(1006);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,53,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(1002);
					match(ARGS_DELIMITER);
					setState(1003);
					((HierarchyWithinConstraintArgsContext)_localctx).filterConstraint = filterConstraint();
					((HierarchyWithinConstraintArgsContext)_localctx).constrains.add(((HierarchyWithinConstraintArgsContext)_localctx).filterConstraint);
					}
					} 
				}
				setState(1008);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,53,_ctx);
			}
			setState(1009);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 90, RULE_hierarchyWithinSelfConstraintArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1011);
			argsOpening();
			setState(1012);
			((HierarchyWithinSelfConstraintArgsContext)_localctx).ofParent = filterConstraint();
			setState(1017);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,54,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(1013);
					match(ARGS_DELIMITER);
					setState(1014);
					((HierarchyWithinSelfConstraintArgsContext)_localctx).filterConstraint = filterConstraint();
					((HierarchyWithinSelfConstraintArgsContext)_localctx).constrains.add(((HierarchyWithinSelfConstraintArgsContext)_localctx).filterConstraint);
					}
					} 
				}
				setState(1019);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,54,_ctx);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 92, RULE_hierarchyWithinRootConstraintArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1022);
			argsOpening();
			setState(1032);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,56,_ctx) ) {
			case 1:
				{
				setState(1023);
				((HierarchyWithinRootConstraintArgsContext)_localctx).classifier = valueToken();
				}
				break;
			case 2:
				{
				{
				setState(1024);
				((HierarchyWithinRootConstraintArgsContext)_localctx).classifier = valueToken();
				setState(1029);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,55,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(1025);
						match(ARGS_DELIMITER);
						setState(1026);
						((HierarchyWithinRootConstraintArgsContext)_localctx).filterConstraint = filterConstraint();
						((HierarchyWithinRootConstraintArgsContext)_localctx).constrains.add(((HierarchyWithinRootConstraintArgsContext)_localctx).filterConstraint);
						}
						} 
					}
					setState(1031);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,55,_ctx);
				}
				}
				}
				break;
			}
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 94, RULE_hierarchyWithinRootSelfConstraintArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1036);
			argsOpening();
			setState(1037);
			((HierarchyWithinRootSelfConstraintArgsContext)_localctx).filterConstraint = filterConstraint();
			((HierarchyWithinRootSelfConstraintArgsContext)_localctx).constrains.add(((HierarchyWithinRootSelfConstraintArgsContext)_localctx).filterConstraint);
			setState(1042);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,57,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(1038);
					match(ARGS_DELIMITER);
					setState(1039);
					((HierarchyWithinRootSelfConstraintArgsContext)_localctx).filterConstraint = filterConstraint();
					((HierarchyWithinRootSelfConstraintArgsContext)_localctx).constrains.add(((HierarchyWithinRootSelfConstraintArgsContext)_localctx).filterConstraint);
					}
					} 
				}
				setState(1044);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,57,_ctx);
			}
			setState(1045);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 96, RULE_attributeSetExactArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1047);
			argsOpening();
			setState(1048);
			((AttributeSetExactArgsContext)_localctx).attributeName = valueToken();
			setState(1049);
			match(ARGS_DELIMITER);
			setState(1050);
			((AttributeSetExactArgsContext)_localctx).attributeValues = variadicValueTokens();
			setState(1051);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 98, RULE_pageConstraintArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1053);
			argsOpening();
			setState(1054);
			((PageConstraintArgsContext)_localctx).pageNumber = valueToken();
			setState(1055);
			match(ARGS_DELIMITER);
			setState(1056);
			((PageConstraintArgsContext)_localctx).pageSize = valueToken();
			setState(1059);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,58,_ctx) ) {
			case 1:
				{
				setState(1057);
				match(ARGS_DELIMITER);
				setState(1058);
				((PageConstraintArgsContext)_localctx).constrain = requireConstraint();
				}
				break;
			}
			setState(1061);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 100, RULE_stripConstraintArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1063);
			argsOpening();
			setState(1064);
			((StripConstraintArgsContext)_localctx).offset = valueToken();
			setState(1065);
			match(ARGS_DELIMITER);
			setState(1066);
			((StripConstraintArgsContext)_localctx).limit = valueToken();
			setState(1067);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 102, RULE_priceContentArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1069);
			argsOpening();
			setState(1070);
			((PriceContentArgsContext)_localctx).contentMode = valueToken();
			setState(1073);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,59,_ctx) ) {
			case 1:
				{
				setState(1071);
				match(ARGS_DELIMITER);
				setState(1072);
				((PriceContentArgsContext)_localctx).priceLists = variadicValueTokens();
				}
				break;
			}
			setState(1075);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 104, RULE_singleRefReferenceContent1Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1077);
			argsOpening();
			setState(1081);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,60,_ctx) ) {
			case 1:
				{
				setState(1078);
				((SingleRefReferenceContent1ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1079);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1083);
			((SingleRefReferenceContent1ArgsContext)_localctx).classifier = valueToken();
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 106, RULE_singleRefReferenceContent2Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1086);
			argsOpening();
			setState(1090);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,61,_ctx) ) {
			case 1:
				{
				setState(1087);
				((SingleRefReferenceContent2ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1088);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1092);
			((SingleRefReferenceContent2ArgsContext)_localctx).classifier = valueToken();
			setState(1093);
			match(ARGS_DELIMITER);
			setState(1094);
			((SingleRefReferenceContent2ArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(1095);
			match(ARGS_DELIMITER);
			setState(1096);
			((SingleRefReferenceContent2ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(1099);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,62,_ctx) ) {
			case 1:
				{
				setState(1097);
				match(ARGS_DELIMITER);
				setState(1098);
				((SingleRefReferenceContent2ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1101);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 108, RULE_singleRefReferenceContent3Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1103);
			argsOpening();
			setState(1107);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,63,_ctx) ) {
			case 1:
				{
				setState(1104);
				((SingleRefReferenceContent3ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1105);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1109);
			((SingleRefReferenceContent3ArgsContext)_localctx).classifier = valueToken();
			setState(1110);
			match(ARGS_DELIMITER);
			setState(1111);
			((SingleRefReferenceContent3ArgsContext)_localctx).filterBy = filterConstraint();
			setState(1114);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,64,_ctx) ) {
			case 1:
				{
				setState(1112);
				match(ARGS_DELIMITER);
				setState(1113);
				((SingleRefReferenceContent3ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1116);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 110, RULE_singleRefReferenceContent4Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1118);
			argsOpening();
			setState(1122);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,65,_ctx) ) {
			case 1:
				{
				setState(1119);
				((SingleRefReferenceContent4ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1120);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1124);
			((SingleRefReferenceContent4ArgsContext)_localctx).classifier = valueToken();
			setState(1125);
			match(ARGS_DELIMITER);
			setState(1126);
			((SingleRefReferenceContent4ArgsContext)_localctx).filterBy = filterConstraint();
			setState(1127);
			match(ARGS_DELIMITER);
			setState(1128);
			((SingleRefReferenceContent4ArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(1129);
			match(ARGS_DELIMITER);
			setState(1130);
			((SingleRefReferenceContent4ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(1133);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,66,_ctx) ) {
			case 1:
				{
				setState(1131);
				match(ARGS_DELIMITER);
				setState(1132);
				((SingleRefReferenceContent4ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 112, RULE_singleRefReferenceContent5Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1137);
			argsOpening();
			setState(1141);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,67,_ctx) ) {
			case 1:
				{
				setState(1138);
				((SingleRefReferenceContent5ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1139);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1143);
			((SingleRefReferenceContent5ArgsContext)_localctx).classifier = valueToken();
			setState(1144);
			match(ARGS_DELIMITER);
			setState(1145);
			((SingleRefReferenceContent5ArgsContext)_localctx).orderBy = orderConstraint();
			setState(1148);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,68,_ctx) ) {
			case 1:
				{
				setState(1146);
				match(ARGS_DELIMITER);
				setState(1147);
				((SingleRefReferenceContent5ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 114, RULE_singleRefReferenceContent6Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1152);
			argsOpening();
			setState(1156);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,69,_ctx) ) {
			case 1:
				{
				setState(1153);
				((SingleRefReferenceContent6ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1154);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1158);
			((SingleRefReferenceContent6ArgsContext)_localctx).classifier = valueToken();
			setState(1159);
			match(ARGS_DELIMITER);
			setState(1160);
			((SingleRefReferenceContent6ArgsContext)_localctx).orderBy = orderConstraint();
			setState(1161);
			match(ARGS_DELIMITER);
			setState(1162);
			((SingleRefReferenceContent6ArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(1163);
			match(ARGS_DELIMITER);
			setState(1164);
			((SingleRefReferenceContent6ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(1167);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,70,_ctx) ) {
			case 1:
				{
				setState(1165);
				match(ARGS_DELIMITER);
				setState(1166);
				((SingleRefReferenceContent6ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1169);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 116, RULE_singleRefReferenceContent7Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1171);
			argsOpening();
			setState(1175);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,71,_ctx) ) {
			case 1:
				{
				setState(1172);
				((SingleRefReferenceContent7ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1173);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1177);
			((SingleRefReferenceContent7ArgsContext)_localctx).classifier = valueToken();
			setState(1178);
			match(ARGS_DELIMITER);
			setState(1179);
			((SingleRefReferenceContent7ArgsContext)_localctx).filterBy = filterConstraint();
			setState(1180);
			match(ARGS_DELIMITER);
			setState(1181);
			((SingleRefReferenceContent7ArgsContext)_localctx).orderBy = orderConstraint();
			setState(1184);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,72,_ctx) ) {
			case 1:
				{
				setState(1182);
				match(ARGS_DELIMITER);
				setState(1183);
				((SingleRefReferenceContent7ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1186);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 118, RULE_singleRefReferenceContent8Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1188);
			argsOpening();
			setState(1192);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,73,_ctx) ) {
			case 1:
				{
				setState(1189);
				((SingleRefReferenceContent8ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1190);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1194);
			((SingleRefReferenceContent8ArgsContext)_localctx).classifier = valueToken();
			setState(1195);
			match(ARGS_DELIMITER);
			setState(1196);
			((SingleRefReferenceContent8ArgsContext)_localctx).filterBy = filterConstraint();
			setState(1197);
			match(ARGS_DELIMITER);
			setState(1198);
			((SingleRefReferenceContent8ArgsContext)_localctx).orderBy = orderConstraint();
			setState(1199);
			match(ARGS_DELIMITER);
			setState(1200);
			((SingleRefReferenceContent8ArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(1201);
			match(ARGS_DELIMITER);
			setState(1202);
			((SingleRefReferenceContent8ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(1205);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,74,_ctx) ) {
			case 1:
				{
				setState(1203);
				match(ARGS_DELIMITER);
				setState(1204);
				((SingleRefReferenceContent8ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1207);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 120, RULE_singleRefReferenceContentWithAttributes0Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1209);
			argsOpening();
			setState(1213);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,75,_ctx) ) {
			case 1:
				{
				setState(1210);
				((SingleRefReferenceContentWithAttributes0ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1211);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1215);
			((SingleRefReferenceContentWithAttributes0ArgsContext)_localctx).classifier = valueToken();
			setState(1216);
			match(ARGS_DELIMITER);
			setState(1217);
			((SingleRefReferenceContentWithAttributes0ArgsContext)_localctx).requirement = requireConstraint();
			setState(1218);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 122, RULE_singleRefReferenceContentWithAttributes1Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1220);
			argsOpening();
			setState(1224);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,76,_ctx) ) {
			case 1:
				{
				setState(1221);
				((SingleRefReferenceContentWithAttributes1ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1222);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1226);
			((SingleRefReferenceContentWithAttributes1ArgsContext)_localctx).classifier = valueToken();
			setState(1227);
			match(ARGS_DELIMITER);
			setState(1228);
			((SingleRefReferenceContentWithAttributes1ArgsContext)_localctx).requirement1 = requireConstraint();
			setState(1229);
			match(ARGS_DELIMITER);
			setState(1230);
			((SingleRefReferenceContentWithAttributes1ArgsContext)_localctx).requirement2 = requireConstraint();
			setState(1231);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 124, RULE_singleRefReferenceContentWithAttributes2Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1233);
			argsOpening();
			setState(1237);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,77,_ctx) ) {
			case 1:
				{
				setState(1234);
				((SingleRefReferenceContentWithAttributes2ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1235);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1239);
			((SingleRefReferenceContentWithAttributes2ArgsContext)_localctx).classifier = valueToken();
			setState(1240);
			match(ARGS_DELIMITER);
			setState(1241);
			((SingleRefReferenceContentWithAttributes2ArgsContext)_localctx).attributeContent = requireConstraint();
			setState(1242);
			match(ARGS_DELIMITER);
			setState(1243);
			((SingleRefReferenceContentWithAttributes2ArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(1244);
			match(ARGS_DELIMITER);
			setState(1245);
			((SingleRefReferenceContentWithAttributes2ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(1248);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,78,_ctx) ) {
			case 1:
				{
				setState(1246);
				match(ARGS_DELIMITER);
				setState(1247);
				((SingleRefReferenceContentWithAttributes2ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1250);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 126, RULE_singleRefReferenceContentWithAttributes3Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1252);
			argsOpening();
			setState(1256);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,79,_ctx) ) {
			case 1:
				{
				setState(1253);
				((SingleRefReferenceContentWithAttributes3ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1254);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1258);
			((SingleRefReferenceContentWithAttributes3ArgsContext)_localctx).classifier = valueToken();
			setState(1259);
			match(ARGS_DELIMITER);
			setState(1260);
			((SingleRefReferenceContentWithAttributes3ArgsContext)_localctx).filterBy = filterConstraint();
			setState(1261);
			match(ARGS_DELIMITER);
			setState(1262);
			((SingleRefReferenceContentWithAttributes3ArgsContext)_localctx).requirement1 = requireConstraint();
			setState(1263);
			match(ARGS_DELIMITER);
			setState(1264);
			((SingleRefReferenceContentWithAttributes3ArgsContext)_localctx).requirement2 = requireConstraint();
			setState(1265);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 128, RULE_singleRefReferenceContentWithAttributes4Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1267);
			argsOpening();
			setState(1271);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,80,_ctx) ) {
			case 1:
				{
				setState(1268);
				((SingleRefReferenceContentWithAttributes4ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1269);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1273);
			((SingleRefReferenceContentWithAttributes4ArgsContext)_localctx).classifier = valueToken();
			setState(1274);
			match(ARGS_DELIMITER);
			setState(1275);
			((SingleRefReferenceContentWithAttributes4ArgsContext)_localctx).filterBy = filterConstraint();
			setState(1276);
			match(ARGS_DELIMITER);
			setState(1277);
			((SingleRefReferenceContentWithAttributes4ArgsContext)_localctx).attributeContent = requireConstraint();
			setState(1278);
			match(ARGS_DELIMITER);
			setState(1279);
			((SingleRefReferenceContentWithAttributes4ArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(1280);
			match(ARGS_DELIMITER);
			setState(1281);
			((SingleRefReferenceContentWithAttributes4ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(1284);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,81,_ctx) ) {
			case 1:
				{
				setState(1282);
				match(ARGS_DELIMITER);
				setState(1283);
				((SingleRefReferenceContentWithAttributes4ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1286);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 130, RULE_singleRefReferenceContentWithAttributes5Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1288);
			argsOpening();
			setState(1292);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,82,_ctx) ) {
			case 1:
				{
				setState(1289);
				((SingleRefReferenceContentWithAttributes5ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1290);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1294);
			((SingleRefReferenceContentWithAttributes5ArgsContext)_localctx).classifier = valueToken();
			setState(1295);
			match(ARGS_DELIMITER);
			setState(1296);
			((SingleRefReferenceContentWithAttributes5ArgsContext)_localctx).orderBy = orderConstraint();
			setState(1297);
			match(ARGS_DELIMITER);
			setState(1298);
			((SingleRefReferenceContentWithAttributes5ArgsContext)_localctx).requirement1 = requireConstraint();
			setState(1299);
			match(ARGS_DELIMITER);
			setState(1300);
			((SingleRefReferenceContentWithAttributes5ArgsContext)_localctx).requirement2 = requireConstraint();
			setState(1301);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 132, RULE_singleRefReferenceContentWithAttributes6Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1303);
			argsOpening();
			setState(1307);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,83,_ctx) ) {
			case 1:
				{
				setState(1304);
				((SingleRefReferenceContentWithAttributes6ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1305);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1309);
			((SingleRefReferenceContentWithAttributes6ArgsContext)_localctx).classifier = valueToken();
			setState(1310);
			match(ARGS_DELIMITER);
			setState(1311);
			((SingleRefReferenceContentWithAttributes6ArgsContext)_localctx).orderBy = orderConstraint();
			setState(1312);
			match(ARGS_DELIMITER);
			setState(1313);
			((SingleRefReferenceContentWithAttributes6ArgsContext)_localctx).attributeContent = requireConstraint();
			setState(1314);
			match(ARGS_DELIMITER);
			setState(1315);
			((SingleRefReferenceContentWithAttributes6ArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(1316);
			match(ARGS_DELIMITER);
			setState(1317);
			((SingleRefReferenceContentWithAttributes6ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(1320);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,84,_ctx) ) {
			case 1:
				{
				setState(1318);
				match(ARGS_DELIMITER);
				setState(1319);
				((SingleRefReferenceContentWithAttributes6ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1322);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 134, RULE_singleRefReferenceContentWithAttributes7Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1324);
			argsOpening();
			setState(1328);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,85,_ctx) ) {
			case 1:
				{
				setState(1325);
				((SingleRefReferenceContentWithAttributes7ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1326);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1330);
			((SingleRefReferenceContentWithAttributes7ArgsContext)_localctx).classifier = valueToken();
			setState(1331);
			match(ARGS_DELIMITER);
			setState(1332);
			((SingleRefReferenceContentWithAttributes7ArgsContext)_localctx).filterBy = filterConstraint();
			setState(1333);
			match(ARGS_DELIMITER);
			setState(1334);
			((SingleRefReferenceContentWithAttributes7ArgsContext)_localctx).orderBy = orderConstraint();
			setState(1335);
			match(ARGS_DELIMITER);
			setState(1336);
			((SingleRefReferenceContentWithAttributes7ArgsContext)_localctx).requirement1 = requireConstraint();
			setState(1337);
			match(ARGS_DELIMITER);
			setState(1338);
			((SingleRefReferenceContentWithAttributes7ArgsContext)_localctx).requirement2 = requireConstraint();
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 136, RULE_singleRefReferenceContentWithAttributes8Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1341);
			argsOpening();
			setState(1345);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,86,_ctx) ) {
			case 1:
				{
				setState(1342);
				((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1343);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1347);
			((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).classifier = valueToken();
			setState(1348);
			match(ARGS_DELIMITER);
			setState(1349);
			((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).filterBy = filterConstraint();
			setState(1350);
			match(ARGS_DELIMITER);
			setState(1351);
			((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).orderBy = orderConstraint();
			setState(1352);
			match(ARGS_DELIMITER);
			setState(1353);
			((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).attributeContent = requireConstraint();
			setState(1354);
			match(ARGS_DELIMITER);
			setState(1355);
			((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(1356);
			match(ARGS_DELIMITER);
			setState(1357);
			((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(1360);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,87,_ctx) ) {
			case 1:
				{
				setState(1358);
				match(ARGS_DELIMITER);
				setState(1359);
				((SingleRefReferenceContentWithAttributes8ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1362);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 138, RULE_multipleRefsReferenceContentArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1364);
			argsOpening();
			setState(1391);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,91,_ctx) ) {
			case 1:
				{
				{
				setState(1368);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,88,_ctx) ) {
				case 1:
					{
					setState(1365);
					((MultipleRefsReferenceContentArgsContext)_localctx).managedReferencesBehaviour = valueToken();
					setState(1366);
					match(ARGS_DELIMITER);
					}
					break;
				}
				setState(1370);
				((MultipleRefsReferenceContentArgsContext)_localctx).classifiers = variadicValueTokens();
				}
				}
				break;
			case 2:
				{
				{
				setState(1374);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,89,_ctx) ) {
				case 1:
					{
					setState(1371);
					((MultipleRefsReferenceContentArgsContext)_localctx).managedReferencesBehaviour = valueToken();
					setState(1372);
					match(ARGS_DELIMITER);
					}
					break;
				}
				setState(1376);
				((MultipleRefsReferenceContentArgsContext)_localctx).classifiers = variadicValueTokens();
				setState(1377);
				match(ARGS_DELIMITER);
				setState(1378);
				((MultipleRefsReferenceContentArgsContext)_localctx).requirement = requireConstraint();
				}
				}
				break;
			case 3:
				{
				{
				setState(1383);
				_errHandler.sync(this);
				switch ( getInterpreter().adaptivePredict(_input,90,_ctx) ) {
				case 1:
					{
					setState(1380);
					((MultipleRefsReferenceContentArgsContext)_localctx).managedReferencesBehaviour = valueToken();
					setState(1381);
					match(ARGS_DELIMITER);
					}
					break;
				}
				setState(1385);
				((MultipleRefsReferenceContentArgsContext)_localctx).classifiers = variadicValueTokens();
				setState(1386);
				match(ARGS_DELIMITER);
				setState(1387);
				((MultipleRefsReferenceContentArgsContext)_localctx).entityRequirement = requireConstraint();
				setState(1388);
				match(ARGS_DELIMITER);
				setState(1389);
				((MultipleRefsReferenceContentArgsContext)_localctx).groupEntityRequirement = requireConstraint();
				}
				}
				break;
			}
			setState(1393);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 140, RULE_allRefsReferenceContentArgs);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1395);
			argsOpening();
			setState(1412);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,94,_ctx) ) {
			case 1:
				{
				{
				setState(1396);
				((AllRefsReferenceContentArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				}
				}
				break;
			case 2:
				{
				{
				setState(1400);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (((((_la - 143)) & ~0x3f) == 0 && ((1L << (_la - 143)) & 32767L) != 0)) {
					{
					setState(1397);
					((AllRefsReferenceContentArgsContext)_localctx).managedReferencesBehaviour = valueToken();
					setState(1398);
					match(ARGS_DELIMITER);
					}
				}

				setState(1402);
				((AllRefsReferenceContentArgsContext)_localctx).requirement = requireConstraint();
				}
				}
				break;
			case 3:
				{
				{
				setState(1406);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (((((_la - 143)) & ~0x3f) == 0 && ((1L << (_la - 143)) & 32767L) != 0)) {
					{
					setState(1403);
					((AllRefsReferenceContentArgsContext)_localctx).managedReferencesBehaviour = valueToken();
					setState(1404);
					match(ARGS_DELIMITER);
					}
				}

				setState(1408);
				((AllRefsReferenceContentArgsContext)_localctx).entityRequirement = requireConstraint();
				setState(1409);
				match(ARGS_DELIMITER);
				setState(1410);
				((AllRefsReferenceContentArgsContext)_localctx).groupEntityRequirement = requireConstraint();
				}
				}
				break;
			}
			setState(1414);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 142, RULE_allRefsWithAttributesReferenceContent1Args);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1416);
			argsOpening();
			setState(1424);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,96,_ctx) ) {
			case 1:
				{
				{
				setState(1417);
				((AllRefsWithAttributesReferenceContent1ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				}
				}
				break;
			case 2:
				{
				setState(1421);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (((((_la - 143)) & ~0x3f) == 0 && ((1L << (_la - 143)) & 32767L) != 0)) {
					{
					setState(1418);
					((AllRefsWithAttributesReferenceContent1ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
					setState(1419);
					match(ARGS_DELIMITER);
					}
				}

				setState(1423);
				((AllRefsWithAttributesReferenceContent1ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 144, RULE_allRefsWithAttributesReferenceContent2Args);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1428);
			argsOpening();
			setState(1432);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (((((_la - 143)) & ~0x3f) == 0 && ((1L << (_la - 143)) & 32767L) != 0)) {
				{
				setState(1429);
				((AllRefsWithAttributesReferenceContent2ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1430);
				match(ARGS_DELIMITER);
				}
			}

			setState(1434);
			((AllRefsWithAttributesReferenceContent2ArgsContext)_localctx).requirement1 = requireConstraint();
			setState(1435);
			match(ARGS_DELIMITER);
			setState(1436);
			((AllRefsWithAttributesReferenceContent2ArgsContext)_localctx).requirement2 = requireConstraint();
			setState(1437);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 146, RULE_allRefsWithAttributesReferenceContent3Args);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1439);
			argsOpening();
			setState(1443);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (((((_la - 143)) & ~0x3f) == 0 && ((1L << (_la - 143)) & 32767L) != 0)) {
				{
				setState(1440);
				((AllRefsWithAttributesReferenceContent3ArgsContext)_localctx).managedReferencesBehaviour = valueToken();
				setState(1441);
				match(ARGS_DELIMITER);
				}
			}

			setState(1445);
			((AllRefsWithAttributesReferenceContent3ArgsContext)_localctx).attributeContent = requireConstraint();
			setState(1446);
			match(ARGS_DELIMITER);
			setState(1447);
			((AllRefsWithAttributesReferenceContent3ArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(1448);
			match(ARGS_DELIMITER);
			setState(1449);
			((AllRefsWithAttributesReferenceContent3ArgsContext)_localctx).groupEntityRequirement = requireConstraint();
			setState(1452);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,99,_ctx) ) {
			case 1:
				{
				setState(1450);
				match(ARGS_DELIMITER);
				setState(1451);
				((AllRefsWithAttributesReferenceContent3ArgsContext)_localctx).requirement = requireConstraint();
				}
				break;
			}
			setState(1454);
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

	@SuppressWarnings("CheckReturnValue")
	public static class SingleRequireHierarchyContentArgsContext extends ParserRuleContext {
		public ValueTokenContext parentsBehaviour;
		public RequireConstraintContext requirement;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public ValueTokenContext valueToken() {
			return getRuleContext(ValueTokenContext.class,0);
		}
		public RequireConstraintContext requireConstraint() {
			return getRuleContext(RequireConstraintContext.class,0);
		}
		public TerminalNode ARGS_DELIMITER() { return getToken(EvitaQLParser.ARGS_DELIMITER, 0); }
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
		enterRule(_localctx, 148, RULE_singleRequireHierarchyContentArgs);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1456);
			argsOpening();
			setState(1464);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,101,_ctx) ) {
			case 1:
				{
				{
				setState(1457);
				((SingleRequireHierarchyContentArgsContext)_localctx).parentsBehaviour = valueToken();
				}
				}
				break;
			case 2:
				{
				{
				setState(1461);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (((((_la - 143)) & ~0x3f) == 0 && ((1L << (_la - 143)) & 32767L) != 0)) {
					{
					setState(1458);
					((SingleRequireHierarchyContentArgsContext)_localctx).parentsBehaviour = valueToken();
					setState(1459);
					match(ARGS_DELIMITER);
					}
				}

				setState(1463);
				((SingleRequireHierarchyContentArgsContext)_localctx).requirement = requireConstraint();
				}
				}
				break;
			}
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

	@SuppressWarnings("CheckReturnValue")
	public static class AllRequiresHierarchyContentArgsContext extends ParserRuleContext {
		public ValueTokenContext parentsBehaviour;
		public RequireConstraintContext stopAt;
		public RequireConstraintContext entityRequirement;
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
		enterRule(_localctx, 150, RULE_allRequiresHierarchyContentArgs);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1468);
			argsOpening();
			setState(1472);
			_errHandler.sync(this);
			_la = _input.LA(1);
			if (((((_la - 143)) & ~0x3f) == 0 && ((1L << (_la - 143)) & 32767L) != 0)) {
				{
				setState(1469);
				((AllRequiresHierarchyContentArgsContext)_localctx).parentsBehaviour = valueToken();
				setState(1470);
				match(ARGS_DELIMITER);
				}
			}

			setState(1474);
			((AllRequiresHierarchyContentArgsContext)_localctx).stopAt = requireConstraint();
			setState(1475);
			match(ARGS_DELIMITER);
			setState(1476);
			((AllRequiresHierarchyContentArgsContext)_localctx).entityRequirement = requireConstraint();
			setState(1477);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 152, RULE_facetSummary1Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1479);
			argsOpening();
			setState(1480);
			((FacetSummary1ArgsContext)_localctx).depth = valueToken();
			setState(1481);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 154, RULE_facetSummary2Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1483);
			argsOpening();
			setState(1484);
			((FacetSummary2ArgsContext)_localctx).depth = valueToken();
			setState(1485);
			match(ARGS_DELIMITER);
			setState(1486);
			((FacetSummary2ArgsContext)_localctx).filter = facetSummaryFilterArgs();
			setState(1489);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,103,_ctx) ) {
			case 1:
				{
				setState(1487);
				match(ARGS_DELIMITER);
				setState(1488);
				((FacetSummary2ArgsContext)_localctx).order = facetSummaryOrderArgs();
				}
				break;
			}
			setState(1493);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,104,_ctx) ) {
			case 1:
				{
				setState(1491);
				match(ARGS_DELIMITER);
				setState(1492);
				((FacetSummary2ArgsContext)_localctx).requirements = facetSummaryRequirementsArgs();
				}
				break;
			}
			setState(1495);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 156, RULE_facetSummary3Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1497);
			argsOpening();
			setState(1498);
			((FacetSummary3ArgsContext)_localctx).depth = valueToken();
			setState(1499);
			match(ARGS_DELIMITER);
			setState(1500);
			((FacetSummary3ArgsContext)_localctx).order = facetSummaryOrderArgs();
			setState(1503);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,105,_ctx) ) {
			case 1:
				{
				setState(1501);
				match(ARGS_DELIMITER);
				setState(1502);
				((FacetSummary3ArgsContext)_localctx).requirements = facetSummaryRequirementsArgs();
				}
				break;
			}
			setState(1505);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 158, RULE_facetSummary4Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1507);
			argsOpening();
			setState(1508);
			((FacetSummary4ArgsContext)_localctx).depth = valueToken();
			setState(1509);
			match(ARGS_DELIMITER);
			setState(1510);
			((FacetSummary4ArgsContext)_localctx).requirements = facetSummaryRequirementsArgs();
			setState(1511);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 160, RULE_facetSummary5Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1513);
			argsOpening();
			setState(1514);
			((FacetSummary5ArgsContext)_localctx).filter = facetSummaryFilterArgs();
			setState(1517);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,106,_ctx) ) {
			case 1:
				{
				setState(1515);
				match(ARGS_DELIMITER);
				setState(1516);
				((FacetSummary5ArgsContext)_localctx).order = facetSummaryOrderArgs();
				}
				break;
			}
			setState(1521);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,107,_ctx) ) {
			case 1:
				{
				setState(1519);
				match(ARGS_DELIMITER);
				setState(1520);
				((FacetSummary5ArgsContext)_localctx).requirements = facetSummaryRequirementsArgs();
				}
				break;
			}
			setState(1523);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 162, RULE_facetSummary6Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1525);
			argsOpening();
			setState(1526);
			((FacetSummary6ArgsContext)_localctx).order = facetSummaryOrderArgs();
			setState(1529);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,108,_ctx) ) {
			case 1:
				{
				setState(1527);
				match(ARGS_DELIMITER);
				setState(1528);
				((FacetSummary6ArgsContext)_localctx).requirements = facetSummaryRequirementsArgs();
				}
				break;
			}
			setState(1531);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 164, RULE_facetSummary7Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1533);
			argsOpening();
			setState(1534);
			((FacetSummary7ArgsContext)_localctx).requirements = facetSummaryRequirementsArgs();
			setState(1535);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 166, RULE_facetSummaryOfReference2Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1537);
			argsOpening();
			setState(1538);
			((FacetSummaryOfReference2ArgsContext)_localctx).referenceName = valueToken();
			setState(1541);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,109,_ctx) ) {
			case 1:
				{
				setState(1539);
				match(ARGS_DELIMITER);
				setState(1540);
				((FacetSummaryOfReference2ArgsContext)_localctx).depth = valueToken();
				}
				break;
			}
			setState(1545);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,110,_ctx) ) {
			case 1:
				{
				setState(1543);
				match(ARGS_DELIMITER);
				setState(1544);
				((FacetSummaryOfReference2ArgsContext)_localctx).filter = facetSummaryFilterArgs();
				}
				break;
			}
			setState(1549);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,111,_ctx) ) {
			case 1:
				{
				setState(1547);
				match(ARGS_DELIMITER);
				setState(1548);
				((FacetSummaryOfReference2ArgsContext)_localctx).order = facetSummaryOrderArgs();
				}
				break;
			}
			setState(1553);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,112,_ctx) ) {
			case 1:
				{
				setState(1551);
				match(ARGS_DELIMITER);
				setState(1552);
				((FacetSummaryOfReference2ArgsContext)_localctx).requirements = facetSummaryRequirementsArgs();
				}
				break;
			}
			setState(1555);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 168, RULE_facetSummaryRequirementsArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1562);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,113,_ctx) ) {
			case 1:
				{
				{
				setState(1557);
				((FacetSummaryRequirementsArgsContext)_localctx).requirement = requireConstraint();
				}
				}
				break;
			case 2:
				{
				{
				setState(1558);
				((FacetSummaryRequirementsArgsContext)_localctx).facetEntityRequirement = requireConstraint();
				setState(1559);
				match(ARGS_DELIMITER);
				setState(1560);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 170, RULE_facetSummaryFilterArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1569);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,114,_ctx) ) {
			case 1:
				{
				{
				setState(1564);
				((FacetSummaryFilterArgsContext)_localctx).filterBy = filterConstraint();
				}
				}
				break;
			case 2:
				{
				{
				setState(1565);
				((FacetSummaryFilterArgsContext)_localctx).filterBy = filterConstraint();
				setState(1566);
				match(ARGS_DELIMITER);
				setState(1567);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 172, RULE_facetSummaryOrderArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1576);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,115,_ctx) ) {
			case 1:
				{
				{
				setState(1571);
				((FacetSummaryOrderArgsContext)_localctx).orderBy = orderConstraint();
				}
				}
				break;
			case 2:
				{
				{
				setState(1572);
				((FacetSummaryOrderArgsContext)_localctx).orderBy = orderConstraint();
				setState(1573);
				match(ARGS_DELIMITER);
				setState(1574);
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

	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceSummary1ArgsContext extends ParserRuleContext {
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
		public ReferenceSummary1ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_referenceSummary1Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceSummary1Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceSummary1Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceSummary1Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ReferenceSummary1ArgsContext referenceSummary1Args() throws RecognitionException {
		ReferenceSummary1ArgsContext _localctx = new ReferenceSummary1ArgsContext(_ctx, getState());
		enterRule(_localctx, 174, RULE_referenceSummary1Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1578);
			argsOpening();
			setState(1579);
			((ReferenceSummary1ArgsContext)_localctx).depth = valueToken();
			setState(1580);
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

	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceSummary2ArgsContext extends ParserRuleContext {
		public ValueTokenContext depth;
		public FacetSummaryFilterArgsContext filter;
		public FacetSummaryOrderArgsContext order;
		public ReferenceSummaryRequirementsArgsContext requirements;
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
		public ReferenceSummaryRequirementsArgsContext referenceSummaryRequirementsArgs() {
			return getRuleContext(ReferenceSummaryRequirementsArgsContext.class,0);
		}
		public ReferenceSummary2ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_referenceSummary2Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceSummary2Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceSummary2Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceSummary2Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ReferenceSummary2ArgsContext referenceSummary2Args() throws RecognitionException {
		ReferenceSummary2ArgsContext _localctx = new ReferenceSummary2ArgsContext(_ctx, getState());
		enterRule(_localctx, 176, RULE_referenceSummary2Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1582);
			argsOpening();
			setState(1583);
			((ReferenceSummary2ArgsContext)_localctx).depth = valueToken();
			setState(1584);
			match(ARGS_DELIMITER);
			setState(1585);
			((ReferenceSummary2ArgsContext)_localctx).filter = facetSummaryFilterArgs();
			setState(1588);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,116,_ctx) ) {
			case 1:
				{
				setState(1586);
				match(ARGS_DELIMITER);
				setState(1587);
				((ReferenceSummary2ArgsContext)_localctx).order = facetSummaryOrderArgs();
				}
				break;
			}
			setState(1592);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,117,_ctx) ) {
			case 1:
				{
				setState(1590);
				match(ARGS_DELIMITER);
				setState(1591);
				((ReferenceSummary2ArgsContext)_localctx).requirements = referenceSummaryRequirementsArgs();
				}
				break;
			}
			setState(1594);
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

	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceSummary3ArgsContext extends ParserRuleContext {
		public ValueTokenContext depth;
		public FacetSummaryOrderArgsContext order;
		public ReferenceSummaryRequirementsArgsContext requirements;
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
		public ReferenceSummaryRequirementsArgsContext referenceSummaryRequirementsArgs() {
			return getRuleContext(ReferenceSummaryRequirementsArgsContext.class,0);
		}
		public ReferenceSummary3ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_referenceSummary3Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceSummary3Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceSummary3Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceSummary3Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ReferenceSummary3ArgsContext referenceSummary3Args() throws RecognitionException {
		ReferenceSummary3ArgsContext _localctx = new ReferenceSummary3ArgsContext(_ctx, getState());
		enterRule(_localctx, 178, RULE_referenceSummary3Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1596);
			argsOpening();
			setState(1597);
			((ReferenceSummary3ArgsContext)_localctx).depth = valueToken();
			setState(1598);
			match(ARGS_DELIMITER);
			setState(1599);
			((ReferenceSummary3ArgsContext)_localctx).order = facetSummaryOrderArgs();
			setState(1602);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,118,_ctx) ) {
			case 1:
				{
				setState(1600);
				match(ARGS_DELIMITER);
				setState(1601);
				((ReferenceSummary3ArgsContext)_localctx).requirements = referenceSummaryRequirementsArgs();
				}
				break;
			}
			setState(1604);
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

	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceSummary4ArgsContext extends ParserRuleContext {
		public ValueTokenContext depth;
		public ReferenceSummaryRequirementsArgsContext requirements;
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
		public ReferenceSummaryRequirementsArgsContext referenceSummaryRequirementsArgs() {
			return getRuleContext(ReferenceSummaryRequirementsArgsContext.class,0);
		}
		public ReferenceSummary4ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_referenceSummary4Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceSummary4Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceSummary4Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceSummary4Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ReferenceSummary4ArgsContext referenceSummary4Args() throws RecognitionException {
		ReferenceSummary4ArgsContext _localctx = new ReferenceSummary4ArgsContext(_ctx, getState());
		enterRule(_localctx, 180, RULE_referenceSummary4Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1606);
			argsOpening();
			setState(1607);
			((ReferenceSummary4ArgsContext)_localctx).depth = valueToken();
			setState(1608);
			match(ARGS_DELIMITER);
			setState(1609);
			((ReferenceSummary4ArgsContext)_localctx).requirements = referenceSummaryRequirementsArgs();
			setState(1610);
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

	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceSummary5ArgsContext extends ParserRuleContext {
		public FacetSummaryFilterArgsContext filter;
		public FacetSummaryOrderArgsContext order;
		public ReferenceSummaryRequirementsArgsContext requirements;
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
		public ReferenceSummaryRequirementsArgsContext referenceSummaryRequirementsArgs() {
			return getRuleContext(ReferenceSummaryRequirementsArgsContext.class,0);
		}
		public ReferenceSummary5ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_referenceSummary5Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceSummary5Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceSummary5Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceSummary5Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ReferenceSummary5ArgsContext referenceSummary5Args() throws RecognitionException {
		ReferenceSummary5ArgsContext _localctx = new ReferenceSummary5ArgsContext(_ctx, getState());
		enterRule(_localctx, 182, RULE_referenceSummary5Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1612);
			argsOpening();
			setState(1613);
			((ReferenceSummary5ArgsContext)_localctx).filter = facetSummaryFilterArgs();
			setState(1616);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,119,_ctx) ) {
			case 1:
				{
				setState(1614);
				match(ARGS_DELIMITER);
				setState(1615);
				((ReferenceSummary5ArgsContext)_localctx).order = facetSummaryOrderArgs();
				}
				break;
			}
			setState(1620);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,120,_ctx) ) {
			case 1:
				{
				setState(1618);
				match(ARGS_DELIMITER);
				setState(1619);
				((ReferenceSummary5ArgsContext)_localctx).requirements = referenceSummaryRequirementsArgs();
				}
				break;
			}
			setState(1622);
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

	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceSummary6ArgsContext extends ParserRuleContext {
		public FacetSummaryOrderArgsContext order;
		public ReferenceSummaryRequirementsArgsContext requirements;
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
		public ReferenceSummaryRequirementsArgsContext referenceSummaryRequirementsArgs() {
			return getRuleContext(ReferenceSummaryRequirementsArgsContext.class,0);
		}
		public ReferenceSummary6ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_referenceSummary6Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceSummary6Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceSummary6Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceSummary6Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ReferenceSummary6ArgsContext referenceSummary6Args() throws RecognitionException {
		ReferenceSummary6ArgsContext _localctx = new ReferenceSummary6ArgsContext(_ctx, getState());
		enterRule(_localctx, 184, RULE_referenceSummary6Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1624);
			argsOpening();
			setState(1625);
			((ReferenceSummary6ArgsContext)_localctx).order = facetSummaryOrderArgs();
			setState(1628);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,121,_ctx) ) {
			case 1:
				{
				setState(1626);
				match(ARGS_DELIMITER);
				setState(1627);
				((ReferenceSummary6ArgsContext)_localctx).requirements = referenceSummaryRequirementsArgs();
				}
				break;
			}
			setState(1630);
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

	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceSummary7ArgsContext extends ParserRuleContext {
		public ReferenceSummaryRequirementsArgsContext requirements;
		public ArgsOpeningContext argsOpening() {
			return getRuleContext(ArgsOpeningContext.class,0);
		}
		public ArgsClosingContext argsClosing() {
			return getRuleContext(ArgsClosingContext.class,0);
		}
		public ReferenceSummaryRequirementsArgsContext referenceSummaryRequirementsArgs() {
			return getRuleContext(ReferenceSummaryRequirementsArgsContext.class,0);
		}
		public ReferenceSummary7ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_referenceSummary7Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceSummary7Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceSummary7Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceSummary7Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ReferenceSummary7ArgsContext referenceSummary7Args() throws RecognitionException {
		ReferenceSummary7ArgsContext _localctx = new ReferenceSummary7ArgsContext(_ctx, getState());
		enterRule(_localctx, 186, RULE_referenceSummary7Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1632);
			argsOpening();
			setState(1633);
			((ReferenceSummary7ArgsContext)_localctx).requirements = referenceSummaryRequirementsArgs();
			setState(1634);
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

	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceSummaryOfReference2ArgsContext extends ParserRuleContext {
		public ValueTokenContext referenceName;
		public ValueTokenContext depth;
		public FacetSummaryFilterArgsContext filter;
		public FacetSummaryOrderArgsContext order;
		public ReferenceSummaryRequirementsArgsContext requirements;
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
		public ReferenceSummaryRequirementsArgsContext referenceSummaryRequirementsArgs() {
			return getRuleContext(ReferenceSummaryRequirementsArgsContext.class,0);
		}
		public ReferenceSummaryOfReference2ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_referenceSummaryOfReference2Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceSummaryOfReference2Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceSummaryOfReference2Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceSummaryOfReference2Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ReferenceSummaryOfReference2ArgsContext referenceSummaryOfReference2Args() throws RecognitionException {
		ReferenceSummaryOfReference2ArgsContext _localctx = new ReferenceSummaryOfReference2ArgsContext(_ctx, getState());
		enterRule(_localctx, 188, RULE_referenceSummaryOfReference2Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1636);
			argsOpening();
			setState(1637);
			((ReferenceSummaryOfReference2ArgsContext)_localctx).referenceName = valueToken();
			setState(1640);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,122,_ctx) ) {
			case 1:
				{
				setState(1638);
				match(ARGS_DELIMITER);
				setState(1639);
				((ReferenceSummaryOfReference2ArgsContext)_localctx).depth = valueToken();
				}
				break;
			}
			setState(1644);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,123,_ctx) ) {
			case 1:
				{
				setState(1642);
				match(ARGS_DELIMITER);
				setState(1643);
				((ReferenceSummaryOfReference2ArgsContext)_localctx).filter = facetSummaryFilterArgs();
				}
				break;
			}
			setState(1648);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,124,_ctx) ) {
			case 1:
				{
				setState(1646);
				match(ARGS_DELIMITER);
				setState(1647);
				((ReferenceSummaryOfReference2ArgsContext)_localctx).order = facetSummaryOrderArgs();
				}
				break;
			}
			setState(1652);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,125,_ctx) ) {
			case 1:
				{
				setState(1650);
				match(ARGS_DELIMITER);
				setState(1651);
				((ReferenceSummaryOfReference2ArgsContext)_localctx).requirements = referenceSummaryRequirementsArgs();
				}
				break;
			}
			setState(1654);
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

	@SuppressWarnings("CheckReturnValue")
	public static class ReferenceSummaryRequirementsArgsContext extends ParserRuleContext {
		public RequireConstraintContext requireConstraint;
		public List<RequireConstraintContext> requirements = new ArrayList<RequireConstraintContext>();
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
		public ReferenceSummaryRequirementsArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_referenceSummaryRequirementsArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterReferenceSummaryRequirementsArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitReferenceSummaryRequirementsArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitReferenceSummaryRequirementsArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final ReferenceSummaryRequirementsArgsContext referenceSummaryRequirementsArgs() throws RecognitionException {
		ReferenceSummaryRequirementsArgsContext _localctx = new ReferenceSummaryRequirementsArgsContext(_ctx, getState());
		enterRule(_localctx, 190, RULE_referenceSummaryRequirementsArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1656);
			((ReferenceSummaryRequirementsArgsContext)_localctx).requireConstraint = requireConstraint();
			((ReferenceSummaryRequirementsArgsContext)_localctx).requirements.add(((ReferenceSummaryRequirementsArgsContext)_localctx).requireConstraint);
			setState(1661);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,126,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(1657);
					match(ARGS_DELIMITER);
					setState(1658);
					((ReferenceSummaryRequirementsArgsContext)_localctx).requireConstraint = requireConstraint();
					((ReferenceSummaryRequirementsArgsContext)_localctx).requirements.add(((ReferenceSummaryRequirementsArgsContext)_localctx).requireConstraint);
					}
					} 
				}
				setState(1663);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,126,_ctx);
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
	public static class HistogramStatistics1ArgsContext extends ParserRuleContext {
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
		public HistogramStatistics1ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_histogramStatistics1Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHistogramStatistics1Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHistogramStatistics1Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHistogramStatistics1Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final HistogramStatistics1ArgsContext histogramStatistics1Args() throws RecognitionException {
		HistogramStatistics1ArgsContext _localctx = new HistogramStatistics1ArgsContext(_ctx, getState());
		enterRule(_localctx, 192, RULE_histogramStatistics1Args);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1664);
			argsOpening();
			setState(1665);
			((HistogramStatistics1ArgsContext)_localctx).requestedBucketCount = valueToken();
			setState(1666);
			match(ARGS_DELIMITER);
			setState(1667);
			((HistogramStatistics1ArgsContext)_localctx).values = variadicValueTokens();
			setState(1668);
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

	@SuppressWarnings("CheckReturnValue")
	public static class HistogramStatistics2ArgsContext extends ParserRuleContext {
		public ValueTokenContext requestedBucketCount;
		public ValueTokenContext behavior;
		public RequireConstraintContext fetchBeforeValues;
		public VariadicValueTokensContext values;
		public RequireConstraintContext fetchAfterValues;
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
		public VariadicValueTokensContext variadicValueTokens() {
			return getRuleContext(VariadicValueTokensContext.class,0);
		}
		public HistogramStatistics2ArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_histogramStatistics2Args; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterHistogramStatistics2Args(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitHistogramStatistics2Args(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitHistogramStatistics2Args(this);
			else return visitor.visitChildren(this);
		}
	}

	public final HistogramStatistics2ArgsContext histogramStatistics2Args() throws RecognitionException {
		HistogramStatistics2ArgsContext _localctx = new HistogramStatistics2ArgsContext(_ctx, getState());
		enterRule(_localctx, 194, RULE_histogramStatistics2Args);
		int _la;
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1670);
			argsOpening();
			setState(1671);
			((HistogramStatistics2ArgsContext)_localctx).requestedBucketCount = valueToken();
			setState(1672);
			match(ARGS_DELIMITER);
			setState(1686);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,128,_ctx) ) {
			case 1:
				{
				setState(1676);
				_errHandler.sync(this);
				_la = _input.LA(1);
				if (((((_la - 143)) & ~0x3f) == 0 && ((1L << (_la - 143)) & 32767L) != 0)) {
					{
					setState(1673);
					((HistogramStatistics2ArgsContext)_localctx).behavior = valueToken();
					setState(1674);
					match(ARGS_DELIMITER);
					}
				}

				setState(1678);
				((HistogramStatistics2ArgsContext)_localctx).fetchBeforeValues = requireConstraint();
				setState(1679);
				match(ARGS_DELIMITER);
				setState(1680);
				((HistogramStatistics2ArgsContext)_localctx).values = variadicValueTokens();
				}
				break;
			case 2:
				{
				setState(1682);
				((HistogramStatistics2ArgsContext)_localctx).values = variadicValueTokens();
				setState(1683);
				match(ARGS_DELIMITER);
				setState(1684);
				((HistogramStatistics2ArgsContext)_localctx).fetchAfterValues = requireConstraint();
				}
				break;
			}
			setState(1688);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 196, RULE_attributeHistogramArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1690);
			argsOpening();
			setState(1691);
			((AttributeHistogramArgsContext)_localctx).requestedBucketCount = valueToken();
			setState(1692);
			match(ARGS_DELIMITER);
			setState(1693);
			((AttributeHistogramArgsContext)_localctx).values = variadicValueTokens();
			setState(1694);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 198, RULE_priceHistogramArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1696);
			argsOpening();
			setState(1697);
			((PriceHistogramArgsContext)_localctx).requestedBucketCount = valueToken();
			setState(1700);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,129,_ctx) ) {
			case 1:
				{
				setState(1698);
				match(ARGS_DELIMITER);
				setState(1699);
				((PriceHistogramArgsContext)_localctx).behaviour = valueToken();
				}
				break;
			}
			setState(1702);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 200, RULE_hierarchyStatisticsArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1704);
			argsOpening();
			setState(1705);
			((HierarchyStatisticsArgsContext)_localctx).settings = variadicValueTokens();
			setState(1706);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 202, RULE_hierarchyRequireConstraintArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1708);
			argsOpening();
			setState(1709);
			((HierarchyRequireConstraintArgsContext)_localctx).outputName = valueToken();
			setState(1714);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,130,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(1710);
					match(ARGS_DELIMITER);
					setState(1711);
					((HierarchyRequireConstraintArgsContext)_localctx).requireConstraint = requireConstraint();
					((HierarchyRequireConstraintArgsContext)_localctx).requirements.add(((HierarchyRequireConstraintArgsContext)_localctx).requireConstraint);
					}
					} 
				}
				setState(1716);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,130,_ctx);
			}
			setState(1717);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 204, RULE_hierarchyFromNodeArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1719);
			argsOpening();
			setState(1720);
			((HierarchyFromNodeArgsContext)_localctx).outputName = valueToken();
			setState(1721);
			match(ARGS_DELIMITER);
			setState(1722);
			((HierarchyFromNodeArgsContext)_localctx).node = requireConstraint();
			setState(1727);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,131,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(1723);
					match(ARGS_DELIMITER);
					setState(1724);
					((HierarchyFromNodeArgsContext)_localctx).requireConstraint = requireConstraint();
					((HierarchyFromNodeArgsContext)_localctx).requirements.add(((HierarchyFromNodeArgsContext)_localctx).requireConstraint);
					}
					} 
				}
				setState(1729);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,131,_ctx);
			}
			setState(1730);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 206, RULE_fullHierarchyOfSelfArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1732);
			argsOpening();
			setState(1733);
			((FullHierarchyOfSelfArgsContext)_localctx).orderBy = orderConstraint();
			setState(1736); 
			_errHandler.sync(this);
			_alt = 1;
			do {
				switch (_alt) {
				case 1:
					{
					{
					setState(1734);
					match(ARGS_DELIMITER);
					setState(1735);
					((FullHierarchyOfSelfArgsContext)_localctx).requireConstraint = requireConstraint();
					((FullHierarchyOfSelfArgsContext)_localctx).requirements.add(((FullHierarchyOfSelfArgsContext)_localctx).requireConstraint);
					}
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				setState(1738); 
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,132,_ctx);
			} while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER );
			setState(1740);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 208, RULE_basicHierarchyOfReferenceArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1742);
			argsOpening();
			setState(1743);
			((BasicHierarchyOfReferenceArgsContext)_localctx).referenceName = valueToken();
			setState(1746); 
			_errHandler.sync(this);
			_alt = 1;
			do {
				switch (_alt) {
				case 1:
					{
					{
					setState(1744);
					match(ARGS_DELIMITER);
					setState(1745);
					((BasicHierarchyOfReferenceArgsContext)_localctx).requireConstraint = requireConstraint();
					((BasicHierarchyOfReferenceArgsContext)_localctx).requirements.add(((BasicHierarchyOfReferenceArgsContext)_localctx).requireConstraint);
					}
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				setState(1748); 
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,133,_ctx);
			} while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER );
			setState(1750);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 210, RULE_basicHierarchyOfReferenceWithBehaviourArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1752);
			argsOpening();
			setState(1753);
			((BasicHierarchyOfReferenceWithBehaviourArgsContext)_localctx).referenceName = valueToken();
			setState(1754);
			match(ARGS_DELIMITER);
			setState(1755);
			((BasicHierarchyOfReferenceWithBehaviourArgsContext)_localctx).emptyHierarchicalEntityBehaviour = valueToken();
			setState(1758); 
			_errHandler.sync(this);
			_alt = 1;
			do {
				switch (_alt) {
				case 1:
					{
					{
					setState(1756);
					match(ARGS_DELIMITER);
					setState(1757);
					((BasicHierarchyOfReferenceWithBehaviourArgsContext)_localctx).requireConstraint = requireConstraint();
					((BasicHierarchyOfReferenceWithBehaviourArgsContext)_localctx).requirements.add(((BasicHierarchyOfReferenceWithBehaviourArgsContext)_localctx).requireConstraint);
					}
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				setState(1760); 
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,134,_ctx);
			} while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER );
			setState(1762);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 212, RULE_fullHierarchyOfReferenceArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1764);
			argsOpening();
			setState(1765);
			((FullHierarchyOfReferenceArgsContext)_localctx).referenceName = valueToken();
			setState(1766);
			match(ARGS_DELIMITER);
			setState(1767);
			((FullHierarchyOfReferenceArgsContext)_localctx).orderBy = orderConstraint();
			setState(1770); 
			_errHandler.sync(this);
			_alt = 1;
			do {
				switch (_alt) {
				case 1:
					{
					{
					setState(1768);
					match(ARGS_DELIMITER);
					setState(1769);
					((FullHierarchyOfReferenceArgsContext)_localctx).requireConstraint = requireConstraint();
					((FullHierarchyOfReferenceArgsContext)_localctx).requirements.add(((FullHierarchyOfReferenceArgsContext)_localctx).requireConstraint);
					}
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				setState(1772); 
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,135,_ctx);
			} while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER );
			setState(1774);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 214, RULE_fullHierarchyOfReferenceWithBehaviourArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1776);
			argsOpening();
			setState(1777);
			((FullHierarchyOfReferenceWithBehaviourArgsContext)_localctx).referenceName = valueToken();
			setState(1778);
			match(ARGS_DELIMITER);
			setState(1779);
			((FullHierarchyOfReferenceWithBehaviourArgsContext)_localctx).emptyHierarchicalEntityBehaviour = valueToken();
			setState(1780);
			match(ARGS_DELIMITER);
			setState(1781);
			((FullHierarchyOfReferenceWithBehaviourArgsContext)_localctx).orderBy = orderConstraint();
			setState(1784); 
			_errHandler.sync(this);
			_alt = 1;
			do {
				switch (_alt) {
				case 1:
					{
					{
					setState(1782);
					match(ARGS_DELIMITER);
					setState(1783);
					((FullHierarchyOfReferenceWithBehaviourArgsContext)_localctx).requireConstraint = requireConstraint();
					((FullHierarchyOfReferenceWithBehaviourArgsContext)_localctx).requirements.add(((FullHierarchyOfReferenceWithBehaviourArgsContext)_localctx).requireConstraint);
					}
					}
					break;
				default:
					throw new NoViableAltException(this);
				}
				setState(1786); 
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,136,_ctx);
			} while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER );
			setState(1788);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 216, RULE_spacingRequireConstraintArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1790);
			argsOpening();
			setState(1791);
			((SpacingRequireConstraintArgsContext)_localctx).requireConstraint = requireConstraint();
			((SpacingRequireConstraintArgsContext)_localctx).constraints.add(((SpacingRequireConstraintArgsContext)_localctx).requireConstraint);
			setState(1796);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,137,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(1792);
					match(ARGS_DELIMITER);
					setState(1793);
					((SpacingRequireConstraintArgsContext)_localctx).requireConstraint = requireConstraint();
					((SpacingRequireConstraintArgsContext)_localctx).constraints.add(((SpacingRequireConstraintArgsContext)_localctx).requireConstraint);
					}
					} 
				}
				setState(1798);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,137,_ctx);
			}
			setState(1799);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 218, RULE_gapRequireConstraintArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1801);
			argsOpening();
			setState(1802);
			((GapRequireConstraintArgsContext)_localctx).size = valueToken();
			setState(1803);
			match(ARGS_DELIMITER);
			setState(1804);
			((GapRequireConstraintArgsContext)_localctx).expression = valueToken();
			setState(1805);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 220, RULE_segmentArgs);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1807);
			argsOpening();
			setState(1811);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,138,_ctx) ) {
			case 1:
				{
				setState(1808);
				((SegmentArgsContext)_localctx).entityHaving = filterConstraint();
				setState(1809);
				match(ARGS_DELIMITER);
				}
				break;
			}
			setState(1813);
			((SegmentArgsContext)_localctx).orderBy = orderConstraint();
			setState(1816);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,139,_ctx) ) {
			case 1:
				{
				setState(1814);
				match(ARGS_DELIMITER);
				setState(1815);
				((SegmentArgsContext)_localctx).limit = orderConstraint();
				}
				break;
			}
			setState(1818);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 222, RULE_inScopeFilterArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1820);
			argsOpening();
			setState(1821);
			((InScopeFilterArgsContext)_localctx).scope = valueToken();
			setState(1826);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,140,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(1822);
					match(ARGS_DELIMITER);
					setState(1823);
					((InScopeFilterArgsContext)_localctx).filterConstraint = filterConstraint();
					((InScopeFilterArgsContext)_localctx).filterConstraints.add(((InScopeFilterArgsContext)_localctx).filterConstraint);
					}
					} 
				}
				setState(1828);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,140,_ctx);
			}
			setState(1829);
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

	@SuppressWarnings("CheckReturnValue")
	public static class TextReferenceArgsContext extends ParserRuleContext {
		public ValueTokenContext classifier;
		public FilterConstraintContext filterConstraint;
		public List<FilterConstraintContext> constraints = new ArrayList<FilterConstraintContext>();
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
		public TextReferenceArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_textReferenceArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterTextReferenceArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitTextReferenceArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitTextReferenceArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final TextReferenceArgsContext textReferenceArgs() throws RecognitionException {
		TextReferenceArgsContext _localctx = new TextReferenceArgsContext(_ctx, getState());
		enterRule(_localctx, 224, RULE_textReferenceArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1831);
			argsOpening();
			setState(1832);
			((TextReferenceArgsContext)_localctx).classifier = valueToken();
			setState(1837);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,141,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(1833);
					match(ARGS_DELIMITER);
					setState(1834);
					((TextReferenceArgsContext)_localctx).filterConstraint = filterConstraint();
					((TextReferenceArgsContext)_localctx).constraints.add(((TextReferenceArgsContext)_localctx).filterConstraint);
					}
					} 
				}
				setState(1839);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,141,_ctx);
			}
			setState(1840);
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

	@SuppressWarnings("CheckReturnValue")
	public static class TextMatchesArgsContext extends ParserRuleContext {
		public ValueTokenContext text;
		public FilterConstraintContext filterConstraint;
		public List<FilterConstraintContext> constraints = new ArrayList<FilterConstraintContext>();
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
		public TextMatchesArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_textMatchesArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterTextMatchesArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitTextMatchesArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitTextMatchesArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final TextMatchesArgsContext textMatchesArgs() throws RecognitionException {
		TextMatchesArgsContext _localctx = new TextMatchesArgsContext(_ctx, getState());
		enterRule(_localctx, 226, RULE_textMatchesArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1842);
			argsOpening();
			setState(1843);
			((TextMatchesArgsContext)_localctx).text = valueToken();
			setState(1848);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,142,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(1844);
					match(ARGS_DELIMITER);
					setState(1845);
					((TextMatchesArgsContext)_localctx).filterConstraint = filterConstraint();
					((TextMatchesArgsContext)_localctx).constraints.add(((TextMatchesArgsContext)_localctx).filterConstraint);
					}
					} 
				}
				setState(1850);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,142,_ctx);
			}
			setState(1851);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 228, RULE_inScopeOrderArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1853);
			argsOpening();
			setState(1854);
			((InScopeOrderArgsContext)_localctx).scope = valueToken();
			setState(1859);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,143,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(1855);
					match(ARGS_DELIMITER);
					setState(1856);
					((InScopeOrderArgsContext)_localctx).orderConstraint = orderConstraint();
					((InScopeOrderArgsContext)_localctx).orderConstraints.add(((InScopeOrderArgsContext)_localctx).orderConstraint);
					}
					} 
				}
				setState(1861);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,143,_ctx);
			}
			setState(1862);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 230, RULE_inScopeRequireArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1864);
			argsOpening();
			setState(1865);
			((InScopeRequireArgsContext)_localctx).scope = valueToken();
			setState(1870);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,144,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(1866);
					match(ARGS_DELIMITER);
					setState(1867);
					((InScopeRequireArgsContext)_localctx).requireConstraint = requireConstraint();
					((InScopeRequireArgsContext)_localctx).requireConstraints.add(((InScopeRequireArgsContext)_localctx).requireConstraint);
					}
					} 
				}
				setState(1872);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,144,_ctx);
			}
			setState(1873);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 232, RULE_positionalParameter);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1875);
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

	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 234, RULE_namedParameter);
		try {
			enterOuterAlt(_localctx, 1);
			{
			setState(1877);
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

	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 236, RULE_variadicValueTokens);
		try {
			int _alt;
			setState(1889);
			_errHandler.sync(this);
			switch ( getInterpreter().adaptivePredict(_input,146,_ctx) ) {
			case 1:
				_localctx = new PositionalParameterVariadicValueTokensContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(1879);
				positionalParameter();
				}
				break;
			case 2:
				_localctx = new NamedParameterVariadicValueTokensContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(1880);
				namedParameter();
				}
				break;
			case 3:
				_localctx = new ExplicitVariadicValueTokensContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(1881);
				((ExplicitVariadicValueTokensContext)_localctx).valueToken = valueToken();
				((ExplicitVariadicValueTokensContext)_localctx).valueTokens.add(((ExplicitVariadicValueTokensContext)_localctx).valueToken);
				setState(1886);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,145,_ctx);
				while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
					if ( _alt==1 ) {
						{
						{
						setState(1882);
						match(ARGS_DELIMITER);
						setState(1883);
						((ExplicitVariadicValueTokensContext)_localctx).valueToken = valueToken();
						((ExplicitVariadicValueTokensContext)_localctx).valueTokens.add(((ExplicitVariadicValueTokensContext)_localctx).valueToken);
						}
						} 
					}
					setState(1888);
					_errHandler.sync(this);
					_alt = getInterpreter().adaptivePredict(_input,145,_ctx);
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
	@SuppressWarnings("CheckReturnValue")
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
		enterRule(_localctx, 238, RULE_valueToken);
		try {
			setState(1906);
			_errHandler.sync(this);
			switch (_input.LA(1)) {
			case POSITIONAL_PARAMETER:
				_localctx = new PositionalParameterValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 1);
				{
				setState(1891);
				positionalParameter();
				}
				break;
			case NAMED_PARAMETER:
				_localctx = new NamedParameterValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 2);
				{
				setState(1892);
				namedParameter();
				}
				break;
			case STRING:
				_localctx = new StringValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 3);
				{
				setState(1893);
				match(STRING);
				}
				break;
			case INT:
				_localctx = new IntValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 4);
				{
				setState(1894);
				match(INT);
				}
				break;
			case FLOAT:
				_localctx = new FloatValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 5);
				{
				setState(1895);
				match(FLOAT);
				}
				break;
			case BOOLEAN:
				_localctx = new BooleanValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 6);
				{
				setState(1896);
				match(BOOLEAN);
				}
				break;
			case DATE:
				_localctx = new DateValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 7);
				{
				setState(1897);
				match(DATE);
				}
				break;
			case TIME:
				_localctx = new TimeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 8);
				{
				setState(1898);
				match(TIME);
				}
				break;
			case DATE_TIME:
				_localctx = new DateTimeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 9);
				{
				setState(1899);
				match(DATE_TIME);
				}
				break;
			case OFFSET_DATE_TIME:
				_localctx = new OffsetDateTimeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 10);
				{
				setState(1900);
				match(OFFSET_DATE_TIME);
				}
				break;
			case FLOAT_NUMBER_RANGE:
				_localctx = new FloatNumberRangeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 11);
				{
				setState(1901);
				match(FLOAT_NUMBER_RANGE);
				}
				break;
			case INT_NUMBER_RANGE:
				_localctx = new IntNumberRangeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 12);
				{
				setState(1902);
				match(INT_NUMBER_RANGE);
				}
				break;
			case DATE_TIME_RANGE:
				_localctx = new DateTimeRangeValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 13);
				{
				setState(1903);
				match(DATE_TIME_RANGE);
				}
				break;
			case UUID:
				_localctx = new UuidValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 14);
				{
				setState(1904);
				match(UUID);
				}
				break;
			case ENUM:
				_localctx = new EnumValueTokenContext(_localctx);
				enterOuterAlt(_localctx, 15);
				{
				setState(1905);
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

	@SuppressWarnings("CheckReturnValue")
	public static class SingleRefReferenceContentWithAttributeNamesArgsContext extends ParserRuleContext {
		public ValueTokenContext first;
		public ValueTokenContext valueToken;
		public List<ValueTokenContext> values = new ArrayList<ValueTokenContext>();
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
		public SingleRefReferenceContentWithAttributeNamesArgsContext(ParserRuleContext parent, int invokingState) {
			super(parent, invokingState);
		}
		@Override public int getRuleIndex() { return RULE_singleRefReferenceContentWithAttributeNamesArgs; }
		@Override
		public void enterRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).enterSingleRefReferenceContentWithAttributeNamesArgs(this);
		}
		@Override
		public void exitRule(ParseTreeListener listener) {
			if ( listener instanceof EvitaQLListener ) ((EvitaQLListener)listener).exitSingleRefReferenceContentWithAttributeNamesArgs(this);
		}
		@Override
		public <T> T accept(ParseTreeVisitor<? extends T> visitor) {
			if ( visitor instanceof EvitaQLVisitor ) return ((EvitaQLVisitor<? extends T>)visitor).visitSingleRefReferenceContentWithAttributeNamesArgs(this);
			else return visitor.visitChildren(this);
		}
	}

	public final SingleRefReferenceContentWithAttributeNamesArgsContext singleRefReferenceContentWithAttributeNamesArgs() throws RecognitionException {
		SingleRefReferenceContentWithAttributeNamesArgsContext _localctx = new SingleRefReferenceContentWithAttributeNamesArgsContext(_ctx, getState());
		enterRule(_localctx, 240, RULE_singleRefReferenceContentWithAttributeNamesArgs);
		try {
			int _alt;
			enterOuterAlt(_localctx, 1);
			{
			setState(1908);
			argsOpening();
			setState(1909);
			((SingleRefReferenceContentWithAttributeNamesArgsContext)_localctx).first = valueToken();
			setState(1914);
			_errHandler.sync(this);
			_alt = getInterpreter().adaptivePredict(_input,148,_ctx);
			while ( _alt!=2 && _alt!=org.antlr.v4.runtime.atn.ATN.INVALID_ALT_NUMBER ) {
				if ( _alt==1 ) {
					{
					{
					setState(1910);
					match(ARGS_DELIMITER);
					setState(1911);
					((SingleRefReferenceContentWithAttributeNamesArgsContext)_localctx).valueToken = valueToken();
					((SingleRefReferenceContentWithAttributeNamesArgsContext)_localctx).values.add(((SingleRefReferenceContentWithAttributeNamesArgsContext)_localctx).valueToken);
					}
					} 
				}
				setState(1916);
				_errHandler.sync(this);
				_alt = getInterpreter().adaptivePredict(_input,148,_ctx);
			}
			setState(1917);
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

	public static final String _serializedATN =
		"\u0004\u0001\u00a3\u0780\u0002\u0000\u0007\u0000\u0002\u0001\u0007\u0001"+
		"\u0002\u0002\u0007\u0002\u0002\u0003\u0007\u0003\u0002\u0004\u0007\u0004"+
		"\u0002\u0005\u0007\u0005\u0002\u0006\u0007\u0006\u0002\u0007\u0007\u0007"+
		"\u0002\b\u0007\b\u0002\t\u0007\t\u0002\n\u0007\n\u0002\u000b\u0007\u000b"+
		"\u0002\f\u0007\f\u0002\r\u0007\r\u0002\u000e\u0007\u000e\u0002\u000f\u0007"+
		"\u000f\u0002\u0010\u0007\u0010\u0002\u0011\u0007\u0011\u0002\u0012\u0007"+
		"\u0012\u0002\u0013\u0007\u0013\u0002\u0014\u0007\u0014\u0002\u0015\u0007"+
		"\u0015\u0002\u0016\u0007\u0016\u0002\u0017\u0007\u0017\u0002\u0018\u0007"+
		"\u0018\u0002\u0019\u0007\u0019\u0002\u001a\u0007\u001a\u0002\u001b\u0007"+
		"\u001b\u0002\u001c\u0007\u001c\u0002\u001d\u0007\u001d\u0002\u001e\u0007"+
		"\u001e\u0002\u001f\u0007\u001f\u0002 \u0007 \u0002!\u0007!\u0002\"\u0007"+
		"\"\u0002#\u0007#\u0002$\u0007$\u0002%\u0007%\u0002&\u0007&\u0002\'\u0007"+
		"\'\u0002(\u0007(\u0002)\u0007)\u0002*\u0007*\u0002+\u0007+\u0002,\u0007"+
		",\u0002-\u0007-\u0002.\u0007.\u0002/\u0007/\u00020\u00070\u00021\u0007"+
		"1\u00022\u00072\u00023\u00073\u00024\u00074\u00025\u00075\u00026\u0007"+
		"6\u00027\u00077\u00028\u00078\u00029\u00079\u0002:\u0007:\u0002;\u0007"+
		";\u0002<\u0007<\u0002=\u0007=\u0002>\u0007>\u0002?\u0007?\u0002@\u0007"+
		"@\u0002A\u0007A\u0002B\u0007B\u0002C\u0007C\u0002D\u0007D\u0002E\u0007"+
		"E\u0002F\u0007F\u0002G\u0007G\u0002H\u0007H\u0002I\u0007I\u0002J\u0007"+
		"J\u0002K\u0007K\u0002L\u0007L\u0002M\u0007M\u0002N\u0007N\u0002O\u0007"+
		"O\u0002P\u0007P\u0002Q\u0007Q\u0002R\u0007R\u0002S\u0007S\u0002T\u0007"+
		"T\u0002U\u0007U\u0002V\u0007V\u0002W\u0007W\u0002X\u0007X\u0002Y\u0007"+
		"Y\u0002Z\u0007Z\u0002[\u0007[\u0002\\\u0007\\\u0002]\u0007]\u0002^\u0007"+
		"^\u0002_\u0007_\u0002`\u0007`\u0002a\u0007a\u0002b\u0007b\u0002c\u0007"+
		"c\u0002d\u0007d\u0002e\u0007e\u0002f\u0007f\u0002g\u0007g\u0002h\u0007"+
		"h\u0002i\u0007i\u0002j\u0007j\u0002k\u0007k\u0002l\u0007l\u0002m\u0007"+
		"m\u0002n\u0007n\u0002o\u0007o\u0002p\u0007p\u0002q\u0007q\u0002r\u0007"+
		"r\u0002s\u0007s\u0002t\u0007t\u0002u\u0007u\u0002v\u0007v\u0002w\u0007"+
		"w\u0002x\u0007x\u0001\u0000\u0001\u0000\u0001\u0000\u0001\u0001\u0001"+
		"\u0001\u0001\u0001\u0001\u0002\u0001\u0002\u0001\u0002\u0001\u0003\u0001"+
		"\u0003\u0001\u0003\u0001\u0004\u0001\u0004\u0001\u0004\u0001\u0005\u0001"+
		"\u0005\u0001\u0005\u0001\u0006\u0001\u0006\u0001\u0006\u0001\u0007\u0001"+
		"\u0007\u0001\u0007\u0001\u0007\u0003\u0007\u010c\b\u0007\u0001\b\u0001"+
		"\b\u0001\b\u0001\b\u0001\b\u0001\b\u0003\b\u0114\b\b\u0001\t\u0001\t\u0001"+
		"\t\u0001\t\u0001\t\u0001\t\u0001\t\u0003\t\u011d\b\t\u0001\t\u0001\t\u0001"+
		"\t\u0003\t\u0122\b\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0003\t\u0129"+
		"\b\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001"+
		"\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001"+
		"\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001"+
		"\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001"+
		"\t\u0001\t\u0003\t\u0150\b\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001"+
		"\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001"+
		"\t\u0001\t\u0001\t\u0003\t\u0163\b\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001"+
		"\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001"+
		"\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0003\t\u0178\b\t\u0001\t\u0001"+
		"\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0003\t\u0183"+
		"\b\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001"+
		"\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001"+
		"\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001"+
		"\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0001\t\u0003\t\u01a6\b\t\u0003"+
		"\t\u01a8\b\t\u0001\n\u0001\n\u0001\n\u0003\n\u01ad\b\n\u0001\n\u0001\n"+
		"\u0001\n\u0003\n\u01b2\b\n\u0001\n\u0001\n\u0001\n\u0001\n\u0001\n\u0001"+
		"\n\u0001\n\u0001\n\u0001\n\u0003\n\u01bd\b\n\u0001\n\u0001\n\u0001\n\u0001"+
		"\n\u0001\n\u0001\n\u0001\n\u0001\n\u0001\n\u0001\n\u0001\n\u0001\n\u0001"+
		"\n\u0001\n\u0001\n\u0003\n\u01ce\b\n\u0001\n\u0001\n\u0001\n\u0001\n\u0001"+
		"\n\u0001\n\u0001\n\u0001\n\u0001\n\u0001\n\u0001\n\u0001\n\u0001\n\u0001"+
		"\n\u0001\n\u0001\n\u0001\n\u0001\n\u0001\n\u0003\n\u01e3\b\n\u0001\n\u0001"+
		"\n\u0001\n\u0001\n\u0001\n\u0001\n\u0001\n\u0001\n\u0001\n\u0003\n\u01ee"+
		"\b\n\u0003\n\u01f0\b\n\u0001\u000b\u0001\u000b\u0001\u000b\u0003\u000b"+
		"\u01f5\b\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0003\u000b\u01fe\b\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0003\u000b\u0203\b\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0003\u000b\u0210\b\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0003\u000b"+
		"\u0219\b\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0003\u000b\u0230\b\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0003\u000b\u0265\b\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0003\u000b\u027a\b\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0003\u000b"+
		"\u028b\b\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0003\u000b\u02be\b\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b\u0001\u000b"+
		"\u0001\u000b\u0003\u000b\u02e1\b\u000b\u0001\u000b\u0001\u000b\u0003\u000b"+
		"\u02e5\b\u000b\u0001\f\u0001\f\u0001\f\u0005\f\u02ea\b\f\n\f\f\f\u02ed"+
		"\t\f\u0001\r\u0001\r\u0001\r\u0005\r\u02f2\b\r\n\r\f\r\u02f5\t\r\u0001"+
		"\u000e\u0001\u000e\u0001\u000e\u0005\u000e\u02fa\b\u000e\n\u000e\f\u000e"+
		"\u02fd\t\u000e\u0001\u000f\u0001\u000f\u0001\u000f\u0005\u000f\u0302\b"+
		"\u000f\n\u000f\f\u000f\u0305\t\u000f\u0001\u0010\u0001\u0010\u0001\u0011"+
		"\u0003\u0011\u030a\b\u0011\u0001\u0011\u0001\u0011\u0001\u0012\u0001\u0012"+
		"\u0001\u0012\u0001\u0012\u0005\u0012\u0312\b\u0012\n\u0012\f\u0012\u0315"+
		"\t\u0012\u0001\u0012\u0001\u0012\u0001\u0013\u0001\u0013\u0001\u0013\u0001"+
		"\u0014\u0001\u0014\u0001\u0014\u0001\u0014\u0005\u0014\u0320\b\u0014\n"+
		"\u0014\f\u0014\u0323\t\u0014\u0001\u0014\u0001\u0014\u0001\u0015\u0001"+
		"\u0015\u0001\u0015\u0001\u0015\u0005\u0015\u032b\b\u0015\n\u0015\f\u0015"+
		"\u032e\t\u0015\u0001\u0015\u0001\u0015\u0001\u0016\u0001\u0016\u0001\u0016"+
		"\u0001\u0016\u0001\u0017\u0001\u0017\u0001\u0017\u0001\u0017\u0001\u0017"+
		"\u0003\u0017\u033b\b\u0017\u0001\u0017\u0001\u0017\u0001\u0017\u0005\u0017"+
		"\u0340\b\u0017\n\u0017\f\u0017\u0343\t\u0017\u0003\u0017\u0345\b\u0017"+
		"\u0001\u0017\u0001\u0017\u0001\u0018\u0001\u0018\u0001\u0018\u0001\u0018"+
		"\u0005\u0018\u034d\b\u0018\n\u0018\f\u0018\u0350\t\u0018\u0001\u0018\u0001"+
		"\u0018\u0001\u0019\u0001\u0019\u0001\u0019\u0001\u0019\u0001\u001a\u0001"+
		"\u001a\u0001\u001a\u0001\u001a\u0005\u001a\u035c\b\u001a\n\u001a\f\u001a"+
		"\u035f\t\u001a\u0001\u001a\u0001\u001a\u0001\u001b\u0001\u001b\u0001\u001b"+
		"\u0001\u001b\u0001\u001c\u0001\u001c\u0001\u001c\u0001\u001c\u0001\u001c"+
		"\u0001\u001c\u0001\u001d\u0001\u001d\u0001\u001d\u0001\u001d\u0003\u001d"+
		"\u0371\b\u001d\u0001\u001d\u0001\u001d\u0001\u001e\u0001\u001e\u0001\u001e"+
		"\u0001\u001e\u0001\u001e\u0001\u001e\u0001\u001f\u0001\u001f\u0001\u001f"+
		"\u0001\u001f\u0003\u001f\u037f\b\u001f\u0001\u001f\u0001\u001f\u0001 "+
		"\u0001 \u0001 \u0001 \u0001 \u0001 \u0001 \u0001 \u0001!\u0001!\u0001"+
		"!\u0001!\u0001\"\u0001\"\u0001\"\u0001\"\u0001#\u0001#\u0001#\u0001#\u0001"+
		"#\u0001#\u0001$\u0001$\u0001$\u0001$\u0001%\u0001%\u0001%\u0001%\u0001"+
		"%\u0001%\u0001&\u0001&\u0001&\u0001&\u0001&\u0001&\u0003&\u03a9\b&\u0001"+
		"&\u0001&\u0001\'\u0001\'\u0001\'\u0001\'\u0003\'\u03b1\b\'\u0001\'\u0001"+
		"\'\u0001\'\u0001\'\u0001\'\u0003\'\u03b8\b\'\u0001\'\u0001\'\u0003\'\u03bc"+
		"\b\'\u0001\'\u0001\'\u0001(\u0001(\u0001(\u0001(\u0003(\u03c4\b(\u0001"+
		"(\u0001(\u0003(\u03c8\b(\u0001(\u0001(\u0001)\u0001)\u0001)\u0001)\u0001"+
		")\u0001)\u0001*\u0001*\u0001*\u0001*\u0005*\u03d6\b*\n*\f*\u03d9\t*\u0001"+
		"*\u0001*\u0001+\u0001+\u0001+\u0001+\u0004+\u03e1\b+\u000b+\f+\u03e2\u0001"+
		"+\u0001+\u0001,\u0001,\u0001,\u0001,\u0001,\u0001,\u0005,\u03ed\b,\n,"+
		"\f,\u03f0\t,\u0001,\u0001,\u0001-\u0001-\u0001-\u0001-\u0005-\u03f8\b"+
		"-\n-\f-\u03fb\t-\u0001-\u0001-\u0001.\u0001.\u0001.\u0001.\u0001.\u0005"+
		".\u0404\b.\n.\f.\u0407\t.\u0003.\u0409\b.\u0001.\u0001.\u0001/\u0001/"+
		"\u0001/\u0001/\u0005/\u0411\b/\n/\f/\u0414\t/\u0001/\u0001/\u00010\u0001"+
		"0\u00010\u00010\u00010\u00010\u00011\u00011\u00011\u00011\u00011\u0001"+
		"1\u00031\u0424\b1\u00011\u00011\u00012\u00012\u00012\u00012\u00012\u0001"+
		"2\u00013\u00013\u00013\u00013\u00033\u0432\b3\u00013\u00013\u00014\u0001"+
		"4\u00014\u00014\u00034\u043a\b4\u00014\u00014\u00014\u00015\u00015\u0001"+
		"5\u00015\u00035\u0443\b5\u00015\u00015\u00015\u00015\u00015\u00015\u0001"+
		"5\u00035\u044c\b5\u00015\u00015\u00016\u00016\u00016\u00016\u00036\u0454"+
		"\b6\u00016\u00016\u00016\u00016\u00016\u00036\u045b\b6\u00016\u00016\u0001"+
		"7\u00017\u00017\u00017\u00037\u0463\b7\u00017\u00017\u00017\u00017\u0001"+
		"7\u00017\u00017\u00017\u00017\u00037\u046e\b7\u00017\u00017\u00018\u0001"+
		"8\u00018\u00018\u00038\u0476\b8\u00018\u00018\u00018\u00018\u00018\u0003"+
		"8\u047d\b8\u00018\u00018\u00019\u00019\u00019\u00019\u00039\u0485\b9\u0001"+
		"9\u00019\u00019\u00019\u00019\u00019\u00019\u00019\u00019\u00039\u0490"+
		"\b9\u00019\u00019\u0001:\u0001:\u0001:\u0001:\u0003:\u0498\b:\u0001:\u0001"+
		":\u0001:\u0001:\u0001:\u0001:\u0001:\u0003:\u04a1\b:\u0001:\u0001:\u0001"+
		";\u0001;\u0001;\u0001;\u0003;\u04a9\b;\u0001;\u0001;\u0001;\u0001;\u0001"+
		";\u0001;\u0001;\u0001;\u0001;\u0001;\u0001;\u0003;\u04b6\b;\u0001;\u0001"+
		";\u0001<\u0001<\u0001<\u0001<\u0003<\u04be\b<\u0001<\u0001<\u0001<\u0001"+
		"<\u0001<\u0001=\u0001=\u0001=\u0001=\u0003=\u04c9\b=\u0001=\u0001=\u0001"+
		"=\u0001=\u0001=\u0001=\u0001=\u0001>\u0001>\u0001>\u0001>\u0003>\u04d6"+
		"\b>\u0001>\u0001>\u0001>\u0001>\u0001>\u0001>\u0001>\u0001>\u0001>\u0003"+
		">\u04e1\b>\u0001>\u0001>\u0001?\u0001?\u0001?\u0001?\u0003?\u04e9\b?\u0001"+
		"?\u0001?\u0001?\u0001?\u0001?\u0001?\u0001?\u0001?\u0001?\u0001@\u0001"+
		"@\u0001@\u0001@\u0003@\u04f8\b@\u0001@\u0001@\u0001@\u0001@\u0001@\u0001"+
		"@\u0001@\u0001@\u0001@\u0001@\u0001@\u0003@\u0505\b@\u0001@\u0001@\u0001"+
		"A\u0001A\u0001A\u0001A\u0003A\u050d\bA\u0001A\u0001A\u0001A\u0001A\u0001"+
		"A\u0001A\u0001A\u0001A\u0001A\u0001B\u0001B\u0001B\u0001B\u0003B\u051c"+
		"\bB\u0001B\u0001B\u0001B\u0001B\u0001B\u0001B\u0001B\u0001B\u0001B\u0001"+
		"B\u0001B\u0003B\u0529\bB\u0001B\u0001B\u0001C\u0001C\u0001C\u0001C\u0003"+
		"C\u0531\bC\u0001C\u0001C\u0001C\u0001C\u0001C\u0001C\u0001C\u0001C\u0001"+
		"C\u0001C\u0001C\u0001D\u0001D\u0001D\u0001D\u0003D\u0542\bD\u0001D\u0001"+
		"D\u0001D\u0001D\u0001D\u0001D\u0001D\u0001D\u0001D\u0001D\u0001D\u0001"+
		"D\u0001D\u0003D\u0551\bD\u0001D\u0001D\u0001E\u0001E\u0001E\u0001E\u0003"+
		"E\u0559\bE\u0001E\u0001E\u0001E\u0001E\u0003E\u055f\bE\u0001E\u0001E\u0001"+
		"E\u0001E\u0001E\u0001E\u0001E\u0003E\u0568\bE\u0001E\u0001E\u0001E\u0001"+
		"E\u0001E\u0001E\u0003E\u0570\bE\u0001E\u0001E\u0001F\u0001F\u0001F\u0001"+
		"F\u0001F\u0003F\u0579\bF\u0001F\u0001F\u0001F\u0001F\u0003F\u057f\bF\u0001"+
		"F\u0001F\u0001F\u0001F\u0003F\u0585\bF\u0001F\u0001F\u0001G\u0001G\u0001"+
		"G\u0001G\u0001G\u0003G\u058e\bG\u0001G\u0003G\u0591\bG\u0001G\u0001G\u0001"+
		"H\u0001H\u0001H\u0001H\u0003H\u0599\bH\u0001H\u0001H\u0001H\u0001H\u0001"+
		"H\u0001I\u0001I\u0001I\u0001I\u0003I\u05a4\bI\u0001I\u0001I\u0001I\u0001"+
		"I\u0001I\u0001I\u0001I\u0003I\u05ad\bI\u0001I\u0001I\u0001J\u0001J\u0001"+
		"J\u0001J\u0001J\u0003J\u05b6\bJ\u0001J\u0003J\u05b9\bJ\u0001J\u0001J\u0001"+
		"K\u0001K\u0001K\u0001K\u0003K\u05c1\bK\u0001K\u0001K\u0001K\u0001K\u0001"+
		"K\u0001L\u0001L\u0001L\u0001L\u0001M\u0001M\u0001M\u0001M\u0001M\u0001"+
		"M\u0003M\u05d2\bM\u0001M\u0001M\u0003M\u05d6\bM\u0001M\u0001M\u0001N\u0001"+
		"N\u0001N\u0001N\u0001N\u0001N\u0003N\u05e0\bN\u0001N\u0001N\u0001O\u0001"+
		"O\u0001O\u0001O\u0001O\u0001O\u0001P\u0001P\u0001P\u0001P\u0003P\u05ee"+
		"\bP\u0001P\u0001P\u0003P\u05f2\bP\u0001P\u0001P\u0001Q\u0001Q\u0001Q\u0001"+
		"Q\u0003Q\u05fa\bQ\u0001Q\u0001Q\u0001R\u0001R\u0001R\u0001R\u0001S\u0001"+
		"S\u0001S\u0001S\u0003S\u0606\bS\u0001S\u0001S\u0003S\u060a\bS\u0001S\u0001"+
		"S\u0003S\u060e\bS\u0001S\u0001S\u0003S\u0612\bS\u0001S\u0001S\u0001T\u0001"+
		"T\u0001T\u0001T\u0001T\u0003T\u061b\bT\u0001U\u0001U\u0001U\u0001U\u0001"+
		"U\u0003U\u0622\bU\u0001V\u0001V\u0001V\u0001V\u0001V\u0003V\u0629\bV\u0001"+
		"W\u0001W\u0001W\u0001W\u0001X\u0001X\u0001X\u0001X\u0001X\u0001X\u0003"+
		"X\u0635\bX\u0001X\u0001X\u0003X\u0639\bX\u0001X\u0001X\u0001Y\u0001Y\u0001"+
		"Y\u0001Y\u0001Y\u0001Y\u0003Y\u0643\bY\u0001Y\u0001Y\u0001Z\u0001Z\u0001"+
		"Z\u0001Z\u0001Z\u0001Z\u0001[\u0001[\u0001[\u0001[\u0003[\u0651\b[\u0001"+
		"[\u0001[\u0003[\u0655\b[\u0001[\u0001[\u0001\\\u0001\\\u0001\\\u0001\\"+
		"\u0003\\\u065d\b\\\u0001\\\u0001\\\u0001]\u0001]\u0001]\u0001]\u0001^"+
		"\u0001^\u0001^\u0001^\u0003^\u0669\b^\u0001^\u0001^\u0003^\u066d\b^\u0001"+
		"^\u0001^\u0003^\u0671\b^\u0001^\u0001^\u0003^\u0675\b^\u0001^\u0001^\u0001"+
		"_\u0001_\u0001_\u0005_\u067c\b_\n_\f_\u067f\t_\u0001`\u0001`\u0001`\u0001"+
		"`\u0001`\u0001`\u0001a\u0001a\u0001a\u0001a\u0001a\u0001a\u0003a\u068d"+
		"\ba\u0001a\u0001a\u0001a\u0001a\u0001a\u0001a\u0001a\u0001a\u0003a\u0697"+
		"\ba\u0001a\u0001a\u0001b\u0001b\u0001b\u0001b\u0001b\u0001b\u0001c\u0001"+
		"c\u0001c\u0001c\u0003c\u06a5\bc\u0001c\u0001c\u0001d\u0001d\u0001d\u0001"+
		"d\u0001e\u0001e\u0001e\u0001e\u0005e\u06b1\be\ne\fe\u06b4\te\u0001e\u0001"+
		"e\u0001f\u0001f\u0001f\u0001f\u0001f\u0001f\u0005f\u06be\bf\nf\ff\u06c1"+
		"\tf\u0001f\u0001f\u0001g\u0001g\u0001g\u0001g\u0004g\u06c9\bg\u000bg\f"+
		"g\u06ca\u0001g\u0001g\u0001h\u0001h\u0001h\u0001h\u0004h\u06d3\bh\u000b"+
		"h\fh\u06d4\u0001h\u0001h\u0001i\u0001i\u0001i\u0001i\u0001i\u0001i\u0004"+
		"i\u06df\bi\u000bi\fi\u06e0\u0001i\u0001i\u0001j\u0001j\u0001j\u0001j\u0001"+
		"j\u0001j\u0004j\u06eb\bj\u000bj\fj\u06ec\u0001j\u0001j\u0001k\u0001k\u0001"+
		"k\u0001k\u0001k\u0001k\u0001k\u0001k\u0004k\u06f9\bk\u000bk\fk\u06fa\u0001"+
		"k\u0001k\u0001l\u0001l\u0001l\u0001l\u0005l\u0703\bl\nl\fl\u0706\tl\u0001"+
		"l\u0001l\u0001m\u0001m\u0001m\u0001m\u0001m\u0001m\u0001n\u0001n\u0001"+
		"n\u0001n\u0003n\u0714\bn\u0001n\u0001n\u0001n\u0003n\u0719\bn\u0001n\u0001"+
		"n\u0001o\u0001o\u0001o\u0001o\u0005o\u0721\bo\no\fo\u0724\to\u0001o\u0001"+
		"o\u0001p\u0001p\u0001p\u0001p\u0005p\u072c\bp\np\fp\u072f\tp\u0001p\u0001"+
		"p\u0001q\u0001q\u0001q\u0001q\u0005q\u0737\bq\nq\fq\u073a\tq\u0001q\u0001"+
		"q\u0001r\u0001r\u0001r\u0001r\u0005r\u0742\br\nr\fr\u0745\tr\u0001r\u0001"+
		"r\u0001s\u0001s\u0001s\u0001s\u0005s\u074d\bs\ns\fs\u0750\ts\u0001s\u0001"+
		"s\u0001t\u0001t\u0001u\u0001u\u0001v\u0001v\u0001v\u0001v\u0001v\u0005"+
		"v\u075d\bv\nv\fv\u0760\tv\u0003v\u0762\bv\u0001w\u0001w\u0001w\u0001w"+
		"\u0001w\u0001w\u0001w\u0001w\u0001w\u0001w\u0001w\u0001w\u0001w\u0001"+
		"w\u0001w\u0003w\u0773\bw\u0001x\u0001x\u0001x\u0001x\u0005x\u0779\bx\n"+
		"x\fx\u077c\tx\u0001x\u0001x\u0001x\u0000\u0000y\u0000\u0002\u0004\u0006"+
		"\b\n\f\u000e\u0010\u0012\u0014\u0016\u0018\u001a\u001c\u001e \"$&(*,."+
		"02468:<>@BDFHJLNPRTVXZ\\^`bdfhjlnprtvxz|~\u0080\u0082\u0084\u0086\u0088"+
		"\u008a\u008c\u008e\u0090\u0092\u0094\u0096\u0098\u009a\u009c\u009e\u00a0"+
		"\u00a2\u00a4\u00a6\u00a8\u00aa\u00ac\u00ae\u00b0\u00b2\u00b4\u00b6\u00b8"+
		"\u00ba\u00bc\u00be\u00c0\u00c2\u00c4\u00c6\u00c8\u00ca\u00cc\u00ce\u00d0"+
		"\u00d2\u00d4\u00d6\u00d8\u00da\u00dc\u00de\u00e0\u00e2\u00e4\u00e6\u00e8"+
		"\u00ea\u00ec\u00ee\u00f0\u0000\u0000\u0868\u0000\u00f2\u0001\u0000\u0000"+
		"\u0000\u0002\u00f5\u0001\u0000\u0000\u0000\u0004\u00f8\u0001\u0000\u0000"+
		"\u0000\u0006\u00fb\u0001\u0000\u0000\u0000\b\u00fe\u0001\u0000\u0000\u0000"+
		"\n\u0101\u0001\u0000\u0000\u0000\f\u0104\u0001\u0000\u0000\u0000\u000e"+
		"\u010b\u0001\u0000\u0000\u0000\u0010\u0113\u0001\u0000\u0000\u0000\u0012"+
		"\u01a7\u0001\u0000\u0000\u0000\u0014\u01ef\u0001\u0000\u0000\u0000\u0016"+
		"\u02e4\u0001\u0000\u0000\u0000\u0018\u02e6\u0001\u0000\u0000\u0000\u001a"+
		"\u02ee\u0001\u0000\u0000\u0000\u001c\u02f6\u0001\u0000\u0000\u0000\u001e"+
		"\u02fe\u0001\u0000\u0000\u0000 \u0306\u0001\u0000\u0000\u0000\"\u0309"+
		"\u0001\u0000\u0000\u0000$\u030d\u0001\u0000\u0000\u0000&\u0318\u0001\u0000"+
		"\u0000\u0000(\u031b\u0001\u0000\u0000\u0000*\u0326\u0001\u0000\u0000\u0000"+
		",\u0331\u0001\u0000\u0000\u0000.\u0335\u0001\u0000\u0000\u00000\u0348"+
		"\u0001\u0000\u0000\u00002\u0353\u0001\u0000\u0000\u00004\u0357\u0001\u0000"+
		"\u0000\u00006\u0362\u0001\u0000\u0000\u00008\u0366\u0001\u0000\u0000\u0000"+
		":\u036c\u0001\u0000\u0000\u0000<\u0374\u0001\u0000\u0000\u0000>\u037a"+
		"\u0001\u0000\u0000\u0000@\u0382\u0001\u0000\u0000\u0000B\u038a\u0001\u0000"+
		"\u0000\u0000D\u038e\u0001\u0000\u0000\u0000F\u0392\u0001\u0000\u0000\u0000"+
		"H\u0398\u0001\u0000\u0000\u0000J\u039c\u0001\u0000\u0000\u0000L\u03a2"+
		"\u0001\u0000\u0000\u0000N\u03ac\u0001\u0000\u0000\u0000P\u03bf\u0001\u0000"+
		"\u0000\u0000R\u03cb\u0001\u0000\u0000\u0000T\u03d1\u0001\u0000\u0000\u0000"+
		"V\u03dc\u0001\u0000\u0000\u0000X\u03e6\u0001\u0000\u0000\u0000Z\u03f3"+
		"\u0001\u0000\u0000\u0000\\\u03fe\u0001\u0000\u0000\u0000^\u040c\u0001"+
		"\u0000\u0000\u0000`\u0417\u0001\u0000\u0000\u0000b\u041d\u0001\u0000\u0000"+
		"\u0000d\u0427\u0001\u0000\u0000\u0000f\u042d\u0001\u0000\u0000\u0000h"+
		"\u0435\u0001\u0000\u0000\u0000j\u043e\u0001\u0000\u0000\u0000l\u044f\u0001"+
		"\u0000\u0000\u0000n\u045e\u0001\u0000\u0000\u0000p\u0471\u0001\u0000\u0000"+
		"\u0000r\u0480\u0001\u0000\u0000\u0000t\u0493\u0001\u0000\u0000\u0000v"+
		"\u04a4\u0001\u0000\u0000\u0000x\u04b9\u0001\u0000\u0000\u0000z\u04c4\u0001"+
		"\u0000\u0000\u0000|\u04d1\u0001\u0000\u0000\u0000~\u04e4\u0001\u0000\u0000"+
		"\u0000\u0080\u04f3\u0001\u0000\u0000\u0000\u0082\u0508\u0001\u0000\u0000"+
		"\u0000\u0084\u0517\u0001\u0000\u0000\u0000\u0086\u052c\u0001\u0000\u0000"+
		"\u0000\u0088\u053d\u0001\u0000\u0000\u0000\u008a\u0554\u0001\u0000\u0000"+
		"\u0000\u008c\u0573\u0001\u0000\u0000\u0000\u008e\u0588\u0001\u0000\u0000"+
		"\u0000\u0090\u0594\u0001\u0000\u0000\u0000\u0092\u059f\u0001\u0000\u0000"+
		"\u0000\u0094\u05b0\u0001\u0000\u0000\u0000\u0096\u05bc\u0001\u0000\u0000"+
		"\u0000\u0098\u05c7\u0001\u0000\u0000\u0000\u009a\u05cb\u0001\u0000\u0000"+
		"\u0000\u009c\u05d9\u0001\u0000\u0000\u0000\u009e\u05e3\u0001\u0000\u0000"+
		"\u0000\u00a0\u05e9\u0001\u0000\u0000\u0000\u00a2\u05f5\u0001\u0000\u0000"+
		"\u0000\u00a4\u05fd\u0001\u0000\u0000\u0000\u00a6\u0601\u0001\u0000\u0000"+
		"\u0000\u00a8\u061a\u0001\u0000\u0000\u0000\u00aa\u0621\u0001\u0000\u0000"+
		"\u0000\u00ac\u0628\u0001\u0000\u0000\u0000\u00ae\u062a\u0001\u0000\u0000"+
		"\u0000\u00b0\u062e\u0001\u0000\u0000\u0000\u00b2\u063c\u0001\u0000\u0000"+
		"\u0000\u00b4\u0646\u0001\u0000\u0000\u0000\u00b6\u064c\u0001\u0000\u0000"+
		"\u0000\u00b8\u0658\u0001\u0000\u0000\u0000\u00ba\u0660\u0001\u0000\u0000"+
		"\u0000\u00bc\u0664\u0001\u0000\u0000\u0000\u00be\u0678\u0001\u0000\u0000"+
		"\u0000\u00c0\u0680\u0001\u0000\u0000\u0000\u00c2\u0686\u0001\u0000\u0000"+
		"\u0000\u00c4\u069a\u0001\u0000\u0000\u0000\u00c6\u06a0\u0001\u0000\u0000"+
		"\u0000\u00c8\u06a8\u0001\u0000\u0000\u0000\u00ca\u06ac\u0001\u0000\u0000"+
		"\u0000\u00cc\u06b7\u0001\u0000\u0000\u0000\u00ce\u06c4\u0001\u0000\u0000"+
		"\u0000\u00d0\u06ce\u0001\u0000\u0000\u0000\u00d2\u06d8\u0001\u0000\u0000"+
		"\u0000\u00d4\u06e4\u0001\u0000\u0000\u0000\u00d6\u06f0\u0001\u0000\u0000"+
		"\u0000\u00d8\u06fe\u0001\u0000\u0000\u0000\u00da\u0709\u0001\u0000\u0000"+
		"\u0000\u00dc\u070f\u0001\u0000\u0000\u0000\u00de\u071c\u0001\u0000\u0000"+
		"\u0000\u00e0\u0727\u0001\u0000\u0000\u0000\u00e2\u0732\u0001\u0000\u0000"+
		"\u0000\u00e4\u073d\u0001\u0000\u0000\u0000\u00e6\u0748\u0001\u0000\u0000"+
		"\u0000\u00e8\u0753\u0001\u0000\u0000\u0000\u00ea\u0755\u0001\u0000\u0000"+
		"\u0000\u00ec\u0761\u0001\u0000\u0000\u0000\u00ee\u0772\u0001\u0000\u0000"+
		"\u0000\u00f0\u0774\u0001\u0000\u0000\u0000\u00f2\u00f3\u0003\f\u0006\u0000"+
		"\u00f3\u00f4\u0005\u0000\u0000\u0001\u00f4\u0001\u0001\u0000\u0000\u0000"+
		"\u00f5\u00f6\u0003\u0018\f\u0000\u00f6\u00f7\u0005\u0000\u0000\u0001\u00f7"+
		"\u0003\u0001\u0000\u0000\u0000\u00f8\u00f9\u0003\u001a\r\u0000\u00f9\u00fa"+
		"\u0005\u0000\u0000\u0001\u00fa\u0005\u0001\u0000\u0000\u0000\u00fb\u00fc"+
		"\u0003\u001c\u000e\u0000\u00fc\u00fd\u0005\u0000\u0000\u0001\u00fd\u0007"+
		"\u0001\u0000\u0000\u0000\u00fe\u00ff\u0003\u001e\u000f\u0000\u00ff\u0100"+
		"\u0005\u0000\u0000\u0001\u0100\t\u0001\u0000\u0000\u0000\u0101\u0102\u0003"+
		"\u00eew\u0000\u0102\u0103\u0005\u0000\u0000\u0001\u0103\u000b\u0001\u0000"+
		"\u0000\u0000\u0104\u0105\u0005\u0001\u0000\u0000\u0105\u0106\u0003$\u0012"+
		"\u0000\u0106\r\u0001\u0000\u0000\u0000\u0107\u010c\u0003\u0010\b\u0000"+
		"\u0108\u010c\u0003\u0012\t\u0000\u0109\u010c\u0003\u0014\n\u0000\u010a"+
		"\u010c\u0003\u0016\u000b\u0000\u010b\u0107\u0001\u0000\u0000\u0000\u010b"+
		"\u0108\u0001\u0000\u0000\u0000\u010b\u0109\u0001\u0000\u0000\u0000\u010b"+
		"\u010a\u0001\u0000\u0000\u0000\u010c\u000f\u0001\u0000\u0000\u0000\u010d"+
		"\u010e\u0005\u0002\u0000\u0000\u010e\u0114\u0003(\u0014\u0000\u010f\u0110"+
		"\u0005\u0003\u0000\u0000\u0110\u0114\u00036\u001b\u0000\u0111\u0112\u0005"+
		"\u0004\u0000\u0000\u0112\u0114\u00038\u001c\u0000\u0113\u010d\u0001\u0000"+
		"\u0000\u0000\u0113\u010f\u0001\u0000\u0000\u0000\u0113\u0111\u0001\u0000"+
		"\u0000\u0000\u0114\u0011\u0001\u0000\u0000\u0000\u0115\u0116\u0005\u0005"+
		"\u0000\u0000\u0116\u01a8\u0003*\u0015\u0000\u0117\u0118\u0005\u0006\u0000"+
		"\u0000\u0118\u01a8\u0003*\u0015\u0000\u0119\u011c\u0005\u0007\u0000\u0000"+
		"\u011a\u011d\u0003&\u0013\u0000\u011b\u011d\u0003*\u0015\u0000\u011c\u011a"+
		"\u0001\u0000\u0000\u0000\u011c\u011b\u0001\u0000\u0000\u0000\u011d\u01a8"+
		"\u0001\u0000\u0000\u0000\u011e\u0121\u0005\b\u0000\u0000\u011f\u0122\u0003"+
		"&\u0013\u0000\u0120\u0122\u0003*\u0015\u0000\u0121\u011f\u0001\u0000\u0000"+
		"\u0000\u0121\u0120\u0001\u0000\u0000\u0000\u0122\u01a8\u0001\u0000\u0000"+
		"\u0000\u0123\u0124\u0005\t\u0000\u0000\u0124\u01a8\u0003,\u0016\u0000"+
		"\u0125\u0128\u0005\n\u0000\u0000\u0126\u0129\u0003&\u0013\u0000\u0127"+
		"\u0129\u0003*\u0015\u0000\u0128\u0126\u0001\u0000\u0000\u0000\u0128\u0127"+
		"\u0001\u0000\u0000\u0000\u0129\u01a8\u0001\u0000\u0000\u0000\u012a\u012b"+
		"\u0005\u000b\u0000\u0000\u012b\u01a8\u00038\u001c\u0000\u012c\u012d\u0005"+
		"\f\u0000\u0000\u012d\u01a8\u00038\u001c\u0000\u012e\u012f\u0005\r\u0000"+
		"\u0000\u012f\u01a8\u00038\u001c\u0000\u0130\u0131\u0005\u000e\u0000\u0000"+
		"\u0131\u01a8\u00038\u001c\u0000\u0132\u0133\u0005\u000f\u0000\u0000\u0133"+
		"\u01a8\u00038\u001c\u0000\u0134\u0135\u0005\u0010\u0000\u0000\u0135\u01a8"+
		"\u0003@ \u0000\u0136\u0137\u0005\u0011\u0000\u0000\u0137\u01a8\u0003>"+
		"\u001f\u0000\u0138\u0139\u0005\u0012\u0000\u0000\u0139\u01a8\u00038\u001c"+
		"\u0000\u013a\u013b\u0005\u0013\u0000\u0000\u013b\u01a8\u00038\u001c\u0000"+
		"\u013c\u013d\u0005\u0014\u0000\u0000\u013d\u01a8\u00038\u001c\u0000\u013e"+
		"\u013f\u0005\u0015\u0000\u0000\u013f\u01a8\u00036\u001b\u0000\u0140\u0141"+
		"\u0005\u0016\u0000\u0000\u0141\u01a8\u00036\u001b\u0000\u0142\u0143\u0005"+
		"\u0017\u0000\u0000\u0143\u01a8\u00038\u001c\u0000\u0144\u0145\u0005\u0018"+
		"\u0000\u0000\u0145\u01a8\u00036\u001b\u0000\u0146\u0147\u0005\u0019\u0000"+
		"\u0000\u0147\u01a8\u00036\u001b\u0000\u0148\u0149\u0005\u001a\u0000\u0000"+
		"\u0149\u01a8\u00038\u001c\u0000\u014a\u014b\u0005\u001b\u0000\u0000\u014b"+
		"\u01a8\u00036\u001b\u0000\u014c\u014f\u0005\u001c\u0000\u0000\u014d\u0150"+
		"\u0003&\u0013\u0000\u014e\u0150\u0003D\"\u0000\u014f\u014d\u0001\u0000"+
		"\u0000\u0000\u014f\u014e\u0001\u0000\u0000\u0000\u0150\u01a8\u0001\u0000"+
		"\u0000\u0000\u0151\u0152\u0005\u001d\u0000\u0000\u0152\u01a8\u0003B!\u0000"+
		"\u0153\u0154\u0005\u001e\u0000\u0000\u0154\u01a8\u0003B!\u0000\u0155\u0156"+
		"\u0005\u001f\u0000\u0000\u0156\u01a8\u0003B!\u0000\u0157\u0158\u0005 "+
		"\u0000\u0000\u0158\u01a8\u0003B!\u0000\u0159\u015a\u0005!\u0000\u0000"+
		"\u015a\u01a8\u0003F#\u0000\u015b\u015c\u0005\"\u0000\u0000\u015c\u01a8"+
		"\u0003B!\u0000\u015d\u015e\u0005#\u0000\u0000\u015e\u01a8\u0003B!\u0000"+
		"\u015f\u0162\u0005$\u0000\u0000\u0160\u0163\u0003&\u0013\u0000\u0161\u0163"+
		"\u0003H$\u0000\u0162\u0160\u0001\u0000\u0000\u0000\u0162\u0161\u0001\u0000"+
		"\u0000\u0000\u0163\u01a8\u0001\u0000\u0000\u0000\u0164\u0165\u0005%\u0000"+
		"\u0000\u0165\u01a8\u0003&\u0013\u0000\u0166\u0167\u0005&\u0000\u0000\u0167"+
		"\u01a8\u0003B!\u0000\u0168\u0169\u0005\'\u0000\u0000\u0169\u01a8\u0003"+
		"F#\u0000\u016a\u016b\u0005(\u0000\u0000\u016b\u01a8\u0003L&\u0000\u016c"+
		"\u016d\u0005)\u0000\u0000\u016d\u01a8\u0003N\'\u0000\u016e\u016f\u0005"+
		"*\u0000\u0000\u016f\u01a8\u0003&\u0013\u0000\u0170\u0171\u0005+\u0000"+
		"\u0000\u0171\u01a8\u0003,\u0016\u0000\u0172\u0173\u0005,\u0000\u0000\u0173"+
		"\u01a8\u0003,\u0016\u0000\u0174\u0177\u0005-\u0000\u0000\u0175\u0178\u0003"+
		"6\u001b\u0000\u0176\u0178\u0003J%\u0000\u0177\u0175\u0001\u0000\u0000"+
		"\u0000\u0177\u0176\u0001\u0000\u0000\u0000\u0178\u01a8\u0001\u0000\u0000"+
		"\u0000\u0179\u017a\u0005.\u0000\u0000\u017a\u01a8\u0003X,\u0000\u017b"+
		"\u017c\u0005/\u0000\u0000\u017c\u01a8\u0003Z-\u0000\u017d\u017e\u0005"+
		"0\u0000\u0000\u017e\u01a8\u0003\\.\u0000\u017f\u0182\u00051\u0000\u0000"+
		"\u0180\u0183\u0003&\u0013\u0000\u0181\u0183\u0003^/\u0000\u0182\u0180"+
		"\u0001\u0000\u0000\u0000\u0182\u0181\u0001\u0000\u0000\u0000\u0183\u01a8"+
		"\u0001\u0000\u0000\u0000\u0184\u0185\u00052\u0000\u0000\u0185\u01a8\u0003"+
		"&\u0013\u0000\u0186\u0187\u00053\u0000\u0000\u0187\u01a8\u0003*\u0015"+
		"\u0000\u0188\u0189\u00054\u0000\u0000\u0189\u01a8\u0003*\u0015\u0000\u018a"+
		"\u018b\u00055\u0000\u0000\u018b\u01a8\u0003&\u0013\u0000\u018c\u018d\u0005"+
		"6\u0000\u0000\u018d\u01a8\u0003*\u0015\u0000\u018e\u018f\u00057\u0000"+
		"\u0000\u018f\u01a8\u0003*\u0015\u0000\u0190\u0191\u00058\u0000\u0000\u0191"+
		"\u01a8\u0003*\u0015\u0000\u0192\u0193\u00059\u0000\u0000\u0193\u01a8\u0003"+
		"\u00deo\u0000\u0194\u0195\u0005:\u0000\u0000\u0195\u01a8\u0003D\"\u0000"+
		"\u0196\u0197\u0005;\u0000\u0000\u0197\u01a8\u0003\u00e2q\u0000\u0198\u0199"+
		"\u0005<\u0000\u0000\u0199\u01a8\u0003H$\u0000\u019a\u019b\u0005=\u0000"+
		"\u0000\u019b\u01a8\u0003&\u0013\u0000\u019c\u019d\u0005>\u0000\u0000\u019d"+
		"\u01a8\u0003H$\u0000\u019e\u019f\u0005?\u0000\u0000\u019f\u01a8\u0003"+
		"&\u0013\u0000\u01a0\u01a1\u0005@\u0000\u0000\u01a1\u01a8\u0003\u00e0p"+
		"\u0000\u01a2\u01a5\u0005A\u0000\u0000\u01a3\u01a6\u0003&\u0013\u0000\u01a4"+
		"\u01a6\u0003*\u0015\u0000\u01a5\u01a3\u0001\u0000\u0000\u0000\u01a5\u01a4"+
		"\u0001\u0000\u0000\u0000\u01a6\u01a8\u0001\u0000\u0000\u0000\u01a7\u0115"+
		"\u0001\u0000\u0000\u0000\u01a7\u0117\u0001\u0000\u0000\u0000\u01a7\u0119"+
		"\u0001\u0000\u0000\u0000\u01a7\u011e\u0001\u0000\u0000\u0000\u01a7\u0123"+
		"\u0001\u0000\u0000\u0000\u01a7\u0125\u0001\u0000\u0000\u0000\u01a7\u012a"+
		"\u0001\u0000\u0000\u0000\u01a7\u012c\u0001\u0000\u0000\u0000\u01a7\u012e"+
		"\u0001\u0000\u0000\u0000\u01a7\u0130\u0001\u0000\u0000\u0000\u01a7\u0132"+
		"\u0001\u0000\u0000\u0000\u01a7\u0134\u0001\u0000\u0000\u0000\u01a7\u0136"+
		"\u0001\u0000\u0000\u0000\u01a7\u0138\u0001\u0000\u0000\u0000\u01a7\u013a"+
		"\u0001\u0000\u0000\u0000\u01a7\u013c\u0001\u0000\u0000\u0000\u01a7\u013e"+
		"\u0001\u0000\u0000\u0000\u01a7\u0140\u0001\u0000\u0000\u0000\u01a7\u0142"+
		"\u0001\u0000\u0000\u0000\u01a7\u0144\u0001\u0000\u0000\u0000\u01a7\u0146"+
		"\u0001\u0000\u0000\u0000\u01a7\u0148\u0001\u0000\u0000\u0000\u01a7\u014a"+
		"\u0001\u0000\u0000\u0000\u01a7\u014c\u0001\u0000\u0000\u0000\u01a7\u0151"+
		"\u0001\u0000\u0000\u0000\u01a7\u0153\u0001\u0000\u0000\u0000\u01a7\u0155"+
		"\u0001\u0000\u0000\u0000\u01a7\u0157\u0001\u0000\u0000\u0000\u01a7\u0159"+
		"\u0001\u0000\u0000\u0000\u01a7\u015b\u0001\u0000\u0000\u0000\u01a7\u015d"+
		"\u0001\u0000\u0000\u0000\u01a7\u015f\u0001\u0000\u0000\u0000\u01a7\u0164"+
		"\u0001\u0000\u0000\u0000\u01a7\u0166\u0001\u0000\u0000\u0000\u01a7\u0168"+
		"\u0001\u0000\u0000\u0000\u01a7\u016a\u0001\u0000\u0000\u0000\u01a7\u016c"+
		"\u0001\u0000\u0000\u0000\u01a7\u016e\u0001\u0000\u0000\u0000\u01a7\u0170"+
		"\u0001\u0000\u0000\u0000\u01a7\u0172\u0001\u0000\u0000\u0000\u01a7\u0174"+
		"\u0001\u0000\u0000\u0000\u01a7\u0179\u0001\u0000\u0000\u0000\u01a7\u017b"+
		"\u0001\u0000\u0000\u0000\u01a7\u017d\u0001\u0000\u0000\u0000\u01a7\u017f"+
		"\u0001\u0000\u0000\u0000\u01a7\u0184\u0001\u0000\u0000\u0000\u01a7\u0186"+
		"\u0001\u0000\u0000\u0000\u01a7\u0188\u0001\u0000\u0000\u0000\u01a7\u018a"+
		"\u0001\u0000\u0000\u0000\u01a7\u018c\u0001\u0000\u0000\u0000\u01a7\u018e"+
		"\u0001\u0000\u0000\u0000\u01a7\u0190\u0001\u0000\u0000\u0000\u01a7\u0192"+
		"\u0001\u0000\u0000\u0000\u01a7\u0194\u0001\u0000\u0000\u0000\u01a7\u0196"+
		"\u0001\u0000\u0000\u0000\u01a7\u0198\u0001\u0000\u0000\u0000\u01a7\u019a"+
		"\u0001\u0000\u0000\u0000\u01a7\u019c\u0001\u0000\u0000\u0000\u01a7\u019e"+
		"\u0001\u0000\u0000\u0000\u01a7\u01a0\u0001\u0000\u0000\u0000\u01a7\u01a2"+
		"\u0001\u0000\u0000\u0000\u01a8\u0013\u0001\u0000\u0000\u0000\u01a9\u01ac"+
		"\u0005B\u0000\u0000\u01aa\u01ad\u0003&\u0013\u0000\u01ab\u01ad\u00030"+
		"\u0018\u0000\u01ac\u01aa\u0001\u0000\u0000\u0000\u01ac\u01ab\u0001\u0000"+
		"\u0000\u0000\u01ad\u01f0\u0001\u0000\u0000\u0000\u01ae\u01b1\u0005C\u0000"+
		"\u0000\u01af\u01b2\u0003&\u0013\u0000\u01b0\u01b2\u00030\u0018\u0000\u01b1"+
		"\u01af\u0001\u0000\u0000\u0000\u01b1\u01b0\u0001\u0000\u0000\u0000\u01b2"+
		"\u01f0\u0001\u0000\u0000\u0000\u01b3\u01b4\u0005D\u0000\u0000\u01b4\u01f0"+
		"\u0003:\u001d\u0000\u01b5\u01b6\u0005E\u0000\u0000\u01b6\u01f0\u0003`"+
		"0\u0000\u01b7\u01b8\u0005F\u0000\u0000\u01b8\u01f0\u00036\u001b\u0000"+
		"\u01b9\u01bc\u0005G\u0000\u0000\u01ba\u01bd\u0003&\u0013\u0000\u01bb\u01bd"+
		"\u0003B!\u0000\u01bc\u01ba\u0001\u0000\u0000\u0000\u01bc\u01bb\u0001\u0000"+
		"\u0000\u0000\u01bd\u01f0\u0001\u0000\u0000\u0000\u01be\u01bf\u0005H\u0000"+
		"\u0000\u01bf\u01f0\u0003D\"\u0000\u01c0\u01c1\u0005I\u0000\u0000\u01c1"+
		"\u01f0\u0003&\u0013\u0000\u01c2\u01c3\u0005J\u0000\u0000\u01c3\u01f0\u0003"+
		"B!\u0000\u01c4\u01c5\u0005K\u0000\u0000\u01c5\u01f0\u0003V+\u0000\u01c6"+
		"\u01c7\u0005L\u0000\u0000\u01c7\u01f0\u0003.\u0017\u0000\u01c8\u01c9\u0005"+
		"M\u0000\u0000\u01c9\u01f0\u00030\u0018\u0000\u01ca\u01cd\u0005N\u0000"+
		"\u0000\u01cb\u01ce\u0003&\u0013\u0000\u01cc\u01ce\u0003B!\u0000\u01cd"+
		"\u01cb\u0001\u0000\u0000\u0000\u01cd\u01cc\u0001\u0000\u0000\u0000\u01ce"+
		"\u01f0\u0001\u0000\u0000\u0000\u01cf\u01d0\u0005O\u0000\u0000\u01d0\u01f0"+
		"\u0003D\"\u0000\u01d1\u01d2\u0005P\u0000\u0000\u01d2\u01f0\u0003&\u0013"+
		"\u0000\u01d3\u01d4\u0005Q\u0000\u0000\u01d4\u01f0\u00030\u0018\u0000\u01d5"+
		"\u01d6\u0005R\u0000\u0000\u01d6\u01f0\u00030\u0018\u0000\u01d7\u01d8\u0005"+
		"S\u0000\u0000\u01d8\u01f0\u00030\u0018\u0000\u01d9\u01da\u0005T\u0000"+
		"\u0000\u01da\u01f0\u0003\u00dcn\u0000\u01db\u01dc\u0005U\u0000\u0000\u01dc"+
		"\u01f0\u0003B!\u0000\u01dd\u01de\u00059\u0000\u0000\u01de\u01f0\u0003"+
		"\u00e4r\u0000\u01df\u01e2\u0005V\u0000\u0000\u01e0\u01e3\u0003&\u0013"+
		"\u0000\u01e1\u01e3\u00030\u0018\u0000\u01e2\u01e0\u0001\u0000\u0000\u0000"+
		"\u01e2\u01e1\u0001\u0000\u0000\u0000\u01e3\u01f0\u0001\u0000\u0000\u0000"+
		"\u01e4\u01e5\u0005W\u0000\u0000\u01e5\u01f0\u00038\u001c\u0000\u01e6\u01e7"+
		"\u0005X\u0000\u0000\u01e7\u01f0\u00038\u001c\u0000\u01e8\u01e9\u0005Y"+
		"\u0000\u0000\u01e9\u01f0\u0003T*\u0000\u01ea\u01ed\u0005Z\u0000\u0000"+
		"\u01eb\u01ee\u0003&\u0013\u0000\u01ec\u01ee\u00030\u0018\u0000\u01ed\u01eb"+
		"\u0001\u0000\u0000\u0000\u01ed\u01ec\u0001\u0000\u0000\u0000\u01ee\u01f0"+
		"\u0001\u0000\u0000\u0000\u01ef\u01a9\u0001\u0000\u0000\u0000\u01ef\u01ae"+
		"\u0001\u0000\u0000\u0000\u01ef\u01b3\u0001\u0000\u0000\u0000\u01ef\u01b5"+
		"\u0001\u0000\u0000\u0000\u01ef\u01b7\u0001\u0000\u0000\u0000\u01ef\u01b9"+
		"\u0001\u0000\u0000\u0000\u01ef\u01be\u0001\u0000\u0000\u0000\u01ef\u01c0"+
		"\u0001\u0000\u0000\u0000\u01ef\u01c2\u0001\u0000\u0000\u0000\u01ef\u01c4"+
		"\u0001\u0000\u0000\u0000\u01ef\u01c6\u0001\u0000\u0000\u0000\u01ef\u01c8"+
		"\u0001\u0000\u0000\u0000\u01ef\u01ca\u0001\u0000\u0000\u0000\u01ef\u01cf"+
		"\u0001\u0000\u0000\u0000\u01ef\u01d1\u0001\u0000\u0000\u0000\u01ef\u01d3"+
		"\u0001\u0000\u0000\u0000\u01ef\u01d5\u0001\u0000\u0000\u0000\u01ef\u01d7"+
		"\u0001\u0000\u0000\u0000\u01ef\u01d9\u0001\u0000\u0000\u0000\u01ef\u01db"+
		"\u0001\u0000\u0000\u0000\u01ef\u01dd\u0001\u0000\u0000\u0000\u01ef\u01df"+
		"\u0001\u0000\u0000\u0000\u01ef\u01e4\u0001\u0000\u0000\u0000\u01ef\u01e6"+
		"\u0001\u0000\u0000\u0000\u01ef\u01e8\u0001\u0000\u0000\u0000\u01ef\u01ea"+
		"\u0001\u0000\u0000\u0000\u01f0\u0015\u0001\u0000\u0000\u0000\u01f1\u01f4"+
		"\u0005[\u0000\u0000\u01f2\u01f5\u0003&\u0013\u0000\u01f3\u01f5\u00034"+
		"\u001a\u0000\u01f4\u01f2\u0001\u0000\u0000\u0000\u01f4\u01f3\u0001\u0000"+
		"\u0000\u0000\u01f5\u02e5\u0001\u0000\u0000\u0000\u01f6\u01f7\u0005\\\u0000"+
		"\u0000\u01f7\u02e5\u0003b1\u0000\u01f8\u01f9\u0005]\u0000\u0000\u01f9"+
		"\u02e5\u0003d2\u0000\u01fa\u01fd\u0005^\u0000\u0000\u01fb\u01fe\u0003"+
		"&\u0013\u0000\u01fc\u01fe\u00034\u001a\u0000\u01fd\u01fb\u0001\u0000\u0000"+
		"\u0000\u01fd\u01fc\u0001\u0000\u0000\u0000\u01fe\u02e5\u0001\u0000\u0000"+
		"\u0000\u01ff\u0202\u0005_\u0000\u0000\u0200\u0203\u0003&\u0013\u0000\u0201"+
		"\u0203\u00034\u001a\u0000\u0202\u0200\u0001\u0000\u0000\u0000\u0202\u0201"+
		"\u0001\u0000\u0000\u0000\u0203\u02e5\u0001\u0000\u0000\u0000\u0204\u0205"+
		"\u0005`\u0000\u0000\u0205\u02e5\u0003H$\u0000\u0206\u0207\u0005a\u0000"+
		"\u0000\u0207\u02e5\u0003&\u0013\u0000\u0208\u0209\u0005b\u0000\u0000\u0209"+
		"\u02e5\u0003f3\u0000\u020a\u020b\u0005c\u0000\u0000\u020b\u02e5\u0003"+
		"&\u0013\u0000\u020c\u020f\u0005d\u0000\u0000\u020d\u0210\u0003&\u0013"+
		"\u0000\u020e\u0210\u0003D\"\u0000\u020f\u020d\u0001\u0000\u0000\u0000"+
		"\u020f\u020e\u0001\u0000\u0000\u0000\u0210\u02e5\u0001\u0000\u0000\u0000"+
		"\u0211\u0212\u0005e\u0000\u0000\u0212\u02e5\u0003H$\u0000\u0213\u0214"+
		"\u0005f\u0000\u0000\u0214\u02e5\u0003&\u0013\u0000\u0215\u0218\u0005g"+
		"\u0000\u0000\u0216\u0219\u0003&\u0013\u0000\u0217\u0219\u0003\u008cF\u0000"+
		"\u0218\u0216\u0001\u0000\u0000\u0000\u0218\u0217\u0001\u0000\u0000\u0000"+
		"\u0219\u02e5\u0001\u0000\u0000\u0000\u021a\u021b\u0005h\u0000\u0000\u021b"+
		"\u02e5\u0003\u008aE\u0000\u021c\u021d\u0005h\u0000\u0000\u021d\u02e5\u0003"+
		"h4\u0000\u021e\u021f\u0005h\u0000\u0000\u021f\u02e5\u0003j5\u0000\u0220"+
		"\u0221\u0005h\u0000\u0000\u0221\u02e5\u0003l6\u0000\u0222\u0223\u0005"+
		"h\u0000\u0000\u0223\u02e5\u0003n7\u0000\u0224\u0225\u0005h\u0000\u0000"+
		"\u0225\u02e5\u0003p8\u0000\u0226\u0227\u0005h\u0000\u0000\u0227\u02e5"+
		"\u0003r9\u0000\u0228\u0229\u0005h\u0000\u0000\u0229\u02e5\u0003t:\u0000"+
		"\u022a\u022b\u0005h\u0000\u0000\u022b\u02e5\u0003v;\u0000\u022c\u022f"+
		"\u0005i\u0000\u0000\u022d\u0230\u0003&\u0013\u0000\u022e\u0230\u0003\u008e"+
		"G\u0000\u022f\u022d\u0001\u0000\u0000\u0000\u022f\u022e\u0001\u0000\u0000"+
		"\u0000\u0230\u02e5\u0001\u0000\u0000\u0000\u0231\u0232\u0005i\u0000\u0000"+
		"\u0232\u02e5\u0003\u0090H\u0000\u0233\u0234\u0005i\u0000\u0000\u0234\u02e5"+
		"\u0003\u0092I\u0000\u0235\u0236\u0005j\u0000\u0000\u0236\u02e5\u0003\u00f0"+
		"x\u0000\u0237\u0238\u0005j\u0000\u0000\u0238\u02e5\u0003x<\u0000\u0239"+
		"\u023a\u0005j\u0000\u0000\u023a\u02e5\u0003z=\u0000\u023b\u023c\u0005"+
		"j\u0000\u0000\u023c\u02e5\u0003|>\u0000\u023d\u023e\u0005j\u0000\u0000"+
		"\u023e\u02e5\u0003l6\u0000\u023f\u0240\u0005j\u0000\u0000\u0240\u02e5"+
		"\u0003~?\u0000\u0241\u0242\u0005j\u0000\u0000\u0242\u02e5\u0003\u0080"+
		"@\u0000\u0243\u0244\u0005j\u0000\u0000\u0244\u02e5\u0003p8\u0000\u0245"+
		"\u0246\u0005j\u0000\u0000\u0246\u02e5\u0003\u0082A\u0000\u0247\u0248\u0005"+
		"j\u0000\u0000\u0248\u02e5\u0003\u0084B\u0000\u0249\u024a\u0005j\u0000"+
		"\u0000\u024a\u02e5\u0003t:\u0000\u024b\u024c\u0005j\u0000\u0000\u024c"+
		"\u02e5\u0003\u0086C\u0000\u024d\u024e\u0005j\u0000\u0000\u024e\u02e5\u0003"+
		"\u0088D\u0000\u024f\u0250\u0005k\u0000\u0000\u0250\u02e5\u0003&\u0013"+
		"\u0000\u0251\u0252\u0005k\u0000\u0000\u0252\u02e5\u0003\u0094J\u0000\u0253"+
		"\u0254\u0005k\u0000\u0000\u0254\u02e5\u0003\u0096K\u0000\u0255\u0256\u0005"+
		"l\u0000\u0000\u0256\u02e5\u0003H$\u0000\u0257\u0258\u0005m\u0000\u0000"+
		"\u0258\u02e5\u0003&\u0013\u0000\u0259\u025a\u0005n\u0000\u0000\u025a\u02e5"+
		"\u0003>\u001f\u0000\u025b\u025c\u0005o\u0000\u0000\u025c\u02e5\u0003B"+
		"!\u0000\u025d\u025e\u0005p\u0000\u0000\u025e\u02e5\u0003&\u0013\u0000"+
		"\u025f\u0260\u0005q\u0000\u0000\u0260\u02e5\u0003D\"\u0000\u0261\u0264"+
		"\u0005r\u0000\u0000\u0262\u0265\u0003&\u0013\u0000\u0263\u0265\u0003\u0098"+
		"L\u0000\u0264\u0262\u0001\u0000\u0000\u0000\u0264\u0263\u0001\u0000\u0000"+
		"\u0000\u0265\u02e5\u0001\u0000\u0000\u0000\u0266\u0267\u0005r\u0000\u0000"+
		"\u0267\u02e5\u0003\u009aM\u0000\u0268\u0269\u0005r\u0000\u0000\u0269\u02e5"+
		"\u0003\u009cN\u0000\u026a\u026b\u0005r\u0000\u0000\u026b\u02e5\u0003\u009e"+
		"O\u0000\u026c\u026d\u0005r\u0000\u0000\u026d\u02e5\u0003\u00a0P\u0000"+
		"\u026e\u026f\u0005r\u0000\u0000\u026f\u02e5\u0003\u00a2Q\u0000\u0270\u0271"+
		"\u0005r\u0000\u0000\u0271\u02e5\u0003\u00a4R\u0000\u0272\u0273\u0005s"+
		"\u0000\u0000\u0273\u02e5\u00036\u001b\u0000\u0274\u0275\u0005s\u0000\u0000"+
		"\u0275\u02e5\u0003\u00a6S\u0000\u0276\u0279\u0005t\u0000\u0000\u0277\u027a"+
		"\u0003&\u0013\u0000\u0278\u027a\u0003\u00aeW\u0000\u0279\u0277\u0001\u0000"+
		"\u0000\u0000\u0279\u0278\u0001\u0000\u0000\u0000\u027a\u02e5\u0001\u0000"+
		"\u0000\u0000\u027b\u027c\u0005t\u0000\u0000\u027c\u02e5\u0003\u00b0X\u0000"+
		"\u027d\u027e\u0005t\u0000\u0000\u027e\u02e5\u0003\u00b2Y\u0000\u027f\u0280"+
		"\u0005t\u0000\u0000\u0280\u02e5\u0003\u00b4Z\u0000\u0281\u0282\u0005t"+
		"\u0000\u0000\u0282\u02e5\u0003\u00b6[\u0000\u0283\u0284\u0005t\u0000\u0000"+
		"\u0284\u02e5\u0003\u00b8\\\u0000\u0285\u0286\u0005t\u0000\u0000\u0286"+
		"\u02e5\u0003\u00ba]\u0000\u0287\u028a\u0005u\u0000\u0000\u0288\u028b\u0003"+
		"&\u0013\u0000\u0289\u028b\u0003\u00aeW\u0000\u028a\u0288\u0001\u0000\u0000"+
		"\u0000\u028a\u0289\u0001\u0000\u0000\u0000\u028b\u02e5\u0001\u0000\u0000"+
		"\u0000\u028c\u028d\u0005u\u0000\u0000\u028d\u02e5\u0003\u00b0X\u0000\u028e"+
		"\u028f\u0005u\u0000\u0000\u028f\u02e5\u0003\u00b2Y\u0000\u0290\u0291\u0005"+
		"u\u0000\u0000\u0291\u02e5\u0003\u00b4Z\u0000\u0292\u0293\u0005u\u0000"+
		"\u0000\u0293\u02e5\u0003\u00b6[\u0000\u0294\u0295\u0005u\u0000\u0000\u0295"+
		"\u02e5\u0003\u00b8\\\u0000\u0296\u0297\u0005u\u0000\u0000\u0297\u02e5"+
		"\u0003\u00ba]\u0000\u0298\u0299\u0005v\u0000\u0000\u0299\u02e5\u00036"+
		"\u001b\u0000\u029a\u029b\u0005v\u0000\u0000\u029b\u02e5\u0003\u00bc^\u0000"+
		"\u029c\u029d\u0005w\u0000\u0000\u029d\u02e5\u00036\u001b\u0000\u029e\u029f"+
		"\u0005w\u0000\u0000\u029f\u02e5\u0003\u00bc^\u0000\u02a0\u02a1\u0005x"+
		"\u0000\u0000\u02a1\u02e5\u0003\u00c0`\u0000\u02a2\u02a3\u0005x\u0000\u0000"+
		"\u02a3\u02e5\u0003\u00c2a\u0000\u02a4\u02a5\u0005y\u0000\u0000\u02a5\u02e5"+
		"\u0003P(\u0000\u02a6\u02a7\u0005z\u0000\u0000\u02a7\u02e5\u0003P(\u0000"+
		"\u02a8\u02a9\u0005{\u0000\u0000\u02a9\u02e5\u0003P(\u0000\u02aa\u02ab"+
		"\u0005|\u0000\u0000\u02ab\u02e5\u0003P(\u0000\u02ac\u02ad\u0005}\u0000"+
		"\u0000\u02ad\u02e5\u0003R)\u0000\u02ae\u02af\u0005~\u0000\u0000\u02af"+
		"\u02e5\u0003\u00c4b\u0000\u02b0\u02b1\u0005\u007f\u0000\u0000\u02b1\u02e5"+
		"\u0003\u00c6c\u0000\u02b2\u02b3\u0005\u0080\u0000\u0000\u02b3\u02e5\u0003"+
		"B!\u0000\u02b4\u02b5\u0005\u0081\u0000\u0000\u02b5\u02e5\u0003B!\u0000"+
		"\u02b6\u02b7\u0005\u0082\u0000\u0000\u02b7\u02e5\u0003,\u0016\u0000\u02b8"+
		"\u02b9\u0005\u0083\u0000\u0000\u02b9\u02e5\u00032\u0019\u0000\u02ba\u02bd"+
		"\u0005\u0084\u0000\u0000\u02bb\u02be\u0003&\u0013\u0000\u02bc\u02be\u0003"+
		"\u00c8d\u0000\u02bd\u02bb\u0001\u0000\u0000\u0000\u02bd\u02bc\u0001\u0000"+
		"\u0000\u0000\u02be\u02e5\u0001\u0000\u0000\u0000\u02bf\u02c0\u0005\u0085"+
		"\u0000\u0000\u02c0\u02e5\u0003\u00cae\u0000\u02c1\u02c2\u0005\u0086\u0000"+
		"\u0000\u02c2\u02e5\u0003\u00ccf\u0000\u02c3\u02c4\u0005\u0087\u0000\u0000"+
		"\u02c4\u02e5\u0003\u00cae\u0000\u02c5\u02c6\u0005\u0088\u0000\u0000\u02c6"+
		"\u02e5\u0003&\u0013\u0000\u02c7\u02c8\u0005\u0088\u0000\u0000\u02c8\u02e5"+
		"\u00034\u001a\u0000\u02c9\u02ca\u0005\u0088\u0000\u0000\u02ca\u02e5\u0003"+
		"\u00cae\u0000\u02cb\u02cc\u0005\u0089\u0000\u0000\u02cc\u02e5\u0003\u00d8"+
		"l\u0000\u02cd\u02ce\u0005\u008a\u0000\u0000\u02ce\u02e5\u0003\u00dam\u0000"+
		"\u02cf\u02d0\u0005\u008b\u0000\u0000\u02d0\u02e5\u0003\u00cae\u0000\u02d1"+
		"\u02d2\u0005\u008c\u0000\u0000\u02d2\u02e5\u00034\u001a\u0000\u02d3\u02d4"+
		"\u0005\u008c\u0000\u0000\u02d4\u02e5\u0003\u00ceg\u0000\u02d5\u02d6\u0005"+
		"\u008d\u0000\u0000\u02d6\u02e5\u0003\u00d0h\u0000\u02d7\u02d8\u0005\u008d"+
		"\u0000\u0000\u02d8\u02e5\u0003\u00d2i\u0000\u02d9\u02da\u0005\u008d\u0000"+
		"\u0000\u02da\u02e5\u0003\u00d4j\u0000\u02db\u02dc\u0005\u008d\u0000\u0000"+
		"\u02dc\u02e5\u0003\u00d6k\u0000\u02dd\u02e0\u0005\u008e\u0000\u0000\u02de"+
		"\u02e1\u0003&\u0013\u0000\u02df\u02e1\u0003B!\u0000\u02e0\u02de\u0001"+
		"\u0000\u0000\u0000\u02e0\u02df\u0001\u0000\u0000\u0000\u02e1\u02e5\u0001"+
		"\u0000\u0000\u0000\u02e2\u02e3\u00059\u0000\u0000\u02e3\u02e5\u0003\u00e6"+
		"s\u0000\u02e4\u01f1\u0001\u0000\u0000\u0000\u02e4\u01f6\u0001\u0000\u0000"+
		"\u0000\u02e4\u01f8\u0001\u0000\u0000\u0000\u02e4\u01fa\u0001\u0000\u0000"+
		"\u0000\u02e4\u01ff\u0001\u0000\u0000\u0000\u02e4\u0204\u0001\u0000\u0000"+
		"\u0000\u02e4\u0206\u0001\u0000\u0000\u0000\u02e4\u0208\u0001\u0000\u0000"+
		"\u0000\u02e4\u020a\u0001\u0000\u0000\u0000\u02e4\u020c\u0001\u0000\u0000"+
		"\u0000\u02e4\u0211\u0001\u0000\u0000\u0000\u02e4\u0213\u0001\u0000\u0000"+
		"\u0000\u02e4\u0215\u0001\u0000\u0000\u0000\u02e4\u021a\u0001\u0000\u0000"+
		"\u0000\u02e4\u021c\u0001\u0000\u0000\u0000\u02e4\u021e\u0001\u0000\u0000"+
		"\u0000\u02e4\u0220\u0001\u0000\u0000\u0000\u02e4\u0222\u0001\u0000\u0000"+
		"\u0000\u02e4\u0224\u0001\u0000\u0000\u0000\u02e4\u0226\u0001\u0000\u0000"+
		"\u0000\u02e4\u0228\u0001\u0000\u0000\u0000\u02e4\u022a\u0001\u0000\u0000"+
		"\u0000\u02e4\u022c\u0001\u0000\u0000\u0000\u02e4\u0231\u0001\u0000\u0000"+
		"\u0000\u02e4\u0233\u0001\u0000\u0000\u0000\u02e4\u0235\u0001\u0000\u0000"+
		"\u0000\u02e4\u0237\u0001\u0000\u0000\u0000\u02e4\u0239\u0001\u0000\u0000"+
		"\u0000\u02e4\u023b\u0001\u0000\u0000\u0000\u02e4\u023d\u0001\u0000\u0000"+
		"\u0000\u02e4\u023f\u0001\u0000\u0000\u0000\u02e4\u0241\u0001\u0000\u0000"+
		"\u0000\u02e4\u0243\u0001\u0000\u0000\u0000\u02e4\u0245\u0001\u0000\u0000"+
		"\u0000\u02e4\u0247\u0001\u0000\u0000\u0000\u02e4\u0249\u0001\u0000\u0000"+
		"\u0000\u02e4\u024b\u0001\u0000\u0000\u0000\u02e4\u024d\u0001\u0000\u0000"+
		"\u0000\u02e4\u024f\u0001\u0000\u0000\u0000\u02e4\u0251\u0001\u0000\u0000"+
		"\u0000\u02e4\u0253\u0001\u0000\u0000\u0000\u02e4\u0255\u0001\u0000\u0000"+
		"\u0000\u02e4\u0257\u0001\u0000\u0000\u0000\u02e4\u0259\u0001\u0000\u0000"+
		"\u0000\u02e4\u025b\u0001\u0000\u0000\u0000\u02e4\u025d\u0001\u0000\u0000"+
		"\u0000\u02e4\u025f\u0001\u0000\u0000\u0000\u02e4\u0261\u0001\u0000\u0000"+
		"\u0000\u02e4\u0266\u0001\u0000\u0000\u0000\u02e4\u0268\u0001\u0000\u0000"+
		"\u0000\u02e4\u026a\u0001\u0000\u0000\u0000\u02e4\u026c\u0001\u0000\u0000"+
		"\u0000\u02e4\u026e\u0001\u0000\u0000\u0000\u02e4\u0270\u0001\u0000\u0000"+
		"\u0000\u02e4\u0272\u0001\u0000\u0000\u0000\u02e4\u0274\u0001\u0000\u0000"+
		"\u0000\u02e4\u0276\u0001\u0000\u0000\u0000\u02e4\u027b\u0001\u0000\u0000"+
		"\u0000\u02e4\u027d\u0001\u0000\u0000\u0000\u02e4\u027f\u0001\u0000\u0000"+
		"\u0000\u02e4\u0281\u0001\u0000\u0000\u0000\u02e4\u0283\u0001\u0000\u0000"+
		"\u0000\u02e4\u0285\u0001\u0000\u0000\u0000\u02e4\u0287\u0001\u0000\u0000"+
		"\u0000\u02e4\u028c\u0001\u0000\u0000\u0000\u02e4\u028e\u0001\u0000\u0000"+
		"\u0000\u02e4\u0290\u0001\u0000\u0000\u0000\u02e4\u0292\u0001\u0000\u0000"+
		"\u0000\u02e4\u0294\u0001\u0000\u0000\u0000\u02e4\u0296\u0001\u0000\u0000"+
		"\u0000\u02e4\u0298\u0001\u0000\u0000\u0000\u02e4\u029a\u0001\u0000\u0000"+
		"\u0000\u02e4\u029c\u0001\u0000\u0000\u0000\u02e4\u029e\u0001\u0000\u0000"+
		"\u0000\u02e4\u02a0\u0001\u0000\u0000\u0000\u02e4\u02a2\u0001\u0000\u0000"+
		"\u0000\u02e4\u02a4\u0001\u0000\u0000\u0000\u02e4\u02a6\u0001\u0000\u0000"+
		"\u0000\u02e4\u02a8\u0001\u0000\u0000\u0000\u02e4\u02aa\u0001\u0000\u0000"+
		"\u0000\u02e4\u02ac\u0001\u0000\u0000\u0000\u02e4\u02ae\u0001\u0000\u0000"+
		"\u0000\u02e4\u02b0\u0001\u0000\u0000\u0000\u02e4\u02b2\u0001\u0000\u0000"+
		"\u0000\u02e4\u02b4\u0001\u0000\u0000\u0000\u02e4\u02b6\u0001\u0000\u0000"+
		"\u0000\u02e4\u02b8\u0001\u0000\u0000\u0000\u02e4\u02ba\u0001\u0000\u0000"+
		"\u0000\u02e4\u02bf\u0001\u0000\u0000\u0000\u02e4\u02c1\u0001\u0000\u0000"+
		"\u0000\u02e4\u02c3\u0001\u0000\u0000\u0000\u02e4\u02c5\u0001\u0000\u0000"+
		"\u0000\u02e4\u02c7\u0001\u0000\u0000\u0000\u02e4\u02c9\u0001\u0000\u0000"+
		"\u0000\u02e4\u02cb\u0001\u0000\u0000\u0000\u02e4\u02cd\u0001\u0000\u0000"+
		"\u0000\u02e4\u02cf\u0001\u0000\u0000\u0000\u02e4\u02d1\u0001\u0000\u0000"+
		"\u0000\u02e4\u02d3\u0001\u0000\u0000\u0000\u02e4\u02d5\u0001\u0000\u0000"+
		"\u0000\u02e4\u02d7\u0001\u0000\u0000\u0000\u02e4\u02d9\u0001\u0000\u0000"+
		"\u0000\u02e4\u02db\u0001\u0000\u0000\u0000\u02e4\u02dd\u0001\u0000\u0000"+
		"\u0000\u02e4\u02e2\u0001\u0000\u0000\u0000\u02e5\u0017\u0001\u0000\u0000"+
		"\u0000\u02e6\u02eb\u0003\u0010\b\u0000\u02e7\u02e8\u0005\u00a0\u0000\u0000"+
		"\u02e8\u02ea\u0003\u0010\b\u0000\u02e9\u02e7\u0001\u0000\u0000\u0000\u02ea"+
		"\u02ed\u0001\u0000\u0000\u0000\u02eb\u02e9\u0001\u0000\u0000\u0000\u02eb"+
		"\u02ec\u0001\u0000\u0000\u0000\u02ec\u0019\u0001\u0000\u0000\u0000\u02ed"+
		"\u02eb\u0001\u0000\u0000\u0000\u02ee\u02f3\u0003\u0012\t\u0000\u02ef\u02f0"+
		"\u0005\u00a0\u0000\u0000\u02f0\u02f2\u0003\u0012\t\u0000\u02f1\u02ef\u0001"+
		"\u0000\u0000\u0000\u02f2\u02f5\u0001\u0000\u0000\u0000\u02f3\u02f1\u0001"+
		"\u0000\u0000\u0000\u02f3\u02f4\u0001\u0000\u0000\u0000\u02f4\u001b\u0001"+
		"\u0000\u0000\u0000\u02f5\u02f3\u0001\u0000\u0000\u0000\u02f6\u02fb\u0003"+
		"\u0014\n\u0000\u02f7\u02f8\u0005\u00a0\u0000\u0000\u02f8\u02fa\u0003\u0014"+
		"\n\u0000\u02f9\u02f7\u0001\u0000\u0000\u0000\u02fa\u02fd\u0001\u0000\u0000"+
		"\u0000\u02fb\u02f9\u0001\u0000\u0000\u0000\u02fb\u02fc\u0001\u0000\u0000"+
		"\u0000\u02fc\u001d\u0001\u0000\u0000\u0000\u02fd\u02fb\u0001\u0000\u0000"+
		"\u0000\u02fe\u0303\u0003\u0016\u000b\u0000\u02ff\u0300\u0005\u00a0\u0000"+
		"\u0000\u0300\u0302\u0003\u0016\u000b\u0000\u0301\u02ff\u0001\u0000\u0000"+
		"\u0000\u0302\u0305\u0001\u0000\u0000\u0000\u0303\u0301\u0001\u0000\u0000"+
		"\u0000\u0303\u0304\u0001\u0000\u0000\u0000\u0304\u001f\u0001\u0000\u0000"+
		"\u0000\u0305\u0303\u0001\u0000\u0000\u0000\u0306\u0307\u0005\u009e\u0000"+
		"\u0000\u0307!\u0001\u0000\u0000\u0000\u0308\u030a\u0005\u00a0\u0000\u0000"+
		"\u0309\u0308\u0001\u0000\u0000\u0000\u0309\u030a\u0001\u0000\u0000\u0000"+
		"\u030a\u030b\u0001\u0000\u0000\u0000\u030b\u030c\u0005\u009f\u0000\u0000"+
		"\u030c#\u0001\u0000\u0000\u0000\u030d\u030e\u0003 \u0010\u0000\u030e\u0313"+
		"\u0003\u000e\u0007\u0000\u030f\u0310\u0005\u00a0\u0000\u0000\u0310\u0312"+
		"\u0003\u000e\u0007\u0000\u0311\u030f\u0001\u0000\u0000\u0000\u0312\u0315"+
		"\u0001\u0000\u0000\u0000\u0313\u0311\u0001\u0000\u0000\u0000\u0313\u0314"+
		"\u0001\u0000\u0000\u0000\u0314\u0316\u0001\u0000\u0000\u0000\u0315\u0313"+
		"\u0001\u0000\u0000\u0000\u0316\u0317\u0003\"\u0011\u0000\u0317%\u0001"+
		"\u0000\u0000\u0000\u0318\u0319\u0003 \u0010\u0000\u0319\u031a\u0003\""+
		"\u0011\u0000\u031a\'\u0001\u0000\u0000\u0000\u031b\u031c\u0003 \u0010"+
		"\u0000\u031c\u0321\u0003\u0010\b\u0000\u031d\u031e\u0005\u00a0\u0000\u0000"+
		"\u031e\u0320\u0003\u0010\b\u0000\u031f\u031d\u0001\u0000\u0000\u0000\u0320"+
		"\u0323\u0001\u0000\u0000\u0000\u0321\u031f\u0001\u0000\u0000\u0000\u0321"+
		"\u0322\u0001\u0000\u0000\u0000\u0322\u0324\u0001\u0000\u0000\u0000\u0323"+
		"\u0321\u0001\u0000\u0000\u0000\u0324\u0325\u0003\"\u0011\u0000\u0325)"+
		"\u0001\u0000\u0000\u0000\u0326\u0327\u0003 \u0010\u0000\u0327\u032c\u0003"+
		"\u0012\t\u0000\u0328\u0329\u0005\u00a0\u0000\u0000\u0329\u032b\u0003\u0012"+
		"\t\u0000\u032a\u0328\u0001\u0000\u0000\u0000\u032b\u032e\u0001\u0000\u0000"+
		"\u0000\u032c\u032a\u0001\u0000\u0000\u0000\u032c\u032d\u0001\u0000\u0000"+
		"\u0000\u032d\u032f\u0001\u0000\u0000\u0000\u032e\u032c\u0001\u0000\u0000"+
		"\u0000\u032f\u0330\u0003\"\u0011\u0000\u0330+\u0001\u0000\u0000\u0000"+
		"\u0331\u0332\u0003 \u0010\u0000\u0332\u0333\u0003\u0012\t\u0000\u0333"+
		"\u0334\u0003\"\u0011\u0000\u0334-\u0001\u0000\u0000\u0000\u0335\u0344"+
		"\u0003 \u0010\u0000\u0336\u0345\u0003\u00eew\u0000\u0337\u0338\u0003\u00ee"+
		"w\u0000\u0338\u0339\u0005\u00a0\u0000\u0000\u0339\u033b\u0001\u0000\u0000"+
		"\u0000\u033a\u0337\u0001\u0000\u0000\u0000\u033a\u033b\u0001\u0000\u0000"+
		"\u0000\u033b\u033c\u0001\u0000\u0000\u0000\u033c\u0341\u0003\u0014\n\u0000"+
		"\u033d\u033e\u0005\u00a0\u0000\u0000\u033e\u0340\u0003\u0014\n\u0000\u033f"+
		"\u033d\u0001\u0000\u0000\u0000\u0340\u0343\u0001\u0000\u0000\u0000\u0341"+
		"\u033f\u0001\u0000\u0000\u0000\u0341\u0342\u0001\u0000\u0000\u0000\u0342"+
		"\u0345\u0001\u0000\u0000\u0000\u0343\u0341\u0001\u0000\u0000\u0000\u0344"+
		"\u0336\u0001\u0000\u0000\u0000\u0344\u033a\u0001\u0000\u0000\u0000\u0345"+
		"\u0346\u0001\u0000\u0000\u0000\u0346\u0347\u0003\"\u0011\u0000\u0347/"+
		"\u0001\u0000\u0000\u0000\u0348\u0349\u0003 \u0010\u0000\u0349\u034e\u0003"+
		"\u0014\n\u0000\u034a\u034b\u0005\u00a0\u0000\u0000\u034b\u034d\u0003\u0014"+
		"\n\u0000\u034c\u034a\u0001\u0000\u0000\u0000\u034d\u0350\u0001\u0000\u0000"+
		"\u0000\u034e\u034c\u0001\u0000\u0000\u0000\u034e\u034f\u0001\u0000\u0000"+
		"\u0000\u034f\u0351\u0001\u0000\u0000\u0000\u0350\u034e\u0001\u0000\u0000"+
		"\u0000\u0351\u0352\u0003\"\u0011\u0000\u03521\u0001\u0000\u0000\u0000"+
		"\u0353\u0354\u0003 \u0010\u0000\u0354\u0355\u0003\u0016\u000b\u0000\u0355"+
		"\u0356\u0003\"\u0011\u0000\u03563\u0001\u0000\u0000\u0000\u0357\u0358"+
		"\u0003 \u0010\u0000\u0358\u035d\u0003\u0016\u000b\u0000\u0359\u035a\u0005"+
		"\u00a0\u0000\u0000\u035a\u035c\u0003\u0016\u000b\u0000\u035b\u0359\u0001"+
		"\u0000\u0000\u0000\u035c\u035f\u0001\u0000\u0000\u0000\u035d\u035b\u0001"+
		"\u0000\u0000\u0000\u035d\u035e\u0001\u0000\u0000\u0000\u035e\u0360\u0001"+
		"\u0000\u0000\u0000\u035f\u035d\u0001\u0000\u0000\u0000\u0360\u0361\u0003"+
		"\"\u0011\u0000\u03615\u0001\u0000\u0000\u0000\u0362\u0363\u0003 \u0010"+
		"\u0000\u0363\u0364\u0003\u00eew\u0000\u0364\u0365\u0003\"\u0011\u0000"+
		"\u03657\u0001\u0000\u0000\u0000\u0366\u0367\u0003 \u0010\u0000\u0367\u0368"+
		"\u0003\u00eew\u0000\u0368\u0369\u0005\u00a0\u0000\u0000\u0369\u036a\u0003"+
		"\u00eew\u0000\u036a\u036b\u0003\"\u0011\u0000\u036b9\u0001\u0000\u0000"+
		"\u0000\u036c\u036d\u0003 \u0010\u0000\u036d\u0370\u0003\u00eew\u0000\u036e"+
		"\u036f\u0005\u00a0\u0000\u0000\u036f\u0371\u0003\u00eew\u0000\u0370\u036e"+
		"\u0001\u0000\u0000\u0000\u0370\u0371\u0001\u0000\u0000\u0000\u0371\u0372"+
		"\u0001\u0000\u0000\u0000\u0372\u0373\u0003\"\u0011\u0000\u0373;\u0001"+
		"\u0000\u0000\u0000\u0374\u0375\u0003 \u0010\u0000\u0375\u0376\u0003\u00ee"+
		"w\u0000\u0376\u0377\u0005\u00a0\u0000\u0000\u0377\u0378\u0003\u00ecv\u0000"+
		"\u0378\u0379\u0003\"\u0011\u0000\u0379=\u0001\u0000\u0000\u0000\u037a"+
		"\u037b\u0003 \u0010\u0000\u037b\u037e\u0003\u00eew\u0000\u037c\u037d\u0005"+
		"\u00a0\u0000\u0000\u037d\u037f\u0003\u00ecv\u0000\u037e\u037c\u0001\u0000"+
		"\u0000\u0000\u037e\u037f\u0001\u0000\u0000\u0000\u037f\u0380\u0001\u0000"+
		"\u0000\u0000\u0380\u0381\u0003\"\u0011\u0000\u0381?\u0001\u0000\u0000"+
		"\u0000\u0382\u0383\u0003 \u0010\u0000\u0383\u0384\u0003\u00eew\u0000\u0384"+
		"\u0385\u0005\u00a0\u0000\u0000\u0385\u0386\u0003\u00eew\u0000\u0386\u0387"+
		"\u0005\u00a0\u0000\u0000\u0387\u0388\u0003\u00eew\u0000\u0388\u0389\u0003"+
		"\"\u0011\u0000\u0389A\u0001\u0000\u0000\u0000\u038a\u038b\u0003 \u0010"+
		"\u0000\u038b\u038c\u0003\u00eew\u0000\u038c\u038d\u0003\"\u0011\u0000"+
		"\u038dC\u0001\u0000\u0000\u0000\u038e\u038f\u0003 \u0010\u0000\u038f\u0390"+
		"\u0003\u00ecv\u0000\u0390\u0391\u0003\"\u0011\u0000\u0391E\u0001\u0000"+
		"\u0000\u0000\u0392\u0393\u0003 \u0010\u0000\u0393\u0394\u0003\u00eew\u0000"+
		"\u0394\u0395\u0005\u00a0\u0000\u0000\u0395\u0396\u0003\u00eew\u0000\u0396"+
		"\u0397\u0003\"\u0011\u0000\u0397G\u0001\u0000\u0000\u0000\u0398\u0399"+
		"\u0003 \u0010\u0000\u0399\u039a\u0003\u00ecv\u0000\u039a\u039b\u0003\""+
		"\u0011\u0000\u039bI\u0001\u0000\u0000\u0000\u039c\u039d\u0003 \u0010\u0000"+
		"\u039d\u039e\u0003\u00eew\u0000\u039e\u039f\u0005\u00a0\u0000\u0000\u039f"+
		"\u03a0\u0003\u0012\t\u0000\u03a0\u03a1\u0003\"\u0011\u0000\u03a1K\u0001"+
		"\u0000\u0000\u0000\u03a2\u03a3\u0003 \u0010\u0000\u03a3\u03a4\u0003\u00ee"+
		"w\u0000\u03a4\u03a5\u0005\u00a0\u0000\u0000\u03a5\u03a8\u0003\u0012\t"+
		"\u0000\u03a6\u03a7\u0005\u00a0\u0000\u0000\u03a7\u03a9\u0003\u0012\t\u0000"+
		"\u03a8\u03a6\u0001\u0000\u0000\u0000\u03a8\u03a9\u0001\u0000\u0000\u0000"+
		"\u03a9\u03aa\u0001\u0000\u0000\u0000\u03aa\u03ab\u0003\"\u0011\u0000\u03ab"+
		"M\u0001\u0000\u0000\u0000\u03ac\u03ad\u0003 \u0010\u0000\u03ad\u03b0\u0003"+
		"\u00eew\u0000\u03ae\u03af\u0005\u00a0\u0000\u0000\u03af\u03b1\u0003\u00ee"+
		"w\u0000\u03b0\u03ae\u0001\u0000\u0000\u0000\u03b0\u03b1\u0001\u0000\u0000"+
		"\u0000\u03b1\u03b7\u0001\u0000\u0000\u0000\u03b2\u03b3\u0005\u00a0\u0000"+
		"\u0000\u03b3\u03b4\u0003\u00eew\u0000\u03b4\u03b5\u0005\u00a0\u0000\u0000"+
		"\u03b5\u03b6\u0003\u00eew\u0000\u03b6\u03b8\u0001\u0000\u0000\u0000\u03b7"+
		"\u03b2\u0001\u0000\u0000\u0000\u03b7\u03b8\u0001\u0000\u0000\u0000\u03b8"+
		"\u03bb\u0001\u0000\u0000\u0000\u03b9\u03ba\u0005\u00a0\u0000\u0000\u03ba"+
		"\u03bc\u0003\u0012\t\u0000\u03bb\u03b9\u0001\u0000\u0000\u0000\u03bb\u03bc"+
		"\u0001\u0000\u0000\u0000\u03bc\u03bd\u0001\u0000\u0000\u0000\u03bd\u03be"+
		"\u0003\"\u0011\u0000\u03beO\u0001\u0000\u0000\u0000\u03bf\u03c0\u0003"+
		" \u0010\u0000\u03c0\u03c3\u0003\u00eew\u0000\u03c1\u03c2\u0005\u00a0\u0000"+
		"\u0000\u03c2\u03c4\u0003\u00eew\u0000\u03c3\u03c1\u0001\u0000\u0000\u0000"+
		"\u03c3\u03c4\u0001\u0000\u0000\u0000\u03c4\u03c7\u0001\u0000\u0000\u0000"+
		"\u03c5\u03c6\u0005\u00a0\u0000\u0000\u03c6\u03c8\u0003\u0012\t\u0000\u03c7"+
		"\u03c5\u0001\u0000\u0000\u0000\u03c7\u03c8\u0001\u0000\u0000\u0000\u03c8"+
		"\u03c9\u0001\u0000\u0000\u0000\u03c9\u03ca\u0003\"\u0011\u0000\u03caQ"+
		"\u0001\u0000\u0000\u0000\u03cb\u03cc\u0003 \u0010\u0000\u03cc\u03cd\u0003"+
		"\u00eew\u0000\u03cd\u03ce\u0005\u00a0\u0000\u0000\u03ce\u03cf\u0003\u00ee"+
		"w\u0000\u03cf\u03d0\u0003\"\u0011\u0000\u03d0S\u0001\u0000\u0000\u0000"+
		"\u03d1\u03d2\u0003 \u0010\u0000\u03d2\u03d7\u0003\u00eew\u0000\u03d3\u03d4"+
		"\u0005\u00a0\u0000\u0000\u03d4\u03d6\u0003\u0014\n\u0000\u03d5\u03d3\u0001"+
		"\u0000\u0000\u0000\u03d6\u03d9\u0001\u0000\u0000\u0000\u03d7\u03d5\u0001"+
		"\u0000\u0000\u0000\u03d7\u03d8\u0001\u0000\u0000\u0000\u03d8\u03da\u0001"+
		"\u0000\u0000\u0000\u03d9\u03d7\u0001\u0000\u0000\u0000\u03da\u03db\u0003"+
		"\"\u0011\u0000\u03dbU\u0001\u0000\u0000\u0000\u03dc\u03dd\u0003 \u0010"+
		"\u0000\u03dd\u03e0\u0003\u00eew\u0000\u03de\u03df\u0005\u00a0\u0000\u0000"+
		"\u03df\u03e1\u0003\u0014\n\u0000\u03e0\u03de\u0001\u0000\u0000\u0000\u03e1"+
		"\u03e2\u0001\u0000\u0000\u0000\u03e2\u03e0\u0001\u0000\u0000\u0000\u03e2"+
		"\u03e3\u0001\u0000\u0000\u0000\u03e3\u03e4\u0001\u0000\u0000\u0000\u03e4"+
		"\u03e5\u0003\"\u0011\u0000\u03e5W\u0001\u0000\u0000\u0000\u03e6\u03e7"+
		"\u0003 \u0010\u0000\u03e7\u03e8\u0003\u00eew\u0000\u03e8\u03e9\u0005\u00a0"+
		"\u0000\u0000\u03e9\u03ee\u0003\u0012\t\u0000\u03ea\u03eb\u0005\u00a0\u0000"+
		"\u0000\u03eb\u03ed\u0003\u0012\t\u0000\u03ec\u03ea\u0001\u0000\u0000\u0000"+
		"\u03ed\u03f0\u0001\u0000\u0000\u0000\u03ee\u03ec\u0001\u0000\u0000\u0000"+
		"\u03ee\u03ef\u0001\u0000\u0000\u0000\u03ef\u03f1\u0001\u0000\u0000\u0000"+
		"\u03f0\u03ee\u0001\u0000\u0000\u0000\u03f1\u03f2\u0003\"\u0011\u0000\u03f2"+
		"Y\u0001\u0000\u0000\u0000\u03f3\u03f4\u0003 \u0010\u0000\u03f4\u03f9\u0003"+
		"\u0012\t\u0000\u03f5\u03f6\u0005\u00a0\u0000\u0000\u03f6\u03f8\u0003\u0012"+
		"\t\u0000\u03f7\u03f5\u0001\u0000\u0000\u0000\u03f8\u03fb\u0001\u0000\u0000"+
		"\u0000\u03f9\u03f7\u0001\u0000\u0000\u0000\u03f9\u03fa\u0001\u0000\u0000"+
		"\u0000\u03fa\u03fc\u0001\u0000\u0000\u0000\u03fb\u03f9\u0001\u0000\u0000"+
		"\u0000\u03fc\u03fd\u0003\"\u0011\u0000\u03fd[\u0001\u0000\u0000\u0000"+
		"\u03fe\u0408\u0003 \u0010\u0000\u03ff\u0409\u0003\u00eew\u0000\u0400\u0405"+
		"\u0003\u00eew\u0000\u0401\u0402\u0005\u00a0\u0000\u0000\u0402\u0404\u0003"+
		"\u0012\t\u0000\u0403\u0401\u0001\u0000\u0000\u0000\u0404\u0407\u0001\u0000"+
		"\u0000\u0000\u0405\u0403\u0001\u0000\u0000\u0000\u0405\u0406\u0001\u0000"+
		"\u0000\u0000\u0406\u0409\u0001\u0000\u0000\u0000\u0407\u0405\u0001\u0000"+
		"\u0000\u0000\u0408\u03ff\u0001\u0000\u0000\u0000\u0408\u0400\u0001\u0000"+
		"\u0000\u0000\u0409\u040a\u0001\u0000\u0000\u0000\u040a\u040b\u0003\"\u0011"+
		"\u0000\u040b]\u0001\u0000\u0000\u0000\u040c\u040d\u0003 \u0010\u0000\u040d"+
		"\u0412\u0003\u0012\t\u0000\u040e\u040f\u0005\u00a0\u0000\u0000\u040f\u0411"+
		"\u0003\u0012\t\u0000\u0410\u040e\u0001\u0000\u0000\u0000\u0411\u0414\u0001"+
		"\u0000\u0000\u0000\u0412\u0410\u0001\u0000\u0000\u0000\u0412\u0413\u0001"+
		"\u0000\u0000\u0000\u0413\u0415\u0001\u0000\u0000\u0000\u0414\u0412\u0001"+
		"\u0000\u0000\u0000\u0415\u0416\u0003\"\u0011\u0000\u0416_\u0001\u0000"+
		"\u0000\u0000\u0417\u0418\u0003 \u0010\u0000\u0418\u0419\u0003\u00eew\u0000"+
		"\u0419\u041a\u0005\u00a0\u0000\u0000\u041a\u041b\u0003\u00ecv\u0000\u041b"+
		"\u041c\u0003\"\u0011\u0000\u041ca\u0001\u0000\u0000\u0000\u041d\u041e"+
		"\u0003 \u0010\u0000\u041e\u041f\u0003\u00eew\u0000\u041f\u0420\u0005\u00a0"+
		"\u0000\u0000\u0420\u0423\u0003\u00eew\u0000\u0421\u0422\u0005\u00a0\u0000"+
		"\u0000\u0422\u0424\u0003\u0016\u000b\u0000\u0423\u0421\u0001\u0000\u0000"+
		"\u0000\u0423\u0424\u0001\u0000\u0000\u0000\u0424\u0425\u0001\u0000\u0000"+
		"\u0000\u0425\u0426\u0003\"\u0011\u0000\u0426c\u0001\u0000\u0000\u0000"+
		"\u0427\u0428\u0003 \u0010\u0000\u0428\u0429\u0003\u00eew\u0000\u0429\u042a"+
		"\u0005\u00a0\u0000\u0000\u042a\u042b\u0003\u00eew\u0000\u042b\u042c\u0003"+
		"\"\u0011\u0000\u042ce\u0001\u0000\u0000\u0000\u042d\u042e\u0003 \u0010"+
		"\u0000\u042e\u0431\u0003\u00eew\u0000\u042f\u0430\u0005\u00a0\u0000\u0000"+
		"\u0430\u0432\u0003\u00ecv\u0000\u0431\u042f\u0001\u0000\u0000\u0000\u0431"+
		"\u0432\u0001\u0000\u0000\u0000\u0432\u0433\u0001\u0000\u0000\u0000\u0433"+
		"\u0434\u0003\"\u0011\u0000\u0434g\u0001\u0000\u0000\u0000\u0435\u0439"+
		"\u0003 \u0010\u0000\u0436\u0437\u0003\u00eew\u0000\u0437\u0438\u0005\u00a0"+
		"\u0000\u0000\u0438\u043a\u0001\u0000\u0000\u0000\u0439\u0436\u0001\u0000"+
		"\u0000\u0000\u0439\u043a\u0001\u0000\u0000\u0000\u043a\u043b\u0001\u0000"+
		"\u0000\u0000\u043b\u043c\u0003\u00eew\u0000\u043c\u043d\u0003\"\u0011"+
		"\u0000\u043di\u0001\u0000\u0000\u0000\u043e\u0442\u0003 \u0010\u0000\u043f"+
		"\u0440\u0003\u00eew\u0000\u0440\u0441\u0005\u00a0\u0000\u0000\u0441\u0443"+
		"\u0001\u0000\u0000\u0000\u0442\u043f\u0001\u0000\u0000\u0000\u0442\u0443"+
		"\u0001\u0000\u0000\u0000\u0443\u0444\u0001\u0000\u0000\u0000\u0444\u0445"+
		"\u0003\u00eew\u0000\u0445\u0446\u0005\u00a0\u0000\u0000\u0446\u0447\u0003"+
		"\u0016\u000b\u0000\u0447\u0448\u0005\u00a0\u0000\u0000\u0448\u044b\u0003"+
		"\u0016\u000b\u0000\u0449\u044a\u0005\u00a0\u0000\u0000\u044a\u044c\u0003"+
		"\u0016\u000b\u0000\u044b\u0449\u0001\u0000\u0000\u0000\u044b\u044c\u0001"+
		"\u0000\u0000\u0000\u044c\u044d\u0001\u0000\u0000\u0000\u044d\u044e\u0003"+
		"\"\u0011\u0000\u044ek\u0001\u0000\u0000\u0000\u044f\u0453\u0003 \u0010"+
		"\u0000\u0450\u0451\u0003\u00eew\u0000\u0451\u0452\u0005\u00a0\u0000\u0000"+
		"\u0452\u0454\u0001\u0000\u0000\u0000\u0453\u0450\u0001\u0000\u0000\u0000"+
		"\u0453\u0454\u0001\u0000\u0000\u0000\u0454\u0455\u0001\u0000\u0000\u0000"+
		"\u0455\u0456\u0003\u00eew\u0000\u0456\u0457\u0005\u00a0\u0000\u0000\u0457"+
		"\u045a\u0003\u0012\t\u0000\u0458\u0459\u0005\u00a0\u0000\u0000\u0459\u045b"+
		"\u0003\u0016\u000b\u0000\u045a\u0458\u0001\u0000\u0000\u0000\u045a\u045b"+
		"\u0001\u0000\u0000\u0000\u045b\u045c\u0001\u0000\u0000\u0000\u045c\u045d"+
		"\u0003\"\u0011\u0000\u045dm\u0001\u0000\u0000\u0000\u045e\u0462\u0003"+
		" \u0010\u0000\u045f\u0460\u0003\u00eew\u0000\u0460\u0461\u0005\u00a0\u0000"+
		"\u0000\u0461\u0463\u0001\u0000\u0000\u0000\u0462\u045f\u0001\u0000\u0000"+
		"\u0000\u0462\u0463\u0001\u0000\u0000\u0000\u0463\u0464\u0001\u0000\u0000"+
		"\u0000\u0464\u0465\u0003\u00eew\u0000\u0465\u0466\u0005\u00a0\u0000\u0000"+
		"\u0466\u0467\u0003\u0012\t\u0000\u0467\u0468\u0005\u00a0\u0000\u0000\u0468"+
		"\u0469\u0003\u0016\u000b\u0000\u0469\u046a\u0005\u00a0\u0000\u0000\u046a"+
		"\u046d\u0003\u0016\u000b\u0000\u046b\u046c\u0005\u00a0\u0000\u0000\u046c"+
		"\u046e\u0003\u0016\u000b\u0000\u046d\u046b\u0001\u0000\u0000\u0000\u046d"+
		"\u046e\u0001\u0000\u0000\u0000\u046e\u046f\u0001\u0000\u0000\u0000\u046f"+
		"\u0470\u0003\"\u0011\u0000\u0470o\u0001\u0000\u0000\u0000\u0471\u0475"+
		"\u0003 \u0010\u0000\u0472\u0473\u0003\u00eew\u0000\u0473\u0474\u0005\u00a0"+
		"\u0000\u0000\u0474\u0476\u0001\u0000\u0000\u0000\u0475\u0472\u0001\u0000"+
		"\u0000\u0000\u0475\u0476\u0001\u0000\u0000\u0000\u0476\u0477\u0001\u0000"+
		"\u0000\u0000\u0477\u0478\u0003\u00eew\u0000\u0478\u0479\u0005\u00a0\u0000"+
		"\u0000\u0479\u047c\u0003\u0014\n\u0000\u047a\u047b\u0005\u00a0\u0000\u0000"+
		"\u047b\u047d\u0003\u0016\u000b\u0000\u047c\u047a\u0001\u0000\u0000\u0000"+
		"\u047c\u047d\u0001\u0000\u0000\u0000\u047d\u047e\u0001\u0000\u0000\u0000"+
		"\u047e\u047f\u0003\"\u0011\u0000\u047fq\u0001\u0000\u0000\u0000\u0480"+
		"\u0484\u0003 \u0010\u0000\u0481\u0482\u0003\u00eew\u0000\u0482\u0483\u0005"+
		"\u00a0\u0000\u0000\u0483\u0485\u0001\u0000\u0000\u0000\u0484\u0481\u0001"+
		"\u0000\u0000\u0000\u0484\u0485\u0001\u0000\u0000\u0000\u0485\u0486\u0001"+
		"\u0000\u0000\u0000\u0486\u0487\u0003\u00eew\u0000\u0487\u0488\u0005\u00a0"+
		"\u0000\u0000\u0488\u0489\u0003\u0014\n\u0000\u0489\u048a\u0005\u00a0\u0000"+
		"\u0000\u048a\u048b\u0003\u0016\u000b\u0000\u048b\u048c\u0005\u00a0\u0000"+
		"\u0000\u048c\u048f\u0003\u0016\u000b\u0000\u048d\u048e\u0005\u00a0\u0000"+
		"\u0000\u048e\u0490\u0003\u0016\u000b\u0000\u048f\u048d\u0001\u0000\u0000"+
		"\u0000\u048f\u0490\u0001\u0000\u0000\u0000\u0490\u0491\u0001\u0000\u0000"+
		"\u0000\u0491\u0492\u0003\"\u0011\u0000\u0492s\u0001\u0000\u0000\u0000"+
		"\u0493\u0497\u0003 \u0010\u0000\u0494\u0495\u0003\u00eew\u0000\u0495\u0496"+
		"\u0005\u00a0\u0000\u0000\u0496\u0498\u0001\u0000\u0000\u0000\u0497\u0494"+
		"\u0001\u0000\u0000\u0000\u0497\u0498\u0001\u0000\u0000\u0000\u0498\u0499"+
		"\u0001\u0000\u0000\u0000\u0499\u049a\u0003\u00eew\u0000\u049a\u049b\u0005"+
		"\u00a0\u0000\u0000\u049b\u049c\u0003\u0012\t\u0000\u049c\u049d\u0005\u00a0"+
		"\u0000\u0000\u049d\u04a0\u0003\u0014\n\u0000\u049e\u049f\u0005\u00a0\u0000"+
		"\u0000\u049f\u04a1\u0003\u0016\u000b\u0000\u04a0\u049e\u0001\u0000\u0000"+
		"\u0000\u04a0\u04a1\u0001\u0000\u0000\u0000\u04a1\u04a2\u0001\u0000\u0000"+
		"\u0000\u04a2\u04a3\u0003\"\u0011\u0000\u04a3u\u0001\u0000\u0000\u0000"+
		"\u04a4\u04a8\u0003 \u0010\u0000\u04a5\u04a6\u0003\u00eew\u0000\u04a6\u04a7"+
		"\u0005\u00a0\u0000\u0000\u04a7\u04a9\u0001\u0000\u0000\u0000\u04a8\u04a5"+
		"\u0001\u0000\u0000\u0000\u04a8\u04a9\u0001\u0000\u0000\u0000\u04a9\u04aa"+
		"\u0001\u0000\u0000\u0000\u04aa\u04ab\u0003\u00eew\u0000\u04ab\u04ac\u0005"+
		"\u00a0\u0000\u0000\u04ac\u04ad\u0003\u0012\t\u0000\u04ad\u04ae\u0005\u00a0"+
		"\u0000\u0000\u04ae\u04af\u0003\u0014\n\u0000\u04af\u04b0\u0005\u00a0\u0000"+
		"\u0000\u04b0\u04b1\u0003\u0016\u000b\u0000\u04b1\u04b2\u0005\u00a0\u0000"+
		"\u0000\u04b2\u04b5\u0003\u0016\u000b\u0000\u04b3\u04b4\u0005\u00a0\u0000"+
		"\u0000\u04b4\u04b6\u0003\u0016\u000b\u0000\u04b5\u04b3\u0001\u0000\u0000"+
		"\u0000\u04b5\u04b6\u0001\u0000\u0000\u0000\u04b6\u04b7\u0001\u0000\u0000"+
		"\u0000\u04b7\u04b8\u0003\"\u0011\u0000\u04b8w\u0001\u0000\u0000\u0000"+
		"\u04b9\u04bd\u0003 \u0010\u0000\u04ba\u04bb\u0003\u00eew\u0000\u04bb\u04bc"+
		"\u0005\u00a0\u0000\u0000\u04bc\u04be\u0001\u0000\u0000\u0000\u04bd\u04ba"+
		"\u0001\u0000\u0000\u0000\u04bd\u04be\u0001\u0000\u0000\u0000\u04be\u04bf"+
		"\u0001\u0000\u0000\u0000\u04bf\u04c0\u0003\u00eew\u0000\u04c0\u04c1\u0005"+
		"\u00a0\u0000\u0000\u04c1\u04c2\u0003\u0016\u000b\u0000\u04c2\u04c3\u0003"+
		"\"\u0011\u0000\u04c3y\u0001\u0000\u0000\u0000\u04c4\u04c8\u0003 \u0010"+
		"\u0000\u04c5\u04c6\u0003\u00eew\u0000\u04c6\u04c7\u0005\u00a0\u0000\u0000"+
		"\u04c7\u04c9\u0001\u0000\u0000\u0000\u04c8\u04c5\u0001\u0000\u0000\u0000"+
		"\u04c8\u04c9\u0001\u0000\u0000\u0000\u04c9\u04ca\u0001\u0000\u0000\u0000"+
		"\u04ca\u04cb\u0003\u00eew\u0000\u04cb\u04cc\u0005\u00a0\u0000\u0000\u04cc"+
		"\u04cd\u0003\u0016\u000b\u0000\u04cd\u04ce\u0005\u00a0\u0000\u0000\u04ce"+
		"\u04cf\u0003\u0016\u000b\u0000\u04cf\u04d0\u0003\"\u0011\u0000\u04d0{"+
		"\u0001\u0000\u0000\u0000\u04d1\u04d5\u0003 \u0010\u0000\u04d2\u04d3\u0003"+
		"\u00eew\u0000\u04d3\u04d4\u0005\u00a0\u0000\u0000\u04d4\u04d6\u0001\u0000"+
		"\u0000\u0000\u04d5\u04d2\u0001\u0000\u0000\u0000\u04d5\u04d6\u0001\u0000"+
		"\u0000\u0000\u04d6\u04d7\u0001\u0000\u0000\u0000\u04d7\u04d8\u0003\u00ee"+
		"w\u0000\u04d8\u04d9\u0005\u00a0\u0000\u0000\u04d9\u04da\u0003\u0016\u000b"+
		"\u0000\u04da\u04db\u0005\u00a0\u0000\u0000\u04db\u04dc\u0003\u0016\u000b"+
		"\u0000\u04dc\u04dd\u0005\u00a0\u0000\u0000\u04dd\u04e0\u0003\u0016\u000b"+
		"\u0000\u04de\u04df\u0005\u00a0\u0000\u0000\u04df\u04e1\u0003\u0016\u000b"+
		"\u0000\u04e0\u04de\u0001\u0000\u0000\u0000\u04e0\u04e1\u0001\u0000\u0000"+
		"\u0000\u04e1\u04e2\u0001\u0000\u0000\u0000\u04e2\u04e3\u0003\"\u0011\u0000"+
		"\u04e3}\u0001\u0000\u0000\u0000\u04e4\u04e8\u0003 \u0010\u0000\u04e5\u04e6"+
		"\u0003\u00eew\u0000\u04e6\u04e7\u0005\u00a0\u0000\u0000\u04e7\u04e9\u0001"+
		"\u0000\u0000\u0000\u04e8\u04e5\u0001\u0000\u0000\u0000\u04e8\u04e9\u0001"+
		"\u0000\u0000\u0000\u04e9\u04ea\u0001\u0000\u0000\u0000\u04ea\u04eb\u0003"+
		"\u00eew\u0000\u04eb\u04ec\u0005\u00a0\u0000\u0000\u04ec\u04ed\u0003\u0012"+
		"\t\u0000\u04ed\u04ee\u0005\u00a0\u0000\u0000\u04ee\u04ef\u0003\u0016\u000b"+
		"\u0000\u04ef\u04f0\u0005\u00a0\u0000\u0000\u04f0\u04f1\u0003\u0016\u000b"+
		"\u0000\u04f1\u04f2\u0003\"\u0011\u0000\u04f2\u007f\u0001\u0000\u0000\u0000"+
		"\u04f3\u04f7\u0003 \u0010\u0000\u04f4\u04f5\u0003\u00eew\u0000\u04f5\u04f6"+
		"\u0005\u00a0\u0000\u0000\u04f6\u04f8\u0001\u0000\u0000\u0000\u04f7\u04f4"+
		"\u0001\u0000\u0000\u0000\u04f7\u04f8\u0001\u0000\u0000\u0000\u04f8\u04f9"+
		"\u0001\u0000\u0000\u0000\u04f9\u04fa\u0003\u00eew\u0000\u04fa\u04fb\u0005"+
		"\u00a0\u0000\u0000\u04fb\u04fc\u0003\u0012\t\u0000\u04fc\u04fd\u0005\u00a0"+
		"\u0000\u0000\u04fd\u04fe\u0003\u0016\u000b\u0000\u04fe\u04ff\u0005\u00a0"+
		"\u0000\u0000\u04ff\u0500\u0003\u0016\u000b\u0000\u0500\u0501\u0005\u00a0"+
		"\u0000\u0000\u0501\u0504\u0003\u0016\u000b\u0000\u0502\u0503\u0005\u00a0"+
		"\u0000\u0000\u0503\u0505\u0003\u0016\u000b\u0000\u0504\u0502\u0001\u0000"+
		"\u0000\u0000\u0504\u0505\u0001\u0000\u0000\u0000\u0505\u0506\u0001\u0000"+
		"\u0000\u0000\u0506\u0507\u0003\"\u0011\u0000\u0507\u0081\u0001\u0000\u0000"+
		"\u0000\u0508\u050c\u0003 \u0010\u0000\u0509\u050a\u0003\u00eew\u0000\u050a"+
		"\u050b\u0005\u00a0\u0000\u0000\u050b\u050d\u0001\u0000\u0000\u0000\u050c"+
		"\u0509\u0001\u0000\u0000\u0000\u050c\u050d\u0001\u0000\u0000\u0000\u050d"+
		"\u050e\u0001\u0000\u0000\u0000\u050e\u050f\u0003\u00eew\u0000\u050f\u0510"+
		"\u0005\u00a0\u0000\u0000\u0510\u0511\u0003\u0014\n\u0000\u0511\u0512\u0005"+
		"\u00a0\u0000\u0000\u0512\u0513\u0003\u0016\u000b\u0000\u0513\u0514\u0005"+
		"\u00a0\u0000\u0000\u0514\u0515\u0003\u0016\u000b\u0000\u0515\u0516\u0003"+
		"\"\u0011\u0000\u0516\u0083\u0001\u0000\u0000\u0000\u0517\u051b\u0003 "+
		"\u0010\u0000\u0518\u0519\u0003\u00eew\u0000\u0519\u051a\u0005\u00a0\u0000"+
		"\u0000\u051a\u051c\u0001\u0000\u0000\u0000\u051b\u0518\u0001\u0000\u0000"+
		"\u0000\u051b\u051c\u0001\u0000\u0000\u0000\u051c\u051d\u0001\u0000\u0000"+
		"\u0000\u051d\u051e\u0003\u00eew\u0000\u051e\u051f\u0005\u00a0\u0000\u0000"+
		"\u051f\u0520\u0003\u0014\n\u0000\u0520\u0521\u0005\u00a0\u0000\u0000\u0521"+
		"\u0522\u0003\u0016\u000b\u0000\u0522\u0523\u0005\u00a0\u0000\u0000\u0523"+
		"\u0524\u0003\u0016\u000b\u0000\u0524\u0525\u0005\u00a0\u0000\u0000\u0525"+
		"\u0528\u0003\u0016\u000b\u0000\u0526\u0527\u0005\u00a0\u0000\u0000\u0527"+
		"\u0529\u0003\u0016\u000b\u0000\u0528\u0526\u0001\u0000\u0000\u0000\u0528"+
		"\u0529\u0001\u0000\u0000\u0000\u0529\u052a\u0001\u0000\u0000\u0000\u052a"+
		"\u052b\u0003\"\u0011\u0000\u052b\u0085\u0001\u0000\u0000\u0000\u052c\u0530"+
		"\u0003 \u0010\u0000\u052d\u052e\u0003\u00eew\u0000\u052e\u052f\u0005\u00a0"+
		"\u0000\u0000\u052f\u0531\u0001\u0000\u0000\u0000\u0530\u052d\u0001\u0000"+
		"\u0000\u0000\u0530\u0531\u0001\u0000\u0000\u0000\u0531\u0532\u0001\u0000"+
		"\u0000\u0000\u0532\u0533\u0003\u00eew\u0000\u0533\u0534\u0005\u00a0\u0000"+
		"\u0000\u0534\u0535\u0003\u0012\t\u0000\u0535\u0536\u0005\u00a0\u0000\u0000"+
		"\u0536\u0537\u0003\u0014\n\u0000\u0537\u0538\u0005\u00a0\u0000\u0000\u0538"+
		"\u0539\u0003\u0016\u000b\u0000\u0539\u053a\u0005\u00a0\u0000\u0000\u053a"+
		"\u053b\u0003\u0016\u000b\u0000\u053b\u053c\u0003\"\u0011\u0000\u053c\u0087"+
		"\u0001\u0000\u0000\u0000\u053d\u0541\u0003 \u0010\u0000\u053e\u053f\u0003"+
		"\u00eew\u0000\u053f\u0540\u0005\u00a0\u0000\u0000\u0540\u0542\u0001\u0000"+
		"\u0000\u0000\u0541\u053e\u0001\u0000\u0000\u0000\u0541\u0542\u0001\u0000"+
		"\u0000\u0000\u0542\u0543\u0001\u0000\u0000\u0000\u0543\u0544\u0003\u00ee"+
		"w\u0000\u0544\u0545\u0005\u00a0\u0000\u0000\u0545\u0546\u0003\u0012\t"+
		"\u0000\u0546\u0547\u0005\u00a0\u0000\u0000\u0547\u0548\u0003\u0014\n\u0000"+
		"\u0548\u0549\u0005\u00a0\u0000\u0000\u0549\u054a\u0003\u0016\u000b\u0000"+
		"\u054a\u054b\u0005\u00a0\u0000\u0000\u054b\u054c\u0003\u0016\u000b\u0000"+
		"\u054c\u054d\u0005\u00a0\u0000\u0000\u054d\u0550\u0003\u0016\u000b\u0000"+
		"\u054e\u054f\u0005\u00a0\u0000\u0000\u054f\u0551\u0003\u0016\u000b\u0000"+
		"\u0550\u054e\u0001\u0000\u0000\u0000\u0550\u0551\u0001\u0000\u0000\u0000"+
		"\u0551\u0552\u0001\u0000\u0000\u0000\u0552\u0553\u0003\"\u0011\u0000\u0553"+
		"\u0089\u0001\u0000\u0000\u0000\u0554\u056f\u0003 \u0010\u0000\u0555\u0556"+
		"\u0003\u00eew\u0000\u0556\u0557\u0005\u00a0\u0000\u0000\u0557\u0559\u0001"+
		"\u0000\u0000\u0000\u0558\u0555\u0001\u0000\u0000\u0000\u0558\u0559\u0001"+
		"\u0000\u0000\u0000\u0559\u055a\u0001\u0000\u0000\u0000\u055a\u0570\u0003"+
		"\u00ecv\u0000\u055b\u055c\u0003\u00eew\u0000\u055c\u055d\u0005\u00a0\u0000"+
		"\u0000\u055d\u055f\u0001\u0000\u0000\u0000\u055e\u055b\u0001\u0000\u0000"+
		"\u0000\u055e\u055f\u0001\u0000\u0000\u0000\u055f\u0560\u0001\u0000\u0000"+
		"\u0000\u0560\u0561\u0003\u00ecv\u0000\u0561\u0562\u0005\u00a0\u0000\u0000"+
		"\u0562\u0563\u0003\u0016\u000b\u0000\u0563\u0570\u0001\u0000\u0000\u0000"+
		"\u0564\u0565\u0003\u00eew\u0000\u0565\u0566\u0005\u00a0\u0000\u0000\u0566"+
		"\u0568\u0001\u0000\u0000\u0000\u0567\u0564\u0001\u0000\u0000\u0000\u0567"+
		"\u0568\u0001\u0000\u0000\u0000\u0568\u0569\u0001\u0000\u0000\u0000\u0569"+
		"\u056a\u0003\u00ecv\u0000\u056a\u056b\u0005\u00a0\u0000\u0000\u056b\u056c"+
		"\u0003\u0016\u000b\u0000\u056c\u056d\u0005\u00a0\u0000\u0000\u056d\u056e"+
		"\u0003\u0016\u000b\u0000\u056e\u0570\u0001\u0000\u0000\u0000\u056f\u0558"+
		"\u0001\u0000\u0000\u0000\u056f\u055e\u0001\u0000\u0000\u0000\u056f\u0567"+
		"\u0001\u0000\u0000\u0000\u0570\u0571\u0001\u0000\u0000\u0000\u0571\u0572"+
		"\u0003\"\u0011\u0000\u0572\u008b\u0001\u0000\u0000\u0000\u0573\u0584\u0003"+
		" \u0010\u0000\u0574\u0585\u0003\u00eew\u0000\u0575\u0576\u0003\u00eew"+
		"\u0000\u0576\u0577\u0005\u00a0\u0000\u0000\u0577\u0579\u0001\u0000\u0000"+
		"\u0000\u0578\u0575\u0001\u0000\u0000\u0000\u0578\u0579\u0001\u0000\u0000"+
		"\u0000\u0579\u057a\u0001\u0000\u0000\u0000\u057a\u0585\u0003\u0016\u000b"+
		"\u0000\u057b\u057c\u0003\u00eew\u0000\u057c\u057d\u0005\u00a0\u0000\u0000"+
		"\u057d\u057f\u0001\u0000\u0000\u0000\u057e\u057b\u0001\u0000\u0000\u0000"+
		"\u057e\u057f\u0001\u0000\u0000\u0000\u057f\u0580\u0001\u0000\u0000\u0000"+
		"\u0580\u0581\u0003\u0016\u000b\u0000\u0581\u0582\u0005\u00a0\u0000\u0000"+
		"\u0582\u0583\u0003\u0016\u000b\u0000\u0583\u0585\u0001\u0000\u0000\u0000"+
		"\u0584\u0574\u0001\u0000\u0000\u0000\u0584\u0578\u0001\u0000\u0000\u0000"+
		"\u0584\u057e\u0001\u0000\u0000\u0000\u0585\u0586\u0001\u0000\u0000\u0000"+
		"\u0586\u0587\u0003\"\u0011\u0000\u0587\u008d\u0001\u0000\u0000\u0000\u0588"+
		"\u0590\u0003 \u0010\u0000\u0589\u0591\u0003\u00eew\u0000\u058a\u058b\u0003"+
		"\u00eew\u0000\u058b\u058c\u0005\u00a0\u0000\u0000\u058c\u058e\u0001\u0000"+
		"\u0000\u0000\u058d\u058a\u0001\u0000\u0000\u0000\u058d\u058e\u0001\u0000"+
		"\u0000\u0000\u058e\u058f\u0001\u0000\u0000\u0000\u058f\u0591\u0003\u0016"+
		"\u000b\u0000\u0590\u0589\u0001\u0000\u0000\u0000\u0590\u058d\u0001\u0000"+
		"\u0000\u0000\u0591\u0592\u0001\u0000\u0000\u0000\u0592\u0593\u0003\"\u0011"+
		"\u0000\u0593\u008f\u0001\u0000\u0000\u0000\u0594\u0598\u0003 \u0010\u0000"+
		"\u0595\u0596\u0003\u00eew\u0000\u0596\u0597\u0005\u00a0\u0000\u0000\u0597"+
		"\u0599\u0001\u0000\u0000\u0000\u0598\u0595\u0001\u0000\u0000\u0000\u0598"+
		"\u0599\u0001\u0000\u0000\u0000\u0599\u059a\u0001\u0000\u0000\u0000\u059a"+
		"\u059b\u0003\u0016\u000b\u0000\u059b\u059c\u0005\u00a0\u0000\u0000\u059c"+
		"\u059d\u0003\u0016\u000b\u0000\u059d\u059e\u0003\"\u0011\u0000\u059e\u0091"+
		"\u0001\u0000\u0000\u0000\u059f\u05a3\u0003 \u0010\u0000\u05a0\u05a1\u0003"+
		"\u00eew\u0000\u05a1\u05a2\u0005\u00a0\u0000\u0000\u05a2\u05a4\u0001\u0000"+
		"\u0000\u0000\u05a3\u05a0\u0001\u0000\u0000\u0000\u05a3\u05a4\u0001\u0000"+
		"\u0000\u0000\u05a4\u05a5\u0001\u0000\u0000\u0000\u05a5\u05a6\u0003\u0016"+
		"\u000b\u0000\u05a6\u05a7\u0005\u00a0\u0000\u0000\u05a7\u05a8\u0003\u0016"+
		"\u000b\u0000\u05a8\u05a9\u0005\u00a0\u0000\u0000\u05a9\u05ac\u0003\u0016"+
		"\u000b\u0000\u05aa\u05ab\u0005\u00a0\u0000\u0000\u05ab\u05ad\u0003\u0016"+
		"\u000b\u0000\u05ac\u05aa\u0001\u0000\u0000\u0000\u05ac\u05ad\u0001\u0000"+
		"\u0000\u0000\u05ad\u05ae\u0001\u0000\u0000\u0000\u05ae\u05af\u0003\"\u0011"+
		"\u0000\u05af\u0093\u0001\u0000\u0000\u0000\u05b0\u05b8\u0003 \u0010\u0000"+
		"\u05b1\u05b9\u0003\u00eew\u0000\u05b2\u05b3\u0003\u00eew\u0000\u05b3\u05b4"+
		"\u0005\u00a0\u0000\u0000\u05b4\u05b6\u0001\u0000\u0000\u0000\u05b5\u05b2"+
		"\u0001\u0000\u0000\u0000\u05b5\u05b6\u0001\u0000\u0000\u0000\u05b6\u05b7"+
		"\u0001\u0000\u0000\u0000\u05b7\u05b9\u0003\u0016\u000b\u0000\u05b8\u05b1"+
		"\u0001\u0000\u0000\u0000\u05b8\u05b5\u0001\u0000\u0000\u0000\u05b9\u05ba"+
		"\u0001\u0000\u0000\u0000\u05ba\u05bb\u0003\"\u0011\u0000\u05bb\u0095\u0001"+
		"\u0000\u0000\u0000\u05bc\u05c0\u0003 \u0010\u0000\u05bd\u05be\u0003\u00ee"+
		"w\u0000\u05be\u05bf\u0005\u00a0\u0000\u0000\u05bf\u05c1\u0001\u0000\u0000"+
		"\u0000\u05c0\u05bd\u0001\u0000\u0000\u0000\u05c0\u05c1\u0001\u0000\u0000"+
		"\u0000\u05c1\u05c2\u0001\u0000\u0000\u0000\u05c2\u05c3\u0003\u0016\u000b"+
		"\u0000\u05c3\u05c4\u0005\u00a0\u0000\u0000\u05c4\u05c5\u0003\u0016\u000b"+
		"\u0000\u05c5\u05c6\u0003\"\u0011\u0000\u05c6\u0097\u0001\u0000\u0000\u0000"+
		"\u05c7\u05c8\u0003 \u0010\u0000\u05c8\u05c9\u0003\u00eew\u0000\u05c9\u05ca"+
		"\u0003\"\u0011\u0000\u05ca\u0099\u0001\u0000\u0000\u0000\u05cb\u05cc\u0003"+
		" \u0010\u0000\u05cc\u05cd\u0003\u00eew\u0000\u05cd\u05ce\u0005\u00a0\u0000"+
		"\u0000\u05ce\u05d1\u0003\u00aaU\u0000\u05cf\u05d0\u0005\u00a0\u0000\u0000"+
		"\u05d0\u05d2\u0003\u00acV\u0000\u05d1\u05cf\u0001\u0000\u0000\u0000\u05d1"+
		"\u05d2\u0001\u0000\u0000\u0000\u05d2\u05d5\u0001\u0000\u0000\u0000\u05d3"+
		"\u05d4\u0005\u00a0\u0000\u0000\u05d4\u05d6\u0003\u00a8T\u0000\u05d5\u05d3"+
		"\u0001\u0000\u0000\u0000\u05d5\u05d6\u0001\u0000\u0000\u0000\u05d6\u05d7"+
		"\u0001\u0000\u0000\u0000\u05d7\u05d8\u0003\"\u0011\u0000\u05d8\u009b\u0001"+
		"\u0000\u0000\u0000\u05d9\u05da\u0003 \u0010\u0000\u05da\u05db\u0003\u00ee"+
		"w\u0000\u05db\u05dc\u0005\u00a0\u0000\u0000\u05dc\u05df\u0003\u00acV\u0000"+
		"\u05dd\u05de\u0005\u00a0\u0000\u0000\u05de\u05e0\u0003\u00a8T\u0000\u05df"+
		"\u05dd\u0001\u0000\u0000\u0000\u05df\u05e0\u0001\u0000\u0000\u0000\u05e0"+
		"\u05e1\u0001\u0000\u0000\u0000\u05e1\u05e2\u0003\"\u0011\u0000\u05e2\u009d"+
		"\u0001\u0000\u0000\u0000\u05e3\u05e4\u0003 \u0010\u0000\u05e4\u05e5\u0003"+
		"\u00eew\u0000\u05e5\u05e6\u0005\u00a0\u0000\u0000\u05e6\u05e7\u0003\u00a8"+
		"T\u0000\u05e7\u05e8\u0003\"\u0011\u0000\u05e8\u009f\u0001\u0000\u0000"+
		"\u0000\u05e9\u05ea\u0003 \u0010\u0000\u05ea\u05ed\u0003\u00aaU\u0000\u05eb"+
		"\u05ec\u0005\u00a0\u0000\u0000\u05ec\u05ee\u0003\u00acV\u0000\u05ed\u05eb"+
		"\u0001\u0000\u0000\u0000\u05ed\u05ee\u0001\u0000\u0000\u0000\u05ee\u05f1"+
		"\u0001\u0000\u0000\u0000\u05ef\u05f0\u0005\u00a0\u0000\u0000\u05f0\u05f2"+
		"\u0003\u00a8T\u0000\u05f1\u05ef\u0001\u0000\u0000\u0000\u05f1\u05f2\u0001"+
		"\u0000\u0000\u0000\u05f2\u05f3\u0001\u0000\u0000\u0000\u05f3\u05f4\u0003"+
		"\"\u0011\u0000\u05f4\u00a1\u0001\u0000\u0000\u0000\u05f5\u05f6\u0003 "+
		"\u0010\u0000\u05f6\u05f9\u0003\u00acV\u0000\u05f7\u05f8\u0005\u00a0\u0000"+
		"\u0000\u05f8\u05fa\u0003\u00a8T\u0000\u05f9\u05f7\u0001\u0000\u0000\u0000"+
		"\u05f9\u05fa\u0001\u0000\u0000\u0000\u05fa\u05fb\u0001\u0000\u0000\u0000"+
		"\u05fb\u05fc\u0003\"\u0011\u0000\u05fc\u00a3\u0001\u0000\u0000\u0000\u05fd"+
		"\u05fe\u0003 \u0010\u0000\u05fe\u05ff\u0003\u00a8T\u0000\u05ff\u0600\u0003"+
		"\"\u0011\u0000\u0600\u00a5\u0001\u0000\u0000\u0000\u0601\u0602\u0003 "+
		"\u0010\u0000\u0602\u0605\u0003\u00eew\u0000\u0603\u0604\u0005\u00a0\u0000"+
		"\u0000\u0604\u0606\u0003\u00eew\u0000\u0605\u0603\u0001\u0000\u0000\u0000"+
		"\u0605\u0606\u0001\u0000\u0000\u0000\u0606\u0609\u0001\u0000\u0000\u0000"+
		"\u0607\u0608\u0005\u00a0\u0000\u0000\u0608\u060a\u0003\u00aaU\u0000\u0609"+
		"\u0607\u0001\u0000\u0000\u0000\u0609\u060a\u0001\u0000\u0000\u0000\u060a"+
		"\u060d\u0001\u0000\u0000\u0000\u060b\u060c\u0005\u00a0\u0000\u0000\u060c"+
		"\u060e\u0003\u00acV\u0000\u060d\u060b\u0001\u0000\u0000\u0000\u060d\u060e"+
		"\u0001\u0000\u0000\u0000\u060e\u0611\u0001\u0000\u0000\u0000\u060f\u0610"+
		"\u0005\u00a0\u0000\u0000\u0610\u0612\u0003\u00a8T\u0000\u0611\u060f\u0001"+
		"\u0000\u0000\u0000\u0611\u0612\u0001\u0000\u0000\u0000\u0612\u0613\u0001"+
		"\u0000\u0000\u0000\u0613\u0614\u0003\"\u0011\u0000\u0614\u00a7\u0001\u0000"+
		"\u0000\u0000\u0615\u061b\u0003\u0016\u000b\u0000\u0616\u0617\u0003\u0016"+
		"\u000b\u0000\u0617\u0618\u0005\u00a0\u0000\u0000\u0618\u0619\u0003\u0016"+
		"\u000b\u0000\u0619\u061b\u0001\u0000\u0000\u0000\u061a\u0615\u0001\u0000"+
		"\u0000\u0000\u061a\u0616\u0001\u0000\u0000\u0000\u061b\u00a9\u0001\u0000"+
		"\u0000\u0000\u061c\u0622\u0003\u0012\t\u0000\u061d\u061e\u0003\u0012\t"+
		"\u0000\u061e\u061f\u0005\u00a0\u0000\u0000\u061f\u0620\u0003\u0012\t\u0000"+
		"\u0620\u0622\u0001\u0000\u0000\u0000\u0621\u061c\u0001\u0000\u0000\u0000"+
		"\u0621\u061d\u0001\u0000\u0000\u0000\u0622\u00ab\u0001\u0000\u0000\u0000"+
		"\u0623\u0629\u0003\u0014\n\u0000\u0624\u0625\u0003\u0014\n\u0000\u0625"+
		"\u0626\u0005\u00a0\u0000\u0000\u0626\u0627\u0003\u0014\n\u0000\u0627\u0629"+
		"\u0001\u0000\u0000\u0000\u0628\u0623\u0001\u0000\u0000\u0000\u0628\u0624"+
		"\u0001\u0000\u0000\u0000\u0629\u00ad\u0001\u0000\u0000\u0000\u062a\u062b"+
		"\u0003 \u0010\u0000\u062b\u062c\u0003\u00eew\u0000\u062c\u062d\u0003\""+
		"\u0011\u0000\u062d\u00af\u0001\u0000\u0000\u0000\u062e\u062f\u0003 \u0010"+
		"\u0000\u062f\u0630\u0003\u00eew\u0000\u0630\u0631\u0005\u00a0\u0000\u0000"+
		"\u0631\u0634\u0003\u00aaU\u0000\u0632\u0633\u0005\u00a0\u0000\u0000\u0633"+
		"\u0635\u0003\u00acV\u0000\u0634\u0632\u0001\u0000\u0000\u0000\u0634\u0635"+
		"\u0001\u0000\u0000\u0000\u0635\u0638\u0001\u0000\u0000\u0000\u0636\u0637"+
		"\u0005\u00a0\u0000\u0000\u0637\u0639\u0003\u00be_\u0000\u0638\u0636\u0001"+
		"\u0000\u0000\u0000\u0638\u0639\u0001\u0000\u0000\u0000\u0639\u063a\u0001"+
		"\u0000\u0000\u0000\u063a\u063b\u0003\"\u0011\u0000\u063b\u00b1\u0001\u0000"+
		"\u0000\u0000\u063c\u063d\u0003 \u0010\u0000\u063d\u063e\u0003\u00eew\u0000"+
		"\u063e\u063f\u0005\u00a0\u0000\u0000\u063f\u0642\u0003\u00acV\u0000\u0640"+
		"\u0641\u0005\u00a0\u0000\u0000\u0641\u0643\u0003\u00be_\u0000\u0642\u0640"+
		"\u0001\u0000\u0000\u0000\u0642\u0643\u0001\u0000\u0000\u0000\u0643\u0644"+
		"\u0001\u0000\u0000\u0000\u0644\u0645\u0003\"\u0011\u0000\u0645\u00b3\u0001"+
		"\u0000\u0000\u0000\u0646\u0647\u0003 \u0010\u0000\u0647\u0648\u0003\u00ee"+
		"w\u0000\u0648\u0649\u0005\u00a0\u0000\u0000\u0649\u064a\u0003\u00be_\u0000"+
		"\u064a\u064b\u0003\"\u0011\u0000\u064b\u00b5\u0001\u0000\u0000\u0000\u064c"+
		"\u064d\u0003 \u0010\u0000\u064d\u0650\u0003\u00aaU\u0000\u064e\u064f\u0005"+
		"\u00a0\u0000\u0000\u064f\u0651\u0003\u00acV\u0000\u0650\u064e\u0001\u0000"+
		"\u0000\u0000\u0650\u0651\u0001\u0000\u0000\u0000\u0651\u0654\u0001\u0000"+
		"\u0000\u0000\u0652\u0653\u0005\u00a0\u0000\u0000\u0653\u0655\u0003\u00be"+
		"_\u0000\u0654\u0652\u0001\u0000\u0000\u0000\u0654\u0655\u0001\u0000\u0000"+
		"\u0000\u0655\u0656\u0001\u0000\u0000\u0000\u0656\u0657\u0003\"\u0011\u0000"+
		"\u0657\u00b7\u0001\u0000\u0000\u0000\u0658\u0659\u0003 \u0010\u0000\u0659"+
		"\u065c\u0003\u00acV\u0000\u065a\u065b\u0005\u00a0\u0000\u0000\u065b\u065d"+
		"\u0003\u00be_\u0000\u065c\u065a\u0001\u0000\u0000\u0000\u065c\u065d\u0001"+
		"\u0000\u0000\u0000\u065d\u065e\u0001\u0000\u0000\u0000\u065e\u065f\u0003"+
		"\"\u0011\u0000\u065f\u00b9\u0001\u0000\u0000\u0000\u0660\u0661\u0003 "+
		"\u0010\u0000\u0661\u0662\u0003\u00be_\u0000\u0662\u0663\u0003\"\u0011"+
		"\u0000\u0663\u00bb\u0001\u0000\u0000\u0000\u0664\u0665\u0003 \u0010\u0000"+
		"\u0665\u0668\u0003\u00eew\u0000\u0666\u0667\u0005\u00a0\u0000\u0000\u0667"+
		"\u0669\u0003\u00eew\u0000\u0668\u0666\u0001\u0000\u0000\u0000\u0668\u0669"+
		"\u0001\u0000\u0000\u0000\u0669\u066c\u0001\u0000\u0000\u0000\u066a\u066b"+
		"\u0005\u00a0\u0000\u0000\u066b\u066d\u0003\u00aaU\u0000\u066c\u066a\u0001"+
		"\u0000\u0000\u0000\u066c\u066d\u0001\u0000\u0000\u0000\u066d\u0670\u0001"+
		"\u0000\u0000\u0000\u066e\u066f\u0005\u00a0\u0000\u0000\u066f\u0671\u0003"+
		"\u00acV\u0000\u0670\u066e\u0001\u0000\u0000\u0000\u0670\u0671\u0001\u0000"+
		"\u0000\u0000\u0671\u0674\u0001\u0000\u0000\u0000\u0672\u0673\u0005\u00a0"+
		"\u0000\u0000\u0673\u0675\u0003\u00be_\u0000\u0674\u0672\u0001\u0000\u0000"+
		"\u0000\u0674\u0675\u0001\u0000\u0000\u0000\u0675\u0676\u0001\u0000\u0000"+
		"\u0000\u0676\u0677\u0003\"\u0011\u0000\u0677\u00bd\u0001\u0000\u0000\u0000"+
		"\u0678\u067d\u0003\u0016\u000b\u0000\u0679\u067a\u0005\u00a0\u0000\u0000"+
		"\u067a\u067c\u0003\u0016\u000b\u0000\u067b\u0679\u0001\u0000\u0000\u0000"+
		"\u067c\u067f\u0001\u0000\u0000\u0000\u067d\u067b\u0001\u0000\u0000\u0000"+
		"\u067d\u067e\u0001\u0000\u0000\u0000\u067e\u00bf\u0001\u0000\u0000\u0000"+
		"\u067f\u067d\u0001\u0000\u0000\u0000\u0680\u0681\u0003 \u0010\u0000\u0681"+
		"\u0682\u0003\u00eew\u0000\u0682\u0683\u0005\u00a0\u0000\u0000\u0683\u0684"+
		"\u0003\u00ecv\u0000\u0684\u0685\u0003\"\u0011\u0000\u0685\u00c1\u0001"+
		"\u0000\u0000\u0000\u0686\u0687\u0003 \u0010\u0000\u0687\u0688\u0003\u00ee"+
		"w\u0000\u0688\u0696\u0005\u00a0\u0000\u0000\u0689\u068a\u0003\u00eew\u0000"+
		"\u068a\u068b\u0005\u00a0\u0000\u0000\u068b\u068d\u0001\u0000\u0000\u0000"+
		"\u068c\u0689\u0001\u0000\u0000\u0000\u068c\u068d\u0001\u0000\u0000\u0000"+
		"\u068d\u068e\u0001\u0000\u0000\u0000\u068e\u068f\u0003\u0016\u000b\u0000"+
		"\u068f\u0690\u0005\u00a0\u0000\u0000\u0690\u0691\u0003\u00ecv\u0000\u0691"+
		"\u0697\u0001\u0000\u0000\u0000\u0692\u0693\u0003\u00ecv\u0000\u0693\u0694"+
		"\u0005\u00a0\u0000\u0000\u0694\u0695\u0003\u0016\u000b\u0000\u0695\u0697"+
		"\u0001\u0000\u0000\u0000\u0696\u068c\u0001\u0000\u0000\u0000\u0696\u0692"+
		"\u0001\u0000\u0000\u0000\u0697\u0698\u0001\u0000\u0000\u0000\u0698\u0699"+
		"\u0003\"\u0011\u0000\u0699\u00c3\u0001\u0000\u0000\u0000\u069a\u069b\u0003"+
		" \u0010\u0000\u069b\u069c\u0003\u00eew\u0000\u069c\u069d\u0005\u00a0\u0000"+
		"\u0000\u069d\u069e\u0003\u00ecv\u0000\u069e\u069f\u0003\"\u0011\u0000"+
		"\u069f\u00c5\u0001\u0000\u0000\u0000\u06a0\u06a1\u0003 \u0010\u0000\u06a1"+
		"\u06a4\u0003\u00eew\u0000\u06a2\u06a3\u0005\u00a0\u0000\u0000\u06a3\u06a5"+
		"\u0003\u00eew\u0000\u06a4\u06a2\u0001\u0000\u0000\u0000\u06a4\u06a5\u0001"+
		"\u0000\u0000\u0000\u06a5\u06a6\u0001\u0000\u0000\u0000\u06a6\u06a7\u0003"+
		"\"\u0011\u0000\u06a7\u00c7\u0001\u0000\u0000\u0000\u06a8\u06a9\u0003 "+
		"\u0010\u0000\u06a9\u06aa\u0003\u00ecv\u0000\u06aa\u06ab\u0003\"\u0011"+
		"\u0000\u06ab\u00c9\u0001\u0000\u0000\u0000\u06ac\u06ad\u0003 \u0010\u0000"+
		"\u06ad\u06b2\u0003\u00eew\u0000\u06ae\u06af\u0005\u00a0\u0000\u0000\u06af"+
		"\u06b1\u0003\u0016\u000b\u0000\u06b0\u06ae\u0001\u0000\u0000\u0000\u06b1"+
		"\u06b4\u0001\u0000\u0000\u0000\u06b2\u06b0\u0001\u0000\u0000\u0000\u06b2"+
		"\u06b3\u0001\u0000\u0000\u0000\u06b3\u06b5\u0001\u0000\u0000\u0000\u06b4"+
		"\u06b2\u0001\u0000\u0000\u0000\u06b5\u06b6\u0003\"\u0011\u0000\u06b6\u00cb"+
		"\u0001\u0000\u0000\u0000\u06b7\u06b8\u0003 \u0010\u0000\u06b8\u06b9\u0003"+
		"\u00eew\u0000\u06b9\u06ba\u0005\u00a0\u0000\u0000\u06ba\u06bf\u0003\u0016"+
		"\u000b\u0000\u06bb\u06bc\u0005\u00a0\u0000\u0000\u06bc\u06be\u0003\u0016"+
		"\u000b\u0000\u06bd\u06bb\u0001\u0000\u0000\u0000\u06be\u06c1\u0001\u0000"+
		"\u0000\u0000\u06bf\u06bd\u0001\u0000\u0000\u0000\u06bf\u06c0\u0001\u0000"+
		"\u0000\u0000\u06c0\u06c2\u0001\u0000\u0000\u0000\u06c1\u06bf\u0001\u0000"+
		"\u0000\u0000\u06c2\u06c3\u0003\"\u0011\u0000\u06c3\u00cd\u0001\u0000\u0000"+
		"\u0000\u06c4\u06c5\u0003 \u0010\u0000\u06c5\u06c8\u0003\u0014\n\u0000"+
		"\u06c6\u06c7\u0005\u00a0\u0000\u0000\u06c7\u06c9\u0003\u0016\u000b\u0000"+
		"\u06c8\u06c6\u0001\u0000\u0000\u0000\u06c9\u06ca\u0001\u0000\u0000\u0000"+
		"\u06ca\u06c8\u0001\u0000\u0000\u0000\u06ca\u06cb\u0001\u0000\u0000\u0000"+
		"\u06cb\u06cc\u0001\u0000\u0000\u0000\u06cc\u06cd\u0003\"\u0011\u0000\u06cd"+
		"\u00cf\u0001\u0000\u0000\u0000\u06ce\u06cf\u0003 \u0010\u0000\u06cf\u06d2"+
		"\u0003\u00eew\u0000\u06d0\u06d1\u0005\u00a0\u0000\u0000\u06d1\u06d3\u0003"+
		"\u0016\u000b\u0000\u06d2\u06d0\u0001\u0000\u0000\u0000\u06d3\u06d4\u0001"+
		"\u0000\u0000\u0000\u06d4\u06d2\u0001\u0000\u0000\u0000\u06d4\u06d5\u0001"+
		"\u0000\u0000\u0000\u06d5\u06d6\u0001\u0000\u0000\u0000\u06d6\u06d7\u0003"+
		"\"\u0011\u0000\u06d7\u00d1\u0001\u0000\u0000\u0000\u06d8\u06d9\u0003 "+
		"\u0010\u0000\u06d9\u06da\u0003\u00eew\u0000\u06da\u06db\u0005\u00a0\u0000"+
		"\u0000\u06db\u06de\u0003\u00eew\u0000\u06dc\u06dd\u0005\u00a0\u0000\u0000"+
		"\u06dd\u06df\u0003\u0016\u000b\u0000\u06de\u06dc\u0001\u0000\u0000\u0000"+
		"\u06df\u06e0\u0001\u0000\u0000\u0000\u06e0\u06de\u0001\u0000\u0000\u0000"+
		"\u06e0\u06e1\u0001\u0000\u0000\u0000\u06e1\u06e2\u0001\u0000\u0000\u0000"+
		"\u06e2\u06e3\u0003\"\u0011\u0000\u06e3\u00d3\u0001\u0000\u0000\u0000\u06e4"+
		"\u06e5\u0003 \u0010\u0000\u06e5\u06e6\u0003\u00eew\u0000\u06e6\u06e7\u0005"+
		"\u00a0\u0000\u0000\u06e7\u06ea\u0003\u0014\n\u0000\u06e8\u06e9\u0005\u00a0"+
		"\u0000\u0000\u06e9\u06eb\u0003\u0016\u000b\u0000\u06ea\u06e8\u0001\u0000"+
		"\u0000\u0000\u06eb\u06ec\u0001\u0000\u0000\u0000\u06ec\u06ea\u0001\u0000"+
		"\u0000\u0000\u06ec\u06ed\u0001\u0000\u0000\u0000\u06ed\u06ee\u0001\u0000"+
		"\u0000\u0000\u06ee\u06ef\u0003\"\u0011\u0000\u06ef\u00d5\u0001\u0000\u0000"+
		"\u0000\u06f0\u06f1\u0003 \u0010\u0000\u06f1\u06f2\u0003\u00eew\u0000\u06f2"+
		"\u06f3\u0005\u00a0\u0000\u0000\u06f3\u06f4\u0003\u00eew\u0000\u06f4\u06f5"+
		"\u0005\u00a0\u0000\u0000\u06f5\u06f8\u0003\u0014\n\u0000\u06f6\u06f7\u0005"+
		"\u00a0\u0000\u0000\u06f7\u06f9\u0003\u0016\u000b\u0000\u06f8\u06f6\u0001"+
		"\u0000\u0000\u0000\u06f9\u06fa\u0001\u0000\u0000\u0000\u06fa\u06f8\u0001"+
		"\u0000\u0000\u0000\u06fa\u06fb\u0001\u0000\u0000\u0000\u06fb\u06fc\u0001"+
		"\u0000\u0000\u0000\u06fc\u06fd\u0003\"\u0011\u0000\u06fd\u00d7\u0001\u0000"+
		"\u0000\u0000\u06fe\u06ff\u0003 \u0010\u0000\u06ff\u0704\u0003\u0016\u000b"+
		"\u0000\u0700\u0701\u0005\u00a0\u0000\u0000\u0701\u0703\u0003\u0016\u000b"+
		"\u0000\u0702\u0700\u0001\u0000\u0000\u0000\u0703\u0706\u0001\u0000\u0000"+
		"\u0000\u0704\u0702\u0001\u0000\u0000\u0000\u0704\u0705\u0001\u0000\u0000"+
		"\u0000\u0705\u0707\u0001\u0000\u0000\u0000\u0706\u0704\u0001\u0000\u0000"+
		"\u0000\u0707\u0708\u0003\"\u0011\u0000\u0708\u00d9\u0001\u0000\u0000\u0000"+
		"\u0709\u070a\u0003 \u0010\u0000\u070a\u070b\u0003\u00eew\u0000\u070b\u070c"+
		"\u0005\u00a0\u0000\u0000\u070c\u070d\u0003\u00eew\u0000\u070d\u070e\u0003"+
		"\"\u0011\u0000\u070e\u00db\u0001\u0000\u0000\u0000\u070f\u0713\u0003 "+
		"\u0010\u0000\u0710\u0711\u0003\u0012\t\u0000\u0711\u0712\u0005\u00a0\u0000"+
		"\u0000\u0712\u0714\u0001\u0000\u0000\u0000\u0713\u0710\u0001\u0000\u0000"+
		"\u0000\u0713\u0714\u0001\u0000\u0000\u0000\u0714\u0715\u0001\u0000\u0000"+
		"\u0000\u0715\u0718\u0003\u0014\n\u0000\u0716\u0717\u0005\u00a0\u0000\u0000"+
		"\u0717\u0719\u0003\u0014\n\u0000\u0718\u0716\u0001\u0000\u0000\u0000\u0718"+
		"\u0719\u0001\u0000\u0000\u0000\u0719\u071a\u0001\u0000\u0000\u0000\u071a"+
		"\u071b\u0003\"\u0011\u0000\u071b\u00dd\u0001\u0000\u0000\u0000\u071c\u071d"+
		"\u0003 \u0010\u0000\u071d\u0722\u0003\u00eew\u0000\u071e\u071f\u0005\u00a0"+
		"\u0000\u0000\u071f\u0721\u0003\u0012\t\u0000\u0720\u071e\u0001\u0000\u0000"+
		"\u0000\u0721\u0724\u0001\u0000\u0000\u0000\u0722\u0720\u0001\u0000\u0000"+
		"\u0000\u0722\u0723\u0001\u0000\u0000\u0000\u0723\u0725\u0001\u0000\u0000"+
		"\u0000\u0724\u0722\u0001\u0000\u0000\u0000\u0725\u0726\u0003\"\u0011\u0000"+
		"\u0726\u00df\u0001\u0000\u0000\u0000\u0727\u0728\u0003 \u0010\u0000\u0728"+
		"\u072d\u0003\u00eew\u0000\u0729\u072a\u0005\u00a0\u0000\u0000\u072a\u072c"+
		"\u0003\u0012\t\u0000\u072b\u0729\u0001\u0000\u0000\u0000\u072c\u072f\u0001"+
		"\u0000\u0000\u0000\u072d\u072b\u0001\u0000\u0000\u0000\u072d\u072e\u0001"+
		"\u0000\u0000\u0000\u072e\u0730\u0001\u0000\u0000\u0000\u072f\u072d\u0001"+
		"\u0000\u0000\u0000\u0730\u0731\u0003\"\u0011\u0000\u0731\u00e1\u0001\u0000"+
		"\u0000\u0000\u0732\u0733\u0003 \u0010\u0000\u0733\u0738\u0003\u00eew\u0000"+
		"\u0734\u0735\u0005\u00a0\u0000\u0000\u0735\u0737\u0003\u0012\t\u0000\u0736"+
		"\u0734\u0001\u0000\u0000\u0000\u0737\u073a\u0001\u0000\u0000\u0000\u0738"+
		"\u0736\u0001\u0000\u0000\u0000\u0738\u0739\u0001\u0000\u0000\u0000\u0739"+
		"\u073b\u0001\u0000\u0000\u0000\u073a\u0738\u0001\u0000\u0000\u0000\u073b"+
		"\u073c\u0003\"\u0011\u0000\u073c\u00e3\u0001\u0000\u0000\u0000\u073d\u073e"+
		"\u0003 \u0010\u0000\u073e\u0743\u0003\u00eew\u0000\u073f\u0740\u0005\u00a0"+
		"\u0000\u0000\u0740\u0742\u0003\u0014\n\u0000\u0741\u073f\u0001\u0000\u0000"+
		"\u0000\u0742\u0745\u0001\u0000\u0000\u0000\u0743\u0741\u0001\u0000\u0000"+
		"\u0000\u0743\u0744\u0001\u0000\u0000\u0000\u0744\u0746\u0001\u0000\u0000"+
		"\u0000\u0745\u0743\u0001\u0000\u0000\u0000\u0746\u0747\u0003\"\u0011\u0000"+
		"\u0747\u00e5\u0001\u0000\u0000\u0000\u0748\u0749\u0003 \u0010\u0000\u0749"+
		"\u074e\u0003\u00eew\u0000\u074a\u074b\u0005\u00a0\u0000\u0000\u074b\u074d"+
		"\u0003\u0016\u000b\u0000\u074c\u074a\u0001\u0000\u0000\u0000\u074d\u0750"+
		"\u0001\u0000\u0000\u0000\u074e\u074c\u0001\u0000\u0000\u0000\u074e\u074f"+
		"\u0001\u0000\u0000\u0000\u074f\u0751\u0001\u0000\u0000\u0000\u0750\u074e"+
		"\u0001\u0000\u0000\u0000\u0751\u0752\u0003\"\u0011\u0000\u0752\u00e7\u0001"+
		"\u0000\u0000\u0000\u0753\u0754\u0005\u008f\u0000\u0000\u0754\u00e9\u0001"+
		"\u0000\u0000\u0000\u0755\u0756\u0005\u0090\u0000\u0000\u0756\u00eb\u0001"+
		"\u0000\u0000\u0000\u0757\u0762\u0003\u00e8t\u0000\u0758\u0762\u0003\u00ea"+
		"u\u0000\u0759\u075e\u0003\u00eew\u0000\u075a\u075b\u0005\u00a0\u0000\u0000"+
		"\u075b\u075d\u0003\u00eew\u0000\u075c\u075a\u0001\u0000\u0000\u0000\u075d"+
		"\u0760\u0001\u0000\u0000\u0000\u075e\u075c\u0001\u0000\u0000\u0000\u075e"+
		"\u075f\u0001\u0000\u0000\u0000\u075f\u0762\u0001\u0000\u0000\u0000\u0760"+
		"\u075e\u0001\u0000\u0000\u0000\u0761\u0757\u0001\u0000\u0000\u0000\u0761"+
		"\u0758\u0001\u0000\u0000\u0000\u0761\u0759\u0001\u0000\u0000\u0000\u0762"+
		"\u00ed\u0001\u0000\u0000\u0000\u0763\u0773\u0003\u00e8t\u0000\u0764\u0773"+
		"\u0003\u00eau\u0000\u0765\u0773\u0005\u0091\u0000\u0000\u0766\u0773\u0005"+
		"\u0092\u0000\u0000\u0767\u0773\u0005\u0093\u0000\u0000\u0768\u0773\u0005"+
		"\u0094\u0000\u0000\u0769\u0773\u0005\u0095\u0000\u0000\u076a\u0773\u0005"+
		"\u0096\u0000\u0000\u076b\u0773\u0005\u0097\u0000\u0000\u076c\u0773\u0005"+
		"\u0098\u0000\u0000\u076d\u0773\u0005\u0099\u0000\u0000\u076e\u0773\u0005"+
		"\u009a\u0000\u0000\u076f\u0773\u0005\u009b\u0000\u0000\u0770\u0773\u0005"+
		"\u009c\u0000\u0000\u0771\u0773\u0005\u009d\u0000\u0000\u0772\u0763\u0001"+
		"\u0000\u0000\u0000\u0772\u0764\u0001\u0000\u0000\u0000\u0772\u0765\u0001"+
		"\u0000\u0000\u0000\u0772\u0766\u0001\u0000\u0000\u0000\u0772\u0767\u0001"+
		"\u0000\u0000\u0000\u0772\u0768\u0001\u0000\u0000\u0000\u0772\u0769\u0001"+
		"\u0000\u0000\u0000\u0772\u076a\u0001\u0000\u0000\u0000\u0772\u076b\u0001"+
		"\u0000\u0000\u0000\u0772\u076c\u0001\u0000\u0000\u0000\u0772\u076d\u0001"+
		"\u0000\u0000\u0000\u0772\u076e\u0001\u0000\u0000\u0000\u0772\u076f\u0001"+
		"\u0000\u0000\u0000\u0772\u0770\u0001\u0000\u0000\u0000\u0772\u0771\u0001"+
		"\u0000\u0000\u0000\u0773\u00ef\u0001\u0000\u0000\u0000\u0774\u0775\u0003"+
		" \u0010\u0000\u0775\u077a\u0003\u00eew\u0000\u0776\u0777\u0005\u00a0\u0000"+
		"\u0000\u0777\u0779\u0003\u00eew\u0000\u0778\u0776\u0001\u0000\u0000\u0000"+
		"\u0779\u077c\u0001\u0000\u0000\u0000\u077a\u0778\u0001\u0000\u0000\u0000"+
		"\u077a\u077b\u0001\u0000\u0000\u0000\u077b\u077d\u0001\u0000\u0000\u0000"+
		"\u077c\u077a\u0001\u0000\u0000\u0000\u077d\u077e\u0003\"\u0011\u0000\u077e"+
		"\u00f1\u0001\u0000\u0000\u0000\u0095\u010b\u0113\u011c\u0121\u0128\u014f"+
		"\u0162\u0177\u0182\u01a5\u01a7\u01ac\u01b1\u01bc\u01cd\u01e2\u01ed\u01ef"+
		"\u01f4\u01fd\u0202\u020f\u0218\u022f\u0264\u0279\u028a\u02bd\u02e0\u02e4"+
		"\u02eb\u02f3\u02fb\u0303\u0309\u0313\u0321\u032c\u033a\u0341\u0344\u034e"+
		"\u035d\u0370\u037e\u03a8\u03b0\u03b7\u03bb\u03c3\u03c7\u03d7\u03e2\u03ee"+
		"\u03f9\u0405\u0408\u0412\u0423\u0431\u0439\u0442\u044b\u0453\u045a\u0462"+
		"\u046d\u0475\u047c\u0484\u048f\u0497\u04a0\u04a8\u04b5\u04bd\u04c8\u04d5"+
		"\u04e0\u04e8\u04f7\u0504\u050c\u051b\u0528\u0530\u0541\u0550\u0558\u055e"+
		"\u0567\u056f\u0578\u057e\u0584\u058d\u0590\u0598\u05a3\u05ac\u05b5\u05b8"+
		"\u05c0\u05d1\u05d5\u05df\u05ed\u05f1\u05f9\u0605\u0609\u060d\u0611\u061a"+
		"\u0621\u0628\u0634\u0638\u0642\u0650\u0654\u065c\u0668\u066c\u0670\u0674"+
		"\u067d\u068c\u0696\u06a4\u06b2\u06bf\u06ca\u06d4\u06e0\u06ec\u06fa\u0704"+
		"\u0713\u0718\u0722\u072d\u0738\u0743\u074e\u075e\u0761\u0772\u077a";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
		_decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
		for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
			_decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
		}
	}
}