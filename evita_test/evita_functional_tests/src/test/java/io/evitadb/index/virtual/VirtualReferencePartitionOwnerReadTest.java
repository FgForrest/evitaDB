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
import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.facet.FacetGroupIndex;
import io.evitadb.index.facet.FacetIdIndex;
import io.evitadb.index.facet.FacetReferenceIndex;
import io.evitadb.index.VirtualReferencePartition;
import io.evitadb.test.EvitaTestSupport;
import io.evitadb.utils.NumberUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// TODO JNO - remove at the end of #1615 (the creation switch and the parity case; the rest stays)
/**
 * Verifies the owner read resolution of reference partitions in a catalog representing its single-owner partitions
 * virtually: by primary key, by target and by key, a partition the registry holds a row for answers a read-only
 * virtual handle carrying exactly its only owner and its key, a partition with several owners answers its index object,
 * and a primary key or a key naming nothing answers nothing. Without the registry - in a catalog with objects - every
 * owner read answers exactly what the object-only resolution answers.
 *
 * The catalog is {@link SingleOwnerPartitionFixture} with the reflected `Category.products` reference, with one product
 * moved to the archive (so both scopes hold registered partitions) and one gallery partition shared by two products
 * under a representative value other single-owner partitions use (so a known tuple names a partition the registry does
 * not hold). The index objects of the registered partitions still exist, which lets every answer be compared with the
 * object it stands for.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Owner read resolution of virtual reference partitions")
@Tag(ENGINE)
@Tag(REFERENCE)
class VirtualReferencePartitionOwnerReadTest implements EvitaTestSupport {
	private static final String CATALOG = "virtualReferencePartitionOwnerReadTest";
	/**
	 * The product moved to the archive.
	 */
	private static final int ARCHIVED_PRODUCT = 5;
	/**
	 * The media two products show in the detail role - a partition with two owners under a tuple the registry knows.
	 */
	private static final int SHARED_DETAIL_MEDIA = 15;
	/**
	 * A primary key no index of the catalog can hold.
	 */
	private static final int UNKNOWN_PARTITION = Integer.MAX_VALUE - 1;
	/**
	 * The references of each collection whose partitions the tests walk.
	 */
	private static final String[][] REFERENCES = {
		{ENTITY_PRODUCT, REFERENCE_CATEGORIES},
		{ENTITY_PRODUCT, REFERENCE_GALLERY},
		{ENTITY_CATEGORY, REFERENCE_PRODUCTS}
	};

	/**
	 * The catalog of the conditional facet scenario.
	 */
	private static final String TRIGGER_CATALOG = "virtualReferencePartitionOwnerReadTriggerTest";
	/**
	 * The entity the conditional facets of the trigger catalog depend on.
	 */
	private static final String ENTITY_PARAMETER = "parameter";
	/**
	 * The attribute of the parameter a conditional facet depends on.
	 */
	private static final String ATTRIBUTE_STATUS = "status";
	/**
	 * The reference faceted by the status of the referenced parameter.
	 */
	private static final String REFERENCE_PARAMETERS = "parameters";
	/**
	 * The reference faceted by the code of the parent of its owner.
	 */
	private static final String REFERENCE_PARENT_PARAMETERS = "parentParameters";
	/**
	 * The parent product of the trigger catalog.
	 */
	private static final int PARENT_PRODUCT = 10;
	/**
	 * The child of {@link #PARENT_PRODUCT}, the only owner of the partitions of parameter 1.
	 */
	private static final int CHILD_PRODUCT = 11;
	/**
	 * A root product, the only owner of the partitions of parameter 2.
	 */
	private static final int OTHER_PRODUCT = 12;

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("VirtualReferencePartitionOwnerReadTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		this.evita.createCatalogWithVirtualReferencePartitions(CATALOG, true);
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(CATALOG, SingleOwnerPartitionFixture::setUpWithReflectedProducts);
		this.evita.updateCatalog(CATALOG, EvitaSessionContract::goLiveAndClose);
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				updateProduct(1, it -> withMedia(it, SHARED_DETAIL_MEDIA, ROLE_DETAIL)).accept(session);
				updateProduct(2, it -> withMedia(it, SHARED_DETAIL_MEDIA, ROLE_DETAIL)).accept(session);
				session.archiveEntity(ENTITY_PRODUCT, ARCHIVED_PRODUCT);
			}
		);
		assertTrue(
			((Catalog) this.evita.getCatalogInstance(CATALOG).orElseThrow())
				.getVirtualReferencePartitionMaintenance().isVirtual(),
			"The catalog must represent its single-owner partitions virtually"
		);
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("a registered partition resolves by primary key to a handle carrying the owner of its row")
	void shouldResolveRegisteredPartitionByPrimaryKeyFromItsRow() {
		final TreeMap<String, Integer> visited = new TreeMap<>();
		forEachPartition(
			(collection, referenceName, scope, typeIndex, partitionPrimaryKey) -> {
				final long[] row = rowOf(typeIndex, partitionPrimaryKey);
				if (row.length == 0) {
					return;
				}
				final ReducedEntityIndex object = objectOf(collection, partitionPrimaryKey);
				final ReferencePartition resolved = ReferencePartitions.resolveForOwnerReadIfExists(
					collection::resolvePartitionIfExists, typeIndex, partitionPrimaryKey
				);
				assertRegistered(object, row[0], resolved);
				assertSame(
					object,
					ReferencePartitions.resolveForOwnerReadIfExists(
						collection::resolvePartitionIfExists, null, partitionPrimaryKey
					),
					"Without the registry the owner read answers the index object"
				);
				visited.merge(collection.getEntityType() + "." + referenceName + "/" + scope, 1, Integer::sum);
			}
		);
		assertCoversEveryShape(visited);
	}

	@Test
	@DisplayName("a partition with several owners resolves by primary key to its index object")
	void shouldResolvePartitionWithSeveralOwnersByPrimaryKeyToItsObject() {
		final Set<String> visited = new TreeSet<>();
		forEachPartition(
			(collection, referenceName, scope, typeIndex, partitionPrimaryKey) -> {
				if (rowOf(typeIndex, partitionPrimaryKey).length > 0) {
					return;
				}
				final ReducedEntityIndex object = objectOf(collection, partitionPrimaryKey);
				final ReferencePartition resolved = ReferencePartitions.resolveForOwnerReadIfExists(
					collection::resolvePartitionIfExists, typeIndex, partitionPrimaryKey
				);
				assertSame(object, resolved, "Partition " + partitionPrimaryKey + " has no row");
				assertTrue(
					resolved.getAllPrimaryKeys().size() > 1,
					"Partition " + partitionPrimaryKey + " has no row, so it must have several owners"
				);
				visited.add(collection.getEntityType() + "." + referenceName);
			}
		);
		assertEquals(
			Set.of(
				ENTITY_CATEGORY + "." + REFERENCE_PRODUCTS,
				ENTITY_PRODUCT + "." + REFERENCE_CATEGORIES,
				ENTITY_PRODUCT + "." + REFERENCE_GALLERY
			),
			visited,
			"Every walked reference must have a partition with several owners"
		);
	}

	@Test
	@DisplayName("a primary key naming no partition resolves to nothing")
	void shouldResolveUnknownPrimaryKeyToNothing() {
		final EntityCollection products = collectionOf(ENTITY_PRODUCT);
		for (final Scope scope : Scope.values()) {
			final ReferencedTypeEntityIndex typeIndex = typeIndexOf(products, REFERENCE_CATEGORIES, scope);
			assertNotNull(typeIndex);
			assertNull(
				ReferencePartitions.resolveForOwnerReadIfExists(
					products::resolvePartitionIfExists, typeIndex, UNKNOWN_PARTITION
				)
			);
			// the type index itself is an index of the collection, but not a partition
			assertNull(
				ReferencePartitions.resolveForOwnerReadIfExists(
					products::resolvePartitionIfExists, typeIndex, typeIndex.getPrimaryKey()
				)
			);
		}
	}

	@Test
	@DisplayName("the partitions of a target resolve with each registered one as its handle and the others as objects")
	void shouldResolvePartitionsOfTargetWithRegisteredOnesFromTheirRows() {
		int registered = 0;
		int objects = 0;
		for (final String[] reference : REFERENCES) {
			final EntityCollection collection = collectionOf(reference[0]);
			final ReferenceSchemaContract referenceSchema =
				collection.getSchema().getReferenceOrThrowException(reference[1]);
			final boolean allowsDuplicates = referenceSchema.getCardinality().allowsDuplicates();
			for (final Scope scope : Scope.values()) {
				final ReferencedTypeEntityIndex typeIndex = typeIndexOf(collection, reference[1], scope);
				if (typeIndex == null) {
					continue;
				}
				for (final int target : targetsOf(collection, typeIndex)) {
					final ReferencePartition[] objectOnly = ReferencePartitions.resolveOfTarget(
						collection.getDataStoreReader(), scope, reference[1], allowsDuplicates, target
					);
					assertArrayEquals(
						objectOnly,
						ReferencePartitions.resolveOfTargetForOwnerRead(
							collection.getDataStoreReader(), scope, reference[1], allowsDuplicates, target, false
						),
						"Without the registry the owner read answers the object-only resolution"
					);
					final ReferencePartition[] resolved = ReferencePartitions.resolveOfTargetForOwnerRead(
						collection.getDataStoreReader(), scope, reference[1], allowsDuplicates, target, true
					);
					assertEquals(
						primaryKeysOf(objectOnly), primaryKeysOf(resolved),
						"Target " + target + " of `" + reference[1] + "` in " + scope + " must answer the same " +
							"partitions"
					);
					for (final ReferencePartition partition : resolved) {
						final long[] row = rowOf(typeIndex, partition.getPrimaryKey());
						if (row.length > 0) {
							assertRegistered(objectOf(collection, partition.getPrimaryKey()), row[0], partition);
							registered++;
						} else {
							assertSame(objectOf(collection, partition.getPrimaryKey()), partition);
							objects++;
						}
					}
				}
			}
		}
		assertTrue(registered > 0, "No registered partition was resolved by its target");
		assertTrue(objects > 0, "No partition with several owners was resolved by its target");
	}

	@Test
	@DisplayName("a key resolves to the handle of its registered partition, to the object otherwise, to nothing when unknown")
	void shouldResolveByKeyFromTheRowOfARegisteredPartition() {
		final EntityCollection products = collectionOf(ENTITY_PRODUCT);
		for (final Scope scope : Scope.values()) {
			final int product = scope == Scope.LIVE ? 1 : ARCHIVED_PRODUCT;
			// a duplicates-allowing reference told apart by its representative attribute
			final Function<RepresentativeReferenceKey, ReferencePartition> gallery =
				ReferencePartitions.resolverByKeyForOwnerRead(
					products.getDataStoreReader(), scope, REFERENCE_GALLERY, true
				);
			final ReferencedTypeEntityIndex galleryTypeIndex = typeIndexOf(products, REFERENCE_GALLERY, scope);
			assertNotNull(galleryTypeIndex);
			for (final String role : List.of(ROLE_GALLERY, ROLE_DETAIL)) {
				final RepresentativeReferenceKey key = media(product, role);
				final ReducedEntityIndex object = objectByKey(products, scope, key);
				final long[] row = rowOf(galleryTypeIndex, object.getPrimaryKey());
				assertEquals(1, row.length, "The " + role + " media of product " + product + " is registered");
				assertRegistered(object, row[0], gallery.apply(key));
				// the second lookup of the same target is answered from the remembered family
				assertRegistered(object, row[0], gallery.apply(key));
			}
			if (scope == Scope.LIVE) {
				// a tuple the registry knows, naming a partition with two owners
				final RepresentativeReferenceKey sharedDetail = media(SHARED_DETAIL_MEDIA, ROLE_DETAIL);
				assertSame(objectByKey(products, scope, sharedDetail), gallery.apply(sharedDetail));
			}
			if (scope == Scope.LIVE) {
				// every live product shows the shared media as a thumbnail
				assertSame(
					objectByKey(products, scope, media(SHARED_MEDIA, ROLE_THUMBNAIL)),
					gallery.apply(media(SHARED_MEDIA, ROLE_THUMBNAIL))
				);
			}
			assertNull(gallery.apply(media(product, "unknownRole")), "A tuple the registry does not know");
			assertNull(gallery.apply(media(REFERENCED_ENTITY_COUNT + 1, ROLE_GALLERY)), "A target without partitions");

			// a reference without representative attributes
			final Function<RepresentativeReferenceKey, ReferencePartition> categories =
				ReferencePartitions.resolverByKeyForOwnerRead(
					products.getDataStoreReader(), scope, REFERENCE_CATEGORIES, true
				);
			final ReferencedTypeEntityIndex categoryTypeIndex = typeIndexOf(products, REFERENCE_CATEGORIES, scope);
			assertNotNull(categoryTypeIndex);
			final ReducedEntityIndex categoryObject = objectByKey(products, scope, category(product));
			final long[] categoryRow = rowOf(categoryTypeIndex, categoryObject.getPrimaryKey());
			assertEquals(1, categoryRow.length, "Category " + product + " is registered");
			assertRegistered(categoryObject, categoryRow[0], categories.apply(category(product)));
			if (scope == Scope.LIVE) {
				assertSame(
					objectByKey(products, scope, category(SHARED_CATEGORY)), categories.apply(category(SHARED_CATEGORY))
				);
			}
			assertNull(categories.apply(category(REFERENCED_ENTITY_COUNT + 1)), "A target without partitions");

			// without the registry the key resolves to the object
			assertSame(
				categoryObject,
				ReferencePartitions.resolverByKeyForOwnerRead(
					products.getDataStoreReader(), scope, REFERENCE_CATEGORIES, false
				).apply(category(product))
			);
		}
	}

	@Test
	@DisplayName("a registry row disagreeing with the index object of its partition fails the owner read")
	void shouldRefuseRegistryRowDisagreeingWithItsObject() {
		final EntityCollection products = collectionOf(ENTITY_PRODUCT);
		final ReferencedTypeEntityIndex typeIndex = typeIndexOf(products, REFERENCE_CATEGORIES, Scope.LIVE);
		assertNotNull(typeIndex);
		final ReducedEntityIndex first = objectByKey(products, Scope.LIVE, category(1));
		final ReducedEntityIndex second = objectByKey(products, Scope.LIVE, category(2));
		assertEquals(1, rowOf(typeIndex, first.getPrimaryKey()).length);
		// the object answered for the partition is another one, owned by another product
		final GenericEvitaInternalError error = assertThrows(
			GenericEvitaInternalError.class,
			() -> ReferencePartitions.resolveForOwnerReadIfExists(
				partitionPrimaryKey -> second, typeIndex, first.getPrimaryKey()
			)
		);
		assertTrue(error.getMessage().contains("index object"), error.getMessage());
		// the object answered for the partition holds a second owner
		final ReducedEntityIndex shared = objectByKey(products, Scope.LIVE, category(SHARED_CATEGORY));
		assertThrows(
			GenericEvitaInternalError.class,
			() -> ReferencePartitions.resolveForOwnerReadIfExists(
				partitionPrimaryKey -> shared, typeIndex, first.getPrimaryKey()
			)
		);
	}

	@Test
	@DisplayName("conditional facets of single-owner partitions are re-evaluated when the referenced entity changes")
	void shouldReevaluateConditionalFacetsOfRegisteredPartitionsWhenReferencedEntityChanges() {
		setUpTriggerCatalog();
		// the referenced entity changes - its owners are found through the registered partition of the target
		this.evita.updateCatalog(
			TRIGGER_CATALOG,
			session -> {
				session.getEntity(ENTITY_PARAMETER, 1, entityFetchAllContent()).orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_STATUS, "ACTIVE")
					.upsertVia(session);
			}
		);
		assertEquals(
			Set.of(CHILD_PRODUCT), facetOwners(triggerCollectionOf(ENTITY_PRODUCT), REFERENCE_PARAMETERS, 1)
		);
		assertEquals(Set.of(), facetOwners(triggerCollectionOf(ENTITY_PRODUCT), REFERENCE_PARAMETERS, 2));
	}

	@Test
	@DisplayName("conditional facets of single-owner partitions are re-evaluated when the parent of the owner changes")
	void shouldReevaluateConditionalFacetsOfRegisteredPartitionsWhenParentChanges() {
		setUpTriggerCatalog();
		// the parent changes - the owners among its children are found through the registered partitions
		this.evita.updateCatalog(
			TRIGGER_CATALOG,
			session -> {
				session.getEntity(ENTITY_PRODUCT, PARENT_PRODUCT, entityFetchAllContent()).orElseThrow()
					.openForWrite()
					.setAttribute(ATTRIBUTE_CODE, "ROOT")
					.upsertVia(session);
			}
		);
		assertEquals(
			Set.of(CHILD_PRODUCT), facetOwners(triggerCollectionOf(ENTITY_PRODUCT), REFERENCE_PARENT_PARAMETERS, 1)
		);
		assertEquals(
			Set.of(), facetOwners(triggerCollectionOf(ENTITY_PRODUCT), REFERENCE_PARENT_PARAMETERS, 2)
		);
	}

	/**
	 * Receives one advertised partition.
	 */
	@FunctionalInterface
	private interface PartitionConsumer {

		/**
		 * Receives one advertised partition.
		 *
		 * @param collection          the collection owning the partition
		 * @param referenceName       the reference of the partition
		 * @param scope               the scope of the partition
		 * @param typeIndex           the type index advertising the partition
		 * @param partitionPrimaryKey the primary key of the partition
		 */
		void accept(
			@Nonnull EntityCollection collection,
			@Nonnull String referenceName,
			@Nonnull Scope scope,
			@Nonnull ReferencedTypeEntityIndex typeIndex,
			int partitionPrimaryKey
		);

	}

	/**
	 * Visits every partition every walked reference advertises in both scopes.
	 *
	 * @param consumer receives each partition
	 */
	private void forEachPartition(@Nonnull PartitionConsumer consumer) {
		for (final String[] reference : REFERENCES) {
			final EntityCollection collection = collectionOf(reference[0]);
			for (final Scope scope : Scope.values()) {
				final ReferencedTypeEntityIndex typeIndex = typeIndexOf(collection, reference[1], scope);
				if (typeIndex != null) {
					final List<Integer> partitionPrimaryKeys = new ArrayList<>(32);
					typeIndex.forEachReferenceIndexPrimaryKey(partitionPrimaryKeys::add);
					for (final int partitionPrimaryKey : partitionPrimaryKeys) {
						consumer.accept(collection, reference[1], scope, typeIndex, partitionPrimaryKey);
					}
				}
			}
		}
	}

	/**
	 * Asserts that the walk visited registered partitions of every shape - a reference without representative values,
	 * one with them, the reflected one - and of both scopes.
	 *
	 * @param visited the number of registered partitions visited per collection, reference and scope
	 */
	private static void assertCoversEveryShape(@Nonnull Map<String, Integer> visited) {
		for (final String shape : List.of(
			ENTITY_PRODUCT + "." + REFERENCE_CATEGORIES + "/" + Scope.LIVE,
			ENTITY_PRODUCT + "." + REFERENCE_CATEGORIES + "/" + Scope.ARCHIVED,
			ENTITY_PRODUCT + "." + REFERENCE_GALLERY + "/" + Scope.LIVE,
			ENTITY_PRODUCT + "." + REFERENCE_GALLERY + "/" + Scope.ARCHIVED,
			ENTITY_CATEGORY + "." + REFERENCE_PRODUCTS + "/" + Scope.LIVE
		)) {
			assertTrue(visited.getOrDefault(shape, 0) > 0, "No registered partition of " + shape + ": " + visited);
		}
	}

	/**
	 * Asserts that a partition resolved for an owner read is the read-only handle of the registered partition whose
	 * index object is given, carrying the owner of the partition's row.
	 *
	 * @param object   the index object of the partition
	 * @param row      the registry row of the partition, `pack(ownerPrimaryKey, targetPrimaryKey)`
	 * @param resolved the resolved partition
	 */
	private static void assertRegistered(
		@Nonnull ReducedEntityIndex object,
		long row,
		@Nullable ReferencePartition resolved
	) {
		final VirtualReferencePartition virtual = assertInstanceOf(
			VirtualReferencePartition.class, resolved,
			"Registered partition " + object.getPrimaryKey() + " must answer from its row"
		);
		assertEquals(object.getPrimaryKey(), virtual.getPrimaryKey());
		assertEquals(object.getIndexKey(), virtual.getIndexKey());
		assertArrayEquals(
			object.getRepresentativeReferenceKey().representativeAttributeValues(),
			virtual.getRepresentativeReferenceKey().representativeAttributeValues()
		);
		assertEquals(NumberUtils.unpackHigh(row), virtual.ownerPrimaryKey());
		assertEquals(NumberUtils.unpackLow(row), virtual.getReferenceKey().primaryKey());
		assertArrayEquals(new int[]{NumberUtils.unpackHigh(row)}, virtual.getAllPrimaryKeys().getArray());
		assertArrayEquals(object.getAllPrimaryKeys().getArray(), virtual.getAllPrimaryKeys().getArray());
		assertThrows(
			GenericEvitaInternalError.class, () -> ReferencePartitions.requireMaterialized(virtual, "test"),
			"A writer must reject the handle"
		);
	}

	/**
	 * Returns the registry row of a partition.
	 *
	 * @param typeIndex           the type index whose registry is read
	 * @param partitionPrimaryKey the primary key of the partition
	 * @return the row as a single-element array, an empty array when the registry holds none
	 */
	@Nonnull
	private static long[] rowOf(@Nonnull ReferencedTypeEntityIndex typeIndex, int partitionPrimaryKey) {
		final OptionalLong row = typeIndex.getVirtualReferencePartitionIndex().getPartitionOwner(partitionPrimaryKey);
		return row.isPresent() ? new long[]{row.getAsLong()} : new long[0];
	}

	/**
	 * Returns the targets a type index lists partitions for.
	 *
	 * @param collection the collection owning the partitions
	 * @param typeIndex  the type index
	 * @return the target primary keys
	 */
	@Nonnull
	private static Set<Integer> targetsOf(
		@Nonnull EntityCollection collection,
		@Nonnull ReferencedTypeEntityIndex typeIndex
	) {
		final Set<Integer> targets = new TreeSet<>();
		typeIndex.forEachReferenceIndexPrimaryKey(
			partitionPrimaryKey -> targets.add(
				objectOf(collection, partitionPrimaryKey).getReferenceKey().primaryKey()
			)
		);
		return targets;
	}

	/**
	 * Returns the primary keys of partitions, sorted.
	 *
	 * @param partitions the partitions
	 * @return the primary keys
	 */
	@Nonnull
	private static List<Integer> primaryKeysOf(@Nonnull ReferencePartition[] partitions) {
		return Arrays.stream(partitions).map(ReferencePartition::getPrimaryKey).sorted().toList();
	}

	/**
	 * Returns the index object of a partition by its primary key.
	 *
	 * @param collection          the collection owning it
	 * @param partitionPrimaryKey the primary key
	 * @return the index object
	 */
	@Nonnull
	private static ReducedEntityIndex objectOf(@Nonnull EntityCollection collection, int partitionPrimaryKey) {
		return assertInstanceOf(ReducedEntityIndex.class, collection.getIndexByPrimaryKeyIfExists(partitionPrimaryKey));
	}

	/**
	 * Returns the index object of a partition by its key.
	 *
	 * @param collection the collection owning it
	 * @param scope      the scope of the partition
	 * @param key        the representative reference key of the partition
	 * @return the index object
	 */
	@Nonnull
	private static ReducedEntityIndex objectByKey(
		@Nonnull EntityCollection collection,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey key
	) {
		return assertInstanceOf(
			ReducedEntityIndex.class,
			collection.getIndexByKeyIfExists(new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, scope, key))
		);
	}

	/**
	 * Returns the referenced-type entity index of a reference in a scope.
	 *
	 * @param collection    the collection
	 * @param referenceName the reference
	 * @param scope         the scope
	 * @return the type index, or `null` when the collection holds none
	 */
	@Nullable
	private static ReferencedTypeEntityIndex typeIndexOf(
		@Nonnull EntityCollection collection,
		@Nonnull String referenceName,
		@Nonnull Scope scope
	) {
		final EntityIndex typeIndex = collection.getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, scope, referenceName)
		);
		return typeIndex == null ? null : assertInstanceOf(ReferencedTypeEntityIndex.class, typeIndex);
	}

	/**
	 * Returns the key of a category partition.
	 *
	 * @param category the category
	 * @return the key
	 */
	@Nonnull
	private static RepresentativeReferenceKey category(int category) {
		return new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_CATEGORIES, category));
	}

	/**
	 * Returns the key of a gallery partition.
	 *
	 * @param media the media
	 * @param role  the role the media is shown in
	 * @return the key
	 */
	@Nonnull
	private static RepresentativeReferenceKey media(int media, @Nonnull String role) {
		return new RepresentativeReferenceKey(new ReferenceKey(REFERENCE_GALLERY, media), new Serializable[]{role});
	}

	/**
	 * Creates the catalog of the conditional facet scenarios, representing its single-owner partitions virtually: a
	 * product reference faceted by the status of the referenced parameter, another faceted by the code of the parent of
	 * its owner, and two products each the only owner of the partitions of its own parameter in both references - none
	 * of the conditions holds yet.
	 */
	private void setUpTriggerCatalog() {
		this.evita.createCatalogWithVirtualReferencePartitions(TRIGGER_CATALOG, true);
		this.evita.defineCatalog(TRIGGER_CATALOG).updateViaNewSession(this.evita);
		this.evita.updateCatalog(
			TRIGGER_CATALOG,
			session -> {
				session.defineEntitySchema(ENTITY_PARAMETER)
					.withAttribute(ATTRIBUTE_STATUS, String.class, whichIs -> whichIs.filterable().nullable())
					.updateVia(session);
				session.defineEntitySchema(ENTITY_PRODUCT)
					.withHierarchy()
					.withAttribute(ATTRIBUTE_CODE, String.class, whichIs -> whichIs.filterable().nullable())
					.withReferenceToEntity(
						REFERENCE_PARAMETERS, ENTITY_PARAMETER, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFiltering()
							.faceted()
							.facetedPartially(
								ExpressionFactory.parse(
									"($reference.referencedEntity.attributes['" + ATTRIBUTE_STATUS + "'] ?? '') == " +
										"'ACTIVE'"
								)
							)
					)
					.withReferenceToEntity(
						REFERENCE_PARENT_PARAMETERS, ENTITY_PARAMETER, Cardinality.ZERO_OR_MORE,
						whichIs -> whichIs
							.indexedForFiltering()
							.faceted()
							.facetedPartially(
								ExpressionFactory.parse(
									"($entity.parentEntity?.attributes['" + ATTRIBUTE_CODE + "'] ?? '') == 'ROOT'"
								)
							)
					)
					.updateVia(session);
				for (int parameter = 1; parameter <= 2; parameter++) {
					session.createNewEntity(ENTITY_PARAMETER, parameter).upsertVia(session);
				}
				session.createNewEntity(ENTITY_PRODUCT, PARENT_PRODUCT).setAttribute(ATTRIBUTE_CODE, "OTHER")
					.upsertVia(session);
				// the only owner of the partitions of parameter 1 in both references
				session.createNewEntity(ENTITY_PRODUCT, CHILD_PRODUCT)
					.setParent(PARENT_PRODUCT)
					.setReference(REFERENCE_PARAMETERS, 1)
					.setReference(REFERENCE_PARENT_PARAMETERS, 1)
					.upsertVia(session);
				// the only owner of the partitions of parameter 2 in both references
				session.createNewEntity(ENTITY_PRODUCT, OTHER_PRODUCT)
					.setReference(REFERENCE_PARAMETERS, 2)
					.setReference(REFERENCE_PARENT_PARAMETERS, 2)
					.upsertVia(session);
				session.goLiveAndClose();
			}
		);
		final EntityCollection products = triggerCollectionOf(ENTITY_PRODUCT);
		for (final String referenceName : List.of(REFERENCE_PARAMETERS, REFERENCE_PARENT_PARAMETERS)) {
			final ReferencedTypeEntityIndex typeIndex = typeIndexOf(products, referenceName, Scope.LIVE);
			assertNotNull(typeIndex);
			final int[] partitions = typeIndex.getAllReferenceIndexes(1);
			assertEquals(1, partitions.length);
			final long[] row = rowOf(typeIndex, partitions[0]);
			assertEquals(1, row.length, "The partition of parameter 1 in `" + referenceName + "` must be registered");
			assertEquals(CHILD_PRODUCT, NumberUtils.unpackHigh(row[0]));
		}
		assertEquals(Set.of(), facetOwners(products, REFERENCE_PARAMETERS, 1));
		assertEquals(Set.of(), facetOwners(products, REFERENCE_PARENT_PARAMETERS, 1));
	}

	/**
	 * Returns the owners the global facet index of a collection holds for one ungrouped facet.
	 *
	 * @param collection    the collection
	 * @param referenceName the faceted reference
	 * @param facet         the primary key of the facet
	 * @return the owners, empty when the facet is indexed for none
	 */
	@Nonnull
	private static Set<Integer> facetOwners(
		@Nonnull EntityCollection collection,
		@Nonnull String referenceName,
		int facet
	) {
		final EntityIndex globalIndex = collection.getIndexByKeyIfExists(
			new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)
		);
		assertNotNull(globalIndex);
		final FacetReferenceIndex facetReferenceIndex = globalIndex.getFacetingEntities().get(referenceName);
		final FacetGroupIndex facetGroupIndex = facetReferenceIndex == null ?
			null : facetReferenceIndex.getFacetsInGroup(null);
		final FacetIdIndex facetIdIndex = facetGroupIndex == null ? null : facetGroupIndex.getFacetIdIndex(facet);
		final Set<Integer> owners = new TreeSet<>();
		if (facetIdIndex != null) {
			for (final int owner : facetIdIndex.getRecords().getArray()) {
				owners.add(owner);
			}
		}
		return owners;
	}

	/**
	 * Returns the collection of an entity type of the trigger catalog in its current version.
	 *
	 * @param entityType the entity type
	 * @return the collection
	 */
	@Nonnull
	private EntityCollection triggerCollectionOf(@Nonnull String entityType) {
		return (EntityCollection) this.evita.getCatalogInstance(TRIGGER_CATALOG)
			.orElseThrow()
			.getCollectionForEntity(entityType)
			.orElseThrow();
	}

	/**
	 * Returns the collection of an entity type in the current catalog version.
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

}
