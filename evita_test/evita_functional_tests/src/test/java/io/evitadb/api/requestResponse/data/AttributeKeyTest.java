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

package io.evitadb.api.requestResponse.data;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.utils.ComparatorUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.COMPARATOR;
import static io.evitadb.test.TestTags.CONTRACT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the total ordering of {@link AttributeKey#compareTo(AttributeKey)}. The ordering is load-bearing for every
 * sorted structure keyed by an attribute key (sorted attribute containers and their binary searches), so this test
 * exists to keep the contract fixed while the method body is optimized for allocation.
 *
 * The contract, inherited from {@link ComparatorUtils#compareLocale(Locale, Locale, java.util.function.IntSupplier)}:
 * a **non-null locale sorts before a null one**, two non-null locales compare by {@link Locale#toString()}, and only
 * a tie on the locale level falls through to the attribute name.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("AttributeKey ordering")
@Tag(CONTRACT)
@Tag(ATTRIBUTE)
@Tag(COMPARATOR)
class AttributeKeyTest {

	private static final Locale CZECH = new Locale("cs");
	private static final Locale ENGLISH = Locale.ENGLISH;
	private static final Locale US = Locale.US;

	/**
	 * Asserts that `left` sorts strictly before `right` and that the comparison is antisymmetric.
	 */
	private static void assertOrderedBefore(AttributeKey left, AttributeKey right) {
		assertTrue(left.compareTo(right) < 0, () -> left + " should sort before " + right);
		assertTrue(right.compareTo(left) > 0, () -> right + " should sort after " + left);
	}

	@Nested
	@DisplayName("locale is the primary ordering level")
	class LocaleOrdering {

		@Test
		@DisplayName("localized key sorts before non-localized one regardless of the attribute name")
		void shouldOrderLocalizedKeyBeforeNonLocalizedOne() {
			// the localized key loses on the name level, yet still sorts first - locale dominates
			assertOrderedBefore(new AttributeKey("zzz", ENGLISH), new AttributeKey("aaa"));
			assertOrderedBefore(new AttributeKey("aaa", ENGLISH), new AttributeKey("aaa"));
		}

		@Test
		@DisplayName("two localized keys are ordered by the locale string")
		void shouldOrderLocalizedKeysByLocaleString() {
			// "cs" < "en" < "en_US" as plain strings
			assertOrderedBefore(new AttributeKey("zzz", CZECH), new AttributeKey("aaa", ENGLISH));
			assertOrderedBefore(new AttributeKey("zzz", ENGLISH), new AttributeKey("aaa", US));
		}

		@Test
		@DisplayName("equal locales fall through to the attribute name")
		void shouldFallThroughToAttributeNameOnEqualLocales() {
			assertOrderedBefore(new AttributeKey("aaa", ENGLISH), new AttributeKey("zzz", ENGLISH));
			// distinct instances rendering the same locale string are still a tie
			assertOrderedBefore(new AttributeKey("aaa", new Locale("en")), new AttributeKey("zzz", ENGLISH));
		}

		@Test
		@DisplayName("both locales null falls through to the attribute name")
		void shouldFallThroughToAttributeNameOnBothLocalesNull() {
			assertOrderedBefore(new AttributeKey("aaa"), new AttributeKey("zzz"));
		}
	}

	@Nested
	@DisplayName("comparison is reflexive and consistent with equality")
	class Consistency {

		@Test
		@DisplayName("equal keys compare as zero")
		void shouldCompareEqualKeysAsZero() {
			assertEquals(0, new AttributeKey("code").compareTo(new AttributeKey("code")));
			assertEquals(0, new AttributeKey("code", ENGLISH).compareTo(new AttributeKey("code", ENGLISH)));
			// distinct but equal locale instances
			assertEquals(0, new AttributeKey("code", new Locale("en")).compareTo(new AttributeKey("code", ENGLISH)));
			// self-comparison
			final AttributeKey key = new AttributeKey("code", US);
			assertEquals(0, key.compareTo(key));
		}

		@Test
		@DisplayName("sorting a mixed set yields locales first, then the locale-agnostic keys")
		void shouldSortMixedKeysDeterministically() {
			final List<AttributeKey> keys = new ArrayList<>(6);
			keys.add(new AttributeKey("name"));
			keys.add(new AttributeKey("code", US));
			keys.add(new AttributeKey("code"));
			keys.add(new AttributeKey("name", CZECH));
			keys.add(new AttributeKey("code", ENGLISH));
			keys.add(new AttributeKey("code", CZECH));
			keys.sort(null);

			assertEquals(
				List.of(
					new AttributeKey("code", CZECH),
					new AttributeKey("name", CZECH),
					new AttributeKey("code", ENGLISH),
					new AttributeKey("code", US),
					new AttributeKey("code"),
					new AttributeKey("name")
				),
				keys
			);
		}

		@Test
		@DisplayName("ordering matches the shared ComparatorUtils.compareLocale semantics")
		void shouldMatchComparatorUtilsSemantics() {
			// the historical implementation delegated here - the ordering must remain identical
			final Locale[] locales = {null, CZECH, ENGLISH, US};
			final String[] names = {"aaa", "zzz"};
			for (final Locale leftLocale : locales) {
				for (final String leftName : names) {
					for (final Locale rightLocale : locales) {
						for (final String rightName : names) {
							final AttributeKey left = new AttributeKey(leftName, leftLocale);
							final AttributeKey right = new AttributeKey(rightName, rightLocale);
							final int expected = ComparatorUtils.compareLocale(
								leftLocale, rightLocale, () -> leftName.compareTo(rightName)
							);
							assertEquals(
								Integer.signum(expected),
								Integer.signum(left.compareTo(right)),
								() -> "mismatch for " + left + " vs " + right
							);
						}
					}
				}
			}
		}
	}
}
