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

package io.evitadb.spi.store.catalog.persistence.storageParts.index;

import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AttributeKeyWithIndexType} verifying the `compareTo` method which uses
 * `ComparatorUtils.compareLocale` (non-null locale first) as the primary level, then cascades through
 * referenceName (null-first), attributeName, and indexType.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("AttributeKeyWithIndexType compareTo and isLocalized")
class AttributeKeyWithIndexTypeTest {

	private static final String ATTR_CODE = "code";
	private static final String ATTR_NAME = "name";
	private static final String REF_BRAND = "brand";
	private static final String REF_CATEGORY = "category";

	/**
	 * Creates an {@link AttributeKeyWithIndexType} with the given fields.
	 */
	@Nonnull
	private static AttributeKeyWithIndexType key(
		@Nullable String referenceName,
		@Nonnull String attributeName,
		@Nullable Locale locale,
		@Nonnull AttributeIndexType indexType
	) {
		return new AttributeKeyWithIndexType(referenceName, attributeName, locale, indexType);
	}

	@Nested
	@DisplayName("Locale comparison (first level via ComparatorUtils.compareLocale)")
	class LocaleComparisonTest {

		@Test
		@DisplayName("should sort non-null locale before null locale")
		void shouldSortNonNullLocaleBeforeNullLocale() {
			final AttributeKeyWithIndexType withLocale = key(null, ATTR_CODE, Locale.ENGLISH, AttributeIndexType.FILTER);
			final AttributeKeyWithIndexType nullLocale = key(null, ATTR_CODE, null, AttributeIndexType.FILTER);

			// non-null locale sorts first (compareLocale returns -1 for non-null vs null)
			assertTrue(withLocale.compareTo(nullLocale) < 0);
			assertTrue(nullLocale.compareTo(withLocale) > 0);
		}

		@Test
		@DisplayName("should compare different non-null locales by their string representation")
		void shouldCompareDifferentNonNullLocalesByString() {
			final AttributeKeyWithIndexType czech = key(null, ATTR_CODE, Locale.forLanguageTag("cs"), AttributeIndexType.FILTER);
			final AttributeKeyWithIndexType english = key(null, ATTR_CODE, Locale.ENGLISH, AttributeIndexType.FILTER);

			// "cs" < "en" lexicographically
			assertTrue(czech.compareTo(english) < 0);
			assertTrue(english.compareTo(czech) > 0);
		}
	}

	@Nested
	@DisplayName("Reference name comparison (second level, null-first)")
	class ReferenceNameComparisonTest {

		@Test
		@DisplayName("should sort null referenceName before non-null when locales are equal")
		void shouldSortNullReferenceNameBeforeNonNull() {
			final AttributeKeyWithIndexType nullRef = key(null, ATTR_CODE, null, AttributeIndexType.FILTER);
			final AttributeKeyWithIndexType nonNullRef = key(REF_BRAND, ATTR_CODE, null, AttributeIndexType.FILTER);

			// null referenceName sorts first (returns -1)
			assertTrue(nullRef.compareTo(nonNullRef) < 0);
			assertTrue(nonNullRef.compareTo(nullRef) > 0);
		}

		@Test
		@DisplayName("should compare different non-null reference names lexicographically")
		void shouldCompareDifferentNonNullReferenceNamesLexicographically() {
			final AttributeKeyWithIndexType brand = key(REF_BRAND, ATTR_CODE, null, AttributeIndexType.FILTER);
			final AttributeKeyWithIndexType category = key(REF_CATEGORY, ATTR_CODE, null, AttributeIndexType.FILTER);

			assertTrue(brand.compareTo(category) < 0);
			assertTrue(category.compareTo(brand) > 0);
		}
	}

	@Nested
	@DisplayName("Attribute name and index type comparison (third and fourth level)")
	class AttributeNameAndIndexTypeTest {

		@Test
		@DisplayName("should compare attribute names when locale and referenceName are equal")
		void shouldCompareAttributeNamesWhenLocaleAndReferenceNameAreEqual() {
			final AttributeKeyWithIndexType code = key(REF_BRAND, ATTR_CODE, null, AttributeIndexType.FILTER);
			final AttributeKeyWithIndexType name = key(REF_BRAND, ATTR_NAME, null, AttributeIndexType.FILTER);

			assertTrue(code.compareTo(name) < 0);
			assertTrue(name.compareTo(code) > 0);
		}

		@Test
		@DisplayName("should compare index types when all other fields are equal")
		void shouldCompareIndexTypesWhenAllOtherFieldsAreEqual() {
			// UNIQUE ordinal=0, FILTER ordinal=1
			final AttributeKeyWithIndexType unique = key(REF_BRAND, ATTR_CODE, null, AttributeIndexType.UNIQUE);
			final AttributeKeyWithIndexType filter = key(REF_BRAND, ATTR_CODE, null, AttributeIndexType.FILTER);

			assertTrue(unique.compareTo(filter) < 0);
			assertTrue(filter.compareTo(unique) > 0);
		}
	}

	@Nested
	@DisplayName("Full equality, symmetry, and isLocalized")
	class EqualityAndUtilityTest {

		@Test
		@DisplayName("should return zero for fully equal keys")
		void shouldReturnZeroForFullyEqualKeys() {
			final AttributeKeyWithIndexType a = key(REF_BRAND, ATTR_CODE, Locale.ENGLISH, AttributeIndexType.SORT);
			final AttributeKeyWithIndexType b = key(REF_BRAND, ATTR_CODE, Locale.ENGLISH, AttributeIndexType.SORT);

			assertEquals(0, a.compareTo(b));
		}

		@Test
		@DisplayName("should be anti-symmetric")
		void shouldBeAntiSymmetric() {
			final AttributeKeyWithIndexType a = key(null, ATTR_CODE, Locale.ENGLISH, AttributeIndexType.UNIQUE);
			final AttributeKeyWithIndexType b = key(REF_BRAND, ATTR_NAME, null, AttributeIndexType.SORT);

			assertEquals(-Integer.signum(b.compareTo(a)), Integer.signum(a.compareTo(b)));
		}

		@Test
		@DisplayName("should report isLocalized true when locale is present")
		void shouldReportIsLocalizedTrueWhenLocaleIsPresent() {
			final AttributeKeyWithIndexType key = key(null, ATTR_CODE, Locale.ENGLISH, AttributeIndexType.FILTER);

			assertTrue(key.isLocalized());
		}

		@Test
		@DisplayName("should report isLocalized false when locale is null")
		void shouldReportIsLocalizedFalseWhenLocaleIsNull() {
			final AttributeKeyWithIndexType key = key(null, ATTR_CODE, null, AttributeIndexType.FILTER);

			assertFalse(key.isLocalized());
		}

		@Test
		@DisplayName("should construct from AttributeIndexKey and preserve fields")
		void shouldConstructFromAttributeIndexKeyAndPreserveFields() {
			final AttributeIndexKey indexKey = new AttributeIndexKey(REF_BRAND, ATTR_CODE, Locale.ENGLISH);
			final AttributeKeyWithIndexType key = new AttributeKeyWithIndexType(indexKey, AttributeIndexType.SORT);

			assertEquals(REF_BRAND, key.getReferenceName());
			assertEquals(ATTR_CODE, key.getAttributeName());
			assertEquals(Locale.ENGLISH, key.getLocale());
			assertEquals(AttributeIndexType.SORT, key.getIndexType());
		}
	}
}
