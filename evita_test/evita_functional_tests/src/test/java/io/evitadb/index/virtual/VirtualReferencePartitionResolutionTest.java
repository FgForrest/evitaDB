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
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.Evita;
import io.evitadb.core.catalog.Catalog;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance;
import io.evitadb.core.collection.EntityCollection;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.session.EvitaSession;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.ReferencePartitionSplit;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.filterBy;
import static io.evitadb.api.query.QueryConstraints.scope;
import static io.evitadb.index.virtual.SingleOwnerPartitionFixture.*;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.QUERY;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the read resolution of reference partitions: with the read switch of a collection on, every partition the
 * registry of single-owner partitions holds a row for resolves - by primary key, by key, by target and in a split - to
 * a {@link VirtualReferencePartition} describing exactly the partition its reduced entity index describes, while every
 * other partition still resolves to its reduced entity index; with the switch off, the read resolution answers the
 * index objects alone.
 *
 * The catalog is {@link SingleOwnerPartitionFixture} with the reflected `Category.products` reference, maintained from
 * its first write, with one product moved to the archive (so both scopes hold registered partitions) and one gallery
 * partition shared by two products under a representative value other single-owner partitions use (so a known tuple
 * can name a partition the registry does not hold).
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
	 * The references of each collection whose registered partitions the tests walk.
	 */
	private static final String[][] REFERENCES = {
		{ENTITY_PRODUCT, REFERENCE_CATEGORIES},
		{ENTITY_PRODUCT, REFERENCE_GALLERY},
		{ENTITY_CATEGORY, REFERENCE_PRODUCTS}
	};

	private TestPaths paths;
	private Evita evita;

	@BeforeEach
	void setUp() {
		this.paths = createTestPaths("VirtualReferencePartitionResolutionTest");
		this.evita = new Evita(newTestEvitaConfigurationBuilder(this.paths).build());
		// TODO JNO - remove at the end of #1615
		this.evita.createCatalogWithVirtualReferencePartitions(CATALOG, false);
		this.evita.defineCatalog(CATALOG).updateViaNewSession(this.evita);
		// maintained from the first write, so every single-owner partition has its row
		for (final String entityType : ENTITY_TYPES) {
			maintenance().setMaintained(entityType, true);
		}
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

	@Nested
	@DisplayName("with the read switch on")
	class ReadVirtually {

		@BeforeEach
		void switchOn() {
			for (final String entityType : ENTITY_TYPES) {
				maintenance().setReadVirtually(entityType, true);
			}
		}

		@Test
		@DisplayName("every registered partition resolves by primary key to a handle describing its index object")
		void shouldResolveEveryRegisteredPartitionVirtuallyByPrimaryKey() {
			final TreeMap<String, Integer> visited = new TreeMap<>();
			inQuery(
				queryContext -> forEachRegistered(
					(collection, referenceName, scope, partitionPrimaryKey, row) -> {
						final ReducedEntityIndex real = realPartition(collection, partitionPrimaryKey);
						final String entityType = collection.getEntityType();
						final ReferencePartition resolved = queryContext.resolvePartitionForReadIfExists(
							entityType, scope, referenceName, partitionPrimaryKey
						);
						assertSameAs(real, row, resolved);
						assertSameAs(
							real, row,
							queryContext.resolvePartitionForRead(entityType, scope, referenceName, partitionPrimaryKey)
						);
						assertSameAs(
							real, row,
							collection.resolvePartitionForReadIfExists(scope, referenceName, partitionPrimaryKey, true)
						);
						assertSame(
							real, collection.resolvePartitionForReadIfExists(
								scope, referenceName, partitionPrimaryKey, false
							),
							"A collection not read virtually answers its index object"
						);
						visited.merge(entityType + "." + referenceName + "/" + scope, 1, Integer::sum);
					}
				)
			);
			assertCoversEveryShape(visited);
		}

		@Test
		@DisplayName("every registered partition resolves by key to its handle, the other duplicate included")
		void shouldResolveEveryRegisteredPartitionVirtuallyByKey() {
			final TreeMap<String, Integer> visited = new TreeMap<>();
			inQuery(
				queryContext -> forEachRegistered(
					(collection, referenceName, scope, partitionPrimaryKey, row) -> {
						final ReducedEntityIndex real = realPartition(collection, partitionPrimaryKey);
						final ReferencePartition resolved = queryContext.resolvePartitionForReadIfExists(
							collection.getEntityType(), scope, real.getRepresentativeReferenceKey()
						);
						assertSameAs(real, row, resolved);
						visited.merge(collection.getEntityType() + "." + referenceName + "/" + scope, 1, Integer::sum);
					}
				)
			);
			assertCoversEveryShape(visited);
		}

		@Test
		@DisplayName("the partitions of a target resolve with every registered one as its handle")
		void shouldResolveThePartitionsOfATargetWithVirtualOnesInPlace() {
			final int[] virtualCount = {0};
			inQuery(
				queryContext -> {
					for (final String[] reference : REFERENCES) {
						final EntityCollection collection = collectionOf(reference[0]);
						final EntitySchemaContract entitySchema = collection.getSchema();
						final ReferenceSchemaContract referenceSchema =
							entitySchema.getReferenceOrThrowException(reference[1]);
						for (final Scope scope : Scope.values()) {
							final ReferencedTypeEntityIndex typeIndex = typeIndexOf(collection, reference[1], scope);
							if (typeIndex == null) {
								continue;
							}
							final Set<Integer> targets = new TreeSet<>();
							typeIndex.forEachReferenceIndexPrimaryKey(
								partitionPrimaryKey -> targets.add(
									realPartition(collection, partitionPrimaryKey).getReferenceKey().primaryKey()
								)
							);
							for (final Integer target : targets) {
								final ReferencePartition[] objects = queryContext.resolvePartitionsOfTarget(
									entitySchema, referenceSchema, scope, target
								);
								final ReferencePartition[] read = queryContext.resolvePartitionsOfTargetForRead(
									entitySchema, referenceSchema, scope, target
								);
								assertEquals(
									primaryKeysOf(objects), primaryKeysOf(read),
									"The read resolution of target " + target + " of `" + reference[1] + "` in " +
										scope + " must answer the same partitions"
								);
								for (final ReferencePartition partition : read) {
									final OptionalLong row = typeIndex.getVirtualReferencePartitionIndex()
										.getPartitionOwner(partition.getPrimaryKey());
									final ReducedEntityIndex real = realPartition(
										collection, partition.getPrimaryKey()
									);
									if (row.isPresent()) {
										assertSameAs(real, row.getAsLong(), partition);
										virtualCount[0]++;
									} else {
										assertSame(real, partition, "An unregistered partition is its index object");
									}
								}
							}
						}
					}
				}
			);
			assertTrue(virtualCount[0] > 10, "The targets must hold registered partitions: " + virtualCount[0]);
		}

		@Test
		@DisplayName("a key the registry does not hold falls through to the index object")
		void shouldFallThroughToTheObjectForAKeyTheRegistryDoesNotHold() {
			inQuery(
				queryContext -> {
					final EntityCollection products = collectionOf(ENTITY_PRODUCT);
					// a tuple no single-owner partition uses - the registry does not know it at all
					final RepresentativeReferenceKey thumbnail = media(SHARED_MEDIA, ROLE_THUMBNAIL);
					assertTrue(
						typeIndexOf(products, REFERENCE_GALLERY, Scope.LIVE).getVirtualReferencePartitionIndex()
							.getTupleId(thumbnail.representativeAttributeValues()) ==
							VirtualReferencePartitionIndex.NO_TUPLE_ID,
						"The premise of the case: the registry does not know the thumbnail tuple"
					);
					assertSame(
						objectByKey(products, Scope.LIVE, thumbnail),
						queryContext.resolvePartitionForReadIfExists(ENTITY_PRODUCT, Scope.LIVE, thumbnail)
					);
					// a tuple the registry knows, but of a partition with two owners
					final RepresentativeReferenceKey sharedDetail = media(SHARED_DETAIL_MEDIA, ROLE_DETAIL);
					assertTrue(
						typeIndexOf(products, REFERENCE_GALLERY, Scope.LIVE).getVirtualReferencePartitionIndex()
							.getTupleId(sharedDetail.representativeAttributeValues()) !=
							VirtualReferencePartitionIndex.NO_TUPLE_ID,
						"The premise of the case: the registry knows the detail tuple"
					);
					final ReferencePartition shared = queryContext.resolvePartitionForReadIfExists(
						ENTITY_PRODUCT, Scope.LIVE, sharedDetail
					);
					assertSame(objectByKey(products, Scope.LIVE, sharedDetail), shared);
					assertEquals(2, assertInstanceOf(ReducedEntityIndex.class, shared).size());
					// a partition without representative values and with several owners
					final RepresentativeReferenceKey sharedCategory = category(SHARED_CATEGORY);
					assertSame(
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
					final int partitionPrimaryKey = objectByKey(products, Scope.LIVE, category(4)).getPrimaryKey();
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
						final ReferencePartitionSplit split = queryContext.splitPartitionsForRead(
							reference[0], reference[1],
							new BaseBitmap(advertised.stream().mapToInt(Integer::intValue).toArray()),
							Scope.LIVE, Scope.ARCHIVED
						);
						final TreeMap<Integer, Long> virtual = new TreeMap<>();
						for (int i = 0; i < split.getVirtualCount(); i++) {
							virtual.put(
								split.getVirtualPrimaryKey(i),
								NumberUtils.pack(
									split.getVirtualOwnerPrimaryKey(i), split.getVirtualTargetPrimaryKey(i)
								)
							);
							assertSameAs(
								realPartition(collection, split.getVirtualPrimaryKey(i)),
								virtual.get(split.getVirtualPrimaryKey(i)),
								split.getVirtualPartition(i)
							);
						}
						assertEquals(registered, virtual, "Every registered partition is split off as a virtual one");
						final TreeSet<Integer> materialized = new TreeSet<>(advertised);
						materialized.removeAll(registered.keySet());
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
		@DisplayName("a query captures the read switch once, at its first question")
		void shouldCaptureTheReadSwitchOncePerQuery() {
			this.inSession(
				session -> {
					final QueryPlanningContext first = newQueryContext(session);
					assertTrue(first.isReadVirtually(ENTITY_PRODUCT));
					maintenance().setReadVirtually(ENTITY_PRODUCT, false);
					assertTrue(first.isReadVirtually(ENTITY_PRODUCT), "The query keeps the switch it captured");
					final QueryPlanningContext second = newQueryContext(session);
					assertFalse(second.isReadVirtually(ENTITY_PRODUCT), "The next query captures it anew");
					final int partitionPrimaryKey =
						objectByKey(collectionOf(ENTITY_PRODUCT), Scope.LIVE, category(4)).getPrimaryKey();
					assertInstanceOf(
						VirtualReferencePartition.class,
						first.resolvePartitionForReadIfExists(
							ENTITY_PRODUCT, Scope.LIVE, REFERENCE_CATEGORIES, partitionPrimaryKey
						)
					);
					assertInstanceOf(
						ReducedEntityIndex.class,
						second.resolvePartitionForReadIfExists(
							ENTITY_PRODUCT, Scope.LIVE, REFERENCE_CATEGORIES, partitionPrimaryKey
						)
					);
				}
			);
		}

		@Test
		@DisplayName("a nested context answers the read switch its root query captured")
		void shouldAnswerTheRootQuerysReadSwitchInANestedContext() {
			this.inSession(
				session -> {
					final QueryPlanningContext root = newQueryContext(session);
					assertTrue(root.isReadVirtually(ENTITY_PRODUCT));
					maintenance().setReadVirtually(ENTITY_PRODUCT, false);
					final QueryPlanningContext nested = collectionOf(ENTITY_PRODUCT).createQueryContext(
						root,
						new EvitaRequest(
							query(collection(ENTITY_PRODUCT), filterBy(scope(Scope.LIVE))),
							OffsetDateTime.now(), EntityReference.class, null
						),
						(EvitaSession) session
					);
					assertTrue(
						nested.isReadVirtually(ENTITY_PRODUCT),
						"A nested context must answer the switch its root captured, not the switch as it is now"
					);
					assertFalse(newQueryContext(session).isReadVirtually(ENTITY_PRODUCT), "A new root captures it anew");
				}
			);
		}

		@Test
		@DisplayName("a query does not reuse the key resolutions of an earlier one after a write between them")
		void shouldBuildTheKeyMemoPerQuery() {
			this.inSession(
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
					assertEquals(
						2, assertInstanceOf(ReducedEntityIndex.class, shared).size(),
						"The later query must resolve the partition the write left behind"
					);
					assertEquals(single.getPrimaryKey(), shared.getPrimaryKey());
				}
			);
		}

		/**
		 * Runs the check in a read-write session; it is the session's query planning contexts the check creates.
		 *
		 * @param check the check
		 */
		private void inSession(@Nonnull Consumer<EvitaSessionContract> check) {
			VirtualReferencePartitionResolutionTest.this.evita.updateCatalog(CATALOG, check);
		}

	}

	@Nested
	@DisplayName("with the read switch off")
	class ReadObjectsOnly {

		@Test
		@DisplayName("every read resolver answers the index objects alone")
		void shouldResolveIndexObjectsAlone() {
			final TreeMap<String, Integer> visited = new TreeMap<>();
			inQuery(
				queryContext -> {
					assertFalse(queryContext.isReadVirtually(ENTITY_PRODUCT));
					forEachRegistered(
						(collection, referenceName, scope, partitionPrimaryKey, row) -> {
							final ReducedEntityIndex real = realPartition(collection, partitionPrimaryKey);
							final String entityType = collection.getEntityType();
							assertSame(
								real,
								queryContext.resolvePartitionForReadIfExists(
									entityType, scope, referenceName, partitionPrimaryKey
								)
							);
							assertSame(
								real,
								queryContext.resolvePartitionForReadIfExists(
									entityType, scope, real.getRepresentativeReferenceKey()
								)
							);
							final ReferencePartition[] ofTarget = queryContext.resolvePartitionsOfTargetForRead(
								collection.getSchema(),
								collection.getSchema().getReferenceOrThrowException(referenceName),
								scope, real.getReferenceKey().primaryKey()
							);
							assertTrue(
								Arrays.stream(ofTarget).anyMatch(it -> it == real) &&
									Arrays.stream(ofTarget).allMatch(ReducedEntityIndex.class::isInstance)
							);
							final ReferencePartitionSplit split = queryContext.splitPartitionsForRead(
								entityType, referenceName, new BaseBitmap(partitionPrimaryKey), Scope.values()
							);
							assertEquals(0, split.getVirtualCount());
							assertArrayEquals(
								new int[]{partitionPrimaryKey}, split.getMaterializedPrimaryKeys().getArray()
							);
							visited.merge(entityType + "." + referenceName + "/" + scope, 1, Integer::sum);
						}
					);
				}
			);
			assertCoversEveryShape(visited);
		}

	}

	/**
	 * Receives one registered partition.
	 */
	@FunctionalInterface
	private interface RegisteredPartitionConsumer {

		/**
		 * Receives one registered partition.
		 *
		 * @param collection          the collection owning the partition
		 * @param referenceName       the reference of the partition
		 * @param scope               the scope of the partition
		 * @param partitionPrimaryKey the primary key of the partition
		 * @param row                 the registry row, `pack(ownerPrimaryKey, targetPrimaryKey)`
		 */
		void accept(
			@Nonnull EntityCollection collection,
			@Nonnull String referenceName,
			@Nonnull Scope scope,
			int partitionPrimaryKey,
			long row
		);

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
	 * Visits every registered partition of every walked reference in both scopes.
	 *
	 * @param consumer receives each registered partition
	 */
	private void forEachRegistered(@Nonnull RegisteredPartitionConsumer consumer) {
		for (final String[] reference : REFERENCES) {
			final EntityCollection collection = collectionOf(reference[0]);
			for (final Scope scope : Scope.values()) {
				final ReferencedTypeEntityIndex typeIndex = typeIndexOf(collection, reference[1], scope);
				if (typeIndex != null) {
					final List<long[]> rows = new ArrayList<>(16);
					typeIndex.getVirtualReferencePartitionIndex().forEachEntry(
						VirtualReferencePartitionSlot.PARTITION_OWNER,
						(partitionPrimaryKey, payload) -> rows.add(new long[]{partitionPrimaryKey, payload})
					);
					for (final long[] row : rows) {
						consumer.accept(collection, reference[1], scope, (int) row[0], row[1]);
					}
				}
			}
		}
	}

	/**
	 * Asserts that the walk visited registered partitions of every shape - a reference without representative
	 * values, one with them, the reflected one - and of both scopes.
	 *
	 * @param visited the number of registered partitions visited per collection, reference and scope
	 */
	private static void assertCoversEveryShape(@Nonnull TreeMap<String, Integer> visited) {
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
	 * Asserts that a resolved partition is the virtual handle of the registered partition whose index object is given.
	 *
	 * @param real     the index object of the partition
	 * @param row      the registry row of the partition
	 * @param resolved the resolved partition
	 */
	private static void assertSameAs(
		@Nonnull ReducedEntityIndex real,
		long row,
		@Nullable ReferencePartition resolved
	) {
		final VirtualReferencePartition virtual = assertInstanceOf(
			VirtualReferencePartition.class, resolved, "Partition " + real.getPrimaryKey() + " must resolve virtually"
		);
		assertEquals(real.getPrimaryKey(), virtual.getPrimaryKey());
		assertEquals(real.getIndexKey(), virtual.getIndexKey());
		assertEquals(real.getRepresentativeReferenceKey(), virtual.getRepresentativeReferenceKey());
		assertArrayEquals(
			real.getRepresentativeReferenceKey().representativeAttributeValues(),
			virtual.getRepresentativeReferenceKey().representativeAttributeValues()
		);
		assertEquals(real.getReferenceKey(), virtual.getReferenceKey());
		assertEquals(1, real.size(), "A registered partition has one owner");
		assertEquals(real.getAllPrimaryKeys().getFirst(), virtual.ownerPrimaryKey());
		assertArrayEquals(real.getAllPrimaryKeys().getArray(), virtual.getAllPrimaryKeys().getArray());
		assertArrayEquals(
			real.getAllPrimaryKeys().getArray(), virtual.getAllPrimaryKeysFormula().compute().getArray()
		);
		assertEquals(1, virtual.size());
		assertEquals(NumberUtils.unpackHigh(row), virtual.ownerPrimaryKey());
		assertEquals(NumberUtils.unpackLow(row), virtual.getReferenceKey().primaryKey());
		assertEquals(virtual.getIndexKey().scope(), virtual.typeIndex().getIndexKey().scope());
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
	private static ReducedEntityIndex realPartition(@Nonnull EntityCollection collection, int partitionPrimaryKey) {
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
