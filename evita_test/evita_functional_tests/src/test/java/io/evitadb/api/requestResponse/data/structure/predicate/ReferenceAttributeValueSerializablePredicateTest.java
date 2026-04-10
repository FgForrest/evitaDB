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
import io.evitadb.api.requestResponse.EvitaRequest.AttributeRequest;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ReferenceAttributeValueSerializablePredicate} verifying
 * fetch status checks, predicate test filtering, locale handling, and
 * exception throwing for unfetched attributes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Reference attribute value predicate")
class ReferenceAttributeValueSerializablePredicateTest {

	@Nested
	@DisplayName("Fetch status checks")
	class FetchStatusTest {

		@Test
		@DisplayName("wasFetched returns true when attributes are required")
		void shouldReturnTrueWhenAttributesRequired() {
			final ReferenceAttributeValueSerializablePredicate predicate =
				new ReferenceAttributeValueSerializablePredicate(
					null, Collections.emptySet(), AttributeRequest.ALL
				);

			assertTrue(predicate.wasFetched());
		}

		@Test
		@DisplayName("wasFetched returns false when attributes not required")
		void shouldReturnFalseWhenAttributesNotRequired() {
			final ReferenceAttributeValueSerializablePredicate predicate =
				new ReferenceAttributeValueSerializablePredicate(
					null, Collections.emptySet(), AttributeRequest.EMPTY
				);

			assertFalse(predicate.wasFetched());
		}

		@Test
		@DisplayName(
			"wasFetched(locale) returns true when locales is empty set"
		)
		void shouldReturnTrueForAnyLocaleWhenLocalesIsEmpty() {
			final ReferenceAttributeValueSerializablePredicate predicate =
				new ReferenceAttributeValueSerializablePredicate(
					null, Collections.emptySet(), AttributeRequest.ALL
				);

			assertTrue(predicate.wasFetched(Locale.ENGLISH));
			assertTrue(predicate.wasFetched(Locale.FRENCH));
		}

		@Test
		@DisplayName(
			"wasFetched(locale) returns true only for fetched locales"
		)
		void shouldReturnTrueOnlyForFetchedLocales() {
			final ReferenceAttributeValueSerializablePredicate predicate =
				new ReferenceAttributeValueSerializablePredicate(
					null, Set.of(Locale.ENGLISH), AttributeRequest.ALL
				);

			assertTrue(predicate.wasFetched(Locale.ENGLISH));
			assertFalse(predicate.wasFetched(Locale.FRENCH));
		}

		@Test
		@DisplayName("wasFetched(locale) returns false when locales is null")
		void shouldReturnFalseWhenLocalesIsNull() {
			final ReferenceAttributeValueSerializablePredicate predicate =
				new ReferenceAttributeValueSerializablePredicate(
					null, null, AttributeRequest.ALL
				);

			assertFalse(predicate.wasFetched(Locale.ENGLISH));
		}

		@Test
		@DisplayName(
			"wasFetched(name) returns true for any name when "
				+ "attribute set is empty"
		)
		void shouldReturnTrueForAnyNameWhenAttributeSetEmpty() {
			final ReferenceAttributeValueSerializablePredicate predicate =
				new ReferenceAttributeValueSerializablePredicate(
					null, Collections.emptySet(), AttributeRequest.ALL
				);

			assertTrue(predicate.wasFetched("anyAttribute"));
		}

		@Test
		@DisplayName(
			"wasFetched(name) returns true only for specified attributes"
		)
		void shouldReturnTrueOnlyForSpecifiedAttributes() {
			final AttributeRequest attrRequest = new AttributeRequest(
				Set.of("attrA", "attrB"), true
			);
			final ReferenceAttributeValueSerializablePredicate predicate =
				new ReferenceAttributeValueSerializablePredicate(
					null, Collections.emptySet(), attrRequest
				);

			assertTrue(predicate.wasFetched("attrA"));
			assertTrue(predicate.wasFetched("attrB"));
			assertFalse(predicate.wasFetched("attrC"));
		}

		@Test
		@DisplayName(
			"wasFetched(name) returns false when attributes not required"
		)
		void shouldReturnFalseForNameWhenNotRequired() {
			final ReferenceAttributeValueSerializablePredicate predicate =
				new ReferenceAttributeValueSerializablePredicate(
					null, Collections.emptySet(), AttributeRequest.EMPTY
				);

			assertFalse(predicate.wasFetched("anyAttribute"));
		}

