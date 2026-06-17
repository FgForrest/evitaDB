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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.attributeInRange;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.FILTER;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end coverage for `attributeInRange` over a `BigDecimalNumberRange` attribute declared with
 * `indexDecimalPlaces > 0` where each stored range is supplied at its **intrinsic** scale — i.e. via the
 * `between(from, to)` factory **without** an explicit decimal-places argument, so a range built from
 * scale-1 bounds keeps `retainedDecimalPlaces == 1` even though the schema indexes at scale 2.
 *
 * The probe value of `attributeInRange` is always coerced to the schema scale, so for the query to be
 * correct the stored range's `RangeIndex` thresholds must also be derived at the schema scale rather than
 * at the value's intrinsic scale. These tests cover the add, update/delta, remove and array-typed range
 * paths plus a mix of differently-scaled ranges under one schema scale.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("attributeInRange — scale-mismatched BigDecimalNumberRange")
@Tag(ENGINE)
@Tag(FILTER)
@Tag(ATTRIBUTE)
public class AttributeInScaleMismatchedBigDecimalRangeFunctionalTest implements EvitaTestSupport {

	private static final String ENTITY_PRODUCT = "product";
	private static final String ATTR_RANGE = "validRange";
	private static final int INDEX_DECIMAL_PLACES = 2;

