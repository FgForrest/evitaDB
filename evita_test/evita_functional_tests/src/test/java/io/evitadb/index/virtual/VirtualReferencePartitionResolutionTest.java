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
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.ReferencePartitionSplit;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartition;
import io.evitadb.index.VirtualReferencePartitionIndex;
import io.evitadb.index.VirtualReferencePartitionSlot;
import io.evitadb.index.bitmap.BaseBitmap;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.entityFetch;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.page;
import static io.evitadb.api.query.QueryConstraints.referenceContentWithAttributes;
import static io.evitadb.api.query.QueryConstraints.require;
import static io.evitadb.api.query.QueryConstraints.scope;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the read resolution of reference partitions: every partition with a single owner, which the catalog keeps
 * as a registry row, resolves - by primary key, by key, by target and in a split - to a
 * {@link VirtualReferencePartition} carrying exactly the owner and the key the entity bodies give it, while every
 * partition with several owners resolves to its reduced entity index holding exactly the owners the bodies give it.
 *
 * The catalog is {@link SingleOwnerPartitionFixture} with the reflected `Category.products` reference, with one product
 * moved to the archive (so both scopes hold registered partitions) and one gallery partition shared by two products
 * under a representative value other single-owner partitions use (so a known tuple can name a partition the registry
 * does not hold). The expected shape of every partition - its key, its owners - is derived from the bodies of the
 * owners, read through the public API.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Read resolution of virtual reference partitions")
@Tag(ENGINE)
@Tag(QUERY)
@Tag(REFERENCE)
class VirtualReferencePartitionResolutionTest implements EvitaTestSupport {
	private static final String CATALOG = "virtualReferencePartitionResolutionTest";
	/**
	 * The product moved to the archive.
	 */
	private static final int ARCHIVED_PRODUCT = 5;
	/**
	 * The media two products show in the detail role - a partition with two owners under a tuple the registry knows.
	 */
	private static final int SHARED_DETAIL_MEDIA = 15;
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
		this.paths = createTestPaths("VirtualReferencePartitionResolutionTest");
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
	@DisplayName("every single-owner partition resolves by primary key to a handle of the owner and key its body gives")
	void shouldResolveEverySingleOwnerPartitionVirtuallyByPrimaryKey() {
		final TreeMap<String, Integer> visited = new TreeMap<>();
		final List<ExpectedPartition> expected = expectedPartitions();
		inQuery(
			queryContext -> {
				for (final ExpectedPartition partition : expected) {
					if (partition.owners().length == 1) {
						final EntityCollection collection = collectionOf(partition.entityType());
						final int primaryKey = registeredPrimaryKeyOf(collection, partition);
						assertShape(
							partition, primaryKey,
							queryContext.resolvePartitionForReadIfExists(
								partition.entityType(), partition.scope(), partition.referenceName(), primaryKey
							)
						);
						assertShape(
							partition, primaryKey,
							queryContext.resolvePartitionForRead(
								partition.entityType(), partition.scope(), partition.referenceName(), primaryKey
							)
						);
						assertShape(
							partition, primaryKey,
							collection.resolvePartitionForReadIfExists(
								partition.scope(), partition.referenceName(), primaryKey, true
							)
						);
						visited.merge(partition.shape(), 1, Integer::sum);
					}
				}
			}
		);
		assertCoversEveryShape(visited);
	}

	@Test
	@DisplayName("every partition resolves by key to the handle or the index object of the owners its bodies give")
	void shouldResolveEveryPartitionByKey() {
		final TreeMap<String, Integer> visited = new TreeMap<>();
		final List<ExpectedPartition> expected = expectedPartitions();
		inQuery(
			queryContext -> {
				for (final ExpectedPartition partition : expected) {
					final ReferencePartition resolved = queryContext.resolvePartitionForReadIfExists(
						partition.entityType(), partition.scope(), partition.key()
					);
					assertShape(partition, primaryKeyOf(collectionOf(partition.entityType()), partition), resolved);
					if (partition.owners().length == 1) {
						visited.merge(partition.shape(), 1, Integer::sum);
					}
				}
			}
		);
		assertCoversEveryShape(visited);
	}

