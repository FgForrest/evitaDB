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

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.query.require.EntityContentRequire;
import io.evitadb.api.requestResponse.EntityFetchAwareDecorator;
import io.evitadb.api.requestResponse.EvitaResponse;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.TestTags;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.hierarchyContent;
import static io.evitadb.api.query.QueryConstraints.referenceContent;
import static io.evitadb.api.query.QueryConstraints.referenceContentAll;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.test.TestConstants.TEST_CATALOG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins what the two I/O statistics of a response mean when a read is answered from memory rather than from the
 * storage.
 *
 * The engine reports two numbers that are deliberately not the same thing: the response total is what the query cost
 * the **storage**, while each entity reports what it would have cost fetched **on its own**. A read-after-write within
 * one session is the shape that tells them apart - the part comes back from the changes the session itself trapped, so
 * the entity needed it and the disk never moved.
 *
 * Every scenario writes, so this class builds its own embedded instance rather than borrowing a shared read-only
 * dataset.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Entity IO statistics")
@Tag(TestTags.ENGINE)
@Tag(TestTags.QUERY)
@Tag(TestTags.REFERENCE)
class EntityIoStatisticsFunctionalTest implements EvitaTestSupport {
	private static final String REFERENCE_CATEGORIES = "categories";
	private static final String REFERENCE_PRODUCTS = "products";
	private static final String REFERENCE_RELATED_PRODUCTS = "relatedProducts";
	private static final int CATEGORY_PK = 1;
	private static final int CHILD_CATEGORY_PK = 2;
	private static final int PRODUCT_PK = 1;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("EntityIoStatisticsFunctionalTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// a reflected reference is what makes writing the product touch the category as well - and it does
				// so through an implicit mutation, which is the write that traps its part in memory
				session.defineEntitySchema(Entities.CATEGORY)
					.withoutGeneratedPrimaryKey()
					/* a category that is both a child and a referenced entity is the shape that tells a decorator
					   re-attaching a parent apart from one attaching references */
					.withHierarchy()
					.withReflectedReferenceToEntity(
						REFERENCE_PRODUCTS, Entities.PRODUCT, REFERENCE_CATEGORIES,
						whichIs -> whichIs.withAttributesInherited()
					)
					.updateVia(session);
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withReferenceToEntity(
						REFERENCE_CATEGORIES, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning()
					)
					/* a reference back into the same collection is what lets an entity reach itself */
					.withReferenceToEntity(
						REFERENCE_RELATED_PRODUCTS, Entities.PRODUCT, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioning()
					)
					.updateVia(session);
				session.upsertEntity(session.createNewEntity(Entities.CATEGORY, CATEGORY_PK));
				session.upsertEntity(
					session.createNewEntity(Entities.CATEGORY, CHILD_CATEGORY_PK).setParent(CATEGORY_PK)
				);
			}
		);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@DisplayName("A read served by the session's own trapped changes costs the storage nothing")
	@Test
	void shouldNotBillAReadAfterWriteAsPhysicalIo() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				// writing the product emits an implicit mutation on the category, which traps the category's
				// parts in memory instead of writing them out
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, PRODUCT_PK)
						.setReference(REFERENCE_CATEGORIES, CATEGORY_PK)
				);

				final EvitaResponse<SealedEntity> bodyOnly = fetchCategory(session, false);
				final EvitaResponse<SealedEntity> withReferences = fetchCategory(session, true);

				// the entity needed the part and reports it - what it would have cost fetched on its own does not
				// depend on who happens to be holding it
				assertEquals(
					1,
					ioFetchCountOf(withReferences) - ioFetchCountOf(bodyOnly),
					"The entity has to report the reference part it was composed from."
				);
				// the storage, on the other hand, was never touched: every part came back from the changes this
				// very session trapped
				assertEquals(
					0, withReferences.getIoFetchCount(),
					"A part answered from the session's own trapped changes performs no storage read."
				);
				assertEquals(
					0, withReferences.getIoFetchedSizeBytes(),
					"A part answered from the session's own trapped changes reads no Bytes."
				);
				return null;
			}
		);

		// the control the zeroes above are only meaningful against: the very same queries against the very same
		// data, once the write is settled and the parts have to come off the storage again
		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EvitaResponse<SealedEntity> bodyOnly = fetchCategory(session, false);
				final EvitaResponse<SealedEntity> withReferences = fetchCategory(session, true);

				assertTrue(
					bodyOnly.getIoFetchCount() > 0,
					"Reading the settled entity has to reach the storage, or the zeroes above prove nothing."
				);
				assertEquals(
					1,
					withReferences.getIoFetchCount() - bodyOnly.getIoFetchCount(),
					"Reading the settled reference part has to reach the storage exactly once."
				);
				return null;
			}
		);
	}

	/**
	 * Fetches the single seeded category, with or without its reference part.
	 *
	 * @param session       session to read through
	 * @param withReferences whether the reference part is to be read as well
	 * @return the response, carrying the I/O statistics the engine measured for it
	 */
	@Nonnull
	private static EvitaResponse<SealedEntity> fetchCategory(
		@Nonnull EvitaSessionContract session,
		boolean withReferences
	) {
		final EvitaResponse<SealedEntity> response = session.querySealedEntity(
			query(
				collection(Entities.CATEGORY),
				filterBy(entityPrimaryKeyInSet(CATEGORY_PK)),
				require(withReferences ? entityFetch(referenceContentAll()) : entityFetch())
			)
		);
		assertEquals(1, response.getRecordData().size());
		return response;
	}

	/**
	 * Returns what the single entity of the passed response reports having cost.
	 *
	 * @param response response to read the entity off
	 * @return the entity's own fetch count
	 */
	private static int ioFetchCountOf(@Nonnull EvitaResponse<SealedEntity> response) {
		return assertInstanceOf(
			EntityFetchAwareDecorator.class, response.getRecordData().get(0)
		).getIoFetchCount();
	}

	/**
	 * Pins that re-attaching a parent does not lose what the referenced bodies cost.
	 *
	 * A decorator that narrows an entity it was handed attaches no references of its own - it exposes the ones it
	 * inherited - so the cost of everything those reach is owed by the entity it wraps. A decorator that is *also*
	 * handed a parent body must still inherit that, because attaching a parent says nothing about the references.
	 * Conflating the two makes an enrichment of a hierarchical entity silently drop every referenced body, which is
	 * the one shape the shipped datasets cannot express: their hierarchical collection has no references at all.
	 */
	@DisplayName("An enrichment that re-attaches a parent keeps what the referenced bodies cost")
	@Test
	void shouldKeepReferencedBodiesWhenAnEnrichmentReAttachesTheParent() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, PRODUCT_PK)
						.setReference(REFERENCE_CATEGORIES, CHILD_CATEGORY_PK)
				);
				return null;
			}
		);

		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EntityContentRequire[] richness = {
					hierarchyContent(entityFetch()),
					referenceContent(REFERENCE_PRODUCTS, entityFetch())
				};

				final EntityFetchAwareDecorator inOneFetch = fetchChildCategoryWith(session, richness);
				final EntityFetchAwareDecorator byEnrichment = assertInstanceOf(
					EntityFetchAwareDecorator.class,
					session.enrichEntity((SealedEntity) fetchChildCategoryWith(session), richness)
				);

				assertTrue(
					inOneFetch.getIoFetchCount() > 0,
					"Reading the parent and the referenced body has to cost something, or the parity below proves nothing."
				);
				assertEquals(
					1,
					((SealedEntity) inOneFetch).getReferences(REFERENCE_PRODUCTS).stream()
						.filter(it -> it.getReferencedEntity().isPresent())
						.count(),
					"The fixture has to attach a referenced body, or the parity below proves nothing."
				);
				assertEquals(
					inOneFetch.getIoFetchCount(),
					byEnrichment.getIoFetchCount(),
					"Reaching a richness by enrichment must cost what reaching it in one fetch costs."
				);
				assertEquals(
					inOneFetch.getIoFetchedBytes(),
					byEnrichment.getIoFetchedBytes(),
					"Reaching a richness by enrichment must read what reaching it in one fetch reads."
				);
				return null;
			}
		);
	}

	/**
	 * Fetches the seeded child category - the one that has both a parent and a referenced body.
	 *
	 * @param session      session to read through
	 * @param requirements richness to fetch the category at
	 * @return the entity, as the decorator that carries its I/O statistics
	 */
	@Nonnull
	private static EntityFetchAwareDecorator fetchChildCategoryWith(
		@Nonnull EvitaSessionContract session,
		@Nonnull EntityContentRequire... requirements
	) {
		final EvitaResponse<SealedEntity> response = session.querySealedEntity(
			query(
				collection(Entities.CATEGORY),
				filterBy(entityPrimaryKeyInSet(CHILD_CATEGORY_PK)),
				require(entityFetch(requirements))
			)
		);
		assertEquals(1, response.getRecordData().size());
		return assertInstanceOf(EntityFetchAwareDecorator.class, response.getRecordData().get(0));
	}

	/**
	 * Pins that an entity reaching itself is counted once.
	 *
	 * The aggregate adds the owner's own parts to the parts of everything it reaches. An entity that references
	 * itself - or a nesting that closes the loop a level further down - puts a view of the owner into the owner's
	 * own reachable set, and the two are views of one entity rather than two entities. Summing them bills the
	 * entity's body once for being the owner and a second time for being its own referenced entity.
	 */
	@DisplayName("An entity that references itself counts its own body once")
	@Test
	void shouldCountAnEntityReachingItselfExactlyOnce() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, PRODUCT_PK)
						.setReference(REFERENCE_RELATED_PRODUCTS, PRODUCT_PK)
				);
				return null;
			}
		);

		this.evita.queryCatalog(
			TEST_CATALOG,
			session -> {
				final EntityFetchAwareDecorator withoutTheBody = fetchProductWith(
					session, referenceContent(REFERENCE_RELATED_PRODUCTS)
				);
				final EntityFetchAwareDecorator withTheBody = fetchProductWith(
					session, referenceContent(REFERENCE_RELATED_PRODUCTS, entityFetch())
				);

				assertTrue(
					withoutTheBody.getIoFetchCount() > 0,
					"Reading the entity and its reference part has to cost something, or the parity below proves nothing."
				);
				assertEquals(
					withoutTheBody.getIoFetchCount(),
					withTheBody.getIoFetchCount(),
					"The body an entity reaches through itself is the body it already read - it costs nothing twice."
				);
				assertEquals(
					withoutTheBody.getIoFetchedBytes(),
					withTheBody.getIoFetchedBytes(),
					"The body an entity reaches through itself reads no Bytes twice."
				);
				return null;
			}
		);
	}

	/**
	 * Fetches the seeded product at the passed richness.
	 *
	 * @param session      session to read through
	 * @param requirements richness to fetch the product at
	 * @return the entity, as the decorator that carries its I/O statistics
	 */
	@Nonnull
	private static EntityFetchAwareDecorator fetchProductWith(
		@Nonnull EvitaSessionContract session,
		@Nonnull EntityContentRequire... requirements
	) {
		final EvitaResponse<SealedEntity> response = session.querySealedEntity(
			query(
				collection(Entities.PRODUCT),
				filterBy(entityPrimaryKeyInSet(PRODUCT_PK)),
				require(entityFetch(requirements))
			)
		);
		assertEquals(1, response.getRecordData().size());
		return assertInstanceOf(EntityFetchAwareDecorator.class, response.getRecordData().get(0));
	}

}
