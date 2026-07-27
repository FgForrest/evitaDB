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

import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.schema.mutation.engine.CreateCatalogSchemaMutation;
import io.evitadb.core.executor.ImmediateScheduledThreadPoolExecutor;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.engine.EnginePersistenceService;
import io.evitadb.store.checksum.Crc32CChecksumFactory;
import io.evitadb.store.engine.DefaultEnginePersistenceService;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.UUID;

import static io.evitadb.store.wal.AbstractMutationLog.CUMULATIVE_CRC32_SIZE;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.TestTags.WAL;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the storage protocol version 4 → 5 WAL migration in {@link Migration_2026_1}, in particular the
 * handling of the ambiguous "version 4" on-disk format.
 *
 * The cumulative-CRC32C WAL framing was introduced before the storage protocol number was bumped from 4 to 5,
 * so a WAL file may already be byte-identical to version 5 while its bootstrap still reports protocol 4
 * ("late version 4"). The migration must recognise such files and preserve them — converting them would misread
 * the 8-byte initial checksum as a transaction length and drop every transaction (see the regression this guards).
 * Genuine pre-checksum ("early version 4") files must still be converted.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(WAL)
@DisplayName("Migration_2026_1 WAL v4→v5 upgrade")
class Migration_2026_1_Test implements EvitaTestSupport {

	/**
	 * A "late version 4" WAL — one already written with cumulative CRC32C checksums — must be preserved
	 * byte-for-byte by the upgrade, not rewritten. This is the direct regression for the migration silently
	 * emptying an already-checksummed engine WAL.
	 */
	@Test
	@DisplayName("preserves an already-checksummed (late v4) WAL instead of destroying it")
	void shouldPreserveAlreadyChecksummedWalDuringUpgrade() throws IOException {
		cleanTestSubDirectory(getClass().getSimpleName());
		final Path dir = getPathInTargetDirectory(getClass().getSimpleName());
		Files.createDirectories(dir);

		// Produce a genuine checksummed WAL using the real WAL writer (CRC32C is computed by default).
		final StorageOptions storageOptions = StorageOptions.builder()
			.storageDirectory(dir)
			.build();
		final TransactionOptions transactionOptions = TransactionOptions.builder()
			.transactionMemoryBufferLimitSizeBytes(1024 << 10)
			.transactionMemoryRegionCount(4)
			.build();
		final Scheduler scheduler = new Scheduler(new ImmediateScheduledThreadPoolExecutor());
		final DefaultEnginePersistenceService service =
			new DefaultEnginePersistenceService(storageOptions, transactionOptions, scheduler);
		final LogFileRecordReference walReference;
		try {
			service.appendWal(1L, UUID.randomUUID(), new CreateCatalogSchemaMutation("catalogA"));
			service.appendWal(2L, UUID.randomUUID(), new CreateCatalogSchemaMutation("catalogB"));
			walReference = service
				.appendWal(3L, UUID.randomUUID(), new CreateCatalogSchemaMutation("catalogC"))
				.walReference();
		} finally {
			service.close();
		}

		final Path walFile = dir.resolve(EnginePersistenceService.getWalFileName(0));
		final byte[] before = Files.readAllBytes(walFile);
		assertTrue(before.length > CUMULATIVE_CRC32_SIZE, "precondition: the WAL must contain transactions");

		final LogFileRecordReference correction =
			Migration_2026_1.upgradeEngineWalFiles(dir, walReference, Crc32CChecksumFactory.INSTANCE);

		assertNull(correction, "an already-checksummed WAL needs no reference correction");
		assertArrayEquals(
			before, Files.readAllBytes(walFile),
			"an already-checksummed WAL must be preserved byte-for-byte, not rewritten"
		);

		cleanTestSubDirectory(getClass().getSimpleName());
	}

