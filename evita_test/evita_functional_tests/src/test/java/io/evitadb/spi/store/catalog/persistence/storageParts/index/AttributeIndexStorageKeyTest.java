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

import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AttributeIndexStorageKey} verifying the `compareTo` method which implements a 3-level cascade:
 * entityIndexKey -> indexType ordinal -> attribute ({@link AttributeIndexKey}).
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("AttributeIndexStorageKey compareTo")
class AttributeIndexStorageKeyTest {

	private static final AttributeIndexKey ATTR_CODE = new AttributeIndexKey(null, "code", null);
	private static final AttributeIndexKey ATTR_NAME = new AttributeIndexKey(null, "name", null);

	/**
	 * Creates a {@link AttributeIndexStorageKey} with GLOBAL entity index in LIVE scope.
	 */
	@Nonnull
	private static AttributeIndexStorageKey keyWithGlobalIndex(
		@Nonnull AttributeIndexType indexType,
		@Nonnull AttributeIndexKey attribute
	) {
		return new AttributeIndexStorageKey(
			new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE, null),
			indexType,
			attribute
		);
	}

	@Nested
	@DisplayName("EntityIndexKey comparison (first level)")
	class EntityIndexKeyComparisonTest {

		@Test
		@DisplayName("should order by entity index key when they differ")
		void shouldOrderByEntityIndexKeyWhenTheyDiffer() {
			final AttributeIndexStorageKey live = new AttributeIndexStorageKey(
				new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE, null),
				AttributeIndexType.FILTER,
				ATTR_CODE
			);
			final AttributeIndexStorageKey archived = new AttributeIndexStorageKey(
				new EntityIndexKey(EntityIndexType.GLOBAL, Scope.ARCHIVED, null),
				AttributeIndexType.FILTER,
				ATTR_CODE
			);

			// LIVE scope ordinal < ARCHIVED scope ordinal
			assertTrue(live.compareTo(archived) < 0);
			assertTrue(archived.compareTo(live) > 0);
		}
	}

	@Nested
	@DisplayName("IndexType comparison (second level)")
	class IndexTypeComparisonTest {

		@Test
		@DisplayName("should order by index type ordinal when entity index keys are equal")
		void shouldOrderByIndexTypeOrdinalWhenEntityIndexKeysAreEqual() {
			// UNIQUE ordinal=0, FILTER ordinal=1, SORT ordinal=2
			final AttributeIndexStorageKey unique = keyWithGlobalIndex(AttributeIndexType.UNIQUE, ATTR_CODE);
			final AttributeIndexStorageKey filter = keyWithGlobalIndex(AttributeIndexType.FILTER, ATTR_CODE);
			final AttributeIndexStorageKey sort = keyWithGlobalIndex(AttributeIndexType.SORT, ATTR_CODE);

			assertTrue(unique.compareTo(filter) < 0);
			assertTrue(filter.compareTo(sort) < 0);
			assertTrue(unique.compareTo(sort) < 0);
		}
	}

	@Nested
	@DisplayName("Attribute comparison (third level)")
	class AttributeComparisonTest {

		@Test
		@DisplayName("should order by attribute key when entity index key and index type are equal")
		void shouldOrderByAttributeKeyWhenEntityIndexKeyAndIndexTypeAreEqual() {
			final AttributeIndexStorageKey code = keyWithGlobalIndex(AttributeIndexType.FILTER, ATTR_CODE);
			final AttributeIndexStorageKey name = keyWithGlobalIndex(AttributeIndexType.FILTER, ATTR_NAME);

			// "code" < "name" lexicographically via AttributeIndexKey.compareTo
			assertTrue(code.compareTo(name) < 0);
			assertTrue(name.compareTo(code) > 0);
		}
	}

	@Nested
	@DisplayName("Full equality")
	class EqualityTest {

		@Test
		@DisplayName("should return zero for fully equal keys")
		void shouldReturnZeroForFullyEqualKeys() {
			final AttributeIndexStorageKey a = keyWithGlobalIndex(AttributeIndexType.FILTER, ATTR_CODE);
			final AttributeIndexStorageKey b = keyWithGlobalIndex(AttributeIndexType.FILTER, ATTR_CODE);

			assertEquals(0, a.compareTo(b));
		}

		@Test
		@DisplayName("should be anti-symmetric")
		void shouldBeAntiSymmetric() {
			final AttributeIndexStorageKey a = keyWithGlobalIndex(AttributeIndexType.UNIQUE, ATTR_CODE);
			final AttributeIndexStorageKey b = keyWithGlobalIndex(AttributeIndexType.SORT, ATTR_NAME);

			assertEquals(-Integer.signum(b.compareTo(a)), Integer.signum(a.compareTo(b)));
		}
	}
}
