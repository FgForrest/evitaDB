// Generated from Predicate.g4 by ANTLR 4.9.2

package io.evitadb.api.query.predicate.parser.grammar;

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
public class PredicateLexer extends Lexer {
	static { RuntimeMetaData.checkVersion("4.9.2", RuntimeMetaData.VERSION); }

	protected static final DFA[] _decisionToDFA;
	protected static final PredictionContextCache _sharedContextCache =
		new PredictionContextCache();
	public static final int
		LPAREN=1, RPAREN=2, PLUS=3, MINUS=4, TIMES=5, DIV=6, MOD=7, GT=8, LT=9,
		EQ=10, NOT_EQ=11, NOT=12, AND=13, OR=14, COMMA=15, POINT=16, POW=17, VARIABLE=18,
		SCIENTIFIC_NUMBER=19, CEIL=20, FLOOR=21, RANDOM_INT=22, WS=23;
	public static String[] channelNames = {
		"DEFAULT_TOKEN_CHANNEL", "HIDDEN"
	};

	public static String[] modeNames = {
		"DEFAULT_MODE"
	};

	private static String[] makeRuleNames() {
		return new String[] {
			"LPAREN", "RPAREN", "PLUS", "MINUS", "TIMES", "DIV", "MOD", "GT", "LT",
			"EQ", "NOT_EQ", "NOT", "AND", "OR", "COMMA", "POINT", "POW", "VARIABLE",
			"SCIENTIFIC_NUMBER", "CEIL", "FLOOR", "RANDOM_INT", "SIGN", "WS"
		};
	}
	public static final String[] ruleNames = makeRuleNames();

