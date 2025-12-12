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

import io.evitadb.dataType.array.CompositeObjectArray;
import lombok.Data;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.LinkedList;
import java.util.concurrent.TimeUnit;

/**
 * This performance test compares {@link CompositeObjectArray} with {@link java.util.LinkedList}
 * performance.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class CompositeObjectArrayOrLinkedList {

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
	public void addIntegersAndRetrieveArrayFromLinkedList(LinkedListState plan, Blackhole blackhole) {
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

		void addValue(Integer value);

		Integer[] toArray();

	}

	@Data
	private static class CompositeArrayImplementation implements SharedInterface {
		private final CompositeObjectArray<Integer> array = new CompositeObjectArray<>(Integer.class);

		@Override
		public void addValue(Integer value) {
			this.array.add(value);
		}

		@Override
		public Integer[] toArray() {
			return this.array.toArray();
		}

	}

	@Data
	private static class LinkedListImplementation implements SharedInterface {
		private static final Integer[] EMPTY_ARRAY = new Integer[0];
		private final LinkedList<Integer> list = new LinkedList<>();

		@Override
		public void addValue(Integer value) {
			this.list.add(value);
		}

		@Override
		public Integer[] toArray() {
			return this.list.toArray(EMPTY_ARRAY);
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
			this.sharedInterface = new LinkedListImplementation();
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
