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


package io.evitadb.api.requestResponse.schema.mutation.catalog;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.analysis.FulltextAnalysisPhases;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.test.TestConstants;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the catalog mutations that create, modify and remove the fulltext parts of a catalog schema - languages,
 * resources and analyzers - applied on their own rather than through the catalog schema builder. The builder never
 * creates a part that exists or modifies one that does not, so the refusals and the no-op paths of the mutations are
 * reachable only this way, for instance when a mutation arrives from a client or is replayed.
 */
@DisplayName("Fulltext language, resource and analyzer catalog mutations")
@Tag(CONTRACT)
@Tag(SCHEMA)
@Tag(FULLTEXT)
class FulltextCatalogSchemaMutationTest {

	/**
	 * The kinds of fulltext parts of a catalog schema, each with the name of the part {@link #declaredCatalog()} holds
	 * and the name of one it does not.
	 */
	enum Part {
		LANGUAGE("cs", "sk"),
		RESOURCE("brands", "labels"),
		ANALYZER("codes", "titles");

		/**
		 * The name of the part the declared catalog holds.
		 */
		private final String declaredName;
		/**
		 * The name of a part the declared catalog does not hold.
		 */
		private final String absentName;

		/**
		 * Creates the kind with its two names.
		 *
		 * @param declaredName the name of the part the declared catalog holds
		 * @param absentName   the name of a part the declared catalog does not hold
		 */
		Part(@Nonnull String declaredName, @Nonnull String absentName) {
			this.declaredName = declaredName;
			this.absentName = absentName;
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
	 * Creates a catalog schema holding one part of every kind, each with its declared definition.
	 *
	 * @return the schema
	 */
	@Nonnull
	private static CatalogSchemaContract declaredCatalog() {
		CatalogSchemaContract schema = emptyCatalog();
		for (final Part part : Part.values()) {
			schema = apply(schema, create(part, part.declaredName, false));
		}
		return schema;
	}

	/**
	 * Applies the mutation to the schema.
	 *
	 * @param schema   the schema to apply the mutation to
	 * @param mutation the mutation to apply
	 * @return the resulting schema
	 */
	@Nonnull
	private static CatalogSchemaContract apply(
		@Nonnull CatalogSchemaContract schema,
		@Nonnull LocalCatalogSchemaMutation mutation
	) {
		return mutation.mutate(schema, EmptyEntitySchemaAccessor.INSTANCE).updatedCatalogSchema();
	}

	/**
	 * Creates the mutation that creates a part.
	 *
	 * @param part        the kind of the part
	 * @param name        the name of the part
	 * @param alternative false for the declared definition, true for a different one
	 * @return the mutation
	 */
	@Nonnull
	private static LocalCatalogSchemaMutation create(@Nonnull Part part, @Nonnull String name, boolean alternative) {
		return switch (part) {
			case LANGUAGE -> new CreateFulltextLanguageSchemaMutation(name, wordSplitting(alternative));
			case RESOURCE -> new CreateFulltextResourceSchemaMutation(name, wordList(alternative ? "nike" : "puma"));
			case ANALYZER -> new CreateFulltextAnalyzerSchemaMutation(name, null, phases(alternative), Map.of());
		};
	}

	/**
	 * Creates the mutation that modifies a part.
	 *
	 * @param part        the kind of the part
	 * @param name        the name of the part
	 * @param alternative false for the declared definition, true for a different one
	 * @return the mutation
	 */
	@Nonnull
	private static LocalCatalogSchemaMutation modify(@Nonnull Part part, @Nonnull String name, boolean alternative) {
		return switch (part) {
			case LANGUAGE -> new ModifyFulltextLanguageSchemaMutation(name, wordSplitting(alternative));
			case RESOURCE -> new ModifyFulltextResourceSchemaMutation(name, wordList(alternative ? "nike" : "puma"));
			case ANALYZER -> new ModifyFulltextAnalyzerSchemaMutation(name, null, phases(alternative), Map.of());
		};
	}

	/**
	 * Creates the mutation that removes a part.
	 *
	 * @param part the kind of the part
	 * @param name the name of the part
	 * @return the mutation
	 */
	@Nonnull
	private static LocalCatalogSchemaMutation remove(@Nonnull Part part, @Nonnull String name) {
		return switch (part) {
			case LANGUAGE -> new RemoveFulltextLanguageSchemaMutation(name);
			case RESOURCE -> new RemoveFulltextResourceSchemaMutation(name);
			case ANALYZER -> new RemoveFulltextAnalyzerSchemaMutation(name);
		};
	}

	/**
	 * Returns the word splitting of a language.
	 *
	 * @param alternative false for the declared definition, true for a different one
	 * @return the splitting steps
	 */
	@Nonnull
	private static AnalysisStep[] wordSplitting(boolean alternative) {
		return alternative ?
			new AnalysisStep[]{standardTokenizer()} :
			new AnalysisStep[]{standardTokenizer(), wordNumberSplit()};
	}

	/**
	 * Returns the all-languages phases of an analyzer.
	 *
	 * @param alternative false for the declared definition, true for a different one
	 * @return the phases
	 */
	@Nonnull
	private static FulltextAnalysisPhases phases(boolean alternative) {
		return new FulltextAnalysisPhases(null, List.of(alternative ? asciiFolding() : lowercase()));
	}

	/**
	 * Returns the parts of one kind the schema holds.
	 *
	 * @param schema the schema to read
	 * @param part   the kind of the parts
	 * @return the parts keyed by name
	 */
	@Nonnull
	private static Map<String, ?> partsOf(@Nonnull CatalogSchemaContract schema, @Nonnull Part part) {
		return switch (part) {
			case LANGUAGE -> schema.getFulltextLanguages();
			case RESOURCE -> schema.getFulltextResources();
			case ANALYZER -> schema.getFulltextAnalyzers();
		};
	}

	/**
	 * Asserts that the schemas hold equal parts of every kind but one.
	 *
	 * @param expected the schema before the change
	 * @param actual   the schema after the change
	 * @param changed  the kind the change addressed
	 */
	private static void assertOtherPartsKept(
		@Nonnull CatalogSchemaContract expected,
		@Nonnull CatalogSchemaContract actual,
		@Nonnull Part changed
	) {
		for (final Part other : Part.values()) {
			if (other != changed) {
				assertEquals(partsOf(expected, other), partsOf(actual, other), other.name());
			}
		}
	}

	@Nested
	@DisplayName("Restating the current state")
	class RestatingTheCurrentState {

		/**
		 * Creating a part with the definition it already has is a no-op, not a conflict.
		 *
		 * @param part the kind of the part
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(Part.class)
		@DisplayName("returns the same schema when creating a part with its current definition")
		void shouldReturnTheSameSchemaWhenCreatingAPartWithItsCurrentDefinition(@Nonnull Part part) {
			final CatalogSchemaContract declared = declaredCatalog();

			final CatalogSchemaContract result = apply(declared, create(part, part.declaredName, false));

			assertSame(declared, result);
		}

		/**
		 * Modifying a part to the definition it already has changes nothing and bumps no version.
		 *
		 * @param part the kind of the part
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(Part.class)
		@DisplayName("returns the same schema when modifying a part to its current definition")
		void shouldReturnTheSameSchemaWhenModifyingAPartToItsCurrentDefinition(@Nonnull Part part) {
			final CatalogSchemaContract declared = declaredCatalog();

			final CatalogSchemaContract result = apply(declared, modify(part, part.declaredName, false));

			assertSame(declared, result);
		}

		/**
		 * Removing a part the catalog does not hold changes nothing and bumps no version.
		 *
		 * @param part the kind of the part
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(Part.class)
		@DisplayName("returns the same schema when removing a part the catalog does not hold")
		void shouldReturnTheSameSchemaWhenRemovingAnAbsentPart(@Nonnull Part part) {
			final CatalogSchemaContract declared = declaredCatalog();

			final CatalogSchemaContract result = apply(declared, remove(part, part.absentName));

			assertSame(declared, result);
		}

	}

	@Nested
	@DisplayName("Refusals")
	class Refusals {

		/**
		 * Creating a part that exists with a different definition is refused and points the caller to modification.
		 *
		 * @param part the kind of the part
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(Part.class)
		@DisplayName("refuses creating a part that exists with a different definition")
		void shouldRefuseCreatingAPartThatExistsWithADifferentDefinition(@Nonnull Part part) {
			final CatalogSchemaContract declared = declaredCatalog();
			final LocalCatalogSchemaMutation creation = create(part, part.declaredName, true);

			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> apply(declared, creation)
			);
			assertTrue(exception.getMessage().contains("already exists"), exception.getMessage());
			assertTrue(exception.getMessage().contains("modify it instead"), exception.getMessage());
		}

		/**
		 * Modifying a part the catalog does not hold is refused and points the caller to creation.
		 *
		 * @param part the kind of the part
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(Part.class)
		@DisplayName("refuses modifying a part the catalog does not hold")
		void shouldRefuseModifyingAnAbsentPart(@Nonnull Part part) {
			final CatalogSchemaContract declared = declaredCatalog();
			final LocalCatalogSchemaMutation modification = modify(part, part.absentName, false);

			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> apply(declared, modification)
			);
			assertTrue(exception.getMessage().contains("does not exist"), exception.getMessage());
			assertTrue(exception.getMessage().contains("create it instead"), exception.getMessage());
		}

	}

	@Nested
	@DisplayName("Applying a change")
	class ApplyingAChange {

		/**
		 * A creation yields a new schema version holding the new part, keeps the parts of the other kinds and leaves
		 * the schema it was applied to untouched.
		 *
		 * @param part the kind of the part
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(Part.class)
		@DisplayName("creates a part in a new schema version and keeps the parts of the other kinds")
		void shouldCreateAPartInANewVersionAndKeepTheOtherParts(@Nonnull Part part) {
			final CatalogSchemaContract declared = declaredCatalog();

			final CatalogSchemaContract result = apply(declared, create(part, part.absentName, false));

			assertEquals(declared.version() + 1, result.version());
			assertEquals(Set.of(part.declaredName, part.absentName), partsOf(result, part).keySet());
			assertOtherPartsKept(declared, result, part);
			assertEquals(Set.of(part.declaredName), partsOf(declared, part).keySet());
		}

		/**
		 * A removal yields a new schema version without the part, keeps the parts of the other kinds and leaves the
		 * schema it was applied to untouched.
		 *
		 * @param part the kind of the part
		 */
		@ParameterizedTest(name = "{0}")
		@EnumSource(Part.class)
		@DisplayName("removes a part in a new schema version and keeps the parts of the other kinds")
		void shouldRemoveAPartInANewVersionAndKeepTheOtherParts(@Nonnull Part part) {
			final CatalogSchemaContract declared = declaredCatalog();

			final CatalogSchemaContract result = apply(declared, remove(part, part.declaredName));

			assertEquals(declared.version() + 1, result.version());
			assertTrue(partsOf(result, part).isEmpty());
			assertOtherPartsKept(declared, result, part);
			assertEquals(Set.of(part.declaredName), partsOf(declared, part).keySet());
		}

	}

	@Nested
	@DisplayName("Language codes and word splitting")
	class Languages {

		/**
		 * The language mutations copy the step array, so a caller reusing the array cannot change a mutation that was
		 * already created from it.
		 */
		@Test
		@DisplayName("is not affected by later changes to the step array it was created from")
		void shouldNotBeAffectedByLaterChangesToTheStepArray() {
			final AnalysisStep[] steps = {standardTokenizer(), wordNumberSplit()};
			final CreateFulltextLanguageSchemaMutation creation = new CreateFulltextLanguageSchemaMutation("cs", steps);
			final ModifyFulltextLanguageSchemaMutation modification =
				new ModifyFulltextLanguageSchemaMutation("cs", steps);

			steps[1] = lowercase();

			final AnalysisStep[] original = {standardTokenizer(), wordNumberSplit()};
			assertArrayEquals(original, creation.getWordSplitting());
			assertArrayEquals(original, modification.getWordSplitting());
		}

		/**
		 * Every language mutation accepts only a bare lower-case ISO 639 code, as `Locale#getLanguage()` returns it.
		 *
		 * @param language the code to refuse
		 */
		@ParameterizedTest(name = "\"{0}\"")
		@ValueSource(strings = {"", "CS", "cs-CZ"})
		@DisplayName("refuses a blank, upper-case or full language tag at construction")
		void shouldRefuseALanguageThatIsNotABareCode(@Nonnull String language) {
			final AnalysisStep[] steps = {standardTokenizer()};

			assertThrows(
				InvalidSchemaMutationException.class,
				() -> new CreateFulltextLanguageSchemaMutation(language, steps)
			);
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> new ModifyFulltextLanguageSchemaMutation(language, steps)
			);
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> new RemoveFulltextLanguageSchemaMutation(language)
			);
		}

	}

}
