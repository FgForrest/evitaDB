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
import io.evitadb.api.exception.IndexNotReady;
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
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.rest.RestProvider;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.FileUtils;
import io.evitadb.utils.UUIDUtil;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.*;

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
		warmUpEmptyIndex();

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
				label("a", "b"),
				label("c", "d")
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

		final List<TrafficRecording> recordings = this.trafficRecorder.getRecordings(
			TrafficRecordingCaptureRequest.builder()
				.content(TrafficRecordingContent.BODY)
				.sinceSessionSequenceId(0L)
				.sinceRecordSessionOffset(0)
				.build()
		).toList();

		assertEquals(6, recordings.size());
		assertInstanceOf(SessionStartContainer.class, recordings.get(0));
		assertInstanceOf(QueryContainer.class, recordings.get(1));
		assertInstanceOf(EntityFetchContainer.class, recordings.get(2));
		assertInstanceOf(EntityEnrichmentContainer.class, recordings.get(3));
		assertInstanceOf(MutationContainer.class, recordings.get(4));
		assertInstanceOf(SessionCloseContainer.class, recordings.get(5));
	}

	@Test
	void shouldCorrectlyPropagateLabelsAndInheritedValues() {
		warmUpEmptyIndex();

		final UUID firstSessionId = UUID.randomUUID();
		this.trafficRecorder.createSession(firstSessionId, 1, OffsetDateTime.now());

		final UUID firstSourceQueryId = UUIDUtil.randomUUID();
		this.trafficRecorder.setupSourceQuery(
			firstSessionId, firstSourceQueryId, OffsetDateTime.now(),
			"Whatever query", GraphQLProvider.CODE
		);
		createLabeledQuery(
			firstSessionId,
			label(Label.LABEL_SOURCE_QUERY, firstSourceQueryId),
			label(Label.LABEL_SOURCE_TYPE, GraphQLProvider.CODE),
			label("a", "b"),
			label("c", "d")
		);
		createLabeledQuery(
			firstSessionId,
			label(Label.LABEL_SOURCE_QUERY, firstSourceQueryId),
			label(Label.LABEL_SOURCE_TYPE, GraphQLProvider.CODE),
			label("abc", "bee"),
			label("ced", "dff")
		);

		this.trafficRecorder.closeSourceQuery(firstSessionId, firstSourceQueryId);
		this.trafficRecorder.closeSession(firstSessionId);

		final UUID secondSessionId = UUID.randomUUID();
		this.trafficRecorder.createSession(secondSessionId, 1, OffsetDateTime.now());

		final UUID secondSourceQueryId = UUIDUtil.randomUUID();
		this.trafficRecorder.setupSourceQuery(
			secondSessionId, secondSourceQueryId, OffsetDateTime.now(),
			"Whatever different query", GraphQLProvider.CODE
		);
		createLabeledQuery(
			secondSessionId,
			label(Label.LABEL_SOURCE_QUERY, secondSourceQueryId),
			label(Label.LABEL_SOURCE_TYPE, RestProvider.CODE),
			label("a", "bee"),
			label("c", "dfr")
		);
		createLabeledQuery(
			secondSessionId,
			label(Label.LABEL_SOURCE_QUERY, secondSourceQueryId),
			label(Label.LABEL_SOURCE_TYPE, RestProvider.CODE),
			label("abc", "bee"),
			label("ce", "whatever")
		);
		this.trafficRecorder.closeSourceQuery(secondSessionId, secondSourceQueryId);
		this.trafficRecorder.closeSession(secondSessionId);

		final List<TrafficRecording> firstSourceQuerySubQueries = this.trafficRecorder.getRecordings(
			TrafficRecordingCaptureRequest.builder()
				.content(TrafficRecordingContent.BODY)
				.labels(new io.evitadb.api.requestResponse.trafficRecording.Label(Label.LABEL_SOURCE_QUERY, firstSourceQueryId))
				.build()
		).toList();

		assertEquals(2, firstSourceQuerySubQueries.size());
		assertEquals(2, firstSourceQuerySubQueries.get(0).recordSessionOffset());
		assertEquals(3, firstSourceQuerySubQueries.get(1).recordSessionOffset());

		final List<TrafficRecording> secondSourceQuerySubQueries = this.trafficRecorder.getRecordings(
			TrafficRecordingCaptureRequest.builder()
				.content(TrafficRecordingContent.BODY)
				.labels(new io.evitadb.api.requestResponse.trafficRecording.Label(Label.LABEL_SOURCE_QUERY, secondSourceQueryId))
				.build()
		).toList();

		assertEquals(2, secondSourceQuerySubQueries.size());
		assertEquals(2, secondSourceQuerySubQueries.get(0).recordSessionOffset());
		assertEquals(3, secondSourceQuerySubQueries.get(1).recordSessionOffset());

		final List<TrafficRecording> beeSubQueries = this.trafficRecorder.getRecordings(
			TrafficRecordingCaptureRequest.builder()
				.content(TrafficRecordingContent.BODY)
				.labels(new io.evitadb.api.requestResponse.trafficRecording.Label("abc", "bee"))
				.build()
		).toList();

		assertEquals(2, beeSubQueries.size());
		assertEquals(firstSessionId, beeSubQueries.get(0).sessionId());
		assertEquals(3, beeSubQueries.get(0).recordSessionOffset());
		assertEquals(secondSessionId, beeSubQueries.get(1).sessionId());
		assertEquals(3, beeSubQueries.get(1).recordSessionOffset());

		final Collection<String> labelNamesByCardinality = this.trafficRecorder.getLabelsNamesOrderedByCardinality(null, 10);
		assertEquals(8, labelNamesByCardinality.size());
		assertArrayEquals(
			new String[]{"a", "abc", "c", "entity-type", Label.LABEL_SOURCE_QUERY, Label.LABEL_SOURCE_TYPE, "ce", "ced"},
			labelNamesByCardinality.toArray(String[]::new)
		);

		final Collection<String> labelValuesByCardinality = this.trafficRecorder.getLabelValuesOrderedByCardinality(Label.LABEL_SOURCE_QUERY, null, 10);
		assertEquals(2, labelValuesByCardinality.size());

		final Collection<String> entityType = this.trafficRecorder.getLabelValuesOrderedByCardinality("entity-type", null, 10);
		assertEquals(1, entityType.size());
		assertEquals(EvitaDataTypes.formatValue(Entities.PRODUCT), entityType.iterator().next());

		final Collection<String> valueAByCardinality = this.trafficRecorder.getLabelValuesOrderedByCardinality("a", null, 10);
		assertEquals(2, valueAByCardinality.size());
		assertArrayEquals(
			new String[]{"'b'", "'bee'"},
			valueAByCardinality.toArray(String[]::new)
		);
	}

	@Test
	void shouldRecordLotOfDataInRingBufferWithWarmedUpIndex() {
		warmUpEmptyIndex();

		final UUID sessionId = writeBunchOfData(10, 10);

		// wait for the data to be written to the disk
		waitUntilDataBecomeAvailable(sessionId, 10_000);

		final List<TrafficRecording> allRecordings = this.trafficRecorder.getRecordings(
			TrafficRecordingCaptureRequest.builder()
				.content(TrafficRecordingContent.BODY)
				.sinceSessionSequenceId(0L)
				.sinceRecordSessionOffset(0)
				.build()
		).toList();

		assertEquals(120, allRecordings.size());
	}

	@Test
	void shouldRecordLotOfDataInRingBufferWithColdIndex() {
		final UUID sessionId = writeBunchOfData(10, 10);

		// wait for the data to be written to the disk
		waitUntilDataBecomeAvailable(sessionId, 10_000);

		final List<TrafficRecording> allRecordings = this.trafficRecorder.getRecordings(
			TrafficRecordingCaptureRequest.builder()
				.content(TrafficRecordingContent.BODY)
				.sinceSessionSequenceId(0L)
				.sinceRecordSessionOffset(0)
				.build()
		).toList();

		assertEquals(120, allRecordings.size());
	}

	@Test
	void shouldRecordMoreDataThanBufferSizeForcingWrapAround() {
		final UUID sessionId = writeBunchOfData(10, 100);

		// wait for the data to be written to the disk
		waitUntilDataBecomeAvailable(sessionId, 1000_000);

		final List<TrafficRecording> allRecordings = this.trafficRecorder.getRecordings(
			TrafficRecordingCaptureRequest.builder()
				.content(TrafficRecordingContent.BODY)
				.sinceSessionSequenceId(0L)
				.sinceRecordSessionOffset(0)
				.build()
		).toList();

		assertTrue(allRecordings.size() > 120);
	}

	@Test
	void shouldRecordDataInParallelAndQueryOnThem() throws InterruptedException {
		final CountDownLatch writeLatch = writeDataInParallel(5, 5, 200);
		final CountDownLatch readLatch = readDataInParallel(5, 5, 200);

		final long waitForDataStart = System.currentTimeMillis();
		waitUntilDataBecomeAvailable(25, 60_000);
		System.out.println("Data available in " + (System.currentTimeMillis() - waitForDataStart) + " ms.");

		final long waitForWriteThreadsStopStart = System.currentTimeMillis();
		assertTrue(writeLatch.await(1, TimeUnit.MINUTES), "Threads should have finished by now.");
		System.out.println("Waiting for write threads being finished in " + (System.currentTimeMillis() - waitForWriteThreadsStopStart) + " ms.");

		final long waitForReadThreadsStopStart = System.currentTimeMillis();
		assertTrue(readLatch.await(1, TimeUnit.MINUTES), "Threads should have finished by now.");
		System.out.println("Waiting for read threads being finished in " + (System.currentTimeMillis() - waitForReadThreadsStopStart) + " ms.");

		final List<TrafficRecording> allRecordings = this.trafficRecorder.getRecordings(
			TrafficRecordingCaptureRequest.builder()
				.content(TrafficRecordingContent.BODY)
				.sinceSessionSequenceId(0L)
				.sinceRecordSessionOffset(0)
				.build()
		).toList();

		assertTrue(allRecordings.size() > 120);
	}

	private void warmUpEmptyIndex() {
		// initialize empty index
		assertThrows(
			IndexNotReady.class,
			() -> this.trafficRecorder.getRecordings(
				TrafficRecordingCaptureRequest.builder()
					.content(TrafficRecordingContent.BODY)
					.sinceSessionSequenceId(0L)
					.sinceRecordSessionOffset(0)
					.build()
			)
		);

		// check index is empty
		assertEquals(
			0L,
			this.trafficRecorder.getRecordings(
				TrafficRecordingCaptureRequest.builder()
					.content(TrafficRecordingContent.BODY)
					.sinceSessionSequenceId(0L)
					.sinceRecordSessionOffset(0)
					.build()
			).count()
		);
	}

	private void createLabeledQuery(@Nonnull UUID sessionId, @Nonnull Label... labels) {
		this.trafficRecorder.recordQuery(
			sessionId,
			query(
				collection(Entities.PRODUCT),
				filterBy(entityPrimaryKeyInSet(1, 2, 3)),
				orderBy(entityPrimaryKeyNatural(OrderDirection.DESC)),
				require(entityFetchAll())
			),
			labels,
			OffsetDateTime.now(),
			15,
			456,
			12311,
			1, 2, 3
		);
	}

	private void waitUntilDataBecomeAvailable(long sessionSequenceId, int waitMilliseconds) {
		final long start = System.currentTimeMillis();
		List<TrafficRecording> recordings = null;
		do {
			try {
				recordings = this.trafficRecorder.getRecordings(
					TrafficRecordingCaptureRequest.builder()
						.content(TrafficRecordingContent.BODY)
						.sinceSessionSequenceId(sessionSequenceId)
						.type(TrafficRecordingType.SESSION_CLOSE)
						.build()
				).toList();
				break;
			} catch (IndexNotReady ignored) {
				// ignore and retry
			}
		} while (System.currentTimeMillis() - start < waitMilliseconds);

		if (recordings == null) {
			fail("Last recording was not written to the disk within the specified time limit.");
		}
	}

	private void waitUntilDataBecomeAvailable(UUID sessionId, int waitMilliseconds) {
		final long start = System.currentTimeMillis();
		List<TrafficRecording> recordings = null;
		do {
			try {
				recordings = this.trafficRecorder.getRecordings(
					TrafficRecordingCaptureRequest.builder()
						.content(TrafficRecordingContent.BODY)
						.sessionId(sessionId)
						.sinceSessionSequenceId(0L)
						.type(TrafficRecordingType.SESSION_CLOSE)
						.build()
				).toList();
				break;
			} catch (IndexNotReady ignored) {
				// ignore and retry
			}
		} while (System.currentTimeMillis() - start < waitMilliseconds);

		if (recordings == null) {
			fail("Last recording was not written to the disk within the specified time limit.");
		}
	}

	@Nonnull
	private UUID writeBunchOfData(int sessionCount, int queryCountInSession) {
		UUID sessionId = null;
		for (int i = 0; i < sessionCount; i++) {
			sessionId = UUID.randomUUID();
			this.trafficRecorder.createSession(sessionId, 1, OffsetDateTime.now());
			for (int j = 0; j < queryCountInSession; j++) {
				this.trafficRecorder.recordQuery(
					sessionId,
					query(
						collection(Entities.PRODUCT),
						filterBy(entityPrimaryKeyInSet(1, 2, 3)),
						orderBy(entityPrimaryKeyNatural(OrderDirection.DESC)),
						require(entityFetchAll())
					),
					new Label[]{
						label("a", "b"),
						label("c", "d")
					},
					OffsetDateTime.now(),
					15,
					456,
					12311,
					1, 2, 3
				);
			}
			this.trafficRecorder.closeSession(sessionId);
			System.out.println("Session #" + (i + 1) + " " + sessionId + " closed.");
		}
		return Objects.requireNonNull(sessionId);
	}

	@Nonnull
	private CountDownLatch writeDataInParallel(int threadCount, int sessionsPerThread, int delayInMilliseconds) {
		final CountDownLatch latch = new CountDownLatch(threadCount);
		for (int thread = 0; thread < threadCount; thread++) {
			int finalThread = thread;
			final Thread theThread = new Thread(
				() -> {
					for (int sessionIndex = 0; sessionIndex < sessionsPerThread; sessionIndex++) {
						final UUID sessionId = UUID.randomUUID();
						this.trafficRecorder.createSession(sessionId, 1, OffsetDateTime.now());
						for (int queryIndex = 0; queryIndex < 5; queryIndex++) {
							this.trafficRecorder.recordQuery(
								sessionId,
								query(
									collection(Entities.PRODUCT),
									filterBy(entityPrimaryKeyInSet(1, 2, 3)),
									orderBy(entityPrimaryKeyNatural(OrderDirection.DESC)),
									require(entityFetchAll())
								),
								new Label[]{
									label("a", "b"),
									label("c", "d")
								},
								OffsetDateTime.now(),
								15,
								456,
								12311,
								1, 2, 3
							);
						}
						this.trafficRecorder.closeSession(sessionId);

						try {
							Thread.sleep(delayInMilliseconds);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						}
					}
					System.out.println("Thread #" + (finalThread + 1) + " finished.");
					latch.countDown();
				}
			);
			theThread.start();
		}
		return latch;
	}

	@Nonnull
	private CountDownLatch readDataInParallel(int threadCount, int sessionsPerThread, int delayInMilliseconds) {
		final CountDownLatch latch = new CountDownLatch(threadCount);
		for (int thread = 0; thread < threadCount; thread++) {
			int finalThread = thread;
			final Thread theThread = new Thread(
				() -> {
					long overallCount = 0;
					for (int sessionIndex = 0; sessionIndex < sessionsPerThread; sessionIndex++) {
						try (
							final Stream<TrafficRecording> recordings = this.trafficRecorder.getRecordings(
								TrafficRecordingCaptureRequest.builder()
									.content(TrafficRecordingContent.BODY)
									.sinceSessionSequenceId(0L)
									.sinceRecordSessionOffset(0)
									.build()
							)
						) {
							overallCount += recordings
								.limit(50)
								.count();
						} catch (IndexNotReady ignored) {
							// ignore and retry
						}

						try {
							Thread.sleep(delayInMilliseconds);
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
						}
					}
					System.out.println("Thread #" + (finalThread + 1) + " finished (read " + overallCount + " records).");
					latch.countDown();
				}
			);
			theThread.start();
		}
		return latch;
	}

	private static class ImmediateExecutorService extends ScheduledThreadPoolExecutor {

		public ImmediateExecutorService(int corePoolSize) {
			super(corePoolSize);
		}

		@Nonnull
		@Override
		public ScheduledFuture<?> schedule(@Nonnull Runnable command, long delay, @Nonnull TimeUnit unit) {
			if (delay > 0) {
				return super.schedule(command, delay, unit);
			} else {
				command.run();
				return new TestScheduledFuture<>(CompletableFuture.completedFuture(null));
			}
		}

		@Nonnull
		@Override
		public <V> ScheduledFuture<V> schedule(@Nonnull Callable<V> callable, long delay, @Nonnull TimeUnit unit) {
			if (delay > 0) {
				return super.schedule(callable, delay, unit);
			} else {
				try {
					final V result = callable.call();
					return new TestScheduledFuture<>(CompletableFuture.completedFuture(result));
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}
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

		@RequiredArgsConstructor
		@EqualsAndHashCode
		private static class TestScheduledFuture<T> implements ScheduledFuture<T> {
			@Delegate
			private final CompletableFuture<T> future;

			@Override
			public long getDelay(@Nonnull TimeUnit delay) {
				return Long.MIN_VALUE;
			}

			@Override
			public int compareTo(@Nonnull Delayed o) {
				throw new UnsupportedOperationException();
			}

		}
	}
}
