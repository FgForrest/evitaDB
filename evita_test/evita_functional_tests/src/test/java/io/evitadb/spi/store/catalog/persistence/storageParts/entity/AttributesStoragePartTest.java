/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.spi.store.catalog.persistence.storageParts.entity;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AttributesStoragePart.AttributesSetKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AttributesStoragePart.EntityAttributesSetKey;
import io.evitadb.utils.NumberUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.annotation.Nonnull;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AttributesStoragePart} verifying unique part ID computation, attribute find and upsert,
 * emptiness detection, and key comparison ordering.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("AttributesStoragePart behavioral tests")
class AttributesStoragePartTest {

	private static final int ENTITY_PK = 10;
	private static final Locale LOCALE_EN = Locale.ENGLISH;
	private static final Locale LOCALE_DE = Locale.GERMAN;

	/**
	 * Creates a mocked {@link KeyCompressor} that recognizes any key and returns the given ID.
	 *
	 * @param id the ID to return from both `getIdIfExists` and `getId`
	 * @return configured mock
	 */
	@Nonnull
	private static KeyCompressor mockKeyCompressorRecognizing(int id) {
		final KeyCompressor keyCompressor = Mockito.mock(KeyCompressor.class);
		when(keyCompressor.getIdIfExists(any())).thenReturn(OptionalInt.of(id));
		when(keyCompressor.getId(any())).thenReturn(id);
		return keyCompressor;
	}

	/**
	 * Creates a mocked {@link KeyCompressor} that does not recognize any key.
	 *
	 * @return configured mock that returns empty from `getIdIfExists`
	 */
	@Nonnull
	private static KeyCompressor mockKeyCompressorNotRecognizing() {
		final KeyCompressor keyCompressor = Mockito.mock(KeyCompressor.class);
		when(keyCompressor.getIdIfExists(any())).thenReturn(OptionalInt.empty());
		return keyCompressor;
	}

	/**
	 * Creates a mocked {@link AttributeSchemaContract} returning the given type and zero indexed decimal places.
	 *
	 * @param type the attribute value type
	 * @return configured mock
	 */
	@Nonnull
	private static AttributeSchemaContract mockAttributeSchema(@Nonnull Class<?> type) {
		final AttributeSchemaContract schema = Mockito.mock(AttributeSchemaContract.class);
		//noinspection rawtypes,unchecked
		when(schema.getType()).thenReturn((Class) type);
		when(schema.getIndexedDecimalPlaces()).thenReturn(0);
		return schema;
	}

	@Nested
	@DisplayName("Unique part ID computation")
	class ComputeUniquePartId {

		@Test
		@DisplayName("should return computed ID when key compressor recognizes the key")
		void shouldReturnComputedIdWhenKeyCompressorRecognizesKey() {
			final int compressedId = 5;
			final KeyCompressor keyCompressor = mockKeyCompressorRecognizing(compressedId);
			final EntityAttributesSetKey key = new EntityAttributesSetKey(ENTITY_PK, LOCALE_EN);

			final OptionalLong result = AttributesStoragePart.computeUniquePartId(keyCompressor, key);

			assertTrue(result.isPresent());
			assertEquals(NumberUtils.join(ENTITY_PK, compressedId), result.getAsLong());
		}

		@Test
		@DisplayName("should return empty when key compressor does not recognize the key")
		void shouldReturnEmptyWhenKeyCompressorDoesNotRecognizeKey() {
			final KeyCompressor keyCompressor = mockKeyCompressorNotRecognizing();
			final EntityAttributesSetKey key = new EntityAttributesSetKey(ENTITY_PK, LOCALE_EN);

			final OptionalLong result = AttributesStoragePart.computeUniquePartId(keyCompressor, key);

			assertFalse(result.isPresent());
		}

		@Test
		@DisplayName("should compute and set part ID on a new container")
		void shouldComputeAndSetPartIdOnNewContainer() {
			final int assignedId = 20;
			final KeyCompressor keyCompressor = mockKeyCompressorRecognizing(assignedId);
			final AttributesStoragePart part = new AttributesStoragePart(ENTITY_PK, LOCALE_EN);

			assertNull(part.getStoragePartPK());

			final long computedId = part.computeUniquePartIdAndSet(keyCompressor);

			assertEquals(NumberUtils.join(ENTITY_PK, assignedId), computedId);
			assertNotNull(part.getStoragePartPK());
			assertEquals(computedId, part.getStoragePartPK());
		}

