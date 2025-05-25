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

package io.evitadb.index.invertedIndex;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.comparator.LocalizedStringComparator;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyReport;
import io.evitadb.dataType.ConsistencySensitiveDataStructure.ConsistencyState;
import io.evitadb.index.attribute.FilterIndex;
import io.evitadb.index.bitmap.TransactionalBitmap;
import io.evitadb.store.index.serializer.InvertedIndexSerializer;
import io.evitadb.store.index.serializer.TransactionalIntegerBitmapSerializer;
import io.evitadb.store.index.serializer.ValueToRecordBitmapSerializer;
import io.evitadb.test.duration.TimeArgumentProvider;
import io.evitadb.test.duration.TimeArgumentProvider.GenerationalTestInput;
import io.evitadb.test.duration.TimeBoundedTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.test.TestConstants.LONG_RUNNING_TEST;
import static io.evitadb.utils.AssertionUtils.assertIteratorContains;
import static io.evitadb.utils.AssertionUtils.assertStateAfterCommit;
import static io.evitadb.utils.AssertionUtils.assertStateAfterRollback;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This test verifies contract of {@link InvertedIndex} data structure.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
class InvertedIndexTest implements TimeBoundedTestSupport {
	public static final String[] NATIONAL_SPECIFIC_WORDS = {
		"chléb",
		"hlína",
		"chata",
		"chalupa",
		"chatka",
		"chechtat",
		"chirurg",
		"chodba",
		"chodník",
		"choroba",
		"chrám",
		"chránit",
		"chroust",
		"chřest",
		"chuť",
		"chůze",
		"hajný",
		"hajzl",
		"haló",
		"halucinace",
		"hanba",
		"hanka",
		"harfa",
		"harpunář",
		"hasák",
		"hasič",
		"hasička",
		"hasičský",
		"hasit",
		"haslo",
		"házat",
		"hejtman",
		"hejtmanka",
		"herna",
		"hezký",
		"hlad",
		"hledat",
		"hlídka",
		"hloupý",
		"hnůj",
		"hodina",
		"hodiny",
		"hojnost",
		"holka",
		"holub",
		"horko",
		"horší",
		"hostina"
	};
	private final InvertedIndex tested = new InvertedIndex(FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder());

	@BeforeEach
	void setUp() {
		this.tested.addRecord(5, 1);
		this.tested.addRecord(5, 20);
		this.tested.addRecord(10, 3);
		this.tested.addRecord(15, 2);
		this.tested.addRecord(15, 4);
		this.tested.addRecord(20, 5);
	}

	@Test
	void shouldReturnValuesForRecord() {
		this.tested.addRecord(50, 1);
		this.tested.addRecord(100, 3);

		assertArrayEquals(this.tested.getValuesForRecord(1, Integer.class), new Integer[]{5, 50});
		assertArrayEquals(this.tested.getValuesForRecord(3, Integer.class), new Integer[]{10, 100});
	}

	@Test
	void shouldAddTransactionalItemsAndRollback() {
		assertStateAfterRollback(
			this.tested,
			original -> {
				original.addRecord(5, 7);
				original.addRecord(12, 18);
				original.addRecord(1, 10);
				original.addRecord(20, 11);

				assertArrayEquals(
					new ValueToRecordBitmap[]{
						new ValueToRecordBitmap(1, 10),
						new ValueToRecordBitmap(5, 1, 7, 20),
						new ValueToRecordBitmap(10, 3),
						new ValueToRecordBitmap(12, 18),
						new ValueToRecordBitmap(15, 2, 4),
						new ValueToRecordBitmap(20, 5, 11)
					},
					original.getValueToRecordBitmap()
				);
			},
			(original, committed) -> {
				assertNull(committed);
				assertArrayEquals(
					new ValueToRecordBitmap[]{
						new ValueToRecordBitmap(5, 1, 20),
						new ValueToRecordBitmap(10, 3),
						new ValueToRecordBitmap(15, 2, 4),
						new ValueToRecordBitmap(20, 5)
					},
					original.getValueToRecordBitmap()
				);
			}
		);
	}

