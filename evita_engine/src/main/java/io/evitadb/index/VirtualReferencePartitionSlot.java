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

/**
 * Names one kind of entry the {@link VirtualReferencePartitionIndex} keeps per partition. Every entry of the index
 * lives in a single tree keyed by `pack(slotId, partitionPrimaryKey)`, so the slot is the high half of each persisted
 * key and all entries of one slot form one contiguous key range.
 *
 * Because the {@link #getId() id} is baked into every key ever written, it is assigned explicitly and never derived
 * from the declaration order: reordering or inserting constants must not re-point the entries already on disk.
 *
 * The constants name the slots every index has. Slots the index declares at runtime - one per sort key, holding the
 * sort value of each partition, and one per chained attribute, holding the predecessor of each partition - take ids
 * from {@link #FIRST_DECLARED_SLOT_ID} up, so the ids below it stay reserved for constants and a constant added later
 * can never collide with a declared slot already on disk.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public enum VirtualReferencePartitionSlot {

	/**
	 * The partition's owner and the referenced entity it points to - the single authority on which owner a
	 * partition belongs to. The payload is `pack(ownerPrimaryKey, targetPrimaryKey)`.
	 */
	PARTITION_OWNER(0),
	/**
	 * The representative attribute values that tell the partition apart from other partitions of the same owner and
	 * referenced entity. The payload is the id of the value tuple in the index's tuple dictionary.
	 */
	REPRESENTATIVE_TUPLE(1);

	/**
	 * The smallest id a slot declared at runtime may take. Every id below it is reserved for a constant of this enum.
	 */
	public static final int FIRST_DECLARED_SLOT_ID = 16;

	/**
	 * The persisted slot id - the high half of every key of this slot.
	 */
	private final int id;

	/**
	 * Declares a slot with its persisted id.
	 *
	 * @param id the persisted slot id, non-negative and unique among the constants
	 */
	VirtualReferencePartitionSlot(int id) {
		this.id = id;
	}

	/**
	 * Returns the persisted slot id - the high half of every key of this slot.
	 *
	 * @return the slot id
	 */
	public int getId() {
		return this.id;
	}

}
