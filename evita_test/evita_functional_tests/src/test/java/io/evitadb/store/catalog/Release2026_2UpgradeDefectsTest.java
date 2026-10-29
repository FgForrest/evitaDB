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
import io.evitadb.test.upgrade.ReleaseFixtureOrigin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
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
 * Checks publication and lossless query comparisons for catalogs written by released 2026.2 and 2026.1 engines.
 * Successful loading alone does not promise query parity for potentially lossy fields. Each scenario uses a fixture
 * under `testData/release_upgrade_fixtures`, written by the released engines from a recipe in
 * `io.evitadb.test.upgrade`; {@link ReleaseFixtureHarness} opens a copy of it and replays the recipe into a fresh
 * catalog for comparison.
 *
 * Every fixture is checked to arrive at the storage protocol its origin wrote. Accepted loads and lossless
 * comparisons live here and in `Release2026_2IndexUpgradeTest`; deliberate refusals and conditional warnings live
 * in the migration preflight and report tests.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Catalogs written by released engines behave correctly after the upgrade")
@Tag(STORAGE)
@Tag(ATTRIBUTE)
class Release2026_2UpgradeDefectsTest implements EvitaTestSupport {
	/** A moment inside both open-bound twins. */
	private static final OffsetDateTime MID_2025 = BASE.minusMonths(7);

	private final ReleaseFixtureHarness harness = new ReleaseFixtureHarness(this);

	/**
	 * Returns every fixture name.
	 *
	 * @return the fixture names
	 */
	@Nonnull
	static Stream<String> allFixtures() {
		return Stream.of(
			"d1a", "d1b", "d1b2", "d1c", "d2", "d3", "d3p", "d4", "d4c", "d4t", "d4f", "d4cp", "d5f", "d5s", "dF1p",
			"dS1p", "dX", "dv", "dN1h", "d14", "d2p", "dDel", "d6", "d7", "d8", "d16", "d16n", "d17", "d17a", "d5r",
			"dK1h", "dGrp", "dUw", "drf", "dRR", "dRng", "d9", "d9o", "d10", "d10s", "d11", "d11t", "dU", "d6c", "d12",
			"d12b", "d13", "d15"
		);
	}