	private static String[] makeLiteralNames() {
		return new String[] {
			null, "'('", "')'", "'+'", "'-'", "'*'", "'/'", "'%'", "'>'", "'<'",
			"'=='", "'!='", "'!'", "'&&'", "'||'", "','", "'.'", "'^'", null, null,
			"'ceil'", "'floor'", "'randomInt'"
		};
	}
	private static final String[] _LITERAL_NAMES = makeLiteralNames();
	private static String[] makeSymbolicNames() {
		return new String[] {
			null, "LPAREN", "RPAREN", "PLUS", "MINUS", "TIMES", "DIV", "MOD", "GT",
			"LT", "EQ", "NOT_EQ", "NOT", "AND", "OR", "COMMA", "POINT", "POW", "VARIABLE",
			"SCIENTIFIC_NUMBER", "CEIL", "FLOOR", "RANDOM_INT", "WS"
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


	public PredicateLexer(CharStream input) {
		super(input);
		_interp = new LexerATNSimulator(this,_ATN,_decisionToDFA,_sharedContextCache);
	}

	@Override
	public String getGrammarFileName() { return "Predicate.g4"; }

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
		"\3\u608b\ua72a\u8133\ub9ed\u417c\u3be7\u7786\u5964\2\31\u008c\b\1\4\2"+
		"\t\2\4\3\t\3\4\4\t\4\4\5\t\5\4\6\t\6\4\7\t\7\4\b\t\b\4\t\t\t\4\n\t\n\4"+
		"\13\t\13\4\f\t\f\4\r\t\r\4\16\t\16\4\17\t\17\4\20\t\20\4\21\t\21\4\22"+
		"\t\22\4\23\t\23\4\24\t\24\4\25\t\25\4\26\t\26\4\27\t\27\4\30\t\30\4\31"+
		"\t\31\3\2\3\2\3\3\3\3\3\4\3\4\3\5\3\5\3\6\3\6\3\7\3\7\3\b\3\b\3\t\3\t"+
		"\3\n\3\n\3\13\3\13\3\13\3\f\3\f\3\f\3\r\3\r\3\16\3\16\3\16\3\17\3\17\3"+
		"\17\3\20\3\20\3\21\3\21\3\22\3\22\3\23\3\23\3\23\7\23]\n\23\f\23\16\23"+
		"`\13\23\3\24\6\24c\n\24\r\24\16\24d\3\24\3\24\6\24i\n\24\r\24\16\24j\5"+
		"\24m\n\24\3\25\3\25\3\25\3\25\3\25\3\26\3\26\3\26\3\26\3\26\3\26\3\27"+
		"\3\27\3\27\3\27\3\27\3\27\3\27\3\27\3\27\3\27\3\30\3\30\3\31\6\31\u0087"+
		"\n\31\r\31\16\31\u0088\3\31\3\31\2\2\32\3\3\5\4\7\5\t\6\13\7\r\b\17\t"+
		"\21\n\23\13\25\f\27\r\31\16\33\17\35\20\37\21!\22#\23%\24\'\25)\26+\27"+
		"-\30/\2\61\31\3\2\6\3\2c|\5\2\62;C\\c|\4\2--//\5\2\13\f\17\17\"\"\2\u008f"+
		"\2\3\3\2\2\2\2\5\3\2\2\2\2\7\3\2\2\2\2\t\3\2\2\2\2\13\3\2\2\2\2\r\3\2"+
		"\2\2\2\17\3\2\2\2\2\21\3\2\2\2\2\23\3\2\2\2\2\25\3\2\2\2\2\27\3\2\2\2"+
		"\2\31\3\2\2\2\2\33\3\2\2\2\2\35\3\2\2\2\2\37\3\2\2\2\2!\3\2\2\2\2#\3\2"+
		"\2\2\2%\3\2\2\2\2\'\3\2\2\2\2)\3\2\2\2\2+\3\2\2\2\2-\3\2\2\2\2\61\3\2"+
		"\2\2\3\63\3\2\2\2\5\65\3\2\2\2\7\67\3\2\2\2\t9\3\2\2\2\13;\3\2\2\2\r="+
		"\3\2\2\2\17?\3\2\2\2\21A\3\2\2\2\23C\3\2\2\2\25E\3\2\2\2\27H\3\2\2\2\31"+
		"K\3\2\2\2\33M\3\2\2\2\35P\3\2\2\2\37S\3\2\2\2!U\3\2\2\2#W\3\2\2\2%Y\3"+
		"\2\2\2\'b\3\2\2\2)n\3\2\2\2+s\3\2\2\2-y\3\2\2\2/\u0083\3\2\2\2\61\u0086"+
		"\3\2\2\2\63\64\7*\2\2\64\4\3\2\2\2\65\66\7+\2\2\66\6\3\2\2\2\678\7-\2"+
		"\28\b\3\2\2\29:\7/\2\2:\n\3\2\2\2;<\7,\2\2<\f\3\2\2\2=>\7\61\2\2>\16\3"+
		"\2\2\2?@\7\'\2\2@\20\3\2\2\2AB\7@\2\2B\22\3\2\2\2CD\7>\2\2D\24\3\2\2\2"+
		"EF\7?\2\2FG\7?\2\2G\26\3\2\2\2HI\7#\2\2IJ\7?\2\2J\30\3\2\2\2KL\7#\2\2"+
		"L\32\3\2\2\2MN\7(\2\2NO\7(\2\2O\34\3\2\2\2PQ\7~\2\2QR\7~\2\2R\36\3\2\2"+
		"\2ST\7.\2\2T \3\2\2\2UV\7\60\2\2V\"\3\2\2\2WX\7`\2\2X$\3\2\2\2YZ\7&\2"+
		"\2Z^\t\2\2\2[]\t\3\2\2\\[\3\2\2\2]`\3\2\2\2^\\\3\2\2\2^_\3\2\2\2_&\3\2"+
		"\2\2`^\3\2\2\2ac\4\62;\2ba\3\2\2\2cd\3\2\2\2db\3\2\2\2de\3\2\2\2el\3\2"+
		"\2\2fh\7\60\2\2gi\4\62;\2hg\3\2\2\2ij\3\2\2\2jh\3\2\2\2jk\3\2\2\2km\3"+
		"\2\2\2lf\3\2\2\2lm\3\2\2\2m(\3\2\2\2no\7e\2\2op\7g\2\2pq\7k\2\2qr\7n\2"+
		"\2r*\3\2\2\2st\7h\2\2tu\7n\2\2uv\7q\2\2vw\7q\2\2wx\7t\2\2x,\3\2\2\2yz"+
		"\7t\2\2z{\7c\2\2{|\7p\2\2|}\7f\2\2}~\7q\2\2~\177\7o\2\2\177\u0080\7K\2"+
		"\2\u0080\u0081\7p\2\2\u0081\u0082\7v\2\2\u0082.\3\2\2\2\u0083\u0084\t"+
		"\4\2\2\u0084\60\3\2\2\2\u0085\u0087\t\5\2\2\u0086\u0085\3\2\2\2\u0087"+
		"\u0088\3\2\2\2\u0088\u0086\3\2\2\2\u0088\u0089\3\2\2\2\u0089\u008a\3\2"+
		"\2\2\u008a\u008b\b\31\2\2\u008b\62\3\2\2\2\b\2^djl\u0088\3\b\2\2";
	public static final ATN _ATN =
		new ATNDeserializer().deserialize(_serializedATN.toCharArray());
	static {
		_decisionToDFA = new DFA[_ATN.getNumberOfDecisions()];
		for (int i = 0; i < _ATN.getNumberOfDecisions(); i++) {
			_decisionToDFA[i] = new DFA(_ATN.getDecisionState(i), i);
		}
	}
}