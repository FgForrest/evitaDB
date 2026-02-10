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

import io.evitadb.api.proxy.impl.ProxycianFactory.ProxyEntityCacheKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for {@link ProxyEntityCacheKey} record which serves as cache key for proxy recipe lookup.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ProxyEntityCacheKey")
class ProxyEntityCacheKeyTest {

	@Test
	@DisplayName("should be equal for same type and entity name")
	void shouldBeEqualForSameTypeAndEntityName() {
		final ProxyEntityCacheKey key1 = new ProxyEntityCacheKey(
			String.class, "product"
		);
		final ProxyEntityCacheKey key2 = new ProxyEntityCacheKey(
			String.class, "product"
		);
		assertEquals(key1, key2);
		assertEquals(key1.hashCode(), key2.hashCode());
	}

	@Test
	@DisplayName("should not be equal for different types")
	void shouldNotBeEqualForDifferentTypes() {
		final ProxyEntityCacheKey key1 = new ProxyEntityCacheKey(
			String.class, "product"
		);
		final ProxyEntityCacheKey key2 = new ProxyEntityCacheKey(
			Integer.class, "product"
		);
		assertNotEquals(key1, key2);
	}

	@Test
	@DisplayName("should not be equal for different entity names")
	void shouldNotBeEqualForDifferentEntityNames() {
		final ProxyEntityCacheKey key1 = new ProxyEntityCacheKey(
			String.class, "product"
		);
		final ProxyEntityCacheKey key2 = new ProxyEntityCacheKey(
			String.class, "category"
		);
		assertNotEquals(key1, key2);
	}

	@Test
	@DisplayName("two-arg constructor should set subType and referenceName to null")
	void twoArgConstructorShouldSetSubTypeAndReferenceNameToNull() {
		final ProxyEntityCacheKey key = new ProxyEntityCacheKey(
			String.class, "product"
		);
		assertNull(key.subType());
		assertNull(key.referenceName());
	}

	@Test
	@DisplayName("four-arg constructor should store all fields")
	void fourArgConstructorShouldStoreAllFields() {
		final ProxyEntityCacheKey key = new ProxyEntityCacheKey(
			String.class, "product", Integer.class, "brand"
		);
		assertEquals(String.class, key.type());
		assertEquals("product", key.entityName());
		assertEquals(Integer.class, key.subType());
		assertEquals("brand", key.referenceName());
	}

	@Test
	@DisplayName("should be equal for same four-arg fields")
	void shouldBeEqualForSameFourArgFields() {
		final ProxyEntityCacheKey key1 = new ProxyEntityCacheKey(
			String.class, "product", Integer.class, "brand"
		);
		final ProxyEntityCacheKey key2 = new ProxyEntityCacheKey(
			String.class, "product", Integer.class, "brand"
		);
		assertEquals(key1, key2);
		assertEquals(key1.hashCode(), key2.hashCode());
	}

	@Test
	@DisplayName("should not be equal when subType differs")
	void shouldNotBeEqualWhenSubTypeDiffers() {
		final ProxyEntityCacheKey key1 = new ProxyEntityCacheKey(
			String.class, "product", Integer.class, "brand"
		);
		final ProxyEntityCacheKey key2 = new ProxyEntityCacheKey(
			String.class, "product", Long.class, "brand"
		);
		assertNotEquals(key1, key2);
	}

	@Test
	@DisplayName("should not be equal when referenceName differs")
	void shouldNotBeEqualWhenReferenceNameDiffers() {
		final ProxyEntityCacheKey key1 = new ProxyEntityCacheKey(
			String.class, "product", Integer.class, "brand"
		);
		final ProxyEntityCacheKey key2 = new ProxyEntityCacheKey(
			String.class, "product", Integer.class, "category"
		);
		assertNotEquals(key1, key2);
	}

	@Test
	@DisplayName("should be reflexively equal")
	void shouldBeReflexivelyEqual() {
		final ProxyEntityCacheKey key = new ProxyEntityCacheKey(
			String.class, "product"
		);
		assertEquals(key, key);
	}
}
