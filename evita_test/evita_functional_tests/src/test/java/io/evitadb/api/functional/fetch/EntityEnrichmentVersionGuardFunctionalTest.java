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

package io.evitadb.api.functional.fetch;

import io.evitadb.api.CommitProgress;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.core.query.response.ServerEntityDecorator;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.TestTags;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.api.query.QueryConstraints.attributeContentAll;
import static io.evitadb.api.query.QueryConstraints.dataInLocalesAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the provenance a {@link ServerEntityDecorator} carries and the behaviour of the shortcut in
 * `EntityCollection#enrichEntityInternal` that returns the caller's own decorator untouched when an enrichment widens
 * none of its predicates.
 *
 * The shortcut rests on one premise: *the same committed catalog snapshot implies byte-identical stored data*. A
 * catalog version names such a snapshot only while the catalog is `ALIVE` and no transaction is in flight, and it is
 * numbered per catalog rather than globally, so this class drives the four situations in which a bare version number
 * would be believed and should not be - a warming-up catalog whose version never moves, an uncommitted transaction
 * overlay, a rolled-back transaction, and a decorator carried between two catalogs that sit at the same number.
 *
 * Two routes produce a decorator, and they carry different predicates: a mutation result
 * (`upsertAndFetchEntity` and friends) is handed straight out of `wrapToDecorator`, while a query or `getEntity`
 * result is additionally narrowed by `limitEntity`. Both can take the shortcut, and {@link EnrichmentShortcut}
 * pins what the second one has to be compared against for that to be true.
 *
 * Every scenario mutates its catalog, so the class builds its own embedded instance rather than borrowing a shared
 * read-only dataset - mutating one of those would force a rebuild for every other consumer.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Catalog version guard on entity enrichment")
@Tag(TestTags.ENGINE)
@Tag(TestTags.QUERY)
class EntityEnrichmentVersionGuardFunctionalTest implements EvitaTestSupport {
	private static final String ATTRIBUTE_CODE = "code";
	private static final String SECOND_CATALOG = "secondCatalog";
	private static final int PRODUCT_PK = 1;
	private static final String ORIGINAL_CODE = "original";
	private static final String CHANGED_CODE = "changed";
	private static final String SECOND_CATALOG_CODE = "second-catalog-value";

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("EntityEnrichmentVersionGuardFunctionalTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	/**
	 * Creates a catalog holding a single product with one non-localized attribute, and leaves it in
	 * {@link io.evitadb.api.CatalogState#WARMING_UP}.
	 *
	 * @param catalogName name of the catalog to create
	 * @param code        value the single product carries in its `code` attribute
	 */
	private void createCatalogWithSingleProduct(@Nonnull String catalogName, @Nonnull String code) {
		this.evita.defineCatalog(catalogName);
		this.evita.updateCatalog(
			catalogName,
			session -> {
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withAttribute(ATTRIBUTE_CODE, String.class, AttributeSchemaEditor::filterable)
					.updateVia(session);
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, PRODUCT_PK).setAttribute(ATTRIBUTE_CODE, code)
				);
			}
		);
	}

	/**
	 * Transitions the named catalog to {@link io.evitadb.api.CatalogState#ALIVE} so that subsequent writes run
	 * through the transactional path.
	 *
	 * @param catalogName name of the catalog to transition
	 */
	private void goLive(@Nonnull String catalogName) {
		try (final EvitaSessionContract session = this.evita.createReadWriteSession(catalogName)) {
			session.goLiveAndClose();
		}
	}

	/**
	 * Reads the single seeded product at the widest scope the schema allows. The result passes through
	 * `limitEntity`, so it is the *narrowed* shape a client normally receives.
	 *
	 * @param session session to read through
	 * @return the fetched entity
	 */
	@Nonnull
	private static SealedEntity readWidest(@Nonnull EvitaSessionContract session) {
		return session.getEntity(Entities.PRODUCT, PRODUCT_PK, attributeContentAll(), dataInLocalesAll())
			.orElseThrow();
	}

