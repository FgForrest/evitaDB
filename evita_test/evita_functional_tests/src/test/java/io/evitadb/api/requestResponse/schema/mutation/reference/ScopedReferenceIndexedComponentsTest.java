/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
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
 * Tests for {@link ScopedReferenceIndexedComponents} verifying construction, validation,
 * equality, and the empty array constant.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("ScopedReferenceIndexedComponents")
class ScopedReferenceIndexedComponentsTest {

	@Nested
	@DisplayName("Construction and validation")
	class Construction {

		@Test
		@DisplayName("should create instance with valid scope and single component")
		void shouldCreateWithValidScopeAndSingleComponent() {
			final ScopedReferenceIndexedComponents instance =
				new ScopedReferenceIndexedComponents(
					Scope.LIVE,
					new ReferenceIndexedComponents[]{ReferenceIndexedComponents.REFERENCED_ENTITY}
				);

			assertEquals(Scope.LIVE, instance.scope());
			assertArrayEquals(
				new ReferenceIndexedComponents[]{ReferenceIndexedComponents.REFERENCED_ENTITY},
				instance.indexedComponents()
			);
		}

		@Test
		@DisplayName("should create instance with multiple components")
		void shouldCreateWithMultipleComponents() {
			final ScopedReferenceIndexedComponents instance =
				new ScopedReferenceIndexedComponents(
					Scope.LIVE,
					new ReferenceIndexedComponents[]{
						ReferenceIndexedComponents.REFERENCED_ENTITY,
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					}
				);

			assertEquals(2, instance.indexedComponents().length);
		}

		@Test
		@DisplayName("should create instance with empty components array")
		void shouldCreateWithEmptyComponentsArray() {
			final ScopedReferenceIndexedComponents instance =
				new ScopedReferenceIndexedComponents(
					Scope.LIVE,
					ReferenceIndexedComponents.EMPTY
				);

			assertEquals(0, instance.indexedComponents().length);
		}

		@Test
		@DisplayName("should throw when scope is null")
		void shouldThrowWhenScopeIsNull() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new ScopedReferenceIndexedComponents(
					null,
					ReferenceIndexedComponents.DEFAULT_INDEXED_COMPONENTS
				)
			);
		}

		@Test
		@DisplayName("should throw when indexed components is null")
		void shouldThrowWhenIndexedComponentsIsNull() {
			assertThrows(
				EvitaInvalidUsageException.class,
				() -> new ScopedReferenceIndexedComponents(Scope.LIVE, null)
			);
		}
	}

	@Nested
	@DisplayName("Empty array constant")
	class Constants {

		@Test
		@DisplayName("should have empty array constant")
		void shouldHaveEmptyArrayConstant() {
			assertArrayEquals(
				ScopedReferenceIndexedComponents.EMPTY,
				ScopedReferenceIndexedComponents.EMPTY
			);
			assertEquals(0, ScopedReferenceIndexedComponents.EMPTY.length);
		}
	}

	@Nested
	@DisplayName("Equality and hashCode")
	class Equality {

		@Test
		@DisplayName("should be equal for same scope and components")
		void shouldBeEqualForSameValues() {
			final ScopedReferenceIndexedComponents first =
				new ScopedReferenceIndexedComponents(
					Scope.LIVE,
					new ReferenceIndexedComponents[]{ReferenceIndexedComponents.REFERENCED_ENTITY}
				);
			final ScopedReferenceIndexedComponents second =
				new ScopedReferenceIndexedComponents(
					Scope.LIVE,
					new ReferenceIndexedComponents[]{ReferenceIndexedComponents.REFERENCED_ENTITY}
				);

			assertEquals(first, second);
			assertEquals(first.hashCode(), second.hashCode());
		}

		@Test
		@DisplayName("should be equal for same scope and multiple components in same order")
		void shouldBeEqualForSameMultipleComponents() {
			final ScopedReferenceIndexedComponents first =
				new ScopedReferenceIndexedComponents(
					Scope.LIVE,
					new ReferenceIndexedComponents[]{
						ReferenceIndexedComponents.REFERENCED_ENTITY,
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					}
				);
			final ScopedReferenceIndexedComponents second =
				new ScopedReferenceIndexedComponents(
					Scope.LIVE,
					new ReferenceIndexedComponents[]{
						ReferenceIndexedComponents.REFERENCED_ENTITY,
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					}
				);

			assertEquals(first, second);
			assertEquals(first.hashCode(), second.hashCode());
		}

		@Test
		@DisplayName("should not be equal for different scope")
		void shouldNotBeEqualForDifferentScope() {
			final ScopedReferenceIndexedComponents first =
				new ScopedReferenceIndexedComponents(
					Scope.LIVE,
					new ReferenceIndexedComponents[]{ReferenceIndexedComponents.REFERENCED_ENTITY}
				);
			final ScopedReferenceIndexedComponents second =
				new ScopedReferenceIndexedComponents(
					Scope.ARCHIVED,
					new ReferenceIndexedComponents[]{ReferenceIndexedComponents.REFERENCED_ENTITY}
				);

			assertNotEquals(first, second);
		}

		@Test
		@DisplayName("should not be equal for different components")
		void shouldNotBeEqualForDifferentComponents() {
			final ScopedReferenceIndexedComponents first =
				new ScopedReferenceIndexedComponents(
					Scope.LIVE,
					new ReferenceIndexedComponents[]{ReferenceIndexedComponents.REFERENCED_ENTITY}
				);
			final ScopedReferenceIndexedComponents second =
				new ScopedReferenceIndexedComponents(
					Scope.LIVE,
					new ReferenceIndexedComponents[]{ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY}
				);

			assertNotEquals(first, second);
		}

		@Test
		@DisplayName("should not be equal for different component order")
		void shouldNotBeEqualForDifferentComponentOrder() {
			final ScopedReferenceIndexedComponents first =
				new ScopedReferenceIndexedComponents(
					Scope.LIVE,
					new ReferenceIndexedComponents[]{
						ReferenceIndexedComponents.REFERENCED_ENTITY,
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY
					}
				);
			final ScopedReferenceIndexedComponents second =
				new ScopedReferenceIndexedComponents(
					Scope.LIVE,
					new ReferenceIndexedComponents[]{
						ReferenceIndexedComponents.REFERENCED_GROUP_ENTITY,
						ReferenceIndexedComponents.REFERENCED_ENTITY
					}
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
			final ScopedReferenceIndexedComponents instance =
				new ScopedReferenceIndexedComponents(
					Scope.LIVE,
					new ReferenceIndexedComponents[]{ReferenceIndexedComponents.REFERENCED_ENTITY}
				);

			final String result = instance.toString();

			assertTrue(result.contains("LIVE"));
			assertTrue(result.contains("REFERENCED_ENTITY"));
		}
	}
}
