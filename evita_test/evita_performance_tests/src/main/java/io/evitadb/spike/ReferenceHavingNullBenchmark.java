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
import io.evitadb.api.query.Query;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;
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
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

import static io.evitadb.api.query.QueryConstraints.*;

/**
 * Prices `referenceHaving(R, attributeIsNull(a))` on a production catalog. A `NULL` body is widened to the whole
 * candidate set at type-level discovery and settled per reduced-index row - the type-level
 * index can only tell that some row of a partition carries `a` - so every partition of `R` becomes a candidate and
 * every one of them is visited. This harness measures what that costs, next to controls answered by the same
 * per-row machinery. It is an A/B harness: the same jar layout is built from a baseline and from a change, and both
 * run against the same restored catalog.
 *
 * # Shapes ({@link QueryState#shape})
 *
 * - `IS_NULL` - `referenceHaving(R, attributeIsNull(a))`, the widened shape.
 * - `IS_NOT_NULL` - `referenceHaving(R, attributeIsNotNull(a))`, a control whose discovery is not widened.
 * - `EQUALS` - `referenceHaving(R, attributeEquals(a, v))` with `v` the most frequent value of `a`, a second
 *   control that narrows discovery.
 * - `NULL_AND_EQUALS` - `referenceHaving(R, and(attributeIsNull(a), attributeEquals(b, w)))` with `w` the most
 *   frequent value of `b` among the rows lacking `a`, so the row-scoped conjunction is never empty by
 *   construction. Needs `secondAttribute`.
 *
 * With {@link QueryState#narrowed} the reference constraint is joined by `attributeEquals(n, x)` on the owner,
 * where `x` is the most frequent value of the entity attribute `n` among the owners holding a row that lacks
 * `a` - the realistic listing shape of a narrowing sibling next to the reference filter. Needs
 * `narrowingAttribute`.
 *
 * Every measured query pages `page(1, 20)` and returns {@link EntityReference}s, so fetching stays out of the
 * number.
 *
 * # The oracle
 *
 * Setup walks every owner body of the collection, independently of any filter, and records each owner's rows of
 * `R` with the values of `a`, `b` and `n`. The expected owner count of every shape is computed from those bodies
 * with the row-scoped semantics of `referenceHaving` (an owner qualifies when **one** of its rows satisfies the
 * whole body), and printed next to the engine's count on a `CHECK` line. A build that predates this fix answers
 * `IS_NULL` with a smaller count wherever a partition mixes carrying and lacking rows - that mismatch is printed,
 * never hidden. With `-p expectCorrect=true` a mismatch throws instead, which is how a run proves it measured a
 * correct query.
 *
 * # Guards
 *
 * - The fixture is a {@link Param}, never a system property, and there is no default reference or attribute: an
 *   unnamed fixture throws, and so does a named one that is missing, not indexed or not filterable in `LIVE`.
 * - Setup prints `FIXTURE <entity>.<reference>.<attribute> partitions=… owners=… rowsCarrying=… rowsLacking=…
 *   expectedNullOwners=…`, computed from the data, and throws when either row count is zero: a `NULL` over a
 *   reference whose every row carries the attribute (or none does) measures nothing the fix changes. The one
 *   exception is declared, never inferred: {@link CorpusState#denseFixture} requires every row to carry `a` and
 *   prices what the fix costs where it changes no answer - proving `IS_NULL` empty.
 * - A shape whose expected answer is empty throws too - an empty expectation cannot tell the defect from the fix.
 * - Every measured query carries `debug(PREFER_INDEX_SCAN)`, and setup runs it once with `queryTelemetry()` and
 *   throws when it nevertheless took the prefetch route ({@link QueryPhase#EXECUTION_PREFETCH}): the defect and
 *   its fix live on the index route.
 * - The result cache is disabled, so a repeated query is evaluated every time.
 *
 * # Census
 *
 * {@link #main(String[])} is not a benchmark: it opens the catalog and prints, for every indexed reference of the
 * collection, its partition count and every attribute with its row and owner counts (carrying / lacking), its
 * distinct values, and the engine's current `IS_NULL` / `IS_NOT_NULL` answers next to the ones computed from the
 * bodies; then every filterable entity attribute usable as a narrowing sibling. Its output is what picks the
 * fixtures above.
 *
 * Needs a quiet machine. Run through JMH's own runner with the fixture parameters, e.g.:
 * {@code java -Xmx2g -cp evita_test/evita_performance_tests/target/benchmarks.jar org.openjdk.jmh.Main
 * io\.evitadb\.spike\.ReferenceHavingNullBenchmark -p storageDirectory=<dir> -p catalogName=<name>
 * -p referenceName=<R> -p nullAttribute=<a> -rf json -rff <result.json>}, and the census with
 * {@code java -Xmx24g -XX:+ExitOnOutOfMemoryError -cp <same jar> io.evitadb.spike.ReferenceHavingNullBenchmark
 * <storageDirectory> <catalogName> [entityType]}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"-Xmx24g", "-XX:+ExitOnOutOfMemoryError"})
public class ReferenceHavingNullBenchmark {
	/**
	 * How long the catalog may take to finish its background load.
	 */
	private static final long LOAD_TIMEOUT_NANOS = TimeUnit.MINUTES.toNanos(10);
	/**
	 * Page size of every measured query - one listing page, so fetching and slicing stay negligible.
	 */
	private static final int PAGE_SIZE = 20;
	/**
	 * Owners fetched per page while the oracle and the census walk the collection.
	 */
	private static final int SCAN_PAGE_SIZE = 2_000;
	/**
	 * The only scope this harness measures; a query lands there unless it says otherwise.
	 */
	private static final io.evitadb.dataType.Scope LIVE = io.evitadb.dataType.Scope.LIVE;

	/**
	 * The body of the measured `referenceHaving`. See the class javadoc.
	 */
	public enum Shape {
		IS_NULL,
		IS_NOT_NULL,
		EQUALS,
		NULL_AND_EQUALS
	}

	/**
	 * The loaded production catalog and the oracle computed from its bodies, shared by every benchmark of the fork.
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
		 * The reference filtered by. Mandatory - pick it from the census.
		 */
		@Param("")
		public String referenceName;
		/**
		 * The filterable reference attribute `a` tested for `NULL`. Mandatory - pick it from the census.
		 */
		@Param("")
		public String nullAttribute;
		/**
		 * The second filterable reference attribute `b` of the `NULL_AND_EQUALS` shape. Needed only by that shape.
		 */
		@Param("")
		public String secondAttribute;
		/**
		 * The filterable entity attribute `n` of the narrowed variant. Needed only when `narrowed=true`.
		 */
		@Param("")
		public String narrowingAttribute;
		/**
		 * Whether an engine answer that differs from the one computed from the bodies fails the setup. Leave it
		 * `false` on a build that predates this fix, whose `IS_NULL` answer is wrong on mixed partitions.
		 */
		@Param("false")
		public boolean expectCorrect;
		/**
		 * Declares a fixture whose every row carries `a`. There `IS_NULL` is empty by construction and a build that
		 * predates this fix answers it correctly too, so what it prices is the cost of *proving* the empty answer: the
		 * older build settles it during candidate discovery, the fixed one walks every partition. Setup then requires
		 * zero lacking rows instead of refusing them, and only `IS_NULL` may expect no owner.
		 */
		@Param("false")
		public boolean denseFixture;

		Evita evita;
		EvitaSessionContract session;
		List<OwnerRows> owners;
		int partitions;
		/**
		 * Whether `a` is array-typed, which leaves `EQUALS` without an unambiguous operand.
		 */
		boolean nullAttributeIsArray;
		/**
		 * Most frequent value of `a` over all rows, the operand of `EQUALS`.
		 */
		Serializable nullAttributeTopValue;
		/**
		 * Most frequent value of `b` over the rows lacking `a`, the operand of `NULL_AND_EQUALS`; null when no such
		 * row carries `b` or no `b` was named.
		 */
		Serializable secondAttributeTopValue;
		/**
		 * Most frequent value of `n` over the owners holding a row that lacks `a`; null when no `n` was named.
		 */
		Serializable narrowingTopValue;

		/**
		 * Opens the catalog, validates the fixture, walks every owner body to build the oracle and prints the
		 * `FIXTURE` line.
		 */
		@Setup(Level.Trial)
		public void open() {
			if (this.storageDirectory.isBlank() || this.catalogName.isBlank()) {
				throw new IllegalArgumentException(
					"Pass the fixture: -p storageDirectory=<dir> -p catalogName=<name>"
				);
			}
			if (this.referenceName.isBlank() || this.nullAttribute.isBlank()) {
				throw new IllegalArgumentException(
					"Pass the fixture: -p referenceName=<R> -p nullAttribute=<a> (run the census to pick them)"
				);
			}
			this.evita = openEvita(Path.of(this.storageDirectory));
			final Catalog catalog = activateAndAwait(this.evita, this.catalogName);
			final EntityCollection collection = catalog.getCollectionForEntityOrThrowException(this.entityType);
			final EntitySchemaContract schema = collection.getSchema();
			final ReferenceSchemaContract reference = schema.getReference(this.referenceName)
				.orElseThrow(() -> new IllegalArgumentException(
					"Entity `" + this.entityType + "` has no reference `" + this.referenceName + "`!"
				));
			if (!reference.isIndexedInScope(LIVE)) {
				throw new IllegalArgumentException("Reference `" + this.referenceName + "` is not indexed in LIVE!");
			}
			requireFilterable(reference, this.nullAttribute, false);
			this.nullAttributeIsArray = reference.getAttribute(this.nullAttribute).orElseThrow().getType().isArray();
			if (!this.secondAttribute.isBlank()) {
				requireFilterable(reference, this.secondAttribute, true);
			}
			if (!this.narrowingAttribute.isBlank()) {
				final EntityAttributeSchemaContract narrowing = schema.getAttribute(this.narrowingAttribute)
					.orElseThrow(() -> new IllegalArgumentException(
						"Entity `" + this.entityType + "` has no attribute `" + this.narrowingAttribute + "`!"
					));
				requireUsableForEquals(narrowing, "Entity attribute `" + this.narrowingAttribute + "`");
			}
			this.partitions = countPartitions(collection, this.referenceName);
			if (this.partitions < 0) {
				throw new IllegalArgumentException("Reference `" + this.referenceName + "` has no LIVE type index!");
			}
			this.session = this.evita.createReadOnlySession(this.catalogName);
			this.owners = scanOwners(this);

			long rowsCarrying = 0;
			long rowsLacking = 0;
			final Map<Serializable, Integer> nullAttributeFrequencies = new HashMap<>(1_024);
			final Map<Serializable, Integer> secondAttributeFrequencies = new HashMap<>(1_024);
			final Map<Serializable, Integer> narrowingFrequencies = new HashMap<>(1_024);
			final Set<Integer> targetsLacking = new HashSet<>(1_024);
			for (OwnerRows owner : this.owners) {
				boolean lacking = false;
				for (int i = 0; i < owner.targets().length; i++) {
					final Serializable value = owner.nullAttributeValues()[i];
					if (value == null) {
						rowsLacking++;
						lacking = true;
						targetsLacking.add(owner.targets()[i]);
						final Serializable second = owner.secondAttributeValues()[i];
						if (second != null) {
							secondAttributeFrequencies.merge(second, 1, Integer::sum);
						}
					} else {
						rowsCarrying++;
						if (!this.nullAttributeIsArray) {
							nullAttributeFrequencies.merge(value, 1, Integer::sum);
						}
					}
				}
				if (lacking && owner.narrowingValue() != null) {
					narrowingFrequencies.merge(owner.narrowingValue(), 1, Integer::sum);
				}
			}
			this.nullAttributeTopValue = mostFrequent(nullAttributeFrequencies);
			this.secondAttributeTopValue = mostFrequent(secondAttributeFrequencies);
			this.narrowingTopValue = mostFrequent(narrowingFrequencies);
			final long expectedNullOwners = countOwners(this.owners, OwnerRows::hasRowLackingNullAttribute);
			System.out.printf(
				"%nFIXTURE %s.%s.%s partitions=%d owners=%d rowsCarrying=%d rowsLacking=%d expectedNullOwners=%d" +
					" targetsLacking=%d dense=%s%n",
				this.entityType, this.referenceName, this.nullAttribute, this.partitions, this.owners.size(),
				rowsCarrying, rowsLacking, expectedNullOwners, targetsLacking.size(), this.denseFixture
			);
			System.out.printf(
				"OPERANDS %s=%s (%d rows), %s=%s (%d null rows), %s=%s (%d null owners)%n",
				this.nullAttribute, this.nullAttributeTopValue, frequencyOf(nullAttributeFrequencies, this.nullAttributeTopValue),
				blankToDash(this.secondAttribute), this.secondAttributeTopValue,
				frequencyOf(secondAttributeFrequencies, this.secondAttributeTopValue),
				blankToDash(this.narrowingAttribute), this.narrowingTopValue,
				frequencyOf(narrowingFrequencies, this.narrowingTopValue)
			);
			if (this.denseFixture) {
				if (rowsCarrying == 0 || rowsLacking != 0) {
					throw new IllegalStateException(
						"Not a dense fixture: `" + this.referenceName + "." + this.nullAttribute + "` has " +
							rowsCarrying + " rows carrying and " + rowsLacking + " rows lacking the attribute!"
					);
				}
			} else if (rowsCarrying == 0 || rowsLacking == 0) {
				throw new IllegalStateException(
					"Vacuous fixture: `" + this.referenceName + "." + this.nullAttribute + "` has " + rowsCarrying +
						" rows carrying and " + rowsLacking + " rows lacking the attribute - both must be non-zero!"
				);
			}
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
	 * One measured query: a shape, with or without the narrowing sibling.
	 */
	@State(Scope.Benchmark)
	public static class QueryState {
		/**
		 * The body of the reference constraint.
		 */
		@Param({"IS_NULL", "IS_NOT_NULL", "EQUALS"})
		public Shape shape;
		/**
		 * Whether the owner-level narrowing sibling joins the reference constraint.
		 */
		@Param("false")
		public boolean narrowed;

		Query query;

		/**
		 * Builds the query, checks its answer against the oracle and verifies, with telemetry, that it runs on the
		 * index route.
		 */
		@Setup(Level.Trial)
		public void build(@Nonnull CorpusState corpus) {
			final String label = this.shape + (this.narrowed ? " narrowed" : " plain");
			final FilterConstraint body;
			final Predicate<OwnerRows> expectation;
			switch (this.shape) {
				case IS_NULL -> {
					body = attributeIsNull(corpus.nullAttribute);
					expectation = OwnerRows::hasRowLackingNullAttribute;
				}
				case IS_NOT_NULL -> {
					body = attributeIsNotNull(corpus.nullAttribute);
					expectation = OwnerRows::hasRowCarryingNullAttribute;
				}
				case EQUALS -> {
					if (corpus.nullAttributeIsArray) {
						throw new IllegalArgumentException(
							label + ": `" + corpus.nullAttribute + "` is an array - its operand is ambiguous!"
						);
					}
					final Serializable value = Objects.requireNonNull(corpus.nullAttributeTopValue);
					body = attributeEquals(corpus.nullAttribute, value);
					expectation = owner -> owner.hasRow(i -> sameValue(owner.nullAttributeValues()[i], value));
				}
				case NULL_AND_EQUALS -> {
					if (corpus.secondAttribute.isBlank()) {
						throw new IllegalArgumentException(label + " needs -p secondAttribute=<b>!");
					}
					final Serializable value = corpus.secondAttributeTopValue;
					if (value == null) {
						throw new IllegalStateException(
							label + ": no row lacking `" + corpus.nullAttribute + "` carries `" +
								corpus.secondAttribute + "` - the conjunction would be empty by construction!"
						);
					}
					body = and(attributeIsNull(corpus.nullAttribute), attributeEquals(corpus.secondAttribute, value));
					expectation = owner -> owner.hasRow(
						i -> owner.nullAttributeValues()[i] == null && sameValue(owner.secondAttributeValues()[i], value)
					);
				}
				default -> throw new IllegalStateException("Unknown shape " + this.shape);
			}
			final FilterConstraint filter;
			final Predicate<OwnerRows> expected;
			if (this.narrowed) {
				if (corpus.narrowingAttribute.isBlank()) {
					throw new IllegalArgumentException(label + " needs -p narrowingAttribute=<n>!");
				}
				final Serializable value = corpus.narrowingTopValue;
				if (value == null) {
					throw new IllegalStateException(
						label + ": no owner with a row lacking `" + corpus.nullAttribute + "` carries `" +
							corpus.narrowingAttribute + "`!"
					);
				}
				filter = and(
					attributeEquals(corpus.narrowingAttribute, value),
					referenceHaving(corpus.referenceName, body)
				);
				expected = owner -> sameValue(owner.narrowingValue(), value) && expectation.test(owner);
			} else {
				filter = referenceHaving(corpus.referenceName, body);
				expected = expectation;
			}
			this.query = Query.query(
				collection(corpus.entityType),
				filterBy(filter),
				require(page(1, PAGE_SIZE), debug(DebugMode.PREFER_INDEX_SCAN))
			);
			final long bodies = countOwners(corpus.owners, expected);
			if (bodies == 0 && !(corpus.denseFixture && this.shape == Shape.IS_NULL)) {
				throw new IllegalStateException(
					label + ": the bodies expect no owner - an empty expectation cannot tell the defect from the fix!"
				);
			}
			final int engine = verifyIndexRoute(corpus, this.query, label);
			final boolean match = engine == bodies;
			System.out.printf(
				"CHECK %s: engine=%d bodies=%d %s%n", label, engine, bodies, match ? "MATCH" : "MISMATCH"
			);
			if (!match && corpus.expectCorrect) {
				throw new IllegalStateException(
					label + ": the engine answers " + engine + " owners where the bodies say " + bodies + "!"
				);
			}
		}
	}

	/**
	 * The rows of one owner of the measured reference, reduced to what the oracle needs. The three row arrays are
	 * parallel: index `i` describes the `i`-th row.
	 *
	 * @param primaryKey            the owner's primary key
	 * @param narrowingValue        the owner's value of the narrowing attribute, or null
	 * @param targets               the referenced primary key of every row
	 * @param nullAttributeValues   the value of `a` on every row, null where the row lacks it
	 * @param secondAttributeValues the value of `b` on every row, null where the row lacks it or no `b` was named
	 */
	record OwnerRows(
		int primaryKey,
		@Nullable Serializable narrowingValue,
		@Nonnull int[] targets,
		@Nonnull Serializable[] nullAttributeValues,
		@Nonnull Serializable[] secondAttributeValues
	) {

		/**
		 * Tells whether at least one row satisfies the predicate over row indexes.
		 */
		boolean hasRow(@Nonnull IntPredicate rowPredicate) {
			for (int i = 0; i < this.targets.length; i++) {
				if (rowPredicate.test(i)) {
					return true;
				}
			}
			return false;
		}

		/**
		 * Tells whether a row lacks `a` - the row-scoped reading of `attributeIsNull(a)`.
		 */
		boolean hasRowLackingNullAttribute() {
			return hasRow(i -> this.nullAttributeValues[i] == null);
		}

		/**
		 * Tells whether a row carries `a` - the row-scoped reading of `attributeIsNotNull(a)`.
		 */
		boolean hasRowCarryingNullAttribute() {
			return hasRow(i -> this.nullAttributeValues[i] != null);
		}
	}

	/**
	 * Runs one measured query and returns the page size so the call cannot be elided.
	 */
	@Benchmark
	public int query(@Nonnull CorpusState corpus, @Nonnull QueryState state) {
		return corpus.session.query(state.query, EntityReference.class).getRecordData().size();
	}

	/**
	 * Prints the census that picks the benchmark fixtures. Not a benchmark; see the class javadoc.
	 *
	 * @param args storage directory, catalog name and optionally the owner entity type (default `Product`)
	 */
	public static void main(@Nonnull String[] args) {
		if (args.length < 2) {
			System.err.println("Usage: ReferenceHavingNullBenchmark <storageDirectory> <catalogName> [entityType]");
			System.exit(1);
		}
		final String catalogName = args[1];
		final String entityType = args.length > 2 ? args[2] : "Product";
		final Evita evita = openEvita(Path.of(args[0]));
		try {
			final Catalog catalog = activateAndAwait(evita, catalogName);
			final EntityCollection collection = catalog.getCollectionForEntityOrThrowException(entityType);
			try (EvitaSessionContract session = evita.createReadOnlySession(catalogName)) {
				new Census(session, collection, entityType).run();
			}
		} finally {
			evita.close();
		}
	}

	/**
	 * Walks every owner body once and tabulates, per indexed reference and per attribute, what the fixture choice
	 * needs. Printed line prefixes: `CENSUS-REF`, `CENSUS-ATTR`, `CENSUS-ENTITY-ATTR`, `CANDIDATE`.
	 */
	private static final class Census {
		private final EvitaSessionContract session;
		private final EntityCollection collection;
		private final String entityType;
		private final EntitySchemaContract schema;
		/**
		 * Indexed references with their attribute tallies, in schema order.
		 */
		private final Map<String, Map<String, AttributeTally>> referenceTallies = new LinkedHashMap<>();
		/**
		 * Owners holding at least one row, per reference.
		 */
		private final Map<String, Integer> referenceOwners = new HashMap<>();
		/**
		 * Rows, per reference.
		 */
		private final Map<String, Long> referenceRows = new HashMap<>();
		/**
		 * Filterable, non-localized entity attributes with their tallies - the narrowing candidates.
		 */
		private final Map<String, AttributeTally> entityTallies = new LinkedHashMap<>();

		Census(@Nonnull EvitaSessionContract session, @Nonnull EntityCollection collection, @Nonnull String entityType) {
			this.session = session;
			this.collection = collection;
			this.entityType = entityType;
			this.schema = collection.getSchema();
		}

		/**
		 * Scans the bodies, then prints the tallies and the engine's current answers.
		 */
		void run() {
			for (ReferenceSchemaContract reference : this.schema.getReferences().values()) {
				if (reference.isIndexedInScope(LIVE)) {
					final Map<String, AttributeTally> tallies = new LinkedHashMap<>();
					for (AttributeSchemaContract attribute : reference.getAttributes().values()) {
						tallies.put(attribute.getName(), new AttributeTally(attribute));
					}
					this.referenceTallies.put(reference.getName(), tallies);
				}
			}
			for (EntityAttributeSchemaContract attribute : this.schema.getAttributes().values()) {
				if (attribute.isFilterableInScope(LIVE) && !attribute.isLocalized()) {
					this.entityTallies.put(attribute.getName(), new AttributeTally(attribute));
				}
			}
			final List<EntityContentRequire> content = new ArrayList<>(this.referenceTallies.size() + 1);
			if (!this.entityTallies.isEmpty()) {
				content.add(attributeContent(this.entityTallies.keySet().toArray(String[]::new)));
			}
			for (String referenceName : this.referenceTallies.keySet()) {
				content.add(referenceContentWithAttributes(referenceName, attributeContentAll()));
			}
			final long start = System.nanoTime();
			final int scanned = forEachOwner(
				this.session, this.entityType, content.toArray(EntityContentRequire[]::new), this::tally
			);
			System.out.printf(
				"%nCENSUS %s: %,d entities scanned in %,d s%n",
				this.entityType, scanned, TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start)
			);
			printReferences();
			printEntityAttributes();
		}

		/**
		 * Adds one owner to every tally.
		 */
		private void tally(@Nonnull SealedEntity owner) {
			for (AttributeTally tally : this.entityTallies.values()) {
				tally.addOwnerValue(owner.getAttribute(tally.name()));
			}
			for (Entry<String, Map<String, AttributeTally>> entry : this.referenceTallies.entrySet()) {
				final String referenceName = entry.getKey();
				final Map<String, AttributeTally> tallies = entry.getValue();
				final List<ReferenceContract> rows = List.copyOf(owner.getReferences(referenceName));
				if (rows.isEmpty()) {
					continue;
				}
				this.referenceOwners.merge(referenceName, 1, Integer::sum);
				this.referenceRows.merge(referenceName, (long) rows.size(), Long::sum);
				for (AttributeTally tally : tallies.values()) {
					if (tally.localized()) {
						// fetched without a locale, a localized value reads as absent - counting it would lie
						continue;
					}
					boolean carrying = false;
					boolean lacking = false;
					for (ReferenceContract row : rows) {
						final Serializable value = row.getAttribute(tally.name());
						tally.addRowValue(value);
						carrying |= value != null;
						lacking |= value == null;
					}
					tally.addOwner(carrying, lacking);
				}
			}
		}

		/**
		 * Prints one `CENSUS-REF` line per indexed reference and one `CENSUS-ATTR` line per attribute, with the
		 * engine's current `NULL` / `NOT NULL` answers for the filterable ones.
		 */
		private void printReferences() {
			for (Entry<String, Map<String, AttributeTally>> entry : this.referenceTallies.entrySet()) {
				final String referenceName = entry.getKey();
				final int bareEngine = count(referenceHaving(referenceName));
				System.out.printf(
					"%nCENSUS-REF %s.%s partitions=%d owners=%d rows=%d engineBareOwners=%d%n",
					this.entityType, referenceName, countPartitions(this.collection, referenceName),
					this.referenceOwners.getOrDefault(referenceName, 0),
					this.referenceRows.getOrDefault(referenceName, 0L), bareEngine
				);
				for (AttributeTally tally : entry.getValue().values()) {
					if (!tally.filterable() || tally.localized()) {
						System.out.printf(
							"CENSUS-ATTR %s.%s type=%s filterable=%s localized=%s - skipped%n",
							referenceName, tally.name(), tally.typeName(), tally.filterable(), tally.localized()
						);
						continue;
					}
					final int engineNull = count(referenceHaving(referenceName, attributeIsNull(tally.name())));
					final int engineNotNull = count(referenceHaving(referenceName, attributeIsNotNull(tally.name())));
					System.out.printf(
						"CENSUS-ATTR %s.%s type=%s nullable=%s default=%s rowsCarrying=%d rowsLacking=%d " +
							"ownersCarrying=%d ownersLacking=%d distinct=%d topValueRows=%d " +
							"engineNull=%d%s engineNotNull=%d%s%n",
						referenceName, tally.name(), tally.typeName(), tally.nullable(), tally.hasDefault(),
						tally.rowsCarrying(), tally.rowsLacking(), tally.ownersCarrying(), tally.ownersLacking(),
						tally.distinct(), tally.topFrequency(),
						engineNull, engineNull == tally.ownersLacking() ? "" : "(!=" + tally.ownersLacking() + ")",
						engineNotNull, engineNotNull == tally.ownersCarrying() ? "" : "(!=" + tally.ownersCarrying() + ")"
					);
					if (tally.rowsCarrying() > 0 && tally.rowsLacking() > 0) {
						System.out.printf(
							"CANDIDATE -p referenceName=%s -p nullAttribute=%s  (partitions=%d, ownersLacking=%d)%n",
							referenceName, tally.name(), countPartitions(this.collection, referenceName),
							tally.ownersLacking()
						);
					}
				}
			}
		}

		/**
		 * Prints one `CENSUS-ENTITY-ATTR` line per narrowing candidate.
		 */
		private void printEntityAttributes() {
			System.out.println();
			for (AttributeTally tally : this.entityTallies.values()) {
				System.out.printf(
					"CENSUS-ENTITY-ATTR %s.%s type=%s ownersCarrying=%d ownersLacking=%d distinct=%d topValueOwners=%d%n",
					this.entityType, tally.name(), tally.typeName(), tally.rowsCarrying(), tally.rowsLacking(),
					tally.distinct(), tally.topFrequency()
				);
			}
		}

		/**
		 * Returns the engine's owner count for the filter, on the index route.
		 */
		private int count(@Nonnull FilterConstraint filter) {
			return this.session.query(
				Query.query(
					collection(this.entityType),
					filterBy(filter),
					require(page(1, 1), debug(DebugMode.PREFER_INDEX_SCAN))
				),
				EntityReference.class
			).getTotalRecordCount();
		}
	}

	/**
	 * Row and owner counts of one attribute, collected by the census. For an entity attribute a "row" is the owner
	 * itself.
	 */
	private static final class AttributeTally {
		private final String name;
		private final String typeName;
		private final boolean filterable;
		private final boolean localized;
		private final boolean nullable;
		private final boolean hasDefault;
		private final boolean array;
		private final Map<Serializable, Integer> frequencies = new HashMap<>(256);
		private long rowsCarrying;
		private long rowsLacking;
		private int ownersCarrying;
		private int ownersLacking;

		AttributeTally(@Nonnull AttributeSchemaContract schema) {
			this.name = schema.getName();
			this.typeName = schema.getType().getSimpleName();
			this.filterable = schema.isFilterableInScope(LIVE);
			this.localized = schema.isLocalized();
			this.nullable = schema.isNullable();
			this.hasDefault = schema.getDefaultValue() != null;
			this.array = schema.getType().isArray();
		}

		/**
		 * Counts one row's value, null meaning the row lacks the attribute.
		 */
		void addRowValue(@Nullable Serializable value) {
			if (value == null) {
				this.rowsLacking++;
			} else {
				this.rowsCarrying++;
				if (!this.array) {
					this.frequencies.merge(value, 1, Integer::sum);
				}
			}
		}

		/**
		 * Counts one entity's value of an entity attribute.
		 */
		void addOwnerValue(@Nullable Serializable value) {
			addRowValue(value);
		}

		/**
		 * Counts one owner of a reference attribute by whether any of its rows carried and lacked the attribute.
		 */
		void addOwner(boolean carrying, boolean lacking) {
			if (carrying) {
				this.ownersCarrying++;
			}
			if (lacking) {
				this.ownersLacking++;
			}
		}

		@Nonnull
		String name() {
			return this.name;
		}

		@Nonnull
		String typeName() {
			return this.typeName;
		}

		boolean filterable() {
			return this.filterable;
		}

		boolean localized() {
			return this.localized;
		}

		boolean nullable() {
			return this.nullable;
		}

		boolean hasDefault() {
			return this.hasDefault;
		}

		long rowsCarrying() {
			return this.rowsCarrying;
		}

		long rowsLacking() {
			return this.rowsLacking;
		}

		int ownersCarrying() {
			return this.ownersCarrying;
		}

		int ownersLacking() {
			return this.ownersLacking;
		}

		/**
		 * Returns the number of distinct values, or -1 for an array attribute, whose values are not tallied.
		 */
		int distinct() {
			return this.array ? -1 : this.frequencies.size();
		}

		/**
		 * Returns how often the most frequent value occurs.
		 */
		int topFrequency() {
			return frequencyOf(this.frequencies, mostFrequent(this.frequencies));
		}
	}

	/**
	 * Walks every owner of the collection and keeps those holding at least one row of the measured reference.
	 */
	@Nonnull
	private static List<OwnerRows> scanOwners(@Nonnull CorpusState corpus) {
		final List<String> referenceAttributes = new ArrayList<>(2);
		referenceAttributes.add(corpus.nullAttribute);
		if (!corpus.secondAttribute.isBlank()) {
			referenceAttributes.add(corpus.secondAttribute);
		}
		final List<EntityContentRequire> content = new ArrayList<>(2);
		content.add(
			referenceContentWithAttributes(
				corpus.referenceName, attributeContent(referenceAttributes.toArray(String[]::new))
			)
		);
		if (!corpus.narrowingAttribute.isBlank()) {
			content.add(attributeContent(corpus.narrowingAttribute));
		}
		final List<OwnerRows> owners = new ArrayList<>(65_536);
		final long start = System.nanoTime();
		final int scanned = forEachOwner(
			corpus.session, corpus.entityType, content.toArray(EntityContentRequire[]::new),
			entity -> {
				final List<ReferenceContract> rows = List.copyOf(entity.getReferences(corpus.referenceName));
				if (rows.isEmpty()) {
					return;
				}
				final int[] targets = new int[rows.size()];
				final Serializable[] nullValues = new Serializable[rows.size()];
				final Serializable[] secondValues = new Serializable[rows.size()];
				for (int i = 0; i < rows.size(); i++) {
					final ReferenceContract row = rows.get(i);
					targets[i] = row.getReferencedPrimaryKey();
					nullValues[i] = row.getAttribute(corpus.nullAttribute);
					secondValues[i] = corpus.secondAttribute.isBlank() ? null : row.getAttribute(corpus.secondAttribute);
				}
				owners.add(
					new OwnerRows(
						entity.getPrimaryKeyOrThrowException(),
						corpus.narrowingAttribute.isBlank() ? null : entity.getAttribute(corpus.narrowingAttribute),
						targets, nullValues, secondValues
					)
				);
			}
		);
		System.out.printf(
			"%nORACLE %,d entities scanned, %,d own `%s`, in %,d s%n",
			scanned, owners.size(), corpus.referenceName, TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start)
		);
		return owners;
	}

	/**
	 * Pages through every entity of the collection in primary key order and hands each body to the consumer.
	 *
	 * @return the number of entities visited
	 */
	private static int forEachOwner(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		@Nonnull EntityContentRequire[] content,
		@Nonnull Consumer<SealedEntity> consumer
	) {
		int visited = 0;
		for (int pageNumber = 1; ; pageNumber++) {
			final EvitaResponse<SealedEntity> response = session.query(
				Query.query(
					collection(entityType),
					require(page(pageNumber, SCAN_PAGE_SIZE), entityFetch(content))
				),
				SealedEntity.class
			);
			for (SealedEntity entity : response.getRecordData()) {
				consumer.accept(entity);
				visited++;
			}
			// a page past the end is answered with the first page, so the loop must stop on the count, not on
			// an empty page
			if ((long) pageNumber * SCAN_PAGE_SIZE >= response.getTotalRecordCount()) {
				return visited;
			}
		}
	}

	/**
	 * Refuses a reference attribute the measured query cannot use.
	 *
	 * @param forEquals whether the attribute is also compared by `attributeEquals`, which an array type defeats
	 */
	private static void requireFilterable(
		@Nonnull ReferenceSchemaContract reference,
		@Nonnull String attributeName,
		boolean forEquals
	) {
		final AttributeSchemaContract attribute = reference.getAttribute(attributeName)
			.orElseThrow(() -> new IllegalArgumentException(
				"Reference `" + reference.getName() + "` has no attribute `" + attributeName + "`!"
			));
		final String label = "Reference attribute `" + reference.getName() + "." + attributeName + "`";
		if (!attribute.isFilterableInScope(LIVE)) {
			throw new IllegalArgumentException(label + " is not filterable in LIVE!");
		}
		if (attribute.isLocalized()) {
			throw new IllegalArgumentException(label + " is localized - this harness queries without a locale!");
		}
		if (forEquals) {
			requireUsableForEquals(attribute, label);
		}
	}

	/**
	 * Refuses an attribute that `attributeEquals` with a single scalar operand cannot be measured on.
	 */
	private static void requireUsableForEquals(@Nonnull AttributeSchemaContract attribute, @Nonnull String label) {
		if (!attribute.isFilterableInScope(LIVE)) {
			throw new IllegalArgumentException(label + " is not filterable in LIVE!");
		}
		if (attribute.isLocalized()) {
			throw new IllegalArgumentException(label + " is localized - this harness queries without a locale!");
		}
		if (attribute.getType().isArray()) {
			throw new IllegalArgumentException(label + " is an array - its `attributeEquals` operand is ambiguous!");
		}
	}

	/**
	 * Returns the number of reduced indexes (partitions) of the reference in `LIVE`, or -1 when it has no type
	 * index there.
	 */
	private static int countPartitions(@Nonnull EntityCollection collection, @Nonnull String referenceName) {
		final ReferencedTypeEntityIndex typeIndex = (ReferencedTypeEntityIndex) collection.getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, LIVE, referenceName)
		);
		if (typeIndex == null) {
			return -1;
		}
		final int[] partitions = {0};
		typeIndex.forEachReferenceIndexPrimaryKey(pk -> partitions[0]++);
		return partitions[0];
	}

	/**
	 * Counts the owners satisfying the predicate.
	 */
	private static long countOwners(@Nonnull List<OwnerRows> owners, @Nonnull Predicate<OwnerRows> predicate) {
		long count = 0;
		for (OwnerRows owner : owners) {
			if (predicate.test(owner)) {
				count++;
			}
		}
		return count;
	}

	/**
	 * Runs the query once with telemetry, refuses a plan that prefetched and returns the engine's total count.
	 */
	private static int verifyIndexRoute(@Nonnull CorpusState corpus, @Nonnull Query query, @Nonnull String label) {
		final Query withTelemetry = Query.query(
			query.getCollection(),
			query.getFilterBy(),
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
		return response.getTotalRecordCount();
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
	 * Returns the most frequent key, ties broken by the smaller string form so the choice is repeatable across
	 * runs and builds; null for an empty map.
	 */
	@Nullable
	private static Serializable mostFrequent(@Nonnull Map<Serializable, Integer> frequencies) {
		Serializable best = null;
		int bestCount = 0;
		for (Entry<Serializable, Integer> entry : frequencies.entrySet()) {
			final int count = entry.getValue();
			if (count > bestCount ||
				(count == bestCount && best != null && entry.getKey().toString().compareTo(best.toString()) < 0)) {
				best = entry.getKey();
				bestCount = count;
			}
		}
		return best;
	}

	/**
	 * Returns how often the key occurs, 0 for a null key.
	 */
	private static int frequencyOf(@Nonnull Map<Serializable, Integer> frequencies, @Nullable Serializable key) {
		return key == null ? 0 : frequencies.getOrDefault(key, 0);
	}

	/**
	 * Compares two attribute values the way the filter index does: decimals by value, everything else by equality.
	 */
	private static boolean sameValue(@Nullable Serializable value, @Nullable Serializable expected) {
		if (value instanceof BigDecimal left && expected instanceof BigDecimal right) {
			return left.compareTo(right) == 0;
		}
		return value != null && !value.getClass().isArray() && Objects.equals(value, expected);
	}

	/**
	 * Renders an unnamed parameter as a dash in the log.
	 */
	@Nonnull
	private static String blankToDash(@Nonnull String value) {
		return value.isBlank() ? "-" : value;
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
