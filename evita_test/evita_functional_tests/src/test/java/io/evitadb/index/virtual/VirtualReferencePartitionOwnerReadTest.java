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
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartition;
import io.evitadb.index.facet.FacetGroupIndex;
import io.evitadb.index.facet.FacetIdIndex;
import io.evitadb.index.facet.FacetReferenceIndex;
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

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.entityFetchAllContent;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.referenceContentWithAttributes;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.scope;
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

/**
 * Verifies the owner read resolution of reference partitions: by primary key, by target and by key, a partition with a
 * single owner - a registry row - answers a read-only virtual handle carrying exactly the owner and the key the entity
 * bodies give it, a partition with several owners answers its index object holding exactly the owners the bodies give
 * it, and a primary key or a key naming nothing answers nothing.
 *
 * The catalog is {@link SingleOwnerPartitionFixture} with the reflected `Category.products` reference, with one product
 * moved to the archive (so both scopes hold registered partitions) and one gallery partition shared by two products
 * under a representative value other single-owner partitions use (so a known tuple names a partition the registry does
 * not hold). The expected shape of every partition is derived from the bodies of its owners, read through the public
 * API. The conditional facet scenarios run in a catalog of their own.
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
	 * The page large enough to hold every entity of the catalog.
	 */
	private static final int PAGE_SIZE = 10_000;

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

	/**
	 * One partition as the bodies describe it.
	 *
	 * @param entityType    the collection owning the partition
	 * @param referenceName the reference of the partition
	 * @param scope         the scope of the partition
	 * @param key           the key of the partition
	 * @param owners        the owners of the partition, ascending
	 */
	private record ExpectedPartition(
		@Nonnull String entityType,
		@Nonnull String referenceName,
		@Nonnull Scope scope,
		@Nonnull RepresentativeReferenceKey key,
		@Nonnull int[] owners
	) {

		/**
		 * Returns the shape the partition belongs to - its collection, reference and scope.
		 *
		 * @return the shape
		 */
		@Nonnull
		String shape() {
			return this.entityType + "." + this.referenceName + "/" + this.scope;
		}

		@Nonnull
		@Override
		public String toString() {
			return shape() + " " + this.key + " owners " + Arrays.toString(this.owners);
		}

	}

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("VirtualReferencePartitionOwnerReadTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
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
	}

	@AfterEach
	void tearDown() {
		this.evita.close();
		cleanupTestPaths(this.paths);
	}

	@Test
	@DisplayName("a single-owner partition resolves by primary key to a handle of the owner and key its body gives")
	void shouldResolveRegisteredPartitionByPrimaryKeyFromItsRow() {
		final TreeMap<String, Integer> visited = new TreeMap<>();
		for (final ExpectedPartition partition : expectedPartitions()) {
			if (partition.owners().length == 1) {
				final EntityCollection collection = collectionOf(partition.entityType());
				final ReferencedTypeEntityIndex typeIndex = typeIndexOf(
					collection, partition.referenceName(), partition.scope()
				);
				assertNotNull(typeIndex);
				final int primaryKey = registeredPrimaryKeyOf(typeIndex, partition);
				assertRegistered(
					partition, primaryKey,
					ReferencePartitions.resolveForOwnerReadIfExists(
						collection::resolvePartitionIfExists, typeIndex, primaryKey
					)
				);
				visited.merge(partition.shape(), 1, Integer::sum);
			}
		}
		assertCoversEveryShape(visited);
	}

	@Test
	@DisplayName("a partition with several owners resolves by primary key to the index object of exactly them")
	void shouldResolvePartitionWithSeveralOwnersByPrimaryKeyToItsObject() {
		final Set<String> visited = new TreeSet<>();
		for (final ExpectedPartition partition : expectedPartitions()) {
			if (partition.owners().length > 1) {
				final EntityCollection collection = collectionOf(partition.entityType());
				final ReferencedTypeEntityIndex typeIndex = typeIndexOf(
					collection, partition.referenceName(), partition.scope()
				);
				assertNotNull(typeIndex);
				final ReducedEntityIndex object = objectByKey(collection, partition.scope(), partition.key());
				final ReferencePartition resolved = ReferencePartitions.resolveForOwnerReadIfExists(
					collection::resolvePartitionIfExists, typeIndex, object.getPrimaryKey()
				);
				assertSame(object, resolved, partition + " has no row");
				assertArrayEquals(partition.owners(), resolved.getAllPrimaryKeys().getArray(), partition.toString());
				visited.add(partition.entityType() + "." + partition.referenceName());
			}
		}
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
	@DisplayName("the partitions of a target resolve as the bodies group the rows referencing it")
	void shouldResolvePartitionsOfTargetWithRegisteredOnesFromTheirRows() {
		final List<ExpectedPartition> expected = expectedPartitions();
		int registered = 0;
		int objects = 0;
		for (final String[] reference : REFERENCES) {
			final EntityCollection collection = collectionOf(reference[0]);
			final ReferenceSchemaContract referenceSchema =
				collection.getSchema().getReferenceOrThrowException(reference[1]);
			final boolean allowsDuplicates = referenceSchema.getCardinality().allowsDuplicates();
			for (final Scope scope : Scope.values()) {
				final ReferencedTypeEntityIndex typeIndex = typeIndexOf(collection, reference[1], scope);
				final Map<Integer, List<ExpectedPartition>> byTarget = new TreeMap<>();
				for (final ExpectedPartition partition : expected) {
					if (partition.shape().equals(reference[0] + "." + reference[1] + "/" + scope)) {
						byTarget.computeIfAbsent(partition.key().primaryKey(), __ -> new ArrayList<>(2)).add(partition);
					}
				}
				for (final Map.Entry<Integer, List<ExpectedPartition>> entry : byTarget.entrySet()) {
					assertNotNull(typeIndex);
					final ReferencePartition[] resolved = ReferencePartitions.resolveOfTargetForOwnerRead(
						collection.getDataStoreReader(), scope, reference[1], allowsDuplicates, entry.getKey(), true
					);
					assertEquals(
						entry.getValue().size(), resolved.length,
						"Target " + entry.getKey() + " of `" + reference[1] + "` in " + scope + " must resolve as " +
							"many partitions as the bodies give: " + Arrays.toString(resolved)
					);
					for (final ExpectedPartition partition : entry.getValue()) {
						if (partition.owners().length == 1) {
							final int primaryKey = registeredPrimaryKeyOf(typeIndex, partition);
							assertRegistered(
								partition, primaryKey,
								Arrays.stream(resolved).filter(it -> it.getPrimaryKey() == primaryKey).findFirst()
									.orElse(null)
							);
							registered++;
						} else {
							final ReducedEntityIndex object = objectByKey(collection, scope, partition.key());
							assertTrue(
								Arrays.stream(resolved).anyMatch(it -> it == object),
								partition + " must resolve to its index object"
							);
							assertArrayEquals(partition.owners(), object.getAllPrimaryKeys().getArray());
							objects++;
						}
					}
				}
			}
		}
		assertTrue(registered > 0, "No single-owner partition was resolved by its target");
		assertTrue(objects > 0, "No partition with several owners was resolved by its target");
	}

	@Test
	@DisplayName("a key resolves to the handle of a single-owner partition, to the object otherwise, to nothing unknown")
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
				// product `n` shows media `n` alone in both roles
				final ExpectedPartition partition = new ExpectedPartition(
					ENTITY_PRODUCT, REFERENCE_GALLERY, scope, media(product, role), new int[]{product}
				);
				final int primaryKey = registeredPrimaryKeyOf(galleryTypeIndex, partition);
				assertRegistered(partition, primaryKey, gallery.apply(partition.key()));
				// the second lookup of the same target is answered from the remembered family
				assertRegistered(partition, primaryKey, gallery.apply(partition.key()));
			}
			if (scope == Scope.LIVE) {
				// a tuple the registry knows, naming a partition with two owners
				final RepresentativeReferenceKey sharedDetail = media(SHARED_DETAIL_MEDIA, ROLE_DETAIL);
				final ReferencePartition shared = gallery.apply(sharedDetail);
				assertSame(objectByKey(products, scope, sharedDetail), shared);
				assertArrayEquals(new int[]{1, 2}, shared.getAllPrimaryKeys().getArray());
				// every live product shows the shared media as a thumbnail
				assertSame(
					objectByKey(products, scope, media(SHARED_MEDIA, ROLE_THUMBNAIL)),
					gallery.apply(media(SHARED_MEDIA, ROLE_THUMBNAIL))
				);
			}
			assertNull(gallery.apply(media(product, "unknownRole")), "A tuple the registry does not know");
			assertNull(gallery.apply(media(REFERENCED_ENTITY_COUNT + 1, ROLE_GALLERY)), "A target without partitions");

			// a reference without representative attributes - product `n` alone is in category `n`
			final Function<RepresentativeReferenceKey, ReferencePartition> categories =
				ReferencePartitions.resolverByKeyForOwnerRead(
					products.getDataStoreReader(), scope, REFERENCE_CATEGORIES, true
				);
			final ReferencedTypeEntityIndex categoryTypeIndex = typeIndexOf(products, REFERENCE_CATEGORIES, scope);
			assertNotNull(categoryTypeIndex);
			final ExpectedPartition categoryPartition = new ExpectedPartition(
				ENTITY_PRODUCT, REFERENCE_CATEGORIES, scope, category(product), new int[]{product}
			);
			assertRegistered(
				categoryPartition, registeredPrimaryKeyOf(categoryTypeIndex, categoryPartition),
				categories.apply(category(product))
			);
			if (scope == Scope.LIVE) {
				assertSame(
					objectByKey(products, scope, category(SHARED_CATEGORY)), categories.apply(category(SHARED_CATEGORY))
				);
			}
			assertNull(categories.apply(category(REFERENCED_ENTITY_COUNT + 1)), "A target without partitions");
		}
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
	 * Derives every partition of the walked references from the bodies of their owners, in both scopes: the rows
	 * grouped by referenced entity and - for the gallery - by the role telling its duplicates apart.
	 *
	 * @return the partitions
	 */
	@Nonnull
	private List<ExpectedPartition> expectedPartitions() {
		final List<ExpectedPartition> result = new ArrayList<>(64);
		for (final String[] reference : REFERENCES) {
			for (final Scope scope : Scope.values()) {
				final Map<RepresentativeReferenceKey, Set<Integer>> owners = new TreeMap<>();
				for (final SealedEntity body : bodiesOf(reference[0], reference[1], scope)) {
					for (final ReferenceContract row : body.getReferences(reference[1])) {
						owners.computeIfAbsent(keyOf(row), __ -> new TreeSet<>()).add(body.getPrimaryKeyOrThrowException());
					}
				}
				owners.forEach(
					(key, ownerSet) -> result.add(
						new ExpectedPartition(
							reference[0], reference[1], scope, key,
							ownerSet.stream().mapToInt(Integer::intValue).toArray()
						)
					)
				);
			}
		}
		return result;
	}

	/**
	 * Returns the bodies of the entities of a collection in a scope with the rows of one reference.
	 *
	 * @param entityType    the collection
	 * @param referenceName the reference
	 * @param scope         the scope
	 * @return the bodies
	 */
	@Nonnull
	private List<SealedEntity> bodiesOf(@Nonnull String entityType, @Nonnull String referenceName, @Nonnull Scope scope) {
		return this.evita.queryCatalog(
			CATALOG,
			(Function<EvitaSessionContract, List<SealedEntity>>) session -> session.queryList(
				query(
					collection(entityType),
					filterBy(scope(scope)),
					require(page(1, PAGE_SIZE), entityFetch(referenceContentWithAttributes(referenceName)))
				),
				SealedEntity.class
			)
		);
	}

	/**
	 * Returns the key of the partition a row falls into - the gallery's carries the role telling its duplicates apart.
	 *
	 * @param row the row
	 * @return the key
	 */
	@Nonnull
	private static RepresentativeReferenceKey keyOf(@Nonnull ReferenceContract row) {
		return REFERENCE_GALLERY.equals(row.getReferenceName()) ?
			media(row.getReferencedPrimaryKey(), row.getAttribute(ATTRIBUTE_ROLE)) :
			new RepresentativeReferenceKey(new ReferenceKey(row.getReferenceName(), row.getReferencedPrimaryKey()));
	}

	/**
	 * Asserts that the walk visited single-owner partitions of every shape - a reference without representative
	 * values, one with them, the reflected one - and of both scopes.
	 *
	 * @param visited the number of single-owner partitions visited per collection, reference and scope
	 */
	private static void assertCoversEveryShape(@Nonnull Map<String, Integer> visited) {
		for (final String shape : List.of(
			ENTITY_PRODUCT + "." + REFERENCE_CATEGORIES + "/" + Scope.LIVE,
			ENTITY_PRODUCT + "." + REFERENCE_CATEGORIES + "/" + Scope.ARCHIVED,
			ENTITY_PRODUCT + "." + REFERENCE_GALLERY + "/" + Scope.LIVE,
			ENTITY_PRODUCT + "." + REFERENCE_GALLERY + "/" + Scope.ARCHIVED,
			ENTITY_CATEGORY + "." + REFERENCE_PRODUCTS + "/" + Scope.LIVE
		)) {
			assertTrue(visited.getOrDefault(shape, 0) > 0, "No single-owner partition of " + shape + ": " + visited);
		}
	}

	/**
	 * Asserts that a partition resolved for an owner read is the read-only handle of a single-owner partition,
	 * carrying the owner and the key its body gives and the owner and target of its registry row.
	 *
	 * @param expected   the partition as the bodies describe it
	 * @param primaryKey the primary key of its row
	 * @param resolved   the resolved partition
	 */
	private static void assertRegistered(
		@Nonnull ExpectedPartition expected,
		int primaryKey,
		@Nullable ReferencePartition resolved
	) {
		final VirtualReferencePartition virtual = assertInstanceOf(
			VirtualReferencePartition.class, resolved, expected + " must answer from its row"
		);
		assertEquals(primaryKey, virtual.getPrimaryKey());
		assertEquals(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, expected.scope(), expected.key()),
			virtual.getIndexKey()
		);
		assertArrayEquals(
			expected.key().representativeAttributeValues(),
			virtual.getRepresentativeReferenceKey().representativeAttributeValues()
		);
		final OptionalLong row = virtual.typeIndex().getVirtualReferencePartitionIndex().getPartitionOwner(primaryKey);
		assertTrue(row.isPresent(), expected + " must have its registry row");
		assertEquals(NumberUtils.unpackHigh(row.getAsLong()), virtual.ownerPrimaryKey());
		assertEquals(NumberUtils.unpackLow(row.getAsLong()), virtual.getReferenceKey().primaryKey());
		assertEquals(expected.owners()[0], virtual.ownerPrimaryKey());
		assertEquals(expected.key().primaryKey(), virtual.getReferenceKey().primaryKey());
		assertArrayEquals(expected.owners(), virtual.getAllPrimaryKeys().getArray());
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
	 * Returns the primary key of the row of a single-owner partition, failing when the registry holds none.
	 *
	 * @param typeIndex the type index whose registry holds the row
	 * @param partition the partition
	 * @return the primary key
	 */
	private static int registeredPrimaryKeyOf(
		@Nullable ReferencedTypeEntityIndex typeIndex,
		@Nonnull ExpectedPartition partition
	) {
		assertNotNull(typeIndex, partition + " needs a type index");
		final VirtualReferencePartition registered = ReferencePartitions.findRegisteredForWrite(typeIndex, partition.key());
		assertNotNull(registered, partition + " must be a registry row");
		return registered.getPrimaryKey();
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
	 * Creates the catalog of the conditional facet scenarios: a product reference faceted by the status of the
	 * referenced parameter, another faceted by the code of the parent of its owner, and two products each the only
	 * owner of the partitions of its own parameter in both references - none of the conditions holds yet.
	 */
	private void setUpTriggerCatalog() {
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