	/**
	 * A genuine "early version 4" WAL — written before the cumulative-checksum framing existed — must still be
	 * converted (initial checksum prepended, per-transaction checksum appended), and the conversion must be
	 * idempotent: re-upgrading the converted file preserves it, proving the converter's output is recognised as
	 * already-checksummed.
	 */
	@Test
	@DisplayName("converts a genuine pre-checksum (early v4) WAL and is idempotent")
	void shouldConvertGenuinePreChecksumWalDuringUpgrade() throws IOException {
		cleanTestSubDirectory(getClass().getSimpleName());
		final Path dir = getPathInTargetDirectory(getClass().getSimpleName());
		Files.createDirectories(dir);

		// Craft a pre-checksum WAL: back-to-back `contentLength (4B) | content` frames, with neither an initial
		// checksum nor per-transaction checksums.
		final byte[] tx1 = "first-transaction-payload".getBytes(StandardCharsets.UTF_8);
		final byte[] tx2 = "second-transaction-payload-a-bit-longer".getBytes(StandardCharsets.UTF_8);
		final Path walFile = dir.resolve(EnginePersistenceService.getWalFileName(0));
		try (
			final FileChannel channel = FileChannel.open(
				walFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING
			)
		) {
			writePreChecksumFrame(channel, tx1);
			writePreChecksumFrame(channel, tx2);
		}
		final long originalSize = Files.size(walFile);

		Migration_2026_1.upgradeEngineWalFiles(
			dir,
			new LogFileRecordReference(EnginePersistenceService::getWalFileName, 0, null, 0L),
			Crc32CChecksumFactory.INSTANCE
		);

		// The converter adds one 8-byte initial checksum and one 8-byte checksum per transaction (2 transactions).
		assertEquals(
			originalSize + CUMULATIVE_CRC32_SIZE + 2L * CUMULATIVE_CRC32_SIZE,
			Files.size(walFile),
			"conversion must add the initial and per-transaction cumulative checksums"
		);

		// Idempotency: the converted file is now checksummed, so a second upgrade must recognise and preserve it.
		final byte[] afterConversion = Files.readAllBytes(walFile);
		final LogFileRecordReference secondCorrection = Migration_2026_1.upgradeEngineWalFiles(
			dir,
			new LogFileRecordReference(EnginePersistenceService::getWalFileName, 0, null, 0L),
			Crc32CChecksumFactory.INSTANCE
		);
		assertNull(secondCorrection, "re-upgrading an already-converted WAL must skip conversion");
		assertArrayEquals(
			afterConversion, Files.readAllBytes(walFile),
			"re-upgrading an already-converted WAL must not modify it"
		);

		cleanTestSubDirectory(getClass().getSimpleName());
	}

	/**
	 * A non-empty WAL that the converter cannot parse as pre-checksum (its leading 4 bytes read as a zero length)
	 * and that detection also rejects as checksummed must make the migration abort loudly — preserving the original
	 * and leaving no partial `.upgrade` file. Crucially this must hold across a retry (a simulated reboot): the
	 * Phase 0 recovery must not mistake a leftover husk for a completed conversion and finish a destructive replace.
	 */
	@Test
	@DisplayName("aborts without data loss and leaves no partial upgrade file when conversion reads no transactions")
	void shouldAbortWithoutDataLossWhenConversionReadsNoTransactions() throws IOException {
		cleanTestSubDirectory(getClass().getSimpleName());
		final Path dir = getPathInTargetDirectory(getClass().getSimpleName());
		Files.createDirectories(dir);

		// A 64-byte file whose first 4 bytes are zero (=> the converter reads a zero content length and converts
		// nothing) but which is clearly non-empty. It is neither valid pre-checksum nor valid checksummed data.
		final byte[] undecodable = new byte[64];
		Arrays.fill(undecodable, CUMULATIVE_CRC32_SIZE, undecodable.length, (byte) 0x07);
		final Path walFile = dir.resolve(EnginePersistenceService.getWalFileName(0));
		Files.write(walFile, undecodable);
		final byte[] before = Files.readAllBytes(walFile);

		final Path upgradeFile = dir.resolve(EnginePersistenceService.getWalFileName(0) + Migration_2026_1.UPGRADE_SUFFIX);

		// First attempt: must abort, preserve the original, and leave no partial upgrade file behind.
		assertThrows(
			GenericEvitaInternalError.class,
			() -> Migration_2026_1.upgradeEngineWalFiles(
				dir, new LogFileRecordReference(EnginePersistenceService::getWalFileName, 0, null, 0L),
				Crc32CChecksumFactory.INSTANCE
			)
		);
		assertArrayEquals(before, Files.readAllBytes(walFile), "the original WAL must be preserved on abort");
		assertFalse(Files.exists(upgradeFile), "no partial upgrade file may remain after an aborted conversion");

		// Second attempt (simulated reboot): Phase 0 recovery must not complete a destructive replace.
		assertThrows(
			GenericEvitaInternalError.class,
			() -> Migration_2026_1.upgradeEngineWalFiles(
				dir, new LogFileRecordReference(EnginePersistenceService::getWalFileName, 0, null, 0L),
				Crc32CChecksumFactory.INSTANCE
			)
		);
		assertArrayEquals(before, Files.readAllBytes(walFile), "the original WAL must remain intact across a retry");

		cleanTestSubDirectory(getClass().getSimpleName());
	}

	/**
	 * Appends a single pre-checksum (version-4) WAL frame: a 4-byte little-endian content length followed by the
	 * raw content bytes, with no trailing cumulative checksum. The whole frame is emitted as one buffer that is
	 * drained until fully written, because {@link FileChannel#write(ByteBuffer)} may write fewer bytes than the
	 * buffer holds. The channel is owned by the caller and deliberately left open here.
	 */
	private static void writePreChecksumFrame(@Nonnull FileChannel channel, @Nonnull byte[] content) throws IOException {
		final ByteBuffer frame = ByteBuffer.allocate(Integer.BYTES + content.length).order(ByteOrder.LITTLE_ENDIAN);
		frame.putInt(content.length);
		frame.put(content);
		frame.flip();
		while (frame.hasRemaining()) {
			channel.write(frame);
		}
	}
}
