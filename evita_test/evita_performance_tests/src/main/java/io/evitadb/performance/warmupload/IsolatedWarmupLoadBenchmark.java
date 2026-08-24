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

package io.evitadb.performance.warmupload;

import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ExportOptions;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.export.file.configuration.FileSystemExportOptions;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.driver.EvitaClient;
import io.evitadb.driver.config.ClientTimeoutOptions;
import io.evitadb.driver.config.ClientTlsOptions;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.test.builder.CopyExistingEntityBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;

/**
 * Single-threaded WARM_UP bulk-load benchmark that reads from an **embedded** source catalog and writes
 * into a **separate, independently profileable** target.
 *
 * The whole point of the split: the reader and the writer must not share a JVM. In a single-process copy
 * the very same thread deserializes source entities and applies target mutations, so no thread filter or
 * sampling trick can separate them - read-path Kryo work lands in the same flame graph as index
 * maintenance. Running the writer as its own server process means a profiler attached to it sees the
 * ingestion path and nothing else.
 *
 * A second, less obvious benefit: it halves the writer's live set. The older all-in-one-server harness
 * ({@link io.evitadb.spike.WarmupCopyCatalogBenchmark}) kept *both* catalogs in the server heap, which is
 * the configuration the write-path ADR found death-spiralling in G1. Here the server holds only the
 * catalog being built.
 *
 * **Two target modes**, selected by {@value #TARGET_MODE_PROPERTY}:
 *
 * - **`remote`** (default) - the target is a separate evitaDB server reached over gRPC, started
 *   beforehand (see `.claude/skills/warmup-reindex-benchmark/run-warmup-target-server.sh`, or let
 *   `run-warmup-reindex.sh` drive both sides). This is the mode to profile: attach to the *server*
 *   process. The trade-off is that client-observed upsert latency then includes protobuf
 *   serialization, the loopback round trip and server-side deframing - it is **end-to-end ingestion
 *   latency, not write-path latency**.
 * - **`embedded`** - the target is a second catalog inside this same JVM, i.e. no transport at all. Useless
 *   for profiling (see above) but it yields a transport-free latency baseline. **Running both modes and
 *   differencing them is how the gRPC overhead in the `remote` numbers gets quantified** rather than
 *   guessed at.
 *
 * **What is measured.** The clock starts after schema reconstruction (excluded) and the report separates
 * four things that are routinely conflated:
 *
 * - **pure upsert time** - the sum of every individual {@link EvitaSessionContract#upsertEntity} call,
 *   with its full mean/median/p95/p99 distribution.
 * - **source read time** - every source fetch, reported so it can be seen and subtracted rather than
 *   silently inflating throughput.
 * - **copy wall-clock** - read + upsert + loop overhead, i.e. how long the rebuild actually takes.
 * - **goLive transition** - measured separately, never folded into the per-upsert statistics.
 *
 * **Caveats that must travel with any number this prints:**
 *
 * - In `remote` mode one upsert is one gRPC round trip (~50-200 us on loopback). Against `Product` at
 *   milliseconds each that is noise, but for cheap collections (tag, stock, voucher) the wire dominates
 *   and those rows must not be read as write-path costs.
 * - Heap size is not a neutral knob: it feeds the collation-key cache's heap-derived default sizing, and
 *   an undersized heap turns this workload GC-bound. **Never compare runs taken at different heaps.** The
 *   GC share of the measured window is always reported; treat anything materially above ~15 % as a
 *   measurement of the collector rather than of evitaDB. Note the reported GC figure covers **this**
 *   (reader) JVM - in `remote` mode the writer's GC must be read from the server's own GC log.
 * - Numbers here are not comparable to the 2 020 s / 16.1 ms-per-`Product` gRPC baseline in
 *   `documentation/adr/2026-07-27-write-path-performance-tuning/`: that harness also *read* over gRPC.
 *
 * The source archive is never touched - the pristine snapshot directory is copied into a disposable
 * working directory on every run and the embedded engine only ever opens that copy.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
public class IsolatedWarmupLoadBenchmark {

	/**
	 * System property pointing to the directory holding a `&lt;catalogName&gt;/` subfolder with the
	 * pristine source snapshot. Copied fresh into the working directory on every run, never written to.
	 * Mandatory.
	 */
	public static final String PRISTINE_DATA_DIR_PROPERTY = "evita.warmup.pristineDataDir";
	/**
	 * System property naming the source catalog; the pristine data directory must contain a subfolder with
	 * exactly this name.
	 */
	public static final String CATALOG_NAME_PROPERTY = "evita.warmup.catalogName";
	/**
	 * System property naming the target catalog to create and populate. In `remote` mode this lives on the
	 * separate server, so it may safely carry the same name as the source.
	 */
	public static final String TARGET_CATALOG_PROPERTY = "evita.warmup.targetCatalog";
	/**
	 * System property selecting where the target catalog lives: `remote` (a separate server over gRPC, the
	 * profileable mode) or `embedded` (this JVM, the transport-free control).
	 */
	public static final String TARGET_MODE_PROPERTY = "evita.warmup.targetMode";
	/**
	 * System property naming the target server host (`remote` mode only).
	 */
	public static final String TARGET_HOST_PROPERTY = "evita.warmup.targetHost";
	/**
	 * System property naming the target server gRPC port (`remote` mode only).
	 */
	public static final String TARGET_PORT_PROPERTY = "evita.warmup.targetPort";
	/**
	 * System property overriding the working directory the pristine snapshot is copied into. Defaults to a
	 * fresh temp directory.
	 */
	public static final String WORK_DIR_PROPERTY = "evita.warmup.workDir";
	/**
	 * System property capping the entities copied per collection (`0` = unlimited). A small positive value
	 * gives a fast end-to-end smoke test; leave it `0` for a real measurement.
	 */
	public static final String MAX_PER_COLLECTION_PROPERTY = "evita.warmup.maxPerCollection";
	/**
	 * System property restricting the run to a comma-separated subset of collections (empty = all). Useful
	 * when profiling, since one collection typically dominates the whole copy.
	 */
	public static final String COLLECTIONS_PROPERTY = "evita.warmup.collections";
	/**
	 * System property overriding how many entities are read from the source per fetch. Reads are keyed by an
	 * explicit primary-key set, so each fetch is an indexed lookup rather than a deep-pagination scan.
	 */
	public static final String BATCH_SIZE_PROPERTY = "evita.warmup.batchSize";
	/**
	 * System property setting how many upserts pass between two intra-collection progress lines (`0` = only
	 * per-collection summaries). A full run takes tens of minutes, so this is what makes it observable.
	 */
	public static final String PROGRESS_INTERVAL_PROPERTY = "evita.warmup.progressInterval";
	/**
	 * System property keeping this JVM alive for the given number of seconds after the report is printed.
	 * Mostly useful in `embedded` mode; when profiling the *server* in `remote` mode it is the server that
	 * has to be kept alive, which its own launcher handles.
	 */
	public static final String HOLD_OPEN_SECONDS_PROPERTY = "evita.warmup.holdOpenSeconds";
	/**
	 * System property naming a CSV file to receive one row per upserted entity (collection, primary key,
	 * upsert duration). Unset (default) records nothing.
	 */
	public static final String PER_ENTITY_CSV_PROPERTY = "evita.warmup.perEntityCsv";
	/**
	 * System property skipping the post-copy count verification. Verification re-queries both catalogs and
	 * costs a little time, but it is the only guard against a silently short copy - skip only deliberately.
	 */
	public static final String SKIP_VERIFICATION_PROPERTY = "evita.warmup.skipVerification";
	/**
	 * System property keeping the working directory on disk after the run instead of deleting it.
	 */
	public static final String KEEP_WORK_DIR_PROPERTY = "evita.warmup.keepWorkDir";

	/**
	 * Default number of entities read from the source per fetch.
	 */
	private static final int DEFAULT_BATCH_SIZE = 1_000;
	/**
	 * Default number of upserts between two intra-collection progress lines.
	 */
	private static final int DEFAULT_PROGRESS_INTERVAL = 25_000;
	/**
	 * GC share of the measured window above which the run is called out as GC-bound in the report.
	 */
	private static final double GC_BOUND_WARNING_THRESHOLD = 15.0;
	/**
	 * Upper bound for a single gRPC message accepted by the client. Armeria's default is 10 MB; a rich
	 * entity (senesi `Product` with prices and references) plus schema payloads can approach it, and the
	 * deframer aborts the whole run with `RESOURCE_EXHAUSTED` rather than degrading. 256 MB is ample
	 * headroom while still guarding against a runaway message.
	 */
	private static final int MAX_GRPC_MESSAGE_SIZE = 256 * 1024 * 1024;
	/**
	 * How long to wait for the target server to accept connections before giving up (`remote` mode).
	 */
	private static final Duration SERVER_WAIT_TIMEOUT = Duration.ofMinutes(10);

	/**
	 * Where the target catalog lives.
	 */
	private enum TargetMode {
		/**
		 * A separate evitaDB server reached over gRPC - the mode to profile.
		 */
		REMOTE,
		/**
		 * A second catalog inside this JVM - the transport-free control.
		 */
		EMBEDDED
	}

	/**
	 * Program entry point. See the class-level documentation for what is measured and the `*_PROPERTY`
	 * constants for configuration.
	 *
	 * @param args ignored - the harness is configured entirely through system properties
	 * @throws Exception when the working copy cannot be prepared, the target is unreachable, or either
	 *                   engine fails to close - all of which must abort the run loudly rather than yield
	 *                   a partial measurement
	 */
	public static void main(@Nonnull final String[] args) throws Exception {
		new IsolatedWarmupLoadBenchmark().run();
	}

	/**
	 * Prepares the working copy, boots the embedded source engine, connects (or falls back) to the target,
	 * runs the measured rebuild and prints the report.
	 *
	 * @throws Exception when the working copy cannot be prepared, the target is unreachable, or either
	 *                   engine fails to close
	 */
	private void run() throws Exception {
		final Path pristineDataDir = Path.of(requiredProperty(PRISTINE_DATA_DIR_PROPERTY));
		final String sourceCatalog = System.getProperty(CATALOG_NAME_PROPERTY, "senesi");
		final TargetMode targetMode = TargetMode.valueOf(
			System.getProperty(TARGET_MODE_PROPERTY, "remote").trim().toUpperCase()
		);
		final String targetCatalog = System.getProperty(
			TARGET_CATALOG_PROPERTY, targetMode == TargetMode.REMOTE ? sourceCatalog : sourceCatalog + "_warmup"
		);
		final String targetHost = System.getProperty(TARGET_HOST_PROPERTY, "localhost");
		final int targetPort = Integer.parseInt(System.getProperty(TARGET_PORT_PROPERTY, "5555"));
		final int maxPerCollection = Integer.parseInt(System.getProperty(MAX_PER_COLLECTION_PROPERTY, "0"));
		final int batchSize = Integer.parseInt(
			System.getProperty(BATCH_SIZE_PROPERTY, String.valueOf(DEFAULT_BATCH_SIZE))
		);
		final int progressInterval = Integer.parseInt(
			System.getProperty(PROGRESS_INTERVAL_PROPERTY, String.valueOf(DEFAULT_PROGRESS_INTERVAL))
		);
		final int holdOpenSeconds = Integer.parseInt(System.getProperty(HOLD_OPEN_SECONDS_PROPERTY, "0"));
		final boolean skipVerification = Boolean.parseBoolean(System.getProperty(SKIP_VERIFICATION_PROPERTY, "false"));
		final boolean keepWorkDir = Boolean.parseBoolean(System.getProperty(KEEP_WORK_DIR_PROPERTY, "false"));
		final Path perEntityCsv = System.getProperty(PER_ENTITY_CSV_PROPERTY) == null ?
			null : Path.of(System.getProperty(PER_ENTITY_CSV_PROPERTY));
		final Set<String> requestedCollections = parseCollections(System.getProperty(COLLECTIONS_PROPERTY));
		final Path workDataDir = System.getProperty(WORK_DIR_PROPERTY) == null ?
			Files.createTempDirectory("evita-warmup-source") : Path.of(System.getProperty(WORK_DIR_PROPERTY));

		if (batchSize < 1) {
			throw new GenericEvitaInternalError(
				"Batch size must be positive, got " + batchSize + ".", "Batch size must be positive."
			);
		}

		// ---- prepare a disposable working copy - the pristine snapshot is never written to -----
		log.info("Copying pristine snapshot `{}` -> working directory `{}`...", pristineDataDir, workDataDir);
		final long prepareStart = System.nanoTime();
		FileUtils.deleteDirectory(workDataDir.toFile());
		FileUtils.copyDirectory(
			pristineDataDir.resolve(sourceCatalog).toFile(),
			workDataDir.resolve(sourceCatalog).toFile()
		);
		log.info("Working copy ready in {} ms.", (System.nanoTime() - prepareStart) / 1_000_000);

		final Evita source = bootSourceEngine(workDataDir);
		try {
			awaitCatalogAlive(source, sourceCatalog);
			final EvitaContract target = targetMode == TargetMode.REMOTE ?
				connectToTargetServer(targetHost, targetPort) : source;
			try {
				final CopyResult result = copyCatalog(
					source, target, targetMode, sourceCatalog, targetCatalog, requestedCollections,
					maxPerCollection, batchSize, progressInterval, perEntityCsv
				);
				// The report is rendered BEFORE verification. Verification has to talk to the target, and a
				// goLive that failed client-side leaves the catalog terminated from this client's point of
				// view - so a verification that cannot even run must not take the measurement down with it.
				System.out.println(
					result.format(sourceCatalog, targetCatalog, targetMode, targetHost, targetPort, batchSize)
				);
				if (perEntityCsv != null) {
					log.info("Per-entity upsert CSV written to `{}`.", perEntityCsv);
				}
				if (skipVerification) {
					log.info("Post-copy verification skipped by configuration.");
				} else {
					try {
						verifyCopy(source, target, sourceCatalog, targetCatalog, maxPerCollection, result);
					} catch (Exception ex) {
						log.error(
							"Post-copy verification could not run - the load figures above stand, but the " +
								"target entity counts are UNVERIFIED for this run.", ex
						);
					}
				}
				holdOpen(holdOpenSeconds);
			} finally {
				// in embedded mode the target IS the source engine - closing it here would be premature
				if (target != source) {
					target.close();
				}
			}
		} finally {
			source.close();
			if (keepWorkDir) {
				log.info("Working directory `{}` kept on disk by configuration.", workDataDir);
			} else {
				FileUtils.deleteDirectory(workDataDir.toFile());
			}
		}
	}

	/**
	 * Boots the embedded engine that serves as the **read** side. Session inactivity timeouts are
	 * effectively disabled because one source read session is held open for the entire run.
	 *
	 * @param workDataDir storage directory holding the working copy of the source catalog
	 * @return the booted instance
	 */
	@Nonnull
	private static Evita bootSourceEngine(@Nonnull final Path workDataDir) {
		log.info(
			"Booting embedded source engine against `{}` (reader max heap {})...",
			workDataDir, formatBytes(Runtime.getRuntime().maxMemory())
		);
		final long bootStart = System.nanoTime();
		final Evita evita = new Evita(
			EvitaConfiguration.builder()
				.server(
					ServerOptions.builder()
						.queryTimeoutInMilliseconds(3_600_000)
						.transactionTimeoutInMilliseconds(3_600_000)
						.closeSessionsAfterSecondsOfInactivity(Integer.MAX_VALUE)
						.build()
				)
				.storage(StorageOptions.builder().storageDirectory(workDataDir).build())
				.cache(CacheOptions.builder().build())
				// The export directory defaults to `./export` RELATIVE TO THE WORKING DIRECTORY, and evitaDB
				// takes an exclusive folder lock on it. The target server is normally launched from the same
				// project directory, so leaving this at its default makes the second engine to start die with
				// `FolderAlreadyUsedException`. Keeping it inside the disposable work dir both avoids the
				// clash and gets it cleaned up with everything else.
				.export(
					new FileSystemExportOptions(
						true,
						ExportOptions.DEFAULT_SIZE_LIMIT_BYTES,
						ExportOptions.DEFAULT_HISTORY_EXPIRATION_SECONDS,
						workDataDir.resolve("export")
					)
				)
				.build()
		);
		log.info("Source engine boot returned in {} ms.", (System.nanoTime() - bootStart) / 1_000_000);
		return evita;
	}

	/**
	 * Connects to the separate target server and blocks until it answers. The server is expected to run with
	 * `tlsMode=RELAXED` and mTLS disabled (as `run-server.sh` configures it); timeouts are deliberately
	 * generous because a bulk load of a multi-GB catalog runs for a long time.
	 *
	 * @param host target server host
	 * @param port target server gRPC (and system API) port
	 * @return the connected client
	 */
	@Nonnull
	private static EvitaClient connectToTargetServer(@Nonnull final String host, final int port) {
		final EvitaClientConfiguration configuration = EvitaClientConfiguration.builder()
			.host(host)
			.port(port)
			.systemApiPort(port)
			.tls(ClientTlsOptions.builder().tlsEnabled(false).mtlsEnabled(false).build())
			.timeouts(
				ClientTimeoutOptions.builder()
					.timeout(1, TimeUnit.HOURS)
					.streamingTimeout(1, TimeUnit.HOURS)
					.build()
			)
			.build();
		final Consumer<GrpcClientBuilder> grpcConfigurator = grpcClientBuilder -> grpcClientBuilder
			.maxResponseMessageLength(MAX_GRPC_MESSAGE_SIZE)
			.maxResponseLength(MAX_GRPC_MESSAGE_SIZE);

		log.info("Connecting to target server at {}:{}...", host, port);
		final EvitaClient client = new EvitaClient(configuration, grpcConfigurator);
		final long deadline = System.nanoTime() + SERVER_WAIT_TIMEOUT.toNanos();
		while (true) {
			try {
				client.getCatalogNames();
				log.info("Target server at {}:{} is answering.", host, port);
				return client;
			} catch (final RuntimeException ex) {
				if (System.nanoTime() > deadline) {
					client.close();
					throw new GenericEvitaInternalError(
						"Target server at " + host + ":" + port + " did not become available within " +
							SERVER_WAIT_TIMEOUT.toMinutes() + " minutes - is it started, and is its gRPC port " +
							port + " with tlsMode=RELAXED? Last error: " + ex.getMessage(),
						"Target server did not become available in time.", ex
					);
				}
				sleepQuietly(1_000L);
			}
		}
	}

	/**
	 * Runs the measured rebuild: reads the source schema, reconstructs it on a freshly created target
	 * catalog (untimed), then copies every entity of every selected collection on a single thread through
	 * one long-lived WARM_UP session, and finally switches the target to ALIVE.
	 *
	 * @param source               the embedded engine holding the source catalog
	 * @param target               where the target catalog lives (a client, or the same engine)
	 * @param targetMode           which of the two the target is
	 * @param sourceCatalog        name of the catalog to read
	 * @param targetCatalog        name of the catalog to create and populate
	 * @param requestedCollections collections to copy, or empty for all
	 * @param maxPerCollection     maximum entities per collection (0 == unlimited)
	 * @param batchSize            entities read from the source per fetch
	 * @param progressInterval     upserts between intra-collection progress lines (0 == none)
	 * @param perEntityCsv         optional CSV destination for one row per upsert
	 * @return the collected measurement
	 * @throws IOException when the per-entity CSV cannot be written
	 */
	@Nonnull
	private static CopyResult copyCatalog(
		@Nonnull final Evita source,
		@Nonnull final EvitaContract target,
		@Nonnull final TargetMode targetMode,
		@Nonnull final String sourceCatalog,
		@Nonnull final String targetCatalog,
		@Nonnull final Set<String> requestedCollections,
		final int maxPerCollection,
		final int batchSize,
		final int progressInterval,
		@Nullable final Path perEntityCsv
	) throws IOException {
		// ---- read the source schema (not timed) ----------------------------------------------
		final CatalogSchemaContract sourceCatalogSchema;
		final List<String> entityTypes;
		final Map<String, EntitySchemaContract> sourceEntitySchemas = new LinkedHashMap<>();
		try (final EvitaSessionContract schemaSession = source.createReadOnlySession(sourceCatalog)) {
			sourceCatalogSchema = schemaSession.getCatalogSchema();
			entityTypes = new ArrayList<>(new TreeSet<>(schemaSession.getAllEntityTypes()));
			for (final String entityType : entityTypes) {
				sourceEntitySchemas.put(entityType, schemaSession.getEntitySchemaOrThrowException(entityType));
			}
		}
		log.info("Source catalog `{}` has {} collections: {}", sourceCatalog, entityTypes.size(), entityTypes);

		final List<String> selectedTypes = selectCollections(entityTypes, requestedCollections);
		if (selectedTypes.size() != entityTypes.size()) {
			log.info("Restricted to {} collection(s): {}", selectedTypes.size(), selectedTypes);
		}

		// ---- create + reconstruct the target schema (not timed) ------------------------------
		if (targetMode == TargetMode.EMBEDDED && targetCatalog.equals(sourceCatalog)) {
			throw new GenericEvitaInternalError(
				"In embedded target mode the target catalog must differ from the source catalog - both live " +
					"in the same engine. Set `" + TARGET_CATALOG_PROPERTY + "`.",
				"In embedded target mode the target catalog must differ from the source catalog."
			);
		}
		target.deleteCatalogIfExists(targetCatalog);
		target.defineCatalog(targetCatalog);
		final long schemaStart = System.nanoTime();
		target.updateCatalog(
			targetCatalog,
			session -> {
				CatalogCopySupport.replicateSchema(session, sourceCatalogSchema, sourceEntitySchemas.values());
			}
		);
		log.info(
			"Target catalog `{}` created and schema reconstructed in {} ms - starting single-threaded WARM_UP load.",
			targetCatalog, (System.nanoTime() - schemaStart) / 1_000_000
		);

		final Map<String, CollectionResult> byCollection = new LinkedHashMap<>();
		final LatencySamples overallUpsert = new LatencySamples(1 << 16);
		final List<String> perEntityRows = perEntityCsv == null ? null : new ArrayList<>(1 << 16);
		final GcSnapshot gcBefore = GcSnapshot.capture();
		final long copyWallStart;
		final long copyWallNanos;
		final long goLiveNanos;
		final String goLiveError;

		// one long-lived read session on the source and one long-lived WARM_UP write session on the target,
		// both held open for the entire run - the source session is the outer resource
		try (final EvitaSessionContract sourceSession = source.createReadOnlySession(sourceCatalog)) {
			final EvitaSessionContract targetSession = target.createReadWriteSession(targetCatalog);
			try {
				copyWallStart = System.nanoTime();
				for (final String entityType : selectedTypes) {
					final CollectionResult collectionResult = copyCollection(
						sourceSession, targetSession, sourceEntitySchemas.get(entityType),
						maxPerCollection, batchSize, progressInterval, overallUpsert, perEntityRows
					);
					byCollection.put(entityType, collectionResult);
					log.info(
						"  loaded {} {} in {} - upsert mean {}, median {} | cumulative {} entities, {}",
						String.format("%,10d", collectionResult.entities()), padRight(entityType, 20),
						formatDuration(collectionResult.wallNanos()),
						formatMicros(collectionResult.upsertLatency().mean()),
						formatMicros(collectionResult.upsertLatency().median()),
						String.format("%,d", overallUpsert.count()),
						formatDuration(System.nanoTime() - copyWallStart)
					);
				}
				copyWallNanos = System.nanoTime() - copyWallStart;

				// Written BEFORE the ALIVE transition, deliberately. goLive can fail client-side and it
				// closes the session on its way out either way, so anything deferred past this point risks
				// losing the raw samples of a half-hour run to a failure that happened after the measurement
				// was already complete.
				if (perEntityRows != null) {
					writePerEntityCsv(perEntityCsv, perEntityRows);
				}

				final long goLiveStart = System.nanoTime();
				String goLiveFailure = null;
				try {
					// switches the target catalog from WARM_UP to ALIVE (this also closes the session)
					targetSession.goLiveAndClose();
				} catch (Exception ex) {
					// A failed transition must NOT discard a completed load - the load IS the measurement,
					// and the transition may well have succeeded on the server regardless. Observed on
					// 2026.2.2 over gRPC: the client aborts with a spurious DEADLINE_EXCEEDED (reporting an
					// hour-long deadline) ~12 s in, while the server logs `is now alive!` ~11 s later. Without
					// this catch the exception escapes before the report is rendered and the per-entity CSV is
					// written, throwing away a 27-minute run over a post-load failure.
					goLiveFailure = ex.getClass().getSimpleName() + ": " + ex.getMessage();
					log.error("goLiveAndClose() failed - load completed, so results are still reported.", ex);
				}
				goLiveNanos = System.nanoTime() - goLiveStart;
				goLiveError = goLiveFailure;
			} finally {
				// goLiveAndClose() already closed it on the happy path; this only matters when the load threw
				if (targetSession.isActive()) {
					targetSession.close();
				}
			}
		}
		final GcSnapshot gcAfter = GcSnapshot.capture();

		return new CopyResult(
			byCollection, overallUpsert.summarize(), copyWallNanos, goLiveNanos, goLiveError,
			gcAfter.minus(gcBefore), Runtime.getRuntime().maxMemory(),
			Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
		);
	}

	/**
	 * Copies every entity of a single collection from the source session into the (WARM_UP) target session.
	 * Primary keys are gathered once via a cheap reference-only query, then entities are fetched with full
	 * content in primary-key batches so each read is an indexed lookup rather than a deep-pagination scan.
	 *
	 * Each {@link EvitaSessionContract#upsertEntity} call is timed individually; the surrounding read is
	 * timed as a separate aggregate. Anything else in this loop is deliberately trivial so the two together
	 * account for essentially all of the collection's wall clock.
	 *
	 * @param sourceSession    long-lived read session on the source catalog
	 * @param targetSession    long-lived WARM_UP write session on the target catalog
	 * @param schema           the source schema of the collection to copy
	 * @param maxPerCollection maximum entities to copy (0 == unlimited)
	 * @param batchSize        entities read per fetch
	 * @param progressInterval upserts between progress lines (0 == none)
	 * @param overallUpsert    accumulator collecting every upsert sample across all collections
	 * @param perEntityRows    optional collector of per-entity CSV rows
	 * @return the collection's measurement
	 */
	@Nonnull
	private static CollectionResult copyCollection(
		@Nonnull final EvitaSessionContract sourceSession,
		@Nonnull final EvitaSessionContract targetSession,
		@Nonnull final EntitySchemaContract schema,
		final int maxPerCollection,
		final int batchSize,
		final int progressInterval,
		@Nonnull final LatencySamples overallUpsert,
		@Nullable final List<String> perEntityRows
	) {
		final String entityType = schema.getName();
		// reflected references are read-only projections auto-maintained from the owning (plain) reference on
		// the other entity; they must not be fetched or written here - copying the plain reference rebuilds them
		final EntityFetch contentRequirement = CatalogCopySupport.buildContentRequirement(schema);
		int[] primaryKeys = fetchAllPrimaryKeys(sourceSession, entityType);
		if (maxPerCollection > 0 && primaryKeys.length > maxPerCollection) {
			primaryKeys = Arrays.copyOf(primaryKeys, maxPerCollection);
		}

		final LatencySamples upsertSamples = new LatencySamples(Math.max(1024, primaryKeys.length));
		final long collectionStart = System.nanoTime();
		long readNanos = 0L;
		long copied = 0L;
		long nextProgressAt = progressInterval > 0 ? progressInterval : Long.MAX_VALUE;

		for (int offset = 0; offset < primaryKeys.length; offset += batchSize) {
			final int limit = Math.min(batchSize, primaryKeys.length - offset);
			final Integer[] batch = new Integer[limit];
			for (int i = 0; i < limit; i++) {
				batch[i] = primaryKeys[offset + i];
			}

			final long readStart = System.nanoTime();
			final List<SealedEntity> entities = sourceSession.queryListOfSealedEntities(
				Query.query(
					collection(entityType),
					filterBy(entityPrimaryKeyInSet(batch)),
					require(page(1, limit), contentRequirement)
				)
			);
			readNanos += System.nanoTime() - readStart;

			for (int i = 0; i < entities.size(); i++) {
				final SealedEntity entity = entities.get(i);
				final CopyExistingEntityBuilder builder = new CopyExistingEntityBuilder(entity);
				final long upsertStart = System.nanoTime();
				targetSession.upsertEntity(builder);
				final long upsertNanos = System.nanoTime() - upsertStart;
				upsertSamples.add(upsertNanos);
				overallUpsert.add(upsertNanos);
				if (perEntityRows != null) {
					perEntityRows.add(entityType + ',' + entity.getPrimaryKey() + ',' + upsertNanos);
				}
			}
			copied += entities.size();

			if (copied >= nextProgressAt) {
				final long elapsed = System.nanoTime() - collectionStart;
				log.info(
					"    {} ... {} / {} entities, {} elapsed, {} entities/s (wall)",
					entityType, String.format("%,d", copied), String.format("%,d", primaryKeys.length),
					formatDuration(elapsed), String.format("%,.0f", copied * 1_000_000_000.0 / elapsed)
				);
				nextProgressAt = copied + progressInterval;
			}
		}

		final long wallNanos = System.nanoTime() - collectionStart;
		return new CollectionResult(copied, wallNanos, readNanos, upsertSamples.summarize());
	}

	/**
	 * Returns the complete set of primary keys of the given collection in a single reference-only query
	 * (no entity bodies fetched), so subsequent full-content reads can be batched by primary key.
	 *
	 * @param sourceSession read session on the source catalog
	 * @param entityType    the collection whose primary keys are requested
	 * @return array of all primary keys of the collection
	 */
	@Nonnull
	private static int[] fetchAllPrimaryKeys(
		@Nonnull final EvitaSessionContract sourceSession,
		@Nonnull final String entityType
	) {
		final int total = countEntities(sourceSession, entityType);
		if (total == 0) {
			return new int[0];
		}
		final List<EntityReferenceContract> references = sourceSession.queryListOfEntityReferences(
			Query.query(collection(entityType), require(page(1, total)))
		);
		final int[] primaryKeys = new int[references.size()];
		for (int i = 0; i < references.size(); i++) {
			primaryKeys[i] = references.get(i).getPrimaryKey();
		}
		return primaryKeys;
	}

	/**
	 * Returns the total number of entities of the given collection via a cheap reference-only count query.
	 *
	 * @param session    an open session on the catalog to count in
	 * @param entityType the collection to count
	 * @return total entity count of the collection
	 */
	private static int countEntities(
		@Nonnull final EvitaSessionContract session,
		@Nonnull final String entityType
	) {
		return session.queryEntityReference(
			Query.query(collection(entityType), require(page(1, 1)))
		).getTotalRecordCount();
	}

	/**
	 * Verifies the load is complete by comparing, per collection, the entity count in the (now ALIVE) target
	 * catalog against the authoritative count re-queried from the source - independently of the copy loop, so
	 * a silently short read is caught rather than masked. Any discrepancy fails the benchmark loudly, because
	 * a short load produces a *faster* and entirely meaningless number.
	 *
	 * @param source           the embedded engine holding the source catalog
	 * @param target           where the target catalog lives
	 * @param sourceCatalog    name of the catalog that was read
	 * @param targetCatalog    name of the freshly built catalog
	 * @param maxPerCollection the per-collection cap that was applied (0 == uncapped)
	 * @param result           the measurement, holding the per-collection copied counts
	 */
	private static void verifyCopy(
		@Nonnull final Evita source,
		@Nonnull final EvitaContract target,
		@Nonnull final String sourceCatalog,
		@Nonnull final String targetCatalog,
		final int maxPerCollection,
		@Nonnull final CopyResult result
	) {
		final StringBuilder mismatches = new StringBuilder(128);
		final StringBuilder table = new StringBuilder(512);
		table.append(String.format("%-22s %12s %12s %12s%n", "collection", "source", "target", "loaded"));
		try (
			final EvitaSessionContract sourceSession = source.createReadOnlySession(sourceCatalog);
			final EvitaSessionContract targetSession = target.createReadOnlySession(targetCatalog)
		) {
			for (final Map.Entry<String, CollectionResult> entry : result.byCollection().entrySet()) {
				final String entityType = entry.getKey();
				final int sourceCount = countEntities(sourceSession, entityType);
				final int targetCount = countEntities(targetSession, entityType);
				final long loaded = entry.getValue().entities();
				final long expected = maxPerCollection > 0 ? Math.min(sourceCount, maxPerCollection) : sourceCount;
				table.append(String.format("%-22s %12d %12d %12d%n", entityType, sourceCount, targetCount, loaded));
				if (targetCount != expected || loaded != expected) {
					mismatches.append(System.lineSeparator())
						.append("  ").append(entityType)
						.append(": expected ").append(expected)
						.append(" (source=").append(sourceCount)
						.append("), target=").append(targetCount)
						.append(", loaded=").append(loaded);
				}
			}
		}
		System.out.print(table);
		if (mismatches.length() > 0) {
			throw new IllegalStateException("Load verification FAILED - entity counts differ:" + mismatches);
		}
		System.out.println(
			maxPerCollection > 0 ?
				"Load verified: target matches min(source, cap) for every loaded collection." :
				"Load verified: target entity counts match the source exactly for every loaded collection."
		);
	}

	/**
	 * Blocks until the given catalog reaches {@link CatalogState#ALIVE}. Boot-time WAL catch-up runs on a
	 * background thread and the {@link Evita} constructor returns while the catalog is still in the
	 * transitional {@link CatalogState#BEING_ACTIVATED} state; querying it before that settles throws.
	 *
	 * @param evita       the booted engine
	 * @param catalogName the catalog to wait for
	 */
	private static void awaitCatalogAlive(@Nonnull final Evita evita, @Nonnull final String catalogName) {
		final long start = System.nanoTime();
		final long deadlineNanos = start + Duration.ofMinutes(60).toNanos();
		while (true) {
			final CatalogState state = evita.getCatalogState(catalogName)
				.orElseThrow(() -> new GenericEvitaInternalError(
					"Catalog `" + catalogName + "` not found in the freshly booted Evita instance.",
					"Catalog not found in the freshly booted Evita instance."
				));
			if (state == CatalogState.ALIVE) {
				log.info(
					"Source catalog `{}` fully activated in {}.",
					catalogName, formatDuration(System.nanoTime() - start)
				);
				return;
			} else if (!state.isTransitional()) {
				throw new GenericEvitaInternalError(
					"Catalog `" + catalogName + "` ended up in unexpected non-transitional state `" + state +
						"` after boot instead of `ALIVE`.",
					"Catalog ended up in an unexpected non-transitional state after boot."
				);
			} else if (System.nanoTime() > deadlineNanos) {
				throw new GenericEvitaInternalError(
					"Timed out waiting for catalog `" + catalogName + "` to become `ALIVE` (still `" + state + "`).",
					"Timed out waiting for catalog to become `ALIVE`."
				);
			}
			sleepQuietly(200L);
		}
	}

	/**
	 * Keeps this JVM alive for the configured number of seconds so an attached profiler can stop and dump
	 * its recording before the process exits.
	 *
	 * @param holdOpenSeconds seconds to wait (non-positive returns immediately)
	 */
	private static void holdOpen(final int holdOpenSeconds) {
		if (holdOpenSeconds <= 0) {
			return;
		}
		log.info("Holding this JVM open for {} s so an attached profiler can dump...", holdOpenSeconds);
		sleepQuietly(holdOpenSeconds * 1_000L);
	}

	/**
	 * Sleeps for the given number of milliseconds, restoring the interrupt flag and failing loudly when
	 * interrupted - a silently swallowed interrupt here would truncate a measurement without saying so.
	 *
	 * @param millis milliseconds to sleep
	 */
	private static void sleepQuietly(final long millis) {
		try {
			Thread.sleep(millis);
		} catch (final InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new GenericEvitaInternalError(
				"Interrupted while waiting.", "Interrupted while waiting.", interrupted
			);
		}
	}

	/**
	 * Writes one CSV row per upserted entity.
	 *
	 * @param perEntityCsv destination file
	 * @param rows         pre-rendered rows, without the header
	 * @throws IOException when the file cannot be written
	 */
	private static void writePerEntityCsv(@Nonnull final Path perEntityCsv, @Nonnull final List<String> rows)
		throws IOException {
		final StringBuilder csv = new StringBuilder(rows.size() * 40 + 64);
		csv.append("collection,primaryKey,upsert_nanos\n");
		for (int i = 0; i < rows.size(); i++) {
			csv.append(rows.get(i)).append('\n');
		}
		Files.write(perEntityCsv, csv.toString().getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Parses the comma-separated collection filter.
	 *
	 * @param raw the raw property value (may be null or blank)
	 * @return the requested collection names, empty when everything is to be copied
	 */
	@Nonnull
	private static Set<String> parseCollections(@Nullable final String raw) {
		final Set<String> result = new LinkedHashSet<>(8);
		if (raw == null || raw.isBlank()) {
			return result;
		}
		for (final String part : raw.split(",")) {
			final String trimmed = part.trim();
			if (!trimmed.isEmpty()) {
				result.add(trimmed);
			}
		}
		return result;
	}

	/**
	 * Intersects the source's collections with the requested subset, preserving source order and failing on
	 * a name that does not exist - a typo must not silently shrink the measured workload.
	 *
	 * @param entityTypes          all collections present in the source
	 * @param requestedCollections the requested subset (empty selects everything)
	 * @return the collections to copy
	 */
	@Nonnull
	private static List<String> selectCollections(
		@Nonnull final List<String> entityTypes,
		@Nonnull final Set<String> requestedCollections
	) {
		if (requestedCollections.isEmpty()) {
			return entityTypes;
		}
		final List<String> unknown = new ArrayList<>(requestedCollections);
		unknown.removeAll(entityTypes);
		if (!unknown.isEmpty()) {
			throw new GenericEvitaInternalError(
				"Requested collection(s) " + unknown +
					" do not exist in the source catalog - available: " + entityTypes,
				"Requested collection(s) do not exist in the source catalog."
			);
		}
		final List<String> selected = new ArrayList<>(requestedCollections.size());
		for (final String entityType : entityTypes) {
			if (requestedCollections.contains(entityType)) {
				selected.add(entityType);
			}
		}
		return selected;
	}

	/**
	 * Reads a mandatory system property.
	 *
	 * @param propertyName the property to read
	 * @return its value
	 */
	@Nonnull
	private static String requiredProperty(@Nonnull final String propertyName) {
		final String value = System.getProperty(propertyName);
		if (value == null || value.isBlank()) {
			throw new GenericEvitaInternalError(
				"Required system property `" + propertyName + "` is not set - see " +
					IsolatedWarmupLoadBenchmark.class.getSimpleName() + " JavaDoc.",
				"Required system property `" + propertyName + "` is not set."
			);
		}
		return value;
	}

	/**
	 * Formats a nanosecond duration for reading. Sub-minute durations stay in seconds - rounding a 0.4 s
	 * collection to `0:00:00` would throw away everything it had to say - while anything longer renders
	 * as `H:mm:ss`, because a full bulk load runs for twenty minutes and nobody reads `1,209.4 s` without
	 * reaching for a calculator.
	 *
	 * @param nanos the duration
	 * @return e.g. `12.4 s` for short durations, `0:20:09` for long ones
	 */
	@Nonnull
	private static String formatDuration(final long nanos) {
		final double seconds = nanos / 1_000_000_000.0;
		if (seconds < 60.0) {
			return String.format("%,.1f s", seconds);
		}
		final long totalSeconds = Math.round(seconds);
		return String.format(
			"%d:%02d:%02d", totalSeconds / 3_600, (totalSeconds % 3_600) / 60, totalSeconds % 60
		);
	}

	/**
	 * Formats a nanosecond duration as `H:mm:ss` followed by the raw seconds in parentheses. Used for the
	 * headline report figures, where the clock form is what gets read but the seconds are what gets
	 * compared against an earlier run.
	 *
	 * @param nanos the duration
	 * @return e.g. `0:20:09  (1,209.4 s)`
	 */
	@Nonnull
	private static String formatDurationWithSeconds(final long nanos) {
		final long totalSeconds = Math.round(nanos / 1_000_000_000.0);
		return String.format(
			"%d:%02d:%02d  (%,.1f s)",
			totalSeconds / 3_600, (totalSeconds % 3_600) / 60, totalSeconds % 60, nanos / 1_000_000_000.0
		);
	}

	/**
	 * Formats a nanosecond duration as microseconds.
	 *
	 * @param nanos the duration
	 * @return e.g. `1,234.5 us`
	 */
	@Nonnull
	private static String formatMicros(final long nanos) {
		return String.format("%,.1f us", nanos / 1_000.0);
	}

	/**
	 * Formats a byte count in gibibytes.
	 *
	 * @param bytes the byte count
	 * @return e.g. `14.0 GiB`
	 */
	@Nonnull
	private static String formatBytes(final long bytes) {
		return String.format("%.1f GiB", bytes / 1024.0 / 1024.0 / 1024.0);
	}

	/**
	 * Right-pads a string to the requested width (used for log alignment).
	 *
	 * @param value the string to pad
	 * @param width target width
	 * @return the padded string
	 */
	@Nonnull
	private static String padRight(@Nonnull final String value, final int width) {
		return value.length() >= width ? value : value + " ".repeat(width - value.length());
	}

	/**
	 * Growable, allocation-frugal collector of nanosecond latency samples. Single-threaded by construction -
	 * every sample is added by the one copying thread - so nothing here synchronizes.
	 */
	private static final class LatencySamples {
		private long[] samples;
		private int count;

		/**
		 * @param initialCapacity capacity to pre-size the backing array to
		 */
		LatencySamples(final int initialCapacity) {
			this.samples = new long[initialCapacity];
		}

		/**
		 * Records a single latency sample.
		 *
		 * @param nanos the measured duration in nanoseconds
		 */
		void add(final long nanos) {
			if (this.count == this.samples.length) {
				this.samples = Arrays.copyOf(this.samples, this.samples.length << 1);
			}
			this.samples[this.count++] = nanos;
		}

		/**
		 * @return number of samples collected so far
		 */
		int count() {
			return this.count;
		}

		/**
		 * Sorts a copy of the collected samples and derives the summary statistics from them.
		 *
		 * @return immutable summary of everything recorded
		 */
		@Nonnull
		LatencySummary summarize() {
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
		private static long percentile(@Nonnull final long[] sorted, final int percentile) {
			final int index = (int) Math.min(sorted.length - 1L, (percentile * (long) sorted.length) / 100L);
			return sorted[index];
		}
	}

	/**
	 * Summary statistics of one latency series, all durations in nanoseconds.
	 *
	 * @param count  number of samples
	 * @param min    smallest sample
	 * @param mean   arithmetic mean
	 * @param median 50th percentile
	 * @param p95    95th percentile
	 * @param p99    99th percentile
	 * @param max    largest sample
	 * @param sum    sum of all samples
	 */
	private record LatencySummary(
		int count, long min, long mean, long median, long p95, long p99, long max, long sum
	) {
	}

	/**
	 * Cumulative garbage-collection counters sampled from the platform MX beans of **this** JVM.
	 *
	 * @param collections total collections across all collectors
	 * @param millis      total time spent collecting, in milliseconds
	 */
	private record GcSnapshot(long collections, long millis) {

		/**
		 * @return the current cumulative GC counters of this JVM
		 */
		@Nonnull
		static GcSnapshot capture() {
			long collections = 0L;
			long millis = 0L;
			final List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
			for (int i = 0; i < beans.size(); i++) {
				final GarbageCollectorMXBean bean = beans.get(i);
				final long count = bean.getCollectionCount();
				final long time = bean.getCollectionTime();
				// a bean reports -1 when the counter is unavailable; ignore rather than corrupt the sum
				if (count > 0) {
					collections += count;
				}
				if (time > 0) {
					millis += time;
				}
			}
			return new GcSnapshot(collections, millis);
		}

		/**
		 * @param earlier the snapshot taken before the measured window
		 * @return the delta accumulated during the window
		 */
		@Nonnull
		GcSnapshot minus(@Nonnull final GcSnapshot earlier) {
			return new GcSnapshot(this.collections - earlier.collections, this.millis - earlier.millis);
		}
	}

	/**
	 * One collection's measurement.
	 *
	 * @param entities      entities upserted
	 * @param wallNanos     wall-clock of the whole collection loop (read + upsert + overhead)
	 * @param readNanos     time spent reading the entities out of the source catalog
	 * @param upsertLatency distribution of the individual upsert calls
	 */
	private record CollectionResult(
		long entities, long wallNanos, long readNanos, @Nonnull LatencySummary upsertLatency
	) {
	}

	/**
	 * The whole run's measurement, and the renderer of the final report.
	 *
	 * @param byCollection  per-collection measurements, in load order
	 * @param overallUpsert distribution of every upsert call across all collections
	 * @param copyWallNanos wall-clock of the whole load loop
	 * @param goLiveNanos   wall-clock of the WARM_UP to ALIVE transition (time-to-failure if it threw)
	 * @param goLiveError   {@code null} when the transition succeeded, otherwise the client-side failure
	 * @param gcDuringCopy  GC activity of THIS (reader) JVM during the measured window
	 * @param maxHeapBytes  this JVM's maximum heap
	 * @param usedHeapBytes this JVM's heap in use once the load finished
	 */
	private record CopyResult(
		@Nonnull Map<String, CollectionResult> byCollection,
		@Nonnull LatencySummary overallUpsert,
		long copyWallNanos,
		long goLiveNanos,
		@Nullable String goLiveError,
		@Nonnull GcSnapshot gcDuringCopy,
		long maxHeapBytes,
		long usedHeapBytes
	) {

		/**
		 * Renders the whole result as a human-readable report block.
		 *
		 * @param sourceCatalog name of the catalog that was read
		 * @param targetCatalog name of the catalog that was built
		 * @param targetMode    where the target lived
		 * @param targetHost    target server host (`remote` mode)
		 * @param targetPort    target server port (`remote` mode)
		 * @param batchSize     entities read per fetch
		 * @return the formatted report
		 */
		@Nonnull
		String format(
			@Nonnull final String sourceCatalog,
			@Nonnull final String targetCatalog,
			@Nonnull final TargetMode targetMode,
			@Nonnull final String targetHost,
			final int targetPort,
			final int batchSize
		) {
			long readNanos = 0L;
			for (final CollectionResult collectionResult : this.byCollection.values()) {
				readNanos += collectionResult.readNanos();
			}
			final long entities = this.overallUpsert.count();
			final long upsertNanos = this.overallUpsert.sum();
			final double copySeconds = this.copyWallNanos / 1_000_000_000.0;
			final double upsertSeconds = upsertNanos / 1_000_000_000.0;
			final double gcSeconds = this.gcDuringCopy.millis() / 1_000.0;
			final double gcShare = this.copyWallNanos == 0 ? 0.0 : 100.0 * gcSeconds / copySeconds;

			final StringBuilder report = new StringBuilder(4096);
			report.append("========================================================================\n");
			report.append("     SINGLE-THREADED WARM_UP BULK LOAD - RESULT\n");
			report.append("========================================================================\n");
			report.append(String.format("source catalog          : %s (embedded, this JVM)%n", sourceCatalog));
			report.append(String.format(
				"target catalog          : %s (%s)%n", targetCatalog,
				targetMode == TargetMode.REMOTE ?
					"remote server " + targetHost + ":" + targetPort + " over gRPC" : "embedded, this JVM"
			));
			report.append(String.format("entities upserted       : %,d%n", entities));
			report.append(String.format("read batch size         : %,d%n", batchSize));
			report.append(String.format(
				"reader JVM heap         : max %.1f GiB, used after load %.1f GiB%n",
				this.maxHeapBytes / 1024.0 / 1024.0 / 1024.0, this.usedHeapBytes / 1024.0 / 1024.0 / 1024.0
			));
			report.append("------------------------------------------------------------------------\n");
			report.append(String.format(
				"load wall-clock         : %s%n", formatDurationWithSeconds(this.copyWallNanos)
			));
			report.append(String.format(
				"  upsert (pure)         : %s  %.1f%% of wall%n",
				formatDurationWithSeconds(upsertNanos),
				copySeconds == 0 ? 0.0 : 100.0 * upsertSeconds / copySeconds
			));
			report.append(String.format(
				"  source read           : %s  %.1f%% of wall%n",
				formatDurationWithSeconds(readNanos),
				this.copyWallNanos == 0 ? 0.0 : 100.0 * readNanos / this.copyWallNanos
			));
			report.append(String.format(
				"goLive -> ALIVE         : %s%s%n",
				formatDurationWithSeconds(this.goLiveNanos),
				this.goLiveError == null ? "" : "   *** CLIENT-SIDE FAILURE (see below) ***"
			));
			report.append(String.format(
				"TOTAL (load + goLive)   : %s%n",
				formatDurationWithSeconds(this.copyWallNanos + this.goLiveNanos)
			));
			if (this.goLiveError != null) {
				report.append("  !! goLive failed CLIENT-side: ").append(this.goLiveError).append('\n');
				report.append("  !! the load figures above are unaffected; check the SERVER log for\n");
				report.append("  !! `is now alive!` - the transition may have completed regardless.\n");
			}
			report.append("------------------------------------------------------------------------\n");
			report.append(String.format(
				"throughput (pure upsert): %,10.1f upserts/s%n",
				upsertNanos == 0 ? 0.0 : entities * 1_000_000_000.0 / upsertNanos
			));
			report.append(String.format(
				"throughput (wall-clock) : %,10.1f entities/s%n",
				this.copyWallNanos == 0 ? 0.0 : entities * 1_000_000_000.0 / this.copyWallNanos
			));
			report.append("------------------------------------------------------------------------\n");
			report.append("per-upsert latency (microseconds)\n");
			report.append(String.format(
				"  %10s %10s %10s %10s %10s %10s%n", "mean", "median", "p95", "p99", "min", "max"
			));
			report.append(String.format(
				"  %10.1f %10.1f %10.1f %10.1f %10.1f %10.1f%n",
				this.overallUpsert.mean() / 1_000.0, this.overallUpsert.median() / 1_000.0,
				this.overallUpsert.p95() / 1_000.0, this.overallUpsert.p99() / 1_000.0,
				this.overallUpsert.min() / 1_000.0, this.overallUpsert.max() / 1_000.0
			));
			if (targetMode == TargetMode.REMOTE) {
				report.append(
					"  NOTE: remote mode - these include protobuf serialization, the loopback round trip and\n" +
						"  server-side deframing. This is end-to-end ingestion latency, NOT write-path latency.\n" +
						"  Run the same load with -Devita.warmup.targetMode=embedded to quantify the difference.\n"
				);
			}
			report.append("------------------------------------------------------------------------\n");
			report.append(String.format(
				"reader JVM GC           : %,d collections, %,.1f s (%.1f%% of load wall-clock)%n",
				this.gcDuringCopy.collections(), gcSeconds, gcShare
			));
			if (targetMode == TargetMode.REMOTE) {
				report.append("  (the WRITER's GC is what matters - read it from the server's own GC log)\n");
			} else if (gcShare > GC_BOUND_WARNING_THRESHOLD) {
				report.append(String.format(
					"  !! WARNING: GC consumed %.1f%% of the measured window. This run describes the%n" +
						"  !! collector more than it describes the write path - raise -Xmx and re-measure%n" +
						"  !! before drawing any conclusion from the numbers above.%n",
					gcShare
				));
			}
			report.append("------------------------------------------------------------------------\n");
			report.append("per-collection breakdown (slowest first)\n");
			report.append(String.format(
				"%-22s %12s %9s %9s %9s %10s %10s %10s %7s%n",
				"collection", "entities", "wall s", "read s", "upsert s",
				"mean us", "med us", "p99 us", "%wall"
			));
			final List<Map.Entry<String, CollectionResult>> sorted = new ArrayList<>(this.byCollection.entrySet());
			sorted.sort((a, b) -> Long.compare(b.getValue().wallNanos(), a.getValue().wallNanos()));
			for (final Map.Entry<String, CollectionResult> entry : sorted) {
				final CollectionResult value = entry.getValue();
				final LatencySummary latency = value.upsertLatency();
				report.append(String.format(
					"%-22s %,12d %9.1f %9.1f %9.1f %10.1f %10.1f %10.1f %6.1f%%%n",
					entry.getKey(), value.entities(),
					value.wallNanos() / 1_000_000_000.0,
					value.readNanos() / 1_000_000_000.0,
					latency.sum() / 1_000_000_000.0,
					latency.mean() / 1_000.0, latency.median() / 1_000.0, latency.p99() / 1_000.0,
					this.copyWallNanos == 0 ? 0.0 : 100.0 * value.wallNanos() / this.copyWallNanos
				));
			}
			report.append("========================================================================\n");
			return report.toString();
		}
	}

}
