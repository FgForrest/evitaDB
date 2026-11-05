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

package io.evitadb.core.query.filter.translator.fulltext;

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.FulltextResourceContent.FulltextResourceKind;
import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.index.fulltext.analysis.*;
import io.evitadb.spi.analysis.*;
import io.evitadb.test.fulltext.FulltextAnalysisTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.*;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.core.query.filter.translator.fulltext.FulltextSurfaceTestSupport.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.fulltext.FulltextAnalysisTestSupport.collectTerms;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The raw final word prepared through profile-owned surface normalization, with stop/synonym bypass and cached
 * operation and resource ownership.
 */
@Tag(ENGINE)
@Tag(FULLTEXT)
@DisplayName("Unfinished query word fragment")
class FulltextQueryFragmentTest {
	/** Installed step providers, resolved as for a catalog. */
	private final AnalysisStepRegistry registry = new AnalysisStepRegistry();

	/** Lowercasing and the fold a stemmer declares apply, the stemmer and the stop filter do not. */
	@ParameterizedTest
	@CsvSource({"en,Running,running", "en,The,the", "cs,Žluťoučký,zlutoucky", "cs,Není,neni",
		"de,süß,suss", "de,suess,suss"})
	@DisplayName("Normalizes and folds the fragment as its profile does, but neither stems nor stops it")
	void shouldNormalizeWithoutStemmingOrStopping(String language, String word, String fragment) {
		try (final FulltextAnalyzerRegistry cache = new FulltextAnalyzerRegistry(this.registry)) {
			final String[] prepared = FulltextQueryFragment.prepare(cache.getCompiledAnalyzer(
				FulltextAnalysisTestSupport.SCHEMA, "text", Locale.forLanguageTag(language)),
				new TextMatchesTranslator.QueryToken(word, new String[]{word}), false);
			assertArrayEquals(new String[]{fragment}, prepared);
		}
	}

	/** The lane prefix leg scans the folded surface keys with the fragment, so both share one normalization. */
	@Test
	@DisplayName("Shares the lane surface normalization even when a words step follows the stemmer")
	void shouldShareSurfaceNormalizationWhenStepFollowsStemmer() {
		final CatalogSchemaContract schema = new InternalCatalogSchemaBuilder(FulltextAnalysisTestSupport.SCHEMA)
			.withFulltextAnalyzer("folded-after-stem", a -> a.words(lowercase(), englishStemming(), asciiFolding()))
			.toInstance();
		try (final FulltextAnalyzerRegistry cache = new FulltextAnalyzerRegistry(this.registry)) {
			final CompiledFulltextAnalyzer compiled =
				cache.getCompiledAnalyzer(schema, "folded-after-stem", Locale.ENGLISH);

			final String[] fragment = fragments(compiled, "Café");

			assertArrayEquals(collectTerms(compiled.surface(), "Café").stream().map(t -> t.term())
				.toArray(String[]::new), fragment);
			assertArrayEquals(new String[]{"café"}, fragment);
		}
	}

	/** A real resource-consuming provider detects work repeated after publication. */
	@Nested
	@DisplayName("Cached fragment preparation")
	class Caching {
		/** Surface normalization retains stops and synonyms; fragment preparation must explicitly omit both. */
		@Test
		@DisplayName("Keeps stop words and original words around query synonym expansion")
		void shouldOmitStopAndSynonymOperations() {
			final CatalogSchemaContract schema =
				new InternalCatalogSchemaBuilder(FulltextAnalysisTestSupport.SCHEMA)
					.withFulltextResource("fragment-stops", wordList("the"))
					.withFulltextResource("fragment-synonyms", synonymList(synonymGroup("bike", "bicycle")))
					.withFulltextAnalyzer("fragment-policy", a -> a.words(lowercase(),
						stopWords(resource("fragment-stops")), synonyms(resource("fragment-synonyms")),
						englishStemming())).toInstance();
			try (final FulltextAnalyzerRegistry cache = new FulltextAnalyzerRegistry(new AnalysisStepRegistry())) {
				final CompiledFulltextAnalyzer compiled =
					cache.getCompiledAnalyzer(schema, "fragment-policy", Locale.ENGLISH);
				for (final String word : List.of("the", "bike")) {
					assertArrayEquals(new String[]{word}, FulltextQueryFragment.prepare(compiled,
						new TextMatchesTranslator.QueryToken(word, new String[]{word}), false));
				}
				assertTrue(collectTerms(compiled.surface(), "the").isEmpty());
			}
		}

