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

package io.evitadb.index.attribute;

import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.ATTRIBUTE;

/**
 * Test verifies contract of {@link GlobalUniqueIndex}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Tag(INDEXING)
@Tag(ATTRIBUTE)
class GlobalUniqueIndexTest {
	private final Catalog catalog = Mockito.mock(Catalog.class);
	private final EntityReferenceWithLocale productRef = new EntityReferenceWithLocale(Entities.PRODUCT, 1, null);
	private final EntityReferenceWithLocale localizedProduct2EnglishRef = new EntityReferenceWithLocale(Entities.PRODUCT, 2, Locale.ENGLISH);
	private final EntityReferenceWithLocale localizedProduct2FrenchRef = new EntityReferenceWithLocale(Entities.PRODUCT, 2, Locale.FRENCH);
	private final EntityReferenceWithLocale localizedProduct3Ref = new EntityReferenceWithLocale(Entities.PRODUCT, 3, Locale.ENGLISH);
	private final GlobalUniqueIndex tested = new GlobalUniqueIndex(
		Scope.LIVE, new AttributeKey("whatever"), String.class, new HashMap<>(), new HashMap<>()
	);

	@BeforeEach
	void setUp() {
		final EntityCollection productCollection = Mockito.mock(EntityCollection.class);
		Mockito.when(productCollection.getEntityTypePrimaryKey()).thenReturn(1);
		Mockito.when(productCollection.getEntityType()).thenReturn(Entities.PRODUCT);
		Mockito.when(this.catalog.getCollectionForEntityPrimaryKeyOrThrowException(1)).thenReturn(productCollection);
		Mockito.when(this.catalog.getCollectionForEntityOrThrowException(Entities.PRODUCT)).thenReturn(productCollection);
		this.tested.attachToCatalog(null, this.catalog);
	}

	@Test
	void shouldRegisterUniqueValueAndRetrieveItBack() {
		this.tested.registerUniqueKey("A", Entities.PRODUCT, null, 1);
		assertEquals(this.productRef, this.tested.getEntityReferenceByUniqueValue("A", null).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("B", null).orElse(null));
	}

	@Test
	void shouldRegisterLocalizedUniqueValueAndRetrieveItBack() {
		this.tested.registerUniqueKey("A", Entities.PRODUCT, Locale.ENGLISH, 2);
		this.tested.registerUniqueKey("B", Entities.PRODUCT, Locale.FRENCH, 2);
		this.tested.registerUniqueKey("C", Entities.PRODUCT, Locale.ENGLISH, 3);
		assertEquals(this.localizedProduct2EnglishRef, this.tested.getEntityReferenceByUniqueValue("A", Locale.ENGLISH).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("A", Locale.FRENCH).orElse(null));
		assertEquals(this.localizedProduct2FrenchRef, this.tested.getEntityReferenceByUniqueValue("B", Locale.FRENCH).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("B", Locale.ENGLISH).orElse(null));
		assertEquals(this.localizedProduct3Ref, this.tested.getEntityReferenceByUniqueValue("C", Locale.ENGLISH).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("E", null).orElse(null));
	}

	@Test
	void shouldFailToRegisterDuplicateValues() {
		this.tested.registerUniqueKey("A", Entities.PRODUCT, null, 1);
		assertThrows(UniqueValueViolationException.class, () -> this.tested.registerUniqueKey("A", Entities.PRODUCT, null, 2));
	}

	@Test
	void shouldFailToRegisterDuplicateLocalizedValues() {
		this.tested.registerUniqueKey("A", Entities.PRODUCT, Locale.ENGLISH, 1);
		assertThrows(UniqueValueViolationException.class, () -> this.tested.registerUniqueKey("A", Entities.PRODUCT, Locale.GERMAN, 2));
	}

	@Test
	void shouldUnregisterPreviouslyRegisteredValue() {
		this.tested.registerUniqueKey("A", Entities.PRODUCT, null, 1);
		assertEquals(this.productRef, this.tested.unregisterUniqueKey("A", Entities.PRODUCT, null, 1));
		assertNull(this.tested.getEntityReferenceByUniqueValue("A", null).orElse(null));
	}

	@Test
	void shouldUnregisterPreviouslyRegisteredLocalizedValue() {
		this.tested.registerUniqueKey("A", Entities.PRODUCT, Locale.ENGLISH, 2);
		this.tested.registerUniqueKey("B", Entities.PRODUCT, Locale.FRENCH, 2);
		this.tested.registerUniqueKey("C", Entities.PRODUCT, Locale.ENGLISH, 3);
		assertEquals(this.localizedProduct2EnglishRef, this.tested.unregisterUniqueKey("A", Entities.PRODUCT, Locale.ENGLISH, 2));
		assertEquals(this.localizedProduct2FrenchRef, this.tested.unregisterUniqueKey("B", Entities.PRODUCT, Locale.FRENCH, 2));
		assertEquals(this.localizedProduct3Ref, this.tested.unregisterUniqueKey("C", Entities.PRODUCT, Locale.ENGLISH, 3));

		assertNull(this.tested.getEntityReferenceByUniqueValue("A", Locale.ENGLISH).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("B", Locale.FRENCH).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("C", Locale.ENGLISH).orElse(null));
	}

	@Test
	void shouldFailToUnregisterUnknownValue() {
		assertThrows(IllegalArgumentException.class, () -> this.tested.unregisterUniqueKey("B", Entities.PRODUCT, null, 1));
		assertThrows(IllegalArgumentException.class, () -> this.tested.unregisterUniqueKey("B", Entities.PRODUCT, Locale.ENGLISH, 1));
	}

	@Test
	void shouldRegisterAndPartialUnregisterValues() {
		this.tested.registerUniqueKey(new String[]{"A", "B", "C"}, Entities.PRODUCT, null, 1);
		assertEquals(this.productRef, this.tested.getEntityReferenceByUniqueValue("A", null).orElse(null));
		assertEquals(this.productRef, this.tested.getEntityReferenceByUniqueValue("B", null).orElse(null));
		assertEquals(this.productRef, this.tested.getEntityReferenceByUniqueValue("C", null).orElse(null));

		this.tested.unregisterUniqueKey(new String[]{"B", "C"}, Entities.PRODUCT, null, 1);
		assertEquals(this.productRef, this.tested.getEntityReferenceByUniqueValue("A", null).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("B", null).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("C", null).orElse(null));
	}

	@Test
	void shouldRegisterAndPartialUnregisterLocalizedValues() {
		this.tested.registerUniqueKey(new String[]{"A", "B", "C"}, Entities.PRODUCT, Locale.ENGLISH, 1);
		assertEquals(this.productRef, this.tested.getEntityReferenceByUniqueValue("A", Locale.ENGLISH).orElse(null));
		assertEquals(this.productRef, this.tested.getEntityReferenceByUniqueValue("B", Locale.ENGLISH).orElse(null));
		assertEquals(this.productRef, this.tested.getEntityReferenceByUniqueValue("C", Locale.ENGLISH).orElse(null));

		this.tested.unregisterUniqueKey(new String[]{"B", "C"}, Entities.PRODUCT, Locale.ENGLISH, 1);
		assertEquals(this.productRef, this.tested.getEntityReferenceByUniqueValue("A", Locale.ENGLISH).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("B", Locale.ENGLISH).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("C", Locale.ENGLISH).orElse(null));
	}

}
