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

package io.evitadb.index;

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SimpleHistogramIndex} and {@link LocalizedHistogramIndex} verifying
 * transactional insert/remove lifecycle, cardinality-gated filter index management,
 * and STM commit/rollback semantics.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("HistogramIndex transactional lifecycle")
class HistogramIndexTest implements TimeBoundedTestSupport {

	private static final String HISTOGRAM_NAME = "priceHistogram";
	private static final String REFERENCE_NAME = "BRAND";

	/**
	 * Tests for {@link SimpleHistogramIndex} — non-localized histogram with direct field storage.
	 */
	@Nested
	@DisplayName("SimpleHistogramIndex (non-localized)")
	class SimpleHistogramIndexTest {

		private SimpleHistogramIndex histogramIndex;

		@BeforeEach
		void setUp() {
			this.histogramIndex = new SimpleHistogramIndex(HISTOGRAM_NAME, REFERENCE_NAME, Integer.class);
		}

		@Test
		@DisplayName("should insert histogram value transactionally")
		void shouldInsertHistogramValueTransactionally() {
			assertStateAfterCommit(
				this.histogramIndex,
				original -> {
					original.insertValue(null, 42, 10);

					final FilterIndex filter = original.getFilterIndex(null);
					assertNotNull(filter, "Filter index should be visible inside transaction");
					assertTrue(filter.getRecordsEqualTo(42).contains(10));
				},
				(original, committed) -> {
					assertNull(original.getFilterIndex(null),
						"Baseline should not contain data after commit");
					assertNotNull(committed, "Committed copy must not be null");
					final FilterIndex committedFilter = committed.getFilterIndex(null);
					assertNotNull(committedFilter, "Committed copy should have filter index");
					assertEquals(1, committedFilter.getRecordsEqualTo(42).size());
					assertTrue(committedFilter.getRecordsEqualTo(42).contains(10));
				}
			);
		}

		@Test
		@DisplayName("should rollback histogram value insertion")
		void shouldRollbackHistogramValueInsertion() {
			assertStateAfterRollback(
				this.histogramIndex,
				original -> original.insertValue(null, 42, 10),
				(original, committed) -> {
					assertNull(original.getFilterIndex(null),
						"Baseline should not contain data after rollback");
					assertNull(committed, "Committed should be null after rollback");
				}
			);
		}

		@Test
		@DisplayName("should track cardinality lifecycle on commit")
		void shouldTrackCardinalityLifecycleOnCommit() {
			assertStateAfterCommit(
				this.histogramIndex,
				original -> {
					original.insertValue(null, 55, 10);
					original.insertValue(null, 55, 20);
				},
				(original, committed) -> {
					assertNotNull(committed, "Committed copy must not be null");
					final Bitmap records = committed.getFilterIndex(null).getRecordsEqualTo(55);
					assertEquals(2, records.size());
					assertTrue(records.contains(10));
					assertTrue(records.contains(20));
				}
			);
		}

		@Test
		@DisplayName("should remove value and clean up empty filter index")
		void shouldRemoveValueAndCleanUpEmptyIndex() {
			assertStateAfterCommit(
				this.histogramIndex,
				original -> {
					original.insertValue(null, 42, 10);
					original.removeValue(null, 42, 10);
					assertNull(original.getFilterIndex(null),
						"FilterIndex should be removed when last value is removed");
				},
				(original, committed) -> {
					assertNotNull(committed, "Committed copy must not be null");
					assertNull(committed.getFilterIndex(null),
						"Committed copy should not contain data after removal");
					assertTrue(committed.isEmpty(), "Should be empty after removal");
				}
			);
		}

