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

import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PredicateLocaleHelper} utility methods.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@DisplayName("Predicate locale helper")
class PredicateLocaleHelperTest implements EvitaTestSupport {

	@Nested
	@DisplayName("combineLocales")
	class CombineLocalesTest {

		@Test
		@DisplayName("returns request locales when existing is null")
		void shouldReturnRequestLocalesWhenExistingIsNull() {
			final EvitaRequest request = Mockito.mock(EvitaRequest.class);
			Mockito.when(request.getRequiredLocales()).thenReturn(Set.of(Locale.ENGLISH));

			final Set<Locale> result = PredicateLocaleHelper.combineLocales(null, request);

			assertEquals(Set.of(Locale.ENGLISH), result);
		}

		@Test
		@DisplayName("returns null when both are null")
		void shouldReturnNullWhenBothNull() {
			final EvitaRequest request = Mockito.mock(EvitaRequest.class);
			Mockito.when(request.getRequiredLocales()).thenReturn(null);

			final Set<Locale> result = PredicateLocaleHelper.combineLocales(null, request);

			assertNull(result);
		}

		@Test
		@DisplayName("returns existing when request locales are null")
		void shouldReturnExistingWhenRequestNull() {
			final EvitaRequest request = Mockito.mock(EvitaRequest.class);
			Mockito.when(request.getRequiredLocales()).thenReturn(null);

			final Set<Locale> existing = Set.of(Locale.FRENCH);
			final Set<Locale> result = PredicateLocaleHelper.combineLocales(existing, request);

			assertSame(existing, result);
		}

		@Test
		@DisplayName("merges both when non-null")
		void shouldMergeBothWhenNonNull() {
			final EvitaRequest request = Mockito.mock(EvitaRequest.class);
			Mockito.when(request.getRequiredLocales()).thenReturn(Set.of(Locale.GERMAN));

			final Set<Locale> result = PredicateLocaleHelper.combineLocales(
				Set.of(Locale.FRENCH), request
			);

			assertEquals(Set.of(Locale.FRENCH, Locale.GERMAN), result);
		}
	}

	@Nested
	@DisplayName("resolveLocale")
	class ResolveLocaleTest {

		@Test
		@DisplayName("returns current locale when non-null")
		void shouldReturnCurrentWhenNonNull() {
			final EvitaRequest request = Mockito.mock(EvitaRequest.class);

			final Locale result = PredicateLocaleHelper.resolveLocale(Locale.ENGLISH, request);

			assertEquals(Locale.ENGLISH, result);
		}

		@Test
		@DisplayName("falls back to implicit locale")
		void shouldFallBackToImplicitLocale() {
			final EvitaRequest request = Mockito.mock(EvitaRequest.class);
			Mockito.when(request.getImplicitLocale()).thenReturn(Locale.FRENCH);

			final Locale result = PredicateLocaleHelper.resolveLocale(null, request);

			assertEquals(Locale.FRENCH, result);
		}

		@Test
		@DisplayName("falls back to explicit locale")
		void shouldFallBackToExplicitLocale() {
			final EvitaRequest request = Mockito.mock(EvitaRequest.class);
			Mockito.when(request.getImplicitLocale()).thenReturn(null);
			Mockito.when(request.getLocale()).thenReturn(Locale.GERMAN);

			final Locale result = PredicateLocaleHelper.resolveLocale(null, request);

			assertEquals(Locale.GERMAN, result);
		}

		@Test
		@DisplayName("falls back to single-element required locales")
		void shouldFallBackToSingleRequiredLocale() {
			final EvitaRequest request = Mockito.mock(EvitaRequest.class);
			Mockito.when(request.getImplicitLocale()).thenReturn(null);
			Mockito.when(request.getLocale()).thenReturn(null);
			Mockito.when(request.getRequiredLocales()).thenReturn(Set.of(Locale.ITALIAN));

			final Locale result = PredicateLocaleHelper.resolveLocale(null, request);

			assertEquals(Locale.ITALIAN, result);
		}

		@Test
		@DisplayName("returns null when no locale can be determined")
		void shouldReturnNullWhenNoneAvailable() {
			final EvitaRequest request = Mockito.mock(EvitaRequest.class);
			Mockito.when(request.getImplicitLocale()).thenReturn(null);
			Mockito.when(request.getLocale()).thenReturn(null);
			Mockito.when(request.getRequiredLocales()).thenReturn(null);

			final Locale result = PredicateLocaleHelper.resolveLocale(null, request);

			assertNull(result);
		}

