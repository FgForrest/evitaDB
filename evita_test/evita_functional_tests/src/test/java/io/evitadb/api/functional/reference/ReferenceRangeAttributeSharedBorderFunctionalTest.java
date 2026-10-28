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

package io.evitadb.api.functional.reference;

import io.evitadb.api.CatalogState;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.IntegerNumberRange;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.FILTER;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Pins `attributeInRange` on a range-typed reference attribute when several owners reference the SAME target with
 * ranges that share a border.
 *
 * The referenced type index answers which targets (reduced indexes) can hold a match. It keys its filter index by the
 * reduced index primary key, so every owner of one target contributes its range to one and the same record id, and two
 * owners' ranges may share a start or an end there. The range index behind the filter index must keep such a border
 * once per range: kept once, the target would be dropped as a candidate although one of its owners matches (shared
 * start), or kept although none does (shared end). `referenceHaving` re-examines every candidate row, so only a lost
 * candidate would reach its result; `facetHaving` takes the candidate set as the answer, so both would.
 *
 * ## The fixture
 *
 * Products reference brands through `brand` (`ZERO_OR_MORE`, filterable, faceted), whose rows carry `validity`
 * (`DateTimeRange`) and `span` (`IntegerNumberRange`), both filterable. Each scenario creates brand 1 and 2 and the
 * products it lists; every product references brand 1. Every scenario is run in `WARMING_UP` and in `ALIVE`.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Range reference attribute of several owners sharing a border within one target")
