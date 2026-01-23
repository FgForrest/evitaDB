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
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.EntityAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Arrays;

import static java.util.Optional.of;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies {@link CreateAttributeSchemaMutation} class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
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
			Scope.NO_SCOPE,
			false,
			false,
			false,
			Integer.class,
			null,
			2
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
			Scope.NO_SCOPE,
			false,
			false,
			false,
			Integer.class,
			null,
			2
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
			Scope.NO_SCOPE,
			false,
			false,
			false,
			Integer.class,
			null,
			2
		);
	}

	@Nonnull
	static ReferenceSchemaContract createMockedReferenceSchema() {
		final ReferenceSchemaContract referenceSchema = Mockito.mock(ReferenceSchema.class);
		Mockito.when(referenceSchema.getName()).thenReturn("referenceName");
		Mockito.when(referenceSchema.getReferencedEntityType()).thenReturn("abd");
		return referenceSchema;
	}

	@Test
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
	void shouldBeReplacedWithIndividualMutationsWhenAttributeWasRemovedAndCreatedWithDifferentSettings() {
		CreateAttributeSchemaMutation mutation = new CreateAttributeSchemaMutation(
			ATTRIBUTE_NAME, "description", "deprecationNotice",
			AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION, true, true, true, true, true,
			String.class, "abc", 0
		);
		final EntitySchemaContract entitySchema = Mockito.mock(EntitySchemaContract.class);
		Mockito.when(entitySchema.getAttribute(ATTRIBUTE_NAME))
			.thenReturn(
				of(createExistingEntityAttributeSchema())
			);
		RemoveAttributeSchemaMutation removeMutation = new RemoveAttributeSchemaMutation(ATTRIBUTE_NAME);
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
	void shouldLeaveMutationIntactWhenRemovalMutationTargetsDifferentAttribute() {
		CreateAttributeSchemaMutation mutation = new CreateAttributeSchemaMutation(
			ATTRIBUTE_NAME, "description", "deprecationNotice",
			AttributeUniquenessType.NOT_UNIQUE, false, false, false, false, false,
			String.class, null, 0
		);
		RemoveAttributeSchemaMutation removeMutation = new RemoveAttributeSchemaMutation("differentName");
		assertNull(mutation.combineWith(null, null, removeMutation));
	}

	@Test
	void shouldCreateEntityAttribute() {
		CreateAttributeSchemaMutation mutation = new CreateAttributeSchemaMutation(
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
	void shouldCreateReferenceAttribute() {
		CreateAttributeSchemaMutation mutation = new CreateAttributeSchemaMutation(
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
	void shouldCreateAttributeInEntity() {
		CreateAttributeSchemaMutation mutation = new CreateAttributeSchemaMutation(
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
	void shouldCreateAttributeInReference() {
		CreateAttributeSchemaMutation mutation = new CreateAttributeSchemaMutation(
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
	void shouldThrowExceptionWhenMutatingEntitySchemaWithExistingAttribute() {
		CreateAttributeSchemaMutation mutation = new CreateAttributeSchemaMutation(
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

}
