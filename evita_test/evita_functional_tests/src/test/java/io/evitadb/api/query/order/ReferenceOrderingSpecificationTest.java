/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.api.query.order;

import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.OrderConstraint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ReferenceOrderingSpecification} verifying that the marker interface extends the correct
 * supertype and is implemented by the expected concrete constraint classes.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("ReferenceOrderingSpecification interface")
class ReferenceOrderingSpecificationTest {

	@Nested
	@DisplayName("Type hierarchy")
	class TypeHierarchyTest {

		@Test
		@DisplayName("should extend Constraint<OrderConstraint>")
		void shouldExtendConstraintInterface() {
			assertTrue(Constraint.class.isAssignableFrom(ReferenceOrderingSpecification.class));
		}

		@Test
		@DisplayName("should be an interface")
		void shouldBeAnInterface() {
			assertTrue(ReferenceOrderingSpecification.class.isInterface());
		}
	}

	@Nested
	@DisplayName("Implementors")
	class ImplementorsTest {

		@Test
		@DisplayName("should be implemented by PickFirstByEntityProperty")
		void shouldBeImplementedByPickFirstByEntityProperty() {
			assertTrue(
				ReferenceOrderingSpecification.class.isAssignableFrom(PickFirstByEntityProperty.class)
			);
		}

		@Test
		@DisplayName("should be implemented by TraverseByEntityProperty")
		void shouldBeImplementedByTraverseByEntityProperty() {
			assertTrue(
				ReferenceOrderingSpecification.class.isAssignableFrom(TraverseByEntityProperty.class)
			);
		}
	}
}
