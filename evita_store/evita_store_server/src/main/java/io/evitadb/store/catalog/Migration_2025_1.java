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

package io.evitadb.store.catalog;


import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.core.file.ExportFileService;
import io.evitadb.core.file.ExportFileService.ExportFileHandle;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.function.Functions;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.store.kryo.ObservableInput;
import io.evitadb.store.kryo.ObservableOutput;
import io.evitadb.store.model.FileLocation;
import io.evitadb.store.offsetIndex.exception.PrematureEndOfFileException;
import io.evitadb.store.offsetIndex.io.BootstrapWriteOnlyFileHandle;
import io.evitadb.store.offsetIndex.io.ReadOnlyFileHandle;
import io.evitadb.store.offsetIndex.model.StorageRecord;
import io.evitadb.store.offsetIndex.model.StorageRecord.RawRecord;
import io.evitadb.store.spi.CatalogPersistenceService;
import io.evitadb.store.spi.PersistenceService;
import io.evitadb.stream.RandomAccessFileInputStream;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ConsoleWriter;
import io.evitadb.utils.ConsoleWriter.ConsoleColor;
import io.evitadb.utils.ConsoleWriter.ConsoleDecoration;
import io.evitadb.utils.FileUtils;

import javax.annotation.Nonnull;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;

import static io.evitadb.store.wal.AbstractMutationLog.WAL_TAIL_LENGTH;

