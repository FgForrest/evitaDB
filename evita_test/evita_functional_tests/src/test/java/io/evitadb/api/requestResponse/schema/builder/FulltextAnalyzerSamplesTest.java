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
import io.evitadb.api.requestResponse.schema.analysis.FulltextAnalysisPhases;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.FulltextAnalyzerSchema;
import io.evitadb.api.requestResponse.schema.mutation.catalog.CreateFulltextAnalyzerSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyFulltextAnalyzerSchemaMutation;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.evitadb.externalApi.FulltextMutationTestSupport.analyzerSamples;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies samples as declaration data, without running an analyzer on their content. */
@DisplayName("Fulltext analyzer author samples")
@Tag(CONTRACT)
@Tag(SCHEMA)
@Tag(FULLTEXT)
class FulltextAnalyzerSamplesTest {
	/**
	 * Creates a catalog without entity dependencies for mutation and builder checks.
	 *
	 * @return the initial catalog schema
	 */
	@Nonnull
	private static CatalogSchema emptyCatalog() {
		return CatalogSchema._internalBuild("sample", NamingConvention.generate("sample"), null,
			EnumSet.allOf(CatalogEvolutionMode.class), EmptyEntitySchemaAccessor.INSTANCE);
	}

	/**
	 * Constructs a declaration through the real editor, including its DTO validation.
	 *
	 * @param samples texts replacing the initial empty list
	 * @return the declaration with its own immutable samples
	 */
	@Nonnull
	private static FulltextAnalyzerSchema declaration(@Nonnull String... samples) {
		final FulltextAnalyzerSchemaBuilder builder = new FulltextAnalyzerSchemaBuilder();
		builder.basedOn("text").samples(samples);
		return builder.toSchema("custom");
	}

	@Nested
	@DisplayName("Editor and value semantics")
	class Editor {
		/** Replacement preserves order, and subsequent calls can clear the declaration. */
		@Test
		@DisplayName("replaces, deduplicates and clears samples")
		void shouldReplaceDedupeAndClearSamples() {
			final FulltextAnalyzerSchemaBuilder builder = new FulltextAnalyzerSchemaBuilder();
			builder.basedOn("text").samples("old").samples("second", "first", "second", " first ");
			assertEquals(List.of("second", "first", " first "), builder.toSchema("custom").getSamples());
			builder.samples();
			assertEquals(List.of(), builder.toSchema("custom").getSamples());
		}

		/** The declaration copies caller-owned storage and exposes an immutable ordered list. */
		@Test
		@DisplayName("copies caller storage and includes samples in value semantics")
		void shouldCopySamplesAndIncludeThemInValueSemantics() {
			final String[] input = {"first", "second"};
			final FulltextAnalyzerSchema schema = declaration(input);
			input[0] = "changed";
			assertEquals(List.of("first", "second"), schema.getSamples());
			assertThrows(UnsupportedOperationException.class, () -> schema.getSamples().add("third"));
			assertEquals(schema, declaration("first", "second", "first"));
			assertEquals(schema.hashCode(), declaration("first", "second").hashCode());
			assertNotEquals(schema, declaration("second", "first"));
			assertTrue(schema.toString().contains("samples=[first, second]"));
		}

		/** A base analyzer supplies phases only; its samples remain its own declaration data. */
		@Test
		@DisplayName("keeps derived analyzer samples independent")
		void shouldNotInheritSamples() {
			final CatalogSchemaContract schema = new InternalCatalogSchemaBuilder(emptyCatalog())
				.withFulltextAnalyzer("base", analyzer -> analyzer.basedOn("text").samples("base text"))
				.withFulltextAnalyzer("derived", analyzer -> analyzer.basedOn("base"))
				.toInstance();
			assertEquals(List.of("base text"), schema.getFulltextAnalyzer("base").orElseThrow().getSamples());
			assertTrue(schema.getFulltextAnalyzer("derived").orElseThrow().getSamples().isEmpty());
		}
	}

