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
import io.evitadb.index.bitmap.Bitmap;
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
	);

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
	void shouldAllowSameValueAcrossLocalesForLocalizedAttribute() {
		// a localized (within-locale-unique) attribute permits the same value to coexist across different locales
		final GlobalUniqueIndex localized = new GlobalUniqueIndex(
			Scope.LIVE, new AttributeKey("localizedCode", Locale.ENGLISH), String.class
		);
		localized.registerUniqueKey("A", Entities.PRODUCT, Locale.ENGLISH, 2, this.classifierResolver);
		// registering the same value under a different locale must NOT raise a uniqueness violation
		assertDoesNotThrow(() -> localized.registerUniqueKey("A", Entities.PRODUCT, Locale.FRENCH, 3, this.classifierResolver));
		// the value resolves under the last writer's locale (the value-keyed tree overwrites like the HashMap it replaces)
		assertEquals(
			new EntityReferenceWithLocale(Entities.PRODUCT, 3, Locale.FRENCH),
			localized.getEntityReferenceByUniqueValue("A", Locale.FRENCH, this.classifierResolver).orElse(null)
		);
		// both primary keys remain visible in the per-entity-type record set
		final Bitmap productRecords = localized.getRecordIds(Entities.PRODUCT, this.classifierResolver);
		assertTrue(productRecords.contains(2));
		assertTrue(productRecords.contains(3));
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
			Scope.LIVE, localized.getAttributeKey(), localized.getType(),
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
		);
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
		// the same value under the new locale must not trip the within-locale uniqueness guard against the old locale
		assertDoesNotThrow(
			() -> index.registerUniqueKey("en-value", Entities.PRODUCT, Locale.GERMAN, 4, this.classifierResolver)
		);
	}


	/**
	 * A globally-unique temporal attribute. `GlobalUniqueIndex` is created unconditionally for a `uniqueGlobally`
	 * attribute — unlike `OwnerUniqueIndex` there is no folding into the shared filter tree — so this is the shortest
	 * path from a schema to the raw-valued unique tree, and it is where selecting the `Instant`-keyed leaf column
	 * threw a `ClassCastException`. See {@code ValueColumnFactory#forKey}.
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
			);
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
			);
			index.registerUniqueKey(NOON, Entities.PRODUCT, null, 1, GlobalUniqueIndexTest.this.classifierResolver);

			assertThrows(
				UniqueValueViolationException.class,
				() -> index.registerUniqueKey(
					NOON, Entities.PRODUCT, null, 2, GlobalUniqueIndexTest.this.classifierResolver
				)
			);
		}

		@Test
		@DisplayName("two offsets naming the same instant stay two distinct unique keys")
		void shouldKeepTwoOffsetsOfOneInstantDistinct() {
			// an index whose leaf column reduced its keys to `Instant` would raise a uniqueness violation here
			assertEquals(NOON.toInstant(), NOON_AT_PLUS_TWO.toInstant());

			final GlobalUniqueIndex index = new GlobalUniqueIndex(
				Scope.LIVE, new AttributeKey("validFrom"), OffsetDateTime.class
			);
			index.registerUniqueKey(NOON, Entities.PRODUCT, null, 1, GlobalUniqueIndexTest.this.classifierResolver);
			index.registerUniqueKey(
				NOON_AT_PLUS_TWO, Entities.PRODUCT, null, 2, GlobalUniqueIndexTest.this.classifierResolver
			);

			assertEquals(
				new EntityReferenceWithLocale(Entities.PRODUCT, 1, null),
				index.getEntityReferenceByUniqueValue(NOON, null, GlobalUniqueIndexTest.this.classifierResolver)
					.orElse(null)
			);
			assertEquals(
				new EntityReferenceWithLocale(Entities.PRODUCT, 2, null),
				index.getEntityReferenceByUniqueValue(
					NOON_AT_PLUS_TWO, null, GlobalUniqueIndexTest.this.classifierResolver
				).orElse(null)
			);
		}
	}

}
