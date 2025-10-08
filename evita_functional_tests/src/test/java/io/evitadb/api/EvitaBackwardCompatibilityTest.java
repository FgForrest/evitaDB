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

package io.evitadb.api;

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.system.SystemStatus;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.evitadb.api.configuration.StorageOptions.DEFAULT_OUTPUT_BUFFER_SIZE;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAll;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This test verifies that data stored in older versions of evitaDB are still readable by the current version.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
@Slf4j
public class EvitaBackwardCompatibilityTest implements EvitaTestSupport {
	private static final String DIRECTORY = "evita-backward-compatibility";
	private Path mainDirectory;

	@BeforeEach
	void setUp() throws MalformedURLException {
		this.mainDirectory = Path.of(System.getProperty("java.io.tmpdir")).resolve(DIRECTORY);
		if (!this.mainDirectory.toFile().exists()) {
			this.mainDirectory.toFile().mkdirs();
		}
	}

	@Tag(LONG_RUNNING_TEST)
	@ParameterizedTest
	@ValueSource(
		strings = {"2024.5", "2025.1", "2025.3", "2025.6"}
	)
	void verifyBackwardCompatibilityTo(String version) throws IOException {
		final Path targetDirectory = this.mainDirectory.resolve(version);
		if (!targetDirectory.toFile().exists()) {
			final String fileName = "evita-demo-dataset_" + version + ".zip";
			log.info("Downloading and unzipping " + fileName);

			targetDirectory.toFile().mkdirs();
			// first download the file from https://evitadb.io/test/evita-demo-dataset_202X.Y.zip to tmp folder and unzip it
			final Path targetZipFile = targetDirectory.resolve(fileName);
			try (final InputStream is = new URL("https://evitadb.io/download/test/" + fileName).openStream()) {
				Files.copy(is, targetZipFile, StandardCopyOption.REPLACE_EXISTING);
			}

			assertTrue(targetZipFile.toFile().exists(), "File " + fileName + " does not exist!");
			assertTrue(targetZipFile.toFile().length() > 0, "File " + fileName + " is empty!");

			// unzip the file
			try (
				final InputStream is = Files.newInputStream(targetZipFile);
				final ZipInputStream zis = new ZipInputStream(is)
			) {
				ZipEntry zipEntry = zis.getNextEntry();
				while (zipEntry != null) {
					Path newPath = targetDirectory.resolve(zipEntry.getName());
					if (zipEntry.isDirectory()) {
						Files.createDirectories(newPath);
					} else {
						// Make sure parent directory exist
						Files.createDirectories(newPath.getParent());
						// Write file content
						Files.copy(zis, newPath, StandardCopyOption.REPLACE_EXISTING);
					}
					zipEntry = zis.getNextEntry();
				}
			}
		}

		log.info("Starting Evita with backward compatibility to " + version);
		try (
			final Evita evita = new Evita(
				EvitaConfiguration.builder()
					.server(
						ServerOptions.builder()
							.closeSessionsAfterSecondsOfInactivity(-1)
							.build()
					)
					.storage(
						StorageOptions.builder()
							.storageDirectory(targetDirectory)
							.outputBufferSize(DEFAULT_OUTPUT_BUFFER_SIZE * 2)
							.build()
					)
					.build()
			)
		) {

			final SystemStatus status = evita.management().getSystemStatus();
			assertEquals(0, status.catalogsCorrupted());
			assertEquals(1, status.catalogsActive());

			// check the catalog has its own id
			final UUID catalogId = evita.queryCatalog(
				"evita",
				session -> {
					for (String entityType : session.getAllEntityTypes()) {
						log.info("Entity type: {}", entityType);
						if (session.getEntityCollectionSize(entityType) > 0) {
							final List<SealedEntity> sealedEntities = session.queryListOfSealedEntities(
								Query.query(
									collection(entityType),
									require(
										page(1, 20),
										entityFetchAll()
									)
								)
							);
							for (SealedEntity sealedEntity : sealedEntities) {
								assertNotNull(sealedEntity);
							}
						}
					}

					return session.getCatalogId();
				}
			);
			assertNotNull(catalogId);
		}
	}

}
