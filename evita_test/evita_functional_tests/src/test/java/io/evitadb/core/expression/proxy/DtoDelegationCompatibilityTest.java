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

import io.evitadb.api.query.expression.object.accessor.entity.AssociatedDataContractAccessor;
import io.evitadb.api.query.expression.object.accessor.entity.AttributesContractAccessor;
import io.evitadb.api.query.expression.object.accessor.entity.EntityContractAccessor.EntityAssociatedDataEvaluationDto;
import io.evitadb.api.query.expression.object.accessor.entity.EntityContractAccessor.EntityAttributesEvaluationDto;
import io.evitadb.api.query.expression.object.accessor.entity.EntityContractAccessor.EntityReferencesEvaluationDto;
import io.evitadb.api.query.expression.object.accessor.entity.ReferenceContractAccessor.ReferenceAttributesEvaluationDto;
import io.evitadb.api.query.expression.object.accessor.entity.ReferencesContractAccessor;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.Reference;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.expression.proxy.entity.EntityAssociatedDataPartial;
import io.evitadb.core.expression.proxy.entity.EntityAttributePartial;
import io.evitadb.core.expression.proxy.entity.EntityReferencesPartial;
import io.evitadb.core.expression.proxy.entity.EntitySchemaPartial;
import io.evitadb.core.expression.proxy.reference.ReferenceAttributePartial;
import io.evitadb.core.expression.proxy.reference.ReferenceIdentityPartial;
import io.evitadb.core.expression.proxy.reference.ReferenceVersionAndDroppablePartial;
import io.evitadb.exception.ExpressionEvaluationException;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AssociatedDataStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AttributesStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;
import one.edee.oss.proxycian.PredicateMethodClassification;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyDispatcherInvocationHandler;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyProxyGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.evitadb.utils.ArrayUtils.EMPTY_CLASS_ARRAY;
import static io.evitadb.utils.ArrayUtils.EMPTY_OBJECT_ARRAY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for DTO delegation compatibility, verifying that ByteBuddy-generated expression proxies work correctly
 * when wrapped in the evaluation DTOs ({@link EntityAttributesEvaluationDto}, {@link EntityAssociatedDataEvaluationDto},
 * {@link EntityReferencesEvaluationDto}, {@link ReferenceAttributesEvaluationDto}) and accessed through the
 * corresponding accessor SPI implementations.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@SuppressWarnings({"rawtypes", "SameParameterValue"})
@DisplayName("DTO delegation compatibility")
class DtoDelegationCompatibilityTest {

	/** Unique marker interface for entity proxies to avoid Proxycian classification cache pollution. */
	private interface DtoDelegationTestEntity extends EntityContract {}

	/** Unique marker interface for reference proxies to avoid Proxycian classification cache pollution. */
	private interface DtoDelegationTestReference extends ReferenceContract {}

	private static final int ENTITY_PK = 1;

	/**
	 * Creates an {@link EntityContract} proxy with the given partials plus the {@link CatchAllPartial} safety net.
	 * Uses {@link DtoDelegationTestEntity} as a marker interface for Proxycian cache isolation.
	 *
	 * @param state    the proxy state record
	 * @param partials specific partials to include before the catch-all
	 * @return entity contract proxy
	 */
	@Nonnull
	private static EntityContract createEntityProxy(
		@Nonnull EntityProxyState state,
		@Nonnull PredicateMethodClassification<?, ?, ?>... partials
	) {
		final PredicateMethodClassification[] all = new PredicateMethodClassification[partials.length + 2];
		System.arraycopy(partials, 0, all, 0, partials.length);
		all[partials.length] = CatchAllPartial.OBJECT_METHODS;
		all[partials.length + 1] = CatchAllPartial.INSTANCE;

		return (EntityContract) ByteBuddyProxyGenerator.instantiate(
			new ByteBuddyDispatcherInvocationHandler<>(state, all),
			new Class<?>[]{ DtoDelegationTestEntity.class },
			EMPTY_CLASS_ARRAY,
			EMPTY_OBJECT_ARRAY
		);
	}

