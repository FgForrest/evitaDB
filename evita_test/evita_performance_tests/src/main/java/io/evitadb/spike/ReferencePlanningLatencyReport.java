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
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.configuration.ThreadPoolOptions;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.queryTelemetry;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.require;

/**
 * Times what a `referenceHaving` costs against a real catalog, per reference, next to the size of the partition
 * family the planner walks to decide whether to use it.
 *
 * # Why this exists as a class rather than as a scratch harness
 *
 * The measurement it reproduces was taken once, in a throwaway harness, and the harness was deleted with the
 * session that wrote it — leaving a table of numbers in `specifications/` that nobody can re-derive. The numbers
 * are the entire argument for the planning work of issue #1603, so they have to be reproducible by someone who
 * was not there. That is the whole reason this file is in the source tree.
 *
 * # What it measures, and what it deliberately does not
 *
 * Two queries per reference, both returning a single page of {@link EntityReference} so that fetching never
 * enters the measurement:
 *
 * - **bare** — `referenceHaving(R)` with no body. Nothing narrows partition discovery, so the planner considers
 *   the reference's whole family. This is the shape whose cost tracks *family size* rather than row count.
 * - **narrowed** — `referenceHaving(R, entityPrimaryKeyInSet(t))` for one referenced entity `t` drawn from the
 *   family. Discovery collapses to a single partition, so this is the control: it must not regress when the
 *   planning path changes, and the gap between the two columns is the quantity the work is about.
 *
 * It reports **wall-clock of the whole query**, not a planning-phase breakdown. A phase split needs telemetry
 * attached per query and is a different instrument; total latency is what the earlier table reported and what a
 * change has to move. The fastest of `runs` samples is taken after `warmups` untimed ones, because the interest
 * is in the cost when everything is resident — a mean here would report the machine, not the plan.
 *
 * Latency is a timing measurement and therefore needs a quiet machine, unlike
 * {@link ConditionalFacetPartitionCensus}, which counts deterministic properties of the data and does not.
 *
 * # Usage
 *
 * ```
 * java -cp <perf-tests-jar-with-deps> io.evitadb.spike.ReferencePlanningLatencyReport \
 *     <storageDirectory> <catalogName> [minFamilySize] [warmups] [runs]
 * ```
 *
 * `minFamilySize` skips the references too small to be interesting (default {@link #DEFAULT_MIN_FAMILY_SIZE}).
 * Only the `LIVE` scope is probed, since that is where a query lands unless it says otherwise.
 *
 * @author Claude (issue #1603 planning integration), FG Forrest a.s. (c) 2026
 */
public class ReferencePlanningLatencyReport {

	/**
	 * How long to wait for the catalog to finish its background load before giving up.
	 */
	private static final long LOAD_TIMEOUT_NANOS = 30L * 60L * 1_000_000_000L;

	/**
	 * References with fewer partitions than this are skipped by default — their walk is too short to time
	 * meaningfully and they would only pad the table.
	 */
	private static final int DEFAULT_MIN_FAMILY_SIZE = 50;

	/**
	 * Untimed queries run before measurement, so classes are loaded and the indexes are resident.
	 */
	private static final int DEFAULT_WARMUPS = 3;

	/**
	 * Timed queries per probe; the fastest is reported.
	 */
	private static final int DEFAULT_RUNS = 5;

	/**
	 * Boots the engine against an existing catalog and prints one row per probed reference.
	 *
	 * @param args storage directory, catalog name, and optionally min family size, warmups and runs
	 */
	public static void main(@Nonnull String[] args) {
		if (args.length < 2) {
			System.err.println(
				"Usage: ReferencePlanningLatencyReport <storageDirectory> <catalogName> "
					+ "[minFamilySize] [warmups] [runs]"
			);
			System.exit(1);
		}
		final Path storageDirectory = Path.of(args[0]);
		final String catalogName = args[1];
		final int minFamilySize = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_MIN_FAMILY_SIZE;
		final int warmups = args.length > 3 ? Integer.parseInt(args[3]) : DEFAULT_WARMUPS;
		final int runs = args.length > 4 ? Integer.parseInt(args[4]) : DEFAULT_RUNS;

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
				"Catalog opened in %.1f s — version %d, state %s%n",
				(System.nanoTime() - openStart) / 1_000_000_000.0,
				catalog.getVersion(), catalog.getCatalogState()
			);
			System.out.printf(
				"minFamilySize=%d warmups=%d runs=%d (fastest of runs reported)%n%n",
				minFamilySize, warmups, runs
			);

			final List<Probe> probes = collectProbes(catalog, minFamilySize);
			probes.sort((left, right) -> Integer.compare(right.familySize(), left.familySize()));

