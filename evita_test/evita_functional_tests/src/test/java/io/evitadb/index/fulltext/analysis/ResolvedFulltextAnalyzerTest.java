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

import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.TestConstants;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import static io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaContract.HTML_TEXT;
import static io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaContract.TEXT;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies how {@link ResolvedFulltextAnalyzer} resolves an analyzer of a real catalog schema for a locale: the
 * built-in `text` and `html-text` definitions, the order in which a phase is looked up along `basedOn`, the word
 * splitting of the language, the resources, and the names it refuses.
 */
@DisplayName("Resolved fulltext analyzer")
@Tag(ENGINE)
@Tag(FULLTEXT)
class ResolvedFulltextAnalyzerTest {
	/** The word splitting of a language the catalog does not declare. */
	private static final List<AnalysisStep> STANDARD = List.of(standardTokenizer());
	/** The words phase of `text` in Czech. */
	private static final List<AnalysisStep> CZECH_WORDS =
		List.of(lowercase(), stopWords(czechStopWords()), czechStemming());
	/** The words phase of `text` in German. */
	private static final List<AnalysisStep> GERMAN_WORDS =
		List.of(lowercase(), stopWords(germanStopWords()), germanStemming());

	/** The built-in analyzers. */
	@Nested
	@DisplayName("Built-in analyzers")
	class BuiltIn {

		/** The engine defines exactly the names the API reserves. */
		@Test
		@DisplayName("should define every reserved name and no other")
		void shouldDefineEveryReservedName() {
			assertEquals(
				FulltextAnalyzerSchemaContract.BUILT_IN_ANALYZERS, BuiltInFulltextAnalyzers.getAnalyzers().keySet()
			);
		}

		/** `text` takes the line of each built-in language and lowercases any other language. */
		@Test
		@DisplayName("should resolve the words phase of text per language")
		void shouldResolveTextPerLanguage() {
			final Map<String, List<AnalysisStep>> expected = Map.of(
				"cs", CZECH_WORDS,
				"sk", List.of(lowercase(), slovakStemming()),
				"pl", List.of(lowercase(), stopWords(polishStopWords()), polishStemming()),
				"ro", List.of(lowercase(), romanianNormalization(), stopWords(romanianStopWords()), romanianStemming()),
				"en", List.of(englishPossessive(), lowercase(), stopWords(englishStopWords()), englishStemming()),
				"de", GERMAN_WORDS,
				"fr", List.of(lowercase())
			);
			final CatalogSchemaContract schema = emptyCatalog();
			for (final Map.Entry<String, List<AnalysisStep>> entry : expected.entrySet()) {
				final ResolvedFulltextAnalyzer resolved =
					ResolvedFulltextAnalyzer.resolve(schema, TEXT, Locale.forLanguageTag(entry.getKey()));
				assertEquals(entry.getValue(), resolved.words(), entry.getKey());
				assertEquals(List.of(), resolved.text(), entry.getKey());
				assertEquals(STANDARD, resolved.splitting(), entry.getKey());
			}
		}

		/** `html-text` strips markup and inherits the words phase of `text` in every language. */
		@Test
		@DisplayName("should resolve html-text as text with markup stripped")
		void shouldResolveHtmlText() {
			final CatalogSchemaContract schema = emptyCatalog();
			final ResolvedFulltextAnalyzer czech = ResolvedFulltextAnalyzer.resolve(schema, HTML_TEXT, cs());
			assertEquals(List.of(htmlStrip()), czech.text());
			assertEquals(CZECH_WORDS, czech.words());

			final ResolvedFulltextAnalyzer french =
				ResolvedFulltextAnalyzer.resolve(schema, HTML_TEXT, Locale.forLanguageTag("fr"));
			assertEquals(List.of(htmlStrip()), french.text());
			assertEquals(List.of(lowercase()), french.words());
		}

