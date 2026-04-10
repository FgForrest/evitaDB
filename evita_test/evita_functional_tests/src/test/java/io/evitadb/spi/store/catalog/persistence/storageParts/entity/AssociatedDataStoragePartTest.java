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

import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import io.evitadb.spi.store.catalog.persistence.storageParts.KeyCompressor;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.AssociatedDataStoragePart.EntityAssociatedDataKey;
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
 * Tests for {@link AssociatedDataStoragePart} verifying unique part ID computation, emptiness detection,
 * associated data replacement logic, and key comparison ordering.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@SuppressWarnings("SameParameterValue")
@DisplayName("AssociatedDataStoragePart behavioral tests")
class AssociatedDataStoragePartTest {

	private static final int ENTITY_PK = 42;
	private static final String DATA_NAME = "description";
	private static final Locale LOCALE_EN = Locale.ENGLISH;

	/**
	 * Creates a mocked {@link KeyCompressor} that recognizes the given key and returns the specified ID.
	 *
	 * @param recognizedId the ID to return from `getIdIfExists`
	 * @param assignedId   the ID to return from `getId`
	 * @return configured mock
	 */
	@Nonnull
	private static KeyCompressor mockKeyCompressorRecognizing(int recognizedId, int assignedId) {
		final KeyCompressor keyCompressor = Mockito.mock(KeyCompressor.class);
		when(keyCompressor.getIdIfExists(any())).thenReturn(OptionalInt.of(recognizedId));
		when(keyCompressor.getId(any())).thenReturn(assignedId);
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
	 * Creates an {@link AssociatedDataValue} with the given name, locale, value, and dropped flag.
	 *
	 * @param name    the associated data name
	 * @param locale  the locale (may be null)
	 * @param value   the serializable value
	 * @param dropped whether the value is dropped
	 * @return new associated data value
	 */
	@Nonnull
	private static AssociatedDataValue createAssociatedDataValue(
		@Nonnull String name,
		@Nonnull Locale locale,
		@Nonnull String value,
		boolean dropped
	) {
		return new AssociatedDataValue(1, new AssociatedDataKey(name, locale), value, dropped);
	}

	@Nested
	@DisplayName("Unique part ID computation")
	class ComputeUniquePartId {

		@Test
		@DisplayName("should return computed ID when key compressor recognizes the key")
		void shouldReturnComputedIdWhenKeyCompressorRecognizesKey() {
			final int compressedId = 7;
			final KeyCompressor keyCompressor = mockKeyCompressorRecognizing(compressedId, compressedId);
			final EntityAssociatedDataKey key = new EntityAssociatedDataKey(ENTITY_PK, DATA_NAME, LOCALE_EN);

			final OptionalLong result = AssociatedDataStoragePart.computeUniquePartId(keyCompressor, key);

			assertTrue(result.isPresent());
			assertEquals(NumberUtils.join(ENTITY_PK, compressedId), result.getAsLong());
		}

		@Test
		@DisplayName("should return empty when key compressor does not recognize the key")
		void shouldReturnEmptyWhenKeyCompressorDoesNotRecognizeKey() {
			final KeyCompressor keyCompressor = mockKeyCompressorNotRecognizing();
			final EntityAssociatedDataKey key = new EntityAssociatedDataKey(ENTITY_PK, DATA_NAME, LOCALE_EN);

			final OptionalLong result = AssociatedDataStoragePart.computeUniquePartId(keyCompressor, key);

			assertFalse(result.isPresent());
		}

		@Test
		@DisplayName("should compute and set part ID on a new container")
		void shouldComputeAndSetPartIdOnNewContainer() {
			final int assignedId = 15;
			final KeyCompressor keyCompressor = mockKeyCompressorRecognizing(assignedId, assignedId);
			final AssociatedDataStoragePart part = new AssociatedDataStoragePart(
				ENTITY_PK, new AssociatedDataKey(DATA_NAME, LOCALE_EN)
			);

			assertNull(part.getStoragePartPK());

			final long computedId = part.computeUniquePartIdAndSet(keyCompressor);

			assertEquals(NumberUtils.join(ENTITY_PK, assignedId), computedId);
			assertNotNull(part.getStoragePartPK());
			assertEquals(computedId, part.getStoragePartPK());
		}

		@Test
		@DisplayName("should throw when compute-and-set is called on a container with existing ID")
		void shouldThrowWhenComputeAndSetCalledOnContainerWithExistingId() {
			final AssociatedDataValue value = createAssociatedDataValue(DATA_NAME, LOCALE_EN, "hello", false);
			final AssociatedDataStoragePart part = new AssociatedDataStoragePart(
				99L, ENTITY_PK, value, 128
			);
			final KeyCompressor keyCompressor = mockKeyCompressorRecognizing(5, 5);

			// container already has a storagePartPK, so computeUniquePartIdAndSet should throw
			assertThrows(Exception.class, () -> part.computeUniquePartIdAndSet(keyCompressor));
		}
	}

	@Nested
	@DisplayName("Emptiness detection")
	class IsEmpty {

		@Test
		@DisplayName("should be empty when value is null")
		void shouldBeEmptyWhenValueIsNull() {
			final AssociatedDataStoragePart part = new AssociatedDataStoragePart(
				ENTITY_PK, new AssociatedDataKey(DATA_NAME, LOCALE_EN)
			);

			assertTrue(part.isEmpty());
		}

		@Test
		@DisplayName("should be empty when value is dropped")
		void shouldBeEmptyWhenValueIsDropped() {
			final AssociatedDataValue droppedValue = createAssociatedDataValue(DATA_NAME, LOCALE_EN, "old", true);
			final AssociatedDataStoragePart part = new AssociatedDataStoragePart(
				1L, ENTITY_PK, droppedValue, 64
			);

			assertTrue(part.isEmpty());
		}

		@Test
		@DisplayName("should not be empty when value exists and is not dropped")
		void shouldNotBeEmptyWhenValueExistsAndIsNotDropped() {
			final AssociatedDataValue activeValue = createAssociatedDataValue(DATA_NAME, LOCALE_EN, "active", false);
			final AssociatedDataStoragePart part = new AssociatedDataStoragePart(
				1L, ENTITY_PK, activeValue, 64
			);

			assertFalse(part.isEmpty());
		}
	}

	@Nested
	@DisplayName("Associated data replacement")
	class ReplaceAssociatedData {

		@Test
		@DisplayName("should set value and mark dirty when replacing null with non-null value")
		void shouldSetValueAndMarkDirtyWhenReplacingNullWithNonNullValue() {
			final AssociatedDataStoragePart part = new AssociatedDataStoragePart(
				ENTITY_PK, new AssociatedDataKey(DATA_NAME, LOCALE_EN)
			);
			assertNull(part.getValue());
			assertFalse(part.isDirty());

			final AssociatedDataValue newValue = createAssociatedDataValue(DATA_NAME, LOCALE_EN, "new", false);
			part.replaceAssociatedData(newValue);

			assertSame(newValue, part.getValue());
			assertTrue(part.isDirty());
		}

		@Test
		@DisplayName("should set value and mark dirty when replacing with a different value")
		void shouldSetValueAndMarkDirtyWhenReplacingWithDifferentValue() {
			final AssociatedDataValue original = createAssociatedDataValue(DATA_NAME, LOCALE_EN, "original", false);
			final AssociatedDataStoragePart part = new AssociatedDataStoragePart(
				1L, ENTITY_PK, original, 64
			);
			assertFalse(part.isDirty());

			final AssociatedDataValue replacement = createAssociatedDataValue(DATA_NAME, LOCALE_EN, "changed", false);
			part.replaceAssociatedData(replacement);

			assertSame(replacement, part.getValue());
			assertTrue(part.isDirty());
		}

		@Test
		@DisplayName("should not mark dirty when replacing with identical value")
		void shouldNotMarkDirtyWhenReplacingWithIdenticalValue() {
			final AssociatedDataValue original = createAssociatedDataValue(DATA_NAME, LOCALE_EN, "same", false);
			final AssociatedDataStoragePart part = new AssociatedDataStoragePart(
				1L, ENTITY_PK, original, 64
			);

			// create a value that is semantically identical
			final AssociatedDataValue identical = createAssociatedDataValue(DATA_NAME, LOCALE_EN, "same", false);
			part.replaceAssociatedData(identical);

			assertFalse(part.isDirty());
		}

		@Test
		@DisplayName("should not mark dirty when replacing null with null")
		void shouldNotMarkDirtyWhenReplacingNullWithNull() {
			final AssociatedDataStoragePart part = new AssociatedDataStoragePart(
				ENTITY_PK, new AssociatedDataKey(DATA_NAME, LOCALE_EN)
			);

			part.replaceAssociatedData(null);

			assertFalse(part.isDirty());
			assertNull(part.getValue());
		}
	}

	@Nested
	@DisplayName("EntityAssociatedDataKey comparison")
	class EntityAssociatedDataKeyComparison {

		@Test
		@DisplayName("should order by entity primary key first")
		void shouldOrderByEntityPrimaryKeyFirst() {
			final EntityAssociatedDataKey lower = new EntityAssociatedDataKey(1, "name", LOCALE_EN);
			final EntityAssociatedDataKey higher = new EntityAssociatedDataKey(2, "name", LOCALE_EN);

			assertTrue(lower.compareTo(higher) < 0);
			assertTrue(higher.compareTo(lower) > 0);
		}

		@Test
		@DisplayName("should order by locale when primary keys are equal")
		void shouldOrderByLocaleWhenPrimaryKeysEqual() {
			// non-null locale sorts before null locale per ComparatorUtils.compareLocale
			final EntityAssociatedDataKey nullLocale = new EntityAssociatedDataKey(1, "name", null);
			final EntityAssociatedDataKey withLocale = new EntityAssociatedDataKey(1, "name", LOCALE_EN);

			assertTrue(withLocale.compareTo(nullLocale) < 0);
			assertTrue(nullLocale.compareTo(withLocale) > 0);
		}

		@Test
		@DisplayName("should order by name when primary key and locale are equal")
		void shouldOrderByNameWhenPrimaryKeyAndLocaleEqual() {
			final EntityAssociatedDataKey alpha = new EntityAssociatedDataKey(1, "alpha", LOCALE_EN);
			final EntityAssociatedDataKey beta = new EntityAssociatedDataKey(1, "beta", LOCALE_EN);

			assertTrue(alpha.compareTo(beta) < 0);
			assertTrue(beta.compareTo(alpha) > 0);
		}

		@Test
		@DisplayName("should return zero when all fields are equal")
		void shouldReturnZeroWhenAllFieldsEqual() {
			final EntityAssociatedDataKey key1 = new EntityAssociatedDataKey(1, "name", LOCALE_EN);
			final EntityAssociatedDataKey key2 = new EntityAssociatedDataKey(1, "name", LOCALE_EN);

			assertEquals(0, key1.compareTo(key2));
		}
	}

}
