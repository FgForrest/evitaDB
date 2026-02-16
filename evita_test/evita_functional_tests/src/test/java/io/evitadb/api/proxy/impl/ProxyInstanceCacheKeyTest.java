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

package io.evitadb.api.proxy.impl;

import io.evitadb.api.proxy.impl.AbstractEntityProxyState.ParentProxyCacheKey;
import io.evitadb.api.proxy.impl.AbstractEntityProxyState.ReferenceProxyCacheKey;
import io.evitadb.api.proxy.impl.AbstractEntityProxyState.ReferencedEntityProxyCacheKey;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for the various {@link ProxyInstanceCacheKey} implementations used
 * to deduplicate generated proxy instances within proxy state.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ProxyInstanceCacheKey implementations")
class ProxyInstanceCacheKeyTest {

	@Nested
	@DisplayName("ReferencedEntityProxyCacheKey")
	class ReferencedEntityProxyCacheKeyTests {

		@Test
		@DisplayName("should be equal for same fields")
		void shouldBeEqualForSameFields() {
			final ReferencedEntityProxyCacheKey key1 =
				new ReferencedEntityProxyCacheKey(
					"brand", 42, ReferencedObjectType.TARGET
				);
			final ReferencedEntityProxyCacheKey key2 =
				new ReferencedEntityProxyCacheKey(
					"brand", 42, ReferencedObjectType.TARGET
				);
			assertEquals(key1, key2);
			assertEquals(key1.hashCode(), key2.hashCode());
		}

		@Test
		@DisplayName("should not be equal when reference name differs")
		void shouldNotBeEqualWhenReferenceNameDiffers() {
			final ReferencedEntityProxyCacheKey key1 =
				new ReferencedEntityProxyCacheKey(
					"brand", 42, ReferencedObjectType.TARGET
				);
			final ReferencedEntityProxyCacheKey key2 =
				new ReferencedEntityProxyCacheKey(
					"category", 42, ReferencedObjectType.TARGET
				);
			assertNotEquals(key1, key2);
		}

		@Test
		@DisplayName("should not be equal when primary key differs")
		void shouldNotBeEqualWhenPrimaryKeyDiffers() {
			final ReferencedEntityProxyCacheKey key1 =
				new ReferencedEntityProxyCacheKey(
					"brand", 42, ReferencedObjectType.TARGET
				);
			final ReferencedEntityProxyCacheKey key2 =
				new ReferencedEntityProxyCacheKey(
					"brand", 99, ReferencedObjectType.TARGET
				);
			assertNotEquals(key1, key2);
		}

		@Test
		@DisplayName("should not be equal when type differs")
		void shouldNotBeEqualWhenTypeDiffers() {
			final ReferencedEntityProxyCacheKey key1 =
				new ReferencedEntityProxyCacheKey(
					"brand", 42, ReferencedObjectType.TARGET
				);
			final ReferencedEntityProxyCacheKey key2 =
				new ReferencedEntityProxyCacheKey(
					"brand", 42, ReferencedObjectType.GROUP
				);
			assertNotEquals(key1, key2);
		}

		@Test
		@DisplayName("should handle MIN_VALUE as primary key")
		void shouldHandleMinValueAsPrimaryKey() {
			final ReferencedEntityProxyCacheKey key =
				new ReferencedEntityProxyCacheKey(
					"brand", Integer.MIN_VALUE, ReferencedObjectType.TARGET
				);
			assertEquals(Integer.MIN_VALUE, key.entityPrimaryKey());
		}

		@Test
		@DisplayName("should implement ProxyInstanceCacheKey")
		void shouldImplementProxyInstanceCacheKey() {
			final ReferencedEntityProxyCacheKey key =
				new ReferencedEntityProxyCacheKey(
					"brand", 1, ReferencedObjectType.TARGET
				);
			// just verify it's an instanceof
			assertEquals(true, key instanceof ProxyInstanceCacheKey);
		}
	}

	@Nested
	@DisplayName("ParentProxyCacheKey")
	class ParentProxyCacheKeyTests {