		@Test
		@DisplayName("should throw when compute-and-set is called on a container with existing ID")
		void shouldThrowWhenComputeAndSetCalledOnContainerWithExistingId() {
			final AttributeValue[] attributes = new AttributeValue[]{
				new AttributeValue(new AttributeKey("name", LOCALE_EN), "value")
			};
			final AttributesStoragePart part = new AttributesStoragePart(
				99L, ENTITY_PK, LOCALE_EN, attributes, 128
			);
			final KeyCompressor keyCompressor = mockKeyCompressorRecognizing(5);

			// container already has a storagePartPK
			assertThrows(Exception.class, () -> part.computeUniquePartIdAndSet(keyCompressor));
		}
	}

	@Nested
	@DisplayName("Find attribute")
	class FindAttribute {

		@Test
		@DisplayName("should return attribute when key exists")
		void shouldReturnAttributeWhenKeyExists() {
			final AttributeKey key = new AttributeKey("name", LOCALE_EN);
			final AttributeValue[] attributes = new AttributeValue[]{
				new AttributeValue(key, "Hello")
			};
			final AttributesStoragePart part = new AttributesStoragePart(
				1L, ENTITY_PK, LOCALE_EN, attributes, 128
			);

			final AttributeValue found = part.findAttribute(key);

			assertNotNull(found);
			assertEquals("Hello", found.value());
		}

		@Test
		@DisplayName("should return null when key does not exist")
		void shouldReturnNullWhenKeyDoesNotExist() {
			final AttributesStoragePart part = new AttributesStoragePart(ENTITY_PK, LOCALE_EN);

			final AttributeValue found = part.findAttribute(new AttributeKey("nonexistent", LOCALE_EN));

			assertNull(found);
		}
	}

	@Nested
	@DisplayName("Upsert attribute")
	class UpsertAttribute {

		@Test
		@DisplayName("should insert new attribute and mark dirty")
		void shouldInsertNewAttributeAndMarkDirty() {
			final AttributesStoragePart part = new AttributesStoragePart(ENTITY_PK, LOCALE_EN);
			final AttributeKey key = new AttributeKey("name", LOCALE_EN);
			final AttributeSchemaContract schema = mockAttributeSchema(String.class);

			part.upsertAttribute(
				key, schema,
				existing -> new AttributeValue(key, "newValue")
			);

			assertTrue(part.isDirty());
			assertEquals(1, part.getAttributes().length);
			assertEquals("newValue", part.getAttributes()[0].value());
		}

		@Test
		@DisplayName("should maintain sorted order after multiple insertions")
		void shouldMaintainSortedOrderAfterMultipleInsertions() {
			final AttributesStoragePart part = new AttributesStoragePart(ENTITY_PK, LOCALE_EN);
			final AttributeSchemaContract schema = mockAttributeSchema(String.class);

			// insert "zebra" first, then "alpha"
			final AttributeKey keyZ = new AttributeKey("zebra", LOCALE_EN);
			final AttributeKey keyA = new AttributeKey("alpha", LOCALE_EN);

			part.upsertAttribute(keyZ, schema, existing -> new AttributeValue(keyZ, "z"));
			part.upsertAttribute(keyA, schema, existing -> new AttributeValue(keyA, "a"));

			assertEquals(2, part.getAttributes().length);
			// attributes should be sorted by key
			assertTrue(
				part.getAttributes()[0].key().compareTo(part.getAttributes()[1].key()) < 0
			);
		}

		@Test
		@DisplayName("should replace existing attribute when content differs")
		void shouldReplaceExistingAttributeWhenContentDiffers() {
			final AttributeKey key = new AttributeKey("name", LOCALE_EN);
			final AttributeValue[] attributes = new AttributeValue[]{
				new AttributeValue(key, "original")
			};
			final AttributesStoragePart part = new AttributesStoragePart(
				1L, ENTITY_PK, LOCALE_EN, attributes, 128
			);

			final AttributeSchemaContract schema = mockAttributeSchema(String.class);
			part.upsertAttribute(
				key, schema,
				existing -> new AttributeValue(key, "changed")
			);

			assertTrue(part.isDirty());
			assertEquals("changed", part.getAttributes()[0].value());
		}

		@Test
		@DisplayName("should not mark dirty when replacing with identical attribute")
		void shouldNotMarkDirtyWhenReplacingWithIdenticalAttribute() {
			final AttributeKey key = new AttributeKey("name", LOCALE_EN);
			final AttributeValue[] attributes = new AttributeValue[]{
				new AttributeValue(key, "same")
			};
			final AttributesStoragePart part = new AttributesStoragePart(
				1L, ENTITY_PK, LOCALE_EN, attributes, 128
			);

			final AttributeSchemaContract schema = mockAttributeSchema(String.class);
			part.upsertAttribute(
				key, schema,
				existing -> existing
			);

			assertFalse(part.isDirty());
		}

