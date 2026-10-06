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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.core.Evita;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.store.catalog.model.CatalogBootstrap;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.EvitaTestSupport.TestPaths;
import io.evitadb.test.upgrade.Release2026_1FixtureRecipes;
import io.evitadb.test.upgrade.Release2026_2FixtureRecipes;
import io.evitadb.test.upgrade.ReleaseFixtureOrigin;
import io.evitadb.test.upgrade.ReleaseFixtureRecipe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.store.catalog.ReleaseFixtureHarness.pks;
import static io.evitadb.store.catalog.ReleaseFixtureHarness.probe;
import static io.evitadb.store.catalog.ReleaseFixtureHarness.removeBrand;
import static io.evitadb.store.catalog.ReleaseFixtureHarness.setAttribute;
import static io.evitadb.store.catalog.ReleaseFixtureHarness.update;
import static io.evitadb.test.TestTags.ATTRIBUTE;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.STORAGE;
import static io.evitadb.test.upgrade.Release2026_2FixtureRecipes.BRAND;
import static io.evitadb.test.upgrade.Release2026_2FixtureRecipes.PRODUCT;
import static io.evitadb.test.upgrade.Release2026_2FixtureRecipes.inRange;
import static io.evitadb.test.upgrade.Release2026_2FixtureRecipes.orderedInEnglish;
import static io.evitadb.test.upgrade.ReleaseFixtureValues.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Catalogs written by the released 2026.2 engine — and by 2026.1, opened or not by 2026.2 — behave after the upgrade to
 * the current engine as a catalog the current engine built from the same input. Each scenario runs against a fixture
 * under `testData/release_upgrade_fixtures`, written by the released engines from a recipe in
 * `io.evitadb.test.upgrade`; {@link ReleaseFixtureHarness} opens a copy of it and replays the recipe into a fresh
 * catalog for comparison.
 *
 * Every fixture is checked to arrive with the storage protocol its origin wrote. The scenarios beyond that cover the
 * release shapes the current engine already upgrades correctly; a fixture without a scenario here holds a shape whose
 * upgrade is covered by the change that handles it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Catalogs written by released engines behave correctly after the upgrade")
@Tag(STORAGE)
@Tag(ATTRIBUTE)
class Release2026_2UpgradeDefectsTest implements EvitaTestSupport {

	private final ReleaseFixtureHarness harness = new ReleaseFixtureHarness(this);

	/**
	 * Returns the name of every fixture, the 2026.2 ones first.
	 *
	 * @return the fixture names
	 */
	@Nonnull
	static Stream<String> allFixtures() {
		return Stream.concat(Release2026_2FixtureRecipes.all().stream(), Release2026_1FixtureRecipes.all().stream())
			.map(ReleaseFixtureRecipe::name);
	}

	/**
	 * Returns the fixtures whose catalog the current engine loads after the upgrade.
	 *
	 * @return the fixture names
	 */
	@Nonnull
	static Stream<String> loadingFixtures() {
		return Stream.of(
			"reference-attribute-open-bound-twins",
			"filter-open-bound-twins",
			"filter-fractional-second-bounds",
			"sort-fractional-second-bounds",
			"sort-fractional-second-bounds-paged",
			"sort-localized-collator-twins",
			"localized-compound-sort",
			"localized-temporal-compound-sort",
			"filter-localized-collator-twins",
			"filter-whole-second-equal-ranges",
			"sort-whole-second-equal-ranges",
			"filter-sub-millisecond-moments-paged",
			"sort-sub-millisecond-moments-paged",
			"two-collections-range-shapes",
			"healthy-temporal-and-localized-sort",
			"healthy-decimal-range-filter",
			"price-validity-extreme-bounds",
			"price-validity-fractional-second-bounds",
			"removed-values-tombstones",
			"reference-filter-localized-removed-owner",
			"reference-filter-decimal-range-removed-owner",
			"reference-whole-second-equal-ranges-both-scopes",
			"healthy-entity-and-group-partitions",
			"group-partition-two-references",
			"healthy-group-unique-withdrawn-key",
			"reflected-references",
			"reflected-of-reflected-reference",
			"shared-range-border",
			"healthy-unique-global-range",
			"sort-localized-cardinalities-zero-width-first",
			"sort-localized-cardinalities-plain-first",
			"filter-decimal-range-folded-keys"
		);
	}

