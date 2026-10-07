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

package io.evitadb.core.query.filter;

import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.utils.FormulaFactory;
import io.evitadb.index.ReducedEntityIndex;
import io.evitadb.index.ReferencePartition;
import io.evitadb.index.ReferencePartitionSplit;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The reference partitions of a set of target entities - the hierarchy nodes a hierarchy constraint or a hierarchy
 * statistics node names - split by representation: those read through their reduced entity indexes, resolved, and
 * those of registered single-owner partitions read virtually, of which only the owners are kept (step 4d of #1615).
 * Produced by {@link io.evitadb.core.query.QueryPlanningContext#resolvePartitionsOfTargetsForRead}.
 *
 * A hierarchy route asks a partition for nothing but its owners, and a single-owner partition has exactly one, so the
 * virtual partitions of all the targets collapse into one bitmap of owners and enter the formula tree as one constant
 * identified by its content - no handle is built and no index object is touched for them. When the query does not read
 * the collection virtually, every partition is materialized and the owners are empty.
 *
 * A block-by-block ordering narrowed by a hierarchy option needs the partitions themselves, the virtual ones as their
 * handles - {@link #getPartitions()} builds them on demand from the splits of the scopes read virtually, which the
 * instance keeps for that purpose only.
 *
 * @param materializedIndexes     the partitions read through their reduced entity indexes, resolved - first those of
 *                                the scopes read through the index objects alone, target by target and scope by
 *                                scope, then those of each scope read virtually, in partition primary key order
 * @param virtualOwners           the owners of the partitions read virtually, united - an owner holding rows of
 *                                several of them is listed once
 * @param virtualCount            how many partitions are read virtually; each holds exactly one owner row, so this is
 *                                what they contribute to a count of owner rows
 * @param scopeSplits             the split of each scope read virtually, in the order the scopes were read - empty
 *                                when no partition is read virtually
 * @param scopeMaterializedStarts the index into `materializedIndexes` at which the materialized partitions of each
 *                                scope of `scopeSplits` start, aligned with it
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record SplitTargetPartitions(
	@Nonnull List<ReducedEntityIndex> materializedIndexes,
	@Nonnull Bitmap virtualOwners,
	int virtualCount,
	@Nonnull ReferencePartitionSplit[] scopeSplits,
	@Nonnull int[] scopeMaterializedStarts
) {
	/**
	 * No split - the instance of a query that reads no partition virtually.
	 */
	private static final ReferencePartitionSplit[] NO_SPLITS = new ReferencePartitionSplit[0];

	/**
	 * Creates the partitions of targets none of which is read virtually - every one materialized.
	 *
	 * @param materializedIndexes the partitions, resolved
	 * @param virtualOwners       the owners of the partitions read virtually - empty
	 * @param virtualCount        how many partitions are read virtually - zero
	 */
	public SplitTargetPartitions(
		@Nonnull List<ReducedEntityIndex> materializedIndexes,
		@Nonnull Bitmap virtualOwners,
		int virtualCount
	) {
		this(materializedIndexes, virtualOwners, virtualCount, NO_SPLITS, ArrayUtils.EMPTY_INT_ARRAY);
	}

	/**
	 * Returns how many owner rows the partitions hold together - the size of every materialized partition plus one per
	 * virtual partition, which is the sum the reduced entity indexes of all the partitions would report.
	 *
	 * @return the number of owner rows
	 */
	public long getOwnerRowCount() {
		long result = this.virtualCount;
		for (final ReducedEntityIndex index : this.materializedIndexes) {
			result += index.getAllPrimaryKeys().size();
		}
		return result;
	}

	/**
	 * Creates the formula of the owners of all the partitions, optionally each partition's owners restricted by a
	 * formula: the owners of every materialized partition - restricted one partition at a time, as the hierarchy routes
	 * did before - and one constant over the owners of the virtual ones, restricted once.
	 *
	 * The constant is created anew by every call and carries no dependency on the registry the owners were read from:
	 * a write that changes them changes its content and with it the hash of every cacheable formula above it.
	 *
	 * @param restriction the formula each partition's owners are intersected with, `null` for none
	 * @return the formula of the owners, the empty formula when there are no partitions
	 */
	@Nonnull
	public Formula createOwnersFormula(@Nullable Formula restriction) {
		final int materializedCount = this.materializedIndexes.size();
		final boolean anyVirtual = !this.virtualOwners.isEmpty();
		final Formula[] formulas = new Formula[materializedCount + (anyVirtual ? 1 : 0)];
		for (int i = 0; i < materializedCount; i++) {
			formulas[i] = restrict(this.materializedIndexes.get(i).getAllPrimaryKeysFormula(), restriction);
		}
		if (anyVirtual) {
			formulas[materializedCount] = restrict(new ConstantFormula(this.virtualOwners), restriction);
		}
		// no formula yields the empty formula, a single one itself
		return FormulaFactory.or(formulas);
	}

	/**
	 * Returns every partition of the targets - the materialized ones as their reduced entity indexes, the ones read
	 * virtually as their {@link io.evitadb.index.VirtualReferencePartition} handles, built here from the rows of the
	 * splits and touching no index object. The partitions come in the order the object-only list of a hierarchy option
	 * holds the partitions of one target in: scope by scope, and within a scope in partition primary key order, the
	 * order in which the type index lists the partitions of a target. Those of the scopes read through the index
	 * objects alone come first, but a query reading the collection virtually reads a scope that way only when it holds
	 * no type index of the reference - and with it no partition.
	 *
	 * Without a partition read virtually the result is {@link #materializedIndexes()} itself.
	 *
	 * @return the partitions of the targets
	 */
	@Nonnull
	public List<? extends ReferencePartition> getPartitions() {
		if (this.scopeSplits.length == 0) {
			return this.materializedIndexes;
		}
		// the materialized partitions are only handed over, read through the partition contract
		final List<? extends ReferencePartition> materialized = this.materializedIndexes;
		final List<ReferencePartition> result = new ArrayList<>(materialized.size() + this.virtualCount);
		// the partitions of the scopes read through the index objects alone
		result.addAll(materialized.subList(0, this.scopeMaterializedStarts[0]));
		for (int i = 0; i < this.scopeSplits.length; i++) {
			final ReferencePartitionSplit split = this.scopeSplits[i];
			final int end = i + 1 < this.scopeSplits.length ? this.scopeMaterializedStarts[i + 1] : materialized.size();
			final int virtualCount = split.getVirtualCount();
			int virtualIndex = 0;
			// both sides of the scope are in partition primary key order - one merge keeps it
			for (int j = this.scopeMaterializedStarts[i]; j < end; j++) {
				final ReferencePartition partition = materialized.get(j);
				while (
					virtualIndex < virtualCount &&
						split.getVirtualPrimaryKey(virtualIndex) < partition.getPrimaryKey()
				) {
					result.add(split.getVirtualPartition(virtualIndex++));
				}
				result.add(partition);
			}
			while (virtualIndex < virtualCount) {
				result.add(split.getVirtualPartition(virtualIndex++));
			}
		}
		return result;
	}

	/**
	 * Intersects the owners of a partition with the restriction, when there is one.
	 *
	 * @param owners      the formula of the owners
	 * @param restriction the restriction, `null` for none
	 * @return the restricted formula
	 */
	@Nonnull
	private static Formula restrict(@Nonnull Formula owners, @Nullable Formula restriction) {
		return restriction == null ? owners : FormulaFactory.and(owners, restriction);
	}

}
