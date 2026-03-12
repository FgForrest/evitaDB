/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.core.expression.proxy;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.expression.proxy.ExpressionProxyInstantiator.InstantiationResult;
import io.evitadb.exception.ExpressionEvaluationException;
import io.evitadb.spi.store.catalog.persistence.accessor.EntityStoragePartAccessor;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AttributesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for nested entity proxy wiring in {@link ExpressionProxyInstantiator}. Verifies that
 * `$reference.referencedEntity.*` and `$reference.groupEntity?.*` expression paths result in nested
 * entity proxies being wired into the reference proxy's {@link ReferenceProxyState}.
 */
@DisplayName("Nested entity proxy wiring")
class NestedEntityProxyWiringTest {

	private static final int ENTITY_PK = 42;
	private static final int REFERENCED_ENTITY_PK = 100;
	private static final int GROUP_ENTITY_PK = 7;
	private static final String ENTITY_TYPE = "Product";
	private static final String REFERENCED_ENTITY_TYPE = "Brand";
	private static final String GROUP_ENTITY_TYPE = "ParameterGroup";
	private static final String REFERENCE_NAME = "brand";

	/**
	 * Creates a mock entity schema returning the given entity type name.
	 *
	 * @param entityType the entity type name
	 * @return mock entity schema
	 */
	@Nonnull
	private static EntitySchemaContract mockSchema(@Nonnull String entityType) {
		final EntitySchemaContract schema = mock(EntitySchemaContract.class);
		when(schema.getName()).thenReturn(entityType);
		when(schema.getAttributes()).thenReturn(Collections.emptyMap());
		return schema;
	}

	/**
	 * Creates a mock reference schema with the given name, referenced entity type, and optional group type.
	 *
	 * @param referenceName      the reference name
	 * @param referencedEntityType the referenced entity type
	 * @param groupType           the group entity type, or `null` if no group
	 * @return mock reference schema
	 */
	@SuppressWarnings("SameParameterValue")
	@Nonnull
	private static ReferenceSchemaContract mockReferenceSchema(
		@Nonnull String referenceName,
		@Nonnull String referencedEntityType,
		@Nullable String groupType
	) {
		final ReferenceSchemaContract refSchema = mock(ReferenceSchemaContract.class);
		when(refSchema.getName()).thenReturn(referenceName);
		when(refSchema.getReferencedEntityType()).thenReturn(referencedEntityType);
		when(refSchema.getReferencedGroupType()).thenReturn(groupType);
		when(refSchema.getCardinality()).thenReturn(Cardinality.ZERO_OR_MORE);
		when(refSchema.getAttributes()).thenReturn(Collections.emptyMap());
		return refSchema;
	}

	/**
	 * Creates an {@link AttributesStoragePart} with the given locale and sorted attribute values.
	 *
	 * @param entityPk the entity primary key
	 * @param values   sorted attribute values
	 * @return attributes storage part
	 */
	@Nonnull
	private static AttributesStoragePart createAttributesPart(
		int entityPk,
		@Nonnull AttributeValue... values
	) {
		final AttributesStoragePart part = new AttributesStoragePart(entityPk);
		for (final AttributeValue value : values) {
			final AttributeSchemaContract attrSchemaMock = mock(AttributeSchemaContract.class);
			doReturn(String.class).when(attrSchemaMock).getType();
			when(attrSchemaMock.getIndexedDecimalPlaces()).thenReturn(0);
			part.upsertAttribute(value.key(), attrSchemaMock, existing -> value);
		}
		return part;
	}

	/**
	 * Creates a schema resolver function that maps entity type names to their schemas.
	 *
	 * @param schemas map of entity type name to schema
	 * @return schema resolver function
	 */
	@Nonnull
	private static Function<String, EntitySchemaContract> schemaResolver(
		@Nonnull Map<String, EntitySchemaContract> schemas
	) {
		return name -> {
			final EntitySchemaContract schema = schemas.get(name);
			if (schema == null) {
				throw new IllegalStateException("No schema for entity type: " + name);
			}
			return schema;
		};
	}

