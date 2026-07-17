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

package io.evitadb.spike;

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TrafficRecordingOptions;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.head.Label;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.core.executor.ImmediateScheduledThreadPoolExecutor;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.management.FileManagementService;
import io.evitadb.store.traffic.OffHeapTrafficRecorder;

import javax.annotation.Nonnull;
import java.nio.file.Path;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAll;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyNatural;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.label;
import static io.evitadb.api.query.QueryConstraints.orderBy;
import static io.evitadb.api.query.QueryConstraints.require;

/**
 * Shared construction helpers for the traffic-recording JMH benchmarks (issue #1282). Mirrors
 * `OffHeapTrafficRecorderTest.setUp` so benchmark states stand up the same object graph
 * as the functional tests do, without needing a running server.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class TrafficRecordingBenchSupport {

	/**
	 * Entity type used by every sample query/mutation built by this class.
	 */
	public static final String ENTITY_TYPE = "Product";
	/**
	 * Two labels attached to every sample query so the label-merging path
	 * (`OffHeapTrafficRecorder.recordQuery` stream allocation) is actually exercised.
	 */
	public static final Label[] SAMPLE_LABELS = new Label[]{label("a", "b"), label("c", "d")};

	private TrafficRecordingBenchSupport() {
		throw new UnsupportedOperationException("This is a static helper class, no instances allowed!");
	}

	/**
	 * A scheduler whose delayed tasks execute synchronously on the calling thread whenever they are scheduled
	 * with a zero delay - used to make session-close driven flushes (`trafficFlushIntervalInMilliseconds = 0`)
	 * and on-demand indexing deterministic within a benchmark method.
	 */
	@Nonnull
	public static Scheduler immediateScheduler() {
		return new Scheduler(new ImmediateScheduledThreadPoolExecutor());
	}

	/**
	 * Builds and initializes an {@link OffHeapTrafficRecorder} the same way
	 * `OffHeapTrafficRecorderTest.setUp` does, parameterized for benchmark use.
	 */
	@Nonnull
	public static OffHeapTrafficRecorder newRecorder(
		@Nonnull Path workDirectory,
		@Nonnull Scheduler scheduler,
		int blockSizeBytes,
		long memoryBufferSizeInBytes,
		long diskBufferSizeInBytes,
		long flushIntervalMs,
		int samplingPercentage
	) {
		workDirectory.toFile().mkdirs();
		final OffHeapTrafficRecorder recorder = new OffHeapTrafficRecorder(blockSizeBytes);
		final StorageOptions storageOptions = StorageOptions.builder()
			.outputBufferSize(blockSizeBytes)
			.workDirectory(workDirectory)
			.build();
		recorder.init(
			"perfCatalog",
			new FileManagementService(storageOptions),
			scheduler,
			storageOptions,
			TrafficRecordingOptions.builder()
				.enabled(true)
				.trafficSamplingPercentage(samplingPercentage)
				.trafficMemoryBufferSizeInBytes(memoryBufferSizeInBytes)
				.trafficDiskBufferSizeInBytes(diskBufferSizeInBytes)
				.trafficFlushIntervalInMilliseconds(flushIntervalMs)
				.build()
		);
		return recorder;
	}

	/**
	 * Builds a query whose recorded footprint scales with {@code approxPayloadBytes} (each primary key
	 * contributes ~4 bytes to the serialized payload), so JMH `@Param` sweeps can approximate a target
	 * record size without needing to know the exact Kryo encoding overhead.
	 */
	@Nonnull
	public static Query sampleQuery(int approxPayloadBytes) {
		final int keyCount = Math.max(1, approxPayloadBytes / 4);
		final int[] primaryKeys = new int[keyCount];
		for (int i = 0; i < keyCount; i++) {
			primaryKeys[i] = i + 1;
		}
		return query(
			collection(ENTITY_TYPE),
			filterBy(entityPrimaryKeyInSet(primaryKeys)),
			orderBy(entityPrimaryKeyNatural(OrderDirection.DESC)),
			require(entityFetchAll())
		);
	}

	/**
	 * Builds an entity upsert mutation whose single attribute value scales with {@code approxPayloadBytes}.
	 */
	@Nonnull
	public static Mutation sampleMutation(int primaryKey, int approxPayloadBytes) {
		return new EntityUpsertMutation(
			ENTITY_TYPE,
			primaryKey,
			EntityExistence.MUST_NOT_EXIST,
			new UpsertAttributeMutation("payload", "x".repeat(Math.max(1, approxPayloadBytes)))
		);
	}

}
