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

package io.evitadb.index.mutation.local;

import io.evitadb.api.index.EntityIndexType;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaEditor;
import io.evitadb.api.requestResponse.schema.EntitySchemaEditor;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
// TODO JNO - remove at the end of #1615
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.MutationHandle;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.IndexMaintainer;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.mutation.local.ReferenceIndexMutator.IterationPath;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.Functions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the write acquisition of reference partitions on {@link EntityIndexLocalMutationExecutor}: every partition
 * handed out for writing must come through the enrolling accessor of the index maintainer (so that its changed
 * storage parts are persisted and the commit-time merge sees it), the by-key acquisition creates what it does not
 * find, and the partitions of one target are found by the same two routes as the read-side resolvers. The fan-out
 * entry points refuse any reference indexing level below
 * {@link ReferenceIndexType#FOR_FILTERING_AND_PARTITIONING}, whose partitions must never reach their consumers.
 *
 * The index maintainer is a real map of real indexes that counts how each index was reached, which is how the tests
 * see whether an acquisition enrolled what it returned.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("EntityIndexLocalMutationExecutor partition write acquisition")
@Tag(INDEXING)
@Tag(REFERENCE)
class EntityIndexLocalMutationExecutorPartitionAcquisitionTest extends AbstractMutatorTestBase {
	/**
	 * A reference that allows no duplicates - at most one partition per target.
	 */
	private static final String SINGLE_REFERENCE = "acquisitionSingleRef";
	/**
	 * A reference that allows duplicates - one partition per target and representative value.
	 */
	private static final String DUPLICATE_REFERENCE = "acquisitionDuplicateRef";
	/**
	 * The representative attribute of {@link #DUPLICATE_REFERENCE}.
	 */
	private static final String REPRESENTATIVE_ATTRIBUTE = "variant";
	/**
	 * Primary key of the referenced entity every test acquires partitions of.
	 */
	private static final int TARGET_PK = 7;

	/**
	 * The index maintainer the executor under test writes through.
	 */
	@Nonnull private final CountingIndexMaintainer indexMaintainer = new CountingIndexMaintainer();
	/**
	 * The executor under test.
	 */
	@Nonnull private final EntityIndexLocalMutationExecutor partitionExecutor;

	{
		final AtomicInteger sequencer = new AtomicInteger(1);
		this.partitionExecutor = new EntityIndexLocalMutationExecutor(
			this.containerAccessor,
			1,
			this.indexMaintainer,
			new MockEntityIndexCreator<>(this.catalogIndex),
			() -> this.productSchema,
			sequencer::getAndIncrement,
			() -> {
				throw new UnsupportedOperationException("Not supported in the test.");
			},
			null,
			null,
			null,
			this.classifierResolver,
			this.usageRegistry,
			this.catalogUsageRegistry,
			true,
			// TODO JNO - remove at the end of #1615
			MutationHandle.OFF
		);
	}

	@Override
	protected void alterCatalogSchema(@Nonnull CatalogSchemaEditor.CatalogSchemaBuilder schemaBuilder) {
		// no catalog-level customization required
	}

	@Override
	protected void alterProductSchema(@Nonnull EntitySchemaEditor.EntitySchemaBuilder schemaBuilder) {
		schemaBuilder
			.withReferenceTo(
				SINGLE_REFERENCE, SINGLE_REFERENCE, Cardinality.ZERO_OR_MORE,
				thatIs -> thatIs.indexedForFiltering()
			)
			.withReferenceTo(
				DUPLICATE_REFERENCE, DUPLICATE_REFERENCE, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				thatIs -> thatIs
					.indexedForFiltering()
					.withAttribute(REPRESENTATIVE_ATTRIBUTE, String.class, attr -> attr.filterable().representative())
			);
	}

	/**
	 * Returns the schema of the reference of the product entity.
	 *
	 * @param referenceName name of the reference
	 * @return the reference schema
	 */
	@Nonnull
	private ReferenceSchemaContract reference(@Nonnull String referenceName) {
		return this.productSchema.getReferenceOrThrowException(referenceName);
	}

	/**
	 * Registers a partition of {@link #DUPLICATE_REFERENCE} and lists it in the reference's type index.
	 *
	 * @param primaryKey          primary key of the partition
	 * @param representativeValue the representative attribute value of the partition
	 * @return the partition
	 */
	@Nonnull
	private ReducedEntityIndex addDuplicatePartition(int primaryKey, @Nonnull String representativeValue) {
		final ReducedEntityIndex partition = new ReducedEntityIndex(
			primaryKey, this.productSchema.getName(),
			ReferencePartitions.createKey(
				Scope.LIVE,
				new RepresentativeReferenceKey(
					new ReferenceKey(DUPLICATE_REFERENCE, TARGET_PK), new Serializable[]{representativeValue}
				)
			)
		);
		this.indexMaintainer.register(partition);
		listInTypeIndex(primaryKey);
		return partition;
	}

	/**
	 * Lists a primary key for {@link #TARGET_PK} in the type index of {@link #DUPLICATE_REFERENCE}, creating the type
	 * index when there is none yet.
	 *
	 * @param primaryKey the primary key to list
	 */
	private void listInTypeIndex(int primaryKey) {
		final EntityIndexKey typeIndexKey = new EntityIndexKey(
			EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, DUPLICATE_REFERENCE
		);
		final EntityIndex existing = this.indexMaintainer.getIndexIfExists(typeIndexKey);
		final ReferencedTypeEntityIndex typeIndex = (ReferencedTypeEntityIndex) (
			existing == null ?
				this.indexMaintainer.register(
					new ReferencedTypeEntityIndex(900, this.productSchema.getName(), typeIndexKey)
				) :
				existing
		);
		typeIndex.insertPrimaryKeyIfMissing(primaryKey, TARGET_PK);
	}

	/**
	 * Collects the partitions of {@link #TARGET_PK} the executor hands out for writing.
	 *
	 * @param referenceName the reference
	 * @return the partitions in the order the consumer received them
	 */
	@Nonnull
	private List<ReferencePartition> collectPartitionsOfTarget(@Nonnull String referenceName) {
		final List<ReferencePartition> partitions = new ArrayList<>(4);
		this.partitionExecutor.forEachPartitionOfTargetForWrite(
			Scope.LIVE, reference(referenceName), TARGET_PK, partitions::add
		);
		return partitions;
	}

	@Nested
	@DisplayName("by representative reference key")
	class ByKey {

		@Test
		@DisplayName("creates a missing partition through the enrolling accessor and returns it again afterwards")
		void shouldCreatePartitionThroughEnrollingAccessorWhenMissing() {
			final RepresentativeReferenceKey rrk = new RepresentativeReferenceKey(
				new ReferenceKey(SINGLE_REFERENCE, TARGET_PK)
			);
			final ReferencePartition created = EntityIndexLocalMutationExecutorPartitionAcquisitionTest.this
				.partitionExecutor.getOrCreatePartitionForWrite(Scope.LIVE, rrk);
			assertInstanceOf(ReducedEntityIndex.class, created);
			assertEquals(rrk, created.getRepresentativeReferenceKey());
			assertEquals(Scope.LIVE, created.getIndexKey().scope());

			final ReferencePartition again = EntityIndexLocalMutationExecutorPartitionAcquisitionTest.this
				.partitionExecutor.getOrCreatePartitionForWrite(Scope.LIVE, rrk);
			assertSame(created, again, "the second acquisition must return the very same object");
			assertEquals(
				2, EntityIndexLocalMutationExecutorPartitionAcquisitionTest.this.indexMaintainer.acquisitionsByKey(
					ReferencePartitions.createKey(Scope.LIVE, rrk)
				),
				"both acquisitions must go through the enrolling by-key accessor"
			);
		}

		@Test
		@DisplayName("treats a non-partition registered under a partition key as a programming error")
		void shouldThrowWhenPartitionKeyHoldsAnotherIndex() {
			final RepresentativeReferenceKey rrk = new RepresentativeReferenceKey(
				new ReferenceKey(SINGLE_REFERENCE, TARGET_PK)
			);
			EntityIndexLocalMutationExecutorPartitionAcquisitionTest.this.indexMaintainer.registerUnder(
				ReferencePartitions.createKey(Scope.LIVE, rrk),
				new GlobalEntityIndex(500, ENTITY_NAME, new EntityIndexKey(EntityIndexType.GLOBAL))
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> EntityIndexLocalMutationExecutorPartitionAcquisitionTest.this.partitionExecutor
					.getOrCreatePartitionForWrite(Scope.LIVE, rrk)
			);
		}
	}

	@Nested
	@DisplayName("partitions of one target")
	class OfTarget {

		@Test
		@DisplayName("creates the single partition of a reference without duplicates")
		void shouldCreateSinglePartitionWhenReferenceDisallowsDuplicates() {
			final List<ReferencePartition> partitions = collectPartitionsOfTarget(SINGLE_REFERENCE);
			assertEquals(1, partitions.size());
			final ReferencePartition partition = partitions.get(0);
			assertEquals(new ReferenceKey(SINGLE_REFERENCE, TARGET_PK), partition.getReferenceKey());
			assertEquals(
				1,
				EntityIndexLocalMutationExecutorPartitionAcquisitionTest.this.indexMaintainer.acquisitionsByKey(
					partition.getIndexKey()
				)
			);
			assertSame(partition, collectPartitionsOfTarget(SINGLE_REFERENCE).get(0));
		}

		@Test
		@DisplayName("enrols every partition a reference with duplicates lists for the target")
		void shouldEnrolEveryListedPartitionWhenReferenceAllowsDuplicates() {
			final ReducedEntityIndex german = addDuplicatePartition(21, "DE");
			final ReducedEntityIndex american = addDuplicatePartition(22, "US");
			final List<ReferencePartition> partitions = collectPartitionsOfTarget(DUPLICATE_REFERENCE);
			assertEquals(List.of(german, american), partitions);
			final CountingIndexMaintainer maintainer =
				EntityIndexLocalMutationExecutorPartitionAcquisitionTest.this.indexMaintainer;
			assertEquals(1, maintainer.acquisitionsByPrimaryKey(21));
			assertEquals(1, maintainer.acquisitionsByPrimaryKey(22));
		}

		@Test
		@DisplayName("creates the type index of a reference with duplicates and finds nothing in it")
		void shouldCreateTypeIndexAndReturnNothingWhenReferenceHasNoPartitions() {
			assertTrue(collectPartitionsOfTarget(DUPLICATE_REFERENCE).isEmpty());
			assertNotNull(
				EntityIndexLocalMutationExecutorPartitionAcquisitionTest.this.indexMaintainer.getIndexIfExists(
					new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, DUPLICATE_REFERENCE)
				),
				"the acquisition must create the type index it reads"
			);
		}

		@Test
		@DisplayName("fails on a primary key the type index lists but the collection does not hold")
		void shouldThrowWhenListedPrimaryKeyIsMissing() {
			addDuplicatePartition(21, "DE");
			listInTypeIndex(23);
			assertThrows(GenericEvitaInternalError.class, () -> collectPartitionsOfTarget(DUPLICATE_REFERENCE));
		}

		@Test
		@DisplayName("rejects a listed primary key of the group family, naming the primary key")
		void shouldThrowWhenListedPrimaryKeyNamesGroupIndex() {
			EntityIndexLocalMutationExecutorPartitionAcquisitionTest.this.indexMaintainer.register(
				new ReducedGroupEntityIndex(
					12, ENTITY_NAME,
					new EntityIndexKey(
						EntityIndexType.REFERENCED_GROUP_ENTITY, Scope.LIVE,
						new RepresentativeReferenceKey(new ReferenceKey(DUPLICATE_REFERENCE, 3))
					)
				)
			);
			listInTypeIndex(12);
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class, () -> collectPartitionsOfTarget(DUPLICATE_REFERENCE)
			);
			assertTrue(error.getPrivateMessage().contains("primary key 12"), error.getPrivateMessage());
		}
	}

	@Nested
	@DisplayName("partition fan-out")
	class FanOut {

		@Test
		@DisplayName("refuses the per-reference fan-out below the partitioning level")
		void shouldRefuseFanOutPerReferenceBelowPartitioning() {
			final AtomicInteger invocations = new AtomicInteger();
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> EntityIndexLocalMutationExecutorPartitionAcquisitionTest.this.partitionExecutor
					.fanOutPerReference(
						ReferenceIndexType.FOR_FILTERING,
						(referenceSchema, indexForRemoval, indexForUpsert) -> invocations.incrementAndGet(),
						Functions.alwaysTrue(), true, IterationPath.BOTH
					)
			);
			assertTrue(
				error.getPrivateMessage().contains(ReferenceIndexType.FOR_FILTERING.name()), error.getPrivateMessage()
			);
			assertEquals(0, invocations.get(), "the consumer must never be reached");
		}

		@Test
		@DisplayName("refuses the per-index fan-out below the partitioning level")
		void shouldRefuseUniqueFanOutBelowPartitioning() {
			final AtomicInteger invocations = new AtomicInteger();
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> EntityIndexLocalMutationExecutorPartitionAcquisitionTest.this.partitionExecutor
					.fanOutUniquePerIndex(
						ReferenceIndexType.FOR_FILTERING,
						(referenceSchema, indexForRemoval, indexForUpsert) -> invocations.incrementAndGet(),
						Functions.alwaysTrue(), true, IterationPath.BOTH
					)
			);
			assertTrue(
				error.getPrivateMessage().contains(ReferenceIndexType.FOR_FILTERING.name()), error.getPrivateMessage()
			);
			assertEquals(0, invocations.get(), "the consumer must never be reached");
		}
	}

	/**
	 * Index maintainer over real indexes that counts how each index was acquired for writing.
	 */
	private final class CountingIndexMaintainer implements IndexMaintainer<EntityIndexKey, EntityIndex> {
		/**
		 * Every index by key.
		 */
		@Nonnull private final Map<EntityIndexKey, EntityIndex> indexes = CollectionUtils.createHashMap(16);
		/**
		 * Every index by primary key.
		 */
		@Nonnull private final Map<Integer, EntityIndex> indexesByPrimaryKey = CollectionUtils.createHashMap(16);
		/**
		 * Number of write acquisitions by key, per key.
		 */
		@Nonnull private final Map<EntityIndexKey, Integer> acquisitionsByKey = CollectionUtils.createHashMap(16);
		/**
		 * Number of write acquisitions by primary key, per primary key.
		 */
		@Nonnull private final Map<Integer, Integer> acquisitionsByPrimaryKey = CollectionUtils.createHashMap(16);
		/**
		 * Primary key of the next index the maintainer creates.
		 */
		private int nextPrimaryKey = 1_000;

		/**
		 * Registers an index under its own key and primary key.
		 *
		 * @param index the index
		 * @return the index
		 */
		@Nonnull
		EntityIndex register(@Nonnull EntityIndex index) {
			return registerUnder(index.getIndexKey(), index);
		}

		/**
		 * Registers an index under the given key and its own primary key.
		 *
		 * @param key   the key to register the index under
		 * @param index the index
		 * @return the index
		 */
		@Nonnull
		EntityIndex registerUnder(@Nonnull EntityIndexKey key, @Nonnull EntityIndex index) {
			this.indexes.put(key, index);
			this.indexesByPrimaryKey.put(index.getPrimaryKey(), index);
			return index;
		}

		/**
		 * Returns how many times the key was acquired for writing.
		 *
		 * @param key the key
		 * @return the number of write acquisitions
		 */
		int acquisitionsByKey(@Nonnull EntityIndexKey key) {
			return this.acquisitionsByKey.getOrDefault(key, 0);
		}

		/**
		 * Returns how many times the primary key was acquired for writing.
		 *
		 * @param primaryKey the primary key
		 * @return the number of write acquisitions
		 */
		int acquisitionsByPrimaryKey(int primaryKey) {
			return this.acquisitionsByPrimaryKey.getOrDefault(primaryKey, 0);
		}

		@Nonnull
		@Override
		public EntityIndex getOrCreateIndex(@Nonnull EntityIndexKey entityIndexKey) {
			this.acquisitionsByKey.merge(entityIndexKey, 1, Integer::sum);
			final EntityIndex existing = this.indexes.get(entityIndexKey);
			return existing == null ? register(createIndex(entityIndexKey)) : existing;
		}

		@Nonnull
		@Override
		public EntityIndex getOrCreateIndexByPrimaryKey(int indexPrimaryKey) {
			this.acquisitionsByPrimaryKey.merge(indexPrimaryKey, 1, Integer::sum);
			final EntityIndex existing = this.indexesByPrimaryKey.get(indexPrimaryKey);
			if (existing == null) {
				// the production buffer fails the premise the same way
				throw new GenericEvitaInternalError("No index with primary key " + indexPrimaryKey + "!");
			}
			return existing;
		}

		@Nullable
		@Override
		public EntityIndex getIndexIfExists(@Nonnull EntityIndexKey entityIndexKey) {
			return this.indexes.get(entityIndexKey);
		}

		@Nullable
		@Override
		public EntityIndex getIndexByPrimaryKeyIfExists(int indexPrimaryKey) {
			return this.indexesByPrimaryKey.get(indexPrimaryKey);
		}

		@Override
		public void removeIndex(@Nonnull EntityIndexKey entityIndexKey) {
			final EntityIndex removed = this.indexes.remove(entityIndexKey);
			if (removed != null) {
				this.indexesByPrimaryKey.remove(removed.getPrimaryKey());
			}
		}

		/**
		 * Creates an empty index of the key's type, as the collection's maintainer does.
		 *
		 * @param key the key
		 * @return a fresh index
		 */
		@Nonnull
		private EntityIndex createIndex(@Nonnull EntityIndexKey key) {
			final int primaryKey = this.nextPrimaryKey++;
			final String entityType =
				EntityIndexLocalMutationExecutorPartitionAcquisitionTest.this.productSchema.getName();
			return switch (key.type()) {
				case REFERENCED_ENTITY -> new ReducedEntityIndex(primaryKey, entityType, key);
				case REFERENCED_ENTITY_TYPE -> new ReferencedTypeEntityIndex(primaryKey, entityType, key);
				case GLOBAL -> new GlobalEntityIndex(primaryKey, entityType, key);
				default -> throw new UnsupportedOperationException(
					"The test maintainer does not create indexes of type " + key.type()
				);
			};
		}
	}

}
