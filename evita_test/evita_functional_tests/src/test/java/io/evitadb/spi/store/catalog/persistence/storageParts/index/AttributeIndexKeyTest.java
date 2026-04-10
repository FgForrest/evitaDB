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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AttributeIndexKey} verifying the `compareTo` method which implements a 3-level comparison:
 * referenceName (null-first) -> attributeName -> locale (null-first).
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("AttributeIndexKey compareTo")
class AttributeIndexKeyTest {

	private static final String ATTR_CODE = "code";
	private static final String ATTR_NAME = "name";
	private static final String REF_BRAND = "brand";
	private static final String REF_CATEGORY = "category";

	/**
	 * Creates an {@link AttributeIndexKey} with the given fields.
	 */
	@Nonnull
	private static AttributeIndexKey key(
		@Nullable String referenceName,
		@Nonnull String attributeName,
		@Nullable Locale locale
	) {
		return new AttributeIndexKey(referenceName, attributeName, locale);
	}

	@Nested
	@DisplayName("Reference name comparison (first level)")
	class ReferenceNameComparisonTest {

		@Test
		@DisplayName("should sort null referenceName before non-null")
		void shouldSortNullReferenceNameBeforeNonNull() {
			final AttributeIndexKey nullRef = key(null, ATTR_CODE, null);
			final AttributeIndexKey nonNullRef = key(REF_BRAND, ATTR_CODE, null);

			assertTrue(nullRef.compareTo(nonNullRef) < 0);
			assertTrue(nonNullRef.compareTo(nullRef) > 0);
		}

		@Test
		@DisplayName("should compare different non-null reference names lexicographically")
		void shouldCompareDifferentNonNullReferenceNamesLexicographically() {
			final AttributeIndexKey brand = key(REF_BRAND, ATTR_CODE, null);
			final AttributeIndexKey category = key(REF_CATEGORY, ATTR_CODE, null);

			// "brand" < "category" lexicographically
			assertTrue(brand.compareTo(category) < 0);
			assertTrue(category.compareTo(brand) > 0);
		}

		@Test
		@DisplayName("should fall through to attributeName when both reference names are null")
		void shouldFallThroughToAttributeNameWhenBothReferenceNamesAreNull() {
			final AttributeIndexKey codeKey = key(null, ATTR_CODE, null);
			final AttributeIndexKey nameKey = key(null, ATTR_NAME, null);

			// "code" < "name" lexicographically
			assertTrue(codeKey.compareTo(nameKey) < 0);
		}
	}

	@Nested
	@DisplayName("Attribute name comparison (second level)")
	class AttributeNameComparisonTest {

		@Test
		@DisplayName("should compare attribute names when reference names are equal")
		void shouldCompareAttributeNamesWhenReferenceNamesAreEqual() {
			final AttributeIndexKey code = key(REF_BRAND, ATTR_CODE, null);
			final AttributeIndexKey name = key(REF_BRAND, ATTR_NAME, null);

			assertTrue(code.compareTo(name) < 0);
			assertTrue(name.compareTo(code) > 0);
		}
	}

	@Nested
	@DisplayName("Locale comparison (third level)")
	class LocaleComparisonTest {

		@Test
		@DisplayName("should sort null locale before non-null")
		void shouldSortNullLocaleBeforeNonNull() {
			final AttributeIndexKey nullLocale = key(REF_BRAND, ATTR_CODE, null);
			final AttributeIndexKey withLocale = key(REF_BRAND, ATTR_CODE, Locale.ENGLISH);

			assertTrue(nullLocale.compareTo(withLocale) < 0);
			assertTrue(withLocale.compareTo(nullLocale) > 0);
		}

		@Test
		@DisplayName("should compare non-null locales by language tag")
		void shouldCompareNonNullLocalesByLanguageTag() {
			final AttributeIndexKey czech = key(REF_BRAND, ATTR_CODE, Locale.forLanguageTag("cs"));
			final AttributeIndexKey english = key(REF_BRAND, ATTR_CODE, Locale.ENGLISH);

			// "cs" < "en" lexicographically
			assertTrue(czech.compareTo(english) < 0);
			assertTrue(english.compareTo(czech) > 0);
		}

		@Test
		@DisplayName("should return zero when both locales are null and all other fields equal")
		void shouldReturnZeroWhenBothLocalesAreNullAndAllFieldsEqual() {
			final AttributeIndexKey a = key(REF_BRAND, ATTR_CODE, null);
			final AttributeIndexKey b = key(REF_BRAND, ATTR_CODE, null);

			assertEquals(0, a.compareTo(b));
		}
	}

	@Nested
	@DisplayName("Full equality and symmetry")
	class EqualityAndSymmetryTest {

		@Test
		@DisplayName("should return zero for fully equal keys")
		void shouldReturnZeroForFullyEqualKeys() {
			final AttributeIndexKey a = key(REF_BRAND, ATTR_CODE, Locale.ENGLISH);
			final AttributeIndexKey b = key(REF_BRAND, ATTR_CODE, Locale.ENGLISH);

			assertEquals(0, a.compareTo(b));
		}

		@Test
		@DisplayName("should return zero for keys with all null optional fields")
		void shouldReturnZeroForKeysWithAllNullOptionalFields() {
			final AttributeIndexKey a = key(null, ATTR_CODE, null);
			final AttributeIndexKey b = key(null, ATTR_CODE, null);

			assertEquals(0, a.compareTo(b));
		}

		@Test
		@DisplayName("should be anti-symmetric")
		void shouldBeAntiSymmetric() {
			final AttributeIndexKey a = key(null, ATTR_CODE, Locale.ENGLISH);
			final AttributeIndexKey b = key(REF_BRAND, ATTR_NAME, Locale.GERMAN);

			final int ab = a.compareTo(b);
			final int ba = b.compareTo(a);

			assertEquals(-Integer.signum(ba), Integer.signum(ab));
		}
	}
}
