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

package io.evitadb.spike;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.TreeMap;

/**
 * Answers one question, with counts and nothing else: **do reduced partition indexes exist for references
 * indexed at {@link ReferenceIndexType#FOR_FILTERING}, or only for
 * {@link ReferenceIndexType#FOR_FILTERING_AND_PARTITIONING} ones?**
 *
 * The answer decides what the `#1529` high-cardinality counterfactual actually costs to reach:
 *
 * - **If the partitions already exist at `FOR_FILTERING`**, flipping the schema flag only widens the filter in
 *   `ReevaluateExpressionExecutor#resolveSiblingReducedIndexes`, and the walk grows the instant the schema
 *   change commits. The exposure is one schema edit away, exactly as issue #1529 states.
 * - **If they do not**, the flag change builds nothing retroactively — `ReferenceIndexMutator` creates a
 *   reduced index only when a reference is written — so the partitions accumulate as entities are rewritten,
 *   and the exposure is one schema edit *plus a republish cycle* away.
 *
 * `ReferenceIndexMutator#collectOwnerReducedIndexes` documents the second answer ("the only ones for which
 * reduced indexes exist at all"), while the `#1529` census counted 183,754 resolvable partition primary keys
 * advertised by `FOR_FILTERING` references' type indexes. Both cannot be right, and a timing harness that
 * walks partitions which would not exist measures nothing. Hence this probe, which resolves every advertised
 * primary key and reports what it actually found.
 *
 * ```
 * java -Xmx32g -cp <cp> io.evitadb.spike.ConditionalFacetPartitionExistenceProbe <dir> <catalog> <collection>
 * ```
 *
 * @author Claude (issue #1529 partition-existence probe), FG Forrest a.s. (c) 2026
 */
public class ConditionalFacetPartitionExistenceProbe {

	/**
	 * How long the probe waits for the catalog's background load to finish before giving up.
	 */
	private static final long LOAD_TIMEOUT_NANOS = 15L * 60L * 1_000_000_000L;

