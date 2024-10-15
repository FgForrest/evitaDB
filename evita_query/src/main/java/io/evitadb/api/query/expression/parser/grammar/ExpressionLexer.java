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

// Generated from Expression.g4 by ANTLR 4.9.2

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
		POW=19, VARIABLE=20, CEIL=21, SQRT=22, FLOOR=23, ABS=24, ROUND=25, LOG=26,
		MAX=27, MIN=28, RANDOM=29, WS=30, STRING=31, INT=32, FLOAT=33, BOOLEAN=34;
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
			"POW", "VARIABLE", "CEIL", "SQRT", "FLOOR", "ABS", "ROUND", "LOG", "MAX",
			"MIN", "RANDOM", "SIGN", "WS", "STRING", "STRING_DOUBLE_QUOTATION_ESC",
			"STRING_SINGLE_QUOTATION_ESC", "STRING_UNICODE", "STRING_HEX", "STRING_DOUBLE_QUOTATION_SAFECODEPOINT",
			"STRING_SINGLE_QUOTATION_SAFECODEPOINT", "INT", "FLOAT", "BOOLEAN"
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


	public ExpressionLexer(CharStream input) {
		super(input);
		_interp = new LexerATNSimulator(this,_ATN,_decisionToDFA,_sharedContextCache);
	}

	@Override
	public String getGrammarFileName() { return "Expression.g4"; }

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
		"\3\u608b\ua72a\u8133\ub9ed\u417c\u3be7\u7786\u5964\2$\u010b\b\1\4\2\t"+
		"\2\4\3\t\3\4\4\t\4\4\5\t\5\4\6\t\6\4\7\t\7\4\b\t\b\4\t\t\t\4\n\t\n\4\13"+
		"\t\13\4\f\t\f\4\r\t\r\4\16\t\16\4\17\t\17\4\20\t\20\4\21\t\21\4\22\t\22"+
		"\4\23\t\23\4\24\t\24\4\25\t\25\4\26\t\26\4\27\t\27\4\30\t\30\4\31\t\31"+
		"\4\32\t\32\4\33\t\33\4\34\t\34\4\35\t\35\4\36\t\36\4\37\t\37\4 \t \4!"+
		"\t!\4\"\t\"\4#\t#\4$\t$\4%\t%\4&\t&\4\'\t\'\4(\t(\4)\t)\4*\t*\3\2\3\2"+
		"\3\3\3\3\3\4\3\4\3\5\3\5\3\6\3\6\3\7\3\7\3\b\3\b\3\t\3\t\3\n\3\n\3\n\3"+
		"\13\3\13\3\f\3\f\3\f\3\r\3\r\3\r\3\16\3\16\3\16\3\17\3\17\3\20\3\20\3"+
		"\20\3\21\3\21\3\21\3\22\3\22\3\23\3\23\3\24\3\24\3\25\3\25\3\25\7\25\u0085"+
		"\n\25\f\25\16\25\u0088\13\25\3\26\3\26\3\26\3\26\3\26\3\27\3\27\3\27\3"+
		"\27\3\27\3\30\3\30\3\30\3\30\3\30\3\30\3\31\3\31\3\31\3\31\3\32\3\32\3"+
		"\32\3\32\3\32\3\32\3\33\3\33\3\33\3\33\3\34\3\34\3\34\3\34\3\35\3\35\3"+
		"\35\3\35\3\36\3\36\3\36\3\36\3\36\3\36\3\36\3\37\3\37\3 \6 \u00ba\n \r"+
		" \16 \u00bb\3 \3 \3!\3!\3!\7!\u00c3\n!\f!\16!\u00c6\13!\3!\3!\3!\3!\7"+
		"!\u00cc\n!\f!\16!\u00cf\13!\3!\5!\u00d2\n!\3\"\3\"\3\"\5\"\u00d7\n\"\3"+
		"#\3#\3#\5#\u00dc\n#\3$\3$\3$\3$\3$\3$\3%\3%\3&\3&\3\'\3\'\3(\5(\u00eb"+
		"\n(\3(\6(\u00ee\n(\r(\16(\u00ef\3)\5)\u00f3\n)\3)\7)\u00f6\n)\f)\16)\u00f9"+
		"\13)\3)\3)\6)\u00fd\n)\r)\16)\u00fe\3*\3*\3*\3*\3*\3*\3*\3*\3*\5*\u010a"+
		"\n*\2\2+\3\3\5\4\7\5\t\6\13\7\r\b\17\t\21\n\23\13\25\f\27\r\31\16\33\17"+
		"\35\20\37\21!\22#\23%\24\'\25)\26+\27-\30/\31\61\32\63\33\65\34\67\35"+
		"9\36;\37=\2? A!C\2E\2G\2I\2K\2M\2O\"Q#S$\3\2\f\3\2c|\5\2\62;C\\c|\4\2"+
		"--//\5\2\13\f\17\17\"\"\n\2$$\61\61^^ddhhppttvv\n\2))\61\61^^ddhhpptt"+
		"vv\5\2\62;CHch\5\2\2!$$^^\5\2\2!))^^\3\2\62;\2\u0112\2\3\3\2\2\2\2\5\3"+
		"\2\2\2\2\7\3\2\2\2\2\t\3\2\2\2\2\13\3\2\2\2\2\r\3\2\2\2\2\17\3\2\2\2\2"+
		"\21\3\2\2\2\2\23\3\2\2\2\2\25\3\2\2\2\2\27\3\2\2\2\2\31\3\2\2\2\2\33\3"+
		"\2\2\2\2\35\3\2\2\2\2\37\3\2\2\2\2!\3\2\2\2\2#\3\2\2\2\2%\3\2\2\2\2\'"+
		"\3\2\2\2\2)\3\2\2\2\2+\3\2\2\2\2-\3\2\2\2\2/\3\2\2\2\2\61\3\2\2\2\2\63"+
		"\3\2\2\2\2\65\3\2\2\2\2\67\3\2\2\2\29\3\2\2\2\2;\3\2\2\2\2?\3\2\2\2\2"+
		"A\3\2\2\2\2O\3\2\2\2\2Q\3\2\2\2\2S\3\2\2\2\3U\3\2\2\2\5W\3\2\2\2\7Y\3"+
		"\2\2\2\t[\3\2\2\2\13]\3\2\2\2\r_\3\2\2\2\17a\3\2\2\2\21c\3\2\2\2\23e\3"+
		"\2\2\2\25h\3\2\2\2\27j\3\2\2\2\31m\3\2\2\2\33p\3\2\2\2\35s\3\2\2\2\37"+
		"u\3\2\2\2!x\3\2\2\2#{\3\2\2\2%}\3\2\2\2\'\177\3\2\2\2)\u0081\3\2\2\2+"+
		"\u0089\3\2\2\2-\u008e\3\2\2\2/\u0093\3\2\2\2\61\u0099\3\2\2\2\63\u009d"+
		"\3\2\2\2\65\u00a3\3\2\2\2\67\u00a7\3\2\2\29\u00ab\3\2\2\2;\u00af\3\2\2"+
		"\2=\u00b6\3\2\2\2?\u00b9\3\2\2\2A\u00d1\3\2\2\2C\u00d3\3\2\2\2E\u00d8"+
		"\3\2\2\2G\u00dd\3\2\2\2I\u00e3\3\2\2\2K\u00e5\3\2\2\2M\u00e7\3\2\2\2O"+
		"\u00ea\3\2\2\2Q\u00f2\3\2\2\2S\u0109\3\2\2\2UV\7*\2\2V\4\3\2\2\2WX\7+"+
		"\2\2X\6\3\2\2\2YZ\7-\2\2Z\b\3\2\2\2[\\\7/\2\2\\\n\3\2\2\2]^\7,\2\2^\f"+
		"\3\2\2\2_`\7\61\2\2`\16\3\2\2\2ab\7\'\2\2b\20\3\2\2\2cd\7@\2\2d\22\3\2"+
		"\2\2ef\7@\2\2fg\7?\2\2g\24\3\2\2\2hi\7>\2\2i\26\3\2\2\2jk\7>\2\2kl\7?"+
		"\2\2l\30\3\2\2\2mn\7?\2\2no\7?\2\2o\32\3\2\2\2pq\7#\2\2qr\7?\2\2r\34\3"+
		"\2\2\2st\7#\2\2t\36\3\2\2\2uv\7(\2\2vw\7(\2\2w \3\2\2\2xy\7~\2\2yz\7~"+
		"\2\2z\"\3\2\2\2{|\7.\2\2|$\3\2\2\2}~\7\60\2\2~&\3\2\2\2\177\u0080\7`\2"+
		"\2\u0080(\3\2\2\2\u0081\u0082\7&\2\2\u0082\u0086\t\2\2\2\u0083\u0085\t"+
		"\3\2\2\u0084\u0083\3\2\2\2\u0085\u0088\3\2\2\2\u0086\u0084\3\2\2\2\u0086"+
		"\u0087\3\2\2\2\u0087*\3\2\2\2\u0088\u0086\3\2\2\2\u0089\u008a\7e\2\2\u008a"+
		"\u008b\7g\2\2\u008b\u008c\7k\2\2\u008c\u008d\7n\2\2\u008d,\3\2\2\2\u008e"+
		"\u008f\7u\2\2\u008f\u0090\7s\2\2\u0090\u0091\7t\2\2\u0091\u0092\7v\2\2"+
		"\u0092.\3\2\2\2\u0093\u0094\7h\2\2\u0094\u0095\7n\2\2\u0095\u0096\7q\2"+
		"\2\u0096\u0097\7q\2\2\u0097\u0098\7t\2\2\u0098\60\3\2\2\2\u0099\u009a"+
		"\7c\2\2\u009a\u009b\7d\2\2\u009b\u009c\7u\2\2\u009c\62\3\2\2\2\u009d\u009e"+
		"\7t\2\2\u009e\u009f\7q\2\2\u009f\u00a0\7w\2\2\u00a0\u00a1\7p\2\2\u00a1"+
		"\u00a2\7f\2\2\u00a2\64\3\2\2\2\u00a3\u00a4\7n\2\2\u00a4\u00a5\7q\2\2\u00a5"+
		"\u00a6\7i\2\2\u00a6\66\3\2\2\2\u00a7\u00a8\7o\2\2\u00a8\u00a9\7c\2\2\u00a9"+
		"\u00aa\7z\2\2\u00aa8\3\2\2\2\u00ab\u00ac\7o\2\2\u00ac\u00ad\7k\2\2\u00ad"+
		"\u00ae\7p\2\2\u00ae:\3\2\2\2\u00af\u00b0\7t\2\2\u00b0\u00b1\7c\2\2\u00b1"+
		"\u00b2\7p\2\2\u00b2\u00b3\7f\2\2\u00b3\u00b4\7q\2\2\u00b4\u00b5\7o\2\2"+
		"\u00b5<\3\2\2\2\u00b6\u00b7\t\4\2\2\u00b7>\3\2\2\2\u00b8\u00ba\t\5\2\2"+
		"\u00b9\u00b8\3\2\2\2\u00ba\u00bb\3\2\2\2\u00bb\u00b9\3\2\2\2\u00bb\u00bc"+
		"\3\2\2\2\u00bc\u00bd\3\2\2\2\u00bd\u00be\b \2\2\u00be@\3\2\2\2\u00bf\u00c4"+
		"\7$\2\2\u00c0\u00c3\5C\"\2\u00c1\u00c3\5K&\2\u00c2\u00c0\3\2\2\2\u00c2"+
		"\u00c1\3\2\2\2\u00c3\u00c6\3\2\2\2\u00c4\u00c2\3\2\2\2\u00c4\u00c5\3\2"+
		"\2\2\u00c5\u00c7\3\2\2\2\u00c6\u00c4\3\2\2\2\u00c7\u00d2\7$\2\2\u00c8"+
		"\u00cd\7)\2\2\u00c9\u00cc\5E#\2\u00ca\u00cc\5M\'\2\u00cb\u00c9\3\2\2\2"+
		"\u00cb\u00ca\3\2\2\2\u00cc\u00cf\3\2\2\2\u00cd\u00cb\3\2\2\2\u00cd\u00ce"+
		"\3\2\2\2\u00ce\u00d0\3\2\2\2\u00cf\u00cd\3\2\2\2\u00d0\u00d2\7)\2\2\u00d1"+
		"\u00bf\3\2\2\2\u00d1\u00c8\3\2\2\2\u00d2B\3\2\2\2\u00d3\u00d6\7^\2\2\u00d4"+
		"\u00d7\t\6\2\2\u00d5\u00d7\5G$\2\u00d6\u00d4\3\2\2\2\u00d6\u00d5\3\2\2"+
		"\2\u00d7D\3\2\2\2\u00d8\u00db\7^\2\2\u00d9\u00dc\t\7\2\2\u00da\u00dc\5"+
		"G$\2\u00db\u00d9\3\2\2\2\u00db\u00da\3\2\2\2\u00dcF\3\2\2\2\u00dd\u00de"+
		"\7w\2\2\u00de\u00df\5I%\2\u00df\u00e0\5I%\2\u00e0\u00e1\5I%\2\u00e1\u00e2"+
		"\5I%\2\u00e2H\3\2\2\2\u00e3\u00e4\t\b\2\2\u00e4J\3\2\2\2\u00e5\u00e6\n"+
		"\t\2\2\u00e6L\3\2\2\2\u00e7\u00e8\n\n\2\2\u00e8N\3\2\2\2\u00e9\u00eb\7"+
		"/\2\2\u00ea\u00e9\3\2\2\2\u00ea\u00eb\3\2\2\2\u00eb\u00ed\3\2\2\2\u00ec"+
		"\u00ee\t\13\2\2\u00ed\u00ec\3\2\2\2\u00ee\u00ef\3\2\2\2\u00ef\u00ed\3"+
		"\2\2\2\u00ef\u00f0\3\2\2\2\u00f0P\3\2\2\2\u00f1\u00f3\7/\2\2\u00f2\u00f1"+
		"\3\2\2\2\u00f2\u00f3\3\2\2\2\u00f3\u00f7\3\2\2\2\u00f4\u00f6\t\13\2\2"+
		"\u00f5\u00f4\3\2\2\2\u00f6\u00f9\3\2\2\2\u00f7\u00f5\3\2\2\2\u00f7\u00f8"+
		"\3\2\2\2\u00f8\u00fa\3\2\2\2\u00f9\u00f7\3\2\2\2\u00fa\u00fc\7\60\2\2"+
		"\u00fb\u00fd\t\13\2\2\u00fc\u00fb\3\2\2\2\u00fd\u00fe\3\2\2\2\u00fe\u00fc"+
		"\3\2\2\2\u00fe\u00ff\3\2\2\2\u00ffR\3\2\2\2\u0100\u0101\7h\2\2\u0101\u0102"+
		"\7c\2\2\u0102\u0103\7n\2\2\u0103\u0104\7u\2\2\u0104\u010a\7g\2\2\u0105"+
		"\u0106\7v\2\2\u0106\u0107\7t\2\2\u0107\u0108\7w\2\2\u0108\u010a\7g\2\2"+
		"\u0109\u0100\3\2\2\2\u0109\u0105\3\2\2\2\u010aT\3\2\2\2\22\2\u0086\u00bb"+
		"\u00c2\u00c4\u00cb\u00cd\u00d1\u00d6\u00db\u00ea\u00ef\u00f2\u00f7\u00fe"+
		"\u0109\3\b\2\2";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
		_decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
		for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
			_decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
		}
	}
}