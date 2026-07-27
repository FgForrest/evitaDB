/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.performance.walreplay.state;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.CatalogState;
import io.evitadb.api.CommitProgress;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.SessionTraits.SessionFlags;
import io.evitadb.api.TransactionContract.CommitBehavior;
import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.configuration.TransactionOptions;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.mutation.CatalogBoundMutation;
import io.evitadb.api.requestResponse.mutation.infrastructure.TransactionMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.core.Evita;
import io.evitadb.core.executor.Scheduler;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.spi.store.catalog.persistence.CatalogPersistenceService;
import io.evitadb.spi.store.engine.exception.WriteAheadLogCorruptedException;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.settings.StorageSettings;
import io.evitadb.store.shared.kryo.KryoFactory;
import io.evitadb.store.wal.AbstractMutationLog;
import io.evitadb.store.wal.CatalogWriteAheadLog;
import io.evitadb.store.wal.WalKryoConfigurer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * JMH fixture that replays real production transactions (captured in Write-Ahead Logs extracted
 * from a live backup) against an embedded evitaDB instance booted from an earlier snapshot of the
 * very same catalog. The catalog itself - name, schema, dataset - is not baked in: this fixture
 * is meant to be pointed at whatever production export is under investigation at the time, not at
 * one specific dataset.
 *
 * The fixture never touches the original backup archive. It expects the data to be prepared
 * upfront (outside JMH, since unzipping multi-gigabyte archives on every fork would be wasteful)
 * and passed in via system properties:
 *
 * - `{@value #CATALOG_NAME_PROPERTY}` - the name of the catalog under investigation. Both the
 *   pristine snapshot directory and every WAL source directory below are expected to contain a
 *   subfolder with exactly this name.
 * - `{@value #PRISTINE_DATA_DIR_PROPERTY}` - directory containing a `<catalogName>/` subfolder
 *   with the *older* snapshot (catalog, collection files, boot file, and its own trailing WAL).
 *   This is copied fresh into a working directory on every trial so the source snapshot is never
 *   mutated.
 * - `{@value #WAL_SOURCE_DIR_PROPERTY}` - one or more (comma separated) directories, each
 *   containing a `<catalogName>/` subfolder with WAL segments (`<catalogName>_26.wal`,
 *   `<catalogName>_27.wal`, ...) read from backups taken later. They are consumed left to right
 *   and each is opened read-only via a standalone {@link CatalogWriteAheadLog} completely
 *   independent of the live catalog's own storage folder. Sources may freely overlap - every
 *   transaction whose version was already applied is skipped - which is what makes it possible to
 *   chain the WAL slices of two consecutive backups into one continuous replay stream.
 *
 * At {@link Setup @Setup(Level.Trial)} time the fixture:
 *
 * 1. copies the pristine snapshot into a fresh working directory and boots an embedded
 *    {@link Evita} instance against it - this replays the snapshot's own trailing WAL through the
 *    normal boot-time recovery path, bringing the catalog to the state it was in when the backup
 *    was taken;
 * 2. reads {@link EvitaSessionContract#getCatalogVersion()} to determine the last transaction
 *    already incorporated into that snapshot.
 *
 * The actual benchmarked operation ({@link #replayPendingTransactions()}) then re-applies every
 * subsequent transaction found in the source WALs as a brand new transaction against the live
 * catalog, going through the exact same session/commit pipeline a real client would use
 * ({@link EvitaSessionContract#applyMutation(EntityMutation)} /
 * {@link EvitaSessionContract#updateCatalogSchema(LocalCatalogSchemaMutation...)} followed by a
 * commit) - this is what makes the benchmark measure real transactional-processing cost rather
 * than the internal WAL-recovery shortcut used at boot.
 *
 * Commits are issued through {@link io.evitadb.api.EvitaContract#updateCatalogAsync} so that each
 * commit-pipeline stage ({@link CommitProgress#onConflictResolved()},
 * {@link CommitProgress#onWalAppended()}, {@link CommitProgress#onChangesVisible()}) can be
 * timestamped individually. The replaying thread blocks on WAL persistence - matching the default
 * behaviour of a real client - while the visibility stage is awaited asynchronously, so the
 * measured throughput is not serialised behind trunk incorporation. See {@link #getStatistics()}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@State(Scope.Benchmark)
public class WalReplayState {

	/**
	 * System property naming the catalog under investigation. Both the pristine data directory and
	 * every WAL source directory must contain a subfolder with exactly this name.
	 */
	public static final String CATALOG_NAME_PROPERTY = "evita.replay.catalogName";
	/**
	 * System property pointing to the directory holding the older snapshot's `&lt;catalogName&gt;/`
	 * subfolder.
	 */
	public static final String PRISTINE_DATA_DIR_PROPERTY = "evita.replay.pristineDataDir";
	/**
	 * System property pointing to one or more (comma separated) directories holding a
	 * `&lt;catalogName&gt;/` subfolder with the newer WAL segments. Consumed left to right;
	 * overlaps are skipped.
	 */
	public static final String WAL_SOURCE_DIR_PROPERTY = "evita.replay.walSourceDir";
	/**
	 * System property overriding the working directory the pristine snapshot is copied into. Defaults
	 * to a fresh subdirectory of the system temp directory.
	 */
	public static final String WORK_DIR_PROPERTY = "evita.replay.workDir";
	/**
	 * System property overriding {@link TransactionOptions#flushFrequencyInMillis()}.
	 */
	public static final String FLUSH_FREQUENCY_PROPERTY = "evita.replay.flushFrequencyInMillis";
	/**
	 * System property overriding {@link StorageOptions#syncWrites()}.
	 */
	public static final String SYNC_WRITES_PROPERTY = "evita.replay.syncWrites";
	/**
	 * System property overriding the transaction pipeline's internal queue size (see
	 * {@link ThreadPoolOptions#queueSize()} on {@link ServerOptions#transactionThreadPool()}) - must
	 * comfortably exceed the number of transactions that may be in flight at once, see {@link #setUp()}.
	 */
	public static final String TRANSACTION_QUEUE_SIZE_PROPERTY = "evita.replay.transactionQueueSize";
	/**
	 * System property capping the number of transactions replayed in one trial (`0` = no cap). Useful
	 * to keep an iteration cycle short while profiling.
	 */
	public static final String MAX_TRANSACTIONS_PROPERTY = "evita.replay.maxTransactions";
	/**
	 * System property capping how many commits may be waiting for their visibility (trunk
	 * incorporation) stage at once. Beyond this the replaying thread blocks on the oldest one, which
	 * bounds heap growth and stops the replay from outrunning the pipeline unboundedly.
	 */
	public static final String MAX_PENDING_VISIBILITY_PROPERTY = "evita.replay.maxPendingVisibility";
	/**
	 * System property overriding {@link StorageOptions#minimalActiveRecordShare()} - the share of
	 * active records below which a storage file is compacted. Lowering it defers compaction (less
	 * write amplification mid-replay), raising it compacts more eagerly.
	 */
	public static final String MINIMAL_ACTIVE_RECORD_SHARE_PROPERTY = "evita.replay.minimalActiveRecordShare";
	/**
	 * System property switching the replaying thread to block on the visibility stage of every
	 * commit (`WAIT_FOR_CHANGES_VISIBLE` semantics, empty pipeline) instead of the default
	 * WAL-persistence stage. Measures the true per-transaction trunk-incorporation floor without
	 * queueing inflation.
	 */
	public static final String WAIT_FOR_VISIBILITY_PROPERTY = "evita.replay.waitForVisibility";
	/**
	 * System property naming a CSV file to receive one row per replayed transaction with its
	 * mutation count and every stage latency. Enables correlating latency with transaction size;
	 * unset (default) records nothing.
	 */
	public static final String PER_TX_CSV_PROPERTY = "evita.replay.perTxCsv";

	/**
	 * Progress is logged every this many replayed transactions.
	 */
	private static final int PROGRESS_LOG_INTERVAL = 250;

	private String catalogName;
	private Path workDataDir;
	private List<Path> walSourceDirs;
	private Evita evita;
	private int maxTransactions;
	private int maxPendingVisibility;
	private boolean waitForVisibility;
	@Nullable private Path perTxCsv;
	@Getter
	private long startCatalogVersion;
	@Getter
	private long replayedTransactions;
	@Getter
	private long skippedTransactions;
	@Getter
	private long failedTransactions;
	@Getter
	private long replayedMutations;
	@Getter
	private long lastReplayedVersion;
	@Getter
	private ReplayStatistics statistics;
	/**
	 * Census of distinct failure signatures encountered while replaying - a WAL slice that starts
	 * beyond the base snapshot's tip (a purged-segment gap) surfaces here rather than aborting the
	 * run, which is exactly the atomic-upsert rollback path the replay is meant to exercise.
	 */
	private final Map<String, int[]> failureSignatures = new LinkedHashMap<>();

	@Nonnull
	private static String requiredStringProperty(@Nonnull String propertyName) {
		final String value = System.getProperty(propertyName);
		if (value == null || value.isBlank()) {
			throw new GenericEvitaInternalError(
				"Required system property `" + propertyName + "` is not set.",
				"Required system property `" + propertyName + "` is not set."
			);
		}
		return value;
	}

	@Nonnull
	private static Path requiredPathProperty(@Nonnull String propertyName, @Nonnull String catalogName) {
		final String value = System.getProperty(propertyName);
		if (value == null || value.isBlank()) {
			throw new GenericEvitaInternalError(
				"Required system property `" + propertyName + "` is not set. It must point to a directory " +
					"containing a `" + catalogName + "/` subfolder - see " + WalReplayState.class.getSimpleName() +
					" JavaDoc for details.",
				"Required system property `" + propertyName + "` is not set."
			);
		}
		return Path.of(value);
	}

	@Setup(Level.Trial)
	public void setUp() throws IOException {
		this.catalogName = requiredStringProperty(CATALOG_NAME_PROPERTY);
		final Path pristineDataDir = requiredPathProperty(PRISTINE_DATA_DIR_PROPERTY, this.catalogName);
		final String walSourceProperty = System.getProperty(WAL_SOURCE_DIR_PROPERTY);
		if (walSourceProperty == null || walSourceProperty.isBlank()) {
			throw new GenericEvitaInternalError(
				"Required system property `" + WAL_SOURCE_DIR_PROPERTY + "` is not set.",
				"Required system property `" + WAL_SOURCE_DIR_PROPERTY + "` is not set."
			);
		}
		this.walSourceDirs = new ArrayList<>(4);
		for (final String rawDir : walSourceProperty.split(",")) {
			final String trimmed = rawDir.trim();
			if (!trimmed.isEmpty()) {
				this.walSourceDirs.add(Path.of(trimmed));
			}
		}
		this.maxTransactions = Integer.parseInt(System.getProperty(MAX_TRANSACTIONS_PROPERTY, "0"));
		this.maxPendingVisibility = Integer.parseInt(System.getProperty(MAX_PENDING_VISIBILITY_PROPERTY, "512"));
		this.waitForVisibility = Boolean.parseBoolean(System.getProperty(WAIT_FOR_VISIBILITY_PROPERTY, "false"));
		this.perTxCsv = System.getProperty(PER_TX_CSV_PROPERTY) == null ?
			null : Path.of(System.getProperty(PER_TX_CSV_PROPERTY));
		this.workDataDir = System.getProperty(WORK_DIR_PROPERTY) == null ?
			Files.createTempDirectory("evita-wal-replay-work") :
			Path.of(System.getProperty(WORK_DIR_PROPERTY));

		log.info("Copying pristine snapshot from `{}` to working directory `{}`...", pristineDataDir, this.workDataDir);
		final long copyStart = System.nanoTime();
		FileUtils.deleteDirectory(this.workDataDir.toFile());
		FileUtils.copyDirectory(
			pristineDataDir.resolve(this.catalogName).toFile(),
			this.workDataDir.resolve(this.catalogName).toFile()
		);
		log.info("Copy finished in {} ms.", (System.nanoTime() - copyStart) / 1_000_000);

		final long flushFrequencyInMillis = Long.parseLong(
			System.getProperty(FLUSH_FREQUENCY_PROPERTY, String.valueOf(TransactionOptions.DEFAULT_FLUSH_FREQUENCY))
		);
		final boolean syncWrites = Boolean.parseBoolean(
			System.getProperty(SYNC_WRITES_PROPERTY, String.valueOf(StorageOptions.DEFAULT_SYNC_WRITES))
		);
		final double minimalActiveRecordShare = Double.parseDouble(
			System.getProperty(
				MINIMAL_ACTIVE_RECORD_SHARE_PROPERTY,
				String.valueOf(StorageOptions.DEFAULT_MINIMAL_ACTIVE_RECORD_SHARE)
			)
		);
		log.info(
			"Booting embedded evitaDB for catalog `{}` (flushFrequencyInMillis={}, syncWrites={}, " +
				"minimalActiveRecordShare={}, waitForVisibility={})...",
			this.catalogName, flushFrequencyInMillis, syncWrites, minimalActiveRecordShare, this.waitForVisibility
		);

		final long bootStart = System.nanoTime();
		this.evita = new Evita(
			EvitaConfiguration.builder()
				.server(
					ServerOptions.builder()
						.queryTimeoutInMilliseconds(600_000)
						.transactionTimeoutInMilliseconds(600_000)
						.closeSessionsAfterSecondsOfInactivity(Integer.MAX_VALUE)
						// the default queue size (100, see ThreadPoolOptions.DEFAULT_TRANSACTION_QUEUE_SIZE) is
						// far smaller than a real WAL replay batch - firing commits back-to-back overflows the
						// conflict-resolution -> trunk-incorporation pipeline's SubmissionPublisher and the
						// replay fails with RejectedExecutionException
						.transactionThreadPool(
							ThreadPoolOptions.transactionThreadPoolBuilder()
								.queueSize(
									Integer.parseInt(System.getProperty(TRANSACTION_QUEUE_SIZE_PROPERTY, "5000"))
								)
								.build()
						)
						.build()
				)
				.storage(
					StorageOptions.builder()
						.storageDirectory(this.workDataDir)
						.syncWrites(syncWrites)
						.minimalActiveRecordShare(minimalActiveRecordShare)
						.build()
				)
				.transaction(
					TransactionOptions.builder()
						.flushFrequencyInMillis(flushFrequencyInMillis)
						.build()
				)
				.cache(CacheOptions.builder().build())
				.build()
		);
		log.info("Boot finished in {} ms, waiting for catalog activation to settle...", (System.nanoTime() - bootStart) / 1_000_000);
		awaitCatalogAlive();
		final long bootMillis = (System.nanoTime() - bootStart) / 1_000_000;
		log.info("Catalog `{}` fully activated in {} ms (boot-time WAL recovery included).", this.catalogName, bootMillis);

		this.startCatalogVersion = this.evita.queryCatalog(this.catalogName, EvitaSessionContract::getCatalogVersion);
		this.lastReplayedVersion = this.startCatalogVersion;
		log.info("Catalog `{}` booted at version {}.", this.catalogName, this.startCatalogVersion);
	}

	/**
	 * Opens one of the configured WAL source folders read-only. The returned handle owns a
	 * {@link Scheduler} that is *not* {@link AutoCloseable} and is not owned by the log itself, so it
	 * is closed together with the log by {@link WalSource#close()} - otherwise its non-daemon threads
	 * would keep the JVM alive forever.
	 */
	@Nonnull
	private static WalSource openWalSource(@Nonnull Path walSourceBaseDir, @Nonnull String catalogName) throws IOException {
		final StorageSettings walStorageSettings = new StorageSettings(
			StorageOptions.builder().storageDirectory(walSourceBaseDir).build(),
			TransactionOptions.builder().build()
		);
		final Pool<Kryo> walKryoPool = new Pool<>(true, false, 16) {
			@Override
			protected Kryo create() {
				return KryoFactory.createKryo(WalKryoConfigurer.INSTANCE);
			}
		};
		final Path walSourceCatalogPath = walSourceBaseDir.resolve(catalogName);
		final int lastWalFileIndex = resolveLastWalFileIndex(walSourceCatalogPath);
		log.info("Opening source WAL in `{}`, last file index {}.", walSourceCatalogPath, lastWalFileIndex);
		final Scheduler scheduler = new Scheduler(ThreadPoolOptions.transactionThreadPoolBuilder().build());
		final CatalogWriteAheadLog wal = new CatalogWriteAheadLog(
			1,
			catalogName,
			new LogFileRecordReference(
				index -> CatalogPersistenceService.getWalFileName(catalogName, index),
				lastWalFileIndex,
				null,
				0L
			),
			walSourceCatalogPath,
			walKryoPool,
			walStorageSettings,
			scheduler
		);
		return new WalSource(wal, scheduler);
	}

	/**
	 * {@link LogFileRecordReference} must be seeded with the index of the *last* (highest-numbered)
	 * WAL segment file actually present in the source folder - {@link AbstractMutationLog} asserts
	 * this on construction and throws {@link WriteAheadLogCorruptedException} otherwise. The one-arg
	 * {@link LogFileRecordReference} constructor defaults to index 0, which only happens to work for
	 * a source WAL that never rotated past its first segment.
	 */
	private static int resolveLastWalFileIndex(@Nonnull Path walSourceCatalogPath) throws IOException {
		try (final Stream<Path> files = Files.list(walSourceCatalogPath)) {
			return files
				.map(path -> path.getFileName().toString())
				.filter(fileName -> fileName.endsWith(".wal"))
				.mapToInt(AbstractMutationLog::getIndexFromWalFileName)
				.max()
				.orElseThrow(() -> new GenericEvitaInternalError(
					"No `*.wal` files found in source WAL directory `" + walSourceCatalogPath + "`.",
					"No `*.wal` files found in source WAL directory."
				));
		}
	}

	/**
	 * Boot-time WAL catch-up (any transactions written to the snapshot's own trailing WAL after
	 * its last catalog-file flush) runs on a background thread - the constructor of {@link Evita}
	 * returns while the catalog is still in the transitional {@link CatalogState#BEING_ACTIVATED}
	 * state. Querying the catalog before this settles throws {@code CatalogTransitioningException},
	 * so this polls until the catalog reaches {@link CatalogState#ALIVE}.
	 */
	private void awaitCatalogAlive() throws IOException {
		final long deadlineNanos = System.nanoTime() + Duration.ofMinutes(30).toNanos();
		while (true) {
			final CatalogState state = this.evita.getCatalogState(this.catalogName)
				.orElseThrow(() -> new GenericEvitaInternalError(
					"Catalog `" + this.catalogName + "` not found in the freshly booted Evita instance.",
					"Catalog not found in the freshly booted Evita instance."
				));
			if (state == CatalogState.ALIVE) {
				return;
			} else if (!state.isTransitional()) {
				throw new GenericEvitaInternalError(
					"Catalog `" + this.catalogName + "` ended up in unexpected non-transitional state `" + state +
						"` after boot instead of `ALIVE`.",
					"Catalog ended up in an unexpected non-transitional state after boot."
				);
			} else if (System.nanoTime() > deadlineNanos) {
				throw new GenericEvitaInternalError(
					"Timed out waiting for catalog `" + this.catalogName + "` to become `ALIVE` (still `" + state + "`).",
					"Timed out waiting for catalog to become `ALIVE`."
				);
			}
			try {
				Thread.sleep(200);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while waiting for catalog activation.", e);
			}
		}
	}

	/**
	 * Replays every transaction found in the configured source WALs whose version is strictly
	 * greater than the highest version already applied, as a brand new transaction against the live
	 * catalog.
	 *
	 * Each commit is issued asynchronously so its pipeline stages can be timestamped separately. The
	 * replaying thread then blocks on {@link CommitProgress#onWalAppended()} - what a real client
	 * using the default `WAIT_FOR_LOG_PERSISTENCE` behaviour waits for - while the visibility stage
	 * (trunk incorporation) is collected and awaited asynchronously, bounded by
	 * {@value #MAX_PENDING_VISIBILITY_PROPERTY}. The measured window ends only once every
	 * transaction's changes are visible, so index propagation is fully accounted for.
	 *
	 * @return the number of transactions replayed
	 */
	public long replayPendingTransactions() throws IOException {
		this.replayedTransactions = 0;
		this.skippedTransactions = 0;
		this.failedTransactions = 0;
		this.replayedMutations = 0;
		this.failureSignatures.clear();

		final LatencySamples applyLatency = new LatencySamples();
		final LatencySamples conflictLatency = new LatencySamples();
		final LatencySamples walLatency = new LatencySamples();
		final LatencySamples visibleLatency = new LatencySamples();
		final LatencySamples endToEndLatency = new LatencySamples();
		final List<CompletableFuture<?>> pendingVisibility = new ArrayList<>(this.maxPendingVisibility);
		final List<PerTxSample> perTxSamples = this.perTxCsv == null ? null : new ArrayList<>(2048);

		final long replayStart = System.nanoTime();
		for (final Path walSourceDir : this.walSourceDirs) {
			if (reachedTransactionCap()) {
				break;
			}
			final WalSource walSource = openWalSource(walSourceDir, this.catalogName);
			try (final Stream<CatalogBoundMutation> mutationStream =
					 walSource.wal().getCommittedMutationStream(this.lastReplayedVersion)) {
				final Iterator<CatalogBoundMutation> iterator = mutationStream.iterator();
				while (iterator.hasNext()) {
					final CatalogBoundMutation leading = iterator.next();
					if (!(leading instanceof TransactionMutation txMutation)) {
						throw new GenericEvitaInternalError(
							"Expected TransactionMutation at the head of the WAL slice, found " + leading.getClass().getName(),
							"Expected TransactionMutation at the head of the WAL slice."
						);
					}

					final List<CatalogBoundMutation> txMutations = new ArrayList<>(txMutation.getMutationCount());
					for (int i = 0; i < txMutation.getMutationCount(); i++) {
						if (!iterator.hasNext()) {
							throw new GenericEvitaInternalError(
								"WAL ends in the middle of transaction `" + txMutation.getTransactionId() +
									"` at version " + txMutation.getVersion() + " (read " + i + " of " +
									txMutation.getMutationCount() + " mutations).",
								"WAL ends in the middle of a transaction."
							);
						}
						txMutations.add(iterator.next());
					}

					if (txMutation.getVersion() <= this.lastReplayedVersion) {
						this.skippedTransactions++;
						continue;
					}

					replayOneTransaction(
						txMutation.getVersion(), txMutations, applyLatency, conflictLatency,
						walLatency, visibleLatency, endToEndLatency, pendingVisibility, perTxSamples
					);
					this.lastReplayedVersion = txMutation.getVersion();

					if (this.replayedTransactions % PROGRESS_LOG_INTERVAL == 0) {
						final long elapsedMillis = (System.nanoTime() - replayStart) / 1_000_000;
						log.info(
							"... replayed {} transactions ({} mutations) in {} ms - {} tx/s, source version {}.",
							this.replayedTransactions, this.replayedMutations, elapsedMillis,
							elapsedMillis == 0 ? 0 : (this.replayedTransactions * 1000L) / elapsedMillis,
							this.lastReplayedVersion
						);
					}
					if (reachedTransactionCap()) {
						break;
					}
				}
			} finally {
				walSource.close();
			}
		}

		// the replay is only really finished once every commit's changes are visible - draining the
		// backlog here keeps trunk incorporation inside the measured window
		for (final CompletableFuture<?> pending : pendingVisibility) {
			pending.join();
		}
		final long replayNanos = System.nanoTime() - replayStart;
		if (perTxSamples != null) {
			writePerTxCsv(perTxSamples);
		}

		this.statistics = new ReplayStatistics(
			this.replayedTransactions, this.replayedMutations, this.skippedTransactions,
			this.failedTransactions, replayNanos,
			applyLatency.summarize(), conflictLatency.summarize(), walLatency.summarize(),
			visibleLatency.summarize(), endToEndLatency.summarize()
		);
		return this.replayedTransactions;
	}

	/**
	 * @return true when a transaction cap was configured and has been reached
	 */
	private boolean reachedTransactionCap() {
		return this.maxTransactions > 0 && this.replayedTransactions >= this.maxTransactions;
	}

	/**
	 * Issues a single transaction asynchronously, timestamping every commit-pipeline stage, and
	 * blocks until the WAL-persistence stage confirms. A per-transaction failure (for instance an
	 * entity the base snapshot never saw, because an older WAL segment had already been purged) is
	 * recorded in the failure census and the replay continues with the next transaction - which is
	 * precisely the atomic-rollback path such a gap is meant to exercise.
	 */
	private void replayOneTransaction(
		long catalogVersion,
		@Nonnull List<CatalogBoundMutation> txMutations,
		@Nonnull LatencySamples applyLatency,
		@Nonnull LatencySamples conflictLatency,
		@Nonnull LatencySamples walLatency,
		@Nonnull LatencySamples visibleLatency,
		@Nonnull LatencySamples endToEndLatency,
		@Nonnull List<CompletableFuture<?>> pendingVisibility,
		@Nullable List<PerTxSample> perTxSamples
	) {
		final long issuedNanos = System.nanoTime();
		// index 0 holds the moment the session lambda finished applying mutations, i.e. the moment the
		// commit itself is handed over to the pipeline - every stage latency is measured from there
		final long[] commitHandoverNanos = new long[1];
		final PerTxSample sample = perTxSamples == null ?
			null : new PerTxSample(catalogVersion, txMutations.size());
		try {
			final CommitProgress commitProgress = this.evita.updateCatalogAsync(
				this.catalogName,
				session -> {
					applyTransactionMutations(session, txMutations);
					commitHandoverNanos[0] = System.nanoTime();
				},
				CommitBehavior.WAIT_FOR_CHANGES_VISIBLE,
				SessionFlags.READ_WRITE
			);

			final CompletableFuture<?> conflictStage = commitProgress.onConflictResolved()
				.toCompletableFuture()
				.thenRun(() -> {
					final long conflictNanos = System.nanoTime() - commitHandoverNanos[0];
					conflictLatency.add(conflictNanos);
					if (sample != null) {
						sample.conflictNanos = conflictNanos;
					}
				});
			final CompletableFuture<?> visibleStage = commitProgress.onChangesVisible()
				.toCompletableFuture()
				.thenRun(() -> {
					final long now = System.nanoTime();
					visibleLatency.add(now - commitHandoverNanos[0]);
					endToEndLatency.add(now - issuedNanos);
					if (sample != null) {
						sample.visibleNanos = now - commitHandoverNanos[0];
						sample.endToEndNanos = now - issuedNanos;
					}
				});

			// a real client using the default WAIT_FOR_LOG_PERSISTENCE behaviour blocks exactly here
			commitProgress.onWalAppended().toCompletableFuture().join();
			final long walNanos = System.nanoTime() - commitHandoverNanos[0];
			walLatency.add(walNanos);
			applyLatency.add(commitHandoverNanos[0] - issuedNanos);
			conflictStage.join();

			this.replayedTransactions++;
			this.replayedMutations += txMutations.size();
			if (sample != null) {
				sample.applyNanos = commitHandoverNanos[0] - issuedNanos;
				sample.walNanos = walNanos;
				perTxSamples.add(sample);
			}

			if (this.waitForVisibility) {
				// synchronous-visibility mode: the replaying thread waits for trunk incorporation of
				// every commit before issuing the next one, so the visibility latency measured is the
				// true empty-pipeline per-transaction floor, not a queueing artifact
				visibleStage.join();
				return;
			}
			pendingVisibility.add(visibleStage);
			if (pendingVisibility.size() >= this.maxPendingVisibility) {
				// bound the backlog: wait for the oldest half so heap growth stays flat and the replay
				// cannot outrun trunk incorporation without limit
				final int drainUpTo = pendingVisibility.size() / 2;
				for (int i = 0; i < drainUpTo; i++) {
					pendingVisibility.get(i).join();
				}
				pendingVisibility.subList(0, drainUpTo).clear();
			}
		} catch (Exception ex) {
			this.failedTransactions++;
			final String signature = signature(ex);
			this.failureSignatures.computeIfAbsent(signature, key -> new int[1])[0]++;
		}
	}

	/**
	 * Writes the per-transaction latency samples collected during the replay into the CSV file
	 * named by {@value #PER_TX_CSV_PROPERTY}, one row per successfully replayed transaction. All
	 * asynchronously-filled fields are safely visible here because every visibility future has
	 * been joined before this method runs.
	 */
	private void writePerTxCsv(@Nonnull List<PerTxSample> perTxSamples) throws IOException {
		final StringBuilder csv = new StringBuilder(perTxSamples.size() * 64 + 128);
		csv.append("version,mutations,apply_ms,conflict_ms,wal_ms,visible_ms,end_to_end_ms\n");
		for (final PerTxSample sample : perTxSamples) {
			csv.append(sample.version).append(',')
				.append(sample.mutationCount).append(',')
				.append(String.format("%.3f", sample.applyNanos / 1_000_000.0)).append(',')
				.append(String.format("%.3f", sample.conflictNanos / 1_000_000.0)).append(',')
				.append(String.format("%.3f", sample.walNanos / 1_000_000.0)).append(',')
				.append(String.format("%.3f", sample.visibleNanos / 1_000_000.0)).append(',')
				.append(String.format("%.3f", sample.endToEndNanos / 1_000_000.0)).append('\n');
		}
		Files.write(this.perTxCsv, csv.toString().getBytes(StandardCharsets.UTF_8));
		log.info("Per-transaction latency CSV with {} rows written to `{}`.", perTxSamples.size(), this.perTxCsv);
	}

	/**
	 * One successfully replayed transaction's identity, size and stage latencies, destined for the
	 * per-transaction CSV. The visibility fields are written by pipeline callback threads and read
	 * by the replay thread only after the corresponding future is joined, hence volatile.
	 */
	private static final class PerTxSample {
		private final long version;
		private final int mutationCount;
		private long applyNanos;
		private long walNanos;
		private volatile long conflictNanos;
		private volatile long visibleNanos;
		private volatile long endToEndNanos;

		private PerTxSample(long version, int mutationCount) {
			this.version = version;
			this.mutationCount = mutationCount;
		}
	}

	/**
	 * Applies the mutations of a single source transaction inside the given session, dispatching
	 * each mutation to the appropriate session API based on its concrete type - mirrors exactly
	 * what a real client sends when replaying captured traffic.
	 */
	private static void applyTransactionMutations(
		@Nonnull EvitaSessionContract session,
		@Nonnull List<CatalogBoundMutation> txMutations
	) {
		for (final CatalogBoundMutation mutation : txMutations) {
			if (mutation instanceof EntityMutation entityMutation) {
				session.applyMutation(entityMutation);
			} else if (mutation instanceof LocalCatalogSchemaMutation schemaMutation) {
				session.updateCatalogSchema(schemaMutation);
			} else {
				throw new GenericEvitaInternalError(
					"Unsupported mutation type encountered during replay: " + mutation.getClass().getName(),
					"Unsupported mutation type encountered during replay."
				);
			}
		}
	}

	/**
	 * Collapses a throwable chain into a short, groupable signature so repeated failures of the same
	 * kind are censused together rather than printed one by one.
	 */
	@Nonnull
	private static String signature(@Nonnull Throwable throwable) {
		Throwable root = throwable;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		final String rawMessage = root.getMessage() == null ? "" : root.getMessage();
		final String message = rawMessage.length() > 140 ? rawMessage.substring(0, 140) : rawMessage;
		return root.getClass().getSimpleName() + " | " + message;
	}

	@TearDown(Level.Trial)
	public void tearDown() throws IOException {
		if (this.statistics != null) {
			log.info("\n{}", this.statistics.format());
		}
		if (!this.failureSignatures.isEmpty()) {
			final StringBuilder census = new StringBuilder(256);
			census.append("Distinct failure signatures encountered during replay:\n");
			for (final Map.Entry<String, int[]> entry : this.failureSignatures.entrySet()) {
				census.append(String.format("  [%6d x] %s%n", entry.getValue()[0], entry.getKey()));
			}
			log.info("\n{}", census);
		}
		if (this.evita != null) {
			this.evita.close();
		}
		if (this.workDataDir != null) {
			FileUtils.deleteDirectory(this.workDataDir.toFile());
		}
	}

	/**
	 * A read-only WAL source together with the {@link Scheduler} it was constructed with. The log does
	 * not take ownership of the scheduler, so both must be closed together.
	 *
	 * @param wal       the read-only write-ahead log
	 * @param scheduler the scheduler backing it
	 */
	private record WalSource(@Nonnull CatalogWriteAheadLog wal, @Nonnull Scheduler scheduler) {

		/**
		 * Closes the log and shuts the scheduler down - the latter's threads are non-daemon and would
		 * otherwise keep the JVM alive.
		 *
		 * @throws IOException when the underlying log cannot be closed
		 */
		void close() throws IOException {
			this.wal.close();
			this.scheduler.shutdown();
		}

	}

	/**
	 * Growable, allocation-frugal collector of nanosecond latency samples. Callbacks fire on evitaDB
	 * pipeline threads, so appends are synchronized; the arithmetic itself stays on primitives.
	 */
	private static final class LatencySamples {
		private long[] samples = new long[4096];
		private int count;

		/**
		 * Records a single latency sample.
		 *
		 * @param nanos the measured duration in nanoseconds
		 */
		synchronized void add(long nanos) {
			if (this.count == this.samples.length) {
				this.samples = Arrays.copyOf(this.samples, this.samples.length << 1);
			}
			this.samples[this.count++] = nanos;
		}

		/**
		 * Sorts the collected samples and derives the summary statistics from them.
		 *
		 * @return immutable summary of everything recorded so far
		 */
		@Nonnull
		synchronized LatencySummary summarize() {
			if (this.count == 0) {
				return new LatencySummary(0, 0, 0, 0, 0, 0, 0, 0);
			}
			final long[] sorted = Arrays.copyOf(this.samples, this.count);
			Arrays.sort(sorted);
			long sum = 0;
			for (int i = 0; i < sorted.length; i++) {
				sum += sorted[i];
			}
			return new LatencySummary(
				sorted.length, sorted[0], sum / sorted.length, percentile(sorted, 50),
				percentile(sorted, 95), percentile(sorted, 99), sorted[sorted.length - 1], sum
			);
		}

		/**
		 * @param sorted     ascending array of samples
		 * @param percentile the requested percentile (1-100)
		 * @return the sample at the requested percentile
		 */
		private static long percentile(@Nonnull long[] sorted, int percentile) {
			final int index = (int) Math.min(sorted.length - 1L, (percentile * (long) sorted.length) / 100L);
			return sorted[index];
		}
	}

	/**
	 * Summary statistics of one latency series, all durations in nanoseconds.
	 *
	 * @param count   number of samples
	 * @param min     smallest sample
	 * @param mean    arithmetic mean
	 * @param median  50th percentile
	 * @param p95     95th percentile
	 * @param p99     99th percentile
	 * @param max     largest sample
	 * @param sum     sum of all samples
	 */
	public record LatencySummary(
		int count, long min, long mean, long median, long p95, long p99, long max, long sum
	) {

		/**
		 * Formats the summary as a single fixed-width table row in milliseconds.
		 *
		 * @param label the row label
		 * @return the formatted row
		 */
		@Nonnull
		public String format(@Nonnull String label) {
			return String.format(
				"  %-26s %7d %10.3f %10.3f %10.3f %10.3f %10.3f %10.3f%n",
				label, this.count, this.min / 1_000_000.0, this.mean / 1_000_000.0,
				this.median / 1_000_000.0, this.p95 / 1_000_000.0, this.p99 / 1_000_000.0,
				this.max / 1_000_000.0
			);
		}

	}

	/**
	 * Aggregate result of one replay pass - throughput plus the latency distribution of every
	 * commit-pipeline stage.
	 *
	 * @param transactions      transactions successfully replayed
	 * @param mutations         mutations contained in them
	 * @param skipped           transactions skipped because their version was already applied
	 * @param failed            transactions that failed and were rolled back
	 * @param replayNanos       wall-clock duration of the whole replay window
	 * @param applyLatency      time spent applying mutations inside the session, before commit
	 * @param conflictLatency   commit handover to conflict resolution confirmed
	 * @param walLatency        commit handover to WAL persistence confirmed
	 * @param visibleLatency    commit handover to changes visible (trunk incorporation) confirmed
	 * @param endToEndLatency   transaction start to changes visible
	 */
	public record ReplayStatistics(
		long transactions, long mutations, long skipped, long failed, long replayNanos,
		@Nonnull LatencySummary applyLatency,
		@Nonnull LatencySummary conflictLatency,
		@Nonnull LatencySummary walLatency,
		@Nonnull LatencySummary visibleLatency,
		@Nonnull LatencySummary endToEndLatency
	) {

		/**
		 * @return transactions committed per second over the whole replay window
		 */
		public double transactionsPerSecond() {
			return this.replayNanos == 0 ? 0 : (this.transactions * 1_000_000_000.0) / this.replayNanos;
		}

		/**
		 * @return mutations applied per second over the whole replay window
		 */
		public double mutationsPerSecond() {
			return this.replayNanos == 0 ? 0 : (this.mutations * 1_000_000_000.0) / this.replayNanos;
		}

		/**
		 * Renders the whole result as a human-readable report block.
		 *
		 * @return the formatted report
		 */
		@Nonnull
		public String format() {
			final StringBuilder result = new StringBuilder(1024);
			result.append("===== WAL REPLAY RESULT =====\n");
			result.append(String.format(
				"transactions replayed : %d (skipped %d already applied, failed %d)%n",
				this.transactions, this.skipped, this.failed
			));
			result.append(String.format("mutations applied     : %d%n", this.mutations));
			result.append(String.format("replay wall-clock     : %.3f s%n", this.replayNanos / 1_000_000_000.0));
			result.append(String.format("throughput            : %.1f tx/s, %.1f mutations/s%n",
				transactionsPerSecond(), mutationsPerSecond()));
			result.append(String.format(
				"average tx size       : %.1f mutations/tx%n",
				this.transactions == 0 ? 0.0 : (double) this.mutations / this.transactions
			));
			result.append("\nlatency (milliseconds)\n");
			result.append(String.format(
				"  %-26s %7s %10s %10s %10s %10s %10s %10s%n",
				"stage", "count", "min", "mean", "median", "p95", "p99", "max"
			));
			result.append(this.applyLatency.format("apply mutations (session)"));
			result.append(this.conflictLatency.format("-> conflict resolved"));
			result.append(this.walLatency.format("-> WAL persisted"));
			result.append(this.visibleLatency.format("-> changes visible"));
			result.append(this.endToEndLatency.format("tx start -> visible"));
			return result.toString();
		}

	}

}
