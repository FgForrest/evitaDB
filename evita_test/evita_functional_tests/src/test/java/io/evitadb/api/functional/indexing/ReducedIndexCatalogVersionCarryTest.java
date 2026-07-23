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

package io.evitadb.api.functional.indexing;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.data.PriceInnerRecordHandling;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.EntityIndexType;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.math.BigDecimal;
import java.util.Currency;

import static io.evitadb.api.functional.indexing.IndexingTestSupport.getReferencedEntityIndex;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.PRICE;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.TRANSACTION;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Locks in the clean-collection carry-by-reference contract: when a clean collection is copied for a new catalog
 * version, its reduced entity indexes are forwarded **by reference** rather than re-shelled (a fresh copy) on every
 * version bump.
 *
 * A reduced index holds no catalog back-reference of its own — its price ref chain captures the GLOBAL entity index
 * directly through a `SuperIndexResolver`, and that GLOBAL is carried by reference in the same copy step — so the
 * reduced index can safely survive a version boundary as the very same instance. `goLive` is the deterministic clean
 * version bump used here: it routes every collection through the clean-copy path.
 *
 * Before carry-by-reference this test would fail (the reduced index came back as a fresh re-shelled instance); the
 * assertions therefore pin the alloc-saving mechanism against a silent revert to per-commit re-shelling (the actual
 * allocation win is measured separately by the senesi JMH gate).
 */
@DisplayName("Reduced index carried by reference across a clean catalog version bump")
@Tag(INDEXING)
@Tag(REFERENCE)
@Tag(PRICE)
@Tag(TRANSACTION)
class ReducedIndexCatalogVersionCarryTest implements EvitaTestSupport {

	private static final String PRICE_LIST_BASIC = "basic";
	private static final Currency CURRENCY_EUR = Currency.getInstance("EUR");
	private static final int BRAND_PK = 1;
	private static final int PRODUCT_PK = 1;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("ReducedIndexCatalogVersionCarry");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
	}

	@AfterEach
	void tearDown() {
		if (this.evita != null && this.evita.isActive()) {
			this.evita.close();
		}
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("goLive must forward a reduced index (and its GLOBAL) by reference, not re-shell it")
	void shouldCarryReducedIndexByReferenceAcrossGoLive() {
		// 1) warm-up: a product with a price and an indexed reference to a brand builds a REFERENCED_ENTITY reduced
		//    index (owning a price ref chain wired to the collection's GLOBAL super price index). Do NOT go live yet.
		this.evita.defineCatalog(TEST_CATALOG);
		this.evita.updateCatalog(
			TEST_CATALOG,
			session -> {
				session.defineEntitySchema(Entities.BRAND)
					.withoutGeneratedPrimaryKey()
					.updateVia(session);
				session.defineEntitySchema(Entities.PRODUCT)
					.withoutGeneratedPrimaryKey()
					.withPrice()
					.withReferenceToEntity(
						Entities.BRAND,
						Entities.BRAND,
						Cardinality.ZERO_OR_ONE,
						ReferenceSchemaEditor::indexedForFilteringAndPartitioning
					)
					.updateVia(session);

				session.upsertEntity(session.createNewEntity(Entities.BRAND, BRAND_PK));
				session.upsertEntity(
					session.createNewEntity(Entities.PRODUCT, PRODUCT_PK)
						.setPriceInnerRecordHandling(PriceInnerRecordHandling.NONE)
						.setPrice(1, PRICE_LIST_BASIC, CURRENCY_EUR, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, true)
						.setReference(Entities.BRAND, BRAND_PK)
				);
			}
		);

		// capture the reduced index and its backing GLOBAL while the catalog is still WARMING_UP
		final EntityIndex reducedBefore = referencedEntityIndex();
		final EntityIndex globalBefore = globalEntityIndex();
		assertNotNull(reducedBefore, "The REFERENCED_ENTITY reduced index must exist after the warm-up upsert!");

		// 2) clean version bump: go live routes every (clean) collection through the clean-copy path. Block-bodied
		//    lambda deliberately — a void expression lambda is ambiguous between Consumer and Function here.
		this.evita.updateCatalog(
			TEST_CATALOG,
			EvitaSessionContract::goLiveAndClose
		);

		// 3) the GLOBAL entity index and the reduced index must both be the SAME instances as before the bump —
		//    carried by reference together, so the reduced index's captured GLOBAL stays identity-stable
		final EntityIndex globalAfter = globalEntityIndex();
		final EntityIndex reducedAfter = referencedEntityIndex();
		assertNotNull(reducedAfter, "The REFERENCED_ENTITY reduced index must survive the version bump!");
		assertSame(
			globalBefore, globalAfter,
			"The GLOBAL entity index must be carried across the version bump by reference!"
		);
		assertSame(
			reducedBefore, reducedAfter,
			"The reduced index must be carried across the clean version bump by reference (not re-shelled)!"
		);
	}

	/**
	 * Reaches into the live {@link Entities#PRODUCT} collection and returns the REFERENCED_ENTITY reduced index for the
	 * single indexed brand reference.
	 *
	 * @return the reduced entity index, or {@code null} if it has not been created yet
	 */
	@Nonnull
	private EntityIndex referencedEntityIndex() {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final EntityCollection collection = (EntityCollection) catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
		return getReferencedEntityIndex(collection, Entities.BRAND, BRAND_PK);
	}

	/**
	 * Reaches into the live {@link Entities#PRODUCT} collection and returns its GLOBAL entity index (the owner of the
	 * super price indexes that back the reduced index's price ref chain).
	 *
	 * @return the GLOBAL entity index; never {@code null} for an existing collection
	 */
	@Nonnull
	private EntityIndex globalEntityIndex() {
		final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
		final EntityCollection collection = (EntityCollection) catalog.getCollectionForEntity(Entities.PRODUCT).orElseThrow();
		final EntityIndex globalIndex = collection.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.GLOBAL));
		assertNotNull(globalIndex, "The GLOBAL entity index must exist!");
		return globalIndex;
	}

}
