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
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.statistics.IndexBrowseCriteria;
import io.evitadb.api.statistics.IndexBrowseOrdering;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static io.evitadb.index.bitmap.RoaringBitmapBackedBitmap.getRoaringBitmap;
import static io.evitadb.roaringbitmap.PersistentRoaringBitmap.and;

/**
 * Counts — never times — the quantities issue #1529's decision rule turns on, against a real catalog.
 *
 * # Why counts, and why a separate class from the timing harness
 *
 * The sibling-resolver walk
 * (`ReevaluateExpressionExecutor#collectOwnersOfReducedIndexes`) is `O(total reduced partitions of the
 * collection)`, independent of how many owners a trigger actually affects. Whether that matters is decided by two
 * numbers, and only one of them is a duration:
 *
 * - `P` — how many reduced partitions a real, migrated catalog actually has per reference. The prior assessment
 *   was taken at 3,000 and explicitly does not generalise.
 * - `intersecting / P` — of those partitions, how many hold at least one owner the trigger affects. This is the
 *   ratio that bounds the whole enterprise: the reverse index proposed in #1529 changes how the intersecting
 *   partitions are *found*, never which ones they are, so the `getOrCreateIndexByPrimaryKey` registration that
 *   follows the walk is identical with or without it. If the ratio approaches 1, the registration absorbs
 *   whatever the walk saves and the structure buys nothing.
 *
 * Both are **deterministic properties of the data**. They do not need a quiet machine, they do not vary between
 * runs, and they can settle the question before a single nanosecond is measured — which is why they are counted
 * here, in their own class, rather than folded into a benchmark that needs a negotiated window.
 *
 * # What it reports
 *
 * Per collection: entity count. Per reference of every collection: the declared
 * {@link ReferenceIndexType} in each scope, the indexed components, the facet flags, and — for every reference
 * that is {@link ReferenceIndexType#FOR_FILTERING_AND_PARTITIONING} — the partition count and the partition-size
 * distribution of both index families (`REFERENCED_ENTITY_TYPE` and `REFERENCED_GROUP_ENTITY_TYPE`).
 *
 * Then, for a chosen collection, it simulates the resolver's fan-out without mutating anything: it takes one
 * partition of one reference as the "mutated" one, treats that partition's members as the affected owners — which
 * is exactly the set `ReevaluateExpressionExecutor#resolveForGroupEntityAttribute` would resolve — and counts, for
 * every *other* partitioned reference, how many partitions intersect it. That is `intersecting / P`, measured on
 * production data rather than assumed.
 *
 * # Deliberately read-only
 *
 * The catalog is opened and never written. Point it at a **copy** of an export snapshot regardless: the engine
 * owns any directory it opens, and a census is not worth risking the only copy of a dataset over.
 *
 * ```
 * java -Xmx48g -cp <classpath> io.evitadb.spike.ConditionalFacetPartitionCensus <storageDir> <catalogName> [collection]
 * ```
 *
 * @author Claude (issue #1529 partition-cardinality census), FG Forrest a.s. (c) 2026
 */
public class ConditionalFacetPartitionCensus {

	/**
	 * How long the census waits for the catalog's background load to finish before giving up.
	 */
	private static final long LOAD_TIMEOUT_NANOS = 15L * 60L * 1_000_000_000L;

	/**
	 * How many of the largest partitions of a reference are listed individually.
	 */
	private static final int LARGEST_LISTED = 3;

	/**
	 * How many distinct "mutated partition" samples the fan-out simulation draws per reference.
	 */
	private static final int FANOUT_SAMPLES = 5;

	/**
	 * How many of the *largest* partitions the fan-out simulation additionally draws — the dense shape, which a
	 * positional sample essentially never hits.
	 */
	private static final int FANOUT_LARGEST_SAMPLES = 3;