		@Test
		@DisplayName(
			"wasFetched(name, locale) returns true when both match"
		)
		void shouldReturnTrueWhenBothNameAndLocaleMatch() {
			final ReferenceAttributeValueSerializablePredicate predicate =
				new ReferenceAttributeValueSerializablePredicate(
					null, Set.of(Locale.ENGLISH), AttributeRequest.ALL
				);

			assertTrue(predicate.wasFetched("attr", Locale.ENGLISH));
			assertFalse(predicate.wasFetched("attr", Locale.FRENCH));
		}
	}

	@Nested
	@DisplayName("Check fetched - exception throwing")
	class CheckFetchedTest {

		@Test
		@DisplayName("checkFetched does not throw when attributes required")
		void shouldNotThrowWhenAttributesRequired() {
			final ReferenceAttributeValueSerializablePredicate predicate =
				new ReferenceAttributeValueSerializablePredicate(
					null, Collections.emptySet(), AttributeRequest.ALL
				);

			predicate.checkFetched();
		}

		@Test
		@DisplayName(
			"checkFetched throws ContextMissingException "
				+ "when attributes not required"
		)
		void shouldThrowWhenAttributesNotRequired() {
			final ReferenceAttributeValueSerializablePredicate predicate =
				new ReferenceAttributeValueSerializablePredicate(
					null, Collections.emptySet(), AttributeRequest.EMPTY
				);

			assertThrows(
				ContextMissingException.class, predicate::checkFetched
			);
		}

		@Test
		@DisplayName(
			"checkFetched(key) does not throw for known global attribute"
		)
		void shouldNotThrowForKnownGlobalAttribute() {
			final ReferenceAttributeValueSerializablePredicate predicate =
				new ReferenceAttributeValueSerializablePredicate(
					null, Collections.emptySet(), AttributeRequest.ALL
				);

			predicate.checkFetched(new AttributeKey("attrA"));
		}

