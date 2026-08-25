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
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.FilterIndexCapability;
import io.evitadb.api.requestResponse.schema.dto.EntityAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Tag;

import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.SCHEMA;
import static io.evitadb.test.TestTags.ATTRIBUTE;

/**
 * This test verifies {@link CreateAttributeSchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("CreateAttributeSchemaMutation")
@Tag(CONTRACT)
@Tag(SCHEMA)
@Tag(ATTRIBUTE)
class CreateAttributeSchemaMutationTest {
	static final String ATTRIBUTE_NAME = "name";

	@Nonnull
	static EntityAttributeSchemaContract createExistingEntityAttributeSchema() {
		return EntityAttributeSchema._internalBuild(
			ATTRIBUTE_NAME,
			"oldDescription",
			"oldDeprecationNotice",
			new ScopedAttributeUniquenessType[] {
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.NOT_UNIQUE)
			},
			Scope.NO_SCOPE,
			null,
			Scope.NO_SCOPE,
			false,
			false,
			false,
			Integer.class,
			null,
			2,
			ConflictResolutionOverride.INHERITED
		);
	}

	@Nonnull
	static GlobalAttributeSchemaContract createExistingGlobalAttributeSchema() {
		return GlobalAttributeSchema._internalBuild(
			ATTRIBUTE_NAME,
			"description",
			"deprecationNotice",
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.NOT_UNIQUE)
			},
			new ScopedGlobalAttributeUniquenessType[]{
				new ScopedGlobalAttributeUniquenessType(Scope.LIVE, GlobalAttributeUniquenessType.NOT_UNIQUE)
			},
			Scope.NO_SCOPE,
			null,
			Scope.NO_SCOPE,
			false,
			false,
			false,
			Integer.class,
			null,
			2,
			ConflictResolutionOverride.INHERITED
		);
	}

	@Nonnull
	static AttributeSchemaContract createExistingAttributeSchema() {
		return AttributeSchema._internalBuild(
			ATTRIBUTE_NAME,
			"oldDescription",
			"oldDeprecationNotice",
			new ScopedAttributeUniquenessType[]{
				new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.NOT_UNIQUE)
			},
			Scope.NO_SCOPE,
			null,
			Scope.NO_SCOPE,
			false,
			false,
			false,
			Integer.class,
			null,
			2,
			ConflictResolutionOverride.INHERITED
		);
	}

	@Nonnull
	static ReferenceSchemaContract createMockedReferenceSchema() {
		final ReferenceSchemaContract referenceSchema = Mockito.mock(ReferenceSchema.class);
		Mockito.when(referenceSchema.getName()).thenReturn("referenceName");
		Mockito.when(referenceSchema.getReferencedEntityType()).thenReturn("abd");
		return referenceSchema;
	}

	/**
	 * Builds a plain, non-filterable, capability-free attribute schema of the requested type. The `String` and
	 * `String[]` forms are what the filter-capability tests across this package start from, since
	 * {@link #createExistingAttributeSchema()} declares an `Integer` attribute the substring capability refuses on
	 * type alone.
	 *
	 * @param type the attribute data type
	 * @return the attribute schema
	 */
	@Nonnull
	static AttributeSchemaContract createAttributeSchemaOfType(@Nonnull Class<? extends Serializable> type) {
		return AttributeSchema._internalBuild(
			ATTRIBUTE_NAME, null, null,
			(ScopedAttributeUniquenessType[]) null,
			Scope.NO_SCOPE, null, Scope.NO_SCOPE,
			false, false, false,
			type, null, 0,
			ConflictResolutionOverride.INHERITED
		);
	}

	/**
	 * The `String` form of {@link #createAttributeSchemaOfType(Class)}.
	 *
	 * @return a non-filterable, capability-free `String` attribute schema
	 */
	@Nonnull
	static AttributeSchemaContract createStringAttributeSchema() {
		return createAttributeSchemaOfType(String.class);
	}

	/**
	 * The entity-level counterpart of {@link #createStringAttributeSchema()} - a distinct DTO with its own
	 * `_internalBuild` arm and its own serializer, so it cannot be a wrapper of the plain form.
	 *
	 * @return a non-filterable, capability-free `String` entity attribute schema
	 */
	@Nonnull
	static EntityAttributeSchemaContract createStringEntityAttributeSchema() {
		return EntityAttributeSchema._internalBuild(
			ATTRIBUTE_NAME, null, null,
			(ScopedAttributeUniquenessType[]) null,
			Scope.NO_SCOPE, null, Scope.NO_SCOPE,
			false, false, false,
			String.class, null, 0,
			ConflictResolutionOverride.INHERITED
		);
	}

	/**
	 * The catalog-level counterpart of {@link #createStringAttributeSchema()}.
	 *
	 * @return a non-filterable, capability-free `String` global attribute schema
	 */
	@Nonnull
	static GlobalAttributeSchemaContract createStringGlobalAttributeSchema() {
		// the untyped nulls would leave the array- and map-shaped overloads equally applicable
		return GlobalAttributeSchema._internalBuild(
			ATTRIBUTE_NAME, null, null,
			(ScopedAttributeUniquenessType[]) null,
			(ScopedGlobalAttributeUniquenessType[]) null,
			Scope.NO_SCOPE, null, Scope.NO_SCOPE,
			false, false, false,
			String.class, null, 0,
			ConflictResolutionOverride.INHERITED
		);
	}

	@Test
	@DisplayName("Should throw exception when invalid type is provided")
	void shouldThrowExceptionWhenInvalidTypeIsProvided() {
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> new CreateAttributeSchemaMutation(
				ATTRIBUTE_NAME, "description", "deprecationNotice",
				AttributeUniquenessType.NOT_UNIQUE, false, false, false, false, false,
				Serializable.class, null, 2
			)
		);
	}

	@Test
	@DisplayName("Should be replaced with individual mutations when attribute was removed and created with different settings")
	void shouldBeReplacedWithIndividualMutationsWhenAttributeWasRemovedAndCreatedWithDifferentSettings() {
		final CreateAttributeSchemaMutation mutation = new CreateAttributeSchemaMutation(
			ATTRIBUTE_NAME, "description", "deprecationNotice",
			AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION, true, true, true, true, true,
			String.class, "abc", 0
		);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME))
			.thenReturn(
				of(createExistingEntityAttributeSchema())
			);
		final RemoveAttributeSchemaMutation removeMutation = new RemoveAttributeSchemaMutation(ATTRIBUTE_NAME);
		final MutationCombinationResult<LocalEntitySchemaMutation> result = mutation.combineWith(
			Mockito.mock(CatalogSchemaContract.class),
			entitySchema,
			removeMutation
		);
		assertNotNull(result);
		assertFalse(result.discarded());
		assertEquals(10, result.current().length);
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof ModifyAttributeSchemaDescriptionMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof ModifyAttributeSchemaDeprecationNoticeMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof ModifyAttributeSchemaTypeMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof ModifyAttributeSchemaDefaultValueMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof SetAttributeSchemaLocalizedMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof SetAttributeSchemaNullableMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof SetAttributeSchemaUniqueMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof SetAttributeSchemaFilterableMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof SetAttributeSchemaSortableMutation));
		assertTrue(Arrays.stream(result.current()).anyMatch(m -> m instanceof SetAttributeSchemaRepresentativeMutation));
	}

	@Test
	@DisplayName("Should leave mutation intact when removal mutation targets different attribute")
	void shouldLeaveMutationIntactWhenRemovalMutationTargetsDifferentAttribute() {
		final CreateAttributeSchemaMutation mutation = new CreateAttributeSchemaMutation(
			ATTRIBUTE_NAME, "description", "deprecationNotice",
			AttributeUniquenessType.NOT_UNIQUE, false, false, false, false, false,
			String.class, null, 0
		);
		final RemoveAttributeSchemaMutation removeMutation = new RemoveAttributeSchemaMutation("differentName");
		assertNull(mutation.combineWith(null, null, removeMutation));
	}

	@Test
	@DisplayName("Should create entity attribute")
	void shouldCreateEntityAttribute() {
		final CreateAttributeSchemaMutation mutation = new CreateAttributeSchemaMutation(
			ATTRIBUTE_NAME, "description", "deprecationNotice",
			new ScopedAttributeUniquenessType[]{ new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.NOT_UNIQUE) },
			Scope.NO_SCOPE, Scope.NO_SCOPE, true, true, false,
			String.class, null, 0
		);
		final EntityAttributeSchemaContract attributeSchema = mutation.mutate(Mockito.mock(CatalogSchemaContract.class), null, EntityAttributeSchemaContract.class);
		assertNotNull(attributeSchema);
		assertEquals(ATTRIBUTE_NAME, attributeSchema.getName());
		assertEquals("description", attributeSchema.getDescription());
		assertEquals("deprecationNotice", attributeSchema.getDeprecationNotice());
		assertEquals(String.class, attributeSchema.getType());
		assertTrue(attributeSchema.isLocalized());
		assertTrue(attributeSchema.isNullable());
		assertFalse(attributeSchema.isSortable());
		assertTrue(attributeSchema.getSortableInScopes().isEmpty());
		assertFalse(attributeSchema.isFilterable());
		assertTrue(attributeSchema.getFilterableInScopes().isEmpty());
		assertEquals(AttributeUniquenessType.NOT_UNIQUE, attributeSchema.getUniquenessType());
		assertNull(attributeSchema.getDefaultValue());
		assertEquals(0, attributeSchema.getIndexedDecimalPlaces());
	}

	@Test
	@DisplayName("Should create reference attribute")
	void shouldCreateReferenceAttribute() {
		final CreateAttributeSchemaMutation mutation = new CreateAttributeSchemaMutation(
			ATTRIBUTE_NAME, "description", "deprecationNotice",
			new ScopedAttributeUniquenessType[]{ new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.NOT_UNIQUE) },
			Scope.NO_SCOPE, Scope.NO_SCOPE, true, true, false,
			String.class, null, 0
		);
		final AttributeSchemaContract attributeSchema = mutation.mutate(Mockito.mock(CatalogSchemaContract.class), null, AttributeSchemaContract.class);
		assertNotNull(attributeSchema);
		assertEquals(ATTRIBUTE_NAME, attributeSchema.getName());
		assertEquals("description", attributeSchema.getDescription());
		assertEquals("deprecationNotice", attributeSchema.getDeprecationNotice());
		assertEquals(String.class, attributeSchema.getType());
		assertTrue(attributeSchema.isLocalized());
		assertTrue(attributeSchema.isNullable());
		assertFalse(attributeSchema.isSortable());
		assertTrue(attributeSchema.getSortableInScopes().isEmpty());
		assertFalse(attributeSchema.isFilterable());
		assertTrue(attributeSchema.getFilterableInScopes().isEmpty());
		assertEquals(AttributeUniquenessType.NOT_UNIQUE, attributeSchema.getUniquenessType());
		assertNull(attributeSchema.getDefaultValue());
		assertEquals(0, attributeSchema.getIndexedDecimalPlaces());
	}

	@Test
	@DisplayName("Should create attribute in entity")
	void shouldCreateAttributeInEntity() {
		final CreateAttributeSchemaMutation mutation = new CreateAttributeSchemaMutation(
			ATTRIBUTE_NAME, "description", "deprecationNotice",
			new ScopedAttributeUniquenessType[]{ new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.NOT_UNIQUE) },
			Scope.NO_SCOPE, Scope.NO_SCOPE, true, true, false,
			String.class, null, 0
		);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.version()).thenReturn(1);
		final EntitySchemaContract newEntitySchema = mutation.mutate(Mockito.mock(CatalogSchemaContract.class), entitySchema);
		assertNotNull(newEntitySchema);
		assertEquals(2, newEntitySchema.version());
		final AttributeSchemaContract attributeSchema = newEntitySchema.getAttribute(ATTRIBUTE_NAME).orElseThrow();
		assertNotNull(attributeSchema);
		assertEquals(ATTRIBUTE_NAME, attributeSchema.getName());
		assertEquals("description", attributeSchema.getDescription());
		assertEquals("deprecationNotice", attributeSchema.getDeprecationNotice());
		assertEquals(String.class, attributeSchema.getType());
		assertTrue(attributeSchema.isLocalized());
		assertTrue(attributeSchema.isNullable());
		assertFalse(attributeSchema.isSortable());
		assertTrue(attributeSchema.getSortableInScopes().isEmpty());
		assertFalse(attributeSchema.isFilterable());
		assertTrue(attributeSchema.getFilterableInScopes().isEmpty());
		assertEquals(AttributeUniquenessType.NOT_UNIQUE, attributeSchema.getUniquenessType());
		assertNull(attributeSchema.getDefaultValue());
		assertEquals(0, attributeSchema.getIndexedDecimalPlaces());
	}

	@Test
	@DisplayName("Should create attribute in reference")
	void shouldCreateAttributeInReference() {
		final CreateAttributeSchemaMutation mutation = new CreateAttributeSchemaMutation(
			ATTRIBUTE_NAME, "description", "deprecationNotice",
			new ScopedAttributeUniquenessType[]{ new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.NOT_UNIQUE) },
			Scope.NO_SCOPE, Scope.NO_SCOPE, true, true, false,
			String.class, null, 0
		);
		final ReferenceSchemaContract referenceSchema = createMockedReferenceSchema();
		final ReferenceSchemaContract newReferenceSchema = mutation.mutate(Mockito.mock(EntitySchemaContract.class), referenceSchema);
		assertNotNull(newReferenceSchema);
		final AttributeSchemaContract attributeSchema = newReferenceSchema.getAttribute(ATTRIBUTE_NAME).orElseThrow();
		assertNotNull(attributeSchema);
		assertEquals(ATTRIBUTE_NAME, attributeSchema.getName());
		assertEquals("description", attributeSchema.getDescription());
		assertEquals("deprecationNotice", attributeSchema.getDeprecationNotice());
		assertEquals(String.class, attributeSchema.getType());
		assertTrue(attributeSchema.isLocalized());
		assertTrue(attributeSchema.isNullable());
		assertFalse(attributeSchema.isSortable());
		assertTrue(attributeSchema.getSortableInScopes().isEmpty());
		assertFalse(attributeSchema.isFilterable());
		assertTrue(attributeSchema.getFilterableInScopes().isEmpty());
		assertEquals(AttributeUniquenessType.NOT_UNIQUE, attributeSchema.getUniquenessType(Scope.LIVE));
		assertNull(attributeSchema.getDefaultValue());
		assertEquals(0, attributeSchema.getIndexedDecimalPlaces());
	}

	@Test
	@DisplayName("Should throw exception when mutating entity schema with existing attribute")
	void shouldThrowExceptionWhenMutatingEntitySchemaWithExistingAttribute() {
		final CreateAttributeSchemaMutation mutation = new CreateAttributeSchemaMutation(
			ATTRIBUTE_NAME, "description", "deprecationNotice",
			new ScopedAttributeUniquenessType[]{ new ScopedAttributeUniquenessType(Scope.LIVE, AttributeUniquenessType.NOT_UNIQUE) },
			Scope.NO_SCOPE, Scope.NO_SCOPE, false, false, false,
			String.class, null, 0
		);
		assertThrows(
			InvalidSchemaMutationException.class,
			() -> {
				final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
				Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME))
					.thenReturn(of(createExistingEntityAttributeSchema()));
				mutation.mutate(Mockito.mock(CatalogSchemaContract.class), entitySchema);
			}
		);
	}

	@Nested
	@DisplayName("filter index capabilities")
	class FilterCapabilities {

		@Test
		@DisplayName("Should carry the filter capabilities through to the created attribute schema")
		void shouldCarryFilterCapabilitiesToCreatedSchema() {
			final CreateAttributeSchemaMutation mutation = new CreateAttributeSchemaMutation(
				ATTRIBUTE_NAME, null, null,
				null,
				Scope.DEFAULT_SCOPES,
				new ScopedFilterCapabilities[]{
					new ScopedFilterCapabilities(Scope.LIVE, FilterIndexCapability.SUBSTRING)
				},
				Scope.NO_SCOPE, false, false, false,
				String.class, null, 0,
				ConflictResolutionOverride.INHERITED
			);
			final AttributeSchemaContract created = mutation.mutate(
				null, null, AttributeSchemaContract.class
			);
			assertEquals(
				Set.of(FilterIndexCapability.SUBSTRING), created.getFilterCapabilitiesInScope(Scope.LIVE)
			);
		}

		@Test
		@DisplayName("Should default the filter capabilities to none when the field is absent on the wire")
		void shouldDefaultFilterCapabilitiesToNoneWhenAbsentOnTheWire() {
			final CreateAttributeSchemaMutation mutation = new CreateAttributeSchemaMutation(
				ATTRIBUTE_NAME, null, null,
				null, Scope.DEFAULT_SCOPES, null, Scope.NO_SCOPE, false, false, false,
				String.class, null, 0,
				ConflictResolutionOverride.INHERITED
			);
			assertNotNull(mutation.getFilterCapabilitiesInScopes());
			assertEquals(0, mutation.getFilterCapabilitiesInScopes().length);
		}

		@Test
		@DisplayName("Should refuse creating a non-String attribute that declares the substring capability")
		void shouldRefuseCreatingNonStringAttributeWithSubstringCapability() {
			// no set-filterable mutation follows a create assembled field by field over the wire, so the create mutation
			// has to make this check itself
			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> new CreateAttributeSchemaMutation(
					ATTRIBUTE_NAME, null, null,
					null,
					Scope.DEFAULT_SCOPES,
					new ScopedFilterCapabilities[]{
						new ScopedFilterCapabilities(Scope.LIVE, FilterIndexCapability.SUBSTRING)
					},
					Scope.NO_SCOPE, false, false, false,
					Integer.class, null, 0,
					ConflictResolutionOverride.INHERITED
				)
			);
			assertTrue(exception.getMessage().contains("String"));
		}

		@Test
		@DisplayName("Should refuse creating an attribute whose capability scope is not filterable")
		void shouldRefuseCreatingAttributeWithCapabilityInNonFilterableScope() {
			assertThrows(
				InvalidSchemaMutationException.class,
				() -> new CreateAttributeSchemaMutation(
					ATTRIBUTE_NAME, null, null,
					null,
					new Scope[]{Scope.LIVE},
					new ScopedFilterCapabilities[]{
						new ScopedFilterCapabilities(Scope.ARCHIVED, FilterIndexCapability.SUBSTRING)
					},
					Scope.NO_SCOPE, false, false, false,
					String.class, null, 0,
					ConflictResolutionOverride.INHERITED
				)
			);
		}

		@Test
		@DisplayName("Should refuse creating a reference attribute that declares the substring capability")
		void shouldRefuseCreatingReferenceAttributeWithSubstringCapability() {
			// only at mutate time does the mutation learn it is targeting a reference - the same mutation class serves
			// entity attributes too, where the capability is perfectly legal
			final CreateAttributeSchemaMutation mutation = new CreateAttributeSchemaMutation(
				ATTRIBUTE_NAME, null, null,
				null,
				Scope.DEFAULT_SCOPES,
				new ScopedFilterCapabilities[]{
					new ScopedFilterCapabilities(Scope.LIVE, FilterIndexCapability.SUBSTRING)
				},
				Scope.NO_SCOPE, false, false, false,
				String.class, null, 0,
				ConflictResolutionOverride.INHERITED
			);
			final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
			Mockito.when(entitySchema.getName()).thenReturn("product");
			final InvalidSchemaMutationException exception = assertThrows(
				InvalidSchemaMutationException.class,
				() -> mutation.mutate(entitySchema, createMockedReferenceSchema())
			);
			assertTrue(exception.getMessage().contains(FilterIndexCapability.SUBSTRING.name()));
			assertTrue(exception.getMessage().contains("referenceName"));
		}

		@Test
		@DisplayName("Should still allow creating a plainly filterable reference attribute")
		void shouldStillAllowCreatingPlainlyFilterableReferenceAttribute() {
			final CreateAttributeSchemaMutation mutation = new CreateAttributeSchemaMutation(
				ATTRIBUTE_NAME, null, null,
				null, Scope.DEFAULT_SCOPES, null, Scope.NO_SCOPE, false, false, false,
				String.class, null, 0,
				ConflictResolutionOverride.INHERITED
			);
			final ReferenceSchemaContract mutated = mutation.mutate(
				Mockito.mock(EntitySchemaContract.class), createMockedReferenceSchema()
			);
			assertNotNull(mutated);
			assertTrue(mutated.getAttribute(ATTRIBUTE_NAME).orElseThrow().isFilterable());
		}
	}

}
