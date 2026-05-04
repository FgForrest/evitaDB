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

import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
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

import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.SLOW;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Generational property-based stress tests for {@link SimpleHistogramIndex} and
 * {@link LocalizedHistogramIndex}. Runs randomized insert/remove operations across
 * many generations and verifies committed state against a JDK reference model.
 *
 * @author Jan Novotny (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("HistogramIndex generational proof")
@Tag(INDEXING)
@Tag(MANAGEMENT)
@Tag(HISTOGRAM)
class LongRunningHistogramIndexTest implements TimeBoundedTestSupport {

	private static final String HISTOGRAM_NAME = "priceHistogram";
	private static final String REFERENCE_NAME = "BRAND";

	@ParameterizedTest(name = "SimpleHistogramIndex should survive generational randomized test")
	@Tag(SLOW)
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
	@Tag(SLOW)
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