/**
 * Migration utility class that handles upgrading catalog data from storage protocol version 2 to version 3.
 * This class provides functionality to:
 *
 * - Migrate catalog bootstrap files to new format
 * - Upgrade catalog data files and WAL files to use new record format
 * - Create backups of original files before migration
 * - Handle validation and error cases during migration
 *
 * The migration process involves:
 * 1. Creating a backup of all catalog files
 * 2. Migrating the catalog bootstrap file to new format
 * 3. Migrating all data files (catalogs, entity collections, WAL) to new format
 * 4. Replacing old files with upgraded versions
 *
 * This migration is required when upgrading from storage protocol version 2 to version 3.
 *
 * @deprecated introduced with #650 and could be removed later when no version prior to 2025.2 is used
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Deprecated(since = "2025.1", forRemoval = true)
public interface Migration_2025_1 {

	/**
	 * Internal method for reading the catalog bootstrap record from the file handle.
	 *
	 * @param fromPosition from which position to read the record
	 * @param readHandle   the file handle to read the record from
	 * @return the catalog bootstrap record
	 * @deprecated introduced with #650 and could be removed later when no version prior to 2025.2 is used
	 */
	@Deprecated(since = "2025.1", forRemoval = true)
	@Nonnull
	static CatalogBootstrap deserializeOldCatalogBootstrapRecord(
		long fromPosition, @Nonnull ReadOnlyFileHandle readHandle) {
		final StorageRecord<CatalogBootstrap> storageRecord = readHandle.execute(
			input -> {
				Assert.isPremiseValid(
					!input.isCompressionEnabled(),
					"Bootstrap record must not be compressed!"
				);
				return StorageRecord.readOldFormat(
					input,
					new FileLocation(fromPosition, CatalogBootstrap.BOOTSTRAP_RECORD_SIZE),
					(theInput, recordLength, control) -> new CatalogBootstrap(
						theInput.readLong(),
						theInput.readInt(),
						Instant.ofEpochMilli(theInput.readLong()).atZone(ZoneId.systemDefault()).toOffsetDateTime(),
						new FileLocation(
							theInput.readLong(),
							theInput.readInt()
						)
					)
				);
			}
		);
		Assert.isPremiseValid(
			storageRecord != null && storageRecord.payload() != null,
			"Bootstrap record is not expected to be NULL!"
		);
		return storageRecord.payload();
	}

	/**
	 * Performs automatic upgrade of the bootstrap file and all catalog files.
	 *
	 * @param catalogName             name of the catalog
	 * @param bootstrapStorageOptions bootstrap storage options
	 * @param catalogStoragePath      path to the catalog storage
	 * @param bootstrapFilePath       path to the bootstrap file
	 * @deprecated introduced with #650 and could be removed later when no version prior to 2025.2 is used
	 */
	@Deprecated(since = "2025.1", forRemoval = true)
	static void upgradeCatalogFiles(
		@Nonnull String catalogName,
		@Nonnull StorageOptions bootstrapStorageOptions,
		@Nonnull StorageOptions storageOptions,
		@Nonnull Path catalogStoragePath,
		@Nonnull Path bootstrapFilePath,
		@Nonnull ExportFileService exportFileService
	) {
		ConsoleWriter.writeLine(
			"Catalog `" + catalogName + "` uses deprecated storage record format of storage protocol version 2.",
			ConsoleColor.BRIGHT_BLUE, ConsoleDecoration.BOLD
		);
		ConsoleWriter.writeLine(
			"Upgrading `" + catalogName + "` to storage record format of storage protocol version 3.",
			ConsoleColor.BRIGHT_BLUE, ConsoleDecoration.BOLD
		);
		// first create backup of all catalog files
		try (
			final ExportFileHandle exportFileHandle = exportFileService.storeFile(
				catalogStoragePath.toFile().getName() + "_" + OffsetDateTime.now()
				                                                            .format(
					                                                            DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "_upgrade.zip",
				"Catalog `" + catalogName + "` backup before storage protocol upgrade.",
				"application/zip",
				null
			)
		) {
			ConsoleWriter.writeLine(
				"Backing up catalog `" + catalogName + "` to `" + exportFileHandle.filePath() + "` before upgrade...",
				ConsoleColor.DARK_BLUE
			);
			FileUtils.compressDirectory(catalogStoragePath, exportFileHandle.outputStream());
		} catch (IOException e) {
			// just log the error and continue
			ConsoleWriter.writeLine(
				"Failed to backup catalog `" + catalogName + "` before upgrade! Error: " + e.getMessage(),
				ConsoleColor.BRIGHT_RED, ConsoleDecoration.BOLD
			);
		}
		// next migrate catalog bootstrap file
		final int recordCount = getOldRecordCount(
			bootstrapFilePath.toFile().length()
		);
		final Path targetPath = catalogStoragePath.resolve("../" + catalogStoragePath.toFile().getName() + "__upgrade")
		                                          .normalize();
		Assert.isPremiseValid(targetPath.toFile().mkdirs(), "Failed to create target directory for upgrade!");
		try (
			final ReadOnlyFileHandle readHandle = new ReadOnlyFileHandle(bootstrapFilePath, bootstrapStorageOptions);
			final BootstrapWriteOnlyFileHandle targetBootstrapHandle = DefaultCatalogPersistenceService.createBootstrapWriteOnlyHandle(
				catalogName, bootstrapStorageOptions, targetPath
			);
		) {
			targetBootstrapHandle.checkAndExecute(
				"copy bootstrap record",
				Functions.noOpRunnable(),
				output -> {
					for (int i = 0; i < recordCount; i++) {
						final long startPosition = getOldPositionForRecord(i);
						final CatalogBootstrap bootstrapRecord = deserializeOldCatalogBootstrapRecord(
							startPosition, readHandle);
						Assert.isPremiseValid(
							bootstrapRecord != null,
							() -> new GenericEvitaInternalError("Failed to read the bootstrap record from the file!")
						);

						// append to the new file
						DefaultCatalogPersistenceService.serializeBootstrapRecord(output, bootstrapRecord);
					}
					return null;
				}
			);
		} catch (Exception e) {
			throw new UnexpectedIOException(
				"Failed to open catalog bootstrap file `" + bootstrapFilePath + "`!",
				"Failed to open catalog bootstrap file!",
				e
			);
		}
		ConsoleWriter.writeLine("Migrated catalog `" + catalogName + "` bootstrap file.", ConsoleColor.DARK_BLUE);
		// finally, migrate all data files in the folder
		try (
			final Stream<Path> streamWalker = Files.walk(catalogStoragePath);
		) {
			streamWalker
				.filter(
					filePath -> {
						final String thePath = filePath.normalize().toString();
						return thePath.endsWith(CatalogPersistenceService.CATALOG_FILE_SUFFIX) ||
							thePath.endsWith(CatalogPersistenceService.ENTITY_COLLECTION_FILE_SUFFIX) ||
							thePath.endsWith(PersistenceService.WAL_FILE_SUFFIX);
					}
				)
				.forEach(filePath -> {
					final long sourceFileSize = filePath.toFile().length();
					final String fileName = filePath.getFileName().toString();
					try (
						final ObservableInput<RandomAccessFileInputStream> input = new ObservableInput<>(
							new RandomAccessFileInputStream(new RandomAccessFile(filePath.toFile(), "rw"), true)
						);
						final ObservableOutput<FileOutputStream> output = new ObservableOutput<>(
							new FileOutputStream(targetPath.resolve(fileName).toFile(), false),
							storageOptions.outputBufferSize(),
							sourceFileSize
						)
					) {
						if (storageOptions.computeCRC32C()) {
							input.computeCRC32();
							output.computeCRC32();
						}
						if (fileName.endsWith(PersistenceService.WAL_FILE_SUFFIX)) {
							RawRecord rawRecord;
							long endOfTransaction = 0L;
							long readTotal = 0L;
							do {
								// each transaction is preceded by overall transaction length
								if (endOfTransaction == -1 || readTotal >= endOfTransaction) {
									final int txLength = input.simpleIntRead();
									endOfTransaction += 4 + txLength;
									output.writeInt(txLength);
									readTotal += 4;
								}
								rawRecord = StorageRecord.readOldRaw(input);
								StorageRecord.writeRaw(
									output, rawRecord.control(), rawRecord.generationId(), rawRecord.rawData());
								readTotal += rawRecord.location().recordLength();
							} while (readTotal + WAL_TAIL_LENGTH < sourceFileSize);
						} else {
							try {
								RawRecord rawRecord;
								do {
									rawRecord = StorageRecord.readOldRaw(input, sourceFileSize);
									StorageRecord.writeRaw(
										output, rawRecord.control(), rawRecord.generationId(), rawRecord.rawData());
								} while (rawRecord.location().endPosition() < sourceFileSize);
							} catch (PrematureEndOfFileException ex) {
								ConsoleWriter.writeLine(
									"There is a dangling record at the end of the file: " + filePath + ". Catalog might me corrupted after migration if the record is referenced in current indexes.",
									ConsoleColor.BRIGHT_RED
								);
							}
							ConsoleWriter.writeLine(
								"Migrated catalog `" + catalogName + "` file: " + filePath, ConsoleColor.DARK_BLUE);
						}
					} catch (FileNotFoundException e) {
						throw new UnexpectedIOException(
							"Failed to open the catalog data file `" + filePath + "`!",
							"Failed to open a catalog data file!",
							e
						);
					} catch (RuntimeException e) {
						ConsoleWriter.writeLine(
							"Failed to migrate catalog `" + catalogName + "` file: " + filePath + ", error: " + e.getMessage(),
							ConsoleColor.BRIGHT_RED, ConsoleDecoration.BOLD
						);
						throw e;
					}
				});
			// finally remove original directory and rename migrated one
			ConsoleWriter.writeLine(
				"Upgrade finished, removing old catalog `" + catalogStoragePath + "`", ConsoleColor.DARK_BLUE);
			FileUtils.deleteDirectory(catalogStoragePath);
			ConsoleWriter.writeLine(
				"Upgrade finished, renaming upgraded folder `" + targetPath + "` to `" + catalogStoragePath + "`",
				ConsoleColor.DARK_BLUE
			);
			FileUtils.renameFolder(targetPath, catalogStoragePath);
			ConsoleWriter.writeLine(
				"Upgrade of catalog `" + catalogName + "` successfully finished.", ConsoleColor.BRIGHT_BLUE,
				ConsoleDecoration.BOLD
			);
		} catch (IOException e) {
			ConsoleWriter.writeLine(
				"Upgrade of catalog `" + catalogName + "` failed!", ConsoleColor.BRIGHT_RED, ConsoleDecoration.BOLD);
			throw new UnexpectedIOException(
				"Failed to migrate catalog `" + catalogName + "` data file!",
				"Failed to migrate a catalog data file!",
				e
			);
		} finally {
			if (targetPath.toFile().exists()) {
				ConsoleWriter.writeLine("Cleaning temporary directory `" + targetPath + "`.", ConsoleColor.DARK_BLUE);
				FileUtils.deleteDirectory(targetPath);
			}
		}
	}

	/**
	 * Calculates the number of records in a file based on its length.
	 *
	 * @param fileLength The length of the file in bytes.
	 * @return The number of records in the file.
	 * @deprecated introduced with #650 and could be removed later when no version prior to 2025.2 is used
	 */
	@Deprecated(since = "2025.1", forRemoval = true)
	static int getOldRecordCount(long fileLength) {
		return Math.toIntExact(fileLength / (CatalogBootstrap.BOOTSTRAP_RECORD_SIZE - 4));
	}

	/**
	 * Returns last meaningful position in the file. It is the last position that can be used to read the record
	 * without risk of reading incomplete record.
	 *
	 * @param fileLength length of the file
	 * @return last meaningful position
	 * @deprecated introduced with #650 and could be removed later when no version prior to 2025.2 is used
	 */
	@Deprecated(since = "2025.1", forRemoval = true)
	static long getOldLastMeaningfulPosition(long fileLength) {
		// removes non-divisible remainder as it might be incomplete record and returns last meaningful position
		final int oldSize = CatalogBootstrap.BOOTSTRAP_RECORD_SIZE - 4;
		return fileLength - (fileLength % oldSize) - oldSize;
	}

	/**
	 * Calculates the position of a record in the file based on its index.
	 *
	 * @param index The index of the record.
	 * @return The position of the record in the file.
	 * @deprecated introduced with #650 and could be removed later when no version prior to 2025.2 is used
	 */
	@Deprecated(since = "2025.1", forRemoval = true)
	static long getOldPositionForRecord(int index) {
		return (long) index * (CatalogBootstrap.BOOTSTRAP_RECORD_SIZE - 4);
	}
}
