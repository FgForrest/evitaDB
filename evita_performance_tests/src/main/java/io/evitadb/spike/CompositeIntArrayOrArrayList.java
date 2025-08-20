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

import com.carrotsearch.hppc.IntArrayList;
import io.evitadb.dataType.array.CompositeIntArray;
import lombok.Data;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * This performance test compares {@link CompositeIntArray} with {@link ArrayList}
 * performance.
 *
 * Benchmark                                      (valueCount)   Mode  Cnt          Score   Error  Units
 * addIntegersAndRetrieveArrayFromArrayList                  1  thrpt        46573035.689          ops/s
 * addIntegersAndRetrieveArrayFromArrayList                 20  thrpt        23648064.517          ops/s
 * addIntegersAndRetrieveArrayFromArrayList                 50  thrpt         9700478.380          ops/s
 * addIntegersAndRetrieveArrayFromArrayList                100  thrpt         6550141.867          ops/s
 * addIntegersAndRetrieveArrayFromArrayList               1000  thrpt          418696.034          ops/s
 * addIntegersAndRetrieveArrayFromArrayList               5000  thrpt           80212.851          ops/s
 * addIntegersAndRetrieveArrayFromArrayList              10000  thrpt           36088.696          ops/s
 * addIntegersAndRetrieveArrayFromCompositeArray             1  thrpt       203209484.030          ops/s
 * addIntegersAndRetrieveArrayFromCompositeArray            20  thrpt        92068566.824          ops/s
 * addIntegersAndRetrieveArrayFromCompositeArray            50  thrpt        30288491.780          ops/s
 * addIntegersAndRetrieveArrayFromCompositeArray           100  thrpt        18849037.969          ops/s
 * addIntegersAndRetrieveArrayFromCompositeArray          1000  thrpt         2323858.760          ops/s
 * addIntegersAndRetrieveArrayFromCompositeArray          5000  thrpt          419785.848          ops/s
 * addIntegersAndRetrieveArrayFromCompositeArray         10000  thrpt          206357.902          ops/s
 * addIntegersAndRetrieveArrayFromIntArrayList               1  thrpt       257050077.349          ops/s
 * addIntegersAndRetrieveArrayFromIntArrayList              20  thrpt        66293483.895          ops/s
 * addIntegersAndRetrieveArrayFromIntArrayList              50  thrpt        15688402.055          ops/s
 * addIntegersAndRetrieveArrayFromIntArrayList             100  thrpt        10674309.363          ops/s
 * addIntegersAndRetrieveArrayFromIntArrayList            1000  thrpt          791487.454          ops/s
 * addIntegersAndRetrieveArrayFromIntArrayList            5000  thrpt          151948.927          ops/s
 * addIntegersAndRetrieveArrayFromIntArrayList           10000  thrpt           69596.820          ops/s
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class CompositeIntArrayOrArrayList {

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

	/**
	 * HashMap lookup benchmark.
	 */
	@Benchmark
	@Measurement(time = 15, timeUnit = TimeUnit.SECONDS)
	@BenchmarkMode({Mode.Throughput})
	@Threads(Threads.MAX)
	public void addIntegersAndRetrieveArrayFromCompositeArray(CompositeArrayState plan, Blackhole blackhole) {
		final SharedInterface sharedInterface = plan.getSharedInterface();
		for (int i = 0; i < plan.getValueCount(); i++) {
			sharedInterface.addValue(i);
		}
		blackhole.consume(
			sharedInterface.toArray()
		);
	}

	/**
	 * Sorted array binary search lookup benchmark.
	 */
	@Benchmark
	@Measurement(time = 15, timeUnit = TimeUnit.SECONDS)
	@BenchmarkMode({Mode.Throughput})
	@Threads(Threads.MAX)
	public void addIntegersAndRetrieveArrayFromIntArrayList(IntArrayListState plan, Blackhole blackhole) {
		final SharedInterface sharedInterface = plan.getSharedInterface();
		for (int i = 0; i < plan.getValueCount(); i++) {
			sharedInterface.addValue(i);
		}
		blackhole.consume(
			sharedInterface.toArray()
		);
	}

	/**
	 * Sorted array binary search lookup benchmark.
	 */
	@Benchmark
	@Measurement(time = 15, timeUnit = TimeUnit.SECONDS)
	@BenchmarkMode({Mode.Throughput})
	@Threads(Threads.MAX)
	public void addIntegersAndRetrieveArrayFromArrayList(LinkedListState plan, Blackhole blackhole) {
		final SharedInterface sharedInterface = plan.getSharedInterface();
		for (int i = 0; i < plan.getValueCount(); i++) {
			sharedInterface.addValue(i);
		}
		blackhole.consume(
			sharedInterface.toArray()
		);
	}

	/**
	 * Common interface for accessing array of values.
	 */
	private interface SharedInterface {

		void addValue(int value);

		int[] toArray();

	}

	@Data
	private static class CompositeArrayImplementation implements SharedInterface {
		private final CompositeIntArray array = new CompositeIntArray();

		@Override
		public void addValue(int value) {
			this.array.add(value);
		}

		@Override
		public int[] toArray() {
			return this.array.toArray();
		}

	}

	@Data
	private static class ArrayListImplementation implements SharedInterface {
		private final ArrayList<Integer> list = new ArrayList<>();

		@Override
		public void addValue(int value) {
			this.list.add(value);
		}

		@Override
		public int[] toArray() {
			return this.list.stream().mapToInt(it -> it).toArray();
		}
	}

	@Data
	private static class IntArrayListImplementation implements SharedInterface {
		private final IntArrayList list = new IntArrayList();

		@Override
		public void addValue(int value) {
			this.list.add(value);
		}

		@Override
		public int[] toArray() {
			return this.list.toArray();
		}
	}

	@State(Scope.Thread)
	@Data
	public static class LinkedListState {

		/**
		 * Number of generated values in the value holder.
		 */
		@Param({"1", "20", "50", "100", "1000", "5000", "10000"})
		private int valueCount;

		/**
		 * Value holder implementation reference.
		 */
		private SharedInterface sharedInterface;

		/**
		 * This setup is called once for each `valueCount`.
		 */
		@Setup(Level.Invocation)
		public void setUp() {
			this.sharedInterface = new ArrayListImplementation();
		}

	}

	@State(Scope.Thread)
	@Data
	public static class IntArrayListState {

		/**
		 * Number of generated values in the value holder.
		 */
		@Param({"1", "20", "50", "100", "1000", "5000", "10000"})
		private int valueCount;

		/**
		 * Value holder implementation reference.
		 */
		private SharedInterface sharedInterface;

		/**
		 * This setup is called once for each `valueCount`.
		 */
		@Setup(Level.Invocation)
		public void setUp() {
			this.sharedInterface = new IntArrayListImplementation();
		}

	}

	@State(Scope.Thread)
	@Data
	public static class CompositeArrayState {

		/**
		 * Number of generated values in the value holder.
		 */
		@Param({"1", "20", "50", "100", "1000", "5000", "10000"})
		private int valueCount;

		/**
		 * Value holder implementation reference.
		 */
		private SharedInterface sharedInterface;

		/**
		 * This setup is called once for each `valueCount`.
		 */
		@Setup(Level.Invocation)
		public void setUp() {
			this.sharedInterface = new CompositeArrayImplementation();
		}

	}

}
