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
import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.Query;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.RoaringBitmapBackedBitmap;
import io.evitadb.index.membership.ReducedIndexMembership;
import io.evitadb.roaringbitmap.PersistentRoaringBitmap;
import io.evitadb.roaringbitmap.RoaringBitmapWriter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import javax.annotation.Nonnull;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.evitadb.api.query.QueryConstraints.*;

/**
 * Prices `orderBy(referenceProperty(R, attributeNatural(A)))` on a production catalog, so the `pickFirst` semantics of
 * "every row of a selected owner takes part in ordering, whatever the filter says about the same reference" - is
 * shipped on a measured cost rather than on a model. It is an A/B harness: the same jar layout is
 * built from the baseline and from the change, and both are run against the same restored catalog.
 *
 * # The selection
 *
 * The reference's targets (referenced primary keys that own at least one partition in `LIVE`) are listed in
 * ascending order and `seedTargets` of them are picked at an even stride. The selection `S` is every owner that
 * references at least one seed. It is the **same** `S` in every arm.
 *
 * # Arms ({@link QueryState#arm})
 *
 * - `UNNARROWED` - `entityPrimaryKeyInSet(S)`. The baseline walks the reference's whole partition family whatever
 *   `S` is; the change visits only the partitions holding a row of `S`.
 * - `NARROWED_SEEDS` - adds `referenceHaving(R, entityPrimaryKeyInSet(seeds))`. The baseline reuses that candidate
 *   set (the seeds' partitions only); the change ignores it and visits every partition holding a row of `S`, which
 *   is the price of the "all rows" rule on a narrowed query.
 *
 * Every arm also runs without the `orderBy` ({@link QueryState#ordered}); those rows are the rig's control and must
 * come out equal in both builds.
 *
 * # Guards
 *
 * - The fixture is a {@link Param}, never a system property, and an unusable fixture throws: a plausible number for
 *   a catalog nobody loaded is worse than a failed run.
 * - Setup prints the fixture's shape (family size, seeds, `|S|`, rows).
 * - Every measured query carries `debug(PREFER_INDEX_SCAN)`, which denies the optional prefetch, and setup runs it
 *   once more with `queryTelemetry()` and throws when it nevertheless took the prefetch route
 *   ({@link QueryPhase#EXECUTION_PREFETCH}): this benchmark prices the index route only.
 * - The result cache is disabled, so a repeated query is sorted every time.
 *
 * Needs a quiet machine. Run through JMH's own runner with the fixture parameters, e.g.:
 * {@code java -Xmx2g -cp evita_test/evita_performance_tests/target/benchmarks.jar org.openjdk.jmh.Main
 * io\.evitadb\.spike\.ReferencePickFirstSortBenchmark -p storageDirectory=<dir> -p catalogName=<name>
 * -rf json -rff <result.json>}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"-Xmx24g", "-XX:+ExitOnOutOfMemoryError"})
public class ReferencePickFirstSortBenchmark {
	/**
	 * How long the catalog may take to finish its background load.
	 */
	private static final long LOAD_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(10);
	/**
	 * Page size of every measured query - one listing page, so fetching and slicing stay negligible.
	 */
	private static final int PAGE_SIZE = 20;

	/**
	 * Which partition set the sorter is handed. See the class javadoc.
	 */
	public enum Arm {
		UNNARROWED,
		NARROWED_SEEDS
	}

	/**
	 * The loaded production catalog, shared by every benchmark of the fork.
	 */
	@State(Scope.Benchmark)
	public static class CorpusState {
		/**
		 * Storage directory holding the restored catalog. Mandatory.
		 */
		@Param("")
		public String storageDirectory;
		/**
		 * Name of the restored catalog. Mandatory.
		 */
		@Param("")
		public String catalogName;
		/**
		 * Owner collection.
		 */
		@Param("Product")
		public String entityType;
		/**
		 * The reference ordered by.
		 */
		@Param("media")
		public String referenceName;
		/**
		 * The sortable reference attribute ordered by.
		 */
		@Param("mediaOrder")
		public String sortAttribute;
		/**
		 * Whether the ordering names `pickFirstByEntityProperty` explicitly - needed for a hierarchical target, whose
		 * default is `traverseByEntityProperty`.
		 */
		@Param("false")
		public boolean explicitPickFirst;

		Evita evita;
		EvitaSessionContract session;
		EntityCollection collection;
		ReferencedTypeEntityIndex typeIndex;
		ReducedIndexMembership membership;
		int[] targets;
		int familySize;

		/**
		 * Opens the catalog and validates that the requested reference and attribute exist and are usable for the
		 * measured ordering.
		 */
		@Setup(Level.Trial)
		public void open() {
			if (this.storageDirectory.isBlank() || this.catalogName.isBlank()) {
				throw new IllegalArgumentException(
					"Pass the fixture: -p storageDirectory=<dir> -p catalogName=<name>"
				);
			}
			final Path storage = Path.of(this.storageDirectory);
			if (!Files.isDirectory(storage)) {
				throw new IllegalArgumentException("Storage directory `" + storage + "` does not exist!");
			}
			this.evita = new Evita(
				EvitaConfiguration.builder()
					.storage(StorageOptions.builder().storageDirectory(storage).build())
					// the result cache would answer a repeated query without sorting - price the uncached work
					.cache(CacheOptions.builder().enabled(false).build())
					.server(
						ServerOptions.builder()
							.requestThreadPool(ThreadPoolOptions.requestThreadPoolBuilder().build())
							.build()
					)
					.build()
			);
			// a catalog restored from a backup comes up INACTIVE; loading it is part of the fixture, not of any measurement
			final CatalogState state = this.evita.getCatalogState(this.catalogName)
				.orElseThrow(() -> new IllegalArgumentException(
					"Catalog `" + this.catalogName + "` not found in `" + storage + "`!"
				));
			if (state == CatalogState.INACTIVE) {
				this.evita.activateCatalog(this.catalogName);
			}
			final Catalog catalog = awaitLoaded(this.evita, this.catalogName);
			this.collection = catalog.getCollectionForEntityOrThrowException(this.entityType);
			final EntitySchemaContract schema = this.collection.getSchema();
			final ReferenceSchemaContract reference = schema.getReference(this.referenceName)
				.orElseThrow(() -> new IllegalArgumentException(
					"Entity `" + this.entityType + "` has no reference `" + this.referenceName + "`!"
				));
			reference.getAttribute(this.sortAttribute)
				.filter(it -> it.isSortableInScope(io.evitadb.dataType.Scope.LIVE))
				.orElseThrow(() -> new IllegalArgumentException(
					"Reference `" + this.referenceName + "` has no sortable attribute `" + this.sortAttribute + "`!"
				));
			this.typeIndex = (ReferencedTypeEntityIndex) this.collection.getIndexByKeyIfExists(
				new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, io.evitadb.dataType.Scope.LIVE, this.referenceName)
			);
			if (this.typeIndex == null) {
				throw new IllegalArgumentException("Reference `" + this.referenceName + "` has no LIVE type index!");
			}
			final GlobalEntityIndex globalIndex = (GlobalEntityIndex) this.collection.getIndexByKeyIfExists(
				new EntityIndexKey(EntityIndexType.GLOBAL, io.evitadb.dataType.Scope.LIVE)
			);
			this.membership = globalIndex == null ? null : globalIndex.getReducedIndexMembership(this.referenceName);
			if (this.membership == null) {
				throw new IllegalArgumentException(
					"Reference `" + this.referenceName + "` maintains no reduced index membership in LIVE!"
				);
			}
			final int[] family = {0};
			this.typeIndex.forEachReferenceIndexPrimaryKey(pk -> family[0]++);
			this.familySize = family[0];
			this.targets = this.typeIndex.getAllReferencedPrimaryKeys().getArray();
			this.session = this.evita.createReadOnlySession(this.catalogName);
			System.out.printf(
				"%nFIXTURE %s.%s ordered by %s: %,d partitions over %,d targets, membership residual %,d%n",
				this.entityType, this.referenceName, this.sortAttribute,
				this.familySize, this.targets.length, this.membership.getResidualIndexPrimaryKeys().size()
			);
		}

		/**
		 * Closes the session and the engine.
		 */
		@TearDown(Level.Trial)
		public void close() {
			if (this.session != null) {
				this.session.close();
			}
			if (this.evita != null) {
				this.evita.close();
			}
		}
	}

	/**
	 * The selection derived from `seedTargets` evenly spaced targets.
	 */
	@State(Scope.Benchmark)
	public static class SelectionState {
		/**
		 * How many targets seed the selection.
		 */
		@Param({"1", "10", "100", "1000", "10000"})
		public int seedTargets;

		int[] seeds;
		int[] owners;
		int[] ownerTargets;
		long ownerRows;

		/**
		 * Picks the seeds, resolves the owners referencing them and every target those owners reference.
		 */
		@Setup(Level.Trial)
		public void select(@Nonnull CorpusState corpus) {
			final int[] targets = corpus.targets;
			if (this.seedTargets > targets.length) {
				throw new IllegalArgumentException(
					"seedTargets=" + this.seedTargets + " exceeds the " + targets.length + " targets of the reference!"
				);
			}
			this.seeds = new int[this.seedTargets];
			final double stride = (double) targets.length / this.seedTargets;
			for (int i = 0; i < this.seedTargets; i++) {
				this.seeds[i] = targets[(int) (i * stride)];
			}
			final List<SealedEntity> selected = corpus.session.query(
				Query.query(
					collection(corpus.entityType),
					filterBy(referenceHaving(corpus.referenceName, entityPrimaryKeyInSet(boxed(this.seeds)))),
					require(page(1, Integer.MAX_VALUE), entityFetch(referenceContent(corpus.referenceName)))
				),
				SealedEntity.class
			).getRecordData();
			final RoaringBitmapWriter<PersistentRoaringBitmap> ownerWriter = RoaringBitmapBackedBitmap.buildWriter();
			final RoaringBitmapWriter<PersistentRoaringBitmap> targetWriter = RoaringBitmapBackedBitmap.buildWriter();
			long rows = 0;
			for (SealedEntity owner : selected) {
				ownerWriter.add(owner.getPrimaryKeyOrThrowException());
				for (ReferenceContract reference : owner.getReferences(corpus.referenceName)) {
					targetWriter.add(reference.getReferencedPrimaryKey());
					rows++;
				}
			}
			this.owners = ownerWriter.get().toArray();
			this.ownerTargets = targetWriter.get().toArray();
			this.ownerRows = rows;
			if (this.owners.length == 0) {
				throw new IllegalStateException("Seeds " + this.seedTargets + " selected no owner!");
			}
			System.out.printf(
				"SELECTION seeds=%,d -> |S|=%,d owners, %,d rows, %,d distinct targets of S's rows%n",
				this.seeds.length, this.owners.length, this.ownerRows, this.ownerTargets.length
			);
		}
	}

	/**
	 * One measured query: an arm, with or without the ordering.
	 */
	@State(Scope.Benchmark)
	public static class QueryState {
		/**
		 * The partition set the sorter is handed.
		 */
		@Param({"UNNARROWED", "NARROWED_SEEDS"})
		public Arm arm;
		/**
		 * Whether the ordering is present; the sort phase costs the difference between the two.
		 */
		@Param({"true", "false"})
		public boolean ordered;

		Query query;

		/**
		 * Builds the query and verifies, with telemetry, that it runs on the index route.
		 */
		@Setup(Level.Trial)
		public void build(@Nonnull CorpusState corpus, @Nonnull SelectionState selection) {
			final FilterConstraint narrowing = switch (this.arm) {
				case UNNARROWED -> null;
				case NARROWED_SEEDS -> referenceHaving(
					corpus.referenceName, entityPrimaryKeyInSet(boxed(selection.seeds))
				);
			};
			final OrderBy ordering;
			if (!this.ordered) {
				ordering = null;
			} else if (corpus.explicitPickFirst) {
				ordering = orderBy(
					referenceProperty(
						corpus.referenceName,
						pickFirstByEntityProperty(entityPrimaryKeyNatural(OrderDirection.ASC)),
						attributeNatural(corpus.sortAttribute)
					)
				);
			} else {
				ordering = orderBy(referenceProperty(corpus.referenceName, attributeNatural(corpus.sortAttribute)));
			}
			this.query = Query.query(
				collection(corpus.entityType),
				filterBy(and(entityPrimaryKeyInSet(boxed(selection.owners)), narrowing)),
				ordering,
				require(page(1, PAGE_SIZE), debug(DebugMode.PREFER_INDEX_SCAN))
			);
			verifyIndexRoute(corpus, this.query, this.arm + (this.ordered ? " ordered" : " unordered"));
		}
	}

	/**
	 * Runs one measured query and returns the page size so the call cannot be elided.
	 */
	@Benchmark
	public int query(@Nonnull CorpusState corpus, @Nonnull SelectionState selection, @Nonnull QueryState state) {
		return corpus.session.query(state.query, EntityReference.class).getRecordData().size();
	}

	/**
	 * Runs the query once with telemetry and refuses a plan that prefetched: the index route is what is priced.
	 */
	private static void verifyIndexRoute(@Nonnull CorpusState corpus, @Nonnull Query query, @Nonnull String label) {
		final Query withTelemetry = Query.query(
			query.getCollection(),
			query.getFilterBy(),
			query.getOrderBy(),
			require(page(1, PAGE_SIZE), debug(DebugMode.PREFER_INDEX_SCAN), queryTelemetry())
		);
		final EvitaResponse<EntityReference> response = corpus.session.query(withTelemetry, EntityReference.class);
		final QueryTelemetry telemetry = response.getExtraResult(QueryTelemetry.class);
		if (telemetry == null) {
			throw new IllegalStateException(label + ": no telemetry returned, the route cannot be verified!");
		}
		if (containsPhase(telemetry, QueryPhase.EXECUTION_PREFETCH)) {
			throw new IllegalStateException(label + ": the query prefetched - this benchmark prices the index route!");
		}
		System.out.printf("ROUTE %s: index route, %,d records%n", label, response.getTotalRecordCount());
	}

	/**
	 * Tells whether the telemetry tree contains the phase anywhere.
	 */
	private static boolean containsPhase(@Nonnull QueryTelemetry telemetry, @Nonnull QueryPhase phase) {
		if (telemetry.getOperation() == phase) {
			return true;
		}
		for (QueryTelemetry step : telemetry.getSteps()) {
			if (containsPhase(step, phase)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Boxes primary keys for the query constraint factories.
	 */
	@Nonnull
	private static Integer[] boxed(@Nonnull int[] primaryKeys) {
		final Integer[] result = new Integer[primaryKeys.length];
		for (int i = 0; i < primaryKeys.length; i++) {
			result[i] = primaryKeys[i];
		}
		return result;
	}

	/**
	 * Waits until the catalog finishes its background load and becomes a usable {@link Catalog}.
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
		throw new IllegalStateException("Catalog `" + catalogName + "` did not become usable within the load timeout!");
	}

}
