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

package io.evitadb.index;

import com.github.javafaker.Faker;
import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.EntityReferenceContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.core.Evita;
import io.evitadb.core.buffer.TrappedChanges;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Predecessor;
import io.evitadb.dataType.Scope;
import io.evitadb.index.component.EntityIndexManifest;
import io.evitadb.index.component.IndexComponent;
import io.evitadb.spi.store.catalog.persistence.storageParts.StoragePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexLeafPagePart;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FilterIndexStoragePart;
import io.evitadb.test.Entities;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.test.generator.DataGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.STORAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the contract of the change-detection baseline an {@link EntityIndex} captures without flushing - when it is
 * built, and after every flush: the capture announces the keys of every {@link IndexComponent} and emits nothing, so
 * it neither consumes the pending changes of a component that is dirty at that point nor differs from the manifest
 * the flush itself writes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("The baseline an entity index captures without flushing")
@Tag(INDEXING)
@Tag(STORAGE)
class EntityIndexBaselineCaptureTest implements EvitaTestSupport {
	private static final String ENTITY_TYPE = "Product";
	private static final int INDEX_PK = 1;
	/** Enough distinct values to make the filter tree span several leaves. */
	private static final int PAGED_VALUE_COUNT = 1_000;

	/**
	 * Builds a plain attribute schema of the given type, live in the `LIVE` scope, bypassing the schema builder.
	 *
	 * @param name the attribute name
	 * @param type the attribute value type
	 * @return the attribute schema
	 */
	@Nonnull
	private static AttributeSchemaContract attributeSchema(
		@Nonnull String name, @Nonnull Class<? extends Serializable> type
	) {
		return AttributeSchema._internalBuild(
			name,
			null,
			new Scope[]{Scope.LIVE},
			null,
			null,
			new Scope[]{Scope.LIVE},
			false, false, false,
			type, null,
			ConflictResolutionOverride.INHERITED
		);
	}

	/**
	 * Lets every registered component of the index announce its keys into a fresh manifest - the capture path.
	 *
	 * @param index the entity index
	 * @return the announced manifest
	 */
	@Nonnull
	private static EntityIndexManifest announcedManifest(@Nonnull EntityIndex index) {
		final EntityIndexManifest manifest = new EntityIndexManifest();
		for (final IndexComponent component : index.getRegisteredComponents()) {
			component.announceKeys(manifest);
		}
		return manifest;
	}

	/**
	 * Lets every registered component of the index collect its parts and announce its keys - the flush path.
	 *
	 * @param index the entity index
	 * @param sink  the accumulator of the collected parts
	 * @return the manifest the collect announced
	 */
	@Nonnull
	private static EntityIndexManifest collectedManifest(@Nonnull EntityIndex index, @Nonnull TrappedChanges sink) {
		final EntityIndexManifest manifest = new EntityIndexManifest();
		for (final IndexComponent component : index.getRegisteredComponents()) {
			component.collectModifiedStorageParts(index.getPrimaryKey(), manifest, sink);
		}
		return manifest;
	}

	/**
	 * Asserts that two manifests list the same keys of every kind.
	 *
	 * @param expected the manifest the flush path announced
	 * @param actual   the manifest the capture path announced
	 * @param subject  names the index in the failure message
	 */
	private static void assertSameManifest(
		@Nonnull EntityIndexManifest expected, @Nonnull EntityIndexManifest actual, @Nonnull String subject
	) {
		assertEquals(expected.getAttributeKeys(), actual.getAttributeKeys(), "attribute keys of " + subject);
		assertEquals(expected.getPriceKeys(), actual.getPriceKeys(), "price keys of " + subject);
		assertEquals(
			expected.getFacetReferencedEntities(), actual.getFacetReferencedEntities(), "facet keys of " + subject
		);
		assertEquals(expected.getHistogramKeys(), actual.getHistogramKeys(), "histogram keys of " + subject);
		assertEquals(expected.isHierarchyPresent(), actual.isHierarchyPresent(), "hierarchy presence of " + subject);
	}

	@Nested
	@DisplayName("on a single index")
	class SingleIndexTest {

		@Test
		@DisplayName("leaves every pending leaf page to the flush when the baseline is captured before it")
		void shouldLeaveEveryPendingLeafPageToFlushWhenBaselineIsCapturedFirst() {
			final GlobalEntityIndex index = new GlobalEntityIndex(
				INDEX_PK, ENTITY_TYPE, new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)
			);
			final AttributeSchemaContract code = attributeSchema("code", Integer.class);
			for (int i = 1; i <= PAGED_VALUE_COUNT; i++) {
				index.insertPrimaryKeyIfMissing(i);
				index.insertFilterAttribute(null, code, Collections.emptySet(), null, i, i, false);
			}

			// the capture runs while the filter tree is dirty - exactly what a load-time repair leaves behind
			index.notifyFlushed();
			final TrappedChanges sink = new TrappedChanges();
			index.getModifiedStorageParts(sink);

			int leafPages = 0;
			boolean rootWritten = false;
			final Iterator<StoragePart> parts = sink.getTrappedChangesIterator();
			while (parts.hasNext()) {
				final StoragePart part = parts.next();
				if (part instanceof FilterIndexLeafPagePart) {
					leafPages++;
				} else if (part instanceof FilterIndexStoragePart root) {
					assertTrue(root.isPaged(), "the fixture must page the filter tree");
					assertEquals(leafPages, root.getLeafPageSequences().length, "the root lists every written page");
					rootWritten = true;
				}
			}
			assertTrue(leafPages > 1, "the flush must write every leaf page, it wrote " + leafPages);
			assertTrue(rootWritten, "the flush must write the root listing those pages");
		}

