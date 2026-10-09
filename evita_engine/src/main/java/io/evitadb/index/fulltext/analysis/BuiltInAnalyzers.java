/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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


package io.evitadb.index.fulltext.analysis;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.CollectionUtils;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.WordlistLoader;
import org.apache.lucene.analysis.cz.CzechAnalyzer;
import org.apache.lucene.analysis.de.GermanAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter;
import org.apache.lucene.analysis.ro.RomanianAnalyzer;
import org.apache.lucene.analysis.snowball.SnowballFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.tartarus.snowball.ext.RomanianStemmer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static java.util.Map.entry;

/**
 * The built-in language table — part of the engine rather than of any configuration.
 *
 * Keyed by the **language** of a locale, not by the whole locale: `cs_CZ` and `cs` get the same analyzers,
 * because analysis is a property of a language. A language the table does not know gets the explicitly named
 * {@link #GENERIC_ANALYZER_NAME} analyzer (tokenize plus lowercase, no stemming, no folding) rather than an
 * exception — full-text over an unknown language is still better than a failing query — and the substitution is
 * reported to the log once per language, never silently skipped.
 *
 * **Four languages use a different chain on each side of the pipeline.** Czech, Slovak, Polish and Romanian
 * all write diacritics that users routinely omit when typing a query, and they all have stemmers whose rules
 * read those diacritics. The two facts together rule out a single symmetric chain:
 *
 * - **Index side** stems the **accented** text and folds diacritics away *afterwards*, so no stemming rule ever
 *   reads mutilated input. `černá` is indexed as `cern`.
 * - **Search side** folds first — it has to, the query text may have arrived bare — and then runs a
 *   {@link VariantStemmer}, which emits **every** stem the word could have had before folding, all at one
 *   position. `cerna` produces a set that provably contains `cern`, so the bare query meets the accented index.
 *
 * Folding both sides with one chain instead (stem the folded text) is the arrangement most engines ship and it
 * loses badly here: the stemmer's tables are written in native orthography and simply do not match folded text.
 * See `documentation/adr/2026-08-24-fulltext-search-lucene-vs-inhouse/` for the measurements.
 *
 * What each language gets:
 *
 * | Language | Index chain                                                  | Search chain                          |
 * |----------|--------------------------------------------------------------|---------------------------------------|
 * | `cs`     | `CzechAnalyzer` + folding                                    | stop, fold, Czech stem variants       |
 * | `sk`     | {@link SlovakStemmer} + folding                              | fold, Slovak stem variants            |
 * | `pl`     | stop, {@link PolishSnowballStemmer}, folding                 | stop, fold, Polish stem variants      |
 * | `ro`     | {@link CommaBelowNormalizationFilter}, stop, Snowball, folding| comma-below, stop, fold, Romanian stem variants |
 * | `en`     | `EnglishAnalyzer`                                            | `EnglishAnalyzer`                     |
 * | `de`     | `GermanAnalyzer`                                             | `GermanAnalyzer`                      |
 *
 * **Every index chain strips markup, no search chain does.** Each index-side analyzer — `generic` included — is
 * wrapped in {@link HtmlStrippingAnalyzerWrapper}, so tags, attributes and entities of a stored HTML value never
 * become terms; a query text is what a user typed and is analyzed without it. That is why no language is uniform
 * any more: English, German and the generic fallback get `english-search`, `german-search` and `generic-search` —
 * the same chains without the character filter — while the index-side names are unchanged.
 *
 * German is the one that must **not** get the folding wrapper, because `GermanNormalizationFilter` already folds
 * umlauts and maps `ß` to `ss`; a second pass would fight it. See {@link DiacriticsFoldingAnalyzerWrapper} for
 * the full argument and for why folding is appended after the stemmer rather than before it. Slovak has no
 * Lucene stop set at all, which is why its search chain has no stop filter.
 *
 * **Built-ins carry a mode, and every `*-search` chain is {@link AnalysisMode#SEARCH_TIME}.** A variant
 * fan-out baked into an index would write every alternative stem as a real term — precisely the symmetric
 * arrangement the asymmetry above exists to avoid — so the mode machinery is used as the type-level guard
 * against it: a schema assigning `czech-search` to the indexing slot is rejected at mutation time. The three
 * `*-search` chains without a variant fan-out follow the same rule so that the name alone says which slot a chain
 * belongs to — indexed through one, a markup-bearing value would index its markup. Every index-side built-in is
 * {@link AnalysisMode#ALL}, because the query side falls back to the index's own analyzer when the assignment moved
 * on since the index was built.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class BuiltInAnalyzers {

	/**
	 * Name of the analyzer used for a language the table does not know. Named rather than derived, so it can be
	 * documented and referred to from a schema like any other analyzer.
	 */
	public static final String GENERIC_ANALYZER_NAME = "generic";
	/**
	 * Name of the Czech index-side analyzer.
	 */
	public static final String CZECH_ANALYZER_NAME = "czech";
	/**
	 * Name of the Czech search-side analyzer — search-time only, see the class javadoc.
	 */
	public static final String CZECH_SEARCH_ANALYZER_NAME = "czech-search";
	/**
	 * Name of the generic search-side analyzer — the {@link #GENERIC_ANALYZER_NAME} chain without the markup
	 * stripping, search-time only, see the class javadoc.
	 */
	public static final String GENERIC_SEARCH_ANALYZER_NAME = "generic-search";
	/**
	 * Name of the English index-side analyzer.
	 */
	public static final String ENGLISH_ANALYZER_NAME = "english";
	/**
	 * Name of the English search-side analyzer — the index chain without the markup stripping, search-time only, see
	 * the class javadoc.
	 */
	public static final String ENGLISH_SEARCH_ANALYZER_NAME = "english-search";
	/**
	 * Name of the German index-side analyzer.
	 */
	public static final String GERMAN_ANALYZER_NAME = "german";
	/**
	 * Name of the German search-side analyzer — the index chain without the markup stripping, search-time only, see
	 * the class javadoc.
	 */
	public static final String GERMAN_SEARCH_ANALYZER_NAME = "german-search";
	/**
	 * Name of the Polish index-side analyzer.
	 */
	public static final String POLISH_ANALYZER_NAME = "polish";
	/**
	 * Name of the Polish search-side analyzer — search-time only, see the class javadoc.
	 */
	public static final String POLISH_SEARCH_ANALYZER_NAME = "polish-search";
	/**
	 * Name of the Slovak index-side analyzer.
	 */
	public static final String SLOVAK_ANALYZER_NAME = "slovak";
	/**
	 * Name of the Slovak search-side analyzer — search-time only, see the class javadoc.
	 */
	public static final String SLOVAK_SEARCH_ANALYZER_NAME = "slovak-search";
	/**
	 * Name of the Romanian index-side analyzer.
	 */
	public static final String ROMANIAN_ANALYZER_NAME = "romanian";
	/**
	 * Name of the Romanian search-side analyzer — search-time only, see the class javadoc.
	 */
	public static final String ROMANIAN_SEARCH_ANALYZER_NAME = "romanian-search";

	/**
	 * The built-in table, keyed by analyzer name. Values are declarations, not analyzers: instances are expensive
	 * to build (Czech and Polish each load a stop-word list from a jar resource, and every Snowball chain
	 * constructs its stemmer's ending tables) and are therefore created lazily, per name, by
	 * {@link FulltextAnalyzerRegistry}. Every index-side entry is built through {@link #indexChain(Supplier,
	 * Supplier)}, which is what puts the markup stripping in front of it.
	 */
	private static final Map<String, BuiltInAnalyzer> ANALYZERS_BY_NAME = Map.ofEntries(
		entry(GENERIC_ANALYZER_NAME, indexChain(TokenizingAnalyzer::new, null)),
		entry(GENERIC_SEARCH_ANALYZER_NAME, searchTime(TokenizingAnalyzer::new, null)),
		entry(
			CZECH_ANALYZER_NAME,
			indexChain(
				() -> new DiacriticsFoldingAnalyzerWrapper(new CzechAnalyzer()), CzechAnalyzer::getDefaultStopSet
			)
		),
		entry(
			CZECH_SEARCH_ANALYZER_NAME,
			searchTime(BuiltInAnalyzers::czechSearchChain, CzechAnalyzer::getDefaultStopSet)
		),
		entry(ENGLISH_ANALYZER_NAME, indexChain(EnglishAnalyzer::new, EnglishAnalyzer::getDefaultStopSet)),
		entry(ENGLISH_SEARCH_ANALYZER_NAME, searchTime(EnglishAnalyzer::new, EnglishAnalyzer::getDefaultStopSet)),
		entry(GERMAN_ANALYZER_NAME, indexChain(GermanAnalyzer::new, GermanAnalyzer::getDefaultStopSet)),
		entry(GERMAN_SEARCH_ANALYZER_NAME, searchTime(GermanAnalyzer::new, GermanAnalyzer::getDefaultStopSet)),
		entry(POLISH_ANALYZER_NAME, indexChain(BuiltInAnalyzers::polishIndexChain, () -> PolishStopWords.SET)),
		entry(
			POLISH_SEARCH_ANALYZER_NAME, searchTime(BuiltInAnalyzers::polishSearchChain, () -> PolishStopWords.SET)
		),
		entry(SLOVAK_ANALYZER_NAME, indexChain(BuiltInAnalyzers::slovakIndexChain, null)),
		entry(SLOVAK_SEARCH_ANALYZER_NAME, searchTime(BuiltInAnalyzers::slovakSearchChain, null)),
		entry(
			ROMANIAN_ANALYZER_NAME,
			indexChain(BuiltInAnalyzers::romanianIndexChain, RomanianAnalyzer::getDefaultStopSet)
		),
		entry(
			ROMANIAN_SEARCH_ANALYZER_NAME,
			searchTime(BuiltInAnalyzers::romanianSearchChain, RomanianAnalyzer::getDefaultStopSet)
		)
	);

	/**
	 * The language table proper, keyed by ISO language code. Deliberately separate from
	 * {@link #ANALYZERS_BY_NAME}: which analyzers a language defaults to and what those analyzers *are* are two
	 * different facts, and a schema may name any analyzer for any language.
	 */
	private static final Map<String, AnalyzerAssignment> ASSIGNMENTS_BY_LANGUAGE = Map.of(
		"cs", new AnalyzerAssignment(CZECH_ANALYZER_NAME, CZECH_SEARCH_ANALYZER_NAME, null),
		"en", new AnalyzerAssignment(ENGLISH_ANALYZER_NAME, ENGLISH_SEARCH_ANALYZER_NAME, null),
		"de", new AnalyzerAssignment(GERMAN_ANALYZER_NAME, GERMAN_SEARCH_ANALYZER_NAME, null),
		"pl", new AnalyzerAssignment(POLISH_ANALYZER_NAME, POLISH_SEARCH_ANALYZER_NAME, null),
		"sk", new AnalyzerAssignment(SLOVAK_ANALYZER_NAME, SLOVAK_SEARCH_ANALYZER_NAME, null),
		"ro", new AnalyzerAssignment(ROMANIAN_ANALYZER_NAME, ROMANIAN_SEARCH_ANALYZER_NAME, null)
	);

	/**
	 * The assignment a language the table does not know falls back to.
	 */
	private static final AnalyzerAssignment GENERIC_ASSIGNMENT =
		new AnalyzerAssignment(GENERIC_ANALYZER_NAME, GENERIC_SEARCH_ANALYZER_NAME, null);

	/**
	 * The folded stop words of the built-in analyzers asked for so far, keyed by analyzer name - see
	 * {@link #foldedStopWordsFor(String)}.
	 */
	private static final Map<String, Set<String>> FOLDED_STOP_WORDS = CollectionUtils.createConcurrentHashMap(16);

	/**
	 * Languages already reported as unknown, so that the fallback is logged once per language instead of once
	 * per analysed value.
	 */
	private static final Set<String> REPORTED_UNKNOWN_LANGUAGES = ConcurrentHashMap.newKeySet(8);

	/**
	 * Returns the built-in analyzer assignment for the language of `locale`, falling back to the
	 * {@link #GENERIC_ANALYZER_NAME} pair for a language the table does not cover. The fallback is logged the first
	 * time it is used for a given language.
	 *
	 * Every assignment is asymmetric: the indexing slot resolves to the language's index chain, the query and phrase
	 * slots to its `*-search` chain. For `cs`, `sk`, `pl` and `ro` the two differ in folding and stem variants, for
	 * every language in the markup stripping only. See the class javadoc for why.
	 *
	 * @param locale locale whose language decides the analyzers; only its language part is consulted
	 * @return built-in analyzer assignment for the locale's language, never null
	 */
	@Nonnull
	public static AnalyzerAssignment assignmentForLocale(@Nonnull Locale locale) {
		final String language = locale.getLanguage();
		final AnalyzerAssignment assignment = ASSIGNMENTS_BY_LANGUAGE.get(language);
		if (assignment != null) {
			return assignment;
		}
		if (REPORTED_UNKNOWN_LANGUAGES.add(language)) {
			log.warn(
				"There is no built-in full-text analyzer for language `{}`, falling back to the `{}` analyzer " +
					"(word-break tokenization and lowercasing only - no stop words, no stemming). Register a " +
					"custom analyzer for this language to get language-aware behaviour.",
				language, GENERIC_ANALYZER_NAME
			);
		}
		return GENERIC_ASSIGNMENT;
	}

	/**
	 * Returns the declaration of the built-in analyzer registered under `name`, or null when no built-in
	 * analyzer carries that name. Used by {@link FulltextAnalyzerRegistry} to resolve a schema-supplied name and
	 * to reject runtime registrations that would shadow a built-in one.
	 *
	 * @param name analyzer name
	 * @return declaration of the built-in analyzer, or null when the name is not a built-in one
	 */
	@Nullable
	static BuiltInAnalyzer definitionFor(@Nonnull String name) {
		return ANALYZERS_BY_NAME.get(name);
	}

	/**
	 * Builds the Czech query chain: tokenize, lowercase, drop stop words, fold diacritics, then emit every stem
	 * variant of each token, all at one position.
	 *
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer czechSearchChain() {
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				TokenStream stream = new StopFilter(
					new LowerCaseFilter(source), CzechAnalyzer.getDefaultStopSet()
				);
				stream = new ASCIIFoldingFilter(stream);
				stream = new VariantStemFilter(stream, new CzechVariantStemmer());
				return new TokenStreamComponents(source, stream);
			}

			@Override
			protected TokenStream normalize(String fieldName, TokenStream in) {
				return new ASCIIFoldingFilter(new LowerCaseFilter(in));
			}
		};
	}

	/**
	 * Builds the Slovak index chain: tokenize, lowercase, stem the accented text with {@link SlovakStemmer},
	 * then fold diacritics.
	 *
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer slovakIndexChain() {
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				TokenStream stream = new SlovakStemmer.SlovakStemFilter(
					new LowerCaseFilter(source), new SlovakStemmer()
				);
				stream = new ASCIIFoldingFilter(stream);
				return new TokenStreamComponents(source, stream);
			}

			@Override
			protected TokenStream normalize(String fieldName, TokenStream in) {
				return new ASCIIFoldingFilter(new LowerCaseFilter(in));
			}
		};
	}

	/**
	 * Builds the Slovak query chain: tokenize, lowercase, fold diacritics, then emit every stem variant of each
	 * token, all at one position. No stop filter — Lucene ships no Slovak stop set.
	 *
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer slovakSearchChain() {
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				final TokenStream folded = new ASCIIFoldingFilter(new LowerCaseFilter(source));
				return new TokenStreamComponents(
					source, new VariantStemFilter(folded, new SlovakVariantStemmer())
				);
			}

			@Override
			protected TokenStream normalize(String fieldName, TokenStream in) {
				return new ASCIIFoldingFilter(new LowerCaseFilter(in));
			}
		};
	}

	/**
	 * Builds the Polish index chain: tokenize, lowercase, drop stop words, stem the accented text with the
	 * vendored Snowball stemmer, then fold diacritics.
	 *
	 * Note that the stop set is evitaDB's own copy of Lucene's Polish list — see {@link PolishStopWords} — and
	 * that Lucene's `PolishAnalyzer` is not used at all: its Stempel stemmer is a statistical trie with no rule
	 * table, over which the query side's variant fan-out cannot be constructed.
	 *
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer polishIndexChain() {
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				TokenStream stream = new StopFilter(
					new LowerCaseFilter(source), PolishStopWords.SET
				);
				stream = new SnowballFilter(stream, new PolishSnowballStemmer());
				stream = new ASCIIFoldingFilter(stream);
				return new TokenStreamComponents(source, stream);
			}

			@Override
			protected TokenStream normalize(String fieldName, TokenStream in) {
				return new ASCIIFoldingFilter(new LowerCaseFilter(in));
			}
		};
	}

	/**
	 * Builds the Polish query chain: tokenize, lowercase, drop stop words, fold diacritics, then emit every stem
	 * variant of each token, all at one position.
	 *
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer polishSearchChain() {
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				TokenStream stream = new StopFilter(
					new LowerCaseFilter(source), PolishStopWords.SET
				);
				stream = new ASCIIFoldingFilter(stream);
				stream = new VariantStemFilter(stream, new PolishVariantStemmer());
				return new TokenStreamComponents(source, stream);
			}

			@Override
			protected TokenStream normalize(String fieldName, TokenStream in) {
				return new ASCIIFoldingFilter(new LowerCaseFilter(in));
			}
		};
	}

	/**
	 * Builds the Romanian index chain: tokenize, lowercase, normalize comma-below spellings to the cedilla ones
	 * the pinned Lucene's stop list and Snowball tables are written in, drop stop words, stem, then fold.
	 *
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer romanianIndexChain() {
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				TokenStream stream = new CommaBelowNormalizationFilter(new LowerCaseFilter(source));
				stream = new StopFilter(stream, RomanianAnalyzer.getDefaultStopSet());
				stream = new SnowballFilter(stream, new RomanianStemmer());
				stream = new ASCIIFoldingFilter(stream);
				return new TokenStreamComponents(source, stream);
			}

			@Override
			protected TokenStream normalize(String fieldName, TokenStream in) {
				return new ASCIIFoldingFilter(new CommaBelowNormalizationFilter(new LowerCaseFilter(in)));
			}
		};
	}

	/**
	 * Builds the Romanian query chain: tokenize, lowercase, normalize comma-below spellings, drop stop words,
	 * fold diacritics, then emit every stem variant of each token, all at one position.
	 *
	 * The comma-below normalization is here **because of the stop filter**, and for no other reason. The stop
	 * filter is the single component of this chain that reads the raw spelling, and the pinned Lucene's
	 * Romanian list is written in cedilla throughout — 24 of its 230 entries carry `ş`/`ţ` and none carries
	 * `ș`/`ț`. A user typing modern Romanian writes `și`, which without this filter survives the stop filter,
	 * gets folded to `si` and is then asked of an index that dropped the word. Everything downstream is
	 * unaffected either way: {@link ASCIIFoldingFilter} collapses `ș` and `ş` to the same `s` before the
	 * variant stemmer ever sees the token.
	 *
	 * `normalize()` — the single-term prefix/fuzzy path — deliberately does not run it: that path has no stop
	 * filter, so folding alone already reconciles the two orthographies there.
	 *
	 * @return the Lucene chain
	 */
	@Nonnull
	private static Analyzer romanianSearchChain() {
		return new Analyzer() {
			@Override
			protected TokenStreamComponents createComponents(String fieldName) {
				final Tokenizer source = new StandardTokenizer();
				TokenStream stream = new CommaBelowNormalizationFilter(new LowerCaseFilter(source));
				stream = new StopFilter(stream, RomanianAnalyzer.getDefaultStopSet());
				stream = new ASCIIFoldingFilter(stream);
				stream = new VariantStemFilter(stream, new RomanianVariantStemmer());
				return new TokenStreamComponents(source, stream);
			}

			@Override
			protected TokenStream normalize(String fieldName, TokenStream in) {
				return new ASCIIFoldingFilter(new LowerCaseFilter(in));
			}
		};
	}

	/**
	 * Returns the stop words the built-in analyzer of the passed name drops, lower-cased and folded to ASCII the way
	 * {@link #foldStopWord(String)} folds a query word. Built once per name and cached.
	 *
	 * The folded form is what lets a query word typed without diacritics be recognized as a stop word the index
	 * dropped: the stop filters of the `cs`, `pl` and `ro` chains (and German's, before its normalization) run on
	 * the accented text and their lists hold accented forms only, so the index drops `není` while a query's `neni`
	 * passes the search chain and then finds nothing.
	 *
	 * @param name analyzer name
	 * @return the folded stop words, empty for an analyzer without a stop filter and for a name that is not built in
	 */
	@Nonnull
	static Set<String> foldedStopWordsFor(@Nonnull String name) {
		final BuiltInAnalyzer definition = definitionFor(name);
		if (definition == null || definition.stopWords() == null) {
			return Set.of();
		}
		return FOLDED_STOP_WORDS.computeIfAbsent(name, theName -> foldAll(definition.stopWords().get()));
	}

	/**
	 * Lower-cases a word and folds it to ASCII with {@link ASCIIFoldingFilter} - the normalization both sides of a
	 * folded stop-word comparison go through.
	 *
	 * @param word the word, typically a query word's surface form
	 * @return the folded word
	 */
	@Nonnull
	static String foldStopWord(@Nonnull String word) {
		final char[] lowerCased = word.toLowerCase(Locale.ROOT).toCharArray();
		// folding expands a character into at most four - the bound ASCIIFoldingFilter itself allocates for
		final char[] folded = new char[lowerCased.length * 4];
		final int length = ASCIIFoldingFilter.foldToASCII(lowerCased, 0, folded, 0, lowerCased.length);
		return new String(folded, 0, length);
	}

	/**
	 * Folds every word of a stop set.
	 *
	 * @param stopWords the stop set of a chain
	 * @return the folded words, unmodifiable
	 */
	@Nonnull
	private static Set<String> foldAll(@Nonnull CharArraySet stopWords) {
		final Set<String> folded = CollectionUtils.createHashSet(stopWords.size());
		for (Object stopWord : stopWords) {
			folded.add(foldStopWord(stopWord instanceof char[] chars ? new String(chars) : stopWord.toString()));
		}
		return Collections.unmodifiableSet(folded);
	}

	/**
	 * Wraps a factory as an index-side built-in: the chain gets {@link HtmlStrippingAnalyzerWrapper} in front of it,
	 * and is usable on both sides of the pipeline (see the class javadoc on why an index chain must stay usable on the
	 * query side).
	 *
	 * @param factory   builds the Lucene chain the markup stripping is put in front of
	 * @param stopWords supplies the stop set the chain drops, null when it has no stop filter
	 * @return the declaration
	 */
	@Nonnull
	private static BuiltInAnalyzer indexChain(
		@Nonnull Supplier<Analyzer> factory,
		@Nullable Supplier<CharArraySet> stopWords
	) {
		return new BuiltInAnalyzer(
			AnalysisMode.ALL, () -> new HtmlStrippingAnalyzerWrapper(factory.get()), stopWords
		);
	}

	/**
	 * Wraps a factory as a built-in usable only while analysing query text.
	 *
	 * @param factory   builds the Lucene chain
	 * @param stopWords supplies the stop set the chain drops, null when it has no stop filter
	 * @return the declaration
	 */
	@Nonnull
	private static BuiltInAnalyzer searchTime(
		@Nonnull Supplier<Analyzer> factory,
		@Nullable Supplier<CharArraySet> stopWords
	) {
		return new BuiltInAnalyzer(AnalysisMode.SEARCH_TIME, factory, stopWords);
	}

	/**
	 * The Polish stop-word list, loaded from an evitaDB resource the first time a Polish chain is built.
	 *
	 * Held in its own class purely for that laziness: a nested class's static initializer runs when the class
	 * is first touched, not when {@link BuiltInAnalyzers} is, which is the same idiom Lucene's own analyzers
	 * use for their defaults.
	 *
	 * **Why a copy rather than `PolishAnalyzer.getDefaultStopSet()`.** That call looks free and is not: the
	 * `DefaultsHolder` behind it loads the 2.2 MB Stempel stemmer table in the same static block, and Stempel
	 * is not used here at all — the index side stems with {@link PolishSnowballStemmer}, whose rule table is
	 * what the query-side variant fan-out is built over. Copying 1.2 kB of word list removed the entire
	 * `lucene-analysis-stempel` dependency. The resource is Lucene's file verbatim, header included; its
	 * provenance and licence (carrot2, BSD) are recorded in `evita_engine/NOTICE`.
	 *
	 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
	 */
	private static final class PolishStopWords {

		/**
		 * Name of the stop-word resource, which sits next to this class.
		 */
		private static final String RESOURCE_NAME = "polish-stopwords.txt";
		/**
		 * The stop words, unmodifiable. Never empty: a missing or unreadable resource is a packaging error and
		 * fails loudly rather than silently turning stop-word removal off.
		 */
		static final CharArraySet SET;

		static {
			try (final InputStream stream = BuiltInAnalyzers.class.getResourceAsStream(RESOURCE_NAME)) {
				if (stream == null) {
					throw new GenericEvitaInternalError(
						"Polish stop-word resource `" + RESOURCE_NAME + "` is missing from the evita_engine " +
							"artifact - the build is packaged incorrectly."
					);
				}
				SET = CharArraySet.unmodifiableSet(
					WordlistLoader.getWordSet(
						new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)), "#"
					)
				);
			} catch (IOException e) {
				throw new GenericEvitaInternalError(
					"Failed to read the Polish stop-word resource `" + RESOURCE_NAME + "`: " + e.getMessage(),
					"Failed to read the Polish stop-word resource.",
					e
				);
			}
		}

		private PolishStopWords() {
			// this class only holds the loaded set
		}

	}

	/**
	 * What a built-in name resolves to: the side(s) of the pipeline its chain may be used on, and the factory
	 * able to build that chain.
	 *
	 * @param mode      side(s) of the pipeline chains built by `factory` may be used on
	 * @param factory   builds the Lucene chain; called at most once per registry, when the name is first used
	 * @param stopWords supplies the stop set the chain drops, the very set its stop filter reads; null when the
	 *                  chain has no stop filter. A supplier, so that declaring it loads no list.
	 */
	record BuiltInAnalyzer(
		@Nonnull AnalysisMode mode,
		@Nonnull Supplier<Analyzer> factory,
		@Nullable Supplier<CharArraySet> stopWords
	) {
	}

}
