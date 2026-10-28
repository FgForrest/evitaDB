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
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityTypeClassifierResolver;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.time.LocalDateTime;
import java.io.Serializable;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueIndexStoragePart;
import io.evitadb.core.buffer.TrappedChanges;

import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
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
	/**
	 * A real resolver knowing products (compact primary key 1) and categories (2), for tests that need a second entity
	 * type.
	 */
	private static final EntityTypeClassifierResolver PRODUCT_AND_CATEGORY =
		resolverOf(Map.of(Entities.PRODUCT, 1, Entities.CATEGORY, 2));
	private final Catalog catalog = Mockito.mock(Catalog.class);
	/**
	 * The entity-type name ↔ compact primary key resolver the index now receives per call instead of holding a catalog
	 * back-reference. It delegates to the same mock-catalog collection accessors the tests already stub, so no extra
	 * stubbing is needed.
	 */
	private final EntityTypeClassifierResolver classifierResolver = new EntityTypeClassifierResolver() {
		@Override
		public int toEntityTypePrimaryKey(@Nonnull String entityType) {
			return GlobalUniqueIndexTest.this.catalog.getCollectionForEntityOrThrowException(entityType).getEntityTypePrimaryKey();
		}

		@Nonnull
		@Override
		public String toEntityTypeName(int entityTypePrimaryKey) {
			return GlobalUniqueIndexTest.this.catalog.getCollectionForEntityPrimaryKeyOrThrowException(entityTypePrimaryKey).getEntityType();
		}
	};
	private final EntityReferenceWithLocale productRef = new EntityReferenceWithLocale(Entities.PRODUCT, 1, null);
	private final EntityReferenceWithLocale localizedProduct2EnglishRef = new EntityReferenceWithLocale(Entities.PRODUCT, 2, Locale.ENGLISH);
	private final EntityReferenceWithLocale localizedProduct2FrenchRef = new EntityReferenceWithLocale(Entities.PRODUCT, 2, Locale.FRENCH);
	private final EntityReferenceWithLocale localizedProduct3Ref = new EntityReferenceWithLocale(Entities.PRODUCT, 3, Locale.ENGLISH);
	private final GlobalUniqueIndex tested = new GlobalUniqueIndex(
		Scope.LIVE, new AttributeKey("whatever"), String.class
	, 0);

	/**
	 * Creates a resolver translating between the entity type names and compact primary keys of `entityTypes`, and
	 * failing loudly on any entity type it was not given.
	 *
	 * @param entityTypes entity type name to compact entity type primary key
	 * @return the map-backed resolver
	 */
	@Nonnull
	private static EntityTypeClassifierResolver resolverOf(@Nonnull Map<String, Integer> entityTypes) {
		final Map<Integer, String> namesByPrimaryKey = new HashMap<>(entityTypes.size());
		entityTypes.forEach((name, primaryKey) -> namesByPrimaryKey.put(primaryKey, name));
		return new EntityTypeClassifierResolver() {
			@Override
			public int toEntityTypePrimaryKey(@Nonnull String entityType) {
				return Objects.requireNonNull(entityTypes.get(entityType), () -> "Unknown entity type " + entityType);
			}

			@Nonnull
			@Override
			public String toEntityTypeName(int entityTypePrimaryKey) {
				return Objects.requireNonNull(
					namesByPrimaryKey.get(entityTypePrimaryKey),
					() -> "Unknown entity type primary key " + entityTypePrimaryKey
				);
			}
		};
	}

	@BeforeEach
	void setUp() {
		final EntityCollection productCollection = Mockito.mock(EntityCollection.class);
		Mockito.when(productCollection.getEntityTypePrimaryKey()).thenReturn(1);
		Mockito.when(productCollection.getEntityType()).thenReturn(Entities.PRODUCT);
		Mockito.when(this.catalog.getCollectionForEntityPrimaryKeyOrThrowException(1)).thenReturn(productCollection);
		Mockito.when(this.catalog.getCollectionForEntityOrThrowException(Entities.PRODUCT)).thenReturn(productCollection);
	}

	@Test
	void shouldRegisterUniqueValueAndRetrieveItBack() {
		this.tested.registerUniqueKey("A", Entities.PRODUCT, null, 1, this.classifierResolver);
		assertEquals(this.productRef, this.tested.getEntityReferenceByUniqueValue("A", null, this.classifierResolver).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("B", null, this.classifierResolver).orElse(null));
	}

	@Test
	void shouldRegisterLocalizedUniqueValueAndRetrieveItBack() {
		this.tested.registerUniqueKey("A", Entities.PRODUCT, Locale.ENGLISH, 2, this.classifierResolver);
		this.tested.registerUniqueKey("B", Entities.PRODUCT, Locale.FRENCH, 2, this.classifierResolver);
		this.tested.registerUniqueKey("C", Entities.PRODUCT, Locale.ENGLISH, 3, this.classifierResolver);
		assertEquals(this.localizedProduct2EnglishRef, this.tested.getEntityReferenceByUniqueValue("A", Locale.ENGLISH, this.classifierResolver).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("A", Locale.FRENCH, this.classifierResolver).orElse(null));
		assertEquals(this.localizedProduct2FrenchRef, this.tested.getEntityReferenceByUniqueValue("B", Locale.FRENCH, this.classifierResolver).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("B", Locale.ENGLISH, this.classifierResolver).orElse(null));
		assertEquals(this.localizedProduct3Ref, this.tested.getEntityReferenceByUniqueValue("C", Locale.ENGLISH, this.classifierResolver).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("E", null, this.classifierResolver).orElse(null));
	}

	@Test
	void shouldFailToRegisterDuplicateValues() {
		this.tested.registerUniqueKey("A", Entities.PRODUCT, null, 1, this.classifierResolver);
		assertThrows(UniqueValueViolationException.class, () -> this.tested.registerUniqueKey("A", Entities.PRODUCT, null, 2, this.classifierResolver));
	}

	@Test
	void shouldFailToRegisterDuplicateLocalizedValues() {
		this.tested.registerUniqueKey("A", Entities.PRODUCT, Locale.ENGLISH, 1, this.classifierResolver);
		assertThrows(UniqueValueViolationException.class, () -> this.tested.registerUniqueKey("A", Entities.PRODUCT, Locale.GERMAN, 2, this.classifierResolver));
	}

	@Test
	void shouldUnregisterPreviouslyRegisteredValue() {
		this.tested.registerUniqueKey("A", Entities.PRODUCT, null, 1, this.classifierResolver);
		assertEquals(this.productRef, this.tested.unregisterUniqueKey("A", Entities.PRODUCT, null, 1, this.classifierResolver));
		assertNull(this.tested.getEntityReferenceByUniqueValue("A", null, this.classifierResolver).orElse(null));
	}

	@Test
	void shouldUnregisterPreviouslyRegisteredLocalizedValue() {
		this.tested.registerUniqueKey("A", Entities.PRODUCT, Locale.ENGLISH, 2, this.classifierResolver);
		this.tested.registerUniqueKey("B", Entities.PRODUCT, Locale.FRENCH, 2, this.classifierResolver);
		this.tested.registerUniqueKey("C", Entities.PRODUCT, Locale.ENGLISH, 3, this.classifierResolver);
		assertEquals(this.localizedProduct2EnglishRef, this.tested.unregisterUniqueKey("A", Entities.PRODUCT, Locale.ENGLISH, 2, this.classifierResolver));
		assertEquals(this.localizedProduct2FrenchRef, this.tested.unregisterUniqueKey("B", Entities.PRODUCT, Locale.FRENCH, 2, this.classifierResolver));
		assertEquals(this.localizedProduct3Ref, this.tested.unregisterUniqueKey("C", Entities.PRODUCT, Locale.ENGLISH, 3, this.classifierResolver));

		assertNull(this.tested.getEntityReferenceByUniqueValue("A", Locale.ENGLISH, this.classifierResolver).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("B", Locale.FRENCH, this.classifierResolver).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("C", Locale.ENGLISH, this.classifierResolver).orElse(null));
	}

	@Test
	void shouldFailToUnregisterUnknownValue() {
		assertThrows(IllegalArgumentException.class, () -> this.tested.unregisterUniqueKey("B", Entities.PRODUCT, null, 1, this.classifierResolver));
		assertThrows(IllegalArgumentException.class, () -> this.tested.unregisterUniqueKey("B", Entities.PRODUCT, Locale.ENGLISH, 1, this.classifierResolver));
	}

	@Test
	void shouldRegisterAndPartialUnregisterValues() {
		this.tested.registerUniqueKey(new String[]{"A", "B", "C"}, Entities.PRODUCT, null, 1, this.classifierResolver);
		assertEquals(this.productRef, this.tested.getEntityReferenceByUniqueValue("A", null, this.classifierResolver).orElse(null));
		assertEquals(this.productRef, this.tested.getEntityReferenceByUniqueValue("B", null, this.classifierResolver).orElse(null));
		assertEquals(this.productRef, this.tested.getEntityReferenceByUniqueValue("C", null, this.classifierResolver).orElse(null));

		this.tested.unregisterUniqueKey(new String[]{"B", "C"}, Entities.PRODUCT, null, 1, this.classifierResolver);
		assertEquals(this.productRef, this.tested.getEntityReferenceByUniqueValue("A", null, this.classifierResolver).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("B", null, this.classifierResolver).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("C", null, this.classifierResolver).orElse(null));
	}

	@Test
	void shouldRegisterAndPartialUnregisterLocalizedValues() {
		this.tested.registerUniqueKey(new String[]{"A", "B", "C"}, Entities.PRODUCT, Locale.ENGLISH, 1, this.classifierResolver);
		assertEquals(this.productRef, this.tested.getEntityReferenceByUniqueValue("A", Locale.ENGLISH, this.classifierResolver).orElse(null));
		assertEquals(this.productRef, this.tested.getEntityReferenceByUniqueValue("B", Locale.ENGLISH, this.classifierResolver).orElse(null));
		assertEquals(this.productRef, this.tested.getEntityReferenceByUniqueValue("C", Locale.ENGLISH, this.classifierResolver).orElse(null));

		this.tested.unregisterUniqueKey(new String[]{"B", "C"}, Entities.PRODUCT, Locale.ENGLISH, 1, this.classifierResolver);
		assertEquals(this.productRef, this.tested.getEntityReferenceByUniqueValue("A", Locale.ENGLISH, this.classifierResolver).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("B", Locale.ENGLISH, this.classifierResolver).orElse(null));
		assertNull(this.tested.getEntityReferenceByUniqueValue("C", Locale.ENGLISH, this.classifierResolver).orElse(null));
	}

	@Test
	void shouldRoundTripLocalizedTupleThroughPackedPayload() {
		// the (entityType, primaryKey, locale) tuple must survive the pack/unpack at the long-payload tree boundary
		this.tested.registerUniqueKey("A", Entities.PRODUCT, Locale.ENGLISH, 2, this.classifierResolver);
		assertEquals(
			this.localizedProduct2EnglishRef,
			this.tested.getEntityReferenceByUniqueValue("A", Locale.ENGLISH, this.classifierResolver).orElse(null)
		);
		// the resolved reference carries the very same entity type, primary key and locale that were registered
		final EntityReferenceWithLocale resolved = this.tested.getEntityReferenceByUniqueValue("A", Locale.ENGLISH, this.classifierResolver).orElseThrow();
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
			() -> this.tested.registerUniqueKey("A", "BIG", null, 1, this.classifierResolver)
		);
	}

	@Test
	void shouldCountRecordForAsLongAsItHoldsAnyValue() {
		final EntityTypeClassifierResolver resolver = PRODUCT_AND_CATEGORY;
		// one locale-less index: product 1 owns a value per locale, and category 1 shares product 1's primary key
		// without being the same entity
		this.tested.registerUniqueKey("en-A", Entities.PRODUCT, Locale.ENGLISH, 1, resolver);
		this.tested.registerUniqueKey("de-A", Entities.PRODUCT, Locale.GERMAN, 1, resolver);
		this.tested.registerUniqueKey("en-B", Entities.PRODUCT, Locale.ENGLISH, 2, resolver);
		this.tested.registerUniqueKey("en-C", Entities.CATEGORY, Locale.ENGLISH, 1, resolver);
		assertEquals(4, this.tested.size());
		assertEquals(3, this.tested.getRecordCount(), "two products and one category");

		this.tested.unregisterUniqueKey("en-A", Entities.PRODUCT, Locale.ENGLISH, 1, resolver);
		assertEquals(3, this.tested.getRecordCount(), "product 1 still holds `de-A` and must still be counted");

		this.tested.unregisterUniqueKey("de-A", Entities.PRODUCT, Locale.GERMAN, 1, resolver);
		assertEquals(2, this.tested.getRecordCount(), "product 1 holds nothing any more");
	}

	@Test
	void shouldLeaveValueOwnedByAnotherRecordUntouchedOnFailedUnregister() {
		this.tested.registerUniqueKey("A", Entities.PRODUCT, null, 1, this.classifierResolver);

		assertThrows(
			IllegalArgumentException.class,
			() -> this.tested.unregisterUniqueKey("A", Entities.PRODUCT, null, 2, this.classifierResolver)
		);
		assertEquals(
			this.productRef, this.tested.getEntityReferenceByUniqueValue("A", null, this.classifierResolver).orElse(null),
			"a refused unregister must not have removed the owner's value"
		);
	}

	@Test
	void shouldRejectSameValueInAnotherLocaleOfLocaleLessIndex() {
		// `uniqueGlobally` on a localized attribute keys one locale-less index, and a value occurs in it once whatever
		// the locale - `uniqueGloballyWithinLocale` gets one index per locale instead and never meets this case
		final GlobalUniqueIndex localized = new GlobalUniqueIndex(
			Scope.LIVE, new AttributeKey("localizedCode"), String.class
		, 0);
		localized.registerUniqueKey("A", Entities.PRODUCT, Locale.ENGLISH, 2, this.classifierResolver);

		assertThrows(
			UniqueValueViolationException.class,
			() -> localized.registerUniqueKey("A", Entities.PRODUCT, Locale.FRENCH, 3, this.classifierResolver),
			"another entity must not claim the value in another locale"
		);
		assertThrows(
			UniqueValueViolationException.class,
			() -> localized.registerUniqueKey("A", Entities.PRODUCT, Locale.FRENCH, 2, this.classifierResolver),
			"the owning entity must not repeat the value in another locale either"
		);
		assertThrows(
			UniqueValueViolationException.class,
			() -> localized.registerUniqueKey("A", Entities.PRODUCT, Locale.ENGLISH, 2, this.classifierResolver),
			"nor register it a second time in its own locale"
		);
		// the rejected claims left the original owner in place
		assertEquals(
			new EntityReferenceWithLocale(Entities.PRODUCT, 2, Locale.ENGLISH),
			localized.getEntityReferenceByUniqueValue("A", Locale.ENGLISH, this.classifierResolver).orElse(null)
		);
		// ... and neither re-attributed the value to the rejected locale nor recorded a second entry for it
		assertTrue(localized.getEntityReferenceByUniqueValue("A", Locale.FRENCH, this.classifierResolver).isEmpty());
		assertEquals(1, localized.size());
		assertEquals(1, localized.getRecordCount());
	}

	@Test
	void shouldNotAssignLocaleIdWhenLookingUpUnseenLocale() {
		final GlobalUniqueIndex localized = new GlobalUniqueIndex(
			Scope.LIVE, new AttributeKey("localizedCode"), String.class
		, 0);
		localized.registerUniqueKey("A", Entities.PRODUCT, Locale.ENGLISH, 1, this.classifierResolver);
		assertEquals(1, localized.getLocaleIndex().size());

		assertTrue(
			localized.getEntityReferenceByUniqueValue("A", Locale.JAPANESE, this.classifierResolver).isEmpty(),
			"a value registered for english must not resolve for a locale the index has never seen"
		);
		// a lookup is a read: it must not register the unseen locale in the (persisted) locale map
		assertEquals(Map.of(1, Locale.ENGLISH), localized.getLocaleIndex());
	}

	@Test
	void shouldNotAssignLocaleIdWhenUnregisteringUnderUnseenLocale() {
		final GlobalUniqueIndex localized = new GlobalUniqueIndex(
			Scope.LIVE, new AttributeKey("localizedCode"), String.class
		, 0);
		localized.registerUniqueKey("A", Entities.PRODUCT, Locale.ENGLISH, 1, this.classifierResolver);

		// no tuple carries a locale the index has never seen, so the ownership check refuses the removal
		assertThrows(
			IllegalArgumentException.class,
			() -> localized.unregisterUniqueKey("A", Entities.PRODUCT, Locale.JAPANESE, 1, this.classifierResolver)
		);
		assertEquals(Map.of(1, Locale.ENGLISH), localized.getLocaleIndex());
		assertEquals(
			new EntityReferenceWithLocale(Entities.PRODUCT, 1, Locale.ENGLISH),
			localized.getEntityReferenceByUniqueValue("A", Locale.ENGLISH, this.classifierResolver).orElse(null)
		);
	}

	@Test
	void shouldAssignFreshLocaleIdAfterCommitMergeInsteadOfCollidingWithExisting() {
		// an index that adopts an existing locale map must start its locale sequence PAST the highest adopted id -
		// otherwise a newly seen locale is handed an id that already belongs to another locale, overwriting it in the
		// reverse map and corrupting locale decoding of every tuple carrying the clobbered id. Commit-merge is one of
		// the two surviving constructors that adopt such a map (the other is the inline restore below).
		final GlobalUniqueIndex localized = createLocalizedIndexWithEnglishAndFrench();

		assertStateAfterCommit(
			localized,
			original -> {
				// nothing further mutated - the merge alone must carry the assigned locale ids over
			},
			(original, committed) -> {
				assertEquals(Locale.ENGLISH, committed.getLocaleIndex().get(1));
				assertEquals(Locale.FRENCH, committed.getLocaleIndex().get(2));
				assertFreshLocaleIdIsAssigned(committed);
			}
		);
	}

	@Test
	void shouldAssignFreshLocaleIdAfterInlineRestoreInsteadOfCollidingWithExisting() {
		final GlobalUniqueIndex localized = createLocalizedIndexWithEnglishAndFrench();

		// rebuild the index from its persisted inline columns, exactly as a load from disk does
		final GlobalUniqueIndex.InlineSnapshot snapshot = localized.inlineSnapshot();
		final GlobalUniqueIndex restored = new GlobalUniqueIndex(
			Scope.LIVE, localized.getAttributeKey(), localized.getType(), 0,
			snapshot.values(), snapshot.payloads(), new HashMap<>(localized.getLocaleIndex())
		);

		assertEquals(Locale.ENGLISH, restored.getLocaleIndex().get(1));
		assertEquals(Locale.FRENCH, restored.getLocaleIndex().get(2));
		assertFreshLocaleIdIsAssigned(restored);
	}

	/**
	 * Builds a localized (within-locale-unique) index holding one english and one french value, so that internal locale
	 * ids 1 and 2 are already taken.
	 *
	 * @return the prepared index
	 */
	@Nonnull
	private GlobalUniqueIndex createLocalizedIndexWithEnglishAndFrench() {
		final GlobalUniqueIndex localized = new GlobalUniqueIndex(
			Scope.LIVE, new AttributeKey("localizedCode", Locale.ENGLISH), String.class
		, 0);
		localized.registerUniqueKey("en-value", Entities.PRODUCT, Locale.ENGLISH, 1, this.classifierResolver);
		localized.registerUniqueKey("fr-value", Entities.PRODUCT, Locale.FRENCH, 2, this.classifierResolver);
		assertEquals(Locale.ENGLISH, localized.getLocaleIndex().get(1));
		assertEquals(Locale.FRENCH, localized.getLocaleIndex().get(2));
		return localized;
	}

	/**
	 * Registers a never-before-seen locale on an index that adopted an existing locale map and asserts it received an
	 * id past every adopted one, leaving the adopted mappings and the values keyed by them intact.
	 *
	 * @param index index that adopted a locale map holding ids 1 (english) and 2 (french)
	 */
	private void assertFreshLocaleIdIsAssigned(@Nonnull GlobalUniqueIndex index) {
		index.registerUniqueKey("de-value", Entities.PRODUCT, Locale.GERMAN, 3, this.classifierResolver);

		assertEquals(Locale.GERMAN, index.getLocaleIndex().get(3), "new locale must receive a fresh id");
		assertEquals(Locale.ENGLISH, index.getLocaleIndex().get(1), "existing locale id must stay untouched");
		assertEquals(3, index.getLocaleIndex().size(), "exactly three distinct locale ids must be in use");

		// a value stored under the adopted english id still decodes to english rather than the freshly registered locale
		assertEquals(
			new EntityReferenceWithLocale(Entities.PRODUCT, 1, Locale.ENGLISH),
			index.getEntityReferenceByUniqueValue("en-value", Locale.ENGLISH, this.classifierResolver).orElse(null)
		);
		// one index holds a value once whatever the locale, so the english value cannot be claimed again under the
		// new one
		assertThrows(
			UniqueValueViolationException.class,
			() -> index.registerUniqueKey("en-value", Entities.PRODUCT, Locale.GERMAN, 4, this.classifierResolver)
		);
	}


	/**
	 * Tests for {@link GlobalUniqueIndex#getRecordCount()}, the number of distinct `(entity type, primary key)` owners,
	 * read off the value tree.
	 */
	@Nested
	@DisplayName("Record count")
	class RecordCountTest {

		@Test
		@DisplayName("an empty index counts no records, also once its only value is removed again")
		void shouldCountNoRecordsInEmptyIndex() {
			final GlobalUniqueIndex index = createIndex();
			assertEquals(0, index.size());
			assertEquals(0, index.getRecordCount());

			index.registerUniqueKey("A", Entities.PRODUCT, null, 1, PRODUCT_AND_CATEGORY);
			index.unregisterUniqueKey("A", Entities.PRODUCT, null, 1, PRODUCT_AND_CATEGORY);

			assertEquals(0, index.size());
			assertEquals(0, index.getRecordCount());
		}

		@Test
		@DisplayName("an owner is counted once when the entity types of neighbouring values interleave")
		void shouldCountOwnerOnceWhenEntityTypesInterleave() {
			final GlobalUniqueIndex index = createIndex();
			// in key order the entity type switches away from products and back to them - the returning product 1
			// must be recognized as already counted rather than counted afresh on every switch of the type
			index.registerUniqueKey("a", Entities.PRODUCT, Locale.ENGLISH, 1, PRODUCT_AND_CATEGORY);
			index.registerUniqueKey("b", Entities.CATEGORY, Locale.ENGLISH, 1, PRODUCT_AND_CATEGORY);
			index.registerUniqueKey("c", Entities.PRODUCT, Locale.GERMAN, 1, PRODUCT_AND_CATEGORY);
			index.registerUniqueKey("d", Entities.CATEGORY, Locale.ENGLISH, 2, PRODUCT_AND_CATEGORY);

			assertEquals(4, index.size());
			assertEquals(3, index.getRecordCount(), "product 1, category 1 and category 2");
		}

		@Test
		@DisplayName("owners whose values span several leaves are counted once")
		void shouldCountOwnersAcrossLeafPages() {
			final EntityTypeClassifierResolver resolver = resolverOf(Map.of(Entities.PRODUCT, 0, Entities.CATEGORY, 1));
			final GlobalUniqueIndex index = createIndex();
			// value i belongs to entity type `i % 2`, and each primary key owns two values 200 keys apart, so the
			// types interleave in key order and one owner's values sit in different leaves
			for (int i = 0; i < 400; i++) {
				index.registerUniqueKey(
					String.format("key-%04d", i),
					i % 2 == 0 ? Entities.PRODUCT : Entities.CATEGORY,
					null,
					(i / 2) % 100 + 1,
					resolver
				);
			}

			assertTrue(index.isPaged(), "the values must span more than one leaf");
			assertEquals(400, index.size());
			assertEquals(200, index.getRecordCount(), "a hundred products and a hundred categories");
		}

		@Test
		@DisplayName("primary keys across the whole int range and the boundary entity type ids are counted")
		void shouldCountExtremePrimaryKeysAndEntityTypeIds() {
			final EntityTypeClassifierResolver resolver =
				resolverOf(Map.of(Entities.PRODUCT, 0, Entities.CATEGORY, 0xFFFF));
			final GlobalUniqueIndex index = createIndex();
			final int[] primaryKeys = {Integer.MIN_VALUE, -1, 0, Integer.MAX_VALUE};
			for (int primaryKey : primaryKeys) {
				index.registerUniqueKey("product-" + primaryKey, Entities.PRODUCT, null, primaryKey, resolver);
				index.registerUniqueKey("category-" + primaryKey, Entities.CATEGORY, null, primaryKey, resolver);
			}

			assertEquals(8, index.getRecordCount());
			// the packed payload unpacks every primary key with its sign, whatever the entity type id next to it
			assertArrayEquals(
				primaryKeys, UniqueIndexTestSupport.ownerRecordIds(index, Entities.PRODUCT, resolver)
			);
			assertArrayEquals(
				primaryKeys, UniqueIndexTestSupport.ownerRecordIds(index, Entities.CATEGORY, resolver)
			);
		}

		@Test
		@DisplayName("a transaction's changes are counted inside it and leave the baseline count untouched")
		void shouldCountTransactionalChangesOnlyInsideTheTransaction() {
			final GlobalUniqueIndex index = createIndexWithEntityOwningTwoValues();

			assertStateAfterCommit(
				index,
				original -> {
					original.unregisterUniqueKey("en-A", Entities.PRODUCT, Locale.ENGLISH, 1, PRODUCT_AND_CATEGORY);
					original.registerUniqueKey("en-C", Entities.PRODUCT, Locale.ENGLISH, 3, PRODUCT_AND_CATEGORY);
					// product 1 still owns `de-A`, and product 3 joins products 1 and 2
					assertEquals(3, original.getRecordCount());
				},
				(original, committed) -> {
					assertEquals(3, committed.getRecordCount());
					assertEquals(2, original.getRecordCount(), "the baseline must not see the transaction's changes");
				}
			);
		}

		@Test
		@DisplayName("a rolled back transaction leaves the count as it was")
		void shouldRestoreRecordCountOnRollback() {
			final GlobalUniqueIndex index = createIndexWithEntityOwningTwoValues();

			assertStateAfterRollback(
				index,
				original -> {
					original.unregisterUniqueKey("en-A", Entities.PRODUCT, Locale.ENGLISH, 1, PRODUCT_AND_CATEGORY);
					original.unregisterUniqueKey("de-A", Entities.PRODUCT, Locale.GERMAN, 1, PRODUCT_AND_CATEGORY);
					assertEquals(1, original.getRecordCount(), "product 1 owns nothing inside the transaction");
				},
				(original, committed) -> {
					assertNull(committed);
					assertEquals(2, original.getRecordCount());
				}
			);
		}

		/**
		 * Creates the baseline the transactional count tests start from: product 1 owns `en-A` and `de-A`, product 2
		 * owns `en-B`, so the locale-less index holds three values of two entities.
		 *
		 * @return the prepared index
		 */
		@Nonnull
		private GlobalUniqueIndex createIndexWithEntityOwningTwoValues() {
			final GlobalUniqueIndex index = createIndex();
			index.registerUniqueKey("en-A", Entities.PRODUCT, Locale.ENGLISH, 1, PRODUCT_AND_CATEGORY);
			index.registerUniqueKey("de-A", Entities.PRODUCT, Locale.GERMAN, 1, PRODUCT_AND_CATEGORY);
			index.registerUniqueKey("en-B", Entities.PRODUCT, Locale.ENGLISH, 2, PRODUCT_AND_CATEGORY);
			assertEquals(2, index.getRecordCount());
			return index;
		}
	}

	/**
	 * Tests for the rule that a value occurs in the index once, whoever claims it a second time.
	 */
	@Nested
	@DisplayName("Strict uniqueness")
	class StrictUniquenessTest {

		@Test
		@DisplayName("an entity of another type sharing the owner's primary key cannot claim the value")
		void shouldRejectValueOwnedByAnotherEntityTypeWithSamePrimaryKey() {
			final GlobalUniqueIndex index = createIndex();
			index.registerUniqueKey("A", Entities.PRODUCT, null, 1, PRODUCT_AND_CATEGORY);

			final UniqueValueViolationException exception = assertThrows(
				UniqueValueViolationException.class,
				() -> index.registerUniqueKey("A", Entities.CATEGORY, null, 1, PRODUCT_AND_CATEGORY)
			);
			// both compact entity type ids were translated back to their names for the message
			assertEquals(Entities.PRODUCT, exception.getExistingRecordType());
			assertEquals(1, exception.getExistingRecordId());
			assertEquals(Entities.CATEGORY, exception.getNewRecordType());
			assertEquals(1, exception.getNewRecordId());
		}

		@Test
		@DisplayName("an array repeating a value its own entity already owns is refused without any change")
		void shouldRejectArrayRepeatingValueTheSameEntityAlreadyOwnsWithoutMutating() {
			assertArrayWithOwnedValueIsRejectedWithoutMutating(1);
		}

		@Test
		@DisplayName("an array holding a value another entity owns is refused without any change")
		void shouldRejectArrayWithValueOfAnotherRecordWithoutMutating() {
			assertArrayWithOwnedValueIsRejectedWithoutMutating(99);
		}

		/**
		 * Lets product `ownerPrimaryKey` own `B` in english, then has product 1 claim `A`, `B` and `C` as one german
		 * array, and verifies the claim is refused before any of its elements is registered.
		 *
		 * @param ownerPrimaryKey primary key of the product owning `B` before the array claim
		 */
		private void assertArrayWithOwnedValueIsRejectedWithoutMutating(int ownerPrimaryKey) {
			final GlobalUniqueIndex index = createIndex();
			index.registerUniqueKey("B", Entities.PRODUCT, Locale.ENGLISH, ownerPrimaryKey, PRODUCT_AND_CATEGORY);

			assertThrows(
				UniqueValueViolationException.class,
				() -> index.registerUniqueKey(
					new String[]{"A", "B", "C"}, Entities.PRODUCT, Locale.GERMAN, 1, PRODUCT_AND_CATEGORY
				)
			);

			assertTrue(index.getEntityReferenceByUniqueValue("A", null, PRODUCT_AND_CATEGORY).isEmpty());
			assertTrue(index.getEntityReferenceByUniqueValue("C", null, PRODUCT_AND_CATEGORY).isEmpty());
			assertEquals(
				new EntityReferenceWithLocale(Entities.PRODUCT, ownerPrimaryKey, Locale.ENGLISH),
				index.getEntityReferenceByUniqueValue("B", null, PRODUCT_AND_CATEGORY).orElse(null)
			);
			assertEquals(1, index.size());
			assertEquals(1, index.getRecordCount());
		}
	}

	/**
	 * Tests for the ownership check that precedes every removal, so a refused unregister leaves the index as it was.
	 */
	@Nested
	@DisplayName("Ownership on unregister")
	class UnregisterOwnershipTest {

		@Test
		@DisplayName("a value is left in place when unregistered by an entity of another type")
		void shouldLeaveValueUntouchedWhenUnregisteredUnderAnotherEntityType() {
			final GlobalUniqueIndex index = createIndex();
			index.registerUniqueKey("A", Entities.PRODUCT, null, 1, PRODUCT_AND_CATEGORY);

			assertThrows(
				IllegalArgumentException.class,
				() -> index.unregisterUniqueKey("A", Entities.CATEGORY, null, 1, PRODUCT_AND_CATEGORY)
			);
			assertEquals(
				new EntityReferenceWithLocale(Entities.PRODUCT, 1, null),
				index.getEntityReferenceByUniqueValue("A", null, PRODUCT_AND_CATEGORY).orElse(null)
			);
		}

		@Test
		@DisplayName("a value is left in place when unregistered by its owner under another locale")
		void shouldLeaveValueUntouchedWhenUnregisteredUnderAnotherLocale() {
			final GlobalUniqueIndex index = createIndex();
			index.registerUniqueKey("A", Entities.PRODUCT, Locale.ENGLISH, 1, PRODUCT_AND_CATEGORY);

			// the owning tuple includes the locale, so the same entity in another locale does not own the value
			assertThrows(
				IllegalArgumentException.class,
				() -> index.unregisterUniqueKey("A", Entities.PRODUCT, Locale.GERMAN, 1, PRODUCT_AND_CATEGORY)
			);
			assertEquals(
				new EntityReferenceWithLocale(Entities.PRODUCT, 1, Locale.ENGLISH),
				index.getEntityReferenceByUniqueValue("A", Locale.ENGLISH, PRODUCT_AND_CATEGORY).orElse(null)
			);
		}

		@Test
		@DisplayName("no element of an array is unregistered when one of them belongs to another entity")
		void shouldUnregisterNoArrayElementWhenOneIsOwnedByAnotherRecord() {
			final GlobalUniqueIndex index = createIndex();
			index.registerUniqueKey(new String[]{"A", "B"}, Entities.PRODUCT, null, 1, PRODUCT_AND_CATEGORY);
			index.registerUniqueKey("C", Entities.PRODUCT, null, 2, PRODUCT_AND_CATEGORY);

			assertThrows(
				IllegalArgumentException.class,
				() -> index.unregisterUniqueKey(new String[]{"A", "C"}, Entities.PRODUCT, null, 1, PRODUCT_AND_CATEGORY)
			);
			assertEquals(
				new EntityReferenceWithLocale(Entities.PRODUCT, 1, null),
				index.getEntityReferenceByUniqueValue("A", null, PRODUCT_AND_CATEGORY).orElse(null)
			);
			assertEquals(
				new EntityReferenceWithLocale(Entities.PRODUCT, 2, null),
				index.getEntityReferenceByUniqueValue("C", null, PRODUCT_AND_CATEGORY).orElse(null)
			);
			assertEquals(3, index.size());
			assertEquals(2, index.getRecordCount());
		}

		@Test
		@DisplayName("a scalar unregister returns the released reference, an array unregister returns null")
		void shouldReturnReleasedReferenceForScalarAndNullForArray() {
			final GlobalUniqueIndex index = createIndex();
			index.registerUniqueKey("A", Entities.PRODUCT, Locale.ENGLISH, 1, PRODUCT_AND_CATEGORY);
			index.registerUniqueKey(new String[]{"B", "C"}, Entities.PRODUCT, Locale.ENGLISH, 1, PRODUCT_AND_CATEGORY);

			assertEquals(
				new EntityReferenceWithLocale(Entities.PRODUCT, 1, Locale.ENGLISH),
				index.unregisterUniqueKey("A", Entities.PRODUCT, Locale.ENGLISH, 1, PRODUCT_AND_CATEGORY)
			);
			assertNull(
				index.unregisterUniqueKey(
					new String[]{"B", "C"}, Entities.PRODUCT, Locale.ENGLISH, 1, PRODUCT_AND_CATEGORY
				)
			);
			assertTrue(index.isEmpty());
		}
	}

	/**
	 * Creates an empty index over a locale-less attribute, the shape a `uniqueGlobally` localized attribute keys.
	 *
	 * @return the fresh empty index
	 */
	@Nonnull
	private static GlobalUniqueIndex createIndex() {
		return new GlobalUniqueIndex(Scope.LIVE, new AttributeKey("code"), String.class, 0);
	}

	/**
	 * A globally-unique temporal attribute. `GlobalUniqueIndex` is created unconditionally for a `uniqueGlobally`
	 * attribute — unlike `OwnerUniqueIndex` there is no folding into the shared filter tree — so this is the shortest
	 * path from a schema to a standalone unique tree. The tree keys a temporal value by its millisecond `Instant`, so
	 * the same instant is one catalog-wide unique value whatever offset each collection writes it with.
	 */
	@Nested
	@DisplayName("Temporal unique attributes")
	class TemporalValueTest {
		private static final OffsetDateTime NOON =
			OffsetDateTime.of(2026, 5, 20, 12, 19, 26, 123_000_000, ZoneOffset.UTC);
		private static final OffsetDateTime NOON_PLUS_ONE_MILLI =
			OffsetDateTime.of(2026, 5, 20, 12, 19, 26, 124_000_000, ZoneOffset.UTC);
		/**
		 * The very same instant as {@link #NOON} written at a different offset — `OffsetDateTime.compareTo` breaks
		 * the instant tie on the local date-time, so the two are distinct unique keys that an epoch-millisecond
		 * encoding would fold into one.
		 */
		private static final OffsetDateTime NOON_AT_PLUS_TWO =
			OffsetDateTime.of(2026, 5, 20, 14, 19, 26, 123_000_000, ZoneOffset.ofHours(2));

		@Test
		@DisplayName("an OffsetDateTime value is registered and retrieved back")
		void shouldRegisterAndRetrieveAnOffsetDateTimeValue() {
			final GlobalUniqueIndex index = new GlobalUniqueIndex(
				Scope.LIVE, new AttributeKey("validFrom"), OffsetDateTime.class
			, 0);
			index.registerUniqueKey(NOON, Entities.PRODUCT, null, 1, GlobalUniqueIndexTest.this.classifierResolver);
			index.registerUniqueKey(
				NOON_PLUS_ONE_MILLI, Entities.PRODUCT, null, 2, GlobalUniqueIndexTest.this.classifierResolver
			);

			assertEquals(
				new EntityReferenceWithLocale(Entities.PRODUCT, 1, null),
				index.getEntityReferenceByUniqueValue(NOON, null, GlobalUniqueIndexTest.this.classifierResolver)
					.orElse(null)
			);
			assertEquals(
				new EntityReferenceWithLocale(Entities.PRODUCT, 2, null),
				index.getEntityReferenceByUniqueValue(
					NOON_PLUS_ONE_MILLI, null, GlobalUniqueIndexTest.this.classifierResolver
				).orElse(null)
			);
			assertNull(
				index.getEntityReferenceByUniqueValue(
					NOON.plusSeconds(1), null, GlobalUniqueIndexTest.this.classifierResolver
				).orElse(null)
			);
		}

		@Test
		@DisplayName("a duplicate OffsetDateTime value is refused")
		void shouldRefuseADuplicateOffsetDateTimeValue() {
			final GlobalUniqueIndex index = new GlobalUniqueIndex(
				Scope.LIVE, new AttributeKey("validFrom"), OffsetDateTime.class
			, 0);
			index.registerUniqueKey(NOON, Entities.PRODUCT, null, 1, GlobalUniqueIndexTest.this.classifierResolver);

			assertThrows(
				UniqueValueViolationException.class,
				() -> index.registerUniqueKey(
					NOON, Entities.PRODUCT, null, 2, GlobalUniqueIndexTest.this.classifierResolver
				)
			);
		}

		@Test
		@DisplayName("the same instant at another offset is refused for another collection and found by any offset")
		void shouldRefuseTheSameInstantAtAnotherOffset() {
			// the discriminating case: NOON and NOON_AT_PLUS_TWO are the same epoch-millisecond, yet distinct under
			// OffsetDateTime.compareTo - only an index keyed by the instant enforces uniqueGlobally across them
			assertEquals(NOON.toInstant(), NOON_AT_PLUS_TWO.toInstant());

			final GlobalUniqueIndex index = new GlobalUniqueIndex(
				Scope.LIVE, new AttributeKey("validFrom"), OffsetDateTime.class
			, 0);
			index.registerUniqueKey(NOON, Entities.PRODUCT, null, 1, PRODUCT_AND_CATEGORY);

			assertThrows(
				UniqueValueViolationException.class,
				() -> index.registerUniqueKey(NOON_AT_PLUS_TWO, Entities.CATEGORY, null, 7, PRODUCT_AND_CATEGORY)
			);
			assertEquals(1, index.size());
			assertEquals(
				new EntityReferenceWithLocale(Entities.PRODUCT, 1, null),
				index.getEntityReferenceByUniqueValue(NOON_AT_PLUS_TWO, null, PRODUCT_AND_CATEGORY).orElse(null)
			);

			// unregistering by yet another offset of the instant releases it for the other collection
			assertNotNull(
				index.unregisterUniqueKey(
					NOON.withOffsetSameInstant(ZoneOffset.ofHours(-5)), Entities.PRODUCT, null, 1, PRODUCT_AND_CATEGORY
				)
			);
			index.registerUniqueKey(NOON_AT_PLUS_TWO, Entities.CATEGORY, null, 7, PRODUCT_AND_CATEGORY);
			assertEquals(
				new EntityReferenceWithLocale(Entities.CATEGORY, 7, null),
				index.getEntityReferenceByUniqueValue(NOON, null, PRODUCT_AND_CATEGORY).orElse(null)
			);
		}

		@Test
		@DisplayName("a LocalDateTime value is refused for a second record and keyed like the filter tree keys it")
		void shouldRefuseADuplicateLocalDateTime() {
			final GlobalUniqueIndex index = new GlobalUniqueIndex(
				Scope.LIVE, new AttributeKey("validFrom"), LocalDateTime.class
			, 0);
			final LocalDateTime value = LocalDateTime.of(2026, 5, 20, 12, 19, 26, 123_000_000);
			index.registerUniqueKey(value.plusNanos(456_789), Entities.PRODUCT, null, 1, PRODUCT_AND_CATEGORY);

			assertThrows(
				UniqueValueViolationException.class,
				() -> index.registerUniqueKey(value, Entities.CATEGORY, null, 7, PRODUCT_AND_CATEGORY)
			);
			assertEquals(
				new EntityReferenceWithLocale(Entities.PRODUCT, 1, null),
				index.getEntityReferenceByUniqueValue(value, null, PRODUCT_AND_CATEGORY).orElse(null)
			);
		}

		@Test
		@DisplayName("the keys are held as compactly as Long keys")
		void shouldHoldTemporalKeysAsCompactlyAsLongKeys() {
			// a temporal key rides in the same single-`long` leaf column a `Long` key does, so two indexes holding the
			// same number of values must occupy exactly the same heap; a boxed column would charge every
			// OffsetDateTime with its own object graph
			final GlobalUniqueIndex temporal = new GlobalUniqueIndex(
				Scope.LIVE, new AttributeKey("validFrom"), OffsetDateTime.class
			, 0);
			final GlobalUniqueIndex longs = new GlobalUniqueIndex(Scope.LIVE, new AttributeKey("id"), Long.class, 0);
			for (int i = 0; i < 300; i++) {
				temporal.registerUniqueKey(NOON.plusSeconds(i), Entities.PRODUCT, null, i + 1, PRODUCT_AND_CATEGORY);
				longs.registerUniqueKey((long) i, Entities.PRODUCT, null, i + 1, PRODUCT_AND_CATEGORY);
			}

			assertEquals(longs.getHeapSizeInBytes(), temporal.getHeapSizeInBytes());
		}

		@Test
		@DisplayName("values are persisted in the declared type at UTC and found by any offset after a reload")
		void shouldPersistTheDeclaredTypeAtUtcAndReloadIt() {
			final GlobalUniqueIndex index = new GlobalUniqueIndex(
				Scope.LIVE, new AttributeKey("validFrom"), OffsetDateTime.class
			, 0);
			index.registerUniqueKey(NOON_AT_PLUS_TWO, Entities.PRODUCT, null, 1, PRODUCT_AND_CATEGORY);

			final TrappedChanges sink = new TrappedChanges();
			index.appendStorageParts(index.getAttributeKey(), sink);
			final GlobalUniqueIndexStoragePart part =
				(GlobalUniqueIndexStoragePart) sink.getTrappedChangesIterator().next();
			final Serializable[] values = Objects.requireNonNull(part.getValues());
			// the serializer reads the value back as the declared type, so the part must not carry the Instant key
			assertArrayEquals(new Serializable[]{NOON}, values);
			assertEquals(ZoneOffset.UTC, ((OffsetDateTime) values[0]).getOffset());

			final GlobalUniqueIndex reloaded = new GlobalUniqueIndex(
				Scope.LIVE, part.getAttributeKey(), part.getType(), 0, values,
				Objects.requireNonNull(part.getPayloads()), new HashMap<>(part.getLocaleIndex())
			);
			assertEquals(
				new EntityReferenceWithLocale(Entities.PRODUCT, 1, null),
				reloaded.getEntityReferenceByUniqueValue(NOON_AT_PLUS_TWO, null, PRODUCT_AND_CATEGORY).orElse(null)
			);
		}

		@Test
		@DisplayName("a persisted pair naming one instant refuses to load")
		void shouldRefuseToLoadTwoPersistedValuesOfOneInstant() {
			// only a part written before the index keyed by instant can hold such a pair
			final GlobalUniqueIndex source = new GlobalUniqueIndex(
				Scope.LIVE, new AttributeKey("validFrom"), Long.class
			, 0);
			source.registerUniqueKey(1L, Entities.PRODUCT, null, 1, PRODUCT_AND_CATEGORY);
			source.registerUniqueKey(2L, Entities.CATEGORY, null, 7, PRODUCT_AND_CATEGORY);
			final long[] payloads = source.inlineSnapshot().payloads();

			assertThrows(
				GenericEvitaInternalError.class,
				() -> new GlobalUniqueIndex(
					Scope.LIVE, new AttributeKey("validFrom"), OffsetDateTime.class, 0,
					new Serializable[]{NOON, NOON_AT_PLUS_TWO}, payloads, new HashMap<>()
				)
			);
		}
	}

}
