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


import com.google.common.collect.Lists;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TrafficRecordingOptions;
import io.evitadb.api.exception.IndexNotReady;
import io.evitadb.api.query.head.Label;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation.EntityExistence;
import io.evitadb.api.requestResponse.data.mutation.EntityUpsertMutation;
import io.evitadb.api.requestResponse.data.mutation.associatedData.UpsertAssociatedDataMutation;
import io.evitadb.api.requestResponse.data.mutation.attribute.UpsertAttributeMutation;
import io.evitadb.api.requestResponse.trafficRecording.*;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest.TrafficRecordingType;
import io.evitadb.core.executor.ImmediateScheduledThreadPoolExecutor;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.core.file.ExportFileService;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.externalApi.graphql.GraphQLProvider;
import io.evitadb.externalApi.rest.RestProvider;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.FileUtils;
import io.evitadb.utils.UUIDUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
	private AtomicLong duration = new AtomicLong(0);
	private AtomicInteger counter = new AtomicInteger(0);

	@BeforeEach
	void setUp() {
		this.trafficRecorder = new OffHeapTrafficRecorder(2_048);
		this.exportDirectory.toFile().mkdirs();
		final StorageOptions storageOptions = StorageOptions.builder()
			.outputBufferSize(2_048)
			.exportDirectory(this.exportDirectory)
			.build();
		final Scheduler scheduler = new Scheduler(new ImmediateScheduledThreadPoolExecutor());
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
			0
		);
	}

	@AfterEach
	void tearDown() throws IOException {
		this.trafficRecorder.close();
		FileUtils.deleteDirectory(this.exportDirectory);
	}

	@Test
	void shouldRecordAndReadAllTypes() {
		warmUpEmptyIndex();

		final UUID sessionId = UUID.randomUUID();
		this.trafficRecorder.createSession(sessionId, 1, OffsetDateTime.now());
		this.trafficRecorder.recordQuery(
			sessionId,
			"Some query",
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
			new int[]{1, 2, 3},
			null
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
			1,
			null
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
			1,
			null
		);
		this.trafficRecorder.recordMutation(
			sessionId,
			OffsetDateTime.now(),
			new EntityUpsertMutation(
				Entities.PRODUCT,
				2,
				EntityExistence.MUST_NOT_EXIST,
				new UpsertAttributeMutation("a", "b")
			),
			null
		);
		this.trafficRecorder.closeSession(sessionId, null);

		try (
			final Stream<TrafficRecording> recordings = this.trafficRecorder.getRecordingsReversed(
				TrafficRecordingCaptureRequest.builder()
					.content(TrafficRecordingContent.BODY)
					.sinceSessionSequenceId(80L)
					.build()
			)
		) {
			final List<TrafficRecording> theRecordings = recordings.toList();

			assertEquals(6, theRecordings.size());
			assertInstanceOf(SessionStartContainer.class, theRecordings.get(5));
			assertInstanceOf(QueryContainer.class, theRecordings.get(4));
			assertInstanceOf(EntityFetchContainer.class, theRecordings.get(3));
			assertInstanceOf(EntityEnrichmentContainer.class, theRecordings.get(2));
			assertInstanceOf(MutationContainer.class, theRecordings.get(1));
			assertInstanceOf(SessionCloseContainer.class, theRecordings.get(0));
		}

		try (
			final Stream<TrafficRecording> recordings = this.trafficRecorder.getRecordingsReversed(
				TrafficRecordingCaptureRequest.builder()
					.content(TrafficRecordingContent.BODY)
					.sinceSessionSequenceId(1L)
					.sinceRecordSessionOffset(3)
					.build()
			)
		) {
			final List<TrafficRecording> theRecordings = recordings.toList();

			assertEquals(4, theRecordings.size());
			assertInstanceOf(SessionStartContainer.class, theRecordings.get(3));
			assertInstanceOf(QueryContainer.class, theRecordings.get(2));
			assertInstanceOf(EntityFetchContainer.class, theRecordings.get(1));
			assertInstanceOf(EntityEnrichmentContainer.class, theRecordings.get(0));
		}
	}

	@Test
	void shouldCorrectlyPropagateLabelsAndInheritedValues() {
		warmUpEmptyIndex();

		final UUID firstSessionId = UUID.randomUUID();
		this.trafficRecorder.createSession(firstSessionId, 1, OffsetDateTime.now());

		final UUID firstSourceQueryId = UUIDUtil.randomUUID();
		this.trafficRecorder.setupSourceQuery(
			firstSessionId, firstSourceQueryId, OffsetDateTime.now(),
			"Whatever query", new Label[]{label(Label.LABEL_SOURCE_TYPE, GraphQLProvider.CODE)}, null
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

		this.trafficRecorder.closeSourceQuery(firstSessionId, firstSourceQueryId, null);
		this.trafficRecorder.closeSession(firstSessionId, null);

		final UUID secondSessionId = UUID.randomUUID();
		this.trafficRecorder.createSession(secondSessionId, 1, OffsetDateTime.now());

		final UUID secondSourceQueryId = UUIDUtil.randomUUID();
		this.trafficRecorder.setupSourceQuery(
			secondSessionId, secondSourceQueryId, OffsetDateTime.now(),
			"Whatever different query", new Label[]{label(Label.LABEL_SOURCE_TYPE, GraphQLProvider.CODE)}, null
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
		this.trafficRecorder.closeSourceQuery(secondSessionId, secondSourceQueryId, null);
		this.trafficRecorder.closeSession(secondSessionId, null);

		try (
			final Stream<TrafficRecording> recordings = this.trafficRecorder.getRecordings(
				TrafficRecordingCaptureRequest.builder()
					.content(TrafficRecordingContent.BODY)
					.labels(new io.evitadb.api.requestResponse.trafficRecording.Label(Label.LABEL_SOURCE_QUERY, firstSourceQueryId))
					.build()
			)
		) {
			final List<TrafficRecording> firstSourceQuerySubQueries = recordings.toList();

			assertEquals(3, firstSourceQuerySubQueries.size());
			assertEquals(2, firstSourceQuerySubQueries.get(0).recordSessionOffset());
			assertInstanceOf(QueryContainer.class, firstSourceQuerySubQueries.get(0));
			assertEquals(3, firstSourceQuerySubQueries.get(1).recordSessionOffset());
			assertInstanceOf(QueryContainer.class, firstSourceQuerySubQueries.get(1));
			assertEquals(4, firstSourceQuerySubQueries.get(2).recordSessionOffset());
			assertInstanceOf(SourceQueryStatisticsContainer.class, firstSourceQuerySubQueries.get(2));
		}

		try (
			final Stream<TrafficRecording> recordings = this.trafficRecorder.getRecordings(
				TrafficRecordingCaptureRequest.builder()
					.content(TrafficRecordingContent.BODY)
					.labels(new io.evitadb.api.requestResponse.trafficRecording.Label(Label.LABEL_SOURCE_QUERY, secondSourceQueryId))
					.build()
			)
		) {
			final List<TrafficRecording> secondSourceQuerySubQueries = recordings.toList();

			assertEquals(3, secondSourceQuerySubQueries.size());
			assertEquals(2, secondSourceQuerySubQueries.get(0).recordSessionOffset());
			assertInstanceOf(QueryContainer.class, secondSourceQuerySubQueries.get(0));
			assertEquals(3, secondSourceQuerySubQueries.get(1).recordSessionOffset());
			assertInstanceOf(QueryContainer.class, secondSourceQuerySubQueries.get(1));
			assertEquals(4, secondSourceQuerySubQueries.get(2).recordSessionOffset());
			assertInstanceOf(SourceQueryStatisticsContainer.class, secondSourceQuerySubQueries.get(2));
		}

		try (
			final Stream<TrafficRecording> recordings = this.trafficRecorder.getRecordings(
				TrafficRecordingCaptureRequest.builder()
					.content(TrafficRecordingContent.BODY)
					.labels(new io.evitadb.api.requestResponse.trafficRecording.Label("abc", "bee"))
					.build()
			)
		) {
			final List<TrafficRecording> beeSubQueries = recordings.toList();

			assertEquals(4, beeSubQueries.size());

			assertEquals(firstSessionId, beeSubQueries.get(0).sessionId());
			assertEquals(3, beeSubQueries.get(0).recordSessionOffset());
			assertInstanceOf(QueryContainer.class, beeSubQueries.get(0));

			assertEquals(firstSessionId, beeSubQueries.get(1).sessionId());
			assertEquals(4, beeSubQueries.get(1).recordSessionOffset());
			assertInstanceOf(SourceQueryStatisticsContainer.class, beeSubQueries.get(1));

			assertEquals(secondSessionId, beeSubQueries.get(2).sessionId());
			assertEquals(3, beeSubQueries.get(2).recordSessionOffset());
			assertInstanceOf(QueryContainer.class, beeSubQueries.get(2));

			assertEquals(secondSessionId, beeSubQueries.get(3).sessionId());
			assertEquals(4, beeSubQueries.get(3).recordSessionOffset());
			assertInstanceOf(SourceQueryStatisticsContainer.class, beeSubQueries.get(3));
		}

		// now try to get records with record offset
		try (
			final Stream<TrafficRecording> recordings = this.trafficRecorder.getRecordings(
				TrafficRecordingCaptureRequest.builder()
					.content(TrafficRecordingContent.BODY)
					.sinceSessionSequenceId(1L)
					.sinceRecordSessionOffset(2)
					.build()
			)
		) {
			final TrafficRecording firstRecordByOffset = recordings.findFirst().orElseThrow();
			assertEquals(1, firstRecordByOffset.sessionSequenceOrder());
			assertEquals(2, firstRecordByOffset.recordSessionOffset());

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

		// try to lookup multiple labels with same and different keys
		try (
			final Stream<TrafficRecording> recordings = this.trafficRecorder.getRecordings(
				TrafficRecordingCaptureRequest.builder()
					.content(TrafficRecordingContent.BODY)
					.labels(
						new io.evitadb.api.requestResponse.trafficRecording.Label("a", "b"),
						new io.evitadb.api.requestResponse.trafficRecording.Label("a", "bee"),
						new io.evitadb.api.requestResponse.trafficRecording.Label("c", "d"),
						new io.evitadb.api.requestResponse.trafficRecording.Label("c", "dfr")
					)
					.build()
			)
		) {
			final List<TrafficRecording> recordingsByMultipleLabels = recordings.toList();

			assertEquals(4, recordingsByMultipleLabels.size());
			assertEquals(firstSessionId, recordingsByMultipleLabels.get(0).sessionId());
			assertEquals(2, recordingsByMultipleLabels.get(0).recordSessionOffset());
			assertInstanceOf(QueryContainer.class, recordingsByMultipleLabels.get(0));

			assertEquals(firstSessionId, recordingsByMultipleLabels.get(1).sessionId());
			assertEquals(4, recordingsByMultipleLabels.get(1).recordSessionOffset());
			assertInstanceOf(SourceQueryStatisticsContainer.class, recordingsByMultipleLabels.get(1));

			assertEquals(secondSessionId, recordingsByMultipleLabels.get(2).sessionId());
			assertEquals(2, recordingsByMultipleLabels.get(2).recordSessionOffset());
			assertInstanceOf(QueryContainer.class, recordingsByMultipleLabels.get(2));

			assertEquals(secondSessionId, recordingsByMultipleLabels.get(3).sessionId());
			assertEquals(4, recordingsByMultipleLabels.get(3).recordSessionOffset());
			assertInstanceOf(SourceQueryStatisticsContainer.class, recordingsByMultipleLabels.get(3));
		}
	}

	@Test
	void shouldRecordLotOfDataInRingBufferWithWarmedUpIndex() {
		warmUpEmptyIndex();

		final UUID sessionId = writeBunchOfData(10, 10);

		// wait for the data to be written to the disk
		waitUntilDataBecomeAvailable(sessionId, 10_000);

		try (
			final Stream<TrafficRecording> recordings = this.trafficRecorder.getRecordings(
				TrafficRecordingCaptureRequest.builder()
					.content(TrafficRecordingContent.BODY)
					.sinceSessionSequenceId(0L)
					.sinceRecordSessionOffset(0)
					.build()
			)
		) {
			final List<TrafficRecording> allRecordings = recordings.toList();

			assertEquals(120, allRecordings.size());
		}
	}

	@Test
	void shouldGraduallyTraverseThroughDataInBothWays() {
		warmUpEmptyIndex();

		final UUID sessionId = writeBunchOfData(10, 10);

		// wait for the data to be written to the disk
		waitUntilDataBecomeAvailable(sessionId, 10_000);

		final List<TrafficRecording> forwardRecords = collectForwardRecords();

		assertEquals(120, forwardRecords.size());
		final Set<TrafficRecordId> ids = new HashSet<>(512);
		for (TrafficRecording forwardRecord : forwardRecords) {
			final TrafficRecordId trId = new TrafficRecordId(forwardRecord.sessionSequenceOrder(), forwardRecord.recordSessionOffset());
			assertFalse(ids.contains(trId));
			ids.add(trId);
		}

		final List<TrafficRecording> reverseRecords = collectReverseRecords();
		assertEquals(120, reverseRecords.size());

		// reversed reverse records should be the same as forward records
		assertEquals(forwardRecords, Lists.reverse(reverseRecords));
	}

	@Nonnull
	private List<TrafficRecording> collectForwardRecords() {
		final List<TrafficRecording> forwardRecords = new ArrayList<>(512);
		long lastSessionSequenceId = 0;
		int lastRecordSessionOffset = 0;
		boolean nextPage;
		do {
			try (
				final Stream<TrafficRecording> recordings = this.trafficRecorder.getRecordings(
					TrafficRecordingCaptureRequest.builder()
						.content(TrafficRecordingContent.BODY)
						.sinceSessionSequenceId(lastSessionSequenceId)
						.sinceRecordSessionOffset(lastRecordSessionOffset)
						.build()
				)
			) {
				final List<TrafficRecording> allRecordings = recordings.limit(5).toList();
				for (int i = 0; i < 4 && i < allRecordings.size(); i++) {
					forwardRecords.add(allRecordings.get(i));
				}

				final int size = allRecordings.size();
				nextPage = size == 5;
				if (nextPage) {
					final TrafficRecording lastRecording = Objects.requireNonNull(allRecordings.get(size - 1));
					lastSessionSequenceId = Objects.requireNonNull(lastRecording.sessionSequenceOrder());
					lastRecordSessionOffset = lastRecording.recordSessionOffset();
				}
			}
		} while (nextPage);
		return forwardRecords;
	}

	@Nonnull
	private List<TrafficRecording> collectReverseRecords() {
		final List<TrafficRecording> reverseRecords = new ArrayList<>(512);
		long lastSessionSequenceId = Long.MAX_VALUE;
		int lastRecordSessionOffset = Integer.MAX_VALUE;
		boolean nextPage;
		do {
			try (
				final Stream<TrafficRecording> recordings = this.trafficRecorder.getRecordingsReversed(
					TrafficRecordingCaptureRequest.builder()
						.content(TrafficRecordingContent.BODY)
						.sinceSessionSequenceId(lastSessionSequenceId)
						.sinceRecordSessionOffset(lastRecordSessionOffset)
						.build()
				)
			) {
				final List<TrafficRecording> allRecordings = recordings.limit(5).toList();
				for (int i = 0; i < 4 && i < allRecordings.size(); i++) {
					reverseRecords.add(allRecordings.get(i));
				}

				final int size = allRecordings.size();
				nextPage = size == 5;
				if (nextPage) {
					final TrafficRecording lastRecording = Objects.requireNonNull(allRecordings.get(size - 1));
					lastSessionSequenceId = Objects.requireNonNull(lastRecording.sessionSequenceOrder());
					lastRecordSessionOffset = lastRecording.recordSessionOffset();
				}
			}
		} while (nextPage);
		return reverseRecords;
	}

	@Test
	void shouldRecordLotOfDataInRingBufferWithColdIndex() {
		final UUID sessionId = writeBunchOfData(10, 10);

		// wait for the data to be written to the disk
		waitUntilDataBecomeAvailable(sessionId, 10_000);

		try (
			final Stream<TrafficRecording> recordings = this.trafficRecorder.getRecordings(
				TrafficRecordingCaptureRequest.builder()
					.content(TrafficRecordingContent.BODY)
					.sinceSessionSequenceId(0L)
					.sinceRecordSessionOffset(0)
					.build()
			)
		) {
			final List<TrafficRecording> allRecordings = recordings.toList();

			assertEquals(120, allRecordings.size());
		}
	}

	@Test
	void shouldRecordMoreDataThanBufferSizeForcingWrapAround() {
		final UUID sessionId = writeBunchOfData(10, 100);

		// wait for the data to be written to the disk
		waitUntilDataBecomeAvailable(sessionId, 10_000);

		try (
			final Stream<TrafficRecording> recordings = this.trafficRecorder.getRecordings(
				TrafficRecordingCaptureRequest.builder()
					.content(TrafficRecordingContent.BODY)
					.sinceSessionSequenceId(0L)
					.sinceRecordSessionOffset(0)
					.build()
			)
		) {
			final List<TrafficRecording> allRecordings = recordings.toList();

			assertTrue(allRecordings.size() > 120, "Actual size: " + allRecordings.size());
		}
	}

	@Test
	void shouldRecordAsLargeAsTheBufferItself() {
		final UUID sessionId = UUIDUtil.randomUUID();
		String[] veryLongString = new String[1024];
		for (int i = 0; i < 1024; i++) {
			StringBuilder singleString = new StringBuilder();
			for (int j = 32; j < 61; j++) {
				singleString.append(Character.valueOf((char)(32 + j % (126 - 32))));
			}
			veryLongString[i] = singleString.toString();
		}
		this.trafficRecorder.createSession(sessionId, 1, OffsetDateTime.now());
		this.trafficRecorder.recordMutation(
			sessionId,
			OffsetDateTime.now(),
			new EntityUpsertMutation(
				Entities.PRODUCT,
				1,
				EntityExistence.MUST_NOT_EXIST,
				new UpsertAssociatedDataMutation("a", veryLongString)
			),
			null
		);
		this.trafficRecorder.closeSession(sessionId, null);

		// wait for the data to be written to the disk
		waitUntilDataBecomeAvailable(sessionId, 10_000);

		try (
			final Stream<TrafficRecording> recordings = this.trafficRecorder.getRecordings(
				TrafficRecordingCaptureRequest.builder()
					.content(TrafficRecordingContent.BODY)
					.sinceSessionSequenceId(0L)
					.sinceRecordSessionOffset(0)
					.build()
			)
		) {
			final List<TrafficRecording> allRecordings = recordings.toList();

			assertEquals(3, allRecordings.size(), "Actual size: " + allRecordings.size());
			final MutationContainer mutation = (MutationContainer) allRecordings.get(1);
			final EntityUpsertMutation upsertMutation = (EntityUpsertMutation) mutation.mutation();
			final UpsertAssociatedDataMutation upsertAssociatedDataMutation = (UpsertAssociatedDataMutation) upsertMutation.getLocalMutations().get(0);
			final String[] value = (String[]) upsertAssociatedDataMutation.getAssociatedDataValue();
			assertEquals(1024, value.length);
		}
	}

	@Disabled("This test fails to often in parallel suite. Needs to be executed manually as a standalone test.")
	@Test
	void shouldRecordDataInParallelAndQueryOnThem() throws InterruptedException {
		final CountDownLatch writeLatch = writeDataInParallel(5, 5, 200);
		final CountDownLatch readLatch = readDataInParallel(5, 5, 200);

		final long waitForDataStart = System.currentTimeMillis();
		waitUntilDataBecomeAvailable(25, 120_000);
		System.out.println("Data available in " + (System.currentTimeMillis() - waitForDataStart) + " ms.");

		final long waitForWriteThreadsStopStart = System.currentTimeMillis();
		assertTrue(writeLatch.await(1, TimeUnit.MINUTES), "Threads should have finished by now.");
		System.out.println("Waiting for write threads being finished in " + (System.currentTimeMillis() - waitForWriteThreadsStopStart) + " ms.");

		final long waitForReadThreadsStopStart = System.currentTimeMillis();
		assertTrue(readLatch.await(1, TimeUnit.MINUTES), "Threads should have finished by now.");
		System.out.println("Waiting for read threads being finished in " + (System.currentTimeMillis() - waitForReadThreadsStopStart) + " ms.");

		System.out.println("Average read duration: " + (this.duration.get() / this.counter.get()) + " ms.");

		try (
			final Stream<TrafficRecording> recordings = this.trafficRecorder.getRecordings(
				TrafficRecordingCaptureRequest.builder()
					.content(TrafficRecordingContent.BODY)
					.sinceSessionSequenceId(0L)
					.sinceRecordSessionOffset(0)
					.build()
			)
		) {
			final List<TrafficRecording> allRecordings = recordings.toList();

			assertTrue(allRecordings.size() > 120);
		}
	}

	private void warmUpEmptyIndex() {
		// initialize empty index
		assertThrows(
			IndexNotReady.class,
			() -> {
				try (
					final Stream<?> stream = this.trafficRecorder.getRecordings(
						TrafficRecordingCaptureRequest.builder()
							.content(TrafficRecordingContent.BODY)
							.sinceSessionSequenceId(0L)
							.sinceRecordSessionOffset(0)
							.build()
					)
				) {
				}
			}
		);

		// check index is empty
		try (
			final Stream<TrafficRecording> stream = this.trafficRecorder.getRecordings(
				TrafficRecordingCaptureRequest.builder()
					.content(TrafficRecordingContent.BODY)
					.sinceSessionSequenceId(0L)
					.sinceRecordSessionOffset(0)
					.build()
			)
		) {
			assertEquals(0L, stream.count());
		}
	}

	private void createLabeledQuery(@Nonnull UUID sessionId, @Nonnull Label... labels) {
		this.trafficRecorder.recordQuery(
			sessionId,
			"Some query",
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
			new int[]{1, 2, 3},
			null
		);
	}

	private void waitUntilDataBecomeAvailable(long sessionSequenceId, int waitMilliseconds) {
		final long start = System.currentTimeMillis();
		List<TrafficRecording> recordings = null;
		do {
			try (
				final Stream<TrafficRecording> stream = this.trafficRecorder.getRecordings(
					TrafficRecordingCaptureRequest.builder()
						.content(TrafficRecordingContent.BODY)
						.sinceSessionSequenceId(sessionSequenceId)
						.type(TrafficRecordingType.SESSION_CLOSE)
						.build()
				);
			) {
				recordings = stream.toList();
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
			try (
				final Stream<TrafficRecording> stream = this.trafficRecorder.getRecordings(
					TrafficRecordingCaptureRequest.builder()
						.content(TrafficRecordingContent.BODY)
						.sessionId(sessionId)
						.sinceSessionSequenceId(0L)
						.type(TrafficRecordingType.SESSION_CLOSE)
						.build()
				)
			) {
				recordings = stream.toList();
				if (!recordings.isEmpty()) {
					break;
				}
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
					"Some query",
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
					new int[]{1, 2, 3},
					null
				);
			}
			this.trafficRecorder.closeSession(sessionId, null);
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
								"Some query",
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
								new int[]{1, 2, 3},
								null
							);
						}
						this.trafficRecorder.closeSession(sessionId, null);

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
						final long start = System.currentTimeMillis();
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

							this.duration.addAndGet(System.currentTimeMillis() - start);
							this.counter.incrementAndGet();
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

	private record TrafficRecordId(
		long sessionSequenceId,
		int recordSessionOffset
	) {
	}
}
