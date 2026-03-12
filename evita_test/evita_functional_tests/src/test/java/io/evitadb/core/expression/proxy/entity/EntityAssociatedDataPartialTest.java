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

import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.expression.proxy.CatchAllPartial;
import io.evitadb.core.expression.proxy.EntityProxyState;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AssociatedDataStoragePart;
import one.edee.oss.proxycian.PredicateMethodClassification;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyDispatcherInvocationHandler;
import one.edee.oss.proxycian.bytebuddy.ByteBuddyProxyGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.evitadb.utils.ArrayUtils.EMPTY_CLASS_ARRAY;
import static io.evitadb.utils.ArrayUtils.EMPTY_OBJECT_ARRAY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link EntityAssociatedDataPartial} verifying that associated data method implementations correctly
 * delegate to the proxy state and storage parts.
 */
@SuppressWarnings("rawtypes")
@DisplayName("Entity associated data partial")
class EntityAssociatedDataPartialTest {

	/** Unique marker interface to avoid Proxycian classification cache pollution between test classes. */
	private interface AssociatedDataTestEntity extends EntityContract {}

	private static final int ENTITY_PK = 1;

	/**
	 * Creates an EntityContract proxy with the given partials plus the CatchAllPartial safety net.
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
			new Class<?>[]{ AssociatedDataTestEntity.class },
			EMPTY_CLASS_ARRAY,
			EMPTY_OBJECT_ARRAY
		);
	}

	/**
	 * Creates an {@link AssociatedDataStoragePart} with the given associated data value.
	 */
	@Nonnull
	private static AssociatedDataStoragePart createAssociatedDataPart(
		@Nonnull String name, @Nullable Locale locale, @Nonnull String value
	) {
		final AssociatedDataKey key = locale == null
			? new AssociatedDataKey(name)
			: new AssociatedDataKey(name, locale);
		final AssociatedDataValue adValue = new AssociatedDataValue(key, value);
		final AssociatedDataStoragePart part = new AssociatedDataStoragePart(1L, ENTITY_PK, adValue, -1);
		return part;
	}

	@Test
	@DisplayName("getAssociatedData(String) returns value by name")
	void shouldReturnAssociatedDataByName() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");

		final AssociatedDataStoragePart descPart = createAssociatedDataPart("description", null, "A great product");

		final Map<AssociatedDataKey, AssociatedDataStoragePart> adMap = new HashMap<>(4);
		adMap.put(new AssociatedDataKey("description"), descPart);

		final EntityProxyState state = new EntityProxyState(
			mockSchema, null, null, null, adMap, null
		);

		final EntityContract proxy = createEntityProxy(
			state,
			EntityAssociatedDataPartial.GET_ASSOCIATED_DATA,
			EntityAssociatedDataPartial.GET_ASSOCIATED_DATA_LOCALIZED,
			EntityAssociatedDataPartial.ASSOCIATED_DATA_AVAILABLE
		);