	/**
	 * Creates a {@link ReferenceContract} proxy with the given partials plus the {@link CatchAllPartial} safety net.
	 * Uses {@link DtoDelegationTestReference} as a marker interface for Proxycian cache isolation.
	 *
	 * @param state    the proxy state record
	 * @param partials specific partials to include before the catch-all
	 * @return reference contract proxy
	 */
	@Nonnull
	private static ReferenceContract createReferenceProxy(
		@Nonnull ReferenceProxyState state,
		@Nonnull PredicateMethodClassification<?, ?, ?>... partials
	) {
		final PredicateMethodClassification[] all = new PredicateMethodClassification[partials.length + 2];
		System.arraycopy(partials, 0, all, 0, partials.length);
		all[partials.length] = CatchAllPartial.OBJECT_METHODS;
		all[partials.length + 1] = CatchAllPartial.INSTANCE;

		return (ReferenceContract) ByteBuddyProxyGenerator.instantiate(
			new ByteBuddyDispatcherInvocationHandler<>(state, all),
			new Class<?>[]{ DtoDelegationTestReference.class },
			EMPTY_CLASS_ARRAY,
			EMPTY_OBJECT_ARRAY
		);
	}

	/**
	 * Creates an {@link EntitySchemaContract} mock with the given entity type name and no extra schema definitions.
	 *
	 * @param entityType the entity type name
	 * @return mocked entity schema
	 */
	@Nonnull
	private static EntitySchemaContract mockEntitySchema(@Nonnull String entityType) {
		final EntitySchemaContract schema = mock(EntitySchemaContract.class);
		when(schema.getName()).thenReturn(entityType);
		return schema;
	}

	/**
	 * Creates an {@link AttributesStoragePart} with the given locale and sorted attribute values. Each value
	 * is registered via `upsertAttribute` with a mock schema typed as {@link String}.
	 *
	 * @param locale the locale, or `null` for global attributes
	 * @param values sorted attribute values
	 * @return populated attributes storage part
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
			final AttributeSchemaContract attrSchemaMock = mock(AttributeSchemaContract.class);
			doReturn(String.class).when(attrSchemaMock).getType();
			when(attrSchemaMock.getIndexedDecimalPlaces()).thenReturn(0);
			part.upsertAttribute(value.key(), attrSchemaMock, existing -> value);
		}
		return part;
	}

	/**
	 * Creates a simple {@link Reference} with the given name and referenced entity PK using a mock schema.
	 *
	 * @param entitySchema      the entity schema (required by the Reference constructor)
	 * @param referenceName     the reference type name
	 * @param referencedEntityPk the PK of the referenced entity
	 * @param internalPk        the internal PK of the reference
	 * @return a new Reference instance
	 */
	@Nonnull
	private static Reference createReference(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull String referenceName,
		int referencedEntityPk,
		int internalPk
	) {
		final ReferenceSchemaContract refSchema = mock(ReferenceSchemaContract.class);
		when(refSchema.getName()).thenReturn(referenceName);
		when(refSchema.getAttributes()).thenReturn(Collections.emptyMap());
		final ReferenceKey refKey = new ReferenceKey(referenceName, referencedEntityPk, internalPk);
		return new Reference(entitySchema, refSchema, refKey, null);
	}

	@Nested
	@DisplayName("EntityAttributesEvaluationDto delegation")
	class EntityAttributesDtoTest {

