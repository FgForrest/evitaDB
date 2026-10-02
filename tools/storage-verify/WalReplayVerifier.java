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

package io.evitadb.tools.storage;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.catalog.wal.VersionSource;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.wal.CatalogWriteAheadLog;
import io.evitadb.store.wal.WalKryoConfigurer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.stream.Stream;

/**
 * Replays a catalog's write-ahead log through the production reader, {@link CatalogWriteAheadLog}, with full Kryo
 * deserialization of every mutation.
 *
 * Every replay runs twice - through the greedy stream that change data capture catches up with, which ends quietly
 * on a read failure, and through the strict stream that trunk incorporation uses, which throws instead - and from many
 * start versions spread over the log: the version a reader starts from decides where the transactions after it land
 * against the reader's buffer, so a single replay from the first version exercises one alignment only. A replay is
 * complete when it delivers every version from its start to the last version of the log, each transaction with
 * exactly the mutations it declares.
 *
 * **Run it on a copy of the WAL folder.** Opening the log checks the active file and truncates a torn tail.
 *
 * Usage: `WalReplayVerifier <storage prefix> <WAL folder copy> <first WAL index> <start points>`. The storage prefix
 * is the part of the WAL file name before `_<index>.wal`. The process exits with `0` when every replay was complete,
 * `1` when any was not, and `2` on a usage error.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class WalReplayVerifier {

	/**
	 * Outcome of one replay.
	 *
	 * @param start        version the replay started from
	 * @param kind         `greedy` or `strict`
	 * @param transactions transactions delivered
	 * @param mutations    mutations delivered, not counting the leading transaction mutations
	 * @param lastVersion  last version delivered, or -1
	 * @param failure      what ended the replay abnormally, or null
	 */
	private record Replay(
		long start, @Nonnull String kind, long transactions, long mutations, long lastVersion, @Nullable String failure
	) {
	}

	/**
	 * Entry point - see the class documentation for the arguments.
	 *
	 * @param args storage prefix, WAL folder copy, first WAL index, start points
	 */
	public static void main(@Nonnull String[] args) {
		if (args.length != 4) {
			System.err.println(
				"usage: WalReplayVerifier <storage prefix> <WAL folder copy> <first WAL index> <start points>"
			);
			System.exit(2);
		}
		final String storagePrefix = args[0];
		final Path folder = Path.of(args[1]);
		final int firstIndex = Integer.parseInt(args[2]);
		final int startPoints = Integer.parseInt(args[3]);

		final Pool<Kryo> kryoPool = new Pool<>(true, false, 16) {
			@Override
			protected Kryo create() {
				return KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
			}
		};
		final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2);
		final List<Replay> replays = new ArrayList<>(startPoints * 2 + 2);
		int exit = 0;
		try (
			final CatalogWriteAheadLog wal = new CatalogWriteAheadLog(
				0L,
				storagePrefix,
				new LogFileRecordReference(
					index -> CatalogPersistenceService.getWalFileName(storagePrefix, index), firstIndex, null, 0L
				),
				folder,
				kryoPool,
				// the reader needs a decompressor for compressed records; nothing is written with these settings
				new StorageSettings(
					StorageOptions.builder().compress(true).build(),
					TransactionOptions.builder().walFileSizeBytes(Long.MAX_VALUE).build()
				),
				new Scheduler(executor)
			)
		) {
			final long firstVersion = wal.getFirstVersionOf(firstIndex);
			final Replay reference = greedy(wal, firstVersion);
			replays.add(reference);
			final long lastVersion = reference.lastVersion();
			replays.add(strict(wal, firstVersion, lastVersion));
			for (int i = 1; i <= startPoints && lastVersion > firstVersion; i++) {
				final long start = firstVersion + (lastVersion - firstVersion) * i / (startPoints + 1);
				replays.add(greedy(wal, start));
				replays.add(strict(wal, start, lastVersion));
			}

			long incomplete = 0, mutations = 0;
			for (Replay replay : replays) {
				final boolean complete = replay.failure() == null && replay.lastVersion() == lastVersion &&
					replay.transactions() == lastVersion - replay.start() + 1;
				if (!complete) {
					incomplete++;
				}
				mutations += Math.max(0, replay.mutations());
				System.out.printf(
					"%s|%s|start=%d|tx=%d|mutations=%d|last=%d|status=%s|failure=%s%n",
					storagePrefix, replay.kind(), replay.start(), replay.transactions(), replay.mutations(),
					replay.lastVersion(), complete ? "OK" : "INCOMPLETE", replay.failure()
				);
			}
			System.out.printf(
				"# SUMMARY wal %s: versions=%d..%d replays=%d mutationsDeserialized=%d incomplete=%d status=%s%n",
				storagePrefix, firstVersion, lastVersion, replays.size(), mutations, incomplete,
				incomplete == 0 ? "OK" : "FAILED"
			);
			exit = incomplete == 0 ? 0 : 1;
		} catch (Throwable ex) {
			System.out.printf("# SUMMARY wal %s: status=FAILED failure=%s%n", storagePrefix, describe(ex));
			exit = 1;
		} finally {
			executor.shutdownNow();
		}
		System.exit(exit);
	}

	/**
	 * Replays through the greedy stream, which change data capture catches up with.
	 */
	@Nonnull
	private static Replay greedy(@Nonnull CatalogWriteAheadLog wal, long start) {
		try (Stream<CatalogBoundMutation> stream = wal.getCommittedMutationStream(start)) {
			return consume(stream.iterator(), start, "greedy");
		} catch (Throwable ex) {
			return new Replay(start, "greedy", -1, -1, -1, describe(ex));
		}
	}

	/**
	 * Replays through the strict stream, which trunk incorporation uses and which throws instead of ending early.
	 */
	@Nonnull
	private static Replay strict(@Nonnull CatalogWriteAheadLog wal, long start, long last) {
		// the engine chose the bound itself, from what the log holds - a missing version is damage, not a bad argument
		try (
			Stream<CatalogBoundMutation> stream =
				wal.getCommittedLiveMutationStream(start, last, VersionSource.INTERNAL)
		) {
			return consume(stream.iterator(), start, "strict");
		} catch (Throwable ex) {
			return new Replay(start, "strict", -1, -1, -1, describe(ex));
		}
	}

	/**
	 * Drains a stream, checking that versions are contiguous and that every transaction carries exactly the number of
	 * mutations it declares - each one deserialized by Kryo on the way.
	 */
	@Nonnull
	private static Replay consume(@Nonnull Iterator<CatalogBoundMutation> it, long start, @Nonnull String kind) {
		long transactions = 0, mutations = 0, last = -1;
		try {
			while (it.hasNext()) {
				final CatalogBoundMutation mutation = it.next();
				if (!(mutation instanceof TransactionMutation transaction)) {
					return new Replay(
						start, kind, transactions, mutations, last, "a mutation outside a transaction after " + last
					);
				}
				if (last != -1 && transaction.getVersion() != last + 1) {
					return new Replay(
						start, kind, transactions, mutations, last,
						"version " + transaction.getVersion() + " follows version " + last
					);
				}
				last = transaction.getVersion();
				transactions++;
				for (int i = 0; i < transaction.getMutationCount(); i++) {
					if (!it.hasNext() || it.next() instanceof TransactionMutation) {
						return new Replay(
							start, kind, transactions, mutations, last,
							"transaction " + last + " ended after " + i + " of " + transaction.getMutationCount() +
								" mutations"
						);
					}
					mutations++;
				}
			}
			return new Replay(start, kind, transactions, mutations, last, null);
		} catch (Throwable ex) {
			return new Replay(start, kind, transactions, mutations, last, describe(ex));
		}
	}

	/**
	 * Formats an exception with its causes on one line.
	 */
	@Nonnull
	private static String describe(@Nonnull Throwable ex) {
		final StringBuilder sb = new StringBuilder(256)
			.append(ex.getClass().getSimpleName()).append(": ").append(ex.getMessage());
		Throwable cause = ex.getCause();
		for (int depth = 0; cause != null && depth < 5; depth++) {
			sb.append(" <- ").append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
			cause = cause.getCause();
		}
		return sb.toString().replace('\n', ' ');
	}
}
