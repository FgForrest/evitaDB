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
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.analysis.FulltextResourceContent;
import io.evitadb.api.requestResponse.schema.analysis.WordList;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.spi.analysis.AnalysisStepFactory;
import io.evitadb.spi.analysis.AnalysisStepParameterDescriptor;
import io.evitadb.spi.analysis.FulltextAnalysisContext;
import io.evitadb.spi.analysis.FulltextAnalysisSide;
import io.evitadb.spi.analysis.FulltextAnalysisTrait;
import io.evitadb.spi.analysis.FulltextSplittingAnalysisOperation;
import io.evitadb.spi.analysis.FulltextSplittingAnalysisStepFactory;
import io.evitadb.spi.analysis.FulltextTextAnalysisOperation;
import io.evitadb.spi.analysis.FulltextTextAnalysisStepFactory;
import io.evitadb.spi.analysis.FulltextWordsAnalysisOperation;
import io.evitadb.spi.analysis.FulltextWordsAnalysisStepFactory;
import io.evitadb.spi.analysis.FulltextWordsPlacement.PlacedStep;
import io.evitadb.spi.analysis.FulltextWordsPlacement;
import io.evitadb.test.TestConstants;
import io.evitadb.test.fulltext.AnalyzedTerm;
import io.evitadb.utils.NamingConvention;
import org.apache.lucene.analysis.FilteringTokenFilter;
import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.charfilter.MappingCharFilter;
import org.apache.lucene.analysis.charfilter.NormalizeCharMap;
import org.apache.lucene.analysis.pattern.PatternReplaceFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.fulltext.FulltextAnalysisTestSupport.collectTerms;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the step-list chains {@link FulltextChainCompiler} builds: the built-in `text` analyzer must produce in
 * each built-in language exactly the terms of today's chains of the language (markup stripping aside, which `text`
 * does not do and `html-text` does), and the compiler must refuse what cannot form a chain.
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
	/** A catalog schema declaring nothing: the built-in analyzers resolve in it as they are. */
	private static final CatalogSchema EMPTY_CATALOG = CatalogSchema._internalBuild(
		TestConstants.TEST_CATALOG,
		NamingConvention.generate(TestConstants.TEST_CATALOG),
		null,
		EnumSet.allOf(CatalogEvolutionMode.class),
		EmptyEntitySchemaAccessor.INSTANCE
	);
	/** A text step on both sides, splitting a word at its underscores. */
	private static final ReplaceCharacter UNDERSCORE_TO_SPACE =
		new ReplaceCharacter("test-underscore-to-space", "_", " ", EnumSet.allOf(FulltextAnalysisSide.class));
	/** The same text step, acting on the index side only. */
	private static final ReplaceCharacter UNDERSCORE_TO_SPACE_ON_INDEX = new ReplaceCharacter(
		"test-underscore-to-space-on-index", "_", " ", EnumSet.of(FulltextAnalysisSide.INDEX)
	);
	/** A text step on both sides, joining the parts of a word at its underscores. */
	private static final ReplaceCharacter UNDERSCORE_TO_X =
		new ReplaceCharacter("test-underscore-to-x", "_", "x", EnumSet.allOf(FulltextAnalysisSide.class));
	/** A further splitting step replacing `a` by `b` in every word. */
	private static final ReplaceInWords A_TO_B = new ReplaceInWords("test-a-to-b", "a", "b");
	/** A further splitting step replacing `b` by `c` in every word. */
	private static final ReplaceInWords B_TO_C = new ReplaceInWords("test-b-to-c", "b", "c");

	/** The registry discovering the installed step providers. */
	private final AnalysisStepRegistry stepRegistry = new AnalysisStepRegistry();
	/** The compiler under test. */
	private final FulltextChainCompiler compiler = new FulltextChainCompiler(this.stepRegistry);
	/** Pins the compiled built-ins to the output recorded from the retired language chains. */
	@Nested
	@DisplayName("Golden output of the built-in analyzers")
	class BuiltInGoldenOutput {
		/** Preserves plain index output, including offsets and position increments. */
		@Test
		@DisplayName("should preserve recorded index terms")
		void shouldProduceTodaysIndexTerms() throws IOException {
			verifyGolden("text", FulltextAnalysisSide.INDEX);
		}

		/** Preserves query variants and their stream order. */
		@Test
		@DisplayName("should preserve recorded query terms")
		void shouldProduceTodaysQueryTerms() throws IOException {
			verifyGolden("text", FulltextAnalysisSide.QUERY);
		}

		/** Preserves markup stripping and corrected offsets on the HTML index side. */
		@Test
		@DisplayName("should preserve recorded index terms on markup")
		void shouldProduceTodaysIndexTermsOnMarkup() throws IOException {
			verifyGolden("html-text", FulltextAnalysisSide.INDEX);
		}
	}

	/** Reads versioned UTF-8 TSV with Base64 UTF-8 cells, preserving every Unicode and control character. */
	private void verifyGolden(@Nonnull String name, @Nonnull FulltextAnalysisSide side) throws IOException {
		for (final String language : List.of("cs", "sk", "pl", "ro", "en", "de", "fr")) {
			final String resource = "/analysis/fulltext-" + name + "-" + side.name().toLowerCase(Locale.ROOT) +
				"-" + language + ".tsv";
			try (
				final FulltextAnalyzer compiled = compileBuiltIn(name, side, Locale.forLanguageTag(language));
				final InputStream stream = getClass().getResourceAsStream(resource);
				final BufferedReader reader = new BufferedReader(new InputStreamReader(
					assertGoldenExists(stream, resource), StandardCharsets.UTF_8))
			) {
				assertEquals("# version=1", reader.readLine(), resource);
				for (final String row : reader.lines().toList()) {
					if (!row.startsWith("#")) {
						final String[] cells = row.split("\t", -1);
						final String input = decodeGolden(cells[0]);
						assertEquals(decodeGolden(cells[1]), String.join("\n", terms(compiled, input)),
							"The output changed - bump the behaviour version of the changed step and " +
								"re-record deliberately, or revert. " + resource + ": " + input);
					}
				}
			}
		}
	}

	/** Fails with the resource name when a golden file is missing, instead of a bare null dereference. */
	@Nonnull
	private static InputStream assertGoldenExists(@Nullable InputStream stream, @Nonnull String resource) {
		assertNotNull(stream, "Golden file " + resource + " is missing - record it deliberately, never skip it.");
		return stream;
	}

	/** Decodes a complete TSV cell. */
	@Nonnull
	private static String decodeGolden(@Nonnull String value) {
		return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
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
			try (
				final FulltextAnalyzer compiled = compile(words, FulltextAnalysisSide.INDEX, brandResources());
				final FulltextAnalyzer unprotected = compile(
					List.of(lowercase(), czechStemming()), FulltextAnalysisSide.INDEX, Map.of()
				)
			) {
				final List<String> stem = termTexts(unprotected, "černým");

				final List<String> terms = termTexts(compiled, "Černých černým");

				assertEquals(1, stem.size(), "the index side stems a word to one term");
				assertEquals(List.of("cernych", stem.get(0)), terms, "an unprotected word is still stemmed");
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
				assertEquals(List.of("cernych"), collectTerms(compiled, "černých")
					.stream().map(AnalyzedTerm::term).toList());
			}
		}

		/** A protected word between two stemmed ones is looked up once, and its neighbours keep all their stems. */
		@Test
		@DisplayName("should keep the variant stems of the words around a protected word on the query side")
		void shouldKeepVariantStemsAroundProtectedWordOnQuerySide() {
			final List<AnalysisStep> words = List.of(
				lowercase(), czechStemming(protectedWords(resource("brands")))
			);
			try (
				final FulltextAnalyzer compiled = compile(words, FulltextAnalysisSide.QUERY, brandResources());
				final FulltextAnalyzer unprotected = compile(
					List.of(lowercase(), czechStemming()), FulltextAnalysisSide.QUERY, Map.of()
				)
			) {
				final List<String> neighbour = termsAndIncrements(unprotected, "černým");
				final List<String> expected = new ArrayList<>(2 * neighbour.size() + 1);
				expected.addAll(neighbour);
				expected.add("cernych+1");
				expected.addAll(neighbour);

				final List<String> terms = termsAndIncrements(compiled, "černým černých černým");

				assertTrue(neighbour.size() > 1, "the neighbour must have several variant stems: " + neighbour);
				assertEquals(expected, terms);
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
				assertEquals(List.of("boty"), collectTerms(index, "Boty").stream().map(AnalyzedTerm::term).toList());
				assertTrue(collectTerms(query, "Boty").isEmpty());
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

	/** Text steps change the text before the tokenizer splits it, in their order. */
	@Nested
	@DisplayName("Text phase")
	class TextPhase {

		/** An index-only text step changes the index chain and leaves the query chain as it is. */
		@Test
		@DisplayName("should build a text step into the chains of its sides only")
		void shouldBuildTextStepIntoChainsOfItsSidesOnly() {
			final FulltextChainCompiler compiler = new FulltextChainCompiler(
				registryWith(UNDERSCORE_TO_SPACE_ON_INDEX)
			);
			final List<AnalysisStep> text = List.of(textStep(UNDERSCORE_TO_SPACE_ON_INDEX.getName()));

			try (
				final FulltextAnalyzer index = chain(compiler, text, SPLITTING, FulltextAnalysisSide.INDEX);
				final FulltextAnalyzer query = chain(compiler, text, SPLITTING, FulltextAnalysisSide.QUERY)
			) {
				assertEquals(List.of("foo", "bar"), termTexts(index, "foo_bar"));
				assertEquals(List.of("foo_bar"), termTexts(query, "foo_bar"));
			}
		}

		/** The second text step reads the text the first one produced. */
		@Test
		@DisplayName("should apply text steps in their order")
		void shouldApplyTextStepsInTheirOrder() {
			final FulltextChainCompiler compiler = new FulltextChainCompiler(
				registryWith(UNDERSCORE_TO_SPACE, UNDERSCORE_TO_X)
			);
			final AnalysisStep toSpace = textStep(UNDERSCORE_TO_SPACE.getName());
			final AnalysisStep toX = textStep(UNDERSCORE_TO_X.getName());

			try (
				final FulltextAnalyzer spaceFirst = chain(
					compiler, List.of(toSpace, toX), SPLITTING, FulltextAnalysisSide.INDEX
				);
				final FulltextAnalyzer xFirst = chain(
					compiler, List.of(toX, toSpace), SPLITTING, FulltextAnalysisSide.INDEX
				)
			) {
				assertEquals(List.of("foo", "bar"), termTexts(spaceFirst, "foo_bar"));
				assertEquals(List.of("fooxbar"), termTexts(xFirst, "foo_bar"));
			}
		}
	}

	/** Further splitting steps act on the tokenizer's words, in their order. */
	@Nested
	@DisplayName("Further splitting")
	class FurtherSplitting {

		/** The second splitting step reads the words the first one produced. */
		@Test
		@DisplayName("should apply further splitting steps in their order")
		void shouldApplyFurtherSplittingStepsInTheirOrder() {
			final FulltextChainCompiler compiler = new FulltextChainCompiler(registryWith(A_TO_B, B_TO_C));
			final AnalysisStep aToB = AnalysisStep.of(A_TO_B.getName(), Map.of());
			final AnalysisStep bToC = AnalysisStep.of(B_TO_C.getName(), Map.of());

			try (
				final FulltextAnalyzer aToBFirst = chain(
					compiler, List.of(), List.of(standardTokenizer(), aToB, bToC), FulltextAnalysisSide.INDEX
				);
				final FulltextAnalyzer bToCFirst = chain(
					compiler, List.of(), List.of(standardTokenizer(), bToC, aToB), FulltextAnalysisSide.INDEX
				)
			) {
				assertEquals(List.of("cc"), termTexts(aToBFirst, "aa"));
				assertEquals(List.of("bb"), termTexts(bToCFirst, "aa"));
			}
		}
	}

	/** Definitions that cannot form a chain are refused when compiled. */
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
			assertTrue(
				exception.getMessage().contains(
					"`lowercase` is a words step, it cannot serve as a further splitting step."
				),
				exception.getMessage()
			);
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
			assertTrue(
				exception.getMessage().contains("`lowercase` is a words step, it cannot serve as the tokenizer."),
				exception.getMessage()
			);
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
			assertTrue(
				exception.getMessage().contains("`standard` is a tokenizer, it cannot serve as a words step."),
				exception.getMessage()
			);
		}

		/** The tokenizer splits the text, it cannot change it before splitting. */
		@Test
		@DisplayName("should refuse the tokenizer in the text phase")
		void shouldRefuseTokenizerInTextPhase() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> FulltextChainCompilerTest.this.compiler.compile(
					List.of(standardTokenizer()), SPLITTING, List.of(), FulltextAnalysisSide.INDEX,
					new FulltextAnalysisContext(CZECH, Map.of())
				)
			);
			assertTrue(
				exception.getMessage().contains("`standard` is a tokenizer, it cannot serve as a text step."),
				exception.getMessage()
			);
		}

		/** A text step reads the whole text, so it cannot act on the words. */
		@Test
		@DisplayName("should refuse a text step in the words phase")
		void shouldRefuseTextStepInWordsPhase() {
			final FulltextChainCompiler compiler = new FulltextChainCompiler(registryWith(UNDERSCORE_TO_SPACE));
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> compiler.compile(
					List.of(), SPLITTING, List.of(textStep(UNDERSCORE_TO_SPACE.getName())), FulltextAnalysisSide.INDEX,
					new FulltextAnalysisContext(CZECH, Map.of())
				)
			);
			assertTrue(
				exception.getMessage().contains(
					"`" + UNDERSCORE_TO_SPACE.getName() + "` is a text step, it cannot serve as a words step."
				),
				exception.getMessage()
			);
		}

		/** A further splitting step needs the words of a tokenizer, so it cannot be one. */
		@Test
		@DisplayName("should refuse a further splitting step as the tokenizer")
		void shouldRefuseFurtherSplittingStepAsTokenizer() {
			final FulltextChainCompiler compiler = new FulltextChainCompiler(registryWith(A_TO_B));
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> compiler.compile(
					List.of(), List.of(AnalysisStep.of(A_TO_B.getName(), Map.of())), List.of(),
					FulltextAnalysisSide.INDEX, new FulltextAnalysisContext(CZECH, Map.of())
				)
			);
			assertTrue(
				exception.getMessage().contains(
					"`" + A_TO_B.getName() + "` is a further splitting step, it cannot serve as the tokenizer."
				),
				exception.getMessage()
			);
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
			assertTrue(exception.getMessage().contains("must start with a tokenizer"), exception.getMessage());
		}
	}

	/** Each words step checks its place among the others through their traits. */
	@Nested
	@DisplayName("Placement checks")
	class PlacementChecks {

		/**
		 * Codex's first case: a fold before the Czech stemmer makes it stem folded words - `Čeští` would be indexed as
		 * `cest` instead of `cesk` and `český` would miss it.
		 */
		@Test
		@DisplayName("should refuse a fold before a stemmer deciding by the diacritics")
		void shouldRefuseFoldBeforeStemmerDecidingByDiacritics() {
			for (final FulltextAnalysisSide side : FulltextAnalysisSide.values()) {
				final EvitaInvalidUsageException exception = assertThrows(
					EvitaInvalidUsageException.class,
					() -> compile(List.of(lowercase(), asciiFolding(), czechStemming()), side, czechResources())
				);
				assertTrue(
					exception.getMessage().contains("`ascii-folding` cannot precede it"), exception.getMessage()
				);
			}
		}

		/**
		 * Codex's third case: a second Czech stemmer after the first would leak its keyword marker into the variants
		 * the first one has not emitted yet. The query side refuses any step after the variants, the index side the
		 * second stemming.
		 */
		@Test
		@DisplayName("should refuse a words step after the variant stems on the query side")
		void shouldRefuseWordsStepAfterVariantStemsOnQuerySide() {
			final List<AnalysisStep> words = List.of(
				lowercase(), czechStemming(), czechStemming(protectedWords(resource("p")))
			);
			final Map<String, FulltextResourceContent> resources = Map.of("p", wordList("konek"));

			final EvitaInvalidUsageException query = assertThrows(
				EvitaInvalidUsageException.class, () -> compile(words, FulltextAnalysisSide.QUERY, resources)
			);
			final EvitaInvalidUsageException index = assertThrows(
				EvitaInvalidUsageException.class, () -> compile(words, FulltextAnalysisSide.INDEX, resources)
			);

			assertTrue(
				query.getMessage().contains("must be the last words step on the query side, but `czech-stemming`"),
				query.getMessage()
			);
			assertTrue(index.getMessage().contains("has already stemmed"), index.getMessage());
		}

		/** Stop words after the Czech stemmer work on stems on the index side, but would corrupt the query variants. */
		@Test
		@DisplayName("should refuse a step after the variant stems on the query side only")
		void shouldRefuseStepAfterVariantStemsOnQuerySideOnly() {
			final List<AnalysisStep> words = List.of(lowercase(), czechStemming(), stopWords(czechStopWords()));
			try (final FulltextAnalyzer index = compile(words, FulltextAnalysisSide.INDEX, czechResources())) {
				assertTrue(!collectTerms(index, "Boty").isEmpty());
			}

			assertThrows(
				EvitaInvalidUsageException.class, () -> compile(words, FulltextAnalysisSide.QUERY, czechResources())
			);
		}

		/** A step acting on the index side only is not on the query side, so it cannot follow the query variants. */
		@Test
		@DisplayName("should accept an index-only step after the variant stems")
		void shouldAcceptIndexOnlyStepAfterVariantStems() {
			final FulltextChainCompiler compiler = new FulltextChainCompiler(registryWith(new PassOnIndexSide()));
			final List<AnalysisStep> words = List.of(lowercase(), czechStemming(), wordsStep(PassOnIndexSide.NAME));
			for (final FulltextAnalysisSide side : FulltextAnalysisSide.values()) {
				try (
					final FulltextAnalyzer chain = new FulltextAnalyzer(
						"text",
						compiler.compile(
							List.of(), SPLITTING, words, side, new FulltextAnalysisContext(CZECH, Map.of())
						)
					)
				) {
					assertTrue(!collectTerms(chain, "Boty").isEmpty(), side.name());
				}
			}
		}

		/** A stem is not a word: a second stemmer would stem it again. */
		@Test
		@DisplayName("should refuse a stemmer after a stemmer")
		void shouldRefuseStemmerAfterStemmer() {
			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> compile(
					List.of(lowercase(), englishStemming(), germanStemming()), FulltextAnalysisSide.INDEX, Map.of()
				)
			);

			assertTrue(
				exception.getMessage().contains(
					"`german-stemming` would stem the words step `english-stemming` has already stemmed"
				),
				exception.getMessage()
			);
		}

		/** A stemmer that keeps the diacritics emits one stem per word and may be followed on the query side. */
		@Test
		@DisplayName("should let a step follow a stemmer that keeps the diacritics")
		void shouldLetStepFollowStemmerThatKeepsDiacritics() {
			final List<AnalysisStep> words = List.of(
				lowercase(), englishStemming(), stopWords(englishStopWords()), asciiFolding()
			);
			try (
				final FulltextAnalyzer query = compile(
					words, FulltextAnalysisSide.QUERY, BuiltInFulltextResources.getResources(), Locale.ENGLISH
				)
			) {
				assertEquals(List.of("run"), collectTerms(query, "the running")
					.stream().map(AnalyzedTerm::term).toList());
			}
		}

		/** `stemming()` checks its place as the step it runs in the language does, and runs none in French. */
		@Test
		@DisplayName("should check the place of `stemming` by the language")
		void shouldCheckPlaceOfStemmingByLanguage() {
			final List<AnalysisStep> words = List.of(lowercase(), asciiFolding(), stemming());

			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> compile(words, FulltextAnalysisSide.INDEX, Map.of(), Locale.forLanguageTag("cs"))
			);
			assertTrue(
				exception.getMessage().contains("Step `stemming` decides by the diacritics"), exception.getMessage()
			);
			for (final Locale accepting : List.of(Locale.ENGLISH, Locale.FRENCH)) {
				try (final FulltextAnalyzer chain = compile(words, FulltextAnalysisSide.QUERY, Map.of(), accepting)) {
					assertTrue(!collectTerms(chain, "Café").isEmpty(), accepting.toString());
				}
			}
		}

		/** A step sees the whole phase of the side with the traits of its steps, and its own position in it. */
		@Test
		@DisplayName("should hand a step the phase of its side with the traits")
		void shouldHandStepPhaseOfItsSideWithTraits() {
			final RecordPlacement recorder = new RecordPlacement();
			final FulltextChainCompiler compiler =
				new FulltextChainCompiler(registryWith(recorder, new PassOnIndexSide()));
			final List<AnalysisStep> words = List.of(
				lowercase(), wordsStep(PassOnIndexSide.NAME), asciiFolding(), wordsStep(RecordPlacement.NAME)
			);

			compiler.compile(
				List.of(), SPLITTING, words, FulltextAnalysisSide.QUERY, new FulltextAnalysisContext(CZECH, Map.of())
			).close();

			assertEquals(1, recorder.placements.size());
			final FulltextWordsPlacement placement = recorder.placements.get(0);
			assertEquals(FulltextAnalysisSide.QUERY, placement.side());
			assertEquals(2, placement.position());
			assertEquals(
				List.of(
					new PlacedStep(LOWERCASE, Set.of()),
					new PlacedStep(ASCII_FOLDING, Set.of(FulltextAnalysisTrait.FOLDS_DIACRITICS)),
					new PlacedStep(RecordPlacement.NAME, Set.of())
				),
				placement.steps()
			);
		}
	}

	/** The words of resources are compared as the steps before the reading step leave them. */
	@Nested
	@DisplayName("Word preparation")
	class WordPreparation {

		/** A protected word written capitalized is protected after `lowercase`, on both sides. */
		@Test
		@DisplayName("should protect a word written as the user types it")
		void shouldProtectWordWrittenAsUserTypesIt() {
			final List<AnalysisStep> words = List.of(lowercase(), czechStemming(protectedWords(resource("brands"))));
			final Map<String, FulltextResourceContent> resources = Map.of("brands", wordList("Černých"));
			for (final FulltextAnalysisSide side : FulltextAnalysisSide.values()) {
				try (final FulltextAnalyzer chain = compile(words, side, resources)) {
					assertEquals(
						List.of("cernych"), collectTerms(chain, "ČERNÝCH").stream().map(AnalyzedTerm::term).toList(),
						side.name()
					);
				}
			}
		}

		/** A list word in decomposed Unicode is normalized to NFC, as the text is. */
		@Test
		@DisplayName("should normalize a list word to NFC")
		void shouldNormalizeListWordToNfc() {
			final List<AnalysisStep> words = List.of(lowercase(), stopWords(resource("stop")));
			final Map<String, FulltextResourceContent> resources = Map.of("stop", wordList("c\u030Cerny\u0301"));
			try (final FulltextAnalyzer chain = compile(words, FulltextAnalysisSide.INDEX, resources)) {
				assertEquals(List.of("boty"), collectTerms(chain, "černý boty")
					.stream().map(AnalyzedTerm::term).toList());
			}
		}

		/** A stop word does not stop its synonyms: the list word stands for itself, not for its group. */
		@Test
		@DisplayName("should not stop the synonyms of a stop word")
		void shouldNotStopSynonymsOfStopWord() {
			final List<AnalysisStep> words = List.of(
				lowercase(), synonyms(resource("groups")), stopWords(resource("stop"))
			);
			final Map<String, FulltextResourceContent> resources = Map.of(
				"groups", synonymList(synonymGroup("mobil", "telefon")), "stop", wordList("Mobil")
			);
			try (final FulltextAnalyzer query = compile(words, FulltextAnalysisSide.QUERY, resources)) {
				assertEquals(List.of("telefon"), collectTerms(query, "mobil")
					.stream().map(AnalyzedTerm::term).toList());
				assertEquals(List.of("telefon"), collectTerms(query, "telefon")
					.stream().map(AnalyzedTerm::term).toList());
			}
		}

		/** A preceding step failing to wrap a word fails the compilation, and the streams built so far are closed. */
		@Test
		@DisplayName("should close the streams built so far when a preceding step fails to wrap")
		void shouldCloseStreamsWhenPrecedingStepFailsToWrap() {
			final TrackClose tracker = new TrackClose();
			final FulltextChainCompiler compiler = new FulltextChainCompiler(registryWith(tracker, new FailToWrap()));
			final List<AnalysisStep> words = List.of(
				wordsStep(TrackClose.NAME), wordsStep(FailToWrap.NAME), stopWords(resource("stop"))
			);

			final EvitaInvalidUsageException exception = assertThrows(
				EvitaInvalidUsageException.class,
				() -> compiler.compile(
					List.of(), SPLITTING, words, FulltextAnalysisSide.INDEX,
					new FulltextAnalysisContext(CZECH, Map.of("stop", wordList("a")))
				)
			);

			assertEquals(FailToWrap.MESSAGE, exception.getMessage());
			assertTrue(tracker.closed.get(), "The stream the first step built must be closed.");
		}
	}

	/**
	 * Builds a registry of the installed providers plus the given ones.
	 *
	 * @param extras the additional providers
	 * @return the registry
	 */
	@Nonnull
	private static AnalysisStepRegistry registryWith(@Nonnull AnalysisStepFactory... extras) {
		final List<AnalysisStepFactory> factories = new ArrayList<>(
			ServiceLoader.load(AnalysisStepFactory.class).stream().map(ServiceLoader.Provider::get).toList()
		);
		Collections.addAll(factories, extras);
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
	 * A words step on both sides whose filter remembers being closed.
	 */
	private static final class TrackClose extends TestStep implements FulltextWordsAnalysisStepFactory {
		/** The step's name. */
		static final String NAME = "test-track-close";
		/** Set when a filter of the step is closed. */
		final AtomicBoolean closed = new AtomicBoolean();

		@Nonnull
		@Override
		public String getName() {
			return NAME;
		}

		@Nonnull
		@Override
		public Set<FulltextAnalysisSide> getSides() {
			return EnumSet.allOf(FulltextAnalysisSide.class);
		}

		@Nonnull
		@Override
		public FulltextWordsAnalysisOperation create(
			@Nonnull Map<String, Serializable> parameters,
			@Nonnull FulltextAnalysisContext context
		) {
			return (words, side) -> new TokenFilter(words) {
				@Override
				public boolean incrementToken() throws IOException {
					return this.input.incrementToken();
				}

				@Override
				public void close() throws IOException {
					TrackClose.this.closed.set(true);
					super.close();
				}
			};
		}
	}

	/**
	 * A words step on both sides that fails whenever it is asked to wrap a stream.
	 */
	private static final class FailToWrap extends TestStep implements FulltextWordsAnalysisStepFactory {
		/** The step's name. */
		static final String NAME = "test-fail-to-wrap";
		/** The message of the failure. */
		static final String MESSAGE = "Wrapping failed.";

		@Nonnull
		@Override
		public String getName() {
			return NAME;
		}

		@Nonnull
		@Override
		public Set<FulltextAnalysisSide> getSides() {
			return EnumSet.allOf(FulltextAnalysisSide.class);
		}

		@Nonnull
		@Override
		public FulltextWordsAnalysisOperation create(
			@Nonnull Map<String, Serializable> parameters,
			@Nonnull FulltextAnalysisContext context
		) {
			return (words, side) -> {
				throw new EvitaInvalidUsageException(MESSAGE);
			};
		}
	}

	/**
	 * A words step acting on the index side only, which leaves the words as they are.
	 */
	private static final class PassOnIndexSide extends TestStep implements FulltextWordsAnalysisStepFactory {
		/** The step's name. */
		static final String NAME = "test-pass-on-index-side";

		@Nonnull
		@Override
		public String getName() {
			return NAME;
		}

		@Nonnull
		@Override
		public Set<FulltextAnalysisSide> getSides() {
			return EnumSet.of(FulltextAnalysisSide.INDEX);
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
	 * A words step on both sides recording every place it is checked in.
	 */
	private static final class RecordPlacement extends TestStep implements FulltextWordsAnalysisStepFactory {
		/** The step's name. */
		static final String NAME = "test-record-placement";
		/** The places the step was checked in, in order. */
		final List<FulltextWordsPlacement> placements = new ArrayList<>(2);

		@Nonnull
		@Override
		public String getName() {
			return NAME;
		}

		@Nonnull
		@Override
		public Set<FulltextAnalysisSide> getSides() {
			return EnumSet.allOf(FulltextAnalysisSide.class);
		}

		@Nonnull
		@Override
		public FulltextWordsAnalysisOperation create(
			@Nonnull Map<String, Serializable> parameters,
			@Nonnull FulltextAnalysisContext context
		) {
			return new FulltextWordsAnalysisOperation() {
				@Nonnull
				@Override
				public TokenStream wrap(@Nonnull TokenStream words, @Nonnull FulltextAnalysisSide side) {
					return words;
				}

				@Override
				public void verifyPlacement(@Nonnull FulltextWordsPlacement placement) {
					RecordPlacement.this.placements.add(placement);
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
	 * A text step replacing a character by another one on the sides it is given, through Lucene's character filter,
	 * which keeps the offsets pointing into the original text.
	 */
	private static final class ReplaceCharacter extends TestStep implements FulltextTextAnalysisStepFactory {
		/** The step's name. */
		@Nonnull private final String name;
		/** The replacement, as the character filter reads it. */
		@Nonnull private final NormalizeCharMap replacement;
		/** The sides the step acts on. */
		@Nonnull private final Set<FulltextAnalysisSide> sides;

		/**
		 * Creates the step.
		 *
		 * @param name        the step's name
		 * @param character   the character to replace
		 * @param replacement the character replacing it
		 * @param sides       the sides the step acts on
		 */
		ReplaceCharacter(
			@Nonnull String name,
			@Nonnull String character,
			@Nonnull String replacement,
			@Nonnull Set<FulltextAnalysisSide> sides
		) {
			final NormalizeCharMap.Builder builder = new NormalizeCharMap.Builder();
			builder.add(character, replacement);
			this.name = name;
			this.replacement = builder.build();
			this.sides = sides;
		}

		@Nonnull
		@Override
		public String getName() {
			return this.name;
		}

		@Nonnull
		@Override
		public Set<FulltextAnalysisSide> getSides() {
			return this.sides;
		}

		@Nonnull
		@Override
		public FulltextTextAnalysisOperation create(
			@Nonnull Map<String, Serializable> parameters,
			@Nonnull FulltextAnalysisContext context
		) {
			return (text, side) -> new MappingCharFilter(this.replacement, text);
		}
	}

	/**
	 * A further splitting step replacing a pattern in every word.
	 */
	private static final class ReplaceInWords extends TestStep implements FulltextSplittingAnalysisStepFactory {
		/** The step's name. */
		@Nonnull private final String name;
		/** The pattern to replace. */
		@Nonnull private final Pattern pattern;
		/** The text replacing every match. */
		@Nonnull private final String replacement;

		/**
		 * Creates the step.
		 *
		 * @param name        the step's name
		 * @param regex       the pattern to replace
		 * @param replacement the text replacing every match
		 */
		ReplaceInWords(@Nonnull String name, @Nonnull String regex, @Nonnull String replacement) {
			this.name = name;
			this.pattern = Pattern.compile(regex);
			this.replacement = replacement;
		}

		@Nonnull
		@Override
		public String getName() {
			return this.name;
		}

		@Nonnull
		@Override
		public FulltextSplittingAnalysisOperation create(
			@Nonnull Map<String, Serializable> parameters,
			@Nonnull FulltextAnalysisContext context
		) {
			return words -> new PatternReplaceFilter(words, this.pattern, this.replacement, true);
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
		return compile(words, side, resources, CZECH);
	}

	/**
	 * Compiles a chain over the standard tokenizer for a locale and wraps it the way the registry wraps today's
	 * chains.
	 *
	 * @param words     the words phase
	 * @param side      the side to compile
	 * @param resources the resources the steps may name
	 * @param locale    the locale the chain is built for
	 * @return the wrapped chain, to be closed by the caller
	 */
	@Nonnull
	private FulltextAnalyzer compile(
		@Nonnull List<AnalysisStep> words,
		@Nonnull FulltextAnalysisSide side,
		@Nonnull Map<String, FulltextResourceContent> resources,
		@Nonnull Locale locale
	) {
		return new FulltextAnalyzer(
			"text",
			this.compiler.compile(List.of(), SPLITTING, words, side, new FulltextAnalysisContext(locale, resources))
		);
	}

	/**
	 * Returns the built-in resources the engine serves, the Czech stop list `czech-stop-words` among them.
	 *
	 * @return the resources of the built-in lines
	 */
	@Nonnull
	private static Map<String, FulltextResourceContent> czechResources() {
		return BuiltInFulltextResources.getResources();
	}

	/**
	 * Resolves a built-in analyzer for a locale, compiles one side of it and wraps it the way the registry wraps
	 * today's chains.
	 *
	 * @param analyzer the name of the built-in analyzer
	 * @param side     the side to compile
	 * @param locale   the locale the analyzer is resolved for
	 * @return the wrapped chain, to be closed by the caller
	 */
	@Nonnull
	private FulltextAnalyzer compileBuiltIn(
		@Nonnull String analyzer,
		@Nonnull FulltextAnalysisSide side,
		@Nonnull Locale locale
	) {
		return new FulltextAnalyzer(
			analyzer, this.compiler.compile(ResolvedFulltextAnalyzer.resolve(EMPTY_CATALOG, analyzer, locale), side)
		);
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
		return collectTerms(analyzer, text).stream()
			.map(term -> term.term() + "@" + term.startOffset() + "-" + term.endOffset() + "+" +
				term.positionIncrement())
			.toList();
	}

	/**
	 * Compiles a chain without a words phase and wraps it the way the registry wraps today's chains.
	 *
	 * @param compiler  the compiler resolving the steps
	 * @param text      the text phase
	 * @param splitting the word splitting
	 * @param side      the side to compile
	 * @return the wrapped chain, to be closed by the caller
	 */
	@Nonnull
	private static FulltextAnalyzer chain(
		@Nonnull FulltextChainCompiler compiler,
		@Nonnull List<AnalysisStep> text,
		@Nonnull List<AnalysisStep> splitting,
		@Nonnull FulltextAnalysisSide side
	) {
		return new FulltextAnalyzer(
			"test", compiler.compile(text, splitting, List.of(), side, new FulltextAnalysisContext(CZECH, Map.of()))
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
		return collectTerms(analyzer, text).stream().map(AnalyzedTerm::term).toList();
	}

	/**
	 * Returns every term a chain produces for a text with its position increment, without the offsets - comparable
	 * between words at different places of a text.
	 *
	 * @param analyzer the chain
	 * @param text     the text
	 * @return the terms as `term+increment`
	 */
	@Nonnull
	private static List<String> termsAndIncrements(@Nonnull FulltextAnalyzer analyzer, @Nonnull String text) {
		return collectTerms(analyzer, text).stream().map(term -> term.term() + "+" + term.positionIncrement()).toList();
	}

}