		@Test
		@DisplayName(
			"checkFetched(key) throws when attribute name not in set"
		)
		void shouldThrowWhenAttributeNameNotInSet() {
			final AttributeRequest attrRequest = new AttributeRequest(
				Set.of("attrA"), true
			);
			final ReferenceAttributeValueSerializablePredicate predicate =
				new ReferenceAttributeValueSerializablePredicate(
					null, Collections.emptySet(), attrRequest
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
			final ReferenceAttributeValueSerializablePredicate predicate =
				new ReferenceAttributeValueSerializablePredicate(
					null, Set.of(Locale.ENGLISH), AttributeRequest.ALL
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
			"checkFetched(key) does not throw when locale matches "
				+ "single locale (derived as this.locale)"
		)
		void shouldNotThrowWhenLocaleMatchesDerivedLocale() {
			// When locales has exactly one element, the constructor
			// derives `this.locale` from it
			final ReferenceAttributeValueSerializablePredicate predicate =
				new ReferenceAttributeValueSerializablePredicate(
					null, Set.of(Locale.ENGLISH), AttributeRequest.ALL
				);

			predicate.checkFetched(
				new AttributeKey("attr", Locale.ENGLISH)
			);
		}

		@Test
		@DisplayName(
			"checkFetched(key) passes for global key even when "
				+ "locales are restricted"
		)
		void shouldPassForGlobalKeyWithRestrictedLocales() {
			final ReferenceAttributeValueSerializablePredicate predicate =
				new ReferenceAttributeValueSerializablePredicate(
					null, Set.of(Locale.ENGLISH), AttributeRequest.ALL
				);

			// global key (no locale) should not trigger locale check
			predicate.checkFetched(new AttributeKey("attr"));
		}
	}

	@Nested
	@DisplayName("isLocaleSet method")
	class IsLocaleSetTest {

		@Test
		@DisplayName("returns true when implicit locale is set")
		void shouldReturnTrueWhenImplicitLocaleSet() {
			final ReferenceAttributeValueSerializablePredicate predicate =
				new ReferenceAttributeValueSerializablePredicate(
					Locale.ENGLISH, null, AttributeRequest.ALL
				);

			assertTrue(predicate.isLocaleSet());
		}

		@Test
		@DisplayName("returns true when locales is not null")
		void shouldReturnTrueWhenLocalesNotNull() {
			final ReferenceAttributeValueSerializablePredicate predicate =
				new ReferenceAttributeValueSerializablePredicate(
					null, Collections.emptySet(), AttributeRequest.ALL
				);

			assertTrue(predicate.isLocaleSet());
		}

		@Test
		@DisplayName("returns true when single locale derives locale field")
		void shouldReturnTrueWhenLocaleIsDerived() {
			final ReferenceAttributeValueSerializablePredicate predicate =
				new ReferenceAttributeValueSerializablePredicate(
					null, Set.of(Locale.ENGLISH), AttributeRequest.ALL
				);

			assertTrue(predicate.isLocaleSet());
		}

		@Test
		@DisplayName("returns false when all locale fields are null")
		void shouldReturnFalseWhenAllLocaleFieldsNull() {
			final ReferenceAttributeValueSerializablePredicate predicate =
				new ReferenceAttributeValueSerializablePredicate(
					null, null, AttributeRequest.ALL
				);

			assertFalse(predicate.isLocaleSet());
		}
	}

	@Nested
	@DisplayName("Predicate test method")
	class TestMethodTest {

		@Test
		@DisplayName(
			"returns false when attributes not required"
		)
		void shouldReturnFalseWhenAttributesNotRequired() {
			final ReferenceAttributeValueSerializablePredicate predicate =
				new ReferenceAttributeValueSerializablePredicate(
					null, Collections.emptySet(), AttributeRequest.EMPTY
				);

			final AttributeValue value = new AttributeValue(
				1, new AttributeKey("attr"), "value", false
			);

			assertFalse(predicate.test(value));
		}

		@Test
		@DisplayName(
			"returns false when attribute value is dropped"
		)
		void shouldReturnFalseWhenAttributeDropped() {
			final ReferenceAttributeValueSerializablePredicate predicate =
				new ReferenceAttributeValueSerializablePredicate(
					null, Collections.emptySet(), AttributeRequest.ALL
				);

			final AttributeValue value = new AttributeValue(
				1, new AttributeKey("attr"), "value", true
			);

			assertFalse(predicate.test(value));
		}

		@Test
		@DisplayName(
			"returns true for existing global attribute when all "
				+ "attributes required"
		)
		void shouldReturnTrueForExistingGlobalAttribute() {
			final ReferenceAttributeValueSerializablePredicate predicate =
				new ReferenceAttributeValueSerializablePredicate(
					null, Collections.emptySet(), AttributeRequest.ALL
				);

			final AttributeValue value = new AttributeValue(
				1, new AttributeKey("attr"), "value", false
			);

			assertTrue(predicate.test(value));
		}

		@Test
		@DisplayName(
			"returns true for localized attribute when locale matches"
		)
		void shouldReturnTrueForLocalizedAttributeWithMatchingLocale() {
			final ReferenceAttributeValueSerializablePredicate predicate =
				new ReferenceAttributeValueSerializablePredicate(
					null, Set.of(Locale.ENGLISH), AttributeRequest.ALL
				);

			final AttributeValue value = new AttributeValue(
				1, new AttributeKey("attr", Locale.ENGLISH),
				"value", false
			);

			assertTrue(predicate.test(value));
		}

		@Test
		@DisplayName(
			"returns false for localized attribute when locale "
				+ "does not match"
		)
		void shouldReturnFalseForLocalizedAttributeWithNonMatchingLocale() {
			final ReferenceAttributeValueSerializablePredicate predicate =
				new ReferenceAttributeValueSerializablePredicate(
					null, Set.of(Locale.ENGLISH), AttributeRequest.ALL
				);

			final AttributeValue value = new AttributeValue(
				1, new AttributeKey("attr", Locale.FRENCH),
				"value", false
			);

			assertFalse(predicate.test(value));
		}

		@Test
		@DisplayName(
			"returns true for localized attribute matching implicit locale"
		)
		void shouldReturnTrueForImplicitLocaleMatch() {
			final ReferenceAttributeValueSerializablePredicate predicate =
				new ReferenceAttributeValueSerializablePredicate(
					Locale.FRENCH, Set.of(Locale.ENGLISH),
					AttributeRequest.ALL
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
			final AttributeRequest attrRequest = new AttributeRequest(
				Set.of("attrA"), true
			);
			final ReferenceAttributeValueSerializablePredicate predicate =
				new ReferenceAttributeValueSerializablePredicate(
					null, Collections.emptySet(), attrRequest
				);

			final AttributeValue value = new AttributeValue(
				1, new AttributeKey("attrB"), "value", false
			);

			assertFalse(predicate.test(value));
		}

		@Test
		@DisplayName(
			"returns true when attribute name is in restricted set"
		)
		void shouldReturnTrueWhenNameInSet() {
			final AttributeRequest attrRequest = new AttributeRequest(
				Set.of("attrA"), true
			);
			final ReferenceAttributeValueSerializablePredicate predicate =
				new ReferenceAttributeValueSerializablePredicate(
					null, Collections.emptySet(), attrRequest
				);

			final AttributeValue value = new AttributeValue(
				1, new AttributeKey("attrA"), "value", false
			);

			assertTrue(predicate.test(value));
		}
	}
}
