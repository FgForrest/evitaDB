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

import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.Scope;
import io.evitadb.index.VirtualReferencePartitionIndex;

import javax.annotation.Nonnull;

/**
 * Keeps the owner rows of the {@link VirtualReferencePartitionIndex} in step with the owners of the reference
 * partitions they describe. It is told about the transitions of a partition's owner count the reference index mutator
 * decides, and about nothing else:
 *
 * - **entered** (0 -> 1) - the partition gained its only owner: its row is registered, naming the owner, the entity
 *   the partition references and its representative attribute values. The mutator registers it before it replicates
 *   the owner's data into the partition, so the row is in place while that data is being written;
 * - **shared** (1 -> 2) - the partition gained a second owner and is no longer a single-owner one: everything the
 *   index records about it is dropped at once;
 * - **vacated** (1 -> 0) - the partition lost its last owner: everything the index records about it is dropped.
 *
 * Every drop is followed by the retirement of the declared sort and chain keys when no partition has an owner row
 * left, so an index without a single row declares no key either - a key nothing can hold would keep the index, and the
 * referenced-type entity index carrying it, from ever being empty.
 *
 * A partition that goes from two owners back to one (2 -> 1) is not decided here: whether it is a single-owner one
 * again is known only once the mutation that took the owner away has finished, so its row is a matter for the end of
 * that mutation.
 *
 * Only references indexed `FOR_FILTERING` with the referenced entity component in the scope have virtual partitions
 * (see {@link #isShadowed(ReferenceSchemaContract, Scope)}); a reference indexed for partitioning keeps a reduced
 * entity index for every partition.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class VirtualReferencePartitionWriter {

	private VirtualReferencePartitionWriter() {
	}

	/**
	 * Returns TRUE when the partitions of the reference in the scope may be virtual - the reference is indexed
	 * `FOR_FILTERING`, not for partitioning, and with the referenced entity component.
	 *
	 * @param referenceSchema the reference
	 * @param scope           the scope
	 * @return TRUE when the registry keeps rows for the reference in the scope
	 */
	static boolean isShadowed(@Nonnull ReferenceSchemaContract referenceSchema, @Nonnull Scope scope) {
		return referenceSchema.getReferenceIndexType(scope) == ReferenceIndexType.FOR_FILTERING &&
			referenceSchema.getIndexedComponents(scope).contains(ReferenceIndexedComponents.REFERENCED_ENTITY);
	}

	/**
	 * Registers the row of a partition that has just gained its only owner (0 -> 1).
	 *
	 * @param registry                   the registry of the reference in the partition's scope
	 * @param partitionPrimaryKey        the primary key of the partition
	 * @param ownerPrimaryKey            the primary key of its only owner
	 * @param representativeReferenceKey the key of the partition - the referenced entity and the representative
	 *                                   attribute values
	 */
	static void partitionEntered(
		@Nonnull VirtualReferencePartitionIndex registry,
		int partitionPrimaryKey,
		int ownerPrimaryKey,
		@Nonnull RepresentativeReferenceKey representativeReferenceKey
	) {
		registry.putPartitionOwner(partitionPrimaryKey, ownerPrimaryKey, representativeReferenceKey.primaryKey());
		// a reference without representative attributes has the empty tuple, which the registry stands for by absence
		if (representativeReferenceKey.representativeAttributeValues().length > 0) {
			registry.putRepresentativeTuple(
				partitionPrimaryKey, representativeReferenceKey.representativeAttributeValues()
			);
		}
	}

	/**
	 * Drops the row of a partition that has just gained its second owner (1 -> 2).
	 *
	 * @param registry            the registry of the reference in the partition's scope
	 * @param partitionPrimaryKey the primary key of the partition
	 */
	static void partitionShared(@Nonnull VirtualReferencePartitionIndex registry, int partitionPrimaryKey) {
		dropPartition(registry, partitionPrimaryKey);
	}

	/**
	 * Drops the row of a partition that has just lost its last owner (1 -> 0).
	 *
	 * @param registry            the registry of the reference in the partition's scope
	 * @param partitionPrimaryKey the primary key of the partition
	 */
	static void partitionVacated(@Nonnull VirtualReferencePartitionIndex registry, int partitionPrimaryKey) {
		dropPartition(registry, partitionPrimaryKey);
	}

	/**
	 * Drops everything the registry records about the partition and retires the declared keys when no partition has
	 * an owner row left.
	 *
	 * @param registry            the registry of the reference in the partition's scope
	 * @param partitionPrimaryKey the primary key of the partition
	 */
	private static void dropPartition(@Nonnull VirtualReferencePartitionIndex registry, int partitionPrimaryKey) {
		registry.removePartition(partitionPrimaryKey);
		registry.retireAllKeysIfNoPartition();
	}

}
