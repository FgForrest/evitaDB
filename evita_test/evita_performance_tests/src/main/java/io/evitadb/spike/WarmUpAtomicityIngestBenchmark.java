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

import com.github.javafaker.Faker;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.CacheOptions;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.EvitaConfiguration.Builder;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.configuration.StorageOptions;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.EntityMutation;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.core.transaction.memory.WarmUpSavepoint;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import io.evitadb.test.generator.DataGenerator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.referenceContent;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_CODE;
import static io.evitadb.test.generator.DataGenerator.ATTRIBUTE_URL;

/**
 * Measurement of what the bulk-ingest thread costs with per-entity warm-up atomicity in place.
 *
 * The mechanism under measurement is {@link WarmUpSavepoint}: `LocalMutationExecutorCollector` brackets every root
 * entity mutation with a savepoint, and every delegate-branch write made underneath journals the inverse that would
 * undo it. The question this program answers is how much of the ingest thread's time a bulk load costs, because that
 * thread is the bulk load's bottleneck — it runs ~94 % saturated (see
 * `documentation/adr/2026-07-31-bulk-ingest-write-path.md`), so its CPU time is very nearly the wall time of the whole
 * load.
 *
 * **Comparing two mechanisms is a cross-revision exercise, not an in-JVM one.** The mechanism has no runtime switch —
 * it is unconditional on the warm-up write path — so an A/B means building each git revision and running this program
 * against both with the same `--seed`, the same corpus knobs and the same machine, then comparing the reported
 * medians. Two runs that differ in any of those three are not comparable.
 *
 * **What is measured.** Primarily {@link ThreadMXBean#getCurrentThreadCpuTime()} of the thread executing the upserts,
 * sampled from inside the session lambda (which `EvitaSession#execute` runs on the caller thread, so the ingest thread
 * is the one that opens the session). Wall clock, thread allocation, process-wide CPU and GC totals are reported
 * alongside, because thread CPU time deliberately excludes work the mechanism pushes onto OTHER threads — most
 * importantly the garbage collection of the mementos and journal entries it allocates. A change visible in process CPU
 * or GC time but not in ingest-thread CPU is real cost that simply is not paid on the critical thread.
 *
 * **The corpus.** A synthetic e-commerce dataset built by {@link DataGenerator}, chosen to touch every family of
 * structure the mechanism has to journal:
 *
 * - **attributes** — filterable, sortable, localized (`en` + `cs`), plus catalog-level `code` / `url` declared
 *   globally unique, so the global unique index participates as well as the per-collection ones;
 * - **prices** — six price lists across four currencies, with a mix of indexed and non-indexed lists;
 * - **hierarchy** — `CATEGORY` is a real tree, so the hierarchy index and its placement structures are written;
 * - **references** — `PRODUCT` points at `BRAND`, `STORE`, `CATEGORY` and `PARAMETER` (the last two faceted /
 *   partitioned, `PARAMETER` grouped by `PARAMETER_GROUP`), and `CATEGORY` carries a REFLECTED reference back to
 *   `PRODUCT`, which is what makes a product upsert also write storage parts belonging to a different entity.
 *
 * **Why the mutation stream is materialized up front.** The corpus is generated exactly once, into a throwaway catalog
 * that is dropped immediately afterwards, and kept as a list of {@link EntityMutation}s. Every pass then replays the
 * very same mutation objects into a freshly created catalog. This removes generation from the measured window (it is
 * far more expensive than the ingest itself) and makes every pass — and every run of this program on the same seed —
 * identical by construction rather than merely by seed.
 *
 * **Protocol.** Passes run back to back in a single JVM. The first is discarded as JIT warm-up and the rest are
 * reported plus reduced to a median. Each pass gets its own catalog, which is verified against the expected entity
 * counts and then dropped, so no pass inherits another's indexes or files.
 *
 * Usage: `WarmUpAtomicityIngestBenchmark [--products=N] [--categories=N] [--brands=N] [--stores=N] [--parameters=N]
 * [--parameterGroups=N] [--passes=N] [--seed=N] [--dir=PATH]`. Run it with a heap large enough to hold the
 * materialized mutation stream AND one fully indexed catalog at a time — `-Xmx16g` is comfortable for the default
 * 50 000 products.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see WarmUpSavepoint
 */
public class WarmUpAtomicityIngestBenchmark implements EvitaTestSupport {

