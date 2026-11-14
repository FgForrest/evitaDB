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

package io.evitadb.api.query;

import io.evitadb.dataType.Scope;
import io.evitadb.exception.EvitaInvalidUsageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the diagnostics and array handling of the checks every query factory applies first, which the factory-level
 * tests observe only as thrown or omitted constraints.
 */
@DisplayName("Constraint argument checks")
@Tag(CONTRACT)
@Tag(QUERY)
class ConstraintArgumentsTest {

	/**
	 * Covers names supplied by the application, which must be present.
	 */
	@Nested
	@DisplayName("Names")
	class Names {

		/** A valid name passes through unchanged, so factories can use the returned value directly. */
		@Test
		@DisplayName("Return a valid name as given")
		void shouldReturnNameWhenItIsDefined() {
			final String name = "code";

			assertSame(name, ConstraintArguments.requireName(name, "attributeName"));
		}

		/** The diagnostic names the argument, which is what tells the caller which name was missing. */
		@Test
		@DisplayName("Reject a null name and name the argument")
		void shouldRejectNameWhenItIsNull() {
			final EvitaInvalidUsageException exception = assertThrows(EvitaInvalidUsageException.class,
				() -> ConstraintArguments.requireName(null, "attributeName"));

			assertTrue(exception.getMessage().contains("`attributeName`"));
			assertTrue(exception.getMessage().contains("null"));
		}

		/**
		 * A blank name is the same application bug as a null one and must not pass as a name.
		 *
		 * @param name blank name
		 */
		@ParameterizedTest
		@ValueSource(strings = {"", " ", "\t\n"})
		@DisplayName("Reject a blank name and name the argument")
		void shouldRejectNameWhenItIsBlank(@Nonnull String name) {
			final EvitaInvalidUsageException exception = assertThrows(EvitaInvalidUsageException.class,
				() -> ConstraintArguments.requireName(name, "attributeName"));

			assertTrue(exception.getMessage().contains("`attributeName`"));
			assertTrue(exception.getMessage().contains("blank"));
		}
	}

	/**
	 * Covers lists of names and enum settings, whose items must all be present.
	 */
	@Nested
	@DisplayName("Name and enum lists")
	class Settings {

		/** A missing or empty list keeps its own meaning; only its items are checked. */
		@Test
		@DisplayName("Accept a missing and an empty list")
		void shouldAcceptListWhenItIsMissingOrEmpty() {
			assertDoesNotThrow(() -> ConstraintArguments.requireNonNullItems(null, "attributeNames"));
			assertDoesNotThrow(() -> ConstraintArguments.requireNonNullItems(new String[0], "attributeNames"));
		}

		/** A null item cannot be ignored here, because dropping a name silently changes what is fetched. */
		@Test
		@DisplayName("Reject a null item and name the argument")
		void shouldRejectItemWhenItIsNull() {
			final EvitaInvalidUsageException exception = assertThrows(EvitaInvalidUsageException.class,
				() -> ConstraintArguments.requireNonNullItems(new Scope[]{Scope.LIVE, null}, "scopes"));

			assertTrue(exception.getMessage().contains("`scopes`"));
			assertTrue(exception.getMessage().contains("null"));
		}

		/** A blank name item is rejected like a blank scalar name. */
		@Test
		@DisplayName("Reject a blank item and name the argument")
		void shouldRejectItemWhenItIsBlank() {
			final EvitaInvalidUsageException exception = assertThrows(EvitaInvalidUsageException.class,
				() -> ConstraintArguments.requireNonNullItems(new String[]{"code", " "}, "attributeNames"));

			assertTrue(exception.getMessage().contains("`attributeNames`"));
			assertTrue(exception.getMessage().contains("blank"));
		}

		/** The blank check applies to names only; enum items are never blank. */
		@Test
		@DisplayName("Accept defined enum items")
		void shouldAcceptItemsWhenTheyAreDefinedEnums() {
			assertDoesNotThrow(() -> ConstraintArguments.requireNonNullItems(
				new Scope[]{Scope.LIVE, Scope.ARCHIVED}, "scopes"));
		}
	}

	/**
	 * Covers lists of values, whose undefined items are ignored.
	 */
	@Nested
	@DisplayName("Value lists")
	class Values {

		/** A missing list stays missing, so a filter can tell "not given" from "nothing matches". */
		@Test
		@DisplayName("Keep a missing list missing")
		void shouldReturnNullWhenValuesAreMissing() {
			assertNull(ConstraintArguments.withoutNullValues((Integer[]) null));
		}

		/** Nothing is copied when no item is undefined. */
		@Test
		@DisplayName("Return the same array when no value is null")
		void shouldReturnSameArrayWhenNoValueIsNull() {
			final Integer[] values = {1, 2};

			assertSame(values, ConstraintArguments.withoutNullValues(values));
		}

		/** The copy keeps the runtime component type, which the constraint constructors rely on. */
		@Test
		@DisplayName("Drop null values and keep the array type")
		void shouldDropNullValuesAndKeepArrayType() {
			final Integer[] result = ConstraintArguments.withoutNullValues(new Integer[]{null, 1, null, 2});

			assertArrayEquals(new Integer[]{1, 2}, result);
			assertEquals(Integer[].class, result.getClass());
		}

		/** A blank price-list name is an undefined value, not a name, so it is ignored like null. */
		@Test
		@DisplayName("Drop null and blank price lists")
		void shouldDropNullAndBlankPriceLists() {
			assertArrayEquals(new String[]{"basic"},
				ConstraintArguments.withoutUndefinedPriceLists(new String[]{null, " ", "basic", ""}));
		}

		/** A missing price-list array stays missing. */
		@Test
		@DisplayName("Keep a missing price-list array missing")
		void shouldReturnNullWhenPriceListsAreMissing() {
			assertNull(ConstraintArguments.withoutUndefinedPriceLists(null));
		}
	}
}