		/** Compiled fragments must reuse operations and their prepared word lists on every query. */
		@Test
		@DisplayName("Reuses the provider and word list after the analyzer is published")
		void shouldReusePreparedFragment() {
			final CountingFragmentWords provider = new CountingFragmentWords();
			final List<AnalysisStepFactory> factories = new ArrayList<>(ServiceLoader.load(AnalysisStepFactory.class)
				.stream().map(ServiceLoader.Provider::get).toList());
			factories.add(provider);
			final AnalysisStepRegistry steps = new AnalysisStepRegistry(factories);
			final CatalogSchemaContract schema =
				new InternalCatalogSchemaBuilder(FulltextAnalysisTestSupport.SCHEMA)
				.withFulltextResource("fragment-words", wordList("marker"))
				.withFulltextAnalyzer("counted-fragment", a -> a.words(lowercase(),
					wordsStep(provider.getName(), parameter("words", "fragment-words")), englishStemming()))
				.toInstance();
			try (final FulltextAnalyzerRegistry cache = new FulltextAnalyzerRegistry(steps)) {
				final CompiledFulltextAnalyzer compiled =
					cache.getCompiledAnalyzer(schema, "counted-fragment", Locale.ENGLISH);
				final int creates = provider.creates;
				final int reads = provider.reads;
				for (int i = 0; i < 3; i++) {
					assertArrayEquals(new String[]{"running"}, FulltextQueryFragment.prepare(compiled,
						new TextMatchesTranslator.QueryToken("Running", new String[]{"Running"}), false));
				}
				assertAll(
					() -> assertEquals(creates, provider.creates, "No provider create after cache publication"),
					() -> assertEquals(reads, provider.reads, "No word-list read after cache publication"));
			}
		}

		/** Counts actual operation construction and prepared resource access, with an identity token operation. */
		private static final class CountingFragmentWords implements FulltextWordsAnalysisStepFactory {
			private int creates;
			private int reads;
			@Nonnull
			@Override
			public String getName() { return "test-counting-fragment-words"; }
			@Nonnull
			@Override
			public List<AnalysisStepParameterDescriptor> getParameters() {
				return List.of(new AnalysisStepParameterDescriptor("words", String.class, true,
					"Word list read during compilation", FulltextResourceKind.WORD_LIST));
			}
			@Override
			public void validate(@Nonnull Map<String, Serializable> parameters) { }
			@Nonnull
			@Override
			public Set<FulltextAnalysisSide> getSides() { return Set.of(FulltextAnalysisSide.values()); }
			@Nonnull
			@Override
			public Set<String> getIndexSideParameters() { return Set.of("words"); }
			@Override
			public int getBehaviourVersion() { return 1; }
			@Nonnull
			@Override
			public FulltextWordsAnalysisOperation create(
				@Nonnull Map<String, Serializable> parameters, @Nonnull FulltextAnalysisContext context
			) {
				this.creates++;
				assertEquals(Set.of("marker"), context.getPreparedWords((String) parameters.get("words")));
				this.reads++;
				return (stream, side) -> stream;
			}
		}
	}

	/** Factory declarations must be honored before constructing fragment operations. */
	@Nested
	@DisplayName("Extension fragment declarations")
	class Fragments {
		/** An omitted stop filter preserves the typed fragment that completes to a longer word. */
		@ParameterizedTest
		@ValueSource(booleans = {true, false})
		@DisplayName("Lets a custom stop filter declare fragment omission, retaining the legacy default")
		void shouldRespectCustomStopDeclaration(boolean omitted) {
			final TransformingWords provider = omitted ? new OmittedFragmentWords("stop") : new TransformingWords("stop");
			withAnalyzer(provider, analyzer -> {
				final String[] fragment = fragments(analyzer, "that");
				assertArrayEquals(omitted ? new String[]{"that"} : new String[0], fragment);
			});
		}

		/** Synonyms must not turn a three-letter typed word into a long enough completion fragment. */
		@ParameterizedTest
		@ValueSource(booleans = {true, false})
		@DisplayName("Keeps car below the prefix guard instead of opening it through automobile")
		void shouldRespectCustomSynonymDeclaration(boolean omitted) {
			final TransformingWords provider = omitted
				? new OmittedFragmentWords("synonym") : new TransformingWords("synonym");
			withAnalyzer(provider, analyzer -> {
				final String[] fragment = fragments(analyzer, "car");
				assertArrayEquals(omitted ? new String[]{"car"} : new String[]{"car", "automobile"}, fragment);
			});
		}

		/** Compile the fragment alone so no ordinary-chain creation can hide an omitted operation. */
		@Test
		@DisplayName("Never constructs an omitted fragment operation")
		void shouldOmitBeforeCreate() {
			final OmittedFragmentWords provider = new OmittedFragmentWords("stop");
			final AnalysisStepRegistry steps = steps(provider);
			final ResolvedFulltextAnalyzer resolved = ResolvedFulltextAnalyzer.resolve(schema(provider),
				"extension", Locale.ENGLISH);
			try (final FulltextAnalyzer chain = new FulltextAnalyzer("fragment",
				new FulltextChainCompiler(steps).compileFragmentChain(resolved, false))) {
				assertEquals(0, provider.createCount());
				assertEquals(List.of("that"), collectTerms(chain, "that").stream().map(t -> t.term()).toList());
			}
		}
	}
}
