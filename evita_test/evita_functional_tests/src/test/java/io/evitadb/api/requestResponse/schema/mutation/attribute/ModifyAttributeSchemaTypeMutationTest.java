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
import io.evitadb.api.requestResponse.schema.FilterIndexCapability;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation.CatalogSchemaWithImpactOnEntitySchemas;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.junit.jupiter.api.Tag;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Set;

import static io.evitadb.api.requestResponse.schema.mutation.attribute.CreateAttributeSchemaMutationTest.*;
import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.ATTRIBUTE;

/**
 * Test verifies {@link ModifyAttributeSchemaTypeMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("ModifyAttributeSchemaTypeMutation")
@Tag(CONTRACT)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
class ModifyAttributeSchemaTypeMutationTest {

	@Test
	@DisplayName("Should override type of previous global attribute mutation when names match")
	void shouldOverrideTypeOfPreviousGlobalAttributeMutationIfNamesMatch() {
		final ModifyAttributeSchemaTypeMutation mutation = new ModifyAttributeSchemaTypeMutation(
			ATTRIBUTE_NAME, String.class, 0
		);
		final ModifyAttributeSchemaTypeMutation existingMutation = new ModifyAttributeSchemaTypeMutation(
			ATTRIBUTE_NAME, DateTimeRange.class, 2);
		final CatalogSchemaContract entitySchema = Mockito.mock(CatalogSchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingGlobalAttributeSchema()));
		final MutationCombinationResult<LocalCatalogSchemaMutation> result = mutation.combineWith(
			Mockito.mock(CatalogSchemaContract.class), existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(ModifyAttributeSchemaTypeMutation.class, result.current()[0]);
		assertEquals(String.class, ((ModifyAttributeSchemaTypeMutation) result.current()[0]).getType());
		assertEquals(0, ((ModifyAttributeSchemaTypeMutation) result.current()[0]).getIndexedDecimalPlaces());
	}

	@Test
	@DisplayName("Should leave both mutations when the name of new global attribute mutation doesn't match")
	void shouldLeaveBothMutationsIfTheNameOfNewGlobalAttributeMutationDoesntMatch() {
		final ModifyAttributeSchemaTypeMutation mutation = new ModifyAttributeSchemaTypeMutation(
			ATTRIBUTE_NAME, String.class, 0
		);
		final ModifyAttributeSchemaTypeMutation existingMutation = new ModifyAttributeSchemaTypeMutation(
			"differentName", DateTimeRange.class, 2);
		assertNull(mutation.combineWith(Mockito.mock(CatalogSchemaContract.class), existingMutation));
	}

	@Test
	@DisplayName("Should override type of previous mutation when names match")
	void shouldOverrideTypeOfPreviousMutationIfNamesMatch() {
		final ModifyAttributeSchemaTypeMutation mutation = new ModifyAttributeSchemaTypeMutation(
			ATTRIBUTE_NAME, String.class, 0
		);
		final ModifyAttributeSchemaTypeMutation existingMutation = new ModifyAttributeSchemaTypeMutation(
			ATTRIBUTE_NAME, DateTimeRange.class, 2);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME)).thenReturn(of(createExistingEntityAttributeSchema()));
		final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(
			Mockito.mock(CatalogSchemaContract.class), entitySchema, existingMutation);
		assertNotNull(result);
		assertNull(result.origin());
		assertNotNull(result.current());
		assertInstanceOf(ModifyAttributeSchemaTypeMutation.class, result.current()[0]);
		assertEquals(String.class, ((ModifyAttributeSchemaTypeMutation) result.current()[0]).getType());
		assertEquals(0, ((ModifyAttributeSchemaTypeMutation) result.current()[0]).getIndexedDecimalPlaces());
	}

	@Test
	@DisplayName("Should leave both mutations when the name of new mutation doesn't match")
	void shouldLeaveBothMutationsIfTheNameOfNewMutationDoesntMatch() {
		final ModifyAttributeSchemaTypeMutation mutation = new ModifyAttributeSchemaTypeMutation(
			ATTRIBUTE_NAME, Integer.class, 2
		);
		final ModifyAttributeSchemaTypeMutation existingMutation = new ModifyAttributeSchemaTypeMutation(
			"differentName", String.class, 0);
		assertNull(
			mutation.combineWith(
				Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class),
				existingMutation
			));
	}

	@Test
	@DisplayName("Should mutate attribute schema")
	void shouldMutateAttributeSchema() {
		final ModifyAttributeSchemaTypeMutation mutation = new ModifyAttributeSchemaTypeMutation(
			ATTRIBUTE_NAME, Integer.class, 2
		);
		final AttributeSchemaContract mutatedSchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class), createExistingAttributeSchema(), AttributeSchemaContract.class);
		assertNotNull(mutatedSchema);
		assertEquals(Integer.class, mutatedSchema.getType());
	}

	@Test
	@DisplayName("Should mutate entity attribute schema")
	void shouldMutateEntityAttributeSchema() {
		final ModifyAttributeSchemaTypeMutation mutation = new ModifyAttributeSchemaTypeMutation(
			ATTRIBUTE_NAME, String.class, 0
		);
		final EntityAttributeSchemaContract mutatedSchema = mutation.mutate(
			Mockito.mock(CatalogSchemaContract.class), createExistingEntityAttributeSchema(),
			EntityAttributeSchemaContract.class
		);
		assertNotNull(mutatedSchema);
		assertEquals(String.class, mutatedSchema.getType());
		assertEquals(0, mutatedSchema.getIndexedDecimalPlaces());
	}

	@Test
	@DisplayName("Should mutate catalog schema")
	void shouldMutateCatalogSchema() {
		final ModifyAttributeSchemaTypeMutation mutation = new ModifyAttributeSchemaTypeMutation(
			ATTRIBUTE_NAME, String.class, 0
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
		final GlobalAttributeSchemaContract newAttributeSchema = newCatalogSchema.getAttribute(ATTRIBUTE_NAME)
			.orElseThrow();
		assertEquals(String.class, newAttributeSchema.getType());
		assertEquals(0, newAttributeSchema.getIndexedDecimalPlaces());
	}

	@Test
	@DisplayName("Should mutate entity schema")
	void shouldMutateEntitySchema() {
		final ModifyAttributeSchemaTypeMutation mutation = new ModifyAttributeSchemaTypeMutation(
			ATTRIBUTE_NAME, String.class, 0
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
		assertEquals(String.class, newAttributeSchema.getType());
		assertEquals(0, newAttributeSchema.getIndexedDecimalPlaces());
	}

	@Test
	@DisplayName("Should mutate reference schema")
	void shouldMutateReferenceSchema() {
		final ModifyAttributeSchemaTypeMutation mutation = new ModifyAttributeSchemaTypeMutation(
			ATTRIBUTE_NAME, String.class, 0
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
		assertEquals(String.class, newAttributeSchema.getType());
		assertEquals(0, newAttributeSchema.getIndexedDecimalPlaces());
	}

	@Test
	@DisplayName("Should throw exception when mutating entity schema with non-existing attribute")
	void shouldThrowExceptionWhenMutatingEntitySchemaWithNonExistingAttribute() {
		final ModifyAttributeSchemaTypeMutation mutation = new ModifyAttributeSchemaTypeMutation(
			ATTRIBUTE_NAME, Integer.class, 2
		);
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> {
				mutation.mutate(Mockito.mock(CatalogSchemaContract.class), Mockito.mock(EntitySchemaContract.class));
			}
		);
	}

	@Nested
	@DisplayName("filter index capabilities")
	class FilterCapabilities {

		@Test
		@DisplayName("Should refuse changing a SUBSTRING-accelerated entity attribute to a non-String type")
		void shouldRefuseChangingSubstringEntityAttributeToNonStringType() {
			// the rebuild carries the existing capabilities over verbatim, so without this check the mutation would
			// quietly produce `Integer + SUBSTRING` - a state the capability's own contract forbids
			final EntityAttributeSchemaContract accelerated = SetAttributeSchemaFilterableMutation
				.fromCapabilities(
					ATTRIBUTE_NAME, new ScopedFilterCapabilities(Scope.LIVE, FilterIndexCapability.SUBSTRING)
				)
				.mutate(null, createStringEntityAttributeSchema(), EntityAttributeSchemaContract.class);

			final ModifyAttributeSchemaTypeMutation mutation =
				new ModifyAttributeSchemaTypeMutation(ATTRIBUTE_NAME, Integer.class, 0);
			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> mutation.mutate(null, accelerated, EntityAttributeSchemaContract.class)
			);
			assertTrue(exception.getMessage().contains(FilterIndexCapability.SUBSTRING.name()));
			assertTrue(exception.getMessage().contains("String"));
		}

		@Test
		@DisplayName("Should refuse changing a SUBSTRING-accelerated global attribute to a non-String type")
		void shouldRefuseChangingSubstringGlobalAttributeToNonStringType() {
			final GlobalAttributeSchemaContract accelerated = SetAttributeSchemaFilterableMutation
				.fromCapabilities(
					ATTRIBUTE_NAME, new ScopedFilterCapabilities(Scope.LIVE, FilterIndexCapability.SUBSTRING)
				)
				.mutate(null, createStringGlobalAttributeSchema(), GlobalAttributeSchemaContract.class);

			assertThrows(
				InvalidSchemaMutationException.class,
				() -> new ModifyAttributeSchemaTypeMutation(ATTRIBUTE_NAME, Integer.class, 0)
					.mutate(null, accelerated, GlobalAttributeSchemaContract.class)
			);
		}

		@Test
		@DisplayName("Should allow changing a SUBSTRING-accelerated attribute between String and String array")
		void shouldAllowChangingSubstringAttributeBetweenStringAndStringArray() {
			final EntityAttributeSchemaContract accelerated = SetAttributeSchemaFilterableMutation
				.fromCapabilities(
					ATTRIBUTE_NAME, new ScopedFilterCapabilities(Scope.LIVE, FilterIndexCapability.SUBSTRING)
				)
				.mutate(null, createStringEntityAttributeSchema(), EntityAttributeSchemaContract.class);

			final EntityAttributeSchemaContract mutated = new ModifyAttributeSchemaTypeMutation(
				ATTRIBUTE_NAME, String[].class, 0
			).mutate(null, accelerated, EntityAttributeSchemaContract.class);

			assertEquals(String[].class, mutated.getType());
			assertEquals(
				Set.of(FilterIndexCapability.SUBSTRING), mutated.getFilterCapabilitiesInScope(Scope.LIVE)
			);
		}

		@Test
		@DisplayName("Should return the very same schema when the accelerated attribute keeps its type")
		void shouldReturnTheSameSchemaWhenTheAcceleratedAttributeKeepsItsType() {
			// the capability check runs *before* the unchanged-type short circuit, so re-applying `String` to a
			// SUBSTRING-accelerated attribute has to pass that check and then return the identical instance. Moving
			// the check below the short circuit would leave this passing; moving the short circuit above a check
			// that then rejected `String` would not - which is the ordering this pins.
			final EntityAttributeSchemaContract accelerated = SetAttributeSchemaFilterableMutation
				.fromCapabilities(
					ATTRIBUTE_NAME, new ScopedFilterCapabilities(Scope.LIVE, FilterIndexCapability.SUBSTRING)
				)
				.mutate(null, createStringEntityAttributeSchema(), EntityAttributeSchemaContract.class);

			assertSame(
				accelerated,
				new ModifyAttributeSchemaTypeMutation(ATTRIBUTE_NAME, String.class, 0)
					.mutate(null, accelerated, EntityAttributeSchemaContract.class)
			);
		}

		@Test
		@DisplayName("Should refuse building a schema whose type cannot support its declared capability")
		void shouldRefuseBuildingSchemaWhoseTypeCannotSupportCapability() {
			// the DTO-level backstop: no construction path, mutation or otherwise, may mint the invalid state
			final EnumMap<Scope, Set<FilterIndexCapability>> capabilities = new EnumMap<>(Scope.class);
			capabilities.put(Scope.LIVE, Set.of(FilterIndexCapability.SUBSTRING));
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> AttributeSchema._internalBuild(
					ATTRIBUTE_NAME, null, null,
					null,
					EnumSet.of(Scope.LIVE),
					capabilities,
					null,
					false, false, false,
					Integer.class, null, 0,
					ConflictResolutionOverride.INHERITED
				)
			);
		}
	}

}
