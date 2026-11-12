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
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntityAttributeSchema;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.exception.InconvertibleDataTypeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.junit.jupiter.api.Tag;

import javax.annotation.Nonnull;
import java.math.BigDecimal;

import static io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutationTest.*;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.ATTRIBUTE;

/**
 * Test verifies {@link ModifyAttributeSchemaDefaultValueMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("ModifyAttributeSchemaDefaultValueMutation")
@Tag(CONTRACT)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
class ModifyAttributeSchemaDefaultValueMutationTest {

	@Test
	@DisplayName("Should override default value of previous global attribute mutation when names match")
	void shouldOverrideDefaultValueOfPreviousGlobalAttributeMutationIfNamesMatch() {
		final ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, 5
		);
		final ModifyAttributeSchemaDefaultValueMutation existingMutation = new ModifyAttributeSchemaDefaultValueMutation(ATTRIBUTE_NAME, "oldDefaultValue");
		final CatalogSchemaContract entitySchema = Mockito.mock(CatalogSchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingGlobalAttributeSchema()));
		final MutationCombinationResult<LocalCatalogSchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(ModifyAttributeSchemaDefaultValueMutation.class, result.current()[0]);
		assertEquals(5, ((ModifyAttributeSchemaDefaultValueMutation) result.current()[0]).getDefaultValue());
	}

	@Test
	@DisplayName("Should leave both mutations when the name of new global attribute mutation doesn't match")
	void shouldLeaveBothMutationsIfTheNameOfNewGlobalAttributeMutationDoesntMatch() {
		final ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, 5
		);
		final ModifyAttributeSchemaDefaultValueMutation existingMutation = new ModifyAttributeSchemaDefaultValueMutation("differentName", "oldDeprecationNotice");
		assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), existingMutation));
	}

	@Test
	@DisplayName("Should override default value of previous mutation when names match")
	void shouldOverrideDefaultValueOfPreviousMutationIfNamesMatch() {
		final ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, 5
		);
		final ModifyAttributeSchemaDefaultValueMutation existingMutation = new ModifyAttributeSchemaDefaultValueMutation(ATTRIBUTE_NAME, "oldDefaultValue");
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingEntityAttributeSchema()));
		final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(ModifyAttributeSchemaDefaultValueMutation.class, result.current()[0]);
		assertEquals(5, ((ModifyAttributeSchemaDefaultValueMutation) result.current()[0]).getDefaultValue());
	}

	@Test
	@DisplayName("Should leave both mutations when the name of new mutation doesn't match")
	void shouldLeaveBothMutationsIfTheNameOfNewMutationDoesntMatch() {
		final ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, 5
		);
		final ModifyAttributeSchemaDefaultValueMutation existingMutation = new ModifyAttributeSchemaDefaultValueMutation("differentName", "oldDefaultValue");
		assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class), existingMutation));
	}

	@Test
	@DisplayName("Should mutate global attribute schema")
	void shouldMutateGlobalAttributeSchema() {
		final ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, 5
		);
		final GlobalAttributeSchemaContract mutatedSchema = mutation.mutate(Mockito.mock(CatalogSchemaContract.class), createExistingGlobalAttributeSchema(), GlobalAttributeSchemaContract.class);
		assertNotNull(mutatedSchema);
		assertEquals(5, mutatedSchema.getDefaultValue());
	}

	@Test
	@DisplayName("Should mutate entity attribute schema")
	void shouldMutateEntityAttributeSchema() {
		final ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, 5
		);
		final EntityAttributeSchemaContract mutatedSchema = mutation.mutate(Mockito.mock(CatalogSchemaContract.class), createExistingEntityAttributeSchema(), EntityAttributeSchemaContract.class);
		assertNotNull(mutatedSchema);
		assertEquals(5, mutatedSchema.getDefaultValue());
	}

	@Test
	@DisplayName("Should mutate catalog schema")
	void shouldMutateCatalogSchema() {
		final ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, 5
		);
		final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
		Mockito.when(catalogSchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingGlobalAttributeSchema()));
		Mockito.when(catalogSchema.version()).thenReturn(1);
		final CatalogSchemaWithImpactOnEntitySchemas mutationResult = mutation.mutate(
			catalogSchema
		);
		assertEquals(0, mutationResult.entitySchemaMutations().length);
		final CatalogSchemaContract newCatalogSchema = mutationResult.updatedCatalogSchema();
		assertEquals(2, newCatalogSchema.version());
		final GlobalAttributeSchemaContract newAttributeSchema = newCatalogSchema.getAttribute(ATTRIBUTE_NAME).orElseThrow();
		assertEquals(5, newAttributeSchema.getDefaultValue());
	}

	@Test
	@DisplayName("Should mutate entity schema")
	void shouldMutateEntitySchema() {
		final ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, 5
		);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingEntityAttributeSchema()));
		Mockito.when(entitySchema.version()).thenReturn(1);
		final EntitySchemaContract newEntitySchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class),
			entitySchema
		);
		assertEquals(2, newEntitySchema.version());
		final AttributeSchemaContract newAttributeSchema = newEntitySchema.getAttribute(ATTRIBUTE_NAME).orElseThrow();
		assertEquals(5, newAttributeSchema.getDefaultValue());
	}

	@Test
	@DisplayName("Should keep a global attribute global when mutating entity schema")
	void shouldMutateEntitySchemaKeepingGlobalAttributeGlobal() {
		final ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, 5
		);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingGlobalAttributeSchema()));
		Mockito.when(entitySchema.version()).thenReturn(1);

		final EntitySchemaContract newEntitySchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class),
			entitySchema
		);

		assertEquals(2, newEntitySchema.version());
		final AttributeSchemaContract newAttributeSchema = newEntitySchema.getAttribute(ATTRIBUTE_NAME).orElseThrow();
		assertInstanceOf(GlobalAttributeSchemaContract.class, newAttributeSchema);
		assertEquals(5, newAttributeSchema.getDefaultValue());
	}

	@Test
	@DisplayName("Should return the same entity schema when an equal array default value comes again")
	void shouldReturnSameEntitySchemaWhenArrayDefaultValueIsEqual() {
		final EntityAttributeSchemaContract existingSchema = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, new String[]{"a", "b"}
		).mutate(
			Mockito.mock(CatalogSchemaContract.class),
			createStringArrayEntityAttributeSchema(),
			EntityAttributeSchemaContract.class
		);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(existingSchema));
		// another array instance with the same elements as the stored default value
		final ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, new String[]{"a", "b"}
		);

		final EntitySchemaContract newEntitySchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class),
			entitySchema
		);

		assertSame(entitySchema, newEntitySchema);
	}

	@Test
	@DisplayName("Should mutate reference schema")
	void shouldMutateReferenceSchema() {
		final ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, 5
		);
		final ReferenceSchemaContract mockedReferenceSchema = createMockedReferenceSchema();
		Mockito.when(mockedReferenceSchema.getAttribute(ATTRIBUTE_NAME))
			.thenReturn(of(createExistingAttributeSchema()));
		final ReferenceSchemaContract mutatedSchema = mutation.mutate(
			Mockito.mock(EntitySchemaContract.class),
			mockedReferenceSchema
		);
		assertNotNull(mutatedSchema);
		final AttributeSchemaContract newAttributeSchema = mutatedSchema.getAttribute(ATTRIBUTE_NAME).orElseThrow();
		assertEquals(5, newAttributeSchema.getDefaultValue());
	}

	@Test
	@DisplayName("Should throw exception when mutating entity schema with non-existing attribute")
	void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingAttribute() {
		final ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, 5
		);
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> {
				mutation.mutate(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class));
			}
		);
	}

	@Test
	@DisplayName("Should refuse a default value the type of the entity attribute cannot hold")
	void shouldThrowExceptionWhenDefaultValueCannotBeConvertedToEntityAttributeType() {
		final ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, "notANumber"
		);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingEntityAttributeSchema()));

		final InvalidSchemaMutationException exception = assertThrows(
			InvalidSchemaMutationException.class,
			() -> mutation.mutate(Mockito.mock(CatalogSchemaContract.class), entitySchema)
		);
		assertInstanceOf(InconvertibleDataTypeException.class, exception.getCause());
	}

	@Test
	@DisplayName("Should name the elements of an array default value the type of the attribute cannot hold")
	void shouldNameArrayElementsWhenArrayDefaultValueCannotBeConverted() {
		final ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, new String[]{"1", "notANumber"}
		);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingEntityAttributeSchema()));

		final InvalidSchemaMutationException exception = assertThrows(
			InvalidSchemaMutationException.class,
			() -> mutation.mutate(Mockito.mock(CatalogSchemaContract.class), entitySchema)
		);
		assertTrue(exception.getMessage().contains("`[1, notANumber]`"), exception.getMessage());
	}

	@Test
	@DisplayName("Should refuse a default value the type of the global attribute cannot hold")
	void shouldThrowExceptionWhenDefaultValueCannotBeConvertedToGlobalAttributeType() {
		final ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, "notANumber"
		);
		final CatalogSchemaContract catalogSchema = Mockito.mock(CatalogSchemaContract.class);
		Mockito.when(catalogSchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingGlobalAttributeSchema()));

		final InvalidSchemaMutationException exception = assertThrows(
			InvalidSchemaMutationException.class,
			() -> mutation.mutate(catalogSchema)
		);
		assertInstanceOf(InconvertibleDataTypeException.class, exception.getCause());
	}

	@Test
	@DisplayName("Should refuse a default value the type of the reference attribute cannot hold")
	void shouldThrowExceptionWhenDefaultValueCannotBeConvertedToReferenceAttributeType() {
		final ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, "notANumber"
		);
		final ReferenceSchemaContract referenceSchema = createMockedReferenceSchema();
		Mockito.when(referenceSchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingAttributeSchema()));

		final InvalidSchemaMutationException exception = assertThrows(
			InvalidSchemaMutationException.class,
			() -> mutation.mutate(Mockito.mock(EntitySchemaContract.class), referenceSchema)
		);
		assertInstanceOf(InconvertibleDataTypeException.class, exception.getCause());
	}

	@Test
	@DisplayName("Should return the same attribute schema when an equal array default value comes again")
	void shouldReturnSameAttributeSchemaWhenArrayDefaultValueIsEqual() {
		final AttributeSchemaContract existingSchema = createStringArrayAttributeSchema(new String[]{"a", "b"});
		final ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, new String[]{"a", "b"}
		);

		final AttributeSchemaContract mutatedSchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class), existingSchema, AttributeSchemaContract.class
		);

		assertSame(existingSchema, mutatedSchema);
	}

	@Test
	@DisplayName("Should return the same reference schema when an equal array default value comes again")
	void shouldReturnSameReferenceSchemaWhenArrayDefaultValueIsEqual() {
		final ReferenceSchemaContract referenceSchema = createMockedReferenceSchema();
		Mockito.when(referenceSchema.getAttribute(ATTRIBUTE_NAME))
			.thenReturn(of(createStringArrayAttributeSchema(new String[]{"a", "b"})));
		final ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, new String[]{"a", "b"}
		);

		final ReferenceSchemaContract mutatedSchema = mutation.mutate(
			Mockito.mock(EntitySchemaContract.class), referenceSchema
		);

		assertSame(referenceSchema, mutatedSchema);
	}

	@Test
	@DisplayName("Should replace a range default value by a range whose bounds merely encode to the same numbers")
	void shouldMutateAttributeSchemaWhenRangeDefaultValueDiffersOnlyInRepresentation() {
		final AttributeSchemaContract existingSchema = createDecimalRangeAttributeSchema(
			BigDecimalNumberRange.between(new BigDecimal("10"), new BigDecimal("20"))
		);
		final BigDecimalNumberRange newDefaultValue = BigDecimalNumberRange.between(
			new BigDecimal("1.0"), new BigDecimal("2.0")
		);
		// the two ranges are equal, because equality of a range is the equality of its encoded bounds
		assertEquals(existingSchema.getDefaultValue(), newDefaultValue);
		final ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, newDefaultValue
		);

		final AttributeSchemaContract mutatedSchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class), existingSchema, AttributeSchemaContract.class
		);

		assertNotSame(existingSchema, mutatedSchema);
		assertEquals("[1.0,2.0]", String.valueOf(mutatedSchema.getDefaultValue()));
	}

	@Test
	@DisplayName("Should replace a range default value of a reference attribute by a range of another representation")
	void shouldMutateReferenceSchemaWhenRangeDefaultValueDiffersOnlyInRepresentation() {
		final ReferenceSchemaContract referenceSchema = createMockedReferenceSchema();
		Mockito.when(referenceSchema.getAttribute(ATTRIBUTE_NAME))
			.thenReturn(
				of(
					createDecimalRangeAttributeSchema(
						BigDecimalNumberRange.between(new BigDecimal("10"), new BigDecimal("20"))
					)
				)
			);
		final ModifyAttributeSchemaDefaultValueMutation mutation = new ModifyAttributeSchemaDefaultValueMutation(
			ATTRIBUTE_NAME, BigDecimalNumberRange.between(new BigDecimal("1.0"), new BigDecimal("2.0"))
		);

		final ReferenceSchemaContract mutatedSchema = mutation.mutate(
			Mockito.mock(EntitySchemaContract.class), referenceSchema
		);

		assertNotSame(referenceSchema, mutatedSchema);
		assertEquals(
			"[1.0,2.0]",
			String.valueOf(mutatedSchema.getAttribute(ATTRIBUTE_NAME).orElseThrow().getDefaultValue())
		);
	}

	/**
	 * Builds a `String[]` entity attribute without a default value or any other trait.
	 *
	 * @return the entity attribute schema
	 */
	@Nonnull
	private static EntityAttributeSchemaContract createStringArrayEntityAttributeSchema() {
		return EntityAttributeSchema._internalBuild(
			ATTRIBUTE_NAME,
			null,
			null,
			null,
			Scope.NO_SCOPE,
			null,
			Scope.NO_SCOPE,
			false,
			false,
			false,
			String[].class,
			null,
			0,
			ConflictResolutionOverride.INHERITED
		);
	}

	/**
	 * Builds a `String[]` attribute holding the default value, with no other trait.
	 *
	 * @param defaultValue the default value the attribute holds
	 * @return the attribute schema
	 */
	@Nonnull
	private static AttributeSchemaContract createStringArrayAttributeSchema(@Nonnull String[] defaultValue) {
		return AttributeSchema._internalBuild(
			ATTRIBUTE_NAME,
			null,
			null,
			null,
			Scope.NO_SCOPE,
			null,
			Scope.NO_SCOPE,
			false,
			false,
			false,
			String[].class,
			defaultValue,
			0,
			ConflictResolutionOverride.INHERITED
		);
	}

	/**
	 * Builds a decimal range attribute with the given default value and no other trait.
	 *
	 * @param defaultValue the default value of the attribute
	 * @return the attribute schema
	 */
	@Nonnull
	private static AttributeSchemaContract createDecimalRangeAttributeSchema(
		@Nonnull BigDecimalNumberRange defaultValue
	) {
		return AttributeSchema._internalBuild(
			ATTRIBUTE_NAME,
			null,
			null,
			null,
			Scope.NO_SCOPE,
			null,
			Scope.NO_SCOPE,
			false,
			false,
			false,
			BigDecimalNumberRange.class,
			defaultValue,
			1,
			ConflictResolutionOverride.INHERITED
		);
	}

}
