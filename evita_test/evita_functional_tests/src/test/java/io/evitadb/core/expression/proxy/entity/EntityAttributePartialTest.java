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

package io.evitadb.core.expression.proxy.entity;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.expression.proxy.CatchAllPartial;
import io.evitadb.core.expression.proxy.EntityProxyState;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AttributesStoragePart;
import one.edee.oss.proxycian.PredicateMethodClassification;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyDispatcherInvocationHandler;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyProxyGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.HashMap;
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
 * Tests for {@link EntityAttributePartial} verifying that attribute method implementations correctly delegate to the
 * proxy state and storage parts.
 */
@SuppressWarnings("rawtypes")
@DisplayName("Entity attribute partial")
class EntityAttributePartialTest {

	/** Unique marker interface to avoid Proxycian classification cache pollution between test classes. */
	private interface AttributeTestEntity extends EntityContract {}

	private static final int ENTITY_PK = 1;

	/**
	 * Creates an EntityContract proxy with the given partials plus the CatchAllPartial safety net.
	 *
	 * @param state    the proxy state record
	 * @param partials specific partials to include
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

		return ByteBuddyProxyGenerator.instantiate(
			new ByteBuddyDispatcherInvocationHandler<>(state, all),
			new Class<?>[]{ AttributeTestEntity.class },
			EMPTY_CLASS_ARRAY,
			EMPTY_OBJECT_ARRAY
		);
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
		// Use upsertAttribute to populate the sorted array
		for (final AttributeValue value : values) {
			final io.evitadb.api.requestResponse.schema.AttributeSchemaContract attrSchemaMock =
				mock(io.evitadb.api.requestResponse.schema.AttributeSchemaContract.class);
			doReturn(String.class).when(attrSchemaMock).getType();
			when(attrSchemaMock.getIndexedDecimalPlaces()).thenReturn(0);
			part.upsertAttribute(value.key(), attrSchemaMock, existing -> value);
		}
		return part;
	}

	@Test
	@DisplayName("getAttribute(String) returns value by binary search")
	void shouldReturnAttributeValueByBinarySearch() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");

		final AttributesStoragePart globalPart = createAttributesPart(
			null,
			new AttributeValue(new AttributeKey("code"), "ABC"),
			new AttributeValue(new AttributeKey("name"), "Widget")
		);

		final EntityProxyState state = new EntityProxyState(
			mockSchema, null, globalPart, null, null, null
		);

		final EntityContract proxy = createEntityProxy(
			state,
			EntitySchemaPartial.GET_SCHEMA,
			EntitySchemaPartial.GET_TYPE,
			EntityAttributePartial.GET_ATTRIBUTE,
			EntityAttributePartial.GET_ATTRIBUTE_LOCALIZED,
			EntityAttributePartial.GET_ATTRIBUTE_SCHEMA,
			EntityAttributePartial.GET_ATTRIBUTE_LOCALES,
			EntityAttributePartial.ATTRIBUTES_AVAILABLE
		);

		assertEquals("ABC", proxy.getAttribute("code"), "getAttribute('code') should return 'ABC'");
		assertEquals("Widget", proxy.getAttribute("name"), "getAttribute('name') should return 'Widget'");
	}

	@Test
	@DisplayName("getAttribute(String) returns null for missing attribute")
	void shouldReturnNullForMissingAttribute() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");

		final AttributesStoragePart globalPart = createAttributesPart(
			null, new AttributeValue(new AttributeKey("code"), "ABC")
		);

		final EntityProxyState state = new EntityProxyState(
			mockSchema, null, globalPart, null, null, null
		);

		final EntityContract proxy = createEntityProxy(
			state, EntityAttributePartial.GET_ATTRIBUTE, EntityAttributePartial.ATTRIBUTES_AVAILABLE
		);

		assertNull(proxy.getAttribute("nonExistent"), "getAttribute('nonExistent') should return null");
	}

	@Test
	@DisplayName("getAttribute(String, Locale) returns localized value")
	void shouldReturnLocalizedAttributeValue() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");

		final AttributesStoragePart enPart = createAttributesPart(
			Locale.ENGLISH,
			new AttributeValue(new AttributeKey("name", Locale.ENGLISH), "Widget")
		);

		final Map<Locale, AttributesStoragePart> attrMap = new HashMap<>(4);
		attrMap.put(Locale.ENGLISH, enPart);

		final EntityProxyState state = new EntityProxyState(
			mockSchema, null, null, attrMap, null, null
		);

		final EntityContract proxy = createEntityProxy(
			state,
			EntityAttributePartial.GET_ATTRIBUTE,
			EntityAttributePartial.GET_ATTRIBUTE_LOCALIZED,
			EntityAttributePartial.ATTRIBUTES_AVAILABLE
		);

		assertEquals("Widget", proxy.getAttribute("name", Locale.ENGLISH), "getAttribute('name', EN) should return 'Widget'");
	}

	@Test
	@DisplayName("getAttribute(String, Locale) returns null for wrong locale")
	void shouldReturnNullForWrongLocale() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");

		final AttributesStoragePart enPart = createAttributesPart(
			Locale.ENGLISH,
			new AttributeValue(new AttributeKey("name", Locale.ENGLISH), "Widget")
		);

		final Map<Locale, AttributesStoragePart> attrMap = new HashMap<>(4);
		attrMap.put(Locale.ENGLISH, enPart);

		final EntityProxyState state = new EntityProxyState(
			mockSchema, null, null, attrMap, null, null
		);

		final EntityContract proxy = createEntityProxy(
			state,
			EntityAttributePartial.GET_ATTRIBUTE,
			EntityAttributePartial.GET_ATTRIBUTE_LOCALIZED,
			EntityAttributePartial.ATTRIBUTES_AVAILABLE
		);

		assertNull(
			proxy.getAttribute("name", Locale.GERMAN),
			"getAttribute('name', DE) should return null when only EN exists"
		);
	}

	@Test
	@DisplayName("getAttributeLocales() returns union of locales")
	void shouldReturnUnionOfAttributeLocales() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");

		final AttributesStoragePart globalPart = createAttributesPart(null);
		final AttributesStoragePart enPart = createAttributesPart(Locale.ENGLISH);
		final AttributesStoragePart dePart = createAttributesPart(Locale.GERMAN);

		final Map<Locale, AttributesStoragePart> attrMap = new HashMap<>(4);
		attrMap.put(Locale.ENGLISH, enPart);
		attrMap.put(Locale.GERMAN, dePart);

		final EntityProxyState state = new EntityProxyState(
			mockSchema, null, globalPart, attrMap, null, null
		);

		final EntityContract proxy = createEntityProxy(
			state, EntityAttributePartial.GET_ATTRIBUTE_LOCALES, EntityAttributePartial.ATTRIBUTES_AVAILABLE
		);

		final Set<Locale> locales = proxy.getAttributeLocales();
		assertEquals(2, locales.size(), "Should contain exactly 2 locales");
		assertTrue(locales.contains(Locale.ENGLISH), "Should contain ENGLISH");
		assertTrue(locales.contains(Locale.GERMAN), "Should contain GERMAN");
	}

	@Test
	@DisplayName("getAttributeSchema(String) delegates to entity schema")
	void shouldDelegateAttributeSchemaToEntitySchema() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");
		final EntityAttributeSchemaContract attrSchema = mock(EntityAttributeSchemaContract.class);
		when(mockSchema.getAttribute("code")).thenReturn(Optional.of(attrSchema));

		final EntityProxyState state = new EntityProxyState(mockSchema, null, null, null, null, null);

		final EntityContract proxy = createEntityProxy(
			state, EntityAttributePartial.GET_ATTRIBUTE_SCHEMA, EntityAttributePartial.ATTRIBUTES_AVAILABLE
		);

		final Optional<?> result = proxy.getAttributeSchema("code");
		assertTrue(result.isPresent(), "getAttributeSchema('code') should be present");
		assertSame(attrSchema, result.get(), "Should return the schema from entity schema");
	}

	@Test
	@DisplayName("attributesAvailable() returns true")
	void shouldReturnTrueForAttributesAvailable() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");

		final EntityProxyState state = new EntityProxyState(mockSchema, null, null, null, null, null);

		final EntityContract proxy = createEntityProxy(state, EntityAttributePartial.ATTRIBUTES_AVAILABLE);

		assertTrue(proxy.attributesAvailable(), "attributesAvailable() should return true");
	}

	@Test
	@DisplayName("Should handle various attribute value types")
	void shouldHandleVariousValueTypes() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");

		final AttributesStoragePart globalPart = createAttributesPartWithTypes(
			new AttributeEntry("code", "ABC", String.class),
			new AttributeEntry("quantity", 42, Integer.class),
			new AttributeEntry("price", new BigDecimal("19.99"), BigDecimal.class),
			new AttributeEntry("active", true, Boolean.class)
		);

		final EntityProxyState state = new EntityProxyState(
			mockSchema, null, globalPart, null, null, null
		);

		final EntityContract proxy = createEntityProxy(
			state,
			EntitySchemaPartial.GET_SCHEMA,
			EntitySchemaPartial.GET_TYPE,
			EntityAttributePartial.GET_ATTRIBUTE,
			EntityAttributePartial.ATTRIBUTES_AVAILABLE
		);

		final Object codeValue = proxy.getAttribute("code");
		assertInstanceOf(String.class, codeValue, "code should be a String");
		assertEquals("ABC", codeValue, "code should equal 'ABC'");

		final Object quantityValue = proxy.getAttribute("quantity");
		assertInstanceOf(Integer.class, quantityValue, "quantity should be an Integer");
		assertEquals(42, quantityValue, "quantity should equal 42");

		final Object priceValue = proxy.getAttribute("price");
		assertInstanceOf(BigDecimal.class, priceValue, "price should be a BigDecimal");
		assertEquals(new BigDecimal("19.99"), priceValue, "price should equal 19.99");

		final Object activeValue = proxy.getAttribute("active");
		assertInstanceOf(Boolean.class, activeValue, "active should be a Boolean");
		assertEquals(true, activeValue, "active should equal true");
	}

	@Test
	@DisplayName("Should return empty Optional for unknown attribute schema")
	void shouldReturnEmptyForUnknownAttributeSchema() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");
		when(mockSchema.getAttribute("unknown")).thenReturn(Optional.empty());

		final EntityProxyState state = new EntityProxyState(mockSchema, null, null, null, null, null);

		final EntityContract proxy = createEntityProxy(
			state, EntityAttributePartial.GET_ATTRIBUTE_SCHEMA, EntityAttributePartial.ATTRIBUTES_AVAILABLE
		);

		final Optional<?> result = proxy.getAttributeSchema("unknown");
		assertTrue(result.isEmpty(), "getAttributeSchema('unknown') should return empty Optional");
	}

	@Test
	@DisplayName("getAttribute(String) returns null when global attributes part is null")
	void shouldReturnNullWhenGlobalAttributesPartNull() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");

		// globalAttributesPart is null in the state
		final EntityProxyState state = new EntityProxyState(
			mockSchema, null, null, null, null, null
		);

		final EntityContract proxy = createEntityProxy(
			state, EntityAttributePartial.GET_ATTRIBUTE, EntityAttributePartial.ATTRIBUTES_AVAILABLE
		);

		assertNull(
			proxy.getAttribute("code"),
			"getAttribute() should return null when global attributes part is null"
		);
	}

	/**
	 * Creates an {@link AttributesStoragePart} with attributes of various types. Each entry specifies
	 * the attribute name, value, and the schema type to use for value conversion.
	 *
	 * @param entries attribute entries with name, value, and type
	 * @return populated attributes storage part
	 */
	@Nonnull
	private static AttributesStoragePart createAttributesPartWithTypes(@Nonnull AttributeEntry... entries) {
		final AttributesStoragePart part = new AttributesStoragePart(ENTITY_PK);
		for (final AttributeEntry entry : entries) {
			final io.evitadb.api.requestResponse.schema.AttributeSchemaContract attrSchemaMock =
				mock(io.evitadb.api.requestResponse.schema.AttributeSchemaContract.class);
			doReturn(entry.type()).when(attrSchemaMock).getType();
			when(attrSchemaMock.getIndexedDecimalPlaces()).thenReturn(0);
			final AttributeKey key = new AttributeKey(entry.name());
			final AttributeValue value = new AttributeValue(key, entry.value());
			part.upsertAttribute(key, attrSchemaMock, existing -> value);
		}
		return part;
	}

