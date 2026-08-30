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

package io.evitadb.api.functional.attribute;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.query.require.QueryTelemetryContent;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry;
import io.evitadb.api.requestResponse.extraResult.QueryTelemetry.QueryPhase;
import io.evitadb.api.requestResponse.data.EntityEditor.EntityBuilder;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor.EntitySchemaBuilder;
import io.evitadb.api.requestResponse.schema.FilterIndexCapability;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.index.trigram.TrigramSubstringSearch;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeContains;
import static io.evitadb.api.query.QueryConstraints.attributeEndsWith;
import static io.evitadb.api.query.QueryConstraints.attributeStartsWith;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.debug;
import static io.evitadb.api.query.QueryConstraints.entityLocaleEquals;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.queryTelemetry;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof that declaring {@link FilterIndexCapability#SUBSTRING} on an attribute changes only HOW FAST
 * `attributeContains` and `attributeEndsWith` are answered, never WHAT they answer.
 *
 * Every case runs the identical corpus and the identical query twice - once against a collection whose attribute
 * declares the capability (and therefore has a trigram index behind it) and once against one that does not (and is
 * therefore answered by the bucket scan that predates it) - and asserts the two agree. That comparison is what makes
 * the test a parity test rather than a restatement of whatever the accelerated path happens to produce.
 *
 * Every corpus here is deliberately sized past the whole gate - not merely past
 * {@link io.evitadb.index.trigram.TrigramSubstringSearch#MINIMAL_ACCELERATED_DISTINCT_VALUE_COUNT} but past the
 * selectivity ratio the searched bound is priced against - because below either the accelerated collection declines
 * and quietly compares the scan against itself. Since the two paths agree by construction, nothing here would fail
 * if that happened; `shouldStayAboveTheGate` is what turns it from a silent pass into a loud one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Attribute substring filtering — trigram index parity")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(FILTER)
@Tag(ATTRIBUTE)
public class AttributeSubstringIndexFunctionalTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_BRAND = "brand";
	private static final String ATTR_NAME = "name";

	/**
	 * The reference the partitioned variant carves the products by, so a `referenceHaving` filter offers the planner
	 * a reduced-index plan beside the global one.
	 */
	private static final String REFERENCE_BRAND = "brand";

	/**
	 * Products carrying the widely-planted `zebra` pattern.
	 */
	private static final int ZEBRA_PRODUCTS = 30;

	/**
	 * Filler products, sharing no trigram with any searched pattern. Their count is what carries the corpus past the
	 * gate: past the floor below which the accelerated path declines outright, and past the selectivity ratio the
	 * `zebra` bound is priced against. Never below 400, so the counts the cases below assert stay what they were,
	 * and never below the threshold, so a retune of
	 * {@link TrigramSubstringSearch#CANDIDATE_SELECTIVITY_DIVISOR} cannot quietly drop the corpus under the gate.
	 */
	private static final int FILLER_PRODUCTS = Math.max(
		400, (int) TrigramSubstringSearch.accelerationThreshold(ZEBRA_PRODUCTS)
	);

	/**
	 * The size of displaced scan the queried brand's partition must reach before the gate admits it - derived, not
	 * written down, because {@link TrigramSubstringSearch#CANDIDATE_SELECTIVITY_DIVISOR} is a measured constant that
	 * is expected to move. A fixture sized against today's value would at a larger divisor stop clearing the gate,
	 * and because these cases have no acceleration observable of their own they would go on passing while quietly
	 * comparing the scan against itself. `shouldStayAboveTheGate` asserts that has not happened.
	 */
	private static final long PARTITIONED_GATE_THRESHOLD =
		TrigramSubstringSearch.accelerationThreshold(ZEBRA_PRODUCTS);

	/**
	 * Primary key of the brand the partitioned cases query. It owns every product up to
	 * {@link #BRAND_ONE_PRODUCT_PREFIX} plus half the `zebra` products, so its partition is large enough to be worth
	 * accelerating, guaranteed to contain matches, and guaranteed NOT to contain all of them - see {@link #brandOf}.
	 */
	private static final int BRAND_ONE = 1;

	/**
	 * Primary key of the brand owning everything {@link #BRAND_ONE} does not.
	 */
	private static final int BRAND_TWO = 2;

	/**
	 * Products with a primary key up to this value belong to {@link #BRAND_ONE} whatever their name; it is also the
	 * number of them that carry the sub-trigram `it` term, since every one of them is a filler. Sized at the gate
	 * threshold, which the half of the `zebra` products the brand also owns then clears with margin.
	 */
	private static final int BRAND_ONE_PRODUCT_PREFIX = (int) PARTITIONED_GATE_THRESHOLD;

	/**
	 * Filler products of the PARTITIONED variant, which has to satisfy two bounds at once: the queried partition must
	 * hold at least {@link #PARTITIONED_GATE_THRESHOLD} distinct values for the accelerated path to be taken at all,
	 * and at most HALF the collection's entities, or `IndexSelectionVisitor` marks the reduced plan
	 * `HIGH_CARDINALITY` and the planner never builds it. Three times the brand's prefix leaves room for both, and
	 * both are asserted in `shouldStayAboveTheGate` rather than trusted.
	 */
	private static final int PARTITIONED_FILLER_PRODUCTS = 3 * BRAND_ONE_PRODUCT_PREFIX;

	/**
	 * Precomposed (NFC) `café` — the form a user types, and the form this corpus stores.
	 */
	private static final String NFC_CAFE = "café";

	/**
	 * Decomposed (NFD) `café` — the form the shared value tree normalizes it to.
	 */
	private static final String NFD_CAFE = Normalizer.normalize(NFC_CAFE, Normalizer.Form.NFD);

	/**
	 * Locale of the localized variant. French maps to a real non-default `Collator`, so the localized filter index
	 * orders its buckets by collation rather than by code point - the branch under which the scan cannot early-break
	 * and the reverse lookup has to resolve through the value id directory instead of through key order.
	 */
	private static final Locale SEARCH_LOCALE = Locale.FRENCH;

	/**
	 * Builds the corpus every case searches, in one deterministic order; the product primary key is the position plus
	 * one.
	 *
	 * @return the product names
	 */
	@Nonnull
	private static List<String> corpus() {
		return corpus(FILLER_PRODUCTS);
	}

	/**
	 * @param fillerProducts how many filler values to plant before the searched ones
	 * @return the product names
	 */
	@Nonnull
	private static List<String> corpus(int fillerProducts) {
		final List<String> names = new ArrayList<>(fillerProducts + ZEBRA_PRODUCTS + 8);
		for (int i = 0; i < fillerProducts; i++) {
			names.add(String.format("item-%04d", i));
		}
		for (int i = 0; i < ZEBRA_PRODUCTS; i++) {
			names.add(String.format("widget zebra %03d", i));
		}
		names.add("omega");
		names.add("tail omega");
		names.add("omega leads here");
		names.add("xxabcdxx");
		names.add("bcd then abc");
		names.add(NFC_CAFE + " noir");
		names.add("decaf latte");
		return names;
	}

	/**
	 * @param paths the per-test storage and export directories
	 * @return the standard test configuration with the session inactivity timeout disabled
	 */
	@Nonnull
	private EvitaConfiguration createConfiguration(@Nonnull TestPaths paths) {
		return newTestEvitaConfigurationBuilder(paths)
			.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
			.build();
	}

	/**
	 * Installs the schema and seeds the corpus.
	 *
	 * @param session     the writable session
	 * @param accelerated whether the attribute declares the SUBSTRING capability
	 * @param localized   whether the attribute is localized, and the values written under {@link #SEARCH_LOCALE}
	 */
	private static void seed(@Nonnull EvitaSessionContract session, boolean accelerated, boolean localized) {
		seed(session, accelerated, localized, false);
	}

	/**
	 * Installs the schema and seeds the corpus, optionally carving the products by {@link #REFERENCE_BRAND} so a
	 * `referenceHaving` filter gives the planner a reduced-index plan to consider.
	 *
	 * @param session     the writable session
	 * @param accelerated whether the attribute declares the SUBSTRING capability
	 * @param localized   whether the attribute is localized, and the values written under {@link #SEARCH_LOCALE}
	 * @param partitioned whether the products carry a partitioning reference, and the larger corpus is used
	 */
	private static void seed(
		@Nonnull EvitaSessionContract session, boolean accelerated, boolean localized, boolean partitioned
	) {
		if (partitioned) {
			session.defineEntitySchema(ENTITY_BRAND).updateVia(session);
			session.createNewEntity(ENTITY_BRAND, BRAND_ONE).upsertVia(session);
			session.createNewEntity(ENTITY_BRAND, BRAND_TWO).upsertVia(session);
		}
		final EntitySchemaBuilder productSchema = session.defineEntitySchema(ENTITY_PRODUCT)
			.withAttribute(
				ATTR_NAME, String.class,
				whichIs -> {
					if (localized) {
						whichIs.localized();
					}
					if (accelerated) {
						whichIs.filterable(FilterIndexCapability.SUBSTRING);
					} else {
						whichIs.filterable();
					}
				}
			);
		if (partitioned) {
			// `indexedForFilteringAndPartitioningInScope`, NOT `indexedInScope` - the latter yields
			// ReferenceIndexType.FOR_FILTERING, under which no reduced index is built for the entity-level attribute
			// and `IndexSelectionVisitor` marks the reduced plan NOT_PARTITIONED_INDEX so the planner never builds
			// it. The distinction is invisible in the answers, which is exactly why it went unnoticed until a
			// counterfactual that only alters the reduced branch failed to redden this test
			productSchema.withReferenceToEntity(
				REFERENCE_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_ONE,
				whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(Scope.LIVE)
			);
		}
		productSchema.updateVia(session);

		final List<String> names = corpus(partitioned ? PARTITIONED_FILLER_PRODUCTS : FILLER_PRODUCTS);
		for (int i = 0; i < names.size(); i++) {
			final int primaryKey = i + 1;
			final EntityBuilder entity = session.createNewEntity(ENTITY_PRODUCT, primaryKey);
			if (localized) {
				entity.setAttribute(ATTR_NAME, SEARCH_LOCALE, names.get(i));
			} else {
				entity.setAttribute(ATTR_NAME, names.get(i));
			}
			if (partitioned) {
				entity.setReference(REFERENCE_BRAND, brandOf(primaryKey, names.get(i)));
			}
			entity.upsertVia(session);
		}
	}

	/**
	 * Decides which brand a product belongs to. {@link #BRAND_ONE} takes the first
	 * {@link #BRAND_ONE_PRODUCT_PREFIX} products, which is what makes its partition large enough to be worth
	 * accelerating, plus HALF the `zebra` products.
	 *
	 * Half rather than all, deliberately. Were the queried brand to own every match, the reduced-index plan's answer
	 * and the collection-wide one would coincide, and a composition that ignored the partition entirely - returning
	 * the global answer unrestricted - would agree with the scan and with every alternative plan. Splitting the
	 * matches is what makes the intersection observable at all.
	 *
	 * @param primaryKey  the product's primary key
	 * @param productName the product's name
	 * @return the brand's primary key
	 */
	private static int brandOf(int primaryKey, @Nonnull String productName) {
		if (primaryKey <= BRAND_ONE_PRODUCT_PREFIX) {
			return BRAND_ONE;
		}
		return productName.contains("zebra") && primaryKey % 2 == 0 ? BRAND_ONE : BRAND_TWO;
	}

	/**
	 * @param session   an open read session
	 * @param filter    the filter to run
	 * @param localized whether the query must scope itself to {@link #SEARCH_LOCALE}
	 * @return the matched primary keys, sorted so the comparison is order-independent
	 */
	@Nonnull
	private static Set<Integer> matchedPrimaryKeys(
		@Nonnull EvitaSessionContract session, @Nonnull FilterConstraint filter, boolean localized
	) {
		final EvitaResponse<EntityReference> result = session.query(
			query(
				collection(ENTITY_PRODUCT),
				localized ? filterBy(entityLocaleEquals(SEARCH_LOCALE), filter) : filterBy(filter),
				require(page(1, Integer.MAX_VALUE))
			),
			EntityReference.class
		);
		final Set<Integer> primaryKeys = new TreeSet<>();
		for (final EntityReference reference : result.getRecordData()) {
			primaryKeys.add(reference.getPrimaryKey());
		}
		return primaryKeys;
	}

	/**
	 * Spins up two independent catalogs over the same corpus - one with the accelerator, one without - and hands both
	 * read sessions to `comparison`, in that order.
	 *
	 * @param label      the per-test directory label
	 * @param localized  whether the attribute is localized
	 * @param comparison receives the accelerated session and the scanning session
	 */
	private void runAgainstBothCatalogs(
		@Nonnull String label,
		boolean localized,
		@Nonnull BiConsumer<EvitaSessionContract, EvitaSessionContract> comparison
	) {
		runAgainstBothCatalogs(label, localized, false, comparison);
	}

	/**
	 * Spins up two independent catalogs over the same corpus - one with the accelerator, one without - and hands both
	 * read sessions to `comparison`, in that order.
	 *
	 * @param label       the per-test directory label
	 * @param localized   whether the attribute is localized
	 * @param partitioned whether the products carry a partitioning reference, and the larger corpus is used
	 * @param comparison  receives the accelerated session and the scanning session
	 */
	private void runAgainstBothCatalogs(
		@Nonnull String label,
		boolean localized,
		boolean partitioned,
		@Nonnull BiConsumer<EvitaSessionContract, EvitaSessionContract> comparison
	) {
		final TestPaths acceleratedPaths = createTestPaths(label + "-accelerated");
		final TestPaths scanningPaths = createTestPaths(label + "-scanning");
		try (
			Evita accelerated = new Evita(createConfiguration(acceleratedPaths));
			Evita scanning = new Evita(createConfiguration(scanningPaths))
		) {
			accelerated.defineCatalog(TEST_CATALOG);
			accelerated.updateCatalog(TEST_CATALOG, session -> {
				seed(session, true, localized, partitioned);
				session.goLiveAndClose();
			});
			scanning.defineCatalog(TEST_CATALOG);
			scanning.updateCatalog(TEST_CATALOG, session -> {
				seed(session, false, localized, partitioned);
				session.goLiveAndClose();
			});
			// the two lambdas are declared rather than inlined: `queryCatalog` is overloaded on Consumer and Function,
			// and a nested lambda leaves the compiler unable to tell which overload the outer one targets
			final Consumer<EvitaSessionContract> withBothSessions = acceleratedSession -> {
				final Consumer<EvitaSessionContract> withScanningSession =
					scanningSession -> comparison.accept(acceleratedSession, scanningSession);
				scanning.queryCatalog(TEST_CATALOG, withScanningSession);
			};
			accelerated.queryCatalog(TEST_CATALOG, withBothSessions);
		} finally {
			cleanupTestPaths(acceleratedPaths);
			cleanupTestPaths(scanningPaths);
		}
	}

	/**
	 * Asserts both catalogs answer `filter` identically, and that the answer is not empty - an empty answer would make
	 * the comparison hold for the wrong reason.
	 *
	 * @param accelerated the session over the capability-declaring collection
	 * @param scanning    the session over the plain collection
	 * @param filter      the filter to compare
	 * @param localized   whether the query must scope itself to {@link #SEARCH_LOCALE}
	 * @param expectedSize how many products the filter must match
	 */
	private static void assertSameAnswer(
		@Nonnull EvitaSessionContract accelerated,
		@Nonnull EvitaSessionContract scanning,
		@Nonnull FilterConstraint filter,
		boolean localized,
		int expectedSize
	) {
		final Set<Integer> scanned = matchedPrimaryKeys(scanning, filter, localized);
		assertEquals(expectedSize, scanned.size(), "the scan's own answer to " + filter + " is not what was planted");
		assertEquals(scanned, matchedPrimaryKeys(accelerated, filter, localized), "answers diverge for " + filter);
	}

	/**
	 * The number of products the queried brand owns in the partitioned variant: every product up to the prefix, plus
	 * the even-keyed half of the `zebra` products.
	 *
	 * @return the size of the queried partition
	 */
	private static int brandOneSize() {
		final List<String> names = corpus(PARTITIONED_FILLER_PRODUCTS);
		int size = 0;
		for (int i = 0; i < names.size(); i++) {
			if (brandOf(i + 1, names.get(i)) == BRAND_ONE) {
				size++;
			}
		}
		return size;
	}

	@Test
	@DisplayName("both corpora are still selective enough for the accelerated catalog to accelerate")
	void shouldStayAboveTheGate() {
		// EVERY case in this class compares an accelerated catalog against a scanning one, and the two agree by
		// construction - so a corpus that stopped clearing the gate would not fail anything here, it would quietly
		// compare the scan against itself and keep reporting success. `CANDIDATE_SELECTIVITY_DIVISOR` is a measured
		// constant expected to be retuned, which makes that a live hazard rather than a theoretical one. This case is
		// the only thing standing between a retune and a suite that passes while testing nothing.
		assertTrue(
			TrigramSubstringSearch.isWorthAccelerating(ZEBRA_PRODUCTS, corpus().size()),
			"the plain corpus of " + corpus().size() + " values no longer clears the gate for a `zebra` bound of "
				+ ZEBRA_PRODUCTS + " - raise FILLER_PRODUCTS until it does, or every parity case here is vacuous"
		);

		final int brandOne = brandOneSize();
		final int allProducts = corpus(PARTITIONED_FILLER_PRODUCTS).size();
		assertTrue(
			TrigramSubstringSearch.isWorthAccelerating(ZEBRA_PRODUCTS, brandOne),
			"the queried brand's " + brandOne + " values no longer clear the gate - the reduced-index case would "
				+ "silently fall back to the scan and prove nothing"
		);
		assertTrue(
			brandOne * 2 <= allProducts,
			"the queried brand holds " + brandOne + " of " + allProducts + " products; above half, "
				+ "IndexSelectionVisitor marks the reduced plan HIGH_CARDINALITY and the planner never builds it"
		);
	}

	@Test
	@DisplayName("contains answers identically with and without the substring index")
	void shouldAnswerContainsIdenticallyWithAndWithoutTheIndex() {
		runAgainstBothCatalogs(
			"substringIndex-contains", false,
			(accelerated, scanning) -> {
				assertSameAnswer(accelerated, scanning, attributeContains(ATTR_NAME, "zebra"), false, ZEBRA_PRODUCTS);
				assertSameAnswer(accelerated, scanning, attributeContains(ATTR_NAME, "omega"), false, 3);
				// `bcd then abc` carries both of `abcd`'s trigrams and none of its order - only exact verification
				// keeps it out of the answer
				assertSameAnswer(accelerated, scanning, attributeContains(ATTR_NAME, "abcd"), false, 1);
				// short of a trigram, so the accelerated collection falls back to the scan
				assertSameAnswer(
					accelerated, scanning, attributeContains(ATTR_NAME, "it"), false, FILLER_PRODUCTS);
				// carried by every filler value, so the selectivity gate declines it
				assertSameAnswer(
					accelerated, scanning, attributeContains(ATTR_NAME, "item"), false, FILLER_PRODUCTS);
				// nothing carries it at all
				assertEquals(
					Set.of(), matchedPrimaryKeys(accelerated, attributeContains(ATTR_NAME, "xyzzy"), false));
			}
		);
	}

	@Test
	@DisplayName("endsWith answers identically with and without the substring index")
	void shouldAnswerEndsWithIdenticallyWithAndWithoutTheIndex() {
		runAgainstBothCatalogs(
			"substringIndex-endsWith", false,
			(accelerated, scanning) -> {
				// two of the three `omega` products end with it, which is what tells the two constraints apart
				assertSameAnswer(accelerated, scanning, attributeEndsWith(ATTR_NAME, "omega"), false, 2);
				assertSameAnswer(accelerated, scanning, attributeEndsWith(ATTR_NAME, "abcdxx"), false, 1);
				assertSameAnswer(accelerated, scanning, attributeEndsWith(ATTR_NAME, "here"), false, 1);
				assertEquals(
					Set.of(), matchedPrimaryKeys(accelerated, attributeEndsWith(ATTR_NAME, "zebra"), false));
			}
		);
	}

	@Test
	@DisplayName("startsWith is untouched by the substring index")
	void shouldLeaveStartsWithUntouched() {
		// startsWith keeps its anchored walk; the declaration must not change its answer either
		runAgainstBothCatalogs(
			"substringIndex-startsWith", false,
			(accelerated, scanning) -> {
				assertSameAnswer(
					accelerated, scanning, attributeStartsWith(ATTR_NAME, "widget"), false, ZEBRA_PRODUCTS);
				assertSameAnswer(accelerated, scanning, attributeStartsWith(ATTR_NAME, "omega"), false, 2);
			}
		);
	}

	@Test
	@DisplayName("a precomposed term matches a decomposed value through the substring index")
	void shouldMatchAcrossUnicodeNormalizationForms() {
		runAgainstBothCatalogs(
			"substringIndex-unicode", false,
			(accelerated, scanning) -> {
				assertFalse(NFC_CAFE.equals(NFD_CAFE), "the two normalization forms must really differ");
				assertSameAnswer(accelerated, scanning, attributeContains(ATTR_NAME, NFC_CAFE), false, 1);
				assertSameAnswer(accelerated, scanning, attributeContains(ATTR_NAME, NFD_CAFE), false, 1);
				assertSameAnswer(accelerated, scanning, attributeEndsWith(ATTR_NAME, NFC_CAFE + " noir"), false, 1);
			}
		);
	}

	/**
	 * Runs `filter` scoped to {@link #BRAND_ONE}, which offers the planner a reduced-index plan beside the global one,
	 * with the engine's own cross-plan verification switched on.
	 *
	 * {@link DebugMode#VERIFY_ALTERNATIVE_INDEX_RESULTS} makes the planner execute EVERY eligible plan and assert
	 * they agree, so the reduced-index plan is exercised whether or not it wins on cost.
	 *
	 * ## What this proves, and what it deliberately does NOT
	 *
	 * PROVES, and it was measured rather than assumed: the real planner builds and executes a reduced-index plan
	 * here, and the trigram path is taken ON that plan. Replacing the accelerated reduced branch with a throwing
	 * probe fires it five times from this case; with the reference declared `indexedInScope` instead of
	 * `indexedForFilteringAndPartitioningInScope` the same probe fires zero times. That is the integration coverage
	 * the mocked unit suite cannot give: the real `computeOnlyOnce`, the real index-stream walk, the real fan-out.
	 *
	 * Does NOT prove the intersection itself. Deleting the `FormulaFactory.and` from `resolveFromIndex` leaves this
	 * case GREEN, because `ReferenceHavingTranslator` computes its own formula and ANDs the answer back down to the
	 * queried partition - re-imposing exactly the restriction the deletion removed. Only the two hierarchy
	 * translators short-circuit their representing constraint
	 * (`HierarchyWithinTranslator:286`, `HierarchyWithinRootTranslator:143`), and there the intersection is the sole
	 * restriction and its removal is a wrong answer. `ReducedIndexSubstringAccelerationTest`'s
	 * `shouldRestrictTheGlobalAnswerToTheTargetedPartitions` models that case and does redden. Making THIS case
	 * observe it would mean rebuilding the fixture around `hierarchyWithin`.
	 *
	 * @param session an open read session
	 * @param filter  the attribute filter to run beside the reference constraint
	 * @return the matched primary keys, sorted so the comparison is order-independent
	 */
	@Nonnull
	private static Set<Integer> matchedPrimaryKeysWithinBrandOne(
		@Nonnull EvitaSessionContract session, @Nonnull FilterConstraint filter
	) {
		final EvitaResponse<EntityReference> result = session.query(
			query(
				collection(ENTITY_PRODUCT),
				filterBy(
					referenceHaving(REFERENCE_BRAND, entityPrimaryKeyInSet(BRAND_ONE)),
					filter
				),
				require(
					page(1, Integer.MAX_VALUE),
					debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS),
					queryTelemetry(QueryTelemetryContent.PLAN)
				)
			),
			EntityReference.class
		);
		assertReducedIndexPlanWasConsidered(result);
		final Set<Integer> primaryKeys = new TreeSet<>();
		for (final EntityReference reference : result.getRecordData()) {
			primaryKeys.add(reference.getPrimaryKey());
		}
		return primaryKeys;
	}

	/**
	 * Fails unless the planner actually offered a REDUCED-index candidate for this query.
	 *
	 * This is the structural half of the case, and it exists because the rest of it cannot see the difference. Every
	 * assertion here compares an accelerated catalog against a scanning one, and those agree whatever index the
	 * planner chose - so a fixture that quietly stopped producing a reduced-index plan would keep passing while
	 * testing only the global one. That is not hypothetical: declaring the reference `indexedInScope` instead of
	 * `indexedForFilteringAndPartitioningInScope` does exactly that, and it went unnoticed through six green runs
	 * until a counterfactual that only alters the reduced branch failed to redden anything.
	 *
	 * ## Why asserting the PLAN is not a weaker check than asserting execution
	 *
	 * Under {@link DebugMode#VERIFY_ALTERNATIVE_INDEX_RESULTS} the two are the same claim, and the chain is worth
	 * spelling out so nobody later reads this as a soft check and "strengthens" it by adding engine code:
	 *
	 * - `QueryPlanner:359` builds the candidate's formula, and `:366`-`:372` add its `QueryPlanBuilder` to the
	 *   result list immediately, with no branch in between that could build one without adding the other;
	 * - `:383` records the plan onto the telemetry step exactly when that formula is non-null, which is what makes
	 *   a non-null plan equivalent to "a builder exists for this candidate";
	 * - `:401` returns the FULL list under this debug mode rather than `subList(0, 1)`;
	 * - `:154` makes that list `queryPlanBuilders`, `:172` hands it to `verifyConsistentResultsInAllPlans`, and
	 *   `:757` executes every builder in it and asserts the answers agree.
	 *
	 * So a recorded plan on a `REFERENCED_ENTITY` alternative entails that the reduced-index plan was executed and
	 * its answer compared against the global one. The only gap is an exception thrown between `:359` and `:366`,
	 * which fails the query outright and so cannot produce a false green.
	 *
	 * Matching the step's PROSE would not do: `QueryPlanner:349` opens the step BEFORE testing
	 * `isEligibleForSeparateQueryPlan()`, so an ineligible candidate is recorded too, description and all. That
	 * version of this assertion was written first and stayed green with the schema reverted.
	 *
	 * @param response the response of a query that requested {@link QueryTelemetry}
	 */
	private static void assertReducedIndexPlanWasConsidered(@Nonnull EvitaResponse<EntityReference> response) {
		final QueryTelemetry telemetry = response.getExtraResult(QueryTelemetry.class);
		assertNotNull(telemetry, "query telemetry was requested and must be present - it is the observable here");
		final List<QueryTelemetry> alternatives = new ArrayList<>(4);
		collectFilterAlternatives(telemetry, alternatives);
		assertFalse(
			alternatives.isEmpty(),
			"the planner recorded no filter alternatives at all, so this assertion is checking nothing"
		);
		// a REDUCED candidate is RECORDED even when it is not eligible - `QueryPlanner` opens the step before
		// testing eligibility - so naming it is not enough. `recordPlan` runs only when a formula was actually
		// built for the candidate, which makes a non-null plan the structural signal that it was eligible AND
		// planned. Matching on the step's prose alone would pass on an ineligible candidate, which is exactly the
		// bug this assertion exists to catch
		final boolean reducedCandidatePlanned = alternatives.stream()
			.filter(it -> String.join(" ", it.getArguments()).contains(EntityIndexType.REFERENCED_ENTITY.name()))
			.anyMatch(it -> it.getPlan() != null);
		assertTrue(
			reducedCandidatePlanned,
			"no REDUCED-index candidate was both eligible and planned for this query, so it exercises the global "
				+ "index only and proves nothing about the reduced-index path. Recorded alternatives were: "
				+ alternatives.stream().map(it -> String.join(" ", it.getArguments())).toList()
		);
	}

	/**
	 * Depth-first collector of every {@link QueryPhase#PLANNING_FILTER_ALTERNATIVE} step's arguments.
	 *
	 * @param step   the telemetry node being visited
	 * @param result the accumulator, one step per candidate index combination
	 */
	private static void collectFilterAlternatives(@Nonnull QueryTelemetry step, @Nonnull List<QueryTelemetry> result) {
		if (step.getOperation() == QueryPhase.PLANNING_FILTER_ALTERNATIVE) {
			result.add(step);
		}
		for (final QueryTelemetry nested : step.getSteps()) {
			collectFilterAlternatives(nested, result);
		}
	}

	@Test
	@DisplayName("a reduced-index plan is served by the global accelerator and answers identically")
	void shouldAnswerIdenticallyOnAReducedIndexPlan() {
		runAgainstBothCatalogs(
			"substringIndex-reduced", false, true,
			(accelerated, scanning) -> {
				final Set<Integer> scanned = matchedPrimaryKeysWithinBrandOne(
					scanning, attributeContains(ATTR_NAME, "zebra")
				);
				// half the `zebra` products, by construction - the other half belongs to the other brand, which is what
				// makes an unrestricted global answer distinguishable from the correctly intersected one
				assertEquals(
					ZEBRA_PRODUCTS / 2, scanned.size(),
					"the queried brand must hold SOME but not ALL of the matches, or the intersection is invisible"
				);
				assertEquals(
					scanned,
					matchedPrimaryKeysWithinBrandOne(accelerated, attributeContains(ATTR_NAME, "zebra")),
					"the reduced-index plan and the scan disagree"
				);
				// the same query with a term short of a trigram, where even the accelerated catalog scans - the two
				// paths must still meet
				final Set<Integer> shortTermScanned = matchedPrimaryKeysWithinBrandOne(
					scanning, attributeContains(ATTR_NAME, "it")
				);
				assertEquals(
					BRAND_ONE_PRODUCT_PREFIX, shortTermScanned.size(),
					"the first " + BRAND_ONE_PRODUCT_PREFIX + " products are all fillers of the queried brand"
				);
				assertEquals(
					shortTermScanned,
					matchedPrimaryKeysWithinBrandOne(accelerated, attributeContains(ATTR_NAME, "it")),
					"a term below the trigram length must be answered identically on a reduced-index plan too"
				);
			}
		);
	}

	@Test
	@DisplayName("a collation-ordered localized attribute answers identically too")
	void shouldAnswerIdenticallyUnderACollationComparator() {
		runAgainstBothCatalogs(
			"substringIndex-localized", true,
			(accelerated, scanning) -> {
				assertSameAnswer(accelerated, scanning, attributeContains(ATTR_NAME, "zebra"), true, ZEBRA_PRODUCTS);
				assertSameAnswer(accelerated, scanning, attributeEndsWith(ATTR_NAME, "omega"), true, 2);
				assertSameAnswer(accelerated, scanning, attributeContains(ATTR_NAME, NFC_CAFE), true, 1);
			}
		);
	}

}