	/**
	 * Returns the fixtures the current engine loads after the upgrade, among those the upgrade migrates rather than
	 * refuses.
	 *
	 * @return the fixture names
	 */
	@Nonnull
	static Stream<String> loadingFixtures() {
		return Stream.of(
			"d1a", "d1c", "d2", "d3", "d3p", "d4", "d4c", "d4t", "d4f", "d5f", "d5s", "dF1p", "dS1p", "dX", "dv",
			"dN1h", "d14", "d2p", "dDel", "d16", "d16n", "d5r", "dK1h", "dGrp", "dUw", "drf", "dRR", "dRng", "dU",
			"d12", "d12b", "d15", "d4cp", "d7", "d17", "d17a", "d10s"
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
			"d1a", "d1c", "d3p", "d4t", "dF1p", "dS1p", "dv", "dN1h", "dDel", "d16n", "dK1h", "dU",
			"d3", "d4c", "d4cp", "dRng", "d10s"
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

	/** Loads and probes the release catalogs whose stored index order the upgrade rewrites. */
	@Nested
	@DisplayName("Stored index ordering")
	class StoredIndexOrdering {

		/** Verifies each catalog publishes the current storage protocol and loads successfully. */
		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {"d1b", "d1b2", "d9", "d9o", "d13"})
		void shouldLoadWhenFixtureIsUpgraded(@Nonnull String fixture) {
			Release2026_2UpgradeDefectsTest.this.harness.withUpgradedCatalog(
				fixture, evita -> assertEquals(7, ReleaseFixtureHarness.headerStorageProtocol(evita, fixture))
			);
		}

		/** Verifies the upgraded indexes answer every recipe probe as a fresh replay does. */
		@ParameterizedTest(name = "{0}")
		@ValueSource(strings = {"d1b", "d1b2", "d9", "d9o", "d13"})
		void shouldAnswerProbesAsFreshEngineWhenFixtureIsUpgraded(@Nonnull String fixture) {
			final AtomicReference<List<String>> fresh = new AtomicReference<>();
			Release2026_2UpgradeDefectsTest.this.harness.withFreshCatalog(
				fixture, evita -> fresh.set(probe(evita, fixture))
			);
			Release2026_2UpgradeDefectsTest.this.harness.withUpgradedCatalog(
				fixture, evita -> assertEquals(fresh.get(), probe(evita, fixture))
			);
		}
	}

	@Nested
	@DisplayName("Storage protocol and publication")
	class StorageProtocol {

		@Test
		@DisplayName("a native 2026.2 catalog is republished at protocol 7 on top of its release bootstrap records")
		void shouldStampProtocolSevenWhenNativeCatalogIsUpgraded() {
			final ReleaseFixtureHarness harness = Release2026_2UpgradeDefectsTest.this.harness;
			final TestPaths paths = harness.copy("dU");
			try {
				final List<CatalogBootstrap> before = harness.bootstrapRecords(paths, "dU");
				try (Evita evita = harness.boot(paths)) {
					ReleaseFixtureHarness.requireAlive(evita, "dU");
					assertEquals(7, ReleaseFixtureHarness.headerStorageProtocol(evita, "dU"));
				}
				final List<CatalogBootstrap> after = harness.bootstrapRecords(paths, "dU");
				assertTrue(after.size() > before.size(), "the upgrade must publish a new bootstrap record");
				assertEquals(before, after.subList(0, before.size()), "release bootstrap records stay as they were");
				assertEquals(7, harness.lastBootstrapStorageProtocol(paths, "dU"));
			} finally {
				harness.cleanup(paths);
			}
		}

		@Test
		@DisplayName("a 2026.1 catalog opened by 2026.2 carries both releases' bootstrap stamps before the upgrade")
		void shouldCarryBothReleaseStampsWhenCatalogWasOpenedByRelease2026_2() {
			final ReleaseFixtureHarness harness = Release2026_2UpgradeDefectsTest.this.harness;
			final TestPaths paths = harness.copy("d12");
			try {
				final List<Integer> protocols = harness.bootstrapRecords(paths, "d12")
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
			final TestPaths paths = harness.copy("dU");
			try {
				try (Evita evita = harness.boot(paths)) {
					ReleaseFixtureHarness.requireAlive(evita, "dU");
				}
				final Path catalogDirectory = ReleaseFixtureHarness.catalogDirectory(paths, "dU");
				final Map<String, Long> lengths = ReleaseFixtureHarness.fileLengths(catalogDirectory);
				final Map<String, String> digests = ReleaseFixtureHarness.fileDigests(catalogDirectory);
				try (Evita evita = harness.boot(paths)) {
					ReleaseFixtureHarness.requireAlive(evita, "dU");
				}
				assertEquals(lengths, ReleaseFixtureHarness.fileLengths(catalogDirectory));
				ReleaseFixtureHarness.assertByteIdentical(digests, catalogDirectory);
			} finally {
				harness.cleanup(paths);
			}
		}
	}

	/** Tests shared counters whose released keys become equal. */
	@Nested
	@DisplayName("Reference counters keyed by open-bound twins at different offsets")
	@Tag(REFERENCE)
	class OpenBoundCounters {

		/** Verifies reference removal preserves the remaining contributions. */
		@Test
		@DisplayName("removing one owner's reference keeps the other owner findable through the shared counter")
		void shouldKeepSecondOwnerFindableWhenFirstOwnerLeavesCollidingCounter() {
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded("d1a", evita -> {
				final Query byValidity = query(
					collection(PRODUCT), filterBy(referenceHaving(BRAND, attributeInRange("validity", MID_2025)))
				);
				assertEquals(List.of(1, 2), pks(evita, "d1a", byValidity));
				update(evita, "d1a", session -> removeBrand(session, 1, 1));
				assertEquals(List.of(2), pks(evita, "d1a", byValidity));
			});
		}

		/** Verifies reference removal preserves the remaining contributions. */
		@Test
		@DisplayName("after one owner leaves, the other owner is still found by an exact match on its value")
		void shouldKeepSecondOwnerFindableByEqualityWhenFirstOwnerLeaves() {
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded("d1a", evita -> {
				update(evita, "d1a", session -> removeBrand(session, 1, 1));
				assertEquals(
					List.of(2),
					pks(
						evita, "d1a",
						query(collection(PRODUCT), filterBy(referenceHaving(BRAND, attributeEquals("validity", U2))))
					)
				);
			});
		}

		/** Verifies reference removal preserves the remaining contributions. */
		@Test
		@DisplayName("both owners can drop their colliding reference one after another")
		void shouldRemoveBothCollidingReferencesWhenOwnersLeaveInTurn() {
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded("d1a", evita -> {
				update(evita, "d1a", session -> removeBrand(session, 1, 1));
				update(evita, "d1a", session -> removeBrand(session, 2, 1));
				assertEquals(
					List.of(),
					pks(
						evita, "d1a",
						query(
							collection(PRODUCT),
							filterBy(referenceHaving(BRAND, attributeInRange("validity", MID_2025)))
						)
					)
				);
			});
		}
	}


	@Nested
	@DisplayName("a FILTER holding the open-bound twins until(10:00+01:00) and until(11:00+02:00)")
	class OpenBoundSentinel {

		@Test
		@DisplayName("the FILTER holding both twins in separate buckets survives the removal of one owner")
		void shouldKeepSecondOwnerInFilterBucketWhenFirstOwnerChanges() {
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded("d1c", evita -> {
				final Query byValidity = inRange("validity", BASE.minusMonths(7));
				assertEquals(List.of(1, 2), pks(evita, "d1c", byValidity));
				update(evita, "d1c", session -> setAttribute(session, 1, "validity", LATER));
				assertEquals(List.of(2), pks(evita, "d1c", byValidity));
				update(evita, "d1c", session -> setAttribute(session, 2, "validity", LATER));
				assertEquals(List.of(), pks(evita, "d1c", byValidity));
			});
		}
	}

	@Nested
	@DisplayName("DateTimeRange owner SORT ordered by whole seconds in the release")
	class DateTimeRangeSortOrder {

		@Test
		@DisplayName("inline: updating A after the upgrade succeeds and leaves the order consistent")
		void shouldKeepOrderConsistentWhenInlineSortIsUpdated() {
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded("d3", evita -> {
				update(evita, "d3", session -> setAttribute(session, 1, "sortValidity", LATER));
				update(evita, "d3", session -> setAttribute(
					session, 2, "sortValidity", DateTimeRange.between(BASE.plusYears(2), BASE.plusYears(2).plusDays(1))
				));
				assertEquals(List.of(3, 1, 2), pks(evita, "d3", sortedByValidity(3)));
			});
		}

		@Test
		@DisplayName("paged: every B_i sorts before its A_i")
		void shouldOrderPagedSortByMillisecondBounds() {
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded(
				"d3p", evita -> assertEquals(List.of(2, 1, 4, 3, 6, 5), pks(evita, "d3p", sortedByValidity(6)))
			);
		}

		@Test
		@DisplayName("paged: updating A_0 after the upgrade succeeds")
		void shouldKeepOrderConsistentWhenPagedSortIsUpdated() {
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded("d3p", evita -> {
				update(evita, "d3p", session -> setAttribute(session, 1, "sortValidity", LATER));
				assertEquals(List.of(2, 4, 3), pks(evita, "d3p", sortedByValidity(3)));
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
	@DisplayName("localized String compounds whose release order ignored the tie-break")
	class LocalizedTieBreak {

		@Test
		@DisplayName("compound (label, code): updating a record after the upgrade keeps the order consistent")
		void shouldKeepOrderConsistentWhenNonTemporalLocalizedCompoundIsUpdated() {
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded("d4c", evita -> {
				update(evita, "d4c", session -> setAttribute(session, 2, "code", 0));
				assertEquals(List.of(3, 2, 1), pks(evita, "d4c", orderedInEnglish("labelCode")));
				update(evita, "d4c", session -> setAttribute(session, 1, "code", 9));
				assertEquals(List.of(3, 2, 1), pks(evita, "d4c", orderedInEnglish("labelCode")));
			});
		}

		@Test
		@DisplayName("compound (label, moment): ordered by the tie-break before the moment")
		void shouldOrderTemporalLocalizedCompoundByTieBreak() {
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded(
				"d4t", evita -> assertEquals(List.of(3, 2, 1), pks(evita, "d4t", orderedInEnglish("labelMoment")))
			);
		}

		@Test
		@DisplayName("compound (label, moment): updating a record after the upgrade succeeds")
		void shouldKeepOrderConsistentWhenTemporalLocalizedCompoundIsUpdated() {
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded("d4t", evita -> {
				update(evita, "d4t", session -> setAttribute(session, 2, "moment", BASE.minusDays(1)));
				assertEquals(List.of(3, 2, 1), pks(evita, "d4t", orderedInEnglish("labelMoment")));
			});
		}
	}

	@Nested
	@DisplayName("Reference attributes and partitions")
	@Tag(REFERENCE)
	class ReferenceShapes {

		@Test
		@DisplayName("The remaining owner of a numeric reference range is found by a point inside it")
		void shouldFindRemainingOwnerWhenRangeReferenceAttributeOfOtherOwnerWasRemoved() {
			Release2026_2UpgradeDefectsTest.this.harness.assertOnFreshThenUpgraded("d16n", evita -> assertEquals(
				List.of(2),
				pks(evita, "d16n", query(
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
				"dGrp", evita -> fresh.set(applySteps(evita, steps))
			);
			Release2026_2UpgradeDefectsTest.this.harness.withUpgradedCatalog(
				"dGrp", evita -> assertEquals(fresh.get(), applySteps(evita, steps))
			);
		}

		@Test
		@DisplayName("a group-only unique reference attribute with a withdrawn release key loads every value intact")
		void shouldKeepEveryValueWhenWithdrawnUniqueRepresentativeIsHealthy() {
			Release2026_2UpgradeDefectsTest.this.harness.withUpgradedCatalog("dUw", evita -> {
				final List<String> references = evita.queryCatalog(
					"dUw",
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
		 * Commits each step in `dGrp` and records the fixture's probe answers after it.
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
					update(evita, "dGrp", step);
					return probe(evita, "dGrp");
				})
				.toList();
		}
	}

}