		@Test
		@DisplayName("delegates getAttribute(String) to entity proxy")
		void shouldDelegateGetAttributeThroughEntityAttributesDto() {
			final EntitySchemaContract mockSchema = mockEntitySchema("Product");

			final AttributesStoragePart globalPart = createAttributesPart(
				null,
				new AttributeValue(new AttributeKey("code"), "ABC")
			);

			final EntityProxyState state = new EntityProxyState(
				mockSchema, null, globalPart, null, null, null
			);

			final EntityContract entityProxy = createEntityProxy(
				state,
				EntitySchemaPartial.GET_SCHEMA,
				EntitySchemaPartial.GET_TYPE,
				EntityAttributePartial.GET_ATTRIBUTE,
				EntityAttributePartial.GET_ATTRIBUTE_LOCALIZED,
				EntityAttributePartial.GET_ATTRIBUTE_SCHEMA,
				EntityAttributePartial.GET_ATTRIBUTE_LOCALES,
				EntityAttributePartial.ATTRIBUTES_AVAILABLE
			);

			final EntityAttributesEvaluationDto dto =
				new EntityAttributesEvaluationDto(entityProxy, false);

			assertEquals("ABC", dto.getAttribute("code"), "DTO should delegate getAttribute to proxy");
		}

		@Test
		@DisplayName("delegates getAttributeLocales() to entity proxy")
		void shouldDelegateGetAttributeLocalesToProxy() {
			final EntitySchemaContract mockSchema = mockEntitySchema("Product");

			final AttributesStoragePart enPart = createAttributesPart(Locale.ENGLISH);
			final AttributesStoragePart dePart = createAttributesPart(Locale.GERMAN);

			final Map<Locale, AttributesStoragePart> attrMap = new HashMap<>(4);
			attrMap.put(Locale.ENGLISH, enPart);
			attrMap.put(Locale.GERMAN, dePart);

			final EntityProxyState state = new EntityProxyState(
				mockSchema, null, null, attrMap, null, null
			);

			final EntityContract entityProxy = createEntityProxy(
				state,
				EntityAttributePartial.GET_ATTRIBUTE_LOCALES,
				EntityAttributePartial.ATTRIBUTES_AVAILABLE
			);

			final EntityAttributesEvaluationDto dto =
				new EntityAttributesEvaluationDto(entityProxy, false);

			final Set<Locale> locales = dto.getAttributeLocales();
			assertEquals(2, locales.size(), "Should contain exactly 2 locales");
			assertTrue(locales.contains(Locale.ENGLISH), "Should contain ENGLISH");
			assertTrue(locales.contains(Locale.GERMAN), "Should contain GERMAN");
		}

		@Test
		@DisplayName("delegates localized getAttribute(String, Locale) to entity proxy")
		void shouldDelegateLocalizedAttributeAccessThroughDto() {
			final EntitySchemaContract mockSchema = mockEntitySchema("Product");

			final AttributesStoragePart enPart = createAttributesPart(
				Locale.ENGLISH,
				new AttributeValue(new AttributeKey("name", Locale.ENGLISH), "Widget")
			);
			final AttributesStoragePart dePart = createAttributesPart(
				Locale.GERMAN,
				new AttributeValue(new AttributeKey("name", Locale.GERMAN), "Ding")
			);

			final Map<Locale, AttributesStoragePart> attrMap = new HashMap<>(4);
			attrMap.put(Locale.ENGLISH, enPart);
			attrMap.put(Locale.GERMAN, dePart);

			final EntityProxyState state = new EntityProxyState(
				mockSchema, null, null, attrMap, null, null
			);

			final EntityContract entityProxy = createEntityProxy(
				state,
				EntityAttributePartial.GET_ATTRIBUTE,
				EntityAttributePartial.GET_ATTRIBUTE_LOCALIZED,
				EntityAttributePartial.ATTRIBUTES_AVAILABLE
			);

			final EntityAttributesEvaluationDto dto =
				new EntityAttributesEvaluationDto(entityProxy, true);

			assertEquals("Widget", dto.getAttribute("name", Locale.ENGLISH),
				"DTO should return English localized attribute");
			assertEquals("Ding", dto.getAttribute("name", Locale.GERMAN),
				"DTO should return German localized attribute");
		}
	}

	@Nested
	@DisplayName("EntityAssociatedDataEvaluationDto delegation")
	class EntityAssociatedDataDtoTest {