		@Test
		@DisplayName("returns null when multiple required locales")
		void shouldReturnNullWhenMultipleRequiredLocales() {
			final EvitaRequest request = Mockito.mock(EvitaRequest.class);
			Mockito.when(request.getImplicitLocale()).thenReturn(null);
			Mockito.when(request.getLocale()).thenReturn(null);
			Mockito.when(request.getRequiredLocales()).thenReturn(Set.of(Locale.ENGLISH, Locale.FRENCH));

			final Locale result = PredicateLocaleHelper.resolveLocale(null, request);

			assertNull(result);
		}
	}

	@Nested
	@DisplayName("resolveImplicitLocale")
	class ResolveImplicitLocaleTest {

		@Test
		@DisplayName("returns current when non-null")
		void shouldReturnCurrentWhenNonNull() {
			final EvitaRequest request = Mockito.mock(EvitaRequest.class);
			Mockito.when(request.getImplicitLocale()).thenReturn(Locale.FRENCH);

			final Locale result = PredicateLocaleHelper.resolveImplicitLocale(Locale.ENGLISH, request);

			assertEquals(Locale.ENGLISH, result);
		}

		@Test
		@DisplayName("returns request implicit when current is null")
		void shouldReturnRequestImplicitWhenCurrentNull() {
			final EvitaRequest request = Mockito.mock(EvitaRequest.class);
			Mockito.when(request.getImplicitLocale()).thenReturn(Locale.FRENCH);

			final Locale result = PredicateLocaleHelper.resolveImplicitLocale(null, request);

			assertEquals(Locale.FRENCH, result);
		}

		@Test
		@DisplayName("returns null when both are null")
		void shouldReturnNullWhenBothNull() {
			final EvitaRequest request = Mockito.mock(EvitaRequest.class);
			Mockito.when(request.getImplicitLocale()).thenReturn(null);

			final Locale result = PredicateLocaleHelper.resolveImplicitLocale(null, request);

			assertNull(result);
		}
	}

	@Nested
	@DisplayName("assertImplicitLocalesConsistent")
	class AssertImplicitLocalesConsistentTest {

		@Test
		@DisplayName("passes when both are null")
		void shouldPassWhenBothNull() {
			final EvitaRequest request = Mockito.mock(EvitaRequest.class);
			Mockito.when(request.getImplicitLocale()).thenReturn(null);

			assertDoesNotThrow(
				() -> PredicateLocaleHelper.assertImplicitLocalesConsistent(null, request)
			);
		}

		@Test
		@DisplayName("passes when current is null")
		void shouldPassWhenCurrentNull() {
			final EvitaRequest request = Mockito.mock(EvitaRequest.class);
			Mockito.when(request.getImplicitLocale()).thenReturn(Locale.ENGLISH);

			assertDoesNotThrow(
				() -> PredicateLocaleHelper.assertImplicitLocalesConsistent(null, request)
			);
		}

		@Test
		@DisplayName("passes when request is null")
		void shouldPassWhenRequestNull() {
			final EvitaRequest request = Mockito.mock(EvitaRequest.class);
			Mockito.when(request.getImplicitLocale()).thenReturn(null);

			assertDoesNotThrow(
				() -> PredicateLocaleHelper.assertImplicitLocalesConsistent(Locale.ENGLISH, request)
			);
		}

		@Test
		@DisplayName("passes when equal")
		void shouldPassWhenEqual() {
			final EvitaRequest request = Mockito.mock(EvitaRequest.class);
			Mockito.when(request.getImplicitLocale()).thenReturn(Locale.ENGLISH);

			assertDoesNotThrow(
				() -> PredicateLocaleHelper.assertImplicitLocalesConsistent(Locale.ENGLISH, request)
			);
		}

		@Test
		@DisplayName("throws when different")
		void shouldThrowWhenDifferent() {
			final EvitaRequest request = Mockito.mock(EvitaRequest.class);
			Mockito.when(request.getImplicitLocale()).thenReturn(Locale.FRENCH);

			assertThrows(
				GenericEvitaInternalError.class,
				() -> PredicateLocaleHelper.assertImplicitLocalesConsistent(Locale.ENGLISH, request)
			);
		}
	}

	@Nested
	@DisplayName("getRequestedLocale")
	class GetRequestedLocaleTest {