		assertEquals(
			"A great product", proxy.getAssociatedData("description"),
			"getAssociatedData('description') should return 'A great product'"
		);
	}

	@Test
	@DisplayName("getAssociatedData(String) returns null for missing")
	void shouldReturnNullForMissingAssociatedData() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");

		final Map<AssociatedDataKey, AssociatedDataStoragePart> adMap = new HashMap<>(4);

		final EntityProxyState state = new EntityProxyState(
			mockSchema, null, null, null, adMap, null
		);

		final EntityContract proxy = createEntityProxy(
			state,
			EntityAssociatedDataPartial.GET_ASSOCIATED_DATA,
			EntityAssociatedDataPartial.ASSOCIATED_DATA_AVAILABLE
		);

		assertNull(proxy.getAssociatedData("nonExistent"), "getAssociatedData('nonExistent') should return null");
	}

	@Test
	@DisplayName("getAssociatedData(String, Locale) returns localized value")
	void shouldReturnLocalizedAssociatedData() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");

		final AssociatedDataStoragePart enPart = createAssociatedDataPart(
			"description", Locale.ENGLISH, "An English description"
		);

		final Map<AssociatedDataKey, AssociatedDataStoragePart> adMap = new HashMap<>(4);
		adMap.put(new AssociatedDataKey("description", Locale.ENGLISH), enPart);

		final EntityProxyState state = new EntityProxyState(
			mockSchema, null, null, null, adMap, null
		);

		final EntityContract proxy = createEntityProxy(
			state,
			EntityAssociatedDataPartial.GET_ASSOCIATED_DATA,
			EntityAssociatedDataPartial.GET_ASSOCIATED_DATA_LOCALIZED,
			EntityAssociatedDataPartial.ASSOCIATED_DATA_AVAILABLE
		);

		assertEquals(
			"An English description", proxy.getAssociatedData("description", Locale.ENGLISH),
			"getAssociatedData('description', EN) should return the English value"
		);
		assertNull(
			proxy.getAssociatedData("description", Locale.GERMAN),
			"getAssociatedData('description', DE) should return null when only EN exists"
		);
	}

	@Test
	@DisplayName("getAssociatedDataLocales() returns union of locales")
	void shouldReturnUnionOfAssociatedDataLocales() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");

		final AssociatedDataStoragePart globalPart = createAssociatedDataPart("specs", null, "spec data");
		final AssociatedDataStoragePart enPart = createAssociatedDataPart("description", Locale.ENGLISH, "EN desc");
		final AssociatedDataStoragePart dePart = createAssociatedDataPart("description", Locale.GERMAN, "DE desc");

		final Map<AssociatedDataKey, AssociatedDataStoragePart> adMap = new HashMap<>(4);
		adMap.put(new AssociatedDataKey("specs"), globalPart);
		adMap.put(new AssociatedDataKey("description", Locale.ENGLISH), enPart);
		adMap.put(new AssociatedDataKey("description", Locale.GERMAN), dePart);

		final EntityProxyState state = new EntityProxyState(
			mockSchema, null, null, null, adMap, null
		);

		final EntityContract proxy = createEntityProxy(
			state,
			EntityAssociatedDataPartial.GET_ASSOCIATED_DATA_LOCALES,
			EntityAssociatedDataPartial.ASSOCIATED_DATA_AVAILABLE
		);

		final Set<Locale> locales = proxy.getAssociatedDataLocales();
		assertEquals(2, locales.size(), "Should contain exactly 2 locales");
		assertTrue(locales.contains(Locale.ENGLISH), "Should contain ENGLISH");
		assertTrue(locales.contains(Locale.GERMAN), "Should contain GERMAN");
	}

	@Test
	@DisplayName("getAssociatedDataSchema(String) delegates to schema")
	void shouldDelegateAssociatedDataSchemaToEntitySchema() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");
		final AssociatedDataSchemaContract adSchema = mock(AssociatedDataSchemaContract.class);
		when(mockSchema.getAssociatedData("description")).thenReturn(Optional.of(adSchema));

		final EntityProxyState state = new EntityProxyState(mockSchema, null, null, null, null, null);

		final EntityContract proxy = createEntityProxy(
			state,
			EntityAssociatedDataPartial.GET_ASSOCIATED_DATA_SCHEMA,
			EntityAssociatedDataPartial.ASSOCIATED_DATA_AVAILABLE
		);

		final Optional<?> result = proxy.getAssociatedDataSchema("description");
		assertTrue(result.isPresent(), "Should find the schema");
		assertSame(adSchema, result.get(), "Should return the schema from entity schema");
	}

	@Test
	@DisplayName("associatedDataAvailable() returns true")
	void shouldReturnTrueForAssociatedDataAvailable() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");

		final EntityProxyState state = new EntityProxyState(mockSchema, null, null, null, null, null);

		final EntityContract proxy = createEntityProxy(
			state, EntityAssociatedDataPartial.ASSOCIATED_DATA_AVAILABLE
		);

		assertTrue(proxy.associatedDataAvailable(), "associatedDataAvailable() should return true");
	}

	@Test
	@DisplayName("getAssociatedData(String) returns null when associated data parts map is null")
	void shouldReturnNullWhenAssociatedDataPartsNull() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");

		// associatedDataParts is null in the state
		final EntityProxyState state = new EntityProxyState(
			mockSchema, null, null, null, null, null
		);

		final EntityContract proxy = createEntityProxy(
			state,
			EntityAssociatedDataPartial.GET_ASSOCIATED_DATA,
			EntityAssociatedDataPartial.ASSOCIATED_DATA_AVAILABLE
		);

		assertNull(
			proxy.getAssociatedData("description"),
			"getAssociatedData() should return null when associated data parts map is null"
		);
	}

	@Test
	@DisplayName("getAssociatedData(String) should return null for dropped associated data")
	void shouldReturnNullForDroppedAssociatedData() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");

		final AssociatedDataKey key = new AssociatedDataKey("description");
		// version=1, key, value="Old desc", dropped=true
		final AssociatedDataValue droppedValue = new AssociatedDataValue(1, key, "Old desc", true);
		final AssociatedDataStoragePart part = new AssociatedDataStoragePart(1L, ENTITY_PK, droppedValue, -1);
		final Map<AssociatedDataKey, AssociatedDataStoragePart> adParts = new HashMap<>();
		adParts.put(key, part);

		final EntityProxyState state = new EntityProxyState(mockSchema, null, null, null, adParts, null);
		final EntityContract proxy = createEntityProxy(state, EntityAssociatedDataPartial.GET_ASSOCIATED_DATA);

		assertNull(proxy.getAssociatedData("description"), "Dropped associated data should return null");
	}

	@Test
	@DisplayName("getAssociatedData(String, Locale) should return null for dropped localized associated data")
	void shouldReturnNullForDroppedLocalizedAssociatedData() {
		final EntitySchemaContract mockSchema = mock(EntitySchemaContract.class);
		when(mockSchema.getName()).thenReturn("Product");

		final AssociatedDataKey key = new AssociatedDataKey("description", Locale.ENGLISH);
		final AssociatedDataValue droppedValue = new AssociatedDataValue(1, key, "Old English desc", true);
		final AssociatedDataStoragePart part = new AssociatedDataStoragePart(1L, ENTITY_PK, droppedValue, -1);
		final Map<AssociatedDataKey, AssociatedDataStoragePart> adParts = new HashMap<>();
		adParts.put(key, part);

		final EntityProxyState state = new EntityProxyState(mockSchema, null, null, null, adParts, null);
		final EntityContract proxy = createEntityProxy(
			state, EntityAssociatedDataPartial.GET_ASSOCIATED_DATA_LOCALIZED
		);

		assertNull(
			proxy.getAssociatedData("description", Locale.ENGLISH),
			"Dropped localized associated data should return null"
		);
	}
}
