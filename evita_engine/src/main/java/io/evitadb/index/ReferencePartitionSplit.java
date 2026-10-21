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

import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.NumberUtils;

import javax.annotation.Nonnull;

/**
 * A set of candidate partition primary keys of one reference split by representation: the partitions the registry of
 * single-owner partitions holds a row for - read virtually - and the rest, read through their reduced entity indexes.
 * Produced by {@link ReferencePartitions#split(Bitmap, ReferencedTypeEntityIndex...)} in one merge of the candidates
 * with the registry rows, so a caller learns the owner and the target of every virtual candidate without resolving any
 * partition.
 *
 * The virtual candidates are kept as aligned columns in ascending partition primary key order; a handle of one is built
 * only when asked for ({@link #getVirtualPartition(int)}).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class ReferencePartitionSplit {
	/**
	 * The candidates no registry holds a row for.
	 */
	@Nonnull private final Bitmap materializedPrimaryKeys;
	/**
	 * The candidates a registry holds a row for, ascending.
	 */
	@Nonnull private final int[] virtualPrimaryKeys;
	/**
	 * The row of each virtual candidate, `pack(ownerPrimaryKey, targetPrimaryKey)`, aligned with
	 * {@link #virtualPrimaryKeys}.
	 */
	@Nonnull private final long[] virtualOwnersAndTargets;
	/**
	 * The referenced-type entity index whose registry holds the row of each virtual candidate - which also names its
	 * scope - aligned with {@link #virtualPrimaryKeys}.
	 */
	@Nonnull private final ReferencedTypeEntityIndex[] virtualTypeIndexes;

	/**
	 * Creates the split.
	 *
	 * @param materializedPrimaryKeys the candidates no registry holds a row for
	 * @param virtualPrimaryKeys      the candidates a registry holds a row for, ascending
	 * @param virtualOwnersAndTargets the row of each virtual candidate, aligned with `virtualPrimaryKeys`
	 * @param virtualTypeIndexes      the referenced-type entity index holding each row, aligned with
	 *                                `virtualPrimaryKeys`
	 */
	ReferencePartitionSplit(
		@Nonnull Bitmap materializedPrimaryKeys,
		@Nonnull int[] virtualPrimaryKeys,
		@Nonnull long[] virtualOwnersAndTargets,
		@Nonnull ReferencedTypeEntityIndex[] virtualTypeIndexes
	) {
		this.materializedPrimaryKeys = materializedPrimaryKeys;
		this.virtualPrimaryKeys = virtualPrimaryKeys;
		this.virtualOwnersAndTargets = virtualOwnersAndTargets;
		this.virtualTypeIndexes = virtualTypeIndexes;
	}

	/**
	 * Returns the candidates no registry holds a row for - those read through their reduced entity indexes.
	 *
	 * @return the materialized candidates
	 */
	@Nonnull
	public Bitmap getMaterializedPrimaryKeys() {
		return this.materializedPrimaryKeys;
	}

	/**
	 * Returns how many candidates are read virtually.
	 *
	 * @return the number of virtual candidates
	 */
	public int getVirtualCount() {
		return this.virtualPrimaryKeys.length;
	}

	/**
	 * Returns the primary key of a virtual candidate.
	 *
	 * @param index the position of the candidate among the virtual ones, in ascending primary key order
	 * @return the partition primary key
	 */
	public int getVirtualPrimaryKey(int index) {
		return this.virtualPrimaryKeys[index];
	}

	/**
	 * Returns the primary key of the only owner of a virtual candidate.
	 *
	 * @param index the position of the candidate among the virtual ones
	 * @return the owner primary key
	 */
	public int getVirtualOwnerPrimaryKey(int index) {
		return NumberUtils.unpackHigh(this.virtualOwnersAndTargets[index]);
	}

	/**
	 * Returns the primary key of the entity a virtual candidate references.
	 *
	 * @param index the position of the candidate among the virtual ones
	 * @return the target primary key
	 */
	public int getVirtualTargetPrimaryKey(int index) {
		return NumberUtils.unpackLow(this.virtualOwnersAndTargets[index]);
	}

	/**
	 * Returns the referenced-type entity index whose registry holds the row of a virtual candidate.
	 *
	 * @param index the position of the candidate among the virtual ones
	 * @return the referenced-type entity index
	 */
	@Nonnull
	public ReferencedTypeEntityIndex getVirtualTypeIndex(int index) {
		return this.virtualTypeIndexes[index];
	}

	/**
	 * Builds the handle of a virtual candidate - see {@link ReferencePartitions#resolveVirtualIfExists}.
	 *
	 * @param index the position of the candidate among the virtual ones
	 * @return the virtual partition
	 */
	@Nonnull
	public VirtualReferencePartition getVirtualPartition(int index) {
		return ReferencePartitions.toVirtualPartition(
			this.virtualTypeIndexes[index], this.virtualPrimaryKeys[index], this.virtualOwnersAndTargets[index]
		);
	}

}