		@Test
		@DisplayName("announces exactly the keys the collect of the flush announces")
		void shouldAnnounceTheKeysTheCollectAnnounces() {
			final GlobalEntityIndex index = new GlobalEntityIndex(
				INDEX_PK, ENTITY_TYPE, new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)
			);
			final Set<Locale> noLocales = Collections.emptySet();
			index.insertPrimaryKeyIfMissing(10);
			index.insertUniqueAttribute(
				null, attributeSchema("code", String.class), noLocales, Scope.LIVE, null, "UNIQUE-VAL", 10
			);
			index.insertFilterAttribute(
				null, attributeSchema("code", String.class), noLocales, null, "UNIQUE-VAL", 10, true
			);
			index.insertSortAttribute(null, attributeSchema("priority", Integer.class), noLocales, null, 42, 10);
			index.insertSortAttribute(
				null, attributeSchema("order", Predecessor.class), noLocales, null, Predecessor.HEAD, 10
			);

			final EntityIndexManifest announced = announcedManifest(index);
			final TrappedChanges sink = new TrappedChanges();
			final EntityIndexManifest collected = collectedManifest(index, sink);

			assertFalse(announced.getAttributeKeys().isEmpty(), "the fixture must populate attribute sub-indexes");
			assertTrue(sink.getTrappedChangesCount() > 0, "the collect of a dirty index must emit its parts");
			assertSameManifest(collected, announced, "a populated global index");
		}

	}

	@Nested
	@DisplayName("on a loaded catalog")
	class LoadedCatalogTest {
		private static final long SEED = 42;
		private final DataGenerator dataGenerator = new DataGenerator();
		private final Map<Serializable, Integer> generatedEntities = new HashMap<>();
		private final BiFunction<String, Faker, Integer> randomEntityPicker = (entityType, faker) -> {
			final Integer entityCount = this.generatedEntities.computeIfAbsent(entityType, serializable -> 0);
			final int primaryKey = entityCount == 0 ? 0 : faker.random().nextInt(1, entityCount);
			return primaryKey == 0 ? null : primaryKey;
		};
		private TestPaths paths;
		private Evita evita;

		@BeforeEach
		void setUp() {
			this.paths = createTestPaths("EntityIndexBaselineCapture");
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
		@DisplayName("finds nothing to write in any index of a healthy load, and announces what the collect announces")
		void shouldFindNothingToWriteAfterHealthyLoad() {
			this.evita.defineCatalog(TEST_CATALOG);
			this.evita.updateCatalog(
				TEST_CATALOG,
				session -> {
					this.dataGenerator.generateEntities(
							this.dataGenerator.getSampleBrandSchema(session), this.randomEntityPicker, SEED
						)
						.limit(5)
						.forEach(it -> createEntity(session.upsertEntity(it)));
					this.dataGenerator.generateEntities(
							this.dataGenerator.getSampleCategorySchema(session), this.randomEntityPicker, SEED
						)
						.limit(10)
						.forEach(it -> createEntity(session.upsertEntity(it)));
					this.dataGenerator.generateEntities(
							this.dataGenerator.getSamplePriceListSchema(session), this.randomEntityPicker, SEED
						)
						.limit(4)
						.forEach(it -> createEntity(session.upsertEntity(it)));
					this.dataGenerator.generateEntities(
							this.dataGenerator.getSampleStoreSchema(session), this.randomEntityPicker, SEED
						)
						.limit(12)
						.forEach(it -> createEntity(session.upsertEntity(it)));
					this.dataGenerator.generateEntities(
							this.dataGenerator.getSampleProductSchema(session), this.randomEntityPicker, SEED
						)
						.limit(300)
						.forEach(session::upsertEntity);
					session.goLiveAndClose();
				}
			);
			this.evita.close();
			this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
			this.evita.waitUntilFullyInitialized();

			final Catalog catalog = (Catalog) this.evita.getCatalogInstance(TEST_CATALOG).orElseThrow();
			int checkedIndexes = 0;
			for (final String entityType : catalog.getEntityTypes()) {
				final EntityCollection collection =
					(EntityCollection) catalog.getCollectionForEntity(entityType).orElseThrow();
				final int lastIndexPrimaryKey = collection.getEntityCollectionHeader().lastEntityIndexPrimaryKey();
				for (int indexPrimaryKey = 1; indexPrimaryKey <= lastIndexPrimaryKey; indexPrimaryKey++) {
					final EntityIndex index = collection.getIndexByPrimaryKeyIfExists(indexPrimaryKey);
					if (index != null) {
						final String subject = entityType + " " + index;
						final EntityIndexManifest announced = announcedManifest(index);
						final TrappedChanges sink = new TrappedChanges();
						final EntityIndexManifest collected = collectedManifest(index, sink);
						assertEquals(0, sink.getTrappedChangesCount(), "a loaded " + subject + " must be clean");
						assertSameManifest(collected, announced, subject);
						checkedIndexes++;
					}
				}
			}
			assertNotNull(catalog.getCollectionForEntity(Entities.PRODUCT).orElse(null), "the fixture needs products");
			assertTrue(checkedIndexes > 10, "the fixture must load reduced indexes too, it loaded " + checkedIndexes);
		}

		/**
		 * Counts the inserted entity so that later entities can reference it.
		 *
		 * @param insertedEntity the reference to the inserted entity
		 */
		private void createEntity(@Nonnull EntityReferenceContract insertedEntity) {
			this.generatedEntities.merge(insertedEntity.getType(), 1, Integer::sum);
		}

	}

}
