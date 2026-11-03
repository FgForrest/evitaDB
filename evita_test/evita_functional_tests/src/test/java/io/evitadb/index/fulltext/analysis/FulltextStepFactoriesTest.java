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

import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.analysis.FulltextResourceContent;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.index.analysis.AsciiFoldingAnalysisStepFactory;
import io.evitadb.index.analysis.LowercaseAnalysisStepFactory;
import io.evitadb.spi.analysis.FulltextAnalysisContext;
import io.evitadb.spi.analysis.FulltextAnalysisSide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the built-in fulltext step providers one by one: what each refuses, which of its parameters it declares
 * as acting on the index side, and the words a chain built from it produces.
 */
@DisplayName("Built-in fulltext step providers")
@Tag(ENGINE)
@Tag(FULLTEXT)
class FulltextStepFactoriesTest {
	/** The word splitting of every chain here. */
	private static final List<AnalysisStep> SPLITTING = List.of(standardTokenizer());

	/** The compiler resolving the installed providers. */
	private final FulltextChainCompiler compiler = new FulltextChainCompiler(new AnalysisStepRegistry());

	/** What a provider checks and declares without building a chain. */
	@Nested
	@DisplayName("Declarations")
	class Declarations {

		/** A provider invoked directly instead of through the registry still refuses a parameter it does not take. */
		@Test
		@DisplayName("should validate parameters before creating an operation")
		void shouldValidateParametersBeforeCreatingOperation() {
			final Map<String, Serializable> unexpected = Map.of("unexpected", "x");
			final FulltextAnalysisContext context = new FulltextAnalysisContext(Locale.ROOT, Map.of());

			for (final Executable creation : List.<Executable>of(
				() -> new StandardTokenizerAnalysisStepFactory().create(unexpected, context),
				() -> new StopWordsAnalysisStepFactory().create(unexpected, context),
				() -> new CzechStemmingAnalysisStepFactory().create(unexpected, context),
				() -> new LowercaseAnalysisStepFactory().create(unexpected, context),
				() -> new AsciiFoldingAnalysisStepFactory().create(unexpected, context)
			)) {
				assertThrows(EvitaInvalidUsageException.class, creation);
			}
		}

		/** The word lists of stop words and protected words change the indexed terms; nothing else here has one. */
		@Test
		@DisplayName("should declare the parameters that change the index terms")
		void shouldDeclareParametersThatChangeIndexTerms() {
			assertEquals(Set.of(), new StandardTokenizerAnalysisStepFactory().getIndexSideParameters());
			assertEquals(Set.of(), new LowercaseAnalysisStepFactory().getIndexSideParameters());
			assertEquals(Set.of(), new AsciiFoldingAnalysisStepFactory().getIndexSideParameters());
			assertEquals(Set.of(WORDS), new StopWordsAnalysisStepFactory().getIndexSideParameters());
			assertEquals(Set.of(PROTECTED_WORDS), new CzechStemmingAnalysisStepFactory().getIndexSideParameters());
		}
	}

	/** The `stop-words` step removes the words of the word lists it names. */
	@Nested
	@DisplayName("Stop words")
	class StopWords {

		/** The words of every named list are removed, not only those of the first one. */
		@Test
		@DisplayName("should remove the words of every named list")
		void shouldRemoveWordsOfEveryNamedList() {
			final List<AnalysisStep> words = List.of(lowercase(), stopWords(resource("first"), resource("second")));
			final Map<String, FulltextResourceContent> resources = Map.of(
				"first", wordList("a", "the"), "second", wordList("of")
			);

			try (final FulltextAnalyzer chain = chain(words, Locale.ENGLISH, resources, FulltextAnalysisSide.INDEX)) {
				assertEquals(List.of("book", "jungle"), termTexts(chain, "The book of a jungle"));
			}
		}

		/** A synonym list is a resource of another kind, never a list of words to remove. */
		@Test
		@DisplayName("should refuse a resource that is not a word list")
		void shouldRefuseResourceThatIsNotWordList() {
			final List<AnalysisStep> words = List.of(stopWords(resource("groups")));
			final Map<String, FulltextResourceContent> resources = Map.of(
				"groups", synonymList(synonymGroup("tv", "television"))
			);

			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> chain(words, Locale.ENGLISH, resources, FulltextAnalysisSide.INDEX)
			);

			assertTrue(exception.getMessage().contains("`groups` is not a word list"), exception.getMessage());
		}
	}

	/** The `lowercase` step lowercases every word by the rules of the field's language. */
	@Nested
	@DisplayName("Lowercase")
	class Lowercase {

		/** Turkish and Azeri keep the dot apart: `I` lowercases to the dotless `ı`, the dotted `İ` to `i`. */
		@Test
		@DisplayName("should keep dotted and dotless I apart in Turkish and Azeri")
		void shouldKeepDottedAndDotlessIApartInTurkishAndAzeri() {
			for (final Locale locale : List.of(Locale.forLanguageTag("tr"), Locale.forLanguageTag("az"))) {
				try (
					final FulltextAnalyzer chain = chain(
						List.of(lowercase()), locale, Map.of(), FulltextAnalysisSide.INDEX
					)
				) {
					assertEquals(List.of("ırmak", "istanbul"), termTexts(chain, "IRMAK İSTANBUL"), locale.toString());
				}
			}
		}
	}

	/** The `ascii-folding` step folds every word to ASCII. */
	@Nested
	@DisplayName("ASCII folding")
	class AsciiFolding {

		/** The step acts on both sides, so an accented query meets an accented text. */
		@Test
		@DisplayName("should fold diacritics on both sides")
		void shouldFoldDiacriticsOnBothSides() {
			for (final FulltextAnalysisSide side : FulltextAnalysisSide.values()) {
				try (
					final FulltextAnalyzer chain = chain(
						List.of(lowercase(), asciiFolding()), Locale.forLanguageTag("cs"), Map.of(), side
					)
				) {
					assertEquals(List.of("zlutoucky", "kun"), termTexts(chain, "Žluťoučký kůň"), side.name());
				}
			}
		}
	}

	/**
	 * Compiles a chain of the standard tokenizer and the given words phase and wraps it the way the registry wraps
	 * today's chains.
	 *
	 * @param words     the words phase
	 * @param locale    the locale the chain is built for
	 * @param resources the resources the steps may name
	 * @param side      the side to compile
	 * @return the wrapped chain, to be closed by the caller
	 */
	@Nonnull
	private FulltextAnalyzer chain(
		@Nonnull List<AnalysisStep> words,
		@Nonnull Locale locale,
		@Nonnull Map<String, FulltextResourceContent> resources,
		@Nonnull FulltextAnalysisSide side
	) {
		return new FulltextAnalyzer(
			"test",
			this.compiler.compile(List.of(), SPLITTING, words, side, new FulltextAnalysisContext(locale, resources))
		);
	}

	/**
	 * Returns the text of every term a chain produces for a text.
	 *
	 * @param analyzer the chain
	 * @param text     the text
	 * @return the term texts in stream order
	 */
	@Nonnull
	private static List<String> termTexts(@Nonnull FulltextAnalyzer analyzer, @Nonnull String text) {
		return analyzer.getTerms(text).stream().map(AnalyzedTerm::term).toList();
	}
}
