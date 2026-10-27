/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Tag;

import static io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps.*;
import static io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutationTest.*;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link CreateGlobalAttributeSchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("CreateGlobalAttributeSchemaMutation")
@Tag(CONTRACT)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
class CreateGlobalAttributeSchemaMutationTest {
	static final String ATTRIBUTE_NAME = "name";

	@Test
	@DisplayName("Should throw exception when invalid type is provided")
	void shouldThrowExceptionWhenInvalidTypeIsProvided() {
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> new CreateGlobalAttributeSchemaMutation(
				ATTRIBUTE_NAME, "description", "deprecationNotice",
				AttributeUniquenessType.NOT_UNIQUE, GlobalAttributeUniquenessType.NOT_UNIQUE, false, false, false, false, false,
				Serializable.class, null, 2
			)
		);
	}

	@Test
	@DisplayName("Should be replaced with individual mutations when global attribute was removed and created with different settings")
	void shouldBeReplacedWithIndividualMutationsWhenGlobalAttributeWasRemovedAndCreatedWithDifferentSettings() {
		final CreateGlobalAttributeSchemaMutation mutation = new CreateGlobalAttributeSchemaMutation(
			ATTRIBUTE_NAME, "newDescription", "newDeprecationNotice",
			AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG,
			true, true, true, true, true,
			String.class, "abc", 0
		);
		final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
		Mockito.when(catalogSchema.getAttribute(ATTRIBUTE_NAME))
			.thenReturn(
				of(createExistingGlobalAttributeSchema())
			);
		final RemoveAttributeSchemaMutation removeMutation = new RemoveAttributeSchemaMutation(ATTRIBUTE_NAME);
		final MutationCombinationResult<LocalCatalogSchemaMutation> result = mutation.combineWith(
			catalogSchema,
			removeMutation
		);
		assertNotNull(result);
		assertFalse(result.discarded());
		assertEquals(11, result.current().length);
		assertTrue(Arrays.stream(result.current()).anyMatch(ModifyAttributeSchemaDescriptionMutation.class::isInstance));
		assertTrue(Arrays.stream(result.current()).anyMatch(
			ModifyAttributeSchemaDeprecationNoticeMutation.class::isInstance));
		assertTrue(Arrays.stream(result.current()).anyMatch(ModifyAttributeSchemaTypeMutation.class::isInstance));
		assertTrue(Arrays.stream(result.current()).anyMatch(ModifyAttributeSchemaDefaultValueMutation.class::isInstance));
		assertTrue(Arrays.stream(result.current()).anyMatch(SetAttributeSchemaLocalizedMutation.class::isInstance));
		assertTrue(Arrays.stream(result.current()).anyMatch(SetAttributeSchemaNullableMutation.class::isInstance));
		assertTrue(Arrays.stream(result.current()).anyMatch(SetAttributeSchemaUniqueMutation.class::isInstance));
		assertTrue(Arrays.stream(result.current()).anyMatch(SetAttributeSchemaGloballyUniqueMutation.class::isInstance));
		assertTrue(Arrays.stream(result.current()).anyMatch(SetAttributeSchemaFilterableMutation.class::isInstance));
		assertTrue(Arrays.stream(result.current()).anyMatch(SetAttributeSchemaSortableMutation.class::isInstance));
		assertTrue(Arrays.stream(result.current()).anyMatch(SetAttributeSchemaRepresentativeMutation.class::isInstance));
	}

	@Test
	@DisplayName("Should leave mutation intact when removal mutation targets different attribute")
	void shouldLeaveMutationIntactWhenRemovalMutationTargetsDifferentAttribute() {
		final CreateGlobalAttributeSchemaMutation mutation = new CreateGlobalAttributeSchemaMutation(
			ATTRIBUTE_NAME, "description", "deprecationNotice",
			AttributeUniquenessType.NOT_UNIQUE, GlobalAttributeUniquenessType.NOT_UNIQUE, false, false, false, false, false,
			String.class, null, 0
		);
		final RemoveAttributeSchemaMutation removeMutation = new RemoveAttributeSchemaMutation("differentName");
		assertNull(mutation.combineWith(null, removeMutation));
	}

	@Test
	@DisplayName("Should create global attribute")
	void shouldCreateGlobalAttribute() {
		final CreateGlobalAttributeSchemaMutation mutation = new CreateGlobalAttributeSchemaMutation(
			ATTRIBUTE_NAME, "description", "deprecationNotice",
			AttributeUniquenessType.NOT_UNIQUE, GlobalAttributeUniquenessType.NOT_UNIQUE, false, false, true, true, false,
			String.class, null, 0
		);
		final GlobalAttributeSchemaContract attributeSchema = mutation.mutate(Mockito.mock(CatalogSchemaContract.class), null, GlobalAttributeSchemaContract.class);
		assertNotNull(attributeSchema);
		assertEquals(ATTRIBUTE_NAME, attributeSchema.getName());
		assertEquals("description", attributeSchema.getDescription());
		assertEquals("deprecationNotice", attributeSchema.getDeprecationNotice());
		assertSame(String.class, attributeSchema.getType());
		assertTrue(attributeSchema.isLocalized());
		assertTrue(attributeSchema.isNullable());
		assertFalse(attributeSchema.isSortable());
		assertFalse(attributeSchema.isFilterable());
		assertEquals(GlobalAttributeUniquenessType.NOT_UNIQUE, attributeSchema.getGlobalUniquenessType());
		assertEquals(AttributeUniquenessType.NOT_UNIQUE, attributeSchema.getUniquenessType());
		assertNull(attributeSchema.getDefaultValue());
		assertEquals(0, attributeSchema.getIndexedDecimalPlaces());
	}

	@Test
	@DisplayName("Should create attribute in catalog")
	void shouldCreateAttributeInCatalog() {
		final CreateGlobalAttributeSchemaMutation mutation = new CreateGlobalAttributeSchemaMutation(
			ATTRIBUTE_NAME, "description", "deprecationNotice",
			AttributeUniquenessType.NOT_UNIQUE, GlobalAttributeUniquenessType.NOT_UNIQUE, false, false, true, true, false,
			String.class, null, 0
		);
		final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
		Mockito.when(catalogSchema.version()).thenReturn(1);
		final CatalogSchemaWithImpactOnEntitySchemas result = mutation.mutate(catalogSchema, Mockito.mock(EntitySchemaProvider.class));
		assertNull(result.entitySchemaMutations());
		final CatalogSchemaContract newCatalogSchema = result.updatedCatalogSchema();
		assertNotNull(newCatalogSchema);
		assertEquals(2, newCatalogSchema.version());
		final GlobalAttributeSchemaContract attributeSchema = newCatalogSchema.getAttribute(ATTRIBUTE_NAME).orElseThrow();
		assertNotNull(attributeSchema);
		assertEquals(ATTRIBUTE_NAME, attributeSchema.getName());
		assertEquals("description", attributeSchema.getDescription());
		assertEquals("deprecationNotice", attributeSchema.getDeprecationNotice());
		assertSame(String.class, attributeSchema.getType());
		assertTrue(attributeSchema.isLocalized());
		assertTrue(attributeSchema.isNullable());
		assertFalse(attributeSchema.isSortable());
		assertFalse(attributeSchema.isFilterable());
		assertEquals(GlobalAttributeUniquenessType.NOT_UNIQUE, attributeSchema.getGlobalUniquenessType());
		assertEquals(AttributeUniquenessType.NOT_UNIQUE, attributeSchema.getUniquenessType());
		assertNull(attributeSchema.getDefaultValue());
		assertEquals(0, attributeSchema.getIndexedDecimalPlaces());
	}

	@Test
	@DisplayName("Should throw exception when mutating catalog schema with existing attribute")
	void shouldThrowExceptionWhenMutatingCatalogSchemaWithExistingAttribute() {
		final CreateGlobalAttributeSchemaMutation mutation = new CreateGlobalAttributeSchemaMutation(
			ATTRIBUTE_NAME, "description", "deprecationNotice",
			AttributeUniquenessType.NOT_UNIQUE, GlobalAttributeUniquenessType.NOT_UNIQUE, false, false, false, false, false,
			String.class, null, 0
		);
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> {
				final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
				Mockito.when(catalogSchema.getAttribute(ATTRIBUTE_NAME))
					.thenReturn(of(createExistingGlobalAttributeSchema()));
				mutation.mutate(catalogSchema, Mockito.mock(EntitySchemaProvider.class));
			}
		);
	}


	/** Covers normalization carried directly by create statements, including remove/create combination. */
	@Nested
	@DisplayName("Normalization descriptions")
	class Normalization {

		/** The create statement owns its description array and transfers it to the DTO. */
		@Test
		@DisplayName("Create statements carry normalization and include it in equality and diagnostics")
		void shouldCarryNormalizationInCreatedSchema() {
			final AnalysisStep[] steps = {lowercase(), trim()};
			final CreateGlobalAttributeSchemaMutation mutation = createWithNormalization(steps);
			steps[0] = asciiFolding();
			mutation.getNormalization()[0] = asciiFolding();
			final GlobalAttributeSchemaContract schema = mutation.mutate(
				null, null, GlobalAttributeSchemaContract.class
			);
			assertEquals(List.of(lowercase(), trim()), schema.getNormalization());
			assertEquals(createWithNormalization(new AnalysisStep[]{lowercase(), trim()}), mutation);
			assertEquals(
				createWithNormalization(new AnalysisStep[]{lowercase(), trim()}).hashCode(), mutation.hashCode()
			);
			assertNotEquals(createWithNormalization(null), mutation);
			assertTrue(mutation.toString().contains("normalization=[lowercase, trim]"));
			assertTrue(createWithNormalization(null).mutate(null, null, GlobalAttributeSchemaContract.class)
				.getNormalization().isEmpty());
		}

		/** Schema difference generation must include both setting and withdrawing the axis. */
		@Test
		@DisplayName("Remove/create combination emits the normalization difference")
		void shouldEmitNormalizationDifferenceWhenCombinedWithRemoval() {
			for (final boolean remove : new boolean[]{false, true}) {
				final CreateGlobalAttributeSchemaMutation mutation = createWithNormalization(
					remove ? null : new AnalysisStep[]{lowercase()}
				);
				final GlobalAttributeSchemaContract original = createStringGlobalAttributeSchema();
				final GlobalAttributeSchemaContract existing = remove ?
					new SetAttributeSchemaNormalizedMutation(ATTRIBUTE_NAME, lowercase())
						.mutate(null, original, GlobalAttributeSchemaContract.class) : original;
				final CatalogSchemaContract owner = Mockito.mock(CatalogSchemaContract.class);
				Mockito.when(owner.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(existing));
				final MutationCombinationResult<LocalCatalogSchemaMutation> combined =
					mutation.combineWith(owner, new RemoveAttributeSchemaMutation(ATTRIBUTE_NAME));
				assertNotNull(combined);
				assertTrue(Arrays.stream(combined.current()).anyMatch(change ->
					change instanceof SetAttributeSchemaNormalizedMutation normalized &&
						Arrays.equals(
							remove ? new AnalysisStep[0] : new AnalysisStep[]{lowercase()},
							normalized.getNormalization()
						)
				));
			}
		}

		/** Direct create statements leave cross-field type checks to assembled-schema validation. */
		@Test
		@DisplayName("Created attributes validate normalization against their final type")
		void shouldValidateNormalizationOnCreatedAttributeType() {
			final CreateGlobalAttributeSchemaMutation mutation = new CreateGlobalAttributeSchemaMutation(
				ATTRIBUTE_NAME, null, null, null, null, null, null, null, null,
				false, false, false, Integer.class, null, 0, ConflictResolutionOverride.INHERITED,
				new AnalysisStep[]{lowercase()}
			);
			assertTrue(mutation.mutate(null, null, GlobalAttributeSchemaContract.class)
				.validate().anyMatch(error -> error.contains("normalization")));
		}

		/**
		 * Builds a complete create statement with no unrelated schema settings.
		 *
		 * @param steps declared normalization chain, or `null` when the statement declares none
		 * @return create mutation of a `String` global attribute carrying the given chain
		 */
		@Nonnull
		private CreateGlobalAttributeSchemaMutation createWithNormalization(@Nullable AnalysisStep[] steps) {
			return new CreateGlobalAttributeSchemaMutation(
				ATTRIBUTE_NAME, null, null, null, null, null, null, null, null,
				false, false, false, String.class, null, 0, ConflictResolutionOverride.INHERITED, steps
			);
		}
	}

}
