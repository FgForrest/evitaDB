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
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.SchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.CreateAssociatedDataSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.associatedData.SetAssociatedDataSchemaSearchableMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateGlobalAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ReferenceAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaSearchableMutation;
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
import io.evitadb.externalApi.api.resolver.mutation.PassThroughMutationObjectMapper;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shared real-engine preparation and payload assertions for fulltext mutation transport tests. */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class FulltextMutationFunctionalTestSupport {
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Set<String> ENUM_FIELDS = Set.of(
		"type", "scope", "mode", "cardinality", "uniquenessType", "conflictResolutionOverride",
		"attributeInheritanceBehavior",
		"indexType", "indexedComponents", "searchableInScopes", "filterableInScopes", "sortableInScopes",
		"facetedInScopes"
	);
	/** The zero weight of the fixture, rendered as the decimal string the mutation output uses. */
	private static final JsonNode ZERO_WEIGHT = TextNode.valueOf("0.0");
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
		final List<SchemaMutation> mutations = FulltextMutationTestSupport.mutations(0.0f).toList();
		return Stream.concat(
			mutations.stream().map(mutation -> new MutationCase(
				mutation, mutation instanceof CreateGlobalAttributeSchemaMutation)),
			Stream.of(
				new MutationCase(mutations.get(0), true),
				new MutationCase(new ModifyReferenceAttributeSchemaMutation(
					"related", (ReferenceAttributeSchemaMutation) mutations.get(0)), false),
				new MutationCase(new ModifyReferenceAttributeSchemaMutation(
					"related", (CreateAttributeSchemaMutation) mutations.get(3)), false)
			)
		);
	}

	/** Creates an isolated catalog with only the prerequisites of the mutation under test. */
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
			if (testCase.global() && mutation instanceof SetAttributeSchemaSearchableMutation) {
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
		if (mutation instanceof ModifyReferenceAttributeSchemaMutation wrapper) {
			body.put("attributeSchemaMutation", input((SchemaMutation) wrapper.getAttributeSchemaMutation()));
		}
		final String type = mutation.getClass().getSimpleName();
		return Map.of(Character.toLowerCase(type.charAt(0)) + type.substring(1), body);
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
			return graphql(java.util.Arrays.asList(array), field);
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
						assertEquals(field.getValue(), body.get(field.getKey()), field.getKey() + " in " + event);
					}
					found = true;
				}
			}
		}
		assertTrue(found, "Missing fulltext mutation body in " + events);
	}

	/**
	 * Returns the fulltext fields a capture of the leaf mutation must carry, as
	 * {@link FulltextMutationTestSupport#mutations(Float)} builds them with a zero weight.
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
			default -> throw new IllegalArgumentException("No fixture values for " + leaf.getClass().getSimpleName());
		};
	}

	/**
	 * Builds the request payload of the mutation through the production shared output converters. It serves the
	 * request builder {@link #input(SchemaMutation)} only and is never used as the expected value of a capture.
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	private static Map<String, Object> output(@Nonnull SchemaMutation mutation) {
		return (Map<String, Object>) (mutation instanceof CreateGlobalAttributeSchemaMutation
			? new DelegatingLocalCatalogSchemaMutationConverter(
				PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE
			).convertToOutput((LocalCatalogSchemaMutation) mutation)
			: new DelegatingEntitySchemaMutationConverter(
				PassThroughMutationObjectMapper.INSTANCE, TestMutationResolvingExceptionFactory.INSTANCE
			).convertToOutput((io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation) mutation));
	}
}
