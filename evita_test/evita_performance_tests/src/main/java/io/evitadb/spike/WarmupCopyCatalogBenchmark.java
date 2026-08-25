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
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor.CatalogSchemaBuilder;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.builder.SortableAttributeCompoundSchemaBuilder;
import io.evitadb.dataType.Scope;
import io.evitadb.driver.EvitaClient;
import io.evitadb.driver.config.ClientTlsOptions;
import io.evitadb.driver.config.ClientTimeoutOptions;
import io.evitadb.driver.config.EvitaClientConfiguration;
import io.evitadb.test.builder.CopyExistingEntityBuilder;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static io.evitadb.api.query.QueryConstraints.associatedDataContentAll;
import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.dataInLocalesAll;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.entityFetchAll;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.hierarchyContent;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.priceContentAll;
import static io.evitadb.api.query.QueryConstraints.referenceContentWithAttributes;
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
	 * Shared empty array for the common case of a collection with nothing to strip before upserting.
	 */
	private static final String[] EMPTY_STRING_ARRAY = new String[0];

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
		// the other entity; they must not be written here - copying the plain reference rebuilds them
		final CollectionCopyPlan copyPlan = buildCopyPlan(schema);
		final EntityFetch contentRequirement = copyPlan.contentRequirement();
		final String[] referencesToStrip = copyPlan.referencesToStrip();
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
				final EntityBuilder entityBuilder = new CopyExistingEntityBuilder(entities.get(i));
				// drop the read-only projections that had to be fetched to keep `getReferences()` legal
				for (int j = 0; j < referencesToStrip.length; j++) {
					entityBuilder.removeReferences(referencesToStrip[j]);
				}
				targetSession.upsertEntity(entityBuilder);
			}
			copied += entities.size();
		}
		return copied;
	}

	/**
	 * Decides how one collection has to be read and what has to be dropped before its entities are written
	 * into the target catalog. Reflected references are read-only projections rebuilt automatically from the
	 * owning (plain) reference on the other entity, so they must never be written - but they cannot simply be
	 * left unfetched either, because {@link CopyExistingEntityBuilder} reads `getReferences()` unconditionally
	 * and an entity fetched without ANY reference requirement throws
	 * {@link io.evitadb.api.exception.ContextMissingException} the moment it is asked for them.
	 *
	 * That gives three cases:
	 *
	 * - **no reflected references** - {@link io.evitadb.api.query.QueryConstraints#entityFetchAll()
	 *   entityFetchAll()}, complete and future-proof, nothing to strip;
	 * - **reflected AND plain references** - an equivalent fetch naming only the plain references, so the
	 *   projections are never transferred over the wire at all and nothing has to be stripped;
	 * - **reflected references ONLY** - there is no plain reference to name, and a `referenceContent` naming
	 *   nothing means "all". So everything is fetched and the projections are stripped from the builder
	 *   instead. Such collections are the tail of a catalog (the demo dataset's `ParameterGroup` is one), so
	 *   the wasted transfer is not worth a more elaborate mechanism.
	 *
	 * @param schema the source schema of the collection
	 * @return the fetch to use and the reference names to remove before upserting
	 */
	@Nonnull
	private static CollectionCopyPlan buildCopyPlan(@Nonnull final EntitySchemaContract schema) {
		final List<String> reflectedNames = new ArrayList<>(4);
		final List<String> plainNames = new ArrayList<>(schema.getReferences().size());
		for (final ReferenceSchemaContract reference : schema.getReferences().values()) {
			if (reference instanceof ReflectedReferenceSchemaContract) {
				reflectedNames.add(reference.getName());
			} else {
				plainNames.add(reference.getName());
			}
		}
		if (reflectedNames.isEmpty()) {
			return new CollectionCopyPlan(entityFetchAll(), EMPTY_STRING_ARRAY);
		}
		if (plainNames.isEmpty()) {
			return new CollectionCopyPlan(entityFetchAll(), reflectedNames.toArray(new String[0]));
		}
		final List<EntityContentRequire> requirements = new ArrayList<>();
		requirements.add(attributeContentAll());
		requirements.add(associatedDataContentAll());
		requirements.add(dataInLocalesAll());
		if (schema.isWithPrice()) {
			requirements.add(priceContentAll());
		}
		if (schema.isWithHierarchy()) {
			requirements.add(hierarchyContent());
		}
		for (int i = 0; i < plainNames.size(); i++) {
			requirements.add(referenceContentWithAttributes(plainNames.get(i)));
		}
		return new CollectionCopyPlan(
			entityFetch(requirements.toArray(new EntityContentRequire[0])), EMPTY_STRING_ARRAY
		);
	}

	/**
	 * How one collection is read and cleaned up on its way into the target catalog.
	 *
	 * @param contentRequirement the fetch requirement to read source entities with
	 * @param referencesToStrip  reference names that were fetched but must not be written - always the
	 *                           read-only reflected projections, never a plain reference
	 */
	private record CollectionCopyPlan(
		@Nonnull EntityFetch contentRequirement,
		@Nonnull String[] referencesToStrip
	) {
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
	 * Reconstructs the source schema on the target catalog. Global (catalog-level) attributes are created
	 * first, then entity schemas are created in three passes so cross-collection references resolve: (1)
	 * everything except references, (2) plain references, (3) reflected references (which need the plain
	 * reference they reflect to already exist on the target entity).
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
		client.updateCatalog(
			targetCatalog,
			session -> {
				replicateGlobalAttributes(sourceCatalogSchema, session);
				// pass 1 - entity bodies without references
				for (final EntitySchemaContract source : sourceEntitySchemas) {
					final EntitySchemaBuilder builder = session.defineEntitySchema(source.getName());
					replicateEntityBase(source, builder);
					builder.updateVia(session);
				}
				// pass 2 - plain references
				for (final EntitySchemaContract source : sourceEntitySchemas) {
					final EntitySchemaBuilder builder = session.defineEntitySchema(source.getName());
					replicatePlainReferences(source, builder);
					builder.updateVia(session);
				}
				// pass 3 - reflected references
				for (final EntitySchemaContract source : sourceEntitySchemas) {
					final EntitySchemaBuilder builder = session.defineEntitySchema(source.getName());
					replicateReflectedReferences(source, builder);
					builder.updateVia(session);
				}
			}
		);
	}

	/**
	 * Copies every catalog-level global attribute (including its global-uniqueness settings) onto the
	 * target catalog schema.
	 *
	 * @param sourceCatalogSchema the source catalog schema
	 * @param session             open WARM_UP session of the target catalog
	 */
	private static void replicateGlobalAttributes(
		@Nonnull final CatalogSchemaContract sourceCatalogSchema,
		@Nonnull final EvitaSessionContract session
	) {
		if (sourceCatalogSchema.getAttributes().isEmpty()) {
			return;
		}
		final CatalogSchemaBuilder builder = session.getCatalogSchema().openForWrite();
		for (final GlobalAttributeSchemaContract attribute : sourceCatalogSchema.getAttributes().values()) {
			builder.withAttribute(
				attribute.getName(),
				attribute.getType(),
				editor -> {
					applyAttribute(attribute, editor);
					final List<Scope> uniqueGlobally = new ArrayList<>(2);
					final List<Scope> uniqueGloballyWithinLocale = new ArrayList<>(2);
					for (final Scope scope : Scope.values()) {
						final GlobalAttributeUniquenessType type = attribute.getGlobalUniquenessType(scope);
						if (type == GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG) {
							uniqueGlobally.add(scope);
						} else if (type == GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG_LOCALE) {
							uniqueGloballyWithinLocale.add(scope);
						}
					}
					if (!uniqueGlobally.isEmpty()) {
						editor.uniqueGloballyInScope(uniqueGlobally.toArray(new Scope[0]));
					}
					if (!uniqueGloballyWithinLocale.isEmpty()) {
						editor.uniqueGloballyWithinLocaleInScope(uniqueGloballyWithinLocale.toArray(new Scope[0]));
					}
				}
			);
		}
		builder.updateVia(session);
	}

	/**
	 * Reproduces the primary-key strategy, hierarchy, price handling, locales, evolution mode, attributes,
	 * associated data and (entity-level) sortable attribute compounds of the source entity schema - but no
	 * references.
	 *
	 * @param source  the source entity schema
	 * @param builder the target entity schema builder
	 */
	private static void replicateEntityBase(
		@Nonnull final EntitySchemaContract source,
		@Nonnull final EntitySchemaBuilder builder
	) {
		final Set<EvolutionMode> evolutionModes = source.getEvolutionMode();
		if (evolutionModes.isEmpty()) {
			builder.verifySchemaStrictly();
		} else {
			builder.verifySchemaButAllow(evolutionModes.toArray(new EvolutionMode[0]));
		}

		if (source.isWithGeneratedPrimaryKey()) {
			builder.withGeneratedPrimaryKey();
		} else {
			builder.withoutGeneratedPrimaryKey();
		}

		if (source.isWithHierarchy()) {
			final Scope[] hierarchyScopes = scopesWhere(source::isHierarchyIndexedInScope);
			if (hierarchyScopes.length > 0) {
				builder.withHierarchyIndexedInScope(hierarchyScopes);
			} else {
				builder.withHierarchy();
			}
		}

		if (source.isWithPrice()) {
			final Scope[] priceScopes = scopesWhere(source::isPriceIndexedInScope);
			final Scope[] effectivePriceScopes = priceScopes.length > 0 ? priceScopes : new Scope[]{Scope.DEFAULT_SCOPE};
			final int indexedPricePlaces = source.getIndexedPricePlaces();
			final Currency[] currencies = source.getCurrencies().toArray(new Currency[0]);
			if (currencies.length > 0) {
				builder.withPriceInCurrencyIndexedInScope(indexedPricePlaces, currencies, effectivePriceScopes);
			} else {
				builder.withPriceIndexedInScope(indexedPricePlaces, effectivePriceScopes);
			}
		}

		if (!source.getLocales().isEmpty()) {
			builder.withLocale(source.getLocales().toArray(new Locale[0]));
		}

		for (final AttributeSchemaContract attribute : source.getAttributes().values()) {
			if (attribute instanceof GlobalAttributeSchemaContract) {
				// global attributes are defined at catalog level - just reference them here
				builder.withGlobalAttribute(attribute.getName());
			} else {
				builder.withAttribute(
					attribute.getName(),
					attribute.getType(),
					editor -> applyAttribute(attribute, editor)
				);
			}
		}

		for (final AssociatedDataSchemaContract associatedData : source.getAssociatedData().values()) {
			builder.withAssociatedData(
				associatedData.getName(),
				associatedData.getType(),
				editor -> {
					if (associatedData.isLocalized()) {
						editor.localized();
					}
					if (associatedData.isNullable()) {
						editor.nullable();
					}
				}
			);
		}

		for (final SortableAttributeCompoundSchemaContract compound : source.getSortableAttributeCompounds().values()) {
			applySortableAttributeCompound(compound, builder::withSortableAttributeCompound);
		}
	}

	/**
	 * Adds the plain (non-reflected) references of the source entity schema to the target builder,
	 * reproducing cardinality, group type, per-scope index type, faceting, reference attributes and
	 * reference-level sortable attribute compounds.
	 *
	 * @param source  the source entity schema
	 * @param builder the target entity schema builder
	 */
	private static void replicatePlainReferences(
		@Nonnull final EntitySchemaContract source,
		@Nonnull final EntitySchemaBuilder builder
	) {
		for (final ReferenceSchemaContract reference : source.getReferences().values()) {
			if (reference instanceof ReflectedReferenceSchemaContract) {
				continue;
			}
			if (reference.isReferencedEntityTypeManaged()) {
				builder.withReferenceToEntity(
					reference.getName(),
					reference.getReferencedEntityType(),
					reference.getCardinality(),
					editor -> applyReference(reference, editor)
				);
			} else {
				builder.withReferenceTo(
					reference.getName(),
					reference.getReferencedEntityType(),
					reference.getCardinality(),
					editor -> applyReference(reference, editor)
				);
			}
		}
	}

	/**
	 * Adds the reflected references of the source entity schema to the target builder, reproducing the
	 * reflected reference name, cardinality, faceting and attribute-inheritance behaviour.
	 *
	 * @param source  the source entity schema
	 * @param builder the target entity schema builder
	 */
	private static void replicateReflectedReferences(
		@Nonnull final EntitySchemaContract source,
		@Nonnull final EntitySchemaBuilder builder
	) {
		for (final ReferenceSchemaContract reference : source.getReferences().values()) {
			if (!(reference instanceof ReflectedReferenceSchemaContract reflected)) {
				continue;
			}
			builder.withReflectedReferenceToEntity(
				reflected.getName(),
				reflected.getReferencedEntityType(),
				reflected.getReflectedReferenceName(),
				editor -> {
					// Only override what the reflected reference does NOT inherit from its target reference.
					// Re-declaring an inherited property (cardinality, faceting) or an inherited attribute is
					// rejected by the engine ("... is inherited ... but it is already defined!"). A freshly
					// created reflected reference already defaults to inherited cardinality, so we set it only
					// when it is explicitly overridden - calling withCardinalityInherited() here would emit a
					// ModifyReferenceSchemaCardinalityMutation(null) that the WAL serializer cannot write.
					if (!reflected.isCardinalityInherited()) {
						editor.withCardinality(reflected.getCardinality());
					}
					// attribute-inheritance behaviour governs which target-reference attributes are inherited;
					// the inherited attributes themselves must NOT be re-declared here
					final AttributeInheritanceBehavior behavior = reflected.getAttributesInheritanceBehavior();
					final String[] inheritanceFilter = reflected.getAttributeInheritanceFilter();
					if (behavior == AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED) {
						editor.withAttributesInherited(inheritanceFilter);
					} else {
						editor.withAttributesInheritedExcept(inheritanceFilter);
					}
					if (!reflected.isFacetedInherited()) {
						final Scope[] facetedScopes = scopesWhere(reflected::isFacetedInScope);
						if (facetedScopes.length > 0) {
							editor.facetedInScope(facetedScopes);
						}
					}
				}
			);
		}
	}

	/**
	 * Applies the group type, per-scope index type, faceting, attributes and sortable attribute compounds
	 * of a plain reference onto its target reference builder.
	 *
	 * @param reference the source reference schema
	 * @param editor    the target reference builder
	 */
	private static void applyReference(
		@Nonnull final ReferenceSchemaContract reference,
		@Nonnull final ReferenceSchemaBuilder editor
	) {
		if (reference.getReferencedGroupType() != null) {
			if (reference.isReferencedGroupTypeManaged()) {
				editor.withGroupTypeRelatedToEntity(reference.getReferencedGroupType());
			} else {
				editor.withGroupType(reference.getReferencedGroupType());
			}
		}

		final List<Scope> forFiltering = new ArrayList<>(2);
		final List<Scope> forFilteringAndPartitioning = new ArrayList<>(2);
		for (final Scope scope : Scope.values()) {
			switch (reference.getReferenceIndexType(scope)) {
				case FOR_FILTERING -> forFiltering.add(scope);
				case FOR_FILTERING_AND_PARTITIONING -> forFilteringAndPartitioning.add(scope);
				case NONE -> {
					// not indexed in this scope - nothing to do
				}
			}
		}
		if (!forFiltering.isEmpty()) {
			editor.indexedForFilteringInScope(forFiltering.toArray(new Scope[0]));
		}
		if (!forFilteringAndPartitioning.isEmpty()) {
			editor.indexedForFilteringAndPartitioningInScope(forFilteringAndPartitioning.toArray(new Scope[0]));
		}

		final Scope[] facetedScopes = scopesWhere(reference::isFacetedInScope);
		if (facetedScopes.length > 0) {
			editor.facetedInScope(facetedScopes);
		}

		for (final AttributeSchemaContract attribute : reference.getAttributes().values()) {
			editor.withAttribute(
				attribute.getName(),
				attribute.getType(),
				attributeEditor -> applyAttribute(attribute, attributeEditor)
			);
		}

		for (final SortableAttributeCompoundSchemaContract compound : reference.getSortableAttributeCompounds().values()) {
			applySortableAttributeCompound(compound, editor::withSortableAttributeCompound);
		}
	}

	/**
	 * Copies all common attribute flags (localized, nullable, representative, default value, indexed
	 * decimal places, per-scope filterable/sortable and per-scope collection-level uniqueness) from the
	 * source attribute onto the given editor. Works for entity, reference and global attribute editors
	 * alike.
	 *
	 * @param attribute the source attribute schema
	 * @param editor    the target attribute editor
	 */
	private static void applyAttribute(
		@Nonnull final AttributeSchemaContract attribute,
		@Nonnull final AttributeSchemaEditor<?> editor
	) {
		if (attribute.isLocalized()) {
			editor.localized();
		}
		if (attribute.isNullable()) {
			editor.nullable();
		}
		if (attribute.isRepresentative()) {
			editor.representative();
		}
		if (attribute.getDefaultValue() != null) {
			editor.withDefaultValue(attribute.getDefaultValue());
		}
		if (attribute.getIndexedDecimalPlaces() > 0) {
			editor.indexDecimalPlaces(attribute.getIndexedDecimalPlaces());
		}

		final Scope[] filterableScopes = scopesWhere(attribute::isFilterableInScope);
		if (filterableScopes.length > 0) {
			editor.filterableInScope(filterableScopes);
		}
		final Scope[] sortableScopes = scopesWhere(attribute::isSortableInScope);
		if (sortableScopes.length > 0) {
			editor.sortableInScope(sortableScopes);
		}

		final List<Scope> unique = new ArrayList<>(2);
		final List<Scope> uniqueWithinLocale = new ArrayList<>(2);
		for (final Scope scope : Scope.values()) {
			final AttributeUniquenessType type = attribute.getUniquenessType(scope);
			if (type == AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION) {
				unique.add(scope);
			} else if (type == AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE) {
				uniqueWithinLocale.add(scope);
			}
		}
		if (!unique.isEmpty()) {
			editor.uniqueInScope(unique.toArray(new Scope[0]));
		}
		if (!uniqueWithinLocale.isEmpty()) {
			editor.uniqueWithinLocaleInScope(uniqueWithinLocale.toArray(new Scope[0]));
		}
	}

	/**
	 * Recreates a sortable attribute compound (its ordered attribute elements and per-scope indexing)
	 * through the supplied factory - which is either the entity or the reference builder's
	 * {@code withSortableAttributeCompound} method.
	 *
	 * @param compound the source sortable attribute compound
	 * @param factory  builder method that registers the compound
	 */
	private static void applySortableAttributeCompound(
		@Nonnull final SortableAttributeCompoundSchemaContract compound,
		@Nonnull final SortableAttributeCompoundFactory factory
	) {
		final List<AttributeElement> elements = compound.getAttributeElements();
		factory.create(
			compound.getName(),
			elements.toArray(new AttributeElement[0]),
			editor -> {
				final Scope[] indexedScopes = scopesWhere(compound::isIndexedInScope);
				if (indexedScopes.length > 0) {
					editor.indexedInScope(indexedScopes);
				}
			}
		);
	}

	/**
	 * Returns the scopes for which the supplied predicate holds.
	 *
	 * @param predicate scope test
	 * @return matching scopes (never null, possibly empty)
	 */
	@Nonnull
	private static Scope[] scopesWhere(@Nonnull final Predicate<Scope> predicate) {
		final List<Scope> matching = new ArrayList<>(Scope.values().length);
		for (final Scope scope : Scope.values()) {
			if (predicate.test(scope)) {
				matching.add(scope);
			}
		}
		return matching.toArray(new Scope[0]);
	}

	/**
	 * Narrow functional bridge for the two {@code withSortableAttributeCompound(name, elements, whichIs)}
	 * builder methods (entity-level and reference-level), which share an identical signature but live on
	 * unrelated editor interfaces.
	 */
	@FunctionalInterface
	private interface SortableAttributeCompoundFactory {

		/**
		 * Registers a sortable attribute compound.
		 *
		 * @param name     compound name
		 * @param elements ordered attribute elements
		 * @param whichIs  compound configuration callback
		 */
		void create(
			@Nonnull String name,
			@Nonnull AttributeElement[] elements,
			@Nonnull Consumer<SortableAttributeCompoundSchemaBuilder> whichIs
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
