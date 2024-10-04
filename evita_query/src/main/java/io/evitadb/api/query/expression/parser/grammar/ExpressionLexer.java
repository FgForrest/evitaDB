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

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.RuntimeMetaData;
import org.antlr.v4.runtime.Vocabulary;
import org.antlr.v4.runtime.VocabularyImpl;
import org.antlr.v4.runtime.atn.ATN;
import org.antlr.v4.runtime.atn.ATNDeserializer;
import org.antlr.v4.runtime.atn.LexerATNSimulator;
import org.antlr.v4.runtime.atn.PredictionContextCache;
import org.antlr.v4.runtime.dfa.DFA;

@SuppressWarnings({"all", "warnings", "unchecked", "unused", "cast"})
public class ExpressionLexer extends Lexer {
	static { RuntimeMetaData.checkVersion("4.9.2", RuntimeMetaData.VERSION); }

	protected static final DFA[] _decisionToDFA;
	protected static final PredictionContextCache _sharedContextCache =
		new PredictionContextCache();
	public static final int
		LPAREN=1, RPAREN=2, PLUS=3, MINUS=4, TIMES=5, DIV=6, MOD=7, GT=8, GT_EQ=9,
		LT=10, LT_EQ=11, EQ=12, NOT_EQ=13, NOT=14, AND=15, OR=16, COMMA=17, POINT=18,
		POW=19, VARIABLE=20, CEIL=21, SQRT=22, FLOOR=23, RANDOM_INT=24, WS=25,
		STRING=26, INT=27, FLOAT=28, BOOLEAN=29;
	public static String[] channelNames = {
		"DEFAULT_TOKEN_CHANNEL", "HIDDEN"
	};

	public static String[] modeNames = {
		"DEFAULT_MODE"
	};

	private static String[] makeRuleNames() {
		return new String[] {
			"LPAREN", "RPAREN", "PLUS", "MINUS", "TIMES", "DIV", "MOD", "GT", "GT_EQ",
			"LT", "LT_EQ", "EQ", "NOT_EQ", "NOT", "AND", "OR", "COMMA", "POINT",
			"POW", "VARIABLE", "CEIL", "SQRT", "FLOOR", "RANDOM_INT", "SIGN", "WS",
			"STRING", "STRING_DOUBLE_QUOTATION_ESC", "STRING_SINGLE_QUOTATION_ESC",
			"STRING_UNICODE", "STRING_HEX", "STRING_DOUBLE_QUOTATION_SAFECODEPOINT",
			"STRING_SINGLE_QUOTATION_SAFECODEPOINT", "INT", "FLOAT", "BOOLEAN"
		};
	}
	public static final String[] ruleNames = makeRuleNames();

