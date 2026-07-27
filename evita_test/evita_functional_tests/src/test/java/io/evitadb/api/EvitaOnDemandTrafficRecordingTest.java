/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.api;


import com.github.javafaker.Faker;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.TrafficRecordingOptions;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecording;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest;
import io.evitadb.api.task.ServerTask;
import io.evitadb.core.Evita;
import io.evitadb.core.session.EvitaInternalSessionContract;
import io.evitadb.core.traffic.TrafficRecordingSettings;
import io.evitadb.externalApi.grpc.testUtils.TestDataProvider;
import io.evitadb.store.traffic.InputStreamTrafficRecordReader;
import io.evitadb.stream.AbstractRandomAccessInputStream;
import io.evitadb.stream.RandomAccessFileInputStream;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.generator.DataGenerator;
import io.evitadb.utils.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.annotation.Nonnull;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.TRAFFIC_ENGINE;
import static java.util.Optional.ofNullable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test is a integration test for on-demand traffic recording facility in evitaDB.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(CONTRACT)
@Tag(QUERY)
@Tag(TRAFFIC_ENGINE)
public class EvitaOnDemandTrafficRecordingTest implements EvitaTestSupport {
	public static final String ATTRIBUTE_CODE = "code";
	/**
	 * Seed for data generation.
	 */
	private static final long SEED = 10;
	/**
	 * Count of the product that will exist in the database BEFORE the test starts.
	 */
	private static final int INITIAL_COUNT_OF_PRODUCTS = 100;
	/**
	 * Instance of the data generator that is used for randomizing artificial test data.
	 */
	protected final DataGenerator dataGenerator = new DataGenerator();
	/**
	 * Index of created entities that allows to retrieve referenced entities when creating product.
	 */
	protected final Map<Serializable, Integer> generatedEntities = new HashMap<>();
	/**
	 * Function allowing to pseudo randomly pick referenced entity for the product.
	 */
	protected final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> {
		final Integer entityCount = this.generatedEntities.computeIfAbsent(entityType, serializable -> 0);
		final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
		return primaryKey == 0 ? null : primaryKey;
	};
	/**
	 * Created randomized product schema.
	 */
	protected SealedEntitySchema productSchema;
	/**
	 * Iterator that infinitely produces new artificial products.
	 */
	protected Iterator<EntityBuilder> productIterator;
	/**
	 * Evita instance.
	 */
	private TestPaths paths;
	private Evita evita;

	/**
	 * Creates new product stream for the iteration.
	 */
	protected Stream<EntityBuilder> getProductStream() {
		return this.dataGenerator.generateEntities(
			this.productSchema,
			this.randomEntityPicker,
			SEED
		);
	}

	/**
	 * Creates new entity and inserts it into the index.
	 */
	protected void createEntity(
		@Nonnull EvitaSessionContract session, @Nonnull Map<Serializable, Integer> generatedEntities,
		@Nonnull EntityBuilder it
	) {
		final EntityReferenceContract insertedEntity = session.upsertEntity(it);
		generatedEntities.compute(
			insertedEntity.getType(),
			(serializable, existing) -> ofNullable(existing).orElse(0) + 1
		);
	}