		@Test
		@DisplayName("should retain value when one of two owners is removed")
		void shouldRetainValueWhenOneOfTwoOwnersRemoved() {
			assertStateAfterCommit(
				this.histogramIndex,
				original -> {
					original.insertValue(null, 55, 10);
					original.insertValue(null, 55, 20);
					original.removeValue(null, 55, 10);

					final FilterIndex filter = original.getFilterIndex(null);
					assertNotNull(filter, "FilterIndex should remain for cardinality > 0");
					assertEquals(1, filter.getRecordsEqualTo(55).size());
					assertTrue(filter.getRecordsEqualTo(55).contains(20));
				},
				(original, committed) -> {
					assertNotNull(committed, "Committed copy must not be null");
					final Bitmap records = committed.getFilterIndex(null).getRecordsEqualTo(55);
					assertEquals(1, records.size());
					assertTrue(records.contains(20));
				}
			);
		}

		@Test
		@DisplayName("should commit removal across two transactions")
		void shouldCommitRemovalAcrossTransactions() {
			final AtomicReference<HistogramIndex> committedRef = new AtomicReference<>();
			assertStateAfterCommit(
				this.histogramIndex,
				original -> original.insertValue(null, 42, 10),
				(original, committed) -> committedRef.set(committed)
			);

			assertStateAfterCommit(
				committedRef.get(),
				original -> original.removeValue(null, 42, 10),
				(original, committed) -> {
					assertNotNull(original.getFilterIndex(null),
						"Original should retain data");
					assertNotNull(committed, "Second committed copy must not be null");
					assertNull(committed.getFilterIndex(null),
						"Second commit should have no data");
				}
			);
		}

		@Test
		@DisplayName("should rollback removal preserving committed state")
		void shouldRollbackRemovalPreservingCommittedState() {
			final AtomicReference<HistogramIndex> committedRef = new AtomicReference<>();
			assertStateAfterCommit(
				this.histogramIndex,
				original -> original.insertValue(null, 42, 10),
				(original, committed) -> committedRef.set(committed)
			);

			assertStateAfterRollback(
				committedRef.get(),
				original -> original.removeValue(null, 42, 10),
				(original, committed) -> {
					final FilterIndex filter = original.getFilterIndex(null);
					assertNotNull(filter, "Data should survive rollback");
					assertTrue(filter.getRecordsEqualTo(42).contains(10));
					assertNull(committed, "Committed should be null after rollback");
				}
			);
		}

		@Test
		@DisplayName("should report empty/non-empty correctly")
		void shouldReportEmptyNonEmptyCorrectly() {
			assertTrue(this.histogramIndex.isEmpty(), "New index should be empty");
			assertStateAfterCommit(
				this.histogramIndex,
				original -> {
					original.insertValue(null, 99, 10);
					assertFalse(original.isEmpty(), "Index with data should not be empty");
				},
				(original, committed) -> {
					assertNotNull(committed, "Committed copy must not be null");
					assertFalse(committed.isEmpty(), "Committed index with data should not be empty");
				}
			);
		}

		@Test
		@DisplayName("should handle insert and remove in same transaction")
		void shouldHandleInsertAndRemoveInSameTransaction() {
			assertStateAfterCommit(
				this.histogramIndex,
				original -> {
					original.insertValue(null, 77, 10);
					assertNotNull(original.getFilterIndex(null));
					original.removeValue(null, 77, 10);
					assertNull(original.getFilterIndex(null),
						"FilterIndex should be cleaned up after same-transaction remove");
				},
				(original, committed) -> {
					assertNotNull(committed, "Committed copy must not be null");
					assertTrue(committed.isEmpty(), "Committed should be empty");
				}
			);
		}

		@Test
		@DisplayName("should track cardinality for same owner same value via two references")
		void shouldTrackCardinalityForSameOwnerSameValueTwoReferences() {
			assertStateAfterCommit(
				this.histogramIndex,
				original -> {
					original.insertValue(null, 42, 10);
					original.insertValue(null, 42, 10);
					original.removeValue(null, 42, 10);

					final FilterIndex filter = original.getFilterIndex(null);
					assertNotNull(filter, "FilterIndex should remain — cardinality still > 0");
					assertTrue(filter.getRecordsEqualTo(42).contains(10));
				},
				(original, committed) -> {
					assertNotNull(committed, "Committed copy must not be null");
					assertTrue(committed.getFilterIndex(null).getRecordsEqualTo(42).contains(10));
				}
			);
		}