		@Test
		@DisplayName("delegates getAssociatedData(String) to entity proxy")
		void shouldDelegateGetAssociatedDataThroughDto() {
			final EntitySchemaContract mockSchema = mockEntitySchema("Product");

			final AssociatedDataKey adKey = new AssociatedDataKey("description");
			final AssociatedDataValue adValue = new AssociatedDataValue(adKey, "A widget");
			final AssociatedDataStoragePart adPart =
				new AssociatedDataStoragePart(1L, ENTITY_PK, adValue, -1);

			final Map<AssociatedDataKey, AssociatedDataStoragePart> adMap = new HashMap<>(4);
			adMap.put(adKey, adPart);

			final EntityProxyState state = new EntityProxyState(
				mockSchema, null, null, null, adMap, null
			);

			final EntityContract entityProxy = createEntityProxy(
				state,
				EntitySchemaPartial.GET_SCHEMA,
				EntityAssociatedDataPartial.GET_ASSOCIATED_DATA,
				EntityAssociatedDataPartial.GET_ASSOCIATED_DATA_LOCALIZED,
				EntityAssociatedDataPartial.GET_ASSOCIATED_DATA_SCHEMA,
				EntityAssociatedDataPartial.GET_ASSOCIATED_DATA_LOCALES,
				EntityAssociatedDataPartial.ASSOCIATED_DATA_AVAILABLE
			);

			final EntityAssociatedDataEvaluationDto dto =
				new EntityAssociatedDataEvaluationDto(entityProxy, false);

			assertEquals("A widget", dto.getAssociatedData("description"),
				"DTO should delegate getAssociatedData to proxy");
		}

		@Test
		@DisplayName("delegates getAssociatedDataLocales() to entity proxy")
		void shouldDelegateGetAssociatedDataLocalesToProxy() {
			final EntitySchemaContract mockSchema = mockEntitySchema("Product");

			final AssociatedDataKey enKey = new AssociatedDataKey("description", Locale.ENGLISH);
			final AssociatedDataValue enValue = new AssociatedDataValue(enKey, "EN desc");
			final AssociatedDataStoragePart enPart =
				new AssociatedDataStoragePart(1L, ENTITY_PK, enValue, -1);

			final AssociatedDataKey deKey = new AssociatedDataKey("description", Locale.GERMAN);
			final AssociatedDataValue deValue = new AssociatedDataValue(deKey, "DE desc");
			final AssociatedDataStoragePart dePart =
				new AssociatedDataStoragePart(2L, ENTITY_PK, deValue, -1);

			final Map<AssociatedDataKey, AssociatedDataStoragePart> adMap = new HashMap<>(4);
			adMap.put(enKey, enPart);
			adMap.put(deKey, dePart);

			final EntityProxyState state = new EntityProxyState(
				mockSchema, null, null, null, adMap, null
			);

			final EntityContract entityProxy = createEntityProxy(
				state,
				EntityAssociatedDataPartial.GET_ASSOCIATED_DATA_LOCALES,
				EntityAssociatedDataPartial.ASSOCIATED_DATA_AVAILABLE
			);

			final EntityAssociatedDataEvaluationDto dto =
				new EntityAssociatedDataEvaluationDto(entityProxy, false);

			final Set<Locale> locales = dto.getAssociatedDataLocales();
			assertEquals(2, locales.size(), "Should contain exactly 2 locales");
			assertTrue(locales.contains(Locale.ENGLISH), "Should contain ENGLISH");
			assertTrue(locales.contains(Locale.GERMAN), "Should contain GERMAN");
		}
	}

	@Nested
	@DisplayName("EntityReferencesEvaluationDto delegation")
	class EntityReferencesDtoTest {

		@Test
		@DisplayName("delegates getSchema() to entity proxy")
		void shouldDelegateGetSchemaThroughReferencesDto() {
			final EntitySchemaContract mockSchema = mockEntitySchema("Product");

			final EntityProxyState state = new EntityProxyState(
				mockSchema, null, null, null, null, null
			);

			final EntityContract entityProxy = createEntityProxy(
				state,
				EntitySchemaPartial.GET_SCHEMA,
				EntitySchemaPartial.GET_TYPE,
				EntityReferencesPartial.REFERENCES_AVAILABLE
			);

			final EntityReferencesEvaluationDto dto =
				new EntityReferencesEvaluationDto(entityProxy);

			assertSame(mockSchema, dto.getSchema(),
				"DTO should delegate getSchema to entity proxy");
		}

