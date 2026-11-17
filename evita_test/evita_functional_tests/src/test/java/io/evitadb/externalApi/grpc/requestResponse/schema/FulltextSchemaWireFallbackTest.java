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

package io.evitadb.externalApi.grpc.requestResponse.schema;

import io.evitadb.externalApi.grpc.dataType.EvitaDataTypesConverter;
import java.math.BigDecimal;
import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.externalApi.FulltextSchemaFunctionalTestSupport;
import io.evitadb.externalApi.grpc.generated.GrpcAttributeSchema;
import io.evitadb.externalApi.grpc.generated.GrpcBigDecimal;
import io.evitadb.externalApi.grpc.generated.GrpcCatalogSchema;
import io.evitadb.externalApi.grpc.generated.GrpcEntitySchema;
import io.evitadb.externalApi.grpc.generated.GrpcReferenceSchema;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;

import static java.math.BigDecimal.valueOf;
import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.FULLTEXT;
import static io.evitadb.test.TestTags.GRPC;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A server that predates fulltext sends schemas without any fulltext setting. The client must then read every field
 * with the schema defaults - not searchable, the `text` analyzer, the default length pivot and weights - rather than
 * with the zero values of the unset protobuf wrappers.
 */
@Tag(EXTERNAL_API)
@Tag(GRPC)
@Tag(SCHEMA)
@Tag(FULLTEXT)
@DisplayName("gRPC schema read without fulltext settings")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FulltextSchemaWireFallbackTest implements EvitaTestSupport {
	private GrpcCatalogSchema catalog;
	private GrpcEntitySchema owner;

	/**
	 * Declares the shared fulltext fixture in a real engine and keeps only the wire form of its schemas, so every
	 * field of the payload starts with a non-default fulltext setting.
	 */
	@BeforeAll
	void setUp() {
		final TestPaths paths = createTestPaths("FulltextSchemaWireFallback");
		try (final Evita evita = new Evita(newTestEvitaConfigurationBuilder(paths).build())) {
			final String catalogName = FulltextSchemaFunctionalTestSupport.prepare(evita);
			evita.updateCatalog(catalogName, session -> {
				session.updateCatalogSchema(FulltextSchemaFunctionalTestSupport.declarations().toArray(
					LocalCatalogSchemaMutation[]::new));
			});
			evita.queryCatalog(catalogName, session -> {
				this.catalog = CatalogSchemaConverter.convert(session.getCatalogSchema(), true);
				this.owner = EntitySchemaConverter.convert(
					session.getCatalogSchema(), session.getEntitySchemaOrThrow("owner"), true);
				return null;
			});
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/** Covers the entity, global and reference attribute branches, associated data and both reference kinds. */
	@Test
	@DisplayName("reads defaults for attributes, associated data and references sent without fulltext settings")
	void shouldFallBackToFulltextDefaultsForEntityPayloadWithoutFulltextFields() {
		assertEquals("html-text", this.owner.getAttributesMap().get("text").getAnalyzer().getValue());
		assertEquals("html-text", this.owner.getAssociatedDataMap().get("text").getAnalyzer().getValue());
		assertEquals(valueOf(0), EvitaDataTypesConverter.toBigDecimal(
			this.owner.getReferencesMap().get("related").getSearchableThroughWeight()));
		final GrpcEntitySchema.Builder builder = this.owner.toBuilder();
		this.owner.getAttributesMap().forEach((name, attribute) -> builder.putAttributes(name, stripped(attribute)));
		this.owner.getAssociatedDataMap().forEach((name, data) -> builder.putAssociatedData(name, data.toBuilder()
			.clearSearchableInScopes().clearAnalyzer().clearLengthPivot().clearSearchWeight().clearTextExpression()
			.build()));
		this.owner.getReferencesMap().forEach((name, reference) -> builder.putReferences(name, stripped(reference)));

		final EntitySchema schema = EntitySchemaConverter.convert(builder.build());

		assertEquals(Set.of("globalText", "text"), schema.getAttributes().keySet());
		schema.getAttributes().values().forEach(FulltextSchemaWireFallbackTest::assertDefaults);
		final AssociatedDataSchemaContract data = schema.getAssociatedData().get("text");
		assertEquals(Set.of(), data.getSearchableInScopes());
		assertEquals(FulltextAnalyzerSchemaContract.TEXT, data.getAnalyzer());
		assertEquals(AttributeSchemaContract.DEFAULT_LENGTH_PIVOT, data.getLengthPivot());
		assertEquals(AttributeSchemaContract.DEFAULT_SEARCH_WEIGHT, data.getSearchWeight());
		assertNull(data.getTextExpression());
		assertEquals(Set.of("related", "reflected"), schema.getReferences().keySet());
		for (final ReferenceSchemaContract reference : schema.getReferences().values()) {
			assertEquals(Map.of(), reference.getSearchableThroughInScopes(), reference.getName());
			assertEquals(
				ReferenceSchemaContract.DEFAULT_SEARCHABLE_THROUGH_WEIGHT, reference.getSearchableThroughWeight(),
				reference.getName()
			);
		}
		final ReferenceSchemaContract related = schema.getReferences().get("related");
		assertFalse(related.getAttributes().isEmpty());
		related.getAttributes().values().forEach(FulltextSchemaWireFallbackTest::assertDefaults);
	}

	/** Covers the catalog-level global attribute, which the catalog converter reads on its own. */
	@Test
	@DisplayName("reads defaults for a global attribute sent without fulltext settings")
	void shouldFallBackToFulltextDefaultsForGlobalAttributeWithoutFulltextFields() {
		assertEquals("html-text", this.catalog.getAttributesMap().get("globalText").getAnalyzer().getValue());
		final GrpcCatalogSchema.Builder builder = this.catalog.toBuilder();
		this.catalog.getAttributesMap().forEach((name, attribute) -> builder.putAttributes(name, attribute.toBuilder()
			.clearSearchableInScopes().clearAnalyzer().clearLengthPivot().clearSearchWeight().build()));

		final CatalogSchema schema =
			CatalogSchemaConverter.convert(builder.build(), EmptyEntitySchemaAccessor.INSTANCE);

		assertEquals(Set.of("globalText"), schema.getAttributes().keySet());
		assertDefaults(schema.getAttributes().get("globalText"));
	}

	/**
	 * Preserves decimal digits through both schema converters, including globals, inherited reference attributes
	 * and explicit zero, without depending on mutation converter behavior.
	 *
	 * @param decimal exact decimal text every weight in the fixture must retain
	 */
	@ParameterizedTest
	@ValueSource(strings = {"0", "0.0000001", "12345678.5", "0.123456789012345678901234567891"})
	@DisplayName("round-trips exact decimal weights in every schema place")
	void shouldRoundTripDecimalWeightsInSchemas(@Nonnull String decimal) {
		final BigDecimal expected = new BigDecimal(decimal);
		final GrpcBigDecimal wireWeight = EvitaDataTypesConverter.toGrpcBigDecimal(expected);
		final GrpcCatalogSchema.Builder catalogBuilder = this.catalog.toBuilder();
		this.catalog.getAttributesMap().forEach((name, attribute) -> catalogBuilder.putAttributes(
			name, attribute.toBuilder().setSearchWeight(wireWeight).build()));
		final GrpcEntitySchema.Builder entityBuilder = this.owner.toBuilder();
		this.owner.getAttributesMap().forEach((name, attribute) -> entityBuilder.putAttributes(
			name, attribute.toBuilder().setSearchWeight(wireWeight).build()));
		this.owner.getAssociatedDataMap().forEach((name, data) -> entityBuilder.putAssociatedData(
			name, data.toBuilder().setSearchWeight(wireWeight).build()));
		this.owner.getReferencesMap().forEach((name, reference) -> {
			final GrpcReferenceSchema.Builder referenceBuilder = reference.toBuilder()
				.setSearchableThroughWeight(wireWeight);
			reference.getAttributesMap().forEach((attributeName, attribute) -> referenceBuilder.putAttributes(
				attributeName, attribute.toBuilder().setSearchWeight(wireWeight).build()));
			entityBuilder.putReferences(name, referenceBuilder.build());
		});
		final CatalogSchema catalogSchema = CatalogSchemaConverter.convert(
			catalogBuilder.build(), EmptyEntitySchemaAccessor.INSTANCE);
		final EntitySchema entitySchema = EntitySchemaConverter.convert(entityBuilder.build());
		assertDecimalWeights(catalogSchema, entitySchema, expected);

		final GrpcCatalogSchema catalogRoundTrip = CatalogSchemaConverter.convert(catalogSchema, true);
		final GrpcEntitySchema entityRoundTrip = EntitySchemaConverter.convert(catalogSchema, entitySchema, true);
		assertDecimalWeights(
			CatalogSchemaConverter.convert(catalogRoundTrip, EmptyEntitySchemaAccessor.INSTANCE),
			EntitySchemaConverter.convert(entityRoundTrip), expected
		);
	}

	/**
	 * Checks all schema kinds and refuses empty fixture maps that would make decimal assertions vacuous.
	 *
	 * @param catalogSchema catalog-level global attribute schema
	 * @param entitySchema entity with global and local attributes, associated data and both reference kinds
	 * @param expected exact decimal weight supplied before conversion
	 */
	private static void assertDecimalWeights(
		@Nonnull CatalogSchema catalogSchema, @Nonnull EntitySchema entitySchema, @Nonnull BigDecimal expected
	) {
		assertEquals(expected, catalogSchema.getAttributes().get("globalText").getSearchWeight());
		assertEquals(expected, entitySchema.getAttributes().get("globalText").getSearchWeight());
		assertEquals(expected, entitySchema.getAttributes().get("text").getSearchWeight());
		assertEquals(expected, entitySchema.getAssociatedData().get("text").getSearchWeight());
		assertFalse(entitySchema.getReferences().isEmpty());
		for (final ReferenceSchemaContract reference : entitySchema.getReferences().values()) {
			assertEquals(expected, reference.getSearchableThroughWeight(), reference.getName());
			assertFalse(reference.getAttributes().isEmpty(), reference.getName());
			for (final AttributeSchemaContract attribute : reference.getAttributes().values()) {
				assertEquals(expected, attribute.getSearchWeight(), reference.getName() + "." + attribute.getName());
			}
		}
	}

	/**
	 * Removes every fulltext setting of an entity or reference attribute, as an older server never sets them.
	 *
	 * @param attribute wire attribute carrying the fixture's fulltext settings
	 * @return the same attribute without any fulltext field
	 */
	@Nonnull
	private static GrpcAttributeSchema stripped(@Nonnull GrpcAttributeSchema attribute) {
		return attribute.toBuilder()
			.clearSearchableInScopes().clearAnalyzer().clearLengthPivot().clearSearchWeight().build();
	}

	/**
	 * Removes the composition settings of a regular or reflected reference and the fulltext settings of its
	 * attributes, as an older server never sets them.
	 *
	 * @param reference wire reference carrying the fixture's composition settings
	 * @return the same reference without any fulltext field
	 */
	@Nonnull
	private static GrpcReferenceSchema stripped(@Nonnull GrpcReferenceSchema reference) {
		final GrpcReferenceSchema.Builder builder = reference.toBuilder()
			.clearSearchableThroughInScopes().clearSearchableThroughWeight();
		reference.getAttributesMap().forEach((name, attribute) -> builder.putAttributes(name, stripped(attribute)));
		return builder.build();
	}

	/**
	 * Requires the schema defaults of a field that declares no fulltext setting.
	 *
	 * @param attribute attribute read from a payload without fulltext settings
	 */
	private static void assertDefaults(@Nonnull AttributeSchemaContract attribute) {
		assertEquals(Set.of(), attribute.getSearchableInScopes(), attribute.getName());
		assertEquals(FulltextAnalyzerSchemaContract.TEXT, attribute.getAnalyzer(), attribute.getName());
		assertEquals(AttributeSchemaContract.DEFAULT_LENGTH_PIVOT, attribute.getLengthPivot(), attribute.getName());
		assertEquals(AttributeSchemaContract.DEFAULT_SEARCH_WEIGHT, attribute.getSearchWeight(), attribute.getName());
	}
}
