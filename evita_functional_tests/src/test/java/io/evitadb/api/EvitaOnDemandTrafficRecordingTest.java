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
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TrafficRecordingOptions;
import io.evitadb.api.file.FileForFetch;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecording;
import io.evitadb.api.requestResponse.trafficRecording.TrafficRecordingCaptureRequest;
import io.evitadb.api.task.ServerTask;
import io.evitadb.core.Evita;
import io.evitadb.core.EvitaInternalSessionContract;
import io.evitadb.core.traffic.TrafficRecordingSettings;
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
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.BiFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
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
public class EvitaOnDemandTrafficRecordingTest implements EvitaTestSupport {
	public static final String ATTRIBUTE_CODE = "code";
	public static final String DIRECTORY_EVITA_TRAFFIC_RECORDING_TEST = "evitaTrafficRecordingTest";
	public static final String DIRECTORY_EVITA_TRAFFIC_RECORDING_TEST_EXPORT = "evitaTrafficRecordingTestExport";
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
	protected void createEntity(@Nonnull EvitaSessionContract session, @Nonnull Map<Serializable, Integer> generatedEntities, @Nonnull EntityBuilder it) {
		final EntityReferenceContract insertedEntity = session.upsertEntity(it);
		generatedEntities.compute(
			insertedEntity.getType(),
			(serializable, existing) -> ofNullable(existing).orElse(0) + 1
		);
	}

	@BeforeEach
	void setUp() throws IOException {
		cleanTestSubDirectory(DIRECTORY_EVITA_TRAFFIC_RECORDING_TEST);
		cleanTestSubDirectory(DIRECTORY_EVITA_TRAFFIC_RECORDING_TEST_EXPORT);
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
					.withAttribute(ATTRIBUTE_CODE, String.class, thatIs -> thatIs.uniqueGlobally())
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
	void tearDown() throws IOException {
		this.evita.close();
		cleanTestSubDirectory(DIRECTORY_EVITA_TRAFFIC_RECORDING_TEST);
		cleanTestSubDirectory(DIRECTORY_EVITA_TRAFFIC_RECORDING_TEST_EXPORT);
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
		assertEquals(4, Arrays.stream(fileNames).filter(name -> name.startsWith("traffic_recording_")).count());
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
		final Random random = new Random();
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
					try (final OutputStream outputStream = new BufferedOutputStream(Files.newOutputStream(tempFile, StandardOpenOption.TRUNCATE_EXISTING), 4_096)) {
						IOUtils.copy(zipInputStream, outputStream, buffer);
					}
					// verify the entry
					try (
						final AbstractRandomAccessInputStream tempInputStream = new RandomAccessFileInputStream(new RandomAccessFile(tempFile.toFile(), "r"));
						final InputStreamTrafficRecordReader reader = new InputStreamTrafficRecordReader(tempInputStream)
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
		return EvitaConfiguration.builder()
			.server(
				ServerOptions.builder()
					.trafficRecording(
						TrafficRecordingOptions.builder()
							.enabled(false)
							.build()
					)
					.build()
			)
			.storage(
				StorageOptions.builder()
					.storageDirectory(getTestDirectory().resolve(DIRECTORY_EVITA_TRAFFIC_RECORDING_TEST))
					.exportDirectory(getTestDirectory().resolve(DIRECTORY_EVITA_TRAFFIC_RECORDING_TEST_EXPORT))
					.build()
			)
			.build();
	}

}
