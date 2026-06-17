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
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.dataType.BigDecimalNumberRange;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeInRange;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FILTER;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end coverage for the direct `attributeInRange` filter over a `BigDecimalNumberRange`
 * attribute declared with `indexDecimalPlaces > 0`, where each stored range is canonicalized to
 * that schema scale — the exact form the new range branch of `FilterIndex#getNormalizer`
 * (`rescaleBigDecimalRange`) produces.
 *
 * Filtering `[1.50, 2.50]` under `indexDecimalPlaces(2)` is only correct when both the stored
 * range bounds and the probe value resolve to the same order-preserving scaled integer
 * (`150` / `250`). The boundary probes below — `2.5` matching the inclusive upper bound of
 * `[1.5, 2.5]`, `2.0` matching the shared endpoint of two overlapping ranges — exercise both the
 * range-index lookup and the `isWithin` prefetch fallback, guarding that range overlap filtering
 * agrees with the histogram bucket scale once the value is at the schema scale.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("attributeInRange — BigDecimalNumberRange canonicalized to indexDecimalPlaces")
@Tag(ENGINE)
@Tag(FILTER)
@Tag(ATTRIBUTE)
public class AttributeInBigDecimalRangeFunctionalTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ATTR_RANGE = "validRange";
	private static final int INDEX_DECIMAL_PLACES = 2;

	/**
	 * Installs a single-entity schema whose `validRange` attribute is a `BigDecimalNumberRange`
	 * indexed at {@link #INDEX_DECIMAL_PLACES}, then seeds four products with overlapping ranges
	 * whose bounds all carry an intrinsic scale of 1.
	 */
	private static void defineAndSeed(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withAttribute(
				ATTR_RANGE, BigDecimalNumberRange.class,
				whichIs -> whichIs.filterable().indexDecimalPlaces(INDEX_DECIMAL_PLACES).nullable()
			)
			.updateVia(session);

		// The seeded bounds carry an intrinsic scale of 1, but each range is canonicalized to the
		// schema's `indexDecimalPlaces` via the `between(from, to, places)` form — the exact shape
		// `FilterIndex#rescaleBigDecimalRange` (the new range branch of `getNormalizer`) produces.
		// Indexing a range at the schema scale is what makes both the range-index lookup AND the
		// `BigDecimalNumberRange#isWithin` prefetch fallback agree with a probe coerced at the same
		// scale; a range left at its intrinsic scale would mismatch the probe by a power of ten.
		createProduct(session, 1, decimalRange("1.5", "2.5"));
		createProduct(session, 2, decimalRange("2.0", "3.0"));
		createProduct(session, 3, decimalRange("3.5", "4.5"));
		createProduct(session, 4, decimalRange("5.0", "6.0"));
	}

	/**
	 * Creates a single product entity carrying the supplied `validRange`.
	 */
	private static void createProduct(
		@Nonnull EvitaSessionContract session,
		int pk,
		@Nonnull BigDecimalNumberRange range
	) {
		session.createNewEntity(ENTITY_PRODUCT, pk)
			.setAttribute(ATTR_RANGE, range)
			.upsertVia(session);
	}

	/**
	 * Convenience factory for a scale-1 `BigDecimal` literal.
	 */
	@Nonnull
	private static BigDecimal bd(@Nonnull String value) {
		return new BigDecimal(value);
	}

	/**
	 * Builds a `BigDecimalNumberRange` from intrinsic-scale bounds, canonicalized to the
	 * schema's {@link #INDEX_DECIMAL_PLACES} — i.e. the form a complete normalizer pass emits
	 * for the source filter index.
	 */
	@Nonnull
	private static BigDecimalNumberRange decimalRange(@Nonnull String from, @Nonnull String to) {
		return BigDecimalNumberRange.between(bd(from), bd(to), INDEX_DECIMAL_PLACES);
	}

	/**
	 * Runs `attributeInRange(validRange, probe)` against the catalog and returns the matched
	 * primary keys as a sorted set so assertions are order-independent.
	 */
	@Nonnull
	private static Set<Integer> matchedPrimaryKeys(
		@Nonnull EvitaSessionContract session,
		@Nonnull BigDecimal probe
	) {
		final EvitaResponse<EntityReference> result = session.query(
			query(
				collection(ENTITY_PRODUCT),
				filterBy(
					attributeInRange(ATTR_RANGE, probe)
				),
				require(
					page(1, Integer.MAX_VALUE)
				)
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
	 * Builds the standard test configuration with a disabled session inactivity timeout and
	 * per-test storage / export directories.
	 */
	@Nonnull
	private EvitaConfiguration createConfiguration(@Nonnull TestPaths paths) {
		return newTestEvitaConfigurationBuilder(paths)
			.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
			.build();
	}

	/**
	 * Spins up a fresh Evita instance bound to per-test directories, installs and seeds the
	 * decimal-range schema, hands the open read session to the caller's assertions, and tears
	 * the instance down afterwards.
	 */
	private void runWithSeededCatalog(@Nonnull Consumer<EvitaSessionContract> assertions) {
		final TestPaths paths = createTestPaths("attributeInBigDecimalRange");
		try (
			Evita evita = new Evita(createConfiguration(paths))
		) {
			evita.defineCatalog(TEST_CATALOG);
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					defineAndSeed(session);
					session.goLiveAndClose();
				}
			);
			evita.queryCatalog(TEST_CATALOG, assertions);
		} finally {
			cleanupTestPaths(paths);
		}
	}

	@Test
	@DisplayName("should match schema-scale BigDecimal ranges including inclusive boundaries")
	void shouldMatchSchemaScaleDecimalRanges() {
		runWithSeededCatalog(
			session -> {
				// probe strictly inside the first range only
				assertEquals(setOf(1), matchedPrimaryKeys(session, bd("1.75")));

				// shared endpoint 2.0 — inclusive lower bound of range 2 AND interior of range 1
				assertEquals(setOf(1, 2), matchedPrimaryKeys(session, bd("2.0")));

				// inclusive upper bound 2.5 of range 1 — also interior of range 2; the upper bound is
				// indexed as 250 at the schema scale, so a probe coerced to 250 lands on the boundary
				assertEquals(setOf(1, 2), matchedPrimaryKeys(session, bd("2.5")));

				// inclusive upper bound 3.0 of range 2 only
				assertEquals(setOf(2), matchedPrimaryKeys(session, bd("3.0")));

				// gap between range 2 (ends 3.0) and range 3 (starts 3.5)
				assertEquals(setOf(), matchedPrimaryKeys(session, bd("3.25")));

				// interior of range 3
				assertEquals(setOf(3), matchedPrimaryKeys(session, bd("4.0")));

				// inclusive lower bound 5.0 and interior of the fourth, disjoint range
				assertEquals(setOf(4), matchedPrimaryKeys(session, bd("5.0")));
				assertEquals(setOf(4), matchedPrimaryKeys(session, bd("5.75")));
				// inclusive upper bound 6.0 of the fourth range
				assertEquals(setOf(4), matchedPrimaryKeys(session, bd("6.0")));
				// beyond every seeded range
				assertEquals(setOf(), matchedPrimaryKeys(session, bd("9.99")));
			}
		);
	}

	/**
	 * Builds an ordered set of the supplied primary keys for comparison against query results.
	 */
	@Nonnull
	private static Set<Integer> setOf(@Nonnull Integer... pks) {
		return new LinkedHashSet<>(Arrays.asList(pks));
	}

}
