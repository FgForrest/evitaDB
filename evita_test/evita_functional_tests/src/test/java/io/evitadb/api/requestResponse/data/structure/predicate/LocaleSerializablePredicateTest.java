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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LocaleSerializablePredicate} verifying locale
 * predicate filtering, default instance behavior, and richer copy
 * creation logic.
 *
 * @author evitaDB
 */
@DisplayName("Locale predicate")
class LocaleSerializablePredicateTest {

	@Nested
	@DisplayName("Construction and default instances")
	class ConstructionTest {

		@Test
		@DisplayName("default instance has null implicit locale and empty locales")
		void shouldHaveNullImplicitLocaleAndEmptyLocalesInDefault() {
			final LocaleSerializablePredicate predicate =
				LocaleSerializablePredicate.DEFAULT_INSTANCE;

			assertNull(predicate.getImplicitLocale());
			assertSame(Collections.emptySet(), predicate.getLocales());
			assertNull(predicate.getUnderlyingPredicate());
		}

		@Test
		@DisplayName(
			"package-private constructor sets implicit locale and locales"
		)
		void shouldSetFieldsFromPackagePrivateConstructor() {
			final LocaleSerializablePredicate predicate =
				new LocaleSerializablePredicate(Locale.ENGLISH, Set.of(Locale.FRENCH));

			assertSame(Locale.ENGLISH, predicate.getImplicitLocale());
			assertTrue(predicate.getLocales().contains(Locale.FRENCH));
		}
	}

	@Nested
	@DisplayName("Predicate test method")
	class TestMethodTest {

		@Test
		@DisplayName(
			"returns true for any locale when locales is empty set "
				+ "(all locales requested)"
		)
		void shouldAcceptAnyLocaleWhenLocalesIsEmptySet() {
			final LocaleSerializablePredicate predicate =
				new LocaleSerializablePredicate(null, Collections.emptySet());

			assertTrue(predicate.test(Locale.ENGLISH));
			assertTrue(predicate.test(Locale.FRENCH));
			assertTrue(predicate.test(Locale.JAPANESE));
		}

		@Test
		@DisplayName("returns true only for locales in the set")
		void shouldAcceptOnlyLocalesInTheSet() {
			final LocaleSerializablePredicate predicate =
				new LocaleSerializablePredicate(
					null, Set.of(Locale.ENGLISH, Locale.FRENCH)
				);

			assertTrue(predicate.test(Locale.ENGLISH));
			assertTrue(predicate.test(Locale.FRENCH));
			assertFalse(predicate.test(Locale.JAPANESE));
		}

		@Test
		@DisplayName("returns false when locales is null and no implicit locale")
		void shouldRejectWhenLocalesIsNullAndNoImplicitLocale() {
			final LocaleSerializablePredicate predicate =
				new LocaleSerializablePredicate(
					(Locale) null, (Set<Locale>) null
				);

			assertFalse(predicate.test(Locale.ENGLISH));
		}

		@Test
		@DisplayName("returns true for implicit locale even when locales is null")
		void shouldAcceptImplicitLocaleWhenLocalesIsNull() {
			final LocaleSerializablePredicate predicate =
				new LocaleSerializablePredicate(Locale.ENGLISH, null);

			assertTrue(predicate.test(Locale.ENGLISH));
			assertFalse(predicate.test(Locale.FRENCH));
		}

		@Test
		@DisplayName(
			"returns true for implicit locale even when not in locales set"
		)
		void shouldAcceptImplicitLocaleEvenWhenNotInExplicitSet() {
			final LocaleSerializablePredicate predicate =
				new LocaleSerializablePredicate(
					Locale.ENGLISH, Set.of(Locale.FRENCH)
				);

			assertTrue(predicate.test(Locale.ENGLISH));
			assertTrue(predicate.test(Locale.FRENCH));
			assertFalse(predicate.test(Locale.JAPANESE));
		}
	}

	@Nested
	@DisplayName("Richer copy creation")
	class RicherCopyTest {

		@Test
		@DisplayName("returns same instance when locales and implicit locale unchanged")
		void shouldReturnSameWhenNothingChanges() {
			final LocaleSerializablePredicate predicate =
				new LocaleSerializablePredicate(null, Collections.emptySet());

			final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.getImplicitLocale()).thenReturn(null);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(Collections.emptySet());

			assertSame(
				predicate, predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName("creates new instance when request adds new locales")
		void shouldCreateNewWhenRequestAddsLocales() {
			final LocaleSerializablePredicate predicate =
				new LocaleSerializablePredicate(
					null, new HashSet<>(Set.of(Locale.ENGLISH))
				);

			final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.getImplicitLocale()).thenReturn(null);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(
					new HashSet<>(Set.of(Locale.ENGLISH, Locale.FRENCH))
				);

			final LocaleSerializablePredicate richerCopy =
				predicate.createRicherCopyWith(evitaRequest);

			assertNotSame(predicate, richerCopy);
			assertTrue(richerCopy.getLocales().contains(Locale.ENGLISH));
			assertTrue(richerCopy.getLocales().contains(Locale.FRENCH));
		}

		@Test
		@DisplayName(
			"returns same instance when request locales are a subset"
		)
		void shouldReturnSameWhenRequestLocalesAreSubset() {
			final LocaleSerializablePredicate predicate =
				new LocaleSerializablePredicate(
					null,
					new HashSet<>(Set.of(Locale.ENGLISH, Locale.FRENCH))
				);

			final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.getImplicitLocale()).thenReturn(null);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(
					new HashSet<>(Set.of(Locale.ENGLISH, Locale.FRENCH))
				);

			assertSame(
				predicate, predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"creates new instance when request sets implicit locale"
		)
		void shouldCreateNewWhenRequestSetsImplicitLocale() {
			final LocaleSerializablePredicate predicate =
				new LocaleSerializablePredicate(null, Collections.emptySet());

			final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.getImplicitLocale())
				.thenReturn(Locale.ENGLISH);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(Collections.emptySet());

			final LocaleSerializablePredicate richerCopy =
				predicate.createRicherCopyWith(evitaRequest);

			assertNotSame(predicate, richerCopy);
			assertSame(Locale.ENGLISH, richerCopy.getImplicitLocale());
		}

		@Test
		@DisplayName(
			"throws when implicit locales differ"
		)
		void shouldThrowWhenImplicitLocalesDiffer() {
			final LocaleSerializablePredicate predicate =
				new LocaleSerializablePredicate(
					Locale.ENGLISH, Collections.emptySet()
				);

			final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.getImplicitLocale())
				.thenReturn(Locale.FRENCH);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(Collections.emptySet());

			assertThrows(
				GenericEvitaInternalError.class,
				() -> predicate.createRicherCopyWith(evitaRequest)
			);
		}

		@Test
		@DisplayName(
			"combines null locales with request locales"
		)
		void shouldCombineNullLocalesWithRequestLocales() {
			final LocaleSerializablePredicate predicate =
				new LocaleSerializablePredicate(
					(Locale) null, (Set<Locale>) null
				);

			final EvitaRequest evitaRequest = Mockito.mock(EvitaRequest.class);
			Mockito.when(evitaRequest.getImplicitLocale()).thenReturn(null);
			Mockito.when(evitaRequest.getRequiredLocales())
				.thenReturn(Set.of(Locale.ENGLISH));

			final LocaleSerializablePredicate richerCopy =
				predicate.createRicherCopyWith(evitaRequest);

			assertNotSame(predicate, richerCopy);
			assertTrue(richerCopy.getLocales().contains(Locale.ENGLISH));
		}
	}
}
