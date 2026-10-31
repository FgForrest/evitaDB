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


package io.evitadb.api.requestResponse.schema.builder;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.analysis.FulltextAnalysisPhases;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateFulltextAnalyzerSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateFulltextLanguageSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateFulltextResourceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyFulltextAnalyzerSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyFulltextLanguageSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyFulltextResourceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveFulltextAnalyzerSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.RemoveFulltextLanguageSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;
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
import java.util.Map;
import java.util.function.Consumer;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the fulltext parts of the catalog schema - languages, resources and analyzers - as the catalog schema
 * builder declares them: the mutations it produces, the schema they build, and the references the catalog schema
 * validates between the parts.
 */
@DisplayName("Fulltext languages, resources and analyzers in the catalog schema")
@Tag(CONTRACT)
@Tag(SCHEMA)
@Tag(FULLTEXT)
class FulltextCatalogSchemaBuilderTest {

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
	 * Applies the changes to a builder over the base schema and returns the mutations the builder produced.
	 *
	 * @param base    the schema the builder starts from
	 * @param changes the changes to make
	 * @return the mutations, empty when the builder produced none
	 */
	@Nonnull
	private static LocalCatalogSchemaMutation[] mutationsOf(
		@Nonnull CatalogSchemaContract base,
		@Nonnull Consumer<InternalCatalogSchemaBuilder> changes
	) {
		final InternalCatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(base);
		changes.accept(builder);
		return builder.toMutation()
			.map(ModifyCatalogSchemaMutation::getSchemaMutations)
			.orElse(new LocalCatalogSchemaMutation[0]);
	}

	/**
	 * Applies the changes to a builder over the base schema and returns the resulting schema.
	 *
	 * @param base    the schema the builder starts from
	 * @param changes the changes to make
	 * @return the resulting schema
	 */
	@Nonnull
	private static CatalogSchemaContract schemaOf(
		@Nonnull CatalogSchemaContract base,
		@Nonnull Consumer<InternalCatalogSchemaBuilder> changes
	) {
		final InternalCatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(base);
		changes.accept(builder);
		return builder.toInstance();
	}

	@Nested
	@DisplayName("Languages")
	class Languages {

		@Test
		@DisplayName("declares word/number splitting after the standard tokenizer")
		void shouldDeclareWordNumberSplitAfterTheStandardTokenizer() {
			final CatalogSchemaContract schema = schemaOf(
				emptyCatalog(),
				b -> b.withFulltextLanguage("cs", l -> l.wordNumberSplit())
			);

			assertEquals(
				List.of(standardTokenizer(), wordNumberSplit()),
				schema.getFulltextLanguage("cs").orElseThrow().getWordSplitting()
			);
			assertTrue(schema.getFulltextLanguage("sk").isEmpty());
			assertEquals(2, schema.version());
		}

		@Test
		@DisplayName("creates, then modifies only on a change, then removes")
		void shouldCreateModifyOnChangeAndRemove() {
			final LocalCatalogSchemaMutation[] creation = mutationsOf(
				emptyCatalog(),
				b -> b.withFulltextLanguage("cs", l -> l.wordNumberSplit())
			);
			assertEquals(1, creation.length);
			assertInstanceOf(CreateFulltextLanguageSchemaMutation.class, creation[0]);

			final CatalogSchemaContract declared = schemaOf(
				emptyCatalog(),
				b -> b.withFulltextLanguage("cs", l -> l.wordNumberSplit())
			);
			assertEquals(0, mutationsOf(declared, b -> b.withFulltextLanguage("cs", l -> l.wordNumberSplit())).length);

			final LocalCatalogSchemaMutation[] modification = mutationsOf(
				declared,
				b -> b.withFulltextLanguage("cs", l -> {})
			);
			assertEquals(1, modification.length);
			assertInstanceOf(ModifyFulltextLanguageSchemaMutation.class, modification[0]);

			final LocalCatalogSchemaMutation[] removal = mutationsOf(declared, b -> b.withoutFulltextLanguage("cs"));
			assertEquals(1, removal.length);
			assertInstanceOf(RemoveFulltextLanguageSchemaMutation.class, removal[0]);
			assertTrue(schemaOf(declared, b -> b.withoutFulltextLanguage("cs")).getFulltextLanguages().isEmpty());
		}