		@Test
		@DisplayName("should preserve data across commit and allow continued insertion")
		void shouldPreserveDataAcrossCommitAndAllowContinuedInsertion() {
			final AtomicReference<HistogramIndex> committedRef = new AtomicReference<>();
			assertStateAfterCommit(
				this.histogramIndex,
				original -> original.insertValue(null, 42, 10),
				(original, committed) -> committedRef.set(committed)
			);

			assertStateAfterCommit(
				committedRef.get(),
				original -> original.insertValue(null, 99, 20),
				(original, committed) -> {
					assertNotNull(committed, "Second committed copy must not be null");
					final FilterIndex filter = committed.getFilterIndex(null);
					assertNotNull(filter, "Filter should exist after second commit");
					assertTrue(filter.getRecordsEqualTo(42).contains(10),
						"Value 42 from first transaction should persist");
					assertTrue(filter.getRecordsEqualTo(99).contains(20),
						"Value 99 from second transaction should be present");
				}
			);
		}

		@Test
		@DisplayName("should invoke forEachLocale with null locale when data exists")
		void shouldInvokeForEachLocaleWithNullLocale() {
			assertStateAfterCommit(
				this.histogramIndex,
				original -> {
					original.insertValue(null, 42, 10);
					final int[] count = {0};
					original.forEachLocale((name, locale) -> {
						assertEquals(HISTOGRAM_NAME, name);
						assertNull(locale, "Non-localized histogram should have null locale");
						count[0]++;
					});
					assertEquals(1, count[0], "forEachLocale should be called exactly once");
				},
				(original, committed) -> assertNotNull(committed)
			);
		}