	/**
	 * Builds an {@link ExpressionProxyDescriptor} for the given expression string.
	 *
	 * @param expression the expression string to parse and analyze
	 * @return the proxy descriptor
	 */
	@Nonnull
	private static ExpressionProxyDescriptor buildDescriptor(@Nonnull String expression) {
		return ExpressionProxyFactory.buildDescriptor(
			io.evitadb.api.query.expression.ExpressionFactory.parse(expression)
		);
	}

	@Test
	@DisplayName("Should wire referenced entity proxy into reference proxy")
	void shouldWireReferencedEntityProxyIntoReferenceProxy() {
		// set up schemas
		final EntitySchemaContract ownerSchema = mockSchema(ENTITY_TYPE);
		final ReferenceSchemaContract refSchema = mockReferenceSchema(
			REFERENCE_NAME, REFERENCED_ENTITY_TYPE, null
		);
		when(ownerSchema.getReference(REFERENCE_NAME)).thenReturn(Optional.of(refSchema));
		final EntitySchemaContract brandSchema = mockSchema(REFERENCED_ENTITY_TYPE);

		// set up storage accessor
		final EntityStoragePartAccessor accessor = mock(EntityStoragePartAccessor.class);

		// owner entity references
		final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, REFERENCED_ENTITY_PK, 1);
		final Reference ref = new Reference(ownerSchema, refSchema, refKey, null);
		final ReferencesStoragePart refsPart = new ReferencesStoragePart(
			ENTITY_PK, 1, new Reference[]{ ref }, -1
		);
		when(accessor.getReferencesStoragePart(ENTITY_TYPE, ENTITY_PK)).thenReturn(refsPart);

		// referenced entity (Brand) — has attribute "name" = "Nike"
		final AttributesStoragePart brandAttrs = createAttributesPart(
			REFERENCED_ENTITY_PK,
			new AttributeValue(new AttributeKey("name"), "Nike")
		);
		when(accessor.getAttributeStoragePart(REFERENCED_ENTITY_TYPE, REFERENCED_ENTITY_PK))
			.thenReturn(brandAttrs);

		// build descriptor for expression accessing referenced entity attributes
		final ExpressionProxyDescriptor descriptor = buildDescriptor(
			"$entity.references['brand'].referencedEntity.attributes['name'] == 'Nike'"
		);

		// instantiate
		final InstantiationResult result = ExpressionProxyInstantiator.instantiate(
			descriptor, ownerSchema, ENTITY_PK, refSchema, refKey, accessor,
			schemaResolver(Map.of(REFERENCED_ENTITY_TYPE, brandSchema))
		);