		@Test
		@DisplayName("delegates getReferences(String) to entity proxy")
		void shouldDelegateGetReferencesToProxy() {
			final EntitySchemaContract mockSchema = mockEntitySchema("Product");

			final Reference brandRef1 = createReference(mockSchema, "brand", 10, 1);
			final Reference brandRef2 = createReference(mockSchema, "brand", 20, 2);
			final Reference[] sortedRefs = new Reference[]{ brandRef1, brandRef2 };
			final ReferencesStoragePart refPart =
				new ReferencesStoragePart(ENTITY_PK, 2, sortedRefs, -1);
			final Map<String, List<ReferenceContract>> refsByName =
				EntityProxyState.indexReferences(refPart);

			final EntityProxyState state = new EntityProxyState(
				mockSchema, null, null, null, null, refsByName
			);

			final EntityContract entityProxy = createEntityProxy(
				state,
				EntitySchemaPartial.GET_SCHEMA,
				EntityReferencesPartial.GET_REFERENCES_BY_NAME,
				EntityReferencesPartial.GET_ALL_REFERENCES,
				EntityReferencesPartial.REFERENCES_AVAILABLE
			);

			final EntityReferencesEvaluationDto dto =
				new EntityReferencesEvaluationDto(entityProxy);

			final Collection<ReferenceContract> brandRefs = dto.getReferences("brand");
			assertEquals(2, brandRefs.size(),
				"DTO should delegate getReferences and return 2 brand references");
		}
	}

	@Nested
	@DisplayName("ReferenceAttributesEvaluationDto delegation")
	class ReferenceAttributesDtoTest {

		@Test
		@DisplayName("delegates getAttribute(String) to reference proxy")
		void shouldDelegateGetAttributeThroughReferenceAttributesDto() {
			final ReferenceSchemaContract mockRefSchema = mock(ReferenceSchemaContract.class);
			when(mockRefSchema.getReferencedEntityType()).thenReturn("Brand");
			when(mockRefSchema.getCardinality()).thenReturn(Cardinality.ZERO_OR_MORE);

			final ReferenceKey refKey = new ReferenceKey("brand", 42);

			final AttributeValue[] attrs = new AttributeValue[]{
				new AttributeValue(new AttributeKey("order"), 5)
			};
			Arrays.sort(attrs);

			final ReferenceProxyState state = new ReferenceProxyState(
				mockRefSchema, refKey, 1, attrs, Set.of(), null, null, null
			);

			final ReferenceContract referenceProxy = createReferenceProxy(
				state,
				ReferenceIdentityPartial.GET_REFERENCE_KEY,
				ReferenceAttributePartial.GET_ATTRIBUTE,
				ReferenceAttributePartial.GET_ATTRIBUTE_LOCALIZED,
				ReferenceAttributePartial.GET_ATTRIBUTE_SCHEMA,
				ReferenceAttributePartial.GET_ATTRIBUTE_LOCALES,
				ReferenceAttributePartial.ATTRIBUTES_AVAILABLE,
				ReferenceVersionAndDroppablePartial.VERSION,
				ReferenceVersionAndDroppablePartial.DROPPED
			);

			final ReferenceAttributesEvaluationDto dto =
				new ReferenceAttributesEvaluationDto(referenceProxy, false);

			assertEquals(
				(Integer) 5, dto.getAttribute("order"),
				"DTO should delegate getAttribute to reference proxy");
		}

