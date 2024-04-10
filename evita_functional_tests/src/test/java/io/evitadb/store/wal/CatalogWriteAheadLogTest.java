/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.store.wal;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.scheduling.Scheduler;
import io.evitadb.store.exception.WriteAheadLogCorruptedException;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.store.spi.OffHeapWithFileBackupReference;
import io.evitadb.store.spi.model.reference.WalFileReference;
import io.evitadb.utils.UUIDUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.OffsetDateTime;

import static io.evitadb.store.spi.CatalogPersistenceService.getWalFileName;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test verifying the behaviour of {@link CatalogWriteAheadLog}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
class CatalogWriteAheadLogTest {
	private final Path walDirectory = Path.of(System.getProperty("java.io.tmpdir")).resolve("evita").resolve(getClass().getSimpleName());
	private final Pool<Kryo> catalogKryoPool = new Pool<>(false, false, 1) {
		@Override
		protected Kryo create() {
			return KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
		}
	};
	private final WalFileReference walFileReference = new WalFileReference(TEST_CATALOG, 0, null);
	private final CatalogWriteAheadLog tested = new CatalogWriteAheadLog(
		TEST_CATALOG,
		walDirectory,
		catalogKryoPool,
		StorageOptions.builder().build(),
		TransactionOptions.builder().build(),
		Mockito.mock(Scheduler.class),
		offsetDateTime -> {}
	);
	private final Path walFilePath = walDirectory.resolve(getWalFileName(TEST_CATALOG, walFileReference.fileIndex()));
	private final int[] txSizes = new int[] {55, 152, 199, 46};

	@BeforeEach
	void setUp() {
		// and then write to the WAL a few times
		final ByteBuffer byteBuffer = ByteBuffer.allocate(200);
		for (int txSize : txSizes) {
			byteBuffer.clear();
			for (int i = 0; i < txSize; i++) {
				byteBuffer.put((byte) i);
			}

			final TransactionMutation writtenTransactionMutation = new TransactionMutation(
				UUIDUtil.randomUUID(), 1L, 2, txSize, OffsetDateTime.MIN
			);

			byteBuffer.flip();
			tested.append(
				writtenTransactionMutation,
				OffHeapWithFileBackupReference.withByteBuffer(
					byteBuffer, txSize, byteBuffer::clear
				)
			);
		}
	}

	@AfterEach
	void tearDown() throws IOException {
		tested.close();
		walFilePath.toFile().delete();
	}

	@Test
	void shouldVerifyWalIsOk() {
		// should not throw any exception
		CatalogWriteAheadLog.checkAndTruncate(walFilePath, catalogKryoPool, true);
	}

	@Test
	void shouldTruncatePartiallyWrittenWal() throws IOException {
		// truncate the WAL to simulate a partially written WAL
		final File walFile = walFilePath.toFile();
		final long originalWalFileLength = walFile.length();
		final long newLength = originalWalFileLength - (txSizes[txSizes.length - 1] / 2);
		try (final var raf = new java.io.RandomAccessFile(walFile, "rw")) {
			raf.setLength(newLength);
		}
		// should not throw any exception
		CatalogWriteAheadLog.checkAndTruncate(walFilePath, catalogKryoPool, true);

		final int txLengthBytes = 4;
		final int classIdBytes = 2;
		final int offsetDateTimeBytesDelta = 11;
		assertEquals(
			originalWalFileLength - txSizes[txSizes.length - 1] - (CatalogWriteAheadLog.TRANSACTION_MUTATION_SIZE - offsetDateTimeBytesDelta) - txLengthBytes - classIdBytes,
			walFile.length()
		);
	}

	@Test
	void shouldThrowExceptionWhenLeadingTxMutationIsDamaged() throws IOException {
		// truncate the WAL to simulate a damaged leading record
		final File walFile = walFilePath.toFile();
		final long originalWalFileLength = walFile.length();
		final long leadPosition = originalWalFileLength - (txSizes[txSizes.length - 1] + 10);
		// damage the leading transaction mutation
		try (final var raf = new java.io.RandomAccessFile(walFile, "rw")) {
			raf.seek(leadPosition);
			raf.write(new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
		}
		// should throw an exception
		assertThrows(
			WriteAheadLogCorruptedException.class,
			() -> CatalogWriteAheadLog.checkAndTruncate(walFilePath, catalogKryoPool, true)
		);
	}
}
