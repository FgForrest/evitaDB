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

import io.evitadb.api.configuration.EvitaConfiguration;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.core.Evita;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.ReferencedEntityPredecessor;
import io.evitadb.index.attribute.ChainIndex;
import io.evitadb.spi.store.catalog.header.model.CatalogHeader;
import io.evitadb.spi.store.catalog.persistence.PersistenceService;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStorageKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.ChainIndexStoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.EntityIndexStoragePart;
import io.evitadb.store.catalog.Migration_2025_6.NoChangeHeaderInfoSupplier;
import io.evitadb.store.model.header.CollectionFileReference;
import io.evitadb.store.model.reference.LogFileRecordReference;
import io.evitadb.store.shared.model.FileLocation;
import io.evitadb.test.EvitaTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.evitadb.store.catalog.OfflineCatalogImages.bootstrapRecords;
import static io.evitadb.store.catalog.OfflineCatalogImages.lastBootstrap;
import static io.evitadb.store.catalog.OfflineCatalogImages.stamp;
import static io.evitadb.store.catalog.OfflineCatalogImages.withOfflineCatalog;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.test.TestTags.STORAGE;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Builds a protocol-6 image with qualified and unqualified localized reference predecessor keys and an
 * entity predecessor of the same name in the same partition. The catalog is written through its storage parts.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(STORAGE)
@Tag(REFERENCE)
@DisplayName("Referenced entity predecessor cleanup during the released storage upgrade")
class Migration_2026_3_ReferencedEntityPredecessorCleanupTest implements EvitaTestSupport {
	private static final String CATALOG = "predecessorCleanup";
	private static final String PRODUCT = "product";
	private static final String BRAND = "brand";
	private static final String POSITION = "position";
	private static final AttributeIndexKey LEGACY = new AttributeIndexKey(BRAND, POSITION, Locale.ENGLISH);
	private static final AttributeIndexKey UNQUALIFIED = new AttributeIndexKey(null, POSITION, Locale.ENGLISH);
	private static final AttributeIndexKey RETAINED = new AttributeIndexKey(null, POSITION, null);

	/** Verifies one publication, full reclamation of a paged chain and preservation of a same-named chain. */
	@Test
	@DisplayName("One upgrade publication drops the reference chain root and pages and keeps the entity chain")
	void shouldRemovePagedReferenceChainAndRetainEntityChain() {
		final TestPaths paths = createTestPaths(CATALOG);
		try {
			final EvitaConfiguration configuration = newTestEvitaConfigurationBuilder(paths).build();
			writeCatalog(configuration);
			final LegacyChain image = writeLegacyChain(configuration);
			stamp(configuration, CATALOG, 6, 6);
			final int before = bootstrapRecords(configuration, CATALOG).size();

			withOfflineCatalog(configuration, CATALOG, service -> {
				final long version = service.getLastCatalogVersion();
				final OffsetIndexStoragePartPersistenceService parts =
					productCollection(service, version).getStoragePartPersistenceService();
				final EntityIndexStoragePart manifest = requireNonNull(
					parts.getStoragePart(version, image.indexId(), EntityIndexStoragePart.class)
				);
				final AttributeIndexStorageKey retained = new AttributeIndexStorageKey(
					manifest.getEntityIndexKey(), AttributeIndexType.CHAIN, RETAINED
				);
				assertEquals(Set.of(retained), manifest.getAttributeIndexes());
				assertNull(parts.getStoragePart(version, image.rootId(), ChainIndexStoragePart.class));
				assertNull(parts.getStoragePart(version, image.unqualifiedRootId(), ChainIndexStoragePart.class));
				for (final long pageId : image.leafIds()) {
					assertNull(parts.getStoragePart(version, pageId, ChainIndexLeafPagePart.class));
				}
				assertNotNull(parts.getStoragePart(version, image.retainedRootId(), ChainIndexStoragePart.class));
				return Boolean.TRUE;
			});
			assertEquals(before + 1, bootstrapRecords(configuration, CATALOG).size());
			assertEquals(9, lastBootstrap(configuration, CATALOG).storageProtocolVersion());
		} finally {
			cleanupTestPaths(paths);
		}
	}