	@Test
	@DisplayName("the partitions of a target resolve as the bodies group the rows referencing it")
	void shouldResolveThePartitionsOfATargetAsTheBodiesGroupThem() {
		final List<ExpectedPartition> expected = expectedPartitions();
		final int[] counts = {0, 0};
		inQuery(
			queryContext -> {
				for (final String[] reference : REFERENCES) {
					final EntityCollection collection = collectionOf(reference[0]);
					final EntitySchemaContract entitySchema = collection.getSchema();
					final ReferenceSchemaContract referenceSchema =
						entitySchema.getReferenceOrThrowException(reference[1]);
					for (final Scope scope : Scope.values()) {
						final Map<Integer, List<ExpectedPartition>> byTarget = new TreeMap<>();
						for (final ExpectedPartition partition : expected) {
							if (partition.shape().equals(reference[0] + "." + reference[1] + "/" + scope)) {
								byTarget.computeIfAbsent(partition.key().primaryKey(), __ -> new ArrayList<>(2))
									.add(partition);
							}
						}
						for (final Map.Entry<Integer, List<ExpectedPartition>> entry : byTarget.entrySet()) {
							final ReferencePartition[] resolved = queryContext.resolvePartitionsOfTargetForRead(
								entitySchema, referenceSchema, scope, entry.getKey()
							);
							assertEquals(
								entry.getValue().size(), resolved.length,
								"Target " + entry.getKey() + " of `" + reference[1] + "` in " + scope + " must " +
									"resolve as many partitions as the bodies give: " + Arrays.toString(resolved)
							);
							for (final ExpectedPartition partition : entry.getValue()) {
								final int primaryKey = primaryKeyOf(collection, partition);
								final ReferencePartition match = Arrays.stream(resolved)
									.filter(it -> it.getPrimaryKey() == primaryKey)
									.findFirst()
									.orElse(null);
								assertShape(partition, primaryKey, match);
								counts[partition.owners().length == 1 ? 0 : 1]++;
							}
						}
					}
				}
			}
		);
		assertTrue(counts[0] > 10, "The targets must hold single-owner partitions: " + counts[0]);
		assertTrue(counts[1] > 0, "The targets must hold partitions with several owners: " + counts[1]);
	}

	@Test
	@DisplayName("a key the registry does not hold falls through to the index object")
	void shouldFallThroughToTheObjectForAKeyTheRegistryDoesNotHold() {
		inQuery(
			queryContext -> {
				final EntityCollection products = collectionOf(ENTITY_PRODUCT);
				// a tuple no single-owner partition uses - the registry does not know it at all
				final RepresentativeReferenceKey thumbnail = media(SHARED_MEDIA, ROLE_THUMBNAIL);
				final ReferencedTypeEntityIndex galleryTypeIndex = typeIndexOf(products, REFERENCE_GALLERY, Scope.LIVE);
				assertNotNull(galleryTypeIndex);
				assertTrue(
					galleryTypeIndex.getVirtualReferencePartitionIndex()
						.getTupleId(thumbnail.representativeAttributeValues()) ==
						VirtualReferencePartitionIndex.NO_TUPLE_ID,
					"The premise of the case: the registry does not know the thumbnail tuple"
				);
				assertEquals(
					objectByKey(products, Scope.LIVE, thumbnail),
					queryContext.resolvePartitionForReadIfExists(ENTITY_PRODUCT, Scope.LIVE, thumbnail)
				);
				// a tuple the registry knows, but of a partition with two owners
				final RepresentativeReferenceKey sharedDetail = media(SHARED_DETAIL_MEDIA, ROLE_DETAIL);
				assertTrue(
					galleryTypeIndex.getVirtualReferencePartitionIndex()
						.getTupleId(sharedDetail.representativeAttributeValues()) !=
						VirtualReferencePartitionIndex.NO_TUPLE_ID,
					"The premise of the case: the registry knows the detail tuple"
				);
				final ReferencePartition shared = queryContext.resolvePartitionForReadIfExists(
					ENTITY_PRODUCT, Scope.LIVE, sharedDetail
				);
				assertEquals(objectByKey(products, Scope.LIVE, sharedDetail), shared);
				assertArrayEquals(
					new int[]{1, 2}, assertInstanceOf(ReducedEntityIndex.class, shared).getAllPrimaryKeys().getArray()
				);
				// a partition without representative values and with several owners
				final RepresentativeReferenceKey sharedCategory = category(SHARED_CATEGORY);
				assertEquals(
					objectByKey(products, Scope.LIVE, sharedCategory),
					queryContext.resolvePartitionForReadIfExists(ENTITY_PRODUCT, Scope.LIVE, sharedCategory)
				);
				// no partition at all
				assertNull(
					queryContext.resolvePartitionForReadIfExists(
						ENTITY_PRODUCT, Scope.LIVE, media(REFERENCED_ENTITY_COUNT, ROLE_DETAIL)
					)
				);
			}
		);
	}

