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
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.FacetStatisticsDepth;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SealedEntitySchema;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.performance.generators.RandomQueryGenerator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllAnd;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;

/**
 * End-to-end timing of a realistic facet / hierarchy / attribute / price query mix against a restored production
 * catalog, for comparing two builds of the roaring bitmap module.
 *
 * The harness carries **no instrumentation and no dependency on any census hook**, so it compiles and runs against
 * a clean checkout as well as against a build that adds vector kernels. Everything it prints is wall clock, plus
 * the matched-record total that proves both builds answered the same questions.
 *
 * The mix is 2,600 queries built from the `Client*` benchmark states' own generators with a fixed seed, so two runs
 * of this class ask exactly the same questions in exactly the same order:
 *
 * - 800 facet + hierarchy summaries at {@link FacetStatisticsDepth#COUNTS}
 * - 400 facet + hierarchy summaries at {@link FacetStatisticsDepth#IMPACT}
 * - 800 facet summaries at {@link FacetStatisticsDepth#COUNTS}, no hierarchy constraint
 * - 300 attribute filtering queries with an attribute sort
 * - 300 price filtering queries with a price sort
 *
 * Two deviations from the benchmark states are deliberate and are what makes the hierarchy and facet queries run
 * at all. The states key their faceted-reference map by *referenced entity type* while both
 * `RandomQueryGenerator#updateFacetStatistics` and `facetHaving` key by *reference name*, so keyed the states' way
 * the map stays empty for every reference whose name differs from its entity type. And `hierarchyWithin` names a
 * reference rather than an entity type, so passing `"Category"` as the states do makes every hierarchy query fail;
 * the reference name is resolved from the schema here instead.
 *
 * The generator statistics come from a sample of {@link #STATISTICS_SAMPLE_PRODUCTS} products rather than from a
 * full catalog read, which keeps startup to a few seconds. The sample is deterministic, so both builds generate
 * the same queries.
 *
 * ```
 * java -Xmx48g -XX:+ExitOnOutOfMemoryError \
 *      --add-modules jdk.incubator.vector \
 *      -cp @classpath.txt \
 *      io.evitadb.spike.RoaringQueryMixTiming <storageDirectory> <catalogName> [timedPasses]
 * ```
 *
 * `storageDirectory` is the folder holding the catalog's data folder, `catalogName` the catalog to open, and
 * `timedPasses` how many measured passes follow the two warm-up passes (default {@value #DEFAULT_TIMED_PASSES}).
 * `--add-modules jdk.incubator.vector` is only needed by a build whose kernels use the Vector API; the JVM
 * arguments are printed at startup so a run that forgot the flag is recognisable in the log.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class RoaringQueryMixTiming implements RandomQueryGenerator {

	/**
	 * How many measured passes run when the command line does not say.
	 */
	public static final int DEFAULT_TIMED_PASSES = 5;
	/**
	 * How many passes run before the first measured one.
	 */
	private static final int WARM_UP_PASSES = 2;
	/**
	 * How long the harness waits for the catalog's background load to finish before giving up.
	 */
	private static final long LOAD_TIMEOUT_NANOS = 30L * 60L * 1_000_000_000L;
	/**
	 * Fixed seed, so the generated query mix is the same on every run and on every build.
	 */
	private static final long SEED = 40L;
	/**
	 * The entity type the query mix filters.
	 */
	private static final String PRODUCT_ENTITY_TYPE = "Product";
	/**
	 * The hierarchy entity type the hierarchy queries walk.
	 */
	private static final String CATEGORY_ENTITY_TYPE = "Category";
	/**
	 * How many products are read with all their data to build the facet / attribute / price statistics the query
	 * generators need.
	 */
	private static final int STATISTICS_SAMPLE_PRODUCTS = 25_000;
	/**
	 * Page size used while sampling.
	 */
	private static final int PAGE_SIZE = 500;
	/**
	 * Query-kind labels, in the order they are printed.
	 */
	private static final String KIND_FACET_HIERARCHY_COUNTS = "facet+hierarchy summary COUNTS";
	private static final String KIND_FACET_HIERARCHY_IMPACT = "facet+hierarchy summary IMPACT";
	private static final String KIND_FACET_COUNTS = "facet summary COUNTS";
	private static final String KIND_ATTRIBUTE = "attribute filtering";
	private static final String KIND_PRICE = "price filtering";
	/**
	 * Fully qualified names of the kernel-provider summary methods this harness will print when one is present.
	 * Both spellings are tried because the provider had not landed when this harness was written.
	 */
	private static final String[][] KERNEL_SUMMARY_CANDIDATES = {
		{"io.evitadb.roaringbitmap.RoaringKernels", "vectorKernelsSummary"},
		{"io.evitadb.roaringbitmap.kernel.VectorKernels", "summary"},
		{"io.evitadb.roaringbitmap.kernel.RoaringKernels", "vectorKernelsSummary"}
	};

	private final Random random = new Random(SEED);
	private final Map<String, Set<Integer>> facetedReferences = new LinkedHashMap<>();
	private final Map<String, Map<Integer, Integer>> facetGroupsIndex = new LinkedHashMap<>();
	private final Map<String, AttributeStatistics> filterableAttributes = new LinkedHashMap<>();
	private final Set<String> sortableAttributes = new HashSet<>();
	private final GlobalPriceStatistics priceStatistics = new GlobalPriceStatistics();
	private final List<Integer> categoryIds = new ArrayList<>();
	private SealedEntitySchema productSchema;
	/**
	 * Name of the product reference that points at the hierarchical category collection. `hierarchyWithin` names a
	 * reference, not an entity type, so passing the entity type makes every hierarchy query fail at parse time.
	 */
	private String categoryReferenceName;

	public static void main(@Nonnull String[] args) {
		if (args.length < 2) {
			System.err.println(
				"Usage: RoaringQueryMixTiming <storageDirectory> <catalogName> [timedPasses]"
			);
			System.exit(1);
		}
		final Path storageDirectory = Path.of(args[0]);
		final String catalogName = args[1];
		final int timedPasses = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_TIMED_PASSES;
		final Path queriesFile = args.length > 3 ? Path.of(args[3]) : null;
		new RoaringQueryMixTiming().run(storageDirectory, catalogName, timedPasses, queriesFile);
	}

	/**
	 * Boots the engine, builds the query mix and runs the warm-up and measured passes.
	 *
	 * @param storageDirectory where the restored catalog lives
	 * @param catalogName      the catalog to open
	 * @param timedPasses      how many measured passes follow the warm-up
	 */
	private void run(
		@Nonnull Path storageDirectory, @Nonnull String catalogName, int timedPasses, @Nullable Path queriesFile
	) {
		try {
			runUnchecked(storageDirectory, catalogName, timedPasses, queriesFile);
		} catch (final IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void runUnchecked(
		@Nonnull Path storageDirectory, @Nonnull String catalogName, int timedPasses, @Nullable Path queriesFile
	) throws IOException {
		reportEnvironment();
		final long bootStart = System.nanoTime();
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
			System.out.printf(
				"%nCatalog `%s` version %d, state %s, loaded in %,d ms%n",
				catalogName, catalog.getVersion(), catalog.getCatalogState(),
				(System.nanoTime() - bootStart) / 1_000_000L
			);

			final List<QueryCase> queries;
			if (queriesFile != null && Files.isReadable(queriesFile)) {
				// replay the exact query set another run wrote (Java serialization of the Query objects, which are
				// Serializable), so two builds do identical work by construction
				queries = new ArrayList<>(2_600);
				try (
					final ObjectInputStream in = new ObjectInputStream(
						new BufferedInputStream(Files.newInputStream(queriesFile))
					)
				) {
					final int count = in.readInt();
					for (int i = 0; i < count; i++) {
						final String kind = in.readUTF();
						final Query query = (Query) in.readObject();
						queries.add(new QueryCase(kind, query));
					}
				} catch (final ClassNotFoundException e) {
					throw new IllegalStateException(e);
				}
				System.out.printf("loaded %,d queries from %s%n", queries.size(), queriesFile);
			} else {
				harvestStatistics(evita, catalogName);
				queries = generateQueries();
				if (queriesFile != null) {
					try (
						final ObjectOutputStream out = new ObjectOutputStream(
							new BufferedOutputStream(Files.newOutputStream(queriesFile))
						)
					) {
						out.writeInt(queries.size());
						for (QueryCase queryCase : queries) {
							out.writeUTF(queryCase.kind());
							out.writeObject(queryCase.query());
						}
					}
					System.out.printf("wrote %,d queries to %s%n", queries.size(), queriesFile);
				}
			}
			long checksum = 17L;
			for (QueryCase queryCase : queries) {
				checksum = 31L * checksum + queryCase.query().toString().hashCode();
			}
			System.out.printf("generated %,d queries with seed %d, query checksum %d%n%n", queries.size(), SEED, checksum);
			if (queries.size() != 2_600) {
				System.out.printf(
					"WARNING: expected 2,600 queries but generated %,d - the two builds are only comparable "
						+ "if this number matches%n%n", queries.size()
				);
			}

			for (int pass = 1; pass <= WARM_UP_PASSES; pass++) {
				final PassResult warmUp = replay(evita, catalogName, queries);
				System.out.printf(
					"warm-up pass %d/%d: %,d ms, %,d records, %,d failures%n",
					pass, WARM_UP_PASSES, warmUp.wallNanos() / 1_000_000L, warmUp.records(), warmUp.failures()
				);
			}
			System.out.println();

			final List<PassResult> measured = new ArrayList<>(timedPasses);
			for (int pass = 1; pass <= timedPasses; pass++) {
				final PassResult result = replay(evita, catalogName, queries);
				measured.add(result);
				printPass(pass, timedPasses, result);
			}
			printSummary(measured);
		}
	}

	/**
	 * Prints what the JVM was started with and which kernel provider, if any, is on the classpath. Both lines exist
	 * so a run that forgot `--add-modules jdk.incubator.vector`, or that measured the wrong build, is obvious in
	 * the log rather than only in the numbers.
	 */
	private static void reportEnvironment() {
		System.out.printf(
			"java %s (%s), %s%n",
			System.getProperty("java.version"), System.getProperty("java.vendor"),
			System.getProperty("os.arch")
		);
		System.out.println("JVM arguments: " + ManagementFactory.getRuntimeMXBean().getInputArguments());
		final String summary = kernelSummary();
		System.out.println(
			"roaring kernel provider: " + (summary == null ? "not present on this classpath" : summary)
		);
	}

	/**
	 * Reads the roaring kernel provider's one-line summary reflectively, so this harness also runs against a build
	 * that has no such class.
	 *
	 * @return the summary, or `null` when no provider is on the classpath
	 */
	@Nullable
	private static String kernelSummary() {
		for (String[] candidate : KERNEL_SUMMARY_CANDIDATES) {
			try {
				final Method method = Class.forName(candidate[0]).getMethod(candidate[1]);
				final Object value = method.invoke(null);
				return candidate[0] + "#" + candidate[1] + "() = " + value;
			} catch (final Throwable ignored) {
				// a missing class, a missing method or a provider that refuses to initialise all mean the same
				// thing here - this build has no kernel provider to report
			}
		}
		return null;
	}

	/**
	 * Reads a sample of products with everything attached plus every category primary key, and feeds the benchmark
	 * states' own statistics collectors with them.
	 *
	 * @param evita       the running engine
	 * @param catalogName the catalog to read
	 */
	private void harvestStatistics(@Nonnull Evita evita, @Nonnull String catalogName) {
		final long start = System.nanoTime();
		evita.queryCatalog(
			catalogName,
			session -> {
				this.productSchema = session.getEntitySchema(PRODUCT_ENTITY_TYPE).orElseThrow();
				for (ReferenceSchemaContract reference : this.productSchema.getReferences().values()) {
					if (this.categoryReferenceName == null
						&& CATEGORY_ENTITY_TYPE.equals(reference.getReferencedEntityType())) {
						this.categoryReferenceName = reference.getName();
					}
					if (reference.isFaceted()) {
						this.facetedReferences.put(reference.getName(), new HashSet<>());
						this.facetGroupsIndex.put(reference.getName(), new HashMap<>());
					}
				}
				for (AttributeSchemaContract attribute : this.productSchema.getAttributes().values()) {
					if (attribute.isSortable()) {
						this.sortableAttributes.add(attribute.getName());
					}
					if (!attribute.getName().startsWith("validity::")
						&& (attribute.isFilterable() || attribute.isUnique()) && attribute.isNullable()) {
						this.filterableAttributes.put(attribute.getName(), new AttributeStatistics(attribute));
					}
				}

				int read = 0;
				int pageNumber = 1;
				EvitaResponse<SealedEntity> response;
				do {
					response = session.query(
						Query.query(
							collection(PRODUCT_ENTITY_TYPE),
							require(entityFetchAllAnd(page(pageNumber++, PAGE_SIZE)))
						),
						SealedEntity.class
					);
					for (SealedEntity entity : response.getRecordData()) {
						updateFacetStatistics(entity, this.facetedReferences, this.facetGroupsIndex);
						updateAttributeStatistics(entity, this.random, this.filterableAttributes);
						updatePriceStatistics(entity, this.random, this.priceStatistics);
						read++;
					}
				} while (response.getRecordPage().hasNext() && read < STATISTICS_SAMPLE_PRODUCTS);

				pageNumber = 1;
				EvitaResponse<EntityReference> categories;
				do {
					categories = session.query(
						Query.query(collection(CATEGORY_ENTITY_TYPE), require(page(pageNumber++, 1_000))),
						EntityReference.class
					);
					for (EntityReference reference : categories.getRecordData()) {
						this.categoryIds.add(reference.getPrimaryKeyOrThrowException());
					}
				} while (categories.getRecordPage().hasNext());
				System.out.printf(
					"harvested from %,d products and %,d categories in %,d ms; hierarchy reference `%s`%n",
					read, this.categoryIds.size(), (System.nanoTime() - start) / 1_000_000L,
					this.categoryReferenceName
				);
				return null;
			}
		);
		this.filterableAttributes.entrySet().removeIf(it -> !hasAnyValue(it.getValue()));
		this.facetedReferences.entrySet().removeIf(it -> it.getValue().isEmpty());
	}

	/**
	 * Whether the sample carried at least one value of this attribute, in any of the schema's locales.
	 *
	 * @param statistics the attribute's statistics
	 * @return `true` when a predicate can be generated for it
	 */
	private boolean hasAnyValue(@Nonnull AttributeStatistics statistics) {
		if (statistics.getStatistics(null) != null) {
			return true;
		}
		for (Locale locale : this.productSchema.getLocales()) {
			if (statistics.getStatistics(locale) != null) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Builds the query mix once, so every pass and every build replays exactly the same queries.
	 *
	 * @return the generated queries
	 */
	@Nonnull
	private List<QueryCase> generateQueries() {
		final List<QueryCase> result = new ArrayList<>(2_600);
		if (this.facetedReferences.isEmpty()) {
			System.out.println("WARNING: no facets harvested, facet queries skipped");
		} else {
			if (this.categoryReferenceName == null) {
				System.out.println("WARNING: no hierarchy reference on Product, hierarchy queries skipped");
			} else {
				for (int i = 0; i < 800; i++) {
					result.add(new QueryCase(KIND_FACET_HIERARCHY_COUNTS, facetHierarchyQuery(FacetStatisticsDepth.COUNTS)));
				}
				for (int i = 0; i < 400; i++) {
					result.add(new QueryCase(KIND_FACET_HIERARCHY_IMPACT, facetHierarchyQuery(FacetStatisticsDepth.IMPACT)));
				}
			}
			for (int i = 0; i < 800; i++) {
				result.add(new QueryCase(KIND_FACET_COUNTS, facetQuery()));
			}
		}
		// `pickRandom` asserts a non-empty set and indexes with `nextInt(size - 1)`, so a single-entry map throws
		if (this.filterableAttributes.size() > 1 && !this.sortableAttributes.isEmpty()) {
			for (int i = 0; i < 300; i++) {
				result.add(new QueryCase(
					KIND_ATTRIBUTE,
					generateRandomAttributeQuery(
						this.random, this.productSchema, this.filterableAttributes, this.sortableAttributes
					)
				));
			}
		} else {
			System.out.println("WARNING: too few filterable or sortable attributes harvested, attribute queries skipped");
		}
		if (this.priceStatistics.getCurrencies().isEmpty()) {
			System.out.println("WARNING: no prices harvested, price queries skipped");
		} else {
			for (int i = 0; i < 300; i++) {
				result.add(new QueryCase(
					KIND_PRICE, generateRandomPriceQuery(this.random, this.productSchema, this.priceStatistics)
				));
			}
		}
		return result;
	}

	/**
	 * @param depth how deep the facet summary is computed
	 * @return one facet-and-hierarchy query
	 */
	@Nonnull
	private Query facetHierarchyQuery(@Nonnull FacetStatisticsDepth depth) {
		return generateRandomHierarchyQuery(
			generateRandomFacetSummaryQuery(
				generateRandomFacetQuery(this.random, this.productSchema, this.facetedReferences),
				this.random, this.productSchema, depth, this.facetGroupsIndex
			),
			this.random, this.categoryIds, this.categoryReferenceName
		);
	}

	/**
	 * @return one facet query summarising facet counts, without a hierarchy constraint
	 */
	@Nonnull
	private Query facetQuery() {
		return generateRandomFacetSummaryQuery(
			generateRandomFacetQuery(this.random, this.productSchema, this.facetedReferences),
			this.random, this.productSchema, FacetStatisticsDepth.COUNTS, this.facetGroupsIndex
		);
	}

	/**
	 * Replays the whole query list in one read-only session on a single thread.
	 *
	 * @param evita       the running engine
	 * @param catalogName the catalog to query
	 * @param queries     the queries to replay
	 * @return the pass's timing
	 */
	@Nonnull
	private static PassResult replay(
		@Nonnull Evita evita,
		@Nonnull String catalogName,
		@Nonnull List<QueryCase> queries
	) {
		final Map<String, long[]> perKind = new LinkedHashMap<>();
		long records = 0L;
		int failures = 0;
		final long start = System.nanoTime();
		try (final EvitaSessionContract session = evita.createReadOnlySession(catalogName)) {
			for (QueryCase queryCase : queries) {
				final long queryStart = System.nanoTime();
				try {
					records += session.query(queryCase.query(), EntityReference.class).getTotalRecordCount();
				} catch (final RuntimeException e) {
					if (failures++ < 5) {
						System.out.printf("  query failed (%s): %s%n", queryCase.kind(), e.getMessage());
					}
				}
				final long[] accumulator = perKind.computeIfAbsent(queryCase.kind(), it -> new long[3]);
				accumulator[0] += System.nanoTime() - queryStart;
				accumulator[1]++;
				accumulator[2] = records;
			}
		}
		return new PassResult(System.nanoTime() - start, records, failures, perKind);
	}

	/**
	 * Prints one measured pass.
	 *
	 * @param pass   this pass's ordinal
	 * @param passes how many measured passes there are
	 * @param result the pass's timing
	 */
	private static void printPass(int pass, int passes, @Nonnull PassResult result) {
		System.out.printf("=== timed pass %d/%d ===%n", pass, passes);
		System.out.printf("%-34s %10s %14s %16s%n", "query kind", "queries", "wall (ms)", "records (cum.)");
		System.out.println("-".repeat(78));
		for (Map.Entry<String, long[]> entry : result.perKind().entrySet()) {
			System.out.printf(
				"%-34s %,10d %,14d %,16d%n", entry.getKey(), entry.getValue()[1], entry.getValue()[0] / 1_000_000L,
				entry.getValue()[2]
			);
		}
		System.out.println("-".repeat(60));
		System.out.printf("%-34s %10s %,14d%n", "TOTAL", "", result.wallNanos() / 1_000_000L);
		System.out.printf("records matched: %,d   failures: %,d%n%n", result.records(), result.failures());
	}

	/**
	 * Prints the median and minimum of every query kind across the measured passes, and checks that every pass
	 * matched the same number of records - a mismatch means the two builds are not answering the same questions
	 * and no timing comparison between them is meaningful.
	 *
	 * @param measured the measured passes
	 */
	private static void printSummary(@Nonnull List<PassResult> measured) {
		if (measured.isEmpty()) {
			System.out.println("no measured passes");
			return;
		}
		System.out.printf("=== SUMMARY over %d timed passes ===%n", measured.size());
		System.out.printf("%-34s %10s %14s %14s %14s%n", "query kind", "queries", "median ms", "min ms", "max ms");
		System.out.println("-".repeat(90));
		for (Map.Entry<String, long[]> entry : measured.get(0).perKind().entrySet()) {
			final String kind = entry.getKey();
			final long[] millis = new long[measured.size()];
			for (int i = 0; i < measured.size(); i++) {
				final long[] value = measured.get(i).perKind().getOrDefault(kind, new long[2]);
				millis[i] = value[0] / 1_000_000L;
			}
			Arrays.sort(millis);
			System.out.printf(
				"%-34s %,10d %,14d %,14d %,14d%n", kind, entry.getValue()[1], millis[millis.length / 2],
				millis[0], millis[millis.length - 1]
			);
		}
		final long[] totals = new long[measured.size()];
		for (int i = 0; i < measured.size(); i++) {
			totals[i] = measured.get(i).wallNanos() / 1_000_000L;
		}
		Arrays.sort(totals);
		System.out.println("-".repeat(90));
		System.out.printf(
			"%-34s %10s %,14d %,14d %,14d%n", "TOTAL", "", totals[totals.length / 2], totals[0],
			totals[totals.length - 1]
		);

		System.out.println();
		final long expected = measured.get(0).records();
		boolean consistent = true;
		final StringBuilder perPass = new StringBuilder();
		for (int i = 0; i < measured.size(); i++) {
			if (i > 0) {
				perPass.append(", ");
			}
			perPass.append(String.format("%,d", measured.get(i).records()));
			consistent &= measured.get(i).records() == expected;
		}
		System.out.println("records matched per pass: " + perPass);
		System.out.println(
			consistent
				? "records matched: CONSISTENT across passes - quote " + String.format("%,d", expected)
					+ " when comparing builds"
				: "records matched: MISMATCH across passes - the timings below are NOT comparable"
		);
		long failures = 0L;
		for (PassResult result : measured) {
			failures += result.failures();
		}
		System.out.printf("query failures across all timed passes: %,d%n", failures);
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
				throw new IllegalStateException("Interrupted while waiting for catalog!", e);
			}
		}
		throw new IllegalStateException("Catalog `" + catalogName + "` did not load in time!");
	}

	/**
	 * One generated query and the kind it belongs to.
	 *
	 * @param kind  the query kind, used to group the timings
	 * @param query the query itself
	 */
	private record QueryCase(@Nonnull String kind, @Nonnull Query query) {
	}

	/**
	 * One replay pass's outcome.
	 *
	 * @param wallNanos how long the whole pass took
	 * @param records   total records the queries matched
	 * @param failures  how many queries threw
	 * @param perKind   nanoseconds and query count per query kind
	 */
	private record PassResult(
		long wallNanos,
		long records,
		int failures,
		@Nonnull Map<String, long[]> perKind
	) {
	}
}