	/**
	 * Name of the reflected reference `CATEGORY` carries back to the products that reference it. Its presence is the
	 * reason a product upsert also mutates a category's storage parts and indexes, which is a case the mechanism has to
	 * journal and a plain single-collection corpus would never reach.
	 */
	private static final String REFERENCE_CATEGORY_PRODUCTS = "products";
	/**
	 * Catalog the corpus is generated into. It exists only so {@link DataGenerator} has schemas to generate against and
	 * is dropped as soon as the mutation stream has been materialized.
	 */
	private static final String CORPUS_CATALOG = "warmUpAtomicityCorpus";
	/**
	 * Prefix of the per-pass catalog names; the pass index is appended.
	 */
	private static final String PASS_CATALOG_PREFIX = "warmUpAtomicityPass";
	/**
	 * How many categories the post-pass verification fetches to confirm the reflected reference really was populated.
	 * A page rather than the whole collection, because one populated category already proves the cross-entity write
	 * path was taken.
	 */
	private static final int REFLECTED_REFERENCE_PROBE_SIZE = 100;
	/**
	 * How long to let the JVM settle after a forced collection and before a measured pass starts, so that the previous
	 * pass's background flushing and collection do not land inside the next pass's window.
	 */
	private static final long SETTLE_MILLIS = 1_000L;
	/**
	 * Thread bean used for the per-thread CPU and allocation readings.
	 */
	private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();
	/**
	 * The same bean under its `com.sun.management` interface when the running JVM offers it, which is what exposes
	 * per-thread allocation. `null` on a JVM that does not, in which case allocation is reported as unavailable rather
	 * than the whole measurement being refused.
	 */
	@Nullable
	private static final com.sun.management.ThreadMXBean SUN_THREAD_MX_BEAN =
		THREAD_MX_BEAN instanceof com.sun.management.ThreadMXBean sunBean ? sunBean : null;
	/**
	 * Operating-system bean under its `com.sun.management` interface, which is what exposes process-wide CPU time;
	 * `null` on a JVM that does not offer it.
	 */
	@Nullable
	private static final com.sun.management.OperatingSystemMXBean SUN_OS_MX_BEAN =
		ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean sunBean ?
			sunBean : null;

	/**
	 * Program entry point. See the class-level documentation for the full description.
	 *
	 * @param args optional `--key=value` overrides of {@link Options}
	 */
	public static void main(@Nonnull String[] args) {
		new WarmUpAtomicityIngestBenchmark().run(Options.parse(args));
	}

	/**
	 * Builds the configuration of the embedded instance every pass runs against. The cache is switched off because it
	 * only ever serves reads and would otherwise add an unrelated background task to a measurement of the write path;
	 * everything else stays at engine defaults so the numbers describe a stock bulk load.
	 *
	 * @param paths storage / work / export directory triplet the instance owns exclusively
	 * @return the assembled configuration
	 */
	@Nonnull
	private static EvitaConfiguration configuration(@Nonnull TestPaths paths) {
		final Builder builder = EvitaConfiguration.builder()
			.server(
				ServerOptions.builder()
					.queryTimeoutInMilliseconds(600_000)
					.transactionTimeoutInMilliseconds(600_000)
					.closeSessionsAfterSecondsOfInactivity(-1)
					.build()
			)
			.storage(
				StorageOptions.builder()
					.storageDirectory(paths.storage())
					.workDirectory(paths.work())
					.build()
			)
			.cache(
				CacheOptions.builder()
					.enabled(false)
					.build()
			);
		return builder.build();
	}

	/**
	 * Creates every schema of the corpus on the given catalog, in the order their cross-references require: the
	 * catalog-level globally unique attributes first, then the collections `PRODUCT` points at, then `CATEGORY` (whose
	 * reflected reference names a `PRODUCT` reference that does not exist yet — legal, it activates once `PRODUCT`
	 * arrives), then `PRODUCT` itself.
	 *
	 * Called once per catalog — for the corpus catalog and again for every pass — so all of them carry identical
	 * schemas and a mutation generated against one replays faithfully into another.
	 *
	 * @param session       open WARM_UP session of the catalog to define the schemas on
	 * @param dataGenerator generator supplying the sample schemas
	 * @return the six created schemas, in insertion order
	 */
	@Nonnull
	private static Map<String, SealedEntitySchema> defineSchemas(
		@Nonnull EvitaSessionContract session,
		@Nonnull DataGenerator dataGenerator
	) {
		session.updateCatalogSchema(
			session.getCatalogSchema()
				.openForWrite()
				.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.sortable().uniqueGlobally().nullable())
				.withAttribute(ATTRIBUTE_URL, String.class, whichIs -> whichIs.localized().uniqueGlobally().nullable())
		);

