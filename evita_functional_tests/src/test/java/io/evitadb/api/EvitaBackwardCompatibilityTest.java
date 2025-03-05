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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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

import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAll;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
		mainDirectory = Path.of(System.getProperty("java.io.tmpdir")).resolve(DIRECTORY);
		if (!mainDirectory.toFile().exists()) {
			mainDirectory.toFile().mkdirs();
		}
	}

	@DisplayName("Verify backward binary data compatibility to 2024.5")
	@Tag(LONG_RUNNING_TEST)
	@Test
	void verifyBackwardCompatibilityTo_2024_5() throws IOException {
		/* TODO JNO - implementovat automatický upgrade datového souboru */
		final Path directory_2024_5 = mainDirectory.resolve("2024.5");
		if (!directory_2024_5.toFile().exists()) {
			log.info("Downloading and unzipping evita-demo-dataset_2024.5.zip");

			directory_2024_5.toFile().mkdirs();
			// first download the file from https://evitadb.io/test/evita-demo-dataset_2024.5.zip to tmp folder and unzip it
			try (final InputStream is = new URL("https://evitadb.io/download/test/evita-demo-dataset_2024.5.zip").openStream()) {
				Files.copy(is, directory_2024_5.resolve("evita-demo-dataset_2024.5.zip"), StandardCopyOption.REPLACE_EXISTING);
			}
			// unzip the file
			try (
				final InputStream is = Files.newInputStream(directory_2024_5.resolve("evita-demo-dataset_2024.5.zip"));
				final ZipInputStream zis = new ZipInputStream(is)
			) {
				ZipEntry zipEntry = zis.getNextEntry();
				while (zipEntry != null) {
					Path newPath = directory_2024_5.resolve(zipEntry.getName());
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

		log.info("Starting Evita with backward compatibility to 2024.5");
		final Evita evita = new Evita(
			EvitaConfiguration.builder()
				.server(
					ServerOptions.builder()
						.closeSessionsAfterSecondsOfInactivity(-1)
						.build()
				)
				.storage(
					StorageOptions.builder()
						.storageDirectory(directory_2024_5)
						.build()
				)
				.build()
		);

		final SystemStatus status = evita.management().getSystemStatus();
		assertEquals(0, status.catalogsCorrupted());
		assertEquals(1, status.catalogsOk());

		// check the catalog has its own id
		final UUID catalogId = evita.queryCatalog(
			"evita",
			session -> {
				for (String entityType : session.getAllEntityTypes()) {
					log.info("Entity type: {}", entityType);
					if (session.getEntityCollectionSize(entityType)  > 0) {
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
