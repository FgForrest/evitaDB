/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.api.functional.indexing;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.dataType.Scope;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityPrimaryKeyInSet;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.referenceHaving;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Tests that a reference gaining an indexed scope actually indexes data written into that scope.
 *
 * # What is being guarded
 *
 * A reference's indexed **components** are what decide whether reduced indexes are built for it at all; the
 * {@link ReferenceIndexType} only governs how much is mirrored into them. A scope that carries an index type
 * and an empty component set is therefore not a half-configured reference but a **silent** one: it reports
 * itself indexed, `referenceHaving` still resolves against whatever indexes already existed, and every write
 * after the change goes unindexed. Nothing fails, and the symptom surfaces later as missing rows.
 *
 * The state is reachable through ordinary schema evolution, because `SetReferenceSchemaIndexedMutation` treats
 * its components argument as all-or-nothing: absent means "derive defaults for every indexed scope", present
 * means "take this verbatim", and there is no per-scope fallback in between. So a reference that already
 * carries components in one scope and is then indexed in a second gets an index type for the second scope and
 * no components — see `InternalSchemaBuilderHelper#indexedForTypeInScope`, which forwards the reference's
 * current components whenever any exist.
 *
 * # Why a direct reference
 *
 * The defect was first seen through a reflected reference, whose inherited components collapse an empty set to
 * {@link ReferenceIndexType#NONE} and make the symptom loud. That was the messenger, not the cause: the
 * builder helper and the mutation are shared, so a direct reference reaches the same state and is the more
 * common way to arrive there. The reflected case belongs in `ReflectedReferenceIndexingTest` as a second
 * witness; this is the general one.
 *
 * @author Claude (issue #1585), FG Forrest a.s. (c) 2026
 */
@DisplayName("Reference scope indexing")
@Tag(CONTRACT)
@Tag(INDEXING)
@Tag(REFERENCE)
class ReferenceScopeIndexingTest implements EvitaTestSupport {
	private static final String REFERENCE_CATEGORIES = "categories";
	private static final int CATEGORY_PK = 1;
	private static final int PRODUCT_PK = 100;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ReferenceScopeIndexingTest");
		this.evita = new Evita(getEvitaConfiguration());
		this.evita.defineCatalog(TEST_CATALOG);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("a reference gaining a scope indexes the data written into it")
	void referenceGainingAScopeIndexesDataWrittenIntoIt() {
		// The reference starts indexed in ARCHIVED alone, so it already carries components there. LIVE is then
		// added - the plainest schema evolution there is - and everything written afterwards lands in LIVE.
		defineSchema(Scope.ARCHIVED);
		setCategoriesIndexedInScopes(Scope.ARCHIVED, Scope.LIVE);

		assertFalse(
			categoriesSchema().getIndexedComponents(Scope.LIVE).isEmpty(),
			"LIVE was just indexed, so it must carry indexed components - a scope with an index type and none "
				+ "of them builds no index at all, and the writes below reach nothing"
		);

		writeProductInCategory();

		assertEquals(
			1, countProductsInCategory(),
			"the product written after LIVE was indexed must be findable through the reference"
		);
	}

	@Test
	@DisplayName("a reference whose scope is withdrawn and restored indexes the data written afterwards")
	void referenceWithARestoredScopeIndexesDataWrittenAfterwards() {
		// The same hole reached the other way round, and the way it was first observed: LIVE is withdrawn and
		// given back. ARCHIVED stays indexed throughout, which is what leaves components behind for the
		// restoring mutation to forward in LIVE's place.
		defineSchema(Scope.ARCHIVED, Scope.LIVE);
		setCategoriesIndexedInScopes(Scope.ARCHIVED);
		setCategoriesIndexedInScopes(Scope.ARCHIVED, Scope.LIVE);

		assertFalse(
			categoriesSchema().getIndexedComponents(Scope.LIVE).isEmpty(),
			"LIVE is indexed again, so its components must have come back with it - restoring the index type "
				+ "alone leaves the reference indexed in name only"
		);

		writeProductInCategory();

		assertEquals(
			1, countProductsInCategory(),
			"the product written after LIVE was indexed again must be findable through the reference"
		);
	}

	/**
	 * Creates both collections and declares `categories` indexed for filtering and partitioning in exactly the
	 * given scopes.
	 *
	 * @param indexedScopes the scopes the reference is indexed in
	 */
	private void defineSchema(@Nonnull Scope... indexedScopes) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.CATEGORY).updateVia(session);
				session.defineEntitySchema(Entities.PRODUCT)
					.withReferenceToEntity(
						REFERENCE_CATEGORIES, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(indexedScopes)
					)
					.updateVia(session);
			}
		);
	}

	/**
	 * Re-declares `categories` as indexed for filtering and partitioning in exactly the given scopes, on a schema
	 * that already exists — which is the path a client takes to widen or narrow an existing reference, and the
	 * one that forwards the reference's surviving components into the mutation.
	 *
	 * @param indexedScopes the scopes the reference is indexed in after this call
	 */
	private void setCategoriesIndexedInScopes(@Nonnull Scope... indexedScopes) {
		this.evita.updateCatalog(
			TEST_CATALOG,
			(Consumer<EvitaSessionContract>) session -> session
				.getEntitySchemaOrThrowException(Entities.PRODUCT)
				.openForWrite()
				.withReferenceToEntity(
					REFERENCE_CATEGORIES, Entities.CATEGORY, Cardinality.ZERO_OR_MORE,
					whichIs -> whichIs.indexedForFilteringAndPartitioningInScope(indexedScopes)
				)
				.updateVia(session)
		);
	}

	/**
	 * Writes one category and one product referencing it, both in the `LIVE` scope.
	 */
	private void writeProductInCategory() {
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.createNewEntity(Entities.CATEGORY, CATEGORY_PK).upsertVia(session);
				session.createNewEntity(Entities.PRODUCT, PRODUCT_PK)
					.setReference(REFERENCE_CATEGORIES, CATEGORY_PK)
					.upsertVia(session);
			}
		);
	}

	/**
	 * Counts the products the index reports in the category, which is the reader the indexing exists for.
	 *
	 * @return number of products found through the reference
	 */
	private int countProductsInCategory() {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			(Function<EvitaSessionContract, Integer>) session -> session.query(
				query(
					collection(Entities.PRODUCT),
					filterBy(
						referenceHaving(REFERENCE_CATEGORIES, entityPrimaryKeyInSet(CATEGORY_PK))
					),
					require(page(1, 1))
				),
				EntityReference.class
			).getTotalRecordCount()
		);
	}

	/**
	 * Reads the current schema of the `categories` reference.
	 *
	 * @return the reference schema
	 */
	@Nonnull
	private ReferenceSchemaContract categoriesSchema() {
		return this.evita.queryCatalog(
			TEST_CATALOG,
			(Function<EvitaSessionContract, ReferenceSchemaContract>) session ->
				session.getEntitySchemaOrThrowException(Entities.PRODUCT)
					.getReference(REFERENCE_CATEGORIES)
					.orElseThrow()
		);
	}

	/**
	 * Builds the engine configuration for this test's isolated storage.
	 *
	 * @return the configuration
	 */
	@Nonnull
	private EvitaConfiguration getEvitaConfiguration() {
		return newTestEvitaConfigurationBuilder(this.paths).build();
	}
}
