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

package io.evitadb.index.virtual;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.query.order.OrderDirection;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.statistics.BrowsedIndex;
import io.evitadb.api.statistics.IndexBrowseCriteria;
import io.evitadb.api.statistics.IndexBrowseOrdering;
import io.evitadb.api.statistics.IndexBrowseResult;
import io.evitadb.api.statistics.IndexDetail;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Set;

import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.OBSERVABILITY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins exception E1 of the virtual reading of reference partitions: the physical index inspection - the index browse
 * and the index detail of a collection - reports the physical indexes while they exist, whatever the read switch says.
 * A single-owner partition registered as a virtual one is still a reduced entity index the writes go through, and it
 * is that index, its heap and its activity, that the inspection describes; with the read switch on it answers exactly
 * as with the switch off, and does not fail.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Physical index inspection of virtual reference partitions")
@Tag(ENGINE)
@Tag(OBSERVABILITY)
@Tag(REFERENCE)
class IndexInspectionExceptionTest implements EvitaTestSupport {
	private static final String CATALOG = "indexInspectionExceptionTest";

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("IndexInspectionExceptionTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		// TODO JNO - remove at the end of #1615
		this.evita.createCatalogWithVirtualReferencePartitions(CATALOG, false);
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		for (final String entityType : ENTITY_TYPES) {
			maintenance().setMaintained(entityType, true);
		}
		this.evita.updateCatalog(CATALOG, SingleOwnerPartitionFixture::setUp);
		this.evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("browsing and describing a registered partition with the read switch on reports its physical index")
	void shouldReportThePhysicalIndexOfARegisteredPartition() {
		final EntityCollection products = collectionOf(ENTITY_PRODUCT);
		final EntityIndex partition = products.getIndexByKeyIfExists(
			new EntityIndexKey(
				EntityIndexType.REFERENCED_ENTITY, Scope.LIVE,
				new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_CATEGORIES, 4))
			)
		);
		final int partitionPrimaryKey = partition.getPrimaryKey();
		final ReferencedTypeEntityIndex typeIndex = assertInstanceOf(
			ReferencedTypeEntityIndex.class,
			products.getIndexByKeyIfExists(
				new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, REFERENCE_CATEGORIES)
			)
		);
		assertTrue(
			typeIndex.getVirtualReferencePartitionIndex().getPartitionOwner(partitionPrimaryKey).isPresent(),
			"The premise of the case: the partition is registered as a virtual one"
		);
		final IndexBrowseCriteria criteria = new IndexBrowseCriteria(
			1, IndexBrowseCriteria.MAX_PAGE_SIZE, IndexBrowseOrdering.MAP_ORDER, OrderDirection.ASC,
			Set.of(EntityIndexType.REFERENCED_ENTITY), Set.of(Scope.LIVE), Set.of(REFERENCE_CATEGORIES)
		);
		final IndexDetail objectDetail = products.describeIndex(partitionPrimaryKey);
		final IndexBrowseResult objectBrowse = products.browseIndexes(criteria);

		maintenance().setReadVirtually(ENTITY_PRODUCT, true);
		final IndexDetail readOnDetail = products.describeIndex(partitionPrimaryKey);
		final IndexBrowseResult readOnBrowse = products.browseIndexes(criteria);

		assertEquals(objectDetail, readOnDetail);
		assertEquals(partitionPrimaryKey, readOnDetail.indexPrimaryKey());
		assertEquals(partition.getHeapSizeInBytes(), readOnDetail.heapSizeInBytes(), "The physical index is described");
		assertEquals(objectBrowse, readOnBrowse);
		final BrowsedIndex browsed = Arrays.stream(readOnBrowse.indexes())
			.filter(it -> it.indexPrimaryKey() == partitionPrimaryKey)
			.findFirst()
			.orElseThrow(() -> new AssertionError("The registered partition must be browsed: " + readOnBrowse));
		assertEquals(EntityIndexType.REFERENCED_ENTITY, browsed.indexType());
		assertEquals(Integer.valueOf(1), browsed.entityCount(), "The physical index holds its only owner");
		assertEquals(Integer.valueOf(4), browsed.discriminatorPrimaryKey());
	}

	/**
	 * Returns the collection of an entity type in the current version of the catalog.
	 *
	 * @param entityType the entity type
	 * @return the collection
	 */
	@Nonnull
	private EntityCollection collectionOf(@Nonnull String entityType) {
		return (EntityCollection) this.evita.getCatalogInstance(CATALOG)
			.orElseThrow()
			.getCollectionForEntity(entityType)
			.orElseThrow();
	}

	/**
	 * Returns the switch holder of the catalog.
	 *
	 * @return the switch holder
	 */
	@Nonnull
	private VirtualReferencePartitionMaintenance maintenance() {
		return ((Catalog) this.evita.getCatalogInstance(CATALOG).orElseThrow())
			.getVirtualReferencePartitionMaintenance();
	}

}
