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

package io.evitadb.api.query;

import io.evitadb.api.query.filter.AttributeEquals;
import io.evitadb.api.query.head.Collection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConstraintLeaf} - the abstract base class for terminal (leaf) constraints
 * that cannot contain child constraints. Uses concrete implementations ({@link Collection},
 * {@link AttributeEquals}) to test leaf behavior.
 *
 * @author evitaDB
 */
@DisplayName("ConstraintLeaf")
class ConstraintLeafTest {

	@Nested
	@DisplayName("Construction")
	class Construction {

		@Test
		@DisplayName("should derive default name from class name")
		void shouldDeriveDefaultName() {
			final Collection constraint = collection("product");
			assertEquals("collection", constraint.getName());
		}

		@Test
		@DisplayName("should accept serializable arguments")
		void shouldAcceptSerializableArguments() {
			final Collection constraint = collection("product");
			assertArrayEquals(new Object[]{"product"}, constraint.getArguments());
		}

		@Test
		@DisplayName("should create leaf with multiple arguments")
		void shouldCreateLeafWithMultipleArguments() {
			final AttributeEquals constraint = attributeEquals("code", "abc");
			assertEquals(2, constraint.getArguments().length);
			assertEquals("code", constraint.getArguments()[0]);
			assertEquals("abc", constraint.getArguments()[1]);
		}
	}

	@Nested
	@DisplayName("Applicability")
	class Applicability {

		@Test
		@DisplayName("should be applicable when all arguments are non-null")
		void shouldBeApplicableWithNonNullArguments() {
			final Collection constraint = collection("product");
			assertTrue(constraint.isApplicable());
		}

		@Test
		@DisplayName("should return null from factory when argument is null")
		void shouldReturnNullFromFactoryForNullArgument() {
			// factory method returns null for null value arguments
			final AttributeEquals constraint = attributeEquals("code", null);
			assertNull(constraint);
		}
	}

	@Nested
	@DisplayName("String representation")
	class StringRepresentation {

		@Test
		@DisplayName("should produce correct toString format")
		void shouldProduceCorrectToStringFormat() {
			final Collection constraint = collection("product");
			assertEquals("collection('product')", constraint.toString());
		}

		@Test
		@DisplayName("should produce correct toString for multi-arg leaf")
		void shouldProduceCorrectToStringForMultiArg() {
			final AttributeEquals constraint = attributeEquals("price", 100);
			assertEquals("attributeEquals('price',100)", constraint.toString());
		}
	}
}