		/** The root locale picks no override: the all-languages definition. */
		@Test
		@DisplayName("should resolve the all-languages definition for the root locale")
		void shouldResolveAllLanguagesForRootLocale() {
			final ResolvedFulltextAnalyzer resolved =
				ResolvedFulltextAnalyzer.resolve(emptyCatalog(), TEXT, Locale.ROOT);
			assertEquals(List.of(lowercase()), resolved.words());
			assertEquals(STANDARD, resolved.splitting());
			assertEquals(Locale.ROOT, resolved.context().locale());
		}
	}

	/** The order in which a phase is looked up along `basedOn`. */
	@Nested
	@DisplayName("Inheritance")
	class Inheritance {

		/**
		 * A language override that defines only the words phase keeps the text phase of its analyzer's all
		 * languages, and a language it does not override takes the words of the analyzer it is based on.
		 */
		@Test
		@DisplayName("should resolve each phase on its own")
		void shouldResolveEachPhaseOnItsOwn() {
			final CatalogSchemaContract schema = catalog(
				b -> b.withFulltextAnalyzer(
					"names",
					a -> a.basedOn(TEXT)
						.text(htmlStrip())
						.language("cs", l -> l.words(lowercase(), asciiFolding()))
				)
			);

			final ResolvedFulltextAnalyzer czech = ResolvedFulltextAnalyzer.resolve(schema, "names", cs());
			assertEquals(List.of(htmlStrip()), czech.text());
			assertEquals(List.of(lowercase(), asciiFolding()), czech.words());

			final ResolvedFulltextAnalyzer german = ResolvedFulltextAnalyzer.resolve(schema, "names", Locale.GERMAN);
			assertEquals(List.of(htmlStrip()), german.text());
			assertEquals(GERMAN_WORDS, german.words());
		}

		/** A phase defined with no steps is defined: it stops the lookup instead of inheriting. */
		@Test
		@DisplayName("should stop at a phase defined with no steps")
		void shouldStopAtAnEmptyPhase() {
			final CatalogSchemaContract schema = catalog(
				b -> b.withFulltextAnalyzer("plain", a -> a.basedOn(HTML_TEXT).language("cs", l -> l.text()))
			);

			assertEquals(List.of(), ResolvedFulltextAnalyzer.resolve(schema, "plain", cs()).text());
			assertEquals(
				List.of(htmlStrip()), ResolvedFulltextAnalyzer.resolve(schema, "plain", Locale.ENGLISH).text()
			);
		}

		/** The lookup follows `basedOn` over several analyzers, declared and built-in. */
		@Test
		@DisplayName("should follow basedOn over several analyzers")
		void shouldFollowSeveralBasedOn() {
			final CatalogSchemaContract schema = catalog(
				b -> b
					.withFulltextAnalyzer("folded", a -> a.basedOn(HTML_TEXT).language("de", l -> l.words(lowercase())))
					.withFulltextAnalyzer("titles", a -> a.basedOn("folded"))
			);

			final ResolvedFulltextAnalyzer czech = ResolvedFulltextAnalyzer.resolve(schema, "titles", cs());
			assertEquals(List.of(htmlStrip()), czech.text());
			assertEquals(CZECH_WORDS, czech.words());
			assertEquals(
				List.of(lowercase()), ResolvedFulltextAnalyzer.resolve(schema, "titles", Locale.GERMAN).words()
			);
		}
	}

	/** What the analyzer takes from the rest of the catalog: the word splitting and the resources. */
	@Nested
	@DisplayName("Catalog parts")
	class CatalogParts {

		/** A declared language splits by its own steps; another language by the standard tokenizer. */
		@Test
		@DisplayName("should take the word splitting of the language")
		void shouldTakeTheWordSplittingOfTheLanguage() {
			final CatalogSchemaContract schema = catalog(b -> b.withFulltextLanguage("cs", l -> l.wordNumberSplit()));

			assertEquals(
				List.of(standardTokenizer(), wordNumberSplit()),
				ResolvedFulltextAnalyzer.resolve(schema, TEXT, cs()).splitting()
			);
			assertEquals(STANDARD, ResolvedFulltextAnalyzer.resolve(schema, TEXT, Locale.ENGLISH).splitting());
		}

