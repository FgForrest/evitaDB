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

package io.evitadb.comparator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static io.evitadb.test.TestTags.COMPARATOR;
import static io.evitadb.test.TestTags.ENGINE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LocalizedStringComparator} verifying
 * locale-aware string comparison behaviour including national
 * character ordering.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@DisplayName("LocalizedStringComparator")
@Tag(ENGINE)
@Tag(COMPARATOR)
class LocalizedStringComparatorTest {

	@Nested
	@DisplayName("National character ordering")
	class NationalCharacterOrderingTest {

		@Test
		@DisplayName(
			"should sort Czech 'Ch' after 'C' but before 'D'"
		)
		void shouldSortCzechChAfterCButBeforeD() {
			final LocalizedStringComparator czechComparator =
				new LocalizedStringComparator(
					Collator.getInstance(new Locale("cs"))
				);

			// in Czech, "Ch" is a digraph sorted after "H"
			// so "Chladnicka" > "Citron"
			assertTrue(
				czechComparator.compare(
					"Chladnička", "Citrón"
				) > 0
			);
		}

		@Test
		@DisplayName(
			"should sort English 'Ch' before 'Ci'"
		)
		void shouldSortEnglishChBeforeCi() {
			final LocalizedStringComparator englishComparator =
				new LocalizedStringComparator(
					Collator.getInstance(Locale.ENGLISH)
				);

			// in English, "Ch" < "Ci" by standard ordering
			assertTrue(
				englishComparator.compare(
					"Chladnička", "Citrón"
				) < 0
			);
		}

		@Test
		@DisplayName(
			"should order Czech vs English differently"
			+ " for national characters"
		)
		void shouldCorrectlySortNationalCharacters() {
			final LocalizedStringComparator czechComparator =
				new LocalizedStringComparator(
					Collator.getInstance(new Locale("cs"))
				);
			final LocalizedStringComparator englishComparator =
				new LocalizedStringComparator(
					Collator.getInstance(Locale.ENGLISH)
				);

			assertTrue(
				czechComparator.compare(
					"Chladnička", "Citrón"
				) > 0
			);
			assertTrue(
				englishComparator.compare(
					"Chladnička", "Citrón"
				) < 0
			);
		}

		@Test
		@DisplayName(
			"should sort German umlauts correctly"
		)
		void shouldSortGermanUmlautsCorrectly() {
			final LocalizedStringComparator germanComparator =
				new LocalizedStringComparator(Locale.GERMAN);

			// "ä" should be treated near "a" in German
			final String[] array = {"Bär", "Aal", "Zug"};
			Arrays.sort(array, germanComparator);

			assertArrayEquals(
				new String[]{"Aal", "Bär", "Zug"},
				array
			);
		}
	}

	@Nested
	@DisplayName("Construction")
	class ConstructionTest {

		@Test
		@DisplayName(
			"should create comparator from Locale"
		)
		void shouldCreateComparatorFromLocale() {
			final LocalizedStringComparator comparator =
				new LocalizedStringComparator(Locale.ENGLISH);

			// verify it works by comparing simple strings
			assertTrue(comparator.compare("apple", "banana") < 0);
			assertTrue(comparator.compare("banana", "apple") > 0);
		}

		@Test
		@DisplayName(
			"should create comparator from Collator"
		)
		void shouldCreateComparatorFromCollator() {
			final Collator collator =
				Collator.getInstance(Locale.ENGLISH);
			final LocalizedStringComparator comparator =
				new LocalizedStringComparator(collator);

			assertTrue(comparator.compare("apple", "banana") < 0);
			assertTrue(comparator.compare("banana", "apple") > 0);
		}
	}

	@Nested
	@DisplayName("Basic comparison")
	class BasicComparisonTest {

		private final LocalizedStringComparator comparator =
			new LocalizedStringComparator(Locale.ENGLISH);

		@Test
		@DisplayName(
			"should return zero for identical strings"
		)
		void shouldReturnZeroForIdenticalStrings() {
			assertEquals(0, this.comparator.compare("hello", "hello"));
		}

		@Test
		@DisplayName(
			"should return zero for both empty strings"
		)
		void shouldReturnZeroForBothEmptyStrings() {
			assertEquals(0, this.comparator.compare("", ""));
		}

		@Test
		@DisplayName(
			"should sort empty string before non-empty"
		)
		void shouldSortEmptyStringBeforeNonEmpty() {
			assertTrue(this.comparator.compare("", "a") < 0);
			assertTrue(this.comparator.compare("a", "") > 0);
		}

		@Test
		@DisplayName(
			"should be case-aware by default"
		)
		void shouldBeCaseAwareByDefault() {
			// Collator default strength is TERTIARY,
			// which distinguishes case
			final int result =
				this.comparator.compare("Apple", "apple");

			// uppercase 'A' typically sorts before lowercase 'a'
			// in English locale with default Collator strength
			assertTrue(result != 0);
		}
	}