		@Test
		@DisplayName("should throw when removing from empty index")
		void shouldThrowWhenRemovingFromEmptyIndex() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> this.histogramIndex.removeValue(null, 42, 10)
			);
		}
	}

	/**
	 * Tests for {@link LocalizedHistogramIndex} — localized histogram with per-locale maps.
	 */
	@Nested
	@DisplayName("LocalizedHistogramIndex (localized)")
	class LocalizedHistogramIndexTest {

		private LocalizedHistogramIndex histogramIndex;

		@BeforeEach
		void setUp() {
			this.histogramIndex = new LocalizedHistogramIndex(HISTOGRAM_NAME, REFERENCE_NAME, Integer.class);
		}

		@Test
		@DisplayName("should insert localized histogram values transactionally")
		void shouldInsertLocalizedHistogramValuesTransactionally() {
			assertStateAfterCommit(
				this.histogramIndex,
				original -> {
					original.insertValue(Locale.ENGLISH, 100, 10);
					original.insertValue(new Locale("cs"), 200, 10);
				},
				(original, committed) -> {
					assertNotNull(committed, "Committed copy must not be null");

					final FilterIndex enFilter = committed.getFilterIndex(Locale.ENGLISH);
					assertNotNull(enFilter, "EN locale filter index should exist");
					assertTrue(enFilter.getRecordsEqualTo(100).contains(10));

					final FilterIndex csFilter = committed.getFilterIndex(new Locale("cs"));
					assertNotNull(csFilter, "CS locale filter index should exist");
					assertTrue(csFilter.getRecordsEqualTo(200).contains(10));

					assertNull(committed.getFilterIndex(null),
						"Non-localized filter index should not exist");
				}
			);
		}

		@Test
		@DisplayName("should remove localized histogram values independently")
		void shouldRemoveLocalizedHistogramValuesIndependently() {
			assertStateAfterCommit(
				this.histogramIndex,
				original -> {
					original.insertValue(Locale.ENGLISH, 100, 10);
					original.insertValue(new Locale("cs"), 200, 10);

					original.removeValue(Locale.ENGLISH, 100, 10);

					assertNull(original.getFilterIndex(Locale.ENGLISH));
					assertNotNull(original.getFilterIndex(new Locale("cs")));
				},
				(original, committed) -> {
					assertNotNull(committed, "Committed copy must not be null");
					assertNull(committed.getFilterIndex(Locale.ENGLISH));
					final FilterIndex csFilter = committed.getFilterIndex(new Locale("cs"));
					assertNotNull(csFilter, "CS locale histogram should remain");
					assertTrue(csFilter.getRecordsEqualTo(200).contains(10));
				}
			);
		}

		@Test
		@DisplayName("should rollback localized histogram value insertion")
		void shouldRollbackLocalizedHistogramValueInsertion() {
			assertStateAfterRollback(
				this.histogramIndex,
				original -> original.insertValue(Locale.ENGLISH, 42, 10),
				(original, committed) -> {
					assertNull(original.getFilterIndex(Locale.ENGLISH),
						"Baseline should not contain data after rollback");
					assertNull(committed, "Committed should be null after rollback");
				}
			);
		}

		@Test
		@DisplayName("should track cardinality for localized values")
		void shouldTrackCardinalityForLocalizedValues() {
			assertStateAfterCommit(
				this.histogramIndex,
				original -> {
					original.insertValue(Locale.ENGLISH, 55, 10);
					original.insertValue(Locale.ENGLISH, 55, 20);
				},
				(original, committed) -> {
					assertNotNull(committed, "Committed copy must not be null");
					final Bitmap records = committed.getFilterIndex(Locale.ENGLISH).getRecordsEqualTo(55);
					assertEquals(2, records.size());
				}
			);
		}

		@Test
		@DisplayName("should report empty/non-empty correctly")
		void shouldReportEmptyNonEmptyCorrectly() {
			assertTrue(this.histogramIndex.isEmpty(), "New index should be empty");
			assertStateAfterCommit(
				this.histogramIndex,
				original -> {
					original.insertValue(Locale.ENGLISH, 99, 10);
					assertFalse(original.isEmpty(), "Index with data should not be empty");
				},
				(original, committed) -> {
					assertNotNull(committed, "Committed copy must not be null");
					assertFalse(committed.isEmpty());
				}
			);
		}

		@Test
		@DisplayName("should invoke forEachLocale for each locale with data")
		void shouldInvokeForEachLocaleForEachLocaleWithData() {
			assertStateAfterCommit(
				this.histogramIndex,
				original -> {
					original.insertValue(Locale.ENGLISH, 42, 10);
					original.insertValue(new Locale("cs"), 99, 20);
					final int[] count = {0};
					original.forEachLocale((name, locale) -> {
						assertEquals(HISTOGRAM_NAME, name);
						assertNotNull(locale, "Localized histogram should have non-null locale");
						count[0]++;
					});
					assertEquals(2, count[0], "forEachLocale should be called for each locale");
				},
				(original, committed) -> assertNotNull(committed)
			);
		}

		@Test
		@DisplayName("should commit removal across two transactions")
		void shouldCommitRemovalAcrossTransactions() {
			final AtomicReference<HistogramIndex> committedRef = new AtomicReference<>();
			assertStateAfterCommit(
				this.histogramIndex,
				original -> original.insertValue(Locale.ENGLISH, 42, 10),
				(original, committed) -> committedRef.set(committed)
			);

			assertStateAfterCommit(
				committedRef.get(),
				original -> original.removeValue(Locale.ENGLISH, 42, 10),
				(original, committed) -> {
					assertNotNull(original.getFilterIndex(Locale.ENGLISH),
						"Original should retain data");
					assertNotNull(committed, "Second committed copy must not be null");
					assertNull(committed.getFilterIndex(Locale.ENGLISH),
						"Second commit should have no data for EN");
				}
			);
		}

		@Test
		@DisplayName("should preserve data across commit and allow continued insertion")
		void shouldPreserveDataAcrossCommitAndAllowContinuedInsertion() {
			final AtomicReference<HistogramIndex> committedRef = new AtomicReference<>();
			assertStateAfterCommit(
				this.histogramIndex,
				original -> original.insertValue(Locale.ENGLISH, 42, 10),
				(original, committed) -> committedRef.set(committed)
			);

			assertStateAfterCommit(
				committedRef.get(),
				original -> original.insertValue(new Locale("cs"), 99, 20),
				(original, committed) -> {
					assertNotNull(committed, "Second committed copy must not be null");
					assertTrue(committed.getFilterIndex(Locale.ENGLISH)
						.getRecordsEqualTo(42).contains(10),
						"EN value from first transaction should persist");
					assertTrue(committed.getFilterIndex(new Locale("cs"))
						.getRecordsEqualTo(99).contains(20),
						"CS value from second transaction should be present");
				}
			);
		}

		@Test
		@DisplayName("should throw when inserting with null locale")
		void shouldThrowWhenInsertingWithNullLocale() {
			assertThrows(
				NullPointerException.class,
				() -> this.histogramIndex.insertValue(null, 42, 10)
			);
		}

		@Test
		@DisplayName("should throw when removing with null locale")
		void shouldThrowWhenRemovingWithNullLocale() {
			assertThrows(
				NullPointerException.class,
				() -> this.histogramIndex.removeValue(null, 42, 10)
			);
		}

		@Test
		@DisplayName("should throw when removing from empty index")
		void shouldThrowWhenRemovingFromEmptyIndex() {
			assertThrows(
				GenericEvitaInternalError.class,
				() -> this.histogramIndex.removeValue(Locale.ENGLISH, 42, 10)
			);
		}
	}

	// ======================== Generational Tests ========================

	@ParameterizedTest(name = "SimpleHistogramIndex should survive generational randomized test")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	void shouldSurviveSimpleHistogramGenerationalTest(GenerationalTestInput input) {
		// reference model: value -> (ownerPK -> cardinality count)
		final Map<Integer, Map<Integer, Integer>> referenceModel = new HashMap<>(64);

		runFor(
			input,
			50,
			new SimpleTestState(
				new StringBuilder(256),
				new SimpleHistogramIndex(HISTOGRAM_NAME, REFERENCE_NAME, Integer.class)
			),
			(random, testState) -> {
				final StringBuilder code = testState.code();
				code.append("--- Generation ---\n");
				appendReferenceState(code, referenceModel);

				final SimpleHistogramIndex index = testState.index();
				final AtomicReference<HistogramIndex> committedRef = new AtomicReference<>();

				assertStateAfterCommit(
					index,
					original -> {
						try {
							final int opsCount = random.nextInt(50);
							for (int i = 0; i < opsCount; i++) {
								final int totalEntries = countEntries(referenceModel);
								if ((random.nextBoolean() || totalEntries < 5) && totalEntries < 100) {
									final int value = random.nextInt(50);
									final int ownerPK = random.nextInt(30) + 1;
									code.append("insert(").append(value)
										.append(", ").append(ownerPK).append(")\n");
									original.insertValue(null, value, ownerPK);
									referenceModel
										.computeIfAbsent(value, k -> new HashMap<>(8))
										.merge(ownerPK, 1, Integer::sum);
								} else {
									final int[] entry = pickRandomEntry(random, referenceModel);
									if (entry != null) {
										code.append("remove(").append(entry[0])
											.append(", ").append(entry[1]).append(")\n");
										original.removeValue(null, entry[0], entry[1]);
										decrementCardinality(referenceModel, entry[0], entry[1]);
									}
								}
							}
						} catch (Exception ex) {
							fail("\n" + code, ex);
						}
					},
					(original, committed) -> {
						verifyHistogramState(committed, null, referenceModel, code);
						committedRef.set(committed);
					}
				);

				return new SimpleTestState(
					new StringBuilder(256),
					(SimpleHistogramIndex) committedRef.get()
				);
			}
		);
	}

	@ParameterizedTest(name = "LocalizedHistogramIndex should survive generational randomized test")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	void shouldSurviveLocalizedHistogramGenerationalTest(GenerationalTestInput input) {
		final Locale[] locales = {Locale.ENGLISH, new Locale("cs"), Locale.GERMAN};
		// reference model: locale -> (value -> (ownerPK -> cardinality count))
		final Map<Locale, Map<Integer, Map<Integer, Integer>>> referenceModel = new HashMap<>(8);

		runFor(
			input,
			50,
			new LocalizedTestState(
				new StringBuilder(256),
				new LocalizedHistogramIndex(HISTOGRAM_NAME, REFERENCE_NAME, Integer.class)
			),
			(random, testState) -> {
				final StringBuilder code = testState.code();
				code.append("--- Generation ---\n");

				final LocalizedHistogramIndex index = testState.index();
				final AtomicReference<HistogramIndex> committedRef = new AtomicReference<>();

				assertStateAfterCommit(
					index,
					original -> {
						try {
							final int opsCount = random.nextInt(50);
							for (int i = 0; i < opsCount; i++) {
								final Locale locale = locales[random.nextInt(locales.length)];
								final Map<Integer, Map<Integer, Integer>> localeModel =
									referenceModel.computeIfAbsent(locale, k -> new HashMap<>(64));
								final int totalEntries = countEntries(localeModel);
								if ((random.nextBoolean() || totalEntries < 5)
									&& totalEntries < 80) {
									final int value = random.nextInt(50);
									final int ownerPK = random.nextInt(30) + 1;
									code.append("insert(").append(locale).append(", ")
										.append(value).append(", ")
										.append(ownerPK).append(")\n");
									original.insertValue(locale, value, ownerPK);
									localeModel
										.computeIfAbsent(value, k -> new HashMap<>(8))
										.merge(ownerPK, 1, Integer::sum);
								} else {
									final int[] entry =
										pickRandomEntry(random, localeModel);
									if (entry != null) {
										code.append("remove(").append(locale).append(", ")
											.append(entry[0]).append(", ")
											.append(entry[1]).append(")\n");
										original.removeValue(locale, entry[0], entry[1]);
										decrementCardinality(
											localeModel, entry[0], entry[1]
										);
									}
								}
							}
						} catch (Exception ex) {
							fail("\n" + code, ex);
						}
					},
					(original, committed) -> {
						for (Locale locale : locales) {
							final Map<Integer, Map<Integer, Integer>> localeModel =
								referenceModel.getOrDefault(locale, Map.of());
							verifyHistogramState(committed, locale, localeModel, code);
						}
						committedRef.set(committed);
					}
				);

				return new LocalizedTestState(
					new StringBuilder(256),
					(LocalizedHistogramIndex) committedRef.get()
				);
			}
		);
	}

	// ======================== Helper Methods ========================

	/**
	 * Counts total (value, ownerPK) pairs with cardinality > 0 in the reference model.
	 */
	private int countEntries(@Nonnull Map<Integer, Map<Integer, Integer>> model) {
		int count = 0;
		for (Map<Integer, Integer> ownerMap : model.values()) {
			for (int cardinality : ownerMap.values()) {
				if (cardinality > 0) {
					count++;
				}
			}
		}
		return count;
	}

	/**
	 * Picks a random (value, ownerPK) pair with cardinality > 0, returns `{value, ownerPK}` or null.
	 */
	@Nullable
	private int[] pickRandomEntry(
		@Nonnull Random random,
		@Nonnull Map<Integer, Map<Integer, Integer>> model
	) {
		final List<int[]> entries = new ArrayList<>(64);
		for (Map.Entry<Integer, Map<Integer, Integer>> valueEntry : model.entrySet()) {
			for (Map.Entry<Integer, Integer> ownerEntry : valueEntry.getValue().entrySet()) {
				if (ownerEntry.getValue() > 0) {
					entries.add(new int[]{valueEntry.getKey(), ownerEntry.getKey()});
				}
			}
		}
		return entries.isEmpty() ? null : entries.get(random.nextInt(entries.size()));
	}

	/**
	 * Decrements cardinality for (value, ownerPK) and cleans up zero-cardinality entries.
	 */
	private void decrementCardinality(
		@Nonnull Map<Integer, Map<Integer, Integer>> model,
		int value,
		int ownerPK
	) {
		final Map<Integer, Integer> ownerMap = model.get(value);
		if (ownerMap != null) {
			final int newCardinality = ownerMap.merge(ownerPK, -1, Integer::sum);
			if (newCardinality <= 0) {
				ownerMap.remove(ownerPK);
				if (ownerMap.isEmpty()) {
					model.remove(value);
				}
			}
		}
	}

	/**
	 * Verifies committed histogram state matches the reference model for a given locale.
	 */
	private void verifyHistogramState(
		@Nonnull HistogramIndex committed,
		@Nullable Locale locale,
		@Nonnull Map<Integer, Map<Integer, Integer>> model,
		@Nonnull StringBuilder code
	) {
		final FilterIndex filterIndex = committed.getFilterIndex(locale);

		// collect expected ownerPKs per value and verify
		boolean hasAnyData = false;
		for (Map.Entry<Integer, Map<Integer, Integer>> valueEntry : model.entrySet()) {
			final int value = valueEntry.getKey();
			final List<Integer> expectedOwners = new ArrayList<>(8);
			for (Map.Entry<Integer, Integer> ownerEntry : valueEntry.getValue().entrySet()) {
				if (ownerEntry.getValue() > 0) {
					expectedOwners.add(ownerEntry.getKey());
					hasAnyData = true;
				}
			}
			if (!expectedOwners.isEmpty()) {
				assertNotNull(
					filterIndex,
					"FilterIndex should exist for locale " + locale
						+ " — expected data present\n" + code
				);
				final Bitmap actualRecords = filterIndex.getRecordsEqualTo(value);
				assertEquals(
					expectedOwners.size(), actualRecords.size(),
					"Record count mismatch for value " + value
						+ " locale " + locale + "\n" + code
				);
				for (int ownerPK : expectedOwners) {
					assertTrue(
						actualRecords.contains(ownerPK),
						"Missing ownerPK " + ownerPK + " for value " + value
							+ " locale " + locale + "\n" + code
					);
				}
			}
		}

		if (!hasAnyData) {
			assertTrue(
				filterIndex == null || filterIndex.getAllRecords().isEmpty(),
				"FilterIndex should be null or empty when no data expected, locale "
					+ locale + "\n" + code
			);
		}
	}

	/**
	 * Appends reference model state to the debug code buffer.
	 */
	private void appendReferenceState(
		@Nonnull StringBuilder code,
		@Nonnull Map<Integer, Map<Integer, Integer>> model
	) {
		code.append("State: {");
		for (Map.Entry<Integer, Map<Integer, Integer>> entry : model.entrySet()) {
			code.append(entry.getKey()).append('=').append(entry.getValue()).append(", ");
		}
		code.append("}\n");
	}

	/**
	 * State holder for {@link SimpleHistogramIndex} generational test.
	 */
	private record SimpleTestState(@Nonnull StringBuilder code, @Nonnull SimpleHistogramIndex index) {}

	/**
	 * State holder for {@link LocalizedHistogramIndex} generational test.
	 */
	private record LocalizedTestState(@Nonnull StringBuilder code, @Nonnull LocalizedHistogramIndex index) {}
}
