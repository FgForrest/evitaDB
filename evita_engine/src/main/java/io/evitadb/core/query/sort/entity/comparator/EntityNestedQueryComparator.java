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

package io.evitadb.core.query.sort.entity.comparator;

import com.carrotsearch.hppc.IntContainer;
import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.IntSet;
import com.carrotsearch.hppc.LongIntHashMap;
import io.evitadb.api.query.QueryConstraints;
import io.evitadb.api.query.order.EntityGroupProperty;
import io.evitadb.api.query.order.EntityProperty;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.structure.ReferenceComparator;
import io.evitadb.api.requestResponse.data.structure.ReferenceDecorator;
import io.evitadb.core.query.QueryExecutionContext;
import io.evitadb.core.query.QueryPlan;
import io.evitadb.core.query.QueryPlanningContext;
import io.evitadb.core.query.algebra.Formula;
import io.evitadb.core.query.algebra.base.ConstantFormula;
import io.evitadb.core.query.algebra.base.EmptyFormula;
import io.evitadb.core.query.sort.NoSorter;
import io.evitadb.core.query.sort.Sorter;
import io.evitadb.core.query.sort.Sorter.SortingContext;
import io.evitadb.dataType.Scope;
import io.evitadb.index.bitmap.BaseBitmap;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static java.util.Optional.ofNullable;

