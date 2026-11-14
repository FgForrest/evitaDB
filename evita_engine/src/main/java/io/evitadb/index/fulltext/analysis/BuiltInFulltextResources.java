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

import io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps;
import io.evitadb.api.requestResponse.schema.analysis.FulltextResourceContent;
import io.evitadb.api.requestResponse.schema.analysis.WordList;
import io.evitadb.exception.GenericEvitaInternalError;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.WordlistLoader;
import org.apache.lucene.analysis.cz.CzechAnalyzer;
import org.apache.lucene.analysis.de.GermanAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.ro.RomanianAnalyzer;

import javax.annotation.Nonnull;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The built-in fulltext resources: the stop lists of the built-in `text` analyzer, read-only under the reserved names
 * of {@link AnalysisSteps#BUILT_IN_RESOURCES}. Each holds the stop set of the Lucene analyzer the language's chain was
 * built from - for Polish evitaDB's own copy of it, see {@link PolishStopWords} - so a step list naming them produces
 * the terms of the chains it replaces. The lists are read the first time any of them is asked for.
 */
final class BuiltInFulltextResources {

	/**
	 * Prevents instantiation of this holder.
	 */
	private BuiltInFulltextResources() {
	}

	/**
	 * Returns the built-in resources keyed by their reserved names.
	 *
	 * @return the built-in resources, immutable
	 */
	@Nonnull
	static Map<String, FulltextResourceContent> getResources() {
		return Resources.ALL;
	}

	/**
	 * Converts a Lucene stop set to a word list, its words sorted so that the list does not depend on the order in
	 * which the set happens to iterate.
	 *
	 * @param stopWords the stop set
	 * @return the word list holding the same words
	 */
	@Nonnull
	private static WordList toWordList(@Nonnull CharArraySet stopWords) {
		final List<String> words = new ArrayList<>(stopWords.size());
		for (final Object word : stopWords) {
			words.add(word instanceof char[] chars ? new String(chars) : word.toString());
		}
		Collections.sort(words);
		return new WordList(words);
	}

	/**
	 * Holds the resources, so that the lists are read when a resource is first asked for, not when the enclosing
	 * class is first touched.
	 */
	private static final class Resources {
		/**
		 * The built-in resources keyed by their reserved names.
		 */
		static final Map<String, FulltextResourceContent> ALL = Map.of(
			AnalysisSteps.CZECH_STOP_WORDS, toWordList(CzechAnalyzer.getDefaultStopSet()),
			AnalysisSteps.POLISH_STOP_WORDS, toWordList(PolishStopWords.SET),
			AnalysisSteps.ROMANIAN_STOP_WORDS, toWordList(RomanianAnalyzer.getDefaultStopSet()),
			AnalysisSteps.ENGLISH_STOP_WORDS, toWordList(EnglishAnalyzer.ENGLISH_STOP_WORDS_SET),
			AnalysisSteps.GERMAN_STOP_WORDS, toWordList(GermanAnalyzer.getDefaultStopSet())
		);

		/**
		 * Prevents instantiation of this holder.
		 */
		private Resources() {
		}
	}

	/**
	 * The Polish stop-word list, loaded from an evitaDB resource the first time a Polish chain or the
	 * `polish-stop-words` resource asks for it.
	 *
	 * Held in its own class purely for that laziness: a nested class's static initializer runs when the class
	 * is first touched, not when its enclosing class is, which is the same idiom Lucene's own analyzers
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
	static final class PolishStopWords {

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
			try (final InputStream stream = BuiltInFulltextResources.class.getResourceAsStream(RESOURCE_NAME)) {
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
}
