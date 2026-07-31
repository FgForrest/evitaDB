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

package io.evitadb.performance.sortattribute.state;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.performance.artificial.ArtificialBenchmarkState;
import io.evitadb.performance.setup.EvitaCatalogSetup;
import io.evitadb.performance.sortattribute.SortAttributeIngestBenchmark;
import io.evitadb.test.Entities;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Shared state for {@link SortAttributeIngestBenchmark}: an empty WARM_UP catalog plus a pre-built batch of products
 * carrying the attribute shape that produced the profile behind issue #1332.
 *
 * The shape is the whole point, so it is spelled out here rather than inherited:
 *
 * - **{@link #LOW_CARDINALITY_ATTRIBUTE_COUNT} sortable `Integer` attributes** drawn from {@link #distinctValues}
 *   distinct values, so each value block holds `entityCount / distinctValues` records. That block width is the axis
 *   the issue turns on - it is what `SortIndexChanges.computePreviousRecord` binary-searches on every insert - and it
 *   is a {@link Param} rather than a constant precisely so it can be varied without changing the batch size.
 * - **{@link #NEAR_UNIQUE_ATTRIBUTE_COUNT} sortable `OffsetDateTime` attributes** that are strictly increasing, so
 *   their blocks are one record wide. **These are the control**: an optimisation aimed at wide blocks must not
 *   regress them.
 * - **{@link #FACETED_REFERENCE_COUNT} faceted references** and {@link #PLAIN_STRING_ATTRIBUTE_COUNT} non-indexed
 *   strings, so the sort-index work is measured against realistic competing indexing load - facet indexing plus
 *   entity building and storage serialisation - rather than in isolation.
 *
 * **Why this class builds its products directly** rather than through `DataGenerator`:
 *
 * 1. *Cardinality control.* `DataGenerator` funnels every sortable attribute that has no explicitly registered value
 *    generator through `getUniqueAttribute()`, which forces the values distinct. Forty sortable `Integer` attributes
 *    would therefore come out near-unique - one record per block, the exact opposite of the profiled workload, and a
 *    silent failure because the benchmark would still run and report a score. Building the values here with an explicit
 *    `random.nextInt(distinctValues)` keeps the cardinality at {@link #distinctValues} exactly.
 * 2. *`DataGenerator.pickRandomFromSet` infinite-loops at this reference count.* Its per-entity reference selection does
 *    not terminate once a schema carries enough reference types; thirty faceted references trip it. That is a latent
 *    bug in shared test infrastructure, not something #1332 should touch, so this benchmark sidesteps it by never
 *    entering that code path.
 * 3. *Determinism across the A/B pair.* This benchmark is measured cross-jar (this branch's engine against the base
 *    engine in a sibling worktree), so the two runs must ingest byte-for-byte identical batches. A fresh
 *    `Random(SEED)` walked in primary-key order gives exactly that; Javafaker's per-locale faker caches do not.
 * 4. *Setup cost.* Direct construction is far cheaper than Javafaker per entity, which is what makes a large
 *    `entityCount` affordable and keeps generation from dwarfing the ingest it is meant to feed.
 *
 * The references are **unmanaged** and `ZERO_OR_ONE`. Unmanaged means each is filled with a plain integer id rather
 * than a resolved seeded entity, so thirty reference types cost nothing to set up and need no target catalogs.
 * `ZERO_OR_ONE` (not `ZERO_OR_MORE`) keeps it at one reference per type: the profiled workload attaches a single facet
 * per group, and ten-per-type would bury the sort-index work under facet indexing.
 *
 * **Read `gc.alloc.rate.norm`, not wall time.** At the iteration counts this benchmark can afford, wall clock is far
 * too noisy to carry a signal (this issue saw ±258 % on `ns/op` against ±0.75 % on allocation in the same run), and
 * this benchmark is inherently a cross-jar comparison that cannot be paired inside one JVM. The honest, measurable
 * question it answers is therefore *how much of a full ingest's allocation the branch's allocation-side changes
 * remove* - the dropped `InsertionPosition` record in the int-keyed internal-node search, the lazily captured B+ tree
 * cursor path, the allocation-free unordered-array guards - so run it with `-prof gc` and compare the normalised
 * allocation rate against the base worktree.
 *
 * **Reported bytes/op is `fixture + ingest`; subtract the control to get the ingest alone.** `setUp()` is annotated
 * `@Setup(Level.Invocation)` and boots a full Evita instance, defines the schema and materialises the whole batch,
 * and `closeEvita()` is its matching `@TearDown(Level.Invocation)`. JMH's `GCProfiler` is an `InternalProfiler`: it
 * snapshots the allocation counters in `beforeIteration` / `afterIteration`, which brackets the **entire** iteration
 * including both per-invocation fixtures, so their allocation lands inside the reported figure.
 *
 * `SortAttributeIngestBenchmark.fixtureControl` is what makes that recoverable - it takes this same state and does
 * nothing, so whatever it reports *is* the fixture. The protocol is
 *
 * ```
 * ingest allocation = warmUpIngest[distinctValues] - fixtureControl[distinctValues]
 * ```
 *
 * with three conditions that are easy to get wrong:
 *
 * 1. **Subtract per `distinctValues`; never pool one control across both.** The two settings do not carry the same
 *    fixture cost. `setAttribute` boxes every low-cardinality `int` and `Integer.valueOf` caches `-128..127`, so at
 *    `distinctValues = 20` all `entityCount * 40` values are cache hits and allocate nothing, while at 1000 only
 *    12.8 % of them are. Measured at `entityCount = 20 000`: 1.268 GB/op against 1.257 GB/op - an 11.2 MB gap, which
 *    is exactly the 697 600 boxes that escape the cache. Pooling would over-subtract one arm and under-subtract the
 *    other.
 * 2. **Take the control from the same build as the `warmUpIngest` it is subtracted from**, and say which build that
 *    was. The fixture itself is stable across an A/B pair - it touches only `evita_api` builders and the engine's
 *    boot path, neither of which a sort-index change moves - but the percentage's denominator is that one build's
 *    own `warmUpIngest - fixtureControl`.
 * 3. **The remainder is the ingest plus a teardown asymmetry.** `closeEvita()` is inside the bracket for both
 *    methods, but the control closes an *empty* catalog while `warmUpIngest` closes one holding `entityCount`
 *    entities, so the flush the ingest provokes stays in the remainder. That is where it belongs - the ingest caused
 *    it - but the remainder is therefore not the `upsertEntity` loop in isolation.
 *
 * **Why the batch is still rebuilt every invocation.** The fixture is 1.35 % (`distinctValues = 1000`) and 1.65 %
 * (`distinctValues = 20`) of the corresponding `warmUpIngest` at `entityCount = 20 000`, and scaling the control from
 * 5 000 to 20 000 entities shows ~96 % of it is the batch itself (~61 kB per builder against ~49 MB of fixed boot,
 * schema and empty-catalog close). Memoising the batch across a trial would therefore remove most of the fixture -
 * but the subtraction above already removes *all* of it, exactly, so hoisting would buy no accuracy while costing the
 * guarantee that each invocation ingests a batch no previous invocation has touched. The Evita boot cannot leave
 * `Level.Invocation` at all: a catalog still holding the previous invocation's entities would start with wide sort
 * blocks and would stop measuring a cold WARM_UP load.
 *
 * **Reproducing the #1332 figures.** They were taken with `-p entityCount=5000`, not at the `@Param` default of
 * 20 000 committed here - the block widths quoted alongside them (~5 and ~250) only follow from 5 000. At that
 * override this harness reports 26.222 and 22.600 GB/op against the 25.920 and 22.472 recorded there, so it
 * reproduces to within 1.2 %.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@State(Scope.Benchmark)
public class SortAttributeIngestBenchmarkState extends ArtificialBenchmarkState
	implements EvitaCatalogSetup {

	/** Number of low-cardinality sortable `Integer` attributes - the subject of issue #1332. */
	public static final int LOW_CARDINALITY_ATTRIBUTE_COUNT = 40;
	/** Number of strictly-increasing sortable attributes kept as the narrow-block control. */
	public static final int NEAR_UNIQUE_ATTRIBUTE_COUNT = 5;
	/** Number of faceted reference types providing competing indexing load. */
	public static final int FACETED_REFERENCE_COUNT = 30;
	/** Number of non-indexed string attributes providing representative entity bulk. */
	public static final int PLAIN_STRING_ATTRIBUTE_COUNT = 5;
	/** Distinct facet ids each reference draws from, so facets share groups the way a real dataset does. */
	public static final int FACET_GROUP_SIZE = 100;

	/** Name prefix of the low-cardinality sortable attributes. */
	public static final String ATTRIBUTE_LOW_CARDINALITY_PREFIX = "sortableInt";
	/** Name prefix of the near-unique sortable attributes. */
	public static final String ATTRIBUTE_NEAR_UNIQUE_PREFIX = "sortableMoment";
	/** Name prefix of the non-indexed string attributes. */
	public static final String ATTRIBUTE_PLAIN_PREFIX = "plainText";
	/** Entity-type prefix of the unmanaged faceted references. */
	public static final String REFERENCE_TYPE_PREFIX = "FACET_GROUP_";

	/** Anchor for the strictly-increasing control moments, offset per entity so their blocks stay one record wide. */
	private static final OffsetDateTime BASE_MOMENT = OffsetDateTime.of(
		2020, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC
	);

	/** Number of products ingested per measured invocation. */
	@Param({"20000"})
	public int entityCount;

	/**
	 * Distinct values each low-cardinality attribute draws from. Block width is `entityCount / distinctValues`, so
	 * the two defaults give 20-wide blocks (inside one leaf) and 1000-wide blocks (spanning many leaves) - the two
	 * regimes the sort-block search behaves differently in.
	 */
	@Param({"1000", "20"})
	public int distinctValues;

	/** The fully materialised batch to ingest - built in setup so generation stays out of the measurement. */
	private List<EntityBuilder> productBatch;

	/**
	 * Name of the low-cardinality sortable attribute with the given ordinal.
	 *
	 * @param ordinal zero-based attribute ordinal
	 * @return the attribute name
	 */
	@Nonnull
	public static String lowCardinalityAttribute(int ordinal) {
		return ATTRIBUTE_LOW_CARDINALITY_PREFIX + ordinal;
	}

	/**
	 * Name of the near-unique sortable attribute with the given ordinal.
	 *
	 * @param ordinal zero-based attribute ordinal
	 * @return the attribute name
	 */
	@Nonnull
	public static String nearUniqueAttribute(int ordinal) {
		return ATTRIBUTE_NEAR_UNIQUE_PREFIX + ordinal;
	}

	/**
	 * Boots an empty catalog, defines the product schema and materialises the batch to ingest, **without** ingesting
	 * anything - the ingest itself is the measured operation.
	 *
	 * This runs per invocation rather than per trial because the subject is a cold WARM_UP bulk load: a catalog that
	 * already held the previous invocation's entities would start with wide blocks and grow them wider, so successive
	 * invocations would not be measuring the same thing.
	 */
	@Setup(Level.Invocation)
	public void setUp() {
		final String catalogName = getCatalogName();
		this.evita = createEmptyEvitaInstance(catalogName);
		this.evita.updateCatalog(
			catalogName,
			session -> {
				final EntitySchemaBuilder schemaBuilder = session.defineEntitySchema(Entities.PRODUCT);
				declareSortAttributeShape(schemaBuilder);
				this.productSchema = session.updateAndFetchEntitySchema(schemaBuilder);
			}
		);
		// materialise the whole batch OUTSIDE the measured method - see the class JavaDoc. A fresh Random(SEED) walked
		// in primary-key order makes the batch identical on every invocation and, crucially, across the A/B jars.
		final List<EntityBuilder> batch = new ArrayList<>(this.entityCount);
		final Random valueRandom = new Random(SEED);
		for (int primaryKey = 1; primaryKey <= this.entityCount; primaryKey++) {
			batch.add(buildProduct(this.productSchema, primaryKey, valueRandom, this.distinctValues));
		}
		this.productBatch = batch;
	}

	/**
	 * Builds one product entity directly, filling exactly the #1332 shape.
	 *
	 * The low-cardinality integers are drawn from `[0, distinctValues)` so their sort blocks are `entityCount /
	 * distinctValues` wide; the control moments are strictly increasing (base plus a per-entity, per-attribute offset)
	 * so their blocks stay one record wide; the references get a bounded random id so facets share groups.
	 *
	 * @param schema         the product schema the builder validates against
	 * @param primaryKey     the entity primary key, also the driver of the strictly-increasing control moments
	 * @param random         the shared, seeded randomiser - walked in key order to stay deterministic across jars
	 * @param distinctValues distinct values each low-cardinality attribute may take
	 * @return a detached builder ready to be upserted
	 */
	@Nonnull
	private static EntityBuilder buildProduct(
		@Nonnull EntitySchemaContract schema, int primaryKey, @Nonnull Random random, int distinctValues
	) {
		final EntityBuilder builder = new InitialEntityBuilder(schema, primaryKey);
		for (int i = 0; i < LOW_CARDINALITY_ATTRIBUTE_COUNT; i++) {
			builder.setAttribute(lowCardinalityAttribute(i), random.nextInt(distinctValues));
		}
		for (int i = 0; i < NEAR_UNIQUE_ATTRIBUTE_COUNT; i++) {
			// unique across the whole batch: primaryKey blocks of NEAR_UNIQUE_ATTRIBUTE_COUNT seconds, one per attribute
			builder.setAttribute(
				nearUniqueAttribute(i),
				BASE_MOMENT.plusSeconds((long) primaryKey * NEAR_UNIQUE_ATTRIBUTE_COUNT + i)
			);
		}
		for (int i = 0; i < PLAIN_STRING_ATTRIBUTE_COUNT; i++) {
			builder.setAttribute(ATTRIBUTE_PLAIN_PREFIX + i, Long.toString(random.nextLong(), Character.MAX_RADIX));
		}
		for (int i = 0; i < FACETED_REFERENCE_COUNT; i++) {
			builder.setReference(REFERENCE_TYPE_PREFIX + i, 1 + random.nextInt(FACET_GROUP_SIZE));
		}
		return builder;
	}

	/**
	 * Declares the #1332 attribute shape on a fresh product schema.
	 *
	 * @param schemaBuilder builder of the product schema
	 */
	private static void declareSortAttributeShape(@Nonnull EntitySchemaBuilder schemaBuilder) {
		for (int i = 0; i < LOW_CARDINALITY_ATTRIBUTE_COUNT; i++) {
			schemaBuilder.withAttribute(
				lowCardinalityAttribute(i), Integer.class, whichIs -> whichIs.sortable().filterable()
			);
		}
		for (int i = 0; i < NEAR_UNIQUE_ATTRIBUTE_COUNT; i++) {
			schemaBuilder.withAttribute(
				nearUniqueAttribute(i), OffsetDateTime.class, whichIs -> whichIs.sortable()
			);
		}
		for (int i = 0; i < PLAIN_STRING_ATTRIBUTE_COUNT; i++) {
			schemaBuilder.withAttribute(
				ATTRIBUTE_PLAIN_PREFIX + i, String.class, whichIs -> whichIs.nullable()
			);
		}
		for (int i = 0; i < FACETED_REFERENCE_COUNT; i++) {
			// ZERO_OR_ONE, not ZERO_OR_MORE - see the class JavaDoc for why the cardinality matters here
			schemaBuilder.withReferenceTo(
				REFERENCE_TYPE_PREFIX + i, REFERENCE_TYPE_PREFIX + i, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.indexedForFilteringAndPartitioning().faceted()
			);
		}
	}

	/**
	 * Returns the pre-built batch the measured method ingests.
	 *
	 * @return the materialised products, exactly `entityCount` of them
	 */
	@Nonnull
	public List<EntityBuilder> productBatch() {
		return this.productBatch;
	}

	/**
	 * We need writable sessions here.
	 */
	@Override
	public EvitaSessionContract getSession() {
		return getSession(() -> this.evita.createReadWriteSession(getCatalogName()));
	}

	@Override
	protected String getCatalogName() {
		return TEST_CATALOG + "_sortAttributeIngest";
	}

	/**
	 * Publicly reachable alias of {@link #getCatalogName()} - the inherited accessor is protected and the benchmark
	 * class lives in a different package.
	 *
	 * @return name of the catalog the benchmark ingests into
	 */
	@Nonnull
	public String getCatalogNameForBenchmark() {
		return getCatalogName();
	}

	/**
	 * Shuts the instance down and releases the batch after every measured ingest.
	 */
	@TearDown(Level.Invocation)
	public void closeEvita() {
		this.evita.close();
		this.productBatch = null;
	}

}
