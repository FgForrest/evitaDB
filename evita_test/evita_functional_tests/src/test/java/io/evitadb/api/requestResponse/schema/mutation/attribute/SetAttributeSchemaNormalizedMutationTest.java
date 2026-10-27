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

package io.evitadb.api.requestResponse.schema.mutation.attribute;

import io.evitadb.api.proxy.mock.EmptyEntitySchemaAccessor;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.builder.InternalEntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.AttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.utils.NamingConvention;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutationTest.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;
import static org.junit.jupiter.api.Assertions.*;

/** Tests full normalization statements on entity, reference and global attribute schemas. */
@DisplayName("Set attribute normalization")
@Tag(CONTRACT)
@Tag(SCHEMA)
class SetAttributeSchemaNormalizedMutationTest {

	/**
	 * Supplies all DTO variants to every preservation and validation test.
	 *
	 * @return a plain, an entity and a global `String` attribute schema
	 */
	@Nonnull
	static Stream<AttributeSchemaContract> schemas() {
		return Stream.of(
			createStringAttributeSchema(), createStringEntityAttributeSchema(), createStringGlobalAttributeSchema()
		);
	}

	/** A statement replaces the complete ordered axis and is idempotent when unchanged. */
	@ParameterizedTest
	@MethodSource("schemas")
	@DisplayName("Statements replace all steps and an empty statement removes normalization")
	void shouldReplaceAndRemoveNormalization(@Nonnull AttributeSchemaContract schema) {
		final SetAttributeSchemaNormalizedMutation mutation = new SetAttributeSchemaNormalizedMutation(
			ATTRIBUTE_NAME, lowercase(), trim()
		);
		final AttributeSchemaContract result = mutation.mutate(null, schema, AttributeSchemaContract.class);
		assertEquals(List.of(lowercase(), trim()), result.getNormalization());
		assertTrue(result.validate().findAny().isEmpty());
		assertSame(result, mutation.mutate(null, result, AttributeSchemaContract.class));
		final AttributeSchemaContract replaced = new SetAttributeSchemaNormalizedMutation(
			ATTRIBUTE_NAME, asciiFolding()
		)
			.mutate(null, result, AttributeSchemaContract.class);
		assertEquals(List.of(asciiFolding()), replaced.getNormalization());
		assertEquals(schema, new SetAttributeSchemaNormalizedMutation(ATTRIBUTE_NAME)
			.mutate(null, replaced, AttributeSchemaContract.class));
		assertThrows(UnsupportedOperationException.class, () -> result.getNormalization().clear());
		assertNotEquals(schema, result);
		assertNotEquals(schema.hashCode(), result.hashCode());
		assertTrue(result.toString().contains("normalization=[lowercase, trim]"));
	}

	/** Every unrelated mutation must carry normalization when reconstructing an attribute DTO. */
	@ParameterizedTest
	@MethodSource("schemas")
	@DisplayName("Unrelated attribute mutations retain the normalization axis")
	void shouldRetainNormalizationAfterUnrelatedMutations(@Nonnull AttributeSchemaContract schema) {
		final AttributeSchemaContract normalized = new SetAttributeSchemaNormalizedMutation(ATTRIBUTE_NAME, lowercase())
			.mutate(null, schema, AttributeSchemaContract.class);
		final List<AttributeSchemaMutation> mutations = List.of(
			new ModifyAttributeSchemaDescriptionMutation(ATTRIBUTE_NAME, "description"),
			new ModifyAttributeSchemaDeprecationNoticeMutation(ATTRIBUTE_NAME, "deprecated"),
			new ModifyAttributeSchemaNameMutation(ATTRIBUTE_NAME, "renamed"),
			new ModifyAttributeSchemaDefaultValueMutation(ATTRIBUTE_NAME, "default"),
			new ModifyAttributeSchemaTypeMutation(ATTRIBUTE_NAME, String[].class, 0),
			new SetAttributeSchemaLocalizedMutation(ATTRIBUTE_NAME, true),
			new SetAttributeSchemaNullableMutation(ATTRIBUTE_NAME, true),
			new SetAttributeSchemaRepresentativeMutation(ATTRIBUTE_NAME, true),
			new SetAttributeSchemaFilterableMutation(ATTRIBUTE_NAME, true),
			new SetAttributeSchemaSortableMutation(ATTRIBUTE_NAME, true),
			new SetAttributeSchemaUniqueMutation(ATTRIBUTE_NAME,
				AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION),
			new SetAttributeSchemaSearchableMutation(ATTRIBUTE_NAME, true),
			new SetAttributeSchemaAcceleratedMutation(ATTRIBUTE_NAME),
			new SetAttributeSchemaConflictResolutionOverrideMutation(
				ATTRIBUTE_NAME, ConflictResolutionOverride.INHERITED
			)
		);
		for (final AttributeSchemaMutation mutation : mutations) {
			assertEquals(List.of(lowercase()), mutation.mutate(null, normalized, AttributeSchemaContract.class)
				.getNormalization(), mutation.toString());
		}
	}