	@Test
	void shouldAddSingleNewTransactionalItemAndCommit() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.addRecord(55, 78);

				assertArrayEquals(
					new ValueToRecordBitmap[]{
						new ValueToRecordBitmap(5, 1, 20),
						new ValueToRecordBitmap(10, 3),
						new ValueToRecordBitmap(15, 2, 4),
						new ValueToRecordBitmap(20, 5),
						new ValueToRecordBitmap(55, 78)
					},
					original.getValueToRecordBitmap()
				);
			},
			(original, committed) -> {
				assertArrayEquals(
					new ValueToRecordBitmap[]{
						new ValueToRecordBitmap(5, 1, 20),
						new ValueToRecordBitmap(10, 3),
						new ValueToRecordBitmap(15, 2, 4),
						new ValueToRecordBitmap(20, 5)
					},
					original.getValueToRecordBitmap()
				);
				assertArrayEquals(
					new ValueToRecordBitmap[]{
						new ValueToRecordBitmap(5, 1, 20),
						new ValueToRecordBitmap(10, 3),
						new ValueToRecordBitmap(15, 2, 4),
						new ValueToRecordBitmap(20, 5),
						new ValueToRecordBitmap(55, 78)
					},
					committed.getValueToRecordBitmap()
				);
			}
		);
	}

	@Test
	void shouldRemoveSingleTransactionalItemAndCommit() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.removeRecord(10, 3);

				assertArrayEquals(
					new ValueToRecordBitmap[]{
						new ValueToRecordBitmap(5, 1, 20),
						new ValueToRecordBitmap(15, 2, 4),
						new ValueToRecordBitmap(20, 5)
					},
					original.getValueToRecordBitmap()
				);
			},
			(original, committed) -> {
				assertArrayEquals(
					new ValueToRecordBitmap[]{
						new ValueToRecordBitmap(5, 1, 20),
						new ValueToRecordBitmap(10, 3),
						new ValueToRecordBitmap(15, 2, 4),
						new ValueToRecordBitmap(20, 5)
					},
					original.getValueToRecordBitmap()
				);
				assertArrayEquals(
					new ValueToRecordBitmap[]{
						new ValueToRecordBitmap(5, 1, 20),
						new ValueToRecordBitmap(15, 2, 4),
						new ValueToRecordBitmap(20, 5)
					},
					committed.getValueToRecordBitmap()
				);
			}
		);
	}

	@Test
	void shouldAddTransactionalItemsAndCommit() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.addRecord(5, 7);
				original.addRecord(12, 18);
				original.addRecord(1, 10);
				original.addRecord(20, 11);

				assertArrayEquals(
					new ValueToRecordBitmap[]{
						new ValueToRecordBitmap(1, 10),
						new ValueToRecordBitmap(5, 1, 7, 20),
						new ValueToRecordBitmap(10, 3),
						new ValueToRecordBitmap(12, 18),
						new ValueToRecordBitmap(15, 2, 4),
						new ValueToRecordBitmap(20, 5, 11)
					},
					original.getValueToRecordBitmap()
				);
			},
			(original, committed) -> {
				assertArrayEquals(
					new ValueToRecordBitmap[]{
						new ValueToRecordBitmap(5, 1, 20),
						new ValueToRecordBitmap(10, 3),
						new ValueToRecordBitmap(15, 2, 4),
						new ValueToRecordBitmap(20, 5)
					},
					original.getValueToRecordBitmap()
				);
				assertArrayEquals(
					new ValueToRecordBitmap[]{
						new ValueToRecordBitmap(1, 10),
						new ValueToRecordBitmap(5, 1, 7, 20),
						new ValueToRecordBitmap(10, 3),
						new ValueToRecordBitmap(12, 18),
						new ValueToRecordBitmap(15, 2, 4),
						new ValueToRecordBitmap(20, 5, 11)
					},
					committed.getValueToRecordBitmap()
				);
			}
		);
	}

	@Test
	void shouldAddAndRemoveItemsInTransaction() {
		assertStateAfterCommit(
			new InvertedIndex(FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder()),
			original -> {
				original.addRecord(5, 7);
				original.addRecord(12, 18);
				original.removeRecord(5, 7);
				original.removeRecord(12, 18);

				assertArrayEquals(
					new ValueToRecordBitmap[0],
					original.getValueToRecordBitmap()
				);
			},
			(original, committed) -> {
				assertArrayEquals(
					new ValueToRecordBitmap[0],
					original.getValueToRecordBitmap()
				);
				assertArrayEquals(
					new ValueToRecordBitmap[0],
					committed.getValueToRecordBitmap()
				);
			}
		);
	}

	@Test
	void shouldShrinkHistogramOnRemovingItems() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				original.removeRecord(5, 1);
				original.removeRecord(10, 3);
				original.removeRecord(20, 5);

				assertArrayEquals(
					new ValueToRecordBitmap[]{
						new ValueToRecordBitmap(5, 20),
						new ValueToRecordBitmap(15, 2, 4)
					},
					original.getValueToRecordBitmap()
				);
			},
			(original, committed) -> {
				assertArrayEquals(
					new ValueToRecordBitmap[]{
						new ValueToRecordBitmap(5, 1, 20),
						new ValueToRecordBitmap(10, 3),
						new ValueToRecordBitmap(15, 2, 4),
						new ValueToRecordBitmap(20, 5)
					},
					original.getValueToRecordBitmap()
				);
				assertArrayEquals(
					new ValueToRecordBitmap[]{
						new ValueToRecordBitmap(5, 20),
						new ValueToRecordBitmap(15, 2, 4)
					},
					committed.getValueToRecordBitmap()
				);
			}
		);
	}

	@Test
	void shouldReportEmptyStateEveInTransaction() {
		assertStateAfterCommit(
			this.tested,
			original -> {
				assertFalse(original.isEmpty());

				original.removeRecord(5, 1);
				original.removeRecord(5, 20);
				original.removeRecord(10, 3);
				original.removeRecord(15, 2);
				original.removeRecord(15, 4);

				assertFalse(original.isEmpty());

				original.removeRecord(20, 5);

				assertTrue(original.isEmpty());
			},
			(original, committed) -> {
				assertFalse(original.isEmpty());
				assertTrue(committed.isEmpty());
			}
		);
	}

	@Test
	void shouldSerializeAndDeserialize() {
		final Kryo kryo = new Kryo();

		kryo.register(InvertedIndex.class, new InvertedIndexSerializer());
		kryo.register(ValueToRecordBitmap.class, new ValueToRecordBitmapSerializer());
		kryo.register(TransactionalBitmap.class, new TransactionalIntegerBitmapSerializer());

		final Output output = new Output(1024, -1);
		kryo.writeObject(output, this.tested);
		output.flush();

		byte[] bytes = output.getBuffer();

		final InvertedIndex deserializedTested = kryo.readObject(new Input(bytes), InvertedIndex.class);
		assertEquals(this.tested, deserializedTested);
	}

	@Test
	void shouldReportEmptyState() {
		assertFalse(this.tested.isEmpty());

		this.tested.removeRecord(5, 1);
		this.tested.removeRecord(5, 20);
		this.tested.removeRecord(10, 3);
		this.tested.removeRecord(15, 2);
		this.tested.removeRecord(15, 4);

		assertFalse(this.tested.isEmpty());

		this.tested.removeRecord(20, 5);

		assertTrue(this.tested.isEmpty());
	}

	@Test
	void shouldReturnSortedAllValues() {
		assertIteratorContains(this.tested.getSortedRecords().getRecordIds().iterator(), new int[]{1, 2, 3, 4, 5, 20});
	}

	@Test
	void shouldReturnSortedValuesFromLowerBoundUp() {
		assertIteratorContains(this.tested.getSortedRecords(10, null).getRecordIds().iterator(), new int[]{2, 3, 4, 5});
	}

	@Test
	void shouldReturnSortedValuesFromLowerBoundUpNotExact() {
		assertIteratorContains(this.tested.getSortedRecords(11, null).getRecordIds().iterator(), new int[]{2, 4, 5});
	}

	@Test
	void shouldReturnSortedValuesFromUpperBoundDown() {
		assertIteratorContains(this.tested.getSortedRecords(null, 15).getRecordIds().iterator(), new int[]{1, 2, 3, 4, 20});
	}

	@Test
	void shouldReturnSortedValuesFromUpperBoundDownNotExact() {
		assertIteratorContains(this.tested.getSortedRecords(null, 14).getRecordIds().iterator(), new int[]{1, 3, 20});
	}

	@Test
	void shouldReturnSortedValuesBetweenBounds() {
		assertIteratorContains(this.tested.getSortedRecords(10, 15).getRecordIds().iterator(), new int[]{2, 3, 4});
	}

	@Test
	void shouldReturnSortedValuesBetweenBoundsNotExact() {
		assertIteratorContains(this.tested.getSortedRecords(11, 14).getRecordIds().iterator(), new int[0]);
		assertIteratorContains(this.tested.getSortedRecords(14, 16).getRecordIds().iterator(), new int[]{2, 4});
		assertIteratorContains(this.tested.getSortedRecords(15, 15).getRecordIds().iterator(), new int[]{2, 4});
	}

	/* NOT SORTED */

	@Test
	void shouldReturnAllValues() {
		assertIteratorContains(this.tested.getRecords().getRecordIds().iterator(), new int[]{1, 20, 3, 2, 4, 5});
	}

	@Test
	void shouldReturnValuesFromLowerBoundUp() {
		assertIteratorContains(this.tested.getRecords(10, null).getRecordIds().iterator(), new int[]{3, 2, 4, 5});
	}

	@Test
	void shouldReturnValuesFromLowerBoundUpNotExact() {
		assertIteratorContains(this.tested.getRecords(11, null).getRecordIds().iterator(), new int[]{2, 4, 5});
	}

	@Test
	void shouldReturnValuesFromUpperBoundDown() {
		assertIteratorContains(this.tested.getRecords(null, 15).getRecordIds().iterator(), new int[]{1, 20, 3, 2, 4});
	}

	@Test
	void shouldReturnValuesFromUpperBoundDownNotExact() {
		assertIteratorContains(this.tested.getRecords(null, 14).getRecordIds().iterator(), new int[]{1, 20, 3});
	}

	@Test
	void shouldReturnValuesBetweenBounds() {
		assertIteratorContains(this.tested.getRecords(10, 15).getRecordIds().iterator(), new int[]{3, 2, 4});
	}

	@Test
	void shouldReturnValuesBetweenBoundsNotExact() {
		assertIteratorContains(this.tested.getRecords(11, 14).getRecordIds().iterator(), new int[0]);
		assertIteratorContains(this.tested.getRecords(14, 16).getRecordIds().iterator(), new int[]{2, 4});
		assertIteratorContains(this.tested.getRecords(15, 15).getRecordIds().iterator(), new int[]{2, 4});
	}

	/* NOT SORTED - REVERSED */

	@Test
	void shouldGenerationalTestPass() {
		final InvertedIndex histogram = new InvertedIndex(FilterIndex.NO_NORMALIZATION, Comparator.naturalOrder());
		histogram.addRecord(64L, 36, 47);
		histogram.addRecord(0L, 10);
		histogram.addRecord(65L, 90);
		histogram.addRecord(2L, 89);
		histogram.addRecord(67L, 9);
		histogram.addRecord(4L, 31, 22);
		histogram.addRecord(5L, 87);
		histogram.addRecord(6L, 5);
		histogram.addRecord(7L, 40);
		histogram.addRecord(74L, 7);
		histogram.addRecord(10L, 54);
		histogram.addRecord(12L, 16);
		histogram.addRecord(76L, 97);
		histogram.addRecord(77L, 56);
		histogram.addRecord(13L, 82);
		histogram.addRecord(15L, 67);
		histogram.addRecord(16L, 55);
		histogram.addRecord(82L, 32);
		histogram.addRecord(18L, 53, 76);
		histogram.addRecord(22L, 45, 37);
		histogram.addRecord(87L, 94, 83);
		histogram.addRecord(88L, 46, 44);
		histogram.addRecord(25L, 99);
		histogram.addRecord(26L, 98, 49);
		histogram.addRecord(92L, 0);
		histogram.addRecord(93L, 1);
		histogram.addRecord(31L, 57);
		histogram.addRecord(95L, 85);
		histogram.addRecord(97L, 66);
		histogram.addRecord(41L, 11);
		histogram.addRecord(44L, 51);
		histogram.addRecord(46L, 81, 3, 41);
		histogram.addRecord(49L, 26);
		histogram.addRecord(51L, 96);
		histogram.addRecord(54L, 8);
		histogram.addRecord(56L, 34);
		histogram.addRecord(57L, 62);
		histogram.addRecord(61L, 78);

		assertStateAfterCommit(
			histogram,
			original -> {
				histogram.removeRecord(65L, 90);
				histogram.removeRecord(51L, 96);
				histogram.removeRecord(22L, 37);
				histogram.addRecord(0L, 75);
				histogram.removeRecord(7L, 40);
				histogram.removeRecord(26L, 49);
				histogram.removeRecord(0L, 75);
				histogram.addRecord(92L, 71);
				histogram.addRecord(31L, 88);
				histogram.addRecord(16L, 59);
				histogram.addRecord(93L, 70);
				histogram.addRecord(74L, 84);
				histogram.removeRecord(64L, 47);
				histogram.addRecord(85L, 69);
				histogram.addRecord(78L, 28);
				histogram.addRecord(71L, 40);
				histogram.addRecord(37L, 43);
				histogram.removeRecord(97L, 66);
				histogram.addRecord(9L, 50);
				histogram.removeRecord(67L, 9);
				histogram.addRecord(45L, 73);
				histogram.removeRecord(13L, 82);
				histogram.removeRecord(92L, 0);
				histogram.removeRecord(93L, 1);
				histogram.addRecord(67L, 17);
				histogram.removeRecord(77L, 56);
				histogram.addRecord(66L, 23);
				histogram.addRecord(98L, 56);
				histogram.addRecord(29L, 48);
				histogram.removeRecord(88L, 44);
				histogram.addRecord(75L, 49);
				histogram.removeRecord(31L, 57);
				histogram.removeRecord(5L, 87);
				histogram.addRecord(65L, 64);
				histogram.removeRecord(71L, 40);
				histogram.removeRecord(4L, 22);
				histogram.removeRecord(61L, 78);
				histogram.addRecord(11L, 12);
				histogram.removeRecord(46L, 81);
				histogram.addRecord(0L, 2);
				histogram.addRecord(42L, 15);
				histogram.addRecord(37L, 25);
				histogram.removeRecord(75L, 49);
				histogram.removeRecord(54L, 8);
				histogram.addRecord(74L, 61);
				histogram.removeRecord(37L, 25);
				histogram.addRecord(16L, 30);
				histogram.addRecord(96L, 72);
				histogram.addRecord(65L, 39);
				histogram.removeRecord(18L, 53);
				histogram.removeRecord(56L, 34);
				histogram.removeRecord(45L, 73);
				histogram.removeRecord(0L, 2);
				histogram.removeRecord(95L, 85);
				histogram.addRecord(85L, 78);
				histogram.addRecord(80L, 18);
				histogram.addRecord(88L, 8);
				histogram.removeRecord(74L, 84);
				histogram.addRecord(96L, 1);
				histogram.addRecord(54L, 38);
				histogram.addRecord(33L, 93);
				histogram.removeRecord(16L, 59);
				histogram.removeRecord(57L, 62);
				histogram.addRecord(64L, 60);
				histogram.addRecord(94L, 75);
				histogram.removeRecord(25L, 99);
				histogram.removeRecord(37L, 43);
				histogram.removeRecord(42L, 15);
				histogram.removeRecord(10L, 54);
				histogram.removeRecord(85L, 78);
				histogram.addRecord(19L, 2);
				histogram.addRecord(81L, 90);
				histogram.addRecord(21L, 95);
				histogram.removeRecord(64L, 60);
				histogram.addRecord(87L, 42);
				histogram.removeRecord(46L, 41);
				histogram.removeRecord(82L, 32);
				histogram.removeRecord(74L, 61);
				histogram.addRecord(42L, 73);
				histogram.removeRecord(78L, 28);
				histogram.removeRecord(16L, 30);
				histogram.removeRecord(98L, 56);
				histogram.addRecord(64L, 47);
				histogram.removeRecord(87L, 83);
				histogram.removeRecord(42L, 73);
				histogram.removeRecord(22L, 45);
				histogram.addRecord(35L, 19);
				histogram.removeRecord(81L, 90);
				histogram.removeRecord(54L, 38);
				histogram.addRecord(64L, 60);
			},
			(original, committed) -> {
				final int[] expected = {1, 2, 3, 5, 7, 8, 10, 11, 12, 16, 17, 18, 19, 23, 26, 31, 36, 39, 42, 46, 47, 48, 50, 51, 55, 60, 64, 67, 69, 70, 71, 72, 75, 76, 88, 89, 93, 94, 95, 97, 98};
				assertArrayEquals(
					expected,
					committed.getSortedRecords().getRecordIds().getArray(),
					"\nExpected: " + Arrays.toString(expected) + "\n" +
						"Actual:   " + Arrays.toString(committed.getSortedRecords().getRecordIds().getArray()) + "\n"
				);
			}
		);
	}

	@ParameterizedTest(name = "InvertedIndex should survive generational randomized test applying modifications on it")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTest(GenerationalTestInput input) {
		doExecute(100, input, Long.class, Comparator.naturalOrder(), random -> (long) random.nextInt(200));
	}

	@ParameterizedTest(name = "InvertedIndex should survive generational randomized test applying localized modifications on it")
	@Tag(LONG_RUNNING_TEST)
	@ArgumentsSource(TimeArgumentProvider.class)
	void generationalProofTestLocalized(GenerationalTestInput input) {
		doExecute(
			100,
			input,
			String.class,
			new LocalizedStringComparator(new Locale("cs")),
			random -> NATIONAL_SPECIFIC_WORDS[random.nextInt(NATIONAL_SPECIFIC_WORDS.length)]
		);
	}

	private <T extends Serializable> void doExecute(
		int initialCount,
		@Nonnull GenerationalTestInput input,
		@Nonnull Class<T> type,
		@Nonnull Comparator<T> comparator,
		@Nonnull Function<Random, T> randomValueSupplier
	) {
		final Map<T, List<Integer>> mapToCompare = new HashMap<>();
		final Map<Integer, Set<T>> recordValues = new HashMap<>();
		final Set<Integer> currentRecordSet = new HashSet<>();
		final Set<T> uniqueValues = new TreeSet<>(comparator);

		runFor(
			input,
			1_00,
			new TestState(
				new StringBuilder()
			),
			(random, testState) -> {
				final StringBuilder codeBuffer = testState.code();
				codeBuffer.append("final InvertedIndex<Long> histogram = new InvertedIndex<>();\n")
					.append(
						mapToCompare.entrySet()
							.stream()
							.map(it -> "histogram.addRecord(" + it.getKey() + "L," + it.getValue().stream().map(Object::toString).collect(Collectors.joining(", ")) + ");")
							.collect(Collectors.joining("\n"))
					)
					.append("\nOps:\n");

				final InvertedIndex histogram = new InvertedIndex(FilterIndex.NO_NORMALIZATION, comparator);
				for (Entry<T, List<Integer>> entry : mapToCompare.entrySet()) {
					histogram.addRecord(
						entry.getKey(),
						entry.getValue().stream().mapToInt(it -> it).toArray()
					);
				}

				assertStateAfterCommit(
					histogram,
					original -> {
						try {
							final int operationsInTransaction = random.nextInt(100);
							for (int i = 0; i < operationsInTransaction; i++) {
								final int length = histogram.getRecords().getRecordIds().size();
								if ((random.nextBoolean() || length < 10) && length < 50) {
									// insert new item
									final T newValue = randomValueSupplier.apply(random);

									int newRecId;
									do {
										newRecId = random.nextInt(initialCount);
									} while (currentRecordSet.contains(newRecId));

									mapToCompare.computeIfAbsent(newValue, aLong -> new ArrayList<>()).add(newRecId);
									recordValues.computeIfAbsent(newRecId, integer -> new HashSet<>()).add(newValue);
									currentRecordSet.add(newRecId);
									uniqueValues.add(newValue);

									codeBuffer.append("histogram.addRecord(").append(newValue).append("L,").append(newRecId).append(");\n");
									histogram.addRecord(newValue, newRecId);
								} else {
									// remove existing item
									final Iterator<Entry<T, List<Integer>>> it = mapToCompare.entrySet().iterator();
									T valueToRemove = null;
									Integer recordToRemove = null;
									final int removePosition = random.nextInt(length);
									int cnt = 0;
									finder:
									for (int j = 0; j < mapToCompare.size() + 1; j++) {
										final Entry<T, List<Integer>> entry = it.next();
										final Iterator<Integer> valIt = entry.getValue().iterator();
										while (valIt.hasNext()) {
											final Integer recordId = valIt.next();
											if (removePosition == cnt++) {
												valueToRemove = entry.getKey();
												recordToRemove = recordId;
												valIt.remove();
												break finder;
											}
										}
									}
									currentRecordSet.remove(recordToRemove);

									final Set<T> theRecordValues = recordValues.get(recordToRemove);
									theRecordValues.remove(valueToRemove);
									if (theRecordValues.isEmpty()) {
										recordValues.remove(recordToRemove);
									}

									final int expectedIndex = indexOf(uniqueValues, valueToRemove);
									if (mapToCompare.get(valueToRemove).isEmpty()) {
										uniqueValues.remove(valueToRemove);
										mapToCompare.remove(valueToRemove);
									}

									codeBuffer.append("histogram.removeRecord(").append(valueToRemove).append("L,").append(recordToRemove).append(");\n");
									final int removedAtIndex = histogram.removeRecord(Objects.requireNonNull(valueToRemove), recordToRemove);

									assertEquals(expectedIndex, removedAtIndex);
								}
							}
						} catch (Exception ex) {
							fail("\n" + codeBuffer, ex);
						}
					},
					(original, committed) -> {
						final int[] expected = currentRecordSet.stream().mapToInt(it -> it).sorted().toArray();
						for (Entry<Integer, Set<T>> entry : recordValues.entrySet()) {
							final Set<T> values = entry.getValue();
							final T[] actual = committed.getValuesForRecord(entry.getKey(), type);
							assertArrayEquals(
								values.stream().sorted(comparator).toArray(),
								Arrays.stream(actual).sorted(comparator).toArray(),
								"\nExpected: " + Arrays.toString(values.toArray()) + "\n" +
									"Actual:   " + Arrays.toString(actual) + "\n\n" +
									codeBuffer
							);
						}
						assertArrayEquals(
							expected,
							committed.getSortedRecords().getRecordIds().getArray(),
							"\nExpected: " + Arrays.toString(expected) + "\n" +
								"Actual:   " + Arrays.toString(committed.getSortedRecords().getRecordIds().getArray()) + "\n\n" +
								codeBuffer
						);
						final ConsistencyReport consistencyReport = committed.getConsistencyReport();
						assertEquals(
							ConsistencyState.CONSISTENT, consistencyReport.state(),
							consistencyReport::report
						);
					}
				);

				return new TestState(
					new StringBuilder()
				);
			}
		);
	}

	private static <T extends Serializable> int indexOf(@Nonnull Set<T> values, @Nonnull T valueToFind) {
		int result = -1;
		for (T value : values) {
			result++;
			if (((Comparable)valueToFind).compareTo(value) == 0) {
				return result;
			}
		}
		return result;
	}

	private record TestState(
		StringBuilder code
	) {}

}
