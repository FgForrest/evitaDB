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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCaptureRequest;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.analysis.*;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.SchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.SetAssociatedDataSchemaSearchableMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateGlobalAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ReferenceAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaSearchableMutation;
import io.evitadb.api.requestResponse.schema.mutation.catalog.*;
import io.evitadb.api.requestResponse.schema.mutation.catalog.ModifyEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReflectedReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaSearchableThroughMutation;
import io.evitadb.core.Evita;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.Scope;
import io.evitadb.externalApi.api.catalog.mutation.TestMutationResolvingExceptionFactory;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.DelegatingEntitySchemaMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.DelegatingLocalCatalogSchemaMutationConverter;
import io.evitadb.externalApi.api.catalog.schemaApi.resolver.mutation.LocalCatalogSchemaMutationInputAggregateConverter;
import io.evitadb.externalApi.api.resolver.mutation.PassThroughMutationObjectMapper;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.math.BigDecimal.valueOf;
import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shared real-engine preparation and payload assertions for fulltext mutation transport tests. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class FulltextMutationFunctionalTestSupport {
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Set<String> ENUM_FIELDS = Set.of(
		"kind", "type", "scope", "mode", "cardinality", "uniquenessType", "conflictResolutionOverride",
		"attributeInheritanceBehavior",
		"indexType", "indexedComponents", "searchableInScopes", "filterableInScopes", "sortableInScopes",
		"facetedInScopes"
	);
	/** The zero weight of the fixture, rendered as the decimal string the mutation output uses. */
	private static final JsonNode ZERO_WEIGHT = TextNode.valueOf("0");
	/** Fields of the attribute families, as built by the fixture with a zero weight. */
	private static final Map<String, JsonNode> ATTRIBUTE_FIELDS = Map.of(
		"searchableInScopes", MAPPER.valueToTree(List.of("LIVE", "ARCHIVED")),
		"analyzer", TextNode.valueOf("text"),
		"lengthPivot", IntNode.valueOf(73),
		"searchWeight", ZERO_WEIGHT
	);
	/** Fields of the associated-data families: the attribute fields plus the raw text expression. */
	private static final Map<String, JsonNode> ASSOCIATED_DATA_FIELDS = Map.of(
		"searchableInScopes", ATTRIBUTE_FIELDS.get("searchableInScopes"),
		"analyzer", ATTRIBUTE_FIELDS.get("analyzer"),
		"lengthPivot", ATTRIBUTE_FIELDS.get("lengthPivot"),
		"searchWeight", ZERO_WEIGHT,
		"textExpression", TextNode.valueOf("$value.title")
	);
	/** Composition modes of the reference families, as built by the fixture. */
	private static final JsonNode COMPOSITION_MODES = MAPPER.valueToTree(List.of(
		Map.of("scope", "LIVE", "mode", "COMPOSITION"),
		Map.of("scope", "ARCHIVED", "mode", "COMPOSITION")
	));

	/** One mutation route, including global and reference attribute ownership. */
	public record MutationCase(@Nonnull SchemaMutation mutation, boolean global) {}

	/** Supplies all eight families plus the global setter and both reference attribute wrappers. */
	@Nonnull
	public static Stream<MutationCase> cases() {
		final List<SchemaMutation> mutations = FulltextMutationTestSupport.mutations(valueOf(0)).toList();
		return Stream.concat(
			Stream.concat(mutations.stream().map(mutation -> new MutationCase(
				mutation, mutation instanceof CreateGlobalAttributeSchemaMutation)),
				FulltextMutationTestSupport.catalogMutations().map(mutation -> new MutationCase(mutation, true))),
			Stream.of(
				new MutationCase(mutations.get(0), true),
				new MutationCase(new ModifyReferenceAttributeSchemaMutation(
					"related", (ReferenceAttributeSchemaMutation) mutations.get(0)), false),
				new MutationCase(new ModifyReferenceAttributeSchemaMutation(
					"related", (CreateAttributeSchemaMutation) mutations.get(3)), false)
			)
		);
	}

	/** Supplies only the nine catalog fulltext mutations, each applied at catalog level. */
	@Nonnull
	public static Stream<MutationCase> catalogCases() {
		return FulltextMutationTestSupport.catalogMutations().map(mutation -> new MutationCase(mutation, true));
	}

	/**
	 * Creates an isolated catalog with only the prerequisites of the mutation under test.
	 *
	 * @param evita the embedded engine behind the tested API
	 * @param testCase the mutation whose prerequisites must exist
	 * @return the catalog containing those prerequisites
	 */
	@Nonnull
	public static String prepare(@Nonnull Evita evita, @Nonnull MutationCase testCase) {
		final String catalog = "fulltext" + UUID.randomUUID().toString().replace("-", "");
		evita.applyMutation(new CreateCatalogSchemaMutation(catalog)).onCompletion().toCompletableFuture().join();
		evita.updateCatalog(catalog, session -> {
			session.defineEntitySchema("owner").updateVia(session);
			session.defineEntitySchema("target")
				.withReferenceToEntity("original", "owner", Cardinality.ZERO_OR_MORE,
					reference -> reference.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED))
				.updateVia(session);
			final SchemaMutation mutation = testCase.mutation();
			if (mutation instanceof ModifyFulltextLanguageSchemaMutation ||
				mutation instanceof RemoveFulltextLanguageSchemaMutation) {
				session.updateCatalogSchema(new CreateFulltextLanguageSchemaMutation("en",
					new AnalysisStep[]{standardTokenizer()}));
			} else if (mutation instanceof ModifyFulltextResourceSchemaMutation ||
				mutation instanceof RemoveFulltextResourceSchemaMutation) {
				session.updateCatalogSchema(new CreateFulltextResourceSchemaMutation("terms", wordList()));
			} else if (mutation instanceof ModifyFulltextAnalyzerSchemaMutation ||
				mutation instanceof RemoveFulltextAnalyzerSchemaMutation) {
				session.updateCatalogSchema(new CreateFulltextAnalyzerSchemaMutation(
					"custom", "text", FulltextAnalysisPhases.UNDEFINED, Map.of(), List.of()));
			} else if (testCase.global() && mutation instanceof SetAttributeSchemaSearchableMutation) {
				session.getCatalogSchema().openForWrite()
					.withAttribute("text", String.class, attribute -> attribute.localized().nullable())
					.updateVia(session);
			} else if (mutation instanceof SetAttributeSchemaSearchableMutation) {
				session.getEntitySchemaOrThrow("owner").openForWrite()
					.withAttribute("text", String.class, attribute -> attribute.localized().nullable())
					.updateVia(session);
			} else if (mutation instanceof SetAssociatedDataSchemaSearchableMutation) {
				session.getEntitySchemaOrThrow("owner").openForWrite()
					.withAssociatedData("text", ComplexDataObject.class, data -> data.localized().nullable())
					.updateVia(session);
			} else if (mutation instanceof SetReferenceSchemaSearchableThroughMutation ||
				mutation instanceof ModifyReferenceAttributeSchemaMutation) {
				session.getEntitySchemaOrThrow("owner").openForWrite()
					.withReferenceToEntity("related", "target", Cardinality.ZERO_OR_MORE, reference -> {
						reference.indexedForFilteringInScope(Scope.LIVE, Scope.ARCHIVED);
						if (mutation instanceof ModifyReferenceAttributeSchemaMutation wrapper &&
							wrapper.getAttributeSchemaMutation() instanceof SetAttributeSchemaSearchableMutation) {
							reference.withAttribute(
								"text", String.class, attribute -> attribute.localized().nullable()
							);
						}
					})
					.updateVia(session);
			}
			session.goLiveAndClose();
		});
		return catalog;
	}

	/** Applies the Java-origin mutation without going through an external converter. */
	public static void apply(@Nonnull EvitaSessionContract session, @Nonnull MutationCase testCase) {
		if (testCase.global()) {
			session.updateCatalogSchema((LocalCatalogSchemaMutation) testCase.mutation());
		} else {
			session.updateEntitySchema(new ModifyEntitySchemaMutation(
				"owner", (LocalEntitySchemaMutation) testCase.mutation()));
		}
	}

	/** Returns retained Java-origin events after a known checkpoint and verifies the expected leaf is present. */
	@Nonnull
	public static List<ChangeCatalogCapture> history(
		@Nonnull Evita evita, @Nonnull String catalog, long sinceVersion, @Nonnull MutationCase testCase
	) {
		final List<ChangeCatalogCapture> captures = evita.queryCatalog(catalog, session -> {
			try (final Stream<ChangeCatalogCapture> history = session.getMutationsHistoryForward(
				ChangeCatalogCaptureRequest.builder()
					.sinceVersion(sinceVersion)
					.content(ChangeCaptureContent.BODY)
					.build()
			)) {
				return history.toList();
			}
		});
		assertTrue(captures.stream().anyMatch(capture -> testCase.mutation().equals(capture.body())));
		return captures;
	}

	/** Converts the mutation into the aggregate accepted by schema endpoints. */
	@Nonnull
	public static Map<String, Object> input(@Nonnull SchemaMutation mutation) {
		final Map<String, Object> body = new LinkedHashMap<>(output(mutation));
		body.remove("mutationType");
		if (mutation instanceof ModifyEntitySchemaMutation entity) {
			body.put("schemaMutations", Arrays.stream(entity.getSchemaMutations())
				.map(FulltextMutationFunctionalTestSupport::input).toList());
		}
		if (mutation instanceof ModifyReferenceAttributeSchemaMutation wrapper) {
			body.put("attributeSchemaMutation", input((SchemaMutation) wrapper.getAttributeSchemaMutation()));
		}
		final String type = mutation.getClass().getSimpleName();
		return Map.of(Character.toLowerCase(type.charAt(0)) + type.substring(1),
			FulltextMutationTestSupport.typedInput(body));
	}

	/** Serializes test input as JSON with the same scalar conventions used by mutation output. */
	@Nonnull
	@SneakyThrows(JsonProcessingException.class)
	public static String json(@Nullable Object value) {
		return MAPPER.writeValueAsString(value);
	}

	/** Renders input objects and enums in GraphQL syntax. */
	@Nonnull
	public static String graphql(@Nullable Object value, @Nonnull String field) {
		if (value instanceof Map<?, ?> map) {
			return map.entrySet().stream()
				.map(entry -> entry.getKey() + ":" + graphql(entry.getValue(), entry.getKey().toString()))
				.collect(Collectors.joining(",", "{", "}"));
		}
		if (value instanceof Object[] array) {
			return graphql(Arrays.asList(array), field);
		}
		if (value instanceof List<?> list) {
			return list.stream().map(item -> graphql(item, field)).collect(Collectors.joining(",", "[", "]"));
		}
		return value instanceof String && ENUM_FIELDS.contains(field) ? value.toString() : json(value);
	}

	/**
	 * Asserts that a typed or untyped capture stream delivers every fulltext value of the fixture mutation. The
	 * expected values are written out from the fixture rather than produced by an output converter, so a field the
	 * converters drop on both sides still fails.
	 */
	@SneakyThrows(JsonProcessingException.class)
	public static void assertCapture(@Nonnull List<String> events, @Nonnull MutationCase testCase) {
		final SchemaMutation leaf = testCase.mutation() instanceof ModifyReferenceAttributeSchemaMutation wrapper
			? (SchemaMutation) wrapper.getAttributeSchemaMutation() : testCase.mutation();
		final Map<String, JsonNode> expected = expectedFields(leaf);
		boolean found = false;
		for (final String event : events) {
			final List<JsonNode> candidates = MAPPER.readTree(event).findParents("mutationType");
			for (final JsonNode body : candidates) {
				if (body.path("mutationType").asText().equals(leaf.getClass().getSimpleName())) {
					for (final Map.Entry<String, JsonNode> field : expected.entrySet()) {
						assertTrue(body.hasNonNull(field.getKey()), field.getKey() + " missing in " + event);
						final JsonNode actual = body.get(field.getKey());
						if (field.getKey().equals("content") && actual.isObject()) {
							final com.fasterxml.jackson.databind.node.ObjectNode content = actual.deepCopy();
							for (final String property : List.of("words", "groups")) {
								if (content.path(property).isNull()) {
									content.remove(property);
								}
							}
							assertEquals(field.getValue(), content, field.getKey() + " in " + event);
						} else {
							assertEquals(field.getValue(), actual, field.getKey() + " in " + event);
						}
					}
					if (leaf.getClass().getSimpleName().contains("Fulltext")) {
						final String name = leaf.getClass().getSimpleName();
						assertEquals(List.of(leaf), new LocalCatalogSchemaMutationInputAggregateConverter(
								PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE
							).convertFromInput(Map.of(Character.toLowerCase(name.charAt(0)) + name.substring(1),
								FulltextMutationTestSupport.typedInput(MAPPER.convertValue(body, Map.class)))));
					}
					found = true;
				}
			}
		}
		assertTrue(found, "Missing fulltext mutation body in " + events);
	}

	/**
	 * Returns the fulltext fields a capture of the leaf mutation must carry, as
	 * {@link FulltextMutationTestSupport#mutations(BigDecimal)} builds them with a zero weight.
	 */
	@Nonnull
	private static Map<String, JsonNode> expectedFields(@Nonnull SchemaMutation leaf) {
		return switch (leaf) {
			case SetAttributeSchemaSearchableMutation ignored -> ATTRIBUTE_FIELDS;
			case CreateAttributeSchemaMutation ignored -> ATTRIBUTE_FIELDS;
			case CreateGlobalAttributeSchemaMutation ignored -> ATTRIBUTE_FIELDS;
			case SetAssociatedDataSchemaSearchableMutation ignored -> ASSOCIATED_DATA_FIELDS;
			case CreateAssociatedDataSchemaMutation ignored -> ASSOCIATED_DATA_FIELDS;
			case SetReferenceSchemaSearchableThroughMutation ignored ->
				Map.of("searchableThroughInScopes", COMPOSITION_MODES, "weight", ZERO_WEIGHT);
			case CreateReferenceSchemaMutation ignored ->
				Map.of("searchableThroughInScopes", COMPOSITION_MODES, "searchableThroughWeight", ZERO_WEIGHT);
			case CreateReflectedReferenceSchemaMutation ignored ->
				Map.of("searchableThroughInScopes", COMPOSITION_MODES, "searchableThroughWeight", ZERO_WEIGHT);
			case CreateFulltextLanguageSchemaMutation ignored -> languageFields();
			case ModifyFulltextLanguageSchemaMutation ignored -> languageFields();
			case RemoveFulltextLanguageSchemaMutation ignored -> Map.of("language", TextNode.valueOf("en"));
			case CreateFulltextResourceSchemaMutation ignored -> Map.of("name", TextNode.valueOf("terms"),
				"content", readExpected("{\"kind\":\"WORD_LIST\",\"words\":[\"and\",\"the\"]}"));
			case ModifyFulltextResourceSchemaMutation ignored -> Map.of("name", TextNode.valueOf("terms"),
				"content", readExpected("{\"kind\":\"SYNONYM_LIST\",\"groups\":[{\"words\":[\"quick\",\"fast\"]}," +
					"{\"words\":[\"bright\",\"light\"]}]}"));
			case RemoveFulltextResourceSchemaMutation ignored -> Map.of("name", TextNode.valueOf("terms"));
			case CreateFulltextAnalyzerSchemaMutation ignored -> analyzerFields();
			case ModifyFulltextAnalyzerSchemaMutation ignored -> analyzerFields();
			case RemoveFulltextAnalyzerSchemaMutation ignored -> Map.of("name", TextNode.valueOf("custom"));
			default -> throw new IllegalArgumentException("No fixture values for " + leaf.getClass().getSimpleName());
		};
	}

	/** Literal ISO code and declared splitting order expected from the Java fixture. */
	@Nonnull
	private static Map<String, JsonNode> languageFields() {
		return Map.of("language", TextNode.valueOf("en"), "wordSplitting", readExpected("""
			[{"step":"standard","parameters":{}},{"step":"word-number-split","parameters":{}}]
			"""));
	}

	/**
	 * Provides analyzer values including undefined phases, empty overrides and bounded author samples.
	 *
	 * @return the expected captured analyzer members
	 */
	@Nonnull
	private static Map<String, JsonNode> analyzerFields() {
		return Map.of("name", TextNode.valueOf("custom"), "basedOn", TextNode.valueOf("text"),
			"allLanguages", readExpected("""
				{"text":[{"step":"html-strip","parameters":{}}],"words":[
				{"step":"lowercase","parameters":{}},
				{"step":"stop-words","parameters":{"words":["english-stop-words"]}},
				{"step":"english-stemming","parameters":{"protected-words":["english-stop-words"]}}]}
				"""),
			"samples", MAPPER.valueToTree(FulltextMutationTestSupport.analyzerSamples()),
			"languages", readExpected("""
				[{"language":"de","phases":{"text":[],"words":null}},
				 {"language":"en","phases":{"text":null,"words":[]}}]
				"""));
	}

	/** Parses an independent literal fixture, retaining JSON nulls and array ordering. */
	@Nonnull
	@SneakyThrows(JsonProcessingException.class)
	private static JsonNode readExpected(@Nonnull String json) {
		return MAPPER.readTree(json);
	}

	/**
	 * Builds the request payload of the mutation through the production shared output converters. It serves the
	 * request builder {@link #input(SchemaMutation)} only and is never used as the expected value of a capture.
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	private static Map<String, Object> output(@Nonnull SchemaMutation mutation) {
		return (Map<String, Object>) (mutation instanceof LocalCatalogSchemaMutation &&
			!(mutation instanceof LocalEntitySchemaMutation)
			? new DelegatingLocalCatalogSchemaMutationConverter(
				PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE
			).convertToOutput((LocalCatalogSchemaMutation) mutation)
			: new DelegatingEntitySchemaMutationConverter(
				PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE
			).convertToOutput((EntitySchemaMutation) mutation));
	}
}