	/**
	 * Writes an always-materialized partition with an entity-level predecessor.
	 *
	 * @param configuration engine configuration for the test image
	 */
	private static void writeCatalog(@Nonnull final EvitaConfiguration configuration) {
		try (final Evita evita = new Evita(configuration)) {
			evita.defineCatalog(CATALOG);
			evita.updateCatalog(CATALOG, session -> {
				session.defineEntitySchema(BRAND).updateVia(session);
				session.defineEntitySchema(PRODUCT)
					.withLocale(Locale.ENGLISH)
					.withAttribute(POSITION, Predecessor.class, attribute -> attribute.sortable())
					.withReferenceToEntity(BRAND, BRAND, Cardinality.ZERO_OR_MORE, reference -> reference
						.indexedForFilteringAndPartitioning()
						.withAttribute(POSITION, ReferencedEntityPredecessor.class, attribute ->
							attribute.localized().sortable()))
					.updateVia(session);
				session.createNewEntity(BRAND, 1).upsertVia(session);
				session.createNewEntity(PRODUCT, 1)
					.setAttribute(POSITION, Predecessor.HEAD)
					.setReference(BRAND, 1, reference -> reference.setAttribute(
						POSITION, Locale.ENGLISH, ReferencedEntityPredecessor.HEAD
					))
					.upsertVia(session);
				session.goLiveAndClose();
			});
		}
	}

	/**
	 * Adds a paged reference chain to the partition's manifest and publishes the hand-built footprint.
	 *
	 * @param configuration engine configuration for the test image
	 * @return the published chain root and leaf addresses
	 */
	@Nonnull
	private static LegacyChain writeLegacyChain(@Nonnull final EvitaConfiguration configuration) {
		final int catalogFileIndex = lastBootstrap(configuration, CATALOG).catalogFileIndex();
		return withOfflineCatalog(configuration, CATALOG, service -> {
			final long version = service.getLastCatalogVersion();
			final CatalogOffsetIndexStoragePartPersistenceService catalogParts =
				service.getStoragePartPersistenceService(version);
			final CatalogHeader<LogFileRecordReference, CollectionFileReference> header =
				catalogParts.getCatalogHeader(version);
			final DefaultEntityCollectionPersistenceService collection = productCollection(service, version);
			final OffsetIndexStoragePartPersistenceService parts = collection.getStoragePartPersistenceService();
			final EntityIndexStoragePart manifest = collection.getEntityCollectionHeader().usedEntityIndexPrimaryKeys()
				.stream().map(id -> requireNonNull(parts.getStoragePart(version, id, EntityIndexStoragePart.class)))
				.filter(index -> index.getEntityIndexKey().type() == EntityIndexType.REFERENCED_ENTITY)
				.findFirst().orElseThrow();
			final LegacyChain image = writeChainParts(version, parts, manifest);

			final FileLocation location = collection.flush(
				version, new NoChangeHeaderInfoSupplier(collection.getEntityCollectionHeader())
			).fileLocation();
			catalogParts.putStoragePart(version, collection.getEntityCollectionHeader());
			final Map<String, CollectionFileReference> collections = new HashMap<>(header.collectionFileIndex());
			final CollectionFileReference reference = requireNonNull(collections.get(PRODUCT));
			collections.put(PRODUCT, new CollectionFileReference(
				PRODUCT, reference.entityTypePrimaryKey(), reference.fileIndex(), location
			));
			catalogParts.writeCatalogHeader(
				PersistenceService.STORAGE_PROTOCOL_VERSION, version, service.getCatalogStoragePath(),
				header.walFileReference(), collections, header.catalogId(), header.catalogName(),
				header.catalogState(), header.lastEntityCollectionPrimaryKey()
			);
			service.recordBootstrap(version, CATALOG, catalogFileIndex, null);
			return image;
		});
	}

