/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.api.requestResponse.schema.mutation.reference;

import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ScopedReferenceIndexType} verifying construction, validation,
 * equality, and the empty array constant.
 *
 * @author evitaDB
 */
@DisplayName("ScopedReferenceIndexType")
class ScopedReferenceIndexTypeTest {

	@Nested
	@DisplayName("Construction and validation")
	class Construction {

		@Test
		@DisplayName("should create instance with valid scope and index type")
		void shouldCreateWithValidScopeAndIndexType() {
			final ScopedReferenceIndexType instance =
				new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING);

			assertEquals(Scope.LIVE, instance.scope());
			assertEquals(ReferenceIndexType.FOR_FILTERING, instance.indexType());
		}

		@Test
		@DisplayName("should throw when scope is null")
		void shouldThrowWhenScopeIsNull() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new ScopedReferenceIndexType(null, ReferenceIndexType.FOR_FILTERING)
			);
		}

		@Test
		@DisplayName("should throw when index type is null")
		void shouldThrowWhenIndexTypeIsNull() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new ScopedReferenceIndexType(Scope.LIVE, null)
			);
		}
	}

	@Nested
	@DisplayName("Empty array constant")
	class Constants {

		@Test
		@DisplayName("should have empty array constant")
		void shouldHaveEmptyArrayConstant() {
			assertArrayEquals(new ScopedReferenceIndexType[0], ScopedReferenceIndexType.EMPTY);
			assertEquals(0, ScopedReferenceIndexType.EMPTY.length);
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class Equality {

		@Test
		@DisplayName("should be equal for same scope and index type")
		void shouldBeEqualForSameValues() {
			final ScopedReferenceIndexType first =
				new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING);
			final ScopedReferenceIndexType second =
				new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING);

			assertEquals(first, second);
			assertEquals(first.hashCode(), second.hashCode());
		}

		@Test
		@DisplayName("should not be equal for different scope")
		void shouldNotBeEqualForDifferentScope() {
			final ScopedReferenceIndexType first =
				new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING);
			final ScopedReferenceIndexType second =
				new ScopedReferenceIndexType(Scope.ARCHIVED, ReferenceIndexType.FOR_FILTERING);

			assertNotEquals(first, second);
		}

		@Test
		@DisplayName("should not be equal for different index type")
		void shouldNotBeEqualForDifferentIndexType() {
			final ScopedReferenceIndexType first =
				new ScopedReferenceIndexType(
					Scope.LIVE, ReferenceIndexType.FOR_FILTERING
				);
			final ScopedReferenceIndexType second =
				new ScopedReferenceIndexType(
					Scope.LIVE, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING
				);

			assertNotEquals(first, second);
		}
	}

	@Nested
	@DisplayName("toString representation")
	class ToStringTest {

		@Test
		@DisplayName("should produce readable toString")
		void shouldProduceReadableToString() {
			final ScopedReferenceIndexType instance =
				new ScopedReferenceIndexType(Scope.LIVE, ReferenceIndexType.FOR_FILTERING);

			final String result = instance.toString();

			assertTrue(result.contains("LIVE"));
			assertTrue(result.contains("FOR_FILTERING"));
		}
	}
}
