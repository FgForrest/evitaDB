/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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


import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TrafficRecordingOptions;
import io.evitadb.api.query.head.Label;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.trafficRecording.EntityEnrichmentContainer;
import io.evitadb.api.requestResponse.trafficRecording.EntityFetchContainer;
import io.evitadb.api.requestResponse.trafficRecording.MutationContainer;
import io.evitadb.api.requestResponse.trafficRecording.QueryContainer;
import io.evitadb.api.requestResponse.trafficRecording.SessionCloseContainer;
import io.evitadb.api.requestResponse.trafficRecording.SessionStartContainer;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecording;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest.TrafficRecordingType;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingContent;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * This test verifies {@link OffHeapTrafficRecorder} functionality.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class OffHeapTrafficRecorderTest implements EvitaTestSupport {
	private final Path exportDirectory = getPathInTargetDirectory(UUID.randomUUID() + "/export");
	private OffHeapTrafficRecorder trafficRecorder;

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
			TrafficRecordingOptions.builder()
				.enabled(true)
				.trafficSamplingPercentage(100)
				.trafficMemoryBufferSizeInBytes(32768L)
				.trafficDiskBufferSizeInBytes(65536L)
				.build(),
			0, 0
		);
	}

	@AfterEach
	void tearDown() {
		FileUtils.deleteDirectory(this.exportDirectory);
	}

	@Test
	void shouldRecordAndReadAllTypes() {
		final UUID sessionId = UUID.randomUUID();
		assertEquals(
			0,
			this.trafficRecorder.getRecordings(
				TrafficRecordingCaptureRequest.builder()
					.content(TrafficRecordingContent.BODY)
					.sinceSessionSequenceId(0L)
					.sinceRecordSessionOffset(0)
					.allTypes()
					.build()
			).count()
		);
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

		final long start = System.currentTimeMillis();
		List<TrafficRecording> recordings;
		do {
			recordings = this.trafficRecorder.getRecordings(
				TrafficRecordingCaptureRequest.builder()
					.content(TrafficRecordingContent.BODY)
					.sinceSessionSequenceId(0L)
					.sinceRecordSessionOffset(0)
					.allTypes()
					.build()
			).toList();
		} while (recordings.isEmpty() && System.currentTimeMillis() - start < 10_000);

		assertEquals(6, recordings.size());
		assertInstanceOf(SessionStartContainer.class, recordings.get(0));
		assertInstanceOf(QueryContainer.class, recordings.get(1));
		assertInstanceOf(EntityFetchContainer.class, recordings.get(2));
		assertInstanceOf(EntityEnrichmentContainer.class, recordings.get(3));
		assertInstanceOf(MutationContainer.class, recordings.get(4));
		assertInstanceOf(SessionCloseContainer.class, recordings.get(5));
	}

	@Test
	void shouldRecordLotOfDataInRingBuffer() throws InterruptedException {
		// initialize empty index
		this.trafficRecorder.getRecordings(
			TrafficRecordingCaptureRequest.builder()
				.content(TrafficRecordingContent.BODY)
				.sinceSessionSequenceId(0L)
				.sinceRecordSessionOffset(0)
				.allTypes()
				.build()
		).toList();

		UUID sessionId = null;
		for (int i = 0; i < 10; i++) {
			sessionId = UUID.randomUUID();
			this.trafficRecorder.createSession(sessionId, 1, OffsetDateTime.now());
			for (int j = 0; j < 10; j++) {
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
			}
			this.trafficRecorder.closeSession(sessionId);
		}

		// wait for the data to be written to the disk
		final long start = System.currentTimeMillis();
		List<TrafficRecording> recordings;
		do {
			recordings = this.trafficRecorder.getRecordings(
				TrafficRecordingCaptureRequest.builder()
					.content(TrafficRecordingContent.BODY)
					.sessionId(sessionId)
					.sinceSessionSequenceId(0L)
					.type(TrafficRecordingType.SESSION_CLOSE)
					.build()
			).toList();
		} while (recordings.isEmpty() && System.currentTimeMillis() - start < 10_000);

		if (recordings.isEmpty()) {
			fail("Last recording was not written to the disk within the specified time limit.");
		}

		final List<TrafficRecording> allRecordings = this.trafficRecorder.getRecordings(
			TrafficRecordingCaptureRequest.builder()
				.content(TrafficRecordingContent.BODY)
				.sinceSessionSequenceId(0L)
				.sinceRecordSessionOffset(0)
				.allTypes()
				.build()
		).toList();

		assertEquals(120, allRecordings.size());
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