	/**
	 * Writes the paged chain through the normal chain serializer and adds its manifest key.
	 *
	 * @param version published catalog version
	 * @param parts collection offset index
	 * @param manifest owning partition manifest
	 * @return the chain footprint and the existing chain to preserve
	 */
	@Nonnull
	private static LegacyChain writeChainParts(
		final long version, @Nonnull final OffsetIndexStoragePartPersistenceService parts,
		@Nonnull final EntityIndexStoragePart manifest
	) {
		final int indexId = manifest.getPrimaryKey();
		final AttributeIndexStorageKey retained = new AttributeIndexStorageKey(
			manifest.getEntityIndexKey(), AttributeIndexType.CHAIN, RETAINED
		);
		assertEquals(Set.of(retained), manifest.getAttributeIndexes());
		final long retainedRoot = AttributeIndexStoragePart.computeUniquePartId(
			indexId, AttributeIndexType.CHAIN, RETAINED, parts.getReadOnlyKeyCompressor()
		);
		assertNotNull(parts.getStoragePart(version, retainedRoot, ChainIndexStoragePart.class));

		final ChainIndex chain = new ChainIndex(LEGACY);
		chain.upsertPredecessor(ReferencedEntityPredecessor.HEAD, 1);
		for (int key = 2; key <= 3200; key++) {
			chain.upsertPredecessor(new ReferencedEntityPredecessor(key - 1), key);
		}
		final TrappedChanges changes = new TrappedChanges();
		chain.appendStorageParts(indexId, changes);
		final List<Long> leafIds = new ArrayList<>();
		final Iterator<StoragePart> iterator = changes.getTrappedChangesIterator();
		long rootId = -1;
		while (iterator.hasNext()) {
			final StoragePart part = iterator.next();
			final long id = parts.putStoragePart(version, part);
			if (part instanceof final ChainIndexStoragePart root) {
				assertTrue(root.isPaged());
				rootId = id;
			} else {
				assertInstanceOf(ChainIndexLeafPagePart.class, part);
				leafIds.add(id);
			}
		}
		assertNotEquals(-1L, rootId);
		assertTrue(leafIds.size() > 1);
		final ChainIndex unqualified = new ChainIndex(UNQUALIFIED);
		unqualified.upsertPredecessor(ReferencedEntityPredecessor.HEAD, 1);
		final TrappedChanges unqualifiedChanges = new TrappedChanges();
		unqualified.appendStorageParts(indexId, unqualifiedChanges);
		final ChainIndexStoragePart unqualifiedRoot = assertInstanceOf(
			ChainIndexStoragePart.class, unqualifiedChanges.getTrappedChangesIterator().next()
		);
		final long unqualifiedRootId = parts.putStoragePart(version, unqualifiedRoot);
		final Set<AttributeIndexStorageKey> keys = new HashSet<>(manifest.getAttributeIndexes());
		keys.add(new AttributeIndexStorageKey(manifest.getEntityIndexKey(), AttributeIndexType.CHAIN, LEGACY));
		keys.add(new AttributeIndexStorageKey(manifest.getEntityIndexKey(), AttributeIndexType.CHAIN, UNQUALIFIED));
		parts.putStoragePart(version, new EntityIndexStoragePart(
			indexId, manifest.getVersion(), manifest.getEntityIndexKey(), keys, manifest.getPriceIndexes(),
			manifest.isHierarchyIndex(), manifest.getFacetIndexes(), manifest.getHistogramIndexes()
		));
		return new LegacyChain(indexId, rootId, List.copyOf(leafIds), retainedRoot, unqualifiedRootId);
	}

	/**
	 * Opens the product collection at the published file reference.
	 *
	 * @param service catalog persistence service
	 * @param version published catalog version
	 * @return the product collection service
	 */
	@Nonnull
	private static DefaultEntityCollectionPersistenceService productCollection(
		@Nonnull final DefaultCatalogPersistenceService service, final long version
	) {
		final CollectionFileReference reference = requireNonNull(
			service.getCatalogHeader(version).collectionFileIndex().get(PRODUCT)
		);
		return service.getOrCreateEntityCollectionPersistenceService(
			version, PRODUCT, reference.entityTypePrimaryKey()
		);
	}

	/**
	 * Identifies the roots and pages written into the hand-built partition.
	 *
	 * @param indexId partition primary key
	 * @param rootId reference chain root
	 * @param leafIds reference chain leaves
	 * @param retainedRootId entity chain root
	 * @param unqualifiedRootId reference chain root with an unqualified key
	 */
	private record LegacyChain(
		int indexId, long rootId, @Nonnull List<Long> leafIds, long retainedRootId, long unqualifiedRootId
	) {
	}
}
