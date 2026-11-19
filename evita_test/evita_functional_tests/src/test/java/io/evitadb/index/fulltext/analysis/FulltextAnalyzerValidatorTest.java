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

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.FulltextResourceContent.FulltextResourceKind;
import io.evitadb.api.requestResponse.schema.analysis.WordsAnalysisStep;
import io.evitadb.api.requestResponse.schema.builder.InternalCatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.index.analysis.AnalysisStepRegistry;
import io.evitadb.spi.analysis.AnalysisStepFactory;
import io.evitadb.spi.analysis.AnalysisStepParameterDescriptor;
import io.evitadb.spi.analysis.FulltextAnalysisContext;
import io.evitadb.spi.analysis.FulltextAnalysisSide;
import io.evitadb.spi.analysis.FulltextWordsAnalysisOperation;
import io.evitadb.spi.analysis.FulltextWordsAnalysisStepFactory;
import io.evitadb.test.TestConstants;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

import static io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaContract.TEXT;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link FulltextAnalyzerValidator} on real catalog schemas: which analyzers it compiles, in which languages,
 * and how it reports what does not compile. The languages of the entities using an analyzer are covered end to end
 * by `FulltextAnalyzerValidationSchemaTest`, which has real entity schemas.
 */
@DisplayName("Fulltext analyzer validator")
@Tag(ENGINE)
@Tag(SCHEMA)
@Tag(FULLTEXT)
class FulltextAnalyzerValidatorTest {
	/** The registry of the installed providers. */
	private static final AnalysisStepRegistry INSTALLED = new AnalysisStepRegistry();

	/** What compiles. */
	@Nested
	@DisplayName("Accepted")
	class Accepted {

		/** The built-in analyzers compile over the word splitting a catalog declares, in every built-in language. */
		@Test
		@DisplayName("should accept the built-in analyzers over declared word splitting")
		void shouldAcceptBuiltInAnalyzers() {
			final CatalogSchemaContract schema = catalog(
				b -> {
					for (final String language : List.of("cs", "sk", "pl", "ro", "en", "de", "fr")) {
						b.withFulltextLanguage(language, l -> l.wordNumberSplit());
					}
				}
			);

			assertDoesNotThrow(() -> FulltextAnalyzerValidator.validate(INSTALLED, schema));
		}

		/**
		 * A fold before the language-dispatching stemmer is refused only where the stemmer folds itself - a language
		 * the catalog neither declares nor uses is not checked.
		 */
		@Test
		@DisplayName("should accept a language-dependent refusal in a language the catalog does not meet")
		void shouldAcceptRefusalInUnmetLanguage() {
			final CatalogSchemaContract schema =
				catalog(b -> b.withFulltextAnalyzer("names", a -> a.words(asciiFolding(), stemming())));

			assertDoesNotThrow(() -> FulltextAnalyzerValidator.validate(INSTALLED, schema));
		}

		/** A resource a step of another provider reads is found among the catalog's resources. */
		@Test
		@DisplayName("should accept a step of another provider reading a declared resource")
		void shouldAcceptDeclaredResourceOfAnotherProvider() {
			final CatalogSchemaContract schema = catalog(
				b -> b
					.withFulltextResource("brands", wordList("Nike"))
					.withFulltextAnalyzer("names", a -> a.words(ReadList.step("brands")))
			);

			assertDoesNotThrow(() -> FulltextAnalyzerValidator.validate(registryWith(new ReadList()), schema));
		}
	}

	/** What does not compile, and how it is reported. */
	@Nested
	@DisplayName("Refused")
	class Refused {

		/** A step refusing its place in the all-languages definition is refused for all languages. */
		@Test
		@DisplayName("should refuse the all-languages definition naming analyzer and side")
		void shouldRefuseAllLanguagesDefinition() {
			final CatalogSchemaContract schema =
				catalog(b -> b.withFulltextAnalyzer("names", a -> a.words(asciiFolding(), czechStemming())));

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class, () -> FulltextAnalyzerValidator.validate(INSTALLED, schema)
			);
			assertMessage(ex, "Fulltext analyzer `names`", "the all-languages definition", "side", "`czech-stemming`");
		}