	@Test
	@DisplayName("a primary key of another scope or reference resolves to nothing")
	void shouldRejectAPrimaryKeyOfAnotherScopeOrReference() {
		inQuery(
			queryContext -> {
				final EntityCollection products = collectionOf(ENTITY_PRODUCT);
				final ReferencedTypeEntityIndex typeIndex = typeIndexOf(products, REFERENCE_CATEGORIES, Scope.LIVE);
				assertNotNull(typeIndex);
				final VirtualReferencePartition registered = ReferencePartitions.findRegisteredForWrite(
					typeIndex, category(4)
				);
				assertNotNull(registered, "Category 4 is held by product 4 alone");
				final int partitionPrimaryKey = registered.getPrimaryKey();
				assertInstanceOf(
					VirtualReferencePartition.class,
					queryContext.resolvePartitionForReadIfExists(
						ENTITY_PRODUCT, Scope.LIVE, REFERENCE_CATEGORIES, partitionPrimaryKey
					)
				);
				assertNull(
					queryContext.resolvePartitionForReadIfExists(
						ENTITY_PRODUCT, Scope.ARCHIVED, REFERENCE_CATEGORIES, partitionPrimaryKey
					)
				);
				assertNull(
					queryContext.resolvePartitionForReadIfExists(
						ENTITY_PRODUCT, Scope.LIVE, REFERENCE_GALLERY, partitionPrimaryKey
					)
				);
				assertThrows(
					GenericEvitaInternalError.class,
					() -> queryContext.resolvePartitionForRead(
						ENTITY_PRODUCT, Scope.LIVE, REFERENCE_GALLERY, partitionPrimaryKey
					)
				);
			}
		);
	}

