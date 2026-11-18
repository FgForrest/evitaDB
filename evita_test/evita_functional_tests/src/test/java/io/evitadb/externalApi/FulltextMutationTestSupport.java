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


package io.evitadb.externalApi;

import java.math.BigDecimal;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSearchMode;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.analysis.*;
import io.evitadb.api.requestResponse.schema.mutation.SchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.SetAssociatedDataSchemaSearchableMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateGlobalAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaSearchableMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.*;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReflectedReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedBucketedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedHistogramIndexDefinition;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceSearchMode;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaSearchableThroughMutation;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.StringUtils;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static java.math.BigDecimal.valueOf;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;

/** Java-origin mutations shared by transport round-trip and CDC regressions. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class FulltextMutationTestSupport {
	/** Supplies every in-scope mutation with absent, zero and positive numeric settings. */
	@Nonnull
	public static Stream<SchemaMutation> mutations() {
		return Stream.<BigDecimal>of(null, BigDecimal.ZERO, valueOf(0.0000001),
			valueOf(12345678.5), new BigDecimal("0.123456789012345678901234567891"))
			.flatMap(FulltextMutationTestSupport::mutations);
	}

	/**
	 * Supplies every in-scope mutation with its optional fulltext settings absent - no analyzer, no length pivot,
	 * no weight, no text expression - once with a null declaration and once with an explicitly empty one, so a
	 * converter that fills a default in or merges the two states is caught. The two states differ in meaning only
	 * for `SetReferenceSchemaSearchableThroughMutation`, where null keeps the composition modes and an empty array
	 * clears them; the create mutations read either as no composition, and the attribute and associated-data
	 * mutations turn a null scope array into an empty one at construction (searchable nowhere). One more mutation
	 * clears the composition modes together with an explicit zero weight, so a converter that drops a zero weight
	 * next to an empty declaration is caught too.
	 */
	@Nonnull
	public static Stream<SchemaMutation> sparseMutations() {
		return Stream.concat(
			Stream.concat(
				sparseMutations(null, null), sparseMutations(Scope.NO_SCOPE, ScopedReferenceSearchMode.EMPTY)
			),
			Stream.of(new SetReferenceSchemaSearchableThroughMutation(
				"related", ScopedReferenceSearchMode.EMPTY, valueOf(0)))
		);
	}

	/** Creates all mutation families with the given declarations and every other fulltext setting absent. */
	@Nonnull
	private static Stream<SchemaMutation> sparseMutations(
		@Nullable Scope[] scopes, @Nullable ScopedReferenceSearchMode[] modes
	) {
		return Stream.of(
			new SetAttributeSchemaSearchableMutation("text", scopes, null, null, null),
			new SetAssociatedDataSchemaSearchableMutation("text", scopes, null, null, null, null),
			new SetReferenceSchemaSearchableThroughMutation("related", modes, null),
			new CreateAttributeSchemaMutation(
				"text", null, null, null, null, null, scopes, null, true, true, false, String.class,
				null, 0, ConflictResolutionOverride.INHERITED, null, null, null, null),
			new CreateGlobalAttributeSchemaMutation(
				"text", null, null, null, null, null, null, scopes, null, true, true, false, String.class,
				null, 0, ConflictResolutionOverride.INHERITED, null, null, null, null),
			new CreateAssociatedDataSchemaMutation(
				"text", null, null, ComplexDataObject.class, true, true, scopes,
				null, ConflictResolutionOverride.INHERITED, null, null, null),
			new CreateReferenceSchemaMutation(
				"related", null, null, Cardinality.ZERO_OR_MORE, "target", true, null, false,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING)
				}, null, null, null, ScopedHistogramIndexDefinition.EMPTY, ScopedBucketedPartially.EMPTY,
				ConflictResolutionOverride.INHERITED, modes, null),
			new CreateReflectedReferenceSchemaMutation(
				"related", null, null, Cardinality.ZERO_OR_MORE, "target", "original", null, null, null, null,
				ScopedHistogramIndexDefinition.EMPTY, ScopedBucketedPartially.EMPTY,
				AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED, new String[0], modes, null)
		);
	}

	/**
	 * Creates all mutation families with non-default fulltext declarations.
	 * @param weight decimal ranking contribution, or null to leave the weight unspecified
	 * @return all create and set mutation families carrying the supplied decimal
	 */
	@Nonnull
	public static Stream<SchemaMutation> mutations(@Nullable BigDecimal weight) {
		final Scope[] scopes = {Scope.LIVE, Scope.ARCHIVED};
		final ScopedReferenceSearchMode[] modes = {
			new ScopedReferenceSearchMode(Scope.LIVE, ReferenceSearchMode.COMPOSITION),
			new ScopedReferenceSearchMode(Scope.ARCHIVED, ReferenceSearchMode.COMPOSITION)
		};
		return Stream.of(
			new SetAttributeSchemaSearchableMutation("text", scopes, "text", 73, weight),
			new SetAssociatedDataSchemaSearchableMutation(
				"text", scopes, ExpressionFactory.parse("$value.title"), "text", 73, weight),
			new SetReferenceSchemaSearchableThroughMutation("related", modes, weight),
			new CreateAttributeSchemaMutation(
				"text", null, null, null, null, null, scopes, null, true, true, false, String.class,
				null, 0, ConflictResolutionOverride.INHERITED, null, "text", 73, weight),
			new CreateGlobalAttributeSchemaMutation(
				"text", null, null, null, null, null, null, scopes, null, true, true, false, String.class,
				null, 0, ConflictResolutionOverride.INHERITED, null, "text", 73, weight),
			new CreateAssociatedDataSchemaMutation(
				"text", null, null, ComplexDataObject.class, true, true, scopes,
				ExpressionFactory.parse("$value.title"), ConflictResolutionOverride.INHERITED, "text", 73, weight),
			new CreateReferenceSchemaMutation(
				"related", null, null, Cardinality.ZERO_OR_MORE, "target", true, null, false,
				new ScopedReferenceIndexType[]{
					new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING),
					new ScopedReferenceIndexType(Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING)
				}, null, null, null, ScopedHistogramIndexDefinition.EMPTY, ScopedBucketedPartially.EMPTY,
				ConflictResolutionOverride.INHERITED, modes, weight),
			new CreateReflectedReferenceSchemaMutation(
				"related", null, null, Cardinality.ZERO_OR_MORE, "target", "original", null, null, null, null,
				ScopedHistogramIndexDefinition.EMPTY, ScopedBucketedPartially.EMPTY,
				AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT, new String[0], modes, weight)
		);
	}
	/**
	 * Supplies both exact sample bounds and non-ASCII content for transport and persistence fixtures.
	 *
	 * @return 100 distinct texts in declaration order, including a 1,000-character text
	 */
	@Nonnull
	public static List<String> analyzerSamples() {
		return java.util.stream.IntStream.range(0, 100).mapToObj(index -> switch (index) {
			case 0 -> "Příliš žluťoučký kůň 東京";
			case 1 -> "ž".repeat(1_000);
			default -> "sample " + index;
		}).toList();
	}

	/**
	 * Supplies all nine catalog mutation families with non-default declared content, phases and author samples.
	 *
	 * @return declaration mutations including analyzer samples at both inclusive bounds
	 */
	@Nonnull
	public static Stream<SchemaMutation> catalogMutations() {
		final AnalysisStep[] splitting = {standardTokenizer(), wordNumberSplit()};
		final FulltextAnalysisPhases phases = new FulltextAnalysisPhases(List.of(htmlStrip()), List.of(
			lowercase(), stopWords(englishStopWords()), englishStemming(protectedWords(englishStopWords()))));
		final Map<String, FulltextAnalysisPhases> languages = Map.of(
			"en", new FulltextAnalysisPhases(null, List.of()),
			"de", new FulltextAnalysisPhases(List.of(), null));
		return Stream.of(
			new CreateFulltextLanguageSchemaMutation("en", splitting),
			new ModifyFulltextLanguageSchemaMutation("en", splitting),
			new RemoveFulltextLanguageSchemaMutation("en"),
			new CreateFulltextResourceSchemaMutation("terms", wordList("and", "the")),
			new ModifyFulltextResourceSchemaMutation("terms", synonymList(
				synonymGroup("quick", "fast"), synonymGroup("bright", "light"))),
			new RemoveFulltextResourceSchemaMutation("terms"),
			new CreateFulltextAnalyzerSchemaMutation("custom", "text", phases, languages, analyzerSamples()),
			new ModifyFulltextAnalyzerSchemaMutation("custom", "text", phases, languages, analyzerSamples()),
			new RemoveFulltextAnalyzerSchemaMutation("custom")
		);
	}

	/**
	 * Keeps empty content, undefined phases, an absent base analyzer and empty language overrides distinct.
	 *
	 * @return sparse catalog mutations with empty author sample lists
	 */
	@Nonnull
	public static Stream<SchemaMutation> sparseCatalogMutations() {
		return Stream.of(
			new CreateFulltextLanguageSchemaMutation("en", new AnalysisStep[0]),
			new ModifyFulltextLanguageSchemaMutation("en", new AnalysisStep[0]),
			new RemoveFulltextLanguageSchemaMutation("en"),
			new CreateFulltextResourceSchemaMutation("terms", wordList()),
			new ModifyFulltextResourceSchemaMutation("terms", synonymList()),
			new RemoveFulltextResourceSchemaMutation("terms"),
			new CreateFulltextAnalyzerSchemaMutation(
				"custom", null, FulltextAnalysisPhases.UNDEFINED, Map.of(), List.of()),
			new ModifyFulltextAnalyzerSchemaMutation(
				"custom", null, new FulltextAnalysisPhases(List.of(), List.of()), Map.of(), List.of()),
			new RemoveFulltextAnalyzerSchemaMutation("custom")
		);
	}

	/** Supplies populated and sparse declarations of both the entity and the catalog mutation routes. */
	@Nonnull
	public static Stream<SchemaMutation> roundTripMutations() {
		return Stream.concat(Stream.concat(mutations(), sparseMutations()),
			Stream.concat(catalogMutations(), sparseCatalogMutations()));
	}

	/** Translates output step descriptions to the provider selector used by schema mutation inputs. */
	@Nullable
	public static Object typedInput(@Nullable Object value) {
		if (value instanceof Map<?, ?> map) {
			if (map.containsKey("step") && map.containsKey("parameters")) {
				final Map<?, ?> parameters = (Map<?, ?>) map.get("parameters");
				final Map<String, Object> converted = new LinkedHashMap<>();
				parameters.forEach((name, parameter) -> converted.put(
					StringUtils.toCamelCase(name.toString()), typedInput(parameter)));
				return Map.of(StringUtils.toCamelCase(map.get("step").toString()),
					converted.isEmpty() ? Boolean.TRUE : converted);
			}
			final Map<String, Object> converted = new LinkedHashMap<>();
			map.forEach((name, property) -> converted.put(name.toString(), typedInput(property)));
			return converted;
		} else if (value instanceof List<?> list) {
			return list.stream().map(FulltextMutationTestSupport::typedInput).toList();
		}
		return value;
	}

}