	public static void main(@Nonnull String[] args) {
		if (args.length < 3) {
			System.err.println(
				"Usage: ConditionalFacetPartitionExistenceProbe <storageDir> <catalogName> <collection>"
			);
			System.exit(1);
		}
		final Path storageDirectory = Path.of(args[0]);
		final String catalogName = args[1];
		final String collectionName = args[2];

		try (
			final Evita evita = new Evita(
				EvitaConfiguration.builder()
					.storage(StorageOptions.builder().storageDirectory(storageDirectory).build())
					.server(
						ServerOptions.builder()
							.requestThreadPool(ThreadPoolOptions.requestThreadPoolBuilder().build())
							.build()
					)
					.build()
			)
		) {
			final Catalog catalog = awaitLoaded(evita, catalogName);
			final EntityCollection collection = catalog.getCollectionForEntityOrThrowException(collectionName);

			System.out.printf("%n=== `%s` reduced-partition existence by reference index type ===%n%n",
				collectionName);
			System.out.printf(
				"  %-22s %-32s %8s %10s %10s %10s  %s%n",
				"reference", "index type", "family", "advertised", "resolved", "members", "resolved class"
			);

			long partitionedAdvertised = 0L;
			long partitionedResolved = 0L;
			long filteringAdvertised = 0L;
			long filteringResolved = 0L;

			for (final ReferenceSchemaContract reference :
				new TreeMap<>(collection.getSchema().getReferences()).values()) {
				final ReferenceIndexType indexType = reference.getReferenceIndexType(Scope.LIVE);
				for (final EntityIndexType family : new EntityIndexType[]{
					EntityIndexType.REFERENCED_ENTITY_TYPE, EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE
				}) {
					final ReferencedTypeEntityIndex typeIndex = typeIndex(collection, family, reference.getName());
					if (typeIndex == null) {
						continue;
					}
					final long[] counters = new long[3];
					final String[] resolvedClass = new String[1];
					typeIndex.forEachReferenceIndexPrimaryKey(pk -> {
						counters[0]++;
						final EntityIndex partition = collection.getIndexByPrimaryKeyIfExists(pk);
						if (partition != null) {
							counters[1]++;
							counters[2] += partition.getAllPrimaryKeys().size();
							if (resolvedClass[0] == null) {
								resolvedClass[0] = partition.getClass().getSimpleName()
									+ "/" + partition.getIndexKey().type();
							}
						}
					});
					if (counters[0] == 0L) {
						continue;
					}
					System.out.printf(
						"  %-22s %-32s %8s %,10d %,10d %,10d  %s%n",
						reference.getName(), indexType,
						family == EntityIndexType.REFERENCED_ENTITY_TYPE ? "entity" : "group",
						counters[0], counters[1], counters[2],
						resolvedClass[0] == null ? "-" : resolvedClass[0]
					);
					if (indexType == ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING) {
						partitionedAdvertised += counters[0];
						partitionedResolved += counters[1];
					} else {
						filteringAdvertised += counters[0];
						filteringResolved += counters[1];
					}
				}
			}

			System.out.printf(
				"%n  FOR_FILTERING_AND_PARTITIONING: %,d advertised, %,d resolved to a live index%n",
				partitionedAdvertised, partitionedResolved
			);
			System.out.printf(
				"  FOR_FILTERING:                  %,d advertised, %,d resolved to a live index%n%n",
				filteringAdvertised, filteringResolved
			);
			System.out.println(
				filteringResolved > 0L
					? "  VERDICT: reduced partitions DO exist for FOR_FILTERING references. Flipping the flag\n"
					+ "           widens the walk immediately - the exposure is one schema edit away."
					: "  VERDICT: reduced partitions exist ONLY for FOR_FILTERING_AND_PARTITIONING references.\n"
					+ "           Flipping the flag builds nothing retroactively - the walk grows only as\n"
					+ "           entities are rewritten, so the exposure is one schema edit plus a republish."
			);
		}
	}

	/**
	 * Resolves one `REFERENCED_*_TYPE` index of a reference.
	 *
	 * @param collection    the collection holding the indexes
	 * @param family        the referenced-type index family
	 * @param referenceName the reference whose type index is resolved
	 * @return the type index, or `null` when the reference has none of that kind
	 */
	@Nullable
	private static ReferencedTypeEntityIndex typeIndex(
		@Nonnull EntityCollection collection,
		@Nonnull EntityIndexType family,
		@Nonnull String referenceName
	) {
		final EntityIndex index = collection.getIndexByKeyIfExists(
			new EntityIndexKey(family, Scope.LIVE, referenceName)
		);
		if (index == null) {
			return null;
		}
		if (index instanceof final ReferencedTypeEntityIndex typeIndex) {
			return typeIndex;
		}
		throw new IllegalStateException("Index " + family + "/" + referenceName + " has the wrong type!");
	}

	/**
	 * Waits until the catalog finishes its background load.
	 *
	 * @param evita       the running engine
	 * @param catalogName the catalog to wait for
	 * @return the loaded catalog
	 */
	@Nonnull
	private static Catalog awaitLoaded(@Nonnull Evita evita, @Nonnull String catalogName) {
		final long deadline = System.nanoTime() + LOAD_TIMEOUT_NANOS;
		while (System.nanoTime() < deadline) {
			final CatalogContract candidate = evita.getCatalogInstance(catalogName)
				.orElseThrow(() -> new IllegalArgumentException("Catalog `" + catalogName + "` not found!"));
			if (candidate instanceof final Catalog loaded) {
				return loaded;
			}
			try {
				Thread.sleep(500L);
			} catch (final InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting for catalog!", e);
			}
		}
		throw new IllegalStateException("Catalog `" + catalogName + "` did not load in time!");
	}

	/**
	 * Not instantiable — this is a `main()`-style probe.
	 */
	private ConditionalFacetPartitionExistenceProbe() {
	}
}