	private static String[] makeLiteralNames() {
		return new String[] {
			null, "'('", "')'", "'+'", "'-'", "'*'", "'/'", "'%'", "'>'", "'>='",
			"'<'", "'<='", "'=='", "'!='", "'!'", "'&&'", "'||'", "','", "'.'", "'^'",
			null, "'ceil'", "'sqrt'", "'floor'", "'random'"
		};
	}
	private static final String[] _LITERAL_NAMES = makeLiteralNames();
	private static String[] makeSymbolicNames() {
		return new String[] {
			null, "LPAREN", "RPAREN", "PLUS", "MINUS", "TIMES", "DIV", "MOD", "GT",
			"GT_EQ", "LT", "LT_EQ", "EQ", "NOT_EQ", "NOT", "AND", "OR", "COMMA",
			"POINT", "POW", "VARIABLE", "CEIL", "SQRT", "FLOOR", "RANDOM_INT", "WS",
			"STRING", "INT", "FLOAT", "BOOLEAN"
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


	public ExpressionLexer(CharStream input) {
		super(input);
		_interp = new LexerATNSimulator(this,_ATN,_decisionToDFA,_sharedContextCache);
	}

	@Override
	public String getGrammarFileName() { return "ExpressionFactory.g4"; }

	@Override
	public String[] getRuleNames() { return ruleNames; }

	@Override
	public String getSerializedATN() { return _serializedATN; }

	@Override
	public String[] getChannelNames() { return channelNames; }

	@Override
	public String[] getModeNames() { return modeNames; }

	@Override
	public ATN getATN() { return _ATN; }

	public static final String _serializedATN =
		"\3\u608b\ua72a\u8133\ub9ed\u417c\u3be7\u7786\u5964\2\37\u00eb\b\1\4\2"+
		"\t\2\4\3\t\3\4\4\t\4\4\5\t\5\4\6\t\6\4\7\t\7\4\b\t\b\4\t\t\t\4\n\t\n\4"+
		"\13\t\13\4\f\t\f\4\r\t\r\4\16\t\16\4\17\t\17\4\20\t\20\4\21\t\21\4\22"+
		"\t\22\4\23\t\23\4\24\t\24\4\25\t\25\4\26\t\26\4\27\t\27\4\30\t\30\4\31"+
		"\t\31\4\32\t\32\4\33\t\33\4\34\t\34\4\35\t\35\4\36\t\36\4\37\t\37\4 \t"+
		" \4!\t!\4\"\t\"\4#\t#\4$\t$\4%\t%\3\2\3\2\3\3\3\3\3\4\3\4\3\5\3\5\3\6"+
		"\3\6\3\7\3\7\3\b\3\b\3\t\3\t\3\n\3\n\3\n\3\13\3\13\3\f\3\f\3\f\3\r\3\r"+
		"\3\r\3\16\3\16\3\16\3\17\3\17\3\20\3\20\3\20\3\21\3\21\3\21\3\22\3\22"+
		"\3\23\3\23\3\24\3\24\3\25\3\25\3\25\7\25{\n\25\f\25\16\25~\13\25\3\26"+
		"\3\26\3\26\3\26\3\26\3\27\3\27\3\27\3\27\3\27\3\30\3\30\3\30\3\30\3\30"+
		"\3\30\3\31\3\31\3\31\3\31\3\31\3\31\3\31\3\32\3\32\3\33\6\33\u009a\n\33"+
		"\r\33\16\33\u009b\3\33\3\33\3\34\3\34\3\34\7\34\u00a3\n\34\f\34\16\34"+
		"\u00a6\13\34\3\34\3\34\3\34\3\34\7\34\u00ac\n\34\f\34\16\34\u00af\13\34"+
		"\3\34\5\34\u00b2\n\34\3\35\3\35\3\35\5\35\u00b7\n\35\3\36\3\36\3\36\5"+
		"\36\u00bc\n\36\3\37\3\37\3\37\3\37\3\37\3\37\3 \3 \3!\3!\3\"\3\"\3#\5"+
		"#\u00cb\n#\3#\6#\u00ce\n#\r#\16#\u00cf\3$\5$\u00d3\n$\3$\7$\u00d6\n$\f"+
		"$\16$\u00d9\13$\3$\3$\6$\u00dd\n$\r$\16$\u00de\3%\3%\3%\3%\3%\3%\3%\3"+
		"%\3%\5%\u00ea\n%\2\2&\3\3\5\4\7\5\t\6\13\7\r\b\17\t\21\n\23\13\25\f\27"+
		"\r\31\16\33\17\35\20\37\21!\22#\23%\24\'\25)\26+\27-\30/\31\61\32\63\2"+
		"\65\33\67\349\2;\2=\2?\2A\2C\2E\35G\36I\37\3\2\f\3\2c|\5\2\62;C\\c|\4"+
		"\2--//\5\2\13\f\17\17\"\"\n\2$$\61\61^^ddhhppttvv\n\2))\61\61^^ddhhpp"+
		"ttvv\5\2\62;CHch\5\2\2!$$^^\5\2\2!))^^\3\2\62;\2\u00f2\2\3\3\2\2\2\2\5"+
		"\3\2\2\2\2\7\3\2\2\2\2\t\3\2\2\2\2\13\3\2\2\2\2\r\3\2\2\2\2\17\3\2\2\2"+
		"\2\21\3\2\2\2\2\23\3\2\2\2\2\25\3\2\2\2\2\27\3\2\2\2\2\31\3\2\2\2\2\33"+
		"\3\2\2\2\2\35\3\2\2\2\2\37\3\2\2\2\2!\3\2\2\2\2#\3\2\2\2\2%\3\2\2\2\2"+
		"\'\3\2\2\2\2)\3\2\2\2\2+\3\2\2\2\2-\3\2\2\2\2/\3\2\2\2\2\61\3\2\2\2\2"+
		"\65\3\2\2\2\2\67\3\2\2\2\2E\3\2\2\2\2G\3\2\2\2\2I\3\2\2\2\3K\3\2\2\2\5"+
		"M\3\2\2\2\7O\3\2\2\2\tQ\3\2\2\2\13S\3\2\2\2\rU\3\2\2\2\17W\3\2\2\2\21"+
		"Y\3\2\2\2\23[\3\2\2\2\25^\3\2\2\2\27`\3\2\2\2\31c\3\2\2\2\33f\3\2\2\2"+
		"\35i\3\2\2\2\37k\3\2\2\2!n\3\2\2\2#q\3\2\2\2%s\3\2\2\2\'u\3\2\2\2)w\3"+
		"\2\2\2+\177\3\2\2\2-\u0084\3\2\2\2/\u0089\3\2\2\2\61\u008f\3\2\2\2\63"+
		"\u0096\3\2\2\2\65\u0099\3\2\2\2\67\u00b1\3\2\2\29\u00b3\3\2\2\2;\u00b8"+
		"\3\2\2\2=\u00bd\3\2\2\2?\u00c3\3\2\2\2A\u00c5\3\2\2\2C\u00c7\3\2\2\2E"+
		"\u00ca\3\2\2\2G\u00d2\3\2\2\2I\u00e9\3\2\2\2KL\7*\2\2L\4\3\2\2\2MN\7+"+
		"\2\2N\6\3\2\2\2OP\7-\2\2P\b\3\2\2\2QR\7/\2\2R\n\3\2\2\2ST\7,\2\2T\f\3"+
		"\2\2\2UV\7\61\2\2V\16\3\2\2\2WX\7\'\2\2X\20\3\2\2\2YZ\7@\2\2Z\22\3\2\2"+
		"\2[\\\7@\2\2\\]\7?\2\2]\24\3\2\2\2^_\7>\2\2_\26\3\2\2\2`a\7>\2\2ab\7?"+
		"\2\2b\30\3\2\2\2cd\7?\2\2de\7?\2\2e\32\3\2\2\2fg\7#\2\2gh\7?\2\2h\34\3"+
		"\2\2\2ij\7#\2\2j\36\3\2\2\2kl\7(\2\2lm\7(\2\2m \3\2\2\2no\7~\2\2op\7~"+
		"\2\2p\"\3\2\2\2qr\7.\2\2r$\3\2\2\2st\7\60\2\2t&\3\2\2\2uv\7`\2\2v(\3\2"+
		"\2\2wx\7&\2\2x|\t\2\2\2y{\t\3\2\2zy\3\2\2\2{~\3\2\2\2|z\3\2\2\2|}\3\2"+
		"\2\2}*\3\2\2\2~|\3\2\2\2\177\u0080\7e\2\2\u0080\u0081\7g\2\2\u0081\u0082"+
		"\7k\2\2\u0082\u0083\7n\2\2\u0083,\3\2\2\2\u0084\u0085\7u\2\2\u0085\u0086"+
		"\7s\2\2\u0086\u0087\7t\2\2\u0087\u0088\7v\2\2\u0088.\3\2\2\2\u0089\u008a"+
		"\7h\2\2\u008a\u008b\7n\2\2\u008b\u008c\7q\2\2\u008c\u008d\7q\2\2\u008d"+
		"\u008e\7t\2\2\u008e\60\3\2\2\2\u008f\u0090\7t\2\2\u0090\u0091\7c\2\2\u0091"+
		"\u0092\7p\2\2\u0092\u0093\7f\2\2\u0093\u0094\7q\2\2\u0094\u0095\7o\2\2"+
		"\u0095\62\3\2\2\2\u0096\u0097\t\4\2\2\u0097\64\3\2\2\2\u0098\u009a\t\5"+
		"\2\2\u0099\u0098\3\2\2\2\u009a\u009b\3\2\2\2\u009b\u0099\3\2\2\2\u009b"+
		"\u009c\3\2\2\2\u009c\u009d\3\2\2\2\u009d\u009e\b\33\2\2\u009e\66\3\2\2"+
		"\2\u009f\u00a4\7$\2\2\u00a0\u00a3\59\35\2\u00a1\u00a3\5A!\2\u00a2\u00a0"+
		"\3\2\2\2\u00a2\u00a1\3\2\2\2\u00a3\u00a6\3\2\2\2\u00a4\u00a2\3\2\2\2\u00a4"+
		"\u00a5\3\2\2\2\u00a5\u00a7\3\2\2\2\u00a6\u00a4\3\2\2\2\u00a7\u00b2\7$"+
		"\2\2\u00a8\u00ad\7)\2\2\u00a9\u00ac\5;\36\2\u00aa\u00ac\5C\"\2\u00ab\u00a9"+
		"\3\2\2\2\u00ab\u00aa\3\2\2\2\u00ac\u00af\3\2\2\2\u00ad\u00ab\3\2\2\2\u00ad"+
		"\u00ae\3\2\2\2\u00ae\u00b0\3\2\2\2\u00af\u00ad\3\2\2\2\u00b0\u00b2\7)"+
		"\2\2\u00b1\u009f\3\2\2\2\u00b1\u00a8\3\2\2\2\u00b28\3\2\2\2\u00b3\u00b6"+
		"\7^\2\2\u00b4\u00b7\t\6\2\2\u00b5\u00b7\5=\37\2\u00b6\u00b4\3\2\2\2\u00b6"+
		"\u00b5\3\2\2\2\u00b7:\3\2\2\2\u00b8\u00bb\7^\2\2\u00b9\u00bc\t\7\2\2\u00ba"+
		"\u00bc\5=\37\2\u00bb\u00b9\3\2\2\2\u00bb\u00ba\3\2\2\2\u00bc<\3\2\2\2"+
		"\u00bd\u00be\7w\2\2\u00be\u00bf\5? \2\u00bf\u00c0\5? \2\u00c0\u00c1\5"+
		"? \2\u00c1\u00c2\5? \2\u00c2>\3\2\2\2\u00c3\u00c4\t\b\2\2\u00c4@\3\2\2"+
		"\2\u00c5\u00c6\n\t\2\2\u00c6B\3\2\2\2\u00c7\u00c8\n\n\2\2\u00c8D\3\2\2"+
		"\2\u00c9\u00cb\7/\2\2\u00ca\u00c9\3\2\2\2\u00ca\u00cb\3\2\2\2\u00cb\u00cd"+
		"\3\2\2\2\u00cc\u00ce\t\13\2\2\u00cd\u00cc\3\2\2\2\u00ce\u00cf\3\2\2\2"+
		"\u00cf\u00cd\3\2\2\2\u00cf\u00d0\3\2\2\2\u00d0F\3\2\2\2\u00d1\u00d3\7"+
		"/\2\2\u00d2\u00d1\3\2\2\2\u00d2\u00d3\3\2\2\2\u00d3\u00d7\3\2\2\2\u00d4"+
		"\u00d6\t\13\2\2\u00d5\u00d4\3\2\2\2\u00d6\u00d9\3\2\2\2\u00d7\u00d5\3"+
		"\2\2\2\u00d7\u00d8\3\2\2\2\u00d8\u00da\3\2\2\2\u00d9\u00d7\3\2\2\2\u00da"+
		"\u00dc\7\60\2\2\u00db\u00dd\t\13\2\2\u00dc\u00db\3\2\2\2\u00dd\u00de\3"+
		"\2\2\2\u00de\u00dc\3\2\2\2\u00de\u00df\3\2\2\2\u00dfH\3\2\2\2\u00e0\u00e1"+
		"\7h\2\2\u00e1\u00e2\7c\2\2\u00e2\u00e3\7n\2\2\u00e3\u00e4\7u\2\2\u00e4"+
		"\u00ea\7g\2\2\u00e5\u00e6\7v\2\2\u00e6\u00e7\7t\2\2\u00e7\u00e8\7w\2\2"+
		"\u00e8\u00ea\7g\2\2\u00e9\u00e0\3\2\2\2\u00e9\u00e5\3\2\2\2\u00eaJ\3\2"+
		"\2\2\22\2|\u009b\u00a2\u00a4\u00ab\u00ad\u00b1\u00b6\u00bb\u00ca\u00cf"+
		"\u00d2\u00d7\u00de\u00e9\3\b\2\2";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
		_decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
		for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
			_decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
		}
	}
}