	@BeforeEach
	void setUp() throws IOException {
		this.paths = createTestPaths("EvitaOnDemandTrafficRecordingTest");
		this.dataGenerator.clear();
		this.generatedEntities.clear();
		final String catalogName = "testCatalog";
		// prepare database
		this.evita = new Evita(
			getEvitaConfiguration()
		);
		this.evita.defineCatalog(TEST_CATALOG);
		// create bunch or entities for referencing in products
		this.evita.updateCatalog(
			catalogName,
			session -> {
				session.getCatalogSchema()
					.openForWrite()
					.withAttribute(ATTRIBUTE_CODE, String.class, GlobalAttributeSchemaEditor::uniqueGlobally)
					.updateVia(session);

				this.dataGenerator.generateEntities(
						this.dataGenerator.getSampleBrandSchema(session),
						this.randomEntityPicker,
						SEED
					)
					.limit(5)
					.forEach(it -> createEntity(session, this.generatedEntities, it));

				this.dataGenerator.generateEntities(
						this.dataGenerator.getSampleCategorySchema(session),
						this.randomEntityPicker,
						SEED
					)
					.limit(10)
					.forEach(it -> createEntity(session, this.generatedEntities, it));

				this.dataGenerator.generateEntities(
						this.dataGenerator.getSamplePriceListSchema(session),
						this.randomEntityPicker,
						SEED
					)
					.limit(4)
					.forEach(it -> createEntity(session, this.generatedEntities, it));

				this.dataGenerator.generateEntities(
						this.dataGenerator.getSampleStoreSchema(session),
						this.randomEntityPicker,
						SEED
					)
					.limit(12)
					.forEach(it -> createEntity(session, this.generatedEntities, it));

				this.dataGenerator.generateEntities(
						this.dataGenerator.getSampleParameterGroupSchema(session),
						this.randomEntityPicker,
						SEED
					)
					.limit(20)
					.forEach(it -> createEntity(session, this.generatedEntities, it));

				this.dataGenerator.generateEntities(
						this.dataGenerator.getSampleParameterSchema(session),
						this.randomEntityPicker,
						SEED
					)
					.limit(200)
					.forEach(it -> createEntity(session, this.generatedEntities, it));

				this.productSchema = this.dataGenerator.getSampleProductSchema(
					session,
					entitySchemaBuilder -> {
						entitySchemaBuilder
							.withoutGeneratedPrimaryKey()
							.withGlobalAttribute(ATTRIBUTE_CODE)
							.withReferenceToEntity(
								Entities.PARAMETER,
								Entities.PARAMETER,
								Cardinality.ZERO_OR_MORE,
								thatIs -> thatIs.faceted().withGroupTypeRelatedToEntity(Entities.PARAMETER_GROUP)
							);
					}
				);
				this.dataGenerator.generateEntities(
						this.productSchema,
						this.randomEntityPicker,
						SEED
					)
					.limit(INITIAL_COUNT_OF_PRODUCTS)
					.forEach(session::upsertEntity);

				session.goLiveAndClose();
			}
		);
		// create product iterator
		this.productIterator = getProductStream().iterator();
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	void manualTrafficRecordingStartAndStop() throws IOException {
		final ServerTask<TrafficRecordingSettings, FileForFetch> recordingTask = this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaInternalSessionContract internalSession = (EvitaInternalSessionContract) session;
				// start recording
				return internalSession.startRecording(
					100, true, null, null, 32_000L
				);
			}
		);