		@Test
		@DisplayName("delegates getAttributeSchema(String) to reference proxy")
		void shouldDelegateGetAttributeSchemaThroughReferenceDto() {
			final ReferenceSchemaContract mockRefSchema = mock(ReferenceSchemaContract.class);
			when(mockRefSchema.getReferencedEntityType()).thenReturn("Brand");
			when(mockRefSchema.getCardinality()).thenReturn(Cardinality.ZERO_OR_MORE);
			final AttributeSchemaContract attrSchema = mock(AttributeSchemaContract.class);
			when(mockRefSchema.getAttribute("order")).thenReturn(Optional.of(attrSchema));

			final ReferenceKey refKey = new ReferenceKey("brand", 42);

			final ReferenceProxyState state = new ReferenceProxyState(
				mockRefSchema, refKey, 1, null, Set.of(), null, null, null
			);

			final ReferenceContract referenceProxy = createReferenceProxy(
				state,
				ReferenceIdentityPartial.GET_REFERENCE_KEY,
				ReferenceAttributePartial.GET_ATTRIBUTE_SCHEMA,
				ReferenceAttributePartial.ATTRIBUTES_AVAILABLE,
				ReferenceVersionAndDroppablePartial.VERSION,
				ReferenceVersionAndDroppablePartial.DROPPED
			);

			final ReferenceAttributesEvaluationDto dto =
				new ReferenceAttributesEvaluationDto(referenceProxy, false);

			final Optional<?> result = dto.getAttributeSchema("order");
			assertTrue(result.isPresent(), "getAttributeSchema('order') should be present");
			assertSame(attrSchema, result.get(),
				"DTO should return the attribute schema from the reference proxy");
		}
	}

	@Nested
	@DisplayName("Full accessor chain integration")
	class AccessorChainTest {

		@Test
		@DisplayName("AttributesContractAccessor.get() works through EntityAttributesEvaluationDto")
		void shouldWorkWithAttributesContractAccessorViaEntityDto() {
			final EntitySchemaContract mockSchema = mockEntitySchema("Product");

			// Mock the attribute schema for "code" - needed by AttributesContractAccessor.get()
			final EntityAttributeSchemaContract codeAttrSchema =
				mock(EntityAttributeSchemaContract.class);
			when(codeAttrSchema.isLocalized()).thenReturn(false);
			when(mockSchema.getAttribute("code")).thenReturn(Optional.of(codeAttrSchema));

			final AttributesStoragePart globalPart = createAttributesPart(
				null,
				new AttributeValue(new AttributeKey("code"), "ABC")
			);

			final EntityProxyState state = new EntityProxyState(
				mockSchema, null, globalPart, null, null, null
			);

			final EntityContract entityProxy = createEntityProxy(
				state,
				EntitySchemaPartial.GET_SCHEMA,
				EntitySchemaPartial.GET_TYPE,
				EntityAttributePartial.GET_ATTRIBUTE,
				EntityAttributePartial.GET_ATTRIBUTE_LOCALIZED,
				EntityAttributePartial.GET_ATTRIBUTE_SCHEMA,
				EntityAttributePartial.GET_ATTRIBUTE_LOCALES,
				EntityAttributePartial.ATTRIBUTES_AVAILABLE
			);

			final EntityAttributesEvaluationDto dto =
				new EntityAttributesEvaluationDto(entityProxy, false);

			final Serializable result = new AttributesContractAccessor().get(dto, "code");
			assertEquals("ABC", result,
				"AttributesContractAccessor should return 'ABC' via DTO delegation");
		}

