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

import io.evitadb.api.CatalogState;
import io.evitadb.api.exception.UniqueValueViolationException;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Locale;

import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
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
		Scope.LIVE, new AttributeKey("whatever"), String.class
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

	@Test
	void shouldRoundTripLocalizedTupleThroughPackedPayload() {
		// the (entityType, primaryKey, locale) tuple must survive the pack/unpack at the long-payload tree boundary
		this.tested.registerUniqueKey("A", Entities.PRODUCT, Locale.ENGLISH, 2);
		assertEquals(
			this.localizedProduct2EnglishRef,
			this.tested.getEntityReferenceByUniqueValue("A", Locale.ENGLISH).orElse(null)
		);
		// the resolved reference carries the very same entity type, primary key and locale that were registered
		final EntityReferenceWithLocale resolved = this.tested.getEntityReferenceByUniqueValue("A", Locale.ENGLISH).orElseThrow();
		assertEquals(Entities.PRODUCT, resolved.getType());
		assertEquals(2, resolved.getPrimaryKey());
		assertEquals(Locale.ENGLISH, resolved.locale());
	}

	@Test
	void shouldFailWhenEntityTypeIdExceedsPayloadField() {
		// a synthetic entity type whose primary key overflows the 16-bit packed payload field must be rejected loudly
		// rather than silently truncated
		final EntityCollection bigCollection = Mockito.mock(EntityCollection.class);
		Mockito.when(bigCollection.getEntityTypePrimaryKey()).thenReturn(0x10000);
		Mockito.when(this.catalog.getCollectionForEntityOrThrowException("BIG")).thenReturn(bigCollection);
		assertThrows(
			GenericEvitaInternalError.class,
			() -> this.tested.registerUniqueKey("A", "BIG", null, 1)
		);
	}

	@Test
	void shouldAllowSameValueAcrossLocalesForLocalizedAttribute() {
		// a localized (within-locale-unique) attribute permits the same value to coexist across different locales
		final GlobalUniqueIndex localized = new GlobalUniqueIndex(
			Scope.LIVE, new AttributeKey("localizedCode", Locale.ENGLISH), String.class
		);
		localized.attachToCatalog(null, this.catalog);
		localized.registerUniqueKey("A", Entities.PRODUCT, Locale.ENGLISH, 2);
		// registering the same value under a different locale must NOT raise a uniqueness violation
		assertDoesNotThrow(() -> localized.registerUniqueKey("A", Entities.PRODUCT, Locale.FRENCH, 3));
		// the value resolves under the last writer's locale (the value-keyed tree overwrites like the HashMap it replaces)
		assertEquals(
			new EntityReferenceWithLocale(Entities.PRODUCT, 3, Locale.FRENCH),
			localized.getEntityReferenceByUniqueValue("A", Locale.FRENCH).orElse(null)
		);
		// both primary keys remain visible in the per-entity-type record set
		final Bitmap productRecords = localized.getRecordIds(Entities.PRODUCT);
		assertTrue(productRecords.contains(2));
		assertTrue(productRecords.contains(3));
	}

	@Test
	void shouldAssignFreshLocaleIdToShellCopyInsteadOfCollidingWithExisting() {
		// a localized globally-unique index with two locales already registered: en -> 1, fr -> 2
		final GlobalUniqueIndex localized = new GlobalUniqueIndex(
			Scope.LIVE, new AttributeKey("localizedCode", Locale.ENGLISH), String.class
		);
		localized.attachToCatalog(null, this.catalog);
		localized.registerUniqueKey("en-value", Entities.PRODUCT, Locale.ENGLISH, 1);
		localized.registerUniqueKey("fr-value", Entities.PRODUCT, Locale.FRENCH, 2);
		assertEquals(Locale.ENGLISH, localized.getLocaleIndex().get(1));
		assertEquals(Locale.FRENCH, localized.getLocaleIndex().get(2));

		// the detached shell copy shares the locale maps by reference; its sequence must be primed past id 2
		final GlobalUniqueIndex shell = localized.createCopyForNewCatalogAttachment(CatalogState.ALIVE);
		shell.attachToCatalog(null, this.catalog);

		// registering a never-before-seen locale must receive a FRESH id (3), not collide with the existing id 1
		shell.registerUniqueKey("de-value", Entities.PRODUCT, Locale.GERMAN, 3);
		assertEquals(Locale.GERMAN, shell.getLocaleIndex().get(3), "new locale must receive a fresh id");
		assertEquals(Locale.ENGLISH, shell.getLocaleIndex().get(1), "existing locale id must stay untouched");

		// a pre-existing english value still decodes to its original locale rather than the freshly registered one
		assertEquals(
			new EntityReferenceWithLocale(Entities.PRODUCT, 1, Locale.ENGLISH),
			shell.getEntityReferenceByUniqueValue("en-value", Locale.ENGLISH).orElse(null)
		);

		// the same value under the new locale must not trip the within-locale uniqueness guard against the old locale
		assertDoesNotThrow(() -> shell.registerUniqueKey("en-value", Entities.PRODUCT, Locale.GERMAN, 4));
	}

	@Test
	void shouldStartShellCopyLocaleSequencePastHighestExistingId() {
		// two locales already assigned ids 1 and 2 on the source index
		final GlobalUniqueIndex localized = new GlobalUniqueIndex(
			Scope.LIVE, new AttributeKey("localizedCode", Locale.ENGLISH), String.class
		);
		localized.attachToCatalog(null, this.catalog);
		localized.registerUniqueKey("en-value", Entities.PRODUCT, Locale.ENGLISH, 1);
		localized.registerUniqueKey("fr-value", Entities.PRODUCT, Locale.FRENCH, 2);

		final GlobalUniqueIndex shell = localized.createCopyForNewCatalogAttachment(CatalogState.ALIVE);
		shell.attachToCatalog(null, this.catalog);
		shell.registerUniqueKey("de-value", Entities.PRODUCT, Locale.GERMAN, 3);

		// the next assigned locale id equals max(existing ids) + 1, leaving exactly three distinct ids
		assertEquals(3, shell.getLocaleIndex().size());
		assertEquals(Locale.GERMAN, shell.getLocaleIndex().get(3));
	}

	@Test
	void shouldPersistFreshLocaleIdThroughCommitMergeForShellCopy() {
		// two locales already assigned ids 1 and 2 on the source index
		final GlobalUniqueIndex localized = new GlobalUniqueIndex(
			Scope.LIVE, new AttributeKey("localizedCode", Locale.ENGLISH), String.class
		);
		localized.attachToCatalog(null, this.catalog);
		localized.registerUniqueKey("en-value", Entities.PRODUCT, Locale.ENGLISH, 1);
		localized.registerUniqueKey("fr-value", Entities.PRODUCT, Locale.FRENCH, 2);

		final GlobalUniqueIndex shell = localized.createCopyForNewCatalogAttachment(CatalogState.ALIVE);
		shell.attachToCatalog(null, this.catalog);
		shell.registerUniqueKey("de-value", Entities.PRODUCT, Locale.GERMAN, 3);

		// a full commit-merge must bake the CORRECT locale ids into committed state; the merge re-primes from
		// idToLocaleIndex, so a broken shell would persist the corruption permanently rather than repair it
		assertStateAfterCommit(
			shell,
			s -> {
				// nothing further mutated; the merge alone must preserve the primed sequence and locale mapping
			},
			(s, committed) -> {
				committed.attachToCatalog(null, this.catalog);
				assertEquals(Locale.ENGLISH, committed.getLocaleIndex().get(1));
				assertEquals(Locale.GERMAN, committed.getLocaleIndex().get(3));
				assertEquals(
					new EntityReferenceWithLocale(Entities.PRODUCT, 1, Locale.ENGLISH),
					committed.getEntityReferenceByUniqueValue("en-value", Locale.ENGLISH).orElse(null)
				);
				assertEquals(
					new EntityReferenceWithLocale(Entities.PRODUCT, 3, Locale.GERMAN),
					committed.getEntityReferenceByUniqueValue("de-value", Locale.GERMAN).orElse(null)
				);
			}
		);
	}

}
