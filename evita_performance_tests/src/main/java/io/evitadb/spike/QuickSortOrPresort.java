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

package io.evitadb.spike;

import io.evitadb.utils.ArrayUtils;
import lombok.Getter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.infra.Blackhole;
import org.roaringbitmap.FastRankRoaringBitmap;
import org.roaringbitmap.RoaringBatchIterator;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.RoaringBitmapWriter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * This spike test tries to test whether quicksort on String data of length from 10 to 50 is faster than presorted approach.
 *
 * Results:
 * QuickSortOrPresort.quickSort  thrpt       17.576          ops/s
 * QuickSortOrPresort.preSort    thrpt      226.016          ops/s
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class QuickSortOrPresort {
	private static final Random RANDOM = new Random();
	private static final int VALUE_COUNT = 100_000;

	@State(Scope.Benchmark)
	public static class QuickSortState {

		@Getter private String[] randomSortedAttribute;
		@Getter private int[] assignedRecordIds;

		/**
		 * This setup is called once for each `valueCount`.
		 */
		@Setup(Level.Invocation)
		public void setUp() {
			this.randomSortedAttribute = generateRandomStrings(VALUE_COUNT);
			this.assignedRecordIds = new int[VALUE_COUNT];
			for (int i = 0; i < VALUE_COUNT; i++) {
				this.assignedRecordIds[i] = i + 1;
			}
		}

		private static String[] generateRandomStrings(int valueCount) {
			final String[] result = new String[valueCount];
			for (int i = 0; i < valueCount; i++) {
				result[i] = generateRandomString(10 + RANDOM.nextInt(41));
			}
			return result;
		}

		private static String generateRandomString(int length) {
			byte[] array = new byte[length];
			new Random().nextBytes(array);
			return new String(array, StandardCharsets.UTF_8);
		}

	}

	@State(Scope.Benchmark)
	public static class PresortedState {
		@Getter private RoaringBitmap selectedRecordIds;
		@Getter private RoaringBitmap unsortedRecordIds;
		@Getter private int[] sortedRecordIds;
		@Getter private int[] recordIdPositions;

		/**
		 * This setup is called once for each `valueCount`.
		 */
		@Setup(Level.Iteration)
		public void setUp() {
			final int totalCount = VALUE_COUNT * 3;
			final List<Integer> positions = new ArrayList<>(totalCount);
			for (int i = 0; i < totalCount; i++) {
				positions.add(i);
			}

			this.selectedRecordIds = new RoaringBitmap();
			this.recordIdPositions = new int[totalCount];
			this.unsortedRecordIds = new FastRankRoaringBitmap();

			int peek = 0;
			int lastPk = -1;
			for (int i = 0; i < totalCount; i++) {
				lastPk += 1 + RANDOM.nextInt(2);
				this.unsortedRecordIds.add(lastPk);
				this.recordIdPositions[i] = positions.remove(RANDOM.nextInt(positions.size()));
				if (peek < VALUE_COUNT && RANDOM.nextBoolean()) {
					peek++;
					this.selectedRecordIds.add(lastPk);
				}
			}

			this.sortedRecordIds = ArrayUtils.computeSortedSecondAlongFirstArray(
				Comparator.comparing(o -> this.recordIdPositions[this.unsortedRecordIds.rank(o)]),
				this.unsortedRecordIds.toArray()
			);
		}

	}

	@Benchmark
	@Threads(1)
	@BenchmarkMode({Mode.Throughput})
	public void quickSort(QuickSortState dataSet, Blackhole blackhole) {
		blackhole.consume(
			ArrayUtils.computeSortedSecondAlongFirstArray(
				dataSet.getRandomSortedAttribute(),
				dataSet.getAssignedRecordIds()
			)
		);
	}

	@Benchmark
	@Threads(1)
	@BenchmarkMode({Mode.Throughput})
	public void preSort(PresortedState dataSet, Blackhole blackhole) {
		final RoaringBitmap selectedRecordIds = dataSet.getSelectedRecordIds();
		final RoaringBitmap unsortedRecordIds = dataSet.getUnsortedRecordIds();
		final int[] recordPositions = dataSet.getRecordIdPositions();
		final int[] preSortedRecordIds = dataSet.getSortedRecordIds();
		final int selectedRecordCount = selectedRecordIds.getCardinality();
		final int[] bufferA = new int[512];
		final int[] bufferB = new int[512];

		final RoaringBitmapWriter<RoaringBitmap> mask = RoaringBitmapWriter.writer()
			.expectedRange(0, recordPositions.length)
			.expectedDensity((double) selectedRecordCount / (double) recordPositions.length)
			.constantMemory()
			.runCompress(false)
			.get();

		final RoaringBatchIterator unsortedRecordIdsIt = unsortedRecordIds.getBatchIterator();
		final RoaringBatchIterator selectedRecordIdsIt = selectedRecordIds.getBatchIterator();

		int matchesFound = 0;
		int peekA = -1;
		int limitA = -1;
		int peekB = -1;
		int limitB = -1;
		for (int i = 0; i < recordPositions.length && matchesFound < selectedRecordCount; i++) {
			if (peekA == limitA) {
				limitA = unsortedRecordIdsIt.nextBatch(bufferA);
				peekA = 0;
			}
			if (peekB == limitB) {
				limitB = selectedRecordIdsIt.nextBatch(bufferB);
				peekB = 0;
			}
			if (bufferA[peekA++] == bufferB[peekB]) {
				mask.add(recordPositions[i]);
				matchesFound++;
				peekB++;
			}
		}

		final RoaringBitmap positions = mask.get();
		final RoaringBatchIterator batchIterator = positions.getBatchIterator();

		final int[] sortedResult = new int[selectedRecordCount];
		int outputPeek = 0;
		while (batchIterator.hasNext()) {
			final int read = batchIterator.nextBatch(bufferB);
			for (int i = 0; i < read; i++) {
				sortedResult[outputPeek++] = preSortedRecordIds[bufferB[i]];
			}
		}

		blackhole.consume(sortedResult);
	}

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

}