		@Test
		@DisplayName("AttributesContractAccessor.get() works through ReferenceAttributesEvaluationDto")
		void shouldWorkWithAttributesContractAccessorViaReferenceDto() {
			final ReferenceSchemaContract mockRefSchema = mock(ReferenceSchemaContract.class);
			when(mockRefSchema.getReferencedEntityType()).thenReturn("Brand");
			when(mockRefSchema.getCardinality()).thenReturn(Cardinality.ZERO_OR_MORE);

			// Mock the attribute schema for "order" - needed by AttributesContractAccessor.get()
			final AttributeSchemaContract orderAttrSchema = mock(AttributeSchemaContract.class);
			when(orderAttrSchema.isLocalized()).thenReturn(false);
			when(mockRefSchema.getAttribute("order")).thenReturn(Optional.of(orderAttrSchema));

			final ReferenceKey refKey = new ReferenceKey("brand", 42);

			final AttributeValue[] attrs = new AttributeValue[]{
				new AttributeValue(new AttributeKey("order"), 5)
			};
			Arrays.sort(attrs);

			final ReferenceProxyState state = new ReferenceProxyState(
				mockRefSchema, refKey, 1, attrs, Set.of(), null, null, null
			);

			final ReferenceContract referenceProxy = createReferenceProxy(
				state,
				ReferenceIdentityPartial.GET_REFERENCE_KEY,
				ReferenceAttributePartial.GET_ATTRIBUTE,
				ReferenceAttributePartial.GET_ATTRIBUTE_LOCALIZED,
				ReferenceAttributePartial.GET_ATTRIBUTE_SCHEMA,
				ReferenceAttributePartial.GET_ATTRIBUTE_LOCALES,
				ReferenceAttributePartial.ATTRIBUTES_AVAILABLE,
				ReferenceVersionAndDroppablePartial.VERSION,
				ReferenceVersionAndDroppablePartial.DROPPED
			);

			final ReferenceAttributesEvaluationDto dto =
				new ReferenceAttributesEvaluationDto(referenceProxy, false);

			final Serializable result = new AttributesContractAccessor().get(dto, "order");
			assertEquals(
				5, result,
				"AttributesContractAccessor should return 5 via reference DTO delegation");
		}

		@Test
		@DisplayName("ReferencesContractAccessor.get() works through EntityReferencesEvaluationDto")
		void shouldWorkWithReferencesContractAccessorViaDto() {
			final EntitySchemaContract mockSchema = mockEntitySchema("Product");

			// Mock the reference schema for "brand" - needed by ReferencesContractAccessor.get()
			final ReferenceSchemaContract brandRefSchema = mock(ReferenceSchemaContract.class);
			when(brandRefSchema.getCardinality()).thenReturn(Cardinality.ZERO_OR_MORE);
			when(mockSchema.getReference("brand")).thenReturn(Optional.of(brandRefSchema));

			final Reference brandRef1 = createReference(mockSchema, "brand", 10, 1);
			final Reference brandRef2 = createReference(mockSchema, "brand", 20, 2);
			final Reference[] sortedRefs = new Reference[]{ brandRef1, brandRef2 };
			final ReferencesStoragePart refPart =
				new ReferencesStoragePart(ENTITY_PK, 2, sortedRefs, -1);
			final Map<String, List<ReferenceContract>> refsByName =
				EntityProxyState.indexReferences(refPart);

			final EntityProxyState state = new EntityProxyState(
				mockSchema, null, null, null, null, refsByName
			);

			final EntityContract entityProxy = createEntityProxy(
				state,
				EntitySchemaPartial.GET_SCHEMA,
				EntitySchemaPartial.GET_TYPE,
				EntityReferencesPartial.GET_REFERENCES_BY_NAME,
				EntityReferencesPartial.GET_REFERENCE,
				EntityReferencesPartial.GET_ALL_REFERENCES,
				EntityReferencesPartial.REFERENCES_AVAILABLE
			);

			final EntityReferencesEvaluationDto dto =
				new EntityReferencesEvaluationDto(entityProxy);

			final Serializable result = new ReferencesContractAccessor().get(dto, "brand");

			// Cardinality.ZERO_OR_MORE returns a Collection
			assertInstanceOf(Collection.class, result,
				"ZERO_OR_MORE cardinality should return a Collection");
			@SuppressWarnings("unchecked")
			final Collection<ReferenceContract> refs = (Collection<ReferenceContract>) result;
			assertEquals(2, refs.size(),
				"ReferencesContractAccessor should return 2 brand references via DTO");
		}

