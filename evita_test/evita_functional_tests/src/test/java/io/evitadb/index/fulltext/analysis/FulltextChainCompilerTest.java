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
import io.evitadb.api.requestResponse.schema.analysis.WordList;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.spi.analysis.AnalysisStepFactory;
import io.evitadb.spi.analysis.AnalysisStepParameterDescriptor;
import io.evitadb.spi.analysis.FulltextAnalysisContext;
import io.evitadb.spi.analysis.FulltextAnalysisSide;
import io.evitadb.spi.analysis.FulltextTextAnalysisOperation;
import io.evitadb.spi.analysis.FulltextTextAnalysisStepFactory;
import io.evitadb.spi.analysis.FulltextWordsAnalysisOperation;
import io.evitadb.spi.analysis.FulltextWordsAnalysisStepFactory;
import org.apache.lucene.analysis.FilteringTokenFilter;
import org.apache.lucene.analysis.cz.CzechAnalyzer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the step-list chains {@link FulltextChainCompiler} builds: the Czech line of the built-in `text` analyzer
 * must produce exactly the terms of today's `czech` / `czech-search` chains (markup stripping aside, which `text` does
 * not do), and the compiler must refuse what cannot form a chain.
 */
@DisplayName("Fulltext chain compiler")
@Tag(ENGINE)
@Tag(FULLTEXT)
class FulltextChainCompilerTest {
	/** The Czech locale every chain here is built for. */
	private static final Locale CZECH = Locale.forLanguageTag("cs");
	/** The word splitting of a language the catalog does not declare. */
	private static final List<AnalysisStep> SPLITTING = List.of(standardTokenizer());
	/** The words phase of the Czech line of the built-in `text` analyzer. */
	private static final List<AnalysisStep> CZECH_WORDS =
		List.of(lowercase(), stopWords(czechStopWords()), czechStemming());
	/** Texts beyond the vocabulary: stop words, upper case, digits, punctuation, mixed tokens. */
	private static final List<String> SENTENCES = List.of(
		"Pánské kožené boty a dámská obuv, velikost 42 - výprodej!",
		"To je ten nejlepší stůl, který jsme kdy měli, a není drahý.",
		"ŽLUTÝ Kabát pro DĚTI; rajčata v zahradních truhlících",
		"iPhone15 a USB-C kabel, 3,5 mm jack, 2x baterie",
		"Kuchyňští pomocníci a angličtí pánští krejčí šijí stříbrným nití"
	);

	/** The registry discovering the installed step providers. */
	private final AnalysisStepRegistry stepRegistry = new AnalysisStepRegistry();
	/** The compiler under test. */
	private final FulltextChainCompiler compiler = new FulltextChainCompiler(this.stepRegistry);
	/** The registry of today's built-in chains, the reference. */
	private FulltextAnalyzerRegistry analyzerRegistry;

	/** Opens the reference registry. */
	@BeforeEach
	void setUp() {
		this.analyzerRegistry = new FulltextAnalyzerRegistry();
	}

	/** Closes the reference registry and the chains it built. */
	@AfterEach
	void tearDown() {
		this.analyzerRegistry.close();
	}

	/** The Czech line of `text` against today's Czech chains. */
	@Nested
	@DisplayName("Czech golden output")
	class CzechGoldenOutput {

		/** The index side stems the accented word, then folds it - as `czech` does. */
		@Test
		@DisplayName("should produce today's index terms")
		void shouldProduceTodaysIndexTerms() {
			try (
				final FulltextAnalyzer compiled = compile(CZECH_WORDS, FulltextAnalysisSide.INDEX, czechResources())
			) {
				final FulltextAnalyzer reference =
					FulltextChainCompilerTest.this.analyzerRegistry.getIndexAnalyzerByName(
						BuiltInAnalyzers.CZECH_ANALYZER_NAME
					);
				for (final String text : samples()) {
					assertEquals(terms(reference, text), terms(compiled, text), text);
				}
			}
		}

		/** The query side folds the word, then expands it to its variant stems - as `czech-search` does. */
		@Test
		@DisplayName("should produce today's query terms")
		void shouldProduceTodaysQueryTerms() {
			try (
				final FulltextAnalyzer compiled = compile(CZECH_WORDS, FulltextAnalysisSide.QUERY, czechResources())
			) {
				final FulltextAnalyzer reference =
					FulltextChainCompilerTest.this.analyzerRegistry.getSearchAnalyzer("product", CZECH);
				for (final String text : samples()) {
					assertEquals(terms(reference, text), terms(compiled, text), text);
				}
			}
		}
	}