	/**
	 * Writes the passed value into the single seeded product and returns the mutation result at the widest scope
	 * the schema allows. Unlike {@link #readWidest(EvitaSessionContract)} this result is handed straight out of
	 * `wrapToDecorator` with no narrowing wrapper around it.
	 *
	 * @param session session to write through
	 * @param code    value to store in the `code` attribute
	 * @return the entity as it stands after the write
	 */
	@Nonnull
	private static SealedEntity writeAndFetchWidest(@Nonnull EvitaSessionContract session, @Nonnull String code) {
		return session.upsertAndFetchEntity(
			readWidest(session).openForWrite().setAttribute(ATTRIBUTE_CODE, code),
			attributeContentAll(), dataInLocalesAll()
		);
	}

	@Nested
	@DisplayName("Warming up catalog")
	class WarmingUpCatalog {

		@DisplayName("Entity fetched while warming up claims no provenance at all")
		@Test
		void shouldStampAWarmUpFetchAsUnknownProvenance() {
			createCatalogWithSingleProduct(TEST_CATALOG, ORIGINAL_CODE);

			EntityEnrichmentVersionGuardFunctionalTest.this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					// a warming-up catalog cannot move its version (Catalog#setVersion asserts an open transaction
					// and warm-up opens none), so the number it reports is not a snapshot token at all - it stays
					// put while the data underneath it change freely, and must therefore never be stamped
					assertEquals(
						ServerEntityDecorator.UNKNOWN_CATALOG_VERSION,
						((ServerEntityDecorator) readWidest(session)).getCatalogVersion()
					);
					assertNotEquals(
						session.getCatalogVersion(),
						((ServerEntityDecorator) readWidest(session)).getCatalogVersion()
					);
					assertNull(((ServerEntityDecorator) readWidest(session)).getCatalogId());
				}
			);
		}

		@DisplayName("Enrichment observes a warm-up mutation")
		@Test
		void shouldObserveAWarmUpMutationWhenEnrichmentWidensNothing() {
			createCatalogWithSingleProduct(TEST_CATALOG, ORIGINAL_CODE);

			final Evita evita = EntityEnrichmentVersionGuardFunctionalTest.this.evita;
			final SealedEntity beforeMutation = evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					return writeAndFetchWidest(session, ORIGINAL_CODE);
				}
			);
			evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					session.upsertEntity(
						readWidest(session).openForWrite().setAttribute(ATTRIBUTE_CODE, CHANGED_CODE)
					);
				}
			);

			try (final EvitaSessionContract readSession = evita.createReadOnlySession(TEST_CATALOG)) {
				// the mutation is visible to a fresh read, so the stored bytes really did change
				assertEquals(CHANGED_CODE, readWidest(readSession).getAttribute(ATTRIBUTE_CODE, String.class));

				final SealedEntity enriched = readSession.enrichEntity(
					beforeMutation, attributeContentAll(), dataInLocalesAll()
				);

				// nothing about the catalog version can tell the two apart - it never moved across the mutation,
				// as the sibling test asserts. What refuses the shortcut is that the decorator carries no
				// provenance to match against, so the enrichment goes back to storage and refreshes.
				assertNotSame(beforeMutation, enriched);
				assertEquals(CHANGED_CODE, enriched.getAttribute(ATTRIBUTE_CODE, String.class));
			}
		}
	}

	@Nested
	@DisplayName("Uncommitted transaction overlay")
	@Tag(TestTags.TRANSACTION)
	class UncommittedOverlay {

		@DisplayName("Entity read from a transaction overlay claims no provenance at all")
		@Test
		void shouldStampAnUncommittedOverlayAsUnknownProvenance() {
			createCatalogWithSingleProduct(TEST_CATALOG, ORIGINAL_CODE);
			goLive(TEST_CATALOG);

			final Evita evita = EntityEnrichmentVersionGuardFunctionalTest.this.evita;
			final EvitaSessionContract writeSession = evita.createReadWriteSession(TEST_CATALOG);
			final SealedEntity uncommitted = writeAndFetchWidest(writeSession, CHANGED_CODE);
			assertEquals(CHANGED_CODE, uncommitted.getAttribute(ATTRIBUTE_CODE, String.class));

			try (final EvitaSessionContract readSession = evita.createReadOnlySession(TEST_CATALOG)) {
				// an overlay is not a committed snapshot: a concurrent reader sits at the very version the write is
				// based on, so stamping that version would make the two indistinguishable
				assertEquals(
					ServerEntityDecorator.UNKNOWN_CATALOG_VERSION,
					((ServerEntityDecorator) uncommitted).getCatalogVersion()
				);
				assertNotEquals(
					readSession.getCatalogVersion(),
					((ServerEntityDecorator) uncommitted).getCatalogVersion()
				);
				assertNull(((ServerEntityDecorator) uncommitted).getCatalogId());
			}

			writeSession.setRollbackOnly();
			awaitSettled(writeSession);
		}

		@DisplayName("Read-only session refuses an uncommitted change carried in by the writer's decorator")
		@Test
		void shouldNotSurfaceAnUncommittedChangeInAReadOnlySession() {
			createCatalogWithSingleProduct(TEST_CATALOG, ORIGINAL_CODE);
			goLive(TEST_CATALOG);

			final Evita evita = EntityEnrichmentVersionGuardFunctionalTest.this.evita;
			final EvitaSessionContract writeSession = evita.createReadWriteSession(TEST_CATALOG);
			final SealedEntity uncommitted = writeAndFetchWidest(writeSession, CHANGED_CODE);

			try (final EvitaSessionContract readSession = evita.createReadOnlySession(TEST_CATALOG)) {
				// the reader is still on the committed snapshot and must never see anything else
				assertEquals(ORIGINAL_CODE, readWidest(readSession).getAttribute(ATTRIBUTE_CODE, String.class));

				final SealedEntity enriched = readSession.enrichEntity(
					uncommitted, attributeContentAll(), dataInLocalesAll()
				);

				// the writer's decorator carries no provenance, so the enrichment cannot short-circuit and the
				// re-read refreshes it from the committed snapshot the reader is on
				assertNotSame(uncommitted, enriched);
				assertEquals(ORIGINAL_CODE, enriched.getAttribute(ATTRIBUTE_CODE, String.class));
			}

			writeSession.setRollbackOnly();
			awaitSettled(writeSession);
		}
	}

	@Nested
	@DisplayName("Rolled back transaction")
	@Tag(TestTags.TRANSACTION)
	class RolledBackTransaction {

		@DisplayName("A change discarded by a rollback does not survive inside the decorator that carried it")
		@Test
		void shouldNotSurfaceARolledBackChangeAfterTheTransactionIsGone() {
			createCatalogWithSingleProduct(TEST_CATALOG, ORIGINAL_CODE);
			goLive(TEST_CATALOG);

			final Evita evita = EntityEnrichmentVersionGuardFunctionalTest.this.evita;
			final EvitaSessionContract writeSession = evita.createReadWriteSession(TEST_CATALOG);
			final SealedEntity rolledBack = writeAndFetchWidest(writeSession, CHANGED_CODE);
			writeSession.setRollbackOnly();
			// closing through the progress stage is what settles the rollback server-side before the reads below
			awaitSettled(writeSession);

			try (final EvitaSessionContract readSession = evita.createReadOnlySession(TEST_CATALOG)) {
				// the rollback really did discard the write - storage still holds the original value
				assertEquals(ORIGINAL_CODE, readWidest(readSession).getAttribute(ATTRIBUTE_CODE, String.class));

				final SealedEntity enriched = readSession.enrichEntity(
					rolledBack, attributeContentAll(), dataInLocalesAll()
				);

				// a rolled-back transaction never moves the catalog version either, so the version alone could
				// never tell this decorator apart from a committed one - what refuses it is that a decorator
				// produced inside a transaction carries no provenance to match against in the first place
				assertNotSame(rolledBack, enriched);
				assertEquals(ORIGINAL_CODE, enriched.getAttribute(ATTRIBUTE_CODE, String.class));
			}
		}
	}

	@Nested
	@DisplayName("Two catalogs at the same version")
	class AcrossCatalogs {

		@DisplayName("Decorator belonging to one catalog is refused by a session for another catalog")
		@Test
		void shouldRefuseADecoratorBelongingToAnotherCatalog() {
			// both catalogs are built by the very same sequence of commits, so they end up at the same version -
			// the distinct attribute value is seeded during warm-up rather than by an extra transaction
			createCatalogWithSingleProduct(TEST_CATALOG, ORIGINAL_CODE);
			createCatalogWithSingleProduct(SECOND_CATALOG, SECOND_CATALOG_CODE);
			goLive(TEST_CATALOG);
			goLive(SECOND_CATALOG);

			final Evita evita = EntityEnrichmentVersionGuardFunctionalTest.this.evita;
			try (final EvitaSessionContract firstSession = evita.createReadOnlySession(TEST_CATALOG);
			     final EvitaSessionContract secondSession = evita.createReadOnlySession(SECOND_CATALOG)) {

				final SealedEntity fromFirst = readWidest(firstSession);
				assertEquals(ORIGINAL_CODE, fromFirst.getAttribute(ATTRIBUTE_CODE, String.class));
				assertEquals(
					SECOND_CATALOG_CODE,
					readWidest(secondSession).getAttribute(ATTRIBUTE_CODE, String.class)
				);

				// the whole point of the scenario: both catalogs number their versions from zero, independently
				assertEquals(firstSession.getCatalogVersion(), secondSession.getCatalogVersion());
				assertEquals(
					secondSession.getCatalogVersion(),
					((ServerEntityDecorator) fromFirst).getCatalogVersion()
				);

				// the entity versions of the two products coincide as well, so the enrichment would happily reuse
				// the first catalog's already-loaded parts and surface them here - the catalog identity the
				// decorator carries is the only thing that can tell the two apart
				assertThrows(
					EvitaInvalidUsageException.class,
					() -> secondSession.enrichEntity(fromFirst, attributeContentAll(), dataInLocalesAll())
				);
			}
		}
	}

	@Nested
	@DisplayName("Enrichment shortcut")
	class EnrichmentShortcut {

		@DisplayName("Entity returned by a query takes the shortcut when it widens nothing")
		@Test
		void shouldTakeTheShortcutForAnEntityReturnedByAQuery() {
			createCatalogWithSingleProduct(TEST_CATALOG, ORIGINAL_CODE);
			goLive(TEST_CATALOG);

			EntityEnrichmentVersionGuardFunctionalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					final SealedEntity fetched = readWidest(session);
					final SealedEntity enriched = session.enrichEntity(
						fetched, attributeContentAll(), dataInLocalesAll()
					);

					// `limitEntity` wraps every query result in narrowing predicates that keep the unnarrowed ones
					// as their `underlyingPredicate`, and that is precisely why the shortcut has to compare a
					// richer copy of each *narrowing* predicate against the narrowing predicate it was copied from.
					// Comparing it against `getXPredicate()` - which answers with the underlying, wider instance -
					// pits two objects that can never be the same against each other, and made the shortcut
					// unreachable through the public enrichment entry point for every entity a query returned.
					// The same instance coming back is the whole proof, because falling through to the storage
					// builds a new decorator even when it fetches nothing.
					assertSame(fetched, enriched);
					assertEquals(
						ORIGINAL_CODE,
						enriched.getAttribute(ATTRIBUTE_CODE, String.class)
					);
				}
			);
		}

		@DisplayName("Entity returned by a query still falls through when the request widens it")
		@Test
		void shouldNotTakeTheShortcutWhenTheRequestWidensAnEntityReturnedByAQuery() {
			createCatalogWithSingleProduct(TEST_CATALOG, ORIGINAL_CODE);
			goLive(TEST_CATALOG);

			EntityEnrichmentVersionGuardFunctionalTest.this.evita.queryCatalog(
				TEST_CATALOG,
				session -> {
					// narrower than the schema allows, so the enrichment below genuinely widens it
					final SealedEntity fetched = session
						.getEntity(Entities.PRODUCT, PRODUCT_PK)
						.orElseThrow();
					final SealedEntity enriched = session.enrichEntity(
						fetched, attributeContentAll(), dataInLocalesAll()
					);

					// a request that asks for more than the entity applies produces fresh predicates, so the
					// shortcut declines and the widened data really do arrive
					assertNotSame(fetched, enriched);
					assertEquals(
						ORIGINAL_CODE,
						enriched.getAttribute(ATTRIBUTE_CODE, String.class)
					);
				}
			);
		}
	}

	/**
	 * Closes the session and blocks until the server finished processing its outcome, so that the state the following
	 * assertions read is settled. A user-requested rollback settles the stage exceptionally, which is expected here
	 * and deliberately absorbed.
	 *
	 * @param session session whose outcome is awaited
	 */
	private static void awaitSettled(@Nonnull EvitaSessionContract session) {
		final CommitProgress progress = session.closeNowWithProgress();
		assertNotNull(progress);
		progress.onChangesVisible().toCompletableFuture().handle((versions, throwable) -> null).join();
	}

}
