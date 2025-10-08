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
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.function.Function;

/**
 * This spike test answers the question whether there is really big impact to keep changes in the form of operations
 * or mutable state.
 *
 * Creation                                                       Mode  Cnt        Score       Error  Units
 * DirectWriteOrOperationLog.retrieveValueFromDirectlyWrittenMap  thrpt   25  1580800.743 ± 16093.624  ops/s
 * DirectWriteOrOperationLog.retrieveValueFromOperationMap        thrpt   25   295822.689 ±  2330.564  ops/s
 *
 * Retrieving changes
 * DirectWriteOrOperationLog.retrieveValueFromDirectlyWrittenMap  thrpt   25   233163.410 ±  2958.510  ops/s
 * DirectWriteOrOperationLog.retrieveValueFromOperationMap        thrpt   25  1195781.543 ± 23327.547  ops/s
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class DirectWriteOrOperationLog {
	public static final int INITIAL_CAPACITY = 50;
	private static final Random random = new Random();

	public static void main(String[] args) throws Exception {
		org.openjdk.jmh.Main.main(args);
	}

	/**
	 * Randomizes the initial values in maps and asks builderFactory to create instance of {@link ImmutableContainerBuilder} implementation.
	 */
	private static ImmutableContainerBuilder createValueHolder(Function<Map<String, Integer>, ImmutableContainerBuilder> builderFactory) {
		final Map<String, Integer> data = new HashMap<>(INITIAL_CAPACITY);
		for (int i = 0; i < INITIAL_CAPACITY; i++) {
			String key;
			do {
				key = String.valueOf(100 + random.nextInt((int) (INITIAL_CAPACITY * 1.5)));
			} while (data.containsKey(key));

			data.put(key, i);
		}
		return builderFactory.apply(data);
	}

	/**
	 * Direct write to hashmap and generate changelog lazily benchmark.
	 */
	@Benchmark
	@BenchmarkMode({Mode.Throughput})
	public void retrieveValueFromDirectlyWrittenMap(DirectWriteState plan, NewDataState newData, Blackhole blackhole) {
		final ImmutableContainerBuilder valueHolder = plan.valueHolder;
		for (Entry<String, Integer> entry : newData.getNewData().entrySet()) {
			if (entry.getValue() % 3 == 0) {
				valueHolder.removeValue(entry.getKey());
			} else {
				valueHolder.putValue(entry.getKey(), entry.getValue());
			}
		}
		blackhole.consume(
			valueHolder.getChanges()
		);
	}

	/**
	 * Write set of operations, have changelog ready and produce result map on demand.
	 */
	@Benchmark
	@BenchmarkMode({Mode.Throughput})
	public void retrieveValueFromOperationMap(OperationState plan, NewDataState newData, Blackhole blackhole) {
		final ImmutableContainerBuilder valueHolder = plan.valueHolder;
		for (Entry<String, Integer> entry : newData.getNewData().entrySet()) {
			if (entry.getValue() % 3 == 0) {
				valueHolder.removeValue(entry.getKey());
			} else {
				valueHolder.putValue(entry.getKey(), entry.getValue());
			}
		}
		blackhole.consume(
			valueHolder.getChanges()
		);
	}

	/**
	 * Type of the operation set or remove.
	 */
	public enum OperationType {

		SET, REMOVE

	}

	/**
	 * Common interface for accessing associated values.
	 */
	private interface ImmutableContainerBuilder {

		/**
		 * Adds new key-value.
		 */
		void putValue(String key, Integer value);

		/**
		 * Removes key with value.
		 */
		void removeValue(String key);

		/**
		 * Returns container with all changes applied.
		 */
		Map<String, Integer> getContainer();

		/**
		 * Returns list of operations that when applied on old container produce the result same as {@link #getContainer()}.
		 */
		Collection<WriteOperation> getChanges();

	}

	/**
	 * Implementation that writes changes directly to result map. Original map is held so that the result changelog
	 * can be generated on demand.
	 */
	@Data
	private static class DirectMapWriter implements ImmutableContainerBuilder {
		private final Map<String, Integer> base;
		private final Map<String, Integer> values;

		public DirectMapWriter(Map<String, Integer> baseMap) {
			this.base = baseMap;
			this.values = new LinkedHashMap<>(baseMap);
		}

		@Override
		public void putValue(String key, Integer value) {
			this.values.put(key, value);
		}

		@Override
		public void removeValue(String key) {
			this.values.remove(key);
		}

		@Override
		public Map<String, Integer> getContainer() {
			return this.values;
		}

		@Override
		public Collection<WriteOperation> getChanges() {
			final List<WriteOperation> changes = new LinkedList<>();
			for (Entry<String, Integer> entry : this.values.entrySet()) {
				final Object original = this.base.get(entry.getKey());
				if (original == null || !original.equals(entry.getValue())) {
					changes.add(new WriteOperation(entry.getKey(), entry.getValue(), OperationType.SET));
				}
			}
			for (Entry<String, Integer> baseEntry : this.base.entrySet()) {
				if (!this.values.containsKey(baseEntry.getKey())) {
					changes.remove(new WriteOperation(baseEntry.getKey(), baseEntry.getValue(), OperationType.REMOVE));
				}
			}
			return changes;
		}
	}

	/**
	 * Implementation that keeps the operations that can be applied on the map to produce result. Inversion implementation
	 * thatn the {@link DirectMapWriter}.
	 */
	@Data
	private static class OperationWriter implements ImmutableContainerBuilder {
		private final Map<String, Integer> baseValues;
		private final Map<String, WriteOperation> operations;

		public OperationWriter(Map<String, Integer> baseValues) {
			this.baseValues = baseValues;
			this.operations = new HashMap<>();
		}

		@Override
		public void putValue(String key, Integer value) {
			this.operations.put(key, new WriteOperation(key, value, OperationType.SET));
		}

		@Override
		public void removeValue(String key) {
			this.operations.put(key, new WriteOperation(key, null, OperationType.REMOVE));
		}

		@Override
		public Map<String, Integer> getContainer() {
			final LinkedHashMap<String, Integer> resultMap = new LinkedHashMap<>(this.baseValues.size() + this.operations.size());
			resultMap.putAll(this.baseValues);
			for (Entry<String, WriteOperation> entry : this.operations.entrySet()) {
				entry.getValue().apply(entry.getKey(), resultMap);
			}
			return resultMap;
		}

		@Override
		public Collection<WriteOperation> getChanges() {
			return this.operations.values();
		}

	}

	public record WriteOperation(@Nonnull String key, @Nullable Integer value, @Nonnull OperationType operation) {
		public void apply(String key, Map<String, Integer> resultMap) {
			if (this.operation == OperationType.SET) {
				resultMap.put(key, this.value);
			} else {
				resultMap.remove(key);
			}
		}
	}

	@State(Scope.Benchmark)
	@Data
	public static class DirectWriteState {

		/**
		 * Value holder implementation reference.
		 */
		private ImmutableContainerBuilder valueHolder;

		/**
		 * This setup is called once for each round.
		 */
		@Setup(Level.Trial)
		public void setUp() {
			this.valueHolder = createValueHolder(DirectMapWriter::new);
		}

	}

	@State(Scope.Benchmark)
	@Data
	public static class OperationState {

		/**
		 * Value holder implementation reference.
		 */
		private ImmutableContainerBuilder valueHolder;

		/**
		 * This setup is called once for each round.
		 */
		@Setup(Level.Trial)
		public void setUp() {
			this.valueHolder = createValueHolder(OperationWriter::new);
		}

	}

	@State(Scope.Thread)
	@Data
	public static class NewDataState {
		private Map<String, Integer> newData;

		/**
		 * This is called once per invocation and randomly generates random change set of key and values to be applied
		 * on {@link ImmutableContainerBuilder}.
		 */
		@Setup(Level.Invocation)
		public void setUp() {
			this.newData = new HashMap<>(INITIAL_CAPACITY);
			for (int i = 0; i < 25; i++) {
				this.newData.put(String.valueOf(100 + random.nextInt((int) (INITIAL_CAPACITY * 1.25))), i);
			}
		}

	}

}