		/** The context holds the locale, the resources of the catalog and the built-in ones. */
		@Test
		@DisplayName("should offer the catalog's and the built-in resources")
		void shouldOfferTheResources() {
			final CatalogSchemaContract schema = catalog(b -> b.withFulltextResource("brands", wordList("Nike")));

			final ResolvedFulltextAnalyzer resolved = ResolvedFulltextAnalyzer.resolve(schema, TEXT, cs());
			assertEquals(cs(), resolved.context().locale());
			assertEquals(wordList("Nike"), resolved.context().getResource("brands"));
			assertTrue(resolved.context().resources().keySet().containsAll(BUILT_IN_RESOURCES));
		}
	}

	/** Names that lead nowhere - possible in a catalog schema the session has not validated yet. */
	@Nested
	@DisplayName("Refusals")
	class Refusals {

		/** A name that is neither built in nor declared. */
		@Test
		@DisplayName("should refuse an unknown analyzer")
		void shouldRefuseAnUnknownAnalyzer() {
			final EvitaInvalidUsageException ex = assertThrows(
				EvitaInvalidUsageException.class, () -> ResolvedFulltextAnalyzer.resolve(emptyCatalog(), "codes", cs())
			);
			assertTrue(ex.getMessage().contains("`codes`"), ex.getMessage());
		}

		/** `basedOn` naming an analyzer that does not exist. */
		@Test
		@DisplayName("should refuse basedOn naming an unknown analyzer")
		void shouldRefuseAnUnknownBasedOn() {
			final CatalogSchemaContract schema =
				catalog(b -> b.withFulltextAnalyzer("titles", a -> a.basedOn("codes")));

			final EvitaInvalidUsageException ex = assertThrows(
				EvitaInvalidUsageException.class, () -> ResolvedFulltextAnalyzer.resolve(schema, "titles", cs())
			);
			assertTrue(ex.getMessage().contains("`titles` is based on analyzer `codes`"), ex.getMessage());
		}

		/** `basedOn` leading back to an analyzer already on the way. */
		@Test
		@DisplayName("should refuse a cycle of basedOn")
		void shouldRefuseACycle() {
			final CatalogSchemaContract schema = catalog(
				b -> b
					.withFulltextAnalyzer("a", a -> a.basedOn("b"))
					.withFulltextAnalyzer("b", a -> a.basedOn("a"))
			);

			assertThrows(EvitaInvalidUsageException.class, () -> ResolvedFulltextAnalyzer.resolve(schema, "a", cs()));
		}
	}

	/**
	 * Returns the Czech locale.
	 *
	 * @return the locale of the language `cs`
	 */
	@Nonnull
	private static Locale cs() {
		return Locale.forLanguageTag("cs");
	}

	/**
	 * Creates an empty catalog schema.
	 *
	 * @return the schema
	 */
	@Nonnull
	private static CatalogSchema emptyCatalog() {
		return CatalogSchema._internalBuild(
			TestConstants.TEST_CATALOG,
			NamingConvention.generate(TestConstants.TEST_CATALOG),
			null,
			EnumSet.allOf(CatalogEvolutionMode.class),
			EmptyEntitySchemaAccessor.INSTANCE
		);
	}

	/**
	 * Builds a catalog schema by applying changes to an empty one.
	 *
	 * @param changes the changes to make
	 * @return the resulting schema, not validated
	 */
	@Nonnull
	private static CatalogSchemaContract catalog(@Nonnull Consumer<InternalCatalogSchemaBuilder> changes) {
		final InternalCatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(emptyCatalog());
		changes.accept(builder);
		return builder.toInstance();
	}
}
