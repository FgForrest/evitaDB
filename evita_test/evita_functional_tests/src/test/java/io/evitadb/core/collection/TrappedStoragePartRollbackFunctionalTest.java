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

package io.evitadb.core.collection;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.ReferenceCardinalityViolatedException;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.buffer.DataStoreChanges;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.TestTags;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins down that a rolled-back entity mutation leaves nothing behind in a storage part that the data store buffer
 * holds TRAPPED — kept in memory by an earlier nested (implicit) mutation until the next flush.
 *
 * The buffer hands a trapped part out BY REFERENCE ({@link DataStoreChanges#getStoragePart}), and the storage
 * executor caches that very instance and mutates it in place while it applies the mutation. Restoring map slots and
 * stored records is therefore not enough: the per-entity savepoint must restore the CONTENT of the shared instance as
 * well, or a root mutation that fails after its nested executor wrote into a trapped part leaves its writes inside
 * that part:
 *
 * - in WARM_UP the next flush would persist them, so the catalog would keep a reflected reference whose counterpart
 *   does not exist, while the index — which the savepoint rewinds — would disagree with the stored body;
 * - in ALIVE every read inside the same transaction would see them, and the committed state would be right only
 *   because the trunk replays the root mutations from the WAL.
 *
 * Every scenario comes with a negative control that runs the same failure against a part that is NOT trapped. The
 * control separates the trap from the failure shape: should a scenario fail while its control passes, the shared
 * instance is what kept the rolled-back change.
 *
 * The schema is the one of the original report: `product.main → category` (`ZERO_OR_ONE`, indexed for filtering and
 * partitioning), its reflection `category.mainProducts` (`ZERO_OR_MORE`) and `product.brand → category`
 * (`EXACTLY_ONE`), whose removal is the natural, injection-free failure of the product's own consistency check.
 * Scenarios that need more add it on top through {@link SchemaVariant}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Rollback of changes written into a trapped storage part")
@Tag(TestTags.ENGINE)
@Tag(TestTags.TRANSACTION)
@Tag(TestTags.REFERENCE)
class TrappedStoragePartRollbackFunctionalTest implements EvitaTestSupport {
	private static final String ENTITY_PRODUCT = "product";
	private static final String ENTITY_CATEGORY = "category";
	private static final String ENTITY_OWNER = "owner";
	private static final String REFERENCE_MAIN = "main";
	private static final String REFERENCE_BRAND = "brand";
	private static final String REFERENCE_MAIN_PRODUCTS = "mainProducts";
	private static final String REFERENCE_OWNER = "owner";
	private static final String ATTRIBUTE_NOTE = "note";
	private static final String ATTRIBUTE_NAME = "name";
	private static final int CATEGORY_TRAPPED = 7;
	private static final int CATEGORY_BRAND = 8;
	private static final int PRODUCT_FAILING = 2;
	private static final int PRODUCT_TRAPPING = 3;
	private static final int OWNER = 1;
	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("TrappedStoragePartRollbackFunctionalTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Nested
	@DisplayName("Reflected reference of a failed root mutation (the reported case)")
	class ReportedCase {

		@BeforeEach
		void setUp() {
			seedCatalog(SchemaVariant.REPORTED);
		}

		/**
		 * The scenario of the report, on the bulk-load path. Product 3 is linked to category 7 first, which makes the
		 * nested executor of category 7 trap its references part in the buffer. Product 2 is then linked to the same
		 * category in an upsert that also removes its `EXACTLY_ONE` brand, so it fails its consistency check AFTER its
		 * nested executor already inserted the reflected reference into that trapped instance.
		 *
		 * The savepoint must rewind not only the index and product 2's own parts but also the content of the shared
		 * instance, which the session's closing flush would otherwise persist. Category 7 must keep exactly product 3
		 * — in the same session, after the flush, and after the catalog is loaded again from disk. Each read point also
		 * asks the index (a reference query from either side), so a disagreement between the index and the stored
		 * body is visible as its own line in the failure.
		 */
		@Test
		@DisplayName("A failed product leaves no reflected reference behind in a warm-up catalog")
		void shouldNotPersistRolledBackReflectedReferenceInWarmUp() {
			final Observation inSession = runReportedScenarioInOneSession(true);
			final Observation afterClose = observeReportedCase();
			restartEvita();
			final Observation afterReopen = observeReportedCase();

			final Observation expected = Observation.reportedCase(List.of(PRODUCT_TRAPPING));
			assertAll(
				() -> assertEquals(expected, inSession, "Read in the same session after the failure"),
				() -> assertEquals(expected, afterClose, "Read after the session close flushed the catalog"),
				() -> assertEquals(expected, afterReopen, "Read after the catalog was loaded again from disk")
			);
		}

		/**
		 * Negative control: the same failing upsert of product 2, but category 7 was NOT touched before, so its
		 * references part is read from the store as a private instance rather than handed out from the trap. This
		 * must pass — it proves the trapped instance, not the failure shape, is what keeps the rolled-back change.
		 */
		@Test
		@DisplayName("Without a trapped part the failed product leaves nothing behind (control)")
		void shouldNotPersistRolledBackReflectedReferenceInWarmUpWithoutTrap() {
			final Observation inSession = runReportedScenarioInOneSession(false);
			final Observation afterClose = observeReportedCase();
			restartEvita();
			final Observation afterReopen = observeReportedCase();

			final Observation expected = Observation.reportedCase(List.of());
			assertAll(
				() -> assertEquals(expected, inSession, "Read in the same session after the failure"),
				() -> assertEquals(expected, afterClose, "Read after the session close flushed the catalog"),
				() -> assertEquals(expected, afterReopen, "Read after the catalog was loaded again from disk")
			);
		}

		/**
		 * The same scenario in a live catalog. The transaction's own buffer traps category 7's part exactly as the
		 * warm-up buffer does, and the transactional savepoint must restore its content the same way, so a read later
		 * in the same transaction must not see the reflected reference of the failed product. The committed state is
		 * checked as well, although it does not depend on that restore: the trunk replays the WAL rather than adopting
		 * the transaction's buffer.
		 */
		@Test
		@DisplayName("A failed product leaves no reflected reference visible inside a live transaction")
		void shouldNotExposeRolledBackReflectedReferenceInsideTransaction() {
			goLive();
			final Observation inTransaction = runReportedScenarioInOneSession(true);
			final Observation afterCommit = observeReportedCase();

			final Observation expected = Observation.reportedCase(List.of(PRODUCT_TRAPPING));
			assertAll(
				() -> assertEquals(expected, inTransaction, "Read in the same transaction after the failure"),
				() -> assertEquals(expected, afterCommit, "Read after the transaction committed")
			);
		}

		/**
		 * Negative control of the live scenario: category 7 is not trapped in the transaction's buffer before product 2
		 * fails, so the reflected reference must leave no trace in the transaction either.
		 */
		@Test
		@DisplayName("Without a trapped part the transaction sees nothing of the failed product (control)")
		void shouldNotExposeRolledBackReflectedReferenceInsideTransactionWithoutTrap() {
			goLive();
			final Observation inTransaction = runReportedScenarioInOneSession(false);
			final Observation afterCommit = observeReportedCase();

			final Observation expected = Observation.reportedCase(List.of());
			assertAll(
				() -> assertEquals(expected, inTransaction, "Read in the same transaction after the failure"),
				() -> assertEquals(expected, afterCommit, "Read after the transaction committed")
			);
		}

		/**
		 * Runs the reported scenario in one session — a warm-up session or a transaction, whichever the catalog state
		 * implies — and observes the state before the session ends.
		 *
		 * @param trapFirst whether product 3 is linked to category 7 first, trapping its references part
		 * @return what the session reads after the failure
		 */
		@Nonnull
		private Observation runReportedScenarioInOneSession(boolean trapFirst) {
			final AtomicReference<Observation> observed = new AtomicReference<>();
			TrappedStoragePartRollbackFunctionalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					runReportedScenario(session, trapFirst);
					observed.set(observeReportedCase(session));
				}
			);
			return observed.get();
		}

		/**
		 * Links product 3 to category 7 when asked to, then attempts the failing upsert of product 2 and swallows the
		 * failure the way a bulk loader would.
		 *
		 * @param session   the read-write session to run in
		 * @param trapFirst whether product 3 is linked to category 7 first, trapping its references part
		 */
		private void runReportedScenario(@Nonnull EvitaSessionContract session, boolean trapFirst) {
			if (trapFirst) {
				linkProductToCategory(session, PRODUCT_TRAPPING, null, null);
			}
			assertThrows(
				ReferenceCardinalityViolatedException.class,
				() -> session.getEntity(ENTITY_PRODUCT, PRODUCT_FAILING, entityFetchAllContent())
					.orElseThrow()
					.openForWrite()
					.setReference(REFERENCE_MAIN, CATEGORY_TRAPPED)
					.removeReference(REFERENCE_BRAND, CATEGORY_BRAND)
					.upsertVia(session),
				"Removing the EXACTLY_ONE brand must fail product 2's consistency check."
			);
		}

		/**
		 * Observes the reported case in a fresh read-only session.
		 *
		 * @return the observed state
		 */
		@Nonnull
		private Observation observeReportedCase() {
			return TrappedStoragePartRollbackFunctionalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return observeReportedCase(session);
				}
			);
		}

		/**
		 * Observes everything the reported case can disagree about: the reflected references stored in category 7's
		 * body, the categories the index files under product 2, product 2's own reference and the products the index
		 * files under category 7.
		 *
		 * @param session the session to read through
		 * @return the observed state
		 */
		@Nonnull
		private Observation observeReportedCase(@Nonnull EvitaSessionContract session) {
			return new Observation(
				referencedKeys(fetch(session, ENTITY_CATEGORY, CATEGORY_TRAPPED), REFERENCE_MAIN_PRODUCTS),
				queryKeys(
					session, ENTITY_CATEGORY,
					referenceHaving(REFERENCE_MAIN_PRODUCTS, entityPrimaryKeyInSet(PRODUCT_FAILING))
				),
				referencedKeys(fetch(session, ENTITY_PRODUCT, PRODUCT_FAILING), REFERENCE_MAIN),
				queryKeys(
					session, ENTITY_PRODUCT,
					referenceHaving(REFERENCE_MAIN, entityPrimaryKeyInSet(CATEGORY_TRAPPED))
				)
			);
		}
	}

	@Nested
	@DisplayName("Failed root mutation of an entity whose parts are trapped")
	class RootMutationOfTrappedEntity {

		@BeforeEach
		void setUp() {
			seedCatalog(SchemaVariant.OWNED_CATEGORY);
		}

		/**
		 * A failure of the ROOT mutation itself, with no nested executor writing into the trapped parts. Linking
		 * product 3 to category 7 traps category 7's references part AND its body (a reference change marks the body
		 * dirty for the version bump). A later ROOT upsert of category 7 itself reads the same two instances, adds a
		 * German name — which records the locale in the body — and removes the `EXACTLY_ONE` owner, so its own
		 * consistency check fails. The root executor reads the body before its savepoint opens, so this is the case
		 * only an explicit journal of the parts it already holds covers.
		 *
		 * The root's private attribute part is discarded, and the removed owner and the added locale must not stay in
		 * the trapped instances either. Category 7 must still have its owner and no German locale, in the same session
		 * and after a reload; the owner and locale queries are answered by the (rewound) index and are reported next
		 * to the body.
		 */
		@Test
		@DisplayName("A failed root mutation of a trapped category leaves its references and body intact in warm-up")
		void shouldNotPersistRolledBackRootChangeOfTrappedEntityInWarmUp() {
			final RootObservation inSession = runRootScenario(true);
			final RootObservation afterClose = observeRootCase();
			restartEvita();
			final RootObservation afterReopen = observeRootCase();

			final RootObservation expected = RootObservation.untouched(List.of(PRODUCT_TRAPPING));
			assertAll(
				() -> assertEquals(expected, inSession, "Read in the same session after the failure"),
				() -> assertEquals(expected, afterClose, "Read after the session close flushed the catalog"),
				() -> assertEquals(expected, afterReopen, "Read after the catalog was loaded again from disk")
			);
		}

		/**
		 * Negative control of {@link #shouldNotPersistRolledBackRootChangeOfTrappedEntityInWarmUp()}: without the
		 * preceding link, category 7's parts are private instances read from the store, and the same failing root
		 * upsert must leave nothing behind.
		 */
		@Test
		@DisplayName("Without trapped parts the failed root mutation leaves nothing behind in warm-up (control)")
		void shouldNotPersistRolledBackRootChangeInWarmUpWithoutTrap() {
			final RootObservation inSession = runRootScenario(false);
			final RootObservation afterClose = observeRootCase();
			restartEvita();
			final RootObservation afterReopen = observeRootCase();

			final RootObservation expected = RootObservation.untouched(List.of());
			assertAll(
				() -> assertEquals(expected, inSession, "Read in the same session after the failure"),
				() -> assertEquals(expected, afterClose, "Read after the session close flushed the catalog"),
				() -> assertEquals(expected, afterReopen, "Read after the catalog was loaded again from disk")
			);
		}

		/**
		 * The live counterpart of {@link #shouldNotPersistRolledBackRootChangeOfTrappedEntityInWarmUp()}: the
		 * transaction must not read the removed owner or the added locale of the failed root mutation, and the
		 * committed state must not carry them either.
		 */
		@Test
		@DisplayName("A failed root mutation of a trapped category is invisible inside a live transaction")
		void shouldNotExposeRolledBackRootChangeOfTrappedEntityInsideTransaction() {
			goLive();
			final RootObservation inTransaction = runRootScenario(true);
			final RootObservation afterCommit = observeRootCase();

			final RootObservation expected = RootObservation.untouched(List.of(PRODUCT_TRAPPING));
			assertAll(
				() -> assertEquals(expected, inTransaction, "Read in the same transaction after the failure"),
				() -> assertEquals(expected, afterCommit, "Read after the transaction committed")
			);
		}

		/**
		 * Negative control of the live scenario: no trap, so the transaction must see nothing of the failure.
		 */
		@Test
		@DisplayName("Without trapped parts the transaction sees nothing of the failed root mutation (control)")
		void shouldNotExposeRolledBackRootChangeInsideTransactionWithoutTrap() {
			goLive();
			final RootObservation inTransaction = runRootScenario(false);
			final RootObservation afterCommit = observeRootCase();

			final RootObservation expected = RootObservation.untouched(List.of());
			assertAll(
				() -> assertEquals(expected, inTransaction, "Read in the same transaction after the failure"),
				() -> assertEquals(expected, afterCommit, "Read after the transaction committed")
			);
		}

		/**
		 * Runs the root scenario in one session (warm-up or transaction, whichever the catalog state implies) and
		 * observes the state before the session ends.
		 *
		 * @param trapFirst whether product 3 is linked to category 7 first, trapping its references part and body
		 * @return what the session reads after the failure
		 */
		@Nonnull
		private RootObservation runRootScenario(boolean trapFirst) {
			final AtomicReference<RootObservation> observed = new AtomicReference<>();
			TrappedStoragePartRollbackFunctionalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					if (trapFirst) {
						linkProductToCategory(session, PRODUCT_TRAPPING, null, null);
					}
					assertThrows(
						ReferenceCardinalityViolatedException.class,
						() -> fetch(session, ENTITY_CATEGORY, CATEGORY_TRAPPED)
							.openForWrite()
							.setAttribute(ATTRIBUTE_NAME, Locale.GERMAN, "Kategorie")
							.removeReference(REFERENCE_OWNER, OWNER)
							.upsertVia(session),
						"Removing the EXACTLY_ONE owner must fail category 7's consistency check."
					);
					observed.set(observeRootCase(session));
				}
			);
			return observed.get();
		}

		/**
		 * Observes the root case in a fresh read-only session.
		 *
		 * @return the observed state
		 */
		@Nonnull
		private RootObservation observeRootCase() {
			return TrappedStoragePartRollbackFunctionalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return observeRootCase(session);
				}
			);
		}

		/**
		 * Observes category 7's body (owner, locales, reflected references) next to what the index says about the
		 * owner and the German locale.
		 *
		 * @param session the session to read through
		 * @return the observed state
		 */
		@Nonnull
		private RootObservation observeRootCase(@Nonnull EvitaSessionContract session) {
			final SealedEntity category = fetch(session, ENTITY_CATEGORY, CATEGORY_TRAPPED);
			return new RootObservation(
				referencedKeys(category, REFERENCE_OWNER),
				localesOf(category),
				referencedKeys(category, REFERENCE_MAIN_PRODUCTS),
				queryKeys(session, ENTITY_CATEGORY, referenceHaving(REFERENCE_OWNER, entityPrimaryKeyInSet(OWNER))),
				queryKeys(session, ENTITY_CATEGORY, entityLocaleEquals(Locale.GERMAN))
			);
		}
	}

	@Nested
	@DisplayName("Entity body trapped by a nested executor")
	class TrappedEntityBody {

		@BeforeEach
		void setUp() {
			seedCatalog(SchemaVariant.ATTRIBUTED_REFERENCE);
		}

		/**
		 * The entity body is trapped and mutated in place too. The reflected reference inherits the localized `note`
		 * attribute, so the nested executor of category 7 does not only insert a reference: it records the attribute's
		 * locale in category 7's BODY as well. Product 3 is linked with an English note — trapping the body with `en` —
		 * and the failing product 2 with a German one.
		 *
		 * Category 7's locales must stay `[en]`. A fix that restored only the references part would leave this test
		 * red, which is what it is here for. The German-locale query is answered by the index and is reported next to
		 * the body.
		 */
		@Test
		@DisplayName("A failed product leaves no locale behind in the trapped body in warm-up")
		@Tag(TestTags.ATTRIBUTE)
		void shouldNotPersistLocaleOfRolledBackReflectedReferenceInWarmUp() {
			final BodyObservation inSession = runBodyScenario(true).afterFailure();
			final BodyObservation afterClose = observeBodyCase();
			restartEvita();
			final BodyObservation afterReopen = observeBodyCase();

			final BodyObservation expected = new BodyObservation(List.of("en"), List.of());
			assertAll(
				() -> assertEquals(expected, inSession, "Read in the same session after the failure"),
				() -> assertEquals(expected, afterClose, "Read after the session close flushed the catalog"),
				() -> assertEquals(expected, afterReopen, "Read after the catalog was loaded again from disk")
			);
		}

		/**
		 * The live counterpart: the transaction must not see the German locale of the failed product in category 7's
		 * body, and the committed state must not carry it.
		 */
		@Test
		@DisplayName("A failed product leaves no locale visible in the trapped body inside a live transaction")
		@Tag(TestTags.ATTRIBUTE)
		void shouldNotExposeLocaleOfRolledBackReflectedReferenceInsideTransaction() {
			goLive();
			final BodyObservation inTransaction = runBodyScenario(true).afterFailure();
			final BodyObservation afterCommit = observeBodyCase();

			final BodyObservation expected = new BodyObservation(List.of("en"), List.of());
			assertAll(
				() -> assertEquals(expected, inTransaction, "Read in the same transaction after the failure"),
				() -> assertEquals(expected, afterCommit, "Read after the transaction committed")
			);
		}

		/**
		 * Negative control: product 3 is linked in an EARLIER session, whose close flushes category 7, so the failing
		 * product 2 reads a private body from the store. Its German locale must leave no trace.
		 */
		@Test
		@DisplayName("Without a trapped body the failed product leaves no locale behind (control)")
		@Tag(TestTags.ATTRIBUTE)
		void shouldNotPersistLocaleOfRolledBackReflectedReferenceWithoutTrap() {
			TrappedStoragePartRollbackFunctionalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					linkProductToCategory(session, PRODUCT_TRAPPING, Locale.ENGLISH, "three");
				}
			);
			final BodyObservation inSession = runBodyScenario(false).afterFailure();
			final BodyObservation afterClose = observeBodyCase();

			final BodyObservation expected = new BodyObservation(List.of("en"), List.of());
			assertAll(
				() -> assertEquals(expected, inSession, "Read in the same session after the failure"),
				() -> assertEquals(expected, afterClose, "Read after the session close flushed the catalog")
			);
		}

		/**
		 * The body version is trapped state as well. The body reports `version + 1` for as long as it is dirty, and a
		 * trapped body is always dirty — it was trapped BECAUSE it was. A second, rolled-back change therefore cannot
		 * bump the version any further, so the version survives the failure unchanged. This test records that the
		 * version is NOT a symptom, and guards that it stays so.
		 */
		@Test
		@DisplayName("A failed product does not bump the version of the trapped body")
		@Tag(TestTags.ATTRIBUTE)
		void shouldNotBumpVersionOfTrappedBodyByRolledBackChange() {
			final BodyScenarioResult result = runBodyScenario(true);
			assertEquals(
				result.versionBeforeFailure(), result.versionAfterFailure(),
				"The rolled-back reflected insert must not change the version of category 7."
			);
		}

		/**
		 * Runs the body scenario in one session (warm-up or transaction, whichever the catalog state implies).
		 *
		 * @param trapFirst whether product 3 is linked to category 7 in this session first, trapping its body
		 * @return the body observed after the failure and the versions around it
		 */
		@Nonnull
		private BodyScenarioResult runBodyScenario(boolean trapFirst) {
			final AtomicReference<BodyScenarioResult> observed = new AtomicReference<>();
			TrappedStoragePartRollbackFunctionalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					if (trapFirst) {
						linkProductToCategory(session, PRODUCT_TRAPPING, Locale.ENGLISH, "three");
					}
					final int versionBefore = fetch(session, ENTITY_CATEGORY, CATEGORY_TRAPPED).version();
					assertThrows(
						ReferenceCardinalityViolatedException.class,
						() -> fetch(session, ENTITY_PRODUCT, PRODUCT_FAILING)
							.openForWrite()
							.setReference(
								REFERENCE_MAIN, CATEGORY_TRAPPED,
								whichIs -> whichIs.setAttribute(ATTRIBUTE_NOTE, Locale.GERMAN, "zwei")
							)
							.removeReference(REFERENCE_BRAND, CATEGORY_BRAND)
							.upsertVia(session),
						"Removing the EXACTLY_ONE brand must fail product 2's consistency check."
					);
					observed.set(
						new BodyScenarioResult(
							observeBodyCase(session),
							versionBefore,
							fetch(session, ENTITY_CATEGORY, CATEGORY_TRAPPED).version()
						)
					);
				}
			);
			return observed.get();
		}

		/**
		 * Observes the body case in a fresh read-only session.
		 *
		 * @return the observed state
		 */
		@Nonnull
		private BodyObservation observeBodyCase() {
			return TrappedStoragePartRollbackFunctionalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					return observeBodyCase(session);
				}
			);
		}

		/**
		 * Observes category 7's locales as the body reports them, next to the categories the index files under the
		 * German locale.
		 *
		 * @param session the session to read through
		 * @return the observed state
		 */
		@Nonnull
		private BodyObservation observeBodyCase(@Nonnull EvitaSessionContract session) {
			return new BodyObservation(
				localesOf(fetch(session, ENTITY_CATEGORY, CATEGORY_TRAPPED)),
				queryKeys(session, ENTITY_CATEGORY, entityLocaleEquals(Locale.GERMAN))
			);
		}
	}

	@Nested
	@DisplayName("Entity handed to a client while its parts are trapped")
	class ReturnedEntitySnapshot {

		@BeforeEach
		void setUp() {
			seedCatalog(SchemaVariant.ATTRIBUTED_REFERENCE);
		}

		/**
		 * The read side of the shared instance. A `SealedEntity` is immutable, and one built from a trapped references
		 * part must not change when a later mutation in the same session replaces a reference in that part in place
		 * (here: a new value of the inherited `note`, propagated to category 7 as a reference attribute mutation).
		 *
		 * The entity fetched before the update must keep reading the old note. The fresh fetch after the update is
		 * asserted too, so the scenario cannot pass by the update never reaching category 7. Nothing is rolled back
		 * here: this is the same shared instance seen from the read side.
		 */
		@Test
		@DisplayName("An entity fetched in a warm-up session does not change under a later mutation")
		@Tag(TestTags.ATTRIBUTE)
		void shouldKeepFetchedEntityUnchangedByLaterMutationInWarmUp() {
			assertSnapshotKeepsItsValue(runReaderScenario(true));
		}

		/**
		 * The live counterpart of {@link #shouldKeepFetchedEntityUnchangedByLaterMutationInWarmUp()}: the entity read
		 * earlier in a transaction must not change under a later mutation of the same transaction.
		 */
		@Test
		@DisplayName("An entity fetched inside a live transaction does not change under a later mutation")
		@Tag(TestTags.ATTRIBUTE)
		void shouldKeepFetchedEntityUnchangedByLaterMutationInsideTransaction() {
			goLive();
			assertSnapshotKeepsItsValue(runReaderScenario(true));
		}

		/**
		 * Negative control: product 3 is linked in an earlier session, so category 7 is fetched from a private,
		 * deserialized part. The later update must leave the fetched entity alone.
		 */
		@Test
		@DisplayName("An entity fetched from an untrapped part does not change under a later mutation (control)")
		@Tag(TestTags.ATTRIBUTE)
		void shouldKeepFetchedEntityUnchangedByLaterMutationWithoutTrap() {
			TrappedStoragePartRollbackFunctionalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					linkProductToCategory(session, PRODUCT_TRAPPING, Locale.ENGLISH, "three");
				}
			);
			assertSnapshotKeepsItsValue(runReaderScenario(false));
		}

		/**
		 * Runs the reader scenario in one session: optionally links product 3 (trapping category 7), fetches category
		 * 7 WITHOUT reading its references, updates the note of product 3's reference, and only then reads the note
		 * through the entity fetched earlier and through a fresh fetch.
		 *
		 * @param trapFirst whether product 3 is linked to category 7 in this session first
		 * @return the note as the earlier and the fresh entity report it
		 */
		@Nonnull
		private ReaderObservation runReaderScenario(boolean trapFirst) {
			final AtomicReference<ReaderObservation> observed = new AtomicReference<>();
			TrappedStoragePartRollbackFunctionalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					if (trapFirst) {
						linkProductToCategory(session, PRODUCT_TRAPPING, Locale.ENGLISH, "three");
					}
					// fetched, but deliberately not read yet - the decorator resolves its references lazily
					final SealedEntity fetchedBefore = fetch(session, ENTITY_CATEGORY, CATEGORY_TRAPPED);

					fetch(session, ENTITY_PRODUCT, PRODUCT_TRAPPING)
						.openForWrite()
						.setReference(
							REFERENCE_MAIN, CATEGORY_TRAPPED,
							whichIs -> whichIs.setAttribute(ATTRIBUTE_NOTE, Locale.ENGLISH, "updated")
						)
						.upsertVia(session);

					observed.set(
						new ReaderObservation(
							noteOfReflectedReference(fetchedBefore),
							noteOfReflectedReference(fetch(session, ENTITY_CATEGORY, CATEGORY_TRAPPED))
						)
					);
				}
			);
			return observed.get();
		}

		/**
		 * Asserts the earlier entity kept the note it was fetched with while a fresh fetch sees the update.
		 *
		 * @param observation the notes as observed
		 */
		private static void assertSnapshotKeepsItsValue(@Nonnull ReaderObservation observation) {
			assertAll(
				() -> assertEquals(
					"updated", observation.freshlyFetched(),
					"Self-check: a fresh fetch must see the update, otherwise nothing was exercised."
				),
				() -> assertEquals(
					"three", observation.fetchedBefore(),
					"The entity fetched before the update must keep the note it was fetched with."
				)
			);
		}

		/**
		 * Returns the English note of category 7's reflected reference to product 3, read through the reference
		 * collection — the one view that is not a snapshot.
		 *
		 * @param category the category entity
		 * @return the note, or `null` when the reference or the note is missing
		 */
		@Nullable
		private static Serializable noteOfReflectedReference(@Nonnull SealedEntity category) {
			return category.getReferences(REFERENCE_MAIN_PRODUCTS)
				.stream()
				.filter(it -> it.getReferencedPrimaryKey() == PRODUCT_TRAPPING)
				.map(it -> (Serializable) it.getAttribute(ATTRIBUTE_NOTE, Locale.ENGLISH))
				.findFirst()
				.orElse(null);
		}
	}

	/**
	 * Defines the schema of the chosen variant and seeds categories 7 and 8 and products 2 and 3 (both with brand 8)
	 * in one warm-up session.
	 *
	 * @param variant which additions to the reported schema are needed
	 */
	private void seedCatalog(@Nonnull SchemaVariant variant) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				final var productSchema = session.defineEntitySchema(ENTITY_PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(
						REFERENCE_MAIN, ENTITY_CATEGORY, Cardinality.ZERO_OR_ONE,
						whichIs -> {
							whichIs.indexedForFilteringAndPartitioning();
							if (variant == SchemaVariant.ATTRIBUTED_REFERENCE) {
								whichIs.withAttribute(
									ATTRIBUTE_NOTE, String.class, thatIs -> thatIs.localized().nullable()
								);
							}
						}
					)
					.withReferenceToEntity(REFERENCE_BRAND, ENTITY_CATEGORY, Cardinality.EXACTLY_ONE, whichIs -> {});
				if (variant == SchemaVariant.ATTRIBUTED_REFERENCE) {
					productSchema.withLocale(Locale.ENGLISH, Locale.GERMAN);
				}
				productSchema.updateVia(session);

				final var categorySchema = session.defineEntitySchema(ENTITY_CATEGORY)
					.withoutGeneratedPrimaryKey()
					.withReflectedReferenceToEntity(
						REFERENCE_MAIN_PRODUCTS, ENTITY_PRODUCT, REFERENCE_MAIN,
						whichIs -> {
							whichIs.withCardinality(Cardinality.ZERO_OR_MORE);
							if (variant == SchemaVariant.ATTRIBUTED_REFERENCE) {
								whichIs.withAttributesInherited();
							}
						}
					);
				if (variant == SchemaVariant.ATTRIBUTED_REFERENCE) {
					categorySchema.withLocale(Locale.ENGLISH, Locale.GERMAN);
				} else if (variant == SchemaVariant.OWNED_CATEGORY) {
					categorySchema
						.withLocale(Locale.GERMAN)
						.withAttribute(ATTRIBUTE_NAME, String.class, thatIs -> thatIs.localized().nullable())
						.withReferenceTo(
							REFERENCE_OWNER, ENTITY_OWNER, Cardinality.EXACTLY_ONE,
							whichIs -> whichIs.indexedForFiltering()
						);
				}
				categorySchema.updateVia(session);

				for (final int categoryPk : new int[]{CATEGORY_TRAPPED, CATEGORY_BRAND}) {
					final var category = session.createNewEntity(ENTITY_CATEGORY, categoryPk);
					if (variant == SchemaVariant.OWNED_CATEGORY) {
						category.setReference(REFERENCE_OWNER, OWNER);
					}
					category.upsertVia(session);
				}
				session.createNewEntity(ENTITY_PRODUCT, PRODUCT_FAILING)
					.setReference(REFERENCE_BRAND, CATEGORY_BRAND)
					.upsertVia(session);
				session.createNewEntity(ENTITY_PRODUCT, PRODUCT_TRAPPING)
					.setReference(REFERENCE_BRAND, CATEGORY_BRAND)
					.upsertVia(session);
			}
		);
	}

	/**
	 * Links a product to category 7 through the `main` reference, which makes the nested executor of category 7
	 * insert the reflected reference and trap category 7's references part and body until the next flush.
	 *
	 * @param session    the read-write session to run in
	 * @param productPk  the product to link
	 * @param noteLocale the locale of the reference note, or `null` for none
	 * @param note       the note, or `null` for none
	 */
	private static void linkProductToCategory(
		@Nonnull EvitaSessionContract session,
		int productPk,
		@Nullable Locale noteLocale,
		@Nullable String note
	) {
		fetch(session, ENTITY_PRODUCT, productPk)
			.openForWrite()
			.setReference(
				REFERENCE_MAIN, CATEGORY_TRAPPED,
				whichIs -> {
					if (noteLocale != null) {
						whichIs.setAttribute(ATTRIBUTE_NOTE, noteLocale, note);
					}
				}
			)
			.upsertVia(session);
	}

	/**
	 * Takes the catalog live, so the following writes go through transactions.
	 */
	private void goLive() {
		try (final EvitaSessionContract session = this.evita.createReadWriteSession(TEST_CATALOG)) {
			session.goLiveAndClose();
		}
	}

	/**
	 * Closes the engine and starts a new one over the same directories, so the following reads come from what the
	 * catalog persisted. The catalog is loaded on the service pool, so `new Evita` returns while it is still
	 * BEING_ACTIVATED - the wait keeps the reads from failing with `CatalogTransitioningException`.
	 */
	private void restartEvita() {
		this.evita.close();
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.waitUntilFullyInitialized();
	}

	/**
	 * Fetches an entity with all its content, failing when it does not exist.
	 *
	 * @param session    the session to read through
	 * @param entityType the entity type
	 * @param primaryKey the primary key
	 * @return the entity
	 */
	@Nonnull
	private static SealedEntity fetch(
		@Nonnull EvitaSessionContract session,
		@Nonnull String entityType,
		int primaryKey
	) {
		return session.getEntity(entityType, primaryKey, entityFetchAllContent()).orElseThrow();
	}

	/**
	 * Returns the sorted primary keys an entity's references of one name point at, as its stored body reports them.
	 *
	 * @param entity        the entity
	 * @param referenceName the reference name
	 * @return sorted referenced primary keys
	 */
	@Nonnull
	private static List<Integer> referencedKeys(@Nonnull SealedEntity entity, @Nonnull String referenceName) {
		return entity.getReferences(referenceName)
			.stream()
			.map(ReferenceContract::getReferencedPrimaryKey)
			.sorted()
			.toList();
	}

	/**
	 * Returns the sorted language tags of all locales an entity's body reports.
	 *
	 * @param entity the entity
	 * @return sorted language tags
	 */
	@Nonnull
	private static List<String> localesOf(@Nonnull SealedEntity entity) {
		final Set<String> tags = entity.getAllLocales()
			.stream()
			.map(Locale::toLanguageTag)
			.collect(Collectors.toCollection(TreeSet::new));
		return List.copyOf(tags);
	}

	/**
	 * Returns the sorted primary keys of the entities the index returns for the filter.
	 *
	 * @param session    the session to query through
	 * @param entityType the queried entity type
	 * @param filter     the filter, answered by the index
	 * @return sorted primary keys
	 */
	@Nonnull
	private static List<Integer> queryKeys(
		@Nonnull EvitaSessionContract session, @Nonnull String entityType, @Nonnull FilterConstraint filter
	) {
		return session.queryList(query(collection(entityType), filterBy(filter)), EntityReference.class)
			.stream()
			.map(EntityReference::getPrimaryKey)
			.sorted()
			.toList();
	}

	/**
	 * The additions a scenario needs on top of the schema of the original report.
	 */
	private enum SchemaVariant {
		/**
		 * Exactly the schema of the report.
		 */
		REPORTED,
		/**
		 * Adds an indexed `EXACTLY_ONE` owner reference and a localized name to the category, so a root upsert of the
		 * category can fail its own consistency check after touching both its references part and its body.
		 */
		OWNED_CATEGORY,
		/**
		 * Adds a localized `note` attribute to the `main` reference, inherited by its reflection, so the nested
		 * executor of the category also records a locale in the category's body.
		 */
		ATTRIBUTED_REFERENCE
	}

	/**
	 * What the reported case can disagree about.
	 *
	 * @param category7Body          products the stored body of category 7 references through `mainProducts`
	 * @param categoriesIndexedFor2  categories the index files under product 2 through `mainProducts`
	 * @param product2Body           categories the stored body of product 2 references through `main`
	 * @param productsIndexedUnder7  products the index files under category 7 through `main`
	 */
	private record Observation(
		@Nonnull List<Integer> category7Body,
		@Nonnull List<Integer> categoriesIndexedFor2,
		@Nonnull List<Integer> product2Body,
		@Nonnull List<Integer> productsIndexedUnder7
	) {

		/**
		 * The consistent state of the reported case: product 2 has no trace anywhere.
		 *
		 * @param linkedProducts the products legitimately linked to category 7
		 * @return the expected observation
		 */
		@Nonnull
		static Observation reportedCase(@Nonnull List<Integer> linkedProducts) {
			return new Observation(linkedProducts, List.of(), List.of(), linkedProducts);
		}
	}

	/**
	 * What the root case can disagree about.
	 *
	 * @param ownerInBody           owners the stored body of category 7 references
	 * @param localesInBody         locales the stored body of category 7 reports
	 * @param mainProductsInBody    products the stored body of category 7 references through `mainProducts`
	 * @param categoriesIndexedForOwner categories the index files under the owner
	 * @param categoriesIndexedInGerman categories the index files under the German locale
	 */
	private record RootObservation(
		@Nonnull List<Integer> ownerInBody,
		@Nonnull List<String> localesInBody,
		@Nonnull List<Integer> mainProductsInBody,
		@Nonnull List<Integer> categoriesIndexedForOwner,
		@Nonnull List<Integer> categoriesIndexedInGerman
	) {

		/**
		 * The state category 7 had before the failed root mutation.
		 *
		 * @param linkedProducts the products legitimately linked to category 7
		 * @return the expected observation
		 */
		@Nonnull
		static RootObservation untouched(@Nonnull List<Integer> linkedProducts) {
			return new RootObservation(
				List.of(OWNER), List.of(), linkedProducts, List.of(CATEGORY_TRAPPED, CATEGORY_BRAND), List.of()
			);
		}
	}

	/**
	 * What the body case can disagree about.
	 *
	 * @param localesInBody             locales the stored body of category 7 reports
	 * @param categoriesIndexedInGerman categories the index files under the German locale
	 */
	private record BodyObservation(
		@Nonnull List<String> localesInBody,
		@Nonnull List<Integer> categoriesIndexedInGerman
	) {
	}

	/**
	 * The result of one body scenario run.
	 *
	 * @param afterFailure          the body observed after the failure, in the same session
	 * @param versionBeforeFailure  category 7's version right before the failing upsert
	 * @param versionAfterFailure   category 7's version right after it
	 */
	private record BodyScenarioResult(
		@Nonnull BodyObservation afterFailure,
		int versionBeforeFailure,
		int versionAfterFailure
	) {
	}

	/**
	 * The note of category 7's reflected reference to product 3, read through two entities.
	 *
	 * @param fetchedBefore  through the entity fetched before the update
	 * @param freshlyFetched through an entity fetched after it
	 */
	private record ReaderObservation(
		@Nullable Serializable fetchedBefore,
		@Nullable Serializable freshlyFetched
	) {
	}

}