		final Map<String, SealedEntitySchema> schemas = new LinkedHashMap<>(8);
		schemas.put(Entities.BRAND, dataGenerator.getSampleBrandSchema(session));
		schemas.put(Entities.STORE, dataGenerator.getSampleStoreSchema(session));
		schemas.put(Entities.PARAMETER_GROUP, dataGenerator.getSampleParameterGroupSchema(session));
		schemas.put(Entities.PARAMETER, dataGenerator.getSampleParameterSchema(session));
		schemas.put(
			Entities.CATEGORY,
			dataGenerator.getSampleCategorySchema(
				session,
				// the cast picks the Consumer overload - the Function one takes over applying the schema, which is not
				// what is wanted here
				(Consumer<EntitySchemaBuilder>) schemaBuilder -> schemaBuilder.withReflectedReferenceToEntity(
					REFERENCE_CATEGORY_PRODUCTS,
					Entities.PRODUCT,
					Entities.CATEGORY,
					whichIs -> whichIs
						.withAttributesInherited()
						.withCardinality(Cardinality.ZERO_OR_MORE)
				)
			)
		);
		schemas.put(
			Entities.PRODUCT,
			dataGenerator.getSampleProductSchema(
				session,
				(Consumer<EntitySchemaBuilder>) schemaBuilder -> schemaBuilder.withReferenceToEntity(
					Entities.PARAMETER,
					Entities.PARAMETER,
					Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs.withGroupTypeRelatedToEntity(Entities.PARAMETER_GROUP).faceted()
				)
			)
		);
		return schemas;
	}

	/**
	 * Generates `count` entities of one collection and converts each into the {@link EntityMutation} a pass will
	 * replay. The `generatedCounts` entry of the collection is advanced AFTER each entity is finished, which is what
	 * makes {@link #referencedEntityPicker(Map)} hand out only primary keys of entities that will already have been
	 * inserted by the time this one is — the property the hierarchy generator relies on to pick a parent, and the
	 * reason the picker can be a pure function of the corpus instead of a query against a live collection.
	 *
	 * @param dataGenerator          generator producing the entities
	 * @param schema                 schema of the collection to generate
	 * @param referencedEntityPicker resolver handing out primary keys of already-generated referenced entities
	 * @param generatedCounts        per-collection counters of entities generated so far
	 * @param count                  number of entities to generate
	 * @param seed                   makes the generation reproducible
	 * @return the generated entities as replayable mutations, in generation order
	 */
	@Nonnull
	private static List<EntityMutation> materialize(
		@Nonnull DataGenerator dataGenerator,
		@Nonnull SealedEntitySchema schema,
		@Nonnull BiFunction<String, Faker, Integer> referencedEntityPicker,
		@Nonnull Map<String, int[]> generatedCounts,
		int count,
		long seed
	) {
		final List<EntityMutation> mutations = new ArrayList<>(count);
		final int[] counter = generatedCounts.get(schema.getName());
		if (counter == null) {
			throw new GenericEvitaInternalError(
				"Collection `" + schema.getName() + "` has no generation counter registered - every collection " +
					"of the corpus must be registered before it is generated, or the referenced-entity picker " +
					"would hand out primary keys of entities that do not exist yet.",
				"Corpus collection is missing its generation counter."
			);
		}
		dataGenerator.generateEntities(schema, referencedEntityPicker, seed)
			.limit(count)
			.forEach(builder -> {
				mutations.add(
					builder.toMutation()
						.orElseThrow(
							() -> new GenericEvitaInternalError(
								"Freshly generated entity of collection `" + schema.getName() + "` produced no " +
									"mutation - the corpus would silently be short of one entity.",
								"Generated entity produced no mutation."
							)
						)
				);
				counter[0]++;
			});
		return mutations;
	}

	/**
	 * Builds the resolver {@link DataGenerator} consults whenever it needs the primary key of a referenced entity —
	 * a hierarchy parent, a reference target, or a reference's group. It answers from the corpus's own generation
	 * counters, so it never needs a live collection and stays a pure function of how far generation has got.
	 *
	 * @param generatedCounts per-collection counters of entities generated so far
	 * @return resolver returning a random already-generated primary key, or `null` when the collection is still empty
	 */
	@Nonnull
	private static BiFunction<String, Faker, Integer> referencedEntityPicker(
		@Nonnull Map<String, int[]> generatedCounts
	) {
		return (entityType, faker) -> {
			final int[] counter = generatedCounts.get(entityType);
			if (counter == null) {
				throw new GenericEvitaInternalError(
					"Corpus generator asked for a referenced entity of collection `" + entityType + "`, which this " +
						"benchmark does not generate - the corpus shape and the schemas have drifted apart.",
					"Corpus generator asked for an unknown collection."
				);
			}
			// the counter is advanced only after an entity is finished, so the upper bound is the last entity that
			// will already have been inserted when the entity being generated now is
			return counter[0] == 0 ? null : faker.random().nextInt(1, counter[0]);
		};
	}

	/**
	 * Returns the number of bytes allocated by the calling thread so far, or `-1` when the running JVM does not expose
	 * per-thread allocation.
	 *
	 * @return allocated bytes, or `-1` when unavailable
	 */
	private static long currentThreadAllocatedBytes() {
		return SUN_THREAD_MX_BEAN == null ? -1L : SUN_THREAD_MX_BEAN.getCurrentThreadAllocatedBytes();
	}

	/**
	 * Returns the CPU time consumed by the whole process so far in nanoseconds, or `-1` when the running JVM does not
	 * expose it. Unlike the per-thread reading this includes the GC and flushing threads, which is where a share of the
	 * mechanism's cost lands.
	 *
	 * @return process CPU nanos, or `-1` when unavailable
	 */
	private static long processCpuTime() {
		return SUN_OS_MX_BEAN == null ? -1L : SUN_OS_MX_BEAN.getProcessCpuTime();
	}

	/**
	 * Returns the number of garbage collections performed since JVM start, summed over every collector.
	 *
	 * @return total collection count
	 */
	private static long gcCount() {
		long total = 0L;
		for (final GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
			final long count = bean.getCollectionCount();
			if (count > 0L) {
				total += count;
			}
		}
		return total;
	}

	/**
	 * Returns the approximate accumulated garbage collection time in milliseconds, summed over every collector.
	 *
	 * @return total collection time in milliseconds
	 */
	private static long gcTimeMillis() {
		long total = 0L;
		for (final GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
			final long time = bean.getCollectionTime();
			if (time > 0L) {
				total += time;
			}
		}
		return total;
	}

	/**
	 * Forces a collection and pauses, so that the debris of the pass just finished is reclaimed before the next pass's
	 * clock starts rather than inside its measured window.
	 */
	private static void settle() {
		System.gc();
		try {
			Thread.sleep(SETTLE_MILLIS);
		} catch (final InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while letting the JVM settle between passes.", ex);
		}
	}

	/**
	 * Returns the median of the supplied values. The caller guarantees a non-empty input.
	 *
	 * @param values values to reduce
	 * @return the median value
	 */
	private static double median(@Nonnull double[] values) {
		final double[] sorted = Arrays.copyOf(values, values.length);
		Arrays.sort(sorted);
		final int middle = sorted.length / 2;
		return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2.0;
	}

	/**
	 * Formats a nanosecond duration as seconds.
	 *
	 * @param nanos duration in nanoseconds, or `-1` when the reading was unavailable
	 * @return the duration in seconds, or `Double.NaN` when unavailable
	 */
	private static double seconds(long nanos) {
		return nanos < 0L ? Double.NaN : nanos / 1_000_000_000.0;
	}

	/**
	 * Runs the whole measurement: builds the corpus once, then replays it pass after pass and reports.
	 *
	 * @param options the parsed command-line options
	 */
	private void run(@Nonnull Options options) {
		if (!THREAD_MX_BEAN.isCurrentThreadCpuTimeSupported()) {
			throw new IllegalStateException(
				"This JVM does not support per-thread CPU time, which is the primary metric of this benchmark."
			);
		}
		THREAD_MX_BEAN.setThreadCpuTimeEnabled(true);

		final TestPaths paths = createTestPaths(options.directory(), "WarmUpAtomicityIngestBenchmark");
		final Evita evita = new Evita(configuration(paths));
		try {
			final Map<String, List<EntityMutation>> corpus = buildCorpus(evita, options);
			final List<PassResult> results = new ArrayList<>(options.passes());
			for (int pass = 0; pass < options.passes(); pass++) {
				results.add(runPass(evita, corpus, pass));
			}
			printReport(options, corpus, results);
		} finally {
			evita.close();
			cleanupTestPaths(paths);
		}
	}

	/**
	 * Generates the whole corpus once, into a throwaway catalog that is dropped again as soon as the mutations have
	 * been materialized. Everything here happens before any clock starts.
	 *
	 * @param evita   the embedded instance
	 * @param options the parsed command-line options
	 * @return the mutations to replay, keyed by collection in the order they must be inserted
	 */
	@Nonnull
	private Map<String, List<EntityMutation>> buildCorpus(@Nonnull Evita evita, @Nonnull Options options) {
		System.out.printf(
			"Generating corpus: %,d products, %,d categories, %,d brands, %,d stores, %,d parameters, " +
				"%,d parameter groups (seed %d)%n",
			options.productCount(), options.categoryCount(), options.brandCount(), options.storeCount(),
			options.parameterCount(), options.parameterGroupCount(), options.seed()
		);
		final long start = System.nanoTime();
		final Map<String, List<EntityMutation>> corpus = new LinkedHashMap<>(8);

		evita.deleteCatalogIfExists(CORPUS_CATALOG);
		evita.defineCatalog(CORPUS_CATALOG);
		evita.updateCatalog(
			CORPUS_CATALOG,
			session -> {
				final DataGenerator dataGenerator = new DataGenerator();
				final Map<String, SealedEntitySchema> schemas = defineSchemas(session, dataGenerator);
				// every collection is registered up front, so the picker can tell "not generated yet" (counter at
				// zero) from "not part of this corpus at all" (no entry, which is a programming error)
				final Map<String, int[]> generatedCounts = new LinkedHashMap<>(8);
				for (final String entityType : schemas.keySet()) {
					generatedCounts.put(entityType, new int[1]);
				}
				final BiFunction<String, Faker, Integer> picker = referencedEntityPicker(generatedCounts);

				// insertion order: everything PRODUCT references first, PRODUCT last - a reference target has to exist
				// by the time the referencing entity is upserted, and CATEGORY's reflected reference is only filled in
				// when the products arrive
				corpus.put(
					Entities.BRAND,
					materialize(
						dataGenerator, schemas.get(Entities.BRAND), picker, generatedCounts,
						options.brandCount(), options.seed()
					)
				);
				corpus.put(
					Entities.STORE,
					materialize(
						dataGenerator, schemas.get(Entities.STORE), picker, generatedCounts,
						options.storeCount(), options.seed()
					)
				);
				corpus.put(
					Entities.PARAMETER_GROUP,
					materialize(
						dataGenerator, schemas.get(Entities.PARAMETER_GROUP), picker, generatedCounts,
						options.parameterGroupCount(), options.seed()
					)
				);
				corpus.put(
					Entities.PARAMETER,
					materialize(
						dataGenerator, schemas.get(Entities.PARAMETER), picker, generatedCounts,
						options.parameterCount(), options.seed()
					)
				);
				corpus.put(
					Entities.CATEGORY,
					materialize(
						dataGenerator, schemas.get(Entities.CATEGORY), picker, generatedCounts,
						options.categoryCount(), options.seed()
					)
				);
				corpus.put(
					Entities.PRODUCT,
					materialize(
						dataGenerator, schemas.get(Entities.PRODUCT), picker, generatedCounts,
						options.productCount(), options.seed()
					)
				);
			}
		);
		evita.deleteCatalogIfExists(CORPUS_CATALOG);

		long total = 0L;
		for (final List<EntityMutation> mutations : corpus.values()) {
			total += mutations.size();
		}
		System.out.printf(
			"Corpus materialized: %,d mutations in %.1f s%n", total, seconds(System.nanoTime() - start)
		);
		return corpus;
	}

	/**
	 * Runs one measured pass: a fresh catalog, the schemas (untimed), then the corpus replayed into it, then
	 * `goLiveAndClose()`, then verification, then the catalog is dropped.
	 *
	 * @param evita  the embedded instance
	 * @param corpus the mutations to replay, keyed by collection in insertion order
	 * @param pass   index of this pass
	 * @return the measurement of this pass
	 */
	@Nonnull
	private PassResult runPass(
		@Nonnull Evita evita,
		@Nonnull Map<String, List<EntityMutation>> corpus,
		int pass
	) {
		final String catalogName = PASS_CATALOG_PREFIX + pass;
		evita.deleteCatalogIfExists(catalogName);
		evita.defineCatalog(catalogName);
		// schema creation is a fixed cost unrelated to the mechanism, so it happens in its own session, untimed
		evita.updateCatalog(
			catalogName,
			session -> {
				defineSchemas(session, new DataGenerator());
			}
		);

		settle();

		final long gcCountBefore = gcCount();
		final long gcTimeBefore = gcTimeMillis();
		// mirrors the array-holder idiom of WarmupCopyCatalogBenchmark: the session lambda is a Consumer, so the
		// readings taken on the ingest thread have to be handed out through a holder
		final PassResult[] holder = new PassResult[1];
		evita.updateCatalog(
			catalogName,
			session -> {
				// EvitaSession#execute runs this on the caller thread, so "the ingest thread" is simply this one; its
				// name is carried into the report so a reader can confirm the per-thread readings describe the thread
				// that did the work rather than one that only waited for it
				final String ingestThreadName = Thread.currentThread().getName();
				final long wallStart = System.nanoTime();
				final long cpuStart = THREAD_MX_BEAN.getCurrentThreadCpuTime();
				final long allocStart = currentThreadAllocatedBytes();
				final long processCpuStart = processCpuTime();

				for (final Entry<String, List<EntityMutation>> collection : corpus.entrySet()) {
					final List<EntityMutation> mutations = collection.getValue();
					for (int i = 0; i < mutations.size(); i++) {
						session.upsertEntity(mutations.get(i));
					}
				}

				final long ingestWallNanos = System.nanoTime() - wallStart;
				final long ingestCpuNanos = THREAD_MX_BEAN.getCurrentThreadCpuTime() - cpuStart;
				final long ingestAllocatedBytes = allocStart < 0L ? -1L : currentThreadAllocatedBytes() - allocStart;
				final long ingestProcessCpuNanos = processCpuStart < 0L ? -1L : processCpuTime() - processCpuStart;

				final long goLiveWallStart = System.nanoTime();
				final long goLiveProcessCpuStart = processCpuTime();
				// switches the catalog from WARM_UP to ALIVE (and closes this session); the work is completed on
				// engine threads this one only waits for, which is why it gets a process-CPU reading rather than a
				// thread-CPU one
				session.goLiveAndClose();

				holder[0] = new PassResult(
					pass, ingestThreadName,
					ingestWallNanos, ingestCpuNanos, ingestAllocatedBytes, ingestProcessCpuNanos,
					System.nanoTime() - goLiveWallStart,
					goLiveProcessCpuStart < 0L ? -1L : processCpuTime() - goLiveProcessCpuStart,
					gcCount() - gcCountBefore,
					gcTimeMillis() - gcTimeBefore
				);
			}
		);

		verify(evita, catalogName, corpus);
		evita.deleteCatalogIfExists(catalogName);

		return holder[0];
	}

	/**
	 * Confirms the pass actually ingested everything, by comparing the entity count the (now ALIVE) catalog reports for
	 * each collection against the size of the replayed mutation list. A pass whose counts drift is not comparable with
	 * its partner, so a mismatch fails the whole run rather than being reported as a footnote.
	 *
	 * @param evita       the embedded instance
	 * @param catalogName the catalog just populated
	 * @param corpus      the mutations that were replayed, keyed by collection
	 */
	private void verify(
		@Nonnull Evita evita,
		@Nonnull String catalogName,
		@Nonnull Map<String, List<EntityMutation>> corpus
	) {
		final StringBuilder mismatches = new StringBuilder(128);
		evita.queryCatalog(
			catalogName,
			session -> {
				for (final Entry<String, List<EntityMutation>> collection : corpus.entrySet()) {
					final int expected = collection.getValue().size();
					final int actual = session.getEntityCollectionSize(collection.getKey());
					if (actual != expected) {
						mismatches.append(System.lineSeparator())
							.append("  ").append(collection.getKey())
							.append(": expected ").append(expected)
							.append(", found ").append(actual);
					}
				}
				// the reflected reference is the corpus's only cross-entity write, and a corpus that happened to
				// generate no product-to-category reference would measure a materially cheaper mechanism while still
				// passing every count check above - so its presence is asserted rather than assumed
				final List<SealedEntity> categories = session.queryListOfSealedEntities(
					Query.query(
						collection(Entities.CATEGORY),
						require(
							page(1, REFLECTED_REFERENCE_PROBE_SIZE),
							entityFetch(referenceContent(REFERENCE_CATEGORY_PRODUCTS))
						)
					)
				);
				boolean anyReflected = false;
				for (int i = 0; i < categories.size(); i++) {
					if (!categories.get(i).getReferences(REFERENCE_CATEGORY_PRODUCTS).isEmpty()) {
						anyReflected = true;
						break;
					}
				}
				if (!anyReflected) {
					mismatches.append(System.lineSeparator())
						.append("  none of the first ").append(REFLECTED_REFERENCE_PROBE_SIZE)
						.append(" categories carries a reflected `").append(REFERENCE_CATEGORY_PRODUCTS)
						.append("` reference - the corpus is not exercising cross-entity writes");
				}
			}
		);
		if (mismatches.length() > 0) {
			throw new IllegalStateException(
				"Catalog `" + catalogName + "` does not hold the expected corpus:" + mismatches
			);
		}
	}

	/**
	 * Prints the per-pass table and the reduction of the measured passes to medians. The medians are the numbers to
	 * quote and the numbers to compare against another git revision's run of this same program on the same seed and
	 * machine — see the class documentation for why an A/B cannot happen inside one JVM.
	 *
	 * @param options the parsed command-line options
	 * @param corpus  the replayed corpus, for the entity total
	 * @param results every pass, in execution order
	 */
	private void printReport(
		@Nonnull Options options,
		@Nonnull Map<String, List<EntityMutation>> corpus,
		@Nonnull List<PassResult> results
	) {
		long entities = 0L;
		for (final List<EntityMutation> mutations : corpus.values()) {
			entities += mutations.size();
		}

		System.out.println("==============================================================================");
		System.out.printf("Corpus                  : %,d entities%n", entities);
		for (final Entry<String, List<EntityMutation>> collection : corpus.entrySet()) {
			System.out.printf("  %-18s: %,d%n", collection.getKey(), collection.getValue().size());
		}
		System.out.printf("Ingest thread           : %s%n", results.get(0).ingestThreadName());
		System.out.printf("Passes (first discarded): %d%n", options.passes());
		System.out.println("------------------------------------------------------------------------------");
		System.out.printf(
			"%-5s %10s %10s %11s %9s %9s %8s %10s%n",
			"pass", "wall s", "cpu s", "ent/s(cpu)", "alloc GB", "procCpu s", "gc ms", "goLive s"
		);
		for (final PassResult result : results) {
			System.out.printf(
				"%-5d %10.2f %10.2f %11.0f %9s %9.2f %8d %10.2f%n",
				result.pass(),
				seconds(result.ingestWallNanos()),
				seconds(result.ingestCpuNanos()),
				entities / seconds(result.ingestCpuNanos()),
				result.ingestAllocatedBytes() < 0L ?
					"n/a" : String.format("%.2f", result.ingestAllocatedBytes() / (1024.0 * 1024.0 * 1024.0)),
				seconds(result.ingestProcessCpuNanos()),
				result.gcTimeMillis(),
				seconds(result.goLiveWallNanos())
			);
		}
		System.out.println("------------------------------------------------------------------------------");

		// the first pass is JIT warm-up and is never part of the verdict
		final int measuredPasses = options.passes() - 1;
		if (measuredPasses < 1) {
			System.out.println(
				"Only the warm-up pass was run - re-run with --passes=2 or more to get a number worth quoting."
			);
			System.out.println("==============================================================================");
			return;
		}
		final double[] cpuSeconds = new double[measuredPasses];
		final double[] wallSeconds = new double[measuredPasses];
		final double[] allocatedGigabytes = new double[measuredPasses];
		for (int pass = 1; pass < options.passes(); pass++) {
			final PassResult result = results.get(pass);
			cpuSeconds[pass - 1] = seconds(result.ingestCpuNanos());
			wallSeconds[pass - 1] = seconds(result.ingestWallNanos());
			allocatedGigabytes[pass - 1] = result.ingestAllocatedBytes() < 0L ?
				Double.NaN : result.ingestAllocatedBytes() / (1024.0 * 1024.0 * 1024.0);
		}
		final double medianCpuSeconds = median(cpuSeconds);
		System.out.printf("Median ingest-thread CPU    : %.2f s%n", medianCpuSeconds);
		System.out.printf("Median ingest throughput    : %,.0f entities/s (cpu)%n", entities / medianCpuSeconds);
		System.out.printf("Median ingest wall clock    : %.2f s%n", median(wallSeconds));
		System.out.printf("Median ingest allocation    : %.2f GB%n", median(allocatedGigabytes));
		System.out.println("==============================================================================");
	}

	/**
	 * Corpus shape and protocol knobs of one run, all overridable from the command line.
	 *
	 * @param productCount        number of `PRODUCT` entities - the dominant cost and the knob to turn for run length
	 * @param categoryCount       number of `CATEGORY` entities, organised into a hierarchy
	 * @param brandCount          number of `BRAND` entities
	 * @param storeCount          number of `STORE` entities
	 * @param parameterCount      number of `PARAMETER` entities
	 * @param parameterGroupCount number of `PARAMETER_GROUP` entities
	 * @param passes              number of passes to run; the first is discarded as JIT warm-up
	 * @param seed                seed of the corpus generation
	 * @param directory           directory the throwaway storage of this run lives under
	 */
	private record Options(
		int productCount,
		int categoryCount,
		int brandCount,
		int storeCount,
		int parameterCount,
		int parameterGroupCount,
		int passes,
		long seed,
		@Nonnull Path directory
	) {

		/**
		 * Default scratch location of the throwaway storage, chosen so a run never touches the shared test directory.
		 */
		private static final String DEFAULT_DIRECTORY = System.getProperty("java.io.tmpdir") + "/warmUpAtomicityBench";

		/**
		 * Parses `--key=value` arguments over the defaults.
		 *
		 * @param args raw command-line arguments
		 * @return the assembled options
		 */
		@Nonnull
		static Options parse(@Nonnull String[] args) {
			int productCount = 50_000;
			int categoryCount = 2_000;
			int brandCount = 1_000;
			int storeCount = 12;
			int parameterCount = 200;
			int parameterGroupCount = 20;
			int passes = 6;
			long seed = 42L;
			Path directory = Path.of(DEFAULT_DIRECTORY);

			for (final String arg : args) {
				final int separator = arg.indexOf('=');
				if (!arg.startsWith("--") || separator < 0) {
					throw new IllegalArgumentException(
						"Unrecognized argument `" + arg + "` - expected `--key=value`. Supported keys: products, " +
							"categories, brands, stores, parameters, parameterGroups, passes, seed, dir."
					);
				}
				final String key = arg.substring(2, separator);
				final String value = arg.substring(separator + 1);
				switch (key) {
					case "products" -> productCount = Integer.parseInt(value);
					case "categories" -> categoryCount = Integer.parseInt(value);
					case "brands" -> brandCount = Integer.parseInt(value);
					case "stores" -> storeCount = Integer.parseInt(value);
					case "parameters" -> parameterCount = Integer.parseInt(value);
					case "parameterGroups" -> parameterGroupCount = Integer.parseInt(value);
					case "passes" -> passes = Integer.parseInt(value);
					case "seed" -> seed = Long.parseLong(value);
					case "dir" -> directory = Path.of(value);
					default -> throw new IllegalArgumentException(
						"Unknown option `--" + key + "`. Supported keys: products, categories, brands, stores, " +
							"parameters, parameterGroups, passes, seed, dir."
					);
				}
			}
			if (passes < 1) {
				throw new IllegalArgumentException(
					"At least one pass has to be run, got `--passes=" + passes + "`."
				);
			}
			return new Options(
				productCount, categoryCount, brandCount, storeCount, parameterCount, parameterGroupCount,
				passes, seed, directory
			);
		}
	}

	/**
	 * Everything one pass measured.
	 *
	 * @param pass                  index of this pass
	 * @param ingestThreadName      name of the thread that executed the upserts, reported so the reader can confirm
	 *                              the per-thread readings describe the thread they are supposed to
	 * @param ingestWallNanos       wall clock of the ingest loop
	 * @param ingestCpuNanos        CPU time the ingest thread spent in the ingest loop - the primary metric
	 * @param ingestAllocatedBytes  bytes the ingest thread allocated in the ingest loop, or `-1` when unavailable
	 * @param ingestProcessCpuNanos CPU time the WHOLE process spent during the ingest loop, or `-1` when unavailable
	 * @param goLiveWallNanos       wall clock of the WARM_UP to ALIVE transition
	 * @param goLiveProcessCpuNanos CPU time the whole process spent during the transition, or `-1` when unavailable
	 * @param gcCount               garbage collections that happened during the pass
	 * @param gcTimeMillis          approximate garbage collection time during the pass
	 */
	private record PassResult(
		int pass,
		@Nonnull String ingestThreadName,
		long ingestWallNanos,
		long ingestCpuNanos,
		long ingestAllocatedBytes,
		long ingestProcessCpuNanos,
		long goLiveWallNanos,
		long goLiveProcessCpuNanos,
		long gcCount,
		long gcTimeMillis
	) {
	}

}