	/** Type changes may temporarily invalidate the attribute, but final validation catches them. */
	@ParameterizedTest
	@MethodSource("schemas")
	@DisplayName("Final validation rejects non-string types while allowing intermediate states")
	void shouldRejectNormalizationAfterTypeChange(@Nonnull AttributeSchemaContract schema) {
		final AttributeSchemaContract normalized = new SetAttributeSchemaNormalizedMutation(ATTRIBUTE_NAME, lowercase())
			.mutate(null, schema, AttributeSchemaContract.class);
		final AttributeSchemaContract changed = new ModifyAttributeSchemaTypeMutation(ATTRIBUTE_NAME, Integer.class, 0)
			.mutate(null, normalized, AttributeSchemaContract.class);
		assertTrue(changed.validate().anyMatch(error -> error.contains("normalization")));
		assertTrue(new SetAttributeSchemaNormalizedMutation(ATTRIBUTE_NAME)
			.mutate(null, changed, AttributeSchemaContract.class)
			.validate().findAny().isEmpty());
		final AttributeSchemaContract array = new ModifyAttributeSchemaTypeMutation(ATTRIBUTE_NAME, String[].class, 0)
			.mutate(null, normalized, AttributeSchemaContract.class);
		assertTrue(array.validate().findAny().isEmpty());
	}

	/** Full statements combine using later-wins behavior in both pipelines. */
	@Test
	@DisplayName("Later entity and catalog statements win only for the same attribute")
	void shouldCombineStatementsForTheSameAttribute() {
		final CatalogSchemaContract catalog = catalogSchema(Map.of());
		final EntitySchemaContract entity = EntitySchema._internalBuild("product");
		final SetAttributeSchemaNormalizedMutation earlier = new SetAttributeSchemaNormalizedMutation(
			ATTRIBUTE_NAME, lowercase()
		);
		final SetAttributeSchemaNormalizedMutation later = new SetAttributeSchemaNormalizedMutation(
			ATTRIBUTE_NAME, trim()
		);
		assertSame(later, later.combineWith(catalog, entity, earlier).current()[0]);
		assertSame(later, later.combineWith(catalog, (LocalCatalogSchemaMutation) earlier).current()[0]);
		assertNull(later.combineWith(catalog, entity, new SetAttributeSchemaNormalizedMutation("other")));
		assertNull(later.combineWith(
			catalog, (LocalCatalogSchemaMutation) new SetAttributeSchemaNormalizedMutation("other")
		));
		assertNull(later.combineWith(
			catalog, entity, (LocalEntitySchemaMutation) new SetAttributeSchemaNullableMutation(ATTRIBUTE_NAME, true)
		));
		assertNull(later.combineWith(
			catalog, (LocalCatalogSchemaMutation) new SetAttributeSchemaNullableMutation(ATTRIBUTE_NAME, true)
		));
	}