		@Test
		@DisplayName("returns locale when set")
		void shouldReturnLocaleWhenSet() {
			final Locale result = PredicateLocaleHelper.getRequestedLocale(
				Locale.ENGLISH, Locale.FRENCH, Set.of(Locale.GERMAN)
			);

			assertEquals(Locale.ENGLISH, result);
		}

		@Test
		@DisplayName("returns implicit locale when locale is null")
		void shouldReturnImplicitWhenLocaleNull() {
			final Locale result = PredicateLocaleHelper.getRequestedLocale(
				null, Locale.FRENCH, null
			);

			assertEquals(Locale.FRENCH, result);
		}

		@Test
		@DisplayName("returns single locale from set")
		void shouldReturnSingleLocaleFromSet() {
			final Locale result = PredicateLocaleHelper.getRequestedLocale(
				null, null, Set.of(Locale.GERMAN)
			);

			assertEquals(Locale.GERMAN, result);
		}

		@Test
		@DisplayName("returns null when no locale available")
		void shouldReturnNullWhenNoLocale() {
			final Locale result = PredicateLocaleHelper.getRequestedLocale(null, null, null);

			assertNull(result);
		}

		@Test
		@DisplayName("returns null when multiple locales in set")
		void shouldReturnNullWhenMultipleLocales() {
			final Locale result = PredicateLocaleHelper.getRequestedLocale(
				null, null, Set.of(Locale.ENGLISH, Locale.FRENCH)
			);

			assertNull(result);
		}

		@Test
		@DisplayName("returns null when locales set is empty")
		void shouldReturnNullWhenLocalesEmpty() {
			final Locale result = PredicateLocaleHelper.getRequestedLocale(
				null, null, Collections.emptySet()
			);

			assertNull(result);
		}
	}

	@Nested
	@DisplayName("isLocaleSet")
	class IsLocaleSetTest {

		@Test
		@DisplayName("returns false when all null")
		void shouldReturnFalseWhenAllNull() {
			assertFalse(PredicateLocaleHelper.isLocaleSet(null, null, null));
		}

		@Test
		@DisplayName("returns true when locale set")
		void shouldReturnTrueWhenLocaleSet() {
			assertTrue(PredicateLocaleHelper.isLocaleSet(Locale.ENGLISH, null, null));
		}

		@Test
		@DisplayName("returns true when implicit locale set")
		void shouldReturnTrueWhenImplicitLocaleSet() {
			assertTrue(PredicateLocaleHelper.isLocaleSet(null, Locale.ENGLISH, null));
		}

		@Test
		@DisplayName("returns true when locales set")
		void shouldReturnTrueWhenLocalesSet() {
			assertTrue(PredicateLocaleHelper.isLocaleSet(null, null, Set.of(Locale.ENGLISH)));
		}
	}

	@Nested
	@DisplayName("getAllLocales")
	class GetAllLocalesTest {

		@Test
		@DisplayName("returns null when both null")
		void shouldReturnNullWhenBothNull() {
			assertNull(PredicateLocaleHelper.getAllLocales(null, null));
		}

		@Test
		@DisplayName("returns singleton when only implicit locale")
		void shouldReturnSingletonWhenOnlyImplicit() {
			final Set<Locale> result = PredicateLocaleHelper.getAllLocales(Locale.ENGLISH, null);

			assertEquals(Set.of(Locale.ENGLISH), result);
		}

		@Test
		@DisplayName("returns locales when only explicit")
		void shouldReturnLocalesWhenOnlyExplicit() {
			final Set<Locale> locales = Set.of(Locale.FRENCH, Locale.GERMAN);
			final Set<Locale> result = PredicateLocaleHelper.getAllLocales(null, locales);

			assertSame(locales, result);
		}

		@Test
		@DisplayName("merges implicit and explicit locales")
		void shouldMergeImplicitAndExplicit() {
			final Set<Locale> result = PredicateLocaleHelper.getAllLocales(
				Locale.ENGLISH, Set.of(Locale.FRENCH, Locale.GERMAN)
			);

			assertEquals(Set.of(Locale.ENGLISH, Locale.FRENCH, Locale.GERMAN), result);
		}

		@Test
		@DisplayName("deduplicates when implicit is in explicit set")
		void shouldDeduplicateWhenImplicitInExplicit() {
			final Set<Locale> result = PredicateLocaleHelper.getAllLocales(
				Locale.ENGLISH, Set.of(Locale.ENGLISH, Locale.FRENCH)
			);

			assertEquals(Set.of(Locale.ENGLISH, Locale.FRENCH), result);
		}
	}
}