	@Test
	@DisplayName("the advertised partitions of a reference split into the registered and the other ones")
	void shouldSplitTheAdvertisedPartitionsOfAReference() {
		final List<ExpectedPartition> expected = expectedPartitions();
		inQuery(
			queryContext -> {
				for (final String[] reference : REFERENCES) {
					final EntityCollection collection = collectionOf(reference[0]);
					final TreeSet<Integer> advertised = new TreeSet<>();
					final TreeMap<Integer, Long> registered = new TreeMap<>();
					for (final Scope scope : Scope.values()) {
						final ReferencedTypeEntityIndex typeIndex = typeIndexOf(collection, reference[1], scope);
						if (typeIndex != null) {
							typeIndex.forEachReferenceIndexPrimaryKey(advertised::add);
							typeIndex.getVirtualReferencePartitionIndex().forEachEntry(
								VirtualReferencePartitionSlot.PARTITION_OWNER, registered::put
							);
						}
					}
					// the single-owner partitions the bodies give, by the primary key of their row
					final TreeMap<Integer, ExpectedPartition> singleOwner = new TreeMap<>();
					final Set<Integer> shared = new TreeSet<>();
					for (final ExpectedPartition partition : expected) {
						if (partition.entityType().equals(reference[0]) && partition.referenceName().equals(reference[1])) {
							if (partition.owners().length == 1) {
								singleOwner.put(registeredPrimaryKeyOf(collection, partition), partition);
							} else {
								shared.add(primaryKeyOf(collection, partition));
							}
						}
					}
					assertEquals(singleOwner.keySet(), registered.keySet(), "Every single-owner partition is a row");
					final ReferencePartitionSplit split = queryContext.splitPartitionsForRead(
						reference[0], reference[1],
						new BaseBitmap(advertised.stream().mapToInt(Integer::intValue).toArray()),
						Scope.LIVE, Scope.ARCHIVED
					);
					final TreeMap<Integer, Long> virtual = new TreeMap<>();
					for (int i = 0; i < split.getVirtualCount(); i++) {
						final int primaryKey = split.getVirtualPrimaryKey(i);
						virtual.put(
							primaryKey,
							NumberUtils.pack(split.getVirtualOwnerPrimaryKey(i), split.getVirtualTargetPrimaryKey(i))
						);
						assertShape(singleOwner.get(primaryKey), primaryKey, split.getVirtualPartition(i));
					}
					assertEquals(registered, virtual, "Every registered partition is split off as a virtual one");
					final TreeSet<Integer> materialized = new TreeSet<>(advertised);
					materialized.removeAll(registered.keySet());
					assertEquals(shared, materialized, "The other advertised partitions are the shared ones");
					assertEquals(
						materialized.stream().mapToInt(Integer::intValue).boxed().toList(),
						Arrays.stream(split.getMaterializedPrimaryKeys().getArray()).boxed().toList()
					);
					assertFalse(registered.isEmpty(), "The reference must hold registered partitions");
					assertFalse(materialized.isEmpty(), "The reference must hold shared partitions");
				}
			}
		);
	}