	public static void main(@Nonnull String[] args) {
		if (args.length < 2) {
			System.err.println(
				"Usage: ConditionalFacetPartitionCensus <storageDirectory> <catalogName> [collectionToSimulate]"
			);
			System.exit(1);
		}
		final Path storageDirectory = Path.of(args[0]);
		final String catalogName = args[1];
		final String simulatedCollection = args.length > 2 ? args[2] : null;

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
			final long openStart = System.nanoTime();
			final Catalog catalog = awaitLoaded(evita, catalogName);
			System.out.printf(
				"Catalog opened in %.1f s — version %d, state %s%n%n",
				(System.nanoTime() - openStart) / 1_000_000_000.0,
				catalog.getVersion(), catalog.getCatalogState()
			);

			final List<String> entityTypes = new ArrayList<>(catalog.getEntityTypes());
			entityTypes.sort(String::compareTo);

			System.out.println("=== COLLECTIONS ===");
			for (final String entityType : entityTypes) {
				final EntityCollection collection = catalog.getCollectionForEntityOrThrowException(entityType);
				System.out.printf(
					"%-28s %,12d entities  %,8d indexes%s%n",
					entityType, collection.size(), collection.getIndexCount(), indexTypeBreakdown(collection)
				);
			}

			for (final String entityType : entityTypes) {
				reportReferences(catalog.getCollectionForEntityOrThrowException(entityType));
			}

			if (simulatedCollection != null) {
				simulateFanOut(catalog.getCollectionForEntityOrThrowException(simulatedCollection));
			}
		}
	}

	/**
	 * Renders how a collection's indexes split across {@link EntityIndexType}, so the walk's share of them is
	 * visible. The sibling resolver visits only the partitions its `REFERENCED_*_TYPE` indexes advertise, which can
	 * be a small fraction of a collection's total index population — a distinction that is invisible from the total
	 * alone and easy to misread as the walk's cost.
	 *
	 * @param collection the collection to break down
	 * @return a printable ` [TYPE=n, ...]` fragment, empty when the collection holds no indexes
	 */
	@Nonnull
	private static String indexTypeBreakdown(@Nonnull EntityCollection collection) {
		final StringBuilder breakdown = new StringBuilder(128);
		for (final EntityIndexType type : EntityIndexType.values()) {
			final int count = collection.browseIndexes(
				new IndexBrowseCriteria(
					1, 1, IndexBrowseOrdering.MAP_ORDER, OrderDirection.ASC,
					EnumSet.of(type), EnumSet.allOf(Scope.class), Set.of()
				)
			).totalRecordCount();
			if (count > 0) {
				breakdown.append(breakdown.isEmpty() ? "  [" : ", ").append(type).append('=').append(count);
			}
		}
		return breakdown.isEmpty() ? "" : breakdown.append(']').toString();
	}

	/**
	 * Prints every reference of one collection with its declared indexing, and the partition census of those that
	 * are partitioned.
	 *
	 * @param collection the collection whose references are described
	 */
	private static void reportReferences(@Nonnull EntityCollection collection) {
		final Map<String, ReferenceSchemaContract> references = collection.getSchema().getReferences();
		if (references.isEmpty()) {
			return;
		}
		System.out.printf("%n=== REFERENCES OF `%s` ===%n", collection.getEntityType());
		for (final ReferenceSchemaContract reference : new TreeMap<>(references).values()) {
			System.out.printf(
				"%n  %s -> %s%s%n", reference.getName(), reference.getReferencedEntityType(),
				reference.getReferencedGroupType() == null
					? "" : " (group: " + reference.getReferencedGroupType() + ")"
			);
			for (final Scope scope : Scope.values()) {
				final ReferenceIndexType indexType = reference.getReferenceIndexType(scope);
				System.out.printf(
					"    %-8s indexType=%-32s components=%-46s faceted=%-5s facetedPartially=%s%n",
					scope, indexType, reference.getIndexedComponents(scope),
					reference.isFacetedInScope(scope),
					reference.getFacetedPartiallyInScope(scope) != null
				);
				// Counted for EVERY reference, not only the partitioned ones. A `FOR_FILTERING` reference still
				// owns a `REFERENCED_*_TYPE` index whose advertising map holds the partitions it WOULD
				// contribute to the sibling walk the moment its index type is switched to
				// FOR_FILTERING_AND_PARTITIONING. That counterfactual is the exposure, and it is countable
				// today without touching the schema.
				reportPartitions(collection, scope, reference.getName(),
					EntityIndexType.REFERENCED_ENTITY_TYPE);
				reportPartitions(collection, scope, reference.getName(),
					EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE);
			}
		}
	}

	/**
	 * Prints the partition count and size distribution of one index family of one reference.
	 *
	 * @param collection    the collection holding the indexes
	 * @param scope         the scope whose indexes are inspected
	 * @param referenceName the reference whose partitions are counted
	 * @param indexType     the referenced-type index family to walk
	 */
	private static void reportPartitions(
		@Nonnull EntityCollection collection,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		@Nonnull EntityIndexType indexType
	) {
		final ReferencedTypeEntityIndex typeIndex = typeIndex(collection, scope, referenceName, indexType);
		if (typeIndex == null) {
			return;
		}
		final int[] sizes = partitionSizes(collection, typeIndex);
		if (sizes.length == 0) {
			System.out.printf("      %-30s no partitions%n", indexType);
			return;
		}
		Arrays.sort(sizes);
		long members = 0L;
		for (final int size : sizes) {
			members += size;
		}
		final StringBuilder largest = new StringBuilder();
		for (int i = 0; i < Math.min(LARGEST_LISTED, sizes.length); i++) {
			largest.append(i == 0 ? "" : ", ").append(String.format("%,d", sizes[sizes.length - 1 - i]));
		}
		System.out.printf(
			"      %-30s P=%,d  members=%,d  min=%,d  median=%,d  p95=%,d  largest=[%s]%n",
			indexType, sizes.length, members, sizes[0], sizes[sizes.length / 2],
			sizes[(int) (sizes.length * 0.95)], largest
		);
	}

	/**
	 * Simulates the sibling fan-out of one cross-entity trigger without mutating anything, and reports
	 * `intersecting / P` per sibling reference.
	 *
	 * For each partitioned reference in turn, a handful of its partitions are treated as the one a trigger fired
	 * for; that partition's members are the affected owners. Every *other* partitioned reference is then walked
	 * exactly as `collectOwnersOfReducedIndexes` walks it, counting how many of its partitions hold at least one
	 * affected owner.
	 *
	 * @param collection the collection whose references fan out
	 */
	private static void simulateFanOut(@Nonnull EntityCollection collection) {
		System.out.printf("%n=== SIBLING FAN-OUT SIMULATION ON `%s` (LIVE scope) ===%n",
			collection.getEntityType());
		final List<String> partitioned = new ArrayList<>();
		final List<String> triggers = new ArrayList<>();
		final List<String> everyIndexed = new ArrayList<>();
		for (final ReferenceSchemaContract reference : collection.getSchema().getReferences().values()) {
			final ReferenceIndexType indexType = reference.getReferenceIndexType(Scope.LIVE);
			if (indexType == ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING) {
				partitioned.add(reference.getName());
			}
			if (indexType != ReferenceIndexType.NONE) {
				everyIndexed.add(reference.getName());
			}
			if (reference.getFacetedPartiallyInScope(Scope.LIVE) != null) {
				triggers.add(reference.getName());
			}
		}
		partitioned.sort(String::compareTo);
		triggers.sort(String::compareTo);
		everyIndexed.sort(String::compareTo);
		System.out.printf("partitioned references : %s%n", partitioned);
		System.out.printf("conditional facets     : %s  <- these are the ONLY references a trigger fires for%n",
			triggers);
		System.out.printf("indexed references     : %s%n", everyIndexed);

		reportCounterfactualWalkSize(collection, partitioned, everyIndexed, triggers);

		// Drive the simulation from the references that actually carry a trigger. Only a reference with a
		// `facetedPartially` expression is ever `mutation.referenceName()`, and that is the one name the walk
		// excludes - so simulating any other reference as the mutated one measures a trigger that cannot fire.
		final List<String> mutatedCandidates = triggers.isEmpty() ? partitioned : triggers;
		for (final String mutated : mutatedCandidates) {
			final ReferencedTypeEntityIndex mutatedType = typeIndex(
				collection, Scope.LIVE, mutated, EntityIndexType.REFERENCED_ENTITY_TYPE
			);
			if (mutatedType == null) {
				continue;
			}
			final int[] samplePartitions = samplePartitionPks(collection, mutatedType);
			for (final int samplePk : samplePartitions) {
				final EntityIndex sample = collection.getIndexByPrimaryKeyIfExists(samplePk);
				if (sample == null) {
					continue;
				}
				final Bitmap affected = sample.getAllPrimaryKeys();
				if (affected.isEmpty()) {
					continue;
				}
				System.out.printf(
					"%n  trigger on `%s`, partition #%d — %,d affected owners%n",
					mutated, samplePk, affected.size()
				);
				final PersistentRoaringBitmap affectedRoaring = getRoaringBitmap(affected);
				long totalProbed = 0L;
				long totalIntersecting = 0L;
				for (final String sibling : partitioned) {
					if (sibling.equals(mutated)) {
						continue;
					}
					for (final EntityIndexType family : new EntityIndexType[]{
						EntityIndexType.REFERENCED_ENTITY_TYPE, EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE
					}) {
						final long[] counted = countIntersecting(
							collection, Scope.LIVE, sibling, family, affectedRoaring
						);
						if (counted[0] == 0L) {
							continue;
						}
						totalProbed += counted[0];
						totalIntersecting += counted[1];
						System.out.printf(
							"    %-24s %-30s probed=%,7d  intersecting=%,7d  ratio=%.4f%n",
							sibling, family, counted[0], counted[1], counted[1] / (double) counted[0]
						);
					}
				}
				System.out.printf(
					"    %-55s probed=%,7d  intersecting=%,7d  ratio=%.4f%n",
					"TOTAL (this is the walk, and the registration count)",
					totalProbed, totalIntersecting,
					totalProbed == 0L ? 0.0 : totalIntersecting / (double) totalProbed
				);
			}
		}
	}

	/**
	 * Reports how large the sibling walk would become if references currently indexed `FOR_FILTERING` were switched
	 * to `FOR_FILTERING_AND_PARTITIONING` — the exposure that matters, because it is one schema edit away and needs
	 * no migration, no data growth and no new entities.
	 *
	 * The counterfactual is **exact, not modelled**. Every indexed reference already owns a `REFERENCED_*_TYPE`
	 * index, and that index's advertising map already holds precisely the reduced-index primary keys the walk would
	 * visit; `FOR_FILTERING` merely stops `resolveSiblingReducedIndexes` from consulting it. Counting the map
	 * therefore reads the future walk size directly off the present catalog.
	 *
	 * Two distinct escalations are reported, because they have different triggers:
	 *
	 * 1. **A sibling is partitioned.** Its partitions join the walk of every existing trigger.
	 * 2. **A second reference gains a `facetedPartially` expression.** The reference that carries today's trigger is
	 *    excluded from its own walk — `ReevaluateExpressionExecutor#resolveSiblingReducedIndexes` skips the sibling
	 *    whose name equals the mutated reference's — but it is *not* excluded from anybody else's. A second trigger
	 *    makes today's protected reference somebody else's sibling.
	 *
	 * @param collection   the collection whose references fan out
	 * @param partitioned  references currently `FOR_FILTERING_AND_PARTITIONING`
	 * @param everyIndexed every reference with any index at all
	 * @param triggers     references carrying a `facetedPartially` expression
	 */
	private static void reportCounterfactualWalkSize(
		@Nonnull EntityCollection collection,
		@Nonnull List<String> partitioned,
		@Nonnull List<String> everyIndexed,
		@Nonnull List<String> triggers
	) {
		System.out.printf("%n  --- counterfactual walk size (no schema change; counts read off live indexes) ---%n");
		long currentTotal = 0L;
		long ifAllPartitioned = 0L;
		for (final String reference : everyIndexed) {
			final long advertised = advertisedPartitions(collection, reference);
			final boolean isPartitioned = partitioned.contains(reference);
			final boolean isTrigger = triggers.contains(reference);
			if (isPartitioned) {
				currentTotal += advertised;
			}
			ifAllPartitioned += advertised;
			System.out.printf(
				"    %-24s advertises %,9d partitions  %s%s%n",
				reference, advertised,
				isPartitioned ? "[partitioned today]" : "[FOR_FILTERING -> would join the walk if switched]",
				isTrigger ? "  <- carries the trigger" : ""
			);
		}
		System.out.printf(
			"    %-24s %,9d  (walk today, minus whichever reference the trigger fires for)%n",
			"TOTAL partitioned", currentTotal
		);
		System.out.printf(
			"    %-24s %,9d  (every indexed reference switched to FOR_FILTERING_AND_PARTITIONING)%n",
			"TOTAL if all switched", ifAllPartitioned
		);
	}

	/**
	 * Counts the reduced-index primary keys a reference's `REFERENCED_ENTITY_TYPE` and
	 * `REFERENCED_GROUP_ENTITY_TYPE` indexes advertise, whatever its declared index type.
	 *
	 * @param collection    the collection holding the indexes
	 * @param referenceName the reference to count
	 * @return the number of partitions the reference would contribute to a sibling walk
	 */
	private static long advertisedPartitions(
		@Nonnull EntityCollection collection,
		@Nonnull String referenceName
	) {
		final long[] counter = new long[1];
		for (final EntityIndexType family : new EntityIndexType[]{
			EntityIndexType.REFERENCED_ENTITY_TYPE, EntityIndexType.REFERENCED_GROUP_ENTITY_TYPE
		}) {
			final ReferencedTypeEntityIndex typeIndex = typeIndex(collection, Scope.LIVE, referenceName, family);
			if (typeIndex != null) {
				typeIndex.forEachReferenceIndexPrimaryKey(pk -> counter[0]++);
			}
		}
		return counter[0];
	}

	/**
	 * Walks one type index exactly as the resolver does and counts probed and intersecting partitions.
	 *
	 * @param collection the collection holding the indexes
	 * @param scope      the scope whose indexes are inspected
	 * @param reference  the reference whose partitions are walked
	 * @param indexType  the referenced-type index family to walk
	 * @param affected   the affected owner PKs
	 * @return a two-element array of `{probed, intersecting}`
	 */
	@Nonnull
	private static long[] countIntersecting(
		@Nonnull EntityCollection collection,
		@Nonnull Scope scope,
		@Nonnull String reference,
		@Nonnull EntityIndexType indexType,
		@Nonnull PersistentRoaringBitmap affected
	) {
		final ReferencedTypeEntityIndex typeIndex = typeIndex(collection, scope, reference, indexType);
		if (typeIndex == null) {
			return new long[]{0L, 0L};
		}
		final long[] counters = new long[]{0L, 0L};
		typeIndex.forEachReferenceIndexPrimaryKey(reducedIndexPk -> {
			final EntityIndex probed = collection.getIndexByPrimaryKeyIfExists(reducedIndexPk);
			if (probed == null) {
				return;
			}
			counters[0]++;
			if (!and(getRoaringBitmap(probed.getAllPrimaryKeys()), affected).isEmpty()) {
				counters[1]++;
			}
		});
		return counters;
	}

	/**
	 * Returns up to {@link #FANOUT_LARGEST_SAMPLES} + {@link #FANOUT_SAMPLES} partition primary keys of a type
	 * index: the largest partitions by owner count, followed by a sample spread across the advertisement order
	 * so the rest is not all taken from one end.
	 *
	 * @param collection the collection holding the indexes, needed to size each partition
	 * @param typeIndex  the type index to sample
	 * @return the sampled reduced-index primary keys
	 */
	@Nonnull
	private static int[] samplePartitionPks(
		@Nonnull EntityCollection collection,
		@Nonnull ReferencedTypeEntityIndex typeIndex
	) {
		final List<Integer> all = new ArrayList<>();
		typeIndex.forEachReferenceIndexPrimaryKey(all::add);
		if (all.isEmpty()) {
			return new int[0];
		}
		// The DENSE shape is the one the issue cares about - a trigger touching thousands of owners - and it lives
		// in the LARGEST partitions, which a positional sample almost never draws. Sample both ends deliberately:
		// the biggest partitions give the dense shape, the spread gives the typical one.
		final List<Integer> bySize = new ArrayList<>(all);
		bySize.sort((left, right) -> Integer.compare(partitionSize(collection, right), partitionSize(collection, left)));

		final int spread = Math.min(FANOUT_SAMPLES, all.size());
		final int biggest = Math.min(FANOUT_LARGEST_SAMPLES, bySize.size());
		final int[] sampled = new int[spread + biggest];
		for (int i = 0; i < biggest; i++) {
			sampled[i] = bySize.get(i);
		}
		for (int i = 0; i < spread; i++) {
			sampled[biggest + i] = all.get((int) ((long) i * all.size() / spread));
		}
		return sampled;
	}

	/**
	 * Returns the member count of one partition, or `0` when the index is gone.
	 *
	 * @param collection    the collection holding the indexes
	 * @param reducedIndexPk the partition's storage primary key
	 * @return the number of owners in the partition
	 */
	private static int partitionSize(@Nonnull EntityCollection collection, int reducedIndexPk) {
		final EntityIndex index = collection.getIndexByPrimaryKeyIfExists(reducedIndexPk);
		return index == null ? 0 : index.getAllPrimaryKeys().size();
	}

	/**
	 * Returns the member count of every partition a type index advertises.
	 *
	 * @param collection the collection holding the indexes
	 * @param typeIndex  the type index whose partitions are sized
	 * @return the partition sizes, in advertisement order
	 */
	@Nonnull
	private static int[] partitionSizes(
		@Nonnull EntityCollection collection,
		@Nonnull ReferencedTypeEntityIndex typeIndex
	) {
		final List<Integer> sizes = new ArrayList<>();
		typeIndex.forEachReferenceIndexPrimaryKey(reducedIndexPk -> {
			final EntityIndex probed = collection.getIndexByPrimaryKeyIfExists(reducedIndexPk);
			if (probed != null) {
				sizes.add(probed.getAllPrimaryKeys().size());
			}
		});
		final int[] result = new int[sizes.size()];
		for (int i = 0; i < result.length; i++) {
			result[i] = sizes.get(i);
		}
		return result;
	}

	/**
	 * Resolves one `REFERENCED_*_TYPE` index, or `null` when the reference has no partitions of that kind.
	 *
	 * @param collection    the collection holding the indexes
	 * @param scope         the scope whose index is resolved
	 * @param referenceName the reference whose type index is resolved
	 * @param indexType     the referenced-type index family
	 * @return the type index, or `null` when absent
	 */
	@Nullable
	private static ReferencedTypeEntityIndex typeIndex(
		@Nonnull EntityCollection collection,
		@Nonnull Scope scope,
		@Nonnull String referenceName,
		@Nonnull EntityIndexType indexType
	) {
		final EntityIndex index = collection.getIndexByKeyIfExists(
			new EntityIndexKey(indexType, scope, referenceName)
		);
		if (index == null) {
			return null;
		}
		if (index instanceof final ReferencedTypeEntityIndex typeIndex) {
			return typeIndex;
		}
		// an index registered under a REFERENCED_*_TYPE key is a ReferencedTypeEntityIndex by construction, so
		// anything else means the census is reading a structure it does not understand - never skip it silently
		throw new IllegalStateException(
			"Index " + indexType + "/" + referenceName + " in " + scope + " is a " +
				index.getClass().getName() + ", not a ReferencedTypeEntityIndex!"
		);
	}

	/**
	 * Waits until the catalog finishes its background load and becomes a usable {@link Catalog}.
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
				throw new IllegalStateException("Interrupted while waiting for catalog `" + catalogName + "`!", e);
			}
		}
		throw new IllegalStateException(
			"Catalog `" + catalogName + "` did not become usable within the load timeout!"
		);
	}

	/**
	 * Not instantiable — this is a `main()`-style report.
	 */
	private ConditionalFacetPartitionCensus() {
	}
}