	/**
	 * Installs the single-entity schema whose `validRange` attribute is a `BigDecimalNumberRange` indexed
	 * at {@link #INDEX_DECIMAL_PLACES}.
	 */
	private static void defineSchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withAttribute(
				ATTR_RANGE, BigDecimalNumberRange.class,
				whichIs -> whichIs.filterable().indexDecimalPlaces(INDEX_DECIMAL_PLACES).nullable()
			)
			.updateVia(session);
	}

	/**
	 * Installs the single-entity schema whose `validRange` attribute is a `BigDecimalNumberRange[]` array
	 * indexed at {@link #INDEX_DECIMAL_PLACES}.
	 */
	private static void defineArraySchema(@Nonnull EvitaSessionContract session) {
		session.defineEntitySchema(ENTITY_PRODUCT)
			.withAttribute(
				ATTR_RANGE, BigDecimalNumberRange[].class,
				whichIs -> whichIs.filterable().indexDecimalPlaces(INDEX_DECIMAL_PLACES).nullable()
			)
			.updateVia(session);
	}

	/**
	 * Convenience factory for a `BigDecimal` literal whose intrinsic scale is taken verbatim from `value`.
	 */
	@Nonnull
	private static BigDecimal bd(@Nonnull String value) {
		return new BigDecimal(value);
	}

	/**
	 * Builds a `BigDecimalNumberRange` from the given bounds **without** specifying decimal places, so the
	 * range keeps the bounds' intrinsic scale — the exact non-canonical form that exercises the bug.
	 */
	@Nonnull
	private static BigDecimalNumberRange intrinsicRange(@Nonnull String from, @Nonnull String to) {
		return BigDecimalNumberRange.between(bd(from), bd(to));
	}

	/**
	 * Runs `attributeInRange(validRange, probe)` against the catalog and returns the matched primary keys
	 * as a sorted set so assertions are order-independent.
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
	 * Builds an ordered set of the supplied primary keys for comparison against query results.
	 */
	@Nonnull
	private static Set<Integer> setOf(@Nonnull Integer... pks) {
		return new LinkedHashSet<>(Arrays.asList(pks));
	}

	/**
	 * Builds the standard test configuration with a disabled session inactivity timeout and per-test
	 * storage / export directories.
	 */
	@Nonnull
	private EvitaConfiguration createConfiguration(@Nonnull TestPaths paths) {
		return newTestEvitaConfigurationBuilder(paths)
			.server(ServerOptions.builder().closeSessionsAfterSecondsOfInactivity(-1).build())
			.build();
	}

	/**
	 * Spins up a fresh Evita instance bound to per-test directories, applies the supplied seeding logic in
	 * a writable catalog, hands an open read session to the caller's assertions, and tears the instance
	 * down afterwards.
	 */
	private void runWithCatalog(
		@Nonnull String label,
		@Nonnull Consumer<EvitaSessionContract> seeding,
		@Nonnull Consumer<EvitaSessionContract> assertions
	) {
		final TestPaths paths = createTestPaths(label);
		try (
			Evita evita = new Evita(createConfiguration(paths))
		) {
			evita.defineCatalog(TEST_CATALOG);
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					seeding.accept(session);
					session.goLiveAndClose();
				}
			);
			evita.queryCatalog(TEST_CATALOG, assertions);
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/**
	 * Variant of {@link #runWithCatalog(String, Consumer, Consumer)} that exposes the live `Evita`
	 * instance to the body so that follow-up write transactions (updates / removals) can run between the
	 * initial seeding and the final assertions.
	 */
	private void runWithLiveCatalog(
		@Nonnull String label,
		@Nonnull Consumer<EvitaSessionContract> seeding,
		@Nonnull BiConsumer<Evita, EvitaSessionContract> body
	) {
		final TestPaths paths = createTestPaths(label);
		try (
			Evita evita = new Evita(createConfiguration(paths))
		) {
			evita.defineCatalog(TEST_CATALOG);
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					seeding.accept(session);
					session.goLiveAndClose();
				}
			);
			evita.queryCatalog(
				TEST_CATALOG,
				(Consumer<EvitaSessionContract>) session -> body.accept(evita, session)
			);
		} finally {
			cleanupTestPaths(paths);
		}
	}

	@Nested
	@DisplayName("add + query")
	class AddAndQuery {

		@Test
		@DisplayName("should match an intrinsic-scale range whose schema scale is higher")
		@Tag(ENGINE)
		@Tag(FILTER)
		@Tag(ATTRIBUTE)
		void shouldMatchIntrinsicScaleRange() {
			runWithCatalog(
				"attributeInScaleMismatchedRange-add",
				session -> {
					defineSchema(session);
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(ATTR_RANGE, intrinsicRange("1.5", "2.5"))
						.upsertVia(session);
				},
				session -> {
					// strictly inside [1.5, 2.5]
					assertEquals(setOf(1), matchedPrimaryKeys(session, bd("2.0")));
					// inclusive lower bound
					assertEquals(setOf(1), matchedPrimaryKeys(session, bd("1.5")));
					// inclusive upper bound
					assertEquals(setOf(1), matchedPrimaryKeys(session, bd("2.5")));
					// below the range
					assertEquals(setOf(), matchedPrimaryKeys(session, bd("1.0")));
					// above the range
					assertEquals(setOf(), matchedPrimaryKeys(session, bd("3.0")));
					// over-scaled probe rounding half-up to 2.50 lands on the inclusive upper bound
					assertEquals(setOf(1), matchedPrimaryKeys(session, bd("2.499")));
				}
			);
		}
	}

	@Nested
	@DisplayName("update / delta")
	class UpdateDelta {

		@Test
		@DisplayName("should drop the old range and match the new one after a scale-mismatched update")
		@Tag(ENGINE)
		@Tag(FILTER)
		@Tag(ATTRIBUTE)
		void shouldReplaceRangeOnUpdate() {
			runWithLiveCatalog(
				"attributeInScaleMismatchedRange-update",
				session -> {
					defineSchema(session);
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(ATTR_RANGE, intrinsicRange("1.5", "2.5"))
						.upsertVia(session);
				},
				(evita, session) -> {
					// initial range matches as expected
					assertEquals(setOf(1), matchedPrimaryKeys(session, bd("2.0")));

					// update the range to a different scale-mismatched range; the remove/add symmetry must
					// not raise a "Sanity check - record not found!" failure
					evita.updateCatalog(
						TEST_CATALOG,
						(Consumer<EvitaSessionContract>) writeSession -> writeSession
							.getEntity(ENTITY_PRODUCT, 1, entityFetchAllContent())
							.orElseThrow()
							.openForWrite()
							.setAttribute(ATTR_RANGE, intrinsicRange("3.5", "4.5"))
							.upsertVia(writeSession)
					);

					evita.queryCatalog(
						TEST_CATALOG,
						(Consumer<EvitaSessionContract>) readSession -> {
							// the old range no longer matches
							assertEquals(setOf(), matchedPrimaryKeys(readSession, bd("2.0")));
							// the new range matches across its span and boundaries
							assertEquals(setOf(1), matchedPrimaryKeys(readSession, bd("4.0")));
							assertEquals(setOf(1), matchedPrimaryKeys(readSession, bd("3.5")));
							assertEquals(setOf(1), matchedPrimaryKeys(readSession, bd("4.5")));
						}
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("remove")
	class Remove {

		@Test
		@DisplayName("should cleanly remove a scale-mismatched range with no match afterwards")
		@Tag(ENGINE)
		@Tag(FILTER)
		@Tag(ATTRIBUTE)
		void shouldRemoveRangeCleanly() {
			runWithLiveCatalog(
				"attributeInScaleMismatchedRange-remove",
				session -> {
					defineSchema(session);
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(ATTR_RANGE, intrinsicRange("1.5", "2.5"))
						.upsertVia(session);
				},
				(evita, session) -> {
					assertEquals(setOf(1), matchedPrimaryKeys(session, bd("2.0")));

					// remove the entity entirely; the range removal must not raise a sanity-check failure
					evita.updateCatalog(
						TEST_CATALOG,
						(Consumer<EvitaSessionContract>) writeSession ->
							writeSession.deleteEntity(ENTITY_PRODUCT, 1)
					);

					evita.queryCatalog(
						TEST_CATALOG,
						(Consumer<EvitaSessionContract>) readSession ->
							assertEquals(setOf(), matchedPrimaryKeys(readSession, bd("2.0")))
					);
				}
			);
		}
	}

	@Nested
	@DisplayName("array-typed range")
	class ArrayRange {

		@Test
		@DisplayName("should match membership across multiple intrinsic-scale ranges in an array")
		@Tag(ENGINE)
		@Tag(FILTER)
		@Tag(ATTRIBUTE)
		void shouldMatchAcrossArrayRanges() {
			runWithCatalog(
				"attributeInScaleMismatchedRange-array",
				session -> {
					defineArraySchema(session);
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(
							ATTR_RANGE,
							new BigDecimalNumberRange[] {
								intrinsicRange("1.5", "2.5"),
								intrinsicRange("5.5", "6.5")
							}
						)
						.upsertVia(session);
				},
				session -> {
					// inside the first array range
					assertEquals(setOf(1), matchedPrimaryKeys(session, bd("2.0")));
					// inside the second array range
					assertEquals(setOf(1), matchedPrimaryKeys(session, bd("6.0")));
					// in the gap between the two ranges
					assertEquals(setOf(), matchedPrimaryKeys(session, bd("4.0")));
					// inclusive boundaries of both ranges
					assertEquals(setOf(1), matchedPrimaryKeys(session, bd("1.5")));
					assertEquals(setOf(1), matchedPrimaryKeys(session, bd("6.5")));
				}
			);
		}
	}

	@Nested
	@DisplayName("mixed scales under one schema scale")
	class MixedScales {

		@Test
		@DisplayName("should filter ranges stored at different intrinsic scales with the same probe scale")
		@Tag(ENGINE)
		@Tag(FILTER)
		@Tag(ATTRIBUTE)
		void shouldFilterMixedScaleRanges() {
			runWithCatalog(
				"attributeInScaleMismatchedRange-mixed",
				session -> {
					defineSchema(session);
					// stored at intrinsic scale 1
					session.createNewEntity(ENTITY_PRODUCT, 1)
						.setAttribute(ATTR_RANGE, intrinsicRange("1.5", "2.5"))
						.upsertVia(session);
					// stored at intrinsic scale 2
					session.createNewEntity(ENTITY_PRODUCT, 2)
						.setAttribute(ATTR_RANGE, intrinsicRange("3.00", "4.00"))
						.upsertVia(session);
				},
				session -> {
					// inside the scale-1 range only
					assertEquals(setOf(1), matchedPrimaryKeys(session, bd("2.0")));
					// inside the scale-2 range only
					assertEquals(setOf(2), matchedPrimaryKeys(session, bd("3.5")));
					// inclusive boundary of the scale-2 range
					assertEquals(setOf(2), matchedPrimaryKeys(session, bd("3.00")));
					assertEquals(setOf(2), matchedPrimaryKeys(session, bd("4.00")));
					// in the gap between the two ranges
					assertEquals(setOf(), matchedPrimaryKeys(session, bd("2.75")));
				}
			);
		}
	}

}