	@Nested
	@DisplayName("Sorting arrays")
	class SortingArraysTest {

		@Test
		@DisplayName(
			"should sort English strings in alphabetical order"
		)
		void shouldSortEnglishStringsInAlphabeticalOrder() {
			final LocalizedStringComparator comparator =
				new LocalizedStringComparator(Locale.ENGLISH);
			final String[] array =
				{"delta", "alpha", "charlie", "bravo"};

			Arrays.sort(array, comparator);

			assertArrayEquals(
				new String[]{
					"alpha", "bravo", "charlie", "delta"
				},
				array
			);
		}

		@Test
		@DisplayName(
			"should sort single-element array without error"
		)
		void shouldSortSingleElementArray() {
			final LocalizedStringComparator comparator =
				new LocalizedStringComparator(Locale.ENGLISH);
			final String[] array = {"solo"};

			Arrays.sort(array, comparator);

			assertArrayEquals(new String[]{"solo"}, array);
		}
	}

	@Nested
	@DisplayName("Cached path order equivalence with Collator")
	class CachedPathOrderEquivalenceTest {

		private final Locale[] testedLocales = {
			new Locale("cs"), Locale.ENGLISH, Locale.GERMAN
		};

		/**
		 * Builds a corpus of national-character strings in **both** canonical forms (NFC and NFD),
		 * with Czech `ch` digraph cases, case variants, combining-mark edge cases and the empty
		 * string - the inputs most likely to expose a divergence between the collation-key byte
		 * order served by the cache and the `Collator.compare` order it must replicate.
		 *
		 * @return corpus of strings for all-pairs order verification
		 */
		@Nonnull
		private static List<String> nationalCorpus() {
			final String[] base = {
				"", "a", "A", "z", "Z", "0", "9",
				"čaj", "Čaj", "čajník", "cukr", "Cukr", "cibule",
				"chata", "Chata", "chalupa", "cesta", "hata", "Hrnek", "hrnek",
				"háček", "hacek", "Háček", "říčka", "ricka", "Řeka", "reka",
				"žárovka", "zarovka", "Žárovka", "šroub", "sroub", "Šroubovák",
				"příliš", "žluťoučký", "kůň", "úpěl", "ďábelské", "ódy",
				"Müller", "Mueller", "Muller", "Größe", "Grosse", "straße", "strasse",
				"école", "ecole", "École", "élève", "cliché", "cœur", "coeur",
				"Šroubovák aku 12V", "Šroubovák aku 18V",
				"Žárovka LED E27 8W", "Žárovka LED E27 10W", "Žárovka LED E14 8W",
				"ệ", "ắ", "ṩ"
			};
			final List<String> corpus = new ArrayList<>(base.length * 2);
			for (final String value : base) {
				corpus.add(Normalizer.normalize(value, Normalizer.Form.NFD));
				corpus.add(Normalizer.normalize(value, Normalizer.Form.NFC));
			}
			return corpus;
		}

		@Test
		@DisplayName(
			"should order all corpus pairs exactly as Collator.compare in cs, en and de"
		)
		void shouldMatchCollatorSignOnAllPairs() {
			for (final Locale locale : this.testedLocales) {
				final LocalizedStringComparator cachedComparator =
					new LocalizedStringComparator(locale);
				final Collator reference = Collator.getInstance(locale);
				final List<String> corpus = nationalCorpus();
				for (final String left : corpus) {
					for (final String right : corpus) {
						assertEquals(
							Integer.signum(reference.compare(left, right)),
							Integer.signum(cachedComparator.compare(left, right)),
							() -> "locale " + locale + ": '" + left + "' vs '" + right + "'"
						);
					}
				}
			}
		}

		@Test
		@DisplayName(
			"should sort a shuffled corpus into the same total order as Collator"
		)
		void shouldSortShuffledCorpusIdenticallyToCollator() {
			for (final Locale locale : this.testedLocales) {
				final Collator reference = Collator.getInstance(locale);
				final List<String> corpus = nationalCorpus();
				final String[] expected = corpus.toArray(String[]::new);
				final String[] actual = corpus.toArray(String[]::new);
				Collections.shuffle(Arrays.asList(actual), new Random(42));

				Arrays.sort(expected, reference::compare);
				Arrays.sort(actual, new LocalizedStringComparator(locale));

				// distinct strings may compare equal (NFC vs NFD forms), so positions of ties may
				// legally differ after shuffling - assert order equivalence, not array identity
				for (int i = 0; i < expected.length; i++) {
					final int index = i;
					assertEquals(
						0, reference.compare(expected[i], actual[i]),
						() -> "locale " + locale + ": position " + index +
							" expected '" + expected[index] + "' but got '" + actual[index] + "'"
					);
				}
			}
		}

