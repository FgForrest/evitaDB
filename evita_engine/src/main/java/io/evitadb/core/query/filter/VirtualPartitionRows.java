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

import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.dataType.Scope;
import io.evitadb.index.ReferencePartitionSplit;
import io.evitadb.index.ReferencedTypeEntityIndex;
import io.evitadb.index.VirtualReferencePartition;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/**
 * The rows of registered single-owner partitions of one reference in one scope that a reference body is evaluated
 * exactly for - the universe `U` of the virtual reading. It is installed on the processing scope of
 * the referenced-type entity index evaluating the body (see
 * {@link FilterByVisitor.ProcessingScope#doWithVirtualPartitionRows}), and every leaf whose type-level answer differs
 * from its answer per partition reads it to answer the rows exactly:
 *
 * - `entityPrimaryKeyInSet` compares the target the row stores, so a reference to a managed target that does not
 *   exist matches as the reduced entity index of the partition matches it;
 * - `entityLocaleEquals` tests the only owner of the row against the locales of the owner collection in the scope;
 * - `groupHaving` looks the row up among the reduced group indexes of the matching groups;
 * - `inScope` compares the scope it names with the scope of the rows: naming it, the container is its children,
 *   naming another scope it is unmet.
 *
 * A negation and `attributeIs` need no override: a single-owner partition holds one row, so the subtraction the
 * referenced-type entity index answers in place is the per-partition answer once it is intersected with `U`.
 *
 * The rows are kept as aligned columns in ascending partition primary key order, so a leaf answers with a constant
 * over the partition primary keys of the rows it selects - a formula identified by its content, carrying no identity
 * of the registry the rows were read from.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class VirtualPartitionRows {
	/**
	 * The referenced-type entity index whose registry holds the rows - it names their reference and scope.
	 */
	@Nonnull private final ReferencedTypeEntityIndex typeIndex;
	/**
	 * The partition primary keys of the rows, ascending.
	 */
	@Nonnull private final int[] partitionPrimaryKeys;
	/**
	 * The only owner of each row, aligned with {@link #partitionPrimaryKeys}.
	 */
	@Nonnull private final int[] ownerPrimaryKeys;
	/**
	 * The target each row references, aligned with {@link #partitionPrimaryKeys}.
	 */
	@Nonnull private final int[] targetPrimaryKeys;
	/**
	 * Reads the representative reference key of the row at a position - only `groupHaving` needs it, and reading it
	 * costs a registry lookup, so it is read on demand.
	 */
	@Nonnull private final IntFunction<RepresentativeReferenceKey> representativeReferenceKeyAccessor;
	/**
	 * The partition primary keys of the rows as a bitmap, built on the first request.
	 */
	@Nullable private Bitmap primaryKeys;

	/**
	 * Creates the rows.
	 *
	 * @param typeIndex                          the referenced-type entity index whose registry holds the rows
	 * @param partitionPrimaryKeys               the partition primary keys of the rows, ascending
	 * @param ownerPrimaryKeys                   the only owner of each row
	 * @param targetPrimaryKeys                  the target of each row
	 * @param representativeReferenceKeyAccessor reads the representative reference key of the row at a position
	 */
	private VirtualPartitionRows(
		@Nonnull ReferencedTypeEntityIndex typeIndex,
		@Nonnull int[] partitionPrimaryKeys,
		@Nonnull int[] ownerPrimaryKeys,
		@Nonnull int[] targetPrimaryKeys,
		@Nonnull IntFunction<RepresentativeReferenceKey> representativeReferenceKeyAccessor
	) {
		this.typeIndex = typeIndex;
		this.partitionPrimaryKeys = partitionPrimaryKeys;
		this.ownerPrimaryKeys = ownerPrimaryKeys;
		this.targetPrimaryKeys = targetPrimaryKeys;
		this.representativeReferenceKeyAccessor = representativeReferenceKeyAccessor;
	}

	/**
	 * Splits the virtual candidates of a split into the rows of each referenced-type entity index holding them - one
	 * instance per scope the candidates come from. Costs one pass over the virtual candidates.
	 *
	 * @param split          the split of the candidates
	 * @return the rows per referenced-type entity index, empty when the split holds no virtual candidate
	 */
	@Nonnull
	public static VirtualPartitionRows[] of(
		@Nonnull ReferencePartitionSplit split
	) {
		final int virtualCount = split.getVirtualCount();
		if (virtualCount == 0) {
			return new VirtualPartitionRows[0];
		}
		// a reference has one type index per scope, so the candidates come from two of them at most
		final ReferencedTypeEntityIndex[] typeIndexes = new ReferencedTypeEntityIndex[Scope.values().length];
		final int[] counts = new int[typeIndexes.length];
		int typeIndexCount = 0;
		for (int i = 0; i < virtualCount; i++) {
			final int slot = slotOf(typeIndexes, typeIndexCount, split.getVirtualTypeIndex(i));
			if (slot == typeIndexCount) {
				typeIndexes[typeIndexCount++] = split.getVirtualTypeIndex(i);
			}
			counts[slot]++;
		}
		final VirtualPartitionRows[] result = new VirtualPartitionRows[typeIndexCount];
		for (int slot = 0; slot < typeIndexCount; slot++) {
			final ReferencedTypeEntityIndex typeIndex = typeIndexes[slot];
			final int[] positions = new int[counts[slot]];
			final int[] partitionPrimaryKeys = new int[counts[slot]];
			final int[] owners = new int[counts[slot]];
			final int[] targets = new int[counts[slot]];
			int row = 0;
			// the split keeps the candidates ascending, so the rows of every type index stay ascending too
			for (int i = 0; i < virtualCount; i++) {
				if (split.getVirtualTypeIndex(i) == typeIndex) {
					positions[row] = i;
					partitionPrimaryKeys[row] = split.getVirtualPrimaryKey(i);
					owners[row] = split.getVirtualOwnerPrimaryKey(i);
					targets[row] = split.getVirtualTargetPrimaryKey(i);
					row++;
				}
			}
			result[slot] = new VirtualPartitionRows(
				typeIndex, partitionPrimaryKeys, owners, targets,
				it -> split.getVirtualPartition(positions[it]).getRepresentativeReferenceKey()
			);
		}
		return result;
	}

	/**
	 * Creates the rows of virtual partitions of one referenced-type entity index.
	 *
	 * @param typeIndex      the referenced-type entity index whose registry holds the partitions
	 * @param partitions     the partitions, ascending by primary key, each held by `typeIndex`
	 * @return the rows
	 */
	@Nonnull
	public static VirtualPartitionRows of(
		@Nonnull ReferencedTypeEntityIndex typeIndex,
		@Nonnull VirtualReferencePartition[] partitions
	) {
		final int[] partitionPrimaryKeys = new int[partitions.length];
		final int[] owners = new int[partitions.length];
		final int[] targets = new int[partitions.length];
		for (int i = 0; i < partitions.length; i++) {
			final VirtualReferencePartition partition = partitions[i];
			Assert.isPremiseValid(
				partition.typeIndex() == typeIndex && (i == 0 || partitionPrimaryKeys[i - 1] < partition.primaryKey()),
				"Virtual partitions must be held by one type index and ascending by primary key!"
			);
			partitionPrimaryKeys[i] = partition.primaryKey();
			owners[i] = partition.ownerPrimaryKey();
			targets[i] = partition.getReferenceKey().primaryKey();
		}
		return new VirtualPartitionRows(
			typeIndex, partitionPrimaryKeys, owners, targets,
			it -> partitions[it].getRepresentativeReferenceKey()
		);
	}

	/**
	 * Returns the slot of a type index among those found so far, or `count` when it is new.
	 *
	 * @param typeIndexes the type indexes found so far
	 * @param count       how many were found
	 * @param typeIndex   the type index looked for
	 * @return its slot
	 */
	private static int slotOf(
		@Nonnull ReferencedTypeEntityIndex[] typeIndexes,
		int count,
		@Nonnull ReferencedTypeEntityIndex typeIndex
	) {
		for (int slot = 0; slot < count; slot++) {
			if (typeIndexes[slot] == typeIndex) {
				return slot;
			}
		}
		return count;
	}

	/**
	 * Returns the referenced-type entity index whose registry holds the rows.
	 *
	 * @return the referenced-type entity index
	 */
	@Nonnull
	public ReferencedTypeEntityIndex getTypeIndex() {
		return this.typeIndex;
	}

	/**
	 * Returns the scope of the rows - the scope of their owners.
	 *
	 * @return the scope
	 */
	@Nonnull
	public Scope getScope() {
		return this.typeIndex.getIndexKey().scope();
	}

	/**
	 * Returns how many rows there are.
	 *
	 * @return the number of rows
	 */
	public int size() {
		return this.partitionPrimaryKeys.length;
	}

	/**
	 * Returns the only owner of a row.
	 *
	 * @param row the position of the row
	 * @return the owner primary key
	 */
	public int getOwnerPrimaryKey(int row) {
		return this.ownerPrimaryKeys[row];
	}

	/**
	 * Returns the target a row references.
	 *
	 * @param row the position of the row
	 * @return the target primary key
	 */
	public int getTargetPrimaryKey(int row) {
		return this.targetPrimaryKeys[row];
	}

	/**
	 * Returns the representative reference key of a row. The cost depends on which factory built this instance:
	 * rows from {@link #of(ReferencePartitionSplit)} resolve the tuple through the registry on
	 * every call, `O(log n)`; rows from {@link #of(ReferencedTypeEntityIndex, VirtualReferencePartition[])}
	 * read it off an already-resolved {@link VirtualReferencePartition}, a plain field read, `O(1)`.
	 *
	 * @param row the position of the row
	 * @return the representative reference key
	 */
	@Nonnull
	public RepresentativeReferenceKey getRepresentativeReferenceKey(int row) {
		return this.representativeReferenceKeyAccessor.apply(row);
	}

	/**
	 * Returns the partition primary keys of every row.
	 *
	 * @return the partition primary keys, a bitmap shared by every caller
	 */
	@Nonnull
	public Bitmap getPartitionPrimaryKeys() {
		if (this.primaryKeys == null) {
			this.primaryKeys = new BaseBitmap(this.partitionPrimaryKeys);
		}
		return this.primaryKeys;
	}

	/**
	 * Returns a constant over the partition primary keys of the rows the predicate selects. Costs one pass over the
	 * rows plus the predicate per row.
	 *
	 * @param selected tells whether the row at a position is selected
	 * @return the constant, or {@link EmptyFormula#INSTANCE} when no row is selected
	 */
	@Nonnull
	public Formula select(@Nonnull IntPredicate selected) {
		final int[] result = new int[this.partitionPrimaryKeys.length];
		int count = 0;
		for (int row = 0; row < this.partitionPrimaryKeys.length; row++) {
			if (selected.test(row)) {
				result[count++] = this.partitionPrimaryKeys[row];
			}
		}
		if (count == 0) {
			return EmptyFormula.INSTANCE;
		}
		// ascending by construction - the rows are
		return new ConstantFormula(new BaseBitmap(count == result.length ? result : Arrays.copyOf(result, count)));
	}

	@Override
	public String toString() {
		return "VirtualPartitionRows{" + this.typeIndex.getIndexKey() + ", " + this.partitionPrimaryKeys.length +
			" rows}";
	}

}