		// verify reference proxy's referencedEntity returns the nested proxy with correct attribute
		assertNotNull(result.referenceProxy(), "Reference proxy should not be null");
		final Optional<SealedEntity> referencedEntity = result.referenceProxy().getReferencedEntity();
		assertTrue(referencedEntity.isPresent(), "Referenced entity should be wired");
		assertEquals("Nike", referencedEntity.get().getAttribute("name"),
			"Nested entity proxy should return correct attribute value");
	}

	@Test
	@DisplayName("Should fetch referenced entity storage parts using referenced entity type and PK")
	void shouldFetchReferencedEntityStoragePartsIndependently() {
		final EntitySchemaContract ownerSchema = mockSchema(ENTITY_TYPE);
		final ReferenceSchemaContract refSchema = mockReferenceSchema(
			REFERENCE_NAME, REFERENCED_ENTITY_TYPE, null
		);
		when(ownerSchema.getReference(REFERENCE_NAME)).thenReturn(Optional.of(refSchema));
		final EntitySchemaContract brandSchema = mockSchema(REFERENCED_ENTITY_TYPE);

		final EntityStoragePartAccessor accessor = mock(EntityStoragePartAccessor.class);

		final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, REFERENCED_ENTITY_PK, 1);
		final Reference ref = new Reference(ownerSchema, refSchema, refKey, null);
		final ReferencesStoragePart refsPart = new ReferencesStoragePart(
			ENTITY_PK, 1, new Reference[]{ ref }, -1
		);
		when(accessor.getReferencesStoragePart(ENTITY_TYPE, ENTITY_PK)).thenReturn(refsPart);

		final AttributesStoragePart brandAttrs = createAttributesPart(
			REFERENCED_ENTITY_PK,
			new AttributeValue(new AttributeKey("name"), "Nike")
		);
		when(accessor.getAttributeStoragePart(REFERENCED_ENTITY_TYPE, REFERENCED_ENTITY_PK))
			.thenReturn(brandAttrs);

		final ExpressionProxyDescriptor descriptor = buildDescriptor(
			"$entity.references['brand'].referencedEntity.attributes['name'] == 'Nike'"
		);

		ExpressionProxyInstantiator.instantiate(
			descriptor, ownerSchema, ENTITY_PK, refSchema, refKey, accessor,
			schemaResolver(Map.of(REFERENCED_ENTITY_TYPE, brandSchema))
		);

		// verify storage parts were fetched using the REFERENCED entity type and PK (not the owner's)
		verify(accessor).getAttributeStoragePart(REFERENCED_ENTITY_TYPE, REFERENCED_ENTITY_PK);
	}

	@Test
	@DisplayName("Should compute independent partial set for nested referenced entity")
	void shouldComputeIndependentPartialSetForReferencedEntity() {
		// expression only accesses primaryKey on the referenced entity — no attributes needed
		final ExpressionProxyDescriptor descriptor = buildDescriptor(
			"$entity.references['brand'].referencedEntity.primaryKey > 0"
		);

		assertTrue(descriptor.needsReferencedEntityProxy());
		assertNotNull(descriptor.referencedEntityPartials());
		assertNotNull(descriptor.referencedEntityRecipe());
		// the nested recipe should need body (for primaryKey) but NOT global attributes
		assertTrue(descriptor.referencedEntityRecipe().needsEntityBody(),
			"Nested recipe should need entity body for primaryKey access");
		assertFalse(descriptor.referencedEntityRecipe().needsGlobalAttributes(),
			"Nested recipe should NOT need attributes when only primaryKey is accessed");
	}

	@Test
	@DisplayName("Should wire group entity proxy into reference proxy")
	void shouldWireGroupEntityProxyIntoReferenceProxy() {
		final EntitySchemaContract ownerSchema = mockSchema(ENTITY_TYPE);
		final ReferenceSchemaContract refSchema = mockReferenceSchema(
			REFERENCE_NAME, REFERENCED_ENTITY_TYPE, GROUP_ENTITY_TYPE
		);
		when(ownerSchema.getReference(REFERENCE_NAME)).thenReturn(Optional.of(refSchema));
		final EntitySchemaContract groupSchema = mockSchema(GROUP_ENTITY_TYPE);

		final EntityStoragePartAccessor accessor = mock(EntityStoragePartAccessor.class);

		// reference with group entity
		final GroupEntityReference groupRef = new GroupEntityReference(GROUP_ENTITY_TYPE, GROUP_ENTITY_PK);
		final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, REFERENCED_ENTITY_PK, 1);
		final Reference ref = new Reference(ownerSchema, refSchema, refKey, groupRef);
		final ReferencesStoragePart refsPart = new ReferencesStoragePart(
			ENTITY_PK, 1, new Reference[]{ ref }, -1
		);
		when(accessor.getReferencesStoragePart(ENTITY_TYPE, ENTITY_PK)).thenReturn(refsPart);

		// group entity — has attribute "type" = "CHECKBOX"
		final AttributesStoragePart groupAttrs = createAttributesPart(
			GROUP_ENTITY_PK,
			new AttributeValue(new AttributeKey("type"), "CHECKBOX")
		);
		when(accessor.getAttributeStoragePart(GROUP_ENTITY_TYPE, GROUP_ENTITY_PK))
			.thenReturn(groupAttrs);

		final ExpressionProxyDescriptor descriptor = buildDescriptor(
			"$entity.references['brand'].groupEntity.attributes['type'] == 'CHECKBOX'"
		);

		final InstantiationResult result = ExpressionProxyInstantiator.instantiate(
			descriptor, ownerSchema, ENTITY_PK, refSchema, refKey, accessor,
			schemaResolver(Map.of(GROUP_ENTITY_TYPE, groupSchema))
		);

		assertNotNull(result.referenceProxy());
		final Optional<SealedEntity> groupEntity = result.referenceProxy().getGroupEntity();
		assertTrue(groupEntity.isPresent(), "Group entity should be wired");
		assertEquals("CHECKBOX", groupEntity.get().getAttribute("type"),
			"Nested group entity proxy should return correct attribute value");
	}

	@Test
	@DisplayName("Should use group entity type and PK for group entity proxy storage fetching")
	void shouldUseGroupEntityTypeAndPkForGroupProxy() {
		final EntitySchemaContract ownerSchema = mockSchema(ENTITY_TYPE);
		final ReferenceSchemaContract refSchema = mockReferenceSchema(
			REFERENCE_NAME, REFERENCED_ENTITY_TYPE, GROUP_ENTITY_TYPE
		);
		when(ownerSchema.getReference(REFERENCE_NAME)).thenReturn(Optional.of(refSchema));
		final EntitySchemaContract groupSchema = mockSchema(GROUP_ENTITY_TYPE);

		final EntityStoragePartAccessor accessor = mock(EntityStoragePartAccessor.class);

		final GroupEntityReference groupRef = new GroupEntityReference(GROUP_ENTITY_TYPE, GROUP_ENTITY_PK);
		final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, REFERENCED_ENTITY_PK, 1);
		final Reference ref = new Reference(ownerSchema, refSchema, refKey, groupRef);
		final ReferencesStoragePart refsPart = new ReferencesStoragePart(
			ENTITY_PK, 1, new Reference[]{ ref }, -1
		);
		when(accessor.getReferencesStoragePart(ENTITY_TYPE, ENTITY_PK)).thenReturn(refsPart);

		final EntityBodyStoragePart groupBody = new EntityBodyStoragePart(GROUP_ENTITY_PK);
		when(accessor.getEntityStoragePart(GROUP_ENTITY_TYPE, GROUP_ENTITY_PK, EntityExistence.MUST_EXIST))
			.thenReturn(groupBody);

		final ExpressionProxyDescriptor descriptor = buildDescriptor(
			"$entity.references['brand'].groupEntity.primaryKey > 0"
		);

		ExpressionProxyInstantiator.instantiate(
			descriptor, ownerSchema, ENTITY_PK, refSchema, refKey, accessor,
			schemaResolver(Map.of(GROUP_ENTITY_TYPE, groupSchema))
		);

		// verify storage parts fetched using GROUP entity type and PK
		verify(accessor).getEntityStoragePart(GROUP_ENTITY_TYPE, GROUP_ENTITY_PK, EntityExistence.MUST_EXIST);
	}

	@Test
	@DisplayName("Should return empty group entity when reference has no group")
	void shouldReturnEmptyGroupEntityWhenReferenceHasNoGroup() {
		final EntitySchemaContract ownerSchema = mockSchema(ENTITY_TYPE);
		final ReferenceSchemaContract refSchema = mockReferenceSchema(
			REFERENCE_NAME, REFERENCED_ENTITY_TYPE, GROUP_ENTITY_TYPE
		);
		when(ownerSchema.getReference(REFERENCE_NAME)).thenReturn(Optional.of(refSchema));

		final EntityStoragePartAccessor accessor = mock(EntityStoragePartAccessor.class);

		// reference WITHOUT a group
		final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, REFERENCED_ENTITY_PK, 1);
		final Reference ref = new Reference(ownerSchema, refSchema, refKey, null);
		final ReferencesStoragePart refsPart = new ReferencesStoragePart(
			ENTITY_PK, 1, new Reference[]{ ref }, -1
		);
		when(accessor.getReferencesStoragePart(ENTITY_TYPE, ENTITY_PK)).thenReturn(refsPart);

		final ExpressionProxyDescriptor descriptor = buildDescriptor(
			"$entity.references['brand'].groupEntity.primaryKey > 0"
		);

		final InstantiationResult result = ExpressionProxyInstantiator.instantiate(
			descriptor, ownerSchema, ENTITY_PK, refSchema, refKey, accessor,
			schemaResolver(Map.of(GROUP_ENTITY_TYPE, mockSchema(GROUP_ENTITY_TYPE)))
		);

		assertNotNull(result.referenceProxy());
		final Optional<SealedEntity> groupEntity = result.referenceProxy().getGroupEntity();
		assertTrue(groupEntity.isEmpty(), "Group entity should be empty when reference has no group");
	}

	@Test
	@DisplayName("Should implement SealedEntity interface on nested proxy")
	void shouldImplementSealedEntityInterface() {
		final EntitySchemaContract ownerSchema = mockSchema(ENTITY_TYPE);
		final ReferenceSchemaContract refSchema = mockReferenceSchema(
			REFERENCE_NAME, REFERENCED_ENTITY_TYPE, null
		);
		when(ownerSchema.getReference(REFERENCE_NAME)).thenReturn(Optional.of(refSchema));
		final EntitySchemaContract brandSchema = mockSchema(REFERENCED_ENTITY_TYPE);

		final EntityStoragePartAccessor accessor = mock(EntityStoragePartAccessor.class);

		final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, REFERENCED_ENTITY_PK, 1);
		final Reference ref = new Reference(ownerSchema, refSchema, refKey, null);
		final ReferencesStoragePart refsPart = new ReferencesStoragePart(
			ENTITY_PK, 1, new Reference[]{ ref }, -1
		);
		when(accessor.getReferencesStoragePart(ENTITY_TYPE, ENTITY_PK)).thenReturn(refsPart);

		final ExpressionProxyDescriptor descriptor = buildDescriptor(
			"true || $entity.references['brand'].*[$.referencedEntity]"
		);

		final InstantiationResult result = ExpressionProxyInstantiator.instantiate(
			descriptor, ownerSchema, ENTITY_PK, refSchema, refKey, accessor,
			schemaResolver(Map.of(REFERENCED_ENTITY_TYPE, brandSchema))
		);

		assertNotNull(result.referenceProxy());
		final Optional<SealedEntity> referencedEntity = result.referenceProxy().getReferencedEntity();
		assertTrue(referencedEntity.isPresent());
		assertInstanceOf(SealedEntity.class, referencedEntity.get(),
			"Nested entity proxy must implement SealedEntity");
	}

	@Test
	@DisplayName("Should throw ExpressionEvaluationException for SealedEntity-only methods via CatchAll")
	void shouldThrowForSealedEntityMethodsViaCatchAll() {
		final EntitySchemaContract ownerSchema = mockSchema(ENTITY_TYPE);
		final ReferenceSchemaContract refSchema = mockReferenceSchema(
			REFERENCE_NAME, REFERENCED_ENTITY_TYPE, null
		);
		when(ownerSchema.getReference(REFERENCE_NAME)).thenReturn(Optional.of(refSchema));
		final EntitySchemaContract brandSchema = mockSchema(REFERENCED_ENTITY_TYPE);

		final EntityStoragePartAccessor accessor = mock(EntityStoragePartAccessor.class);

		final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, REFERENCED_ENTITY_PK, 1);
		final Reference ref = new Reference(ownerSchema, refSchema, refKey, null);
		final ReferencesStoragePart refsPart = new ReferencesStoragePart(
			ENTITY_PK, 1, new Reference[]{ ref }, -1
		);
		when(accessor.getReferencesStoragePart(ENTITY_TYPE, ENTITY_PK)).thenReturn(refsPart);

		final ExpressionProxyDescriptor descriptor = buildDescriptor(
			"true || $entity.references['brand'].*[$.referencedEntity]"
		);

		final InstantiationResult result = ExpressionProxyInstantiator.instantiate(
			descriptor, ownerSchema, ENTITY_PK, refSchema, refKey, accessor,
			schemaResolver(Map.of(REFERENCED_ENTITY_TYPE, brandSchema))
		);

		final SealedEntity nestedProxy = result.referenceProxy().getReferencedEntity().orElseThrow();
		// openForWrite() is a SealedInstance method — must be caught by CatchAllPartial
		assertThrows(ExpressionEvaluationException.class, nestedProxy::openForWrite,
			"SealedEntity-only methods should throw ExpressionEvaluationException");
	}
}
