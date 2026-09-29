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
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
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
import javax.annotation.Nullable;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static io.evitadb.api.query.QueryConstraints.*;

/**
 * Prices the lookup of entities by a unique attribute - `attributeEquals(a, v)` and `attributeInSet(a, v1..v20)` -
 * on a production catalog. That lookup reads a unique index instead of a histogram, and which unique index it reads
 * (the catalog's where the attribute is globally unique, the collection's where it is unique within the collection)
 * is decided per requested scope. This harness prices that choice on the hot path of every front store: fetching one
 * product by its code or its URL. It is an A/B harness: the same class is built into a baseline and a change jar.
 *
 * # Shapes ({@link QueryState#shape}) and scopes ({@link QueryState#scopes})
 *
 * - `EQUALS` - `attributeEquals(a, v)`, one owner per query.
 * - `IN_SET` - `attributeInSet(a, v1..v20)`, twenty owners per query.
 *
 * A localized attribute is looked up together with `entityLocaleEquals(l)`, `l` being {@link CorpusState#locale}.
 * With `LIVE` the query names no scope (the default one); with `LIVE_AND_ARCHIVED` it carries
 * `scope(LIVE, ARCHIVED)`, which makes the lookup walk both scopes in that order.
 *
 * # Operands and the oracle
 *
 * Setup walks every owner body and samples {@link CorpusState#sampleSize} owners carrying `a` (in the locale, for a
 * localized one) at an even stride in primary-key order, so the sample is the same on every build and every run.
 * The measured method walks the prepared queries round-robin, so no single value stays hot in a cache. Every
 * prepared query is run once during setup and its primary keys compared with the owners it was built from; the
 * `CHECK` line reports the outcome and any mismatch throws - a lookup answering the wrong owner measures nothing.
 *
 * # Guards
 *
 * - The fixture is a {@link Param}: no default attribute. An attribute that is missing, not unique in `LIVE`, an
 *   array, or localized without a locale (or not localized with one) throws.
 * - Setup prints `FIXTURE <entity>.<attribute>` with the schema facts that decide the lookup route (type,
 *   localization, uniqueness and global uniqueness per scope) and the sample, computed from the data.
 * - Setup prints `ROUTE`, whether the first query of the shape prefetched - informative only: the production
 *   shape is priced, with no debug mode steering the planner.
 * - The result cache is disabled, so a repeated query is evaluated every time.
 *
 * # Census
 *
 * {@link #main(String[])} is not a benchmark: it prints the collection's locales, the entities it holds per scope,
 * and every entity attribute unique in some scope with the facts above - the input for picking the fixtures.
 *
 * Run through JMH's own runner, e.g. {@code java -Xmx2g -cp <benchmarks.jar> org.openjdk.jmh.Main
 * io\.evitadb\.spike\.UniqueAttributeLookupBenchmark -p storageDirectory=<dir> -p catalogName=<name>
 * -p attribute=<a> [-p locale=<l>] -rf json -rff <result.json>}, and the census with {@code java -Xmx24g -cp
 * <same jar> io.evitadb.spike.UniqueAttributeLookupBenchmark <storageDirectory> <catalogName> [entityType]}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"-Xmx24g", "-XX:+ExitOnOutOfMemoryError"})
public class UniqueAttributeLookupBenchmark {
	/**
	 * How long the catalog may take to finish its background load.
	 */
	private static final long LOAD_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(10);
	/**
	 * Page size of every measured query - one listing page, the `IN_SET` answer fits on it whole.
	 */
	private static final int PAGE_SIZE = 20;
	/**
	 * Operands of one `IN_SET` query.
	 */
	private static final int IN_SET_SIZE = 20;
	/**
	 * Owners fetched per page while setup walks the collection.
	 */
	private static final int SCAN_PAGE_SIZE = 2_000;
	/**
	 * The scope every owner is sampled from.
	 */
	private static final io.evitadb.dataType.Scope LIVE = io.evitadb.dataType.Scope.LIVE;
	/**
	 * The second scope of the `LIVE_AND_ARCHIVED` variant.
	 */
	private static final io.evitadb.dataType.Scope ARCHIVED = io.evitadb.dataType.Scope.ARCHIVED;

	/**
	 * The filter of the measured query.
	 */
	public enum Shape {
		EQUALS,
		IN_SET
	}

	/**
	 * The scopes the measured query requests.
	 */
	public enum Scopes {
		LIVE,
		LIVE_AND_ARCHIVED
	}

	/**
	 * The loaded production catalog and the sampled operands, shared by every benchmark of the fork.
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
		 * The unique entity attribute looked up. Mandatory - pick it from the census.
		 */
		@Param("")
		public String attribute;
		/**
		 * The locale of a localized attribute, as a language tag; must stay blank for a non-localized one.
		 */
		@Param("")
		public String locale;
		/**
		 * How many owners are sampled as operands.
		 */
		@Param("1000")
		public int sampleSize;

		Evita evita;
		EvitaSessionContract session;
		@Nullable Locale theLocale;
		/**
		 * The sampled owners' primary keys, parallel to {@link #sampleValues}.
		 */
		int[] samplePrimaryKeys;
		/**
		 * The sampled owners' values of the attribute, parallel to {@link #samplePrimaryKeys}.
		 */
		Serializable[] sampleValues;

		/**
		 * Opens the catalog, validates the fixture, samples the operands and prints the `FIXTURE` line.
		 */
		@Setup(Level.Trial)
		public void open() {
			if (this.storageDirectory.isBlank() || this.catalogName.isBlank() || this.attribute.isBlank()) {
				throw new IllegalArgumentException(
					"Pass the fixture: -p storageDirectory=<dir> -p catalogName=<name> -p attribute=<a>"
				);
			}
			this.evita = openEvita(Path.of(this.storageDirectory));
			final Catalog catalog = activateAndAwait(this.evita, this.catalogName);
			final EntitySchemaContract schema = catalog.getCollectionForEntityOrThrowException(this.entityType).getSchema();
			final EntityAttributeSchemaContract attributeSchema = schema.getAttribute(this.attribute)
				.orElseThrow(() -> new IllegalArgumentException(
					"Entity `" + this.entityType + "` has no attribute `" + this.attribute + "`!"
				));
			if (!attributeSchema.isUniqueInScope(LIVE)) {
				throw new IllegalArgumentException("Attribute `" + this.attribute + "` is not unique in LIVE!");
			}
			if (attributeSchema.getType().isArray()) {
				throw new IllegalArgumentException("Attribute `" + this.attribute + "` is an array!");
			}
			if (attributeSchema.isLocalized() == this.locale.isBlank()) {
				throw new IllegalArgumentException(
					"Attribute `" + this.attribute + "` is " + (attributeSchema.isLocalized() ? "" : "not ") +
						"localized - pass -p locale=<tag> exactly for a localized one!"
				);
			}
			this.theLocale = this.locale.isBlank() ? null : Locale.forLanguageTag(this.locale);
			if (this.theLocale != null && !schema.getLocales().contains(this.theLocale)) {
				throw new IllegalArgumentException(
					"Entity `" + this.entityType + "` has no locale `" + this.locale + "`: " + schema.getLocales()
				);
			}
			this.session = this.evita.createReadOnlySession(this.catalogName);

			final List<int[]> carrying = new ArrayList<>(1_024);
			final List<Serializable> values = new ArrayList<>(1_024);
			final int scanned = forEachOwner(
				this.session, this.entityType, this.attribute, this.theLocale,
				owner -> {
					final Serializable value = this.theLocale == null ?
						owner.getAttribute(this.attribute) : owner.getAttribute(this.attribute, this.theLocale);
					if (value != null) {
						carrying.add(new int[]{owner.getPrimaryKeyOrThrowException()});
						values.add(value);
					}
				}
			);
			if (carrying.size() < this.sampleSize) {
				throw new IllegalStateException(
					"Only " + carrying.size() + " owners carry `" + this.attribute + "`, fewer than the sample of " +
						this.sampleSize + "!"
				);
			}
			// the scan pages in primary-key order, so an even stride over it is the same sample on every build
			final int stride = carrying.size() / this.sampleSize;
			this.samplePrimaryKeys = new int[this.sampleSize];
			this.sampleValues = new Serializable[this.sampleSize];
			for (int i = 0; i < this.sampleSize; i++) {
				this.samplePrimaryKeys[i] = carrying.get(i * stride)[0];
				this.sampleValues[i] = values.get(i * stride);
			}
			System.out.printf(
				"%nFIXTURE %s.%s type=%s localized=%s locale=%s %s owners=%d carrying=%d sampled=%d stride=%d " +
					"archivedEntities=%s%n",
				this.entityType, this.attribute, attributeSchema.getType().getSimpleName(),
				attributeSchema.isLocalized(), this.locale.isBlank() ? "-" : this.locale,
				describeUniqueness(attributeSchema, catalog), scanned, carrying.size(), this.sampleSize, stride,
				countArchived(this.session, this.entityType)
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
	 * The prepared queries of one shape and scope variant, walked round-robin by the measured method.
	 */
	@State(Scope.Thread)
	public static class QueryState {
		/**
		 * The filter of the measured query.
		 */
		@Param({"EQUALS", "IN_SET"})
		public Shape shape;
		/**
		 * The scopes the measured query requests.
		 */
		@Param("LIVE")
		public Scopes scopes;

		Query[] queries;
		int cursor;

		/**
		 * Prepares the queries, checks every answer against the sampled owners and reports the route.
		 */
		@Setup(Level.Trial)
		public void build(@Nonnull CorpusState corpus) {
			final String label = this.shape + " " + this.scopes;
			final int sampleSize = corpus.samplePrimaryKeys.length;
			final int queryCount = this.shape == Shape.EQUALS ? sampleSize : sampleSize / IN_SET_SIZE;
			this.queries = new Query[queryCount];
			final int[][] expected = new int[queryCount][];
			for (int q = 0; q < queryCount; q++) {
				final FilterConstraint lookup;
				if (this.shape == Shape.EQUALS) {
					lookup = attributeEquals(corpus.attribute, corpus.sampleValues[q]);
					expected[q] = new int[]{corpus.samplePrimaryKeys[q]};
				} else {
					final int from = q * IN_SET_SIZE;
					lookup = attributeInSet(
						corpus.attribute, Arrays.copyOfRange(corpus.sampleValues, from, from + IN_SET_SIZE)
					);
					expected[q] = Arrays.copyOfRange(corpus.samplePrimaryKeys, from, from + IN_SET_SIZE);
				}
				this.queries[q] = Query.query(
					collection(corpus.entityType),
					filterBy(
						lookup,
						corpus.theLocale == null ? null : entityLocaleEquals(corpus.theLocale),
						this.scopes == Scopes.LIVE_AND_ARCHIVED ? scope(LIVE, ARCHIVED) : null
					),
					require(page(1, PAGE_SIZE))
				);
			}
			int mismatches = 0;
			String firstMismatch = null;
			for (int q = 0; q < queryCount; q++) {
				final int[] actual = corpus.session.query(this.queries[q], EntityReference.class)
					.getRecordData().stream().mapToInt(EntityReference::getPrimaryKeyOrThrowException).sorted().toArray();
				final int[] wanted = expected[q].clone();
				Arrays.sort(wanted);
				if (!Arrays.equals(actual, wanted)) {
					mismatches++;
					if (firstMismatch == null) {
						firstMismatch = "expected " + Arrays.toString(wanted) + " got " + Arrays.toString(actual) +
							" for " + this.queries[q].getFilterBy();
					}
				}
			}
			System.out.printf(
				"CHECK %s: queries=%d owners=%d mismatches=%d %s%n",
				label, queryCount, this.shape == Shape.EQUALS ? queryCount : queryCount * IN_SET_SIZE, mismatches,
				mismatches == 0 ? "MATCH" : "MISMATCH " + firstMismatch
			);
			if (mismatches > 0) {
				throw new IllegalStateException(label + ": " + mismatches + " lookups answered the wrong owners!");
			}
			System.out.printf("ROUTE %s: prefetch=%s%n", label, prefetches(corpus, this.queries[0]) ? "yes" : "no");
		}
	}

	/**
	 * Runs the next prepared query and returns the page size so the call cannot be elided.
	 */
	@Benchmark
	public int lookup(@Nonnull CorpusState corpus, @Nonnull QueryState state) {
		final Query query = state.queries[state.cursor];
		state.cursor = state.cursor + 1 == state.queries.length ? 0 : state.cursor + 1;
		return corpus.session.query(query, EntityReference.class).getRecordData().size();
	}

	/**
	 * Prints the census that picks the benchmark fixtures. Not a benchmark; see the class javadoc.
	 *
	 * @param args storage directory, catalog name and optionally the owner entity type (default `Product`)
	 */
	public static void main(@Nonnull String[] args) {
		if (args.length < 2) {
			System.err.println("Usage: UniqueAttributeLookupBenchmark <storageDirectory> <catalogName> [entityType]");
			System.exit(1);
		}
		final String catalogName = args[1];
		final String entityType = args.length > 2 ? args[2] : "Product";
		final Evita evita = openEvita(Path.of(args[0]));
		try {
			final Catalog catalog = activateAndAwait(evita, catalogName);
			final EntitySchemaContract schema = catalog.getCollectionForEntityOrThrowException(entityType).getSchema();
			try (EvitaSessionContract session = evita.createReadOnlySession(catalogName)) {
				System.out.printf(
					"%nCENSUS %s locales=%s archivedEntities=%s%n", entityType, schema.getLocales(),
					countArchived(session, entityType)
				);
			}
			for (EntityAttributeSchemaContract attribute : schema.getAttributes().values()) {
				if (attribute.isUniqueInAnyScope()) {
					System.out.printf(
						"CENSUS-UNIQUE %s.%s type=%s localized=%s %s filterableLIVE=%s%n",
						entityType, attribute.getName(), attribute.getType().getSimpleName(), attribute.isLocalized(),
						describeUniqueness(attribute, catalog), attribute.isFilterableInScope(LIVE)
					);
				}
			}
		} finally {
			evita.close();
		}
	}

	/**
	 * Describes the uniqueness of the attribute in both scopes, the catalog-level one included.
	 */
	@Nonnull
	private static String describeUniqueness(@Nonnull AttributeSchemaContract attribute, @Nonnull Catalog catalog) {
		final StringBuilder description = new StringBuilder(128);
		for (io.evitadb.dataType.Scope scope : io.evitadb.dataType.Scope.values()) {
			description.append("unique").append(scope).append('=').append(attribute.getUniquenessType(scope)).append(' ');
		}
		final GlobalAttributeSchemaContract global = attribute instanceof GlobalAttributeSchemaContract direct ?
			direct : catalog.getSchema().getAttribute(attribute.getName()).orElse(null);
		for (io.evitadb.dataType.Scope scope : io.evitadb.dataType.Scope.values()) {
			description.append("global").append(scope).append('=')
				.append(global == null ? "-" : global.getGlobalUniquenessType(scope)).append(' ');
		}
		return description.toString().trim();
	}

	/**
	 * Returns how many entities of the collection live in `ARCHIVED`, or a note when the scope cannot be queried.
	 */
	@Nonnull
	private static String countArchived(@Nonnull EvitaSessionContract session, @Nonnull String entityType) {
		try {
			return String.valueOf(
				session.query(
					Query.query(collection(entityType), filterBy(scope(ARCHIVED)), require(page(1, 1))),
					EntityReference.class
				).getTotalRecordCount()
			);
		} catch (RuntimeException ex) {
			return "unavailable(" + ex.getClass().getSimpleName() + ")";
		}
	}

	/**
	 * Runs the query once with telemetry and tells whether the plan prefetched.
	 */
	private static boolean prefetches(@Nonnull CorpusState corpus, @Nonnull Query query) {
		final Query withTelemetry = Query.query(
			query.getCollection(), query.getFilterBy(), require(page(1, PAGE_SIZE), queryTelemetry())
		);
		final EvitaResponse<EntityReference> response = corpus.session.query(withTelemetry, EntityReference.class);
		final QueryTelemetry telemetry = response.getExtraResult(QueryTelemetry.class);
		if (telemetry == null) {
			throw new IllegalStateException("No telemetry returned, the route cannot be reported!");
		}
		return containsPhase(telemetry, QueryPhase.EXECUTION_PREFETCH);
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
	 * Hands every `LIVE` owner, with the attribute fetched, to the consumer in primary-key order and returns how many
	 * were visited.
	 */
	private static int forEachOwner(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull String attribute,
		@Nullable Locale locale,
		@Nonnull java.util.function.Consumer<SealedEntity> consumer
	) {
		int visited = 0;
		for (int pageNumber = 1; ; pageNumber++) {
			final EvitaResponse<SealedEntity> response = session.query(
				Query.query(
					collection(entityType),
					require(
						page(pageNumber, SCAN_PAGE_SIZE),
						entityFetch(attributeContent(attribute), locale == null ? null : dataInLocales(locale))
					)
				),
				SealedEntity.class
			);
			for (SealedEntity entity : response.getRecordData()) {
				consumer.accept(entity);
				visited++;
			}
			// a page past the end is answered with the first page, so the loop must stop on the count
			if ((long) pageNumber * SCAN_PAGE_SIZE >= response.getTotalRecordCount()) {
				return visited;
			}
		}
	}

	/**
	 * Builds the engine over the storage directory with the result cache disabled.
	 */
	@Nonnull
	private static Evita openEvita(@Nonnull Path storage) {
		if (!Files.isDirectory(storage)) {
			throw new IllegalArgumentException("Storage directory `" + storage + "` does not exist!");
		}
		return new Evita(
			EvitaConfiguration.builder()
				.storage(StorageOptions.builder().storageDirectory(storage).build())
				// the result cache would answer a repeated query without evaluating it - price the uncached work
				.cache(CacheOptions.builder().enabled(false).build())
				.server(
					ServerOptions.builder()
						.requestThreadPool(ThreadPoolOptions.requestThreadPoolBuilder().build())
						.build()
				)
				.build()
		);
	}

	/**
	 * Activates a catalog restored from a backup (it comes up `INACTIVE`) and waits until it finishes its
	 * background load and becomes a usable {@link Catalog}.
	 */
	@Nonnull
	private static Catalog activateAndAwait(@Nonnull Evita evita, @Nonnull String catalogName) {
		final CatalogState state = evita.getCatalogState(catalogName)
			.orElseThrow(() -> new IllegalArgumentException("Catalog `" + catalogName + "` not found!"));
		if (state == CatalogState.INACTIVE) {
			evita.activateCatalog(catalogName);
		}
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
