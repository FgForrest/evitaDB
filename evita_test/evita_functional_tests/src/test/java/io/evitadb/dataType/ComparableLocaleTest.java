/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.dataType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.DATA_TYPE;

/**
 * Tests for {@link ComparableLocale} verifying comparison,
 * equality, toString, and serialization behavior.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("ComparableLocale functionality")
@Tag(CONTRACT)
@Tag(DATA_TYPE)
class ComparableLocaleTest {

	@Nested
	@DisplayName("Comparison")
	class ComparisonTest {

		@Test
		@DisplayName("should order by language tag")
		void shouldOrderByLanguageTag() {
			final ComparableLocale cs =
				new ComparableLocale(new Locale("cs", "CZ"));
			final ComparableLocale en =
				new ComparableLocale(Locale.ENGLISH);
			final ComparableLocale fr =
				new ComparableLocale(Locale.FRENCH);

			// cs-CZ < en < fr by language tag
			assertTrue(cs.compareTo(en) < 0);
			assertTrue(en.compareTo(fr) < 0);
			assertTrue(cs.compareTo(fr) < 0);
		}

		@Test
		@DisplayName(
			"should distinguish locale variants " +
				"(en-GB vs en-US)"
		)
		void shouldDistinguishLocaleVariants() {
			final ComparableLocale enGb =
				new ComparableLocale(Locale.UK);
			final ComparableLocale enUs =
				new ComparableLocale(Locale.US);

			// en-GB < en-US by language tag
			assertTrue(enGb.compareTo(enUs) < 0);
		}

		@Test
		@DisplayName(
			"should return zero when comparing " +
				"equal locales"
		)
		void shouldReturnZeroForEqualLocales() {
			final ComparableLocale en1 =
				new ComparableLocale(Locale.ENGLISH);
			final ComparableLocale en2 =
				new ComparableLocale(Locale.ENGLISH);

			assertEquals(0, en1.compareTo(en2));
		}

		@Test
		@DisplayName("should handle Locale.ROOT")
		void shouldHandleLocaleRoot() {
			final ComparableLocale root =
				new ComparableLocale(Locale.ROOT);
			final ComparableLocale en =
				new ComparableLocale(Locale.ENGLISH);

			// ROOT has empty language tag "und", en has "en"
			// comparison depends on language tag ordering
			assertTrue(root.compareTo(en) != 0);
		}

		@Test
		@DisplayName("should sort array correctly")
		void shouldSortArrayCorrectly() {
			final ComparableLocale fr =
				new ComparableLocale(Locale.FRENCH);
			final ComparableLocale cs =
				new ComparableLocale(
					new Locale("cs", "CZ")
				);
			final ComparableLocale en =
				new ComparableLocale(Locale.ENGLISH);

			final ComparableLocale[] array = {fr, cs, en};
			Arrays.sort(array);

			assertEquals("cs-CZ", array[0].toString());
			assertEquals("en", array[1].toString());
			assertEquals("fr", array[2].toString());
		}
	}

	@Nested
	@DisplayName("Consistency with equals")
	class ConsistencyWithEqualsTest {

		/**
		 * An ill-formed variant is DROPPED from a language tag, so ordering by the tag alone equates two locales
		 * that {@link Locale#equals} keeps apart. These instances are index keys - a filter or sort value tree
		 * identifies one entry per key by this comparator - so an order that equates them merges two entries into
		 * one and the removal of the second fails an internal premise.
		 */
		@Test
		@DisplayName("two locales sharing a language tag but not equal must NOT compare equal")
		void shouldNotEquateLocalesThatShareALanguageTag() {
			final Locale withIllFormedVariant = new Locale("en", "US", "ill!formed");
			final Locale plain = new Locale("en", "US");

			assertEquals(
				withIllFormedVariant.toLanguageTag(), plain.toLanguageTag(),
				"premise: the ill-formed variant is dropped, so both render as the same tag"
			);
			assertNotEquals(withIllFormedVariant, plain, "premise: they are nevertheless distinct locales");

			assertNotEquals(
				0,
				new ComparableLocale(withIllFormedVariant).compareTo(new ComparableLocale(plain)),
				"the order must separate them, or they collapse into one index entry"
			);
		}

		@Test
		@DisplayName("a well-formed variant survives in the tag and was never at risk")
		void shouldSeparateLocalesWithWellFormedVariants() {
			final ComparableLocale first = new ComparableLocale(new Locale("en", "US", "x"));
			final ComparableLocale second = new ComparableLocale(new Locale("en", "US", "y"));

			assertNotEquals(0, first.compareTo(second), "encoded as x-lvariant-x / x-lvariant-y");
		}

		@Test
		@DisplayName("the tie-break decides only what the language tag left tied")
		void shouldPreserveLanguageTagOrdering() {
			final Locale[] distinctTags = {
				new Locale("cs", "CZ"), new Locale("de", "DE"), new Locale("en", "US")
			};
			for (int i = 0; i < distinctTags.length; i++) {
				for (int j = 0; j < distinctTags.length; j++) {
					final int tagOrder = distinctTags[i].toLanguageTag()
						.compareTo(distinctTags[j].toLanguageTag());
					if (tagOrder != 0) {
						assertEquals(
							Integer.signum(tagOrder),
							Integer.signum(
								new ComparableLocale(distinctTags[i])
									.compareTo(new ComparableLocale(distinctTags[j]))
							),
							"every ordering decision the tag makes must survive the tie-break"
						);
					}
				}
			}
		}

		@Test
		@DisplayName("equal locales still compare equal")
		void shouldStillEquateEqualLocales() {
			assertEquals(
				0,
				new ComparableLocale(new Locale("en", "US")).compareTo(new ComparableLocale(new Locale("en", "US")))
			);
		}
	}

	@Nested
	@DisplayName("Equality")
	class EqualityTest {

		@Test
		@DisplayName("should be reflexive")
		void shouldBeReflexive() {
			final ComparableLocale en =
				new ComparableLocale(Locale.ENGLISH);

			assertEquals(en, en);
		}

		@Test
		@DisplayName("should be symmetric")
		void shouldBeSymmetric() {
			final ComparableLocale en1 =
				new ComparableLocale(Locale.ENGLISH);
			final ComparableLocale en2 =
				new ComparableLocale(Locale.ENGLISH);

			assertEquals(en1, en2);
			assertEquals(en2, en1);
		}

		@Test
		@DisplayName("should be transitive")
		void shouldBeTransitive() {
			final ComparableLocale en1 =
				new ComparableLocale(Locale.ENGLISH);
			final ComparableLocale en2 =
				new ComparableLocale(Locale.ENGLISH);
			final ComparableLocale en3 =
				new ComparableLocale(Locale.ENGLISH);

			assertEquals(en1, en2);
			assertEquals(en2, en3);
			assertEquals(en1, en3);
		}

		@Test
		@DisplayName("should not be equal to null")
		void shouldNotBeEqualToNull() {
			final ComparableLocale en =
				new ComparableLocale(Locale.ENGLISH);

			assertNotEquals(null, en);
		}

		@Test
		@DisplayName(
			"should not be equal to different locale"
		)
		void shouldNotBeEqualToDifferentLocale() {
			final ComparableLocale en =
				new ComparableLocale(Locale.ENGLISH);
			final ComparableLocale fr =
				new ComparableLocale(Locale.FRENCH);

			assertNotEquals(en, fr);
		}

		@Test
		@DisplayName(
			"should produce consistent hash codes " +
				"for equal instances"
		)
		void shouldProduceConsistentHashCodes() {
			final ComparableLocale en1 =
				new ComparableLocale(Locale.ENGLISH);
			final ComparableLocale en2 =
				new ComparableLocale(Locale.ENGLISH);

			assertEquals(en1.hashCode(), en2.hashCode());
		}

		@Test
		@DisplayName(
			"should not be equal to object of different type"
		)
		void shouldNotBeEqualToDifferentType() {
			final ComparableLocale en =
				new ComparableLocale(Locale.ENGLISH);

			assertNotEquals("en", en);
		}
	}

	@Nested
	@DisplayName("ToString")
	class ToStringTest {

		@Test
		@DisplayName(
			"should return language tag for simple locale"
		)
		void shouldReturnLanguageTagForSimpleLocale() {
			final ComparableLocale en =
				new ComparableLocale(Locale.ENGLISH);

			assertEquals("en", en.toString());
		}

		@Test
		@DisplayName(
			"should return language tag with country"
		)
		void shouldReturnLanguageTagWithCountry() {
			final ComparableLocale enUs =
				new ComparableLocale(Locale.US);

			assertEquals("en-US", enUs.toString());
		}

		@Test
		@DisplayName(
			"should return 'und' for Locale.ROOT"
		)
		void shouldReturnUndForLocaleRoot() {
			final ComparableLocale root =
				new ComparableLocale(Locale.ROOT);

			assertEquals("und", root.toString());
		}
	}

	@Nested
	@DisplayName("Serialization")
	class SerializationTest {

		@Test
		@DisplayName(
			"should survive Java serialization round-trip"
		)
		void shouldSurviveSerializationRoundTrip()
			throws Exception {
			final ComparableLocale original =
				new ComparableLocale(Locale.US);

			final ByteArrayOutputStream baos =
				new ByteArrayOutputStream();
			try (final ObjectOutputStream oos =
				     new ObjectOutputStream(baos)) {
				oos.writeObject(original);
			}

			final ByteArrayInputStream bais =
				new ByteArrayInputStream(baos.toByteArray());
			try (final ObjectInputStream ois =
				     new ObjectInputStream(bais)) {
				final ComparableLocale deserialized =
					(ComparableLocale) ois.readObject();

				assertEquals(original, deserialized);
				assertEquals(
					original.toString(),
					deserialized.toString()
				);
			}
		}
	}

	@Nested
	@DisplayName("Constructor edge cases")
	class ConstructorEdgeCaseTest {

		@Test
		@DisplayName(
			"should reject null locale at construction time"
		)
		void shouldRejectNullLocaleAtConstructionTime() {
			// @Nonnull on the field causes Lombok to generate
			// a null-check in the constructor
			assertThrows(
				NullPointerException.class,
				() -> new ComparableLocale(null)
			);
		}

		@Test
		@DisplayName("should expose locale via getter")
		void shouldExposeLocaleViaGetter() {
			final Locale usLocale = Locale.US;
			final ComparableLocale comparable =
				new ComparableLocale(usLocale);

			assertSame(usLocale, comparable.getLocale());
		}
	}
}