	@Nested
	@DisplayName("Construction limits")
	class Limits {
		/** Both inclusive bounds accept non-ASCII text, with duplicates counted only once. */
		@Test
		@DisplayName("accepts exactly 100 samples and exactly 1,000 characters")
		void shouldAcceptInclusiveBounds() {
			assertEquals(100, FulltextAnalyzerSchemaContract.MAX_SAMPLES);
			assertEquals(1_000, FulltextAnalyzerSchemaContract.MAX_SAMPLE_LENGTH);
			final List<String> input = new ArrayList<>(analyzerSamples());
			input.add(input.getFirst());
			assertEquals(analyzerSamples(), declaration(input.toArray(String[]::new)).getSamples());
			assertEquals(analyzerSamples(), new CreateFulltextAnalyzerSchemaMutation(
				"custom", "text", FulltextAnalysisPhases.UNDEFINED, Map.of(), input).getSamples());
			assertEquals(analyzerSamples(), new ModifyFulltextAnalyzerSchemaMutation(
				"custom", "text", FulltextAnalysisPhases.UNDEFINED, Map.of(), input).getSamples());
		}

		/**
		 * Supplies one input per refusal; a null entry and a blank entry share the refusal reason.
		 *
		 * @return the case label, the samples and a message fragment that only the expected refusal contains
		 */
		@Nonnull
		static Stream<Arguments> invalidSamples() {
			final List<String> tooMany = new ArrayList<>(analyzerSamples());
			tooMany.add("overflow");
			return Stream.of(
				Arguments.of("null list", null, "samples must not be null."),
				Arguments.of("null entry", Arrays.asList((String) null), "null or blank"),
				Arguments.of("empty entry", List.of(""), "null or blank"),
				Arguments.of("whitespace entry", List.of(" \t\n"), "null or blank"),
				Arguments.of("entry over the length limit", List.of("x".repeat(1_001)), "must not exceed"),
				Arguments.of("more distinct entries than allowed", tooMany, "more than 100 distinct")
			);
		}

		/** Invalid collection entries and either exceeded bound are usage errors at construction. */
		@ParameterizedTest(name = "{0}")
		@MethodSource("invalidSamples")
		@DisplayName("refuses null, blank and excessive samples in every construction path")
		void shouldRefuseInvalidSamples(
			@Nonnull String label, @Nullable List<String> samples, @Nonnull String reason
		) {
			final List<Executable> constructions = List.of(
				() -> declaration(samples == null ? null : samples.toArray(String[]::new)),
				() -> FulltextAnalyzerSchema._internalBuild(
					"custom", "text", FulltextAnalysisPhases.UNDEFINED, Map.of(), samples),
				() -> new CreateFulltextAnalyzerSchemaMutation(
					"custom", "text", FulltextAnalysisPhases.UNDEFINED, Map.of(), samples),
				() -> new ModifyFulltextAnalyzerSchemaMutation(
					"custom", "text", FulltextAnalysisPhases.UNDEFINED, Map.of(), samples)
			);

			for (final Executable construction : constructions) {
				final String message = assertThrows(InvalidSchemaMutationException.class, construction).getMessage();
				assertTrue(message.contains(reason), message);
			}
		}
	}

