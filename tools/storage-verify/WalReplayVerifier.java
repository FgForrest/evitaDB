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
import io.evitadb.spi.store.engine.EnginePersistenceService;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.wal.AbstractMutationLog;
import io.evitadb.store.wal.CatalogWriteAheadLog;
import io.evitadb.store.wal.WalKryoConfigurer;
import io.evitadb.utils.FileUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.stream.Stream;

import static io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService.WAL_FILE_SUFFIX;

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
 * Every catalog WAL under the storage root is found and its files are told apart by the engine's own naming -
 * {@link AbstractMutationLog#getIndexFromWalFileName} and {@link CatalogPersistenceService#getWalFileName} - so the
 * tool never restates the file-name format. A `.wal` file whose name the engine would not have written fails the run
 * rather than being skipped. The engine's own log has a different reader and is left to the record verifier.
 *
 * Opening a log checks its active file and truncates a torn tail, so every log is replayed from a copy in the work
 * directory, never in place; the copy is deleted afterwards.
 *
 * Usage: `WalReplayVerifier <storage root> <work dir> <start points>`. The process exits with `0` when every replay
 * was complete, `1` when any was not, and `2` on a usage error.
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
	 * One catalog WAL found under the storage root.
	 *
	 * @param folder        folder holding the log's files
	 * @param storagePrefix the part of the file names that the engine's naming puts before the file index
	 * @param indexes       indexes of the log's files, ascending
	 */
	private record WalLog(@Nonnull Path folder, @Nonnull String storagePrefix, @Nonnull TreeSet<Integer> indexes) {
	}

	/**
	 * Entry point - see the class documentation for the arguments.
	 *
	 * @param args storage root, work directory, start points
	 */
	public static void main(@Nonnull String[] args) throws IOException {
		if (args.length != 3) {
			System.err.println("usage: WalReplayVerifier <storage root> <work dir> <start points>");
			System.exit(2);
		}
		final Path root = Path.of(args[0]);
		final Path work = Path.of(args[1]);
		final int startPoints = Integer.parseInt(args[2]);

		final List<String> unrecognized = new ArrayList<>(4);
		final List<WalLog> logs = findLogs(root, unrecognized);
		boolean ok = true;
		for (String file : unrecognized) {
			System.out.printf("# SUMMARY wal %s: status=FAILED failure=not a WAL file name the engine writes%n", file);
			ok = false;
		}
		for (WalLog log : logs) {
			final Path copy = work.resolve(log.storagePrefix());
			FileUtils.deleteDirectory(copy);
			Files.createDirectories(copy);
			try {
				for (int index : log.indexes()) {
					final String fileName = CatalogPersistenceService.getWalFileName(log.storagePrefix(), index);
					Files.copy(log.folder().resolve(fileName), copy.resolve(fileName));
				}
				ok &= verifyLog(log.storagePrefix(), copy, log.indexes().first(), startPoints);
			} finally {
				FileUtils.deleteDirectory(copy);
			}
		}
		System.exit(ok ? 0 : 1);
	}

	/**
	 * Finds every catalog WAL under the root and groups its files by folder and storage prefix.
	 *
	 * @param root         storage root to search
	 * @param unrecognized collects `.wal` files whose names the engine would not have written
	 * @return the logs, in folder and prefix order
	 */
	@Nonnull
	private static List<WalLog> findLogs(@Nonnull Path root, @Nonnull List<String> unrecognized) throws IOException {
		final List<Path> files;
		try (Stream<Path> walk = Files.walk(root)) {
			files = walk.filter(Files::isRegularFile).toList();
		}
		final Map<Path, WalLog> logs = new TreeMap<>();
		for (Path file : files) {
			final String name = file.getFileName().toString();
			if (!name.endsWith(WAL_FILE_SUFFIX)) {
				continue;
			}
			final int index = indexOf(name);
			if (index >= 0 && name.equals(EnginePersistenceService.getWalFileName(index))) {
				// the engine's own log has a different reader - the record verifier covers it
				continue;
			}
			final String prefix = index >= 0 ? storagePrefixOf(name, index) : null;
			if (prefix == null) {
				unrecognized.add(file.toString());
				continue;
			}
			logs.computeIfAbsent(
				file.getParent().resolve(prefix),
				key -> new WalLog(file.getParent(), prefix, new TreeSet<>())
			).indexes().add(index);
		}
		return new ArrayList<>(logs.values());
	}

	/**
	 * Reads the file index from a WAL file name with the engine's own parser.
	 *
	 * @param name WAL file name
	 * @return the index, or -1 when the name carries none
	 */
	private static int indexOf(@Nonnull String name) {
		try {
			return AbstractMutationLog.getIndexFromWalFileName(name);
		} catch (RuntimeException ex) {
			// the engine's parser refuses a name with no digits before the suffix
			return -1;
		}
	}

	/**
	 * Returns the storage prefix a catalog WAL file name was built from, verified by building the name again.
	 *
	 * @param name  WAL file name
	 * @param index file index read from the name
	 * @return the prefix, or null when the engine's naming would not produce this name
	 */
	@Nullable
	private static String storagePrefixOf(@Nonnull String name, int index) {
		// what the engine's naming appends to a prefix for this index, so the prefix is all that precedes it
		final int prefixLength = name.length() - CatalogPersistenceService.getWalFileName("", index).length();
		if (prefixLength <= 0) {
			return null;
		}
		final String prefix = name.substring(0, prefixLength);
		return CatalogPersistenceService.getWalFileName(prefix, index).equals(name) ? prefix : null;
	}

	/**
	 * Replays one log from many start versions and prints one line per replay and a summary.
	 *
	 * @param storagePrefix storage prefix of the log's files
	 * @param folder        folder holding a copy of the log's files
	 * @param firstIndex    index of the log's oldest file
	 * @param startPoints   start versions to replay from, besides the first one
	 * @return true when every replay was complete
	 */
	private static boolean verifyLog(
		@Nonnull String storagePrefix,
		@Nonnull Path folder,
		int firstIndex,
		int startPoints
	) {
		final Pool<Kryo> kryoPool = new Pool<>(true, false, 16) {
			@Override
			protected Kryo create() {
				return KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
			}
		};
		final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(2);
		final List<Replay> replays = new ArrayList<>(startPoints * 2 + 2);
		boolean verified = false;
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
			verified = incomplete == 0;
		} catch (Throwable ex) {
			System.out.printf("# SUMMARY wal %s: status=FAILED failure=%s%n", storagePrefix, describe(ex));
		} finally {
			executor.shutdownNow();
		}
		return verified;
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