	@Test
	@DisplayName("a query does not reuse the key resolutions of an earlier one after a write between them")
	void shouldBuildTheKeyMemoPerQuery() {
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				final RepresentativeReferenceKey key = media(4, ROLE_DETAIL);
				final QueryPlanningContext before = newQueryContext(session);
				final ReferencePartition single = before.resolvePartitionForReadIfExists(
					ENTITY_PRODUCT, Scope.LIVE, key
				);
				assertInstanceOf(VirtualReferencePartition.class, single);
				// a second owner joins - the partition is no longer a single-owner one and loses its row
				updateProduct(6, it -> withMedia(it, 4, ROLE_DETAIL)).accept(session);
				final QueryPlanningContext after = newQueryContext(session);
				final ReferencePartition shared = after.resolvePartitionForReadIfExists(
					ENTITY_PRODUCT, Scope.LIVE, key
				);
				assertArrayEquals(
					new int[]{4, 6}, assertInstanceOf(ReducedEntityIndex.class, shared).getAllPrimaryKeys().getArray(),
					"The later query must resolve the partition the write left behind"
				);
				assertEquals(single.getPrimaryKey(), shared.getPrimaryKey());
			}
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
	 * Asserts a resolved partition has the shape the bodies give: a single-owner partition is the virtual handle of
	 * its row, carrying its only owner and its key; any other is the reduced entity index of exactly its owners.
	 *
	 * @param expected   the partition as the bodies describe it
	 * @param primaryKey the primary key of the partition
	 * @param resolved   the resolved partition
	 */
	private void assertShape(
		@Nullable ExpectedPartition expected,
		int primaryKey,
		@Nullable ReferencePartition resolved
	) {
		assertNotNull(expected, "Partition " + primaryKey + " is no partition the bodies give");
		assertNotNull(resolved, expected + " must resolve");
		assertEquals(primaryKey, resolved.getPrimaryKey(), expected.toString());
		assertEquals(
			new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY, expected.scope(), expected.key()),
			resolved.getIndexKey(), expected.toString()
		);
		assertEquals(expected.key(), resolved.getRepresentativeReferenceKey(), expected.toString());
		assertArrayEquals(
			expected.key().representativeAttributeValues(),
			resolved.getRepresentativeReferenceKey().representativeAttributeValues(), expected.toString()
		);
		assertArrayEquals(expected.owners(), resolved.getAllPrimaryKeys().getArray(), expected.toString());
		if (expected.owners().length == 1) {
			final VirtualReferencePartition virtual = assertInstanceOf(
				VirtualReferencePartition.class, resolved, expected + " must resolve virtually"
			);
			assertEquals(expected.owners()[0], virtual.ownerPrimaryKey());
			assertArrayEquals(expected.owners(), virtual.getAllPrimaryKeysFormula().compute().getArray());
			assertEquals(1, virtual.size());
			assertEquals(expected.scope(), virtual.typeIndex().getIndexKey().scope());
			final long row = virtual.typeIndex().getVirtualReferencePartitionIndex()
				.getPartitionOwner(primaryKey)
				.orElseThrow();
			assertEquals(expected.owners()[0], NumberUtils.unpackHigh(row));
			assertEquals(expected.key().primaryKey(), NumberUtils.unpackLow(row));
		} else {
			assertInstanceOf(ReducedEntityIndex.class, resolved, expected + " has several owners");
		}
	}

	/**
	 * Returns the primary key of a partition the bodies give: of its row for a single-owner one, of its index object
	 * otherwise.
	 *
	 * @param collection the collection owning the partition
	 * @param partition  the partition
	 * @return the primary key
	 */
	private int primaryKeyOf(@Nonnull EntityCollection collection, @Nonnull ExpectedPartition partition) {
		return partition.owners().length == 1 ?
			registeredPrimaryKeyOf(collection, partition) :
			objectByKey(collection, partition.scope(), partition.key()).getPrimaryKey();
	}

	/**
	 * Returns the primary key of the row of a single-owner partition, failing when the registry holds none.
	 *
	 * @param collection the collection owning the partition
	 * @param partition  the partition
	 * @return the primary key
	 */
	private static int registeredPrimaryKeyOf(
		@Nonnull EntityCollection collection,
		@Nonnull ExpectedPartition partition
	) {
		final ReferencedTypeEntityIndex typeIndex = typeIndexOf(collection, partition.referenceName(), partition.scope());
		assertNotNull(typeIndex, partition + " needs a type index");
		final VirtualReferencePartition registered = ReferencePartitions.findRegisteredForWrite(typeIndex, partition.key());
		assertNotNull(registered, partition + " must be a registry row");
		return registered.getPrimaryKey();
	}

	/**
	 * Asserts that the walk visited single-owner partitions of every shape - a reference without representative
	 * values, one with them, the reflected one - and of both scopes.
	 *
	 * @param visited the number of single-owner partitions visited per collection, reference and scope
	 */
	private static void assertCoversEveryShape(@Nonnull TreeMap<String, Integer> visited) {
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
	 * Runs the check with a planning context of a query over the products in both scopes. The session is a read-write
	 * one, because a planning context needs the session object itself, which a read-only session hands out only
	 * wrapped; the check writes nothing.
	 *
	 * @param check the check
	 */
	private void inQuery(@Nonnull Consumer<QueryPlanningContext> check) {
		this.evita.updateCatalog(
			CATALOG,
			session -> {
				check.accept(newQueryContext(session));
			}
		);
	}

	/**
	 * Creates the planning context of a query over the products in both scopes.
	 *
	 * @param session the session of the query
	 * @return the planning context
	 */
	@Nonnull
	private QueryPlanningContext newQueryContext(@Nonnull EvitaSessionContract session) {
		return collectionOf(ENTITY_PRODUCT).createQueryContext(
			new EvitaRequest(
				query(collection(ENTITY_PRODUCT), filterBy(scope(Scope.LIVE, Scope.ARCHIVED))),
				OffsetDateTime.now(), EntityReference.class, null
			),
			session
		);
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
	 * Returns the collection of an entity type in the catalog version the current thread sees - inside a session, the
	 * one the session sees.
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