/**
 * This comparator allows sorting {@link ReferenceDecorator} according properties on referenced entities.
 * In order to sort efficiently this comparator binds to the nested query execution and prior to calling
 * {@link #compare(ReferenceContract, ReferenceContract)} these two methods needs to be called in specific order:
 *
 * - {@link #setSorters(QueryExecutionContext, Collection)} or {@link #setGroupSorters(QueryExecutionContext, Collection)} which initializes
 * the sorted provided by nested {@link QueryPlan}
 * - {@link #setFilteredEntities(int[], int[], Function, boolean)}  which initializes filtered referenced records and
 * allows to prepare all internal datastructures required form efficient comparisons
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
	@Nullable @Getter private EntityPropertyWithScopes orderBy;
	/**
	 * The ordering constraint that should be used when preparing nested {@link QueryPlan}. We need to propagate this
	 * constraint later to this point of execution.
	 */
	@Nullable @Getter private EntityGroupPropertyWithScopes groupOrderBy;
	/**
	 * Locale valid for nested scope (if set on particular scope level).
	 */
	@Nullable @Getter @Setter private Locale locale;
	/**
	 * The array of sorters that should be used for sorting referenced entities. The sorters order is key for sorting
	 * priority (primary, secondary, etc.).
	 */
	@Nullable private SortersTuple sorter;
	/**
	 * The array of sorters that should be used for sorting referenced entities. The sorters order is key for sorting
	 * priority (primary, secondary, etc.).
	 */
	@Nullable private SortersTuple groupSorter;
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
	 * Positions of the referenced entities ordered by their group, keyed by the (group, referenced entity) pair -
	 * see {@link #toGroupedReferenceKey(int, int)}. It is present only when the group of a referenced entity differs
	 * between the references being ordered, so that the position of a referenced entity depends on the group its
	 * reference holds. The positions in {@link #sortedEntityIndexes} then belong to the references whose group has no
	 * value to be ordered by, and follow all positions in this map.
	 */
	@Nullable private LongIntHashMap groupedReferencePositions;
	/**
	 * Array of references that were not found by the {@link Sorter}.
	 */
	private IntSet nonSortedReferences;

	/**
	 * Sorts the passed entities by the sorters of the passed tuple.
	 *
	 * @param filteredEntities the entity primary keys to sort
	 * @param sortersTuple     the sorters to sort by, NULL keeps the passed order
	 * @param appendUnsorted   TRUE to append the entities no sorter ranked - those lacking every value the sorters
	 *                         order by - after the ranked ones, FALSE to leave them out of the result
	 * @return the sorted entity primary keys
	 */
	@Nonnull
	private static int[] getSortedEntities(
		@Nonnull int[] filteredEntities,
		@Nullable SortersTuple sortersTuple,
		boolean appendUnsorted
	) {
		if (sortersTuple == null) {
			return filteredEntities;
		} else {
			// if the input filtered entities contains duplicates, this will make distinct bitmap
			final Formula input = ArrayUtils.isEmpty(filteredEntities) ?
				EmptyFormula.INSTANCE : new ConstantFormula(new BaseBitmap(filteredEntities));
			final int inputSize = input.compute().size();
			final int[] result = new int[inputSize];
			SortingContext sortingContext = new SortingContext(
				sortersTuple.queryContext(),
				input.compute(),
				0, inputSize,
				0, 0
			);
			for (Sorter sorter : sortersTuple.sorters()) {
				if (!appendUnsorted && sorter instanceof NoSorter) {
					// the plan closes its sorters with the default one, which ranks what the others left unranked
					break;
				}
				sortingContext = sorter.sortAndSlice(sortingContext, result,null);
			}

			// append the rest of the records if not all are sorted
			if (sortingContext.peak() < result.length) {
				if (!appendUnsorted) {
					return Arrays.copyOf(result, sortingContext.peak());
				}
				NoSorter.INSTANCE.sortAndSlice(sortingContext, result, null);
			}

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

	public void setOrderBy(@Nonnull EntityProperty orderBy, @Nonnull Set<Scope> scope) {
		Assert.isTrue(
			this.orderBy == null,
			"The constraint `entityProperty` could be used only once within `referenceProperty` parent container!"
		);
		this.orderBy = new EntityPropertyWithScopes(orderBy, scope);
	}

	public void setGroupOrderBy(@Nonnull EntityGroupProperty groupOrderBy, @Nonnull Set<Scope> scope) {
		Assert.isTrue(
			this.groupOrderBy == null,
			"The constraint `entityGroupProperty` could be used only once within `referenceProperty` parent container!"
		);
		Assert.isTrue(
			this.orderBy == null,
			"Sorting by group property in a second dimension has no sense! It will have no effect."
		);
		this.groupOrderBy = new EntityGroupPropertyWithScopes(groupOrderBy, scope);
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
	public void setSorters(@Nonnull QueryExecutionContext queryContext, @Nonnull Collection<Sorter> sorters) {
		this.sorter = new SortersTuple(queryContext, sorters);
	}

	/**
	 * Initializes the query context used for evaluation nested {@link QueryPlan} and the {@link Sorter} that was
	 * identified by this plan.
	 */
	public void setGroupSorters(@Nonnull QueryExecutionContext queryContext, @Nonnull Collection<Sorter> sorters) {
		this.groupSorter = new SortersTuple(queryContext, sorters);
	}

	/**
	 * Combines the group and the referenced entity primary key into the key of {@link #groupedReferencePositions}.
	 *
	 * @param groupPrimaryKey            primary key of the group the reference holds
	 * @param referencedEntityPrimaryKey primary key of the referenced entity
	 * @return the combined key
	 */
	private static long toGroupedReferenceKey(int groupPrimaryKey, int referencedEntityPrimaryKey) {
		return ((long) groupPrimaryKey << 32) | (referencedEntityPrimaryKey & 0xFFFFFFFFL);
	}

	/**
	 * Initializes internal datastructures by using previously identified {@link Sorter}. This method needs to be
	 * preceded by {@link #setSorters(QueryExecutionContext, Collection)} call.
	 *
	 * @param filteredEntities                    sorted, distinct primary keys of the referenced entities to order
	 * @param filteredEntityGroups                primary keys of the groups of those entities
	 * @param groupToReferencedEntityIdTranslator returns the referenced entities held in the passed group
	 * @param groupAssignmentVaries               TRUE when the group of a referenced entity differs between the
	 *                                            references being ordered - between their owners, or within one -
	 *                                            so the position of a referenced entity must depend on the group of
	 *                                            the reference rather than on the entity alone
	 */
	public void setFilteredEntities(
		@Nonnull int[] filteredEntities,
		@Nonnull int[] filteredEntityGroups,
		@Nonnull Function<Integer, int[]> groupToReferencedEntityIdTranslator,
		boolean groupAssignmentVaries
	) {
		this.groupedReferencePositions = null;
		this.entitiesFound = filteredEntities;
		this.sortedEntityIndexes = new int[filteredEntities.length];
		for (int i = 0; i < filteredEntities.length; i++) {
			this.sortedEntityIndexes[i] = i;
		}
		if (!ArrayUtils.isEmpty(filteredEntities)) {
			if (this.groupSorter == null) {
				final int[] sortedEntities = getSortedEntities(filteredEntities, this.sorter, true);
				ArrayUtils.sortSecondAlongFirstArray(sortedEntities, this.sortedEntityIndexes);
			} else if (groupAssignmentVaries) {
				setFilteredEntitiesByGroupedReference(
					filteredEntities, filteredEntityGroups, groupToReferencedEntityIdTranslator
				);
			} else {
				// a group the sorters cannot rank - one that does not exist, or lacks every value they order by - has
				// no value to be ordered by, so its entities are left to join the ungrouped ones below
				final int[] sortedGroupEntities = getSortedEntities(filteredEntityGroups, this.groupSorter, false);
				final int[] sortedEntities = getSortedEntities(filteredEntities, this.sorter, true);
				final IntSet filteredEntitySet = new IntHashSet(filteredEntities.length);
				for (int entityPk : filteredEntities) {
					filteredEntitySet.add(entityPk);
				}
				int peek = -1;
				final int[] combinedSortedEntities = new int[filteredEntities.length];
				for (int sortedGroupEntity : sortedGroupEntities) {
					// `sortAlong` reorders the array in place, and the translator may hand out an array it keeps - the
					// mapping of a whole batch caches it for every other reader of the reference in the same query
					final int[] groupEntityPks = groupToReferencedEntityIdTranslator.apply(sortedGroupEntity).clone();
					ArrayUtils.sortAlong(sortedEntities, groupEntityPks);
					for (int entityPk : groupEntityPks) {
						if (filteredEntitySet.removeAll(entityPk) > 0) {
							combinedSortedEntities[++peek] = entityPk;
						}
					}
				}
				// the entities no ordered group reached - they have no group, their group was filtered out, or it has
				// no value to be ordered by - lack the value the groups are ordered by, so they follow all grouped
				// ones in the order of the entities; left unset, their zero slots would sort first and shift the
				// position of every entity
				if (peek < combinedSortedEntities.length - 1) {
					for (int entityPk : sortedEntities) {
						if (filteredEntitySet.removeAll(entityPk) > 0) {
							combinedSortedEntities[++peek] = entityPk;
						}
					}
				}
				ArrayUtils.sortSecondAlongFirstArray(combinedSortedEntities, this.sortedEntityIndexes);
			}
		}
	}

	/**
	 * Variant of {@link #setFilteredEntities(int[], int[], Function, boolean)} for the case when the group of
	 * a referenced entity differs between the references being ordered. Each (group, referenced entity) pair gets its
	 * own position in {@link #groupedReferencePositions}: the groups in their order, the entities of a group in the
	 * order of the entities. Every referenced entity also gets a position in {@link #sortedEntityIndexes} after all
	 * of those, in the order of the entities - the position of a reference whose group has no value to be ordered by.
	 *
	 * @param filteredEntities                    sorted, distinct primary keys of the referenced entities to order
	 * @param filteredEntityGroups                primary keys of the groups of those entities
	 * @param groupToReferencedEntityIdTranslator returns the referenced entities held in the passed group
	 */
	private void setFilteredEntitiesByGroupedReference(
		@Nonnull int[] filteredEntities,
		@Nonnull int[] filteredEntityGroups,
		@Nonnull Function<Integer, int[]> groupToReferencedEntityIdTranslator
	) {
		final int[] sortedGroupEntities = getSortedEntities(filteredEntityGroups, this.groupSorter, false);
		final int[] sortedEntities = getSortedEntities(filteredEntities, this.sorter, true);
		final LongIntHashMap positions = new LongIntHashMap(filteredEntities.length);
		int position = 0;
		for (int sortedGroupEntity : sortedGroupEntities) {
			// see `setFilteredEntities` - the translated array must not be reordered in place
			final int[] groupEntityPks = groupToReferencedEntityIdTranslator.apply(sortedGroupEntity).clone();
			ArrayUtils.sortAlong(sortedEntities, groupEntityPks);
			for (int entityPk : groupEntityPks) {
				if (Arrays.binarySearch(filteredEntities, entityPk) >= 0) {
					positions.put(toGroupedReferenceKey(sortedGroupEntity, entityPk), position++);
				}
			}
		}
		ArrayUtils.sortSecondAlongFirstArray(sortedEntities, this.sortedEntityIndexes);
		for (int i = 0; i < this.sortedEntityIndexes.length; i++) {
			this.sortedEntityIndexes[i] += position;
		}
		this.groupedReferencePositions = positions;
	}

	/**
	 * Returns the position of the reference in the requested order, or -1 when the comparator cannot rank it.
	 *
	 * @param reference the reference to locate, may be NULL
	 * @return the position of the reference, or -1
	 */
	private int getPosition(@Nullable ReferenceContract reference) {
		if (reference == null) {
			return -1;
		}
		final int referencedPrimaryKey = reference.getReferencedPrimaryKey();
		if (this.groupedReferencePositions != null) {
			final Optional<GroupEntityReference> group = reference.getGroup();
			if (group.isPresent()) {
				final int index = this.groupedReferencePositions.indexOf(
					toGroupedReferenceKey(group.get().primaryKey(), referencedPrimaryKey)
				);
				if (this.groupedReferencePositions.indexExists(index)) {
					return this.groupedReferencePositions.indexGet(index);
				}
			}
		}
		// both arrays are non-null here - `compare` checks it before asking
		final int index = Arrays.binarySearch(this.entitiesFound, referencedPrimaryKey);
		return index < 0 ? -1 : this.sortedEntityIndexes[index];
	}

	@Override
	public int getNonSortedReferenceCount() {
		return ofNullable(this.nonSortedReferences)
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
		return this.nextComparator;
	}

	@Override
	public int compare(ReferenceContract o1, ReferenceContract o2) {
		Assert.isPremiseValid(this.entitiesFound != null && this.sortedEntityIndexes != null, "The comparator is not initialized!");
		final int o1Position = getPosition(o1);
		final int o2Position = getPosition(o2);
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
	 * @param sorters      the reference to created list of referenced entity sorters
	 */
	private record SortersTuple(
		@Nonnull QueryExecutionContext queryContext,
		@Nonnull Collection<Sorter> sorters
	) {

	}

	/**
	 * This record encapsulates an `EntityProperty` instance along with its associated `Scope` set.
	 *
	 * `EntityPropertyWithScopes` is used to specify the ordering property and the applicable scopes
	 * in operations requiring entity sorting or selection based on the referenced entity's attributes.
	 */
	public record EntityPropertyWithScopes(
		@Nonnull EntityProperty orderBy,
		@Nonnull Set<Scope> scopes
	) {

		/**
		 * Creates a standalone {@link OrderBy} constraint that can be used in the main query.
		 * @return a standalone {@link OrderBy} constraint
		 */
		@Nonnull
		public OrderBy createStandaloneOrderBy() {
			return QueryConstraints.orderBy(this.orderBy.getChildren());
		}

	}

	/**
	 * This record encapsulates an `EntityGroupProperty` instance along with its associated `Scope` set.
	 *
	 * `EntityGroupPropertyWithScopes` is used to specify the ordering property and the applicable scopes
	 * in operations requiring entity sorting or selection based on the referenced entity's attributes.
	 */
	public record EntityGroupPropertyWithScopes(
		@Nonnull EntityGroupProperty orderBy,
		@Nonnull Set<Scope> scopes
	) {

		/**
		 * Creates a standalone {@link OrderBy} constraint that can be used in the main query.
		 * @return a standalone {@link OrderBy} constraint
		 */
		@Nonnull
		public OrderBy createStandaloneOrderBy() {
			return QueryConstraints.orderBy(this.orderBy.getChildren());
		}

	}
}
