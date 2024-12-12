/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.store.traffic;


import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.query.head.Label;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.core.async.Scheduler;
import io.evitadb.core.file.ExportFileService;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * This test verifies {@link OffHeapTrafficRecorder} functionality.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class OffHeapTrafficRecorderTest implements EvitaTestSupport {
	private OffHeapTrafficRecorder trafficRecorder;
	private final Path exportDirectory = getPathInTargetDirectory(UUID.randomUUID() + "/export");

	@BeforeEach
	void setUp() {
		this.trafficRecorder = new OffHeapTrafficRecorder(2_048);
		this.exportDirectory.toFile().mkdirs();
		final StorageOptions storageOptions = StorageOptions.builder()
			.outputBufferSize(1_024)
			.exportDirectory(this.exportDirectory)
			.build();
		final Scheduler scheduler = new Scheduler(new ImmediateExecutorService(1));
		this.trafficRecorder.init(
			TEST_CATALOG,
			new ExportFileService(storageOptions, scheduler),
			scheduler,
			storageOptions,
			ServerOptions.builder()
				.trafficRecording(true)
				.trafficSamplingPercentage(0)
				.trafficMemoryBufferSizeInBytes(4096L)
				.trafficDiskBufferSizeInBytes(16_384L)
				.build()
		);
	}

	@AfterEach
	void tearDown() {
		FileUtils.deleteDirectory(this.exportDirectory);
	}

	@Test
	void shouldRecordAndReadAllTypes() {
		final UUID sessionId = UUID.randomUUID();
		this.trafficRecorder.createSession(sessionId, 1, OffsetDateTime.now());
		this.trafficRecorder.recordQuery(
			sessionId,
			query(
				collection(Entities.PRODUCT),
				filterBy(entityPrimaryKeyInSet(1, 2, 3)),
				orderBy(entityPrimaryKeyNatural(OrderDirection.DESC)),
				require(entityFetchAll())
			),
			new Label[]{
				new Label("a", "b"),
				new Label("c", "d")
			},
			OffsetDateTime.now(),
			15,
			456,
			12311,
			1, 2, 3
		);
		this.trafficRecorder.recordFetch(
			sessionId,
			query(
				collection(Entities.PRODUCT),
				filterBy(entityPrimaryKeyInSet(1)),
				require(entityFetchAll())
			),
			OffsetDateTime.now(),
			15,
			456,
			1
		);
		this.trafficRecorder.recordEnrichment(
			sessionId,
			query(
				collection(Entities.PRODUCT),
				filterBy(entityPrimaryKeyInSet(1)),
				require(entityFetchAll())
			),
			OffsetDateTime.now(),
			15,
			456,
			1
		);
		this.trafficRecorder.recordMutation(
			sessionId,
			OffsetDateTime.now(),
			new EntityUpsertMutation(
				Entities.PRODUCT,
				2,
				EntityExistence.MUST_NOT_EXIST,
				new UpsertAttributeMutation("a", "b")
			)
		);
		this.trafficRecorder.closeSession(sessionId);
	}

	@Test
	void shouldRecordLotOfDataInRingBuffer() {
		fail("Not implemented");
	}

	private static class ImmediateExecutorService extends ScheduledThreadPoolExecutor {

		public ImmediateExecutorService(int corePoolSize) {
			super(corePoolSize);
		}

		@Override
		public void execute(@Nonnull Runnable command) {
			command.run();
		}

		@Nonnull
		@Override
		public Future<?> submit(@Nonnull Runnable task) {
			task.run();
			return CompletableFuture.completedFuture(null);
		}

		@Nonnull
		@Override
		public <T> Future<T> submit(@Nonnull Runnable task, T result) {
			task.run();
			return CompletableFuture.completedFuture(result);
		}

		@Nonnull
		@Override
		public <T> Future<T> submit(@Nonnull Callable<T> task) {
			final T result;
			try {
				result = task.call();
				return CompletableFuture.completedFuture(result);
			} catch (Exception e) {
				return CompletableFuture.failedFuture(e);
			}
		}

	}
}
