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
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStepParameter;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps;
import io.evitadb.api.requestResponse.schema.analysis.FulltextResourceContent;
import io.evitadb.api.requestResponse.schema.analysis.FulltextResourceReference;
import io.evitadb.api.requestResponse.schema.analysis.NormalizingAnalysisStep;
import io.evitadb.api.requestResponse.schema.analysis.SplittingAnalysisStep;
import io.evitadb.api.requestResponse.schema.analysis.TextAnalysisStep;
import io.evitadb.api.requestResponse.schema.analysis.TokenizerAnalysisStep;
import io.evitadb.api.requestResponse.schema.analysis.WordsAnalysisStep;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.index.analysis.AsciiFoldingAnalysisStepFactory;
import io.evitadb.index.analysis.LowercaseAnalysisStepFactory;
import io.evitadb.index.analysis.RemoveCharactersAnalysisStepFactory;
import io.evitadb.spi.analysis.FulltextAnalysisContext;
import io.evitadb.spi.analysis.FulltextAnalysisSide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
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
	/** The factories of `AnalysisSteps` describing a step by its name, for steps the client has no factory for. */
	private static final Set<String> GENERIC_FACTORIES =
		Set.of("textStep", "tokenizerStep", "wordsStep", "normalizingStep");

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
				() -> new AsciiFoldingAnalysisStepFactory().create(unexpected, context),
				() -> new RemoveCharactersAnalysisStepFactory().create(unexpected, context),
				() -> new HtmlStripAnalysisStepFactory().create(unexpected, context),
				() -> new WordNumberSplitAnalysisStepFactory().create(unexpected, context),
				() -> new SlovakStemmingAnalysisStepFactory().create(unexpected, context),
				() -> new PolishStemmingAnalysisStepFactory().create(unexpected, context),
				() -> new RomanianStemmingAnalysisStepFactory().create(unexpected, context),
				() -> new EnglishStemmingAnalysisStepFactory().create(unexpected, context),
				() -> new GermanStemmingAnalysisStepFactory().create(unexpected, context),
				() -> new StemmingAnalysisStepFactory().create(unexpected, context),
				() -> new RomanianNormalizationAnalysisStepFactory().create(unexpected, context),
				() -> new EnglishPossessiveAnalysisStepFactory().create(unexpected, context),
				() -> new SynonymsAnalysisStepFactory().create(unexpected, context)
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
			assertEquals(Set.of(CHARACTERS), new RemoveCharactersAnalysisStepFactory().getIndexSideParameters());
			assertEquals(Set.of(), new HtmlStripAnalysisStepFactory().getIndexSideParameters());
			assertEquals(Set.of(), new WordNumberSplitAnalysisStepFactory().getIndexSideParameters());
			assertEquals(Set.of(), new RomanianNormalizationAnalysisStepFactory().getIndexSideParameters());
			assertEquals(Set.of(), new EnglishPossessiveAnalysisStepFactory().getIndexSideParameters());
			// synonyms act on the query side only, so an edit of their lists needs no reindexing
			assertEquals(Set.of(), new SynonymsAnalysisStepFactory().getIndexSideParameters());
			for (final AbstractStemmingAnalysisStepFactory stemming : List.of(
				new SlovakStemmingAnalysisStepFactory(), new PolishStemmingAnalysisStepFactory(),
				new RomanianStemmingAnalysisStepFactory(), new EnglishStemmingAnalysisStepFactory(),
				new GermanStemmingAnalysisStepFactory(), new StemmingAnalysisStepFactory()
			)) {
				assertEquals(Set.of(PROTECTED_WORDS), stemming.getIndexSideParameters(), stemming.getName());
			}
		}

		/** A stemming step refuses a `protected-words` parameter that names no list, whichever language it stems. */
		@Test
		@DisplayName("should refuse protected words naming no list")
		void shouldRefuseProtectedWordsNamingNoList() {
			final Map<String, Serializable> empty = Map.of(PROTECTED_WORDS, new String[0]);

			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class, () -> new PolishStemmingAnalysisStepFactory().validate(empty)
			);

			assertTrue(exception.getMessage().contains("`polish-stemming`"), exception.getMessage());
		}
	}

	/** Every step a client can describe is built by the server in each place the client lets it be put. */
	@Nested
	@DisplayName("Placement")
	class Placement {

		/**
		 * Calls every factory of `AnalysisSteps` returning a step and compiles the step in each phase its type
		 * admits, on both sides and for languages with and without a stemmer; a normalizing step is also compiled as
		 * a normalization. A client step the server cannot build fails here before any schema names it.
		 */
		@Test
		@DisplayName("should build every client step in each place its type admits")
		void shouldBuildEveryClientStepInEachPlaceItsTypeAdmits() throws ReflectiveOperationException {
			final AnalysisStepRegistry registry = new AnalysisStepRegistry();
			final FulltextChainCompiler placementCompiler = new FulltextChainCompiler(registry);
			final Map<String, FulltextResourceContent> resources = Map.of(
				"list", wordList("a"), "groups", synonymList(synonymGroup("tv", "television"))
			);
			int checkedPlaces = 0;
			for (final Method method : AnalysisSteps.class.getMethods()) {
				if (!Modifier.isStatic(method.getModifiers()) || !AnalysisStep.class.isAssignableFrom(
					method.getReturnType()) || GENERIC_FACTORIES.contains(method.getName())) {
					continue;
				}
				final AnalysisStep step = (AnalysisStep) method.invoke(null, arguments(method));
				for (final Locale locale : List.of(Locale.forLanguageTag("cs"), Locale.ENGLISH, Locale.FRENCH)) {
					for (final FulltextAnalysisSide side : FulltextAnalysisSide.values()) {
						final FulltextAnalysisContext context = new FulltextAnalysisContext(locale, resources);
						final List<List<AnalysisStep>> places = new ArrayList<>(4);
						if (step instanceof TextAnalysisStep) {
							places.add(List.of(step));
						}
						if (step instanceof TokenizerAnalysisStep) {
							places.add(List.of());
						}
						for (final List<AnalysisStep> text : places) {
							assertBuilds(placementCompiler, text, splittingFor(step), List.of(), side, context, method);
							checkedPlaces++;
						}
						if (step instanceof SplittingAnalysisStep) {
							assertBuilds(
								placementCompiler, List.of(), List.of(standardTokenizer(), step), List.of(), side,
								context, method
							);
							checkedPlaces++;
						}
						if (step instanceof WordsAnalysisStep) {
							assertBuilds(placementCompiler, List.of(), SPLITTING, List.of(step), side, context, method);
							checkedPlaces++;
						}
					}
				}
				if (step instanceof NormalizingAnalysisStep) {
					assertEquals(
						"x", registry.compile(List.of(step), null).apply("x").strip(), method.getName()
					);
					checkedPlaces++;
				}
			}
			// 17 fulltext places (2 text, 1 tokenizer, 1 splitting, 13 words) x 3 locales x 2 sides, 4 normalizations
			assertTrue(checkedPlaces >= 106, "Only " + checkedPlaces + " places were checked.");
		}

		/**
		 * Returns the word splitting for a step: the step itself when it is a tokenizer, the standard one otherwise.
		 *
		 * @param step the step being placed
		 * @return the word splitting
		 */
		@Nonnull
		private List<AnalysisStep> splittingFor(@Nonnull AnalysisStep step) {
			return step instanceof TokenizerAnalysisStep ? List.of(step) : SPLITTING;
		}

		/**
		 * Compiles a chain and runs a text through it.
		 *
		 * @param compiler  the compiler
		 * @param text      the text phase
		 * @param splitting the word splitting
		 * @param words     the words phase
		 * @param side      the side to compile
		 * @param context   the locale and resources
		 * @param factory   the client factory that described the step, for the message
		 */
		private void assertBuilds(
			@Nonnull FulltextChainCompiler compiler,
			@Nonnull List<AnalysisStep> text,
			@Nonnull List<AnalysisStep> splitting,
			@Nonnull List<AnalysisStep> words,
			@Nonnull FulltextAnalysisSide side,
			@Nonnull FulltextAnalysisContext context,
			@Nonnull Method factory
		) {
			try (
				final FulltextAnalyzer chain = new FulltextAnalyzer(
					"placement", compiler.compile(text, splitting, words, side, context)
				)
			) {
				assertTrue(
					!chain.getTerms("Television iPhone15 a <b>tv</b>").isEmpty(),
					factory.getName() + " in " + context.locale() + " on " + side
				);
			}
		}

		/**
		 * Creates arguments for a factory of `AnalysisSteps`: a resource named after the kind of resource the step
		 * reads, a character to remove, no optional parameters.
		 *
		 * @param method the factory
		 * @return its arguments
		 */
		@Nonnull
		private Object[] arguments(@Nonnull Method method) {
			final Class<?>[] types = method.getParameterTypes();
			final Object[] arguments = new Object[types.length];
			for (int i = 0; i < types.length; i++) {
				if (types[i] == String.class) {
					arguments[i] = "-";
				} else if (types[i] == FulltextResourceReference[].class) {
					arguments[i] = new FulltextResourceReference[]{
						resource(SYNONYMS.equals(method.getName()) ? "groups" : "list")
					};
				} else if (types[i] == AnalysisStepParameter[].class) {
					arguments[i] = new AnalysisStepParameter[0];
				} else {
					throw new IllegalStateException("No argument for " + types[i] + " of " + method.getName());
				}
			}
			return arguments;
		}
	}

	/** The `html-strip` step strips markup from stored values and leaves queries as typed. */
	@Nested
	@DisplayName("HTML strip")
	class HtmlStrip {

		/** Tags never become words, and a word's offsets point at it in the stored, markup-bearing value. */
		@Test
		@DisplayName("should strip markup on the index side, offsets pointing into the original")
		void shouldStripMarkupOnIndexSideOffsetsPointingIntoOriginal() {
			final String value = "<p>Pánské <b>boty</b> &amp; obuv</p>";
			try (
				final FulltextAnalyzer chain = chain(
					List.of(htmlStrip()), List.of(lowercase()), Locale.ROOT, Map.of(), FulltextAnalysisSide.INDEX
				)
			) {
				final List<AnalyzedTerm> terms = chain.getTerms(value);

				assertEquals(List.of("pánské", "boty", "obuv"), terms.stream().map(AnalyzedTerm::term).toList());
				// a word directly followed by a tag ends after that tag, so only its start is exact
				assertTrue(value.substring(terms.get(1).startOffset()).startsWith("boty</b>"));
			}
		}

		/** A query is what a user typed: the step is left out of the query chain. */
		@Test
		@DisplayName("should leave the query text as typed")
		void shouldLeaveQueryTextAsTyped() {
			try (
				final FulltextAnalyzer chain = chain(
					List.of(htmlStrip()), List.of(lowercase()), Locale.ROOT, Map.of(), FulltextAnalysisSide.QUERY
				)
			) {
				assertEquals(List.of("b", "boty", "b"), termTexts(chain, "<b>boty</b>"));
			}
		}
	}

	/** The `remove-characters` step in the text phase removes code points before the text is split into words. */
	@Nested
	@DisplayName("Remove characters")
	class RemoveCharacters {

		/**
		 * A removed hyphen joins the parts of a word, a removed supplementary code point disappears whole, and the
		 * offsets still address the original text.
		 */
		@Test
		@DisplayName("should remove the characters before splitting, on both sides")
		void shouldRemoveCharactersBeforeSplittingOnBothSides() {
			final String text = "USB-C a \uD835\uDD38B";
			for (final FulltextAnalysisSide side : FulltextAnalysisSide.values()) {
				try (
					final FulltextAnalyzer chain = chain(
						List.of(removeCharacters("-\uD835\uDD38")), List.of(lowercase()), Locale.ROOT, Map.of(), side
					)
				) {
					final List<AnalyzedTerm> terms = chain.getTerms(text);

					assertEquals(
						List.of("usbc", "a", "b"), terms.stream().map(AnalyzedTerm::term).toList(), side.name()
					);
					assertEquals("USB-C", text.substring(terms.get(0).startOffset(), terms.get(0).endOffset()));
				}
			}
		}
	}

	/** The `word-number-split` step splits a word where a letter meets a digit and keeps the word itself. */
	@Nested
	@DisplayName("Word number split")
	class WordNumberSplit {

		/** The original comes first, its parts follow at consecutive positions, each pointing at itself. */
		@Test
		@DisplayName("should emit the original and its parts")
		void shouldEmitOriginalAndItsParts() {
			try (
				final FulltextAnalyzer chain = new FulltextAnalyzer(
					"test",
					FulltextStepFactoriesTest.this.compiler.compile(
						List.of(), List.of(standardTokenizer(), wordNumberSplit()), List.of(lowercase()),
						FulltextAnalysisSide.INDEX, new FulltextAnalysisContext(Locale.ROOT, Map.of())
					)
				)
			) {
				assertEquals(
					List.of(
						new AnalyzedTerm("iphone15", "iPhone15", 0, 8, 1),
						new AnalyzedTerm("iphone", "iPhone", 0, 6, 0),
						new AnalyzedTerm("15", "15", 6, 8, 1)
					),
					chain.getTerms("iPhone15")
				);
			}
		}
	}

	/** The stemming steps stem the words of their language and leave the protected ones alone. */
	@Nested
	@DisplayName("Stemming")
	class Stemming {

		/**
		 * The folding stemmers fold a protected word but do not stem it, on both sides; the same word unprotected is
		 * stemmed on the index side.
		 */
		@Test
		@DisplayName("should fold but not stem a protected word in every folding language")
		void shouldFoldButNotStemProtectedWordInEveryFoldingLanguage() {
			final Map<AnalysisStep, String[]> wordsByStemming = Map.of(
				slovakStemming(protectedWords(resource("p"))), new String[]{"sk", "Mestami", "mestami"},
				polishStemming(protectedWords(resource("p"))), new String[]{"pl", "Kotami", "kotami"},
				romanianStemming(protectedWords(resource("p"))), new String[]{"ro", "Cărțile", "cartile"}
			);
			for (final Map.Entry<AnalysisStep, String[]> entry : wordsByStemming.entrySet()) {
				final Locale locale = Locale.forLanguageTag(entry.getValue()[0]);
				final String word = entry.getValue()[1];
				final String folded = entry.getValue()[2];
				final Map<String, FulltextResourceContent> resources = Map.of("p", wordList(word.toLowerCase(locale)));
				for (final FulltextAnalysisSide side : FulltextAnalysisSide.values()) {
					try (
						final FulltextAnalyzer chain = chain(
							List.of(lowercase(), entry.getKey()), locale, resources, side
						)
					) {
						assertEquals(List.of(folded), termTexts(chain, word), entry.getKey() + " " + side);
					}
				}
				final AnalysisStep unprotected = AnalysisStep.of(entry.getKey().name(), Map.of());
				try (
					final FulltextAnalyzer chain = chain(
						List.of(lowercase(), unprotected), locale, Map.of(), FulltextAnalysisSide.INDEX
					)
				) {
					assertTrue(!termTexts(chain, word).contains(folded), unprotected.name());
				}
			}
		}

		/** English stems the same way on both sides and leaves a protected word as it is. */
		@Test
		@DisplayName("should stem English alike on both sides, protected words excepted")
		void shouldStemEnglishAlikeOnBothSidesProtectedWordsExcepted() {
			final Map<String, FulltextResourceContent> resources = Map.of("p", wordList("running"));
			for (final FulltextAnalysisSide side : FulltextAnalysisSide.values()) {
				try (
					final FulltextAnalyzer english = chain(
						List.of(lowercase(), englishStemming(protectedWords(resource("p")))), Locale.ENGLISH,
						resources, side
					)
				) {
					assertEquals(List.of("running", "jump"), termTexts(english, "Running jumps"), side.name());
				}
			}
		}

		/** German normalizes umlauts before stemming, the same way on both sides. */
		@Test
		@DisplayName("should normalize German umlauts before stemming on both sides")
		void shouldNormalizeGermanUmlautsBeforeStemmingOnBothSides() {
			try (
				final FulltextAnalyzer index = chain(
					List.of(lowercase(), germanStemming()), Locale.GERMAN, Map.of(), FulltextAnalysisSide.INDEX
				);
				final FulltextAnalyzer query = chain(
					List.of(lowercase(), germanStemming()), Locale.GERMAN, Map.of(), FulltextAnalysisSide.QUERY
				)
			) {
				assertEquals(termTexts(index, "Häuser schön"), termTexts(query, "Häuser schön"));
				assertEquals(termTexts(index, "Häuser schön"), termTexts(index, "Hauser schon"));
			}
		}

		/** `stemming()` runs the stemming step of the chain's language, and nothing in a language without one. */
		@Test
		@DisplayName("should run the stemming step of the chain's language")
		void shouldRunStemmingStepOfChainsLanguage() {
			final String text = "Pánské kožené boty running jumps";
			final Map<Locale, AnalysisStep> stemmingByLanguage = Map.of(
				Locale.forLanguageTag("cs"), czechStemming(),
				Locale.ENGLISH, englishStemming(),
				Locale.FRENCH, lowercase()
			);
			for (final Map.Entry<Locale, AnalysisStep> entry : stemmingByLanguage.entrySet()) {
				for (final FulltextAnalysisSide side : FulltextAnalysisSide.values()) {
					try (
						final FulltextAnalyzer dispatched = chain(
							List.of(lowercase(), stemming()), entry.getKey(), Map.of(), side
						);
						final FulltextAnalyzer direct = chain(
							List.of(lowercase(), entry.getValue()), entry.getKey(), Map.of(), side
						)
					) {
						assertEquals(termTexts(direct, text), termTexts(dispatched, text), entry.getKey() + " " + side);
					}
				}
			}
		}

		/** The protected words of `stemming()` reach the step it runs. */
		@Test
		@DisplayName("should pass protected words on to the step it runs")
		void shouldPassProtectedWordsOnToStepItRuns() {
			try (
				final FulltextAnalyzer chain = chain(
					List.of(lowercase(), stemming(protectedWords(resource("p")))), Locale.ENGLISH,
					Map.of("p", wordList("running")), FulltextAnalysisSide.INDEX
				)
			) {
				assertEquals(List.of("running", "jump"), termTexts(chain, "running jumps"));
			}
		}
	}

	/** The `synonyms` step expands a query word to the other words of its group. */
	@Nested
	@DisplayName("Synonyms")
	class Synonyms {
		/** Two groups in one list. */
		private final Map<String, FulltextResourceContent> resources = Map.of(
			"groups", synonymList(synonymGroup("mobil", "telefon"), synonymGroup("tv", "televize"))
		);

		/** Every word of the group stands at the query word's position; the following word moves on. */
		@Test
		@DisplayName("should expand a query word to its group at one position")
		void shouldExpandQueryWordToItsGroupAtOnePosition() {
			try (
				final FulltextAnalyzer chain = chain(
					List.of(lowercase(), synonyms(resource("groups"))), Locale.ROOT, this.resources,
					FulltextAnalysisSide.QUERY
				)
			) {
				final List<AnalyzedTerm> terms = chain.getTerms("Mobil levně");

				assertEquals(
					Set.of("mobil", "telefon"),
					Set.of(terms.get(0).term(), terms.get(1).term())
				);
				assertEquals(List.of(1, 0, 1), terms.stream().map(AnalyzedTerm::positionIncrement).toList());
				assertEquals("levně", terms.get(2).term());
			}
		}

		/** The index side stores what the text holds; the step is left out of its chain. */
		@Test
		@DisplayName("should leave the index side alone")
		void shouldLeaveIndexSideAlone() {
			try (
				final FulltextAnalyzer chain = chain(
					List.of(lowercase(), synonyms(resource("groups"))), Locale.ROOT, this.resources,
					FulltextAnalysisSide.INDEX
				)
			) {
				assertEquals(List.of("mobil", "levně"), termTexts(chain, "Mobil levně"));
			}
		}

		/** A stemmer after the step stems the synonyms as it stems the query word. */
		@Test
		@DisplayName("should let a following stemmer stem the synonyms")
		void shouldLetFollowingStemmerStemSynonyms() {
			final Locale czech = Locale.forLanguageTag("cs");
			try (
				final FulltextAnalyzer expanded = chain(
					List.of(lowercase(), synonyms(resource("groups")), czechStemming()), czech, this.resources,
					FulltextAnalysisSide.QUERY
				);
				final FulltextAnalyzer plain = chain(
					List.of(lowercase(), czechStemming()), czech, Map.of(), FulltextAnalysisSide.QUERY
				)
			) {
				assertTrue(termTexts(expanded, "televize").containsAll(termTexts(plain, "tv")));
				assertTrue(termTexts(expanded, "televize").containsAll(termTexts(plain, "televize")));
			}
		}

		/** A group word with a space would never meet a single query word, so it is refused. */
		@Test
		@DisplayName("should refuse a synonym of more than one word")
		void shouldRefuseSynonymOfMoreThanOneWord() {
			final Map<String, FulltextResourceContent> multiWord = Map.of(
				"groups", synonymList(synonymGroup("tv", "smart tv"))
			);

			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> chain(List.of(synonyms(resource("groups"))), Locale.ROOT, multiWord, FulltextAnalysisSide.QUERY)
			);

			assertTrue(exception.getMessage().contains("`smart tv`"), exception.getMessage());
		}

		/** A word list is a resource of another kind, never a list of synonym groups. */
		@Test
		@DisplayName("should refuse a resource that is not a synonym list")
		void shouldRefuseResourceThatIsNotSynonymList() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> chain(
					List.of(synonyms(resource("words"))), Locale.ROOT, Map.of("words", wordList("tv")),
					FulltextAnalysisSide.QUERY
				)
			);

			assertTrue(exception.getMessage().contains("`words` is not a synonym list"), exception.getMessage());
		}
	}

	/** The parameterless language steps of the words phase. */
	@Nested
	@DisplayName("Language normalization")
	class LanguageNormalization {

		/** Comma below becomes cedilla, the spelling the stop list and the stemmer of the pinned Lucene use. */
		@Test
		@DisplayName("should rewrite Romanian comma below to cedilla")
		void shouldRewriteRomanianCommaBelowToCedilla() {
			try (
				final FulltextAnalyzer chain = chain(
					List.of(lowercase(), romanianNormalization()), Locale.forLanguageTag("ro"), Map.of(),
					FulltextAnalysisSide.QUERY
				)
			) {
				assertEquals(List.of("\u015Fi", "\u0163ar\u0103"), termTexts(chain, "\u0219i \u021Bar\u0103"));
			}
		}

		/** The possessive `'s` goes, so `John's` meets `John`. */
		@Test
		@DisplayName("should remove the English possessive")
		void shouldRemoveEnglishPossessive() {
			try (
				final FulltextAnalyzer chain = chain(
					List.of(englishPossessive(), lowercase()), Locale.ENGLISH, Map.of(), FulltextAnalysisSide.INDEX
				)
			) {
				assertEquals(List.of("john", "book"), termTexts(chain, "John's book"));
			}
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
		return chain(List.of(), words, locale, resources, side);
	}

	/**
	 * Compiles a chain of the given text phase, the standard tokenizer and the given words phase and wraps it the way
	 * the registry wraps today's chains.
	 *
	 * @param text      the text phase
	 * @param words     the words phase
	 * @param locale    the locale the chain is built for
	 * @param resources the resources the steps may name
	 * @param side      the side to compile
	 * @return the wrapped chain, to be closed by the caller
	 */
	@Nonnull
	private FulltextAnalyzer chain(
		@Nonnull List<AnalysisStep> text,
		@Nonnull List<AnalysisStep> words,
		@Nonnull Locale locale,
		@Nonnull Map<String, FulltextResourceContent> resources,
		@Nonnull FulltextAnalysisSide side
	) {
		return new FulltextAnalyzer(
			"test",
			this.compiler.compile(text, SPLITTING, words, side, new FulltextAnalysisContext(locale, resources))
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