		@Test
		@DisplayName("AssociatedDataContractAccessor.get() works through EntityAssociatedDataEvaluationDto")
		void shouldWorkWithAssociatedDataContractAccessorViaDto() {
			final EntitySchemaContract mockSchema = mockEntitySchema("Product");

			// Mock the associated data schema - needed by AssociatedDataContractAccessor.get()
			final AssociatedDataSchemaContract descAdSchema =
				mock(AssociatedDataSchemaContract.class);
			when(descAdSchema.isLocalized()).thenReturn(false);
			when(mockSchema.getAssociatedData("description"))
				.thenReturn(Optional.of(descAdSchema));

			final AssociatedDataKey adKey = new AssociatedDataKey("description");
			final AssociatedDataValue adValue = new AssociatedDataValue(adKey, "A widget");
			final AssociatedDataStoragePart adPart =
				new AssociatedDataStoragePart(1L, ENTITY_PK, adValue, -1);

			final Map<AssociatedDataKey, AssociatedDataStoragePart> adMap = new HashMap<>(4);
			adMap.put(adKey, adPart);

			final EntityProxyState state = new EntityProxyState(
				mockSchema, null, null, null, adMap, null
			);

			final EntityContract entityProxy = createEntityProxy(
				state,
				EntitySchemaPartial.GET_SCHEMA,
				EntitySchemaPartial.GET_TYPE,
				EntityAssociatedDataPartial.GET_ASSOCIATED_DATA,
				EntityAssociatedDataPartial.GET_ASSOCIATED_DATA_LOCALIZED,
				EntityAssociatedDataPartial.GET_ASSOCIATED_DATA_SCHEMA,
				EntityAssociatedDataPartial.GET_ASSOCIATED_DATA_LOCALES,
				EntityAssociatedDataPartial.ASSOCIATED_DATA_AVAILABLE
			);

			final EntityAssociatedDataEvaluationDto dto =
				new EntityAssociatedDataEvaluationDto(entityProxy, false);

			final Serializable result =
				new AssociatedDataContractAccessor().get(dto, "description");
			assertEquals("A widget", result,
				"AssociatedDataContractAccessor should return 'A widget' via DTO delegation");
		}
	}

	@Nested
	@DisplayName("Unhandled delegation safety")
	class UnhandledDelegationTest {

		/**
		 * Separate marker interface for this test to avoid Proxycian classification cache pollution.
		 * Other tests register `EntityReferencesPartial.GET_REFERENCES_BY_NAME` which would be cached
		 * for the shared `DtoDelegationTestEntity` class and prevent the CatchAllPartial from being
		 * invoked for `getReferences(String)`.
		 */
		private interface UnhandledTestEntity extends EntityContract {}

		@Test
		@DisplayName("throws ExpressionEvaluationException for unhandled DTO method")
		void shouldThrowExpressionEvaluationExceptionForUnhandledDtoMethod() {
			final EntitySchemaContract mockSchema = mockEntitySchema("Product");

			// Proxy has only attribute + schema partials - NO references partial
			final EntityProxyState state = new EntityProxyState(
				mockSchema, null, null, null, null, null
			);

			// Use a unique marker interface to ensure fresh Proxycian classification
			final PredicateMethodClassification[] partials = new PredicateMethodClassification[]{
				EntitySchemaPartial.GET_SCHEMA,
				EntitySchemaPartial.GET_TYPE,
				EntityAttributePartial.GET_ATTRIBUTE,
				EntityAttributePartial.ATTRIBUTES_AVAILABLE,
				CatchAllPartial.OBJECT_METHODS,
				CatchAllPartial.INSTANCE
			};

			final EntityContract entityProxy = (EntityContract) ByteBuddyProxyGenerator.instantiate(
				new ByteBuddyDispatcherInvocationHandler<>(state, partials),
				new Class<?>[]{ UnhandledTestEntity.class },
				EMPTY_CLASS_ARRAY,
				EMPTY_OBJECT_ARRAY
			);

			final EntityReferencesEvaluationDto dto =
				new EntityReferencesEvaluationDto(entityProxy);

			// getReferences(String) is not handled by any partial, CatchAllPartial should throw
			assertThrows(
				ExpressionEvaluationException.class,
				() -> dto.getReferences("brand"),
				"Should throw ExpressionEvaluationException for unhandled method"
			);
		}
	}
}
