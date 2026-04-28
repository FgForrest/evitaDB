/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.api.requestResponse.system.SystemStatus;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.FileUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
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
import static org.junit.jupiter.api.Assertions.fail;

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
		strings = {"2024.5", "2025.1", "2025.3", "2025.6", "2026.1"}
	)
	void verifyBackwardCompatibilityTo(String version) throws IOException {
		final Path targetDirectory = this.mainDirectory.resolve(version);
		targetDirectory.toFile().mkdirs();

		final String fileName = "evita-demo-dataset_" + version + ".zip";
		final Path targetZipFile = targetDirectory.resolve(fileName);

		// download the zip only if it doesn't exist or is empty (cache for repeated runs)
		if (!targetZipFile.toFile().exists() || targetZipFile.toFile().length() == 0) {
			log.info("Downloading " + fileName);
			try (final InputStream is = new URL("https://evitadb.io/download/test/" + fileName).openStream()) {
				Files.copy(is, targetZipFile, StandardCopyOption.REPLACE_EXISTING);
			}

			assertTrue(targetZipFile.toFile().exists(), "File " + fileName + " does not exist!");
			assertTrue(targetZipFile.toFile().length() > 0, "File " + fileName + " is empty!");
		} else {
			log.info("Using cached " + fileName);
		}

		// clean everything except the zip (evitaDB modifies the storage directory on startup)
		try (final Stream<Path> entries = Files.list(targetDirectory)) {
			entries
				.filter(entry -> !entry.equals(targetZipFile))
				.forEach(entry -> {
					if (entry.toFile().isDirectory()) {
						FileUtils.deleteDirectory(entry);
					} else {
						FileUtils.deleteFileIfExists(entry);
					}
				});
		}

		// unzip the file fresh
		log.info("Unzipping " + fileName);
		try (
			final InputStream is = Files.newInputStream(targetZipFile);
			final ZipInputStream zis = new ZipInputStream(is)
		) {
			ZipEntry zipEntry = zis.getNextEntry();
			while (zipEntry != null) {
				final Path newPath = targetDirectory.resolve(zipEntry.getName());
				if (zipEntry.isDirectory()) {
					Files.createDirectories(newPath);
				} else {
					// make sure parent directory exists
					Files.createDirectories(newPath.getParent());
					Files.copy(zis, newPath, StandardCopyOption.REPLACE_EXISTING);
				}
				zipEntry = zis.getNextEntry();
			}
		}

		log.info("Starting Evita with backward compatibility to " + version);
		final TestPaths testPaths = createTestPaths("EvitaBackwardCompatibilityTest");
		try (
			final Evita evita = new Evita(
				newTestEvitaConfigurationBuilder(testPaths)
					.server(
						ServerOptions.builder()
							.closeSessionsAfterSecondsOfInactivity(-1)
							.build()
					)
					.storage(
						StorageOptions.builder()
							.storageDirectory(targetDirectory)
							.workDirectory(testPaths.work())
							.outputBufferSize(DEFAULT_OUTPUT_BUFFER_SIZE << 1)
							.build()
					)
					.build()
			)
		) {

			// Old-format catalogs are upgraded asynchronously by the engine: the boot-time load throws
			// CatalogRequiresUpgradeException, the failure handler issues an UpgradeCatalogFormatMutation
			// through EngineTransactionManager, and on its completion the load is retried under
			// LoadMode.RETRY_AFTER_UPGRADE. The first-attempt future stays exceptionally completed —
			// waitUntilFullyInitialized() would propagate the upgrade-required exception — so we poll
			// for the upgraded catalog to settle into ALIVE state instead. System CDC is not a viable
			// signal here because the retry's `replaceCatalogReference` is a pure in-memory engine-state
			// update — no system CDC event marks the final BEING_ACTIVATED → ALIVE transition.
			waitForAtLeastOneAliveCatalog(evita, version);

			final SystemStatus status = evita.management().getSystemStatus();
			assertEquals(0, status.catalogsCorrupted());
			assertEquals(1, status.catalogsActive());
			final String catalogName = evita.getCatalogNames()
				.stream()
				.filter(it -> evita.getCatalogState(it).map(state -> state == CatalogState.ALIVE).orElse(false))
				.findFirst()
				.orElseThrow();

			// check the catalog has its own id
			final UUID catalogId = evita.queryCatalog(
				catalogName,
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

			// read entire WAL contents from oldest to newest record
			readWalContents(evita, catalogName);
		} finally {
			cleanupTestPaths(testPaths);
		}
	}

	/**
	 * Polls until at least one catalog reaches {@link CatalogState#ALIVE} or any catalog becomes
	 * {@link CatalogState#CORRUPTED}. Required because the boot-time auto-upgrade flow runs
	 * asynchronously after the engine constructor returns: the first-attempt load future for an
	 * old-format catalog completes exceptionally with `CatalogRequiresUpgradeException`, the
	 * engine schedules an `UpgradeCatalogFormatMutation` and a retry on the service executor,
	 * and only after that retry installs a real `Catalog` does the catalog state move to ALIVE.
	 *
	 * Polling (rather than a system-CDC subscription) is required because the retry's
	 * `replaceCatalogReference` is a pure in-memory engine-state update — no system CDC event
	 * marks the final BEING_ACTIVATED → ALIVE transition, so a CDC subscriber would block on
	 * the upgrade-mutation event and never wake up for the install that follows.
	 *
	 * @param evita   the engine instance whose catalog states are polled
	 * @param version dataset version label, used in failure messages
	 */
	private static void waitForAtLeastOneAliveCatalog(@Nonnull Evita evita, @Nonnull String version) {
		final long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
		while (true) {
			if (evita.management().getSystemStatus().catalogsCorrupted() > 0) {
				fail("Catalog became CORRUPTED during boot-time auto-upgrade for dataset " + version);
			}
			final boolean anyAlive = evita.getCatalogNames()
				.stream()
				.anyMatch(it -> evita.getCatalogState(it)
					.map(state -> state == CatalogState.ALIVE)
					.orElse(false));
			if (anyAlive) {
				return;
			}
			if (System.nanoTime() > deadline) {
				fail("No catalog reached ALIVE state within 10 minutes for dataset " + version);
			}
			try {
				Thread.sleep(500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(
					"Interrupted while waiting for catalog to come alive", e
				);
			}
		}
	}

	/**
	 * Reads the WAL (Write-Ahead Log) contents of the specified catalog and validates
	 * the consistency between the last transaction version in the WAL and the current catalog version.
	 *
	 * @param evita The instance of {@link Evita} used to access the catalog and its mutation logs.
	 * @param catalogName The name of the catalog whose WAL contents are to be read and validated.
	 */
	private static void readWalContents(@Nonnull Evita evita, @Nonnull String catalogName) {
		final CatalogContract catalog = evita.getCatalogInstance(catalogName)
			.orElseThrow(() -> new IllegalStateException("Catalog '" + catalogName + "' not found!"));
		final long currentCatalogVersion = catalog.getVersion();
		log.info("Current catalog version: {}", currentCatalogVersion);

		long walRecordCount = 0;
		long lastTransactionVersion = -1;
		try (final Stream<CatalogBoundMutation> mutationStream = catalog.getCommittedMutationStream(1)) {
			final Iterator<CatalogBoundMutation> iterator = mutationStream.iterator();
			while (iterator.hasNext()) {
				final CatalogBoundMutation mutation = iterator.next();
				walRecordCount++;
				if (mutation instanceof TransactionMutation transactionMutation) {
					lastTransactionVersion = transactionMutation.getVersion();
					// skip over the individual mutations within this transaction
					for (int i = 0; i < transactionMutation.getMutationCount(); i++) {
						iterator.next();
						walRecordCount++;
					}
				}
			}
		}

		log.info(
			"WAL read complete: {} total records, last transaction version: {}",
			walRecordCount, lastTransactionVersion
		);
		if (walRecordCount > 0) {
			assertEquals(
				currentCatalogVersion, lastTransactionVersion,
				"Last WAL transaction version must equal current catalog version!"
			);
		} else {
			log.info("No WAL records found (dataset predates WAL storage) — skipping version assertion");
		}
	}

}
