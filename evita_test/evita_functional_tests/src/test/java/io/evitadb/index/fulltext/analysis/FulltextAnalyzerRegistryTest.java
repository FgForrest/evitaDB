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

import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.fulltext.analysis.BuiltInAnalyzers.BuiltInAnalyzer;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.standard.StandardTokenizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of {@link FulltextAnalyzerRegistry} — how a (collection, locale) pair resolves to the built-in analyzer names
 * of its language, that instances are shared per name, that a persisted index can name its analyzer directly, and
 * that a closed registry stops handing analyzers out, including to a lookup racing with the close.
 *
 * @author Lukáš Hornych (hornych@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Full-text analyzer registry")
@Tag(ENGINE)
@Tag(FULLTEXT)
class FulltextAnalyzerRegistryTest {

	private static final String ENTITY_TYPE = "PRODUCT";
	private static final String OTHER_ENTITY_TYPE = "CATEGORY";
	private static final String SLOW_ANALYZER_NAME = "slow-to-build";
	private static final Locale CZECH_CZ = new Locale("cs", "CZ");
	private static final Locale CZECH = new Locale("cs");
	private static final Locale SLOVAK = new Locale("sk");
	private static final Locale POLISH = new Locale("pl");
	private static final Locale ROMANIAN = new Locale("ro");
	private static final Locale ENGLISH = new Locale("en");
	private static final Locale GERMAN = new Locale("de");

	@Nullable private FulltextAnalyzerRegistry registry;

	@AfterEach
	void tearDown() {
		if (this.registry != null) {
			this.registry.close();
			this.registry = null;
		}
	}

	/**
	 * Creates the registry under test over `definitions` and remembers it so that it gets closed after the test.
	 *
	 * @param definitions translates an analyzer name into its declaration
	 * @return the registry under test
	 */
	@Nonnull
	private FulltextAnalyzerRegistry createRegistry(@Nonnull Function<String, BuiltInAnalyzer> definitions) {
		this.registry = new FulltextAnalyzerRegistry(definitions);
		return this.registry;
	}

	/**
	 * Creates the registry under test over the built-in analyzers.
	 *
	 * @return the registry under test
	 */
	@Nonnull
	private FulltextAnalyzerRegistry createRegistry() {
		return createRegistry(BuiltInAnalyzers::definitionFor);
	}

	/**
	 * Creates the registry under test over the built-in analyzers plus one chain named {@link #SLOW_ANALYZER_NAME},
	 * built by `factory` - the seam the tests of the closing race control the chain's construction through.
	 *
	 * @param factory builds the extra chain
	 * @return the registry under test
	 */
	@Nonnull
	private FulltextAnalyzerRegistry createRegistryWithSlowAnalyzer(@Nonnull Supplier<Analyzer> factory) {
		final BuiltInAnalyzer slow = new BuiltInAnalyzer(factory, null);
		return createRegistry(name -> SLOW_ANALYZER_NAME.equals(name) ? slow : BuiltInAnalyzers.definitionFor(name));
	}

	/**
	 * Returns the terms of `text` as produced by `analyzer`, for comparing what two slots emit.
	 *
	 * @param analyzer analyzer to run
	 * @param text     text to analyse
	 * @return terms produced by the analyzer
	 */
	@Nonnull
	private static List<String> terms(@Nonnull FulltextAnalyzer analyzer, @Nonnull String text) {
		return analyzer.getTerms(text).stream().map(AnalyzedTerm::term).toList();
	}

	@Nested
	@DisplayName("Folded stop words")
	class FoldedStopWords {

		@Test
		@DisplayName("A Czech stop word typed without diacritics is recognized, an ordinary word is not")
		void shouldRecognizeCzechStopWordsWithoutDiacritics() {
			final FulltextAnalyzerRegistry registry = createRegistry();
			assertTrue(registry.isFoldedStopWord(BuiltInAnalyzers.CZECH_ANALYZER_NAME, "neni"));
			assertTrue(registry.isFoldedStopWord(BuiltInAnalyzers.CZECH_ANALYZER_NAME, "Není"));
			assertTrue(registry.isFoldedStopWord(BuiltInAnalyzers.CZECH_ANALYZER_NAME, "JESTE"));
			// `být` folds into the real word `byt` - which is why the caller checks the index first
			assertTrue(registry.isFoldedStopWord(BuiltInAnalyzers.CZECH_ANALYZER_NAME, "byt"));
			assertFalse(registry.isFoldedStopWord(BuiltInAnalyzers.CZECH_ANALYZER_NAME, "skladem"));
			final Set<String> stopWords = registry.getFoldedStopWords(BuiltInAnalyzers.CZECH_ANALYZER_NAME);
			assertTrue(stopWords.contains("neni"));
			assertFalse(stopWords.contains("není"), "The set holds the folded forms only");
			// the search-side chain drops the same words
			assertEquals(stopWords, registry.getFoldedStopWords(BuiltInAnalyzers.CZECH_SEARCH_ANALYZER_NAME));
		}

		@Test
		@DisplayName("A Polish stop word typed without diacritics is recognized, an ordinary word is not")
		void shouldRecognizePolishStopWords() {
			final FulltextAnalyzerRegistry registry = createRegistry();
			assertTrue(registry.isFoldedStopWord(BuiltInAnalyzers.POLISH_ANALYZER_NAME, "byc"));
			assertTrue(registry.isFoldedStopWord(BuiltInAnalyzers.POLISH_ANALYZER_NAME, "juz"));
			assertFalse(registry.isFoldedStopWord(BuiltInAnalyzers.POLISH_ANALYZER_NAME, "herbata"));
			// the search-side chain drops the same words
			assertEquals(
				registry.getFoldedStopWords(BuiltInAnalyzers.POLISH_ANALYZER_NAME),
				registry.getFoldedStopWords(BuiltInAnalyzers.POLISH_SEARCH_ANALYZER_NAME)
			);
		}

		@Test
		@DisplayName("A Romanian stop word is recognized folded in both orthographies, an ordinary word is not")
		void shouldRecognizeRomanianStopWordsInBothOrthographies() {
			final FulltextAnalyzerRegistry registry = createRegistry();
			assertTrue(registry.isFoldedStopWord(BuiltInAnalyzers.ROMANIAN_ANALYZER_NAME, "dupa"));
			// the list is written with cedilla, a modern query with comma below - folding collapses both
			assertTrue(registry.isFoldedStopWord(BuiltInAnalyzers.ROMANIAN_ANALYZER_NAME, "și"));
			assertTrue(registry.isFoldedStopWord(BuiltInAnalyzers.ROMANIAN_ANALYZER_NAME, "şi"));
			assertFalse(registry.isFoldedStopWord(BuiltInAnalyzers.ROMANIAN_ANALYZER_NAME, "ceai"));
			// the search-side chain drops the same words
			assertEquals(
				registry.getFoldedStopWords(BuiltInAnalyzers.ROMANIAN_ANALYZER_NAME),
				registry.getFoldedStopWords(BuiltInAnalyzers.ROMANIAN_SEARCH_ANALYZER_NAME)
			);
		}

		@Test
		@DisplayName("A German stop word typed without an umlaut is recognized, an ordinary word is not")
		void shouldRecognizeGermanStopWords() {
			final FulltextAnalyzerRegistry registry = createRegistry();
			assertTrue(registry.isFoldedStopWord(BuiltInAnalyzers.GERMAN_ANALYZER_NAME, "fur"));
			assertTrue(registry.isFoldedStopWord(BuiltInAnalyzers.GERMAN_ANALYZER_NAME, "für"));
			assertFalse(registry.isFoldedStopWord(BuiltInAnalyzers.GERMAN_ANALYZER_NAME, "tee"));
			// the search-side chain drops the same words
			assertEquals(
				registry.getFoldedStopWords(BuiltInAnalyzers.GERMAN_ANALYZER_NAME),
				registry.getFoldedStopWords(BuiltInAnalyzers.GERMAN_SEARCH_ANALYZER_NAME)
			);
		}

		@Test
		@DisplayName("A chain without a stop filter and an unknown name have no stop words")
		void shouldHaveNoStopWordsWithoutStopFilter() {
			final FulltextAnalyzerRegistry registry = createRegistry();
			assertTrue(registry.getFoldedStopWords(BuiltInAnalyzers.SLOVAK_ANALYZER_NAME).isEmpty());
			assertTrue(registry.getFoldedStopWords(BuiltInAnalyzers.GENERIC_ANALYZER_NAME).isEmpty());
			assertTrue(registry.getFoldedStopWords("unknown").isEmpty());
			assertFalse(registry.isFoldedStopWord("unknown", "the"));
		}

		@Test
		@DisplayName("A closed registry refuses the lookup")
		void shouldRefuseLookupWhenClosed() {
			// not remembered for the tear-down, which would close it a second time
			final FulltextAnalyzerRegistry registry = new FulltextAnalyzerRegistry();
			registry.close();
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> registry.getFoldedStopWords(BuiltInAnalyzers.CZECH_ANALYZER_NAME)
			);
		}

	}

	@Nested
	@DisplayName("Instance resolution")
	class InstanceResolution {

		@Test
		@DisplayName("Locales of the same language share one instance")
		void shouldShareOneInstancePerLanguage() {
			final FulltextAnalyzerRegistry registry = createRegistry();
			// analysis is a property of a language, so the country part must not fragment the cache - nor pay
			// for a second chain, which means a second stop-word list and a second set of stemmer tables
			assertSame(
				registry.getIndexAnalyzer(ENTITY_TYPE, CZECH_CZ),
				registry.getIndexAnalyzer(ENTITY_TYPE, CZECH)
			);
		}

		@Test
		@DisplayName("Collections resolving to the same name share one instance")
		void shouldShareOneInstanceAcrossCollections() {
			final FulltextAnalyzerRegistry registry = createRegistry();
			// the collection decides WHICH analyzer is used, never which copy of it - ten collections on the
			// Czech default must not build ten identical chains
			assertSame(
				registry.getIndexAnalyzer(ENTITY_TYPE, CZECH_CZ),
				registry.getIndexAnalyzer(OTHER_ENTITY_TYPE, CZECH_CZ)
			);
		}

		@Test
		@DisplayName("Unknown language falls back to the generic analyzer instead of failing")
		void shouldFallBackToGenericAnalyzerForUnknownLanguage() {
			final FulltextAnalyzerRegistry registry = createRegistry();
			final FulltextAnalyzer analyzer = registry.getIndexAnalyzer(ENTITY_TYPE, new Locale("fi"));
			// neither an exception nor a silent skip - an explicitly named fallback analyzer that an index can
			// record and that documentation can describe
			assertEquals(BuiltInAnalyzers.GENERIC_ANALYZER_NAME, analyzer.getAnalyzerName());
			assertIterableEquals(List.of("kirjat"), terms(analyzer, "Kirjat"));
		}

		@Test
		@DisplayName("Both sides yield working analyzers")
		void shouldServeBothSides() {
			final FulltextAnalyzerRegistry registry = createRegistry();
			final FulltextAnalyzer index = registry.getIndexAnalyzer(ENTITY_TYPE, ENGLISH);
			final FulltextAnalyzer search = registry.getSearchAnalyzer(ENTITY_TYPE, ENGLISH);

			// the index chain strips markup and the search chain does not, so they are two chains; on markup-free
			// text both produce identical terms
			assertNotSame(index, search);
			final List<String> expected = List.of("english", "book");
			assertIterableEquals(expected, terms(index, "English Books"));
			assertIterableEquals(expected, terms(search, "English Books"));
		}

		@Test
		@DisplayName("Czech, Slovak, Polish and Romanian defaults are asymmetric between the sides")
		void shouldResolveAsymmetricBuiltInAssignmentForFoldingLanguages() {
			final FulltextAnalyzerRegistry registry = createRegistry();
			assertAsymmetric(
				registry, CZECH_CZ,
				BuiltInAnalyzers.CZECH_ANALYZER_NAME, BuiltInAnalyzers.CZECH_SEARCH_ANALYZER_NAME
			);
			assertAsymmetric(
				registry, SLOVAK,
				BuiltInAnalyzers.SLOVAK_ANALYZER_NAME, BuiltInAnalyzers.SLOVAK_SEARCH_ANALYZER_NAME
			);
			assertAsymmetric(
				registry, POLISH,
				BuiltInAnalyzers.POLISH_ANALYZER_NAME, BuiltInAnalyzers.POLISH_SEARCH_ANALYZER_NAME
			);
			assertAsymmetric(
				registry, ROMANIAN,
				BuiltInAnalyzers.ROMANIAN_ANALYZER_NAME, BuiltInAnalyzers.ROMANIAN_SEARCH_ANALYZER_NAME
			);
		}

		@Test
		@DisplayName("English, German and the generic fallback pair a stripping index chain with a search chain")
		void shouldResolveIndexAndSearchPairForTheRemainingLanguages() {
			final FulltextAnalyzerRegistry registry = createRegistry();
			assertAsymmetric(
				registry, ENGLISH,
				BuiltInAnalyzers.ENGLISH_ANALYZER_NAME, BuiltInAnalyzers.ENGLISH_SEARCH_ANALYZER_NAME
			);
			assertAsymmetric(
				registry, GERMAN,
				BuiltInAnalyzers.GERMAN_ANALYZER_NAME, BuiltInAnalyzers.GERMAN_SEARCH_ANALYZER_NAME
			);
			assertAsymmetric(
				registry, new Locale("fi"),
				BuiltInAnalyzers.GENERIC_ANALYZER_NAME, BuiltInAnalyzers.GENERIC_SEARCH_ANALYZER_NAME
			);
		}

	}

	@Nested
	@DisplayName("Lookup by name")
	class LookupByName {

		@Test
		@DisplayName("A name resolves to the instance the language lookup shares")
		void shouldShareTheInstanceWithTheAssignmentLookup() {
			final FulltextAnalyzerRegistry registry = createRegistry();
			assertSame(
				registry.getIndexAnalyzer(ENTITY_TYPE, CZECH),
				registry.getIndexAnalyzerByName(BuiltInAnalyzers.CZECH_ANALYZER_NAME)
			);
		}

		@Test
		@DisplayName("An unknown name is refused")
		void shouldRefuseAnUnknownName() {
			final FulltextAnalyzerRegistry registry = createRegistry();
			assertThrows(EvitaInvalidUsageException.class, () -> registry.getIndexAnalyzerByName("no-such-chain"));
		}

		@Test
		@DisplayName("A closed registry is refused")
		void shouldRefuseAClosedRegistry() {
			final FulltextAnalyzerRegistry registry = createRegistry();
			registry.close();
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> registry.getIndexAnalyzerByName(BuiltInAnalyzers.CZECH_ANALYZER_NAME)
			);
		}

	}

	@Nested
	@DisplayName("Analyzer assignment")
	class Assignments {

		@Test
		@DisplayName("An empty name is rejected on either side")
		void shouldRejectBlankNames() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new AnalyzerAssignment(" ", "a")
			);
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new AnalyzerAssignment("a", " ")
			);
		}

	}

	@Nested
	@DisplayName("Lifecycle")
	class Lifecycle {

		@Test
		@DisplayName("A closed registry refuses every further use")
		void shouldFailAfterClose() {
			final FulltextAnalyzerRegistry registry = createRegistry();
			registry.getIndexAnalyzer(ENTITY_TYPE, CZECH_CZ);
			registry.close();

			// handing out an analyzer whose stream components were already released would produce results that
			// look merely empty, so the failure has to be explicit
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> registry.getIndexAnalyzer(ENTITY_TYPE, CZECH_CZ)
			);
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> registry.getSearchAnalyzer(ENTITY_TYPE, CZECH_CZ)
			);
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> registry.getIndexAnalyzerByName(BuiltInAnalyzers.CZECH_ANALYZER_NAME)
			);
		}

		@Test
		@DisplayName("Closing twice is harmless")
		void shouldTolerateRepeatedClose() {
			final FulltextAnalyzerRegistry registry = createRegistry();
			registry.getIndexAnalyzer(ENTITY_TYPE, CZECH_CZ);
			registry.close();
			assertDoesNotThrow(registry::close);
		}

		@Test
		@DisplayName("An analyzer built while the registry was closing is released, not leaked")
		void shouldNotLeakAnalyzerBuiltWhileClosing() throws Exception {
			// the race: close() walks the instance map and finds nothing, because the factory below has not
			// returned yet. The entry lands afterwards, so unless the lookup re-checks the flag and takes its own
			// instance back out, that chain's stream components are held for the lifetime of the process
			final CountDownLatch insideFactory = new CountDownLatch(1);
			final CountDownLatch releaseFactory = new CountDownLatch(1);
			final CountDownLatch closeReturned = new CountDownLatch(1);
			final CloseRecordingAnalyzer built = new CloseRecordingAnalyzer();

			final FulltextAnalyzerRegistry registry = createRegistryWithSlowAnalyzer(
				() -> {
					insideFactory.countDown();
					awaitUninterruptibly(releaseFactory);
					return built;
				}
			);

			final AtomicReference<Throwable> lookupFailure = new AtomicReference<>();
			final Thread lookup = new Thread(
				() -> {
					try {
						registry.getIndexAnalyzerByName(SLOW_ANALYZER_NAME);
					} catch (Throwable t) {
						lookupFailure.set(t);
					}
				},
				"analyzer-lookup"
			);
			lookup.start();

			// positive wait - generous, it returns the instant the factory is entered
			assertTrue(insideFactory.await(30, TimeUnit.SECONDS), "The factory was never entered.");

			// close() runs on its own thread so that the test still releases the factory even if close() ends up
			// waiting on the half-built entry - either ordering is a pass, a hang is not
			final Thread closer = new Thread(
				() -> {
					registry.close();
					closeReturned.countDown();
				},
				"analyzer-registry-closer"
			);
			closer.start();
			// negative wait: close() is expected to get through an empty map without the factory, but nothing
			// breaks if it does not - a loaded machine can only make this window pass unused
			closeReturned.await(250, TimeUnit.MILLISECONDS);

			releaseFactory.countDown();
			lookup.join(TimeUnit.SECONDS.toMillis(30));
			closer.join(TimeUnit.SECONDS.toMillis(30));
			assertFalse(lookup.isAlive(), "The lookup thread did not finish.");
			assertFalse(closer.isAlive(), "close() did not return.");

			// the late lookup must fail like any other post-close call ...
			assertInstanceOf(EvitaInvalidUsageException.class, lookupFailure.get());
			// ... and the chain must have been released exactly once, by whichever side removed it from the map
			assertEquals(1, built.closeCount(), "The analyzer built during close() was not released exactly once.");
		}

		@Test
		@DisplayName("An analyzer published before close() is released by close() exactly once")
		void shouldReleaseAnAnalyzerPublishedBeforeCloseOnce() {
			final CloseRecordingAnalyzer built = new CloseRecordingAnalyzer();
			final FulltextAnalyzerRegistry registry = createRegistryWithSlowAnalyzer(() -> built);

			// the lookup completes before close() runs, so close() owns the entry. The interleaving in between - the
			// lookup has published its entry, close() removes and releases it before the lookup re-checks the flag -
			// has no deterministic seam; the conditional removal in `getAnalyzerInstance` covers it untested
			registry.getIndexAnalyzerByName(SLOW_ANALYZER_NAME);
			registry.close();

			assertEquals(1, built.closeCount(), "close() must release each instance exactly once.");
		}

	}

	/**
	 * Waits for `latch` without letting an interrupt end the wait, so that a test factory blocks until the test
	 * releases it rather than until the JVM decides otherwise.
	 *
	 * @param latch latch to await
	 */
	private static void awaitUninterruptibly(@Nonnull CountDownLatch latch) {
		boolean interrupted = false;
		try {
			while (true) {
				try {
					latch.await();
					return;
				} catch (InterruptedException e) {
					interrupted = true;
				}
			}
		} finally {
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * A minimal Lucene analyzer that counts how often it was closed, so that a test can tell "released exactly
	 * once" apart from both "leaked" and "closed twice".
	 */
	private static final class CloseRecordingAnalyzer extends Analyzer {

		/**
		 * Number of {@link #close()} calls this instance has seen.
		 */
		private final AtomicInteger closeCount = new AtomicInteger();

		@Override
		protected TokenStreamComponents createComponents(String fieldName) {
			final Tokenizer source = new StandardTokenizer();
			return new TokenStreamComponents(source, source);
		}

		@Override
		public void close() {
			this.closeCount.incrementAndGet();
			super.close();
		}

		/**
		 * Returns how often this analyzer was closed.
		 *
		 * @return number of close calls
		 */
		int closeCount() {
			return this.closeCount.get();
		}

	}


	/**
	 * Asserts the built-in default of `locale` names `indexName` for the stored values and `searchName` for the
	 * query text.
	 *
	 * @param registry   registry under test
	 * @param locale     locale whose built-in default is checked
	 * @param indexName  expected index-side analyzer name
	 * @param searchName expected query-side analyzer name
	 */
	private static void assertAsymmetric(
		@Nonnull FulltextAnalyzerRegistry registry,
		@Nonnull Locale locale,
		@Nonnull String indexName,
		@Nonnull String searchName
	) {
		assertEquals(indexName, registry.getIndexAnalyzer(ENTITY_TYPE, locale).getAnalyzerName());
		assertEquals(searchName, registry.getSearchAnalyzer(ENTITY_TYPE, locale).getAnalyzerName());
	}

}
