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
import io.evitadb.index.bitmap.Bitmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
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
 * @param materializedIndexes the partitions read through their reduced entity indexes, resolved
 * @param virtualOwners       the owners of the partitions read virtually, united - an owner holding rows of several of
 *                            them is listed once
 * @param virtualCount        how many partitions are read virtually; each holds exactly one owner row, so this is
 *                            what they contribute to a count of owner rows
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record SplitTargetPartitions(
	@Nonnull List<ReducedEntityIndex> materializedIndexes,
	@Nonnull Bitmap virtualOwners,
	int virtualCount
) {

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
