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

import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.exception.UnexpectedIOException;
import io.evitadb.spi.export.ExportService;
import io.evitadb.spi.export.model.ExportFileHandle;
import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
import io.evitadb.store.checksum.Checksum;
import io.evitadb.store.checksum.ChecksumFactory;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.shared.model.FileLocation;
import io.evitadb.store.wal.AbstractMutationLog;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ConsoleWriter;
import io.evitadb.utils.ConsoleWriter.ConsoleColor;
import io.evitadb.utils.ConsoleWriter.ConsoleDecoration;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.evitadb.spi.store.catalog.persistence.PersistenceService.WAL_FILE_SUFFIX;

/**
 * Migration interface containing one-time migration logic for upgrading WAL files from storage protocol version 4
 * to version 5. The migration rewrites WAL files to include cumulative CRC32C checksums.
 *
 * **Old WAL format (early version 4):**
 * - File starts immediately with transaction data (no initial checksum)
 * - Transaction: `contentLength (4B) | txData (contentLength bytes)`
 * - Finalized file tail: `firstCv (8B) | lastCv (8B) | CRC32C(firstCv, lastCv) (8B)` — 24 bytes
 *
 * **New WAL format (version 5):**
 * - File starts with initial cumulative CRC32C (8B)
 * - Transaction: `contentLength (4B) | txData (contentLength bytes) | cumulativeCRC32C (8B)`
 * - Finalized file tail: `firstCv (8B) | lastCv (8B) | cumulativeCRC32C_of_entire_file (8B)` — 24 bytes
 *
 * **Ambiguity of "version 4" on disk:** the cumulative-checksum framing was added to the WAL writer/reader
 * before the storage protocol number was bumped from 4 to 5. As a result a file may already be in the version-5
 * format (byte-identical) while its bootstrap/header still reports protocol version 4 ("late version 4"). Such a
 * file must NOT be converted — doing so would misread its 8-byte initial checksum as a transaction content
 * length and drop every transaction. {@link #isWalFileAlreadyChecksummed} detects this case and the migration
 * then preserves the WAL files untouched, letting the caller bump only the stored protocol version.
 *
 * The migration uses a two-phase approach (for genuine early-version-4 files):
 * 1. Convert all WAL files to `.wal.upgrade` files
 * 2. Replace all original `.wal` files with the `.wal.upgrade` files
 *
 * Phase 0 (recovery check) detects and handles partial migration from a previous interrupted attempt.
 *
 * @deprecated introduced with #1062 and could be removed later when no version prior to 2026.1 is used
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Deprecated(since = "2026.1", forRemoval = true)
public interface Migration_2026_1 {

	/**
	 * Old WAL tail length in version 4: firstCv (8B) + lastCv (8B) + CRC32C(firstCv, lastCv) (8B) = 24 bytes.
	 */
	int OLD_WAL_TAIL_LENGTH = 24;
	/**
	 * Suffix appended to WAL files during conversion.
	 */
	String UPGRADE_SUFFIX = ".upgrade";

	/**
	 * Result of converting a single WAL file, containing the final cumulative checksum for cross-file
	 * chaining and optionally the corrected file location for the stored WAL reference.
	 *
	 * @param finalCumulativeChecksum     the final cumulative checksum of this file (for chaining to next file)
	 * @param correctedFileLocation       the corrected file location for the stored WAL reference (null if
	 *                                    no matching transaction was found in this file)
	 * @param correctedCumulativeChecksum the cumulative checksum at the matched transaction position
	 */
	record WalFileConversionResult(
		long finalCumulativeChecksum,
		@Nullable FileLocation correctedFileLocation,
		long correctedCumulativeChecksum
	) {}

	/**
	 * Upgrades catalog WAL files from storage protocol version 4 to 5 by rewriting them with cumulative
	 * CRC32C checksums.
	 *
	 * @param catalogHeader     the catalog header containing WAL file reference
	 * @param catalogStoragePath path to the catalog storage directory
	 * @param walFileReference  the current WAL file reference (may be null if no WAL exists)
	 * @param exportService     export service for creating WAL backup
	 * @param checksumFactory   factory for creating checksum calculators
	 * @param postUpgradeAction action to execute after successful upgrade, receives the corrected WAL
	 *                          file reference (or null if no correction was computed, e.g. during recovery)
	 */
	static void upgradeCatalogWalFiles(
		@Nonnull CatalogHeader<LogFileRecordReference, CollectionFileReference> catalogHeader,
		@Nonnull Path catalogStoragePath,
		@Nullable LogFileRecordReference walFileReference,
		@Nonnull ExportService exportService,
		@Nonnull ChecksumFactory checksumFactory,
		@Nonnull Consumer<LogFileRecordReference> postUpgradeAction
	) {
		ConsoleWriter.writeLine(
			"Catalog `" + catalogHeader.catalogName() + "` contains storage protocol version 4. " +
				"Upgrading WAL files to version 5 (adding cumulative CRC32C checksums).",
			ConsoleColor.BRIGHT_BLUE
		);

		// catalog uses the Consumer callback to write the header with the corrected reference
		upgradeWalFiles(
			catalogHeader.catalogName(),
			catalogStoragePath,
			walFileReference,
			exportService,
			checksumFactory,
			postUpgradeAction
		);

		ConsoleWriter.writeLine(
			"Catalog `" + catalogHeader.catalogName() + "` was successfully upgraded to protocol version 5.",
			ConsoleColor.BRIGHT_BLUE, ConsoleDecoration.BOLD
		);
	}

	/**
	 * Upgrades engine WAL files from storage protocol version 4 to 5 by rewriting them with cumulative
	 * CRC32C checksums. No backup is created for engine WAL files because the export service is not
	 * available at engine initialization time. The two-phase upgrade approach (`.upgrade` files) still
	 * provides crash safety.
	 *
	 * @param storagePath      path to the engine storage directory
	 * @param walFileReference the current WAL file reference (may be null if no WAL exists)
	 * @param checksumFactory  factory for creating checksum calculators
	 * @return the corrected WAL file reference with updated byte positions and cumulative checksum,
	 *         or null if no WAL files exist or no correction was needed
	 */
	@Nullable
	static LogFileRecordReference upgradeEngineWalFiles(
		@Nonnull Path storagePath,
		@Nullable LogFileRecordReference walFileReference,
		@Nonnull ChecksumFactory checksumFactory
	) {
		ConsoleWriter.writeLine(
			"Engine state contains storage protocol version 4. " +
				"Upgrading WAL files to version 5 (adding cumulative CRC32C checksums).",
			ConsoleColor.BRIGHT_BLUE
		);

		final LogFileRecordReference correctedRef = upgradeWalFiles(
			"engine",
			storagePath,
			walFileReference,
			null,
			checksumFactory,
			// engine state update is handled by the caller
			(ref) -> {}
		);

		ConsoleWriter.writeLine(
			"Engine WAL files were successfully upgraded to protocol version 5.",
			ConsoleColor.BRIGHT_BLUE, ConsoleDecoration.BOLD
		);

		return correctedRef;
	}

	/**
	 * Core WAL upgrade logic shared between catalog and engine WAL files.
	 *
	 * After conversion, the byte positions in the WAL files change due to the added initial checksum
	 * (8 bytes) and per-transaction cumulative checksums (8 bytes each). If a `walFileReference` is
	 * provided, this method computes the corrected file location and cumulative checksum and passes
	 * it to the `postUpgradeAction` consumer, and also returns it.
	 *
	 * @param name              descriptive name for logging (catalog name or "engine")
	 * @param storagePath       path to the directory containing WAL files
	 * @param walFileReference  the current WAL file reference (may be null if no WAL exists)
	 * @param exportService     export service for creating WAL backup (may be null to skip backup)
	 * @param checksumFactory   factory for creating checksum calculators
	 * @param postUpgradeAction action to execute after successful replacement, receives the corrected
	 *                          WAL file reference (or null if no correction was computed)
	 * @return the corrected WAL file reference, or null if no correction was needed
	 */
	@Nullable
	private static LogFileRecordReference upgradeWalFiles(
		@Nonnull String name,
		@Nonnull Path storagePath,
		@Nullable LogFileRecordReference walFileReference,
		@Nullable ExportService exportService,
		@Nonnull ChecksumFactory checksumFactory,
		@Nonnull Consumer<LogFileRecordReference> postUpgradeAction
	) {
		// Identify all WAL files in the storage directory
		final File[] walFiles = storagePath.toFile().listFiles(
			(dir, fileName) -> fileName.endsWith(WAL_FILE_SUFFIX) && !fileName.endsWith(UPGRADE_SUFFIX)
		);

		if (walFiles == null || walFiles.length == 0) {
			// No WAL files to migrate - just run the post-upgrade action with no correction
			postUpgradeAction.accept(null);
			return null;
		}

		// Sort WAL files by their index
		Arrays.sort(walFiles, Comparator.comparingInt(
			f -> AbstractMutationLog.getIndexFromWalFileName(f.getName())
		));

		// Determine the active file index
		final int activeFileIndex = walFileReference != null ? walFileReference.fileIndex() : -1;

		// "Version 4" on disk is ambiguous. The cumulative-CRC32C WAL framing was introduced before the storage
		// protocol was bumped from 4 to 5, so a WAL file may report protocol version 4 while already being
		// byte-identical to version 5 ("late version 4"). Running the pre-checksum conversion below on such a file
		// would misread its 8-byte initial cumulative checksum as a transaction content length and drop every
		// transaction. Detect the already-checksummed shape on the active (last-written) file and, if present,
		// preserve every WAL file untouched and only let the caller bump the stored protocol version — the reload
		// path recomputes the WAL reference from the file itself, so no reference correction is needed here. The
		// decision is directory-granular: the whole file series is produced by a single writer, so the active file
		// is representative of the format of the rest.
		File activeWalFile = null;
		if (activeFileIndex >= 0) {
			for (final File walFile : walFiles) {
				if (AbstractMutationLog.getIndexFromWalFileName(walFile.getName()) == activeFileIndex) {
					activeWalFile = walFile;
					break;
				}
			}
		}
		if (activeWalFile == null) {
			activeWalFile = walFiles[walFiles.length - 1];
		}
		if (isWalFileAlreadyChecksummed(activeWalFile, checksumFactory)) {
			ConsoleWriter.writeLine(
				"WAL files for `" + name + "` already carry cumulative CRC32C checksums; preserving them as-is " +
					"and only bumping the storage protocol version.",
				ConsoleColor.BRIGHT_BLUE
			);
			// null correction => the caller keeps the existing (already valid) WAL reference; the reload
			// self-corrects the byte positions and cumulative checksum from the file contents.
			postUpgradeAction.accept(null);
			return null;
		}

		// Phase 0: Recovery check
		final File[] upgradeFiles = storagePath.toFile().listFiles(
			(dir, fileName) -> fileName.endsWith(WAL_FILE_SUFFIX + UPGRADE_SUFFIX)
		);

		if (upgradeFiles != null && upgradeFiles.length > 0) {
			if (upgradeFiles.length == walFiles.length) {
				// All upgrade files exist - skip to Phase 2 (replacement)
				// We don't have the corrected reference (conversion was done in a previous attempt),
				// so pass null - checkAndTruncate will self-correct on next startup
				ConsoleWriter.writeLine(
					"Found " + upgradeFiles.length + " upgrade files from previous migration attempt. " +
						"Completing replacement.",
					ConsoleColor.BRIGHT_BLUE
				);
				replaceWalFiles(storagePath, walFiles, null, postUpgradeAction);
				return null;
			} else {
				// Partial upgrade files - delete them and restart
				ConsoleWriter.writeLine(
					"Found " + upgradeFiles.length + " partial upgrade files from previous migration attempt. " +
						"Deleting and restarting.",
					ConsoleColor.BRIGHT_BLUE
				);
				for (File upgradeFile : upgradeFiles) {
					if (!upgradeFile.delete()) {
						throw new UnexpectedIOException(
							"Failed to delete partial upgrade file: " + upgradeFile.getAbsolutePath(),
							"Failed to delete partial upgrade file!"
						);
					}
				}
			}
		}

		// Phase 1: Backup WAL files (only if export service is available)
		if (exportService != null) {
			backupWalFiles(name, storagePath, walFiles, exportService);
		}

		// Phase 1 continued: Convert all WAL files
		// Thread the cumulative checksum across files to maintain the cross-file CRC chain
		long previousFileCumulativeChecksum = 0L;
		LogFileRecordReference correctedWalFileReference = null;
		for (File walFile : walFiles) {
			final int fileIndex = AbstractMutationLog.getIndexFromWalFileName(walFile.getName());
			final boolean isFinalized = fileIndex < activeFileIndex;
			final boolean isActive = fileIndex == activeFileIndex;

			// For the file matching the stored WAL reference, pass the old file location
			// so convertWalFile can compute the corrected position
			final FileLocation oldFileLocation =
				walFileReference != null && fileIndex == walFileReference.fileIndex()
					? walFileReference.fileLocation()
					: null;
			// Track the cumulative checksum at the start of the referenced file
			// (needed when fileLocation is null — no processed transactions in the file)
			final long cumulativeChecksumAtFileStart = previousFileCumulativeChecksum;

			final WalFileConversionResult result = convertWalFile(
				walFile, isFinalized, checksumFactory, previousFileCumulativeChecksum, oldFileLocation
			);
			previousFileCumulativeChecksum = result.finalCumulativeChecksum();

			// Build corrected WAL reference for the file matching walFileReference
			if (walFileReference != null && fileIndex == walFileReference.fileIndex()) {
				if (result.correctedFileLocation() != null) {
					// Transaction was found and position was corrected
					correctedWalFileReference = new LogFileRecordReference(
						walFileReference.walFileNameProvider(),
						walFileReference.fileIndex(),
						result.correctedFileLocation(),
						result.correctedCumulativeChecksum()
					);
				} else if (walFileReference.fileLocation() == null) {
					// No processed transactions — update only the cumulative checksum
					correctedWalFileReference = new LogFileRecordReference(
						walFileReference.walFileNameProvider(),
						walFileReference.fileIndex(),
						null,
						cumulativeChecksumAtFileStart
					);
				}
				// else: fileLocation was non-null but no matching transaction found (shouldn't happen
				// with valid data) — leave correctedWalFileReference as null
			}

			ConsoleWriter.writeLine(
				"Converted WAL file: " + walFile.getName() +
					(isFinalized ? " (finalized)" : isActive ? " (active)" : " (unknown)"),
				ConsoleColor.BRIGHT_BLUE
			);
		}

		// Phase 2: Replace all WAL files and execute post-upgrade action with corrected reference
		replaceWalFiles(storagePath, walFiles, correctedWalFileReference, postUpgradeAction);
		return correctedWalFileReference;
	}

	/**
	 * Creates a backup ZIP of all WAL files before migration.
	 *
	 * @param name          descriptive name for the backup file
	 * @param storagePath   path to the storage directory
	 * @param walFiles      array of WAL files to back up
	 * @param exportService export service for creating the backup
	 */
	private static void backupWalFiles(
		@Nonnull String name,
		@Nonnull Path storagePath,
		@Nonnull File[] walFiles,
		@Nonnull ExportService exportService
	) {
		final String backupFileName = name + "_wal_backup_v4.zip";
		try (
			final ExportFileHandle exportHandle = exportService.storeFile(
				backupFileName,
				"WAL backup before migration from storage protocol version 4 to 5",
				"application/zip",
				"migration"
			)
		) {
			try (ZipOutputStream zipOut = new ZipOutputStream(exportHandle.outputStream())) {
				final byte[] buffer = new byte[8192];
				for (File walFile : walFiles) {
					zipOut.putNextEntry(new ZipEntry(walFile.getName()));
					try (FileInputStream fis = new FileInputStream(walFile)) {
						int bytesRead;
						while ((bytesRead = fis.read(buffer)) != -1) {
							zipOut.write(buffer, 0, bytesRead);
						}
					}
					zipOut.closeEntry();
				}
			}
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Failed to create WAL backup for " + name + ": " + e.getMessage(),
				"Failed to create WAL backup!",
				e
			);
		}

		ConsoleWriter.writeLine(
			"Created WAL backup: " + backupFileName + " (" + walFiles.length + " files)",
			ConsoleColor.BRIGHT_BLUE
		);
	}

	/**
	 * Converts a single WAL file from old format (v4) to new format (v5) with cumulative CRC32C checksums.
	 * The converted file is written with `.upgrade` suffix.
	 *
	 * If `oldFileLocation` is provided, this method also tracks the position of the matching transaction
	 * in the new file, so the caller can update the stored WAL reference with corrected byte positions.
	 *
	 * @param walFile                        the WAL file to convert
	 * @param isFinalized                    true if the file is finalized (has a tail), false if active
	 * @param checksumFactory                factory for creating checksum calculators
	 * @param previousFileCumulativeChecksum cumulative checksum from the previous WAL file's tail
	 *                                       (0 for the first file)
	 * @param oldFileLocation                the file location of the last processed transaction from the
	 *                                       stored WAL reference (null if this file doesn't contain the
	 *                                       referenced transaction or no transactions were processed)
	 * @return conversion result containing the final cumulative checksum and optionally the corrected
	 *         file location for the stored WAL reference
	 */
	@Nonnull
	private static WalFileConversionResult convertWalFile(
		@Nonnull File walFile,
		boolean isFinalized,
		@Nonnull ChecksumFactory checksumFactory,
		long previousFileCumulativeChecksum,
		@Nullable FileLocation oldFileLocation
	) {
		final Path upgradePath = walFile.toPath().resolveSibling(walFile.getName() + UPGRADE_SUFFIX);
		final Checksum cumulativeChecksum = checksumFactory.createCumulativeChecksum(previousFileCumulativeChecksum);

		// Results captured inside the try and consumed after the channels are closed, so the fail-safe abort below
		// can portably delete the partial `.upgrade` file before throwing.
		long transactionDataEnd;
		long convertedByteCount;
		long resultCumulativeChecksum;
		FileLocation resultCorrectedLocation;
		long resultCorrectedChecksum;

		try (
			FileChannel readChannel = FileChannel.open(walFile.toPath(), StandardOpenOption.READ);
			FileChannel writeChannel = FileChannel.open(
				upgradePath,
				StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
			)
		) {
			final long fileLength = readChannel.size();

			// Write initial cumulative CRC32C (8 bytes, chained from previous file or 0 for first file)
			final ByteBuffer initialChecksumBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
			final long initialChecksum = cumulativeChecksum.getValue();
			initialChecksumBuf.putLong(initialChecksum);
			initialChecksumBuf.flip();
			Assert.isPremiseValid(
				8 == writeChannel.write(initialChecksumBuf),
				"Failed to write initial cumulative checksum!"
			);
			cumulativeChecksum.update(initialChecksum); // initial checksum is part of the cumulative checksum

			// Calculate where transaction data ends
			transactionDataEnd = isFinalized ? fileLength - OLD_WAL_TAIL_LENGTH : fileLength;

			// Read and write transactions
			long readPosition = 0;
			long lastTxCumulativeChecksum = previousFileCumulativeChecksum;
			FileLocation correctedFileLocation = null;
			long correctedCumulativeChecksum = 0L;
			final ByteBuffer contentLengthBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);

			while (readPosition < transactionDataEnd) {
				// Read 4-byte content length
				contentLengthBuf.clear();
				final int bytesRead = readChannel.read(contentLengthBuf, readPosition);
				if (bytesRead < 4) {
					break; // end of data or corrupt
				}
				contentLengthBuf.flip();
				final int contentLength = contentLengthBuf.getInt();

				if (contentLength <= 0 || readPosition + 4 + contentLength > transactionDataEnd) {
					break; // invalid or truncated transaction
				}

				// Track the write position before writing this transaction (for position correction)
				final long newTxStartPosition = writeChannel.position();

				// Read transaction data
				final byte[] txData = new byte[contentLength];
				final ByteBuffer txDataBuf = ByteBuffer.wrap(txData);
				int totalRead = 0;
				while (totalRead < contentLength) {
					final int read = readChannel.read(txDataBuf, readPosition + 4 + totalRead);
					if (read < 0) {
						break;
					}
					totalRead += read;
				}

				// Update cumulative checksum with content length (as 4 bytes) and transaction data
				cumulativeChecksum.update(contentLength);
				cumulativeChecksum.update(txData);

				// Write to new file: content length + transaction data + cumulative CRC32C
				final long txCumulativeChecksum = cumulativeChecksum.getValue();
				lastTxCumulativeChecksum = txCumulativeChecksum;
				final int expectedWriteLength = 4 + contentLength + 8;
				final ByteBuffer writeBuf = ByteBuffer.allocate(expectedWriteLength)
					.order(ByteOrder.LITTLE_ENDIAN);
				writeBuf.putInt(contentLength);
				writeBuf.put(txData);
				writeBuf.putLong(txCumulativeChecksum);
				writeBuf.flip();
				Assert.isPremiseValid(
					expectedWriteLength == writeChannel.write(writeBuf),
					"Failed to write converted transaction content length!"
				);

				// If this transaction matches the stored WAL reference, record corrected position
				if (oldFileLocation != null && readPosition == oldFileLocation.startingPosition()) {
					correctedFileLocation = new FileLocation(
						newTxStartPosition,
						AbstractMutationLog.TRANSACTION_PREFIX_SIZE + contentLength +
							AbstractMutationLog.CUMULATIVE_CRC32_SIZE
					);
					correctedCumulativeChecksum = txCumulativeChecksum;
				}

				cumulativeChecksum.update(txCumulativeChecksum);
				readPosition += 4 + contentLength;
			}

			// Handle tail for finalized files
			if (isFinalized && transactionDataEnd + OLD_WAL_TAIL_LENGTH <= fileLength) {
				final ByteBuffer tailBuf = ByteBuffer.allocate(OLD_WAL_TAIL_LENGTH).order(ByteOrder.LITTLE_ENDIAN);
				readChannel.read(tailBuf, transactionDataEnd);
				tailBuf.flip();

				final long firstCv = tailBuf.getLong();
				final long lastCv = tailBuf.getLong();
				// Skip old CRC32C of versions (8 bytes) - we don't need it

				// Update cumulative checksum with firstCv and lastCv
				cumulativeChecksum.update(firstCv);
				cumulativeChecksum.update(lastCv);

				// Write new tail: firstCv + lastCv + cumulative CRC32C of entire file
				final long tailCumulativeChecksum = cumulativeChecksum.getValue();
				final ByteBuffer newTailBuf = ByteBuffer.allocate(AbstractMutationLog.WAL_TAIL_LENGTH)
					.order(ByteOrder.LITTLE_ENDIAN);
				newTailBuf.putLong(firstCv);
				newTailBuf.putLong(lastCv);
				newTailBuf.putLong(tailCumulativeChecksum);
				newTailBuf.flip();
				Assert.isPremiseValid(
					OLD_WAL_TAIL_LENGTH == writeChannel.write(newTailBuf),
					"Failed to write converted WAL tail!"
				);
				// For finalized files, the tail checksum chains to the next file
				lastTxCumulativeChecksum = tailCumulativeChecksum;
			}

			writeChannel.force(true);
			convertedByteCount = readPosition;
			resultCumulativeChecksum = lastTxCumulativeChecksum;
			resultCorrectedLocation = correctedFileLocation;
			resultCorrectedChecksum = correctedCumulativeChecksum;
		} catch (IOException e) {
			// Clean up any partial upgrade file so a later Phase 0 recovery cannot complete a broken conversion.
			try {
				Files.deleteIfExists(upgradePath);
			} catch (IOException suppressed) {
				e.addSuppressed(suppressed);
			}
			throw new UnexpectedIOException(
				"Failed to convert WAL file: " + walFile.getAbsolutePath(),
				"Failed to convert WAL file!",
				e
			);
		}

		// Fail-safe backstop (channels now closed): if the source held transaction data yet not a single transaction
		// was converted, the file is not in the assumed pre-checksum layout (e.g. it is already checksummed and
		// slipped past detection). Delete the partial `.upgrade` file — so a later Phase 0 recovery cannot complete
		// a destructive replace — and abort before the original WAL is ever touched.
		if (convertedByteCount == 0 && transactionDataEnd > 0) {
			try {
				Files.deleteIfExists(upgradePath);
			} catch (IOException e) {
				throw new UnexpectedIOException(
					"WAL migration aborted (zero transactions read from a non-empty file `" + walFile.getName() +
						"`) but the partial upgrade file `" + upgradePath + "` could not be removed; delete it " +
						"manually before restarting to avoid data loss.",
					"Failed to clean up partial WAL upgrade file after an aborted migration!",
					e
				);
			}
			throw new GenericEvitaInternalError(
				"WAL migration read zero transactions from a non-empty file `" + walFile.getName() + "` (" +
					transactionDataEnd + " bytes of transaction data). Refusing to produce an empty WAL to avoid " +
					"data loss — the file may already be in the checksummed format."
			);
		}

		return new WalFileConversionResult(
			resultCumulativeChecksum, resultCorrectedLocation, resultCorrectedChecksum
		);
	}

	/**
	 * Determines whether the given WAL file is already stored in the checksummed (version 5) format — i.e. it
	 * begins with an 8-byte initial cumulative CRC32C and every transaction frame carries its trailing cumulative
	 * checksum. This is the case for "late version 4" files: the cumulative-checksum framing was introduced before
	 * the storage protocol was bumped from 4 to 5, so a file may legitimately report protocol version 4 on disk
	 * while already being byte-identical to version 5. Running {@link #convertWalFile} on such a file would misread
	 * its initial checksum as a transaction content length and drop every transaction, so the migration must
	 * detect and preserve these files.
	 *
	 * Detection is positive confirmation and mirrors the WAL reader exactly: the file must parse cleanly as the
	 * checksummed format from its initial checksum to its end, with every per-transaction cumulative checksum
	 * matching the recomputed value (the running checksum is fed length-then-content in the same order the writer
	 * used). A genuine pre-checksum (early version 4) file fails this because its leading bytes do not form a
	 * self-consistent checksum chain that lands exactly on the file boundary. An expected caller passes the active
	 * (non-finalized) file, which has no trailing tail.
	 *
	 * @param walFile         the WAL file to inspect (the active, non-finalized file is expected)
	 * @param checksumFactory factory used to recompute the cumulative CRC32C chain
	 * @return true if the file is already in the checksummed version-5 format
	 */
	private static boolean isWalFileAlreadyChecksummed(
		@Nonnull File walFile,
		@Nonnull ChecksumFactory checksumFactory
	) {
		final int prefixSize = AbstractMutationLog.TRANSACTION_PREFIX_SIZE;
		final int checksumSize = AbstractMutationLog.CUMULATIVE_CRC32_SIZE;
		try (final FileChannel channel = FileChannel.open(walFile.toPath(), StandardOpenOption.READ)) {
			final long fileLength = channel.size();
			// The file must at least hold the initial checksum plus one complete frame (length + >=1B content +
			// per-transaction checksum); anything smaller cannot be a non-empty checksummed WAL.
			if (fileLength < (long) checksumSize + prefixSize + 1 + checksumSize) {
				return false;
			}

			final ByteBuffer longBuffer = ByteBuffer.allocate(checksumSize).order(ByteOrder.LITTLE_ENDIAN);
			final ByteBuffer intBuffer = ByteBuffer.allocate(prefixSize).order(ByteOrder.LITTLE_ENDIAN);

			// The checksummed format begins with the 8-byte initial cumulative checksum; seed the running checksum
			// from it exactly as the WAL reader does.
			longBuffer.clear();
			if (channel.read(longBuffer, 0) != checksumSize) {
				return false;
			}
			longBuffer.flip();
			final long initialChecksum = longBuffer.getLong();
			final Checksum cumulativeChecksum = checksumFactory.createCumulativeChecksum(initialChecksum);
			cumulativeChecksum.update(initialChecksum);

			long position = checksumSize;
			int framesValidated = 0;
			while (position + prefixSize + checksumSize <= fileLength) {
				// Read the 4-byte content length.
				intBuffer.clear();
				if (channel.read(intBuffer, position) != prefixSize) {
					return false;
				}
				intBuffer.flip();
				final int contentLength = intBuffer.getInt();
				final long frameEnd = position + prefixSize + (long) contentLength + checksumSize;
				if (contentLength <= 0 || frameEnd > fileLength) {
					// Not a well-formed checksummed frame at this position.
					break;
				}

				// Read the transaction content.
				final byte[] content = new byte[contentLength];
				final ByteBuffer contentBuffer = ByteBuffer.wrap(content);
				int totalRead = 0;
				while (totalRead < contentLength) {
					final int read = channel.read(contentBuffer, position + prefixSize + totalRead);
					if (read < 0) {
						return false;
					}
					totalRead += read;
				}

				// Read the stored per-transaction cumulative checksum.
				longBuffer.clear();
				if (channel.read(longBuffer, position + prefixSize + contentLength) != checksumSize) {
					return false;
				}
				longBuffer.flip();
				final long storedChecksum = longBuffer.getLong();

				// Recompute the running cumulative checksum in the writer's order (length, then content) and require
				// it to match the stored per-transaction checksum before chaining the checksum itself forward.
				cumulativeChecksum.update(contentLength);
				cumulativeChecksum.update(content);
				if (!cumulativeChecksum.equalsTo(storedChecksum)) {
					return false;
				}
				cumulativeChecksum.update(storedChecksum);
				framesValidated++;
				position = frameEnd;
			}

			// A genuine checksummed, non-finalized active file is fully consumed by complete frames.
			return framesValidated > 0 && position == fileLength;
		} catch (IOException e) {
			// If the file cannot be read as a checksummed WAL, let the normal conversion path handle it.
			return false;
		}
	}

	/**
	 * Phase 2: Replace all original WAL files with their upgraded counterparts.
	 * Deletes all `.wal` files first, then renames all `.wal.upgrade` files to `.wal`.
	 *
	 * @param storagePath              path to the storage directory
	 * @param originalWalFiles         the original WAL files to replace
	 * @param correctedWalFileReference the corrected WAL file reference to pass to the post-upgrade action
	 * @param postUpgradeAction        action to execute after successful replacement, receives
	 *                                 the corrected WAL file reference
	 */
	private static void replaceWalFiles(
		@Nonnull Path storagePath,
		@Nonnull File[] originalWalFiles,
		@Nullable LogFileRecordReference correctedWalFileReference,
		@Nonnull Consumer<LogFileRecordReference> postUpgradeAction
	) {
		// Delete all original WAL files
		for (File walFile : originalWalFiles) {
			if (walFile.exists() && !walFile.delete()) {
				throw new UnexpectedIOException(
					"Failed to delete original WAL file: " + walFile.getAbsolutePath(),
					"Failed to delete original WAL file!"
				);
			}
		}

		// Rename all upgrade files to their original names
		final File[] upgradeFiles = storagePath.toFile().listFiles(
			(dir, fileName) -> fileName.endsWith(WAL_FILE_SUFFIX + UPGRADE_SUFFIX)
		);

		if (upgradeFiles != null) {
			for (File upgradeFile : upgradeFiles) {
				final String originalName = upgradeFile.getName()
					.substring(0, upgradeFile.getName().length() - UPGRADE_SUFFIX.length());
				final Path targetPath = upgradeFile.toPath().resolveSibling(originalName);
				try {
					Files.move(upgradeFile.toPath(), targetPath);
				} catch (IOException e) {
					throw new UnexpectedIOException(
						"Failed to rename upgrade file: " + upgradeFile.getAbsolutePath() + " to " + targetPath,
						"Failed to rename upgrade file!",
						e
					);
				}
			}
		}

		// Execute post-upgrade action with the corrected WAL file reference
		postUpgradeAction.accept(correctedWalFileReference);
	}

}