		@Test
		@DisplayName("should be equal for same primary key")
		void shouldBeEqualForSamePrimaryKey() {
			final ParentProxyCacheKey key1 = new ParentProxyCacheKey(42);
			final ParentProxyCacheKey key2 = new ParentProxyCacheKey(42);
			assertEquals(key1, key2);
			assertEquals(key1.hashCode(), key2.hashCode());
		}

		@Test
		@DisplayName("should not be equal for different primary keys")
		void shouldNotBeEqualForDifferentPrimaryKeys() {
			final ParentProxyCacheKey key1 = new ParentProxyCacheKey(42);
			final ParentProxyCacheKey key2 = new ParentProxyCacheKey(99);
			assertNotEquals(key1, key2);
		}

		@Test
		@DisplayName("should handle MIN_VALUE for unknown primary key")
		void shouldHandleMinValueForUnknownPrimaryKey() {
			final ParentProxyCacheKey key =
				new ParentProxyCacheKey(Integer.MIN_VALUE);
			assertEquals(Integer.MIN_VALUE, key.entityPrimaryKey());
		}

		@Test
		@DisplayName("should be reflexively equal")
		void shouldBeReflexivelyEqual() {
			final ParentProxyCacheKey key = new ParentProxyCacheKey(1);
			assertEquals(key, key);
		}
	}

	@Nested
	@DisplayName("ReferenceProxyCacheKey")
	class ReferenceProxyCacheKeyTests {

		@Test
		@DisplayName("should be equal for same fields")
		void shouldBeEqualForSameFields() {
			final ReferenceProxyCacheKey key1 =
				new ReferenceProxyCacheKey("brand", 42, 1);
			final ReferenceProxyCacheKey key2 =
				new ReferenceProxyCacheKey("brand", 42, 1);
			assertEquals(key1, key2);
			assertEquals(key1.hashCode(), key2.hashCode());
		}

		@Test
		@DisplayName("should not be equal when reference name differs")
		void shouldNotBeEqualWhenReferenceNameDiffers() {
			final ReferenceProxyCacheKey key1 =
				new ReferenceProxyCacheKey("brand", 42, 1);
			final ReferenceProxyCacheKey key2 =
				new ReferenceProxyCacheKey("category", 42, 1);
			assertNotEquals(key1, key2);
		}

		@Test
		@DisplayName("should not be equal when primary key differs")
		void shouldNotBeEqualWhenPrimaryKeyDiffers() {
			final ReferenceProxyCacheKey key1 =
				new ReferenceProxyCacheKey("brand", 42, 1);
			final ReferenceProxyCacheKey key2 =
				new ReferenceProxyCacheKey("brand", 99, 1);
			assertNotEquals(key1, key2);
		}

		@Test
		@DisplayName("should not be equal when internal pk differs")
		void shouldNotBeEqualWhenInternalPkDiffers() {
			final ReferenceProxyCacheKey key1 =
				new ReferenceProxyCacheKey("brand", 42, 1);
			final ReferenceProxyCacheKey key2 =
				new ReferenceProxyCacheKey("brand", 42, 2);
			assertNotEquals(key1, key2);
		}

		@Test
		@DisplayName("should construct from ReferenceKey")
		void shouldConstructFromReferenceKey() {
			final ReferenceKey refKey = new ReferenceKey("brand", 42, 1);
			final ReferenceProxyCacheKey key =
				new ReferenceProxyCacheKey(refKey);
			assertEquals("brand", key.referenceName());
			assertEquals(42, key.referencedEntityPrimaryKey());
			assertEquals(1, key.internalPrimaryKey());
		}

		@Test
		@DisplayName("should reject unknown reference key in constructor")
		void shouldRejectUnknownReferenceKeyInConstructor() {
			// A reference key with 0 internal primary key is unknown
			final ReferenceKey unknownRefKey = new ReferenceKey("brand", 42);
			assertThrows(
				Exception.class,
				() -> new ReferenceProxyCacheKey(unknownRefKey)
			);
		}
	}
}
