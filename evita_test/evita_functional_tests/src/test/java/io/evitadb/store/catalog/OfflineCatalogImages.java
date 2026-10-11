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

package io.evitadb.store.catalog;

import io.evitadb.api.CatalogState;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.core.catalog.UnusableCatalog;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.export.file.ExportFileService;
import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
import io.evitadb.spi.store.catalog.persistence.PersistenceService;
import io.evitadb.spi.store.engine.CatalogFolderOperations;
import io.evitadb.spi.store.engine.model.CatalogFolderId;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.offsetIndex.io.BootstrapWriteOnlyFileHandle;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.store.shared.model.FileLocation;
import io.evitadb.test.EvitaTestSupport;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static io.evitadb.store.catalog.DefaultCatalogPersistenceService.serializeBootstrapRecord;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opens, stamps and inspects a catalog on disk with the engine stopped - the means by which the storage protocol tests
 * reproduce, without a binary fixture, the state an older release or a development build left on disk: the catalog is
 * written by the engine, and its catalog header and bootstrap record are then stamped with the storage protocol of the
 * state to reproduce, through the store's own types. Every write goes through the same persistence service and the
 * same bootstrap record format the engine uses.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class OfflineCatalogImages {

	private OfflineCatalogImages() {
	}

	/**
	 * Opens the catalog with the engine stopped, through the same persistence service the engine loads it with, runs
	 * `work` on it and closes it again, together with the scheduler and export service it needs. The open is the load
	 * path of the engine: a catalog of an older storage protocol is migrated by it, and one of a refused protocol is
	 * refused by it.
	 *
	 * @param configuration the configuration of the engine
	 * @param catalogName   the catalog
	 * @param work          the work to run
	 * @param <T>           the type of the result
	 * @return the result of the work
	 */
	public static <T> T withOfflineCatalog(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull String catalogName,
		@Nonnull Function<DefaultCatalogPersistenceService, T> work
	) {
		final Path storageDirectory = configuration.storage().storageDirectory();
		final CatalogFolderId folderId = new CatalogFolderId(
			catalogFolder(configuration, catalogName).getFileName().toString()
		);
		final Scheduler scheduler = new Scheduler(new ScheduledThreadPoolExecutor(1));
		try (
			ExportFileService exportService = new ExportFileService(configuration.export(), scheduler);
			DefaultCatalogPersistenceService service = new DefaultCatalogPersistenceService(
				new UnusableCatalog(
					catalogName, CatalogState.ALIVE, folderId, storageDirectory,
					CatalogFolderOperations.unsupported("The test opens the catalog only to read and stamp its parts."),
					(name, folder, root) -> new IllegalStateException("The test never queries the catalog.")
				),
				catalogName, folderId, configuration.storage(), configuration.transaction(), scheduler,
				exportService
			)
		) {
			return work.apply(service);
		} finally {
			scheduler.shutdownNow();
			try {
				assertTrue(scheduler.awaitTermination(30, TimeUnit.SECONDS), "the offline scheduler did not stop");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * Returns the folder holding the files of a catalog.
	 *
	 * @param configuration the configuration of the engine
	 * @param catalogName   the catalog
	 * @return the folder
	 */
	@Nonnull
	static Path catalogFolder(@Nonnull EvitaConfiguration configuration, @Nonnull String catalogName) {
		return EvitaTestSupport.catalogDirectory(configuration.storage().storageDirectory(), catalogName);
	}

	/**
	 * Returns every bootstrap record of a catalog, oldest first.
	 *
	 * @param configuration the configuration of the engine
	 * @param catalogName   the catalog
	 * @return the records
	 */
	@Nonnull
	static List<CatalogBootstrap> bootstrapRecords(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull String catalogName
	) {
		try (
			Stream<CatalogBootstrap> records = DefaultCatalogPersistenceService.getCatalogBootstrapRecordStream(
				catalogName, catalogFolder(configuration, catalogName), bootstrapStorageSettings(configuration)
			)
		) {
			return records.toList();
		}
	}

	/**
	 * Returns the last bootstrap record of a catalog - the state the catalog has published, which its next load
	 * starts from.
	 *
	 * @param configuration the configuration of the engine
	 * @param catalogName   the catalog
	 * @return the last record
	 */
	@Nonnull
	public static CatalogBootstrap lastBootstrap(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull String catalogName
	) {
		final List<CatalogBootstrap> records = bootstrapRecords(configuration, catalogName);
		assertTrue(!records.isEmpty(), "catalog `" + catalogName + "` holds no bootstrap record");
		return records.getLast();
	}

	/**
	 * Stamps the published state of a catalog with the given storage protocols: writes the catalog header of its last
	 * version once more under `headerProtocol`, flushes it, and appends a bootstrap record of `bootstrapProtocol`
	 * pointing at the flushed header - the same two writes a protocol migration publishes its result with. The data
	 * the header addresses are left as they are. The catalog must open without a migration, that is carry the current
	 * storage protocol, when it is stamped.
	 *
	 * @param configuration     the configuration of the engine
	 * @param catalogName       the catalog
	 * @param headerProtocol    the storage protocol of the catalog header
	 * @param bootstrapProtocol the storage protocol of the bootstrap record
	 * @return the bootstrap record now publishing the catalog
	 */
	@Nonnull
	public static CatalogBootstrap stamp(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull String catalogName,
		int headerProtocol,
		int bootstrapProtocol
	) {
		final int catalogFileIndex = lastBootstrap(configuration, catalogName).catalogFileIndex();
		final StampedHeader stamped = withOfflineCatalog(
			configuration, catalogName,
			service -> {
				final long catalogVersion = service.getLastCatalogVersion();
				final CatalogOffsetIndexStoragePartPersistenceService catalogParts =
					service.getStoragePartPersistenceService(catalogVersion);
				final CatalogHeader<LogFileRecordReference, CollectionFileReference> header =
					catalogParts.getCatalogHeader(catalogVersion);
				assertEquals(
					PersistenceService.STORAGE_PROTOCOL_VERSION, header.storageProtocolVersion(),
					"only a catalog of the current storage protocol opens without a migration and can be stamped"
				);
				catalogParts.writeCatalogHeader(
					headerProtocol, catalogVersion, service.getCatalogStoragePath(), header.walFileReference(),
					header.collectionFileIndex(), header.catalogId(), header.catalogName(), header.catalogState(),
					header.lastEntityCollectionPrimaryKey()
				);
				return new StampedHeader(catalogVersion, catalogParts.flush(catalogVersion).fileLocation());
			}
		);
		final CatalogBootstrap record = new CatalogBootstrap(
			bootstrapProtocol,
			stamped.catalogVersion(),
			catalogFileIndex,
			// the bootstrap file keeps milliseconds - a record read back must equal the one written
			OffsetDateTime.ofInstant(Instant.ofEpochMilli(System.currentTimeMillis()), ZoneId.systemDefault()),
			stamped.fileLocation()
		);
		appendBootstrapRecord(configuration, catalogName, record);
		assertEquals(
			record, lastBootstrap(configuration, catalogName),
			"the stamped record must be the last one, or the image is not the state it claims to be"
		);
		return record;
	}

	/**
	 * Runs the migration of the published state of a catalog of the current storage protocol with the engine stopped,
	 * handing its result to the given publication instead of publishing it - the migration the load path runs, minus
	 * the publication.
	 *
	 * @param configuration     the configuration of the engine
	 * @param catalogName       the catalog
	 * @param postUpgradeAction receives the catalog header the migration would publish
	 * @param published         receives the collection references the published catalog header holds
	 */
	static void migrateOffline(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull String catalogName,
		@Nonnull Consumer<CatalogHeader<LogFileRecordReference, CollectionFileReference>> postUpgradeAction,
		@Nonnull Map<String, CollectionFileReference> published
	) {
		withOfflineCatalog(
			configuration, catalogName,
			service -> {
				final long catalogVersion = service.getLastCatalogVersion();
				final CatalogOffsetIndexStoragePartPersistenceService catalogParts =
					service.getStoragePartPersistenceService(catalogVersion);
				final CatalogHeader<LogFileRecordReference, CollectionFileReference> header =
					catalogParts.getCatalogHeader(catalogVersion);
				published.putAll(header.collectionFileIndex());
				Migration_2026_3.upgradeFromStorageProtocolVersion_6_to_8(
					header, catalogParts, service::createEntityCollectionPersistenceService, postUpgradeAction
				);
				return Boolean.TRUE;
			}
		);
	}

	/**
	 * Appends a bootstrap record to the bootstrap file of a catalog through a handle of its own, as the engine appends
	 * one when it publishes.
	 *
	 * @param configuration the configuration of the engine
	 * @param catalogName   the catalog
	 * @param record        the record to append
	 */
	static void appendBootstrapRecord(
		@Nonnull EvitaConfiguration configuration,
		@Nonnull String catalogName,
		@Nonnull CatalogBootstrap record
	) {
		final StorageSettings settings = bootstrapStorageSettings(configuration);
		try (
			BootstrapWriteOnlyFileHandle handle = new BootstrapWriteOnlyFileHandle(
				bootstrapFile(catalogFolder(configuration, catalogName)),
				settings.outputBufferSize(),
				true,
				settings.lockTimeoutSeconds(),
				settings
			)
		) {
			handle.checkAndExecuteAndSync(
				"append the stamped bootstrap record",
				() -> {
				},
				output -> serializeBootstrapRecord(output, record).payload(),
				(output, written) -> written
			);
		}
	}

	/**
	 * Describes every file of a folder: its length and the digest of its content.
	 *
	 * @param folder the folder
	 * @return the files by name
	 */
	@Nonnull
	static SortedMap<String, StoredFile> storedFiles(@Nonnull Path folder) {
		final SortedMap<String, StoredFile> files = new TreeMap<>();
		try (Stream<Path> list = Files.list(folder)) {
			for (final Path file : list.filter(Files::isRegularFile).toList()) {
				files.put(file.getFileName().toString(), StoredFile.of(file));
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return files;
	}

	/**
	 * Asserts that the files of a catalog folder were written by appending only: every file is still there, no file was
	 * added - no new data file generation - and every file starts with exactly the bytes it held before.
	 *
	 * @param label              names the moment in failure messages
	 * @param before             the files before
	 * @param after              the files after
	 * @param bootstrapUnchanged TRUE when the bootstrap file must keep its bytes - nothing was published; FALSE when a
	 *                           publication happened, which may rewrite the bootstrap file as a whole (without time
	 *                           travel the store keeps only the newest record), so it is not compared
	 */
	static void assertOnlyAppended(
		@Nonnull String label,
		@Nonnull Map<String, StoredFile> before,
		@Nonnull Map<String, StoredFile> after,
		boolean bootstrapUnchanged
	) {
		assertEquals(before.keySet(), after.keySet(), label + ": no file may be added or removed");
		for (final Entry<String, StoredFile> file : before.entrySet()) {
			final StoredFile afterFile = after.get(file.getKey());
			final boolean bootstrapFile = file.getKey().endsWith(PersistenceService.BOOT_FILE_SUFFIX);
			if (bootstrapFile && !bootstrapUnchanged) {
				continue;
			}
			assertTrue(
				afterFile.size() >= file.getValue().size(),
				label + ": file `" + file.getKey() + "` must not have shrunk: " + file.getValue() + " -> " + afterFile
			);
			assertEquals(
				file.getValue().digest(), StoredFile.digestOf(afterFile.path(), file.getValue().size()),
				label + ": file `" + file.getKey() + "` must keep the bytes it held before"
			);
			if (bootstrapFile) {
				assertEquals(
					file.getValue(), afterFile,
					label + ": nothing may be published - the bootstrap file must not change"
				);
			}
		}
	}

	/**
	 * Returns the names of the files of the folder the process holds open, read from the file descriptors the platform
	 * lists for the process (`/proc/self/fd`), or `null` where it lists none.
	 *
	 * @param folder the folder
	 * @return the names of the files held open, or `null` when the platform does not tell
	 */
	@Nullable
	static Set<String> filesHeldOpenIn(@Nonnull Path folder) {
		final Path descriptors = Path.of("/proc/self/fd");
		if (!Files.isDirectory(descriptors)) {
			return null;
		}
		try (Stream<Path> list = Files.list(descriptors)) {
			final Path realFolder = folder.toRealPath();
			final Set<String> held = new TreeSet<>();
			for (final Path descriptor : list.toList()) {
				try {
					final Path target = Files.readSymbolicLink(descriptor);
					if (target.startsWith(realFolder)) {
						held.add(realFolder.relativize(target).toString());
					}
				} catch (IOException e) {
					// the descriptor was closed while the list was read - it is not held
				}
			}
			return held;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Returns the storage settings the engine reads and writes bootstrap files with.
	 *
	 * @param configuration the configuration of the engine
	 * @return the bootstrap storage settings
	 */
	@Nonnull
	private static StorageSettings bootstrapStorageSettings(@Nonnull EvitaConfiguration configuration) {
		return new StorageSettings(configuration.storage(), configuration.transaction()).modifyForBootstrapFile();
	}

	/**
	 * Returns the single bootstrap file of a catalog folder.
	 *
	 * @param catalogFolder the catalog folder
	 * @return the path of its bootstrap file
	 */
	@Nonnull
	private static Path bootstrapFile(@Nonnull Path catalogFolder) {
		try (Stream<Path> list = Files.list(catalogFolder)) {
			final List<Path> bootstrapFiles = list
				.filter(it -> it.getFileName().toString().endsWith(PersistenceService.BOOT_FILE_SUFFIX))
				.toList();
			assertEquals(1, bootstrapFiles.size(), "a catalog folder holds exactly one bootstrap file");
			return bootstrapFiles.getFirst();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * The catalog header a stamp wrote: the version it belongs to and where it was flushed.
	 *
	 * @param catalogVersion the catalog version
	 * @param fileLocation   the location of the flushed header
	 */
	private record StampedHeader(long catalogVersion, @Nonnull FileLocation fileLocation) {
	}

	/**
	 * A file of a catalog folder: its length and the digest of its content.
	 *
	 * @param path   the file
	 * @param size   its length
	 * @param digest the SHA-256 of its content
	 */
	record StoredFile(@Nonnull Path path, long size, @Nonnull String digest) {

		/**
		 * Describes a file.
		 *
		 * @param file the file
		 * @return the description
		 */
		@Nonnull
		static StoredFile of(@Nonnull Path file) {
			try {
				final long size = Files.size(file);
				return new StoredFile(file, size, digestOf(file, size));
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}

		/**
		 * Computes the SHA-256 of the first `length` bytes of the file.
		 *
		 * @param file   the file
		 * @param length how many bytes to digest
		 * @return the digest
		 */
		@Nonnull
		static String digestOf(@Nonnull Path file, long length) {
			try (InputStream input = Files.newInputStream(file)) {
				final MessageDigest digest = MessageDigest.getInstance("SHA-256");
				final byte[] buffer = new byte[8_192];
				long remaining = length;
				while (remaining > 0) {
					final int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
					if (read < 0) {
						break;
					}
					digest.update(buffer, 0, read);
					remaining -= read;
				}
				return HexFormat.of().formatHex(digest.digest());
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			} catch (NoSuchAlgorithmException e) {
				throw new IllegalStateException(e);
			}
		}

		/**
		 * Returns the description without the path, which differs between two copies of one file.
		 *
		 * @return the length and the digest
		 */
		@Nonnull
		String content() {
			return this.size + ":" + this.digest;
		}
	}

}
