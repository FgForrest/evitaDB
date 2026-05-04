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

package io.evitadb.api.functional.histogram;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.core.Evita;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import io.evitadb.test.annotation.DataSet;
import io.evitadb.test.extension.DataCarrier;
import io.evitadb.test.extension.EvitaParameterResolver;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.HISTOGRAM;
import static io.evitadb.test.TestTags.REFERENCE;


/**
 * Shared test base for the reference-summary histogram functional test suite. Hosts two
 * {@link DataSet} generator methods — a hand-crafted 6-product fixture
 * ({@link #REFERENCE_HISTOGRAM_SMALL}) and a deterministic 60-product read-only fixture
 * ({@link #REFERENCE_HISTOGRAM_LARGE}) — so the concrete subclasses can share the same
 * provisioned Evita instance via `@UseDataSet(...)` and avoid spinning up per-test
 * catalogs.
 *
 * Also hosts the shared {@link OverlapFixture} helper used by the REFERENCE_ATTRIBUTE
 * boundary-resolution and mutation-path tests, together with the {@link PvCandidate}
 * record and collector helpers consumed by the oracle / boundary suites.
 *
 * Concrete subclasses must be `public` so the JUnit {@link EvitaParameterResolver}
 * extension is able to discover them.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
@Tag(CONTRACT)
@Tag(HISTOGRAM)
@Tag(REFERENCE)
public abstract class AbstractReferenceSummaryHistogramFunctionalTest implements EvitaTestSupport {

	/**
	 * Name of the small 6-product fixture. Used by tests that need tight control over a
	 * hand-picked per-group value distribution (two parameter groups with {10, 20, 30}
	 * and {100, 200} `basicUnitValue` spans plus a reference-level `marketShare` ladder).
	 */
	public static final String REFERENCE_HISTOGRAM_SMALL = "referenceHistogramSmall";

	/**
	 * Name of the large 60-product, read-only fixture. Three parameter groups each hold
	 * 20 products cross-referenced to 10 parameter values; reference-level `marketShare`
	 * and referenced-entity `price` are drawn from a seeded {@link Random} so the data
	 * is reproducible.
	 */
	public static final String REFERENCE_HISTOGRAM_LARGE = "referenceHistogramLarge";

	// ---------------------------------------------------------------------
	// shared entity / reference / attribute / histogram names
	// ---------------------------------------------------------------------

	protected static final String ENTITY_PRODUCT = "product";
	protected static final String ENTITY_PARAMETER_VALUE = "parameterValue";
	protected static final String ENTITY_PARAMETER = "parameter";

	protected static final String REF_PARAM_VALUES = "parameterValues";
	protected static final String REF_CATEGORIES = "categories";

	/**
	 * Small-fixture attribute — source of the `priceBucket` histogram in the hand-crafted
	 * schema. The large fixture uses {@link #ATTR_PRICE} instead.
	 */
	protected static final String ATTR_BASIC_UNIT_VALUE = "basicUnitValue";

	/**
	 * Large-fixture attribute — source of the `priceBucket` histogram in the seeded
	 * 60-product schema.
	 */
	protected static final String ATTR_PRICE = "price";

	protected static final String ATTR_MARKET_SHARE = "marketShare";
	protected static final String ATTR_QUANTITY = "quantity";
	protected static final String ATTR_NAME = "name";

	/**
	 * Histogram name used by both fixtures. In the small fixture it sources
	 * {@link #ATTR_BASIC_UNIT_VALUE}; in the large fixture it sources {@link #ATTR_PRICE}.
	 */
	protected static final String HISTOGRAM_PRICE = "priceBucket";

	/**
	 * Histogram sourcing the reference-level `marketShare` attribute — present in both
	 * fixtures under the same name.
	 */
	protected static final String HISTOGRAM_MARKET_SHARE = "marketShareBucket";

	// ---------------------------------------------------------------------
	// large-fixture fixed sizes — kept on the base so subclasses can iterate
	// their assertions without recomputing the seed shape.
	// ---------------------------------------------------------------------

	protected static final int GROUP_COUNT = 3;
	protected static final int PRODUCTS_PER_GROUP = 20;
	protected static final int PRODUCT_COUNT = GROUP_COUNT * PRODUCTS_PER_GROUP;
	protected static final long SEED = 42L;

	/**
	 * Defines a product → parameterValue → parameter schema with the reference carrying
	 * both a facet and TWO histograms:
	 *
	 * - `priceBucket`: REFERENCED_ENTITY_ATTRIBUTE on the parameter value's
	 * `basicUnitValue`;
	 * - `marketShareBucket`: REFERENCE_ATTRIBUTE on the reference's own `marketShare`
	 * attribute.
	 *
	 * Both histogram sources let us exercise REFERENCE_ATTRIBUTE and
	 * REFERENCED_ENTITY_ATTRIBUTE branches of the requested-bucket walker.
	 */
	protected static void defineSmallSchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_PARAMETER)
			.withAttribute(ATTR_NAME, String.class, whichIs -> whichIs.filterable().nullable())
			.updateVia(session);

		session.defineEntitySchema(ENTITY_PARAMETER_VALUE)
			.withAttribute(ATTR_NAME, String.class, whichIs -> whichIs.filterable().nullable())
			.withAttribute(
				ATTR_BASIC_UNIT_VALUE, BigDecimal.class,
				whichIs -> whichIs.filterable().indexDecimalPlaces(2).nullable()
			)
			.updateVia(session);

		session.defineEntitySchema(ENTITY_PRODUCT)
			.withReferenceToEntity(
				REF_PARAM_VALUES, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.indexedWithComponents(ReferenceIndexedComponents.values())
					.faceted()
					.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
					.withAttribute(
						ATTR_MARKET_SHARE, BigDecimal.class,
						thatIs -> thatIs.filterable().indexDecimalPlaces(2).nullable()
					)
					.bucketed(
						HISTOGRAM_PRICE,
						ExpressionFactory.parse(
							"$reference.referencedEntity?.attributes['basicUnitValue']"
						)
					)
					.bucketed(
						HISTOGRAM_MARKET_SHARE,
						ExpressionFactory.parse(
							"$reference.attributes['" + ATTR_MARKET_SHARE + "']"
						)
					)
			)
			.updateVia(session);
	}

	/**
	 * Loads a small fixed dataset: 2 parameter groups, 5 parameter values with distinct
	 * `basicUnitValue`s, 6 products distributing references across both groups.
	 * Parameter group 1 gets values {10, 20, 30}; parameter group 2 gets values
	 * {100, 200}. Products reference one or two values each.
	 *
	 * Reference-level `marketShare` is assigned deterministically per (product, pv)
	 * pair so we get a spread of values {10, 20, 30, 40, 50, 60, 70} for range tests.
	 */
	protected static void seedSmallData(@Nonnull EvitaSessionContract session) {
		session.createNewEntity(ENTITY_PARAMETER, 1)
			.setAttribute(ATTR_NAME, "Width")
			.upsertVia(session);
		session.createNewEntity(ENTITY_PARAMETER, 2)
			.setAttribute(ATTR_NAME, "Weight")
			.upsertVia(session);

		createParameterValue(session, 1, "10cm", "10");
		createParameterValue(session, 2, "20cm", "20");
		createParameterValue(session, 3, "30cm", "30");
		createParameterValue(session, 4, "100g", "100");
		createParameterValue(session, 5, "200g", "200");

		// products in group 1 (Width) referencing values 1..3
		createProductWithRefs(session, 1, new int[]{1}, new int[]{10}, 1);
		createProductWithRefs(session, 2, new int[]{2}, new int[]{20}, 1);
		createProductWithRefs(session, 3, new int[]{3}, new int[]{30}, 1);
		createProductWithRefs(session, 4, new int[]{1, 2}, new int[]{40, 50}, 1);
		// products in group 2 (Weight) referencing values 4..5
		createProductWithRefs(session, 5, new int[]{4}, new int[]{60}, 2);
		createProductWithRefs(session, 6, new int[]{5}, new int[]{70}, 2);
	}

	// ---------------------------------------------------------------------
	// small-fixture entity builders (used by seedSmallData above)
	// ---------------------------------------------------------------------

	/**
	 * Creates a single parameter-value entity with the given primary key, human-friendly
	 * name and BigDecimal-encoded `basicUnitValue`.
	 */
	protected static void createParameterValue(
		@Nonnull EvitaSessionContract session, int pk, @Nonnull String name, @Nonnull String value
	) {
		session.createNewEntity(ENTITY_PARAMETER_VALUE, pk)
			.setAttribute(ATTR_NAME, name)
			.setAttribute(ATTR_BASIC_UNIT_VALUE, new BigDecimal(value))
			.upsertVia(session);
	}

	/**
	 * Creates a product and wires it to the requested parameter values, each with its own
	 * `marketShare` attribute and a shared parameter-group link.
	 */
	protected static void createProductWithRefs(
		@Nonnull EvitaSessionContract session, int productPk, @Nonnull int[] pvPks,
		@Nonnull int[] marketShares, int groupPk
	) {
		final EntityBuilder builder = session.createNewEntity(ENTITY_PRODUCT, productPk);
		for (int i = 0; i < pvPks.length; i++) {
			final int pvPk = pvPks[i];
			final int marketShare = marketShares[i];
			builder.setReference(
				REF_PARAM_VALUES, pvPk,
				whichIs -> whichIs
					.setGroup(ENTITY_PARAMETER, groupPk)
					.setAttribute(ATTR_MARKET_SHARE, new BigDecimal(marketShare))
			);
		}
		builder.upsertVia(session);
	}

	/**
	 * Defines the schema with two complementary histograms on a single reference:
	 *
	 * - `priceBucket`: REFERENCED_ENTITY_ATTRIBUTE — drills into the parameter value's
	 * `price` attribute;
	 * - `marketShareBucket`: REFERENCE_ATTRIBUTE — reads the `marketShare` attribute set
	 * directly on the reference.
	 */
	protected static void defineLargeSchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_PARAMETER)
			.withAttribute(ATTR_NAME, String.class, whichIs -> whichIs.filterable().nullable())
			.updateVia(session);

		session.defineEntitySchema(ENTITY_PARAMETER_VALUE)
			.withAttribute(ATTR_NAME, String.class, whichIs -> whichIs.filterable().nullable())
			.withAttribute(
				ATTR_PRICE, BigDecimal.class,
				whichIs -> whichIs.filterable().indexDecimalPlaces(2).nullable()
			)
			.updateVia(session);

		session.defineEntitySchema(ENTITY_PRODUCT)
			.withAttribute(
				ATTR_QUANTITY, BigDecimal.class,
				whichIs -> whichIs.filterable().indexDecimalPlaces(2).nullable()
			)
			.withReferenceToEntity(
				REF_PARAM_VALUES, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
				whichIs -> whichIs
					.indexedForFilteringAndPartitioning()
					.indexedWithComponents(ReferenceIndexedComponents.values())
					.faceted()
					.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
					.withAttribute(
						ATTR_MARKET_SHARE, BigDecimal.class,
						thatIs -> thatIs.filterable().indexDecimalPlaces(2)
					)
					.bucketed(
						HISTOGRAM_PRICE,
						ExpressionFactory.parse(
							"$reference.referencedEntity?.attributes['" + ATTR_PRICE + "']"
						)
					)
					.bucketed(
						HISTOGRAM_MARKET_SHARE,
						ExpressionFactory.parse(
							"$reference.attributes['" + ATTR_MARKET_SHARE + "']"
						)
					)
			)
			.updateVia(session);
	}

	/**
	 * Seeds the deterministic 60-product fixture — 3 parameter groups, 10 parameter
	 * values per group (prices scaled per-group so ranges don't overlap), and
	 * {@link #PRODUCT_COUNT} products pseudo-randomly wired to 1..3 values in their
	 * assigned group. Reference `marketShare` is drawn from a second PRNG stream so
	 * histogram content is fully deterministic.
	 */
	protected static void seedLargeData(@Nonnull EvitaSessionContract session) {
		// parameters (groups)
		for (int groupPk = 1; groupPk <= GROUP_COUNT; groupPk++) {
			session.createNewEntity(ENTITY_PARAMETER, groupPk)
				.setAttribute(ATTR_NAME, "group-" + groupPk)
				.upsertVia(session);
		}

		// parameter values: 10 per group, price scaled per group so ranges don't overlap
		for (int groupPk = 1; groupPk <= GROUP_COUNT; groupPk++) {
			for (int i = 0; i < 10; i++) {
				final int pvPk = (groupPk - 1) * 10 + i + 1;
				final BigDecimal price = new BigDecimal(groupPk * 100 + i * 10);
				session.createNewEntity(ENTITY_PARAMETER_VALUE, pvPk)
					.setAttribute(ATTR_NAME, "pv-" + pvPk)
					.setAttribute(ATTR_PRICE, price)
					.upsertVia(session);
			}
		}

		// products: PRODUCTS_PER_GROUP per group, references 1-3 PVs from its group
		final Random rnd = new Random(SEED);
		for (int productPk = 1; productPk <= PRODUCT_COUNT; productPk++) {
			final int groupPk = ((productPk - 1) / PRODUCTS_PER_GROUP) + 1;
			final int refCount = 1 + rnd.nextInt(3);
			final int groupPvStart = (groupPk - 1) * 10 + 1;
			final int groupPvEnd = groupPk * 10;
			final Set<Integer> pickedPvs = new HashSet<>();
			while (pickedPvs.size() < refCount) {
				pickedPvs.add(groupPvStart + rnd.nextInt(groupPvEnd - groupPvStart + 1));
			}
			final EntityBuilder builder = session.createNewEntity(ENTITY_PRODUCT, productPk)
				.setAttribute(ATTR_QUANTITY, new BigDecimal(1 + rnd.nextInt(100)));
			for (final int pvPk : pickedPvs) {
				final BigDecimal share = new BigDecimal(1 + rnd.nextInt(1000))
					.divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
				builder.setReference(
					REF_PARAM_VALUES, pvPk,
					whichIs -> whichIs
						.setGroup(ENTITY_PARAMETER, groupPk)
						.setAttribute(ATTR_MARKET_SHARE, share)
				);
			}
			builder.upsertVia(session);
		}
	}

	// ---------------------------------------------------------------------
	// large-fixture oracle helpers (drive boundary-resolution assertions)
	// ---------------------------------------------------------------------

	/**
	 * Collects every `(pvPk, groupPk, marketShare, name)` tuple present in the large
	 * fixture. Dedups by `(pvPk, marketShare)` within each group so multiple products
	 * referencing the same pvPk with the same share collapse to a single candidate.
	 *
	 * @param session read session against the large fixture
	 * @return mapping from `groupPk` to the list of candidate tuples for that group
	 */
	@Nonnull
	protected static Map<Integer, List<PvCandidate>> collectReferencesWithMarketShare(
		@Nonnull EvitaSessionContract session
	) {
		final Map<Integer, Map<Integer, PvCandidate>> byGroupAndPk =
			new LinkedHashMap<>();
		for (int productPk = 1; productPk <= PRODUCT_COUNT; productPk++) {
			final Optional<SealedEntity> product = session.getEntity(
				ENTITY_PRODUCT, productPk,
				QueryConstraints.referenceContentWithAttributes(
					REF_PARAM_VALUES, ATTR_MARKET_SHARE
				)
			);
			if (product.isEmpty()) {
				continue;
			}
			for (final ReferenceContract ref : product.get().getReferences(REF_PARAM_VALUES)) {
				final int groupPk = ref.getGroup()
					.map(EntityReferenceContract::getPrimaryKey)
					.orElseThrow();
				final int pvPk = ref.getReferencedPrimaryKey();
				final BigDecimal marketShare = ref.getAttribute(ATTR_MARKET_SHARE, BigDecimal.class);
				if (marketShare == null) {
					continue;
				}
				final Optional<SealedEntity> pvEntity = session.getEntity(
					ENTITY_PARAMETER_VALUE, pvPk,
					QueryConstraints.attributeContent(ATTR_NAME)
				);
				final String pvName = pvEntity.map(p -> p.getAttribute(ATTR_NAME, String.class))
					.orElse("pv-" + pvPk);
				final PvCandidate candidate = new PvCandidate(pvPk, marketShare, pvName);
				byGroupAndPk
					.computeIfAbsent(groupPk, k -> new LinkedHashMap<>())
					.put(candidate.hashKey(), candidate);
			}
		}
		final Map<Integer, List<PvCandidate>> out = new LinkedHashMap<>();
		for (final Entry<Integer, Map<Integer, PvCandidate>> e : byGroupAndPk.entrySet()) {
			out.put(e.getKey(), new ArrayList<>(e.getValue().values()));
		}
		return out;
	}

	/**
	 * Returns the lowest pvPk in `candidates` whose `marketShare` equals `value` (compared via
	 * `compareTo` to tolerate scale differences introduced by `indexDecimalPlaces(2)`
	 * normalization). Used by boundary-resolution assertions as the ground-truth for the
	 * "no facet sorter → lowest PK wins" fallback rule.
	 *
	 * @param candidates candidate list within a single group
	 * @param value      target marketShare value to match
	 * @return the lowest {@code pvPk} in {@code candidates} whose marketShare equals {@code value}
	 */
	protected static int lowestPkWithValue(
		@Nonnull List<PvCandidate> candidates,
		@Nonnull BigDecimal value
	) {
		int best = Integer.MAX_VALUE;
		for (final PvCandidate c : candidates) {
			if (c.marketShare().compareTo(value) == 0 && c.pvPk() < best) {
				best = c.pvPk();
			}
		}
		Assertions.assertTrue(
			best != Integer.MAX_VALUE,
			"No candidate with value " + value + " found in " + candidates
		);
		return best;
	}

	// ---------------------------------------------------------------------
	// Bespoke-schema test scaffolding
	// ---------------------------------------------------------------------

	/**
	 * Builds the standard {@link EvitaConfiguration} used by bespoke-schema tests:
	 * disabled session inactivity timeout plus dedicated per-test storage and export
	 * directories rooted under {@link #getTestDirectory()}.
	 */
	@Nonnull
	protected EvitaConfiguration createEvitaConfiguration(@Nonnull TestPaths paths) {
		return newTestEvitaConfigurationBuilder(paths)
			.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
			.build();
	}

	/**
	 * Scaffolds a bespoke-schema test: cleans a pair of per-test directories, spins up a
	 * fresh {@link Evita} instance bound to them, applies the schema definition, optionally
	 * seeds data, and hands the running instance to the caller's assertions. Both
	 * directories are cleaned again on exit so tests do not leak disk state into siblings.
	 *
	 * The storage directory name is {@code dirPrefix} and the export directory is derived
	 * as {@code dirPrefix + "_export"} so each test only supplies a single prefix.
	 *
	 * @param dirPrefix        unique prefix identifying the test (storage dir name — the
	 *                         export dir is {@code dirPrefix + "_export"})
	 * @param schemaDefinition callback that installs the bespoke schema
	 * @param seed             optional callback that seeds data (run after the schema);
	 *                         pass {@code null} when no seeding is required
	 * @param assertions       callback invoked with the live Evita instance
	 */
	protected void runWithInlineSchema(
		@Nonnull String dirPrefix,
		@Nonnull Consumer<EvitaSessionContract> schemaDefinition,
		@Nullable Consumer<EvitaSessionContract> seed,
		@Nonnull Consumer<Evita> assertions
	) {
		final TestPaths paths = createTestPaths(dirPrefix);
		try (
			Evita evita = new Evita(createEvitaConfiguration(paths))
		) {
			evita.defineCatalog(TEST_CATALOG);
			evita.updateCatalog(TEST_CATALOG, schemaDefinition);
			if (seed != null) {
				evita.updateCatalog(TEST_CATALOG, seed);
			}
			assertions.accept(evita);
		} finally {
			cleanupTestPaths(paths);
		}
	}

	// ---------------------------------------------------------------------
	// @DataSet provisioning methods (invoked by EvitaParameterResolver)
	// ---------------------------------------------------------------------

	/**
	 * Installs the hand-crafted 6-product schema and data into the provided Evita.
	 * Two parameter groups (Width, Weight) hold 5 parameter values whose
	 * `basicUnitValue`s span {10, 20, 30, 100, 200}; products reference one or two
	 * values each, carrying a reference-level `marketShare` in the {10..70} band.
	 *
	 * `readOnly = false` lets the requested-flag negative-path test install an extra
	 * plain reference on top of this catalog — no concurrent fixture shares the
	 * `REFERENCE_HISTOGRAM_SMALL` catalog so that mutation is safe.
	 */
	@DataSet(value = REFERENCE_HISTOGRAM_SMALL, readOnly = false)
	DataCarrier setUpSmall(@Nonnull Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG, session -> {
				defineSmallSchema(session);
				seedSmallData(session);
			}
		);
		return new DataCarrier();
	}

	/**
	 * Installs the deterministic 60-product read-only schema and data into the provided
	 * Evita. Three parameter groups each carry 10 parameter values with `price` in
	 * disjoint ranges; products are scattered across the groups by a seeded PRNG.
	 */
	@DataSet(REFERENCE_HISTOGRAM_LARGE)
	DataCarrier setUpLarge(@Nonnull Evita evita) {
		evita.updateCatalog(
			TEST_CATALOG, session -> {
				defineLargeSchema(session);
				seedLargeData(session);
			}
		);
		return new DataCarrier();
	}

	/**
	 * Captured `(pvPk, marketShare, name)` triple harvested from the large fixture.
	 * Identity is `(pvPk, marketShare)` so multiple carriers of the same value survive
	 * dedup while a single carrier still collapses.
	 *
	 * @param pvPk        referenced entity primary key
	 * @param marketShare the value carried on the reference edge
	 * @param name        the referenced entity's `name` attribute for tie-breaker
	 *                    verification
	 */
	protected record PvCandidate(
		int pvPk,
		@Nonnull BigDecimal marketShare,
		@Nonnull String name
	) {
		int hashKey() {
			return 31 * this.pvPk + this.marketShare.hashCode();
		}
	}

	// ---------------------------------------------------------------------
	// OverlapFixture — isolated Evita instances for scenarios the large seed
	// can't reach (multi-group overlap, deliberate ties, mutation paths).
	// ---------------------------------------------------------------------

	/**
	 * Dedicated helper that provisions an isolated Evita instance with a tiny,
	 * purpose-built fixture for scenarios the deterministic parent seed cannot reach:
	 *
	 * - multi-group overlap — a single referenced PK appears in two different groups
	 * with distinct reference-attribute values;
	 * - deliberate ties — two referenced PKs carry the same min marketShare in a single
	 * group so the facet-sorter tie-breaker path is forced;
	 * - mutation paths — attribute update and reference removal driving RGEI re-keying.
	 *
	 * Each factory method creates a fresh {@link Evita} in a temp directory, seeds the
	 * fixture, runs the caller's assertions, and tears the instance down — so parent
	 * fixture invariants remain untouched.
	 */
	static final class OverlapFixture implements EvitaTestSupport {
		static final int SHARED_PV = 500;
		static final int TIE_LOW_PV = 100;
		static final int TIE_HIGH_PV = 200;

		private static final String LABEL_OVERLAP = "referenceHistogramE2E_overlap";
		private static final String LABEL_TIE = "referenceHistogramE2E_tie";
		private static final String LABEL_UPDATE = "referenceHistogramE2E_update";
		private static final String LABEL_REMOVAL = "referenceHistogramE2E_removal";

		/**
		 * The {@link FixtureCtx} is identical for every OverlapFixture variant — the fixtures
		 * only differ in their seed data, not in the entity/reference/histogram triple under
		 * test.
		 */
		private static final FixtureCtx FIXTURE_CTX = new FixtureCtx(
			ENTITY_PRODUCT, REF_PARAM_VALUES, HISTOGRAM_MARKET_SHARE
		);

		/**
		 * Provisions a two-group fixture where `SHARED_PV` is referenced from products in
		 * BOTH group 1 and group 2 with different marketShare values per group — group 1
		 * pins the low value, group 2 pins the high value. Also places additional distinct
		 * PVs in each group so the histogram actually has buckets.
		 */
		static void runWithOverlapFixture(@Nonnull FixtureTest test) {
			runFixture(
				LABEL_OVERLAP, OverlapFixture::seedOverlap,
				evita -> evita.queryCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) session -> test.run(session, FIXTURE_CTX)
				)
			);
		}

		/**
		 * Provisions a two-PV single-group fixture for exercising the `attributeUpdate`
		 * RGEI re-keying path: two referenced PVs (100 and 200) in group 1 with
		 * `marketShare` values 50.0 and 80.0. The test callback receives the full
		 * {@link Evita} instance so it may freely open write sessions and verify boundary
		 * resolution both before and after the mutation.
		 */
		static void runWithUpdateFixture(@Nonnull MutationFixtureTest test) {
			runFixture(
				LABEL_UPDATE, OverlapFixture::seedUpdate,
				evita -> test.run(evita, FIXTURE_CTX)
			);
		}

		/**
		 * Provisions a two-PV single-group fixture for exercising the
		 * `removeAllAttributes` RGEI re-keying path: two referenced PVs (100 and 200)
		 * in group 1 with `marketShare` values 30.0 (the initial min) and 80.0. The test
		 * callback receives the full {@link Evita} so it may remove references and
		 * re-query to verify the boundary no longer returns the removed PV.
		 */
		static void runWithRemovalFixture(@Nonnull MutationFixtureTest test) {
			runFixture(
				LABEL_REMOVAL, OverlapFixture::seedRemoval,
				evita -> test.run(evita, FIXTURE_CTX)
			);
		}

		/**
		 * Provisions a fixture where group 1 contains two referenced PKs with the SAME
		 * min marketShare — forces the facet-sorter tie-breaker into active duty.
		 */
		static void runWithTieFixture(@Nonnull FixtureTest test) {
			runFixture(
				LABEL_TIE, OverlapFixture::seedTie,
				evita -> evita.queryCatalog(
					TEST_CATALOG,
					(Consumer<EvitaSessionContract>) session -> test.run(session, FIXTURE_CTX)
				)
			);
		}

		/**
		 * Scaffolds an OverlapFixture variant: cleans a pair of per-fixture directories,
		 * spins up a fresh {@link Evita} bound to them, installs the shared overlap schema,
		 * applies the variant-specific seed, calls `goLiveAndClose`, and hands the running
		 * instance to the caller's body. Both directories are cleaned again on exit so
		 * variants do not leak disk state into siblings.
		 */
		private static void runFixture(
			@Nonnull String label,
			@Nonnull Consumer<EvitaSessionContract> seed,
			@Nonnull Consumer<Evita> body
		) {
			final OverlapFixture fixture = new OverlapFixture();
			final TestPaths paths = fixture.createTestPaths(label);
			try (
				Evita evita = new Evita(fixture.getEvitaConfiguration(paths))
			) {
				evita.defineCatalog(TEST_CATALOG);
				evita.updateCatalog(
					TEST_CATALOG, session -> {
						defineSchema(session);
						seed.accept(session);
						session.goLiveAndClose();
					}
				);
				body.accept(evita);
			} finally {
				fixture.cleanupTestPaths(paths);
			}
		}

		private static void defineSchema(@Nonnull EvitaSessionContract session) {
			session.defineEntitySchema(ENTITY_PARAMETER)
				.withAttribute(ATTR_NAME, String.class, whichIs -> whichIs.filterable().nullable())
				.updateVia(session);

			session.defineEntitySchema(ENTITY_PARAMETER_VALUE)
				.withAttribute(ATTR_NAME, String.class, whichIs -> whichIs.filterable().nullable())
				.updateVia(session);

			session.defineEntitySchema(ENTITY_PRODUCT)
				.withReferenceToEntity(
					REF_PARAM_VALUES, ENTITY_PARAMETER_VALUE, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs
						.indexedForFilteringAndPartitioning()
						.indexedWithComponents(ReferenceIndexedComponents.values())
						.faceted()
						.withGroupTypeRelatedToEntity(ENTITY_PARAMETER)
						.withAttribute(
							ATTR_MARKET_SHARE, BigDecimal.class,
							thatIs -> thatIs.filterable().indexDecimalPlaces(2)
						)
						.bucketed(
							HISTOGRAM_MARKET_SHARE,
							ExpressionFactory.parse(
								"$reference.attributes['" + ATTR_MARKET_SHARE + "']"
							)
						)
				)
				.updateVia(session);
		}

		/**
		 * Seeds two groups (1 and 2) that share {@link #SHARED_PV} with disjoint
		 * marketShare values. Group 1: SHARED_PV@10 (min), extra PV 501@50, extra PV
		 * 502@80 (max). Group 2: extra PV 510@20 (min), extra PV 511@60, SHARED_PV@90
		 * (max).
		 */
		private static void seedOverlap(@Nonnull EvitaSessionContract session) {
			session.createNewEntity(ENTITY_PARAMETER, 1)
				.setAttribute(ATTR_NAME, "group-1").upsertVia(session);
			session.createNewEntity(ENTITY_PARAMETER, 2)
				.setAttribute(ATTR_NAME, "group-2").upsertVia(session);

			session.createNewEntity(ENTITY_PARAMETER_VALUE, SHARED_PV)
				.setAttribute(ATTR_NAME, "shared").upsertVia(session);
			session.createNewEntity(ENTITY_PARAMETER_VALUE, 501)
				.setAttribute(ATTR_NAME, "pv-501").upsertVia(session);
			session.createNewEntity(ENTITY_PARAMETER_VALUE, 502)
				.setAttribute(ATTR_NAME, "pv-502").upsertVia(session);
			session.createNewEntity(ENTITY_PARAMETER_VALUE, 510)
				.setAttribute(ATTR_NAME, "pv-510").upsertVia(session);
			session.createNewEntity(ENTITY_PARAMETER_VALUE, 511)
				.setAttribute(ATTR_NAME, "pv-511").upsertVia(session);

			// product 1: group 1 — SHARED_PV@10 (min), 501@50, 502@80 (max)
			session.createNewEntity(ENTITY_PRODUCT, 1)
				.setReference(
					REF_PARAM_VALUES, SHARED_PV, whichIs ->
						whichIs.setGroup(ENTITY_PARAMETER, 1)
							.setAttribute(ATTR_MARKET_SHARE, new BigDecimal("10.00"))
				)
				.setReference(
					REF_PARAM_VALUES, 501, whichIs ->
						whichIs.setGroup(ENTITY_PARAMETER, 1)
							.setAttribute(ATTR_MARKET_SHARE, new BigDecimal("50.00"))
				)
				.setReference(
					REF_PARAM_VALUES, 502, whichIs ->
						whichIs.setGroup(ENTITY_PARAMETER, 1)
							.setAttribute(ATTR_MARKET_SHARE, new BigDecimal("80.00"))
				)
				.upsertVia(session);

			// product 2: group 2 — 510@20 (min), 511@60, SHARED_PV@90 (max)
			session.createNewEntity(ENTITY_PRODUCT, 2)
				.setReference(
					REF_PARAM_VALUES, 510, whichIs ->
						whichIs.setGroup(ENTITY_PARAMETER, 2)
							.setAttribute(ATTR_MARKET_SHARE, new BigDecimal("20.00"))
				)
				.setReference(
					REF_PARAM_VALUES, 511, whichIs ->
						whichIs.setGroup(ENTITY_PARAMETER, 2)
							.setAttribute(ATTR_MARKET_SHARE, new BigDecimal("60.00"))
				)
				.setReference(
					REF_PARAM_VALUES, SHARED_PV, whichIs ->
						whichIs.setGroup(ENTITY_PARAMETER, 2)
							.setAttribute(ATTR_MARKET_SHARE, new BigDecimal("90.00"))
				)
				.upsertVia(session);
		}

		/**
		 * Seeds a single-group fixture where two distinct referenced PKs carry identical
		 * min marketShare — forces the facet-sorter tie-breaker path. {@link #TIE_LOW_PV}
		 * and {@link #TIE_HIGH_PV} are both at 10.00 in group 1; additional PVs at 50.00
		 * and 80.00 make the histogram have three buckets so min/max distinguishing is
		 * meaningful.
		 */
		private static void seedTie(@Nonnull EvitaSessionContract session) {
			session.createNewEntity(ENTITY_PARAMETER, 1)
				.setAttribute(ATTR_NAME, "group-1").upsertVia(session);
			session.createNewEntity(ENTITY_PARAMETER_VALUE, TIE_LOW_PV)
				.setAttribute(ATTR_NAME, "tie-low").upsertVia(session);
			session.createNewEntity(ENTITY_PARAMETER_VALUE, TIE_HIGH_PV)
				.setAttribute(ATTR_NAME, "tie-high").upsertVia(session);
			session.createNewEntity(ENTITY_PARAMETER_VALUE, 300)
				.setAttribute(ATTR_NAME, "mid").upsertVia(session);
			session.createNewEntity(ENTITY_PARAMETER_VALUE, 400)
				.setAttribute(ATTR_NAME, "max").upsertVia(session);

			session.createNewEntity(ENTITY_PRODUCT, 1)
				.setReference(
					REF_PARAM_VALUES, TIE_LOW_PV, whichIs ->
						whichIs.setGroup(ENTITY_PARAMETER, 1)
							.setAttribute(ATTR_MARKET_SHARE, new BigDecimal("10.00"))
				)
				.setReference(
					REF_PARAM_VALUES, TIE_HIGH_PV, whichIs ->
						whichIs.setGroup(ENTITY_PARAMETER, 1)
							.setAttribute(ATTR_MARKET_SHARE, new BigDecimal("10.00"))
				)
				.setReference(
					REF_PARAM_VALUES, 300, whichIs ->
						whichIs.setGroup(ENTITY_PARAMETER, 1)
							.setAttribute(ATTR_MARKET_SHARE, new BigDecimal("50.00"))
				)
				.setReference(
					REF_PARAM_VALUES, 400, whichIs ->
						whichIs.setGroup(ENTITY_PARAMETER, 1)
							.setAttribute(ATTR_MARKET_SHARE, new BigDecimal("80.00"))
				)
				.upsertVia(session);
		}

		/**
		 * Seeds a single group with two referenced PVs that will be mutated by the test.
		 * PV 100 starts at marketShare 50.0 (lower bucket), PV 200 at 80.0 (upper bucket).
		 */
		private static void seedUpdate(@Nonnull EvitaSessionContract session) {
			session.createNewEntity(ENTITY_PARAMETER, 1)
				.setAttribute(ATTR_NAME, "group-1").upsertVia(session);
			session.createNewEntity(ENTITY_PARAMETER_VALUE, 100)
				.setAttribute(ATTR_NAME, "pv-100").upsertVia(session);
			session.createNewEntity(ENTITY_PARAMETER_VALUE, 200)
				.setAttribute(ATTR_NAME, "pv-200").upsertVia(session);

			session.createNewEntity(ENTITY_PRODUCT, 1)
				.setReference(
					REF_PARAM_VALUES, 100, whichIs ->
						whichIs.setGroup(ENTITY_PARAMETER, 1)
							.setAttribute(ATTR_MARKET_SHARE, new BigDecimal("50.00"))
				)
				.setReference(
					REF_PARAM_VALUES, 200, whichIs ->
						whichIs.setGroup(ENTITY_PARAMETER, 1)
							.setAttribute(ATTR_MARKET_SHARE, new BigDecimal("80.00"))
				)
				.upsertVia(session);
		}

		/**
		 * Seeds a single group with two referenced PVs for reference-removal tests.
		 * PV 100 starts at marketShare 30.0 (the initial min), PV 200 at 80.0.
		 */
		private static void seedRemoval(@Nonnull EvitaSessionContract session) {
			session.createNewEntity(ENTITY_PARAMETER, 1)
				.setAttribute(ATTR_NAME, "group-1").upsertVia(session);
			session.createNewEntity(ENTITY_PARAMETER_VALUE, 100)
				.setAttribute(ATTR_NAME, "pv-100").upsertVia(session);
			session.createNewEntity(ENTITY_PARAMETER_VALUE, 200)
				.setAttribute(ATTR_NAME, "pv-200").upsertVia(session);

			session.createNewEntity(ENTITY_PRODUCT, 1)
				.setReference(
					REF_PARAM_VALUES, 100, whichIs ->
						whichIs.setGroup(ENTITY_PARAMETER, 1)
							.setAttribute(ATTR_MARKET_SHARE, new BigDecimal("30.00"))
				)
				.setReference(
					REF_PARAM_VALUES, 200, whichIs ->
						whichIs.setGroup(ENTITY_PARAMETER, 1)
							.setAttribute(ATTR_MARKET_SHARE, new BigDecimal("80.00"))
				)
				.upsertVia(session);
		}

		@Nonnull
		private EvitaConfiguration getEvitaConfiguration(@Nonnull TestPaths paths) {
			return newTestEvitaConfigurationBuilder(paths)
				.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
				.build();
		}

		@FunctionalInterface
		interface FixtureTest {
			void run(@Nonnull EvitaSessionContract session, @Nonnull FixtureCtx ctx);
		}

		/**
		 * Variant of {@link FixtureTest} that receives the entire {@link Evita} instance
		 * so the test can open its own write sessions — required for scenarios that
		 * assert state changes across a mutation.
		 */
		@FunctionalInterface
		interface MutationFixtureTest {
			void run(@Nonnull Evita evita, @Nonnull FixtureCtx ctx);
		}

		record FixtureCtx(
			@Nonnull String entityProduct,
			@Nonnull String refName,
			@Nonnull String histogramName
		) {
		}
	}
}