	@Nested
	@DisplayName("Mutation combinations and versions")
	class Mutations {
		/** A sample-only edit is visible to mutation equality, catalog comparison and versioning. */
		@Test
		@DisplayName("bumps the schema only when samples actually change")
		void shouldBumpVersionOnlyWhenSamplesChange() {
			final CatalogSchemaContract empty = emptyCatalog();
			final CreateFulltextAnalyzerSchemaMutation create = new CreateFulltextAnalyzerSchemaMutation(
				"custom", "text", FulltextAnalysisPhases.UNDEFINED, Map.of(), List.of("first"));
			final CatalogSchemaContract before = create.mutate(empty, EmptyEntitySchemaAccessor.INSTANCE)
				.updatedCatalogSchema();
			final ModifyFulltextAnalyzerSchemaMutation modify = new ModifyFulltextAnalyzerSchemaMutation(
				"custom", "text", FulltextAnalysisPhases.UNDEFINED, Map.of(), List.of("second"));
			final CatalogSchemaContract after = modify.mutate(before, EmptyEntitySchemaAccessor.INSTANCE)
				.updatedCatalogSchema();
			assertEquals(before.version() + 1, after.version());
			assertTrue(before.differsFrom(after));
			assertSame(after, modify.mutate(after, EmptyEntitySchemaAccessor.INSTANCE).updatedCatalogSchema());
			assertEquals(List.of("second"), after.getFulltextAnalyzer("custom").orElseThrow().getSamples());
			assertNotEquals(modify, new ModifyFulltextAnalyzerSchemaMutation(
				"custom", "text", FulltextAnalysisPhases.UNDEFINED, Map.of(), List.of("first")));
			assertThrows(InvalidSchemaMutationException.class,
				() -> create.mutate(after, EmptyEntitySchemaAccessor.INSTANCE));
		}

		/** The catalog builder emits a real sample-only modification and suppresses an identical definition. */
		@Test
		@DisplayName("detects sample-only changes in the catalog builder")
		void shouldDetectSampleOnlyBuilderChanges() {
			final CatalogSchemaContract before = new InternalCatalogSchemaBuilder(emptyCatalog())
				.withFulltextAnalyzer("custom", analyzer -> analyzer.basedOn("text").samples("first"))
				.toInstance();
			final InternalCatalogSchemaBuilder changed = new InternalCatalogSchemaBuilder(before);
			changed.withFulltextAnalyzer("custom", analyzer -> analyzer.basedOn("text").samples("second"));
			final CatalogSchemaContract after = changed.toInstance();
			assertEquals(before.version() + 1, after.version());
			assertEquals(List.of("second"), after.getFulltextAnalyzer("custom").orElseThrow().getSamples());
			final InternalCatalogSchemaBuilder identical = new InternalCatalogSchemaBuilder(after);
			identical.withFulltextAnalyzer("custom", analyzer -> analyzer.basedOn("text").samples("second", "second"));
			assertTrue(identical.toMutation().isEmpty());
			assertEquals(after.version(), identical.toInstance().version());
		}

		/** Folding creation and replacement retains the last complete sample list, including an empty one. */
		@Test
		@DisplayName("retains the final samples when combining mutations")
		void shouldKeepFinalSamplesWhenCombining() {
			final CatalogSchemaContract empty = emptyCatalog();
			final CreateFulltextAnalyzerSchemaMutation create = new CreateFulltextAnalyzerSchemaMutation(
				"custom", "text", FulltextAnalysisPhases.UNDEFINED, Map.of(), List.of("first"));
			final ModifyFulltextAnalyzerSchemaMutation modify = new ModifyFulltextAnalyzerSchemaMutation(
				"custom", "text", FulltextAnalysisPhases.UNDEFINED, Map.of(), List.of("second"));
			final InternalCatalogSchemaBuilder builder =
				new InternalCatalogSchemaBuilder(empty, List.of(create, modify));
			assertEquals(List.of("second"), builder.toInstance().getFulltextAnalyzer("custom").orElseThrow()
				.getSamples());
			final ModifyFulltextAnalyzerSchemaMutation clear = new ModifyFulltextAnalyzerSchemaMutation(
				"custom", "text", FulltextAnalysisPhases.UNDEFINED, Map.of(), List.of());
			final InternalCatalogSchemaBuilder cleared =
				new InternalCatalogSchemaBuilder(empty, List.of(create, modify, clear));
			assertTrue(cleared.toInstance().getFulltextAnalyzer("custom").orElseThrow().getSamples().isEmpty());
		}
	}
}
