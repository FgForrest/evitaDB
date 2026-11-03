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

package io.evitadb.store.catalog;

import io.evitadb.api.query.Query;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.store.catalog.ReleaseFixtureHarness.pks;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.upgrade.Release2026_2FixtureRecipes.PRODUCT;
import static io.evitadb.test.upgrade.Release2026_2FixtureRecipes.BRAND;
import static io.evitadb.test.upgrade.Release2026_2FixtureRecipes.inRange;
import static io.evitadb.test.upgrade.Release2026_2FixtureRecipes.equalsInEnglish;
import static io.evitadb.test.upgrade.Release2026_2FixtureRecipes.orderedInEnglish;
import static io.evitadb.test.upgrade.ReleaseFixtureValues.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies queries on release-written indexes whose surviving values suffice for a lossless upgrade.
 * Refusals and potentially lossy shapes are covered by the migration preflight and report tests.
 */
@DisplayName("Release indexes retain their values and answer correctly after upgrade")
@Tag(STORAGE)
@Tag(ATTRIBUTE)
class Release2026_2IndexUpgradeTest implements EvitaTestSupport {
	/** Probes the longer fractional interval retained in the released reference buckets. */
	@Nonnull
	private static final OffsetDateTime PLUS_2_500 = BASE.plusSeconds(2).plusNanos(500_000_000L);

	/** Copies release bytes and compares repaired indexes with current writes. */
	@Nonnull
	private final ReleaseFixtureHarness harness = new ReleaseFixtureHarness(this);

	/** Verifies a sort-only SORT holding both twins (blocks of 2 and 3) loads and orders all five. */
	@Test
	@DisplayName("a sort-only SORT holding both twins (blocks of 2 and 3) loads and orders all five")
	void shouldOrderAllRecordsWhenSortHoldsBothTwins() {
		this.harness.assertOnFreshThenUpgraded("d1b", evita -> assertEquals(
			List.of(1, 2, 3, 4, 5),
			pks(evita, "d1b", query(collection(PRODUCT), orderBy(attributeNatural("sortValidity"))))
		));
	}

	/** Verifies a cardinality-1 twin ordered before its cardinality-3 twin loads and orders all four. */
	@Test
	@DisplayName("a cardinality-1 twin ordered before its cardinality-3 twin loads and orders all four")
	void shouldOrderAllRecordsWhenShorterTwinComesFirst() {
		this.harness.assertOnFreshThenUpgraded("d1b2", evita -> assertEquals(
			List.of(1, 2, 3, 4),
			pks(evita, "d1b2", query(collection(PRODUCT), orderBy(attributeNatural("sortValidity"))))
		));
	}

	/** Verifies inline: B=[00.100,03.100] sorts before A=[00.900,02.900]. */
	@Test
	@DisplayName("inline: B=[00.100,03.100] sorts before A=[00.900,02.900]")
	void shouldOrderInlineSortByMillisecondBounds() {
		this.harness.assertOnFreshThenUpgraded("d3", evita -> assertEquals(
			List.of(3, 2, 1),
			pks(evita, "d3", query(collection(PRODUCT), orderBy(attributeNatural("sortValidity"))))
		));
	}

	/** Verifies ordered by the tie-break before the code. */
	@Test
	@DisplayName("ordered by the tie-break before the code")
	void shouldOrderNonTemporalLocalizedCompoundByTieBreak() {
		this.harness.assertOnFreshThenUpgraded(
			"d4c", evita -> assertEquals(List.of(3, 2, 1), pks(evita, "d4c", orderedInEnglish("labelCode")))
		);
	}

	/** Verifies collator ties across page boundaries are ordered by the tie-break. */
	@Test
	@DisplayName("collator ties across page boundaries are ordered by the tie-break")
	void shouldOrderPagedLocalizedCompoundByTieBreak() {
		final AtomicReference<List<Integer>> fresh = new AtomicReference<>();
		this.harness.withFreshCatalog(
			"d4cp", evita -> fresh.set(pks(evita, "d4cp", orderedInEnglish("labelCode")))
		);
		assertEquals(321, fresh.get().get(0));
		assertEquals(2, fresh.get().get(1));
		this.harness.withUpgradedCatalog(
			"d4cp", evita -> assertEquals(fresh.get(), pks(evita, "d4cp", orderedInEnglish("labelCode")))
		);
	}

	/** Verifies a paged globally unique tree whose release order inverts loads and finds both values. */
	@Test
	@DisplayName("a paged globally unique tree whose release order inverts loads and finds both values")
	void shouldFindBothValuesWhenPagedGlobalUniqueTreeInverts() {
		this.harness.assertOnFreshThenUpgraded("d9", evita -> {
			assertEquals(
				List.of(299),
				pks(evita, "d9", query(collection(PRODUCT), filterBy(attributeEquals("period", A))))
			);
			assertEquals(
				List.of(300),
				pks(evita, "d9", query(collection(PRODUCT), filterBy(attributeEquals("period", B))))
			);
		});
	}

	/** Verifies a paged owner unique tree whose release order inverts loads and finds both values. */
	@Test
	@DisplayName("a paged owner unique tree whose release order inverts loads and finds both values")
	void shouldFindBothValuesWhenPagedOwnerUniqueTreeInverts() {
		this.harness.assertOnFreshThenUpgraded("d9o", evita -> {
			assertEquals(List.of(299), pks(evita, "d9o", equalsInEnglish("period", A)));
			assertEquals(List.of(300), pks(evita, "d9o", equalsInEnglish("period", B)));
		});
	}

	/** Verifies a paged 2026.1 range FILTER loads and answers a point query as a fresh engine. */
	@Test
	@DisplayName("a paged 2026.1 range FILTER loads and answers a point query as a fresh engine")
	void shouldAnswerPointQueryWhenPagedRangeFilterCameFromRelease2026_1() {
		final AtomicReference<List<Integer>> fresh = new AtomicReference<>();
		final Query query = inRange("range", new BigDecimal("1.5"));
		this.harness.withFreshCatalog(
			"d13", evita -> fresh.set(pks(evita, "d13", query))
		);
		this.harness.withUpgradedCatalog(
			"d13", evita -> assertEquals(fresh.get(), pks(evita, "d13", query))
		);
	}

	/** Verifies that two owners' ranges sharing a start are both counted in the referenced-type index. */
	@Test
	@DisplayName("two owners' ranges sharing a start are both counted in the referenced-type index")
	void shouldFindLongerRangeWhenTwoOwnersRangesShareStart() {
		this.harness.withUpgradedCatalog("dRng", evita -> {
			assertEquals(
				List.of(2),
				pks(
					evita, "dRng",
					query(collection(PRODUCT), filterBy(referenceHaving(BRAND, attributeInRange("span", 25))))
				)
			);
			assertEquals(
				List.of(2),
				pks(
					evita, "dRng",
					query(
						collection(PRODUCT),
						filterBy(referenceHaving(BRAND, attributeInRange("validity", PLUS_2_500)))
					)
				)
			);
		});
	}

}
