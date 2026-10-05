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


// TODO JNO - remove at the end of #1615 (the whole file)
package io.evitadb.index.mutation.local;

import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance.PromotionRehearsal;
import io.evitadb.index.EntityIndex;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.ReferencePartitions;
import io.evitadb.spi.store.catalog.persistence.accessor.WritableEntityStorageContainerAccessor;
import io.evitadb.spi.store.catalog.persistence.accessor.WritableEntityStorageContainerAccessor.LocaleWithScope;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static io.evitadb.utils.Assert.isPremiseValid;

// TODO JNO - remove at the end of #1615
/**
 * The {@link PromotionRehearsal} the reference index mutator hands to a probe when a single-owner partition gains its
 * second owner. It lets the probe write the partition's first owner into a partition no collection knows - through
 * {@link ReferenceIndexMutator#populatePartitionWithOwner}, the write a virtual partition's materialization needs - and
 * compare the result with the live partition, which still holds the first owner's data only.
 *
 * The first owner's data is read as the root mutation sees it, through an executor writing as the first owner (see
 * {@link EntityIndexLocalMutationExecutor#createExecutorOfOtherOwner(int)}): that executor maintains no virtual
 * partition and reports to no probe, so the rehearsal can neither re-enter the registry of single-owner partitions nor
 * the probe that runs it, and it acquires no index, so it touches nothing the running mutation keeps.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class PartitionPromotionRehearsal implements PromotionRehearsal {
	/**
	 * The executor of the mutation adding the second owner.
	 */
	@Nonnull private final EntityIndexLocalMutationExecutor executor;
	/**
	 * The reference the partition belongs to, as the executor's collection declares it.
	 */
	@Nonnull private final ReferenceSchemaContract referenceSchema;
	/**
	 * The live partition gaining its second owner.
	 */
	@Nonnull private final ReferencePartition partition;
	/**
	 * The primary key of the partition's first owner.
	 */
	private final int ownerPrimaryKey;
	/**
	 * The accessor of the first owner, resolved when first asked for.
	 */
	@Nullable private WritableEntityStorageContainerAccessor ownerAccessor;

	/**
	 * Creates the rehearsal of a partition that has just gained its second owner.
	 *
	 * @param executor        the executor of the mutation adding the second owner
	 * @param referenceSchema the reference the partition belongs to
	 * @param partition       the partition, holding exactly two owners
	 */
	PartitionPromotionRehearsal(
		@Nonnull EntityIndexLocalMutationExecutor executor,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull ReferencePartition partition
	) {
		final int[] owners = partition.getAllPrimaryKeys().getArray();
		isPremiseValid(
			owners.length == 2,
			() -> "Partition " + partition.getPrimaryKey() + " is expected to have two owners, it has " +
				owners.length + "!"
		);
		this.executor = executor;
		this.referenceSchema = referenceSchema;
		this.partition = partition;
		this.ownerPrimaryKey = owners[0] == executor.getOwnerPrimaryKey() ? owners[1] : owners[0];
	}

	@Nonnull
	@Override
	public String getEntityType() {
		return this.executor.getEntityType();
	}

	@Nonnull
	@Override
	public ReferencePartition getPartition() {
		return this.partition;
	}

	@Override
	public int getOwnerPrimaryKey() {
		return this.ownerPrimaryKey;
	}

	@Override
	public int getJoiningOwnerPrimaryKey() {
		return this.executor.getOwnerPrimaryKey();
	}

	@Nonnull
	@Override
	public LocaleWithScope[] getOwnerAddedLocales() {
		return getOwnerAccessor().getAddedLocales();
	}

	@Nonnull
	@Override
	public LocaleWithScope[] getOwnerRemovedLocales() {
		return getOwnerAccessor().getRemovedLocales();
	}

	@Override
	public void populate(@Nonnull EntityIndex partition) {
		final ReferencePartition rebuilt = ReferencePartitions.asPartition(partition);
		isPremiseValid(
			rebuilt != null && rebuilt != this.partition &&
				rebuilt.getIndexKey().equals(this.partition.getIndexKey()) && rebuilt.size() == 0,
			() -> "The rehearsal populates an empty partition of the key " + this.partition.getIndexKey() +
				" other than the live one!"
		);
		ReferenceIndexMutator.populatePartitionWithOwner(
			this.executor.createExecutorOfOtherOwner(this.ownerPrimaryKey), partition, this.referenceSchema
		);
	}

	/**
	 * Returns the accessor of the first owner - the one the executor writing as the first owner reads through.
	 *
	 * @return the accessor of the first owner
	 */
	@Nonnull
	private WritableEntityStorageContainerAccessor getOwnerAccessor() {
		if (this.ownerAccessor == null) {
			this.ownerAccessor = this.executor.getContainerAccessor().getAccessorOfOtherEntity(this.ownerPrimaryKey);
		}
		return this.ownerAccessor;
	}
}