	/** Protected words stay unstemmed on both sides, but are still folded. */
	@Nested
	@DisplayName("Protected words")
	class ProtectedWords {

		/** The protected word is folded and kept whole; its neighbours are stemmed as usual. */
		@Test
		@DisplayName("should leave a protected word unstemmed on the index side")
		void shouldLeaveProtectedWordUnstemmedOnIndexSide() {
			final List<AnalysisStep> words = List.of(
				lowercase(), czechStemming(protectedWords(resource("brands")))
			);
			try (final FulltextAnalyzer compiled = compile(words, FulltextAnalysisSide.INDEX, brandResources())) {
				final List<String> terms = compiled.getTerms("Černých černým").stream()
					.map(AnalyzedTerm::term).toList();
				assertEquals("cernych", terms.get(0));
				assertNotEquals("cernym", terms.get(1), "an unprotected word is still stemmed");
			}
		}

		/** The protected word is looked up as one folded term, never as its variant stems. */
		@Test
		@DisplayName("should look a protected word up as it is on the query side")
		void shouldLookProtectedWordUpAsItIsOnQuerySide() {
			final List<AnalysisStep> words = List.of(
				lowercase(), czechStemming(protectedWords(resource("brands")))
			);
			try (final FulltextAnalyzer compiled = compile(words, FulltextAnalysisSide.QUERY, brandResources())) {
				assertEquals(List.of("cernych"), compiled.getTerms("černých").stream().map(AnalyzedTerm::term).toList());
			}
		}

		/**
		 * Returns resources holding the word list `brands` with one protected word, lower-cased as the chain compares.
		 *
		 * @return the resources
		 */
		@Nonnull
		private Map<String, FulltextResourceContent> brandResources() {
			return Map.of("brands", new WordList(List.of("černých")));
		}
	}

	/** Definitions that cannot form a chain are refused when compiled. */
	/** A text or words step joins only the chains of the sides it declares. */
	@Nested
	@DisplayName("Sides")
	class Sides {

		/** A query-only step leaves the index chain as it is and acts on the query chain. */
		@Test
		@DisplayName("should build a query-only step into the query chain only")
		void shouldBuildQueryOnlyStepIntoQueryChainOnly() {
			final AnalysisStepRegistry registry = registryWith(new DropAllOnQuerySide());
			final FulltextChainCompiler compiler = new FulltextChainCompiler(registry);
			final List<AnalysisStep> words = List.of(lowercase(), wordsStep(DropAllOnQuerySide.NAME));
			final FulltextAnalysisContext context = new FulltextAnalysisContext(CZECH, Map.of());
			try (
				final FulltextAnalyzer index = new FulltextAnalyzer(
					"index", compiler.compile(List.of(), SPLITTING, words, FulltextAnalysisSide.INDEX, context)
				);
				final FulltextAnalyzer query = new FulltextAnalyzer(
					"query", compiler.compile(List.of(), SPLITTING, words, FulltextAnalysisSide.QUERY, context)
				)
			) {
				assertEquals(List.of("boty"), index.getTerms("Boty").stream().map(AnalyzedTerm::term).toList());
				assertTrue(query.getTerms("Boty").isEmpty());
			}
		}