	/** Reference normalization is permitted even on unindexed references. */
	@Test
	@DisplayName("Reference attributes accept the same normalization descriptions")
	void shouldNormalizeReferenceAttributes() {
		final ReferenceSchema reference = ReferenceSchema._internalBuild(
			"brand", "brand", false, Cardinality.ZERO_OR_MORE, null, false, null, null
		);
		final EntitySchema entity = EntitySchema._internalBuild("product");
		final ReferenceSchema withAttribute = (ReferenceSchema) new CreateAttributeSchemaMutation(
			ATTRIBUTE_NAME, null, null, (AttributeUniquenessType) null,
			false, false, false, false, false, String.class, null, 0
		).mutate(entity, reference);
		assertEquals(List.of(lowercase()), new SetAttributeSchemaNormalizedMutation(ATTRIBUTE_NAME, lowercase())
			.mutate(entity, withAttribute).getAttribute(ATTRIBUTE_NAME).orElseThrow().getNormalization());
	}

	/** Mutations own their array and expose value-based equality and readable diagnostics. */
	@Test
	@DisplayName("Mutation state is defensive and missing steps mean removal")
	void shouldOwnItsNormalizationStatement() {
		final AnalysisStep[] steps = {lowercase()};
		final SetAttributeSchemaNormalizedMutation mutation = new SetAttributeSchemaNormalizedMutation(
			ATTRIBUTE_NAME, steps
		);
		steps[0] = trim();
		mutation.getNormalization()[0] = trim();
		assertArrayEquals(new AnalysisStep[]{lowercase()}, mutation.getNormalization());
		assertEquals(new SetAttributeSchemaNormalizedMutation(ATTRIBUTE_NAME, lowercase()), mutation);
		assertEquals(
			new SetAttributeSchemaNormalizedMutation(ATTRIBUTE_NAME, lowercase()).hashCode(), mutation.hashCode()
		);
		assertEquals(new SetAttributeSchemaNormalizedMutation(ATTRIBUTE_NAME),
			new SetAttributeSchemaNormalizedMutation(ATTRIBUTE_NAME, (AnalysisStep[]) null));
		assertEquals(Operation.UPSERT, mutation.operation());
		assertTrue(mutation.toString().contains("normalization=[lowercase]"));
	}

	/** Both outer schema mutation entry points apply the same statement as the attribute-level entry point. */
	@Test
	@DisplayName("Normalization statements update entity and catalog schemas")
	void shouldMutateHoldingSchemas() {
		final CatalogSchemaContract catalog = catalogSchema(
			Map.of(ATTRIBUTE_NAME, createStringGlobalAttributeSchema())
		);
		final SetAttributeSchemaNormalizedMutation mutation = new SetAttributeSchemaNormalizedMutation(
			ATTRIBUTE_NAME, trim()
		);
		assertEquals(List.of(trim()), mutation.mutate(catalog).updatedCatalogSchema()
			.getAttribute(ATTRIBUTE_NAME).orElseThrow().getNormalization());
		final CatalogSchemaContract entityCatalog = catalogSchema(Map.of());
		final EntitySchemaContract entity = new InternalEntitySchemaBuilder(
			entityCatalog, EntitySchema._internalBuild("product")
		).withAttribute(ATTRIBUTE_NAME, String.class).toInstance();
		assertEquals(List.of(trim()), mutation.mutate(entityCatalog, entity)
			.getAttribute(ATTRIBUTE_NAME).orElseThrow().getNormalization());
	}

	/** Description-list structure is validated on the completed schema rather than inside a set operation. */
	@Test
	@DisplayName("A null step is reported by assembled-schema validation")
	void shouldReportNullStepsDuringAssembledValidation() {
		final AttributeSchemaContract invalid = new SetAttributeSchemaNormalizedMutation(
			ATTRIBUTE_NAME, new AnalysisStep[]{null}
		).mutate(null, createStringAttributeSchema(), AttributeSchemaContract.class);
		assertTrue(invalid.validate().anyMatch(error -> error.contains("null normalization step")));
	}

	/**
	 * Builds an in-memory catalog schema holding the passed global attributes.
	 *
	 * @param attributes global attributes of the catalog, keyed by name
	 * @return catalog schema with all evolution modes enabled
	 */
	@Nonnull
	private static CatalogSchema catalogSchema(@Nonnull Map<String, GlobalAttributeSchemaContract> attributes) {
		return CatalogSchema._internalBuild(
			1, "unit", NamingConvention.generate("unit"), null, null,
			EnumSet.allOf(CatalogEvolutionMode.class), attributes, EmptyEntitySchemaAccessor.INSTANCE
		);
	}

}
