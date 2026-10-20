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
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.IndexMaintainer;
import io.evitadb.index.IndexType;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReducedGroupEntityIndex;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.mutation.local.EntityIndexLocalMutationExecutor.Target;
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
import java.util.function.IntFunction;

import static io.evitadb.index.mutation.local.PartitionWriteKind.ADDING;
import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the write acquisition of reference partitions on {@link EntityIndexLocalMutationExecutor}: every partition
 * handed out for writing must come through the enrolling accessor of the index maintainer (so that its changed
 * storage parts are persisted and the commit-time merge sees it), the by-key acquisition of a reference indexed for
 * partitioning creates what it does not find, and the partitions of one target are found by the same two routes as
 * the read-side resolvers. The acquisition of a reference whose partitions may be virtual creates nothing - see
 * `PartitionAcquisitionFunnelTest`. The fan-out
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
	 * A reference indexed for partitioning - its partitions are never virtual.
	 */
	private static final String PARTITIONING_REFERENCE = "acquisitionPartitioningRef";
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
			new PartitionReservations()
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
				ReferenceSchemaEditor::indexedForFiltering
			)
			.withReferenceTo(
				DUPLICATE_REFERENCE, DUPLICATE_REFERENCE, Cardinality.ZERO_OR_MORE_WITH_DUPLICATES,
				thatIs -> thatIs
					.indexedForFiltering()
					.withAttribute(REPRESENTATIVE_ATTRIBUTE, String.class, attr -> attr.filterable().representative())
			)
			.withReferenceTo(
				PARTITIONING_REFERENCE, PARTITIONING_REFERENCE, Cardinality.ZERO_OR_MORE,
				ReferenceSchemaEditor::indexedForFilteringAndPartitioning
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
			Scope.LIVE, reference(referenceName), TARGET_PK, ADDING, partitions::add
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
				new ReferenceKey(PARTITIONING_REFERENCE, TARGET_PK)
			);
			final ReferencePartition created = EntityIndexLocalMutationExecutorPartitionAcquisitionTest.this
				.partitionExecutor.getOrCreatePartitionForInsert(Scope.LIVE, rrk, "test", ADDING);
			assertInstanceOf(ReducedEntityIndex.class, created);
			assertEquals(rrk, created.getRepresentativeReferenceKey());
			assertEquals(Scope.LIVE, created.getIndexKey().scope());

			final ReferencePartition again = EntityIndexLocalMutationExecutorPartitionAcquisitionTest.this
				.partitionExecutor.getOrCreatePartitionForInsert(Scope.LIVE, rrk, "test", ADDING);
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
				new ReferenceKey(PARTITIONING_REFERENCE, TARGET_PK)
			);
			EntityIndexLocalMutationExecutorPartitionAcquisitionTest.this.indexMaintainer.registerUnder(
				ReferencePartitions.createKey(Scope.LIVE, rrk),
				new GlobalEntityIndex(500, ENTITY_NAME, new EntityIndexKey(EntityIndexType.GLOBAL))
			);
			assertThrows(
				GenericEvitaInternalError.class,
				() -> EntityIndexLocalMutationExecutorPartitionAcquisitionTest.this.partitionExecutor
					.getOrCreatePartitionForInsert(Scope.LIVE, rrk, "test", ADDING)
			);
		}
	}

	@Nested
	@DisplayName("partitions of one target")
	class OfTarget {

		@Test
		@DisplayName("creates the single partition of a reference without duplicates")
		void shouldCreateSinglePartitionWhenReferenceDisallowsDuplicates() {
			final List<ReferencePartition> partitions = collectPartitionsOfTarget(PARTITIONING_REFERENCE);
			assertEquals(1, partitions.size());
			final ReferencePartition partition = partitions.getFirst();
			assertEquals(new ReferenceKey(PARTITIONING_REFERENCE, TARGET_PK), partition.getReferenceKey());
			assertEquals(
				1,
				EntityIndexLocalMutationExecutorPartitionAcquisitionTest.this.indexMaintainer.acquisitionsByKey(
					partition.getIndexKey()
				)
			);
			assertSame(partition, collectPartitionsOfTarget(PARTITIONING_REFERENCE).getFirst());
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
		@DisplayName("finds nothing of a reference with duplicates without a type index, and creates no type index")
		void shouldReturnNothingAndCreateNoTypeIndexWhenReferenceHasNoPartitions() {
			assertTrue(collectPartitionsOfTarget(DUPLICATE_REFERENCE).isEmpty());
			assertNull(
				EntityIndexLocalMutationExecutorPartitionAcquisitionTest.this.indexMaintainer.getIndexIfExists(
					new EntityIndexKey(EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, DUPLICATE_REFERENCE)
				),
				"the partitions of the reference may be virtual, so their acquisition must create nothing"
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
						Functions.alwaysTrue(), true, IterationPath.BOTH, ADDING
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
						Functions.alwaysTrue(), true, IterationPath.BOTH, ADDING
					)
			);
			assertTrue(
				error.getPrivateMessage().contains(ReferenceIndexType.FOR_FILTERING.name()), error.getPrivateMessage()
			);
			assertEquals(0, invocations.get(), "the consumer must never be reached");
		}
	}

	@Nested
	@DisplayName("writing as another owner")
	class AsOtherOwner {
		/**
		 * Primary key of the other owner the executor under test writes as.
		 */
		private static final int OTHER_OWNER_PK = 42;

		/**
		 * Creates the executor writing as {@link #OTHER_OWNER_PK}, whose data the parent's accessor serves through an
		 * accessor of its own.
		 *
		 * @return the executor writing as the other owner
		 */
		@Nonnull
		private EntityIndexLocalMutationExecutor otherOwnerExecutor() {
			EntityIndexLocalMutationExecutorPartitionAcquisitionTest.this.containerAccessor
				.registerAccessorOfOtherEntity(OTHER_OWNER_PK, new MockStorageContainerAccessor());
			return EntityIndexLocalMutationExecutorPartitionAcquisitionTest.this.partitionExecutor
				.createExecutorOfOtherOwner(OTHER_OWNER_PK);
		}

		/**
		 * Creates an empty partition of the given reference for {@link #TARGET_PK}, unknown to any index maintainer.
		 *
		 * @param primaryKey    primary key of the partition
		 * @param referenceName the reference the partition belongs to
		 * @return the partition
		 */
		@Nonnull
		private ReducedEntityIndex partitionOf(int primaryKey, @Nonnull String referenceName) {
			return new ReducedEntityIndex(
				primaryKey, EntityIndexLocalMutationExecutorPartitionAcquisitionTest.this.productSchema.getName(),
				ReferencePartitions.createKey(
					Scope.LIVE, new RepresentativeReferenceKey(new ReferenceKey(referenceName, TARGET_PK))
				)
			);
		}

		/**
		 * Asserts that populating the index with the other owner fails on the premise that it is a partition of the
		 * given reference whose partitions may be virtual, leaving the index without any owner.
		 *
		 * @param index           the index handed for population
		 * @param referenceSchema the reference handed for population
		 */
		private void assertRefusedAsNoVirtualPartition(
			@Nonnull EntityIndex index,
			@Nonnull ReferenceSchemaContract referenceSchema
		) {
			final EntityIndexLocalMutationExecutor otherOwner = otherOwnerExecutor();
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> ReferenceIndexMutator.populatePartitionWithOwner(otherOwner, index, referenceSchema)
			);
			assertTrue(error.getPrivateMessage().contains("that may be virtual"), error.getPrivateMessage());
			assertArrayEquals(new int[0], index.getAllPrimaryKeys().getArray(), "the index must stay without owners");
		}

		@Test
		@DisplayName("an executor writing as another owner reports that owner and indexes under its primary key")
		void shouldReportOtherOwnerWhenWritingAsIt() {
			final EntityIndexLocalMutationExecutor otherOwner = otherOwnerExecutor();
			assertEquals(OTHER_OWNER_PK, otherOwner.getOwnerPrimaryKey());
			assertEquals(OTHER_OWNER_PK, otherOwner.getPrimaryKeyToIndex(IndexType.ATTRIBUTE_INDEX, Target.NEW));
			assertEquals(
				1, EntityIndexLocalMutationExecutorPartitionAcquisitionTest.this.partitionExecutor.getOwnerPrimaryKey(),
				"the executor it was created from keeps writing as its own owner"
			);
		}

		@Test
		@DisplayName("the executor of an owner the root mutation does not mutate is handed out again")
		void shouldHandOutTheSameExecutorWhenTheOwnerIsNotMutated() {
			final EntityIndexLocalMutationExecutor first = otherOwnerExecutor();
			final EntityIndexLocalMutationExecutor second =
				EntityIndexLocalMutationExecutorPartitionAcquisitionTest.this.partitionExecutor
					.createExecutorOfOtherOwner(OTHER_OWNER_PK);
			assertSame(first, second, "the owner cannot change while the root mutation runs");
			assertEquals(OTHER_OWNER_PK, second.getOwnerPrimaryKey());
		}

		@Test
		@DisplayName("an owner the root mutation started mutating gets a new executor on every call")
		void shouldCreateANewExecutorEveryTimeWhenTheOwnerIsMutated() {
			final EntityIndexLocalMutationExecutor remembered = otherOwnerExecutor();
			EntityIndexLocalMutationExecutorPartitionAcquisitionTest.this.containerAccessor
				.markOtherEntityInFlight(OTHER_OWNER_PK);
			final EntityIndexLocalMutationExecutor afterMutationStarted =
				EntityIndexLocalMutationExecutorPartitionAcquisitionTest.this.partitionExecutor
					.createExecutorOfOtherOwner(OTHER_OWNER_PK);
			final EntityIndexLocalMutationExecutor again =
				EntityIndexLocalMutationExecutorPartitionAcquisitionTest.this.partitionExecutor
					.createExecutorOfOtherOwner(OTHER_OWNER_PK);
			assertNotSame(remembered, afterMutationStarted, "the remembered executor reads the owner's old state");
			assertNotSame(afterMutationStarted, again, "the state of an owner being mutated goes on changing");
			assertEquals(OTHER_OWNER_PK, again.getOwnerPrimaryKey());
		}

		@Test
		@DisplayName("an executor writing as another owner refuses every index acquisition, naming the owner")
		void shouldRefuseEveryIndexAcquisitionWhenWritingAsOtherOwner() {
			final EntityIndexLocalMutationExecutor otherOwner = otherOwnerExecutor();
			final EntityIndexKey globalKey = new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE);
			final List<GenericEvitaInternalError> errors = List.of(
				assertThrows(GenericEvitaInternalError.class, () -> otherOwner.getOrCreateIndex(globalKey)),
				assertThrows(GenericEvitaInternalError.class, () -> otherOwner.getIndexIfExists(globalKey)),
				assertThrows(
					GenericEvitaInternalError.class, () -> otherOwner.getEntityIndexByPrimaryKeyForModification(1)
				),
				assertThrows(GenericEvitaInternalError.class, () -> otherOwner.getCatalogIndex(Scope.LIVE))
			);
			for (GenericEvitaInternalError error : errors) {
				assertTrue(
					error.getPrivateMessage().contains("entity " + OTHER_OWNER_PK), error.getPrivateMessage()
				);
			}
			final CountingIndexMaintainer maintainer =
				EntityIndexLocalMutationExecutorPartitionAcquisitionTest.this.indexMaintainer;
			assertEquals(0, maintainer.acquisitionsByKey(globalKey), "no refusal may reach the parent's indexes");
			assertEquals(0, maintainer.acquisitionsByPrimaryKey(1), "no refusal may reach the parent's indexes");
			assertNull(maintainer.getIndexIfExists(globalKey), "no refusal may create an index");
		}

		@Test
		@DisplayName("populating a partition refuses the global index")
		void shouldRefusePopulatingGlobalIndex() {
			assertRefusedAsNoVirtualPartition(
				new GlobalEntityIndex(
					500, ENTITY_NAME, new EntityIndexKey(EntityIndexType.GLOBAL, Scope.LIVE)
				),
				reference(SINGLE_REFERENCE)
			);
		}

		@Test
		@DisplayName("populating a partition refuses a partition of another reference than the one handed with it")
		void shouldRefusePopulatingPartitionOfAnotherReference() {
			assertRefusedAsNoVirtualPartition(partitionOf(31, DUPLICATE_REFERENCE), reference(SINGLE_REFERENCE));
		}

		@Test
		@DisplayName("populating a partition refuses a partition of a reference indexed for partitioning")
		void shouldRefusePopulatingPartitionOfPartitioningReference() {
			assertRefusedAsNoVirtualPartition(
				partitionOf(32, PARTITIONING_REFERENCE), reference(PARTITIONING_REFERENCE)
			);
		}

		@Test
		@DisplayName("populating a partition refuses a partition that already has the owner")
		void shouldRefusePopulatingPartitionThatAlreadyHasOwner() {
			final ReducedEntityIndex partition = partitionOf(33, SINGLE_REFERENCE);
			partition.insertPrimaryKeyIfMissing(OTHER_OWNER_PK);
			final EntityIndexLocalMutationExecutor otherOwner = otherOwnerExecutor();
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> ReferenceIndexMutator.populatePartitionWithOwner(
					otherOwner, partition, reference(SINGLE_REFERENCE)
				)
			);
			assertTrue(
				error.getPrivateMessage().contains("already has the owner " + OTHER_OWNER_PK), error.getPrivateMessage()
			);
			assertArrayEquals(new int[]{OTHER_OWNER_PK}, partition.getAllPrimaryKeys().getArray());
			assertTrue(partition.getFilterIndexes().isEmpty(), "nothing of the owner's data may have been written");
			assertTrue(partition.getSortIndexes().isEmpty(), "nothing of the owner's data may have been written");
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

		@Nonnull
		@Override
		public EntityIndex installIndex(
			@Nonnull EntityIndexKey indexKey,
			int primaryKey,
			@Nonnull IntFunction<EntityIndex> factory
		) {
			throw new UnsupportedOperationException("The test maintainer does not install indexes");
		}

		@Override
		public int allocatePartitionPrimaryKey() {
			throw new UnsupportedOperationException("The test maintainer does not allocate primary keys");
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