		@Test
		@DisplayName("should coerce value to schema type")
		void shouldCoerceValueToSchemaType() {
			final AttributesStoragePart part = new AttributesStoragePart(ENTITY_PK, LOCALE_EN);
			final AttributeKey key = new AttributeKey("count", LOCALE_EN);
			// schema declares Integer type but we pass a String "42"
			final AttributeSchemaContract schema = mockAttributeSchema(Integer.class);

			part.upsertAttribute(
				key, schema,
				existing -> new AttributeValue(key, "42")
			);

			assertTrue(part.isDirty());
			// the value should be coerced to Integer
			assertEquals(42, part.getAttributes()[0].value());
		}
	}

	@Nested
	@DisplayName("Emptiness detection")
	class IsEmpty {

		@Test
		@DisplayName("should be empty when no attributes exist")
		void shouldBeEmptyWhenNoAttributesExist() {
			final AttributesStoragePart part = new AttributesStoragePart(ENTITY_PK, LOCALE_EN);

			assertTrue(part.isEmpty());
		}

		@Test
		@DisplayName("should be empty when all attributes are dropped")
		void shouldBeEmptyWhenAllAttributesAreDropped() {
			final AttributeValue dropped = new AttributeValue(
				1, new AttributeKey("name", LOCALE_EN), "val", true
			);
			final AttributesStoragePart part = new AttributesStoragePart(
				1L, ENTITY_PK, LOCALE_EN, new AttributeValue[]{dropped}, 128
			);

			assertTrue(part.isEmpty());
		}

		@Test
		@DisplayName("should not be empty when at least one non-dropped attribute exists")
		void shouldNotBeEmptyWhenAtLeastOneNonDroppedAttributeExists() {
			final AttributeValue active = new AttributeValue(new AttributeKey("name", LOCALE_EN), "val");
			final AttributesStoragePart part = new AttributesStoragePart(
				1L, ENTITY_PK, LOCALE_EN, new AttributeValue[]{active}, 128
			);

			assertFalse(part.isEmpty());
		}
	}

	@Nested
	@DisplayName("EntityAttributesSetKey comparison")
	class EntityAttributesSetKeyComparison {

		@Test
		@DisplayName("should order by locale first")
		void shouldOrderByLocaleFirst() {
			// non-null locale sorts before null locale per ComparatorUtils.compareLocale
			final EntityAttributesSetKey nullLocale = new EntityAttributesSetKey(1, null);
			final EntityAttributesSetKey withLocale = new EntityAttributesSetKey(1, LOCALE_EN);

			assertTrue(withLocale.compareTo(nullLocale) < 0);
			assertTrue(nullLocale.compareTo(withLocale) > 0);
		}

		@Test
		@DisplayName("should order by entity primary key when locales are equal")
		void shouldOrderByEntityPrimaryKeyWhenLocalesEqual() {
			final EntityAttributesSetKey lower = new EntityAttributesSetKey(1, LOCALE_EN);
			final EntityAttributesSetKey higher = new EntityAttributesSetKey(2, LOCALE_EN);

			assertTrue(lower.compareTo(higher) < 0);
			assertTrue(higher.compareTo(lower) > 0);
		}

		@Test
		@DisplayName("should return zero when all fields are equal")
		void shouldReturnZeroWhenAllFieldsEqual() {
			final EntityAttributesSetKey key1 = new EntityAttributesSetKey(1, LOCALE_EN);
			final EntityAttributesSetKey key2 = new EntityAttributesSetKey(1, LOCALE_EN);

			assertEquals(0, key1.compareTo(key2));
		}
	}

	@Nested
	@DisplayName("AttributesSetKey comparison")
	class AttributesSetKeyComparison {

		@Test
		@DisplayName("should order by locale")
		void shouldOrderByLocale() {
			// non-null locale sorts before null locale per ComparatorUtils.compareLocale
			final AttributesSetKey nullLocale = new AttributesSetKey(null);
			final AttributesSetKey withLocale = new AttributesSetKey(LOCALE_EN);

			assertTrue(withLocale.compareTo(nullLocale) < 0);
			assertTrue(nullLocale.compareTo(withLocale) > 0);
		}

		@Test
		@DisplayName("should return zero when both locales are null")
		void shouldReturnZeroWhenBothLocalesAreNull() {
			final AttributesSetKey key1 = new AttributesSetKey(null);
			final AttributesSetKey key2 = new AttributesSetKey(null);

			assertEquals(0, key1.compareTo(key2));
		}

		@Test
		@DisplayName("should return zero when locales are identical")
		void shouldReturnZeroWhenLocalesAreIdentical() {
			final AttributesSetKey key1 = new AttributesSetKey(LOCALE_EN);
			final AttributesSetKey key2 = new AttributesSetKey(LOCALE_EN);

			assertEquals(0, key1.compareTo(key2));
		}
	}

}
