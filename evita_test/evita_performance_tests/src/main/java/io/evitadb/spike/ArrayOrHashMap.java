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

import lombok.Data;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * This microbenchmark tests the breaking point in the sense of value count when HashMap starts to be faster than plain
 * binary search in sorted order array.
 *
 * Results:
 *
 * Benchmark                                (valueCount)   Mode  Cnt         Score        Error  Units
 * ArrayOrHashMap.retrieveValueFromArray               1  thrpt   25  33018946.872 ± 499327.916  ops/s
 * ArrayOrHashMap.retrieveValueFromArray               2  thrpt   25  26930320.555 ± 816724.849  ops/s
 * ArrayOrHashMap.retrieveValueFromArray               3  thrpt   25  23096141.884 ± 895579.899  ops/s
 * ArrayOrHashMap.retrieveValueFromArray               4  thrpt   25  22336761.515 ± 757062.957  ops/s
 * ArrayOrHashMap.retrieveValueFromArray               5  thrpt   25  20970277.174 ± 684321.439  ops/s
 * ArrayOrHashMap.retrieveValueFromArray              10  thrpt   25  18272554.920 ± 378155.886  ops/s
 * ArrayOrHashMap.retrieveValueFromArray              20  thrpt   25  17173265.347 ± 108496.597  ops/s
 * ArrayOrHashMap.retrieveValueFromArray              50  thrpt   25  14547561.158 ± 233628.890  ops/s
 * ArrayOrHashMap.retrieveValueFromArray             100  thrpt   25  13673743.240 ± 357581.808  ops/s
 * ArrayOrHashMap.retrieveValueFromHashMap             1  thrpt   25  30330764.630 ± 243337.973  ops/s
 * ArrayOrHashMap.retrieveValueFromHashMap             2  thrpt   25  29802111.163 ± 285228.437  ops/s
 * ArrayOrHashMap.retrieveValueFromHashMap             3  thrpt   25  29179654.518 ± 457798.894  ops/s
 * ArrayOrHashMap.retrieveValueFromHashMap             4  thrpt   25  29198463.764 ± 238315.417  ops/s
 * ArrayOrHashMap.retrieveValueFromHashMap             5  thrpt   25  29376291.696 ± 508033.314  ops/s
 * ArrayOrHashMap.retrieveValueFromHashMap            10  thrpt   25  28421087.086 ± 246257.453  ops/s
 * ArrayOrHashMap.retrieveValueFromHashMap            20  thrpt   25  25601031.601 ± 148148.828  ops/s
 * ArrayOrHashMap.retrieveValueFromHashMap            50  thrpt   25  27278606.367 ± 334218.101  ops/s
 * ArrayOrHashMap.retrieveValueFromHashMap           100  thrpt   25  27415683.421 ± 203609.194  ops/s
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class ArrayOrHashMap {
	private static final Random random = new Random();

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

	/**
	 * Randomizes `valueCount` of values and asks valueLookupFactory to create instance of {@link ValueLookup} implementation.
	 */
	private static ValueLookup createValueHolder(int valueCount, BiFunction<String[], Integer[], ValueLookup> valueLookupFactory) {
		final Set<String> keys = new HashSet<>(valueCount);
		final List<Integer> values = new ArrayList<>(valueCount);
		for (int i = 0; i < valueCount; i++) {
			String key;
			do {
				key = String.valueOf(100 + random.nextInt((int) (valueCount * 1.5)));
			} while (keys.contains(key));

			keys.add(key);
			values.add(i);
		}
		final String[] sortedKeys = keys.toArray(new String[0]);
		Arrays.sort(sortedKeys, Comparator.naturalOrder());
		return valueLookupFactory.apply(sortedKeys, values.toArray(new Integer[0]));
	}

	/**
	 * HashMap lookup benchmark.
	 */
	@Benchmark
	@BenchmarkMode({Mode.Throughput})
	public void retrieveValueFromHashMap(HashMapState plan, KeyState keyAccessor, Blackhole blackhole) {
		blackhole.consume(
			plan.valueHolder.getValue(keyAccessor.key)
		);
	}

	/**
	 * Sorted array binary search lookup benchmark.
	 */
	@Benchmark
	@BenchmarkMode({Mode.Throughput})
	public void retrieveValueFromArray(ArrayState plan, KeyState keyAccessor, Blackhole blackhole) {
		blackhole.consume(
			plan.valueHolder.getValue(keyAccessor.key)
		);
	}

	/**
	 * Common interface for accessing associated values.
	 */
	private interface ValueLookup {

		/**
		 * Returns value assigned to the key.
		 */
		@Nullable
		Integer getValue(@Nonnull String key);

	}

	/**
	 * Array value holder implementation - keeps sorted array of names upon which it makes binary search and result
	 * index is used to retrieve value from values array.
	 */
	private record ArraySet(@Nonnull String[] names, @Nonnull Integer[] values) implements ValueLookup {
		@Nullable
		@Override
		public Integer getValue(@Nonnull String key) {
			final int index = Arrays.binarySearch(this.names, key);
			return index >= 0 ? this.values[index] : null;
		}
	}

	/**
	 * HashMap holder implementation - performs get on key in hashmap and returns the result.
	 */
	@Data
	private static class HashMapArraySet implements ValueLookup {
		private final Map<String, Integer> keyValues;

		public HashMapArraySet(String[] names, Integer[] values) {
			this.keyValues = new HashMap<>(names.length);
			for (int i = 0; i < names.length; i++) {
				final String name = names[i];
				this.keyValues.put(name, values[i]);
			}
		}

		@Override
		public Integer getValue(@Nonnull String key) {
			return this.keyValues.get(key);
		}
	}

	@State(Scope.Benchmark)
	@Data
	public static class HashMapState {

		/**
		 * Number of generated values in the value holder.
		 */
		@Param({"1", "2", "3", "4", "5", "10", "20", "50", "100"})
		private int valueCount;

		/**
		 * Value holder implementation reference.
		 */
		private ValueLookup valueHolder;

		/**
		 * This setup is called once for each `valueCount`.
		 */
		@Setup(Level.Trial)
		public void setUp() {
			this.valueHolder = createValueHolder(
				this.valueCount,
				HashMapArraySet::new
			);
		}

	}

	@State(Scope.Benchmark)
	@Data
	public static class ArrayState {

		/**
		 * Number of generated values in the value holder.
		 */
		@Param({"1", "2", "3", "4", "5", "10", "20", "50", "100"})
		private int valueCount;

		/**
		 * Value holder implementation reference.
		 */
		private ValueLookup valueHolder;

		/**
		 * This setup is called once for each `valueCount`.
		 */
		@Setup(Level.Trial)
		public void setUp() {
			this.valueHolder = createValueHolder(
				this.valueCount,
				ArraySet::new
			);
		}

	}

	@State(Scope.Thread)
	@Data
	public static class KeyState {
		private String key;

		/**
		 * This is called once per invocation and randomly generates key to retrieve from {@link ValueLookup}
		 */
		@Setup(Level.Invocation)
		public void setUp(HashMapState plan) {
			this.key = String.valueOf(100 + random.nextInt((int) (plan.valueCount * 1.25)));
		}

	}

}