		/** An override is checked in its own language. */
		@Test
		@DisplayName("should refuse a language override in its language")
		void shouldRefuseLanguageOverride() {
			final CatalogSchemaContract schema = catalog(
				b -> b.withFulltextAnalyzer(
					"names",
					a -> a.words(lowercase()).language("sk", l -> l.words(asciiFolding(), slovakStemming()))
				)
			);

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class, () -> FulltextAnalyzerValidator.validate(INSTALLED, schema)
			);
			assertMessage(ex, "`names`", "language `sk`");
		}

		/** A language the catalog declares word splitting for is checked for every analyzer. */
		@Test
		@DisplayName("should refuse in a declared language")
		void shouldRefuseInDeclaredLanguage() {
			final CatalogSchemaContract schema = catalog(
				b -> b
					.withFulltextLanguage("cs", l -> l.wordNumberSplit())
					.withFulltextAnalyzer("names", a -> a.words(asciiFolding(), stemming()))
			);

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class, () -> FulltextAnalyzerValidator.validate(INSTALLED, schema)
			);
			assertMessage(ex, "`names`", "language `cs`");
		}

		/** The word splitting of a declared language is part of the built-in analyzers' chains too. */
		@Test
		@DisplayName("should refuse a built-in analyzer over a declared splitting that does not compile")
		void shouldRefuseBuiltInOverBrokenSplitting() {
			final CatalogSchemaContract schema =
				catalog(b -> b.withFulltextLanguage("de", l -> l.tokenizer(tokenizerStep("test-no-such-tokenizer"))));

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class, () -> FulltextAnalyzerValidator.validate(INSTALLED, schema)
			);
			assertMessage(ex, "`" + TEXT + "`", "language `de`", "test-no-such-tokenizer");
		}

		/** A step no provider installs. */
		@Test
		@DisplayName("should refuse a step that is not installed")
		void shouldRefuseUnknownStep() {
			final CatalogSchemaContract schema =
				catalog(b -> b.withFulltextAnalyzer("names", a -> a.words(wordsStep("test-no-such-step"))));

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class, () -> FulltextAnalyzerValidator.validate(INSTALLED, schema)
			);
			assertMessage(ex, "`names`", "test-no-such-step");
		}

		/**
		 * A resource a step of another provider names but the catalog does not declare is a user error - the catalog
		 * schema cannot see which parameters of such a step name a resource.
		 */
		@Test
		@DisplayName("should refuse a step of another provider reading a missing resource")
		void shouldRefuseMissingResourceOfAnotherProvider() {
			final CatalogSchemaContract schema =
				catalog(b -> b.withFulltextAnalyzer("names", a -> a.words(ReadList.step("brands"))));

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class,
				() -> FulltextAnalyzerValidator.validate(registryWith(new ReadList()), schema)
			);
			assertMessage(ex, "`names`", "`brands` does not exist");
		}

		/** A provider that only reads raw content must still honor its declared resource kind at validation. */
		@Test
		@DisplayName("should refuse a wrong-kind third-party resource while validating the fingerprint")
		void shouldRefuseWrongKindResourceOfAnotherProvider() {
			final CatalogSchemaContract schema = catalog(b -> b
				.withFulltextResource("brands", synonymList(synonymGroup("nike", "brand")))
				.withFulltextAnalyzer("names", a -> a.words(ReadList.step("brands"))));
			final InvalidSchemaMutationException ex = assertThrows(InvalidSchemaMutationException.class,
				() -> FulltextAnalyzerValidator.validate(registryWith(new ReadList()), schema));
			assertMessage(ex, "`names`", "fingerprint", "brands");
		}

		/** On load the catalog schema is not validated first, so a broken `basedOn` reaches the validator. */
		@Test
		@DisplayName("should refuse basedOn naming an unknown analyzer")
		void shouldRefuseUnknownBasedOn() {
			final CatalogSchemaContract schema =
				catalog(b -> b.withFulltextAnalyzer("titles", a -> a.basedOn("codes")));

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class, () -> FulltextAnalyzerValidator.validate(INSTALLED, schema)
			);
			assertMessage(ex, "`titles` is based on analyzer `codes`");
		}

		/** A cycle of `basedOn` ends the walk over the languages and is refused by the resolution. */
		@Test
		@DisplayName("should refuse a cycle of basedOn")
		void shouldRefuseCycle() {
			final CatalogSchemaContract schema = catalog(
				b -> b
					.withFulltextAnalyzer("a", a -> a.basedOn("b"))
					.withFulltextAnalyzer("b", a -> a.basedOn("a"))
			);

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class, () -> FulltextAnalyzerValidator.validate(INSTALLED, schema)
			);
			assertMessage(ex, "cycle of `basedOn`");
		}

		/**
		 * A cycle of analyzers that define both phases themselves: resolving them never follows `basedOn`, so only the
		 * walk over their languages meets the cycle.
		 */
		@Test
		@DisplayName("should refuse a cycle of basedOn between analyzers defining both phases")
		void shouldRefuseCycleBetweenAnalyzersDefiningBothPhases() {
			final CatalogSchemaContract schema = catalog(
				b -> b
					.withFulltextAnalyzer("a", a -> a.basedOn("b").text(htmlStrip()).words(lowercase()))
					.withFulltextAnalyzer("b", a -> a.basedOn("a").text(htmlStrip()).words(lowercase()))
			);

			final InvalidSchemaMutationException ex = assertThrows(
				InvalidSchemaMutationException.class, () -> FulltextAnalyzerValidator.validate(INSTALLED, schema)
			);
			assertMessage(ex, "cycle of `basedOn`");
		}
	}

	/**
	 * Asserts that a refusal's message contains every given fragment.
	 *
	 * @param ex        the refusal
	 * @param fragments the fragments the message must contain
	 */
	private static void assertMessage(@Nonnull InvalidSchemaMutationException ex, @Nonnull String... fragments) {
		for (final String fragment : fragments) {
			assertTrue(ex.getMessage().contains(fragment), () -> "`" + fragment + "` not in: " + ex.getMessage());
		}
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
		factories.addAll(List.of(extras));
		return new AnalysisStepRegistry(factories);
	}

	/**
	 * A words step of another provider whose parameter names a resource the step reads when it is created - the
	 * catalog schema does not know the parameter names a resource.
	 */
	private static final class ReadList implements FulltextWordsAnalysisStepFactory {
		/** The step's name. */
		static final String NAME = "test-read-list";
		/** The parameter naming the resource. */
		static final String LIST = "list";

		/**
		 * Describes the step reading the given resource.
		 *
		 * @param resource the name of the resource
		 * @return the description
		 */
		@Nonnull
		static WordsAnalysisStep step(@Nonnull String resource) {
			return wordsStep(NAME, parameter(LIST, resource));
		}

		@Nonnull
		@Override
		public String getName() {
			return NAME;
		}

		@Nonnull
		@Override
		public List<AnalysisStepParameterDescriptor> getParameters() {
			return List.of(new AnalysisStepParameterDescriptor(LIST, String.class, true, "the resource to read",
				FulltextResourceKind.WORD_LIST));
		}

		@Override
		public void validate(@Nonnull Map<String, Serializable> parameters) {
			// the descriptor says it all
		}

		@Nonnull
		@Override
		public Set<String> getIndexSideParameters() {
			return Set.of(LIST);
		}

		@Override
		public int getBehaviourVersion() {
			return 1;
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
			context.getResource((String) parameters.get(LIST));
			return (words, side) -> words;
		}
	}
}
