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


import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.driver.EvitaClient;
import io.evitadb.driver.config.ClientTimeoutOptions;
import io.evitadb.driver.config.ClientTlsOptions;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.wal.CatalogWriteAheadLog;
import io.evitadb.store.wal.WalKryoConfigurer;
import io.evitadb.test.EvitaTestSupport;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Manual replay tool that opens an `EvitaClient` against an externally running evitaDB
 * server and replays missing transactions read from a separate WAL folder on disk.
 *
 * Pre-requisite: an evitaDB server is already running locally on [SERVER_HOST] / [SERVER_PORT]
 * with the [CATALOG_NAME] catalog in the ALIVE state. The test does **not** start a server.
 *
 * The flow:
 *
 * 1. open a read-only session through the client and read the current catalog version,
 * 2. open an external `CatalogWriteAheadLog` over the WAL files in [WAL_SOURCE_BASE_PATH] /
 *    [CATALOG_NAME] (a different folder, presumed to contain newer transactions),
 * 3. iterate the stream of committed mutations starting from the current catalog version,
 * 4. for every transaction whose version is greater than the current catalog version open a
 *    fresh read-write session through the client and re-apply all mutations of that
 *    transaction; the session is closed after each transaction so commits are one-to-one
 *    with the source WAL.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@Disabled("Manual recovery / replay test - run from the IDE only.")
public class WalReplayAgainstLocalServerTest implements EvitaTestSupport {
	/**
	 * Catalog name to be replayed.
	 */
	private static final String CATALOG_NAME = "hugoboss";
	/**
	 * Host of the externally running evitaDB server.
	 */
	private static final String SERVER_HOST = "localhost";
	/**
	 * gRPC port of the externally running evitaDB server.
	 */
	private static final int SERVER_PORT = 5555;
	/**
	 * System API port of the externally running evitaDB server.
	 */
	private static final int SERVER_SYSTEM_PORT = 5555;
	/**
	 * Storage directory containing the (newer) WAL files to be replayed. The `CATALOG_NAME`
	 * subdirectory under it is expected to contain `<catalog>_<index>.wal` files.
	 */
	private static final Path WAL_SOURCE_BASE_PATH = Path.of("/www/oss/evita/release_2026-1/data_wals/");

	@Test
	void replayMissingWalTransactionsAgainstLocalServer() throws IOException {
		final Path walSourceCatalogPath = WAL_SOURCE_BASE_PATH.resolve(CATALOG_NAME);

		try (
			final EvitaClient evitaClient = new EvitaClient(
				EvitaClientConfiguration.builder()
					.host(SERVER_HOST)
					.port(SERVER_PORT)
					.systemApiPort(SERVER_SYSTEM_PORT)
					.tls(
						ClientTlsOptions.builder()
							.mtlsEnabled(false)
							.build()
					)
					.timeouts(
						ClientTimeoutOptions.builder()
							.timeout(10, TimeUnit.MINUTES)
							.streamingTimeout(10, TimeUnit.MINUTES)
							.build()
					)
					.build()
			)
		) {
			// 1. Read the current catalog version from a read-only session.
			final long currentCatalogVersion = evitaClient.queryCatalog(
				CATALOG_NAME,
				EvitaSessionContract::getCatalogVersion
			);
			log.info(
				"Current catalog version of `{}` on local server: {}.",
				CATALOG_NAME, currentCatalogVersion
			);

			// 2. Open the source WAL folder and replay every transaction with version > current.
			final StorageOptions walStorageOptions = StorageOptions.builder()
				.storageDirectory(WAL_SOURCE_BASE_PATH)
				.build();
			final TransactionOptions transactionOptions = TransactionOptions.builder().build();
			final Pool<Kryo> walKryoPool = new Pool<>(true, false, 16) {
				@Override
				protected Kryo create() {
					return KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
				}
			};

			try (
				final CatalogWriteAheadLog wal = new CatalogWriteAheadLog(
					1,
					CATALOG_NAME,
					index -> CatalogPersistenceService.getWalFileName(CATALOG_NAME, index),
					walSourceCatalogPath,
					walKryoPool,
					walStorageOptions, transactionOptions,
					new Scheduler(ThreadPoolOptions.transactionThreadPoolBuilder().build()),
					0
				)
			) {
				replayMutations(evitaClient, wal, currentCatalogVersion);
			}
		}
	}

