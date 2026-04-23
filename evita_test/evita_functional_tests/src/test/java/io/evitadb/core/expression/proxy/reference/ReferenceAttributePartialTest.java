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

package io.evitadb.core.expression.proxy.reference;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.expression.proxy.CatchAllPartial;
import io.evitadb.core.expression.proxy.ReferenceProxyState;
import one.edee.oss.proxycian.PredicateMethodClassification;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyDispatcherInvocationHandler;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyProxyGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static io.evitadb.utils.ArrayUtils.EMPTY_CLASS_ARRAY;
import static io.evitadb.utils.ArrayUtils.EMPTY_OBJECT_ARRAY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ReferenceAttributePartial} verifying that attribute method implementations correctly delegate to the
 * proxy state using binary search on sorted attribute arrays.
 */
@SuppressWarnings("rawtypes")
@DisplayName("Reference attribute partial")
class ReferenceAttributePartialTest {

	/** Unique marker interface to avoid Proxycian classification cache pollution between test classes. */
	private interface AttributeTestReference extends ReferenceContract {}

	/**
	 * Creates a ReferenceContract proxy with the given partials plus the CatchAllPartial safety net.
	 *
	 * @param state    the proxy state record
	 * @param partials specific partials to include
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
			new Class<?>[]{ AttributeTestReference.class },
			EMPTY_CLASS_ARRAY,
			EMPTY_OBJECT_ARRAY
		);
	}

	@Test
	@DisplayName("getAttribute(String) returns value from reference attribute array")
	void shouldReturnAttributeValueFromReferenceAttributeArray() {
		final ReferenceSchemaContract mockRefSchema = mock(ReferenceSchemaContract.class);
		when(mockRefSchema.getReferencedEntityType()).thenReturn("Brand");
		when(mockRefSchema.getCardinality()).thenReturn(Cardinality.ZERO_OR_MORE);

		final ReferenceKey refKey = new ReferenceKey("brand", 42);

		// Create sorted attribute array
		final AttributeValue[] attrs = new AttributeValue[]{
			new AttributeValue(new AttributeKey("code"), "ABC"),
			new AttributeValue(new AttributeKey("order"), 5)
		};
		// Ensure sorted order
		Arrays.sort(attrs);

		final ReferenceProxyState state = new ReferenceProxyState(
			mockRefSchema, refKey, 1, attrs, Set.of(), null, null, null
		);

		final ReferenceContract proxy = createReferenceProxy(
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

		assertEquals("ABC", proxy.getAttribute("code"), "getAttribute('code') should return 'ABC'");
		assertEquals(Integer.valueOf(5), proxy.getAttribute("order"), "getAttribute('order') should return 5");
	}

	@Test
	@DisplayName("getAttribute(String) returns null for missing attribute")
	void shouldReturnNullForMissingReferenceAttribute() {
		final ReferenceSchemaContract mockRefSchema = mock(ReferenceSchemaContract.class);
		when(mockRefSchema.getReferencedEntityType()).thenReturn("Brand");
		when(mockRefSchema.getCardinality()).thenReturn(Cardinality.ZERO_OR_MORE);

		final ReferenceKey refKey = new ReferenceKey("brand", 42);

		final AttributeValue[] attrs = new AttributeValue[]{
			new AttributeValue(new AttributeKey("code"), "ABC")
		};

		final ReferenceProxyState state = new ReferenceProxyState(
			mockRefSchema, refKey, 1, attrs, Set.of(), null, null, null
		);

		final ReferenceContract proxy = createReferenceProxy(
			state,
			ReferenceIdentityPartial.GET_REFERENCE_KEY,
			ReferenceAttributePartial.GET_ATTRIBUTE,
			ReferenceAttributePartial.ATTRIBUTES_AVAILABLE,
			ReferenceVersionAndDroppablePartial.VERSION,
			ReferenceVersionAndDroppablePartial.DROPPED
		);

		assertNull(proxy.getAttribute("nonExistent"), "getAttribute('nonExistent') should return null");
	}

	@Test
	@DisplayName("getAttribute(String, Locale) returns localized value")
	void shouldReturnLocalizedReferenceAttributeValue() {
		final ReferenceSchemaContract mockRefSchema = mock(ReferenceSchemaContract.class);
		when(mockRefSchema.getReferencedEntityType()).thenReturn("Brand");
		when(mockRefSchema.getCardinality()).thenReturn(Cardinality.ZERO_OR_MORE);

		final ReferenceKey refKey = new ReferenceKey("brand", 42);

		final AttributeValue[] attrs = new AttributeValue[]{
			new AttributeValue(new AttributeKey("label", Locale.ENGLISH), "English Label"),
			new AttributeValue(new AttributeKey("name"), "GlobalName")
		};
		// Ensure sorted order
		Arrays.sort(attrs);

		final ReferenceProxyState state = new ReferenceProxyState(
			mockRefSchema, refKey, 1, attrs, Set.of(Locale.ENGLISH), null, null, null
		);

		final ReferenceContract proxy = createReferenceProxy(
			state,
			ReferenceIdentityPartial.GET_REFERENCE_KEY,
			ReferenceAttributePartial.GET_ATTRIBUTE,
			ReferenceAttributePartial.GET_ATTRIBUTE_LOCALIZED,
			ReferenceAttributePartial.ATTRIBUTES_AVAILABLE,
			ReferenceVersionAndDroppablePartial.VERSION,
			ReferenceVersionAndDroppablePartial.DROPPED
		);

		assertEquals(
			"English Label", proxy.getAttribute("label", Locale.ENGLISH),
			"getAttribute('label', EN) should return 'English Label'"
		);
	}

	@Test
	@DisplayName("getAttributeSchema(String) delegates to reference schema")
	void shouldDelegateAttributeSchemaToReferenceSchema() {
		final ReferenceSchemaContract mockRefSchema = mock(ReferenceSchemaContract.class);
		when(mockRefSchema.getReferencedEntityType()).thenReturn("Brand");
		when(mockRefSchema.getCardinality()).thenReturn(Cardinality.ZERO_OR_MORE);
		final AttributeSchemaContract attrSchema = mock(AttributeSchemaContract.class);
		when(mockRefSchema.getAttribute("order")).thenReturn(Optional.of(attrSchema));

		final ReferenceKey refKey = new ReferenceKey("brand", 42);

		final ReferenceProxyState state = new ReferenceProxyState(
			mockRefSchema, refKey, 1, null, Set.of(), null, null, null
		);

		final ReferenceContract proxy = createReferenceProxy(
			state,
			ReferenceIdentityPartial.GET_REFERENCE_KEY,
			ReferenceAttributePartial.GET_ATTRIBUTE_SCHEMA,
			ReferenceAttributePartial.ATTRIBUTES_AVAILABLE,
			ReferenceVersionAndDroppablePartial.VERSION,
			ReferenceVersionAndDroppablePartial.DROPPED
		);

		final Optional<?> result = proxy.getAttributeSchema("order");
		assertTrue(result.isPresent(), "getAttributeSchema('order') should be present");
		assertSame(attrSchema, result.get(), "Should return the schema from reference schema");
	}

	@Test
	@DisplayName("attributesAvailable() returns true")
	void shouldReturnTrueForAttributesAvailable() {
		final ReferenceSchemaContract mockRefSchema = mock(ReferenceSchemaContract.class);
		when(mockRefSchema.getReferencedEntityType()).thenReturn("Brand");
		when(mockRefSchema.getCardinality()).thenReturn(Cardinality.ZERO_OR_MORE);

		final ReferenceKey refKey = new ReferenceKey("brand", 42);

		final ReferenceProxyState state = new ReferenceProxyState(
			mockRefSchema, refKey, 1, null, Set.of(), null, null, null
		);

		final ReferenceContract proxy = createReferenceProxy(
			state,
			ReferenceIdentityPartial.GET_REFERENCE_KEY,
			ReferenceAttributePartial.ATTRIBUTES_AVAILABLE,
			ReferenceVersionAndDroppablePartial.VERSION,
			ReferenceVersionAndDroppablePartial.DROPPED
		);

		assertTrue(proxy.attributesAvailable(), "attributesAvailable() should return true");
	}

	@Test
	@DisplayName("getAttribute(String) returns null when attribute array is null")
	void shouldReturnNullWhenAttributeArrayNull() {
		final ReferenceSchemaContract mockRefSchema = mock(ReferenceSchemaContract.class);
		when(mockRefSchema.getReferencedEntityType()).thenReturn("Brand");
		when(mockRefSchema.getCardinality()).thenReturn(Cardinality.ZERO_OR_MORE);

		final ReferenceKey refKey = new ReferenceKey("brand", 42);

		// attributes array is null in the state
		final ReferenceProxyState state = new ReferenceProxyState(
			mockRefSchema, refKey, 1, null, Set.of(), null, null, null
		);

		final ReferenceContract proxy = createReferenceProxy(
			state,
			ReferenceIdentityPartial.GET_REFERENCE_KEY,
			ReferenceAttributePartial.GET_ATTRIBUTE,
			ReferenceAttributePartial.ATTRIBUTES_AVAILABLE,
			ReferenceVersionAndDroppablePartial.VERSION,
			ReferenceVersionAndDroppablePartial.DROPPED
		);

		assertNull(
			proxy.getAttribute("code"),
			"getAttribute() should return null when attribute array is null"
		);
	}

	@Test
	@DisplayName("Should return attribute locales from state")
	void shouldReturnAttributeLocalesFromState() {
		final ReferenceSchemaContract mockRefSchema = mock(ReferenceSchemaContract.class);
		when(mockRefSchema.getReferencedEntityType()).thenReturn("Brand");
		when(mockRefSchema.getCardinality()).thenReturn(Cardinality.ZERO_OR_MORE);

		final ReferenceKey refKey = new ReferenceKey("brand", 42);
		final Set<Locale> expectedLocales = Set.of(Locale.ENGLISH, Locale.GERMAN);

		final ReferenceProxyState state = new ReferenceProxyState(
			mockRefSchema, refKey, 1, null, expectedLocales, null, null, null
		);

		final ReferenceContract proxy = createReferenceProxy(
			state,
			ReferenceIdentityPartial.GET_REFERENCE_KEY,
			ReferenceAttributePartial.GET_ATTRIBUTE_LOCALES,
			ReferenceAttributePartial.ATTRIBUTES_AVAILABLE,
			ReferenceVersionAndDroppablePartial.VERSION,
			ReferenceVersionAndDroppablePartial.DROPPED
		);

		final Set<Locale> locales = proxy.getAttributeLocales();
		assertEquals(2, locales.size(), "Should contain exactly 2 locales");
		assertTrue(locales.contains(Locale.ENGLISH), "Should contain ENGLISH");
		assertTrue(locales.contains(Locale.GERMAN), "Should contain GERMAN");
	}

	@Test
	@DisplayName("getAttribute(String) should return null for dropped (tombstoned) reference attribute")
	void shouldReturnNullForDroppedReferenceAttribute() {
		final ReferenceSchemaContract mockRefSchema = mock(ReferenceSchemaContract.class);
		when(mockRefSchema.getName()).thenReturn("brand");
		when(mockRefSchema.getReferencedEntityType()).thenReturn("Brand");
		when(mockRefSchema.getCardinality()).thenReturn(Cardinality.ZERO_OR_MORE);

		// version=1, key="order", value=5, dropped=true
		final AttributeValue droppedAttr = new AttributeValue(1, new AttributeKey("order"), 5, true);
		final AttributeValue[] attrs = new AttributeValue[]{ droppedAttr };
		Arrays.sort(attrs);

		final ReferenceKey refKey = new ReferenceKey("brand", 42);
		final ReferenceProxyState state = new ReferenceProxyState(
			mockRefSchema, refKey, 1, attrs, Set.of(), null, null, null
		);

		final ReferenceContract proxy = createReferenceProxy(
			state,
			ReferenceIdentityPartial.GET_REFERENCE_KEY,
			ReferenceAttributePartial.GET_ATTRIBUTE,
			ReferenceVersionAndDroppablePartial.VERSION,
			ReferenceVersionAndDroppablePartial.DROPPED
		);

		assertNull(proxy.getAttribute("order"), "Dropped reference attribute should return null");
	}
}
