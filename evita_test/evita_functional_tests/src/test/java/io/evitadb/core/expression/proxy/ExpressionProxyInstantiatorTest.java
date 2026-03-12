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

import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.expression.proxy.ExpressionProxyInstantiator.InstantiationResult;
import io.evitadb.exception.ExpressionEvaluationException;
import io.evitadb.spi.store.catalog.persistence.accessor.EntityStoragePartAccessor;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AttributesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.EntityBodyStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;
import one.edee.oss.proxycian.PredicateMethodClassification;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyDispatcherInvocationHandler;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyProxyGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static io.evitadb.utils.ArrayUtils.EMPTY_CLASS_ARRAY;
import static io.evitadb.utils.ArrayUtils.EMPTY_OBJECT_ARRAY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link ExpressionProxyInstantiator} verifying that the trigger-time proxy instantiation correctly fetches
 * storage parts per recipe and produces working proxy objects.
 */
@DisplayName("Expression proxy instantiator")
class ExpressionProxyInstantiatorTest {

	private static final int ENTITY_PK = 42;
	private static final int REFERENCED_ENTITY_PK = 100;
	private static final int GROUP_ENTITY_PK = 7;
	private static final String ENTITY_TYPE = "Product";
	private static final String REFERENCE_NAME = "brand";
	private static final String REFERENCED_ENTITY_TYPE = "Brand";
	private static final String GROUP_ENTITY_TYPE = "ParameterGroup";

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
		return schema;
	}

	/**
	 * Creates a mock reference schema with the given name and referenced entity type.
	 *
	 * @param referenceName        the reference name
	 * @param referencedEntityType the referenced entity type
	 * @return mock reference schema
	 */
	@Nonnull
	private static ReferenceSchemaContract mockReferenceSchema(
		@Nonnull String referenceName,
		@Nonnull String referencedEntityType
	) {
		return mockReferenceSchema(referenceName, referencedEntityType, null);
	}

	/**
	 * Creates a mock reference schema with the given name, referenced entity type, and optional group type.
	 *
	 * @param referenceName        the reference name
	 * @param referencedEntityType the referenced entity type
	 * @param groupType            the group entity type, or `null` if no group
	 * @return mock reference schema
	 */
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
	 * Creates an {@link AttributesStoragePart} with the given locale and sorted attribute values.
	 *
	 * @param locale the locale, or `null` for global
	 * @param values sorted attribute values
	 * @return attributes storage part
	 */
	@Nonnull
	private static AttributesStoragePart createAttributesPart(
		@Nullable Locale locale,
		@Nonnull AttributeValue... values
	) {
		final AttributesStoragePart part;
		if (locale == null) {
			part = new AttributesStoragePart(ENTITY_PK);
		} else {
			part = new AttributesStoragePart(ENTITY_PK, locale);
		}
		for (final AttributeValue value : values) {
			final io.evitadb.api.requestResponse.schema.AttributeSchemaContract attrSchemaMock =
				mock(io.evitadb.api.requestResponse.schema.AttributeSchemaContract.class);
			doReturn(String.class).when(attrSchemaMock).getType();
			when(attrSchemaMock.getIndexedDecimalPlaces()).thenReturn(0);
			part.upsertAttribute(value.key(), attrSchemaMock, existing -> value);
		}
		return part;
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
	@DisplayName("Should create a non-null entity proxy")
	void shouldCreateEntityProxy() {
		final EntitySchemaContract schema = mockSchema(ENTITY_TYPE);
		final EntityBodyStoragePart bodyPart = new EntityBodyStoragePart(ENTITY_PK);
		final EntityStoragePartAccessor accessor = mock(EntityStoragePartAccessor.class);
		when(accessor.getEntityStoragePart(ENTITY_TYPE, ENTITY_PK, EntityExistence.MUST_EXIST))
			.thenReturn(bodyPart);

		final ExpressionProxyDescriptor descriptor = buildDescriptor("$entity.primaryKey > 0");
		final InstantiationResult result = ExpressionProxyInstantiator.instantiate(
			descriptor, schema, ENTITY_PK, null, null, accessor,
			name -> { throw new IllegalStateException("No nested schema expected"); }
		);

		assertNotNull(result.entityProxy(), "Entity proxy should not be null");
		assertInstanceOf(EntityContract.class, result.entityProxy());
		assertNull(result.referenceProxy(), "Reference proxy should be null for PK-only expression");
	}

	@Test
	@DisplayName("Should return correct primary key from instantiated entity proxy")
	void shouldReturnCorrectPrimaryKeyFromEntityProxy() {
		final EntitySchemaContract schema = mockSchema(ENTITY_TYPE);
		final EntityBodyStoragePart bodyPart = new EntityBodyStoragePart(ENTITY_PK);
		final EntityStoragePartAccessor accessor = mock(EntityStoragePartAccessor.class);
		when(accessor.getEntityStoragePart(ENTITY_TYPE, ENTITY_PK, EntityExistence.MUST_EXIST))
			.thenReturn(bodyPart);

		final ExpressionProxyDescriptor descriptor = buildDescriptor("$entity.primaryKey > 0");
		final InstantiationResult result = ExpressionProxyInstantiator.instantiate(
			descriptor, schema, ENTITY_PK, null, null, accessor,
			name -> { throw new IllegalStateException("No nested schema expected"); }
		);

		assertEquals(ENTITY_PK, result.entityProxy().getPrimaryKey(), "Proxy should return PK 42");
	}

	@Test
	@DisplayName("Should return correct attribute value from instantiated entity proxy")
	void shouldReturnCorrectAttributeFromEntityProxy() {
		final EntitySchemaContract schema = mockSchema(ENTITY_TYPE);
		final AttributesStoragePart globalAttrs = createAttributesPart(
			null,
			new AttributeValue(new AttributeKey("code"), "ABC")
		);
		final EntityStoragePartAccessor accessor = mock(EntityStoragePartAccessor.class);
		when(accessor.getAttributeStoragePart(ENTITY_TYPE, ENTITY_PK)).thenReturn(globalAttrs);

		final ExpressionProxyDescriptor descriptor = buildDescriptor(
			"$entity.attributes['code'] == 'ABC'"
		);
		final InstantiationResult result = ExpressionProxyInstantiator.instantiate(
			descriptor, schema, ENTITY_PK, null, null, accessor,
			name -> { throw new IllegalStateException("No nested schema expected"); }
		);

		assertEquals("ABC", result.entityProxy().getAttribute("code"), "Proxy should return attribute 'ABC'");
	}

	@Test
	@DisplayName("Should fetch only recipe-specified storage parts")
	void shouldFetchOnlyRecipeSpecifiedParts() {
		final EntitySchemaContract schema = mockSchema(ENTITY_TYPE);
		final EntityBodyStoragePart bodyPart = new EntityBodyStoragePart(ENTITY_PK);
		final EntityStoragePartAccessor accessor = mock(EntityStoragePartAccessor.class);
		when(accessor.getEntityStoragePart(ENTITY_TYPE, ENTITY_PK, EntityExistence.MUST_EXIST))
			.thenReturn(bodyPart);

		// PK-only expression — should only fetch entity body, NOT attributes or references
		final ExpressionProxyDescriptor descriptor = buildDescriptor("$entity.primaryKey > 0");
		ExpressionProxyInstantiator.instantiate(
			descriptor, schema, ENTITY_PK, null, null, accessor,
			name -> { throw new IllegalStateException("No nested schema expected"); }
		);

		// verify body was fetched
		verify(accessor).getEntityStoragePart(ENTITY_TYPE, ENTITY_PK, EntityExistence.MUST_EXIST);
		// verify attributes were NOT fetched
		verify(accessor, never()).getAttributeStoragePart(eq(ENTITY_TYPE), eq(ENTITY_PK));
		verify(accessor, never()).getAttributeStoragePart(eq(ENTITY_TYPE), eq(ENTITY_PK), any(Locale.class));
		// verify references were NOT fetched
		verify(accessor, never()).getReferencesStoragePart(eq(ENTITY_TYPE), eq(ENTITY_PK));
	}

	@Test
	@DisplayName("Should return null reference proxy when no reference partials in descriptor")
	void shouldReturnNullReferenceProxyWhenNoReferencePartials() {
		final EntitySchemaContract schema = mockSchema(ENTITY_TYPE);
		final EntityStoragePartAccessor accessor = mock(EntityStoragePartAccessor.class);

		// constant expression — no data needed, no references
		final ExpressionProxyDescriptor descriptor = buildDescriptor("1 + 2 > 3");
		final InstantiationResult result = ExpressionProxyInstantiator.instantiate(
			descriptor, schema, ENTITY_PK, null, null, accessor,
			name -> { throw new IllegalStateException("No nested schema expected"); }
		);

		assertNotNull(result.entityProxy());
		assertNull(result.referenceProxy(), "Reference proxy should be null for constant expression");
	}

	@Test
	@DisplayName("Should return correct schema type from instantiated entity proxy")
	void shouldReturnCorrectSchemaTypeFromEntityProxy() {
		final EntitySchemaContract schema = mockSchema(ENTITY_TYPE);
		final EntityStoragePartAccessor accessor = mock(EntityStoragePartAccessor.class);

		final ExpressionProxyDescriptor descriptor = buildDescriptor("$entity.type == 'Product'");
		final InstantiationResult result = ExpressionProxyInstantiator.instantiate(
			descriptor, schema, ENTITY_PK, null, null, accessor,
			name -> { throw new IllegalStateException("No nested schema expected"); }
		);

		assertEquals(ENTITY_TYPE, result.entityProxy().getType(), "Proxy type should match schema name");
	}

	@Test
	@DisplayName("Should instantiate reference proxy from descriptor")
	void shouldInstantiateReferenceProxyFromDescriptor() {
		final EntitySchemaContract schema = mockSchema(ENTITY_TYPE);
		final ReferenceSchemaContract refSchema = mockReferenceSchema(REFERENCE_NAME, REFERENCED_ENTITY_TYPE);

		// create a reference with attribute "order" = 5
		final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, REFERENCED_ENTITY_PK, 1);
		final Reference ref = new Reference(
			schema, refSchema, 1, refKey, null,
			Map.of(new AttributeKey("order"), new AttributeValue(new AttributeKey("order"), 5)),
			false
		);
		final ReferencesStoragePart refsPart = new ReferencesStoragePart(
			ENTITY_PK, 1, new Reference[]{ ref }, -1
		);

		final EntityStoragePartAccessor accessor = mock(EntityStoragePartAccessor.class);
		when(accessor.getReferencesStoragePart(ENTITY_TYPE, ENTITY_PK)).thenReturn(refsPart);

		// expression accessing both reference attribute and entity primaryKey — needs body too
		final ExpressionProxyDescriptor descriptor = buildDescriptor(
			"$entity.references['brand'].attributes['order'] > 0 && $entity.primaryKey > 0"
		);

		final EntityBodyStoragePart bodyPart = new EntityBodyStoragePart(ENTITY_PK);
		when(accessor.getEntityStoragePart(ENTITY_TYPE, ENTITY_PK, EntityExistence.MUST_EXIST))
			.thenReturn(bodyPart);

		final InstantiationResult result = ExpressionProxyInstantiator.instantiate(
			descriptor, schema, ENTITY_PK, refSchema, refKey, accessor,
			name -> { throw new IllegalStateException("No nested schema expected"); }
		);

		assertNotNull(result.referenceProxy(), "Reference proxy should not be null");
		assertInstanceOf(ReferenceContract.class, result.referenceProxy());
		assertEquals(Integer.valueOf(5), result.referenceProxy().getAttribute("order"),
			"Reference proxy should return attribute value 5");
	}

	@Test
	@DisplayName("Should fetch locale-specific attribute parts per recipe")
	void shouldFetchLocaleSpecificAttributeParts() {
		final EntitySchemaContract schema = mockSchema(ENTITY_TYPE);

		// manually construct a descriptor whose recipe requires locale-specific attribute parts
		final StoragePartRecipe recipe = new StoragePartRecipe(
			false, true, Set.of(Locale.ENGLISH, Locale.GERMAN),
			false, Set.of(), Set.of()
		);
		final ExpressionProxyDescriptor baseDescriptor = buildDescriptor(
			"$entity.attributes['name'] == 'test'"
		);
		// create a descriptor with the locale-enriched recipe but the same partials
		final ExpressionProxyDescriptor descriptor = new ExpressionProxyDescriptor(
			baseDescriptor.entityPartials(),
			baseDescriptor.referencePartials(),
			recipe,
			false, false, null, null, null, null
		);

		final AttributesStoragePart globalAttrs = createAttributesPart(
			null, new AttributeValue(new AttributeKey("name"), "Global")
		);
		final AttributesStoragePart enAttrs = createAttributesPart(
			Locale.ENGLISH, new AttributeValue(new AttributeKey("name", Locale.ENGLISH), "English")
		);
		final AttributesStoragePart deAttrs = createAttributesPart(
			Locale.GERMAN, new AttributeValue(new AttributeKey("name", Locale.GERMAN), "Deutsch")
		);

		final EntityStoragePartAccessor accessor = mock(EntityStoragePartAccessor.class);
		when(accessor.getAttributeStoragePart(ENTITY_TYPE, ENTITY_PK)).thenReturn(globalAttrs);
		when(accessor.getAttributeStoragePart(ENTITY_TYPE, ENTITY_PK, Locale.ENGLISH)).thenReturn(enAttrs);
		when(accessor.getAttributeStoragePart(ENTITY_TYPE, ENTITY_PK, Locale.GERMAN)).thenReturn(deAttrs);

		ExpressionProxyInstantiator.instantiate(
			descriptor, schema, ENTITY_PK, null, null, accessor,
			name -> { throw new IllegalStateException("No nested schema expected"); }
		);

		// verify each locale-specific attribute part was fetched
		verify(accessor).getAttributeStoragePart(ENTITY_TYPE, ENTITY_PK, Locale.ENGLISH);
		verify(accessor).getAttributeStoragePart(ENTITY_TYPE, ENTITY_PK, Locale.GERMAN);
		verify(accessor).getAttributeStoragePart(ENTITY_TYPE, ENTITY_PK);
	}

	@Test
	@DisplayName("Should return correct reference key from reference proxy")
	void shouldReturnCorrectReferenceKeyFromReferenceProxy() {
		final EntitySchemaContract schema = mockSchema(ENTITY_TYPE);
		final ReferenceSchemaContract refSchema = mockReferenceSchema(REFERENCE_NAME, REFERENCED_ENTITY_TYPE);

		final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, REFERENCED_ENTITY_PK, 1);
		final Reference ref = new Reference(schema, refSchema, refKey, null);
		final ReferencesStoragePart refsPart = new ReferencesStoragePart(
			ENTITY_PK, 1, new Reference[]{ ref }, -1
		);

		final EntityStoragePartAccessor accessor = mock(EntityStoragePartAccessor.class);
		when(accessor.getReferencesStoragePart(ENTITY_TYPE, ENTITY_PK)).thenReturn(refsPart);

		final ExpressionProxyDescriptor descriptor = buildDescriptor(
			"$entity.references['brand'].attributes['order'] > 0"
		);

		final InstantiationResult result = ExpressionProxyInstantiator.instantiate(
			descriptor, schema, ENTITY_PK, refSchema, refKey, accessor,
			name -> { throw new IllegalStateException("No nested schema expected"); }
		);

		assertNotNull(result.referenceProxy(), "Reference proxy should not be null");
		assertEquals(refKey, result.referenceProxy().getReferenceKey(),
			"Reference proxy should return the expected ReferenceKey");
	}

	@Test
	@DisplayName("Should throw ExpressionEvaluationException for unhandled method on reference proxy")
	void shouldThrowForUnhandledMethodOnReferenceProxy() {
		final EntitySchemaContract schema = mockSchema(ENTITY_TYPE);
		final ReferenceSchemaContract refSchema = mockReferenceSchema(REFERENCE_NAME, REFERENCED_ENTITY_TYPE);

		final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, REFERENCED_ENTITY_PK, 1);
		final Reference ref = new Reference(schema, refSchema, refKey, null);
		final ReferencesStoragePart refsPart = new ReferencesStoragePart(
			ENTITY_PK, 1, new Reference[]{ ref }, -1
		);

		final EntityStoragePartAccessor accessor = mock(EntityStoragePartAccessor.class);
		when(accessor.getReferencesStoragePart(ENTITY_TYPE, ENTITY_PK)).thenReturn(refsPart);

		final ExpressionProxyDescriptor descriptor = buildDescriptor(
			"$entity.references['brand'].attributes['order'] > 0"
		);

		final InstantiationResult result = ExpressionProxyInstantiator.instantiate(
			descriptor, schema, ENTITY_PK, refSchema, refKey, accessor,
			name -> { throw new IllegalStateException("No nested schema expected"); }
		);

		final ReferenceContract referenceProxy = result.referenceProxy();
		assertNotNull(referenceProxy, "Reference proxy should not be null");

		// estimateSize() is not handled by any specific partial — must be caught by CatchAllPartial
		assertThrows(
			ExpressionEvaluationException.class,
			referenceProxy::estimateSize,
			"Unhandled method should throw ExpressionEvaluationException"
		);
	}

	@Test
	@DisplayName("Should cache proxy classes across identical partial sets")
	void shouldCacheProxyClassesAcrossIdenticalPartialSets() {
		final ExpressionProxyDescriptor descriptor = buildDescriptor("$entity.attributes['code'] == 'ABC'");
		final PredicateMethodClassification<?, ?, ?>[] partials = descriptor.entityPartials();

		final EntitySchemaContract schema = mockSchema(ENTITY_TYPE);
		final AttributesStoragePart attrs1 = createAttributesPart(
			null, new AttributeValue(new AttributeKey("code"), "ABC")
		);
		final AttributesStoragePart attrs2 = createAttributesPart(
			null, new AttributeValue(new AttributeKey("code"), "DEF")
		);

		final EntityProxyState state1 = new EntityProxyState(schema, null, attrs1, null, null, null);
		final EntityProxyState state2 = new EntityProxyState(schema, null, attrs2, null, null, null);

		final EntityContract proxy1 = ByteBuddyProxyGenerator.instantiate(
			new ByteBuddyDispatcherInvocationHandler<>(state1, partials),
			new Class<?>[]{ EntityContract.class },
			EMPTY_CLASS_ARRAY,
			EMPTY_OBJECT_ARRAY
		);
		final EntityContract proxy2 = ByteBuddyProxyGenerator.instantiate(
			new ByteBuddyDispatcherInvocationHandler<>(state2, partials),
			new Class<?>[]{ EntityContract.class },
			EMPTY_CLASS_ARRAY,
			EMPTY_OBJECT_ARRAY
		);

		assertSame(
			proxy1.getClass(), proxy2.getClass(),
			"Proxies created with the same partial set should share the same generated class"
		);
		assertEquals("ABC", proxy1.getAttribute("code"));
		assertEquals("DEF", proxy2.getAttribute("code"));
	}

	@Test
	@DisplayName("Should instantiate entity-only proxy with correct attributes and null reference proxy")
	void shouldInstantiateEntityOnlyProxyWithCorrectAttributes() {
		final EntitySchemaContract schema = mockSchema(ENTITY_TYPE);
		final AttributesStoragePart globalAttrs = createAttributesPart(
			null, new AttributeValue(new AttributeKey("code"), "ABC")
		);

		final EntityStoragePartAccessor accessor = mock(EntityStoragePartAccessor.class);
		when(accessor.getAttributeStoragePart(ENTITY_TYPE, ENTITY_PK)).thenReturn(globalAttrs);

		final ExpressionProxyDescriptor descriptor = buildDescriptor(
			"$entity.attributes['code'] == 'ABC'"
		);

		final InstantiationResult result = ExpressionProxyInstantiator.instantiate(
			descriptor, schema, ENTITY_PK, null, null, accessor,
			name -> { throw new IllegalStateException("No nested schema expected"); }
		);

		assertNotNull(result.entityProxy(), "Entity proxy must not be null");
		assertInstanceOf(EntityContract.class, result.entityProxy());
		assertEquals("ABC", result.entityProxy().getAttribute("code"),
			"Entity proxy should return the correct attribute value");
		assertNull(result.referenceProxy(),
			"Reference proxy must be null for entity-only expression");
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

		final GroupEntityReference groupRef = new GroupEntityReference(GROUP_ENTITY_TYPE, GROUP_ENTITY_PK);
		final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, REFERENCED_ENTITY_PK, 1);
		final Reference ref = new Reference(ownerSchema, refSchema, refKey, groupRef);
		final ReferencesStoragePart refsPart = new ReferencesStoragePart(
			ENTITY_PK, 1, new Reference[]{ ref }, -1
		);
		when(accessor.getReferencesStoragePart(ENTITY_TYPE, ENTITY_PK)).thenReturn(refsPart);

		final AttributesStoragePart groupAttrs = createAttributesPart(
			null, new AttributeValue(new AttributeKey("type"), "CHECKBOX")
		);
		when(accessor.getAttributeStoragePart(GROUP_ENTITY_TYPE, GROUP_ENTITY_PK)).thenReturn(groupAttrs);

		final ExpressionProxyDescriptor descriptor = buildDescriptor(
			"$entity.references['brand'].groupEntity.attributes['type'] == 'CHECKBOX'"
		);

		assertTrue(descriptor.needsGroupEntityProxy(),
			"Descriptor should flag that a group entity proxy is needed");

		final InstantiationResult result = ExpressionProxyInstantiator.instantiate(
			descriptor, ownerSchema, ENTITY_PK, refSchema, refKey, accessor,
			schemaResolver(Map.of(GROUP_ENTITY_TYPE, groupSchema))
		);

		assertNotNull(result.entityProxy(), "Entity proxy must not be null");

		final ReferenceContract referenceProxy = result.referenceProxy();
		assertNotNull(referenceProxy, "Reference proxy must not be null");

		final Optional<SealedEntity> groupEntity = referenceProxy.getGroupEntity();
		assertTrue(groupEntity.isPresent(), "Group entity should be wired into reference proxy");
		assertEquals("CHECKBOX", groupEntity.get().getAttribute("type"),
			"Group entity proxy should return correct attribute value");
	}

	@Test
	@DisplayName("Should return null reference proxy when reference key is null")
	void shouldReturnNullReferenceProxyWhenReferenceKeyNull() {
		final EntitySchemaContract schema = mockSchema(ENTITY_TYPE);
		final ReferenceSchemaContract refSchema = mockReferenceSchema(REFERENCE_NAME, REFERENCED_ENTITY_TYPE);
		final EntityStoragePartAccessor accessor = mock(EntityStoragePartAccessor.class);
		when(accessor.getReferencesStoragePart(ENTITY_TYPE, ENTITY_PK))
			.thenReturn(new ReferencesStoragePart(ENTITY_PK));

		final ExpressionProxyDescriptor descriptor = buildDescriptor(
			"$entity.references['brand'].attributes['order'] > 0"
		);

		// referenceKey is null — should skip reference proxy instantiation
		final InstantiationResult result = ExpressionProxyInstantiator.instantiate(
			descriptor, schema, ENTITY_PK, refSchema, null, accessor,
			name -> { throw new IllegalStateException("No nested schema expected"); }
		);

		assertNotNull(result.entityProxy(), "Entity proxy must not be null");
		assertNull(result.referenceProxy(), "Reference proxy must be null when referenceKey is null");
	}

	@Test
	@DisplayName("Should resolve Locale.ROOT sentinel to actual entity locales for attributes")
	void shouldResolveLocaleRootSentinelToActualEntityLocales() {
		final EntitySchemaContract schema = mockSchema(ENTITY_TYPE);

		// Build a descriptor for $entity.localizedAttributes['description'] — recipe will have
		// Locale.ROOT sentinel in neededAttributeLocales
		final ExpressionProxyDescriptor descriptor = buildDescriptor(
			"true || $entity.localizedAttributes['description']"
		);

		// Verify the recipe actually contains the Locale.ROOT sentinel (pre-condition)
		assertTrue(
			descriptor.entityRecipe().neededAttributeLocales().contains(Locale.ROOT),
			"Pre-condition: recipe should contain Locale.ROOT sentinel"
		);

		// Create a body part that reports the entity has ENGLISH and GERMAN locales
		final EntityBodyStoragePart bodyPart = new EntityBodyStoragePart(
			1, ENTITY_PK, io.evitadb.dataType.Scope.LIVE, null,
			Set.of(Locale.ENGLISH, Locale.GERMAN),
			Set.of(Locale.ENGLISH, Locale.GERMAN),
			Set.of(), -1
		);

		final AttributesStoragePart globalAttrs = createAttributesPart(null);
		final AttributesStoragePart enAttrs = createAttributesPart(Locale.ENGLISH);
		final AttributesStoragePart deAttrs = createAttributesPart(Locale.GERMAN);

		final EntityStoragePartAccessor accessor = mock(EntityStoragePartAccessor.class);
		when(accessor.getEntityStoragePart(ENTITY_TYPE, ENTITY_PK, EntityExistence.MUST_EXIST))
			.thenReturn(bodyPart);
		when(accessor.getAttributeStoragePart(ENTITY_TYPE, ENTITY_PK)).thenReturn(globalAttrs);
		when(accessor.getAttributeStoragePart(ENTITY_TYPE, ENTITY_PK, Locale.ENGLISH)).thenReturn(enAttrs);
		when(accessor.getAttributeStoragePart(ENTITY_TYPE, ENTITY_PK, Locale.GERMAN)).thenReturn(deAttrs);

		ExpressionProxyInstantiator.instantiate(
			descriptor, schema, ENTITY_PK, null, null, accessor,
			name -> { throw new IllegalStateException("No nested schema expected"); }
		);

		// Verify: should fetch attribute parts for ENGLISH and GERMAN (the body's actual locales),
		// NOT for Locale.ROOT
		verify(accessor).getAttributeStoragePart(ENTITY_TYPE, ENTITY_PK, Locale.ENGLISH);
		verify(accessor).getAttributeStoragePart(ENTITY_TYPE, ENTITY_PK, Locale.GERMAN);
		verify(accessor, never()).getAttributeStoragePart(ENTITY_TYPE, ENTITY_PK, Locale.ROOT);
	}

	@Test
	@DisplayName("Should resolve Locale.ROOT sentinel to actual entity locales for associated data")
	void shouldResolveLocaleRootSentinelToActualEntityLocalesForAssociatedData() {
		final EntitySchemaContract schema = mockSchema(ENTITY_TYPE);

		// Build a descriptor for $entity.localizedAssociatedData['label'] — recipe should have
		// Locale.ROOT sentinel in neededAssociatedDataLocales (Bug 3 fix)
		final ExpressionProxyDescriptor descriptor = buildDescriptor(
			"true || $entity.localizedAssociatedData['label']"
		);

		// Verify pre-conditions from Bug 3 mapper fix
		assertTrue(
			descriptor.entityRecipe().neededAssociatedDataLocales().contains(Locale.ROOT),
			"Pre-condition: recipe should contain Locale.ROOT sentinel in AD locales"
		);
		assertTrue(
			descriptor.entityRecipe().neededAssociatedDataNames().contains("label"),
			"Pre-condition: recipe should contain 'label' in AD names"
		);

		// Create a body part with ENGLISH and GERMAN locales
		final EntityBodyStoragePart bodyPart = new EntityBodyStoragePart(
			1, ENTITY_PK, io.evitadb.dataType.Scope.LIVE, null,
			Set.of(Locale.ENGLISH, Locale.GERMAN),
			Set.of(),
			Set.of(), -1
		);

		final EntityStoragePartAccessor accessor = mock(EntityStoragePartAccessor.class);
		when(accessor.getEntityStoragePart(ENTITY_TYPE, ENTITY_PK, EntityExistence.MUST_EXIST))
			.thenReturn(bodyPart);
		when(accessor.getAssociatedDataStoragePart(eq(ENTITY_TYPE), eq(ENTITY_PK), any(AssociatedDataKey.class)))
			.thenReturn(mock(io.evitadb.spi.store.catalog.persistence.storageParts.entity.AssociatedDataStoragePart.class));
		when(accessor.getAttributeStoragePart(ENTITY_TYPE, ENTITY_PK))
			.thenReturn(createAttributesPart(null));
		when(accessor.getAttributeStoragePart(eq(ENTITY_TYPE), eq(ENTITY_PK), any(Locale.class)))
			.thenReturn(createAttributesPart(null));

		ExpressionProxyInstantiator.instantiate(
			descriptor, schema, ENTITY_PK, null, null, accessor,
			name -> { throw new IllegalStateException("No nested schema expected"); }
		);

		// Verify: should fetch AD parts for both localized (ENGLISH, GERMAN) and global
		final AssociatedDataKey globalKey = new AssociatedDataKey("label");
		final AssociatedDataKey enKey = new AssociatedDataKey("label", Locale.ENGLISH);
		final AssociatedDataKey deKey = new AssociatedDataKey("label", Locale.GERMAN);
		verify(accessor).getAssociatedDataStoragePart(ENTITY_TYPE, ENTITY_PK, globalKey);
		verify(accessor).getAssociatedDataStoragePart(ENTITY_TYPE, ENTITY_PK, enKey);
		verify(accessor).getAssociatedDataStoragePart(ENTITY_TYPE, ENTITY_PK, deKey);

		// Should NOT fetch for Locale.ROOT
		final AssociatedDataKey rootKey = new AssociatedDataKey("label", Locale.ROOT);
		verify(accessor, never()).getAssociatedDataStoragePart(ENTITY_TYPE, ENTITY_PK, rootKey);
	}

	@Test
	@DisplayName("Should return null reference proxy when reference schema is null")
	void shouldReturnNullReferenceProxyWhenReferenceSchemaNull() {
		final EntitySchemaContract schema = mockSchema(ENTITY_TYPE);
		final EntityStoragePartAccessor accessor = mock(EntityStoragePartAccessor.class);
		when(accessor.getReferencesStoragePart(ENTITY_TYPE, ENTITY_PK))
			.thenReturn(new ReferencesStoragePart(ENTITY_PK));

		final ReferenceKey refKey = new ReferenceKey(REFERENCE_NAME, REFERENCED_ENTITY_PK, 1);
		final ExpressionProxyDescriptor descriptor = buildDescriptor(
			"$entity.references['brand'].attributes['order'] > 0"
		);

		// referenceSchema is null — should skip reference proxy instantiation
		final InstantiationResult result = ExpressionProxyInstantiator.instantiate(
			descriptor, schema, ENTITY_PK, null, refKey, accessor,
			name -> { throw new IllegalStateException("No nested schema expected"); }
		);

		assertNotNull(result.entityProxy(), "Entity proxy must not be null");
		assertNull(result.referenceProxy(), "Reference proxy must be null when referenceSchema is null");
	}
}
