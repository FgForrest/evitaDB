/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.core.query.sort.attribute.translator;

import com.carrotsearch.hppc.IntContainer;
import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntSet;
import io.evitadb.api.query.order.EntityGroupProperty;
import io.evitadb.api.query.order.EntityProperty;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator;
import io.evitadb.api.requestResponse.data.structure.ReferenceDecorator;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlan;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.sort.ConditionalSorter;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Function;

import static java.util.Optional.ofNullable;

/**
 * This comparator allows sorting {@link ReferenceDecorator} according properties on referenced entities.
 * In order to sort efficiently this comparator binds to the nested query execution and prior to calling
 * {@link #compare(ReferenceContract, ReferenceContract)} these two methods needs to be called in specific order:
 *
 * - {@link #setSorter(QueryExecutionContext, Sorter)} or {@link #setGroupSorter(QueryExecutionContext, Sorter)} which initializes
 * the sorted provided by nested {@link QueryPlan}
 * - {@link #setFilteredEntities(int[], int[], Function)}  which initializes filtered referenced records and allows to
 * prepare all internal datastructures required form efficient comparisons
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@SuppressWarnings("ComparatorNotSerializable")
public class EntityNestedQueryComparator implements ReferenceComparator {
	/**
	 * Next comparator to be used for sorting {@link #nonSortedReferences}.
	 */
	@Nullable private final ReferenceComparator nextComparator;
	/**
	 * The ordering constraint that should be used when preparing nested {@link QueryPlan}. We need to propagate this
	 * constraint later to this point of execution.
	 */
	@Nullable @Getter private EntityProperty orderBy;
	/**
	 * The ordering constraint that should be used when preparing nested {@link QueryPlan}. We need to propagate this
	 * constraint later to this point of execution.
	 */
	@Nullable @Getter private EntityGroupProperty groupOrderBy;
	/**
	 * Locale valid for nested scope (if set on particular scope level).
	 */
	@Nullable @Getter @Setter private Locale locale;
	/**
	 * The array of sorters that should be used for sorting referenced entities. The sorters order is key for sorting
	 * priority (primary, secondary, etc.).
	 */
	@Nullable private SorterTuple sorter;
	/**
	 * The array of sorters that should be used for sorting referenced entities. The sorters order is key for sorting
	 * priority (primary, secondary, etc.).
	 */
	@Nullable private SorterTuple groupSorter;
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
	private IntSet nonSortedReferences;

	@Nonnull
	private static int[] getSortedEntities(@Nonnull int[] filteredEntities, @Nullable SorterTuple theSorter) {
		final Sorter firstApplicableSorter = theSorter == null ?
			null : ConditionalSorter.getFirstApplicableSorter(theSorter.queryContext(), theSorter.sorter());

		if (firstApplicableSorter == null) {
			return filteredEntities;
		} else {
			final int[] result = new int[filteredEntities.length];
			firstApplicableSorter.sortAndSlice(
				theSorter.queryContext(),
				ArrayUtils.isEmpty(filteredEntities) ?
					EmptyFormula.INSTANCE : new ConstantFormula(new BaseBitmap(filteredEntities)),
				0, filteredEntities.length,
				result, 0
			);
			return result;
		}
	}

	public EntityNestedQueryComparator() {
		this.nextComparator = null;
	}

	public EntityNestedQueryComparator(@Nonnull EntityNestedQueryComparator base, @Nullable ReferenceComparator nextComparator) {
		this.nextComparator = nextComparator;
		this.orderBy = base.orderBy;
		this.groupOrderBy = base.groupOrderBy;
		this.sorter = base.sorter;
		this.groupSorter = base.groupSorter;
	}

	public void setOrderBy(@Nullable EntityProperty orderBy) {
		Assert.isTrue(
			this.orderBy == null,
			"The constraint `entityProperty` could be used only once within `referenceProperty` parent container!"
		);
		this.orderBy = orderBy;
	}

	public void setGroupOrderBy(@Nullable EntityGroupProperty groupOrderBy) {
		Assert.isTrue(
			this.groupOrderBy == null,
			"The constraint `entityGroupProperty` could be used only once within `referenceProperty` parent container!"
		);
		Assert.isTrue(
			this.orderBy == null,
			"Sorting by group property in a second dimension has no sense! It will have no effect."
		);
		this.groupOrderBy = groupOrderBy;
	}

	/**
	 * Returns true if the internal datastructures of this comparator are initialized and {@link #compare(ReferenceContract, ReferenceContract)}
	 * can be safely used.
	 */
	public boolean isInitialized() {
		return this.sorter != null || this.groupSorter != null;
	}

	/**
	 * Initializes the query context used for evaluation nested {@link QueryPlan} and the {@link Sorter} that was
	 * identified by this plan.
	 */
	public void setSorter(@Nonnull QueryExecutionContext queryContext, @Nullable Sorter sorter) {
		this.sorter = new SorterTuple(queryContext, sorter);
	}

	/**
	 * Initializes the query context used for evaluation nested {@link QueryPlan} and the {@link Sorter} that was
	 * identified by this plan.
	 */
	public void setGroupSorter(@Nonnull QueryExecutionContext queryContext, @Nullable Sorter sorter) {
		this.groupSorter = new SorterTuple(queryContext, sorter);
	}

	/**
	 * Initializes internal datastructures by using previously identified {@link Sorter}. This method needs to be
	 * preceded by {@link #setSorter(QueryExecutionContext, Sorter)} call.
	 */
	public void setFilteredEntities(
		@Nullable int[] filteredEntities,
		@Nullable int[] filteredEntityGroups,
		@Nonnull Function<Integer, int[]> groupToReferencedEntityIdTranslator
	) {
		this.entitiesFound = filteredEntities;
		this.sortedEntityIndexes = new int[filteredEntities.length];
		for (int i = 0; i < filteredEntities.length; i++) {
			this.sortedEntityIndexes[i] = i;
		}
		if (!ArrayUtils.isEmpty(filteredEntities)) {
			if (groupSorter == null) {
				final int[] sortedEntities = getSortedEntities(filteredEntities, sorter);
				ArrayUtils.sortSecondAlongFirstArray(sortedEntities, this.sortedEntityIndexes);
			} else {
				final int[] sortedGroupEntities = getSortedEntities(filteredEntityGroups, groupSorter);
				final int[] sortedEntities = getSortedEntities(filteredEntities, sorter);
				final IntSet filteredEntitySet = new IntHashSet(filteredEntities.length);
				for (int entityPk : filteredEntities) {
					filteredEntitySet.add(entityPk);
				}
				int peek = -1;
				final int[] combinedSortedEntities = new int[filteredEntities.length];
				for (int sortedGroupEntity : sortedGroupEntities) {
					final int[] groupEntityPks = groupToReferencedEntityIdTranslator.apply(sortedGroupEntity);
					ArrayUtils.sortAlong(sortedEntities, groupEntityPks);
					for (int entityPk : groupEntityPks) {
						if (filteredEntitySet.removeAll(entityPk) > 0) {
							combinedSortedEntities[++peek] = entityPk;
						}
					}
				}
				ArrayUtils.sortSecondAlongFirstArray(combinedSortedEntities, this.sortedEntityIndexes);
			}
		}
	}

	@Override
	public int getNonSortedReferenceCount() {
		return ofNullable(nonSortedReferences)
			.map(IntContainer::size)
			.orElse(0);
	}

	@Nonnull
	@Override
	public ReferenceComparator andThen(@Nonnull ReferenceComparator comparatorForUnknownRecords) {
		return new EntityNestedQueryComparator(this, comparatorForUnknownRecords);
	}

	@Nullable
	@Override
	public ReferenceComparator getNextComparator() {
		return nextComparator;
	}

	@Override
	public int compare(ReferenceContract o1, ReferenceContract o2) {
		final int o1Index = o1 == null ? -1 : Arrays.binarySearch(entitiesFound, o1.getReferencedPrimaryKey());
		final int o2Index = o2 == null ? -1 : Arrays.binarySearch(entitiesFound, o2.getReferencedPrimaryKey());
		final int o1Position = o1Index < 0 ? -1 : this.sortedEntityIndexes[o1Index];
		final int o2Position = o2Index < 0 ? -1 : this.sortedEntityIndexes[o2Index];
		if (o1Position >= 0 && o2Position >= 0) {
			return Integer.compare(o1Position, o2Position);
		} else if (o1Position < 0 && o2Position >= 0) {
			this.nonSortedReferences = ofNullable(this.nonSortedReferences)
				.orElseGet(IntHashSet::new);
			if (o1 != null) {
				this.nonSortedReferences.add(o1.getReferencedPrimaryKey());
			}
			return 1;
		} else if (o1Position >= 0) {
			this.nonSortedReferences = ofNullable(this.nonSortedReferences)
				.orElseGet(IntHashSet::new);
			if (o2 != null) {
				this.nonSortedReferences.add(o2.getReferencedPrimaryKey());
			}
			return -1;
		} else {
			this.nonSortedReferences = ofNullable(this.nonSortedReferences)
				.orElseGet(IntHashSet::new);
			if (o1 != null) {
				this.nonSortedReferences.add(o1.getReferencedPrimaryKey());
			}
			if (o2 != null) {
				this.nonSortedReferences.add(o2.getReferencedPrimaryKey());
			}
			return 0;
		}
	}

	/**
	 * Tuple containing {@link QueryPlanningContext} and {@link Sorter} that needs to be used in combination in this comparator.
	 *
	 * @param queryContext the reference to the query context used for nested query plan evaluation
	 * @param sorter       the reference to created referenced entity sorter
	 */
	private record SorterTuple(
		@Nonnull QueryExecutionContext queryContext,
		@Nullable Sorter sorter
	) {

	}
}