		/** A step acting on no side would silently do nothing anywhere, so the registry refuses it. */
		@Test
		@DisplayName("should refuse a step acting on no side")
		void shouldRefuseStepActingOnNoSide() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class, () -> registryWith(new NoSide())
			);
			assertTrue(exception.getMessage().contains("acts on no side"), exception.getMessage());
		}

		/** A class implementing two phases says no phase, so the registry refuses it. */
		@Test
		@DisplayName("should refuse a step implementing two phases")
		void shouldRefuseStepImplementingTwoPhases() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class, () -> registryWith(new TwoPhases())
			);
			assertTrue(exception.getMessage().contains("implements 2 fulltext phases"), exception.getMessage());
		}
	}

	@Nested
	@DisplayName("Refusals")
	class Refusals {

		/** A words phase step placed in the word splitting belongs to another phase. */
		@Test
		@DisplayName("should refuse a step of another phase")
		void shouldRefuseStepOfAnotherPhase() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> FulltextChainCompilerTest.this.compiler.compile(
					List.of(), List.of(standardTokenizer(), lowercase()), List.of(), FulltextAnalysisSide.INDEX,
					new FulltextAnalysisContext(CZECH, Map.of())
				)
			);
			assertTrue(exception.getMessage().contains("`lowercase` is a words step"), exception.getMessage());
		}

		/** A words step cannot be the tokenizer either. */
		@Test
		@DisplayName("should refuse a words step as the tokenizer")
		void shouldRefuseWordsStepAsTokenizer() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> FulltextChainCompilerTest.this.compiler.compile(
					List.of(), List.of(lowercase()), List.of(), FulltextAnalysisSide.INDEX,
					new FulltextAnalysisContext(CZECH, Map.of())
				)
			);
			assertTrue(exception.getMessage().contains("cannot serve as the tokenizer"), exception.getMessage());
		}

		/** The tokenizer belongs to the word splitting, never to the words phase. */
		@Test
		@DisplayName("should refuse the tokenizer in the words phase")
		void shouldRefuseTokenizerInWordsPhase() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> FulltextChainCompilerTest.this.compiler.compile(
					List.of(), SPLITTING, List.of(lowercase(), standardTokenizer()), FulltextAnalysisSide.INDEX,
					new FulltextAnalysisContext(CZECH, Map.of())
				)
			);
			assertTrue(exception.getMessage().contains("`standard` is a tokenizer"), exception.getMessage());
		}

		/** Word splitting without its tokenizer has nothing to split the text with. */
		@Test
		@DisplayName("should refuse word splitting without a tokenizer")
		void shouldRefuseSplittingWithoutTokenizer() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> FulltextChainCompilerTest.this.compiler.compile(
					List.of(), List.of(), CZECH_WORDS, FulltextAnalysisSide.INDEX,
					new FulltextAnalysisContext(CZECH, czechResources())
				)
			);
			assertTrue(exception.getMessage().contains("must start with a tokenizer"));
		}

		/** A normalizing-only step is installed but cannot serve a fulltext analyzer. */
		@Test
		@DisplayName("should refuse a normalizing-only step")
		void shouldRefuseNormalizingOnlyStep() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> FulltextChainCompilerTest.this.stepRegistry.resolveFulltext(trim())
			);
			assertTrue(exception.getMessage().contains("`trim` is not usable in a fulltext analyzer"));
		}

		/** A stop-words step naming no word list is refused by the registry's parameter check. */
		@Test
		@DisplayName("should refuse stop words without a list")
		void shouldRefuseStopWordsWithoutList() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> FulltextChainCompilerTest.this.stepRegistry.resolveFulltext(wordsStep(STOP_WORDS))
			);
			assertTrue(exception.getMessage().contains("Missing required parameter `words`"));
		}
	}

	/**
	 * Builds a registry of the installed providers plus one more.
	 *
	 * @param extra the additional provider
	 * @return the registry
	 */
	@Nonnull
	private static AnalysisStepRegistry registryWith(@Nonnull AnalysisStepFactory extra) {
		final List<AnalysisStepFactory> factories = new ArrayList<>(
			ServiceLoader.load(AnalysisStepFactory.class).stream().map(ServiceLoader.Provider::get).toList()
		);
		factories.add(extra);
		return new AnalysisStepRegistry(factories);
	}

	/**
	 * Base of the test providers: a parameterless step at version 1.
	 */
	private abstract static class TestStep implements AnalysisStepFactory {
		@Nonnull
		@Override
		public List<AnalysisStepParameterDescriptor> getParameters() {
			return List.of();
		}

		@Override
		public void validate(@Nonnull Map<String, Serializable> parameters) {
			// parameterless
		}

		@Nonnull
		public Set<String> getIndexSideParameters() {
			return Set.of();
		}

		public int getBehaviourVersion() {
			return 1;
		}
	}

	/**
	 * A words step acting on the query side only, which drops every word - easy to tell from no step at all.
	 */
	private static final class DropAllOnQuerySide extends TestStep implements FulltextWordsAnalysisStepFactory {
		/** The step's name. */
		static final String NAME = "test-drop-all-on-query-side";

		@Nonnull
		@Override
		public String getName() {
			return NAME;
		}

		@Nonnull
		@Override
		public Set<FulltextAnalysisSide> getSides() {
			return EnumSet.of(FulltextAnalysisSide.QUERY);
		}

		@Nonnull
		@Override
		public FulltextWordsAnalysisOperation create(
			@Nonnull Map<String, Serializable> parameters,
			@Nonnull FulltextAnalysisContext context
		) {
			return (words, side) -> new FilteringTokenFilter(words) {
				@Override
				protected boolean accept() {
					return false;
				}
			};
		}
	}

	/**
	 * A words step declaring no side.
	 */
	private static final class NoSide extends TestStep implements FulltextWordsAnalysisStepFactory {
		@Nonnull
		@Override
		public String getName() {
			return "test-no-side";
		}

		@Nonnull
		@Override
		public Set<FulltextAnalysisSide> getSides() {
			return Set.of();
		}

		@Nonnull
		@Override
		public FulltextWordsAnalysisOperation create(
			@Nonnull Map<String, Serializable> parameters,
			@Nonnull FulltextAnalysisContext context
		) {
			return (words, side) -> words;
		}
	}

	/**
	 * A step implementing the text and the words phase at once - possible only through an operation type serving
	 * both.
	 */
	private static final class TwoPhases extends TestStep
		implements FulltextTextAnalysisStepFactory, FulltextWordsAnalysisStepFactory {

		/** An operation serving both phases. */
		interface BothOperations extends FulltextTextAnalysisOperation, FulltextWordsAnalysisOperation {
		}

		@Nonnull
		@Override
		public String getName() {
			return "test-two-phases";
		}

		@Nonnull
		@Override
		public Set<FulltextAnalysisSide> getSides() {
			return EnumSet.allOf(FulltextAnalysisSide.class);
		}

		@Nonnull
		@Override
		public BothOperations create(
			@Nonnull Map<String, Serializable> parameters,
			@Nonnull FulltextAnalysisContext context
		) {
			throw new UnsupportedOperationException("never compiled");
		}
	}

	/**
	 * Compiles a chain over the standard tokenizer and wraps it the way the registry wraps today's chains.
	 *
	 * @param words     the words phase
	 * @param side      the side to compile
	 * @param resources the resources the steps may name
	 * @return the wrapped chain, to be closed by the caller
	 */
	@Nonnull
	private FulltextAnalyzer compile(
		@Nonnull List<AnalysisStep> words,
		@Nonnull FulltextAnalysisSide side,
		@Nonnull Map<String, FulltextResourceContent> resources
	) {
		return new FulltextAnalyzer(
			"text",
			this.compiler.compile(List.of(), SPLITTING, words, side, new FulltextAnalysisContext(CZECH, resources))
		);
	}

	/**
	 * Returns the built-in Czech stop list as the resource `czech-stop-words`.
	 *
	 * @return the resources of the Czech line
	 */
	@Nonnull
	private static Map<String, FulltextResourceContent> czechResources() {
		final List<String> words = new ArrayList<>(CzechAnalyzer.getDefaultStopSet().size());
		for (final Object word : CzechAnalyzer.getDefaultStopSet()) {
			words.add(new String((char[]) word));
		}
		return Map.of(CZECH_STOP_WORDS, new WordList(words));
	}

	/**
	 * Returns the sample texts: every lemma of the Czech fixture with all its forms, then the sentences.
	 *
	 * @return the texts to compare the chains on
	 */
	@Nonnull
	private static List<String> samples() {
		final List<String> samples = new ArrayList<>(CzechAnalysisFixture.VOCABULARY.size() + SENTENCES.size());
		for (final Lemma lemma : CzechAnalysisFixture.VOCABULARY) {
			samples.add(String.join(" ", lemma.forms()));
		}
		samples.addAll(SENTENCES);
		return samples;
	}

	/**
	 * Returns every term a chain produces for a text, with its offsets and position increment.
	 *
	 * @param analyzer the chain
	 * @param text     the text
	 * @return the terms, comparable across chains
	 */
	@Nonnull
	private static List<String> terms(@Nonnull FulltextAnalyzer analyzer, @Nonnull String text) {
		return analyzer.getTerms(text).stream()
			.map(term -> term.term() + "@" + term.startOffset() + "-" + term.endOffset() + "+" +
				term.positionIncrement())
			.toList();
	}
}
