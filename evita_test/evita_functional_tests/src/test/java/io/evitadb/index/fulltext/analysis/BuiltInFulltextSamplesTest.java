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

import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps;
import io.evitadb.api.requestResponse.schema.analysis.WordList;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.query.filter.translator.fulltext.TextMatchesTranslator;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.CollectionUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.*;

/** Pins all twelve language samples against both production sides of every built-in analyzer in a real catalog. */
@Tag(ENGINE)
@Tag(FULLTEXT)
@Tag(SCHEMA)
@DisplayName("Built-in language self-match samples")
class BuiltInFulltextSamplesTest implements EvitaTestSupport {
	/** Each sample must leave index terms and find itself as typed, lower-cased and canonically folded. */
	@Test
	@DisplayName("Every built-in analyzer self-matches all twelve samples of every built-in language")
	void shouldSelfMatchEveryBuiltInSample() {
		final TestPaths paths = createTestPaths("BuiltInFulltextSamples");
		try (final Evita engine = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
			engine.defineCatalog(TEST_CATALOG);
			engine.updateCatalog(TEST_CATALOG, session -> {
				final Catalog catalog = (Catalog) engine.getCatalogInstanceOrThrowException(TEST_CATALOG);
				for (final String language : List.of("cs", "sk", "pl", "ro", "en", "de")) {
					final Locale locale = Locale.forLanguageTag(language);
					assertEquals(12, BuiltInFulltextSamples.forLocale(locale).size());
					for (final String name : BuiltInFulltextAnalyzers.getAnalyzers().keySet()) {
						final CompiledFulltextAnalyzer chain = catalog.getFulltextAnalyzerRegistry()
							.getCompiledAnalyzer(session.getCatalogSchema(), name, locale);
						for (final String sample : BuiltInFulltextSamples.forLocale(locale)) {
							final Set<String> index = CollectionUtils.createHashSet(8);
							chain.indexSurface().analyzeIndexed(sample,
								(term, preStem, surface, start, end, increment) -> index.add(term));
							assertFalse(index.isEmpty(), language + ": " + sample);
							final String folded = Normalizer.normalize(sample.toLowerCase(Locale.ROOT),
								Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
							for (final String query : List.of(sample, sample.toLowerCase(Locale.ROOT), folded)) {
								final String[][][] alternatives =
									TextMatchesTranslator.analyzeAlternatives(chain, query, index);
								assertTrue(alternatives.length > 0, language + ": " + sample);
								for (final String[][] word : alternatives) {
									assertTrue(Arrays.stream(word).flatMap(Arrays::stream).anyMatch(index::contains),
										name + "/" + language + " sample=" + sample + " query=" + query +
											" index=" + index + " search=" + Arrays.deepToString(alternatives));
								}
							}
						}
					}
				}
			});
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/** Checks all built-in stop words on each actual chain, including entries beyond any validation bound. */
	@Test
	@DisplayName("Every built-in stop word disappears from index analysis")
	void shouldRemoveEveryBuiltInStopWord() {
		final TestPaths paths = createTestPaths("BuiltInFulltextStopWords");
		try (final Evita engine = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
			engine.defineCatalog(TEST_CATALOG);
			engine.queryCatalog(TEST_CATALOG, session -> {
				final Catalog catalog = (Catalog) engine.getCatalogInstanceOrThrowException(TEST_CATALOG);
				for (final String name : BuiltInFulltextAnalyzers.getAnalyzers().keySet()) {
					int count = 0;
					for (final String language : List.of("cs", "sk", "pl", "ro", "en", "de")) {
						final Locale locale = Locale.forLanguageTag(language);
						final var resolved = ResolvedFulltextAnalyzer.resolve(session.getCatalogSchema(), name, locale);
						final var chain = catalog.getFulltextAnalyzerRegistry()
							.getCompiledAnalyzer(session.getCatalogSchema(), name, locale);
						for (final var step : resolved.words()) {
							if (AnalysisSteps.STOP_WORDS.equals(step.name())) {
								for (final String resource : (String[]) step.parameters().get(AnalysisSteps.WORDS)) {
									final WordList words = (WordList) resolved.context().getResource(resource);
									for (final String word : words.words()) {
										chain.indexSurface().analyzeIndexed(word,
											(term, stem, surface, start, end, inc) -> assertTrue(term.isEmpty(),
												name + "/" + language + "/" + word + " -> " + term));
										count++;
									}
								}
							}
						}
					}
					// the total over the six languages; the Romanian list of Lucene 10 has 24 more entries than the one
					// of Lucene 9 (the comma-below spellings), 254 instead of 230
					assertEquals(871, count, name);
				}
			});
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/** A submitted Polish query ignores the abbreviation and can match a document containing only the noun. */
	@Test
	@DisplayName("Drops the Polish abbreviation from a real textMatches query")
	void shouldFindPolishDocumentWithoutAbbreviation() {
		final TestPaths paths = createTestPaths("BuiltInPolishAbbreviation");
		final Locale polish = Locale.forLanguageTag("pl");
		try (final Evita engine = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
			engine.defineCatalog(TEST_CATALOG);
			engine.updateCatalog(TEST_CATALOG, session -> {
				session.defineEntitySchema("Item").withLocale(polish)
					.withAttribute("name", String.class, a -> a.localized().searchable()).updateVia(session);
				session.upsertEntity(session.createNewEntity("Item", 1).setAttribute("name", polish, "kubek"));
			});
			engine.queryCatalog(TEST_CATALOG, session -> {
				final List<EntityReference> result = session.queryList(query(collection("Item"),
					filterBy(entityLocaleEquals(polish), textMatches("kubek o.o."))), EntityReference.class);
				assertEquals(List.of(1), result.stream().map(EntityReference::getPrimaryKey).toList());
			});
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/** The language lookup that decides which built-in samples the behaviour check applies. */
	@Nested
	@DisplayName("Language lookup")
	class LanguageLookup {
		/** Root and a language without a built-in list get no samples; French-field fixtures rely on that. */
		@Test
		@DisplayName("Returns no samples for root and for a language without a built-in list")
		void shouldReturnNoSamplesOutsideBuiltInLanguages() {
			assertTrue(BuiltInFulltextSamples.forLocale(Locale.ROOT).isEmpty());
			assertTrue(BuiltInFulltextSamples.forLocale(Locale.FRENCH).isEmpty());
		}

		/** A field locale may carry a region; only its language selects the list. */
		@Test
		@DisplayName("Selects the samples by language, ignoring the region")
		void shouldSelectSamplesByLanguageOnly() {
			assertEquals(BuiltInFulltextSamples.forLocale(Locale.ENGLISH), BuiltInFulltextSamples.forLocale(Locale.UK));
			assertEquals(BuiltInFulltextSamples.forLocale(Locale.forLanguageTag("cs")),
				BuiltInFulltextSamples.forLocale(Locale.forLanguageTag("cs-CZ")));
		}

		/** A list without a diacritic would make the diacritics-free form of the self-match check vacuous. */
		@Test
		@DisplayName("Holds twelve distinct words per language, with diacritics wherever the language has them")
		void shouldHoldTwelveDistinctWordsPerLanguage() {
			for (final String language : List.of("cs", "sk", "pl", "ro", "en", "de")) {
				final List<String> samples = BuiltInFulltextSamples.forLocale(Locale.forLanguageTag(language));
				assertEquals(12, Set.copyOf(samples).size(), language);
				assertTrue(samples.stream().noneMatch(String::isBlank), language);
				final boolean withDiacritics = samples.stream().anyMatch(sample -> {
					final String lower = sample.toLowerCase(Locale.ROOT);
					return !lower.equals(Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", ""));
				});
				assertEquals(!"en".equals(language), withDiacritics, language);
			}
		}
	}

}