	@Test
	@DisplayName("getAttribute(String) should return null for dropped (tombstoned) attribute")
	void shouldReturnNullForDroppedGlobalAttribute() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");
		// version=1, key="code", value="ABC", dropped=true
		final AttributeValue droppedAttr = new AttributeValue(1, new AttributeKey("code"), "ABC", true);
		final AttributesStoragePart globalPart = createAttributesPart(null, droppedAttr);
		final EntityProxyState state = new EntityProxyState(mockSchema, null, globalPart, null, null, null);
		final EntityContract proxy = createEntityProxy(state, EntityAttributePartial.GET_ATTRIBUTE);

		assertNull(proxy.getAttribute("code"), "Dropped attribute should return null");
	}

	@Test
	@DisplayName("getAttribute(String, Locale) should return null for dropped localized attribute")
	void shouldReturnNullForDroppedLocalizedAttribute() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");
		final AttributeValue droppedAttr = new AttributeValue(
			1, new AttributeKey("name", Locale.ENGLISH), "English Name", true
		);
		final AttributesStoragePart localePart = createAttributesPart(Locale.ENGLISH, droppedAttr);
		final Map<Locale, AttributesStoragePart> localeParts = new HashMap<>();
		localeParts.put(Locale.ENGLISH, localePart);
		final EntityProxyState state = new EntityProxyState(mockSchema, null, null, localeParts, null, null);
		final EntityContract proxy = createEntityProxy(state, EntityAttributePartial.GET_ATTRIBUTE_LOCALIZED);

		assertNull(
			proxy.getAttribute("name", Locale.ENGLISH),
			"Dropped localized attribute should return null"
		);
	}

	/**
	 * Record holding an attribute entry with its name, value, and expected schema type for use in
	 * {@link #createAttributesPartWithTypes(AttributeEntry...)}.
	 *
	 * @param name  the attribute name
	 * @param value the attribute value
	 * @param type  the attribute schema type for value conversion
	 */
	private record AttributeEntry(
		@Nonnull String name,
		@Nonnull Serializable value,
		@Nonnull Class<?> type
	) {}
}
