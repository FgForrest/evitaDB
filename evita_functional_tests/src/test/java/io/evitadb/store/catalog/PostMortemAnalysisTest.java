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


import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.core.async.Scheduler;
import io.evitadb.core.metric.event.storage.FileType;
import io.evitadb.store.kryo.ObservableOutputKeeper;
import io.evitadb.store.offsetIndex.OffsetIndex;
import io.evitadb.store.offsetIndex.io.WriteOnlyFileHandle;
import io.evitadb.store.offsetIndex.model.OffsetIndexRecordTypeRegistry;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.store.spi.model.CatalogHeader;
import io.evitadb.store.spi.model.reference.WalFileReference;
import io.evitadb.store.wal.CatalogWriteAheadLog;
import io.evitadb.store.wal.WalKryoConfigurer;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.store.catalog.CatalogOffsetIndexStoragePartPersistenceService.loadOffsetIndexDescriptor;
import static io.evitadb.store.catalog.DefaultCatalogPersistenceService.VERSIONED_KRYO_FACTORY;
import static io.evitadb.store.catalog.DefaultCatalogPersistenceService.createWalIfAnyWalFilePresent;
import static io.evitadb.store.catalog.DefaultCatalogPersistenceService.getCatalogBootstrapRecordStream;
import static io.evitadb.store.spi.CatalogPersistenceService.getCatalogDataStoreFileName;

/**
 * This tests is expected to be used only manually to inspect the contents of the catalog files when the crash happens.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Disabled("This test is not meant to be run in CI, it is for manual post-mortem analysis of the catalog file remnants.")
public class PostMortemAnalysisTest implements EvitaTestSupport {
	private final ObservableOutputKeeper observableOutputKeeper = new ObservableOutputKeeper(
		TEST_CATALOG,
		StorageOptions.builder().build(),
		Mockito.mock(Scheduler.class)
	);

	@Test
	void analyzeBootFile() {
		final String catalogName = "decodoma_sk";
		final Path basePath = Path.of("/www/oss/evitaDB/data/");
		final StorageOptions storageOptions = StorageOptions.builder()
			.storageDirectory(basePath)
			.computeCRC32(true)
			.build();

		getCatalogBootstrapRecordStream(
			catalogName,
			storageOptions
		).forEach(it -> {
			System.out.println(it.catalogFileIndex() + "/" + it.catalogVersion() + ": " + it.timestamp() + " (" + it.fileLocation() + ")");
		});
	}

	@Test
	void analyzeWriteAheadLog() {
		final String catalogName = "decodoma_cz";
		final Path basePath = Path.of("/www/oss/evitaDB/data/");
		final Path catalogFilePath = basePath.resolve(catalogName);
		final StorageOptions storageOptions = StorageOptions.builder().storageDirectory(basePath).build();
		final TransactionOptions transactionOptions = TransactionOptions.builder().build();
		final Pool<Kryo> catalogKryoPool = new Pool<>(true, false, 16) {
			@Override
			protected Kryo create() {
				return KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
			}
		};

		try (
			final CatalogWriteAheadLog wal = new CatalogWriteAheadLog(
				1, catalogName, catalogFilePath, catalogKryoPool,
				storageOptions, transactionOptions,
				new Scheduler(ThreadPoolOptions.transactionThreadPoolBuilder().build()),
				0
			)
		) {
			final AtomicReference<UUID> lastTransactionId = new AtomicReference<>();
			wal.getCommittedMutationStream(-1)
				.forEach(mutation -> {
					if (mutation instanceof TransactionMutation txMut && !Objects.equals(lastTransactionId.get(), txMut.getTransactionId())) {
						System.out.println("\n\n>>>>  Transaction " + txMut.getTransactionId() + " at " + txMut.getCommitTimestamp() + "\n\n");
						lastTransactionId.set(txMut.getTransactionId());
					}
					System.out.println("  " + mutation);
				});
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@Test
	void analyzeCatalogHeader() {
		final String catalogName = "decodoma_cz";
		final Path basePath = Path.of("/www/oss/evitaDB/data/");
		final Path catalogFilePath = basePath.resolve(catalogName);
		final OffsetIndexRecordTypeRegistry recordRegistry = new OffsetIndexRecordTypeRegistry();
		final StorageOptions storageOptions = StorageOptions.builder().storageDirectory(basePath).build();
		final TransactionOptions transactionOptions = TransactionOptions.builder().build();
		final AtomicReference<CatalogHeader> catalogHeaderRef = new AtomicReference<>();
		final Pool<Kryo> catalogKryoPool = new Pool<>(true, false, 16) {
			@Override
			protected Kryo create() {
				return KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
			}
		};

		getCatalogBootstrapRecordStream(
			catalogName,
			storageOptions
		).forEach(it -> {
			System.out.print(it.catalogFileIndex() + "/" + it.catalogVersion() + ": " + it.timestamp() + " (" + it.fileLocation() + ")");
			try {
				final OffsetIndex indexRead = new OffsetIndex(
					it.catalogVersion(),
					catalogFilePath.resolve(getCatalogDataStoreFileName(catalogName, it.catalogFileIndex())),
					it.fileLocation(),
					storageOptions,
					recordRegistry,
					new WriteOnlyFileHandle(
						catalogName,
						FileType.CATALOG,
						catalogName,
						storageOptions,
						catalogFilePath,
						observableOutputKeeper
					),
					null,
					null,
					(indexBuilder, theInput) -> loadOffsetIndexDescriptor(
						catalogFilePath, recordRegistry, VERSIONED_KRYO_FACTORY,
						catalogHeaderRef::set,
						indexBuilder, theInput, it.fileLocation()
					)
				);
				final WalFileReference walRef = catalogHeaderRef.get().walFileReference();
				if (walRef == null) {
					System.out.println(" -> OK, size " + indexRead.getEntries().size());
				} else {
					System.out.println(" -> OK " + walRef.fileIndex() + "/" + walRef.fileLocation() + ", size " + indexRead.getEntries().size());
				}
			} catch (Exception e) {
				System.out.println(" -> ERROR: " + e.getMessage());
			}
		});

		final CatalogHeader catalogHeader = catalogHeaderRef.get();
		try (
			final CatalogWriteAheadLog wal = createWalIfAnyWalFilePresent(
				catalogHeader.version(), catalogName, storageOptions, transactionOptions, new Scheduler(ThreadPoolOptions.transactionThreadPoolBuilder().build()),
				position -> System.out.println("Trim attempted: " + position),
				() -> firstActiveCatalogVersion -> System.out.println("Purge attempted: " + firstActiveCatalogVersion),
				catalogFilePath, catalogKryoPool
			)
		) {
			if (wal != null) {
				final AtomicReference<UUID> lastTransactionId = new AtomicReference<>();
				wal.getCommittedMutationStream(catalogHeader.version())
					.forEach(mutation -> {
						if (mutation instanceof TransactionMutation txMut && !Objects.equals(lastTransactionId.get(), txMut.getTransactionId())) {
							System.out.println("\n\n>>>>  Transaction " + txMut.getTransactionId() + " at " + txMut.getCommitTimestamp() + "\n\n");
							lastTransactionId.set(txMut.getTransactionId());
						}
						System.out.println("  " + mutation);
					});
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

}