		@Test
		@DisplayName(
			"should treat NFC and NFD forms of the same text as equal"
		)
		void shouldTreatCanonicallyEquivalentFormsAsEqual() {
			final LocalizedStringComparator comparator =
				new LocalizedStringComparator(new Locale("cs"));
			final String nfc = Normalizer.normalize("žluťoučký kůň", Normalizer.Form.NFC);
			final String nfd = Normalizer.normalize("žluťoučký kůň", Normalizer.Form.NFD);

			// the two forms differ as Java strings, yet must collate as equal
			assertNotEquals(nfc, nfd);
			assertEquals(0, comparator.compare(nfc, nfd));
			assertEquals(0, comparator.compare(nfd, nfc));
		}
	}

	@Nested
	@DisplayName("Cached path robustness")
	class CachedPathRobustnessTest {

		@Test
		@DisplayName(
			"should keep Collator order under cache eviction pressure"
		)
		void shouldKeepCollatorOrderUnderCacheEvictionPressure() {
			final Locale locale = new Locale("cs");
			final LocalizedStringComparator cachedComparator =
				new LocalizedStringComparator(locale);
			final Collator reference = Collator.getInstance(locale);

			// far more distinct values than the cache has slots, sharing long prefixes and
			// recurring diacritic suffixes - forces constant slot collisions and evictions
			final String[] suffixes = {"černá", "bílá", "žlutá", "červená", "modrá"};
			final int valueCount = 20_000;
			final String[] values = new String[valueCount];
			for (int i = 0; i < valueCount; i++) {
				values[i] = "Výrobek řady č. " + (i % 977) + " " +
					suffixes[i % suffixes.length] + " " + i;
			}

			final Random rnd = new Random(42);
			for (int i = 0; i < 100_000; i++) {
				final String left = values[rnd.nextInt(valueCount)];
				final String right = values[rnd.nextInt(valueCount)];
				assertEquals(
					Integer.signum(reference.compare(left, right)),
					Integer.signum(cachedComparator.compare(left, right)),
					() -> "'" + left + "' vs '" + right + "'"
				);
			}
		}

		@Test
		@DisplayName(
			"should stay consistent when hammered from multiple threads"
		)
		void shouldStayConsistentUnderConcurrentComparisons() throws Exception {
			final Locale locale = new Locale("cs");
			final LocalizedStringComparator cachedComparator =
				new LocalizedStringComparator(locale);
			final Collator reference = Collator.getInstance(locale);

			final int valueCount = 512;
			final String[] values = new String[valueCount];
			for (int i = 0; i < valueCount; i++) {
				values[i] = "Židle čalouněná řada " + (i % 61) + " model " + i;
			}
			// precompute reference signs single-threaded (Collator.compare is synchronized)
			final int[][] expectedSigns = new int[valueCount][valueCount];
			for (int a = 0; a < valueCount; a++) {
				for (int b = 0; b < valueCount; b++) {
					expectedSigns[a][b] = Integer.signum(reference.compare(values[a], values[b]));
				}
			}

			final int threadCount = 8;
			final ExecutorService executor = Executors.newFixedThreadPool(threadCount);
			try {
				final List<Future<Boolean>> futures = new ArrayList<>(threadCount);
				for (int t = 0; t < threadCount; t++) {
					final long seed = 1_000L + t;
					futures.add(
						executor.submit(() -> {
							final Random rnd = new Random(seed);
							for (int i = 0; i < 200_000; i++) {
								final int a = rnd.nextInt(valueCount);
								final int b = rnd.nextInt(valueCount);
								final int actual = Integer.signum(
									cachedComparator.compare(values[a], values[b])
								);
								if (actual != expectedSigns[a][b]) {
									return false;
								}
							}
							return true;
						})
					);
				}
				for (final Future<Boolean> future : futures) {
					assertTrue(
						future.get(60, TimeUnit.SECONDS),
						"concurrent comparison diverged from Collator order"
					);
				}
			} finally {
				executor.shutdownNow();
			}
		}

		@Test
		@DisplayName(
			"should follow custom Collator configuration and bypass the cache"
		)
		void shouldFollowCustomCollatorConfiguration() {
			final Collator primaryStrength = Collator.getInstance(new Locale("cs"));
			primaryStrength.setStrength(Collator.PRIMARY);
			final LocalizedStringComparator customComparator =
				new LocalizedStringComparator(primaryStrength);
			final LocalizedStringComparator defaultComparator =
				new LocalizedStringComparator(new Locale("cs"));

			// PRIMARY strength ignores the accent difference; the default TERTIARY must not -
			// proving the custom-collator constructor is not served from the locale-default cache
			assertEquals(0, customComparator.compare("a", "á"));
			assertTrue(defaultComparator.compare("a", "á") != 0);
		}
	}

}