		@Test
		@DisplayName("refuses a full language tag instead of a bare language code")
		void shouldRefuseAFullLanguageTag() {
			final InternalCatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(emptyCatalog());
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> builder.withFulltextLanguage("cs-CZ", l -> l.wordNumberSplit())
			);
		}

		@Test
		@DisplayName("treats removing an undeclared language as nothing to do")
		void shouldIgnoreRemovingAnUndeclaredLanguage() {
			final CatalogSchemaContract schema = schemaOf(emptyCatalog(), b -> b.withoutFulltextLanguage("cs"));
			assertTrue(schema.getFulltextLanguages().isEmpty());
			assertEquals(1, schema.version());
		}

		@Test
		@DisplayName("refuses a null word splitting step instead of failing on it")
		void shouldRefuseANullWordSplittingStep() {
			final CreateFulltextLanguageSchemaMutation mutation = new CreateFulltextLanguageSchemaMutation(
				"cs", new AnalysisStep[]{standardTokenizer(), null}
			);

			assertThrows(
				EvitaInvalidUsageException.class,
				() -> mutation.mutate(emptyCatalog(), EmptyEntitySchemaAccessor.INSTANCE)
			);
		}

	}

	@Nested
	@DisplayName("Resources")
	class Resources {

		@Test
		@DisplayName("declares word lists and synonym lists by name")
		void shouldDeclareWordAndSynonymLists() {
			final CatalogSchemaContract schema = schemaOf(
				emptyCatalog(),
				b -> b
					.withFulltextResource("brands", wordList("puma", "nike"))
					.withFulltextResource("phones", synonymList(synonymGroup("mobil", "telefon")))
			);

			assertEquals(wordList("puma", "nike"), schema.getFulltextResource("brands").orElseThrow().getContent());
			assertEquals(
				synonymList(synonymGroup("mobil", "telefon")),
				schema.getFulltextResource("phones").orElseThrow().getContent()
			);
		}

		@Test
		@DisplayName("modifies a resource only when its content changes")
		void shouldModifyOnlyOnAContentChange() {
			final CatalogSchemaContract declared = schemaOf(
				emptyCatalog(),
				b -> b.withFulltextResource("brands", wordList("puma"))
			);
			assertEquals(0, mutationsOf(declared, b -> b.withFulltextResource("brands", wordList("puma"))).length);

			final LocalCatalogSchemaMutation[] modification = mutationsOf(
				declared,
				b -> b.withFulltextResource("brands", wordList("nike"))
			);
			assertEquals(1, modification.length);
			assertInstanceOf(ModifyFulltextResourceSchemaMutation.class, modification[0]);
		}

		@Test
		@DisplayName("refuses the name of a built-in resource")
		void shouldRefuseTheNameOfABuiltInResource() {
			final InternalCatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(emptyCatalog());
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> builder.withFulltextResource(CZECH_STOP_WORDS, wordList("a")).toInstance()
			);
		}

		@Test
		@DisplayName("refuses a synonym group of a single word")
		void shouldRefuseASynonymGroupOfASingleWord() {
			assertThrows(EvitaInvalidUsageException.class, () -> synonymGroup("mobil"));
		}

	}

	@Nested
	@DisplayName("Analyzers")
	class Analyzers {

		@Test
		@DisplayName("declares an analyzer based on a built-in one with its own text phase")
		void shouldDeclareAnAnalyzerBasedOnABuiltInOne() {
			final CatalogSchemaContract schema = schemaOf(
				emptyCatalog(),
				b -> b.withFulltextAnalyzer(
					"html-title", a -> a.basedOn(FulltextAnalyzerSchemaContract.TEXT).text(htmlStrip())
				)
			);

			final FulltextAnalyzerSchemaContract analyzer = schema.getFulltextAnalyzer("html-title").orElseThrow();
			assertEquals(FulltextAnalyzerSchemaContract.TEXT, analyzer.getBasedOn().orElseThrow());
			assertEquals(new FulltextAnalysisPhases(List.of(htmlStrip()), null), analyzer.getAllLanguages());
			assertTrue(analyzer.getLanguages().isEmpty());
			assertDoesNotThrow(schema::validate);
		}

		@Test
		@DisplayName("keeps per-language overrides beside the all-languages phases")
		void shouldKeepPerLanguageOverrides() {
			final CatalogSchemaContract schema = schemaOf(
				emptyCatalog(),
				b -> b.withFulltextAnalyzer(
					"names",
					a -> a
						.allLanguages(l -> l.words(lowercase()))
						.language("cs", l -> l.words(lowercase(), czechStemming()))
				)
			);

			final FulltextAnalyzerSchemaContract analyzer = schema.getFulltextAnalyzer("names").orElseThrow();
			assertTrue(analyzer.getBasedOn().isEmpty());
			assertEquals(new FulltextAnalysisPhases(null, List.of(lowercase())), analyzer.getAllLanguages());
			assertEquals(
				Map.of("cs", new FulltextAnalysisPhases(null, List.of(lowercase(), czechStemming()))),
				analyzer.getLanguages()
			);
		}

		@Test
		@DisplayName("refuses an analyzer that is based on nothing and defines no phase for all languages")
		void shouldRefuseAnAnalyzerWithoutPhasesOrBase() {
			final InternalCatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(emptyCatalog());
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> builder.withFulltextAnalyzer("empty", a -> a.language("cs", l -> l.words(lowercase())))
			);
		}

		@Test
		@DisplayName("refuses the name of a built-in analyzer")
		void shouldRefuseTheNameOfABuiltInAnalyzer() {
			final InternalCatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(emptyCatalog());
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> builder
					.withFulltextAnalyzer(FulltextAnalyzerSchemaContract.TEXT, a -> a.words(lowercase()))
					.toInstance()
			);
		}

		@Test
		@DisplayName("modifies an analyzer only when its definition changes")
		void shouldModifyOnlyOnADefinitionChange() {
			final CatalogSchemaContract declared = schemaOf(
				emptyCatalog(),
				b -> b.withFulltextAnalyzer("codes", a -> a.words(lowercase()))
			);
			assertEquals(0, mutationsOf(
				declared,
				b -> b.withFulltextAnalyzer("codes", a -> a.words(lowercase()))).length
			);

			final LocalCatalogSchemaMutation[] modification = mutationsOf(
				declared,
				b -> b.withFulltextAnalyzer("codes", a -> a.words(asciiFolding()))
			);
			assertEquals(1, modification.length);
			assertInstanceOf(ModifyFulltextAnalyzerSchemaMutation.class, modification[0]);
		}

		@Test
		@DisplayName("refuses a null analysis step instead of failing on it")
		void shouldRefuseANullAnalysisStep() {
			final InternalCatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(emptyCatalog());
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> builder.withFulltextAnalyzer("codes", a -> a.text(lowercase(), null))
			);
		}

	}

	@Nested
	@DisplayName("Mutation combination in one builder")
	class Combination {

		@Test
		@DisplayName("folds a redefinition of a new analyzer into its creation")
		void shouldFoldARedefinitionIntoTheCreation() {
			final LocalCatalogSchemaMutation[] mutations = mutationsOf(
				emptyCatalog(),
				b -> b
					.withFulltextAnalyzer("codes", a -> a.words(lowercase()))
					.withFulltextAnalyzer("codes", a -> a.words(asciiFolding()))
			);

			assertEquals(1, mutations.length);
			final CreateFulltextAnalyzerSchemaMutation creation =
				assertInstanceOf(CreateFulltextAnalyzerSchemaMutation.class, mutations[0]);
			assertEquals(new FulltextAnalysisPhases(null, List.of(asciiFolding())), creation.getAllLanguages());
		}

		@Test
		@DisplayName("keeps a modification apart from the creation of a part the base schema already holds")
		void shouldKeepAModificationApartFromTheCreationOfAnExistingPart() {
			final CatalogSchemaContract declared = schemaOf(
				emptyCatalog(),
				b -> b
					.withFulltextLanguage("cs", l -> l.wordNumberSplit())
					.withFulltextResource("brands", wordList("puma"))
					.withFulltextAnalyzer("codes", a -> a.words(lowercase()))
			);
			// each creation restates the existing definition, so on its own it changes nothing
			final InternalCatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(
				declared,
				List.of(
					new CreateFulltextLanguageSchemaMutation(
						"cs", new AnalysisStep[]{standardTokenizer(), wordNumberSplit()}
					),
					new ModifyFulltextLanguageSchemaMutation("cs", new AnalysisStep[]{standardTokenizer()}),
					new CreateFulltextResourceSchemaMutation("brands", wordList("puma")),
					new ModifyFulltextResourceSchemaMutation("brands", wordList("nike")),
					new CreateFulltextAnalyzerSchemaMutation(
						"codes", null, new FulltextAnalysisPhases(null, List.of(lowercase())), Map.of()
					),
					new ModifyFulltextAnalyzerSchemaMutation(
						"codes", null, new FulltextAnalysisPhases(null, List.of(asciiFolding())), Map.of()
					)
				)
			);

			final CatalogSchemaContract schema = builder.toInstance();
			assertEquals(
				List.of(standardTokenizer()), schema.getFulltextLanguage("cs").orElseThrow().getWordSplitting()
			);
			assertEquals(wordList("nike"), schema.getFulltextResource("brands").orElseThrow().getContent());
			assertEquals(
				new FulltextAnalysisPhases(null, List.of(asciiFolding())),
				schema.getFulltextAnalyzer("codes").orElseThrow().getAllLanguages()
			);
		}

		@Test
		@DisplayName("keeps only the last of two modifications")
		void shouldKeepOnlyTheLastModification() {
			final CatalogSchemaContract declared = schemaOf(
				emptyCatalog(),
				b -> b.withFulltextAnalyzer("codes", a -> a.words(lowercase()))
			);
			final LocalCatalogSchemaMutation[] mutations = mutationsOf(
				declared,
				b -> b
					.withFulltextAnalyzer("codes", a -> a.words(asciiFolding()))
					.withFulltextAnalyzer("codes", a -> a.words(trim()))
			);

			assertEquals(1, mutations.length);
			final ModifyFulltextAnalyzerSchemaMutation modification =
				assertInstanceOf(ModifyFulltextAnalyzerSchemaMutation.class, mutations[0]);
			assertEquals(new FulltextAnalysisPhases(null, List.of(trim())), modification.getAllLanguages());
		}

		@Test
		@DisplayName("lets a removal absorb the earlier creation of the same analyzer")
		void shouldLetARemovalAbsorbTheCreation() {
			final InternalCatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(emptyCatalog());
			builder.withFulltextAnalyzer("codes", a -> a.words(lowercase())).withoutFulltextAnalyzer("codes");

			final LocalCatalogSchemaMutation[] mutations = builder.toMutation().orElseThrow().getSchemaMutations();
			assertEquals(1, mutations.length);
			assertInstanceOf(RemoveFulltextAnalyzerSchemaMutation.class, mutations[0]);
			assertTrue(builder.toInstance().getFulltextAnalyzers().isEmpty());
		}

		@Test
		@DisplayName("keeps a removal and a later creation of the same resource apart")
		void shouldKeepARemovalAndALaterCreationApart() {
			final CatalogSchemaContract declared = schemaOf(
				emptyCatalog(),
				b -> b.withFulltextResource("brands", wordList("puma"))
			);
			final InternalCatalogSchemaBuilder builder = new InternalCatalogSchemaBuilder(declared);
			builder.withoutFulltextResource("brands").withFulltextResource("brands", wordList("nike"));

			final LocalCatalogSchemaMutation[] mutations = builder.toMutation().orElseThrow().getSchemaMutations();
			assertEquals(2, mutations.length);
			assertInstanceOf(CreateFulltextResourceSchemaMutation.class, mutations[1]);
			assertEquals(
				wordList("nike"), builder.toInstance().getFulltextResource("brands").orElseThrow().getContent()
			);
		}

	}

	@Nested
	@DisplayName("References validated by the catalog schema")
	class Validation {

		@Test
		@DisplayName("refuses an analyzer reading a resource that does not exist")
		void shouldRefuseAMissingResource() {
			final CatalogSchemaContract schema = schemaOf(
				emptyCatalog(),
				b -> b.withFulltextAnalyzer("names", a -> a.words(lowercase(), stopWords(resource("brands"))))
			);

			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				schema::validate
			);
			assertTrue(exception.getMessage().contains("`brands`, which does not exist"), exception.getMessage());
		}

		@Test
		@DisplayName("accepts the built-in stop lists and declared word lists")
		void shouldAcceptBuiltInAndDeclaredWordLists() {
			final CatalogSchemaContract schema = schemaOf(
				emptyCatalog(),
				b -> b
					.withFulltextResource("brands", wordList("puma"))
					.withFulltextAnalyzer(
						"names",
						a -> a.words(
							lowercase(),
							stopWords(czechStopWords(), resource("brands")),
							czechStemming(protectedWords(resource("brands")))
						)
					)
			);

			assertDoesNotThrow(schema::validate);
		}

		@Test
		@DisplayName("refuses a step naming a blank resource instead of failing on it")
		void shouldRefuseABlankResourceName() {
			final CatalogSchemaContract schema = schemaOf(
				emptyCatalog(),
				b -> b.withFulltextAnalyzer(
					"names",
					a -> a.words(
						step(STOP_WORDS, parameter(WORDS, new String[]{null})),
						czechStemming(parameter(PROTECTED_WORDS, new String[]{" "}))
					)
				)
			);

			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				schema::validate
			);
			assertTrue(exception.getMessage().contains("`words` names a blank resource"), exception.getMessage());
			assertTrue(
				exception.getMessage().contains("`protected-words` names a blank resource"), exception.getMessage()
			);
		}

		@Test
		@DisplayName("leaves a parameter of another provider's step to the server, whatever its name")
		void shouldLeaveParametersOfOtherStepsToTheServer() {
			final CatalogSchemaContract schema = schemaOf(
				emptyCatalog(),
				b -> b.withFulltextAnalyzer(
					"names",
					a -> a.words(
						step("append-words", parameter(WORDS, new String[]{"sale"})),
						step("expand", parameter(SYNONYM_LISTS, "literal"))
					)
				)
			);

			assertDoesNotThrow(schema::validate);
		}

		@Test
		@DisplayName("refuses a resource of the wrong kind for the parameter reading it")
		void shouldRefuseAResourceOfTheWrongKind() {
			final CatalogSchemaContract schema = schemaOf(
				emptyCatalog(),
				b -> b
					.withFulltextResource("brands", wordList("puma"))
					.withFulltextAnalyzer("names", a -> a.words(lowercase(), synonyms(resource("brands"))))
			);

			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				schema::validate
			);
			assertTrue(exception.getMessage().contains("SYNONYM_LIST"), exception.getMessage());
		}

		@Test
		@DisplayName("refuses an analyzer based on one that does not exist")
		void shouldRefuseAMissingBase() {
			final CatalogSchemaContract schema = schemaOf(
				emptyCatalog(),
				b -> b.withFulltextAnalyzer("names", a -> a.basedOn("ghost"))
			);

			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				schema::validate
			);
			assertTrue(exception.getMessage().contains("`ghost`, which does not exist"), exception.getMessage());
		}

		@Test
		@DisplayName("refuses analyzers based on each other in a cycle")
		void shouldRefuseABasedOnCycle() {
			final CatalogSchemaContract schema = schemaOf(
				emptyCatalog(),
				b -> b
					.withFulltextAnalyzer("first", a -> a.basedOn("second"))
					.withFulltextAnalyzer("second", a -> a.basedOn("first"))
			);

			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				schema::validate
			);
			assertTrue(exception.getMessage().contains("cycle"), exception.getMessage());
		}

		@Test
		@DisplayName("refuses removing a resource an analyzer still reads, but not when the same update re-points it")
		void shouldRefuseRemovingAReadResourceUnlessRepointed() {
			final CatalogSchemaContract declared = schemaOf(
				emptyCatalog(),
				b -> b
					.withFulltextResource("brands", wordList("puma"))
					.withFulltextResource("labels", wordList("nike"))
					.withFulltextAnalyzer("names", a -> a.words(stopWords(resource("brands"))))
			);

			final CatalogSchemaContract removedOnly = schemaOf(declared, b -> b.withoutFulltextResource("brands"));
			assertThrows(InvalidSchemaMutationException.class, removedOnly::validate);

			final CatalogSchemaContract repointed = schemaOf(
				declared,
				b -> b
					.withFulltextAnalyzer("names", a -> a.words(stopWords(resource("labels"))))
					.withoutFulltextResource("brands")
			);
			assertDoesNotThrow(repointed::validate);
		}

		@Test
		@DisplayName("names the language whose override reads a resource that does not exist")
		void shouldNameTheLanguageOfAnOverrideReadingAMissingResource() {
			final CatalogSchemaContract schema = schemaOf(
				emptyCatalog(),
				b -> b.withFulltextAnalyzer(
					"names",
					a -> a.words(lowercase()).language("cs", l -> l.words(stopWords(resource("brands"))))
				)
			);

			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				schema::validate
			);
			assertTrue(exception.getMessage().contains("(language `cs`)"), exception.getMessage());
			assertTrue(exception.getMessage().contains("`brands`, which does not exist"), exception.getMessage());
		}

		@Test
		@DisplayName("reports every problem of the fulltext parts at once")
		void shouldReportEveryProblemAtOnce() {
			final CatalogSchemaContract schema = schemaOf(
				emptyCatalog(),
				b -> b
					.withFulltextAnalyzer("names", a -> a.words(stopWords(resource("brands"))))
					.withFulltextAnalyzer("titles", a -> a.basedOn("ghost"))
					.withFulltextAnalyzer("first", a -> a.basedOn("second"))
					.withFulltextAnalyzer("second", a -> a.basedOn("first"))
			);

			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				schema::validate
			);
			assertTrue(exception.getMessage().contains("`brands`, which does not exist"), exception.getMessage());
			assertTrue(exception.getMessage().contains("`ghost`, which does not exist"), exception.getMessage());
			assertTrue(exception.getMessage().contains("cycle"), exception.getMessage());
		}

		@Test
		@DisplayName("refuses a resource parameter of a built-in step that does not list resource names")
		void shouldRefuseAResourceParameterThatDoesNotListNames() {
			final CatalogSchemaContract schema = schemaOf(
				emptyCatalog(),
				b -> b
					.withFulltextResource("brands", wordList("puma"))
					.withFulltextAnalyzer("names", a -> a.words(step(STOP_WORDS, parameter(WORDS, "brands"))))
			);

			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				schema::validate
			);
			assertTrue(
				exception.getMessage().contains("parameter `words` must list resource names"), exception.getMessage()
			);
		}

	}

}
