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
// TODO JNO - remove at the end of #1615
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.MutationHandle;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.EntityIndexKey;
import io.evitadb.index.GlobalEntityIndex;
import io.evitadb.index.IndexMaintainer;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartition;
import io.evitadb.utils.CollectionUtils;
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

import static io.evitadb.test.TestTags.INDEXING;
import static io.evitadb.test.TestTags.REFERENCE;
import static io.evitadb.utils.Assert.isPremiseValid;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// TODO JNO - remove at the end of #1615
/**
 * Pins the acquisition of reference partitions on the write path of a mutation that writes the single-owner
 * partitions of its catalog virtually: every acquisition looks the partition up and creates nothing - its index object
 * when there is one, the virtual handle built from its registry row otherwise - and fails, naming the write path, when
 * there is neither. The lookups read the registry and nothing else: no index is created and no primary key is
 * allocated. A mutation that does not write virtually, and a reference whose partitions are never virtual, acquire
 * their partitions exactly as before - creating what they do not find.
 *
 * The index maintainer is a real map of real indexes that counts every index it creates and every primary key it
 * allocates; a registry row without an index object is seeded the way a partition entering its first owner registers
 * it - under a primary key the maintainer allocates, listed for its target in the type index.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Partition acquisition on the virtual write path")
@Tag(INDEXING)
@Tag(REFERENCE)
class PartitionAcquisitionFunnelTest extends AbstractMutatorTestBase {
	/**
	 * A reference that allows no duplicates - at most one partition per target.
	 */
	private static final String SINGLE_REFERENCE = "funnelSingleRef";
	/**
	 * A reference that allows duplicates - one partition per target and representative value.
	 */
	private static final String DUPLICATE_REFERENCE = "funnelDuplicateRef";
	/**
	 * The representative attribute of {@link #DUPLICATE_REFERENCE}.
	 */
	private static final String REPRESENTATIVE_ATTRIBUTE = "variant";
	/**
	 * A reference indexed for partitioning - its partitions are never virtual.
	 */
	private static final String PARTITIONING_REFERENCE = "funnelPartitioningRef";
	/**
	 * Primary key of the referenced entity every test acquires partitions of.
	 */
	private static final int TARGET_PK = 7;
	/**
	 * Primary key of the only owner of every seeded registry row.
	 */
	private static final int OWNER_PK = 1;
	/**
	 * Names the write path in the acquisitions of the tests.
	 */
	private static final String WRITE_SITE = "funnelTestSite";

	/**
	 * The index maintainer both executors write through.
	 */
	@Nonnull private final CountingIndexMaintainer indexMaintainer = new CountingIndexMaintainer();
	/**
	 * The executor of a mutation writing the single-owner partitions virtually.
	 */
	@Nonnull private final EntityIndexLocalMutationExecutor virtualExecutor =
		createExecutor(MutationHandle.WRITTEN_VIRTUALLY);
	/**
	 * The executor of a mutation writing every partition as an index object.
	 */
	@Nonnull private final EntityIndexLocalMutationExecutor objectExecutor = createExecutor(MutationHandle.MAINTAINED);

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
			)
			.withReferenceTo(
				PARTITIONING_REFERENCE, PARTITIONING_REFERENCE, Cardinality.ZERO_OR_MORE,
				thatIs -> thatIs.indexedForFilteringAndPartitioning()
			);
	}

	/**
	 * Creates an executor of the product collection writing through {@link #indexMaintainer}.
	 *
	 * @param handle the handle of the mutation
	 * @return the executor
	 */
	@Nonnull
	private EntityIndexLocalMutationExecutor createExecutor(@Nonnull MutationHandle handle) {
		return createExecutor(handle, new PartitionReservations());
	}

	/**
	 * Creates an executor of the product collection writing through {@link #indexMaintainer} with the given
	 * reservations of its root mutation.
	 *
	 * @param handle       the handle of the mutation
	 * @param reservations the reservations of the root mutation
	 * @return the executor
	 */
	@Nonnull
	private EntityIndexLocalMutationExecutor createExecutor(
		@Nonnull MutationHandle handle,
		@Nonnull PartitionReservations reservations
	) {
		final AtomicInteger sequencer = new AtomicInteger(1);
		return new EntityIndexLocalMutationExecutor(
			this.containerAccessor,
			OWNER_PK,
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
			handle,
			reservations
		);
	}

	/**
	 * Creates the representative reference key of the target of {@link #SINGLE_REFERENCE} or
	 * {@link #PARTITIONING_REFERENCE}.
	 *
	 * @param referenceName the reference
	 * @return the key
	 */
	@Nonnull
	private static RepresentativeReferenceKey keyOf(@Nonnull String referenceName) {
		return new RepresentativeReferenceKey(new ReferenceKey(referenceName, TARGET_PK));
	}

	/**
	 * Creates the representative reference key of the target of {@link #DUPLICATE_REFERENCE} with a representative
	 * value.
	 *
	 * @param representativeValue the representative attribute value
	 * @return the key
	 */
	@Nonnull
	private static RepresentativeReferenceKey duplicateKeyOf(@Nonnull String representativeValue) {
		return new RepresentativeReferenceKey(
			new ReferenceKey(DUPLICATE_REFERENCE, TARGET_PK), new Serializable[]{representativeValue}
		);
	}

	/**
	 * Returns the type index of the reference in the live scope, creating it when there is none yet.
	 *
	 * @param referenceName the reference
	 * @return the type index
	 */
	@Nonnull
	private ReferencedTypeEntityIndex typeIndexOf(@Nonnull String referenceName) {
		final EntityIndexKey typeIndexKey = new EntityIndexKey(
			EntityIndexType.REFERENCED_ENTITY_TYPE, Scope.LIVE, referenceName
		);
		final EntityIndex existing = this.indexMaintainer.getIndexIfExists(typeIndexKey);
		return (ReferencedTypeEntityIndex) (
			existing == null ?
				this.indexMaintainer.register(
					new ReferencedTypeEntityIndex(900, this.productSchema.getName(), typeIndexKey)
				) :
				existing
		);
	}

	/**
	 * Registers the row of a single-owner partition of the key without an index object, under a primary key the
	 * maintainer allocates, and lists it for its target in the type index.
	 *
	 * @param key the representative reference key of the partition
	 * @return the primary key of the partition
	 */
	private int seedRow(@Nonnull RepresentativeReferenceKey key) {
		final int primaryKey = this.indexMaintainer.allocatePartitionPrimaryKey();
		seedRow(key, primaryKey);
		return primaryKey;
	}

	/**
	 * Registers the row of a single-owner partition of the key under the given primary key and lists it for its
	 * target in the type index.
	 *
	 * @param key        the representative reference key of the partition
	 * @param primaryKey the primary key of the partition
	 */
	private void seedRow(@Nonnull RepresentativeReferenceKey key, int primaryKey) {
		final ReferencedTypeEntityIndex typeIndex = typeIndexOf(key.referenceName());
		VirtualReferencePartitionWriter.partitionEntered(
			typeIndex.getVirtualReferencePartitionIndex(), primaryKey, OWNER_PK, key
		);
		typeIndex.insertPrimaryKeyIfMissing(primaryKey, key.primaryKey());
	}

	/**
	 * Registers an index object of the partition of the key and lists it for its target in the type index.
	 *
	 * @param key        the representative reference key of the partition
	 * @param primaryKey the primary key of the partition
	 * @return the index object
	 */
	@Nonnull
	private ReducedEntityIndex addObject(@Nonnull RepresentativeReferenceKey key, int primaryKey) {
		final ReducedEntityIndex partition = new ReducedEntityIndex(
			primaryKey, this.productSchema.getName(), ReferencePartitions.createKey(Scope.LIVE, key)
		);
		this.indexMaintainer.register(partition);
		typeIndexOf(key.referenceName()).insertPrimaryKeyIfMissing(primaryKey, key.primaryKey());
		return partition;
	}

	/**
	 * Asserts that the partition is the virtual handle of the seeded row.
	 *
	 * @param primaryKey the primary key of the seeded row
	 * @param key        the representative reference key of the seeded row
	 * @param partition  the acquired partition
	 */
	private static void assertHandleOfRow(
		int primaryKey,
		@Nonnull RepresentativeReferenceKey key,
		@Nonnull ReferencePartition partition
	) {
		final VirtualReferencePartition handle = assertInstanceOf(VirtualReferencePartition.class, partition);
		assertEquals(primaryKey, handle.getPrimaryKey());
		assertEquals(key, handle.getRepresentativeReferenceKey());
		assertEquals(ReferencePartitions.createKey(Scope.LIVE, key), handle.getIndexKey());
		assertEquals(OWNER_PK, handle.ownerPrimaryKey());
	}

	/**
	 * Collects the partitions of {@link #TARGET_PK} the executor hands out for writing.
	 *
	 * @param executor      the executor
	 * @param referenceName the reference
	 * @return the partitions in the order the consumer received them
	 */
	@Nonnull
	private List<ReferencePartition> collectPartitionsOfTarget(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull String referenceName
	) {
		final List<ReferencePartition> partitions = new ArrayList<>(4);
		executor.forEachPartitionOfTargetForWrite(
			Scope.LIVE, this.productSchema.getReferenceOrThrowException(referenceName), TARGET_PK, partitions::add
		);
		return partitions;
	}

	@Test
	@DisplayName("the references of the fixture may be virtual except the one indexed for partitioning")
	void shouldShadowEveryReferenceButThePartitioningOne() {
		assertTrue(
			VirtualReferencePartitionWriter.isShadowed(
				this.productSchema.getReferenceOrThrowException(SINGLE_REFERENCE), Scope.LIVE
			)
		);
		assertTrue(
			VirtualReferencePartitionWriter.isShadowed(
				this.productSchema.getReferenceOrThrowException(DUPLICATE_REFERENCE), Scope.LIVE
			)
		);
		assertFalse(
			VirtualReferencePartitionWriter.isShadowed(
				this.productSchema.getReferenceOrThrowException(PARTITIONING_REFERENCE), Scope.LIVE
			)
		);
	}

	@Nested
	@DisplayName("by representative reference key")
	class ByKey {

		@Test
		@DisplayName("the index object wins over the registry row of the same partition")
		void shouldPreferIndexObjectWhenBothExist() {
			final RepresentativeReferenceKey key = keyOf(SINGLE_REFERENCE);
			final ReducedEntityIndex object = addObject(key, 31);
			seedRow(key, 31);
			final PartitionAcquisitionFunnelTest outer = PartitionAcquisitionFunnelTest.this;
			assertSame(object, outer.virtualExecutor.getPartitionForWrite(Scope.LIVE, key, WRITE_SITE));
			assertSame(object, outer.virtualExecutor.getOrCreatePartitionForInsert(Scope.LIVE, key, WRITE_SITE));
			assertEquals(
				2, outer.indexMaintainer.acquisitionsByKey(object.getIndexKey()),
				"the index object must be registered for modification by both acquisitions"
			);
		}

		@Test
		@DisplayName("a row without an index object answers its handle, for a reference without duplicates")
		void shouldAnswerHandleOfRowWhenReferenceDisallowsDuplicates() {
			final RepresentativeReferenceKey key = keyOf(SINGLE_REFERENCE);
			final int primaryKey = seedRow(key);
			final PartitionAcquisitionFunnelTest outer = PartitionAcquisitionFunnelTest.this;
			assertHandleOfRow(
				primaryKey, key, outer.virtualExecutor.getPartitionForWrite(Scope.LIVE, key, WRITE_SITE)
			);
			assertHandleOfRow(
				primaryKey, key, outer.virtualExecutor.getOrCreatePartitionForInsert(Scope.LIVE, key, WRITE_SITE)
			);
		}

		@Test
		@DisplayName("a row without an index object answers its handle, for a reference with duplicates")
		void shouldAnswerHandleOfRowWhenReferenceAllowsDuplicates() {
			final RepresentativeReferenceKey german = duplicateKeyOf("DE");
			final RepresentativeReferenceKey american = duplicateKeyOf("US");
			final int germanPrimaryKey = seedRow(german);
			final int americanPrimaryKey = seedRow(american);
			final PartitionAcquisitionFunnelTest outer = PartitionAcquisitionFunnelTest.this;
			assertHandleOfRow(
				germanPrimaryKey, german, outer.virtualExecutor.getPartitionForWrite(Scope.LIVE, german, WRITE_SITE)
			);
			assertHandleOfRow(
				americanPrimaryKey, american,
				outer.virtualExecutor.getPartitionForWrite(Scope.LIVE, american, WRITE_SITE)
			);
		}

		@Test
		@DisplayName("the lookups create no index and allocate no primary key")
		void shouldLeaveSequenceUntouchedWhenLookingUp() {
			final RepresentativeReferenceKey single = keyOf(SINGLE_REFERENCE);
			final RepresentativeReferenceKey german = duplicateKeyOf("DE");
			seedRow(single);
			seedRow(german);
			final CountingIndexMaintainer maintainer = PartitionAcquisitionFunnelTest.this.indexMaintainer;
			final int indexCount = maintainer.indexCount();
			final int nextPrimaryKey = maintainer.nextPrimaryKey();
			final EntityIndexLocalMutationExecutor executor = PartitionAcquisitionFunnelTest.this.virtualExecutor;
			executor.getPartitionForWrite(Scope.LIVE, single, WRITE_SITE);
			executor.getOrCreatePartitionForInsert(Scope.LIVE, german, WRITE_SITE);
			collectPartitionsOfTarget(executor, SINGLE_REFERENCE);
			collectPartitionsOfTarget(executor, DUPLICATE_REFERENCE);
			assertEquals(indexCount, maintainer.indexCount(), "no index may be created");
			assertEquals(nextPrimaryKey, maintainer.nextPrimaryKey(), "no primary key may be handed out");
		}

		@Test
		@DisplayName("a lookup finding neither an index object nor a row fails, naming the write path")
		void shouldFailNamingWritePathWhenLookupMisses() {
			// a known tuple of another target and an unknown tuple of this one
			seedRow(
				new RepresentativeReferenceKey(
					new ReferenceKey(DUPLICATE_REFERENCE, TARGET_PK + 1), new Serializable[]{"DE"}
				)
			);
			final CountingIndexMaintainer maintainer = PartitionAcquisitionFunnelTest.this.indexMaintainer;
			final int indexCount = maintainer.indexCount();
			for (final RepresentativeReferenceKey key : List.of(
				keyOf(SINGLE_REFERENCE), duplicateKeyOf("DE"), duplicateKeyOf("FR")
			)) {
				final GenericEvitaInternalError error = assertThrows(
					GenericEvitaInternalError.class,
					() -> PartitionAcquisitionFunnelTest.this.virtualExecutor
						.getPartitionForWrite(Scope.LIVE, key, WRITE_SITE)
				);
				assertTrue(error.getPrivateMessage().contains(WRITE_SITE), error.getPrivateMessage());
				assertTrue(error.getPrivateMessage().contains("neither"), error.getPrivateMessage());
			}
			assertEquals(indexCount, maintainer.indexCount(), "a failed lookup must create nothing");
		}

		@Test
		@DisplayName("an insert finding neither an index object nor a row mints a handle under a new primary key")
		void shouldMintVirtualHandleWhenInsertMisses() {
			final PartitionAcquisitionFunnelTest outer = PartitionAcquisitionFunnelTest.this;
			final RepresentativeReferenceKey key = keyOf(SINGLE_REFERENCE);
			final ReferencedTypeEntityIndex typeIndex = outer.typeIndexOf(SINGLE_REFERENCE);
			final CountingIndexMaintainer maintainer = outer.indexMaintainer;
			final int indexCount = maintainer.indexCount();
			final int nextPrimaryKey = maintainer.nextPrimaryKey();

			final VirtualReferencePartition minted = assertInstanceOf(
				VirtualReferencePartition.class,
				outer.virtualExecutor.getOrCreatePartitionForInsert(Scope.LIVE, key, WRITE_SITE)
			);

			assertEquals(nextPrimaryKey, minted.getPrimaryKey(), "the handle takes the next primary key");
			assertEquals(nextPrimaryKey + 1, maintainer.nextPrimaryKey(), "exactly one primary key is allocated");
			assertEquals(OWNER_PK, minted.ownerPrimaryKey(), "the handle names the entering owner");
			assertEquals(key, minted.getRepresentativeReferenceKey());
			assertSame(typeIndex, minted.typeIndex());
			assertEquals(indexCount, maintainer.indexCount(), "minting creates no index object");
			assertTrue(
				typeIndex.getVirtualReferencePartitionIndex().getPartitionOwner(minted.getPrimaryKey()).isEmpty(),
				"minting registers no row - the owner's entering the partition does"
			);
		}

		@Test
		@DisplayName("an insert takes the primary key the root mutation reserved for the key, once")
		void shouldTakeReservedPrimaryKeyOnReentry() {
			final PartitionAcquisitionFunnelTest outer = PartitionAcquisitionFunnelTest.this;
			final RepresentativeReferenceKey key = keyOf(SINGLE_REFERENCE);
			final ReferencedTypeEntityIndex typeIndex = outer.typeIndexOf(SINGLE_REFERENCE);
			final CountingIndexMaintainer maintainer = outer.indexMaintainer;
			final int vacatedPrimaryKey = maintainer.allocatePartitionPrimaryKey();
			outer.virtualExecutor.reserveVacatedPartition(
				ReferencePartitions.toWriteHandle(typeIndex, vacatedPrimaryKey, OWNER_PK, key)
			);
			final int nextPrimaryKey = maintainer.nextPrimaryKey();

			assertEquals(
				vacatedPrimaryKey,
				outer.virtualExecutor.getOrCreatePartitionForInsert(Scope.LIVE, key, WRITE_SITE).getPrimaryKey(),
				"the owner entering the vacated partition keeps its primary key"
			);
			assertEquals(nextPrimaryKey, maintainer.nextPrimaryKey(), "taking a reservation allocates nothing");
			// the reservation is consumed - the next insert finding nothing mints anew
			assertEquals(
				nextPrimaryKey,
				outer.virtualExecutor.getOrCreatePartitionForInsert(Scope.LIVE, key, WRITE_SITE).getPrimaryKey()
			);
			// a reservation of another key is not taken
			outer.virtualExecutor.reserveVacatedPartition(
				ReferencePartitions.toWriteHandle(typeIndex, vacatedPrimaryKey, OWNER_PK, key)
			);
			assertEquals(
				nextPrimaryKey + 1,
				outer.virtualExecutor.getOrCreatePartitionForInsert(
					Scope.LIVE, new RepresentativeReferenceKey(new ReferenceKey(SINGLE_REFERENCE, TARGET_PK + 1)),
					WRITE_SITE
				).getPrimaryKey()
			);
			// and a lookup of the reserved key names the reservation in its refusal
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> outer.virtualExecutor.getPartitionForWrite(Scope.LIVE, key, WRITE_SITE)
			);
			assertTrue(
				error.getPrivateMessage().contains("left it as partition " + vacatedPrimaryKey),
				error.getPrivateMessage()
			);
		}

		@Test
		@DisplayName("an insert minting a partition before its referenced-type index exists is refused")
		void shouldRefuseMintingBeforeTypeIndexExists() {
			final CountingIndexMaintainer maintainer = PartitionAcquisitionFunnelTest.this.indexMaintainer;
			final int nextPrimaryKey = maintainer.nextPrimaryKey();
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> PartitionAcquisitionFunnelTest.this.virtualExecutor
					.getOrCreatePartitionForInsert(Scope.LIVE, keyOf(SINGLE_REFERENCE), WRITE_SITE)
			);
			assertTrue(error.getPrivateMessage().contains(WRITE_SITE), error.getPrivateMessage());
			assertTrue(error.getPrivateMessage().contains("referenced-type index"), error.getPrivateMessage());
			assertEquals(nextPrimaryKey, maintainer.nextPrimaryKey(), "a refused insert must allocate nothing");
		}

		@Test
		@DisplayName("an insert while the root mutation is finishing is refused, a lookup is not")
		void shouldRefuseInsertWhileRootMutationIsFinishing() {
			final PartitionAcquisitionFunnelTest outer = PartitionAcquisitionFunnelTest.this;
			final PartitionReservations reservations = new PartitionReservations();
			final EntityIndexLocalMutationExecutor executor =
				outer.createExecutor(MutationHandle.WRITTEN_VIRTUALLY, reservations);
			final RepresentativeReferenceKey key = keyOf(SINGLE_REFERENCE);
			final int primaryKey = outer.seedRow(key);
			reservations.setFinishing(true);
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> executor.getOrCreatePartitionForInsert(Scope.LIVE, key, WRITE_SITE)
			);
			assertTrue(error.getPrivateMessage().contains(WRITE_SITE), error.getPrivateMessage());
			assertTrue(error.getPrivateMessage().contains("finishing"), error.getPrivateMessage());
			assertEquals(primaryKey, executor.getPartitionForWrite(Scope.LIVE, key, WRITE_SITE).getPrimaryKey());
			reservations.setFinishing(false);
			assertEquals(
				primaryKey, executor.getOrCreatePartitionForInsert(Scope.LIVE, key, WRITE_SITE).getPrimaryKey()
			);
		}

		@Test
		@DisplayName("a promotion installs an empty index object under the primary key of the row")
		void shouldInstallEmptyIndexObjectUnderPrimaryKeyOfRowOnPromotion() {
			final PartitionAcquisitionFunnelTest outer = PartitionAcquisitionFunnelTest.this;
			final RepresentativeReferenceKey key = keyOf(SINGLE_REFERENCE);
			final int primaryKey = outer.seedRow(key);
			final CountingIndexMaintainer maintainer = outer.indexMaintainer;
			final int nextPrimaryKey = maintainer.nextPrimaryKey();
			final VirtualReferencePartition handle = assertInstanceOf(
				VirtualReferencePartition.class, outer.virtualExecutor.getPartitionForWrite(Scope.LIVE, key, WRITE_SITE)
			);

			final ReducedEntityIndex installed = outer.virtualExecutor.installPartitionObject(handle, WRITE_SITE);

			assertEquals(primaryKey, installed.getPrimaryKey(), "the index object takes the primary key of the row");
			assertEquals(ReferencePartitions.createKey(Scope.LIVE, key), installed.getIndexKey());
			assertEquals(0, installed.size(), "the caller writes the first owner into the empty index object");
			assertSame(installed, maintainer.getIndexIfExists(installed.getIndexKey()));
			assertSame(installed, maintainer.getIndexByPrimaryKeyIfExists(primaryKey));
			assertEquals(nextPrimaryKey, maintainer.nextPrimaryKey(), "the installation allocates no primary key");
			assertSame(
				installed, outer.virtualExecutor.getPartitionForWrite(Scope.LIVE, key, WRITE_SITE),
				"the index object wins over the row from now on"
			);
		}

		@Test
		@DisplayName("a promotion while the root mutation is finishing is refused before anything is installed")
		void shouldRefusePromotionWhileRootMutationIsFinishing() {
			final PartitionAcquisitionFunnelTest outer = PartitionAcquisitionFunnelTest.this;
			final PartitionReservations reservations = new PartitionReservations();
			final EntityIndexLocalMutationExecutor executor =
				outer.createExecutor(MutationHandle.WRITTEN_VIRTUALLY, reservations);
			final RepresentativeReferenceKey key = keyOf(SINGLE_REFERENCE);
			outer.seedRow(key);
			final VirtualReferencePartition handle = assertInstanceOf(
				VirtualReferencePartition.class, executor.getPartitionForWrite(Scope.LIVE, key, WRITE_SITE)
			);
			final int indexCount = outer.indexMaintainer.indexCount();
			reservations.setFinishing(true);

			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class, () -> executor.installPartitionObject(handle, WRITE_SITE)
			);

			assertTrue(error.getPrivateMessage().contains(WRITE_SITE), error.getPrivateMessage());
			assertTrue(error.getPrivateMessage().contains("finishing"), error.getPrivateMessage());
			assertEquals(indexCount, outer.indexMaintainer.indexCount(), "a refused promotion installs nothing");
		}

		@Test
		@DisplayName("a mutation not writing virtually creates the index object a row stands for, as before")
		void shouldCreateIndexObjectWhenNotWritingVirtually() {
			final RepresentativeReferenceKey key = keyOf(SINGLE_REFERENCE);
			final int primaryKey = seedRow(key);
			final PartitionAcquisitionFunnelTest outer = PartitionAcquisitionFunnelTest.this;
			final ReferencePartition looked = outer.objectExecutor.getPartitionForWrite(Scope.LIVE, key, WRITE_SITE);
			final ReducedEntityIndex created = assertInstanceOf(ReducedEntityIndex.class, looked);
			assertTrue(primaryKey != created.getPrimaryKey(), "the created index object must not take the row's key");
			assertSame(created, outer.objectExecutor.getOrCreatePartitionForInsert(Scope.LIVE, key, WRITE_SITE));
		}

		@Test
		@DisplayName("a reference indexed for partitioning creates its index object, even when writing virtually")
		void shouldCreateIndexObjectOfPartitioningReferenceWhenWritingVirtually() {
			final RepresentativeReferenceKey key = keyOf(PARTITIONING_REFERENCE);
			final PartitionAcquisitionFunnelTest outer = PartitionAcquisitionFunnelTest.this;
			final ReferencePartition created = outer.virtualExecutor.getPartitionForWrite(Scope.LIVE, key, WRITE_SITE);
			assertInstanceOf(ReducedEntityIndex.class, created);
			assertSame(created, outer.virtualExecutor.getOrCreatePartitionForInsert(Scope.LIVE, key, WRITE_SITE));
		}
	}

	@Nested
	@DisplayName("partitions of one target")
	class OfTarget {

		@Test
		@DisplayName("the single partition of a reference without duplicates is the handle of its row")
		void shouldAnswerHandleOfRowWhenReferenceDisallowsDuplicates() {
			final RepresentativeReferenceKey key = keyOf(SINGLE_REFERENCE);
			final int primaryKey = seedRow(key);
			final List<ReferencePartition> partitions = collectPartitionsOfTarget(
				PartitionAcquisitionFunnelTest.this.virtualExecutor, SINGLE_REFERENCE
			);
			assertEquals(1, partitions.size());
			assertHandleOfRow(primaryKey, key, partitions.get(0));
		}

		@Test
		@DisplayName("a target without a partition yields none and creates nothing")
		void shouldYieldNothingWhenTargetHasNoPartition() {
			final CountingIndexMaintainer maintainer = PartitionAcquisitionFunnelTest.this.indexMaintainer;
			final int indexCount = maintainer.indexCount();
			assertTrue(collectPartitionsOfTarget(
				PartitionAcquisitionFunnelTest.this.virtualExecutor, SINGLE_REFERENCE).isEmpty()
			);
			assertTrue(collectPartitionsOfTarget(
				PartitionAcquisitionFunnelTest.this.virtualExecutor, DUPLICATE_REFERENCE).isEmpty()
			);
			assertEquals(indexCount, maintainer.indexCount(), "nothing may be created");
		}

		@Test
		@DisplayName("the partitions of a reference with duplicates are index objects and handles of rows alike")
		void shouldMixIndexObjectsAndHandlesWhenReferenceAllowsDuplicates() {
			final ReducedEntityIndex german = addObject(duplicateKeyOf("DE"), 21);
			final RepresentativeReferenceKey american = duplicateKeyOf("US");
			final int americanPrimaryKey = seedRow(american);
			final List<ReferencePartition> partitions = collectPartitionsOfTarget(
				PartitionAcquisitionFunnelTest.this.virtualExecutor, DUPLICATE_REFERENCE
			);
			assertEquals(2, partitions.size());
			assertSame(german, partitions.get(0));
			assertHandleOfRow(americanPrimaryKey, american, partitions.get(1));
			assertEquals(1, PartitionAcquisitionFunnelTest.this.indexMaintainer.acquisitionsByPrimaryKey(21));
		}

		@Test
		@DisplayName("a listed primary key with neither an index object nor a row fails")
		void shouldFailWhenListedPrimaryKeyHasNeitherObjectNorRow() {
			typeIndexOf(DUPLICATE_REFERENCE).insertPrimaryKeyIfMissing(23, TARGET_PK);
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> collectPartitionsOfTarget(
					PartitionAcquisitionFunnelTest.this.virtualExecutor, DUPLICATE_REFERENCE
				)
			);
			assertTrue(error.getPrivateMessage().contains("primary key 23"), error.getPrivateMessage());
			assertTrue(
				error.getPrivateMessage().contains("forEachPartitionOfTargetForWrite"), error.getPrivateMessage()
			);
		}
	}

	@Nested
	@DisplayName("group family by primary key")
	class GroupFamily {

		@Test
		@DisplayName("a primary key naming a registered partition is refused on the group-family walk")
		void shouldRefuseRegisteredPartitionOnGroupFamilyWalk() {
			final int primaryKey = seedRow(keyOf(SINGLE_REFERENCE));
			final GenericEvitaInternalError error = assertThrows(
				GenericEvitaInternalError.class,
				() -> PartitionAcquisitionFunnelTest.this.virtualExecutor
					.getGroupIndexByPrimaryKeyForModification(Scope.LIVE, SINGLE_REFERENCE, primaryKey)
			);
			assertTrue(
				error.getPrivateMessage().contains("registered single-owner partition"), error.getPrivateMessage()
			);
		}

		@Test
		@DisplayName("a primary key naming nothing answers nothing on the group-family walk")
		void shouldAnswerNothingForUnknownPrimaryKeyOnGroupFamilyWalk() {
			seedRow(keyOf(SINGLE_REFERENCE));
			final PartitionAcquisitionFunnelTest outer = PartitionAcquisitionFunnelTest.this;
			assertNull(outer.virtualExecutor.getGroupIndexByPrimaryKeyForModification(Scope.LIVE, SINGLE_REFERENCE, 77));
			final int primaryKey = outer.indexMaintainer.nextPrimaryKey() - 1;
			assertNull(
				outer.objectExecutor.getGroupIndexByPrimaryKeyForModification(Scope.LIVE, SINGLE_REFERENCE, primaryKey),
				"a mutation not writing virtually does not consult the registry"
			);
		}
	}

	/**
	 * A map-based index maintainer that counts how each index was reached, every index it creates and every primary
	 * key it hands out. Unlike the collection's maintainer it answers `null` for a primary key it holds no index of,
	 * which lets the group-family walk see the missing index.
	 */
	private final class CountingIndexMaintainer implements IndexMaintainer<EntityIndexKey, EntityIndex> {
		/**
		 * The indexes by key.
		 */
		@Nonnull private final Map<EntityIndexKey, EntityIndex> indexes = CollectionUtils.createHashMap(16);
		/**
		 * The indexes by primary key.
		 */
		@Nonnull private final Map<Integer, EntityIndex> indexesByPrimaryKey = CollectionUtils.createHashMap(16);
		/**
		 * The number of acquisitions per key.
		 */
		@Nonnull private final Map<EntityIndexKey, Integer> acquisitionsByKey = CollectionUtils.createHashMap(16);
		/**
		 * The number of acquisitions per primary key.
		 */
		@Nonnull private final Map<Integer, Integer> acquisitionsByPrimaryKey = CollectionUtils.createHashMap(16);
		/**
		 * The next primary key the maintainer hands out - to a created index or to an allocation.
		 */
		private int nextPrimaryKey = 1_000;

		/**
		 * Registers an index under its own key.
		 *
		 * @param index the index
		 * @return the index
		 */
		@Nonnull
		EntityIndex register(@Nonnull EntityIndex index) {
			this.indexes.put(index.getIndexKey(), index);
			this.indexesByPrimaryKey.put(index.getPrimaryKey(), index);
			return index;
		}

		/**
		 * Returns the number of indexes the maintainer holds.
		 *
		 * @return the number of indexes
		 */
		int indexCount() {
			return this.indexes.size();
		}

		/**
		 * Returns the next primary key the maintainer hands out.
		 *
		 * @return the next primary key
		 */
		int nextPrimaryKey() {
			return this.nextPrimaryKey;
		}

		/**
		 * Returns how many times the key was acquired through the enrolling accessor.
		 *
		 * @param key the key
		 * @return the number of acquisitions
		 */
		int acquisitionsByKey(@Nonnull EntityIndexKey key) {
			return this.acquisitionsByKey.getOrDefault(key, 0);
		}

		/**
		 * Returns how many times the primary key was acquired through the enrolling accessor.
		 *
		 * @param primaryKey the primary key
		 * @return the number of acquisitions
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

		@SuppressWarnings("DataFlowIssue")
		@Nonnull
		@Override
		public EntityIndex getOrCreateIndexByPrimaryKey(int indexPrimaryKey) {
			this.acquisitionsByPrimaryKey.merge(indexPrimaryKey, 1, Integer::sum);
			return this.indexesByPrimaryKey.get(indexPrimaryKey);
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
			isPremiseValid(
				!this.indexes.containsKey(indexKey) && !this.indexesByPrimaryKey.containsKey(primaryKey),
				"The test maintainer installs an index under a free key and primary key only!"
			);
			return register(factory.apply(primaryKey));
		}

		@Override
		public int allocatePartitionPrimaryKey() {
			return this.nextPrimaryKey++;
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
			final String entityType = PartitionAcquisitionFunnelTest.this.productSchema.getName();
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