		generateSomeTraffic();

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaInternalSessionContract internalSession = (EvitaInternalSessionContract) session;
				// stop recording
				return internalSession.stopRecording(recordingTask.getStatus().taskId()).result();
			}
		);

		final FileForFetch fileForFetch = recordingTask.getFutureResult().join();
		assertNotNull(fileForFetch);

		// list files in the ZIP archive
		final String[] fileNames = listAndVerifyFilesInArchive(fileForFetch);

		assertTrue(fileForFetch.totalSizeInBytes() > 8000);
		// the chunk file size of 32 kB combined with the deterministic traffic produced by
		// `generateSomeTraffic()` lands close to the 3↔4 chunk boundary; sub-millisecond timing
		// jitter (encoded in `now` and `duration` of every record via Kryo varint) shifts the
		// chunk-3 close point by one session, so accept either count
		final long chunkCount = Arrays.stream(fileNames).filter(name -> name.startsWith("traffic_recording_")).count();
		assertTrue(chunkCount == 3 || chunkCount == 4, "Expected 3 or 4 traffic recording chunks, got " + chunkCount);
		assertEquals(1, Arrays.stream(fileNames).filter(name -> name.equals("metadata.txt")).count());
	}

	@Test
	void manualTrafficRecordingStartAndStopWithoutExportingFile() {
		final ServerTask<TrafficRecordingSettings, FileForFetch> recordingTask = this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaInternalSessionContract internalSession = (EvitaInternalSessionContract) session;
				// start recording
				return internalSession.startRecording(
					100, false, null, null, 32_000L
				);
			}
		);

		generateSomeTraffic();

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaInternalSessionContract internalSession = (EvitaInternalSessionContract) session;
				// stop recording
				return internalSession.stopRecording(recordingTask.getStatus().taskId()).result();
			}
		);

		assertNull(recordingTask.getFutureResult().join());
	}

	@Test
	void manualTrafficRecordingStartAndAutomaticStopWhenFileSizeIsReached() throws IOException {
		final ServerTask<TrafficRecordingSettings, FileForFetch> recordingTask = this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaInternalSessionContract internalSession = (EvitaInternalSessionContract) session;
				// start recording
				return internalSession.startRecording(
					100, true, null, 4000L, 32_000L
				);
			}
		);

		generateSomeTraffic();

		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaInternalSessionContract internalSession = (EvitaInternalSessionContract) session;
				// stop recording
				return internalSession.stopRecording(recordingTask.getStatus().taskId()).result();
			}
		);

		final FileForFetch fileForFetch = recordingTask.getFutureResult().join();
		assertNotNull(fileForFetch);

		// list files in the ZIP archive
		final String[] fileNames = listAndVerifyFilesInArchive(fileForFetch);

		assertTrue(
			// the size might be bigger because we flush entire sessions and deflater has its own 8KB buffer
			fileForFetch.totalSizeInBytes() > 4000L && fileForFetch.totalSizeInBytes() < 4000L + 8192L,
			"File size: " + fileForFetch.totalSizeInBytes()
		);
		assertEquals(2, Arrays.stream(fileNames).filter(name -> name.startsWith("traffic_recording_")).count());
		assertEquals(1, Arrays.stream(fileNames).filter(name -> name.equals("metadata.txt")).count());
	}

	@Disabled("The test needs to be run manually because it takes a minute to run.")
	@Test
	void manualTrafficRecordingStartAndAutomaticStopWhenTimedOut() throws IOException {
		final ServerTask<TrafficRecordingSettings, FileForFetch> recordingTask = this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaInternalSessionContract internalSession = (EvitaInternalSessionContract) session;
				// start recording
				return internalSession.startRecording(
					100, true, Duration.of(1, ChronoUnit.MINUTES), null, 32_000L
				);
			}
		);

		final long start = System.currentTimeMillis();
		do {
			System.out.print(".");
			generateSomeTraffic();
		} while (System.currentTimeMillis() - start < 120_000L);

		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaInternalSessionContract internalSession = (EvitaInternalSessionContract) session;
				// stop recording
				System.out.println("\nStopping recording...");
				return internalSession.stopRecording(recordingTask.getStatus().taskId()).result();
			}
		);

		final FileForFetch fileForFetch = recordingTask.getFutureResult().join();
		assertNotNull(fileForFetch);

		System.out.println("Export file size: " + fileForFetch.totalSizeInBytes());
		System.out.println("Export file contains these files: ");

		// list files in the ZIP archive
		final String[] fileNames = listAndVerifyFilesInArchive(fileForFetch);
		for (String fileName : fileNames) {
			System.out.println(" - " + fileName);
		}
	}

	private void generateSomeTraffic() {
		// seed random so the produced traffic volume is deterministic and the chunk count assertion
		// is not dependent on the random PKs picked (which influences how many entities each query
		// matches and fetches into the recording stream)
		final Random random = new Random(SEED);
		for (int j = 0; j < 40; j++) {
			this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// do some queries
					for (int i = 0; i < 10; i++) {
						final int[] pks = IntStream.generate(() -> random.nextInt(INITIAL_COUNT_OF_PRODUCTS))
							.limit(10)
							.toArray();
						session.query(
							query(
								collection(Entities.PRODUCT),
								filterBy(entityPrimaryKeyInSet(pks)),
								require(entityFetchAll())
							),
							SealedEntity.class
						);
					}
				}
			);
		}
	}

	@Nonnull
	private String[] listAndVerifyFilesInArchive(@Nonnull FileForFetch fileForFetch) throws IOException {
		System.out.println("Verifying the content of the ZIP archive: " + fileForFetch);

		final byte[] buffer = new byte[4_096];
		final List<String> filesInZip = new ArrayList<>(16);
		try (
			final InputStream inputStream = this.evita.management().fetchFile(fileForFetch.fileId());
			final ZipInputStream zipInputStream = new ZipInputStream(inputStream)
		) {
			ZipEntry nextEntry;
			while ((nextEntry = zipInputStream.getNextEntry()) != null) {
				filesInZip.add(nextEntry.getName());
				if (nextEntry.getName().endsWith(".bin")) {
					// extract the entry
					final Path tempFile = Files.createTempFile("evitaTrafficRecordingTest", nextEntry.getName());
					try (final OutputStream outputStream = new BufferedOutputStream(
						Files.newOutputStream(tempFile, StandardOpenOption.TRUNCATE_EXISTING), 4_096)) {
						IOUtils.copy(zipInputStream, outputStream, buffer);
					}
					// verify the entry
					try (
						final AbstractRandomAccessInputStream tempInputStream = new RandomAccessFileInputStream(
							new RandomAccessFile(tempFile.toFile(), "r"));
						final InputStreamTrafficRecordReader reader = new InputStreamTrafficRecordReader(
							tempInputStream)
					) {
						try (
							final Stream<TrafficRecording> recordings = reader.getRecordings(
								TrafficRecordingCaptureRequest.builder()
									.build()
							)
						) {
							final long count = recordings.count();
							assertTrue(count > 0, "The file " + nextEntry.getName() + " contains no records.");
							System.out.println(" - " + nextEntry.getName() + " contains " + count + " records.");
						}
					}
				}
				zipInputStream.closeEntry();
			}
		}
		return filesInZip.toArray(String[]::new);
	}

	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.server(
				ServerOptions.builder()
					.trafficRecording(
						TrafficRecordingOptions.builder()
							.enabled(false)
							.build()
					)
					.build()
			)
			.build();
	}

	/**
	 * Tests for the on-demand export of the disk ring buffer, driven by an *ambient* (always-on)
	 * traffic recorder - i.e. {@link TrafficRecordingOptions#enabled()} is true from catalog-alive time,
	 * as opposed to the manual {@code startRecording}/{@code stopRecording} task tested above. Each test
	 * builds its own {@link Evita} instance because the buffer/flush settings under test (a long flush
	 * interval vs. a tiny disk buffer with immediate flush) are mutually exclusive.
	 */
	@Nested
	@DisplayName("On-demand export of the disk ring buffer (ambient recording)")
	class ExportTrafficRecordingTest {

		@Test
		@DisplayName("Should export recently closed sessions without any explicit drain call, matching the exported zip content 1:1")
		void shouldExportRecentlyClosedSessionsWithoutExplicitDrain() throws IOException {
			final TestPaths exportPaths = createTestPaths("EvitaOnDemandTrafficRecordingExportR1Test");
			// the background flush must NOT have a chance to run - the default (long) interval
			// guarantees the only path from "session closed" to "on disk" is the export's own
			// pre-export drain hook (`drainFinalizedSessionsToDisk`)
			try (
				Evita exportEvita = createAmbientEvita(
					exportPaths,
					TrafficRecordingOptions.builder()
						.enabled(true)
						// the background flush must NOT have a chance to run - the default (long) interval
						// guarantees the only path from "session closed" to "on disk" is the export's own
						// pre-export drain hook (`drainFinalizedSessionsToDisk`)
						.trafficFlushIntervalInMilliseconds(TrafficRecordingOptions.DEFAULT_TRAFFIC_FLUSH_INTERVAL)
						.build()
				)
			) {
				new TestDataProvider().generateEntities(exportEvita, 5);
				goLiveCatalog(exportEvita);
				generateAmbientTraffic(exportEvita, 5, 5);

				final FileForFetch fileForFetch = exportEvita.updateCatalog(
					TEST_CATALOG,
					session -> {
						return ((EvitaInternalSessionContract) session).exportTrafficRecording(16_000L);
					}
				).getFutureResult().join();

				assertNotNull(fileForFetch);
				final ExportedZipSummary summary = readExportedZip(exportEvita, fileForFetch);
				assertTrue(
					summary.metadataExportedSessions() > 0,
					"Expected recently closed sessions to be exported without any explicit drain, metadata reported: " + summary
				);
				assertTrue(
					summary.totalRecordCount() > 0,
					"Expected at least one exported traffic record, metadata reported: " + summary
				);
				assertEquals(
					summary.metadataExportedSessions(), summary.distinctSessionCount(),
					"Every exported session must appear exactly once in the zip content, metadata reported: " + summary
				);
				assertEquals(
					summary.metadataSnapshotSessions(),
					summary.metadataExportedSessions() + summary.metadataSkippedSessions(),
					"exported + skipped must equal the frozen snapshot size, metadata reported: " + summary
				);
			} finally {
				cleanupTestPaths(exportPaths);
			}
		}

		@Test
		@Timeout(30)
		@DisplayName("Should tolerate continuous rotation of the disk ring buffer racing a concurrent export, with no writer stall or corruption")
		void shouldTolerateContinuousRotationDuringConcurrentExport() throws Exception {
			final TestPaths exportPaths = createTestPaths("EvitaOnDemandTrafficRecordingExportR2Test");
			// tiny disk buffer + immediate flush forces rotation within a few dozen sessions
			// while the writer thread keeps recording concurrently with the export
			try (
				Evita exportEvita = createAmbientEvita(
					exportPaths,
					TrafficRecordingOptions.builder()
						.enabled(true)
						// tiny disk buffer + immediate flush forces rotation within a few dozen sessions
						// while the writer thread keeps recording concurrently with the export
						.trafficDiskBufferSizeInBytes(131_072L)
						.trafficFlushIntervalInMilliseconds(0L)
						.exportFileChunkSizeInBytes(16_000L)
						.build()
				)
			) {
				new TestDataProvider().generateEntities(exportEvita, 5);
				goLiveCatalog(exportEvita);

				final AtomicBoolean keepWriting = new AtomicBoolean(true);
				final AtomicReference<Throwable> writerFailure = new AtomicReference<>();
				final Thread writerThread = new Thread(
					() -> {
						try {
							while (keepWriting.get()) {
								generateAmbientTraffic(exportEvita, 1, 5);
							}
						} catch (Throwable ex) {
							writerFailure.set(ex);
						}
					},
					"traffic-writer-vs-export"
				);
				writerThread.start();

				final FileForFetch fileForFetch;
				try {
					// let a handful of rotations happen before racing the export
					Thread.sleep(200L);
					fileForFetch = exportEvita.updateCatalog(
						TEST_CATALOG,
						session -> {
							return ((EvitaInternalSessionContract) session).exportTrafficRecording(16_000L);
						}
					).getFutureResult().join();
				} finally {
					keepWriting.set(false);
					writerThread.join(10_000L);
				}

				assertNull(
					writerFailure.get(),
					"Writer thread must never fail/throw during a concurrent export: " + writerFailure.get()
				);
				assertNotNull(fileForFetch);

				final ExportedZipSummary summary = readExportedZip(exportEvita, fileForFetch);
				assertEquals(
					summary.metadataSnapshotSessions(),
					summary.metadataExportedSessions() + summary.metadataSkippedSessions(),
					"exported + skipped must equal the frozen snapshot size even under continuous rotation, metadata reported: " + summary
				);
				assertEquals(
					summary.metadataExportedSessions(), summary.distinctSessionCount(),
					"Every exported session must round-trip fully through InputStreamTrafficRecordReader with no truncated tail record, metadata reported: " + summary
				);
			} finally {
				cleanupTestPaths(exportPaths);
			}
		}

		@Nonnull
		private Evita createAmbientEvita(
			@Nonnull TestPaths exportPaths, @Nonnull TrafficRecordingOptions trafficRecordingOptions) {
			final Evita ambientEvita = new Evita(
				newTestEvitaConfigurationBuilder(exportPaths)
					.server(
						ServerOptions.builder()
							.trafficRecording(trafficRecordingOptions)
							.build()
					)
					.build()
			);
			ambientEvita.defineCatalog(TEST_CATALOG);
			return ambientEvita;
		}

		/**
		 * Transitions the catalog from "warming up" to "alive" - the ambient traffic recorder is only
		 * initialized as a real (non {@code NoOp}) recorder once the catalog reaches this state, and
		 * only "alive" catalogs allow the concurrent read sessions the writer/export race needs.
		 */
		private static void goLiveCatalog(@Nonnull Evita ambientEvita) {
			ambientEvita.updateCatalog(
				TEST_CATALOG,
				EvitaSessionContract::goLiveAndClose
			);
		}

		/**
		 * Closes {@code sessionCount} sessions, each running a single trivial entity-fetch query, so that
		 * every {@code queryCatalog} call produces exactly one recorded traffic-recording session.
		 */
		private static void generateAmbientTraffic(@Nonnull Evita ambientEvita, int sessionCount, int pkBound) {
			final Random random = new Random(SEED);
			for (int i = 0; i < sessionCount; i++) {
				ambientEvita.queryCatalog(
					TEST_CATALOG,
					session -> {
						session.query(
							query(
								collection(Entities.PRODUCT),
								filterBy(entityPrimaryKeyInSet(random.nextInt(pkBound) + 1)),
								require(entityFetchAll())
							),
							SealedEntity.class
						);
					}
				);
			}
		}

		/**
		 * Extracts every {@code .bin} entry (round-tripped through {@link InputStreamTrafficRecordReader})
		 * and parses the {@code metadata.txt} counters out of an exported zip archive.
		 */
		@Nonnull
		private ExportedZipSummary readExportedZip(
			@Nonnull Evita ambientEvita, @Nonnull FileForFetch fileForFetch) throws IOException {
			final byte[] buffer = new byte[4_096];
			int totalRecordCount = 0;
			final Set<UUID> sessionIds = new HashSet<>();
			int metadataExported = -1;
			int metadataSkipped = -1;
			int metadataSnapshot = -1;
			try (
				final InputStream inputStream = ambientEvita.management().fetchFile(fileForFetch.fileId());
				final ZipInputStream zipInputStream = new ZipInputStream(inputStream)
			) {
				ZipEntry nextEntry;
				while ((nextEntry = zipInputStream.getNextEntry()) != null) {
					if (nextEntry.getName().endsWith(".bin")) {
						final Path tempFile = Files.createTempFile(
							"evitaTrafficRecordingExportTest", nextEntry.getName());
						try {
							try (final OutputStream outputStream = new BufferedOutputStream(
								Files.newOutputStream(tempFile, StandardOpenOption.TRUNCATE_EXISTING), 4_096)) {
								IOUtils.copy(zipInputStream, outputStream, buffer);
							}
							try (
								final AbstractRandomAccessInputStream tempInputStream = new RandomAccessFileInputStream(
									new RandomAccessFile(tempFile.toFile(), "r"));
								final InputStreamTrafficRecordReader reader = new InputStreamTrafficRecordReader(
									tempInputStream)
							) {
								try (
									final Stream<TrafficRecording> recordings = reader.getRecordings(
										TrafficRecordingCaptureRequest.builder().build()
									)
								) {
									final List<TrafficRecording> recordingList = recordings.toList();
									totalRecordCount += recordingList.size();
									recordingList.forEach(recording -> sessionIds.add(recording.sessionId()));
								}
							}
						} finally {
							Files.deleteIfExists(tempFile);
						}
					} else if (nextEntry.getName().equals("metadata.txt")) {
						final ByteArrayOutputStream metadataBytes = new ByteArrayOutputStream();
						IOUtils.copy(zipInputStream, metadataBytes, buffer);
						final String metadataText = metadataBytes.toString(StandardCharsets.UTF_8);
						metadataExported = parseIntAfter(metadataText, "exported (\\d+) sessions");
						metadataSkipped = parseIntAfter(metadataText, "skipped (\\d+) sessions");
						metadataSnapshot = parseIntAfter(metadataText, "snapshot contained (\\d+) sessions");
					}
					zipInputStream.closeEntry();
				}
			}
			return new ExportedZipSummary(
				totalRecordCount, sessionIds.size(), metadataExported, metadataSkipped, metadataSnapshot);
		}

		private static int parseIntAfter(@Nonnull String text, @Nonnull String regex) {
			final Matcher matcher = Pattern.compile(regex).matcher(text);
			assertTrue(matcher.find(), "Pattern `" + regex + "` not found in metadata.txt content:\n" + text);
			return Integer.parseInt(matcher.group(1));
		}

		/**
		 * Aggregated view of one exported zip archive - the raw record/session counts observed by
		 * actually parsing the {@code .bin} entries, alongside the counters {@code metadata.txt} reports.
		 */
		private record ExportedZipSummary(
			int totalRecordCount,
			int distinctSessionCount,
			int metadataExportedSessions,
			int metadataSkippedSessions,
			int metadataSnapshotSessions
		) {
		}

	}

}