			System.out.printf(
				"%-24s %-24s %12s %14s %14s%n",
				"collection", "reference", "family", "bare", "narrowed"
			);
			for (final Probe probe : probes) {
				final String bare = time(evita, catalogName, probe, false, warmups, runs);
				final String narrowed = time(evita, catalogName, probe, true, warmups, runs);
				System.out.printf(
					"%-24s %-24s %,12d %14s %14s%n",
					probe.entityType(), probe.referenceName(), probe.familySize(), bare, narrowed
				);
				System.out.printf("%-49s PLAN: %s%n", "", describePlan(evita, catalogName, probe));
			}
		}
	}

	/**
	 * Finds every reference of every collection that owns a `REFERENCED_ENTITY_TYPE` family in the `LIVE` scope
	 * large enough to be worth timing.
	 *
	 * @param catalog       the loaded catalog
	 * @param minFamilySize references with fewer partitions than this are skipped
	 * @return the probes to run, in no particular order
	 */
	@Nonnull
	private static List<Probe> collectProbes(@Nonnull Catalog catalog, int minFamilySize) {
		final List<Probe> probes = new ArrayList<>();
		final List<String> entityTypes = new ArrayList<>(catalog.getEntityTypes());
		entityTypes.sort(String::compareTo);
		for (final String entityType : entityTypes) {
			final EntityCollection entityCollection = catalog.getCollectionForEntityOrThrowException(entityType);
			final Map<String, ReferenceSchemaContract> references = entityCollection.getSchema().getReferences();
			for (final ReferenceSchemaContract reference : new TreeMap<>(references).values()) {
				final ReferencedTypeEntityIndex typeIndex = typeIndex(entityCollection, reference.getName());
				if (typeIndex == null) {
					continue;
				}
				final AtomicInteger familySize = new AtomicInteger();
				typeIndex.forEachReferenceIndexPrimaryKey(reducedIndexPk -> familySize.incrementAndGet());
				if (familySize.get() < minFamilySize) {
					continue;
				}
				final Bitmap referencedPrimaryKeys = typeIndex.getAllReferencedPrimaryKeys();
				if (referencedPrimaryKeys.isEmpty()) {
					continue;
				}
				probes.add(
					new Probe(
						entityType, reference.getName(), familySize.get(),
						referencedPrimaryKeys.getFirst()
					)
				);
			}
		}
		return probes;
	}

	/**
	 * Runs one probe and returns the fastest timing as a printable string, or the failure when the query cannot
	 * run at all — a reference that is not indexed for filtering raises rather than answering, and reporting that
	 * in the cell is more useful than omitting the row.
	 *
	 * @param evita       the running engine
	 * @param catalogName catalog to query
	 * @param probe       what to query
	 * @param narrowed    `true` to add the single-target body that collapses partition discovery
	 * @param warmups     untimed runs before measurement
	 * @param runs        timed runs; the fastest is returned
	 * @return the formatted duration, or a short failure marker
	 */
	@Nonnull
	private static String time(
		@Nonnull Evita evita,
		@Nonnull String catalogName,
		@Nonnull Probe probe,
		boolean narrowed,
		int warmups,
		int runs
	) {
		try {
			for (int i = 0; i < warmups; i++) {
				execute(evita, catalogName, probe, narrowed);
			}
			long fastest = Long.MAX_VALUE;
			for (int i = 0; i < runs; i++) {
				final long start = System.nanoTime();
				execute(evita, catalogName, probe, narrowed);
				fastest = Math.min(fastest, System.nanoTime() - start);
			}
			return String.format("%,.2f ms", fastest / 1_000_000.0);
		} catch (final RuntimeException e) {
			// a reference can be advertised by its type index and still refuse to be filtered on - reporting the
			// refusal beats dropping the row, because a disappearing row reads as "not measured"
			return e.getClass().getSimpleName();
		}
	}

	/**
	 * Executes one query and returns how many entities it reported, which is discarded — the call exists for its
	 * timing, and the return keeps the compiler from eliding it.
	 *
	 * @param evita       the running engine
	 * @param catalogName catalog to query
	 * @param probe       what to query
	 * @param narrowed    `true` to add the single-target body
	 * @return total record count the query reported
	 */
	private static int execute(
		@Nonnull Evita evita,
		@Nonnull String catalogName,
		@Nonnull Probe probe,
		boolean narrowed
	) {
		return evita.queryCatalog(
			catalogName,
			(Function<EvitaSessionContract, Integer>) session -> session.query(
				query(
					collection(probe.entityType()),
					filterBy(
						narrowed
							? referenceHaving(
								probe.referenceName(), entityPrimaryKeyInSet(probe.sampleReferencedPrimaryKey())
							)
							: referenceHaving(probe.referenceName())
					),
					require(page(1, 1))
				),
				EntityReference.class
			).getTotalRecordCount()
		);
	}

	/**
	 * Asks the engine what it decided about the reduced-index alternative for one reference, by running the bare
	 * query once more with telemetry attached and reading back the alternative's own description.
	 *
	 * This is the observable the planning work is actually about. Latency says a query is slow; this says *why*
	 * the reduced-index plan lost — which obstacles were raised, or that no alternative was registered at all
	 * because `BidirectionalReferenceRewriter` answered the constraint from the counterpart end instead. The
	 * string is produced by `TargetIndexes#toStringWithCosts`, which appends
	 * {@link io.evitadb.core.query.indexSelection.TargetIndexes#getEligibilityObstacleString()}, and reaches the
	 * telemetry tree when `QueryPlanner` pops the alternative's step.
	 *
	 * Run separately from the timed probes on purpose: requesting telemetry changes what the engine records, and
	 * a measurement must not observe itself.
	 *
	 * @param evita       the running engine
	 * @param catalogName catalog to query
	 * @param probe       the reference to describe
	 * @return the alternative descriptions found, or a statement that none was registered
	 */
	@Nonnull
	private static String describePlan(
		@Nonnull Evita evita,
		@Nonnull String catalogName,
		@Nonnull Probe probe
	) {
		try {
			final EvitaResponse<EntityReference> response = evita.queryCatalog(
				catalogName,
				(Function<EvitaSessionContract, EvitaResponse<EntityReference>>) session -> session.query(
					query(
						collection(probe.entityType()),
						filterBy(referenceHaving(probe.referenceName())),
						require(page(1, 1), queryTelemetry())
					),
					EntityReference.class
				)
			);
			final QueryTelemetry telemetry = response.getExtraResult(QueryTelemetry.class);
			if (telemetry == null) {
				return "no telemetry recorded";
			}
			final List<String> alternatives = new ArrayList<>();
			collectReducedIndexAlternatives(telemetry, alternatives);
			final StringBuilder phases = new StringBuilder(256);
			appendPhases(telemetry, phases, 0);
			return (alternatives.isEmpty()
				? "no REFERENCED_ENTITY alternative registered"
				: String.join("  ||  ", alternatives)) + phases;
		} catch (final RuntimeException e) {
			return e.getClass().getSimpleName();
		}
	}

	/**
	 * Renders where the time actually went, two levels deep.
	 *
	 * Latency alone cannot say whether a cost was removed or merely moved: the same total can hide a phase
	 * shrinking while a sibling grows by the same amount. This prints the phases so that question is decided by
	 * the measurement rather than by reading the code and guessing.
	 *
	 * @param node   the telemetry node to render
	 * @param sink   receives the rendered lines
	 * @param depth  current nesting depth
	 */
	private static void appendPhases(
		@Nonnull QueryTelemetry node,
		@Nonnull StringBuilder sink,
		int depth
	) {
		if (depth > 2) {
			return;
		}
		sink.append(String.format("%n%-52s%s%s: %.2f ms", "", "  ".repeat(depth), node.getOperation(),
			node.getSpentTime() / 1_000_000.0));
		for (final QueryTelemetry step : node.getSteps()) {
			appendPhases(step, sink, depth + 1);
		}
	}

	/**
	 * Walks a telemetry tree collecting every argument that describes a reduced-index alternative.
	 *
	 * @param node the telemetry node to walk
	 * @param sink collected descriptions, appended in encounter order
	 */
	private static void collectReducedIndexAlternatives(
		@Nonnull QueryTelemetry node,
		@Nonnull List<String> sink
	) {
		for (final String argument : node.getArguments()) {
			if (argument.contains("REFERENCED_ENTITY composed of")) {
				sink.add(argument);
			}
		}
		for (final QueryTelemetry step : node.getSteps()) {
			collectReducedIndexAlternatives(step, sink);
		}
	}

	/**
	 * Resolves a reference's `REFERENCED_ENTITY_TYPE` index in the `LIVE` scope.
	 *
	 * @param entityCollection the collection holding the indexes
	 * @param referenceName    the reference whose type index is resolved
	 * @return the type index, or `null` when the reference advertises no partitions of that kind
	 */
	@Nullable
	private static ReferencedTypeEntityIndex typeIndex(
		@Nonnull EntityCollection entityCollection,
		@Nonnull String referenceName
	) {
		final EntityIndex index = entityCollection.getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, referenceName)
		);
		if (index == null) {
			return null;
		}
		if (index instanceof final ReferencedTypeEntityIndex typeIndex) {
			return typeIndex;
		}
		throw new IllegalStateException(
			"Index REFERENCED_ENTITY_TYPE/" + referenceName + " is a " + index.getClass().getName()
				+ ", not a ReferencedTypeEntityIndex!"
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
	private ReferencePlanningLatencyReport() {
	}

	/**
	 * One reference worth timing.
	 *
	 * @param entityType                 collection owning the reference
	 * @param referenceName              the reference to filter on
	 * @param familySize                 how many partitions the reference advertises in `LIVE`
	 * @param sampleReferencedPrimaryKey one referenced entity drawn from the family, for the narrowed control
	 */
	private record Probe(
		@Nonnull String entityType,
		@Nonnull String referenceName,
		int familySize,
		int sampleReferencedPrimaryKey
	) {
	}

}