	/**
	 * Reads the WAL stream starting from `currentCatalogVersion`, groups mutations by their
	 * leading `TransactionMutation`, and replays each transaction (whose version is strictly
	 * greater than `currentCatalogVersion`) as a single read-write session against the
	 * supplied client.
	 *
	 * @param evitaClient            the client connected to the local server
	 * @param wal                    the source WAL to be replayed
	 * @param currentCatalogVersion  the highest catalog version already present locally
	 */
	private static void replayMutations(
		final EvitaClient evitaClient,
		final CatalogWriteAheadLog wal,
		final long currentCatalogVersion
	) {
		// passing currentCatalogVersion places the cursor at the first transaction whose
		// version is >= currentCatalogVersion; everything <= currentCatalogVersion is skipped
		// at the per-transaction check below.
		try (final Stream<CatalogBoundMutation> mutationStream = wal.getCommittedMutationStream(currentCatalogVersion)) {
			final Iterator<CatalogBoundMutation> iterator = mutationStream.iterator();
			int replayedTransactions = 0;
			int skippedTransactions = 0;
			long replayedMutationCount = 0;
			long lastReplayedVersion = currentCatalogVersion;

			while (iterator.hasNext()) {
				final CatalogBoundMutation leading = iterator.next();
				if (!(leading instanceof TransactionMutation txMutation)) {
					throw new IllegalStateException(
						"Expected TransactionMutation at the head of the WAL slice, found " +
							leading.getClass().getName()
					);
				}

				final List<CatalogBoundMutation> txMutations = new ArrayList<>(txMutation.getMutationCount());
				for (int i = 0; i < txMutation.getMutationCount(); i++) {
					if (!iterator.hasNext()) {
						throw new IllegalStateException(
							"WAL ends in the middle of transaction `" + txMutation.getTransactionId() +
								"` at version " + txMutation.getVersion() + " (read " + i +
								" of " + txMutation.getMutationCount() + " mutations)."
						);
					}
					txMutations.add(iterator.next());
				}

				if (txMutation.getVersion() <= currentCatalogVersion) {
					skippedTransactions++;
					continue;
				}

				log.info(
					">>>> Replaying transaction `{}` (source version {}) at {} - {} mutations",
					txMutation.getTransactionId(),
					txMutation.getVersion(),
					txMutation.getCommitTimestamp(),
					txMutation.getMutationCount()
				);

				final Consumer<EvitaSessionContract> updater =
					session -> applyTransactionMutations(session, txMutations);
				evitaClient.updateCatalog(
					CATALOG_NAME,
					updater,
					CommitBehavior.WAIT_FOR_WAL_PERSISTENCE,
					SessionFlags.READ_WRITE
				);

				replayedTransactions++;
				replayedMutationCount += txMutations.size();
				lastReplayedVersion = txMutation.getVersion();
			}

			log.info(
				"Replay finished. Replayed {} transactions ({} mutations); skipped {} (already applied). " +
					"Last replayed source version: {}.",
				replayedTransactions, replayedMutationCount, skippedTransactions, lastReplayedVersion
			);
		}
	}

	/**
	 * Applies the mutations of a single source transaction inside the given client session.
	 * Each mutation is dispatched to the appropriate session API based on its concrete type.
	 *
	 * @param session      the read-write client session bound to the target catalog
	 * @param txMutations  the mutations of one source transaction, in original order
	 */
	private static void applyTransactionMutations(
		final EvitaSessionContract session,
		final List<CatalogBoundMutation> txMutations
	) {
		for (final CatalogBoundMutation mutation : txMutations) {
			if (mutation instanceof EntityMutation entityMutation) {
				session.applyMutation(entityMutation);
			} else if (mutation instanceof LocalCatalogSchemaMutation schemaMutation) {
				session.updateCatalogSchema(schemaMutation);
			} else {
				throw new IllegalStateException(
					"Unsupported mutation type encountered during replay: " + mutation.getClass().getName()
				);
			}
		}
	}

}
