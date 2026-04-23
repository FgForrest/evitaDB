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
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AssociatedDataValueSerializablePredicate} verifying
 * fetch status checks, predicate test filtering, locale handling,
 * richer copy creation, and exception throwing for unfetched
 * associated data.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("Associated data value predicate")
class AssociatedDataValueSerializablePredicateTest {

	@Nested
	@DisplayName("Fetch status checks")
	class FetchStatusTest {

		@Test
		@DisplayName(
			"wasFetched returns true when associated data required"
		)
		void shouldReturnTrueWhenRequired() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Collections.emptySet(), true
				);

			assertTrue(predicate.wasFetched());
		}

		@Test
		@DisplayName(
			"wasFetched returns false when associated data not required"
		)
		void shouldReturnFalseWhenNotRequired() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Collections.emptySet(), false
				);

			assertFalse(predicate.wasFetched());
		}

		@Test
		@DisplayName(
			"wasFetched(locale) returns true for any locale when "
				+ "locales is empty"
		)
		void shouldReturnTrueForAnyLocaleWhenEmpty() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Collections.emptySet(), true
				);

			assertTrue(predicate.wasFetched(Locale.ENGLISH));
		}

		@Test
		@DisplayName(
			"wasFetched(locale) returns false when locales is null"
		)
		void shouldReturnFalseWhenLocalesNull() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null, null,
					Collections.emptySet(), true
				);

			assertFalse(predicate.wasFetched(Locale.ENGLISH));
		}

		@Test
		@DisplayName(
			"wasFetched(name) returns true for any name when set empty"
		)
		void shouldReturnTrueForAnyNameWhenSetEmpty() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Collections.emptySet(), true
				);

			assertTrue(predicate.wasFetched("anything"));
		}

		@Test
		@DisplayName(
			"wasFetched(name) returns false when not required"
		)
		void shouldReturnFalseForNameWhenNotRequired() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Collections.emptySet(), false
				);

			assertFalse(predicate.wasFetched("anything"));
		}

		@Test
		@DisplayName(
			"wasFetched(name, locale) returns true when both match"
		)
		void shouldReturnTrueWhenNameAndLocaleMatch() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null, Set.of(Locale.ENGLISH),
					Set.of("data"), true
				);

			assertTrue(predicate.wasFetched("data", Locale.ENGLISH));
			assertFalse(predicate.wasFetched("data", Locale.FRENCH));
			assertFalse(predicate.wasFetched("other", Locale.ENGLISH));
		}

		@Test
		@DisplayName(
			"wasFetched(AssociatedDataKey) returns true when "
				+ "associated data set is empty (all requested)"
		)
		void shouldReturnTrueForKeyWhenSetEmpty() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Collections.emptySet(), true
				);

			final AssociatedDataKey key =
				new AssociatedDataKey("data");

			// empty associatedDataSet means all data requested
			assertTrue(predicate.wasFetched(key));
		}

		@Test
		@DisplayName(
			"wasFetched(AssociatedDataKey) returns true when name "
				+ "is in set"
		)
		void shouldReturnTrueWhenKeyNameIsInSet() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Set.of("data"), true
				);

			assertTrue(
				predicate.wasFetched(new AssociatedDataKey("data"))
			);
		}

		@Test
		@DisplayName(
			"wasFetched(AssociatedDataKey) checks locale for "
				+ "localized keys"
		)
		void shouldCheckLocaleForLocalizedKey() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null, Set.of(Locale.ENGLISH),
					Set.of("data"), true
				);

			assertTrue(
				predicate.wasFetched(
					new AssociatedDataKey("data", Locale.ENGLISH)
				)
			);
			assertFalse(
				predicate.wasFetched(
					new AssociatedDataKey("data", Locale.FRENCH)
				)
			);
		}
	}

	@Nested
	@DisplayName("Check fetched - exception throwing")
	class CheckFetchedTest {

		@Test
		@DisplayName(
			"checkFetched throws ContextMissingException when "
				+ "not required"
		)
		void shouldThrowWhenNotRequired() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Collections.emptySet(), false
				);

			assertThrows(
				ContextMissingException.class, predicate::checkFetched
			);
		}

		@Test
		@DisplayName("checkFetched does not throw when required")
		void shouldNotThrowWhenRequired() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Collections.emptySet(), true
				);

			predicate.checkFetched();
		}

		@Test
		@DisplayName(
			"checkFetched(key) throws when name not in set"
		)
		void shouldThrowWhenNameNotInSet() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Set.of("dataA"), true
				);

			assertThrows(
				ContextMissingException.class,
				() -> predicate.checkFetched(
					new AssociatedDataKey("dataB")
				)
			);
		}

		@Test
		@DisplayName(
			"checkFetched(key) throws when locale not available"
		)
		void shouldThrowWhenLocaleNotAvailable() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null, Set.of(Locale.ENGLISH),
					Collections.emptySet(), true
				);

			assertThrows(
				ContextMissingException.class,
				() -> predicate.checkFetched(
					new AssociatedDataKey("data", Locale.FRENCH)
				)
			);
		}

		@Test
		@DisplayName(
			"checkFetched(key) passes for global key"
		)
		void shouldPassForGlobalKey() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null, Set.of(Locale.ENGLISH),
					Collections.emptySet(), true
				);

			predicate.checkFetched(new AssociatedDataKey("data"));
		}
	}

	@Nested
	@DisplayName("Predicate test method")
	class TestMethodTest {

		@Test
		@DisplayName("returns false when not required")
		void shouldReturnFalseWhenNotRequired() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Collections.emptySet(), false
				);

			final AssociatedDataValue value = new AssociatedDataValue(
				1, new AssociatedDataKey("data"), "value", false
			);

			assertFalse(predicate.test(value));
		}

		@Test
		@DisplayName("returns false when value is dropped")
		void shouldReturnFalseWhenDropped() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Collections.emptySet(), true
				);

			final AssociatedDataValue value = new AssociatedDataValue(
				1, new AssociatedDataKey("data"), "value", true
			);

			assertFalse(predicate.test(value));
		}

		@Test
		@DisplayName("returns true for existing global data")
		void shouldReturnTrueForGlobalData() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Collections.emptySet(), true
				);

			final AssociatedDataValue value = new AssociatedDataValue(
				1, new AssociatedDataKey("data"), "value", false
			);

			assertTrue(predicate.test(value));
		}

		@Test
		@DisplayName(
			"returns true for localized data matching locale"
		)
		void shouldReturnTrueForMatchingLocale() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null, Set.of(Locale.ENGLISH),
					Collections.emptySet(), true
				);

			final AssociatedDataValue value = new AssociatedDataValue(
				1, new AssociatedDataKey("data", Locale.ENGLISH),
				"value", false
			);

			assertTrue(predicate.test(value));
		}

		@Test
		@DisplayName(
			"returns false for localized data not matching locale"
		)
		void shouldReturnFalseForNonMatchingLocale() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null, Set.of(Locale.ENGLISH),
					Collections.emptySet(), true
				);

			final AssociatedDataValue value = new AssociatedDataValue(
				1, new AssociatedDataKey("data", Locale.FRENCH),
				"value", false
			);

			assertFalse(predicate.test(value));
		}

		@Test
		@DisplayName(
			"returns true for localized data matching implicit locale"
		)
		void shouldReturnTrueForImplicitLocaleMatch() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					Locale.FRENCH, null, Set.of(Locale.ENGLISH),
					Collections.emptySet(), true
				);

			final AssociatedDataValue value = new AssociatedDataValue(
				1, new AssociatedDataKey("data", Locale.FRENCH),
				"value", false
			);

			assertTrue(predicate.test(value));
		}

		@Test
		@DisplayName(
			"returns false when name not in restricted set"
		)
		void shouldReturnFalseWhenNameNotInSet() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Set.of("dataA"), true
				);

			final AssociatedDataValue value = new AssociatedDataValue(
				1, new AssociatedDataKey("dataB"), "value", false
			);

			assertFalse(predicate.test(value));
		}
	}

	@Nested
	@DisplayName("Locale utility methods")
	class LocaleUtilityTest {

		@Test
		@DisplayName(
			"getRequestedLocale returns locale when set"
		)
		void shouldReturnLocaleWhenSet() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, Locale.ENGLISH, Collections.emptySet(),
					Collections.emptySet(), true
				);

			assertSame(
				Locale.ENGLISH,
				predicate.getRequestedLocale()
			);
		}

		@Test
		@DisplayName(
			"getRequestedLocale returns null when no "
				+ "locale available"
		)
		void shouldReturnNullWhenNoLocaleAvailable() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null,
					Set.of(Locale.ENGLISH, Locale.FRENCH),
					Collections.emptySet(), true
				);

			assertNull(predicate.getRequestedLocale());
		}

		@Test
		@DisplayName("isLocaleSet returns true when locale set")
		void shouldReturnTrueWhenLocaleSet() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, Locale.ENGLISH, null,
					Collections.emptySet(), true
				);

			assertTrue(predicate.isLocaleSet());
		}

		@Test
		@DisplayName(
			"isLocaleSet returns false when all locale fields null"
		)
		void shouldReturnFalseWhenAllNull() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null, null,
					Collections.emptySet(), true
				);

			assertFalse(predicate.isLocaleSet());
		}
	}

	@Nested
	@DisplayName("Richer copy creation")
	class RicherCopyTest {

		@Test
		@DisplayName(
			"creates richer copy when request requires associated data"
		)
		void shouldCreateRicherCopyForNoAssociatedData() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Collections.emptySet(), false
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityAssociatedData())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(Collections.emptySet());
			Mockito.when(evitaRequest.getEntityAssociatedDataSet())
				.thenReturn(Collections.emptySet());

			assertNotSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"returns same when neither requires associated data"
		)
		void shouldNotCreateRicherCopyForNoAssociatedData() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Collections.emptySet(), false
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityAssociatedData())
				.thenReturn(false);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(Collections.emptySet());
			Mockito.when(evitaRequest.getEntityAssociatedDataSet())
				.thenReturn(Collections.emptySet());

			assertSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"returns same when predicate already has associated data"
		)
		void shouldNotCreateRicherCopyWhenAlreadyPresent() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Collections.emptySet(), true
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityAssociatedData())
				.thenReturn(false);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(Collections.emptySet());
			Mockito.when(evitaRequest.getEntityAssociatedDataSet())
				.thenReturn(Collections.emptySet());

			assertSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"creates richer copy when request adds new locales"
		)
		void shouldCreateRicherCopyForGlobalAssociatedData() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Collections.emptySet(), true
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityAssociatedData())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(
					new HashSet<>(
						Collections.singletonList(Locale.ENGLISH)
					)
				);
			Mockito.when(evitaRequest.getEntityAssociatedDataSet())
				.thenReturn(Collections.emptySet());

			assertNotSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"returns same when nothing changed"
		)
		void shouldNotCreateRicherCopyForGlobalAssociatedData() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Collections.emptySet(), true
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityAssociatedData())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(Collections.emptySet());
			Mockito.when(evitaRequest.getEntityAssociatedDataSet())
				.thenReturn(Collections.emptySet());

			assertSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"creates richer copy when request adds additional locales"
		)
		void shouldCreateRicherCopyForLocalizedAssociatedData() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null,
					new HashSet<>(
						Collections.singletonList(Locale.ENGLISH)
					),
					Collections.emptySet(), true
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityAssociatedData())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(
					new HashSet<>(
						Arrays.asList(Locale.ENGLISH, Locale.CANADA)
					)
				);
			Mockito.when(evitaRequest.getEntityAssociatedDataSet())
				.thenReturn(Collections.emptySet());

			assertNotSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"returns same when locales match exactly"
		)
		void shouldNotCreateRicherCopyForLocalizedAssociatedData() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null,
					new HashSet<>(
						Arrays.asList(Locale.ENGLISH, Locale.CANADA)
					),
					Collections.emptySet(), true
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityAssociatedData())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(
					new HashSet<>(
						Arrays.asList(Locale.ENGLISH, Locale.CANADA)
					)
				);
			Mockito.when(evitaRequest.getEntityAssociatedDataSet())
				.thenReturn(Collections.emptySet());

			assertSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"returns same when request locales are subset"
		)
		void shouldNotCreateRicherCopyForLocalizedSubset() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null,
					new HashSet<>(
						Arrays.asList(Locale.ENGLISH, Locale.CANADA)
					),
					Collections.emptySet(), true
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityAssociatedData())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(
					new HashSet<>(
						Collections.singletonList(Locale.ENGLISH)
					)
				);
			Mockito.when(evitaRequest.getEntityAssociatedDataSet())
				.thenReturn(Collections.emptySet());

			assertSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"creates richer copy when locales and names expand"
		)
		void shouldCreateRicherCopyForLocalizedByName() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null,
					new HashSet<>(
						Collections.singletonList(Locale.ENGLISH)
					),
					new HashSet<>(Collections.singletonList("A")),
					true
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityAssociatedData())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(
					new HashSet<>(
						Arrays.asList(Locale.ENGLISH, Locale.CANADA)
					)
				);
			Mockito.when(evitaRequest.getEntityAssociatedDataSet())
				.thenReturn(
					new HashSet<>(Collections.singletonList("A"))
				);

			assertNotSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"returns same when locales and names match exactly"
		)
		void shouldNotCreateRicherCopyForLocalizedByName() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null,
					new HashSet<>(
						Arrays.asList(Locale.ENGLISH, Locale.CANADA)
					),
					new HashSet<>(Arrays.asList("A", "B")),
					true
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityAssociatedData())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(
					new HashSet<>(
						Arrays.asList(Locale.ENGLISH, Locale.CANADA)
					)
				);
			Mockito.when(evitaRequest.getEntityAssociatedDataSet())
				.thenReturn(new HashSet<>(Arrays.asList("A", "B")));

			assertSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"returns same when request is subset by name"
		)
		void shouldNotCreateRicherCopyForSubsetByName() {
			final AssociatedDataValueSerializablePredicate predicate =
				new AssociatedDataValueSerializablePredicate(
					null, null,
					new HashSet<>(
						Arrays.asList(Locale.ENGLISH, Locale.CANADA)
					),
					new HashSet<>(Arrays.asList("A", "B")),
					true
				);

			final EvitaRequest evitaRequest =
				Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityAssociatedData())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(
					new HashSet<>(
						Collections.singletonList(Locale.ENGLISH)
					)
				);
			Mockito.when(evitaRequest.getEntityAssociatedDataSet())
				.thenReturn(
					new HashSet<>(Collections.singletonList("B"))
				);

			assertSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}
	}
}
