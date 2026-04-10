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

package io.evitadb.api.requestResponse.data.structure.predicate;

import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.requestResponse.EvitaRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link HierarchySerializablePredicate} verifying hierarchy
 * predicate behavior including fetched state checks, test filtering,
 * and richer copy creation.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Hierarchy predicate")
class HierarchySerializablePredicateTest {

	@Nested
	@DisplayName("Construction and default instances")
	class ConstructionTest {

		@Test
		@DisplayName("default instance has hierarchy required")
		void shouldHaveHierarchyRequiredInDefaultInstance() {
			final HierarchySerializablePredicate predicate =
				HierarchySerializablePredicate.DEFAULT_INSTANCE;

			assertTrue(predicate.isRequiresHierarchy());
			assertNull(predicate.getUnderlyingPredicate());
		}

		@Test
		@DisplayName("no-arg constructor does not require hierarchy")
		void shouldNotRequireHierarchyWithNoArgConstructor() {
			final HierarchySerializablePredicate predicate =
				new HierarchySerializablePredicate();

			assertFalse(predicate.isRequiresHierarchy());
			assertNull(predicate.getUnderlyingPredicate());
		}

		@Test
		@DisplayName("boolean constructor sets requiresHierarchy correctly")
		void shouldSetRequiresHierarchyFromBooleanConstructor() {
			final HierarchySerializablePredicate predicateTrue =
				new HierarchySerializablePredicate(true);
			final HierarchySerializablePredicate predicateFalse =
				new HierarchySerializablePredicate(false);

			assertTrue(predicateTrue.isRequiresHierarchy());
			assertFalse(predicateFalse.isRequiresHierarchy());
		}
	}

	@Nested
	@DisplayName("Fetch status checks")
	class FetchStatusTest {

		@Test
		@DisplayName("wasFetched returns true when hierarchy is required")
		void shouldReturnTrueWhenHierarchyWasFetched() {
			final HierarchySerializablePredicate predicate =
				new HierarchySerializablePredicate(true);

			assertTrue(predicate.wasFetched());
		}

		@Test
		@DisplayName("wasFetched returns false when hierarchy is not required")
		void shouldReturnFalseWhenHierarchyWasNotFetched() {
			final HierarchySerializablePredicate predicate =
				new HierarchySerializablePredicate(false);

			assertFalse(predicate.wasFetched());
		}

		@Test
		@DisplayName("checkFetched does not throw when hierarchy was fetched")
		void shouldNotThrowWhenHierarchyWasFetched() {
			final HierarchySerializablePredicate predicate =
				new HierarchySerializablePredicate(true);

			predicate.checkFetched();
			// no exception expected
		}

		@Test
		@DisplayName(
			"checkFetched throws ContextMissingException "
				+ "when hierarchy was not fetched"
		)
		void shouldThrowContextMissingExceptionWhenNotFetched() {
			final HierarchySerializablePredicate predicate =
				new HierarchySerializablePredicate(false);

			assertThrows(
				ContextMissingException.class,
				predicate::checkFetched
			);
		}
	}

	@Nested
	@DisplayName("Predicate test method")
	class TestMethodTest {

		@Test
		@DisplayName("returns true for any integer when hierarchy required")
		void shouldReturnTrueWhenHierarchyRequired() {
			final HierarchySerializablePredicate predicate =
				new HierarchySerializablePredicate(true);

			assertTrue(predicate.test(1));
			assertTrue(predicate.test(0));
			assertTrue(predicate.test(-1));
			assertTrue(predicate.test(Integer.MAX_VALUE));
		}

		@Test
		@DisplayName(
			"returns false for any integer when hierarchy not required"
		)
		void shouldReturnFalseWhenHierarchyNotRequired() {
			final HierarchySerializablePredicate predicate =
				new HierarchySerializablePredicate(false);

			assertFalse(predicate.test(1));
			assertFalse(predicate.test(0));
			assertFalse(predicate.test(-1));
		}
	}

	@Nested
	@DisplayName("Richer copy creation")
	class RicherCopyTest {

		@Test
		@DisplayName(
			"returns same instance when hierarchy is already required"
		)
		void shouldReturnSameWhenAlreadyRequiresHierarchy() {
			final HierarchySerializablePredicate predicate =
				new HierarchySerializablePredicate(true);

			final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresParent()).thenReturn(true);

			assertSame(
				predicate, predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"returns same instance when both have hierarchy not required"
		)
		void shouldReturnSameWhenBothDoNotRequire() {
			final HierarchySerializablePredicate predicate =
				new HierarchySerializablePredicate(false);

			final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresParent()).thenReturn(false);

			assertSame(
				predicate, predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName("creates new instance when request requires hierarchy")
		void shouldCreateNewWhenRequestRequiresHierarchy() {
			final HierarchySerializablePredicate predicate =
				new HierarchySerializablePredicate(false);

			final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresParent()).thenReturn(true);

			final HierarchySerializablePredicate richerCopy =
				predicate.createRicherCopyWith(evitaRequest);

			assertNotSame(predicate, richerCopy);
			assertTrue(richerCopy.isRequiresHierarchy());
		}

		@Test
		@DisplayName(
			"returns same when hierarchy is required even if request "
				+ "does not require it"
		)
		void shouldReturnSameWhenAlreadyRicherThanRequest() {
			final HierarchySerializablePredicate predicate =
				new HierarchySerializablePredicate(true);

			final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresParent()).thenReturn(false);

			assertSame(
				predicate, predicate.createRicherCopyWith(evitaRequest)
			);
		}
	}
}
