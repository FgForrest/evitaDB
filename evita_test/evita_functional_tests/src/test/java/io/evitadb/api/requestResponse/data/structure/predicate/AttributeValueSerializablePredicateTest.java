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
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AttributeValueSerializablePredicate} verifying fetch
 * status checks, predicate test filtering, locale handling, richer copy
 * creation, and exception throwing for unfetched attributes.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@DisplayName("Attribute value predicate")
class AttributeValueSerializablePredicateTest {

	@Nested
	@DisplayName("Fetch status checks")
	class FetchStatusTest {

		@Test
		@DisplayName("wasFetched returns true when attributes are required")
		void shouldReturnTrueWhenAttributesRequired() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Collections.emptySet(), true
				);

			assertTrue(predicate.wasFetched());
		}

		@Test
		@DisplayName("wasFetched returns false when attributes not required")
		void shouldReturnFalseWhenAttributesNotRequired() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
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
		void shouldReturnTrueForAnyLocaleWhenLocalesEmpty() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
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
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null, null,
					Collections.emptySet(), true
				);

			assertFalse(predicate.wasFetched(Locale.ENGLISH));
		}

		@Test
		@DisplayName(
			"wasFetched(name) returns true for any name when "
				+ "attribute set is empty"
		)
		void shouldReturnTrueForAnyNameWhenSetEmpty() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
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
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
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
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null, Set.of(Locale.ENGLISH),
					Set.of("attr"), true
				);

			assertTrue(predicate.wasFetched("attr", Locale.ENGLISH));
			assertFalse(predicate.wasFetched("attr", Locale.FRENCH));
			assertFalse(predicate.wasFetched("other", Locale.ENGLISH));
		}
	}

	@Nested
	@DisplayName("Check fetched - exception throwing")
	class CheckFetchedTest {

		@Test
		@DisplayName(
			"checkFetched throws ContextMissingException when "
				+ "attributes not required"
		)
		void shouldThrowWhenAttributesNotRequired() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Collections.emptySet(), false
				);

			assertThrows(
				ContextMissingException.class, predicate::checkFetched
			);
		}

		@Test
		@DisplayName(
			"checkFetched does not throw when attributes required"
		)
		void shouldNotThrowWhenAttributesRequired() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Collections.emptySet(), true
				);

			predicate.checkFetched();
		}

		@Test
		@DisplayName(
			"checkFetched(key) throws when attribute name not in set"
		)
		void shouldThrowWhenAttributeNameNotInSet() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Set.of("attrA"), true
				);

			assertThrows(
				ContextMissingException.class,
				() -> predicate.checkFetched(new AttributeKey("attrB"))
			);
		}

		@Test
		@DisplayName(
			"checkFetched(key) throws when locale not available"
		)
		void shouldThrowWhenLocaleNotAvailable() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null, Set.of(Locale.ENGLISH),
					Collections.emptySet(), true
				);

			assertThrows(
				ContextMissingException.class,
				() -> predicate.checkFetched(
					new AttributeKey("attr", Locale.FRENCH)
				)
			);
		}

		@Test
		@DisplayName(
			"checkFetched(key) passes for global key"
		)
		void shouldPassForGlobalKey() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null, Set.of(Locale.ENGLISH),
					Collections.emptySet(), true
				);

			predicate.checkFetched(new AttributeKey("attr"));
		}
	}

	@Nested
	@DisplayName("Predicate test method")
	class TestMethodTest {

		@Test
		@DisplayName("returns false when attributes not required")
		void shouldReturnFalseWhenNotRequired() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Collections.emptySet(), false
				);

			final AttributeValue value = new AttributeValue(
				1, new AttributeKey("attr"), "value", false
			);

			assertFalse(predicate.test(value));
		}

		@Test
		@DisplayName("returns false when attribute is dropped")
		void shouldReturnFalseWhenDropped() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Collections.emptySet(), true
				);

			final AttributeValue value = new AttributeValue(
				1, new AttributeKey("attr"), "value", true
			);

			assertFalse(predicate.test(value));
		}

		@Test
		@DisplayName(
			"returns true for existing global attribute"
		)
		void shouldReturnTrueForGlobalAttribute() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Collections.emptySet(), true
				);

			final AttributeValue value = new AttributeValue(
				1, new AttributeKey("attr"), "value", false
			);

			assertTrue(predicate.test(value));
		}

		@Test
		@DisplayName(
			"returns true for localized attribute matching locale"
		)
		void shouldReturnTrueForMatchingLocalizedAttribute() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null, Set.of(Locale.ENGLISH),
					Collections.emptySet(), true
				);

			final AttributeValue value = new AttributeValue(
				1, new AttributeKey("attr", Locale.ENGLISH),
				"value", false
			);

			assertTrue(predicate.test(value));
		}

		@Test
		@DisplayName(
			"returns false for localized attribute not matching locale"
		)
		void shouldReturnFalseForNonMatchingLocalizedAttribute() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null, Set.of(Locale.ENGLISH),
					Collections.emptySet(), true
				);

			final AttributeValue value = new AttributeValue(
				1, new AttributeKey("attr", Locale.FRENCH),
				"value", false
			);

			assertFalse(predicate.test(value));
		}

		@Test
		@DisplayName(
			"returns true for localized attribute matching "
				+ "implicit locale"
		)
		void shouldReturnTrueForImplicitLocaleMatch() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					Locale.FRENCH, null, Set.of(Locale.ENGLISH),
					Collections.emptySet(), true
				);

			final AttributeValue value = new AttributeValue(
				1, new AttributeKey("attr", Locale.FRENCH),
				"value", false
			);

			assertTrue(predicate.test(value));
		}

		@Test
		@DisplayName(
			"returns false when attribute name not in restricted set"
		)
		void shouldReturnFalseWhenNameNotInSet() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Set.of("attrA"), true
				);

			final AttributeValue value = new AttributeValue(
				1, new AttributeKey("attrB"), "value", false
			);

			assertFalse(predicate.test(value));
		}
	}

	@Nested
	@DisplayName("Locale utility methods")
	class LocaleUtilityTest {

		@Test
		@DisplayName(
			"getAllLocales returns implicit locale set when "
				+ "locales is null"
		)
		void shouldReturnImplicitLocaleSetWhenLocalesNull() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					Locale.ENGLISH, null, null,
					Collections.emptySet(), true
				);

			final Set<Locale> allLocales = predicate.getAllLocales();

			assertNotNull(allLocales);
			assertTrue(allLocales.contains(Locale.ENGLISH));
		}

		@Test
		@DisplayName(
			"getAllLocales merges implicit locale with locales"
		)
		void shouldMergeImplicitLocaleWithLocales() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					Locale.ENGLISH, null, Set.of(Locale.FRENCH),
					Collections.emptySet(), true
				);

			final Set<Locale> allLocales = predicate.getAllLocales();

			assertNotNull(allLocales);
			assertTrue(allLocales.contains(Locale.ENGLISH));
			assertTrue(allLocales.contains(Locale.FRENCH));
		}

		@Test
		@DisplayName(
			"getAllLocales returns locales when no implicit locale"
		)
		void shouldReturnLocalesWhenNoImplicitLocale() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null, Set.of(Locale.FRENCH),
					Collections.emptySet(), true
				);

			final Set<Locale> allLocales = predicate.getAllLocales();

			assertNotNull(allLocales);
			assertTrue(allLocales.contains(Locale.FRENCH));
			assertFalse(allLocales.contains(Locale.ENGLISH));
		}

		@Test
		@DisplayName(
			"getAllLocales returns null when both implicit locale "
				+ "and locales are null"
		)
		void shouldReturnNullWhenBothNull() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null, null,
					Collections.emptySet(), true
				);

			assertNull(predicate.getAllLocales());
		}

		@Test
		@DisplayName(
			"getRequestedLocale returns locale when set"
		)
		void shouldReturnLocaleWhenSet() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
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
			"getRequestedLocale returns implicit "
				+ "locale when locale is null"
		)
		void shouldReturnImplicitLocaleWhenLocaleNull() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					Locale.FRENCH, null, Collections.emptySet(),
					Collections.emptySet(), true
				);

			assertSame(
				Locale.FRENCH,
				predicate.getRequestedLocale()
			);
		}

		@Test
		@DisplayName(
			"getRequestedLocale returns single "
				+ "locale from set"
		)
		void shouldReturnSingleLocaleFromSet() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null, Set.of(Locale.JAPANESE),
					Collections.emptySet(), true
				);

			assertSame(
				Locale.JAPANESE,
				predicate.getRequestedLocale()
			);
		}

		@Test
		@DisplayName(
			"getRequestedLocale returns null when no "
				+ "locale available"
		)
		void shouldReturnNullWhenNoLocaleAvailable() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null, Set.of(Locale.ENGLISH, Locale.FRENCH),
					Collections.emptySet(), true
				);

			assertNull(predicate.getRequestedLocale());
		}

		@Test
		@DisplayName(
			"isLocaleSet returns true when locale field is set"
		)
		void shouldReturnTrueWhenLocaleFieldSet() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
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
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
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
		@DisplayName("creates richer copy when request requires attributes")
		void shouldCreateRicherCopyForNoAttributes() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Collections.emptySet(), false
				);

			final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityAttributes())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(Collections.emptySet());
			Mockito.when(evitaRequest.getEntityAttributeSet())
				.thenReturn(Collections.emptySet());

			assertNotSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"returns same instance when neither requires attributes"
		)
		void shouldNotCreateRicherCopyForNoAttributes() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Collections.emptySet(), false
				);

			final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityAttributes())
				.thenReturn(false);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(Collections.emptySet());
			Mockito.when(evitaRequest.getEntityAttributeSet())
				.thenReturn(Collections.emptySet());

			assertSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"returns same instance when predicate already has attributes"
		)
		void shouldNotCreateRicherCopyForNoAttributesWhenAttributesPresent() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Collections.emptySet(), true
				);

			final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityAttributes())
				.thenReturn(false);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(Collections.emptySet());
			Mockito.when(evitaRequest.getEntityAttributeSet())
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
		void shouldCreateRicherCopyForGlobalAttributes() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Collections.emptySet(), true
				);

			final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityAttributes())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(
					new HashSet<>(
						Collections.singletonList(Locale.ENGLISH)
					)
				);
			Mockito.when(evitaRequest.getEntityAttributeSet())
				.thenReturn(Collections.emptySet());

			assertNotSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"returns same when locales and attributes unchanged"
		)
		void shouldNotCreateRicherCopyForGlobalAttributes() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null, Collections.emptySet(),
					Collections.emptySet(), true
				);

			final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityAttributes())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(Collections.emptySet());
			Mockito.when(evitaRequest.getEntityAttributeSet())
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
		void shouldCreateRicherCopyForGlobalAndLocalizedAttributes() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null,
					new HashSet<>(
						Collections.singletonList(Locale.ENGLISH)
					),
					Collections.emptySet(), true
				);

			final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityAttributes())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(
					new HashSet<>(
						Arrays.asList(Locale.ENGLISH, Locale.CANADA)
					)
				);
			Mockito.when(evitaRequest.getEntityAttributeSet())
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
		void shouldNotCreateRicherCopyForGlobalAndLocalizedAttributes() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null,
					new HashSet<>(
						Arrays.asList(Locale.ENGLISH, Locale.CANADA)
					),
					Collections.emptySet(), true
				);

			final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityAttributes())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(
					new HashSet<>(
						Arrays.asList(Locale.ENGLISH, Locale.CANADA)
					)
				);
			Mockito.when(evitaRequest.getEntityAttributeSet())
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
		void shouldNotCreateRicherCopyForGlobalAndLocalizedAttributesSubset() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null,
					new HashSet<>(
						Arrays.asList(Locale.ENGLISH, Locale.CANADA)
					),
					Collections.emptySet(), true
				);

			final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityAttributes())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(
					new HashSet<>(
						Collections.singletonList(Locale.ENGLISH)
					)
				);
			Mockito.when(evitaRequest.getEntityAttributeSet())
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
		void shouldCreateRicherCopyForGlobalAndLocalizedAttributesByName() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null,
					new HashSet<>(
						Collections.singletonList(Locale.ENGLISH)
					),
					new HashSet<>(Collections.singletonList("A")),
					true
				);

			final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityAttributes())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(
					new HashSet<>(
						Arrays.asList(Locale.ENGLISH, Locale.CANADA)
					)
				);
			Mockito.when(evitaRequest.getEntityAttributeSet())
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
		void shouldNotCreateRicherCopyForGlobalAndLocalizedAttributesByName() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null,
					new HashSet<>(
						Arrays.asList(Locale.ENGLISH, Locale.CANADA)
					),
					new HashSet<>(Arrays.asList("A", "B")),
					true
				);

			final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityAttributes())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(
					new HashSet<>(
						Arrays.asList(Locale.ENGLISH, Locale.CANADA)
					)
				);
			Mockito.when(evitaRequest.getEntityAttributeSet())
				.thenReturn(new HashSet<>(Arrays.asList("A", "B")));

			assertSame(
				predicate,
				predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"returns same when request is subset by locales and names"
		)
		void shouldNotCreateRicherCopyForSubsetByName() {
			final AttributeValueSerializablePredicate predicate =
				new AttributeValueSerializablePredicate(
					null, null,
					new HashSet<>(
						Arrays.asList(Locale.ENGLISH, Locale.CANADA)
					),
					new HashSet<>(Arrays.asList("A", "B")),
					true
				);

			final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.isRequiresEntityAttributes())
				.thenReturn(true);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(
					new HashSet<>(
						Collections.singletonList(Locale.ENGLISH)
					)
				);
			Mockito.when(evitaRequest.getEntityAttributeSet())
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