	/**
	 * Returns the fixtures whose upgraded catalog already answers every probe of its recipe as a fresh current engine
	 * fed the same recipe does.
	 *
	 * @return the fixture names
	 */
	@Nonnull
	static Stream<String> freshEquivalentFixtures() {
		return Stream.of(
			"reference-attribute-open-bound-twins",
			"filter-open-bound-twins",
			"sort-fractional-second-bounds-paged",
			"filter-sub-millisecond-moments-paged",
			"healthy-temporal-and-localized-sort",
			"healthy-decimal-range-filter",
			"removed-values-tombstones",
			"reference-filter-decimal-range-removed-owner",
			"healthy-entity-and-group-partitions",
			"healthy-unique-global-range"
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("allFixtures")
	@DisplayName("every fixture arrives with the storage protocol its origin wrote")
	void shouldCarryReleaseStorageProtocolWhenFixtureIsCopied(@Nonnull String fixture) {
		final TestPaths paths = this.harness.copy(fixture);
		try {
			final int expected = ReleaseFixtureHarness.recipe(fixture).origin() == ReleaseFixtureOrigin.RELEASE_2026_1
				? 4 : 6;
			assertEquals(expected, this.harness.lastBootstrapStorageProtocol(paths, fixture));
		} finally {
			this.harness.cleanup(paths);
		}
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("loadingFixtures")
	@DisplayName("the fixture loads after the upgrade with its header at storage protocol 7")
	void shouldLoadWhenFixtureIsUpgraded(@Nonnull String fixture) {
		this.harness.withUpgradedCatalog(
			fixture, evita -> assertEquals(7, ReleaseFixtureHarness.headerStorageProtocol(evita, fixture))
		);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("freshEquivalentFixtures")
	@DisplayName("every probe of the upgraded fixture answers as on a fresh engine fed the same recipe")
	void shouldAnswerProbesAsFreshEngineWhenFixtureIsUpgraded(@Nonnull String fixture) {
		final AtomicReference<List<String>> fresh = new AtomicReference<>();
		this.harness.withFreshCatalog(fixture, evita -> fresh.set(probe(evita, fixture)));
		this.harness.withUpgradedCatalog(fixture, evita -> assertEquals(fresh.get(), probe(evita, fixture)));
	}

	@Nested
	@DisplayName("Storage protocol and publication")
	class StorageProtocol {

		@Test
		@DisplayName("a native 2026.2 catalog is republished at protocol 7 on top of its release bootstrap records")
		void shouldStampProtocolSevenWhenNativeCatalogIsUpgraded() {
			final ReleaseFixtureHarness harness = Release2026_2UpgradeDefectsTest.this.harness;
			final String fixture = "healthy-unique-global-range";
			final TestPaths paths = harness.copy(fixture);
			try {
				final List<CatalogBootstrap> before = harness.bootstrapRecords(paths, fixture);
				try (Evita evita = harness.boot(paths)) {
					ReleaseFixtureHarness.requireAlive(evita, fixture);
					assertEquals(7, ReleaseFixtureHarness.headerStorageProtocol(evita, fixture));
				}
				final List<CatalogBootstrap> after = harness.bootstrapRecords(paths, fixture);
				assertTrue(after.size() > before.size(), "the upgrade must publish a new bootstrap record");
				assertEquals(before, after.subList(0, before.size()), "release bootstrap records stay as they were");
				assertEquals(7, harness.lastBootstrapStorageProtocol(paths, fixture));
			} finally {
				harness.cleanup(paths);
			}
		}

		@Test
		@DisplayName("a 2026.1 catalog opened by 2026.2 carries both releases' bootstrap stamps before the upgrade")
		void shouldCarryBothReleaseStampsWhenCatalogWasOpenedByRelease2026_2() {
			final ReleaseFixtureHarness harness = Release2026_2UpgradeDefectsTest.this.harness;
			final String fixture = Release2026_1FixtureRecipes.SORT_LOCALIZED_CARDINALITIES_ZERO_WIDTH_FIRST;
			final TestPaths paths = harness.copy(fixture);
			try {
				final List<Integer> protocols = harness.bootstrapRecords(paths, fixture)
					.stream()
					.map(CatalogBootstrap::storageProtocolVersion)
					.distinct()
					.toList();
				assertEquals(List.of(4, 6), protocols);
			} finally {
				harness.cleanup(paths);
			}
		}

		@Test
		@DisplayName("the second boot of an upgraded healthy catalog leaves every file of the catalog byte-identical")
		void shouldAppendNothingWhenUpgradedCatalogBootsAgain() {
			final ReleaseFixtureHarness harness = Release2026_2UpgradeDefectsTest.this.harness;
			final String fixture = "healthy-unique-global-range";
			final TestPaths paths = harness.copy(fixture);
			try {
				try (Evita evita = harness.boot(paths)) {
					ReleaseFixtureHarness.requireAlive(evita, fixture);
				}
				final Path catalogDirectory = ReleaseFixtureHarness.catalogDirectory(paths, fixture);
				final Map<String, Long> lengths = ReleaseFixtureHarness.fileLengths(catalogDirectory);
				final Map<String, String> digests = ReleaseFixtureHarness.fileDigests(catalogDirectory);
				try (Evita evita = harness.boot(paths)) {
					ReleaseFixtureHarness.requireAlive(evita, fixture);
				}
				assertEquals(lengths, ReleaseFixtureHarness.fileLengths(catalogDirectory));
				ReleaseFixtureHarness.assertByteIdentical(digests, catalogDirectory);
			} finally {
				harness.cleanup(paths);
			}
		}
	}

	@Nested
	@DisplayName("A FILTER holding the open-bound twins until(10:00+01:00) and until(11:00+02:00)")
	class OpenBoundTwinsFilter {

		@Test
		@DisplayName("the FILTER holding both twins in separate buckets survives the removal of one owner")
		void shouldKeepSecondOwnerInFilterBucketWhenFirstOwnerChanges() {
			final String fixture = "filter-open-bound-twins";
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded(fixture, evita -> {
				final Query byValidity = inRange("validity", BASE.minusMonths(7));
				assertEquals(List.of(1, 2), pks(evita, fixture, byValidity));
				update(evita, fixture, session -> setAttribute(session, 1, "validity", LATER));
				assertEquals(List.of(2), pks(evita, fixture, byValidity));
				update(evita, fixture, session -> setAttribute(session, 2, "validity", LATER));
				assertEquals(List.of(), pks(evita, fixture, byValidity));
			});
		}
	}

	@Nested
	@DisplayName("DateTimeRange owner SORT ordered by whole seconds in the release")
	class DateTimeRangeSortOrder {

		@Test
		@DisplayName("inline: updating A after the upgrade succeeds and leaves the order consistent")
		void shouldKeepOrderConsistentWhenInlineSortIsUpdated() {
			final String fixture = "sort-fractional-second-bounds";
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded(fixture, evita -> {
				update(evita, fixture, session -> setAttribute(session, 1, "sortValidity", LATER));
				update(evita, fixture, session -> setAttribute(
					session, 2, "sortValidity", DateTimeRange.between(BASE.plusYears(2), BASE.plusYears(2).plusDays(1))
				));
				assertEquals(List.of(3, 1, 2), pks(evita, fixture, sortedByValidity(3)));
			});
		}

		@Test
		@DisplayName("paged: every B_i sorts before its A_i")
		void shouldOrderPagedSortByMillisecondBounds() {
			final String fixture = "sort-fractional-second-bounds-paged";
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded(
				fixture, evita -> assertEquals(List.of(2, 1, 4, 3, 6, 5), pks(evita, fixture, sortedByValidity(6)))
			);
		}

		@Test
		@DisplayName("paged: updating A_0 after the upgrade succeeds")
		void shouldKeepOrderConsistentWhenPagedSortIsUpdated() {
			final String fixture = "sort-fractional-second-bounds-paged";
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded(fixture, evita -> {
				update(evita, fixture, session -> setAttribute(session, 1, "sortValidity", LATER));
				assertEquals(List.of(2, 4, 3), pks(evita, fixture, sortedByValidity(3)));
			});
		}

		/**
		 * Returns a query for the first products ordered by `sortValidity`.
		 *
		 * @param count how many products
		 * @return the query
		 */
		@Nonnull
		private static Query sortedByValidity(int count) {
			return query(collection(PRODUCT), orderBy(attributeNatural("sortValidity")), require(page(1, count)));
		}
	}

	@Nested
	@DisplayName("Localized String compounds whose release order ignored the tie-break")
	class LocalizedTieBreak {

		@Test
		@DisplayName("compound (label, code): updating a record after the upgrade keeps the order consistent")
		void shouldKeepOrderConsistentWhenNonTemporalLocalizedCompoundIsUpdated() {
			final String fixture = "localized-compound-sort";
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded(fixture, evita -> {
				update(evita, fixture, session -> setAttribute(session, 2, "code", 0));
				assertEquals(List.of(3, 2, 1), pks(evita, fixture, orderedInEnglish("labelCode")));
				update(evita, fixture, session -> setAttribute(session, 1, "code", 9));
				assertEquals(List.of(3, 2, 1), pks(evita, fixture, orderedInEnglish("labelCode")));
			});
		}

		@Test
		@DisplayName("compound (label, moment): updating a record after the upgrade succeeds")
		void shouldKeepOrderConsistentWhenTemporalLocalizedCompoundIsUpdated() {
			final String fixture = "localized-temporal-compound-sort";
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded(fixture, evita -> {
				update(evita, fixture, session -> setAttribute(session, 2, "moment", BASE.minusDays(1)));
				assertEquals(List.of(3, 2, 1), pks(evita, fixture, orderedInEnglish("labelMoment")));
			});
		}
	}

	@Nested
	@DisplayName("Reference attributes and partitions")
	@Tag(REFERENCE)
	class ReferenceShapes {

		/**
		 * The fixture of one owner reaching one group partition through two references.
		 */
		private static final String GROUP_PARTITION = "group-partition-two-references";

		@Test
		@DisplayName("the remaining owner of a range reference attribute is found by a point inside it")
		void shouldFindRemainingOwnerWhenRangeReferenceAttributeOfOtherOwnerWasRemoved() {
			final String fixture = "reference-filter-decimal-range-removed-owner";
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded(fixture, evita -> assertEquals(
				List.of(2),
				pks(evita, fixture, query(
					collection(PRODUCT),
					filterBy(referenceHaving(BRAND, attributeInRange("size", new BigDecimal("5"))))
				))
			));
		}

		@Test
		@DisplayName("group partitions: updating the attribute and removing both references answers as a fresh engine")
		void shouldAnswerAsFreshEngineWhenGroupPartitionOwnerChanges() {
			final List<Consumer<EvitaSessionContract>> steps = List.of(
				session -> setAttribute(session, 1, "validity", LATER),
				session -> removeBrand(session, 1, 1),
				session -> removeBrand(session, 1, 2)
			);
			final AtomicReference<List<List<String>>> fresh = new AtomicReference<>();
			Release2026_2UpgradeDefectsTest.this.harness.withFreshCatalog(
				GROUP_PARTITION, evita -> fresh.set(applySteps(evita, steps))
			);
			Release2026_2UpgradeDefectsTest.this.harness.withUpgradedCatalog(
				GROUP_PARTITION, evita -> assertEquals(fresh.get(), applySteps(evita, steps))
			);
		}

		@Test
		@DisplayName("a group-only unique reference attribute with a withdrawn release key loads every value intact")
		void shouldKeepEveryValueWhenWithdrawnUniqueRepresentativeIsHealthy() {
			final String fixture = "healthy-group-unique-withdrawn-key";
			Release2026_2UpgradeDefectsTest.this.harness.withUpgradedCatalog(fixture, evita -> {
				final List<String> references = evita.queryCatalog(
					fixture,
					session -> {
						return session.queryList(
								query(collection(PRODUCT), require(entityFetch(referenceContentAllWithAttributes()))),
								SealedEntity.class
							)
							.stream()
							.flatMap(
								product -> product.getReferences(BRAND).stream().map(
									reference -> product.getPrimaryKey() + "->" + reference.getReferencedPrimaryKey() +
										"/" + reference.getGroup().orElseThrow().getPrimaryKey() + " " +
										reference.getAttribute("validity")
								)
							)
							.toList();
					}
				);
				assertEquals(
					List.of(
						"2->2/10 " + UNTIL_900_PLUS_ONE,
						"3->3/20 " + UNTIL_100_PLUS_TWO
					),
					references
				);
			});
		}

		/**
		 * Commits each step in the group partition fixture and records the fixture's probe answers after it.
		 *
		 * @param evita the engine
		 * @param steps the transactions
		 * @return the probe answers after each step
		 */
		@Nonnull
		private static List<List<String>> applySteps(
			@Nonnull Evita evita,
			@Nonnull List<Consumer<EvitaSessionContract>> steps
		) {
			return steps.stream()
				.map(step -> {
					update(evita, GROUP_PARTITION, step);
					return probe(evita, GROUP_PARTITION);
				})
				.toList();
		}
	}

}