@Tag(CONTRACT)
@Tag(REFERENCE)
@Tag(FILTER)
@Tag(ATTRIBUTE)
class ReferenceRangeAttributeSharedBorderFunctionalTest implements EvitaTestSupport {
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_BRAND = "brand";
	private static final String REF_BRAND = "brand";
	private static final String ATTR_VALIDITY = "validity";
	private static final String ATTR_SPAN = "span";
	private static final int BRAND = 1;
	private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
	private static final OffsetDateTime T1 = OffsetDateTime.of(2026, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC);
	private static final OffsetDateTime T2 = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, ZoneOffset.UTC);
	/**
	 * A moment strictly between {@link #T2} and {@link #T3}.
	 */
	private static final OffsetDateTime T2_5 = OffsetDateTime.of(2026, 3, 15, 0, 0, 0, 0, ZoneOffset.UTC);
	private static final OffsetDateTime T3 = OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC);
	private static final OffsetDateTime T4 = OffsetDateTime.of(2026, 5, 1, 0, 0, 0, 0, ZoneOffset.UTC);
	private static final OffsetDateTime T5 = OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);
	private static final int[] NONE = {};

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ReferenceRangeAttributeSharedBorderFunctionalTest");
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("referenceHaving finds the owner whose DateTimeRange outlasts another one starting at the same moment")
	void shouldFindOwnerWhoseDateTimeRangeOutlastsAnotherWhenBothShareStart(CatalogState state) {
		prepare(state, ATTR_VALIDITY, DateTimeRange.between(T0, T2), DateTimeRange.between(T0, T3));

		assertArrayEquals(
			new int[]{2},
			queryProducts(referenceHaving(REF_BRAND, attributeInRange(ATTR_VALIDITY, T2_5))),
			"product 2 references brand 1 with [T0,T3], which covers T2.5"
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("control: referenceHaving finds the same owner when the two DateTimeRanges start at different moments")
	void shouldFindOwnerWhoseDateTimeRangeOutlastsAnotherWhenStartsDiffer(CatalogState state) {
		prepare(state, ATTR_VALIDITY, DateTimeRange.between(T0, T2), DateTimeRange.between(T0.plusDays(1), T3));

		assertArrayEquals(
			new int[]{2},
			queryProducts(referenceHaving(REF_BRAND, attributeInRange(ATTR_VALIDITY, T2_5)))
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("referenceHaving finds the owner whose IntegerNumberRange outlasts another one starting at the same value")
	void shouldFindOwnerWhoseIntegerRangeOutlastsAnotherWhenBothShareStart(CatalogState state) {
		prepare(state, ATTR_SPAN, IntegerNumberRange.between(0, 20), IntegerNumberRange.between(0, 30));

		assertArrayEquals(
			new int[]{2},
			queryProducts(referenceHaving(REF_BRAND, attributeInRange(ATTR_SPAN, 25))),
			"product 2 references brand 1 with [0,30], which covers 25"
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("referenceHaving finds the owner whose `until` validity outlasts another owner's `until` validity")
	void shouldFindOwnerWhoseUntilValidityOutlastsAnotherUntilValidity(CatalogState state) {
		prepare(state, ATTR_VALIDITY, DateTimeRange.until(T2), DateTimeRange.until(T3));

		assertArrayEquals(
			new int[]{2},
			queryProducts(referenceHaving(REF_BRAND, attributeInRange(ATTR_VALIDITY, T2_5))),
			"product 2 references brand 1 with until(T3), which covers T2.5"
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("referenceHaving keeps finding an owner after another owner's validity sharing its start moves away")
	void shouldKeepFindingOwnerAfterAnotherOwnersValiditySharingItsStartChanges(CatalogState state) {
		prepare(state, ATTR_VALIDITY, DateTimeRange.between(T0, T3), DateTimeRange.between(T0, T2));
		assertArrayEquals(
			new int[]{1, 2},
			queryProducts(referenceHaving(REF_BRAND, attributeInRange(ATTR_VALIDITY, T1))),
			"precondition: both validities cover T1"
		);

		this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> session
				.getEntity(ENTITY_PRODUCT, 2, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.setReference(
					REF_BRAND, BRAND,
					whichIs -> whichIs.setAttribute(ATTR_VALIDITY, DateTimeRange.between(T4, T5))
				)
				.upsertVia(session)
		);

		assertArrayEquals(
			new int[]{1},
			queryProducts(referenceHaving(REF_BRAND, attributeInRange(ATTR_VALIDITY, T1))),
			"product 1 still references brand 1 with [T0,T3], which covers T1"
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("facetHaving selects the brand one of whose rows covers the moment when two rows share a start")
	void shouldSelectFacetWhenOneOfTwoValiditiesSharingStartCoversTheMoment(CatalogState state) {
		prepare(state, ATTR_VALIDITY, DateTimeRange.between(T0, T2), DateTimeRange.between(T0, T3));

		assertArrayEquals(
			new int[]{1, 2},
			queryProducts(userFilter(facetHaving(REF_BRAND, attributeInRange(ATTR_VALIDITY, T2_5)))),
			"brand 1 is referenced with [T0,T3], which covers T2.5 - every product of brand 1 is in the facet"
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("facetHaving selects no brand when both rows sharing an end are over")
	void shouldSelectNoFacetWhenBothValiditiesSharingEndAreOver(CatalogState state) {
		prepare(state, ATTR_VALIDITY, DateTimeRange.between(T0, T3), DateTimeRange.between(T1, T3));

		assertArrayEquals(
			NONE,
			queryProducts(userFilter(facetHaving(REF_BRAND, attributeInRange(ATTR_VALIDITY, T4)))),
			"neither [T0,T3] nor [T1,T3] covers T4"
		);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("control: referenceHaving finds no owner when both rows sharing an end are over")
	void shouldFindNoOwnerWhenBothValiditiesSharingEndAreOver(CatalogState state) {
		prepare(state, ATTR_VALIDITY, DateTimeRange.between(T0, T3), DateTimeRange.between(T1, T3));

		assertArrayEquals(
			NONE,
			queryProducts(referenceHaving(REF_BRAND, attributeInRange(ATTR_VALIDITY, T4)))
		);
	}

	/**
	 * Once the second owner leaves, brand 1 has a single owner, and a single-owner partition is answered from the
	 * referenced type index without re-examining its row. A shared end lost while the second owner's range was removed
	 * would leave the remaining range open-ended there, and the query past the end would return its owner.
	 */
	@ParameterizedTest(name = "{0}")
	@EnumSource(value = CatalogState.class, names = {"WARMING_UP", "ALIVE"})
	@DisplayName("referenceHaving finds no owner past a shared end after the other owner sharing it left the target")
	void shouldFindNoOwnerPastSharedEndAfterTheOtherOwnerLeaves(CatalogState state) {
		prepare(state, ATTR_VALIDITY, DateTimeRange.between(T0, T3), DateTimeRange.between(T1, T3));

		this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> session
				.getEntity(ENTITY_PRODUCT, 2, entityFetchAllContent())
				.orElseThrow()
				.openForWrite()
				.removeReference(REF_BRAND, BRAND)
				.upsertVia(session)
		);

		assertArrayEquals(
			new int[]{1},
			queryProducts(referenceHaving(REF_BRAND, attributeInRange(ATTR_VALIDITY, T2))),
			"precondition: product 1 still references brand 1 with [T0,T3], which covers T2"
		);
		assertArrayEquals(
			NONE,
			queryProducts(referenceHaving(REF_BRAND, attributeInRange(ATTR_VALIDITY, T4))),
			"product 1's [T0,T3] is over at T4 - its end must survive the removal of product 2's [T1,T3]"
		);
	}

	/**
	 * Creates the schema described on the class, brands 1 and 2, and one product per passed value - product `i + 1`
	 * references brand 1 with `values[i]` in `attributeName`.
	 *
	 * @param state         target catalog state
	 * @param attributeName the reference attribute the values are written to
	 * @param values        the reference attribute values, one product each
	 */
	private void prepare(@Nonnull CatalogState state, @Nonnull String attributeName, @Nonnull Serializable... values) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> {
				session.defineEntitySchema(ENTITY_BRAND).withoutGeneratedPrimaryKey().updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(
						REF_BRAND, ENTITY_BRAND, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFiltering()
							.faceted()
							.withAttribute(ATTR_VALIDITY, DateTimeRange.class, thatIs -> thatIs.filterable().nullable())
							.withAttribute(
								ATTR_SPAN, IntegerNumberRange.class, thatIs -> thatIs.filterable().nullable()
							)
					)
					.updateVia(session);
				session.createNewEntity(ENTITY_BRAND, 1).upsertVia(session);
				session.createNewEntity(ENTITY_BRAND, 2).upsertVia(session);
				for (int i = 0; i < values.length; i++) {
					final Serializable value = values[i];
					session.createNewEntity(ENTITY_PRODUCT, i + 1)
						.setReference(REF_BRAND, BRAND, whichIs -> whichIs.setAttribute(attributeName, value))
						.upsertVia(session);
				}
				if (state == CatalogState.ALIVE) {
					session.goLiveAndClose();
				}
			}
		);
	}

	/**
	 * Returns the primary keys of the products matching the passed filter, in ascending order.
	 *
	 * @param filter the filter to apply
	 * @return matching product primary keys
	 */
	@Nonnull
	private int[] queryProducts(@Nonnull FilterConstraint filter) {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			(Function<EvitaSessionContract, int[]>) session -> session.query(
					query(
						collection(ENTITY_PRODUCT),
						filterBy(filter),
						require(page(1, 100))
					),
					EntityReference.class
				)
				.getRecordData()
				.stream()
				.mapToInt(EntityReference::getPrimaryKey)
				.sorted()
				.toArray()
		);
	}

	/**
	 * Builds the engine configuration over the test folder, with sessions that never expire.
	 *
	 * @return the configuration
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths)
			.server(
				ServerOptions.builder()
					.closeSessionsAfterSecondsOfInactivity(-1)
					.build()
			)
			.build();
	}
}
