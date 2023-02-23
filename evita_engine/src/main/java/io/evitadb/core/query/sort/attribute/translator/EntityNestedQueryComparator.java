/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.core.query.sort.attribute.translator;

import io.evitadb.api.query.order.EntityProperty;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator;
import io.evitadb.api.requestResponse.data.structure.ReferenceDecorator;
import io.evitadb.core.query.QueryContext;
import io.evitadb.core.query.QueryPlan;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.index.array.CompositeObjectArray;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.utils.ArrayUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;

import static java.util.Optional.ofNullable;

/**
 * This comparator allows sorting {@link ReferenceDecorator} according properties on referenced entities.
 * In order to sort efficiently this comparator binds to the nested query execution and prior to calling
 * {@link #compare(ReferenceContract, ReferenceContract)} these two methods needs to be called in specific order:
 *
 * - {@link #initSorter(QueryContext, Sorter)} which initializes the sorted provided by nested {@link QueryPlan}
 * - {@link #setFilteredEntities(int[])} which initializes filtered referenced records and allows to prepare all
 * internal datastructures required form efficient comparisons
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@SuppressWarnings("ComparatorNotSerializable")
public class EntityNestedQueryComparator implements ReferenceComparator {
	/**
	 * Next comparator to be used for sorting {@link #nonSortedReferences}.
	 */
	@Nonnull private final ReferenceComparator nextComparator;
	/**
	 * The ordering constraint that should be used when preparing nested {@link QueryPlan}. We need to propagate this
	 * constraint later to this point of execution.
	 */
	@Nonnull @Getter private final EntityProperty orderBy;
	/**
	 * Reference to the query context used for nested query plan evaluation.
	 */
	@Nullable private QueryContext queryContext;
	/**
	 * The reference to created sorter.
	 */
	@Nullable private Sorter sorter;
	/**
	 * The array of filtered referenced entity primary keys.
	 * Array contains sorted, distinct array of primary keys.
	 */
	@Nullable private int[] entitiesFound;
	/**
	 * The array of indexes of the referenced primary keys in the sorted array. The index positions match the positions
	 * of the primary keys in {@link #entitiesFound} array. {@link ReferenceDecorator} comparison needs to only locate
	 * the primary key in {@link #entitiesFound} using binary search and then retrieve the index from this array on
	 * the same position and compare those for both references.
	 */
	@Nullable private int[] sortedEntityIndexes;
	/**
	 * Array of references that were not found by the {@link Sorter}.
	 */
	private CompositeObjectArray<ReferenceContract> nonSortedReferences;

	public EntityNestedQueryComparator(@Nonnull EntityProperty orderBy) {
		this(orderBy, null);
	}

	public EntityNestedQueryComparator(@Nonnull EntityProperty orderBy, @Nonnull ReferenceComparator comparatorForUnknownRecords) {
		this.orderBy = orderBy;
		this.nextComparator = comparatorForUnknownRecords;
	}

	/**
	 * Returns true if the internal datastructures of this comparator are initialized and {@link #compare(ReferenceContract, ReferenceContract)}
	 * can be safely used.
	 */
	public boolean isInitialized() {
		return this.sorter != null;
	}

	/**
	 * Initializes the query context used for evaluation nested {@link QueryPlan} and the {@link Sorter} that was
	 * identified by this plan.
	 */
	public void initSorter(@Nonnull QueryContext queryContext, @Nullable Sorter sorter) {
		this.queryContext = queryContext;
		this.sorter = sorter;
	}

	/**
	 * Initializes internal datastructures by using previously identified {@link Sorter}. This method needs to be
	 * preceded by {@link #initSorter(QueryContext, Sorter)} call.
	 */
	public void setFilteredEntities(@Nullable int[] filteredEntities) {
		final int[] sortedEntities;
		if (filteredEntities == null) {
			sortedEntities = new int[0];
		} else {
			sortedEntities = sorter == null ?
				filteredEntities :
				sorter.sortAndSlice(
					queryContext, new ConstantFormula(new BaseBitmap(filteredEntities)), 0, filteredEntities.length
				);
		}

		this.entitiesFound = new int[sortedEntities.length];
		this.sortedEntityIndexes = new int[sortedEntities.length];
		for (int i = 0; i < sortedEntities.length; i++) {
			this.entitiesFound[i] = sortedEntities[i];
			this.sortedEntityIndexes[i] = i;
		}
		Arrays.sort(this.entitiesFound);
		ArrayUtils.sortSecondAlongFirstArray(sortedEntities, this.sortedEntityIndexes);
	}

	@Nonnull
	@Override
	public Iterable<ReferenceContract> getNonSortedReferences() {
		return ofNullable((Iterable<ReferenceContract>) nonSortedReferences)
			.orElse(Collections.emptyList());
	}

	@Nonnull
	@Override
	public ReferenceComparator andThen(@Nonnull ReferenceComparator comparatorForUnknownRecords) {
		return new EntityNestedQueryComparator(orderBy, comparatorForUnknownRecords);
	}

	@Nonnull
	@Override
	public ReferenceComparator getNextComparator() {
		return nextComparator;
	}

	@Override
	public int compare(ReferenceContract o1, ReferenceContract o2) {
		final int o1Index = Arrays.binarySearch(entitiesFound, o1.getReferencedPrimaryKey());
		final int o2Index = Arrays.binarySearch(entitiesFound, o2.getReferencedPrimaryKey());
		final int o1Position = o1Index < 0 ? -1 : this.sortedEntityIndexes[o1Index];
		final int o2Position = o2Index < 0 ? -1 : this.sortedEntityIndexes[o2Index];
		if (o1Position >= 0 && o2Position >= 0) {
			return Integer.compare(o1Position, o2Position);
		} else if (o1Position < 0 && o2Position >= 0) {
			this.nonSortedReferences = ofNullable(this.nonSortedReferences)
				.orElseGet(() -> new CompositeObjectArray<>(ReferenceContract.class));
			this.nonSortedReferences.add(o1);
			return 1;
		} else if (o1Position >= 0) {
			this.nonSortedReferences = ofNullable(this.nonSortedReferences)
				.orElseGet(() -> new CompositeObjectArray<>(ReferenceContract.class));
			this.nonSortedReferences.add(o2);
			return -1;
		} else {
			this.nonSortedReferences = ofNullable(this.nonSortedReferences)
				.orElseGet(() -> new CompositeObjectArray<>(ReferenceContract.class));
			this.nonSortedReferences.add(o1);
			this.nonSortedReferences.add(o2);
			return 0;
		}
	}
}
