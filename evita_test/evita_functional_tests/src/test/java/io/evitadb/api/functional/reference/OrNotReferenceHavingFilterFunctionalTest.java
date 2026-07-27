/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api.functional.reference;

import io.evitadb.api.query.require.DebugMode;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.annotation.UseDataSet;
import io.evitadb.test.extension.EvitaParameterResolver;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Functional tests for boolean logic involving `not(referenceHaving(...))` combined with its
 * positive counterpart inside `or` / `and` containers. Reproduces the regression reported in
 * issue [#1025](https://github.com/FgForrest/evitaDB/issues/1025), where
 * `or(referenceX_Having, not(referenceX_Having))` returned only a partial result instead of
 * the entire entity collection.
 *
 * The fix is in `FormulaCloner` (detecting dedup-collapsed positional siblings of
 * `NotFormula` / `DisentangleFormula` and collapsing the wrapper to `EmptyFormula`),
 * with defensive identity guards added in `NotFormula.computeInternal` and
 * `DisentangleFormula.computeInternal`. These tests guard that behaviour against
 * regressions for reference-based predicates (the original reproduction used
 * `referenceShippingMethodHaving`).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Filtering by or/and combined with not(referenceHaving)")
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
@Tag(CONTRACT)
@Tag(REFERENCE)
@Tag(FILTER)
public class OrNotReferenceHavingFilterFunctionalTest extends AbstractReferenceFilterFunctionalTest {

	/**
	 * The universe — `or(P, not(P))` must return every product in the collection regardless of
	 * whether it has the BRAND reference. The BRAND reference has cardinality `ZERO_OR_ONE`, so
	 * the dataset deliberately contains both branches of the partition.
	 */
	@DisplayName("Should return all products for or(referenceHaving, not(referenceHaving))")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnAllProductsForOrOfReferenceHavingAndItsNegation(
		Evita evita,
		List<SealedEntity> originalProducts
	) {
		final Set<Integer> withBrand = originalProducts.stream()
			.filter(it -> !it.getReferences(Entities.BRAND).isEmpty())
			.map(SealedEntity::getPrimaryKey)
			.collect(Collectors.toSet());
		final Set<Integer> withoutBrand = originalProducts.stream()
			.filter(it -> it.getReferences(Entities.BRAND).isEmpty())
			.map(SealedEntity::getPrimaryKey)
			.collect(Collectors.toSet());
		Assumptions.assumeFalse(
			withBrand.isEmpty() || withoutBrand.isEmpty(),
			"Test requires the BRAND reference partition to contain both branches"
		);

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							or(
								referenceHaving(Entities.BRAND),
								not(referenceHaving(Entities.BRAND))
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				assertEquals(
					originalProducts.size(), result.getRecordData().size(),
					"or(P, NOT(P)) must cover the entire collection"
				);
				final Set<Integer> returnedPks = result.getRecordData().stream()
					.map(EntityReference::getPrimaryKey)
					.collect(Collectors.toSet());
				final Set<Integer> expectedPks = originalProducts.stream()
					.map(SealedEntity::getPrimaryKey)
					.collect(Collectors.toSet());
				assertEquals(expectedPks, returnedPks, "Returned primary keys must equal the universe");
				return null;
			}
		);
	}

	/**
	 * Same `or(P, not(P))` shape as the universe test, but with an inner reference predicate
	 * (`entityPrimaryKeyInSet`). The two branches still form an exhaustive partition of the
	 * collection — every product either has a STORE reference matching `targetStorePk` or it
	 * does not — so the engine must return every product. Before the fix in this PR, only
	 * 83/100 were returned — the 17 missing products were exactly those that had **no** STORE
	 * reference at all, because `FormulaDeduplicator` collapsed the two structurally equivalent
	 * positional siblings of the inner `NotFormula(P, P)` into a single instance and the
	 * `FormulaCloner` mistakenly returned the surviving superset rather than `EmptyFormula`.
	 */
	@DisplayName("Should return all products for or(referenceHaving(filter), not(referenceHaving(filter)))")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnAllProductsForOrOfReferenceHavingWithInnerFilterAndItsNegation(
		Evita evita,
		List<SealedEntity> originalProducts,
		List<SealedEntity> originalStores
	) {
		final SealedEntity targetStore = originalStores.get(0);
		final int targetStorePk = targetStore.getPrimaryKey();

		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							or(
								referenceHaving(Entities.STORE, entityPrimaryKeyInSet(targetStorePk)),
								not(referenceHaving(Entities.STORE, entityPrimaryKeyInSet(targetStorePk)))
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				assertEquals(
					originalProducts.size(), result.getRecordData().size(),
					"or(referenceHaving(filter), NOT(referenceHaving(filter))) must cover the entire collection"
				);
				return null;
			}
		);
	}

	/**
	 * The empty set — `and(P, not(P))` must return no products. This is the dual of the
	 * `or(P, not(P))` guarantee for the conjunction path of `FutureNotFormula.postProcess`.
	 */
	@DisplayName("Should return no products for and(referenceHaving, not(referenceHaving))")
	@UseDataSet(HUNDRED_PRODUCTS)
	@Test
	void shouldReturnNoProductsForAndOfReferenceHavingAndItsNegation(Evita evita) {
		evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<EntityReference> result = session.query(
					query(
						collection(Entities.PRODUCT),
						filterBy(
							and(
								referenceHaving(Entities.BRAND),
								not(referenceHaving(Entities.BRAND))
							)
						),
						require(
							page(1, Integer.MAX_VALUE),
							debug(DebugMode.VERIFY_ALTERNATIVE_INDEX_RESULTS, DebugMode.VERIFY_POSSIBLE_CACHING_TREES)
						)
					),
					EntityReference.class
				);

				assertTrue(
					result.getRecordData().isEmpty(),
					"and(P, NOT(P)) must collapse to the empty set"
				);
				return null;
			}
		);
	}

}
