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

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cz.CzechAnalyzer;
import org.apache.lucene.analysis.de.GermanAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.pl.PolishAnalyzer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The built-in language table — part of the engine rather than of any configuration.
 *
 * Keyed by the **language** of a locale, not by the whole locale: `cs_CZ` and `cs` get the same analyzer,
 * because analysis is a property of a language. A language the table does not know gets the explicitly named
 * {@link #GENERIC_ANALYZER_NAME} analyzer (tokenize plus lowercase, no stemming, no folding) rather than an
 * exception — full-text over an unknown language is still better than a failing query — and the substitution is
 * reported to the log once per language, never silently skipped.
 *
 * What each language gets, and why:
 *
 * | Language | Chain                                          | Diacritics                                     |
 * |----------|------------------------------------------------|------------------------------------------------|
 * | `cs`     | `CzechAnalyzer` + folding                      | folded — `CzechAnalyzer` keeps them, see below  |
 * | `en`     | `EnglishAnalyzer`                              | not applicable                                 |
 * | `de`     | `GermanAnalyzer`                               | folded by its own `GermanNormalizationFilter`  |
 * | `pl`     | `PolishAnalyzer` (stempel)                     | left as-is                                     |
 * | `sk`     | {@link TokenizingAnalyzer} + folding           | folded                                         |
 *
 * Czech is the one that needs the extra step: `CzechAnalyzer` is `StandardTokenizer`, `LowerCaseFilter`,
 * `StopFilter` and `CzechStemFilter` — accents survive it, so `cerna` would not find `černá` at all. German is
 * the one that must **not** get it, because `GermanNormalizationFilter` already folds umlauts and maps `ß` to
 * `ss`; a second pass would fight it. See {@link DiacriticsFoldingAnalyzerWrapper} for the full argument and for
 * why folding is appended after the stemmer rather than before it.
 *
 * Every built-in analyzer is {@link AnalysisMode#ALL} — none of these chains contains a runtime-swappable
 * component, so all of them are usable on both sides of the pipeline. That is why the mode is not plumbed
 * through this class at all: {@link FulltextAnalyzerRegistry} knows a built-in name means `ALL`.
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
	 * Name of the Czech analyzer.
	 */
	public static final String CZECH_ANALYZER_NAME = "czech";
	/**
	 * Name of the English analyzer.
	 */
	public static final String ENGLISH_ANALYZER_NAME = "english";
	/**
	 * Name of the German analyzer.
	 */
	public static final String GERMAN_ANALYZER_NAME = "german";
	/**
	 * Name of the Polish analyzer.
	 */
	public static final String POLISH_ANALYZER_NAME = "polish";
	/**
	 * Name of the Slovak analyzer.
	 */
	public static final String SLOVAK_ANALYZER_NAME = "slovak";

	/**
	 * The built-in table, keyed by analyzer name. Values are factories, not analyzers: instances are expensive
	 * to build (Czech loads 172 stop words from a resource inside the jar, Polish a 2.1 MB stemmer table) and
	 * are therefore created lazily, per name, by {@link FulltextAnalyzerRegistry}.
	 */
	private static final Map<String, Supplier<Analyzer>> ANALYZERS_BY_NAME = Map.of(
		GENERIC_ANALYZER_NAME, TokenizingAnalyzer::new,
		CZECH_ANALYZER_NAME, () -> new DiacriticsFoldingAnalyzerWrapper(new CzechAnalyzer()),
		ENGLISH_ANALYZER_NAME, EnglishAnalyzer::new,
		GERMAN_ANALYZER_NAME, GermanAnalyzer::new,
		POLISH_ANALYZER_NAME, PolishAnalyzer::new,
		SLOVAK_ANALYZER_NAME, () -> new DiacriticsFoldingAnalyzerWrapper(new TokenizingAnalyzer())
	);

	/**
	 * The language table proper, keyed by ISO language code. Deliberately separate from
	 * {@link #ANALYZERS_BY_NAME}: which analyzer a language defaults to and what that analyzer *is* are two
	 * different facts, and a schema may name any analyzer for any language.
	 */
	private static final Map<String, String> NAMES_BY_LANGUAGE = Map.of(
		"cs", CZECH_ANALYZER_NAME,
		"en", ENGLISH_ANALYZER_NAME,
		"de", GERMAN_ANALYZER_NAME,
		"pl", POLISH_ANALYZER_NAME,
		"sk", SLOVAK_ANALYZER_NAME
	);

	/**
	 * Languages already reported as unknown, so that the fallback is logged once per language instead of once
	 * per analysed value.
	 */
	private static final Set<String> REPORTED_UNKNOWN_LANGUAGES = ConcurrentHashMap.newKeySet(8);

	/**
	 * Returns the name of the built-in analyzer for the language of `locale`, falling back to
	 * {@link #GENERIC_ANALYZER_NAME} for a language the table does not cover. The fallback is logged the first
	 * time it is used for a given language.
	 *
	 * @param locale locale whose language decides the analyzer; only its language part is consulted
	 * @return name of the built-in analyzer for the locale's language, never null
	 */
	@Nonnull
	public static String nameForLocale(@Nonnull Locale locale) {
		final String language = locale.getLanguage();
		final String name = NAMES_BY_LANGUAGE.get(language);
		if (name != null) {
			return name;
		}
		if (REPORTED_UNKNOWN_LANGUAGES.add(language)) {
			log.warn(
				"There is no built-in full-text analyzer for language `{}`, falling back to the `{}` analyzer " +
					"(word-break tokenization and lowercasing only - no stop words, no stemming). Register a " +
					"custom analyzer for this language to get language-aware behaviour.",
				language, GENERIC_ANALYZER_NAME
			);
		}
		return GENERIC_ANALYZER_NAME;
	}

	/**
	 * Returns the factory building the built-in analyzer registered under `name`, or null when no built-in
	 * analyzer carries that name. Used by {@link FulltextAnalyzerRegistry} to resolve a schema-supplied name and
	 * to reject runtime registrations that would shadow a built-in one.
	 *
	 * @param name analyzer name
	 * @return factory building the built-in analyzer, or null when the name is not a built-in one
	 */
	@Nullable
	public static Supplier<Analyzer> supplierFor(@Nonnull String name) {
		return ANALYZERS_BY_NAME.get(name);
	}

}
