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

package io.evitadb.spike;

import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.driver.EvitaClient;
import io.evitadb.driver.config.ClientTlsOptions;
import io.evitadb.driver.config.ClientTimeoutOptions;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.performance.warmupload.CatalogCopySupport;
import io.evitadb.test.builder.CopyExistingEntityBuilder;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;

/**
 * Single-threaded WARM_UP bulk-copy benchmark.
 *
 * Connects to a locally running evitaDB server over gRPC, reads every collection of a source catalog
 * (default `senesi`) and re-inserts an identical copy of every entity into a freshly created catalog
 * named `<source>_XXXX` (random 4-hex suffix). The target schema is faithfully reconstructed first (so
 * the write path exercises the same indexes as the original), then the whole dataset is copied on a
 * single thread while the catalog is in WARM_UP mode, and finally the catalog is switched to ALIVE via
 * `goLiveAndClose()`.
 *
 * The program reports the wall-clock time of the copy loop and the goLive transition separately, plus
 * the total. Schema reconstruction happens before the clock starts and is therefore not measured. The
 * copy-loop time necessarily includes the client-side read + gRPC round-trip overhead, which cannot be
 * separated from the WARM_UP write cost when driving the server through the remote driver.
 *
 * Usage: {@code WarmupCopyCatalogBenchmark [sourceCatalog] [host] [port] [maxPerCollection]}
 * (defaults: senesi localhost 5555 0). A non-zero {@code maxPerCollection} copies at most that many
 * entities per collection - intended only for a fast end-to-end smoke test; leave it {@code 0} for the
 * real, full-dataset measurement.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class WarmupCopyCatalogBenchmark {

	/**
	 * Number of entities read from the source (and upserted into the target) per gRPC round-trip. The
	 * reads are keyed by an explicit primary-key set so each fetch is an indexed lookup rather than a
	 * deep-pagination scan.
	 */
	private static final int BATCH_SIZE = 1_000;

	/**
	 * Upper bound for a single gRPC response frame (and the enclosing HTTP response) accepted by the client.
	 * Armeria's default is 10&nbsp;MB, which a full-content read of {@link #BATCH_SIZE} rich entities (e.g.
	 * senesi `Product` with prices and references) exceeds - the deframer then aborts the whole copy with
	 * `RESOURCE_EXHAUSTED: Frame size ... exceeds maximum: 10485760`. 256&nbsp;MB gives ample headroom while
	 * still guarding against a runaway response.
	 */
	private static final int MAX_GRPC_MESSAGE_SIZE = 256 * 1024 * 1024;

	/**
	 * Program entry point. See class-level documentation for the full description.
	 *
	 * @param args optional {@code [sourceCatalog] [host] [port]}
	 */
	public static void main(@Nonnull final String[] args) {
		// maintenance mode: `--delete <catalog> [host] [port]` drops a (throwaway) catalog through the API -
		// never delete a catalog directory on disk, that orphans the engine-level WAL registration
		if (args.length >= 2 && "--delete".equals(args[0])) {
			final String host = args.length > 2 ? args[2] : "localhost";
			final int port = args.length > 3 ? Integer.parseInt(args[3]) : 5555;
			deleteCatalog(args[1], host, port);
			return;
		}

		final String sourceCatalog = args.length > 0 ? args[0] : "senesi";
		final String host = args.length > 1 ? args[1] : "localhost";
		final int port = args.length > 2 ? Integer.parseInt(args[2]) : 5555;
		// 0 == unlimited (real measurement); a positive value caps entities per collection for smoke tests
		final int maxPerCollection = args.length > 3 ? Integer.parseInt(args[3]) : 0;
		final String targetCatalog = sourceCatalog + "_" + String.format("%04x", new Random().nextInt(0x1_0000));

		final EvitaClientConfiguration configuration = clientConfiguration(host, port);

		System.out.printf("Copying catalog `%s` -> `%s` at %s:%d%n", sourceCatalog, targetCatalog, host, port);

		// a full-content read of BATCH_SIZE rich entities easily exceeds Armeria's default 10 MB response
		// cap, so raise both the gRPC per-message and the HTTP response limits on the client stubs
		final Consumer<GrpcClientBuilder> grpcConfigurator = grpcClientBuilder -> grpcClientBuilder
			.maxResponseMessageLength(MAX_GRPC_MESSAGE_SIZE)
			.maxResponseLength(MAX_GRPC_MESSAGE_SIZE);

		try (final EvitaClient client = new EvitaClient(configuration, grpcConfigurator)) {
			try {
				runCopy(client, sourceCatalog, targetCatalog, maxPerCollection);
			} catch (RuntimeException ex) {
				// a partial copy leaves a half-populated target catalog behind; remove it through the API so
				// a failed run is not littering the storage directory with orphaned throwaway catalogs
				System.err.printf(
					"Copy failed (%s) - removing partial target catalog `%s` via API%n",
					ex.getMessage(), targetCatalog
				);
				try {
					client.deleteCatalogIfExists(targetCatalog);
				} catch (RuntimeException cleanupFailure) {
					System.err.printf("  cleanup of `%s` failed: %s%n", targetCatalog, cleanupFailure.getMessage());
				}
				throw ex;
			}
		}
	}

	/**
	 * Runs the actual copy: reads the source schema, reconstructs it on the freshly created target catalog,
	 * copies every entity of every collection on a single thread while the target is in WARM_UP mode, switches
	 * the target to ALIVE via `goLiveAndClose()`, verifies the result against the source and prints the report.
	 * Schema reconstruction happens before the clock starts and is therefore not part of the measured time.
	 *
	 * @param client           the shared gRPC client
	 * @param sourceCatalog    name of the catalog to read
	 * @param targetCatalog    name of the catalog to create and populate
	 * @param maxPerCollection maximum entities to copy per collection (0 == unlimited)
	 */
	private static void runCopy(
		@Nonnull final EvitaClient client,
		@Nonnull final String sourceCatalog,
		@Nonnull final String targetCatalog,
		final int maxPerCollection
	) {
		waitForCatalog(client, sourceCatalog);

		// ---- read the source schema (not timed) ----------------------------------------------
		final CatalogSchemaContract sourceCatalogSchema = read(
			client, sourceCatalog, EvitaSessionContract::getCatalogSchema
		);
		final List<String> entityTypes = new ArrayList<>(
			read(client, sourceCatalog, session -> new TreeSet<>(session.getAllEntityTypes()))
		);
		final Map<String, EntitySchemaContract> sourceEntitySchemas = new LinkedHashMap<>();
		for (final String entityType : entityTypes) {
			sourceEntitySchemas.put(
				entityType,
				read(client, sourceCatalog, session -> session.getEntitySchemaOrThrowException(entityType))
			);
		}
		System.out.printf("Source catalog has %d collections: %s%n", entityTypes.size(), entityTypes);

		// ---- reconstruct the target schema faithfully (not timed) ----------------------------
		client.defineCatalog(targetCatalog);
		replicateSchema(client, targetCatalog, sourceCatalogSchema, sourceEntitySchemas.values());
		System.out.println("Target schema reconstructed, starting single-threaded WARM_UP copy...");

		// ---- timed single-threaded copy + goLive ---------------------------------------------
		final long[] copyNanos = {0L};
		final long[] goLiveNanos = {0L};
		final long[] copied = {0L};
		// records how many entities were actually upserted per collection, for post-copy verification
		final Map<String, Long> copiedByType = new LinkedHashMap<>();
		// records the per-collection wall-clock (nanos) of the copy, for the final timing breakdown
		final Map<String, Long> nanosByType = new LinkedHashMap<>();
		client.updateCatalog(
			targetCatalog,
			session -> {
				final long copyStart = System.nanoTime();
				for (final String entityType : entityTypes) {
					final long collectionStart = System.nanoTime();
					final long count = copyCollection(
						client, sourceCatalog, sourceEntitySchemas.get(entityType), session, maxPerCollection
					);
					final long now = System.nanoTime();
					final long collectionNanos = now - collectionStart;
					copiedByType.put(entityType, count);
					nanosByType.put(entityType, collectionNanos);
					copied[0] += count;
					// per-collection progress line - prints per-collection AND cumulative time/count so an
					// interrupted run still leaves a usable "how far did we get, how long" trail in the log
					final double collectionSeconds = collectionNanos / 1_000_000_000.0;
					final double perEntityMicros = count == 0 ? 0.0 : collectionNanos / 1_000.0 / count;
					final double cumulativeSeconds = (now - copyStart) / 1_000_000_000.0;
					System.out.printf(
						"  copied %,10d %-20s in %8.1f s (%7.2f us/entity)  |  cumulative %,12d entities, %8.1f s%n",
						count, entityType, collectionSeconds, perEntityMicros, copied[0], cumulativeSeconds
					);
				}
				copyNanos[0] = System.nanoTime() - copyStart;

				final long goLiveStart = System.nanoTime();
				// switches the catalog from WARM_UP to ALIVE (also closes this session)
				session.goLiveAndClose();
				goLiveNanos[0] = System.nanoTime() - goLiveStart;
			}
		);

		// ---- verify the copy against the SOURCE (catalog is now ALIVE) -----------------------
		verifyCopy(client, sourceCatalog, targetCatalog, entityTypes, maxPerCollection, copiedByType);

		printReport(targetCatalog, copied[0], copyNanos[0], goLiveNanos[0], copiedByType, nanosByType);
	}

	/**
	 * Builds the client configuration used to connect to the local server. The server is expected to run with
	 * `tlsMode=RELAXED` (plaintext gRPC accepted) and the timeouts are deliberately generous because a bulk copy
	 * of a multi-GB catalog can take a long time.
	 *
	 * @param host server host
	 * @param port server gRPC (and system API) port
	 * @return the assembled client configuration
	 */
	@Nonnull
	private static EvitaClientConfiguration clientConfiguration(@Nonnull final String host, final int port) {
		return EvitaClientConfiguration.builder()
			.host(host)
			.port(port)
			.systemApiPort(port)
			.tls(
				ClientTlsOptions.builder()
					.tlsEnabled(false)
					.mtlsEnabled(false)
					.build()
			)
			.timeouts(
				ClientTimeoutOptions.builder()
					.timeout(1, TimeUnit.HOURS)
					.streamingTimeout(1, TimeUnit.HOURS)
					.build()
			)
			.build();
	}

	/**
	 * Maintenance helper: removes a catalog through the API (`deleteCatalogIfExists`). This is the only correct
	 * way to drop a throwaway catalog (e.g. a `<source>_XXXX` copy) - deleting its directory on disk would orphan
	 * the engine-level WAL registration and break catalog listing on the next boot.
	 *
	 * @param catalog the catalog to remove
	 * @param host    server host
	 * @param port    server gRPC (and system API) port
	 */
	private static void deleteCatalog(@Nonnull final String catalog, @Nonnull final String host, final int port) {
		try (final EvitaClient client = new EvitaClient(clientConfiguration(host, port))) {
			client.deleteCatalogIfExists(catalog);
			System.out.printf("Deleted catalog `%s` (if it existed) at %s:%d%n", catalog, host, port);
		}
	}

	/**
	 * Verifies the copy is complete by comparing, per collection, the entity count in the (ALIVE) target
	 * catalog against the authoritative count in the source catalog - re-queried here independently of the
	 * copy loop, so a silently-short read (e.g. a paging bug in the primary-key fetch) is caught rather than
	 * masked. For an uncapped run the target must equal the source exactly; for a capped smoke run
	 * (`maxPerCollection > 0`) the expected count is `min(source, cap)`. The internal copied counter is also
	 * cross-checked against the same expectation. Any discrepancy fails the benchmark loudly.
	 *
	 * @param client           the shared gRPC client
	 * @param sourceCatalog    name of the catalog that was read
	 * @param targetCatalog    name of the freshly created copy
	 * @param entityTypes      all collections that were copied
	 * @param maxPerCollection the per-collection cap that was applied (0 == uncapped)
	 * @param copiedByType     number of entities the copy loop reported upserting per collection
	 */
	private static void verifyCopy(
		@Nonnull final EvitaClient client,
		@Nonnull final String sourceCatalog,
		@Nonnull final String targetCatalog,
		@Nonnull final List<String> entityTypes,
		final int maxPerCollection,
		@Nonnull final Map<String, Long> copiedByType
	) {
		final StringBuilder mismatches = new StringBuilder(128);
		System.out.printf("%-22s %12s %12s %12s%n", "collection", "source", "target", "copied");
		for (final String entityType : entityTypes) {
			final int sourceCount = countEntities(client, sourceCatalog, entityType);
			final int targetCount = countEntities(client, targetCatalog, entityType);
			final long copied = copiedByType.getOrDefault(entityType, 0L);
			// uncapped: expect the full source count; capped smoke: expect min(source, cap)
			final long expected = maxPerCollection > 0
				? Math.min(sourceCount, maxPerCollection)
				: sourceCount;
			System.out.printf("%-22s %12d %12d %12d%n", entityType, sourceCount, targetCount, copied);
			if (targetCount != expected || copied != expected) {
				mismatches.append(System.lineSeparator())
					.append("  ").append(entityType)
					.append(": expected ").append(expected)
					.append(" (source=").append(sourceCount)
					.append("), target=").append(targetCount)
					.append(", copied=").append(copied);
			}
		}
		if (mismatches.length() > 0) {
			throw new IllegalStateException("Copy verification FAILED - entity counts differ:" + mismatches);
		}
		System.out.println(
			maxPerCollection > 0
				? "Copy verified: target matches min(source, cap) for every collection."
				: "Copy verified: target entity counts match the source exactly for every collection."
		);
	}

	/**
	 * Returns the total number of entities of the given collection in the given catalog via a cheap
	 * reference-only count query.
	 *
	 * @param client      the shared gRPC client
	 * @param catalogName the catalog to count in
	 * @param entityType  the collection to count
	 * @return total entity count of the collection
	 */
	private static int countEntities(
		@Nonnull final EvitaClient client,
		@Nonnull final String catalogName,
		@Nonnull final String entityType
	) {
		return read(
			client,
			catalogName,
			session -> session.queryEntityReference(
				Query.query(collection(entityType), require(page(1, 1)))
			).getTotalRecordCount()
		);
	}

	/**
	 * Copies every entity of a single collection from the source catalog into the (WARM_UP) target
	 * session. Primary keys are gathered once via a cheap reference-only query, then entities are fetched
	 * with full content in {@link #BATCH_SIZE}-sized primary-key batches to keep each read an indexed
	 * lookup instead of a deep-pagination scan.
	 *
	 * @param client           the shared gRPC client
	 * @param sourceCatalog    name of the catalog being read
	 * @param schema           the source schema of the collection to copy
	 * @param targetSession    the open WARM_UP session of the target catalog to upsert into
	 * @param maxPerCollection maximum entities to copy (0 == unlimited; positive caps for smoke tests)
	 * @return number of entities copied
	 */
	private static long copyCollection(
		@Nonnull final EvitaClient client,
		@Nonnull final String sourceCatalog,
		@Nonnull final EntitySchemaContract schema,
		@Nonnull final EvitaSessionContract targetSession,
		final int maxPerCollection
	) {
		final String entityType = schema.getName();
		// reflected references are read-only projections auto-maintained from the owning (plain) reference on
		// the other entity; they must not be fetched or written here - copying the plain reference rebuilds them
		final EntityFetch contentRequirement = CatalogCopySupport.buildContentRequirement(schema);
		int[] primaryKeys = fetchAllPrimaryKeys(client, sourceCatalog, entityType);
		if (maxPerCollection > 0 && primaryKeys.length > maxPerCollection) {
			final int[] capped = new int[maxPerCollection];
			System.arraycopy(primaryKeys, 0, capped, 0, maxPerCollection);
			primaryKeys = capped;
		}
		long copied = 0L;
		for (int offset = 0; offset < primaryKeys.length; offset += BATCH_SIZE) {
			final int limit = Math.min(BATCH_SIZE, primaryKeys.length - offset);
			final Integer[] batch = new Integer[limit];
			for (int i = 0; i < limit; i++) {
				batch[i] = primaryKeys[offset + i];
			}
			final List<SealedEntity> entities = read(
				client,
				sourceCatalog,
				session -> session.queryListOfSealedEntities(
					Query.query(
						collection(entityType),
						filterBy(entityPrimaryKeyInSet(batch)),
						require(
							page(1, batch.length),
							contentRequirement
						)
					)
				)
			);
			for (int i = 0; i < entities.size(); i++) {
				targetSession.upsertEntity(new CopyExistingEntityBuilder(entities.get(i)));
			}
			copied += entities.size();
		}
		return copied;
	}

	/**
	 * Returns the complete set of primary keys of the given collection in a single reference-only query
	 * (no entity bodies fetched), so subsequent full-content reads can be batched by primary key.
	 *
	 * @param client        the shared gRPC client
	 * @param sourceCatalog name of the catalog being read
	 * @param entityType    the collection whose primary keys are requested
	 * @return array of all primary keys of the collection
	 */
	private static int[] fetchAllPrimaryKeys(
		@Nonnull final EvitaClient client,
		@Nonnull final String sourceCatalog,
		@Nonnull final String entityType
	) {
		final int total = read(
			client,
			sourceCatalog,
			session -> session.queryEntityReference(
				Query.query(collection(entityType), require(page(1, 1)))
			).getTotalRecordCount()
		);
		if (total == 0) {
			return new int[0];
		}
		final List<EntityReferenceContract> references = read(
			client,
			sourceCatalog,
			session -> session.queryListOfEntityReferences(
				Query.query(collection(entityType), require(page(1, total)))
			)
		);
		final int[] primaryKeys = new int[references.size()];
		for (int i = 0; i < references.size(); i++) {
			primaryKeys[i] = references.get(i).getPrimaryKey();
		}
		return primaryKeys;
	}

	/**
	 * Blocks until the source catalog is present and answers a trivial query, tolerating the window in
	 * which the server has opened its API port but not yet finished loading the (multi-GB) catalog.
	 *
	 * @param client        the shared gRPC client
	 * @param sourceCatalog name of the catalog to wait for
	 */
	private static void waitForCatalog(@Nonnull final EvitaClient client, @Nonnull final String sourceCatalog) {
		for (int attempt = 0; attempt < 600; attempt++) {
			try {
				if (client.getCatalogNames().contains(sourceCatalog)) {
					// confirm the catalog actually answers (fully loaded)
					read(client, sourceCatalog, EvitaSessionContract::getAllEntityTypes);
					return;
				}
			} catch (final Exception ex) {
				// server still starting up / catalog still loading - retry below
			}
			try {
				Thread.sleep(1_000L);
			} catch (final InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting for catalog `" + sourceCatalog + "`.");
			}
		}
		throw new IllegalStateException("Source catalog `" + sourceCatalog + "` did not become available in time.");
	}

	/**
	 * Prints the final wall-clock report to stdout.
	 *
	 * @param targetCatalog name of the freshly created copy
	 * @param copied        number of entities copied
	 * @param copyNanos     wall-clock nanos of the copy loop
	 * @param goLiveNanos   wall-clock nanos of the goLive transition
	 * @param copiedByType  number of entities copied per collection
	 * @param nanosByType   wall-clock nanos of the copy per collection
	 */
	private static void printReport(
		@Nonnull final String targetCatalog,
		final long copied,
		final long copyNanos,
		final long goLiveNanos,
		@Nonnull final Map<String, Long> copiedByType,
		@Nonnull final Map<String, Long> nanosByType
	) {
		final double copyMs = copyNanos / 1_000_000.0;
		final double goLiveMs = goLiveNanos / 1_000_000.0;
		final double totalMs = copyMs + goLiveMs;
		final double perEntityMicros = copied == 0 ? 0.0 : copyNanos / 1_000.0 / copied;
		System.out.println("========================================================================");
		System.out.printf("Target catalog          : %s%n", targetCatalog);
		System.out.printf("Entities copied         : %,d%n", copied);
		System.out.printf("WARM_UP copy (1 thread) : %,.1f ms (%.1f s / %.1f min)%n", copyMs, copyMs / 1_000.0, copyMs / 60_000.0);
		System.out.printf("  per entity            : %.2f us%n", perEntityMicros);
		System.out.printf("goLive -> ALIVE         : %,.1f ms (%.1f s)%n", goLiveMs, goLiveMs / 1_000.0);
		System.out.printf("TOTAL                   : %,.1f ms (%.1f s / %.1f min)%n", totalMs, totalMs / 1_000.0, totalMs / 60_000.0);
		System.out.println("------------------------------------------------------------------------");
		// per-collection breakdown, slowest first - this is where the bulk-copy time actually goes
		System.out.printf("%-22s %12s %10s %12s %7s%n", "collection", "entities", "seconds", "us/entity", "%copy");
		final List<Map.Entry<String, Long>> byNanosDesc = new ArrayList<>(nanosByType.entrySet());
		byNanosDesc.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
		for (final Map.Entry<String, Long> entry : byNanosDesc) {
			final String entityType = entry.getKey();
			final long nanos = entry.getValue();
			final long count = copiedByType.getOrDefault(entityType, 0L);
			final double seconds = nanos / 1_000_000_000.0;
			final double perEntity = count == 0 ? 0.0 : nanos / 1_000.0 / count;
			final double pctOfCopy = copyNanos == 0 ? 0.0 : 100.0 * nanos / copyNanos;
			System.out.printf("%-22s %,12d %10.1f %12.2f %6.1f%%%n", entityType, count, seconds, perEntity, pctOfCopy);
		}
		System.out.println("========================================================================");
	}

	// ============================================================================================
	// Schema replication
	// ============================================================================================

	/**
	 * Reconstructs the source schema on the target catalog through a single `updateCatalog` call. The
	 * replication rules themselves live in {@link CatalogCopySupport#replicateSchema} - they are shared
	 * with {@link io.evitadb.performance.warmupload.IsolatedWarmupLoadBenchmark}, were arrived at
	 * empirically, and must not be duplicated: a second copy silently stops receiving the fixes the
	 * first one gets.
	 *
	 * @param client              the shared gRPC client
	 * @param targetCatalog       name of the target catalog (already defined, in WARM_UP)
	 * @param sourceCatalogSchema the source catalog schema (for global attributes)
	 * @param sourceEntitySchemas the source entity schemas to reproduce
	 */
	private static void replicateSchema(
		@Nonnull final EvitaClient client,
		@Nonnull final String targetCatalog,
		@Nonnull final CatalogSchemaContract sourceCatalogSchema,
		@Nonnull final Collection<EntitySchemaContract> sourceEntitySchemas
	) {
		// the lambda must keep a block body: an expression-bodied implicitly-typed lambda is potentially
		// compatible with BOTH the `Consumer` and the `Function` overload of `updateCatalog`, which the
		// compiler rejects as ambiguous. A block that is void-compatible only resolves to the `Consumer`
		// overload - the same reason `read` below funnels its query lambdas through an explicit `Function`.
		client.updateCatalog(
			targetCatalog,
			session -> {
				CatalogCopySupport.replicateSchema(session, sourceCatalogSchema, sourceEntitySchemas);
			}
		);
	}

	/**
	 * Runs a read-only query lambda against the given catalog. Funnels every value-returning
	 * `queryCatalog` call through an explicitly-typed {@link Function} so the compiler does not treat it
	 * as ambiguous between the `Function` and `Consumer` overloads of
	 * {@link EvitaClient#queryCatalog(String, Function, io.evitadb.api.SessionTraits.SessionFlags...)}.
	 *
	 * @param client  the shared gRPC client
	 * @param catalog name of the catalog to read from
	 * @param logic   read-only session logic returning a value
	 * @param <T>     result type
	 * @return the value produced by the logic
	 */
	private static <T> T read(
		@Nonnull final EvitaClient client,
		@Nonnull final String catalog,
		@Nonnull final Function<EvitaSessionContract, T> logic
	) {
		return client.queryCatalog(catalog, logic);
	}

}
